"""기존 FDS ERROR 결제의 실제 직원 재평가 API를 호출한다."""

import argparse
import getpass
import json
import os
import sys

from fds_demo_session import staff_session


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("payment_id", type=int, help="fds_error.py가 출력한 결제 ID")
    parser.add_argument("--base-url", default=os.environ.get("DONWORRY_BASE_URL", "http://localhost:8081"))
    parser.add_argument("--staff-id", default="staff_test")
    parser.add_argument("--staff-password")
    args = parser.parse_args()
    password = args.staff_password or getpass.getpass("보안 직원 비밀번호 (입력 숨김): ")
    try:
        request = staff_session(args.base_url.rstrip("/"), args.staff_id, password)
        status, data = request(
            "POST", f"/api/payment-operations/{args.payment_id}/fds-reevaluations", csrf=True)
    except RuntimeError as error:
        sys.exit(str(error))
    print(json.dumps(data, ensure_ascii=False, indent=2))
    if status != 200:
        sys.exit(f"재평가 실패: HTTP {status} {data.get('code', data)}")
    print(f"재평가 완료: paymentId={args.payment_id}, status={data.get('status')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
