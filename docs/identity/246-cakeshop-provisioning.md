# #246 CakeShop 가맹점과 HMAC 키 준비

[Identity 목차](README.md) · [HMAC 계약](219-merchant-hmac.md) · [이슈 결정](https://github.com/team-sweethan/DonWorry/issues/246#issuecomment-6442207054)

## #354 PR B 이후 키 이전

현재 키 저장은 [ADR-0052 배포 절차](../decisions/0052-merchant-key-management.md#기존-키-이전과-배포)를 따른다. **시연 이후** 기존 CAKESHOP 환경변수 값을 그대로 유지한 상태에서 마스터 키를 준비하고 `donworry.integration.legacy-import-enabled=true`로 기동해 DB로 암호화 이전한다. 같은 키로 서명 인증·CONNECTED·MIGRATE 이력을 확인한 다음 이전 플래그와 DonWorry의 레거시 환경변수를 제거한다. CakeShop 서버의 키 값은 변경하지 않는다. 아래 신규 키 생성 명령을 기존 환경의 키 이전에 실행하지 않는다.

미이전 레거시 가맹점은 503이며, 관리자 폐기 이후 환경변수 키로 되돌아가지 않는다. DB와 해당 마스터 키를 별도로 백업·함께 복원한다. 실제 키 이전·배포 완료를 뜻하지 않는다. 아래는 기존 #246 등록·검증 이력이다.

PR C의 연결 확인·결제 게이트는 [개발자 안내](354-merchant-integration.md)를 따른다. 동일 키 이전으로 이미 CONNECTED인 CAKESHOP은 재확인 없이 인증된다. 재발급했다면 새 키로 연결 확인해야 신규 결제가 재개된다. CakeShop 실제 발신 코드 변경(#242)·운영 이전은 별도다.

## 등록 값과 적용

새 [Flyway 시드](../../backend/src/main/resources/db/migration/V20261027_221311__seed_cakeshop_merchant_and_owner.sql)가 앱 기동 때 다음을 함께 등록한다. 기존 `DEMOSHOP`과 계정은 보존하며 ID는 자동 생성하므로 숫자로 가정하지 않는다.

| 항목 | 값 |
| --- | --- |
| 가맹점 | `CAKESHOP` / `CakeShop` / `ACTIVE` |
| 수수료율 | `0.020000` (2%, **P6 확인 전 임시값**; 현재 정산 계산 적용 여부는 별도 P6 계약) |
| 키 참조 | `DONWORRY_MERCHANT_HMAC_CAKESHOP` |
| 사장님 | `cakeshop_owner` / `CakeShop 사장님` / `ACTIVE` / `OPERATOR` |
| 사장님 권한 | `FDS_VIEW`, `FINANCE_VIEW` |
| 시드 비밀번호 | 기존 시연 계정과 같은 `1234`의 BCrypt 해시 |

스키마·Entity·API 변경은 없다. 기존 컬럼에 P1 소유 데이터를 추가한다. 같은 환경에서 Flyway를 다시 실행해도 시드는 재실행되지 않는다.
수동으로 같은 코드·로그인 ID를 먼저 만들었다면 UNIQUE 오류가 발생하므로 덮어쓰거나 기존 migration을 수정하지 말고 충돌 데이터를 확인한다.
키가 없는 환경에서도 앱은 기동하며, 해당 가맹점의 HMAC 요청은 기존 계약대로 `503 MERCHANT_AUTH_002`다.
시연 계정은 기존 #131 정책을 따른다. 현재 운영 프로필도 이 계정을 자동 정지하지 않으며 운영 계정 제한은 #131 후속이다.

## 환경별 키 생성·전달

환경변수 이름은 로컬·시연 모두 `DONWORRY_MERCHANT_HMAC_CAKESHOP`으로 같고 **값은 환경마다 독립적으로 생성**한다. 다른 가맹점 키도 재사용하지 않는다.
DonWorry는 `System.getenv`로만 읽으므로 YAML·`-D`·Spring property에 넣어도 키로 읽히지 않는다.

로컬 macOS/Linux에서는 아래 명령을 **사용자 터미널에서** 실행한다. 32바이트 무작위 키를 표준 Base64로 인코딩해 현재 셸에 넣으며 원문을 출력하지 않는다.

```bash
export DONWORRY_MERCHANT_HMAC_CAKESHOP="$(python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')"
cd backend
./gradlew --no-daemon bootRun --args='--payment.http.enabled=true --fds.mock.seed-enabled=false'
```

같은 셸에서 실행한 두 서버는 같은 값을 상속할 수 있다. CakeShop을 다른 터미널·호스트에서 실행한다면 해당 환경의 서버 담당자끼리 접근 제한된 비밀 전달 수단으로 같은 값을 전달하고, CakeShop의 서버 전용 키 설정에 주입한다. 발신 `merchant_code`는 `CAKESHOP`이다. CakeShop의 실제 설정 이름·서명 코드 연결은 #242에서 확인한다.

시연은 배포 담당자가 별도 키를 생성해 DonWorry 서비스와 CakeShop 서비스의 배포 비밀 설정에 같은 값을 주입하고 두 프로세스를 다시 시작한다. 로컬 키를 시연으로 복사하지 않는다. 실제 배포 설정·HTTPS 확인은 #175 범위다.
키 원문을 저장소·이슈·PR·채팅·로그·화면·브라우저 번들에 넣지 않는다. 셸 추적(`set -x`)도 끈다. 전달·설정 확인 시에는 변수 이름과 설정 유무만 공유한다.
서명은 요청마다 새 timestamp·nonce로 만들고 같은 결제를 재시도할 때 업무 본문과 `Idempotency-Key`는 유지한다.

## 로컬 실제 서명 확인

위 서버와 **같은 키가 설정된 셸**에서 아래 Python 표준 라이브러리 예시를 실행한다. 없는 결제 조회를 통해 상태를 변경하지 않고 P5 업무 코드까지 도달하는지 확인한다. 기본 비활성인 `payment.http.enabled`는 위 명령에서 해당 실행에만 켠다.

```python
import base64
import hashlib
import hmac
import os
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPRedirectHandler

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

key = base64.b64decode(os.environ['DONWORRY_MERCHANT_HMAC_CAKESHOP'], validate=True)
assert len(key) >= 32
target = '/api/payments/9223372036854775807'
timestamp, nonce = str(int(time.time())), str(uuid.uuid4())
canonical = '\n'.join([
    'DONWORRY-HMAC-V1', 'CAKESHOP', timestamp, nonce, 'GET', target,
    '', '', hashlib.sha256(b'').hexdigest(),
])
request = Request('http://localhost:8081' + target, headers={
    'X-Merchant-Code': 'CAKESHOP', 'X-Timestamp': timestamp, 'X-Nonce': nonce,
    'X-Signature': hmac.new(key, canonical.encode(), hashlib.sha256).hexdigest(),
})
try:
    response = build_opener(NoRedirect()).open(request, timeout=10)
except HTTPError as error:
    response = error
print(response.status, response.read().decode())
```

예상 결과는 `404 PAYMENT_NOT_FOUND`(결제 없음)이다. `401`이면 서명·시각·가맹점 코드를, `503 MERCHANT_AUTH_002`면 양쪽 서버의 키 참조·설정·Base64 형식을 확인한다. 코드 없는 단순 `404`는 Controller 비활성일 수 있어 업무 도달 증거로 쓰지 않는다. 재실행은 새 nonce를 생성한다. 키·서명을 출력하지 않는다.

## 사장님 로그인·정산 화면 확인

프론트 로컬 서버에서 `cakeshop_owner` / `1234`로 로그인한다. `/api/auth/me`의 `OPERATOR`, 실제 CakeShop `merchantId`, 두 권한을 확인한 뒤 메뉴의 **정산 현황**(`/owner/settlements`)에 진입한다.
현재 dev의 이 경로는 빈 화면(`EmptyPage`)이다. 화면 진입이 P6 업무 API·정산 데이터·타 가맹점 격리 검증을 의미하지 않는다.
`/api/v1/settlements` 세션 공통 인가는 [#240 연결 계약](240-owner-finance-session.md)을 따른다. `cakeshop_owner`는 그 조건(`OPERATOR`·`FINANCE_VIEW`·`merchantId`)을 갖춘 시드 계정이며, P6 실제 업무 연결은 후속이다.

## 검증 기록

검사 명령·검토 대상 커밋·실제 응답과 미검증 항목은 이 작업의 PR에 기록한다. 시연 환경 적용·CakeShop 실제 발신은 #175·#242에서 별도 확인한다.
