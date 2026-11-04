# 0079. 사장님 403 이벤트 타입과 계정 상태 조회 계약

- **상태:** 제안 — #521 사용자 설계·구현 승인, #515 합의된 경계 유지. path 길이·해제 경계 비교 세부안은 P2 연결 시 확인
- **날짜:** 2026-11-04
- **관련:** [#521](https://github.com/team-sweethan/DonWorry/issues/521), [#515](https://github.com/team-sweethan/DonWorry/issues/515), [ADR-0078](0078-operator-account-suspension.md), [계약·검증 명세](../identity/521-operator-security-contracts.md)
- **영향 받는 담당:** P1 identity, P2 fds

## 배경

P2가 403 수신과 정지 호출을 연결하려면 이벤트 항목과 해제 이후 집계에 사용할 계정 상태 조회가 필요하다. #518은 정지·감사·세션 만료만 제공한다.
403 원본 저장은 후속이므로 먼저 타입을 공개한다. 현재 관리자 해제는 상태 전환과 `USER_ENABLED` 감사를 같은 트랜잭션으로 남기고 기존 대상 계정·시각 인덱스가 있다.

## 결정

기존 `fds → identity :: api` 방향으로 `OperatorAccessDenied`와 `OperatorAccountStatusReader`를 공개한다. 이벤트 식별자는 P1 원본 행 PK인 Long, 발생·해제 시각은 UTC Instant이며 상태는 api 전용 enum이다.
마지막 해제 시각은 새 컬럼 없이 해당 계정의 가장 최근 `USER_ENABLED`에서 읽는다. 없는 계정·직원은 empty, 해제 이력이 없는 OPERATOR는 null 시각을 반환한다. DB 장애는 전파한다.
조회는 호출자와 분리된 읽기 전용 REQUIRES_NEW·REPEATABLE_READ로 상태와 해제 이력을 일관되게 읽는다. 조회 이후 정지 호출까지의 원자성은 보장하지 않는다.

ADR-0078은 정지 자체의 결정으로 유지한다. 이번 이벤트·조회 계약은 독립적으로 검토·변경할 수 있도록 별도 ADR에 둔다.

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| P2가 identity Entity·Repository를 직접 조회 | AGENTS.md 4절의 공개 api 경계를 위반하고 저장 구조에 결합됨 |
| 상태 조회를 HTTP로 제공 | 같은 앱의 정방향 Java 호출로 충분하며 인증·네트워크 경계를 불필요하게 추가함 |
| 내부 UserStatus를 그대로 반환 | 소비자가 identity/entity를 참조하게 되므로 공개 전용 enum으로 변환함 |
| platform_users에 lastActivatedAt 추가 | 기존 감사가 실제 해제마다 원자적으로 기록되며 이번 범위는 DB 변경 없이 제공할 수 있음 |
| 관리자 해제 이벤트로 P2 상태 복사 갱신 | 별도 수신·재전달·순서 처리가 필요하고 #515 합의의 후보 시점 조회 흐름보다 범위가 커짐 |
| P2 집계 트랜잭션에 조회 참여 | 기존 스냅샷·영속성 문맥 때문에 최근 커밋된 해제를 놓칠 수 있음 |
| 이벤트 ID를 UUID로 신규 생성 | 기존 원본 PK를 중복 제거·논리 참조로 재사용하는 #521 계약과 다름 |

## 영향

- API: identity/api의 이벤트·거절 enum·상태 조회·결과·상태 enum 추가. 정지 API·모듈 의존·HTTP 계약은 유지.
- DB: AdminChangeHistoryRepository 조회만 추가. 테이블·인덱스·마이그레이션 변경 없음. 재활성화 경로와 감사 보존은 USER_ENABLED 기준을 유지해야 함.
- 화면: 변경 없음.
- 테스트: 공개 경계·JSON 왕복·조회 단위 검사, 실제 정지·관리자 해제·시각·스냅샷·인덱스 MariaDB 검사 추가.
- 문서: identity 계약 명세·각 README 링크 추가. 실제 403 발행과 P2 구현은 후속.

## 검증

실행 커밋·명령·결과와 미검증 범위는 [계약 명세 실행 기록](../identity/521-operator-security-contracts.md#5-검증-계획과-실행-기록)을 따른다. 기존 #518 실행 결과를 이번 변경의 통과 근거로 사용하지 않는다.
