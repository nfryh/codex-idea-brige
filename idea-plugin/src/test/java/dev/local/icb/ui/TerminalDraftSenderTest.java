// 使用本机平台真实粘贴构建器检查草稿保留、不提交和接收失败，不调用模型。
package dev.local.icb.ui;

import com.intellij.terminal.frontend.view.TerminalView;
import com.intellij.testFramework.HeavyPlatformTestCase;

import dev.local.icb.terminal262.TerminalDraftSender;

import org.jetbrains.plugins.terminal.view.impl.TerminalSendTextBuilderImpl;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/** 校验终端输入合同，而不是把加入后台队列当作可见发送。 */
public class TerminalDraftSenderTest extends HeavyPlatformTestCase {
    @Override
    protected com.intellij.testFramework.OpenProjectTaskBuilder getOpenProjectOptions() {
        return super.getOpenProjectOptions().runPostStartUpActivities(false);
    }

    /** 原草稿完全保留，括号粘贴和行尾移动已启用，并且没有执行标志。 */
    public void testNativeBuilderPreservesDraftAndNeverExecutes() {
        var draft = new StringBuilder("existing user draft");
        var builder =
                new TerminalSendTextBuilderImpl(
                        options -> {
                            assertFalse(options.getShouldExecute());
                            assertTrue(options.getRequireBracketedPasteMode());
                            assertTrue(options.getUseBracketedPasteMode());
                            assertTrue(options.getSendEndKeyBeforeText());
                            draft.append(options.getText());
                            return true;
                        });
        // 真实平台构建器检查所有发送选项，视图只隔离真实用户终端。
        var view =
                (TerminalView)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {TerminalView.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("createSendTextBuilder"))
                                        return builder;
                                    throw new AssertionError(
                                            "Unexpected terminal operation: " + method.getName());
                                });
        assertTrue(
                TerminalDraftSender.paste(
                        new TerminalDraftSender.Destination(view, null, null), "@gradlew#L5-6"));
        assertEquals("existing user draft @gradlew#L5-6 ", draft.toString());
    }

    /** 平台拒绝括号粘贴时返回失败；控制字符在进入平台接口前被拒绝。 */
    public void testRejectedPasteAndControlCharactersDoNotProduceInput() {
        var attempted = new AtomicBoolean();
        var builder =
                new TerminalSendTextBuilderImpl(
                        options -> {
                            attempted.set(true);
                            return false;
                        });
        var view =
                (TerminalView)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {TerminalView.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("createSendTextBuilder"))
                                        return builder;
                                    throw new AssertionError(
                                            "Unexpected terminal operation: " + method.getName());
                                });
        var destination = new TerminalDraftSender.Destination(view, null, null);
        assertFalse(TerminalDraftSender.paste(destination, "@file.txt"));
        assertTrue(attempted.get());
        attempted.set(false);
        assertThrows(
                IllegalArgumentException.class,
                () -> TerminalDraftSender.paste(destination, "@file.txt\ncommand"));
        assertFalse(attempted.get());
    }

    /** 在真实窗口内容组件上添加提示栏，释放后恢复原组件与已有内容。 */
    public void testContextBarRestoresOriginalTerminalComponentOnDispose() {
        var original = new javax.swing.JPanel();
        var content =
                com.intellij.ui.content.ContentFactory.getInstance()
                        .createContent(original, "fixture terminal", false);
        var manager =
                com.intellij.ui.content.ContentFactory.getInstance()
                        .createContentManager(true, getProject());
        manager.addContent(content);
        var lifetime = com.intellij.openapi.util.Disposer.newDisposable("fixture bar");
        var view =
                (TerminalView)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {TerminalView.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError(
                                            "Context bar must not call internal terminal API: "
                                                    + method.getName());
                                });
        try {
            var label = new javax.swing.JLabel("Codex：gradlew · 已选中 2 行");
            new TerminalDraftSender.Destination(view, null, content).addBar(label, lifetime);
            assertNotSame(original, content.getComponent());
            assertSame(content.getComponent(), original.getParent());
            com.intellij.openapi.util.Disposer.dispose(lifetime);
            assertSame(original, content.getComponent());
        } finally {
            com.intellij.openapi.util.Disposer.dispose(manager);
        }
    }
}
