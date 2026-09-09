package com.classschedule.security;

public interface RegistrationEmailSender {
    void sendRegistrationCode(String email, String code);
}
