from datetime import datetime

from pydantic import BaseModel, ValidationError

from app.agent.facts import _history, _kst, _linked, _won
from app.agent.tools import ToolRecord
from app.schemas.question import AnswerFact, PreviousTurn
from app.schemas.report import DecisionBasis, FactItem, RuleHitDetail
from app.schemas.tools import (
    CasePayment,
    CasePaymentsTool,
    LinkedPaymentsTool,
    PaymentHistoryTool,
)

# 질문 · 답변의 사실 목록 (ADR-0086 3절) — AI 답의 근거는 이 번호로만 댄다
# 보고서 사실에는 결제 표가 없어 코드가 결제-N을, 합계 · 간격은 코드가 계산-N을 만든다
# AI는 더하거나 빼지 않고 인용만 한다 (11/09 사용자 결정)

ORDINALS = ("첫", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열")
_LEVEL_KO = {"HIGH_RISK": "고위험", "CAUTION": "주의"}


# 저장된 report_body에서 질문에 필요한 칸만 읽는다 — 다른 칸은 예전 형식이어도 상관없다
class _ReportPayment(BaseModel):
    paymentId: str
    amount: int
    requestedAt: str
    riskScore: int | None = None
    ruleHitDetails: list[RuleHitDetail] = []
    basis: DecisionBasis | None = None


class ReportView(BaseModel):
    # #548 전 보고서에는 facts가 없다 — 그 보고서는 질문을 받지 않는다
    facts: list[FactItem]
    paymentAnalyses: list[_ReportPayment]
    additionalChecks: list[str] = []


class UnsupportedReport(Exception):
    pass


def read_report(body: dict) -> ReportView:
    try:
        view = ReportView.model_validate(body)
    except ValidationError as e:
        raise UnsupportedReport("질문에 필요한 보고서 칸(facts · paymentAnalyses)이 없다") from e
    if not view.paymentAnalyses:
        raise UnsupportedReport("보고서에 결제가 없다")
    return view


# 보고서 사실 문장과 같은 이름 (facts.payment_names)
# 1건이면 "이번 결제", 여러 건이면 "첫 번째 결제"
def payment_names(view: ReportView) -> dict[str, str]:
    ids = [p.paymentId for p in view.paymentAnalyses]
    if len(ids) == 1:
        return {ids[0]: "이번 결제"}
    return {
        pid: f"{ORDINALS[i]} 번째 결제" if i < len(ORDINALS) else f"{i + 1}번째 결제"
        for i, pid in enumerate(ids)
    }


def _payment_text(name: str, p: _ReportPayment) -> str:
    score = "확인 못 함" if p.riskScore is None else f"{p.riskScore}점"
    rules = " · ".join(r.name for r in p.ruleHitDetails) or "없음"
    text = f"{name}: {_kst(p.requestedAt)} {_won(p.amount)} · FDS 점수 {score} · 걸린 규칙 {rules}"
    if p.basis and p.basis.signals:
        signals = " · ".join(f"{s.label}({_LEVEL_KO[s.level]})" for s in p.basis.signals)
        text += f" · 징후 {signals}"
    return text


# 걸린 시간 — "40초" · "6분" · "6분 30초" · "1시간 5분" · "2일 3시간"
def span_text(seconds: int) -> str:
    if seconds < 60:
        return f"{seconds}초"
    if seconds < 3600:
        minutes, rest = divmod(seconds, 60)
        return f"{minutes}분" + (f" {rest}초" if rest else "")
    if seconds < 86400:
        hours, rest = divmod(seconds, 3600)
        return f"{hours}시간" + (f" {rest // 60}분" if rest // 60 else "")
    days, rest = divmod(seconds, 86400)
    return f"{days}일" + (f" {rest // 3600}시간" if rest // 3600 else "")


def _at(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


# 계산값 — 사건 결제 합계 · 건수, 첫~마지막 간격, 결제 사이 간격 (ADR-0086 3절)
def computed_facts(view: ReportView) -> list[AnswerFact]:
    payments = view.paymentAnalyses
    names = payment_names(view)
    total = sum(p.amount for p in payments)
    lines = [f"사건 결제 {len(payments)}건 합계 {total:,}원"]
    if len(payments) >= 2:
        ordered = sorted(payments, key=lambda p: _at(p.requestedAt))
        first, last = ordered[0], ordered[-1]
        span = int((_at(last.requestedAt) - _at(first.requestedAt)).total_seconds())
        lines.append(
            f"가장 이른 결제({names[first.paymentId]}, {_kst(first.requestedAt)})부터 "
            f"가장 늦은 결제({names[last.paymentId]}, {_kst(last.requestedAt)})까지 "
            f"{span_text(span)}"
        )
        gaps = [
            f"{names[a.paymentId]} → {names[b.paymentId]} "
            + span_text(int((_at(b.requestedAt) - _at(a.requestedAt)).total_seconds()))
            for a, b in zip(ordered, ordered[1:], strict=False)
        ]
        lines.append("결제 사이 간격(시각 순): " + " · ".join(gaps))
    return [
        AnswerFact(id=f"계산-{n}", text=text, source="COMPUTED")
        for n, text in enumerate(lines, start=1)
    ]


def report_facts(view: ReportView) -> list[AnswerFact]:
    names = payment_names(view)
    facts = [
        AnswerFact(id=f.id, text=f.text, source="REPORT", paymentId=f.paymentId) for f in view.facts
    ]
    facts += [
        AnswerFact(
            id=f"결제-{n}",
            text=_payment_text(names[p.paymentId], p),
            source="PAYMENT",
            paymentId=p.paymentId,
        )
        for n, p in enumerate(view.paymentAnalyses, start=1)
    ]
    return facts + rule_facts(view) + normal_facts(view) + computed_facts(view)


# 정상 거래 근거와 그 근거가 설명하는 징후 (Codex 리뷰 #756)
# Spring이 계산해 보고서에 저장한 값 그대로 — AI가 근거와 징후의 관계를 스스로 잇지 않게 한다
# 연결은 화면(caseNormalNotes)과 같이 explains로 말한다
# explains가 비면 canExplain으로 "이 결제엔 그 징후가 없다"고 적는다
def normal_facts(view: ReportView) -> list[AnswerFact]:
    names = payment_names(view)
    lines: list[tuple[str, str]] = []
    for p in view.paymentAnalyses:
        for normal in p.basis.normals if p.basis else []:
            head = f"{names[p.paymentId]} · 정상 거래 근거 {normal.label}"
            if normal.explains:
                text = f"{head} → 이 결제의 {' · '.join(normal.explains)} 징후를 설명"
            else:
                can = " · ".join(normal.canExplain)
                text = f"{head} → {can} 징후를 설명할 수 있으나 이 결제에는 그 징후가 없음"
            lines.append((text, p.paymentId))
    return [
        AnswerFact(id=f"정상-{n}", text=text, source="PAYMENT", paymentId=pid)
        for n, (text, pid) in enumerate(lines, start=1)
    ]


# 적중 규칙의 기준 · 이번 값 · 가산 점수 — 보고서에 저장된 값 그대로 (Codex 리뷰 #756)
# 셋 다 없으면(예전 보고서) 이름은 결제-N에 있으니 만들지 않는다
def rule_facts(view: ReportView) -> list[AnswerFact]:
    names = payment_names(view)
    lines: list[tuple[str, str]] = []
    for p in view.paymentAnalyses:
        for rule in p.ruleHitDetails:
            parts = []
            if rule.criterion:
                parts.append(f"기준 {rule.criterion}")
            if rule.observed:
                parts.append(f"이번 값 {rule.observed}")
            if rule.appliedScore is not None:
                parts.append(f"가산 {rule.appliedScore}점")
            if parts:
                lines.append(
                    (f"{names[p.paymentId]} · {rule.name}: " + " · ".join(parts), p.paymentId)
                )
    return [
        AnswerFact(id=f"규칙-{n}", text=text, source="PAYMENT", paymentId=pid)
        for n, (text, pid) in enumerate(lines, start=1)
    ]


# 앞 질문에서 새로 조회한 값 — 번호가 이번 추가-N과 겹치지 않게 앞-N으로 다시 붙인다
# 앞 답 문장 자체는 넣지 않는다 (근거는 사실에서만)
# 원문은 바꾸지 않는다 — 조회 시각은 AI에게 보일 때만 붙인다
# (Codex 리뷰 #756: 원문에 붙이면 이어 넘길수록 시각이 쌓이고 같은 사실이 둘로 갈림)
def previous_facts(previous: list[PreviousTurn]) -> list[AnswerFact]:
    added = [f for turn in previous for f in turn.facts if f.source in ("ADDED", "PREVIOUS")]
    seen: set[tuple[str | None, str]] = set()
    facts: list[AnswerFact] = []
    for fact in added:
        key = (fact.toolName, fact.text)
        if key in seen:
            continue
        seen.add(key)
        facts.append(fact.model_copy(update={"id": f"앞-{len(facts) + 1}", "source": "PREVIOUS"}))
    return facts


# AI에게 보이는 사실 문장 — 앞 질문 조회값만 조회 시각을 붙인다
# 답 검사도 이 문장으로 한다 — 보인 시각을 인용한 답이 숫자 검사에 걸리지 않게 (Codex 리뷰 #756)
def shown_text(fact: AnswerFact) -> str:
    if fact.source == "PREVIOUS" and fact.retrievedAt:
        return f"{fact.text} ({_kst(fact.retrievedAt)} 조회)"
    return fact.text


def prompt_line(fact: AnswerFact) -> str:
    return f"{fact.id}: {shown_text(fact)}"


# 판정 질문 거절 때 보일 확인할 것 전체 — 결제별 징후의 확인할 것 + AI 추가 제안 (중복 제거)
def report_checks(view: ReportView) -> list[str]:
    checks: list[str] = []
    for p in view.paymentAnalyses:
        for signal in p.basis.signals if p.basis else []:
            if signal.checkBefore and signal.checkBefore not in checks:
                checks.append(signal.checkBefore)
    checks += [c for c in view.additionalChecks if c not in checks]
    return checks


# 새로 조회한 결과 → 사실 줄. 결제 이름(첫 번째 결제)을 붙이려고 보고서 결제로 결제 목록을 만든다
def _case_payments(view: ReportView, like: PaymentHistoryTool | LinkedPaymentsTool) -> ToolRecord:
    cases = CasePaymentsTool(
        caseId=like.caseId,
        evidenceVersion=like.evidenceVersion,
        source="보고서",
        retrievedAt=like.retrievedAt,
        payments=[
            CasePayment(
                paymentId=p.paymentId,
                amount=p.amount,
                paymentStatus=None,
                requestedAt=p.requestedAt,
            )
            for p in view.paymentAnalyses
        ],
        missingPaymentIds=[],
    )
    return ToolRecord("get_case_payments", {}, cases, True)


def added_facts(
    view: ReportView,
    tool_name: str,
    result: PaymentHistoryTool | LinkedPaymentsTool,
    start: int,
) -> list[AnswerFact]:
    records = [_case_payments(view, result), ToolRecord(tool_name, {}, result)]
    lines = _history(records) if isinstance(result, PaymentHistoryTool) else _linked(records)
    return [
        AnswerFact(
            id=f"추가-{start + n}",
            text=text,
            source="ADDED",
            paymentId=pid,
            toolName=tool_name,
            retrievedAt=result.retrievedAt,
        )
        for n, (text, _, pid, _) in enumerate(lines)
    ]
