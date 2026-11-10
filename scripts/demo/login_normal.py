#!/usr/bin/env python3
"""평소 로그인 (#770 크리덴셜 스터핑 시연 준비) — 직원들이 평소 브라우저로 드문드문 로그인하는 모습을 만든다.

시연 10분 전부터 켜 두면 로그인 보안 화면의 꺾은선 · 실시간 시도에 평소 모습(실패율 낮음)이 쌓인다.
직원의 "평소 브라우저" 기록도 함께 쌓인다(기본 Edge · Windows). 시연에서 탈취 역할 브라우저는 이것과 다른 종류로 쓴다.
가끔 비밀번호 오타(실패 한 번 뒤 성공)를 넣는다. Ctrl+C로 멈춘다.

  python scripts/demo/login_normal.py --account hanjiwoo:비밀번호 --account parkseoyeon:비밀번호
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

EDGE_WINDOWS = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
                'Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0')
DIM, RED, RESET = '\033[2m', '\033[91m', '\033[0m'


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


def main() -> int:
    parser = argparse.ArgumentParser(description='평소 로그인 — 시연 전에 켜 둔다.')
    parser.add_argument('--base-url', default='http://localhost:8081')
    parser.add_argument('--account', action='append', required=True, help='아이디:비밀번호 (여러 번)')
    parser.add_argument('--user-agent', default=EDGE_WINDOWS, help='평소 브라우저(기본 Edge · Windows)')
    parser.add_argument('--interval', type=float, default=20.0, help='로그인 사이 평균 초')
    parser.add_argument('--typo-rate', type=float, default=0.1, help='비밀번호 오타 비율 (실패 한 번 뒤 성공)')
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
    print(f'{DIM}평소 로그인 시작 — 계정 {len(accounts)}개, 평균 {args.interval:.0f}초 간격. Ctrl+C로 멈춤{RESET}')
    try:
        while True:
            login_id, password = random.choice(accounts)
            try:
                if random.random() < args.typo_rate:
                    status = login(base, args.user_agent, login_id, password + 'x')
                    print(f'{DIM}[평소] {time.strftime("%H:%M:%S")} {login_id} ✖ 비밀번호 오타 ({status}){RESET}')
                    time.sleep(random.uniform(2, 5))
                status = login(base, args.user_agent, login_id, password)
                mark = '✔ 로그인' if status == 200 else f'✖ 실패 ({status})'
                color = DIM if status == 200 else RED
                print(f'{color}[평소] {time.strftime("%H:%M:%S")} {login_id} {mark}{RESET}')
            except urllib.error.URLError as error:
                print(f'{RED}[평소] 연결 실패: {error.reason}{RESET}')
            time.sleep(random.uniform(args.interval * 0.5, args.interval * 1.5))
    except KeyboardInterrupt:
        print(f'{DIM}평소 로그인 멈춤{RESET}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
