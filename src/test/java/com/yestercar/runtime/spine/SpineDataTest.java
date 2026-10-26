package com.yestercar.runtime.spine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.common.io.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Spine 运行时的契约测试。
 *
 * <h2>这些测试防的是什么</h2>
 * <ul>
 *   <li><b>版本混用静默出错</b>：3.8 的解析器读 3.5 的 {@code .skel} 不会抛异常，
 *       结果是「加载成功、动画数 0」。所以这里逐文件断言版本串，并对 29 个模型全量加载；</li>
 *   <li><b>阿米娅的动画清单被改坏</b>：README 记录了她 14 个动画的实测时长，
 *       这里逐条钉死（±0.02 s）。只要二进制字段顺序错一个字节，时长就会偏；</li>
 *   <li><b>采样/蒙皮越界</b>：{@code fill} 是每帧调用的热路径，越界会让渲染端整场崩；
 *       这里对每个动画的多个时间点做「索引 / UV / 颜色 / 数值有限」检查；</li>
 *   <li><b>3.8 素材被跳过</b>：5 个 3.8.99 模型必须能加载且动画数 &gt; 0。</li>
 * </ul>
 */
class SpineDataTest {

    /** 阿米娅的 14 个动画与实测时长（README 记录，容差 ±0.02 s）。 */
    private static final Map<String, Float> AMIYA_ANIMATIONS = new TreeMap<>(Map.ofEntries(
            Map.entry("Attack", 1.767f),
            Map.entry("Attack_Begin", 0.133f),
            Map.entry("Attack_End", 0.133f),
            Map.entry("Default", 0.000f),
            Map.entry("Die", 1.000f),
            Map.entry("Idle", 1.000f),
            Map.entry("Skill", 1.000f),
            Map.entry("Skill_2", 0.400f),
            Map.entry("Skill_2_Begin", 0.133f),
            Map.entry("Skill_2_End", 0.133f),
            Map.entry("Skill_Begin", 0.167f),
            Map.entry("Skill_End", 0.167f),
            Map.entry("Start", 1.000f),
            Map.entry("Stun", 0.800f)));

    private static final float TOLERANCE = 0.02f;
    private static final String[] V38_MODELS = {"mlynar", "ines", "kroos", "reed", "eyjafjalla_the_hvit_aska"};

    // ------------------------------------------------------------------
    // 路径与工具
    // ------------------------------------------------------------------

    private static Path operatorRoot() {
        return Workspace.projectRoot().resolve("data").resolve("spine").resolve("operator");
    }

    private static Path operatorDir(String id) {
        return operatorRoot().resolve(id);
    }

    private static SpineData load(String id) throws IOException {
        return SpineData.load(operatorDir(id));
    }

    private static List<Path> allOperatorDirs() throws IOException {
        try (Stream<Path> children = Files.list(operatorRoot())) {
            return children.filter(Files::isDirectory).sorted().toList();
        }
    }

    // ------------------------------------------------------------------
    // 阿米娅：动画清单逐条对齐
    // ------------------------------------------------------------------

    @Test
    void 阿米娅_14个动画与实测时长一致() throws IOException {
        SpineData data = load("amiya");
        assertEquals("3.5.51", data.version(), "阿米娅是 3.5.51");
        List<String> names = data.animations();
        assertEquals(14, names.size(), "阿米娅应当有 14 个动画，实际 " + names);
        for (Map.Entry<String, Float> expected : AMIYA_ANIMATIONS.entrySet()) {
            float actual = data.duration(expected.getKey());
            assertTrue(actual >= 0f, "缺少动画 " + expected.getKey() + "，实际清单 " + names);
            assertEquals(expected.getValue(), actual, TOLERANCE, "动画 " + expected.getKey() + " 时长不符");
        }
        for (String name : names) {
            assertTrue(AMIYA_ANIMATIONS.containsKey(name), "多出了未记录的动画: " + name);
        }
    }

