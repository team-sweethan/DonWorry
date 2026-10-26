# 0034. P3 Case 화면용 FDS 평가 근거 공개 조회 계약

- **상태:** 제안 — #162 구현, P3 연동·팀 리뷰 대기
- **날짜:** 2026-10-26
- **관련:** Issue #162, [ADR-0012](0012-payment-settlement-dependency.md), [ADR-0023](0023-payment-held-event-contract.md)
- **영향 받는 담당:** P2 정후 · P3 수민

## 배경

P3 Case 화면은 결제별 FDS 점수·판정·적중 룰을 표시해야 하지만, P3가 `fds`의 Entity·Repository를 직접 읽을 수 없다. P5의 `PaymentHeld` 이벤트는 HELD 사실과 Case 생성에 필요한 결제 스냅샷을 전달하는 계약이며, FDS 평가 근거를 복사해 중계하는 용도가 아니다.

`investigation → fds :: api` 의존은 ADR-0012에서 이미 허용한다. 따라서 FDS 데이터의 소유 모듈인 P2가 P3에 읽기 전용 공개 계약을 제공한다.

## 결정

P2는 `fds.api.FdsEvidenceReader.findLatestByPaymentIds(Collection<Long>)`를 공개한다. 반환값 `FdsPaymentEvidence`는 결제별 `paymentId`, `evaluationId`, `result`, `riskScore`, `evaluatedAt`와 `MATCH` 적중 룰(`ruleCode`, `ruleName`, `appliedScore`)만 포함한다.

- 입력의 null·0 이하 ID는 `IllegalArgumentException`으로 거절한다. 중복 ID는 한 번만 조회한다.
- 평가가 있는 결제만 결과에 포함한다. P3는 빠진 결제를 평가 없음으로 표시한다.
- 결제별 평가가 하나 이상이면, `ERROR`를 제외한 가장 최신 평가를 반환한다. 모두 `ERROR`이면 최신 `ERROR`를 반환해 평가 이력이 없는 경우와 구분한다.
- P3는 Case 접근 권한과 가맹점 범위를 먼저 확인한 뒤, 그 Case에 연결된 결제 ID만 전달한다. 이 공개 API는 세션·권한을 직접 검사하지 않는다.
- 조회 구현은 읽기 전용 트랜잭션에서 P2 테이블만 읽으며 이벤트·HTTP 엔드포인트·스키마는 변경하지 않는다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| P5가 P2 평가 응답과 적중 룰을 `PaymentHeld` 이벤트에 복사 | P5가 P2 소유 데이터를 복사·중계해 정본과 어긋날 수 있다. 이벤트는 HELD 사실 통지로 제한한다. |
| P3가 FDS Entity·Repository 직접 조회 | `fds` 모듈 내부 접근으로 Modulith 경계를 위반한다. |
| P2의 경보 HTTP API를 P3가 호출 | 같은 애플리케이션 모듈 간 연동에 HTTP·인증 계약을 불필요하게 추가한다. |
| `ERROR` 평가를 항상 결과에서 제외 | 평가 이력이 없는 결제와 평가 실패 결제를 P3가 구분할 수 없다. |

## 영향

- API: `FdsEvidenceReader`, `FdsPaymentEvidence`를 `fds.api`에 추가한다. P3는 해당 인터페이스만 주입한다.
- DB: 기존 `fds_evaluations`, `fds_rule_hits` 조회만 사용하며 migration은 없다.
- 화면: P3 #163에서 Mock FDS 정보를 실제 공개 조회 결과로 교체할 수 있다.
- 테스트: 최신 유효 판정 선택, 전체 ERROR 처리, 적중 룰 필터링, 입력 검증을 단위 테스트로 확인한다.
- 문서: ADR 목록에 계약을 기록한다. Notion 정본에는 구현 확정 뒤 동기화 여부를 P2·P3가 확인한다.

## 검증

- `cd backend && ./gradlew spotlessApply` — 통과
- `cd backend && ./gradlew test --tests com.donworry.fds.service.FdsEvidenceQueryServiceTests` — 통과
- `cd backend && ./gradlew check` — 통과
- MariaDB 실제 조회는 이 변경에서 실행하지 않는다. P3 실제 화면 연동은 #163 범위다.
