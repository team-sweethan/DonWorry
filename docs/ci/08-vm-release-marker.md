# 08. VM 이미지와 릴리스 marker

[← CI 문서](README.md)

## 발행 순서

[VM Images](../../.github/workflows/vm-images.yml)는 검사 통과 후 backend·frontend·AI
이미지를 `sha-<SHA>`로 발행한다. GHCR 발행은 main push이면서
`DONWORRY_RELEASE_ENABLED == 'true'`인 경우에만 수행한다.
PR·수동 실행은 `prod`를 변경하지 않는다.

1. 서비스 이미지 3개에 `org.opencontainers.image.revision=<전체 SHA>` label을 붙이고
   provenance를 활성화해 빌드·업로드한다. 각 빌드 단계의 digest 출력을 받는다.
2. [메타데이터 검증](../../.github/scripts/prepare-release-metadata.sh)이 SHA와 digest
   3개를 모두 검증한 뒤 marker labels를 출력한다. 누락·형식 오류는 CI 실패이며
   marker 빌드·업로드와 `prod` 승격을 실행하지 않는다.
3. marker를 `donworry-release:sha-<SHA>`로 빌드·업로드하고 digest를 받는다.
4. [게시 스크립트](../../.github/scripts/publish-release-marker.sh)가 main HEAD를 조회한다.
5. 대상 SHA와 일치하면 준비된 digest를 `donworry-release:prod`로 게시한다.
   일치하지 않으면 건너뛰며, 조회·digest 형식·게시 오류는 CI를 실패시킨다.

최신 확인 이후에는 marker를 다시 빌드하거나 SHA 태그에서 대상을 다시 선택하지 않는다.
기존 workflow concurrency로 main push 실행을 직렬화한다. 별도의 발행 경로를
추가할 경우 같은 직렬화 범위에 포함해야 한다.

## 릴리스 묶음 계약 v1

marker는 provenance 없는 단일 image manifest다. 아래 값은 manifest annotation이
아닌 **image config의 labels**에 저장한다. SHA는 소문자 hex 40자리,
digest는 `sha256:` 뒤 소문자 hex 64자리만 허용한다. 공백·개행도 허용하지 않는다.

| Label | 값 |
| --- | --- |
| `org.opencontainers.image.revision` | 전체 커밋 SHA |
| `io.donworry.release.schema-version` | `1` |
| `io.donworry.release.backend.digest` | backend 빌드의 index digest |
| `io.donworry.release.frontend.digest` | frontend 빌드의 index digest |
| `io.donworry.release.ai.digest` | AI 빌드의 index digest |

저장소는 `ghcr.io/team-sweethan/donworry-backend`,
`ghcr.io/team-sweethan/donworry-frontend`, `ghcr.io/team-sweethan/donworry-ai`로 고정한다.
digest는 태그를 다시 조회하지 않고 같은 실행의 `build-push-action` 출력에서 받는다.
서비스 이미지는 provenance를 포함한 최상위 index digest를 기록하며,
플랫폼별 image manifest digest나 config digest와 구분한다.

묶음의 식별자는 marker digest다. 같은 SHA 재실행은 서비스 SHA 태그와 marker SHA
태그를 새 결과로 갱신하며, main 확인을 통과하면 `prod`도 새 묶음으로 갱신한다.
내용이 달라지면 marker digest가 달라진다. 내용까지 같으면 digest가 같을 수 있다.
중간 실패 시 서비스 태그 일부만 갱신되고 이전 marker가 남을 수 있으므로
태그 존재를 이번 실행의 성공 근거로 사용하지 않는다.

[#481](https://github.com/team-sweethan/DonWorry/issues/481)의 소비자는 `prod`를 읽은 뒤
marker digest로 고정하고 SHA·digest 3개를 승인 묶음으로 저장해야 한다.
승인 후 태그를 다시 조회해 대상을 선택하지 않는다. 스키마 누락·알 수 없는 버전·
필수 값 누락은 배포 전에 거절한다. 기존 SHA-only marker로의 자동 대체는 하지 않는다.
고정된 index 안의 실행 플랫폼 선택·revision 대조는 #481 범위이며 아직 구현되지 않았다.
이 계약만으로 원격 이미지 존재·플랫폼·revision 일치를 검증했다고 간주하지 않는다.
digest 고정은 원격 이미지의 보존 기간도 보장하지 않는다.

## 보장 범위와 실제 배포

main 변경과 GHCR 게시를 원자적으로 묶지는 않는다. 최종 조회 뒤 main이 바뀌면
이전 SHA가 `prod`로 게시될 수 있다. 또한 새 main의 검사·빌드가 끝나기 전에는
이전 릴리스 후보가 남는다. `prod`만 보고 최신 main의 배포 허가로 판단하지 않는다.

실제 배포 전 승인 SHA·main HEAD·marker revision·이미지 3개의 revision을 대조하고
검증된 digest만 Compose에 적용하는 작업은 [#448](https://github.com/team-sweethan/DonWorry/issues/448)이다.
현재 저장소에는 해당 Jenkins·VM 검증 코드가 없어 이 문서는 구현 완료를 뜻하지 않는다.
배포 시작 이후 main 변경에 대한 정책도 #448에서 확정한다.

설계 근거와 버린 대안은 [ADR-0069](../decisions/0069-release-marker-publication.md)를 따른다.
릴리스 묶음의 형식과 소비 계약은 [ADR-0073](../decisions/0073-release-bundle-metadata.md)를 따른다.
엄밀한 게시 시점 최신성 또는 완료 기준 변경에 대한 합의 전에는 #461을 닫지 않는다.

## 로컬 검사

```bash
python3 -m unittest discover -s .github/scripts -p 'test_release_*.py' -v
for script in .github/scripts/*.sh; do bash -n "$script"; done
python3 .github/scripts/check_md_links.py
```

원격 Git·Docker는 테스트 대역으로 검사한다. 실제 GHCR 발행과 Jenkins·VM 배포 검증은 별도다.
`bash -n`에 파일 여러 개를 넘기면 첫 파일만 검사하므로 반복문으로 모든 셸 파일을 확인한다.

## 실제 발행 확인 (#480)

main push 실행 후 해당 실행의 SHA·이미지 digest 3개·marker digest를 기록한다.
`docker buildx imagetools inspect <저장소>@<digest>`로 다음을 확인하고 실행 링크를 남긴다.

- marker의 media type이 단일 manifest이고 준비된 marker와 `prod`의 digest가 같다.
- marker config의 labels가 스키마 `1`, 해당 SHA와 빌드 출력 digest 3개를 담는다.
  config는 `--format '{{json .Image}}'`로 조회할 수 있다.
- 서비스 index의 실행 플랫폼 manifest를 찾아 그 digest로 config를 조회하고
  `org.opencontainers.image.revision`이 같은 SHA인지 확인한다. attestation manifest는 제외한다.

`--raw`는 manifest JSON이므로 config labels 확인을 대신하지 못한다.
태그는 재실행으로 바뀔 수 있어 SHA 태그와 `prod`의 조회 시점도 구분해 기록한다.
이 확인을 수행하기 전까지 실제 GHCR 발행은 미검증이다.
