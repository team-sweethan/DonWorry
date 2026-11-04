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

- [x] OPERATOR 정지: INACTIVE, 모든 세션 만료, SYSTEM 감사 1건·전후 상태
- [x] 순차·동시 재호출: 추가 감사·세션 변경 없음
- [x] ACTIVE/INACTIVE ADMIN·SECURITY_STAFF 모두 거절·변경 없음
- [x] NULL·0·음수·없는 ID 실패 계약
- [x] 감사 저장 실패: 상태 롤백, 세션 유지, 재시도 성공
- [x] 호출자 트랜잭션 롤백에도 정지·감사·세션 만료 유지
- [x] 관리자 해제와 경합: 두 선행 순서 검증, 커밋·세션 만료까지 잠금 유지
- [x] 정지 전 세션 요청 거절·정지 후 로그인 기존 응답·관리자 해제 후 로그인 성공
- [x] 같은 가맹점의 서명된 결제 요청 정지 전후 통과
- [x] 기존 계정·가맹점·신청 관리 감사 STAFF 저장 회귀
- [x] 변경 전 DB 기존 감사 행 → migration → STAFF, 기존 값·FK·대상 CHECK 보존
- [x] SYSTEM+NULL 허용, STAFF+NULL·SYSTEM+ID·알 수 없는 유형·NULL 유형 거절
- [x] 전체 Flyway 적용·JPA validate·ModularityTests·ArchitectureTests
- [x] 실제 라우터의 무권한 정산·자금·조치 목록/상세 직접 진입: 업무 API 호출 0회
- [x] 무권한 홈: 연동 조회 허용, 정산·조치 API 자동 호출 없음
- [x] backend spotlessApply·check·mariaDbTest
- [x] frontend lint·format:check·build·관련 테스트

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
실제 인증 가드·페이지를 메모리 라우터로 실행하며 API만 테스트 응답으로 대체한다. 정산·자금·대출·조치 목록/상세 무권한 진입과 무권한 홈 6건에, FDS_VIEW를 부여한 조치 목록의 정상 조회 대조 1건을 추가했다. 모두 통과하면 `PASS 7/7`을 표시한다.
이 페이지는 `npm test`에 자동 포함되지 않는다. Chrome 확장 연결 오류 후 기본 조작을 시도했으나 자동 승인 검토에서 Google Chrome 사용이 거절되어 실제 실행은 미검증이다. 검증용 Vite 서버는 종료한다.
코드 점검상 기존 가드가 페이지 마운트를 막으므로 제품 화면 코드는 변경하지 않았다. #515에 공유할 결과는 브라우저 검증 미수행 사실을 포함해야 하며, 이번 세션에서 외부 댓글은 게시하지 않는다.

전체 검사 보완: 기본 512 MiB 테스트 JVM이 후반 Spring 컨텍스트 로딩 중 `OutOfMemoryError`로 진행하지 못했다. `jcmd GC.heap_info`에서 524288 KiB 중 523469 KiB 사용을 확인했다. #517 통합 테스트는 클래스 종료 시 컨텍스트를 닫고, `mariaDbTest`만 최대 힙 2 GiB로 설정했다. 일반 check·앱 실행 설정은 바꾸지 않는다.
중간 실행에서는 새 결제 테스트의 정상 접수 응답(202)·VARBINARY 내용 비교를 수정했다. 기존 `LoginHistoryHttpTests.logout_whenConcurrentAcrossRoutes_recordsAtMostOnce`에서도 Spring Security의 세션 조회 중 `Session already invalidated`로 500이 한 번 발생했다. 변경 전 dev `86a5441`의 같은 테스트는 별도 임시 복사본에서 두 번 통과했으며 기존 문제라고 단정하지 않는다. 최종 실행 결과와 구분한다.

### 최종 자동 검사 (2026-11-04)

