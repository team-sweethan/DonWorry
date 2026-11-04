import json

import pytest

from app.agent.assemble import facts_json
from app.agent.evidence import decision_basis
from app.schemas.tools import (
    CasePaymentsTool,
    FdsPayment,
    LinkedPaymentsTool,
    PaymentHistoryTool,
)

# 판정 근거 · 판정 기준표 (#501)
# 실측 사건 모양(v3 시험지 1011 · 1066 · 1015 · 1014)을 줄여 옮겼다 — 같은 근거면 언제나 같은 판정

AT = "2026-05-21T11:16:43Z"
HEAD = {"caseId": "1011", "evidenceVersion": 1, "source": "P2", "retrievedAt": AT}
NONE = {
    "provided": True,
    "otherCustomerCount": 0,
    "paymentCount": 0,
    "resultCounts": [],
    "declinedCount": 0,
}


def _rule(code: str, name: str) -> dict[str, object]:
    return {"code": code, "name": name, "appliedScore": 25}


def _case(amount: int) -> CasePaymentsTool:
    return CasePaymentsTool.model_validate(
        {
            **HEAD,
            "payments": [
                {"paymentId": "1", "amount": amount, "paymentStatus": "HELD", "requestedAt": AT}
            ],
            "missingPaymentIds": [],
        }
    )


def _fds(rules: list[dict[str, object]], country: str = "KR") -> FdsPayment:
    return FdsPayment.model_validate(
        {
            "paymentId": "1",
            "riskScore": 50,
            "fdsResult": "REVIEW",
            "ruleHits": rules,
            "signals": {
                "cardDisplayLabel": None,
                "cardProvided": True,
                "deviceProvided": True,
                "ipCountryCode": country,
                "ipProvided": True,
            },
        }
    )


# (금액, 상태, 시각) 목록으로 고객 최근 결제를 만든다
def _history(rows: list[tuple[int, str, str]]) -> PaymentHistoryTool:
    return PaymentHistoryTool.model_validate(
        {
            **HEAD,
            "lookbackDays": 30,
            "payments": [
                {"paymentId": f"h{i}", "amount": a, "paymentStatus": s, "requestedAt": t}
                for i, (a, s, t) in enumerate(rows)
            ],
            "statusCounts": [],
            "truncated": False,
        }
    )


def _linked(card_others: int = 0) -> LinkedPaymentsTool:
    card = (
        {**NONE, "otherCustomerCount": card_others, "paymentCount": 9, "declinedCount": 2}
        if card_others
        else NONE
    )
    return LinkedPaymentsTool.model_validate(
        {
            **HEAD,
            "lookbackDays": 30,
            "card": card,
            "device": NONE,
            "ip": {**NONE, "provided": False},
        }
    )


def _basis(amount, rules, history, linked, country="KR"):
    case = _case(amount)
    return decision_basis(case.payments[0], _fds(rules, country), case, history, linked)


USUAL = [(200000 + i * 10000, "CAPTURED", f"2026-05-1{i}T10:00:00Z") for i in range(5)]


# 1011 — 같은 카드를 다른 고객이 씀(고위험) + 새 기기 · 새 카드 · 해외 접속(주의)
# 매입 완료 이력이 있어도 고액 징후가 없어 아무것도 설명하지 못한다 → 승인 취소 검토
def test_linked_card_with_new_card_and_overseas_is_reject() -> None:
    basis = _basis(
        205600,
        [_rule("R02", "신규 단말기"), _rule("R03", "신규 카드")],
        _history(USUAL),
        _linked(card_others=1),
        country="TH",
    )

    assert basis.rule == "HIGH_RISK_WITH_OTHERS"
    assert basis.recommendation == "REJECT_REVIEW"
    labels = {s.label: s for s in basis.signals}
    assert labels["같은 카드를 다른 고객이 사용"].level == "HIGH_RISK"
    assert "해외 접속 (TH)" in labels
    assert all(s.normalReason is None for s in basis.signals)
    similar = next(n for n in basis.normals if n.key == "SIMILAR_AMOUNT")
    assert similar.canExplain == ["고액 결제"] and similar.explains == []


