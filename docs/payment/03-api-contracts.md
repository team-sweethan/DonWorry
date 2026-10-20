# P5 외부·내부 API 계약 초안

출처: [S5·S6](README.md). 실제 Controller/인터페이스/DTO는 아직 없다. S5에서 URL·API ID·목적을 확인했더라도 “초안/협의 중”이므로 팀 확정 API라고 쓰지 않는다. 아래 DTO 필드·클래스명·메서드명·오류 코드는 **PROPOSED**. S6의 P6 API-09/10 메서드와 응답 필드는 전달받은 계약으로 보존한다.

S8 후속 선택: 질문 1·2·3·4·5·7의 방향을 반영했으며 **사용자 선택 / 팀 합의 대기**다. 질문 6(P6 정산 구조)·8(P3 케이스 생성 기준)은 OPEN으로 유지한다. 상세 요청/응답 필드는 여전히 제안이다.

## 공통 규격 (PROPOSED)

- UUID는 HTTP 문자열/Java UUID, 금액은 최소 화폐 단위 long, 시각은 UTC ISO-8601/Instant. nullable은 `?`로 표시한다. PageRequest는 cursor?, limit(1~100 제안), Page는 items/nextCursor?.
- 외부 서버 요청은 P1 가맹점 검증과 CakeShop 인증 계약(Q11)을 거친다. HMAC 등 구체 헤더·canonicalization·시계 허용폭은 OPEN. nonce는 요청마다 새 값, 멱등 키는 같은 업무 재시도에서 유지.
- 인증·권한 → 잠금 아래 멱등 키·본문 일치 및 완료 결과 재생 → 신규 실행 상태·기한 검증 순서다. 성공한 confirm/cancel/직원 결정의 재생을 현재 상태가 다르다는 이유로 거부하지 않는다. 만료 토큰으로 인증 자체가 안 되면 CakeShop 서버의 인증된 상태 조회·재발급 경로를 사용한다.
- 외부 오류 제안: 400 INVALID_REQUEST, 401 AUTHENTICATION_FAILED, 403 TENANT_FORBIDDEN, 404 PAYMENT_NOT_FOUND, 409 STATE_CONFLICT/IDEMPOTENCY_CONFLICT/ACTIVE_ORDER_EXISTS/CANCELLATION_NOT_ALLOWED, 410 CHECKOUT_EXPIRED, 503 FDS_UNAVAILABLE/RETRYABLE_TECHNICAL_ERROR. ErrorResponse={code,message,retryable,correlationId}. 실제 통합 오류 포맷은 Q15.
- 내부 오류는 같은 의미의 Java 도메인 예외/결과이며 HTTP 코드를 내부 API 주소처럼 사용하지 않는다. FDS/Mock 업무 거절은 결제 결과 DTO의 DECLINED, 기술 실패만 예외다.
- PaymentView 제안: paymentId, tenantId, orderExternalId, amount, currency, status, cardApproved(boolean: 현재 유효 승인), approvedAt?, canceledAt?, declineReason?. HELD이면 cardApproved=true, CANCELED이면 false이고 approvedAt은 과거 승인 사실을 보존한다. 민감 카드 token/fingerprint는 CakeShop 조회에 노출하지 않는다.
- CancelResult 제안: paymentId, status, cancellationId, canceledAmount, canceledAt, settlementStatus?. 상태 조회·내부 상세와 API-10 응답은 서로 다른 DTO다.
- mutable Entity/JPA proxy 반환 금지. 공개 DTO/record는 제공 api 패키지에 둔다. Q01 포트안은 P5 소유 계약 타입이며 P6 구현 클래스를 참조하지 않는다.
- 각 항목의 연관 테이블은 해당 소유 모듈이 접근한다는 뜻이다. P5가 P6/P1 테이블을 직접 접근하는 허용 목록이 아니다.

성공 HTTP 상태 제안: 세션 최초 생성·안정적인 접수 결과 재생은 201, 조회·카드 목록·결제 확정·취소·토큰 재발급은 200. 세션 생성/재발급의 checkoutUrl은 최초 전달 전용으로 전체 원응답 재생의 예외이며, 재생은 checkoutUrl=null과 tokenReissueRequired=true를 반환한다. 카드 업무 거절도 정상 결제 처리 결과이므로 confirm의 200 응답에 DECLINED를 담는다. 기술 장애는 503이며 성공 응답으로 포장하지 않는다. 전체 HTTP 상태·오류 포맷은 Q15 승인 대상이다.

