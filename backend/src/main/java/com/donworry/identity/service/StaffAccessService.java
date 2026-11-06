package com.donworry.identity.service;

import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessRecorded;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.entity.StaffAccessEvent;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.StaffAccessEventRepository;
import com.donworry.identity.security.SessionUser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// 보안 직원 · ADMIN 접근 기록 (ADR-0082 4절). 사장님 403 기록(OperatorAccessDenialService)과 같은 저장 · 발행 모양
@Slf4j
@Service
public class StaffAccessService implements StaffAccessRecorder {
    private final StaffAccessEventRepository events;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate separateTransaction;
    private final Clock clock;

    public StaffAccessService(
            StaffAccessEventRepository events,
            ApplicationEventPublisher publisher,
            PlatformTransactionManager transactionManager,
            @Qualifier("identityClock") Clock clock) {
        this.events = events;
        this.publisher = publisher;
        this.separateTransaction = new TransactionTemplate(transactionManager);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    // 같은 트랜잭션이라 업무가 롤백되면 기록도 사라진다 — "기록 있음 = 실제로 나감" (ADR-0082 4절)
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAllowed(StaffAccess access) {
        var staff = currentStaff().orElseThrow(() -> new IllegalStateException("직원 세션이 아니면 허용 접근을 기록할 수 없다"));
        save(StaffAccessEvent.allowed(staff.userId(), staff.role(), staff.loginReference(), access, now()));
    }

    @Override
    public void recordDenied(StaffAccess access, StaffAccessDenyReason reason) {
        var staff = currentStaff().orElse(null);
        if (staff == null) return;
        try {
            separateTransaction.executeWithoutResult(status -> save(StaffAccessEvent.denied(
                    staff.userId(), staff.role(), staff.loginReference(), access, reason, now())));
        } catch (RuntimeException e) {
            // DB 오류 원문에 값이 섞일 수 있어 예외 종류와 직원 ID만 남긴다 (ADR-0080과 같음)
            log.warn(
                    "직원 접근 거부 기록 실패 staffId={} accessType={} error={}",
                    staff.userId(),
                    access.accessType(),
                    e.getClass().getSimpleName());
        }
    }

    private void save(StaffAccessEvent event) {
        var saved = events.saveAndFlush(event);
        publisher.publishEvent(StaffAccessRecorded.builder()
                .eventId(saved.getEventId())
                .staffId(saved.getStaffId())
                .actorRole(saved.getActorRole().name())
                .accessType(saved.getAccessType())
                .result(saved.getResult())
                .denyReason(saved.getDenyReason())
                .caseId(saved.getCaseId())
                .merchantId(saved.getMerchantId())
                .paymentId(saved.getPaymentId())
                .customerRef(saved.getCustomerRef())
                .fieldType(saved.getFieldType())
                .itemCount(saved.getItemCount())
                .loginReference(saved.getLoginReference())
                .occurredAt(saved.getOccurredAt().toInstant(ZoneOffset.UTC))
                .build());
    }

    // 기록 주체는 요청 값이 아니라 로그인 세션이다. 사장님 · 비로그인은 이 기록 대상이 아니다
    private Optional<SessionUser> currentStaff() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof SessionUser user)) return Optional.empty();
        return user.role() == UserRole.SECURITY_STAFF || user.role() == UserRole.ADMIN
                ? Optional.of(user)
                : Optional.empty();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
