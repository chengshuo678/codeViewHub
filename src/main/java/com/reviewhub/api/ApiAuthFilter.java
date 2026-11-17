package com.reviewhub.api;

import com.reviewhub.domain.Role;
import com.reviewhub.domain.User;
import com.reviewhub.service.AuthService;
import com.reviewhub.service.SettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 统一鉴权过滤器：
 *  - /api/auth/login           → 放行
 *  - /api/jenkins/**           → 服务间调用，校验 X-API-Key（未配置密钥时放行）
 *  - 其余 /api/**              → 校验登录会话（X-Auth-Token）；管理类端点要求 ADMIN 角色
 *  - 静态资源与非 /api 路径      → 放行（页面由前端路由守卫控制）
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    /** 仅管理员可访问的 API 前缀 */
    private static final Set<String> ADMIN_PATHS = Set.of(
            "/api/rules", "/api/skills", "/api/settings", "/api/users");

    private final AuthService auth;
    private final SettingsService settings;

    public ApiAuthFilter(AuthService auth, SettingsService settings) {
        this.auth = auth;
        this.settings = settings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        if (path.equals("/api/auth/login")) {
            chain.doFilter(request, response);
            return;
        }

        // Jenkins 服务间调用：API Key 校验
        if (path.startsWith("/api/jenkins")) {
            String key = settings.get("security.apiKey").trim();
            if (!key.isEmpty()) {
                String provided = request.getHeader("X-API-Key");
                if (provided == null || !provided.equals(key)) {
                    reject(response, HttpServletResponse.SC_UNAUTHORIZED, "无效的 X-API-Key，请在系统设置中核对接入密钥");
                    return;
                }
            }
            chain.doFilter(request, response);
            return;
        }

        // 其余 API：登录会话校验
        User user = auth.validate(request.getHeader("X-Auth-Token")).orElse(null);
        if (user == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或会话已过期，请重新登录");
            return;
        }
        if (requiresAdmin(path) && user.getRole() != Role.ADMIN) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "需要管理员权限");
            return;
        }
        request.setAttribute("currentUser", user);
        chain.doFilter(request, response);
    }

    private boolean requiresAdmin(String path) {
        return ADMIN_PATHS.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    private void reject(HttpServletResponse response, int status, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
