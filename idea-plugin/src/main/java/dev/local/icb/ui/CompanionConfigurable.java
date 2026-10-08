// 本机联动设置与配置预览入口，项目信任仍由原生 Codex 处理。
package dev.local.icb.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.options.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.ui.*;

import dev.local.icb.core.*;

import java.awt.*;
import java.io.IOException;
import java.nio.file.*;

import javax.swing.*;

/** 提供一次配置和可选内容偏好，不重复要求项目授权。 */
public final class CompanionConfigurable implements Configurable {
    private JPanel panel;
    private JTextField cli;
    private JTextField home;
    private JCheckBox auto;
    private JCheckBox nearby;
    private JCheckBox unsaved;
    private JCheckBox enabled;
    private JCheckBox mcp;
    private JButton install;
    private JButton uninstall;

    @Override
    public String getDisplayName() {
        return "Codex IDEA Companion";
    }

    @Override
    public JComponent createComponent() {
        panel = new JPanel(new BorderLayout());
        JPanel form = new JPanel(new GridBagLayout());
        enabled = new JCheckBox("启用 Codex 终端联动");
        cli = new JTextField(45);
        home = new JTextField(45);
        JButton detectCli = new JButton("检测本机 Codex 路径");
        detectCli.addActionListener(
                event -> {
                    detectCli.setEnabled(false);
                    ModalityState settingsModality = ModalityState.current();
                    ApplicationManager.getApplication()
                            .executeOnPooledThread(
                                    () -> {
                                        // 仅查找现有可执行文件，不运行 Shell 初始化文件或下载 Codex。
                                        Path detected = LocalCodexPaths.findExecutable();
                                        onSettingsUi(
                                                settingsModality,
                                                () -> {
                                                    detectCli.setEnabled(true);
                                                    if (detected == null)
                                                        Messages.showInfoMessage(
                                                                panel,
                                                                "没有找到已安装的 Codex。请填写其可执行文件绝对路径。",
                                                                "Codex 路径检测");
                                                    else cli.setText(detected.toString());
                                                });
                                    });
                });
        auto = new JCheckBox("自动带入当前文件和选区（提交时发送；终端栏实时显示）");
        nearby = new JCheckBox("没有选区时附加光标附近代码（最多 8192 字节）");
        unsaved = new JCheckBox("允许模型上下文协议工具读取未保存的文档");
        mcp = new JCheckBox("同时配置模型上下文协议的编辑器工具", true);
        install = new JButton("预览安装或更新配置");
        uninstall = new JButton("预览移除插件配置");
        install.addActionListener(event -> preview(false));
        uninstall.addActionListener(event -> preview(true));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(install);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(uninstall);
        String shortcuts =
                System.getProperty("os.name").equals("Mac OS X")
                        ? "Command + Option + K"
                        : "Ctrl + Alt + K";
        JComponent[] rows = {
            new JLabel("使用你已安装的 Codex；项目信任与命令审批由 Codex 处理。"),
            enabled,
            new JLabel("在 IDEA 终端只输入 codex；插件自动选择能继承终端连接信息的原生运行方式。"),
            new JLabel("Codex 可执行文件"),
            cli,
            detectCli,
            new JLabel("Codex 配置目录（与终端 CODEX_HOME 一致）"),
            home,
            auto,
            nearby,
            unsaved,
            mcp,
            buttons,
            new JLabel("选中代码后按 " + shortcuts + " 将引用插入原终端草稿，不自动提交。"),
            new JLabel("首次安装配置后，在 Codex 的 /hooks 审核回调并重新打开终端。")
        };
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(4, 0, 4, 0);
        for (int row = 0; row < rows.length; row++) {
            constraints.gridy = row;
            form.add(rows[row], constraints);
        }
        panel.add(form, BorderLayout.NORTH);
        reset();
        return panel;
    }

