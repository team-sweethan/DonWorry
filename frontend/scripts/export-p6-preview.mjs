import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { build } from 'vite'

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const output = path.resolve(
  process.argv[2] ?? path.join(frontend, '..', 'artifacts', 'DonWorry-P6-payout-preview.html'),
)
if (path.extname(output).toLowerCase() !== '.html') throw new Error('Output must be an HTML file')
const temporary = await fs.mkdtemp(path.join(frontend, '.p6-export.local.'))
try {
  const entry = path.join(temporary, 'main.tsx')
  await fs.writeFile(
    entry,
    `import React from 'react'
import { createRoot } from 'react-dom/client'
import '@ant-design/v5-patch-for-react-19'
import 'pretendard/dist/web/variable/pretendardvariable.css'
import { App as AntApp, ConfigProvider } from 'antd'
import koKR from 'antd/locale/ko_KR'
import { HashRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider } from '../src/auth/AuthContext'
import { antdTheme } from '../src/styles/theme'
import { FinancePreview } from '../src/pages/owner/FinancePreview'
import { LoanProductsPage } from '../src/pages/owner/LoanProductsPage'
import '../src/styles/global.css'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
dayjs.locale('ko')
createRoot(document.getElementById('root')!).render(<ConfigProvider locale={koKR} theme={antdTheme}><AntApp><AuthProvider><HashRouter><main style={{padding:24}}><Routes><Route path="/dev/settlements" element={<FinancePreview />} /><Route path="/dev/funds" element={<FinancePreview />} /><Route path="/dev/funds/loans" element={<LoanProductsPage />} /><Route path="*" element={<Navigate to="/dev/settlements" replace />} /></Routes></main></HashRouter></AuthProvider></AntApp></ConfigProvider>)
`,
  )
  const bundled = await build({
    root: frontend,
    configFile: path.join(frontend, 'vite.config.ts'),
    base: './',
    build: {
      write: false,
      cssCodeSplit: false,
      assetsInlineLimit: () => true,
      rolldownOptions: {
        input: entry,
        output: { format: 'es', inlineDynamicImports: true },
      },
    },
  })
  const files = (Array.isArray(bundled) ? bundled : [bundled]).flatMap((result) => result.output)
  const js = files
    .filter((file) => file.type === 'chunk')
    .map((file) => file.code)
    .join('\n')
  const css = files
    .filter((file) => file.type === 'asset' && file.fileName.endsWith('.css'))
    .map((file) => String(file.source))
    .join('\n')
  if (!js || !css || !css.includes('data:font'))
    throw new Error('Self-contained JavaScript/CSS/font bundle is required')
  const html =
    '<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>DonWorry P6 · 지급 정책 화면 시연</title><style>' +
    css.replaceAll('</style', '<\\/style') +
    '</style></head><body><div id="root"></div><script type="module">' +
    js.replaceAll('</script', '<\\/script') +
    '</script></body></html>'
  await fs.mkdir(path.dirname(output), { recursive: true })
  await fs.writeFile(output, html)
  console.log(`P6 preview: ${output} (${Buffer.byteLength(html)} bytes)`)
} finally {
  const resolved = path.resolve(temporary)
  if (
    path.dirname(resolved) !== frontend ||
    !path.basename(resolved).startsWith('.p6-export.local.')
  )
    throw new Error('Unexpected temporary directory')
  await fs.rm(resolved, { recursive: true, force: true })
}
