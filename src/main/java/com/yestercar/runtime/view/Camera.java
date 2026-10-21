package com.yestercar.runtime.view;

/**
 * 战斗界面的**透视相机**。
 *
 * <p>与编辑器的 {@link com.yestercar.campaigneditor.view.MapViewport} <strong>并列而非继承</strong>：
 * 两者承担完全不同的职责 ——
 *
 * <table border="1">
 *   <caption>两套投影的分工</caption>
 *   <tr><th></th><th>编辑器 MapViewport</th><th>战斗 Camera（本类）</th></tr>
 *   <tr><td>投影</td><td>正交（平行投影）</td><td><b>透视</b></td></tr>
 *   <tr><td>目的</td><td>点格拾取要精确、要对齐网格</td><td>好看、有代入感</td></tr>
 *   <tr><td>格子形状</td><td>正方形</td><td>梯形（近大远小）</td></tr>
 *   <tr><td>缩放</td><td>滚轮改比例</td><td>改相机距离</td></tr>
 * </table>
 *
 * <p>两者共用同一份地图数据（{@code TileMap}），<strong>地图 JSON 不受影响</strong>。
 *
 * <h2>相机模型</h2>
 * 参考项目 Defender 的 {@code render/projection.js}，是标准的针孔相机：
 *
 * <pre>
 *   世界坐标：x = 列，y = 行（row 越大越靠近相机），z = 高度（向上为正，单位=格）
 *
 *   C = T + dist · (0, +sin(tilt), cos(tilt))     相机位置（在目标点的南侧 = y 更大的一侧）
 *   f = (0, −sin(tilt), −cos(tilt))               前方向（朝 y 减小、z 减小的方向看下去）
 *   u = (0, −cos(tilt), +sin(tilt))               上方向
 *
 *   depth  = (P − C) · f
 *   屏幕坐标 = (cx, cy) + focal/depth · ((P−C)·r, −(P−C)·u)
 * </pre>
 *
 * <p>注意 y 增大时 depth 减小 —— 也就是 <strong>row 越大离相机越近、投影越大</strong>，
 * 这正是需求要的方向。搞反的话远处会画到屏幕下方、近处画到上方，整个画面颠倒
 * （实测踩过一次，靠单元测试抓出来的）。
 *
 * <h2>官方参数是标定出来的</h2>
 * 明日方舟的战斗视角是<strong>俯角 30°、垂直视场角 40°</strong>。
 * 这不是主观定的：参考项目的注释记载它是对**官方 1920×1080 截图做最小二乘拟合**得到的，
 * 而且每个关卡的 {@code configBlackBoard} 里本来就带 {@code left_battle_camera_param}
 * 这类相机参数 —— 数据是从游戏里挖出来的。我们直接采用同一套数值。
 *
 * <h2>为什么 row 大 = 离相机近</h2>
 * 相机在目标的<strong>南侧</strong>（y 更小的方向是远处）。
 * 所以行号越大，越靠近观察者，投影出来越大 —— 这正是「近处平、远处厚」的来源：
 * 近处的格子侧面被压缩到几乎看不见，远处的格子因为俯角关系侧面占比大。
 * 这是<strong>透视的自然结果</strong>，不需要额外造假。
 */
public final class Camera {

    /**
     * 明日方舟标准战斗相机的俯角（度），从正上方往下算。
     *
     * <p>30° 意味着相机离"正俯视"偏了 30° —— 既能看到地面，也能看到方块的侧面。
     */
    public static final float OFFICIAL_PITCH_DEG = 30f;

    /** 官方垂直视场角（度）。 */
    public static final float OFFICIAL_FOV_Y_DEG = 40f;

    /** 官方相机相对目标点的基准偏移（居中坐标系，单位=格）。 */
    public static final float[] OFFICIAL_BASE_OFFSET = {0f, -4.81f, -7.76f};

    /** 相机距离的允许范围（格）。太近会穿模，太远透视感消失。 */
    public static final float MIN_DIST = 4f;
    public static final float MAX_DIST = 60f;

    private static final float DEG = (float) (Math.PI / 180.0);
    private static final float EPS = 1e-6f;

    // ---- 相机参数 ----

    /** 注视点（地面上的世界坐标）。 */
    private float targetX;
    private float targetY;
    private float targetZ;

    /** 俯角（弧度），0 = 正俯视。 */
    private float tilt;

    /** 相机到注视点的距离（格）。 */
    private float dist;

    /** 视口尺寸。 */
    private int viewportW;
    private int viewportH;

    /** 主点（通常为视口中心）。 */
    private float cx;
    private float cy;

    // ---- 预计算量（每次参数变化后重算） ----

