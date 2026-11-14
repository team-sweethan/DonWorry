import logging
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Literal, TypedDict

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langgraph.graph import END, START, StateGraph

from app.agent import prompts, trace
from app.agent.assemble import analyzable, assemble_report, evidence_id
from app.agent.facts import fact_catalog, prompt_facts, report_facts, signal_id
from app.agent.llm import LlmClient
from app.agent.narrative import (
    Narrative,
    missing_notable,
    narrative_schema,
    validate_narrative,
)
from app.agent.tools import (
    CASE_WIDE,
    EXTRA_TOOLS,
    SUSPICION_ARG,
    ExtraTool,
    ToolBox,
    ToolRecord,
    llm_content,
)
from app.config import Settings
from app.schemas.case_context import CaseContext
from app.schemas.report import (
    BasisSignal,
    ExtraInvestigation,
    FactSentenceItem,
    ReportBody,
    TraceStep,
)
from app.schemas.tools import (
    CasePaymentsTool,
    DecisionBasisTool,
    FdsEvidenceTool,
    FdsPayment,
    LinkedPaymentsTool,
    OrderContextTool,
    PaymentHistoryTool,
    SimilarCasesTool,
    ToolResult,
)

# 조사 Agent — 기본 조회 6개는 코드가 매번 하고, AI는 그 결과를 보고 추가 조사만 고른다 (#578)
# 흐름: 기본 조회 → 추가 조사(추가 도구가 있을 때만) → 사실 문장 쓰기(형식 검사 · 고쳐 쓰기) → 조립
# 단계마다 조사 과정 기록(trace)을 남겨 보고서에 함께 담는다 (#294)
# 노드가 끝날 때마다 지금까지의 기록을 on_progress로 알린다 — 생성 중 화면 표시용 (#322)

log = logging.getLogger(__name__)

ProgressListener = Callable[[list[TraceStep]], None]


class AgentState(TypedDict, total=False):
    messages: list[BaseMessage]
    records: list[ToolRecord]
    tool_calls_used: int
    write_messages: list[BaseMessage]
    repairs: int
    narrative: Narrative | None
    # 안전망으로 요약 뒤에 덧붙일 눈여겨볼 사실 번호 (#591)
    summary_appended: list[str]
    report: ReportBody | None
    failure: dict[str, str] | None
    next: str
    trace: list[TraceStep]
    started: float
    # 이번 사건 의심 목록 (#817) — 기본 조회의 징후로 코드가 만든다. AI 추가 조회는 이 안에서만
    suspicions: list["Suspicion"]
    extras: list[ExtraInvestigation]


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


# 코드가 한 기본 조회의 고객 이력(30일) — 정상 근거 계산용, AI 추가 조사(90일)는 빼고 본다 (#581)
def _base_history(records: list[ToolRecord]) -> PaymentHistoryTool | None:
    found = [
        r.result for r in records if r.filled_by_system and isinstance(r.result, PaymentHistoryTool)
    ]
    return found[-1] if found else None


def _latest_basis(records: list[ToolRecord]) -> DecisionBasisTool | None:
    found = [r.result for r in records if isinstance(r.result, DecisionBasisTool)]
    return found[-1] if found else None


def _latest_order(records: list[ToolRecord]) -> OrderContextTool | None:
    found = [r.result for r in records if isinstance(r.result, OrderContextTool)]
    return found[-1] if found else None


# 기본 조회(앞뒤 1시간)만 — AI가 넓혀 본 6 · 24시간 결과가 표를 덮지 않게 (#817 선택 B)
def _base_linked(records: list[ToolRecord]) -> LinkedPaymentsTool | None:
    found = [
        r.result for r in records if r.filled_by_system and isinstance(r.result, LinkedPaymentsTool)
    ]
    return found[-1] if found else None


LEVEL_KO = {"HIGH_RISK": "고위험", "CAUTION": "주의"}


# 추가 조회를 고르는 AI에게 보여 줄 의심 하나 (#817)
# id(의심-N)를 AI가 고르고, 보고서에는 Spring 징후 key · 이름을 남긴다 (화면 의심 카드와 잇는 키)
@dataclass(frozen=True)
class Suspicion:
    id: str
    key: str
    label: str
    level: str
    payment_count: int
    explained_count: int


