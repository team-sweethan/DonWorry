import { Button, Result } from 'antd'
import { useNavigate } from 'react-router-dom'

/**
 * 진행 중인 사건을 담당이 아닌 직원이 열었을 때 (#612). 서버가 거부를 접근 기록에 남긴다.
 * 상세 · 보고서 전문 · 과거 사건 비교가 같은 안내를 쓴다.
 */
export function CaseForbidden({ listPath = '/security/cases' }: { listPath?: string }) {
  const navigate = useNavigate()
  return (
    <div className="dw-error-view">
      <Result
        status="403"
        title="이 사건의 담당자만 볼 수 있어요"
        subTitle="진행 중인 사건은 담당 보안 직원만 열 수 있어요. 담당이 필요하면 관리자에게 요청하세요."
        extra={
          <Button type="primary" onClick={() => navigate(listPath)}>
            사건 목록으로
          </Button>
        }
      />
    </div>
  )
}
