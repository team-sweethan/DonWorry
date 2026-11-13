import { QuestionCircleOutlined } from '@ant-design/icons'
import { Button, Tour } from 'antd'
import type { TourProps } from 'antd'
import { useEffect, useRef, useState } from 'react'
import './finance-guide.css'

const settlementGuide = [
  [
    '.finance-period',
    '조회 기간',
    '시작일과 종료일을 선택하면 해당 기간의 정산 내역을 볼 수 있어요.',
  ],
  [
    '.payout-summary-grid',
    '정산 요약',
    '실제 지급액은 환불 회수를 차감한 금액이에요. 정산 예정금은 앞으로 지급될 금액이에요.',
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
    '7일·30일·다음 달을 빠르게 고를 수 있어요. 달력에서 시작일과 마지막 날을 고른 뒤 확인을 누르세요. 양옆 화살표로 같은 일수만큼 이전·다음 기간으로 이동할 수 있어요.',
  ],
  [
    '.finance-trend',
    '예상 잔액 그래프',
    '위의 선은 하루가 끝날 때의 예상 잔액이에요. 아래의 초록·주황 막대는 그날 들어오는 정산금과 나가는 지출을 별도 눈금으로 비교해요. 위 그래프의 점선 아래 주황색 점은 남겨둘 잔액 기준보다 낮은 날이에요. 날짜를 누르거나 방향키로 그날의 금액을 확인하고, 긴 기간은 좌우 스크롤로 살펴보세요.',
  ],
  [
    '.cashflow-verdict-warning',
    '자금 부족 경고',
    '처음 기준보다 낮아지는 날과 기간 중 가장 큰 부족액을 보여줘요. 부족한 이유 확인을 누르면 그날의 계산 내역이 펼쳐지고, 지출 계획 확인을 누르면 등록한 지출을 볼 수 있어요.',
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
      const wide =
        selector === '[data-guide="finance-settings"]' ||
        selector === '.stl-detail .stl-more-button'
      return target && target.getClientRects().length && !target.matches(':disabled')
        ? [
            {
              title,
              className: wide ? 'finance-guide-wide' : undefined,
              description:
                page === 'funds'
                  ? description
                      .split(/(?<=\.) /)
                      .map((sentence) => <p key={sentence}>{sentence}</p>)
                  : description,
              target,
            },
          ]
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
        rootClassName={page === 'funds' ? 'funds-guide' : 'settlement-guide'}
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
