package com.jiang.financeagent;

import com.jiang.financeagent.web.AgentServer;

/**
 * 第 10 步入口：启动 Web 服务，用浏览器和 Agent 对话。
 *
 * 用法：
 *   bash dev.sh run Step10Main          用默认端口 8000
 *   bash dev.sh run Step10Main 9000     指定端口
 *
 * 【为什么进程不会自己退出】
 *   HttpServer.start() 之后就返回了，main 方法随即结束。
 *   但服务仍然在跑 —— 因为它的线程池里是**非守护线程**，
 *   JVM 会一直等到所有非守护线程结束才退出。
 *   所以这里的 main 不需要写 `while(true)` 来"挂住"进程。
 */
public class Step10Main {

    private static final int DEFAULT_PORT = 8000;

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("未读到 DEEPSEEK_API_KEY，请确认环境变量已配置并重启终端");
            return;
        }

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException e) {
                System.out.println("端口参数不是数字：" + args[0] + "，改用默认端口 " + DEFAULT_PORT);
            }
        }

        System.out.println("================ 财务问数 Agent · Web 版 ================");
        new AgentServer(apiKey).start(port);
    }
}
