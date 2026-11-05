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
from app.agent.evidence import payment_links
from app.agent.tools import ToolRecord
from app.schemas.case_context import CaseContext
from app.schemas.report import FactItem, FactSentenceItem, FactTopicSection
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


# 조회 도구(Tool) 하나 = 주제 하나. Tool이 늘면 FACT_TOPICS에 한 줄 + collect 함수만 추가한다
# collect: Tool 기록 → (사실 한 줄, 그 사실을 가져온 기록 순번) 목록
# 기록이 없으면(조회 실패 · 안 부름) 빈 목록 → 그 주제는 빠진다
@dataclass(frozen=True)
class FactTopic:
    key: str
    title: str
    prefix: str
    tool: str
    collect: Callable[[list[ToolRecord]], list[tuple[str, int]]]


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


def _payment_line(p: HistoryPayment) -> str:
    status = PAYMENT_STATUS_KO.get(p.paymentStatus or "", "상태 모름")
    return f"{_kst(p.requestedAt)} {_won(p.amount)} {status}"


def _history(records: list[ToolRecord]) -> list[tuple[str, int]]:
    found = _latest(records, PaymentHistoryTool)
    cases = _latest(records, CasePaymentsTool)
    if found is None:
        return []
    history, seq = found
    assert isinstance(history, PaymentHistoryTool)
    days = history.lookbackDays
    if not history.payments:
        return [(f"최근 {days}일 이 고객의 다른 결제 없음", seq)]
    more = " (더 있음, 최근 것만 조회)" if history.truncated else ""
    counts = " · ".join(
        f"{PAYMENT_STATUS_KO.get(c.paymentStatus, c.paymentStatus)} {c.count}건"
        for c in history.statusCounts
    )
    lines = [f"최근 {days}일 이 고객의 다른 결제 {len(history.payments)}건{more}: {counts}"]
    if cases is not None:
        assert isinstance(cases[0], CasePaymentsTool)
        burst, earlier, later = split_history(cases[0], history)
        for label, group in (
            ("사건 결제 앞뒤 10분 안의 다른 결제", burst),
            ("사건 전 결제(최근 것부터)", earlier),
            ("사건 도중 · 뒤 결제", later),
        ):
            if group:
                # 건수는 전체, 목록은 최근 몇 건만 (#548 Codex 리뷰)
                # 잘린 목록 개수를 건수로 쓰면 연속 결제 규모가 줄어 보인다
                shown = group[:HISTORY_SHOWN_TO_AI]
                items = " · ".join(_payment_line(p) for p in shown)
                rest = len(group) - len(shown)
                more = f"(최근 {len(shown)}건만 표시)" if rest else ""
                tail = f" 외 {rest}건" if rest else ""
                lines.append(f"{label} {len(group)}건{more}: {items}{tail}")
    return [(line, seq) for line in lines]


_KINDS = (("카드", "card"), ("기기", "device"), ("IP", "ip"))


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
def _linked(records: list[ToolRecord]) -> list[tuple[str, int]]:
    found = _latest(records, LinkedPaymentsTool)
    if found is None:
        return []
    linked, seq = found
    assert isinstance(linked, LinkedPaymentsTool)
    days = linked.lookbackDays
    lines: list[str] = []
    if linked.payments:
        for links in linked.payments:
            own = payment_links(linked, links.paymentId)
            assert own is not None
            for (kind, _), match in zip(_KINDS, own, strict=True):
                lines.append(_linked_line(f"결제 {links.paymentId}", kind, match, days))
    else:
        owner = "사건 결제 전체"
        for kind, attr in _KINDS:
            lines.append(_linked_line(owner, kind, getattr(linked, attr), days))
    return [(line, seq) for line in lines]


_NOT_SENT = "정보 없음(가맹점이 보내지 않음)"


def _signal_line(payment_id: str, signals: Signals | None) -> str:
    if signals is None:
        return f"결제 {payment_id}: 카드 · 기기 · 접속 정보 없음"
    if signals.ipCountryCode:
        code = signals.ipCountryCode
        country = "한국" if code == "KR" else f"해외({code})"
    else:
        country = "모름" if signals.ipProvided else _NOT_SENT
    card = "정보 있음" if signals.cardProvided else _NOT_SENT
    device = "정보 있음" if signals.deviceProvided else _NOT_SENT
    return f"결제 {payment_id}: 카드 {card} · 기기 {device} · 접속 국가 {country}"


