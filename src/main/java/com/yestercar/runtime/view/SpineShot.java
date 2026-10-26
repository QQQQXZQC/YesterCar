package com.yestercar.runtime.view;

import com.yestercar.common.io.Workspace;
import com.yestercar.runtime.spine.MeshBuffer;
import com.yestercar.runtime.spine.SpineData;
import com.yestercar.runtime.spine.SpinePose;
import java.nio.file.Path;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;

/**
 * 本工程渲染快照：用<b>游戏同一条渲染路径</b>（{@link SpineMeshPainter}）把某一帧画成 PNG。
 *
 * <p>相机与 {@code tools/spine-oracle/java/OfficialShot} 完全一致
 * （世界 (0,0) 脚底、中心 (0,200)、1.1 px/单位、512×512、背景 (41,43,51)），
 * 所以可以直接和官方渲染逐像素对比 —— 这是验证"游戏内画面是否真的和官方一样"的硬标准。
 *
 * <p>用法：
 * <pre>
 * java -cp target/YesterCar.jar com.yestercar.runtime.view.SpineShot \
 *      data/spine/operator/texas Idle 0.5 out\ours-texas.png
 * </pre>
 */
public final class SpineShot extends PApplet {

    private static String dir;
    private static String anim;
    private static String timeArg;
    private static String outPath;
    /** 序列模式：>1 表示沿动画时长均匀取这么多帧，输出 outPath-000.png … */
    private static int seqFrames = 1;

    /** @return 当前帧的采样时刻 */
    private float frameTime() {
        if (seqFrames <= 1) {
            return switch (timeArg) {
                case "mid" -> duration * 0.5f;
                case "end" -> Math.max(0f, duration - 0.001f);
                default -> Float.parseFloat(timeArg);
            };
        }
        return duration * frameIndex / (float) seqFrames;
    }

    private static final int SIZE = 512;
    private static final float SCALE = 1.1f;
    private static final float CENTER_Y = 200f;

    private SpineData data;
    private SpinePose pose;
    private float duration;
    private int frameIndex;
    private PImage tex;
    private int frames;

    /** @param args 见类注释 */
    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("用法: SpineShot <素材目录> <动画> <秒|mid|end> <out.png>");
            System.exit(2);
        }
        dir = args[0];
        anim = args[1];
        timeArg = args[2];
        outPath = args[3];
        if (args.length > 4 && args[4].startsWith("--seq=")) {
            seqFrames = Math.max(1, Integer.parseInt(args[4].substring(6)));
        }
        PApplet.main(SpineShot.class);
    }

    @Override
    public void settings() {
        size(SIZE, SIZE, PConstants.P2D);
        pixelDensity(1);
    }

    @Override
    public void setup() {
        surface.setTitle("SpineShot");
        Path base = Workspace.projectRoot().resolve(dir);
        try {
            data = SpineData.load(base);
            tex = loadImage(base.resolve(data.atlas().page()).toString());
            pose = new SpinePose(data);
            pose.play(anim, false);
            duration = Math.max(0f, data.duration(anim));
            pose.setTime(frameTime());
        } catch (Exception ex) {
            System.err.println("[SpineShot] 加载失败: " + ex);
            System.exit(1);
        }
        background(41, 43, 51);
    }

    @Override
    public void draw() {
        background(41, 43, 51);
        MeshBuffer mesh = new MeshBuffer();
        pose.fill(mesh);
        // 与 OfficialShot 的相机一致：世界 (0,200) → 画布中心，y 向上
        // 脚底 (0,0) 落在 SIZE/2 + 200*scale；SpineMeshPainter 内部按 y 向下换算
        float cx = SIZE / 2f;
        float cy = SIZE / 2f + CENTER_Y * SCALE;
        SpineMeshPainter.draw(g, tex, mesh, cx, cy, SCALE, 1f, 0f, 1f, 1f, 0f, 0f);
        if (++frames >= 2) {
            frames = 0;
            if (seqFrames <= 1) {
                saveFrame(outPath);
                System.out.println("wrote " + outPath);
                exit();
                return;
            }
            saveFrame(String.format("%s-%03d.png", outPath, frameIndex));
            if (++frameIndex >= seqFrames) {
                System.out.println("wrote " + seqFrames + " frames -> " + outPath + "-NNN.png");
                exit();
                return;
            }
            pose.setTime(frameTime());
        }
    }
}
