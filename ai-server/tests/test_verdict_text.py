import json

from app.agent.graph import run_investigation
from app.agent.narrative import narrative_schema
from app.agent.verdict_text import headline, recommendation_summary
from app.config import Settings
from app.schemas.report import PaymentAnalysis
from tests.fakes import ScriptedLlm, context, narrative, toolbox

# 판단 문장은 코드가 쓰고 AI는 사실만 쓴다 (#532)
# 결과(기준표 판정)와 반대되는 AI 문장이 보고서에 남지 않아야 한다
SETTINGS = Settings(_env_file=None)
PAYMENTS = ["9001", "9002"]
REJECT_TEXT = "승인 취소를 권고합니다."


def _run(*outputs: str):
    llm = ScriptedLlm(turns=[], outputs=list(outputs))
    return llm, run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)


def _contradicting() -> str:
    body = json.loads(narrative(PAYMENTS))
    body["headline"] = "두 결제 모두 " + REJECT_TEXT
    body["recommendationSummary"] = REJECT_TEXT
    body["reason"] = "처음 쓰는 카드라 " + REJECT_TEXT
    return json.dumps(body, ensure_ascii=False)


# 결과가 추가 확인인데 AI가 승인 취소를 권고해도 결론 · 권고 요약 · 이유는 결과를 따른다
def test_report_judgementText_whenAiContradicts_followsBasis() -> None:
    _, outcome = _run(_contradicting())

    report = outcome.report
    assert outcome.status == "SUCCEEDED"
    assert {a.recommendation for a in report.paymentAnalyses} == {"NEEDS_CHECK"}
    texts = [
        report.verdict.headline,
        report.recommendation.summary,
        *[a.reason for a in report.paymentAnalyses],
    ]
    assert not [t for t in texts if "승인 취소" in t]
    assert "추가 확인" in report.verdict.headline
    assert "추가 확인" in report.recommendation.summary


# 결제별 이유는 판정 근거의 징후 이름을 그대로 쓴다 — 화면 밑줄 표시와 맞는다
def test_report_reason_listsOpenSignalNames() -> None:
    _, outcome = _run(narrative(PAYMENTS))

    reasons = {a.paymentId: a.reason for a in outcome.report.paymentAnalyses}
    assert "고액 결제" in reasons["9001"] and "신규 카드" in reasons["9001"]
    assert "단시간 반복 결제" in reasons["9002"] and "신규 카드" in reasons["9002"]


# AI 사실 문장에 섞인 판단 문구는 지우지 않고 다시 쓰게 한다 (#548 — 예전엔 코드가 몰래 뺐다)
def test_narrative_judgementSentence_isRewritten() -> None:
    written = narrative(
        summary=[
            {"text": "결제마다 카드 · 기기 · 접속 정보가 없습니다.", "facts": ["접속-1"]},
            {"text": "두 결제 모두 매입 진행을 검토할 수 있습니다.", "facts": ["접속-1"]},
        ],
    )

    llm, outcome = _run(written, narrative())

    assert llm.write_calls == 2
    assert outcome.status == "SUCCEEDED"
    assert not [s for s in outcome.report.summary if "매입 진행" in s]


# 지난 결제의 상태("승인 취소된 결제")는 사실이라 걸리지 않는다
def test_narrative_pastStatusFact_isKept() -> None:
    fact = "결제 9001은 승인 취소된 기록이 아니라 카드 정보가 없는 결제입니다."
    written = narrative(
        summary=[
            {"text": "결제마다 카드 · 기기 · 접속 정보가 없습니다.", "facts": ["접속-1"]},
            {"text": fact, "facts": ["접속-1"]},
        ],
    )

    llm, outcome = _run(written)

    assert llm.write_calls == 1
    assert fact in outcome.report.summary


# AI에게 판단 칸(결론 한 줄 · 권고 요약 · 이유 · 위험/정상 근거)을 쓰라고 하지 않는다
def test_narrativeSchema_doesNotAskJudgementFields() -> None:
    from tests.fakes import catalog
    from tests.test_narrative_quality import CASES, FDS

    schema = json.dumps(narrative_schema(catalog(CASES, FDS)))

    for field in ("headline", "recommendationSummary", "reason", "riskEvidence", "normalEvidence"):
        assert field not in schema


def _analysis(pid: str, recommendation: str, basis: dict | None) -> PaymentAnalysis:
    return PaymentAnalysis.model_validate(
        {
            "paymentId": pid,
            "amount": 220000,
            "requestedAt": "2026-05-21T10:00:00Z",
            "riskScore": 50,
            "ruleHits": [],
            "unknowns": [],
            "recommendation": recommendation,
            "reason": "",
            "ruleHitDetails": [],
            "basis": basis,
        }
    )


EXPLAINED = {
    "signals": [
        {
            "key": "R01",
            "label": "고액 결제",
            "detail": None,
            "level": "CAUTION",
            "normalReason": "최근 30일 비슷한 금액의 결제 5건 매입 완료",
        }
    ],
    "normals": [],
    "rule": "ALL_EXPLAINED",
    "recommendation": "ACCEPT_REVIEW",
}


# 매입 진행 검토면 결론에 판정만 덩그러니 두지 않고 왜인지(정상 사유)를 붙인다
def test_headline_accept_says_all_explained() -> None:
    line = headline([_analysis("1", "ACCEPT_REVIEW", EXPLAINED)])

    assert line == "매입 진행 검토 — 모든 징후에 정상 사유 있음"


# 결제별 판정이 섞이면 결론은 판정별 건수, 권고 요약은 결제별 판정을 나열한다
def test_mixed_recommendations_listed_by_payment() -> None:
    analyses = [
        _analysis("1", "ACCEPT_REVIEW", EXPLAINED),
        _analysis("2", "REJECT_REVIEW", None),
    ]

    assert headline(analyses) == "결제 2건 중 승인 취소 검토 1건 · 매입 진행 검토 1건"
    assert "결제 2 승인 취소 검토, 결제 1 매입 진행 검토" in recommendation_summary(analyses)
