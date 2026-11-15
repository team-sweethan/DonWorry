import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { landingScreens, type LandingScreen } from './landingScreens'
import './LandingWorkspace.css'
import './LandingScreenPreview.css'

const WorkspaceFrame = lazy(() =>
  import('./LandingWorkspace').then((module) => ({ default: module.LandingWorkspaceFrame })),
)

function ScreenFrame({ screen }: { screen: LandingScreen }) {
  return (
    <Suspense
      fallback={
        <div className="landing-preview-loading" role="status">
          화면 예시를 불러오고 있어요.
        </div>
      }
    >
      <WorkspaceFrame screen={screen} />
    </Suspense>
  )
}

export function LandingScreenPreview({ screen }: { screen: LandingScreen }) {
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
          <ScreenFrame screen={screen} />
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
            <ScreenFrame screen={screen} />
          </div>
        </dialog>
      )}
    </>
  )
}
