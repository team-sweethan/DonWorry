import json

from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.config import Settings
from tests.fakes import LINKED_SUMMARY, BrokenLlm, ScriptedLlm, context, narrative, toolbox

SETTINGS = Settings(_env_file=None)
FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())


# 보고서의 사실 값은 Tool 응답 그대로이고, 증거 추적은 Agent가 실제로 부른 순서다
def test_mock_flow_builds_report_from_tool_records() -> None:
    outcome = run_investigation(context(), MockLlmClient(), toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    report = outcome.report.model_dump()
    assert list(report) == [
        "overview",
        "summary",
        "summaryFacts",
        "paymentAnalyses",
        "timeline",
        "facts",
        "additionalChecks",
        "recommendation",
        "evidence",
        "verdict",
        "investigationTrace",
        "similarCases",
        "paymentHistory",
        "linkedPayments",
    ]
    first = report["paymentAnalyses"][0]
    assert (first["paymentId"], first["amount"], first["riskScore"]) == ("9001", 650000, 65)
    assert first["ruleHits"] == ["HIGH_AMOUNT", "NEW_CARD"]
    assert report["overview"]["totalAmount"] == 770000
    assert [e["toolName"] for e in report["evidence"]] == [
        "get_case_payments",
        "get_fds_evidence",
        "find_similar_cases",
        "get_payment_history",
        "find_linked_payments",
    ]
    assert [e["source"] for e in report["evidence"]] == ["P3·P5", "P2", "P3·P2", "P5", "P2"]
    assert report["evidence"][0]["dataVersion"] == "evidence v2"
    assert "FDS 점수는 부정거래 확률이 아닙니다." in report["recommendation"]["limitations"]


# 형식이 틀리면 오류를 알려 고쳐 쓰게 한다
def test_invalid_narrative_is_repaired() -> None:
    llm = ScriptedLlm(turns=[], outputs=["형식 아님", narrative(["9001", "9002"])])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert llm.write_calls == 2


# 고쳐 쓰기 횟수를 넘으면 실패로 끝낸다 — 자동 판정 없음
def test_narrative_keeps_failing_ends_failed() -> None:
    llm = ScriptedLlm(turns=[], outputs=["형식 아님"])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "FAILED"
    assert outcome.report is None
    assert outcome.error["code"] == "NARRATIVE_INVALID"
    assert llm.write_calls == SETTINGS.agent_max_repairs + 1


# 못 찾은 값은 추측하지 않고 미확인으로 남긴다
# 결제 정보 · FDS 점수를 하나라도 못 찾으면 성공 보고서를 만들지 않는다
# 일부만 합친 총액을 확정값처럼 내보내지 않게 — 기존 고정 보고서(#250)와 같은 계약
def test_missing_evidence_ends_failed() -> None:
    outcome = run_investigation(
        context("102", 1), MockLlmClient(), toolbox("sample_missing.json"), SETTINGS
    )

    assert outcome.status == "FAILED"
    assert outcome.report is None
    assert outcome.error["code"] == "EVIDENCE_MISSING"
    assert "9101" in outcome.error["reason"] and "9102" in outcome.error["reason"]


# 조사 중 Case 증거 버전이 바뀌었으면 낡은 근거로 성공 보고서를 만들지 않는다
def test_stale_evidence_ends_failed() -> None:
    outcome = run_investigation(
        context(evidence_version=3), MockLlmClient(), toolbox("sample_case.json"), SETTINGS
    )

    assert outcome.status == "FAILED"
    assert outcome.error["code"] == "EVIDENCE_STALE"


def test_llm_error_ends_failed() -> None:
    outcome = run_investigation(context(), BrokenLlm(), toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "FAILED"
    assert outcome.error["code"] == "AGENT_ERROR"


def test_fixture_matches_spring_tool_shape() -> None:
    assert set(FIXTURE) == {"casePayments", "fdsEvidence"}


# 결론 · 이유는 판정 근거로 코드가, 규칙 근거는 FDS 조회 기록에서 채운다 (#532)
# 증거 ID는 그 결제를 받은 조회 줄 — 기본 조회는 FDS 근거를 한 번에 받는다 (#578)
def test_assemble_fills_verdict_from_basis_and_rule_hits_from_tool_records() -> None:
    written = json.loads(narrative(["9001", "9002"]))
    llm = ScriptedLlm(turns=[], outputs=[json.dumps(written, ensure_ascii=False)])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    report = outcome.report
    assert report.verdict.headline == (
        "결제 2건 모두 추가 확인 — 정상 사유 없는 징후: 고액 결제 · 신규 카드 · 단시간 반복 결제"
    )
    first, second = report.paymentAnalyses
    assert second.reason == "정상 사유가 없는 주의 징후가 있습니다: 단시간 반복 결제 · 신규 카드"
    assert [(r.code, r.name, r.appliedScore, r.evidenceId) for r in first.ruleHitDetails] == [
        ("HIGH_AMOUNT", "고액 결제", 35, "E-101-v1-02"),
        ("NEW_CARD", "처음 쓰는 카드", 30, "E-101-v1-02"),
    ]
    assert {r.evidenceId for r in second.ruleHitDetails} == {"E-101-v1-02"}


# 결제 하나라도 승인 취소 검토면 종합 권고도 승인 취소 검토다
def test_overall_recommendation_is_reject_when_any_payment_is_reject() -> None:
    from app.agent.assemble import overall_recommendation

    assert overall_recommendation(["ACCEPT_REVIEW", "REJECT_REVIEW"]) == "REJECT_REVIEW"
    assert overall_recommendation(["ACCEPT_REVIEW", "ACCEPT_REVIEW"]) == "ACCEPT_REVIEW"
    assert overall_recommendation(["ACCEPT_REVIEW", "NEEDS_CHECK"]) == "NEEDS_CHECK"


# 결제별 권고는 판정 기준표가 정한다 (#501) — 같은 카드를 다른 고객이 썼고 새 카드 징후도 있으면
# AI가 매입 진행 검토라고 써도 승인 취소 검토
def test_recommendation_comes_from_basis_not_ai() -> None:
    from app.schemas.tools import LinkedPaymentsTool
    from tests.test_linked_payments import LINKED, _toolbox

    written = json.loads(narrative(summary=LINKED_SUMMARY))
    written["recommendation"] = "ACCEPT_REVIEW"
    llm = ScriptedLlm(turns=[], outputs=[json.dumps(written, ensure_ascii=False)])
    toolbox_with_linked = _toolbox(LinkedPaymentsTool.model_validate(LINKED))

    outcome = run_investigation(context(), llm, toolbox_with_linked, SETTINGS)

    assert outcome.status == "SUCCEEDED"
    for analysis in outcome.report.paymentAnalyses:
        assert analysis.basis is not None
        assert analysis.basis.rule == "HIGH_RISK_WITH_OTHERS"
        assert analysis.recommendation == analysis.basis.recommendation == "REJECT_REVIEW"
    assert outcome.report.verdict.overallRecommendation == "REJECT_REVIEW"
