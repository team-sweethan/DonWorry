export type FundsWeather = 'sun' | 'cloud' | 'rain'

// 날씨는 기존 잔액을 설명하는 비유이며, 매출이나 새로운 위험 점수를 예측하지 않는다.
export function fundsWeather(
  balance: number,
  minimum: number,
  previousBalance?: number,
): FundsWeather {
  if (balance < minimum) return 'rain'
  if (previousBalance !== undefined && balance < previousBalance) return 'cloud'
  return 'sun'
}

export const fundsWeatherLabels: Record<FundsWeather, { title: string; meaning: string }> = {
  sun: { title: '맑음', meaning: '남겨둘 잔액 기준 이상' },
  cloud: { title: '구름', meaning: '기준 이상이지만 전날보다 잔액 감소' },
  rain: { title: '비 소식', meaning: '남겨둘 잔액 기준 미달' },
}
