# #188 미처리 결제 조회 HTTP 계약

- 상태: 3단계 HTTP·DTO·조회·분류 및 역할 검사 구현. 현재 feature 브랜치 작업 트리 기준이며 배포·병합 전이다. 검사 결과는 아래 §8에 기록한다.
- 최초 설계 기준: `0afaf11`, 2026-10-26. 최신 통합 검사 기준은 §9를 따른다. 관련 [Issue #188](https://github.com/team-sweethan/DonWorry/issues/188), [담당자 확인을 마친 조회 설계](152-payment-operations-design.md), [ADR-0035](../decisions/0035-long-held-payment-policy.md).
- 이번 문서의 세부 경로·필드 이름은 P5 구현 선택이다. 기존 담당자 확인을 이 세부 계약에 대한 추가 회신으로 표시하지 않는다.

## 1. 경로와 공개 조건

| 요청 | 역할 |
| --- | --- |
| `GET /api/payment-operations` | 미처리 후보 목록 |
| `GET /api/payment-operations/{paymentId}` | 완료 결제도 포함하는 결제 단건 상세 |

기존 CakeShop의 `/api/payments/{paymentId}`와 별개다. 기존 `payment.http.enabled` 설정과 `PaymentReader` 계약을 변경하지 않는다. 운영 조회 Controller는 P1 SecurityConfig의 두 GET 경로 검사와 같은 변경에서 연결한다. 인증된 SessionUser의 SECURITY_STAFF·ADMIN만 허용하고 OPERATOR는 기능 권한에 관계없이 거절한다. 기존 세션·계정 보안 검사도 유지한다.

전체 가맹점(정지 가맹점 포함)을 볼 수 있으며 `merchantId`는 검색 조건이다. 인증·인가가 입력 검증과 결제 존재 여부 조회보다 먼저 수행된다. 성공·실패 응답은 `Cache-Control: no-store`를 사용한다. 이 브랜치의 서버를 실행하면 호출할 수 있다. 기존 실행 중인 서버나 dev에 자동 반영되지는 않는다. GET과 같은 조회로 처리되는 HEAD에도 동일한 역할 검사를 적용한다.

## 2. 목록 입력과 페이지

`PaymentOperationsQueryRequest`에 아래 선택 입력을 바인딩하고 서비스에서 검증한다. 모든 조건은 AND이며, 미처리 후보 조건도 항상 적용한다.

| 파라미터 | 형식·기본값 | 검사·의미 |
| --- | --- | --- |
| `paymentId` | Long, 생략 가능 | 양수, 결제 ID 일치 |
| `merchantId` | Long, 생략 가능 | 양수, 가맹점 ID 일치 |
| `orderRef` | String, 생략 가능 | 양끝 공백 없는 1~100 Unicode 문자, 저장 주문 참조와 전체 값 비교. 부분 검색 아님 |
| `paymentStatus` | String, 생략 가능 | 현재 PaymentStatus 이름 중 하나, 대문자 |
| `category` | String, 생략 가능 | §5의 분류 이름 중 하나, 대문자 |
| `createdFrom` | ISO-8601 offset 시각 | 최초 결제 시각의 하한, 포함 |
| `createdTo` | ISO-8601 offset 시각 | 최초 결제 시각의 상한, 제외 |
| `page` | Integer, 기본 0 | 0 이상, `page * size`가 Integer.MAX_VALUE 이내 |
| `size` | Integer, 기본 20 | 1~100 |

시각은 `Z` 또는 offset이 있어야 하고 UTC Instant로 비교한다. 양쪽 기간이 있으면 `createdFrom < createdTo`여야 한다. 한쪽만 지정할 수 있다. 빈 값·알 수 없는 enum·숫자 범위 초과·중복 파라미터·지원하지 않는 파라미터는 400이다. 생략한 값에만 기본값을 적용한다. `sort`는 지원하지 않는다.

정렬은 `payments.createdAt ASC, payments.paymentId ASC`로 고정해 오래된 결제부터 보여준다. 같은 시각의 결제는 ID로 순서를 고정한다. 필터·분류와 전체 건수 계산은 페이지를 자르기 전에 같은 기준으로 수행한다. 한 페이지만 조회한 후 분류 필터로 일부 행을 버리지 않는다.

목록 후보는 `paymentStatus IN (PENDING, HELD) OR actionStatus IN (PENDING, FAILED)`다. 결제를 기준으로 요청·직원 조치를 LEFT JOIN하며 요청 없는 기존 경로도 포함한다. 완료 상태 필터를 넣어도 후보 범위가 확장되지는 않는다. 예를 들어 정상 완료 건은 ID를 지정해도 목록에서 빠지며 상세로 확인한다. 존재하지 않는 ID를 목록 필터로 주면 빈 목록이다.

페이지 응답은 `PaymentOperationsPageResponse`로 고정한다.

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `items` | List<PaymentOperationSummaryResponse> | 현재 페이지, 없으면 빈 배열 |
| `page`, `size` | int | 요청 페이지와 적용 크기 |
| `totalElements` | long | 후보·필터 적용 후 전체 건수 |
| `totalPages` | long | 전체 건수를 크기로 나눈 올림값, 빈 결과는 0 |
| `hasNext` | boolean | 다음 페이지 존재 여부 |
| `observedAt` | Instant | 응답 내 경과 시간 계산에 공통 사용한 관측 시각 |

범위를 넘은 페이지는 200·빈 items·실제 전체 건수·hasNext=false다. 한 요청의 목록·count·연관 데이터는 같은 P5 읽기 트랜잭션 스냅샷에서 조회한다. 페이지 사이에 데이터가 변경되면 누락·중복이 생길 수 있으며 여러 요청에 걸친 스냅샷은 보장하지 않는다. 재조회는 0페이지부터 시작한다. 조회 시각은 DB 커밋 시각이나 타 모듈 동기화 완료 시각이 아니다.

## 3. 목록 행 DTO

`PaymentOperationSummaryResponse`의 필드는 다음과 같다. `?`는 JSON null 허용을 뜻한다. ID·금액은 Long, 시각은 UTC ISO-8601 문자열, enum은 이름 문자열로 직렬화한다.

| 필드 | 타입·의미 |
| --- | --- |
| `paymentId`, `merchantId`, `amount` | Long, 저장된 식별자·원 단위 금액 |
| `orderRef`, `paymentStatus` | String, 주문 참조·저장 상태 |
| `createdAt` | Instant, 최초 결제 시각 |
| `actionType`, `actionStatus` | String?, 직원 조치가 없으면 둘 다 null |
| `observation` | PaymentOperationObservationResponse, §5 |

요청·평가·판정·멱등키 등 내부 확인용 식별자는 상세에만 둔다. 목록의 `observation`에도 이 키나 승인·고객 참조 원문을 넣지 않는다.

## 4. 상세 DTO와 기록 부재

`PaymentOperationDetailResponse`는 `payment`, `request`, `evaluation`, `action`, `observation` 다섯 객체를 가진다. 객체 자체는 null로 생략하지 않는다. 없는 영역도 recordStatus를 제공한다.

| 영역 / DTO | 필드 |
| --- | --- |
| `payment` / PaymentOperationPaymentResponse | `paymentId`, `merchantId`, `orderRef`, `amount`, `paymentStatus`, `createdAt`, `statusChangedAt?`, `hasAuthorization`, `declineCode?` |
| `request` / PaymentOperationRequestResponse | `recordStatus`, `requestId?`, `attemptNo?`, `requestIdempotencyKey?`, `processingStage?` |
| `evaluation` / PaymentOperationEvaluationResponse | `recordStatus`, `evaluationRequestId?`, `evaluationId?`, `evaluationResult?`, `riskScore?`, `errorCode?`, `evaluatedAt?` |
| `action` / PaymentOperationActionResponse | `recordStatus`, `actionId?`, `decisionId?`, `actionType?`, `actionIdempotencyKey?`, `actionStatus?`, `errorCode?`, `requestedAt?`, `completedAt?`, `captureId?` |

키·평가 UUID는 UUID 문자열, attemptNo는 Long, riskScore는 Integer, hasAuthorization은 boolean이다. 나머지 타입은 §3과 같다. recordStatus는 다음처럼 사용한다.

- `PRESENT`: 대상 행 또는 P5 평가 스냅샷이 있다. 성공을 뜻하지 않는다.
- `ABSENT`: P5 대상 행이 없다. request/action에 사용하며, evaluation은 요청 행 자체가 없을 때 사용한다.
- `NOT_RECORDED`: 요청 행과 평가 UUID는 있지만 평가 결과가 P5에 저장되지 않았다. P2 미실행·실패를 의미하지 않는다.

request가 ABSENT면 해당 데이터 필드는 모두 null이며 evaluation도 ABSENT와 null이다. 평가 결과 미저장 상태에서도 evaluationRequestId는 반환한다. 부분적으로 저장된 평가나 모순은 확보된 값을 보존하고 UNKNOWN 분류와 근거로 표시한다. 저장된 ERROR는 PRESENT다. DB 예외를 위 상태로 변환하지 않는다.

hasAuthorization은 승인 참조 존재 여부만 제공한다. completedAt은 기존 payment_actions의 성공 또는 실패 확정 시각이며 P3 Case 종료 시각이 아니다. 자동 매입은 action이 없어도 정상일 수 있다. PENDING 조치의 completedAt=null은 미완료, VOID의 captureId=null은 대상 외다. 모든 null을 미수집으로 묶지 않는다.

## 5. 관측·시간 DTO

`PaymentOperationObservationResponse`를 목록과 상세에서 공유한다.

| 필드 | 타입·의미 |
| --- | --- |
| `category` | 아래 분류 문자열 |
| `reasons` | List<String>, 저장 근거에 따른 한국어 설명. 원문 예외·비밀값 제외 |
| `limitations` | List<String>, P3/P6 상태·미수집 항목 등 확인 불가 정보의 한국어 설명 |
| `elapsedSeconds` | Long?, 최초 createdAt부터 observedAt까지의 초, 소수점 버림 |
| `waitingBasis` | HELD_STATUS_CHANGED_AT / ACTION_REQUESTED_AT / UNKNOWN / NOT_APPLICABLE |
| `waitingSince` | Instant?, 저장된 대기 시작 기준 |
| `waitingSeconds` | Long?, waitingSince부터 observedAt까지의 초, 소수점 버림 |
| `observedAt` | Instant, 목록 응답의 모든 행·상세 내 공통 시각 |

| category | 조건·의미 |
| --- | --- |
| EVALUATION_PENDING | 미승인 PENDING + RECEIVED/EVALUATED, 평가·승인 확정 전 |
| EVALUATION_ERROR | 미승인 PENDING + NEEDS_REVIEW/저장 ERROR |
| AUTO_CAPTURE_PENDING | 승인된 PENDING, 직원 조치 없음 |
| DECISION_PENDING | HELD, 직원 조치 없음 |
| STAFF_CAPTURE_PENDING | HELD, CAPTURE/PENDING |
| VOID_FAILED | HELD, VOID/FAILED, VOID_DECLINED |
| FINANCIAL_COMPLETED | CAPTURED/VOIDED, 저장 조치와 모순 없음. P3 반영은 미확인 |
| PAYMENT_TERMINATED | DENIED/DECLINED/EXPIRED, 저장 정보와 모순 없음. 일반적으로 상세용 |
| UNKNOWN | 근거 부족·모순 또는 위 조건으로 설명 불가 |

분류는 업무 상태를 변경하지 않는다. 모순이 있으면 UNKNOWN을 우선한다. 예: 완료 결제와 PENDING/FAILED 조치, HELD와 SUCCEEDED 조치, ERROR와 승인 참조의 공존. 분류 CASE는 PaymentOperationsRepository에서 목록·건수·상세에 공유한다. 먼저 요청의 가맹점·단계·평가 스냅샷과 결제 조합, 이어서 조치의 결과 필드 모순을 검사하고, 위 표 순서로 정상 조합을 적용한다. 나머지는 UNKNOWN이다. AUTHORIZED의 ALLOW는 승인된 PENDING/CAPTURED 또는 미승인 DECLINED, DENY는 미승인 DENIED, REVIEW는 승인된 HELD/CAPTURED/VOIDED/EXPIRED 또는 미승인 DECLINED와 대조한다. 요청 행 부재만으로 UNKNOWN으로 만들지 않고, 승인·HELD·조치로 확인 가능한 분류는 반환한다.

DECISION_PENDING의 대기 기준은 HELD의 statusChangedAt, STAFF_CAPTURE_PENDING은 action.requestedAt이다. 기준 시각이 없으면 waitingBasis=UNKNOWN, 시각·초=null이다. 평가 대기·ERROR·자동 매입 대기도 정확한 시작 기록이 없어 UNKNOWN과 null이다. 종료 분류와 VOID_FAILED는 NOT_APPLICABLE과 null, 원인 불일치는 UNKNOWN과 null이다. 전체 경과 시간과 대기 시간을 혼동하지 않는다.

저장 시각이 observedAt보다 미래면 해당 경과 초는 null로 반환하고 limitations에 시각 불일치를 표시한다. 음수나 0으로 보정하지 않는다. 미수집 호출·응답·타임아웃·자동 매입 발행 시각을 createdAt으로 대신하지 않는다. 기한 초과 플래그나 자동 실패 판정은 없다.

## 6. 오류와 구현 경계

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | PAYMENT_OPERATIONS_INVALID_QUERY | 목록 입력 또는 상세 ID 오류 |
| 401 | AUTH_001 | 비로그인, P1 기존 응답 |
| 403 | AUTH_002 | 역할 불허, P1 기존 응답. 기타 기존 계정·세션 제한 코드는 유지 |
| 404 | PAYMENT_OPERATIONS_NOT_FOUND | 허용된 상세 조회에서 결제 없음 |
| 503 | PAYMENT_OPERATIONS_UNAVAILABLE | DB 조회 실패. 빈 결과로 바꾸지 않음 |
| 500 | PAYMENT_OPERATIONS_INTERNAL_ERROR | 그 밖의 예상하지 못한 실패 |

상세 ID는 Long 양수만 허용하며 상세 경로의 쿼리 파라미터는 허용하지 않는다. 오류 본문은 ProblemDetail + code다. 원본 예외·SQL·검색값을 본문에 반영하지 않는다. 운영 조회 전용 모듈 내부 예외 처리로 대응하며 담당 미정인 공통 예외 기반을 만들지 않는다. CakeShop의 “같은 요청 키로 확인” 복구 안내를 재사용하지 않는다.

DTO는 payment.dto, Controller는 payment.controller, 읽기 트랜잭션·관측 응답 구성은 payment.service, DB 조회와 동일 분류 CASE는 payment.repository에 둔다. DTO는 record로 만들고 필드 4개 이상이면 @Builder를 사용한다. 신규 테이블·이벤트 발행·상태 변경·P3/P6 호출은 없다. 후보 조회와 분류 필터가 같은 의미인지 구현 테스트로 검증한다.

## 7. 구현 검증 조건

- 익명 401, OPERATOR 403, STAFF/ADMIN 200, 다른 가맹점·정지 가맹점 조회, 없는 상세 404. 입력 오류보다 인증·인가가 먼저 적용된다.
- 정상 HELD·요청 없는 후보, 완료 상세, 분류 모순, 필터 적용 후 건수·페이지, 같은 시각의 ID 정렬, 빈 페이지.
- 시각 상·하한·offset·빈 값·범위 초과·중복·알 수 없는 입력, 기간 한쪽 지정, size=1/100과 잘못된 0/101.
- nullable 항목, ERROR/미저장/행 없음, 미래 시각, 원문 미노출, DB 실패 503, 조회 전후 쓰기·이벤트 없음.
- 검사 명령: `cd backend && ./gradlew spotlessApply check mariaDbTest`. 실행 결과와 미검증 범위는 §8을 따른다.

## 8. 구현과 검사 기록

- 초기 검사: `0afaf11` 위의 작업 트리. 이후 커밋과 최신 dev 반영 결과는 §9에 별도로 기록한다. 새 실행 코드의 병합·배포를 의미하지 않는다.
- 구현 파일: payment.controller의 PaymentOperationsController/ExceptionHandler, payment.dto의 조회 계약, payment.service의 PaymentOperationsQueryService, payment.repository의 조회·분류 CASE, identity.security.SecurityConfig의 GET/HEAD 역할 검사.
- 저장 구조·기존 PaymentReader·CakeShop 인증은 변경하지 않았다. P3/P6 내부 데이터와 이벤트 저장소를 조회하는 운영 코드는 없다.
- 새 기능 테스트는 입력·시각 단위 18건, 실제 세션·HTTP·MariaDB 25건이다. 권한, 분류 20개 조합, 필터·페이지·완료 상세, 누락값·원문 제외·DB 장애·업무 데이터/이벤트 불변을 확인한다.
- 전체 검사 중 테스트 연결 풀 누적에 의한 P6 동시성 검사 시간 초과를 발견해 이 클래스의 컨텍스트를 종료하도록 보완했다. 공유 DB에 남은 임시 관리자 계정이 시드 검사에 영향을 주는 문제도 테스트별 계정·세션·가맹점 정리로 수정했다. 업무 코드나 기존 검사의 검증 조건은 약화하지 않았다.
- 초기 구현 검사: `cd backend && ./gradlew spotlessApply check mariaDbTest` — spotless·check 통과. 기본 테스트 438건 중 437건 통과, 기존 로컬 시연용 LocalSettlementDemoTests 1건 건너뜀. MariaDB 277건 중 276건 통과·1건 실패. 새 조회 기능 43건(단위 18·실제 HTTP 25)은 모두 통과했다.
- 남은 실패: `LoginHistoryHttpTests.logout_whenConcurrentAcrossRoutes_recordsAtMostOnce`, 동시 로그아웃의 예상 204/401 대신 500. 로그에서 AuthService.logoutAll의 SessionUser=null 참조를 확인했다. 수정 전 `0afaf11`을 /tmp에 별도 추출한 뒤 `./gradlew mariaDbTest --tests '*LoginHistoryHttpTests.logout_whenConcurrentAcrossRoutes_recordsAtMostOnce'`로 동일 실패를 재현했다. dev나 P1 로그아웃 구현은 수정하지 않았다. 전체 MariaDB 통과·#188 전체 완료로 표시하지 않는다.
- 문서: `python3 .github/scripts/check_md_links.py`, `git diff --check`, 신규 파일 공백 검사 통과. 프론트 검사는 변경이 없어 실행하지 않았다.
- 미검증/범위 외: 프론트 화면, 운영 데이터의 대량 조회 성능, 배포 환경 접속, P2 재평가·P3 반영·P6 조회·운영 이벤트 복구. 시간 수치·운영 담당 합의는 기존 후속으로 남긴다.

## 9. PR 준비 단계의 최신 dev 통합 검사

- 최신 dev `595a44a`(#194 P3 인계·#197 P6 공개 조회)를 반영한 구현 커밋: `256808b1ab526b5031d20e49ff3f0edc9334b0ea`. 이후 변경은 이 검사 기록 문서뿐이다.
- `cd backend && ./gradlew spotlessApply check mariaDbTest`: spotless·check 통과(447건 중 446건 통과·기존 시연 테스트 1건 건너뜀), MariaDB 296건 중 293건 통과·3건 실패. 새 기능 43건은 모두 통과했다.
- 이번 실패: PlatformUserRepositoryTests의 관리자 시드 개수 검사 2건(기대 1·실제 23), CaptureReaderMariaDbTests의 완료 시각 검사 1건(기대 01:02:03.123456Z·실제 10:02:03.123456Z, 날짜 2026-10-26).
- 비교 검사: 변경 없는 dev `595a44a`를 임시 디렉터리에 추출해 `./gradlew mariaDbTest` 실행. 271건 중 270건 통과·1건 실패로, P6 완료 시각 불일치가 동일하게 재현됐다. 관리자 시드 개수 검사 2건은 이 비교 실행에서 통과했으므로 기존 문제라고 단정하지 않으며, 작업 브랜치 전체 검사에서의 실패 원인은 미확인이다.
- §8의 동시 로그아웃 검사는 이번 실행에서는 통과했다. 기존 커밋에서 재현했던 경합 문제를 수정한 것은 아니다.
- 전체 검사 통과 전 초안 PR로 제공하며 #188을 자동 종료하지 않는다. P1 시드·동시 로그아웃, P6 시각 검증 확인과 전체 재검증이 남는다.
