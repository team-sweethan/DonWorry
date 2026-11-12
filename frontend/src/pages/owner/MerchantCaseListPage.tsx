import { useEffect, useState } from 'react'
import { ReloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Skeleton } from 'antd'
import { useParams } from 'react-router-dom'
import { getMerchantCases } from '../../api/merchantCases'
import { ApiError } from '../../api/client'
import { PageHeader } from '../../components/common/PageHeader'
import { ScreenResult } from '../../components/common/ScreenResult'
import { useAuth } from '../../auth/useAuth'
import type {
  MerchantCaseList,
  MerchantCaseRow,
  MerchantCaseStatus,
} from '../../types/merchantCase'
import { MerchantCaseRowItem } from './MerchantCaseCard'
import { caseStatus, heldMoney } from './merchantCaseProgress'
import { StatusMark } from './StatusMark'
import { RollingNumber } from './RollingNumber'
import { MerchantCaseProcess } from './MerchantCaseProcess'
import { useMerchantCaseDetails } from './useMerchantCaseDetails'
import './merchant-case-list.css'
import '../../styles/ownerMotion.css'

const PAGE = 10
const DAY = 24 * 60 * 60 * 1000

type CaseFilter = 'ALL' | MerchantCaseStatus

/** DB DATETIME은 UTC다. 시간대 없는 응답도 브라우저의 로컬 시각으로 해석하지 않는다 */
const utc = (value: string) => Date.parse(/(Z|[+-]\d{2}:\d{2})$/.test(value) ? value : `${value}Z`)
const kstDate = (time: number) =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date(time))
/** 끝난 사건을 한국 날짜 기준으로 묶는다 — 오늘 · 어제 · 지난 7일 · 그 전 */
function dayGroup(createdAt: string, today: string) {
  const days = Math.round((Date.parse(today) - Date.parse(kstDate(utc(createdAt)))) / DAY)
  return days <= 0 ? '오늘' : days === 1 ? '어제' : days < 7 ? '지난 7일' : '그 전'
}
const won = (n: number) => `${n.toLocaleString('ko-KR')}원`

/**
 * 고객사 "보안 조치 결과" (fds 정본 02 §2.12 ②, #205).
 * 결제 · 분쟁 목록 화면(Stripe 결제 목록 · PayPal 분쟁 센터 · 스마트스토어 클레임 관리)처럼
 * 개수가 붙은 필터 한 줄 + 열이 고정된 목록으로 위아래 비교가 되게 한다. 움직임은 상호작용(펼치기 · 필터)에만 둔다.
 * - 맨 위 한 문장으로 "아직 안 끝난 돈"을 답한다 (이 화면에서 숫자를 세는 연출은 여기 한 곳)
 * - 진행 중 사건은 상세를 불러 줄에 금액 · 결과를 채운다 (보통 몇 건). 끝난 사건은 펼칠 때만 부른다
 * - 한 번에 한 사건만 펼친다. /owner/actions/:id 로 들어오면 그 사건을 펼친 채 그 자리로 이동한다
 * 로그인한 사장님 가맹점의 사건만 서버가 돌려준다. 화면의 메뉴 권한은 편의이고 인가는 서버가 한다.
 */
