import re
from typing import Any

from pydantic import BaseModel, ValidationError, field_validator

from app.schemas.report import Recommendation

# Qwen이 쓰는 판단 문장 부분 — 결제 ID · 금액 · 점수 같은 사실은 넣지 않는다
# 사실은 코드가 Tool 기록에서 채운다


def _no_blank(items: list[str]) -> list[str]:
    if any(not item.strip() for item in items):
        raise ValueError("빈 문장이 있다")
    return items


def _not_blank(text: str) -> str:
    if not text.strip():
        raise ValueError("빈 문장이다")
    return text


class PaymentNarrative(BaseModel):
    riskEvidence: list[str]
    normalEvidence: list[str]
    unknowns: list[str]
    recommendation: Recommendation
    # 권고 이유 한 문장 — 화면 결제별 권고 옆에 붙는다
    reason: str

    check_lists = field_validator("riskEvidence", "normalEvidence", "unknowns")(_no_blank)
    check_reason = field_validator("reason")(_not_blank)


class Narrative(BaseModel):
    # 직원이 가장 먼저 읽는 결론 한 줄 — 보고서 verdict.headline이 된다
    headline: str
    summary: list[str]
    payments: dict[str, PaymentNarrative]
    overallAnalysis: list[str]
    additionalChecks: list[str]
    recommendationSummary: str
    limitations: list[str]

    check_lists = field_validator("summary", "overallAnalysis", "additionalChecks", "limitations")(
        _no_blank
    )
    check_headline = field_validator("headline")(_not_blank)


_TEXT_LIST = {"type": "array", "items": {"type": "string"}}


# 결제 ID를 키로 고정한 JSON Schema — Ollama format으로 넘겨 모델이 정확히 이 결제들만 쓰게 한다
def narrative_schema(payment_ids: list[str]) -> dict[str, Any]:
    payment = {
        "type": "object",
        "properties": {
            "riskEvidence": _TEXT_LIST,
            "normalEvidence": _TEXT_LIST,
            "unknowns": _TEXT_LIST,
            "recommendation": {
                "type": "string",
                "enum": ["ACCEPT_REVIEW", "REJECT_REVIEW", "NEEDS_CHECK"],
            },
            "reason": {"type": "string"},
        },
        "required": ["riskEvidence", "normalEvidence", "unknowns", "recommendation", "reason"],
    }
    return {
        "type": "object",
        "properties": {
            "headline": {"type": "string"},
            "summary": _TEXT_LIST,
            "payments": {
                "type": "object",
                "properties": {payment_id: payment for payment_id in payment_ids},
                "required": payment_ids,
            },
            "overallAnalysis": _TEXT_LIST,
            "additionalChecks": _TEXT_LIST,
            "recommendationSummary": {"type": "string"},
            "limitations": _TEXT_LIST,
        },
        "required": [
            "headline",
            "summary",
            "payments",
            "overallAnalysis",
            "additionalChecks",
            "recommendationSummary",
            "limitations",
        ],
    }


# 문장에 나오면 안 되는 코드값 — 밑줄이 든 영문 코드(REJECT_REVIEW · HIGH_AMOUNT 등)와 상태 코드
# \b는 한글도 글자로 봐서 "REJECT_REVIEW를"을 놓친다 — 영문 · 숫자 · 밑줄 기준으로만 경계를 본다
_CODE = re.compile(
    r"(?<![A-Za-z0-9_])(?:[A-Z]+(?:_[A-Z]+)+"
    r"|HELD|REVIEW|DENY|DENIED|ALLOW|CAPTURED|VOIDED|PENDING|DECLINED|EXPIRED)(?![A-Za-z0-9_])"
)

# 확률처럼 단정하는 표현 — FDS 점수는 확률이 아니고 판정은 직원이 한다 (fds 정본 기획 2.6)
_ASSERTIVE = re.compile(
    r"확률|%|가능성이\s*(?:매우\s*)?(?:높|크)|사기(?:로|임이|가)\s*(?:판단|확실|분명|확정|추정)"
)

# 숫자와 뒤 단위 — "65만 원"은 650000으로 읽는다
_NUMBER = re.compile(r"(\d[\d,]*)(\s*만)?\s*(건|회|번|개|명|분|시간|일|차)?")

# 수량 · 시간 단위 앞의 작은 수는 근거에 없어도 된다 ("2건", "3회")
# 점수 · 금액("5점")은 예외가 아니다
_SMALL_COUNT = 10

# 한자 · 일본 문자 — Qwen이 "新規 카드"처럼 섞어 쓴다
_HANJA = re.compile(r"[぀-ヿ㐀-䶿一-鿿豈-﫿]+")

# 영어 단어 — 화면에서 쓰는 약어와 시연 표기(mock 조사 · Qwen 미사용)만 허용한다
_ENGLISH = re.compile(r"(?<![A-Za-z])[A-Za-z]{2,}(?![A-Za-z])")
ALLOWED_WORDS = frozenset({"FDS", "IP", "OTP", "VPN", "ID", "AI", "mock", "Qwen"})

