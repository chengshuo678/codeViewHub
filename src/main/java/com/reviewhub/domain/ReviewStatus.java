package com.reviewhub.domain;

/** 评审任务状态 */
public enum ReviewStatus {
    /** 排队中 */
    QUEUED,
    /** 评审中 */
    RUNNING,
    /** 评审完成 */
    SUCCESS,
    /** 评审失败 */
    FAILED
}
