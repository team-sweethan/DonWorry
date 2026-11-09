import { Alert, Card, Input, Pagination, Table, Tabs, Tag } from 'antd'
import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { getOperatorAccessDetections, type OperatorAccessDetectionPage } from '../../api/fds'
import { PageHeader } from '../../components/common/PageHeader'

export function OperatorAccessDetectionsPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const listQuery = new URLSearchParams(location.search)
  const initialPage = Number(listQuery.get('page'))
  const initialRisk = listQuery.get('risk')
  const [page, setPage] = useState(
    Number.isSafeInteger(initialPage) && initialPage >= 0 ? initialPage : 0,
  )
  const [risk, setRisk] = useState<'ALL' | 'HIGH' | 'LOW'>(
    initialRisk === 'ALL' || initialRisk === 'LOW' ? initialRisk : 'HIGH',
  )
  const [search, setSearch] = useState(listQuery.get('search') ?? '')
  const [result, setResult] = useState<OperatorAccessDetectionPage | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    let active = true
    getOperatorAccessDetections(page, risk, search)
      .then((value) => {
        if (active) {
          setResult(value)
          setError(false)
        }
      })
      .catch(() => {
        if (active) setError(true)
      })
    return () => {
      active = false
    }
  }, [page, risk, search])
  const count = (value: 'ALL' | 'HIGH' | 'LOW') =>
    value === 'ALL'
      ? (result?.highRiskCount ?? 0) + (result?.lowRiskCount ?? 0)
      : value === 'HIGH'
        ? (result?.highRiskCount ?? 0)
        : (result?.lowRiskCount ?? 0)
  const listReturnPath = `${location.pathname}?${new URLSearchParams({
    page: String(page),
    risk,
    search,
  }).toString()}`
  return (
    <>
      <PageHeader
        title="계정 접근 이상 탐지"
        description="계정·룰별 반복 접근 이상을 위험도와 조치 상태로 확인합니다."
      />
      {error && (
        <Alert
          type="error"
          message="탐지 목록을 불러오지 못했습니다."
          style={{ marginBottom: 16 }}
        />
      )}
      <Card>
        <Tabs
          activeKey={risk}
          onChange={(key) => {
            setRisk(key as 'ALL' | 'HIGH' | 'LOW')
            setPage(0)
          }}
          items={(['HIGH', 'LOW', 'ALL'] as const).map((key) => ({
            key,
            label: `${key === 'ALL' ? '전체' : key === 'HIGH' ? '고위험' : '저위험'} ${count(key)}건`,
          }))}
        />
        <Input.Search
          placeholder="계정, 고객사, 룰명 검색"
          allowClear
          style={{ maxWidth: 320, marginBottom: 16 }}
          onSearch={(value) => {
            setSearch(value)
            setPage(0)
          }}
        />
        <Table
          rowKey={(value) => value.detectionId ?? `low-${value.sourceEventId}`}
          pagination={false}
          dataSource={result?.content ?? []}
          columns={[
            { title: '계정', dataIndex: 'loginId' },
            { title: '소속', dataIndex: 'affiliation' },
            { title: '룰', dataIndex: 'ruleName' },
            {
              title: '발생 횟수',
              dataIndex: 'eventCount',
              render: (value: number) => `${value}회`,
            },
            {
              title: '위험도',
              dataIndex: 'riskLevel',
              render: (value: string) => (
                <Tag color={value === 'HIGH' ? 'red' : 'gold'}>
                  {value === 'HIGH' ? '고위험' : '저위험'}
                </Tag>
              ),
            },
            {
              title: '조치 상태',
              dataIndex: 'actionStatus',
              render: (value: string) => (
                <Tag
                  color={
                    value === 'SUCCEEDED'
                      ? 'green'
                      : value === 'SKIPPED_REACTIVATED'
                        ? 'default'
                        : value === 'NONE'
                          ? 'default'
                          : value === 'REQUESTED'
                            ? 'blue'
                            : 'red'
                  }
                >
                  {{
                    SUCCEEDED: '조치 완료',
                    SKIPPED_REACTIVATED: '재활성화 후 조치 제외',
                    NONE: '조치 없음',
                    REQUESTED: '처리 중',
                  }[value] ?? '조치 실패'}
                </Tag>
              ),
            },
            {
              title: '최근 발생',
              dataIndex: 'occurredAt',
              render: (value: string) =>
                new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'short' }).format(
                  new Date(value),
                ),
            },
          ]}
          onRow={(value) => ({
            onClick: () =>
              navigate(
                value.detectionId
                  ? `/admin/fds/operator-access-detections/${value.detectionId}`
                  : `/admin/fds/operator-access-detections/low/${value.sourceEventId}`,
                { state: { operatorAccessDetectionListReturnPath: listReturnPath } },
              ),
            style: { cursor: 'pointer' },
          })}
        />
        <div className="dw-pagination-footer">
          <Pagination
            current={page + 1}
            total={Math.max(result?.totalElements ?? 0, 1)}
            pageSize={10}
            showSizeChanger={false}
            hideOnSinglePage={false}
            showTotal={() => `총 ${result?.totalElements ?? 0}건`}
            onChange={(value) => setPage(value - 1)}
          />
        </div>
        <Alert
          type="info"
          showIcon
          message="표시 기준 · 고위험은 이력 전체, 저위험은 계정·룰별 최신 신호만 표시합니다. 고위험 근거는 저위험에서 제외됩니다."
          style={{ marginTop: 12 }}
        />
      </Card>
    </>
  )
}
