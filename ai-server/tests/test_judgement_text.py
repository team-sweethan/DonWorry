import json

from app.agent.graph import run_investigation
from app.agent.narrative import narrative_schema
from app.config import Settings
from tests.fakes import ScriptedLlm, context, narrative, toolbox

# 판단 문장은 AI가 쓰지 않고 사실만 쓴다 (#532, #548)
# 판정 권고 문장(결론 한 줄 · 권고 요약 · 결제별 이유)은 #644에서 보고서에서도 뺐다
SETTINGS = Settings(_env_file=None)


def _run(*outputs: str):
    llm = ScriptedLlm(turns=[], outputs=list(outputs))
    return llm, run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)


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
