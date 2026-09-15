package com.yestercar.runtime;

import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;

/**
 * 战斗运行时窗口（窗口 4）。
 *
 * <p><strong>占位骨架</strong>：只画标题，证明「一个模块窗口能被独立打开」。
 * 真实职责是带权 Dijkstra 动态寻路、障碍放置、干员部署与战斗结算——
 * 见策划方案 §5 与 §10.4。这是四个模块里最大的一块。
 *
 * <p>待接入的类（策划方案 §10.4.7）：
 * <ul>
 *   <li>{@code core}：{@code GameController} / {@code GameState} / {@code GameConfigLoader}</li>
 *   <li>{@code model}：{@code Enemy} / {@code Operator} / {@code Obstacle} / {@code Unit}</li>
 *   <li>{@code pathfinding}：{@code FlowField} / {@code DijkstraBuilder} /
 *       {@code PathfindingService} / {@code PathRecomputeService}</li>
 *   <li>{@code system}：{@code WaveManager} / {@code SpawnManager} / {@code CostSystem} /
 *       {@code DeploymentSystem} / {@code ObstacleSystem} / {@code BlockSystem} /
 *       {@code CombatSystem} / {@code TargetSelector} / {@code StarRatingSystem}</li>
 *   <li>{@code view}：{@code BattleRenderer} / {@code HUDRenderer} / {@code DeployPanel}</li>
 * </ul>
 */
public class GameRuntimeApp extends PApplet {

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
        surface.setTitle("YesterCar — 战斗运行时");
    }

    @Override
    public void draw() {
        UITheme.background(this);

        textAlign(PConstants.CENTER, PConstants.CENTER);
        fill(UITheme.COLOR_ACCENT);
        textSize(UITheme.TITLE_SIZE);
        text("战斗运行时", width / 2f, height / 2f - 16);

        fill(UITheme.COLOR_TEXT_DIM);
        textSize(UITheme.FONT_SIZE);
        text("占位窗口 · 流场寻路 / 战斗 / 部署 待实现", width / 2f, height / 2f + 22);
    }
}
