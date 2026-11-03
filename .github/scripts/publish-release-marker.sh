#!/usr/bin/env bash
set -euo pipefail

repository=ghcr.io/team-sweethan/donworry-release
digest=${MARKER_DIGEST:?MARKER_DIGEST is required}
if [[ ! "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo '::error::준비된 marker digest 형식이 올바르지 않습니다.' >&2
  exit 1
fi

# 빌드는 이미 끝났다. 조회 뒤에는 준비된 manifest를 prod로 게시하는 일만 남긴다.
source "$(dirname "${BASH_SOURCE[0]}")/check-release-target.sh"
if [[ "$head" != "$expected" ]]; then
  exit 0
fi

# 단일 manifest를 index로 감싸지 않고 준비한 marker의 digest를 그대로 유지한다.
# main 변경과 GHCR 쓰기는 원자적이지 않다. 실제 배포 전 SHA 대조는 #448에서 필수다.
docker buildx imagetools create --prefer-index=false \
  --tag "$repository:prod" "$repository@$digest"
echo 'published=true' >> "$output"
