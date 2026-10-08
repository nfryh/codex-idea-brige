// 仅本机保存联动偏好与安装位置，不重复保存 Codex 的项目授权。
package dev.local.icb.core;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;

/** 联动开关和安装位置；项目信任与执行审批由原生 Codex 负责。 */
@State(
        name = "CodexIdeaCompanion",
        storages = @Storage(value = "codex-idea-companion.xml", roamingType = RoamingType.DISABLED))
@Service(Service.Level.APP)
public final class CompanionSettings
        implements PersistentStateComponent<CompanionSettings.Preferences> {
    /** 不包含快照、提示词和终端秘密的本地状态。 */
    public static final class Preferences {
        /** true 表示为打开的本地项目启用联动，false 表示关闭联动；不承担项目信任审批。 */
        public volatile boolean integrationEnabled = true;

        /** true 表示自动附加当前编辑器，false 表示仅发送显式引用。 */
        public volatile boolean autoContextEnabled = true;

        /** true 表示无选区时附加有界附近文本，false 表示只发光标元信息。 */
        public volatile boolean includeNearbyCode;

        /** true 表示允许工具读取未保存文档，false 表示工具仅读磁盘版。 */
        public volatile boolean allowUnsavedMcpRead;

        /** 用户选择的 Codex 命令行绝对路径。 */
        public volatile String cliPath = "";

        /** 用户选择的 Codex 配置目录，默认由设置页填入。 */
        public volatile String codexHome = "";

        /** 安装器实际观察到的版本，不代表当前终端版本。 */
        public volatile String observedCliVersion = "未检测";
    }

    private volatile Preferences preferences = new Preferences();

    public static CompanionSettings get() {
        return ApplicationManager.getApplication().getService(CompanionSettings.class);
    }

    @Override
    public Preferences getState() {
        return preferences;
    }

    @Override
    public void loadState(Preferences state) {
        preferences = state;
    }
}