# 기본 조회의 징후 · 정상 근거로 의심 목록을 만든다 — 같은 징후는 결제를 묶어 하나
# 다른 징후에 이미 센 것(countedIn)은 뺀다
# 징후를 못 받았으면 빈 목록 — 사건 전체(CASE)만 고를 수 있다
def case_suspicions(records: list[ToolRecord]) -> list[Suspicion]:
    found = [
        r.result for r in records if r.filled_by_system and isinstance(r.result, DecisionBasisTool)
    ]
    if not found:
        return []
    grouped: dict[str, list[BasisSignal]] = {}
    for payment in found[-1].payments:
        for signal in payment.basis.signals:
            if not signal.countedIn:
                grouped.setdefault(signal_id(signal), []).append(signal)
    return [
        Suspicion(
            id=f"의심-{n}",
            key=key,
            label=signals[0].label,
            level=signals[0].level,
            payment_count=len(signals),
            explained_count=sum(1 for s in signals if s.normalReason),
        )
        for n, (key, signals) in enumerate(grouped.items(), start=1)
    ]


def _explained_text(s: Suspicion) -> str:
    if s.explained_count == 0:
        return "없음"
    if s.explained_count == s.payment_count:
        return "있음"
    return f"{s.explained_count}건만 있음"


# [의심 목록] — 이름 · 등급 · 결제 수 · 설명하는 기록 유무. 점수 숫자 · 영문 코드는 넣지 않는다
# 고르는 AI는 화면에 나가는 글을 쓰지 않는다 — 등급은 무엇을 먼저 볼지 고르는 데만 쓴다
def _suspicion_lines(suspicions: list[Suspicion]) -> str:
    if not suspicions:
        return prompts.EXTRA_NO_SUSPICIONS
    return "\n".join(
        f"{s.id}: {s.label} ({LEVEL_KO.get(s.level, '주의')} 징후 · 결제 {s.payment_count}건 · "
        f"설명하는 기록 {_explained_text(s)})"
        for s in suspicions
    )


# 조회 이유 — AI가 쓰지 않고 코드가 의심 이름 · 조회 · 범위로 만든다 (#817 선택 A)
def extra_reason(
    suspicion: Suspicion | None, tool: ExtraTool, args: dict[str, Any], done: bool
) -> str:
    target = f"'{suspicion.label}' 의심을 확인하려고" if suspicion else "사건 전체를 보려고"
    tail = "봤습니다." if done else "보려 했지만 조회하지 못했습니다."
    return f"{target} {tool.describe(args)} {tail}"


# 결제별 FDS 근거 — 같은 결제를 여러 번 봤으면 마지막 값을 쓴다
def _fds_by_payment(records: list[ToolRecord]) -> dict[str, FdsPayment]:
    merged: dict[str, FdsPayment] = {}
    for record in records:
        if isinstance(record.result, FdsEvidenceTool):
            merged.update({payment.paymentId: payment for payment in record.result.payments})
    return merged


