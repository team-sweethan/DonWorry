export const LIST_PAGE_SIZE = 20

/** 서버의 int 범위를 넘거나 잘못된 페이지는 API에 보내지 않고 첫 페이지로 읽는다. */
export function parseListPage(value: string | null): number {
  const page = Number(value)
  return Number.isSafeInteger(page) && page >= 1 && page <= 2_147_483_647 ? page : 1
}

export function parseListKeyword(value: string | null): string {
  return (value ?? '').slice(0, 100)
}

export function withQuery(path: string, query: string): string {
  return query ? `${path}?${query}` : path
}

/** 결과가 없어도 1페이지는 있다. 목록 주소의 페이지가 이 값을 넘으면 마지막 페이지로 줄인다. */
export function lastListPage(totalElements: number): number {
  return Math.max(1, Math.ceil(totalElements / LIST_PAGE_SIZE))
}

/** 페이지만 다르면 같은 조건이다. 같은 조건일 때만 이전 결과를 조회 중에 계속 보여 준다. */
export function sameListCondition(left: URLSearchParams, right: URLSearchParams): boolean {
  return withoutPage(left) === withoutPage(right)
}

function withoutPage(params: URLSearchParams): string {
  const copy = new URLSearchParams(params)
  copy.delete('page')
  return copy.toString()
}
