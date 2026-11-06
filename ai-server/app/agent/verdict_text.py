from app.schemas.report import DecisionBasis, PaymentAnalysis, Recommendation

# 판단 문장(결론 한 줄 · 권고 요약 · 결제별 이유)은 코드가 기준표 판정으로 쓴다 (#532)
# AI가 쓰면 판정과 반대되는 문장이 그대로 저장됐다 — AI는 사실 문장만 쓴다

RECOMMENDATION_KO: dict[Recommendation, str] = {
    "REJECT_REVIEW": "승인 취소 검토",
    "NEEDS_CHECK": "추가 확인",
    "ACCEPT_REVIEW": "매입 진행 검토",
}
# 화면 · 결론에서 무거운 판정부터 보여 준다
_ORDER: list[Recommendation] = ["REJECT_REVIEW", "NEEDS_CHECK", "ACCEPT_REVIEW"]

_SUMMARY: dict[Recommendation, str] = {
    "REJECT_REVIEW": (
        "고위험 징후와 정상 사유가 없는 다른 징후가 겹쳐 승인 취소를 먼저 검토할 것을 권고합니다."
    ),
    "NEEDS_CHECK": "정상 사유가 없는 징후가 있어 추가 확인 후 판정할 것을 권고합니다.",
    "ACCEPT_REVIEW": "모든 징후에 정상 사유가 있어 매입 진행을 검토할 수 있습니다.",
}
NO_FDS_REASON = "FDS 평가 결과를 확인하지 못했습니다."
NO_PAYMENT_HEADLINE = "결제 정보를 확인하지 못해 추가 확인이 필요합니다."
_SIGNALS_SHOWN = 3


# 정상 사유가 없고 다른 징후에 포함되지도 않은 징후 이름
def open_signals(basis: DecisionBasis) -> list[str]:
    return [s.label for s in basis.signals if s.normalReason is None and s.countedIn is None]


# 결제별 이유 — 판정 근거의 징후 이름 그대로 (화면 밑줄 표시와 맞춘다)
def payment_reason(basis: DecisionBasis | None) -> str:
    if basis is None:
        return NO_FDS_REASON
    names = " · ".join(open_signals(basis))
    if basis.rule == "HIGH_RISK_WITH_OTHERS":
        return f"고위험 징후를 포함해 정상 사유가 없는 징후가 겹칩니다: {names}"
    if basis.rule == "HIGH_RISK_ONLY":
        return f"정상 사유가 없는 고위험 징후가 있습니다: {names}"
    if basis.rule == "CAUTION_ONLY":
        return f"정상 사유가 없는 주의 징후가 있습니다: {names}"
    explained = [f"{s.label}({s.normalReason})" for s in basis.signals if s.normalReason]
    if explained:
        return "모든 징후에 정상 사유가 있습니다: " + " · ".join(explained)
    return "적중한 징후가 없습니다."


def _counts(analyses: list[PaymentAnalysis]) -> dict[Recommendation, int]:
    counts = {r: sum(1 for a in analyses if a.recommendation == r) for r in _ORDER}
    return {r: n for r, n in counts.items() if n}


# 결론 한 줄 — 판정이 하나면 판정과 주요 징후, 섞였으면 판정별 건수
def headline(analyses: list[PaymentAnalysis]) -> str:
    counts = _counts(analyses)
    if not counts:
        return NO_PAYMENT_HEADLINE
    if len(counts) > 1:
        parts = " · ".join(f"{RECOMMENDATION_KO[r]} {n}건" for r, n in counts.items())
        return f"결제 {len(analyses)}건 중 {parts}"
    (only,) = counts
    prefix = (
        RECOMMENDATION_KO[only]
        if len(analyses) == 1
        else f"결제 {len(analyses)}건 모두 {RECOMMENDATION_KO[only]}"
    )
    names: list[str] = []
    for analysis in analyses:
        for name in open_signals(analysis.basis) if analysis.basis else []:
            if name not in names:
                names.append(name)
    if not names:
        return f"{prefix} — 모든 징후에 정상 사유 있음" if only == "ACCEPT_REVIEW" else prefix
    shown = " · ".join(names[:_SIGNALS_SHOWN]) + (" 등" if len(names) > _SIGNALS_SHOWN else "")
    return f"{prefix} — 정상 사유 없는 징후: {shown}"


# 권고 요약 — 판정이 하나면 그 판정의 문장, 섞였으면 결제별 판정을 나열
def recommendation_summary(analyses: list[PaymentAnalysis]) -> str:
    counts = _counts(analyses)
    if not counts:
        return "결제 정보를 확인하지 못해 추가 확인 후 판정할 것을 권고합니다."
    if len(counts) == 1:
        (only,) = counts
        return _SUMMARY[only]
    listed = ", ".join(
        f"결제 {a.paymentId} {RECOMMENDATION_KO[a.recommendation]}"
        for r in _ORDER
        for a in analyses
        if a.recommendation == r
    )
    return f"결제별 판정이 다릅니다({listed}). 결제별 판정 근거를 확인한 뒤 판정할 것을 권고합니다."
