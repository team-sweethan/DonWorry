from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol

from app.client.spring import SpringToolClient
from app.schemas.tools import (
    CasePaymentsTool,
    DecisionBasisTool,
    FdsEvidenceTool,
    LinkedCasesTool,
    LinkedKind,
    LinkedMatch,
    LinkedPaymentsTool,
    OrderContextTool,
    OrderPayment,
    PaymentHistoryTool,
    SimilarCasesTool,
    ToolResult,
)

# 추가 조회마다 AI가 고르는 "어느 의심 때문인지" 인자 (#817)
# 값은 이번 사건 의심 목록의 번호(의심-N)나 사건 전체(CASE)만
# 사건마다 목록이 달라 case_spec이 enum을 붙인다
SUSPICION_ARG = "suspicion"
CASE_WIDE = "CASE"
SUSPICION_DESCRIPTION = (
    "이 조회로 확인하려는 의심. [의심 목록]의 번호(예: 의심-1)를 고른다. "
    "특정 의심이 아니라 사건 전체를 보려면 CASE."
)


# 추가 조사 도구 — 기본 조회(코드)를 본 AI가 더 볼 때만 고른다 (#578)
# 읽기 전용만 둔다 (AGENTS.md 6절). caseId 같은 권한 인자는 AI가 아니라 ToolBox가 묶는다
# check: AI가 준 인자에서 실행할 인자만 남긴다. 허용 값이 아니면 None — 실행하지 않는다 (#817)
#   주지 않으면 도구 설명에 있는 인자만 남긴다 (AI가 지어낸 case_id 등은 버림)
# action: 코드가 쓰는 조회 이유의 뒷부분 — 이유 문장은 AI가 쓰지 않는다 (#817 선택 A)
@dataclass(frozen=True)
class ExtraTool:
    spec: dict[str, Any]
    title: str
    run: Callable[["ToolBox", dict[str, Any]], ToolResult]
    check: Callable[[dict[str, Any]], dict[str, Any] | None] | None = None
    action: Callable[[dict[str, Any]], str] | None = None

    @property
    def name(self) -> str:
        return self.spec["function"]["name"]

    def allowed_args(self, raw: dict[str, Any]) -> dict[str, Any] | None:
        if self.check is not None:
            return self.check(raw)
        known = self.spec["function"]["parameters"]["properties"]
        return {k: v for k, v in raw.items() if k in known and k != SUSPICION_ARG}

    def describe(self, args: dict[str, Any]) -> str:
        return self.action(args) if self.action is not None else f"'{self.title}'(으)로 더"

    # 이번 사건 의심 번호를 enum으로 붙인 도구 설명 — AI가 목록 밖 값을 고르기 어렵게
    # 그래도 목록 밖 값이 오면 graph가 실행하지 않는다 (OpenAI 도구 고르기는 strict가 아님)
    def case_spec(self, suspicion_ids: list[str]) -> dict[str, Any]:
        function = dict(self.spec["function"])
        parameters = dict(function["parameters"])
        properties = {
            **parameters.get("properties", {}),
            SUSPICION_ARG: {
                "type": "string",
                "description": SUSPICION_DESCRIPTION,
                "enum": [*suspicion_ids, CASE_WIDE],
            },
        }
        required = [r for r in parameters.get("required", []) if r != SUSPICION_ARG]
        function["parameters"] = {
            **parameters,
            "properties": properties,
            "required": [SUSPICION_ARG, *required],
        }
        return {**self.spec, "function": function}


def _with_suspicion(properties: dict[str, Any], required: list[str]) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            SUSPICION_ARG: {"type": "string", "description": SUSPICION_DESCRIPTION},
            **properties,
        },
        "required": [SUSPICION_ARG, *required],
    }


# 고객 이력 90일 (#581) — 기본 조회의 30일로 평소 결제를 알 수 없을 때만 넓혀 본다
# 기간은 AI가 정하지 않는다: 도구에 인자가 없고 90은 코드가 넣는다 (Spring도 30 · 90만 받음)
HISTORY_90_DAYS = 90


def _history_90(box: "ToolBox", args: dict[str, Any]) -> ToolResult:
    return box.get_payment_history(HISTORY_90_DAYS)


# AI가 지어낸 인자(기간 · 사건 번호)는 버린다
def _no_args(raw: dict[str, Any]) -> dict[str, Any] | None:
    return {}


