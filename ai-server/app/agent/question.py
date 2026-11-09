import logging
import time
from typing import Any, Protocol

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from pydantic import BaseModel, ValidationError

from app.agent import question_prompts as qp
from app.agent import trace
from app.agent.facts import Fact
from app.agent.llm import LlmClient
from app.agent.narrative import FactSentence, _repeats, _sentence_errors
from app.agent.question_facts import (
    ReportView,
    UnsupportedReport,
    added_facts,
    previous_facts,
    read_report,
    report_checks,
    report_facts,
)
from app.config import Settings
from app.schemas.question import (
    AnswerFact,
    AnswerKind,
    AnswerSentence,
    PreviousTurn,
    QuestionRequest,
    QuestionResult,
    QuestionToolCall,
)
from app.schemas.report import TraceStep
from app.schemas.tools import LinkedPaymentsTool, PaymentHistoryTool

# 사건 질문 · 답변 Agent (ADR-0086, #750)
# 흐름: 사실 목록(코드) → 넓혀 볼지 고르기(AI, 증거 버전이 같을 때만) → 답 쓰기(AI)
#       → 검사(코드) → 고정 문장(거절 · 모름)
# 판정 · 상태 변경은 없다. 실패하면 답 없이 FAILED만 돌려준다 (AGENTS.md 6절)

log = logging.getLogger(__name__)

HISTORY_90 = "get_payment_history_90d"
LINKED_WINDOW = "find_linked_payments_window"
HISTORY_90_DAYS = 90
LINKED_HOURS = frozenset({6, 24})
SPECS = [qp.HISTORY_90_SPEC, qp.LINKED_WINDOW_SPEC]
# 사실 줄의 출처로 남길 Spring Tool 이름
_SPRING_TOOL = {HISTORY_90: "get_payment_history", LINKED_WINDOW: "find_linked_payments"}


# 질문이 쓰는 Spring Tool 두 개 — SpringToolBox가 그대로 맞는다 (사건 번호는 ToolBox가 묶음)
class QuestionToolBox(Protocol):
    def get_payment_history(self, lookback_days: int | None = None) -> PaymentHistoryTool: ...

    def find_linked_payments(self, nearby_hours: int | None = None) -> LinkedPaymentsTool: ...


# 응답이 요청과 다르다 — 예: #751 전 Spring은 모르는 시간 인자를 버리고 1시간 값을 준다
class ToolMismatch(Exception):
    pass


def _title(name: str) -> str:
    return qp.TOOL_TITLES.get(name, trace.tool_title(name))


# 허용 목록 · 값 검사 — 통과하면 실행할 인자, 아니면 None. AI가 지어낸 인자는 버린다
def _allowed_args(name: str, raw: dict[str, Any]) -> dict[str, Any] | None:
    if name == HISTORY_90:
        return {}
    if name == LINKED_WINDOW:
        hours = raw.get("hours")
        if isinstance(hours, int) and not isinstance(hours, bool) and hours in LINKED_HOURS:
            return {"hours": hours}
    return None


def _run(
    name: str, args: dict[str, Any], box: QuestionToolBox, evidence_version: int
) -> PaymentHistoryTool | LinkedPaymentsTool:
    result: PaymentHistoryTool | LinkedPaymentsTool
    if name == HISTORY_90:
        result = box.get_payment_history(HISTORY_90_DAYS)
        if result.lookbackDays != HISTORY_90_DAYS:
            raise ToolMismatch(f"요청 90일, 응답 {result.lookbackDays}일")
    else:
        result = box.find_linked_payments(args["hours"])
        if result.nearbyWindowMinutes != args["hours"] * 60:
            raise ToolMismatch(
                f"요청 {args['hours']}시간, 응답 {result.nearbyWindowMinutes}분 — 다른 범위를 받음"
            )
    # 보고서와 같은 증거 버전의 값만 섞는다 — 조회 사이에 사건이 바뀌었으면 버린다
    if result.evidenceVersion != evidence_version:
        raise ToolMismatch("조회 중 사건 증거 버전이 바뀌었다")
    return result


def _facts_text(facts: list[AnswerFact]) -> str:
    return "\n".join(f"{f.id}: {f.text}" for f in facts)


# 앞 질문은 뜻을 알기 위한 맥락만 — 번호 없이 문장만 보인다 (앞 답 문장을 근거로 인용하지 못하게)
def _previous_text(previous: list[PreviousTurn]) -> str:
    if not previous:
        return qp.NO_PREVIOUS
    lines = []
    for n, turn in enumerate(previous, start=1):
        answer = " ".join(s.text for s in turn.sentences) or "(답 없음)"
        lines.append(f"{n}. 질문: {turn.question}\n   답: {answer}")
    return "\n".join(lines)


