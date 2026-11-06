import argparse
import csv
import json
import sys
from collections import Counter
from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Literal, TextIO

from app.agent.evidence import RULE_LABELS, decision_basis
from app.schemas.report import BasisRule, Recommendation
from app.schemas.tools import (
    CasePayment,
    CasePaymentsTool,
    FdsPayment,
    HistoryPayment,
    LinkedMatch,
    LinkedPaymentsTool,
    PaymentHistoryTool,
    PaymentLinks,
    RuleHit,
    Signals,
    StatusCount,
)

# 판정 기준표 기준선 측정 (#614) — 정답이 있는 시나리오 결제로 기준표 권고가 어떻게 나오는지 센다
# 기준표(evidence.decision_basis)만 돌린다. AI · Spring · DB는 부르지 않아 .env 제공자와 무관하다
# 데이터는 fds-ml 저장소의 11/03 만약 비교 결과 — 이 저장소로 복사하지 않고 경로로 받는다
# 예) uv run python -m scripts.measure_basis \
#       --labels ../fds-ml/data/shadow/scenario_labels.csv \
#       --shadow ../fds-ml/data/shadow/shadow_export.csv --out basis.local.json

FRAUD = "사기"
NORMAL = "정상"
RECOMMENDATIONS: tuple[Recommendation, ...] = ("REJECT_REVIEW", "NEEDS_CHECK", "ACCEPT_REVIEW")
RECOMMENDATION_KO: dict[Recommendation, str] = {
    "REJECT_REVIEW": "승인 취소 검토",
    "NEEDS_CHECK": "추가 확인",
    "ACCEPT_REVIEW": "매입 진행 검토",
}

Mode = Literal["recorded", "intent"]
MODE_KO: dict[Mode, str] = {"recorded": "기록대로", "intent": "시나리오 뜻대로"}

BASE_AT = datetime(2026, 11, 3, tzinfo=UTC)
# 시나리오 결제는 몇 초 간격으로 보냈다 (shadow 특징값 minutesSinceLastTx ≈ 0.04분)
RECORDED_GAP = timedelta(seconds=3)
# 시나리오 뜻대로: 단골의 앞 결제는 며칠 전 결제다. 30일 기본 조회 안, 10분 연속 결제 밖
INTENT_GAP = timedelta(days=7)
# 앞 결제를 벌리는 시나리오 — 손님의 첫 결제 시나리오 앞말로 본다
# F1 고액 연속 · F5 소액 반복은 몇 초 연속이 시나리오 자체라 벌리지 않는다
SPREAD_PREFIXES = ("N2", "N3", "N4", "F2")
LOOKBACK_DAYS = 30

# 시나리오 50건이 모두 같은 시험용 IP(203.0.113.5)로 보내져, 그대로 넣으면 모든 결제가
# "같은 IP 다른 고객"이 된다. 시나리오 뜻이 아니라 IP 연결은 없음으로 둔다 (11/07 결정)
LIMITATIONS = (
    "같은 IP 연결은 넣지 않았다 (시나리오 50건이 모두 같은 시험용 IP라 전부 걸린다).",
    "같은 카드 연결은 특징값 cardUsedByOtherCustomer(0/1)로만 넣었다 (다른 고객 수는 1명).",
    "결제 하나를 사건 하나로 보고 돌렸다 — 실제 사건은 FDS 보류(REVIEW) 결제만 열린다.",
)


@dataclass(frozen=True)
class ScenarioPayment:
    payment_id: str
    scenario: str
    label: str
    status: str
    amount: int
    rule_result: str | None
    risk_score: int | None
    rules: tuple[str, ...]
    country: str | None
    past_count: int
    card_shared: bool


@dataclass(frozen=True)
class CaseTools:
    case_payments: CasePaymentsTool
    evidence: FdsPayment
    history: PaymentHistoryTool
    linked: LinkedPaymentsTool


@dataclass(frozen=True)
class BasisResult:
    payment_id: str
    scenario: str
    label: str
    rule_result: str | None
    rules: list[str]
    rule: BasisRule
    recommendation: Recommendation
    signals: list[str]


