from dataclasses import dataclass
from datetime import datetime, timedelta

from app.schemas.report import BasisRule, BasisSignal, DecisionBasis, NormalBasis, Recommendation
from app.schemas.tools import (
    CasePayment,
    CasePaymentsTool,
    FdsPayment,
    HistoryPayment,
    LinkedMatch,
    LinkedPaymentsTool,
    PaymentHistoryTool,
    RuleHit,
)

# 판정 근거 · 판정 기준표 (#501) — 권고는 AI가 아니라 이 코드가 정한다
# 징후를 고위험 · 주의로 나누고, 정상 거래 근거가 설명하는 징후에 정상 사유를 붙인다
# 정상 거래 근거는 정해진 징후만 설명한다 (금액 이력이 새 카드 · 해외 접속을 덮지 않게)

HIGH_RISK = "HIGH_RISK"
CAUTION = "CAUTION"

# 규칙 코드 → (징후 이름, 등급). 예전 영문 코드도 같은 규칙으로 읽는다
_RULES: dict[str, tuple[str, str]] = {
    "R01": ("고액 결제", CAUTION),
    "R02": ("처음 쓰는 기기", CAUTION),
    "R03": ("처음 쓰는 카드", CAUTION),
    "R04": ("짧은 시간 반복 결제", CAUTION),
    "R05": ("고위험 국가 접속", CAUTION),
    "R06": ("카드 테스팅", HIGH_RISK),
    "R07": ("차단 목록 일치", HIGH_RISK),
    "R08": ("카드사 거절 직후 재시도", HIGH_RISK),
}
_ALIASES = {
    "HIGH_AMOUNT": "R01",
    "NEW_DEVICE": "R02",
    "NEW_CARD": "R03",
    "RAPID_REPEAT_PAYMENT": "R04",
}

OVERSEAS = "OVERSEAS"
LINKED_CARD = "LINKED_CARD"
LINKED_DEVICE = "LINKED_DEVICE"
LINKED_IP = "LINKED_IP"

SIMILAR_AMOUNT = "SIMILAR_AMOUNT"
OWN_CARD = "OWN_CARD"

# 비슷한 금액: 이번 금액의 0.5 ~ 1.5배, 매입 완료 2건 이상
SIMILAR_LOW, SIMILAR_HIGH, SIMILAR_MIN_COUNT = 0.5, 1.5, 2
# 사건 결제 앞뒤 10분 안의 결제는 평소 결제가 아니다 (assemble.split_history와 같은 기준)
BURST_WINDOW = timedelta(minutes=10)
APPROVED_STATUSES = frozenset({"CAPTURED", "HELD"})

# 정상 거래 근거가 설명할 수 있는 징후
_EXPLAINS = {SIMILAR_AMOUNT: ("R01",), OWN_CARD: ("R04",)}

_RECOMMENDATION: dict[BasisRule, Recommendation] = {
    "HIGH_RISK_WITH_OTHERS": "REJECT_REVIEW",
    "HIGH_RISK_ONLY": "NEEDS_CHECK",
    "CAUTION_ONLY": "NEEDS_CHECK",
    "ALL_EXPLAINED": "ACCEPT_REVIEW",
}

# 화면 · AI에게 보여 주는 기준표 칸 이름
RULE_LABELS: dict[BasisRule, str] = {
    "HIGH_RISK_WITH_OTHERS": "고위험 징후 + 정상 사유 없는 다른 징후 → 승인 취소 검토",
    "HIGH_RISK_ONLY": "고위험 징후 하나만 정상 사유 없음 → 추가 확인",
    "CAUTION_ONLY": "주의 징후만 정상 사유 없음 → 추가 확인",
    "ALL_EXPLAINED": "모든 징후에 정상 사유 있음 → 매입 진행 검토",
}


@dataclass(frozen=True)
class _Signal:
    key: str
    label: str
    detail: str | None
    level: str


def _rule_key(code: str) -> str:
    return _ALIASES.get(code, code)


def _rule_signal(rule: RuleHit) -> _Signal:
    key = _rule_key(rule.code)
    label, level = _RULES.get(key, (rule.name, CAUTION))
    detail = (
        f"{rule.criterion} · 이번 {rule.observed}" if rule.criterion and rule.observed else None
    )
    return _Signal(key, label, detail, level)


def _linked_signal(key: str, label: str, level: str, match: LinkedMatch) -> _Signal | None:
    if not match.provided or match.paymentCount == 0:
        return None
    people = "1명" if match.otherCustomerCount == 1 else "여러 명"
    declined = " · 그 결제 중 카드사 거절 있음" if match.declinedCount else ""
    return _Signal(key, label, f"최근 30일 다른 고객 {people}{declined}", level)


def _signals(evidence: FdsPayment, linked: LinkedPaymentsTool | None) -> list[_Signal]:
    found = [_rule_signal(rule) for rule in evidence.ruleHits]
    keys = {s.key for s in found}
    country = evidence.signals.ipCountryCode if evidence.signals else None
    # 고위험 국가 규칙이 걸렸으면 해외 접속을 따로 세지 않는다
    if country and country != "KR" and "R05" not in keys:
        found.append(_Signal(OVERSEAS, f"해외 접속 ({country})", None, CAUTION))
    # 같은 카드 · 기기 · IP 결제는 사건 전체 정보라 사건의 모든 결제에 붙인다
    if linked is not None:
        for signal in (
            _linked_signal(LINKED_CARD, "같은 카드를 다른 고객이 사용", HIGH_RISK, linked.card),
            _linked_signal(LINKED_DEVICE, "같은 기기를 다른 고객이 사용", HIGH_RISK, linked.device),
            # 같은 IP는 공유기 · 회사 망에서도 흔해 주의 징후로 둔다
            _linked_signal(LINKED_IP, "같은 IP를 다른 고객이 사용", CAUTION, linked.ip),
        ):
            if signal is not None:
                found.append(signal)
    return found


