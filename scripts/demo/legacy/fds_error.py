"""로컬에서 선택한 FDS 오류 시나리오 한 건을 만들고 실제 CakeShop 결제를 보낸다."""

import argparse
import base64
import getpass
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

from fds_demo_session import staff_session


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def merchant_key() -> bytes:
    raw = os.environ.get("DONWORRY_MERCHANT_HMAC_CAKESHOP", "")
    try:
        key = base64.b64decode(raw, validate=True)
    except ValueError:
        key = b""
    if len(key) < 32:
        sys.exit("DONWORRY_MERCHANT_HMAC_CAKESHOP에 32바이트 이상 Base64 서명 키가 필요합니다.")
    return key


def signed_payment(base_url: str, key: bytes, customer_ref: str):
    target = "/api/payments"
    body = json.dumps({
        "orderRef": f"FDS-ERROR-{uuid.uuid4().hex}",
        "customerRef": customer_ref,
        "amount": 100_000,
        "orderCreatedAt": (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat().replace("+00:00", "Z"),
        "deviceId": f"device-error-{customer_ref[-6:]}",
        "cardToken": f"tok_error_{customer_ref}",
        "cardDisplayLabel": "시연 오류 카드 ****0001",
        "ipAddress": "203.0.113.10",
        "ipCountryCode": "KR",
        "buyerEmail": f"error-{customer_ref}@example.test",
    }, separators=(",", ":")).encode()
    timestamp, nonce, idempotency_key = str(int(time.time())), str(uuid.uuid4()), str(uuid.uuid4())
    canonical = "\n".join([
        "DONWORRY-HMAC-V1", "CAKESHOP", timestamp, nonce, "POST", target, "application/json",
        idempotency_key, hashlib.sha256(body).hexdigest(),
    ])
    headers = {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotency_key,
        "X-Merchant-Code": "CAKESHOP",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": hmac.new(key, canonical.encode(), hashlib.sha256).hexdigest(),
    }
    try:
        response = build_opener(NoRedirect()).open(Request(base_url + target, body, headers=headers, method="POST"), timeout=30)
    except HTTPError as error:
        response = error
    except URLError as error:
        sys.exit(f"Spring 서버에 연결하지 못했습니다: {error.reason}")
    raw = response.read().decode()
    try:
        return response.status, json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        return response.status, {"raw": raw}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("DONWORRY_BASE_URL", "http://localhost:8081"))
    parser.add_argument("--staff-id", default="staff_test")
    parser.add_argument("--staff-password")
    parser.add_argument(
        "--scenario",
        choices=(
            "TRANSIENT_FDS_EVALUATION_TIMEOUT",
            "TRANSIENT_FDS_RULE_DATA_UNAVAILABLE",
            "FDS_EVALUATION_PROCESSING_FAILED",
        ),
        default="TRANSIENT_FDS_EVALUATION_TIMEOUT",
        help="평가 시간 초과·룰 정보 일시 조회 실패·비일시 처리 실패 시나리오",
    )
    args = parser.parse_args()
    key = merchant_key()
    password = args.staff_password or getpass.getpass("보안 직원 비밀번호 (입력 숨김): ")
    base_url = args.base_url.rstrip("/")
    try:
        request = staff_session(base_url, args.staff_id, password)
        status, data = request("POST", f"/api/demo/fds-errors/next?scenario={args.scenario}", csrf=True)
    except RuntimeError as error:
        sys.exit(str(error))
    if status != 204:
        sys.exit(f"오류 주입 준비 실패: HTTP {status} {data.get('code', data)}\n"
                 "backend의 fds.demo-error.enabled=true 설정과 SECURITY_STAFF 계정을 확인하세요.")
    customer_ref = str(random.randint(10**11, 10**12 - 1))
    status, data = signed_payment(base_url, key, customer_ref)
    if status >= 400:
        sys.exit(f"결제 실패: HTTP {status} {data.get('code', data)}\n다음 정상 FDS 평가가 대신 ERROR가 될 수 있으니 주의하세요.")
    payment_id = data.get("paymentId")
    print(f"FDS ERROR 결제 생성: paymentId={payment_id}, scenario={args.scenario}, status={data.get('paymentStatus')}")
    if args.scenario.startswith("TRANSIENT_"):
        print(f"재평가: py scripts/demo/legacy/fds_reevaluate.py {payment_id}")
    else:
        print("비일시 평가 처리 실패는 P2 분석 후 수동 검토 필요 상태가 됩니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
