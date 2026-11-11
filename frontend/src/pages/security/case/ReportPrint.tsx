import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { flushSync } from 'react-dom'
import { recordReportPrint } from '../../../api/cases'
import { useAuth } from '../../../auth/useAuth'
import { dateTime } from './caseLabels'
import './reportPrint.css'

// AI 보고서 인쇄 · PDF 저장 (#623) — 브라우저 인쇄 창을 쓴다 (라이브러리 · 서버 PDF 없음)
// 버튼이든 Ctrl+P든 인쇄 창이 열리기 직전(beforeprint)에 출력 기록을 남긴다
// 화면은 인쇄 창에서 인쇄 · 저장 · 취소 중 무엇을 했는지 알 수 없고, 인쇄를 막지도 못한다 → 기록이 실패해도 인쇄는 그대로
// 성공한 보고서를 보여 줄 때만 그린다
export function ReportPrintMarks({
  caseId,
  reportId,
  heading,
  onBeforePrint,
  onAfterPrint,
}: {
  caseId: string
  reportId: string
  // 첫 쪽 머리말 — Case · 보고서 버전 · 증거 버전
  heading: string
  // 인쇄 창이 열리기 직전 — 보고서 탭으로 바꾼다 (조사 과정은 찍지 않는다)
  onBeforePrint: () => void
  onAfterPrint: () => void
}) {
  const { user } = useAuth()
  const [printedAt, setPrintedAt] = useState<string | null>(null)
  // 부모가 새 함수를 넘겨도 인쇄 이벤트를 다시 달지 않는다
  const handlers = useRef({ onBeforePrint, onAfterPrint })
  useEffect(() => {
    handlers.current = { onBeforePrint, onAfterPrint }
  }, [onBeforePrint, onAfterPrint])

  useEffect(() => {
    const before = () => {
      // 인쇄 미리보기가 그려지기 전에 출력 시각 · 보고서 탭을 화면에 반영한다
      flushSync(() => {
        setPrintedAt(dateTime(new Date().toISOString()))
        handlers.current.onBeforePrint()
      })
      recordReportPrint(caseId, reportId).catch(() => undefined)
    }
    const after = () => handlers.current.onAfterPrint()
    window.addEventListener('beforeprint', before)
    window.addEventListener('afterprint', after)
    return () => {
      window.removeEventListener('beforeprint', before)
      window.removeEventListener('afterprint', after)
    }
  }, [caseId, reportId])

  const printer = user ? `${user.displayName}(${user.loginId})` : '확인 못 함'
  const footer = `출력 ${printer} · ${printedAt ?? ''} · 내부 전용`
  return (
    <>
      <div className="dw-print-only" style={headingStyle}>
        <div style={{ fontSize: 18, fontWeight: 700 }}>AI 조사 보고서 · {heading}</div>
        <div style={{ fontSize: 12, marginTop: 4 }}>
          내부 전용 (사장님 · 구매자 비공개) · 판정이 아닌 참고 자료입니다. 판정은 보안 직원이
          합니다.
        </div>
        <div style={{ fontSize: 12, marginTop: 2, color: '#475569' }}>
          출력 {printer} · {printedAt}
        </div>
      </div>
      {/* 모든 쪽 아래 — 종이가 밖으로 나가도 누가 언제 뽑았는지 보이게 (크롬 쪽 여백 칸) */}
      <style>{`@media print { @page {
  @bottom-left { content: ${cssString(footer)}; font-size: 8pt; color: #475569; }
  @bottom-right { content: counter(page) " / " counter(pages); font-size: 8pt; color: #475569; }
} }`}</style>
    </>
  )
}

// CSS content 문자열 — 이름에 따옴표 · 역슬래시 · 줄바꿈이 있어도 규칙이 깨지지 않게
function cssString(text: string): string {
  return `"${text
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/[\r\n]+/g, ' ')}"`
}

const headingStyle: CSSProperties = {
  borderBottom: '2px solid #0F172A',
  paddingBottom: 8,
  marginBottom: 12,
  color: '#0F172A',
}