    @Test
    void 阿米娅骨架规模与贴图对齐() throws IOException {
        SpineData data = load("amiya");
        assertEquals(67, data.boneCount(), "骨骼数");
        assertEquals(53, data.slotCount(), "插槽数");
        assertEquals("char_002_amiya.png", data.atlas().page(), "贴图页名");
        assertEquals(512, data.atlas().width());
        assertEquals(512, data.atlas().height());
        assertTrue(data.atlas().regionCount() >= 50, "贴图区域数 " + data.atlas().regionCount());
        assertNotNull(data.atlas().region("F_Head"), "示例区域 F_Head 必须存在");
        assertNull(data.atlas().region("不存在的区域"), "不存在的区域返回 null");
        assertTrue(data.atlas().region("F_Eye").rotated(), "F_Eye 在 atlas 里是 rotate: true");
        assertFalse(data.atlas().region("F_Head").rotated(), "F_Head 不是旋转区域");
    }

    @Test
    void 包围盒与顶点上界是合理量级() throws IOException {
        SpineData data = load("amiya");
        assertTrue(data.maxX() - data.minX() > 100f, "宽度应当是一个角色量级");
        assertTrue(data.maxY() - data.minY() > 100f, "高度应当是一个角色量级");
        assertTrue(data.minY() > -100f && data.maxY() < 1000f, "setup 包围盒纵向范围异常");
        assertTrue(data.maxVertices() >= 4, "最大顶点数不可能小于一个矩形");
        assertTrue(data.maxTriangles() > 0, "最大三角数应当 > 0");
    }

    // ------------------------------------------------------------------
    // 3.8.99：必须能加载
    // ------------------------------------------------------------------

    @Test
    void 五个38模型能加载且动画数大于零() throws IOException {
        for (String id : V38_MODELS) {
            SpineData data = load(id);
            assertEquals("3.8.99", data.version(), id + " 应当是 3.8.99");
            assertTrue(data.animations().size() > 0, id + " 动画数为 0，说明 3.8 分支解析错位了");
            assertTrue(data.boneCount() > 0, id + " 骨骼数为 0");
            assertTrue(data.slotCount() > 0, id + " 插槽数为 0");
            assertNotNull(data.atlas().page(), id + " 贴图页名");
        }
    }

    @Test
    void 二十九个干员全部能加载() throws IOException {
        List<Path> dirs = allOperatorDirs();
        assertEquals(29, dirs.size(), "data/spine/operator 下应当有 29 个目录，实际 " + dirs.size());
        for (Path dir : dirs) {
            String id = dir.getFileName().toString();
            SpineData data = SpineData.load(dir);
            assertTrue(data.animations().size() > 0, id + " 动画数为 0");
            assertTrue(data.boneCount() > 5, id + " 骨骼数异常: " + data.boneCount());
            assertTrue(data.version().startsWith("3."), id + " 版本串异常: " + data.version());
            assertTrue(data.maxVertices() > 0, id + " 最大顶点数为 0");
        }
    }

    @Test
    void 版本是逐文件读出来的() throws IOException {
        Map<String, Integer> byVersion = new TreeMap<>();
        for (Path dir : allOperatorDirs()) {
            SpineData data = SpineData.load(dir);
            byVersion.merge(data.version(), 1, Integer::sum);
        }
        assertEquals(24, (int) byVersion.getOrDefault("3.5.51", 0), "3.5.51 的模型数: " + byVersion);
        assertEquals(5, (int) byVersion.getOrDefault("3.8.99", 0), "3.8.99 的模型数: " + byVersion);
        assertEquals(29, byVersion.values().stream().mapToInt(Integer::intValue).sum(), "总模型数");
    }

    // ------------------------------------------------------------------
    // 姿态采样：越界 / UV / 数值有限
    // ------------------------------------------------------------------

