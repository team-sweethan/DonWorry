package com.donworry.identity.service;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginHistoryRequestMetadata {
    private final boolean proxy;
    private final Set<String> trusted;

    public LoginHistoryRequestMetadata(
            @Value("${security.login-history.ip-mode:DIRECT}") String mode,
            @Value("${security.login-history.trusted-proxies:}") String proxies,
            @Value("${server.forward-headers-strategy:NONE}") String forwarding,
            @Value("${server.tomcat.remoteip.remote-ip-header:}") String remoteIpHeader) {
        if (!Set.of("DIRECT", "PROXY").contains(mode))
            throw new IllegalArgumentException("Invalid login history IP mode");
        if (!"NONE".equalsIgnoreCase(forwarding) || !remoteIpHeader.isBlank())
            throw new IllegalArgumentException(
                    "Login history requires the original socket peer (forward headers strategy NONE)");
        proxy = mode.equals("PROXY");
        trusted = Arrays.stream(proxies.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(value -> {
                    String ip = literal(value);
                    if (ip == null) throw new IllegalArgumentException("Invalid trusted proxy IP");
                    return ip;
                })
                .collect(Collectors.toUnmodifiableSet());
        if (proxy && trusted.isEmpty())
            throw new IllegalArgumentException("PROXY mode requires trusted proxy addresses");
    }

    public String clientIp(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (!proxy) return peer;
        if (peer == null || !trusted.contains(peer)) return null;
        var values = request.getHeaders("X-DonWorry-Client-IP");
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        return values.hasMoreElements() ? null : literal(value);
    }

    public String userAgent(HttpServletRequest request) {
        String raw = request.getHeader("User-Agent");
        if (raw == null) return null;
        var safe = raw.codePoints()
                .filter(c -> !Character.isISOControl(c)
                        && Character.getType(c) != Character.FORMAT
                        && Character.getType(c) != Character.LINE_SEPARATOR
                        && Character.getType(c) != Character.PARAGRAPH_SEPARATOR)
                .limit(512)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return safe.isBlank() ? null : safe;
    }

    private static String literal(String value) {
        if (value == null || value.isBlank() || value.length() > 45) return null;
        try {
            if (value.contains(":")) {
                if (!value.matches("[0-9a-fA-F:.]+")) return null;
                return InetAddress.getByName(value).getHostAddress();
            }
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) return null;
            for (String part : parts)
                if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return null;
            return value;
        } catch (java.net.UnknownHostException | NumberFormatException exception) {
            return null;
        }
    }
}
