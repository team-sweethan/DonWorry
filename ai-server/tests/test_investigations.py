import json
from collections.abc import Callable
from concurrent.futures import Future
from typing import Any

import httpx
from fastapi.testclient import TestClient

from app.client.spring import API_KEY_HEADER, SpringToolClient
from app.controller.investigation_controller import get_api_key, get_service
from app.main import app
from app.schemas.case_context import CaseContext
from app.schemas.investigation import InvestigationRequest, InvestigationResult
from app.schemas.report import ReportBody
from app.service.investigation_service import InvestigationService
from app.service.report_generator import ReportResult

# Spring → AI 서버 조사 접수 · 상태 조회 · 결과 전송을 확인한다 (ADR-0009 결정 1 · 2)
# 생성기 · Spring은 가짜로 두고, 조사 실행 시점은 손으로 정한다

KEY = "test-key"
REPORT = json.loads(open("tests/fixtures/sample_report.json", encoding="utf-8").read())


def request(attempt_id: str = "attempt-1", report_id: str = "5") -> dict[str, Any]:
    return {
        "reportId": report_id,
        "attemptId": attempt_id,
        "caseId": "101",
        "evidenceVersion": 2,
        "reportVersion": 1,
        "merchantId": "1",
        "customerRef": "customer-1",
        "caseCreatedAt": "2026-10-27T12:00:30Z",
    }


# 제출된 조사를 바로 돌리지 않고 모아 두었다가 테스트가 원할 때 돌린다
class ManualExecutor:
    def __init__(self) -> None:
        self.tasks: list[Callable[[], None]] = []

    def submit(self, fn: Callable[..., None], *args: Any) -> Future[None]:
        self.tasks.append(lambda: fn(*args))
        return Future()

    def run_all(self) -> None:
        while self.tasks:
            self.tasks.pop(0)()


class FakeGenerator:
    def __init__(self, fail: Exception | None = None) -> None:
        self.contexts: list[CaseContext] = []
        self._fail = fail

    def generate(self, context: CaseContext) -> ReportResult:
        self.contexts.append(context)
        if self._fail:
            raise self._fail
        return ReportResult(
            status="SUCCEEDED", report=ReportBody.model_validate(REPORT), error=None, toolCalls=[]
        )


class FakeSpring:
    def __init__(self, fail: bool = False) -> None:
        self.sent: list[InvestigationResult] = []
        self._fail = fail

    def send_result(self, result: InvestigationResult) -> None:
        if self._fail:
            raise ConnectionError("Spring에 연결할 수 없다")
        self.sent.append(result)


def setup(
    generator: FakeGenerator | None = None, spring: FakeSpring | None = None, key: str = KEY
) -> tuple[TestClient, InvestigationService, ManualExecutor, FakeGenerator, FakeSpring]:
    generator = generator or FakeGenerator()
    spring = spring or FakeSpring()
    executor = ManualExecutor()
    service = InvestigationService(generator, spring, executor)
    app.dependency_overrides[get_service] = lambda: service
    app.dependency_overrides[get_api_key] = lambda: key
    return TestClient(app), service, executor, generator, spring


def teardown_function() -> None:
    app.dependency_overrides.clear()


HEADERS = {API_KEY_HEADER: KEY}


# 접수하면 바로 202 · RUNNING, 조사가 끝나면 결과를 Spring에 보내고 상태 조회로도 볼 수 있다
def test_accept_runs_in_background_and_sends_result() -> None:
    client, _, executor, generator, spring = setup()

    res = client.post("/investigations", json=request(), headers=HEADERS)

    assert res.status_code == 202
    assert res.json()["status"] == "RUNNING"
    assert generator.contexts == []

    executor.run_all()

    assert generator.contexts[0].case_id == "101"
    assert generator.contexts[0].evidence_version == 2
    assert [(r.reportId, r.attemptId, r.status) for r in spring.sent] == [
        ("5", "attempt-1", "SUCCEEDED")
    ]
    status = client.get("/investigations/5", headers=HEADERS).json()
    assert status["status"] == "SUCCEEDED"
    assert status["reportBody"]["overview"]["caseId"] == "101"


