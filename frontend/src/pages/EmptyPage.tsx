import { PageHeader } from '../components/common/PageHeader'

/** 담당 기능 페이지를 연결할 본문 영역을 표시한다. */
export function EmptyPage({ title }: { title: string }) {
  return (
    <>
      <PageHeader title={title} />
      <section className="dw-implementation-area" aria-label={`${title} 구현 영역`}>
        <div>
          <h2>담당자 구현 영역</h2>
          <p>이 영역에 {title} 화면이 들어갑니다.</p>
        </div>
      </section>
    </>
  )
}
