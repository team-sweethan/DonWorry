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
    public void sendApproval(String email, String loginId, String link) {
        send(
                email,
                "[돈워리] 이용 승인 · 첫 비밀번호를 설정해 주세요",
                "이용 신청이 승인되어 가맹점과 사장님 계정이 발급되었습니다.\n\n로그인 아이디: " + loginId
                        + "\n\n아래 링크에서 본인이 사용할 비밀번호를 설정한 뒤 로그인해 주세요.\n" + link
                        + "\n\n링크는 발급 후 72시간 동안 한 번만 사용할 수 있습니다."
                        + "\n관리자가 비밀번호를 초기화하면 이 링크는 사용할 수 없습니다."
                        + "\n결제 연동은 별도 진행합니다. 로그인 후 [시크릿 키 발급]에서 키와 가맹점 코드를 확인해 쇼핑몰 서버에 설정해 주세요."
                        + "\n쇼핑몰 서버에서 서명된 연결 확인 요청을 보내 연동을 완료한 뒤 신규 결제를 요청할 수 있습니다."
                        + "\n시크릿 키를 메일이나 메신저로 보내지 마세요.");
    }

    @Override
    public void sendRejection(String email, String reason) {
        send(
                email,
                "[돈워리] 이용 신청 검토 결과를 안내합니다",
                "이용 신청이 반려되었습니다. 이번 신청으로 계정은 발급되지 않았습니다.\n\n반려 사유\n" + reason + "\n\n사유를 확인한 뒤 이용 신청 화면에서 새로 신청해 주세요.\n"
                        + settings.applicationLink());
    }

    private void send(String email, String subject, String body) {
        try {
            var helper = new MimeMessageHelper(sender.createMimeMessage(), false, "UTF-8");
            helper.setFrom(settings.from(), "돈워리");
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(body);
            sender.send(helper.getMimeMessage());
        } catch (jakarta.mail.MessagingException | java.io.UnsupportedEncodingException e) {
            throw new org.springframework.mail.MailPreparationException("Application mail preparation failed");
        }
    }

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
