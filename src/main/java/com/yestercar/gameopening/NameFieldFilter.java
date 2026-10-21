package com.yestercar.gameopening;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;

/**
 * 代号输入框的输入过滤：剔除非法字符 + 12 字上限。
 *
 * <h2>为什么是 {@link DocumentFilter} 而不是 {@code DocumentListener}</h2>
 * 在 {@code DocumentListener} 里调 {@code JTextField.setText()} 回写清洗结果，
 * Swing 会抛 {@code IllegalStateException: Attempt to mutate in notification}，
 * 于是同步被中断 —— 表现为"打了字不显示 / 删除被回滚 / 文本无限增长最后崩"。
 * {@code DocumentFilter} 的改写发生在通知监听器之前，不会撞上那个异常。
 *
 * <h2>⚠️ 只改"被改动的那一段"，绝不整篇重写</h2>
 * 上一版为了做清洗+截断，每次都 {@code fb.replace(0, 全文长度, 新全文, ...)}。
 * 这在**输入法合成期间**是致命的：合成文本是文档里带特殊属性的片段，
 * 整篇替换会把它的属性抹掉，Swing 随后绘制合成文本时抛
 * {@code IllegalArgumentException: Invalid substring range}
 * （栈在 {@code Utilities.drawComposedText}），并且**每帧刷一次**。
 *
 * <p>所以这里只对"这次要插入的那一段"做清洗，长度也只在插入时截断；
 * 删除操作原样透传。文档其余部分（包括合成区）一概不碰。
 */
final class NameFieldFilter extends DocumentFilter {

    @Override
    public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs)
            throws BadLocationException {
        replace(fb, offset, 0, text, attrs);
    }

    @Override
    public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
        // 删除原样透传 —— 不重写文档，合成区才不会被破坏
        fb.remove(offset, length);
    }

    @Override
    public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
            throws BadLocationException {
        Document doc = fb.getDocument();
        String incoming = trimIllegal(text);

        // 只保留"能放得下"的部分。room 按**替换后**的文档长度算：
        // 删掉 length 个字符腾出的空间就该算作可用空间。
        int room = Username.MAX_LENGTH - (doc.getLength() - length);
        if (incoming.length() > room) {
            incoming = room <= 0 ? "" : incoming.substring(0, room);
        }

        if (incoming.isEmpty()) {
            if (length > 0) {
                fb.remove(offset, length);   // 纯删除（或插入被截没了）
            }
            return;                          // 纯新增但没空间：直接忽略
        }
        fb.replace(offset, length, incoming, attrs);
    }

    /**
     * 去掉控制字符与不可见字符，但**保留空格**（空格由 {@link Username#sanitize}
     * 在显示/提交时统一压缩，这里不动它，避免破坏用户正在输入的文本）。
     *
     * @param text 待插入的文本，可为 {@code null}
     * @return 处理后的文本，绝不为 {@code null}
     */
    private static String trimIllegal(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || Username.isInvisibleChar(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
