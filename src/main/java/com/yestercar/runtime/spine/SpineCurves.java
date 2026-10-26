package com.yestercar.runtime.spine;

/**
 * 关键帧曲线（linear / stepped / bezier）。
 *
 * <p>3.5 与 3.8 的<b>文件里都是一样的编码</b>：每个关键帧（最后一帧除外）后面跟 1 个字节
 * 曲线类型 + 可选 4 个 float 控制点。反汇编 {@code SkeletonBinary.readCurve} 在两个版本里完全相同：
 * <pre>
 * switch (input.readByte()) {
 *   case 1: setStepped(frame);      // 阶跃
 *   case 2: setCurve(frame, cx1, cy1, cx2, cy2);   // 贝塞尔，依次读 4 个 float
 *   default: 线性，不读额外数据
 * }
 * </pre>
 *
 * <p>运行时同样照官方 {@code Animation$CurveTimeline} 的做法：<b>解析期把三次贝塞尔预采样成
 * 19 个 float 的查找表</b>（1 个类型位 + 9 组 x/y 采样点），求值时在这张表上线性插值。
 * 这样既与官方逐位一致，又避免每帧解三次方程。
 */
final class SpineCurves {

    /** 每帧曲线数据的长度（照抄官方 19）。 */
    static final int STRIDE = 19;

    static final int LINEAR = 0;
    static final int STEPPED = 1;
    static final int BEZIER = 2;

    private SpineCurves() {
    }

    /**
     * 照官方 {@code CurveTimeline.setCurve} 的向前差分采样，把三次贝塞尔写进曲线表。
     *
     * @param curves     曲线表（长度 = 帧数 * {@link #STRIDE}）
     * @param frameIndex 帧下标
     */
    static void bezier(float[] curves, int frameIndex,
            float cx1, float cy1, float cx2, float cy2) {
        // 一次差分/二次差分的初值（0.03 与 0.006 是官方写死的步长系数：s 从 0 到 1 取 10 段）
        float cx = (cx2 - 2f * cx1) * 0.03f;
        float cy = (cy2 - 2f * cy1) * 0.03f;
        float dx = ((cx1 - cx2) * 3f + 1f) * 0.006f;
        float dy = ((cy1 - cy2) * 3f + 1f) * 0.006f;
        float ddx = cx * 2f + dx;
        float ddy = cy * 2f + dy;
        float x = cx1 * 0.3f + cx + dx * 0.16666667f;
        float y = cy1 * 0.3f + cy + dy * 0.16666667f;

        int i = frameIndex * STRIDE;
        curves[i] = BEZIER;
        i++;
        float sumX = x;
        float sumY = y;
        int end = i + STRIDE - 1;
        while (i < end) {
            curves[i] = sumX;
            curves[i + 1] = sumY;
            x += ddx;
            ddx += dx;
            y += ddy;
            ddy += dy;
            sumX += x;
            sumY += y;
            i += 2;
        }
    }

    /** 标记为阶跃。 */
    static void stepped(float[] curves, int frameIndex) {
        curves[frameIndex * STRIDE] = STEPPED;
    }

    /**
     * 取曲线在 percent 处的值（percent 是「本段内的时间比例」）。
     *
     * @param curves     曲线表
     * @param frameIndex 本段起始帧下标
     * @param percent    0..1
     * @return 曲线映射后的比例 0..1
     */
    static float percent(float[] curves, int frameIndex, float percent) {
        float p = percent;
        if (p < 0f) {
            p = 0f;
        } else if (p > 1f) {
            p = 1f;
        }
        int i = frameIndex * STRIDE;
        int type = (int) curves[i];
        if (type == LINEAR) {
            return p;
        }
        if (type == STEPPED) {
            return 0f;
        }
        // 贝塞尔：在 9 个采样点上按 x 线性插值
        i++;
        int first = i;
        int end = i + STRIDE - 1;
        float lowX = 0f;
        while (i < end) {
            float curveX = curves[i];
            if (curveX >= p) {
                float px;
                float py;
                if (i == first) {
                    px = 0f;
                    py = 0f;
                } else {
                    px = curves[i - 2];
                    py = curves[i - 1];
                }
                return py + (curves[i + 1] - py) * (p - px) / (curveX - px);
            }
            lowX = curveX;
            i += 2;
        }
        float py = curves[i - 1];
        if (lowX >= 1f) {
            return py;
        }
        return py + (1f - py) * (p - lowX) / (1f - lowX);
    }
}
