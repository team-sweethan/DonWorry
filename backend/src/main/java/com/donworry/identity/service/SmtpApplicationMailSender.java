package com.donworry.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SmtpApplicationMailSender implements ApplicationMailSender {
    private final JavaMailSender sender;
    private final ApplicationSettings settings;

    @Override
    public void sendVerification(String email, String link) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(settings.from(), "돈워리");
            helper.setTo(email);
            helper.setSubject("[돈워리] 이용 신청 접수 · 이메일을 확인해 주세요");
            helper.setText("이용 신청이 접수되었습니다. 아래 링크를 열고 이메일 확인 버튼을 눌러 주세요.\n\n" + link
                    + "\n\n링크는 발급 후 24시간 동안 한 번만 사용할 수 있습니다. 재발송하면 이전 링크는 무효입니다."
                    + "\n이메일 확인 후 관리자가 검토합니다. 신청·이메일 확인만으로 계정이 발급되지 않으며 결제 연동은 별도 진행합니다."
                    + "\n본인이 신청하지 않았다면 이 메일을 무시해 주세요.");
            sender.send(message);
        } catch (jakarta.mail.MessagingException | java.io.UnsupportedEncodingException exception) {
            throw new org.springframework.mail.MailPreparationException("Application mail preparation failed");
        }
    }
}