# 1066 — 카드 테스팅 모양: 거절 후 재시도(고위험) + 단시간 반복(주의), 승인 이력 없음
# 단시간 반복은 거절 후 재시도와 같은 행동이라 따로 세지 않는다 → 추가 확인 (#501 리뷰)
# 지금 자료로는 카드 테스팅과 한도를 모르고 다시 누른 정상 고객을 가를 수 없다
def test_retry_with_repeat_counts_once_without_approved_history() -> None:
    declined = [
        (7800, "DECLINED", "2026-05-20T03:51:47Z"),
        (29500, "DECLINED", "2026-04-20T10:17:36Z"),
    ]
    basis = _basis(
        4600,
        [_rule("R04", "단시간 반복 결제"), _rule("R08", "거절 후 재시도")],
        _history(declined),
        _linked(),
    )

    assert basis.rule == "HIGH_RISK_ONLY"
    assert basis.recommendation == "NEEDS_CHECK"
    repeat = next(s for s in basis.signals if s.key == "R04")
    assert repeat.countedIn == "거절 후 재시도"


# 1015 — 같은 모양의 정상 고객: 거절 후 재시도만 센다 → 추가 확인
# (정상 고객을 승인 취소 검토로 보내지 않게)
def test_retry_with_repeat_is_needs_check_for_usual_customer() -> None:
    rows = [
        (32000, "CAPTURED", "2026-06-30T10:28:20Z"),
        (22100, "CAPTURED", "2026-06-27T11:47:43Z"),
    ]
    basis = _basis(
        28100,
        [_rule("R04", "단시간 반복 결제"), _rule("R08", "거절 후 재시도")],
        _history(rows),
        _linked(),
    )

    assert basis.rule == "HIGH_RISK_ONLY"
    assert basis.recommendation == "NEEDS_CHECK"
    retry = next(s for s in basis.signals if s.key == "R08")
    assert retry.normalReason is None and retry.countedIn is None


# 승인 이력이 있고 새 카드 규칙이 안 걸려도 "본인 카드"로 보지 않는다 (#501 리뷰)
# R03이 꺼져 있어도 안 걸리고, 고객 이력은 카드별이 아니다 — 단시간 반복만 남으면 추가 확인
def test_no_own_card_reason_from_missing_new_card_rule() -> None:
    approved = [(30000, "CAPTURED", "2026-05-01T10:00:00Z")]
    basis = _basis(28000, [_rule("R04", "단시간 반복 결제")], _history(approved), _linked())

    assert basis.rule == "CAUTION_ONLY"
    assert basis.signals[0].normalReason is None


# 1014 — 해외 여행 중인 정상 고객: 새 기기 · 새 카드 · 해외 접속(주의)만 → 추가 확인
def test_common_signals_only_is_needs_check() -> None:
    basis = _basis(
        150000,
        [_rule("R02", "신규 단말기"), _rule("R03", "신규 카드")],
        _history([(140000, "CAPTURED", "2026-05-01T10:00:00Z")]),
        _linked(),
        country="VN",
    )

    assert basis.rule == "CAUTION_ONLY"
    assert basis.recommendation == "NEEDS_CHECK"


# 평소 금액의 고액 결제 — 비슷한 금액 매입 완료 2건 이상이 고액 징후를 설명한다 → 매입 진행 검토
def test_high_amount_explained_by_usual_amount_is_accept() -> None:
    basis = _basis(220000, [_rule("R01", "고액 결제")], _history(USUAL), _linked())

    assert basis.rule == "ALL_EXPLAINED"
    assert basis.recommendation == "ACCEPT_REVIEW"
    assert basis.signals[0].normalReason == "최근 30일 비슷한 금액의 결제 5건 매입 완료"


# 비슷한 금액 이력이 1건뿐이면 정상 사유로 보지 않는다
def test_single_similar_payment_is_not_enough() -> None:
    basis = _basis(220000, [_rule("R01", "고액 결제")], _history(USUAL[:1]), _linked())

    assert basis.rule == "CAUTION_ONLY"