## 번호 충돌

S5의 `P5-API-09`는 P6가 호출하는 **매입 직전 잠금**이다. S6의 `API-09`는 P5가 호출하는 **P6 취소 가능 조회**다. 아래에서는 후자를 `P6-API-09`, 취소 반영을 `P6-API-10`으로 표기하여 기존 기능을 삭제하지 않는다. 정본 번호 승인 Q15가 필요하다.

## P5-API-01 — 결제 세션 생성

- **제공 / 호출:** P5 / CakeShop 서버
- **URL 또는 Java 메서드:** POST /api/v1/checkout/sessions (S5)
- **호출 시점:** 주문 생성 후 DonWorry 결제창 진입 전
- **Request DTO:** SessionCreateRequest={orderExternalId, customerRef, amount, currency, buyerIp, deviceId?, orderExpiresAt, returnUrl}; tenantId는 인증 컨텍스트. 헤더 멱등 키·nonce·요청 시각·서명 규격은 Q11.
- **Response DTO:** SessionCreateResponse={paymentId,status=REQUESTED,checkoutUrl?,orderExpiresAt,tokenReissueRequired}. 최초 성공은 checkoutUrl 전달/false, 멱등 재생은 저장된 접수 결과와 checkoutUrl=null/true. 원문 토큰은 저장하지 않고 P5-UI-03 재발급으로 복구한다. 재생 status는 생성 당시 값이며 현재 상태는 P5-API-02로 확인한다.
- **검증 규칙:** amount>0·통화 허용, 미래 주문 기한, tenant의 주문번호 UNIQUE, returnUrl 허용 origin/path. 금액은 CakeShop 서버가 제공하고 브라우저 값을 신뢰하지 않음.
- **오류·예외:** 인증 오류, ACTIVE_ORDER_EXISTS, INVALID_REQUEST. DB 기술 오류는 전체 롤백.
- **멱등성:** (tenant,key)+requestHash. 다른 키의 같은 활성 주문은 자동 새 결제 대신 409. 같은 키는 새 결제를 만들지 않는다. 안정적인 접수 결과만 재생하며 checkout 진입 정보 복구는 별도 재발급한다(Q11 팀 합의 대기).
- **트랜잭션 경계:** T0. 인증 nonce 소비 정책과 업무 트랜잭션 분리 제안. payment·history·멱등 완료 응답은 함께 커밋.
- **연관 테이블:** P5 payment, payment_status_history, idempotency_record, request_nonce; P1 tenant는 공개 API 논리 조회.
- **확정 여부:** 사용자 선택(질문 3-A) / 팀 합의 대기. URL·필드·보안 세부는 PROPOSED

## P5-API-02 — 결제 상태 조회

- **제공 / 호출:** P5 / CakeShop 서버
- **URL 또는 Java 메서드:** GET /api/v1/payments/{paymentId} (S5)
- **호출 시점:** 복귀 화면·주문 상태 확인·응답 유실 후
- **Request DTO:** PaymentStatusQuery={paymentId}; 인증 tenant·nonce.
- **Response DTO:** PaymentView. HELD 승인 사실 포함; 실제 고객 문구·주문 상태는 Q09.
- **검증 규칙:** payment의 tenant와 인증 tenant 일치. UUID·조회 권한 검증.
- **오류·예외:** 404/403/인증 오류. 기술 장애를 DECLINED 응답으로 대체하지 않음.
- **멱등성:** 읽기는 반복 가능. 요청 nonce는 새 값. 현재 상태를 반환하며 과거 멱등 응답을 재생하지 않음.
- **트랜잭션 경계:** 짧은 readOnly 조회, payment 비관적 쓰기 락 불필요.
- **연관 테이블:** P5 payment, 필요 시 card_approval 및 request_nonce. history 전체는 별도 내부 API.
- **확정 여부:** PROPOSED(S5 협의 중), HELD 승인 사실 CONFIRMED(S6)

## P5-API-03 — 승인 결제 전체취소

