# #528 접속기록 보관 정책 구현

- 기준: `dev` `fa7974c`, `feature/528-access-record-retention` 작업 트리.
- 결정: [ADR-0033](../decisions/0033-login-history.md), [ADR-0080](../decisions/0080-operator-access-denials.md).

## 변경

- `AccessRecordRetentionPolicy`에 달력상 1년 보관·90일 목록 기간을 모았다. UTC 현재 시각을 마이크로초로 맞춰 기준을 계산한다.
- 로그인 이력은 1년 경계 포함 과거 행을 삭제하고 최근 성공은 경계보다 이후부터 현재까지 조회한다. 목록은 90일 유지한다.
- `OperatorAccessDenialRetentionService`는 기동 후·매시간 1,000건씩 독립 트랜잭션으로 IP만 NULL 처리한다. NULL은 건너뛰고 원본 행·다른 칸을 유지한다. 배치 반복은 한 실행 최대 5분이며 실패 후 다음 실행에서 재시도한다.
- 재검토 후 새 migration으로 `operator_access_denials(occurred_at, denial_id)` 인덱스를 추가한다. 기존 migration·컬럼·데이터·HTTP API·이벤트 모양은 유지한다. 마지막 로그인의 빈 안내만 1년으로 맞추고 목록의 90일 안내는 유지한다.

## 초기 구현 검증 (`d197f6e`)

2026-11-04, `dev` `fa7974c` 위 최종 구현 작업 트리에서 실행했다. 검사 후 애플리케이션·테스트 코드는 변경하지 않고 문서에 결과만 기록했다. 과거 #527 결과를 이번 검증으로 사용하지 않는다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest --console=plain`: BUILD SUCCESSFUL, 6분 25초. 일반 1,440개 중 1,439개 통과·기존 1개 skip, UTC MariaDB 825개·Asia/Seoul MariaDB 20개 통과. failures/errors 0. 컴파일·포맷·모듈/계층·전체 Flyway/JPA validate 포함.
- 로그인 이력 HTTP/DB 24개·403 원본 HTTP/DB 19개 통과. 1년 경계 직전/정확한 경계/이후, 100일 전 성공과 90일 목록 분리, 미래 제외, 원본 행·다른 칸 유지, NULL 건너뛰기·재실행, 장애 후 재시도·1,000건 초과 배치를 확인했다. 403 배치는 윤년을 포함한 달력상 1년도 확인한다.
- `cd frontend && npm run lint && npm run format:check && npm run build && npm test`: 종료 코드 0, 테스트 325개 통과. 기존 `scripts/export-p6-preview.mjs`의 `no-unsafe-finally` lint 경고와 번들 크기 경고는 남아 있다.
- `git diff --check`, 변경 문서의 상대 파일 링크 검사: 통과.

테스트 수치는 `backend/build/test-results/{test,mariaDbTest,settlementSeoulMariaDbTest}` XML의 failures/errors/skipped와 프로세스 종료 코드로 대조했다. 보고서는 `backend/build/reports/tests/`에 있다.

## 미검증·다른 담당

실제 1년 경과 대기·예약 주기 대기·운영 DB 적용·브라우저 실행·Notion 동기화는 수행하지 않는다. 테스트는 시각을 주입하고 배치를 직접 호출한다.
P2 복사본은 #515 담당 범위다. 이벤트 발행 기록·백업에 남는 IP의 파기·복구 정책과 조사 원본 행의 보관 기간은 별도다.
제8조의 개별 기록 해당 여부·정보주체 제외·실제 운영 환경의 최소 2년 이상 보관 의무 판정은 미완료다. 이 구현은 1년 제품 정책이며, 2년 이상 의무 대상에는 그대로 적용하지 않는다. 운영 적용 전 별도 정책·구현이 필요하다.

## 재검토 보완 — IP 정리 인덱스

`d197f6e` 재검토에서 기존 `(user_id, occurred_at)` 인덱스가 전체 계정의 만료 IP 검색에 맞지 않음을 확인했다. 새 migration은 `(occurred_at, denial_id)` 인덱스 하나만 추가한다.

가상 최근 기록 1만 건·만료 0건을 넣고 Repository의 실제 `@Query` SQL을 읽어 EXPLAIN하는 회귀 테스트를 추가했다. 인덱스 추가 전 대상 인덱스 불일치로 실패(BUILD FAILED, 17초)를 재현했다. 수정 후 같은 테스트를 포함한 전체 검사가 통과했다.

2026-11-04, `d197f6e` 위 인덱스 보완 최종 작업 트리에서 `cd backend && ./gradlew spotlessApply check mariaDbTest --console=plain`을 실행했다. BUILD SUCCESSFUL(6분 4초), 일반 1,440개 중 1,439개 통과·기존 1개 skip, UTC MariaDB 826개·Asia/Seoul MariaDB 20개 통과, failures/errors 0. 전체 Flyway 적용·JPA validate·모듈/계층 검사 포함이다. 검사 후 테스트·migration은 바꾸지 않고 실행 기록만 보완했다.

403 원본 HTTP/DB 테스트 20개가 통과했다. 실제 IP 정리 SQL의 선택 인덱스가 `idx_operator_access_denials_retention`, 검색 방식이 `range`이며 `Using filesort`가 없음을 확인했다. 인덱스 컬럼 순서도 실제 DB에서 확인했다.

`git diff --check`와 변경 문서 상대 링크 검사도 통과했다. 프론트는 이번 인덱스 보완에서 바꾸지 않아 위 `d197f6e`의 검사 결과를 유지하고 재실행하지 않았다. 운영 DB 적용·실제 운영 데이터의 실행 시간은 미검증이다. 새로운 다른 담당 계약 변경이나 결정 요청은 없다.
