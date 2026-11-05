import { useLayoutEffect, useRef } from 'react'
import { Button, Space } from 'antd'
import { Link, Outlet, useLocation, useMatches, useNavigationType } from 'react-router-dom'
import { ScreenResult } from '../components/common/ScreenResult'
import { shouldResetScroll, showRouteErrorHome } from '../utils/routeEffects.ts'

export function RouteLayout() {
  const { pathname } = useLocation()
  const navigationType = useNavigationType()
  const previousPath = useRef<string | null>(null)
  const matches = useMatches()
  const handle = matches.at(-1)?.handle
  const title =
    typeof handle === 'object' &&
    handle !== null &&
    'title' in handle &&
    typeof handle.title === 'string'
      ? handle.title
      : null
  useLayoutEffect(() => {
    document.title = title ? `${title} | 돈워리` : '돈워리'
  }, [title])
  useLayoutEffect(() => {
    if (shouldResetScroll(previousPath.current, pathname, navigationType))
      window.scrollTo({ top: 0, left: 0, behavior: 'instant' })
    previousPath.current = pathname
  }, [pathname, navigationType])
  return <Outlet />
}

export function RouteErrorPage() {
  const { pathname } = useLocation()
  useLayoutEffect(() => {
    document.title = '오류 | 돈워리'
  }, [])
  return (
    <ScreenResult
      kind="500"
      action={
        <Space>
          <Button type="primary" onClick={() => window.location.reload()}>
            새로고침
          </Button>
          {showRouteErrorHome(pathname) && <Link to="/">홈으로</Link>}
        </Space>
      }
    />
  )
}
