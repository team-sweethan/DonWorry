import type { FdsResult } from '../../../api/fds'

/**
 * 경보 화면의 판정 표시. 채점 엔진 화면과 같은 신호색 점 + 고정폭 코드다.
 * ALLOW 푸른 회색 · REVIEW 앰버 · DENY 로즈 · ERROR 라벤더. 경보 목록 · 상세에서만 쓴다.
 */
export function ResultTag({ result }: { result: FdsResult }) {
  return (
    <span className={`dw-alert-signal-code dw-alert-signal-code--${result.toLowerCase()}`}>
      <i aria-hidden="true" />
      {result}
    </span>
  )
}
