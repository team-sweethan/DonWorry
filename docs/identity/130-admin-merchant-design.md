# #130 관리자 가맹점 관리 설계

상태: 2026-10-25 사용자 승인 설계를 구현한다. 기준 dev `078a5c8`. 실행 결과와 미검증은 [identity README](README.md)의 #130 기록을 따른다.

## 범위와 기존 기능

- P1 identity와 frontend만 변경한다. 기존 merchants·admin_change_histories를 사용하며 DB 스키마·migration·공개 Java 계약·allowedDependencies는 변경하지 않는다.
- #124 TenantReader, #125 세션 종료, #128 계정 잠금, #129 관리자 제한·이력·계정 생성 화면, #126 공통 CSRF 클라이언트를 재사용한다.
- 가맹점을 삭제하지 않는다. 코드·이름·수수료율 수정, webhook_url·secret_reference 편집, 정산 계산 연동·전체 업무 인가·CSRF 실제 활성화는 범위 밖이다.

## HTTP 계약

모든 경로는 ADMIN만 허용한다. 비로그인 401 AUTH_001, 비관리자 403 AUTH_002, 강제 비밀번호 변경 중 403 AUTH_008이다.

| 요청 | 계약 |
| --- | --- |
| GET /api/admin/merchants | page 기본 0·size 기본 20(1~100), keyword 코드/이름 부분 검색(최대 100자), status 선택 ACTIVE/INACTIVE, merchantId 내림차순 |
| GET /api/admin/merchants/{id} | 가맹점 상세 |
| POST /api/admin/merchants | merchantCode·displayName·pgFeeRate → 201 상세. 서버가 ID·ACTIVE·UTC createdAt 생성 |
| PUT /api/admin/merchants/{id}/status | status ACTIVE/INACTIVE → 204. 같은 값은 이력·세션 종료 생략 |
| GET /api/admin/merchants/options | 기존 #129 ACTIVE 가맹점 선택 계약 유지 |

응답 필드: merchantId·merchantCode·displayName·status·pgFeeRate·createdAt. 목록은 items·page·size·totalElements다. 웹훅·비밀키 필드는 내보내지 않는다.

- 코드: `[A-Z0-9]{3,40}`, 자동 대문자 변환 없이 검증. DB `uk_merchants_merchant_code`도 최종 방어로 사용한다.
- 이름: 공백 이름 거절·100자 이내, 저장 전 trim.
- 수수료율: BigDecimal, `0 <= rate < 1`, 소수 6자리 이내. 기본값은 화면에서 1%로 제공하고 API는 값을 필수로 받는다. 반올림으로 입력 범위를 바꾸지 않는다.
- 화면은 % 단위로 0~100 미만·소수 4자리까지 입력한다. 1%는 비율 `0.010000`, 0.0001%는 `0.000001`로 정수 기반 문자열 변환해 전송한다.
- 입력 오류 400 ADMIN_007, 없는 가맹점 404 ADMIN_008, 코드 중복 409 ADMIN_009. 다른 DB·트랜잭션 장애는 500 AUTH_007이다. 기존 ADMIN_001~006 의미는 유지한다.
- 없는/정지 가맹점·연결 누락 사장님 로그인은 DISABLED 계정과 동일한 401 AUTH_004·동일 문구다. 로그인 가맹점 조회 장애는 기존 로그인 장애 500 AUTH_005다.

## 순서 보장과 상태 복구

