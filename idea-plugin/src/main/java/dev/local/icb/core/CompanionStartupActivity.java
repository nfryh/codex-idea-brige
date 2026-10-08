// 本地项目直接启动后台联动；项目信任和命令审批留在原生 Codex。
package dev.local.icb.core;

import com.intellij.notification.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;

/** 启用项目服务并说明一次性安装和传输范围。 */
public final class CompanionStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(Project project) {
        ProjectContextService service = project.getService(ProjectContextService.class);
        service.start();
        if (CompanionSettings.get().getState().codexHome.isBlank())
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("ICB")
                    .createNotification(
                            "Codex 终端联动",
                            "选中代码后可用快捷键加入 Codex 上下文；首次使用需配置提交回调。项目信任和命令审批在原生 Codex 中处理。",
                            NotificationType.INFORMATION)
                    .addAction(
                            NotificationAction.createSimple(
                                    "配置联动",
                                    () ->
                                            ShowSettingsUtil.getInstance()
                                                    .showSettingsDialog(
                                                            project, "Codex IDEA Companion")))
                    .notify(project);
    }
}
