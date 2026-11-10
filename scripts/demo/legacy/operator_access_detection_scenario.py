#!/usr/bin/env python3
"""Run the local OUT_OF_ROLE threshold scenario for boss after starting backend on localhost:8081.

Repeat --path to rotate through several admin APIs, which shows up as a multi-feature probe on the detail screen:
  python3 scripts/demo/legacy/operator_access_detection_scenario.py \
    --path /api/admin/users --path /api/admin/merchants --path /api/admin/merchant-applications \
    --path /api/admin/case-assignments --path /api/fds/operator-access-detections
"""
import argparse
import http.cookiejar
import json
import sys
import time
import urllib.error
import urllib.request


def main() -> int:
    parser = argparse.ArgumentParser(description='Call a chosen API repeatedly with a chosen local account.')
    parser.add_argument('--base-url', default='http://localhost:8081')
    parser.add_argument('--login-id', default='boss')
    parser.add_argument('--password', default='1234')
    parser.add_argument(
        '--path',
        action='append',
        help='API path, beginning with /. Repeat to rotate through several paths (default: /api/admin/users).',
    )
    parser.add_argument('--method', default='GET', choices=['GET', 'POST', 'PUT', 'DELETE'])
    parser.add_argument('--count', type=int, default=5, help='number of denied calls to make')
    args = parser.parse_args()
    paths = args.path or ['/api/admin/users']
    if any(not path.startswith('/') for path in paths):
        parser.error('--path must begin with /.')
    if args.count < 1:
        parser.error('--count must be at least 1.')
    jar = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    base = args.base_url.rstrip('/')
    try:
        client.open(base + '/api/auth/csrf').read()
    except urllib.error.URLError as error:
        print(
            f'Cannot connect to {base}. Start the backend first: '
            'cd backend; .\\gradlew.bat bootRun "--args=--ai.ml-shadow.enabled=true"',
            file=sys.stderr,
        )
        print(f'Connection detail: {error.reason}', file=sys.stderr)
        return 2
    csrf = next((cookie.value for cookie in jar if cookie.name == 'XSRF-TOKEN'), None)
    if not csrf:
        raise RuntimeError('CSRF cookie was not issued by the backend.')
    request = urllib.request.Request(
        base + '/api/auth/login',
        data=json.dumps({'loginId': args.login_id, 'password': args.password}).encode(),
        headers={'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf},
        method='POST')
    client.open(request).read()
    csrf = next((cookie.value for cookie in jar if cookie.name == 'XSRF-TOKEN'), None)
    for attempt in range(1, args.count + 1):
        path = paths[(attempt - 1) % len(paths)]
        try:
            denied_request = urllib.request.Request(
                base + path,
                headers={'X-XSRF-TOKEN': csrf} if args.method != 'GET' and csrf else {},
                method=args.method,
            )
            client.open(denied_request).read()
            raise RuntimeError(f'Expected {args.method} {path} to be denied.')
        except urllib.error.HTTPError as error:
            if error.code != 403:
                raise
            print(f'{attempt}/{args.count}: {args.method} {path} received expected 403')
    time.sleep(1)
    try:
        client.open(base + '/api/auth/me').read()
    except urllib.error.HTTPError as error:
        if error.code == 401:
            print('PASS: threshold reached, account was suspended and current session expired.')
            return 0
        raise
    print('FAIL: session is still active. Check the P2 listener and database logs.', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
