import io
import json
from typing import Any

import pytest
from langchain_core.messages import AIMessage, BaseMessage

from app.agent.llm import MockLlmClient
from app.config import Settings
from scripts.measure_summary import FIXTURES, main, measure_run

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
