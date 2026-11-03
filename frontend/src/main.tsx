import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
// 글꼴 파일을 함께 번들해 외부 CDN 없이 쓴다. 화면에 나온 글자만 나눠 내려받는다.
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css'
import { App } from './app/App'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
