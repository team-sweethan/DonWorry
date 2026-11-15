import { useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { PageHeader } from '../../components/common/PageHeader'
import { navByRole } from '../../config/navigation'
import { ManagementLayout } from '../../layouts/AdminLayout'
import { MerchantCaseRowItem } from '../owner/MerchantCaseCard'
import { SettlementPrototype } from '../owner/SettlementPrototype'
import { CashFlowOverview } from '../owner/CashFlowOverview'
import { SettlementPeriodControl } from '../owner/SettlementPeriodControl'
import { FundsPeriodControl } from '../owner/FundsPeriodControl'
import { previewPayoutReport, previewSettlements } from '../owner/settlementPreviewData'
import type { MerchantCaseDetail } from '../../types/merchantCase'
import '../owner/merchant-case-list.css'
import '../owner/finance-preview.css'
import '../owner/owner-finance-skin.css'
import './LandingWorkspace.css'

import type { LandingScreen } from './landingScreens'

const today = '2026-11-15'
const noop = () => {}
const cases: MerchantCaseDetail[] = [
  {
    caseId: 'landing-held',
    displayStatus: 'INVESTIGATING',
    createdAt: '2026-11-15T05:31:00Z',
    closedAt: null,
    summary: null,
    payments: [
      {
        paymentId: 'EXAMPLE-01',
        amount: 1280000,
        requestedAt: '2026-11-15T05:31:00Z',
        paymentStatus: 'HELD',
        decision: null,
        customerCanceled: false,
      },
    ],
  },
  {
    caseId: 'landing-complete',
    displayStatus: 'CLOSED',
    createdAt: '2026-11-14T02:18:00Z',
    closedAt: '2026-11-14T02:40:00Z',
    summary: null,
    payments: [
      {
        paymentId: 'EXAMPLE-02',
        amount: 324000,
        requestedAt: '2026-11-14T02:18:00Z',
        paymentStatus: 'CAPTURED',
        decision: 'ACCEPT',
        customerCanceled: false,
      },
    ],
  },
]

let balance = 2400000
const forecastDays = Array.from({ length: 7 }, (_, i) => {
  const opening = balance
  const expenses = i === 3 ? 3280000 : 320000
  balance = opening + 420000 - expenses
  return {
    date: `2026-11-${15 + i}`,
    opening,
    completed: 0,
    scheduled: 420000,
    expenses,
    closing: balance,
  }
})

function ActionScreen() {
  return (
    <div className="mcl-page">
      <PageHeader
        title="조치 결과"
        description="이상한 결제를 돈워리가 잠시 멈추고 확인한 기록이에요."
      />
      <section className="mcl-hero">
        <p className="mcl-hero-line">
          아직 처리가 끝나지 않은 결제가 <strong className="mcl-mark">1건, 1,280,000원</strong>{' '}
          있어요
        </p>
        <p className="mcl-hero-sub">
          끝날 때까지 이 돈은 정산되지 않아요. 사장님이 하실 일은 없어요.
        </p>
      </section>
      {cases.map((detail, index) => (
        <MerchantCaseRowItem
          key={detail.caseId}
          caseId={detail.caseId}
          row={{ ...detail, paymentCount: detail.payments.length }}
          state={{ status: 'ready', detail, stale: false, failed: false }}
          open={index === 0}
          onToggle={noop}
          onRetry={noop}
          isNew={index === 0}
          settlementLink={false}
        />
      ))}
    </div>
  )
}

// 공개 페이지에서는 인증·업무 API 없이 실제 표시 컴포넌트에 예시 데이터만 전달한다.
export function LandingWorkspace({ screen }: { screen: LandingScreen }) {
  return (
    <div className="landing-workspace" data-screen={screen} aria-hidden="true" inert>
      <ManagementLayout
        role="owner"
        home="/owner"
        groups={navByRole.owner}
        profile={{ name: '김사장', roleLabel: '사장님 · 케이크샵 강남점' }}
      >
        {screen === 'actions' ? (
          <ActionScreen />
        ) : screen === 'settlements' ? (
          <div className="finance-preview finance-surface">
            <SettlementPrototype
              actualEntries={previewSettlements(new Date(`${today}T12:00:00`))}
              periodControls={
                <SettlementPeriodControl
                  today={today}
                  value={{ from: '2026-11-01', to: '2026-11-30' }}
                  onChange={noop}
                />
              }
              todayValue={today}
              period={{ from: '2026-11-01', to: '2026-11-30' }}
              payoutReport={previewPayoutReport(
                new Date(`${today}T12:00:00`),
                '2026-11-01',
                '2026-11-30',
                0,
                0,
                1,
              )}
            />
          </div>
        ) : (
          <div className="finance-preview finance-surface">
            <PageHeader
              title="자금 현황"
              description="등록한 지출을 감당할 돈이 있는지, 부족한 날은 언제인지 미리 확인하세요."
            />
            <FundsPeriodControl
              today={today}
              value={{ from: today, to: '2026-11-21' }}
              min={today}
              max="2027-11-15"
              onChange={noop}
            />
            <CashFlowOverview
              currentBalance={2400000}
              graphDate={today}
              days={forecastDays}
              incomes={forecastDays.map((d) => ({
                id: `income-${d.date}`,
                date: d.date,
                title: '예상 지급',
                amount: d.scheduled,
                note: '예시 · 환불 차감 후 예상 유입',
              }))}
              costs={forecastDays.map((d) => ({
                id: `cost-${d.date}`,
                date: d.date,
                title: d.date.endsWith('18') ? '임대료' : '재료비',
                amount: d.expenses,
                note: '등록한 예정 지출',
              }))}
              onDetails={noop}
            />
          </div>
        )}
      </ManagementLayout>
    </div>
  )
}

// iframe이 데스크톱 뷰포트를 유지하므로 휴대폰에서도 같은 화면을 통째로 축소한다.
export function LandingWorkspaceFrame({ screen }: { screen: LandingScreen }) {
  const [body, setBody] = useState<HTMLElement | null>(null)
  const content: ReactNode = <LandingWorkspace screen={screen} />
  return (
    <iframe
      title="사장님 서비스 화면 예시"
      className="landing-workspace-frame"
      srcDoc="<!doctype html><html lang='ko'><head></head><body></body></html>"
      aria-hidden="true"
      inert
      tabIndex={-1}
      onLoad={(event) => {
        const target = event.currentTarget.contentDocument
        if (!target) return
        target.head.replaceChildren(
          ...Array.from(document.querySelectorAll('style, link[rel="stylesheet"]'), (node) =>
            node.cloneNode(true),
          ),
        )
        const style = target.createElement('style')
        style.textContent = 'html { scrollbar-gutter: auto; } body { margin: 0; overflow: hidden; }'
        target.head.append(style)
        setBody(target.body)
      }}
    >
      {body && createPortal(content, body)}
    </iframe>
  )
}
