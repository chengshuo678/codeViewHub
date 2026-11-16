package com.reviewhub.domain;

/** 评审任务来源 */
public enum ReviewSource {
    /** Web 页面手动提交 */
    MANUAL,
    /** Jenkins 流水线 */
    JENKINS,
    /** API 直接调用 */
    API
}
