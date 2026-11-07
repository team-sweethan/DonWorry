"""시연 시드 전용 로컬 DB(donworry_seed)에 시연 데이터를 채운다 (#638).
시연 보안 직원 2명 + 손님 3명 결제 → 사건 3개 → 시연 직원에게 배정.

실행: 저장소 맨 위에서 py scripts/demo/seed_demo.py (macOS · Linux: python3)
필요: backend를 시드 DB로 켬(run-local -Seed) · ai-server · DONWORRY_MERCHANT_HMAC_CAKESHOP (docs/demo/local-demo.md 3-3절)
backend가 donworry_seed가 아닌 DB(작업용 donworry_dev 등)에 붙어 있으면 멈춘다 — 작업 데이터에 시드가 섞이지 않게.
다시 실행하면 있는 직원은 건너뛰고 새 손님 사건 3개를 더 만든다. 판정(승인 · 거절)은 하지 않는다 — 시연 중 직원이 누른다.
비우기: backend를 끄고 py scripts/demo/seed_demo.py --reset → donworry_seed의 표만 모두 지운다. 다시 켜면 Flyway가 새로 만든다.
"""

import argparse
import getpass
import glob
import os
import random
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.error import URLError
from urllib.parse import urlparse

import cakeshop_pay
from fds_demo_session import staff_session

DEFAULT_BASE_URL = os.environ.get("DONWORRY_BASE_URL", "http://localhost:8081")
REPO = Path(__file__).resolve().parents[2]
AI_ENV_FILE = REPO / "ai-server" / ".env"
LOCAL_YML = REPO / "backend" / "src" / "main" / "resources" / "application-local.yml"
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}
SEED_DB = "donworry_seed"  # 이 스크립트가 쓰고 지우는 유일한 DB. 이름을 인자로 받지 않는다
DB_HOST, DB_PORT, DB_USER = "127.0.0.1", "3307", os.environ.get("DONWORRY_DB_USER", "donworry")
SEED_PASSWORD = "seed1234"  # 비밀번호 규칙: 8자 이상 · 영문 + 숫자
SEED_STAFF = [("seed_staff_a", "시연 보안직원 A"), ("seed_staff_b", "시연 보안직원 B")]
CASE_WAIT_SECONDS = 20


class SeedStop(Exception):
    """안전 확인에 걸렸거나 서버가 예상과 다르게 답해 멈춘다."""


# 운영 · 배포 서버에 가짜 직원 · 결제를 만들지 않게 내 PC 주소만 받는다
def check_target(base_url: str) -> None:
    host = urlparse(base_url).hostname
    if host not in LOCAL_HOSTS:
        raise SeedStop(f"내 PC(localhost) 서버에만 실행합니다. 받은 주소: {base_url}")


# ai-server가 OpenAI면 사건마다 보고서 요청이 밖으로 나간다 — 허락(--allow-openai) 없이는 멈춘다
# ai-server(pydantic-settings)는 OS 환경변수를 .env보다 먼저 쓰므로 같은 순서로 본다
def ai_provider(env_file: Path, environ: dict) -> str | None:
    if environ.get("AI_SERVER_LLM_PROVIDER"):
        return environ["AI_SERVER_LLM_PROVIDER"].strip().lower()
    if not env_file.exists():
        return None
    provider = None
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("AI_SERVER_LLM_PROVIDER="):
            provider = line.split("=", 1)[1].strip().strip("\"'").lower()
    return provider


def check_ai_provider(env_file: Path, environ: dict, allow_openai: bool) -> str | None:
    provider = ai_provider(env_file, environ)
    if provider == "openai" and not allow_openai:
        raise SeedStop(
            "ai-server 제공자가 openai라서 멈춥니다. 사건마다 OpenAI로 보고서 요청이 나갑니다.\n"
            "ai-server/.env를 mock으로 바꾸고 ai-server를 다시 켜거나, 허락을 받았으면 --allow-openai를 붙이세요."
        )
    return provider


# --- 로컬 MariaDB (표준 라이브러리만 쓰려고 mariadb 명령줄 프로그램을 부른다) ---


