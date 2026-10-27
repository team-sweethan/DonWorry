from pydantic import BaseModel


# 조사에 필요한 Case 정보 — 사건 개요의 사실 값은 DB가 정본이라 Spring이 넘겨준다 (#253)
# 지금은 로컬 실행 스크립트가 넘겨준다
class CaseContext(BaseModel):
    case_id: str
    evidence_version: int
    report_version: int
    merchant_id: str
    customer_ref: str
    case_created_at: str
