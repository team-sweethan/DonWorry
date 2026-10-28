import re
from pathlib import Path

import pytest

from app.schemas.report import InvestigationTrace, Overview, PaymentAnalysis, ReportBody, TraceStep

# AI 보고서 형식과 화면 타입(frontend/src/types/case.ts의 ReportBody)이 어긋나지 않는지 본다
# (ADR-0009 결정 4)
CASE_TS = Path(__file__).resolve().parents[2] / "frontend" / "src" / "types" / "case.ts"


def _block(source: str, start: str) -> str:
    begin = source.index(start) + len(start)
    depth, end = 1, begin
    while depth:
        depth += {"{": 1, "}": -1}.get(source[end], 0)
        end += 1
    return source[begin : end - 1]


# 화면 타입의 선택 항목(`verdict?:`)도 키로 읽는다
# 예전 형식 보고서 때문에 화면에서만 선택 항목이다
def _keys(block: str, indent: int) -> list[str]:
    return re.findall(rf"^ {{{indent}}}(\w+)\??:", block, flags=re.MULTILINE)


@pytest.mark.skipif(not CASE_TS.exists(), reason="frontend 소스가 없는 환경")
def test_report_body_matches_frontend_type() -> None:
    source = CASE_TS.read_text(encoding="utf-8")
    body = _block(source, "export type ReportBody = {")

    assert _keys(body, 2) == list(ReportBody.model_fields)
    assert _keys(_block(body, "overview: {"), 4) == list(Overview.model_fields)
    analyses = _block(body, "paymentAnalyses: Array<{")
    assert _keys(analyses, 4) == list(PaymentAnalysis.model_fields)
    # 조사 과정 기록(#294)은 따로 선언한 타입이라 이름으로 찾는다
    assert _keys(_block(source, "export type InvestigationTrace = {"), 2) == list(
        InvestigationTrace.model_fields
    )
    assert _keys(_block(source, "export type TraceStep = {"), 2) == list(TraceStep.model_fields)
