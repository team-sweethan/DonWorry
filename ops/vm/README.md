# VM1 업데이트 CD (#481 B · #482 C · #483 D · #499)

**구현 제안·로컬 검증 단계다. 현재 VM1 또는 Jenkins에 설치·적용하지 않았다.**
기존 `donworry-cd-dryrun` Job은 유지한다. 새 Job은 사람이 `TARGET_SHA`를 지정해
실행하고 관리자가 이미지 묶음을 승인하면 VM1 업데이트를 자동 수행한다.
webhook·SCM polling·main 병합에 따른 운영 배포 자동 시작은 설정하지 않는다.

## 운영 근거 (2026-11-03 사용자 확인)

- 최초 배포: VM1 root가 `DONWORRY_ALLOW_FIRST_DEPLOY=yes`로
  `/usr/local/sbin/deploy-donworry`의 `run_first_deployment` 실행.
- 배포 SHA: `1514b3db28dd001018b210f39b0db7ce6428ccd0`.
  컨테이너 기동·기본 헬스체크는 사용자 확인이며 이 작업에서 원격 재검증하지 않았다.
- Compose: `/srv/donworry/compose.yaml`. 기존 성공 기록: `backups/deployed-image-tag`.
- Jenkins: `donworry-cd-dryrun`에서 SHA·승인·승인 후 main 대조·SSH 운영 점검만 수행.
  기존 업데이트 배포 경로는 없다.
- Mac 사본: `Desktop/donworry-cd-review`. 원본 운영 VM에서 가져왔다는 사용자 확인이다.
  전송 전후 해시 비교를 하지 않아 현재 VM과 바이트 단위 동일함은 미검증이다.

이번 작업에서 읽은 사본의 SHA256 (운영 원본의 검증값이 아님):

| 사본 | 바이트 | SHA256 |
| --- | --- | --- |
| `deploy-donworry` | 22,715 | `e9c1b7b5b54cf75a065f661d5032c9fca8e2949f40b06273f1d0d140d8240b73` |
| `compose.yaml` | 3,525 | `ac8601b225ef8373a567a9519cfd1ff8a71ddc5dac383ead677380c2a811e992` |

## 파일과 실행 계약

- [release.py](release.py): VM root 전용 `prepare` / `approve` / `apply` / `inspect` / `reconcile` 명령.
- [Jenkinsfile](Jenkinsfile): 기존 SSH credential·known_hosts를 사용하는 새 수동 실행 Job 제안.
- [test_release.py](test_release.py): 명령 대역 테스트와 Docker Compose CLI의 구성 왕복 검사.
- [ADR-0074](../../docs/decisions/0074-approved-release-update.md): 승인 대상·최신성 시점·버린 대안.
- [ADR-0075](../../docs/decisions/0075-deployment-serialization-and-interruption.md): 공통 잠금·중단 기록·시간 제한·버린 대안.
- [ADR-0077](../../docs/decisions/0077-release-backup-and-image-recovery.md): 최초 후보·DB 백업·별도 승인 이미지 복구·DB 복원 제한.

운영 스크립트 설치 경로는 `/usr/local/sbin/donworry-release`로 제안한다.
내용은 `release.py`이며 Python 3.10 이상, Linux `fcntl`, Git, Docker Engine·Compose·Buildx,
curl이 필요하다. Docker CLI는 root의 인증/설정을 사용하며 GHCR 읽기 권한이 필요하다.
`--wait`, `--wait-timeout`, `--no-deps`, `--pull never`, `imagetools inspect --raw/--format`을
현재 VM 버전에서 지원하는지 실제 설치 전에 확인한다.

명령 인터페이스 (서버에서 실행할 명령이므로 여기서는 실행하지 않음):

```text
donworry-release prepare <TARGET_SHA> <JENKINS_EXECUTION_ID>
donworry-release approve <BUNDLE_ID> <JENKINS_EXECUTION_ID> <APPROVED_BY>
donworry-release apply <BUNDLE_ID> <JENKINS_EXECUTION_ID>
donworry-release inspect <BUNDLE_ID> <EXECUTION_ID>
donworry-release reconcile <BUNDLE_ID> <EXECUTION_ID> <OPERATOR> --against current|target --evidence <INCIDENT_ID>
```