def _int(value: str) -> int | None:
    return int(float(value)) if value.strip() else None


def load_payments(labels: Path, shadow: Path, model_version: str) -> list[ScenarioPayment]:
    with shadow.open(encoding="utf-8") as file:
        scored = {
            row["payment_id"]: row
            for row in csv.DictReader(file)
            if row["model_version"] == model_version
        }
    with labels.open(encoding="utf-8") as file:
        rows = sorted(csv.DictReader(file), key=lambda row: int(row["payment_id"]))
    payments = []
    for row in rows:
        score = scored.get(row["payment_id"])
        features = json.loads(score["features"]) if score else {}
        payments.append(
            ScenarioPayment(
                payment_id=row["payment_id"],
                scenario=row["scenario"],
                label=row["label"],
                status=row["payment_status"],
                amount=int(row["amount"]),
                # shadow 기록이 없으면 FDS 평가를 모르는 결제 — 기준표가 "평가 미확인"으로 센다
                rule_result=score["rule_result"] or None if score else None,
                risk_score=_int(score["rule_risk_score"]) if score else None,
                rules=tuple((score["matched_rules"] or "").split()) if score else (),
                country=score["ip_country_code"] or None if score else None,
                past_count=int(features.get("customerPastTxCount", 0)),
                card_shared=bool(features.get("cardUsedByOtherCustomer", 0)),
            )
        )
    return payments


# CSV에 손님 칸이 없다 — 시나리오는 한 손님의 결제를 이어 보내므로
# 이전 결제 수가 1 이상이면 바로 앞 결제와 같은 손님, 0이면 새 손님이다
def group_customers(payments: list[ScenarioPayment]) -> dict[str, str]:
    customers: dict[str, str] = {}
    previous: str | None = None
    count = 0
    for payment in payments:
        if payment.past_count > 0 and previous is not None:
            customers[payment.payment_id] = customers[previous]
        else:
            count += 1
            customers[payment.payment_id] = f"c{count}"
        previous = payment.payment_id
    return customers


def payment_times(
    payments: list[ScenarioPayment], customers: dict[str, str], mode: Mode
) -> dict[str, datetime]:
    times = {p.payment_id: BASE_AT + i * RECORDED_GAP for i, p in enumerate(payments)}
    if mode == "recorded":
        return times
    first_scenario: dict[str, str] = {}
    for p in payments:
        first_scenario.setdefault(customers[p.payment_id], p.scenario)
    for i, p in enumerate(payments):
        customer = customers[p.payment_id]
        if not first_scenario[customer].startswith(SPREAD_PREFIXES):
            continue
        later = sum(1 for q in payments[i + 1 :] if customers[q.payment_id] == customer)
        times[p.payment_id] -= later * INTENT_GAP
    return times


def _iso(at: datetime) -> str:
    return at.isoformat().replace("+00:00", "Z")


def _match(others: int) -> LinkedMatch:
    return LinkedMatch(
        provided=True,
        otherCustomerCount=others,
        paymentCount=others,
        resultCounts=[],
        declinedCount=0,
    )