export function MerchantCaseListPage() {
  const { id: linkedId } = useParams()
  // useFeedback()은 렌더마다 새 객체라 effect 의존성에는 App이 주는 message를 쓴다
  const { message } = App.useApp()
  const { user } = useAuth()
  // 링크만 달 뿐 API를 부르지 않는다. 권한이 없으면 링크를 숨겨 403 화면으로 보내지 않는다
  const settlementLink = !!user?.permissions.includes('FINANCE_VIEW')
  const [now] = useState(() => Date.now())
  const [refreshKey, setRefreshKey] = useState(0)
  // undefined: 아직 고르지 않음(기본값을 쓴다) · null: 모두 접음
  const [picked, setPicked] = useState<string | null | undefined>(undefined)
  const [finishedLimit, setFinishedLimit] = useState(PAGE)
  const [filter, setFilter] = useState<CaseFilter>('ALL')
  // 어떤 새로고침에 대한 응답인지 함께 저장한다. failed는 조회 실패 — 빈 목록과 구분하고 직전 목록은 유지한다.
  const [result, setResult] = useState<{
    refreshKey: number
    list: MerchantCaseList | null
    failed: boolean
    forbidden: boolean
  } | null>(null)

  useEffect(() => {
    let alive = true
    getMerchantCases()
      .then((list) => {
        if (alive) setResult({ refreshKey, list, failed: false, forbidden: false })
      })
      .catch((error: unknown) => {
        if (!alive) return
        const forbidden = error instanceof ApiError && error.status === 403
        if (!forbidden) message.error('조치 결과를 불러오지 못했습니다. 잠시 후 다시 시도하세요.')
        setResult((prev) => ({ refreshKey, list: prev?.list ?? null, failed: true, forbidden }))
      })
    return () => {
      alive = false
    }
  }, [refreshKey, message])

  const list = result?.list
  const cases = list?.cases ?? []
  const active = cases.filter((c) => c.displayStatus !== 'CLOSED')
  const finished = cases.filter((c) => c.displayStatus === 'CLOSED')
  const linkedMissing = !!list && !!linkedId && !cases.some((c) => c.caseId === linkedId)
  // 주소로 연 끝난 사건이 "더 보기" 뒤에 있으면 거기까지 보여준다
  const linkedIndex = finished.findIndex((c) => c.caseId === linkedId)
  const limit = Math.max(finishedLimit, linkedIndex + 1)
  const openId = picked === undefined ? (linkedId ?? active[0]?.caseId ?? null) : picked
  const detailIds = [
    ...active.map((c) => c.caseId),
    ...(openId && !active.some((c) => c.caseId === openId) ? [openId] : []),
  ]
  const details = useMerchantCaseDetails(detailIds, refreshKey)

  // 주소로 연 사건으로 한 번 이동한다. 목록이 처음 그려진 뒤에만 이동하고, 새로고침마다 다시 움직이지 않는다
  const listReady = !!list
  useEffect(() => {
    if (!listReady || !linkedId) return
    const target = document.getElementById(`case-${linkedId}`)
    const smooth = !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    target?.scrollIntoView({ block: 'center', behavior: smooth ? 'smooth' : 'auto' })
  }, [listReady, linkedId])

  // 맨 위 문장 · 필터 금액: 진행 중 사건 상세가 모두 오면 아직 보류 중인 결제만 세고 더한다
  const activeDetails = active.map((c) => details.get(c.caseId).detail)
  const buckets =
    active.length > 0 && activeDetails.every((d) => d !== null)
      ? heldMoney(activeDetails as NonNullable<(typeof activeDetails)[number]>[])
      : null
  const heldCount = buckets
    ? buckets.reduce((n, b) => n + b.count, 0)
    : active.reduce((n, c) => n + c.paymentCount, 0)
  const heldTotal = buckets ? buckets.reduce((n, b) => n + b.amount, 0) : null
  const stillHeld = active.length > 0 && heldCount > 0

  if (result?.forbidden) return <ScreenResult kind="403" homePath="/owner" />
  const loading = result?.refreshKey !== refreshKey

  // 목록 거르기: 상태 필터. 이미 받은 목록만 거르고 API를 더 부르지 않는다
  // 사건 번호는 화면에 보이지 않는다 — 차례로 붙는 내부 번호라 번호 차이로 플랫폼 전체 사건 수를 짐작할 수 있다
  const counts: Record<CaseFilter, number> = {
    ALL: cases.length,
    INVESTIGATING: cases.filter((c) => c.displayStatus === 'INVESTIGATING').length,
    PROCESSING: cases.filter((c) => c.displayStatus === 'PROCESSING').length,
    CLOSED: finished.length,
  }
  const filterMoney: Partial<Record<CaseFilter, number>> = buckets
    ? {
        INVESTIGATING: buckets[0]!.amount,
        PROCESSING: buckets[1]!.amount + buckets[2]!.amount,
      }
    : {}
  const activeShown = active.filter((c) => filter === 'ALL' || c.displayStatus === filter)
  const finishedShown = filter === 'ALL' || filter === 'CLOSED' ? finished : []
  const today = kstDate(now)
  const finishedGroups: { label: string; rows: MerchantCaseRow[] }[] = []
  for (const c of finishedShown.slice(0, limit)) {
    const label = dayGroup(c.createdAt, today)
    const last = finishedGroups[finishedGroups.length - 1]
    if (last?.label === label) last.rows.push(c)
    else finishedGroups.push({ label, rows: [c] })
  }
  const changeFilter = (next: CaseFilter) => {
    setFilter(next)
    setFinishedLimit(PAGE)
  }
  const row = (c: MerchantCaseRow | null, caseId: string) => (
    <div key={caseId} id={`case-${caseId}`} className="mcl-anchor">
      <MerchantCaseRowItem
        caseId={caseId}
        row={c}
        state={details.get(caseId)}
        open={openId === caseId}
        onToggle={() => setPicked(openId === caseId ? null : caseId)}
        onRetry={() => details.retry(caseId)}
        isNew={!!c && now - utc(c.createdAt) < DAY}
        settlementLink={settlementLink}
      />
    </div>
  )

  return (
    <div className="mcl-page">
      <PageHeader
        title="조치 결과"
        description="이상한 결제를 돈워리가 잠시 멈추고 확인한 기록이에요."
        actions={
          <Button
            icon={<ReloadOutlined />}
            loading={loading && !!result}
            onClick={() => setRefreshKey((k) => k + 1)}
          >
            새로고침
          </Button>
        }
      />
      {result?.failed && (
        <Alert
          type="error"
          showIcon
          message="조치 결과를 불러오지 못했습니다. 아래 건수와 목록은 실제와 다를 수 있습니다."
          action={
            <Button size="small" onClick={() => setRefreshKey((k) => k + 1)}>
              다시 시도
            </Button>
          }
        />
      )}

      {!list ? (
        loading && (
          <div role="status" aria-label="조치 결과를 불러오는 중">
            <Skeleton active paragraph={{ rows: 4 }} />
          </div>
        )
      ) : (
        <>
          <section className="mcl-hero" aria-live="polite">
            <p className="mcl-hero-line">
              {stillHeld ? (
                <>
                  아직 처리가 끝나지 않은 결제가{' '}
                  {/* 시연 연출: 자리마다 숫자가 굴러가 멈춘 뒤 형광펜이 한 번 그어진다 (동작 줄이기면 둘 다 없음) */}
                  <strong className="mcl-mark">
                    <RollingNumber value={heldCount} suffix="건" delay={150} />
                    {heldTotal !== null && (
                      <>
                        , <RollingNumber value={heldTotal} suffix="원" delay={300} />
                      </>
                    )}
                  </strong>{' '}
                  있어요
                </>
              ) : cases.length === 0 ? (
                '아직 멈춘 결제가 없어요'
              ) : (
                '지금 처리 중인 결제는 없어요'
              )}
            </p>
            <p className="mcl-hero-sub">
              {stillHeld
                ? '끝날 때까지 이 돈은 정산되지 않아요. 사장님이 하실 일은 없어요.'
                : cases.length === 0
                  ? '이상한 결제가 생기면 돈워리가 먼저 멈추고 여기에 알려 드려요.'
                  : active.length > 0
                    ? '진행 중인 사건의 결제는 모두 처리됐고, 사건을 마무리하고 있어요.'
                    : '멈췄던 결제는 모두 확인이 끝났어요.'}
              {cases.length > 0 && (
                <span className="mcl-hero-stats">
                  지금까지 정상 처리 결정 {list.overview.acceptDecisionCount}건 · 취소 결정{' '}
                  {list.overview.rejectDecisionCount}건
                </span>
              )}
            </p>
            {cases.length > 0 && (
              <MerchantCaseProcess
                newToday={cases.filter((c) => now - utc(c.createdAt) < DAY).length}
                checking={buckets ? buckets[0]! : null}
                applying={
                  buckets
                    ? {
                        count: buckets[1]!.count + buckets[2]!.count,
                        amount: buckets[1]!.amount + buckets[2]!.amount,
                      }
                    : null
                }
                checkingCases={counts.INVESTIGATING}
                applyingCases={counts.PROCESSING}
                closedCases={counts.CLOSED}
              />
            )}
            <details className="mcl-why">
              <summary>왜 결제가 멈추나요?</summary>
              {/* docs/settlement/merchant-guide.md 1·2절에 있는 내용만 쓴다. 대기 시간 · 결과를 미리 약속하지 않는다 */}
              <ul>
                <li>
                  이상 거래가 의심되면 돈워리가 승인된 결제의 매입을 잠시 멈추고 보안 담당자가
                  확인해요.
                </li>
                <li>
                  멈춘 동안에는 매입되지 않아 정산 예정금도 아직 없어요. 언제 끝날지는 미리 알 수
                  없어요.
                </li>
                <li>
                  정상 처리로 결정되면 매입되고, 매입한 날을 빼고 세 번째 영업일에 정산 예정이에요.
                </li>
                <li>취소로 결정되면 매입 전에 승인이 취소돼 정산에 들어가지 않아요.</li>
                <li>탐지 근거와 담당자 메모는 보안을 위해 알려 드리지 않아요.</li>
              </ul>
            </details>
          </section>

          {(cases.length > 0 || linkedMissing) && (
            <section className="mcl-board" aria-label="사건 목록">
              <div className="mcl-toolbar">
                <div className="mcl-filters" role="group" aria-label="상태로 거르기">
                  {(['ALL', 'INVESTIGATING', 'PROCESSING', 'CLOSED'] as const).map((key) => (
                    <button
                      key={key}
                      type="button"
                      className={filter === key ? 'is-on' : undefined}
                      aria-pressed={filter === key}
                      onClick={() => changeFilter(key)}
                    >
                      {key !== 'ALL' && <StatusMark tone={caseStatus[key].tone} size={14} />}
                      <span className="mcl-filter-name">
                        {key === 'ALL' ? '전체' : caseStatus[key].word}
                      </span>
                      <span className="mcl-filter-count">{counts[key]}</span>
                      {filterMoney[key] ? (
                        <span className="mcl-filter-money">{won(filterMoney[key]!)}</span>
                      ) : null}
                    </button>
                  ))}
                </div>
              </div>
              <div className="mcl-scroll">
                <div className="mcl-table">
                  <div className="mct-cols mcl-thead" aria-hidden="true">
                    <span>상태</span>
                    <span>발생</span>
                    <span className="mct-num">결제</span>
                    <span className="mct-money">금액</span>
                    <span>결과</span>
                    <span />
                  </div>
                  {linkedMissing && linkedId && row(null, linkedId)}
                  {activeShown.length > 0 && filter === 'ALL' && (
                    <h2 className="mcl-group">진행 중</h2>
                  )}
                  {activeShown.map((c) => row(c, c.caseId))}
                  {finishedGroups.map((g) => (
                    <div key={g.label} role="group" aria-label={`끝난 사건 · ${g.label}`}>
                      <h2 className="mcl-group">{g.label}</h2>
                      {g.rows.map((c) => row(c, c.caseId))}
                    </div>
                  ))}
                  {activeShown.length === 0 && finishedShown.length === 0 && !linkedMissing && (
                    <p className="mcl-no-match">
                      이 상태의 사건이 없어요.
                      <button type="button" onClick={() => changeFilter('ALL')}>
                        전체 보기
                      </button>
                    </p>
                  )}
                </div>
              </div>
              {finishedShown.length > limit && (
                <button
                  type="button"
                  className="mcl-more"
                  onClick={() => setFinishedLimit(limit + PAGE)}
                >
                  끝난 사건 더 보기 · {finishedShown.length - limit}건 남음
                </button>
              )}
              <p className="mcl-foot">
                금액 · 결과는 진행 중 사건과 펼쳐 본 사건에 보여요. 끝난 사건은 눌러서 펼치면
                나와요.
              </p>
            </section>
          )}
        </>
      )}
    </div>
  )
}