    @Override
    public void reset() {
        var state = CompanionSettings.get().getState();
        cli.setText(state.cliPath);
        home.setText(
                state.codexHome.isBlank()
                        ? LocalCodexPaths.configurationHome().toString()
                        : state.codexHome);
        auto.setSelected(state.autoContextEnabled);
        nearby.setSelected(state.includeNearbyCode);
        unsaved.setSelected(state.allowUnsavedMcpRead);
        enabled.setSelected(state.integrationEnabled);
        if (state.cliPath.isBlank()) {
            ApplicationManager.getApplication()
                    .executeOnPooledThread(
                            () -> {
                                // 设置页首次打开时识别已有安装；只更新尚未被用户编辑的空字段。
                                Path detected = LocalCodexPaths.findExecutable();
                                // 此回调只更新路径控件，不修改平台模型；设置页构建过程中也可以执行。
                                onSettingsUi(
                                        ModalityState.any(),
                                        () -> {
                                            if (panel != null
                                                    && cli.getText().isBlank()
                                                    && detected != null)
                                                cli.setText(detected.toString());
                                        });
                            });
        }
    }

    @Override
    public boolean isModified() {
        var state = CompanionSettings.get().getState();
        return !cli.getText().equals(state.cliPath)
                || !home.getText().equals(state.codexHome)
                || auto.isSelected() != state.autoContextEnabled
                || nearby.isSelected() != state.includeNearbyCode
                || unsaved.isSelected() != state.allowUnsavedMcpRead
                || enabled.isSelected() != state.integrationEnabled;
    }

