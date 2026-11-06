import json

from app.schemas.tools import CasePaymentsTool, LinkedPaymentsTool
from tests.fakes import catalog
from tests.test_linked_payments import LINKED

# 같은 카드 · 기기 · IP는 그 결제의 것만 보여 준다 (#533) — 기준표 징후 쪽은 Spring(#618)
# 사건 결제 9001만 다른 고객과 카드가 겹치고 9002는 깨끗하다
FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())
CASE = CasePaymentsTool.model_validate(FIXTURE["casePayments"])
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


# AI에게도 결제마다 그 결제의 연결 결제를 보여 준다 — 9002(두 번째 결제)에 "다른 고객 결제 없음"
# 사실 문장은 결제 번호 대신 순서로 부른다 (#568)
def test_facts_show_links_per_payment() -> None:
    facts = catalog(CASE, LinkedPaymentsTool.model_validate(PER_PAYMENT))

    texts = [f.text for f in facts if f.topic == "linked"]
    assert texts[0].startswith("첫 번째 결제 · 같은 카드: 최근 30일 다른 고객 2명 · 결제 3건")
    assert "두 번째 결제 · 같은 카드: 최근 30일 다른 고객 결제 없음" in texts
