from typing import Any

from pydantic import BaseModel, ValidationError, field_validator

from app.schemas.report import Recommendation

# Qwen이 쓰는 판단 문장 부분 — 결제 ID · 금액 · 점수 같은 사실은 넣지 않는다
# 사실은 코드가 Tool 기록에서 채운다


def _no_blank(items: list[str]) -> list[str]:
    if any(not item.strip() for item in items):
        raise ValueError("빈 문장이 있다")
    return items


class PaymentNarrative(BaseModel):
    riskEvidence: list[str]
    normalEvidence: list[str]
    unknowns: list[str]
    recommendation: Recommendation

    check_lists = field_validator("riskEvidence", "normalEvidence", "unknowns")(_no_blank)


class Narrative(BaseModel):
    summary: list[str]
    payments: dict[str, PaymentNarrative]
    overallAnalysis: list[str]
    additionalChecks: list[str]
    recommendationSummary: str
    limitations: list[str]

    check_lists = field_validator("summary", "overallAnalysis", "additionalChecks", "limitations")(
        _no_blank
    )


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
        },
        "required": ["riskEvidence", "normalEvidence", "unknowns", "recommendation"],
    }
    return {
        "type": "object",
        "properties": {
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
            "summary",
            "payments",
            "overallAnalysis",
            "additionalChecks",
            "recommendationSummary",
            "limitations",
        ],
    }


# 형식 검사 — 통과하면 Narrative, 아니면 Qwen에게 돌려줄 오류 문장 목록
def validate_narrative(raw: str, payment_ids: list[str]) -> tuple[Narrative | None, list[str]]:
    try:
        narrative = Narrative.model_validate_json(raw)
    except ValidationError as e:
        return None, [f"{'.'.join(map(str, err['loc']))}: {err['msg']}" for err in e.errors()]
    errors: list[str] = []
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
