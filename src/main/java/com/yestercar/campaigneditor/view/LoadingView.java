package com.yestercar.campaigneditor.view;

import com.yestercar.common.io.Workspace;
import com.yestercar.common.util.AssetPaths;
import com.yestercar.common.util.GifAnimation;
import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PImage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 战役编辑器的启动 / 加载界面。
 *
 * <p>职责：铺一张全屏背景图，在指定位置画一条进度条，进度条前端跟着一个小人 GIF 动画。
 * 进度走满后交由调用方切换到编辑器界面。
 *
 * <h2>为什么单独成类</h2>
 * 编辑器窗口本身就是个状态机，把加载界面拆出来可以让
 * 「加载画面」与「编辑画面」各自独立演进，互不干扰。
 *
 * <h2>资源缺失时的行为</h2>
 * 背景图与小人都在可写目录 {@code data/} 下，可能被删掉或替换。
 * 缺任何一个都只退化成「没有它」，<strong>不抛异常</strong> ——
 * 加载界面画不出来不该导致编辑器打不开。
 */
public class LoadingView {

    /** 加载背景图，相对 {@code data/imgs/}。路径常量定义在公共模块，见 {@link AssetPaths}。 */
    public static final String BACKGROUND_IMAGE = AssetPaths.EDITOR_LOADING_BACKGROUND;

    /** 加载阶段总时长（毫秒）。 */
    public static final int DURATION_MS = 5000;

    // ---- 进度条几何：按 1600x900 的背景图坐标直接给出 ----

    /** 进度条左端 x 坐标，对应图片上的 (200, 750)。 */
    public static final float BAR_X = 200f;

    /** 进度条右端 x 坐标，对应图片上的 (1300, 750)。 */
    public static final float BAR_RIGHT = 1400f;

    /** 进度条中心 y 坐标。 */
    public static final float BAR_Y = 750f;

    /** 进度条能画的最大宽度 = 右端 - 左端。 */
    public static final float BAR_MAX_WIDTH = BAR_RIGHT - BAR_X;

    /**
     * 进度条实际宽度（可自定义）。
     *
     * <p>默认吃满 (200,750)→(1300,750) 整段。想让条短一点就改这个值。
     */
    public static final float BAR_WIDTH = BAR_MAX_WIDTH;

    /** 进度条高度。 */
    public static final float BAR_HEIGHT = 18f;

    /** 加载槽（底）颜色：黑色。 */
    public static final int COLOR_TRACK = 0xFF000000;

    /** 加载条（进度）颜色：白色。 */
    public static final int COLOR_PROGRESS = 0xFFFFFFFF;

    /** 加载槽边框颜色：纯黑槽在深色背景上看不见边界，描一圈浅灰。 */
    public static final int COLOR_TRACK_BORDER = 0xFF9A9A9A;

    /** 找不到背景图时的兜底底色。 */
    public static final int COLOR_FALLBACK_BG = 0xFF101418;

    /** 进度条圆角半径。 */
    private static final float BAR_RADIUS = 6f;

    // ---- 进度条前端的小人（GIF 动画） ----

    /** 小人显示高度（像素）。宽度按 GIF 原始宽高比换算。 */
    public static final float CHARACTER_HEIGHT = 106f;

    /** 小人底边相对进度条中心的 y 偏移：负值向上，让小人站在条上。 */
    public static final float CHARACTER_BOTTOM_OFFSET_Y = BAR_HEIGHT;

    /**
     * 小人是否水平镜像。
     *
     * <p>素材本身朝哪边就填对应的值：朝右用 {@code false}，朝左用 {@code true}。
     *
     * <p>实现上<strong>不能靠把宽度传成负数</strong> —— Processing 的 {@code image()}
     * 会忽略负宽高、原样画出来（踩过一次）。必须用 {@code translate + scale(-1, 1)}。
     */
    public static final boolean CHARACTER_FLIP_X = false;

    private final PApplet applet;
    private final long startTime;
    private final PImage background;
    private final GifAnimation character;

    /**
     * @param applet 所属窗口，用于创建图片与绘制
     */
    public LoadingView(PApplet applet) {
        this.applet = applet;
        this.startTime = applet.millis();
        this.background = loadBackground();
        this.character = loadCharacterGif();
    }

    /**
     * 加载小人的 GIF 动画。
     *
     * <p>失败不致命：加载界面照常显示，只是没有小人。
     *
     * @return 动画；失败返回 {@code null}
     */
    private GifAnimation loadCharacterGif() {
        Path gifFile = Workspace.animAsset(AssetPaths.LOADING_CHARACTER_GIF);
        if (!Files.isRegularFile(gifFile)) {
            System.err.println("[LoadingView] 找不到小人 GIF，加载界面将不显示小人: " + gifFile);
            return null;
        }
        try {
            GifAnimation gif = GifAnimation.load(applet, gifFile);
            System.out.println("[LoadingView] 小人 GIF 已加载: " + gif);
            return gif;
        } catch (IOException | RuntimeException e) {
            System.err.println("[LoadingView] 小人 GIF 加载失败: " + e);
            return null;
        }
    }

