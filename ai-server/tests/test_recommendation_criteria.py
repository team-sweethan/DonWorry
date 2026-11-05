import json

from app.agent.narrative import narrative_schema
from app.agent.prompts import WRITE_EXAMPLES, WRITE_SYSTEM
from tests.fakes import catalog, narrative
from tests.test_narrative_quality import CASES, FDS

# 권고는 AI가 고르지 않는다 — 판정 기준표(코드)가 정한다 (#501)
# AI는 판단하지 않고 조사한 사실만 전달한다 (#548)


# AI 출력 형식 · 예시에 권고 고르기 칸이 없다
def test_ai_does_not_choose_recommendation() -> None:
    schema = json.dumps(narrative_schema(catalog(CASES, FDS)), ensure_ascii=False)
    examples = json.dumps(WRITE_EXAMPLES, ensure_ascii=False)

    assert "recommendation" not in schema and "recommendation" not in examples
    assert "판단하지 않는다" in WRITE_SYSTEM
    assert f"출력 예시 {len(WRITE_EXAMPLES)}개" in WRITE_SYSTEM


# 예전 권고 고르기 기준(어느 쪽이 무거운지 AI가 따지기)은 지시문에서 빠졌다
def test_prompt_has_no_ai_weighing_rules() -> None:
    assert "REJECT_REVIEW(승인 취소 검토):" not in WRITE_SYSTEM
    assert "어느 쪽이 더 무거운지를 권고로 고른다" not in WRITE_SYSTEM


# AI가 예전처럼 권고 칸을 써도 보고서 권고는 기준표 판정이다
def test_investigation_ignores_ai_recommendation_and_keeps_basis() -> None:
    from app.agent.graph import run_investigation
    from app.config import Settings
    from tests.fakes import ScriptedLlm, call, context, toolbox

    written = json.loads(narrative())
    written["recommendation"] = "REJECT_REVIEW"
    llm = ScriptedLlm(
        turns=[[call("get_case_payments")]], outputs=[json.dumps(written, ensure_ascii=False)]
    )

    outcome = run_investigation(
        context(), llm, toolbox("sample_case.json"), Settings(_env_file=None)
    )

    assert outcome.status == "SUCCEEDED"
    # 샘플은 주의 징후뿐이라 기준표 판정은 추가 확인 — AI가 쓴 승인 취소 검토는 쓰지 않는다
    assert outcome.report.verdict.overallRecommendation == "NEEDS_CHECK"


# 출력 예시에 판단 칸(결론 한 줄 · 권고 요약 · 이유 · 위험/정상 근거)이 없다 (#532, #548)
def test_examples_have_no_judgement_fields() -> None:
    for example in WRITE_EXAMPLES:
        out = example["출력"]
        assert set(out) == {"summary", "topics", "additionalChecks"}
        assert "riskEvidence" not in json.dumps(out) and "normalEvidence" not in json.dumps(out)
