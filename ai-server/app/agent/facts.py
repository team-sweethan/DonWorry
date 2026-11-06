from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from app.agent.assemble import (
    FDS_RESULT_KO,
    HISTORY_SHOWN_TO_AI,
    PAYMENT_STATUS_KO,
    SIMILARITY_KO,
    evidence_id,
    similar_outcome,
    split_history,
)
from app.agent.tools import ToolRecord
from app.schemas.case_context import CaseContext
from app.schemas.report import FactItem
from app.schemas.tools import (
    CasePaymentsTool,
    FdsEvidenceTool,
    HistoryPayment,
    LinkedMatch,
    LinkedPaymentsTool,
    PaymentHistoryTool,
    Signals,
    SimilarCasesTool,
)

# AI는 판단하지 않고 조회한 사실을 주제별로 전달한다 (#548)
# 코드가 Tool 기록을 사실 한 줄씩으로 만들고 번호를 붙인다 — AI 문장은 이 번호로만 근거를 댄다

KST = timezone(timedelta(hours=9))


@dataclass(frozen=True)
class Fact:
    id: str
    topic: str
    text: str
    evidence_id: str
    # 접속 정보처럼 결제 하나의 사실이면 그 결제 ID — 화면 링크가 그 결제 카드로 간다 (#559)
    payment_id: str | None = None
    # AI에게 줄 순서 — 눈여겨볼 사실 먼저, "없음" 사실은 뒤 (#568)
    rank: int = 1


# 무엇을 눈여겨볼지는 AI가 고르지 않고 코드가 정한다 (#568) — 판단 방지
# NOTABLE: 다른 고객과 겹침 · 연속 결제, NOTHING: 겹친 것 · 결제 · 사건 없음
NOTABLE, PLAIN, NOTHING = 0, 1, 2


# collect가 돌려주는 한 줄 — (사실 문장, 기록 순번, 결제 ID 또는 None, 순서 등급)
Line = tuple[str, int, str | None, int]


# 조회 도구(Tool) 하나 = 주제 하나. Tool이 늘면 FACT_TOPICS에 한 줄 + collect 함수만 추가한다
# collect: Tool 기록 → 사실 줄 목록. 기록이 없으면(조회 실패 · 안 부름) 빈 목록 → 그 주제는 빠진다
@dataclass(frozen=True)
class FactTopic:
    key: str
    prefix: str
    tool: str
    collect: Callable[[list[ToolRecord]], list[Line]]


def _latest(records: list[ToolRecord], kind: type) -> tuple[object, int] | None:
    found = [
        (r.result, seq) for seq, r in enumerate(records, start=1) if isinstance(r.result, kind)
    ]
    return found[-1] if found else None


# 화면과 같은 한국 시각 — "11/05 13:02"
def _kst(value: str | None) -> str:
    if not value:
        return "시각 모름"
    return datetime.fromisoformat(value).astimezone(KST).strftime("%m/%d %H:%M")


def _won(amount: int | None) -> str:
    return "금액 모름" if amount is None else f"{amount:,}원"


