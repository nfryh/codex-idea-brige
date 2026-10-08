// 在平台模态状态中触发真实设置按钮，验证后台结果不会等到关闭设置后才出现。
package dev.local.icb.ui;

import com.intellij.openapi.application.*;
import com.intellij.openapi.application.impl.LaterInvocator;
import com.intellij.openapi.ui.*;
import com.intellij.testFramework.*;

import dev.local.icb.core.CompanionSettings;

import java.awt.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.*;

/** 使用独立配置目录和测试版本程序，不读取或修改真实 Codex 配置。 */
public class ConfigurableModalityTest extends HeavyPlatformTestCase {
    /** 当前测试不运行其他商业插件的项目启动活动。 */
    @Override
    protected OpenProjectTaskBuilder getOpenProjectOptions() {
        return super.getOpenProjectOptions().runPostStartUpActivities(false);
    }

    /** 在设置仍处于模态状态时，真实按钮的后台错误必须显示，并重新启用按钮。 */
    public void testPreviewResultIsDisplayedBeforeSettingsModalCloses() throws Exception {
        var original = CompanionSettings.get().getState();
        var preferences = new CompanionSettings.Preferences();
        Path root = Path.of(getProject().getBasePath());
        Files.createDirectories(root);
        Path cli = root.resolve("fake-codex");
        Files.writeString(cli, "#!/bin/sh\nprintf 'codex-cli 0.159.2\\n'\n");
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwx------"));
        Path home = root.resolve("test-home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("hooks.json"), "{invalid}");
        preferences.cliPath = cli.toString();
        preferences.codexHome = home.toString();
        CompanionSettings.get().loadState(preferences);
        var configurable = new CompanionConfigurable();
        var component = configurable.createComponent();
        JButton preview = button(component, "预览安装或更新配置");
        AtomicBoolean displayed = new AtomicBoolean(), blocked = new AtomicBoolean();
        TestDialog previous =
                TestDialogManager.setTestDialog(
                        message -> {
                            displayed.set(true);
                            return 0;
                        });
        Object settingsWindow = new Object();
        LaterInvocator.enterModal(settingsWindow);
        try {
            // 先验证没有窗口模态信息的后台回调确实被阻塞，确保用例覆盖截图暴露的根因。
            ApplicationManager.getApplication()
                    .executeOnPooledThread(
                            () ->
                                    ApplicationManager.getApplication()
                                            .invokeLater(() -> blocked.set(true)))
                    .get();
            preview.doClick();
            assertFalse(preview.isEnabled());
            PlatformTestUtil.waitWithEventsDispatching("设置窗口内的预览错误没有显示", displayed::get, 8);
            assertTrue(displayed.get());
            assertFalse(blocked.get());
            assertTrue(preview.isEnabled());
            assertEquals("{invalid}", Files.readString(home.resolve("hooks.json")));
            assertFalse(Files.exists(home.resolve("config.toml")));
        } finally {
            configurable.disposeUIResources();
            LaterInvocator.leaveModal(settingsWindow);
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            TestDialogManager.setTestDialog(previous);
            CompanionSettings.get().loadState(original);
        }
    }

    /** 设置已经关闭时，迟到回调必须被丢弃，不能又弹出预览或错误。 */
    public void testDisposedSettingsDiscardsPendingResult() throws Exception {
        var configurable = new CompanionConfigurable();
        configurable.createComponent();
        AtomicBoolean displayed = new AtomicBoolean();
        var method =
                CompanionConfigurable.class.getDeclaredMethod(
                        "onSettingsUi", ModalityState.class, Runnable.class);
        method.setAccessible(true);
        Object settingsWindow = new Object();
        LaterInvocator.enterModal(settingsWindow);
        try {
            ModalityState captured = ModalityState.current();
            ApplicationManager.getApplication()
                    .executeOnPooledThread(
                            () -> {
                                try {
                                    method.invoke(
                                            configurable,
                                            captured,
                                            (Runnable) () -> displayed.set(true));
                                } catch (ReflectiveOperationException ex) {
                                    throw new AssertionError(ex);
                                }
                            })
                    .get();
            configurable.disposeUIResources();
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            assertFalse(displayed.get());
        } finally {
            LaterInvocator.leaveModal(settingsWindow);
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        }
    }

    /**
     * 从真实设置组件查找用户可点击的按钮。
     *
     * @param label 设置页按钮的完整中文文本
     */
    private JButton button(Component component, String label) {
        if (component instanceof JButton candidate && candidate.getText().equals(label))
            return candidate;
        if (component instanceof Container parent)
            for (Component child : parent.getComponents()) {
                JButton found = button(child, label);
                if (found != null) return found;
            }
        return null;
    }
}
