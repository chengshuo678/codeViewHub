package com.reviewhub.domain;

/** 问题严重级别（顺序即严重程度，BLOCKER 最严重） */
public enum Severity {
    BLOCKER("阻断"),
    CRITICAL("严重"),
    MAJOR("主要"),
    MINOR("次要"),
    INFO("提示");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 宽松解析，无法识别时返回默认值 */
    public static Severity parse(String s, Severity dft) {
        if (s == null) return dft;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return dft;
        }
    }
}