- **제공 / 호출:** P5 / CakeShop 서버
- **URL 또는 Java 메서드:** POST /api/v1/payments/{paymentId}/cancel (S5)
- **호출 시점:** 승인 결제 취소 요청
- **Request DTO:** CancelRequest={reasonCode?}; paymentId 경로, tenant 인증, 멱등 키. canceledAmount는 payment 금액에서 산출.
- **Response DTO:** CancelResult. 동일 업무 키 재호출은 저장 결과. 성공 cancellationId 전략 Q04.
- **검증 규칙:** APPROVED 및 P6 취소 허용. HELD의 CakeShop 직접 취소 권한은 OPEN이며 이 초안에서는 P3 결정 경로만 정의. REQUESTED 기한 만료는 EXPIRED 방향(Q12 팀 합의 대기). 고객 자발적 중단은 미결.
- **오류·예외:** STATE_CONFLICT, CANCELLATION_NOT_ALLOWED, 멱등 충돌, 기술 오류. 취소 불가 409는 원상태 유지; 허용 조건이 바뀔 수 있어 불가 응답 영구 캐시는 권장하지 않음.
- **멱등성:** 성공 응답 원자 저장. 동일 키·동일 본문 재생. 다른 키로 CANCELED 재호출 시 기존 성공 재사용안은 Q04, 이중 차감은 항상 금지.
- **트랜잭션 경계:** T4. P6 API-09/10 포함 같은 TM·트랜잭션 조건, 실패 전체 롤백.
- **연관 테이블:** P5 payment/history/card_approval/mock_card/idempotency_record/request_nonce; P6 소유 captures/order_fulfillments/settlements는 계약으로만 접근.
- **확정 여부:** PROPOSED(S5/S6), 구조 CONFLICT Q01

## P5-UI-01 — 결제창 Mock 카드 선택 정보

- **제공 / 호출:** P5 / DonWorry 결제창
- **URL 또는 Java 메서드:** GET /api/v1/checkout/sessions/{paymentId}/cards (제안 URL)
- **호출 시점:** 유효 checkout 세션에서 카드 선택 전
- **Request DTO:** CheckoutCardsQuery={paymentId}; checkoutToken은 로그·URL 재노출을 줄이는 전달 규격 Q11.
- **Response DTO:** CheckoutCardsResponse={paymentId,amount,currency,cards:[{cardToken,maskedPan,label}]}. 시연용 공용 Mock 카드 범위만 제공.
- **검증 규칙:** checkout 해시 일치, 세션 REQUESTED, 주문 기한, 노출 가능한 Mock 카드 목록. 비밀 토큰 전체 로깅 금지.
- **오류·예외:** CHECKOUT_EXPIRED, AUTHENTICATION_FAILED, STATE_CONFLICT.
- **멱등성:** 읽기 반복 가능. 카드 선택만으로 승인·한도 변경 없음.
- **트랜잭션 경계:** readOnly. 결제 실행 트랜잭션 시작하지 않음.
- **연관 테이블:** P5 payment, card_token, mock_card.
- **확정 여부:** PROPOSED; URL·토큰 인증·카드 범위 OPEN Q11

## P5-UI-02 — 선택 카드 결제 확정

- **제공 / 호출:** P5 / DonWorry 결제창
- **URL 또는 Java 메서드:** POST /api/v1/checkout/sessions/{paymentId}/confirm (제안 URL)
- **호출 시점:** 고객이 결제 버튼 선택
- **Request DTO:** ConfirmRequest={cardToken}; checkoutToken·멱등 키. 금액·통화는 저장된 payment에서 사용.
- **Response DTO:** PaymentView. ALLOW/HOLD Mock 성공은 APPROVED/HELD, REJECT/Mock 업무 거절은 DECLINED.
- **검증 규칙:** 인증·권한·동일 멱등 결과를 먼저 확인한 뒤 신규 실행에만 유효 세션·REQUESTED·주문 기한·허용 카드를 검사한다. 동일 키로 카드 변경 금지. 신규 실행 시 기한 경과는 EXPIRED 전이와 410 결과로 처리하는 제안이며 카드 승인 없음. 기술 실패 뒤 카드 변경 규칙은 Q05 팀 합의 대기.
- **오류·예외:** FDS_UNAVAILABLE는 롤백·재시도; 업무 거절은 정상 결과; 세션/멱등/상태 충돌은 오류.
- **멱등성:** payment 잠금 + idempotency_record + 카드 requestId. FDS evaluationId 고정 재생 계약 필요.
- **트랜잭션 경계:** T1. P2 TF는 독립 저장한다. P2 실패면 카드 호출 금지, P5 롤백. Mock 원장·상태·한도·이력·응답은 단일 커밋. TF 이후 승인 직전 주문 기한 재검사.
- **연관 테이블:** P5 7개 중 nonce는 인증 방식에 따라 사용; P2 평가·경보 데이터는 P2 소유.
- **확정 여부:** PROPOSED; FDS 분기 CONFIRMED(S6)

