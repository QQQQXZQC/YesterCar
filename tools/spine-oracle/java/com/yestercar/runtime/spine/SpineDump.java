package com.yestercar.runtime.spine;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 本工程运行时的姿态转储 —— 输出格式与 {@code tools/spine-oracle/java/Oracle35} 完全一致。
 *
 * <p>这样"本工程 vs 官方运行时"可以直接逐行 diff（{@code tools/spine-oracle/diff.ps1}）。
 * 放在 {@code com.yestercar.runtime.spine} 包里是为了能读包内字段（骨骼局部值、每插槽
 * 当前附件、约束 mix 状态）—— 这些都是诊断必需、但不该做成公开 API 的东西。
 *
 * <p>用法（先 {@code mvn package}）：
 * <pre>
 * java -cp "tools/spine-oracle/out;target/classes" com.yestercar.runtime.spine.SpineDump \
 *      data/spine/operator/texas Idle 0.5
 * </pre>
 */
public final class SpineDump {

    /** @return 与官方 oracle 一致的附件类型名（Mesh / Region / Other） */    private static String typeName(SpineAttachment a) {
        if (a.type == SpineAttachment.REGION) {
            return "Region";
        }
        if (a.type == SpineAttachment.MESH) {
            return "Mesh";
        }
        return "Other";
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String anim = args[1];
        float t = Float.parseFloat(args[2]);

        SpineData data = SpineData.load(dir);
        SpinePose pose = new SpinePose(data);
        boolean setup = "setup".equals(anim);
        if (!setup) {
            pose.play(anim, false);
            pose.setTime(t);
        }
        MeshBuffer buf = new MeshBuffer();
        pose.fill(buf);

        System.out.println("ANIM " + (setup ? "(none)" : anim) + " t=" + t
                + " dur=" + (setup ? -1f : data.duration(anim)));
        System.out.println("SLOTS " + data.slotCount());
        for (int i = 0; i < data.slotCount(); i++) {
            SpineAttachment a = pose.slotAttachment[i];
            System.out.println(String.format(Locale.ROOT, "S %d %s %s a=%.3f %s",
                    i, data.slots[i].name, a == null ? "-" : a.name, pose.slotColor[3][i],
                    a == null ? "Other" : typeName(a)));
        }
        System.out.println("BONES " + data.boneCount());
        for (int i = 0; i < data.boneCount(); i++) {
            System.out.println(String.format(Locale.ROOT, "B %d %s %.3f %.3f %.3f",
                    i, data.bones[i].name, pose.worldX(i), pose.worldY(i), pose.worldRotation(i)));
        }
        System.out.println("LOCAL " + data.boneCount());
        for (int i = 0; i < data.boneCount(); i++) {
            System.out.println(String.format(Locale.ROOT, "L %d %s %.3f %.3f %.3f %.3f %.3f %.3f %.3f",
                    i, data.bones[i].name, pose.boneRotation[i], pose.boneX[i], pose.boneY[i],
                    pose.boneScaleX[i], pose.boneScaleY[i], pose.boneShearX[i], pose.boneShearY[i]));
        }
        for (int i = 0; i < data.ik().length; i++) {
            SpineConstraints.Ik c = data.ik()[i];
            System.out.println(String.format(Locale.ROOT, "IK %d %s mix=%.4f bend=%d",
                    c.index, c.name, pose.ikMix[c.index], pose.ikBendDir[c.index]));
        }
        for (int i = 0; i < data.transforms().length; i++) {
            SpineConstraints.Transform c = data.transforms()[i];
            int b = c.index * 4;
            System.out.println(String.format(Locale.ROOT,
                    "TC %d %s rot=%.4f trans=%.4f scale=%.4f shear=%.4f",
                    c.index, c.name, pose.tcMix[b], pose.tcMix[b + 1],
                    pose.tcMix[b + 2], pose.tcMix[b + 3]));
        }

        // 每插槽顶点数（与官方 oracle 的 C 行同序：按插槽下标，不是绘制顺序）
        for (int i = 0; i < data.slotCount(); i++) {
            SpineAttachment a = pose.slotAttachment[i];
            System.out.println("C " + i + " "
                    + (a == null || !a.renderable() ? 0 : a.vertexCount));
        }

        // 绘制顺序（drawOrder 时间轴的产物）
        Field orderField = SpinePose.class.getDeclaredField("drawOrder");
        orderField.setAccessible(true);
        int[] order = (int[]) orderField.get(pose);
        StringBuilder dord = new StringBuilder("DRAWORDER");
        for (int i : order) {
            dord.append(' ').append(i);
        }
        System.out.println(dord);

        // 每插槽的世界顶点（V 行）：按绘制顺序把缓冲切片还原成"每个插槽自己的顶点"。
        // 这是查"面部/服饰被拉花"的关键 —— 骨骼全对但顶点差很多，就一定是蒙皮或 deform。
        float[] xy = buf.xy();
        int cursor = 0;
        for (int orderIndex = 0; orderIndex < order.length; orderIndex++) {
            int slot = order[orderIndex];
            SpineAttachment a = pose.slotAttachment[slot];
            if (a == null || !a.renderable()) {
                continue;
            }
            int n = a.vertexCount;
            StringBuilder sb = new StringBuilder();
            sb.append("V ").append(slot).append(' ').append(n);
            for (int v = 0; v < n; v++) {
                sb.append(' ').append(String.format(Locale.ROOT, "%.3f,%.3f",
                        xy[(cursor + v) * 2], xy[(cursor + v) * 2 + 1]));
            }
            cursor += n;
            System.out.println(sb);
        }

        List<String> coords = new ArrayList<>();
        for (int i = 0; i < buf.vertexCount(); i++) {
            coords.add(String.format(Locale.ROOT, "%.3f,%.3f", xy[i * 2], xy[i * 2 + 1]));
        }
        Collections.sort(coords);
        System.out.println("VALL " + coords.size());
        System.out.println(String.join(" ", coords));
    }
}
