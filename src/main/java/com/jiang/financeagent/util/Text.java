package com.jiang.financeagent.util;

import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 控制台文本对齐工具。
 * 中文在等宽字体里占 2 个字符宽度，直接按 String.length() 补空格会错位，
 * 所以这里按「显示宽度」计算。
 */
public final class Text {

    /** 复用同一个编码器（单线程使用，无需担心线程安全） */
    private static final CharsetEncoder GBK_ENCODER =
            java.nio.charset.Charset.forName("GBK").newEncoder();

    private Text() {
    }

    /**
     * 把控制台显示不了的字符处理掉。
     *
     * 【为什么需要这一步】
     *   控制台代码页是 936(GBK)，而 GBK 覆盖不了全部 Unicode。实测：
     *     半角日元符号 ¥(U+00A5) → 编码不了 → 输出成 '?'
     *     emoji（如 😊）         → 编码不了 → 输出成 '?'
     *   在财务项目里，金额符号变成问号非常难看。
     *
     * 【为什么在渲染层改，而不是在提示词里要求模型别用 ¥】
     *   在提示词里让模型迁就自己的编码，是在错误的层解决问题 ——
     *   模型换一个符号就又坏了，而且这条约束和业务毫无关系。
     *   正确的做法是：模型自由输出，渲染层负责适配显示能力。
     *
     *   全角 ￥(U+FFE5) 在 GBK 里有编码（a3 a4），所以做一次等价替换即可。
     */
    public static String forConsole(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\u00A5') {
                sb.append('\uFFE5');            // 半角 ¥ → 全角 ￥
            } else if (GBK_ENCODER.canEncode(c)) {
                sb.append(c);
            }
            // 其余无法编码的字符（emoji 等）直接丢弃，好过显示一排问号
        }
        return sb.toString();
    }

    /** 按显示宽度右侧补空格（中文算 2 宽） */
    public static String padRight(String s, int width) {
        int displayWidth = width(s);
        StringBuilder sb = new StringBuilder(s);
        for (int i = displayWidth; i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** 按显示宽度截断，超出部分用 … 表示 */
    public static String truncate(String s, int maxWidth) {
        if (width(s) <= maxWidth) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (char c : s.toCharArray()) {
            int w = (c > 0x2E80) ? 2 : 1;
            if (used + w > maxWidth - 1) {
                break;
            }
            sb.append(c);
            used += w;
        }
        return sb.append('…').toString();
    }

    /** 计算显示宽度 */
    public static int width(String s) {
        int width = 0;
        for (char c : s.toCharArray()) {
            width += (c > 0x2E80) ? 2 : 1;
        }
        return width;
    }
}
