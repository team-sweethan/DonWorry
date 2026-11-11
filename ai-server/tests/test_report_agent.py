import json

from app.agent.assemble import BASIS_UNAVAILABLE
from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import DecisionBasisTool
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
        "investigationTrace",
        "similarCases",
        "paymentHistory",
        "linkedPayments",
        "caseOverview",
        "summaryAppended",
        "scope",
        "orderContext",
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
        "get_order_context",
        "get_decision_basis",
    ]
    assert [e["source"] for e in report["evidence"]] == [
        "P3·P5",
        "P2",
        "P3·P2",
        "P5",
        "P2",
        "P5",
        "P4",
    ]
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
    assert set(FIXTURE) == {"casePayments", "fdsEvidence", "decisionBasis"}


# 규칙 근거는 FDS 조회 기록에서 채운다 (#532)
# 증거 ID는 그 결제를 받은 조회 줄 — 기본 조회는 FDS 근거를 한 번에 받는다 (#578)
def test_assemble_fills_rule_hits_from_tool_records() -> None:
    written = json.loads(narrative(["9001", "9002"]))
    llm = ScriptedLlm(turns=[], outputs=[json.dumps(written, ensure_ascii=False)])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    first, second = outcome.report.paymentAnalyses
    assert [(r.code, r.name, r.appliedScore, r.evidenceId) for r in first.ruleHitDetails] == [
        ("HIGH_AMOUNT", "고액 결제", 35, "E-101-v1-02"),
        ("NEW_CARD", "처음 쓰는 카드", 30, "E-101-v1-02"),
    ]
    assert {r.evidenceId for r in second.ruleHitDetails} == {"E-101-v1-02"}


# 판정 권고는 보고서에 만들지 않는다 (#644, ADR-0009 결정 13)
# 옛 Spring이 기준표 칸 · 권고를 보내고 AI가 권고 칸을 써도 보고서에는 권고 키가 없다
# 징후 · 정상 근거 · 한계 줄은 남는다
def test_assembleReport_whenBasisReceived_hasNoRecommendationKeys() -> None:
    from app.schemas.tools import LinkedPaymentsTool
    from tests.test_linked_payments import LINKED, _toolbox

    written = json.loads(narrative(summary=LINKED_SUMMARY))
    written["recommendation"] = "ACCEPT_REVIEW"
    llm = ScriptedLlm(turns=[], outputs=[json.dumps(written, ensure_ascii=False)])
    toolbox_with_linked = _toolbox(LinkedPaymentsTool.model_validate(LINKED))
    toolbox_with_linked = StaticToolBox(
        toolbox_with_linked.get_case_payments(),
        toolbox_with_linked.get_fds_evidence(None),
        linked=toolbox_with_linked.find_linked_payments(),
        basis=DecisionBasisTool.model_validate(
            {**FIXTURE["decisionBasis"], "payments": [_reject("9001"), _reject("9002")]}
        ),
    )

    outcome = run_investigation(context(), llm, toolbox_with_linked, SETTINGS)

    assert outcome.status == "SUCCEEDED"
    report = outcome.report.model_dump()
    assert "verdict" not in report
    assert list(report["recommendation"]) == ["limitations"]
    assert report["recommendation"]["limitations"]
    for analysis in report["paymentAnalyses"]:
        assert "recommendation" not in analysis and "reason" not in analysis
        assert list(analysis["basis"]) == ["signals", "normals"]
        assert [(s["key"], s["level"]) for s in analysis["basis"]["signals"]] == [
            ("LINKED_CARD", "HIGH_RISK"),
            ("R03", "CAUTION"),
            ("R01", "CAUTION"),
        ]


class _NoBasisToolBox(StaticToolBox):
    def get_decision_basis(self) -> DecisionBasisTool:
        raise RuntimeError("Spring 503")


# 옛 Spring 응답 모양 — 기준표 칸 · 권고가 함께 온다 (#644 전)
def _reject(payment_id: str) -> dict:
    signals = [
        {"key": "LINKED_CARD", "label": "같은 카드를 다른 고객이 사용", "detail": None},
        {"key": "R03", "label": "신규 카드", "detail": None},
        {"key": "R01", "label": "고액 결제", "detail": None},
    ]
    levels = ["HIGH_RISK", "CAUTION", "CAUTION"]
    return {
        "paymentId": payment_id,
        "basis": {
            "signals": [
                {**s, "level": lv, "normalReason": None}
                for s, lv in zip(signals, levels, strict=True)
            ],
            "normals": [],
            "rule": "HIGH_RISK_WITH_OTHERS",
            "recommendation": "REJECT_REVIEW",
        },
    }


# 징후 · 정상 근거 조회가 실패해도 보고서는 만들고 한계 줄로 사실대로 남긴다 (#618)
def test_basis_lookup_fails_reportKeepsLimitation() -> None:
    sample = toolbox("sample_case.json")
    box = _NoBasisToolBox(sample.get_case_payments(), sample.get_fds_evidence(None))
    written = json.loads(narrative(["9001", "9002"]))
    llm = ScriptedLlm(turns=[], outputs=[json.dumps(written, ensure_ascii=False)])

    outcome = run_investigation(context(), llm, box, SETTINGS)

    assert outcome.status == "SUCCEEDED"
    report = outcome.report
    assert [a.basis for a in report.paymentAnalyses] == [None, None]
    assert BASIS_UNAVAILABLE in report.recommendation.limitations
