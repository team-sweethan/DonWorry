# 로컬 시연 가이드 — 케이크샵 결제부터 매입 완료까지

관련: #279 · [ADR-0009](../decisions/0009-ai-investigation-call-structure.md) · [ADR-0041](../decisions/0041-ai-server-internal-api-key-authentication.md) · [CakeShop 키 준비](../identity/246-cakeshop-provisioning.md)

처음 시연하는 사람이 내 PC에서 돈워리의 전체 흐름을 재현하는 방법이다. 2026-10-28 `dev`(`e888960`)에서 이 순서대로 ACCEPT → 매입 완료(CAPTURED)까지 확인했다.

```
케이크샵 결제(P5) → FDS 평가(P2) → 보류 HELD(P5) → Case 생성(P3) → AI 보고서(P4)
→ 보안 직원 판정(P3) → 매입 요청(P5) → 매입·정산 예정금(P6) → 매입 완료 CAPTURED(P5) → 인계 완료(P3)
```

사람이 하는 일은 **결제 보내기**와 **판정 버튼 누르기** 두 가지뿐이다. 나머지는 서버가 이벤트로 자동 처리한다.

## 1. 시연 방식 고르기

| 방식 | 보이는 것 | 필요한 것 |
| --- | --- | --- |
| ① 시연 버튼 | Case · 고정 보고서 · 판정 화면 | backend · frontend |
| ② 실제 결제 + 고정 보고서 | ①에 더해 FDS 점수 · 실제 보류 · 매입 · 정산 | ① + 서명 키 + 결제 API 켜기 |
| ③ ② + ai-server mock | AI가 자료를 꺼내 보고 쓰는 보고서 연결 | ② + ai-server + AI 서버 키 |
| ④ ② + Qwen | 실제 LLM이 쓴 보고서 | ③ + Ollama · `qwen2.5:7b` |

- ①은 결제를 DB에 저장하지 않고 메모리로 만든다. FDS 평가 · 실제 매입 · 정산을 거치지 않는다.
- 처음이면 ① → ② → ③ → ④ 순서로 하나씩 늘리는 것을 권한다. 막혔을 때 어느 단계 문제인지 알기 쉽다.

## 2. 준비물

