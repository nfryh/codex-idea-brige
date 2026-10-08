// 显式预览后安装，按内容清单合并和卸载，不恢复整份旧配置。
package dev.local.icb.core;

import com.google.gson.*;

import dev.local.icb.contract.*;

import org.tomlj.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 用户级 Hooks 和模型上下文协议工具配置的无损安装器。 */
public final class InstallService {
    private static final List<String> EVENTS =
            List.of("SessionStart", "UserPromptSubmit", "Stop", "Interrupt", "SessionEnd");
    private static final String START = "# BEGIN Codex IDEA Companion v1\n";
    private static final String END = "# END Codex IDEA Companion v1\n";

    /**
     * 安装预览对应的精确原文件和新文件，用于并发比较。
     *
     * @param home 用户选择的 Codex 配置目录
     * @param hooksBefore 预览读取的原 Hooks 文件，未存在时为 null
     * @param hooksAfter 合并后的 Hooks 文件，inline 模式下保持原值
     * @param configBefore 预览读取的原配置文件，未存在时为 null
     * @param configAfter 保留无关内容的配置文件
     * @param manifestBefore 上次安装清单，未存在时为 null
     * @param manifestAfter 新清单，只保存本插件拥有的内容
     * @param preview 不含原配置秘密的变更摘要
     * @param cliVersion 安装器实际执行 --version 得到的版本
     * @param uninstall true 表示只删除匹配的条目，false 表示安装或升级
     */
    public record Plan(
            Path home,
            String hooksBefore,
            String hooksAfter,
            String configBefore,
            String configAfter,
            String manifestBefore,
            String manifestAfter,
            String preview,
            String cliVersion,
            boolean uninstall) {}

