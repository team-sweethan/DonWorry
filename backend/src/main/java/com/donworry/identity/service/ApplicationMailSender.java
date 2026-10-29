package com.donworry.identity.service;

public interface ApplicationMailSender {
    void sendVerification(String email, String link);
}
