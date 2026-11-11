package com.donworry.identity.service;

// 브라우저 정보 문자열 → "브라우저 · 운영체제" 이름 (#643, INS-07). 버전은 버려서 브라우저 업데이트로 다른 기기가 되지 않게 한다
// Edge · Opera 문자열에도 "Chrome/"이 들어 있어 먼저 판별한다. iPad 문자열에는 "Mac OS X"가 있어 iOS를 먼저 본다
final class UserAgentDevice {
    static final String UNKNOWN = "알 수 없음";

    private UserAgentDevice() {}

    static String label(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return UNKNOWN;
        return browser(userAgent) + " · " + os(userAgent);
    }

    private static String browser(String ua) {
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) return "Edge";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        if (ua.contains("SamsungBrowser/")) return "Samsung Internet";
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) return "Firefox";
        if (ua.contains("Chrome/") || ua.contains("CriOS/")) return "Chrome";
        if (ua.contains("Safari/")) return "Safari";
        return "기타 브라우저";
    }

    private static String os(String ua) {
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("CrOS")) return "ChromeOS";
        if (ua.contains("Mac OS X") || ua.contains("Macintosh")) return "macOS";
        if (ua.contains("Linux")) return "Linux";
        return "기타 운영체제";
    }
}
