# 2번 접근 통제와 사장님 공개 범위 검증

검증일: 2026-11-09 KST. 사용자 승인 범위의 로컬 구현·합성 데이터 검증 기록이다. 팀 승인·병합·배포·금융권 보안 인증 완료를 의미하지 않는다. 관련 작업은 #690이다. 아래 실제 API·화면 검증은 `478fb941`에 이 변경을 적용한 로컬 서버 기준이며, PR은 이후 `7d0aa6cb` 기준 feature 브랜치로 정리했다. 최종 커밋의 CI 결과는 PR에서 별도로 확인한다.

## 변경 결과와 영향 범위

사장님에게는 사건 처리 안내와 결제별 직원 판정·실제 결제 상태만 제공한다. `MerchantCaseQueryService`에서 `ReportGateway` 의존과 내부 보고서 조회를 제거했다. `MerchantCasePublicSummary`는 `CaseStatus`만 받아 정해진 안내 문장을 만든다. 내부 보고서의 자유 문장을 필터링하거나 다시 요약하지 않는다.

- 공개 응답: `caseId`, `displayStatus`, `createdAt`, `closedAt`, `summary`, `payments`. 결제 ID·금액·요청 시각·직원 판정·실제 결제 상태·고객 취소 여부는 기존 계약을 유지한다.
- 비공개: 내부 AI 요약, IP·기기·다른 고객 집계, FDS 판단 근거, 직원 메모, 조사 방식. `directInvestigation` 필드를 제거했다.
- `summary` 배열의 의미가 내부 보고서 요약에서 상태 기반 안내로 변경됐다. 프론트 타입·테스트·화면을 함께 수정했다. 다른 소비자가 있다면 배포 전에 호환성을 확인해야 한다.
- UI 제목은 `처리 안내`. 조사 종료만으로 전체 결제가 매입·취소·정산됐다고 표현하지 않고 결제별 실제 결과를 보게 한다.
- 직원 보고서, FDS·AI 판단, 결제·정산 로직, DB 스키마, 기존 접근 권한·탐지 임계치는 변경하지 않았다.

수정 파일: `MerchantCaseQueryService.java`, 새 `MerchantCasePublicSummary.java`, `MerchantCaseDetailResponse.java`, `MerchantCaseFlowTests.java`, 새 `MerchantCasePublicSummaryTests.java`, `MerchantCaseDetailPage.tsx`, `merchantCase.ts`, `merchantCases.test.ts`, ADR-0019, investigation README 및 이 검증 문서.

## 실제 API 검증

사용자 터미널에서 실행한 backend 8081, frontend 5173, MariaDB 3307을 사용했다. 실제 API 검증과 자동 통합 테스트 통과는 별개다.

| 항목                               | 실제 결과                                                                                                                                      |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 본인 가맹점 사건 31·32             | 200, 상태 기반 안내만 반환. 결제 81·84의 600000원·ACCEPT·CAPTURED 유지                                                                         |
| 비로그인 사장님 API                | 401                                                                                                                                            |
| 사장님의 직원 사건 API 접근        | 403                                                                                                                                            |
| 직원·관리자의 사장님 API 접근      | 각각 403                                                                                                                                       |
| 다른 가맹점의 사건 32 조회         | 404 / INV_001. 없는 사건과 동일한 응답                                                                                                         |
| 비담당 직원의 진행 중 사건 30 조회 | 403                                                                                                                                            |
| 담당 직원의 사건 32 보고서         | 200, 기존 보고서 35 및 실제 OpenAI 모델 정보 유지                                                                                              |
| 직원 거래 내역 마스킹              | 주문번호로 찾은 실제 합성 결제 1행에 maskedIpAddress·maskedDeviceId·maskedBuyerEmail. 원문 IP·기기·이메일·카드 토큰·주문자 이름·전화 필드 없음 |
| CSRF 없는 CSV 요청                 | 403                                                                                                                                            |

전용 합성 직원 `29` (`2번 접근통제 합성검증`)으로 기존 내부자 통제를 확인했다. 실제 고객 거래를 반출하지 않고 없는 숫자 고객번호를 조회했으며, 정상 CSV는 헤더만 있는 200바이트였다.

