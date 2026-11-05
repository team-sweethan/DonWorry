import hashlib
import hmac
import unittest
from unittest.mock import MagicMock, patch

import merchant_connect


class MerchantConnectTest(unittest.TestCase):
    # 연결 확인과 결제 모두 명시적인 User-Agent를 보내며 기존 서명 계약을 유지한다.
    def test_send_whenConnectingOrPaying_setsUserAgentAndPreservesSignature(self):
        for target, body, attempt in [
            ("/api/merchant-connections/check", b"", ""),
            ("/api/payments", b'{"amount":1000}', "attempt-1"),
        ]:
            with self.subTest(target=target):
                response = MagicMock()
                response.__enter__.return_value = response
                response.status = 204
                response.read.return_value = b""
                with patch.object(merchant_connect, "build_opener") as opener:
                    opener.return_value.open.return_value = response
                    merchant_connect.send("https://example.com", "TESTSHOP", b"test-key", "POST", target, body, attempt)
                    request = opener.return_value.open.call_args.args[0]
                self.assertEqual(request.get_header("User-agent"), "DonWorry-Merchant-Connect/1.0")
                canonical = "\n".join([
                    "DONWORRY-HMAC-V1", "TESTSHOP", request.get_header("X-timestamp"),
                    request.get_header("X-nonce"), "POST", target,
                    "application/json" if body else "", attempt, hashlib.sha256(body).hexdigest(),
                ])
                self.assertEqual(request.get_header("X-signature"), hmac.new(b"test-key", canonical.encode(), hashlib.sha256).hexdigest())
                self.assertEqual(request.data, body or None)


if __name__ == "__main__":
    unittest.main()
