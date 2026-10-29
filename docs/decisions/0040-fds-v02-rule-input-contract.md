# 0040. FDS v0.2 룰 입력과 P2 정책 저장소

- **상태:** 제안
- **날짜:** 2026-10-28
- **관련:** Notion "DonWorry FDS · 공통 룰 카탈로그 및 결제 차단 시나리오 v0.2"
- **영향 받는 담당:** P2 정후, P5 시은

## 배경

v0.2의 신규 단말기·신규 카드·고위험 국가·카드 테스팅·차단 목록 룰은 P5가 가진 결제 시도 신호와 P2가 보존하는 평가 이력이 필요하다. P2가 P5의 DB나 내부 Repository를 조회하면 `payment → fds` 단방향 의존과 데이터 소유 경계를 위반한다.

## 결정

P5는 P2 공개 평가 계약으로 `deviceId`, 카드 원문이 아닌 `cardToken`, 카드 표시 전용 마스킹 값 `cardDisplayLabel`, 검증된 `ipCountryCode`, 실제 결제 요청의 `ipAddress`, 구매자 `buyerEmail`을 기존 평가 입력과 함께 전달한다. `deviceId`는 기기명(예: 갤럭시25)이 아니라 P5가 안정적으로 생성·재전달하는 불투명 식별값이다. `cardDisplayLabel`은 `VISA •••• 1234`처럼 표시 전용이며 차단 판정에는 쓰지 않는다. P2는 전달받은 스냅샷, `fds_evaluations`의 자체 이력, P2 소유 `fds_high_risk_countries`·`fds_blocklist_entries`만 조회한다. 실제 승인 성공·거절은 P5가 P2 공개 계약으로 기록해 R02/R03/R06의 근거로 쓴다. R08은 이번 변경 범위에서 제외한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| P2가 P5 DB를 직접 조회 | 모듈 내부 접근과 데이터 소유 경계를 위반한다. |
| P2가 카드 원문을 저장 | PCI 범위와 Notion의 카드 원문 저장 금지에 어긋난다. |

## 영향

- API: P5 → P2 평가 입력과 승인 결과 기록 계약이 확장된다. P5는 `deviceId`(최대 100자), `cardToken`(최대 255자), `cardDisplayLabel`(최대 50자·마스킹 값), `ipCountryCode`(ISO alpha-2), `ipAddress`(최대 45자), `buyerEmail`(최대 254자·소문자 정규화)을 함께 전달한다.
- DB: P2 `fds_evaluations`에 룰 입력·승인 결과를 보존하고, P2 소유 정책·차단 목록을 관리한다. 카드 원문은 저장하지 않는다.
- 화면: 보안 직원은 차단 목록을 등록·해제하고, 경보 상세의 기기·IP·이메일 후보를 차단 목록에 추가할 수 있다.
- 테스트: 룰별 입력 누락, 이력, 차단·해제 및 중복 요청을 검증한다.
- 문서: ERD의 P2 테이블을 갱신한다.

## 검증

구현 후 `./gradlew check mariaDbTest`로 확인한다. 현재는 미검증이다.