구현·테스트·migration 최종 상태 `2dcf4af`에서 실행했다. 이후에는 이 실행 기록만 갱신한다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest --console=plain`: **BUILD SUCCESSFUL**, 5분 42초. 일반 1,404건 중 1,403 통과·기존 1건 skip, MariaDB 795건 모두 통과, 별도 Asia/Seoul JVM 20건 모두 통과. failures/errors 0을 XML 합계로 확인했다. ModularityTests·ArchitectureTests·전체 Flyway·JPA validate 포함.
- 신규 일반 4건·MariaDB 12건이 통과했다. 정지 서비스 MariaDB 9건·수행자 migration 2건·HMAC/공개 결제창 회귀 1건이다. 기존 STAFF 저장 경로 검증도 함께 통과했다.
- 최종 실행에서 기존 동시 로그아웃 22건·웹훅 7건도 전부 통과했다. 웹훅 중간 실행의 횟수 실패 이후 새 결제 회귀에서 선택 항목 notifyUrl을 생략해 이 검사가 불필요한 웹훅 예약을 남기지 않게 했다. 기존 테스트의 기대값·제품 코드는 완화하지 않았다. 변경 전 dev 웹훅 7건도 별도 실행에서 통과했다. 기존 검사들의 간헐 실패 원인이 완전히 규명됐다는 뜻은 아니다.
- `cd frontend && npm run lint && npm run format:check && npm run build && npm test`: 종료 코드 0, 테스트 324건 모두 통과. 기존 `scripts/export-p6-preview.mjs`의 `no-unsafe-finally` 경고와 번들 크기 경고 유지. 실제 브라우저 시나리오 6건의 통과를 의미하지 않는다.
- `node --check frontend/tests/browser/owner-access.mjs`, `git diff --check 86a5441`, 신규 설계 문서의 상대 링크 존재 검사: 통과.

보고서: `backend/build/reports/tests/test/index.html`, `backend/build/reports/tests/mariaDbTest/index.html`, `backend/build/reports/tests/settlementSeoulMariaDbTest/index.html`.

미검증: 실제 브라우저 무권한 진입·홈 6건, 운영 DB 적용, 실제 P2 탐지 연결, 해제 직후 과거 이벤트 재정지 방지. 외부 #515 댓글은 게시하지 않았으며 코드 점검 결과와 미검증 범위를 이 문서·PR에 남긴다.

### 사용자 브라우저 실행 후 검증 페이지 보완

사용자 실행에서 첫 시나리오가 `화면 준비 시간 초과`로 실패했다. 검증 코드가 `textContent.includes('403')`를 기다렸으나 Ant Design Result의 403 숫자는 SVG여서 텍스트에 없다. 실제 화면 문구인 `접근 권한이 없습니다`를 기다리도록 수정하고, 실패 메시지에 시나리오 경로·API 호출 목록·화면 텍스트를 추가했다.
설치된 Ant Design Result를 React로 렌더링해 이전 숫자 조건은 false, 수정한 문구 조건은 true임을 확인했다. JS 문법·Prettier·lint 검사도 실행했다. 이는 전체 브라우저 6개 시나리오 통과를 뜻하지 않으며 사용자 새로고침 후 확인이 남아 있다. 백엔드는 여전히 필요하지 않다.

### 사용자 확인: 기존 6개 시나리오 통과

사용자가 `69335c8` 검증 페이지를 새로고침한 뒤 PASS 6/6과 경로별 API 내역을 제공했다. 권한 없는 정산·자금·대출·조치 목록/상세는 인증 확인·최근 로그인 조회만 발생했고 업무 API는 0회였다. 무권한 홈은 여기에 `/api/owner/integration?page=0`만 추가됐다. 위 두 화면 체크를 사용자 실행 근거로 완료 표시한다. 에이전트가 직접 브라우저를 실행한 결과는 아니다.
이 결과는 API 대역을 사용한 실제 라우터·화면 검증이며 실제 서버 인가·자동 정지 전체 E2E를 뜻하지 않는다. 앞서 기록한 브라우저 미검증은 이 사용자 확인으로 기존 6건에 한해 해소됐다.

검증 도구가 모든 화면을 막아도 통과하는 오류를 잡기 위해 같은 `/owner/actions`에 FDS_VIEW를 부여하는 대조 1건을 추가했다. 이 경우 실제 조치 목록 본문과 `/api/merchant/security-cases` 호출이 반드시 있어야 통과한다. API 대역은 권한과 무관하게 같은 성공 데이터를 반환하므로 대역 자체가 권한 검사를 흉내 내지 않는다. 기존 무권한 0회 기준은 유지했다. 추가 대조 시나리오의 브라우저 실행은 아직 미검증이다. 문법·포맷·lint 검사는 통과했다(기존 lint 경고 1건 유지).
