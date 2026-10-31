# 쇼핑몰 서버 연동 안내

[Identity 목차](README.md) · [서명 상세](219-merchant-hmac.md) · [키 관리·운영 이전](../decisions/0052-merchant-key-management.md)

## 1. 사장님이 준비할 것

1. 이용 신청 → 이메일 확인 → 관리자 승인을 받는다.
2. 승인 메일의 링크에서 첫 비밀번호를 설정하고 로그인한다.
3. **시크릿 키 발급** 화면에서 비밀번호를 확인한 뒤 키를 발급한다. 가맹점 코드와 Base64 키를 쇼핑몰 **서버** 설정에 보관한다. 브라우저 코드·Git·메일·이슈·로그에 키를 넣지 않는다.
4. 아래 스크립트 또는 같은 서명을 구현한 쇼핑몰 서버로 연결 확인 요청을 보낸다.
5. 204를 받으면 돈워리 화면을 새로고침해 **연동 완료**를 확인한다. 이후 키 원문은 다시 볼 수 없다. 가맹점이 사용 중(ACTIVE)이면 신규 결제를 요청할 수 있다.

키를 재발급하면 이전 키는 즉시 폐기되고 신규 결제가 중단된다. 새 키를 서버에 반영하고 연결 확인을 다시 마쳐야 한다. 이미 접수한 결제·픽업 업무 자체를 취소하는 동작은 아니다. 관리자가 키를 폐기한 경우에도 새 발급과 연결 확인이 필요하다.

### 로컬 실행 준비

개발용 `backend/src/main/resources/application-local.yml`은 Git 제외 파일이다. `donworry.integration.local-master-keys.V1`에 32바이트 Base64 마스터 키를 **한 번만** 생성해 보관하고 `payment.http.enabled: true`를 함께 설정하면 backend에서 `./gradlew bootRun`으로 실행할 수 있다. 예제는 `application-local.yml.example`을 따른다. 마스터 키는 쇼핑몰 시크릿 키를 DB에 암복호화하는 돈워리 서버 전용 키이며, 같은 DB를 쓰는 동안 값과 ID를 유지한다. Git·문서에 실제 값을 넣지 않는다.

환경변수가 있으면 로컬 값보다 우선한다. `prod`에서는 로컬 키를 사용하지 않고 운영 환경변수만 사용한다. Gradle의 기존 제외 규칙에 따라 이 개인 설정 파일은 jar/bootJar에 포함되지 않는다. 기존 CAKESHOP 키 이전 플래그는 자동으로 켜지 않으며 이번 로컬 준비는 새 신청 가맹점 검증용이다.

## 2. 연결 확인 요청

| 항목 | 값 |
| --- | --- |
| 메서드·경로 | `POST /api/merchant-connections/check` |
| 본문 | 0바이트. `{}`·줄바꿈도 보내지 않는다 |
| 인증 | 기존 `X-Merchant-Code`, `X-Timestamp`, `X-Nonce`, `X-Signature` |
| Content-Type·Idempotency-Key | 없어도 된다. 전송하면 실제 값을 서명에 포함한다 |
| 성공 | 204, 응답 본문 없음. 결제는 생성하지 않는다 |

