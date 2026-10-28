from typing import Literal

from pydantic import BaseModel

from app.schemas.case_context import CaseContext
from app.schemas.report import ReportBody

# Spring ↔ AI 서버 조사 계약 (ADR-0009 결정 1 · 2) — 필드 이름이 JSON 키 그대로라 camelCase를 쓴다


# Spring → AI 서버 조사 접수. 사건 개요의 사실 값은 DB가 정본이라 Spring이 함께 보낸다
class InvestigationRequest(BaseModel):
    reportId: str
    # 시도마다 새로 만드는 토큰 — Spring은 현재 토큰과 같은 결과만 반영한다
    attemptId: str
    caseId: str
    evidenceVersion: int
    reportVersion: int
    merchantId: str
    customerRef: str
    caseCreatedAt: str

    def to_context(self) -> CaseContext:
        return CaseContext(
            case_id=self.caseId,
            evidence_version=self.evidenceVersion,
            report_version=self.reportVersion,
            merchant_id=self.merchantId,
            customer_ref=self.customerRef,
            case_created_at=self.caseCreatedAt,
        )


# 진행 상태 조회 응답이자 결과 콜백 본문 — 콜백은 SUCCEEDED · FAILED일 때만 보낸다
# 실패면 reportBody 없이 error(code · reason)만 둔다. 자동 판정은 하지 않는다
class InvestigationResult(BaseModel):
    reportId: str
    attemptId: str
    status: Literal["RUNNING", "SUCCEEDED", "FAILED"]
    reportBody: ReportBody | None = None
    error: dict[str, str] | None = None
