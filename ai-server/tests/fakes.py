import json
from pathlib import Path
from typing import Any

from langchain_core.messages import AIMessage, BaseMessage

from app.agent.facts import Fact, fact_catalog
from app.agent.tools import StaticToolBox, ToolRecord
from app.schemas.case_context import CaseContext
from app.schemas.tools import (
    CasePaymentsTool,
    DecisionBasisTool,
    FdsEvidenceTool,
    LinkedPaymentsTool,
    OrderContextTool,
    PaymentHistoryTool,
    SimilarCasesTool,
)

FIXTURES = Path(__file__).parent / "fixtures"


def toolbox(name: str) -> StaticToolBox:
    data = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    # 징후 · 정상 근거는 Spring이 계산한다 (#618) — 샘플의 decisionBasis를 그대로 돌려준다
    return StaticToolBox(
        CasePaymentsTool.model_validate(data["casePayments"]),
        FdsEvidenceTool.model_validate(data["fdsEvidence"]),
        basis=DecisionBasisTool.model_validate(data["decisionBasis"])
        if "decisionBasis" in data
        else None,
    )


def context(case_id: str = "101", evidence_version: int = 2) -> CaseContext:
    return CaseContext(
        case_id=case_id,
        evidence_version=evidence_version,
        report_version=1,
        merchant_id="1",
        customer_ref="customer-1",
        case_created_at="2026-10-27T12:00:30Z",
    )


_TOOL_NAMES: dict[type, str] = {
    CasePaymentsTool: "get_case_payments",
    FdsEvidenceTool: "get_fds_evidence",
    SimilarCasesTool: "find_similar_cases",
    PaymentHistoryTool: "get_payment_history",
    LinkedPaymentsTool: "find_linked_payments",
    OrderContextTool: "get_order_context",
}


# Tool 응답들을 조회 기록 순서대로 넣어 사실 목록을 만든다 (#548)
def catalog(*results: Any) -> list[Fact]:
    records = [ToolRecord(_TOOL_NAMES[type(r)], {}, r) for r in results]
    return fact_catalog(context(), records)


def call(name: str, **args: Any) -> dict[str, Any]:
    return {"name": name, "args": args, "id": f"call-{name}-{len(args)}", "type": "tool_call"}


# 가짜 AI 출력 (#548, #559) — 요약 문장마다 근거 사실 번호
# 접속 정보(접속-1)는 FDS 근거가 있으면 늘 있어 근거로 쓴다
def narrative(payment_ids: list[str] | None = None, **overrides: Any) -> str:
    body: dict[str, Any] = {
        "summary": [
            {"text": "결제마다 카드 · 기기 · 접속 정보를 모았습니다.", "facts": ["접속-1"]},
            {"text": "조회한 기록은 아래 표에 있습니다.", "facts": ["접속-1"]},
        ],
        "additionalChecks": ["고객 본인 확인", "카드 명의 확인"],
    }
    body.update(overrides)
    return json.dumps(body, ensure_ascii=False)


# 같은 카드가 겹친 LINKED 데이터로 조사할 때의 요약 — 눈여겨볼 사실(연결-1)까지 인용해야
# 형식 검사를 통과한다 (#568)
LINKED_SUMMARY = [
    {"text": "같은 카드를 다른 고객이 쓴 결제가 있습니다.", "facts": ["연결-1", "접속-1"]}
]


# 정해진 순서로 답하는 가짜 LLM — 각 차례는 도구 호출 목록, None이면 "조사 완료"
class ScriptedLlm:
    def __init__(
        self,
        turns: list[list[dict[str, Any]] | None],
        outputs: list[str],
        repeat_last_turn: bool = False,
    ) -> None:
        self._turns = list(turns)
        self._outputs = list(outputs)
        self._repeat = repeat_last_turn
        self.choose_messages: list[list[BaseMessage]] = []
        self.choose_tools_offered: list[list[str]] = []
        self.write_messages: list[list[BaseMessage]] = []
        self.write_schemas: list[dict[str, Any]] = []
        self.write_calls = 0

    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        self.choose_messages.append(list(messages))
        self.choose_tools_offered.append([t["function"]["name"] for t in tools])
        if not self._turns:
            return AIMessage(content="조사 완료")
        turn = self._turns[0] if self._repeat and len(self._turns) == 1 else self._turns.pop(0)
        return (
            AIMessage(content="조사 완료")
            if turn is None
            else AIMessage(content="", tool_calls=turn)
        )

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        self.write_calls += 1
        self.write_messages.append(list(messages))
        self.write_schemas.append(schema)
        return self._outputs.pop(0) if len(self._outputs) > 1 else self._outputs[0]


class BrokenLlm:
    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        raise ConnectionError("Ollama에 연결할 수 없다")

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        raise ConnectionError("Ollama에 연결할 수 없다")
