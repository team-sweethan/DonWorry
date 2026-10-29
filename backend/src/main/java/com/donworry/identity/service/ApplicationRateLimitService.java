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
        consume("SUBMIT_IP", ip, Duration.ofHours(1), 5, false);
    }

    // 최초 메일은 재발송 쿨다운만 건다. 신청 저장과 같은 트랜잭션·연결에서 기록하고 하루 5회에는 세지 않는다.
    @Transactional
    public void initialMail(String email) {
        long now = identityClock.millis();
        String hash = ApplicationTokenCodec.hash(email);
        recent(INITIAL_EMAIL, hash, COOLDOWN, now);
        limits.update(INITIAL_EMAIL, hash, String.valueOf(now), expiry(COOLDOWN));
    }

    // 재발송 허용 여부를 판정하고, 최초 메일 직후 1분 안이면 false로 발송만 생략하게 한다.
    // 신청 여부가 응답 코드로 드러나지 않게 쿨다운 중에도 같은 202를 반환한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean resend(String email, String ip) {
        // 없는 이메일을 바꿔 가며 요청해 제한 행을 무한히 만들지 못하게 IP 상한을 먼저 본다.
        consume("RESEND_IP", ip, Duration.ofHours(1), 10, false);
        consume("RESEND_EMAIL", email, Duration.ofHours(24), 5, true);
        long now = identityClock.millis();
        return limits.find(INITIAL_EMAIL, ApplicationTokenCodec.hash(email))
                .map(times -> parse(times, COOLDOWN, now).isEmpty())
                .orElse(true);
    }

    // 관리자는 공개 경로와 이메일 버킷을 공유하되 IP 버킷은 소비하지 않는다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void adminResend(String email) {
        String hash = ApplicationTokenCodec.hash(email);
        var times = recent("RESEND_EMAIL", hash, Duration.ofHours(24), identityClock.millis());
        var retry = adminRetryAt(email, times);
        if (retry != null) throw new ApplicationResendLimitException(retry);
        times.add(identityClock.millis());
        limits.update(
                "RESEND_EMAIL",
                hash,
                times.stream().map(String::valueOf).collect(Collectors.joining(",")),
                expiry(Duration.ofHours(24)));
    }

    public LocalDateTime adminRetryAt(String email) {
        var times = limits.find("RESEND_EMAIL", ApplicationTokenCodec.hash(email))
                .map(value -> parse(value, Duration.ofHours(24), identityClock.millis()))
                .orElseGet(ArrayList::new);
        return adminRetryAt(email, times);
    }

    private LocalDateTime adminRetryAt(String email, List<Long> times) {
        long now = identityClock.millis();
        long retry =
                times.stream().mapToLong(t -> t + COOLDOWN.toMillis()).max().orElse(0);
        if (times.size() >= 5)
            retry = Math.max(
                    retry,
                    times.stream().mapToLong(Long::longValue).min().orElseThrow()
                            + Duration.ofHours(24).toMillis());
        long initial = limits.find(INITIAL_EMAIL, ApplicationTokenCodec.hash(email))
                .map(value -> parse(value, COOLDOWN, now).stream()
                        .mapToLong(t -> t + COOLDOWN.toMillis())
                        .max()
                        .orElse(0))
                .orElse(0L);
        retry = Math.max(retry, initial);
        return retry > now ? LocalDateTime.ofInstant(Instant.ofEpochMilli(retry), ZoneOffset.UTC) : null;
    }

    private void consume(String scope, String subject, Duration window, int max, boolean cooldown) {
        long now = identityClock.millis();
        String hash = ApplicationTokenCodec.hash(subject);
        var times = recent(scope, hash, window, now);
        if (times.size() >= max || (cooldown && times.stream().anyMatch(time -> time > now - COOLDOWN.toMillis())))
            throw new ApplicationException(ApplicationErrorCode.RATE_LIMITED);
        times.add(now);
        limits.update(
                scope, hash, times.stream().map(String::valueOf).collect(Collectors.joining(",")), expiry(window));
    }

    // 행을 잠그고 시간창 안의 허용 시각만 돌려준다.
    private List<Long> recent(String scope, String hash, Duration window, long now) {
        return parse(limits.lock(scope, hash, expiry(window)), window, now);
    }

    private static List<Long> parse(String stored, Duration window, long now) {
        return Arrays.stream(stored.split(","))
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .filter(time -> time > now - window.toMillis())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private LocalDateTime expiry(Duration window) {
        return LocalDateTime.ofInstant(identityClock.instant().plus(window), ZoneOffset.UTC);
    }

    // 1,000행씩 나눠 지우되 만료 행이 남지 않을 때까지 반복한다. 각 DELETE는 따로 커밋한다.
    public void deleteExpired() {
        var now = LocalDateTime.ofInstant(identityClock.instant(), ZoneOffset.UTC);
        int deleted;
        do {
            deleted = limits.deleteExpired(now);
        } while (deleted >= ApplicationRateLimitRepository.DELETE_BATCH);
    }
}