def build_graph(
    context: CaseContext,
    llm: LlmClient,
    toolbox: ToolBox,
    settings: Settings,
    extra_tools: tuple[ExtraTool, ...] = EXTRA_TOOLS,
):
    max_calls = settings.agent_max_extra_calls
    extras = {tool.name: tool for tool in extra_tools}
    # 지문은 사건마다 다른 의심 번호(enum)를 뺀 도구 설명으로 잰다
    prompt_fingerprint = prompts.fingerprint([tool.spec for tool in extra_tools], max_calls)

    def title(name: str) -> str:
        return extras[name].title if name in extras else trace.tool_title(name)

    # 기본 조회: 결제 목록 + 모든 결제의 FDS 근거 + 비슷한 과거 사건(#296) + 고객 최근 결제(#338)
    # + 같은 카드 · 기기 · IP를 쓴 다른 결제(#352) + 주문 정보(#634)
    # 결제 목록 · FDS 조회 실패는 조사 실패로 올린다
    def base(state: AgentState) -> AgentState:
        records: list[ToolRecord] = []
        steps = list(state["trace"])
        _base_lookup("get_case_payments", {}, toolbox.get_case_payments, records, steps)
        case_payments = _latest_case_payments(records)
        payment_ids = [p.paymentId for p in case_payments.payments] if case_payments else []
        if payment_ids:
            _base_lookup(
                "get_fds_evidence",
                {"payment_ids": payment_ids},
                lambda: toolbox.get_fds_evidence(payment_ids),
                records,
                steps,
            )
        # 비슷한 사건 · 고객 최근 결제 · 연관 결제는 참고 자료라 실패해도 보고서는 만든다
        # (카드에는 "조회하지 못함")
        _reference_lookup(
            "find_similar_cases", toolbox.find_similar_cases, "비슷한 사건", records, steps
        )
        _reference_lookup(
            "get_payment_history", toolbox.get_payment_history, "고객 최근 결제", records, steps
        )
        _reference_lookup(
            "find_linked_payments",
            toolbox.find_linked_payments,
            "같은 카드 · 기기 · IP 결제",
            records,
            steps,
        )
        _reference_lookup(
            "get_order_context", toolbox.get_order_context, "주문 정보", records, steps
        )
        # 징후 · 정상 근거는 Spring이 계산한다 (#618) — 맨 뒤에 둬 앞 조회의 증거 번호가 바뀌지 않게
        # 못 받으면 결제의 징후 칸이 비고 한계 줄로 알린다 (판정 권고는 만들지 않음, #644)
        _reference_lookup(
            "get_decision_basis", toolbox.get_decision_basis, "징후 · 정상 근거", records, steps
        )
        ready = _ready_to_write(records, steps)
        if ready["next"] != "write" or not extra_tools:
            return ready
        suspicions = case_suspicions(records)
        messages: list[BaseMessage] = [
            SystemMessage(content=prompts.EXTRA_SYSTEM.format(max_calls=max_calls)),
            HumanMessage(
                content=prompts.EXTRA_START.format(
                    case_id=context.case_id,
                    suspicions=_suspicion_lines(suspicions),
                    results=_base_results(records),
                )
            ),
        ]
        return {**ready, "messages": messages, "suspicions": suspicions, "next": "choose"}

    def choose(state: AgentState) -> AgentState:
        started = time.perf_counter()
        ids = [s.id for s in state.get("suspicions") or []]
        reply = llm.choose_tools(state["messages"], [t.case_spec(ids) for t in extra_tools])
        calls = reply.tool_calls if isinstance(reply, AIMessage) else []
        decided = trace.step(
            "DECIDE",
            "AI",
            "추가로 볼 조회 고르기",
            duration_ms=trace.elapsed_ms(started),
            received=trace.decided(calls, title)
            + trace.tokens(getattr(reply, "usage_metadata", None)),
        )
        return {"messages": [*state["messages"], reply], "trace": [*state["trace"], decided]}

    def after_choose(state: AgentState) -> str:
        reply = state["messages"][-1]
        wants_tools = isinstance(reply, AIMessage) and bool(reply.tool_calls)
        return "run_tools" if wants_tools and state["tool_calls_used"] < max_calls else "write"

    def run_tools(state: AgentState) -> AgentState:
        reply = state["messages"][-1]
        assert isinstance(reply, AIMessage)
        messages = list(state["messages"])
        records = list(state["records"])
        used = state["tool_calls_used"]
        steps = list(state["trace"])
        done_extras = list(state.get("extras") or [])
        suspicions = {s.id: s for s in state.get("suspicions") or []}
        for call in reply.tool_calls:
            name = call["name"]
            call_id = call.get("id") or name
            started = time.perf_counter()
            raw = call.get("args") or {}
            asked: str | None = None
            if used >= max_calls:
                content = prompts.EXTRA_LIMIT
                received = "호출 한도를 넘어 실행하지 않음"
            elif name not in extras:
                # 기본 조회 도구도 여기서는 거절한다 — 코드가 이미 했다
                used += 1
                content = f"허용되지 않은 도구다: {name}"
                received = "허용되지 않은 도구라 실행하지 않음"
            elif raw.get(SUSPICION_ARG) not in (*suspicions, CASE_WIDE):
                # 이번 사건 의심 목록 밖 — 없는 의심을 핑계로 조회하지 않는다 (#817)
                used += 1
                content = prompts.EXTRA_BAD_SUSPICION
                received = "의심 목록 밖이라 실행하지 않음"
            elif (args := extras[name].allowed_args(raw)) is None:
                # 허용 값 밖 인자(예: 6 · 24가 아닌 시간) — 지어낸 범위로 조회하지 않는다
                used += 1
                content = f"허용되지 않은 인자다: {name}"
                received = "허용되지 않은 인자라 실행하지 않음"
            else:
                used += 1
                tool = extras[name]
                suspicion = suspicions.get(raw[SUSPICION_ARG])
                try:
                    result = tool.run(toolbox, args)
                    records.append(ToolRecord(name, args, result))
                    content = llm_content(result)
                    received = trace.tool_received(result)
                    done = True
                except Exception:  # 응답 범위가 요청과 다른 경우 포함 — 그 조회만 빼고 진행한다
                    log.warning("추가 조회 실패: %s", name, exc_info=True)
                    content = prompts.EXTRA_TOOL_FAILED
                    received = "조회 실패 — 없이 진행"
                    done = False
                asked = extra_reason(suspicion, tool, args, done)
                done_extras.append(
                    ExtraInvestigation(
                        suspicionKey=suspicion.key if suspicion else CASE_WIDE,
                        suspicionLabel=suspicion.label if suspicion else "사건 전체",
                        toolName=name,
                        title=tool.title,
                        args=args,
                        reason=asked,
                        status="DONE" if done else "FAILED",
                        evidenceId=evidence_id(context, len(records)) if done else None,
                        retrievedAt=records[-1].result.retrievedAt if done else None,
                    )
                )
            messages.append(ToolMessage(content=content, name=name, tool_call_id=call_id))
            steps.append(
                trace.step(
                    "TOOL",
                    "AI",
                    title(name),
                    duration_ms=trace.elapsed_ms(started),
                    asked=asked,
                    received=received,
                )
            )
        return {
            "messages": messages,
            "records": records,
            "tool_calls_used": used,
            "trace": steps,
            "extras": done_extras,
        }

    def after_run(state: AgentState) -> str:
        return "write" if state["tool_calls_used"] >= max_calls else "choose"

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
        # AI에게 주는 사실과 보고서에 저장할 사실을 같은 함수로 만든다 — 번호가 어긋나지 않게 (#548)
        facts = fact_catalog(context, records)
        messages = state.get("write_messages") or [
            SystemMessage(content=prompts.WRITE_SYSTEM),
            HumanMessage(
                content=prompts.WRITE_FACTS.format(facts=prompt_facts(facts, payment_ids))
            ),
        ]
        started = time.perf_counter()
        raw = llm.write_json(messages, narrative_schema(facts))
        narrative, errors = validate_narrative(raw, facts, payment_ids)
        used_tokens = trace.tokens(getattr(raw, "usage", None))
        attempt = state["repairs"] + 1

        def written(received: str, reason: str | None = None) -> list[TraceStep]:
            done = trace.step(
                "WRITE",
                "AI",
                f"사실 문장 쓰기 {attempt}차",
                duration_ms=trace.elapsed_ms(started),
                asked=f"결제 {len(payment_ids)}건의 조사 사실 {len(facts)}개를 이어 요약 쓰기",
                received=received + used_tokens,
                retry_reason=reason,
            )
            return [*state["trace"], done]

        # 걸린 문장은 코드가 몰래 지우지 않고 다시 쓰게 한다 (#548)
        if narrative is not None:
            return {
                "narrative": narrative,
                "write_messages": messages,
                "next": "assemble",
                "trace": written("형식 검사 통과"),
            }
        if state["repairs"] < settings.agent_max_repairs:
            repair = HumanMessage(content=prompts.REPAIR.format(errors="; ".join(errors)))
            return {
                "write_messages": [*messages, AIMessage(content=raw), repair],
                "repairs": state["repairs"] + 1,
                "next": "write",
                "trace": written("형식 검사 실패 → 다시 쓰기", trace.retry_reason(errors)),
            }
        # 안전망 (#591) — 남은 오류가 눈여겨볼 사실 빠짐뿐이면 실패 대신 코드가 그 사실을 덧붙인다
        # 몰래 고치지 않는다: 보고서 · 화면 · 조사 과정에 "시스템이 덧붙임"으로 남긴다 (#548 개정)
        # 숫자 · 형식 오류가 하나라도 남으면 그대로 실패한다
        rescued, _ = validate_narrative(raw, facts, payment_ids, allow_missing_notable=True)
        if rescued is not None:
            appended = missing_notable(rescued, facts)
            return {
                "narrative": rescued,
                "summary_appended": appended,
                "write_messages": messages,
                "next": "assemble",
                "trace": written(
                    f"눈여겨볼 사실만 빠짐 → 요약에 빠진 사실 {len(appended)}개를 시스템이 덧붙임",
                    trace.retry_reason(errors),
                ),
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
        facts = report_facts(fact_catalog(context, records))
        # 추가 조회마다 그 조회 기록에서 나온 사실 번호 (#817) — 화면 의심 카드가 사실로 잇는다
        extras = [
            e.model_copy(update={"factIds": [f.id for f in facts if f.evidenceId == e.evidenceId]})
            if e.status == "DONE"
            else e
            for e in state.get("extras") or []
        ]
        report = assemble_report(
            context,
            case_payments,
            _fds_by_payment(records),
            [FactSentenceItem(text=s.text, factIds=s.facts) for s in narrative.summary],
            narrative.additionalChecks,
            facts,
            records,
            _now(),
            trace.build_trace(settings, list(state["trace"]), 0, prompt_fingerprint),
            _latest_similar(records),
            _latest_history(records),
            _base_linked(records),
            _base_history(records),
            state.get("summary_appended") or [],
            _latest_basis(records),
            _latest_order(records),
            extras,
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
        final = trace.build_trace(
            settings, steps, trace.elapsed_ms(state["started"]), prompt_fingerprint
        )
        return {"report": report.model_copy(update={"investigationTrace": final}), "trace": steps}

    graph = StateGraph(AgentState)
    graph.add_node("base", base)
    graph.add_node("choose", choose)
    graph.add_node("run_tools", run_tools)
    graph.add_node("write", write)
    graph.add_node("assemble", assemble)
    graph.add_edge(START, "base")
    graph.add_conditional_edges(
        "base", lambda s: s["next"], {"choose": "choose", "write": "write", "end": END}
    )
    graph.add_conditional_edges("choose", after_choose, ["run_tools", "write"])
    graph.add_conditional_edges("run_tools", after_run, ["choose", "write"])
    graph.add_conditional_edges(
        "write", lambda s: s["next"], {"write": "write", "assemble": "assemble", "end": END}
    )
    graph.add_edge("assemble", END)
    return graph.compile()


def _lookup_step(name: str, args: dict[str, Any], received: str, started: float) -> TraceStep:
    return trace.step(
        "TOOL",
        "SYSTEM",
        trace.tool_title(name),
        duration_ms=trace.elapsed_ms(started),
        asked=trace.tool_asked(name, args),
        received=received,
    )


# 필수 기본 조회 — 실패하면 예외가 올라가 조사가 실패한다 (일부 근거로 보고서를 만들지 않는다)
def _base_lookup(
    name: str,
    args: dict[str, Any],
    fetch: Callable[[], ToolResult],
    records: list[ToolRecord],
    steps: list[TraceStep],
) -> None:
    started = time.perf_counter()
    result = fetch()
    records.append(ToolRecord(name, args, result, True))
    steps.append(_lookup_step(name, args, trace.tool_received(result), started))


# 참고 자료 기본 조회 — 실패하면 기록 없이 "조회 실패"만 남기고 진행한다
def _reference_lookup(
    name: str,
    fetch: Callable[[], ToolResult],
    label: str,
    records: list[ToolRecord],
    steps: list[TraceStep],
) -> None:
    started = time.perf_counter()
    try:
        result = fetch()
    except Exception:
        log.warning("참고 조회 실패: %s", name, exc_info=True)
        steps.append(_lookup_step(name, {}, f"조회 실패 — {label} 없이 진행", started))
        return
    records.append(ToolRecord(name, {}, result, True))
    steps.append(_lookup_step(name, {}, trace.tool_received(result), started))


BASE_TOOL_NAMES = (
    "get_case_payments",
    "get_fds_evidence",
    "find_similar_cases",
    "get_payment_history",
    "find_linked_payments",
    "get_order_context",
)


# 추가 조사 AI에게 보여 줄 기본 조회 결과 — 못 한 조회도 알린다
def _base_results(records: list[ToolRecord]) -> str:
    done = {r.tool_name: r for r in records}
    return "\n".join(
        f"[{name}] {llm_content(done[name].result) if name in done else '조회하지 못함'}"
        for name in BASE_TOOL_NAMES
    )


# 진행 알림이 실패해도 조사는 계속한다 — 표시용일 뿐이다
def _notify(on_progress: ProgressListener, steps: list[TraceStep]) -> None:
    try:
        on_progress(list(steps))
    except Exception:
        log.warning("진행 알림 실패", exc_info=True)


def run_investigation(
    context: CaseContext,
    llm: LlmClient,
    toolbox: ToolBox,
    settings: Settings,
    on_progress: ProgressListener | None = None,
    extra_tools: tuple[ExtraTool, ...] = EXTRA_TOOLS,
) -> InvestigationOutcome:
    initial: AgentState = {
        "messages": [],
        "records": [],
        "tool_calls_used": 0,
        "repairs": 0,
        "narrative": None,
        "report": None,
        "failure": None,
        "trace": [],
        "started": time.perf_counter(),
        "suspicions": [],
        "extras": [],
    }
    try:
        final = initial
        reported = 0
        # values 모드는 노드가 끝날 때마다 전체 상태를 준다 — 마지막 값이 invoke 결과와 같다
        for state in build_graph(context, llm, toolbox, settings, extra_tools).stream(
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
