import json
from datetime import datetime, timedelta

from app.agent.assemble import (
    BURST_KEY,
    EARLIER_KEY,
    drop_rule_only,
    facts_json,
    outside_rule_evidence,
)
from app.agent.narrative import narrative_schema, sanitize_narrative, validate_narrative
from app.schemas.tools import (
    CasePaymentsTool,
    HistoryPayment,
    LinkedPaymentsTool,
    PaymentHistoryTool,
    StatusCount,
)
from tests.fakes import narrative
from tests.test_linked_payments import FIXTURE, LINKED

# #355 판단 문장 다듬기 — 고객 수 · 결제 수 혼동, 샘플 복사, 이유 어긋남, 직전 연속 결제, 문장 수


def _linked_facts(linked: dict) -> dict:
    cases = CasePaymentsTool.model_validate(FIXTURE["casePayments"])
    facts = json.loads(facts_json(cases, {}, None, None, LinkedPaymentsTool.model_validate(linked)))
    return facts["같은 카드 · 기기 · IP를 쓴 다른 고객 결제(최근 30일)"]


# 고객 1명이 결제 3건을 했으면 AI 입력에 "결제 여러 건"이 있어야 한다
# 없으면 "결제 한 건"으로 쓴다 (Case 9)
def test_linked_facts_separate_customer_and_payment_counts() -> None:
    case9 = {**LINKED, "card": {**LINKED["card"], "otherCustomerCount": 1, "paymentCount": 3}}

    card = _linked_facts(case9)["같은 카드"]

    assert card["다른 고객"] == "한 명"
    assert card["그 고객들의 결제"] == "여러 건"


PAYMENTS = ["9001", "9002"]


# 샘플 문장("카드사에 거절 사유 문의")을 그대로 쓰면 코드가 뺀다 (Case 8)
# 사건과 무관한 사실이 보고서에 들어가지 않게
# 다시 쓰기로 돌려보내면 같은 문장을 반복해 조사가 실패했다 (3차 실측)
def test_example_sentence_copied_is_dropped() -> None:
    raw = narrative(PAYMENTS, additionalChecks=["고객 본인 확인", "카드사에 거절 사유 문의"])
    parsed, errors = validate_narrative(raw, PAYMENTS, None)

    cleaned, removed = sanitize_narrative(parsed)

    assert errors == []
    assert cleaned.additionalChecks == ["고객 본인 확인"]
    assert removed == 1


# 같은 카드를 쓴 다른 고객이 있는데 이유가 "다른 근거가 없다"면 다시 쓰게 한다 (Case 8 · 9)
def test_reason_no_other_evidence_with_linked_payments_is_repaired() -> None:
    written = json.loads(narrative(PAYMENTS))
    written["payments"]["9001"]["reason"] = (
        "규칙 적중 외에 다른 근거가 없어 추가 확인이 필요합니다."
    )
    outside = outside_rule_evidence({}, None, LinkedPaymentsTool.model_validate(LINKED))

    _, errors = validate_narrative(json.dumps(written, ensure_ascii=False), PAYMENTS, None, outside)

    assert any("규칙 밖 정황" in e and "같은 카드" in e for e in errors)


# 근거가 정말 규칙뿐이면 "다른 근거가 없다"는 맞는 말이라 통과한다
# 매번 다시 쓰다 조사 실패하지 않게
def test_reason_no_other_evidence_when_only_rules_passes() -> None:
    written = json.loads(narrative(PAYMENTS))
    written["payments"]["9001"]["reason"] = (
        "규칙 적중 외에 다른 근거가 없어 추가 확인이 필요합니다."
    )
    no_match = {"provided": True, "otherCustomerCount": 0, "paymentCount": 0}
    none = {**LINKED, "card": {**LINKED["card"], **no_match}, "device": LINKED["device"]}
    outside = outside_rule_evidence({}, None, LinkedPaymentsTool.model_validate(none))

    narrative_obj, errors = validate_narrative(
        json.dumps(written, ensure_ascii=False), PAYMENTS, None, outside
    )

    assert outside == []
    assert errors == []
    assert narrative_obj is not None


# 사건 결제 앞뒤 10분 안의 고객 결제는 "같은 연속 결제"로,
# 그 밖은 "그 전 결제"로 나눠 AI에게 보여 준다
def test_history_split_into_just_before_burst_and_earlier() -> None:
    cases = CasePaymentsTool.model_validate(FIXTURE["casePayments"])
    first = min(p.requestedAt for p in cases.payments)
    at = datetime.fromisoformat(first)
    history = PaymentHistoryTool(
        caseId="101",
        evidenceVersion=2,
        source="P5",
        retrievedAt=first,
        lookbackDays=30,
        payments=[
            HistoryPayment(
                paymentId="8102",
                amount=600000,
                paymentStatus="CAPTURED",
                requestedAt=(at - timedelta(seconds=7)).isoformat(),
            ),
            HistoryPayment(
                paymentId="8001",
                amount=580000,
                paymentStatus="CAPTURED",
                requestedAt=(at - timedelta(days=20)).isoformat(),
            ),
        ],
        statusCounts=[StatusCount(paymentStatus="CAPTURED", count=2)],
        truncated=False,
    )

    facts = json.loads(facts_json(cases, {}, None, history, None))["고객 최근 결제(이 사건 밖)"]

    assert [p["금액(원)"] for p in facts[BURST_KEY]] == [600000]
    assert [p["금액(원)"] for p in facts[EARLIER_KEY]] == [580000]


