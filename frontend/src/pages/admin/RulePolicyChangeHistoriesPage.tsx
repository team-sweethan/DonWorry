import { Alert, Card, Descriptions, Input, Modal, Pagination, Table, Tag } from 'antd'
import { useEffect, useState } from 'react'
import {
  getFdsRulePolicyChangeHistories,
  type FdsRulePolicyChangeHistory,
  type FdsRulePolicyChangeHistoryPage,
} from '../../api/fds'
import { PageHeader } from '../../components/common/PageHeader'

function formatDate(value: string) {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'short' }).format(
    new Date(value),
  )
}

const ruleNames: Record<string, string> = {
  R01: '고액 결제',
  R02: '신규 단말기',
  R03: '신규 카드',
  R04: '단시간 반복 결제',
  R05: '고위험 국가 IP',
  R06: '카드 테스팅',
  R07: '차단 목록',
  R08: '거절 후 재시도',
  SEC_OPERATOR_OUT_OF_ROLE_REPEAT: '역할 범위 밖 API 반복 호출',
  SEC_OPERATOR_PERMISSION_MISSING_REPEAT: '권한 부족 API 반복 호출',
  // 내부자 규칙 (#685) — 백엔드 InsiderRule 이름과 같게. INS_05는 끌 수 없어 이력에 나오지 않는다
  INS_01: '담당 밖 접근 반복',
  INS_03: '전화번호 열람 집중',
  INS_06: '경보 겹침',
  INS_07: '처음 보는 브라우저 로그인 직후 전화번호 열람',
  INS_08: '업무 대비 거래 목록 · 내보내기 대량 조회',
  INS_09: '담당 무관 고객 반복 검색',
}

function ruleName(ruleCode: string) {
  return ruleNames[ruleCode] ?? ruleCode
}

function searchValue(value: string) {
  const normalized = value.trim()
  const matchingCode = Object.entries(ruleNames).find(([, name]) => name === normalized)?.[0]
  return matchingCode ?? normalized
}

function RuleTypeTag({ ruleCode }: { ruleCode: string }) {
  const accountRule = ruleCode.startsWith('SEC_') || ruleCode.startsWith('INS_')
  return <Tag color={accountRule ? 'purple' : 'blue'}>{accountRule ? '계정 룰' : '결제 룰'}</Tag>
}

function sourceLabel(source: FdsRulePolicyChangeHistory['source']) {
  return {
    ADMIN_DIRECT: '관리자 직접 변경',
    SECURITY_STAFF_DIRECT: '보안 직원 직접 켬',
    DISABLE_REQUEST_APPROVED: '끄기 요청 승인',
  }[source]
}

export function RulePolicyChangeHistoriesPage() {
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [result, setResult] = useState<FdsRulePolicyChangeHistoryPage | null>(null)
  const [error, setError] = useState(false)
  const [selected, setSelected] = useState<FdsRulePolicyChangeHistory | null>(null)

  useEffect(() => {
    getFdsRulePolicyChangeHistories(page, search)
      .then(setResult)
      .catch(() => setError(true))
  }, [page, search])

  return (
    <>
      <PageHeader
        title="룰 변경 이력"
        description="결제·계정 룰의 실제 켜기·끄기 변경 기록입니다."
      />
      {error && (
        <Alert
          type="error"
          showIcon
          message="룰 변경 이력을 불러오지 못했습니다."
          className="dw-page-alert"
        />
      )}
      <Card>
        <Input.Search
          allowClear
          placeholder="룰명, 요청자, 변경자 검색"
          style={{ maxWidth: 320, marginBottom: 16 }}
          onSearch={(value) => {
            setSearch(searchValue(value))
            setPage(0)
          }}
        />
        <Table
          rowKey="historyId"
          size="small"
          pagination={false}
          dataSource={result?.content ?? []}
          columns={[
            { title: '룰', dataIndex: 'ruleCode', render: ruleName },
            {
              title: '구분',
              dataIndex: 'ruleCode',
              render: (value: string) => <RuleTypeTag ruleCode={value} />,
            },
            {
              title: '변경',
              dataIndex: 'action',
              render: (value: 'ENABLED' | 'DISABLED') => (
                <Tag color={value === 'ENABLED' ? 'green' : 'red'}>
                  {value === 'ENABLED' ? '켬' : '끔'}
                </Tag>
              ),
            },
            {
              title: '변경 정보',
              dataIndex: 'source',
              render: (
                value: FdsRulePolicyChangeHistory['source'],
                history: FdsRulePolicyChangeHistory,
              ) => `${sourceLabel(value)} · ${history.changedByName}`,
            },
            {
              title: '요청 정보',
              dataIndex: 'requestedByName',
              render: (value: string | null) => value ?? '—',
            },
            { title: '변경 시각', dataIndex: 'changedAt', render: formatDate },
          ]}
          onRow={(history) =>
            history.requestReason
              ? { onClick: () => setSelected(history), style: { cursor: 'pointer' } }
              : {}
          }
        />
        <div className="dw-pagination-footer">
          <Pagination
            current={page + 1}
            pageSize={10}
            total={Math.max(result?.totalElements ?? 0, 1)}
            showSizeChanger={false}
            hideOnSinglePage={false}
            showTotal={() => `총 ${result?.totalElements ?? 0}건`}
            onChange={(next) => setPage(next - 1)}
          />
        </div>
      </Card>
      <Modal
        title="룰 변경 요청 사유"
        open={selected !== null}
        footer={null}
        onCancel={() => setSelected(null)}
      >
        {selected && (
          <Descriptions column={1} size="small">
            <Descriptions.Item label="룰">{ruleName(selected.ruleCode)}</Descriptions.Item>
            <Descriptions.Item label="구분">
              <RuleTypeTag ruleCode={selected.ruleCode} />
            </Descriptions.Item>
            <Descriptions.Item label="요청자">{selected.requestedByName}</Descriptions.Item>
            <Descriptions.Item label="요청 사유">
              <div style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                {selected.requestReason}
              </div>
            </Descriptions.Item>
            <Descriptions.Item label="변경 정보">
              {sourceLabel(selected.source)} · {selected.changedByName}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </>
  )
}
