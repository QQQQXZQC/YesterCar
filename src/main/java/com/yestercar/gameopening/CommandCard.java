package com.yestercar.gameopening;

import com.yestercar.common.ui.DesignSpace;
import com.yestercar.gameopening.icon.SvgPath;
import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;

/**
 * 命令卡 —— {@code .ak-command} 的移植。
 *
 * <h2>这是主菜单的基本单元</h2>
 * ak-ui 的正菜单几乎全部由它拼成：一个深色面、里面右下角对齐一组
 * "大标签 + 小元信息"，右上角可以挂一个徽标，底部一条按选中状态展开的信号条。
 *
 * <h2>层序（从下往上，与 CSS 的 z-index 一致）</h2>
 * <pre>
 *   z=-1  水印        旋转 -5deg，不透明度 0.17 —— 唯一"装饰"，但压得很低
 *   z=-1  底部信号条   aria-pressed=true 时 scaleX(0) → 1
 *   z=-1  斜向渐层     linear-gradient(165deg, rgba(255,255,255,.07), transparent 60%)
 *     z=0 面色         纯色 / 半透明石墨
 *     z=1 标签（衬线、900 重、letter-spacing -0.08em）
 *     z=1 元信息（小字、宽字距、单行省略）
 *     z=2 徽标 / 当前标记
 *        内缩细边框    ::after，inset ≈ 0.22cqw
 * </pre>
 *
 * <h2>状态机（每一项都改**至少两样**，这是 design-language 的硬要求）</h2>
 * <table border="1">
 *   <caption>四种状态</caption>
 *   <tr><th>状态</th><th>改了什么</th></tr>
 *   <tr><td>静息</td><td>—</td></tr>
 *   <tr><td>悬浮</td><td>亮度 ×1.08 <b>且</b> 上移 0.18cqw（位移 + 明度）</td></tr>
 *   <tr><td>按下</td><td>亮度 ×0.95 <b>且</b> 下移 0.08cqw（反向位移 + 变暗）</td></tr>
 *   <tr><td>选中</td><td>底部信号条展开 <b>且</b> 加一圈信号色描边（不只靠颜色）</td></tr>
 *   <tr><td>禁用</td><td>整体降到 45% 不透明度 <b>且</b> 不响应鼠标 <b>且</b>
 *       元信息换成说明文字（三重手段，不靠颜色单打）</td></tr>
 * </table>
 *
 * <h2>⚠️ 命中区域是矩形</h2>
 * {@code design-language.md} 明确要求："即使可见形状被剪裁，
 * 命中区域也要保持矩形且足够大"。本类的 {@link #hit} 用**未剪裁的矩形**判定。
 */
final class CommandCard {

    /** 命令卡的面色变体。 */
    enum Tone {
        /** 石墨深色（默认）—— {@code .ak-command} / {@code --dark}。 */
        DARK,
        /** 纸面白（视觉锚点）—— {@code .ak-command--paper}。 */
        PAPER,
        /** 柔和灰 —— {@code .ak-command--muted}。 */
        MUTED,
        /** 青色信息面 —— {@code .ak-command--cyan}。 */
        CYAN,
        /**
         * 危险色面（红底白字）—— **我们自己的扩展，ak-ui 里没有这个变体**。
         *
         * <h2>⚠️ 这是一次刻意的语义移植，别照字面理解</h2>
         * ak-ui 的 {@code --ak-signal-danger: #e33b3b} 语义是"**出错**"
         * （它只出现在 {@code .ak-status--critical}）。
         * 我们把它用作「准备作战」这个**主行动按钮**的底色 ——
         * 在产品语境里红底白字是"开始/进行"的强调，不是"出错了"。
         *
         * <p>所以在读代码时：{@code Tone.DANGER} 在这里表示
         * <b>主行动</b>，不是错误提示。错误提示仍然用
         * {@link UITheme#COLOR_ACCENT}（橙）。
         */
        DANGER
    }

    // ---- 几何（全部按设计坐标，由 DesignSpace 换算） ----

    /**
     * 内边框的内缩量（设计坐标）。
     *
     * <p>原文 {@code inset: clamp(0.2rem, 0.26cqw, 0.3rem)}。在 1366 基准下
     * {@code 0.26cqw ≈ 3.55px}，clamp 到 0.3rem=4.8 与 0.2rem=3.2 之间 → **3.55px**。
     */
    private static final float INSET = 3.55f;

    /** 底部信号条的高度（设计坐标）：{@code clamp(0.18rem, 0.22cqw, 0.25rem)} → 3.0px。 */
    private static final float SIGNAL_H = 3.0f;

