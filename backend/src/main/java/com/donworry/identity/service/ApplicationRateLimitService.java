package com.donworry.identity.service;

import com.donworry.identity.repository.ApplicationRateLimitRepository;
import com.donworry.identity.security.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class ApplicationRateLimitService {
    private static final String INITIAL_EMAIL = "INITIAL_EMAIL";
    private static final Duration COOLDOWN = Duration.ofMinutes(1);

    private final ApplicationRateLimitRepository limits;
    private final Clock identityClock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void submission(String ip) {
        consume("SUBMIT_IP", ip, Duration.ofHours(1), false);
    }

    // 최초 메일은 재발송 쿨다운만 건다. 신청 저장과 같은 트랜잭션·연결에서 기록하고 하루 5회에는 세지 않는다.
    @Transactional
    public void initialMail(String email) {
        long now = identityClock.millis();
        String hash = ApplicationTokenCodec.hash(email);
        recent(INITIAL_EMAIL, hash, COOLDOWN, now);
        limits.update(INITIAL_EMAIL, hash, String.valueOf(now), expiry(COOLDOWN));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resend(String email) {
        long now = identityClock.millis();
        String hash = ApplicationTokenCodec.hash(email);
        if (!recent(INITIAL_EMAIL, hash, COOLDOWN, now).isEmpty())
            throw new ApplicationException(ApplicationErrorCode.RATE_LIMITED);
        consume("RESEND_EMAIL", email, Duration.ofHours(24), true);
    }

    private void consume(String scope, String subject, Duration window, boolean cooldown) {
        long now = identityClock.millis();
        String hash = ApplicationTokenCodec.hash(subject);
        var times = recent(scope, hash, window, now);
        if (times.size() >= 5 || (cooldown && times.stream().anyMatch(time -> time > now - COOLDOWN.toMillis())))
            throw new ApplicationException(ApplicationErrorCode.RATE_LIMITED);
        times.add(now);
        limits.update(
                scope, hash, times.stream().map(String::valueOf).collect(Collectors.joining(",")), expiry(window));
    }

    // 행을 잠그고 시간창 안의 허용 시각만 돌려준다.
    private List<Long> recent(String scope, String hash, Duration window, long now) {
        return Arrays.stream(limits.lock(scope, hash, expiry(window)).split(","))
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .filter(time -> time > now - window.toMillis())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private LocalDateTime expiry(Duration window) {
        return LocalDateTime.ofInstant(identityClock.instant().plus(window), ZoneOffset.UTC);
    }

    @Transactional
    public void deleteExpired() {
        limits.deleteExpired(LocalDateTime.ofInstant(identityClock.instant(), ZoneOffset.UTC));
    }
}
