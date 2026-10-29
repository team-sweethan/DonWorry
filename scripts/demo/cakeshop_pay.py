# 케이크샵 대신 돈워리에 HMAC 서명 결제 3건을 보내는 로컬 시연 스크립트 (docs/demo/local-demo.md)
# 같은 손님이 60만 원을 3번 결제 → 3번째가 FDS 60점 REVIEW로 HELD(보류)되어 Case·보고서가 생긴다
# 필요: Spring payment.http.enabled=true, Spring과 같은 DONWORRY_MERCHANT_HMAC_CAKESHOP 환경변수
# 실행: 저장소 맨 위에서 py scripts/demo/cakeshop_pay.py (macOS · Linux: python3)
# 같은 손님으로 다시: py scripts/demo/cakeshop_pay.py <손님 번호> — 보고서 "고객 최근 결제"(#338) 확인용
# 카드 · 기기도 보내기: --signals (손님마다 고정된 가짜 카드 · 기기), IP까지: --ip 203.0.113.5 --country US (#346)
#   카드 · 기기를 보내면 FDS 규칙(새 기기 · 처음 쓰는 카드)이 걸려 몇 번째 결제가 보류될지 달라질 수 있다
import argparse
import base64
import hashlib
import hmac
import json
import os
import random
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener

BASE_URL = os.environ.get("DONWORRY_BASE_URL", "http://localhost:8081")
MERCHANT_CODE = "CAKESHOP"
AMOUNT = 600_000
COUNT = 3


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_key() -> bytes:
    raw = os.environ.get("DONWORRY_MERCHANT_HMAC_CAKESHOP", "")
    if not raw:
        sys.exit("DONWORRY_MERCHANT_HMAC_CAKESHOP 환경변수가 없어요. 키를 저장한 뒤 터미널을 새로 열어 주세요 (docs/demo/local-demo.md 2-1).")
    try:
        key = base64.b64decode(raw, validate=True)
    except ValueError:
        sys.exit("서명 키가 Base64 형식이 아니에요.")
    if len(key) < 32:
        sys.exit("서명 키가 너무 짧아요(32바이트 이상 필요).")
    return key


# DONWORRY-HMAC-V1 서명 (docs/identity/219-merchant-hmac.md)
def send(key: bytes, method: str, target: str, body: bytes = b"", idempotency_key: str = ""):
    content_type = "application/json" if body else ""
    timestamp, nonce = str(int(time.time())), str(uuid.uuid4())
    canonical = "\n".join([
        "DONWORRY-HMAC-V1", MERCHANT_CODE, timestamp, nonce, method, target,
        content_type, idempotency_key, hashlib.sha256(body).hexdigest(),
    ])
    headers = {
        "X-Merchant-Code": MERCHANT_CODE,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": hmac.new(key, canonical.encode("utf-8"), hashlib.sha256).hexdigest(),
    }
    if content_type:
        headers["Content-Type"] = content_type
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = Request(BASE_URL + target, data=body or None, method=method, headers=headers)
    try:
        response = build_opener(NoRedirect()).open(request, timeout=30)
    except HTTPError as error:
        response = error
    except URLError as error:
        sys.exit(f"Spring 서버({BASE_URL})에 연결하지 못했어요: {error.reason}")
    text = response.read().decode("utf-8")
    try:
        data = json.loads(text) if text else {}
    except json.JSONDecodeError:
        data = {"raw": text}
    return response.status, data


# 가맹점이 보내는 FDS 식별자 (#325) — 카드는 원문이 아니라 토큰 · 마스킹 표시값만. 시연용 가짜 값이다
def signals(customer_ref: str, args: argparse.Namespace) -> dict:
    found = {}
    if args.signals:
        found.update(
            cardToken=f"tok_demo_{customer_ref}",
            cardDisplayLabel=f"데모카드 ****{customer_ref[-4:]}",
            deviceId=f"device-demo-{customer_ref[-6:]}",
        )
    if args.ip:
        found["ipAddress"] = args.ip
    if args.country:
        found["ipCountryCode"] = args.country
    return found


def pay(key: bytes, customer_ref: str, n: int, extra: dict):
    order_ref = f"CAKE-{datetime.now():%Y%m%d%H%M%S}-{uuid.uuid4().hex[:6]}-{n}"
    created_at = (datetime.now(timezone.utc) - timedelta(seconds=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    body = json.dumps(
        # Python 3.8에서도 돌게 dict 합치기는 ** 로 한다
        {"orderRef": order_ref, "customerRef": customer_ref, "amount": AMOUNT, "orderCreatedAt": created_at, **extra},
        separators=(",", ":"),
    ).encode("utf-8")
    status, data = send(key, "POST", "/api/payments", body, str(uuid.uuid4()))
    # 202는 아직 처리 중 — 끝날 때까지 결제 상태를 다시 조회한다
    for _ in range(10):
        if status != 202 or "paymentId" not in data:
            break
        time.sleep(1)
        status, data = send(key, "GET", f"/api/payments/{data['paymentId']}")
    return order_ref, status, data


def main() -> int:
    parser = argparse.ArgumentParser(description="돈워리 로컬 시연 결제")
    # 손님 번호를 주면 그 손님으로 결제한다. 없으면 매번 새 손님
    parser.add_argument("customer", nargs="?", help="손님 번호 (같은 손님으로 다시 결제할 때)")
    parser.add_argument("--signals", action="store_true", help="손님마다 고정된 가짜 카드 · 기기를 함께 보냄")
    parser.add_argument("--ip", help="구매자 접속 IP (예: 203.0.113.5)")
    parser.add_argument("--country", help="접속 국가 코드 2자리 (예: US, KR)")
    args = parser.parse_args()
    key = load_key()
    customer_ref = args.customer or str(random.randint(10**11, 10**12 - 1))
    extra = signals(customer_ref, args)
    print(f"손님 번호 {customer_ref} 로 {AMOUNT:,}원 결제를 {COUNT}번 보냅니다")
    print(f"함께 보내는 값: {', '.join(extra) if extra else '없음'}\n")
    for n in range(1, COUNT + 1):
        order_ref, status, data = pay(key, customer_ref, n, extra)
        if status >= 400:
            print(f"{n}번째 실패: HTTP {status} {data.get('code', '')} {data.get('detail', data)}")
            if status == 401:
                print("→ 서명이 거절됐어요. Spring 창과 이 창의 서명 키가 같은지, PC 시계가 맞는지 확인해 주세요.")
            elif status == 404 and not data.get("code"):
                print("→ 결제 API가 꺼져 있어요. payment.http.enabled=true로 Spring을 다시 켜 주세요.")
            return 1
        mark = "  ← 보류! 보안 직원 화면에 Case가 생겼어요" if data.get("paymentStatus") == "HELD" else ""
        print(f"{n}번째  결제 ID {data.get('paymentId')}  상태 {data.get('paymentStatus')}  주문 {order_ref}{mark}")
        time.sleep(1)
    print("\n이제 http://localhost:5173 에서 staff_test / 1234 로 로그인해 Case 목록을 열어 보세요.")
    print(f"같은 손님으로 다시 결제하려면: py scripts/demo/cakeshop_pay.py {customer_ref}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
