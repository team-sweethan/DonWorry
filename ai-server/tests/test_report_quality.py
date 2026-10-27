import json

from app.agent.assemble import ALWAYS_LIMITATIONS, ALWAYS_UNKNOWN, facts_json
from app.agent.graph import run_investigation
from app.agent.llm import MockLlmClient
from app.agent.narrative import validate_narrative
from app.agent.prompts import WRITE_EXAMPLE
from app.agent.tools import StaticToolBox
from app.config import Settings
from app.schemas.tools import CasePayment, CasePaymentsTool, FdsEvidenceTool, FdsPayment
from tests.fakes import ScriptedLlm, call, context, narrative, toolbox

# Qwen 실제 실행에서 나온 실수를 코드가 막는지 본다
# (근거에 없는 숫자 · 코드값 · 확률 단정 · "미확인 없음")
SETTINGS = Settings(_env_file=None)
PAYMENTS = ["9001", "9002"]


def _run(*outputs: str) -> tuple[ScriptedLlm, object]:
    llm = ScriptedLlm(turns=[], outputs=list(outputs))
    return llm, run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)


# 근거에 없는 숫자("60점 이상" — 실제는 65 · 55)는 돌려보내 고쳐 쓰게 한다
def test_number_not_in_facts_is_repaired() -> None:
    wrong = narrative(PAYMENTS, overallAnalysis=["두 결제 모두 FDS 점수가 60점 이상입니다."])

    llm, outcome = _run(wrong, narrative(PAYMENTS))

    assert llm.write_calls == 2
    assert outcome.status == "SUCCEEDED"
    assert outcome.report.overallAnalysis == ["짧은 간격의 반복 결제입니다."]


# 근거에 있는 숫자는 쉼표 · "만 원" 표기여도 통과한다
def test_fact_numbers_are_allowed() -> None:
    ok = narrative(
        PAYMENTS,
        summary=["65만 원(650,000원) 결제가 65점, 결제 9002가 55점으로 2건입니다."],
    )

    llm, outcome = _run(ok)

    assert llm.write_calls == 1
    assert outcome.status == "SUCCEEDED"


def test_code_value_is_repaired() -> None:
    wrong = narrative(PAYMENTS, recommendationSummary="두 결제 모두 REJECT_REVIEW를 권고합니다.")

    llm, outcome = _run(wrong, narrative(PAYMENTS))

    assert llm.write_calls == 2
    assert outcome.status == "SUCCEEDED"


def test_status_code_is_repaired() -> None:
    wrong = narrative(PAYMENTS, summary=["두 결제 모두 HELD 상태입니다."])

    llm, _ = _run(wrong, narrative(PAYMENTS))

    assert llm.write_calls == 2


# 확률처럼 단정하는 표현은 쓰지 못하게 한다 (FDS 점수는 확률이 아니다)
def test_assertive_phrase_is_repaired() -> None:
    wrong = narrative(PAYMENTS, overallAnalysis=["두 결제 모두 사기 가능성이 높습니다."])

    llm, outcome = _run(wrong, narrative(PAYMENTS))

    assert llm.write_calls == 2
    assert outcome.status == "SUCCEEDED"


# 고쳐 쓰기 횟수를 넘어도 계속 틀리면 실패로 끝낸다
def test_content_keeps_failing_ends_failed() -> None:
    wrong = narrative(PAYMENTS, overallAnalysis=["사기 확률은 80%입니다."])

    llm, outcome = _run(wrong)

    assert outcome.status == "FAILED"
    assert outcome.error["code"] == "NARRATIVE_INVALID"


# 확인 못 한 것은 코드가 항상 채우고, "미확인 사항 없음" 같은 문장은 걸러 낸다
def test_unknowns_and_limitations_are_always_filled() -> None:
    body = json.loads(narrative(PAYMENTS, limitations=["확인되지 않은 정보가 없습니다."]))
    for payment in body["payments"].values():
        payment["unknowns"] = []

    _, outcome = _run(json.dumps(body, ensure_ascii=False))

    report = outcome.report
    assert all(ALWAYS_UNKNOWN in p.unknowns for p in report.paymentAnalyses)
    limitations = report.recommendation.limitations
    assert "확인되지 않은 정보가 없습니다." not in limitations
    assert all(item in limitations for item in ALWAYS_LIMITATIONS)


