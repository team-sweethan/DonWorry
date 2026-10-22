import '@ant-design/v5-patch-for-react-19'
import { App as AntApp, ConfigProvider } from 'antd'
import koKR from 'antd/locale/ko_KR'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import { RouterProvider } from 'react-router-dom'
import { DemoProvider } from '../demo/DemoContext'
import { antdTheme } from '../styles/theme'
import { router } from './router'
import '../styles/alert.css'
import '../styles/global.css'

dayjs.locale('ko')

/** 기존 앱을 감싸는 공통 디자인 테마. 아직 실제 인증은 연결하지 않았음. */
export function App() {
  return (
    <ConfigProvider locale={koKR} theme={antdTheme}>
      <AntApp message={{ top: 72, maxCount: 3 }}>
        <DemoProvider>
          <RouterProvider router={router} />
        </DemoProvider>
      </AntApp>
    </ConfigProvider>
  )
}
