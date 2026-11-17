import { useCallback, useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { canOpenPath } from '../config/navigation'
import { boothAccount } from './boothAccounts'
import {
  boothAudience,
  boothFlow,
  boothHandoff,
  boothSteps,
  boothTrackOf,
  resolveBoothSpot,
  resolveBoothStep,
  type BoothPosition,
  type BoothSpot,
  type BoothStep,
  type BoothTrack,
} from './boothSteps'
import { boothCaseForPath, parseBoothCase } from './boothSettings'
import {
  boothViewportBottom,
  clipBoothBox,
  observeBoothBar,
  revealBoothDetails,
  type BoothBox,
} from './boothSpotlight'
import { prepareBoothSpot, type BoothPreparation } from './boothPreparation'
import './booth-tour.css'

/**
 * 부스 시연용 자막 바 (로컬 개발 시연용 · 통합 브랜치에 머지하지 않는다).
 * 이어 보기: /booth 를 열면 소개 → 사장님 → 보안 직원 → 관리자를 한 창에서 잇는다 (계정은 boothAccounts.local.ts).
 * 창별 보기: 주소 끝에 ?booth=1 (브라우저 프로필마다 한 번). 끄기: ?booth=0.
 * 준비한 Case를 바로 열려면 ?boothCase=<Case ID>.
 */
const ENABLED_KEY = 'dw.booth'
const FLOW_KEY = 'dw.booth.flow'
const START_PATH = '/booth'
const CASE_KEY = 'dw.booth.case'
const REPORT_CASE_KEY = 'dw.booth.reportCase'
const ALL_KEY = 'dw.booth.all'
const HIDDEN_KEY = 'dw.booth.hidden'

function read(storage: Storage, key: string) {
  try {
    return storage.getItem(key)
  } catch {
    return null
  }
}

function write(storage: Storage, key: string, value: string | null) {
  try {
    if (value === null) storage.removeItem(key)
    else storage.setItem(key, value)
  } catch {
    // 저장소를 못 쓰면 이번 화면에서만 동작한다
  }
}

// 주소의 설정값은 앱이 뜰 때 한 번만 읽는다 — 이후 화면 이동으로 주소가 바뀌어도 유지된다
function applyQuery() {
  const params = new URLSearchParams(window.location.search)
  const booth = params.get('booth')
  if (booth === '1') {
    write(localStorage, ENABLED_KEY, '1')
    write(localStorage, FLOW_KEY, null)
  }
  if (booth === '0') {
    write(localStorage, ENABLED_KEY, null)
    write(localStorage, FLOW_KEY, null)
  }
  if (params.has('boothCase'))
    write(localStorage, CASE_KEY, parseBoothCase(params.get('boothCase')))
  if (params.has('boothReportCase'))
    write(localStorage, REPORT_CASE_KEY, parseBoothCase(params.get('boothReportCase')))
  // 시작 주소로 들어오면 처음 단계부터 다시 시작한다.
  // /booth 는 한 창 이어 보기, /booth/<역할> 은 그 역할만 보는 창별 보기다
  const path = window.location.pathname.replace(/\/+$/, '')
  if (path === START_PATH || path.startsWith(START_PATH + '/')) {
    write(localStorage, ENABLED_KEY, '1')
    write(localStorage, FLOW_KEY, path === START_PATH ? '1' : null)
    for (const key of ['flow', 'public', 'owner', 'security', 'admin'])
      write(sessionStorage, `dw.booth.position.${key}`, null)
    write(sessionStorage, HIDDEN_KEY, null)
    write(localStorage, ALL_KEY, null)
    const security = boothAccount('security')
    if (security?.caseId && !params.has('boothCase'))
      write(localStorage, CASE_KEY, parseBoothCase(security.caseId))
    if (security?.reportCaseId && !params.has('boothReportCase'))
      write(localStorage, REPORT_CASE_KEY, parseBoothCase(security.reportCaseId))
  }
}
applyQuery()

const enabled = read(localStorage, ENABLED_KEY) === '1'
const flow = read(localStorage, FLOW_KEY) === '1'
const configuredCaseId =
  parseBoothCase(read(localStorage, CASE_KEY)) ??
  parseBoothCase(boothAccount('security')?.caseId ?? null)
const reportCaseId =
  parseBoothCase(read(localStorage, REPORT_CASE_KEY)) ??
  parseBoothCase(boothAccount('security')?.reportCaseId ?? null)
// 이어 보기에는 계정이 준비된 역할만 넣는다
const flowTracks: BoothTrack[] = [
  'public',
  ...(['owner', 'security', 'admin'] as const).filter((track) => boothAccount(track) !== null),
]
const trackHome: Record<BoothTrack, string> = {
  public: '/',
  owner: '/owner',
  security: '/security/home',
  admin: '/admin/home',
}

function loadPosition(key: string): BoothPosition {
  const saved = read(sessionStorage, `dw.booth.position.${key}`)
  if (!saved) return null
  try {
    const value: unknown = JSON.parse(saved)
    if (
      typeof value === 'object' &&
      value !== null &&
      'stepId' in value &&
      'at' in value &&
      typeof value.stepId === 'string' &&
      typeof value.at === 'string'
    )
      return {
        stepId: value.stepId,
        at: value.at,
        spot: 'spot' in value && typeof value.spot === 'number' ? value.spot : 0,
      }
  } catch {
    // 깨진 값은 버린다
  }
  return null
}

/** 입력 중이거나 방향키를 직접 쓰는 요소에서는 단계 이동 키를 가로채지 않는다. */
function isTyping(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false
  return (
    target.isContentEditable ||
    target.closest(
      'input, textarea, select, [role="tab"], [role="combobox"], [role="listbox"], [role="slider"], [role="radio"], [role="menuitem"], [role="spinbutton"], .ant-picker, .ant-select',
    ) !== null
  )
}

function findSpot(sel: string, text: string | undefined): HTMLElement | null {
  let found: NodeListOf<HTMLElement>
  try {
    found = document.querySelectorAll<HTMLElement>(sel)
  } catch {
    return null
  }
  for (const element of found) {
    if (element.closest('.dw-booth')) continue
    if (text && !element.textContent?.includes(text)) continue
    // 이력과 계산 근거의 접힌 details를 펼친다. 준비 조작은 읽기 전용 허용 목록만 사용한다.
    revealBoothDetails(element)
    // 접혀 있거나 아직 그려지지 않은 요소는 건너뛴다
    if (element.getClientRects().length === 0) continue
    return element
  }
  return null
}

function sameBox(a: BoothBox | null, b: BoothBox | null) {
  if (a === null || b === null) return a === b
  return a.top === b.top && a.left === b.left && a.width === b.width && a.height === b.height
}

/**
 * 짚을 요소의 화면 위치. 화면이 늦게 그려지거나 스크롤 · 애니메이션으로 움직여도 따라가도록 짧은 간격으로 다시 잰다.
 * 요소를 처음 찾으면 한 번만 화면 안으로 끌어온다.
 */
function useSpotBox(spot: BoothSpot | null, key: string, padding: number) {
  const sel = spot?.sel ?? null
  const text = spot?.text
  const note = spot?.note ?? ''
  const fallbackSel = spot?.fallback?.sel
  const fallbackText = spot?.fallback?.text
  const fallbackNote = spot?.fallback?.note
  const preparationKey = (spot?.prepare ?? []).join(',')
  const [measured, setMeasured] = useState<{
    key: string
    box: BoothBox | null
    note: string
    waiting: boolean
  }>({ key: '', box: null, note: '', waiting: true })
  useEffect(() => {
    if (sel === null) return
    // 화면이 늦게 그려져 요소가 밀려나면 다시 끌어온다 (진행자가 직접 스크롤해도 계속 되돌리지 않게 몇 번만)
    let scrolls = 0
    let lastScroll = 0
    const clicked = new Set<BoothPreparation>()
    const preparations = preparationKey ? (preparationKey.split(',') as BoothPreparation[]) : []
    const findVisible = (selector: string) =>
      Array.from(document.querySelectorAll(selector)).find(
        (element) => element.getClientRects().length > 0 && !element.closest('[inert]'),
      ) ?? null
    const measure = () => {
      const ready = prepareBoothSpot(preparations, clicked, findVisible, (element) => {
        element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
      })
      if (!ready) {
        setMeasured((current) =>
          current.key === key && current.waiting && current.box === null
            ? current
            : {
                key,
                box: null,
                note: '실제 데이터를 불러오고 필요한 화면을 펼치고 있습니다.',
                waiting: true,
              },
        )
        return
      }
      const resolved = resolveBoothSpot(
        {
          sel,
          text,
          note,
          fallback:
            fallbackSel && fallbackNote
              ? { sel: fallbackSel, text: fallbackText, note: fallbackNote }
              : undefined,
        },
        (target) => findSpot(target.sel, target.text),
      )
      const element = resolved.target
      let box: BoothBox | null = null
      if (element) {
        const rect = element.getBoundingClientRect()
        const usable = boothViewportBottom(
          window.innerHeight,
          document.querySelector<HTMLElement>('aside.dw-booth')?.getBoundingClientRect() ?? null,
        )
        const shown = Math.min(rect.bottom, usable) - Math.max(rect.top, 0)
        const enough = shown >= Math.min(rect.height, usable) * 0.6
        if (!enough && scrolls < 4 && Date.now() - lastScroll > 800) {
          scrolls += 1
          lastScroll = Date.now()
          element.scrollIntoView({
            block: rect.height > usable * 0.6 ? 'start' : 'center',
            behavior: 'smooth',
          })
        }
        box = clipBoothBox(rect, window.innerWidth, usable, padding)
      }
      setMeasured((current) =>
        current.key === key &&
        sameBox(current.box, box) &&
        current.note === resolved.note &&
        current.waiting === resolved.waiting
          ? current
          : { key, box, note: resolved.note, waiting: resolved.waiting },
      )
    }
    const first = requestAnimationFrame(measure)
    const timer = window.setInterval(measure, 200)
    window.addEventListener('scroll', measure, true)
    window.addEventListener('resize', measure)
    return () => {
      // 원문 번호를 요청하지 않고 입력 양식만 보여 준 뒤 다음 박스에서 닫는다.
      if (preparations.includes('contact-form'))
        document.querySelector<HTMLButtonElement>('.dw-contact-raw-modal .ant-modal-close')?.click()
      cancelAnimationFrame(first)
      window.clearInterval(timer)
      window.removeEventListener('scroll', measure, true)
      window.removeEventListener('resize', measure)
    }
  }, [sel, text, note, fallbackSel, fallbackText, fallbackNote, preparationKey, key, padding])
  return sel !== null && measured.key === key ? measured : { box: null, note, waiting: true }
}

export function BoothTour() {
  if (!enabled) return null
  return <BoothBar />
}

const startTracks: Record<string, BoothTrack> = {
  public: 'public',
  owner: 'owner',
  security: 'security',
  admin: 'admin',
}

/**
 * 시작 주소. 남아 있는 로그인을 끊고 처음 단계로 보낸다.
 * /booth 는 소개 화면에서 이어 보기를, /booth/<owner|security|admin> 은 그 계정으로 로그인해 그 역할 화면을 연다.
 */
export function BoothStart() {
  const { store } = useAuth()
  const navigate = useNavigate()
  const { role } = useParams()
  const track = role ? (startTracks[role] ?? null) : null
  const account = track ? boothAccount(track) : null
  const [failed, setFailed] = useState(false)
  const unknown = role !== undefined && (track === null || (track !== 'public' && account === null))
  useEffect(() => {
    if (unknown) return
    let cancelled = false
    void (async () => {
      await store.logout().catch(() => undefined)
      if (cancelled) return
      if (!track || !account) {
        void navigate('/', { replace: true })
        return
      }
      try {
        if (!(await store.login(account.loginId, account.password))) {
          if (!cancelled) setFailed(true)
          return
        }
        if (!cancelled) void navigate(trackHome[track], { replace: true })
      } catch {
        if (!cancelled) setFailed(true)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [store, navigate, track, account, unknown])
  return (
    <div className="dw-error-view" role="status">
      <p>
        {unknown
          ? '이 역할의 계정이 boothAccounts.local.ts에 없습니다.'
          : failed
            ? '로그인하지 못했습니다. 계정과 백엔드 상태를 확인한 뒤 새로고침해 주세요.'
            : '부스 시연을 준비하고 있습니다.'}
      </p>
    </div>
  )
}

function BoothBar() {
  const barRef = useCallback((bar: HTMLElement | null) => {
    if (bar) return observeBoothBar(bar, document.body)
  }, [])
  const { user, status, store } = useAuth()
  const navigate = useNavigate()
  const pathname = useLocation().pathname.replace(/\/+$/, '') || '/'
  const [rememberedCaseId, setRememberedCaseId] = useState(configuredCaseId)
  const caseId = boothCaseForPath(pathname, rememberedCaseId, reportCaseId)
  if (caseId !== rememberedCaseId) setRememberedCaseId(caseId)
  useEffect(() => {
    write(localStorage, CASE_KEY, caseId)
  }, [caseId])
  const onStart = pathname === START_PATH || pathname.startsWith(START_PATH + '/')
  const signedIn = status === 'authenticated' ? user : null
  const track = boothTrackOf(signedIn)
  const [all, setAll] = useState(() => read(localStorage, ALL_KEY) === '1')
  const [hidden, setHidden] = useState(() => read(sessionStorage, HIDDEN_KEY) === '1')
  const [positions, setPositions] = useState<Record<string, BoothPosition>>({})
  // 역할을 바꾸는 중인 단계. error가 있으면 로그인에 실패한 것이다
  const [switching, setSwitching] = useState<{ stepId: string; error: boolean } | null>(null)
  const positionKey = flow ? 'flow' : track
  const position =
    positionKey in positions ? (positions[positionKey] ?? null) : loadPosition(positionKey)

  const canOpen = (path: string) => !signedIn || canOpenPath(signedIn, path)
  const steps = flow
    ? boothFlow(flowTracks, {
        all,
        caseId,
        reportCaseId,
        evaluationId: boothAccount('security')?.evaluationId,
      }).filter((step) => step.track !== track || !step.path || canOpen(step.path))
    : boothSteps(track, {
        all,
        caseId,
        reportCaseId,
        evaluationId: boothAccount('security')?.evaluationId,
        canOpen: (path) => track === 'public' || canOpen(path),
      })
  // 자막은 지금 로그인한 역할의 단계 안에서만 화면을 따라간다
  const here = flow ? steps.filter((step) => step.track === track) : steps
  const step =
    (switching && steps.find((candidate) => candidate.id === switching.stepId)) ||
    resolveBoothStep(here.length > 0 ? here : steps, position, pathname)
  const index = step ? steps.indexOf(step) : -1
  const spots = step?.spots ?? []
  const spotIndex =
    step && position?.stepId === step.id
      ? Math.max(0, Math.min(position.spot, spots.length - 1))
      : 0
  const spot =
    hidden || switching || status === 'loading' || onStart ? null : (spots[spotIndex] ?? null)
  const spotlight = useSpotBox(
    spot,
    `${track}:${step?.id ?? ''}:${spotIndex}:${pathname}`,
    track === 'public' ? 24 : 6,
  )
  const box = spotlight.box

  const save = (next: NonNullable<BoothPosition>) => {
    setPositions((current) => ({ ...current, [positionKey]: next }))
    write(sessionStorage, `dw.booth.position.${positionKey}`, JSON.stringify(next))
  }
  // 다른 역할의 단계로 넘어갈 때: 로그아웃 → 그 역할 계정으로 로그인 → 화면 이동
  const switchTo = async (
    target: BoothStep,
    targetTrack: BoothTrack,
    next: NonNullable<BoothPosition>,
  ) => {
    setSwitching({ stepId: target.id, error: false })
    try {
      if (signedIn) await store.logout()
      const account = boothAccount(targetTrack)
      if (account && !(await store.login(account.loginId, account.password)))
        throw new Error('login')
      save(next)
      // 로그인 화면의 자동 이동이 먼저 끝난 뒤에 원하는 화면으로 보낸다
      await new Promise((resolve) => window.setTimeout(resolve, 150))
      await navigate(target.path ?? trackHome[targetTrack])
      setSwitching(null)
    } catch {
      setSwitching({ stepId: target.id, error: true })
    }
  }
  const atStart = index <= 0 && spotIndex <= 0
  const atEnd = index >= steps.length - 1 && spotIndex >= spots.length - 1
  // 한 단계 안에서는 박스가 하나씩 옮겨 가고, 박스를 다 짚으면 다음 단계로 넘어간다
  const go = (offset: 1 | -1) => {
    if (switching) {
      // 실패 안내를 닫는다. 진행 중에는 아무것도 하지 않는다
      if (switching.error) setSwitching(null)
      return
    }
    if (!step || (offset > 0 && spot && spotlight.waiting)) return
    const nextSpot = spotIndex + offset
    if (nextSpot >= 0 && nextSpot < spots.length) {
      save({ stepId: step.id, at: pathname, spot: nextSpot })
      return
    }
    const target = steps[index + offset]
    if (!target) return
    const next = {
      stepId: target.id,
      at: target.path ?? pathname,
      spot: offset > 0 ? 0 : Math.max((target.spots?.length ?? 0) - 1, 0),
    }
    const targetTrack = target.track ?? track
    if (flow && targetTrack !== track) {
      void switchTo(target, targetTrack, next)
      return
    }
    save(next)
    if (target.path && target.path !== pathname) void navigate(target.path)
  }
  const toggleAll = () => {
    write(localStorage, ALL_KEY, all ? null : '1')
    setAll(!all)
  }
  const toggleHidden = () => {
    write(sessionStorage, HIDDEN_KEY, hidden ? null : '1')
    setHidden(!hidden)
  }

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey || isTyping(event.target)) return
      // 발표용 리모컨은 PageDown · PageUp을 보낸다
      if (event.key === 'ArrowRight' || event.key === 'PageDown') {
        event.preventDefault()
        go(1)
      } else if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
        event.preventDefault()
        go(-1)
      } else if (event.key === 'h' || event.key === 'H') toggleHidden()
      else if (event.key === 'm' || event.key === 'M') toggleAll()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  // 로그인 확인 중에는 공개 단계가 잠깐 보이지 않게 기다린다
  if ((status === 'loading' && !switching) || !step || onStart) return null
  if (hidden)
    return (
      <button type="button" className="dw-booth-reopen" onClick={toggleHidden}>
        안내 열기 (H)
      </button>
    )

  const shownTrack = flow ? (step.track ?? track) : track
  const audience = boothAudience[shownTrack]
  const detail = switching
    ? switching.error
      ? '로그인하지 못했습니다. boothAccounts.local.ts의 계정과 백엔드 상태를 확인한 뒤 다시 눌러 주세요.'
      : `${audience.label} 화면으로 바꾸는 중입니다.`
    : spot
      ? spotlight.note
      : step.detail
  return (
    <>
      {box && (
        <div className="dw-booth-box" style={box} aria-hidden="true">
          {spots.length > 1 && <span className="dw-booth-box-number">{spotIndex + 1}</span>}
        </div>
      )}
      <aside ref={barRef} className="dw-booth" aria-label="부스 시연 안내">
        <div className={`dw-booth-audience${audience.customer ? ' is-customer' : ''}`}>
          {audience.label}
        </div>
        <div className="dw-booth-text" aria-live="polite">
          <p className="dw-booth-headline">{step.headline}</p>
          <p className="dw-booth-detail">{detail}</p>
          {(step.action || (!flow && atEnd)) && (
            <p className="dw-booth-action">
              진행 · {!flow && atEnd ? boothHandoff[track] : step.action}
            </p>
          )}
        </div>
        <div className="dw-booth-controls">
          <span className="dw-booth-count">
            {index + 1} / {steps.length}
            {spots.length > 1 && (
              <span className="dw-booth-count-spot">
                {' '}
                · 박스 {spotIndex + 1}/{spots.length}
              </span>
            )}
            {spot && spotlight.waiting && (
              <span className="dw-booth-count-spot"> · 화면 준비 확인</span>
            )}
          </span>
          <div className="dw-booth-buttons">
            <button type="button" onClick={() => go(-1)} disabled={atStart} aria-label="이전">
              ←
            </button>
            <button
              type="button"
              onClick={() => go(1)}
              disabled={atEnd || (!!spot && spotlight.waiting)}
              aria-label="다음"
            >
              →
            </button>
          </div>
          <div className="dw-booth-options">
            <button type="button" onClick={toggleAll} aria-pressed={all}>
              {all ? '추가 설명 보는 중 (M)' : '기본 코스 · 전체 4~5분 (M)'}
            </button>
            <button type="button" onClick={toggleHidden}>
              숨기기 (H)
            </button>
          </div>
        </div>
      </aside>
    </>
  )
}
