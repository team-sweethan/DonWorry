package com.donworry.identity.dto;

public final class MerchantApplicationResponses {
    private MerchantApplicationResponses() {}

    public record Config(boolean enabled) {}

    public record Submission(String mailStatus) {}

    public record Verification(String status) {}
}
