package com.yestercar.runtime.spine;

/**
 * 骨骼的 setup 姿态数据（不可变）。
 *
 * <p>{@code x}/{@code y}/{@code rotation}/{@code scaleX}/{@code scaleY}/{@code shearX}/{@code shearY}
 * 是<b>局部</b> setup 值。时间轴里的 rotate/translate/shear 关键帧是相对这些值的<b>增量</b>，
 * scale 关键帧是相对它的<b>倍数</b> —— 这一点是照官方运行时
 * {@code Animation$RotateTimeline.apply} 等方法的反汇编确认的（见 {@link SpineTimeline} 的注释）。
 */
final class SpineBone {

    /** 骨骼名（诊断用）。 */
    final String name;
    /** 父骨骼下标，根骨骼为 -1。 */
    final int parent;

    final float rotation;
    final float x;
    final float y;
    final float scaleX;
    final float scaleY;
    final float shearX;
    final float shearY;
    final float length;

    /**
     * 变换模式，对应 {@code BoneData.TransformMode} 的序号：
     * 0=normal，1=onlyTranslation，2=noRotationOrReflection，3=noScale，4=noScaleOrReflection。
     *
     * <p>本项目素材实测全部是 0（normal），其余模式按官方公式实现了 0 与 1，
     * 2/3/4 见 {@link SpinePose} 里的说明。
     */
    final int transformMode;

    SpineBone(String name, int parent, float rotation, float x, float y,
            float scaleX, float scaleY, float shearX, float shearY, float length, int transformMode) {
        this.name = name;
        this.parent = parent;
        this.rotation = rotation;
        this.x = x;
        this.y = y;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.shearX = shearX;
        this.shearY = shearY;
        this.length = length;
        this.transformMode = transformMode;
    }
}
