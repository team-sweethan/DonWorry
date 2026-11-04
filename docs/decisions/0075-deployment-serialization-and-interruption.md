# 0075. VM 공통 잠금과 지속 실행 기록으로 배포 중단 확인

- **상태:** 제안 — #482 구현·PR 리뷰 대상, Jenkins/운영 VM 설치·실검증 전
- **날짜:** 2026-11-03
- **관련:** #448 C (#482), #480, #481, #483, #484, [ADR-0074](0074-approved-release-update.md)
- **영향 받는 담당:** P1, Jenkins·VM 운영 담당

## 배경

#481은 승인한 digest만 적용하지만 잠금은 CLI 입구에만 있고, pull·재검증 중 중단은
기록하지 않는다. Jenkins의 동시 실행 제한은 다른 Job·수동 실행·복구를 직렬화하지 못한다.
로컬 운영 사본의 `deploy-donworry`는 `/run/lock/donworry-deploy.lock`을 사용하며
기존 배포 기록이 있으면 최초 배포를 거절한다. 이 사본과 현재 VM의 일치 여부는 미검증이다.

## 결정

### 실제 변경 경로의 잠금

VM의 `/run/lock/donworry-deploy.lock`에 Linux `flock` 배타 잠금을 사용한다.
`Release`의 prepare·approve·apply·inspect·reconcile 메서드 자체에서 획득한다.
Jenkins와 수동 실행은 같은 `/usr/local/sbin/donworry-release`를 호출한다.
승인 대기에는 잡지 않으며, apply는 잠금 획득부터 검증·pull·최종 검증·Compose 적용·
기동 확인·결과 저장까지 유지한다. 최대 300초 대기 후 서비스 변경 없이 실패한다.
잠금 획득 뒤 기존 미완료 기록과 구성·main을 검사하고, pull 뒤 #481의 고정 marker·
이미지·플랫폼·구성·DB·로컬 이미지·main 검증을 다시 수행한다.
최종 main 대조 후 적용 시작이라는 ADR-0074의 최신성 기준과 원자성 한계는 유지한다.

명령 자식에게 잠금 fd를 상속하고 별도 세션으로 실행한다. 부모가 죽어도 자식이 실행 중이면
잠금이 유지되도록 명시적 `LOCK_UN` 없이 fd를 닫는다. SSH SIGHUP은 무시한다.
SIGTERM·일반 예외는 자식 프로세스 그룹을 종료·회수한 뒤 실패를 기록한다.
SIGKILL·VM 재시작은 예외 처리를 보장하지 않으므로 디스크의 running 기록으로 탐지한다.
Docker CLI 종료 뒤 Docker daemon의 변경이 계속될 수도 있다. SSH 단절·잠금 획득 성공·
PID 부재만으로 작업 종료나 서비스 안정 상태를 단정하지 않는다.

복구 대상·DB 정책은 #483이다. 복구 구현은 같은 `exclusive`와 `operation(kind='recovery')`
경계를 사용해야 한다. 임의 shell/Compose를 실행하는 범용 복구 우회 명령은 제공하지 않는다.
기존 최초 배포 스크립트의 복원 helper를 직접 호출하거나 원본 Compose로 업데이트하지 않는다.
직접 Docker 권한이 있는 root의 우회까지 막는 보안 경계는 아니며 sudo/Job 정책은 #484다.

### 지속 기록과 상태 확인

`/srv/donworry/releases/<BUNDLE_ID>/result.json`을 pull 전부터 생성한다.
실행 ID·묶음·승인자·작업 종류·PID·boot ID·시작/갱신/종료 시각·단계 이력·결과를 저장한다.
단계는 validating → pulling → final_validation → applying → checking → publishing → completed다.
단계 전환은 파일 fsync → 같은 디렉터리 임시 파일의 rename → 디렉터리 fsync로 저장한다.
최초 기록이 잘리거나 JSON이 깨져도 성공으로 간주하지 않는다.
`current.json` 역시 원자적으로 저장한다. 성공 포인터 저장 중 죽어도 미완료 결과가 우선한다.

running/failed 또는 해석할 수 없는 기록은 다음 배포를 막는다. 시간 경과나 reboot로 만료시키지
않고, 자동 재시도·자동 복구하지 않는다. 실패 결과·승인 기록은 삭제하거나 성공으로 고치지 않는다.
`inspect`는 같은 잠금 아래 실제 컨테이너별 이미지 ID/ref·running·health와 중단 단계를 조회한다.
혼합 이미지·컨테이너 부재·조회 실패는 운영자가 확인할 대상이며 기본 동작으로 해제하지 않는다.

