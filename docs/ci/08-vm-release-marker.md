# 08. VM 이미지와 릴리스 marker

[← CI 문서](README.md)

## 발행 순서

[VM Images](../../.github/workflows/vm-images.yml)는 검사 통과 후 backend·frontend·AI
이미지를 `sha-<SHA>`로 발행한다. GHCR 발행은 main push이면서
`DONWORRY_RELEASE_ENABLED == 'true'`인 경우에만 수행한다.
PR·수동 실행은 `prod`를 변경하지 않는다.

1. marker를 `donworry-release:sha-<SHA>`로 빌드·업로드하고 digest를 받는다.
2. [게시 스크립트](../../.github/scripts/publish-release-marker.sh)가 main HEAD를 조회한다.
3. 대상 SHA와 일치하면 준비된 digest를 `donworry-release:prod`로 게시한다.
   일치하지 않으면 건너뛰며, 조회·digest 형식·게시 오류는 CI를 실패시킨다.

최신 확인 이후에는 marker를 다시 빌드하거나 SHA 태그에서 대상을 다시 선택하지 않는다.
기존 workflow concurrency로 main push 실행을 직렬화한다. 별도의 발행 경로를
추가할 경우 같은 직렬화 범위에 포함해야 한다.

## 보장 범위와 실제 배포

main 변경과 GHCR 게시를 원자적으로 묶지는 않는다. 최종 조회 뒤 main이 바뀌면
이전 SHA가 `prod`로 게시될 수 있다. 또한 새 main의 검사·빌드가 끝나기 전에는
이전 릴리스 후보가 남는다. `prod`만 보고 최신 main의 배포 허가로 판단하지 않는다.

실제 배포 전 승인 SHA·main HEAD·marker revision·이미지 3개의 revision을 대조하고
검증된 digest만 Compose에 적용하는 작업은 [#448](https://github.com/team-sweethan/DonWorry/issues/448)이다.
현재 저장소에는 해당 Jenkins·VM 검증 코드가 없어 이 문서는 구현 완료를 뜻하지 않는다.
배포 시작 이후 main 변경에 대한 정책도 #448에서 확정한다.

설계 근거와 버린 대안은 [ADR-0069](../decisions/0069-release-marker-publication.md)를 따른다.
엄밀한 게시 시점 최신성 또는 완료 기준 변경에 대한 합의 전에는 #461을 닫지 않는다.

## 로컬 검사

```bash
python3 -m unittest discover -s .github/scripts -p 'test_release_*.py' -v
bash -n .github/scripts/check-release-target.sh .github/scripts/publish-release-marker.sh
```

원격 Git·Docker는 테스트 대역으로 검사한다. 실제 GHCR 발행과 Jenkins·VM 배포 검증은 별도다.
