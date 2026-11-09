import { CreditCardOutlined, FileSearchOutlined, UserOutlined } from '@ant-design/icons'
import type { CaseSummary } from '../../types/case'
import { homeWorkflow } from '../../utils/securityHome'

type Props = {
  cases: CaseSummary[]
  active: boolean
}

export function HomeWorkflow({ cases, active }: Props) {
  const flow = homeWorkflow(cases)
  const stages = [
    {
      key: 'prepare',
      label: '접수·보고서 준비',
      count: flow.preparing,
      detail:
        flow.reportRunning > 0
          ? `보고서 생성 대기·진행 ${flow.reportRunning}건`
          : '생성됨 · 조사 중',
      icon: <FileSearchOutlined />,
      running: active && flow.reportRunning > 0,
    },
    {
      key: 'decision',
      label: '직원 판정',
      count: flow.decisionPending,
      detail: '직원 확인·판정 대기',
      icon: <UserOutlined />,
      running: false,
    },
    {
      key: 'finance',
      label: '금융 처리',
      count: flow.actionPending,
      detail: '매입·승인 취소 대기',
      icon: <CreditCardOutlined />,
      running: active && flow.actionPending > 0,
    },
  ]
  return (
    <section
      className={`security-home-workflow${active ? ' is-guide-active' : ''}`}
      aria-label="담당 사건 업무 흐름"
    >
      <div className="security-home-workflow-heading">
        <strong>담당 사건 업무 흐름</strong>
      </div>
      <ol className="security-home-stages">
        {stages.map((stage, index) => (
          <li
            key={stage.key}
            className={`security-home-stage is-${stage.key}${stage.running ? ' is-running' : ''}${stage.count === 0 ? ' is-empty' : ''}`}
          >
            {index === 0 && (
              <span className="security-home-guide-link" aria-hidden="true">
                <i />
              </span>
            )}
            <div className="security-home-stage-icon" aria-hidden="true">
              {stage.icon}
            </div>
            <span className="security-home-stage-order">0{index + 1}</span>
            <h3>{stage.label}</h3>
            <div className="security-home-stage-count">
              {stage.count}
              <small>건</small>
            </div>
            <p>{stage.detail}</p>
          </li>
        ))}
      </ol>
    </section>
  )
}
