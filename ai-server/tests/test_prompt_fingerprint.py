import json

from app.agent import prompts
from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.tools import StaticToolBox
from app.config import Settings
from tests.fakes import FIXTURES, context

# 프롬프트 지문 (#581) — 측정 결과를 "어느 프롬프트로 쟀는지" 묶는 값

SETTINGS = Settings(_env_file=None)
SPEC = {
    "type": "function",
    "function": {
        "name": "look_more",
        "description": "더 본다",
        "parameters": {"type": "object", "properties": {}},
    },
}


def _fingerprint_of(name: str) -> str | None:
    data = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    case_id = data["casePayments"]["caseId"]
    outcome = run_investigation(
        context(case_id, 1), MockLlmClient(), StaticToolBox.from_sample(data), SETTINGS
    )
    assert outcome.report is not None
    return outcome.report.investigationTrace.promptFingerprint


# 사건이 달라도 지문은 같다 — 사건 사실이 섞이면 프롬프트를 바꿨는지 비교할 수 없다
def test_prompt_fingerprint_same_across_cases() -> None:
    accept = _fingerprint_of("measure_accept.json")
    case5 = _fingerprint_of("measure_case5_new.json")

    assert accept is not None
    assert accept == case5 == prompts.fingerprint([], SETTINGS.agent_max_extra_calls)


# 지시문이나 추가 도구 설명을 고치면 지문이 바뀐다 — 안 바뀌면 다른 프롬프트의 측정을 한데 묶는다
def test_prompt_fingerprint_changes_when_prompt_or_tool_spec_changes(monkeypatch) -> None:
    before = prompts.fingerprint([], 3)

    with_tool = prompts.fingerprint([SPEC], 3)
    monkeypatch.setattr(prompts, "WRITE_SYSTEM", prompts.WRITE_SYSTEM + "\n- 한 줄 더")
    edited = prompts.fingerprint([], 3)

    assert len({before, with_tool, edited}) == 3
