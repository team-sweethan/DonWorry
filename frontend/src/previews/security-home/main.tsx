import '@ant-design/v5-patch-for-react-19'
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css'
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { App, Button, ConfigProvider, Segmented } from 'antd'
import koKR from 'antd/locale/ko_KR'
import { MemoryRouter } from 'react-router-dom'
import { ManagementBrand, ManagementLayout } from '../../layouts/AdminLayout'
import { navByRole } from '../../config/navigation'
import { SecurityHomeDashboard } from '../../pages/security/SecurityHomePage'
import { antdTheme } from '../../styles/theme'
import { sampleHomeCases } from './fixtures'
import '../../styles/global.css'
import './preview.css'

if (!import.meta.env.DEV) throw new Error('개발 서버에서만 사용하는 샘플 화면입니다.')

export function Preview() {
  const { message } = App.useApp()
  const [scenario, setScenario] = useState('sample')
  const [replay, setReplay] = useState(0)
  const [cases, setCases] = useState(() => sampleHomeCases())
  const [newIds, setNewIds] = useState<string[]>([])
  const [updatedAt, setUpdatedAt] = useState(() => Date.now())
  const explain = () => {
    void message.info('샘플 미리보기입니다. 실제 사건으로 이동하지 않습니다.')
  }
  return (
    <ManagementLayout
      role="security"
      groups={navByRole.security}
      header={
        <header className="dw-header">
          <ManagementBrand role="security" home="/security/home" />
          <strong className="home-preview-badge">샘플 미리보기 · 실제 업무 데이터 아님</strong>
        </header>
      }
    >
      <div className="home-preview-controls">
        <Segmented
          value={scenario}
          options={[
            { label: '사건 있는 상태', value: 'sample' },
            { label: '빈 상태', value: 'empty' },
          ]}
          onChange={(value) => {
            setScenario(value)
            setCases(value === 'sample' ? sampleHomeCases() : [])
            setNewIds([])
            setReplay((n) => n + 1)
          }}
        />
        <Button onClick={() => setReplay((n) => n + 1)}>등장 효과 다시 보기</Button>
        <Button
          onClick={() => {
            setScenario('sample')
            const id = `SAMPLE-NEW-${Date.now()}`
            const newCase = {
              ...sampleHomeCases()[2]!,
              caseId: id,
              createdAt: new Date().toISOString(),
              status: 'ACTION_FAILED' as const,
            }
            setCases((previous) => [newCase, ...previous])
            setNewIds([id])
            setUpdatedAt(Date.now())
          }}
        >
          새 사건 추가
        </Button>
        <a href="/security/home">실제 홈 열기 →</a>
      </div>
      <SecurityHomeDashboard
        key={replay}
        result={{ cases, refreshKey: 0, failed: false, receivedAt: updatedAt, newIds }}
        loading={false}
        myId={1}
        watchStopped={false}
        refresh={() => {
          setNewIds([])
          setUpdatedAt(Date.now())
          void message.success('샘플 현황을 갱신했습니다.')
        }}
        openList={explain}
        openCase={explain}
      />
    </ManagementLayout>
  )
}

createRoot(document.getElementById('root')!).render(
  <ConfigProvider locale={koKR} theme={antdTheme}>
    <App>
      <MemoryRouter initialEntries={['/security/home']}>
        <Preview />
      </MemoryRouter>
    </App>
  </ConfigProvider>,
)
