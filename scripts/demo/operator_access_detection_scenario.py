#!/usr/bin/env python3
"""Run the local OUT_OF_ROLE threshold scenario for boss after starting backend on localhost:8081."""
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
    parser.add_argument('--path', default='/api/admin/users', help='API path, beginning with /.')
    parser.add_argument('--method', default='GET', choices=['GET', 'POST', 'PUT', 'DELETE'])
    parser.add_argument('--count', type=int, default=5, help='number of denied calls to make')
    args = parser.parse_args()
    if not args.path.startswith('/'):
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
        try:
            denied_request = urllib.request.Request(
                base + args.path,
                headers={'X-XSRF-TOKEN': csrf} if args.method != 'GET' and csrf else {},
                method=args.method,
            )
            client.open(denied_request).read()
            raise RuntimeError(f'Expected {args.method} {args.path} to be denied.')
        except urllib.error.HTTPError as error:
            if error.code != 403:
                raise
            print(f'{attempt}/{args.count}: received expected 403')
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
