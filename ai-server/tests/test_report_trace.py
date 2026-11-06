import json

from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.trace import build_trace
from app.config import Settings
from tests.fakes import ScriptedLlm, context, narrative, toolbox

# 조사 과정 기록(investigationTrace, #294) — 화면 "조사 과정" 탭이 그대로 보여 준다

SETTINGS = Settings(_env_file=None)
FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())
ENGLISH_SENTENCE = "risk가 높은 결제라 본인 확인이 필요합니다."


def _repaired_run():
    llm = ScriptedLlm(
        turns=[],
        outputs=[
            narrative(
                summary=[
                    {"text": ENGLISH_SENTENCE, "facts": ["접속-1"]},
                    {"text": "카드 정보가 없습니다.", "facts": ["접속-1"]},
                ]
            ),
            narrative(),
        ],
    )
    return run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)


# 실제로 일어난 순서 · 횟수대로 남는다
# 기본 조회 5개는 시스템이 한 일 → 추가 조회 고르기(AI, 고를 게 없으면 한 번) → 문장 → 조립
# (#578, 첫 추가 도구 고객 이력 90일이 생겨 고르기 단계가 돈다 #581)
def test_trace_records_steps_in_actual_order() -> None:
    outcome = run_investigation(context(), MockLlmClient(), toolbox("sample_case.json"), SETTINGS)

    trace = outcome.report.investigationTrace
    assert [(s.kind, s.actor) for s in trace.steps] == [
        ("TOOL", "SYSTEM"),
        ("TOOL", "SYSTEM"),
        ("TOOL", "SYSTEM"),
        ("TOOL", "SYSTEM"),
        ("TOOL", "SYSTEM"),
        ("TOOL", "SYSTEM"),
        ("DECIDE", "AI"),
        ("WRITE", "AI"),
        ("ASSEMBLE", "SYSTEM"),
    ]
    assert [s.title for s in trace.steps if s.kind == "TOOL"] == [
        "결제 목록 조회",
        "FDS 근거 조회",
        "비슷한 과거 사건 조회",
        "고객 최근 결제 조회",
        "같은 카드 · 기기 · IP 결제 조회",
        "판정 기준표 조회",
    ]
    assert trace.steps[1].asked == "결제 9001 · 9002의 FDS 근거"
    assert all(s.durationMs is not None and s.durationMs >= 0 for s in trace.steps)
    assert trace.durationMs is not None


# 금액 · 시각 같은 원본 값과 형식 검사에 걸린 AI 문장은 기록에 들어가지 않는다
def test_trace_excludes_raw_tool_values_and_ai_sentences() -> None:
    outcome = _repaired_run()

    saved = outcome.report.investigationTrace.model_dump_json()
    for payment in FIXTURE["casePayments"]["payments"]:
        assert str(payment["amount"]) not in saved
        assert f"{payment['amount']:,}" not in saved
        assert payment["requestedAt"] not in saved
    assert ENGLISH_SENTENCE not in saved
    assert "risk가 높은" not in saved


# 다시 쓴 사실 문장은 몇 번째였는지와 걸린 검사 규칙이 남는다
def test_trace_repair_step_keeps_rule_reason() -> None:
    outcome = _repaired_run()

    writes = [s for s in outcome.report.investigationTrace.steps if s.kind == "WRITE"]
    assert [s.title for s in writes] == ["사실 문장 쓰기 1차", "사실 문장 쓰기 2차"]
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


# 단계별 실행(#322)으로 바꿔도 최종 보고서는 같고, 진행 알림은 끝난 단계가 순서대로 늘어난다
def test_run_investigation_reports_growing_steps_and_same_final_report() -> None:
    snapshots: list[list[str]] = []

    with_progress = run_investigation(
        context(),
        MockLlmClient(),
        toolbox("sample_case.json"),
        SETTINGS,
        lambda steps: snapshots.append([s.title for s in steps]),
    )
    without = run_investigation(context(), MockLlmClient(), toolbox("sample_case.json"), SETTINGS)

    final = [s.title for s in with_progress.report.investigationTrace.steps]
    assert len(snapshots) > 2
    assert all(len(a) < len(b) for a, b in zip(snapshots, snapshots[1:], strict=False))
    assert all(snap == final[: len(snap)] for snap in snapshots)
    assert snapshots[-1] == final
    # 시각 · 걸린 시간을 뺀 판단 내용은 진행 알림이 있든 없든 같다
    assert with_progress.status == without.status == "SUCCEEDED"
    assert with_progress.report.verdict == without.report.verdict
    assert with_progress.report.paymentAnalyses == without.report.paymentAnalyses
    assert with_progress.report.summary == without.report.summary
