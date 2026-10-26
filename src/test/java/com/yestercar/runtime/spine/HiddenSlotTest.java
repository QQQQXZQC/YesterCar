package com.yestercar.runtime.spine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.common.io.Workspace;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * "按插槽名删层"（{@code data/spine/operator/<id>/hidden-slots.txt}）的契约测试。
 *
 * <p>需求方点名要删的六层：铃兰 / 史尔特尔 的 {@code F_Face_Default_h}、{@code F_Face_Default_i}，
 * 煌 的 {@code F_Face_Default_4_2}、{@code F_Face_Default_3_2}（都是眼下的半月形阴影层）。
 *
 * <p>这里钉三件事：
 * <ol>
 *   <li>被点名的插槽在<b>任何时刻</b>都不挂附件（等于永远不画）；</li>
 *   <li>同组的其他层（例如 {@code F_Face_Default_j}/{@code _4_3}）<b>必须还在</b>，
 *       证明只删了这两层、没有连带影响；</li>
 *   <li>没有 {@code hidden-slots.txt} 的干员<b>一个插槽都不许隐藏</b>（其他 26 个模型不变）。</li>
 * </ol>
 */
class HiddenSlotTest {

    private static SpineData load(String id) throws IOException {
        return SpineData.load(Workspace.projectRoot()
                .resolve("data").resolve("spine").resolve("operator").resolve(id));
    }

    private static int slot(SpineData d, String name) {
        for (int i = 0; i < d.slotCount(); i++) {
            if (d.slots[i].name.equals(name)) {
                return i;
            }
        }
        throw new AssertionError("找不到插槽 " + name + " @" + d.name());
    }

    /** 在被点名的插槽上跑一遍动画，确认整段动画里它都是空的。 */
    private static void assertAlwaysEmpty(SpineData d, String anim) {
        float dur = Math.max(0.001f, d.duration(anim));
        for (float frac : new float[] {0f, 0.25f, 0.5f, 0.75f, 0.99f}) {
            SpinePose pose = new SpinePose(d);
            pose.play(anim, false);
            pose.setTime(dur * frac);
            pose.fill(new MeshBuffer());
            for (String name : d.hiddenSlotNames()) {
                int i = slot(d, name);
                assertTrue(d.isSlotHidden(i), name + " 应当标记为隐藏");
                assertNull(pose.slotAttachment[i],
                        d.name() + " 的 " + name + " 在 t=" + (dur * frac) + " 仍是可见的");
            }
        }
    }

    @Test
    void suzuranFaceLayersRemoved() throws IOException {
        SpineData d = load("suzuran");
        assertEquals(Set.of("F_Face_Default_h", "F_Face_Default_i"), d.hiddenSlotNames());
        assertAlwaysEmpty(d, "Idle");
        assertAlwaysEmpty(d, "Attack");

        // 同组其他层必须还在（只删两层，别把整张脸删了）
        SpinePose pose = new SpinePose(d);
        pose.play("Idle", false);
        pose.setTime(0.5f);
        pose.fill(new MeshBuffer());
        assertNotNull(pose.slotAttachment[slot(d, "F_Face_Default_j")], "F_Face_Default_j 不该被删");
        assertNotNull(pose.slotAttachment[slot(d, "F_Face_Default_g")], "F_Face_Default_g 不该被删");
    }

    @Test
    void surtrFaceLayersRemoved() throws IOException {
        SpineData d = load("surtr");
        assertEquals(Set.of("F_Face_Default_h", "F_Face_Default_i"), d.hiddenSlotNames());
        assertAlwaysEmpty(d, "Idle");
        assertAlwaysEmpty(d, "Attack");

        SpinePose pose = new SpinePose(d);
        pose.play("Idle", false);
        pose.setTime(0.5f);
        pose.fill(new MeshBuffer());
        assertNotNull(pose.slotAttachment[slot(d, "F_Face_Default_j")], "F_Face_Default_j 不该被删");
        assertNotNull(pose.slotAttachment[slot(d, "F_Face_Default_n")], "F_Face_Default_n 不该被删");
    }

    @Test
    void blazeFaceLayersRemoved() throws IOException {
        SpineData d = load("blaze");
        assertEquals(Set.of("F_Face_Default_4_2", "F_Face_Default_3_2"), d.hiddenSlotNames());
        assertAlwaysEmpty(d, "Idle");
        assertAlwaysEmpty(d, "Attack");

        SpinePose pose = new SpinePose(d);
        pose.play("Idle", false);
        pose.setTime(0.5f);
        pose.fill(new MeshBuffer());
        assertNotNull(pose.slotAttachment[slot(d, "F_Face_Default_4_3")], "F_Face_Default_4_3 不该被删");
        assertNotNull(pose.slotAttachment[slot(d, "F_Face_Default_3_3")], "F_Face_Default_3_3 不该被删");
    }

    /** 其他干员没有配置文件 → 一个插槽都不许隐藏。 */
    @Test
    void otherOperatorsAreUntouched() throws IOException {
        Path root = Workspace.projectRoot().resolve("data").resolve("spine").resolve("operator");
        int withConfig = 0;
        try (var dirs = java.nio.file.Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs.sorted()::iterator) {
                if (!java.nio.file.Files.isDirectory(dir)) {
                    continue;
                }
                SpineData d = SpineData.load(dir);
                String id = dir.getFileName().toString();
                if (id.equals("suzuran") || id.equals("surtr") || id.equals("blaze")) {
                    withConfig++;
                    assertEquals(2, d.hiddenSlotNames().size(), id + " 应当恰好隐藏 2 层");
                    continue;
                }
                assertTrue(d.hiddenSlotNames().isEmpty(), id + " 不该有隐藏层");
                for (int i = 0; i < d.slotCount(); i++) {
                    assertFalse(d.isSlotHidden(i), id + " 的插槽 " + d.slots[i].name + " 被误隐藏");
                }
            }
        }
        assertEquals(3, withConfig, "应当只有 3 个干员有 hidden-slots.txt");
    }
}
