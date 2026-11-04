# 0074. Jenkins가 승인한 릴리스 묶음으로 VM 업데이트

- **상태:** 제안 — #481 구현·PR 리뷰 대상, 운영 설치·실검증 전
- **날짜:** 2026-11-03 (2026-11-04 개정: #499 배포 성공 후 기록 일치)
- **관련:** #448 B (#481), #480, #482, #483, #484, #499, [ADR-0073](0073-release-bundle-metadata.md)
- **영향 받는 담당:** P1, Jenkins·VM 운영 담당

## 배경

사용자 확인에 따르면 VM1은 2026-11-03 root가 최초 배포 스크립트를 수동 실행한 상태다.
Jenkins는 SHA 검증·관리자 승인·SSH 읽기 전용 점검까지만 검증됐다. 업데이트 경로는 없다.
현재 운영 Compose는 SHA 태그를 사용하므로 같은 SHA 재빌드의 승인 대상을 구별하지 못한다.
원본 위치·사본 해시와 확인 범위는 [VM CD 안내](../../ops/vm/README.md)에 기록한다.

## 결정

Jenkins에서 사람이 TARGET_SHA로 실행하고 관리자가 **고정된 전체 묶음**을 승인한 뒤
VM root 명령이 검증·업데이트를 자동 실행한다. main 병합 자동 시작은 도입하지 않는다.
기존 Dry-run Job·최초 배포 스크립트를 교체하지 않고 별도 Job과 명령으로 제안한다.

승인 대상은 SHA·marker digest·서비스 index/플랫폼 child/config digest·VM 플랫폼·실행 ID·
생성 시각·구성 버전이다. index에서 attestation을 제외한 실행 플랫폼 manifest를 하나만
선택하고 child config의 revision·플랫폼을 대조한다. 실제 pull 뒤 로컬 이미지 ID도 대조한다
(기존 저장소는 config digest, containerd 이미지 저장소는 index digest).
원격 manifest 원문을 SHA256으로 대조하며, marker config는 Buildx `.Image`로 읽는다.
동일 SHA 재빌드나 mutable 태그 변경에도 승인 이후 저장된 digest만 사용한다.

승인할 구성을 Docker Compose `config --format json`으로 해석해 VM에 보관한다.
비밀값이 포함되므로 root 전용으로 저장하고 Jenkins에는 해시·식별자만 반환한다.
원본 파일·해석된 구성·bind 내용·명령 구현 변경은 새 준비·승인을 요구한다.
서비스 이미지 3개를 index digest로 고정하고 플랫폼을 명시한다. DB를 바꾸지 않도록
`up --no-deps --no-build --pull never`로 앱만 적용한다.

### 배포 성공 후 기존 기록 갱신 (#499)

2026-11-03 결정에 따라 Compose 기동·실행 이미지·health가 모두 통과한 뒤 `.env`의
`IMAGE_TAG`와 `backups/deployed-image-tag`를 `sha-<승인 SHA>`로 갱신한다. 옛 기록을 보존하면
원본 Compose의 수동 `up`이 경고 없이 옛 버전을 다시 선택하기 때문이다.
`IMAGE_TAG=` 줄은 정확히 하나여야 하고 값 외의 바이트는 그대로 유지한다.
두 파일은 각각 같은 폴더 임시 파일(root:root 0600) → 파일 fsync → rename → 폴더 fsync로
교체한다. 둘 사이 중단은 가능하며 자동으로 되돌리지 않는다. 모든 갱신 후에만 current를 게시한다.

파일 교체 전에 이미 pull한 승인 digest를 로컬 `sha-<SHA>` 태그로 지정하고 이미지 ID를
검증한다. 같은 SHA를 재빌드했더라도 수동 Compose의 기본 로컬 이미지 선택이 승인 이미지와
일치하게 하려는 조치다. 원격 태그를 다시 pull하지 않는다. 이후 명시적 pull·다른 태그 지정은
로컬 태그를 바꿀 수 있으므로 지속적인 digest 보장 수단은 저장된 `compose.json`이다.

태그 지정·두 파일 교체·성공 후 구성 해시 저장 실패는 컨테이너가 새 버전이어도 `failed`와
`PAUSE_CD`로 차단한다. result에는 갱신 전/의도한/관측한 IMAGE_TAG와 파일별 상태만 남기고
다른 환경값은 남기지 않는다. 강제 종료 중 `writing`은 실제 파일 점검이 필요한 상태다.
같은 SHA여서 전후 파일 내용이 같으면 내용 일치만으로 `updated`라 판단하지 않는다.
쓰기 미시작은 `unchanged`, rename 완료를 확인한 교체는 `updated`, 시작했으나 교체 여부를
확정할 수 없는 경우는 `unknown`으로 남긴다.
기존 snapshot은 수정하지 않는다. 두 기록 교체 전에 root 전용 임시 환경파일로 변경 후 구성을
계산해 `planned_configuration`을 지속 기록한다. `.env` 외 원본/bind 해시는 승인 구성과 같아야 한다.
실제 교체 후 구성은 예상값과 대조하고 `published_configuration`으로 기록한다.
다음 prepare는 새 두 기록을 대조하고 새 구성으로 승인받는다. reconcile은 성공 후 구성 해시나
교체 전 저장한 예상 구성 해시를 사용한다. 두 파일 교체 직후 SIGKILL되어 성공 후 해시가 없어도
복구 확인이 가능하며, 임의의 현재 구성을 새 기준으로 채택하지 않는다. 두 기록이 확인 대상
SHA와 다르면 차단을 유지한다. 부분 기록 복구는 #483 범위다.
또한 `current`·`target` 모두 세 서비스의 로컬 SHA 태그가 확인 대상 이미지 ID와 같은지
대조한다. 같은 SHA 재빌드 뒤 태그 지정이 실패한 상태를 해제하면 수동 Compose가 이전
이미지를 선택할 수 있기 때문이다. 누락·일부 불일치 시에도 차단하며 reconcile은 태그를 바꾸지 않는다.

기존 `deploy-donworry`는 변경하지 않는다. 제공된 11-03 09:55 Mac 사본의 파일 읽기/태그 형식
검사는 호환되지만, 현재 VM 원본 대조와 실제 실행은 미검증이다.

**배포 시작**은 잠금 획득·고정 이미지 다운로드·구성/플랫폼/DB/이미지 재검증을 끝내고
마지막 main HEAD 대조에 성공한 후 적용 시작 기록을 저장하는 시점이다.
그 이후 main 변경은 다음 배포로 처리하며 실행 중 새 대상을 고르지 않는다.
최종 HEAD 조회와 Compose 변경은 원자적이지 않다. 엄밀한 최신성을 원하면 모든 main 변경
경로와 배포를 조정하는 추가 설계가 필요하다. #461의 게시 시점 최신성을 충족했다고 보지 않는다.

실패/미완료 기록은 후속 배포를 차단하며 적용 후 실패는 PAUSE_CD를 남긴다.
공통 잠금·중단 복구 완성은 #482, DB 백업·롤백은 #483, 승인 Job·sudo 권한은 #484다.
이번 PR은 ENABLE_UPDATE_CD 파일이 없는 환경에서 실제 적용을 차단하며, 운영 활성화는
후속 검증과 별도 사용자 승인 후 수행한다. 기능 구현만으로 운영 CD 완료를 뜻하지 않는다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 수동 VM 명령만 추가 | Jenkins 수동 시작·승인 후 실제 CD 실행이라는 사용자 목표에 못 미침 |
| main 병합 때 운영 배포 자동 시작 | 아직 확정하지 않은 운영 방식이며 이번 범위를 넘음 |
| Jenkins 승인 후 prod·SHA 태그를 다시 조회 | 승인과 다른 빌드를 섞을 수 있음 |
| index만 보고 revision을 확인 | provenance index·attestation과 실행 이미지 config를 혼동함 |
| 플랫폼 child digest만 승인 저장 | 발행자가 제공한 index와의 소속 관계를 잃음 |
| 승인 뒤 원본 Compose를 그대로 적용 | 구성 변경·태그 선택을 재사용할 수 있어 승인 대상을 고정하지 못함 |
| 기존 최초 배포 함수를 업데이트에 재사용 | 이미 배포된 DB·기록을 최초 배포 전제로 처리함 |
| 실패 시 자동으로 이전 태그 재실행 | DB 마이그레이션과 mutable 태그 때문에 복구 가능성을 보장하지 못함 (#483) |
| 대안 B: 옛 IMAGE_TAG·성공 기록을 보존하고 새 경로만 쓰도록 운영 규칙으로 제한 | 2026-11-03 결정으로 버림. 수동 Compose나 기존 경로를 실행했을 때 조용히 옛 버전으로 돌아가는 위험을 사람이 계속 피해야 함 |
| 파일 기록만 갱신하고 로컬 SHA 태그는 그대로 두기 | 같은 SHA 재빌드/기존 로컬 태그가 승인 digest와 다를 수 있어 수동 Compose의 이미지 선택이 달라짐 |
| 원격 SHA 태그를 다시 pull해서 로컬 태그 맞추기 | 승인 뒤 mutable 태그를 다시 선택하므로 승인하지 않은 빌드가 섞일 수 있음 |

## 영향

- API·DB 스키마·화면: 변경 없음. 업데이트 앱 기동 시 기존 Flyway 실행 가능성은 있어
  DB 백업/복원 검토 없이 운영 업데이트를 승인하지 않는다.
- 운영: 새 Jenkinsfile·VM 명령, 승인/실행 기록, root 전용 Compose snapshot.
  #499는 성공 후 `.env`·배포 기록·로컬 SHA 태그를 갱신한다. 실제 VM 설치/활성화는 포함하지 않는다.
- 테스트: stale marker·main 변경·revision/digest/platform 불일치·조회 실패·태그 변경·
  승인 재사용·구성 변경 차단, 정상 digest 적용·실행 이미지 대조, Compose 값 왕복.
- 운영 문서: [VM CD 안내](../../ops/vm/README.md), [릴리스 계약](../ci/08-vm-release-marker.md).

## 검증

명령과 실제 실행 결과는 PR에 기록한다. Docker/Git 대역 테스트와 실제 Compose CLI 해석을
구분한다. 실제 GHCR 조회·Jenkins Groovy/승인/SSH 실행·VM 업데이트·복구는 미검증이다.

참고: Docker [imagetools inspect](https://docs.docker.com/reference/cli/docker/buildx/imagetools/inspect/),
[Compose up](https://docs.docker.com/reference/cli/docker/compose/up/),
Jenkins [Input Step](https://plugins.jenkins.io/pipeline-input-step/).