## P2-EVAL — FDS 평가

- **제공 / 호출:** P2 / P5
- **URL 또는 Java 메서드:** FdsEvaluator.evaluate(FdsEvaluationRequest) (제안 Java)
- **호출 시점:** T1에서 카드 승인 직전
- **Request DTO:** {evaluationId,paymentId,tenantId,customerRef,amount,currency,cardFingerprint,deviceId?,buyerIp,hasPriorApprovedCard}. 과거 카드 사용 계산 주체는 ADR-0001 제안·Q05.
- **Response DTO:** {evaluationId,decision:ALLOW|HOLD|REJECT,score,reasonCodes,assessedAt}. decision을 P5가 임의 재계산하지 않고 범위 일관성만 확인.
- **검증 규칙:** 동일 evaluationId의 입력 동일성, score 0~49/50~80/81 이상 경계. 잘못된 decision/score 응답은 기술 계약 오류, 승인 금지.
- **오류·예외:** FdsUnavailable/FdsTimeout/InvalidFdsResponse 예외. 업무 REJECT와 구분.
- **멱등성:** 질문 4-A: 실제 평가 시도를 집계하되 동일 논리 시도의 기술 재전송은 중복 집계하지 않는다. evaluationId·원입력 보존·카드 변경 규칙·재사용 시간은 P2 팀 합의 대기.
- **트랜잭션 경계:** P2 TF 별도 트랜잭션으로 저장하며 P5 T1 롤백에 연동해 삭제하지 않는다. P2는 P5 테이블 조회·잠금·역호출 금지. TF의 시도 접수/실패 기록 내구성·전파 구현은 팀 합의 대기.
- **연관 테이블:** P5 payment/card_token/card_approval 읽기 재료; P2 소유 평가 기록(테이블 이름 미확인).
- **확정 여부:** 집계 방향 사용자 선택(질문 4-A) / 팀 합의 대기. 점수 경계 CONFIRMED(S6), 상세 중복·전파 계약 미결

## P5-API-04 — 결제 상세

- **제공 / 호출:** P5 / P3·P4(investigation/report)
- **URL 또는 Java 메서드:** PaymentReader.getPayment(PaymentQuery) (제안 Java)
- **호출 시점:** 케이스 생성·조사 자료 수집
- **Request DTO:** {paymentId,tenantId}; 내부 권한 컨텍스트.
- **Response DTO:** PaymentDetail={payment:PaymentView,customerRef,deviceId?,maskedPan?,version}. 필요한 위험 정보 추가는 최소 범위 합의.
- **검증 규칙:** 가맹점·조사 권한, 존재 여부. 내부 호출도 tenant 혼동 금지.
- **오류·예외:** PaymentNotFound/TenantForbidden.
- **멱등성:** 읽기 반복 가능.
- **트랜잭션 경계:** readOnly; 결과를 근거로 쓰기할 때 다시 잠금·상태 검증.
- **연관 테이블:** P5 payment/card_token.
- **확정 여부:** PROPOSED(S5 초안)

## P5-API-05 — 보류 결제 목록

- **제공 / 호출:** P5 / P3
- **URL 또는 Java 메서드:** PaymentReader.findHeld(HeldQuery) (제안 Java)
- **호출 시점:** 조사 목록·케이스 연결
- **Request DTO:** {tenantId,customerRef?,cursor?,limit}.
- **Response DTO:** {items:[PaymentView],nextCursor?}; status HELD만.
- **검증 규칙:** 권한, limit 범위, 커서 정렬 (requestedAt,paymentId) 고정.
- **오류·예외:** InvalidQuery/TenantForbidden.
- **멱등성:** 읽기 반복 가능하나 동시 변경에 따른 목록 변화 허용.
- **트랜잭션 경계:** readOnly. 매입/판정 잠금 대체 불가.
- **연관 테이블:** P5 payment.
- **확정 여부:** PROPOSED(S5 초안)

