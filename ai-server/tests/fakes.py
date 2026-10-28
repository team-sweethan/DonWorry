import json
from pathlib import Path
from typing import Any

from langchain_core.messages import AIMessage, BaseMessage

from app.agent.tools import StaticToolBox
from app.schemas.case_context import CaseContext
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool

FIXTURES = Path(__file__).parent / "fixtures"


def toolbox(name: str) -> StaticToolBox:
    data = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    return StaticToolBox(
        CasePaymentsTool.model_validate(data["casePayments"]),
        FdsEvidenceTool.model_validate(data["fdsEvidence"]),
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


def call(name: str, **args: Any) -> dict[str, Any]:
    return {"name": name, "args": args, "id": f"call-{name}-{len(args)}", "type": "tool_call"}


def narrative(payment_ids: list[str], **overrides: Any) -> str:
    body = {
        "summary": ["결제 2건 모두 처음 쓰는 카드로 결제됐습니다."],
        "payments": {
            payment_id: {
                "riskEvidence": ["처음 쓰는 카드 규칙 적중"],
                "normalEvidence": ["확인된 정상 정황 없음"],
                "unknowns": ["고객 본인의 결제 의사"],
                "recommendation": "NEEDS_CHECK",
            }
            for payment_id in payment_ids
        },
        "overallAnalysis": ["짧은 간격의 반복 결제입니다."],
        "additionalChecks": ["고객 본인 확인"],
        "recommendationSummary": "본인 확인 후 판정을 권고합니다.",
        "limitations": ["결제 이력은 조회하지 않았습니다."],
    }
    body.update(overrides)
    return json.dumps(body, ensure_ascii=False)


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
        self.write_calls = 0

    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        self.choose_messages.append(list(messages))
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
        return self._outputs.pop(0) if len(self._outputs) > 1 else self._outputs[0]


class BrokenLlm:
    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        raise ConnectionError("Ollama에 연결할 수 없다")

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        raise ConnectionError("Ollama에 연결할 수 없다")
