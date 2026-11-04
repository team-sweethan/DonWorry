import json

from app.agent.assemble import OUTSIDE_RULE_LABELS, facts_json, outside_rule_evidence
from app.agent.evidence import decision_basis
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool, LinkedPaymentsTool
from tests.test_linked_payments import LINKED

# 같은 카드 · 기기 · IP 징후는 그 결제의 것만 붙인다 (#533)
# 사건 결제 9001만 다른 고객과 카드가 겹치고 9002는 깨끗하다
FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())
CASE = CasePaymentsTool.model_validate(FIXTURE["casePayments"])
FDS = {p.paymentId: p for p in FdsEvidenceTool.model_validate(FIXTURE["fdsEvidence"]).payments}
CLEAN = {
    "provided": True,
    "otherCustomerCount": 0,
    "paymentCount": 0,
    "resultCounts": [],
    "declinedCount": 0,
}
PER_PAYMENT = {
    **LINKED,
    "payments": [
        {"paymentId": "9001", "card": LINKED["card"], "device": CLEAN, "ip": LINKED["ip"]},
        {"paymentId": "9002", "card": CLEAN, "device": CLEAN, "ip": LINKED["ip"]},
    ],
}


def _labels(pid: str, linked: dict) -> list[str]:
    payment = next(p for p in CASE.payments if p.paymentId == pid)
    tool = LinkedPaymentsTool.model_validate(linked)
    return [s.label for s in decision_basis(payment, FDS[pid], CASE, None, tool).signals]


# 결제별 칸이 있으면 카드가 겹친 9001에만 징후를 붙인다
def test_linked_signal_only_on_matching_payment() -> None:
    assert "같은 카드를 다른 고객이 사용" in _labels("9001", PER_PAYMENT)
    assert not [label for label in _labels("9002", PER_PAYMENT) if "같은 카드" in label]


# 예전 Spring 응답(결제별 칸 없음)이면 사건 단위로 붙이되 사실대로 "이 사건 결제 중"이라고 쓴다
def test_linked_signal_without_per_payment_says_case_level() -> None:
    assert "이 사건 결제 중 같은 카드를 다른 고객이 사용" in _labels("9002", LINKED)


# AI에게도 결제마다 그 결제의 연결 결제를 보여 준다 — 9002에 "다른 고객 결제 없음"
def test_facts_show_links_per_payment() -> None:
    facts = json.loads(
        facts_json(CASE, FDS, None, None, LinkedPaymentsTool.model_validate(PER_PAYMENT))
    )

    by_id = {p["결제 ID"]: p for p in facts["결제"]}
    assert by_id["9001"]["같은 카드 · 기기 · IP(이 결제)"]["같은 카드"]["다른 고객 결제"] == "있음"
    assert by_id["9002"]["같은 카드 · 기기 · IP(이 결제)"]["같은 카드"] == "다른 고객 결제 없음"


# 9002의 "다른 근거가 없다" 검사에는 연결 결제를 규칙 밖 정황으로 넣지 않는다
def test_outside_rule_evidence_uses_payment_links() -> None:
    tool = LinkedPaymentsTool.model_validate(PER_PAYMENT)

    assert OUTSIDE_RULE_LABELS["linked"] in outside_rule_evidence(FDS, None, tool, "9001")
    assert OUTSIDE_RULE_LABELS["linked"] not in outside_rule_evidence(FDS, None, tool, "9002")
