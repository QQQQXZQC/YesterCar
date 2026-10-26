import com.badlogic.gdx.files.FileHandle;
import com.esotericsoftware.spine.Animation;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationStateData;
import com.esotericsoftware.spine.Bone;
import com.esotericsoftware.spine.Skeleton;
import com.esotericsoftware.spine.SkeletonBinary;
import com.esotericsoftware.spine.SkeletonData;
import com.esotericsoftware.spine.Skin;
import com.esotericsoftware.spine.Slot;
import com.esotericsoftware.spine.attachments.Attachment;
import com.esotericsoftware.spine.attachments.AttachmentLoader;
import com.esotericsoftware.spine.attachments.BoundingBoxAttachment;
import com.esotericsoftware.spine.attachments.MeshAttachment;
import com.esotericsoftware.spine.attachments.PathAttachment;
import com.esotericsoftware.spine.attachments.RegionAttachment;
import com.esotericsoftware.spine.attachments.VertexAttachment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 官方 oracle（spine-libgdx 3.5.51.1）：把同一份 .skel 在指定动画/时刻的姿态导成文本。
 *
 * <p>用法：{@code java -cp "<spine-libgdx-3.5.51.1.jar>;<gdx-1.10.0.jar>;out" Oracle35 <skel> <anim> <秒>}
 *
 * <p>输出与 {@code com.yestercar.runtime.spine.SpineDump} 完全同格式，直接 diff 即可：
 * <pre>
 * ANIM Idle t=0.5 dur=2.0
 * SLOTS 53
 * S 0 F_Ponytail F_Ponytail a=1.000
 * C 0 32                     ← 该插槽顶点数
 * IK 0 Ik_F_L_Hand mix=1.0000 bend=1
 * TC 0 Pocky rot=0.0000 ...
 * BONES 67
 * B 0 root 0.000 0.000 0.000 ← 世界坐标 + 世界旋转
 * LOCAL 67
 * L 0 root ...               ← 局部变换
 * VALL 352
 * x,y x,y ...                ← 全部世界顶点（已排序）
 * </pre>
 *
 * <p>贴图不需要：{@code AttachmentLoader} 自己造附件，骨骼/网格几何不依赖贴图。
 * 代价是 {@code RegionAttachment} 没有 region，会产出 ±MAX/2 的假顶点，比对时要剔掉
 * （{@code tools/spine-oracle/diff.ps1} 已处理）。
 */
public class Oracle35 {

    static final class Loader implements AttachmentLoader {
        public RegionAttachment newRegionAttachment(Skin skin, String name, String path) {
            RegionAttachment a = new RegionAttachment(name);
            a.setPath(path);
            return a;
        }

        public MeshAttachment newMeshAttachment(Skin skin, String name, String path) {
            MeshAttachment a = new MeshAttachment(name);
            a.setPath(path);
            return a;
        }

        public BoundingBoxAttachment newBoundingBoxAttachment(Skin skin, String name) {
            return new BoundingBoxAttachment(name);
        }

        public PathAttachment newPathAttachment(Skin skin, String name) {
            return new PathAttachment(name);
        }
    }

    public static void main(String[] args) throws Exception {
        String skelPath = args[0];
        String anim = args[1];
        float t = Float.parseFloat(args[2]);

        SkeletonData data = new SkeletonBinary(new Loader())
                .readSkeletonData(new FileHandle(new File(skelPath)));

        Skeleton skeleton = new Skeleton(data);
        Animation a = data.findAnimation(anim);
        if (a != null) {
            skeleton.setToSetupPose();
            AnimationStateData asd = new AnimationStateData(data);
            asd.setDefaultMix(0f);
            AnimationState st = new AnimationState(asd);
            st.setAnimation(0, anim, false);
            st.update(Math.max(0f, t));
            st.apply(skeleton);
        }
        skeleton.updateWorldTransform();

        System.out.println("ANIM " + (a == null ? "(none)" : anim) + " t=" + t
                + " dur=" + (a == null ? -1 : a.getDuration()));
        System.out.println("SLOTS " + skeleton.getSlots().size);
        for (int i = 0; i < skeleton.getSlots().size; i++) {
            Slot s = skeleton.getSlots().get(i);
            Attachment at = s.getAttachment();
            System.out.println(String.format(Locale.ROOT, "S %d %s %s a=%.3f %s",
                    i, s.getData().getName(), at == null ? "-" : at.getName(), s.getColor().a,
                    at instanceof MeshAttachment ? "Mesh" : (at instanceof RegionAttachment ? "Region" : "Other")));
        }
        StringBuilder dord = new StringBuilder("DRAWORDER");
        com.badlogic.gdx.utils.Array<Slot> dor = skeleton.getDrawOrder();
        for (int i = 0; i < dor.size; i++) {
            dord.append(' ').append(dor.get(i).getData().getIndex());
        }
        System.out.println(dord);
        System.out.println("BONES " + skeleton.getBones().size);
        for (int i = 0; i < skeleton.getBones().size; i++) {
            Bone b = skeleton.getBones().get(i);
            System.out.println(String.format(Locale.ROOT, "B %d %s %.3f %.3f %.3f",
                    i, b.getData().getName(), b.getWorldX(), b.getWorldY(), b.getWorldRotationX()));
        }
        System.out.println("LOCAL " + skeleton.getBones().size);
        for (int i = 0; i < skeleton.getBones().size; i++) {
            Bone b = skeleton.getBones().get(i);
            System.out.println(String.format(Locale.ROOT, "L %d %s %.3f %.3f %.3f %.3f %.3f %.3f %.3f",
                    i, b.getData().getName(), b.getRotation(), b.getX(), b.getY(),
                    b.getScaleX(), b.getScaleY(), b.getShearX(), b.getShearY()));
        }

        List<String> coords = new ArrayList<>();
        for (int i = 0; i < skeleton.getSlots().size; i++) {
            Slot s = skeleton.getSlots().get(i);
            Attachment at = s.getAttachment();
            float[] w = null;
            if (at instanceof MeshAttachment) {
                VertexAttachment va = (VertexAttachment) at;
                int len = va.getWorldVerticesLength();
                if (len <= 0) {
                    System.out.println("C " + i + " 0");
                    continue;
                }
                w = new float[len];
                va.computeWorldVertices(s, w);
            } else if (at instanceof RegionAttachment) {
                float[] full = ((RegionAttachment) at).updateWorldVertices(s, false);
                w = java.util.Arrays.copyOf(full, 8);
            }
            if (w == null) {
                System.out.println("C " + i + " 0");
                continue;
            }
            System.out.println("C " + i + " " + (w.length / 2));
            StringBuilder vs = new StringBuilder();
            vs.append("V ").append(i).append(' ').append(w.length / 2);
            for (int k = 0; k + 1 < w.length; k += 2) {
                vs.append(' ').append(String.format(Locale.ROOT, "%.3f,%.3f", w[k], w[k + 1]));
                coords.add(String.format(Locale.ROOT, "%.3f,%.3f", w[k], w[k + 1]));
            }
            System.out.println(vs);
        }
        Collections.sort(coords);
        System.out.println("VALL " + coords.size());
        System.out.println(String.join(" ", coords));
    }
}