    @Test
    void fill_产出的顶点与索引都不越界() throws IOException {
        for (String id : new String[] {"amiya", "mlynar", "blaze"}) {
            SpineData data = load(id);
            SpinePose pose = new SpinePose(data);
            MeshBuffer buffer = new MeshBuffer();
            for (String anim : data.animations()) {
                assertTrue(pose.play(anim, false), id + " 播放 " + anim + " 失败");
                float duration = data.duration(anim);
                for (float t : new float[] {0f, duration * 0.25f, duration * 0.5f, duration, duration + 1f}) {
                    pose.setTime(t);
                    pose.fill(buffer);
                    checkBuffer(buffer, id + "/" + anim + "@" + t);
                }
            }
        }
    }

    @Test
    void 循环动画推进后顶点数不变但位置在动() throws IOException {
        SpineData data = load("amiya");
        SpinePose pose = new SpinePose(data);
        MeshBuffer a = new MeshBuffer();
        MeshBuffer b = new MeshBuffer();
        assertTrue(pose.play("Idle", true));
        pose.setTime(0f);
        pose.fill(a);
        float[] atZero = copyVertices(a);
        for (int i = 0; i < 30; i++) {
            pose.update(1f / 30f);
            pose.fill(b);
            checkBuffer(b, "Idle 推进中");
        }
        pose.setTime(0.5f);
        assertFalse(pose.finished(), "循环动画不该 finished");
        pose.fill(b);
        assertEquals(a.vertexCount(), b.vertexCount(), "Idle 的 t=0 与 t=0.5 顶点数应当一致");
        assertTrue(differs(atZero, b), "Idle 的 t=0 与 t=0.5 坐标应当不同（动画真的在动）");
    }

    @Test
    void 非循环动画播完会finished并夹住时间() throws IOException {
        SpineData data = load("amiya");
        SpinePose pose = new SpinePose(data);
        assertTrue(pose.play("Attack", false));
        assertEquals("Attack", pose.current());
        assertFalse(pose.finished());
        float duration = data.duration("Attack");
        for (int i = 0; i < 200; i++) {
            pose.update(1f / 60f);
        }
        assertTrue(pose.finished(), "非循环动画播完应当 finished");
        assertEquals(duration, pose.time(), 1e-3f, "时间应当夹在 duration");
        pose.setTime(duration + 5f);
        assertEquals(duration, pose.time(), 1e-3f, "setTime 超过 duration 应当夹住");
        pose.setTime(-3f);
        assertEquals(0f, pose.time(), 1e-6f, "setTime 负数应当夹到 0");
    }

    @Test
    void 播放不存在的动画不改状态() throws IOException {
        SpineData data = load("amiya");
        SpinePose pose = new SpinePose(data);
        pose.fill(new MeshBuffer());
        assertNull(pose.current(), "初始没有动画");
        assertFalse(pose.play("NoSuchAnimation", true), "不存在的动画应当返回 false");
        assertNull(pose.current(), "失败的 play 不能改当前动画");
        assertTrue(pose.play("Idle", true));
        pose.setTime(0.3f);
        float t = pose.time();
        assertFalse(pose.play("也不存在", true));
        assertEquals("Idle", pose.current(), "失败的 play 不能清掉当前动画");
        assertEquals(t, pose.time(), 1e-6f, "失败的 play 不能改时间");
    }

    @Test
    void 包围盒能覆盖setup姿态的所有顶点() throws IOException {
        for (String id : new String[] {"amiya", "mlynar"}) {
            SpineData data = load(id);
            SpinePose pose = new SpinePose(data);
            MeshBuffer buffer = new MeshBuffer();
            pose.fill(buffer);
            assertTrue(buffer.vertexCount() > 0, id + " setup 姿态没画出任何顶点");
            float[] xy = buffer.xy();
            for (int i = 0; i < buffer.vertexCount() * 2; i += 2) {
                assertTrue(xy[i] >= data.minX() - 0.5f && xy[i] <= data.maxX() + 0.5f,
                        id + " 顶点 x 超出包围盒: " + xy[i]);
                assertTrue(xy[i + 1] >= data.minY() - 0.5f && xy[i + 1] <= data.maxY() + 0.5f,
                        id + " 顶点 y 超出包围盒: " + xy[i + 1]);
            }
        }
    }

