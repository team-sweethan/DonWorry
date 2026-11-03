package com.donworry.identity.service;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ApplicationSettings {
    private final boolean enabled;
    private final String baseUrl;
    private final String from;

    public ApplicationSettings(
            @Value("${donworry.applications.enabled:true}") boolean enabled,
            @Value("${donworry.applications.public-base-url:http://localhost:5173}") String baseUrl,
            @Value("${donworry.applications.mail-from:donworry@localhost}") String from,
            @Value("${spring.mail.host:localhost}") String host,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${spring.mail.properties[mail.smtp.auth]:false}") boolean smtpAuth,
            @Value("${spring.mail.properties[mail.smtp.starttls.required]:false}") boolean tlsRequired,
            Environment environment) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.from = from;
        if (!enabled) return;
        URI uri = URI.create(this.baseUrl);
        if (uri.getHost() == null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getUserInfo() != null
                || !SetHolder.SCHEMES.contains(uri.getScheme())
                || from.isBlank()
                || from.contains("\r")
                || from.contains("\n"))
            throw new IllegalArgumentException("Invalid application public URL or sender configuration");
        if (environment.matchesProfiles("prod")
                && (!uri.getScheme().equals("https")
                        || SetHolder.LOCAL.contains(uri.getHost())
                        || host.equals("localhost")
                        || username.isBlank()
                        || password.isBlank()
                        || !smtpAuth
                        || !tlsRequired
                        || from.endsWith("@localhost")))
            throw new IllegalArgumentException(
                    "Production application mail requires HTTPS public URL and SMTP credentials");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void requireEnabled() {
        if (!enabled)
            throw new com.donworry.identity.security.ApplicationException(
                    com.donworry.identity.security.ApplicationErrorCode.UNAVAILABLE);
    }

    public String verificationLink(String token) {
        return baseUrl + "/apply/verify#token=" + token;
    }

    public String initialPasswordLink(String token) {
        return baseUrl + "/apply/password#token=" + token;
    }

    public String applicationLink() {
        return baseUrl + "/apply";
    }

    public String from() {
        return from;
    }

    private static class SetHolder {
        static final java.util.Set<String> SCHEMES = java.util.Set.of("http", "https");
        static final java.util.Set<String> LOCAL = java.util.Set.of("localhost", "127.0.0.1", "[::1]");
    }
}
