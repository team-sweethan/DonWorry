from collections.abc import Callable
from typing import Any, Literal, Protocol

from pydantic import BaseModel

from app.agent.graph import ProgressListener, run_investigation
from app.agent.llm import LlmClient, build_llm_client
from app.agent.tools import SpringToolBox, ToolBox
from app.client.spring import SpringToolClient
from app.config import Settings
from app.schemas.case_context import CaseContext
from app.schemas.report import ReportBody


class ToolCall(BaseModel):
    toolName: str
    args: dict[str, Any]
    filledBySystem: bool


# 보고서 생성 결과 — 실패면 report 없이 error(code · reason)만 둔다. 자동 판정은 하지 않는다
class ReportResult(BaseModel):
    status: Literal["SUCCEEDED", "FAILED"]
    report: ReportBody | None
    error: dict[str, str] | None
    toolCalls: list[ToolCall]


# "보고서 만드는 자리" — #253의 접수 · 결과 콜백은 이 인터페이스만 부른다
# on_progress는 생성 중 끝난 단계를 받는다 (#322) — 없으면 알리지 않는다
class ReportGenerator(Protocol):
    def generate(
        self, context: CaseContext, on_progress: ProgressListener | None = None
    ) -> ReportResult: ...


class AgentReportGenerator:
    def __init__(
        self, llm: LlmClient, toolbox_for: Callable[[str], ToolBox], settings: Settings
    ) -> None:
        self._llm = llm
        self._toolbox_for = toolbox_for
        self._settings = settings

    def generate(
        self, context: CaseContext, on_progress: ProgressListener | None = None
    ) -> ReportResult:
        toolbox: ToolBox = self._toolbox_for(context.case_id)
        outcome = run_investigation(context, self._llm, toolbox, self._settings, on_progress)
        return ReportResult(
            status=outcome.status,
            report=outcome.report,
            error=outcome.error,
            toolCalls=[
                ToolCall(toolName=r.tool_name, args=r.args, filledBySystem=r.filled_by_system)
                for r in outcome.records
            ],
        )


def build_report_generator(settings: Settings) -> AgentReportGenerator:
    client = SpringToolClient(
        settings.spring_base_url, settings.internal_api_key, settings.http_timeout_seconds
    )
    return AgentReportGenerator(
        build_llm_client(settings), lambda case_id: SpringToolBox(client, case_id), settings
    )