    /**
     * 准备配置变更并执行绝对路径的版本检查，不写用户配置。
     *
     * @param home 由用户选择的 Codex 配置目录
     * @param cli 由用户选择的 Codex 可执行文件绝对路径
     * @param java IDE 当前 java.home 下已验证的 Java 可执行路径
     * @param includeMcp true 表示启用可选 IDE 工具，false 表示只安装自动提交回调
     */
    public Plan prepare(Path home, Path cli, Path java, boolean includeMcp) throws IOException {
        if (!System.getProperty("os.name").equals("Mac OS X"))
            throw new IOException("首版安装器仅支持 macOS；其他平台尚未验收");
        if (!home.isAbsolute() || !cli.isAbsolute() || !java.isAbsolute())
            throw new IOException("安装路径必须是绝对路径");
        // 参数独立传递，不执行用户 Shell 初始化文件，也不启动 Codex 会话。
        String version = version(cli, "--version");
        version(java, "-version");
        if (!version.trim().matches("codex-cli \\S+"))
            throw new IOException("所选程序不是有效的 Codex 可执行文件：" + version.trim());
        String hooks = existing(home.resolve("hooks.json"));
        String config = existing(home.resolve("config.toml"));
        String manifest = existing(home.resolve("idea-companion/install-manifest.json"));
        JsonObject old = manifest == null ? new JsonObject() : Json.parse(manifest);
        JsonObject document = hooks == null ? new JsonObject() : Json.parse(hooks);
        if (!document.has("hooks")) document.add("hooks", new JsonObject());
        if (!document.get("hooks").isJsonObject())
            throw new IOException("hooks.json 的 hooks 字段必须是对象");
        String nextConfig = config == null ? "" : config;
        TomlParseResult parsed = Toml.parse(nextConfig);
        if (parsed.hasErrors()) throw new IOException("config.toml 无效，已停止修改");
        // 只移除仍与上次清单逐字或完整 JSON 内容匹配的本插件条目。
        removeOwned(document, old);
        nextConfig = removeBlock(nextConfig, old);
        // 自动沿用现有回调来源，用户不需要理解或选择两种配置格式。
        boolean inlineHooks =
                Toml.parse(nextConfig).getTable("hooks") != null
                        || old.has("hookSource")
                                && old.get("hookSource").getAsString().equals("TOML");
        Path jar = home.resolve("idea-companion/v1/bridge-client.jar");
        JsonArray groups = new JsonArray();
        StringBuilder inline = new StringBuilder();
        for (String event : EVENTS) {
            String mode = event.equals("UserPromptSubmit") ? "submit" : "event";
            // 短生命周期回调使用快速一级编译，避免启动阶段进行高层编译；持续运行的工具进程保持默认配置。
            String command =
                    quoteShell(java.toString())
                            + " -XX:TieredStopAtLevel=1 -jar "
                            + quoteShell(jar.toString())
                            + " "
                            + mode;
            JsonObject handler = Json.object("type", "command", "command", command, "timeout", 3);
            if (event.equals("UserPromptSubmit")) handler.addProperty("additionalContextLimit", 0);
            JsonObject group = Json.object("hooks", List.of(handler));
            // 删除匹配项后若仍有同一桥接命令，说明用户改过定义；不能追加第二份。
            JsonElement currentEvent = document.getAsJsonObject("hooks").get(event);
            for (JsonElement existingGroups :
                    currentEvent == null ? List.<JsonElement>of() : List.of(currentEvent)) {
                if (!existingGroups.isJsonArray()) continue;
                for (JsonElement existingGroup : existingGroups.getAsJsonArray()) {
                    JsonObject existing = existingGroup.getAsJsonObject();
                    if (!existing.has("hooks") || !existing.get("hooks").isJsonArray()) continue;
                    for (JsonElement existingHandler : existing.getAsJsonArray("hooks")) {
                        JsonObject candidate = existingHandler.getAsJsonObject();
                        if (candidate.has("command")
                                && candidate.get("command").isJsonPrimitive()
                                && candidate
                                        .get("command")
                                        .getAsString()
                                        .contains(quoteShell(jar.toString())))
                            throw new IOException("原有桥接 handler 已被用户修改，不能覆盖或重复安装；请先审核该条目");
                    }
                }
            }
            groups.add(
                    Json.object(
                            "event",
                            event,
                            "group",
                            group,
                            "sha256",
                            Json.sha(Json.GSON.toJson(group))));
            if (inlineHooks) {
                inline.append("[[hooks.")
                        .append(event)
                        .append("]]\n[[hooks.")
                        .append(event)
                        .append(".hooks]]\n")
                        .append("type = \"command\"\ncommand = ")
                        .append(Json.GSON.toJson(command))
                        .append("\ntimeout = 3\n");
                if (event.equals("UserPromptSubmit")) inline.append("additionalContextLimit = 0\n");
            } else {
                JsonObject eventObject = document.getAsJsonObject("hooks");
                if (!eventObject.has(event)) eventObject.add(event, new JsonArray());
                if (!eventObject.get(event).isJsonArray())
                    throw new IOException("Hook 事件必须是数组：" + event);
                eventObject.getAsJsonArray(event).add(group);
            }
        }
        // 保留原 TOML 正文，仅增加专属表；已有非本插件同名表不能覆盖。
        parsed = Toml.parse(nextConfig);
        if (includeMcp && parsed.getTable("mcp_servers.idea_companion") != null)
            throw new IOException("已有用户定义的 mcp_servers.idea_companion，不能覆盖");
        StringBuilder block = new StringBuilder(START);
        if (inlineHooks) block.append(inline);
        if (includeMcp)
            block.append("[mcp_servers.idea_companion]\ncommand = ")
                    .append(Json.GSON.toJson(java.toString()))
                    .append("\nargs = ")
                    .append(Json.GSON.toJson(List.of("-jar", jar.toString(), "mcp")))
                    .append(
                            "\nenv_vars = [\"ICB_ENDPOINT_FILE\"]\nstartup_timeout_sec = 10\ntool_timeout_sec = 5\nrequired = false\n");
        block.append(END);
        nextConfig =
                nextConfig
                        + (nextConfig.isEmpty() || nextConfig.endsWith("\n") ? "" : "\n")
                        + block;
        if (Toml.parse(nextConfig).hasErrors())
            throw new IOException("合并后的 TOML 无效；原配置保持不变，请检查是否使用不可扩展的 inline table");
        boolean hadJsonHandlers = old.has("hooks") && !old.getAsJsonArray("hooks").isEmpty();
        String nextHooks =
                inlineHooks && !hadJsonHandlers ? hooks : Json.GSON.toJson(document) + "\n";
        JsonObject newManifest =
                Json.object(
                        "protocol",
                        1,
                        "cliVersion",
                        version.trim(),
                        "hooks",
                        inlineHooks ? new JsonArray() : groups,
                        "tomlBlock",
                        block.toString(),
                        "tomlBlockSha256",
                        Json.sha(block.toString()),
                        "hookSource",
                        inlineHooks ? "TOML" : "JSON",
                        "hooksFileSha256",
                        Json.sha(nextHooks == null ? "" : nextHooks),
                        "configFileSha256",
                        Json.sha(nextConfig));
        String preview =
                "将释放 bridge-client.jar 到 "
                        + jar
                        + "\nHook 来源："
                        + (inlineHooks ? "原 TOML 表" : "用户级 hooks.json")
                        + "\n实际 Codex："
                        + version.trim()
                        + "\n新增或更新事件："
                        + String.join("、", EVENTS)
                        + "\n"
                        + Json.GSON.toJson(groups)
                        + "\n"
                        + block
                        + "\n保留所有其他 Hook、模型、权限、服务器和未知字段；修改前创建私有备份。\n安装后请在原生 CLI 的 /hooks 审核和信任。";
        return new Plan(
                home,
                hooks,
                nextHooks,
                config,
                nextConfig,
                manifest,
                Json.GSON.toJson(newManifest),
                preview,
                version.trim(),
                false);
    }

