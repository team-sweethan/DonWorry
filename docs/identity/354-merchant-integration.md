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

## 4. 남은 확인

PR C 구현 범위의 검사 명령과 결과는 PR #377 본문에 기록한다. 이 문서에는 작업 기록을 쌓지 않는다.

- 메일 발신·FDS 평가는 테스트 대역이다. 실제 메일 수신부터 브라우저로 끝까지 진행하는 흐름, 실제 PG·CakeShop 발신 코드는 미검증이다.
- 운영 배포·운영 키 주입·기존 키 운영 이전·마스터 키 백업 복원·운영 HTTPS는 미검증이다.
- 사장님 홈(`/owner`)과 로그인 첫 이동은 이슈의 목업 합의 후 구현한다. #354는 이 범위가 끝날 때 닫는다.