# Qwen에게 주는 근거는 코드값 대신 한국어 이름으로 보여 준다
def test_facts_use_korean_labels() -> None:
    box = toolbox("sample_case.json")
    fds = {p.paymentId: p for p in box.get_fds_evidence(None).payments}

    facts = facts_json(box.get_case_payments(), fds)

    assert "매입 보류" in facts and "검토" in facts
    assert "HELD" not in facts and "REVIEW" not in facts


# Qwen이 지어낸 인자(case_id)는 쓰지도 기록하지도 않는다
def test_invented_tool_args_are_dropped() -> None:
    llm = ScriptedLlm(
        turns=[[call("get_case_payments", case_id=101)], [call("get_fds_evidence")], None],
        outputs=[narrative(PAYMENTS)],
    )

    outcome = run_investigation(context(), llm, toolbox("sample_case.json"), SETTINGS)

    assert outcome.records[0].args == {}


# 프롬프트 예시 문장도 내용 검사를 통과해야 한다 — 예시가 규칙을 어기면 Qwen이 따라 한다
def test_prompt_example_passes_content_checks() -> None:
    example = dict(WRITE_EXAMPLE)
    example["payments"] = {"9201": WRITE_EXAMPLE["payments"]["<결제 ID>"]}

    narrative_obj, errors = validate_narrative(
        json.dumps(example, ensure_ascii=False), ["9201"], set()
    )

    assert errors == []
    assert narrative_obj is not None


# 타임라인은 코드가 만드는 칸이라 코드값 대신 한국어로 쓴다
def test_timeline_uses_korean_fds_result() -> None:
    outcome = run_investigation(
        context("103", 1), MockLlmClient(), toolbox("sample_single.json"), SETTINGS
    )

    assert outcome.status == "SUCCEEDED"
    assert [t.event for t in outcome.report.timeline] == ["9201 결제 요청 · FDS 검토 (55점)"]


# 작은 숫자 예외는 수량 · 시간 단위 앞에서만 — "5점"처럼 근거에 없는 점수는 걸린다
def test_small_score_not_in_facts_is_repaired() -> None:
    wrong = json.loads(narrative(PAYMENTS))
    wrong["payments"]["9001"]["riskEvidence"] = ["FDS 점수는 5점입니다."]

    llm, outcome = _run(json.dumps(wrong, ensure_ascii=False), narrative(PAYMENTS))

    assert llm.write_calls == 2
    assert outcome.status == "SUCCEEDED"


def test_small_counts_are_allowed() -> None:
    ok = narrative(PAYMENTS, overallAnalysis=["10분 안에 3회 결제 시도가 있었고 결제는 2건입니다."])

    llm, _ = _run(ok)

    assert llm.write_calls == 1


# 같은 초 안의 결제도 실제 시각 순서로 보인다 (카드 테스팅처럼 결제가 몰릴 때)
def test_timeline_sorted_by_real_time() -> None:
    payments = CasePaymentsTool(
        caseId="104",
        evidenceVersion=1,
        source="P3·P5",
        retrievedAt="2026-10-27T12:00:05Z",
        payments=[
            CasePayment(
                paymentId="9302",
                amount=1000,
                paymentStatus="HELD",
                requestedAt="2026-10-27T12:00:00.100Z",
            ),
            CasePayment(
                paymentId="9301",
                amount=1000,
                paymentStatus="HELD",
                requestedAt="2026-10-27T12:00:00Z",
            ),
        ],
        missingPaymentIds=[],
    )
    fds = FdsEvidenceTool(
        caseId="104",
        evidenceVersion=1,
        source="P2",
        retrievedAt="2026-10-27T12:00:05Z",
        payments=[
            FdsPayment(paymentId=p, riskScore=55, fdsResult="REVIEW", ruleHits=[])
            for p in ("9301", "9302")
        ],
        missingPaymentIds=[],
    )

    outcome = run_investigation(
        context("104", 1), MockLlmClient(), StaticToolBox(payments, fds), SETTINGS
    )

    assert [t.event.split()[0] for t in outcome.report.timeline] == ["9301", "9302"]
