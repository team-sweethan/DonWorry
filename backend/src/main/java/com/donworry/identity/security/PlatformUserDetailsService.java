package com.donworry.identity.security;

import com.donworry.identity.repository.PlatformUserRepository;
import com.donworry.identity.repository.UserPermissionRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlatformUserDetailsService implements UserDetailsService {
    private final PlatformUserRepository repository;
    private final UserPermissionRepository permissionRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        return repository
                .findByLoginId(username.trim().toLowerCase(Locale.ROOT))
                .map(user -> new PlatformUserDetails(
                        user, permissionRepository.findPermissionCodesByUserId(user.getUserId())))
                .orElseThrow(() -> new UsernameNotFoundException("계정을 찾을 수 없습니다."));
    }
}
