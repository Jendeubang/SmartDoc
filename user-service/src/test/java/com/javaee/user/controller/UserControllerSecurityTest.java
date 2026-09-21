package com.javaee.user.controller;

import com.javaee.user.dto.UserProfileUpdateDTO;
import com.javaee.user.service.UserService;
import com.javaee.user.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ensures authorization uses JWT-established identity, never a client header. */
class UserControllerSecurityTest {
    private final UserService users = mock(UserService.class);
    private final UserController controller = new UserController(users);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void profileUpdateUsesAuthenticatedPrincipal() {
        authenticateAs(7L, "USER");
        UserProfileUpdateDTO dto = new UserProfileUpdateDTO();
        when(users.updateProfile(eq(7L), eq(dto))).thenReturn(new UserVO());

        controller.updateProfile(dto);

        verify(users).updateProfile(7L, dto);
    }

    @Test
    void forceLogoutCannotBeEscalatedByForgedIdentityHeader() {
        authenticateAs(7L, "USER");

        controller.forceLogout(99L);

        verify(users).forceLogout(7L, 99L);
    }

    private void authenticateAs(Long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
