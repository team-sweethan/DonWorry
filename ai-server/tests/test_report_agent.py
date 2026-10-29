import json

from langchain_core.messages import HumanMessage, ToolMessage

from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.config import Settings
from tests.fakes import BrokenLlm, ScriptedLlm, call, context, narrative, toolbox

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
        "paymentAnalyses",
        "timeline",
        "overallAnalysis",
        "additionalChecks",
        "recommendation",
        "evidence",
        "verdict",
        "investigationTrace",
        "similarCases",
    ]
    first = report["paymentAnalyses"][0]
    assert (first["paymentId"], first["amount"], first["riskScore"]) == ("9001", 650000, 65)
    assert first["ruleHits"] == ["HIGH_AMOUNT", "NEW_CARD"]
    assert report["overview"]["totalAmount"] == 770000
    assert [e["toolName"] for e in report["evidence"]] == [
        "get_case_payments",
        "get_fds_evidence",
        "find_similar_cases",
    ]
    assert [e["source"] for e in report["evidence"]] == ["P3·P5", "P2", "P3·P2"]
    assert report["evidence"][0]["dataVersion"] == "evidence v2"
    assert "FDS 점수는 부정거래 확률이 아닙니다." in report["recommendation"]["limitations"]


# Agent가 결제 하나의 FDS 근거를 빠뜨리면 알려 주고, Agent가 스스로 다시 고른다
def test_missing_evidence_is_nudged_and_agent_picks_it() -> None:
    llm = ScriptedLlm(
        turns=[
            [call("get_case_payments")],
            [call("get_fds_evidence", payment_ids=["9001"])],
            None,
            [call("get_fds_evidence", payment_ids=["9002"])],
            [call("find_similar_cases")],
            None,
        ],
        outputs=[narrative(["9001", "9002"])],
    )

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert [r.args for r in outcome.records] == [
        {},
        {"payment_ids": ["9001"]},
        {"payment_ids": ["9002"]},
        {},
    ]
    assert not any(r.filled_by_system for r in outcome.records)
    nudges = [m for m in llm.choose_messages[-1] if isinstance(m, HumanMessage)]
    assert any("9002" in str(m.content) for m in nudges)


# 알려 줘도 계속 안 보면 코드가 보충하고, 증거 추적에 자동 보충으로 남긴다
def test_agent_ignoring_nudges_gets_system_fill() -> None:
    llm = ScriptedLlm(turns=[[call("get_case_payments")]], outputs=[narrative(["9001", "9002"])])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    filled = [r for r in outcome.records if r.filled_by_system]
    assert [r.tool_name for r in filled] == ["get_fds_evidence", "find_similar_cases"]
    assert [e.source for e in outcome.report.evidence][-2:] == [
        "P2 (자동 보충)",
        "P3·P2 (자동 보충)",
    ]


# 허용 목록에 없는 도구는 실행하지 않고 Agent에게 거절을 알린다
def test_disallowed_tool_is_refused() -> None:
    llm = ScriptedLlm(
        turns=[
            [call("approve_payment", payment_id="9001")],
            [call("get_case_payments")],
            [call("get_fds_evidence")],
            None,
        ],
        outputs=[narrative(["9001", "9002"])],
    )

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert "approve_payment" not in [r.tool_name for r in outcome.records]
    refused = [
        m
        for m in llm.choose_messages[1]
        if isinstance(m, ToolMessage) and m.name == "approve_payment"
    ]
    assert "허용되지 않은 도구" in str(refused[0].content)


# 도구를 끝없이 부르면 최대 횟수에서 멈춘다
def test_tool_call_limit_stops_agent() -> None:
    llm = ScriptedLlm(
        turns=[[call("get_case_payments")]],
        outputs=[narrative(["9001", "9002"])],
        repeat_last_turn=True,
    )

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    chosen = [r for r in outcome.records if not r.filled_by_system]
    assert len(chosen) == SETTINGS.agent_max_tool_calls
    assert outcome.status == "SUCCEEDED"


# 사건 밖 결제를 고르면 도구가 거절하고, Agent는 다시 고른다
def test_payment_outside_case_is_reported_to_agent() -> None:
    llm = ScriptedLlm(
        turns=[
            [call("get_case_payments")],
            [call("get_fds_evidence", payment_ids=["7777"])],
            [call("get_fds_evidence")],
            [call("find_similar_cases")],
            None,
        ],
        outputs=[narrative(["9001", "9002"])],
    )

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert [r.args for r in outcome.records] == [{}, {}, {}]


# 형식이 틀리면 오류를 알려 고쳐 쓰게 한다
def test_invalid_narrative_is_repaired() -> None:
    llm = ScriptedLlm(turns=[], outputs=["형식 아님", narrative(["9001", "9002"])])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert llm.write_calls == 2


# 근거에 없는 결제 ID를 쓰면 형식 오류로 돌려보내고, 최종 보고서에도 들어가지 않는다
def test_unknown_payment_id_in_narrative_is_rejected() -> None:
    wrong = json.loads(narrative(["9001", "9002"]))
    wrong["payments"]["9999"] = wrong["payments"]["9001"]
    llm = ScriptedLlm(
        turns=[], outputs=[json.dumps(wrong, ensure_ascii=False), narrative(["9001", "9002"])]
    )

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert llm.write_calls == 2
    assert [p.paymentId for p in outcome.report.paymentAnalyses] == ["9001", "9002"]


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


# 결론 · 이유는 AI 문장에서, 규칙 근거는 FDS 조회 기록에서 채운다 — 증거 ID는 그 결제를 받은 조회 줄
def test_assemble_fills_verdict_from_narrative_and_rule_hits_from_tool_records() -> None:
    written = json.loads(narrative(["9001", "9002"]))
    written["payments"]["9002"]["reason"] = "두 번째 결제는 짧은 간격의 반복 결제입니다."
    llm = ScriptedLlm(
        turns=[
            [call("get_case_payments")],
            [call("get_fds_evidence", payment_ids=["9001"])],
            [call("get_fds_evidence", payment_ids=["9002"])],
            None,
        ],
        outputs=[json.dumps(written, ensure_ascii=False)],
    )

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    report = outcome.report
    assert report.verdict.headline == written["headline"]
    first, second = report.paymentAnalyses
    assert second.reason == "두 번째 결제는 짧은 간격의 반복 결제입니다."
    assert [(r.code, r.name, r.appliedScore, r.evidenceId) for r in first.ruleHitDetails] == [
        ("HIGH_AMOUNT", "고액 결제", 35, "E-101-v1-02"),
        ("NEW_CARD", "처음 쓰는 카드", 30, "E-101-v1-02"),
    ]
    assert {r.evidenceId for r in second.ruleHitDetails} == {"E-101-v1-03"}
    assert {"E-101-v1-02", "E-101-v1-03"} <= {e.evidenceId for e in report.evidence}


# 결제 하나라도 승인 취소 검토면 종합 권고도 승인 취소 검토다
def test_overall_recommendation_is_reject_when_any_payment_is_reject() -> None:
    written = json.loads(narrative(["9001", "9002"]))
    written["payments"]["9001"]["recommendation"] = "ACCEPT_REVIEW"
    written["payments"]["9002"]["recommendation"] = "REJECT_REVIEW"
    llm = ScriptedLlm(turns=[], outputs=[json.dumps(written, ensure_ascii=False)])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert outcome.report.verdict.overallRecommendation == "REJECT_REVIEW"
