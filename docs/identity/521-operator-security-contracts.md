# #521 사장님 403 이벤트와 계정 상태 조회 계약

- 기준: [Issue #521](https://github.com/team-sweethan/DonWorry/issues/521), [#515 정본](https://github.com/team-sweethan/DonWorry/issues/515)과 댓글 합의.
- 선행: [PR #518](https://github.com/team-sweethan/DonWorry/pull/518)의 `fee52ac`에서 별도 워크트리를 만들고, 같은 PR의 최신 `c74a03a`(Modulith 예약 작업의 테스트 DB 간섭 차단)까지 반영했다. 실제 dev 병합·재정렬 여부와 검증 결과는 아래 실행 기록으로 구분한다.
- 결정 이유·버린 대안: [ADR-0079](../decisions/0079-operator-security-contracts.md). 정지 기능은 [ADR-0078](../decisions/0078-operator-account-suspension.md)을 유지한다.

## 1. 범위와 의존 방향

`identity/api`에 이벤트 record·거절 종류와 상태 조회 인터페이스·결과 record·전용 상태 enum을 제공한다. `identity/service`의 조회 빈을 실제로 등록하여 P2가 Java 인터페이스를 주입받을 수 있게 한다.
기존 `fds → identity :: api` 방향만 사용하며 `allowedDependencies`·HTTP API·SecurityConfig·프론트엔드는 바꾸지 않는다.

403 원본 테이블·마이그레이션·이벤트 발행·거절 종류 분류·P2 수신과 룰·탐지 목록은 이번 범위가 아니다. 이번 PR을 병합해도 실제 403 이벤트가 들어오는 것은 아니다.

## 2. OperatorAccessDenied

위치: `com.donworry.identity.api`. `@Builder`가 있는 record이며 HTTP 응답 DTO가 아니다.

| 항목 | Java 타입 | 의미 |
| --- | --- | --- |
| `eventId` | `Long` | P1 403 원본 행의 `BIGINT AUTO_INCREMENT` PK. UUID·사용자 ID·Modulith publication ID가 아니다. 재전달해도 같은 값이며 P2 중복 제거와 원본 논리 참조에 사용한다 |
| `userId` | `Long` | 거절된 OPERATOR 계정 ID |
| `loginId` | `String` | 거절 당시 로그인 ID |
| `merchantId` | `Long` | 거절 당시 소속 가맹점 ID |
| `httpMethod` | `String` | 실제 요청 메서드 |
| `path` | `String` | 쿼리스트링을 제외한 요청 경로. 발행자가 저장·발행 전에 최대 512자로 잘라 같은 값을 양쪽에 사용한다 |
| `deniedType` | `OperatorAccessDeniedType` | `OUT_OF_ROLE` 또는 `PERMISSION_MISSING` |
| `occurredAt` | `Instant` | 실제 거절 발생 시각(UTC). P2 수신·재전달 시각으로 바꾸지 않는다 |
| `clientIp` | `String` | 거절 당시 클라이언트 IP. 신뢰할 프록시·추출 방식과 원본 저장 제약은 후속 수집 구현에서 정리한다 |

`OUT_OF_ROLE`은 OPERATOR에게 열려 있지 않은 경로(없는 경로 포함), `PERMISSION_MISSING`은 OPERATOR에게 열린 경로에서 필요한 권한이 없는 경우다.
이번에는 enum만 추가한다. 실제 분류는 다음 PR에서 SecurityConfig의 메서드·경로 규칙과 공유해야 한다.

`OperatorAccessDenied.MAX_PATH_LENGTH = 512`를 계약 상수로 둔다. record 생성자는 요청 경로를 정규화하거나 잘라 주지 않는다. 정규화는 후속 발행자가 원본 저장 전에 한 번 수행하며, 잘못된 입력을 record가 자동으로 보정한다고 가정하지 않는다.
생성·발행의 필수 값 검증과 IP 추출은 후속 구현의 책임이다. 다음 발행 PR은 원본 저장과 같은 트랜잭션에서 이벤트를 발행하고, 저장 실패가 기존 403 응답을 500으로 바꾸지 않도록 해야 한다.

## 3. OperatorAccountStatusReader

```java
Optional<OperatorAccountStatusSnapshot> findById(Long userId);
```

| 조건 | 결과 |
| --- | --- |
| 존재하는 OPERATOR | 현재 `OperatorAccountStatus.ACTIVE` 또는 `INACTIVE`, 마지막 해제 시각 |
| 없는 계정 | `Optional.empty()` |
| ADMIN·SECURITY_STAFF | 활성 여부와 관계없이 `Optional.empty()` |
| null·0 이하 ID | `IllegalArgumentException` |
| 계정·감사 조회 DB 장애 | 예외 전파. empty나 해제 이력 없음으로 바꾸지 않음 |

`OperatorAccountStatusSnapshot`은 `OperatorAccountStatus status`, `Instant lastActivatedAt` 두 필드다. `identity/entity/UserStatus`를 외부에 노출하지 않고 서비스에서 명시적으로 변환한다.
`ACTIVE`는 계정 상태만 뜻한다. 가맹점 활성·일시적 로그인 잠금·보유 권한·비밀번호 변경 필요 여부를 합친 로그인 가능 판정이 아니다.

`lastActivatedAt`은 해당 계정의 마지막 `USER_ENABLED.created_at`이다. `AdminChangeHistoryRepository.findFirstByTargetUserIdAndChangeTypeOrderByCreatedAtDesc(userId, "USER_ENABLED")`로 조회한다.
해제 이력이 없으면 null이며 계정 생성·로그인·FDS 정지·수동 정지 시각을 대신 쓰지 않는다. 현재 INACTIVE여도 과거의 마지막 해제 시각은 반환한다.
기존 UTC `LocalDateTime`을 `ZoneOffset.UTC`로 `Instant`에 변환하여 `DATETIME(6)` 정밀도를 보존한다. JVM 기본 시간대를 사용하지 않는다.

기존 인덱스 `idx_admin_change_histories_user_created (target_user_id, created_at)`를 사용 가능한 형태로 최신 해제 한 건을 찾는다. `change_type`은 인덱스에 없으므로 해제 기록을 찾기까지 다른 유형을 검사할 수 있다. 새 인덱스·컬럼·캐시는 추가하지 않는다.

### 재활성화 경로의 유지보수 규칙

현재 관리자 `AdminAccountService.setStatus`는 실제 `INACTIVE → ACTIVE` 변경 때 상태와 `USER_ENABLED`를 같은 트랜잭션으로 저장한다. 이미 ACTIVE인 계정에 ACTIVE를 다시 지정하면 변경·감사 모두 생략하므로 기준 시각이 초기화되지 않는다.
앞으로 계정을 다시 활성화하는 경로를 추가할 때도 **상태 변경과 같은 트랜잭션에서 `USER_ENABLED`를 반드시 기록한다.** 최초 ACTIVE 생성은 해제가 아니므로 기록을 만들지 않는다. 감사 이력에서 조회하는 동안에는 이 기록을 삭제하면 기준 시각이 소실되므로 보존해야 한다.

### 트랜잭션과 보장 범위

조회 서비스는 `REQUIRES_NEW`, `readOnly = true`, `REPEATABLE_READ`를 사용한다. P2의 기존 집계 트랜잭션을 잠시 중단하고 새 영속성 문맥·스냅샷에서 계정 상태와 감사 이력을 함께 읽는다.
관리자가 조회 시작 전에 커밋한 해제를 호출자 쪽의 오래된 스냅샷 때문에 놓치지 않게 한다. 상태와 감사가 별개 시점의 조회로 섞이지 않도록 조회 트랜잭션의 격리 수준을 명시한다.
호출자 트랜잭션이 DB 연결을 보유한 채 조회할 수 있으므로 추가 연결을 사용한다. P2는 후보일 때만 조회하고, 연결 풀을 넘는 동시 실행은 P2 연결 시 검증해야 한다.

조회는 상태 변경 잠금을 잡지 않는다. 반환 후 관리자가 해제하고 P2가 `suspend(userId)`를 호출하는 경합까지 원자적으로 막는 계약은 아니다. 조건부 정지나 세대 값이 필요하면 #515에서 계약을 먼저 합의한다. 이번 구현으로 그 경합이 해결됐다고 보고하지 않는다.

## 4. P2 연결 순서

1. `eventId`로 중복을 제거하고 이벤트를 저장한다.
2. `occurredAt` 기준으로 해당 룰의 시간 창에서 집계한다. `OUT_OF_ROLE` 기본 5분 5회, `PERMISSION_MISSING` 기본 1분 30회다.
3. 후보일 때만 `findById(userId)`를 호출한다. empty 또는 INACTIVE면 정지 호출 없이 끝낸다.
4. ACTIVE면 집계 시작을 `max(해당 룰의 최근 N분 시작, lastActivatedAt)`으로 보정해 다시 센다. null이면 해당 룰의 시간 창만 사용한다.
5. 여전히 기준을 충족하면 기존 `OperatorAccountSuspender.suspend(userId)`를 호출한다.

해제 경계는 `occurredAt > lastActivatedAt`을 권장한다. 해제와 같은 시각의 과거 이벤트까지 제외하려는 세부안이며, 이 PR에는 P2 집계를 구현하지 않는다. P2 연결 시 이 비교 연산·시간 창의 끝 경계를 함께 확인한다.
기존 정지 API는 독립 커밋이므로 P2 저장 트랜잭션이 나중에 롤백해도 정지는 되돌아가지 않는다. 수신 멱등과 탐지 기록·호출 순서·복구는 P2가 연결 시 검증한다.

## 5. 검증 계획과 실행 기록

- 단위: 잘못된 ID, 없는 계정·직원 제외, ACTIVE/INACTIVE, null 의미, UTC 변환, 두 저장소 장애 전파.
- 공개 계약: 내부 entity/repository/service 비참조를 ArchUnit으로 검사한다. 같은 identity 내부 의존은 ModularityTests만으로 금지되지 않는다. 이벤트 JSON 왕복에서 Long 식별자·두 enum 값·Instant를 보존한다.
- MariaDB: 실제 정지 → 관리자 해제, 반복 해제·재정지, 다른 계정·변경 종류·삽입 순서 제외, ACTIVE 재지정 시각 유지, 마이크로초 보존, 호출자의 오래된 스냅샷과 분리, 기존 인덱스 실행 계획.
- 전체: `cd backend && ./gradlew spotlessApply check mariaDbTest --console=plain`.

실행 결과는 아직 기록하지 않았다. 구현 변경 후 검사를 실행하고 결과·실행 기준을 여기에 남긴다.

미검증: 실제 403 수집·발행, P2 연결·동시성·전체 재정지 방지, 운영 DB·배포. 이번 PR의 타입 제공을 #515 전체 완료로 취급하지 않는다. 병합 후 #515에 P2 연결 가능 여부를 알리는 댓글은 별도 외부 작업이다.
