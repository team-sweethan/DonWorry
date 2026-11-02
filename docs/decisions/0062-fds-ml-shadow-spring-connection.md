# 0062. FDS ML Shadow Spring 연결 — P4가 커밋된 평가를 주기적으로 읽어 채점 · 기록

- **상태:** 제안 — #409 PR(P2 조회) 구현, PR 리뷰 대상. 저장 · 실행은 후속 PR
- **날짜:** 2026-11-02
- **관련:** Issue #409 · #375, [ADR-0061](0061-fds-ml-shadow-inference.md) (ai-server RF 추론 API), [ADR-0009](0009-ai-investigation-call-structure.md), [ADR-0012](0012-payment-settlement-dependency.md), 설계 [`375-fds-ml-shadow-design.md`](../investigation/375-fds-ml-shadow-design.md)
- **영향 받는 담당:** P2 (fds/api 공개 조회 추가 — 정후님 허락 2026-11-02), P4 (실행 · 저장)

## 배경

- ADR-0061로 ai-server에 `POST /ml/fraud-score`(RF 사기 확률)가 생겼다. 결제 판정은 Rule Engine만 하고 ML은 Shadow로 기록만 한다
- Spring이 ai-server에 보낼 입력(대상 결제 · 고객 이전 이력 · 같은 카드를 쓴 다른 고객 이력)은 P2 `fds_evaluations`에 있다. investigation(P4 포함)은 이미 `fds :: api`만 쓸 수 있다
- P2는 카드 토큰 · 기기 ID · 다른 고객 번호 원문을 모듈 밖으로 내보내지 않는다 (#346 · #352)

## 결정

1. **실행 방식** — P4(`investigation/report`) 스케줄러가 30초마다 커밋된 평가를 fds/api로 읽어 ai-server에 보내고 결과를 P4 테이블에 기록한다. 결제 · Rule 평가 코드는 바꾸지 않는다
2. **fds/api 조회 계약** — `FdsMlInputReader` (읽기 전용)
   - `findNewTargets(afterEvaluationId, settledBefore, limit)`: 결제마다 처음 나온 비오류 평가만, 오래된 순. 재평가는 ERROR에서만 생기므로 이것이 최종 Rule 판정이다. 걸러내기는 SQL 안에서 한다 (Java에서 거르면 한 번에 읽은 행이 모두 걸러질 때 커서가 멈춘다)
   - `findInput(evaluationId, historyLimit)`: 대상 + 고객 이력(같은 가맹점 · 같은 `customer_ref`) + 같은 카드 다른 고객 이력, 모두 대상 시각 이하 · 결제당 1행 · Rule 결과와 상관없이 모든 결제. 합계가 상한을 넘으면 자르지 않고 `historyOverLimit`
3. **식별값 별칭** — 카드 · 기기 · 고객 번호는 응답 하나 안에서만 통하는 별칭(대상 고객 `T`, 다른 고객 `O1`…, 카드 `C1`…, 기기 `D1`…)으로 내보낸다. Feature는 같은지만 비교하므로 확률이 같다
4. **같은 카드 범위** — 같은 가맹점 안에서만 찾는다. 학습 데이터의 도난 카드도 같은 가맹점 안에서만 만들어졌고, 기존 인덱스 · 쿼리로 된다
5. **저장 · 실패 처리 (후속 PR)** — P4 소유 `ml_shadow_scores`에 `UNIQUE(payment_id, model_version)`로 결제당 한 행(SUCCEEDED · FAILED · SKIPPED)을 남긴다. 커서는 그 테이블의 가장 큰 `evaluation_id`. ai-server 연결 실패 · 503 · 모델 버전 불일치는 기록 없이 다음 주기에 다시 한다. 상세는 설계 문서 7~9절

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| P2가 평가 저장 후 이벤트 발행 → P4 구독 | P2 평가 경로를 고쳐야 하고, 이벤트 유실 · 재처리 설계가 따로 필요하다 |
| 결제 중 동기 호출 | 결제가 ML 지연 · 장애에 묶인다 (ADR-0061) |
| P4가 `fds_evaluations` 행을 받아 이력 구성 | 원문 식별값이 P2 밖으로 나가고, 조회 규칙이 두 모듈에 생긴다 |
| 원문 또는 고정 해시(HMAC)로 전달 | 원문 비공개 선례와 어긋난다. 고정 해시는 응답끼리 이어져 사실상 식별자다 |
| 같은 카드를 가맹점을 넘어 조회 | `card_token` 인덱스(P2 마이그레이션)가 필요하고 학습에 없던 경우다. v2 모델 때 다시 본다 |
| 큰 이력을 최근 N건으로 잘라 전송 | 고객 건수 · 평균 Feature가 학습과 달라진다. 상한 초과는 채점하지 않고 표시한다 |
| 처음 나온 평가 거르기를 Java에서 | 한 번에 읽은 행이 모두 걸러지면 기록이 생기지 않아 커서가 같은 자리를 반복한다 |

## 영향

- API: P2 `fds/api`에 `FdsMlInputReader` · `FdsMlTarget` · `FdsMlInput` · `FdsMlTransaction` 추가 (HTTP API 없음, `SecurityConfig` 변경 없음)
- DB: 이번 PR 없음 (P2 조회 쿼리 2개 추가, 기존 인덱스 사용). 후속 PR에서 P4 `ml_shadow_scores` 추가
- 의존: 변경 없음 (investigation → `fds :: api` 기존 허용)
- 화면: 없음
- 테스트: P2 조회 단위 테스트 4개 · MariaDB 테스트 4개

## 검증

- 이 PR에서 실행할 것: `cd backend && ./gradlew spotlessApply && ./gradlew check mariaDbTest`
- 미검증: P4 스케줄러 · 저장(후속 PR), 실제 결제 데이터에서의 지연 · 부하
