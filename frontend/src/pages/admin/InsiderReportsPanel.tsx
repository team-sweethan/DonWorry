import { Alert, Button, Descriptions, Segmented, Space, Table, Tag, Typography } from 'antd'
import { ListEmpty } from '../../components/common/ListEmpty'
import { useCallback, useEffect, useState } from 'react'
import type { InsiderAlertSeverity } from '../../api/insiderAlerts'
import {
  getInsiderReports,
  type InsiderReport,
  type InsiderReportAccessType,
  type InsiderReportBaseline,
  type InsiderReportBaselineRow,
  type InsiderReportBody,
  type InsiderReportLoginAttack,
} from '../../api/insiderCases'
import { displayRuleCode, displayRuleText, isExportBlockRule } from '../../utils/ruleDisplayCode'
import { dateTime } from '../security/case/caseLabels'
import { errorText } from './caseAssignmentForm'
import { RuleCell } from './InsiderParts'

const severityLabel: Record<InsiderAlertSeverity, string> = {
  CAUTION: '주의',
  HIGH: '높음',
  CRITICAL: '위험',
}
const oneLine = {
  onHeaderCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
  onCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
}

function period(from: string | null, to: string | null) {
  if (!from || !to) return '-'
  return from === to ? dateTime(from) : `${dateTime(from)} ~ ${dateTime(to)}`
}

