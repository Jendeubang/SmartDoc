package com.javaee.user.controller;

import com.javaee.common.exception.BusinessException;
import com.javaee.common.model.Result;
import com.javaee.common.utils.JwtUtils;
import com.javaee.user.dto.*;
import com.javaee.user.entity.User;
import com.javaee.user.service.UserService;
import com.javaee.user.vo.*;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService users;
    public UserController(UserService users) { this.users = users; }

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO dto) { return Result.success(users.login(dto)); }

    @PostMapping("/register")
    public Result<UserVO> register(@RequestBody RegisterDTO dto) { return Result.success(users.register(dto)); }

    @GetMapping("/{id:\\d+}")
    public Result<UserVO> getUserById(@PathVariable Long id) { return Result.success(users.getUserById(id)); }

    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@RequestBody UserProfileUpdateDTO dto) {
        return Result.success(users.updateProfile(currentUserId(), dto));
    }

    @GetMapping("/lookup")
    public Result<UserVO> getUserByUsername(@RequestParam String username) {
        User user = users.getUserByUsername(username);
        if (user == null) throw new BusinessException("用户不存在");
        UserVO result = new UserVO(); BeanUtils.copyProperties(user, result); return Result.success(result);
    }

    @GetMapping("/directory")
    public Result<List<UserDirectoryVO>> searchDirectory(@RequestParam(required = false, defaultValue = "") String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        return Result.success(users.searchDirectory(normalized.substring(0, Math.min(50, normalized.length()))));
    }

    @PostMapping("/refresh")
    public Result<RefreshTokenVO> refresh(@RequestBody RefreshTokenDTO dto) {
        return Result.success(users.refreshToken(dto.getRefreshToken()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestBody(required = false) RefreshTokenDTO dto) {
        String access = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        users.logout(access, dto == null ? null : dto.getRefreshToken());
        return Result.success();
    }

    @PostMapping("/{id}/force-logout")
    public Result<Void> forceLogout(@PathVariable Long id) {
        users.forceLogout(currentUserId(), id); return Result.success();
    }

    @PostMapping("/password/forgot")
    public Result<Map<String, Object>> forgotPassword(@RequestBody ForgotPasswordDTO dto) {
        String token = users.requestPasswordReset(dto.getAccount());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "如果账号存在，密码重置说明已发送到注册邮箱");
        if (token != null) response.put("debugResetToken", token);
        return Result.success(response);
    }

    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@RequestBody ResetPasswordDTO dto) {
        users.resetPassword(dto.getToken(), dto.getNewPassword()); return Result.success();
    }

    /**
     * The gateway's X-User-Id header is only a downstream transport detail.
     * Authorization decisions must use the identity established by the JWT
     * authentication filter, otherwise a direct service-port request could
     * forge the operator by sending a different header.
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Number principal)) {
            throw new BusinessException("用户未认证，请先登录");
        }
        return principal.longValue();
    }
}
