import json
from datetime import datetime, timedelta

from app.agent.narrative import narrative_schema, validate_narrative
from app.agent.prompts import WRITE_EXAMPLES
from app.schemas.tools import (
    CasePaymentsTool,
    FdsEvidenceTool,
    HistoryPayment,
    LinkedPaymentsTool,
    PaymentHistoryTool,
    StatusCount,
)
from tests.fakes import catalog, narrative
from tests.test_linked_payments import FIXTURE, LINKED

# #355 문장 다듬기 중 #548 뒤에도 남는 것
# 고객 수 · 결제 수 혼동, 샘플 복사, 연속 결제 나누기, 문장 수
# 걸린 문장은 코드가 지우지 않고 다시 쓰게 한다 (#548)

CASES = CasePaymentsTool.model_validate(FIXTURE["casePayments"])
FDS = FdsEvidenceTool.model_validate(FIXTURE["fdsEvidence"])
PAYMENTS = ["9001", "9002"]


# 고객 1명이 결제 3건을 했으면 사실에 "1명 · 결제 3건"이 함께 있어야 한다
# 고객 수만 주면 "결제 한 건"으로 바꿔 썼다 (Case 9)
def test_linked_facts_separate_customer_and_payment_counts() -> None:
    case9 = {**LINKED, "card": {**LINKED["card"], "otherCustomerCount": 1, "paymentCount": 3}}

    facts = catalog(CASES, LinkedPaymentsTool.model_validate(case9))

    card = next(f.text for f in facts if "같은 카드" in f.text)
    assert "다른 고객 1명 · 결제 3건" in card


# 샘플 문장("카드사에 거절 사유 문의")을 그대로 쓰면 다시 쓰게 한다 (Case 8)
# 사건과 무관한 사실이 보고서에 들어가지 않게
def test_example_sentence_copied_is_rewritten() -> None:
    facts = catalog(CASES, FDS)
    copied = WRITE_EXAMPLES[0]["출력"]["additionalChecks"][0]
    raw = narrative(PAYMENTS, additionalChecks=["고객 본인 확인", copied])

    parsed, errors = validate_narrative(raw, facts, PAYMENTS)

    assert parsed is None
    assert any("예시 문장" in e for e in errors)


# 확인할 것이 1개면 다시 쓰게 한다 — 출력 형식도 2개 이상을 요구한다
# 요약은 1~3문장 (#559 — 사실이 적은 사건에 억지로 2문장을 채우지 않게)
def test_summary_and_checks_need_two_items() -> None:
    facts = catalog(CASES, FDS)
    short = narrative(PAYMENTS, additionalChecks=["고객 본인 확인"])

    _, errors = validate_narrative(short, facts, PAYMENTS)
    schema = narrative_schema(facts)["properties"]

    assert any("additionalChecks는 2개 이상" in e for e in errors)
    assert (schema["summary"]["minItems"], schema["summary"]["maxItems"]) == (1, 3)
    assert schema["additionalChecks"]["minItems"] == 2


def _history(*rows: tuple[str, int, datetime]) -> PaymentHistoryTool:
    return PaymentHistoryTool(
        caseId="101",
        evidenceVersion=2,
        source="P5",
        retrievedAt=max(at for _, _, at in rows).isoformat(),
        lookbackDays=30,
        payments=[
            HistoryPayment(
                paymentId=pid, amount=amount, paymentStatus="CAPTURED", requestedAt=at.isoformat()
            )
            for pid, amount, at in rows
        ],
        statusCounts=[StatusCount(paymentStatus="CAPTURED", count=len(rows))],
        truncated=False,
    )


def _history_texts(history: PaymentHistoryTool) -> list[str]:
    return [f.text for f in catalog(CASES, history) if f.topic == "history"]


# 사건 결제 앞뒤 10분 안의 고객 결제와 그 밖의 결제를 나눠 보여 준다
def test_history_split_into_just_before_burst_and_earlier() -> None:
    first = datetime.fromisoformat(min(p.requestedAt for p in CASES.payments))

    texts = _history_texts(
        _history(
            ("8102", 600000, first - timedelta(seconds=7)),
            ("8001", 580000, first - timedelta(days=20)),
        )
    )

    burst = next(t for t in texts if t.startswith("사건 결제 앞뒤 10분 안의 다른 결제"))
    earlier = next(t for t in texts if t.startswith("사건 전 결제"))
    assert "600,000원" in burst and "580,000원" not in burst
    assert "580,000원" in earlier


# 나중에 다시 조사하면 고객 최근 결제에 사건 뒤 결제가 섞인다 (조회 시각 기준 30일)
# 사건 뒤 결제는 "사건 전 결제"(평소 구매)가 아니라 따로 묶는다
def test_history_after_case_is_not_earlier() -> None:
    last = datetime.fromisoformat(max(p.requestedAt for p in CASES.payments))

    texts = _history_texts(
        _history(
            ("8201", 590000, last + timedelta(days=1)),
            ("8001", 580000, last - timedelta(days=20)),
        )
    )

    later = next(t for t in texts if t.startswith("사건 도중 · 뒤 결제"))
    earlier = next(t for t in texts if t.startswith("사건 전 결제"))
    assert "590,000원" in later and "590,000원" not in earlier


# 4차 수정 재현 (Case 12)
# AI가 쓴 "다른 고객의 결제 이력 정보는 알 수 없었습니다"가 한계 줄에 들어갔다
# 실제로는 같은 카드 결제 12건을 조회했다. 한계 줄은 조회 기록으로 코드만 쓴다
def test_limitations_come_only_from_tool_records() -> None:
    from app.agent.assemble import LINKED_PRIVACY
    from app.agent.graph import run_investigation
    from app.config import Settings
    from tests.fakes import ScriptedLlm, call, context
    from tests.test_linked_payments import _toolbox

    written = json.loads(narrative(PAYMENTS))
    written["limitations"] = ["다른 고객의 결제 이력 정보는 이번 조사에서 알 수 없었습니다."]
    llm = ScriptedLlm(
        turns=[[call("get_case_payments")]], outputs=[json.dumps(written, ensure_ascii=False)]
    )
    toolbox = _toolbox(LinkedPaymentsTool.model_validate(LINKED))

    outcome = run_investigation(context(), llm, toolbox, Settings(_env_file=None))

    limitations = outcome.report.recommendation.limitations
    assert outcome.status == "SUCCEEDED"
    assert not any("알 수 없었습니다" in line for line in limitations)
    assert LINKED_PRIVACY in limitations
    assert "limitations" not in narrative_schema(catalog(CASES, FDS))["properties"]


# 이력 묶음이 5건을 넘으면 건수는 전체로 세고 목록만 줄인다 (#548 Codex 리뷰)
# 잘린 목록 개수를 건수로 쓰면 연속 결제 8건이 "5건"으로 줄어 AI 문장 · 숫자 검사까지 틀어진다
def test_fact_catalog_historyOver5_countsAllPayments() -> None:
    first = datetime.fromisoformat(min(p.requestedAt for p in CASES.payments))

    texts = _history_texts(
        _history(*[(f"81{i}", 650000, first - timedelta(seconds=10 + i)) for i in range(8)])
    )

    burst = next(t for t in texts if t.startswith("사건 결제 앞뒤 10분 안의 다른 결제"))
    assert burst.startswith("사건 결제 앞뒤 10분 안의 다른 결제 8건")
    assert burst.count("650,000원") == 5 and burst.endswith("외 3건")
