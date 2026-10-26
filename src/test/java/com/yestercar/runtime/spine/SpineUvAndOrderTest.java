package com.yestercar.runtime.spine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.common.io.Workspace;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 两条"画面级"缺陷的回归测试：旋转图集的 UV 换轴、drawOrder 时间轴解码。
 *
 * <p>这两条都不会让骨骼数值变，只让**画面**变 —— 前者让所有 {@code rotate: true} 的部件
 * 取到邻块纹理（头发/耳朵/嘴/衣领糊成一团），后者让图层顺序乱掉（头发画在脸前面）。
 * 所以只能用"UV 数值"和"绘制顺序"来钉。
 */
class SpineUvAndOrderTest {

    private static SpineData load(String id) throws IOException {
        return SpineData.load(Workspace.projectRoot()
                .resolve("data").resolve("spine").resolve("operator").resolve(id));
    }

    /**
     * 旋转区域（{@code rotate: true} = 90°）在贴图里宽高互换，官方 {@code RegionAttachment.setRegion}
     * 给四个角是"错开一位"的 —— 等价于把区域内的 (u,v) 转置。
     *
     * <p>这里用 texas 的图集验证：{@code F_R_Hair} 是 rotate:true，{@code F_Head} 是 rotate:false。
     * 期望值由"和官方渲染逐像素对比"反推并锁定：加了转置后差异从 5.68% 降到 0.018%。
     */
    @Test
    void rotatedRegionTransposesUv() throws IOException {
        SpineData d = load("texas");
        SpineAtlas atlas = d.atlas();

        SpineAtlas.Region rotated = atlas.region("F_R_Hair");
        assertTrue(rotated != null && rotated.rotated(), "F_R_Hair 应当是旋转区域");

        float[] out = new float[2];
        // 转置的判别式：区域内的"右上角"要落到矩形的左上角，"左下角"落到右下角
        // （未旋转区域恰好相反：右上 → 矩形右上）。
        rotated.toUv(1f, 0f, out);
        assertEquals(rotated.rectU0(), out[0], 1e-5f, "旋转区域：u 轴由 v 决定");
        assertEquals(rotated.rectV0(), out[1], 1e-5f);
        rotated.toUv(0f, 1f, out);
        assertEquals(rotated.rectU1(), out[0], 1e-5f);
        assertEquals(rotated.rectV1(), out[1], 1e-5f);

        SpineAtlas.Region plain = atlas.region("F_Head");
        assertTrue(plain != null && !plain.rotated(), "F_Head 应当是未旋转区域");
        plain.toUv(1f, 0f, out);
        assertEquals(plain.rectU1(), out[0], 1e-5f, "未旋转区域：u 轴由 u 决定");
        assertEquals(plain.rectV0(), out[1], 1e-5f);
    }

    /**
     * drawOrder 时间轴的二进制解码。
     *
     * <p>期望值直接抄自官方运行时（{@code Skeleton.getDrawOrder()}）在
     * texas / Idle / t=0.5s 的实测结果。解码写错（以前是
     * {@code drawOrder[i1] = order + n1}）就会得到完全不同的排列，
     * 头发/披风会被画到脸前面。
     */
    @Test
    void texasIdleDrawOrderMatchesOfficial() throws IOException {
        SpineData d = load("texas");
        SpinePose pose = new SpinePose(d);
        assertTrue(pose.play("Idle", false));
        pose.setTime(0.5f);
        pose.fill(new MeshBuffer());

        int[] order = drawOrderOf(pose);
        int[] expected = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            25, 26, 27, 28, 32, 34, 35, 36, 37, 38, 39, 40, 41, 29, 30, 31, 33, 42, 43, 44, 45, 46,
            47, 48, 49,
        };
        assertEquals(expected.length, order.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], order[i], "drawOrder[" + i + "]");
        }
    }

    /** @param pose 姿态 @return 当前绘制顺序（反射读私有字段，仅供测试） */
    private static int[] drawOrderOf(SpinePose pose) throws IOException {
        try {
            Field f = SpinePose.class.getDeclaredField("drawOrder");
            f.setAccessible(true);
            return ((int[]) f.get(pose)).clone();
        } catch (ReflectiveOperationException e) {
            throw new IOException(e);
        }
    }

    /** 每个模型的 drawOrder 都必须是一个合法排列（越界/重复会让渲染端画错或崩）。 */
    @Test
    void everyModelHasValidDrawOrder() throws IOException {
        Path root = Workspace.projectRoot().resolve("data").resolve("spine").resolve("operator");
        try (var dirs = java.nio.file.Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs.sorted()::iterator) {
                if (!java.nio.file.Files.isDirectory(dir)) {
                    continue;
                }
                SpineData d = SpineData.load(dir);
                String anim = d.duration("Idle") >= 0 ? "Idle" : d.animations().get(0);
                float dur = Math.max(0.001f, d.duration(anim));
                for (float frac : new float[] {0f, 0.25f, 0.5f, 0.75f, 0.99f}) {
                    SpinePose pose = new SpinePose(d);
                    pose.play(anim, false);
                    pose.setTime(dur * frac);
                    pose.fill(new MeshBuffer());
                    int[] order = drawOrderOf(pose);
                    boolean[] seen = new boolean[order.length];
                    for (int v : order) {
                        assertTrue(v >= 0 && v < order.length && !seen[v],
                                d.name() + " @" + anim + " t=" + (dur * frac) + " 的 drawOrder 不是合法排列");
                        seen[v] = true;
                    }
                }
            }
        }
    }
}