    /** 内边距：{@code clamp(0.7rem, 1.15cqw, 1.35rem)} 水平 → 15.7px；垂直取 1cqw ≈ 13.7px。 */
    private static final float PAD_X = 15.7f;
    private static final float PAD_Y = 13.7f;

    /**
     * 标签与元信息之间的间隙（设计坐标）—— {@code .ak-command { gap: 0.65rem }}。
     *
     * <p>⚠️ 这个值<b>不随放大倍数变化</b>（决定书 D3 明确要求）。
     * 理由：gap 是行与行之间的呼吸，跟着放大只会让内容散开，
     * 而放大本来就是为了让内容更实。
     */
    private static final float INNER_GAP = 10.4f;

    /** 悬浮上移 / 按下下移（设计坐标）：{@code 0.18cqw} / {@code 0.08cqw}。 */
    private static final float LIFT = 2.46f;
    private static final float SINK = 1.09f;

    /** 悬浮位移用的曲线，与 {@code .ak-command} 的 transition 一致。 */
    private static final Easing MOVE_EASE = Easing.COMMAND_MOVE;
    private static final float MOVE_MS = 280f;

    private final PApplet g;
    private final DesignSpace d;
    private final Fonts fonts;

    // ---- 内容 ----
    private String label = "";

    /** 标签是否水平居中（默认左对齐）。 */
    private boolean centerLabel;
    private String meta = "";
    private String badge = "";
    private String watermark = "";
    /** 水印图标 id（决定书 D5 之后改用 Remix 图标，不再用汉字）。 */
    private String watermarkIcon = "";
    private Tone tone = Tone.DARK;
    private boolean primary;

    /**
     * 标签字号（设计坐标）。默认 {@value #DEFAULT_LABEL}。
     *
     * <p>ak-ui 的 {@code .ak-command__label} 是
     * {@code clamp(1.1rem, 2.7cqw, 2.85rem)} —— 字号**跟着容器走**。
     * 我们独占整行的大卡与三列小卡宽度差一倍多，所以字号得能按卡给。
     */
    private float labelSize = DEFAULT_LABEL;

    /**
     * 上一帧**实际**用到的标签像素字号。
     *
     * <h2>为什么需要它</h2>
     * {@link #drawContent} 在文字比卡片宽时会**按比例缩小字号**（`fit()` 的替代）。
     * 于是"设定字号"与"实际渲染字号"可能差很远 ——
     * 验收里 3.5③（标签字号 / 牌高）必须用**实际**那一档，
     * 否则就是拿名义值冒充实际效果。
     */
    private float lastLabelPx;

    /** 标签是否水平垂直居中（{@code --vertical}）。 */
    private boolean centered;

    /**
     * 放大倍数（决定书 D2/D3）。2×2 布局的牌比三行布局大，内距与水印要同步放大。
     *
     * <p>作用范围：内边距、水印尺寸。
     * <b>不影响</b> {@link #INNER_GAP}（gap 不放大），
     * 字号另有 {@link #MAX_TEXT_SCALE} 上限。
     */
    private float enlarge = 1f;

    /**
     * 字号放大上限 —— <b>1.5 倍</b>（决定书 D3）。
     *
     * <p>卡片几何可以放到 1.45×，但字再大就从"清楚的标题"变成"撑满的标语"，
     * 可读性反而下降。所以几何放得开、字号有天花板。
     */
    private static final float MAX_TEXT_SCALE = 1.5f;

    /** 默认标签字号（设计坐标）—— 对应 {@code 2.7cqw} 在 1366 基准下的值。 */
    static final float DEFAULT_LABEL = 37f;

    // ---- 布局（窗口坐标） ----
    private float x;
    private float y;
    private float w;
    private float h;

    // ---- 状态 ----
    private boolean selected;
    private boolean disabled;
    private boolean hovered;
    private boolean pressed;

    /** 悬浮/按下的位移插值当前值，用来做过渡而不是瞬变。 */
    private float offset;
    private int lastFrameMs = -1;

    // ---- 入场（ak-ui 的 data-ak-enter：错峰滑入 + 淡入） ----

    /** 入场期间的额外平移（窗口坐标），动画结束后恒为 0。 */
    private float enterDx;
    private float enterDy;

    /** 入场期间的透明度乘子（1 = 已就位）。 */
    private float enterAlpha = 1f;

    CommandCard(PApplet g, DesignSpace d, Fonts fonts) {
        this.g = g;
        this.d = d;
        this.fonts = fonts;
    }

    // ------------------------------------------------------------------
    // 流式设置（照着 CSS 的类名读起来差不多）
    // ------------------------------------------------------------------

