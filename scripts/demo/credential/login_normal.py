#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["rich>=13"]
# ///
"""평소 로그인 (#770 크리덴셜 스터핑 시연 준비) — 직원들이 평소 브라우저로 드문드문 로그인하는 모습을 만든다.

시연 10분 전부터 켜 두면 로그인 보안 화면의 꺾은선 · 실시간 시도에 평소 모습(실패율 낮음)이 쌓인다.
직원의 "평소 브라우저" 기록도 함께 쌓인다(기본 Edge · Windows). 시연에서 탈취 역할 브라우저는 이것과 다른 종류로 쓴다.
가끔 비밀번호 오타(실패 한 번 뒤 성공)를 넣는다. Ctrl+C로 멈춘다.

  uv run scripts/demo/credential/login_normal.py --account hanjiwoo:비밀번호 --account parkseoyeon:비밀번호

uv run이면 rich로 꾸민 화면(흐린 기록 + 아래 상태 줄)이 나온다. rich가 없으면 줄 출력으로 돌아간다.
"""
import argparse
import http.cookiejar
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request

# 공격 스크립트와 같은 색 — 성공 초록 · 오타 노랑 · 실패 연한 빨강 · 시각 회보라 · 누적 어두운 회색
OK, WARN, FAIL, DIM, LINE = '#7EE2A0', '#FFD27A', '#FF8A80', '#8C86A0', '#5E5873'
EDGE_WINDOWS = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
                'Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0')


def login(base: str, user_agent: str, login_id: str, password: str) -> int:
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    opener.open(urllib.request.Request(base + '/api/auth/csrf', headers={'User-Agent': user_agent})).read()
    token = next((c.value for c in jar if c.name == 'XSRF-TOKEN'), '')
    request = urllib.request.Request(
        base + '/api/auth/login',
        data=json.dumps({'loginId': login_id, 'password': password}).encode(),
        headers={'Content-Type': 'application/json', 'X-XSRF-TOKEN': token, 'User-Agent': user_agent},
        method='POST')
    try:
        with opener.open(request) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


class Output:
    """rich가 있으면 흐린 기록 + 상태 줄, 없으면 줄 출력"""

    def __init__(self, plain: bool, accounts: int, interval: float):
        self.counts = {'ok': 0, 'typo': 0, 'error': 0}
        self.accounts = accounts
        self.interval = interval
        self.console = None
        if not plain:
            try:
                from rich.console import Console

                self.console = Console()
            except ImportError:
                self.console = None

    def start(self) -> None:
        text = f'평소 로그인 — 직원 계정 {self.accounts}개 · 평균 {self.interval:.0f}초 간격 · Ctrl+C로 멈춤'
        if self.console:
            from rich.panel import Panel

            self.console.print(Panel(f'[{DIM}]{text}[/]', title=f'[{OK}]평소 업무[/]', border_style=OK))
        else:
            print(f'\033[2m{text}\033[0m')

    def line(self, kind: str, login_id: str, detail: str) -> None:
        self.counts[kind] += 1
        stamp = time.strftime('%H:%M:%S')
        status = f'로그인 {self.counts["ok"]}회 · 오타 {self.counts["typo"]}회'
        if self.console:
            style = {'ok': OK, 'typo': WARN, 'error': FAIL}[kind]
            mark = '✔' if kind == 'ok' else '✖'
            self.console.print(f'[{DIM}][평소] {stamp}[/]  [{style}]{login_id}  {mark} {detail}[/]   [{LINE}]{status}[/]')
        else:
            color = {'ok': OK, 'typo': WARN, 'error': FAIL}[kind]
            rgb = f'\033[38;2;{int(color[1:3], 16)};{int(color[3:5], 16)};{int(color[5:7], 16)}m'
            print(f'{rgb}[평소] {stamp} {login_id} {detail}\033[0m')


def main() -> int:
    parser = argparse.ArgumentParser(description='평소 로그인 — 시연 전에 켜 둔다.')
    parser.add_argument('--base-url', default='http://localhost:8081')
    parser.add_argument('--account', action='append', required=True, help='아이디:비밀번호 (여러 번)')
    parser.add_argument('--user-agent', default=EDGE_WINDOWS, help='평소 브라우저(기본 Edge · Windows)')
    parser.add_argument('--interval', type=float, default=20.0, help='로그인 사이 평균 초')
    parser.add_argument('--typo-rate', type=float, default=0.1, help='비밀번호 오타 비율 (실패 한 번 뒤 성공)')
    parser.add_argument('--plain', action='store_true', help='rich가 있어도 줄 출력으로')
    args = parser.parse_args()
    accounts = []
    for value in args.account:
        if ':' not in value:
            parser.error('--account는 아이디:비밀번호 모양이어야 합니다.')
        login_id, password = value.split(':', 1)
        accounts.append((login_id, password))

    if os.name == 'nt':
        os.system('')
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(errors='replace')
    base = args.base_url.rstrip('/')
    out = Output(args.plain, len(accounts), args.interval)
    out.start()
    try:
        while True:
            login_id, password = random.choice(accounts)
            try:
                if random.random() < args.typo_rate:
                    status = login(base, args.user_agent, login_id, password + 'x')
                    out.line('typo', login_id, f'비밀번호 오타 ({status})')
                    time.sleep(random.uniform(2, 5))
                status = login(base, args.user_agent, login_id, password)
                if status == 200:
                    out.line('ok', login_id, '로그인 (평소 브라우저)')
                else:
                    out.line('error', login_id, f'로그인 실패 ({status})')
            except urllib.error.URLError as error:
                out.line('error', '-', f'연결 실패: {error.reason}')
            time.sleep(random.uniform(args.interval * 0.5, args.interval * 1.5))
    except KeyboardInterrupt:
        print('평소 로그인 멈춤')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
