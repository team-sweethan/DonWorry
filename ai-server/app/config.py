from pydantic_settings import BaseSettings, SettingsConfigDict


# .env 또는 환경변수에서 읽는다 (접두사 AI_SERVER_)
class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="AI_SERVER_")

    # mock: Ollama 없이 정해진 순서로 조사 (CI · Ollama 없는 PC) / ollama: Qwen 사용
    llm_provider: str = "mock"
    llm_api_key: str = ""
    ollama_base_url: str = "http://localhost:11434"
    ollama_model: str = "qwen2.5:7b"

    # Spring 내부 Tool API — 서비스 키는 #253에서 Spring이 확인한다
    spring_base_url: str = "http://localhost:8081"
    internal_api_key: str = ""
    http_timeout_seconds: float = 10.0

    # Agent 안전장치 — Tool 호출 최대 횟수, 필수 근거 재요청 횟수, 보고서 고쳐 쓰기 횟수
    agent_max_tool_calls: int = 6
    agent_max_nudges: int = 2
    agent_max_repairs: int = 2


settings = Settings()
