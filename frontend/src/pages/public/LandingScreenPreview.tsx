import { useEffect, useRef, useState, type ComponentType } from 'react'
import { createPreviewLoader } from './landingPreviewLoader'
import { landingScreens, type LandingScreen } from './landingScreens'
import './LandingWorkspace.css'
import './LandingScreenPreview.css'

type FrameComponent = ComponentType<{ screen: LandingScreen }>
const loadFrame = createPreviewLoader(() =>
  import('./LandingWorkspace').then((module) => module.LandingWorkspaceFrame),
)

function ScreenFrame({ screen, Frame }: { screen: LandingScreen; Frame?: FrameComponent }) {
  return Frame ? (
    <Frame screen={screen} />
  ) : (
    <div className="landing-preview-loading" role="status">
      화면 예시를 불러오고 있어요.
    </div>
  )
}

export function LandingScreenPreview({ screen }: { screen: LandingScreen }) {
  const [attempt, setAttempt] = useState(0)
  const [loaded, setLoaded] = useState<{
    attempt: number
    Frame?: FrameComponent
    failed: boolean
  } | null>(null)
  useEffect(() => {
    let active = true
    loadFrame().then(
      (Frame) => {
        if (active) setLoaded({ attempt, Frame, failed: false })
      },
      () => {
        if (active) setLoaded({ attempt, failed: true })
      },
    )
    return () => {
      active = false
    }
  }, [attempt])
  if (loaded?.attempt === attempt && loaded.failed)
    return (
      <div className="landing-preview-error" role="alert">
        <strong>화면 예시를 불러오지 못했어요.</strong>
        <p>
          연결 상태를 확인하고 다시 시도해 주세요. 서비스 설명과 이용 신청은 계속 사용할 수 있어요.
        </p>
        <button
          type="button"
          className="dw-btn dw-btn-tonal"
          onClick={() => setAttempt((value) => value + 1)}
        >
          미리보기 다시 시도
        </button>
      </div>
    )
  return (
    <PreviewGallery
      screen={screen}
      Frame={loaded?.attempt === attempt ? loaded.Frame : undefined}
    />
  )
}

function PreviewGallery({ screen, Frame }: { screen: LandingScreen; Frame?: FrameComponent }) {
  const viewport = useRef<HTMLDivElement>(null)
  const dialog = useRef<HTMLDialogElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const [scale, setScale] = useState(1)
  const [expanded, setExpanded] = useState(false)
  const title = landingScreens.find((item) => item.key === screen)!.title

  useEffect(() => {
    const node = viewport.current
    if (!node) return
    const observer = new ResizeObserver(([entry]) =>
      setScale(Math.min(1, entry.contentRect.width / 1280)),
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (!expanded) return
    const node = dialog.current
    if (!node) return
    const restoreTarget = trigger.current
    const overflow = document.documentElement.style.overflow
    document.documentElement.style.overflow = 'hidden'
    node.showModal()
    return () => {
      node.close()
      document.documentElement.style.overflow = overflow
      restoreTarget?.focus({ preventScroll: true })
    }
  }, [expanded])

  return (
    <>
      <div className="landing-preview-toolbar">
        <p className="landing-preview-pan-hint">화면을 좌우로 밀어 자세히 살펴보세요.</p>
        <button
          ref={trigger}
          type="button"
          className="dw-btn dw-btn-tonal"
          aria-haspopup="dialog"
          onClick={() => setExpanded(true)}
        >
          전체 화면 보기
        </button>
      </div>
      <div
        className="landing-preview-viewport"
        ref={viewport}
        role="region"
        aria-label={`${title} 화면 예시. 좌우로 스크롤할 수 있습니다.`}
        tabIndex={0}
      >
        <div className="landing-preview-overview" style={{ zoom: scale }}>
          <ScreenFrame screen={screen} Frame={Frame} />
        </div>
      </div>
      {expanded && (
        <dialog
          ref={dialog}
          className="landing-preview-dialog"
          aria-labelledby="landing-preview-dialog-title"
          onCancel={() => setExpanded(false)}
          onClose={() => setExpanded(false)}
        >
          <div className="landing-preview-dialog-head">
            <div>
              <h2 id="landing-preview-dialog-title">{title} 화면 예시</h2>
              <p>예시 데이터입니다. 화면을 좌우로 밀어 살펴보세요.</p>
            </div>
            <button
              type="button"
              className="dw-btn dw-btn-tonal"
              autoFocus
              onClick={() => setExpanded(false)}
            >
              닫기
            </button>
          </div>
          <div
            className="landing-preview-dialog-scroll"
            role="region"
            aria-label="서비스 화면 확대 보기"
            tabIndex={0}
          >
            <ScreenFrame screen={screen} Frame={Frame} />
          </div>
        </dialog>
      )}
    </>
  )
}
