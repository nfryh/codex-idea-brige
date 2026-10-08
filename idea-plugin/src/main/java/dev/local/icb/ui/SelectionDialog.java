// 不依赖过时的选择对话框接口，统一呈现会话和文件选择。
package dev.local.icb.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;

import java.awt.BorderLayout;

import javax.swing.*;

/** 只显示用户需要选择的数据，不猜测其目标。 */
public final class SelectionDialog {
    private SelectionDialog() {}

    /**
     * 返回明确选择的选项，取消时返回 -1。
     *
     * @param message 本次选择的用途
     * @param title 对话框标题
     */
    public static int choose(Project project, String message, String title, String[] options) {
        JComboBox<String> choices = new JComboBox<>(options);
        DialogWrapper dialog =
                new DialogWrapper(project) {
                    {
                        setTitle(title);
                        init();
                    }

                    @Override
                    protected JComponent createCenterPanel() {
                        JPanel panel = new JPanel(new BorderLayout(0, 12));
                        panel.add(new JLabel(message), BorderLayout.NORTH);
                        panel.add(choices, BorderLayout.CENTER);
                        return panel;
                    }
                };
        return dialog.showAndGet() ? choices.getSelectedIndex() : -1;
    }
}