운영자는 진행 중인 작업 종료·DB/Flyway 상태·업무 영향을 별도로 확인하고 근거 식별자를 남긴 뒤
`reconcile --against current|target --evidence <근거>`를 명시적으로 실행한다.
명령은 서비스를 바꾸지 않고 이전 성공/확인 묶음 또는 중단된 승인 묶음의 **전체** 이미지·
구성·DB health·기동 검사를 수행한다. 일치할 때만 별도 `resolution.json`에 운영자·근거·
검사 시각·관측 묶음·원래 결과 해시를 기록하고 PAUSE_CD를 해제한다.
이 검사는 DB 호환성·migration 성공·업무 기능을 증명하지 않는다. 그 확인은 운영자 근거 및
#483 정책의 대상이다. 근거 식별자 형식만 검사하며 근거 내용의 진실성을 자동 검증하지 않는다.
다른 미완료 실행이 있거나 부분 교체·기동 실패이면 해제할 수 없다.
기록 후 중단된 reconcile은 같은 인자로 다시 점검할 수 있다. 기존 배포 승인은 재사용하지 않는다.

최초 배포는 current 묶음이 없으므로 current 확인/자동 rollback을 허용하지 않는다.
대상 전체가 일치하지 않으면 #483 운영자 복구가 필요하다. 이 작업은 복구 후보를 만들지 않는다.

### 제한 시간과 재시도

| 경계 | 제한 | 자동 재시도 |
| --- | --- | --- |
| 공통 잠금 | 300초 | 0.1초 간격으로 잠금 획득만 반복 |
| main·registry 조회 (명령당) | 120초 | 없음; 실패 시 새 점검/승인 |
| digest pull (서비스당) | 600초 | 없음; daemon 내부 재시도는 Docker 동작 |
| 로컬 inspect·Compose config/ps | 30초 | 없음 |
| Compose up | 360초, 내부 wait 240초 | 없음; 일부 적용 가능하므로 상태 확인 |
| health 각 curl/exec | 10초 | 읽기 전용 health만 최대 60회·4초 간격, 전체 300초 예산 |
| 백업·복원·업무 기능 검사 | 현재 실행 경로 없음 | #483에서 시간·승인·안전한 재시도 범위 결정 전 실행 금지 |

health 예산은 각 시도 사이에 검사하므로 마지막 시도의 최대 30초가 추가될 수 있다.
프로세스 제한 초과 시 그룹에 TERM을 보내고 최대 5초 후 KILL, 회수한다.
커널/Docker daemon 정체는 CLI timeout만으로 해소된다고 보장하지 않는다.
Jenkins 준비 30분(잠금 포함), 승인 대기 30분, 승인 기록 10분, 적용 90분으로 분리한다.
SSH 연결은 5초, keepalive는 15초 간격·3회다. 전송 단절을 VM 작업 취소로 해석하지 않는다.

## 버린 대안

| 대안 | 버린 이유 |
| --- | --- |
| Jenkins disableConcurrentBuilds만 사용 | 수동 명령·다른 Job·복구와 경합함 |
| pull 뒤에만 잠금 획득 | 다운로드 중단 기록과 공통 작업 경계를 나누어 복잡해짐; 현재는 대기 후 전체 재검증 선택 |
| 잠금 무기한 대기/오래된 실행 자동 재시도 | 운영 대기·Jenkins 중단이 누적되고 새 배포를 덮을 수 있음 |
| 잠금 파일 삭제, PID/시간만으로 stale 처리 | 살아 있는 프로세스·Docker daemon 작업과 별도 잠금 inode가 경합할 수 있음 |
| SSH가 끊기면 성공/실패 추정 | VM과 daemon 작업의 종료 여부를 알 수 없음 |
| 실패 결과 삭제 또는 자동 success 변경 | 중단 근거와 실제 서비스 상태를 잃음 |
| health만 맞으면 자동 해제/이전 태그 rollback | DB migration·부분 교체·업무 영향이 확인되지 않음 |

## 영향과 검증

API·업무 DB 스키마·화면은 변경하지 않는다. VM 명령·Jenkins 시간/SSH·실행 기록 계약과
운영 절차가 바뀐다. 실제 설치 시 명령 구현 해시가 바뀌므로 기존 묶음은 새 준비·승인이 필요하다.

대역 테스트는 pull 실패 기록, 대기한 이전 실행의 main 재대조, 수동/복구 잠금 경합,
부분 교체 확인 거절, 명시적 확인 및 이력 보존을 검사한다. 로컬 실제 프로세스의 SIGKILL,
부모 종료 후 자식 fd 잠금 유지, timeout 종료도 검사한다. 실제 Compose CLI 구성 왕복과
Git/Docker 대역 검증을 구분한다. Jenkins 중단·실제 SSH 단절·Linux VM reboot·daemon 부분
적용·업무 기능·DB 복원은 운영 미검증이다. [절차/검사 명령](../../ops/vm/README.md)에 따른다.
