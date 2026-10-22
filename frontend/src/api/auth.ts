/**
 * 로그인 API. 호출은 TASK 05에서 붙인다.
 * 로그인 화면은 이 에러면 "아이디·비밀번호 불일치"를, 그 밖의 실패는 "서버 연결 실패"를 보여준다.
 */

/** 아이디 또는 비밀번호가 틀렸을 때 (백엔드 401). */
export class InvalidCredentialsError extends Error {
  constructor() {
    super('아이디 또는 비밀번호가 올바르지 않습니다.')
    this.name = 'InvalidCredentialsError'
  }
}
