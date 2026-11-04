# #517 시스템 감사 기록과 사장님 계정 정지

관련: [#517](https://github.com/team-sweethan/DonWorry/issues/517), [#515 정본](https://github.com/team-sweethan/DonWorry/issues/515), [ADR-0078](../decisions/0078-operator-account-suspension.md).

## 범위와 승인

사용자가 P1 ERD 기록 완료를 확인하고 설계 보완·구현을 승인했다. Notion 내용을 도구로 재조회한 결과는 아니다.
`dev` `86a5441`에서 `feature/517-operator-account-suspension`을 생성했다.
감사 수행자 확장·정지 공개 Java API·화면 오탐 점검을 구현한다. 403 저장·이벤트·룰·P2 호출 연결·활성화 시각 조회는 #515 후속이다.
사용자가 남은 설계 조건을 반영하도록 승인했으므로 #517의 원래 ADR 제외와 달리 이번 계약만 제안 ADR에 기록한다. #515 전체 합의·ADR 채택을 뜻하지 않는다.

## 계약

`identity/api/OperatorAccountSuspender.suspend(Long userId)`만 공개한다. 이유·룰·횟수는 받지 않는다.
NULL·0 이하 ID는 `IllegalArgumentException`, 없는 계정은 공개 예외의 `USER_NOT_FOUND`, 직원은 `NOT_OPERATOR`로 거절한다.
역할을 상태보다 먼저 검사하여 INACTIVE 직원도 거절한다. 이미 INACTIVE인 OPERATOR는 세션·감사를 포함해 변경 없이 성공한다.

호출자 트랜잭션은 `NOT_SUPPORTED`로 중단한다. loginId 조회 → 기존 계정 잠금 → `REQUIRES_NEW`에서 재조회·역할 검사·상태 변경·감사 저장 → 커밋 → 세션 만료 → 잠금 해제 순서다.
따라서 P2 트랜잭션이 이후 롤백돼도 정지는 유지된다. P2는 재시도할 수 있다. DB·감사 실패는 호출자에게 전파하고 세션은 만료하지 않는다.
관리자 해제·로그인은 기존과 같은 잠금을 사용한다. 동시 해제와 정지의 최종 상태는 잠금 획득 순서에 따른다. 단일 JVM 메모리 세션·잠금 전제는 유지한다.

`admin_change_histories.actor_type VARCHAR(20) NOT NULL DEFAULT 'STAFF'`를 추가하고 기존 행을 STAFF로 채운다. `actor_user_id BIGINT NULL`로 변경하되 FK는 유지한다.
CHECK는 STAFF+ID 있음 또는 SYSTEM+ID 없음만 허용한다. 기존 대상 CHECK·FK·인덱스·감사 JSON은 유지한다.
기존 사용자·가맹점 생성 메서드는 STAFF를 명시하며 시스템 사용자 메서드는 SYSTEM+NULL을 고정한다.
FDS 정지는 `USER_SUSPENDED_BY_FDS`, 전후 JSON은 ACTIVE/INACTIVE 상태만 남긴다.
가맹점 상태·키·연동·권한·결제 데이터는 변경하지 않는다. HTTP API나 SecurityConfig 허용 규칙은 추가하지 않는다.

## 화면 점검

현재 `ServerSession`은 `canOpenPath`로 거절하면 Outlet을 렌더링하지 않는다. 홈은 권한별 메뉴만 노출하고 연동 상태만 조회한다.
#515의 무권한 정산 화면 자동 호출 예시는 현재 코드에서 재현되는지 검증해야 한다. 실패한 API 응답을 숨기는 방식으로 처리하지 않는다.

## 검증 체크리스트

체크는 실행 결과를 확인한 뒤에만 표시한다. 최종 결과는 아래 실행 기록에 남긴다.

- [ ] OPERATOR 정지: INACTIVE, 모든 세션 만료, SYSTEM 감사 1건·전후 상태
- [ ] 순차·동시 재호출: 추가 감사·세션 변경 없음
- [ ] ACTIVE/INACTIVE ADMIN·SECURITY_STAFF 모두 거절·변경 없음
- [ ] NULL·0·음수·없는 ID 실패 계약
- [ ] 감사 저장 실패: 상태 롤백, 세션 유지, 재시도 성공
- [ ] 호출자 트랜잭션 롤백에도 정지·감사·세션 만료 유지
- [ ] 관리자 해제와 경합: 두 선행 순서 검증, 커밋·세션 만료까지 잠금 유지
- [ ] 정지 전 세션 요청 거절·정지 후 로그인 기존 응답·관리자 해제 후 로그인 성공
- [ ] 같은 가맹점의 서명된 결제 요청 정지 전후 통과
- [ ] 기존 계정·가맹점·신청 관리 감사 STAFF 저장 회귀
- [ ] 변경 전 DB 기존 감사 행 → migration → STAFF, 기존 값·FK·대상 CHECK 보존
- [ ] SYSTEM+NULL 허용, STAFF+NULL·SYSTEM+ID·알 수 없는 유형·NULL 유형 거절
- [ ] 전체 Flyway 적용·JPA validate·ModularityTests·ArchitectureTests
- [ ] 실제 라우터의 무권한 정산·자금·조치 목록/상세 직접 진입: 업무 API 호출 0회
- [ ] 무권한 홈: 연동 조회 허용, 정산·조치 API 자동 호출 없음
- [ ] backend spotlessApply·check·mariaDbTest
- [ ] frontend lint·format:check·build·관련 테스트

## 실행 기록과 미검증

검증 근거 위치:

| 대상 | 테스트 |
| --- | --- |
| 커밋 순서·커밋 실패·감사 실패·멱등 | `OperatorAccountSuspensionServiceTests` |
| 실제 정지·전체 세션·로그인·해제·직원 거절·ID·롤백·동시성 | `OperatorAccountSuspensionMariaDbTests` |
| 기존 행 업그레이드·수행자 CHECK·기존 FK·대상 CHECK | `AdminChangeActorMigrationMariaDbTests`, `IdentityStatusMigrationMariaDbTests` |
| 정지 전후 HMAC·호스팅 세션·공개 결제창 결제·키 보존 | `PaymentHttpMariaDbTests.payment_whenOperatorSuspended_keepsSignedPaymentAndHostedCheckoutAvailable` |
| 기존 STAFF 경로 | `AdminAccountHttpTests`, `AdminMerchantHttpTests`, `ApplicationApprovalHttpTests` |
| 실제 라우터·effect 실행용 검증 페이지 | `frontend/tests/browser/owner-access.html`·`owner-access.mjs` |

브라우저 검증은 frontend에서 `npm run dev -- --host 127.0.0.1 --port 5177 --strictPort` 후 `http://127.0.0.1:5177/tests/browser/owner-access.html`을 연다.
실제 인증 가드·페이지를 메모리 라우터로 실행하며 API만 테스트 응답으로 대체한다. 정산·자금·대출·조치 목록/상세 무권한 진입과 무권한 홈 6개 시나리오가 모두 통과하면 `PASS 6/6`을 표시한다.
이 페이지는 `npm test`에 자동 포함되지 않는다. Chrome 확장 연결 오류 후 기본 조작을 시도했으나 자동 승인 검토에서 Google Chrome 사용이 거절되어 실제 실행은 미검증이다. 검증용 Vite 서버는 종료한다.
코드 점검상 기존 가드가 페이지 마운트를 막으므로 제품 화면 코드는 변경하지 않았다. #515에 공유할 결과는 브라우저 검증 미수행 사실을 포함해야 하며, 이번 세션에서 외부 댓글은 게시하지 않는다.

전체 검사 보완: 기본 512 MiB 테스트 JVM이 후반 Spring 컨텍스트 로딩 중 `OutOfMemoryError`로 진행하지 못했다. `jcmd GC.heap_info`에서 524288 KiB 중 523469 KiB 사용을 확인했다. #517 통합 테스트는 클래스 종료 시 컨텍스트를 닫고, `mariaDbTest`만 최대 힙 2 GiB로 설정했다. 일반 check·앱 실행 설정은 바꾸지 않는다.
중간 실행에서는 새 결제 테스트의 정상 접수 응답(202)·VARBINARY 내용 비교를 수정했다. 기존 `LoginHistoryHttpTests.logout_whenConcurrentAcrossRoutes_recordsAtMostOnce`에서도 Spring Security의 세션 조회 중 `Session already invalidated`로 500이 한 번 발생했다. 변경 전 dev `86a5441`의 같은 테스트는 별도 임시 복사본에서 두 번 통과했으며 기존 문제라고 단정하지 않는다. 최종 실행 결과와 구분한다.

최종 자동 검사 결과는 실행 완료 후 추가한다. 운영 DB 적용·실제 P2 탐지 연결·해제 직후 과거 이벤트 재정지 방지는 이번 검증에 포함하지 않는다.
