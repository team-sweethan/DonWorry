package com.donworry.identity.service;

public interface ApplicationMailSender {
    void sendVerification(String email, String link);

    void sendApproval(String email, String loginId, String link);

    void sendRejection(String email, String reason);
}
