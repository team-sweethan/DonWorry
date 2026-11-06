import json
from typing import Any

from langchain_core.messages import AIMessage, BaseMessage

from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import PaymentHistoryTool
from tests.fakes import FIXTURES, call, context

# 첫 추가 조사 도구 — 고객 이력 90일 (#581)
# 처음 보는 손님 샘플: 30일 이력은 이번 결제 앞뒤 10분 안 거절 2건뿐
# 90일로 넓히면 매입 완료 2건이 더 나온다

SETTINGS = Settings(_env_file=None)
DATA = json.loads((FIXTURES / "measure_case5_new.json").read_text(encoding="utf-8"))


# 처음 한 번 90일 도구를 고르고, 그다음엔 "조사 완료" — 요약은 mock 답(받은 사실 번호 모두 인용)
class _Asks90:
    def __init__(self, **args: Any) -> None:
        self._args = args
        self._asked = False
        self.fact_prompts: list[str] = []

    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        if self._asked:
            return AIMessage(content="조사 완료")
        self._asked = True
        return AIMessage(content="", tool_calls=[call("get_payment_history_90d", **self._args)])

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        self.fact_prompts.append(str(messages[1].content))
        return MockLlmClient().write_json(messages, schema)


# 받은 기간을 기록하고, 원하면 90일 조회만 실패시킨다
class _RecordingBox(StaticToolBox):
    asked: list[int | None]
    fail_90: bool

    def get_payment_history(self, lookback_days: int | None = None) -> PaymentHistoryTool:
        self.asked.append(lookback_days)
        if lookback_days is not None and self.fail_90:
            raise RuntimeError("Spring 500 — http://internal")
        return super().get_payment_history(lookback_days)


def _box(fail_90: bool = False) -> _RecordingBox:
    box = _RecordingBox.from_sample(DATA)
    box.asked = []
    box.fail_90 = fail_90
    return box


def _run(llm: _Asks90, box: _RecordingBox):
    return run_investigation(context(DATA["casePayments"]["caseId"], 1), llm, box, SETTINGS)


# AI가 인자(기간 · 사건 번호)를 지어내도 버리고 90일만 조회한다 — 넓은 기간이 Spring까지 가지 않게
def test_extra_history90_ai_args_ignored() -> None:
    box = _box()

    outcome = _run(_Asks90(days=365, case_id="999"), box)

    assert outcome.status == "SUCCEEDED"
    assert box.asked == [None, 90]
    extra = [(r.tool_name, r.args) for r in outcome.records if not r.filled_by_system]
    assert extra == [("get_payment_history_90d", {})]


# 90일을 조회하면 표와 AI에게 준 사실이 둘 다 90일이다
# 표는 90일인데 문장 근거는 30일이면 서로 어긋난다
def test_extra_history90_called_table_and_facts_both_90() -> None:
    llm = _Asks90()

    outcome = _run(llm, _box())

    assert outcome.report is not None
    assert outcome.report.paymentHistory is not None
    assert outcome.report.paymentHistory.lookbackDays == 90
    assert len(outcome.report.paymentHistory.payments) == 4
    history = [f.text for f in outcome.report.facts if f.topic == "history"]
    assert history[0].startswith("최근 90일 이 고객의 다른 결제 4건")
    assert not any("30일" in text for text in history)
    assert "최근 90일" in llm.fact_prompts[0] and "최근 30일 이 고객" not in llm.fact_prompts[0]


# 90일 조회가 실패하면 30일 결과를 그대로 두고 보고서를 만든다 — 실패가 30일 근거까지 지우면 안 된다
def test_extra_history90_fails_keeps_30_and_report_made() -> None:
    outcome = _run(_Asks90(), _box(fail_90=True))

    assert outcome.status == "SUCCEEDED"
    assert outcome.report is not None
    assert outcome.report.paymentHistory is not None
    assert outcome.report.paymentHistory.lookbackDays == 30
    steps = [s for s in outcome.report.investigationTrace.steps if s.title == "고객 이력 90일 조회"]
    assert [(s.actor, s.received) for s in steps] == [("AI", "조회 실패 — 없이 진행")]


# 기준표 판정 · 정상 근거는 AI가 90일을 불렀는지와 상관없이 같다 — 같은 사건엔 같은 권고
# (#581 Codex 리뷰)
# 90일로 찾은 9월 매입 완료 2건이 "최근 30일 비슷한 금액의 결제 2건 매입 완료"로 표시되고,
# 고액 결제 사건이면 권고까지 바뀔 수 있었다 (판정 패널의 "권고와 반대" 사유 규칙이 흔들림)
def test_extra_history90_does_not_change_decision_basis() -> None:
    with_90 = _run(_Asks90(), _box())
    without = run_investigation(
        context(DATA["casePayments"]["caseId"], 1),
        MockLlmClient(),
        StaticToolBox.from_sample(DATA),
        SETTINGS,
    )

    assert with_90.report is not None and without.report is not None
    assert with_90.report.paymentHistory.lookbackDays == 90
    basis_90 = [a.basis for a in with_90.report.paymentAnalyses]
    assert basis_90 == [a.basis for a in without.report.paymentAnalyses]
    assert [a.recommendation for a in with_90.report.paymentAnalyses] == [
        a.recommendation for a in without.report.paymentAnalyses
    ]
    assert all(b is not None and b.normals == [] for b in basis_90)
