import { Alert, Tabs } from 'antd'
import type { ComponentType } from 'react'
import { useSearchParams } from 'react-router-dom'
import { PageHeader } from '../../components/common/PageHeader'
import {
  CompareSection,
  DataSection,
  LimitsSection,
  OverviewSection,
  SplitSection,
  TrainingSection,
  VersionsSection,
} from './mlModel/MlModelSections'
import { mlModelTab, mlModelTabs, type MlModelTab } from './mlModel/mlModelRecord'
import './MlModelPage.css'

const sections: Record<MlModelTab, ComponentType> = {
  overview: OverviewSection,
  data: DataSection,
  split: SplitSection,
  training: TrainingSection,
  compare: CompareSection,
  versions: VersionsSection,
  limits: LimitsSection,
}

/** 보안 직원 [탐지 › ML 모델] (#778) — 거래 내역 "ML 참고" 점수를 내는 모델의 실험 기록. 탭 하나씩 보여 준다 */
export function MlModelPage() {
  const [query, setQuery] = useSearchParams()
  const tab = mlModelTab(query.get('tab'))
  const Section = sections[tab]

  return (
    <div className="mlm-page">
      <PageHeader
        title="ML 모델"
        description='거래 내역의 "ML 참고" 점수를 내는 모델이 어떻게 만들어졌는지 — 데이터를 어떻게 만들고, 어떻게 나눠 학습 · 검증 · 시험했고, 다른 모델과 비교해 왜 이 모델을 골랐는지의 기록입니다.'
      />
      <Alert
        className="dw-page-alert"
        type="info"
        showIcon
        message={
          <>
            ML 점수는 <strong>결제 판정에 쓰지 않습니다</strong>(그림자 채점, Shadow). 판정은 탐지
            룰만 합니다. 아래 숫자는 모두 <strong>합성 데이터 실험 결과</strong>이며 실제 결제에서의
            성능을 보장하지 않습니다.
          </>
        }
      />
      <Tabs
        activeKey={tab}
        onChange={(key) => setQuery(key === 'overview' ? {} : { tab: key }, { replace: true })}
        items={mlModelTabs.map((item) => ({ key: item.key, label: item.label }))}
      />
      <div className="mlm-body">
        <Section />
      </div>
    </div>
  )
}