# 결제 하나를 "이 결제로 사건이 열렸다면"으로 놓고 Spring Tool 응답 모양을 만든다
def build_case(
    payment: ScenarioPayment,
    payments: list[ScenarioPayment],
    customers: dict[str, str],
    times: dict[str, datetime],
) -> CaseTools:
    case_id = f"whatif-{payment.payment_id}"
    at = times[payment.payment_id]
    retrieved = _iso(at + timedelta(minutes=1))
    head = {"caseId": case_id, "evidenceVersion": 1, "retrievedAt": retrieved}
    # 같은 손님의 다른 결제 중 30일 기본 조회 안 — 사건 뒤 결제 · 연속 결제는 기준표가 거른다
    history = [
        HistoryPayment(
            paymentId=p.payment_id,
            amount=p.amount,
            paymentStatus=p.status,
            requestedAt=_iso(times[p.payment_id]),
        )
        for p in payments
        if p.payment_id != payment.payment_id
        and customers[p.payment_id] == customers[payment.payment_id]
        and times[p.payment_id] >= at - timedelta(days=LOOKBACK_DAYS)
    ]
    card = _match(1 if payment.card_shared else 0)
    none = _match(0)
    return CaseTools(
        case_payments=CasePaymentsTool(
            **head,
            source="scenario CSV",
            payments=[
                CasePayment(
                    paymentId=payment.payment_id,
                    amount=payment.amount,
                    paymentStatus=payment.status,
                    requestedAt=_iso(at),
                )
            ],
            missingPaymentIds=[],
        ),
        evidence=FdsPayment(
            paymentId=payment.payment_id,
            riskScore=payment.risk_score,
            fdsResult=payment.rule_result,
            # 이름은 기준표가 규칙 코드로 다시 붙인다 (evidence._RULES)
            ruleHits=[RuleHit(code=code, name=code, appliedScore=None) for code in payment.rules],
            signals=Signals(
                cardDisplayLabel=None,
                cardProvided=True,
                deviceProvided=True,
                ipCountryCode=payment.country,
                ipProvided=True,
            ),
        ),
        history=PaymentHistoryTool(
            **head,
            source="scenario CSV",
            lookbackDays=LOOKBACK_DAYS,
            payments=history,
            statusCounts=[
                StatusCount(paymentStatus=status, count=n)
                for status, n in Counter(p.paymentStatus or "" for p in history).items()
            ],
            truncated=False,
        ),
        linked=LinkedPaymentsTool(
            **head,
            source="scenario CSV",
            lookbackDays=LOOKBACK_DAYS,
            card=card,
            device=none,
            ip=none,
            payments=[PaymentLinks(paymentId=payment.payment_id, card=card, device=none, ip=none)],
        ),
    )


def measure(payments: list[ScenarioPayment], mode: Mode) -> list[BasisResult]:
    customers = group_customers(payments)
    times = payment_times(payments, customers, mode)
    results = []
    for payment in payments:
        tools = build_case(payment, payments, customers, times)
        basis = decision_basis(
            tools.case_payments.payments[0],
            tools.evidence,
            tools.case_payments,
            tools.history,
            tools.linked,
        )
        results.append(
            BasisResult(
                payment_id=payment.payment_id,
                scenario=payment.scenario,
                label=payment.label,
                rule_result=payment.rule_result,
                rules=list(payment.rules),
                rule=basis.rule,
                recommendation=basis.recommendation,
                signals=[
                    s.label + (f" (정상 사유: {s.normalReason})" if s.normalReason else "")
                    for s in basis.signals
                ],
            )
        )
    return results


# 묶음 — 규칙이 하나도 안 걸린 결제는 징후가 없어 늘 매입 진행 검토라 따로 본다
GROUPS: tuple[tuple[str, Callable[[BasisResult], bool]], ...] = (
    ("전체", lambda r: True),
    ("규칙 적중", lambda r: bool(r.rules)),
    ("FDS 의심 (보류 · 차단)", lambda r: r.rule_result in ("REVIEW", "DENY")),
)


@dataclass(frozen=True)
class GroupSummary:
    name: str
    total: int
    by_label: dict[str, dict[str, int]]
    fraud_accept: int
    normal_reject: int


def summarize(name: str, results: list[BasisResult]) -> GroupSummary:
    by_label = {
        label: {
            rec: sum(1 for r in results if r.label == label and r.recommendation == rec)
            for rec in RECOMMENDATIONS
        }
        for label in (FRAUD, NORMAL)
    }
    return GroupSummary(
        name=name,
        total=len(results),
        by_label=by_label,
        fraud_accept=by_label[FRAUD]["ACCEPT_REVIEW"],
        normal_reject=by_label[NORMAL]["REJECT_REVIEW"],
    )


