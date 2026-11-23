package com.reviewhub.domain;

/** 深度扫描（仓库级影响分析）状态 */
public enum DeepScanStatus {
    NONE, QUEUED, RUNNING, SUCCESS, FAILED
}
