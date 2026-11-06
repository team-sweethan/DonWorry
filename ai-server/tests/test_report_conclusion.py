from app.agent.evidence import (
    _RULES,
    CHECK_BEFORE,
    FDS_UNCONFIRMED,
    LINKED_CARD,
    LINKED_DEVICE,
    LINKED_IP,
    OVERSEAS,
)
from app.agent.graph import run_investigation
from app.config import Settings
from app.schemas.tools import LinkedPaymentsTool
from tests.fakes import ScriptedLlm, context, narrative, toolbox
from tests.test_extra_history import DATA, _Asks90, _box
from tests.test_extra_history import _run as _run_extra
from tests.test_linked_payments import LINKED, _toolbox

# 결론 먼저 보고서 (#591) — 개요 한 줄 · 판정 전에 확인할 것 · 안전망 · 30일 경계 표시

SETTINGS = Settings(_env_file=None)


def _linked_run(*outputs: str):
    llm = ScriptedLlm(turns=[], outputs=list(outputs))
    box = _toolbox(LinkedPaymentsTool.model_validate(LINKED))
    return llm, run_investigation(context(), llm, box, SETTINGS)


# 다시 쓰기를 다 써도 눈여겨볼 사실만 빠졌으면 성공으로 넘기고 빠진 사실을 덧붙인다
def test_safety_net_only_notable_missing_after_repairs_appends_missing_facts() -> None:
    llm, outcome = _linked_run(narrative())

    assert outcome.status == "SUCCEEDED"
    assert llm.write_calls == SETTINGS.agent_max_repairs + 1
    report = outcome.report
    notable = [f.id for f in report.facts if f.notable]
    assert notable and report.summaryAppended == notable
    last_write = [s for s in report.investigationTrace.steps if s.kind == "WRITE"][-1]
    assert "시스템이 덧붙임" in (last_write.received or "")


# 틀린 숫자가 남아 있으면 안전망 없이 그대로 실패한다
def test_safety_net_with_wrong_number_still_fails_narrative_invalid() -> None:
    wrong = narrative(summary=[{"text": "다른 고객 결제가 77건 있습니다.", "facts": ["접속-1"]}])

    _, outcome = _linked_run(wrong)

    assert outcome.status == "FAILED"
    assert outcome.error["code"] == "NARRATIVE_INVALID"


# 결제 여러 건 개요의 건수 · 합계 · 시각 · 규칙 이름이 조회 값과 같다
def test_case_overview_multiple_payments_matches_tool_values() -> None:
    llm = ScriptedLlm(turns=[], outputs=[narrative()])

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.report.caseOverview == (
        "고객 customer-1의 결제 2건(합계 770,000원, 10/27 21:00~21:07)이 "
        "고액 결제 · 처음 쓰는 카드 · 단시간 반복 결제 규칙에 걸려 매입 보류된 사건입니다."
    )


# 모든 징후에 판정 전에 확인할 것 문장이 있다
def test_check_before_every_signal_key_has_sentence() -> None:
    keys = [*_RULES, OVERSEAS, LINKED_CARD, LINKED_DEVICE, LINKED_IP, FDS_UNCONFIRMED]

    assert [k for k in keys if not CHECK_BEFORE.get(k, "").strip()] == []


# 30일 기본 조회에 든 결제만 기준표 범위로 표시한다
def test_in_basis_true_only_for_payments_in_30_day_lookup() -> None:
    outcome = _run_extra(_Asks90(), _box())

    table = outcome.report.paymentHistory
    base_ids = {p["paymentId"] for p in DATA["history"]["payments"]}
    assert table.lookbackDays == 90
    assert any(not item.inBasis for item in table.payments)
    assert all(item.inBasis == (item.paymentId in base_ids) for item in table.payments)
