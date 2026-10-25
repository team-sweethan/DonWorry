# 0030. CakeShop 주문별 결제 시도와 HTTP 조회

- **상태:** 제안 — #149 저장 구조 방향 사용자 승인, 저장 기반 구현, HTTP·P1 인증 연결 미구현
- **날짜:** 2026-10-25
- **관련:** [Issue #149](https://github.com/team-sweethan/DonWorry/issues/149), [ADR-0012](0012-payment-settlement-dependency.md), [ADR-0026](0026-payment-read-api.md), [ADR-0027](0027-payment-actions.md)
- **영향 받는 담당:** P5 · P1/CakeShop. 고객 참조 확인은 P2·P3에도 영향

## 배경

기준 dev 84698bb의 createAndAuthorize는 매번 새 결제와 평가 UUID를 만든다. payments에는 주문 중복 제약·상태 변경 시각·거절 코드가 없다. PaymentReader는 내부 Java 단건 조회이며 가맹점 인가를 하지 않는다. payment_actions는 직원 금융 조치만 다룬다.

P1은 같은 주문의 거절 이력을 보존하면서 재결제를 허용하고, 결과 미확인/PENDING/HELD 동안 주문 만료를 보류하는 방향에 동의했다. 재고 예약과 API Key 담당 수락, 최대 보류 정책 및 HTTP 세부 계약은 추가 확인이 필요하다.

## 결정 제안

HTTP 요청에는 가맹점 범위의 시도별 키를 사용한다. payment_orders 한 행으로 주문 입력·접수 기한·동시 요청을 관리하고, payment_requests에 각 시도와 payments·고정 평가 UUID·재개 단계를 보존한다. 결제 상태는 payments만 정본으로 두며 status_changed_at·decline_code를 추가한다.

같은 키 재전송은 기존 시도를 복원한다. 새 키는 최초 시도 또는 DENIED/DECLINED 후 기한 내에서만 허용한다. PENDING/HELD/CAPTURED 및 취소·만료 상태에서 새 시도를 임의로 허용하지 않는다. 기존 주문과 금액·고객·최초 생성 시각이 다르면 충돌이다.

P1 인증 공개 경계를 통해 얻은 가맹점으로 HTTP 접근 범위를 제한하고 내부 PaymentReader 계약은 유지한다. 상세 API·저장·오류·이관·복구 제안은 [#149 설계](../payment/149-cakeshop-payment-api.md)와 [ERD 제안](../erd/README.md#149-p5-cakeshop-결제-저장-제안)을 따른다. 사용자는 테이블 2개·payments 컬럼 2개 추가 방향을 승인했다. 상세 스키마의 migration·Entity·Repository를 작업 브랜치에 구현했다. 운영 DB 적용과 Notion 동기화는 미수행이다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 기존 createAndAuthorize를 그대로 POST에 연결 | 응답 유실·재전송 시 다른 paymentId와 평가가 생성됨 |
| payments에 UNIQUE(merchant_id, order_ref)만 추가 | 거절 이력 보존과 같은 주문 재결제를 동시에 지원하지 못함 |
| 요청 키만 저장하고 주문은 조회 후 INSERT | 서로 다른 키의 동시 요청에서 진행 중 결제 중복 생성 가능 |
| 기존 payment_actions에 최초 요청 저장 | 직원 판정의 decisionId·CAPTURE/VOID 계약과 최초 평가·승인을 혼합 |
| 메모리 잠금·캐시만 사용 | 다중 요청·재시작 후 동일 요청 확인 근거가 사라짐 |
| 모든 과거 결제에 새 시도와 변경 시각을 추정 백필 | 실제 키·주문 기한·변경 시각을 알 수 없어 거짓 이력이 됨 |
| 조회에서 평가·승인 자동 재실행 | 읽기 요청의 부작용과 중복 실행 위험. 재개는 같은 키 POST로 분리 |
| PENDING/HELD를 10분 뒤 실패 처리 | 이미 진행 중인 매입·승인과 충돌. 별도 종료/취소 계약 필요 |
| 금액·고객 변경을 같은 주문의 새 시도로 수용 | 이전 결제와 서로 다른 주문 내용 혼재. 이번 안에서는 새 주문으로 분리 |

## 영향

- API: CakeShop 요청·ID 조회·주문별 시도 조회 HTTP 신설 제안. P1 인증 API 이름·HTTP 헤더·오류는 미확정. 기존 P3 조회와 P6 이벤트는 유지.
- DB: P5 payment_orders·payment_requests와 payments 메타데이터 2컬럼의 migration·Entity·Repository 구현. P1·P2·P3·P6 테이블 변경 없음.
- 코드: HTTP 접수의 결제/키 원자 저장, 저장 평가로 재개, 승인·단계 원자 커밋을 위해 기존 승인 로직을 재사용 가능한 트랜잭션 내부 로직으로 분리할 필요가 있음.
- 화면: P1/CakeShop이 처리 중·보류·완료·거절 및 새 시도를 구분하고 서버 조회를 연결. 이번 P5 작업에 화면 수정 없음.
- 테스트: 주문/키 경합, 기한 경계·새 시도, 평가/승인 중단 복원, 가맹점 격리·기존 데이터·전체 회귀.
- 문서: #149 설계·ERD 제안·P5 README·ADR 목록. Notion 동기화와 최대 보류 정책은 미확정.

## 검증

저장 기반 검증은 [#149 구현·검사 기록](../payment/149-cakeshop-payment-api.md)에 기록한다. HTTP·접수 서비스의 동시성 검증은 후속이다. ADR 번호는 현재 로컬/원격 dev의 마지막 0029 다음으로 부여했으며 병합 전 다른 PR과의 번호 충돌을 다시 확인한다.


## 접수 구현 상세

접수 저장은 신규 주문까지 포함한 단일 REQUIRES_NEW 트랜잭션으로 구현한다. 주문 조회부터 FOR UPDATE를 사용하고 READ_COMMITTED에서 대기 후 키/최신 시도를 다시 확인한다. 기존 REPEATABLE_READ 스냅샷 및 JPA의 잠금 전 Entity 조회는 최신 시도/순번을 놓칠 수 있어 사용하지 않는다. 주문/키 UNIQUE 경합만 트랜잭션 종료 후 최대 2회 재조회하며 FK·다른 UNIQUE 오류는 숨기지 않는다. 내부 기존 생성 경로와 같은 주문의 동시 혼용은 지원하지 않으며 HTTP는 새 접수 서비스만 사용해야 한다. 상세 범위와 검사는 [#149 기록](../payment/149-cakeshop-payment-api.md)을 따른다.


## 평가·승인 재개 구현

접수 커밋 뒤 고정 UUID·저장값으로 P2를 호출하고 결과를 별도 커밋한다. 저장 평가를 승인에 사용하며 기존 승인 로직에 MANDATORY 경계를 추가해 결제 상태·이벤트 기록·요청 단계를 함께 커밋한다. 기존 내부 호출의 REQUIRES_NEW 경계는 유지한다. 단계 저장을 승인 이후 별도 트랜잭션으로 분리하는 대안은 승인 성공 후 중단 시 중복 실행 구간이 생겨 사용하지 않는다. ERROR의 자동 재평가와 GET의 처리 재개는 제공하지 않는다. DB·공개 이벤트 필드는 추가 변경하지 않는다.

실제 P2 동일 UUID 동시 호출에서 MariaDB 스냅샷 경합을 재현해, P5 호출 경계에 해당 일시적 DB 경합만 최대 2회 재호출하는 처리를 추가했다. UUID와 입력은 고정하며 일반 오류를 성공·거절로 변환하지 않는다. P2 모듈 코드나 계약은 변경하지 않는다.


## 가맹점별 조회 구현

단건은 paymentId와 인증된 가맹점 ID를 함께 조회 조건으로 사용한다. 주문별 응답의 최신 시도와 이력은 독립 읽기 전용 REPEATABLE_READ 트랜잭션으로 읽는다. 호출자 트랜잭션의 격리 수준에 참여하면 최신값과 목록이 다른 시점일 수 있어 선택하지 않았다. 이력은 순번 커서·기본 20/최대 100으로 제한하며 기존 PaymentReader와 스키마는 유지한다. P1 API Key 공개 경계가 없는 현재 코드에는 HTTP 엔드포인트를 추가하지 않는다.
