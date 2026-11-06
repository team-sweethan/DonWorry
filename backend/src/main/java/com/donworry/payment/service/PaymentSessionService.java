package com.donworry.payment.service;

import com.donworry.identity.api.TenantReader;
import com.donworry.identity.api.TenantSummary;
import com.donworry.payment.dto.CheckoutResponses;
import com.donworry.payment.dto.MerchantPaymentResponse;
import com.donworry.payment.dto.PaymentSessionCreateRequest;
import com.donworry.payment.dto.PaymentSessionResponse;
import com.donworry.payment.entity.PaymentRequest;
import com.donworry.payment.entity.PaymentRiskSignals;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.entity.PaymentSessionStatus;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentRequestRepository;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

// 돈워리 호스팅 결제창: 가맹점 서버의 결제 준비 → 구매자 결제창 → 기존 결제 접수 흐름 연결.
// 결제 접수·평가·승인은 기존 PaymentRequestFlowService를 그대로 쓴다. 이 서비스는 세션과 결제창 입출력만 맡는다.
@Service
public class PaymentSessionService {
    // 결제 접수 기한과 같다(orderCreatedAt + 10분, #149).
    private static final Duration REQUEST_WINDOW = Duration.ofMinutes(10);
    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentSessionRepository sessions;
    private final PaymentRequestRepository requests;
    private final PaymentRequestFlowService flow;
    private final MerchantPaymentQueryService query;
    private final TenantReader tenants;
    private final PaymentWebhookTargetPolicy webhookTargets;
    private final TransactionTemplate transactions;
    private final String checkoutBaseUrl;

    public PaymentSessionService(
            PaymentSessionRepository sessions,
            PaymentRequestRepository requests,
            PaymentRequestFlowService flow,
            MerchantPaymentQueryService query,
            TenantReader tenants,
            PaymentWebhookTargetPolicy webhookTargets,
            PlatformTransactionManager transactionManager,
            @Value("${donworry.applications.public-base-url:http://localhost:5173}") String checkoutBaseUrl) {
        this.sessions = sessions;
        this.requests = requests;
        this.flow = flow;
        this.query = query;
        this.tenants = tenants;
        this.webhookTargets = webhookTargets;
        this.transactions = new TransactionTemplate(transactionManager);
        this.checkoutBaseUrl = checkoutBaseUrl.replaceAll("/+$", "");
    }

    // 같은 멱등 키·같은 내용이면 같은 세션을 돌려준다. 다른 내용이면 409.
    public PaymentSessionResponse create(Long merchantId, String key, PaymentSessionCreateRequest body) {
        if (key == null || !key.matches(UUID_PATTERN)) throw invalid();
        UUID requestKey = UUID.fromString(key);
        long customer;
        try {
            customer = Long.parseLong(body.customerRef());
        } catch (NumberFormatException e) {
            throw invalid();
        }
        if (customer <= 0
                || !body.orderCreatedAt().equals(body.orderCreatedAt().truncatedTo(ChronoUnit.MICROS))
                || !body.orderRef().equals(body.orderRef().strip())) throw invalid();
        URI success = requireUrl(body.successUrl());
        URI fail = requireUrl(body.failUrl());
        if (!sameOrigin(success, fail)) throw invalid();
        // 웹훅 주소는 운영에서 https · 공인 주소만 받는다(로컬 개발은 설정으로 허용). 보내기 직전에도 다시 본다 (ADR-0064 남은 결정)
        if (body.notifyUrl() != null && !webhookTargets.acceptableAtCreate(requireUrl(body.notifyUrl())))
            throw invalid();
        // 구매자 이메일은 결제 때 FDS 입력(PaymentRiskSignals)과 같은 규칙으로 검사한다.
        // 세션에 고정되어 구매자가 고칠 수 없으므로 잘못된 값은 결제 때 500이 아니라 여기서 400으로 막는다
        if (body.buyerEmail() != null && !validBuyerEmail(body.buyerEmail())) throw invalid();

        var existing = sessions.findByMerchantIdAndRequestKey(merchantId, requestKey);
        if (existing.isPresent()) return reuse(existing.get(), body);

        Instant now = Instant.now();
        Instant expiresAt = body.orderCreatedAt().plus(REQUEST_WINDOW);
        if (!now.isBefore(expiresAt)) throw new PaymentHttpException(409, "ORDER_EXPIRED", "주문의 결제 접수 기한이 지났습니다.");
        var session = PaymentSession.builder()
                .sessionKey(newSessionKey())
                .merchantId(merchantId)
                .requestKey(requestKey)
                .attemptKey(UUID.randomUUID())
                .orderRef(body.orderRef())
                .customerRef(body.customerRef())
                .amount(body.amount())
                .orderCreatedAt(body.orderCreatedAt())
                .orderName(body.orderName())
                .buyerEmail(body.buyerEmail())
                .successUrl(body.successUrl())
                .failUrl(body.failUrl())
                .notifyUrl(body.notifyUrl())
                .expiresAt(expiresAt)
                .createdAt(now)
                .build();
        try {
            return toResponse(transactions.execute(status -> sessions.save(session)));
        } catch (DataIntegrityViolationException race) {
            // 같은 키를 동시에 보낸 경우 먼저 저장된 세션을 다시 읽는다.
            return sessions.findByMerchantIdAndRequestKey(merchantId, requestKey)
                    .map(saved -> reuse(saved, body))
                    .orElseThrow(() -> race);
        }
    }

