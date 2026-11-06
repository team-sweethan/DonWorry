import argparse
import json
import re
import sys
import time
from collections import Counter
from collections.abc import Callable
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, TextIO

from langchain_core.messages import AIMessage, BaseMessage

from app.agent import prompts, trace
from app.agent.assemble import FDS_RESULT_KO, PAYMENT_STATUS_KO, analyzable
from app.agent.facts import NOTABLE, Fact, fact_catalog
from app.agent.graph import run_investigation
from app.agent.llm import LlmClient, build_llm_client
from app.agent.narrative import _key, validate_narrative
from app.agent.tools import EXTRA_TOOLS, StaticToolBox
from app.agent.trace import provider_and_model
from app.config import Settings
from app.schemas.case_context import CaseContext
from app.schemas.report import ReportBody
from app.schemas.tools import CasePaymentsTool

# 요약 측정 (#581) — 샘플마다 조사를 N번 돌려 요약 지표를 센다. 프롬프트 · 도구 변경 전후 비교용
# OpenAI면 횟수 · 예상 비용을 보여 주고 y를 받아야 돈다. pytest · CI는 이 스크립트를 실행하지 않는다
# 예) uv run python -m scripts.measure_summary                       (샘플 5개 × 3번, .env 제공자)
#     uv run python -m scripts.measure_summary --runs 5 --out before.local.json

FIXTURES = Path(__file__).resolve().parents[1] / "tests" / "fixtures"
SAMPLES = (
    "measure_accept.json",
    "measure_mixed.json",
    "measure_reject.json",
    "measure_case5_new.json",
    "measure_case5_regular.json",
)
DEFAULT_RUNS = 3
# 실수로 큰 수를 넣어 과금이 커지지 않게 한다 (11/06 사용자 결정)
MAX_RUNS = 20
# 대략 값 — gpt-4.1-mini 요약 1회 ≈ $0.001 (PLAN_AI_REPORT T4). 도구 고르기도 같은 값으로 센다
USD_PER_CALL = 0.001
KRW_PER_USD = 1400

# 표에 이미 있는 세부 건수 — "허용 8건", "4건 검토"처럼 판정 · 상태 옆의 건수 (#568에서 금지)
_STATUS_WORDS = "|".join(
    sorted({*FDS_RESULT_KO.values(), *PAYMENT_STATUS_KO.values()}, key=len, reverse=True)
)
_DETAIL_COUNT = re.compile(
    rf"(?:{_STATUS_WORDS})\s*\d[\d,]*\s*건|\d[\d,]*\s*건\s*(?:은|는|이|가|을|를)?\s*(?:{_STATUS_WORDS})"
)
_NO_EARLIER_FACT = "그 전 결제 없음"
_NO_EARLIER_TEXT = re.compile(r"(?:앞선|그 전|이전)\s*결제[^.]*없")
# 예시 문장 베끼기를 어느 문장에서 했는지 — 우리가 쓴 예시 문장만 남긴다 (AI 문장은 저장 안 함)
_EXAMPLE_LABELS = {
    _key(text): f"{part} 예시: {text}"
    for example in prompts.WRITE_EXAMPLES
    for part, texts in (
        ("요약", [s["text"] for s in example["출력"]["summary"]]),
        ("확인할 것", example["출력"]["additionalChecks"]),
    )
    for text in texts
}
# 불합격 사유를 규칙별로 묶어 세려고 사실 번호 목록([...])은 지운다
_ID_LIST = re.compile(r"\[[^\]]*\]")


# AI 호출을 그대로 넘기며 기록한다 — 1차 답 · AI가 고른 추가 도구를 보려고 (보고서엔 1차 답이 없다)
class RecordingLlm:
    def __init__(self, inner: LlmClient) -> None:
        self._inner = inner
        self.writes: list[str] = []
        self.chosen: list[str] = []

    def choose_tools(self, messages: list[BaseMessage], tools: list[dict[str, Any]]) -> AIMessage:
        reply = self._inner.choose_tools(messages, tools)
        if isinstance(reply, AIMessage):
            self.chosen += [call["name"] for call in reply.tool_calls]
        return reply

    def write_json(self, messages: list[BaseMessage], schema: dict[str, Any]) -> str:
        raw = self._inner.write_json(messages, schema)
        self.writes.append(str(raw))
        return raw


# 조사 한 번의 지표 — None은 "그 지표를 볼 수 없는 경우" (예: 눈여겨볼 사실이 없는 샘플, 조사 실패)
@dataclass(frozen=True)
class RunResult:
    sample: str
    status: str
    first_pass: bool
    rewrites: int
    notable_in_first: bool | None
    detail_counts: int | None
    no_earlier_included: bool | None
    extra_tools: list[str]
    fingerprint: str | None
    # 불합격한 답마다 걸린 검사 규칙 (AI 문장은 뺀다) — 무엇을 고칠지 보려고
    reject_reasons: list[list[str]]
    # 답마다 그대로 베낀 예시 문장 (불합격 · 합격 답 모두)
    copied_examples: list[list[str]]
    error_code: str | None
    seconds: float


