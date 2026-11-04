import json
from pathlib import Path

import pytest

from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import LinkedPaymentsTool, PaymentHistoryTool, SimilarCasesTool
from tests.fakes import context

# 권고 측정 샘플 (권고 기준 조정) — run_report --sample로 실제 LLM 권고를 고치기 전 · 후 비교한다
# 여기서는 샘플이 읽히고 조사가 끝까지 되는지만 본다. 실제 권고는 mock이라 확인하지 않는다

FIXTURES = Path(__file__).parent / "fixtures"
SETTINGS = Settings(_env_file=None)
MEASURES = ["measure_reject.json", "measure_accept.json", "measure_mixed.json"]


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


# 측정 샘플의 고객 이력 · 같은 카드 결제 · 비슷한 사건이 도구 응답으로 그대로 나간다
@pytest.mark.parametrize("name", MEASURES)
def test_measure_sample_runs_with_its_evidence(name: str) -> None:
    data = _load(name)
    case_id = data["casePayments"]["caseId"]

    outcome = run_investigation(
        context(case_id, 1), MockLlmClient(), StaticToolBox.from_sample(data), SETTINGS
    )

    assert outcome.status == "SUCCEEDED"
    results = {record.tool_name: record.result for record in outcome.records}
    assert results["get_payment_history"] == PaymentHistoryTool.model_validate(data["history"])
    assert results["find_linked_payments"] == LinkedPaymentsTool.model_validate(data["linked"])
    if "similar" in data:
        assert results["find_similar_cases"] == SimilarCasesTool.model_validate(data["similar"])


# 예전 샘플(결제 · FDS 근거만)은 지금처럼 이력 · 연관 결제 · 비슷한 사건이 "없음"이다
def test_old_sample_keeps_empty_evidence() -> None:
    box = StaticToolBox.from_sample(_load("sample_case.json"))

    assert box.get_payment_history().payments == []
    assert box.find_similar_cases().cases == []
    assert box.find_linked_payments().card.provided is False
