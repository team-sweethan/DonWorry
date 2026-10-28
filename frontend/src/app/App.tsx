import '@ant-design/v5-patch-for-react-19'
import { App as AntApp, ConfigProvider } from 'antd'
import koKR from 'antd/locale/ko_KR'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import { RouterProvider } from 'react-router-dom'
import { AuthProvider } from '../auth/AuthContext'
import { DemoProvider } from '../demo/DemoProvider'
import { antdTheme } from '../styles/theme'
import { router } from './router'
import '../styles/alert.css'
import '../styles/global.css'
import '../styles/ruleCatalog.css'

dayjs.locale('ko')

/** 공통 디자인 테마와 실제 인증 · 시연 상태를 제공한다. */
export function App() {
  return (
    <ConfigProvider locale={koKR} theme={antdTheme}>
      <AntApp message={{ top: 72, maxCount: 3 }}>
        <DemoProvider>
          <AuthProvider>
            <RouterProvider router={router} />
          </AuthProvider>
        </DemoProvider>
      </AntApp>
    </ConfigProvider>
  )
}
