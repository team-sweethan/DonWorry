export const landingScreens = [
  {
    key: 'actions',
    title: '조치 결과',
    text: '멈춘 결제는 지금 어떤 상태일까요?',
    detail: '담당자가 확인 중인지, 정상 처리나 취소가 실제 결제에 반영됐는지 확인하세요.',
  },
  {
    key: 'settlements',
    title: '정산 내역',
    text: '정산과 실제 지급을 구분해서 보세요.',
    detail: '달력과 내역에서 정산 예정·완료를 살펴보고, 환불 차감 후 실제 지급액까지 확인하세요.',
  },
  {
    key: 'funds',
    title: '자금 현황',
    text: '돈이 부족해지는 날을 미리 확인하세요.',
    detail:
      '시작 잔액과 정산 유입, 등록한 지출을 바탕으로 앞으로의 잔액을 예상해 보세요. 은행 잔액을 직접 조회하지는 않아요.',
  },
] as const
export type LandingScreen = (typeof landingScreens)[number]['key']
