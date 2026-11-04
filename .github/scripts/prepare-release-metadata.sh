#!/usr/bin/env bash
set -euo pipefail

output=${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}
sha=${RELEASE_SHA:?RELEASE_SHA is required}
if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo '::error::릴리스 SHA 형식이 올바르지 않습니다.' >&2
  exit 1
fi

# 모두 검증한 다음 출력해야 일부 이미지 정보만 있는 묶음이 전달되지 않는다.
for variable in BACKEND_DIGEST FRONTEND_DIGEST AI_DIGEST; do
  digest=${!variable:-}
  if [[ ! "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "::error::$variable 값이 없거나 digest 형식이 올바르지 않습니다." >&2
    exit 1
  fi
done

# 값은 위에서 hex 형식으로 제한해 multiline output 구분자를 삽입할 수 없다.
{
  echo 'labels<<DONWORRY_RELEASE_LABELS'
  echo "org.opencontainers.image.revision=$sha"
  echo 'io.donworry.release.schema-version=1'
  echo "io.donworry.release.backend.digest=$BACKEND_DIGEST"
  echo "io.donworry.release.frontend.digest=$FRONTEND_DIGEST"
  echo "io.donworry.release.ai.digest=$AI_DIGEST"
  echo 'DONWORRY_RELEASE_LABELS'
} >> "$output"
