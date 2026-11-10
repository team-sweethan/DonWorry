import { PauseCircleOutlined, PlayCircleOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Input, Modal, Table, Tag } from 'antd'
import { ListEmpty } from '../../components/common/ListEmpty'
import { ListLoading } from '../../components/common/ListLoading'
import { useCallback, useEffect, useState } from 'react'
import {
  getAiSwitch,
  resumeAi,
  stopAi,
  type AiSwitchAction,
  type AiSwitchHistory,
  type AiSwitchStatus,
} from '../../api/aiSwitch'
import { useAuth } from '../../auth/useAuth'
import { PageHeader } from '../../components/common/PageHeader'
import { dateTime } from '../security/case/caseLabels'
import { errorText } from './caseAssignmentForm'
import './AiSwitchPage.css'

const REASON_MAX = 500
const actionTag: Record<AiSwitchAction, { label: string; color: string }> = {
  STOPPED: { label: '정지', color: 'red' },
  RESUMED: { label: '다시 켬', color: 'green' },
}
const oneLine = {
  onHeaderCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
  onCell: () => ({ style: { whiteSpace: 'nowrap' as const } }),
}

/** 맨 위 상태 한 줄. 설정으로 AI 서버가 꺼진 서버는 스위치와 상관없이 고정 보고서다 */
function statusView(status: AiSwitchStatus) {
  if (!status.switchOn)
    return {
      tone: 'is-stopped',
      title: 'AI 조사 정지 중',
      detail:
        'AI를 부르지 않고 같은 조회 결과와 징후 · 정상 근거로 고정 보고서를 만듭니다. AI 요약과 추가 조사만 빠지고, 판정은 그대로 직원이 합니다.' +
        (status.configEnabled
          ? ''
          : ' 이 서버는 설정에서 AI 서버가 꺼져 있어, 스위치를 다시 켜도 고정 보고서로 만듭니다.'),
    }
  if (!status.configEnabled)
    return {
      tone: 'is-config-off',
      title: '고정 보고서로 만드는 중',
      detail:
        '스위치는 켜져 있지만 이 서버 설정에서 AI 서버가 꺼져 있습니다. 스위치와 상관없이 고정 보고서로 만듭니다.',
    }
  return {
    tone: 'is-on',
    title: 'AI 조사 사용 중',
    detail:
      '보고서에 AI 요약과 추가 조사가 들어갑니다. 요약에 조회 결과에 없는 내용이 보이면 바로 정지하세요.',
  }
}

/**
 * [AI 조사 긴급정지] (#626, ADR-0083) — 관리자 · 보안 직원이 같은 화면을 쓴다.
 * 끄기는 둘 다, 다시 켜기는 관리자만 (가이드라인 "중단 · 재개승인"). 꺼도 보고서는 고정 보고서로 계속 나오고 판정은 직원이 한다.
 * 버튼을 숨기는 것은 편의일 뿐이고 실제 권한은 서버(SecurityConfig · 서비스)가 막는다.
 */
