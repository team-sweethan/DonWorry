/**
 * 열어 둔 상세가 가리키는 항목을 최신 목록에서 다시 찾는다 (#803 리뷰).
 * 같은 기간을 새로고침하면 목록이 새 객체로 바뀌므로, 고를 때 저장한 옛 객체 대신 같은 ID의 새 항목을 보여 준다.
 * 새 목록에 없으면 null — 상세를 닫는다.
 */
export function latestSelection<T>(
  items: readonly T[],
  selected: T | null,
  id: (item: T) => string | number,
): T | null {
  if (selected === null) return null
  const selectedId = id(selected)
  return items.find((item) => id(item) === selectedId) ?? null
}
