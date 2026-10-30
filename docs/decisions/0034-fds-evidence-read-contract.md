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
- **#326에서 더한 필드 — 추가만 한다.** 적중 룰에 `thresholdSnapshot` · `observedValue`(평가 당시 저장한 기준값 · 실제값 JSON 원본, 없으면 `null`)를 싣는다. 이름은 `FdsEvaluationDetail.FdsRuleHitDetail`과 같다.
  - 차단 목록(R07) 실제값에는 카드 토큰 · IP · 이메일 같은 식별자가 들어 있다. 받는 쪽은 원본을 화면 · 외부로 그대로 내보내지 않고 필요한 값만 골라 쓴다 (P4는 숫자 키만, [ADR-0009](0009-ai-investigation-call-structure.md))
- **#346에서 더한 필드 — 추가만 한다 (P2 허락).** `signals`(`PaymentSignals`): `cardDisplayLabel`(마스킹 카드 표시값) · `cardProvided` · `deviceProvided` · `ipCountryCode` · `ipProvided`
  - 원문 식별자(카드 토큰 · IP 주소 · 기기 ID · 이메일)는 싣지 않는다. 표시해도 되는 값(마스킹 카드 · IP 국가)만 옮기고, 나머지는 가맹점이 보냈는지 여부만 준다 (빈 문자열도 안 보낸 것으로 본다)
  - 이전 6필드 생성자는 남겨 `signals`가 `null`인 값으로 만든다 (기존 사용처 · 테스트 그대로)
