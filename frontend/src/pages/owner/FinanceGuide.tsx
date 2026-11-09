import { QuestionCircleOutlined } from '@ant-design/icons'
import { Button, Tour } from 'antd'
import type { TourProps } from 'antd'
import { useEffect, useRef, useState } from 'react'

const settlementGuide = [
  [
    '.finance-period',
    '조회 기간',
    '시작일과 종료일을 선택하면 해당 기간의 정산 내역을 볼 수 있어요.',
  ],
  [
    '.payout-summary-grid',
    '정산 요약',
    '실제 지급액은 환불 회수를 차감한 금액이에요. 정산 예정금은 앞으로 지급할 금액이에요.',
  ],
  [
    '.stl-calendar-header',
    '캘린더',
    '날짜를 누르면 그날의 주문별 정산과 지출 내역이 옆에 표시돼요.',
  ],
  [
    '.stl-detail .stl-more-button',
    '상세 내역',
    '더보기를 누르면 선택한 날짜의 내역을 검색하고, 정산 건의 상세 보기를 열 수 있어요.',
  ],
]

const fundsGuide = [
  [
    '.finance-opening-setup',
    '최초 잔액 설정',
    '오늘 시작 잔액을 입력해 예측을 시작하세요. 최소 운영 자금은 이후 잔액·지출 설정에서 변경할 수 있어요.',
  ],
  [
    '[data-guide="finance-settings"]',
    '잔액·지출 설정',
    '최소 운영 자금을 설정하고 일회성 지출이나 정기 지출을 등록할 수 있어요.',
  ],
  [
    '[data-guide="finance-period"]',
    '예측 기간',
    '7일·30일·다음 달 예측을 선택하거나 조회 시작일과 종료일을 직접 지정하세요.',
  ],
  [
    '.finance-trend',
    '예상 잔액 그래프',
    '초록 막대는 그날 들어오는 정산금, 빨간 막대는 완료·예정 지출이에요. 갈색 선은 전날 잔액에 그날의 정산금을 더하고 지출을 뺀 예상 마감 잔액이에요. 최소 운영 자금을 설정했다면 점선이 기준이고, 주황색 점은 잔액이 그 기준보다 낮은 날이에요. 가이드를 닫고 그래프를 좌우로 움직이거나 날짜의 점을 눌러 계산 근거를 확인할 수 있어요.',
  ],
  [
    '.cashflow-verdict-warning',
    '자금 부족 경고',
    '처음 기준에 못 미치는 날과 최대 부족액을 보여줘요. 원인 확인을 누르면 날짜별 계산 근거가 나와요.',
  ],
]

export function FinanceGuide({ page }: { page: 'settlement' | 'funds' }) {
  const trigger = useRef<HTMLButtonElement>(null)
  const [steps, setSteps] = useState<NonNullable<TourProps['steps']>>([])
  const [current, setCurrent] = useState(0)
  const open = steps.length > 0

  const close = () => {
    setSteps([])
    trigger.current?.focus({ preventScroll: true })
  }

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setSteps([])
        trigger.current?.focus({ preventScroll: true })
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [open])

  const start = () => {
    const root = trigger.current?.closest('.stl-prototype, .finance-preview')
    const initial = root?.querySelector('.finance-opening-setup')
    const guide =
      page === 'settlement'
        ? settlementGuide
        : initial
          ? fundsGuide.slice(0, 1)
          : fundsGuide.slice(1)
    const available = guide.flatMap(([selector, title, description]) => {
      const target = root?.querySelector<HTMLElement>(selector)
      return target && target.getClientRects().length && !target.matches(':disabled')
        ? [{ title, description, target }]
        : []
    })
    setCurrent(0)
    setSteps(
      available.length
        ? available
        : [
            {
              title: '사용 가이드',
              description: '조회가 끝나면 사용 가이드를 다시 열어 화면의 기능을 확인하세요.',
            },
          ],
    )
  }

  return (
    <>
      <Button ref={trigger} size="small" icon={<QuestionCircleOutlined />} onClick={start}>
        사용 가이드
      </Button>
      <Tour
        open={open}
        current={current}
        steps={steps}
        disabledInteraction
        onChange={setCurrent}
        onClose={close}
        onFinish={close}
        actionsRender={(actions) => (
          <>
            <Button size="small" type="text" onClick={close}>
              건너뛰기
            </Button>
            {actions}
          </>
        )}
      />
    </>
  )
}
