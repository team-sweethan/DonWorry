import logging
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Literal, TypedDict

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langgraph.graph import END, START, StateGraph

from app.agent import prompts, trace
from app.agent.assemble import (
    analyzable,
    assemble_report,
    fact_numbers,
    facts_json,
    outside_rule_evidence,
)
from app.agent.llm import LlmClient
from app.agent.narrative import (
    Narrative,
    emptied_errors,
    narrative_schema,
    sanitize_narrative,
    validate_narrative,
)
from app.agent.tools import (
    ALLOWED_TOOL_NAMES,
    TOOL_ARGS,
    TOOL_SPECS,
    ToolBox,
    ToolRecord,
    llm_content,
)
from app.config import Settings
from app.schemas.case_context import CaseContext
from app.schemas.report import ReportBody, TraceStep
from app.schemas.tools import (
    CasePaymentsTool,
    FdsEvidenceTool,
    FdsPayment,
    LinkedPaymentsTool,
    PaymentHistoryTool,
    SimilarCasesTool,
    ToolResult,
)

# 조사 Agent — Qwen이 다음에 볼 도구를 고르고, 코드는 허용 · 횟수 · 필수 근거 · 기록을 지킨다
# 흐름: 고르기 ↔ 실행 → 필수 근거 확인 → 판단 문장 쓰기(형식 검사 · 고쳐 쓰기) → 보고서 조립
# 단계마다 조사 과정 기록(trace)을 남겨 보고서에 함께 담는다 (#294)
# 노드가 끝날 때마다 지금까지의 기록을 on_progress로 알린다 — 생성 중 화면 표시용 (#322)

log = logging.getLogger(__name__)

ProgressListener = Callable[[list[TraceStep]], None]


class AgentState(TypedDict, total=False):
    messages: list[BaseMessage]
    records: list[ToolRecord]
    tool_calls_used: int
    nudges: int
    write_messages: list[BaseMessage]
    repairs: int
    narrative: Narrative | None
    report: ReportBody | None
    failure: dict[str, str] | None
    next: str
    trace: list[TraceStep]
    started: float


@dataclass
class InvestigationOutcome:
    status: Literal["SUCCEEDED", "FAILED"]
    report: ReportBody | None
    error: dict[str, str] | None
    records: list[ToolRecord] = field(default_factory=list)


def _now() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def _latest_case_payments(records: list[ToolRecord]) -> CasePaymentsTool | None:
    found = [r.result for r in records if isinstance(r.result, CasePaymentsTool)]
    return found[-1] if found else None


def _latest_similar(records: list[ToolRecord]) -> SimilarCasesTool | None:
    found = [r.result for r in records if isinstance(r.result, SimilarCasesTool)]
    return found[-1] if found else None


def _latest_history(records: list[ToolRecord]) -> PaymentHistoryTool | None:
    found = [r.result for r in records if isinstance(r.result, PaymentHistoryTool)]
    return found[-1] if found else None


def _latest_linked(records: list[ToolRecord]) -> LinkedPaymentsTool | None:
    found = [r.result for r in records if isinstance(r.result, LinkedPaymentsTool)]
    return found[-1] if found else None


# 결제별 FDS 근거 — 같은 결제를 여러 번 봤으면 마지막 값을 쓴다
def _fds_by_payment(records: list[ToolRecord]) -> dict[str, FdsPayment]:
    merged: dict[str, FdsPayment] = {}
    for record in records:
        if isinstance(record.result, FdsEvidenceTool):
            merged.update({payment.paymentId: payment for payment in record.result.payments})
    return merged


# FDS 도구로 확인을 시도한 결제 — 평가가 없어 missingPaymentIds로 온 결제도 "본 것"이다
def _fds_seen(records: list[ToolRecord]) -> set[str]:
    seen: set[str] = set()
    for record in records:
        if isinstance(record.result, FdsEvidenceTool):
            seen |= {payment.paymentId for payment in record.result.payments}
            seen |= set(record.result.missingPaymentIds)
    return seen


