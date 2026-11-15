// 동시에 열린 미리보기는 로딩을 공유하고, 실패한 요청은 다음 시도에서 다시 실행한다.
export function createPreviewLoader<T>(load: () => Promise<T>) {
  let pending: Promise<T> | undefined
  return () => {
    pending ??= Promise.resolve()
      .then(load)
      .catch((error: unknown) => {
        pending = undefined
        throw error
      })
    return pending
  }
}