    CommandCard at(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        return this;
    }

    /**
     * 标签水平居中（默认左对齐，与 ak-ui 的卡片一致；个别按钮需要居中）。
     *
     * @param on 是否居中
     * @return 自身（链式）
     */
    CommandCard centerLabel(boolean on) {
        this.centerLabel = on;
        return this;
    }

    CommandCard label(String s) {
        this.label = s == null ? "" : s;
        return this;
    }

    CommandCard meta(String s) {
        this.meta = s == null ? "" : s;
        return this;
    }

    CommandCard badge(String s) {
        this.badge = s == null ? "" : s;
        return this;
    }

    /**
     * 水印文字（ak-ui 的 {@code __watermark} 是个图标，这里用字符代替）。
     *
     * @param s 一到两个字符最好，多了会溢出
     * @return 自身
     */
    CommandCard watermark(String s) {
        this.watermark = s == null ? "" : s;
        return this;
    }

    CommandCard tone(Tone t) {
        this.tone = t == null ? Tone.DARK : t;
        return this;
    }

    CommandCard primary(boolean b) {
        this.primary = b;
        return this;
    }

    /**
     * 标签是否**居中**（对应 {@code .ak-command--vertical}）。
     *
     * <p>ak-ui 的 {@code --vertical} 变体把内容从"靠底靠左"改成"水平垂直都居中"
     * （{@code _ak-command.scss:219-224}）。窄卡用它 —— 参考稿第 3 行最右那张
     * 「仓库」就是竖排居中，因为 {@code fr=9} 的格子放不下左对齐的两行。
     *
     * @param b 是否居中
     * @return 自身
     */
    CommandCard centered(boolean b) {
        this.centered = b;
        return this;
    }

    /**
     * 设置放大倍数（决定书 D2/D3）。
     *
     * @param k 倍数；{@code <= 0} 视为 1
     * @return 自身
     */
    CommandCard enlarge(float k) {
        this.enlarge = k <= 0f ? 1f : k;
        return this;
    }

    /**
     * 水印图标（Remix Icon 的 sprite id，见 {@code icon/AkIcons.java}）。
     *
     * <p>⚠️ 决定书 D5：<b>不再用汉字水印</b>。上游
     * {@code .ak-command__watermark} 本来就是一个 SVG 图标
     * （{@code <use href="#icon-rook">}），之前用单字代替只是因为当时没有图标源。
     *
     * @param iconId sprite id；{@code null} 或空表示不画
     * @return 自身
     */
    CommandCard watermarkIcon(String iconId) {
        this.watermarkIcon = iconId == null ? "" : iconId;
        this.watermark = "";
        return this;
    }

    /**
     * 指定标签字号（设计坐标）。
     *
     * @param designSize 字号
     * @return 自身
     */
    CommandCard labelSize(float designSize) {
        this.labelSize = designSize;
        return this;
    }

    CommandCard selected(boolean b) {
        this.selected = b;
        return this;
    }

    /**
     * 禁用。
     *
     * <p>禁用时 {@link #hit} 一律返回 {@code false} —— 见类注释里
     * "不靠颜色单打"那条。
     *
     * @param b 是否禁用
     * @return 自身
     */
    CommandCard disabled(boolean b) {
        this.disabled = b;
        return this;
    }

    /**
     * 设置入场偏移与透明度（ak-ui {@code createEntrance} 的对应：
     * direction 的 40px 位移 + opacity 0→1，动画结束后传 0/0/1）。
     *
     * <p>⚠️ 命中判定（{@link #hit}）始终按<b>最终位置</b>算 ——
     * 与 ak-ui 一致：入场动画是纯视觉的，不改变可点区域。
     *
     * @param dx       x 方向偏移（窗口像素；从右侧进场时为正）
     * @param dy       y 方向偏移（从下方进场时为正）
     * @param alphaMul 透明度乘子 0~1
     * @return 自身
     */
    CommandCard enter(float dx, float dy, float alphaMul) {
        this.enterDx = dx;
        this.enterDy = dy;
        this.enterAlpha = Math.max(0f, Math.min(1f, alphaMul));
        return this;
    }

    // ------------------------------------------------------------------
    // 状态更新
    // ------------------------------------------------------------------

