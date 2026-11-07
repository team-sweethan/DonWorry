import json
from datetime import datetime, timedelta

from langchain_core.messages import HumanMessage

from app.agent.facts import prompt_facts
from app.agent.graph import run_investigation
from app.agent.narrative import validate_narrative
from app.config import Settings
from app.schemas.tools import LinkedPaymentsTool
from tests.fakes import LINKED_SUMMARY, ScriptedLlm, call, catalog, context, narrative, toolbox
from tests.test_linked_payments import LINKED, FailingLinkedToolBox, _toolbox
from tests.test_linked_per_payment import CLEAN
from tests.test_narrative_quality import CASES, FDS, PAYMENTS, _history

# AI는 판단하지 않고 조사한 사실을 이어서 요약한다 (#548, #559)
# 코드는 뜻을 단어로 추측하지 않고, 사실 번호 · 숫자 · 겉모양만 검사한다 — 걸리면 다시 쓰기

SETTINGS = Settings(_env_file=None)
FACTS = catalog(CASES, FDS, LinkedPaymentsTool.model_validate(LINKED))


def _check(text: str, *ids: str) -> list[str]:
    summary = [{"text": text, "facts": list(ids)}]
    return validate_narrative(narrative(summary=summary), FACTS, PAYMENTS)[1]


# 요약 문장에 근거 번호가 없거나 지어낸 번호면 다시 쓰게 한다
def test_validate_summary_missingOrUnknownFactId_requestsRewrite() -> None:
    missing = _check("같은 카드를 다른 고객 2명이 썼습니다.")
    unknown = _check("같은 카드를 다른 고객 2명이 썼습니다.", "연결-9")

    assert any("사실 번호를 facts에 하나 이상" in e for e in missing)
    assert any("준 적 없는 사실 번호 ['연결-9']" in e for e in unknown)


# 요약 속 숫자가 인용한 사실에 없으면(15건 ≠ 3건) 다시 쓰게 한다 — 작은 수도 본다
def test_validate_summary_numberNotInCitedFacts_requestsRewrite() -> None:
    wrong = _check("같은 카드로 다른 고객이 15건 결제했습니다.", "연결-1")
    small = _check("같은 카드로 다른 고객이 4건 결제했습니다.", "연결-1")
    right = _check("같은 카드로 다른 고객 2명이 결제 3건을 했습니다.", "연결-1")

    assert any("없는 숫자 [15]" in e for e in wrong)
    assert any("없는 숫자 [4]" in e for e in small)
    assert right == []


# "매입을 취소해야 합니다" 같은 판단 문구는 지우지 않고 다시 쓰게 한다 — 판정은 보안 직원 몫
def test_validate_facts_judgementPhrase_requestsRewrite() -> None:
    errors = _check("다른 고객 2명이 같은 카드를 써서 매입을 취소해야 합니다.", "연결-1")

    assert any("판단 문구" in e for e in errors)


# AI에게 준 번호 · 사실과 보고서에 저장한 번호 · 사실이 같다 — 링크가 그 조회 기록의 표로 간다
def test_fact_catalog_sameIdsInPromptAndReport() -> None:
    llm = ScriptedLlm(
        turns=[[call("get_case_payments")]], outputs=[narrative(summary=LINKED_SUMMARY)]
    )

    outcome = run_investigation(
        context(), llm, _toolbox(LinkedPaymentsTool.model_validate(LINKED)), SETTINGS
    )

    assert outcome.status == "SUCCEEDED"
    prompt = next(m.content for m in llm.write_messages[0] if isinstance(m, HumanMessage))
    saved = outcome.report.facts
    assert saved and all(f"{f.id}: {f.text}" in prompt for f in saved)
    evidence = {e.evidenceId: e.toolName for e in outcome.report.evidence}
    assert evidence[next(f for f in saved if f.topic == "linked").evidenceId] == (
        "find_linked_payments"
    )
    # 접속 정보는 결제 카드로 링크하도록 결제 ID를 함께 둔다 (#559)
    assert {f.paymentId for f in saved if f.topic == "signals"} == set(PAYMENTS)


# 조회에 실패한 도구의 사실은 빼서 "같은 카드 결제 없음" 같은 사실로 보이지 않게 한다
def test_fact_catalog_failedTool_topicOmitted() -> None:
    box = FailingLinkedToolBox(CASES, FDS)
    llm = ScriptedLlm(turns=[[call("get_case_payments")]], outputs=[narrative()])

    outcome = run_investigation(context(), llm, box, SETTINGS)

    assert outcome.status == "SUCCEEDED"
    ids = llm.write_schemas[0]["properties"]["summary"]["items"]["properties"]["facts"]
    assert not [i for i in ids["items"]["enum"] if i.startswith("연결")]
    assert all(f.topic != "linked" for f in outcome.report.facts)


