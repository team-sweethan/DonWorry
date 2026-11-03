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

# 비슷한 금액: 이번 금액의 0.5 ~ 1.5배, 매입 완료 2건 이상
SIMILAR_LOW, SIMILAR_HIGH, SIMILAR_MIN_COUNT = 0.5, 1.5, 2
# 사건 결제 앞뒤 10분 안의 결제는 평소 결제가 아니다 (assemble.split_history와 같은 기준)
BURST_WINDOW = timedelta(minutes=10)

# 정상 거래 근거가 설명할 수 있는 징후
# "전에 승인된 본인 카드"는 쓰지 않는다 — 카드별 승인 이력을 받지 못해 확인할 수 없다
# (R03이 안 걸린 것은 규칙이 꺼져 있어도 같고, 고객 이력은 카드별이 아니다, #501 리뷰)
_EXPLAINS = {SIMILAR_AMOUNT: ("R01",)}

# AI 입력용 정상 거래 근거 이름 — 건수 없이 쓴다 (#501 리뷰)
# 화면용 이름("9건 매입 완료")의 건수는 AI 문장의 허용 숫자에 없어 그대로 옮기면 다시 쓰기에 걸린다
# 같은 카드 결제를 "한 명 / 여러 명"으로 주는 것(#352)과 같은 방식
_AI_LABELS = {SIMILAR_AMOUNT: "사건 전 비슷한 금액의 매입 완료 결제 여러 건"}

# 같은 행동을 두 번 세지 않는다 — 거절 후 재시도 · 카드 테스팅은 이미 짧은 시간 반복 결제다
_COUNTED_IN = {"R04": ("R08", "R06")}

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


# 평소 결제 = 사건 전 결제 — assemble.split_history의 "그 전 결제"와 같은 기준
# 사건 결제 앞뒤 10분 안(같은 연속 결제)과 사건 뒤 결제는 사건의 정상 근거가 아니다
def _usual_history(
    case_payments: CasePaymentsTool, history: PaymentHistoryTool
) -> list[HistoryPayment]:
    case_times = [t for t in (_at(p.requestedAt) for p in case_payments.payments) if t]
    usual = []
    for payment in history.payments:
        at = _at(payment.requestedAt)
        if at is not None and any(abs(at - t) <= BURST_WINDOW for t in case_times):
            continue
        if at is not None and case_times and at > max(case_times):
            continue
        usual.append(payment)
    return usual


def _normals(
    payment: CasePayment,
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
    return found


def _label_of(key: str) -> str:
    return _RULES.get(key, (key, CAUTION))[0]


def _rule(signals: list[BasisSignal]) -> BasisRule:
    open_signals = [s for s in signals if s.normalReason is None and s.countedIn is None]
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
    normals = _normals(payment, case_payments, history)
    reason_for: dict[str, str] = {}
    for key, label in normals:
        for target in _EXPLAINS[key]:
            reason_for.setdefault(target, label)
    labels = {s.key: s.label for s in raw_signals}

    def counted_in(key: str) -> str | None:
        return next((labels[k] for k in _COUNTED_IN.get(key, ()) if k in labels), None)

    signals = [
        BasisSignal(
            key=s.key,
            label=s.label,
            detail=s.detail,
            level=s.level,  # type: ignore[arg-type]
            # 고위험 징후는 자료만으로 정상 사유를 붙이지 않는다 — 직원 확인으로만 풀린다
            normalReason=reason_for.get(s.key) if s.level == CAUTION else None,
            countedIn=counted_in(s.key),
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
    ai_label = {n.label: _AI_LABELS.get(n.key, n.label) for n in basis.normals}

    def signal(s: BasisSignal) -> dict[str, object]:
        if s.countedIn:
            return {"징후": s.label, "같은 행동이라 따로 세지 않음": s.countedIn}
        reason = ai_label.get(s.normalReason, s.normalReason) if s.normalReason else "없음"
        return {"징후": s.label, "정상 사유": reason}

    return {
        "고위험 징후": [signal(s) for s in basis.signals if s.level == HIGH_RISK],
        "주의 징후": [signal(s) for s in basis.signals if s.level == CAUTION],
        "정상 거래 근거": [
            {
                "근거": ai_label[n.label],
                "설명할 수 있는 징후": n.canExplain,
                "이번에 설명한 징후": n.explains or "없음",
            }
            for n in basis.normals
        ],
        "기준표 판정": RULE_LABELS[basis.rule],
    }
