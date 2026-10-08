// 检查可见草稿引用的路径、行号、控制字符和完整标记匹配。
package dev.local.icb.contract;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.file.Path;

/** 引用是用户可检查的普通文本，不允许借文件名插入终端按键。 */
public class DraftReferenceTest {
    @Test
    public void selectedLinesUseLastSelectedCharacterAndCwdRelativePath() {
        var item =
                Attachment.snapshot(
                        "root",
                        "src/a.java",
                        "SELECTION_SNAPSHOT",
                        "one\ntwo\nthree\n",
                        0,
                        8,
                        1,
                        false);
        assertEquals(
                "@src/a.java#L1-2",
                DraftReference.format(
                        Path.of("/workspace/src/a.java"), Path.of("/workspace"), item));
    }

    @Test
    public void pathOnlyAndOtherContentRootRemainUnambiguous() {
        assertEquals(
                "@/other/a.java",
                DraftReference.format(
                        Path.of("/other/a.java"),
                        Path.of("/workspace"),
                        Attachment.path("root", "a.java")));
        assertEquals(
                "@\"src/a b.java\"",
                DraftReference.format(
                        Path.of("/workspace/src/a b.java"),
                        Path.of("/workspace"),
                        Attachment.path("root", "src/a b.java")));
    }

    @Test
    public void controlCharactersCannotBecomeTerminalInput() {
        for (String name : new String[] {"a\nb", "a\rb", "a\u001bb"})
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            DraftReference.format(
                                    Path.of("/workspace/" + name),
                                    Path.of("/workspace"),
                                    Attachment.path("root", name)));
    }

    @Test
    public void deletedReferenceAndSimilarLineNumberDoNotMatch() {
        assertTrue(
                DraftReference.present(
                        "existing draft @src/a.java#L1-2 explain", "@src/a.java#L1-2"));
        assertTrue(DraftReference.present("@\"src/a b.java\"#L1", "@\"src/a b.java\"#L1"));
        assertFalse(DraftReference.present("@src/a.java#L10", "@src/a.java#L1"));
        assertFalse(DraftReference.present("prefix@src/a.java#L1", "@src/a.java#L1"));
        assertFalse(DraftReference.present("explain this", "@src/a.java#L1-2"));
    }
}