export function AiSwitchPage() {
  const { message } = App.useApp()
  const { user } = useAuth()
  const canResume = user?.role === 'ADMIN'
  const [status, setStatus] = useState<AiSwitchStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [target, setTarget] = useState<'stop' | 'resume' | null>(null)
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)

  const reload = useCallback(() => {
    getAiSwitch()
      .then((next) => {
        setStatus(next)
        setError(null)
      })
      .catch(() => setError('AI 조사 상태를 불러오지 못했습니다. 잠시 후 다시 시도하세요.'))
  }, [])
  useEffect(() => {
    reload()
  }, [reload])

  const open = (next: 'stop' | 'resume') => {
    setReason('')
    setTarget(next)
  }
  const submit = async () => {
    if (!target) return
    setSaving(true)
    try {
      const next = target === 'stop' ? await stopAi(reason) : await resumeAi(reason)
      setStatus(next)
      message.success(
        target === 'stop'
          ? 'AI 조사를 정지했습니다. 진행 중이던 보고서는 고정 보고서로 마무리했습니다.'
          : 'AI 조사를 다시 켰습니다.',
      )
      setTarget(null)
    } catch (e) {
      message.error(errorText(e, '바꾸지 못했습니다. 잠시 후 다시 시도하세요.'))
      reload()
    } finally {
      setSaving(false)
    }
  }

  const loading = status === null && error === null
  const view = status ? statusView(status) : null
  const last = status?.histories[0]
  return (
    <>
      <PageHeader
        title="AI 조사 긴급정지"
        description="AI 보고서에 이상이 보이면 바로 정지하세요. 정지 중에도 사건 보고서는 고정 보고서로 계속 나옵니다. 다시 켜기는 관리자만 할 수 있습니다."
      />
      {error && <Alert type="error" showIcon message={error} className="dw-page-alert" />}
      <Card loading={loading} className={`ai-switch-status ${view?.tone ?? ''}`}>
        {status && view && (
          <>
            <div className="ai-switch-head">
              <div>
                <h2>
                  <span className="ai-switch-mark" aria-hidden="true" />
                  {view.title}
                </h2>
                <p>{view.detail}</p>
              </div>
              {status.switchOn ? (
                <Button
                  danger
                  type="primary"
                  size="large"
                  icon={<PauseCircleOutlined />}
                  onClick={() => open('stop')}
                >
                  AI 조사 정지
                </Button>
              ) : canResume ? (
                <Button
                  type="primary"
                  size="large"
                  icon={<PlayCircleOutlined />}
                  onClick={() => open('resume')}
                >
                  AI 조사 다시 켜기
                </Button>
              ) : (
                <span className="ai-switch-note">
                  다시 켜기는 관리자가 원인을 확인한 뒤 합니다.
                </span>
              )}
            </div>
            <dl className="ai-switch-facts">
              <div>
                <dt>지금 보고서</dt>
                <dd>
                  {status.aiActive ? <Tag color="purple">AI 조사</Tag> : <Tag>고정 보고서</Tag>}
                </dd>
              </div>
              <div>
                <dt>긴급정지 스위치</dt>
                <dd>
                  {status.switchOn ? <Tag color="green">켜짐</Tag> : <Tag color="red">정지 중</Tag>}
                </dd>
              </div>
              <div>
                <dt>설정 (AI 서버 연결)</dt>
                <dd>
                  {status.configEnabled ? (
                    <Tag color="green">켜짐</Tag>
                  ) : (
                    <Tag color="default">꺼짐</Tag>
                  )}
                </dd>
              </div>
              <div>
                <dt>마지막 변경</dt>
                <dd>
                  {last ? (
                    <>
                      {dateTime(last.changedAt)} <small>· {last.changedByName}</small>
                    </>
                  ) : (
                    <small>바꾼 적 없음</small>
                  )}
                </dd>
              </div>
            </dl>
          </>
        )}
      </Card>
      <Card className="dw-table-card">
        <div className="dw-table-toolbar">
          <div>
            <h3>최근 변경 기록</h3>
            <p>최신순 20건 · 정지 · 다시 켜기마다 사유가 남습니다.</p>
          </div>
        </div>
        <Table<AiSwitchHistory>
          rowKey="historyId"
          size="small"
          pagination={false}
          scroll={{ x: 'max-content' }}
          dataSource={status?.histories ?? []}
          locale={{
            emptyText: loading ? (
              <ListLoading label="변경 기록을 불러오는 중" />
            ) : (
              <ListEmpty title="아직 바꾼 기록이 없습니다 (처음부터 켜짐)." />
            ),
          }}
          columns={[
            { ...oneLine, title: '시각', dataIndex: 'changedAt', render: dateTime },
            {
              ...oneLine,
              title: '변경',
              dataIndex: 'action',
              render: (value: AiSwitchAction) => (
                <Tag color={actionTag[value].color}>{actionTag[value].label}</Tag>
              ),
            },
            { ...oneLine, title: '바꾼 사람', dataIndex: 'changedByName' },
            {
              title: '사유',
              dataIndex: 'reason',
              render: (value: string) => (
                <div style={{ minWidth: 240, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                  {value}
                </div>
              ),
            },
          ]}
        />
      </Card>
      <Modal
        title={target === 'stop' ? 'AI 조사 정지' : 'AI 조사 다시 켜기'}
        open={target !== null}
        okText={target === 'stop' ? '정지' : '다시 켜기'}
        okButtonProps={{ danger: target === 'stop', disabled: reason.trim().length === 0 }}
        cancelText="닫기"
        confirmLoading={saving}
        onOk={() => void submit()}
        onCancel={() => setTarget(null)}
      >
        <p>
          {target === 'stop'
            ? '정지하면 다음 보고서부터 AI를 부르지 않고, 지금 진행 중인 보고서도 고정 보고서로 마무리합니다. 사유는 변경 기록에 남습니다.'
            : '원인을 확인했으면 다시 켭니다. 다음 보고서부터 AI 조사를 씁니다. 정지 중 만든 고정 보고서는 그대로 남습니다.'}
        </p>
        <Input.TextArea
          value={reason}
          maxLength={REASON_MAX}
          showCount
          rows={3}
          placeholder={
            target === 'stop'
              ? '정지 사유 (필수, 500자까지). 예: 요약에 조회 결과에 없는 금액이 반복해서 나옴'
              : '다시 켜는 사유 (필수, 500자까지). 예: 원인 확인 · 조치 내용'
          }
          onChange={(event) => setReason(event.target.value)}
        />
      </Modal>
    </>
  )
}