function Sentences({ title, lines }: { title: string; lines: string[] }) {
  return (
    <div>
      <Typography.Text strong>{title}</Typography.Text>
      <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
        {lines.map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>
    </div>
  )
}

/** 평소(30일) 대비 (#749) — 평소 하루 평균 · 가장 많은 날과 이번을 나란히. 해석 문장은 없다 */
function Baseline({ baseline }: { baseline: InsiderReportBaseline }) {
  const usual = (value: number | null, unit: string) => (value === null ? '-' : `${value}${unit}`)
  return (
    <div>
      <Typography.Text strong>평소({baseline.days}일) 대비</Typography.Text>
      <Table<InsiderReportBaselineRow>
        rowKey="key"
        size="small"
        pagination={false}
        dataSource={baseline.rows}
        columns={[
          { ...oneLine, title: '항목', dataIndex: 'label' },
          {
            ...oneLine,
            title: '평소 하루 평균',
            align: 'right',
            render: (_, row) => usual(row.dailyAverage, row.unit),
          },
          {
            ...oneLine,
            title: '가장 많았던 날',
            align: 'right',
            render: (_, row) => usual(row.maxDay, row.unit),
          },
          {
            ...oneLine,
            title: '이번 (경보 근거)',
            align: 'right',
            render: (_, row) => (
              <Typography.Text strong>{`${row.current}${row.unit}`}</Typography.Text>
            ),
          },
        ]}
      />
      <Typography.Text type="secondary">
        {baseline.hasRecords
          ? `평소: ${period(baseline.from, baseline.to)} · 기록 있는 날 ${baseline.daysWithRecords}일 / ${baseline.days}일`
          : `비교할 평소 기록 없음 — ${period(baseline.from, baseline.to)} 동안 접근 기록이 없습니다(새 계정 등).`}
      </Typography.Text>
    </div>
  )
}

/** SEC-INS-10 탈취 의심 로그인 (#770) — 로그인 공격 중 처음 보는 브라우저로 들어온 로그인과 그 앞뒤 */
function LoginAttackFacts({ login }: { login: InsiderReportLoginAttack }) {
  return (
    <div>
      <Typography.Text strong>탈취 의심 로그인</Typography.Text>
      <Descriptions size="small" column={1} bordered styles={{ label: { whiteSpace: 'nowrap' } }}>
        <Descriptions.Item label="로그인">
          {dateTime(login.loginAt)} · {login.device}
        </Descriptions.Item>
        <Descriptions.Item label="평소 브라우저 (30일)">
          {login.usualDevices.length > 0
            ? login.usualDevices.join(', ')
            : '지난 30일 성공 로그인 기록 없음'}
        </Descriptions.Item>
        <Descriptions.Item label="로그인 전 1시간 실패">
          {login.failuresBeforeLogin === 0
            ? '0회 — 한 번에 맞힘'
            : `${login.failuresBeforeLogin}회`}
        </Descriptions.Item>
        <Descriptions.Item label="로그인 뒤 접근">
          {login.accessAfterLogin}건
          {login.firstExportAttemptSeconds !== null &&
            ` · ${login.firstExportAttemptSeconds}초 뒤 거래 내역 내보내기 시도`}
        </Descriptions.Item>
      </Descriptions>
    </div>
  )
}

/** 한 판의 본문 — 사실(코드가 셈) · 고정 설명 · 한계. 추천 문장은 없다 */
function ReportBody({ body }: { body: InsiderReportBody }) {
  const { facts, narrative } = body
  const { access } = facts
  // 로그인으로만 걸린 사건(#770) — 경보 근거 접근 기록이 하나도 없다
  const loginOnly = Boolean(facts.loginAttack) && access.total === 0
  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Descriptions size="small" column={2} bordered styles={{ label: { whiteSpace: 'nowrap' } }}>
        <Descriptions.Item label="조사 대상">
          {facts.staffName ?? `ID ${facts.staffId}`}
        </Descriptions.Item>
        <Descriptions.Item label="그때 계정">
          {facts.accountLocked ? <Tag color="volcano">보안 정지 중</Tag> : <Tag>정지 아님</Tag>}
        </Descriptions.Item>
        <Descriptions.Item label="근거 접근">{access.total}건</Descriptions.Item>
        <Descriptions.Item label="기간">
          {period(access.periodFrom, access.periodTo)}
        </Descriptions.Item>
        <Descriptions.Item label="서로 다른 고객">
          {access.distinctCustomers}명
          {access.unresolvedCustomerRecords > 0 &&
            ` (고객을 확인하지 못한 기록 ${access.unresolvedCustomerRecords}건 별도)`}
        </Descriptions.Item>
        <Descriptions.Item label="가맹점 · 사건">
          {access.distinctMerchants}곳 · {access.distinctCases}건
        </Descriptions.Item>
      </Descriptions>

      <div>
        <Typography.Text strong>걸린 규칙 (순서대로)</Typography.Text>
        <Table
          rowKey="alertId"
          size="small"
          pagination={false}
          dataSource={facts.alerts}
          columns={[
            { ...oneLine, title: '시각', dataIndex: 'raisedAt', render: dateTime },
            {
              title: '규칙',
              onHeaderCell: oneLine.onHeaderCell,
              render: (_, row) => (
                <RuleCell code={displayRuleCode(row.ruleCode)} label={row.ruleLabel} />
              ),
            },
            {
              ...oneLine,
              title: '등급',
              dataIndex: 'severity',
              render: (value: InsiderAlertSeverity) => severityLabel[value],
            },
            {
              title: '내용',
              dataIndex: 'summary',
              onCell: () => ({ style: { minWidth: 220 } }),
              render: displayRuleText,
            },
          ]}
        />
      </div>

      {/* 탈취 의심(#770)은 관리자가 가장 먼저 볼 사실이라 걸린 규칙 바로 아래 */}
      {facts.loginAttack && <LoginAttackFacts login={facts.loginAttack} />}

      {loginOnly ? (
        <Typography.Text type="secondary">
          이 사건은 로그인으로 걸려 경보 근거가 된 접근 기록이 없습니다. 위 [탈취 의심 로그인]을
          보세요.
        </Typography.Text>
      ) : (
        <div>
          <Typography.Text strong>접근 종류별</Typography.Text>
          <Table<InsiderReportAccessType>
            rowKey="type"
            size="small"
            pagination={false}
            dataSource={access.byType}
            locale={{ emptyText: <ListEmpty title="경보 근거로 묶인 접근 기록이 없습니다." /> }}
            columns={[
              { ...oneLine, title: '종류', dataIndex: 'label' },
              { ...oneLine, title: '허용', dataIndex: 'allowed', align: 'right' },
              { ...oneLine, title: '거부', dataIndex: 'denied', align: 'right' },
              {
                ...oneLine,
                title: '행 수',
                dataIndex: 'items',
                align: 'right',
                render: (value: number) => (value > 0 ? value : '-'),
              },
              { ...oneLine, title: '사건', dataIndex: 'cases', align: 'right' },
            ]}
          />
          {/* 차단 경보의 근거는 원인 경보의 기록만이라, 막힌 내보내기 시도는 이 표의 거부에 들어가지 않는다 (#675) */}
          {facts.alerts.some((alert) => isExportBlockRule(alert.ruleCode)) && (
            <Typography.Text type="secondary">
              막힌 내보내기 시도는 위 [걸린 규칙]의 SEC-INS-05 줄에 있고, 이 표의 숫자에는 세지
              않습니다.
            </Typography.Text>
          )}
        </div>
      )}

      {/* #749 전에 만든 판에는 평소 대비가 없다 — 판은 고치지 않으므로 그대로 둔다.
          근거 접근 기록이 없는 탈취 의심 사건은 비교할 "이번" 값이 없어 숨긴다 (#770) */}
      {facts.baseline && !loginOnly && <Baseline baseline={facts.baseline} />}

      <div>
        <Space size="small" style={{ marginBottom: 4 }}>
          <Typography.Text strong>설명</Typography.Text>
          <Tag>{narrative.sourceLabel}</Tag>
        </Space>
        <Space direction="vertical" size="small" style={{ width: '100%' }}>
          <Sentences title="정상 업무로 설명될 수 있는가" lines={narrative.normalExplanation} />
          <Sentences title="정지를 풀면" lines={narrative.ifReleased} />
          <Sentences title="정지를 유지하면" lines={narrative.ifKept} />
        </Space>
      </div>

      <Typography.Text type="secondary">
        {body.limits.map((line) => (
          <div key={line}>· {line}</div>
        ))}
      </Typography.Text>
    </Space>
  )
}

