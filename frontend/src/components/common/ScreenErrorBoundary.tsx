import { Component, type ReactNode, type ErrorInfo } from 'react'
import { Button, Result } from 'antd'

type Props = { children: ReactNode }
type State = { failed: boolean }

/** 페이지 내부 렌더링 오류만 보호. Header와 Sidebar는 유지됨. */
export class ScreenErrorBoundary extends Component<Props, State> {
  state: State = { failed: false }
  static getDerivedStateFromError(): State {
    return { failed: true }
  }
  componentDidCatch(error: Error, info: ErrorInfo) {
    // 운영 환경에서는 Sentry 등 공통 오류 수집기로 교체.
    console.error('Screen render error', error, info)
  }
  render() {
    if (this.state.failed) {
      return (
        <div className="dw-error-view">
          <Result
            status="error"
            title="화면을 표시하지 못했어요"
            subTitle="페이지를 다시 불러오거나 잠시 후 다시 시도해 주세요."
            extra={
              <Button type="primary" onClick={() => window.location.reload()}>
                새로고침
              </Button>
            }
          />
        </div>
      )
    }
    return this.props.children
  }
}
