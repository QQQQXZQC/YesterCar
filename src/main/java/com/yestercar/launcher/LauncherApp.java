package com.yestercar.launcher;

import com.yestercar.common.io.Workspace;
import com.yestercar.common.util.UITheme;
import g4p_controls.GButton;
import g4p_controls.GEvent;
import g4p_controls.GLabel;
import processing.core.PApplet;
import processing.core.PConstants;

/**
 * 主窗口：主菜单、选关、编队、干员选择的宿主。
 *
 * <p>本类目前是<strong>占位骨架</strong>：三个模块按钮只打印日志，尚未打开子窗口。
 * 窗口机制（{@code PApplet.runSketch} 与 G4P {@code GWindow}）的对比实验见
 * {@code docs/第一阶段规划.md} 第九节，结论确定后再回填这里。
 *
 * <p>按策划方案 §10.1.4，本类只保留入口方法并把事件转发给控制器；
 * 后续应拆出 {@code launcher.controller.SceneRouter} 与
 * {@code launcher.view.MainMenuView}。
 */
public class LauncherApp extends PApplet {

    private static final int WINDOW_W = 720;
    private static final int WINDOW_H = 460;

    private static final int BTN_X = 60;
    private static final int BTN_W = 260;
    private static final int BTN_H = 42;
    private static final int BTN_GAP = 14;
    private static final int BTN_Y0 = 140;

    private static final String CMD_MAP_EDITOR = "mapEditor";
    private static final String CMD_LEVEL_EDITOR = "levelEditor";
    private static final String CMD_GAME_RUNTIME = "gameRuntime";
    private static final String CMD_QUIT = "quit";

    private GLabel statusLabel;

    @Override
    public void settings() {
        size(WINDOW_W, WINDOW_H, JAVA2D);
        // 必须在 settings() 内：在 setup() 里调用会抛 IllegalStateException
        UITheme.disablePixelDensityScaling(this);
    }

    @Override
    public void setup() {
        // 必须在创建任何 G4P 控件之前应用主题：字体设置只对之后创建的控件生效
        UITheme.apply(this);
        surface.setTitle("YesterCar — 主菜单");

        createModuleButton(0, CMD_MAP_EDITOR, "地图编辑器");
        createModuleButton(1, CMD_LEVEL_EDITOR, "关卡编辑器");
        createModuleButton(2, CMD_GAME_RUNTIME, "战斗运行时");
        createModuleButton(3, CMD_QUIT, "退出");

        statusLabel = UITheme.label(this, BTN_X, height - 54f, width - BTN_X * 2f, 24,
                "就绪。三个模块窗口尚未接入。");
    }

    private void createModuleButton(int index, String command, String text) {
        int y = BTN_Y0 + index * (BTN_H + BTN_GAP);
        GButton button = new GButton(this, BTN_X, y, BTN_W, BTN_H, text);
        // G4P 的 GButton 没有 setActionCommand/getActionCommand，用 GAbstractControl
        // 暴露的公开字段 tag 承载命令字，避免在主窗口里堆积一串控件属性。
        button.tag = command;
        // 注意：不需要调用 addEventHandler —— GButton 的构造函数已自动在所属窗口上
        // 绑定名为 handleButtonEvents 的默认回调（方法名与签名均固定，见 G4P 文档）。
    }

    @Override
    public void draw() {
        UITheme.background(this);

        // 标题
        textAlign(PConstants.LEFT, PConstants.BASELINE);
        fill(UITheme.COLOR_ACCENT);
        textSize(UITheme.TITLE_SIZE);
        text("YesterCar", BTN_X, 62);

        fill(UITheme.COLOR_TEXT_DIM);
        textSize(UITheme.FONT_SIZE);
        text("明日方舟式关卡设计器与基础战斗", BTN_X, 90);

        // 右侧环境信息：用于确认路径与主题在 IDEA / 打包后两种运行方式下都正确
        drawEnvironmentPanel();
    }

    private void drawEnvironmentPanel() {
        float x = BTN_X + BTN_W + 50;
        float y = BTN_Y0;
        float lineHeight = 20;

        textAlign(PConstants.LEFT, PConstants.BASELINE);
        fill(UITheme.COLOR_TEXT);
        textSize(UITheme.FONT_SIZE);
        text("运行环境", x, y);

        fill(UITheme.COLOR_TEXT_DIM);
        textSize(UITheme.FONT_SIZE - 2);
        text("Java  " + System.getProperty("java.version"), x, y + lineHeight);
        text("工程根  " + Workspace.projectRoot(), x, y + lineHeight * 2);
        text("地图目录  " + Workspace.mapsDir(), x, y + lineHeight * 3);
        text("关卡目录  " + Workspace.levelsDir(), x, y + lineHeight * 4);
        text("主题  " + UITheme.FONT_FAMILY + " / scheme " + UITheme.DEFAULT_SCHEME,
                x, y + lineHeight * 5);
    }

    /**
     * G4P 按钮事件的默认回调。
     *
     * <p><strong>方法名与签名是 G4P 约定的，不能改</strong>：{@code GButton} 的构造函数
     * 会在所属窗口上查找名为 {@code handleButtonEvents}、参数为
     * {@code (GButton, GEvent)} 的公开方法并自动绑定。名字写错时 G4P 只在控制台打印
     * 一条容易被忽略的提示，按钮不会有任何反应。
     *
     * @param button 触发事件的按钮
     * @param event  事件类型
     */
    @SuppressWarnings("unused")
    public void handleButtonEvents(GButton button, GEvent event) {
        if (event != GEvent.CLICKED) {
            return;
        }
        String command = button.tag;
        if (command == null) {
            return;
        }
        switch (command) {
            case CMD_MAP_EDITOR -> notImplemented("地图编辑器");
            case CMD_LEVEL_EDITOR -> notImplemented("关卡编辑器");
            case CMD_GAME_RUNTIME -> notImplemented("战斗运行时");
            case CMD_QUIT -> exit();
            default -> notImplemented(command);
        }
    }

    private void notImplemented(String moduleName) {
        String message = moduleName + "：窗口尚未接入（骨架阶段）。";
        System.out.println("[LauncherApp] " + message);
        if (statusLabel != null) {
            statusLabel.setText(message);
        }
    }

    @Override
    public void keyPressed() {
        if (key == ESC) {
            key = 0; // 拦截 ESC：Processing 默认用 ESC 直接退进程，容易误关
        }
    }
}