HISTORY_90 = ExtraTool(
    spec={
        "type": "function",
        "function": {
            "name": "get_payment_history_90d",
            "description": (
                "같은 고객의 최근 90일 결제(이 사건 결제 제외, 최근 순 최대 20건)와 "
                "상태별 건수를 본다. "
                "기본 조회의 고객 최근 결제(30일)에 이번 결제보다 앞선 결제가 없거나 적어서 "
                "이 고객의 평소 결제를 알 수 없을 때만 부른다. "
                "30일 결제로 평소 결제를 볼 수 있으면 부르지 않는다."
            ),
            "parameters": _with_suspicion({}, []),
        },
    },
    title="고객 이력 90일 조회",
    run=_history_90,
    check=_no_args,
    action=lambda args: "고객 결제 이력을 90일로 넓혀",
)


# 연결 결제 시간대 넓혀 보기 (#817) — 기본 조회는 30일 전체와 사건 결제 앞뒤 1시간만 센다
# 허용 시간은 질문 기능(ADR-0086)과 같다
# Spring이 모르는 시간을 버리고 1시간 값을 주면 조회 실패로 본다
LINKED_HOURS = frozenset({6, 24})


class ToolMismatch(Exception):
    pass


def _linked_hours(raw: dict[str, Any]) -> dict[str, Any] | None:
    hours = raw.get("hours")
    if isinstance(hours, int) and not isinstance(hours, bool) and hours in LINKED_HOURS:
        return {"hours": hours}
    return None


def _linked_window(box: "ToolBox", args: dict[str, Any]) -> ToolResult:
    result = box.find_linked_payments(args["hours"])
    if result.nearbyWindowMinutes != args["hours"] * 60:
        raise ToolMismatch(
            f"요청 {args['hours']}시간, 응답 {result.nearbyWindowMinutes}분 — 다른 범위를 받음"
        )
    return result


LINKED_WINDOW = ExtraTool(
    spec={
        "type": "function",
        "function": {
            "name": "find_linked_payments_window",
            "description": (
                "같은 카드 · 기기 · IP를 쓴 다른 고객 결제 중 "
                "사건 결제 앞뒤 hours시간 안의 건수를 본다 (최근 30일 안, 건수만). "
                "기본 조회의 같은 카드 · 기기 · IP 결제는 30일 전체와 앞뒤 1시간만 세어 "
                "그 사이 시간대(몇 시간 · 하루)에 몰렸는지는 알 수 없다. "
                "같은 카드 · 기기 · IP 결제가 30일 안에 있는데 1시간 밖에 몰렸는지 "
                "알아야 할 때만 부른다. 30일 안에 그런 결제가 없으면 부르지 않는다. "
                "hours는 6(반나절) 또는 24(하루)."
            ),
            "parameters": _with_suspicion(
                {"hours": {"type": "integer", "enum": sorted(LINKED_HOURS)}}, ["hours"]
            ),
        },
    },
    title="같은 카드 · 기기 · IP 시간대 넓혀 보기",
    run=_linked_window,
    check=_linked_hours,
    action=lambda args: f"같은 카드 · 기기 · IP 결제를 사건 결제 앞뒤 {args['hours']}시간으로 넓혀",
)

# 같은 IP로 열린 지난 사건 (#821) — 카드 · 기기는 코드가 늘 보고(기본 조회), IP는 AI가 볼지 고른다
# 공용 와이파이(카페 · 회사)면 상관없는 고객 사건이 섞이므로 IP가 의심과 이어질 때만 본다
# 범위는 AI가 정하지 않는다: 도구에 인자가 없고 IP는 코드가 넣는다
# (Spring도 CARD_DEVICE · IP만 받음)
LINKED_CASES_BY_DEVICE = "CARD_DEVICE"
LINKED_CASES_BY_IP = "IP"


def _linked_cases_ip(box: "ToolBox", args: dict[str, Any]) -> ToolResult:
    result = box.find_linked_cases(LINKED_CASES_BY_IP)
    if result.by != LINKED_CASES_BY_IP:
        raise ToolMismatch(f"요청 IP, 응답 {result.by} — 다른 범위를 받음")
    return result