    @Test
    void 贴图区域toUv在区域矩形内() throws IOException {
        SpineData data = load("amiya");
        SpineAtlas atlas = data.atlas();
        float[] out = new float[2];
        SpineAtlas.Region head = atlas.region("F_Head");
        head.toUv(0f, 0f, out);
        assertEquals(head.x() / (float) atlas.width(), out[0], 1e-6f, "toUv(0,0).u 应当是区域左边界");
        assertEquals(head.y() / (float) atlas.height(), out[1], 1e-6f, "toUv(0,0).v 应当是区域上边界");
        head.toUv(1f, 1f, out);
        assertEquals((head.x() + head.w()) / (float) atlas.width(), out[0], 1e-6f);
        assertEquals((head.y() + head.h()) / (float) atlas.height(), out[1], 1e-6f);

        SpineAtlas.Region eye = atlas.region("F_Eye");
        assertTrue(eye.rotated());
        float u0 = eye.x() / (float) atlas.width();
        float v0 = eye.y() / (float) atlas.height();
        float u1 = (eye.x() + eye.packedWidth()) / (float) atlas.width();
        float v1 = (eye.y() + eye.packedHeight()) / (float) atlas.height();
        for (float u = 0f; u <= 1f; u += 0.25f) {
            for (float v = 0f; v <= 1f; v += 0.25f) {
                eye.toUv(u, v, out);
                assertTrue(out[0] >= u0 - 1e-6f && out[0] <= u1 + 1e-6f, "旋转区域 UV u 越界: " + out[0]);
                assertTrue(out[1] >= v0 - 1e-6f && out[1] <= v1 + 1e-6f, "旋转区域 UV v 越界: " + out[1]);
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void checkBuffer(MeshBuffer buffer, String where) {
        assertTrue(buffer.vertexCount() > 0, where + " 顶点数为 0");
        assertTrue(buffer.triCount() > 0, where + " 三角形数为 0");
        int[] tris = buffer.tris();
        for (int i = 0; i < buffer.triCount() * 3; i++) {
            int index = tris[i];
            assertTrue(index >= 0 && index < buffer.vertexCount(),
                    where + " 三角形索引越界: " + index + " / " + buffer.vertexCount());
        }
        float[] uv = buffer.uv();
        float[] rgba = buffer.rgba();
        float[] xy = buffer.xy();
        for (int i = 0; i < buffer.vertexCount(); i++) {
            float u = uv[i * 2];
            float v = uv[i * 2 + 1];
            assertTrue(u >= -1e-4f && u <= 1f + 1e-4f, where + " u 越界: " + u);
            assertTrue(v >= -1e-4f && v <= 1f + 1e-4f, where + " v 越界: " + v);
            for (int c = 0; c < 4; c++) {
                float value = rgba[i * 4 + c];
                assertTrue(value >= -1e-4f && value <= 1f + 1e-4f, where + " 颜色分量越界: " + value);
            }
            assertTrue(Float.isFinite(xy[i * 2]), where + " x 不是有限数");
            assertTrue(Float.isFinite(xy[i * 2 + 1]), where + " y 不是有限数");
        }
    }

    private static float[] copyVertices(MeshBuffer buffer) {
        float[] out = new float[buffer.vertexCount() * 2];
        System.arraycopy(buffer.xy(), 0, out, 0, out.length);
        return out;
    }

    private static boolean differs(float[] a, MeshBuffer buffer) {
        float[] b = buffer.xy();
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-3f) {
                return true;
            }
        }
        return false;
    }
}
