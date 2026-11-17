package com.reviewhub.service;

import com.reviewhub.domain.User;
import com.reviewhub.repo.UserRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务：登录会话（内存 token，24 小时滑动过期）与密码 MD5 加密。
 * 服务重启后所有会话失效，用户需重新登录。
 */
@Service
public class AuthService {

    public static final long SESSION_TTL_MS = 24L * 60 * 60 * 1000;

    private final UserRepository userRepo;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public record Session(Long userId, long expiresAt) {
    }

    public AuthService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /** MD5 十六进制密文（小写） */
    public static String md5(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    /** 登录成功返回用户，失败返回 empty */
    public Optional<User> login(String username, String password) {
        Optional<User> u = userRepo.findByUsername(username == null ? "" : username.trim());
        if (u.isEmpty() || !u.get().isEnabled()) return Optional.empty();
        if (!u.get().getPassword().equalsIgnoreCase(md5(password))) return Optional.empty();
        return u;
    }

    public String issueToken(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(userId, System.currentTimeMillis() + SESSION_TTL_MS));
        return token;
    }

    /** 校验 token 并滑动续期，返回用户（无效/过期/被禁用返回 empty） */
    public Optional<User> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        if (System.currentTimeMillis() > s.expiresAt()) {
            sessions.remove(token);
            return Optional.empty();
        }
        Optional<User> u = userRepo.findById(s.userId());
        if (u.isEmpty() || !u.get().isEnabled()) {
            sessions.remove(token);
            return Optional.empty();
        }
        sessions.put(token, new Session(s.userId(), System.currentTimeMillis() + SESSION_TTL_MS));
        return u;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    public User save(User user) {
        return userRepo.save(user);
    }

    /** 该用户其余所有会话失效（改密/禁用后强制重新登录） */
    public void revokeUserSessions(Long userId) {
        sessions.entrySet().removeIf(e -> e.getValue().userId().equals(userId));
    }

    /** 撤销该用户除当前 token 外的全部会话（修改密码后其他设备强制下线，当前会话保留） */
    public void revokeOtherSessions(Long userId, String keepToken) {
        sessions.entrySet().removeIf(e -> e.getValue().userId().equals(userId)
                && (keepToken == null || !e.getKey().equals(keepToken)));
    }
}
