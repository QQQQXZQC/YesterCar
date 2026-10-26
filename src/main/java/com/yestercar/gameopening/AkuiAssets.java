package com.yestercar.gameopening;

import processing.core.PApplet;
import processing.core.PImage;

import java.io.File;

/**
 * ak-ui 主菜单用到的美术资源 —— **唯一入口**。
 *
 * <h2>为什么集中成一个常量 + 一个类</h2>
 * 决定书 §4 要求"资源根目录写成一个常量，不要散落"。散落的后果很实际：
 * 换一次目录要改十几处，漏一处就是一张图莫名消失，而且不报错。
 *
 * <h2>⚠️ 只从 {@code data/imgs/akui/} 读，不从 vendored 目录读</h2>
 * {@code ak-ui-1.1.0/} 是参考副本，会被 {@code .gitignore} 排除；
 * 从那里读图意味着换台机器 clone 下来就全白了。
 *
 * <h2>⚠️ 用 PNG，不用 WebP</h2>
 * 决定书 §4 明确：Processing 的 ImageIO 默认不解 WebP，而为一个格式引入
 * 新的解码器依赖不值得。同目录下两种格式都有，所以这里只取 PNG。
 *
 * <h2>资源缺失时怎么办</h2>
 * 返回 {@code null}，调用方**跳过绘制**并打一行警告。不画占位图 ——
 * 决定书 §7 禁止"用占位图填充"。
 */
final class AkuiAssets {

    private AkuiAssets() {
    }

    /** 资源根目录（相对工程根）。**全仓只此一处**。 */
    static final String ROOT = "data/imgs/Main/";

    /** 整屏背景（cover 铺满）。 */
    static final String BG = ROOT + "MainBack.png";

    /** 角色立绘（底部居中，不裁头）。 */
    static final String CHARACTER = ROOT + "char_anjielina.png";

    /** 纸面板纹理（只叠在纸面板上）。 */
    static final String PAPER = ROOT + "paper-texture.jpg";

    /**
     * 加载一张图。
     *
     * @param applet 应用
     * @param path   相对工程根的路径
     * @return 图像；文件不存在或读失败时返回 {@code null}（并打警告）
     */
    static PImage load(PApplet applet, String path) {
        File f = new File(path);
        if (!f.isFile()) {
            System.out.println("[AkuiAssets] 缺资源，跳过绘制: " + f.getAbsolutePath());
            return null;
        }
        PImage img = applet.loadImage(path);
        if (img == null) {
            System.out.println("[AkuiAssets] 读取失败，跳过绘制: " + f.getAbsolutePath());
        }
        return img;
    }

    /**
     * 把图按 {@code cover} 方式铺满整个画面（裁切多余部分，不拉伸）。
     *
     * <p>CSS 的 {@code object-fit: cover}：先按较大比例等比放大到能盖住，
     * 再居中裁切。等比是关键 —— 非等比会把摄影背景压扁。
     *
     * @param applet 应用
     * @param img    要铺的图
     */
    static void drawCover(PApplet applet, PImage img) {
        if (img == null) {
            return;
        }
        float sw = applet.width;
        float sh = applet.height;
        float scale = Math.max(sw / img.width, sh / img.height);
        float dw = img.width * scale;
        float dh = img.height * scale;
        applet.image(img, (sw - dw) / 2f, (sh - dh) / 2f, dw, dh);
    }

    /** 立绘水平中心占画面宽的比例（偏左，给右侧菜单让位）。 */
    /**
     * 立绘水平中心占画面宽的比例。
     *
     * <p>用户第 5 轮要求：**靠左，不要水平居中**。
     * 0.30 让立绘主体落在画面左侧三分之一处，右侧菜单完全不遮挡。
     */
    private static final float CHARACTER_CENTER_X = 0.30f;

    /**
     * 把立绘按"底部摆放、不裁头"摆放。
     *
     * <p>规则：宽度不超过画面的 {@code maxWidthFraction}，
     * 高度不超过画面的 {@code maxHeightFraction}，取两者中更紧的等比缩放；
     * 水平居中、底边贴画面底部。
     *
     * <p>⚠️ 为什么要限制宽度：立绘原始尺寸可能比窗口高得多，
     * 只限高度会让它横向溢出、把左右两侧的菜单压掉。
     *
     * @param applet           应用
     * @param img             立绘
     * @param maxWidthFraction 最大占宽比例（0~1）
     * @param maxHeightFraction 最大占高比例（0~1）
     * @return {@code {x, y, w, h}}（窗口坐标）
     */
    static float[] characterBox(PApplet applet, PImage img,
                                float maxWidthFraction, float maxHeightFraction) {
        if (img == null) {
            return null;
        }
        float maxW = applet.width * maxWidthFraction;
        float maxH = applet.height * maxHeightFraction;
        float scale = Math.min(maxW / img.width, maxH / img.height);
        float dw = img.width * scale;
        float dh = img.height * scale;
        // TASKS-ROUND 2.2：**水平居中**、底边贴窗口底。
        //
        // ⚠️ 上一轮为了避开右菜单把它偏到了 0.34。本轮按任务单改回居中，
        //    代价是立绘会压到牌区下方 —— 牌面是不透明纸面，压不穿，
        //    但立绘会在牌区**右侧/下侧**露出来。这是规格要求，按规格做。
        float cx = applet.width * CHARACTER_CENTER_X;
        return new float[]{cx - dw / 2f, applet.height - dh, dw, dh};
    }
}
