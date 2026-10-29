package com.donworry.identity.service;

import com.donworry.identity.dto.AdminAccountRequests.CreateUserRequest;
import com.donworry.identity.entity.*;
import com.donworry.identity.repository.*;
import com.donworry.identity.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class AccountProvisioningService {
    private final PlatformUserRepository users;
    private final MerchantRepository merchants;
    private final AdminChangeHistoryService histories;

    @Transactional(propagation = Propagation.MANDATORY)
    public PlatformUser create(SessionUser actor, CreateUserRequest input, String passwordHash) {
        if (!input.loginId().matches("[a-z0-9_]{3,30}")
                || input.displayName().isBlank()
                || input.displayName().length() > 100) invalid();
        UserRole role = role(input.role());
        if (role == UserRole.ADMIN) invalid();
        if (users.findByLoginId(input.loginId()).isPresent())
            throw new AccountManagementException(AuthErrorCode.ADMIN_LOGIN_ID_CONFLICT);
        if (role == UserRole.OPERATOR) {
            if (input.merchantId() == null
                    || merchants
                            .findById(input.merchantId())
                            .filter(m -> m.getStatus() == MerchantStatus.ACTIVE)
                            .isEmpty()) throw new AccountManagementException(AuthErrorCode.ADMIN_MERCHANT_REQUIRED);
        } else if (input.merchantId() != null) invalid();
        var user = users.saveAndFlush(PlatformUser.create(
                input.loginId(), input.displayName().trim(), role, input.merchantId(), passwordHash));
        histories.recordUser(actor.userId(), user.getUserId(), "USER_CREATED", null, snapshot(user));
        return user;
    }

    private UserRole role(String value) {
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            invalid();
            return null;
        }
    }

    private Map<String, Object> snapshot(PlatformUser user) {
        var data = new LinkedHashMap<String, Object>();
        data.put("loginId", user.getLoginId());
        data.put("displayName", user.getDisplayName());
        data.put("role", user.getRole().name());
        data.put("status", user.getStatus().name());
        data.put("merchantId", user.getMerchantId());
        data.put("passwordChangeRequired", user.isPasswordChangeRequired());
        return data;
    }

    private void invalid() {
        throw new AccountManagementException(AuthErrorCode.ADMIN_INVALID_INPUT);
    }
}
