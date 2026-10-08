// 原生终端草稿中的可见文件引用；不包含终端控制字符或源码正文。
package dev.local.icb.contract;

import java.nio.file.Path;

/** 把已校验的文件路径与冻结行号转换为用户能够检查、删除的引用。 */
public final class DraftReference {
    private DraftReference() {}

    /** 优先使用会话目录内的相对路径；目录尚未报告时保留已校验的绝对路径。 */
    public static String format(Path file, Path cwd, Attachment attachment) {
        String path =
                (cwd != null && file.startsWith(cwd) ? cwd.relativize(file) : file)
                        .toString()
                        .replace(java.io.File.separatorChar, '/');
        if (path.isEmpty() || path.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("ICB_REFERENCE_PATH_INVALID");
        // 引号和空白路径采用 JSON 字符串表示，不能让文件名变成按键或多行输入。
        String quoted =
                path.chars()
                                .anyMatch(
                                        c ->
                                                Character.isWhitespace(c)
                                                        || c == '"'
                                                        || c == '\\'
                                                        || c == '#')
                        ? Json.GSON.toJson(path)
                        : path;
        String range =
                attachment.startLineOneBased() == null
                        ? ""
                        : "#L"
                                + attachment.startLineOneBased()
                                + (attachment
                                                .startLineOneBased()
                                                .equals(attachment.endLineInclusiveOneBased())
                                        ? ""
                                        : "-" + attachment.endLineInclusiveOneBased());
        return "@" + quoted + range;
    }

    /**
     * 检查本轮草稿是否仍包含完整引用，避免删除引用后偷偷发送旧快照。
     *
     * @param prompt 本轮原生提交的提示词，仅在内存中检查
     * @param reference 插件生成的完整文件与行号标记
     */
    public static boolean present(String prompt, String reference) {
        int from = 0;
        while ((from = prompt.indexOf(reference, from)) >= 0) {
            int end = from + reference.length();
            if ((from == 0 || Character.isWhitespace(prompt.charAt(from - 1)))
                    && (end == prompt.length() || Character.isWhitespace(prompt.charAt(end))))
                return true;
            from = end;
        }
        return false;
    }
}