서명은 [HMAC 계약 1절](219-merchant-hmac.md#1-요청과-서명)의 9줄 UTF-8·HMAC-SHA256을 따른다. 키는 Base64 문자열 자체가 아니라 **디코딩한 바이트**다. 빈 본문의 SHA-256도 서명에 포함한다. HTTP 라이브러리가 Content-Type을 자동 추가한다면 최종 헤더와 서명 값이 반드시 같아야 한다. 경로는 위 원문 표기를 쓰며 POST 경로를 percent 인코딩하지 않는다.

시각은 현재 UTC Unix 초, nonce는 매 HTTP 요청마다 새 소문자 UUID v4다. 같은 서명을 재전송하면 401이다. 이미 연동 완료인 키로 새 nonce를 보내면 다시 204를 받으며 최초 연결 확인 시각은 유지된다. 응답을 받지 못했을 때도 새 nonce로 재확인할 수 있다. 이 요청은 결제를 만들지 않는다.

| HTTP·오류 | 확인할 것 |
| --- | --- |
| 401 `MERCHANT_AUTH_001` | 키 없음·폐기·옛 키·잘못된 서명·시각·nonce 재사용·가맹점 코드 |
| 503 `MERCHANT_AUTH_002` | 서버 마스터 키·암호문·DB 장애 또는 기존 키 미이전. 운영 담당 확인 |
| 403 `MERCHANT_AUTH_003` | 정지 가맹점. 연결 확인과 신규 결제 모두 불가 |
| 403 `MERCHANT_AUTH_006` | 유효한 키지만 연결 확인 전인 신규 결제 POST |
| 400 `MERCHANT_AUTH_007` | 연결 확인 본문이 비어 있지 않음 |

키가 없는 상태는 인증 자체가 불가능해 401이며 `MERCHANT_AUTH_006`과 구분한다. 계정 정지는 쇼핑몰 결제 차단 조건이 아니다. 조회·기존 픽업 PUT에는 연동 상태 차단을 추가하지 않지만, 그 요청도 현재 유효한 키로 서명해야 한다. 가맹점별 소유권·결제 업무 검사는 기존 계약을 따른다.

## 3. 가짜 쇼핑몰 스크립트

Python 3 표준 라이브러리만 필요하다. 저장소 루트에서 실행한다. Spring의 결제 검증에는 `payment.http.enabled=true`가 필요하다. 연결 확인 API는 이 설정 없이도 제공한다. 새 가맹점은 서버에 암호화 마스터 키가 준비되어 있어야 한다([ADR-0052](../decisions/0052-merchant-key-management.md)). 기존 CAKESHOP은 동일 키 이전 후 CONNECTED이므로 다시 발급하지 않는다.

```bash
python3 scripts/demo/merchant_connect.py --merchant-code YOURSHOP
```

화면에서 복사한 키를 숨김 입력 프롬프트에 붙여 넣는다. 키를 명령 인자나 셸 기록에 넣지 않는다. `--base-url http://localhost:8081`로 로컬 서버 주소를 지정할 수 있으며 원격 서버는 HTTPS만 허용한다. 리다이렉트는 따라가지 않는다.

연결 확인 후 **실제 1,000원 테스트 결제 기록**을 추가하려면:

```bash
python3 scripts/demo/merchant_connect.py --merchant-code YOURSHOP --payment
```

연결 확인 전 차단을 검사하려면 새 키를 발급한 직후 아래를 먼저 실행한다. 기대값은 403 `MERCHANT_AUTH_006`, 스크립트 종료 코드 1이다.

```bash
python3 scripts/demo/merchant_connect.py --merchant-code YOURSHOP --payment-only
```

`--payment`와 `--payment-only`는 함께 쓰지 않는다. 테스트 대상 환경에서만 결제를 만든다. 200·202는 HTTP 처리 결과이며 실제 승인·매입 성공과 같지 않다. 스크립트는 `paymentId`·`paymentStatus`를 표시한다. 202면 결제 조회로 최종 상태를 확인한다. 실제 연동 코드는 결제 응답이 불명확할 때 **같은 Idempotency-Key·같은 본문**으로 새 시각·nonce·서명을 만들어 조회·재시도해야 한다. 이 스크립트는 실행마다 새 주문과 멱등 키를 만들므로 응답 유실 후 무작정 재실행하지 않는다.

## 4. PR C 검증과 남은 확인

`feature/354-merchant-connection`, 기준 `dev 628e6b8` 위 작업 트리에서 새 신청 HTTP → 이메일 확인(메일 발신 대역으로 링크 수집) → 관리자 승인 → 첫 비밀번호 설정 → 무권한 OPERATOR 로그인 → 키 발급 → **실제 Python 프로세스의 연결 확인·결제 POST**를 검증한다. 기존 시드 가맹점으로 대체하지 않는다. FDS 평가만 REVIEW 대역이며 결제는 202·HELD로 접수됨을 확인한다. 실제 PG·CakeShop·운영 배포 검증을 뜻하지 않는다.

2026-10-31, 기준 `dev 628e6b8` 위 PR C **미커밋 작업 트리**에서 실행했다. 검사 뒤 구현·테스트 코드는 변경하지 않고 이 검증 기록만 추가했다.

- backend `./gradlew spotlessApply check mariaDbTest --console=plain`: BUILD SUCCESSFUL(3분 47초). 일반 1,173개 중 1,172개 통과·기존 1개 skip, MariaDB 603개 모두 통과, 실패/오류 0. 전체 Flyway/JPA validate·모듈 경계·계층 검사 포함.
- 신규/확장 키 관리 HTTP 집중 검사 18개 통과. 새 연결 확인 4개는 키 없음·연동 전 차단, 조회/픽업 경로의 업무 검사 도달, 본문 거절, nonce 재사용 거절·연결 시각 보존·원문 숨김, 정지 가맹점·정지 사장님 구분, nonce 소비 뒤 재발급 경합, 새 신청→승인→첫 설정→키 발급→실제 Python 스크립트의 204·결제 202/HELD를 검증한다.
- frontend `npm run lint`, `npm run format:check`, `npm test`, `npm run build`: 모두 종료 코드 0. 기존 Fast Refresh 경고 1개·번들 크기 경고 유지. 새 화면의 브라우저 검증과 구분한다.
- `python3 .github/scripts/check_md_links.py`, `git diff --check`, 스크립트 `--help`: 통과.

최초 스크립트 검사에서 Python urllib가 빈 바이트 본문에도 Content-Type을 자동 추가해 401이 발생했다. 빈 본문을 `None`으로 전송해 실제 헤더와 서명을 일치시켰고, 그 뒤 집중 검사와 위 전체 검사가 통과했다. 원격 CI·커밋 기준 결과는 아직 없다. 검사 보고서: `backend/build/reports/tests/test/index.html`, `backend/build/reports/tests/mariaDbTest/index.html`.

사장님 홈 배치와 로그인 첫 이동은 이슈의 구현 전 목업 합의가 필요해 확인 대기 중이다. 브라우저에서 새 신청부터 화면 키 발급·복사·스크립트·연동 완료 표시까지의 전체 수동 검증, 실제 메일 수신·운영 키 이전·마스터 키 백업 복원·운영 HTTPS·Notion 대조는 미검증이다.


### 개발용 로컬 마스터 키 설정 보완

2026-10-31 사용자가 개발용 Git 제외 YAML 고정 키를 승인했다. `dev 628e6b8` 위 PR C 미커밋 작업 트리에서 `MerchantKeyCipher`의 비운영 로컬 설정 조회와 prod 차단을 추가하고 ADR-0052·설정 예제·README를 갱신했다. 개인 `application-local.yml`에는 개발용 고정 키와 `payment.http.enabled=true`를 준비했으며 키 원문은 Git·이 기록에 넣지 않았다. 기존 키 환경변수가 있으면 같은 값을 보관하고 없으면 한 번만 생성한다. 다른 담당 API·DB 변경은 없다.

- `./gradlew spotlessApply check --console=plain`: BUILD SUCCESSFUL(30초). 일반 1,177개 중 1,176개 성공·기존 1개 skip. 암호화 테스트 8개 모두 통과(로컬 키 재사용·prod+local 차단·환경변수 우선·잘못된 환경변수의 fallback 금지·키 누락/형식 포함).
- `./gradlew mariaDbTest bootJar --console=plain`: BUILD SUCCESSFUL(3분 22초). MariaDB 603개 성공, 실패/오류 0. 새 bootJar의 ZIP 목록에서 `application-local.yml`이 없는 것을 확인했다.
- 별도 환경변수 설정·실행 인자 없이 `./gradlew bootRun`: 실제 로컬 DB 연결·Flyway 최신 상태·JPA validate·8081 기동 성공. `GET /api/merchant-applications/config` HTTP 200 확인 후 검증용 서버를 종료했다. 기동 시 운영 이전 플래그를 켜거나 기존 키를 이전하지 않았다.
- `git check-ignore`와 `git ls-files`로 개인 파일의 Git 제외·미추적을 확인했다. 파일 권한은 0600이다. Markdown 상대 링크·`git diff --check` 통과. 검사 뒤 변경은 문서·예제 주석뿐이다.

미검증: 실제 운영 프로세스 배포·키 주입·백업 복원, 브라우저 새 가맹점 전체 흐름. 이번 보완은 백엔드 설정만 바꿔 프론트 검사를 반복하지 않았다. 사장님 홈의 목업 합의 대기 상태는 유지한다.


### 사용자 로컬 확인과 PR 범위

2026-10-31 사용자가 같은 PR C 미커밋 작업 트리의 로컬 환경에서 직접 확인하고 대화에 결과를 공유했다. 가맹점 `BABO1234`의 연결 전 신규 결제는 403 `MERCHANT_AUTH_006`이었고, 연결 확인 후 화면의 연동 완료·다시 보기 비활성을 확인했다고 보고했다. 재발급한 새 키의 신규 결제도 403 `MERCHANT_AUTH_006`으로 차단됐고, 새 키로 재연결한 스크립트는 연결 확인 204와 결제 202, `paymentId: 2`, `paymentStatus: PENDING`을 반환했다. 따라서 재발급→차단→재연결→결제 재접수까지 사용자 확인 근거가 있다. 해당 결제의 최종 승인·매입 완료는 확인하지 않았다. 키 원문은 전달받거나 기록하지 않았다.

사용자는 현재 구현을 PR로 올리는 것까지만 요청했고 원격 CI 확인은 직접 진행한다. 이 PR에는 연결 확인·신규 결제 차단·상태 안내·개발 문서/스크립트·개발용 로컬 키 설정을 포함한다. 사장님 홈(`/owner`)·로그인 첫 이동은 미구현으로 남겨 #354를 자동 종료하지 않는다. 최초 신청·실제 메일·승인부터 브라우저 전체 흐름을 완료했다는 추가 근거로 확대하지 않는다.
