package com.yestercar.gameopening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 右侧菜单竖向切分的几何契约。
 *
 * <h2>这条契约改过三次，三次的理由都留着 —— 否则下一个人会以为是随意改的</h2>
 * <ol>
 *   <li><b>ak-ui 原文</b>：{@code resource 15% / operation 28% / 1fr}。
 *       当时的 bug 是漏掉 operation 轨道、命令区吃掉 85%，命令区高 1.49 倍。</li>
 *   <li><b>TASKS-ROUND 3.4</b>：资源条已删除，那 15% 成了空带，要求并入牌区
 *       → {@code 28% / 72%}。</li>
 *   <li><b>TASKS-ROUND-2 §2（当前）</b>：牌区恢复<b>行结构</b>，
 *       且「作战」卡成为牌区<b>第 1 行</b> —— 它不再是独立轨道。
 *       于是菜单里只剩一块牌区，占满整个菜单高。</li>
 * </ol>
 *
 * <p>⚠️ 所以本测试现在断言的是"**只有一块，且占满**"。
 * 它防的仍然是同一类错误：<b>某块内容把别人的高度吃了、或者自己消失了</b>。
 * 只不过现在"别人的高度"已经不存在 —— 再出现 28/72 反而说明有人把旧结构改回来了。
 */
class MainMenuLayoutTest {

    /** 真实值：810 × 71.111%（MENU_H_PCT）。 */
    private static final float MENU_H = 810f * 71.111f / 100f;

    @Test
    void 牌区占满整个菜单高() {
        float[] t = MainMenu.trackHeights(100f);
        assertEquals(1, t.length, "现在只剩一块牌区，不该再切成几条轨");
        assertEquals(100f, t[0], 1e-4f, "牌区必须等于菜单高");
    }

    @Test
    void 牌区等于菜单高() {
        float[] t = MainMenu.trackHeights(MENU_H);
        assertEquals(MENU_H, t[0], 1e-3f);
    }

    @Test
    void 不得退回三轨结构() {
        // 如果哪天又冒出 resource/operation 两条空轨，这里会立刻炸
        float[] t = MainMenu.trackHeights(MENU_H);
        assertEquals(1, t.length,
                "trackHeights 返回了 " + t.length + " 条轨 —— 有人把资源条/作战轨加回来了？");
    }

    @Test
    void 牌区高度大于零且不超过菜单() {
        for (float h : new float[]{100f, 300f, MENU_H, 900f}) {
            float[] t = MainMenu.trackHeights(h);
            assertTrue(t[0] > 0f, h + " 时牌区高必须为正");
            assertTrue(t[0] <= h + 1e-3f, h + " 时牌区不该超过菜单高");
        }
    }
}
