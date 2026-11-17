package com.reviewhub.api;

import com.reviewhub.api.dto.AuthDtos;
import com.reviewhub.domain.User;
import com.reviewhub.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 登录 / 会话 / 个人密码 API */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public AuthDtos.LoginResponse login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        User u = auth.login(req.username(), req.password())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误，或账号已被禁用"));
        String token = auth.issueToken(u.getId());
        return new AuthDtos.LoginResponse(token, toDto(u));
    }

    @PostMapping("/logout")
    public Map<String, String> logout(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        auth.logout(token);
        return Map.of("ok", "true");
    }

    @GetMapping("/me")
    public AuthDtos.UserDto me(@RequestAttribute("currentUser") User user) {
        return toDto(user);
    }

    @PostMapping("/change-password")
    public Map<String, String> changePassword(@RequestAttribute("currentUser") User user,
                                              @Valid @RequestBody AuthDtos.ChangePasswordRequest req) {
        if (!user.getPassword().equalsIgnoreCase(AuthService.md5(req.oldPassword()))) {
            throw new IllegalArgumentException("原密码不正确");
        }
        if (req.newPassword().equals(req.oldPassword())) {
            throw new IllegalArgumentException("新密码不能与原密码相同");
        }
        user.setPassword(AuthService.md5(req.newPassword()));
        auth.save(user);
        return Map.of("ok", "true");
    }

    static AuthDtos.UserDto toDto(User u) {
        return new AuthDtos.UserDto(u.getId(), u.getUsername(), u.getDisplayName(),
                u.getRole().name(), u.getRole().getLabel(), u.isEnabled(),
                u.getCreatedAt() == null ? null : u.getCreatedAt()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }
}