# 사장님 화면이 읽는 summary(문장 목록)는 그대로, 링크용 summaryFacts는 같은 문장 · 같은 순서
# 기준표 판정은 그대로, 주제별 · 위험/정상 칸은 없다
def test_assemble_summaryFacts_keepsSummaryTextsForMerchant() -> None:
    llm = ScriptedLlm(turns=[[call("get_case_payments")]], outputs=[narrative()])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    body = outcome.report.model_dump()
    written = json.loads(narrative())["summary"]
    assert body["summary"] == [s["text"] for s in written]
    assert body["summaryFacts"] == [{"text": s["text"], "factIds": s["facts"]} for s in written]
    assert "factTopics" not in body and "overallAnalysis" not in body
    assert all(
        "riskEvidence" not in p and "normalEvidence" not in p for p in body["paymentAnalyses"]
    )


# --- 요약 다듬기 (#568) — 사건 결제 1건(9001)
ONE = CASES.model_copy(update={"payments": CASES.payments[:1]})
FDS_ONE = FDS.model_copy(update={"payments": FDS.payments[:1]})
LINKED_ONE = LinkedPaymentsTool.model_validate(
    {
        **LINKED,
        "payments": [
            {"paymentId": "9001", "card": LINKED["card"], "device": CLEAN, "ip": LINKED["ip"]}
        ],
    }
)


# 이력 첫 줄에 코드가 나눈 결과를 쓴다 — "그 전 결제 없음"이 빠지면 연속 결제를 평소 결제로 오해
def test_fact_catalog_historyLine_saysBurstAndEarlierSplit() -> None:
    at = datetime.fromisoformat(ONE.payments[0].requestedAt)
    burst = _history(
        ("8102", 600000, at - timedelta(minutes=3)), ("8103", 600000, at - timedelta(minutes=1))
    )
    mixed = _history(
        ("8102", 600000, at - timedelta(minutes=3)), ("8001", 580000, at - timedelta(days=20))
    )

    def first_line(history) -> str:
        return next(f.text for f in catalog(ONE, history) if f.topic == "history")

    assert first_line(burst).startswith(
        "최근 30일 이 고객의 다른 결제 2건 (모두 이번 결제 앞뒤 10분 안 · 그 전 결제 없음)"
    )
    assert "(이번 결제 앞뒤 10분 안 1건 · 그 전 1건)" in first_line(mixed)


# 사건 결제가 1건이면 사실 문장에 결제 번호 대신 "이번 결제" — 번호가 요약에 나오지 않게
def test_fact_catalog_singlePayment_saysThisPayment() -> None:
    facts = catalog(ONE, FDS_ONE, LINKED_ONE)

    texts = [f.text for f in facts if f.topic in ("linked", "signals")]
    assert texts and all(t.startswith("이번 결제") for t in texts)
    assert not any("9001" in t for t in texts)
    # 링크용 결제 ID는 그대로 둔다 (#559)
    assert {f.payment_id for f in facts if f.topic == "signals"} == {"9001"}


# 눈여겨볼 사실(다른 고객과 겹침)이 먼저, "없음" 사실은 뒤 — 요약 첫 문장이 핵심을 놓치지 않게
def test_prompt_facts_notableFactsFirst() -> None:
    facts = catalog(ONE, FDS_ONE, LINKED_ONE)

    lines = prompt_facts(facts, ["9001"]).splitlines()

    assert lines[0] == "사건 결제 1건"
    assert "같은 카드: 최근 30일 다른 고객 2명" in lines[1]
    assert "같은 기기: 최근 30일 다른 고객 결제 없음" in lines[-1]
    assert not any("9001" in line for line in lines)


# 눈여겨볼 사실(다른 고객과 겹침 · 연속 결제)이 요약에서 빠지면 다시 쓰게 한다 (#568)
# Case 5 재생성에서 같은 IP 4명 겹침이 빠진 채 통과했다 — 직원은 "겹친 것 없음"으로 읽는다
def test_validate_summary_notableFactMissing_requestsRewrite() -> None:
    facts = catalog(ONE, FDS_ONE, LINKED_ONE)
    access = {"text": "이번 결제의 카드 · 기기 · 접속 정보를 모았습니다.", "facts": ["접속-1"]}
    card = {"text": "같은 카드를 다른 고객 2명이 결제 3건에 썼습니다.", "facts": ["연결-1"]}

    _, skipped = validate_narrative(narrative(summary=[access]), facts, ["9001"])
    _, cited = validate_narrative(narrative(summary=[card, access]), facts, ["9001"])

    assert any("눈여겨볼 사실 ['연결-1']" in e for e in skipped)
    assert cited == []


# 이력이 잘렸으면(최근 것만 조회) 조회한 결제가 모두 연속 결제여도
# "그 전 결제 없음"이라고 단정하지 않는다
# 더 오래된 평소 결제가 있는 고객을 "처음 온 손님"으로 읽게 된다 (#568 Codex 리뷰)
def test_fact_catalog_truncatedHistory_doesNotSayNoEarlier() -> None:
    at = datetime.fromisoformat(ONE.payments[0].requestedAt)
    history = _history(
        ("8102", 600000, at - timedelta(minutes=3)), ("8103", 600000, at - timedelta(minutes=1))
    ).model_copy(update={"truncated": True})

    first = next(f.text for f in catalog(ONE, history) if f.topic == "history")

    assert "없음" not in first
    assert "그 전 결제는 조회 못 함" in first
