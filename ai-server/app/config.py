from pydantic_settings import BaseSettings, SettingsConfigDict


# .env 또는 환경변수에서 읽는다 (접두사 AI_SERVER_)
class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="AI_SERVER_")

    # mock: Ollama 없이 정해진 순서로 조사 (CI · Ollama 없는 PC) / ollama: Qwen 사용
    llm_provider: str = "mock"
    llm_api_key: str = ""
    ollama_base_url: str = "http://localhost:11434"
    ollama_model: str = "qwen2.5:7b"

    # Spring 내부 API(Tool · 결과 콜백) 주소와 서비스 키 — 같은 키를 양방향에 쓴다
    # 키가 비어 있으면 Spring에서 오는 조사 접수를 모두 거절한다 (설정 누락으로 문이 열리지 않게)
    spring_base_url: str = "http://localhost:8081"
    internal_api_key: str = ""
    http_timeout_seconds: float = 10.0

    # 조사 동시 실행 수 · 보관할 결과 수
    # 결과는 메모리에만 둔다 — AI 서버가 재시작되면 Spring이 다시 시도한다
    max_concurrent_investigations: int = 2
    max_kept_results: int = 500

    # Agent 안전장치 — Tool 호출 최대 횟수, 필수 근거 재요청 횟수, 보고서 고쳐 쓰기 횟수
    agent_max_tool_calls: int = 6
    agent_max_nudges: int = 2
    agent_max_repairs: int = 2


settings = Settings()
