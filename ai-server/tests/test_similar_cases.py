import json

from langchain_core.messages import HumanMessage

from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.narrative import narrative_schema
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool, SimilarCasesTool
from tests.fakes import ScriptedLlm, call, context, narrative

# 비슷한 과거 사건 (#296) — 사실 값은 Tool 기록에서 코드가 채운다
# AI가 안 부르면 다시 요청 → 그래도 안 하면 시스템 보충

SETTINGS = Settings(_env_file=None)
FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())
SIMILAR = {
    "caseId": "101",
    "evidenceVersion": 2,
    "source": "P3·P2",
    "retrievedAt": "2026-10-27T12:05:00Z",
    "cases": [
        {
            "caseId": "41",
            "similarity": "SAME_CUSTOMER",
            "sharedRules": [{"code": "HIGH_AMOUNT", "name": "고액"}],
            "closedAt": "2026-10-20T03:00:00Z",
            "acceptCount": 0,
            "rejectCount": 1,
        },
        {
            "caseId": "29",
            "similarity": "SHARED_RULES",
            "sharedRules": [
                {"code": "HIGH_AMOUNT", "name": "고액"},
                {"code": "NEW_CARD", "name": "처음 쓰는 카드"},
            ],
            "closedAt": "2026-10-09T03:00:00Z",
            "acceptCount": 1,
            "rejectCount": 1,
        },
    ],
    "totalMatched": 7,
}


def _toolbox(similar: dict | None = None) -> StaticToolBox:
    return StaticToolBox(
        CasePaymentsTool.model_validate(FIXTURE["casePayments"]),
        FdsEvidenceTool.model_validate(FIXTURE["fdsEvidence"]),
        SimilarCasesTool.model_validate(similar or SIMILAR),
    )


class _BrokenSimilarToolBox(StaticToolBox):
    def find_similar_cases(self) -> SimilarCasesTool:
        raise ConnectionError("Spring에 연결할 수 없다")


# 보고서의 비슷한 사건(번호 · 이유 · 결과 건수)은 Tool 응답 그대로다 — AI가 지어낼 칸이 없다
def test_similar_cases_filled_from_tool_record_not_ai() -> None:
    outcome = run_investigation(context(), MockLlmClient(), _toolbox(), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    saved = outcome.report.similarCases.model_dump()
    assert saved == {"cases": SIMILAR["cases"], "totalMatched": 7}
    # AI가 쓰는 판단 문장 형식에는 비슷한 사건 칸이 없다
    assert "similar" not in json.dumps(narrative_schema(["9001", "9002"])).lower()


# AI가 비슷한 사건을 안 보면 한 번 더 요청하고, 그래도 안 보면 시스템이 조회한다
def test_similar_cases_nudged_then_system_filled() -> None:
    llm = ScriptedLlm(
        turns=[[call("get_case_payments")], [call("get_fds_evidence")], None],
        outputs=[narrative(["9001", "9002"])],
    )

    outcome = run_investigation(context(), llm, _toolbox(), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    nudges = [m for m in llm.choose_messages[-1] if isinstance(m, HumanMessage)]
    assert any("비슷한 과거 사건" in str(m.content) for m in nudges)
    filled = [
        s
        for s in outcome.report.investigationTrace.steps
        if s.title == "비슷한 과거 사건 조회 (시스템 보충)"
    ]
    assert [s.actor for s in filled] == ["SYSTEM"]
    assert outcome.report.similarCases.totalMatched == 7


# 보충 조회까지 실패해도 판정에 필요한 보고서는 만든다 — 비슷한 사건 칸만 비어 있다
def test_similar_cases_fill_failure_still_succeeds() -> None:
    toolbox = _BrokenSimilarToolBox(
        CasePaymentsTool.model_validate(FIXTURE["casePayments"]),
        FdsEvidenceTool.model_validate(FIXTURE["fdsEvidence"]),
    )
    outcome = run_investigation(context(), MockLlmClient(), toolbox, SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert outcome.report.similarCases is None
    fill = [s for s in outcome.report.investigationTrace.steps if s.actor == "SYSTEM"][-2]
    assert fill.received == "조회 실패 — 비슷한 사건 없이 진행"
