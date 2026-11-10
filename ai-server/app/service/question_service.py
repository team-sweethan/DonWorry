from collections.abc import Callable

from app.agent.llm import LlmClient, build_llm_client
from app.agent.question import QuestionToolBox, answer_question
from app.agent.tools import SpringToolBox
from app.client.spring import SpringToolClient
from app.config import Settings
from app.schemas.question import QuestionRequest, QuestionResult


# 사건 질문 · 답변 (ADR-0086) — 동기. 저장 · 권한 · 횟수 상한은 Spring이 한다 (#752)
class QuestionService:
    def __init__(
        self,
        llm: LlmClient,
        toolbox_for: Callable[[str], QuestionToolBox],
        settings: Settings,
    ) -> None:
        self._llm = llm
        self._toolbox_for = toolbox_for
        self._settings = settings

    def answer(self, request: QuestionRequest) -> QuestionResult:
        return answer_question(
            request, self._llm, self._toolbox_for(request.caseId), self._settings
        )


def build_question_service(settings: Settings) -> QuestionService:
    client = SpringToolClient(
        settings.spring_base_url, settings.internal_api_key, settings.http_timeout_seconds
    )
    return QuestionService(
        build_llm_client(settings), lambda case_id: SpringToolBox(client, case_id), settings
    )
