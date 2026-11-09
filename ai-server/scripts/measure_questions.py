import argparse
import json
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, TextIO

from app.agent.llm import LlmClient, build_llm_client
from app.agent.question import answer_question
from app.agent.trace import provider_and_model
from app.config import Settings
from app.schemas.question import QuestionRequest
from app.schemas.tools import LinkedPaymentsTool, PaymentHistoryTool

# 사건 질문 측정 (#750, ADR-0086)
# 질문마다 N번 돌려 답 종류 · 넓혀 보기 선택 · 1차 검사 통과를 센다
# OpenAI면 횟수 · 예상 비용을 보여 주고 y를 받아야 돈다. pytest · CI는 이 스크립트를 실행하지 않는다
# 예) uv run python -m scripts.measure_questions               (질문 12개 × 1번, .env 제공자)
#     uv run python -m scripts.measure_questions --runs 3

SAMPLE = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "measure_questions.json"
DEFAULT_RUNS = 1
MAX_RUNS = 10
# 대략 값 — gpt-4.1-mini 호출 1번 ≈ $0.001 (measure_summary와 같은 값)
USD_PER_CALL = 0.001
KRW_PER_USD = 1400


# 측정용 넓혀 보기 응답 — 연관 결제는 요청한 시간 범위로 돌려준다 (#751 뒤 Spring처럼)
class SampleBox:
    def __init__(self, data: dict[str, Any]) -> None:
        self._history = PaymentHistoryTool.model_validate(data["history90"])
        self._linked = {
            int(h): LinkedPaymentsTool.model_validate(v) for h, v in data["linked"].items()
        }

    def get_payment_history(self, lookback_days: int | None = None) -> PaymentHistoryTool:
        return self._history

    def find_linked_payments(self, nearby_hours: int | None = None) -> LinkedPaymentsTool:
        return self._linked[nearby_hours or 6]


@dataclass
class Run:
    question: str
    expect_kind: str
    kind: str | None
    status: str
    kind_ok: bool
    tools_ok: bool
    first_pass: bool
    seconds: float
    sentences: list[str]


def measure(
    question: dict[str, Any], data: dict[str, Any], llm: LlmClient, settings: Settings
) -> Run:
    request = QuestionRequest(
        questionId="measure",
        reportId="57",
        caseId="31",
        reportEvidenceVersion=2,
        currentEvidenceVersion=2,
        question=question["question"],
        reportBody=data["reportBody"],
        previous=question["previous"],
    )
    started = time.perf_counter()
    result = answer_question(request, llm, SampleBox(data), settings)
    writes = [s for s in result.trace if s.kind == "WRITE"]
    used = sorted({c.toolName for c in result.toolCalls if c.status == "OK"})
    return Run(
        question=question["question"],
        expect_kind=question["expectKind"],
        kind=result.kind,
        status=result.status,
        kind_ok=result.kind == question["expectKind"],
        tools_ok=used == sorted(question["expectTools"]),
        first_pass=result.status != "FAILED" and len(writes) == 1,
        seconds=round(time.perf_counter() - started, 1),
        sentences=[s.text for s in result.sentences],
    )


def _rate(values: list[bool]) -> str:
    return f"{sum(values)}/{len(values)}"


# 질문 하나에 AI 호출 2~4번 (넓혀 볼지 고르기 1~2번 + 답 쓰기 1~2번)
def _won(calls: int) -> int:
    return round(calls * USD_PER_CALL * KRW_PER_USD)


def main(
    argv: list[str] | None = None,
    *,
    settings: Settings | None = None,
    ask: Callable[[str], str] = input,
    make_llm: Callable[[Settings], LlmClient] = build_llm_client,
    out: TextIO = sys.stdout,
) -> int:
    parser = argparse.ArgumentParser(description="사건 질문 측정 — 질문마다 N번 돌려 지표를 센다")
    parser.add_argument(
        "--runs", type=int, default=DEFAULT_RUNS, help=f"질문마다 횟수 (1~{MAX_RUNS})"
    )
    parser.add_argument("--out", type=Path, help="결과 JSON 저장 경로 (예: q-before.local.json)")
    args = parser.parse_args(argv)
    if not 1 <= args.runs <= MAX_RUNS:
        print(f"--runs는 1~{MAX_RUNS}만 됩니다 (받은 값 {args.runs})", file=out)
        return 2
    settings = settings or Settings()
    data = json.loads(SAMPLE.read_text(encoding="utf-8"))
    total = len(data["questions"]) * args.runs
    provider, model = provider_and_model(settings)
    # 돈이 드는 제공자만 묻는다 — mock · Ollama는 바로 돈다
    if settings.llm_provider == "openai":
        prompt = (
            f"OpenAI({model})로 질문 {total}번 (AI 호출 {total * 2}~{total * 4}번), "
            f"예상 약 {_won(total * 2)}~{_won(total * 4)}원. 진행할까요? (y/N) "
        )
        if ask(prompt).strip().lower() != "y":
            print("취소했습니다 — OpenAI를 부르지 않았습니다", file=out)
            return 1
    llm = make_llm(settings)
    print(f"측정 시작 · {provider} ({model}) · 질문 {total}번", file=out)
    runs: list[Run] = []
    for question in data["questions"]:
        for _ in range(args.runs):
            run = measure(question, data, llm, settings)
            runs.append(run)
            mark = "O" if run.kind_ok and run.tools_ok else "X"
            print(
                f"  [{mark}] {run.question} → {run.kind} (기대 {run.expect_kind})"
                f" · 도구 {'맞음' if run.tools_ok else '다름'}"
                f" · {'1차 통과' if run.first_pass else '다시 쓰기/실패'} · {run.seconds}초",
                file=out,
            )
            for text in run.sentences:
                print(f"      {text}", file=out)
    print(
        f"[전체] 답 종류 맞음 {_rate([r.kind_ok for r in runs])}"
        f" · 넓혀 보기 맞음 {_rate([r.tools_ok for r in runs])}"
        f" · 1차 통과 {_rate([r.first_pass for r in runs])}"
        f" · 실패 {sum(r.status == 'FAILED' for r in runs)}"
        f" · 평균 {round(sum(r.seconds for r in runs) / len(runs), 1)}초",
        file=out,
    )
    if args.out:
        head = {"provider": provider, "model": model}
        body = {**head, "runs": [r.__dict__ for r in runs]}
        args.out.write_text(json.dumps(body, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"결과 저장: {args.out}", file=out)
    return 0


if __name__ == "__main__":
    # Windows 기본 출력(cp949)은 일부 글자를 못 써서 멈춘다 → 항상 UTF-8 (measure_summary와 같다)
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    sys.stderr.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    sys.exit(main())
