import json

from app.agent.narrative import (
    emptied_errors,
    narrative_schema,
    sanitize_narrative,
    validate_narrative,
)
from app.agent.prompts import WRITE_EXAMPLES, WRITE_SYSTEM
from tests.fakes import narrative

# 권고는 AI가 고르지 않는다 — 판정 기준표(코드)가 정하고 AI는 이유를 쓴다 (#501)
# 기준표 판정이 추가 확인이면 모르는 사실이 있어야 직원이 확인할 수 있다

PAYMENTS = ["9001", "9002"]
NEEDS_CHECK = {pid: "NEEDS_CHECK" for pid in PAYMENTS}


def _with(pid: str, **payment: object) -> str:
    written = json.loads(narrative(PAYMENTS))
    written["payments"][pid].update(payment)
    return json.dumps(written, ensure_ascii=False)


# AI 출력 형식 · 예시에 권고 고르기 칸이 없다
def test_ai_does_not_choose_recommendation() -> None:
    payment = narrative_schema(PAYMENTS)["properties"]["payments"]["properties"]["9001"]
    assert "recommendation" not in payment["properties"]
    assert all("recommendation" not in p for e in WRITE_EXAMPLES for p in e["payments"].values())
    assert "너가 고르지 않는다" in WRITE_SYSTEM
    assert f"출력 예시 {len(WRITE_EXAMPLES)}개" in WRITE_SYSTEM


# 예전 권고 고르기 기준(어느 쪽이 무거운지 AI가 따지기)은 지시문에서 빠졌다
def test_prompt_has_no_ai_weighing_rules() -> None:
    assert "REJECT_REVIEW(승인 취소 검토):" not in WRITE_SYSTEM
    assert "어느 쪽이 더 무거운지를 권고로 고른다" not in WRITE_SYSTEM


# 정상 거래 근거는 설명할 수 있는 징후만 설명한다 — 금액 이력으로 새 카드 · 해외 접속을 덮지 않게
# (실측: 1011번 — 매입 완료 9건으로 새 카드 · 새 기기 · 해외 접속까지 상쇄해 추가 확인이 나왔다)
def test_prompt_limits_what_normal_evidence_explains() -> None:
    assert '"설명할 수 있는 징후"만 설명한다' in WRITE_SYSTEM
    assert "다른 고객이 쓴 카드를 덮지 않는다" in WRITE_SYSTEM


# 기준표 판정이 추가 확인인데 모르는 사실이 없으면 다시 쓰게 한다
def test_needs_check_without_unknowns_is_rejected() -> None:
    raw = _with("9001", unknowns=[])

    parsed, errors = validate_narrative(raw, PAYMENTS, None, recommendations=NEEDS_CHECK)

    assert parsed is None
    assert len(errors) == 1
    assert "결제 9001" in errors[0]


# 기준표 판정이 매입 진행 · 승인 취소 검토면 모르는 사실이 없어도 된다
def test_decisive_basis_without_unknowns_passes() -> None:
    for recommendation in ("ACCEPT_REVIEW", "REJECT_REVIEW"):
        judged = {pid: recommendation for pid in PAYMENTS}

        parsed, errors = validate_narrative(
            _with("9001", unknowns=[]), PAYMENTS, None, recommendations=judged
        )

        assert errors == []
        assert parsed is not None


# 예시의 모르는 사실을 그대로 써서 코드가 빼면 추가 확인의 근거가 사라진다 → 다시 쓰기
def test_needs_check_with_only_example_unknowns_is_rewritten() -> None:
    example_unknown = WRITE_EXAMPLES[0]["payments"]["<결제 ID>"]["unknowns"][0]
    raw = _with("9001", unknowns=[example_unknown])
    parsed, errors = validate_narrative(raw, PAYMENTS, None, recommendations=NEEDS_CHECK)
    assert errors == []

    cleaned, removed = sanitize_narrative(parsed)

    assert removed == 1
    assert any("결제 9001" in error for error in emptied_errors(cleaned, NEEDS_CHECK))


# 전체 흐름 — 기준표가 추가 확인인데 모르는 사실이 없으면 다시 쓰게 하고, 다시 쓴 답으로 끝난다
# AI가 예전처럼 권고 칸을 써도 보고서 권고는 기준표 판정이다
def test_investigation_rewrites_empty_unknowns_and_keeps_basis() -> None:
    from app.agent.graph import run_investigation
    from app.config import Settings
    from tests.fakes import ScriptedLlm, call, context, toolbox

    first = json.loads(narrative(PAYMENTS))
    second = json.loads(narrative(PAYMENTS))
    for pid in PAYMENTS:
        first["payments"][pid]["unknowns"] = []
        second["payments"][pid]["recommendation"] = "REJECT_REVIEW"
    llm = ScriptedLlm(
        turns=[[call("get_case_payments")]],
        outputs=[json.dumps(first, ensure_ascii=False), json.dumps(second, ensure_ascii=False)],
    )

    outcome = run_investigation(
        context(), llm, toolbox("sample_case.json"), Settings(_env_file=None)
    )

    assert outcome.status == "SUCCEEDED"
    assert llm.write_calls == 2
    # 샘플은 주의 징후뿐이라 기준표 판정은 추가 확인 — AI가 쓴 승인 취소 검토는 쓰지 않는다
    assert outcome.report.verdict.overallRecommendation == "NEEDS_CHECK"


# 징후 이름을 그대로 써야 화면이 조사 요약에 그 징후 색 밑줄을 그을 수 있다 (#501 화면)
# 이유는 코드가 징후 이름으로 쓰고(#532, test_verdict_text) AI 문장도 같은 이름을 쓰게 한다
def test_prompt_asks_signal_names() -> None:
    assert "징후를 가리킬 때는 판정 근거의 징후 이름 그대로 쓴다" in WRITE_SYSTEM


# 출력 예시에 판단 칸(결론 한 줄 · 권고 요약 · 이유)이 없다 — AI가 판단 문장을 따라 쓰지 않게 (#532)
def test_examples_have_no_judgement_fields() -> None:
    for example in WRITE_EXAMPLES:
        assert "headline" not in example and "recommendationSummary" not in example
        assert all("reason" not in p for p in example["payments"].values())