## P5-API-06 — 결제 상태 이력

- **제공 / 호출:** P5 / P3·P4
- **URL 또는 Java 메서드:** PaymentReader.getHistory(HistoryQuery) (제안 Java)
- **호출 시점:** 조사 보고서·판정 근거
- **Request DTO:** {paymentId,tenantId,cursor?,limit}.
- **Response DTO:** {items:[{historyId,fromStatus?,toStatus,requestedBy,reasonCode?,changedAt}],nextCursor?}.
- **검증 규칙:** 조사 권한·tenant, 안정적인 페이지 정렬. timestamp만으로 동시 전이 순서 단정 금지.
- **오류·예외:** PaymentNotFound/TenantForbidden/InvalidQuery.
- **멱등성:** 읽기 반복 가능.
- **트랜잭션 경계:** readOnly.
- **연관 테이블:** P5 payment, payment_status_history.
- **확정 여부:** PROPOSED(S5 초안)

## P5-API-07 — HELD 직원 결정 실행

- **제공 / 호출:** P5 / P3
- **URL 또는 Java 메서드:** ReviewExecutor.execute(ReviewDecisionRequest) (제안 Java)
- **호출 시점:** P3가 직원 결정을 확정한 뒤
- **Request DTO:** {decisionId,reviewId?,paymentId,tenantId,action:KEEP|CANCEL,actorId,reasonCode?}. decisionId는 변경 불가능한 실행 식별자, reviewId의 유일성은 미확인.
- **Response DTO:** {decisionId,paymentId,status,cancellationId?,executedAt}. KEEP이면 cancellationId NULL, CANCEL 성공이면 Q04의 ID.
- **검증 규칙:** P3 권한 있는 서버 결정, tenant 일치, 현재 HELD. 같은 decisionId에 다른 payment/action/본문 거부.
- **오류·예외:** StateConflict/IdempotencyConflict/InvalidDecision/취소 기술 오류. 다른 결정으로 전이한 상태를 동일 성공으로 오인하지 않음.
- **멱등성:** 키 p3:decision:{decisionId} 제안. KEEP/CANCEL 모두 idempotency_record에 원응답 저장. CANCEL card requestId는 decisionId+operation에서 안정 생성하는 규격 Q03.
- **트랜잭션 경계:** KEEP T2, CANCEL T3 모두 P3 결정 저장과 공동 커밋. P3 진입 REQUIRED, P5 실행 MANDATORY 제안. P3 또는 P5 실패 시 결정·결제·원장·응답 전체 롤백. 재시도 decisionId 안정 보존 방식은 Q03 팀 합의 대기.
- **연관 테이블:** P3 소유 결정 데이터와 P5 payment/history/idempotency_record를 공동 커밋. CANCEL이면 mock_card/card_approval, P6 조건부. P3 테이블명은 미확인.
- **확정 여부:** 처리 정책 CONFIRMED(S6), 공동 커밋 사용자 선택(질문 5-A) / 팀 합의 대기. DTO·ID 규격은 PROPOSED

## P5-API-08 — 매입 후보 조회

- **제공 / 호출:** P5 / P6 배치
- **URL 또는 Java 메서드:** PaymentReader.findApproved(CaptureCandidatesQuery) (제안 Java)
- **호출 시점:** P6 매입 배치 후보 수집
- **Request DTO:** {cursor?,limit,approvedBefore?}; 범위 제한 권한.
- **Response DTO:** {items:[{paymentId,tenantId,amount,currency,approvedAt}],nextCursor?}.
- **검증 규칙:** APPROVED만, HELD 제외. 이미 매입 여부는 P6 소유로 확인.
- **오류·예외:** InvalidQuery/TechnicalFailure.
- **멱등성:** 배치마다 처음부터 APPROVED 후보를 재조회하고 P6가 기매입 paymentId를 제외한다. cursor는 해당 실행 안에서만 사용, 다음 실행에 승인 시각 watermark를 넘기지 않는다. P6는 paymentId 기준 중복 매입을 방지한다.
- **트랜잭션 경계:** readOnly, 후보 조회 자체로 매입 자격·락 보장 안 함.
- **연관 테이블:** P5 payment. P6 captures의 중복 제약은 P6 확인.
- **확정 여부:** APPROVED만 CONFIRMED(S6), 재조회 방식 사용자 선택(질문 7-A) / 팀 합의 대기. 서명 PROPOSED

