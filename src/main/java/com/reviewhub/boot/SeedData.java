package com.reviewhub.boot;

import com.reviewhub.domain.ReviewRule;
import com.reviewhub.domain.ReviewSkill;
import com.reviewhub.domain.Severity;
import com.reviewhub.domain.User;
import com.reviewhub.repo.AppSettingRepository;
import com.reviewhub.repo.ReviewRuleRepository;
import com.reviewhub.repo.ReviewSkillRepository;
import com.reviewhub.domain.AppSetting;
import com.reviewhub.repo.UserRepository;
import com.reviewhub.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内置种子数据：管理员账号、技能、规则、默认配置。
 * 仅在缺失时插入，不会覆盖用户在 Web 页面上的修改。
 */
@Component
public class SeedData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    private final ReviewSkillRepository skills;
    private final ReviewRuleRepository rules;
    private final AppSettingRepository settings;
    private final UserRepository users;

    public SeedData(ReviewSkillRepository skills, ReviewRuleRepository rules,
                    AppSettingRepository settings, UserRepository users) {
        this.skills = skills;
        this.rules = rules;
        this.settings = settings;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedAdmin();
        seedSettings();
        seedSkills();
        seedRules();
    }

    /** 默认管理员：admin / admin123（MD5 密文存储），请登录后尽快修改密码 */
    private void seedAdmin() {
        if (users.count() > 0) return;
        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(AuthService.md5("admin123"));
        admin.setDisplayName("系统管理员");
        admin.setRole(com.reviewhub.domain.Role.ADMIN);
        admin.setEnabled(true);
        users.save(admin);
        log.info("已创建默认管理员账号 admin（初始密码 admin123，请尽快修改）");
    }

    private void seedSettings() {
        Map<String, String> defaults = Map.ofEntries(
                // 模型连接：默认 mock 提供者，开箱即用；接入私有大模型后在「系统设置」页切换为 openai
                Map.entry("llm.provider", "mock"),
                Map.entry("llm.baseUrl", ""),
                Map.entry("llm.apiKey", ""),
                Map.entry("llm.model", ""),
                Map.entry("llm.temperature", "0.1"),
                // 评审引擎
                Map.entry("review.maxRounds", "6"),
                Map.entry("review.groupMaxLines", "600"),
                Map.entry("review.reflection", "true"),
                // 质量门禁：默认 CRITICAL 及以上问题阻断流水线
                Map.entry("review.gateSeverity", "CRITICAL"),
                // 平台访问密钥（为空表示不校验）
                Map.entry("security.apiKey", "")
        );
        defaults.forEach((k, v) -> {
            if (!settings.existsById(k)) {
                settings.save(new AppSetting(k, v));
            }
        });
    }

    private void seedSkills() {
        if (skills.count() > 0) return;

        ReviewSkill general = new ReviewSkill();
        general.setName("通用代码评审");
        general.setDescription("资深全栈工程师视角，覆盖正确性、健壮性与可维护性（默认技能）");
        general.setDefaulted(true);
        general.setBuiltin(true);
        general.setSystemPrompt("""
                你是一位拥有 15 年经验的资深全栈工程师，正在为团队做代码评审。

                评审原则：
                1. 只报告 diff 中真实可见、有依据的问题，绝不臆测不存在的代码
                2. 关注正确性（逻辑错误、边界条件、空指针）、健壮性（异常处理、资源释放）、可维护性（命名、结构、重复代码）
                3. 每个问题给出具体、可执行的修改建议
                4. 不做风格化的吹毛求疵，优先报告影响功能与安全的问题
                5. severity 判定：BLOCKER=会导致故障或数据错误；CRITICAL=安全漏洞或高概率触发的缺陷；MAJOR=明确的缺陷隐患；MINOR=改进建议；INFO=提示信息""");

        ReviewSkill security = new ReviewSkill();
        security.setName("安全审计专家");
        security.setDescription("以攻击者视角审计注入、越权、敏感信息等安全风险");
        security.setBuiltin(true);
        security.setSystemPrompt("""
                你是一位应用安全专家，以攻击者视角审计代码变更。

                审计重点：
                1. 注入类：SQL 注入、命令注入、XSS、反序列化、路径穿越、SSRF
                2. 认证授权：越权访问、缺失鉴权、会话固定、弱加密
                3. 敏感信息：硬编码密钥/密码/token、日志泄露个人信息、错误信息泄露内部细节
                4. 依赖与配置：不安全的默认配置、已知漏洞组件版本

                只报告 diff 范围内真实存在、可论证的安全风险，说明攻击路径与利用条件。安全类问题 severity 至少为 CRITICAL。""");

        ReviewSkill performance = new ReviewSkill();
        performance.setName("性能优化专家");
        performance.setDescription("识别 N+1 查询、内存泄漏、锁粒度等性能风险");
        performance.setBuiltin(true);
        performance.setSystemPrompt("""
                你是一位性能优化专家，专注识别代码变更中的性能风险。

                审查重点：
                1. 数据库：N+1 查询、缺失索引的全表扫描、大结果集未分页、循环内查询
                2. 内存：大对象拷贝、无界集合增长、缓存失效策略、内存泄漏（未释放的监听器/连接）
                3. 并发：锁粒度过粗、线程池配置不当、异步链路中的阻塞调用
                4. 算法：高复杂度循环嵌套、重复计算

                只报告有真实影响的问题，尽量估算影响量级（如 QPS、数据规模）并给出优化建议。""");

        skills.save(general);
        skills.save(security);
        skills.save(performance);
        log.info("已内置 3 个评审技能");
    }

    private void seedRules() {
        if (rules.count() > 0) return;

        save("空指针防护", "可能为 null 的对象未判空直接使用", "**/*.java", Severity.CRITICAL,
                "关注：可空返回值未判空；Map.get 后直接链式调用；Optional 误用；自动拆箱 NPE。");
        save("资源未关闭", "流、连接等资源未正确释放", "**/*.java", Severity.CRITICAL,
                "关注：流/连接/Channel 打开后未在 finally 或 try-with-resources 中关闭。");
        save("线程安全", "共享可变状态的并发访问问题", "**/*.java", Severity.CRITICAL,
                "关注：共享可变状态无同步；非线程安全集合用于并发场景；SimpleDateFormat 等非线程安全类共享使用。");
        save("SQL 注入", "SQL 拼接导致的注入风险", "**/*.java,**/*.py,**/*.go,**/*.php,**/*.rb", Severity.CRITICAL,
                "关注：SQL 字符串拼接外部输入；MyBatis 中 ${} 用法；动态排序/表名字段拼接。");
        save("XSS 防护", "用户输入未经转义直接渲染", "**/*.{js,ts,jsx,tsx,vue,html}", Severity.CRITICAL,
                "关注：v-html / dangerouslySetInnerHTML / innerHTML 直接渲染用户输入；未转义插值输出。");
        save("硬编码凭据", "源码中硬编码密码、密钥、token", "**/*", Severity.CRITICAL,
                "关注：源码中的密码、AK/SK、token、私钥；测试代码中的真实凭据。");
        save("敏感信息泄露", "日志或响应中暴露敏感数据", "**/*", Severity.MAJOR,
                "关注：日志打印密码/身份证/手机号等个人信息；异常堆栈直接返回给前端。");
        save("异常处理", "异常被吞掉或捕获过宽", "**/*.java", Severity.MAJOR,
                "关注：空 catch 块；捕获 Exception/Throwable 过宽；异常信息丢失；循环内抛出异常。");
        save("日志规范", "日志使用不当", "**/*.java", Severity.MINOR,
                "关注：System.out 代替日志框架；日志级别误用；关键链路缺少日志上下文。");
        save("API 输入校验", "接口缺少参数校验", "**/*Controller.java,**/controller/**/*.java,**/api/**/*.py", Severity.MAJOR,
                "关注：缺少参数校验（长度/格式/范围）；批量接口无数量上限；分页无上限。");
        save("前端内存泄漏", "组件卸载后残留定时器与监听器", "**/*.{js,ts,jsx,tsx,vue}", Severity.MAJOR,
                "关注：未清理的定时器/事件监听/观察者；组件卸载后仍更新状态。");
        save("事务正确性", "多写操作缺失事务或事务失效", "**/*.java", Severity.MAJOR,
                "关注：多写操作缺失事务；事务内远程调用/耗时操作；@Transactional 自调用失效。");
        save("配置与依赖风险", "危险配置或存在风险的依赖", "**/pom.xml,**/package.json,**/*.yml,**/*.yaml,**/*.properties", Severity.MAJOR,
                "关注：危险配置（debug 模式、明文密码）；SNAPSHOT 依赖；版本过旧存在已知漏洞。");
        save("代码可读性", "影响可读性的代码问题", "**/*", Severity.MINOR,
                "关注：魔法数字/字符串；超长方法（超过 80 行）；明显重复的代码块；误导性命名。");

        log.info("已内置 14 条评审规则");
    }

    private void save(String name, String desc, String pattern, Severity severity, String prompt) {
        ReviewRule r = new ReviewRule();
        r.setName(name);
        r.setDescription(desc);
        r.setFilePattern(pattern);
        r.setSeverity(severity);
        r.setPrompt(prompt);
        r.setEnabled(true);
        r.setBuiltin(true);
        rules.save(r);
    }
}
