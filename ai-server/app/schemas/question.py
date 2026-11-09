from typing import Any, Literal

from pydantic import BaseModel, Field

from app.schemas.report import TraceStep

# Spring ↔ AI 서버 사건 질문 · 답변 계약 (ADR-0086, #750) — JSON 키 그대로라 camelCase를 쓴다
# 동기 호출이다. Spring이 질문 행 · 접근 기록을 먼저 남기고 이 답을 받아 저장한다 (#752)

QUESTION_MAX_LENGTH = 300
PREVIOUS_MAX = 3

# ANSWER: 사실로 답함 / NOT_IN_REPORT: 보고서와 허용 조회로 알 수 없음
# DECLINED_DECISION: 판정 · 권고 질문 / DECLINED_SCOPE: 사건 밖 · 개인정보 질문
AnswerKind = Literal["ANSWER", "NOT_IN_REPORT", "DECLINED_DECISION", "DECLINED_SCOPE"]


class AnswerSentence(BaseModel):
    text: str
    # 근거 사실 번호 — 코드 고정 문장(거절 · 모름)은 빈 목록
    facts: list[str]


# 답이 인용한 사실 원문 — 답만 저장해도 근거를 다시 볼 수 있게 함께 돌려준다
# source: REPORT(보고서 사실) · PAYMENT(보고서 결제 표) · COMPUTED(코드 계산)
#         · ADDED(이번에 새로 조회) · PREVIOUS(앞 질문에서 새로 조회한 값)
class AnswerFact(BaseModel):
    id: str
    text: str
    source: Literal["REPORT", "PAYMENT", "COMPUTED", "ADDED", "PREVIOUS"]
    # 결제 하나의 사실이면 그 결제 ID — 화면이 그 결제 줄로 이동한다
    paymentId: str | None = None
    # 새로 조회한 값만 — 어느 도구로 언제 봤는지
    toolName: str | None = None
    retrievedAt: str | None = None


# 직전 질문 · 답 (같은 보고서 · 같은 직원, 검사를 통과한 것만, 최대 3개)
# 앞 답 문장은 AI에게 맥락으로만 보여 주고 근거로 인용하게 하지 않는다
class PreviousTurn(BaseModel):
    question: str
    kind: AnswerKind
    sentences: list[AnswerSentence]
    # 앞 답이 인용한 사실 — 이 중 새로 조회한 값(ADDED)만 다시 사실로 넣는다
    facts: list[AnswerFact] = Field(default_factory=list)


class QuestionRequest(BaseModel):
    questionId: str
    reportId: str
    caseId: str
    # 보고서를 만든 증거 버전과 지금 사건 증거 버전 — 다르면 새로 조회하지 않는다
    reportEvidenceVersion: int
    currentEvidenceVersion: int
    question: str = Field(min_length=1, max_length=QUESTION_MAX_LENGTH)
    # 저장된 report_body 그대로 — 예전 형식도 올 수 있어 필요한 칸만 골라 읽는다
    reportBody: dict[str, Any]
    previous: list[PreviousTurn] = Field(default_factory=list, max_length=PREVIOUS_MAX)


class QuestionToolCall(BaseModel):
    toolName: str
    args: dict[str, Any]
    # REJECTED: 허용 목록 · 값 밖이라 실행하지 않음 / FAILED: 조회 실패 · 응답이 요청과 다름
    status: Literal["OK", "REJECTED", "FAILED"]


# 실패면 sentences 없이 error(code · reason)만 둔다. 판정 · 상태 변경은 없다
class QuestionResult(BaseModel):
    questionId: str
    status: Literal["ANSWERED", "DECLINED", "FAILED"]
    kind: AnswerKind | None
    sentences: list[AnswerSentence]
    # 판정 질문 거절일 때 보고서의 확인할 것 전체 (체크 상태는 화면에만 있어 서버가 모른다)
    checks: list[str]
    facts: list[AnswerFact]
    toolCalls: list[QuestionToolCall]
    trace: list[TraceStep]
    error: dict[str, str] | None = None