# 같은 보고서 · 같은 시도가 다시 와도 한 번만 조사한다
def test_same_attempt_is_accepted_once() -> None:
    client, _, executor, generator, _ = setup()

    client.post("/investigations", json=request(), headers=HEADERS)
    res = client.post("/investigations", json=request(), headers=HEADERS)
    executor.run_all()

    assert res.status_code == 202
    assert len(generator.contexts) == 1


# 새 시도가 오면 이전 시도의 늦은 결과는 버리고 보내지 않는다
def test_late_result_of_previous_attempt_is_dropped() -> None:
    client, _, executor, _, spring = setup()

    client.post("/investigations", json=request("attempt-1"), headers=HEADERS)
    client.post("/investigations", json=request("attempt-2"), headers=HEADERS)
    first, second = executor.tasks
    first()

    assert spring.sent == []
    assert client.get("/investigations/5", headers=HEADERS).json()["status"] == "RUNNING"

    second()

    assert [r.attemptId for r in spring.sent] == ["attempt-2"]


# 생성기 장애는 FAILED로 끝내고 그대로 알린다 — 자동 판정 없음
def test_generator_error_ends_failed() -> None:
    client, _, executor, _, spring = setup(
        generator=FakeGenerator(fail=RuntimeError("Ollama 끊김"))
    )

    client.post("/investigations", json=request(), headers=HEADERS)
    executor.run_all()

    assert spring.sent[0].status == "FAILED"
    assert spring.sent[0].error["code"] == "AGENT_ERROR"
    assert spring.sent[0].reportBody is None


# 결과 전송이 실패해도 결과는 남아 Spring이 상태 조회로 가져갈 수 있다
def test_result_is_kept_when_callback_fails() -> None:
    client, _, executor, _, _ = setup(spring=FakeSpring(fail=True))

    client.post("/investigations", json=request(), headers=HEADERS)
    executor.run_all()

    assert client.get("/investigations/5", headers=HEADERS).json()["status"] == "SUCCEEDED"


def test_unknown_report_returns_not_found() -> None:
    client, *_ = setup()

    assert client.get("/investigations/999", headers=HEADERS).status_code == 404


# 서비스 키가 없거나 틀리면 거절한다. 서버 키가 비어 있으면 모두 거절한다
def test_service_key_is_required() -> None:
    client, *_ = setup()

    assert client.post("/investigations", json=request()).status_code == 401
    wrong = {API_KEY_HEADER: "wrong"}
    assert client.post("/investigations", json=request(), headers=wrong).status_code == 401
    assert client.get("/investigations/5", headers=wrong).status_code == 401


def test_empty_server_key_rejects_all() -> None:
    client, *_ = setup(key="")

    res = client.post("/investigations", json=request(), headers={API_KEY_HEADER: ""})

    assert res.status_code == 401


def test_health_needs_no_key() -> None:
    client, *_ = setup()

    assert client.get("/health").status_code == 200


# 오래된 끝난 결과는 정해진 수만 남기고 비운다 — 진행 중인 조사는 남긴다
def test_old_finished_results_are_evicted() -> None:
    executor = ManualExecutor()
    service = InvestigationService(FakeGenerator(), FakeSpring(), executor, max_kept_results=2)

    for report_id in ("1", "2", "3"):
        service.accept(InvestigationRequest.model_validate(request(report_id=report_id)))
    executor.run_all()

    assert service.status("1") is None
    assert service.status("3") is not None


# Spring 결과 수신 경로로 결과 · 서비스 키를 보낸다
def test_spring_client_sends_result() -> None:
    seen: list[httpx.Request] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        return httpx.Response(200)

    client = SpringToolClient("http://spring", KEY, transport=httpx.MockTransport(handler))
    client.send_result(
        InvestigationResult(
            reportId="5", attemptId="attempt-1", status="FAILED", error={"code": "X"}
        )
    )

    assert seen[0].method == "POST"
    assert seen[0].url.path == "/internal/ai/reports/5/result"
    assert seen[0].headers[API_KEY_HEADER] == KEY
    assert json.loads(seen[0].content)["attemptId"] == "attempt-1"