1. `prepare`: 기존 배포 기록·DB·main 확인 → `prod`를 한 번 조회해 marker digest 고정 →
   스키마 1·SHA·서비스 index·실행 플랫폼 child manifest·revision 확인 → Compose 구성 저장.
   컨테이너 변경과 서비스 이미지 다운로드는 하지 않는다.
2. Jenkins: VM 응답의 전체 묶음 ID·SHA·marker/index/child/config digest·구성 해시를
   artifact와 승인 화면에 제시한다. `input`의 `submitterParameter`로 실제 승인자를 받는다.
   실행 ID는 Jenkins 빌드 번호와 UUID로 구성한다. Job URL과 빌드 번호는 Jenkins 기록으로 연결된다.
   시간 제한은 전체가 아닌 단계별로 둔다: 준비 30분(공통 잠금 최대 5분 포함), 승인 대기 30분(초과 시 승인 기록 없이 종료),
   승인 기록 10분, 적용 150분. 늦은 승인이 적용 시간을 줄여 배포 도중 중단되지 않게 한다.
3. `approve`: 묶음·저장 Compose·현재 구성이 같을 때 승인자·시각·실행 ID를 VM에 새로 기록한다.
   구성이나 대상이 바뀌면 새 `prepare`와 새 승인을 받는다. 승인을 덮어쓰지 않는다.
4. `apply`: 공통 잠금 획득 → 승인·실행 ID·미완료 기록 확인 → 지속 실행 기록 생성 →
   고정 marker·index·child·revision 재검증 → digest로 pull → 구성·DB·marker·이미지·플랫폼·
   로컬 이미지 재검증 → 이전 배포 확인·DB 백업·DB 지문/최종 검증 → 마지막 main 조회 → 적용 단계 기록 → 저장된 Compose로
   backend·ai·nginx만 `up --no-deps --no-build --pull never`.
   이미지 ID는 Docker 이미지 저장 방식에 따라 다르다. 기존 저장소는 config digest,
   containerd 이미지 저장소(`docker info`의 `driver-type io.containerd.snapshotter.*`)는
   pull한 index digest와 비교한다. 실행 컨테이너의 `Image`도 같은 기준으로 대조한다.
   승인 뒤 태그로 이미지를 다시 선택하지 않는다.
5. Compose 대기 → 실제 컨테이너 config ID와 실행 ref 비교 → Nginx `/healthz`,
   backend `/api/auth/me`의 200/401/403, AI `/health` 확인 → 승인된 핵심 기능 검사 → 승인 digest의 로컬 SHA 태그 지정·대조 →
   `.env`·`backups/deployed-image-tag` 갱신 → 성공 기록.

`apply`는 root 소유 `/srv/donworry/ENABLE_UPDATE_CD` 활성화 파일이 없으면 차단한다.
**이 파일 생성·서버 설치·Jenkins Job 전환은 실제 VM 검증과 별도 사용자 승인 후 수행한다.**
이번 PR 자체는 활성화하지 않는다. #482~#484의 운영 중단·복구·권한 검토가 끝나기 전에
운영 업데이트를 실행할 준비가 완료됐다고 판단하지 않는다.

## 구성과 실행 기록

`/srv/donworry/releases/<BUNDLE_ID>/`에 다음을 보관한다.

| 파일 | 내용 |
| --- | --- |
| `bundle.json` | SHA·marker·index·child·config digest·플랫폼·실행 ID·시각·구성 버전 |
| `compose.json` | 승인한 구성과 digest 3개로 고정된 실행 Compose. 환경 비밀값 포함 |
| `approval.json` | 묶음 ID·실행 ID·승인자·승인 시각 |
| `result.json` | 실행 ID·묶음·작업 종류·승인·PID/boot ID·시각·단계/이력·결과 (`running`, `success`, `failed`)·배포 기록 갱신 전후 태그/파일별 상태·예상/성공 후 구성 해시 |
| `resolution.json` | 운영자·점검 근거 ID·실제 확인 묶음·원래 결과 해시·확인 시각 (명시적 상태 확인 때만) |
| `database.sql` | 배포 전 DB 덤프. root 전용·Jenkins artifact 업로드 금지. 존재만으로 복원 검증이 아님 |
| `recovery-approval.json` | 실패 결과 해시·직전 묶음·운영자·근거·복구 승인 시각 |
| `recovery-attempt.json` | 복구 승인에 연결된 별도 복구 묶음 ID. 승인당 서비스 적용 1회 |

