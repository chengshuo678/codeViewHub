package com.reviewhub.engine;

import com.reviewhub.domain.ReviewRule;
import com.reviewhub.engine.diff.DiffFile;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;

/** 规则路由：根据文件路径匹配适用规则（确定性，借鉴 open-code-review rule routing） */
@Component
public class RuleMatcher {

    private final AntPathMatcher matcher = new AntPathMatcher();

    /** 返回与该文件匹配的全部启用规则 */
    public List<ReviewRule> match(List<ReviewRule> rules, DiffFile file) {
        List<ReviewRule> matched = new ArrayList<>();
        for (ReviewRule rule : rules) {
            if (matchPattern(rule.getFilePattern(), file.path)) {
                matched.add(rule);
            }
        }
        return matched;
    }

    private boolean matchPattern(String pattern, String path) {
        if (pattern == null || pattern.isBlank()) return true; // 空模式 = 匹配所有文件
        for (String p : splitTopLevel(pattern)) { // 花括号内的逗号不作为分隔符
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.equals("**") || t.equals("*")) return true;
            for (String expanded : expandBraces(t)) {
                if (matcher.match(expanded, path)) return true;
                // AntPathMatcher 的 "**/x" 不匹配根目录下的 "x"，这里补齐该语义
                if (expanded.startsWith("**/") && matcher.match(expanded.substring(3), path)) return true;
            }
        }
        return false;
    }

    /** 顶层逗号切分（忽略 {} 内的逗号） */
    private List<String> splitTopLevel(String pattern) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (char c : pattern.toCharArray()) {
            if (c == '{') depth++;
            else if (c == '}') depth = Math.max(0, depth - 1);
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    /** 展开 Ant 通配符中的 {a,b,c} 花括号组（AntPathMatcher 原生不支持） */
    private List<String> expandBraces(String pattern) {
        int l = pattern.indexOf('{');
        if (l < 0) return List.of(pattern);
        int r = pattern.indexOf('}', l);
        if (r < 0) return List.of(pattern);
        String prefix = pattern.substring(0, l);
        String suffix = pattern.substring(r + 1);
        List<String> out = new ArrayList<>();
        for (String alt : pattern.substring(l + 1, r).split(",")) {
            out.addAll(expandBraces(prefix + alt.trim() + suffix));
        }
        return out;
    }
}
