# #219 CakeShop HMAC 인증

상태: P1 공통 인증·P5 경로 및 nonce DB 저장 구현. #241에서 P6 픽업 두 PUT 경로의 공통 인증을 확장했다. P6 업무 Controller·어댑터 연결(#227)과 CakeShop 실제 호출은 후속이다. 검증 결과는 6절을 따른다.
기준: #219 최초 구현은 dev `d9fd751`, `feature/219-cakeshop-hmac`. #241 확장은 dev `ae93f90`, `feature/241-pickup-hmac`. 외부 계약의 이유와 대안은 [ADR-0037](../decisions/0037-merchant-hmac-authentication.md)을 따른다.

## 1. 요청과 서명

HMAC-SHA256을 사용한다. 키 원문을 헤더로 보내지 않는다. 모든 운영 호출은 HTTPS가 필요하며 HMAC은 요청 내용을 암호화하지 않는다.

| 헤더 | 규칙 |
| --- | --- |
| `X-Merchant-Code` | 등록된 코드와 정확히 일치하는 대문자·숫자 3~40자 |
| `X-Timestamp` | UTC Unix 시각, 초 단위 10자리 숫자. 서버 현재 초 기준 ±300초 포함 |
| `X-Nonce` | 소문자 표준 UUID v4. HTTP 전송마다 새 값 |
| `X-Signature` | 아래 서명의 소문자 hex 64자리 |
| `Content-Type` | 실제 전송하는 값 그대로 서명. 없으면 빈 문자열 |
| `Idempotency-Key` | 실제 값 그대로 서명. 없으면 빈 문자열. 결제 POST의 필수값·UUID 검사는 P5 계약 유지 |

위 헤더는 중복을 허용하지 않는다. 본문은 최대 65,536바이트다. 초과는 413, 형식이 모호한 인증 헤더는 401이다.

서명 대상은 다음 9줄을 UTF-8로 인코딩한 바이트다. 구분자는 LF 한 개이며 마지막 줄 뒤 개행은 없다.

```text
DONWORRY-HMAC-V1
<X-Merchant-Code>
<X-Timestamp>
<X-Nonce>
<대문자 HTTP 메서드>
<원본 경로 + 있을 경우 ?원본 쿼리 문자열>
<Content-Type, 없으면 빈 줄>
<Idempotency-Key, 없으면 빈 줄>
<실제 본문 바이트의 SHA-256 소문자 hex>
```

- JSON을 파싱·정렬·재직렬화하지 않는다. 공백·문자 인코딩까지 실제 전송 바이트와 같아야 한다.
- 쿼리는 정렬·디코딩하지 않는다. `%20`과 `+`, 파라미터 순서도 서명에 포함된다. 프록시는 경로·쿼리를 재작성하지 않아야 한다.
- `Content-Type`의 charset 추가·변경도 서명을 바꾼다. 클라이언트가 최종 전송 헤더를 확정한 뒤 서명한다.
- 본문이 없는 GET은 빈 바이트 배열의 SHA-256이다. 서명 비교는 `MessageDigest.isEqual`을 쓴다.
- 이 형식은 DonWorry v1 계약이다. RFC 9421의 `Signature-Input` wire format을 구현한 것은 아니다.

## 2. 재전송과 결제 멱등성

nonce는 가맹점별로 한 번만 소비한다. 저장은 서명 검증 이후, 업무 실행 이전에 독립 커밋해야 한다. 결제 처리 실패·롤백으로 nonce를 복구하지 않는다.
서명 시각+301초까지 보관해 미래 시각 허용과 정수 초의 마지막 유효 시점까지 보호한다. 만료 전 기록을 밀어내는 캐시는 사용하지 않는다.
DB 기록 저장이 실패하면 503으로 차단하며 업무 코드를 실행하지 않는다.

응답 유실·503 후 재시도할 때 CakeShop은 **같은 결제 Idempotency-Key·같은 업무 본문**을 유지하고, 시각·nonce·서명을 새로 만든다.
인증 재전송 차단은 결제 중복 방지와 별개다. P5의 가맹점별 멱등 키·요청 내용 일치·주문 직렬화가 최종 중복 결제를 막는다.

