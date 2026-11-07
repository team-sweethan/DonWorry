# 0084. 보안 직원 계정 보안 정지 — 별도 상태 `SUSPENDED`와 판정으로만 해제

- **상태:** 제안 — 사용자 결정(11/07). P1 주환 확인 필요 (P1 소유 상태값 · 표 · `AdminAccountService` 변경)
- **날짜:** 2026-11-07
- **관련:** #647 (이 ADR), [ADR-0082](0082-insider-access-security.md) 5절 · 합의 4번, [ADR-0078](0078-operator-account-suspension.md) (사장님 정지 순서), 실험 커밋 e7ff1b3 (T10)
- **영향 받는 담당:** P1 주환(상태값 · 표 · 계정 관리 API · 화면), P2 정후(정지를 부르는 쪽, 조각 7b), P3 수민(정지를 푸는 판정, 조각 8)

## 배경

ADR-0082는 내부자 위험 경보(예: 경보 중 내보내기 시도)나 징계 판정 때 보안 직원 로그인을 막기로 했다. 막을 때 계정 상태를 무엇으로 둘지는 합의 4번으로 남겼다.

- 계정 상태는 `ACTIVE` · `INACTIVE` 두 값이다. `INACTIVE`는 퇴사 · 운영 정지에 쓰고, [계정 관리]에서 관리자가 [계정 정지] · [정지 해제]로 켜고 끈다
- 실험(e7ff1b3)은 `INACTIVE`를 재사용했다. 그 결과 관리자가 [정지 해제]를 누르면 조사 판정 없이 보안 정지가 풀렸다(옆문)
- 로그인 실패 잠금은 따로 있다(`platform_users.login_locked_until`, 시간이 지나면 자동으로 풀림)
- `platform_users.status`는 CHECK가 없는 `VARCHAR(20)`이다. 로그인 · 비밀번호 변경 · 사건 배정 후보 등은 "`ACTIVE`가 아니면 막기"로 검사한다

## 결정

보안 정지는 **별도 상태 `SUSPENDED`**로 둔다. 정지 기록은 새 표 `staff_account_locks`(P1)에 남기고, **조사 판정으로만** 푼다.

1. **상태:** `UserStatus.SUSPENDED` 추가. "`ACTIVE`가 아니면 막기" 검사가 그대로 로그인 · 세션 · 배정 후보를 막는다. 상태값 추가에 마이그레이션은 필요 없다
2. **정지 기록:** `staff_account_locks` 한 줄 = 정지 한 번. 이유 · 근거 경보 · 정지 주체(시스템 `SYSTEM` / 관리자 `STAFF` + 번호) · 해제한 관리자 · 사건 · 판정 · 해제 결과. 사람당 열린 정지 1개(생성 칼럼 UNIQUE)
3. **공개 창구:** `identity.api.StaffAccountLocker` — `lockBySystem` · `lockByAdmin` · `release` · `isLocked` · `findLocks`
4. **정지 순서:** 사장님 정지(ADR-0078)와 같다. 계정 순서 잠금 → 새 트랜잭션(정지 기록 · `SUSPENDED` · 변경 이력) 커밋 → 세션 모두 만료. 부른 쪽이 롤백돼도 정지는 남는다. `SECURITY_STAFF`만 정지하고, 이미 열린 정지가 있으면 그대로 성공한다
5. **해제 결과는 판정이 고른다:** `RESTORE`(무혐의 → `ACTIVE`) / `DEACTIVATE`(징계 · 퇴사 → `INACTIVE`). 징계 직원이 풀리는 순간에도 로그인할 수 없다. 해제는 부른 쪽(판정 저장) 트랜잭션에 참여해 함께 커밋 · 롤백된다
6. **옆문 막기:** 열린 정지가 있는 계정은 [계정 관리] 상태 변경(`PUT /api/admin/users/{id}/status`)을 `409 ADMIN_011`로 거절한다. 화면은 "보안 정지(조사 중)"로 보이고 상태 버튼을 숨긴다
7. **이미 비활성인 직원:** 정지 기록만 남기고 `INACTIVE`를 유지한다. 해제할 때도 `SUSPENDED`일 때만 상태를 바꿔, 퇴사 계정이 되살아나지 않는다
8. **이름:** 로그인 실패 잠금(`login_locked_until`)과 헷갈리지 않게 `LOCKED` 대신 `SUSPENDED`. 정지 기록 줄의 상태는 `OPEN` / `RELEASED`

