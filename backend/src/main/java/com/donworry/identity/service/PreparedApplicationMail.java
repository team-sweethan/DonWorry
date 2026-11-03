package com.donworry.identity.service;

public record PreparedApplicationMail(Long deliveryId, String email, String token) {
    @Override
    public String toString() {
        return "PreparedApplicationMail[redacted]";
    }
}