    private float sinT;
    private float cosT;
    private float camX;
    private float camY;
    private float camZ;
    /** 焦距：{@code scale * dist}，单位 像素·格。 */
    private float focal;

    private int version;

    /**
     * 用官方视角造一个相机。
     *
     * @param viewportW 视口宽（像素）
     * @param viewportH 视口高（像素）
     */
    /** @return 视口宽（像素） */
    public int viewWidth() {
        return viewportW;
    }

    /** @return 视口高（像素） */
    public int viewHeight() {
        return viewportH;
    }

    /** @return 投影中心 y */
    public float centerY() {
        return cy;
    }

    /** @return 投影中心 x */
    public float centerX() {
        return cx;
    }

    /**
     * 窗口尺寸变化：只更新视口与投影中心，**保留平移/缩放/俯仰状态**。
     *
     * <p>之前是直接 new 一个 Camera —— 旧状态（target/dist）全丢，
     * 于是每次缩放/全屏地图都会重新取景跑到画面外。
     *
     * @param w 新视口宽（像素）
     * @param h 新视口高（像素）
     */
    /**
     * 把画面整体上移 {@code px} 像素（投影中心下移同样的量）。
     *
     * <p>给底部待部署区让位：这样地图不会和它重叠，而视口尺寸不变，
     * 不会出现"扣高度导致投影错位"的问题。
     *
     * @param px 上移像素
     */
    public void shiftUp(float px) {
        // 符号试错结论：cy 增大反而把画面往下推，所以这里用减号
        this.cy = viewportH / 2f - Math.max(0f, px);
        update();
    }

    public void resize(int w, int h) {
        this.viewportW = Math.max(1, w);
        this.viewportH = Math.max(1, h);
        this.cx = this.viewportW / 2f;
        this.cy = this.viewportH / 2f;
        update();
    }

    public Camera(int viewportW, int viewportH) {
        this.viewportW = Math.max(1, viewportW);
        this.viewportH = Math.max(1, viewportH);
        this.tilt = OFFICIAL_PITCH_DEG * DEG;
        this.dist = 16f;
        this.cx = this.viewportW / 2f;
        this.cy = this.viewportH / 2f;
        update();
    }

    // ------------------------------------------------------------------
    // 参数设置
    // ------------------------------------------------------------------

    /**
     * 把镜头对准整张地图，让它刚好装进视口。
     *
     * <h2>为什么用「实测 + 迭代」而不是解析式或二分</h2>
     * 透视下地图投影成一个<strong>上窄下宽的梯形</strong>，屏幕占用是距离的非线性函数，
     * 手推换算很容易错（这里踩过两次：相机符号反了导致画面颠倒；
     * 二分搜索里因为存在共享状态，测出来的包围盒和最终状态对不上）。
     *
     * <p>所以改成最朴素也最可靠的办法：<strong>每一轮都真的把相机摆到候选参数上、
     * 投影一遍量出包围盒，再按实测结果修正</strong>。没有跨轮次的隐藏状态，
     * 每轮从同一份参数算出同一份结果。收敛很快（通常 3~4 轮）。
     *
     * @param cols     地图列数
     * @param rows     地图行数
     * @param maxRise  地图中最高的地形高度（格），把高出来的部分也算进包围盒
     * @param margin   视口内留出的边距比例，0.06 表示四周各留 6%
     */
    public void frameMap(int cols, int rows, float maxRise, float margin) {
        tilt = OFFICIAL_PITCH_DEG * DEG;
        sinT = (float) Math.sin(tilt);
        cosT = (float) Math.cos(tilt);
        cx = viewportW / 2f;
        cy = viewportH / 2f;

        // 初值：注视点放在地图中心。
        // ⚠️ 格 (col,row) 占 [col,col+1] × [row,row+1]，整张图的范围是 [0,cols] × [0,rows]，
        // 中心因此是 (cols/2, rows/2) —— **不是** (cols-1)/2。
        // 这里曾按"整数坐标即格心"写成 (cols-1)/2，取景与地形差半格。
        targetX = cols / 2f;
        targetY = rows / 2f;
        targetZ = 0f;
        dist = 16f;
        recompute();

        float availW = viewportW * (1f - margin * 2f);
        float availH = viewportH * (1f - margin * 2f);

        for (int pass = 0; pass < 24; pass++) {
            float[] b = screenBounds(cols, rows, maxRise);
            float w = b[2] - b[0];
            float h = b[3] - b[1];
            if (w <= 1f || h <= 1f) {
                break;
            }

            // ① 距离修正：投影尺寸与距离近似成反比，按实测比例缩放即可
            float fit = Math.min(availW / w, availH / h);
            if (Math.abs(fit - 1f) < 0.002f) {
                break; // 已经刚好装满，不必再动
            }
            dist = clampDist(dist / fit);
            recompute();

            // ② 居中修正：把包围盒中心的偏移按「注视点挪 1 格 = 画面动多少像素」回代
            b = screenBounds(cols, rows, maxRise);
            float offX = (b[0] + b[2]) / 2f - viewportW / 2f;
            float offY = (b[1] + b[3]) / 2f - viewportH / 2f;
            if (Math.abs(offX) < 0.5f && Math.abs(offY) < 0.5f) {
                continue;
            }
            float sensX = centerShiftPerStep(cols, rows, maxRise, 1f, 0f);
            float sensY = centerShiftPerStep(cols, rows, maxRise, 0f, 1f);
            if (Math.abs(sensX) > 1e-3f) {
                targetX -= offX / sensX;
            }
            if (Math.abs(sensY) > 1e-3f) {
                targetY -= offY / sensY;
            }
            recompute();
        }
    }

