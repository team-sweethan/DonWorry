import pytest
from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, HumanMessage

from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient, OpenAiLlmClient
from app.agent.narrative import narrative_schema
from app.config import Settings, settings
from app.controller.investigation_controller import get_service
from app.main import app
from app.schemas.report import ReportBody
from tests.fakes import catalog, context, narrative, toolbox
from tests.test_narrative_quality import CASES, FDS

# OpenAI 제공자 (#453) — 배포 VM은 사양 한계로 Ollama를 설치할 수 없어 OpenAI로 보고서를 쓴다
# 실제 OpenAI 호출은 넣지 않는다 (키 · 비용). 키 있는 환경에서 scripts/run_report.py로 따로 확인


# OpenAI로 쓴 보고서도 응답 검증을 통과하고, 조사 과정 탭에 제공자 · 모델이 남는다
def test_investigation_trace_openaiProvider_acceptedInResponseSchema() -> None:
    openai_settings = Settings(_env_file=None, llm_provider="openai", llm_api_key="test-key")

    outcome = run_investigation(
        context(), MockLlmClient(), toolbox("sample_case.json"), openai_settings
    )

    assert outcome.report is not None
    sent = ReportBody.model_validate(outcome.report.model_dump(mode="json"))
    assert sent.investigationTrace is not None
    assert (sent.investigationTrace.provider, sent.investigationTrace.model) == (
        "OPENAI",
        "gpt-4.1-mini",
    )


# openai인데 키가 비어 있으면 서버가 뜨지 않는다 — 시연 중 보고서마다 실패하지 않게
def test_build_llm_client_openai_missingKey_failsAtStartup(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "llm_provider", "openai")
    monkeypatch.setattr(settings, "llm_api_key", "")
    get_service.cache_clear()
    try:
        with pytest.raises(ValueError, match="AI_SERVER_LLM_API_KEY"):
            with TestClient(app):  # lifespan(서버 시작 단계)까지 실행
                pass
    finally:
        get_service.cache_clear()


# 판단 문장 원문과 토큰 수가 그대로 넘어간다 — 품질 검사 · 조사 과정 탭 토큰 표시가 쓴다
def test_openai_write_json_returnsRawTextWithUsage() -> None:
    raw = narrative(["9001"])
    usage = {"input_tokens": 1200, "output_tokens": 300, "total_tokens": 1500}
    chat = GenericFakeChatModel(messages=iter([AIMessage(content=raw, usage_metadata=usage)]))

    result = OpenAiLlmClient(chat).write_json(
        [HumanMessage(content="근거")], narrative_schema(catalog(CASES, FDS))
    )

    assert result == raw
    assert getattr(result, "usage", None) == usage
