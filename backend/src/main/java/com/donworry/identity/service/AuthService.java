package com.donworry.identity.service;

import com.donworry.identity.dto.LoginRequest;
import com.donworry.identity.dto.MeResponse;
import com.donworry.identity.repository.UserPermissionRepository;
import com.donworry.identity.security.PlatformUserDetails;
import com.donworry.identity.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final UserPermissionRepository permissionRepository;

    public MeResponse login(LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        String loginId = input.loginId().trim().toLowerCase(Locale.ROOT);
        // 제어 문자 · Unicode 줄 구분자 · 방향 제어 문자로 로그에 가짜 줄을 넣거나 내용을 뒤집지 못하게 한다.
        String logLoginId = loginId.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "_");
        try {
            var result = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(loginId, input.password()));
            var authenticated = ((PlatformUserDetails) result.getPrincipal()).sessionUser();
            // 권한은 비밀번호 검증 뒤에 읽는다. 검증 전에 읽으면 있는 아이디만 DB를 한 번 더 조회해
            // 응답 시간으로 계정 존재 여부가 드러난다.
            // 세션에 직렬화되므로 변경 불가 · 직렬화 가능한 목록으로 복사한다.
            var user = authenticated.toBuilder()
                    .permissions(List.copyOf(permissionRepository.findPermissionCodesByUserId(authenticated.userId())))
                    .build();
            // 인증에 쓰인 UserDetails와 비밀번호 해시는 세션에 보관하지 않는다.
            var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, result.getAuthorities());
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            log.info("로그인 성공 loginId={}", logLoginId);
            return MeResponse.from(user);
        } catch (BadCredentialsException | DisabledException exception) {
            log.info("로그인 실패 loginId={}", logLoginId);
            throw exception;
        } catch (InternalAuthenticationServiceException exception) {
            log.error("로그인 처리 오류 loginId={}", logLoginId, exception);
            throw exception;
        }
    }

    public MeResponse me(SessionUser user) {
        return MeResponse.from(user);
    }
}
