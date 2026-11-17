/** 읽기 전용 조작만 허용한다. 제출·판정·키 발급·AI 생성 버튼은 등록하지 않는다. */
const controls = {
  'loan-detail': '.booth-loan-modal .ant-modal-content',
  'settlement-list': '[data-booth-action="settlement-list"][aria-selected="true"]',
  'payout-kind': '[data-booth-action="payout-kind"][aria-selected="true"]',
  'completed-case': '[data-booth-action="completed-case"][aria-expanded="true"]',
  'shortage-date': '[data-booth-action="shortage-date"][aria-pressed="true"]',
  'contact-read': '[data-booth-contact-ready]',
  'contact-form': '.dw-contact-raw-modal',
  'assigned-staff': '[data-booth-action="assigned-staff"][aria-pressed="true"]',
  'monitored-staff': '[data-booth-action="monitored-staff"][aria-pressed="true"]',
} as const

export type BoothPreparation = keyof typeof controls

/** 늦게 도착하는 화면을 기다리며 순서대로 연다. 한 박스에서 같은 조작을 반복하지 않는다. */
export function prepareBoothSpot(
  actions: readonly BoothPreparation[],
  clicked: Set<BoothPreparation>,
  find: (selector: string) => Element | null,
  click: (element: Element) => void,
) {
  for (const action of actions) {
    if (find(controls[action])) continue
    if (clicked.has(action)) return false
    const control = find(`[data-booth-action="${action}"]`)
    // 예측 그래프가 그려졌지만 음수 잔액 날짜가 없으면 현재 날짜를 그대로 보여 준다.
    // 데이터가 아직 오지 않은 상태와 구분해, 부족일이 없는 계정도 다음으로 넘어갈 수 있게 한다.
    if (!control && action === 'shortage-date' && find('[data-funds-date][aria-pressed="true"]'))
      continue
    if (!control || control.matches(':disabled, [aria-disabled="true"], .ant-btn-loading'))
      return false
    clicked.add(action)
    click(control)
    return false
  }
  return true
}
