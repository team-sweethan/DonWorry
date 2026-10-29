# #315 PR 4 — 메일 복구·개인정보 삭제·운영 진입 제어

기준: `dev` `897b572` (#342 병합), GitHub #315의 2026-10-29 남은 작업. 설계와 재검토 기록을 보존한다. 최신 구현·검증 상태는 맨 아래 11절을 따른다.
설계 브랜치: `feature/315-recovery-design`. 구현은 `feature/315-recovery`에서 진행한다.
[신청 설계](315-application-design.md)·[승인 설계](315-approval-design.md)·[ADR-0047](../decisions/0047-application-recovery-and-retention.md)을 함께 따른다.

## 1. 범위

1. 관리자 목록·상세의 메일 실패/불확실 표시와 수동 재발송.
2. 미확인 7일·반려 30일 신청 자동 삭제와 예약 해제.
3. 서버 기능 설정을 랜딩·로그인 신청 버튼과 공개 신청 경로에 반영.
4. #315 전체 브라우저 흐름·반응형·키보드·Mailpit 검증, Gmail 실제 수신 별도 기록.

새 테이블·컬럼·모듈 의존·다른 담당 Java 계약은 계획하지 않는다. 자동 재전송 큐·결제 연동 자동화·신청 수정·승인 취소는 범위 밖이다.

## 2. 현재 구현에서 주의할 점

- `ApplicationMailDispatchService`는 커밋 후 SMTP와 별도 결과 저장을 수행한다. 결과 저장 실패 시 응답은 UNKNOWN이지만 DB에 PENDING이 남을 수 있다.
- 관리자 상세에는 메일 이력이 없다. 승인/반려 응답의 발송 결과를 새로고침 후에도 조회할 수 있어야 한다.
- 확인·재발송은 신청 PK를 먼저 잠근다. 상태 인덱스에 먼저 쓰기 잠금을 걸었던 #335의 교착을 다시 만들지 않는다.
- 첫 설정은 가맹점 → 계정 → 신청 → 토큰 순서다. 관리자 초기화는 대상 계정 잠금 안에서 첫 설정 토큰을 폐기한다.
- `passwordChangeRequired=true`는 첫 설정과 관리자 초기화가 공유한다. 이 값만 보고 첫 설정 링크를 재발급하면 안 된다.
- 현재 enabled=false는 신규 접수뿐 아니라 이메일 확인·첫 설정·승인/반려도 막는다. PR4에서 이 의미를 유지한다.

## 3. 메일 표시와 관리자 화면

저장 상태와 표시 상태를 구분한다. `PENDING`이 `startedAt + 5분 <= now`이면 표시만 UNKNOWN으로 계산한다. 조회는 DB 상태를 바꾸지 않는다. 5분은 복구 허용 기준이며 실제 미수신이나 SMTP 종료의 증거가 아니다. 현재 연결/읽기/쓰기 제한 각각 3초도 전체 실행 시간 상한은 아니다.

| 표시 | 안내 |
| --- | --- |
| PENDING | 처리 중, 재발송 비활성 |
| SENT | SMTP 수락. 실제 수신은 수신함에서 확인 |
| FAILED | 발송 실패, 새 시도로 복구 |
| UNKNOWN | 결과 불확실, 중복 수신 가능성을 확인하고 복구 |

현재 신청 상태에 해당하는 종류의 최신 시도가 대표 메일이다. EMAIL_PENDING은 EMAIL_VERIFICATION, APPROVED는 APPROVAL, REJECTED는 REJECTION이다. REVIEW_PENDING은 확인 완료로 표시하며 과거 확인 메일 실패를 복구 대상으로 세지 않는다.
최신은 `deliveryId DESC`로 정한다. 같은 신청의 발급은 신청 PK 잠금 안에서 직렬화하므로 ID가 시도 순서를 나타낸다. startedAt/완료 시각은 표시·시간 제한에만 쓴다. 서버 시계가 뒤로 보정되어도 새 시도가 옛 시도보다 뒤로 밀리지 않게 한다.

- 목록에 대표 메일 상태와 `메일 확인 필요` 필터를 추가한다. 필터는 대표 시도의 FAILED/UNKNOWN/오래된 PENDING이다. SENT의 링크 만료는 상세의 링크 상태로 따로 표시한다.
- 기존 목록 기본 상태는 REVIEW_PENDING이므로 메일 필터를 켤 때 `status=ALL`, page=0을 URL에 명시한다. `mailAttention=true` 단독 URL도 전체 상태가 기본이다. 이후 EMAIL_PENDING/APPROVED/REJECTED를 선택하면 교집합으로 좁힐 수 있고, REVIEW_PENDING 선택 시 메일 필터를 해제한다. 검색·페이지 변경과 상세 복귀에서도 mailAttention을 보존한다.
- 상세는 신청·발급 결과와 메일 영역을 구분한다. 발송 이력은 20건 페이지로 조회한다.
- 확인창에 종류·신청 이메일·옛 링크 폐기·중복 수신 가능성을 표시한다. 이메일·아이디·반려 사유를 바꾸는 입력은 없다.
- 응답 유실/5xx 후 자동 재시도하지 않는다. 최신 상세와 이력을 다시 읽은 뒤에만 새 요청을 허용한다.
- 삭제된 상세는 404와 목록 복귀를 제공한다. 기존 검색·필터·페이지 URL을 유지한다.

메일 필터는 DB에서 대표 시도 조건을 적용한 뒤 페이지네이션한다. 결과 페이지를 메모리에서 거르지 않는다. 페이지별 신청 ID로 메일 요약을 묶어 조회해 N+1을 피한다.
대표 시도는 반드시 `(applicationId, kind)`로 범위를 한정한 MAX(deliveryId)다. 예전 실패 뒤 최신 SENT가 있으면 필터에서 제외한다. 목록/상세/이력의 조회 트랜잭션은 읽기 전용 REPEATABLE_READ로 통일하고 회차별 now도 고정해 items/count/대표 메일이 같은 스냅샷·5분 경계를 사용하게 한다. 쓰기는 아래 READ_COMMITTED를 사용한다. 이력은 다른 신청의 ID를 섞지 않고 신청 존재부터 확인하며 없는 신청은 빈 페이지 대신 404다.

## 4. HTTP 계약

| 메서드·경로 | 계약 |
| --- | --- |
| GET `/api/admin/merchant-applications` | 기존 조건 + `mailAttention=true`(생략/false는 기존 조회), item에 nullable latestMail |
| GET `/api/admin/merchant-applications/{id}` | 기존 상세 + latestMail/resendAllowed/resendBlockedReason/nextResendAt |
| GET `/api/admin/merchant-applications/{id}/mail-deliveries` | page=0, size=20(1~100). items/page/size/totalElements |
| POST `/api/admin/merchant-applications/{id}/mail-resends` | `{expectedDeliveryId}` → 200 `{deliveryId, kind, mailStatus}` |
| GET `/api/merchant-applications/config` | 비로그인 허용. 200 `{enabled: boolean}` |

latestMail/이력 item: deliveryId, kind, storedStatus, effectiveStatus, startedAt, completedAt, failureCode, 연결 토큰의 expiresAt/usedAt/revokedAt. 토큰 원문·해시·본문·SMTP 예외를 응답하지 않는다.
resendBlockedReason은 FEATURE_DISABLED/IN_PROGRESS/COOLDOWN/DAILY_LIMIT/STATE_NOT_ELIGIBLE/RETENTION_EXPIRED/SETUP_COMPLETED/SETUP_REVOKED/ACCOUNT_UNAVAILABLE/HISTORY_UNAVAILABLE 또는 null이다. 시간으로만 해소되는 제한의 가장 늦은 해제 시각을 nextResendAt으로 반환하고 그 외는 null이다. 시각은 기존 UTC 계약을 따르고 화면은 Asia/Seoul로 표시한다.

expectedDeliveryId는 화면에서 본 대표 메일 ID다. 현재 대표와 다르면 409로 거절한다. 오래된 화면의 이중 클릭·관리자 간 동시 복구·응답 유실 뒤 같은 요청 반복이 새 시도를 만들지 않게 한다. ID가 같아도 현재 상태·제한은 다시 검사한다.
종류는 서버가 신청 상태로 결정한다. REVIEW_PENDING은 재발송하지 않는다. 대표 이력이 없으면 HISTORY_UNAVAILABLE로 차단하며 임의 복원하지 않는다.

새 오류 제안: APPLICATION_020/409(최신 시도 변경 또는 재발송 불가), APPLICATION_021/429(관리자 재발송 제한, Retry-After 제공). 없는 신청은 기존 APPLICATION_009/404, 기능 off는 APPLICATION_008/503을 사용한다. 관리자 재발송 본문의 누락/형식 오류는 기존 ApplicationExceptionHandler 계약인 APPLICATION_016/400, 조회 파라미터 오류는 APPLICATION_001/400이다.
APPLICATION_021은 정적 오류 enum만으로 처리하지 않는다. 제한 예외에 retryAt을 담고 handler에서 `Retry-After=max(1, ceil(retryAt-now의 초))`와 ProblemDetail의 nextResendAt을 함께 반환한다. 본문·상세·헤더는 같은 제한 계산을 사용한다.
expectedDeliveryId는 양의 정수 필수다. 이력 페이지는 기존 목록과 같이 page>=0, size=1~100, page*size의 정수 범위를 검사한다. mailAttention은 true/false만 받는다. URL의 ALL은 화면 전용 값이며 API에는 status를 생략한다. 이력 반환 순서는 deliveryId DESC다.
SMTP 실패에도 신청·발급 결과는 유지하고 200의 mailStatus=FAILED/UNKNOWN으로 안내한다. 준비 저장 실패 시 SMTP를 호출하지 않는다.

ADMIN 인가와 쓰기 직전 수행자 계정·세션·강제 변경 검사를 유지한다. 새 재발송 POST는 전역 CSRF=false여도 검사한다. 신규 응답·오류는 no-store다. config의 GET/HEAD만 정확히 공개 허용하고 강제 변경 세션도 조회하게 한다. 관리자 경로를 공개 예외로 추가하지 않는다.

## 5. 종류별 재발송과 동시성

| 종류 | 조건·원자 저장 |
| --- | --- |
| 확인 | EMAIL_PENDING, createdAt+7일 > now. 종전 미사용 확인 토큰 폐기 + 새 24시간 토큰 + PENDING |
| 승인 | APPROVED, 발급 연결 일치, 가맹점/OPERATOR 계정 ACTIVE, passwordChangeRequired=true. 아래 이력 검사 + 종전 미사용 첫 설정 토큰 폐기 + 새 72시간 토큰 + PENDING |
| 반려 | REJECTED, reviewedAt+30일 > now. 저장된 사유로 새 PENDING |

승인 복구는 이 신청·계정의 첫 설정 사용 이력이 없어야 한다. 최신 첫 설정 토큰(`tokenId DESC`)이 미사용·미폐기여야 하며 만료만 된 것은 재발급 가능하다. 최신 토큰 폐기는 관리자 초기화 뒤일 수 있으므로 차단한다. 사용 이력·토큰 부재·연결 이상도 차단한다. 완료 계정은 기존 로그인/관리자 초기화 절차로 안내한다.
재발송의 옛 토큰 폐기와 새 토큰 생성은 같은 커밋이므로 옛 폐기 토큰을 보고 초기화로 오인하지 않는다. 승인 신청의 토큰 이력은 보존한다. 새 발송이 실패해도 옛 링크를 복구하지 않는다.

### 제한

- 확인 메일은 공개 경로와 이메일 제한을 공유한다: 1분 1회·24시간 재발송 5회, 최초 메일은 쿨다운에만 포함. 관리자용 진입점에서 IP 제한과 이메일 제한을 분리하고 공개 IP당 1시간 10회 계약은 유지한다.
- 이메일 제한은 기존처럼 별도 트랜잭션으로 신청 잠금 전에 소비한다. 이후 충돌해도 소비 횟수는 되돌리지 않는다.
- 관리자용 이메일 제한 메서드는 재발송 허용 여부와 retryAt을 반환하는 내부 결과를 사용한다. 기존 공개 메서드의 최초 메일 쿨다운 `false → 202` 계약을 관리자 응답에 그대로 적용하지 않는다. 관리자 쿨다운/횟수 초과는 APPLICATION_021이다. 공개 경로는 기존 일반 응답/오류 코드 계약을 유지한다. 조회용 제한 계산은 find로만 읽고 행 생성·횟수 소비·FOR UPDATE를 하지 않는다.
- 승인/반려는 신청·종류별 시작 후 1분 쿨다운, 24시간 재발송 5회다. 종류별 최초 시도는 하루 횟수에서 제외하고 쿨다운에는 포함한다. 신청 잠금 안에서 발송 이력으로 계산하고 PENDING까지 생성한다.
- 모든 종류에서 최신 시도가 5분 미만 PENDING이면 거절한다. SENT의 미수신/만료 복구도 같은 제한·확인창으로 허용한다. SMTP 실패·UNKNOWN도 횟수에 포함한다.

여기서 5분 PENDING 차단은 관리자 복구 POST에 적용한다. 공개 확인 메일 재발송의 기존 1분/24시간 계약은 바꾸지 않는다. 공개 재발송이 관리자 화면에서 본 시도를 갱신하면 expectedDeliveryId 검사로 관리자 요청을 거절한다.
승인/반려의 최초 시도는 해당 신청·종류의 MIN(deliveryId)이며 날짜별 최초가 아니다. 일일 횟수는 이 최초 ID를 제외하고 `startedAt > now-24시간`인 시도를 센다. 다섯 시도가 남아 있으면 가장 오래된 시도의 startedAt+24시간, 쿨다운은 가장 최근 시작 시각+1분, 관리자 PENDING은 최신 시도의 startedAt+5분을 각각 계산해 필요한 최댓값을 nextResendAt으로 준다. now와 같은 만료 경계는 허용한다.
이메일 확인은 별도 제한 트랜잭션이 충돌 시도까지 셀 수 있으므로 메일 이력으로 제한을 추정하지 않는다. 조회도 RESEND_EMAIL/INITIAL_EMAIL의 실제 제한 행을 읽어 동일 정책으로 계산한다. 주 상태/연결/보관 기한/기능 조건으로 차단된 경우 nextResendAt은 null이다.
관리자 확인 메일의 사전 조회·수행자 인가/활성 세션·기능·입력 검사 후 이메일 제한을 소비하고, 최종 잠금 안에서 다시 검사한다. 존재하지 않는 신청·다른 역할·알려진 오래된 expectedDeliveryId 요청으로 정상 신청의 제한을 소비하지 않는다. 사전 검사 후 경쟁으로 발생한 충돌의 제한 소비는 유지한다.

### 잠금·발송

승인 복구는 사전 조회로 잠금 대상을 찾고 **가맹점 잠금 → 수행자·대상 계정 잠금(기존 정렬) → 수행자 세션 확인 → READ_COMMITTED/REQUIRES_NEW → 관리자 재확인 → 신청 PK 잠금 → 계정·가맹점·토큰·대표 메일 재조회** 순서다. 사전 조회 상태를 최종 판단에 쓰지 않는다.
확인/반려는 수행자 계정 잠금·세션 확인 후 같은 트랜잭션과 신청 PK 잠금을 사용한다. 확인용 이메일 제한은 이 잠금들 전에 소비한다.
첫 설정·초기화·정지와 기존 잠금을 공유한다. 초기화가 먼저면 재발급을 거절하고, 재발급이 먼저면 이후 초기화가 새 링크까지 폐기한다.
토큰/PENDING 커밋 → 잠금 해제 → 기존 dispatch로 SMTP → 해당 deliveryId만 결과 저장한다. 지연된 과거 결과는 자신의 행만 바꾸며 토큰·계정·최신 시도에 영향을 주지 않는다. 옛 메일이 뒤늦게 도착할 수 있지만 옛 링크는 무효다. 정확히 한 번 전달을 보장한다고 표현하지 않는다.
준비 저장은 별도 Spring bean의 트랜잭션 메서드 또는 TransactionTemplate로 실행한다. 같은 bean의 내부 호출에 @Transactional만 붙여 경계를 기대하지 않는다. SMTP는 바깥 호출에서 커밋 성공 뒤에만 실행하며 관리자/신청/토큰 잠금이나 열린 DB 트랜잭션을 보유하지 않는다. 원문 토큰을 포함한 준비 객체는 기존 PreparedApplicationMail/ReviewMail처럼 toString을 마스킹한다.

### 결과 저장과 삭제 경합

현재 complete의 findById→엔티티 변경은 조회 이후 삭제될 때 flush에서 실패할 수 있다. PR4에서는 별도 트랜잭션의 단일 조건부 UPDATE로 바꾼다: `WHERE delivery_id=:id AND status='PENDING'`. 변경값은 SENT/FAILED/UNKNOWN과 고정 실패 코드·UTC 완료 시각뿐이다.
영향 1행이면 기록 완료, 0행이면 이미 삭제되었거나 결과가 확정된 시도로 정상 무시한다. 이미 확정된 결과를 다른 값으로 덮어쓰지 않는다. DB 예외 전체를 무시하는 방식은 금지하며 연결/저장 실패는 기존처럼 UNKNOWN으로 응답한다. UPDATE 후 행 재생성·신청 잠금의 역방향 획득은 하지 않는다.
삭제와 UPDATE 중 먼저 DB 잠금을 얻은 쪽이 끝난 후 다른 쪽이 진행한다. 조회에서 계산하는 오래된 PENDING→UNKNOWN은 DB 값을 바꾸지 않으므로 뒤늦은 정상 완료를 이 조건으로 기록할 수 있다. 관련 테스트의 persistence.complete spy도 변경된 반환 계약을 따른다.

## 6. 개인정보 자동 삭제

| 상태 | 삭제 경계 (UTC, 실행 시작 시각 고정) |
| --- | --- |
| EMAIL_PENDING | createdAt <= now-7일 |
| REJECTED | reviewedAt <= now-30일 |
| REVIEW_PENDING / APPROVED | 삭제하지 않음 |

자정이 아닌 경과 시간 기준이다. reviewedAt이 없는 비정상 반려 행은 생성일로 대체하지 않고 건수 경고·담당자 점검 대상으로 남긴다.

- identity/service에 ApplicationRetentionService와 예약 진입점을 둔다. 기존 scheduling.enabled를 따르며 최초 60초 뒤·이후 1시간 간격 실행. applications.enabled=false여도 삭제한다.
- 시작 시 최대 신청 ID를 고정하고 ID 커서로 후보 100건씩 잠금 없이 읽는다. 신청마다 별도 READ_COMMITTED/REQUIRES_NEW에서 PK 잠금 후 상태·기한을 재검사한다. 상태 인덱스 범위를 먼저 쓰기 잠금하지 않는다.
- 후보 조건은 `lastId < application_id <= upperId AND ((status='EMAIL_PENDING' AND created_at<=emailCutoff) OR (status='REJECTED' AND reviewed_at<=rejectionCutoff))`이고 application_id ASC/LIMIT 100이다. 미삭제 신청에 OFFSET을 쓰면 앞 행 삭제 후 뒤 행을 건너뛰므로 OFFSET은 사용하지 않는다.
- **발송 이력 → 토큰 → 신청**을 한 트랜잭션에서 삭제한다. 중간 실패는 해당 신청 전체 롤백이다. 가맹점·계정·금융/조사 기록은 삭제하지 않는다.
- 삭제 커밋과 함께 미확인 신청의 이메일·사업자번호·아이디 예약이 해제된다. 반려 예약은 이미 반려 시 해제되어 있다.
- 확인·재발송·삭제는 신청 PK 잠금으로 직렬화한다. 먼저 확인되어 REVIEW_PENDING이면 보존하고, 삭제가 먼저면 후속 요청은 기존 링크 오류/404다. 기한 지난 신청은 정리 대기 중에도 확인·재발송 불가다.
- SMTP 중 삭제될 수 있다. 삭제된 deliveryId의 늦은 결과 저장은 정상 무시 결과로 처리하고 이력을 재생성하지 않는다. 다른 결과 저장 오류는 기존 UNKNOWN 처리를 유지한다. 시작한 외부 SMTP의 취소는 보장하지 않는다.
- 기존 로그인 이력 정리처럼 회차 실행 예산은 5분이다. 실패/시간 초과는 건수·오류 유형만 기록하고 다음 회차에 재시도한다. 개인정보·토큰·본문·예외 원문은 로그로 남기지 않는다.
- 특정 신청의 삭제 실패는 해당 트랜잭션을 롤백하고 커서를 넘겨 나머지를 계속 처리한다. 같은 회차에서 실패 행을 즉시 반복하지 않는다. DB 연결 자체가 불가능하면 회차를 종료한다. 5분은 다음 신청 처리의 시작을 중단하는 기준이며 진행 중 DB 잠금을 강제 중단하는 보장이 아니다. 시간 예산으로 전체 ID 범위를 돌지 못하면 다음 회차는 마지막 처리 ID부터 이어가고, 고정 상한까지 순회한 뒤 처음으로 돌아가 실패 행을 다시 본다. 재기동 시 커서는 초기화되며 이 지연 가능성도 운영 기록에 남긴다.

삭제는 엔티티별 remove 대신 신청 ID로 자식 둘을 각각 bulk DELETE하고 마지막 신청을 DELETE한다. 같은 트랜잭션에서 전체 삭제가 성공한 경우만 deleted 건수를 올린다. bulk DML 이후 오래된 관리 엔티티를 다시 저장하지 않는다. SQL 시각은 Clock의 UTC 값을 마이크로초로 절삭한다. native/Jdbc 경로는 기존 LoginHistoryRetentionService처럼 JVM 기본 시간대가 재적용되지 않는 UTC DATETIME 바인딩을 사용한다.
커서/상한은 단일 실행 서비스의 메모리 상태다. 예산으로 나눈 다음 회차도 같은 upperId를 유지하고, 현재 회차 시작의 최신 cutoff로 후보를 다시 평가한다. upperId까지 끝나면 커서를 초기화해 다음 회차에 새 상한을 잡는다. 중첩 수동/예약 실행은 허용하지 않는다. 기존 스케줄링 스위치가 전체 예약 기능을 끄므로 테스트에서 서비스를 직접 호출해 삭제를 검증하고, 별도 컨텍스트 테스트로 스케줄링 on/off 등록을 확인한다.

기한 도달과 물리 삭제는 다르다. 정상 시 다음 시간별 회차에 삭제하며 중단·장애·시간 예산 초과로 지연될 수 있다. 운영 README에 간격, 스케줄러 활성화, 실패 로그, 재기동 후 정리 확인을 기록한다. 백업의 별도 보존/폐기까지 완료했다고 표현하지 않는다.
승인 신청의 가맹점 이용 종료 기준은 여전히 미정이다. INACTIVE를 이용 종료로 간주하지 않는다.

## 7. 서버 설정과 공개 화면

별도 VITE 플래그 없이 config GET이 기존 ApplicationSettings의 enabled를 읽는다. DB/SMTP 조회 없이 boolean만 반환하며 false도 200이다.

- 랜딩·로그인: 조회 중/실패/false는 신청 비활성. 실패는 일시 오류·재조회, false는 신청 준비 중 안내. 기존 로그인은 계속 제공한다.
- `/apply`, `/apply/verify`, `/apply/password`: 공통 가드가 직접 URL/새로고침에도 폼 노출 전 설정을 확인한다. false는 이용 불가·홈/로그인 이동, 오류는 재조회 제공.
- 토큰 경로 loader는 가드 대기 전에 fragment를 읽고 즉시 주소창에서 제거한다. 토큰은 메모리에서만 보관한다.
- 페이지 진입마다 설정을 다시 확인한다. 열린 폼에서 서버가 꺼지면 POST의 APPLICATION_008에 입력을 유지하며 안내한다.
- 관리자 목록·상세·메일 이력은 off에도 읽을 수 있다. 승인/반려/재발송 버튼은 off 사유를 표시하며 서버도 거절한다.

config는 publicApi로 요청해 로그인 만료 알림·업무 활동 연장을 발생시키지 않는다. 화면 가드가 바뀌어 오래된 true 응답이 늦게 도착해도 현재 경로의 상태를 덮지 않도록 요청 취소 또는 세대 번호를 사용한다. 관리자 화면도 같은 설정 조회를 사용한다.
공통 api/publicApi 클라이언트는 네트워크/5xx/일반 403을 재전송하지 않지만 **CSRF_001로 업무 처리 전에 거절된 요청만 새 CSRF로 한 번 재전송**한다. 이 기존 동작은 유지한다. 이를 포함해 POST가 무조건 한 번만 전송된다고 표현하지 않으며 CSRF 거절 시 업무 저장·제한 소비·SMTP가 없음을 검사한다.
기존 ApiError는 code/detail만 보존하므로 UI가 429 본문이나 Retry-After를 직접 읽는다고 가정하지 않는다. 관리자 재발송 409/429 뒤 상세를 재조회해 resendBlockedReason/nextResendAt을 갱신한다. 재조회가 실패하면 재발송 버튼을 잠그고 조회 재시도만 제공한다. 공유 클라이언트의 오류 모델은 이번 기능 때문에 확장하지 않는다.

신규 접수만 닫고 기존 확인/첫 설정을 계속 허용하는 설정 분리는 이번 범위가 아니다. 현재 서버 의미를 그대로 화면에 반영한다.

## 8. 구현 단위

1. ApplicationReviewController/Responses 확장, ApplicationMailRecoveryService와 공통 대표 메일·재발송 판정. 기존 승인 생성 메서드는 재호출하지 않는다.
2. ApplicationRetentionService/예약 진입점, 세 Repository의 후보·삭제 쿼리, 결과 저장의 삭제 경합 처리.
3. ApplicationSettings accessor, config controller/DTO, SecurityConfig의 정확한 config GET/HEAD·재발송 CSRF 규칙.
4. src/api/adminApplications.ts·merchantApplications.ts, 관리자 목록/상세 메일 영역, 공개 신청 가드·랜딩/로그인. HTTP는 src/api/에서만 호출한다.
5. 기존 HTTP/MariaDB·프론트 테스트 확장, identity README의 기준 커밋·검사·운영 절차·미검증 갱신.

기존 저장 구조를 사용한다. 성능 검증에서 인덱스가 필요하면 newMigration으로 새 migration을 만들고 ERD/설계를 함께 갱신한다. 설계 단계에서 임의 컬럼·테이블·migration을 만들지 않는다.
잠금 보장은 기존 단일 서버 메모리 세션 전제다. 다중 인스턴스의 계정 초기화/정지 경쟁 보장을 이 설계로 새로 확보했다고 표현하지 않는다.

## 9. 검증 계획 — 실행 전

**서버/MariaDB:** ADMIN·세션·정지·강제 변경·CSRF(false 포함)·no-store, PENDING 5분/횟수 1분·24시간 경계, 공개/관리자 확인 메일 제한 공유, 최신 시도 필터와 지연 콜백, 동시 재발송 1건·같은 ID 반복 409, SMTP/결과 저장 실패, 준비 실패 전체 롤백·SMTP 미호출을 확인한다.
승인 재발송 후 가맹점·계정·권한·승인 이력 건수 불변, 옛 링크 무효·새 링크 사용·만료 재발급, 완료/초기화/정지/연결 오류 차단과 재발송 vs 첫 설정·초기화·정지 경쟁을 확인한다.
삭제는 7일/30일 직전·정각·직후, reviewedAt 기준, 승인/검토 대기 보존, FK 순서·실패 롤백, 예약 해제 후 재신청/수동 생성, 다중 배치·재실행, 확인/재발송/늦은 결과 저장 경쟁, 기능 off에서도 정리·예약 off 시 미실행, UTC 경계를 검증한다.
config 비로그인·강제 변경 세션 조회와 prod 기본 false·HTTPS/SMTP 활성화 검사도 회귀 확인한다.
시계가 과거로 이동해도 높은 deliveryId/tokenId를 최신으로 선택하는지, 재발송 본문 오류 APPLICATION_016과 제한 응답의 Retry-After/nextResendAt 일치, 최초 메일 쿨다운의 공개 202·관리자 429 분리를 검사한다. 삭제 실패 행 뒤의 정상 행 처리와 예산 초과 뒤 커서 재개도 확인한다.

**프론트/브라우저:** config 로딩/오류/false/true의 모든 진입, fragment 즉시 제거, 자동 POST 재시도 없음, 메일 영역·확인창·차단 사유·응답 유실/404 복귀를 확인한다.
랜딩 → 선택 아이디 신청 → Mailpit 확인 → 이메일 확인 → 관리자 승인 → Mailpit 발급 → 첫 설정 → 로그인/업무 진입 전체를 실행한다. 반려/사유 메일/재신청, SMTP 중단/복구/재발송, 만료/사용/옛 링크 폐기, 초기화 후 재발급 차단을 포함한다.
390px 모바일·태블릿·1440px 데스크톱, 키보드만으로 입력/확인창/재발송/복귀, 오류·성공 포커스/라벨/가로 넘침을 점검한다.
기본 REVIEW_PENDING 목록에서 메일 필터를 켠 직후 전체 상태 실패가 보이는지, 필터 단독 URL·검색·페이지·상세 복귀에 같은 조건이 유지되는지 검증한다.

### 브라우저 없이 수행할 신규 검증

| 영역 | 필수 자동 검증과 관찰값 |
| --- | --- |
| 대표 메일 | 같은 신청의 다른 kind·과거 실패/최신 성공, 신청 간 ID 혼입 차단, 필터 items/count/page 일치, 최신 ID와 이력 정렬 |
| 제한 | 공개/관리자 동시 확인 재발송의 합산 상한, 공개 202/관리자 429, 최초 ID 제외와 날짜 변경, nextResendAt과 실제 허용 시점 일치, 사전 거절 시 제한 불변 |
| 원자성 | 토큰 저장/PENDING 저장/커밋 실패를 각각 주입해 구 토큰 상태 보존·새 행 0·SMTP 0. 성공은 잠금 해제·트랜잭션 종료 후 SMTP 호출 |
| 결과 저장 | 조회 직후 삭제의 경합 대신 조건부 UPDATE로 처리, 결과 중복 저장 0행·기존 값 보존, 오래된 PENDING 완료, 실제 DB 예외 UNKNOWN |
| 보관 | 서로 다른 신청이 같은 이메일인 경우 대상 ID만 삭제, 발송/토큰/신청 각 삭제 실패 전체 롤백, 100건 초과·중간 실패·커서 이어가기·재기동 모델, UTC와 Asia/Seoul JVM 비교 |
| 잠금 | 서로 다른 관리자 세션으로 재발송/재발송, 재발송/초기화, 재발송/첫 설정, 확인/삭제, 완료 저장/삭제를 양방향 선행 순서로 제어 |
| HTTP | CSRF 전역 on/off 양쪽·비로그인/직원/사장님/강제 변경/만료 세션, 누락/음수 ID·페이지 오버플로, config GET/HEAD·잘못된 메서드, no-store·오류 코드 |
| 프론트 | Node 테스트로 409/429 후 GET만 수행·실패 시 잠금, CSRF_001 1회 재전송·네트워크 자동 재전송 0, config의 오래된 응답 무시·publicApi 사용·URL 조건 유지 |
| SMTP | Mailpit 컨테이너에서 세 종류의 관리자 재발송 본문/대상/새 링크 수신, 구 링크 POST 거절·새 링크 POST 성공. 새 endpoint 호출부터 수신까지 연결해 sender 직접 호출만으로 복구 검증을 대신하지 않음 |

경쟁 테스트는 sleep만으로 순서를 기대하지 않고 latch/barrier로 두 선행 순서를 강제한다. 두 Future의 제한 시간 내 완료·예외 종류·DB 최종 행·토큰 사용 가능성·SMTP 호출 수를 모두 확인한다. 같은 관리자만 사용하면 수행자 계정 잠금이 경쟁을 가리므로 서로 다른 관리자를 사용하고, 필요한 경우 잠금 stripe도 달리한다. Docker 미사용/건너뜀은 MariaDB 통과로 기록하지 않는다.
이 표는 앞으로 구현할 PR4의 검증 기준이다. 현재 코드의 기존 테스트 통과를 이 표의 신규 동작 통과로 간주하지 않는다. 브라우저 검증 항목은 이번 재검토에서 실행하지 않고 최종 구현 후 별도로 남긴다.

구현 후 명령:

```bash
cd backend
./gradlew spotlessApply
./gradlew check mariaDbTest
```

```bash
cd frontend
npm run lint
npm run format:check
npm run build
npm test
```

최초 설계 작성에서는 애플리케이션 검사를 실행하지 않았다. 이후 2차 재검토의 기존 코드 회귀 결과는 아래에 별도로 기록한다. PR4 구현·브라우저·Gmail·운영 HTTPS·Notion 최신 대조는 미검증이다.
Gmail 계정·2단계 인증·앱 비밀번호 준비는 담당자 작업이며 비밀값은 저장소/이슈/PR/로그에 넣지 않는다. Mailpit과 실제 수신 결과를 분리해 기록한다.
마지막 구현 PR만 Closes #315를 쓴다. 이슈의 코드 완료 기준과 담당자 직접 작업을 각각 대조하며 미실행 검증·외부 준비를 완료로 체크하지 않는다.

## 10. 설계 재검토 기록

2026-10-29, 기준 코드 `897b572`와 이 설계 작업 트리를 대조했다. 실행 테스트 결과가 아니라 코드/설계 검토 결과다.

- P2: 기본 REVIEW_PENDING과 메일 필터를 동시에 적용하면 결과가 비는 경로를 발견했다. 메일 필터 활성화 시 전체 상태로 전환하고 URL 유지 계약을 추가했다.
- P2: 시각 역행 시 최신 시도/토큰이 바뀌는 문제를 발견했다. 발급 순서의 기준을 deliveryId/tokenId로 변경했다.
- P2: 관리자 입력 오류 코드가 기존 handler와 달랐고, 기존 공개 재발송의 false 결과와 정적 예외만으로 관리자 429/Retry-After를 만들 수 없었다. 기존 오류 코드를 유지하고 내부 제한 결과·예외/handler 계약을 구체화했다.
- 추가 보완: 삭제 실패에서 계속 처리하는 정책과 시간 예산 이후 커서 재개, 단일 서버 보장의 범위를 명시했다.

위 항목은 문서에 반영했다. 구현·동시성·DB/브라우저 검증은 아직 남아 있다.

### 2차 재검토 — 브라우저 제외

같은 기준 코드의 서비스·예외 handler·공통 API 클라이언트·관련 migration 5개·기존 HTTP/SMTP 테스트를 대조했다. 새 기능 구현 없이 설계와 검증 기준을 보완했다.

- P2: 결과 저장의 findById 후 엔티티 갱신은 삭제와 경합할 때 flush 실패가 가능했다. PENDING 조건부 UPDATE와 0행 정상 무시·실제 DB 장애 UNKNOWN을 분리했다.
- P2: 현재 ApiError가 nextResendAt/Retry-After를 보존하지 않아 제한 정보를 UI에 반영하는 경로가 빠져 있었다. 409/429 후 상세 재조회, 실패 시 다시 보내기 차단을 명시했다.
- P2: 공통 클라이언트는 CSRF_001만 한 번 재전송하므로 무조건 POST 재전송 금지라는 검증 문구가 실제 계약과 달랐다. CSRF 거절 전 업무 부작용 0을 보장하고 제한된 기존 재전송을 유지하도록 명확히 했다.
- 추가 보완: 대표 시도의 신청/종류 범위, 조회 스냅샷, 일일 최초 시도 정의, 조회용 실제 제한 행, 후보 SQL/UTC 바인딩, 삭제 커서 상태·실행 중첩 방지, 테스트의 별도 트랜잭션/관리자/latch 기준을 추가했다.

브라우저 검증은 요청에 따라 제외했다. 아래 회귀 실행 결과는 기존 코드의 현재 상태를 확인한 것이며 PR4 신규 API·삭제·재발송의 실행 근거가 아니다.

### 이번에 실행한 회귀 검사

2026-10-29, `feature/315-recovery-design`, HEAD `897b572` + 이 문서/ADR/README만 변경된 작업 트리에서 실행했다. 애플리케이션 코드는 변경하지 않았다.

| 명령 | 결과 |
| --- | --- |
| `cd backend && ./gradlew check mariaDbTest --console=plain` | BUILD SUCCESSFUL. 일반 659개 중 658 통과·기존 1개 skip, MariaDB 514개 모두 통과. 실패/오류 0. spotless 검사·모듈/계층·Flyway/JPA·기존 HTTP/동시성·Mailpit SMTP 포함 |
| `cd frontend && npm run lint && npm run format:check && npm run build && npm test` | 모두 종료 코드 0, 테스트 227개 통과. 기존 Fast Refresh lint 경고 1개·번들 크기 경고 유지 |
| `git diff --check` 및 변경 문서 4개의 상대 파일 링크/줄끝 공백 확인 | 통과, 상대 링크 80개 확인 |

기존 테스트가 전체 실행되었음을 XML 결과의 failures/errors/skipped와 종료 코드로 대조했다. 기존 Mailpit 테스트는 sender의 승인/반려 메일 수신도 확인하지만, 아직 없는 PR4 재발송 endpoint의 통합 검증은 아니다. 브라우저는 실행하지 않았고 실제 Gmail/운영 배포/Notion은 확인하지 않았다. ai-server는 변경·계약 영향이 없어 검사 범위에 포함하지 않았다.

## 11. PR4 구현 상태와 운영 절차

2026-10-29 `feature/315-recovery`에서 이 설계를 구현했다. 기존 migration·테이블·다른 담당 Java API는 변경하지 않았다. 브라우저 검증은 사용자 요청으로 이번 실행에서 제외했다.

### 구현

- ApplicationMailQueryService/RecoveryService: 대표 메일·발송 이력 페이지, FAILED/UNKNOWN/오래된 PENDING 필터, 최신 ID 검증·종류별 재발송·서버 제한. 첫 설정 완료·초기화·정지/연결 이상에서는 새 링크를 발급하지 않는다.
- ApplicationPersistenceService: PENDING 조건부 결과 UPDATE로 삭제/중복 완료 경합 처리.
- ApplicationRetentionService/Cleanup: 미확인 7일·반려 30일, PK 재확인·발송/토큰/신청 원자 삭제, 100건 페이지·실패 격리·메모리 커서·5분 실행 예산.
- config GET/HEAD와 공개 경로 가드·랜딩/로그인 신청 버튼. 관리자 목록 메일 필터와 상세 확인창·이력·차단 사유·재조회 흐름.
- read-only 조회는 REPEATABLE_READ, 준비 쓰기는 READ_COMMITTED/REQUIRES_NEW. SMTP는 준비 커밋과 잠금 해제 이후 호출한다.

### 운영

1. prod 기본 `DONWORRY_APPLICATIONS_ENABLED=false`를 유지하면 신규 접수·확인·첫 설정·관리자 승인/반려/재발송이 중지된다. 공개 설정 GET과 관리자 읽기는 계속 사용할 수 있다.
2. 운영 활성화는 기존 DONWORRY_PUBLIC_BASE_URL의 HTTPS 주소와 SMTP 발신 설정/인증/TLS 준비를 먼저 마친 후 enabled=true로 재기동한다. Gmail 비밀값은 환경변수로만 주입한다. 설정은 실행 중 변경되는 토글이 아니며 서버 재기동으로 적용한다.
3. 메일 복구는 관리자 신청 목록의 '메일 확인 필요'에서 신청을 열고 종류·이메일·현재 계정 상태·최근 시도를 확인한 뒤 수행한다. SMTP 수락은 도착 보장이 아니다. UNKNOWN은 중복 수신 가능성이 있다.
4. 재발송은 새 링크만 유효하며 실패해도 이전 링크를 되살리지 않는다. 설정 완료/초기화 뒤에는 기존 로그인 또는 관리자 비밀번호 초기화 절차를 따른다.
5. 삭제 스케줄러는 `DONWORRY_SCHEDULING_ENABLED=true`가 필요하다. 신청 기능 off에도 실행되며 기동 60초 후, 이후 fixedDelay 1시간이다. 종료 기한과 실제 물리 삭제는 일치하지 않으며 장애·백로그·실행 예산에 따라 지연될 수 있다.
6. '신청 보관 정리' deleted/failed 건수와 '신청 삭제 실패'/'신청 보관 정리 중단' 로그를 확인한다. 실패 시 DB 상태·연결·FK를 점검하고 다음 회차/재기동 후 삭제를 재확인한다. 삭제 cursor는 재기동 시 초기화된다. 별도 공개 수동 삭제 API는 없다.
7. 검토 대기는 이 정책의 삭제 대상이 아니다. 승인 신청은 이용 종료 기준이 정해질 때까지 보존한다. 가맹점 INACTIVE·계정 DISABLED를 이용 종료로 해석하지 않는다. 백업 보존/폐기는 별도 운영 정책이다.

### 실행 근거와 한계

최종 실행은 `./gradlew spotlessApply check mariaDbTest --console=plain` 통과(일반 658 통과·1 skip, MariaDB 531 통과), 프론트 lint/format:check/build/test 통과(230개)다. 실행 기준과 상세 검증은 [identity README](README.md)의 PR4 항목을 따른다. 브라우저·모바일/키보드·Gmail 실제 수신·운영 HTTPS·Notion 최신 대조는 수행하지 않았다. 단일 서버 잠금 전제를 유지한다.
앞선 2차 설계 재검토의 659/514/227 수치는 **변경 전 회귀**이며 이번 구현 결과로 사용하지 않는다. 설계 검증 계획 중 별도 JVM 시간대 실행·스케줄러 실시간 기동·5분 예산 소진/재기동 커서 실험·모든 경쟁 조합의 양방향 제어는 별도 미검증으로 남긴다.
