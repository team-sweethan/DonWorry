# 0015. FDS 평가 목록 조회의 서버 페이지네이션과 검색 계약

- **상태:** 제안 — 팀 합의 필요
- **날짜:** 2026-10-23
- **관련:** Issue #93, PR #96
- **영향 받는 담당:** P2 정후, P1 주환 (+ frontend)

## 배경

FDS 평가는 삭제하지 않는 이력 데이터다. 기존처럼 목록 전체를 한 번에 반환한 뒤 프론트에서 페이지를 나누면, 데이터가 늘수록 DB 전체 조회·정렬과 응답 전송, 브라우저 렌더링 비용이 함께 증가한다.

경보 화면은 선택한 가맹점 범위 안에서 평가 ID, 결제 ID, 고객 식별자로 평가 이력을 검색해야 한다. 현재 페이지의 데이터만 프론트에서 검색하면 다른 페이지에 있는 결과를 찾을 수 없다.

## 결정

`GET /api/fds/evaluations`는 서버 페이지네이션과 검색을 제공한다.

- 요청 선택 파라미터: `result`, `attentionOnly`, `search`, `page`, `size`
- 응답: `content`, `page`, `size`, `totalElements`, `totalPages`, `resultCounts`를 가진 페이지 객체
- 기본 페이지 크기는 10건이며, 목록 정렬은 `evaluatedAt DESC, evaluationId DESC`로 고정한다.
- `search`는 평가 ID, 결제 ID, 고객 식별자를 대상으로 서버에서 적용한 뒤 페이지네이션한다. 가맹점은 P1 목록 드롭다운의 `merchantId` 선택으로 범위를 정하므로 검색어 대상에 넣지 않는다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 전체 평가 배열을 반환하고 프론트에서 페이지네이션 | 평가 이력이 누적될수록 전체 DB 조회·응답 전송·브라우저 처리 비용이 증가한다. |
| 현재 페이지 데이터만 프론트에서 검색 | 다른 페이지의 평가 ID·결제 ID·고객 식별자를 검색할 수 없다. |
| 커서 기반 페이지네이션 | 현재 경보 화면은 페이지 번호와 총 건수를 표시하는 요구가 있어 offset 기반 페이지네이션이 화면 요구에 더 직접적이다. |

## 영향

- API: `GET /api/fds/evaluations`의 응답이 배열에서 페이지 객체로 변경되고, 페이지·검색 파라미터가 추가된다.
- DB: 스키마 변경 없음.
- 화면: `/security/alerts`가 서버 페이지네이션·검색 결과를 사용한다.
- 테스트: 가맹점 선택 범위 안의 검색, 판정 필터와 검색 조합, 페이지네이션을 MariaDB 통합 테스트로 검증한다.
- 문서: 팀 합의 후 API 명세와 PR 설명을 이 계약에 맞춘다.

## 검증

검증됨:

- `cd backend && ./gradlew.bat check` 통과
- `cd backend && ./gradlew.bat mariaDbTest --tests com.donworry.fds.service.FdsEvaluationQuerySearchTests` 통과
- `cd frontend && npm run lint && npm run format:check && npm run build` 통과

미검증:

- 현재 목록 조회는 경보 화면 시연 범위의 전체 평가를 대상으로 한다. 가맹점 권한 범위·실제 로그인 연동 등 후속 조치로 조회 범위와 접근 제어 계약이 확정·변경되면, 목록·상세 API와 화면을 함께 통합 검증한다.