- MerchantLockService는 고정 256개 모니터를 사용한다. 잠금 객체를 ID마다 무제한 생성하지 않는다. null 가맹점은 잠금이 필요 없는 관리자·직원 경로다.
- 잠금 순서는 가맹점 → 기존 계정(분할 인덱스 순서) → UserSessionService의 짧은 모니터다. 기존 계정 변경·본인 비밀번호 변경은 가맹점 잠금을 뒤늦게 얻지 않는다.
- 로그인 첫 DB 조회는 실제 계정 loginId·가맹점 잠금 키만 정한다. 잠금 안에서 인증 DB 재조회 → OPERATOR 연결 가맹점 ACTIVE 확인 → 권한 조회 → 세션 등록을 끝낸다.
- 첫 조회에 없던 계정이 뒤늦게 생성되어 인증됐어도, 확보한 가맹점 잠금 키와 실제 사장님 가맹점이 다르면 AUTH_004로 거절한다. 잠금 순서를 뒤집어 재획득하지 않는다. 재시도는 정상 잠금 경로를 사용한다.
- 정지: 가맹점·수행자 계정 잠금 → 수행자 세션 유효성 → REQUIRES_NEW에서 수행자 DB 상태/역할/강제 변경 확인·대상 상태 변경·이력 INSERT·소속 OPERATOR ID 조회 → 실제 커밋 → expireAll(userId) → 잠금 해제.
- #129 OPERATOR 생성도 해당 가맹점 잠금 안에서 ACTIVE 확인·계정 생성·이력 커밋을 완료한다. 생성이 먼저면 정지 조회 대상에 포함되고 정지가 먼저면 ADMIN_005로 생성이 거절된다.
- 로그인 먼저면 정지가 새 세션까지 만료시키고, 정지가 먼저면 새 로그인은 차단한다. 만료된 세션은 다음 요청부터 401 AUTH_001이다. 이미 실행 중인 업무 요청을 취소하지 않는다.
- 가맹점 상태는 계정 status·권한을 바꾸지 않는다. 재활성화 후 ACTIVE 계정은 새 로그인 가능, 별도 DISABLED 계정은 계속 차단, 옛 세션은 복구하지 않는다.
- 로그인은 BCrypt 검증 동안에도 가맹점 잠금을 유지하므로 같은 가맹점(및 같은 잠금 칸) 사장님 로그인은 직렬화된다. 한계와 받아들인 이유는 ADR-0028 "영향과 검증"에 둔다.
- MerchantRepository는 삭제 메서드를 노출하지 않도록 `Repository`에 필요한 메서드만 선언한다.
- 세션과 잠금은 현재 단일 서버 범위다. 다중 서버 지원을 의미하지 않는다.

## 이력과 DB

- 기존 Merchant 조회 매핑에 merchant_code·pg_fee_rate·created_at·자동 ID·생성/상태 변경을 보완한다. webhook_url·secret_reference는 기존 NULL 허용 컬럼을 그대로 두고 매핑·편집하지 않는다.
- MERCHANT_CREATED·MERCHANT_DISABLED·MERCHANT_ENABLED를 기존 이력 테이블에 INSERT한다. target_merchant_id만 지정하고 target_user_id는 NULL이다.
- 생성 이력에는 코드·이름·상태·수수료율, 상태 변경에는 변경 전후 status를 기록한다. 비밀값은 제외한다.
- 업무와 이력은 같은 트랜잭션이다. 이력 INSERT·DB 커밋 실패 시 생성·상태 변경을 롤백하고 세션 종료를 호출하지 않는다.
- P6 수수료율 공유는 예정이다. 정산 고정값 0.01·시드 임시값 0.020000은 유지한다. TenantReader 수수료율 반환·settlement 의존은 담당 합의와 후속 ADR 대상이다.

## 화면과 검증

- /admin/merchants 목록·검색·필터·서버 페이지 이동, /new 생성, /:id 상세·정지·재활성화.
- 정지 확인에 소속 사장님 세션 종료를 안내한다. API 실패 시 오류·재시도를 제공하고 중복 제출을 막는다. 계정 생성의 ACTIVE 선택과 가맹점 생성 링크를 연결한다.
- 단위: 커밋 후 세션 종료·커밋 실패·수행자 만료/정지·같은 상태 재요청.
- 실제 MariaDB/HTTP: ADMIN 401·403·강제 변경, 입력/정밀도·중복·목록/상세·상태 복구·세션/타 가맹점 보호·이력 롤백·TenantReader, latch로 로그인↔정지·계정 생성↔정지.
- 프론트: % 변환 경계·검색 인코딩·요청 본문·CSRF 헤더·네트워크 실패 재전송 없음·401 만료 처리. 브라우저 실제 폼·확인 모달·화면 연결은 별도 기록한다.
