# 로컬 시연 가이드 — 케이크샵 결제부터 매입 완료까지

내 PC에서 돈워리의 전체 흐름을 재현하는 방법입니다. 2026-10-28 `dev`(`e888960`)에서 이 순서대로 ACCEPT → 매입 완료(CAPTURED)까지 확인했습니다.

관련: #279 · #289 · [ADR-0009](../decisions/0009-ai-investigation-call-structure.md) · [ADR-0041](../decisions/0041-ai-server-internal-api-key-authentication.md) · [CakeShop 키 준비](../identity/246-cakeshop-provisioning.md)

## 0. 한눈에 보기

```
케이크샵 결제(P5) → FDS 평가(P2) → 보류 HELD(P5) → Case 생성(P3) → AI 보고서(P4)
→ 보안 직원 판정(P3) → 매입 요청(P5) → 매입·정산 예정금(P6) → 매입 완료 CAPTURED(P5) → 인계 완료(P3)
```

사람이 하는 일은 **결제 보내기**와 **판정 버튼 누르기** 두 가지뿐이고, 나머지는 서버가 자동으로 처리합니다.

**보고 싶은 것에 따라 절을 고르세요.** 뒤 절은 앞 절을 끝낸 상태에서 "추가로 할 일"만 적었습니다.

- 화면만 빨리 보고 싶다 → [1. 5분 확인](#1-5분-확인-시연-버튼)
- FDS 점수 · 보류 · 매입 · 정산까지 실제로 보고 싶다 → [2. 실제 결제부터 매입까지](#2-실제-결제부터-매입까지) ← **대부분 여기서 시작**
- AI 보고서가 만들어지는 연결까지 → 2절 다음 [3. AI 보고서 붙이기](#3-ai-보고서-붙이기-mock)
- 진짜 LLM(Qwen)이 쓴 보고서까지 → 3절 다음 [4. Qwen으로 바꾸기](#4-qwen으로-바꾸기)

시작 전 준비: [README Getting Started](../../README.md#getting-started)대로 DB · backend · frontend가 켜지는 상태여야 합니다. 명령은 Windows PowerShell 기준이고, macOS · Linux는 [7-1](#7-1-macos--linux-명령)을 봅니다.

## 1. 5분 확인: 시연 버튼

결제를 DB에 저장하지 않고 메모리로 만드는 연습용 버튼입니다. FDS 평가 · 실제 매입 · 정산은 거치지 않습니다.

1. **창 1** `backend` 폴더에서 `.\gradlew.bat bootRun` → ✅ `Started DonWorryApplication`
2. **창 2** `frontend` 폴더에서 `npm run dev`
3. `http://localhost:5173`에서 보안 직원 `staff_test` / `1234`로 로그인
4. **Case 목록**의 **시연: HELD 결제 발생** 버튼 → 30만 원 · 65점 결제가 생기고 Case 상세로 이동
5. 고정 문장 보고서를 확인하고 판정 → "인계 상태 다시 확인"을 누르면 완료 ([프론트 README](../../frontend/README.md))

## 2. 실제 결제부터 매입까지

케이크샵 대신 스크립트로 서명한 결제를 보내고, FDS 점수 → 보류 → Case → 판정 → 매입 · 정산까지 봅니다.
이 절에서 AI 보고서는 고정 문장으로 나옵니다.

### 2-1. 처음 한 번만

**① 케이크샵 서명 키 저장** — PowerShell 아무 창에서

```powershell
$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Environment]::SetEnvironmentVariable('DONWORRY_MERCHANT_HMAC_CAKESHOP', [Convert]::ToBase64String($b), 'User')
```

그다음 **터미널 프로그램을 완전히 닫고 다시 엽니다.** Windows Terminal은 창 전체를, IntelliJ · VS Code 안의 터미널은 프로그램 자체를 껐다 켭니다.

✅ 새 창에서 `[bool]$env:DONWORRY_MERCHANT_HMAC_CAKESHOP` → `True`

<details>
<summary>왜 파일이 아니라 환경변수에 저장하나요?</summary>

결제 스크립트(케이크샵 역할)와 Spring(돈워리)이 **같은 키**로 서명을 만들고 확인해야 합니다. P1 규칙상 이 키는 파일에 적지 않고 OS 환경변수로만 읽습니다 ([CakeShop 키 준비](../identity/246-cakeshop-provisioning.md)). 이미 열려 있던 프로그램에는 새 환경변수가 보이지 않아서 터미널을 다시 열어야 합니다.

</details>

**② 결제 API 켜기** — `backend/src/main/resources/application-local.yml`에 추가

```yaml
payment:
  http:
    enabled: true            # CakeShop 서명 결제 API 켜기 (기본 꺼짐)
```

- 파일이 없으면 [README 3절](../../README.md#3-개인-설정-필요할-때만)처럼 `application-local.yml.example`을 복사해 만듭니다.
- 이미 `payment:` 줄이 있으면 그 아래에 합칩니다. 같은 이름을 두 번 쓰면 Spring이 켜지지 않습니다.
- 이 파일은 Spring을 켤 때 자동으로 읽습니다. 바꾼 뒤에는 Spring을 다시 켭니다.

### 2-2. 매번 실행

| 창 | 폴더 | 명령 | 이렇게 보이면 성공 |
| --- | --- | --- | --- |
| 1 | `backend` | `.\gradlew.bat bootRun` | `Started DonWorryApplication` |
| 2 | `frontend` | `npm run dev` | `http://localhost:5173` |
| 3 | 저장소 맨 위 | `py scripts/demo/cakeshop_pay.py` | 3번째 줄에 `HELD` |

창 3은 창 1이 다 켜진 뒤에 실행합니다. 결과는 이렇게 나옵니다.

```
1번째  결제 ID 4  상태 CAPTURED  주문 CAKE-...-1
2번째  결제 ID 5  상태 CAPTURED  주문 CAKE-...-2
3번째  결제 ID 6  상태 HELD  주문 CAKE-...-3  ← 보류! 보안 직원 화면에 Case가 생겼어요
```

- 같은 손님이 60만 원을 3번 결제합니다. 실행할 때마다 새 손님 번호를 써서 새 Case가 생깁니다.
- 1 · 2번째: 고액 결제(50만 원 이상) +30 → 30점 ALLOW → 바로 승인 · 매입
- 3번째: 고액 결제 +30, 10분 안 3번째 결제 +30 → 60점 REVIEW → 승인 후 **HELD(보류)**
- 1 · 2번째 상태 이름은 처리 시점에 따라 다를 수 있습니다. 3번째가 `HELD`인지가 중요합니다.

이어서 [5. 결과 확인](#5-결과-확인)의 순서대로 화면을 봅니다.

## 3. AI 보고서 붙이기 (mock)

2절을 끝낸 상태에서, 고정 문장 대신 ai-server가 자료를 꺼내 보고 쓴 보고서가 오게 연결합니다. mock은 LLM 없이 정해진 규칙으로 답하는 연습 모드라 Ollama가 필요 없습니다.

준비: ai-server를 띄울 Python 3.13 이상(uv 또는 아나콘다)

### 3-1. 처음 한 번만

**① AI 서버 키 만들기** — Spring과 ai-server가 서로 확인하는 출입증입니다. 서명 키와 다른 값입니다.

```powershell
$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
-join ($b | ForEach-Object { $_.ToString('x2') })
```

출력된 64자리 값을 아래 ② · ③ **두 파일에 글자 하나까지 똑같이** 넣습니다. 두 파일 모두 Git에 올라가지 않습니다. 키를 채팅 · 이슈 · 커밋에 붙이지 않습니다.

**② Spring 쪽** — 2절에서 만든 `application-local.yml`에 추가

```yaml
ai:
  server:
    enabled: true            # 고정 보고서 대신 ai-server에 조사를 맡김 (기본 꺼짐)
    api-key: "①에서 만든 값"
```

이미 `ai:` 줄이 있으면 그 아래에 합칩니다. 바꾼 뒤 Spring을 다시 켭니다.

**③ ai-server 쪽** — `ai-server` 폴더에서 설정 파일을 만들고 두 줄을 채움

```powershell
cd ai-server
copy .env.example .env
```

```dotenv
AI_SERVER_LLM_PROVIDER=mock
AI_SERVER_INTERNAL_API_KEY=①에서 만든 값
```

`AI_SERVER_LLM_PROVIDER`는 어떤 AI가 보고서를 쓸지 정하는 스위치입니다. 이 절은 `mock`, 4절에서 `ollama`로 바꿉니다.

### 3-2. 매번 실행

2절의 창 1 · 2에 **창 4**를 하나 더 띄웁니다.

| 창 | 폴더 | 명령 | 이렇게 보이면 성공 |
| --- | --- | --- | --- |
| 4 | `ai-server` | `uv run uvicorn app.main:app --port 8000` | `Uvicorn running on http://127.0.0.1:8000` |

- 반드시 `ai-server` 폴더에서 실행합니다. ai-server는 **실행한 폴더의** `.env`를 읽습니다.
- ✅ `http://localhost:8000/health` → `{"status":"ok"}`
- "애플리케이션 제어 정책에서 이 파일을 차단했습니다"가 나오면 [7-2](#7-2-windows에서-ai-server-실행이-막힐-때)를 봅니다.

그다음 창 3에서 **새 결제**를 보냅니다(`py scripts/demo/cakeshop_pay.py`). 이미 보고서가 있는 Case에는 새 보고서가 생기지 않습니다.
✅ 창 4에 `POST /investigations ... 202`가 찍히고, 보고서 한계 줄에 "mock 조사입니다"가 보입니다.

**"고객 최근 결제" 카드 확인 (#338)** — 스크립트는 매번 새 손님으로 결제해서 이 카드가 늘 "결제 없음"입니다. 같은 손님의 지난 결제를 보려면:
1. 방금 생긴 Case에서 결제 하나라도 판정합니다 (판정이 있는 Case에는 새 결제가 병합되지 않습니다)
2. 스크립트 끝에 찍힌 손님 번호로 다시 보냅니다: `py scripts/demo/cakeshop_pay.py <손님 번호>`
3. 새 Case의 보고서 전문에 첫 실행의 결제가 "고객 최근 결제"로 보입니다 (이 사건 결제는 빠짐)

## 4. Qwen으로 바꾸기

3절을 끝낸 상태에서, 보고서를 실제 LLM(Qwen)이 쓰게 바꿉니다.

1. [Ollama](https://ollama.com)를 설치 · 실행하고 모델을 받습니다 (처음 한 번, 약 4.7GB).
   ```powershell
   ollama pull qwen2.5:7b
   ```
2. `ai-server/.env`의 첫 줄을 바꿉니다.
   ```dotenv
   AI_SERVER_LLM_PROVIDER=ollama
   ```
3. 창 4에서 `Ctrl + C`로 ai-server를 끄고 다시 켭니다. `.env`는 켤 때 한 번만 읽습니다.
4. 창 3에서 **새 결제**를 보냅니다.

✅ 보고서 한계 줄에 "mock 조사입니다"가 없고, 판단 문장이 결제 내용에 맞게 달라집니다. PC 성능에 따라 보고서 하나에 1~3분 걸립니다.

## 5. 결과 확인

### 5-1. 화면에서

`http://localhost:5173`에서 보안 직원 `staff_test` / `1234`로 로그인합니다.

1. **경보** (`/security/alerts`) — 3번째 결제의 평가가 60점 REVIEW로 보입니다. 상세에서 고액 결제 +30, 단시간 반복 결제 +30을 확인합니다.
2. **Case 목록** (`/security/cases`) — 새 Case가 "판정 대기", AI 보고서 "생성 완료"(또는 작성 중)로 보입니다. 고객 칸은 `가맹점ID:손님번호` 형식입니다.
3. **Case 상세** — AI 조사 보고서를 읽습니다. 어떤 보고서인지는 "7. AI 권고 → 한계" 줄로 구분합니다.
   - 고정 보고서(2절): "고정 문장 보고서입니다(AI 연결 전)"
   - mock(3절): "mock 조사입니다(Qwen 미사용)"
   - Qwen(4절): 두 문구가 모두 없습니다. "8. 증거 추적 정보"에 AI가 본 자료(`get_case_payments` · `get_fds_evidence`)가 남습니다.
4. **판정** — 결제를 고르고 `ACCEPT · 매입 허용` 또는 `REJECT · 매입 거부`, 판정 사유를 적고 **판정 확정**을 누릅니다.
5. **결과** — 몇 초 안에 "금융 조치 인계"가 완료(ACCEPTED)되고 Case가 종료됩니다. 상세 화면은 2분 동안 자동으로 새로 고치고, 그 뒤에는 직접 새로고침합니다.
   - ACCEPT: P5 매입 요청 → P6 매입 · 정산 예정금 → 결제 CAPTURED
   - REJECT: P5 승인 취소 → 결제 VOIDED (정산 없음)

### 5-2. DB에서

"거래 내역" 메뉴는 아직 빈 화면이라 결제 · 매입 · 정산은 DB 도구(HeidiSQL · IntelliJ Database 등)로 봅니다. `donworry` DB(포트 3307)에서, 결제 ID에는 스크립트 3번째 줄의 값을 넣습니다.

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

## 6. 막혔을 때

화면이나 창에 보이는 증상으로 찾습니다.

**결제 스크립트 (창 3)**

- **"환경변수가 없어요"** → 서명 키를 저장한 뒤 터미널을 새로 열지 않았습니다. [2-1 ①](#2-1-처음-한-번만)을 다시 확인합니다.
- **`401`** → Spring과 스크립트의 서명 키가 다릅니다. 키를 저장하기 **전에** 켠 Spring이면 다시 켭니다. PC 시계가 5분 이상 틀려도 401입니다.
- **코드 없는 `404`** → 결제 API가 꺼져 있습니다. `payment.http.enabled: true`를 넣고([2-1 ②](#2-1-처음-한-번만)) Spring을 다시 켭니다.
- **`503 MERCHANT_AUTH_002`** → 서버 쪽 서명 키가 없거나 Base64 형식이 아닙니다 ([CakeShop 키 준비](../identity/246-cakeshop-provisioning.md)).
- **연결 실패** → Spring이 아직 다 켜지지 않았습니다.

**AI 보고서**

- **3절인데 보고서가 고정 문장** → `ai.server.enabled`가 꺼져 있습니다. 켜고 Spring을 다시 켠 뒤 **새 결제**를 보냅니다.
- **창 4(ai-server)에 아무 로그가 없음** → Spring이 ai-server를 부르지 않았습니다. 위와 같은 원인이거나 `base-url`(기본 `http://localhost:8000`)이 다릅니다.
- **보고서가 "실패"** → 두 파일의 AI 서버 키가 다릅니다(ai-server가 Spring에 401로 막힘). ai-server는 키가 비어 있으면 조사 접수 자체를 거절합니다.
- **4절인데 "mock 조사입니다"가 보임** → `ai-server/.env`가 아직 `mock`입니다. `ollama`로 바꾸고 ai-server를 다시 켠 뒤 새 결제를 보냅니다. 창에서 `$env:AI_SERVER_LLM_PROVIDER`를 넣었다면 그 값이 `.env`보다 우선합니다.
- **4절에서 5분 넘게 걸려 실패** → PC가 느립니다. `application-local.yml`의 `ai.server`에 `result-timeout: 10m`을 넣어 기다리는 시간을 늘립니다.
- **4절에서 `11434` 연결 오류** → Ollama가 꺼져 있습니다. Ollama를 실행하고 `ollama pull qwen2.5:7b`로 모델을 받습니다.
- **Qwen 답에 중국어가 섞임** → Qwen 2.5의 버릇입니다. 한국어 지시와 `temperature=0`으로 줄이지만 검사 장치는 아직 없습니다. 섞이면 화면을 남겨 P4에 알려 줍니다.

**ai-server가 안 켜짐**

- **"애플리케이션 제어 정책에서 이 파일을 차단했습니다 (os error 4551)"** → [7-2](#7-2-windows에서-ai-server-실행이-막힐-때)

## 7. 부록

### 7-1. macOS · Linux 명령

PowerShell 명령 대신 아래를 씁니다.

- backend 실행: `./gradlew bootRun`
- 결제 스크립트: `python3 scripts/demo/cakeshop_pay.py` (Python 3.8 이상, 표준 라이브러리만 씀)
- ai-server 설정 복사: `cp .env.example .env`
- AI 서버 키 만들기: `python3 -c 'import secrets; print(secrets.token_hex(32))'`
- 케이크샵 서명 키 저장: 키를 **한 번만** 만들어 그 값을 셸 설정 파일에 붙입니다. **한 번만** 실행합니다.
  ```bash
  echo "export DONWORRY_MERCHANT_HMAC_CAKESHOP=\"$(python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')\"" >> ~/.zshrc
  ```
  ✅ 터미널을 새로 열고 `[ -n "$DONWORRY_MERCHANT_HMAC_CAKESHOP" ] && echo ok` → `ok`

<details>
<summary>왜 <code>export ...="$(python3 ...)"</code>를 설정 파일에 그대로 넣으면 안 되나요?</summary>

키를 만드는 명령 자체를 설정 파일에 넣으면 터미널을 열 때마다 새 키가 생깁니다. 그러면 Spring 창과 결제 스크립트 창의 키가 달라져 결제가 401로 거절됩니다. 위 명령은 실행하는 순간 한 번 만든 값을 그대로 파일에 적습니다.

</details>

### 7-2. Windows에서 ai-server 실행이 막힐 때

"애플리케이션 제어 정책에서 이 파일을 차단했습니다 (os error 4551)"는 스마트 앱 컨트롤 같은 Windows 보안 기능이 uv가 받은 서명 없는 Python이나 `uvicorn.exe`를 막은 것입니다. 스마트 앱 컨트롤은 끄면 다시 켤 수 없으니 끄지 말고, `ai-server` 폴더에서 아래를 차례로 시도합니다.

1. `uvicorn.exe` 대신 파이썬으로 실행합니다.
   ```powershell
   uv run python -m uvicorn app.main:app --port 8000
   ```
2. 그래도 막히면 서명된 아나콘다 Python으로 가상환경을 다시 만듭니다 (Python 3.13 이상).
   ```powershell
   Remove-Item -Recurse -Force .venv
   $env:UV_PYTHON = (Get-Command python).Source
   uv run python -m uvicorn app.main:app --port 8000
   ```
3. 그래도 막히면 가상환경 없이 아나콘다에 바로 설치해 실행합니다. 아나콘다 `(base)`의 라이브러리 버전이 바뀔 수 있습니다.
   ```powershell
   python -m pip install -e .
   python -m uvicorn app.main:app --port 8000
   ```

### 7-3. 설정이 저장되는 곳

| 설정 | 저장 위치 | 읽는 쪽 | 쓰는 절 |
| --- | --- | --- | --- |
| 케이크샵 서명 키 `DONWORRY_MERCHANT_HMAC_CAKESHOP` | OS 사용자 환경변수 | Spring · 결제 스크립트 | 2절 |
| 결제 API 켜기 `payment.http.enabled` | `backend/src/main/resources/application-local.yml` | Spring | 2절 |
| AI 서버 켜기 · 키 `ai.server.enabled` · `ai.server.api-key` | 같은 `application-local.yml` | Spring | 3절 |
| AI 서버 키 · AI 종류 `AI_SERVER_INTERNAL_API_KEY` · `AI_SERVER_LLM_PROVIDER` | `ai-server/.env` | ai-server | 3 · 4절 |

포트: backend `8081` · frontend `5173` · ai-server `8000` · DB `3307` · Ollama `11434`

### 7-4. 한계

- 카드사 승인 · 취소와 매입은 모의 구현이라 늘 바로 성공합니다. 실패 · 지연은 이 흐름으로 볼 수 없습니다.
- 단계 사이는 커밋 뒤 이벤트로 이어집니다. 중간에 실패하면 `EVENT_PUBLICATION`에 미완료로 남고 자동 재발행은 아직 없습니다. 대기 중인 인계는 Case 상세의 "다시 확인"으로 P5 결과를 받아옵니다.
- AI는 Case에 묶인 결제와 FDS 근거만 봅니다. 같은 손님의 직전 결제 · 기기 · 로그인은 조회 도구가 없어 보고서에 "확인하지 않음"으로 적힙니다.
- 실제 CakeShop 사이트에서 보내는 결제는 아직 연결 전입니다. 스크립트는 CakeShop의 서명 방식([HMAC 계약](../identity/219-merchant-hmac.md))을 그대로 흉내 냅니다.
