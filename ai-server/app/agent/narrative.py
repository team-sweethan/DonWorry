import re
from collections.abc import Callable
from typing import Any

from pydantic import BaseModel, ValidationError, field_validator

from app.agent.prompts import EXAMPLE_SENTENCES
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
    # 한계 줄(무엇을 조회했나 · 못 했나)은 AI가 쓰지 않는다 (#355, Case 10 · 12)
    # 코드가 조회 기록으로 쓴다

    check_lists = field_validator("summary", "overallAnalysis", "additionalChecks")(_no_blank)
    check_headline = field_validator("headline")(_not_blank)


_TEXT_LIST = {"type": "array", "items": {"type": "string"}}
# 요약 · 종합 분석 · 확인할 것은 2문장 이상 (#355) — Ollama가 이 형식대로만 출력하게 한다
MIN_SENTENCES = 2
_TEXT_LIST_2 = {**_TEXT_LIST, "minItems": MIN_SENTENCES}


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
            "summary": _TEXT_LIST_2,
            "payments": {
                "type": "object",
                "properties": {payment_id: payment for payment_id in payment_ids},
                "required": payment_ids,
            },
            "overallAnalysis": _TEXT_LIST_2,
            "additionalChecks": _TEXT_LIST_2,
            "recommendationSummary": {"type": "string"},
        },
        "required": [
            "headline",
            "summary",
            "payments",
            "overallAnalysis",
            "additionalChecks",
            "recommendationSummary",
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

# "규칙 외에 다른 근거가 없어" · "다른 근거가 부족하여" (#355)
# 규칙 밖 정황이 있는데 이렇게 쓰면 근거와 어긋난다
_NO_OTHER_EVIDENCE = re.compile(
    r"(?:다른|추가|그\s*외의?|외에|별도의?)\s*(?:판단할\s*|뒷받침할\s*)?(?:근거|정황)[가이]?\s*(?:없|부족)"
)


def _top_level(narrative: Narrative) -> list[str]:
    return [
        *narrative.summary,
        *narrative.overallAnalysis,
        *narrative.additionalChecks,
        narrative.recommendationSummary,
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


# 규칙 밖 정황이 근거에 있는데 이유 · 결론 문장이 "다른 근거가 없다"고 쓴 곳 (#355, Case 8 · 9)
# 근거가 정말 규칙뿐이면(outside 비어 있음) 검사하지 않는다 — 맞는 문장까지 막지 않게
# 요약 · 종합 분석 · 권고 요약은 사건 전체 정황으로,
# 결제별 이유는 그 결제의 정황으로 검사한다 (#355 리뷰)
# by_payment가 없으면 결제별 이유도 사건 전체 정황으로 본다
def evidence_mismatch_errors(
    narrative: Narrative,
    outside: list[str],
    by_payment: dict[str, list[str]] | None = None,
) -> list[str]:
    case_texts = [*narrative.summary, *narrative.overallAnalysis, narrative.recommendationSummary]
    checks = [(outside, text) for text in case_texts]
    for pid, payment in narrative.payments.items():
        found = outside if by_payment is None else by_payment.get(pid, [])
        checks.append((found, f"결제 {pid} reason: {payment.reason}"))
    return [
        f"근거에 규칙 밖 정황({' · '.join(found)})이 있다. "
        f'"다른 근거가 없다/부족하다"고 쓰지 말고 그 정황을 이유에 넣어라: "{text}"'
        for found, text in checks
        if found and _NO_OTHER_EVIDENCE.search(text)
    ]


# 형식 · 내용 검사 — 통과하면 Narrative, 아니면 Qwen에게 돌려줄 오류 문장 목록
# outside_rule_evidence: 근거에 있는 규칙 밖 정황 이름 (#355). None이면 이유 어긋남은 보지 않는다
# outside_by_payment: 결제별 이유를 검사할 그 결제의 정황 (#355 리뷰)
def validate_narrative(
    raw: str,
    payment_ids: list[str],
    allowed_numbers: set[int] | None = None,
    outside_rule_evidence: list[str] | None = None,
    outside_by_payment: dict[str, list[str]] | None = None,
) -> tuple[Narrative | None, list[str]]:
    try:
        narrative = Narrative.model_validate_json(raw)
    except ValidationError as e:
        return None, [f"{'.'.join(map(str, err['loc']))}: {err['msg']}" for err in e.errors()]
    errors: list[str] = []
    if allowed_numbers is not None:
        errors += content_errors(narrative, allowed_numbers)
    errors += style_errors(narrative)
    errors += evidence_mismatch_errors(narrative, outside_rule_evidence or [], outside_by_payment)
    for name, items in (
        ("summary", narrative.summary),
        ("overallAnalysis", narrative.overallAnalysis),
        ("additionalChecks", narrative.additionalChecks),
    ):
        if len(items) < MIN_SENTENCES:
            errors.append(f"{name}는 {MIN_SENTENCES}개 이상 써라 (지금 {len(items)}개)")
    if not narrative.recommendationSummary.strip():
        errors.append("recommendationSummary가 비어 있다")
    expected = set(payment_ids)
    written = set(narrative.payments)
    if expected - written:
        errors.append(f"payments에 빠진 결제: {sorted(expected - written)}")
    if written - expected:
        errors.append(f"payments에 근거에 없는 결제: {sorted(written - expected)}")
    return (narrative, []) if not errors else (None, errors)


# 형식 검사를 통과한 문장에서 틀리거나 겹친 문장을 코드가 뺀다 (#355)
# 다시 쓰기로 돌려보내면 temperature 0인 7B가 같은 문장을 3번 내
# 조사 실패(NARRATIVE_INVALID)가 됐다 (3차 실측)
# 빼도 잃는 게 없는 것만 뺀다 — 결론 · 이유 · 권고 요약은 건드리지 않는다
def _key(text: str) -> str:
    return _SPACING.sub("", text)


def sanitize_narrative(narrative: Narrative) -> tuple[Narrative, int]:
    examples = {_key(text) for text in EXAMPLE_SENTENCES}
    removed = 0

    def keep(items: list[str], drop: Callable[[str], bool]) -> list[str]:
        nonlocal removed
        kept = [item for item in items if not drop(item)]
        removed += len(items) - len(kept)
        return kept

    def copied(text: str) -> bool:
        return _key(text) in examples

    # 1) 샘플 문장을 그대로 쓴 문장 — 이번 사건과 상관없는 사실 (Case 8 "카드사 거절 사유 확인")
    summary = keep(narrative.summary, copied)
    overall = keep(narrative.overallAnalysis, copied)
    checks = keep(narrative.additionalChecks, copied)
    # 2) 요약 · 종합 분석 문장을 결제별 근거에 그대로 복사한 문장 — 화면에 두 번 나온다 (Case 10)
    # 그 문장뿐이면 남긴다 — 빼면 이유는 "정황이 있다"는데 목록이 비어 보였다 (Case 13)
    top = {_key(text) for text in [*summary, *overall]}

    def evidence(items: list[str]) -> list[str]:
        items = keep(items, copied)
        if all(_key(t) in top for t in items):
            return items
        return keep(items, lambda t: _key(t) in top)

    payments = {
        payment_id: payment.model_copy(
            update={
                "riskEvidence": evidence(payment.riskEvidence),
                "normalEvidence": evidence(payment.normalEvidence),
                "unknowns": keep(payment.unknowns, copied),
            }
        )
        for payment_id, payment in narrative.payments.items()
    }
    cleaned = narrative.model_copy(
        update={
            "summary": summary,
            "overallAnalysis": overall,
            "additionalChecks": checks,
            "payments": payments,
        }
    )
    return cleaned, removed


# 문장을 뺀 뒤 요약 · 종합 분석 · 확인할 것이 비면 다시 쓰게 한다 (#355 리뷰)
# 예시 문장만으로 채운 경우다. 비운 채 조립하면 요약 최소 1줄 제약에 걸려 조사가 오류로 끝났다
def emptied_errors(narrative: Narrative) -> list[str]:
    return [
        f"{name}에 출력 예시 문장만 썼다. 예시는 다른 사건이다. 이번 [조사 근거]로 새로 써라"
        for name, items in (
            ("summary", narrative.summary),
            ("overallAnalysis", narrative.overallAnalysis),
            ("additionalChecks", narrative.additionalChecks),
        )
        if not items
    ]
