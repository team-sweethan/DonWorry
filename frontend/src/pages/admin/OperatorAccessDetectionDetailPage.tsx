import { Alert, Button, Card, Descriptions, Space, Table, Tag } from 'antd'
import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  getLowRiskOperatorAccessDetection,
  getOperatorAccessDetection,
  type OperatorAccessDetectionDetail,
} from '../../api/fds'
import { getMerchantDirectory } from '../../api/merchants'
import { PageHeader } from '../../components/common/PageHeader'
import { merchantDisplayName } from '../../utils/merchantDisplayName'
import { displayRuleCode } from '../../utils/ruleDisplayCode'

function formatDate(value: string | null) {
  return value
    ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium' }).format(
        new Date(value),
      )
    : '—'
}

function ruleLabel(ruleCode: string) {
  return ruleCode === 'SEC_OPERATOR_OUT_OF_ROLE_REPEAT'
    ? '역할 범위 밖 API 반복 호출'
    : ruleCode === 'SEC_OPERATOR_PERMISSION_MISSING_REPEAT'
      ? '권한 부족 API 반복 호출'
      : ruleCode
}

function deniedTypeLabel(deniedType: string) {
  return deniedType === 'OUT_OF_ROLE'
    ? '역할 범위 밖 접근'
    : deniedType === 'PERMISSION_MISSING'
      ? '필요 권한 없음'
      : deniedType
}

export function OperatorAccessDetectionDetailPage() {
  const { id, eventId } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const [result, setResult] = useState<OperatorAccessDetectionDetail | null>(null)
  const [merchantName, setMerchantName] = useState<string | null>(null)
  const [error, setError] = useState(false)
  const sourceId = Number(eventId ?? id)
  const isLowRisk = eventId !== undefined
  const invalidId = !Number.isSafeInteger(sourceId) || sourceId < 1
  const returnPath =
    (location.state as { operatorAccessDetectionListReturnPath?: string } | null)
      ?.operatorAccessDetectionListReturnPath ?? '/admin/fds/operator-access-detections'

  useEffect(() => {
    if (invalidId) return
    let active = true
    ;(isLowRisk
      ? getLowRiskOperatorAccessDetection(sourceId)
      : getOperatorAccessDetection(sourceId)
    )
      .then((detail) => {
        if (!active) return
        const merchantId = detail.merchantId
        setError(false)
        setMerchantName(null)
        setResult(detail)
        if (merchantId === null) return
        void getMerchantDirectory()
          .then((merchants) => {
            if (active) setMerchantName(merchantDisplayName(merchantId, merchants))
          })
          .catch(() => {
            if (active) setMerchantName(null)
          })
      })
      .catch(() => {
        if (active) setError(true)
      })
    return () => {
      active = false
    }
  }, [sourceId, invalidId, isLowRisk])

  return (
    <>
      <PageHeader
        title={result?.riskLevel === 'LOW' ? '저위험 접근 이상 상세' : '계정 접근 이상 상세'}
        description="반복 접근의 현재 위험도와 판단 근거를 확인합니다."
      />
      <Space style={{ marginBottom: 16 }}>
        <Button onClick={() => navigate(returnPath)}>목록으로</Button>
      </Space>
      {(error || invalidId) && (
        <Alert type="error" showIcon message="탐지 상세를 불러오지 못했습니다." />
      )}
      {result && (
        <>
          <Card title="판정 및 조치" style={{ marginBottom: 16 }}>
            <Descriptions bordered size="small" column={1} labelStyle={{ width: 180 }}>
              <Descriptions.Item label="위험도">
                <Tag color={result.riskLevel === 'HIGH' ? 'red' : 'gold'}>
                  {result.riskLevel === 'HIGH' ? '고위험' : '저위험'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="계정">{result.loginId}</Descriptions.Item>
              <Descriptions.Item label="소속 정보">
                {merchantName ?? result.merchantId ?? '—'}
              </Descriptions.Item>
              <Descriptions.Item label="룰">{`${displayRuleCode(result.ruleCode)} ${ruleLabel(result.ruleCode)}`}</Descriptions.Item>
              <Descriptions.Item label="거절 유형">
                {deniedTypeLabel(result.deniedType)}
              </Descriptions.Item>
              <Descriptions.Item label="판정 기준">
                {result.windowSeconds}초 내 {result.thresholdCount}회
              </Descriptions.Item>
              <Descriptions.Item label="집계 건수">{result.eventCount}회</Descriptions.Item>
              <Descriptions.Item label="처리 결과">
                <Tag color={result.actionStatus === 'SUCCEEDED' ? 'green' : 'default'}>
                  {result.actionStatus === 'SUCCEEDED'
                    ? '계정 정지 완료'
                    : result.actionStatus === 'SKIPPED_REACTIVATED'
                      ? '재활성화 후 조치 제외'
                      : result.actionStatus === 'NONE'
                        ? '조치 대상 아님'
                        : result.actionStatus === 'REQUESTED'
                          ? '정지 처리 중'
                          : '조치 실패'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="조치 시각">
                {result.riskLevel === 'LOW'
                  ? '기준 미도달 — 계정 상태 유지'
                  : formatDate(result.actionCompletedAt)}
              </Descriptions.Item>
              <Descriptions.Item label="집계 시작">
                {formatDate(result.windowStartedAt)}
              </Descriptions.Item>
              <Descriptions.Item label="집계 종료">
                {formatDate(result.windowEndedAt)}
              </Descriptions.Item>
              {result.failureCode && (
                <Descriptions.Item label="실패 코드" span={2}>
                  {result.failureCode}
                </Descriptions.Item>
              )}
            </Descriptions>
          </Card>
          <Card
            title={`${result.riskLevel === 'LOW' ? '확인된 접근 이벤트' : '판정 근거'} (${result.evidences.length}건)`}
          >
            <Table
              size="small"
              rowKey="identityEventId"
              pagination={false}
              dataSource={result.evidences}
              columns={[
                { title: '원본 이벤트 ID', dataIndex: 'identityEventId' },
                { title: '메서드', dataIndex: 'httpMethod' },
                { title: '경로', dataIndex: 'path' },
                { title: '발생 시각', dataIndex: 'occurredAt', render: formatDate },
              ]}
            />
          </Card>
        </>
      )}
    </>
  )
}