    /**
     * 把镜头对准整张地图（自动取常见边距）。
     *
     * @param cols 列数
     * @param rows 行数
     */
    public void frameMap(int cols, int rows) {
        frameMap(cols, rows, 0.45f, 0.06f);
    }

    /**
     * 在当前相机参数下，把地图投影到屏幕并求包围盒。
     *
     * <p>只看<strong>八个角</strong>：透视是射影变换，凸多面体的像由顶点决定，
     * 所以不必扫全部格子。当前参数会被原样保留（本方法只读）。
     *
     * @param cols    列数
     * @param rows    行数
     * @param maxRise 最高地形高度（格）
     * @return {@code [minX, minY, maxX, maxY]}
     */
    private float[] screenBounds(int cols, int rows, float maxRise) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float[] out = new float[4];

        for (int ci = 0; ci <= 1; ci++) {
            for (int ri = 0; ri <= 1; ri++) {
                for (float z : new float[]{0f, maxRise}) {
                    // 整张图的范围是 [0, cols] × [0, rows]（格 (col,row) 占 [col,col+1]）
                    project(ci == 0 ? 0f : cols,
                            ri == 0 ? 0f : rows, z, out);
                    minX = Math.min(minX, out[0]);
                    maxX = Math.max(maxX, out[0]);
                    minY = Math.min(minY, out[1]);
                    maxY = Math.max(maxY, out[1]);
                }
            }
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    /**
     * 探测「注视点挪 (dx, dy) 格后，包围盒中心移动多少像素」。
     *
     * <p>测完会把参数恢复原样。<strong>不能省这一步改成解析式</strong> ——
     * 屏幕位移与世界位移的换算跟深度有关，手推容易错（踩过）。
     *
     * @param dx x 方向探测位移（格）
     * @param dy y 方向探测位移（格）
     * @return {@code dx} 非 0 时返回中心的 x 位移，否则返回 y 位移（像素/格）
     */
    private float centerShiftPerStep(int cols, int rows, float maxRise,
                                     float dx, float dy) {
        float saveX = targetX;
        float saveY = targetY;

        float[] base = screenBounds(cols, rows, maxRise);
        float baseCx = (base[0] + base[2]) / 2f;
        float baseCy = (base[1] + base[3]) / 2f;

        targetX = saveX + dx;
        targetY = saveY + dy;
        recompute();
        float[] moved = screenBounds(cols, rows, maxRise);

        targetX = saveX;
        targetY = saveY;
        recompute();

        return dx != 0f
                ? ((moved[0] + moved[2]) / 2f - baseCx) / dx
                : ((moved[1] + moved[3]) / 2f - baseCy) / dy;
    }

    /**
     * 平移注视点（拖动地图）。
     *
     * @param dx 世界 x 位移（格）
     * @param dy 世界 y 位移（格）
     */
    public void panBy(float dx, float dy) {
        targetX += dx;
        targetY += dy;
        update();
    }

    /**
     * 改相机距离（缩放）。
     *
     * @param delta 正数拉远（缩小），负数拉近（放大）
     */
    public void zoomBy(float delta) {
        dist = clampDist(dist + delta);
        update();
    }

    /**
     * 设置俯角。
     *
     * @param degrees 俯角（度），会被夹在 10° ~ 89° 之间
     */
    public void setPitch(float degrees) {
        float d = Math.max(10f, Math.min(89f, degrees));
        tilt = d * DEG;
        update();
    }

    /**
     * 改视口尺寸（窗口缩放时调用）。
     *
     * @param w 新宽度
     * @param h 新高度
     */
    public void setViewport(int w, int h) {
        viewportW = Math.max(1, w);
        viewportH = Math.max(1, h);
        cx = viewportW / 2f;
        cy = viewportH / 2f;
        update();
    }

    private static float clampDist(float d) {
        return Math.max(MIN_DIST, Math.min(MAX_DIST, d));
    }

    /** 参数变化后重算派生量，并让版本号前进（渲染端据此判断要不要重投影）。 */
    private void update() {
        recompute();
        version++;
    }

    /**
     * 只重算派生量，<strong>不动版本号</strong>。
     *
     * <p>为什么必须分开：{@code frameMap} 里要做几十次数值试探，每次都调
     * {@link #recompute()}。若它顺手自增版本号，渲染端每帧都会以为相机变了而重投影 ——
     * 白白浪费，而且版本号失去意义。
     */
    private void recompute() {
        sinT = (float) Math.sin(tilt);
        cosT = (float) Math.cos(tilt);

        // 相机在注视点**南侧**（y 更大的一侧）上方。
        // ⚠️ 符号不能反：反了的话 row 大的格子会离相机更远，整个画面上下颠倒。
        camX = targetX;
        camY = targetY + dist * sinT;
        camZ = targetZ + dist * cosT;

        // 焦距：注视点处「一格 = scale 像素」。scale 由视口高与垂直视场角决定
        float visibleHeight = 2f * dist * (float) Math.tan(OFFICIAL_FOV_Y_DEG * DEG / 2f);
        float scale = viewportH / Math.max(1f, visibleHeight);
        focal = scale * dist;
    }

    // ------------------------------------------------------------------
    // 投影
    // ------------------------------------------------------------------

    /**
     * 该世界点沿视线方向的深度。
     *
     * @param x 世界 x（列）
     * @param y 世界 y（行）
     * @param z 世界 z（高度，格）
     * @return 深度，最小 {@code EPS}
     */
    public float depthOf(float x, float y, float z) {
        float vy = y - camY;
        float vz = z - camZ;
        // f = (0, −sin, −cos)：y 变小、z 变小为"往前"
        float d = -vy * sinT - vz * cosT;
        return d > EPS ? d : EPS;
    }

    /**
     * 把世界点投影到屏幕。
     *
     * @param x   世界 x（列，可为小数）
     * @param y   世界 y（行）
     * @param z   世界 z（高度，格）
     * @param out 结果写入的数组，长度至少 4：{@code [0]=屏幕x, [1]=屏幕y, [2]=该处每格像素数, [3]=深度}
     */
    public void project(float x, float y, float z, float[] out) {
        float vx = x - camX;
        float vy = y - camY;
        float vz = z - camZ;

        float depth = depthOf(x, y, z);
        // u = (0, −cos, +sin)：屏幕上方向
        float up = -vy * cosT + vz * sinT;
        float k = focal / depth;

        out[0] = cx + vx * k;
        out[1] = cy - up * k;
        out[2] = k;
        out[3] = depth;
    }

    /**
     * 屏幕点反投影到指定高度的平面（用于鼠标拾取）。
     *
     * @param screenX 屏幕 x
     * @param screenY 屏幕 y
     * @param z       目标平面高度（格），地面传 0
     * @param out     结果写入的数组，长度至少 2：{@code [0]=世界x, [1]=世界y}
     * @return 射线与该平面在相机前方相交时返回 {@code true}
     */
    public boolean unproject(float screenX, float screenY, float z, float[] out) {
        // 屏幕点对应的视线方向：d = f + a·r + b·u
        float a = (screenX - cx) / focal;
        float b = -(screenY - cy) / focal;

        float dx = a;
        float dy = -sinT - b * cosT;
        float dz = -cosT + b * sinT;
        if (Math.abs(dz) < EPS) {
            return false;
        }
        float t = (z - camZ) / dz;
        if (!(t > 0) || !Float.isFinite(t)) {
            return false;
        }
        out[0] = camX + t * dx;
        out[1] = camY + t * dy;
        return true;
    }

    /**
     * 某世界点处「一格 = 多少像素」。
     *
     * @param x 世界 x
     * @param y 世界 y
     * @param z 世界 z
     * @return 像素/格
     */
    public float scaleAt(float x, float y, float z) {
        return focal / depthOf(x, y, z);
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** @return 注视点 x（格） */
    public float targetX() {
        return targetX;
    }

    /** @return 注视点 y（格） */
    public float targetY() {
        return targetY;
    }

    /** @return 相机距离（格） */
    public float dist() {
        return dist;
    }

    /** @return 俯角（度） */
    public float pitchDegrees() {
        return tilt / DEG;
    }

    /** @return 参数版本号；每变一次 +1，供渲染端判断要不要重投影 */
    public int version() {
        return version;
    }

    @Override
    public String toString() {
        return String.format("Camera[target=(%.2f, %.2f) dist=%.2f pitch=%.1f° viewport=%dx%d focal=%.1f]",
                targetX, targetY, dist, pitchDegrees(), viewportW, viewportH, focal);
    }
}
