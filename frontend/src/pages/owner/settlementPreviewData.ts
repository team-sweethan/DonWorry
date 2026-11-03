import type { SettlementPreviewEntry } from './financePreviewTypes.ts'
type Status = 'done' | 'sch' | 'exp' | 'can'
export type OrderProduct = { productName: string; quantity: number }
// 주문 상품은 현재 목 화면 전용이며 실제 API 응답 계약에는 추가하지 않는다.
export type PreviewOrderEntry = SettlementPreviewEntry & { orderItems?: OrderProduct[] }
export const mockOrderItems: OrderProduct[][] = [
  [{ productName: '딸기 생크림 케이크 2호', quantity: 1 }],
  [
    { productName: '초코 케이크 1호', quantity: 2 },
    { productName: '레터링 케이크', quantity: 1 },
  ],
  [
    { productName: '딸기 생크림 케이크 2호', quantity: 2 },
    { productName: '초코 케이크 1호', quantity: 1 },
    { productName: '레터링 케이크', quantity: 1 },
  ],
  [
    { productName: '생크림 케이크 3호', quantity: 1 },
    { productName: '과일 타르트', quantity: 2 },
    { productName: '미니 치즈 케이크', quantity: 3 },
    { productName: '초코 케이크 1호', quantity: 1 },
    { productName: '레터링 케이크', quantity: 1 },
  ],
]
// 원본 HTML의 시연 데이터다. 실제 정산 API와 수수료 정책에 사용하지 않는다.
export function previewSettlements(now: Date): PreviewOrderEntry[] {
  const items: [number, number, Status][] = [
    [32000, -14, 'done'],
    [64000, -11, 'done'],
    [280000, -8, 'done'],
    [38000, -6, 'done'],
    [32000, -5, 'done'],
    [120000, -4, 'sch'],
    [32000, -3, 'sch'],
    [250000, -2, 'sch'],
    [29000, -1, 'sch'],
    [45000, 1, 'exp'],
    [250000, 3, 'exp'],
    [58000, 5, 'exp'],
    [120000, 9, 'exp'],
    [100000, -3, 'done'],
  ]
  return items.map(([gross, offset, status], i) => ({
    id: `CS-${now.getFullYear()}-${String(i + 1).padStart(4, '0')}`,
    item: '주문번호 기준 정산',
    orderItems: mockOrderItems[i % mockOrderItems.length],
    gross,
    status,
    pickup: new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset, 15, 20),
    date: new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset + 3, 15, 20),
  }))
}
