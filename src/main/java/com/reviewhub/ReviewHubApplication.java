package com.reviewhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CodeReview Hub - AI 代码评审平台
 *
 * 借鉴 open-code-review（阿里巴巴开源）的混合架构设计：
 *  - 确定性流程负责：diff 解析、文件筛选、规则路由、行号定位
 *  - LLM 智能体负责：风险判断，可通过 file_read / code_search 工具主动获取上下文
 */
@SpringBootApplication
public class ReviewHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewHubApplication.class, args);
    }
}