# 결제마다 마지막으로 본 FDS 기록 — 보고서에 쓴 값과 같다
def _signals(records: list[ToolRecord]) -> list[tuple[str, int]]:
    latest: dict[str, tuple[Signals | None, int]] = {}
    for seq, record in enumerate(records, start=1):
        if isinstance(record.result, FdsEvidenceTool):
            for payment in record.result.payments:
                latest[payment.paymentId] = (payment.signals, seq)
    return [(_signal_line(pid, signals), seq) for pid, (signals, seq) in latest.items()]


def _similar(records: list[ToolRecord]) -> list[tuple[str, int]]:
    found = _latest(records, SimilarCasesTool)
    if found is None:
        return []
    similar, seq = found
    assert isinstance(similar, SimilarCasesTool)
    if not similar.cases:
        return [("비슷한 과거 사건 없음", seq)]
    lines = []
    if similar.totalMatched > len(similar.cases):
        lines.append(f"비슷한 과거 사건 {similar.totalMatched}건 중 {len(similar.cases)}건만 조회")
    for c in similar.cases:
        rules = " · ".join(rule.name for rule in c.sharedRules) or "없음"
        lines.append(
            f"{SIMILARITY_KO[c.similarity]} 사건: 겹친 규칙 {rules}, "
            f"직원 판정 {similar_outcome(c.acceptCount, c.rejectCount)}"
        )
    return [(line, seq) for line in lines]


FACT_TOPICS: tuple[FactTopic, ...] = (
    FactTopic("history", "결제 이력", "이력", "get_payment_history", _history),
    FactTopic("linked", "같은 카드 · 기기 · IP", "연결", "find_linked_payments", _linked),
    FactTopic("signals", "카드 · 기기 · 접속 정보", "접속", "get_fds_evidence", _signals),
    FactTopic("similar", "비슷한 과거 사건", "과거", "find_similar_cases", _similar),
)
TOPIC_BY_KEY = {topic.key: topic for topic in FACT_TOPICS}


# 사실 목록 — AI에게 줄 때와 보고서에 저장할 때 같은 함수로 만든다 (번호가 어긋나지 않게)
# 조회 기록이 없는 주제(조회 실패 · 안 부름)는 빼서 "없다"는 사실로 보이지 않게 한다
def fact_catalog(context: CaseContext, records: list[ToolRecord]) -> list[Fact]:
    facts: list[Fact] = []
    for topic in FACT_TOPICS:
        for n, (text, seq) in enumerate(topic.collect(records), start=1):
            facts.append(Fact(f"{topic.prefix}-{n}", topic.key, text, evidence_id(context, seq)))
    return facts


def topic_keys(facts: list[Fact]) -> list[str]:
    present = {fact.topic for fact in facts}
    return [topic.key for topic in FACT_TOPICS if topic.key in present]


# AI에게 주는 [조사 사실] — 주제별 번호와 사실 한 줄
def prompt_facts(facts: list[Fact], payment_ids: list[str]) -> str:
    lines = [f"사건 결제 ID: {', '.join(payment_ids)}"]
    for key in topic_keys(facts):
        topic = TOPIC_BY_KEY[key]
        lines.append(f"\n[{topic.title}] (topics.{key})")
        lines += [f"{fact.id}: {fact.text}" for fact in facts if fact.topic == key]
    return "\n".join(lines)


# 보고서에 저장할 주제별 문장 · 번호별 사실
def report_sections(
    facts: list[Fact], topics: dict[str, list[tuple[str, list[str]]]]
) -> tuple[list[FactTopicSection], list[FactItem]]:
    sections = [
        FactTopicSection(
            topic=key,
            title=TOPIC_BY_KEY[key].title,
            sentences=[
                FactSentenceItem(text=text, factIds=ids) for text, ids in topics.get(key, [])
            ],
        )
        for key in topic_keys(facts)
    ]
    items = [FactItem(id=f.id, topic=f.topic, text=f.text, evidenceId=f.evidence_id) for f in facts]
    return sections, items
