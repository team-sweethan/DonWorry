# 0073. Marker digest로 식별하는 릴리스 이미지 묶음

- **상태:** 제안 — #480 사용자 구현 승인, PR 리뷰 대상
- **날짜:** 2026-11-03
- **관련:** Issue #448 A (#480), #481, [ADR-0069](0069-release-marker-publication.md)
- **영향 받는 담당:** P1, Jenkins·VM 배포 담당

## 배경

현재 marker에는 revision SHA만 있고 서비스 이미지 digest는 없다. 서비스의
`sha-<SHA>` 태그는 같은 SHA 재빌드로 바뀔 수 있으므로 승인한 빌드와 실제 배포할
빌드를 SHA만으로 구별할 수 없다. #481이 태그를 다시 조회하지 않고 대상을
고정할 수 있도록 발행 단계에서 하나의 묶음을 제공해야 한다.

## 결정

서비스 이미지 3개에 revision label을 붙이고 provenance를 명시적으로 활성화한다.
같은 workflow 실행의 각 빌드 출력에서 최상위 index digest를 받는다.
SHA와 digest 3개를 전부 검증한 뒤 provenance 없는 단일 manifest marker의
image config labels에 넣는다. label 이름·고정 저장소·형식은
[CI 릴리스 계약 v1](../ci/08-vm-release-marker.md#릴리스-묶음-계약-v1)에 정의한다.

묶음은 marker digest로 식별한다. schema-version은 `1`이며 SHA는 소문자 hex
40자리, 각 digest는 `sha256:` 뒤 소문자 hex 64자리만 허용한다.
누락·형식 오류는 marker 발행 전에 CI 실패로 처리하고 부분 metadata를 출력하지 않는다.
PR·수동 실행의 발행 금지, main 확인 시점과 digest 그대로 `prod` 승격은 0069를 유지한다.

같은 SHA 재빌드는 SHA 태그와 marker를 새 묶음으로 갱신할 수 있다.
중간 실패로 이전 marker가 남아도 태그에서 새 묶음을 조합하지 않는다.
#481은 marker digest를 고정해 승인 SHA·이미지 digest 3개와 함께 저장하며
승인 후 태그를 재조회하지 않는다. 스키마 누락·미지원 버전·필수 값 누락을
배포 전에 거절하고 기존 SHA-only marker로 대체하지 않는다.
index와 플랫폼별 manifest의 관계·실행 플랫폼·revision 대조는 #481에서 구현한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 배포 시 SHA 태그를 다시 조회 | 같은 SHA 재빌드에서 승인한 빌드와 다른 이미지를 고를 수 있다 |
| Actions artifact에만 묶음 저장 | 기존 GHCR marker 소비 경로 외에 별도 조회·보존 계약이 필요하다 |
| marker 내부 JSON 파일 | 작은 고정 필드에 파일 layer·추출 절차를 추가한다. config labels로 충분하다 |
| 플랫폼 manifest digest만 기록 | 빌드 출력 index와 다른 대상을 기록하며 발행 단계에 플랫폼 선택 정책을 결합한다 |
| 이미지 provenance 비활성화 | digest 조회를 단순하게 하려고 기존 빌드 증명 정보를 없앨 이유가 없다 |
| 기존 marker를 SHA 태그 조회로 보완 | 이전 계약을 자동 수용하면 digest 고정 보장이 깨진다 |

## 영향

- API·DB·화면: 변경 없음.
- 발행 계약: marker config에 스키마·서비스 index digest 3개 추가. 서비스 revision label 추가.
- 소비 계약: #481에서 v1 확인·묶음 승인·digest 고정 필요. 기존 소비자 구현의 정본은 #448에서 확인한다.
- 테스트: 누락·형식 오류·부분 출력 방지·정상 기록·동일 SHA 재빌드와 기존 게시 회귀 검사.
- 문서: CI 릴리스 문서에 labels 위치·재실행·실제 확인 절차를 기록한다.
- 보장 한계: 원격 이미지 존재·보존·플랫폼·revision 일치, 게시 시점 최신성,
  Jenkins·VM 차단·복구는 이 변경만으로 보장하지 않는다.

## 검증

검사 명령은 `python3 -m unittest discover -s .github/scripts -p 'test_release_*.py' -v`,
`bash -n .github/scripts/*.sh`, `python3 .github/scripts/check_md_links.py`다.
검토 대상 커밋의 실행 결과는 PR에 남긴다. Git·Docker 게시 호출은 대역 검사다.
실제 main push·GHCR manifest/config labels·서비스 플랫폼 revision과
Jenkins·VM 소비 검증은 미검증이며 실행 링크 확보 전에는 완료로 표시하지 않는다.
