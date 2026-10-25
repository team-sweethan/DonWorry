package com.donworry.identity.dto;

public record TemporaryPasswordResponse(AdminUserResponse user, String temporaryPassword) {
    @Override
    public String toString() {
        return "TemporaryPasswordResponse[userId=" + user.userId() + ", temporaryPassword=[REDACTED]]";
    }
}
