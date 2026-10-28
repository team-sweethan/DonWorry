import logging
import threading
from collections import OrderedDict
from concurrent.futures import Executor
from typing import Protocol

from app.schemas.investigation import InvestigationRequest, InvestigationResult
from app.service.report_generator import ReportGenerator

log = logging.getLogger(__name__)


class ResultNotifier(Protocol):
    def send_result(self, result: InvestigationResult) -> None: ...


# 조사 접수 · 실행 · 결과 보관 · 결과 전송 (ADR-0009 결정 1 · 2)
# - 접수는 바로 끝내고(202) 조사는 뒤에서 돈다
# - 보고서마다 가장 최근 시도(attemptId)의 상태만 보관한다. 이전 시도의 늦은 결과는 버린다
# - 결과는 메모리에만 둔다. AI 서버가 재시작되면 Spring이 "결과 없음"으로 보고 다시 시도한다
class InvestigationService:
    def __init__(
        self,
        generator: ReportGenerator,
        notifier: ResultNotifier,
        executor: Executor,
        max_kept_results: int = 500,
    ) -> None:
        self._generator = generator
        self._notifier = notifier
        self._executor = executor
        self._max_kept = max_kept_results
        self._results: OrderedDict[str, InvestigationResult] = OrderedDict()
        self._lock = threading.Lock()

    # 같은 보고서 · 같은 시도가 다시 오면 다시 돌리지 않는다 (Spring 재전송에 멱등)
    def accept(self, request: InvestigationRequest) -> InvestigationResult:
        with self._lock:
            current = self._results.get(request.reportId)
            if current is not None and current.attemptId == request.attemptId:
                return current
            running = InvestigationResult(
                reportId=request.reportId, attemptId=request.attemptId, status="RUNNING"
            )
            self._store(running)
        self._executor.submit(self._run, request)
        return running

    def status(self, report_id: str) -> InvestigationResult | None:
        with self._lock:
            return self._results.get(report_id)

    def _run(self, request: InvestigationRequest) -> None:
        try:
            generated = self._generator.generate(request.to_context())
            result = InvestigationResult(
                reportId=request.reportId,
                attemptId=request.attemptId,
                status=generated.status,
                reportBody=generated.report,
                error=generated.error,
            )
        except Exception as e:  # 생성기 장애 — 자동 판정 없이 실패로 끝낸다
            log.exception("조사 실패 reportId=%s", request.reportId)
            result = InvestigationResult(
                reportId=request.reportId,
                attemptId=request.attemptId,
                status="FAILED",
                error={"code": "AGENT_ERROR", "reason": str(e)},
            )
        with self._lock:
            current = self._results.get(request.reportId)
            if current is None or current.attemptId != request.attemptId:
                log.info("이전 시도 결과를 버림 reportId=%s", request.reportId)
                return
            self._store(result)
        try:
            self._notifier.send_result(result)
        except Exception:  # 전송 실패 — Spring이 상태 조회(GET)로 결과를 가져간다
            log.warning("결과 전송 실패 reportId=%s", request.reportId, exc_info=True)

    # 오래된 끝난 결과부터 비운다 — 진행 중인 조사는 남긴다 (lock 안에서 부른다)
    def _store(self, result: InvestigationResult) -> None:
        self._results[result.reportId] = result
        self._results.move_to_end(result.reportId)
        finished = [k for k, v in self._results.items() if v.status != "RUNNING"]
        for report_id in finished[: max(0, len(self._results) - self._max_kept)]:
            del self._results[report_id]
