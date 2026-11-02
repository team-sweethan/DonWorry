import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  failureAnalysisResultLabel,
  failureCauseLabel,
} from '../src/pages/security/alert/failureAnalysis.ts'

// 정의한 코드는 직원용 문구로, 새 코드는 원문 그대로 보여준다.
test('failureAnalysisLabels_whenKnownOrUnknown_mapsOnlyDefinedCodes', () => {
  assert.equal(
    failureCauseLabel('TRANSIENT_FDS_EVALUATION_TIMEOUT'),
    'FDS 평가 처리 시간이 초과되었습니다.',
  )
  assert.equal(
    failureCauseLabel('TRANSIENT_FDS_RULE_DATA_UNAVAILABLE'),
    'FDS 평가에 필요한 룰 정보를 일시적으로 읽을 수 없습니다.',
  )
  assert.equal(
    failureCauseLabel('FDS_EVALUATION_PROCESSING_FAILED'),
    'FDS 평가 처리 중 예상하지 못한 내부 오류가 발생했습니다.',
  )
  assert.equal(failureCauseLabel('NEW_FDS_ERROR'), 'NEW_FDS_ERROR')
  assert.equal(failureAnalysisResultLabel('REEVALUATION_AVAILABLE'), '재평가 가능')
  assert.equal(failureAnalysisResultLabel('NEW_ANALYSIS_RESULT'), 'NEW_ANALYSIS_RESULT')
})