    // 결제창 화면 정보. 세션 키를 아는 사람만 연다.
    public CheckoutResponses.Session open(String sessionKey) {
        var session = requireSession(sessionKey);
        // 접수됐지만 세션에 연결되기 전에 멈춘 결제도 있다. 만료로 막지 않고 결과를 보이거나 다시 누를 수 있게 한다
        var admitted = admittedPaymentId(session);
        String merchantName = tenants.findById(session.getMerchantId())
                .map(TenantSummary::name)
                .orElse("가맹점");
        var cards = Arrays.stream(CheckoutMockCard.values())
                .map(card -> new CheckoutResponses.Card(
                        card.name(), card.label(), card.masked(), card.issuerCountry(), card.color()))
                .toList();
        return new CheckoutResponses.Session(
                merchantName,
                session.getOrderRef(),
                session.getOrderName(),
                session.getAmount(),
                session.getBuyerEmail(),
                session.getExpiresAt(),
                session.getStatus() == PaymentSessionStatus.OPEN
                        && session.expired(Instant.now())
                        && admitted.isEmpty(),
                cards,
                finishedResult(session, admitted),
                cancelUrl(session),
                origin(session.getSuccessUrl()));
    }

    // 결제창에서 결제한다. 같은 세션의 재요청은 같은 결제 시도(attemptKey)라 결제가 두 번 생기지 않는다.
    public CheckoutResponses.Result pay(String sessionKey, CheckoutResponses.PayRequest body, String ipAddress) {
        var session = requireSession(sessionKey);
        if (session.getPaymentId() != null) return result(session);
        // 카드 목록 · 만료 · 가맹점 정지는 새 접수만 막는다. 같은 결제 시도(attemptKey)가 이미 접수됐으면
        // 평가 · 승인 · 세션 연결 중에 멈춘 것이라 기한이 지났어도 이어서 처리한다 (PR #425 리뷰)
        // 지금은 없는 카드(C · D, #582)로 접수된 시도를 같은 화면에서 다시 눌러도 재개된다 (PR #587 리뷰)
        if (admittedPaymentId(session).isEmpty()) {
            CheckoutMockCard.from(body.card()).orElseThrow(this::invalid);
            if (session.expired(Instant.now()))
                throw new PaymentHttpException(409, "SESSION_EXPIRED", "결제 가능 시간이 지났습니다.");
            // 세션을 만든 뒤 가맹점이 정지됐을 수 있다. 아직 접수하지 않은 결제는 시작 직전에 다시 확인한다
            tenants.findById(session.getMerchantId())
                    .filter(tenant -> "ACTIVE".equals(tenant.status()))
                    .orElseThrow(() -> new PaymentHttpException(409, "MERCHANT_INACTIVE", "지금은 이 가맹점에서 결제할 수 없습니다."));
        }
        // 처음 누른 카드·기기·IP를 먼저 고정한다. 접수 뒤 평가·승인 중 실패해 다시 눌러도
        // 같은 값으로 같은 결제 시도를 이어 간다(카드를 바꿔 다시 눌러도 처음 값을 쓴다)
        var fixed = Objects.requireNonNull(transactions.execute(status -> {
            var locked = sessions.findByIdForUpdate(session.getSessionId()).orElseThrow();
            locked.fixAttempt(body.card(), body.deviceId(), ipAddress, Instant.now());
            // 지금은 없는 카드(C · D, #582)로 고정만 되고 접수되지 않았으면 이번에 고른 카드로 바꾼다.
            // 접수된 요청이 없어 같은 시도 키에 다른 신호가 남지 않는다. 그대로 두면 만료까지 계속 400 (PR #587 리뷰)
            if (CheckoutMockCard.from(locked.getSubmittedCard()).isEmpty()
                    && admittedPaymentId(locked).isEmpty()) locked.replaceRemovedCard(body.card());
            return locked;
        }));
        if (fixed.getPaymentId() != null) return result(fixed);
        String internalCustomer = session.getMerchantId() + ":" + session.getCustomerRef();
        // 이미 접수된 시도를 이어 갈 때는 접수 때 저장한 위험 신호를 그대로 보낸다. 카드 이름이 바뀌었거나(#582)
        // 지금은 없는 카드(C · D)로 접수됐어도 같은 시도로 재개된다 — 새로 만들면 표시값이 달라 키 충돌이 난다 (PR #587 리뷰)
        var signals = requests.findByMerchantIdAndIdempotencyKey(session.getMerchantId(), session.getAttemptKey())
                .map(PaymentRequest::riskSignals)
                .orElseGet(() -> newRiskSignals(fixed, internalCustomer));
        var command = new PaymentRequestCommand(
                session.getMerchantId(),
                session.getOrderRef(),
                internalCustomer,
                session.getAmount(),
                session.getOrderCreatedAt(),
                session.getAttemptKey(),
                signals);
        // 접수·평가·승인은 트랜잭션 밖에서 기존 흐름이 처리한다(P2 호출 포함).
        var receipt = flow.submit(command);
        var linked = transactions.execute(status -> {
            var locked = sessions.findByIdForUpdate(session.getSessionId()).orElseThrow();
            locked.submitted(receipt.paymentId(), Instant.now());
            return locked;
        });
        return result(Objects.requireNonNull(linked));
    }

