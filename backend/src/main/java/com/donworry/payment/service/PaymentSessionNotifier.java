package com.donworry.payment.service;

import com.donworry.identity.api.MerchantWebhookSigner;
import com.donworry.payment.dto.MerchantPaymentResponse;
import com.donworry.payment.dto.PaymentWebhookPayload;
import com.donworry.payment.entity.PaymentSession;
import com.donworry.payment.repository.PaymentSessionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

// 호스팅 결제창으로 만든 결제의 상태가 바뀌면 가맹점 notifyUrl로 알린다 (ADR-0064 3절, #402 PR②)
// 1) 마지막으로 알린 (상태, 승인 여부)와 지금 결제가 달라진 세션을 예약하고(DB에서 바로 비교 — 상태가 어디서 바뀌었든 놓치지 않는다)
// 2) 예약 시각이 지난 세션을 보낸다. 2xx면 보낸 값을 기록하고, 아니면 5초부터 두 배씩 최대 5분 간격으로 다시 보낸다
// HTTP 호출은 업무 트랜잭션 밖에서 하고, 결과를 기록할 때만 세션 행을 잠근다. 가맹점은 조회 배치로도 상태를 맞춘다
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.http.enabled", havingValue = "true")
public class PaymentSessionNotifier {

    private static final int BATCH = 50;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final PaymentSessionRepository sessions;
    private final MerchantPaymentQueryService query;
    private final MerchantWebhookSigner signer;
    private final PaymentWebhookTargetPolicy targets;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final RestClient rest;

    public PaymentSessionNotifier(
            PaymentSessionRepository sessions,
            MerchantPaymentQueryService query,
            MerchantWebhookSigner signer,
            PaymentWebhookTargetPolicy targets,
            JsonMapper json,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.query = query;
        this.signer = signer;
        this.targets = targets;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        // 리다이렉트를 따라가지 않는다 — 가맹점 주소가 다른 곳(내부 주소 등)으로 넘기지 못하게
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(TIMEOUT);
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${donworry.payment.webhook.fixed-delay:5s}")
    public void run() {
        Instant now = Instant.now();
        for (Long sessionId : sessions.findChangedSinceNotified(PageRequest.of(0, BATCH))) {
            transactions.executeWithoutResult(status -> sessions.findByIdForUpdate(sessionId)
                    .ifPresent(session -> payment(session)
                            .ifPresent(p -> session.scheduleNotifyIfChanged(
                                    p.paymentStatus().name(), p.authorized(), now))));
        }
        for (Long sessionId : sessions.findDueNotifications(now, PageRequest.of(0, BATCH))) {
            try {
                send(sessionId);
            } catch (RuntimeException e) {
                log.warn(
                        "결제 웹훅 처리 실패 sessionId={} reason={}",
                        sessionId,
                        e.getClass().getSimpleName());
            }
        }
    }

    void send(Long sessionId) {
        PaymentSession session = sessions.findById(sessionId).orElse(null);
        if (session == null || session.getNotifyUrl() == null || session.getPaymentId() == null) return;
        MerchantPaymentResponse payment = payment(session).orElse(null);
        if (payment == null) return;
        String eventId =
                PaymentWebhookPayload.eventId(payment.paymentId(), payment.paymentStatus(), payment.authorized());
        boolean delivered = deliver(session, payment, eventId);
        transactions.executeWithoutResult(status -> sessions.findByIdForUpdate(sessionId)
                .ifPresent(locked -> {
                    // 보낸 값(보내기 직전 조회한 상태)을 기록한다. 그사이 상태가 또 바뀌었으면 다음 실행에서 다시 예약된다
                    if (delivered) locked.notified(payment.paymentStatus().name(), payment.authorized());
                    else locked.notifyFailed(Instant.now());
                }));
    }

    // 2xx 응답만 전달 성공이다. 3xx(리다이렉트를 따르지 않음) · 4xx · 5xx · 시간 초과 · 연결 실패는 다시 보낸다
    private boolean deliver(PaymentSession session, MerchantPaymentResponse payment, String eventId) {
        URI target = URI.create(session.getNotifyUrl());
        if (!targets.deliverable(target)) {
            log.warn("결제 웹훅 대상 주소를 허용하지 않음 sessionId={} host={}", session.getSessionId(), target.getHost());
            return false;
        }
        try {
            byte[] body = json.writeValueAsBytes(new PaymentWebhookPayload(
                    eventId,
                    PaymentWebhookPayload.TYPE_STATUS_CHANGED,
                    payment.paymentId(),
                    session.getOrderRef(),
                    payment.paymentStatus(),
                    payment.authorized(),
                    payment.amount(),
                    payment.statusChangedAt()));
            Optional<MerchantWebhookSigner.SignedWebhook> signed = signer.sign(session.getMerchantId(), eventId, body);
            if (signed.isEmpty()) {
                // 가맹점 키가 없거나 쓸 수 없다 — 서명 없이 보내지 않고 나중에 다시 시도한다
                log.info("결제 웹훅 보류: 가맹점 키 없음 sessionId={}", session.getSessionId());
                return false;
            }
            var headers = signed.get();
            Boolean ok = rest.post()
                    .uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Merchant-Code", headers.merchantCode())
                    .header("X-Timestamp", headers.timestamp())
                    .header("X-Event-Id", headers.eventId())
                    .header("X-Signature", headers.signature())
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().is2xxSuccessful());
            if (!Boolean.TRUE.equals(ok)) {
                log.info(
                        "결제 웹훅 응답이 2xx가 아님 sessionId={} attempt={}",
                        session.getSessionId(),
                        session.getNotifyAttempts() + 1);
            }
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException e) {
            log.info(
                    "결제 웹훅 전달 실패 sessionId={} attempt={} reason={}",
                    session.getSessionId(),
                    session.getNotifyAttempts() + 1,
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private Optional<MerchantPaymentResponse> payment(PaymentSession session) {
        return query.findPayment(session.getMerchantId(), session.getPaymentId());
    }
}
