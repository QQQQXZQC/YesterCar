package com.yestercar.runtime.spine;

/**
 * 时间轴基类 + 本项目支持的全部时间轴实现。
 *
 * <h2>时间轴里存的是「相对 setup 的量」，不是绝对值</h2>
 * 这一点最容易错，是照官方 {@code Animation$RotateTimeline.apply} /
 * {@code TranslateTimeline.apply} / {@code ScaleTimeline.apply} 的反汇编确认的：
 * <pre>
 * // rotate（含最短路径归一到 (-180,180] 再叠加）
 * bone.rotation = bone.data.rotation + value;
 * // translate
 * bone.x        = bone.data.x + x;
 * bone.y        = bone.data.y + y;
 * // scale（乘子，1.0 == setup 的 scale）
 * bone.scaleX   = bone.data.scaleX * valueX;
 * bone.scaleY   = bone.data.scaleY * valueY;
 * // shear
 * bone.shearX   = bone.data.shearX + valueX;
 * bone.shearY   = bone.data.shearY + valueY;
 * </pre>
 *
 * <h2>支持范围</h2>
 * <ul>
 *   <li>支持：rotate / translate / scale / shear / attachment / color(rgba) /
 *       twoColor 的 rgba 部分 / deform / drawOrder；</li>
 *   <li>IK、transform、path 三类约束的时间轴<b>只跳过不应用</b>（解析时按字段宽度正确读掉，
 *       保证后面字节不错位）。这三类约束本身也没实现 —— 见 {@link SpineData} 类注释。</li>
 * </ul>
 */
abstract class SpineTimeline {

    /** 关键帧时间（升序）。 */
    final float[] frames;
    /** 每帧 19 个 float 的曲线表（见 {@link SpineCurves}）。 */
    final float[] curves;

    SpineTimeline(float[] frames, float[] curves) {
        this.frames = frames;
        this.curves = curves;
    }

    /** 在本帧时刻把值写进姿态。 */
    abstract void apply(SpinePose pose, float time);