def _start(facts: list[AnswerFact], request: QuestionRequest) -> str:
    return qp.CHOOSE_START.format(
        facts=_facts_text(facts),
        previous=_previous_text(request.previous),
        question=request.question,
    )


class _RawAnswer(BaseModel):
    kind: AnswerKind
    sentences: list[FactSentence]


# 답 검사 — 보고서 문장과 같은 규칙(받은 번호만 · 숫자는 인용한 사실에만 · 판단 · 단정 표현 금지)
# 거절 · 모름은 문장을 쓰지 않으므로(코드 고정 문장) 종류만 본다
def validate_answer(
    raw: str, facts: list[AnswerFact], payment_count: int
) -> tuple[_RawAnswer | None, list[str]]:
    try:
        answer = _RawAnswer.model_validate_json(raw)
    except ValidationError as e:
        return None, [f"{'.'.join(map(str, err['loc']))}: {err['msg']}" for err in e.errors()]
    if answer.kind != "ANSWER":
        return answer, []
    if not answer.sentences:
        return None, ["kind가 ANSWER면 sentences를 하나 이상 써라"]
    by_id = {f.id: Fact(f.id, f.source, f.text, "", f.paymentId) for f in facts}
    # "세 번째 결제"를 "3번 결제"로 불러도 되게 결제 순번은 허용한다
    base = set(range(1, payment_count + 1))
    errors = [e for s in answer.sentences for e in _sentence_errors(s, by_id, base)]
    errors += [
        f'같은 문장을 반복하지 마라: "{t}"' for t in _repeats([s.text for s in answer.sentences])
    ]
    if len(answer.sentences) > qp.MAX_SENTENCES:
        errors.append(f"sentences는 {qp.MAX_SENTENCES}문장까지 써라")
    return (answer, []) if not errors else (None, errors)