    /**
     * 准备卸载，只移除当前仍与清单匹配的条目。
     *
     * @param home 用户此前选择的 Codex 配置目录
     */
    public Plan prepareUninstall(Path home) throws IOException {
        String manifest = existing(home.resolve("idea-companion/install-manifest.json"));
        if (manifest == null) throw new IOException("没有安装清单，不能猜测和删除用户配置");
        JsonObject old = Json.parse(manifest);
        String hooks = existing(home.resolve("hooks.json"));
        String config = existing(home.resolve("config.toml"));
        JsonObject document = hooks == null ? new JsonObject() : Json.parse(hooks);
        removeOwned(document, old);
        String source = config == null ? "" : config;
        boolean edited =
                source.contains(START)
                        && old.has("tomlBlock")
                        && !source.contains(old.get("tomlBlock").getAsString());
        String after = removeBlock(source, old, true);
        if (Toml.parse(after).hasErrors()) throw new IOException("卸载后的 TOML 无效，已停止修改");
        return new Plan(
                home,
                hooks,
                hooks == null ? null : Json.GSON.toJson(document) + "\n",
                config,
                after,
                manifest,
                null,
                "仅删除完整匹配安装清单的五项 Hook 和专属 TOML 段；保留其他所有配置、会话和登录状态。\n不会恢复整份备份，不会终止 Codex。"
                        + (edited ? "\n用户修改过的 TOML 段将原样保留，请自行审核剩余配置。" : ""),
                "",
                true);
    }

