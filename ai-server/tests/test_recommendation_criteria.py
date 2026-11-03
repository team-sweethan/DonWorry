import json

from app.agent.narrative import emptied_errors, sanitize_narrative, validate_narrative
from app.agent.prompts import WRITE_EXAMPLES, WRITE_SYSTEM
from tests.fakes import narrative

# 권고 기준 조정 — "추가 확인" 위주에서 판정 지원 위주로
# 추가 확인 필요는 모르는 사실이 있을 때만, 근거가 기울면 매입 진행 · 승인 취소 검토를 고른다

PAYMENTS = ["9001", "9002"]


def _with(pid: str, **payment: object) -> str:
    written = json.loads(narrative(PAYMENTS))
    written["payments"][pid].update(payment)
    return json.dumps(written, ensure_ascii=False)


# 프롬프트가 추가 확인 필요로 쏠리게 하던 문장이 없고, 예시가 권고 세 가지를 하나씩 보여 준다
def test_prompt_does_not_default_to_needs_check() -> None:
    assert "대부분의 사건은" not in WRITE_SYSTEM
    examples = {p["recommendation"] for e in WRITE_EXAMPLES for p in e["payments"].values()}
    assert examples == {"ACCEPT_REVIEW", "REJECT_REVIEW", "NEEDS_CHECK"}
    assert f"출력 예시 {len(WRITE_EXAMPLES)}개" in WRITE_SYSTEM


# 정상 정황은 같은 종류의 위험 신호만 설명한다 — 금액 이력으로 해외 접속 · 새 기기를 덮지 않게
# (실측: measure_mixed가 평소 금액 이력만으로 매입 진행 검토가 나왔다)
def test_prompt_limits_what_normal_evidence_explains() -> None:
    assert "같은 종류의 위험 신호만 설명한다" in WRITE_SYSTEM
    assert "설명되지 않은 위험 신호가 남아 있고 그 이유를 모르면" in WRITE_SYSTEM


# needsCheck_withoutUnknowns_isRewritten — 모르는 사실 없이 추가 확인 필요를 고르면 다시 쓰게 한다
def test_needs_check_without_unknowns_is_rejected() -> None:
    parsed, errors = validate_narrative(_with("9001", unknowns=[]), PAYMENTS, None)

    assert parsed is None
    assert len(errors) == 1
    assert "결제 9001" in errors[0]


# 매입 진행 · 승인 취소 검토는 모르는 사실이 없어도 된다 — 근거로 결론을 낸 경우다
def test_decisive_recommendation_without_unknowns_passes() -> None:
    for recommendation in ("ACCEPT_REVIEW", "REJECT_REVIEW"):
        raw = _with("9001", unknowns=[], recommendation=recommendation)

        parsed, errors = validate_narrative(raw, PAYMENTS, None)

        assert errors == []
        assert parsed is not None


# 예시의 모르는 사실을 그대로 써서 코드가 빼면 추가 확인 필요의 근거가 사라진다 → 다시 쓰기
def test_needs_check_with_only_example_unknowns_is_rewritten() -> None:
    example_unknown = WRITE_EXAMPLES[0]["payments"]["<결제 ID>"]["unknowns"][0]
    parsed, errors = validate_narrative(_with("9001", unknowns=[example_unknown]), PAYMENTS, None)
    assert errors == []

    cleaned, removed = sanitize_narrative(parsed)

    assert removed == 1
    assert any("결제 9001" in error for error in emptied_errors(cleaned))


# 전체 흐름 — 모르는 사실 없는 추가 확인 필요는 다시 쓰게 하고, 다시 쓴 승인 취소 검토로 끝난다
def test_investigation_rewrites_empty_needs_check() -> None:
    from app.agent.graph import run_investigation
    from app.config import Settings
    from tests.fakes import ScriptedLlm, call, context
    from tests.test_narrative_quality import _toolbox_with_linked

    first = json.loads(narrative(PAYMENTS))
    second = json.loads(narrative(PAYMENTS))
    for pid in PAYMENTS:
        first["payments"][pid]["unknowns"] = []
        second["payments"][pid]["recommendation"] = "REJECT_REVIEW"
    llm = ScriptedLlm(
        turns=[[call("get_case_payments")]],
        outputs=[json.dumps(first, ensure_ascii=False), json.dumps(second, ensure_ascii=False)],
    )

    outcome = run_investigation(context(), llm, _toolbox_with_linked(), Settings(_env_file=None))

    assert outcome.status == "SUCCEEDED"
    assert llm.write_calls == 2
    assert outcome.report.verdict.overallRecommendation == "REJECT_REVIEW"