def build_graph(context: CaseContext, llm: LlmClient, toolbox: ToolBox, settings: Settings):
    max_calls = settings.agent_max_tool_calls

    def choose(state: AgentState) -> AgentState:
        started = time.perf_counter()
        reply = llm.choose_tools(state["messages"], TOOL_SPECS)
        calls = reply.tool_calls if isinstance(reply, AIMessage) else []
        decided = trace.step(
            "DECIDE",
            "AI",
            "다음에 볼 조회 고르기",
            duration_ms=trace.elapsed_ms(started),
            received=trace.decided(calls) + trace.tokens(getattr(reply, "usage_metadata", None)),
        )
        return {"messages": [*state["messages"], reply], "trace": [*state["trace"], decided]}

    def after_choose(state: AgentState) -> str:
        reply = state["messages"][-1]
        wants_tools = isinstance(reply, AIMessage) and bool(reply.tool_calls)
        return "run_tools" if wants_tools and state["tool_calls_used"] < max_calls else "check"

    def run_tools(state: AgentState) -> AgentState:
        reply = state["messages"][-1]
        assert isinstance(reply, AIMessage)
        messages = list(state["messages"])
        records = list(state["records"])
        used = state["tool_calls_used"]
        steps = list(state["trace"])
        for call in reply.tool_calls:
            name = call["name"]
            call_id = call.get("id") or name
            started = time.perf_counter()
            asked: str | None = None
            if used >= max_calls:
                content = '도구 호출 한도를 넘었다. 더 부르지 말고 "조사 완료"라고 답하라.'
                received = "호출 한도를 넘어 실행하지 않음"
            elif name not in ALLOWED_TOOL_NAMES:
                used += 1
                content = f"허용되지 않은 도구다: {name}"
                received = "허용되지 않은 도구라 실행하지 않음"
            else:
                used += 1
                # 도구 설명에 없는 인자(예: Qwen이 지어낸 case_id)는 쓰지도 기록하지도 않는다
                args = {k: v for k, v in (call.get("args") or {}).items() if k in TOOL_ARGS[name]}
                # 표시용 요약도 try 안에서 만든다 (#294 리뷰)
                # 인자 형식이 틀려도 기존 도구 실패 복구로 간다
                try:
                    asked = trace.tool_asked(name, args)
                    result = _execute(toolbox, name, args)
                    records.append(ToolRecord(name, args, result))
                    content = llm_content(result)
                    received = trace.tool_received(result)
                except Exception as e:  # 도구 실패는 Qwen에게 알려 다시 고르게 한다
                    content = f"도구 실행 실패: {e}"
                    received = "실행 실패 — AI에게 알리고 다시 고르게 함"
            messages.append(ToolMessage(content=content, name=name, tool_call_id=call_id))
            steps.append(
                trace.step(
                    "TOOL",
                    "AI",
                    trace.tool_title(name),
                    duration_ms=trace.elapsed_ms(started),
                    asked=asked,
                    received=received,
                )
            )
        return {"messages": messages, "records": records, "tool_calls_used": used, "trace": steps}

    def after_run(state: AgentState) -> str:
        return "check" if state["tool_calls_used"] >= max_calls else "choose"

    # 필수 근거: 결제 목록 + 모든 결제의 FDS 근거 + 비슷한 과거 사건(#296) + 고객 최근 결제(#338)
    # + 같은 카드 · 기기 · IP를 쓴 다른 결제(#352)
    # 빠지면 Qwen에게 알리고, 그래도 안 보면 코드가 보충한다
    def check(state: AgentState) -> AgentState:
        records = list(state["records"])
        case_payments = _latest_case_payments(records)
        missing: list[str] = []
        if case_payments is None:
            missing.append("결제 목록(get_case_payments)")
        else:
            unseen = [
                p.paymentId for p in case_payments.payments if p.paymentId not in _fds_seen(records)
            ]
            missing += [f"결제 {payment_id}의 FDS 근거(get_fds_evidence)" for payment_id in unseen]
        if _latest_similar(records) is None:
            missing.append("비슷한 과거 사건(find_similar_cases)")
        if _latest_history(records) is None:
            missing.append("고객 최근 결제(get_payment_history)")
        if _latest_linked(records) is None:
            missing.append("같은 카드 · 기기 · IP 결제(find_linked_payments)")
        steps = list(state["trace"])
        if not missing:
            return _ready_to_write(records, steps)
        can_nudge = state["nudges"] < settings.agent_max_nudges
        if can_nudge and state["tool_calls_used"] < max_calls:
            nudge = HumanMessage(content=prompts.NUDGE_MISSING.format(missing=", ".join(missing)))
            asked_again = trace.step(
                "NUDGE", "SYSTEM", "빠진 근거 다시 요청", asked=", ".join(missing)
            )
            return {
                "messages": [*state["messages"], nudge],
                "nudges": state["nudges"] + 1,
                "next": "choose",
                "trace": [*steps, asked_again],
            }
        # AI가 끝까지 안 본 근거는 코드가 조회한다 — 기록에는 시스템 보충으로 남긴다
        if case_payments is None:
            started = time.perf_counter()
            case_payments = toolbox.get_case_payments()
            records.append(ToolRecord("get_case_payments", {}, case_payments, True))
            steps.append(_system_fill("get_case_payments", {}, case_payments, started))
        unseen = [
            p.paymentId for p in case_payments.payments if p.paymentId not in _fds_seen(records)
        ]
        if unseen:
            started = time.perf_counter()
            filled = toolbox.get_fds_evidence(unseen)
            records.append(ToolRecord("get_fds_evidence", {"payment_ids": unseen}, filled, True))
            steps.append(_system_fill("get_fds_evidence", {"payment_ids": unseen}, filled, started))
        # 비슷한 사건 · 고객 최근 결제 · 연관 결제는 참고 자료라
        # 보충 조회가 실패해도 보고서는 만든다
        # (카드에는 "조회하지 못함")
        if _latest_similar(records) is None:
            _fill_reference(
                "find_similar_cases", toolbox.find_similar_cases, "비슷한 사건", records, steps
            )
        if _latest_history(records) is None:
            _fill_reference(
                "get_payment_history", toolbox.get_payment_history, "고객 최근 결제", records, steps
            )
        if _latest_linked(records) is None:
            _fill_reference(
                "find_linked_payments",
                toolbox.find_linked_payments,
                "같은 카드 · 기기 · IP 결제",
                records,
                steps,
            )
        return _ready_to_write(records, steps)

    # 결제 정보 · FDS 점수가 하나라도 비면 성공 보고서를 만들지 않는다
    # 일부만 합친 총액 등 틀린 사실이 확정값처럼 보이기 때문
    # 기존 고정 보고서(#250)와 같은 계약이다. 직원은 수동 판정으로 간다 (fds 정본 02 §2.5)
    def _ready_to_write(records: list[ToolRecord], steps: list[TraceStep]) -> AgentState:
        case_payments = _latest_case_payments(records)
        if case_payments is None or not case_payments.payments:
            failure = {"code": "NO_PAYMENTS", "reason": "조사할 결제가 없다"}
            return {"records": records, "failure": failure, "next": "end", "trace": steps}
        fds = _fds_by_payment(records)
        incomplete = [
            p.paymentId
            for p in case_payments.payments
            if not analyzable(p)
            or fds.get(p.paymentId) is None
            or fds[p.paymentId].riskScore is None
        ]
        if incomplete:
            reason = f"결제 정보 또는 FDS 점수를 확인하지 못한 결제: {incomplete}"
            failure = {"code": "EVIDENCE_MISSING", "reason": reason}
            return {"records": records, "failure": failure, "next": "end", "trace": steps}
        return {"records": records, "next": "write", "trace": steps}

    def write(state: AgentState) -> AgentState:
        records = state["records"]
        case_payments = _latest_case_payments(records)
        assert case_payments is not None
        payment_ids = [p.paymentId for p in case_payments.payments if analyzable(p)]
        messages = state.get("write_messages") or [
            SystemMessage(content=prompts.WRITE_SYSTEM),
            HumanMessage(
                content=prompts.WRITE_FACTS.format(
                    facts=facts_json(
                        case_payments,
                        _fds_by_payment(records),
                        _latest_similar(records),
                        _latest_history(records),
                        _latest_linked(records),
                    )
                )
            ),
        ]
        started = time.perf_counter()
        raw = llm.write_json(messages, narrative_schema(payment_ids))
        # 연관 결제(#352)는 AI에게 숫자를 보여 주지 않으므로 허용 숫자에 넣지 않는다
        allowed = fact_numbers(
            case_payments,
            _fds_by_payment(records),
            _latest_similar(records),
            _latest_history(records),
        )
        # 규칙 밖 정황이 있으면 "다른 근거가 없다"는 이유를 막는다 (#355)
        fds, history, linked = (
            _fds_by_payment(records),
            _latest_history(records),
            _latest_linked(records),
        )
        outside = outside_rule_evidence(fds, history, linked)
        by_payment = {pid: outside_rule_evidence(fds, history, linked, pid) for pid in payment_ids}
        narrative, errors = validate_narrative(raw, payment_ids, allowed, outside, by_payment)
        used_tokens = trace.tokens(getattr(raw, "usage", None))
        attempt = state["repairs"] + 1

        def written(received: str, reason: str | None = None) -> list[TraceStep]:
            done = trace.step(
                "WRITE",
                "AI",
                f"판단 문장 쓰기 {attempt}차",
                duration_ms=trace.elapsed_ms(started),
                asked=f"결제 {len(payment_ids)}건의 근거 요약을 보고 판단 문장 쓰기",
                received=received + used_tokens,
                retry_reason=reason,
            )
            return [*state["trace"], done]

        if narrative is not None:
            # 틀리거나 겹친 문장은 다시 쓰게 하지 않고 코드가 뺀다 (#355)
            narrative, removed = sanitize_narrative(narrative)
            # 빼고 나서 필수 목록이 비었으면 그때만 다시 쓰게 한다 (#355 리뷰)
            errors = emptied_errors(narrative)
            if not errors:
                passed = "형식 검사 통과" + (f" · 문장 {removed}개 뺌" if removed else "")
                return {
                    "narrative": narrative,
                    "write_messages": messages,
                    "next": "assemble",
                    "trace": written(passed),
                }
        if state["repairs"] < settings.agent_max_repairs:
            repair = HumanMessage(content=prompts.REPAIR.format(errors="; ".join(errors)))
            return {
                "write_messages": [*messages, AIMessage(content=raw), repair],
                "repairs": state["repairs"] + 1,
                "next": "write",
                "trace": written("형식 검사 실패 → 다시 쓰기", trace.retry_reason(errors)),
            }
        reason = f"형식 검사 {settings.agent_max_repairs + 1}회 실패: {'; '.join(errors)}"
        return {
            "failure": {"code": "NARRATIVE_INVALID", "reason": reason},
            "next": "end",
            "trace": written("형식 검사 실패 → 조사 실패", trace.retry_reason(errors)),
        }

    # 조사 중 Case 증거 버전이 바뀌었으면 낡은 근거로 성공 보고서를 만들지 않는다 (#250 리뷰)
    def assemble(state: AgentState) -> AgentState:
        started = time.perf_counter()
        records = state["records"]
        stale = [r for r in records if r.result.evidenceVersion != context.evidence_version]
        if stale:
            failure = {"code": "EVIDENCE_STALE", "reason": "조사 중 Case 증거 버전이 바뀌었다"}
            return {"failure": failure}
        case_payments = _latest_case_payments(records)
        narrative = state["narrative"]
        assert case_payments is not None and narrative is not None
        report = assemble_report(
            context,
            case_payments,
            _fds_by_payment(records),
            narrative,
            records,
            _now(),
            trace.build_trace(settings, list(state["trace"]), 0),
            _latest_similar(records),
            _latest_history(records),
            _latest_linked(records),
        )
        # 조립까지 끝난 뒤 걸린 시간을 재서 마지막 단계와 총 시간을 채운다
        steps = [
            *state["trace"],
            trace.step(
                "ASSEMBLE",
                "SYSTEM",
                "보고서 조립 · 증거 버전 확인",
                duration_ms=trace.elapsed_ms(started),
                received=f"증거 v{context.evidence_version} 기준으로 조립",
            ),
        ]
        final = trace.build_trace(settings, steps, trace.elapsed_ms(state["started"]))
        return {"report": report.model_copy(update={"investigationTrace": final}), "trace": steps}

    graph = StateGraph(AgentState)
    graph.add_node("choose", choose)
    graph.add_node("run_tools", run_tools)
    graph.add_node("check", check)
    graph.add_node("write", write)
    graph.add_node("assemble", assemble)
    graph.add_edge(START, "choose")
    graph.add_conditional_edges("choose", after_choose, ["run_tools", "check"])
    graph.add_conditional_edges("run_tools", after_run, ["choose", "check"])
    graph.add_conditional_edges(
        "check", lambda s: s["next"], {"choose": "choose", "write": "write", "end": END}
    )
    graph.add_conditional_edges(
        "write", lambda s: s["next"], {"write": "write", "assemble": "assemble", "end": END}
    )
    graph.add_edge("assemble", END)
    return graph.compile()


