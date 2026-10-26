package com.yestercar;

import com.yestercar.common.ui.DesignSpace;
import com.yestercar.gameopening.Easing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ak-ui 移植层的契约：缓动曲线与设计坐标。
 *
 * <h2>为什么要测缓动</h2>
 * ak-ui 的动效全部指定了贝塞尔曲线，而<strong>曲线数值是设计决定</strong>：
 * {@code .22,1,.36,1} 与 {@code .16,1,.3,1} 属于"强出场"——
 * 前段冲得快、后段稳稳落位，读起来是机械感而不是弹跳感。
 * 用线性插值代替，动效会"软"下来，那正是"不像 ak-ui"的一个主要来源。
 *
 * <p>所以这里锁住两件事：曲线在端点处必须精确（0 进 0 出 1），
 * 以及"强出场"的特征必须成立 —— 前四分之一就走完一半以上。
 */
@DisplayName("ak-ui 移植层：缓动与设计坐标")
class AkUiPortTest {

    // ------------------------------------------------------------------
    // Easing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("所有曲线在端点上精确：f(0)=0，f(1)=1")
    void 端点精确() {
        for (Easing e : new Easing[]{Easing.STANDARD, Easing.EMPHASIZED,
                Easing.COMMAND_MOVE, Easing.TERMINAL, Easing.LINEAR}) {
            assertEquals(0f, e.at(0f), 1e-4f, "f(0) 必须是 0");
            assertEquals(1f, e.at(1f), 1e-4f, "f(1) 必须是 1");
        }
    }

    @Test
    @DisplayName("超出范围会被夹住，不会外推")
    void 范围被夹() {
        assertEquals(0f, Easing.STANDARD.at(-5f), 1e-4f);
        assertEquals(1f, Easing.STANDARD.at(5f), 1e-4f);
    }

    @Test
    @DisplayName("单调递增：进度不会倒退")
    void 单调递增() {
        for (Easing e : new Easing[]{Easing.STANDARD, Easing.EMPHASIZED,
                Easing.COMMAND_MOVE, Easing.TERMINAL}) {
            float prev = -1f;
            for (int i = 0; i <= 50; i++) {
                float v = e.at(i / 50f);
                assertTrue(v >= prev - 1e-4f,
                        "曲线在 " + (i / 50f) + " 处倒退了: " + prev + " → " + v);
                prev = v;
            }
        }
    }

    @Test
    @DisplayName("★ 「强出场」曲线前半段就冲过大半 —— 这是 ak-ui 的手感来源")
    void 强出场特征() {
        // .ak-command 的 transition 与 .ak-terminal-mark 都用这类曲线
        for (Easing e : new Easing[]{Easing.COMMAND_MOVE, Easing.TERMINAL}) {
            assertTrue(e.at(0.25f) > 0.5f,
                    "四分之一处应当已走过一半以上，实际 " + e.at(0.25f));
            assertTrue(e.at(0.5f) > 0.8f,
                    "一半处应当接近到位，实际 " + e.at(0.5f));
        }
    }

    @Test
    @DisplayName("LINEAR 就是恒等")
    void 线性是恒等() {
        for (int i = 0; i <= 10; i++) {
            float x = i / 10f;
            assertEquals(x, Easing.LINEAR.at(x), 1e-3f);
        }
    }

    @Test
    @DisplayName("progress 重载：延迟未到时不启动")
    void 延迟语义正确() {
        assertEquals(0f, Easing.LINEAR.progress(50f, 100f, 200f), 1e-4f,
                "还没到延迟就该是 0");
        assertEquals(0f, Easing.LINEAR.progress(100f, 100f, 200f), 1e-4f,
                "正好在延迟点也是 0");
        assertEquals(0.5f, Easing.LINEAR.progress(200f, 100f, 200f), 1e-3f,
                "延迟 100 + 走 100/200 = 0.5");
        assertEquals(1f, Easing.LINEAR.progress(400f, 100f, 200f), 1e-4f);
        // ⚠️ 时长为 0：**已经把延迟走完**才算立刻完成（否则除以 0）
        assertEquals(1f, Easing.LINEAR.progress(100f, 100f, 0f), 1e-4f,
                "延迟走完 + 时长 0 = 立刻完成");
        // 但"这一帧还没起步"是另一回事，用 progressFromStart
        assertEquals(0f, Easing.LINEAR.progressFromStart(0f, 0f, 0f), 1e-4f,
                "还没起步就应当是 0");
        assertEquals(0f, Easing.LINEAR.progressFromStart(0f, 0f, 200f), 1e-4f);
        assertEquals(1f, Easing.LINEAR.progressFromStart(300f, 100f, 200f), 1e-4f);
    }