# 요약 · 종합 분석 · 확인할 것이 1개면 다시 쓰게 한다 — 출력 형식도 2개 이상을 요구한다
def test_summary_overall_checks_need_two_items() -> None:
    short = narrative(PAYMENTS, overallAnalysis=["짧은 간격의 반복 결제입니다."])

    _, errors = validate_narrative(short, PAYMENTS, None)
    schema = narrative_schema(PAYMENTS)["properties"]

    assert any("overallAnalysis는 2개 이상" in e for e in errors)
    assert all(
        schema[k]["minItems"] == 2 for k in ("summary", "overallAnalysis", "additionalChecks")
    )


# --- 실측(Case 10)에서 나온 결함의 재현 테스트 (버그 수정 예외, #355) ---


# 요약 문장을 결제별 근거에 그대로 복사하면 결제별 근거 쪽만 코드가 뺀다 (Case 10)
# 이유 = 권고 요약은 결제가 하나일 때 원래 같은 내용이라 건드리지 않는다
def test_sentence_copied_between_summary_and_payment_is_dropped_from_payment() -> None:
    written = json.loads(narrative(PAYMENTS))
    copied = "다른 고객이 같은 카드를 사용한 결제가 있었습니다."
    written["summary"] = [written["summary"][0], copied]
    written["payments"]["9001"]["riskEvidence"] = [copied, "처음 쓰는 카드로 결제했습니다."]
    written["recommendationSummary"] = written["payments"]["9001"]["reason"]
    parsed, errors = validate_narrative(json.dumps(written, ensure_ascii=False), PAYMENTS, None)

    cleaned, _ = sanitize_narrative(parsed)

    assert errors == []
    assert copied in cleaned.summary
    assert cleaned.payments["9001"].riskEvidence == ["처음 쓰는 카드로 결제했습니다."]
    assert cleaned.recommendationSummary == cleaned.payments["9001"].reason


# "○○ 규칙에 걸렸습니다"로만 된 위험 근거는 규칙 카드와 같은 내용이라 코드가 뺀다 (Case 10)
def test_rule_only_risk_sentence_is_dropped() -> None:
    assert drop_rule_only(
        [
            "고액 결제와 단시간 반복 결제 규칙에 걸렸습니다.",
            "고액 결제 규칙이 적중했습니다.",
            "고액 결제 규칙에 걸렸고 처음 쓰는 카드라 카드 주인 확인이 필요합니다.",
        ]
    ) == ["고액 결제 규칙에 걸렸고 처음 쓰는 카드라 카드 주인 확인이 필요합니다."]


# 3차 실측 실패 재현 — Case 10 모양의 출력(요약 문장을 위험 근거에 복사, 이유 = 권고 요약)을 Qwen이
# 3번 똑같이 내면 칸끼리 복사 검사 때문에 NARRATIVE_INVALID가 됐다. 다시 쓰기 없이 성공해야 한다
def test_case10_like_output_succeeds_without_rewrite() -> None:
    from app.agent.graph import run_investigation
    from app.config import Settings
    from tests.fakes import ScriptedLlm, call, context

    written = json.loads(narrative(PAYMENTS))
    shared = "다른 고객이 같은 카드를 사용한 결제가 있었습니다."
    reason = "규칙에 걸렸고 다른 고객 결제가 있어 추가 확인이 필요합니다."
    written["summary"] = ["고액 결제와 단시간 반복 결제 규칙에 걸렸습니다.", shared]
    for pid in PAYMENTS:
        written["payments"][pid]["riskEvidence"] = [
            "고액 결제와 단시간 반복 결제 규칙에 걸렸습니다.",
            shared,
        ]
        written["payments"][pid]["reason"] = reason
    written["recommendationSummary"] = reason
    llm = ScriptedLlm(
        turns=[[call("get_case_payments")]], outputs=[json.dumps(written, ensure_ascii=False)]
    )

    outcome = run_investigation(context(), llm, _toolbox_with_linked(), Settings(_env_file=None))

    assert outcome.status == "SUCCEEDED"
    assert llm.write_calls == 1
    first = outcome.report.paymentAnalyses[0]
    assert first.riskEvidence == []
    assert first.reason == reason
    assert outcome.report.recommendation.summary == reason


def _toolbox_with_linked():
    from tests.test_linked_payments import _toolbox

    return _toolbox(LinkedPaymentsTool.model_validate(LINKED))


# 4차 수정 재현 (Case 12)
# AI가 쓴 "다른 고객의 결제 이력 정보는 알 수 없었습니다"가 한계 줄에 들어갔다
# 실제로는 같은 카드 결제 12건을 조회했다. 한계 줄은 조회 기록으로 코드만 쓴다
def test_limitations_come_only_from_tool_records() -> None:
    from app.agent.assemble import LINKED_PRIVACY
    from app.agent.graph import run_investigation
    from app.config import Settings
    from tests.fakes import ScriptedLlm, call, context

    written = json.loads(narrative(PAYMENTS))
    written["limitations"] = ["다른 고객의 결제 이력 정보는 이번 조사에서 알 수 없었습니다."]
    llm = ScriptedLlm(
        turns=[[call("get_case_payments")]], outputs=[json.dumps(written, ensure_ascii=False)]
    )

    outcome = run_investigation(context(), llm, _toolbox_with_linked(), Settings(_env_file=None))

    limitations = outcome.report.recommendation.limitations
    assert outcome.status == "SUCCEEDED"
    assert not any("알 수 없었습니다" in line for line in limitations)
    assert LINKED_PRIVACY in limitations
    assert "limitations" not in narrative_schema(PAYMENTS)["properties"]