def db_client() -> str:
    found = os.environ.get("DONWORRY_MARIADB_CLIENT") or shutil.which("mariadb") or shutil.which("mysql")
    if not found:
        found = next(iter(sorted(glob.glob(r"C:\Program Files\MariaDB*\bin\mariadb.exe"), reverse=True)), None)
    if not found:
        raise SeedStop("mariadb 명령줄 프로그램을 찾지 못했습니다. DONWORRY_MARIADB_CLIENT에 mariadb.exe 경로를 넣으세요.")
    return found


def db_password() -> str:
    if os.environ.get("DONWORRY_DB_PASSWORD"):
        return os.environ["DONWORRY_DB_PASSWORD"]
    if LOCAL_YML.exists():
        match = re.search(r"^\s*password:\s*(\S+)", LOCAL_YML.read_text(encoding="utf-8"), re.M)
        if match:
            return match.group(1).strip("\"'")
    return "donworry"


def db_query(sql: str) -> list:
    completed = subprocess.run(
        [db_client(), "-h", DB_HOST, "-P", DB_PORT, f"-u{DB_USER}", "-N", "-B", "-e", sql],
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "MYSQL_PWD": db_password()},
    )
    if completed.returncode != 0:
        raise SeedStop(f"로컬 DB 조회 실패: {completed.stderr.strip()}")
    return [line.split("\t") for line in completed.stdout.splitlines() if line]


def last_login_history_id() -> int:
    try:
        rows = db_query(f"SELECT COALESCE(MAX(history_id), 0) FROM {SEED_DB}.login_histories")
    except SeedStop as error:
        raise SeedStop(
            f"{SEED_DB}에 표가 없습니다. backend를 시드 DB로 켰는지 확인하세요 (run-local.local.ps1 -Seed).\n{error}"
        ) from error
    return int(rows[0][0])


# 관리자 로그인이 donworry_seed에 기록됐으면 이 backend는 시드 DB에 붙어 있다 (API로는 DB 이름을 알 수 없음)
def check_backend_uses_seed_db(before: int) -> None:
    for _ in range(5):
        if last_login_history_id() > before:
            return
        time.sleep(1)
    raise SeedStop(
        f"방금 로그인이 {SEED_DB}에 기록되지 않았습니다. backend가 다른 DB(작업용 donworry_dev 등)에 붙어 있습니다.\n"
        "시드는 시드 DB에만 넣습니다. backend를 끄고 run-local.local.ps1 -Seed로 다시 켜세요."
    )


# donworry_seed의 표만 지운다. 다른 DB 이름은 SQL에 들어갈 수 없다 (SEED_DB 상수)
def reset_sql(tables: list) -> str:
    if not tables:
        return ""
    # 표 이름 속 백틱은 두 번 써서 한 이름으로 묶는다 — `a`.`b` 같은 이름이 다른 DB를 가리키지 못하게
    names = ", ".join("`{}`.`{}`".format(SEED_DB, table.replace("`", "``")) for table in tables)
    return f"SET FOREIGN_KEY_CHECKS = 0; DROP TABLE {names}; SET FOREIGN_KEY_CHECKS = 1;"


def reset_seed_db() -> None:
    # 켜진 backend가 시드 DB를 쓰는 중에 표를 지우면 그 backend가 깨진다 — 같은 계정의 접속이 남아 있으면 멈춘다
    connections = db_query(
        f"SELECT COUNT(*) FROM information_schema.processlist WHERE db = '{SEED_DB}' AND id <> CONNECTION_ID()"
    )
    if int(connections[0][0]) > 0:
        raise SeedStop(f"{SEED_DB}에 붙은 접속이 있습니다. 시드 DB로 켠 backend를 먼저 끄세요.")
    tables = [row[0] for row in db_query(
        f"SELECT table_name FROM information_schema.tables WHERE table_schema = '{SEED_DB}'"
    )]
    if not tables:
        print(f"{SEED_DB}가 이미 비어 있습니다.")
        return
    db_query(reset_sql(tables))
    print(f"{SEED_DB}의 표 {len(tables)}개를 지웠습니다. run-local.local.ps1 -Seed로 켜면 Flyway가 새로 만듭니다.")


def expect(status: int, data: dict, ok: tuple, what: str) -> dict:
    if status not in ok:
        raise SeedStop(f"{what} 실패: HTTP {status} {data.get('code', '')} {data.get('detail', data)}")
    return data


