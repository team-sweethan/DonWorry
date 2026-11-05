import logging
from typing import Any

import pytest
from langchain_core.messages import BaseMessage, ToolMessage

from app.agent import prompts
from app.agent.graph import run_investigation
from app.agent.tools import ExtraTool, StaticToolBox, ToolBox
from app.config import Settings
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool, ToolResult
from tests.fakes import ScriptedLlm, call, context, narrative, toolbox

# 기본 조회는 코드가 하고 AI는 추가 조사만 고른다 (#578)
# 진짜 추가 도구는 아직 없어 테스트용 추가 도구로 틀을 확인한다

SETTINGS = Settings(_env_file=None)
BASE_TOOLS = [
    "get_case_payments",
    "get_fds_evidence",
    "find_similar_cases",
    "get_payment_history",
    "find_linked_payments",
]
INTERNAL_ERROR = "http://spring-internal:8080/internal/ai/tools 500 Internal Server Error"


def _spec(name: str, properties: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": "테스트용 추가 조회",
            "parameters": {"type": "object", "properties": properties or {}, "required": []},
        },
    }


def _history(box: ToolBox, args: dict[str, Any]) -> ToolResult:
    return box.get_payment_history()


def _broken(box: ToolBox, args: dict[str, Any]) -> ToolResult:
    raise RuntimeError(INTERNAL_ERROR)


EXTRA = ExtraTool(
    spec=_spec("look_extra", {"days": {"type": "integer"}}), title="추가 조회", run=_history
)
BROKEN = ExtraTool(spec=_spec("look_broken"), title="고장 난 조회", run=_broken)


def _texts(messages: list[BaseMessage]) -> list[str]:
    return [str(m.content) for m in messages]


# 추가 도구가 없으면 AI를 부르지 않고 코드가 기본 조회 5개를 한 번씩 한다
# FDS 근거는 사건 결제 번호를 코드가 넣어 조회한다
def test_base_lookups_run_by_code_without_ai_choice() -> None:
    llm = ScriptedLlm(
        turns=[[call("get_case_payments")]],
        outputs=[narrative(["9001", "9002"])],
        repeat_last_turn=True,
    )

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    assert llm.choose_messages == []
    assert [(r.tool_name, r.args, r.filled_by_system) for r in outcome.records] == [
        ("get_case_payments", {}, True),
        ("get_fds_evidence", {"payment_ids": ["9001", "9002"]}, True),
        ("find_similar_cases", {}, True),
        ("get_payment_history", {}, True),
        ("find_linked_payments", {}, True),
    ]


class _FailingBox(StaticToolBox):
    def __init__(self, failing: str) -> None:
        base = toolbox("sample_case.json")
        super().__init__(base.get_case_payments(), base.get_fds_evidence(None))
        self._failing = failing

    def get_case_payments(self) -> CasePaymentsTool:
        if self._failing == "get_case_payments":
            raise ConnectionError(INTERNAL_ERROR)
        return super().get_case_payments()

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool:
        if self._failing == "get_fds_evidence":
            raise ConnectionError(INTERNAL_ERROR)
        return super().get_fds_evidence(payment_ids)


# 결제 목록 · FDS 근거를 못 보면 일부 근거로 성공 보고서를 만들지 않는다 — 직원은 수동 판정
@pytest.mark.parametrize("failing", ["get_case_payments", "get_fds_evidence"])
def test_base_required_lookup_failure_ends_failed(failing: str) -> None:
    llm = ScriptedLlm(turns=[], outputs=[narrative(["9001", "9002"])])

    outcome = run_investigation(context(), llm, _FailingBox(failing), SETTINGS)

    assert outcome.status == "FAILED"
    assert outcome.report is None
    assert llm.write_calls == 0


# 추가 조회가 실패하면 AI에게는 정해진 문장만 주고, 오류 원문(내부 주소 등)은 로그에만 남긴다
def test_extra_tool_failure_sends_fixed_message_to_ai(caplog: pytest.LogCaptureFixture) -> None:
    llm = ScriptedLlm(turns=[[call("look_broken")], None], outputs=[narrative(["9001", "9002"])])

    with caplog.at_level(logging.WARNING):
        outcome = run_investigation(
            context(), llm, toolbox("sample_case.json"), SETTINGS, extra_tools=(BROKEN,)
        )

    assert outcome.status == "SUCCEEDED"
    failed = [m for m in llm.choose_messages[-1] if isinstance(m, ToolMessage)]
    assert [m.content for m in failed] == [prompts.EXTRA_TOOL_FAILED]
    assert all(INTERNAL_ERROR not in t for msgs in llm.choose_messages for t in _texts(msgs))
    assert INTERNAL_ERROR in caplog.text
    steps = outcome.report.investigationTrace.steps
    assert [s.received for s in steps if s.title == "고장 난 조회"] == ["조회 실패 — 없이 진행"]


# 추가 조사에서는 기본 조회 · 없는 도구를 거절하고, 도구 설명에 없는 인자는 버리고,
# 정해진 횟수를 넘으면 더 묻지 않는다 — 거절한 호출도 횟수에 든다
def test_extra_investigation_refuses_base_and_unknown_tools_and_caps_calls() -> None:
    llm = ScriptedLlm(
        turns=[
            [call("get_case_payments"), call("approve_payment", payment_id="9001")],
            [call("look_extra", days=90, case_id="999")],
        ],
        outputs=[narrative(["9001", "9002"])],
        repeat_last_turn=True,
    )

    outcome = run_investigation(
        context(), llm, toolbox("sample_case.json"), SETTINGS, extra_tools=(EXTRA,)
    )

    assert outcome.status == "SUCCEEDED"
    assert SETTINGS.agent_max_extra_calls == 3
    assert len(llm.choose_messages) == 2
    chosen = [(r.tool_name, r.args) for r in outcome.records if not r.filled_by_system]
    assert chosen == [("look_extra", {"days": 90})]
    refused = [
        str(m.content)
        for m in llm.choose_messages[1]
        if isinstance(m, ToolMessage) and m.name in {"get_case_payments", "approve_payment"}
    ]
    assert len(refused) == 2 and all("허용되지 않은 도구" in t for t in refused)


# AI는 기본 조회 결과를 보고 고르며, 받는 도구 목록은 추가 도구뿐이다
def test_extra_investigation_sees_base_results() -> None:
    llm = ScriptedLlm(turns=[None], outputs=[narrative(["9001", "9002"])])

    run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS, extra_tools=(EXTRA,))

    assert llm.choose_tools_offered == [["look_extra"]]
    seen = "\n".join(_texts(llm.choose_messages[0]))
    assert '"paymentId":"9001"' in seen
    assert all(f"[{name}]" in seen for name in BASE_TOOLS)
    assert "cardDisplayLabel" not in seen
