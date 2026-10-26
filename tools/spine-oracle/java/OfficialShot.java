import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.utils.ScreenUtils;
import com.esotericsoftware.spine.Animation;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationStateData;
import com.esotericsoftware.spine.Skeleton;
import com.esotericsoftware.spine.SkeletonBinary;
import com.esotericsoftware.spine.SkeletonData;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 官方渲染快照：用真正的 spine-libgdx + 真实图集，把某个干员的某一帧渲染成 PNG。
 *
 * <p>和 {@code com.yestercar.runtime.view.SpineShot} 用<b>完全相同的相机</b>
 * （同一缩放、同一中心、同一 512x512、同一背景色），所以两张图可以逐像素比较 ——
 * 这是判断"游戏内到底和官方差在哪"的唯一硬标准
 * （骨骼数值对不代表画面一样：蒙皮、deform、drawOrder、UV 都可能错）。
 *
 * <p>用法：
 * <pre>
 * java -cp "out35;&lt;spine&gt;;&lt;gdx&gt;;&lt;lwjgl&gt;" OfficialShot \
 *      data\spine\operator\texas\char_102_texas.skel \
 *      data\spine\operator\texas\char_102_texas.atlas Idle mid out\official-texas.png
 * </pre>
 * 采样时刻支持秒数 / {@code mid} / {@code end}。
 *
 * <p>相机约定（与 {@code SpineShot} 一致）：世界 (0,0) 是脚底、y 向上，
 * 视图中心 (0,200)，缩放 1.1 px/单位，画布 512x512，背景 (41,43,51)。
 *
 * <p>渲染器用反射拿：spine-libgdx 3.5 里叫 {@code SkeletonMeshRenderer}，3.8 合并进了
 * {@code SkeletonRenderer} —— 这样同一份源码能分别对着两个版本的 jar 编译。
 */
public class OfficialShot extends ApplicationAdapter {

    static String skelPath;
    static String atlasPath;
    static String anim;
    static String timeArg;
    static String outPath;
    /** >1 表示序列模式：沿动画时长均匀取这么多帧，输出 outPath-000.png … */
    static int seqFrames = 1;

    static final int SIZE = 512;
    static final float SCALE = 1.1f;
    static final float CENTER_Y = 200f;
    static final Color BG = new Color(41 / 255f, 43 / 255f, 51 / 255f, 1f);

    private TextureAtlas atlas;
    private Skeleton skeleton;
    private Object renderer;
    private java.lang.reflect.Method rendererDraw;
    private PolygonSpriteBatch batch;
    private OrthographicCamera camera;
    private int frames;
    private int frameIndex;
    private float duration;
    private boolean done;

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("usage: OfficialShot <skel> <atlas> <anim> <sec|mid|end> <out.png>");
            System.exit(2);
        }
        skelPath = args[0];
        atlasPath = args[1];
        anim = args[2];
        timeArg = args[3];
        outPath = args[4];
        if (args.length > 5 && args[5].startsWith("--seq=")) {
            seqFrames = Math.max(1, Integer.parseInt(args[5].substring(6)));
        }

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("OfficialShot");
        config.setWindowedMode(SIZE, SIZE);
        config.useVsync(false);
        config.setForegroundFPS(0);
        config.setResizable(false);
        new Lwjgl3Application(new OfficialShot(), config);
    }

    @Override
    public void create() {
        atlas = new TextureAtlas(new FileHandle(new File(atlasPath)));
        SkeletonData data = new SkeletonBinary(atlas)
                .readSkeletonData(new FileHandle(new File(skelPath)));
        skeleton = new Skeleton(data);
        Animation a = data.findAnimation(anim);
        if (a != null) {
            AnimationStateData asd = new AnimationStateData(data);
            asd.setDefaultMix(0f);
            AnimationState st = new AnimationState(asd);
            st.setAnimation(0, anim, false);
            duration = a.getDuration();
            st.update(Math.max(0f, resolveTime(a)));
            st.apply(skeleton);
        }
        skeleton.updateWorldTransform();

        try {
            Class<?> cls;
            try {
                cls = Class.forName("com.esotericsoftware.spine.SkeletonMeshRenderer");
            } catch (ClassNotFoundException e) {
                cls = Class.forName("com.esotericsoftware.spine.SkeletonRenderer");
            }
            renderer = cls.getConstructor().newInstance();
            cls.getMethod("setPremultipliedAlpha", boolean.class).invoke(renderer, Boolean.FALSE);
            rendererDraw = cls.getMethod("draw", PolygonSpriteBatch.class, Skeleton.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no usable Skeleton renderer", e);
        }
        batch = new PolygonSpriteBatch();

        float viewH = SIZE / SCALE;
        camera = new OrthographicCamera(viewH, viewH);
        camera.position.set(0f, CENTER_Y, 0f);
        camera.update();
    }

    /** @return 采样时刻（秒）；支持 mid / end 写法 */
    private static float resolveTime(Animation a) {
        float dur = a == null ? 0f : a.getDuration();
        if ("mid".equals(timeArg)) {
            return dur * 0.5f;
        }
        if ("end".equals(timeArg)) {
            return Math.max(0f, dur - 0.001f);
        }
        return Float.parseFloat(timeArg);
    }

    @Override
    public void render() {
        ScreenUtils.clear(BG);
        camera.update();
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        try {
            rendererDraw.invoke(renderer, batch, skeleton);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("render failed", e);
        }
        batch.end();
        if (++frames >= 2 && !done) {
            frames = 0;
            if (seqFrames <= 1) {
                done = true;
                save();
                Gdx.app.exit();
                return;
            }
            String path = String.format("%s-%03d.png", outPath, frameIndex);
            saveTo(path);
            if (++frameIndex >= seqFrames) {
                System.out.println("wrote " + seqFrames + " frames -> " + outPath + "-NNN.png");
                Gdx.app.exit();
                return;
            }
            poseFrame();
        }
    }

    /** 把骨骼摆到序列的第 frameIndex 帧（用 AnimationState 采样，和单帧模式同一条路径） */    private void poseFrame() {
        Animation a = skeleton.getData().findAnimation(anim);
        if (a == null) {
            return;
        }
        skeleton.setToSetupPose();
        AnimationStateData asd = new AnimationStateData(skeleton.getData());
        asd.setDefaultMix(0f);
        AnimationState st = new AnimationState(asd);
        st.setAnimation(0, anim, false);
        st.update(Math.max(0f, duration * frameIndex / (float) seqFrames));
        st.apply(skeleton);
        skeleton.updateWorldTransform();
    }

    private void save() {
        saveTo(outPath);
    }

    private void saveTo(String path) {
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();
        Pixmap src = ScreenUtils.getFrameBufferPixmap(0, 0, w, h);
        Pixmap out = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        int rowBytes = w * 4;
        ByteBuffer s = src.getPixels();
        ByteBuffer d = out.getPixels();
        s.position(0);
        d.position(0);
        byte[] row = new byte[rowBytes];
        for (int y = 0; y < h; y++) {
            s.position(y * rowBytes);
            s.get(row);
            d.position((h - 1 - y) * rowBytes);
            d.put(row);
        }
        File f = new File(path);
        if (f.getParentFile() != null) {
            f.getParentFile().mkdirs();
        }
        PixmapIO.writePNG(new FileHandle(f), out);
        src.dispose();
        out.dispose();
        System.out.println("wrote " + path);
    }

    @Override
    public void dispose() {
        if (batch != null) {
            batch.dispose();
        }
        if (atlas != null) {
            atlas.dispose();
        }
    }
}
