import json
import logging
from typing import Any, Protocol

from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from langchain_ollama import ChatOllama

from app.config import Settings

log = logging.getLogger(__name__)


# 판단 문장 원문 + Ollama 토큰 수 (#355) — 문자열 그대로 쓰면서 usage를 함께 넘긴다
# 클라이언트 하나를 동시 조사가 같이 쓰므로, 토큰 수를 클라이언트에 저장하지 않고 응답에 붙인다
class LlmText(str):
    usage: dict[str, int] | None = None

    @classmethod
    def of(cls, text: str, usage: dict[str, int] | None) -> "LlmText":
        result = cls(text)
        result.usage = usage
        return result


def _usage(message: AIMessage) -> dict[str, int] | None:
    usage = getattr(message, "usage_metadata", None)
    return dict(usage) if usage else None


# LLM 호출 경계 — 제공자(Ollama · mock · 이후 OpenAI)를 바꿔도 agent · service를 고치지 않는다
# (AGENTS.md 6절)
class LlmClient(Protocol):
    # 다음에 볼 도구를 고른다. 도구 호출이 없으면 조사 완료로 본다
    def choose_tools(
        self, messages: list[BaseMessage], tools: list[dict[str, Any]]
    ) -> AIMessage: ...

    # 주어진 JSON Schema 모양으로 판단 문장을 쓴다. 원문 문자열을 돌려주고 검사는 호출자가 한다
    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str: ...


# Ollama에 올린 Qwen — temperature 0으로 같은 근거에 같은 답을 내게 한다
# num_ctx를 고정해 PC마다 다른 Ollama 기본값에 기대지 않는다 (#355)
class OllamaLlmClient:
    def __init__(
        self, base_url: str, model: str, num_ctx: int = 8192, choose_max_tokens: int = 128
    ) -> None:
        self._base_url = base_url
        self._model = model
        self._num_ctx = num_ctx
        self._choose_max_tokens = choose_max_tokens

    def _chat(self, **kwargs: Any) -> ChatOllama:
        return ChatOllama(
            model=self._model,
            base_url=self._base_url,
            temperature=0,
            num_ctx=self._num_ctx,
            **kwargs,
        )

    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        # 도구 이름 · 인자만 받으면 되므로 긴 설명은 잘라 시간을 아낀다
        result = self._chat(num_predict=self._choose_max_tokens).bind_tools(tools).invoke(messages)
        reply = result if isinstance(result, AIMessage) else AIMessage(content=str(result.content))
        log.info("Ollama 도구 고르기 토큰 %s", _usage(reply))
        return reply

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        result = self._chat(format=schema).invoke(messages)
        usage = _usage(result) if isinstance(result, AIMessage) else None
        log.info("Ollama 판단 문장 쓰기 토큰 %s", usage)
        return LlmText.of(str(result.content), usage)


# Ollama 없이 도는 정해진 순서의 조사 — CI · Ollama 없는 팀원 PC용. 판단 문장은 고정이다
class MockLlmClient:
    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        called = {message.name for message in messages if isinstance(message, ToolMessage)}
        for name in (
            "get_case_payments",
            "get_fds_evidence",
            "find_similar_cases",
            "get_payment_history",
            "find_linked_payments",
        ):
            if name not in called:
                call = {"name": name, "args": {}, "id": f"mock-{name}", "type": "tool_call"}
                return AIMessage(content="", tool_calls=[call])
        return AIMessage(content="조사 완료")

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        payment_ids = schema["properties"]["payments"]["required"]
        payment = {
            "riskEvidence": ["FDS 규칙 적중 내역을 확인했습니다."],
            "normalEvidence": ["추가로 확인된 정상 정황은 없습니다."],
            "unknowns": ["고객 본인의 결제 의사"],
            "recommendation": "NEEDS_CHECK",
            "reason": "FDS 근거와 함께 조회한 정황을 비교해 추가 확인이 필요합니다.",
        }
        return json.dumps(
            {
                "headline": "FDS 규칙에 걸린 결제라 본인 확인 후 판정이 필요합니다.",
                "summary": [
                    f"조사 대상 결제 {len(payment_ids)}건의 FDS 근거를 확인했습니다.",
                    "직원은 결제별 위험 근거와 정상 정황을 비교한 뒤 판정해 주세요.",
                ],
                "payments": {payment_id: payment for payment_id in payment_ids},
                "overallAnalysis": [
                    "결제 간 공통 기기 · 계정 관계는 확인하지 않았습니다.",
                    "mock 조사라 근거 사이의 관계는 해석하지 않았습니다.",
                ],
                "additionalChecks": ["고객 본인 확인 필요 여부", "결제별 FDS 근거 확인"],
                "recommendationSummary": "추가 확인 후 결제별로 판정할 것을 권고합니다.",
            },
            ensure_ascii=False,
        )


def build_llm_client(settings: Settings) -> LlmClient:
    if settings.llm_provider == "ollama":
        return OllamaLlmClient(
            settings.ollama_base_url,
            settings.ollama_model,
            settings.ollama_num_ctx,
            settings.ollama_choose_max_tokens,
        )
    if settings.llm_provider == "mock":
        return MockLlmClient()
    raise ValueError(f"지원하지 않는 LLM 제공자: {settings.llm_provider}")
