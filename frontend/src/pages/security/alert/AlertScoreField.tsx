import { Tooltip } from 'antd'
import type { CSSProperties } from 'react'
import type { FdsEvaluationSummary, FdsResult } from '../../../api/fds'
import { customerPart } from '../../../utils/caseRules'
import { scoreToX, scoreZones } from './scoreScale'

export type AlertFilter = 'NEEDS_ATTENTION' | FdsResult | 'ALL'

const zones = scoreZones

const reviewLine = scoreToX(50)
const denyLine = scoreToX(80)

// 같은 점수의 점이 한 줄로 겹치지 않게, 평가 ID로 정한 고정 위치에 흩는다 (새로고침해도 자리가 같다)
function spread(evaluationId: number, salt: number) {
  const value = Math.sin(evaluationId * 12.9898 + salt * 78.233) * 43758.5453
  return value - Math.floor(value)
}

function isActive(filter: AlertFilter, result: FdsResult) {
  if (filter === 'ALL') return true
  if (filter === 'NEEDS_ATTENTION') return result !== 'ALLOW'
  return filter === result
}

function formatShort(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

/**
 * 점수 눈금자. 0~100점 위에 최근 평가 하나가 점 하나다. 50 · 80 선이 REVIEW · DENY 기준선이다.
 * 구역 머리 · ERROR 칸을 누르면 그 판정만 보고(표 필터), 점을 누르면 그 경보 상세로 간다.
 * 구역 숫자는 가맹점 전체 건수, 점은 최근 평가(recent)다.
 */
export function AlertScoreField({
  counts,
  recent,
  freshIds,
  filter,
  busy,
  rules,
  onSelect,
  onOpen,
}: {
  counts: Record<FdsResult, number> | null
  recent: FdsEvaluationSummary[] | null
  freshIds: Set<number>
  filter: AlertFilter
  busy: boolean
  /** 켜진 탐지 룰 수 / 전체. 못 받았으면 null */
  rules: { active: number; total: number } | null
  onSelect: (filter: AlertFilter) => void
  onOpen: (evaluationId: number) => void
}) {
  const count = (result: FdsResult) => counts?.[result] ?? 0
  const scored = (recent ?? []).filter((evaluation) => evaluation.riskScore !== null)
  const errors = (recent ?? []).filter((evaluation) => evaluation.riskScore === null)
  const latest = recent?.[0]
  const rankOf = new Map(
    (recent ?? []).map((evaluation, index) => [evaluation.evaluationId, index]),
  )
  const total = Math.max(1, recent?.length ?? 1)

  const dotTitle = (evaluation: FdsEvaluationSummary) => (
    <span className="dw-score-dot-tip">
      <strong>
        결제 {evaluation.paymentId} · {evaluation.riskScore ?? '점수 없음'}
        {evaluation.riskScore !== null && '점'} · {evaluation.result}
      </strong>
      <span>
        {customerPart(evaluation.customerRef)} · {formatShort(evaluation.evaluatedAt)}
      </span>
    </span>
  )

  return (
    <div className="dw-engine-surface dw-score-field-wrap" aria-busy={busy}>
      <div className="dw-score-engine-bar">
        <span className="dw-score-engine-name">
          <span className="dw-score-engine-led" aria-hidden="true" />
          FDS 채점 엔진
        </span>
        <span className="dw-score-engine-stats">
          <span>
            탐지 룰 <b>{rules ? `${rules.active}/${rules.total}` : '–'}</b> 가동
          </span>
          <span>
            최근 <b>{recent?.length ?? 0}</b>건
          </span>
        </span>
      </div>
      <div className="dw-score-field-layout">
        <div className="dw-score-main">
          <div className="dw-score-zones">
            {zones.map((zone) => {
              const value = count(zone.result)
              return (
                <button
                  key={zone.result}
                  type="button"
                  className={[
                    'dw-score-zone',
                    `dw-score-zone--${zone.result.toLowerCase()}`,
                    isActive(filter, zone.result) ? '' : 'dw-score-zone--dim',
                    filter === zone.result ? 'dw-score-zone--selected' : '',
                  ].join(' ')}
                  style={{ flexBasis: `${zone.width}%` }}
                  aria-pressed={filter === zone.result}
                  aria-label={`${zone.result} ${zone.title} ${value}건, ${zone.range}`}
                  title={filter === zone.result ? '다시 누르면 위험 판정 전체로' : undefined}
                  // 이미 고른 구역을 다시 누르면 선택을 풀고 위험 판정 전체로 돌아간다
                  onClick={() => onSelect(filter === zone.result ? 'NEEDS_ATTENTION' : zone.result)}
                >
                  {filter === zone.result && (
                    <span className="dw-score-zone-clear" aria-hidden="true">
                      ✕
                    </span>
                  )}
                  <span className="dw-score-zone-head">
                    <em>{zone.result}</em>
                    <span>{zone.title}</span>
                  </span>
                  <strong key={value} className={value === 0 ? 'dw-score-zero' : undefined}>
                    {value}
                  </strong>
                  <small>{zone.range}</small>
                </button>
              )
            })}
          </div>
          <div className="dw-score-plot" aria-label="최근 평가의 위험 점수 분포">
            {zones.map((zone) => (
              <span
                key={zone.result}
                className={`dw-score-zone-bg dw-score-zone-bg--${zone.result.toLowerCase()}`}
                style={{ left: `${zone.start}%`, width: `${zone.width}%` }}
              />
            ))}
            <span className="dw-score-grid" aria-hidden="true" />
            <span className="dw-score-scan" aria-hidden="true" />
            <i
              className="dw-score-threshold dw-score-threshold--review"
              style={{ left: `${reviewLine}%` }}
            >
              <b>50 REVIEW</b>
            </i>
            <i
              className="dw-score-threshold dw-score-threshold--deny"
              style={{ left: `${denyLine}%` }}
            >
              <b>80 DENY</b>
            </i>
            {recent !== null && scored.length === 0 && (
              <span className="dw-score-empty">아직 채점된 결제가 없습니다</span>
            )}
            {scored.map((evaluation) => {
              const score = evaluation.riskScore ?? 0
              const rank = rankOf.get(evaluation.evaluationId) ?? 0
              const x = Math.min(
                98.5,
                Math.max(1.5, scoreToX(score) + (spread(evaluation.evaluationId, 1) - 0.5) * 2.4),
              )
              const y = 12 + spread(evaluation.evaluationId, 2) * 76
              const fresh = freshIds.has(evaluation.evaluationId)
              return (
                <Tooltip
                  key={evaluation.evaluationId}
                  title={dotTitle(evaluation)}
                  mouseEnterDelay={0}
                >
                  <button
                    type="button"
                    tabIndex={-1}
                    aria-hidden="true"
                    className={[
                      'dw-score-dot',
                      `dw-score-dot--${evaluation.result.toLowerCase()}`,
                      isActive(filter, evaluation.result) ? '' : 'dw-score-dot--dim',
                      fresh ? 'dw-score-dot--fresh' : '',
                      rank === 0 ? 'dw-score-dot--latest' : '',
                    ].join(' ')}
                    style={
                      {
                        left: `${x}%`,
                        top: `${y}%`,
                        // 오래된 평가일수록 흐리게 (최근 흐름이 보이게)
                        '--dw-score-dot-opacity': String(1 - (rank / total) * 0.6),
                      } as CSSProperties
                    }
                    onClick={() => onOpen(evaluation.evaluationId)}
                  />
                </Tooltip>
              )
            })}
          </div>
          <div className="dw-score-axis" aria-hidden="true">
            <span>0</span>
            <span style={{ left: `${scoreToX(25)}%` }}>25</span>
            <span style={{ left: `${reviewLine}%` }}>50</span>
            <span style={{ left: `${scoreToX(65)}%` }}>65</span>
            <span style={{ left: `${denyLine}%` }}>80</span>
            <span style={{ left: `${scoreToX(90)}%` }}>90</span>
            <span style={{ left: '100%' }}>100</span>
          </div>
        </div>
        <button
          type="button"
          className={[
            'dw-score-error',
            isActive(filter, 'ERROR') ? '' : 'dw-score-error--dim',
            filter === 'ERROR' ? 'dw-score-zone--selected' : '',
          ].join(' ')}
          aria-pressed={filter === 'ERROR'}
          aria-label={`ERROR 채점 실패 ${count('ERROR')}건, 재평가 대기`}
          onClick={() => onSelect(filter === 'ERROR' ? 'NEEDS_ATTENTION' : 'ERROR')}
        >
          {filter === 'ERROR' && (
            <span className="dw-score-zone-clear" aria-hidden="true">
              ✕
            </span>
          )}
          <span className="dw-score-zone-head">
            <em>ERROR</em>
            <span>채점 실패</span>
          </span>
          <strong
            key={count('ERROR')}
            className={count('ERROR') === 0 ? 'dw-score-zero' : undefined}
          >
            {count('ERROR')}
          </strong>
          <small>점수 없음 · 재평가 대기</small>
          <span className="dw-score-error-dots" aria-hidden="true">
            {errors.slice(0, 24).map((evaluation) => (
              <i
                key={evaluation.evaluationId}
                className={freshIds.has(evaluation.evaluationId) ? 'dw-score-error-dot--fresh' : ''}
              />
            ))}
          </span>
        </button>
      </div>
      <div className="dw-score-footer">
        <span className="dw-score-latest">
          <span className="dw-score-latest-label">최근</span>
          {latest ? (
            <>
              <strong>결제 {latest.paymentId}</strong>
              <span
                className={`dw-score-latest-result dw-score-latest-result--${latest.result.toLowerCase()}`}
              >
                {latest.riskScore === null ? '채점 실패' : `${latest.riskScore}점`} {latest.result}
              </span>
              <span className="dw-score-latest-time">{formatShort(latest.evaluatedAt)}</span>
            </>
          ) : (
            <span>대기 중</span>
          )}
        </span>
        <span className="dw-score-switch" role="group" aria-label="표에 보일 판정">
          <button
            type="button"
            aria-pressed={filter === 'NEEDS_ATTENTION'}
            onClick={() => onSelect('NEEDS_ATTENTION')}
          >
            위험 판정
          </button>
          <button type="button" aria-pressed={filter === 'ALL'} onClick={() => onSelect('ALL')}>
            전체
          </button>
        </span>
      </div>
    </div>
  )
}
