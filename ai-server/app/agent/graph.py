from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Literal, TypedDict

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langgraph.graph import END, START, StateGraph

from app.agent import prompts
from app.agent.assemble import analyzable, assemble_report, facts_json
from app.agent.llm import LlmClient
from app.agent.narrative import Narrative, narrative_schema, validate_narrative
from app.agent.tools import ALLOWED_TOOL_NAMES, TOOL_SPECS, ToolBox, ToolRecord
from app.config import Settings
from app.schemas.case_context import CaseContext
from app.schemas.report import ReportBody
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool, FdsPayment

# 조사 Agent — Qwen이 다음에 볼 도구를 고르고, 코드는 허용 · 횟수 · 필수 근거 · 기록을 지킨다
# 흐름: 고르기 ↔ 실행 → 필수 근거 확인 → 판단 문장 쓰기(형식 검사 · 고쳐 쓰기) → 보고서 조립


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
        reply = llm.choose_tools(state["messages"], TOOL_SPECS)
        return {"messages": [*state["messages"], reply]}

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
        for call in reply.tool_calls:
            name = call["name"]
            call_id = call.get("id") or name
            if used >= max_calls:
                content = '도구 호출 한도를 넘었다. 더 부르지 말고 "조사 완료"라고 답하라.'
            elif name not in ALLOWED_TOOL_NAMES:
                used += 1
                content = f"허용되지 않은 도구다: {name}"
            else:
                used += 1
                try:
                    result = _execute(toolbox, name, call.get("args") or {})
                    records.append(ToolRecord(name, dict(call.get("args") or {}), result))
                    content = result.model_dump_json()
                except Exception as e:  # 도구 실패는 Qwen에게 알려 다시 고르게 한다
                    content = f"도구 실행 실패: {e}"
            messages.append(ToolMessage(content=content, name=name, tool_call_id=call_id))
        return {"messages": messages, "records": records, "tool_calls_used": used}

    def after_run(state: AgentState) -> str:
        return "check" if state["tool_calls_used"] >= max_calls else "choose"

    # 필수 근거: 결제 목록 + 모든 결제의 FDS 근거
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
        if not missing:
            return _ready_to_write(records)
        can_nudge = state["nudges"] < settings.agent_max_nudges
        if can_nudge and state["tool_calls_used"] < max_calls:
            nudge = HumanMessage(content=prompts.NUDGE_MISSING.format(missing=", ".join(missing)))
            return {
                "messages": [*state["messages"], nudge],
                "nudges": state["nudges"] + 1,
                "next": "choose",
            }
        if case_payments is None:
            case_payments = toolbox.get_case_payments()
            records.append(ToolRecord("get_case_payments", {}, case_payments, True))
        unseen = [
            p.paymentId for p in case_payments.payments if p.paymentId not in _fds_seen(records)
        ]
        if unseen:
            filled = toolbox.get_fds_evidence(unseen)
            records.append(ToolRecord("get_fds_evidence", {"payment_ids": unseen}, filled, True))
        return _ready_to_write(records)

    def _ready_to_write(records: list[ToolRecord]) -> AgentState:
        case_payments = _latest_case_payments(records)
        if case_payments is None or not case_payments.payments:
            failure = {"code": "NO_PAYMENTS", "reason": "조사할 결제가 없다"}
            return {"records": records, "failure": failure, "next": "end"}
        return {"records": records, "next": "write"}

    def write(state: AgentState) -> AgentState:
        records = state["records"]
        case_payments = _latest_case_payments(records)
        assert case_payments is not None
        payment_ids = [p.paymentId for p in case_payments.payments if analyzable(p)]
        messages = state.get("write_messages") or [
            SystemMessage(content=prompts.WRITE_SYSTEM),
            HumanMessage(
                content=prompts.WRITE_FACTS.format(
                    facts=facts_json(case_payments, _fds_by_payment(records))
                )
            ),
        ]
        raw = llm.write_json(messages, narrative_schema(payment_ids))
        narrative, errors = validate_narrative(raw, payment_ids)
        if narrative is not None:
            return {"narrative": narrative, "write_messages": messages, "next": "assemble"}
        if state["repairs"] < settings.agent_max_repairs:
            repair = HumanMessage(content=prompts.REPAIR.format(errors="; ".join(errors)))
            return {
                "write_messages": [*messages, AIMessage(content=raw), repair],
                "repairs": state["repairs"] + 1,
                "next": "write",
            }
        reason = f"형식 검사 {settings.agent_max_repairs + 1}회 실패: {'; '.join(errors)}"
        return {"failure": {"code": "NARRATIVE_INVALID", "reason": reason}, "next": "end"}

    # 조사 중 Case 증거 버전이 바뀌었으면 낡은 근거로 성공 보고서를 만들지 않는다 (#250 리뷰)
    def assemble(state: AgentState) -> AgentState:
        records = state["records"]
        stale = [r for r in records if r.result.evidenceVersion != context.evidence_version]
        if stale:
            failure = {"code": "EVIDENCE_STALE", "reason": "조사 중 Case 증거 버전이 바뀌었다"}
            return {"failure": failure}
        case_payments = _latest_case_payments(records)
        narrative = state["narrative"]
        assert case_payments is not None and narrative is not None
        report = assemble_report(
            context, case_payments, _fds_by_payment(records), narrative, records, _now()
        )
        return {"report": report}

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


def _execute(
    toolbox: ToolBox, name: str, args: dict[str, Any]
) -> CasePaymentsTool | FdsEvidenceTool:
    if name == "get_case_payments":
        return toolbox.get_case_payments()
    payment_ids = args.get("payment_ids") or None
    return toolbox.get_fds_evidence([str(p) for p in payment_ids] if payment_ids else None)


def run_investigation(
    context: CaseContext, llm: LlmClient, toolbox: ToolBox, settings: Settings
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
    }
    try:
        final = build_graph(context, llm, toolbox, settings).invoke(
            initial, {"recursion_limit": 80}
        )
    except Exception as e:  # LLM · 도구 서버 장애 — 자동 판정 없이 실패로 끝낸다
        return InvestigationOutcome("FAILED", None, {"code": "AGENT_ERROR", "reason": str(e)})
    records = final.get("records", [])
    if final.get("report") is not None:
        return InvestigationOutcome("SUCCEEDED", final["report"], None, records)
    failure = final.get("failure") or {"code": "UNKNOWN", "reason": "보고서를 만들지 못했다"}
    return InvestigationOutcome("FAILED", None, failure, records)