사용자가 nonce 신규 테이블과 저장소 ERD·ADR 선행 반영을 승인했다. [ERD](../erd/README.md)의 #219 계약을 새 migration 하나로 구현한다.
같은 가맹점 행을 잠근 짧은 DB 트랜잭션 안에서 중복 확인·INSERT하며 UNIQUE도 유지한다. 중복 요청을 SQL 오류로 만들지 않아 MariaDB 드라이버가 nonce 값을 오류 로그에 노출하는 것을 막는다. 잠금 대기 후에도 만료를 재확인한다.
매분 만료 행을 1,000개씩 독립 삭제한다. 실행은 최대 5초 동안 다음 배치를 반복하고 실패 시 다음 실행에서 재시도한다. 유효한 기록을 먼저 삭제하지 않는다.

## 3. 적용 경로와 공개 경계

| 메서드 | 경로 |
| --- | --- |
| POST | `/api/payments` |
| GET | `/api/payments/{paymentId}` |
| GET | `/api/payment-orders` |
| PUT | `/api/v1/order-fulfillments/{orderId}/schedule` |
| PUT | `/api/v1/order-fulfillments/{orderId}/completion` |

전용 보안 체인이 해당 경로를 모든 메서드로 먼저 선택한다. 경로별 지원하지 않는 메서드는 405이며 `Allow`는 해당 경로의 POST·GET·PUT을 반환한다.
정해진 메서드만 HMAC 인증을 허용하고 CSRF 예외로 둔다. 직원 체인의 전체 CSRF 설정·다른 업무 경로·`/internal/ai/**`는 바꾸지 않는다.
직원 세션은 불러오거나 저장하지 않는다. 서명에 성공하면 현재 요청의 SecurityContext에 가맹점 신원만 넣는다.

```java
// identity.api — 직원 SessionUser·Entity·Repository에 의존하지 않는다.
public interface CurrentMerchant {
    Optional<MerchantIdentity> findCurrent();
}
public record MerchantIdentity(Long merchantId) {}
```

