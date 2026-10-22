# P5 Payment — 1차 시연 문서

최초 작성: 2026-10-21 · 최신화: 2026-10-22 (#62) · 코드·SQL 대조 기준: `ba9427f` · 담당: P5 시은.
**W1 내부 규칙·단위 테스트와 `payments` 생성 SQL이 저장소에 있다. #71에서 Payment Entity·Repository와 DB 테스트를 추가했고, 현재 작업본에 최초 PENDING 저장 서비스를 추가했다. HTTP·모듈 연동은 미구현이다.**
과거 테스트 실행 근거와 미검증 범위는 [검증 문서](verification.md)에 구분해 기록한다.

## 문서와 근거

| 문서 | 읽는 목적 |
| --- | --- |
| [설계](design.md) | 상태·저장 필드/제약·모듈 DTO 초안·접수와 실행·실패 처리 |
| [검증](verification.md) | 불변식·시연·실패 테스트와 완료 기준 |

업무 설명은 Notion **fds 정본**을 바탕으로 한다. 2026-10-22 제공된 정본·P1~P6 ERD·P6 상세 설계를 대조했다.
현재 저장 구조는 migration SQL, 모듈 의존 방향은 채택된 ADR-0012를 따른다. 출처 링크와 남은 차이는 [문서 대조 현황](../README.md)에 모았다.

| 출처 | 적용 내용 |
| --- | --- |
| 01 업무분석 §1.1 | P5/P6 소유권, 판정·인계·실행의 분리 |
| 02 기획 §2.2~2.3, §2.9~2.14 | 결제 분기, 영속 접수, 1차 MVP와 시연 |
| 03 설계 §3.2~3.3, §3.8~3.11 | 상태값, 통신 방향, 최소 ERD, 예외 처리 |
| 04 DB 구현 가이드 Baseline v1(2026-10-21) | 모듈 내부 FK·모듈 간 논리 참조, 데이터 매핑 예시 |
| 사용자 후속 확인 | `auth_expires_at`·`rule_set_version`은 1차에서 사용하지 않음 |
| [AGENTS](../../AGENTS.md)·[ADR-0012](../decisions/0012-payment-settlement-dependency.md) | 현재 저장소 규칙·채택된 의존 방향. ADR-0001은 대체됨 |
| [ERD](../erd/README.md)·[payments 생성 SQL](../../backend/src/main/resources/db/migration/V20261022_145100__create_payments_table.sql) | 현재 업무 테이블 14개, P5 소유 `payments` 8개 컬럼·타입·제약 |
| [ADR-0009](../decisions/0009-ai-investigation-call-structure.md) | AI 조회 창구·재시도 상세 제안. 제안 상태이며 P3/P4 세부 계약 확인 필요 |

업무·상태·통신의 정본은 Notion 01~03이다. 04는 DB 구현 가이드이며 FK 선택 원칙에는 최신 합의를 적용한다.
초기 FDS Baseline의 **8개 테이블·57개 컬럼**은 과거 범위다. 현재 저장소에는 업무 테이블 **14개**와 공통 `EVENT_PUBLICATION`이 있다.
P5 보조 저장 3개는 현재 ERD·SQL에 없는 제안이며, 기존 테이블 수에 포함하지 않는다.
FDS 정본에는 이전 P6→P5 동기 접수 확인이 남아 있고, P6 상세 설계는 ADR-0012와 같은 P5→P6 의존을 명시한다. 저장소 문서는 채택된 방향으로 맞추고 Notion 동기화 필요 사항을 기록했다.

## 1차 시연 범위

P5의 목표는 **결제를 받아 FDS와 모의 승인을 거치고, 인계·실행 결과를 구분해 CakeShop에 정확한 상태를 제공하는 것**이다.

| 포함 | 담당 경계 |
| --- | --- |
| 최초 PENDING 저장, P2 평가 요청, ALLOW/REVIEW/DENY 및 ERROR 처리 | 점수·평가 근거 저장은 P2 |
| 모의 카드 승인, HELD·DENIED·DECLINED 처리 | 카드 한도 관리는 이번 최소 설계에서 추가하지 않음 |
| HELD 통지와 P5 결제·승인 정보 조회 | Case·병합·AI·직원 판정은 P3/P4 |
| 직원 조치의 멱등 접수, CAPTURE 이벤트와 P6 영속 접수 확인, VOID 영속 접수 | CAPTURE 접수·실제 실행은 P6 |
| VOID 실제 실행·기본 이력·실패 복구, P6 실행 결과 반영 | 매입·정산 테이블은 P6 소유 |
| CakeShop 상태 조회·결과 통지와 실패 재처리 | CakeShop 화면·주문 상태 변경은 해당 저장소 소유 |

부분 매입·부분 환불, 실제 카드사·외부 PG, 정산·수수료 계산, 판정 정정, 복잡한 카드 한도 원장,
별도 결제창 토큰 재발급 기능은 이번 P5 최소 범위에 넣지 않는다.
기본 인증·가맹점 격리·요청 중복 방지·전달 복구는 필요하다.
EXPIRED는 승인 만료라는 의미를 유지하되, 자동 만료 스케줄러·기한·자동 VOID는 확정하지 않는다.

## 최소 저장·DTO 초안의 검토 상태

**기존 SQL과 ADR-0012의 방향은 현재 기준이고, 보조 저장·DTO 세부는 제안이다.** 아래 내용을 필드 수준으로 검토한다.

- 현재 `payments` 8개 컬럼 유지, P5 보조 저장 3개 제안: `payment_requests`·`payment_operations`·`payment_histories`.
- P5 접수 ID, P6 최종 작업 ID, 접수 상태, 실제 실행 상태를 분리.
- P2 평가, P3 HELD/직원 조치/접수 결과, P6 CAPTURE/접수 확인/실행 결과의 필수·선택 DTO.
- 요청 키·본문 충돌, 접수 버전·실행 시도 순서, 재기동·응답 유실·반대 조치 제어 기준.

필드와 예외는 [설계 §2~3](design.md), 저장안의 대안·영향은 [ADR-0010](../decisions/0010-payment-operation-storage.md)을 검토한다.
ADR-0010은 제안이며 C1~C7의 합의를 대신하지 않는다. 특히 P6 접수 버전·작업 ID·실행 직전 확인, P3 결과 버전 저장,
모의 AUTH/VOID와 DB의 공동 커밋은 관련 담당자 확인이 필요하다. P6 실행 직전 검증에 필요한 값은 요청 이벤트·P6 소유 데이터로 확보해야 하며, P6가 P5를 역호출하는 초안은 ADR-0012에 따라 사용하지 않는다.

## 구현 순서와 완료 기준

W1 코드는 저장소에 반영되어 있고, W2~W6의 업무 흐름은 아직 완성되지 않았다. W2의 기반인 `payments` 생성 SQL은 #56에서 추가됐다. #71의 [Payment Entity](../../backend/src/main/java/com/donworry/payment/entity/Payment.java)는 최초 PENDING 생성과 기존 SQL 매핑까지 구현했다. [PaymentRepository](../../backend/src/main/java/com/donworry/payment/repository/PaymentRepository.java)는 저장·ID 조회만 제공하며 물리 삭제 메서드는 노출하지 않는다. 현재 작업본의 [PaymentCreationService](../../backend/src/main/java/com/donworry/payment/service/PaymentCreationService.java)는 내부 입력으로 최초 PENDING을 저장하고 ID를 반환한다. I/T 번호는 [검증 문서](verification.md)의 불변식/테스트다.

`PaymentCreationService.createPending(merchantId, customerRef, orderRef, amount)`는 Spring Bean을 통해 호출한다. 생성 시각은 서비스가 정하며, `REQUIRES_NEW`로 최초 저장을 별도 커밋하므로 호출자 롤백에도 PENDING은 남는다. 향후 평가 호출은 이 메서드 반환 후 연결한다. 이는 내부 저장 기반이며 가맹점 존재·권한·신뢰할 금액 검증이나 주문 중복 방지를 제공하는 외부 접수 API가 아니다. C1·C6 합의와 후속 구현은 여전히 필요하다.
공유 계약이 필요한 작업은 아래 C 항목의 해당 답변을 먼저 확보한다.

| 작업 | 선행 조건 | 완료 조건·검증 연결 |
| --- | --- | --- |
| W1 내부 분기·상태 규칙 | 정본 기준. DB 없이 검증 | 규칙 테스트 42건 및 기존 검사 5건의 과거 실행 기록은 검증 문서 참조. T01~T04·T05~T06의 분기 판단만 확인. 저장·이벤트·접수는 미검증 |
| W2 결제 저장·모의 승인 | W1, C1·C2·C6 | PENDING 선저장, 승인 식별자·결과 보존, 중복 승인 방지. I01~I04 / T01~T04·T08 DB 범위 |
| W3 HELD 이벤트·읽기 계약 | W2, C3·C5 | 커밋된 HELD만 통지, 중복·유실 복구 및 가맹점 격리 조회. I03·I09·I10 / T03·T09·T12 |
| W4 조치 접수·P6 인계 | W2, C3~C6 | P3 커밋과 분리된 멱등 접수, CAPTURE 접수 확인·조회·결과 통지. I04~I06·I09 / T05~T06·T08~T09 |
| W5 금융 실행 결과·경합 | W4, C4·C6 | VOID 성공 후 VOIDED, P6 성공 후 CAPTURED, 중복·경합 제어. I05~I08 / T07~T08·T10 |
| W6 CakeShop·전체 시연 | W3~W5, C7 | 통지 실패 복구, 실제 결과 표시, 혼합 판정 시연. I07~I10 / T07·T11~T12·시연 D1 |

## 구현 전에 필요한 최소 계약

업무 방향을 다시 묻는 목록이 아니라, 코드를 연결하는 데 필요한 세부 답변이다.

| ID | 담당에게 확인할 내용 | 막히는 작업 |
| --- | --- | --- |
| C1 | P1·CakeShop: 기존 DB의 ID(BIGINT)·금액(KRW BIGINT)·시각(UTC DATETIME(6))에 맞춘 외부 JSON 표현, 신뢰할 주문 금액·주체, 기본 인증, 주문별 중복 접수 기준은? 기존 테이블과 Flyway 전환은 반영됨 | W2 |
| C2 | P2: 평가 요청/응답, 타임아웃·ERROR 복구, 동일 요청과 재평가 구분, 임시 임계값을 이번 시연에서 사용할 것인가? | W2 |
| C3 | P3: HELD·조치·접수 결과의 필드와 키, 조회·권한, 판정 증명과 반대 조치 충돌 응답은? | W3·W4 |
| C4 | P6: `settlement.api`의 CAPTURE 요청·접수 결과·실행 결과 이벤트 필드와 식별자, 순서 역전 복구, ACCEPT 후 상태 및 VOID 경합 제어는? 즉시 매입·PROCESSING 선행 커밋 없음이라는 P6 ERD에서 접수 증거·실패 기록·접수 이벤트 발행 시점도 확인. P6가 P5를 호출하지 않는 방향은 확정 | W4·W5 |
| C5 | 공통 이벤트 담당 P2·P3·P6: 발행 기록·커밋 후 전달·재발행·소비자 중복 방지·고아 HELD 탐지는 어떻게 검증할 것인가? | W3·W4 |
| C6 | P5·P6·DB 담당: §2의 보조 3개 저장안·키 제약과 모의 AUTH/VOID 공동 커밋, 실행 불명 대조·원 작업 재시도를 채택할 것인가? | W2·W4·W5 |
| C7 | CakeShop·P1: 통지·조회 스키마, 인증·주문 상태 매핑, 중복·역순·실패 통지 복구는? | W6 |

승인 만료 컬럼 제외는 EXPIRED의 의미를 주문 만료로 바꾸지 않는다. 승인 만료 감지·자동 조치가 시연에 필요하면 별도 계약을 먼저 정한다.
상세 저장·전달 방식의 기술 선택은 아직 제안이며, 새 결정을 내릴 때 ADR에 대안·영향을 기록한다.
