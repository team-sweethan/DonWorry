# P5 Payment — 1차 MVP 문서

최초 작성: 2026-10-21 · 최신화: 2026-10-26 · 구현 현황: #100·#118·#122·#136·#139·#141 · 문서 범위 정리: #104·#158 · 담당: P5 시은.

#100에서 최초 PENDING 저장·커밋 → 저장 데이터 기반 P2 평가 → 결제 분기 판단을 내부 서비스로 연결했다. 실제 P2·MariaDB로 ALLOW·REVIEW와 트랜잭션 분리를 검증했고, DENY·ERROR·호출 실패는 P2 대역으로 검증했다. #118의 4·5번 단계에서 승인 처리 서비스·모의 승인기·Entity 전이와 별도 createAndAuthorize 진입점을 연결했다. 기존 createAndEvaluate는 평가 전용으로 유지한다. 6번 단계에서 MariaDB 커밋·롤백·동시성 통합 검증을 수행했다. #122에서 ALLOW 승인 성공 후 매입 요청 발행 → P6 매입·정산 저장 → 완료 수신 후 CAPTURED 반영을 연결하고, 롤백·중복·동시 수신·미완료 기록 재전달을 검증했다. HTTP API는 후속 범위다.

**현재 구현과 1차 연동 제안을 구분한다. P3·P6 합의가 필요한 항목은 확정된 계약이나 구현 완료로 표시하지 않는다.**

검사별 실행 기준·결과와 미검증 범위는 [검증 문서](verification.md)에 기록한다.

## 문서와 근거

