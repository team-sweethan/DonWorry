import { useState } from 'react'
import { useLocation, Link } from 'react-router-dom'
import { Alert, Button, Empty, Input, Modal, Pagination, Select, Tag } from 'antd'
import './loan-products.css'
import { PageHeader } from '../../components/common/PageHeader'
import './finance-page-header.css'

import groups from './loan-products.json'

type Product = (typeof groups.general)[number]
type Scope = 'general' | 'special' | 'all'
const pageSize = 9
const productRegions = (product: Product) =>
  product.rsdAreaPamtEqltIstm.split(',').map((region) => region.trim())
const regions = [...new Set([...groups.general, ...groups.special].flatMap(productRegions))]
  .filter((region) => region && region !== '-' && region !== '전국')
  .sort((a, b) => a.localeCompare(b, 'ko'))

export function LoanProductsPage() {
  const { pathname, search } = useLocation()
  const [selected, setSelected] = useState<Product | null>(null)
  const [region, setRegion] = useState('')
  const [scope, setScope] = useState<Scope>('general')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(1)
  const products = (
    scope === 'all' ? [...groups.general, ...groups.special] : groups[scope]
  ).filter((product) => {
    const areas = productRegions(product)
    return (
      (!region || areas.includes(region) || areas.includes('전국')) &&
      [product.finprdnm, product.ofrinstnm]
        .join(' ')
        .toLowerCase()
        .includes(query.trim().toLowerCase())
    )
  })
  const site = selected?.rltsite?.trim()
  const back = pathname.startsWith('/dev/')
    ? `/dev/funds${new URLSearchParams(search).get('sample') === '1' ? '?sample=1' : ''}`
    : '/owner/funds'
  return (
    <div className="loan-products">
      <PageHeader
        title="운영자금 상품"
        description="소상공인·중소기업 운영자금 상품을 찾아보세요."
        actions={<Link to={back}>← 자금 현황</Link>}
      />
      <Alert
        type="info"
        showIcon
        message="서민금융진흥원 상품 자료"
        className="finance-intro-alert"
        description="저장된 상품 자료입니다. 최신 지원 조건과 접수 여부는 취급기관에서 확인해 주세요."
      />
      <section className="loan-surface loan-catalog">
        <h2>사업 운영자금 상품</h2>
        <p>지역을 선택하면 해당 지역과 전국 상품을 함께 볼 수 있습니다.</p>
        <div className="loan-filters">
          <Input
            aria-label="상품명 또는 기관 검색"
            placeholder="상품명 또는 기관 검색"
            allowClear
            value={query}
            onChange={(event) => {
              setQuery(event.target.value)
              setPage(1)
            }}
          />
          <Select
            aria-label="지역"
            value={region}
            options={[
              { value: '', label: '전체 지역' },
              ...regions.map((value) => ({ value, label: value })),
            ]}
            onChange={(value) => {
              setRegion(value)
              setPage(1)
            }}
          />
          <Select
            aria-label="지원 대상 범위"
            value={scope}
            options={[
              { value: 'general', label: '일반 운영자금' },
              { value: 'special', label: '업종·특정 조건 상품' },
              { value: 'all', label: '전체 운영자금' },
            ]}
            onChange={(value: Scope) => {
              setScope(value)
              setPage(1)
            }}
          />
        </div>
        <p aria-live="polite">상품 {products.length}건</p>
        {products.length ? (
          <div className="loan-product-grid">
            {products.slice((page - 1) * pageSize, page * pageSize).map((product, index) => (
              <article key={index} className="loan-product-card">
                <div>
                  <Tag color="gold">{product.rsdAreaPamtEqltIstm}</Tag>
                  <Tag>{product.usge}</Tag>
                </div>
                <h3>{product.finprdnm}</h3>
                <p>
                  {product.ofrinstnm} · {product.trgt}
                </p>
                <Button onClick={() => setSelected(product)}>지원 조건 보기</Button>
              </article>
            ))}
          </div>
        ) : (
          <Empty description="검색 조건에 맞는 상품이 없습니다." />
        )}
        {products.length > pageSize && (
          <Pagination
            current={page}
            total={products.length}
            pageSize={pageSize}
            onChange={setPage}
            showSizeChanger={false}
          />
        )}
      </section>
      <Modal
        title={selected?.finprdnm}
        open={selected !== null}
        onCancel={() => setSelected(null)}
        footer={<Button onClick={() => setSelected(null)}>닫기</Button>}
      >
        {selected && (
          <>
            <p>
              {selected.ofrinstnm} · {selected.rsdAreaPamtEqltIstm}
            </p>
            {[
              ['지원 대상', selected.trgt],
              ['상세 조건', selected.suprtgtdtlcond],
              ['신청 방법', selected.jnmthd],
              ['문의처', selected.cnpl],
            ]
              .filter(([, value]) => value && value.trim() !== '-')
              .map(([label, value]) => (
                <div className="loan-condition" key={label}>
                  <b>{label}</b>
                  <p>{value}</p>
                </div>
              ))}
            {site && /^https?:\/\/[^\s]+$/i.test(site) ? (
              <a href={site} target="_blank" rel="noopener noreferrer">
                기관 홈페이지 보기 ↗
              </a>
            ) : site && site !== '-' ? (
              <p>관련 사이트 안내 · {site}</p>
            ) : null}
          </>
        )}
      </Modal>
    </div>
  )
}
