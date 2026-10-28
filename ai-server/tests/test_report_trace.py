import json

from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.trace import build_trace
from app.config import Settings
from tests.fakes import ScriptedLlm, call, context, narrative, toolbox

# 조사 과정 기록(investigationTrace, #294) — 화면 "조사 과정" 탭이 그대로 보여 준다

SETTINGS = Settings(_env_file=None)
FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())
ENGLISH_HEADLINE = "risk가 높은 결제라 본인 확인이 필요합니다."


def _repaired_run():
    llm = ScriptedLlm(
        turns=[],
        outputs=[
            narrative(["9001", "9002"], headline=ENGLISH_HEADLINE),
            narrative(["9001", "9002"]),
        ],
    )
    return run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)


# 실제로 일어난 순서 · 횟수대로 남는다 (mock: 결제 목록 → FDS 근거 → 조사 완료 → 문장 → 조립)
def test_trace_records_steps_in_actual_order() -> None:
    outcome = run_investigation(context(), MockLlmClient(), toolbox("sample_case.json"), SETTINGS)

    trace = outcome.report.investigationTrace
    assert [(s.kind, s.actor) for s in trace.steps] == [
        ("DECIDE", "AI"),
        ("TOOL", "AI"),
        ("DECIDE", "AI"),
        ("TOOL", "AI"),
        ("DECIDE", "AI"),
        ("WRITE", "AI"),
        ("ASSEMBLE", "SYSTEM"),
    ]
    assert [s.title for s in trace.steps if s.kind == "TOOL"] == ["결제 목록 조회", "FDS 근거 조회"]
    assert trace.steps[4].received == "조사 완료라고 답함"
    assert all(s.durationMs is not None and s.durationMs >= 0 for s in trace.steps)
    assert trace.durationMs is not None


# AI가 끝까지 안 본 근거를 코드가 조회하면 AI가 아니라 시스템이 한 일로 남는다
def test_trace_marks_system_filled_calls_as_system() -> None:
    llm = ScriptedLlm(turns=[[call("get_case_payments")]], outputs=[narrative(["9001", "9002"])])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    steps = outcome.report.investigationTrace.steps
    ai_tools = [s.title for s in steps if s.kind == "TOOL" and s.actor == "AI"]
    system_tools = [s for s in steps if s.kind == "TOOL" and s.actor == "SYSTEM"]
    assert ai_tools == ["결제 목록 조회"]
    assert [s.title for s in system_tools] == ["FDS 근거 조회 (시스템 보충)"]
    assert system_tools[0].asked == "결제 9001 · 9002의 FDS 근거"
    assert [s.actor for s in steps if s.kind == "NUDGE"] == ["SYSTEM", "SYSTEM"]


# 금액 · 시각 같은 원본 값과 형식 검사에 걸린 AI 문장은 기록에 들어가지 않는다
def test_trace_excludes_raw_tool_values_and_ai_sentences() -> None:
    outcome = _repaired_run()

    saved = outcome.report.investigationTrace.model_dump_json()
    for payment in FIXTURE["casePayments"]["payments"]:
        assert str(payment["amount"]) not in saved
        assert f"{payment['amount']:,}" not in saved
        assert payment["requestedAt"] not in saved
    assert ENGLISH_HEADLINE not in saved
    assert "risk가 높은" not in saved


# 다시 쓴 판단 문장은 몇 번째였는지와 걸린 검사 규칙이 남는다
def test_trace_repair_step_keeps_rule_reason() -> None:
    outcome = _repaired_run()

    writes = [s for s in outcome.report.investigationTrace.steps if s.kind == "WRITE"]
    assert [s.title for s in writes] == ["판단 문장 쓰기 1차", "판단 문장 쓰기 2차"]
    assert writes[0].received == "형식 검사 실패 → 다시 쓰기"
    assert writes[0].retryReason is not None and "영어 단어" in writes[0].retryReason
    assert writes[1].received == "형식 검사 통과"
    assert writes[1].retryReason is None


# 화면에 보일 모델 이름은 실제로 쓴 제공자 설정을 따른다
def test_trace_model_matches_provider() -> None:
    ollama = Settings(_env_file=None, llm_provider="ollama", ollama_model="qwen2.5:14b")

    assert (build_trace(ollama, [], 0).provider, build_trace(ollama, [], 0).model) == (
        "OLLAMA",
        "qwen2.5:14b",
    )
    outcome = run_investigation(context(), MockLlmClient(), toolbox("sample_case.json"), SETTINGS)
    assert (
        outcome.report.investigationTrace.provider,
        outcome.report.investigationTrace.model,
    ) == (
        "MOCK",
        "mock",
    )
