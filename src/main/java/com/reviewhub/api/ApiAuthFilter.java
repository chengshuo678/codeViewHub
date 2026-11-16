package com.reviewhub.api;

import com.reviewhub.service.SettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Jenkins 接入密钥校验：
 * 若「系统设置」中配置了 security.apiKey，则 /api/jenkins/** 必须携带 X-API-Key。
 * 未配置时不校验（内网默认开放）。Web 页面使用的 /api/** 始终开放。
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    private final SettingsService settings;

    public ApiAuthFilter(SettingsService settings) {
        this.settings = settings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = settings.get("security.apiKey").trim();
        if (!key.isEmpty() && request.getRequestURI().startsWith("/api/jenkins")) {
            String provided = request.getHeader("X-API-Key");
            if (provided == null || !provided.equals(key)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"无效的 X-API-Key，请在系统设置中核对接入密钥\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
