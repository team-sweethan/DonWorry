import { defaultPreviewLoan } from './loanForecast'
import { useState } from 'react'
import { useLocation, Link } from 'react-router-dom'
import { Alert, Button, Modal, Tag } from 'antd'
import './loan-products.css'
import { PageHeader } from '../../components/common/PageHeader'
import './finance-page-header.css'

// 팀원 HTML의 가상 상품이다. 은행 조회 결과나 실제 추천 순위로 사용하지 않는다.
const products = [
  {
    bank: '한빛은행 (가상)',
    name: '정산 연계 운전자금 대출',
    limit: 30000000,
    rate: '연 5.4 ~ 7.9%',
    low: 5.4,
    term: '6 ~ 12개월',
    feat: '돈워리 정산 예정 금액을 근거로 한도를 산정합니다.',
    best: true,
  },
  {
    bank: '누리은행 (가상)',
    name: '가맹점 마이너스 통장',
    limit: 20000000,
    rate: '연 6.1 ~ 8.8%',
    low: 6.1,
    term: '1년 (연장 가능)',
    feat: '한도 안에서 쓴 만큼만 이자를 냅니다.',
    best: false,
  },
  {
    bank: '새솔저축은행 (가상)',
    name: '소상공인 매출 기반 단기자금',
    limit: 10000000,
    rate: '연 8.9 ~ 12.5%',
    low: 8.9,
    term: '3 ~ 6개월',
    feat: '최근 3개월 카드 매출 기준 · 빠른 심사',
    best: false,
  },
]
const money = (n: number) => `${n.toLocaleString('ko-KR')}원`
type Snapshot = {
  required: number
  minimumDate?: string
  minimum: number
  totalIn: number
  firstShortage: string | null
  from: string
  to: string
  capturedAt: string
  preview: boolean
}
function isSnapshot(value: unknown): value is Snapshot {
  if (!value || typeof value !== 'object') return false
  const v = value as Record<string, unknown>
  const date = (s: unknown) =>
    typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && Number.isFinite(Date.parse(s))
  return (
    typeof v.required === 'number' &&
    Number.isSafeInteger(v.required) &&
    v.required >= 0 &&
    typeof v.minimum === 'number' &&
    Number.isSafeInteger(v.minimum) &&
    v.required === Math.max(0, -v.minimum) &&
    typeof v.totalIn === 'number' &&
    Number.isSafeInteger(v.totalIn) &&
    v.totalIn >= 0 &&
    date(v.from) &&
    date(v.to) &&
    (v.firstShortage === null || date(v.firstShortage)) &&
    typeof v.capturedAt === 'string' &&
    Number.isFinite(Date.parse(v.capturedAt)) &&
    typeof v.preview === 'boolean'
  )
}
export function LoanProductsPage() {
  const { pathname, state } = useLocation()
  const input: unknown = state
  const [defaultSnapshot] = useState(() => defaultPreviewLoan())
  const fromForecast = isSnapshot(input)
  const snapshot = fromForecast ? input : defaultSnapshot
  const [selected, setSelected] = useState<(typeof products)[number] | null>(null)
  const back = pathname.startsWith('/dev/') ? '/dev/funds' : '/owner/funds'
  return (
    <div className="loan-products">
      <PageHeader
        title="연계 대출 상품"
        description="정산 예정 금액을 바탕으로 상품을 살펴보는 시연 화면입니다. 실제 조회·신청 연동은 준비 중입니다."
        actions={<Link to={back}>← 자금 현황</Link>}
      />
      <Alert
        type="info"
        showIcon
        message="시연용 가상 상품 · 은행 API 미연동"
        description="은행 이름·금리·한도·추천 표시는 팀원 프로토타입의 예시입니다. 실제 상품 조회나 대출 신청·심사는 수행하지 않습니다."
      />
      <div className="loan-evidence-grid">
        <section className="loan-surface">
          <small>필요 자금 (예상)</small>
          <h2 className={snapshot && snapshot.required > 0 ? 'loan-negative' : ''}>
            {snapshot
              ? snapshot.required > 0
                ? `최소 ${money(snapshot.required)} 이상`
                : '부족 없음'
              : '확인 필요'}
          </h2>
          <p>
            {snapshot
              ? snapshot.firstShortage
                ? `${snapshot.firstShortage}부터 부족이 예상됩니다. ${snapshot.minimumDate ?? '선택 기간'}의 최저 예상 잔액은 ${money(snapshot.minimum)}입니다.`
                : '선택 기간의 예상 잔액이 0원 아래로 내려가지 않습니다.'
              : '자금 현황에서 기간을 조회한 뒤 상품 보기 버튼을 눌러 주세요.'}
          </p>
          <small>
            첫 부족일 이전까지 위 금액을 확보하면, 등록된 유입·지출 기준으로 선택 기간의 일 마감
            잔액을 0원 이상 유지할 수 있습니다. 실제 대출 승인 금액은 아닙니다.
          </small>
        </section>
        <section className="loan-surface">
          <small>조회 근거</small>
          <dl>
            <dt>계산 기간</dt>
            <dd>{snapshot ? `${snapshot.from} ~ ${snapshot.to}` : '미확인'}</dd>
            <dt>기간 내 정산 유입</dt>
            <dd>{snapshot ? money(snapshot.totalIn) : '미확인'}</dd>
            <dt>보안 확인 중</dt>
            <dd>P5 연동 전 · 확인 불가</dd>
            <dt>기준 시각</dt>
            <dd>
              {snapshot
                ? new Intl.DateTimeFormat('ko-KR', {
                    timeZone: 'Asia/Seoul',
                    dateStyle: 'short',
                    timeStyle: 'medium',
                  }).format(new Date(snapshot.capturedAt))
                : '미확인'}
            </dd>
            <dt>계산 데이터</dt>
            <dd>
              {snapshot
                ? snapshot.preview
                  ? fromForecast
                    ? '자금 현황의 예시 계산 결과'
                    : '기본 예시 · 오늘부터 7일'
                  : '자금 현황 계산 결과'
                : '미확인'}
            </dd>
          </dl>
          <small>
            {fromForecast
              ? '자금 현황에서 전달된 계산값입니다. 최신 값은 다시 조회해 주세요.'
              : '현재는 기본 예시입니다. 변경한 잔액·지출을 반영하려면 자금 현황에서 상품 보기 버튼으로 이동하세요.'}
          </small>
        </section>
      </div>
      <div className="loan-product-grid">
        {products.map((product) => (
          <article
            key={product.bank}
            className={`loan-product-card ${product.best ? 'loan-featured' : ''}`}
          >
            <div className="loan-bank">
              <span>{product.bank}</span>
              {product.best && <Tag color="blue">정산 연계 추천 · 예시</Tag>}
            </div>
            <h2>{product.name}</h2>
            <dl>
              <dt>한도</dt>
              <dd>
                <b>최대 {money(product.limit)}</b>
              </dd>
              <dt>금리</dt>
              <dd>{product.rate}</dd>
              <dt>기간</dt>
              <dd>{product.term}</dd>
            </dl>
            <p>{product.feat}</p>
            {snapshot && snapshot.required > 0 && (
              <>
                <small>필요 자금 대비 예시 한도</small>
                <div className="loan-coverage">
                  <div
                    style={{
                      width: `${Math.min(100, (product.limit / snapshot.required) * 100)}%`,
                    }}
                  />
                </div>
                <p>
                  필요 자금을 빌리면 월 이자 약{' '}
                  <b>{money(Math.round((snapshot.required * product.low) / 100 / 12))}</b>{' '}
                  <small>(예시 최저 금리 · 단순 이자 계산)</small>
                </p>
              </>
            )}
            <Button onClick={() => setSelected(product)}>상품 설명 보기</Button>
          </article>
        ))}
      </div>
      <Modal
        title={selected?.name}
        open={selected !== null}
        onCancel={() => setSelected(null)}
        footer={<Button onClick={() => setSelected(null)}>닫기</Button>}
      >
        <p>{selected?.bank}</p>
        <p>{selected?.feat}</p>
        <p>
          팀원 HTML에서 가져온 시연용 상품 설명입니다. 실제 상품 조건이나 신청 화면은 아직 연결되지
          않았습니다.
        </p>
      </Modal>
    </div>
  )
}