| 문서 | 목적 |
| --- | --- |
| [설계](design.md) | 결제 상태·현재 저장 구조·P2 계약·P6 완료 통지와 접수 확인 제안 |
| [#118 모의 승인 계약](mock-authorization.md) | 내부 입력·출력, 모의 승인기 구현과 기술 실패·재호출 기준. 생성·평가·승인 연결 구현, MariaDB 검증 범위는 검증 기록 참조 |
| [#118 상태 전이·트랜잭션](authorization-flow.md) | Entity 전이·승인 처리 서비스 구현. 생성·평가·승인 연결 구현, MariaDB 검증 범위는 검증 기록 참조 |
| [#122 매입 이벤트 연결](capture-events.md) | 발행·수신 조건과 트랜잭션 설계. 요청 발행·완료 수신 구현. 단계별 검증과 후속 범위 기록 |
| [검증](verification.md) | 1차 검증 계획과 과거 실행 기록 |
| [후속 검토](future-design.md) | 보조 저장·시도별 결과 관리 등 1차 필수에서 분리한 초안 |
| [#152 장기 보류 정책](152-long-held-payment-policy.md) | 제안 — 원인별 확인·종료·예약·경합·활성화 조건. 기간과 담당 합의 미정 |
| [#152 돈워리 내부 대응](152-internal-response.md) | D1~D6 운영 원칙·원인별 확인. 내부 조회 담당 확인 완료, 시간·운영 담당 및 구현 미완료 |
| [#152 조회 설계](152-payment-operations-design.md) | 목록·상세 정보·권한·P2/P3/P6 계약과 #188~#191 후속 범위. 설계 확인 완료·구현 전 |
| [#188 운영 조회 HTTP 계약](188-payment-operations-api.md) | GET 경로·DTO·필터·페이지·오류 규칙 및 #188 브랜치 구현·검사 기록. 병합·배포 전 |
| [#190 FDS 복구 저장·API 계약](190-fds-recovery-contract.md) | 단일 복구 테이블·명시적 1회 실행·응답/오류 계약. 설계안 사용자 승인, 코드·DB 미변경·통합 검증 전 |
| [#152 담당별 합의 기록](152-policy-confirmation.md) | P1·P2·P3·P5·P6 회신·사용자 확인 근거와 미정 범위. CakeShop 예약은 별도 |
| [ADR-0035](../decisions/0035-long-held-payment-policy.md) | 제안 — 확인 기한과 실제 금융 종료 분리. 대안·영향 기록 |
| [ADR-0027](../decisions/0027-payment-actions.md) | 현재 직원 조치 계약·#158 P3 확인과 CAPTURE 확정 실패 후속 협의 |
| [ADR-0018](../decisions/0018-payment-mvp-capture-completion.md) | ADR-0027로 대체된 이전 CAPTURE 제안의 이유·대안·영향 |

업무의 근거는 Notion FDS 정본과 P6 담당 ERD다. 실제 컬럼은 migration SQL, 의존 방향은 채택된 [ADR-0012](../decisions/0012-payment-settlement-dependency.md)를 따른다. 출처와 차이는 [문서 대조 현황](../README.md)에 기록한다.

## 현재 구현

**2026-10-26 P3 진행 보충:** 최신 GitHub `dev 2ba8a4f`에는 #170의 PaymentHeld 수신·Case 연결이 병합됐다. 아래 이전 단계 기록의 HELD 수신 미구현 표시는 당시 기준이다. 직원 금융 Gateway·PaymentActionResult 수신·인계 재확인은 P3 #161의 후속이다. [#152 내부 대응 기준](152-internal-response.md)에서 운영 분담 제안과 미확정 사항을 구분한다.

- #149는 [CakeShop 요청·조회 설계](149-cakeshop-payment-api.md)와 [ADR-0030](../decisions/0030-cakeshop-payment-attempts.md)을 작성한 단계다. 5번 단계에서 신규 저장 구조(migration·Entity·Repository)와 상태 변경 메타데이터를 구현했다. 후속 단계에서 접수 서비스의 동일 키 재사용·주문 잠금·거절 후 재결제·기존 주문 차단을 구현했다. 이어서 고정 평가 UUID로 P2 호출·결과 복원과 모의 승인/단계/이벤트의 원자 저장을 연결했다. 가맹점별 단건·주문별 시도 목록 조회도 추가했다. 사용자 승인으로 기본 비활성 HTTP와 인증 대역 테스트를 추가했다. 실제 P1 인증 연결·운영 활성화는 미완료다. 기존 내부 결제 처리·조회 구현과 구분한다.

- #141은 [직원 금융 조치 저장·처리 설계](141-payment-actions.md)와 [ADR-0027](../decisions/0027-payment-actions.md)에 따라 직원 CAPTURE·VOID 실행과 결과 저장·발행을 구현했다. 사용자의 저장소 ERD 기준 구현 승인 후 4번 단계에서 `payment_actions` migration·Entity·공개 계약·내부 요청 저장을 추가했다. 5번 단계에서 공개 실행 Bean·직원 CAPTURE 요청/완료·P3 결과 발행을 연결했다. 6번 단계에서 REJECT 모의 승인 취소·VOIDED·결과 발행도 연결했다. 7번 단계에서 요청 거절·P6 실패 복구를 보강하고 전체 검사를 통과했다. #158에서 P3가 완료 기반 인계·VOID actionId·시각 기준에 동의했다. P3 운영 수신과 Notion 동기화는 후속이며, CAPTURE 확정 실패는 P5·P6 후속 협의로 남긴다. 도입 시점은 미정이다.

- #139의 [결제 단건 조회 계약](../decisions/0026-payment-read-api.md)에 따라 `payment.api.PaymentReader`와 `PaymentSummary`를 정의했다. 4번 단계에서 읽기 전용 `PaymentReaderService`를 연결했다. 입력 검증·저장값 매핑·없는 결제·DB 오류 전파를 단위 검증했고, 5번 단계에서 실제 DB·읽기 전용 트랜잭션·세션 없는 호출·상태 커밋 후 조회를 검증했다. P3 목업 교체·권한 검증·화면 연결은 후속이다.

- #136의 [HELD 이벤트 계약](../decisions/0023-payment-held-event-contract.md)은 P3와 필드·필수 조건·시각 기준을 합의했다. 3번 단계에서 `payment.api.PaymentHeld` 타입을 추가했고 4번 단계에서 NOTIFY_HELD 분기의 발행을 연결했다. 5번 단계에서 실제 MariaDB·Modulith와 테스트 수신자로 커밋·롤백·재전달·동시 승인 통합 검증을 통과했다. P3 운영 수신·Case 연결은 아직 미구현·미검증이다.

- 결제 순수 규칙, `payments` Entity·Repository·최초 PENDING 저장 서비스가 있다.
- #91/#92의 P2 평가 호출과 #94/#95의 응답 검증·분기 판단이 반영됐다. `createAndEvaluate()`는 평가 전용이며 `createAndAuthorize()`는 모의 승인과 결제 상태·승인 참조 저장까지 수행한다.
- `PaymentCreationService.createPending()`은 `REQUIRES_NEW`로 최초 저장을 별도 커밋하고 ID를 반환한다. 인증·가맹점 존재·주문 금액 신뢰·주문 중복 방지를 제공하는 외부 API는 아니다.
- #100에서 최초 저장과 평가를 연결했고, #118에서 별도 승인 트랜잭션과 같은 결제의 반복·동시 승인 차단을 추가했다. 주문 단위 중복 접수는 후속 범위다.
- P6 #111에는 매입·정산 동시 저장과 CaptureRequested 수신·CaptureCompleted 발행이 구현되어 있다. #122에서 P5의 ALLOW 요청 발행·완료 수신·CAPTURED 반영을 연결했다. REVIEW는 HELD를 유지하며, #141의 5번 단계에서 직원 ACCEPT 매입·P3 결과 발행을 연결했다. P3 운영 수신·인계/Case 반영은 별도 후속이다.

과거 테스트 결과는 [검증 기록](verification.md)에 실행 기준과 함께 보존한다. 현재 브랜치에서 재실행한 결과로 해석하지 않는다.

## 1차 시연 범위

P5는 최초 결제 저장 → FDS 평가 → 모의 승인 → 보류 또는 매입 요청 → 실제 결과 반영 → CakeShop 통지를 연결한다.

| 포함할 업무 | 담당 경계 |
| --- | --- |
| PENDING 저장, ALLOW/REVIEW/DENY와 ERROR 처리 | 평가·점수는 P2 |
| 모의 승인, HELD·DENIED·DECLINED 처리, 승인 취소 | P5 |
| HELD 통지, 직원 ACCEPT/REJECT 처리, 인계 결과 통지 | Case·판정·인계 저장은 P3 |
| CAPTURE 요청, P6 완료 통지 후 CAPTURED 반영 | 매입·수수료·정산 예정금 저장은 P6 |
| 결제 조회·결과 통지, 중복 방지·실패 복구 | CakeShop 주문·화면은 별도 저장소 |

이번 축소안은 **P6의 별도 접수 단계 없이 매입 COMPLETED·정산 SCHEDULED 동시 저장 후 완료 통지를 사용하는 것**이다. P6 완료 필드 `paymentId`, `captureId`, `capturedAt`은 [ADR-0017](../decisions/0017-capture-completed-event-contract.md)에 채택되어 구현됐다. 직원 조치의 P5 결과 발행은 #141에서 구현했다. P3 수신·인계 반영은 후속이며 구체 계약은 [직원 조치 설계](141-payment-actions.md)를 따른다.

실제 카드사·외부 PG, 부분 매입·환불, 픽업 이후 정산 완료, 판정 정정, 승인 만료 자동화는 이 범위에 넣지 않는다. `EXPIRED`는 승인 만료라는 의미를 유지한다.
보조 저장 3개·추가 작업 ID·접수 버전·외부 결과 불명 프로토콜은 [후속 검토](future-design.md)로 분리한다. 기본 멱등성·전달 복구·권한 검증까지 제외하는 것은 아니다.

## 구현 순서와 완료 기준

| 작업 | 다음 구현·검증 |
| --- | --- |
| W1 내부 규칙 | 구현된 판단 규칙과 실제 저장·호출을 구분. 과거 실행 기록 보존 |
| W2 저장·평가·모의 승인 | #118에서 상태·참조 저장 및 동일 결제 동시 승인 차단 검증. 주문 중복 방지·재시작 복구는 후속 |
| W3 HELD·읽기 | 커밋 후 통지, 중복 Case 방지, 가맹점 범위 조회 |
| W4 조치·P6 인계 | ALLOW 자동 매입은 #122에서 연결·검증. 직원 CAPTURE·VOID는 #141 5·6번 단계에서 연결. P3 운영 인계는 후속 |
| W5 실제 결과·복구 | CAPTURED 중복·경합·명시적 재전달은 #122에서 검증. VOIDED·결과 재전달은 #141 6번 단계 검증 범위. 운영 자동 복구는 후속 |
| W6 CakeShop·시연 | 외부 통지 실패 복구와 전체 흐름 검증 |

## 구현 전에 필요한 최소 계약

| ID | 담당·확인할 내용 |
| --- | --- |
| C1 | P1·CakeShop: 인증, 가맹점·금액 검증, 주문 중복 접수 기준과 외부 표현 |
| C2 | P2: 현재 공개 DTO 유지. ERROR·타임아웃 복구, 동일 UUID 재전송과 재평가 구분 |
| C3 | P3: #158에서 완료 기반 인계·CAPTURE targetOperationId=captureId·VOID=actionId 확인. acceptedAt은 P3 반영 시각, completedAt은 이력에 보존. Gateway·리스너·Case 연결 구현·검증은 별도 |
| C4 | P6: ADR-0016·0017의 요청·완료 타입을 #122에서 P5에 연결. 수수료 계산은 P6 소유. 미완료 기록 재전달 검증과 별개로 운영 복구 정책·P3 매핑은 후속 확인 |
| C5 | 공통 이벤트 담당: 발행 기록·커밋 후 전달·재발행·이벤트 식별·소비자 멱등성·유실 복구 |
| C6 | P5는 #141 payment_actions 한 테이블로 요청·결과와 반대 조치 차단을 구현. P3는 #158의 정상 대기 REQUESTED·확정 거절 FAILED·같은 키 재확인·완료 상태 보존 방침을 연결. CAPTURE 확정 실패 계약·운영 복구 정책은 P5·P6 후속 협의, 도입 시점 미정 |
| C7 | CakeShop·P1: 조회·통지 스키마와 인증, 중복·역순·실패 통지 복구 |

#141은 CAPTURE·VOID 모두 실제 성공 확인 뒤 인계 ACCEPTED를 반영하는 기준이다. #158에서 P3 확인을 받았으며, P3 운영 수신·Case 종료는 별도 구현·검증 대상이다. 상세 계약·후속 협의 사항은 [ADR-0027](../decisions/0027-payment-actions.md)을 따른다. Notion 차이는 P3가 notion-sync Issue로 관리한다.
