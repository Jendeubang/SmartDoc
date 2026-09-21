package com.javaee.user.config;

import com.javaee.user.entity.User;
import com.javaee.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Creates the first administrator only for an empty database and only when explicitly configured. */
@Component
public class BootstrapAdminInitializer {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);
    private final UserMapper users;
    private final BCryptPasswordEncoder encoder;

    @Value("${smartdoc.bootstrap.admin.username:}") private String username;
    @Value("${smartdoc.bootstrap.admin.password:}") private String password;
    @Value("${smartdoc.bootstrap.admin.email:}") private String email;
    @Value("${smartdoc.bootstrap.admin.phone:}") private String phone;

    public BootstrapAdminInitializer(UserMapper users, BCryptPasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (blank(username) || blank(password) || blank(email) || blank(phone)) {
            log.info("Initial administrator bootstrap is disabled because SMARTDOC_BOOTSTRAP_ADMIN_* is not fully configured.");
            return;
        }
        Long count = users.selectCount(null);
        if (count != null && count > 0) return;
        assertStrongPassword(password);
        User admin = new User();
        admin.setUsername(username.trim());
        admin.setPassword(encoder.encode(password));
        admin.setEmail(email.trim());
        admin.setPhone(phone.trim());
        admin.setRole("ADMIN");
        admin.setStatus(1);
        admin.setCreateTime(LocalDateTime.now());
        admin.setUpdateTime(LocalDateTime.now());
        users.insert(admin);
        log.warn("Initial SmartDoc administrator created. Remove SMARTDOC_BOOTSTRAP_ADMIN_* variables and restart.");
    }

    private void assertStrongPassword(String value) {
        if (value.length() < 14 || value.length() > 72 || !value.matches(".*[A-Z].*")
                || !value.matches(".*[a-z].*") || !value.matches(".*\\d.*")
                || !value.matches(".*[^A-Za-z0-9].*")) {
            throw new IllegalStateException("Bootstrap administrator password must be 14-72 characters and include upper/lowercase, number and symbol");
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
