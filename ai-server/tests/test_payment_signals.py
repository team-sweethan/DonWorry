import copy
import json

from app.agent.assemble import MISSING_SIGNAL_LIMITATIONS, facts_json
from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool
from tests.fakes import context

# 결제별 카드 · 기기 · 접속 국가 (#346) — 사실 값은 Tool 기록에서 코드가 채우고,
# AI에는 있음/없음과 국가만 보여 준다. 가맹점이 안 보낸 값은 한계 줄로 남긴다

SETTINGS = Settings(_env_file=None)
FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())
CARD_ONLY = {
    "cardDisplayLabel": "신한 ****1234",
    "cardProvided": True,
    "deviceProvided": True,
    "ipCountryCode": None,
    "ipProvided": False,
}
FULL = {
    "cardDisplayLabel": "국민 ****5678",
    "cardProvided": True,
    "deviceProvided": True,
    "ipCountryCode": "US",
    "ipProvided": True,
}


def _fds(signals: list[dict]) -> FdsEvidenceTool:
    data = copy.deepcopy(FIXTURE["fdsEvidence"])
    for payment, s in zip(data["payments"], signals, strict=True):
        payment["signals"] = s
    return FdsEvidenceTool.model_validate(data)


def _toolbox(signals: list[dict]) -> StaticToolBox:
    return StaticToolBox(CasePaymentsTool.model_validate(FIXTURE["casePayments"]), _fds(signals))


# 보고서의 결제별 카드 · 기기 · 접속 국가는 Tool 응답 그대로다 — AI가 지어낼 칸이 없다
def test_payment_signals_filled_from_tool_record_not_ai() -> None:
    outcome = run_investigation(context(), MockLlmClient(), _toolbox([CARD_ONLY, FULL]), SETTINGS)

    assert outcome.status == "SUCCEEDED"
    saved = [a.signals.model_dump() for a in outcome.report.paymentAnalyses]
    assert saved == [CARD_ONLY, FULL]


# AI 입력에는 카드 표시값(끝자리 숫자)이 들어가지 않는다 — 있음/없음 · 국가만
def test_card_label_not_in_ai_facts() -> None:
    case_payments = CasePaymentsTool.model_validate(FIXTURE["casePayments"])
    fds = {p.paymentId: p for p in _fds([CARD_ONLY, FULL]).payments}

    facts = facts_json(case_payments, fds)

    for label in ("신한", "1234", "국민", "5678"):
        assert label not in facts
    first = json.loads(facts)["결제"][0]["카드 · 기기 · 접속"]
    assert first == {
        "카드 정보": "있음",
        "기기 정보": "있음",
        "접속 국가": "정보 없음 (가맹점이 보내지 않음)",
    }


# 가맹점이 IP를 보내지 않은 결제가 있으면 한계 줄에 남는다 — 직원이 "해외 접속 아님"으로 읽지 않게
def test_missing_ip_adds_limitation() -> None:
    without_ip = run_investigation(
        context(), MockLlmClient(), _toolbox([CARD_ONLY, FULL]), SETTINGS
    )
    with_ip = run_investigation(context(), MockLlmClient(), _toolbox([FULL, FULL]), SETTINGS)

    assert MISSING_SIGNAL_LIMITATIONS["ip"] in without_ip.report.recommendation.limitations
    assert MISSING_SIGNAL_LIMITATIONS["ip"] not in with_ip.report.recommendation.limitations
