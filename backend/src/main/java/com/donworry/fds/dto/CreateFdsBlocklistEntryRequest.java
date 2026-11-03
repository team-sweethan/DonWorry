package com.donworry.fds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateFdsBlocklistEntryRequest(
        @NotNull IdentifierType identifierType,
        @NotBlank @Size(max = 255) String identifierValue,
        @Size(max = 50) String displayLabel,
        @NotBlank @Size(max = 500) String reason) {

    public CreateFdsBlocklistEntryRequest(IdentifierType identifierType, String identifierValue, String reason) {
        this(identifierType, identifierValue, null, reason);
    }

    public enum IdentifierType {
        CARD_TOKEN,
        DEVICE_ID,
        BUYER_REF,
        IP_ADDRESS,
        BUYER_EMAIL
    }
}
