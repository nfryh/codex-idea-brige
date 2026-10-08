// 对已授权根执行真实路径边界和敏感文件检查。
package dev.local.icb.contract;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 每次访问均重新验证真实路径，避免字符串前缀和符号链接越界。 */
public final class PathPolicy {
    /** 当前项目授权的真实文件根，不从请求指定项目。 */
    private final Map<String, Path> roots;

    public PathPolicy(Map<String, Path> roots) {
        this.roots = Map.copyOf(roots);
    }

    /**
     * 解析可传输的项目文件。
     *
     * @param rootId 用户授权的文件根标识
     * @param relativePath 该根内相对路径，禁止绝对路径和父目录跳转
     */
    public Path resolve(String rootId, String relativePath) throws IOException {
        // 常规读取和自动上下文从不取得敏感文件的单次许可。
        return resolve(rootId, relativePath, false);
    }

    /**
     * 显式引用可使用用户针对该冻结引用给出的单次确认。
     *
     * @param rootId 用户授权的文件根标识
     * @param relativePath 根内相对文件路径
     * @param sensitiveConfirmed true 表示用户已明确批准该次敏感引用，false 表示默认拒绝敏感文件；不扩大根边界
     */
    public Path resolve(String rootId, String relativePath, boolean sensitiveConfirmed)
            throws IOException {
        Path root = roots.get(rootId);
        if (root == null || relativePath.contains("\0") || relativePath.contains("\\"))
            throw new IOException("PATH_DENIED");
        // 相对路径由已验证根的同一文件系统创建，避免 IDEA 的包装路径与原生路径混用。
        Path relative = root.getFileSystem().getPath(relativePath);
        if (relative.isAbsolute()) throw new IOException("PATH_DENIED");
        for (Path part : relative)
            if (part.toString().equals("..")) throw new IOException("PATH_DENIED");
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)
                || !file.toRealPath().startsWith(root)
                || !Files.isRegularFile(file)) throw new IOException("PATH_DENIED");
        // 凭据文件和构建目录不进入自动上下文或受限读取。
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (Set.of(".git", ".codex", ".idea", "build", "target", "node_modules", "dist")
                    .contains(name)) throw new IOException("PATH_DENIED");
            if (!sensitiveConfirmed
                    && (Set.of(".ssh", ".aws").contains(name)
                            || name.startsWith(".env")
                            || name.endsWith(".pem")
                            || name.endsWith(".key")
                            || name.endsWith(".p12")
                            || name.contains("credential")
                            || name.equals("auth.json")
                            || name.startsWith("id_rsa")
                            || name.startsWith("id_ed25519")))
                throw new IOException("SENSITIVE_PATH_DENIED");
        }
        return file;
    }

    /**
     * 2026-09-30：校验回调工作目录并返回规范化真实路径，防止显示或注册别名目录。
     *
     * @param cwd 原生 Codex 回调提供的当前工作目录
     * @return 属于当前项目内容根的真实工作目录
     */
    public Path validateCwd(String cwd) throws IOException {
        if (!Path.of(cwd).isAbsolute()) throw new IOException("CWD_DENIED");
        for (Path root : roots.values()) {
            Path actual = root.getFileSystem().getPath(cwd).toRealPath();
            if (actual.startsWith(root)) return actual;
        }
        throw new IOException("CWD_DENIED");
    }

    /** 查找平台文件所对应的已授权根与相对路径。 */
    public Map.Entry<String, String> identify(Path path) throws IOException {
        return identify(path, false);
    }

    /**
     * 将已捕获路径关联到授权根，敏感确认不会放行根外路径。
     *
     * @param sensitiveConfirmed true 表示本次显式敏感引用已经确认，false 表示执行默认敏感策略
     */
    public Map.Entry<String, String> identify(Path path, boolean sensitiveConfirmed)
            throws IOException {
        for (var entry : roots.entrySet()) {
            // 本地文件名保持原样，只使用内容根所拥有的文件系统完成真实路径检查。
            Path real = entry.getValue().getFileSystem().getPath(path.toString()).toRealPath();
            if (real.startsWith(entry.getValue())) {
                String relative = entry.getValue().relativize(real).toString().replace('\\', '/');
                resolve(entry.getKey(), relative, sensitiveConfirmed);
                return Map.entry(entry.getKey(), relative);
            }
        }
        throw new IOException("PATH_DENIED");
    }

    /**
     * 使用目录文件描述符逐层打开授权文件，符号链接替换不能改变读取的根。
     *
     * @param rootId 已授权的项目文件根标识
     * @param relativePath 模型或本轮基线请求的根内相对文件路径
     * @param maxBytes 本次读取允许的最大 UTF-8 字节数，插件使用 131072 字节
     */
    public String readText(String rootId, String relativePath, int maxBytes) throws IOException {
        Path target = resolve(rootId, relativePath).toRealPath(), root = roots.get(rootId);
        if (Files.isSymbolicLink(root)) throw new IOException("PATH_DENIED");
        Object rootKey =
                Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        .fileKey();
        List<DirectoryStream<Path>> opened = new ArrayList<>();
        try {
            // 从文件系统根逐层打开内容根，禁止任何祖先目录在检查之后被换成根外符号链接。
            DirectoryStream<Path> first = Files.newDirectoryStream(root.getRoot());
            opened.add(first);
            if (!(first instanceof SecureDirectoryStream<Path> secure))
                throw new IOException("SECURE_READ_UNAVAILABLE");
            for (Path part : root) {
                SecureDirectoryStream<Path> next =
                        secure.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
                opened.add(next);
                secure = next;
            }
            if (!Objects.equals(
                    rootKey,
                    secure.getFileAttributeView(BasicFileAttributeView.class)
                            .readAttributes()
                            .fileKey())) throw new IOException("PATH_DENIED");
            Path withinRoot = root.relativize(target);
            for (int index = 0; index < withinRoot.getNameCount() - 1; index++) {
                SecureDirectoryStream<Path> next =
                        secure.newDirectoryStream(
                                withinRoot.getName(index), LinkOption.NOFOLLOW_LINKS);
                opened.add(next);
                secure = next;
            }
            Path name = withinRoot.getFileName();
            BasicFileAttributeView attributes =
                    secure.getFileAttributeView(
                            name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes before = attributes.readAttributes();
            if (!before.isRegularFile()) throw new IOException("PATH_DENIED");
            byte[] content;
            try (var channel =
                    secure.newByteChannel(
                            name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                content = Json.bounded(Channels.newInputStream(channel), maxBytes);
            }
            BasicFileAttributes after = attributes.readAttributes();
            if (!Objects.equals(before.fileKey(), after.fileKey())
                    || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(
                            rootKey,
                            Files.readAttributes(
                                            root,
                                            BasicFileAttributes.class,
                                            LinkOption.NOFOLLOW_LINKS)
                                    .fileKey())
                    || !resolve(rootId, relativePath).toRealPath().equals(target))
                throw new IOException("ICB_CONTEXT_STALE");
            for (byte value : content) if (value == 0) throw new IOException("BINARY_FILE");
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(content))
                    .toString();
        } finally {
            for (int index = opened.size() - 1; index >= 0; index--) opened.get(index).close();
        }
    }
}