/**
 * 내부자 사건 해제 검토 보고서 (#675) — 판별로 고른다(기본은 최신 판).
 * 사건이 열리고 몇 초 뒤에 첫 판이 생기므로 없으면 [새로고침]으로 다시 읽는다.
 * 기록을 읽지 못하면 판을 남기지 않고 몇 분 뒤 다시 만든다(#702). 경보 없이 열린 사건(#698)은 판이 없다.
 */
export function InsiderReportsPanel({ insiderCaseId }: { insiderCaseId: number }) {
  const [reports, setReports] = useState<InsiderReport[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)

  // 읽기 중 표시는 [새로고침]에서만 켠다 — 첫 읽기는 effect 안이라 state를 바로 바꾸지 않는다
  const load = useCallback(() => {
    getInsiderReports(insiderCaseId)
      .then((next) => {
        setReports(next)
        setError(null)
        setSelected(next.length > 0 ? next[next.length - 1].reportVersion : null)
      })
      .catch((e) =>
        setError(errorText(e, '해제 검토 보고서를 불러오지 못했습니다. 잠시 후 다시 시도하세요.')),
      )
      .finally(() => setLoading(false))
  }, [insiderCaseId])
  useEffect(() => {
    load()
  }, [load])
  const refresh = () => {
    setLoading(true)
    load()
  }

  const report = reports?.find((row) => row.reportVersion === selected) ?? null

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="정지를 풀지 판단할 때 볼 사실입니다. 숫자는 코드가 셌고 설명은 정해진 문장입니다(AI 미사용). 보고서는 추천하지 않습니다 — 판정은 관리자가 합니다."
      />
      {error && <Alert type="error" showIcon message={error} />}
      {reports && reports.length === 0 && (
        <ListEmpty
          title="아직 보고서가 없습니다."
          description="보통 사건이 열리고 몇 초 뒤에 만들어지고, 기록을 읽지 못했으면 몇 분 뒤 다시 만듭니다. 경보 없이 열린 사건(정지만 남아 있던 직원에게 서버가 연 사건)은 보고서가 없으니 사건 정보 탭의 경보와 [내부자 경보] 화면으로 판단하세요."
        />
      )}
      <Space wrap>
        {reports && reports.length > 0 && (
          <Segmented<number>
            value={selected ?? undefined}
            onChange={setSelected}
            options={reports.map((row) => ({
              value: row.reportVersion,
              label: `${row.reportVersion}판 · ${row.triggerLabel}`,
            }))}
          />
        )}
        <Button size="small" loading={loading} onClick={refresh}>
          새로고침
        </Button>
      </Space>
      {report && (
        <>
          <Typography.Text type="secondary">
            {report.reportVersion}판 · {report.triggerLabel} · {dateTime(report.createdAt)} 기준
          </Typography.Text>
          {report.status === 'FAILED' || !report.body ? (
            <Alert
              type="warning"
              showIcon
              message="이 판은 만들지 못했습니다(기록 읽기 실패). 사건 정보 탭의 경보와 [내부자 경보] 화면의 근거로 판단하세요. 사건 · 정지 상태는 바뀌지 않았습니다."
            />
          ) : (
            <ReportBody body={report.body} />
          )}
        </>
      )}
    </Space>
  )
}
