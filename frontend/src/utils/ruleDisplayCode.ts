// 보안 탐지 규칙 표시 코드 (#664) — 저장값은 그대로 두고 화면에 보이는 코드만 SEC-OP · SEC-INS로 맞춘다
// 사장님 계정 규칙은 저장값(SEC_OPERATOR_…)이 끄기 요청 주소에도 쓰여 값을 바꾸지 않고 여기서만 바꿔 보인다
// 내부자 규칙은 백엔드가 SEC-INS-0x로 내려준다. 그 전에 저장된 경보 문장 · 보고서의 INS-0x도 같은 표기로 보인다

const operatorRuleCodes: Record<string, string> = {
  SEC_OPERATOR_OUT_OF_ROLE_REPEAT: 'SEC-OP-01',
  SEC_OPERATOR_PERMISSION_MISSING_REPEAT: 'SEC-OP-02',
}

// 앞에 SEC-가 이미 붙은 코드는 건드리지 않는다
const legacyInsiderCode = /(?<![A-Z-])INS-(\d{2})\b/g
// 탐지 룰 목록 · 변경 이력은 내부자 규칙도 저장 이름(INS_0x)으로 온다 — 끄기 · 켜기 주소 열쇠라서 (#685)
const insiderStoredName = /^INS_(\d{2})$/

export function displayRuleCode(ruleCode: string): string {
  return (
    operatorRuleCodes[ruleCode] ??
    ruleCode.replace(insiderStoredName, 'SEC-INS-$1').replace(legacyInsiderCode, 'SEC-INS-$1')
  )
}

export function displayRuleText(text: string): string {
  return text.replace(legacyInsiderCode, 'SEC-INS-$1')
}

// 높음 경보 중 내보내기 시도(차단) 경보 — 옛 보고서는 INS-05, 새 보고서는 SEC-INS-05로 저장돼 있다
export function isExportBlockRule(ruleCode: string): boolean {
  return displayRuleCode(ruleCode) === 'SEC-INS-05'
}
