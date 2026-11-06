import io
import json
from typing import Any

import pytest
from langchain_core.messages import AIMessage, BaseMessage

from app.agent.llm import MockLlmClient
from app.config import Settings
from scripts.measure_summary import FIXTURES, main, measure_run, summarize

# 요약 측정 스크립트 (#581) — 진짜 AI는 부르지 않는다 (mock · 가짜 AI만)

SETTINGS = Settings(_env_file=None)
OPENAI = Settings(_env_file=None, llm_provider="openai", llm_api_key="test-key")


def _never_build(_settings: Settings) -> Any:
    raise AssertionError("AI 클라이언트를 만들면 안 된다")


def _never_ask(_question: str) -> str:
    raise AssertionError("묻기 전에 멈춰야 한다")


def _sample(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


# y를 입력하지 않으면 OpenAI 클라이언트를 만들지 않고 끝난다 — 과금 사고 방지
@pytest.mark.parametrize("answer", ["", "n", "N", "yes please"])
def test_measure_not_yes_does_not_call_llm(answer: str) -> None:
    asked: list[str] = []

    def ask(question: str) -> str:
        asked.append(question)
        return answer

    code = main([], settings=OPENAI, ask=ask, make_llm=_never_build, out=io.StringIO())

    assert code == 1
    assert len(asked) == 1 and "(y/N)" in asked[0] and "원" in asked[0]


# 20번이 넘으면 묻지도 않고 거절한다 — 실수로 큰 수를 넣어도 과금이 커지지 않게
@pytest.mark.parametrize("runs", ["21", "200", "0"])
def test_measure_runs_over_20_rejected(runs: str) -> None:
    code = main(
        ["--runs", runs], settings=OPENAI, ask=_never_ask, make_llm=_never_build, out=io.StringIO()
    )

    assert code == 2


# 첫 답에서 눈여겨볼 사실을 뺀 AI — 다시 쓰기에서 mock 답(모두 포함)을 낸다
class _FirstMissesNotable:
    def __init__(self) -> None:
        self._mock = MockLlmClient()
        self._writes = 0

    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        return AIMessage(content="조사 완료")

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        self._writes += 1
        raw = self._mock.write_json(messages, schema)
        if self._writes > 1:
            return raw
        body = json.loads(raw)
        body["summary"][0]["facts"] = [
            f for f in body["summary"][0]["facts"] if f.startswith("접속")
        ]
        return json.dumps(body, ensure_ascii=False)


# 눈여겨볼 사실은 1차 답으로 센다 — 최종 답으로 세면 다시 쓰기 덕에 늘 포함이라 지표가 거짓이 된다
def test_measure_metrics_notable_counted_on_first_attempt() -> None:
    data = _sample("measure_case5_new.json")

    missed = measure_run("case5_new", data, _FirstMissesNotable(), SETTINGS)
    cited = measure_run("case5_new", data, MockLlmClient(), SETTINGS)

    assert (missed.status, missed.first_pass, missed.rewrites) == ("SUCCEEDED", False, 1)
    assert missed.notable_in_first is False
    assert (cited.first_pass, cited.rewrites, cited.notable_in_first) == (True, 0, True)


# 늘 눈여겨볼 사실을 빼먹는 AI — 다시 써도 같아서 조사가 실패한다
class _AlwaysMissesNotable(_FirstMissesNotable):
    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        self._writes = 0
        return super().write_json(messages, schema)


# 눈여겨볼 사실도 빼먹고 없는 숫자도 쓰는 AI — 안전망(#591)으로도 못 살려 조사가 실패한다
class _AlwaysMissesNotableWithWrongNumber(_AlwaysMissesNotable):
    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        body = json.loads(super().write_json(messages, schema))
        body["summary"][0]["text"] += " 같은 날 결제가 777건 더 있습니다."
        return json.dumps(body, ensure_ascii=False)


# 조사가 실패해도 답마다 불합격 사유가 남는다 — 사유가 없으면 무엇을 고칠지 알 수 없다
def test_measure_failed_run_records_reject_reasons() -> None:
    run = measure_run(
        "case5_new",
        _sample("measure_case5_new.json"),
        _AlwaysMissesNotableWithWrongNumber(),
        SETTINGS,
    )

    assert (run.status, run.error_code) == ("FAILED", "NARRATIVE_INVALID")
    assert len(run.reject_reasons) == 1 + SETTINGS.agent_max_repairs
    for reasons in run.reject_reasons:
        assert any("눈여겨볼 사실" in reason for reason in reasons)
        assert not any("mock 조사라" in reason for reason in reasons)


# 안전망으로 성공한 조사는 덧붙인 사실 수가 기록된다 — 안 세면 AI 품질과 안전망 효과가 섞인다
def test_measure_safety_net_run_records_appended_count() -> None:
    run = measure_run(
        "case5_new", _sample("measure_case5_new.json"), _AlwaysMissesNotable(), SETTINGS
    )

    assert run.status == "SUCCEEDED"
    assert run.appended == len(run.notable_facts) > 0
    assert "안전망 덧붙임 1/1" in summarize([run])


# 두 번째 조사 도중 Ctrl+C를 누른 것처럼 멈춘다
class _StopOnSecondRun:
    def __init__(self) -> None:
        self._mock = MockLlmClient()
        self._writes = 0

    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        return AIMessage(content="조사 완료")

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        self._writes += 1
        if self._writes > 1:
            raise KeyboardInterrupt
        return self._mock.write_json(messages, schema)


# 조사가 끝날 때마다 저장한다 — 도중에 멈추면 그때까지의 측정 결과가 다 사라지던 문제
def test_measure_out_saved_after_each_run(tmp_path) -> None:
    out = tmp_path / "before.local.json"
    argv = ["--sample", "measure_case5_new.json", "--runs", "2", "--out", str(out)]

    with pytest.raises(KeyboardInterrupt):
        main(
            argv,
            settings=SETTINGS,
            ask=_never_ask,
            make_llm=lambda _settings: _StopOnSecondRun(),
            out=io.StringIO(),
        )

    saved = json.loads(out.read_text(encoding="utf-8"))
    assert saved["finished"] is False
    assert [run["status"] for run in saved["runs"]] == ["SUCCEEDED"]
