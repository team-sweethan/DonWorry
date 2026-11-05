import json
import logging
from typing import Any, Protocol

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from langchain_ollama import ChatOllama
from langchain_openai import ChatOpenAI
from pydantic import SecretStr

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


# LLM 호출 경계 — 제공자(Ollama · OpenAI · mock)를 바꿔도 agent · service를 고치지 않는다
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
        log.info("Ollama 사실 문장 쓰기 토큰 %s", usage)
        return LlmText.of(str(result.content), usage)


# OpenAI 구조화 출력(strict)은 모든 객체에 additionalProperties: false가 있어야 받는다 (#453)
# 판단 문장 스키마는 Ollama와 같이 쓰므로 원본은 두고 복사본에만 붙인다
def _strict_schema(schema: Any) -> Any:
    if isinstance(schema, dict):
        copied = {key: _strict_schema(value) for key, value in schema.items()}
        if copied.get("type") == "object":
            copied["additionalProperties"] = False
        return copied
    if isinstance(schema, list):
        return [_strict_schema(item) for item in schema]
    return schema


# OpenAI — 배포 VM은 사양 한계로 Ollama를 설치할 수 없어 쓰는 제공자 (#453, ADR-0009)
# 조사 흐름 · 문장 검사 · 다시 쓰기는 그대로, 도구 고르기와 사실 문장 쓰기만 맡긴다
class OpenAiLlmClient:
    def __init__(self, chat: BaseChatModel) -> None:
        self._chat = chat

    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        result = self._chat.bind_tools(tools).invoke(messages)
        reply = result if isinstance(result, AIMessage) else AIMessage(content=str(result.content))
        log.info("OpenAI 도구 고르기 토큰 %s", _usage(reply))
        return reply

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        response_format = {
            "type": "json_schema",
            "json_schema": {
                "name": "investigation_narrative",
                "schema": _strict_schema(schema),
                "strict": True,
            },
        }
        result = self._chat.bind(response_format=response_format).invoke(messages)
        usage = _usage(result) if isinstance(result, AIMessage) else None
        log.info("OpenAI 사실 문장 쓰기 토큰 %s", usage)
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

    # 받은 사실 번호를 모두 단 고정 문장 — 숫자를 쓰지 않아 숫자 검사에 걸리지 않는다 (#548, #559)
    # 모두 다는 이유: 눈여겨볼 사실이 빠지면 형식 검사가 돌려보낸다 (#568)
    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        ids = schema["properties"]["summary"]["items"]["properties"]["facts"]["items"]["enum"]
        return json.dumps(
            {
                "summary": [
                    {
                        "text": "mock 조사라 조회한 사실을 고정 문장으로만 전달합니다.",
                        "facts": list(ids),
                    }
                ],
                "additionalChecks": ["고객 본인 확인 필요 여부", "결제별 FDS 근거 확인"],
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
    if settings.llm_provider == "openai":
        # 키 없이 켜지면 시연 중 보고서마다 실패한다 — 서버 시작 단계에서 멈추게 한다 (#453)
        if not settings.llm_api_key:
            raise ValueError("AI_SERVER_LLM_API_KEY가 비어 있어 OpenAI 제공자를 쓸 수 없다")
        # temperature 0으로 같은 근거에 같은 답을 내게 한다 (Ollama와 같은 기준)
        chat = ChatOpenAI(
            model=settings.openai_model,
            api_key=SecretStr(settings.llm_api_key),
            temperature=0,
            timeout=settings.openai_timeout_seconds,
            max_retries=2,
        )
        return OpenAiLlmClient(chat)
    if settings.llm_provider == "mock":
        return MockLlmClient()
    raise ValueError(f"지원하지 않는 LLM 제공자: {settings.llm_provider}")
