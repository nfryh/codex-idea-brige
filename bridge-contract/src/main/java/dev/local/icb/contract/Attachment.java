// 不可变的选区、全文或路径引用，与 IntelliJ 对象隔离。
package dev.local.icb.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户在指定时刻冻结的上下文。
 *
 * @param attachmentId 单次引用标识
 * @param rootId 已授权文件根标识
 * @param relativePath 根内相对文件路径
 * @param kind PATH_ONLY 为路径，SELECTION_SNAPSHOT 为选区，FILE_SNAPSHOT 为全文
 * @param startOffset 文档 UTF-16 起始偏移；路径引用为 null
 * @param endOffsetExclusive 文档 UTF-16 不含尾端的偏移；路径引用为 null
 * @param startLineOneBased 从 1 开始的第一行；路径引用为 null
 * @param endLineInclusiveOneBased 从 1 开始的最后选中行；路径引用为 null
 * @param documentModificationStamp 捕获时文档版本；路径引用为 null
 * @param contentSha256 正文摘要；路径引用为空
 * @param unsaved true 表示来自未保存文档，false 表示已保存
 * @param capturedAt 本地冻结快照的时刻
 * @param content 精确选中正文、全文，或路径引用的 null
 */
public record Attachment(
        String attachmentId,
        String rootId,
        String relativePath,
        String kind,
        Integer startOffset,
        Integer endOffsetExclusive,
        Integer startLineOneBased,
        Integer endLineInclusiveOneBased,
        Long documentModificationStamp,
        String contentSha256,
        boolean unsaved,
        Instant capturedAt,
        String content) {
    /**
     * 冻结文本并校验代理对、行分隔符和容量。
     *
     * @param rootId 已授权文件根标识
     * @param relativePath 根内相对文件路径
     * @param kind SELECTION_SNAPSHOT 表示选区，FILE_SNAPSHOT 表示全文
     * @param text IDEA 使用 LF 行分隔符的完整文档文本
     * @param start 选区开始的 UTF-16 偏移
     * @param end 选区结束的 UTF-16 偏移，不包含该位置字符
     * @param stamp 捕获时文档修改版本
     * @param unsaved true 表示文档未保存，false 表示已保存
     */
    public static Attachment snapshot(
            String rootId,
            String relativePath,
            String kind,
            String text,
            int start,
            int end,
            long stamp,
            boolean unsaved) {
        if (!kind.equals("SELECTION_SNAPSHOT") && !kind.equals("FILE_SNAPSHOT"))
            throw new IllegalArgumentException("KIND_INVALID");
        if (text.contains("\r")
                || start < 0
                || end < start
                || end > text.length()
                || splitPair(text, start)
                || splitPair(text, end)) throw new IllegalArgumentException("RANGE_INVALID");
        String content = text.substring(start, end);
        if (Json.bytes(content) > 131072)
            throw new IllegalArgumentException("ICB_CONTEXT_TOO_LARGE");
        // 结束行按最后一个真实选中字符计算，空文件不读取 end - 1。
        int first = 1 + (int) text.substring(0, start).chars().filter(c -> c == '\n').count();
        int last =
                end == start
                        ? first
                        : 1
                                + (int)
                                        text.substring(0, end - 1)
                                                .chars()
                                                .filter(c -> c == '\n')
                                                .count();
        return new Attachment(
                UUID.randomUUID().toString(),
                rootId,
                relativePath,
                kind,
                start,
                end,
                first,
                last,
                stamp,
                Json.sha(content),
                unsaved,
                Instant.now(),
                content);
    }

    /**
     * 检查范围是否切开 UTF-16 代理对。
     *
     * @param offset 当前选区端点的 UTF-16 偏移
     */
    private static boolean splitPair(String text, int offset) {
        return offset > 0
                && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    /**
     * 创建只有路径、不含正文的引用。
     *
     * @param rootId 已授权文件根标识
     * @param relativePath 根内相对文件路径
     */
    public static Attachment path(String rootId, String relativePath) {
        return new Attachment(
                UUID.randomUUID().toString(),
                rootId,
                relativePath,
                "PATH_ONLY",
                null,
                null,
                null,
                null,
                null,
                "",
                false,
                Instant.now(),
                null);
    }

    /** 返回完全相同的引用判据，不将同路径不同快照合并。 */
    public String dedupeKey() {
        return rootId
                + "\0"
                + relativePath
                + "\0"
                + kind
                + "\0"
                + startOffset
                + "\0"
                + endOffsetExclusive
                + "\0"
                + contentSha256;
    }
}