class _Run:
    def __init__(
        self, request: QuestionRequest, llm: LlmClient, box: QuestionToolBox, settings: Settings
    ) -> None:
        self.request = request
        self.llm = llm
        self.box = box
        self.settings = settings
        self.steps: list[TraceStep] = []
        self.calls: list[QuestionToolCall] = []

    def failed(self, code: str, reason: str) -> QuestionResult:
        return QuestionResult(
            questionId=self.request.questionId,
            status="FAILED",
            kind=None,
            sentences=[],
            checks=[],
            facts=[],
            toolCalls=self.calls,
            trace=self.steps,
            error={"code": code, "reason": reason},
        )

    def run(self) -> QuestionResult:
        try:
            view = read_report(self.request.reportBody)
        except UnsupportedReport as e:
            return self.failed("REPORT_UNSUPPORTED", str(e))
        facts = report_facts(view) + previous_facts(self.request.previous)
        try:
            if self.request.reportEvidenceVersion == self.request.currentEvidenceVersion:
                facts += self.widen(view, facts)
            else:
                self.steps.append(
                    trace.step(
                        "DECIDE",
                        "SYSTEM",
                        "넓혀 보기 건너뜀",
                        received="보고서를 만든 뒤 사건 증거가 바뀌어 저장된 사실로만 답함",
                    )
                )
            return self.write(view, facts)
        except Exception:  # LLM · 서버 장애 — 답 없이 실패로 끝낸다 (자동 판정 없음)
            log.exception("질문 답 실패 questionId=%s", self.request.questionId)
            return self.failed("AGENT_ERROR", "답을 만드는 중 오류가 났다")

    # 넓혀 보기 — AI가 도구 · 값을 고르고 코드가 허용 목록 · 값 · 응답을 검사한다
    def widen(self, view: ReportView, facts: list[AnswerFact]) -> list[AnswerFact]:
        max_calls = self.settings.question_max_tool_calls
        messages: list[BaseMessage] = [
            SystemMessage(content=qp.CHOOSE_SYSTEM.format(max_calls=max_calls)),
            HumanMessage(content=_start(facts, self.request)),
        ]
        added: list[AnswerFact] = []
        used = 0
        while used < max_calls:
            started = time.perf_counter()
            reply = self.llm.choose_tools(messages, SPECS)
            tool_calls = reply.tool_calls if isinstance(reply, AIMessage) else []
            self.steps.append(
                trace.step(
                    "DECIDE",
                    "AI",
                    "넓혀 볼지 고르기",
                    duration_ms=trace.elapsed_ms(started),
                    received=trace.decided(tool_calls, _title)
                    + trace.tokens(getattr(reply, "usage_metadata", None)),
                )
            )
            if not tool_calls:
                break
            messages.append(reply)
            for call in tool_calls:
                used, content = self.call_tool(call, used, view, added)
                name = call["name"]
                messages.append(
                    ToolMessage(content=content, name=name, tool_call_id=call.get("id") or name)
                )
        return added

    def call_tool(
        self, call: Any, used: int, view: ReportView, added: list[AnswerFact]
    ) -> tuple[int, str]:
        name = call["name"]
        raw = call.get("args") or {}
        started = time.perf_counter()
        args = _allowed_args(name, raw)
        if used >= self.settings.question_max_tool_calls:
            status, content, received = "REJECTED", qp.CHOOSE_LIMIT, "한도를 넘어 실행하지 않음"
        elif args is None:
            used += 1
            status = "REJECTED"
            content = f"허용되지 않은 도구 또는 값이다: {name}"
            received = "허용 목록 · 값 밖이라 실행하지 않음"
        else:
            used += 1
            try:
                result = _run(name, args, self.box, self.request.reportEvidenceVersion)
                new = added_facts(view, _SPRING_TOOL[name], result, len(added) + 1)
                added.extend(new)
                status, content = "OK", _facts_text(new) or "결과 없음"
                received = f"새 사실 {len(new)}개"
            except Exception:  # 조회 실패 · 응답이 요청과 다름 — 그 조회만 빼고 진행
                log.warning("넓혀 보기 실패: %s", name, exc_info=True)
                status, content, received = "FAILED", qp.CHOOSE_TOOL_FAILED, "조회 실패 — 없이 진행"
        # 기록에는 도구 설명에 있는 인자만 남긴다
        known = (qp.LINKED_WINDOW_SPEC if name == LINKED_WINDOW else qp.HISTORY_90_SPEC)["function"]
        kept = {k: v for k, v in raw.items() if k in known["parameters"]["properties"]}
        self.calls.append(
            QuestionToolCall(
                toolName=name[:60], args=args if args is not None else kept, status=status
            )
        )
        self.steps.append(
            trace.step(
                "TOOL", "AI", _title(name), duration_ms=trace.elapsed_ms(started), received=received
            )
        )
        return used, content

    def write(self, view: ReportView, facts: list[AnswerFact]) -> QuestionResult:
        messages: list[BaseMessage] = [
            SystemMessage(content=qp.WRITE_SYSTEM),
            HumanMessage(content=_start(facts, self.request)),
        ]
        schema = qp.answer_schema([f.id for f in facts])
        attempts = self.settings.question_max_repairs + 1
        errors: list[str] = []
        for attempt in range(1, attempts + 1):
            started = time.perf_counter()
            raw = self.llm.write_json(messages, schema)
            answer, errors = validate_answer(raw, facts, len(view.paymentAnalyses))
            self.steps.append(
                trace.step(
                    "WRITE",
                    "AI",
                    f"답 쓰기 {attempt}차",
                    duration_ms=trace.elapsed_ms(started),
                    received=("검사 통과" if answer else "검사 실패")
                    + trace.tokens(getattr(raw, "usage", None)),
                    retry_reason=trace.retry_reason(errors) if errors else None,
                )
            )
            if answer is not None:
                return self.finish(answer, view, facts)
            repair = HumanMessage(content=qp.REPAIR.format(errors="; ".join(errors)))
            messages = [*messages, AIMessage(content=raw), repair]
        return self.failed("ANSWER_INVALID", f"검사 {attempts}회 실패: {'; '.join(errors)}")

    def finish(
        self, answer: _RawAnswer, view: ReportView, facts: list[AnswerFact]
    ) -> QuestionResult:
        checks: list[str] = []
        cited: list[AnswerFact] = []
        if answer.kind == "ANSWER":
            sentences = [AnswerSentence(text=s.text, facts=s.facts) for s in answer.sentences]
            ids = list(dict.fromkeys(fid for s in sentences for fid in s.facts))
            by_id = {f.id: f for f in facts}
            cited = [by_id[fid] for fid in ids]
        else:
            # 거절 · 모름은 AI 문장을 버리고 코드 고정 문장 (ADR-0086 4절)
            sentences = [AnswerSentence(text=qp.FIXED_TEXT[answer.kind], facts=[])]
            if answer.kind == "DECLINED_DECISION":
                checks = report_checks(view)
        return QuestionResult(
            questionId=self.request.questionId,
            status="DECLINED" if answer.kind.startswith("DECLINED") else "ANSWERED",
            kind=answer.kind,
            sentences=sentences,
            checks=checks,
            facts=cited,
            toolCalls=self.calls,
            trace=self.steps,
        )


def answer_question(
    request: QuestionRequest, llm: LlmClient, box: QuestionToolBox, settings: Settings
) -> QuestionResult:
    return _Run(request, llm, box, settings).run()
