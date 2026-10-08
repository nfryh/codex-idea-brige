<!-- Codex Terminal Companion 的项目介绍、安装和使用入口。 -->
# Codex Terminal Companion

在 IntelliJ IDEA（JetBrains 的集成开发环境）终端中使用 Codex，把当前文件和代码选区加入对话。

选择代码，按下快捷键，文件与行号引用就会出现在 Codex 草稿中。插件保留已有输入，并在你提交问题时附加发送时的代码快照，包括未保存的选区内容。

[安装](#安装) · [使用](#使用) · [常见问题](#常见问题) · [开发](#开发) · [许可证](#许可证)

## 功能

- **文件与选区跟随**：IDEA 终端栏显示当前文件和选中的行数。
- **快捷发送引用**：发送选区或当前文件，保留草稿内容，由你决定何时提交。
- **代码快照**：发送选区时保存当时的文本，之后继续编辑不会改变这次引用的内容。
- **多个终端**：多个 Codex 终端同时运行时，可以选择引用的接收目标。
- **编辑器工具**：可选启用项目文档读取、现有诊断、文件定位和已记录文件的只读差异查看。

当前为开发版本 `1.0.0-dev.6`，支持 **macOS 本地终端**。

## 安装

### 环境要求

|环境|要求|
|---|---|
|操作系统|macOS|
|IntelliJ IDEA|2026.2 起，最低 IntelliJ 平台构建系列 `262`；后续版本需核对兼容情况|
|Codex|本机已安装的原生命令行程序，需要支持 Hooks（生命周期回调，用于在提交问题等事件发生时传递上下文）|

使用本机已有的 Codex，不要求固定版本。远程开发、其他操作系统和其他 JetBrains 产品尚未验证。

### 安装插件

1. 从 [版本发布页](https://github.com/niefang0910/codex-idea-brige/releases) 获取插件压缩包；没有发布附件时，按下方 [源码构建](#源码构建) 生成。
2. 在 IDEA 中打开 **设置 → 插件 → 从磁盘安装插件**，选择压缩包；仅在 IDEA 提示时重启。
3. 打开 **设置 → 工具 → Codex IDEA Companion**，确认 Codex 可执行文件和配置目录。
4. 点击 **预览安装或更新配置**，检查变更后批准。首次增加回调时，在 Codex 的 `/hooks` 中审核并信任。
5. 打开新的 IDEA 终端，输入 `codex`。

插件自动准备终端联动环境。登录、项目信任和命令审批由 Codex 处理，配置文件只在预览并批准后修改。

## 使用

1. 在 IDEA 终端运行 `codex`，输入问题草稿。
2. 回到编辑器，选中需要讨论的代码。
3. 按 **Command + Option + K**，引用会加入已有的 Codex 草稿。
4. 检查问题与引用，再自行提交。

例如，选中 `src/main/java/UserService.java` 的第 10 至 18 行，草稿中会出现：

```text
请解释这段代码的作用 @src/main/java/UserService.java#L10-18
```

插件添加引用时不发送回车。删除草稿中的完整引用标记后，对应选区快照不会随本次提交发送。

### 默认快捷键

|动作|macOS 默认快捷键|
|---|---|
|发送选区引用|Command + Option + K|
|发送当前文件引用|Command + Option + Shift + K|
|查看引用队列|Command + Option + Q|

也可以通过编辑器右键菜单发送引用。自定义快捷键时，在 **设置 → 键位映射** 中搜索动作名称。

多终端选择、引用管理、权限和卸载流程见 [完整使用说明](docs/USAGE.md)。

## 常见问题

**文件或选区提示没有显示**

提示位于 IDEA 终端栏。确认联动已启用，并打开新终端；通过 **Codex 联动诊断** 查看实际连接情况。

**快捷键没有添加引用**

确认目标终端正在运行 Codex，且当前没有执行任务。多个 Codex 终端存在时先选择目标；快捷键冲突可在键位映射中调整。

**未保存内容与敏感文件**

可以显式发送未保存的选区。自动附加上下文和工具读取未保存文档分别受设置控制。敏感文件默认禁止发送，显式引用时需要单次确认，文件访问范围仍限于当前项目。

## 开发

### 源码构建

构建使用 Java 25 和本机已安装的 IDEA。Gradle Wrapper 是按项目指定版本启动 Gradle 构建工具的程序，仓库已提供；无需另行安装 Gradle。

```sh
export IDEA_HOME='/absolute/path/to/IntelliJ IDEA.app'
export JAVA_HOME="$IDEA_HOME/Contents/jbr/Contents/Home"
./gradlew :idea-plugin:prepareDistribution
```

将 `IDEA_HOME` 替换为实际 IDEA 安装目录。`JAVA_HOME` 指向 Java 25 运行时；上面的路径使用 IDEA 随附的运行时。也可以用 `-PideaHome` 指定 IDEA 目录，构建会复用该安装。

生成的安装包为 `dist/codex-terminal-companion-1.0.0-dev.6.zip`。相邻 `.sha256` 文件保存 SHA-256（安全散列算法生成的文件摘要），用于核对下载或复制后的安装包。

### 测试与贡献

修改前阅读 [项目开发规则](AGENTS.md)。源码使用 Java，构建脚本使用 Kotlin。完整测试还需要本机已有 Codex、`zsh` 命令解释程序和 `/usr/bin/python3`。

```sh
./gradlew test :idea-plugin:verifyPlugin
scripts/format-java.sh --check
```

`verifyPlugin` 是插件验证器兼容检查。格式脚本使用固定版本的 `google-java-format`（Google 提供的 Java 源码格式工具）；需要 Bash 命令解释程序、Git 版本控制工具、curl 下载工具和 shasum 文件摘要工具。使用 `--write` 替代 `--check` 可格式化源码。依赖变更同步更新锁文件及第三方许可声明。

三个源码模块分别是：`idea-plugin` 负责 IDEA 集成，`bridge-client` 负责独立桥接进程，`bridge-contract` 负责共享消息与安全规则。桥接通过 Hooks 和 Model Context Protocol（模型上下文协议，用于让 Codex 调用受限编辑器工具）传递上下文，详细参数见 [协议说明](docs/PROTOCOL.md)。

## 验证范围

<details>
<summary>查看已执行检查与尚未完成的验收</summary>

2026-10-08 在本机 IntelliJ IDEA 2026.2.1（构建号 `262.9437.185`）、Java 25、Codex 0.161.0 和 Gradle 9.1.0 上验证：116 项自动化测试通过，失败、错误、跳过均为 0；插件验证器确认与该平台二进制兼容。

终端适配仍使用实验性和少量内部应用程序接口，后续平台可能变化。兼容报告包含 41 项实验性接口、2 项弃用接口和 2 项内部接口使用；原始报告生成于 `idea-plugin/build/reports/pluginVerifier/`。

受控原生终端测试使用临时配置和本地模拟模型服务，没有向外部模型发送请求。真实安装后的两个终端引擎发送路径、实际模型接收未保存内容、跨项目及跨实例隔离、恢复会话和升级卸载仍需验收。

</details>

## 许可证

项目使用 [MIT License](LICENSE)，允许使用、修改和分发，要求保留版权及许可声明。第三方依赖遵循各自的许可证，见 [第三方声明](docs/THIRD_PARTY_NOTICES.txt)。
