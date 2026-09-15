package com.yestercar.mapeditor;

import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;

/**
 * 地图编辑器窗口（窗口 2）。
 *
 * <p><strong>占位骨架</strong>：只画标题，证明「一个模块窗口能被独立打开」。
 * 真实职责是编辑静态地形与红蓝门，产出 {@code map_xxx.json}——见策划方案 §6.1 与 §10.2。
 *
 * <p>待接入的类（策划方案 §10.2.3）：{@code controller.MapEditorController}、
 * {@code model.TileMap} / {@code Door} / {@code EditorState}、
 * {@code view.GridRenderer} / {@code ToolbarView} / {@code StatusBarView}、
 * {@code io.MapJsonIO}、{@code command.PlaceTileCommand} / {@code EraseTileCommand}。
 */
public class MapEditorApp extends PApplet {

    private static final int WINDOW_W = 900;
    private static final int WINDOW_H = 620;

    @Override
    public void settings() {
        size(WINDOW_W, WINDOW_H, JAVA2D);
        // 必须在 settings() 内：在 setup() 里调用会抛 IllegalStateException
        UITheme.disablePixelDensityScaling(this);
    }

    @Override
    public void setup() {
        UITheme.apply(this);
        surface.setTitle("YesterCar — 地图编辑器");
    }

    @Override
    public void draw() {
        UITheme.background(this);

        textAlign(PConstants.CENTER, PConstants.CENTER);
        fill(UITheme.COLOR_ACCENT);
        textSize(UITheme.TITLE_SIZE);
        text("地图编辑器", width / 2f, height / 2f - 16);

        fill(UITheme.COLOR_TEXT_DIM);
        textSize(UITheme.FONT_SIZE);
        text("占位窗口 · 网格 / 地形工具栏 / 撤销栈 待实现", width / 2f, height / 2f + 22);
    }
}