def _cited(raw: str) -> set[str]:
    try:
        summary = json.loads(raw).get("summary") or []
        return {fid for sentence in summary for fid in sentence.get("facts") or []}
    except (ValueError, AttributeError, TypeError):
        return set()


# 답마다 형식 검사를 다시 돌려 불합격 사유를 얻는다 — 조사가 실패하면 보고서가 없어 사유도 사라진다
def _reject_reasons(writes: list[str], facts: list[Fact], data: dict[str, Any]) -> list[list[str]]:
    cases = CasePaymentsTool.model_validate(data["casePayments"])
    payment_ids = [p.paymentId for p in cases.payments if analyzable(p)]
    reasons = []
    for raw in writes:
        narrative, errors = validate_narrative(raw, facts, payment_ids)
        if narrative is None:
            reasons.append([trace.retry_reason([error]) for error in errors])
    return reasons


def _copied_examples(writes: list[str]) -> list[list[str]]:
    copied = []
    for raw in writes:
        try:
            body = json.loads(raw)
            texts = [s.get("text") or "" for s in body.get("summary") or []]
            texts += [t for t in body.get("additionalChecks") or [] if isinstance(t, str)]
        except (ValueError, AttributeError, TypeError):
            texts = []
        copied.append([_EXAMPLE_LABELS[_key(t)] for t in texts if _key(t) in _EXAMPLE_LABELS])
    return copied


def measure_run(sample: str, data: dict[str, Any], llm: LlmClient, settings: Settings) -> RunResult:
    cases = data["casePayments"]
    context = CaseContext(
        case_id=cases["caseId"],
        evidence_version=cases["evidenceVersion"],
        report_version=1,
        merchant_id="1",
        customer_ref="measure",
        case_created_at=cases["retrievedAt"],
    )
    recorder = RecordingLlm(llm)
    started = time.monotonic()
    outcome = run_investigation(context, recorder, StaticToolBox.from_sample(data), settings)
    seconds = round(time.monotonic() - started, 1)
    facts = fact_catalog(context, outcome.records)
    notable = {f.id for f in facts if f.rank == NOTABLE}
    # 최종 답은 C1 검사(눈여겨볼 사실 빠지면 다시 쓰기) 때문에 늘 포함이라 1차 답으로 센다
    first = _cited(recorder.writes[0]) if recorder.writes else set()
    report: ReportBody | None = outcome.report
    text = " ".join(report.summary) if report else ""
    has_no_earlier = any(_NO_EARLIER_FACT in f.text for f in facts)
    return RunResult(
        sample=sample,
        status=outcome.status,
        first_pass=outcome.status == "SUCCEEDED" and len(recorder.writes) == 1,
        rewrites=max(len(recorder.writes) - 1, 0),
        notable_in_first=(notable <= first) if notable else None,
        detail_counts=len(_DETAIL_COUNT.findall(text)) if report else None,
        no_earlier_included=bool(_NO_EARLIER_TEXT.search(text))
        if report and has_no_earlier
        else None,
        extra_tools=recorder.chosen,
        fingerprint=report.investigationTrace.promptFingerprint if report else None,
        reject_reasons=_reject_reasons(recorder.writes, facts, data),
        copied_examples=_copied_examples(recorder.writes),
        error_code=outcome.error["code"] if outcome.error else None,
        seconds=seconds,
    )


# 조사 한 번에 드는 AI 호출 수 (최소, 최대) — 다시 쓰기 · 추가 조사를 다 쓰는 경우가 최대
def calls_per_run(settings: Settings) -> tuple[int, int]:
    low, high = 1, 1 + settings.agent_max_repairs
    if EXTRA_TOOLS:
        low, high = low + 1, high + settings.agent_max_extra_calls
    return low, high


def _won(calls: int) -> int:
    return round(calls * USD_PER_CALL * KRW_PER_USD)


def _rate(values: list[bool | None]) -> str:
    seen = [v for v in values if v is not None]
    return f"{sum(seen)}/{len(seen)}" if seen else "해당 없음"


def summarize(results: list[RunResult]) -> str:
    detail = [r.detail_counts for r in results if r.detail_counts is not None]
    rewrites = sum(r.rewrites for r in results) / len(results)
    return " · ".join(
        [
            f"{len(results)}번",
            f"성공 {sum(r.status == 'SUCCEEDED' for r in results)}",
            f"1차 통과 {_rate([r.first_pass for r in results])}",
            f"다시 쓰기 평균 {rewrites:.1f}",
            f"눈여겨볼 사실(1차) {_rate([r.notable_in_first for r in results])}",
            f"세부 건수 나온 요약 {sum(n > 0 for n in detail)}/{len(detail)}",
            f"'앞선 결제 없음' 포함 {_rate([r.no_earlier_included for r in results])}",
            f"추가 조회 고름 {sum(bool(r.extra_tools) for r in results)}/{len(results)}",
            f"평균 {sum(r.seconds for r in results) / len(results):.0f}초",
        ]
    )


