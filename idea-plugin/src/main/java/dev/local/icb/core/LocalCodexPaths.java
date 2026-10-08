// 只发现已有的本机 Codex 安装与配置目录，不下载软件或运行 Shell 初始化文件。
package dev.local.icb.core;

import java.nio.file.*;
import java.util.*;

/** 识别环境变量和 macOS 常见安装目录，减少首次设置的手工输入。 */
public final class LocalCodexPaths {
    private LocalCodexPaths() {}

    /** 查找环境 PATH 或 macOS 的 Homebrew 安装目录中的现有 Codex 可执行文件。 */
    public static Path findExecutable() {
        Set<String> directories = new LinkedHashSet<>();
        String searchPath = System.getenv("PATH");
        if (searchPath != null)
            directories.addAll(Arrays.asList(searchPath.split(java.io.File.pathSeparator)));
        if (System.getProperty("os.name").equals("Mac OS X"))
            directories.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin"));
        for (String directory : directories) {
            try {
                Path parent = Path.of(directory);
                if (!parent.isAbsolute()) continue;
                Path candidate = parent.resolve("codex");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate))
                    return candidate;
            } catch (InvalidPathException ex) {
                /* PATH 中无效的目录不属于可执行文件候选。 */
            }
        }
        return null;
    }

    /** 使用当前进程的 CODEX_HOME；未设置时采用 Codex 的默认配置目录。 */
    public static Path configurationHome() {
        String configured = System.getenv("CODEX_HOME");
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".codex")
                : Path.of(configured);
    }
}
