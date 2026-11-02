#!/usr/bin/env bash
set -euo pipefail

# 조회 실패를 "오래된 커밋"으로 취급하면 발행 없이 CI만 성공하므로 구분한다.
expected=${RELEASE_SHA:?RELEASE_SHA is required}
output=${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}
if [[ ! "$expected" =~ ^[0-9a-f]{40}$ ]]; then
  echo '::error::배포 대상 SHA 형식이 올바르지 않습니다.' >&2
  exit 1
fi

if ! remote=$(git ls-remote --exit-code --refs origin refs/heads/main); then
  echo '::error::main HEAD를 조회하지 못했습니다. 배포를 중단합니다.' >&2
  exit 1
fi

# 빈 응답·다중 응답·다른 ref를 정상 조회로 받아들이지 않는다.
pattern=$'^([0-9a-f]{40})\trefs/heads/main$'
if [[ ! "$remote" =~ $pattern ]]; then
  echo '::error::main HEAD 조회 응답이 올바르지 않습니다. 배포를 중단합니다.' >&2
  exit 1
fi
head=${BASH_REMATCH[1]}

if [[ "$head" == "$expected" ]]; then
  echo 'current=true' >> "$output"
else
  echo 'current=false' >> "$output"
  echo "::warning::main이 $head 로 변경되어 marker를 건너뜁니다 (이 실행: $expected)"
fi