# 많이 걸린 불합격 사유 — 같은 규칙은 사실 번호가 달라도 한 묶음으로 센다
def top_reasons(results: list[RunResult], limit: int = 3) -> list[str]:
    counts = Counter(
        _ID_LIST.sub("[…]", reason)
        for r in results
        for attempt in r.reject_reasons
        for reason in attempt
    )
    return [f"{n}번 · {reason}" for reason, n in counts.most_common(limit)]


# 많이 베낀 예시 문장
def top_copied(results: list[RunResult]) -> list[str]:
    counts = Counter(label for r in results for attempt in r.copied_examples for label in attempt)
    return [f"{n}번 · {label}" for label, n in counts.most_common()]


# 조사가 한 번 끝날 때마다 덮어써 저장한다 — 도중에 멈춰도(Ctrl+C) 그때까지 결과가 남는다
def _save(path: Path, head: dict[str, Any], results: list[RunResult], finished: bool) -> None:
    body = {**head, "finished": finished, "runs": [asdict(r) for r in results]}
    path.write_text(json.dumps(body, ensure_ascii=False, indent=2), encoding="utf-8")


def main(
    argv: list[str] | None = None,
    *,
    settings: Settings | None = None,
    ask: Callable[[str], str] = input,
    make_llm: Callable[[Settings], LlmClient] = build_llm_client,
    out: TextIO = sys.stdout,
) -> int:
    parser = argparse.ArgumentParser(description="요약 측정 — 샘플마다 조사를 N번 돌려 지표를 센다")
    parser.add_argument(
        "--runs", type=int, default=DEFAULT_RUNS, help=f"샘플마다 횟수 (1~{MAX_RUNS})"
    )
    parser.add_argument(
        "--sample", action="append", help="tests/fixtures의 샘플 이름 (여러 번 가능)"
    )
    parser.add_argument("--out", type=Path, help="결과 JSON 저장 경로 (예: before.local.json)")
    args = parser.parse_args(argv)
    if not 1 <= args.runs <= MAX_RUNS:
        print(f"--runs는 1~{MAX_RUNS}만 됩니다 (받은 값 {args.runs})", file=out)
        return 2
    settings = settings or Settings()
    samples = args.sample or list(SAMPLES)
    total = len(samples) * args.runs
    provider, model = provider_and_model(settings)
    # 돈이 드는 제공자만 묻는다 — mock · Ollama는 바로 돈다
    if settings.llm_provider == "openai":
        low, high = (total * n for n in calls_per_run(settings))
        question = (
            f"OpenAI({model})로 조사 {total}번 (AI 호출 {low}~{high}번), "
            f"예상 약 {_won(low)}~{_won(high)}원. 진행할까요? (y/N) "
        )
        if ask(question).strip().lower() != "y":
            print("취소했습니다 — OpenAI를 부르지 않았습니다", file=out)
            return 1
    llm = make_llm(settings)
    fingerprint = prompts.fingerprint([t.spec for t in EXTRA_TOOLS], settings.agent_max_extra_calls)
    print(f"측정 시작 · {provider} ({model}) · 지문 {fingerprint} · 조사 {total}번", file=out)
    head = {"provider": provider, "model": model, "fingerprint": fingerprint}
    results: list[RunResult] = []
    for name in samples:
        data = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
        runs = []
        for i in range(1, args.runs + 1):
            print(f"  {name} {i}/{args.runs} ...", file=sys.stderr)
            run = measure_run(name, data, llm, settings)
            print(f"    {run.status} · 다시 쓰기 {run.rewrites} · {run.seconds}초", file=sys.stderr)
            runs.append(run)
            results.append(run)
            if args.out:
                _save(args.out, head, results, finished=False)
        print(f"[{name}] {summarize(runs)}", file=out)
        for line in top_reasons(runs):
            print(f"    불합격 사유 {line}", file=out)
    print(f"[전체] {summarize(results)}", file=out)
    for line in top_reasons(results, limit=5):
        print(f"    불합격 사유 {line}", file=out)
    for line in top_copied(results):
        print(f"    베낀 예시 {line}", file=out)
    if args.out:
        _save(args.out, head, results, finished=True)
        print(f"결과 저장: {args.out}", file=out)
    return 0


if __name__ == "__main__":
    # Windows 기본 출력(cp949)은 "•" 같은 글자를 못 써서 멈춘다 → 항상 UTF-8 (run_report와 같다)
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    sys.stderr.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    sys.exit(main())