# 사건 결제 앞뒤 10분 안의 결제는 평소 결제가 아니다 — 같은 연속 결제로 고액을 설명하지 않는다
def test_burst_payments_are_not_usual_history() -> None:
    burst = [
        (220000, "CAPTURED", "2026-05-21T11:10:00Z"),
        (220000, "CAPTURED", "2026-05-21T11:12:00Z"),
    ]
    basis = _basis(220000, [_rule("R01", "고액 결제")], _history(burst), _linked())

    assert basis.rule == "CAUTION_ONLY"


# 사건 뒤 결제도 평소 결제가 아니다 (#501 리뷰)
# 보고서를 늦게 만들어도 사건 뒤 결제로 고액을 설명하지 않는다
def test_payments_after_case_are_not_usual_history() -> None:
    later = [
        (220000, "CAPTURED", "2026-05-22T10:00:00Z"),
        (220000, "CAPTURED", "2026-05-23T10:00:00Z"),
    ]
    basis = _basis(220000, [_rule("R01", "고액 결제")], _history(later), _linked())

    assert basis.rule == "CAUTION_ONLY"


# 고위험 국가 규칙이 걸렸으면 해외 접속을 따로 세지 않는다 · 예전 영문 코드도 같은 징후로 읽는다
def test_high_risk_country_and_legacy_codes() -> None:
    basis = _basis(
        100000,
        [_rule("R05", "고위험 국가 IP"), _rule("NEW_CARD", "처음 쓰는 카드")],
        None,
        None,
        country="NG",
    )

    keys = [s.key for s in basis.signals]
    assert keys == ["R05", "R03"]
    assert basis.rule == "CAUTION_ONLY"


# 징후 이름은 FDS 규칙 이름 그대로 — Case 상세 적중 규칙 · 조사 요약 밑줄과 같은 이름 (#505)
FDS_RULE_NAMES = [
    ("R01", "고액 결제"),
    ("R02", "신규 단말기"),
    ("R03", "신규 카드"),
    ("R04", "단시간 반복 결제"),
    ("R05", "고위험 국가 IP"),
    ("R06", "카드 테스팅"),
    ("R07", "차단 목록"),
    ("R08", "거절 후 재시도"),
]


@pytest.mark.parametrize(("code", "name"), FDS_RULE_NAMES)
def test_rule_signal_label_is_fds_rule_name(code: str, name: str) -> None:
    basis = _basis(100000, [_rule(code, name)], None, None)

    assert [s.label for s in basis.signals] == [name]


# AI에게 주는 근거에 판정 근거 · 기준표 판정이 들어가고 영문 판정 코드는 없다
def test_facts_include_basis_in_korean() -> None:
    case = _case(205600)
    fds = {"1": _fds([_rule("R03", "신규 카드")], country="TH")}

    facts = json.loads(facts_json(case, fds, None, _history(USUAL), _linked(card_others=1)))

    basis = facts["결제"][0]["판정 근거"]
    assert basis["기준표 판정"].endswith("승인 취소 검토")
    assert basis["고위험 징후"] == [{"징후": "같은 카드를 다른 고객이 사용", "정상 사유": "없음"}]
    assert "REJECT_REVIEW" not in json.dumps(facts, ensure_ascii=False)


# --- Codex 리뷰 재현 (#501) ---


# AI 입력의 판정 근거에 든 숫자는 문장 검사가 허용하는 숫자여야 한다
# 비슷한 금액 이력이 10건을 넘으면 "17건 매입 완료"가 AI 입력에 들어가는데 허용 숫자에는 없어
# AI가 근거를 그대로 옮겨 쓰면 다시 쓰기를 거듭하다 보고서가 실패할 수 있었다 (10 이하 수량만 예외)
def test_basis_facts_have_no_number_outside_allowed() -> None:
    from app.agent.assemble import fact_numbers
    from app.agent.narrative import _numbers

    # 사건(5월 21일) 전 비슷한 금액 17건 — 날짜 · 시각 · 금액에 17이 우연히 들어가지 않게 둔다
    days = [f"2026-04-0{d}" for d in range(1, 10)] + [f"2026-05-0{d}" for d in range(1, 9)]
    similar = [(200000 + i * 1000, "CAPTURED", f"{day}T10:00:00Z") for i, day in enumerate(days)]
    others = [(5000, "CAPTURED", f"2026-03-0{d}T10:00:00Z") for d in range(1, 4)]
    history = _history(similar + others)
    case = _case(205600)
    fds = {"1": _fds([_rule("R01", "고액 결제")])}

    facts = json.loads(facts_json(case, fds, None, history, _linked()))

    basis_text = json.dumps(facts["결제"][0]["판정 근거"], ensure_ascii=False)
    assert _numbers(basis_text) <= fact_numbers(case, fds, None, history)


