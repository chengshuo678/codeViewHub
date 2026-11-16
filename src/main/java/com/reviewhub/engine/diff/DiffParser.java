package com.reviewhub.engine.diff;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一 diff 格式解析器（确定性解析，借鉴 open-code-review 的设计：
 * 由代码而非模型来决定行号与文件归属，避免 LLM 行号漂移）。
 */
@Component
public class DiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    public List<DiffFile> parse(String diffText) {
        List<DiffFile> files = new ArrayList<>();
        if (diffText == null || diffText.isBlank()) return files;

        String[] lines = diffText.replace("\r\n", "\n").split("\n", -1);
        DiffFile current = null;
        DiffFile.Hunk hunk = null;
        int oldNo = 0, newNo = 0;
        boolean inHunkBody = false;

        for (String raw : lines) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;

            if (line.startsWith("diff --git ")) {
                current = new DiffFile();
                current.path = extractGitPath(line);
                current.oldPath = current.path;
                files.add(current);
                hunk = null;
                inHunkBody = false;
                continue;
            }
            if (current == null) continue;

            if (line.startsWith("--- ")) {
                String p = stripTimestamp(line.substring(4));
                if (p.equals("/dev/null")) {
                    current.type = DiffFile.ChangeType.ADD; // 旧文件为空 = 新增文件
                } else {
                    current.oldPath = unquote(p.startsWith("a/") ? p.substring(2) : p);
                }
                inHunkBody = false;
                continue;
            }
            if (line.startsWith("+++ ")) {
                String p = stripTimestamp(line.substring(4));
                if (p.equals("/dev/null")) {
                    current.type = DiffFile.ChangeType.DELETE;
                } else {
                    current.path = unquote(p.startsWith("b/") ? p.substring(2) : p);
                }
                inHunkBody = false;
                continue;
            }
            if (line.startsWith("new file mode")) {
                current.type = DiffFile.ChangeType.ADD;
                inHunkBody = false;
                continue;
            }
            if (line.startsWith("rename from ") || line.startsWith("rename to ")) {
                current.type = DiffFile.ChangeType.RENAME;
                if (line.startsWith("rename to ")) current.path = unquote(line.substring(10).trim());
                inHunkBody = false;
                continue;
            }
            if (line.startsWith("Binary files") || line.startsWith("GIT binary patch")) {
                current.binary = true;
                inHunkBody = false;
                continue;
            }

            Matcher m = HUNK_HEADER.matcher(line);
            if (m.matches()) {
                oldNo = Integer.parseInt(m.group(1));
                newNo = Integer.parseInt(m.group(3));
                hunk = new DiffFile.Hunk(oldNo, newNo);
                current.hunks.add(hunk);
                inHunkBody = true;
                continue;
            }

            if (!inHunkBody || hunk == null) continue; // index/similarity/mode 等元信息行

            if (line.startsWith("+")) {
                hunk.lines.add(new DiffFile.DiffLine(DiffFile.LineType.ADD, 0, newNo, line.substring(1)));
                newNo++;
                hunk.newEnd = Math.max(hunk.newEnd, newNo - 1);
                current.addedCount++;
            } else if (line.startsWith("-")) {
                hunk.lines.add(new DiffFile.DiffLine(DiffFile.LineType.DEL, oldNo, 0, line.substring(1)));
                oldNo++;
                current.deletedCount++;
            } else if (line.startsWith(" ")) {
                hunk.lines.add(new DiffFile.DiffLine(DiffFile.LineType.CONTEXT, oldNo, newNo, line.length() > 1 ? line.substring(1) : ""));
                oldNo++;
                newNo++;
                hunk.newEnd = Math.max(hunk.newEnd, newNo - 1);
            } else if (line.startsWith("\\ No newline")) {
                // 忽略
            }
        }
        return files;
    }

    /** 从 "diff --git a/x b/y" 提取 b 侧路径 */
    private String extractGitPath(String line) {
        String body = line.substring("diff --git ".length()).trim();
        // 常规格式 a/<path> b/<path>，路径可能含空格（带引号时优先用 +++ 行修正）
        int idx = body.indexOf(" b/");
        if (idx > 0) {
            String b = body.substring(idx + 3);
            if (body.startsWith("\"a/")) { // 带引号路径 "a/p sp" "b/p sp"
                int qb = body.indexOf("\" b/");
                if (qb > 0) b = body.substring(qb + 4).replace("\"", "");
            }
            return unquote(b);
        }
        return body;
    }

    private String stripTimestamp(String s) {
        int tab = s.indexOf('\t');
        return tab > 0 ? s.substring(0, tab) : s;
    }

    private String unquote(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