def summaries(results: list[BasisResult]) -> list[GroupSummary]:
    return [summarize(name, [r for r in results if keep(r)]) for name, keep in GROUPS]


def _summary_table(mode: Mode, groups: list[GroupSummary]) -> list[str]:
    head = " | ".join(RECOMMENDATION_KO[rec] for rec in RECOMMENDATIONS)
    lines = [
        f"### {MODE_KO[mode]}",
        "",
        f"| 묶음 | 정답 | 건수 | {head} |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for g in groups:
        for label in (FRAUD, NORMAL):
            counts = g.by_label[label]
            cells = " | ".join(str(counts[rec]) for rec in RECOMMENDATIONS)
            lines.append(f"| {g.name} | {label} | {sum(counts.values())} | {cells} |")
    lines.append("")
    for g in groups:
        lines.append(
            f"- {g.name} {g.total}건: 사기인데 매입 진행 검토 **{g.fraud_accept}건** · "
            f"정상인데 승인 취소 검토 **{g.normal_reject}건**"
        )
    return lines


def _payment_table(by_mode: dict[Mode, list[BasisResult]]) -> list[str]:
    recorded, intent = by_mode["recorded"], by_mode["intent"]
    lines = [
        "| 결제 | 시나리오 | 정답 | FDS | 규칙 | 기록대로 | 시나리오 뜻대로 | 징후 (뜻대로) |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for a, b in zip(recorded, intent, strict=True):
        cells = [
            f"{RULE_LABELS[r.rule].split(' → ')[0]} → {RECOMMENDATION_KO[r.recommendation]}"
            for r in (a, b)
        ]
        lines.append(
            f"| {a.payment_id} | {a.scenario} | {a.label} | {a.rule_result or '-'} | "
            f"{' '.join(a.rules) or '-'} | {cells[0]} | {cells[1]} | "
            f"{' · '.join(b.signals) or '없음'} |"
        )
    return lines


def render(by_mode: dict[Mode, list[BasisResult]], model_version: str) -> str:
    any_results = next(iter(by_mode.values()))
    fraud = sum(1 for r in any_results if r.label == FRAUD)
    lines = [
        f"## 판정 기준표 기준선 (#614) — 시나리오 결제 {len(any_results)}건 "
        f"(사기 {fraud} · 정상 {len(any_results) - fraud}), FDS 기록 {model_version} 줄",
        "",
    ]
    for mode, results in by_mode.items():
        lines += _summary_table(mode, summaries(results)) + [""]
    lines += ["### 결제별 기준표 칸", ""] + _payment_table(by_mode) + ["", "### 한계", ""]
    lines += [f"- {line}" for line in LIMITATIONS]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None, *, out: TextIO = sys.stdout) -> int:
    parser = argparse.ArgumentParser(description="판정 기준표 기준선 — 시나리오 정답과 권고 대조")
    parser.add_argument("--labels", type=Path, required=True, help="scenario_labels.csv")
    parser.add_argument("--shadow", type=Path, required=True, help="shadow_export.csv")
    parser.add_argument("--model-version", default="rf-v2", help="shadow 기록에서 읽을 모델 줄")
    parser.add_argument("--out", type=Path, help="결과 JSON 저장 경로 (예: basis.local.json)")
    args = parser.parse_args(argv)
    payments = load_payments(args.labels, args.shadow, args.model_version)
    modes: tuple[Mode, ...] = ("recorded", "intent")
    by_mode = {mode: measure(payments, mode) for mode in modes}
    print(render(by_mode, args.model_version), file=out, end="")
    if args.out:
        body = {
            mode: {
                "groups": [asdict(g) for g in summaries(results)],
                "payments": [asdict(r) for r in results],
            }
            for mode, results in by_mode.items()
        }
        args.out.write_text(json.dumps(body, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"결과 저장: {args.out}", file=out)
    return 0


if __name__ == "__main__":
    # Windows 기본 출력(cp949)은 "·" 같은 글자에서 멈춘다 → 항상 UTF-8 (measure_summary와 같음)
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    sys.exit(main())
