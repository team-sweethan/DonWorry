package com.donworry.payment.service;

import com.donworry.identity.api.TenantReader;
import com.donworry.identity.api.TenantSummary;
import com.donworry.payment.dto.CheckoutResponses;
import com.donworry.payment.dto.MerchantPaymentResponse;
import com.donworry.payment.dto.PaymentSessionCreateRequest;
import com.donworry.payment.dto.PaymentSessionResponse;
import com.donworry.payment.entity.PaymentRiskSignals;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.entity.PaymentSessionStatus;
import com.donworry.payment.entity.PaymentStatus;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

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
    private final PaymentRequestFlowService flow;
    private final MerchantPaymentQueryService query;
    private final TenantReader tenants;
    private final TransactionTemplate transactions;
    private final String checkoutBaseUrl;

    public PaymentSessionService(
            PaymentSessionRepository sessions,
            PaymentRequestFlowService flow,
            MerchantPaymentQueryService query,
            TenantReader tenants,
            PlatformTransactionManager transactionManager,
            @Value("${donworry.applications.public-base-url:http://localhost:5173}") String checkoutBaseUrl) {
        this.sessions = sessions;
        this.flow = flow;
        this.query = query;
        this.tenants = tenants;
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
        if (body.notifyUrl() != null) requireUrl(body.notifyUrl());

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
                session.getStatus() == PaymentSessionStatus.OPEN && session.expired(Instant.now()),
                cards,
                session.getPaymentId() == null ? null : result(session),
                cancelUrl(session),
                origin(session.getSuccessUrl()));
    }

    // 결제창에서 결제한다. 같은 세션의 재요청은 같은 결제 시도(attemptKey)라 결제가 두 번 생기지 않는다.
    public CheckoutResponses.Result pay(String sessionKey, CheckoutResponses.PayRequest body, String ipAddress) {
        var session = requireSession(sessionKey);
        if (session.getPaymentId() != null) return result(session);
        if (session.expired(Instant.now())) throw new PaymentHttpException(409, "SESSION_EXPIRED", "결제 가능 시간이 지났습니다.");
        var card = CheckoutMockCard.from(body.card()).orElseThrow(this::invalid);
        String internalCustomer = session.getMerchantId() + ":" + session.getCustomerRef();
        var command = new PaymentRequestCommand(
                session.getMerchantId(),
                session.getOrderRef(),
                internalCustomer,
                session.getAmount(),
                session.getOrderCreatedAt(),
                session.getAttemptKey(),
                new PaymentRiskSignals(
                        body.deviceId(),
                        card.token(internalCustomer),
                        card.displayLabel(),
                        null,
                        ipAddress,
                        session.getBuyerEmail()));
        // 접수·평가·승인은 트랜잭션 밖에서 기존 흐름이 처리한다(P2 호출 포함).
        var receipt = flow.submit(command);
        var linked = transactions.execute(status -> {
            var locked = sessions.findByIdForUpdate(session.getSessionId()).orElseThrow();
            locked.submitted(receipt.paymentId(), Instant.now());
            return locked;
        });
        return result(Objects.requireNonNull(linked));
    }

    public CheckoutResponses.Result result(String sessionKey) {
        var session = requireSession(sessionKey);
        if (session.getPaymentId() == null) return new CheckoutResponses.Result(null, false, false, null);
        return result(session);
    }

    // 승인되면(매입 전·보류 포함) 성공으로 돌려보낸다. 직원 거절(VOIDED)은 이후 웹훅으로 알린다.
    CheckoutResponses.Result result(PaymentSession session) {
        MerchantPaymentResponse payment = query.findPayment(session.getMerchantId(), session.getPaymentId())
                .orElseThrow();
        PaymentStatus status = payment.paymentStatus();
        boolean authorized = payment.authorized();
        if (authorized
                && (status == PaymentStatus.PENDING
                        || status == PaymentStatus.HELD
                        || status == PaymentStatus.CAPTURED)) {
            String url = UriComponentsBuilder.fromUriString(session.getSuccessUrl())
                    .queryParam("paymentKey", payment.paymentId())
                    .queryParam("orderId", session.getOrderRef())
                    .queryParam("amount", session.getAmount())
                    .encode()
                    .build()
                    .toUriString();
            return new CheckoutResponses.Result(status, true, false, url);
        }
        if (status == PaymentStatus.PENDING) return new CheckoutResponses.Result(status, false, true, null);
        String code = payment.declineCode() != null ? payment.declineCode() : status.name();
        String url = UriComponentsBuilder.fromUriString(session.getFailUrl())
                .queryParam("code", code)
                .encode()
                .build()
                .toUriString();
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
        return UriComponentsBuilder.fromUriString(session.getFailUrl())
                .queryParam("code", "USER_CANCELED")
                .encode()
                .build()
                .toUriString();
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

    private URI requireUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawFragment() != null) throw invalid();
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
