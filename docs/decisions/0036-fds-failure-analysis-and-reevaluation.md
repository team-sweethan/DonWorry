# 0036. P2 FDS 오류 분석·재탐지 계약

- **상태:** 제안 — P2 구현, P5 수신·재탐지 연결 대기
- **날짜:** 2026-10-27
- **관련:** #152, #190, #218, [ADR-0012](0012-payment-settlement-dependency.md), [ADR-0035](0035-long-held-payment-policy.md)
- **영향 받는 담당:** P2 정후 · P5 시은

## 배경

P5가 FDS 평가를 동기 호출한 뒤 P2 내부 시스템 오류의 `ERROR`를 받아 결제를 대기시키는 경우가 있다. P2가 P5의 결제 요청 내부를 직접 읽지 않고, 저장된 ERROR 평가와 오류 조사에 필요한 스냅샷을 공개 계약으로 받아야 한다.

기존 `FdsEvaluator.evaluate`는 같은 `evaluationRequestId`의 평가 결과를 반환하는 최초 탐지 계약이다. 이 계약을 바꾸면 기존 P5 호출자에 영향을 주며, 같은 UUID 재호출은 새 평가가 아니다.

## 결정

- 기존 `fds.api.FdsEvaluator.evaluate(FdsEvaluationRequest)`는 변경하지 않는다.
- P5는 P2 내부 시스템 오류의 `ERROR`를 `FdsFailureReporter.report(FdsEvaluationFailureReport)`로 P2에 보고한다. 보고에는 `failureReportId`, 원본/현재 평가 UUID, 저장된 평가 ID·오류 코드, 원래 평가 입력, P5 관측 시각·상태 스냅샷을 포함한다.
- P2는 `fds_failure_analyses`에 보고와 분석 결과를 저장한다. P5 소유 ID는 논리 참조이며 FK를 만들지 않는다.
- 분석 저장 후 P2는 `fds.api.FdsEvaluationFailureAnalyzed` 이벤트를 발행한다. 이벤트 타입은 P5가 import 가능한 `fds.api`에 둔다.
- P2가 실제 `ERROR` 평가와 일시 오류 복구 근거를 확인해 `REEVALUATION_AVAILABLE`를 발행한 경우에만, P5는 `FdsReevaluationRequester.reevaluate(FdsReevaluationRequest)`를 호출할 수 있다.
- 재탐지 요청은 원본 UUID와 새 UUID, `retryId`를 함께 전달한다. P2는 새 평가를 `fds_evaluations`에 저장하고 원본 UUID·재탐지 키를 연결한다.
- P2는 P5 결제 상태를 변경하지 않으며, 분석 이벤트도 재탐지를 강제하지 않는다.

## 실패 유형과 분석 결과

| P5 관측 | P2 확인 | 분석 결과 |
| --- | --- | --- |
| `FDS_ERROR_RESPONSE` | 같은 입력의 `ERROR` 평가, 오류 코드가 `TRANSIENT_`로 시작 | `REEVALUATION_AVAILABLE` |
| 내부 오류 보고인데 P2에 평가 행이 없음 | P2 저장 상태 불일치 | `EVALUATION_NOT_FOUND` |
| 입력 스냅샷 불일치 | P2 평가 행과 P5 보고 입력이 다름 | `PAYMENT_FOLLOW_UP_REQUIRED` |
| 그 밖의 `ERROR` | 자동 재탐지 근거 없음 | `MANUAL_REVIEW_REQUIRED` |

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 최초 탐지 API에 오류 분석 필드를 추가 | 정상 최초 탐지와 사후 오류 분석의 책임이 섞이고 기존 호출자 계약이 바뀐다. |
| 같은 평가 UUID로 최초 탐지 API를 재호출 | 기존 저장 결과를 반환하는 멱등 호출이므로 새 평가가 실행되지 않는다. |
| P2가 P5 테이블을 직접 조회 | 모듈 경계를 위반한다. |
| P2가 오류만으로 결제를 자동 취소·거절 | 평가 오류는 금융·결제 종료 근거가 아니다. |

## 영향

- API: P2 공개 오류 보고·재탐지 인터페이스와 분석 완료 이벤트가 추가된다.
- DB: P2 `fds_failure_analyses`와 `fds_evaluations` 재탐지 연결 컬럼을 추가한다.
- P5: 이벤트 수신·분석 결과 저장·재탐지 실행·새 평가 결과 반영은 별도 작업이다.
- 화면: 자동 흐름에는 화면이 필요 없다. 운영용 오류 조회·수동 실행 화면은 별도 설계한다.
- 정책: 자동 재탐지 횟수·간격·실행 주체는 이 ADR에서 결정하지 않는다.
