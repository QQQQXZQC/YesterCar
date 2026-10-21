package com.yestercar.gameopening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import org.junit.jupiter.api.Test;

/**
 * 代号输入框的行为契约。
 *
 * <p>这三个测试对应的正是用户报的三个 bug：
 * <ol>
 *   <li>中文打不进去；</li>
 *   <li>打了字但画布仍显示默认「博士」；</li>
 *   <li>删除的字又回来，文本无限增长直到崩。</li>
 * </ol>
 *
 * <p>JTextField 与 DocumentFilter 不依赖窗口显示，可以无头运行 ——
 * 所以这些行为**能被自动化验证**，不用靠"看着好像好了"。
 */
class NameFieldTest {

    /** 造一个和正式代码完全同构的输入框。 */
    private static JTextField newField() throws Exception {
        final JTextField[] holder = new JTextField[1];
        SwingUtilities.invokeAndWait(() -> {
            JTextField f = new JTextField();
            ((AbstractDocument) f.getDocument()).setDocumentFilter(new NameFieldFilter());
            holder[0] = f;
        });
        return holder[0];
    }

    private static void setText(JTextField f, String text) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                f.getDocument().remove(0, f.getDocument().getLength());
                if (!text.isEmpty()) {
                    f.getDocument().insertString(0, text, null);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void 英文能进入输入框() throws Exception {
        JTextField f = newField();
        setText(f, "abc");
        // 这就是 bug 2 的核心：文本必须真的进到 Document，
        // 否则画布读到的永远是空串 → 显示默认「博士」
        assertEquals("abc", f.getText());
    }

    @Test
    void 中文能进入输入框() throws Exception {
        JTextField f = newField();
        // 模拟输入法确认选词：一次性插入"博士"两个字
        setText(f, "博士");
        assertEquals("博士", f.getText());
    }

    @Test
    void 中英文混排() throws Exception {
        JTextField f = newField();
        setText(f, "Dr博士01");
        assertEquals("Dr博士01", f.getText());
    }

    @Test
    void 删除真的删除() throws Exception {
        JTextField f = newField();
        setText(f, "abcd");
        assertEquals("abcd", f.getText());

        SwingUtilities.invokeAndWait(() -> {
            try {
                f.getDocument().remove(3, 1);   // 等价于 Backspace
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // bug 3：上一版删除会被回滚，这里必须真的少一个字符
        assertEquals("abc", f.getText());
    }

    @Test
    void 字数上限对中文生效() throws Exception {
        JTextField f = newField();
        setText(f, "一二三四五六七八九十甲乙丙丁");
        assertEquals(Username.MAX_LENGTH, f.getText().length(),
                "中文也必须被截到 " + Username.MAX_LENGTH + " 字");
    }

    @Test
    void 字数上限对英文生效() throws Exception {
        JTextField f = newField();
        setText(f, "abcdefghijklmnopqrst");
        assertEquals(Username.MAX_LENGTH, f.getText().length());
    }

    @Test
    void 超长输入不会抛出异常() throws Exception {
        JTextField f = newField();
        // bug 3 的崩溃点：连续超长输入不能把程序搞断
        assertTrue(canRun(() -> setText(f, "abcdefghijklmnopqrstuvwxyz")));
        assertEquals(Username.MAX_LENGTH, f.getText().length());
    }

    @Test
    void 控制字符与不可见字符被清洗掉() throws Exception {
        JTextField f = newField();
        setText(f, "a\u0000b\u200Bc");
        assertEquals("abc", f.getText());
    }

    @Test
    void 在中间插入不破坏前后文() throws Exception {
        JTextField f = newField();
        setText(f, "abcd");
        SwingUtilities.invokeAndWait(() -> {
            try {
                f.getDocument().insertString(2, "XY", null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // 整篇重写的老实现会把文档重建，前后文容易错位；
        // 现在只改被改动的那一段，必须原样保留。
        assertEquals("abXYcd", f.getText());
    }

    @Test
    void 先删再插腾出的空间可用() throws Exception {
        JTextField f = newField();
        setText(f, "abcdefghijkl");          // 正好 12
        assertEquals(Username.MAX_LENGTH, f.getText().length());
        SwingUtilities.invokeAndWait(() -> {
            try {
                f.getDocument().remove(10, 2);   // 删掉 2 个
                f.getDocument().insertString(10, "中文", null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(Username.MAX_LENGTH, f.getText().length());
        assertTrue(f.getText().endsWith("中文"));
    }

    @Test
    void 删除后再输入_删掉的字不能复活() throws Exception {
        JTextField f = newField();
        setText(f, "abcd");
        SwingUtilities.invokeAndWait(() -> {
            try {
                f.getDocument().remove(3, 1);            // Backspace：删掉 'd'
                f.getDocument().insertString(3, "X", null);   // 再输入 'X'
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // 用户报的正是这个：删了之后一输入，删掉的字又回来。
        // 原因是删除只改了镜像、没改输入框；下次追加时用"旧全文 + 新字符"覆盖。
        assertEquals("abcX", f.getText());
    }

    @Test
    void 连续删除再输入不复活() throws Exception {
        JTextField f = newField();
        setText(f, "测试员");
        SwingUtilities.invokeAndWait(() -> {
            try {
                f.getDocument().remove(2, 1);   // 删「员」
                f.getDocument().remove(1, 1);   // 删「试」
                f.getDocument().insertString(1, "新", null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals("测新", f.getText());
    }

    private interface Thrower {
        void run() throws Exception;
    }

    private static boolean canRun(Thrower t) {
        try {
            t.run();
            return true;
        } catch (InvocationTargetException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
