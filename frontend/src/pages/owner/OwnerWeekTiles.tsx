import { ArrowRightOutlined } from '@ant-design/icons'
import { useState } from 'react'
import type { CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { monthDay, shortWon, weekday } from './ownerHomeView'
import type { FlowDay } from './ownerHomeView'
import { StatusMark } from './StatusMark'

const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
const delay = (ms: number) => ({ '--ow-delay': `${ms}ms` }) as CSSProperties

/**
 * 홈의 앞으로 7일. 그래프 대신 날짜 칸마다 그날 마감 잔액을 크게 쓴다 — 범례를 읽지 않아도 된다.
 * 칸 아래 작은 글자는 그날 들어오고 나가는 돈, 모자라는 날은 주황 테두리와 주의 아이콘이다.
 * 칸을 누르면 아래 한 줄에 정확한 금액으로 계산 근거를 보여준다.
 */
export function OwnerWeekTiles({
  days,
  minimum,
  today,
}: {
  days: FlowDay[]
  minimum: number
  today: string
}) {
  const [picked, setPicked] = useState<string | null>(null)
  const selected =
    days.find((d) => d.date === picked) ??
    days.find((d) => d.belowMinimum) ??
    days.find((d) => d.date === today) ??
    days[0]
  if (!selected) return null
  return (
    <>
      <div className="oh-week" role="group" aria-label="날짜 고르기">
        {days.map((d, i) => (
          <button
            key={d.date}
            type="button"
            className={`oh-day ow-rise${d.date === selected.date ? ' is-selected' : ''}${d.belowMinimum ? ' is-low' : ''}`}
            style={delay(i * 25)}
            aria-pressed={d.date === selected.date}
            aria-label={`${monthDay(d.date)} 마감 잔액 ${won(d.closingBalance)}${d.belowMinimum ? ', 최소 운영 자금보다 적음' : ''}`}
            onClick={() => setPicked(d.date)}
          >
            <span className="oh-day-date">
              {d.date === today ? '오늘' : Number(d.date.slice(8))} {weekday(d.date)}
            </span>
            <strong className="oh-day-balance">
              {d.belowMinimum && <StatusMark tone="warning" size={14} />}
              {shortWon(d.closingBalance)}
            </strong>
            <span className="oh-day-moves">
              {d.income > 0 && <span className="is-in">+{shortWon(d.income)}</span>}
              {d.expense > 0 && <span className="is-out">−{shortWon(d.expense)}</span>}
              {d.income === 0 && d.expense === 0 && <span>변동 없음</span>}
            </span>
          </button>
        ))}
      </div>
      <div className="oh-day-detail" aria-live="polite">
        <div key={selected.date} className="oh-day-detail-body">
          <span className="oh-day-detail-date">
            {monthDay(selected.date)}({weekday(selected.date)})
          </span>
          <span>
            시작 {won(selected.openingBalance)}
            {selected.income > 0 && <b className="is-in"> + 들어올 돈 {won(selected.income)}</b>}
            {selected.expense > 0 && (
              <b className="is-out">
                {' '}
                − 나갈 돈 {won(selected.expense)}
                {selected.expenseTitles.length > 0 && ` (${selected.expenseTitles.join(' · ')})`}
              </b>
            )}{' '}
            ={' '}
            <strong className={selected.belowMinimum ? 'is-low' : ''}>
              마감 {won(selected.closingBalance)}
            </strong>
          </span>
          {selected.belowMinimum && minimum > 0 && (
            <span className="oh-day-warn">
              최소 운영 자금 {won(minimum)}보다 {won(minimum - selected.closingBalance)} 적어요
            </span>
          )}
        </div>
      </div>
      <Link className="oh-more" to="/owner/funds">
        자금 현황에서 자세히 보기 <ArrowRightOutlined />
      </Link>
    </>
  )
}