1. 정상 상태 CSV 200.
2. 담당 무관 고객 10명 검색 → SEC-INS-09 CAUTION, CSV 200 유지.
3. 30명 검색 → 같은 경보 34가 HIGH로 상승.
4. HIGH 상태 CSV 요청 → 403 / INSIDER_EXPORT_BLOCKED, SEC-INS-05 및 CRITICAL 내부 사건 16 생성, 경보 2개.
5. 기존 세션 401, 새 로그인 401 / AUTH_004로 계정 정지 확인.
6. 일반 계정 상태 변경으로 ACTIVE 복구 시도 → 409 / ADMIN_011. 검토 절차 우회 불가.
7. 관리자가 합성 검증 사유로 NORMAL_DUTY 검토 → 200, 새 로그인 정상 복구 확인.
8. 검증 종료 후 직원 29를 INACTIVE로 비활성화(204). 감사·경보·사건 기록은 삭제하지 않았다.

초기 실행의 비숫자 고객번호는 감사의 customerRef 형식에 해당하지 않아 경보가 없었다. 숫자 합성 고객번호로 수정한 뒤 위 10명·30명 시나리오를 통과했다. 원시 검증 기록에는 실패와 해결 경위를 모두 남겼다. 기존 계정 비밀번호나 가맹점 설정은 변경하지 않았다.

## 화면 검증

- 사장님 `/owner/actions/31`, `/owner/actions/32`: 처리 안내, 조사 종료, 정상 처리 결정, 매입 완료 확인. 내부 AI 요약·조사 방식 안내 없음.
- 직원 `/security/cases/32/report`: 내부 전용 AI 보고서와 다른 고객·FDS 집계 유지 확인.
- 데스크톱 1440px에서 사건 32 전체 결과를 직접 확인하고 캡처했다.
- 390px에서는 기존 공통 스타일 `frontend/src/styles/global.css`의 `min-width: 1280px` 때문에 가로 넘침이 발생한다. 모바일 검증은 통과로 표시하지 않는다. 공통 반응형 변경은 이번 범위에 포함하지 않았다.
- 관리자 내부 사건의 새 화면 캡처는 수행하지 않았다. 정지·검토·복구는 실제 관리자 API로 확인했다.

증거는 대화 outputs의 `DonWorry-plan2-owner32-desktop.png`, `DonWorry-plan2-owner32-mobile-overflow.png`, `DonWorry-plan2-staff-report.png`, `DonWorry-2번-API검증.json`에 보관한다. API 키·비밀번호는 증거에 저장하지 않았다.

## 자동 검사와 CI 대기

| 검사                                             | 결과                                                                        |
| ------------------------------------------------ | --------------------------------------------------------------------------- |
| 프론트 lint                                      | 성공, 기존 경고 2개                                                         |
| 프론트 테스트                                    | 402개 통과                                                                  |
| 프론트 build                                     | 성공, 기존 큰 청크 경고                                                     |
| 백엔드 spotlessApply·compileJava·compileTestJava | 성공                                                                        |
| MerchantCasePublicSummaryTests                   | 7개 통과                                                                    |
| 백엔드 전체 check                                | Mockito/JVM attach의 javatool pipe 접근 오류 뒤 진행 정체로 중단. 통과 아님 |
| MariaDB 전체 통합 검사                           | 사용자가 장시간 실행으로 CI 확인을 선택. 로컬 통과 결과 없음                |
| 내부 문장 누출 방지 HTTP 회귀                    | MerchantCaseFlowTests에 추가·컴파일 완료, MariaDB 실행 결과는 CI 대기       |

`.github/workflows/backend.yml`은 backend 변경 PR에서 `./gradlew check`와 `./gradlew mariaDbTest`를 실행한다. 위 표는 로컬 검증 시점의 결과다. 사용자가 후속으로 1·2번 커밋·푸시·PR 게시를 승인했으며, #690 관련 PR의 **해당 변경 커밋** 검사 결과로 CI 통과 여부를 확인한다. 워크플로 변경은 없다.

원문 열람 사유·감사 저장 실패 시 거절 등 모든 기존 보안 회귀를 이번 실제 API 시나리오로 대체하지 않는다. 해당 자동 검사와 변경된 MerchantCaseFlowTests는 CI 통과까지 미검증으로 남긴다. 실금융 처리·실제 고객 데이터·실서비스 배포는 검증하지 않았다.

## 설계 근거와 범위

서버에서 역할·객체 소유권을 검사하고 공개할 속성만 구성하는 방향은 [OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)와 [OWASP API3 속성 수준 인가](https://api-security.owasp.org/editions/2023/en/0xa3-broken-object-property-level-authorization/)를 참고한 설계다. 국내 금융권 규정 준수 여부나 실제 공격 전반 방어를 입증하는 자료로 해석하지 않는다. 계약 상세는 [ADR-0019](../decisions/0019-security-case-http-contract.md)를 따른다.
