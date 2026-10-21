package com.yestercar;

import com.yestercar.common.ui.DesignSpace;
import com.yestercar.gameopening.Username;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游戏开场：设计坐标系换算 + 玩家代号清洗。
 *
 * <h2>为什么这两件事要一起测</h2>
 * 它们都是"看起来很简单、错了却很难看出来"的那类东西：
 * 坐标换算错了只是版面歪一点，代号清洗错了只是偶尔多一个怪字符 ——
 * 都不会报错，只会让人觉得"哪里不对"。
 */
@DisplayName("开场界面：坐标换算与代号清洗")
class GameOpeningTest {

    // ------------------------------------------------------------------
    // DesignSpace
    // ------------------------------------------------------------------

    @Test
    @DisplayName("窗口就是设计基准时，缩放因子为 1、坐标原样")
    void 基准尺寸下恒等() {
        DesignSpace d = new DesignSpace((int) DesignSpace.BASE_W,
                (int) DesignSpace.BASE_H);

        assertEquals(1f, d.sx(), 1e-4f);
        assertEquals(1f, d.sy(), 1e-4f);
        assertEquals(100f, d.x(100f), 1e-3f);
        assertEquals(200f, d.y(200f), 1e-3f);
        assertEquals(50f, d.w(50f), 1e-3f);
        assertEquals(50f, d.h(50f), 1e-3f);
    }

    @Test
    @DisplayName("★ 决定性回归：16:9 窗口下两轴因子必须相等")
    void 实际窗口两轴相等() {
        // 决定书 FIX-ROUND-1 §1 / D14：设计基准改成 16:9（1440×810）。
        // 旧版这里断言"两轴必须不同"—— 而那正是文字被非等比拉伸的根因。
        // 这条测试现在锁的是**修复**，不是缺陷。
        for (int[] wh : new int[][]{{1600, 900}, {1280, 720}, {2560, 1440}}) {
            DesignSpace d = new DesignSpace(wh[0], wh[1]);
            assertEquals(d.sx(), d.sy(), 1e-5f,
                    wh[0] + "x" + wh[1] + " 下 sx 必须等于 sy");
            // 统一因子 = 窗口宽 / 基准宽
            assertEquals(wh[0] / DesignSpace.BASE_W, d.sx(), 1e-4f);
        }
    }

    @Test
    @DisplayName("字号换算：16:9 下与两轴一致")
    void 字号换算() {
        DesignSpace d = new DesignSpace(1600, 900);
        assertEquals(14f * d.sx(), d.font(14f), 1e-3f);
        assertEquals(d.font(14f), 14f * d.sy(), 1e-3f,
                "16:9 下字号与纵向因子也应一致");
    }

    // ------------------------------------------------------------------
    // Username
    // ------------------------------------------------------------------

    @Test
    @DisplayName("普通名字原样通过")
    void 普通名字通过() {
        assertEquals("博士", Username.sanitize("博士"));
        assertEquals("Doctor", Username.sanitize("Doctor"));
        assertEquals("A1_中英混排", Username.sanitize("A1_中英混排"));
    }

    @Test
    @DisplayName("★ 首尾空白被去掉（否则「看起来一样」的两个名字不相等）")
    void 去首尾空白() {
        assertEquals("博士", Username.sanitize("  博士  "));
        assertEquals("博士", Username.sanitize("\t博士\n"));
    }

    @Test
    @DisplayName("★ 零宽字符与双向控制字符被丢掉")
    void 丢弃不可见字符() {
        // 零宽空格、BOM、软连字符 —— 屏幕上完全看不见，却让字符串不相等
        assertEquals("博士", Username.sanitize("博\u200B士"));
        assertEquals("博士", Username.sanitize("\uFEFF博士"));
        assertEquals("ab", Username.sanitize("a\u00ADb"));
        // 双向控制字符会让显示顺序与存储顺序不一致，也要丢
        assertEquals("ab", Username.sanitize("\u202Aab\u202C"));
    }

    @Test
    @DisplayName("★ 连续空白压成一个（中文输入法很容易敲出全角空格）")
    void 空白压缩() {
        assertEquals("博 士", Username.sanitize("博   士"));
        assertEquals("博 士", Username.sanitize("博\u3000\u3000士"));
        assertEquals("a b", Username.sanitize("a\t\t b"));
    }

    @Test
    @DisplayName("★ 超长名字被截到上限")
    void 超长被截断() {
        String long12 = "一二三四五六七八九十十一";
        assertEquals(Username.MAX_LENGTH, Username.sanitize(long12).length());

        String tooLong = long12 + "十二十三";
        String out = Username.sanitize(tooLong);
        assertEquals(Username.MAX_LENGTH, out.length());
        assertTrue(long12.startsWith(out), "截断应当从头保留");
    }

    @Test
    @DisplayName("★ 截断后再 trim —— 不然会留下一个尾随空格")
    void 截断后不留尾随空格() {
        // 12 个字符，第 12 个是空格 → 截断后应当把那个空格也去掉
        String s = "一二三四五六七八九十十 ";
        String out = Username.sanitize(s);
        assertEquals(out, out.strip(), "结果不该有首尾空白: 「" + out + "」");
    }

    @Test
    @DisplayName("全是不可见字符时，清洗结果为空（要能区分「没填」）")
    void 全不可见时为空() {
        assertEquals("", Username.sanitize("\u200B\uFEFF\u202A"));
        assertEquals("", Username.sanitize("   "));
        assertEquals("", Username.sanitize(null));
    }

    @Test
    @DisplayName("★ 空输入退回默认代号")
    void 空输入用默认() {
        assertEquals(Username.DEFAULT_NAME, Username.sanitizeOrDefault(""));
        assertEquals(Username.DEFAULT_NAME, Username.sanitizeOrDefault("   "));
        assertEquals(Username.DEFAULT_NAME, Username.sanitizeOrDefault(null));
        assertEquals(Username.DEFAULT_NAME, Username.display("\u200B"));
        // 有内容就不该被换掉
        assertEquals("博士", Username.sanitizeOrDefault("博士"));
    }

    @Test
    @DisplayName("提示文字里带着实际上限，不写死数字")
    void 提示文字与常量一致() {
        assertTrue(Username.limitHint().contains(String.valueOf(Username.MAX_LENGTH)),
                "提示应当说清上限，实际: " + Username.limitHint());
    }

    @Test
    @DisplayName("清洗是幂等的（再洗一次不变）")
    void 清洗幂等() {
        for (String s : new String[]{"  博\u200B士  ", "a\t\tb", "一二三四五六七八九十十一十二"}) {
            String once = Username.sanitize(s);
            assertEquals(once, Username.sanitize(once),
                    "二次清洗不该改变结果: 「" + once + "」");
        }
    }
}
