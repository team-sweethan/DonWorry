import re
import time
from typing import Any

from app.config import Settings
from app.schemas.report import InvestigationTrace, TraceStep
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool

# 조사 과정 기록(investigationTrace, #294) — 화면 "조사 과정" 탭이 그대로 보여 준다
# 원본 값(금액 · 시각 · 점수)과 프롬프트는 넣지 않고, 코드가 만든 짧은 요약만 남긴다

TOOL_TITLES = {"get_case_payments": "결제 목록 조회", "get_fds_evidence": "FDS 근거 조회"}

# 형식 검사 오류 뒤에 붙는 실패한 AI 문장(: "...")을 지운다 — 검사 규칙만 남긴다
_QUOTED_SENTENCE = re.compile(r':\s*".*"\s*$', flags=re.DOTALL)
MAX_RETRY_REASONS = 3


def elapsed_ms(started: float) -> int:
    return round((time.perf_counter() - started) * 1000)


def tool_title(name: str) -> str:
    return TOOL_TITLES.get(name, f"허용 목록 밖 도구({name[:30]})")


def step(
    kind: str,
    actor: str,
    title: str,
    *,
    duration_ms: int | None = None,
    asked: str | None = None,
    received: str | None = None,
    retry_reason: str | None = None,
) -> TraceStep:
    return TraceStep(
        kind=kind,
        actor=actor,
        title=title,
        durationMs=duration_ms,
        asked=asked,
        received=received,
        retryReason=retry_reason,
    )


# AI가 다음에 무엇을 하기로 했는지 — 도구 이름만 옮긴다
def decided(tool_calls: list[dict[str, Any]]) -> str:
    if not tool_calls:
        return "조사 완료라고 답함"
    return " · ".join(tool_title(call["name"]) for call in tool_calls) + " 선택"


def tool_asked(name: str, args: dict[str, Any]) -> str:
    if name == "get_case_payments":
        return "이 사건의 결제 전체"
    payment_ids = args.get("payment_ids") or []
    if payment_ids:
        return f"결제 {' · '.join(str(p) for p in payment_ids)}의 FDS 근거"
    return "이 사건 모든 결제의 FDS 근거"


# 받은 것 요약 — 건수만 쓴다. 금액 · 점수 같은 원본 값은 보고서 본문에 이미 있다
def tool_received(result: CasePaymentsTool | FdsEvidenceTool) -> str:
    missing = (
        f" · 못 찾은 결제 {len(result.missingPaymentIds)}건" if result.missingPaymentIds else ""
    )
    if isinstance(result, CasePaymentsTool):
        return f"결제 {len(result.payments)}건{missing}"
    rules = sum(len(p.ruleHits) for p in result.payments)
    return f"FDS 평가 {len(result.payments)}건 · 적중 규칙 {rules}개{missing}"


def retry_reason(errors: list[str]) -> str:
    rules = [_QUOTED_SENTENCE.sub("", error).strip() for error in errors]
    unique = list(dict.fromkeys(rule for rule in rules if rule))
    shown = unique[:MAX_RETRY_REASONS]
    more = f" 외 {len(unique) - len(shown)}건" if len(unique) > len(shown) else ""
    return " / ".join(shown) + more


def provider_and_model(settings: Settings) -> tuple[str, str]:
    if settings.llm_provider == "ollama":
        return "OLLAMA", settings.ollama_model
    return "MOCK", "mock"


def build_trace(settings: Settings, steps: list[TraceStep], duration_ms: int) -> InvestigationTrace:
    provider, model = provider_and_model(settings)
    return InvestigationTrace(provider=provider, model=model, durationMs=duration_ms, steps=steps)
