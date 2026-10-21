package com.yestercar;

import com.yestercar.campaigneditor.EditorApp;
import com.yestercar.common.AppBoot;
import processing.core.PApplet;

/**
 * 程序入口 2：**战役编辑器**。
 *
 * <p>与 {@link GameMain} 结构对称，唯一的区别是<strong>不做启动动画</strong>——
 * 做图是高频操作，每次等 2.5 秒动画会很烦。
 *
 * <p>两个入口通常这样到达这里：
 * <ul>
 *   <li>命令行 {@code java -jar target/YesterCar.jar --editor}；</li>
 *   <li>IDEA 里直接运行本类的 {@code main}（建议单独建一个 Run Configuration）。</li>
 * </ul>
 */
public final class EditorMain {

    private EditorMain() {
        // 入口类，不允许实例化
    }

    /**
     * 程序主入口。
     *
     * @param args 命令行参数；支持 {@code --check}
     */
    public static void main(String[] args) {
        if (AppBoot.isCheckMode(args)) {
            System.exit(AppBoot.runSelfCheck("YesterCar 战役编辑器") ? 0 : 1);
        }

        AppBoot.prepare(EditorMain.class, "YesterCar 战役编辑器");
        PApplet.main(EditorApp.class);
    }
}
