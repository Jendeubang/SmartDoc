package com.javaee.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.common.exception.BusinessException;
import com.javaee.common.utils.JwtUtils;
import com.javaee.common.utils.ValidateUtils;
import com.javaee.user.dto.LoginDTO;
import com.javaee.user.dto.RegisterDTO;
import com.javaee.user.dto.UserProfileUpdateDTO;
import com.javaee.user.entity.User;
import com.javaee.user.mapper.UserMapper;
import com.javaee.user.service.AuthSessionService;
import com.javaee.user.service.PasswordResetMailService;
import com.javaee.user.service.UserService;
import com.javaee.user.vo.LoginVO;
import com.javaee.user.vo.RefreshTokenVO;
import com.javaee.user.vo.UserDirectoryVO;
import com.javaee.user.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthSessionService sessions;
    private final StringRedisTemplate redis;
    private final PasswordResetMailService passwordResetMailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${security.password-reset.expose-token:false}")
    private boolean exposeResetToken;

    public UserServiceImpl(UserMapper userMapper, BCryptPasswordEncoder passwordEncoder,
                           AuthSessionService sessions, StringRedisTemplate redis,
                           PasswordResetMailService passwordResetMailService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.redis = redis;
        this.passwordResetMailService = passwordResetMailService;
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        ValidateUtils.notEmpty(dto.getUsername(), "用户名不能为空");
        ValidateUtils.notEmpty(dto.getPassword(), "密码不能为空");
        String account = dto.getUsername().trim();
        String identity = account.toLowerCase();
        String failureKey = "auth:login-fail:" + identity;
        if (redis.hasKey("auth:login-lock:" + identity)) {
            throw new BusinessException("登录失败次数过多，请 15 分钟后重试");
        }
        User user = userMapper.selectByUsername(account);
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            recordLoginFailure(identity, failureKey);
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() == 0) throw new BusinessException(ErrorCodeEnum.PERMISSION_ERROR);
        redis.delete(failureKey);
        LoginVO result = sessions.createSession(user);
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        result.setUser(vo);
        return result;
    }

    @Override
    public UserVO register(RegisterDTO dto) {
        ValidateUtils.notEmpty(dto.getUsername(), "用户名不能为空");
        assertStrongPassword(dto.getPassword());
        ValidateUtils.email(dto.getEmail(), "邮箱格式不正确");
        ValidateUtils.phone(dto.getPhone(), "手机号格式不正确");
        if (userMapper.selectByUsername(dto.getUsername()) != null) throw new BusinessException(ErrorCodeEnum.USER_EXISTED);
        if (userMapper.selectByEmail(dto.getEmail()) != null) throw new BusinessException("邮箱已被注册");
        if (userMapper.selectByPhone(dto.getPhone()) != null) throw new BusinessException("手机号已被注册");
        User user = new User();
        user.setUsername(dto.getUsername().trim());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setRole("USER");
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        save(user);
        UserVO vo = new UserVO(); BeanUtils.copyProperties(user, vo); return vo;
    }

    @Override
    public User getUserByUsername(String username) {
        ValidateUtils.notEmpty(username, "用户名不能为空");
        return userMapper.selectByUsername(username);
    }

    @Override
    public UserVO getUserById(Long id) {
        ValidateUtils.notNull(id, "用户 ID 不能为空");
        User user = getById(id);
        if (user == null) throw new BusinessException(ErrorCodeEnum.USER_NOT_FOUND);
        UserVO vo = new UserVO(); BeanUtils.copyProperties(user, vo); return vo;
    }

    @Override
    public UserVO updateProfile(Long userId, UserProfileUpdateDTO dto) {
        User user = getById(userId);
        if (user == null) throw new BusinessException(ErrorCodeEnum.USER_NOT_FOUND);
        String signature = dto == null || dto.getSignature() == null ? null : dto.getSignature().trim();
        String avatar = dto == null || dto.getAvatarFileId() == null ? null : dto.getAvatarFileId().trim();
        if (signature != null && signature.length() > 160) throw new BusinessException("个性签名不能超过 160 个字符");
        if (avatar != null && avatar.length() > 128) throw new BusinessException("头像文件标识不合法");
        user.setSignature(signature == null || signature.isBlank() ? null : signature);
        user.setAvatarFileId(avatar == null || avatar.isBlank() ? null : avatar);
        user.setUpdateTime(LocalDateTime.now());
        updateById(user);
        UserVO vo = new UserVO(); BeanUtils.copyProperties(user, vo); return vo;
    }

    @Override
    public List<UserDirectoryVO> searchDirectory(String keyword) {
        return userMapper.searchDirectory(keyword == null ? "" : keyword);
    }

    @Override
    public RefreshTokenVO refreshToken(String refreshToken) {
        ValidateUtils.notEmpty(refreshToken, "刷新令牌不能为空");
        try {
            if (!JwtUtils.validateRefreshToken(refreshToken)) throw new BusinessException(ErrorCodeEnum.TOKEN_ERROR);
            User user = getById(JwtUtils.getUserId(refreshToken));
            if (user == null || user.getStatus() == 0) throw new BusinessException(ErrorCodeEnum.USER_NOT_FOUND);
            return sessions.rotate(user, refreshToken);
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCodeEnum.TOKEN_ERROR); }
    }

    @Override
    public void logout(String accessToken, String refreshToken) { sessions.logout(accessToken, refreshToken); }

    @Override
    public void forceLogout(Long operatorId, Long targetUserId) {
        User operator = getById(operatorId);
        if (operator == null || !"ADMIN".equalsIgnoreCase(operator.getRole())) throw new BusinessException("仅平台管理员可执行此操作");
        sessions.revokeAll(targetUserId);
    }

    @Override
    public String requestPasswordReset(String account) {
        ValidateUtils.notEmpty(account, "账号或邮箱不能为空");
        User user = userMapper.selectByAccount(account.trim());
        if (user == null) return null;
        byte[] random = new byte[32]; secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        redis.opsForValue().set("auth:password-reset:" + JwtUtils.sha256(token), String.valueOf(user.getId()), Duration.ofMinutes(15));
        passwordResetMailService.send(user.getEmail(), token);
        log.info("Password reset requested for userId={}", user.getId());
        return exposeResetToken ? token : null;
    }

    @Override
    public void resetPassword(String token, String newPassword) {
        ValidateUtils.notEmpty(token, "重置令牌不能为空");
        assertStrongPassword(newPassword);
        String userId = redis.opsForValue().getAndDelete("auth:password-reset:" + JwtUtils.sha256(token));
        if (userId == null) throw new BusinessException("重置链接无效或已过期");
        User user = getById(Long.valueOf(userId));
        if (user == null) throw new BusinessException(ErrorCodeEnum.USER_NOT_FOUND);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setUpdateTime(LocalDateTime.now());
        updateById(user);
        sessions.revokeAll(user.getId());
    }

    private void recordLoginFailure(String identity, String failureKey) {
        Long failures = redis.opsForValue().increment(failureKey);
        redis.expire(failureKey, Duration.ofMinutes(15));
        if (failures != null && failures >= 5) {
            redis.opsForValue().set("auth:login-lock:" + identity, "locked", Duration.ofMinutes(15));
            redis.delete(failureKey);
        }
    }

    private void assertStrongPassword(String password) {
        ValidateUtils.notEmpty(password, "密码不能为空");
        if (password.length() < 10 || password.length() > 72 ||
                !password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*") ||
                !password.matches(".*\\d.*") || !password.matches(".*[^A-Za-z0-9].*")) {
            throw new BusinessException("密码需为 10-72 位，并包含大写字母、小写字母、数字和特殊字符");
        }
    }
}
