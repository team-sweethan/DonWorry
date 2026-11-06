import csv
import io
import json
from datetime import datetime, timedelta
from pathlib import Path

from app.agent.evidence import decision_basis
from scripts.measure_basis import (
    ScenarioPayment,
    build_case,
    group_customers,
    main,
    payment_times,
)

# 판정 기준표 기준선 측정 (#614) — 작은 CSV로 사실 만들기 · 집계만 본다 (AI 호출 없음)

LABEL_HEAD = ["payment_id", "scenario", "label", "payment_status", "amount", "run_id"]
SHADOW_HEAD = ["payment_id", "model_version", "rule_result", "rule_risk_score"]
SHADOW_HEAD += ["matched_rules", "ip_country_code", "features"]


def _payment(
    payment_id: str,
    scenario: str = "N2 단골 소액",
    past_count: int = 0,
    card_shared: bool = False,
) -> ScenarioPayment:
    return ScenarioPayment(
        payment_id=payment_id,
        scenario=scenario,
        label="정상",
        status="CAPTURED",
        amount=45_000,
        rule_result="ALLOW",
        risk_score=0,
        rules=(),
        country="KR",
        past_count=past_count,
        card_shared=card_shared,
    )


def _write_csv(
    tmp_path: Path, rows: list[tuple[str, str, str, str, str, str, int, int]]
) -> list[str]:
    # rows: (결제, 시나리오, 정답, FDS 결과, 적중 규칙, 국가, 이전 결제 수, 다른 고객 카드)
    # rf-v1 줄은 규칙 없는 ALLOW로 넣는다 — rf-v2 줄만 읽어야 집계가 맞는다
    labels, shadow = tmp_path / "labels.csv", tmp_path / "shadow.csv"
    with labels.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(LABEL_HEAD)
        for pid, scenario, label, *_ in rows:
            writer.writerow([pid, scenario, label, "CAPTURED", 100_000, "test"])
    with shadow.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(SHADOW_HEAD)
        for pid, _, _, result, rules, country, past, shared in rows:
            features = {"customerPastTxCount": past, "cardUsedByOtherCustomer": shared}
            writer.writerow([pid, "rf-v1", "ALLOW", 0, "", country, json.dumps(features)])
            writer.writerow([pid, "rf-v2", result, 50, rules, country, json.dumps(features)])
    return ["--labels", str(labels), "--shadow", str(shadow)]


# 이전 결제 수가 있으면 앞 결제와 같은 손님 — 다른 손님 결제가 평소 이력에 섞이지 않게
def test_group_customers_with_past_count_joins_previous_payment() -> None:
    payments = [
        _payment("1"),
        _payment("2", past_count=1),
        _payment("3"),
        _payment("4"),
        _payment("5", past_count=1),
        _payment("6", past_count=2),
    ]

    customers = group_customers(payments)

    assert customers["1"] == customers["2"]
    assert len({customers["2"], customers["3"], customers["4"]}) == 3
    assert customers["4"] == customers["5"] == customers["6"]


# 시나리오 뜻대로면 단골의 앞 결제는 10분 연속 밖 · 30일 조회 안, 연속 결제 시나리오는 그대로
def test_payment_times_intent_spreads_regular_customer_beyond_burst_within_lookback() -> None:
    payments = [
        _payment("1", scenario="N2 단골 소액"),
        _payment("2", scenario="N2 단골 소액", past_count=1),
        _payment("3", scenario="F1 고액 연속 (기존 시연)"),
        _payment("4", scenario="F1 고액 연속 (기존 시연)", past_count=1),
    ]
    customers = group_customers(payments)

    def gap(mode: str, earlier: str, later: str) -> timedelta:
        times = payment_times(payments, customers, mode)  # type: ignore[arg-type]
        tools = build_case(payments[int(later) - 1], payments, customers, times)
        seen = {p.paymentId: p.requestedAt for p in tools.history.payments}
        case_at = tools.case_payments.payments[0].requestedAt
        return datetime.fromisoformat(case_at or "") - datetime.fromisoformat(seen[earlier] or "")

    assert gap("recorded", "1", "2") <= timedelta(minutes=10)
    assert timedelta(minutes=10) < gap("intent", "1", "2") <= timedelta(days=30)
    assert gap("intent", "3", "4") <= timedelta(minutes=10)


# 다른 고객이 쓴 카드면 같은 카드 징후(고위험)가 붙는다 — F4 카드 공유 사기가 징후 없이 세지지 않게
def test_build_case_card_used_by_other_customer_adds_linked_card_signal() -> None:
    payments = [_payment("1"), _payment("2", card_shared=True)]
    customers = group_customers(payments)
    times = payment_times(payments, customers, "recorded")

    tools = build_case(payments[1], payments, customers, times)
    basis = decision_basis(
        tools.case_payments.payments[0],
        tools.evidence,
        tools.case_payments,
        tools.history,
        tools.linked,
    )

    assert [(s.key, s.level) for s in basis.signals] == [("LINKED_CARD", "HIGH_RISK")]
    assert basis.recommendation == "NEEDS_CHECK"


# 묶음마다 권고 분포 · 사기인데 매입 진행 검토 · 정상인데 승인 취소 검토를 센다 (이슈 완료 기준)
def test_main_small_csv_counts_distribution_and_misses(tmp_path: Path) -> None:
    args = _write_csv(
        tmp_path,
        [
            ("1", "F5 소액 반복", "사기", "ALLOW", "", "KR", 0, 0),
            ("2", "F1 고액 연속", "사기", "REVIEW", "R01 R04", "KR", 0, 0),
            ("3", "N1 소액 새 손님", "정상", "ALLOW", "", "KR", 0, 0),
            ("4", "N3 단골 고액", "정상", "DENY", "R06 R01 R02", "KR", 0, 0),
        ],
    )
    out_json = tmp_path / "basis.local.json"

    code = main([*args, "--out", str(out_json)], out=io.StringIO())

    assert code == 0
    body = json.loads(out_json.read_text(encoding="utf-8"))
    for mode in ("recorded", "intent"):
        groups = {g["name"]: g for g in body[mode]["groups"]}
        whole, hit = groups["전체"], groups["규칙 적중"]
        assert whole["by_label"]["사기"] == {
            "REJECT_REVIEW": 0,
            "NEEDS_CHECK": 1,
            "ACCEPT_REVIEW": 1,
        }
        assert whole["by_label"]["정상"] == {
            "REJECT_REVIEW": 1,
            "NEEDS_CHECK": 0,
            "ACCEPT_REVIEW": 1,
        }
        assert (whole["fraud_accept"], whole["normal_reject"]) == (1, 1)
        assert (hit["total"], hit["fraud_accept"], hit["normal_reject"]) == (2, 0, 1)
        assert groups["FDS 의심 (보류 · 차단)"]["total"] == 2
