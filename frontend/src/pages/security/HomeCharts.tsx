import type { CSSProperties } from 'react'
import type { CaseSummary } from '../../types/case'
import type { CaseListQuery, RiskFilter } from '../../utils/caseListQuery'
import { SLA_HOURS } from '../../utils/caseRules'
import { homeAgeBands, homeRiskBands, type AgeBand, type HomeBand } from '../../utils/securityHome'

type Props = {
  cases: CaseSummary[]
  openList: (query: Partial<CaseListQuery>) => void
}

type BarRow = HomeBand<string> & { tone: string; onClick?: () => void }

// 막대 길이는 그래프 안 최댓값 기준. 0건은 그리지 않고, 1건 이상은 최소 길이를 둬 보이게 한다
function Bars({ rows, label }: { rows: BarRow[]; label: string }) {
  const max = Math.max(1, ...rows.map((r) => r.count))
  return (
    <ul className="security-home-bars" aria-label={label}>
      {rows.map((row, index) => {
        const bar = (
          <>
            <span className="security-home-bar-label">{row.label}</span>
            <span className="security-home-bar-track" aria-hidden="true">
              {row.count > 0 && (
                <i
                  className={`is-${row.tone}`}
                  style={
                    {
                      width: `${Math.max(3, (row.count / max) * 100)}%`,
                      '--bar-delay': `${300 + index * 90}ms`,
                    } as CSSProperties
                  }
                />
              )}
            </span>
            <strong className="security-home-bar-value">{row.count}건</strong>
          </>
        )
        return (
          <li
            key={row.key}
            className={row.tone === 'age-4' && row.count > 0 ? 'is-exceeded' : undefined}
          >
            {row.onClick && row.count > 0 ? (
              <button
                type="button"
                onClick={row.onClick}
                aria-label={`${row.label} ${row.count}건 사건 목록 보기`}
              >
                {bar}
              </button>
            ) : (
              <div>{bar}</div>
            )}
          </li>
        )
      })}
    </ul>
  )
}

const riskTone: Record<RiskFilter, string> = {
  deny: 'risk-deny',
  review: 'risk-review',
  low: 'risk-low',
  none: 'none',
}

export function HomeCharts({ cases, openList }: Props) {
  const risk = homeRiskBands(cases).map((band) => ({
    ...band,
    tone: riskTone[band.key],
    onClick: () => openList({ risk: band.key }),
  }))
  // 오래될수록 진한 파랑. 기한 지남은 가장 진한 색과 굵은 글자로 구분한다
  const age = homeAgeBands(cases).map((band: HomeBand<AgeBand>, index) => ({
    ...band,
    tone: `age-${index + 1}`,
    // 목록에는 기한 지남 조건만 있어 그 구간만 목록으로 이동한다
    onClick: band.key === 'exceeded' ? () => openList({ slaExceeded: true }) : undefined,
  }))
  return (
    <div className="security-home-enter security-home-charts">
      <section className="security-home-chart" aria-labelledby="security-home-risk-title">
        <h3 id="security-home-risk-title">위험도 분포</h3>
        <p>연결 결제 중 가장 높은 FDS 점수 · FDS 판정 구간 기준</p>
        <Bars rows={risk} label="위험도 구간별 진행 중 사건 수" />
        <small>결제 점수를 하나라도 불러오지 못한 사건은 점수 없음으로 셉니다.</small>
      </section>
      <section className="security-home-chart" aria-labelledby="security-home-age-title">
        <h3 id="security-home-age-title">생성 후 경과 시간</h3>
        <p>오래될수록 진한 색 · {SLA_HOURS}시간이 지나면 기한 지남</p>
        <Bars rows={age} label="경과 시간별 진행 중 사건 수" />
        <small>시간이 지나도 자동으로 판정하거나 종료하지 않습니다.</small>
      </section>
    </div>
  )
}