- **#352에서 더한 창구 — 추가만 한다 (P2 허락).** `fds.api.FdsLinkedPaymentReader.findLinked(merchantId, customerRef, casePaymentIds, since)` → `FdsLinkedPayments`(`card` · `device` · `ip` 각각 `Match`: `provided` · `otherCustomerCount` · `paymentCount` · `resultCounts`(판정별 건수) · `declinedCount`)
  - 사건 결제의 평가에서 카드 토큰 · 기기 ID · IP 주소를 모아, 같은 가맹점 · `since` 이후 평가와 원문으로 맞춘다. 같은 `customerRef` · 사건 결제는 세지 않는다. 이메일은 비교하지 않는다
  - 결과에는 숫자와 판정 코드만 있다 — 원문 식별자 · 다른 고객 번호 · 다른 결제 ID를 실을 칸이 없다. 사건 결제가 그 값을 보내지 않았으면 `provided = false`(비교하지 않음)
  - 결제마다 하나로 세고, 판정은 위와 같은 "최신 비오류 평가" 규칙을 쓴다. 카드사 거절은 평가의 `authorization_result = DECLINED`(P5가 기록)로 센다
  - DB 정렬 규칙이 대소문자를 무시해도 원문이 정확히 같은 값만 센다 (조회 뒤 다시 대조)

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| P5가 P2 평가 응답과 적중 룰을 `PaymentHeld` 이벤트에 복사 | P5가 P2 소유 데이터를 복사·중계해 정본과 어긋날 수 있다. 이벤트는 HELD 사실 통지로 제한한다. |
| P3가 FDS Entity·Repository 직접 조회 | `fds` 모듈 내부 접근으로 Modulith 경계를 위반한다. |
| P2의 경보 HTTP API를 P3가 호출 | 같은 애플리케이션 모듈 간 연동에 HTTP·인증 계약을 불필요하게 추가한다. |
| `ERROR` 평가를 항상 결과에서 제외 | 평가 이력이 없는 결제와 평가 실패 결제를 P3가 구분할 수 없다. |
| (#326) P4가 `FdsEvaluationQueryService.detail`로 기준값 · 실제값 조회 | `fds.service` 내부 클래스라 모듈 경계 위반이고, 평가 ID 단위라 결제 목록을 한 번에 못 읽는다. |
| (#346) 원문 식별자(카드 토큰 · IP 주소 · 기기 ID)를 그대로 제공 | 받는 쪽마다 걸러야 하고, 한 곳만 빠뜨려도 AI 입력 · 보고서로 샌다. 표시값과 여부만 준다 |
| (#352) `FdsEvidenceReader`에 연관 결제 메서드를 더함 | P3 테스트가 이 인터페이스를 가짜로 구현해 쓰고 있어 메서드가 늘면 함께 고쳐야 한다. 쓰는 곳(P4)만 받도록 인터페이스를 나눈다 |
| (#352) 카드 토큰 · 기기 ID · IP 주소 앞자리 인덱스 추가 | 시연 데이터 규모에서는 `merchant_id`로 시작하는 기존 인덱스로 충분하고, P2 테이블 마이그레이션이 늘어난다. 데이터가 늘면 `(merchant_id, card_token / device_id / ip_address, payment_requested_at)` 3개를 추가한다 |
| (#352) 일치하는 결제 목록(결제 ID · 고객 번호)을 내보내고 받는 쪽이 셈 | 다른 고객 번호 · 결제 ID가 P2 밖으로 나가 AI 입력 · 보고서로 샐 수 있다. 집계는 P2가 한다 |
| (#326) P2가 규칙별 숫자를 타입 있는 필드로 풀어서 제공 | 규칙마다 키가 달라 필드가 계속 늘고, 룰을 바꿀 때마다 공개 계약을 고쳐야 한다. 원본 JSON을 주고 받는 쪽이 고른다. |

## 영향

- API: `FdsEvidenceReader`, `FdsPaymentEvidence`를 `fds.api`에 추가한다. P3는 해당 인터페이스만 주입한다.
- DB: 기존 `fds_evaluations`, `fds_rule_hits` 조회만 사용하며 migration은 없다.
- (#326) API: `MatchedRule` 필드 2개 추가. 사용처 P3 `PaymentFdsInfoReader`가 값을 넘기고, P3 화면 응답(`CaseDetailResponse.RuleHitResponse`)은 그대로다. DB 변경 없음.
- (#346) API: `FdsPaymentEvidence.signals` 추가. 사용처 P4 `PaymentSignalReader`(AI 보고서 결제별 카드 · 기기 · 접속 국가). P3 `PaymentFdsInfoReader`는 이 필드를 쓰지 않아 그대로다. DB 변경 없음
- (#352) API: `FdsLinkedPaymentReader` · `FdsLinkedPayments` 추가, 구현 `FdsLinkedPaymentQueryService`. 저장소 조회 메서드 3개(가맹점 + 기간 + 카드 토큰 / 기기 ID / IP 주소 목록) 추가. `FdsEvidenceQueryService`의 최신 평가 고르기를 같은 패키지에서 같이 쓰도록 `static`으로 바꿨다(동작 그대로). 사용처 P4 `LinkedPaymentFinder`. DB 변경 없음
- (#352) 테스트: 다른 고객 수 · 결제 수 · 판정 · 카드사 거절 집계와 원문 미노출, 같은 고객 · 사건 결제 제외, 보내지 않은 값 = 비교 안 함, 재평가 결제 한 번만, 다른 가맹점 · 기간 밖 제외(MariaDB)
- 화면: P3 #163에서 Mock FDS 정보를 실제 공개 조회 결과로 교체할 수 있다.
- 테스트: 최신 유효 판정 선택, 전체 ERROR 처리, 적중 룰 필터링, 입력 검증을 단위 테스트로 확인한다.
- 문서: ADR 목록에 계약을 기록한다. Notion 정본에는 구현 확정 뒤 동기화 여부를 P2·P3가 확인한다.

## 검증

- `cd backend && ./gradlew spotlessApply` — 통과
- `cd backend && ./gradlew test --tests com.donworry.fds.service.FdsEvidenceQueryServiceTests` — 통과
- `cd backend && ./gradlew check` — 통과
- MariaDB 실제 조회는 이 변경에서 실행하지 않는다. P3 실제 화면 연동은 #163 범위다.