# 결론 한 줄 — 화면 결론 카드에 한 줄로 들어가는 길이
HEADLINE_MAX = 60
_SENTENCE_BREAK = re.compile(r"[.!?。]\s*\S")

# 반복 비교용 — 띄어쓰기 · 문장부호를 빼고 같은 문장인지 본다
_SPACING = re.compile(r"[\s.,·!?~\"'()\[\]]")


def _top_level(narrative: Narrative) -> list[str]:
    return [
        *narrative.summary,
        *narrative.overallAnalysis,
        *narrative.additionalChecks,
        narrative.recommendationSummary,
        *narrative.limitations,
    ]


def _payment_texts(payment: PaymentNarrative) -> list[str]:
    return [*payment.riskEvidence, *payment.normalEvidence, *payment.unknowns, payment.reason]


def _sentences(narrative: Narrative) -> list[str]:
    texts = [narrative.headline, *_top_level(narrative)]
    for payment in narrative.payments.values():
        texts += _payment_texts(payment)
    return texts


# 같은 묶음 안에서 반복된 문장 — 결제가 여럿이면 결제마다 같은 근거를 쓰는 것은 허용한다
def _repeats(texts: list[str]) -> list[str]:
    seen: set[str] = set()
    repeated: list[str] = []
    for text in texts:
        key = _SPACING.sub("", text)
        if key and key in seen:
            repeated.append(text)
        seen.add(key)
    return repeated


# 근거와 맞춰 볼 숫자 — 수량 · 시간 단위가 붙은 작은 수는 뺀다
def _numbers(text: str) -> set[int]:
    found: set[int] = set()
    for digits, man, count_unit in _NUMBER.findall(text):
        plain = digits.replace(",", "")
        value = int(plain) * (10000 if man else 1) if plain else 0
        if count_unit and value <= _SMALL_COUNT:
            continue
        found.add(value)
    return found


# 내용 검사 — 근거에 없는 숫자, 코드값, 확률 단정 표현을 찾는다
def content_errors(narrative: Narrative, allowed_numbers: set[int]) -> list[str]:
    errors: list[str] = []
    for text in _sentences(narrative):
        unknown = sorted(_numbers(text) - allowed_numbers)
        if unknown:
            errors.append(f'근거에 없는 숫자 {unknown}: "{text}"')
        if code := _CODE.search(text):
            errors.append(f'코드값 "{code.group()}"을 한국어로 풀어 써라: "{text}"')
        if phrase := _ASSERTIVE.search(text):
            errors.append(f'확률 · 단정 표현 "{phrase.group()}"을 쓰지 마라: "{text}"')
    return errors


# 문장 품질 검사 — 한자 · 영어 단어 · 반복 문장 · 결론 길이 (숫자 근거와 상관없이 항상 본다)
def style_errors(narrative: Narrative) -> list[str]:
    errors: list[str] = []
    for text in _sentences(narrative):
        if hanja := _HANJA.search(text):
            errors.append(f'한자 · 일본 문자 "{hanja.group()}"를 한국어로 바꿔 써라: "{text}"')
        if _CODE.search(text):
            continue  # 코드값은 content_errors가 알린다
        words = [w for w in _ENGLISH.findall(text) if w not in ALLOWED_WORDS]
        if words:
            errors.append(f'영어 단어 {words}를 한국어로 바꿔 써라: "{text}"')
    groups = [_top_level(narrative)] + [_payment_texts(p) for p in narrative.payments.values()]
    for texts in groups:
        errors += [f'같은 문장을 반복하지 마라: "{text}"' for text in _repeats(texts)]
    headline = narrative.headline.strip()
    if len(headline) > HEADLINE_MAX:
        errors.append(f'headline은 {HEADLINE_MAX}자 이내로 줄여라: "{headline}"')
    if _SENTENCE_BREAK.search(headline):
        errors.append(f'headline은 한 문장으로 써라: "{headline}"')
    return errors


# 형식 · 내용 검사 — 통과하면 Narrative, 아니면 Qwen에게 돌려줄 오류 문장 목록
def validate_narrative(
    raw: str, payment_ids: list[str], allowed_numbers: set[int] | None = None
) -> tuple[Narrative | None, list[str]]:
    try:
        narrative = Narrative.model_validate_json(raw)
    except ValidationError as e:
        return None, [f"{'.'.join(map(str, err['loc']))}: {err['msg']}" for err in e.errors()]
    errors: list[str] = []
    if allowed_numbers is not None:
        errors += content_errors(narrative, allowed_numbers)
    errors += style_errors(narrative)
    if not narrative.summary:
        errors.append("summary가 비어 있다")
    if not narrative.recommendationSummary.strip():
        errors.append("recommendationSummary가 비어 있다")
    expected = set(payment_ids)
    written = set(narrative.payments)
    if expected - written:
        errors.append(f"payments에 빠진 결제: {sorted(expected - written)}")
    if written - expected:
        errors.append(f"payments에 근거에 없는 결제: {sorted(written - expected)}")
    return (narrative, []) if not errors else (None, errors)
