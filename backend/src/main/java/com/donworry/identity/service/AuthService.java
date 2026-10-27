package com.donworry.identity.service;

import com.donworry.identity.dto.LoginRequest;
import com.donworry.identity.dto.MeResponse;
import com.donworry.identity.entity.MerchantStatus;
import com.donworry.identity.entity.UserRole;
import com.donworry.identity.repository.MerchantRepository;
import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.repository.UserPermissionRepository;
import com.donworry.identity.security.AccountManagementException;
import com.donworry.identity.security.AuthErrorCode;
import com.donworry.identity.security.PlatformUserDetails;
import com.donworry.identity.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final UserSessionService userSessionService;
    private final UserPermissionRepository permissionRepository;
    private final AccountLockService accountLockService;
    private final PlatformUserRepository userRepository;
    private final MerchantRepository merchants;
    private final MerchantLockService merchantLocks;
    private final LoginAttemptService loginAttempts;
    private final LoginHistoryService loginHistories;

    public MeResponse login(LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        String loginId = input.loginId().trim().toLowerCase(Locale.ROOT);
        String accountLoginId;
        Long merchantId;
        Long accountUserId;
        try {
            // DB collation이 같은 계정으로 보는 악센트 등의 별칭도 반드시 같은 잠금을 사용한다.
            var account = userRepository.findByLoginId(loginId);
            accountLoginId = account.map(user -> user.getLoginId()).orElse(loginId);
            accountUserId = account.map(user -> user.getUserId()).orElse(null);
            merchantId = account.filter(user -> user.getRole() == UserRole.OPERATOR)
                    .map(user -> user.getMerchantId())
                    .orElse(null);
        } catch (RuntimeException exception) {
            log.error("로그인 계정 조회 오류", exception);
            throw new InternalAuthenticationServiceException("로그인 계정을 조회하지 못했습니다.", exception);
        }
        return merchantLocks.withMerchant(
                merchantId,
                () -> accountLockService.withAccount(
                        accountLoginId,
                        () -> loginLocked(
                                input, loginId, accountLoginId, accountUserId, merchantId, request, response)));
    }

    private MeResponse loginLocked(
            LoginRequest input,
            String loginId,
            String lockedAccountLoginId,
            Long accountUserId,
            Long lockedMerchantId,
            HttpServletRequest request,
            HttpServletResponse response) {
        // 제어 문자 · Unicode 줄 구분자 · 방향 제어 문자로 로그에 가짜 줄을 넣거나 내용을 뒤집지 못하게 한다.
        String logLoginId = loginId.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "_");
        try {
            Authentication result;
            try {
                result = authenticationManager.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated(loginId, input.password()));
            } catch (BadCredentialsException exception) {
                // 잠금과 같은 실제 계정 아이디로 센다.
                loginAttempts.recordFailure(lockedAccountLoginId);
                throw exception;
            }
            var details = (PlatformUserDetails) result.getPrincipal();
            // 첫 조회 뒤 별칭 계정이 생성됐어도 실제 계정의 잠금 없이 횟수를 초기화하지 않는다.
            if (!lockedAccountLoginId.equals(details.getUsername())) throw new BadCredentialsException("로그인할 수 없습니다.");
            if (loginAttempts.isLocked(details.getLoginLockedUntil()))
                throw new BadCredentialsException("로그인할 수 없습니다.");
            var authenticated = details.sessionUser();
            if (authenticated.role() == UserRole.OPERATOR) {
                // 최초 조회 때 없던 계정이 생성됐어도 가맹점 잠금 없이 세션을 등록하지 않는다.
                if (lockedMerchantId == null || !lockedMerchantId.equals(authenticated.merchantId()))
                    throw new DisabledException("로그인할 수 없습니다.");
                boolean active;
                try {
                    active = merchants
                            .findById(lockedMerchantId)
                            .filter(m -> m.getStatus() == MerchantStatus.ACTIVE)
                            .isPresent();
                } catch (RuntimeException exception) {
                    throw new InternalAuthenticationServiceException("로그인 가맹점을 조회하지 못했습니다.", exception);
                }
                if (!active) throw new DisabledException("로그인할 수 없습니다.");
            }
            // 권한은 비밀번호 검증 뒤에 읽는다. 검증 전에 읽으면 있는 아이디만 DB를 한 번 더 조회해
            // 응답 시간으로 계정 존재 여부가 드러난다.
            // 세션에 직렬화되므로 변경 불가 · 직렬화 가능한 목록으로 복사한다.
            List<String> permissions;
            try {
                permissions = List.copyOf(permissionRepository.findPermissionCodesByUserId(authenticated.userId()));
            } catch (RuntimeException exception) {
                // 공통 DB 오류(AUTH_007)가 아니라 로그인 처리 실패(AUTH_005)로 응답한다.
                throw new InternalAuthenticationServiceException("로그인 권한을 조회하지 못했습니다.", exception);
            }
            var user = authenticated.toBuilder()
                    .permissions(permissions)
                    .loginReference(UUID.randomUUID())
                    .build();
            loginAttempts.clearFailures(user.userId());
            // 인증에 쓰인 UserDetails와 비밀번호 해시는 세션에 보관하지 않는다.
            var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, result.getAuthorities());
            userSessionService.establishAuthentication(authentication, request, response);
            loginHistories.success(user, request);
            log.info("로그인 성공 loginId={}", logLoginId);
            return MeResponse.from(user);
        } catch (BadCredentialsException | DisabledException exception) {
            loginHistories.failure(accountUserId, request);
            log.info("로그인 실패 loginId={}", logLoginId);
            throw exception;
        } catch (InternalAuthenticationServiceException exception) {
            log.error("로그인 처리 오류 loginId={}", logLoginId, exception);
            throw exception;
        }
    }

    public MeResponse me(SessionUser user) {
        // 인가 통과 후에도 동시 로그아웃이 공유 SecurityContext의 인증을 비울 수 있다.
        if (user == null) throw new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        return MeResponse.from(user);
    }

    public void logoutAll(SessionUser user, HttpServletRequest request, HttpServletResponse response) {
        // 인가 필터를 지난 뒤 같은 세션의 다른 로그아웃이 공유 SecurityContext의 인증을 비울 수 있다.
        if (user == null) throw new AccountManagementException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        userSessionService.logoutAll(user.userId(), request, response);
        loginHistories.logout(user, true, request);
    }
}
