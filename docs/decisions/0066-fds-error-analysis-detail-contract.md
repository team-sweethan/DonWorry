# 0066. FDS 경보 상세의 오류 원인·분석 결과 공개 계약

- **상태:** 제안 — 사용자 구현 승인, PR 리뷰 대상
- **날짜:** 2026-11-02
- **관련:** ADR-0036, #417
- **영향 받는 담당:** P2 정후 · P1 주환 (frontend)

## 배경

ERROR 경보 상세에서 P5 `fdsRecovery.reasonCode`는 정상 재평가 완료 시 null이고 재평가 ERROR 시 새 오류 코드가 된다. 최초 ERROR의 원인과 P2 분석 결론을 같은 필드로 표시하면 의미가 섞인다.

## 결정

P2 `GET /api/fds/evaluations/{evaluationId}` 응답에 해당 평가 UUID의 `failureAnalysis`를 선택적으로 추가한다. 값은 P2가 저장한 `disposition`, `reasonCode`, `analyzedAt`이며, P5 복구 상태와 분리한다.

화면은 P2 `errorCode`를 오류 발생 원인, P2 `failureAnalysis.disposition`을 분석 결과로 표시한다. 정해진 코드만 한글 문구로 매핑하고 새·미정 코드에는 원문 코드를 표시한다.

오류 발생 원인은 P2 내부 평가 단계에 따라 다음처럼 정의한다. `TRANSIENT_` 접두사는 ADR-0036의 재평가 가능 후보 규칙을 따른다.

| 오류 코드 | 직원 화면 문구 | 분석 결과 |
| --- | --- | --- |
| `TRANSIENT_FDS_EVALUATION_TIMEOUT` | FDS 평가 처리 시간이 초과되었습니다. | 재평가 가능 |
| `TRANSIENT_FDS_RULE_DATA_UNAVAILABLE` | FDS 평가에 필요한 룰 정보를 일시적으로 읽을 수 없습니다. | 재평가 가능 |
| `FDS_EVALUATION_PROCESSING_FAILED` | FDS 평가 처리 중 예상하지 못한 내부 오류가 발생했습니다. | 수동 검토 필요 |

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| P5 `fdsRecovery.reasonCode`를 분석 사유로 표시 | 완료·재평가 ERROR에서 값의 의미가 달라 P2 분석 결과를 정확히 표현하지 못한다. |
| P5 응답에 P2 분석 사유를 별도 추가 | P2 경보 상세가 이미 P2 데이터를 조회하므로 동일 분석 데이터를 P5 계약에 중복한다. |

## 영향

- API: P2 경보 상세에 nullable `failureAnalysis` 추가
- DB: 없음. 기존 `fds_failure_analyses`를 조회한다.
- 화면: ERROR 경보에서 오류 발생 원인·분석 결과를 한글로 표시한다.
- 테스트: P2 상세 조회와 정의/미정 코드 매핑을 검사한다.

## 검증

- `cd backend && ./gradlew spotlessApply test --tests com.donworry.fds.service.FdsEvaluationQueryServiceTests`
- `cd frontend && npm test && npm run lint && npm run format:check && npm run build`
- 실행 결과는 PR에 기록한다.