    /** 最后一个 {@code frames[i] <= time} 的下标；{@code time < frames[0]} 返回 -1。 */
    final int segment(float time) {
        int n = frames.length;
        if (n == 0 || time < frames[0]) {
            return -1;
        }
        if (time >= frames[n - 1]) {
            return n - 1;
        }
        int low = 0;
        int high = n - 1;
        while (low < high - 1) {
            int mid = (low + high) >>> 1;
            if (frames[mid] <= time) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** 段内比例（已过曲线）。 */
    final float percentAt(int index, float time) {
        float t0 = frames[index];
        float t1 = frames[index + 1];
        if (t1 <= t0) {
            return 0f;
        }
        return SpineCurves.percent(curves, index, (time - t0) / (t1 - t0));
    }

    private static float[] defaultCurves(int frameCount) {
        return new float[frameCount * SpineCurves.STRIDE];
    }

    // ------------------------------------------------------------------
    // 骨骼通道：rotate
    // ------------------------------------------------------------------

    /** rotate 时间轴（值是"相对 setup 的角度增量"）。 */
    static final class Rotate extends SpineTimeline {
        private final int bone;
        private final float[] values;

        Rotate(int bone, float[] frames, float[] values, float[] curves) {
            super(frames, curves != null ? curves : defaultCurves(frames.length));
            this.bone = bone;
            this.values = values;
        }

        @Override
        void apply(SpinePose pose, float time) {
            int n = frames.length;
            if (n == 0 || time < frames[0]) {
                return;
            }
            float value;
            if (time >= frames[n - 1]) {
                value = values[n - 1];
            } else {
                int i = segment(time);
                float percent = percentAt(i, time);
                float delta = values[i + 1] - values[i];
                // 走最短路径：差值归一到 (-180, 180]
                if (delta > 180f) {
                    delta -= 360f;
                } else if (delta < -180f) {
                    delta += 360f;
                }
                value = values[i] + delta * percent;
                // 再归一到 (-180, 180]，避免叠加 setup 后转出好几圈
                value -= 360f * Math.round(value / 360f);
            }
            pose.boneRotation[bone] = pose.data.bones[bone].rotation + value;
        }
    }

    // ------------------------------------------------------------------
    // 骨骼通道：translate / scale / shear（共用 x/y 双通道结构）
    // ------------------------------------------------------------------

    /** x/y 双通道骨骼时间轴的种类。 */
    enum BoneKind {
        /** 位移：加法叠加。 */
        TRANSLATE,
        /** 缩放：乘法叠加（1.0 == setup）。 */
        SCALE,
        /** 切变：加法叠加。 */
        SHEAR
    }

    /** translate / scale / shear 时间轴。 */
    static final class BoneXy extends SpineTimeline {
        private final int bone;
        private final BoneKind kind;
        private final float[] values;

        BoneXy(int bone, BoneKind kind, float[] frames, float[] values, float[] curves) {
            super(frames, curves != null ? curves : defaultCurves(frames.length));
            this.bone = bone;
            this.kind = kind;
            this.values = values;
        }

        @Override
        void apply(SpinePose pose, float time) {
            int n = frames.length;
            if (n == 0 || time < frames[0]) {
                return;
            }
            float x;
            float y;
            if (time >= frames[n - 1]) {
                x = values[(n - 1) * 2];
                y = values[(n - 1) * 2 + 1];
            } else {
                int i = segment(time);
                float percent = percentAt(i, time);
                float x0 = values[i * 2];
                float y0 = values[i * 2 + 1];
                x = x0 + (values[(i + 1) * 2] - x0) * percent;
                y = y0 + (values[(i + 1) * 2 + 1] - y0) * percent;
            }
            SpineBone data = pose.data.bones[bone];
            switch (kind) {
                case TRANSLATE:
                    pose.boneX[bone] = data.x + x;
                    pose.boneY[bone] = data.y + y;
                    break;
                case SCALE:
                    pose.boneScaleX[bone] = data.scaleX * x;
                    pose.boneScaleY[bone] = data.scaleY * y;
                    break;
                case SHEAR:
                default:
                    pose.boneShearX[bone] = data.shearX + x;
                    pose.boneShearY[bone] = data.shearY + y;
                    break;
            }
        }
    }

    // ------------------------------------------------------------------
    // 插槽：attachment
    // ------------------------------------------------------------------

    /** 换附件（第一帧之前视为"不画"，与官方一致）。 */
    static final class Attachment extends SpineTimeline {
        private final int slot;
        private final String[] names;

        Attachment(int slot, float[] frames, String[] names) {
            super(frames, defaultCurves(frames.length));
            this.slot = slot;
            this.names = names;
        }

        /** 目标插槽（{@link SpineData} 估算最大顶点数时要用）。 */
        int slotIndex() {
            return slot;
        }

        /** 该时间轴点到的所有附件名。 */
        String[] names() {
            return names;
        }

        @Override
        void apply(SpinePose pose, float time) {
            if (frames.length == 0) {
                return;
            }
            int i = segment(time);
            if (i < 0) {
                // 首帧之前：官方是"什么都不做"（保留 setup 姿态挂的附件），
                // 不是"把插槽清空"。写 null 会让整只干员少零件 —— 实测 cuora 的两只眼睛、
                // blaze/april/myrtle/reed 的 40–48 个顶点就是这么丢的。
                return;
            }
            String name = names[i];
            pose.slotAttachment[slot] = name == null ? null : pose.data.attachment(slot, name);
        }
    }

    // ------------------------------------------------------------------
    // 插槽：颜色
    // ------------------------------------------------------------------

    /** 颜色时间轴（直接写 RGBA 绝对值，乘到插槽颜色上）。 */
    static final class Color extends SpineTimeline {
        private final int slot;
        private final float[] rgba;

        Color(int slot, float[] frames, float[] rgba, float[] curves) {
            super(frames, curves != null ? curves : defaultCurves(frames.length));
            this.slot = slot;
            this.rgba = rgba;
        }

        @Override
        void apply(SpinePose pose, float time) {
            int n = frames.length;
            if (n == 0 || time < frames[0]) {
                return;
            }
            if (time >= frames[n - 1]) {
                write(pose, (n - 1) * 4);
                return;
            }
            int i = segment(time);
            float percent = percentAt(i, time);
            for (int c = 0; c < 4; c++) {
                float a = rgba[i * 4 + c];
                float b = rgba[(i + 1) * 4 + c];
                pose.slotColor[c][slot] = a + (b - a) * percent;
            }
        }

        private void write(SpinePose pose, int base) {
            for (int c = 0; c < 4; c++) {
                pose.slotColor[c][slot] = rgba[base + c];
            }
        }
    }

    // ------------------------------------------------------------------
    // 网格形变
    // ------------------------------------------------------------------

    /**
     * 形变时间轴。
     *
     * <p>关键帧里存的是"整份 deform 数组"（长度见 {@link SpineAttachment#deformLength}）：
     * 无权重网格已经是"基准 + 偏移"，有权重网格是每根骨骼两个偏移量。
     * 这跟官方 {@code SkeletonBinary.readAnimation} 里读 deform 的做法一致 —— 它在解析期就把
     * 基准值加进去了，运行时只做数组插值。
     */
    static final class Deform extends SpineTimeline {
        private final int slot;
        private final SpineAttachment attachment;
        private final float[][] deforms;

        Deform(int slot, SpineAttachment attachment, float[] frames, float[][] deforms, float[] curves) {
            super(frames, curves != null ? curves : defaultCurves(frames.length));
            this.slot = slot;
            this.attachment = attachment;
            this.deforms = deforms;
        }

        @Override
        void apply(SpinePose pose, float time) {
            // 附件被换掉了就不该再套这份形变
            if (pose.slotAttachment[slot] != attachment) {
                return;
            }
            int n = frames.length;
            if (n == 0 || time < frames[0]) {
                return;
            }
            if (time >= frames[n - 1]) {
                pose.slotDeform[slot] = deforms[n - 1];
                return;
            }
            int i = segment(time);
            float percent = percentAt(i, time);
            float[] a = deforms[i];
            float[] b = deforms[i + 1];
            float[] out = pose.deformScratch(slot, a.length);
            float q = percent;
            for (int k = 0; k < out.length; k++) {
                float v0 = a[k];
                out[k] = v0 + (b[k] - v0) * q;
            }
            pose.slotDeform[slot] = out;
        }
    }

    // ------------------------------------------------------------------
    // 约束时间轴（IK / transform）
    // ------------------------------------------------------------------

    /**
     * IK 约束时间轴：逐帧改 {@code mix} 与 {@code bendDirection}。
     *
     * <p><b>为什么必须有它</b>：方舟把"约束强度"也做成动画。setup 里的 {@code mix=0} 只表示
     * "静止时约束不生效"，而 Idle/Attack 里往往有关键帧把它设成 1 ——
     * 只读 setup 的值会让武器/烟斗/触手永远停在 setup 位置（实测：德克萨斯 F_pocky 偏 152 单位、
     * F_Weapon 偏 52 单位，正是"看起来乱"的主要来源）。
     *
     * <p>{@code mix} 走曲线插值；{@code bendDirection} 是整数，取当前段左端点的值（官方同样不插值）。
     */
    static final class Ik extends SpineTimeline {
        private final int index;
        private final float[] mix;
        private final int[] bend;

        Ik(int index, float[] frames, float[] mix, int[] bend, float[] curves) {
            super(frames, curves != null ? curves : defaultCurves(frames.length));
            this.index = index;
            this.mix = mix;
            this.bend = bend;
        }

        @Override
        void apply(SpinePose pose, float time) {
            int n = frames.length;
            if (n == 0 || time < frames[0]) {
                return;
            }
            if (time >= frames[n - 1]) {
                pose.ikMix[index] = mix[n - 1];
                pose.ikBendDir[index] = bend[n - 1];
                return;
            }
            int i = segment(time);
            float percent = percentAt(i, time);
            pose.ikMix[index] = mix[i] + (mix[i + 1] - mix[i]) * percent;
            pose.ikBendDir[index] = bend[i];
        }
    }

    /**
     * transform 约束时间轴：一帧同时给 rotateMix / translateMix / scaleMix / shearMix 四个通道
     * （二进制格式就是四值一组，与官方 {@code TransformConstraintTimeline} 一致）。
     */
    static final class TransformMix extends SpineTimeline {
        private final int index;
        private final float[] rot;
        private final float[] trans;
        private final float[] scale;
        private final float[] shear;

        TransformMix(int index, float[] frames, float[] rot, float[] trans, float[] scale, float[] shear,
                float[] curves) {
            super(frames, curves != null ? curves : defaultCurves(frames.length));
            this.index = index;
            this.rot = rot;
            this.trans = trans;
            this.scale = scale;
            this.shear = shear;
        }

        @Override
        void apply(SpinePose pose, float time) {
            int n = frames.length;
            if (n == 0 || time < frames[0]) {
                return;
            }
            int base = index * 4;
            if (time >= frames[n - 1]) {
                pose.tcMix[base] = rot[n - 1];
                pose.tcMix[base + 1] = trans[n - 1];
                pose.tcMix[base + 2] = scale[n - 1];
                pose.tcMix[base + 3] = shear[n - 1];
                return;
            }
            int i = segment(time);
            float percent = percentAt(i, time);
            pose.tcMix[base] = rot[i] + (rot[i + 1] - rot[i]) * percent;
            pose.tcMix[base + 1] = trans[i] + (trans[i + 1] - trans[i]) * percent;
            pose.tcMix[base + 2] = scale[i] + (scale[i + 1] - scale[i]) * percent;
            pose.tcMix[base + 3] = shear[i] + (shear[i + 1] - shear[i]) * percent;
        }
    }

    // ------------------------------------------------------------------
    // 绘制顺序
    // ------------------------------------------------------------------

    /** 绘制顺序时间轴（本项目素材里基本没有，但读到了就照做）。 */
    static final class DrawOrder extends SpineTimeline {
        private final int[][] orders;

        DrawOrder(float[] frames, int[][] orders) {
            super(frames, defaultCurves(frames.length));
            this.orders = orders;
        }

        @Override
        void apply(SpinePose pose, float time) {
            if (frames.length == 0 || time < frames[0]) {
                return;
            }
            pose.setDrawOrder(orders[segment(time)]);
        }
    }
}