묶음 ID는 전체 `bundle.json`의 SHA256이다. 저장 Compose 해시도 묶음에 포함한다.
구성 버전은 원본 `.env`·`compose.yaml`·명령 구현 파일·해석된 Compose·읽기 전용 bind 파일의
해시다. DB named volume 데이터는 구성 해시에 넣지 않는다. 미지원 서비스, 쓰기 가능한 bind,
심볼릭 링크 bind, 외부 Compose secrets/configs는 별도 검토 전 차단한다.
Compose `config`의 `$` 이스케이프를 그대로 보관해 환경값을 이중 변환하지 않는다.

root umask 077로 저장하며 **releases 디렉터리·명령·Compose·환경파일·bind 원본은 root가
소유하고 Jenkins SSH 계정에 직접 쓰기 권한을 주지 않는 것**을 설치 전제로 한다.
저장 Compose나 원격 명령의 상세 출력은 Jenkins로 반환하지 않는다.
Jenkins에는 digest·해시·식별자만 있는 요약을 보관한다. 같은 호스트의 root나 허용된 배포
계정을 공격자로부터 격리하거나 승인자를 암호학적으로 증명하는 시스템은 아니다.
승인자 값은 Jenkins의 인증된 `input` 결과를 신뢰하므로 Job 설정·credential·sudo 경계는
#484에서 확인해야 한다. 광범위한 shell/python/sudo 권한은 부여하지 않는다.

성공 시 `.env`의 `IMAGE_TAG`와 `backups/deployed-image-tag`를 `sha-<승인 SHA>`로 갱신한 뒤
`releases/current.json`에 마지막 성공 묶음을 기록한다. Compose·실행 이미지·health가 모두
통과하기 전에는 두 파일을 바꾸지 않는다. `IMAGE_TAG=` 줄은 정확히 하나여야 하며 그 값만
바이트 단위로 교체한다. `$`, CRLF, 마지막 줄바꿈 유무 등 다른 바이트는 보존한다.
각 파일은 같은 폴더의 root:root 0600 임시 파일에 쓰고 fsync → rename → 폴더 fsync로 교체한다.
두 파일을 함께 교체하는 원자적 트랜잭션은 아니다.

`result.json`의 `deployment_records`에는 `before`·`intended`·실제 관측 `after` 태그와
파일별 `unchanged`/`updated`/`unknown`을 남긴다. 쓰기 전 지속 기록은 `writing`이며, 강제 종료로
이 상태가 남으면 실제 파일을 점검해야 한다. `updated`는 읽어 확인한 내용이며, 실패 실행에서
전원 중단 내구성까지 보장하는 뜻은 아니다. 다른 `.env` 값은 기록하지 않는다.
같은 SHA로 내용이 같을 때도 태그 지정 실패로 쓰기를 시작하지 않은 파일은 `unchanged`다.
내용이 같은 파일의 교체는 rename 직후 확인한 진행 상태로 판별한다. 쓰기를 시작했으나
교체 여부를 확정할 수 없으면 `unknown`으로 남긴다.
부분 갱신·로컬 태그 지정/검증·구성 해시 저장 실패도 `failed`·`PAUSE_CD`를 남긴다.
컨테이너는 이미 새 버전일 수 있으므로 자동 되돌리기나 재실행을 하지 않는다.
기존 승인 snapshot은 보존한다. root 전용 임시 환경파일로 `IMAGE_TAG`만 변경한 Compose 구성을
계산하고 `planned_configuration`을 두 파일 교체 전에 지속 기록한다. 다른 원본/bind 해시는
승인 구성과 같아야 한다. 실제 갱신 후 구성은 이 예상값과 대조하고 `published_configuration`에 저장한다.
다음 `prepare`는 갱신된 두 기록을 검사하고 변경된 `.env`를 새 승인 구성에 포함한다.

