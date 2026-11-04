import json

from app.agent.assemble import BURST_KEY, drop_rule_only, facts_json
from app.agent.graph import run_investigation
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import CasePaymentsTool, FdsEvidenceTool, PaymentHistoryTool
from tests.fakes import ScriptedLlm, context, narrative

# 같은 연속 결제를 정상 쪽으로 쓴 AI 문장 · 규칙만 되풀이한 위험 문장 (#542)
# 로컬 시연 Case 2(OpenAI gpt-4.1-mini)에서 실제로 나온 문장을 그대로 쓴다
# 판정 코드는 같은 연속 결제를 정상 근거에서 뺐는데
# AI 문장만 정상으로 써서 화면이 서로 반대로 보였다
SETTINGS = Settings(_env_file=None)
FIXTURE = json.loads(open("tests/fixtures/sample_case.json", encoding="utf-8").read())
PAYMENTS = ["9001", "9002"]

BURST_SUMMARY = (
    "이 고객은 같은 날 같은 금액으로 연속 결제를 여러 건 했으나, 모두 매입 완료된 상태입니다."
)
BURST_OVERALL = (
    "고액 결제와 단시간 반복 결제 규칙 적중은 위험 신호이나, "
    "같은 고객의 연속 결제 이력이 있어 일부 정상 가능성이 있습니다."
)
BURST_NORMAL = "고객은 같은 날 같은 금액으로 연속 결제를 여러 건 했고, 모두 매입 완료되었습니다."
CLEAN_NORMAL = "같은 카드와 기기를 다른 고객이 사용한 기록은 없습니다."
RULE_ONLY = "고액 결제와 단시간 반복 결제 규칙에 적중하여 의심 신호가 있습니다."

# 첫 사건 결제(12:00) 몇 초 전 — 같은 연속 결제
BURST_TIMES = ["2026-10-27T11:59:50Z", "2026-10-27T11:59:55Z"]
# 사건 몇 주 전 — 평소 결제
EARLIER_TIMES = ["2026-10-07T03:00:00Z", "2026-10-14T03:00:00Z"]


def _history(times: list[str]) -> PaymentHistoryTool:
    return PaymentHistoryTool.model_validate(
        {
            "caseId": "101",
            "evidenceVersion": 2,
            "source": "P5",
            "retrievedAt": "2026-10-27T12:05:00Z",
            "lookbackDays": 30,
            "payments": [
                {
                    "paymentId": f"80{i}",
                    "amount": 650000,
                    "paymentStatus": "CAPTURED",
                    "requestedAt": t,
                }
                for i, t in enumerate(times)
            ],
            "statusCounts": [{"paymentStatus": "CAPTURED", "count": len(times)}],
            "truncated": False,
        }
    )


def _written() -> str:
    body = json.loads(narrative(PAYMENTS))
    body["summary"] = ["결제 2건 모두 처음 쓰는 카드로 결제됐습니다.", BURST_SUMMARY]
    body["overallAnalysis"] = ["짧은 간격의 반복 결제입니다.", BURST_OVERALL]
    body["payments"]["9001"]["normalEvidence"] = [CLEAN_NORMAL, BURST_NORMAL]
    return json.dumps(body, ensure_ascii=False)


def _run(history: PaymentHistoryTool):
    llm = ScriptedLlm(turns=[], outputs=[_written()])
    toolbox = StaticToolBox(
        CasePaymentsTool.model_validate(FIXTURE["casePayments"]),
        FdsEvidenceTool.model_validate(FIXTURE["fdsEvidence"]),
        history=history,
    )
    return llm, run_investigation(context(), llm, toolbox, SETTINGS)


# AI에게 같은 연속 결제의 결제 상태("매입 완료")를 주지 않는다 — 정상 근거로 쓸 재료를 주지 않는다
def test_facts_burst_payments_have_no_status() -> None:
    cases = CasePaymentsTool.model_validate(FIXTURE["casePayments"])

    facts = json.loads(facts_json(cases, {}, None, _history(BURST_TIMES), None))

    burst = facts["고객 최근 결제(이 사건 밖)"][BURST_KEY]
    assert len(burst) == 2
    assert all("결제 상태" not in p for p in burst)


# 같은 연속 결제가 있는 사건이면 그 결제를 정상으로 쓴 문장을 다시 쓰게 하지 않고 뺀다
def test_burst_as_normal_sentences_removed_without_rewrite() -> None:
    llm, outcome = _run(_history(BURST_TIMES))

    report = outcome.report
    first = next(a for a in report.paymentAnalyses if a.paymentId == "9001")
    assert llm.write_calls == 1
    assert outcome.status == "SUCCEEDED"
    assert first.normalEvidence == [CLEAN_NORMAL]
    assert BURST_OVERALL not in report.overallAnalysis
    assert BURST_SUMMARY not in report.summary


# 같은 연속 결제가 없으면 지난 결제가 매입 완료라는 말은 평소 결제에 대한 사실이라 남긴다
def test_burst_sentences_kept_when_case_has_no_burst() -> None:
    _, outcome = _run(_history(EARLIER_TIMES))

    report = outcome.report
    first = next(a for a in report.paymentAnalyses if a.paymentId == "9001")
    assert BURST_NORMAL in first.normalEvidence
    assert BURST_SUMMARY in report.summary


# "규칙에 적중하여 의심 신호가 있습니다"도 규칙 카드와 같은 내용이라 뺀다
# 다른 정황이 섞이면 남긴다
def test_rule_only_risk_variant_is_dropped() -> None:
    with_context = "고액 결제 규칙에 적중했고 처음 쓰는 카드라 카드 주인 확인이 필요합니다."

    assert drop_rule_only([RULE_ONLY, with_context]) == [with_context]