인증 없거나 직원 세션이면 empty다. P5의 기존 `PaymentMerchantIdentity`는 공개 `CurrentMerchant`를 읽는 작은 어댑터로 연결한다.
가맹점 번호를 body·query·임의 헤더에서 가져오는 fallback은 없다. 다른 가맹점 결제·주문을 숨기는 404 조건은 P5가 계속 검사한다.
P6가 확인한 계약(#241, 2026-10-27): 본문은 schedule의 `scheduledPickupAt`, completion의 `pickedUpAt`이다. `merchantId`는 본문으로 받지 않는다.
P6는 자기 모듈 어댑터에서 아래처럼 공개 경계를 사용한다. `settlement → identity :: api`는 ADR-0038·PR #245로 이미 허용됐다.

```java
// settlement 내부 어댑터 예시. empty면 P6 인증 실패로 차단하고 다른 신원으로 대체하지 않는다.
return currentMerchant.findCurrent().map(MerchantIdentity::merchantId);
```

`CurrentStaff`·직원 세션은 픽업 인증을 대신하지 않는다. P1은 요청의 가맹점 신원까지만 제공하며 주문 소유권을 보장하지 않는다.
P6가 인증된 ID를 픽업 저장·매입의 ID와 대조한다(#226·#227). 주문 식별 범위·선도착 저장·동일/상충 재전송의 업무 처리는 P6 소유다.
현재 dev에는 실제 P6 업무 Controller가 없으므로 공통 인증 제공과 주문 저장·소유권 통합 검증을 구분한다.

## 4. 키와 가맹점 상태

- 기존 `merchants.secret_reference`는 `DONWORRY_MERCHANT_HMAC_`로 시작하는 환경변수 이름만 저장한다. 이 기존 컬럼을 Entity에 매핑하며 기존 migration은 바꾸지 않는다.
- 환경변수 값은 독립적인 무작위 키 32바이트 이상의 표준 Base64다. Spring properties·시스템 속성·기본 키로 대체하지 않는다.
- 가맹점과 환경별 키를 분리한다. 참조가 NULL이면 아직 연동하지 않은 가맹점이므로 401, 참조 형식/환경변수/키 형식이 잘못되면 서버 설정 오류 503이다.
- 원문 키·서명·본문·nonce는 로그에 남기지 않는다. 관리 DTO에도 `secretReference`·키를 추가하지 않는다.
- 실제 CakeShop 가맹점 코드·환경변수명과 키 배포는 운영 준비 때 별도로 지정한다. 이번 구현은 기존 시드에 키나 참조를 임의로 넣지 않는다.
- 유효한 서명의 INACTIVE 가맹점은 GET과 위 두 픽업 PUT이 가능하며 POST는 새 서명으로 재시도해도 403이다. 픽업 PUT 허용은 기존 주문의 통지 누락으로 정산이 멈추지 않도록 한 P6 제안·P1 결정이다(#241). 자기 결제 범위는 P5에서 검사한다. 잘못된 서명은 먼저 401이다.
- 403으로 거절한 POST도 nonce는 먼저 소비한다. 가맹점이 다시 ACTIVE가 된 뒤 같은 서명을 재전송하면 401이므로, 정지 중 실패로 처리한 요청이 나중에 실행되지 않는다.

## 5. 오류

모두 `application/problem+json`으로 반환하며 구체적인 인증 실패 원인을 노출하지 않는다.

| HTTP | code | 의미 |
| --- | --- | --- |
| 401 | MERCHANT_AUTH_001 | 누락·형식 오류·없는 가맹점·서명 불일치·만료·nonce 재사용·연동하지 않은 가맹점 |
| 503 | MERCHANT_AUTH_002 | 키 설정·가맹점 조회·nonce 기록 저장 장애 |
| 403 | MERCHANT_AUTH_003 | 유효 서명의 정지 가맹점 POST |
| 405 | MERCHANT_AUTH_004 | 지정 경로의 미지원 메서드 |
| 413 | MERCHANT_AUTH_005 | 본문 크기 초과 |

P5 업무 입력·충돌·다른 가맹점 조회·처리 장애 오류는 기존 P5 코드를 유지한다.
`payment.http.enabled` 기본 비활성은 유지한다. 인증 구현과 실제 CakeShop 운영 활성화·HTTPS 검증은 구분한다.
HMAC 보안 체인은 이 플래그와 P6 Controller 유무에 무관하게 항상 등록한다. 기능이 꺼져도 보호 경로가 직원 세션 체인으로 넘어가지 않게 하기 위해서다 (비활성 시 404 대신 401/405, 유효 서명 요청은 nonce만 기록된 뒤 404).

## 6. 검증과 후속

검증 계획: 독립 HMAC 고정 벡터, 본문·쿼리·멱등 키 변조, ±5분 경계, 중복 헤더, nonce 동시 소비·DB 장애·독립 커밋·만료 삭제,
직원 세션 우회·인증 비저장·HEAD 우회·CSRF 범위, P5 실제 HTTP 서명·멱등 재시도·가맹점 격리·정지 조회, 전체 Flyway·JPA validate·모듈 경계.

2026-10-27, 기준 dev `d9fd751` 위 최종 구현 작업 트리에서 `./gradlew spotlessApply check mariaDbTest --console=plain`을 실행했다.
일반 486개 중 485개 성공·기존 1개 건너뜀, MariaDB 323개 성공. 실패·오류 0. 문서 상대 링크·공백 검사도 통과했다.

- P5 HTTP 9개는 테스트 신원 헤더를 제거하고 실제 HMAC 필터·공개 경계 어댑터·가맹점 조회·DB nonce 기록을 거친다. 키 조회·FDS 평가만 대역이다.
- 같은 HTTP 서명 재전송의 401, 새 서명·같은 결제 키 재시도의 동일 결과, 타 가맹점 404, 정지 POST/GET, 잘못된 본문 뒤에도 nonce 보존을 확인했다.
- nonce MariaDB 9개는 12개 동시 요청의 1개 수락, 가맹점별 범위, 외부 롤백과 독립 커밋, 저장소 재생성 후 차단, 저장 장애·만료 경계·삭제 실패/재시도·1,001건 배치, 중복 nonce 로그 미노출·잠금 대기 중 만료를 확인했다.
- 정상 중복 INSERT를 ORM/JDBC 오류로 만들면 MariaDB 드라이버가 nonce를 로그에 남기는 것을 재현했다. 가맹점 행 잠금 후 중복을 조회해 정상 거절하고, UNIQUE는 최종 방어로 유지하도록 보완했다.
- 미검증·후속: CakeShop 실제 서명 코드·환경변수 키 배포·운영 HTTPS/프록시·부하·실제 프로세스 재시작(저장소 객체 재생성으로 구분해 검사)·P6 연결·Notion 동기화·팀 메시지 발송. 프론트/AI 코드는 변경하지 않아 해당 검사를 실행하지 않았다.
- 기존 #203 브랜치는 보존했다. 셸 Git fetch의 DNS 오류로 GitHub 연결 도구에서 받은 원격 Git 객체를 SHA 일치 확인 후 로컬에 반영했다.

### 최신 dev 통합 후 최종 검증

최신 dev `d5a74d4`(#221)를 통합한 커밋 `8b184fc`에서 `./gradlew spotlessApply check mariaDbTest --console=plain`을 다시 실행해 통과했다.
일반 496개 중 495개 성공·기존 1개 건너뜀, MariaDB 323개 성공. 실패·오류 0.
ADR 목록 충돌은 P2 ADR-0036과 HMAC ADR-0037을 모두 보존해 해결했고 ERD는 자동 병합됐다.
신규 P2 migration까지 포함해 전체 Flyway·JPA validate·모듈 경계 검사를 통과했다.
검증 이후 변경은 이 문서의 실행 기록뿐이다. [PR #225](https://github.com/team-sweethan/DonWorry/pull/225)는 dev 대상으로 생성했다.
원격 CI 결과는 PR에서 별도 확인하며 위 수치는 로컬 실행 결과다.

### 리뷰 반영 후 검증

PR 리뷰를 반영한 커밋 `510eae8`에서 정지 가맹점 POST의 403 거절보다 nonce 소비를 먼저 하도록 순서를 바꿨다. 정지 중 거절한 서명은 재활성화 뒤 재전송해도 401이다.
같은 코드에서 `./gradlew spotlessApply check mariaDbTest --console=plain`을 실행해 통과했다. 일반 497개 중 496개 성공·기존 1개 건너뜀, MariaDB 323개 성공. 실패·오류 0.
신규 테스트 `inactive_whenPostRejected_consumesNonceBeforeReactivation`이 수정 전 코드에서 실패하는지는 실행해 확인하지 않았다.
`510eae8`의 원격 CI(backend·docs 포함)는 통과했다. 이후 변경은 이 문서의 실행 기록뿐이다.

### #241 픽업 PUT 공통 인증 검증

2026-10-27, 기준 dev `ae93f90` 위 구현 커밋 `2b599cd`와 같은 코드에서 확인했다.

- `cd backend && ./gradlew spotlessApply check mariaDbTest --console=plain`: 통과. 일반 510개 중 509개 성공·기존 1개 건너뜀, MariaDB 328개 성공, 실패·오류 0. 최종 명령의 일반 test는 동일 소스의 직전 성공 결과를 UP-TO-DATE로 재사용했고 MariaDB는 새로 실행했다. 전체 Flyway·JPA validate·ModularityTests·ArchitectureTests 포함.
- 신규 픽업 보안 테스트 11개는 구현 전에 실패하고 구현 후 통과했다. 두 PUT의 정지 가맹점 허용·본문 보존·세션 비저장·직원 세션 분리, 미지원 메서드 405·Allow: PUT, 주문번호·경로·메서드·본문 변조, 만료·nonce 재사용·새 서명 재시도·nonce 장애 503을 확인했다. 기존 인접 경로의 CSRF 보호도 확인했다.
- Python 예시를 오프라인 실행해 두 요청과 고정 서명을 생성했고 Java 서명 테스트 2개와 일치했다. 처음 이를 MockMvc 요청에 그대로 넣었을 때 테스트 환경이 Content-Type에 charset을 추가해 401이 됐다. 고정 벡터 비교는 서명 단위 테스트에서 수행하고, HTTP 검사는 실제 전송 헤더에 맞춰 서명하도록 구분했다.
- MariaDB 실제 HTTP 4개는 키 조회만 대역이고 HMAC 필터·DB 가맹점 조회·독립 nonce 커밋을 거친다. P5 비활성·CSRF 활성·P6 Controller 부재 상태에서 서명 누락 401, 정지 가맹점의 올바른 PUT은 nonce 소비 후 404, 동일 서명 재사용 401, 새 nonce는 다시 인증·기록, 미지원 메서드 405·Allow: PUT, 실제 nonce INSERT 장애 503을 확인했다.
- 문서 상대 링크·diff 공백 검사 통과. 검증 이후 변경은 실행 기록과 관련 문서 안내 보완뿐이다.
- 미검증: 실제 P6 Controller·어댑터·주문/매입 소유권 대조·픽업 저장·동일/상충 업무 재전송 통합, CakeShop 발신 코드·실제 키/가맹점 등록·배포 도메인·HTTPS/프록시·Notion 동기화. 프론트·AI 코드는 변경하지 않아 해당 검사를 실행하지 않았고 팀 메시지는 발송하지 않았다. #241은 실제 통합 검증 전까지 전체 완료로 처리하지 않는다.

## 7. #241 CakeShop 픽업 PUT 서명 예시

P6가 전달한 두 경로와 본문 필드를 사용한다. 아래는 CakeShop 서버에서 적용할 Python 표준 라이브러리 예시이며 CakeShop 저장소 구현 자체는 아니다.
실제 가맹점 등록·키 준비는 #246, 결제 발신 연결은 #242다. 픽업 발신 코드와 실제 호출은 CakeShop 작업에서 함께 연결해야 한다.
키는 서버 환경에서만 읽고 base_url은 배포 주소로 지정한다. 브라우저에 키를 전달하지 않는다.

```python
import base64
import hashlib
import hmac
import json
import time
import uuid
from urllib.parse import quote
from urllib.request import Request


def pickup_request(base_url, merchant_code, key_base64, order_id, action, pickup_at):
    field = {"schedule": "scheduledPickupAt", "completion": "pickedUpAt"}[action]
    target = f"/api/v1/order-fulfillments/{quote(order_id, safe='')}/{action}"
    # 직렬화는 한 번만 한다. 서명한 바이트를 그대로 전송한다.
    body = json.dumps({field: pickup_at}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    timestamp = str(int(time.time()))
    nonce = str(uuid.uuid4())
    content_type = "application/json"
    canonical = "\n".join([
        "DONWORRY-HMAC-V1", merchant_code, timestamp, nonce, "PUT", target,
        content_type, "", hashlib.sha256(body).hexdigest(),
    ])
    key = base64.b64decode(key_base64, validate=True)
    signature = hmac.new(key, canonical.encode("utf-8"), hashlib.sha256).hexdigest()
    return Request(
        base_url.rstrip("/") + target,
        data=body,
        method="PUT",
        headers={
            "Content-Type": content_type,
            "X-Merchant-Code": merchant_code,
            "X-Timestamp": timestamp,
            "X-Nonce": nonce,
            "X-Signature": signature,
        },
    )
```

- `action="schedule"`이면 `scheduledPickupAt`, `action="completion"`이면 `pickedUpAt`을 보낸다. 시각 예시는 `2026-10-27T06:00:00Z`이며 최종 업무 입력·응답·시각 검증은 #227을 따른다.
- `base_url`에는 scheme·host·port만 지정한다. 경로 접두사나 query를 넣지 않는다. 실제 외부 경로가 다르면 P1·P6·인프라와 맞춘 뒤 서명 경로와 전송 경로를 함께 변경한다.
- 이 PUT 예시는 `Idempotency-Key`를 보내지 않아 서명 대상 8번째 줄이 빈 문자열이다. P5 결제 POST의 필수 멱등 키 정책은 그대로다.
- HTTP 재시도마다 함수를 다시 호출해 timestamp·nonce·서명을 새로 만든다. 같은 주문·동작·본문은 유지한다. 같은 서명 재사용은 업무 성공 여부와 무관하게 401이다.
- 새 서명·같은 내용의 업무 중복 및 상충 시각 처리는 #226·#227의 P6 계약을 따른다. HMAC 인증 성공이 업무 중복 처리를 대신하지 않는다.
- 자동 redirect를 따라 다른 경로로 재전송하지 않는다. 새 경로가 필요하면 최종 경로를 확정해 새로 서명한다.
- P6 Controller가 없을 때 올바른 서명은 nonce 소비 후 404일 수 있다. 실제 픽업 저장 성공으로 기록하지 않는다.