def find_user(admin, login_id: str) -> dict | None:
    status, data = admin("GET", f"/api/admin/users?keyword={login_id}&size=50")
    expect(status, data, (200,), "직원 검색")
    return next((user for user in data.get("items", []) if user["loginId"] == login_id), None)


# 임시 비밀번호로 처음 로그인해 시연 비밀번호로 바꾼다 (비밀번호를 바꾸기 전에는 다른 API가 막힌다)
def set_seed_password(base_url: str, login_id: str, temporary_password: str) -> None:
    session = staff_session(base_url, login_id, temporary_password)
    status, data = session(
        "POST", "/api/auth/password", {"currentPassword": temporary_password, "newPassword": SEED_PASSWORD}, csrf=True
    )
    expect(status, data, (204,), f"{login_id} 비밀번호 변경")


def ensure_staff(admin, base_url: str, login_id: str, display_name: str) -> int:
    user = find_user(admin, login_id)
    if user is None:
        status, data = admin(
            "POST",
            "/api/admin/users",
            {"loginId": login_id, "displayName": display_name, "role": "SECURITY_STAFF"},
            csrf=True,
        )
        created = expect(status, data, (201,), f"{login_id} 생성")
        set_seed_password(base_url, login_id, created["temporaryPassword"])
        print(f"  {login_id} 만들었습니다")
        return created["user"]["userId"]
    if user["role"] != "SECURITY_STAFF" or user["status"] != "ACTIVE":
        raise SeedStop(f"{login_id}가 이미 있지만 활성 보안 직원이 아닙니다 ({user['role']} · {user['status']}). 관리자 화면에서 확인하세요.")
    # 앞 실행이 비밀번호 변경 전에 멈췄으면 임시 비밀번호를 다시 받아 마저 바꾼다
    if user["passwordChangeRequired"]:
        status, data = admin("POST", f"/api/admin/users/{user['userId']}/password-reset", csrf=True)
        reset = expect(status, data, (200,), f"{login_id} 임시 비밀번호 재발급")
        set_seed_password(base_url, login_id, reset["temporaryPassword"])
        print(f"  {login_id} 있음 — 비밀번호 변경을 마저 했습니다")
    else:
        print(f"  {login_id} 있음 — 건너뜁니다")
    return user["userId"]


# 결제 → 사건을 바로 잇는 API가 없어, 결제 전후의 직원별 열린 사건을 비교해 새 사건을 찾는다
def open_cases(admin) -> dict:
    status, data = admin("GET", "/api/admin/case-assignments")
    expect(status, data, (200,), "담당 현황 조회")
    cases = {}
    for staff in data.get("staff", []):
        status, rows = admin("GET", f"/api/admin/case-assignments/staff/{staff['staffId']}/cases")
        expect(status, rows if isinstance(rows, dict) else {}, (200,), "직원 사건 조회")
        for row in rows:
            cases[row["caseId"]] = staff["staffId"]
    return cases


def customers(run_id: str) -> list:
    # 손님 번호는 cakeshop_pay와 같은 12자리. 1번과 3번은 같은 카드 — 3번 사건 보고서 "같은 카드"에 1번 결제가 잡힌다
    shared_card = f"SEED{run_id}"
    numbers = [str(random.randint(10**11, 10**12 - 1)) for _ in range(3)]
    return [
        ("기본 손님", numbers[0], argparse.Namespace(ip=None, country=None, email=None, card=shared_card)),
        ("해외 IP 손님", numbers[1], argparse.Namespace(ip="198.51.100.7", country="US", email=None, card=None)),
        ("1번과 같은 카드를 쓴 손님", numbers[2], argparse.Namespace(ip=None, country=None, email=None, card=shared_card)),
    ]


def pay_customer(key: bytes, label: str, customer_ref: str, options: argparse.Namespace) -> bool:
    extra = cakeshop_pay.signals(customer_ref, options)
    held = False
    for n in range(1, cakeshop_pay.COUNT + 1):
        order_ref, status, data = cakeshop_pay.pay(key, customer_ref, n, extra)
        if status >= 400:
            raise SeedStop(f"{label} {n}번째 결제 실패: HTTP {status} {data.get('code', '')} {data.get('detail', data)}")
        held = held or data.get("paymentStatus") == "HELD"
        print(f"  {label} {customer_ref} {n}번째  결제 ID {data.get('paymentId')}  상태 {data.get('paymentStatus')}")
        time.sleep(1)
    return held