## P5-API-09 — 매입 직전 잠금·검증

- **제공 / 호출:** P5 / P6 배치
- **URL 또는 Java 메서드:** SettlementGate.lockApprovedPayment(CaptureLockRequest) (제안 Java)
- **호출 시점:** P6 T5 트랜잭션 안, P6 행 잠금/생성보다 먼저
- **Request DTO:** {paymentId}.
- **Response DTO:** {paymentId,tenantId,amount,currency,approvedAt,version}; Entity 반환 금지.
- **검증 규칙:** 활성 트랜잭션, PESSIMISTIC_WRITE 획득 후 최신 APPROVED 검증. HELD·CANCELED 거부.
- **오류·예외:** PaymentNotApproved/PaymentNotFound/LockTimeout/트랜잭션 없음 오류.
- **멱등성:** 잠금 획득 자체는 반복 가능, 실제 captures 중복 쓰기는 P6가 차단.
- **트랜잭션 경계:** MANDATORY 제안. P6 커밋까지 같은 물리 트랜잭션이 유지되어야 함. REQUIRES_NEW 금지.
- **연관 테이블:** P5 payment; 이후 P6 captures/order_fulfillments/settlements는 P6만 접근.
- **확정 여부:** 목적 S5 확인, 전파 PROPOSED

## P6-API-09 — 취소 가능 조회

- **제공 / 호출:** P6 업무 구현 / 기존 계약 호출자 P5
- **URL 또는 Java 메서드:** getCancellationEligibility(paymentId) (S6 Java)
- **호출 시점:** payment 잠금 이후, Mock 취소 이전
- **Request DTO:** UUID paymentId.
- **Response DTO:** {cancellable:boolean,refundDeadlineAt:Instant?,settlementStatus:String?,reasonCode:String?}. 필드 유지.
- **검증 규칙:** 현재 매입·정산·마감 검증. null 마감이 무조건 취소 가능을 뜻하지 않음. Q13 경계·결정 규칙 확인.
- **오류·예외:** P6 기술 예외는 취소 중단. 불허는 cancellable=false와 사유 반환. 의미 불명확 응답은 fail closed.
- **멱등성:** 현재 상태를 반환하는 읽기; 과거 허용 결과를 캐시해 취소하지 않음.
- **트랜잭션 경계:** T3/T4 동일 트랜잭션 MANDATORY 제안. P6 현재 행 읽기·잠금 정책 필요.
- **연관 테이블:** P6 captures/order_fulfillments/settlements. P5 직접 조회 금지.
- **확정 여부:** 필드·메서드 CONFIRMED(S6), P5 포트 소유 방향 사용자 선택 / 팀 합의 대기(Q01). 정산 구조·마감은 질문 6 OPEN(Q13)

## P6-API-10 — 정산 취소 반영

- **제공 / 호출:** P6 업무 구현 / 기존 계약 호출자 P5
- **URL 또는 Java 메서드:** applyPaymentCancellation(cancellationId,paymentId,canceledAmount,canceledAt) (S6 Java)
- **호출 시점:** Mock 취소·P5 상태 변경 후, 동일 커밋 전
- **Request DTO:** {cancellationId:UUID,paymentId:UUID,canceledAmount:long,canceledAt:Instant}.
- **Response DTO:** {paymentId,settlementStatus?}. 매입 전 null, 매입 후 CANCELED. applied 필드 제외.
- **검증 규칙:** 전체 원결제 금액·원승인 취소 시각 일치, API-09 이후 동일 락 유지. 동일 ID 다른 본문 거부.
- **오류·예외:** CancellationConflict/SettlementUpdateFailure. P5는 오류 전파·전체 롤백. 매입후 정산행 부재 처리 Q13.
- **멱등성:** 동일 cancellationId 동일 본문 재호출 중복 변경 금지. 저장 위치·보존기간은 P6 OPEN, 가상 테이블 추가 금지.
- **트랜잭션 경계:** T3/T4 동일 TM·트랜잭션 MANDATORY 제안. 비동기 AFTER_COMMIT로 바꾸면 이 계약과 다름.
- **연관 테이블:** P6 captures/order_fulfillments/settlements만. P5의 card_approval에는 직접 접근하지 않음.
- **확정 여부:** 필드·메서드 CONFIRMED(S6), P5 포트 방향 사용자 선택 / 팀 합의 대기(Q01). 질문 6의 정산 단위·상태 의미와 취소 식별자 저장 위치 OPEN(Q13·Q04)

