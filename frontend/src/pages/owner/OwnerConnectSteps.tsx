import { ArrowRightOutlined, ReloadOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import type { CSSProperties, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { IntegrationInfo } from '../../api/integration'

const delay = (ms: number) => ({ '--ow-delay': `${ms}ms` }) as CSSProperties

type Step = { title: string; body: ReactNode; action?: ReactNode }

/**
 * 연결 전 홈의 중심. 세 단계를 위에서 아래로 두고, 지금 할 단계만 펼쳐 버튼을 하나 보여준다.
 * 끝난 단계는 체크, 남은 단계는 흐리게 둔다. 단계는 서버의 연동 상태로만 정한다.
 */
export function OwnerConnectSteps({
  data,
  busy,
  onRefresh,
}: {
  data: IntegrationInfo
  busy: boolean
  onRefresh: () => void
}) {
  const current = data.status === 'NO_KEY' ? 0 : 1
  const steps: Step[] = [
    {
      title: '시크릿 키 발급하기',
      body: '쇼핑몰 서버가 돈워리에 결제를 맡길 때 쓰는 비밀 키예요. 발급할 때 비밀번호를 한 번 더 확인해요.',
      action: (
        <Link className="oh-primary-link" to="/owner/integration">
          시크릿 키 발급하기 <ArrowRightOutlined />
        </Link>
      ),
    },
    {
      title: '쇼핑몰 서버에 키 넣기',
      body: (
        <>
          쇼핑몰 서버 설정에 가맹점 코드 <span className="oh-code">{data.merchantCode}</span>와
          시크릿 키를 넣고, 연결 확인 요청을 보내 주세요. 키는 메일·메신저로 보내지 마세요.
        </>
      ),
      action: (
        <>
          <Link className="oh-primary-link" to="/owner/integration">
            연동 방법 보기 <ArrowRightOutlined />
          </Link>
          <Button
            size="large"
            icon={<ReloadOutlined />}
            loading={busy}
            onClick={onRefresh}
            className="oh-step-recheck"
          >
            연결 상태 다시 확인
          </Button>
        </>
      ),
    },
    {
      title: '연결 확인',
      body: '쇼핑몰의 연결 확인 요청이 도착하면 완료돼요. 그때부터 신규 결제를 요청할 수 있어요.',
    },
  ]
  return (
    <section className="oh-connect ow-rise" style={delay(60)} aria-labelledby="oh-connect-title">
      <div className="oh-connect-head">
        <h2 id="oh-connect-title">쇼핑몰 연결까지 3단계</h2>
        <span>
          3단계 중 {current + 1}단계 · 가맹점 코드{' '}
          <span className="oh-code">{data.merchantCode}</span>
        </span>
      </div>
      <ol className="oh-connect-steps">
        {steps.map((step, i) => {
          const state = i < current ? 'is-done' : i === current ? 'is-current' : 'is-todo'
          return (
            <li
              key={step.title}
              className={`${state} ow-rise`}
              style={delay(140 + i * 90)}
              aria-current={i === current ? 'step' : undefined}
            >
              <span className="oh-connect-mark" aria-hidden="true">
                {i < current ? (
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
                    <path d="m5 12.5 4.5 4.5L19 7.5" />
                  </svg>
                ) : (
                  i + 1
                )}
              </span>
              <div className="oh-connect-copy">
                <h3>
                  {step.title}
                  {i < current && <span className="oh-connect-tag">완료</span>}
                  {i === current && <span className="oh-connect-tag is-now">지금 할 차례</span>}
                </h3>
                {/* 끝난 단계와 남은 단계는 제목만, 지금 단계만 설명과 버튼을 펼친다 */}
                {i === current && (
                  <>
                    <p>{step.body}</p>
                    {step.action && <div className="oh-connect-actions">{step.action}</div>}
                  </>
                )}
                {i > current && <p className="oh-connect-later">{step.body}</p>}
              </div>
            </li>
          )
        })}
      </ol>
    </section>
  )
}