_ORDINALS = ("첫", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열")


# 사실 문장에서 사건 결제를 부르는 말 — 1건이면 "이번 결제", 여러 건이면 "첫 번째 결제" (#568)
# 순서는 사건 결제 목록(화면 결제 카드) 순서. 결제 번호는 화면 카드 제목 · 링크에만 쓴다
def payment_names(records: list[ToolRecord]) -> dict[str, str]:
    found = _latest(records, CasePaymentsTool)
    if found is None:
        return {}
    assert isinstance(found[0], CasePaymentsTool)
    ids = [p.paymentId for p in found[0].payments]
    if len(ids) == 1:
        return {ids[0]: "이번 결제"}
    return {
        pid: f"{_ORDINALS[i]} 번째 결제" if i < len(_ORDINALS) else f"{i + 1}번째 결제"
        for i, pid in enumerate(ids)
    }


def _name(names: dict[str, str], payment_id: str) -> str:
    return names.get(payment_id, f"결제 {payment_id}")


# 이력 첫 줄의 괄호 — 같은 결제를 코드가 나눠 잇는다, AI 추론에 맡기지 않음 (#568)
# 예: "모두 이번 결제 앞뒤 10분 안 · 그 전 결제 없음"
# 이력이 잘렸으면(최근 것만 조회) 못 본 결제가 있으니 "없음" 대신 "조회 못 함" (#568 Codex 리뷰)
def _split_note(
    subject: str, single: bool, burst: list, earlier: list, later: list, truncated: bool
) -> str:
    unseen = "조회 못 함" if truncated else "없음"
    no_earlier = "그 전 결제는 조회 못 함" if truncated else "그 전 결제 없음"
    if burst and not earlier and not later:
        return f"모두 {subject} 앞뒤 10분 안 · {no_earlier}"
    parts = [
        f"{subject} 앞뒤 10분 안 {len(burst)}건" if burst else f"{subject} 앞뒤 10분 안 {unseen}"
    ]
    parts.append(f"그 전 {len(earlier)}건" if earlier else no_earlier)
    if later:
        parts.append(f"{'뒤' if single else '도중 · 뒤'} {len(later)}건")
    return " · ".join(parts)


def _payment_line(p: HistoryPayment) -> str:
    status = PAYMENT_STATUS_KO.get(p.paymentStatus or "", "상태 모름")
    return f"{_kst(p.requestedAt)} {_won(p.amount)} {status}"


def _history(records: list[ToolRecord]) -> list[Line]:
    found = _latest(records, PaymentHistoryTool)
    cases = _latest(records, CasePaymentsTool)
    if found is None:
        return []
    history, seq = found
    assert isinstance(history, PaymentHistoryTool)
    days = history.lookbackDays
    if not history.payments:
        return [(f"최근 {days}일 이 고객의 다른 결제 없음", seq, None, NOTHING)]
    more = " (더 있음, 최근 것만 조회)" if history.truncated else ""
    counts = " · ".join(
        f"{PAYMENT_STATUS_KO.get(c.paymentStatus, c.paymentStatus)} {c.count}건"
        for c in history.statusCounts
    )
    head = f"최근 {days}일 이 고객의 다른 결제 {len(history.payments)}건{more}"
    if cases is None:
        return [(f"{head}: {counts}", seq, None, PLAIN)]
    assert isinstance(cases[0], CasePaymentsTool)
    single = len(cases[0].payments) == 1
    subject = "이번 결제" if single else "사건 결제"
    burst, earlier, later = split_history(cases[0], history)
    note = _split_note(subject, single, burst, earlier, later, history.truncated)
    lines = [(f"{head} ({note}): {counts}", seq, None, NOTABLE if burst else PLAIN)]
    for label, group, rank in (
        (f"{subject} 앞뒤 10분 안의 다른 결제", burst, NOTABLE),
        (
            "이번 결제보다 앞선 결제(최근 것부터)" if single else "사건 전 결제(최근 것부터)",
            earlier,
            PLAIN,
        ),
        ("이번 결제 뒤 결제" if single else "사건 도중 · 뒤 결제", later, PLAIN),
    ):
        if group:
            # 건수는 전체, 목록은 최근 몇 건만 (#548 Codex 리뷰)
            # 잘린 목록 개수를 건수로 쓰면 연속 결제 규모가 줄어 보인다
            shown = group[:HISTORY_SHOWN_TO_AI]
            items = " · ".join(_payment_line(p) for p in shown)
            rest = len(group) - len(shown)
            more = f"(최근 {len(shown)}건만 표시)" if rest else ""
            tail = f" 외 {rest}건" if rest else ""
            lines.append((f"{label} {len(group)}건{more}: {items}{tail}", seq, None, rank))
    return lines


_KINDS = (("카드", "card"), ("기기", "device"), ("IP", "ip"))


def _linked_rank(match: LinkedMatch) -> int:
    if not match.provided:
        return PLAIN
    return NOTABLE if match.paymentCount > 0 else NOTHING


def _linked_line(owner: str, kind: str, match: LinkedMatch, days: int) -> str:
    if not match.provided:
        return f"{owner} · 같은 {kind}: {kind} 정보를 가맹점이 보내지 않아 찾지 못함"
    if match.paymentCount == 0:
        return f"{owner} · 같은 {kind}: 최근 {days}일 다른 고객 결제 없음"
    results = " · ".join(
        f"{FDS_RESULT_KO.get(c.result, c.result)} {c.count}건" for c in match.resultCounts
    )
    line = (
        f"{owner} · 같은 {kind}: 최근 {days}일 다른 고객 {match.otherCustomerCount}명 · "
        f"결제 {match.paymentCount}건"
    )
    if results:
        line += f" (FDS 판정 {results})"
    return line + f", 카드사 거절 {match.declinedCount}건"


# 결제별 칸이 있으면 결제마다, 없으면(예전 Spring) 사건 전체 값으로 쓴다 (#533)
def _linked(records: list[ToolRecord]) -> list[Line]:
    found = _latest(records, LinkedPaymentsTool)
    if found is None:
        return []
    linked, seq = found
    assert isinstance(linked, LinkedPaymentsTool)
    days = linked.lookbackDays
    names = payment_names(records)
    matches: list[tuple[str, str, LinkedMatch]] = []
    if linked.payments:
        for links in linked.payments:
            own = (links.card, links.device, links.ip)
            owner = _name(names, links.paymentId)
            matches += [(owner, kind, m) for (kind, _), m in zip(_KINDS, own, strict=True)]
    else:
        matches = [("사건 결제 전체", kind, getattr(linked, attr)) for kind, attr in _KINDS]
    return [
        (_linked_line(owner, kind, m, days), seq, None, _linked_rank(m))
        for owner, kind, m in matches
    ]


_NOT_SENT = "정보 없음(가맹점이 보내지 않음)"


def _signal_line(owner: str, signals: Signals | None) -> str:
    if signals is None:
        return f"{owner}: 카드 · 기기 · 접속 정보 없음"
    if signals.ipCountryCode:
        code = signals.ipCountryCode
        country = "한국" if code == "KR" else f"해외({code})"
    else:
        country = "모름" if signals.ipProvided else _NOT_SENT
    card = "정보 있음" if signals.cardProvided else _NOT_SENT
    device = "정보 있음" if signals.deviceProvided else _NOT_SENT
    return f"{owner}: 카드 {card} · 기기 {device} · 접속 국가 {country}"


# 결제마다 마지막으로 본 FDS 기록 — 보고서에 쓴 값과 같다
def _signals(records: list[ToolRecord]) -> list[Line]:
    latest: dict[str, tuple[Signals | None, int]] = {}
    for seq, record in enumerate(records, start=1):
        if isinstance(record.result, FdsEvidenceTool):
            for payment in record.result.payments:
                latest[payment.paymentId] = (payment.signals, seq)
    names = payment_names(records)
    return [
        (_signal_line(_name(names, pid), signals), seq, pid, PLAIN)
        for pid, (signals, seq) in latest.items()
    ]


def _similar(records: list[ToolRecord]) -> list[Line]:
    found = _latest(records, SimilarCasesTool)
    if found is None:
        return []
    similar, seq = found
    assert isinstance(similar, SimilarCasesTool)
    if not similar.cases:
        return [("비슷한 과거 사건 없음", seq, None, NOTHING)]
    lines = []
    if similar.totalMatched > len(similar.cases):
        lines.append(f"비슷한 과거 사건 {similar.totalMatched}건 중 {len(similar.cases)}건만 조회")
    for c in similar.cases:
        rules = " · ".join(rule.name for rule in c.sharedRules) or "없음"
        lines.append(
            f"{SIMILARITY_KO[c.similarity]} 사건: 겹친 규칙 {rules}, "
            f"직원 판정 {similar_outcome(c.acceptCount, c.rejectCount)}"
        )
    return [(line, seq, None, PLAIN) for line in lines]


FACT_TOPICS: tuple[FactTopic, ...] = (
    FactTopic("history", "이력", "get_payment_history", _history),
    FactTopic("linked", "연결", "find_linked_payments", _linked),
    FactTopic("signals", "접속", "get_fds_evidence", _signals),
    FactTopic("similar", "과거", "find_similar_cases", _similar),
)


# 사실 목록 — AI에게 줄 때와 보고서에 저장할 때 같은 함수로 만든다 (번호가 어긋나지 않게)
# 조회 기록이 없는 주제(조회 실패 · 안 부름)는 빼서 "없다"는 사실로 보이지 않게 한다
def fact_catalog(context: CaseContext, records: list[ToolRecord]) -> list[Fact]:
    facts: list[Fact] = []
    for topic in FACT_TOPICS:
        for n, (text, seq, pid, rank) in enumerate(topic.collect(records), start=1):
            fid = f"{topic.prefix}-{n}"
            facts.append(Fact(fid, topic.key, text, evidence_id(context, seq), pid, rank))
    return facts


# AI에게 주는 [조사 사실] — 눈여겨볼 사실 먼저, "없음" 사실은 뒤 (#568)
# 같은 등급 안에서는 주제 순서 그대로. 결제 번호는 주지 않는다 (문장에 번호가 나오지 않게)
def prompt_facts(facts: list[Fact], payment_ids: list[str]) -> str:
    lines = [f"사건 결제 {len(payment_ids)}건"]
    lines += [f"{fact.id}: {fact.text}" for fact in sorted(facts, key=lambda f: f.rank)]
    return "\n".join(lines)


# 보고서에 저장할 번호별 사실 — 화면은 번호의 주제로 링크할 표 카드를 찾는다 (#559)
def report_facts(facts: list[Fact]) -> list[FactItem]:
    return [
        FactItem(
            id=f.id,
            topic=f.topic,
            text=f.text,
            evidenceId=f.evidence_id,
            paymentId=f.payment_id,
            notable=f.rank == NOTABLE,
        )
        for f in facts
    ]
