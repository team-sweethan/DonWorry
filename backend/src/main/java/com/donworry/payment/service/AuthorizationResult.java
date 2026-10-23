package com.donworry.payment.service;

record AuthorizationResult(Type type, String authorizationRef, String declineCode) {
    enum Type {
        SUCCEEDED,
        DECLINED
    }

    AuthorizationResult {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (type == Type.SUCCEEDED) {
            if (authorizationRef == null
                    || authorizationRef.isBlank()
                    || authorizationRef.codePointCount(0, authorizationRef.length()) > 100
                    || declineCode != null) {
                throw new IllegalArgumentException("success requires a valid reference and no decline code");
            }
        } else if (authorizationRef != null || declineCode == null || declineCode.isBlank()) {
            throw new IllegalArgumentException("decline requires a code and no reference");
        }
    }
}
