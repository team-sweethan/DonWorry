"""로컬 FDS 시연 스크립트가 보안 직원 세션·CSRF 토큰을 얻는 공통 코드."""

import http.cookiejar
import json
import urllib.parse
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener


def staff_session(base_url: str, login_id: str, password: str):
    jar = http.cookiejar.CookieJar()
    opener = build_opener(HTTPCookieProcessor(jar))

    def request(method: str, target: str, body: dict | None = None, csrf: bool = False):
        encoded = json.dumps(body, separators=(",", ":")).encode() if body is not None else None
        headers = {"Content-Type": "application/json"} if encoded is not None else {}
        if csrf:
            token = next((cookie.value for cookie in jar if cookie.name == "XSRF-TOKEN"), "")
            headers["X-XSRF-TOKEN"] = urllib.parse.unquote(token)
        try:
            response = opener.open(Request(base_url + target, encoded, method=method, headers=headers), timeout=30)
        except HTTPError as error:
            response = error
        raw = response.read().decode()
        try:
            data = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            data = {"raw": raw}
        return response.status, data

    status, data = request("POST", "/api/auth/login", {"loginId": login_id, "password": password})
    if status != 200:
        raise RuntimeError(f"직원 로그인 실패: HTTP {status} {data.get('code', data)}")
    status, data = request("GET", "/api/auth/csrf")
    if status != 204:
        raise RuntimeError(f"CSRF 토큰을 받지 못했습니다: HTTP {status} {data.get('code', data)}")
    if not any(cookie.name == "XSRF-TOKEN" for cookie in jar):
        raise RuntimeError("CSRF 토큰 쿠키가 없습니다.")
    return request
