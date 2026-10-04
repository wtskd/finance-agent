package com.jiang.financeagent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** 临时诊断程序：打印终端输入的真实字节。用完可以删掉本文件。 */
public class HexDumpMain {

    public static void main(String[] args) throws Exception {
        System.out.println("请输入一行中文（例如：上个月支出多少），然后回车：");
        System.out.flush();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = System.in.read()) != -1 && b != '\n') {
            if (b != '\r') {
                buffer.write(b);
            }
        }

        byte[] bytes = buffer.toByteArray();
        StringBuilder hex = new StringBuilder();
        for (byte x : bytes) {
            hex.append(String.format("%02X ", x));
        }

        System.out.println();
        System.out.println("字节数   : " + bytes.length);
        System.out.println("十六进制 : " + hex.toString().trim());
        System.out.println("按 UTF-8 : " + new String(bytes, StandardCharsets.UTF_8));
        System.out.println("按 GBK   : " + new String(bytes, Charset.forName("GBK")));
    }
}
