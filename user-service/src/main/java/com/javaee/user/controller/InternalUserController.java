package com.javaee.user.controller;

import com.javaee.common.model.Result;
import com.javaee.user.service.UserService;
import com.javaee.user.service.AuthSessionService;
import com.javaee.user.vo.UserVO;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/users")
public class InternalUserController {
    private final UserService users;
    private final AuthSessionService sessions;
    public InternalUserController(UserService users, AuthSessionService sessions) { this.users = users; this.sessions = sessions; }

    @GetMapping("/{id}")
    public Result<UserVO> get(@PathVariable Long id) { return Result.success(users.getUserById(id)); }

    @DeleteMapping("/{id}/sessions")
    public Result<Void> revokeSessions(@PathVariable Long id) { sessions.revokeAll(id); return Result.success(); }
}
