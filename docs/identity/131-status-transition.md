# #131 PR A 상태 전환 운영 안내

[ADR-0060](../decisions/0060-identity-status-names.md), [Issue #131](https://github.com/team-sweethan/DonWorry/issues/131).

## 적용 범위

계정 DISABLED → INACTIVE, 신청 EMAIL_PENDING → EMAIL_VERIFICATION_PENDING. 데이터 삭제·개발 DB 초기화 없이 전환한다. 신청 CHECK와 생성 컬럼 3개를 함께 바꾸며 UNIQUE 인덱스는 계속 유지한다. 시드 계정 ACTIVE와 과거 감사 이력의 USER_DISABLED·JSON은 보존한다. 새 수동 정지 이력은 USER_INACTIVATED다. 가맹점·권한·금융·메일 발송 PENDING은 유지한다.

현재 PR A는 `prod` 시연 계정 정지를 구현하지 않는다. 운영 배포 전에 PR B도 필요하다. 2026-11-02 운영은 빈 DB로 시작하는 사용자 결정을 따르되, 기존 연습 DB 삭제는 이 작업에서 실행하지 않는다. 담당자가 대상 VM1·DB를 확인한다. 팀 로컬·공용 개발 DB는 보존 업그레이드한다.

## 점검 배포 순서

1. 공용 개발 DB를 쓰는 팀원과 전환 시간을 조율한다. 현재 DB 호스트·포트·이름 및 앱 커밋, Flyway 이력을 기록하고 이전/신규 backend·frontend 배포물을 확보한다. 운영과 개발 DB는 분리한다.
2. 같은 DB를 쓰는 **모든 이전 backend 프로세스**를 종료한다. 요청 유입 차단만으로는 부족하며 예약 정리·메일 복구·수동 실행 등 DB 접근도 중단해야 한다. 자동 재시작·별도 worker가 있다면 멈춘다. 이번 전환 중 구/신 앱 공존은 지원하지 않는다.
3. DB 담당자가 전체 DB를 백업한다. MariaDB 11.8 도구로 데이터·스키마·Flyway 이력을 함께 저장한다. 비밀번호는 명령행에 적지 않고 프롬프트/보호된 설정을 쓴다. 예: `mariadb-dump -h <host> -P <port> -u <user> -p --single-transaction --routines --events --triggers --hex-blob <database> > <backup.sql>`. 백업에는 개인정보·해시가 있으므로 접근을 제한하고 저장소에 넣지 않는다. 별도 일회용 DB에 복원해 테이블·건수·상태 분포·Flyway 이력을 비교한 뒤 진행한다.
4. 새 backend의 Flyway로 두 migration을 적용한다. 계정 파일은 정지 행만 UPDATE한다. 신청 파일은 임시 구/신 조건 ALTER → UPDATE → 새 조건 ALTER 순이다. DDL은 자동 커밋되므로 파일 단위 원자적 롤백으로 보지 않는다. `flyway_schema_history`의 성공과 남은 migration, 구 상태값 잔여 0, `SHOW CREATE TABLE merchant_applications`의 CHECK·생성 컬럼·UNIQUE 3개를 확인한다.
5. 신규 backend·frontend를 같은 점검 배포로 올린다. 이전 화면 탭 새로고침을 안내한다. 신규 계정/조회·정지/복구·기존 세션 종료, 신청→이메일 확인→검토 대기·메일 복구·중복 신청/아이디 예약을 점검한 뒤 서비스를 연다. 정지된 계정을 로컬 시작으로 복구하지 않는다.

## 실패 복구와 롤백

- 전환이 실패하면 앱과 자동 재시작을 계속 중단한 채 DB 로그·실패 SQL·Flyway 이력을 기록한다. 계정 파일 적용 여부와 신청 상태별 건수, CHECK/생성 컬럼/인덱스는 실제 DB에서 확인한다. 임시 상태에서는 구/신 이름이 함께 허용될 수 있다. 두 파일의 성공 여부를 각각 확인한다.
- 부분 실패한 DB에 구버전 앱을 바로 켜거나 SQL 파일을 고쳐 재실행하지 않는다. `flyway repair`로 이력만 지우거나 성공으로 처리하지 않는다. DDL은 이미 반영되었을 수 있으며 앱 상태만 되돌리면 읽기/예약 오류가 난다.
- 기본 복구 경로는 **전환 직전 검증한 백업 전체 복원**이다. DB 담당자가 정확한 대상과 백업을 재확인한 뒤, 모든 앱이 멈춘 상태에서 대상 스키마를 백업 시점으로 재생성/복원한다. 잔여 테이블 위에 부분 덮어쓰지 않는다. `mariadb -h <host> -P <port> -u <user> -p <database> < <backup.sql>`로 데이터·스키마·Flyway 이력을 함께 복원하고 백업 시점과 상태·건수·생성 컬럼·UNIQUE를 대조한다. 이전 backend·frontend를 함께 복구한 후 점검한다. 운영/공용 DB에서 이 절차는 담당자 조율 없이 실행하지 않는다.
- 백업 이후 새 업무 데이터가 생겼으면 전체 복원이 그 변경을 잃을 수 있다. 서비스 재개 후 롤백은 다시 중단·현재 DB 추가 백업·영향 검토 후 진행한다. 임의 역방향 UPDATE나 미검증 역전환 스크립트는 제공하지 않는다.
- 실패 SQL/원인을 해결한 신규 배포물로 다시 진행할 때도 복원된 이전 DB와 앱을 확인하고 같은 중단·백업 절차부터 수행한다. 팀원 개발 DB 초기화를 해결책으로 요구하지 않는다.

## 검증 기록

기준 dev `b3ac3e4`, 브랜치 `feature/131-status-transition`. 실행 결과는 검사 후 기록한다. 기존 이슈/README에 있는 과거 통과 결과는 이번 전환의 근거로 쓰지 않는다.

미검증: 실제 공용/운영 DB 전환·백업 복원 리허설, 실제 브라우저 관리 화면/기존 탭, HTTPS/프록시·배포 비밀값 주입·Notion 동기화. 운영 시연 계정 제한은 PR B다.