    /**
     * 读取加载背景图。
     *
     * <p>用文件系统读取（{@code data/imgs/} 是可写目录，不在 classpath 上）。
     * 路径含中文，Processing 的 {@code loadImage} 内部走文件流，能正确处理非 ASCII 文件名。
     *
     * @return 图片；不存在或读取失败时返回 {@code null}
     */
    private PImage loadBackground() {
        Path path = Workspace.imageFile(BACKGROUND_IMAGE);
        if (!Files.isRegularFile(path)) {
            System.err.println("[LoadingView] 找不到加载背景图，改用纯色底: " + path);
            return null;
        }
        try {
            PImage image = applet.loadImage(path.toString());
            if (image == null) {
                System.err.println("[LoadingView] 背景图读取失败: " + path);
            }
            return image;
        } catch (RuntimeException e) {
            System.err.println("[LoadingView] 背景图加载异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 当前进度。
     *
     * @return 0.0 ~ 1.0
     */
    public float progress() {
        long elapsed = applet.millis() - startTime;
        float ratio = elapsed / (float) DURATION_MS;
        return ratio < 0f ? 0f : (ratio > 1f ? 1f : ratio);
    }

    /**
     * @return 是否已经加载完毕，可以进入编辑器界面
     */
    public boolean isFinished() {
        return progress() >= 1f;
    }

    /**
     * 绘制加载界面。
     */
    public void draw() {
        drawBackground();
        drawProgressBar(progress());
        drawCharacter(progress());
    }

    private void drawBackground() {
        if (background != null) {
            // 背景图就是 1600x900，与窗口 1:1，直接铺满
            applet.image(background, 0, 0, applet.width, applet.height);
        } else {
            applet.background(COLOR_FALLBACK_BG);
        }
    }

    private void drawProgressBar(float progress) {
        float barTop = BAR_Y - BAR_HEIGHT / 2f;

        applet.noStroke();

        // 槽底：黑色
        applet.fill(COLOR_TRACK);
        applet.rect(BAR_X, barTop, BAR_WIDTH, BAR_HEIGHT, BAR_RADIUS);

        // 进度：白色，宽度随进度增长
        if (progress > 0f) {
            applet.fill(COLOR_PROGRESS);
            applet.rect(BAR_X, barTop, BAR_WIDTH * progress, BAR_HEIGHT, BAR_RADIUS);
        }

        // 槽边框
        applet.noFill();
        applet.stroke(COLOR_TRACK_BORDER);
        applet.strokeWeight(1.5f);
        applet.rect(BAR_X, barTop, BAR_WIDTH, BAR_HEIGHT, BAR_RADIUS);

        applet.noStroke();
        applet.noFill();
    }

    /**
     * 在进度条前端绘制小人。
     *
     * <p>「始终贴在进度条前端」：小人的 x 由进度直接决定 ——
     * {@code BAR_X + BAR_WIDTH * progress}，与进度条填充的右端完全重合，
     * 不做任何追赶/缓动，两者永远同步。
     *
     * <p>动画按「从加载开始算起的真实时间」推进，与进度解耦：
     * 进度走完一轮时小人大概播了 1.9 轮（加载 2.2 秒 / GIF 1.16 秒）。
     *
     * @param progress 当前进度 0~1
     */
    private void drawCharacter(float progress) {
        if (character == null) {
            return;
        }

        PImage frame = character.frameAt(applet.millis() - startTime);

        // 按高度换算宽度，保持 GIF 原始宽高比
        float h = CHARACTER_HEIGHT;
        float w = h * character.width() / (float) character.height();

        float centerX = BAR_X + BAR_WIDTH * progress;
        // 底边落在进度条上方：CENTER 模式下 y 是中心，所以要减去半个高度
        float bottom = BAR_Y + CHARACTER_BOTTOM_OFFSET_Y;
        float centerY = bottom - h / 2f;

        applet.imageMode(PApplet.CENTER);
        if (CHARACTER_FLIP_X) {
            // 镜像要用变换矩阵取负缩放。
            // 注意：**不能靠把宽度传成负数来镜像** —— Processing 的 image() 会忽略负宽高，
            // 图会原样画出来（踩过一次）。正确做法是 translate 到镜像轴再 scale(-1, 1)：
            // 以 anchorX 为轴翻转，之后在局部原点画图即可。
            applet.pushMatrix();
            applet.translate(centerX, centerY);
            applet.scale(-1f, 1f);
            applet.image(frame, 0, 0, w, h);
            applet.popMatrix();
        } else {
            applet.image(frame, centerX, centerY, w, h);
        }
        applet.imageMode(PApplet.CORNER);
    }

    /**
     * @return 背景图是否成功加载，供自检输出使用
     */
    public boolean hasBackground() {
        return background != null;
    }

    /**
     * @return 小人动画是否成功加载
     */
    public boolean hasCharacter() {
        return character != null;
    }

    /**
     * @return 小人动画，供自检/调试查询；可能为 {@code null}
     */
    public GifAnimation character() {
        return character;
    }
}
