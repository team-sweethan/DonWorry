# #354 PR A — 사장님 계정 추가 생성 제한과 쇼핑몰 주소 수집

관련: [Issue #354](https://github.com/team-sweethan/DonWorry/issues/354), [ADR-0049](../decisions/0049-application-shop-url.md).

## 범위와 결정

2026-10-30 P1 사용자가 설계와 구현 진행을 승인했다. 최초 제안의 쇼핑몰 종류는 분류와 검토 목적이 모호하여 제외하고 주소만 받는다. PR B의 HMAC 키 저장·개발 연동 화면, PR C의 연결 확인·신규 결제 차단·사장님 홈은 포함하지 않는다. 기준 dev `fa498c2`다.

## 계정 생성

승인과 관리자 직접 생성이 공유하는 `AccountProvisioningService`에서 OPERATOR 생성 시 가맹점 행을 `PESSIMISTIC_WRITE`로 잠근다. ACTIVE 확인 후 해당 가맹점 OPERATOR의 존재 여부를 조회하고 계정·이력을 저장한다. 관리자 직접 생성 트랜잭션은 READ_COMMITTED로 맞추고 승인 경로는 기존 READ_COMMITTED를 유지한다. 잠금 대기 전에 다른 쿼리를 했어도 잠금 후 커밋된 최신 계정을 읽는다.

기존 MerchantLockService → AccountLockService 순서를 유지한다. 공통 생성 서비스 안에 메모리 잠금을 추가하지 않는다. DB 잠금은 같은 트랜잭션의 커밋·롤백까지 유지하므로 다른 JVM의 생성 요청도 직렬화된다. 이 보장은 서비스 경계를 지나는 요청에 적용되며 임의 SQL INSERT까지 금지하는 DB UNIQUE 보장은 아니다.

이미 OPERATOR가 있으면 409 `ADMIN_010` (`ADMIN_OPERATOR_ALREADY_EXISTS`)이다. 정지·첫 설정 전 계정도 포함한다. 기존 복수 계정은 삭제·수정하지 않고 추가 생성만 거절한다. 기존 loginId 중복 오류가 먼저 적용되며 SECURITY_STAFF 생성은 기존 정책을 유지한다. 관리자 생성 화면은 제한을 안내하고 409에서 입력을 유지하며 소속 가맹점 오류를 표시한다.

## 주소 계약

- 기존 공개 신청 POST에 `shopUrl` 추가: 신규 신청 필수, trim 후 최대 2048자, 호스트가 있는 HTTP(S) 절대 URI, user-info 불가. 국제화 도메인은 검증할 때만 IDN.toASCII로 변환하며 입력 원문을 저장·표시한다. 프론트도 변환된 호스트의 각 구간에 영숫자·구간 내부 하이픈만 허용하고 밑줄·빈 구간·앞뒤 하이픈을 거절한다. IPv6는 URL 파서 검증을 사용한다. 서버가 주소에 접속하지 않는다.
- 주소의 경로·쿼리 대소문자를 바꾸지 않는다. 신청 검토 자료로만 사용하고 연동 주소·webhook_url로 복사하지 않는다.
- `merchant_applications.shop_url VARCHAR(2048) NULL` 하나를 새 migration으로 추가한다. P1 소유이며 다른 담당 테이블·FK 변경이 없다.
- 관리자 상세 GET에 nullable `shopUrl`을 추가한다. 목록에는 추가하지 않는다. HTTP(S) 검증을 통과하는 주소만 새 탭 링크로 보여주며 `noopener noreferrer`를 사용한다.
- 기존 신청은 NULL로 보존하고 “기존 신청으로 수집하지 않은 정보입니다.”로 표시한다. 주소 누락만으로 기존 승인을 막지 않는다. 기존 requestedLoginId 누락 승인 제한은 그대로다.

## 화면·동의·보존

신청 가맹점 정보 단계에 쇼핑몰 주소를 필수로 받고 제출 전 확인에 다시 보여준다. “현재 운영 중인 쇼핑몰 주소를 입력해 주세요. 이용 신청 검토에 사용합니다.”를 안내한다. 개인정보 항목에 주소, 목적에 쇼핑몰 운영 여부 확인을 추가한다. 동의 버전은 `2026-10-30.1`이며 이전 동의 기록은 덮어쓰지 않는다. 구 버전 양식을 열어 둔 사용자는 새로고침 후 새 안내를 확인하고 신청해야 한다.

보존은 신청 행과 동일하다. 미확인 7일·반려 30일이 지나면 기존 자식 우선 정리와 함께 삭제하고 검토 대기·승인은 보존한다. 승인 신청은 가맹점 이용 기간 보관하되 INACTIVE를 이용 종료로 취급하지 않는다. 이용 종료·백업 폐기 기준은 기존 미정 사항이며 이번에 정하지 않는다. 원문 주소를 메일·감사 이력에 새로 복사하지 않는다.

## 검증 계획과 상태

실행 결과는 최종 PR과 이 문서의 후속 기록에 남긴다. 계획을 통과 결과로 취급하지 않는다.

- MariaDB: 신규 신청 주소 저장·정규화, 누락·스킴·상대주소·user-info·길이 검증, 관리자 상세 nullable 계약, 기존 신청 승인, webhook 미전파, 보존 정리.
- 계정: 첫 설정 전·정지·기존 복수 계정의 추가 생성 거절, 기존 계정 보존, JVM 잠금을 거치지 않는 별도 트랜잭션 동시 생성 1건 성공, 승인 원자성·중복 승인 회귀.
- Frontend: 신청 payload 주소·새 동의 버전, URL 검증·안전 링크 조건, 기존 API·상태 스냅샷 회귀.
- 전체 명령: backend `./gradlew spotlessApply check mariaDbTest`, frontend `npm run lint`, `npm run format:check`, `npm test`, `npm run build`, 문서 링크 검사와 `git diff --check`.

Notion 최신 대조·동기화와 운영 배포는 미수행이다. 이번 합의는 저장소 설계·구현 진행 승인이고 팀 전체 ADR 채택·운영 적용을 뜻하지 않는다.

## 최종 실행 기록 (2026-10-30)

`feature/354-application-shop-url`, 기준 `fa498c2` 위 최종 구현 작업 트리에서 실행했다. 아래 검사 이후 애플리케이션·테스트 코드는 변경하지 않고 검증 문서만 보완했다. PR의 구현 커밋과 함께 확인한다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest --console=plain`: BUILD SUCCESSFUL (3분 33초). 일반 712개 중 711 통과·기존 1개 skip, MariaDB 558개 모두 통과, 실패·오류 0. XML 보고서와 종료 코드를 대조했다. 새 migration 포함 전체 Flyway·JPA validate·모듈/계층 검사 포함.
- `cd frontend && npm run lint && npm run format:check && npm test && npm run build`: 모두 종료 코드 0, 테스트 235개 통과. 기존 router Fast Refresh lint 경고 1개와 번들 크기 경고 유지.
- 신규 MariaDB 검증: 계정 존재·첫 설정 전·정지·기존 복수 계정의 추가 생성 거절, 메모리 잠금 없이 두 별도 트랜잭션의 다른 로그인 아이디 동시 생성에서 한 건만 성공, 주소 누락·길이·스킴·상대주소·사용자 정보 거절, 관리자 상세의 NULL/주소·승인·webhook 미전파. 기존 저장·승인·삭제·메일 검사를 새 필드와 함께 회귀 실행했다.
- 최초 프론트 테스트에서 브라우저 URL 파서가 `https:///shop`을 정상화하는 경로를 발견하여 원문 authority 검사를 추가했다. 보완 후 전체 235개가 통과했다.
- Chrome: 로컬 Vite 15354·Spring 8081에서 주소 입력란·수집 목적 표시, 잘못된 주소 거절과 나머지 입력 유지, 공백을 제거한 경로·쿼리 원문을 제출 전 확인에서 표시함을 확인했다. 실제 신청 제출은 하지 않았다.
- 로컬 MariaDB 11.8의 기존 DB에 새 nullable migration을 적용하고 기동 성공을 확인했다. 예약 정리·FDS mock seed는 끈 상태다. 기존 migration은 수정하지 않았다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.

미검증: 브라우저 실제 접수부터 관리자 상세·승인까지의 전체 흐름, 모바일·키보드 전체 검증, Gmail 실제 수신, Notion 최신 대조·동기화, 운영 배포, 원격 CI. 주소 저장·관리자 상세·승인·메일은 MariaDB HTTP/SMTP 테스트 결과로만 검증하며 브라우저 완료로 표현하지 않는다. 승인 신청의 이용 종료·백업 폐기 기준은 기존 미정 사항이다. 다른 담당 Java API·테이블 계약 변경은 없다.

## 리뷰 후 국제화 도메인 검증 보완 (2026-10-30)

[리뷰 지적](https://github.com/team-sweethan/DonWorry/pull/358#discussion_r4419766286)을 `bf4b712` 위 회귀 테스트로 재현했다. 브라우저에서는 허용하던 한글 도메인 2건이 서버 URI.getHost()에서 거절되어 신규 단위 테스트 13건 중 2건이 실패했다.

서버는 user-info를 원문 authority에서 먼저 거절하고, URI.getHost()가 해석하지 못하는 국제화 호스트를 검증할 때만 IDN.toASCII(USE_STD3_ASCII_RULES)로 변환해 URI 호스트를 검사한다. 포트는 호스트와 분리하여 보존한다. 입력·DB 저장·표시·전송의 주소 원문과 경로·쿼리는 바꾸지 않는다. 스키마와 계정 생성 정책 변경은 없다.

`bf4b712` 위 최종 보완 작업 트리에서 다음을 실행했고 이후 구현·테스트는 변경하지 않고 이 기록만 보완했다.

- backend `./gradlew spotlessApply check mariaDbTest --console=plain`: BUILD SUCCESSFUL (3분 34초). 일반 725개 중 724 통과·기존 1개 skip, MariaDB 559개 모두 통과, 실패·오류 0. 단위 테스트 13건과 실제 한글 도메인 신청 POST·DB 원문 보존, 한글 호스트에 user-info가 있는 요청 거절을 포함한다.
- frontend `npm run lint`, `npm run format:check`, `npm test`, `npm run build`: 통과, 테스트 236개. 국제화 도메인·포트·경로 입력 허용, user-info 거절과 신청 payload 원문 보존을 검사했다. 기존 lint·번들 경고 유지.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.

미검증: 이번 보완 뒤 브라우저 재실행, 운영 배포, Notion 최신 대조·동기화, 보완 커밋의 원격 CI. 앞의 브라우저 전체 접수→승인·모바일·키보드·Gmail 미검증 범위도 유지한다. 다른 담당 계약 변경이나 새 사람 결정 사항은 없다.

## 추가 리뷰 후 호스트 문법 보완 (2026-10-30)

[추가 리뷰](https://github.com/team-sweethan/DonWorry/pull/358#discussion_r4419910065)의 `https://exa_mple.com` 프론트 허용·서버 거절을 `377535a` 위 회귀 테스트로 재현했다. 프론트에서 브라우저 파싱 후 호스트 구간 문법을 추가 검사해 밑줄·빈 구간·앞뒤 하이픈을 제출 전 거절한다. 원문 authority를 경로·쿼리·fragment와 분리해 user-info·호스트 percent escape를 거절하며, 쿼리에만 있는 @는 user-info로 오인하지 않는다. 한글 도메인·하이픈이 있는 정상 호스트·IPv6·포트·끝의 루트 점은 계속 허용한다. 서버 구현·DB 스키마·계정 생성 정책은 바꾸지 않았다.

`377535a` 위 최종 보완 작업 트리에서 실행했고 이후 구현·테스트를 바꾸지 않고 이 기록만 보완했다.

- frontend `npm run lint`, `npm run format:check`, `npm test`, `npm run build`: 통과, 테스트 237개. 기존 lint·번들 경고 유지.
- backend `./gradlew spotlessApply check mariaDbTest --tests com.donworry.identity.security.MerchantApplicationHttpTests --console=plain`: BUILD SUCCESSFUL (41초). 일반 733개 중 732 통과·기존 1개 skip, 신청 HTTP MariaDB 20개 모두 통과, 실패·오류 0. 추가 호스트 문법 단위 검사와 실제 밑줄·앞 하이픈 호스트 POST 거절 포함.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`: 통과.

미검증: 이번 보완 뒤 브라우저 재실행·전체 MariaDB 재실행·새 커밋의 원격 CI. 서버 구현 변경이 없어 이번에는 신청 HTTP 범위로 MariaDB 회귀 검사를 실행했다. 이전 377535a의 전체 MariaDB 559개 통과와 구분한다. 기존 운영·Notion·Gmail·브라우저 전체 흐름 미검증 범위도 유지한다. 다른 담당 계약 변경·새 사람 결정 사항은 없다.
