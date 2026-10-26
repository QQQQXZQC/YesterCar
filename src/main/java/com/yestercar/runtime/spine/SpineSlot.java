package com.yestercar.runtime.spine;

/**
 * 插槽（绘制顺序 = 插槽顺序）的 setup 数据。
 *
 * <p>插槽本身不画东西：它把「一根骨骼」和「一个附件」绑在一起，并提供一个颜色乘子。
 * 绘制顺序就是插槽在文件里的顺序（没有 drawOrder 时间轴时），
 * 所以 {@link SpinePose#fill(MeshBuffer)} 必须按这个顺序输出三角形。
 */
final class SpineSlot {

    /** 插槽名（诊断用）。 */
    final String name;
    /** 绑定的骨骼下标。 */
    final int bone;

    /** setup 颜色（0..1）。 */
    final float r;
    final float g;
    final float b;
    final float a;

    /** setup 姿态挂的附件名，可能为 {@code null}（该插槽默认不画东西）。 */
    final String attachmentName;

    /** blend mode 序号（0=normal，其余本项目素材未使用）。 */
    final int blendMode;

    SpineSlot(String name, int bone, float r, float g, float b, float a, String attachmentName, int blendMode) {
        this.name = name;
        this.bone = bone;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        this.attachmentName = attachmentName;
        this.blendMode = blendMode;
    }
}