    /** 执行用户已确认的精确预览，任何并发编辑都停止覆盖。 */
    public void apply(Plan plan, byte[] jar) throws IOException {
        Path directory = plan.home.resolve("idea-companion");
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path lockPath = directory.resolve("install.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("安装锁不能是符号链接");
        try (FileChannel channel =
                        FileChannel.open(
                                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("另一个安装器正在修改配置，请重新预览");
            if (!Objects.equals(existing(plan.home.resolve("hooks.json")), plan.hooksBefore)
                    || !Objects.equals(
                            existing(plan.home.resolve("config.toml")), plan.configBefore)
                    || !Objects.equals(
                            existing(directory.resolve("install-manifest.json")),
                            plan.manifestBefore)) throw new IOException("配置在预览后发生变化，请重新预览并确认");
            Path backups = directory.resolve("backups");
            Files.createDirectories(backups);
            Files.setPosixFilePermissions(backups, PosixFilePermissions.fromString("rwx------"));
            String stamp = Instant.now().toString().replace(':', '-');
            if (plan.hooksBefore != null)
                atomic(
                        backups.resolve(stamp + "-hooks.json"),
                        plan.hooksBefore.getBytes(StandardCharsets.UTF_8));
            if (plan.configBefore != null)
                atomic(
                        backups.resolve(stamp + "-config.toml"),
                        plan.configBefore.getBytes(StandardCharsets.UTF_8));
            if (!plan.uninstall) {
                // 两份配置不能作为一个文件原子替换，先记录自有定义的交付阶段；部分写入后仍能安全重新合并。
                JsonObject staged = Json.parse(plan.manifestAfter);
                JsonObject prior =
                        plan.manifestBefore == null
                                ? new JsonObject()
                                : Json.parse(plan.manifestBefore);
                JsonArray handlers = staged.getAsJsonArray("hooks").deepCopy();
                if (prior.has("hooks"))
                    for (JsonElement handler : prior.getAsJsonArray("hooks"))
                        if (!handlers.contains(handler)) handlers.add(handler);
                JsonArray blocks = new JsonArray();
                if (prior.has("tomlBlocks"))
                    for (JsonElement block : prior.getAsJsonArray("tomlBlocks"))
                        if (!blocks.contains(block)) blocks.add(block);
                if (prior.has("tomlBlock") && !blocks.contains(prior.get("tomlBlock")))
                    blocks.add(prior.get("tomlBlock"));
                if (!blocks.contains(staged.get("tomlBlock"))) blocks.add(staged.get("tomlBlock"));
                staged.add("hooks", handlers);
                staged.add("tomlBlocks", blocks);
                staged.addProperty("installationState", "PENDING");
                atomic(
                        directory.resolve("install-manifest.json"),
                        Json.GSON.toJson(staged).getBytes(StandardCharsets.UTF_8));
                Path release = directory.resolve("v1");
                Files.createDirectories(release);
                Files.setPosixFilePermissions(
                        release, PosixFilePermissions.fromString("rwx------"));
                atomic(release.resolve("bridge-client.jar"), jar);
            }
            if (!Objects.equals(plan.hooksBefore, plan.hooksAfter) && plan.hooksAfter != null)
                atomic(
                        plan.home.resolve("hooks.json"),
                        plan.hooksAfter.getBytes(StandardCharsets.UTF_8));
            // 写第二个文件前再次检查，拒绝不合作的外部修改。
            if (!Objects.equals(existing(plan.home.resolve("config.toml")), plan.configBefore))
                throw new IOException("config.toml 并发变化；Hooks 已完成合并，请重新预览剩余变更");
            atomic(
                    plan.home.resolve("config.toml"),
                    plan.configAfter.getBytes(StandardCharsets.UTF_8));
            if (!plan.uninstall) {
                atomic(
                        directory.resolve("install-manifest.json"),
                        plan.manifestAfter.getBytes(StandardCharsets.UTF_8));
                atomic(
                        directory.resolve("installation-info.json"),
                        Json.GSON
                                .toJson(
                                        Json.object(
                                                "protocol",
                                                1,
                                                "cliVersion",
                                                plan.cliVersion(),
                                                "bridgeJava",
                                                21))
                                .getBytes(StandardCharsets.UTF_8));
                Files.deleteIfExists(directory.resolve("compatibility-lock.json"));
            } else Files.deleteIfExists(directory.resolve("install-manifest.json"));
        } catch (OverlappingFileLockException ex) {
            throw new IOException("安装器锁已被占用，请重新预览");
        }
    }

    /** 仅删除完整匹配的自有 handler 组，用户修改后的条目保留并报告冲突。 */
    private static void removeOwned(JsonObject document, JsonObject manifest) throws IOException {
        if (!manifest.has("hooks") || !document.has("hooks")) return;
        JsonObject hooks = document.getAsJsonObject("hooks");
        for (JsonElement item : manifest.getAsJsonArray("hooks")) {
            JsonObject owned = item.getAsJsonObject();
            String event = owned.get("event").getAsString();
            if (!hooks.has(event)) continue;
            JsonArray groups = hooks.getAsJsonArray(event);
            JsonObject wanted =
                    owned.getAsJsonObject("group").getAsJsonArray("hooks").get(0).getAsJsonObject();
            for (int index = groups.size() - 1; index >= 0; index--) {
                JsonObject group = groups.get(index).getAsJsonObject();
                if (!group.has("hooks")) continue;
                JsonArray handlers = group.getAsJsonArray("hooks");
                for (int handlerIndex = handlers.size() - 1; handlerIndex >= 0; handlerIndex--)
                    if (handlers.get(handlerIndex).equals(wanted)) handlers.remove(handlerIndex);
                if (handlers.isEmpty() && group.size() == 1) groups.remove(index);
            }
            if (groups.isEmpty()) hooks.remove(event);
        }
    }

    /**
     * 仅移除清单中完全相同的专属 TOML 段，编辑后的段要求用户处理。
     *
     * @param source 用户当前 TOML 正文
     */
    private static String removeBlock(String source, JsonObject manifest) throws IOException {
        return removeBlock(source, manifest, false);
    }

    /**
     * 逐段移除清单中完整匹配的定义，包括并发中断时记录的前后版本。
     *
     * @param source 用户当前 TOML 正文
     * @param keepEdited true 表示卸载时保留用户修改段，false 表示升级遇到修改段必须停止审核
     */
    private static String removeBlock(String source, JsonObject manifest, boolean keepEdited)
            throws IOException {
        if (!source.contains(START)) return source;
        if (!manifest.has("tomlBlock")) throw new IOException("存在无归属的插件配置段，请先确认来源");
        Set<String> blocks = new LinkedHashSet<>();
        blocks.add(manifest.get("tomlBlock").getAsString());
        if (manifest.has("tomlBlocks"))
            for (JsonElement block : manifest.getAsJsonArray("tomlBlocks"))
                blocks.add(block.getAsString());
        for (String block : blocks) source = source.replace(block, "");
        if (source.contains(START) && !keepEdited)
            throw new IOException("插件 TOML 段被手动修改，不能静默覆盖或删除");
        return source;
    }

    /** 有界读取安装文件，拒绝符号链接，保留不存在与空文件的区别。 */
    private static String existing(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("配置文件不能是符号链接");
        if (!Files.exists(path)) return null;
        try (InputStream input = Files.newInputStream(path)) {
            return new String(Json.bounded(input, 1048576), StandardCharsets.UTF_8);
        }
    }

    /** 同目录原子替换并校验写入内容，所有安装文件仅当前用户可读写。 */
    private static void atomic(Path path, byte[] content) throws IOException {
        if (Files.isSymbolicLink(path) || Files.isSymbolicLink(path.getParent()))
            throw new IOException("安装路径不能是符号链接");
        Path temporary =
                Files.createTempFile(
                        path.getParent(),
                        ".icb-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temporary, content);
            if (!Arrays.equals(Files.readAllBytes(temporary), content))
                throw new IOException("安装内容校验失败");
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 实际运行指定绝对路径的版本命令，绝不解析用户 Shell 文件。
     *
     * @param option --version 为 Codex 版本，-version 为 Java 运行时版本
     */
    private static String version(Path executable, String option) throws IOException {
        ProcessBuilder builder =
                new ProcessBuilder(executable.toString(), option).redirectErrorStream(true);
        // GUI 启动的 IDEA 可能没有 Homebrew 的 PATH；使用已选可执行文件目录找到同一安装的 Node 运行时。
        builder.environment()
                .compute(
                        "PATH",
                        (key, value) ->
                                executable.getParent()
                                        + File.pathSeparator
                                        + (value == null ? "" : value));
        Process process = builder.start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("版本检查超时");
            }
            String output =
                    new String(
                            Json.bounded(process.getInputStream(), 8192), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IOException("版本检查未成功");
            return output;
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("版本检查被取消");
        }
    }

    /**
     * 为 macOS zsh 生成单个可靠 Shell 参数。
     *
     * @param value Java 或桥接程序的绝对路径
     */
    public static String quoteShell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
