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
import com.esotericsoftware.spine.attachments.ClippingAttachment;
import com.esotericsoftware.spine.attachments.MeshAttachment;
import com.esotericsoftware.spine.attachments.PathAttachment;
import com.esotericsoftware.spine.attachments.PointAttachment;
import com.esotericsoftware.spine.attachments.RegionAttachment;
import com.esotericsoftware.spine.attachments.VertexAttachment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 官方 oracle（spine-libgdx 3.8.99.1）—— 输出格式与 {@link Oracle35} 完全一致。
 *
 * <p>5 个 3.8.99 素材（mlynar / ines / kroos / reed / eyjafjalla_the_hvit_aska）必须用这个。
 * 3.8 的 {@code AttachmentLoader} 多了 clipping / point，{@code computeWorldVertices} 也换了签名。
 */
public class Oracle38 {

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

        public ClippingAttachment newClippingAttachment(Skin skin, String name) {
            return new ClippingAttachment(name);
        }

        public PointAttachment newPointAttachment(Skin skin, String name) {
            return new PointAttachment(name);
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
                va.computeWorldVertices(s, 0, len, w, 0, 2);
            } else if (at instanceof RegionAttachment) {
                w = new float[8];
                ((RegionAttachment) at).computeWorldVertices(s.getBone(), w, 0, 2);
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
