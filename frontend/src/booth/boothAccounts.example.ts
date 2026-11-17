import type { BoothAccounts } from './boothAccounts'

// 이 파일을 boothAccounts.local.ts로 복사한 뒤, 본인 로컬 DB의 기존 계정을 적는다.
// 실제 비밀번호와 암호화 키는 이 예제에 적거나 커밋하지 않는다.
export const boothAccounts: BoothAccounts = {
  owner: { loginId: '', password: '' },
  security: {
    loginId: '',
    password: '',
    caseId: '',
    reportCaseId: '',
    evaluationId: '',
  },
  admin: { loginId: '', password: '' },
}
