# 0046. 가맹점 목록 공개 조회와 FDS 경보 가맹점 필터

- **상태:** 채택 — 구현 반영
- **날짜:** 2026-10-29
- **관련:** ADR-0012, ADR-0015, ADR-0020
- **영향 받는 담당:** P1 주환, P2 정후

## 배경

FDS 평가는 `merchantId`만 저장한다. 경보 화면에서 어느 고객사의 평가인지 선택·표시하려면 P1 소유 가맹점의 코드·이름·상태가 필요하지만, P2는 `merchants` 테이블을 직접 조회할 수 없다.

## 결정

P1은 `MerchantDirectoryReader` 공개 Java 인터페이스와 `GET /api/merchants`를 제공한다. 응답은 `merchantId`, `merchantCode`, `displayName`, `status`이며, 과거 경보를 찾을 수 있도록 ACTIVE·INACTIVE를 모두 반환한다.

P2는 평가 테이블에 가맹점명 사본을 저장하지 않는다. `GET /api/fds/evaluations`에 선택 `merchantId` 파라미터를 추가해 P2 소유 `fds_evaluations.merchant_id`만 서버에서 필터링한다. 경보 화면은 P1 목록에서 가맹점 하나를 선택한 뒤 해당 ID만 P2 목록 API에 전달하며, 검색어는 평가 ID·결제 ID·고객 식별자에만 적용한다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| P2가 `merchants` 테이블을 직접 조인 | 다른 모듈 데이터 소유권과 공개 `api/` 경계를 위반한다. |
| P5가 가맹점명·코드를 평가 요청마다 P2에 전달·저장 | P5도 이름의 소유자가 아니며, 이름 변경 뒤 P2 사본이 오래된다. |
| P2가 가맹점명 변경 이벤트를 구독해 복제 | 단순 조회 표시를 위해 사본·재처리·정합성 문제를 만든다. |

## 영향

- API: P1 가맹점 디렉터리 공개 계약·HTTP 조회, P2 경보 목록의 선택 `merchantId` 필터 추가.
- DB: 변경 없음. P2는 기존 `fds_evaluations.merchant_id`만 조회한다.
- 화면: 경보 목록은 가맹점 하나를 선택한 뒤 표시한다. 드롭다운은 P1의 최신 이름을 표시하고 값은 `merchantId`이며, 전체 가맹점 옵션은 두지 않는다.
- 테스트: P1 디렉터리의 ACTIVE·INACTIVE 반환, P2의 가맹점 서버 필터를 검증한다.
- 문서: ADR 목록과 이 계약을 갱신한다.

## 검증

구현 후 `./gradlew spotlessApply check`, `npm run lint && npm run format:check && npm run build`를 실행한다. MariaDB 통합 검사는 별도로 기록한다.