LINKED_CASES_IP = ExtraTool(
    spec={
        "type": "function",
        "function": {
            "name": "find_linked_cases_ip",
            "description": (
                "이 사건 결제와 같은 IP를 쓴 결제가 들어 있는 "
                "다른 고객의 지난 종료 사건(최근 90일)과 "
                "그 결제에 내린 직원 판정(매입 진행 · 승인 취소) 건수를 본다. "
                "같은 카드 · 기기로 열린 지난 사건은 기본 조회에 이미 있다. "
                "같은 IP는 카페 · 회사 같은 공용 인터넷일 수 있어 상관없는 사건이 섞인다. "
                "같은 IP를 쓴 다른 고객 결제가 있어 "
                "그 IP가 의심과 이어지는지 알아야 할 때만 부른다. "
                "같은 IP 결제가 없거나 IP 정보가 없으면 부르지 않는다."
            ),
            "parameters": _with_suspicion({}, []),
        },
    },
    title="같은 IP로 열린 지난 사건 조회",
    run=_linked_cases_ip,
    check=_no_args,
    action=lambda args: "같은 IP로 열린 지난 사건까지 넓혀",
)

EXTRA_TOOLS: tuple[ExtraTool, ...] = (HISTORY_90, LINKED_WINDOW, LINKED_CASES_IP)


# 실제로 한 조회 기록 — 보고서의 사실 값과 증거 추적은 이 기록에서만 만든다
# filled_by_system: 코드가 한 기본 조회면 True, AI가 고른 추가 조회면 False (#578)
@dataclass(frozen=True)
class ToolRecord:
    tool_name: str
    args: dict[str, Any]
    result: ToolResult
    filled_by_system: bool = False


# AI에게 돌려줄 도구 응답 — 카드 표시값(카드사 · 끝자리 숫자)은 뺀다 (#346 리뷰)
# 보고서를 채우는 Tool 기록(ToolRecord)에는 원래 응답을 그대로 둔다
# 연관 결제(#352) 응답은 처음부터 건수 · 판정만 있어 뺄 것이 없다
# 같은 카드 · 기기 · IP로 열린 지난 사건(#821)은 사건 번호를 뺀다
# 주문 정보(#634)는 주문번호 · 상품명을 뺀다 — 가맹점이 쓴 글자라 지시문이 섞일 수 있다
# (프롬프트 주입 차단)
# 같은 주문인지는 orderGroup 번호로 안다
def llm_content(result: ToolResult) -> str:
    if isinstance(result, FdsEvidenceTool):
        return result.model_dump_json(
            exclude={"payments": {"__all__": {"signals": {"cardDisplayLabel"}}}}
        )
    if isinstance(result, OrderContextTool):
        return result.model_dump_json(exclude={"payments": {"__all__": {"orderRef", "orderName"}}})
    if isinstance(result, LinkedCasesTool):
        # 지난 사건 번호는 화면 링크용 — AI에는 건수 · 판정 종류 · 시점만 준다 (#821, 11/14 결정)
        # 지난 결제별 판정 · 겹친 결제(pastPayments)도 의심 카드 표시용이라 AI 입력을 늘리지 않는다
        return result.model_dump_json(exclude={"cases": {"__all__": {"caseId", "pastPayments"}}})
    return result.model_dump_json()


# 한 Case로 묶인 도구 실행기 — 실제는 Spring, 테스트 · 샘플 실행은 고정 응답
class ToolBox(Protocol):
    def get_case_payments(self) -> CasePaymentsTool: ...

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool: ...

    def find_similar_cases(self) -> SimilarCasesTool: ...

    # lookback_days가 None이면 기본 조회(30일), 90이면 AI 추가 조사 (#581)
    def get_payment_history(self, lookback_days: int | None = None) -> PaymentHistoryTool: ...

    # nearby_hours가 None이면 Spring 기본(앞뒤 1시간, #637)
    # 6 · 24는 질문 · 답변의 넓혀 보기 (ADR-0086)
    def find_linked_payments(self, nearby_hours: int | None = None) -> LinkedPaymentsTool: ...

    # 사건 결제의 주문 정보 (#634) — 기본 조회
    def get_order_context(self) -> OrderContextTool: ...

    # 결제별 징후 · 정상 근거 (#618) — Spring이 계산한다. AI가 고르는 도구가 아니다
    def get_decision_basis(self) -> DecisionBasisTool: ...

    # 같은 카드 · 기기(CARD_DEVICE, 기본 조회) · IP(AI 추가 조회)로 열린 지난 사건 (#821)
    def find_linked_cases(self, by: str) -> LinkedCasesTool: ...