- [README Getting Started](../../README.md#getting-started)의 DB · backend · frontend 실행이 되는 상태
- 결제 스크립트용 Python 3.8 이상 (표준 라이브러리만 씀. Windows는 `py`, macOS · Linux는 `python3`)
- ③ · ④: ai-server를 띄울 Python 3.13 이상 (uv 또는 아나콘다, [7절](#7-자주-막히는-곳) 참고)
- ④: [Ollama](https://ollama.com)

포트: backend `8081` · frontend `5173` · ai-server `8000` · DB `3307` · Ollama `11434`

## 3. 처음 한 번 설정

### 3-1. CakeShop 서명 키

②·③·④에 필요하다. 결제 스크립트가 케이크샵 대신 HMAC 서명을 붙여 결제를 보낸다. Spring과 스크립트가 같은 키를 써야 한다.
P1 규칙상 이 키는 파일에 적지 않고 **OS 환경변수**로만 읽는다.

Windows PowerShell에서 한 번 실행해 사용자 환경변수로 저장한다.

```powershell
$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Environment]::SetEnvironmentVariable('DONWORRY_MERCHANT_HMAC_CAKESHOP', [Convert]::ToBase64String($b), 'User')
```

저장한 뒤 **터미널 프로그램을 완전히 닫고 다시 연다** (Windows Terminal은 창 전체, IntelliJ · VS Code 터미널은 프로그램 자체). 이미 열린 프로그램에는 새 환경변수가 보이지 않는다.
새 창에서 `[bool]$env:DONWORRY_MERCHANT_HMAC_CAKESHOP`이 `True`면 된다.

macOS · Linux는 셸 설정 파일(`~/.zshrc` 등)에 넣는다.

```bash
export DONWORRY_MERCHANT_HMAC_CAKESHOP="$(python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')"
```

### 3-2. AI 서버 키 만들기

③·④에 필요하다. Spring과 ai-server가 서로 확인하는 출입증이다 ([ADR-0041](../decisions/0041-ai-server-internal-api-key-authentication.md)). 서명 키와 다른 값으로 만든다.

```powershell
$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
-join ($b | ForEach-Object { $_.ToString('x2') })
```

출력된 64자리 값을 아래 두 파일에 **글자 하나까지 똑같이** 넣는다. 두 파일 모두 Git에 올라가지 않는다. 키를 채팅 · 이슈 · 커밋에 붙이지 않는다.

### 3-3. Spring 개인 설정 `application-local.yml`

`backend/src/main/resources/application-local.yml`에 아래를 추가한다. 파일이 없으면 [README 3절](../../README.md#3-개인-설정-필요할-때만)처럼 예시 파일을 복사해 만든다. 이미 `payment:`나 `ai:` 줄이 있으면 그 아래에 합친다 (같은 이름을 두 번 쓰면 기동 오류).

```yaml
payment:
  http:
    enabled: true            # ② 이상: CakeShop 서명 결제 API 켜기 (기본 꺼짐)
ai:
  server:
    enabled: true            # ③ 이상: 고정 보고서 대신 ai-server에 조사를 맡김. ②는 false
    api-key: "3-2에서 만든 값"
```

이 파일은 Spring을 켤 때 자동으로 읽는다. 창마다 `$env:`로 넣을 필요가 없다. 바꾼 뒤에는 Spring을 다시 켠다.

### 3-4. ai-server 설정 `ai-server/.env`

③·④에 필요하다.

```powershell
cd ai-server
copy .env.example .env       # macOS · Linux: cp .env.example .env
```

`.env`에서 두 줄을 채운다.

```dotenv
AI_SERVER_LLM_PROVIDER=mock
AI_SERVER_INTERNAL_API_KEY=3-2에서 만든 값
```

ai-server는 **실행한 폴더의** `.env`를 읽는다. 반드시 `ai-server` 폴더에서 실행한다.

## 4. 실행 순서

창을 3~4개 띄운다.

**창 1: backend**

```powershell
cd backend
.\gradlew.bat bootRun        # macOS · Linux: ./gradlew bootRun
```

`Started DonWorryApplication`이 보일 때까지 기다린다.

**창 2: frontend**

```powershell
cd frontend
npm run dev
```

**창 3: ai-server** (③ · ④만)

```powershell
cd ai-server
uv run uvicorn app.main:app --port 8000
```

`Uvicorn running on http://127.0.0.1:8000`이 보이고 `http://localhost:8000/health`가 `{"status":"ok"}`면 된다. Windows에서 "애플리케이션 제어 정책" 오류가 나면 [7절](#7-자주-막히는-곳)을 본다.

**창 4: 결제 보내기** (② · ③ · ④, 저장소 맨 위에서)

```powershell
py scripts/demo/cakeshop_pay.py        # macOS · Linux: python3 scripts/demo/cakeshop_pay.py
```

같은 손님이 60만 원을 3번 결제한다. 실행할 때마다 새 손님 번호를 써서 새 Case가 생긴다.

```
1번째  결제 ID 4  상태 CAPTURED  주문 CAKE-...-1
2번째  결제 ID 5  상태 CAPTURED  주문 CAKE-...-2
3번째  결제 ID 6  상태 HELD  주문 CAKE-...-3  ← 보류! 보안 직원 화면에 Case가 생겼어요
```

- 1 · 2번째: 고액 결제(50만 원 이상) +30 → 30점 ALLOW → 바로 승인 · 매입
- 3번째: 고액 결제 +30, 10분 안 3번째 결제 +30 → 60점 REVIEW → 승인 후 **HELD(보류)**
- 1 · 2번째 상태 이름은 처리 시점에 따라 다를 수 있다. 3번째가 `HELD`인지가 중요하다.

## 5. 화면에서 따라가기

`http://localhost:5173`에서 보안 직원 `staff_test` / `1234`로 로그인한다.

1. **경보** (`/security/alerts`) — 3번째 결제의 평가가 60점 REVIEW로 보인다. 상세에서 고액 결제 +30, 단시간 반복 결제 +30을 확인한다.
2. **Case 목록** (`/security/cases`) — 새 Case가 "판정 대기", AI 보고서 "생성 완료"(또는 작성 중)로 보인다. 고객 칸이 `가맹점ID:손님번호` 형식이다.
3. **Case 상세** — AI 조사 보고서를 읽는다. 어떤 보고서인지는 "7. AI 권고 → 한계" 줄로 구분한다.
   - ② 고정 보고서: "고정 문장 보고서입니다(AI 연결 전)"
   - ③ mock: "mock 조사입니다(Qwen 미사용)"
   - ④ Qwen: 두 문구가 모두 없고, 판단 문장이 결제 내용에 맞게 달라진다. "8. 증거 추적 정보"에 AI가 본 자료(`get_case_payments` · `get_fds_evidence`)가 남는다.
4. **판정** — 결제를 고르고 `ACCEPT · 매입 허용` 또는 `REJECT · 매입 거부`, 판정 사유를 적고 **판정 확정**을 누른다.
5. **결과** — 몇 초 안에 "금융 조치 인계"가 완료(ACCEPTED)가 되고 Case가 종료된다. 상세 화면은 2분 동안 자동으로 새로 고친다. 그 뒤에는 직접 새로고침한다.
   - ACCEPT: P5 매입 요청 → P6 매입 · 정산 예정금 → 결제 CAPTURED
   - REJECT: P5 승인 취소 → 결제 VOIDED (정산 없음)

①(시연 버튼)은 Case 목록의 **시연: HELD 결제 발생** 버튼으로 시작한다. 30만 원 · 65점 결제가 메모리에 생기고 바로 Case 상세로 이동한다. 판정 뒤에는 "인계 상태 다시 확인"을 눌러야 완료된다 ([프론트 README](../../frontend/README.md)).

## 6. DB로 확인하기

"거래 내역" 메뉴는 아직 빈 화면이라 결제 · 매입 · 정산은 DB 도구(HeidiSQL · IntelliJ Database 등)로 본다. `donworry` DB(포트 3307), 결제 ID는 스크립트 3번째 줄의 값을 넣는다.

```sql
-- P5 결제: ACCEPT면 CAPTURED, REJECT면 VOIDED
SELECT payment_id, status, amount FROM payments WHERE payment_id = 6;

-- P5 금융 조치
SELECT action_type, status, capture_id, completed_at FROM payment_actions WHERE payment_id = 6;

-- P6 매입 · 정산 예정금 (ACCEPT일 때만)
SELECT capture_id, status, capture_amount, captured_at FROM captures WHERE payment_id = 6;
SELECT settlement_id, status, gross_amount, settlement_amount FROM settlements WHERE payment_id = 6;

-- P3 인계: 최근 판정의 인계가 ACCEPTED면 끝
SELECT handoff_id, action_type, status, target_operation_id FROM action_handoffs ORDER BY handoff_id DESC LIMIT 1;

-- 처리되지 못하고 남은 이벤트 (정상이면 비어 있음)
SELECT listener_id, event_type, publication_date FROM EVENT_PUBLICATION WHERE completion_date IS NULL;
```

## 7. 자주 막히는 곳

**결제 스크립트**

- "환경변수가 없어요": 서명 키를 저장한 뒤 터미널을 새로 열지 않았다 ([3-1](#3-1-cakeshop-서명-키)).
- `401`: Spring과 스크립트의 서명 키가 다르다. 키를 저장하기 **전에** 켠 Spring이면 다시 켠다. PC 시계가 5분 이상 틀려도 401이다.
- 코드 없는 `404`: 결제 API가 꺼져 있다. `payment.http.enabled: true`를 넣고 Spring을 다시 켠다.
- `503 MERCHANT_AUTH_002`: 서버 쪽 서명 키가 없거나 Base64 형식이 아니다 ([CakeShop 키 준비](../identity/246-cakeshop-provisioning.md)).
- 연결 실패: Spring이 아직 다 켜지지 않았다.

**AI 보고서**

- 보고서가 고정 문장으로 나옴: `ai.server.enabled`가 꺼져 있다. 켜고 Spring을 다시 켠 뒤 **새 결제**를 보낸다. 이미 보고서가 있는 Case에서는 새 보고서가 생기지 않는다.
- ai-server 창에 아무 로그가 없음: Spring이 ai-server를 부르지 않았다. 위와 같은 원인이거나 `base-url`(기본 `http://localhost:8000`)이 다르다.
- 보고서가 "실패": 두 파일의 AI 서버 키가 다르다(ai-server가 Spring에 401로 막힘). ai-server는 키가 비어 있으면 조사 접수 자체를 거절한다.
- ④에서 5분 넘게 걸려 실패: PC가 느리다. `application-local.yml`의 `ai.server`에 `result-timeout: 10m`을 넣어 기다리는 시간을 늘린다.
- ④에서 `11434` 연결 오류: Ollama가 꺼져 있다. Ollama를 실행하고 `ollama pull qwen2.5:7b`로 모델을 받는다.
- Qwen 답에 중국어가 섞임: Qwen 2.5의 버릇이다. ai-server는 한국어 지시와 `temperature=0`으로 줄이지만 검사 장치는 아직 없다. 섞이면 화면을 남겨 P4에 알려 준다.

**Windows "애플리케이션 제어 정책에서 이 파일을 차단했습니다 (os error 4551)"**

스마트 앱 컨트롤 같은 Windows 보안 기능이 uv가 받은 서명 없는 Python이나 `uvicorn.exe`를 막은 것이다. 스마트 앱 컨트롤은 끄면 다시 켤 수 없으니 끄지 말고 아래 순서로 해결한다.

1. `uvicorn.exe` 대신 파이썬으로 실행한다.
   ```powershell
   uv run python -m uvicorn app.main:app --port 8000
   ```
2. 그래도 막히면 서명된 아나콘다 Python으로 가상환경을 다시 만든다 (Python 3.13 이상).
   ```powershell
   Remove-Item -Recurse -Force .venv
   $env:UV_PYTHON = (Get-Command python).Source
   uv run python -m uvicorn app.main:app --port 8000
   ```
3. 그래도 막히면 가상환경 없이 아나콘다에 바로 설치해 실행한다. 아나콘다 `(base)`의 라이브러리 버전이 바뀔 수 있다.
   ```powershell
   python -m pip install -e .
   python -m uvicorn app.main:app --port 8000
   ```

## 8. 한계

- 카드사 승인 · 취소와 매입은 모의 구현이라 늘 바로 성공한다. 실패 · 지연은 이 흐름으로 볼 수 없다.
- 단계 사이는 커밋 뒤 이벤트로 이어진다. 중간에 실패하면 `EVENT_PUBLICATION`에 미완료로 남고 자동 재발행은 아직 없다. 대기 중인 인계는 Case 상세의 "다시 확인"으로 P5 결과를 받아온다.
- AI는 Case에 묶인 결제와 FDS 근거만 본다. 같은 손님의 직전 결제 · 기기 · 로그인은 조회 도구가 없어 보고서에 "확인하지 않음"으로 적힌다.
- 실제 CakeShop 사이트에서 보내는 결제는 아직 연결 전이다. 스크립트는 CakeShop의 서명 방식([HMAC 계약](../identity/219-merchant-hmac.md))을 그대로 흉내 낸다.
