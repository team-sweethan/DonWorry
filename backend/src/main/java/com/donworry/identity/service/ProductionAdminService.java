package com.donworry.identity.service;

import com.donworry.identity.entity.UserRole;
import com.donworry.identity.entity.UserStatus;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.security.PasswordChangeException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("prod")
@RequiredArgsConstructor
public class ProductionAdminService {
    private final PlatformUserRepository users;
    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;
    private final AdminChangeHistoryService histories;

    @Transactional
    public void applyPassword(String password) {
        // 설정값을 예외 메시지·검증 결과에 넣지 않아 기동 실패 로그에도 노출하지 않는다.
        try {
            policy.validate("admin", password);
        } catch (PasswordChangeException exception) {
            throw new IllegalStateException("DONWORRY_ADMIN_PASSWORD가 없거나 비밀번호 정책에 맞지 않습니다.");
        }
        var admin = users.findByLoginId("admin")
                .filter(user -> user.getRole() == UserRole.ADMIN && user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("운영에는 활성 admin ADMIN 계정이 필요합니다."));
        boolean passwordMatches = encoder.matches(password, admin.getPasswordHash());
        if (passwordMatches && !admin.isPasswordChangeRequired()) return;
        boolean previouslyRequired = admin.isPasswordChangeRequired();
        admin.changePasswordHash(passwordMatches ? admin.getPasswordHash() : encoder.encode(password));
        // HTTP 수행자가 없는 시작 작업이다. 대상 관리자 ID와 별도 유형·출처로 구분한다.
        histories.recordUser(
                admin.getUserId(),
                admin.getUserId(),
                "PRODUCTION_PASSWORD_APPLIED",
                Map.of("passwordChangeRequired", previouslyRequired),
                Map.of("passwordChangeRequired", false, "source", "PRODUCTION_STARTUP"));
    }
}
