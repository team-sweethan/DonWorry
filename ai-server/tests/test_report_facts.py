import json

from langchain_core.messages import HumanMessage

from app.agent.graph import run_investigation
from app.agent.narrative import narrative_schema, validate_narrative
from app.config import Settings
from app.schemas.tools import LinkedPaymentsTool
from tests.fakes import ScriptedLlm, call, catalog, context, fill_topics, narrative, toolbox
from tests.test_linked_payments import LINKED, FailingLinkedToolBox, _toolbox
from tests.test_narrative_quality import CASES, FDS, PAYMENTS

# AI는 판단하지 않고 조사한 사실을 주제별로 전달한다 (#548)
# 코드는 뜻을 단어로 추측하지 않고, 사실 번호 · 숫자 · 겉모양만 검사한다 — 걸리면 다시 쓰기

SETTINGS = Settings(_env_file=None)
FACTS = catalog(CASES, FDS, LinkedPaymentsTool.model_validate(LINKED))


def _check(**overrides: object) -> list[str]:
    raw = fill_topics(narrative(**overrides), narrative_schema(FACTS))
    return validate_narrative(raw, FACTS, PAYMENTS)[1]


def _topic(key: str, text: str, *ids: str) -> dict[str, object]:
    topics = json.loads(fill_topics(narrative(), narrative_schema(FACTS)))["topics"]
    topics[key] = [{"text": text, "facts": list(ids)}]
    return topics


# 근거 번호가 없거나 지어낸 번호면 다시 쓰게 한다
def test_validate_facts_missingOrUnknownFactId_requestsRewrite() -> None:
    missing = _check(topics=_topic("linked", "같은 카드를 다른 고객 2명이 썼습니다."))
    unknown = _check(topics=_topic("linked", "같은 카드를 다른 고객 2명이 썼습니다.", "연결-9"))

    assert any("사실 번호를 facts에 하나 이상" in e for e in missing)
    assert any("준 적 없는 사실 번호 ['연결-9']" in e for e in unknown)


# 문장 속 숫자가 인용한 사실에 없으면(15건 ≠ 3건) 다시 쓰게 한다 — 작은 수도 본다
def test_validate_facts_numberNotInCitedFacts_requestsRewrite() -> None:
    wrong = _check(topics=_topic("linked", "같은 카드로 다른 고객이 15건 결제했습니다.", "연결-1"))
    small = _check(topics=_topic("linked", "같은 카드로 다른 고객이 4건 결제했습니다.", "연결-1"))
    right = _check(
        topics=_topic("linked", "같은 카드로 다른 고객 2명이 결제 3건을 했습니다.", "연결-1")
    )

    assert any("없는 숫자 [15]" in e for e in wrong)
    assert any("없는 숫자 [4]" in e for e in small)
    assert right == []


# "매입을 취소해야 합니다" 같은 판단 문구는 지우지 않고 다시 쓰게 한다 — 판정은 보안 직원 몫
def test_validate_facts_judgementPhrase_requestsRewrite() -> None:
    errors = _check(
        topics=_topic(
            "linked", "다른 고객 2명이 같은 카드를 써서 매입을 취소해야 합니다.", "연결-1"
        )
    )

    assert any("판단 문구" in e for e in errors)


# 결제 이력 칸에 같은 카드 사실 번호를 붙이면 주제가 섞인다
def test_validate_facts_otherTopicFactId_requestsRewrite() -> None:
    errors = _check(topics=_topic("signals", "같은 카드를 다른 고객이 썼습니다.", "연결-1"))

    assert any("topics.signals에는 이 주제의 사실 번호만" in e for e in errors)


# AI에게 준 번호 · 사실과 보고서에 저장한 번호 · 사실이 같다 — 누르면 그 조회 기록이 열린다
def test_fact_catalog_sameIdsInPromptAndReport() -> None:
    llm = ScriptedLlm(turns=[[call("get_case_payments")]], outputs=[narrative()])

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


# 조회에 실패한 도구의 주제는 빼서 "같은 카드 결제 없음" 같은 사실로 보이지 않게 한다
def test_fact_catalog_failedTool_topicOmitted() -> None:
    box = FailingLinkedToolBox(CASES, FDS)
    llm = ScriptedLlm(turns=[[call("get_case_payments")]], outputs=[narrative()])

    outcome = run_investigation(context(), llm, box, SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert "linked" not in narrative_schema_topics(llm)
    assert all(f.topic != "linked" for f in outcome.report.facts)
    assert [t.topic for t in outcome.report.factTopics] == ["history", "signals", "similar"]


def narrative_schema_topics(llm: ScriptedLlm) -> list[str]:
    return list(llm.write_schemas[0]["properties"]["topics"]["properties"])


# 새 보고서: 사장님 화면이 읽는 summary(문장 목록) · 기준표 판정은 그대로, 위험 · 정상 칸은 없다
def test_assemble_factsReport_keepsSummaryAndCodeVerdict() -> None:
    llm = ScriptedLlm(turns=[[call("get_case_payments")]], outputs=[narrative()])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    body = outcome.report.model_dump()
    assert body["summary"] == [s["text"] for s in json.loads(narrative())["summary"]]
    assert body["verdict"]["overallRecommendation"] == "NEEDS_CHECK"
    assert all(p["recommendation"] == p["basis"]["recommendation"] for p in body["paymentAnalyses"])
    assert "overallAnalysis" not in body
    assert all(
        "riskEvidence" not in p and "normalEvidence" not in p for p in body["paymentAnalyses"]
    )
    assert body["factTopics"] and body["facts"]