def _at(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


# 사건 결제 앞뒤 10분 안의 결제를 뺀 이력 — 같은 연속 결제는 평소 결제가 아니다
def _usual_history(
    case_payments: CasePaymentsTool, history: PaymentHistoryTool
) -> list[HistoryPayment]:
    case_times = [t for t in (_at(p.requestedAt) for p in case_payments.payments) if t]
    usual = []
    for payment in history.payments:
        at = _at(payment.requestedAt)
        if at is not None and any(abs(at - t) <= BURST_WINDOW for t in case_times):
            continue
        usual.append(payment)
    return usual


def _normals(
    payment: CasePayment,
    evidence: FdsPayment,
    case_payments: CasePaymentsTool,
    history: PaymentHistoryTool | None,
) -> list[tuple[str, str]]:
    if history is None:
        return []
    found: list[tuple[str, str]] = []
    usual = _usual_history(case_payments, history)
    amount = payment.amount or 0
    similar = [
        p
        for p in usual
        if p.paymentStatus == "CAPTURED"
        and p.amount is not None
        and amount * SIMILAR_LOW <= p.amount <= amount * SIMILAR_HIGH
    ]
    if amount and len(similar) >= SIMILAR_MIN_COUNT:
        found.append((SIMILAR_AMOUNT, f"최근 30일 비슷한 금액의 결제 {len(similar)}건 매입 완료"))
    # 본인 카드: 카드 정보가 있고, 새 카드 규칙이 안 걸렸고, 승인된 이력이 있다
    # (Spring은 승인 이력이 없으면 새 카드 규칙을 걸지 않으므로 승인 이력까지 본다)
    keys = {_rule_key(rule.code) for rule in evidence.ruleHits}
    card_given = evidence.signals is not None and evidence.signals.cardProvided
    approved = any(p.paymentStatus in APPROVED_STATUSES for p in history.payments)
    if card_given and "R03" not in keys and approved:
        device_given = evidence.signals is not None and evidence.signals.deviceProvided
        label = "전에 승인된 적 있는 본인 카드"
        if device_given and "R02" not in keys:
            label += " · 기기"
        found.append((OWN_CARD, label))
    return found


def _label_of(key: str) -> str:
    return _RULES.get(key, (key, CAUTION))[0]


def _rule(signals: list[BasisSignal]) -> BasisRule:
    open_signals = [s for s in signals if s.normalReason is None]
    if any(s.level == HIGH_RISK for s in open_signals):
        return "HIGH_RISK_WITH_OTHERS" if len(open_signals) >= 2 else "HIGH_RISK_ONLY"
    return "CAUTION_ONLY" if open_signals else "ALL_EXPLAINED"


def decision_basis(
    payment: CasePayment,
    evidence: FdsPayment,
    case_payments: CasePaymentsTool,
    history: PaymentHistoryTool | None,
    linked: LinkedPaymentsTool | None,
) -> DecisionBasis:
    raw_signals = _signals(evidence, linked)
    normals = _normals(payment, evidence, case_payments, history)
    reason_for: dict[str, str] = {}
    for key, label in normals:
        for target in _EXPLAINS[key]:
            reason_for.setdefault(target, label)
    signals = [
        BasisSignal(
            key=s.key,
            label=s.label,
            detail=s.detail,
            level=s.level,  # type: ignore[arg-type]
            # 고위험 징후는 자료만으로 정상 사유를 붙이지 않는다 — 직원 확인으로만 풀린다
            normalReason=reason_for.get(s.key) if s.level == CAUTION else None,
        )
        for s in raw_signals
    ]
    present = {s.key for s in signals}
    rule = _rule(signals)
    return DecisionBasis(
        signals=signals,
        normals=[
            NormalBasis(
                key=key,
                label=label,
                canExplain=[_label_of(t) for t in _EXPLAINS[key]],
                explains=[_label_of(t) for t in _EXPLAINS[key] if t in present],
            )
            for key, label in normals
        ],
        rule=rule,
        recommendation=_RECOMMENDATION[rule],
    )


# AI에게 보여 줄 판정 근거 — 숫자 없이 이름 · 정상 사유 · 기준표 칸만
def basis_facts(basis: DecisionBasis) -> dict[str, object]:
    def signal(s: BasisSignal) -> dict[str, object]:
        return {"징후": s.label, "정상 사유": s.normalReason or "없음"}

    return {
        "고위험 징후": [signal(s) for s in basis.signals if s.level == HIGH_RISK],
        "주의 징후": [signal(s) for s in basis.signals if s.level == CAUTION],
        "정상 거래 근거": [
            {
                "근거": n.label,
                "설명할 수 있는 징후": n.canExplain,
                "이번에 설명한 징후": n.explains or "없음",
            }
            for n in basis.normals
        ],
        "기준표 판정": RULE_LABELS[basis.rule],
    }
