package com.reviewhub.api;

import com.reviewhub.api.dto.AuthDtos;
import com.reviewhub.domain.Role;
import com.reviewhub.domain.User;
import com.reviewhub.repo.UserRepository;
import com.reviewhub.service.AuthService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 用户管理 API（仅管理员） */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepo;
    private final AuthService auth;

    public UserController(UserRepository userRepo, AuthService auth) {
        this.userRepo = userRepo;
        this.auth = auth;
    }

    @GetMapping
    public List<AuthDtos.UserDto> list() {
        return userRepo.findAllByOrderById().stream().map(AuthController::toDto).toList();
    }

    @PostMapping
    public AuthDtos.UserDto create(@Valid @RequestBody AuthDtos.CreateUserRequest req) {
        String username = req.username().trim();
        if (userRepo.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在: " + username);
        }
        User u = new User();
        u.setUsername(username);
        u.setPassword(AuthService.md5(req.password()));
        u.setDisplayName(trim(req.displayName()));
        u.setRole(parseRole(req.role()));
        u.setEnabled(true);
        return AuthController.toDto(userRepo.save(u));
    }

    @PutMapping("/{id}")
    public AuthDtos.UserDto update(@PathVariable Long id, @Valid @RequestBody AuthDtos.UpdateUserRequest req) {
        User u = load(id);
        u.setDisplayName(trim(req.displayName()));
        u.setRole(parseRole(req.role()));
        if (req.enabled() != null) u.setEnabled(req.enabled());
        User saved = userRepo.save(u);
        if (!saved.isEnabled() || !saved.getRole().equals(Role.ADMIN)) {
            auth.revokeUserSessions(id); // 禁用或降权后强制下线
        }
        return AuthController.toDto(saved);
    }

    /** 管理员重置任意用户密码 */
    @PutMapping("/{id}/reset-password")
    public AuthDtos.UserDto resetPassword(@PathVariable Long id,
                                          @Valid @RequestBody AuthDtos.ResetPasswordRequest req) {
        User u = load(id);
        u.setPassword(AuthService.md5(req.newPassword()));
        auth.revokeUserSessions(id); // 重置密码后强制重新登录
        return AuthController.toDto(userRepo.save(u));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @RequestAttribute("currentUser") User current) {
        if (id.equals(current.getId())) {
            throw new IllegalArgumentException("不能删除当前登录账号");
        }
        User u = load(id);
        if (u.getRole() == Role.ADMIN && userRepo.findAllByOrderById().stream()
                .filter(x -> x.getRole() == Role.ADMIN && x.isEnabled() && !x.getId().equals(id))
                .count() == 0) {
            throw new IllegalArgumentException("系统至少需要保留一个可用的管理员账号");
        }
        userRepo.delete(u);
        auth.revokeUserSessions(id);
    }

    private User load(Long id) {
        return userRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("用户不存在: " + id));
    }

    private static Role parseRole(String s) {
        try {
            return Role.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效角色: " + s + "（可选 ADMIN / USER）");
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
