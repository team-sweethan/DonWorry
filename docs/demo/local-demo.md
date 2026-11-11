# 로컬 시연 가이드 — 케이크샵 결제부터 매입 완료까지

로컬 개발 환경에서 **서명 결제 → HELD → 담당 배정 → 조사 보고서 → 직원 ACCEPT → CAPTURED → 가맹점 결과 → 정산 예정**을 같은 식별자로 확인하는 가이드입니다. 현재 절차는 2026-11-09 `478fb941` 코드와 대조했습니다. 과거 `e888960`에서 확인한 매입 결과는 현재 버전의 검증 결과가 아닙니다. 이번 실행 상태는 마지막의 실행 기록에 구분합니다. 이후 사장님 내부 요약 비공개 변경과 재검증은 [접근 통제 검증 기록](security-access-checklist.md)을 따릅니다.

관련: #279 · #289 · [ADR-0009](../decisions/0009-ai-investigation-call-structure.md) · [ADR-0041](../decisions/0041-ai-server-internal-api-key-authentication.md) · [CakeShop 키 준비](../identity/246-cakeshop-provisioning.md)

## 0. 한눈에 보기

```
케이크샵 결제(P5) → FDS 평가(P2) → 보류 HELD(P5) → Case 생성(P3) → AI 보고서(P4)
→ 보안 직원 판정(P3) → 매입 요청(P5) → 매입·정산 예정금(P6) → 매입 완료 CAPTURED(P5) → 인계 완료(P3)
```

가맹점은 자기 결제의 결과와 정산 예정을 확인하고, 관리자는 미배정 사건을 배정하며, 담당 보안 직원이 증거를 읽고 최종 판정합니다. AI는 조사 자료를 정리하며 결제 판정을 대신하지 않습니다.

**보고 싶은 것에 따라 절을 고르세요.** 뒤 절은 앞 절을 끝낸 상태에서 "추가로 할 일"만 적었습니다.

