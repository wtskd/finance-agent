package com.jiang.financeagent.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 控制台输入读取器：自动识别 UTF-8 / GBK。
 *
 * ============================================================
 * 为什么不能直接用 Scanner(System.in, StandardCharsets.UTF_8)
 * ============================================================
 *   Windows 终端的输入编码并不可靠：
 *     - 控制台代码页是 936(GBK) 时，输入送过来的是 GBK 字节
 *     - chcp 65001 或某些终端下，送过来的是 UTF-8 字节
 *
 *   实测症状：在 Git Bash 里输入「上个月支出多少」，程序按 UTF-8 硬解，
 *   得到一串乱码交给模型，模型只能回答「我注意到您的消息似乎出现了乱码」。
 *
 *   ★ 注意：输出侧没问题是因为 dev.sh 给 Java 加了 -Dsun.stdout.encoding=UTF-8，
 *     但输入侧没有任何东西替你兜底 —— 所以必须自己在代码里处理。
 *
 * ============================================================
 * 解决思路：严格解码 + 回退（fail-then-fallback）
 * ============================================================
 *   1. 先按 UTF-8「严格模式」解码 —— 遇到非法字节立即抛错，而不是悄悄替换成 ?
 *   2. 抛错说明它根本不是 UTF-8，那就按 GBK 解码
 *   3. 纯 ASCII 输入两种都能解对，不受影响
 *
 *   为什么不猜终端类型？因为猜不准，而且代码不该依赖用户怎么配置终端。
 *   让解码器自己说话，比我们写一堆 if 判断可靠得多。
 */
public final class ConsoleInput {

    private static final Charset GBK = Charset.forName("GBK");

    /** 设 FINANCE_DEBUG=1 时才打印编码诊断信息 */
    private static final boolean DEBUG = "1".equals(System.getenv("FINANCE_DEBUG"));


    private final InputStream in = System.in;

    /** 只提示一次，避免刷屏 */
    private boolean encodingReported = false;

    /**
     * 读一行。
     *
     * @return 读到的内容；返回 null 表示输入流已结束（Ctrl+D 或管道结束）
     */
    public String readLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        boolean gotAny = false;

        while ((b = in.read()) != -1) {
            gotAny = true;
            if (b == '\n') {
                break;
            }
            if (b == '\r') {
                continue;   // 忽略 Windows 的 \r，避免行尾多一个空字符
            }
            buffer.write(b);
        }

        if (!gotAny) {
            return null;
        }
        return decode(buffer.toByteArray());
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            // 控制台为 936 时，输入本来就是 GBK，属正常情况，不打扰用户。
            // 需要排查时用：FINANCE_DEBUG=1 bash dev.sh run Step4Main
            if (DEBUG && !encodingReported) {
                encodingReported = true;
                System.out.println("（诊断：输入非 UTF-8，已按 GBK 解码。原始字节："
                        + toHex(bytes) + "）");
            }
            return new String(bytes, GBK);
        }

    }

    /** 把字节转成十六进制，用于诊断编码问题 */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

}