    // 처음 접수할 결제의 위험 신호. 고정된 카드 · 기기 · IP로 만든다.
    private PaymentRiskSignals newRiskSignals(PaymentSession fixed, String internalCustomer) {
        var card = CheckoutMockCard.from(fixed.getSubmittedCard()).orElseThrow(this::invalid);
        return new PaymentRiskSignals(
                fixed.getSubmittedDeviceId(),
                card.token(internalCustomer),
                card.displayLabel(),
                null,
                fixed.getSubmittedIp(),
                fixed.getBuyerEmail());
    }

    public CheckoutResponses.Result result(String sessionKey) {
        var session = requireSession(sessionKey);
        var admitted = admittedPaymentId(session);
        if (admitted.isEmpty()) return new CheckoutResponses.Result(null, false, false, null);
        return result(session, admitted.get());
    }

    // 세션에 연결된 결제, 없으면 이 세션의 결제 시도(attemptKey)로 이미 접수된 결제
    private Optional<Long> admittedPaymentId(PaymentSession session) {
        if (session.getPaymentId() != null) return Optional.of(session.getPaymentId());
        return requests.findByMerchantIdAndIdempotencyKey(session.getMerchantId(), session.getAttemptKey())
                .map(PaymentRequest::getPaymentId);
    }

    // 결제창을 열 때 보일 결과. 처리 중에 멈춘 결제(접수만 되고 연결 전)는 결과 대신 결제 버튼을 보여
    // 다시 누르면 같은 시도를 이어 가게 한다 — 조회(GET)는 처리를 재개하지 않으므로 기다리게 하지 않는다
    private CheckoutResponses.Result finishedResult(PaymentSession session, Optional<Long> admitted) {
        if (admitted.isEmpty()) return null;
        var result = result(session, admitted.get());
        return session.getPaymentId() == null && result.inProgress() ? null : result;
    }

    // 승인되면(매입 전·보류 포함) 성공으로 돌려보낸다. 직원 거절(VOIDED)은 이후 웹훅으로 알린다.
    CheckoutResponses.Result result(PaymentSession session) {
        return result(session, session.getPaymentId());
    }

