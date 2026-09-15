package com.yestercar.splash;

import com.yestercar.common.util.UITheme;
import com.yestercar.launcher.LauncherApp;
import processing.core.PApplet;
import processing.core.PConstants;

/**
 * 启动动画窗口。
 *
 * <p>职责：显示约 2.5 秒的标题与进度条，然后切换到 {@link LauncherApp}。
 * 按任意键或点击可跳过。
 *
 * <p>本类只做渲染与计时，不含业务逻辑。
 */
public class SplashScreen extends PApplet {

    /** 动画总时长（毫秒）。 */
    private static final int DURATION_MS = 2500;

    /** 窗口尺寸。 */
    private static final int WINDOW_W = 640;
    private static final int WINDOW_H = 400;

    private int startTime;
    private boolean launched;

    @Override
    public void settings() {
        size(WINDOW_W, WINDOW_H, JAVA2D);
        // 必须在 settings() 内：在 setup() 里调用会抛 IllegalStateException
        UITheme.disablePixelDensityScaling(this);
    }

    @Override
    public void setup() {
        UITheme.apply(this);
        surface.setTitle("YesterCar — 启动中");
        startTime = millis();
        launched = false;
    }

    @Override
    public void draw() {
        UITheme.background(this);

        int elapsed = millis() - startTime;
        float progress = constrain(elapsed / (float) DURATION_MS, 0f, 1f);

        drawTitle();
        drawProgressBar(progress);
        drawHint();

        if (progress >= 1f) {
            launchMainWindow();
        }
    }

    private void drawTitle() {
        textAlign(PConstants.CENTER, PConstants.CENTER);

        fill(UITheme.COLOR_ACCENT);
        textSize(UITheme.TITLE_SIZE + 10);
        text("YesterCar", width / 2f, height / 2f - 46);

        fill(UITheme.COLOR_TEXT);
        textSize(UITheme.FONT_SIZE);
        text("明日方舟式关卡设计器与基础战斗", width / 2f, height / 2f - 6);

        fill(UITheme.COLOR_TEXT_DIM);
        textSize(UITheme.FONT_SIZE - 2);
        text("课程设计原型 · Maven + Processing 4 + G4P", width / 2f, height / 2f + 22);
    }

    private void drawProgressBar(float progress) {
        float barW = width * 0.62f;
        float barH = 8;
        float barX = (width - barW) / 2f;
        float barY = height / 2f + 70;

        noStroke();
        fill(0xFF3A3A3A);
        rect(barX, barY, barW, barH, 4);

        fill(UITheme.COLOR_ACCENT);
        rect(barX, barY, barW * progress, barH, 4);
    }

    private void drawHint() {
        textAlign(PConstants.CENTER, PConstants.BOTTOM);
        fill(UITheme.COLOR_TEXT_DIM);
        textSize(UITheme.FONT_SIZE - 3);
        text("按任意键或点击可跳过", width / 2f, height - 18f);
    }

    /** 切换到主窗口；重复调用只会生效一次。 */
    private void launchMainWindow() {
        if (launched) {
            return;
        }
        launched = true;

        // runSketch 会为 LauncherApp 新建一个独立的 PApplet 窗口，并返回一个新的动画线程。
        // 启动窗随后隐藏（而不是销毁），以免两个窗口争抢前台。
        //
        // 注意：这一步曾经"看起来"没生效，实际原因是 UITheme 在 setup() 里调用了
        // pixelDensity()，抛出的 IllegalStateException 打断了主窗口的渲染循环，
        // 使主窗口根本没有机会显示。修掉那个 bug 之后本调用是安全的。
        PApplet.runSketch(new String[]{"YesterCar"}, new LauncherApp());
        surface.setVisible(false);
    }

    @Override
    public void keyPressed() {
        launchMainWindow();
    }

    @Override
    public void mousePressed() {
        launchMainWindow();
    }
}
