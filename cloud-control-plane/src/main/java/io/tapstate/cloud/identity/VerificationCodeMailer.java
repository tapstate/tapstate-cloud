package io.tapstate.cloud.identity;

public interface VerificationCodeMailer {

    void send(String email, String code);
}
