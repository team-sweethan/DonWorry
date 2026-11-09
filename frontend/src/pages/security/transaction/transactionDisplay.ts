import type { FdsResult } from '../../../types/case'

// 거래 내역 목록 · 상세가 같이 쓰는 표시값 (#732)

// 경보 화면과 같은 판정이지만, 처음 보는 직원도 읽게 한국어로 쓴다
export const fdsResultText: Record<FdsResult, { label: string; color: string }> = {
  ALLOW: { label: '통과', color: 'green' },
  REVIEW: { label: '검토', color: 'gold' },
  DENY: { label: '차단', color: 'red' },
  ERROR: { label: '평가 오류', color: 'default' },
}

// 처음 보는 직원이 칸 뜻을 알 수 있게 제목 옆 ⓘ에 설명을 둔다
export const transactionHelp = {
  order: '가맹점이 손님 영수증에 찍은 번호예요. 가맹점에 문의할 때 이 번호로 찾아요.',
  customer:
    '가맹점이 손님마다 붙인 번호예요. 이름 대신 이 번호로 같은 손님의 결제를 모아 봐요. 이메일 · 카드 · 접속 정보는 줄을 눌러 상세에서 봐요.',
  device: '결제한 기기의 식별값 앞부분만 보여요. 개인정보 보호를 위해 나머지는 가렸어요.',
  ip: '결제한 나라와 IP 앞부분이에요. 별표(*)는 가린 부분이에요.',
  // 판정처럼 읽히지 않게 "높음 · 위험" 대신 기준 이상만 표시한다 (#441, ADR-0061)
  ml: 'ML이 매긴 참고 점수예요. 실제 사기 확률이 아니라 시연용 학습 데이터에서 나온 점수라서, 결제 결과는 바뀌지 않아요. 학습 때 정한 기준 이상이면 한 번 더 볼 만한 결제로 표시해요. 기준값은 점수에 마우스를 올리면 보여요.',
}

// IPv6는 가린 값(예: 0:0:*)만 보면 고장처럼 보여서 화면에서만 종류를 앞에 붙인다 (#539)
export const ipText = (masked: string | null) =>
  masked?.includes(':') ? `IPv6 · ${masked}` : masked

// 가맹점에 전달하거나 검색칸에 붙여 넣을 때 쓰는 번호는 복사 버튼을 단다
export const copyable = (text: string) => ({ text, tooltips: ['복사', '복사됨'] })
