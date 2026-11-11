# 문서 위치

저장소 문서의 지도다. 작업별로 먼저 읽을 문서는 [AGENTS.md 1절](../AGENTS.md#1-정본-위치)에 있고, 이 문서는 그 표로 정할 수 없을 때 관련 정본을 찾는 데 쓴다.

- 1~2절은 `dev` `4a24d40a`(2026-11-09) 기준 현재 안내다.
- 3절 이후는 2026-10-22~23의 **과거 대조 기록**이다. 당시 상태를 보존하려고 남겨 두며, 현재 구현 여부를 판단하는 데 쓰지 않는다. 현재 구현은 코드 · migration SQL · 채택된 ADR로 확인한다 ([AGENTS.md 1절 "어긋날 때 기준"](../AGENTS.md#어긋날-때-기준)).

## 1. 무엇을 어디서 확인하나

| 내용 | 기준 |
| --- | --- |
| 작업 규칙 · 검사 명령 · 담당 | [AGENTS](../AGENTS.md), [규칙 지도](conventions/README.md), [코드 규칙](conventions/02-code.md) |
| 업무 흐름 · 상태값 · 외부 계약의 설계 의도 | 채택된 [ADR](decisions/README.md)과 모듈별 업무 문서(2절) |
| 모듈 의존 방향 · 이벤트 타입 위치 | [ADR-0012](decisions/0012-payment-settlement-dependency.md), 각 모듈 `package-info.java`의 `allowedDependencies`, [Modulith 04](modulith/04-dependency-and-events.md) |
| 실제 테이블 · 컬럼 · 타입 · 제약 | [migration SQL](../backend/src/main/resources/db/migration/), 소유 · 관계 요약은 [ERD](erd/README.md) |
| 프로젝트 소개 · 로컬 실행 · 기능 설정 | [프로젝트 README](../README.md) |
| 화면 경로 · 메뉴 · 프론트 인가 · CSRF | [프론트 README](../frontend/README.md#화면) |
| AI 조사 · FastAPI 연동 | [ADR-0009](decisions/0009-ai-investigation-call-structure.md), [AI 서버](../ai-server/) |
| 로컬 시연 (결제 → 매입 완료 · AI 보고서) | [로컬 시연 가이드](demo/local-demo.md) |
| CI · VM 이미지 · 배포 | [CI](ci/README.md), [VM 업데이트 CD](../ops/vm/README.md) |
| 1차 CakeShop 연동 | [cakeshop_refactoring](https://github.com/team-sweethan/cakeshop_refactoring) |

Notion 자료는 과거 원문 참고용이다. 합의는 저장소 문서에 기록한다 ([AGENTS.md 1절](../AGENTS.md#1-정본-위치)). Notion 자동 동기화 페이지는 [CI 07](ci/07-notion-sync.md).

## 2. 폴더

| 폴더 | 내용 |
| --- | --- |
| [`decisions/`](decisions/README.md) | ADR — 모듈 경계 · 의존 방향 · 저장 방식 · 외부 계약 · 기술 선택 |
| [`conventions/`](conventions/README.md) | 코드 · 이름 · 테스트 · 로그 규칙과 자동 검사 지도 |
| [`modulith/`](modulith/README.md) | 모듈 구조 · 의존 · 이벤트 |
| [`erd/`](erd/README.md) | 테이블 소유 · 관계 · 계약 요약 |
| [`flyway/`](flyway/README.md) | 마이그레이션 작업 순서 · 오류 해결 |
| [`ci/`](ci/README.md) | 워크플로별 검사 · 문제 해결 · Notion 동기화 · VM 릴리스 |
| [`identity/`](identity/README.md) | P1 로그인 · 계정 · 가맹점 · 이용 신청 · 내부자 접근 기록 설계 |
| [`investigation/`](investigation/README.md) | P3 Case · 판정 · 인계, P4 ML 그림자 채점 |
| [`payment/`](payment/README.md) | P5 결제 · 매입 · 취소 · 환불 · 주문자 연락처 |
| [`settlement/`](settlement/README.md) | P6 정산 · 지출 · 현금 흐름 · 화면 연결 |
| [`demo/`](demo/local-demo.md) | 로컬 시연 가이드 |

## 3. 과거 대조 기록 — #62 Notion 대조 (2026-10-22, `ba9427f`)

> 아래 3~6절은 당시 기록이다. "미구현"으로 적힌 Case · 경보 서버 연결, 서버 인증, AI 보고서 생성, P6 매입 · 정산 등은 이후 구현되었다. 현재 상태는 1~2절의 기준으로 확인한다.

사용자가 제공한 [Notion 자료 묶음](https://www.notion.so/40102d70288a8082b56ce24620a535ae)의 FDS 정본 · 기획 · 설계, P1~P6 ERD와 P6 상세 설계를 대조했다. 당시 확인 기준은 FDS [정본](https://www.notion.so/40102d70288a80bf8245c3fb8ddb231c) · [02 기획](https://www.notion.so/05002d70288a823f942c01fd7debd526) · [03 설계](https://www.notion.so/e6702d70288a82ef812101c4f8dd1e08), 담당 ERD [P1](https://www.notion.so/40102d70288a804cbd4fc895fec73d15) · [P2](https://www.notion.so/40102d70288a80919ec0c80d054770ed) · [P3](https://www.notion.so/40102d70288a80f2980ff3bbf7d968f5) · [P4](https://www.notion.so/40102d70288a8076ae4df729e7fed8b2) · [P5](https://www.notion.so/40102d70288a8068af50e5b30fc3efd2) · [P6](https://www.notion.so/40102d70288a80a7b5acd696c702596e), [P6 상세 설계](https://www.notion.so/40102d70288a805d8ae8cac21a7f133e)였다.

### 3.1 당시 확인된 범위

아래는 #62 시점(`ba9427f`)의 대조 기록이다. #104의 P5/P6 확인 범위는 6절을 따른다.

- 업무 테이블 14개와 공통 `EVENT_PUBLICATION`이 SQL에 있다. 초기 FDS Baseline의 8개 테이블 합계를 현재 전체 스키마 수로 사용하지 않는다.
- 내부 PK·참조 ID는 BIGINT, 금액은 원 단위 BIGINT, 업무 시각은 UTC DATETIME(6)이다. UUID 멱등 키와 업무 PK를 구분한다.
- P5는 결제 순수 규칙·테스트와 `payments` SQL까지 반영되어 있다. 보조 저장 3개와 모듈 DTO는 [미합의 제안](payment/README.md)이다.
- P6 담당 ERD의 1차 범위는 요청 이벤트 수신 후 즉시 매입, 수수료·정산 예정금 계산, `settlements.SCHEDULED` 저장이다. 별도 일일 매입 배치는 두지 않는다. 관련 업무 코드는 미구현이다.
- P6의 픽업 정보·픽업 완료 후 72시간 정산 완료·취소 연동은 2차 범위다. `order_fulfillments`와 정산 일정·완료 시각 컬럼은 현재 SQL에 없다.
- Case·경보 목업은 프론트 라우터에 연결되어 있다. 실제 서버 인증·업무 API·AI 보고서 생성은 미구현이다.
- 모듈 의존 방향은 #60에서 코드로 강제됐다. 기존 ADR의 도입 당시 검증 기록과 현재 구현 여부는 구분한다.

## 4. 과거 대조 기록 — 출처 간 차이와 담당 확인 사항 (2026-10-22)

| 항목 | 확인한 차이 | 영향·처리 |
| --- | --- | --- |
| P5/P6 의존 방향 | FDS 정본 §4·§5.6, 기획 §2.9, 설계 §3.10에는 `settlement → payment`와 P6의 P5 공개 API 접수 확인이 남아 있다. P6 상세 §1.3·§5와 ADR-0012는 `payment → settlement`·결과 이벤트다 | 저장소는 채택된 ADR-0012와 현재 코드에 맞춘다. P5/P6 및 FDS 문서 담당이 Notion의 이전 설명을 동기화해야 한다. 이벤트 이름·필드는 별도 합의 대상 |
| CAPTURE 영속 접수와 즉시 실행 | FDS는 영속 접수와 실행 결과를 분리해 Case를 종료한다. P6 담당 ERD는 PROCESSING 선행 커밋 없이 즉시 실행 후 저장한다고 명시한다 | 성공·실패 시 무엇을 접수 증거로 커밋하고 언제 접수 결과를 발행할지 P3/P5/P6 확인 필요. `CaptureCompleted`만으로 접수 프로토콜을 대체하거나 실행 실패를 접수 실패로 간주하지 않는다 |
| P6 1차/2차 범위 | P6 상세의 전체 흐름에는 픽업·정산 완료가 함께 나오지만 §2.2·§3.2·최종 요약과 담당 ERD는 이를 2차로 구분한다 | 현재 1차 범위는 매입·SCHEDULED까지로 표시한다. 2차 테이블·컬럼은 담당 ERD 합의와 새 migration 없이 추가하지 않는다 |
| 수수료 입력 | P6 ERD의 물리 타입 표에 `pg_fee_rate_bp` 변환 설명이 남아 있으나 P1 ERD와 SQL은 `pg_fee_rate DECIMAL(9,6)`이다. `settlement → identity` 의존은 [ADR-0038](decisions/0038-settlement-identity-dependency.md)로 허용됐다. #356에서 `TenantSummary.pgFeeRate`로 저장 비율을 공개한다 ([ADR-0048](decisions/0048-tenant-pg-fee-read-contract.md), `0.02 = 2%`) | P6 정산 계산 연결은 후속이다. 카드 수수료는 모의 정책 2%를 유지한다. 원 단위 계산·반올림은 P6 책임이며 P1 조회에서는 변환하지 않는다. Notion 최신 내용은 이번 작업에서 확인하지 않았다 |
| P1 권한 PK 표 | P1 ERD 본문은 `permission_id` 단일 PK를 명시하지만 아래 표에는 이전 복합 PK 표시가 남아 있다 | SQL은 `permission_id` PK와 `(user_id, permission_code)` UNIQUE다. P1이 Notion 표를 본문과 맞출 사항 |
| FDS 룰 근거·기존 컬럼 합계 | FDS 설계 §3.9는 룰 근거 저장을 추후 검토로 설명하고, 상위 안내는 초기 컬럼 합계를 포함한다. 담당 ERD·SQL에는 `fds_rule_hits`, 판정의 `evidence_version`, 인계의 `last_attempted_at`이 있다 | 현재 스키마 설명은 담당 ERD·SQL을 따른다. 초기 합계는 역사적 배경으로만 보존 |

추가 계약은 [P5 C1~C7](payment/README.md#구현-전에-필요한-최소-계약), AI의 P3/P4·Tool 계약은 [ADR-0009](decisions/0009-ai-investigation-call-structure.md)에 남아 있다.
미합의 ADR을 구현 사실만으로 `채택`으로 바꾸지 않는다. 현재 적용 여부는 [ADR 목록](decisions/README.md)에서 별도로 확인한다.

## 5. 과거 대조 기록 — 대조 범위의 한계 (2026-10-22)

- 제공된 Notion 자료와 현재 저장소를 대조했다. 다른 원본 페이지의 이후 수정, Notion 댓글 합의, CakeShop의 현재 코드·외부 API는 별도 확인이 필요하다.
- 문서 상대 링크 검사는 파일 경로만 검사한다. 외부 URL 접근·앵커·업무 내용의 일치를 자동 보장하지 않는다.
- 테스트 실행 기록은 해당 커밋·작업본에서의 결과다. 문서 정리를 전체 업무 시나리오 검증으로 해석하지 않는다.

## 6. 과거 기록 — #104 1차 P5/P6 축소 제안 (2026-10-23)

코드·SQL 기준은 `ae28a7c`다. P5 최초 저장·P2 평가 호출·응답 분기 판단은 있으나 최초 저장과 평가 연결은 별도 #100 작업이다. 이 기준의 P6는 패키지 골격이며 담당자가 전달한 매입·정산 동시 저장 구현은 별도 브랜치의 미검증 내용이다.

[Issue #104](https://github.com/team-sweethan/DonWorry/issues/104)에서 [ADR-0018](decisions/0018-payment-mvp-capture-completion.md)를 제안한다. 별도 접수 이벤트 없이 P6 완료 통지(paymentId·captureId·capturedAt)를 P5 CAPTURED 및 직원 ACCEPT 건의 P3 인계 완료 근거로 쓰는 안이다. 보조 저장·추가 작업 ID·시도별 UNKNOWN 프로토콜은 후속 검토로 분리한다.

4절의 기존 접수 정책과 달라지는 **제안**이며 아직 합의·구현된 대체 계약은 아니다. P3의 ID·시각 매핑, 완료 전 Case 종료 불가, 실패 시 인계·재시도 정책을 P3·P6와 확인해야 한다. Notion 원문과 채택된 ADR-0012는 변경하지 않았다. 최종 계약 채택 후 Notion 동기화는 별도 추적해야 한다.

#104 작성 중 `d9e4251`까지 dev를 반영했다. P3의 목 인계 서비스는 즉시 접수 응답과 수신 처리 시각을 사용한다. 완료 이벤트 기반 계약·capturedAt 매핑은 여전히 미구현이며 P3 변경이 필요하다. ADR 번호는 새 P2 ADR과 겹치지 않도록 0016을 사용한다.
