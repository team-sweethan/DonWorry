package com.donworry.identity.service;

import com.donworry.identity.api.ClientIpResolver;
import com.donworry.identity.api.OperatorAccessDenied;
import com.donworry.identity.api.OperatorAccessDeniedType;
import com.donworry.identity.entity.OperatorAccessDenial;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.OperatorAccessDenialRepository;
import com.donworry.identity.repository.PlatformUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorAccessDenialService {
    private final PlatformUserRepository users;
    private final OperatorAccessDenialRepository denials;
    private final ApplicationEventPublisher events;
    private final ClientIpResolver clientIps;
    private final Clock clock;

    public OperatorAccessDenialService(
            PlatformUserRepository users,
            OperatorAccessDenialRepository denials,
            ApplicationEventPublisher events,
            ClientIpResolver clientIps,
            @Qualifier("identityClock") Clock clock) {
        this.users = users;
        this.denials = denials;
        this.events = events;
        this.clientIps = clientIps;
        this.clock = clock;
    }

    // 필터 밖에서 독립 커밋한다. 저장·커밋 실패는 핸들러가 받아 원래 403을 유지한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, OperatorAccessDeniedType deniedType, HttpServletRequest request) {
        if (userId == null) return;
        var user = users.findById(userId)
                .filter(account -> account.getRole() == UserRole.OPERATOR)
                .orElse(null);
        if (user == null) return;
        var occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var path = request.getServletPath();
        int length = path.codePointCount(0, path.length());
        if (length > OperatorAccessDenied.MAX_PATH_LENGTH) {
            path = path.substring(0, path.offsetByCodePoints(0, OperatorAccessDenied.MAX_PATH_LENGTH));
        }
        // 방화벽 허용 메서드가 확장돼도 저장 길이로 감사 수집이 누락되지 않게 한다.
        var httpMethod = request.getMethod();
        if (httpMethod.length() > OperatorAccessDenied.MAX_HTTP_METHOD_LENGTH) {
            httpMethod = httpMethod.substring(0, OperatorAccessDenied.MAX_HTTP_METHOD_LENGTH);
        }
        var denial = denials.saveAndFlush(OperatorAccessDenial.record(
                userId,
                user.getMerchantId(),
                httpMethod,
                path,
                deniedType,
                clientIps.clientIp(request),
                LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC)));
        events.publishEvent(OperatorAccessDenied.builder()
                .eventId(denial.getDenialId())
                .userId(userId)
                .loginId(user.getLoginId())
                .merchantId(denial.getMerchantId())
                .httpMethod(denial.getHttpMethod())
                .path(denial.getPath())
                .deniedType(denial.getDeniedType())
                .clientIp(denial.getClientIp())
                .occurredAt(occurredAt)
                // 브라우저가 보장하는 동일 출처 요청만 계정 정지 자동화의 근거로 쓴다.
                .suspensionEligible("same-origin".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site")))
                .build());
    }
}
