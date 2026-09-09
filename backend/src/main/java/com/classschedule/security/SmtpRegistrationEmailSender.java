package com.classschedule.security;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class SmtpRegistrationEmailSender implements RegistrationEmailSender {
    private final JavaMailSender mailSender;
    private final String from;

    public SmtpRegistrationEmailSender(
            JavaMailSender mailSender, @Value("${app.auth.email.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendRegistrationCode(String email, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("智程排课注册验证码");
            helper.setText(
                    "您好，您的智程排课注册验证码是：" + code + "\n\n验证码 10 分钟内有效，请勿将验证码告知他人。", false);
            mailSender.send(message);
        } catch (MessagingException | MailException exception) {
            throw new RegistrationService.RegistrationRejected(
                    503, "MAIL_DELIVERY_FAILED", "验证码邮件发送失败，请稍后重试");
        }
    }
}
