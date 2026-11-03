# VM1 업데이트 CD (#481 / PR B)

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

- [release.py](release.py): VM root 전용 `prepare` / `approve` / `apply` 명령.
- [Jenkinsfile](Jenkinsfile): 기존 SSH credential·known_hosts를 사용하는 새 수동 실행 Job 제안.
- [test_release.py](test_release.py): 명령 대역 테스트와 Docker Compose CLI의 구성 왕복 검사.
- [ADR-0074](../../docs/decisions/0074-approved-release-update.md): 승인 대상·최신성 시점·버린 대안.

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
```

1. `prepare`: 기존 배포 기록·DB·main 확인 → `prod`를 한 번 조회해 marker digest 고정 →
   스키마 1·SHA·서비스 index·실행 플랫폼 child manifest·revision 확인 → Compose 구성 저장.
   컨테이너 변경과 서비스 이미지 다운로드는 하지 않는다.
2. Jenkins: VM 응답의 전체 묶음 ID·SHA·marker/index/child/config digest·구성 해시를
   artifact와 승인 화면에 제시한다. `input`의 `submitterParameter`로 실제 승인자를 받는다.
   실행 ID는 Jenkins 빌드 번호와 UUID로 구성한다. Job URL과 빌드 번호는 Jenkins 기록으로 연결된다.
3. `approve`: 묶음·저장 Compose·현재 구성이 같을 때 승인자·시각·실행 ID를 VM에 새로 기록한다.
   구성이나 대상이 바뀌면 새 `prepare`와 새 승인을 받는다. 승인을 덮어쓰지 않는다.
4. `apply`: 승인과 실행 ID 확인 → 고정 marker·index·child·revision 재검증 → digest로 pull →
   로컬 config ID·플랫폼·revision 확인 → 구성·DB·로컬 이미지 재검증 → 마지막 main 조회 →
   적용 시작 기록 → 저장된 Compose로 backend·ai·nginx만 `up --no-deps --no-build --pull never`.
   승인 뒤 태그로 이미지를 다시 선택하지 않는다.
5. Compose 대기 → 실제 컨테이너 config ID와 실행 ref 비교 → Nginx `/healthz`,
   backend `/api/auth/me`의 200/401/403, AI `/health` 확인 → 성공 기록.

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
| `result.json` | 적용 시작/완료 시각·승인 기록·결과 (`applying`, `success`, `failed`) |

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

성공 시 `releases/current.json`은 마지막 성공 묶음을 가리킨다. 원래 `.env`의 `IMAGE_TAG`,
최초 성공 기록, 기존 최초 배포 스크립트는 변경하지 않는다. 업데이트 이후 운영 Compose
명령은 해당 성공 묶음의 `compose.json`을 명시해야 한다. 원본 Compose를 그대로 `up`하면
구 SHA 태그를 다시 선택하므로 운영 명령 경로 정리는 #482에서 함께 확인한다.

적용 중 실패하면 `PAUSE_CD`와 실패 기록을 남기고 자동 재시도/rollback하지 않는다.
`applying` 또는 `failed` 기록이 하나라도 있으면 다른 업데이트도 차단한다. 기존 스크립트와
같은 `/run/lock/donworry-deploy.lock`을 명령 단위로 사용한다. Jenkins 승인 대기 동안에는
잠금을 잡지 않는다. 프로세스 강제 종료·SSH 단절·다중 경로 전체의 중단/재개 규약은 #482,
실제 DB 백업·복원과 복구 후보는 #483의 후속이다. 이 PR은 DB 백업을 수행하지 않는다.

## 검증과 실제 적용 전 남은 일

```bash
python3 -m unittest discover -s ops/vm -p 'test_*.py' -v
python3 -m unittest discover -s .github/scripts -p 'test_release_*.py' -v
python3 .github/scripts/check_md_links.py
```

첫 명령은 Docker/Git/Compose 서비스 변경을 대체한다. 별도의 Compose 왕복 테스트는
Docker daemon 없이 실제 Compose CLI로 `$` 포함 환경값과 bind 경로·서비스 구성 hash를
검사한다. CLI가 없으면 해당 테스트만 skip되며 VM 검증을 뜻하지 않는다.

실제 적용 전 검증·승인이 필요한 항목:

- #480의 main push 이미지 발행·스키마 1 marker와 서비스 revision 실검증. 기존 SHA-only
  marker는 거절되며 최초 배포 SHA로 v1 승인을 자동 생성하지 않는다.
- VM 원본/사본 비교, root 파일 소유·권한·Python/Compose/Buildx 버전, GHCR 읽기 인증.
- Jenkins 새 Job에서 이 Jenkinsfile의 Groovy 해석, `input` 승인자 반환과 SSH/sudo 계약.
  Jenkins 접근이 없어 이 부분은 로컬 테스트로 검증하지 못했다.
- 격리 환경에서 실패 차단과 정상 실행 이미지를 재현한 뒤 실제 Jenkins 실행 링크와
  VM 결과 기록. #482~#484 검토 및 실제 활성화 승인은 별도.
- 마지막 HEAD 조회와 서비스 변경은 원자적이지 않다. 조회 성공 후 적용을 시작하며,
  그 이후 main 변경은 다음 실행에서 처리한다. #461 게시 시점 최신성 보장은 별개다.
