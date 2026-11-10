import { CheckCircleFilled, CloseCircleFilled } from '@ant-design/icons'
import { Card, Descriptions, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { ReactNode } from 'react'
import { Bars, SourceLine, StatTile, ThoughtBox } from './MlModelParts'
import {
  baseDataset,
  baseSplitPeriods,
  baseSplits,
  compareSet,
  compareThoughts,
  count,
  currentModel,
  customerSegments,
  dataThoughts,
  featureImportance,
  limitThoughts,
  modelCards,
  modelColors,
  modelScores,
  percent,
  realisticConditions,
  realisticDataset,
  realisticSplits,
  ruleActualWrongAt50,
  scenarioCaughtAt50,
  scenarios,
  shadowCheck,
  sources,
  splitThoughts,
  tabformer,
  testResult,
  timeline,
  trainingFacts,
  trainingSteps,
  trainingThoughts,
  v3Conditions,
  versionThoughts,
  versions,
  type SplitRow,
} from './mlModelRecord'

const title = (text: string) => <span className="dw-section-title">{text}</span>
const num = { align: 'right' as const }

function SectionCard({
  heading,
  sub,
  children,
}: {
  heading: string
  sub?: ReactNode
  children: ReactNode
}) {
  return (
    <Card title={title(heading)} className="mlm-card">
      {sub && <p className="mlm-sub">{sub}</p>}
      {children}
    </Card>
  )
}

function KeyValues({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <Descriptions column={1} colon={false} size="small" className="mlm-kv">
      {items.map((item) => (
        <Descriptions.Item key={item.label} label={item.label}>
          {item.value}
        </Descriptions.Item>
      ))}
    </Descriptions>
  )
}

function SimpleTable<T extends object>({
  columns,
  rows,
  rowKey,
}: {
  columns: ColumnsType<T>
  rows: T[]
  rowKey: keyof T
}) {
  return (
    <Table<T>
      className="mlm-table"
      size="small"
      pagination={false}
      columns={columns}
      dataSource={rows}
      rowKey={(row) => String(row[rowKey])}
    />
  )
}

// ---- 한눈에 ----

export function OverviewSection() {
  const rule = modelScores.find((m) => m.key === 'rule')
  const current = modelScores.find((m) => m.current)
  return (
    <>
      <SectionCard
        heading="지금 쓰는 모델"
        sub={`${currentModel.version} · ${currentModel.algorithm}(결정 나무 ${currentModel.trees}그루의 다수결). 결제마다 "사기일 확률"을 계산해 ${currentModel.thresholdPercent}% 이상이면 HIGH로 기록만 합니다.`}
      >
        <div className="mlm-stat-grid">
          <StatTile
            label="지금 쓰는 모델"
            value={currentModel.version}
            foot={`${currentModel.since}부터 · 입력 ${currentModel.featureCount}칸 · 기준값 ${currentModel.thresholdPercent}%`}
          />
          <StatTile
            label="학습에 쓴 합성 거래"
            value={count(baseDataset.total)}
            foot={`사기 ${count(baseDataset.fraud)}건 (${percent(baseDataset.fraud, baseDataset.total)})`}
          />
          <StatTile
            label="정상 결제를 5건만 잘못 의심할 때 잡은 사기"
            value={`${rule?.caught.fp5} → ${current?.caught.fp5}`}
            foot={`탐지 룰 → rf-v2 · ${count(compareSet.fraud)}건 중`}
          />
          <StatTile
            label="마지막 시험(Test)에서 잡은 사기"
            value={`${testResult.caught} / ${testResult.fraud}`}
            foot={`잘못 의심한 정상 ${testResult.wrongNormal} / ${count(testResult.normal)} · F1 ${testResult.f1.toFixed(2)}`}
          />
          <StatTile
            label="외부 데이터(TabFormer) AP"
            value={tabformer.ap[1].value.toFixed(4)}
            foot={`무작위 ${tabformer.ap[0].value.toFixed(4)}과 거의 같음`}
            tone="bad"
          />
        </div>
      </SectionCard>
      <SectionCard heading="걸어 온 길">
        <ol className="mlm-timeline">
          {timeline.map((step) => (
            <li key={step.title}>
              <span className="mlm-timeline-when">{step.when}</span>
              <strong>{step.title}</strong>
              <span>{step.body}</span>
            </li>
          ))}
        </ol>
      </SectionCard>
      <SourceLine text={sources.overview} />
    </>
  )
}

// ---- ① 데이터 만들기 ----

export function DataSection() {
  const scenarioColumns: ColumnsType<(typeof scenarios)[number]> = [
    { title: '시나리오', dataIndex: 'label' },
    { title: '어떻게 만들었나', dataIndex: 'how', className: 'mlm-muted' },
    { title: '거래', dataIndex: 'transactions', ...num, render: count },
    { title: '공격 사건', dataIndex: 'episodes', ...num, render: count },
  ]
  return (
    <>
      <SectionCard
        heading="합성 데이터를 어떻게 만들었나"
        sub={`실제 결제 · 사기 정답 데이터가 없고 실제 개인정보는 쓸 수 없어서, 가상 손님 · 카드 · 기기로 거래를 직접 만들었습니다. seed ${baseDataset.seed}로 고정해 몇 번을 다시 만들어도 같은 파일이 나옵니다.`}
      >
        <div className="mlm-grid2">
          <div className="mlm-box">
            <h4 className="mlm-box-title">기본 데이터 (rf-v1 · rf-v2 학습용)</h4>
            <KeyValues
              items={[
                { label: '기간', value: baseDataset.period },
                { label: '가상 가맹점', value: baseDataset.merchants },
                {
                  label: '가상 손님',
                  value: `${count(baseDataset.customers)}명 (거래가 생긴 손님 ${count(baseDataset.activeCustomers)}명)`,
                },
                { label: '정상 거래', value: `${count(baseDataset.normal)}건` },
                {
                  label: '사기 거래',
                  value: `${count(baseDataset.fraud)}건 (${percent(baseDataset.fraud, baseDataset.total)})`,
                },
                {
                  label: '공격 사건',
                  value: `${count(baseDataset.attackEpisodes)}개 (사기 거래를 사건으로 묶음)`,
                },
                {
                  label: '정상 금액',
                  value: `중앙값 ${count(baseDataset.normalAmount.median)}원 · 상위 10% ${count(baseDataset.normalAmount.top10)}원 · 상위 1% ${count(baseDataset.normalAmount.top1)}원`,
                },
              ]}
            />
            <h4 className="mlm-box-title mlm-gap">정상 손님 3가지 유형</h4>
            <SimpleTable
              rowKey="name"
              rows={customerSegments}
              columns={[
                { title: '유형', dataIndex: 'name' },
                { title: '비율', dataIndex: 'share', ...num, render: (v: number) => `${v}%` },
                { title: '한 번 결제', dataIndex: 'amount', ...num },
                { title: '한 달 결제', dataIndex: 'monthly', ...num },
              ]}
            />
          </div>
          <div className="mlm-box">
            <h4 className="mlm-box-title">사기 시나리오 7종</h4>
            <SimpleTable rowKey="key" rows={scenarios} columns={scenarioColumns} />
            <p className="mlm-total">
              합계 거래 {count(scenarios.reduce((sum, s) => sum + s.transactions, 0))} · 공격 사건{' '}
              {count(scenarios.reduce((sum, s) => sum + s.episodes, 0))}
            </p>
          </div>
        </div>
      </SectionCard>
      <SectionCard
        heading="현실형 데이터 (rf-v3 실험용)"
        sub='기본 데이터가 "사기는 티가 나게" 만들어졌다는 반성에서, 사기를 줄이고 헷갈리는 정상과 숨은 사기를 일부러 넣었습니다. 접속 국가 칸을 더했습니다 (IP 원본은 만들지 않음).'
      >
        <div className="mlm-grid2">
          <div className="mlm-box">
            <KeyValues
              items={[
                {
                  label: '규모',
                  value: `${count(realisticDataset.total)}건 · 손님 ${count(realisticDataset.customers)}명`,
                },
                {
                  label: '사기',
                  value: `${count(realisticDataset.fraud)}건 (${percent(realisticDataset.fraud, realisticDataset.total, 1)}) · 시나리오마다 ${realisticDataset.fraudPerScenario}`,
                },
                { label: '접속 국가', value: realisticDataset.countries },
              ]}
            />
          </div>
          <div className="mlm-box">
            <SimpleTable
              rowKey="label"
              rows={realisticConditions}
              columns={[
                { title: '상황', dataIndex: 'label' },
                { title: '거래', dataIndex: 'count', ...num, render: count },
              ]}
            />
          </div>
        </div>
        <ThoughtBox items={dataThoughts} />
      </SectionCard>
      <SourceLine text={sources.data} />
    </>
  )
}

// ---- ② 나누기 ----

const splitColors: Record<string, string> = {
  train: '#2563eb',
  validation: '#0f9d8a',
  test: '#9333ea',
}

function splitColumns(withUse: boolean): ColumnsType<SplitRow> {
  const columns: ColumnsType<SplitRow> = [
    { title: '구간', dataIndex: 'label' },
    { title: '전체', key: 'total', ...num, render: (_, row) => count(row.normal + row.fraud) },
    { title: '정상', dataIndex: 'normal', ...num, render: count },
    { title: '사기', dataIndex: 'fraud', ...num, render: count },
    {
      title: '사기율',
      key: 'rate',
      ...num,
      render: (_, row) =>
        row.key === 'excluded' ? '' : percent(row.fraud, row.normal + row.fraud, 1),
    },
  ]
  if (withUse) columns.push({ title: '쓰는 곳', dataIndex: 'use', className: 'mlm-muted' })
  return columns
}

export function SplitSection() {
  const parts = baseSplits.filter((row) => row.key !== 'excluded')
  const total = parts.reduce((sum, row) => sum + row.normal + row.fraud, 0)
  return (
    <>
      <SectionCard
        heading="학습 · 검증 · 시험으로 어떻게 나눴나"
        sub="거래 시각 순으로 앞 70%는 학습(Train), 다음 15%는 모델 고르기(Validation), 마지막 15%는 마지막 시험(Test)에만 씁니다."
      >
        <div className="mlm-stack" role="img" aria-label="시간순 구간 비율 70 · 15 · 15">
          {parts.map((row) => (
            <span
              key={row.key}
              style={{
                width: `${((row.normal + row.fraud) / total) * 100}%`,
                background: splitColors[row.key],
              }}
            >
              {row.key === 'train'
                ? `Train ${count(row.normal + row.fraud)}건 · ${baseSplitPeriods.train}`
                : row.key === 'test'
                  ? `Test ${count(row.normal + row.fraud)} · ${baseSplitPeriods.test}`
                  : `Validation ${count(row.normal + row.fraud)}`}
            </span>
          ))}
        </div>
        <div className="mlm-box mlm-gap">
          <SimpleTable rowKey="key" rows={baseSplits} columns={splitColumns(true)} />
        </div>
        <div className="mlm-box mlm-gap">
          <h4 className="mlm-box-title">구간별 사기 시나리오 (기본 데이터)</h4>
          <SimpleTable
            rowKey="key"
            rows={scenarios}
            columns={[
              { title: '시나리오', dataIndex: 'label' },
              { title: 'Train', key: 'train', ...num, render: (_, s) => s.split.train },
              {
                title: 'Validation',
                key: 'validation',
                ...num,
                render: (_, s) => s.split.validation,
              },
              { title: 'Test', key: 'test', ...num, render: (_, s) => s.split.test },
              { title: '제외', key: 'excluded', ...num, render: (_, s) => s.split.excluded || '' },
            ]}
          />
        </div>
        <div className="mlm-box mlm-gap">
          <h4 className="mlm-box-title">현실형 데이터(v3)도 같은 방법 — 경계에 걸친 사건 0개</h4>
          <SimpleTable rowKey="key" rows={realisticSplits} columns={splitColumns(false)} />
        </div>
        <ThoughtBox items={splitThoughts} />
      </SectionCard>
      <SourceLine text={sources.split} />
    </>
  )
}

// ---- ③ 학습 ----

export function TrainingSection() {
  const top = featureImportance[0].share
  return (
    <>
      <SectionCard heading="어떻게 학습했나 — 입력 12칸과 고르는 절차">
        <div className="mlm-grid2">
          <div className="mlm-box">
            <h4 className="mlm-box-title">입력 12칸과 모델이 많이 기댄 순서 (rf-v2)</h4>
            <Bars
              max={top}
              rows={featureImportance.map((f) => ({
                label: f.label,
                value: f.share,
                text: `${f.share.toFixed(1)}%`,
                color: modelColors.rfV2,
              }))}
            />
            <p className="mlm-sub mlm-gap">
              막대 = 나무들이 판단할 때 그 칸을 쓴 비중(합 100%). "사기면 이 칸이 크다"는 뜻은
              아닙니다.
            </p>
          </div>
          <div className="mlm-box">
            <h4 className="mlm-box-title">고르는 절차</h4>
            <ol className="mlm-steps">
              {trainingSteps.map((step) => (
                <li key={step.title}>
                  <strong>{step.title}</strong>
                  <span>{step.body}</span>
                </li>
              ))}
              <li>
                <strong>4. Test로 한 번 채점</strong>
                <span>
                  F1 {testResult.f1} · 잡은 사기 {testResult.caught}/{testResult.fraud}(
                  {percent(testResult.caught, testResult.fraud, 1)}) · 잘못 의심한 정상{' '}
                  {testResult.wrongNormal}/{count(testResult.normal)}(
                  {percent(testResult.wrongNormal, testResult.normal, 1)}) · AP{' '}
                  {testResult.ap.toFixed(4)}
                </span>
              </li>
            </ol>
            <KeyValues items={trainingFacts} />
          </div>
        </div>
        <ThoughtBox items={trainingThoughts} />
      </SectionCard>
      <SourceLine text={sources.training} />
    </>
  )
}

// ---- ④ 모델 비교 ----

export function CompareSection() {
  const at50 = modelScores.filter((m) => m.key !== 'control')
  const scenarioRows = scenarios.map((s) => ({
    key: s.key,
    label: s.label,
    total: s.split.validation,
  }))
  return (
    <>
      <SectionCard
        heading="모델 비교 — 정상 결제를 똑같이 잘못 의심할 때, 사기를 몇 건 잡았나"
        sub={`문제지: Validation ${count(compareSet.normal + compareSet.fraud)}건(정상 ${count(compareSet.normal)} · 사기 ${compareSet.fraud}). 모든 모델이 학습에 쓰지 않은 같은 구간입니다. 모델마다 정상 결제를 잘못 의심하는 수를 똑같이 맞춘 뒤 사기를 몇 건 잡았는지 셉니다.`}
      >
        <div className="mlm-model-cards">
          {modelCards.map((card) => (
            <div key={card.key} className="mlm-box">
              <strong>
                <span
                  className="mlm-dot"
                  style={{ background: modelColors[card.key] }}
                  aria-hidden="true"
                />
                {card.title}
              </strong>
              <span>{card.body}</span>
            </div>
          ))}
        </div>
        <div className="mlm-box mlm-gap">
          <h4 className="mlm-box-title">정상 결제를 50건(정상의 약 2%)까지 잘못 의심할 때</h4>
          <Bars
            max={compareSet.fraud}
            rows={at50.map((m) => ({
              label: m.current ? `${m.label} (사용 중)` : m.label,
              value: m.caught.fp50,
              text: `${m.caught.fp50} (${percent(m.caught.fp50, compareSet.fraud, 1)})${m.key === 'rule' ? '*' : ''}`,
              color: modelColors[m.key],
              strong: m.current,
            }))}
          />
          <p className="mlm-sub mlm-gap">
            * 룰 점수는 0 · 25 · 30 … 처럼 띄엄띄엄이라 잘못 의심을 50건에 맞출 수 없어, 넘지 않는
            가장 가까운 기준(잘못 의심 {ruleActualWrongAt50}건)을 썼습니다.
          </p>
        </div>
        <div className="mlm-box mlm-gap">
          <h4 className="mlm-box-title">
            잘못 의심을 몇 건까지 허용하느냐에 따라 (잡은 사기, {compareSet.fraud}건 중)
          </h4>
          <SimpleTable
            rowKey="key"
            rows={modelScores}
            columns={[
              {
                title: '모델',
                key: 'label',
                render: (_, m) => (
                  <span className={m.muted ? 'mlm-muted' : undefined}>
                    {m.current ? <strong>{m.label}</strong> : m.label}{' '}
                    {m.current && <Tag color="blue">사용 중</Tag>}
                  </span>
                ),
              },
              {
                title: (
                  <>
                    정상 0건 잘못 의심
                    <br />
                    <small>룰 "거절"과 같은 수준</small>
                  </>
                ),
                key: 'fp0',
                ...num,
                render: (_, m) => m.caught.fp0,
              },
              {
                title: (
                  <>
                    정상 5건 잘못 의심
                    <br />
                    <small>룰 "보류"와 같은 수준</small>
                  </>
                ),
                key: 'fp5',
                ...num,
                render: (_, m) => m.caught.fp5,
              },
              {
                title: '정상 50건 잘못 의심',
                key: 'fp50',
                ...num,
                render: (_, m) => m.caught.fp50,
              },
              {
                title: (
                  <>
                    AP
                    <br />
                    <small>기준값 없는 순위 점수</small>
                  </>
                ),
                dataIndex: 'ap',
                ...num,
                render: (v: number) => v.toFixed(4),
              },
            ]}
          />
        </div>
        <div className="mlm-box mlm-gap">
          <h4 className="mlm-box-title">시나리오별 — 정상 50건 잘못 의심할 때 잡은 비율</h4>
          <SimpleTable
            rowKey="key"
            rows={scenarioRows}
            columns={[
              { title: '시나리오', dataIndex: 'label' },
              { title: '건수', dataIndex: 'total', ...num },
              ...scenarioCaughtAt50.map((model) => ({
                title: model.label,
                key: model.key,
                ...num,
                render: (_: unknown, row: (typeof scenarioRows)[number]) => {
                  const rate = model.caught[row.key] / row.total
                  return (
                    <span className={rate === 0 ? 'mlm-bad' : rate < 0.4 ? 'mlm-warn' : undefined}>
                      {percent(model.caught[row.key], row.total)}
                    </span>
                  )
                },
              })),
            ]}
          />
        </div>
        <ThoughtBox items={compareThoughts} />
      </SectionCard>
      <SourceLine text={sources.compare} />
    </>
  )
}

// ---- ⑤ 버전 이야기 ----

const statusTag = { base: 'default', current: 'blue', dropped: 'red' } as const

export function VersionsSection() {
  const rate = (value: number, bad = false) => (
    <span className={bad ? 'mlm-bad' : undefined}>
      {bad ? <strong>{value.toFixed(1)}%</strong> : `${value.toFixed(1)}%`}
    </span>
  )
  return (
    <>
      <SectionCard
        heading="버전 이야기 — 왜 바꿨고, 왜 멈췄나"
        sub="버전을 바꿀 때마다 돌리기 전에 통과 기준을 먼저 정하고, 결과를 본 뒤에는 기준을 고치지 않았습니다."
      >
        <div className="mlm-versions">
          {versions.map((v) => (
            <div
              key={v.key}
              className={
                v.status === 'current' ? 'mlm-box mlm-version is-current' : 'mlm-box mlm-version'
              }
            >
              <div className="mlm-version-head">
                <strong>{v.name}</strong>
                <Tag color={statusTag[v.status]}>{v.statusLabel}</Tag>
                <span className="mlm-muted">{v.when}</span>
              </div>
              <p>{v.summary}</p>
              {v.criteria.length > 0 && (
                <ul className="mlm-criteria">
                  {v.criteria.map((c) => (
                    <li key={c.text} className={c.passed ? undefined : 'is-failed'}>
                      {c.passed ? (
                        <CheckCircleFilled className="mlm-ok" aria-label="통과" />
                      ) : (
                        <CloseCircleFilled className="mlm-bad" aria-label="미달" />
                      )}
                      <span>{c.text}</span>
                      <span className="mlm-criteria-result">{c.result}</span>
                    </li>
                  ))}
                </ul>
              )}
              <ul className="mlm-notes">
                {v.notes.map((note) => (
                  <li key={note}>{note}</li>
                ))}
              </ul>
            </div>
          ))}
        </div>
        <div className="mlm-box mlm-gap">
          <h4 className="mlm-box-title">
            rf-v3 자세히 — v3 Test 30,000건(사기 207), 룰 "보류"와 같은 수(정상 536건)를 잘못 의심할
            때
          </h4>
          <SimpleTable
            rowKey="label"
            rows={v3Conditions}
            columns={[
              { title: '상황', dataIndex: 'label' },
              { title: '건수', dataIndex: 'count', ...num },
              { title: '탐지 룰', key: 'rule', ...num, render: (_, r) => rate(r.rule) },
              { title: 'rf-v2', key: 'rfV2', ...num, render: (_, r) => rate(r.rfV2) },
              {
                title: 'v3 데이터 · 국가 칸 없음',
                key: 'noCountry',
                ...num,
                render: (_, r) => rate(r.noCountry),
              },
              {
                title: 'rf-v3',
                key: 'rfV3',
                ...num,
                render: (_, r) => rate(r.rfV3, !r.fraud && r.rfV3 > 10),
              },
            ]}
          />
          <p className="mlm-sub mlm-gap">
            정상은 잘못 의심한 비율, 사기는 잡은 비율. 국가 칸 없는 모델을 함께 둬서 "데이터를 바꾼
            효과"와 "국가 칸 효과"를 나눠 봤습니다.
          </p>
        </div>
        <ThoughtBox items={versionThoughts} />
      </SectionCard>
      <SourceLine text={sources.versions} />
    </>
  )
}

// ---- ⑥ 한계 · 외부 검증 ----

export function LimitsSection() {
  return (
    <>
      <SectionCard heading="한계와 외부 검증 — 그래서 판정에 쓰지 않습니다">
        <div className="mlm-grid2">
          <div className="mlm-box">
            <h4 className="mlm-box-title">외부 데이터: IBM TabFormer (Apache 2.0)</h4>
            <p className="mlm-sub">
              {tabformer.description} 정상의 {tabformer.wrongNormalPercent}%를 잘못 의심할 때 잡은
              사기:
            </p>
            <Bars
              max={tabformer.fraud}
              labelWidth={190}
              rows={tabformer.caught.map((row) => ({
                label: row.label,
                value: row.count,
                text: `${row.count}건`,
                color: modelColors[row.key],
              }))}
            />
            <div className="mlm-gap">
              <SimpleTable
                rowKey="label"
                rows={tabformer.ap}
                columns={[
                  { title: 'AP (순위 점수)', dataIndex: 'label' },
                  {
                    title: '값',
                    key: 'value',
                    ...num,
                    render: (_, row) => (
                      <span className={row.bad ? 'mlm-bad' : undefined}>
                        {row.value.toFixed(4)}
                      </span>
                    ),
                  },
                ]}
              />
            </div>
          </div>
          <div className="mlm-box">
            <h4 className="mlm-box-title">로컬 시나리오 결제로 본 그림자 기록</h4>
            <p className="mlm-sub">{shadowCheck.description}</p>
            <SimpleTable
              rowKey="label"
              rows={shadowCheck.rows}
              columns={[
                { title: '', dataIndex: 'label' },
                { title: '건수', dataIndex: 'count', ...num },
                { title: '룰 의심', dataIndex: 'rule', ...num },
                { title: 'ML HIGH', dataIndex: 'ml', ...num },
              ]}
            />
            <ul className="mlm-notes mlm-gap">
              {shadowCheck.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          </div>
        </div>
        <ThoughtBox items={limitThoughts} />
      </SectionCard>
      <SourceLine text={sources.limits} />
    </>
  )
}
