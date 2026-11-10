# 시연 도구

용도별로 폴더를 나눴습니다 (#789). 명령은 모두 저장소 맨 위에서 실행합니다.

| 폴더 | 용도 | 지금 시연에 쓰는가 |
| --- | --- | --- |
| [`credential/`](credential/) | 크리덴셜 스터핑 시연 (#770) | 씁니다 |
| [`merchant/`](merchant/) | 새 가맹점 키 입력 → 연결 확인 → 테스트 결제. `MerchantIntegrationHttpMariaDbTests`가 실행합니다 | 연동 확인용 |
| [`legacy/`](legacy/) | 예전 결제 양식에 맞춘 도구 (서명 결제 · FDS 오류 · 운영자 접근 탐지) | 쓰지 않습니다 |

## credential — 크리덴셜 스터핑 시연

| 파일 | 하는 일 |
| --- | --- |
| `login_normal.py` | 직원들의 평소 로그인을 드문드문 만듭니다. 시연 10분 전부터 켜 둡니다 |
| `credential_stuffing.py` | 유출 목록을 계정마다 한 번씩 로그인 API에 넣습니다. `--reused`로 준 계정이 맞으면 멈춥니다 |
| `leaked_accounts.example.txt` | `credential_stuffing.py`가 기본으로 읽는 예시 유출 목록 |

```bash
uv run scripts/demo/credential/login_normal.py --account hanjiwoo:비밀번호 --account parkseoyeon:비밀번호
uv run scripts/demo/credential/credential_stuffing.py --reused hanjiwoo:비밀번호
```

`uv run`이면 꾸민 화면(rich)이 나오고, `python`으로 바로 실행하면 줄 출력으로 돌아갑니다.

## merchant — 가맹점 연동 확인

| 파일 | 하는 일 |
| --- | --- |
| `merchant_connect.py` | 시크릿 키를 입력받아 연결 확인 후 선택적으로 테스트 결제를 보냅니다. 표준 라이브러리만 씁니다 |
| `test_merchant_connect.py` | 위 스크립트의 서명 · 요청 모양 단위 테스트 |

사용법은 [가맹점 연동 안내](../../docs/identity/354-merchant-integration.md)에 있습니다.

- 백엔드 `MerchantIntegrationHttpMariaDbTests`가 `../scripts/demo/merchant/merchant_connect.py`를 직접 실행합니다. 옮기거나 이름을 바꾸면 그 테스트도 함께 고칩니다
- 단위 테스트: `python -m pytest scripts/demo/merchant`

## legacy — 예전 결제 양식 도구

지금 시연에는 쓰지 않습니다. 예전 문서와 기록이 가리키는 위치를 남기려고 지우지 않고 옮겼습니다.

| 파일 | 하는 일 |
| --- | --- |
| `cakeshop_pay.py` | 가맹점 서명 결제(`POST /api/payments`)를 직접 보냅니다. [로컬 시연 가이드](../../docs/demo/local-demo.md)가 안내합니다 |
| `fds_error.py` · `fds_reevaluate.py` · `fds_demo_session.py` | FDS 오류 결제를 만들고 재평가합니다 (#418). `fds_demo_session.py`는 앞의 두 스크립트가 불러 쓰는 직원 로그인 도우미입니다 |
| `operator_access_detection_scenario.py` · `operator_access_detection_reset.py` | 운영자 반복 권한 위반 탐지 장면을 만들고 지웁니다 (#526) |

결정 기록(ADR-0019 · 0063 · 0064)에는 옮기기 전 경로(`scripts/demo/cakeshop_pay.py`)가 그대로 적혀 있습니다. 결정 당시 기록이라 고치지 않았습니다. 지금 위치는 `scripts/demo/legacy/`입니다.
