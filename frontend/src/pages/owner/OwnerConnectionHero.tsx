import { Mascot } from '../../components/brand/BrandMark'
import type { CSSProperties } from 'react'
import type { IntegrationStatus } from '../../api/integration'
import './owner-connection-hero.css'
import '../../styles/ownerMotion.css'

const steps = ['키 발급', '쇼핑몰 서버 설정', '연결 확인'] as const
const stepIndex: Record<IntegrationStatus, number> = {
  NO_KEY: 0,
  PENDING_CONNECTION: 1,
  CONNECTED: 3,
}
const headline: Record<IntegrationStatus, string> = {
  NO_KEY: '아직 쇼핑몰과 연결되지 않았어요',
  PENDING_CONNECTION: '쇼핑몰 서버의 연결 확인을 기다리고 있어요',
  CONNECTED: '쇼핑몰과 연결됐어요',
}
const delay = (ms: number) => ({ '--ow-delay': `${ms}ms` }) as CSSProperties

/**
 * 시크릿 키 화면 위쪽의 연결 그림 (사장님만).
 * 서버가 돌려준 연동 상태만 그린다. 연결 완료 표시는 서버 상태가 CONNECTED일 때만 나온다 —
 * 화면에서 먼저 완료로 보이지 않는다. 기다리는 동안 선이 흐르는 것은 "확인 대기"를 뜻한다.
 */
export function OwnerConnectionHero({
  status,
  description,
}: {
  status: IntegrationStatus
  description?: string
}) {
  const current = stepIndex[status]
  const connected = status === 'CONNECTED'
  return (
    <section
      className={`och${connected ? ' is-connected' : ''}${status === 'PENDING_CONNECTION' ? ' is-waiting' : ''}`}
      aria-label="쇼핑몰 연결 상태"
    >
      <div className="och-copy">
        <span className="och-eyebrow">
          {connected
            ? '연결 확인 완료'
            : status === 'NO_KEY'
              ? '연결을 시작해 볼까요?'
              : '다음은 쇼핑몰 서버 설정이에요'}
        </span>
        <h2 className="och-headline" role="status">
          {headline[status]}
        </h2>
        {description && <p className="och-description">{description}</p>}
      </div>
      <div className="och-picture" aria-hidden="true">
        <span className="och-node">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7">
            <path d="M4 9l1.5-5h13L20 9M4 9v11h16V9M4 9h16M9 20v-6h6v6" />
          </svg>
          <span>내 쇼핑몰 서버</span>
        </span>
        <svg className="och-wire" viewBox="0 0 300 20" preserveAspectRatio="none">
          {connected ? (
            // 상태가 바뀌어 완료가 되면 새로 그려져 한 번 재생된다
            <path key="done" d="M0 10 H300" pathLength={1} className="och-line-done ow-draw" />
          ) : (
            <path key="wait" d="M0 10 H300" className="och-line-wait" />
          )}
        </svg>
        {connected && (
          <span key="check" className="och-check ow-pop" style={delay(700)}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
              <path d="m5 12.5 4.5 4.5L19 7.5" />
            </svg>
          </span>
        )}
        <span className="och-node is-donworry">
          <span className="och-mascot">
            <Mascot size={82} className="dw-mascot-float" />
          </span>
          <span>돈워리</span>
        </span>
      </div>
      <ol className="och-steps" aria-label="연결 순서">
        {steps.map((label, i) => {
          const state = i < current ? 'is-done' : i === current ? 'is-current' : ''
          return (
            <li key={label} className={state} aria-current={i === current ? 'step' : undefined}>
              <span className="och-track">
                <span className="ow-grow-x" style={delay(200 + i * 160)} />
              </span>
              <span className="och-step-label">
                <b>{i < current ? '✓' : i + 1}</b>
                {label}
                {i === current && <small>지금 할 일</small>}
              </span>
            </li>
          )
        })}
      </ol>
    </section>
  )
}
