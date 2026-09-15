package com.yestercar.leveleditor;

import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;

/**
 * 关卡编辑器窗口（窗口 3）。
 *
 * <p><strong>占位骨架</strong>：只画标题，证明「一个模块窗口能被独立打开」。
 * 真实职责是读取地图作背景、以表单编辑波次与规则，产出 {@code level_xxx.json}——
 * 见策划方案 §6.2 与 §10.3。
 *
 * <p>待接入的类（策划方案 §10.3.3）：{@code controller.LevelEditorController}、
 * {@code model.LevelData} / {@code Wave} / {@code Spawn} / {@code EnemyCatalog}、
 * {@code view.MapPreviewRenderer} / {@code FormPanel} / {@code WaveListPanel}、
 * {@code io.LevelJsonIO}。
 */
public class LevelEditorApp extends PApplet {

    private static final int WINDOW_W = 900;
    private static final int WINDOW_H = 700;

    @Override
    public void settings() {
        size(WINDOW_W, WINDOW_H, JAVA2D);
        // 必须在 settings() 内：在 setup() 里调用会抛 IllegalStateException
        UITheme.disablePixelDensityScaling(this);
    }

    @Override
    public void setup() {
        UITheme.apply(this);
        surface.setTitle("YesterCar — 关卡编辑器");
    }

    @Override
    public void draw() {
        UITheme.background(this);

        textAlign(PConstants.CENTER, PConstants.CENTER);
        fill(UITheme.COLOR_ACCENT);
        textSize(UITheme.TITLE_SIZE);
        text("关卡编辑器", width / 2f, height / 2f - 16);

        fill(UITheme.COLOR_TEXT_DIM);
        textSize(UITheme.FONT_SIZE);
        text("占位窗口 · 关卡参数表单 / 波次列表 待实现", width / 2f, height / 2f + 22);
    }
}
