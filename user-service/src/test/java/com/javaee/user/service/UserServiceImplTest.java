package com.javaee.user.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.user.dto.LoginDTO;
import com.javaee.user.dto.RegisterDTO;
import com.javaee.user.entity.User;
import com.javaee.user.mapper.UserMapper;
import com.javaee.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {
    private UserMapper userMapper;
    private AuthSessionService sessions;
    private StringRedisTemplate redis;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        sessions = mock(AuthSessionService.class);
        redis = mock(StringRedisTemplate.class);
        service = new UserServiceImpl(
                userMapper,
                new BCryptPasswordEncoder(),
                sessions,
                redis,
                mock(PasswordResetMailService.class)
        );
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
    }

    @Test
    void registerRejectsWeakPasswordBeforeWritingUser() {
        RegisterDTO dto = registration("member", "weak", "member@example.com", "13800138000");

        assertThatThrownBy(() -> service.register(dto))
                .isInstanceOf(BusinessException.class);
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerStoresEncodedPasswordAndDefaultRole() {
        RegisterDTO dto = registration(" member ", "Strong#Pass1", "member@example.com", "13800138000");
        when(userMapper.selectByUsername(dto.getUsername())).thenReturn(null);
        when(userMapper.selectByEmail(dto.getEmail())).thenReturn(null);
        when(userMapper.selectByPhone(dto.getPhone())).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(9L);
            return 1;
        });

        var result = service.register(dto);

        assertThat(result.getUsername()).isEqualTo("member");
        verify(userMapper).insert(org.mockito.ArgumentMatchers.argThat(user ->
                "USER".equals(user.getRole())
                        && Integer.valueOf(1).equals(user.getStatus())
                        && new BCryptPasswordEncoder().matches("Strong#Pass1", user.getPassword())
        ));
    }

    @Test
    void loginStopsImmediatelyWhenAccountIsLocked() {
        LoginDTO dto = new LoginDTO();
        dto.setUsername("member");
        dto.setPassword("Strong#Pass1");
        when(redis.hasKey("auth:login-lock:member")).thenReturn(true);

        assertThatThrownBy(() -> service.login(dto))
                .isInstanceOf(BusinessException.class);
        verify(userMapper, never()).selectByUsername(any());
    }

    @Test
    void forceLogoutRequiresAdministratorRole() {
        User ordinaryUser = new User();
        ordinaryUser.setId(3L);
        ordinaryUser.setRole("USER");
        when(userMapper.selectById(3L)).thenReturn(ordinaryUser);

        assertThatThrownBy(() -> service.forceLogout(3L, 8L))
                .isInstanceOf(BusinessException.class);
        verify(sessions, never()).revokeAll(any());
    }

    @Test
    void administratorCanForceMemberLogout() {
        User admin = new User();
        admin.setId(1L);
        admin.setRole("ADMIN");
        when(userMapper.selectById(1L)).thenReturn(admin);

        service.forceLogout(1L, 8L);

        verify(sessions).revokeAll(8L);
    }

    private RegisterDTO registration(String username, String password, String email, String phone) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setEmail(email);
        dto.setPhone(phone);
        return dto;
    }
}
