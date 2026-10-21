package com.yestercar.launcher;

import com.yestercar.gameopening.GameOpeningApp;

/**
 * 旧的主菜单入口 —— 现在只是一个**转发壳**。
 *
 * <h2>⚠️ 这个类只为了不打断已有引用而存在</h2>
 * 它原来是一个独立的 PApplet 主菜单窗口（720×460、G4P 按钮，三个按钮只打印日志），
 * 由 {@code SplashScreen} 用 {@code runSketch} 拉起来。那套结构有三个真问题：
 *
 * <ol>
 *   <li><b>窗口会闪</b> —— 每切一次都重新建窗、抢前台；</li>
 *   <li><b>阶段状态要靠静态变量传</b>，容易漏；</li>
 *   <li><b>"进度条走满之后变成用户名输入"这个设计做不到</b> ——
 *       两个窗口之间没有共同的画布可以让元素变形。</li>
 * </ol>
 *
 * <p>现在整个开场是 {@link GameOpeningApp} <strong>一个窗口三个阶段</strong>。
 * 这里保留同名类只是为了：IDEA 的运行配置、以及任何还在引用
 * {@code LauncherApp} 的地方不会突然编译不过。
 * （它原来那三个模块按钮全部只打印日志 —— 属于"看起来能点、其实没用"，
 * 正好借这次重写一并清掉。）
 *
 * <p><strong>新代码请直接用 {@link GameOpeningApp}。</strong>
 */
public final class LauncherApp {

    private LauncherApp() {
        // 纯转发壳，不允许实例化
    }

    /**
     * 启动游戏开场。
     *
     * @param args 透传给 Processing
     */
    public static void launch(String[] args) {
        processing.core.PApplet.main(GameOpeningApp.class);
    }
}
