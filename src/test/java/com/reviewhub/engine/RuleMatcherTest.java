package com.reviewhub.engine;

import com.reviewhub.domain.ReviewRule;
import com.reviewhub.domain.Severity;
import com.reviewhub.engine.diff.DiffFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleMatcherTest {

    private final RuleMatcher matcher = new RuleMatcher();

    private ReviewRule rule(String pattern) {
        ReviewRule r = new ReviewRule();
        r.setName("t");
        r.setFilePattern(pattern);
        r.setSeverity(Severity.MAJOR);
        return r;
    }

    private DiffFile file(String path) {
        DiffFile f = new DiffFile();
        f.path = path;
        return f;
    }

    @Test
    void globMatching() {
        List<ReviewRule> rules = List.of(rule("**/*.java"), rule("**/*.{js,ts}"), rule("src/**"), rule("**/pom.xml"));

        assertEquals(1, matcher.match(rules, file("a/b/C.java")).size());
        // 花括号展开：**/*.{js,ts} 命中 ts，但不命中 tsx
        assertEquals(1, matcher.match(rules, file("x/app.ts")).size());
        assertEquals(0, matcher.match(rules, file("x/app.tsx")).size());
        assertEquals(1, matcher.match(rules, file("src/deep/nested/any.txt")).size());
        // **/x 需要命中根目录下的 x
        assertEquals(1, matcher.match(rules, file("pom.xml")).size());
        assertEquals(1, matcher.match(rules, file("build/pom.xml")).size());
    }

    @Test
    void multiPatternAndEmpty() {
        // 单条规则的多模式：**/*.java,**/*.py 应命中 a.py
        assertEquals(1, matcher.match(List.of(rule("**/*.java,**/*.py")), file("a.py")).size());
        // 根目录文件也能被 **/ 前缀模式命中
        assertEquals(1, matcher.match(List.of(rule("**/*.py")), file("main.py")).size());

        // 空模式匹配所有文件
        assertEquals(1, matcher.match(List.of(rule("")), file("anything/x.yml")).size());
        // 无匹配规则
        assertEquals(0, matcher.match(List.of(), file("a.java")).size());
    }
}