# --- 사건 도중 결제 재현 (#532) ---
# 사건은 30분 안의 보류 결제를 계속 합친다 — 10:00 보류 → 10:14 정상 결제 2건 → 10:28 보류
# 10:14 결제는 두 사건 결제와 10분 넘게 떨어져 있지만 사건이 시작된 뒤의 결제라 평소 결제가 아니다
STRETCHED = [("1", "2026-05-21T10:00:00Z"), ("2", "2026-05-21T10:28:00Z")]
DURING = [
    (220000, "CAPTURED", "2026-05-21T10:14:00Z"),
    (230000, "CAPTURED", "2026-05-21T10:14:30Z"),
]


def _stretched_case() -> CasePaymentsTool:
    return CasePaymentsTool.model_validate(
        {
            **HEAD,
            "payments": [
                {"paymentId": pid, "amount": 220000, "paymentStatus": "HELD", "requestedAt": at}
                for pid, at in STRETCHED
            ],
            "missingPaymentIds": [],
        }
    )


# 사건 도중 결제로 고액 징후를 설명하지 않는다 — 두 사건 결제 모두 추가 확인
def test_payments_during_stretched_case_are_not_usual_history() -> None:
    case = _stretched_case()

    for payment in case.payments:
        basis = decision_basis(
            payment, _fds([_rule("R01", "고액 결제")]), case, _history(DURING), _linked()
        )
        assert basis.normals == []
        assert basis.recommendation == "NEEDS_CHECK"


# AI에게도 사건 도중 결제를 "그 전 결제"(평소 결제)로 보여 주지 않는다 — 판정과 같은 기준
def test_facts_do_not_show_payments_during_case_as_earlier() -> None:
    from app.agent.assemble import EARLIER_KEY

    case = _stretched_case()
    facts = json.loads(facts_json(case, {}, None, _history(DURING), None))

    assert facts["고객 최근 결제(이 사건 밖)"][EARLIER_KEY] == []


# --- FDS 점수 없음 재현 (#532) ---
# 평가 오류(점수 없음 · 적중 규칙 없음)를 "징후 없음"으로 읽어 매입 진행 검토가 나왔다
# 지금은 보고서 생성 전 검사(graph._ready_to_write)가 막지만, 판정 코드도 스스로 막는다
# 점수 없음은 판정을 올리지도 내리지도 않는다


def _error_fds() -> FdsPayment:
    return _fds([]).model_copy(update={"riskScore": None, "fdsResult": "ERROR"})


def _linked_card_and_device() -> LinkedPaymentsTool:
    linked = _linked(card_others=1)
    return linked.model_copy(update={"device": linked.card})


def _error_basis(linked: LinkedPaymentsTool):
    case = _case(220000)
    return decision_basis(case.payments[0], _error_fds(), case, _history(USUAL), linked)


# 점수 없음만 있으면 추가 확인 — 매입 진행 검토로 가지 않는다
def test_fds_error_alone_is_needs_check() -> None:
    basis = _error_basis(_linked())

    assert basis.recommendation == "NEEDS_CHECK"
    assert "FDS 평가 결과 미확인" in [s.label for s in basis.signals]


# 점수 없음 + 고위험 징후 하나 — 점수 없음을 "다른 징후"로 세어 승인 취소 검토로 올리지 않는다
def test_fds_error_does_not_raise_single_high_risk() -> None:
    basis = _error_basis(_linked(card_others=1))

    assert basis.rule == "HIGH_RISK_ONLY"
    assert basis.recommendation == "NEEDS_CHECK"


# 점수 없음 + 고위험 · 다른 징후가 겹치면 승인 취소 검토를 유지한다 — 내리지도 않는다
def test_fds_error_keeps_reject_from_other_signals() -> None:
    basis = _error_basis(_linked_card_and_device())

    assert basis.recommendation == "REJECT_REVIEW"