- 예전 "시연: HELD 결제 발생" 버튼을 찾는다 → [1. 시연 버튼은 없어졌습니다](#1-시연-버튼은-없어졌습니다)
- FDS 점수 · 보류 · 매입 · 정산까지 실제로 보고 싶다 → [2. 실제 결제부터 매입까지](#2-실제-결제부터-매입까지) ← **대부분 여기서 시작**
- AI 보고서가 만들어지는 연결까지 → 2절 다음 [3. AI 보고서 붙이기](#3-ai-보고서-붙이기-mock)
- 진짜 LLM(Qwen)이 쓴 보고서까지 → 3절 다음 [4. Qwen으로 바꾸기](#4-qwen으로-바꾸기)

시작 전 준비: [README Getting Started](../../README.md#getting-started)대로 DB · backend · frontend가 켜지는 상태여야 합니다. 명령은 Windows PowerShell 기준이고, macOS · Linux는 [7-1](#7-1-macos--linux-명령)을 봅니다.

## 1. 시연 버튼은 없어졌습니다

Case 목록의 **시연: HELD 결제 발생** 버튼과 Case 상세의 **시연: 결제 병합** 버튼, 이 버튼이 부르던 `POST /api/demo/held-payments`는 #163 2단계에서 지웠습니다. 메모리 결제라 FDS 평가 · 실제 매입을 거치지 않아 실제 흐름과 달랐기 때문입니다.

HELD Case는 [2절](#2-실제-결제부터-매입까지)의 결제 스크립트(`scripts/demo/legacy/cakeshop_pay.py`)로 만듭니다. 병합 장면은 판정 전에 같은 손님 번호로 다시 보냅니다(`py scripts/demo/legacy/cakeshop_pay.py <손님 번호>`). 새로 보류된 결제가 있으면 병합 기한(30분) 안에서 같은 Case에 붙습니다.

## 2. 실제 결제부터 매입까지

케이크샵 대신 스크립트로 서명한 결제를 보내고, FDS 점수 → 보류 → Case → 판정 → 매입 · 정산까지 봅니다.
이 절에서 AI 보고서는 고정 문장으로 나옵니다.

### 2-3. FDS ERROR · 재평가 시연

경보 화면에는 Mock 생성 버튼이 없습니다. 로컬에서만 ERROR와 재평가를 확인하려면
`application-local.yml`에 아래 설정을 추가한 뒤 backend를 다시 시작합니다. 이 설정은 다음 FDS
평가 **한 건만** 선택한 ERROR 시나리오로 만들며, 운영 프로필에서는 동작하지 않습니다.

```yaml
fds:
  demo-error:
    enabled: true
```

저장소 루트에서 실행합니다. 두 스크립트 모두 `SECURITY_STAFF` 세션과 CSRF 토큰을 사용하므로
직원 비밀번호를 입력해야 합니다.

```powershell
py scripts/demo/legacy/fds_error.py
# 기본: 평가 시간 초과 → P2 분석 후 재평가 가능
# 룰 정보 일시 조회 실패 → P2 분석 후 재평가 가능
py scripts/demo/legacy/fds_error.py --scenario TRANSIENT_FDS_RULE_DATA_UNAVAILABLE
# 비일시 평가 처리 실패 → P2 분석 후 수동 검토 필요
py scripts/demo/legacy/fds_error.py --scenario FDS_EVALUATION_PROCESSING_FAILED
# 출력된 paymentId를 사용
py scripts/demo/legacy/fds_reevaluate.py <paymentId>
```

첫 스크립트는 다음 FDS 평가를 무장한 뒤 실제 CakeShop 서명 결제를 보내 ERROR 복구 기록을 만듭니다.
두 번째 스크립트는 실제 직원 재평가 API를 호출합니다. 시연이 끝나면 `enabled: false`로 바꾸고 backend를
다시 시작합니다.

### 2-1. 처음 한 번만

**① 기존 키와 연동 상태 먼저 확인**

현재 키 관리는 [ADR-0052](../decisions/0052-merchant-key-management.md)와 [연동 안내](../identity/354-merchant-integration.md)를 따릅니다. 환경변수에 키가 있다는 사실만으로 결제 준비가 끝난 것은 아닙니다. CAKESHOP이 ACTIVE이고 DB의 키와 스크립트의 키가 일치하며 CONNECTED여야 합니다. 사장님 `/owner/integration`에서 상태를 확인합니다. 미이전 레거시 키는 503, 연결 확인 전 신규 결제는 403일 수 있습니다.

기존 환경에서는 키를 덮어쓰거나 재발급하지 않습니다. 레거시 이전이나 새 키 발급이 필요하면 위 문서의 백업·마스터 키·연결 확인 절차를 별도로 수행한 뒤 돌아옵니다. 키 원문은 실행 기록·화면 캡처·Git에 남기지 않습니다.

아래는 **기존 키가 없는 신규 로컬 환경의 키 생성 예시**입니다. 이 명령만으로 현재 DB의 발급·이전·연결 확인을 대체하지 않습니다.

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
    enabled: true # CakeShop 서명 결제 API 켜기 (기본 꺼짐)
```

- 파일이 없으면 [README 3절](../../README.md#3-개인-설정-필요할-때만)처럼 `application-local.yml.example`을 복사해 만듭니다.
- 이미 `payment:` 줄이 있으면 그 아래에 합칩니다. 같은 이름을 두 번 쓰면 Spring이 켜지지 않습니다.
- 이 파일은 Spring을 켤 때 자동으로 읽습니다. 바꾼 뒤에는 Spring을 다시 켭니다.

### 2-2. 매번 실행

| 창  | 폴더         | 명령                              | 이렇게 보이면 성공            |
| --- | ------------ | --------------------------------- | ----------------------------- |
| 1   | `backend`    | `.\gradlew.bat bootRun`           | `Started DonWorryApplication` |
| 2   | `frontend`   | `npm run dev`                     | `http://localhost:5173`       |
| 3   | 저장소 맨 위 | `py scripts/demo/legacy/cakeshop_pay.py` | 3번째 줄에 `HELD`             |

창 3은 창 1이 다 켜진 뒤에 실행합니다. 결과는 이렇게 나옵니다.

```
1번째  결제 ID 4  상태 CAPTURED  주문 CAKE-...-1
2번째  결제 ID 5  상태 CAPTURED  주문 CAKE-...-2
3번째  결제 ID 6  상태 HELD  주문 CAKE-...-3  ← 보류! 보안 직원 화면에 Case가 생겼어요
```

- 같은 손님이 60만 원을 3번 결제합니다. 첫 실행에서는 새 손님 번호를 사용합니다. 주문 UUID와 Idempotency-Key도 매번 새로 생성되므로 재실행은 기존 거래 재조회가 아니라 **새 거래 3건 추가**입니다. 아래 점수는 기본 규칙 예시이며 활성 룰·누적 이력에 따라 실제 결과가 달라집니다.
- 1 · 2번째: 고액 결제(50만 원 이상) +30 → 30점 ALLOW → 바로 승인 · 매입
- 3번째: 고액 결제 +30, 10분 안 3번째 결제 +30 → 60점 REVIEW → 승인 후 **HELD(보류)**
- 1 · 2번째 상태 이름은 처리 시점에 따라 다를 수 있습니다. 3번째가 `HELD`인지가 중요합니다.

이어서 [5. 대표 경로 확인](#5-대표-경로-확인)의 순서대로 화면을 봅니다.

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
    enabled: true # 고정 보고서 대신 ai-server에 조사를 맡김 (기본 꺼짐)
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

| 창  | 폴더        | 명령                                      | 이렇게 보이면 성공                         |
| --- | ----------- | ----------------------------------------- | ------------------------------------------ |
| 4   | `ai-server` | `uv run uvicorn app.main:app --port 8000` | `Uvicorn running on http://127.0.0.1:8000` |

- 반드시 `ai-server` 폴더에서 실행합니다. ai-server는 **실행한 폴더의** `.env`를 읽습니다.
- ✅ `http://localhost:8000/health` → `{"status":"ok"}`
- "애플리케이션 제어 정책에서 이 파일을 차단했습니다"가 나오면 [7-2](#7-2-windows에서-ai-server-실행이-막힐-때)를 봅니다.

그다음 창 3에서 **새 결제**를 보냅니다(`py scripts/demo/legacy/cakeshop_pay.py`). 이미 보고서가 있는 Case에는 새 보고서가 생기지 않습니다.
✅ 창 4에 `POST /investigations ... 202`가 찍히고, 보고서 한계 줄에 "mock 조사입니다"가 보입니다.

**"고객 최근 결제" 카드 확인 (#338)** — 스크립트는 매번 새 손님으로 결제해서 이 카드가 늘 "결제 없음"입니다. 같은 손님의 지난 결제를 보려면:

1. 방금 생긴 Case에서 결제 하나라도 판정합니다 (판정이 있는 Case에는 새 결제가 병합되지 않습니다)
2. 스크립트 끝에 찍힌 손님 번호로 다시 보냅니다: `py scripts/demo/legacy/cakeshop_pay.py <손님 번호>`
3. 새 Case의 보고서 전문에 첫 실행의 결제가 "고객 최근 결제"로 보입니다 (이 사건 결제는 빠짐)

**결제별 카드 · 기기 · 접속 국가 확인 (#346)** — 기본 실행부터 손님별 가짜 카드 · 기기 · 국내 IP · 이메일을 모두 보냅니다.

- IP와 국가 변경: `py scripts/demo/legacy/cakeshop_pay.py --ip 203.0.113.5 --country US`
- 이메일도 변경: `py scripts/demo/legacy/cakeshop_pay.py --email buyer@example.test`
- 카드 · 기기 규칙(새 기기 · 처음 쓰는 카드)이 함께 평가되므로 과거 데이터 상태에 따라 몇 번째 결제가 보류되는지가 위 표와 달라질 수 있습니다

**"같은 카드 · 기기 · IP를 쓴 다른 결제" 카드 확인 (#352)** — 기본 실행은 손님별로 다른 카드를 보냅니다.

1. 같은 카드 이름으로 두 번 보냅니다 (매번 새 손님): `py scripts/demo/legacy/cakeshop_pay.py --card A` → 한 번 더 `py scripts/demo/legacy/cakeshop_pay.py --card A`
2. 두 번째 Case의 보고서 전문에서 "같은 카드"에 "다른 고객 1명 · 결제 3건"과 그 결제들의 FDS 판정이 보입니다
3. 같은 손님의 결제와 이 사건 결제는 세지 않습니다. 다른 가맹점 결제도 보지 않습니다

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

✅ 조사 과정의 사용 모델과 실행 설정이 Ollama/Qwen인지 확인합니다. 보고서 문구만으로 실제 LLM 실행을 판별하지 않습니다. PC 성능에 따라 생성 시간이 달라집니다.

### 4-1. OpenAI로 바꾸기 (배포 VM · Ollama를 설치할 수 없는 PC, #453)

Ollama 대신 OpenAI가 보고서를 씁니다. 조사 순서 · 문장 검사 · 판정(직원)은 4절과 같습니다. 사건의 결제 · FDS 근거 요약이 OpenAI로 나가므로 합성 시연 데이터로만 씁니다 ([ADR-0009 결정 5](../decisions/0009-ai-investigation-call-structure.md)).

1. `ai-server/.env`(VM이면 ai-server 환경 변수)를 바꿉니다. 키는 팀 공용 키이고, 저장소 · 문서 · 메신저에 붙여 넣지 않습니다.
   ```dotenv
   AI_SERVER_LLM_PROVIDER=openai
   AI_SERVER_LLM_API_KEY=팀 공용 키
   # 바꿀 때만: AI_SERVER_OPENAI_MODEL=gpt-4.1-mini
   ```
2. ai-server를 다시 켭니다. 키가 비어 있으면 `AI_SERVER_LLM_API_KEY가 비어 있어` 오류로 켜지지 않습니다.
3. **새 결제**를 보냅니다.

✅ 보고서 전문 "조사 과정" 탭의 사용 모델에 `gpt-4.1-mini`(보라색)가 보이고, 보고서 한계 줄에 "mock 조사입니다"가 없습니다.

## 5. 대표 경로 확인

### 5-1. 실행 식별자부터 기록

최소 데이터는 새 합성 고객 한 명의 결제 3건입니다. 기존 금융 기록을 삭제하거나 시계를 바꾸지 않습니다. 반복 검증은 첫 실행이 끝난 다음 **다른 새 고객**으로 수행합니다. 같은 고객을 재사용하면 30분 병합과 누적 규칙의 영향을 받습니다.

| 항목           | 기록할 값                                                            |
| -------------- | -------------------------------------------------------------------- |
| 실행 시각·코드 | 한국시간, HEAD, 관련 미커밋 변경                                     |
| 환경           | DB 이름·포트, 앱 포트, 고정 보고서 / mock / 실제 LLM                 |
| 거래           | merchantCode, customerRef, 세 건 각각의 orderRef·paymentId·실제 상태 |
| 조사           | caseId, 담당자, 최신 reportVersion, 생성 상태·조회 근거              |
| 결과           | 판정 ID·ACCEPT, 인계 상태, captureId, settlementId·정산 예정일       |

예시 ID를 현재 데이터로 착각하지 않습니다. 키·쿠키·토큰·실제 고객 개인정보는 기록하지 않습니다.

### 5-2. 직원 화면과 가맹점 화면

1. **경보** `/security/alerts`: 로컬 시드 직원 `staff_test`로 로그인해 이번 paymentId의 평가를 찾습니다. 기본 규칙 예시는 60점 REVIEW지만 화면의 실제 점수와 적용 규칙을 기록합니다. HELD가 없으면 추가 결제를 반복하지 말고 규칙·차단·연동 오류부터 확인합니다.
2. **담당 배정**: 새 사건은 맡은 미종결 사건이 적은 직원에게 자동 배정됩니다. 먼저 현재 담당을 확인하고, 시연 계정과 다르면 관리자가 해당 합성 사건 한 건만 사유를 남겨 담당 변경합니다. 미배정이면 관리자가 `/admin/case-assignments`의 담당 현황에서 이번 caseId를 시연 직원에게 배정합니다. 열린 사건은 담당 직원만 상세 조사·판정할 수 있습니다. 계정 전환은 정상 로그아웃·로그인을 사용합니다.
3. **Case** `/security/cases/:id`: customerRef와 결제 ID가 이번 요청과 같은지 확인합니다. 최신 보고서가 생성 완료인지, 보고서 작성 후 새 결제가 병합되지 않았는지 확인합니다. 보고서 전문의 자료·출처·조회 범위·한계·조사 과정을 읽습니다. AI 권고는 #644에서 제거됐으므로 예전의 “7. AI 권고”를 찾거나 AI 승인으로 설명하지 않습니다.
4. **보고서 방식**: Spring 고정 보고서는 AI 서버 미연결, mock은 AI 서버 연결을 통과하지만 LLM 미사용입니다. 실제 LLM 여부는 사용 모델과 실행 설정·조사 기록을 함께 확인합니다. 특정 한계 문구가 없다는 사실만으로 실제 LLM 사용을 확정하지 않습니다.
5. **직원 ACCEPT**: 이번 HELD 결제만 선택하고 화면이 요구하는 증거 확인·판정 사유를 작성한 뒤 `매입 요청`을 확정합니다. 보고서가 오래됐거나 생성 중이면 최신 버전을 확인하고 진행합니다. 이는 합성 데이터와 모의 금융 승인 환경의 시연입니다.
6. **결과**: 동일 paymentId가 CAPTURED인지 확인하고 “금융 조치 인계”의 ACCEPTED와 Case 종료를 함께 확인합니다. 인계 접수만 보고 매입 성공으로 간주하지 않습니다. 필요하면 화면의 “다시 확인”으로 실제 처리 결과를 조회합니다.
7. **거래 내역** `/security/transactions`: 현재 구현된 거래 목록에서 이번 거래 상태·금액을 대조합니다. 이 메뉴는 빈 화면이 아닙니다. 권한과 감사 정책을 그대로 사용하며 불필요한 연락처 원문 조회·CSV 반출은 하지 않습니다.
8. **가맹점 결과**: 로그아웃 후 로컬 CAKESHOP 사장님 `cakeshop_owner`로 로그인합니다. `/owner/actions`에서 이번 사건의 처리 결과와 결제 상태를 확인합니다. 직원 전용 점수·내부 조사 내용이 보이는 것을 기대하지 않습니다.
9. **정산 예정** `/owner/settlements`: 조회 기간에 예정일을 포함해 확인합니다. 현재 정책은 매입일을 제외한 세 번째 영업일(D+3), 주말·한국 공휴일 제외입니다. 오늘 발생한 매입은 미래의 정산 예정이며 은행 입금 완료가 아닙니다. 같은 실행의 앞선 ALLOW 결제 2건도 정산에 포함될 수 있으므로 합계만으로 세 번째 거래를 식별하지 않습니다.

로컬 시드 비밀번호는 기존 안내의 `1234`이며 실제 환경에서 변경된 경우 현재 개발 계정을 사용합니다. 반복 로그인 실패로 잠금이 생기면 우회하지 않습니다.

### 5-3. 식별자를 고정한 읽기 전용 대조

화면에 정산 ID 등 필요한 연결 값이 없을 때만 승인된 개발 DB에서 읽기 전용으로 대조합니다. 아래 `:paymentId`는 실제 세 번째 결제 ID로 바꾸는 SQL 매개변수 표기입니다. 현재 연결 DB는 application-local.yml 또는 실행 환경변수로 확인하고 `donworry`로 단정하지 않습니다.

```sql
SELECT payment_id, status, amount FROM payments WHERE payment_id = :paymentId;
SELECT action_type, status, capture_id, completed_at
FROM payment_actions WHERE payment_id = :paymentId;
SELECT capture_id, status, capture_amount, captured_at
FROM captures WHERE payment_id = :paymentId;
SELECT * FROM settlements WHERE payment_id = :paymentId;
```

다른 실행의 마지막 인계를 고를 수 있으므로 `ORDER BY ... DESC LIMIT 1`만으로 이번 거래의 성공을 확인하지 않습니다. 예정일 컬럼·지급 배치 상태는 현재 스키마와 정산 API 기준으로 읽습니다. 원장 UPDATE·DELETE, 지급일 소급, P5를 우회하는 샘플 생성은 이 시연에 사용하지 않습니다.

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
- **4-1절인데 보고서가 "실패"** → ai-server 로그에 `401`이면 키가 틀렸고, 연결 오류면 그 PC · VM에서 `api.openai.com`으로 나갈 수 없습니다. 급하면 `AI_SERVER_LLM_PROVIDER=mock`으로 되돌려 시연합니다.
- **Qwen 답에 중국어가 섞임** → Qwen 2.5의 버릇입니다. 한국어 지시와 `temperature=0`으로 줄이지만 검사 장치는 아직 없습니다. 섞이면 화면을 남겨 P4에 알려 줍니다.

**ai-server가 안 켜짐**

- **"애플리케이션 제어 정책에서 이 파일을 차단했습니다 (os error 4551)"** → [7-2](#7-2-windows에서-ai-server-실행이-막힐-때)

## 7. 부록

### 7-1. macOS · Linux 명령

PowerShell 명령 대신 아래를 씁니다.

- backend 실행: `./gradlew bootRun`
- 결제 스크립트: `python3 scripts/demo/legacy/cakeshop_pay.py` (Python 3.8 이상, 표준 라이브러리만 씀)
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

| 설정                                                                         | 저장 위치                                          | 읽는 쪽                | 쓰는 절 |
| ---------------------------------------------------------------------------- | -------------------------------------------------- | ---------------------- | ------- |
| 케이크샵 서명 키 `DONWORRY_MERCHANT_HMAC_CAKESHOP`                           | OS 사용자 환경변수                                 | Spring · 결제 스크립트 | 2절     |
| 결제 API 켜기 `payment.http.enabled`                                         | `backend/src/main/resources/application-local.yml` | Spring                 | 2절     |
| AI 서버 켜기 · 키 `ai.server.enabled` · `ai.server.api-key`                  | 같은 `application-local.yml`                       | Spring                 | 3절     |
| AI 서버 키 · AI 종류 `AI_SERVER_INTERNAL_API_KEY` · `AI_SERVER_LLM_PROVIDER` | `ai-server/.env`                                   | ai-server              | 3 · 4절 |
| OpenAI 키 · 모델 `AI_SERVER_LLM_API_KEY` · `AI_SERVER_OPENAI_MODEL`          | `ai-server/.env` (VM은 환경 변수)                  | ai-server              | 4-1절   |

결제 API · AI 서버 켜기 값은 `application-local.yml`에 넣든 OS 환경 변수(`PAYMENT_HTTP_ENABLED` · `AI_SERVER_ENABLED`)로 두든 테스트(`./gradlew check`)에는 적용되지 않습니다. `backend/build.gradle`이 테스트에서 둘을 끕니다 (#374). IntelliJ 자체 실행기처럼 Gradle을 거치지 않고 테스트를 돌리면 이 설정이 빠지니 Gradle로 돌립니다.

포트: backend `8081` · frontend `5173` · ai-server `8000` · DB `3307` · Ollama `11434`

### 7-4. 한계

- 카드사 승인 · 취소와 매입은 모의 구현이라 늘 바로 성공합니다. 실패 · 지연은 이 흐름으로 볼 수 없습니다.
- 단계 사이는 커밋 뒤 이벤트로 이어집니다. 중간에 실패하면 `EVENT_PUBLICATION`에 미완료로 남고 자동 재발행은 아직 없습니다. 대기 중인 인계는 Case 상세의 "다시 확인"으로 P5 결과를 받아옵니다.
- 조사 자료는 현재 허용된 읽기 전용 도구와 실제 호출 기록 기준입니다. 고객 최근 결제·카드 관련 조회 등 이미 추가된 자료가 있으므로 Case 결제와 FDS 두 가지만 본다고 설명하지 않습니다. 수집하지 않은 로그인 이벤트나 기술적 공격은 추측하지 않습니다.
- 이 절차의 입력은 CakeShop 대신 보내는 HMAC 스크립트입니다. 실제 CakeShop 주문 화면·결제창·웹훅 반영은 별도 검증 항목이며 스크립트 성공으로 검증 완료를 주장하지 않습니다.

## 8. 2026-11-09 실행 기록

기준 HEAD `478fb941`, 로컬 `donworry_dev`. 실제 합성 데이터로 **고정 보고서 경로의 결제→보류→직원 판정→매입→가맹점 결과→정산 예정**을 확인했다. 실제 은행 지급이나 LLM 실행 완료를 뜻하지 않는다.

| 구분           | 실제 결과                                                                                                                                                    |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 입력           | CAKESHOP, customerRef `756600622472`, 서로 다른 60만 원 주문 3건                                                                                             |
| 첫 결제        | paymentId 79, `CAKE-20261109050328-08eebd-1`, CAPTURED                                                                                                       |
| 두 번째        | paymentId 80, `CAKE-20261109050331-1fec7d-2`, CAPTURED                                                                                                       |
| 세 번째        | paymentId 81, `CAKE-20261109050333-a9c171-3`, HELD, REVIEW 60점 (고액 +30, 반복 +30)                                                                         |
| 사건           | caseId 31, 보고서 ID 34 / v1, 증거 v1. 처음 보안직원 10에게 자동 배정                                                                                        |
| 배정           | 관리자가 합성 시연 사유로 사건 31 한 건을 demo 보안직원(staff_test)에게 변경                                                                                 |
| 보고서         | 전문 화면의 “AI 서버가 꺼진 상태에서 만든 고정 보고서” 안내 확인. 실제 AI 요약·추가 조사 미실행                                                              |
| 판정           | 05:07 KST, staff_test의 ACCEPT. 합성 시연임과 고정 보고서 사용을 사유에 명시                                                                                 |
| 처리 결과      | Case 종료, 금융 처리 완료, 완료 작업 ID 51, 결제 81 매입 완료. HMAC GET도 HTTP 200 / CAPTURED                                                                |
| 사장님 결과    | cakeshop_owner의 `/owner/actions/31`에서 조사 종료·정상 처리 결정·결제 81 매입 완료 확인                                                                     |
| 정산 예정      | `/owner/settlements` → 11월 12일 → 첫 주문 상세에서 세 번째 orderRef 일치 확인. 원금 600,000원, 카드 수수료 12,000원, PG 수수료 12,000원, 순정산금 576,000원 |
| 실행 전체 정산 | 11월 12일 주문 3건·예정금 1,728,000원. 실제 지급액 0원과 구분                                                                                                |
| 추가 식별자    | captureId·settlementId는 이번 UI 및 결제 조회에서 확인하지 않음. 완료 작업 ID를 captureId로 단정하지 않음                                                    |

검증 화면: 채팅 outputs의 `DonWorry-case31-captured.png`, `DonWorry-case31-owner.png`, `DonWorry-payment81-settlement.png`. 직원 상세·가맹점 결과·정산 상세를 직접 확인했으며, 확인한 데스크톱 화면에서 주요 상태·금액·조작부의 잘림이나 겹침은 보이지 않았다. 모바일 검증은 하지 않았다.

### 실행 환경에서 막힌 점과 해결 범위

- Docker를 사용자가 켠 뒤 일반 PowerShell에서는 `docker ps`가 정상, 실행 도구에서는 파이프 `permission denied`. 사용자가 `docker compose up -d mariadb`를 실행했다. 앱이 실제 연결한 MariaDB 12.3의 `donworry_dev`가 compose DB라는 증거는 없으므로 둘을 같은 것으로 단정하지 않는다.
- Gradle 캐시 잠금 파일 쓰기 권한을 부여받은 뒤 compileJava·bootRun 성공. 86개 migration 검증, 새 migration 적용 없음. DB 버전이 소스의 최신 migration보다 앞선다는 경고는 남아 있다.
- 도구가 띄운 서버는 기동 완료 로그에도 HTTP 시간 초과. 해당 프로세스를 종료하고 사용자가 일반 PowerShell에서 같은 명령을 실행하자 프론트 HTTP 200, 백엔드 비로그인 auth 401 및 브라우저 로그인 정상. 원인 세부사항은 미확정이며 권한·방화벽을 임의 변경하지 않았다.
- `py` 런처는 설치 Python을 찾지 못했다. 실제 결제는 저장소 루트에서 `.\ai-server\.venv\Scripts\python.exe scripts/demo/legacy/cakeshop_pay.py`로 실행했다.
- AI 서버는 도구 실행 환경에서 기존 가상환경의 `_uuid_utils` DLL이 실행 정책에 차단됐다. 일반 터미널에서는 정상 실행됐다. 사용자가 `.env`를 저장하고 OpenAI 제공자로 AI 서버를 재시작한 뒤, 백엔드에도 `AI_SERVER_ENABLED=true`와 같은 내부 키를 적용해 실제 OpenAI 연결에 성공했다. 시스템 보안 정책은 변경하지 않았다.

### 남은 검증과 관찰

- 실제 OpenAI 연결과 새 고객의 두 번째 반복 실행은 아래 9절에서 검증했다. 별도 FastAPI mock 경로, 실제 CakeShop 주문·웹훅, 실패·취소·환불 회귀는 미검증이다.
- 고정 보고서에 “AI가 씀” 소제목이 함께 표시된다. 사장님 조사 결과 요약에도 “직원은 … 판정해 주세요”가 표시된다. 이번 문서 범위를 넘는 화면 문구 수정은 하지 않았으며 후속 검토 대상으로 기록한다.
- 정산 화면의 “주말 제외” 안내는 공휴일까지 제외하는 계산 정책과 차이가 있어 기존 3번 계획의 정정 대상임을 실제 화면에서도 확인했다.
- 문서 Prettier 검사·`git diff --check`·상대 파일 링크 검사는 통과했다. 별도 자동 회귀 테스트는 실행하지 않았다.

## 9. 실제 OpenAI 연결과 두 번째 반복 검증 — 2026-11-09

사용자가 API 비용과 합성 시연 근거의 OpenAI 전송 범위를 안내받고 `.env`의 제공자·키를 설정했다. 키 값은 출력하거나 문서에 저장하지 않았다. 두 서버는 사용자 일반 PowerShell에서 재시작했고 프론트는 유지했다. 기존 `cakeshop_pay.py`를 새 고객으로 한 번 실행했다.

| 확인 항목    | 실제 결과                                                                                                                                                                                     |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 고객·가맹점  | customerRef `717783843207`, CAKESHOP / merchantId 2                                                                                                                                           |
| 결제         | 82·83 CAPTURED, 84 HELD. 각 600,000원                                                                                                                                                         |
| 주문         | `CAKE-20261109052050-ea0a07-1`, `CAKE-20261109052053-15bf96-2`, `CAKE-20261109052056-b5fadf-3`                                                                                                |
| 사건·보고서  | caseId 32, reportId 35 / 보고서 v1 / 증거 v1. FDS 60 REVIEW: 고액 +30, 반복 +30                                                                                                               |
| 접근·배정    | 보안직원 10 자동 배정. staff_test로 상세 접근 시 담당자만 열람 가능 안내 확인. 관리자가 사건 32만 합성 시연 사유로 staff_test에게 재배정                                                      |
| 실제 AI 결과 | 내부 인증 GET `/investigations/35`: SUCCEEDED, provider OPENAI, model gpt-4.1-mini, durationMs 8630, fixedReason null. 직원 화면의 조사 과정과 일치                                           |
| Agent 동작   | 기본 조회 후 AI가 고객 이력 90일 조회 선택 → 2건 조회 → 조사 완료 선택 → 사실 문장 작성 1차 형식 검사 통과 → 증거 v1 보고서 조립                                                              |
| LLM 사용량   | 실제 LLM 호출 3회. 입력 1,543 + 1,712 + 1,858 = 5,113토큰, 출력 14 + 4 + 312 = 330토큰. 화면의 AI 4단계에는 읽기 도구 실행 1개가 포함되므로 LLM 호출 4회라는 뜻이 아님                        |
| 판정·매입    | 05:24 KST staff_test ACCEPT. 합성 시연이며 실제 고객·카드사 확인은 하지 않았음을 사유에 명시. Case 종료, 금융 처리 완료, 완료 작업 ID 54. HMAC GET `/api/payments/84`도 HTTP 200 / CAPTURED   |
| 사장님 결과  | `/owner/actions/32`: 조사 종료, 결제 84 정상 처리 결정·매입 완료                                                                                                                              |
| 정산         | 결제 84의 정확한 주문번호로 정산 상세 확인. 11월 12일 예정, 600,000 - 12,000 - 12,000 = 576,000원. 당일은 첫 실행 포함 6건 / 3,456,000원, 이번 실행 증가분 3건 / 1,728,000원. 실제 지급액 0원 |

이 실행으로 **고정 보고서 경로 1회 + 실제 OpenAI 경로 1회**의 핵심 통합 시연을 확인했다. 카드사·매입은 모의 구현이며 실금융 지급 검증이 아니다. 별도 샘플 보고서 스크립트는 실행하지 않았고, 실제 앱 통합 경로로 검증했다. captureId·settlementId는 별도로 조회하지 않았다.

검증 화면은 채팅 outputs의 `DonWorry-case32-openai-trace.png`, `DonWorry-case32-openai-report.png`, `DonWorry-case32-captured.png`, `DonWorry-case32-owner.png`, `DonWorry-payment84-settlement.png`에 저장했다. 확인한 데스크톱 화면에서 주요 결과의 겹침·잘림은 보이지 않았다. 모바일은 미검증이다.

### 발표와 후속 설계에 반영할 관찰

- 이번 AI는 허용된 읽기 도구 중 90일 고객 이력 조회를 선택했다. 결제 판정·매입은 AI가 수행하지 않았다. 한 번의 성공은 모든 사건의 정확도나 안정성을 입증하지 않는다.
- 기존 결제 스크립트는 결제 준비를 거치지 않아 주문 상품 정보가 없다. AI 보고서·정산 상세 모두 이를 표시한다. CakeShop 실제 주문 흐름까지 검증했다고 설명하지 않는다.
- 기본 합성 IP를 재사용하므로 보고서에는 기존 시연 고객의 IP 연관 집계가 포함된다. 반복 시연할수록 이 집계와 유사 사건이 달라질 수 있다.
- 이 시연 당시에는 내부 보고서 화면의 사장님 비공개 안내와 달리 사장님 결과에 AI 요약 5문장과 IP 공유 고객 수·FDS 집계가 노출됐다. 후속 2번 변경에서 내부 요약 전달을 제거하고 상태 기반 처리 안내로 교체했다. 당시 캡처와 변경 후 검증은 [접근 통제 검증 기록](security-access-checklist.md)으로 구분한다.
