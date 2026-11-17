package com.reviewhub.domain;

/** 用户角色 */
public enum Role {
    ADMIN("管理员"),
    USER("普通用户");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