    // ------------------------------------------------------------------
    // 设计坐标（ak-ui 的 cqw/cqh）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("★ 三条竖轨 15% / 28% / 57% 加起来铺满容器")
    void 三条轨道铺满() {
        DesignSpace d = new DesignSpace(1280, 720);
        float sum = d.cqw(15f) + d.cqw(28f) + d.cqw(57f);
        assertEquals(1280f, sum, 0.5f,
                "ak-ui 的 --ak-dashboard-menu-*-track 三条轨道必须正好铺满");
    }

    @Test
    @DisplayName("★ 16:9 窗口下两轴因子相等（L2 模糊的根因已修）")
    void 两轴因子相等() {
        // 决定书 FIX-ROUND-1 §1：设计基准从 1366×653（2.09:1）改成 1440×810（16:9），
        // 于是任何 16:9 窗口下 sx == sy，只剩统一缩放。
        //
        // ⚠️ 这条断言的方向与旧版**相反**，是有意的：
        // 旧测试断言"两轴必须不同"，而那正是文字被非等比拉伸、
        // 既显小又发虚的根因。测试锁住旧缺陷，就得改测试，不是改代码。
        for (int[] wh : new int[][]{{1600, 900}, {1280, 720}, {1920, 1080}, {2560, 1440}}) {
            DesignSpace d = new DesignSpace(wh[0], wh[1]);
            assertEquals(d.sx(), d.sy(), 1e-5f,
                    "16:9 窗口 " + wh[0] + "x" + wh[1] + " 下两轴因子必须相等：sx="
                            + d.sx() + " sy=" + d.sy());
        }
        // 基准本身必须是 16:9
        assertEquals(16f / 9f, DesignSpace.BASE_W / DesignSpace.BASE_H, 1e-4f,
                "设计基准必须是 16:9");
    }

    @Test
    @DisplayName("★ 非 16:9 窗口下两轴**仍然相等**（等比缩放 + 居中留边）")
    void 非16比9时两轴相等() {
        // 改成等比缩放后：4:3 窗口会左右留边，但文字不会被拉伸。
        DesignSpace d = new DesignSpace(1000, 900);
        assertEquals(d.sx(), d.sy(), 1e-6f,
                "两轴必须相等：sx=" + d.sx() + " sy=" + d.sy());
        float min = Math.min(d.sx(), d.sy());
        assertEquals(10f * min, d.len(10f), 1e-4f, "len() 必须取较小因子");
    }

    @Test
    @DisplayName("len() 取较小因子，保证方形元素不越界")
    void 方形元素不越界() {
        DesignSpace d = new DesignSpace(1280, 720);
        float min = Math.min(d.sx(), d.sy());
        assertEquals(10f * min, d.len(10f), 1e-4f);
        assertTrue(d.len(10f) <= d.w(10f) + 1e-4f);
        assertTrue(d.len(10f) <= d.h(10f) + 1e-4f);
    }

    @Test
    @DisplayName("cqw / cqh 与设计基准的百分比一致")
    void 百分比换算正确() {
        DesignSpace d = new DesignSpace(1366, 768);   // 16:9
        // 基准尺寸下 1cqw = 13.66px，1cqh = 7.68px
        assertEquals(13.66f, d.cqw(1f), 0.01f);
        assertEquals(7.68f, d.cqh(1f), 0.01f);
        assertEquals(1366f, d.cqw(100f), 0.5f);
        assertEquals(768f, d.cqh(100f), 0.5f);
    }

    @Test
    @DisplayName("16:9 下字号换算与两轴一致")
    void 字号换算() {
        DesignSpace d = new DesignSpace(1600, 900);
        assertEquals(14f * d.sx(), d.font(14f), 1e-4f);
        // 16:9 时两轴相等，所以跟谁都一样 —— 这正是修完之后期望的性质
        assertEquals(d.font(14f), 14f * d.sy(), 1e-4f,
                "16:9 下字号不该与纵向因子有差");
    }
}
