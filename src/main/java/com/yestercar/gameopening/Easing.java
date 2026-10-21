package com.yestercar.gameopening;

/**
 * 三次贝塞尔缓动 —— ak-ui 的 {@code cubic-bezier(...)} 在 Java 里的对应实现。
 *
 * <h2>为什么需要它</h2>
 * ak-ui 的动效全部指定了贝塞尔曲线，而且这些数值是**设计决定**不是随手写的：
 *
 * <table border="1">
 *   <caption>ak-ui 用到的曲线</caption>
 *   <tr><th>出处</th><th>曲线</th><th>用途</th></tr>
 *   <tr><td>{@code --ak-ease-standard}</td>
 *       <td>{@code cubic-bezier(0.2, 0, 0, 1)}</td>
 *       <td>通用状态切换（hover / press）</td></tr>
 *   <tr><td>{@code --ak-ease-emphasized}</td>
 *       <td>{@code cubic-bezier(0.2, 0.8, 0.2, 1)}</td>
 *       <td>入场、强调</td></tr>
 *   <tr><td>{@code .ak-command:hover}</td>
 *       <td>{@code cubic-bezier(0.22, 1, 0.36, 1)}</td>
 *       <td>命令卡的位移</td></tr>
 *   <tr><td>{@code .ak-terminal-mark}</td>
 *       <td>{@code cubic-bezier(0.16, 1, 0.3, 1)}</td>
 *       <td>终端的标记入场</td></tr>
 * </table>
 *
 * <p>用线性插值代替它们，动效会"软"下来 —— 那正是"不像 ak-ui"的一个主要来源。
 * 这里的 {@code .22,1,.36,1} / {@code .16,1,.3,1} 属于"强出场"曲线：
 * 前段冲得快、后段稳稳落位，读起来是机械感而不是弹跳感。
 *
 * <h2>实现方式</h2>
 * CSS 的 cubic-bezier 定义的是"时间 → 进度"的映射：给定 x（时间比例）求 y（进度）。
 * 三次贝塞尔是参数方程，所以要先<b>牛顿迭代</b>从 x 反解出参数 t，再代回去求 y。
 * 这里做了 8 次迭代 + 二分兜底 —— 对动效来说精度远远够。
 */
public final class Easing {

    // ---- 与 ak-ui 的 token 一一对应 ----

    /** {@code --ak-ease-standard: cubic-bezier(0.2, 0, 0, 1)} */
    public static final Easing STANDARD = new Easing(0.2f, 0f, 0f, 1f);

    /** {@code --ak-ease-emphasized: cubic-bezier(0.2, 0.8, 0.2, 1)} */
    public static final Easing EMPHASIZED = new Easing(0.2f, 0.8f, 0.2f, 1f);

    /** {@code .ak-command} 的 {@code transition: transform 280ms cubic-bezier(0.22, 1, 0.36, 1)} */
    public static final Easing COMMAND_MOVE = new Easing(0.22f, 1f, 0.36f, 1f);

    /** {@code .ak-terminal-mark} 的 {@code cubic-bezier(0.16, 1, 0.3, 1)} */
    public static final Easing TERMINAL = new Easing(0.16f, 1f, 0.3f, 1f);

    /** 线性（少数地方确实要线性，比如尘埃漂移）。 */
    public static final Easing LINEAR = new Easing(0f, 0f, 1f, 1f);

    private final float x1;
    private final float y1;
    private final float x2;
    private final float y2;

    /**
     * @param x1 第一个控制点的 x（0~1）
     * @param y1 第一个控制点的 y
     * @param x2 第二个控制点的 x（0~1）
     * @param y2 第二个控制点的 y
     */
    public Easing(float x1, float y1, float x2, float y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    /**
     * 求某个时间比例处的进度。
     *
     * @param x 时间比例 0~1（超出会被夹）
     * @return 进度 0~1
     */
    public float at(float x) {
        if (x <= 0f) {
            return 0f;
        }
        if (x >= 1f) {
            return 1f;
        }
        return bezier(solveT(x), y1, y2);
    }

    /** 三次贝塞尔的分量式：起点 0、终点 1。 */
    private static float bezier(float t, float p1, float p2) {
        float u = 1f - t;
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t;
    }

    /**
     * 从 x 反解参数 t。
     *
     * <p>先牛顿迭代（快），不收敛再用二分（稳）。
     */
    private float solveT(float x) {
        float t = x;        // 以 x 为初值：对这两条曲线收敛很快
        for (int i = 0; i < 8; i++) {
            float current = bezier(t, x1, x2) - x;
            if (Math.abs(current) < 1e-5f) {
                return t;
            }
            float slope = bezierSlope(t, x1, x2);
            if (Math.abs(slope) < 1e-6f) {
                break;      // 斜率太小，牛顿法会飞出去，交给二分
            }
            t -= current / slope;
        }

        // 二分兜底
        float lo = 0f;
        float hi = 1f;
        t = x;
        for (int i = 0; i < 24; i++) {
            float mid = (lo + hi) / 2f;
            if (bezier(mid, x1, x2) < x) {
                lo = mid;
            } else {
                hi = mid;
            }
            t = mid;
        }
        return t;
    }

    /** 三次贝塞尔在 t 处的导数。 */
    private static float bezierSlope(float t, float p1, float p2) {
        float u = 1f - t;
        return 3f * u * u * p1 + 6f * u * t * (p2 - p1) + 3f * t * t * (1f - p2);
    }

    /**
     * 按毫秒计的一段动画进度。
     *
     * @param elapsedMs 已经过毫秒
     * @param durationMs 总时长
     * @return 缓动后的进度 0~1
     */
    public float progress(float elapsedMs, float durationMs) {
        if (durationMs <= 0f) {
            return 1f;
        }
        return at(elapsedMs / durationMs);
    }

    /**
     * 延迟 + 时长的一段动画进度（ak-ui 的 {@code animation-delay}）。
     *
     * <p>⚠️ {@code durationMs <= 0} 要单独处理，否则会除以 0。
     * 语义是：<b>延迟已经走完</b>才谈"立刻完成"；延迟都没到就仍是 0。
     * 所以判断顺序是"先看延迟有没有过"，而不是先看时长。
     *
     * @param elapsedMs  已经过毫秒
     * @param delayMs    延迟毫秒
     * @param durationMs 时长毫秒
     * @return 缓动后的进度 0~1；还没到延迟时返回 0
     */
    public float progress(float elapsedMs, float delayMs, float durationMs) {
        if (elapsedMs < delayMs) {
            return 0f;                  // 延迟未到
        }
        if (durationMs <= 0f) {
            return 1f;                  // 延迟已过且时长为 0 → 立刻完成
        }
        return progress(elapsedMs - delayMs, durationMs);
    }

    /**
     * 延迟 + 时长，且**帧尚未开始**时的语义。
     *
     * <p>与上面那个的区别只有一处：这里的 0 表示"这一帧还没起步"，
     * 所以三者全为 0 时返回 0 而不是 1。
     * 动画时钟在 {@code millis() - startMillis} 处会正好碰到这种情况。
     *
     * @param elapsedMs  已经过毫秒
     * @param delayMs    延迟毫秒
     * @param durationMs 时长毫秒
     * @return 进度 0~1
     */
    public float progressFromStart(float elapsedMs, float delayMs, float durationMs) {
        if (elapsedMs <= 0f) {
            return 0f;                  // 还没起步
        }
        return progress(elapsedMs, delayMs, durationMs);
    }
}