사장님 정지(`OperatorAccountSuspender`, ADR-0078)는 지금처럼 `INACTIVE`를 쓴다. `SUSPENDED`로 옮길지는 따로 검토한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| `INACTIVE` 재사용 + 열린 정지면 [활성] 거부 (실험안) | 퇴사 정지와 보안 정지가 같은 값이라 목록 · 상세에서 구분되지 않는다. 조사 중인 직원이 상태로 바로 보이지 않고, 옆문 검사를 빠뜨리면 그대로 풀린다 |
| 이름 `LOCKED` | 로그인 실패 잠금(`login_locked_until`)과 같은 말이라 "비밀번호 틀려서 잠김"과 섞인다. 업계도 비활성(퇴사) · 정지(관리자 · 보안 조치) · 잠김(비밀번호 실패)을 나눈다(예: Okta `DEPROVISIONED` · `SUSPENDED` · `LOCKED_OUT`) |
| 해제하면 항상 `ACTIVE` | 징계 · 퇴사 판정이어도 잠깐 로그인 가능한 상태가 되고, 관리자가 다시 정지해야 한다. P3는 identity 내부 `AdminAccountService`를 부를 수 없어 한 번에 끝낼 방법도 없다 |
| 정지 기록 없이 상태만 바꿈 | 왜 · 어느 경보로 막았고 누가 어느 판정으로 풀었는지 남지 않는다. 변경 이력(JSON)만으로는 열린 정지 1개 보장 · 조회가 어렵다 |

## 영향

- API: 새 HTTP 경로 없음(`SecurityConfig` 변경 없음). `PUT /api/admin/users/{id}/status`가 열린 정지 계정에 `409 ADMIN_011`. 계정 목록 응답 · 필터의 `status`에 `SUSPENDED`가 나올 수 있음
- 모듈 경계: `identity.api`에 `StaffAccountLocker` · `StaffLockReason` · `StaffLockOutcome` · `StaffLockRelease` · `StaffLockRecord` · `StaffAccountLockException` 추가. 부르는 쪽(fds 7b · investigation 8)은 기존 화살표(→ identity) 안이다
- DB: 새 표 `staff_account_locks` ([migration](../../backend/src/main/resources/db/migration/V20261107_233256__create_staff_account_locks.sql), [ERD](../erd/README.md)). `platform_users.status`는 변경 없음(CHECK 없음)
- 기존 코드: `OperatorAccountStatusReaderService`는 `SUSPENDED`를 `INACTIVE`로 보고(사장님에게는 오지 않음). `ProductionStartupService`는 시연 계정을 `INACTIVE`로 바꾸므로 `SUSPENDED`도 `INACTIVE`가 됨(더 강한 쪽)
- 화면: [계정 관리] 목록 · 상세 · 필터에 "보안 정지(조사 중)"(주황), 상세에서 상태 버튼 대신 안내 문구
- 테스트: `StaffAccountLockServiceTests` · `AdminAccountServiceTests.setStatus_openLock_isRejected` · `StaffAccountLockMariaDbTests`(로그인 거절, 부른 쪽 롤백 뒤 정지 유지)

## 검증

- 통과(사용자 PC, #647 브랜치): `./gradlew spotlessApply` · `./gradlew check`, frontend `npm run lint` · `npm run format:check` · `npm run build`
- 미검증: `mariaDbTest`(새 표 Flyway 적용 · JPA validate · `StaffAccountLockMariaDbTests`) — CI에서 확인
