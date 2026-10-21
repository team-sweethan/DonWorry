# 아키텍처 결정 기록 (ADR)

중대한 결정의 **이유 · 버린 대안 · 영향**을 남긴다. Notion은 합의 내용을, 여기는 "왜 그것을 골랐고 무엇을 버렸는지"를 담는다.

## 언제 쓰나

쓴다:

- 모듈 경계 · 의존 방향 변경
- 저장 방식 · 스키마 전략 변경
- 외부 계약(HTTP · 이벤트) 변경
- 기술 · 라이브러리 선택과 교체

안 쓴다:

- 변수명 · 파일 위치 같은 구현 선택
- Notion에 이미 적힌 결정을 그대로 반복하는 것

## 규칙

- 파일명 `NNNN-짧은-제목.md`, 번호는 4자리 연번
- 한 파일에 한 결정
- **채택된 ADR 본문은 고치지 않는다.** 뒤집을 때는 새 ADR을 쓰고 이전 것의 상태만 `대체됨 (→ NNNN)`으로 바꾼다
- 상태가 "제안"인 동안은 PR 리뷰에서 자유롭게 고친다. 팀이 합의하면 "채택"으로 바꾼다
- 템플릿: [`0000-template.md`](0000-template.md)

## 목록

| 번호 | 제목 | 상태 |
| --- | --- | --- |
| [0001](0001-module-dependency-direction.md) | 모듈 간 의존 방향 | 제안 — 팀 합의 필요 |
| [0002](0002-frontend-common-layout.md) | Ant Design 기반 프론트 공통 레이아웃 | 제안 — 구현 반영, 팀 디자인 승인 필요 |
| [0003](0003-payment-settlement-cancellation-contract.md) | P5–P6 취소 계약의 의존 방향과 원자성 | 제안 — P5 초안, 팀 합의 대기 |
| [0004](0004-payment-expiry-state.md) | 미승인 결제 만료 상태 분리 | 제안 — P5 초안, 팀 합의 대기 |
| [0005](0005-checkout-token-reissue.md) | checkout 토큰 재발급 | 제안 — P5 초안, 팀 합의 대기 |
| [0006](0006-fds-attempt-persistence.md) | FDS 실제 시도 기록의 독립 저장 | 제안 — P5 초안, 팀 합의 대기 |
| [0007](0007-review-payment-atomicity.md) | P3 결정과 P5 실행의 공동 커밋 | 제안 — P5 초안, 팀 합의 대기 |
| [0008](0008-capture-candidate-rescan.md) | 매입 후보의 실행별 재조회 | 제안 — P5 초안, 팀 합의 대기 |
| [0009](0009-ai-investigation-call-structure.md) | AI 조사 호출 구조 (Spring ↔ FastAPI) | 제안 — 팀 합의 필요 |