원본 Compose를 수동 실행할 때 옛 버전으로 돌아가지 않도록, 두 파일 교체 직전에 이미 pull한
승인 digest에 로컬 `sha-<SHA>` 태그를 붙이고 이미지 ID를 다시 대조한다. 원격 SHA 태그는
재조회하지 않는다. 이 로컬 태그는 이후 원격 pull·다른 태그 명령으로 바뀔 수 있다.
승인 digest를 계속 보장하려면 성공 묶음의 `compose.json`을 사용한다. 수동 업데이트도
위 `prepare`/`approve`/`apply` 경로를 사용한다.

기존 `deploy-donworry`는 수정하지 않는다. 2026-11-03 09:55 버전으로 제공된 Mac 사본의
`check_deployment_state`·`set_image_tag`·성공 기록 함수가 `sha-<40자리 SHA>`와 두 값의 일치를
사용하므로 파일 형식은 호환된다. 이 작업에서 확인한 것은 사본 코드이며 현재 VM 원본의
동일성·실제 실행 호환은 미검증이다. 사본의 CLI는 기존 기록이 있으면 최초 배포를 거절한다.

다운로드·재검증 중 실패도 `PAUSE_CD`와 실패 기록을 남긴다. 자동 재시도/rollback하지 않는다.
미해결 `running`/`failed` 또는 이전 버전의 `applying`, 손상된 결과는 다음 배포를 차단한다.
SIGKILL·전원 중단으로 PAUSE_CD가 없어도 미완료 기록으로 차단한다.

## 공통 변경 경로와 제한 시간

Jenkins와 수동 명령은 동일한 root 명령을 사용한다. `Release` 메서드 자체가
`/run/lock/donworry-deploy.lock`을 잡으므로 CLI를 통하지 않는 호출도 직렬화한다.
잠금은 최대 300초 기다리며, 획득 후 상태/승인을 다시 확인한다. apply는 다운로드부터
결과 저장까지 유지한다. 승인 대기에는 잡지 않는다. **잠금 파일을 삭제하지 않는다.**

로컬 `Desktop/donworry-cd-review/deploy-donworry` 사본을 다시 확인한 결과 같은 잠금
파일을 사용하며 최초 배포만 실행한다. `backups/deployed-image-tag`가 있으면 거절한다.
그 안의 복원 helper는 업데이트/복구 명령으로 연결돼 있지 않다. 현재 VM 원본과의 일치 및
다른 수동/cron 실행 경로 부재는 설치 전 확인이 필요하다. 원본 Compose 직접 up,
helper 직접 호출, 별도 복구 shell을 업데이트 경로로 사용하지 않는다.
#483에서 복구를 구현할 때는 `exclusive` + `operation(kind='recovery')`로 이 잠금·기록
경계를 재사용해야 한다. 복구 대상 선정·DB 백업/복원 코드는 이번 작업에 추가하지 않는다.

명령 제한은 main/registry 조회 120초, 서비스별 pull 600초, 로컬 inspect/config/ps 30초,
Compose up 360초(내부 wait 240초), health curl/exec 각 10초다. 변경 명령·pull·원격 조회는
자동 재시도하지 않는다. 읽기 전용 health만 4초 간격 최대 60회, 전체 300초 예산으로
재시도한다(마지막 시도 최대 30초 추가). timeout 시 자식 그룹 TERM → 5초 → KILL/회수다.
백업은 900초, 승인된 기능 검사는 60초이며 둘 다 재시도하지 않는다.
DB 복원 명령은 제공하지 않는다. 세부 정책은 ADR-0077을 따른다.

명령 자식이 잠금 fd를 상속하고 별도 세션에서 실행된다. SSH SIGHUP은 무시한다.
Jenkins 취소·SSH keepalive 실패(15초 간격 3회)는 VM 작업 종료를 의미하지 않는다.
부모 프로세스가 강제 종료돼도 살아 있는 자식이 잠금을 유지할 수 있다. Docker daemon은
CLI 종료 뒤에도 변경할 수 있으므로 잠금이 풀렸다는 이유만으로 다음 배포를 강행하지 않는다.

## 중단·부분 배포 후 확인 절차

1. Jenkins의 `release-bundle.json`에서 묶음/실행 ID를 확인한다. 새 apply·기존 승인 재시도·
   잠금 파일 삭제·result 삭제를 하지 않는다. 비밀값이 있는 compose.json은 로그에 붙이지 않는다.
