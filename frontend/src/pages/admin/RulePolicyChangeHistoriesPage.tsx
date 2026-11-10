import { Alert, Button, Card, Descriptions, Input, Modal, Pagination, Table, Tag } from 'antd'
import { useEffect, useState } from 'react'
import {
  getFdsRulePolicyChangeHistories,
  type FdsRulePolicyChangeHistory,
  type FdsRulePolicyChangeHistoryPage,
} from '../../api/fds'
import { ListEmpty } from '../../components/common/ListEmpty'
import { displayRuleCode } from '../../utils/ruleDisplayCode'
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
  INS_10: '로그인 공격 중 처음 보는 브라우저 로그인',
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
    <div className="dw-rule-management-page">
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
      <Card className="dw-rule-records">
        <div className="dw-rule-records-heading">
          <div>
            <h2>룰 적용 기록</h2>
            <p>변경한 룰과 처리자, 변경 경위를 함께 확인합니다.</p>
          </div>
          <span className="dw-rule-records-count">
            {result
              ? `${result.totalElements.toLocaleString('ko-KR')}건`
              : error
                ? '조회 실패'
                : '불러오는 중'}
          </span>
        </div>
        <div className="dw-rule-records-toolbar">
          <Input.Search
            allowClear
            placeholder="룰명, 요청자, 변경자 검색"
            className="dw-rule-records-search"
            onSearch={(value) => {
              setSearch(searchValue(value))
              setPage(0)
            }}
          />
        </div>
        <Table
          rowKey="historyId"
          pagination={false}
          scroll={{ x: 1000 }}
          loading={result === null && !error}
          locale={{
            emptyText: (
              <ListEmpty
                title={
                  error
                    ? '변경 이력을 확인할 수 없습니다.'
                    : search
                      ? '검색 조건에 맞는 변경 이력이 없습니다.'
                      : '룰 변경 이력이 없습니다.'
                }
              />
            ),
          }}
          dataSource={result?.content ?? []}
          columns={[
            {
              title: '탐지 룰',
              dataIndex: 'ruleCode',
              width: 320,
              render: (value: string) => (
                <div className="dw-rule-record-cell">
                  <strong>{ruleName(value)}</strong>
                  <span>
                    {displayRuleCode(value)} · <RuleTypeTag ruleCode={value} />
                  </span>
                </div>
              ),
            },
            {
              title: '변경',
              width: 90,
              dataIndex: 'action',
              render: (value: 'ENABLED' | 'DISABLED') => (
                <Tag color={value === 'ENABLED' ? 'green' : 'red'}>
                  {value === 'ENABLED' ? '켬' : '끔'}
                </Tag>
              ),
            },
            {
              title: '변경자 · 경위',
              width: 220,
              dataIndex: 'source',
              render: (
                value: FdsRulePolicyChangeHistory['source'],
                history: FdsRulePolicyChangeHistory,
              ) => (
                <div className="dw-rule-record-cell">
                  <strong>{history.changedByName}</strong>
                  <span>{sourceLabel(value)}</span>
                </div>
              ),
            },
            {
              title: '요청자',
              width: 140,
              dataIndex: 'requestedByName',
              render: (value: string | null) => value ?? '—',
            },
            { title: '변경 시각', dataIndex: 'changedAt', width: 170, render: formatDate },
            {
              title: '요청 사유',
              width: 110,
              render: (_, history: FdsRulePolicyChangeHistory) =>
                history.requestReason ? (
                  <Button
                    size="small"
                    onClick={(event) => {
                      event.stopPropagation()
                      setSelected(history)
                    }}
                  >
                    사유 보기
                  </Button>
                ) : (
                  '—'
                ),
            },
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
        className="dw-rule-detail-modal"
        width={640}
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
    </div>
  )
}
