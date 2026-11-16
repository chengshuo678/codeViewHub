package com.reviewhub.engine.diff;

import java.util.ArrayList;
import java.util.List;

/** 解析后的单个文件 diff */
public class DiffFile {

    public enum ChangeType { ADD, MODIFY, DELETE, RENAME }

    public enum LineType { CONTEXT, ADD, DEL }

    public static class DiffLine {
        public final LineType type;
        public final int oldNo;   // 旧文件行号（ADD 行为 0）
        public final int newNo;   // 新文件行号（DEL 行为 0）
        public final String text;

        public DiffLine(LineType type, int oldNo, int newNo, String text) {
            this.type = type;
            this.oldNo = oldNo;
            this.newNo = newNo;
            this.text = text;
        }
    }

    public static class Hunk {
        public final int oldStart;
        public final int newStart;
        public final List<DiffLine> lines = new ArrayList<>();
        public int newEnd; // 最后一个新行号

        public Hunk(int oldStart, int newStart) {
            this.oldStart = oldStart;
            this.newStart = newStart;
            this.newEnd = newStart - 1;
        }
    }

    public String path;        // 新文件路径
    public String oldPath;     // 旧文件路径
    public ChangeType type = ChangeType.MODIFY;
    public boolean binary = false;
    public final List<Hunk> hunks = new ArrayList<>();
    public int addedCount = 0;
    public int deletedCount = 0;

    /** 该文件所有新增行的行号 */
    public List<Integer> addedLineNumbers() {
        List<Integer> nums = new ArrayList<>();
        for (Hunk h : hunks) {
            for (DiffLine l : h.lines) {
                if (l.type == LineType.ADD) nums.add(l.newNo);
            }
        }
        return nums;
    }

    /** 获取某行附近 ±context 行的文本（供反思阶段核对） */
    public String contextAround(int line, int context) {
        StringBuilder sb = new StringBuilder();
        for (Hunk h : hunks) {
            for (DiffLine l : h.lines) {
                if (l.type != LineType.DEL && l.newNo > 0 && Math.abs(l.newNo - line) <= context) {
                    sb.append(l.newNo).append(l.type == LineType.ADD ? "+ " : "  ").append(l.text).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