    private CheckoutResponses.Result result(PaymentSession session, Long paymentId) {
        MerchantPaymentResponse payment =
                query.findPayment(session.getMerchantId(), paymentId).orElseThrow();
        PaymentStatus status = payment.paymentStatus();
        boolean authorized = payment.authorized();
        if (authorized
                && (status == PaymentStatus.PENDING
                        || status == PaymentStatus.HELD
                        || status == PaymentStatus.CAPTURED)) {
            String url = withQuery(
                    session.getSuccessUrl(),
                    "paymentKey",
                    String.valueOf(payment.paymentId()),
                    "orderId",
                    session.getOrderRef(),
                    "amount",
                    String.valueOf(session.getAmount()));
            return new CheckoutResponses.Result(status, true, false, url);
        }
        if (status == PaymentStatus.PENDING) return new CheckoutResponses.Result(status, false, true, null);
        String code = payment.declineCode() != null ? payment.declineCode() : status.name();
        String url = withQuery(session.getFailUrl(), "code", code);
        return new CheckoutResponses.Result(status, authorized, false, url);
    }

    private PaymentSessionResponse reuse(PaymentSession session, PaymentSessionCreateRequest body) {
        if (!session.sameRequest(
                body.orderRef(),
                body.customerRef(),
                body.amount(),
                body.orderCreatedAt(),
                body.orderName(),
                body.buyerEmail(),
                body.successUrl(),
                body.failUrl(),
                body.notifyUrl()))
            throw new PaymentHttpException(409, "IDEMPOTENCY_KEY_CONFLICT", "같은 요청 키에 다른 내용이 있습니다.");
        return toResponse(session);
    }

    private PaymentSessionResponse toResponse(PaymentSession session) {
        return new PaymentSessionResponse(
                session.getSessionKey(),
                checkoutBaseUrl + "/checkout/" + session.getSessionKey(),
                session.getExpiresAt());
    }

    private String cancelUrl(PaymentSession session) {
        return withQuery(session.getFailUrl(), "code", "USER_CANCELED");
    }

    // 가맹점이 등록한 주소의 기존 인코딩(예: next=%2Forders)은 그대로 두고, 새로 붙이는 값만 인코딩한다
    // 전체에 encode()를 걸면 기존 %까지 %25로 바뀐다. 주소는 세션 생성 때 requireUrl로 이미 검사했다
    // encodeQueryParam은 +를 남기지만 가맹점 쿼리 파서(URLSearchParams 등)는 +를 공백으로 읽어 %2B로 바꾼다
    private static String withQuery(String registeredUrl, String... namesAndValues) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(asciiUrl(registeredUrl));
        for (int i = 0; i < namesAndValues.length; i += 2) {
            builder.queryParam(
                    namesAndValues[i],
                    UriUtils.encodeQueryParam(namesAndValues[i + 1], StandardCharsets.UTF_8)
                            .replace("+", "%2B"));
        }
        return builder.build(true).toUriString();
    }

    // 한글 같은 비ASCII 문자는 퍼센트 인코딩하고, 이미 있는 %XX는 바꾸지 않는다
    private static String asciiUrl(String url) {
        return URI.create(url).toASCIIString();
    }

    private PaymentSession requireSession(String sessionKey) {
        if (sessionKey == null || !sessionKey.matches("pss_[0-9a-f]{32}"))
            throw new PaymentHttpException(404, "SESSION_NOT_FOUND", "결제창을 찾을 수 없습니다.");
        return sessions.findBySessionKey(sessionKey)
                .orElseThrow(() -> new PaymentHttpException(404, "SESSION_NOT_FOUND", "결제창을 찾을 수 없습니다."));
    }

    private static String newSessionKey() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "pss_" + HexFormat.of().formatHex(bytes);
    }

    private static boolean validBuyerEmail(String email) {
        try {
            new PaymentRiskSignals(null, null, null, null, null, email);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private URI requireUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawFragment() != null) throw invalid();
            // 결제 뒤 이 주소에 값을 붙여 돌려보낸다(withQuery). 그때 실패하지 않도록 지금 같은 방식으로 읽어 본다
            UriComponentsBuilder.fromUriString(asciiUrl(value)).build(true);
            return uri;
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static String origin(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equals(b.getScheme()) && a.getHost().equals(b.getHost()) && a.getPort() == b.getPort();
    }

    private PaymentHttpException invalid() {
        return new PaymentHttpException(400, "INVALID_REQUEST", "요청 형식을 확인해주세요.");
    }
}
