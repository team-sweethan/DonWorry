/**
 * 늦게 도착한 응답을 버리기 위한 요청 번호 (#621 리뷰).
 * 요청을 보낼 때 begin()으로 번호를 받고, 입력 변경 · 대상 변경 · 창 닫기 때 invalidate()로 이전 요청을 무효로 만든다.
 * 응답을 화면에 반영하기 전에 isCurrent(번호)를 확인한다.
 */
export function createLatestRequest() {
  let current = 0
  return {
    begin: () => ++current,
    invalidate: () => {
      current += 1
    },
    isCurrent: (token: number) => token === current,
  }
}