2. `donworry-release inspect <BUNDLE_ID> <EXECUTION_ID>`로 같은 잠금 아래 결과와 실제
   컨테이너별 image ID/ref·running·health를 확인한다. 잠금 대기 초과는 작업 진행/정체의
   신호다. SSH 성공 여부나 PID 하나만으로 완료를 추정하지 않는다.
3. 단계·PID/boot ID·시각을 VM 프로세스/서비스 및 Docker 상태와 대조한다. reboot이면
   boot ID가 달라지며 디스크의 running 기록은 그대로 차단 근거다. Docker daemon에서
   이전 작업이 끝났는지, Flyway migration 실패/진행/부분 적용과 DB·업무 영향이 없는지
   운영자가 점검한다. DB health는 DB 호환성이나 migration 성공 증거가 아니다.
4. 혼합 digest·컨테이너 누락·health 실패·DB/구성 불명·조회 오류면 PAUSE_CD를 유지하고
   운영자 복구(#483)로 넘긴다. 최초 성공 배포가 digest 묶음으로 등록되지 않았다면
   `--against current` 확인은 불가능하다. 자동으로 예전 태그를 선택하지 않는다.
5. 이전에 기록된 성공/확인 묶음 전체가 실제 상태와 일치하면 `--against current`, 중단된
   승인 대상 전체가 일치하면 `--against target`을 **운영자가 명시**한다. 진행 작업 종료·
   DB/Flyway·업무 영향의 점검 근거를 Incident/작업 기록에 보관한 다음 아래 명령을 실행한다.
   담당 권한과 실제 운영 점검은 #483·#484에서 확정/검증한다.

   ```text
   donworry-release reconcile <BUNDLE_ID> <EXECUTION_ID> <OPERATOR> --against target --evidence INC-123
   ```

   evidence에는 비밀값 없는 기록 식별자만 넣는다. 명령은 근거의 내용을 자동 판정하지 않는다.
   구성·DB health·전체 이미지·기동 검사와 두 배포 기록의 대상 SHA 일치 검사,
   세 서비스의 로컬 SHA 태그 이미지 ID 대조를 통과해야 resolution.json과 current.json을
   저장하고 PAUSE_CD를 해제한다. 원래 실패/중단 결과는 보존하며 배포 성공으로 고치지 않는다.
   쓰기 중 끊기면 같은 인자로 다시 검사할 수 있다. 다음 배포는 새 준비·승인이 필요하다.
   `current`·`target` 모두 로컬 SHA 태그를 대조한다. 같은 SHA 재빌드도 다른 digest일 수 있으므로
   태그 누락·일부 불일치가 있으면 PAUSE_CD를 유지한다. reconcile은 태그를 다시 붙이지 않는다.

성공 후 변경된 `.env`는 `published_configuration`으로 대조한다. 두 파일 교체 직후 중단되어
이 값이 없으면 교체 전 저장한 `planned_configuration`으로도 대조할 수 있다. 임의의 현재 구성을
새 기준으로 받아들이지 않는다. 구성·두 기록·실행 이미지·로컬 SHA 태그가 모두 맞을 때만
확인을 허용하며 `reconcile`은 파일이나 태그를 바꾸지 않는다. 일부만 바뀐 기록이나 승인/예상
구성과 맞지 않는 상태는 #483 운영자 복구 대상으로 남긴다.

result 단계는 `validating → pulling → final_validation → applying → checking → publishing_records → publishing → completed`다.
result/current 변경은 파일 fsync·rename·디렉터리 fsync로 저장한다.
`publishing`에서 중단돼 current가 새 묶음을 가리켜도 미완료 result가 다음 배포를 막는다.

## 검증과 실제 적용 전 남은 일

```bash
python3 -m unittest discover -s ops/vm -p 'test_*.py' -v
python3 -m unittest discover -s .github/scripts -p 'test_release_*.py' -v
python3 .github/scripts/check_md_links.py
# 선택: 새 로컬 MariaDB 컨테이너의 fixture만 덤프/복원하고 컨테이너를 정리한다.
DONWORRY_DB_TEST=1 python3 -m unittest discover -s ops/vm -p 'test_database_integration.py' -v
```

첫 명령은 Docker/Git/Compose 서비스 변경을 대체한다. 별도의 Compose 왕복 테스트는
Docker daemon 없이 실제 Compose CLI로 `$` 포함 환경값과 bind 경로·서비스 구성 hash를
검사한다. CLI가 없으면 해당 테스트만 skip되며 VM 검증을 뜻하지 않는다.

#482 로컬 검사는 잠금 대기 후 stale main 차단, 배포 중 수동/복구 잠금 경합,
pull 실패 기록, 부분 이미지 확인 거절, 운영자 근거를 남긴 해제/재실행을 대역으로 검사한다.
실제 로컬 자식 프로세스로 SIGKILL 후 지속 기록과 다음 실행 차단, 부모 종료 뒤 자식의
잠금 유지, subprocess timeout도 재현한다. 이는 Linux VM reboot/실제 SSH 단절 검증이 아니다.

#499 대역 테스트는 다른 SHA로 두 기록 갱신, 비밀값·임의 바이트 보존, 0600과 root chown 요청,
Compose/이미지/health 실패 시 기록 보존, 태그 줄 0개/중복 거절, 파일별 쓰기/rename 후 fsync 실패,
로컬 태그 실패, 갱신 후 다음 prepare를 검사한다. chown syscall은 대체하므로 실제 root 소유권은
VM 검증이 필요하다. 실제 VM 갱신·기존 스크립트 실행·운영 활성화는 수행하지 않는다.


실제 적용 전 검증·승인이 필요한 항목:

- #480의 main push 이미지 발행·스키마 1 marker와 서비스 revision 실검증. 기존 SHA-only
  marker는 거절되며 최초 배포 SHA로 v1 승인을 자동 생성하지 않는다.
- VM 원본/사본 비교, root 파일 소유·권한·Python/Compose/Buildx 버전, GHCR 읽기 인증.
- 실제 pull 후 `docker image inspect`·`docker inspect`의 이미지 ID. VM1은 2026-11-03
  `docker info`의 `DriverStatus`에 `driver-type`이 없는 기존 저장소(config digest 비교)임을 확인했다.
  기존 저장소 경로는 대역 테스트, containerd 경로는 대역 테스트와 로컬 Docker로만 확인했다.
- Jenkins 새 Job에서 이 Jenkinsfile의 Groovy 해석, `input` 승인자 반환과 SSH/sudo 계약.
  Jenkins 접근이 없어 이 부분은 로컬 테스트로 검증하지 못했다.
- 격리 환경에서 실패 차단과 정상 실행 이미지를 재현한 뒤 실제 Jenkins 실행 링크와
  VM 결과 기록. #482~#484 검토 및 실제 활성화 승인은 별도.
- Jenkins 취소/SSH 단절 후 작업 진행·잠금·디스크 기록 대조, VM 재시작·서비스 일부 교체 후
  미완료 탐지/점검/해제 절차, 실제 수동 실행/복구 경합. VM 원본 잠금 경로·다른 변경 경로와
  root 기록 권한을 확인하고 실행 링크·대상 SHA·근거를 남긴다. 로컬 대역 통과로 체크하지 않는다.
- 마지막 HEAD 조회와 서비스 변경은 원자적이지 않다. 조회 성공 후 적용을 시작하며,
  그 이후 main 변경은 다음 실행에서 처리한다. #461 게시 시점 최신성 보장은 별개다.

## #483 최초 등록·백업·이미지 복구

다음 명령은 서버에서 운영자가 실행할 절차다. 이 문서를 읽거나 로컬 테스트를 실행하는 것으로
서버에 설치/활성화되지 않는다. 모든 명령은 같은 공통 잠금을 사용한다.

### 적용 전 준비

1. VM 원본·파일 소유/권한·도구 버전·GHCR 인증을 확인하고 새 명령을 설치한다.
   `.env`, Compose, 읽기 전용 bind, 명령, 다음 기능 검사 파일은 root 소유이며 배포 계정이
   쓸 수 없어야 한다. 파일 내용을 Jenkins 로그에 붙이지 않는다. #484의 Job/sudo 검토도 필요하다.
2. 승인된 테스트 환경·데이터와 핵심 기능 시나리오를 합의한다. root 전용
   `/srv/donworry/functional_check.py`에 그 검사를 구현하고 성공일 때만 종료 코드 0을 반환한다.
   `/srv/donworry/functional-check.json`에는 아래 계약을 저장한다. 이는 아직 합의되지 않은
   실제 로그인/결제 시나리오를 대신 승인하는 예제가 아니다. 검사 파일·정책이 없거나 변경되면
   기존 승인으로 배포할 수 없다. 정책은 배포 적용 전, 실제 검사는 health 후에 확인한다.

   ```json
   {"environment":"approved-test","test_data":"TEST-DATA-ID","evidence":"APPROVAL-ID"}
   ```

   테스트 데이터의 범위·검사의 추가 파일/외부 자원도 승인 근거에서 통제한다. 실제 운영
   결제를 임의로 수행하지 않는다. 이 hook은 합의한 테스트 환경을 위한 것으로, 합의 없이
   정책값만 넣어 운영 배포를 활성화하지 않는다.
3. 현재 운영 컨테이너·SHA 기록·DB/Flyway·업무 상태를 확인하고 근거를 남긴 뒤 최초 후보를 등록한다.
   이 명령은 현재 컨테이너의 이미지 ID/RepoDigest와 registry revision을 대조하고 구성 snapshot을 만든다.
   main/prod marker를 현재 배포라고 추정하지 않으므로 구버전 SHA도 검증 가능하다.

   ```text
   donworry-release register-current <CURRENT_SHA> <REGISTRATION_ID> <OPERATOR> --evidence <INCIDENT_ID>
   ```

   **#480 이전에 빌드된 이미지**(예: 2026-11-03 운영 `sha-1514b3d`)에는 `org.opencontainers.image.revision`
   label이 없어 위 명령이 거절된다. 이때만 운영자가 `--without-revision`을 붙여 등록한다.

   ```text
   donworry-release register-current <CURRENT_SHA> <REGISTRATION_ID> <OPERATOR> --evidence <INCIDENT_ID> --without-revision
   ```

   label이 **없을 때만** 건너뛰며, 다른 SHA를 가리키는 label은 거절한다. 실행 이미지 ID·RepoDigest·
   원격 index/플랫폼 digest 대조는 그대로 한다. SHA는 `.env`·배포 기록과 운영자 근거로만 확인한 것이므로
   묶음에 `revision_verified: false`를 남기고, 이 묶음으로의 복구 승인에도 같은 값을 기록한다.
   새로 배포하는 이미지(#480 이후 빌드)는 항상 label을 검사한다.

   RepoDigest가 없거나 단일 manifest/플랫폼을 확정할 수 없으면 멈춘다. 현재 SHA 태그를 다시
   pull해서 후보를 만들지 않는다. 실제 이미지 추적과 별도 운영자 판단이 필요하다.
   등록 결과는 restore_verified=false이며, 서비스 교체나 과거 배포 성공 증명은 아니다.
   명령/설정 변경 후에는 미해결 실행이 없는 상태에서 같은 절차로 현재 구성을 재등록할 수 있다.
   기존 `reconcile`로 target을 확인한 묶음도 DB/업무 점검 근거를 남겨 다시 등록한 후 다음 배포를 진행한다.
4. `ENABLE_UPDATE_CD` 생성/운영 활성화는 별도 승인 대상이다. 활성화 전에 테스트 VM의 정상/실패
   경로와 실제 백업 파일을 별도 DB에 복원하는 검증을 수행한다. fixture 복원 통과만으로 대신하지 않는다.

### 배포 전 백업

새 prepare/approve/apply 경로는 현재 성공/등록 묶음의 구성·실행 이미지를 먼저 확인한다.
기능 검사 정책·구현이나 등록된 현재 묶음이 없으면 `prepare`·`apply`가 실행 기록을 만들기 전에
거절한다 (`failed`·`PAUSE_CD`를 남기지 않음).
pull 후 서비스 변경 전에 DB health, Flyway 성공 이력, 지원 DB 객체를 점검하고 백업한다.
백업 전후/최종 적용 전 DB 지문이 바뀌면 차단한다. 백업 중 DDL·다른 변경 경로를 실행하지 않는다.
비 InnoDB·뷰·루틴·이벤트는 별도 백업 정책 합의 전 차단한다.

`result.json.backup`의 `status`, `previous_bundle`, `database`, `digest`, `bytes`, 시각을 확인한다.
running은 강제 중단 가능성, failed는 명령/검증 실패다. 둘 다 복원 가능한 백업으로 취급하지 않는다.
원래 실패/부분 파일은 보존하며 데이터가 포함된 SQL은 이슈나 Jenkins에 올리지 않는다.
SQL은 배포 묶음 폴더에 보관하므로 저장 공간·보관 기간·접근 권한·별도 매체 보관을 운영자가 관리한다.

### 실패 후 이미지 복구

1. 기존 중단 확인 절차의 inspect, VM/daemon 작업 종료, Flyway·DB·업무 영향 확인을 수행한다.
   health/핵심 기능 실패는 자동 rollback 대신 failed/PAUSE_CD로 남는다.
2. 배포 전 백업 성공과 연결된 직전 묶음을 확인한다. DB 지문이 바뀌거나 migration 실패/부분 적용,
   호환성 불명, 이전 묶음 없음이면 자동 진행하지 않는다. 별도 운영자 판단으로 넘긴다.
3. **업무 데이터 호환성·기존 작업 종료·복귀 영향을 점검한 근거**를 남기고 이미지 복구를 별도 승인한다.

   ```text
   donworry-release approve-recovery <FAILED_BUNDLE_ID> <EXECUTION_ID> <OPERATOR> --evidence <INCIDENT_ID>
   donworry-release recover <FAILED_BUNDLE_ID> <EXECUTION_ID>
   ```

   approval은 실패 결과 해시와 이전 묶음을 고정한다. 다른 임의의 태그/묶음으로 바꾸지 못한다.
   recover는 승인된 이전 snapshot으로 앱 3개만 바꾼다. DB 서비스·SQL·볼륨을 복원하지 않는다.
   복구도 기동·핵심 기능·DB 지문 검사를 거친다. 원래 실패 결과와 복구 결과는 별개다.
4. 복구 반환의 bundle_id/execution으로 inspect하고 두 배포 기록·current·resolution·PAUSE_CD를 확인한다.
   성공 결과 후 current 게시만 끊겼다면 같은 recover가 상태를 재검사하고 게시만 마무리한다.
   실패/running 복구는 같은 승인으로 Compose를 다시 적용하지 않는다. 잠금/기록 삭제로 우회하지 않는다.
   원래 묶음에 해결되지 않은 복구 실행이 더 있으면 운영자가 모든 실행을 함께 확인해야 한다.

### DB 복원은 별도 판단

DB 복원 명령은 구현하지 않는다. 이미지 복구 승인으로 DB를 복원하면 안 된다.
복원이 필요하면 백업 시점 이후 데이터 손실 구간·영향 업무·업무 담당 승인·쓰기 중지 계획·
복원 파일 해시·별도 DB 복원 검증·전환/검사 계획을 먼저 기록한다.
Flyway repair/clean, DB 초기화, 볼륨 삭제를 이 절차의 대안으로 사용하지 않는다.

### 검증 범위

`test_recovery.py`는 registry/Docker/업무 검사 대역으로 등록·백업 실패·DB 지문 변경 차단·
별도 승인 복구·잠금 경합·복구 실패·게시 재개를 검사한다. 기존 배포 단위 검사는 추가 백업/기능
검사 경계만 대체하며 해당 연결은 이 별도 통합 대역 검사에서 검증한다.
`test_database_integration.py`는 새 로컬 MariaDB 컨테이너에 fixture DB만 만들고 실제 SQL
조회·덤프·별도 DB 복원·스키마 변경 탐지를 검사한다. 컨테이너에는 호스트 볼륨/포트/외부 네트워크를
연결하지 않으며 종료 시 제거한다. 실제 운영 백업 복원·업무 기능·Jenkins/VM 검증은 아니다.
실제 VM에서는 최초 후보·백업 권한/용량·별도 복원·health/합의한 기능 실패·복구 경합/실패와
#482의 중단 실험을 검증하고 Jenkins 링크·SHA·근거를 남긴다. 이 전까지 #483을 완료로 닫지 않는다.
