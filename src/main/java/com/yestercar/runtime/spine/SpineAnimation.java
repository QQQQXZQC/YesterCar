package com.yestercar.runtime.spine;

/**
 * 一个动画：名字 + 时长 + 一组时间轴。
 *
 * <p>时长不是文件里存的字段，而是<b>所有时间轴最后一帧时刻的最大值</b>（官方
 * {@code SkeletonBinary.readAnimation} 里 {@code duration = Math.max(duration, ...)} 的结果），
 * 所以只有 {@code Default} 这种"没有任何时间轴"的动画才是 0.000 —— 阿米娅的实测数据正是如此。
 */
final class SpineAnimation {

    final String name;
    final float duration;
    final SpineTimeline[] timelines;

    SpineAnimation(String name, float duration, SpineTimeline[] timelines) {
        this.name = name;
        this.duration = duration;
        this.timelines = timelines;
    }
}
