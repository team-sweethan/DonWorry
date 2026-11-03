# 0061. FDS ML Shadow 평가 — RF 추론을 ai-server에 두고 결제 판정과 분리

- **상태:** 제안 — #375 PR ① 구현, PR 리뷰 대상
- **날짜:** 2026-11-02
- **관련:** Issue #375 · #450(rf-v2 교체), [ADR-0009](0009-ai-investigation-call-structure.md) (AI 조사 호출 구조)
- **영향 받는 담당:** P4 (ai-server). 후속 연결에서 P2 (fds 공개 조회), P3 (investigation 안 실행 위치)

## 배경

- 결제 판정은 Rule Engine(R01~R08) 점수로만 한다. 합성 거래 2만 건으로 Random Forest · XGBoost · Isolation Forest · Transformer · Transformer+XGBoost를 실험했다 (개인 실험 저장소 HyunGyu-Cho/fds-ml, 팀 저장소에는 넣지 않음).
- Rule 조건을 Python으로 재현한 오프라인 실험에서, 정상 오탐을 5건으로 같게 맞추면 Validation 사기 443건 중 Rule 64건 · RF 245건을 잡았다 (fds-ml `reports/scorecard.md`). 합성 데이터 결과라 실제 결제 성능의 근거는 아니다.
  - 처음 적었던 Rule 57건은 서버 Rule Engine과 조건이 조금 다른 재현 코드의 값이었다. 서버 코드(`FdsEvaluationTransactionService`)를 그대로 옮긴 재현(`rule_engine.py`)으로 다시 잰 64건으로 고친다 (#450)
- 실제 거래에서 Rule과 ML을 비교하려면 결제에 영향을 주지 않고 ML을 돌릴 자리가 필요하다. 배포 VM은 1코어 · 8GB 2대이고 ai-server(FastAPI)와 Spring이 같은 VM에 있다.

## 결정

Random Forest를 **Shadow Mode**로만 쓴다 (지금 모델: rf-v2, 아래 "개정" 참고). 결제 판정은 지금처럼 Rule Engine만 하고, RF는 같은 거래의 사기 확률을 따로 계산해 Rule 결과와 나란히 기록하는 데만 쓴다. 추론은 기존 ai-server의 `POST /ml/fraud-score`에서 한다.

1. **판정과 분리** — RF 확률을 Rule 점수에 더하지 않고 승인 · 보류 · 거절을 바꾸지 않는다. 응답의 `mlRiskLevel`(HIGH/LOW)은 분석용 표시다
2. **Feature 단일 기준** — 학습 코드 `fds-ml/features.py`를 `ai-server/app/ml/features.py`로 그대로 옮긴다. Spring은 원본 거래 행만 보내고 Feature 계산은 ai-server가 한다. 학습 데이터 전체로 계산한 golden 30건(rf-v1 `tests/fixtures/ml_golden.json` · rf-v2 `ml_golden_rf-v2.json`, fds-ml `export_ai_server_model.py`로 생성)과 Feature · 확률이 같은지 테스트로 지킨다
3. **모델 파일** — 저장소에 압축해 둔다 (`ai-server/models/<버전>.joblib`, 파일 이름이 버전). 서버 시작 시 한 번 읽고 `n_jobs=1`로 돌린다. 읽지 못하면 서버는 뜨고 ML API만 503으로 답한다. 저장소에 포함한 파일만 읽는다 (joblib은 읽을 때 코드를 실행할 수 있다)
4. **교체** — 모델을 바꿀 때는 새 버전 파일을 추가하고 `AI_SERVER_ML_MODEL_PATH`와 Spring `ai.ml-shadow.model-version`(`ML_SHADOW_MODEL_VERSION`)을 함께 바꾼다 (다르면 Spring이 기록하지 않고 멈춘다, [ADR-0062](0062-fds-ml-shadow-spring-connection.md)). 다른 모델(XGBoost 등)은 같은 계약의 두 번째 Shadow 모델로 비교한다. 판정 반영은 Shadow 기록을 근거로 별도 ADR로 정한다

### 계약 (Spring → ai-server)

- `POST /ml/fraud-score`, 헤더 `X-Internal-Api-Key` (조사 API와 같은 키, 비어 있으면 모두 거절)
- 요청: `evaluationId`, `target`(대상 결제), `customerHistory`(같은 고객의 이전 결제), `sharedCardHistory`(같은 카드를 쓴 다른 고객의 이전 결제)
  - 거래 행: `paymentId` · `merchantId` · `customerRef` · `amount` · `paymentRequestedAt`(시간대 필수) · `deviceId` · `cardToken`
  - 카드 · 기기는 같은지만 비교하므로 Spring이 해시로 보내도 결과가 같다. IP · 이메일은 받지 않는다
  - 고객은 `merchantId` + `customerRef` 조합으로 구분한다 (Rule Engine의 이력 조회와 같다). 가맹점이 다르면 회원번호가 같아도 다른 고객이다. ai-server는 `features.py`에 넘기기 전에 고객 키를 `가맹점번호:customerRef`로 바꾼다 (`features.py`는 그대로)
- 응답: `evaluationId` · `modelVersion` · `fraudProbability` · `threshold` · `mlRiskLevel` · `features`(모델 입력 칸 — rf-v1 13개, rf-v2 12개) · `latencyMs`
- 오류
  - 422: 이력에 대상보다 뒤 시각 거래 · 같은 `paymentId` 중복 · `customerHistory`에 다른 고객 · `sharedCardHistory`가 같은 카드 다른 고객이 아님 · 이력 2,000건 초과
  - 503 `ML_MODEL_UNAVAILABLE` (모델 파일이 없거나 내용이 잘못됨 — 서버는 뜬다), 500 `ML_INFERENCE_FAILED`

## 개정 — 기본 모델 rf-v1 → rf-v2 (#450, 2026-11-02)

- **왜:** rf-v1은 합성 가맹점 1001~1003만 보고 배워서, 실제 가맹점 번호(처음 보는 번호)가 들어오면 확률이 흔들린다. 같은 손님 · 같은 결제(첫 결제 60만 원)인데 가맹점 번호에 따라 22% ~ 44%로 달라졌다
- **rf-v2:** 학습 절차는 rf-v1과 같고 입력에서 가맹점 번호(`merchantId`)만 뺐다 (입력 12칸, 기준값 0.30 그대로)
- **성능 유지 판정** (fds-ml `reports/rf_v1_vs_v2.md`, Validation 2,998건 · 사기 443건, 합성 데이터)
  - 헛경보 50건일 때 잡은 사기 350 → 347 (−0.9%), AP 0.8984 → 0.9022 — 통과 기준(5% 넘게 감소 없음 · AP 하락 0.01 이내) 통과
  - 가맹점 번호만 바꿨을 때 기준값 위아래가 뒤집히는 결제 49건 → 0건
- **바뀌는 동작:** Spring 커서는 모델 버전별이라, 교체 직후 rf-v2가 과거 평가를 처음부터 다시 채점한다 (주기당 50건). 이력은 대상 결제 시각 이전만 읽으므로 미래 정보는 섞이지 않는다. 다 채워질 때까지 거래 내역 화면의 ML 참고 칸이 옛 결제에서 비어 있을 수 있다. rf-v1 기록은 지우지 않는다
- **되돌리기:** `ai-server/models/rf-v1.joblib`을 남겨 두었다. `AI_SERVER_ML_MODEL_PATH=models/rf-v1.joblib`과 `ML_SHADOW_MODEL_VERSION=rf-v1`을 함께 주면 된다
- 이 개정도 결제 판정에는 영향이 없다 (결정 1 그대로)

## 고려한 대안

| 대안 | 버린 이유 |
| --- | --- |
| RF 확률을 Rule 점수에 더하거나 결제 중 동기 판정에 사용 | 실제 성능을 확인하기 전에 결제 결과가 바뀐다. Rule 점수(0~100)와 확률(0~1)은 같은 척도가 아니다 |
| 별도 ML 서버 · VM | 1코어 VM 2대뿐이다. 프로세스 하나를 더 운영할 만큼 부하가 크지 않다 (1건 약 20ms) |
| Ollama · Qwen에 판정을 맡김 | LLM은 표 형태 데이터 분류 모델이 아니고 느리다. RF는 CPU에서 바로 추론한다 |
| Spring(Java)에서 Feature 계산 | 학습 코드와 같은 규칙을 두 언어로 따로 유지하게 되어 값이 어긋날 위험이 크다 |
| ai-server가 DB를 직접 조회해 이력 구성 | ADR-0009(ai-server는 DB에 직접 접속하지 않는다)에 어긋난다 |
| 고객 전체 이력을 제한 없이 전송 | 1코어 VM에서 요청 하나가 메모리 · 시간을 다 쓸 수 있다. 2,000건 상한을 둔다 |
| XGBoost · Transformer+XGBoost 하이브리드 | 학습 데이터 1.4만 건으로는 시퀀스 모델의 이점을 확인하기 어렵고, torch 의존성이 크고, 조사 보고서에 근거를 설명하기 어렵다. 같은 계약의 두 번째 Shadow 모델로 후속 비교한다 |
| 모델 파일을 저장소 밖(배포 서버 · Git LFS)에 둠 | 압축 후 3.6MB라 저장소에 두는 편이 배포 · CI 테스트가 단순하다. 팀은 LFS를 쓰지 않는다 |
| (#450) rf-v1을 두고 실제 가맹점 번호를 학습 때 본 번호(예: 1001)로 바꿔 넣기 | 어떤 번호로 바꾸느냐에 따라 같은 결제의 확률이 22% ~ 44%로 달라진다. 근거 없는 값을 끼워 맞추는 셈이다 |
| (#450) rf-v1 그대로 두기 (실제 가맹점은 "처음 보는 가맹점") | 가맹점 번호만 바뀌어도 2,998건 중 49건이 기준값 위아래로 뒤집힌다. rf-v2는 성능을 유지하면서 0건이다 |
| 요청마다 모델 파일 읽기 | 1건마다 0.2초(압축) ~ 2.8초(원본)가 더 걸린다 |
| 학습 때 설정 그대로 `n_jobs=-1` | 1건 예측이 70ms로 `n_jobs=1`(20ms)보다 느리고, 실행마다 1e-16 정도 결과가 달라 golden 비교가 흔들린다 |

## 영향

- API: ai-server `POST /ml/fraud-score` 추가. Spring 호출은 후속 PR
- DB: 없음
- 화면: 없음. #450 이후 거래 내역 화면 ML 참고 칸 도움말의 모델 이름이 rf-v2로 바뀐다
- 테스트: ai-server 71개 추가 (`test_ml_model.py` · `test_ml_api.py`). #450에서 golden 테스트를 rf-v1 · rf-v2 모두 확인하도록 넓힘 (60개 추가), API 계약 테스트는 기본 모델 rf-v2로 확인
- 의존성 · 운영: `scikit-learn==1.9.1`. ai-server 시작 시 모델 로딩으로 메모리 약 110MB · 시작 0.2초 증가. dev를 받은 뒤 `uv sync` 필요, Windows에서 Python 실행이 막히면 [로컬 시연 문서](../demo/local-demo.md) 7-2절
- 문서: 이 ADR, ADR 목록 한 줄

이 ADR 범위 밖 (후속 결정)

- Spring 연결 방식 — 결제 · Rule 경로를 고치지 않도록 커밋된 평가를 주기적으로 읽는 방식을 우선 검토 (이벤트 발행과 비교)
- P2 `fds/api` 이력 조회 인터페이스, Shadow 결과 저장 테이블 (ERD 합의)
- ~~가맹점 번호를 뺀 v2 모델~~ — #450에서 rf-v2로 교체 (위 "개정")
- 같은 사람이 같은 카드로 두 가맹점에서 결제하면 가맹점별 고객이 달라 `cardUsedByOtherCustomer`가 1이 된다. PG는 가맹점 간 고객을 이을 수 없어 Rule과 같은 한계이고, 합성 데이터에는 이런 경우가 없다 — Shadow 기록으로 빈도를 본다
- Rule 위험 등급 80점 경계 (코드 `score >= 80` DENY, 기획 80점 REVIEW) — P2에 따로 공유

## 검증

- `cd ai-server && ruff format --check . && ruff check . && pytest -q` → 163 passed (커밋 `7eea46d`, Windows · Python 3.14.6 / Linux · Python 3.13)
- Codex 리뷰 반영 후 (잘못된 모델 내용 503 · 고객 = 가맹점 + customerRef) → 재현 테스트 7개 수정 전 실패 확인, 수정 후 170 passed (Linux · Python 3.13). golden 60건은 고객 키 변경 후에도 그대로 통과
- Windows `python -m uvicorn app.main:app` 실기동 → 카드 도용 golden 사례 200, 확률 `0.6590696767001114`(학습 환경과 동일), 첫 호출 107ms · 이후 25ms, 서비스 키 없이 401
- 모델 경로를 없는 파일로 두고 기동 → `/health` 200, ML API 503 (Linux)
- 미검증: 1코어 VM 지연 · 메모리, Docker 배포 (ai-server Dockerfile 없음), 실제 결제에서의 탐지 성능