def wait_new_cases(admin, before: dict, expected: int) -> dict:
    deadline = time.monotonic() + CASE_WAIT_SECONDS
    while True:
        new = {case_id: staff_id for case_id, staff_id in open_cases(admin).items() if case_id not in before}
        if len(new) >= expected or time.monotonic() > deadline:
            return new
        time.sleep(1)


# 열린 사건이 적은 시연 직원에게 준다 — 여러 번 실행해도 한 직원에게 몰리지 않게 (같으면 앞 직원)
def assign(admin, new_cases: dict, staff_ids: list, before: dict) -> list:
    load = {staff_id: sum(1 for owner in before.values() if owner == staff_id) for staff_id in staff_ids}
    result = []
    for case_id in sorted(new_cases):
        target = min(staff_ids, key=lambda staff_id: load[staff_id])
        load[target] += 1
        if new_cases[case_id] != target:
            status, data = admin(
                "POST",
                f"/api/admin/case-assignments/cases/{case_id}/reassign",
                {"newStaffId": target, "reason": "OTHER", "memo": "시드 스크립트 시연 배정 (#638)"},
                csrf=True,
            )
            expect(status, data or {}, (200, 204), f"사건 {case_id} 배정")
        result.append((case_id, target))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="돈워리 로컬 시연 데이터 시드")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--admin-id", default="admin")
    parser.add_argument("--allow-openai", action="store_true", help="ai-server가 openai여도 진행 (사전 허락 받은 경우만)")
    parser.add_argument("--reset", action="store_true", help=f"{SEED_DB}의 표를 모두 지운다 (backend를 끈 뒤)")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    if args.reset:
        try:
            reset_seed_db()
        except SeedStop as error:
            print(f"멈춤: {error}")
            return 1
        return 0
    try:
        check_target(base_url)
        provider = check_ai_provider(AI_ENV_FILE, os.environ, args.allow_openai)
        print(f"서버 {base_url} · ai-server 제공자 {provider or '확인 못 함(.env 없음)'}")
        key = cakeshop_pay.load_key()
        cakeshop_pay.BASE_URL = base_url
        password = os.environ.get("DONWORRY_ADMIN_PASSWORD") or getpass.getpass(f"관리자 {args.admin_id} 비밀번호 (입력 숨김): ")
        login_mark = last_login_history_id()
        admin = staff_session(base_url, args.admin_id, password)
        check_backend_uses_seed_db(login_mark)
        print(f"backend가 시드 DB({SEED_DB})에 붙어 있습니다")

        print("\n1. 시연 직원")
        staff_ids = [ensure_staff(admin, base_url, login_id, name) for login_id, name in SEED_STAFF]

        print("\n2. 결제 → 경보 → 사건")
        before = open_cases(admin)
        run_id = uuid.uuid4().hex[:6].upper()
        held = sum(pay_customer(key, label, ref, options) for label, ref, options in customers(run_id))

        print("\n3. 사건 배정")
        new_cases = wait_new_cases(admin, before, held)
        if len(new_cases) < held:
            print(f"  주의: 보류 손님 {held}명인데 새 사건은 {len(new_cases)}개만 찾았습니다. 사건 생성이 늦거나 실패했을 수 있습니다.")
        names = dict(zip(staff_ids, (login_id for login_id, _ in SEED_STAFF)))
        assigned = assign(admin, new_cases, staff_ids, before)
        for case_id, staff_id in assigned:
            print(f"  사건 {case_id} → {names[staff_id]}")
    except SeedStop as error:
        print(f"\n멈춤: {error}")
        return 1
    except RuntimeError as error:  # staff_session 로그인 실패
        print(f"\n멈춤: {error}")
        return 1
    except URLError as error:
        print(f"\n멈춤: 서버({base_url})에 연결하지 못했습니다: {error.reason}")
        return 1

    print("\n끝. http://localhost:5173 에서 로그인해 보세요")
    for login_id, _ in SEED_STAFF:
        print(f"  {login_id} / {SEED_PASSWORD}")
    print("AI 보고서는 사건이 생길 때 서버가 자동으로 요청합니다 (ai-server가 켜져 있어야 함)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
