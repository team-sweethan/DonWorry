"""새 가맹점의 키 입력 → 연결 확인 → 선택적 테스트 결제. 표준 라이브러리만 사용한다."""

import argparse
import base64
import getpass
import hashlib
import hmac
import json
import re
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def send(base_url, merchant_code, secret, method, target, body=b"", attempt=""):
    content_type = "application/json" if body else ""
    timestamp, nonce = str(int(time.time())), str(uuid.uuid4())
    canonical = "\n".join([
        "DONWORRY-HMAC-V1", merchant_code, timestamp, nonce, method, target,
        content_type, attempt, hashlib.sha256(body).hexdigest(),
    ])
    headers = {
        "X-Merchant-Code": merchant_code,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": hmac.new(secret, canonical.encode("utf-8"), hashlib.sha256).hexdigest(),
    }
    if content_type:
        headers["Content-Type"] = content_type
    if attempt:
        headers["Idempotency-Key"] = attempt
    request = Request(base_url + target, data=body or None, method=method, headers=headers)
    try:
        response = build_opener(NoRedirect()).open(request, timeout=30)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        try:
            data = json.loads(raw) if raw else {}
        except (ValueError, UnicodeError):
            data = {}
        if not isinstance(data, dict):
            data = {}
        # 응답 원문·헤더는 출력하지 않는다.
        print(f"{method} {target}: HTTP {response.status}")
        for field in ("code", "paymentId", "paymentStatus"):
            if field in data:
                print(f"  {field}: {data[field]}")
        return response.status, data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("--merchant-code", required=True)
    parser.add_argument("--payment", action="store_true", help="연결 확인 후 1,000원 테스트 결제 생성")
    parser.add_argument("--payment-only", action="store_true", help="연결 확인 없이 결제 차단 검증")
    args = parser.parse_args()
    origin = urlsplit(args.base_url)
    if (origin.scheme not in ("http", "https") or not origin.hostname
            or origin.username is not None or origin.password is not None
            or origin.path not in ("", "/") or origin.query or origin.fragment):
        parser.error("base-url은 경로·인증정보가 없는 HTTP(S) 서버 주소여야 합니다.")
    if origin.scheme == "http" and origin.hostname not in ("localhost", "127.0.0.1", "::1"):
        parser.error("로컬 서버 외에는 HTTPS를 사용해 주세요.")
    if not re.fullmatch(r"[A-Z0-9]{3,40}", args.merchant_code):
        parser.error("가맹점 코드는 화면에 표시된 대문자·숫자 3~40자여야 합니다.")
    if args.payment and args.payment_only:
        parser.error("--payment와 --payment-only 중 하나만 선택해 주세요.")
    raw = getpass.getpass("시크릿 키 (Base64, 입력 숨김): ") if sys.stdin.isatty() else sys.stdin.readline().strip()
    try:
        secret = base64.b64decode(raw, validate=True)
        if len(secret) < 32:
            raise ValueError()
    except ValueError:
        parser.error("시크릿 키는 Base64로 인코딩된 32바이트 이상이어야 합니다.")
    base_url = args.base_url.rstrip("/")
    try:
        if not args.payment_only:
            status, _ = send(base_url, args.merchant_code, secret, "POST", "/api/merchant-connections/check")
            if status != 204:
                return 1
            print("연결 확인 완료. 돈워리 화면에서 새로고침해 연동 상태를 확인해 주세요.")
        if args.payment or args.payment_only:
            body = json.dumps({
                "orderRef": "CONNECT-" + uuid.uuid4().hex,
                "customerRef": "354001",
                "amount": 1000,
                "orderCreatedAt": (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat().replace("+00:00", "Z"),
            }, separators=(",", ":")).encode("utf-8")
            status, data = send(base_url, args.merchant_code, secret, "POST", "/api/payments", body, str(uuid.uuid4()))
            if status >= 300:
                return 1
            # 202는 접수·보류/처리 중일 수 있어 승인 성공이라고 출력하지 않는다.
            if status == 202:
                print("결제가 접수되었습니다. 최종 결과는 결제 조회로 확인해 주세요.")
        return 0
    except (URLError, OSError, TimeoutError):
        print("서버 응답을 확인하지 못했습니다. 연결 상태·결제 결과를 조회한 뒤 재실행 여부를 결정해 주세요.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
