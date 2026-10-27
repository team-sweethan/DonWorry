import argparse
import json
import sys
import time
from datetime import UTC, datetime
from pathlib import Path

from app.agent.graph import run_investigation
from app.agent.llm import build_llm_client
from app.agent.tools import SpringToolBox, StaticToolBox, ToolBox
from app.client.spring import SpringToolClient
from app.config import Settings
from app.schemas.case_context import CaseContext
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool

# Case 하나를 조사해 보고서 JSON을 출력한다 — Spring 접수 · 콜백(#253) 없이 Agent만 확인할 때 쓴다
# 예) Spring 없이 샘플로
#     uv run python -m scripts.run_report --sample tests/fixtures/sample_case.json
# 예) 로컬 Spring으로 (Spring은 AI_TOOLS_ENABLED=true로 켠다)
#     uv run python -m scripts.run_report --case-id 5
# Qwen 사용: AI_SERVER_LLM_PROVIDER=ollama (기본 mock)


def main() -> int:
    parser = argparse.ArgumentParser(description="조사 Agent 단독 실행")
    parser.add_argument("--case-id", help="Spring에서 조회할 Case ID (샘플을 쓰면 샘플 값)")
    parser.add_argument(
        "--sample", type=Path, help="Tool 응답 샘플 JSON (casePayments · fdsEvidence)"
    )
    parser.add_argument("--evidence-version", type=int, default=None)
    parser.add_argument("--report-version", type=int, default=1)
    parser.add_argument("--merchant-id", default="1")
    parser.add_argument("--customer-ref", default="demo-customer")
    args = parser.parse_args()

    settings = Settings()
    toolbox: ToolBox
    if args.sample:
        data = json.loads(args.sample.read_text(encoding="utf-8"))
        case_payments = CasePaymentsTool.model_validate(data["casePayments"])
        toolbox = StaticToolBox(case_payments, FdsEvidenceTool.model_validate(data["fdsEvidence"]))
        case_id = case_payments.caseId
        evidence_version = args.evidence_version or case_payments.evidenceVersion
    elif args.case_id:
        client = SpringToolClient(
            settings.spring_base_url, settings.internal_api_key, settings.http_timeout_seconds
        )
        toolbox = SpringToolBox(client, args.case_id)
        case_id = args.case_id
        evidence_version = (
            args.evidence_version or client.get_case_payments(case_id).evidenceVersion
        )
    else:
        parser.error("--case-id 또는 --sample 중 하나가 필요하다")

    context = CaseContext(
        case_id=case_id,
        evidence_version=evidence_version,
        report_version=args.report_version,
        merchant_id=args.merchant_id,
        customer_ref=args.customer_ref,
        case_created_at=datetime.now(UTC).isoformat().replace("+00:00", "Z"),
    )
    model = settings.ollama_model if settings.llm_provider == "ollama" else "mock"
    print(f"조사 시작 · Case {case_id} · LLM {settings.llm_provider} ({model})", file=sys.stderr)
    started = time.monotonic()
    outcome = run_investigation(context, build_llm_client(settings), toolbox, settings)
    elapsed = time.monotonic() - started

    for seq, record in enumerate(outcome.records, start=1):
        by = "코드 보충" if record.filled_by_system else "AI 선택"
        print(f"  {seq}. {record.tool_name} {record.args} · {by}", file=sys.stderr)
    print(f"결과 {outcome.status} · {elapsed:.1f}초", file=sys.stderr)
    if outcome.report is not None:
        print(outcome.report.model_dump_json(indent=2))
    else:
        print(json.dumps(outcome.error, ensure_ascii=False, indent=2))
    return 0 if outcome.status == "SUCCEEDED" else 1


if __name__ == "__main__":
    sys.exit(main())