    @Override
    public void apply() throws ConfigurationException {
        try {
            if (!home.getText().isBlank() && !Path.of(home.getText()).isAbsolute())
                throw new ConfigurationException("Codex 配置目录必须是绝对路径");
            if (!cli.getText().isBlank() && !Path.of(cli.getText()).isAbsolute())
                throw new ConfigurationException("Codex 可执行文件必须是绝对路径");
        } catch (InvalidPathException ex) {
            throw new ConfigurationException("Codex 路径格式无效");
        }
        var state = CompanionSettings.get().getState();
        state.cliPath = cli.getText();
        state.codexHome = home.getText();
        state.autoContextEnabled = auto.isSelected();
        state.includeNearbyCode = nearby.isSelected();
        state.allowUnsavedMcpRead = unsaved.isSelected();
        state.integrationEnabled = enabled.isSelected();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            ProjectContextService service = project.getService(ProjectContextService.class);
            // 全局关闭时撤销凭证；启用时直接使用当前项目内容根，不产生项目授权步骤。
            if (!state.integrationEnabled) service.execute(service::revoke);
            else {
                service.start();
                service.execute(service::reloadRoots);
            }
        }
    }

    /**
     * 在后台准备精确预览，批准按钮只作用于已完成且可审阅的变更。
     *
     * @param removing true 表示移除匹配配置，false 表示安装或升级
     */
    private void preview(boolean removing) {
        Path chosenHome, chosenCli;
        try {
            chosenHome = Path.of(home.getText());
            chosenCli = Path.of(cli.getText());
        } catch (RuntimeException ex) {
            Messages.showErrorDialog(panel, "安装路径无效", "Codex 配置");
            return;
        }
        boolean useMcp = mcp.isSelected();
        install.setEnabled(false);
        uninstall.setEnabled(false);
        // 2026-10-06：后台结果属于当前设置对话框，不能等到所有模态窗口关闭后才显示。
        ModalityState settingsModality = ModalityState.current();
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            try {
                                InstallService installer = new InstallService();
                                // 先读取、解析和版本检查，再在界面显示变更；此阶段不改配置。
                                InstallService.Plan plan =
                                        removing
                                                ? installer.prepareUninstall(chosenHome)
                                                : installer.prepare(
                                                        chosenHome,
                                                        chosenCli,
                                                        Path.of(
                                                                System.getProperty("java.home"),
                                                                "bin",
                                                                "java"),
                                                        useMcp);
                                onSettingsUi(
                                        settingsModality,
                                        () -> {
                                            JTextArea text = new JTextArea(plan.preview(), 24, 85);
                                            text.setEditable(false);
                                            text.setLineWrap(true);
                                            DialogWrapper dialog =
                                                    new DialogWrapper(false) {
                                                        {
                                                            setTitle("Codex 配置变更预览");
                                                            setOKButtonText("批准并写入配置");
                                                            init();
                                                        }

                                                        @Override
                                                        protected JComponent createCenterPanel() {
                                                            return new JScrollPane(text);
                                                        }
                                                    };
                                            if (!dialog.showAndGet()) {
                                                install.setEnabled(true);
                                                uninstall.setEnabled(true);
                                                return;
                                            }
                                            try {
                                                apply();
                                            } catch (ConfigurationException ex) {
                                                install.setEnabled(true);
                                                uninstall.setEnabled(true);
                                                Messages.showErrorDialog(
                                                        panel,
                                                        ex.getMessageHtml().toString(),
                                                        "Codex 配置未完成");
                                                return;
                                            }
                                            ApplicationManager.getApplication()
                                                    .executeOnPooledThread(
                                                            () -> {
                                                                try {
                                                                    byte[] jar = new byte[0];
                                                                    if (!removing)
                                                                        try (var input =
                                                                                CompanionConfigurable
                                                                                        .class
                                                                                        .getResourceAsStream(
                                                                                                "/bridge/bridge-client.jar")) {
                                                                            if (input == null)
                                                                                throw new IOException(
                                                                                        "插件包缺少桥接程序");
                                                                            jar =
                                                                                    input
                                                                                            .readAllBytes();
                                                                        }
                                                                    installer.apply(plan, jar);
                                                                    CompanionSettings.get()
                                                                                    .getState()
                                                                                    .observedCliVersion =
                                                                            plan.cliVersion();
                                                                    if (removing) {
                                                                        CompanionSettings.get()
                                                                                        .getState()
                                                                                        .integrationEnabled =
                                                                                false;
                                                                        for (Project project :
                                                                                ProjectManager
                                                                                        .getInstance()
                                                                                        .getOpenProjects()) {
                                                                            project.getService(
                                                                                            ProjectContextService
                                                                                                    .class)
                                                                                    .revoke();
                                                                        }
                                                                        onSettingsUi(
                                                                                settingsModality,
                                                                                () ->
                                                                                        enabled
                                                                                                .setSelected(
                                                                                                        false));
                                                                    }
                                                                    onSettingsUi(
                                                                            settingsModality,
                                                                            () ->
                                                                                    Messages
                                                                                            .showInfoMessage(
                                                                                                    panel,
                                                                                                    removing
                                                                                                            ? "匹配的插件配置已移除；请自行审核 CLI 配置。"
                                                                                                            : "配置已安装。请在 Codex 的 /hooks 审核回调，重新打开终端；不需要再次授权项目。",
                                                                                                    "Codex 配置"));
                                                                } catch (IOException ex) {
                                                                    onSettingsUi(
                                                                            settingsModality,
                                                                            () ->
                                                                                    Messages
                                                                                            .showErrorDialog(
                                                                                                    panel,
                                                                                                    ex
                                                                                                            .getMessage(),
                                                                                                    "Codex 配置未完成"));
                                                                } finally {
                                                                    onSettingsUi(
                                                                            settingsModality,
                                                                            () -> {
                                                                                install.setEnabled(
                                                                                        true);
                                                                                uninstall
                                                                                        .setEnabled(
                                                                                                true);
                                                                            });
                                                                }
                                                            });
                                        });
                            } catch (com.intellij.openapi.progress.ProcessCanceledException ex) {
                                onSettingsUi(
                                        settingsModality,
                                        () -> {
                                            install.setEnabled(true);
                                            uninstall.setEnabled(true);
                                        });
                                throw ex;
                            } catch (IOException | RuntimeException ex) {
                                onSettingsUi(
                                        settingsModality,
                                        () -> {
                                            install.setEnabled(true);
                                            uninstall.setEnabled(true);
                                            Messages.showErrorDialog(
                                                    panel, ex.getMessage(), "Codex 配置预览失败");
                                        });
                            }
                        });
    }

    /** 在设置窗口允许的模态状态下更新界面，关闭设置后丢弃迟到结果，避免弹出脱离原操作的预览。 */
    private void onSettingsUi(ModalityState modality, Runnable task) {
        ApplicationManager.getApplication().invokeLater(task, modality, ignored -> panel == null);
    }

    @Override
    public void disposeUIResources() {
        panel = null;
    }
}
