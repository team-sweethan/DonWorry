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
    ]
    first = report["paymentAnalyses"][0]
    assert (first["paymentId"], first["amount"], first["riskScore"]) == ("9001", 650000, 65)
    assert first["ruleHits"] == ["HIGH_AMOUNT", "NEW_CARD"]
    assert report["overview"]["totalAmount"] == 770000
    assert [e["toolName"] for e in report["evidence"]] == ["get_case_payments", "get_fds_evidence"]
    assert [e["source"] for e in report["evidence"]] == ["P3·P5", "P2"]
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
    assert [r.tool_name for r in filled] == ["get_fds_evidence"]
    assert outcome.report.evidence[-1].source == "P2 (자동 보충)"


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
            None,
        ],
        outputs=[narrative(["9001", "9002"])],
    )

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert [r.args for r in outcome.records] == [{}, {}]


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
def test_missing_info_is_marked_unknown() -> None:
    outcome = run_investigation(
        context("102", 1), MockLlmClient(), toolbox("sample_missing.json"), SETTINGS
    )

    assert outcome.status == "SUCCEEDED"
    report = outcome.report
    assert report.overview.paymentCount == 2
    assert [p.paymentId for p in report.paymentAnalyses] == ["9101"]
    assert report.paymentAnalyses[0].riskScore is None
    assert "FDS 평가 결과 미확인" in report.paymentAnalyses[0].unknowns
    assert any("9102" in item for item in report.recommendation.limitations)


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
