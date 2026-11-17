package com.reviewhub.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 认证与用户管理 DTO */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") @Size(max = 60) String username,
            @NotBlank(message = "密码不能为空") @Size(max = 100) String password) {
    }

    public record LoginResponse(String token, UserDto user) {
    }

    public record UserDto(Long id, String username, String displayName,
                          String role, String roleLabel, boolean enabled, String createdAt) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 100) String oldPassword,
            @NotBlank @Size(min = 6, max = 100, message = "新密码长度需为 6-100 位") String newPassword) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 60) String username,
            @NotBlank @Size(min = 6, max = 100, message = "密码长度需为 6-100 位") String password,
            @Size(max = 100) String displayName,
            @NotBlank String role) {
    }

    public record UpdateUserRequest(
            @Size(max = 100) String displayName,
            @NotBlank String role,
            Boolean enabled) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 6, max = 100, message = "密码长度需为 6-100 位") String newPassword) {
    }
}