def _system_fill(
    name: str,
    args: dict[str, Any],
    result: ToolResult,
    started: float,
) -> TraceStep:
    return trace.step(
        "TOOL",
        "SYSTEM",
        f"{trace.tool_title(name)} (시스템 보충)",
        duration_ms=trace.elapsed_ms(started),
        asked=trace.tool_asked(name, args),
        received=trace.tool_received(result),
    )


# 참고 자료 보충 조회 — 실패하면 기록 없이 "조회 실패"만 남기고 진행한다
def _fill_reference(
    name: str,
    fetch: Callable[[], ToolResult],
    label: str,
    records: list[ToolRecord],
    steps: list[TraceStep],
) -> None:
    started = time.perf_counter()
    try:
        result = fetch()
        records.append(ToolRecord(name, {}, result, True))
        steps.append(_system_fill(name, {}, result, started))
    except Exception:
        steps.append(
            trace.step(
                "TOOL",
                "SYSTEM",
                f"{trace.tool_title(name)} (시스템 보충)",
                duration_ms=trace.elapsed_ms(started),
                asked=trace.tool_asked(name, {}),
                received=f"조회 실패 — {label} 없이 진행",
            )
        )


# 진행 알림이 실패해도 조사는 계속한다 — 표시용일 뿐이다
def _notify(on_progress: ProgressListener, steps: list[TraceStep]) -> None:
    try:
        on_progress(list(steps))
    except Exception:
        log.warning("진행 알림 실패", exc_info=True)