class SpringToolBox:
    def __init__(self, client: SpringToolClient, case_id: str) -> None:
        self._client = client
        self._case_id = case_id

    def get_case_payments(self) -> CasePaymentsTool:
        return self._client.get_case_payments(self._case_id)

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool:
        return self._client.get_fds_evidence(self._case_id, payment_ids)

    def find_similar_cases(self) -> SimilarCasesTool:
        return self._client.find_similar_cases(self._case_id)

    def get_payment_history(self, lookback_days: int | None = None) -> PaymentHistoryTool:
        return self._client.get_payment_history(self._case_id, lookback_days)

    def find_linked_payments(self, nearby_hours: int | None = None) -> LinkedPaymentsTool:
        return self._client.find_linked_payments(self._case_id, nearby_hours)

    def get_order_context(self) -> OrderContextTool:
        return self._client.get_order_context(self._case_id)

    def get_decision_basis(self) -> DecisionBasisTool:
        return self._client.get_decision_basis(self._case_id)

    def find_linked_cases(self, by: str) -> LinkedCasesTool:
        return self._client.find_linked_cases(self._case_id, by)


# 미리 받아 둔 응답으로 도구를 흉내 낸다 — payment_ids를 주면 Spring처럼 그 결제만 돌려준다
class StaticToolBox:
    # similar · history · linked · order를 주지 않으면
    # 비슷한 사건 · 이력 · 연관 결제 · 주문 정보가 없는 응답을 돌려준다
    # history90을 주지 않으면 30일 이력과 같은 결제를 90일로 돌려준다 (#581)
    def __init__(
        self,
        case_payments: CasePaymentsTool,
        fds_evidence: FdsEvidenceTool,
        similar: SimilarCasesTool | None = None,
        history: PaymentHistoryTool | None = None,
        linked: LinkedPaymentsTool | None = None,
        history90: PaymentHistoryTool | None = None,
        basis: DecisionBasisTool | None = None,
        order: OrderContextTool | None = None,
        linked_windows: dict[int, LinkedPaymentsTool] | None = None,
        linked_cases: dict[str, LinkedCasesTool] | None = None,
    ) -> None:
        self._case_payments = case_payments
        self._linked_cases = linked_cases or {}
        self._linked_windows = linked_windows or {}
        self._fds_evidence = fds_evidence
        self._basis = basis
        self._similar = similar or SimilarCasesTool(
            caseId=case_payments.caseId,
            evidenceVersion=case_payments.evidenceVersion,
            source="P3·P2",
            retrievedAt=case_payments.retrievedAt,
            cases=[],
            totalMatched=0,
        )
        self._history = history or PaymentHistoryTool(
            caseId=case_payments.caseId,
            evidenceVersion=case_payments.evidenceVersion,
            source="P5",
            retrievedAt=case_payments.retrievedAt,
            lookbackDays=30,
            payments=[],
            statusCounts=[],
            truncated=False,
        )
        # 90일 기본값 — 샘플에 30일보다 오래된 결제가 없으면 30일 결과와 같은 결제다 (#581)
        self._history90 = history90 or self._history.model_copy(
            update={"lookbackDays": HISTORY_90_DAYS}
        )
        # 연관 결제 기본값 — 카드 · 기기 · IP 모두 "정보 없음"
        not_provided = LinkedMatch(
            provided=False, otherCustomerCount=0, paymentCount=0, resultCounts=[], declinedCount=0
        )
        self._linked = linked or LinkedPaymentsTool(
            caseId=case_payments.caseId,
            evidenceVersion=case_payments.evidenceVersion,
            source="P2",
            retrievedAt=case_payments.retrievedAt,
            lookbackDays=30,
            card=not_provided,
            device=not_provided,
            ip=not_provided,
        )
        # 주문 정보 기본값 — 결제마다 "주문 정보 없음" (결제 준비 세션이 없는 결제와 같다, #634)
        self._order = order or OrderContextTool(
            caseId=case_payments.caseId,
            evidenceVersion=case_payments.evidenceVersion,
            source="P5",
            retrievedAt=case_payments.retrievedAt,
            payments=[
                OrderPayment(paymentId=p.paymentId, provided=False) for p in case_payments.payments
            ],
        )

    # 징후 · 정상 근거 기본값 — 결제별 근거가 없는 응답 (보고서는 한계 줄로 알림, #618)
    # Spring이 계산하므로 여기서 계산하지 않는다. 샘플은 decisionBasis 키로 준다
    def _default_basis(self) -> DecisionBasisTool:
        return DecisionBasisTool(
            caseId=self._case_payments.caseId,
            evidenceVersion=self._case_payments.evidenceVersion,
            source="P4",
            retrievedAt=self._case_payments.retrievedAt,
            payments=[],
        )

    # 샘플 JSON 하나로 만든다 — similar · history · linked 키가 없으면 위의 "없음" 응답 (측정 샘플)
    # history90은 AI가 90일 도구를 골랐을 때 돌려줄 응답 (#581)
    @classmethod
    def from_sample(cls, data: dict[str, Any]) -> "StaticToolBox":
        return cls(
            CasePaymentsTool.model_validate(data["casePayments"]),
            FdsEvidenceTool.model_validate(data["fdsEvidence"]),
            similar=SimilarCasesTool.model_validate(data["similar"]) if "similar" in data else None,
            history=PaymentHistoryTool.model_validate(data["history"])
            if "history" in data
            else None,
            linked=LinkedPaymentsTool.model_validate(data["linked"]) if "linked" in data else None,
            history90=PaymentHistoryTool.model_validate(data["history90"])
            if "history90" in data
            else None,
            basis=DecisionBasisTool.model_validate(data["decisionBasis"])
            if "decisionBasis" in data
            else None,
            order=OrderContextTool.model_validate(data["order"]) if "order" in data else None,
            linked_windows={
                int(hours): LinkedPaymentsTool.model_validate(value)
                for hours, value in data.get("linkedWindows", {}).items()
            },
            linked_cases={
                by: LinkedCasesTool.model_validate(value)
                for by, value in data.get("linkedCases", {}).items()
            },
        )

    def get_decision_basis(self) -> DecisionBasisTool:
        return self._basis or self._default_basis()

    # 시간 인자는 무시한다 — #751 전 Spring처럼 같은 응답을 준다
    # 샘플에 linkedWindows(시간 → 응답)가 있으면 그 시간은 그 응답을 준다 (#817)
    def find_linked_payments(self, nearby_hours: int | None = None) -> LinkedPaymentsTool:
        if nearby_hours is not None and nearby_hours in self._linked_windows:
            return self._linked_windows[nearby_hours]
        return self._linked

    def get_order_context(self) -> OrderContextTool:
        return self._order

    # 샘플에 linkedCases(범위 → 응답)가 없으면 지난 사건 없음 — 비교한 종류는 범위대로 (#821)
    def find_linked_cases(self, by: str) -> LinkedCasesTool:
        if by in self._linked_cases:
            return self._linked_cases[by]
        kinds: list[LinkedKind] = ["IP"] if by == LINKED_CASES_BY_IP else ["CARD", "DEVICE"]
        return LinkedCasesTool(
            caseId=self._case_payments.caseId,
            evidenceVersion=self._case_payments.evidenceVersion,
            source="P3·P2",
            retrievedAt=self._case_payments.retrievedAt,
            by="IP" if by == LINKED_CASES_BY_IP else "CARD_DEVICE",
            lookbackDays=90,
            kinds=kinds,
            notProvided=[],
            cases=[],
            totalMatched=0,
        )

    def find_similar_cases(self) -> SimilarCasesTool:
        return self._similar

    def get_payment_history(self, lookback_days: int | None = None) -> PaymentHistoryTool:
        return self._history90 if lookback_days == HISTORY_90_DAYS else self._history

    def get_case_payments(self) -> CasePaymentsTool:
        return self._case_payments

    def get_fds_evidence(self, payment_ids: list[str] | None) -> FdsEvidenceTool:
        if not payment_ids:
            return self._fds_evidence
        wanted = set(payment_ids)
        linked = {payment.paymentId for payment in self._case_payments.payments}
        if not wanted <= linked:
            # Spring Tool API의 400 INV_006과 같다
            raise ValueError("이 Case에 속한 결제가 아닙니다")
        return self._fds_evidence.model_copy(
            update={
                "payments": [p for p in self._fds_evidence.payments if p.paymentId in wanted],
                "missingPaymentIds": [
                    p for p in self._fds_evidence.missingPaymentIds if p in wanted
                ],
            }
        )
