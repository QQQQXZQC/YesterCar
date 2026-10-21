package com.yestercar;

import com.yestercar.common.AppBoot;
import com.yestercar.gameopening.GameOpeningApp;
import processing.core.PApplet;

/**
 * 程序入口 1：**游戏本体**。
 *
 * <p>按策划方案 §10.1.4，本类不承担任何业务逻辑，只做路由：
 * <ol>
 *   <li>带 {@code --editor} → 转发给 {@link EditorMain}（战役编辑器）；</li>
 *   <li>带 {@code --check} → 交给 {@link AppBoot#runSelfCheck(String)} 做无窗口自检；</li>
 *   <li>带 {@code --play <战役名>} → <strong>跳过启动动画与主菜单</strong>，直接开战；</li>
 *   <li>否则 → 游戏开场 {@link GameOpeningApp}（加载动画 → 输入代号 → 主菜单，同一个窗口三个阶段）。</li>
 * </ol>
 *
 * <p>「打印环境」「建目录」「自检」三件事都搬到了 {@link AppBoot}，
 * 因为 {@link EditorMain} 也需要，留在这里就会有两份。
 *
 * <h2>运行方式</h2>
 * <pre>
 * IDEA      : 直接运行本类的 main 方法
 * 命令行    : mvn exec:java
 * 打包后    : java -jar target/YesterCar.jar                      （游戏本体）
 *             java -jar target/YesterCar.jar --editor             （战役编辑器）
 *             java -jar target/YesterCar.jar --play _debug        （直接开战，调试用）
 *             java -jar target/YesterCar.jar --check              （环境自检）
 * </pre>
 */
public final class GameMain {

    private GameMain() {
        // 入口类，不允许实例化
    }

    /**
     * 程序主入口。
     *
     * @param args 命令行参数，见类注释
     */
    public static void main(String[] args) {
        // ① 必须最先判断：一旦要求打开编辑器，就不该再走启动动画与环境准备。
        //    转发时剥掉 --editor（否则下游会再转发一次），但保留 --check 等其它参数。
        if (AppBoot.isEditorMode(args)) {
            EditorMain.main(AppBoot.stripFlags(args, AppBoot.EDITOR_FLAG));
            return;
        }

        // ② 自检模式：跑完即退，不开窗口
        if (AppBoot.isCheckMode(args)) {
            System.exit(AppBoot.runSelfCheck("YesterCar 游戏本体") ? 0 : 1);
        }

        String campaign = AppBoot.playCampaignName(args);

        AppBoot.prepare(GameMain.class, "YesterCar");

        // ③ 调试直开：跳过启动动画与主菜单，直接进战斗
        if (campaign != null) {
            System.out.println("[GameMain] --play 模式，直接加载战役: " + campaign);
            System.out.println("[GameMain] 注意：自定义战役界面与编队界面尚未实现，"
                    + "当前直接进战斗。");
            // 第 18 轮：战斗改在主窗口内，不再另起一个 PApplet 窗口
            GameOpeningApp.skipToBattle(campaign);
            PApplet.main(GameOpeningApp.class);
            return;
        }

        // ③.5 调试直开主菜单：跳过加载与代号（--menu）
        if (AppBoot.hasFlag(args, "--menu")) {
            System.out.println("[GameMain] --menu 模式，直接进主菜单");
            GameOpeningApp.skipToMenu();
        }

        // ③.6 自动截图：--shot <输出文件>（配合 --menu）
        String shotOut = AppBoot.valueAfter(args, "--shot");
        if (shotOut != null) {
            GameOpeningApp.autoShot(shotOut, 2600);
            GameOpeningApp.autoShotPhase(AppBoot.valueAfter(args, "--shot-phase"));
            System.out.println("[GameMain] 将在主菜单就位后截图到 " + shotOut);
        }

        // ④ 正常启动：游戏开场（加载 → 代号 → 主菜单，单窗口三阶段）
        PApplet.main(GameOpeningApp.class);
    }}