def _execute(toolbox: ToolBox, name: str, args: dict[str, Any]) -> ToolResult:
    if name == "get_case_payments":
        return toolbox.get_case_payments()
    if name == "find_similar_cases":
        return toolbox.find_similar_cases()
    if name == "get_payment_history":
        return toolbox.get_payment_history()
    if name == "find_linked_payments":
        return toolbox.find_linked_payments()
    payment_ids = args.get("payment_ids") or None
    return toolbox.get_fds_evidence([str(p) for p in payment_ids] if payment_ids else None)


def run_investigation(
    context: CaseContext,
    llm: LlmClient,
    toolbox: ToolBox,
    settings: Settings,
    on_progress: ProgressListener | None = None,
) -> InvestigationOutcome:
    initial: AgentState = {
        "messages": [
            SystemMessage(
                content=prompts.INVESTIGATE_SYSTEM.format(max_calls=settings.agent_max_tool_calls)
            ),
            HumanMessage(content=prompts.INVESTIGATE_START.format(case_id=context.case_id)),
        ],
        "records": [],
        "tool_calls_used": 0,
        "nudges": 0,
        "repairs": 0,
        "narrative": None,
        "report": None,
        "failure": None,
        "trace": [],
        "started": time.perf_counter(),
    }
    try:
        final = initial
        reported = 0
        # values 모드는 노드가 끝날 때마다 전체 상태를 준다 — 마지막 값이 invoke 결과와 같다
        for state in build_graph(context, llm, toolbox, settings).stream(
            initial, {"recursion_limit": 80}, stream_mode="values"
        ):
            final = state
            steps = state.get("trace", [])
            if on_progress is not None and len(steps) != reported:
                reported = len(steps)
                _notify(on_progress, steps)
    except Exception as e:  # LLM · 도구 서버 장애 — 자동 판정 없이 실패로 끝낸다
        return InvestigationOutcome("FAILED", None, {"code": "AGENT_ERROR", "reason": str(e)})
    records = final.get("records", [])
    if final.get("report") is not None:
        return InvestigationOutcome("SUCCEEDED", final["report"], None, records)
    failure = final.get("failure") or {"code": "UNKNOWN", "reason": "보고서를 만들지 못했다"}
    return InvestigationOutcome("FAILED", None, failure, records)