    /**
     * 每帧更新悬浮/按下状态与位移过渡。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @param nowMs  当前毫秒
     */
    void update(float mouseX, float mouseY, int nowMs) {
        hovered = !disabled && hit(mouseX, mouseY);

        float target = 0f;
        if (!disabled && pressed && hovered) {
            target = SINK;              // 下移
        } else if (hovered) {
            target = -LIFT;             // 上移
        }

        float dt = lastFrameMs < 0 ? 16f : Math.max(1f, nowMs - lastFrameMs);
        lastFrameMs = nowMs;
        // 用同一个曲线把位移插值过去；dt 一大就直接到位，避免拖尾
        float k = Math.min(1f, dt / MOVE_MS);
        offset += (target - offset) * MOVE_EASE.at(k);
        if (Math.abs(target - offset) < 0.05f) {
            offset = target;
        }
    }

    /**
     * 命中判定：**未剪裁的矩形**。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return 命中返回 {@code true}；禁用时恒为 {@code false}
     */
    boolean hit(float mx, float my) {
        if (disabled) {
            return false;
        }
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /**
     * @return 左边界（窗口坐标）
     */
    float x() {
        return x;
    }

    /**
     * @return 上边界（窗口坐标，不含悬浮位移）
     */
    float y() {
        return y;
    }

    /**
     * @return 宽度（窗口坐标）
     */
    float width() {
        return w;
    }

    /**
     * @return 高度（窗口坐标）
     */
    float height() {
        return h;
    }

    /** @return 上一帧实际渲染用的标签像素字号（可能因宽度不足而被缩小） */
    float lastLabelPx() {
        return lastLabelPx;
    }

    /** @return 内边距的设计值（供验收算"牌内可用宽"） */
    static float padXDesign() {
        return PAD_X;
    }

    /** @return 当前放大倍数（供验收算"牌内可用宽"） */
    float enlargeFactor() {
        return enlarge;
    }

    void press() {
        pressed = true;
    }

    void release() {
        pressed = false;
    }

    boolean isPressed() {
        return pressed;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    void draw() {
        float ox = x + enterDx;
        float oy = y + offset + enterDy;

        int face = faceColor();
        // ⚠️ 禁用态透明度 0.72（试过 0.45 与 0.60）。
        //    0.45 在半透明纸面卡叠深色底时让整张卡几乎看不见；
        //    0.60 在有立绘/摄影背景的主菜单里仍然太透（字符被背景吃掉）。
        //    "禁用"应当读作"这张卡在、但按下没反应"，而不是"这里什么都没有"。
        //    ak-ui 上游没有 :disabled 规则（查证过），这个数是我们自己定的。
        float alpha = (disabled ? 0.86f : 1f) * enterAlpha;
        boolean down = pressed && hovered;

        g.pushMatrix();
        g.translate(0f, 0f);

        // ---- ① 投影（CSS 的 box-shadow，画在最底下） ----
        //
        // ak-ui: box-shadow: 0 1cqw 2cqw rgba(0, 0, 0, 0.3)
        // （_ak-command.scss:28）。P2D 没有高斯模糊，用多层递减偏移矩形近似。
        if (System.getProperty("yestercar.noShadow") == null) {
            drawDropShadow(ox, oy, alpha);
        }

        // ---- ② 面色 ----
        //
        // ⚠️ 画两遍：先一遍**不透明**打底，再一遍半透明上色。
        //
        // 起因：ak-ui 的面色带 alpha（纸面 .95 / 石墨 .92），而我们的投影就画在
        // 正下方。8% 的透光让 16 层投影在卡片内部显出**一条条横向条纹** ——
        // 深色牌上尤其明显（实测「退出」卡上半部像被划了几道）。
        //
        // 先铺一层同色不透明底，投影就透不上来了；半透明那一遍照旧，
        // 所以颜色与设计稿完全一致，只是背面不再漏光。
        g.noStroke();
        g.fill(face, 255 * alpha * (down ? 0.95f : 1f));
        CutShape.square(g, ox, oy, w, h);
        g.fill(face, alpha(face, alpha, down));
        CutShape.square(g, ox, oy, w, h);

        // ---- ③ 斜向渐层（属于 background-image，压在面色之上） ----
        drawTopSheen(ox, oy, alpha, down);

        // ---- ④ 底部信号条（::before，z-index:-1） ----
        g.noStroke();
        g.fill(UITheme.MENU_ACCENT, 255 * alpha);
        CutShape.signalBar(g, ox, oy + h - d.h(SIGNAL_H), w, d.h(SIGNAL_H),
                selected ? 1f : 0f);

        // ---- ④.5 纸纹（D7，只叠纸面） ----
        drawPaperTexture(ox, oy, alpha);

        // ---- ⑤ 水印（子元素，z-index:-1） ----
        //
        // ⚠️ 顺序很要紧：`.ak-command` 有 `isolation: isolate`，于是
        // z-index:-1 的子元素**画在自身背景之上、内容之下** —— 不是"被背景盖住"。
        // 上一版把水印画在面色之前，再加上面色现在有不透明打底，
        // 结果图标**一个都看不见**（实测）。
        // 同一 z 层里 ::before 先于子元素，所以信号条在水印之下。
        drawWatermark(ox, oy, alpha);

        // ---- ⑥ 内缩细边框（::after） ----

        // ---- ④ 斜向渐层：165deg，前 60% 衰减到透明 ----
        drawTopSheen(ox, oy, alpha, down);

        // ---- ⑤ 内缩细边框（::after） ----
        g.noFill();
        boolean lightFace = tone == Tone.PAPER || tone == Tone.MUTED;
        g.stroke(255, 255, 255, (lightFace ? 0.28f : 0.18f) * 255 * alpha);
        g.strokeWeight(d.len(1f));
        CutShape.insetStroke(g, ox, oy, w, h, d.len(INSET));

        // ---- ⑥ 选中时的信号色描边（第二重提示，不只靠那条色带） ----
        if (selected) {
            g.stroke(UITheme.MENU_ACCENT, 255 * alpha);
            g.strokeWeight(d.len(2f));
            CutShape.insetStroke(g, ox, oy, w, h, d.len(INSET) + d.len(2f));
        }
        g.noStroke();

        // ---- ⑦ 内容：右下角对齐（align-content: end） ----
        drawContent(ox, oy, alpha);

        // ---- ⑧ 徽标 / 当前标记（z=2，右上角） ----
        drawBadge(ox, oy, alpha);

        g.popMatrix();
    }

    /** 水印字号要按"设计字号"给，所以把窗口尺寸反算回设计尺寸。 */
    private float sxHint() {
        return d.sx();
    }

    private void drawWatermark(float ox, float oy, float alpha) {
        if (watermarkIcon.isEmpty()) {
            return;
        }
        // ak-ui: `width: min(9cqw, 75%)` —— 这是**卡片的**百分比，卡片变大它自然变大。
        // ⚠️ 所以这里**不能**再乘 enlarge：那会把放大算两遍（实测 128px 的水印
        //    压住了标题）。9cqw 用 BASE_W 换算，75% 取卡片短边。
        // ak-ui: min(9cqw, 75%)。但我们的牌是**纵向长条**（296×553），
        // 75% 取短边仍是 222px，而卡宽只有 296 —— 图标横向几乎撑满。
        // 所以再压一档：不超过卡宽的 55%，让水印留在牌面里。
        float size = Math.min(DesignSpace.BASE_W * 9f / 100f,
                Math.min(w * 0.55f, h * 0.45f));
        float side = Math.min(d.w(size), d.h(size));

        g.pushMatrix();
        g.translate(ox + w / 2f, oy + h / 2f);
        // ak-ui: transform: translate(-50%,-50%) rotate(-5deg)
        g.rotate(PApplet.radians(-5f));
        // opacity: 0.17
        SvgPath.fill(g, watermarkIcon, -side / 2f, -side / 2f, side,
                withAlpha(textColor(), 0.17f * alpha));
        g.popMatrix();
    }

    /**
     * 纸面板纹理（决定书 D7）—— 由 {@code GameOpeningApp} 在 setup 里注入一次。
     *
     * <p>**只叠在纸面/柔和面上**（{@code PAPER} / {@code MUTED}），
     * 深色牌与青色牌不叠 —— 原版的纸纹本来也只属于纸面板。
     *
     * <p>用静态字段是因为纹理是全局唯一的一张图，没必要每张卡各持一份引用。
     */
    private static processing.core.PImage paperTexture;

    /**
     * 注入纸纹（由启动时调用一次）。
     *
     * @param tex 纹理图；{@code null} 表示没有，不叠
     */
    static void setPaperTexture(processing.core.PImage tex) {
        paperTexture = tex;
    }

    /**
     * 在纸面上叠一层纹理。
     *
     * <p>⚠️ 必须在面色之后、内容之前调用：纹理属于面层的质感，
     * 盖到文字上就成了脏点。
     *
     * @param ox    卡片左边界
     * @param oy    卡片上边界
     * @param alpha 整体透明度
     */
    private void drawPaperTexture(float ox, float oy, float alpha) {
        if (paperTexture == null || (tone != Tone.PAPER && tone != Tone.MUTED)) {
            return;
        }
        g.pushStyle();
        g.imageMode(PConstants.CORNER);
        // 纹理本身不透明，用 tint 压到很淡，只要"手感的噪点"，不要它的图案
        g.tint(255, 255, 255, 0.10f * 255f * alpha);
        g.image(paperTexture, ox, oy, w, h);
        g.noTint();
        g.popStyle();
    }

    /**
     * 给颜色换一个 alpha。
     *
     * @param argb  原色
     * @param scale 0~1
     * @return 新色
     */
    private static int withAlpha(int argb, float scale) {
        int a = Math.round(Math.max(0f, Math.min(1f, scale)) * 255f);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * {@code linear-gradient(165deg, rgba(255,255,255,.07), transparent 60%)}。
     *
     * <p>⚠️ 165deg 在 CSS 里是"从接近正上方、略偏右"往下走的方向。
     * 第一版我按<b>水平色带</b>画，结果每一带都有硬边、看起来像条形码。
     * 正确做法是：对每个像素求它在渐变轴上的投影，再按投影位置取 alpha。
     * 这里按行扫描，每行内部再按 x 微调 —— 因为 165deg 几乎竖直，
     * 行内的变化很小，用两段三角形近似就够，不会看出台阶。
     */
    private void drawTopSheen(float ox, float oy, float alpha, boolean down) {
        if (w <= 0f || h <= 0f) {
            return;
        }
        float brightness = down ? 0.95f : (hovered ? 1.08f : 1f);
        float maxAlpha = 0.07f;

        // 渐变轴：165deg → 方向向量 (sin165, -cos165) = (0.2588, 0.9659)（向下为正）
        float dx = 0.2588f;
        float dy = 0.9659f;
        // 轴长 = 矩形在该方向上的投影长度
        float axis = Math.abs(w * dx) + Math.abs(h * dy);

        // 40 → 14：JAVA2D 下每条都是一次带抗锯齿的填充，40 条太贵。
        // 14 条在深色牌上仍看不出台阶（早期 5 条时才明显）。
        int bands = 14;
        g.noStroke();
        for (int i = 0; i < bands; i++) {
            float t0 = i / (float) bands;
            float t1 = (i + 1) / (float) bands;
            // 取带中心在轴上的位置（相对轴长）
            float tc = (t0 + t1) / 2f;
            float proj = tc * axis;
            float a = maxAlpha * Math.max(0f, 1f - proj / (axis * 0.6f));
            if (a <= 0.0005f) {
                continue;
            }
            g.fill(255, 255, 255, a * 255 * alpha * brightness);
            float y0 = oy + h * t0;
            // ⚠️ 高度**不能** +1.2 去补缝：相邻两条半透明矩形一旦重叠，
            //    重叠那条的 alpha 就是两倍，深色卡上会显出**等距横纹**
            //    （实测「退出」卡上半部像被划了几道）。宁可留 1px 缝，
            //    也不要有累加 —— P2D 的抗锯齿会把缝抹平。
            g.rect(ox, y0, w, h * (t1 - t0));
        }
    }

    private void drawContent(float ox, float oy, float alpha) {
        // ⚠️ 内边距随放大倍数走（决定书 D3），但**内部 gap 不放大**。
        float padX = d.w(PAD_X) * enlarge;
        float padY = d.h(PAD_Y) * enlarge;
        float innerGap = d.h(INNER_GAP);            // gap 恒为 0.65rem，不乘 enlarge
        float baseY = oy + h - padY;

        boolean light = tone == Tone.PAPER || tone == Tone.MUTED;
        int primaryText = light ? 0xFF1B1D1F : UITheme.MENU_TEXT;
        float brightness = brightnessFor();

        if (centered) {
            drawContentCentered(ox, oy, padX, alpha, brightness);
            return;
        }

        g.textAlign(PConstants.LEFT, PConstants.BOTTOM);

        // 可用宽度：两边各留一个内边距。
        // ⚠️ CSS 那边有 text-overflow: ellipsis 自动省略，Processing 没有 ——
        // 不自己截断的话，长文字会**直接糊到隔壁卡上**（实测见过）。
        float avail = w - padX * 2f;

        // 元信息字号：base 10 × enlarge，但受 1.5× 上限
        float metaScale = Math.min(enlarge, MAX_TEXT_SCALE);
        float metaSize = 10f * metaScale;

        // 小元信息在下（ak-ui 的顺序是 label 在上、meta 在下，靠底对齐）
        if (!meta.isEmpty()) {
            fonts.apply(g, Fonts.Role.MONO, metaSize);
            g.fill(light ? 0x941B1D1F : UITheme.MENU_TEXT_DIM,
                    0.58f * 255 * alpha * brightness);
            fonts.text(g, fit(meta, avail), ox + padX, baseY);
        }

        // 大标签在上。
        //
        // ⚠️ 字号是**按可用宽度反推**的，不是固定值。
        // 起因：Processing 的 textWidth() 对 CJK 回退字形报得偏大
        // （实测「第一关：边…」约 96pt，却被算成 >184pt），
        // 于是 {@link #fit} 把一个明明放得下的标签截成了「第一关：边…」。
        //
        // 与其和字体度量较劲，不如**先量再定字号**：能放下就用目标字号，
        // 放不下就按比例缩小。这样即使度量偏大，结果也只是字略小，
        // 而不是内容被砍掉 —— 信息完整比字号精确重要。
        if (!label.isEmpty()) {
            // 目标字号 = 设计字号 × min(放大倍数, 1.5)，再受卡片高度约束
            float target = labelSize * Math.min(enlarge, MAX_TEXT_SCALE);
            float wanted = Math.min(target, h * 0.42f / d.sy());
            fonts.apply(g, Fonts.Role.COMMAND, wanted);
            float measured = g.textWidth(label);
            if (measured > avail && measured > 0f) {
                wanted = Math.max(11f, wanted * (avail / measured));
                fonts.apply(g, Fonts.Role.COMMAND, wanted);
            }
            // 标签底边 = 元信息顶边 − gap（有 meta 时）；没有 meta 就贴底内边距
            float labelBottom = baseY - (meta.isEmpty() ? 0f : metaSize + innerGap);
            lastLabelPx = fonts.size(wanted);
            g.fill(primaryText, 255 * alpha * brightness);
            if (centerLabel) {
                g.textAlign(PConstants.CENTER, PConstants.BOTTOM);
                fonts.text(g, fit(label, avail), ox + padX + avail / 2f, labelBottom);
                g.textAlign(PConstants.LEFT, PConstants.BOTTOM);
            } else {
                fonts.text(g, fit(label, avail), ox + padX, labelBottom);
            }
        }
    }

    /** @return 当前的亮度系数（悬浮/按下）。 */
    private float brightnessFor() {
        return (pressed && hovered) ? 0.95f : (hovered ? 1.08f : 1f);
    }

    /**
     * {@code --vertical} 的内容：标签水平垂直居中，元信息在下方。
     *
     * <p>窄卡（{@code fr=9}）必须走这条 —— 左对齐会让标签被裁。
     */
    private void drawContentCentered(float ox, float oy, float padX,
                                     float alpha, float brightness) {
        boolean light = tone == Tone.PAPER || tone == Tone.MUTED;
        int primaryText = light ? 0xFF1B1D1F : UITheme.MENU_TEXT;
        float cx = ox + w / 2f;
        float avail = w - padX * 2f;

        float labelSize = Math.min(this.labelSize, h * 0.34f / d.sy());
        fonts.apply(g, Fonts.Role.COMMAND, labelSize);
        float measured = g.textWidth(label);
        if (measured > avail && measured > 0f) {
            labelSize = Math.max(10f, labelSize * (avail / measured));
            fonts.apply(g, Fonts.Role.COMMAND, labelSize);
        }
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        g.fill(primaryText, 255 * alpha * brightness);
        fonts.text(g, label, cx, oy + h * (meta.isEmpty() ? 0.5f : 0.44f));

        if (!meta.isEmpty()) {
            fonts.apply(g, Fonts.Role.MONO, 9f);
            g.fill(light ? 0x941B1D1F : UITheme.MENU_TEXT_DIM,
                    0.58f * 255 * alpha * brightness);
            fonts.text(g, fit(meta, avail), cx, oy + h * 0.74f);
        }
    }

    private void drawBadge(float ox, float oy, float alpha) {
        float padX = d.w(PAD_X);
        float padY = d.h(PAD_Y);

        if (!badge.isEmpty()) {
            // __badge：橙底、深字、等宽、粗 —— 右上角
            fonts.apply(g, Fonts.Role.MONO, 11f);
            float tw = g.textWidth(badge);
            float bw = tw + d.w(8f);
            float bh = d.h(16f);
            float bx = ox + w - d.w(INSET) - d.w(6f) - bw;
            float by = oy + d.h(INSET) + d.h(6f);

            g.noStroke();
            g.fill(UITheme.MENU_ACCENT, 255 * alpha);
            CutShape.square(g, bx, by, bw, bh);
            g.fill(0xFF111315, 255 * alpha);
            g.textAlign(PConstants.CENTER, PConstants.CENTER);
            fonts.text(g, badge, bx + bw / 2f, by + bh / 2f);
        } else if (selected) {
            // __current：深底白字的"当前"标记
            String now = "当前";
            fonts.apply(g, Fonts.Role.MONO, 11f);
            float tw = g.textWidth(now);
            float bw = tw + d.w(10f);
            float bh = d.h(17f);
            float bx = ox + w - padX - bw;
            float by = oy + padY;

            g.noStroke();
            g.fill(0xE3121416, 255 * alpha);
            CutShape.square(g, bx, by, bw, bh);
            g.fill(255, 255, 255, 255 * alpha);
            g.textAlign(PConstants.CENTER, PConstants.CENTER);
            fonts.text(g, now, bx + bw / 2f, by + bh / 2f);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 投影 —— 近似 CSS 的 {@code box-shadow: 0 1cqw 2cqw rgba(0,0,0,.3)}。
     *
     * <p>JAVA2D 没有高斯模糊，所以用<b>叠层</b>做柔边：偏移量取原文的
     * {@code 1cqw}（垂直）与 {@code 2cqw}（模糊半径），
     * 从外到内画若干层，每层 alpha 递减、尺寸递增。
     *
     * <p>层数取 5 —— 再多肉眼也分不出，但会让每帧多画很多矩形。
     *
     * @param ox    卡片左边界
     * @param oy    卡片上边界
     * @param alpha 整体透明度
     */
    private void drawDropShadow(float ox, float oy, float alpha) {
        float cqw = d.cqw(1f);
        float dropY = cqw * 1f;          // 0 1cqw
        float blur = cqw * 2f;           // 2cqw

        // ⚠️ 层数不能太少。第一版用了 5 层，放大 6 倍能数出 5 条阶梯。
        //    CSS 的模糊是连续的，层数不够就变成"叠纸"而不是"投影"。
        //    16 层在半透明纸面上已经看不出台阶（纸面 95% 不透明，
        //    底下的阴影会以 5% 透上来 —— 那是 CSS 本来就有的行为，不是 bug）。
        // 16 → 10：同上。10 层的柔边仍够，台阶不明显。
        int layers = 10;

        g.noStroke();
        for (int i = layers; i >= 1; i--) {
            float k = i / (float) layers;
            float grow = blur * k;
            // 高斯衰减的粗略拟合：越靠内 alpha 越高
            float falloff = (1f - k) * (1f - k);
            g.fill(0f, 0f, 0f, 0.30f * 255f * falloff / layers * 4f * alpha);
            CutShape.square(g, ox - grow, oy + dropY - grow,
                    w + grow * 2f, h + grow * 2f);
        }
    }

    /**
     * 把一行文字截到放得下。
     *
     * <p>ak-ui 靠 CSS 的 {@code text-overflow: ellipsis} 处理溢出；
     * Processing 没有这个能力，所以按实际字宽自己算。
     *
     * <p>⚠️ <strong>不要用 {@code textWidth()} 逐字累加</strong>。
     * 实测那样会截得过狠：Processing 对"CJK 回退字形"的宽度常报不准
     * （报得比实际大），于是 {@code 第一关：边境哨站} 被截成
     * {@code 第一关：边…}，而卡片右边其实还空着一大截。
     *
     * <p>改用<b>二分</b>：只比较"整串的宽度"，而整串宽度是准的。
     * 二分 8 次就能定位到 1/256 的精度，足够。
     *
     * @param text     原文
     * @param maxWidth 可用像素宽
     * @return 放得下的文字，超长时以 {@code …} 结尾
     */
    private String fit(String text, float maxWidth) {
        if (text.isEmpty() || maxWidth <= 0f || g.textWidth(text) <= maxWidth) {
            return text;
        }
        String ell = "…";
        // 用省略号本身兜底：连一个字符都放不下时至少给个符号
        if (g.textWidth(ell) > maxWidth) {
            return "";
        }
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (g.textWidth(text.substring(0, mid) + ell) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ell;
    }

    private int faceColor() {
        return switch (tone) {
            case PAPER -> UITheme.MENU_PAPER;
            case MUTED -> UITheme.MENU_MUTED;
            case CYAN -> UITheme.MENU_CYAN;
            case DANGER -> UITheme.SIGNAL_DANGER;
            case DARK -> UITheme.MENU_RAISED;
        };
    }

    private int textColor() {
        return switch (tone) {
            case PAPER, MUTED -> 0xFF1B1D1F;
            // 红底上用白字 —— 对比度足够；青底同理
            case CYAN, DARK, DANGER -> UITheme.MENU_TEXT;
        };
    }

    /** 面色本身多半是半透明的，所以直接用它自己的 alpha 通道。 */
    private static float alpha(int color, float alphaScale, boolean down) {
        float base = ((color >>> 24) & 0xFF) / 255f;
        float brightness = down ? 0.95f : 1f;
        return base * alphaScale * brightness * 255f;
    }
}
