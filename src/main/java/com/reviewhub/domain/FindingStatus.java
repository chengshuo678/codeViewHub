package com.reviewhub.domain;

/** 单条评审问题的处理状态 */
public enum FindingStatus {
    /** 待处理 */
    PENDING,
    /** 已确认（真实问题） */
    CONFIRMED,
    /** 已忽略（不需要修复） */
    IGNORED,
    /** 误报 */
    FALSE_POSITIVE;

    public String getLabel() {
        switch (this) {
            case PENDING: return "待处理";
            case CONFIRMED: return "已确认";
            case IGNORED: return "已忽略";
            default: return "误报";
        }
    }
}