## P5-UI-03 — checkout 토큰 재발급

- **제공 / 호출:** P5 / 인증된 CakeShop 서버. 고객 브라우저가 가맹점 비밀키로 호출하지 않음.
- **URL 또는 Java 메서드:** POST /api/v1/checkout/sessions/{paymentId}/token/reissue (제안 URL).
- **호출 시점:** 세션 생성 응답 유실 또는 기존 진입 정보 복구가 필요할 때.
- **Request DTO:** TokenReissueRequest={}; paymentId 경로, 인증 tenant·새 nonce·재발급 멱등 키.
- **Response DTO:** {paymentId,checkoutUrl?,orderExpiresAt,tokenReissueRequired}. 최초 커밋 성공은 새 토큰 포함 URL/false, 동일 키 재생은 URL=null/true. 응답 유실이면 새 재발급 키로 요청한다.
- **검증 규칙:** 인증 tenant 소유 확인 → 잠금·동일 키 재생 → 신규 재발급이면 REQUESTED·주문 기한 확인. 승인·취소·EXPIRED 이후 재발급 금지. 토큰 교체가 주문 기한을 연장하지 않음.
- **오류·예외:** AUTHENTICATION_FAILED/TENANT_FORBIDDEN/STATE_CONFLICT/CHECKOUT_EXPIRED/IDEMPOTENCY_CONFLICT/기술 오류.
- **멱등성:** 같은 키는 토큰 해시를 다시 교체하지 않음. 서로 다른 키 동시 요청은 payment 잠금으로 직렬화하고 마지막 커밋 토큰만 유효. CakeShop은 재발급 요청을 직렬화하고 응답 순서 역전 시 재확인해야 함. 헤더·빈 요청의 hash 범위는 팀 합의 대기.
- **트랜잭션 경계:** TR. 새 token hash와 안정적인 접수 결과를 공동 커밋, 원문은 저장하지 않고 커밋 후 최초 응답에만 제공. 장애 시 기존 해시 유지. 응답 유실은 새 재발급으로 복구.
- **연관 테이블:** P5 payment, idempotency_record, request_nonce. 신규 업무 테이블 없음.
- **확정 여부:** 사용자 선택(질문 3-A) / 팀 합의 대기. URL·필드·동시 요청 처리 세부는 PROPOSED.

## 이벤트 계약 후보 (질문 8 OPEN — 미결, Q07)

| 임시 ID | 제공 → 소비 | payload 제안 | 시점·중복·실패 |
|---|---|---|---|
| P2-EVT-ALERT | P2 → P3 | eventId,evaluationId,paymentId,tenantId,score,decision | 위험 평가 경보. HELD 확정 증거가 아님. P2 커밋/재발행 규격 OPEN |
| P5-EVT-HELD | P5 → P3 | eventId,paymentId,tenantId,version,approvedAt | T1 커밋 후 전달. P3 eventId 중복 차단·현재 상태 재조회. 내구성/재시도 Q07 |
| P5-EVT-CANCELED | P5 → 관심 모듈 | eventId,paymentId,cancellationId,version,canceledAt | 성공 커밋 후 통지. 포트안의 API-10을 대체하지 않으며 P6가 같은 취소를 다시 적용하지 않게 함 |

아래 후보를 실제 케이스 생성 규칙으로 채택한 것은 아니다. HELD를 기준으로 생성할지, REJECT·카드 거절도 조사할지, 어느 모듈이 어떤 알림을 발행할지는 질문 8 OPEN이다. 커밋 후 단순 호출만으로 전달을 보장하지 않으며 전달 기록·재시도·중복 방지의 저장 경계도 확정 전이다.

이벤트 클래스는 발행 모듈 api에 두며 수신 모듈 타입을 참조하지 않는다. P3는 이벤트 도착 순서를 믿지 않고 payment.version/현재 상태로 판단한다. 이벤트 저장소 의존성이 있다는 사실만으로 이 이벤트가 내구성 있게 발행된다고 보장하지 않는다. 신뢰 전달 구현·실패 알림은 별도 검증한다.
