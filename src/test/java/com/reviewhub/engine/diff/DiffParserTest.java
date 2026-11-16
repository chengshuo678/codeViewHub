package com.reviewhub.engine.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    @Test
    void parseStandardDiff() {
        String diff = """
                diff --git a/src/A.java b/src/A.java
                index 123..456 100644
                --- a/src/A.java
                +++ b/src/A.java
                @@ -10,4 +10,5 @@ class A {
                 context line
                -old line
                +new line 1
                +new line 2
                 tail
                """;
        List<DiffFile> files = parser.parse(diff);
        assertEquals(1, files.size());
        DiffFile f = files.get(0);
        assertEquals("src/A.java", f.path);
        assertEquals(2, f.addedCount);
        assertEquals(1, f.deletedCount);
        assertEquals(1, f.hunks.size());

        DiffFile.Hunk h = f.hunks.get(0);
        assertEquals(10, h.oldStart);
        assertEquals(10, h.newStart);
        // 新增行号必须是 11、12
        assertEquals(List.of(11, 12), f.addedLineNumbers());
        // 旧行号：context=10, del=11, context=12
        assertEquals(11, h.lines.get(1).oldNo);
        assertEquals(0, h.lines.get(1).newNo);
    }

    @Test
    void parseNewFileAndDeleteFile() {
        String diff = """
                diff --git a/new.txt b/new.txt
                new file mode 100644
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1,2 @@
                +hello
                +world
                diff --git a/gone.txt b/gone.txt
                deleted file mode 100644
                --- a/gone.txt
                +++ /dev/null
                @@ -1,1 +0,0 @@
                -bye
                """;
        List<DiffFile> files = parser.parse(diff);
        assertEquals(2, files.size());

        DiffFile added = files.get(0);
        assertEquals(DiffFile.ChangeType.ADD, added.type);
        assertEquals("new.txt", added.path);
        assertEquals(2, added.addedCount);
        assertEquals(List.of(1, 2), added.addedLineNumbers());

        DiffFile deleted = files.get(1);
        assertEquals(DiffFile.ChangeType.DELETE, deleted.type);
        assertEquals(0, deleted.addedCount);
        assertTrue(deleted.addedLineNumbers().isEmpty());
    }

    @Test
    void parseRenamesBinaryAndEmpty() {
        String diff = """
                diff --git a/old.java b/rename/dst.java
                similarity index 90%
                rename from old.java
                rename to rename/dst.java
                @@ -1,2 +1,2 @@
                 ctx
                -a
                +b
                diff --git a/logo.png b/logo.png
                Binary files a/logo.png and b/logo.png differ
                """;
        List<DiffFile> files = parser.parse(diff);
        assertEquals(2, files.size());
        assertEquals("rename/dst.java", files.get(0).path);
        assertEquals(DiffFile.ChangeType.RENAME, files.get(0).type);
        assertTrue(files.get(1).binary);

        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void contextAroundReturnsNeighborhood() {
        String diff = """
                diff --git a/x.java b/x.java
                --- a/x.java
                +++ b/x.java
                @@ -1,4 +1,4 @@
                 one
                +two
                 three
                +four
                """;
        DiffFile f = parser.parse(diff).get(0);
        String ctx = f.contextAround(2, 1);
        // 上下文行格式 "1  two-space one"，新增行格式 "2+ two"
        assertTrue(ctx.contains("1  one"));
        assertTrue(ctx.contains("2+ two"));
        assertFalse(ctx.contains("four"));
    }
}
