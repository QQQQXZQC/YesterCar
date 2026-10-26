package com.yestercar.runtime.spine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.common.io.Workspace;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 约束求解与"隐藏部件"的回归测试 —— 全部锚定在<b>官方运行时实测值</b>上。
 *
 * <h2>为什么这些数字能当护栏</h2>
 * 本工程的 Spine 运行时是手写的（不能引 libGDX，见 {@code docs/骨骼动画接入.md}），
 * 所以"和官方一致"必须靠外部参照物。下表的期望值是用官方 {@code spine-libgdx-3.5.51.1}
 * 加载同一个 {@code .skel}、{@code setToSetupPose + AnimationState(Idle, 0.5s)} 后
 * 读 {@code Bone.getWorldX()/getWorldY()} 得到的（脚本见 {@code docs/骨骼动画接入.md} §9）。
 *
 * <h2>这些测试分别防哪一类回归</h2>
 * <ul>
 *   <li>{@link #texasIdleMatchesOfficial()} —— <b>transform 约束时间轴</b>：
 *       德克萨斯的 {@code Pocky}/{@code Weapon_1} 约束在 setup 里 {@code mix=0}，
 *       动画里才被抬到 1。以前解析时把这段时间轴丢掉，烟斗与刀会飘 50–150 单位；</li>
 *   <li>{@link #sariaIdleMatchesOfficial()} —— 同上，外加 <b>位移混合</b>
 *       （约束直接改世界坐标，必须传播到子骨）；</li>
 *   <li>{@link #wIdleMatchesOfficial()} —— <b>transformMode 2（noRotationOrReflection）</b>：
 *       这种骨骼的矩阵不乘父级，写错会差 80–175°；</li>
 *   <li>{@link #cuoraEyesAreVisible()} —— <b>attachment 时间轴的首帧语义</b>：
 *       首帧之前官方"什么都不做"（保留 setup 附件），清空会让角色少零件；</li>
 *   <li>{@link #bagpipeIkMixesComeFromAnimation()} —— <b>IK 约束时间轴</b>被真正应用。</li>
 * </ul>
 */
class SpineConstraintTest {

    private static SpineData load(String id) throws IOException {
        return SpineData.load(Workspace.projectRoot()
                .resolve("data").resolve("spine").resolve("operator").resolve(id));
    }

    private static int bone(SpineData d, String name) {
        for (int i = 0; i < d.boneCount(); i++) {
            if (d.bones[i].name.equals(name)) {
                return i;
            }
        }
        throw new AssertionError("找不到骨骼 " + name + " @" + d.name());
    }

    private static int slot(SpineData d, String name) {
        for (int i = 0; i < d.slotCount(); i++) {
            if (d.slots[i].name.equals(name)) {
                return i;
            }
        }
        throw new AssertionError("找不到插槽 " + name + " @" + d.name());
    }

    /** 打开 Idle 并定位到指定时刻（与官方脚本同一条路径：setup → 动画 → 采样）。 */
    private static SpinePose poseAt(SpineData d, String anim, float time) {
        SpinePose pose = new SpinePose(d);
        assertTrue(pose.play(anim, false), "模型没有动画 " + anim);
        pose.setTime(time);
        pose.fill(new MeshBuffer());
        return pose;
    }

    private static void assertBone(SpineData d, SpinePose pose, String name,
            float expectX, float expectY, float tolerance) {
        int i = bone(d, name);
        String where = d.name() + " / " + name;
        assertEquals(expectX, pose.worldX(i), tolerance, where + " worldX");
        assertEquals(expectY, pose.worldY(i), tolerance, where + " worldY");
    }

    // ------------------------------------------------------------------
    // 约束
    // ------------------------------------------------------------------

    @Test
    void texasIdleMatchesOfficial() throws IOException {
        // 官方 spine-libgdx 3.5.51.1，Idle t=0.5
        SpineData d = load("texas");
        SpinePose pose = poseAt(d, "Idle", 0.5f);
        // 烟斗与刀都由 transform 约束驱动（setup mix=0，动画里变 1）
        assertBone(d, pose, "F_pocky", 18.899f, 243.301f, 1f);
        assertBone(d, pose, "F_Weapon", 10.402f, 145.953f, 1f);
        // 小腿由 2 骨 IK 驱动（弯膝方向弄反会差 10–20 单位）
        assertBone(d, pose, "F_L_Calf", 25.410f, 105.327f, 1f);
    }

    @Test
    void sariaIdleMatchesOfficial() throws IOException {
        SpineData d = load("saria");
        SpinePose pose = poseAt(d, "Idle", 0.5f);
        // 枪口挂在 F_Pinhead_A 下面，而 F_Pinhead_A 是位移混合约束的子骨：
        // 约束改完父骨必须重算子骨，否则枪口整块错位（实测偏 260 单位）
        assertBone(d, pose, "F_Muzzle", 128.317f, 362.261f, 1f);
        assertBone(d, pose, "F_Shield", -74.387f, 157.651f, 1f);
    }

    @Test
    void wIdleMatchesOfficial() throws IOException {
        SpineData d = load("w");
        SpinePose pose = poseAt(d, "Idle", 0.5f);
        // F_Gun / F_Weapon_SP 是 transformMode = noRotationOrReflection（不乘父级矩阵）
        assertBone(d, pose, "F_Gun", -8.422f, 173.198f, 1f);
        assertBone(d, pose, "F_Weapon_SP", 42.566f, 153.016f, 1f);
        assertBone(d, pose, "F_Muzzle", 115.263f, 87.903f, 1f);
    }

    @Test
    void bagpipeIkMixesComeFromAnimation() throws IOException {
        // bagpipe 的 IK 在 setup 里全是 mix=0，Idle 的 IK 时间轴把它抬到 1。
        // 若时间轴被丢弃，左右腿/手会停在 setup 姿态。
        SpineData d = load("bagpipe");
        SpinePose pose = poseAt(d, "Idle", 0.5f);
        int raised = 0;
        for (int i = 0; i < pose.ikMix.length; i++) {
            if (pose.ikMix[i] > 0.99f) {
                raised++;
            }
        }
        assertTrue(raised >= 5, "Idle 里应当有多条 IK 的 mix 被动画抬到 1，实测 " + raised);
    }

    // ------------------------------------------------------------------
    // 附件 / 隐藏部件
    // ------------------------------------------------------------------

    @Test
    void cuoraEyesAreVisible() throws IOException {
        // 眼睛靠 attachment 时间轴在首帧之后才挂上；首帧之前官方保留 setup 附件。
        // 以前这里被清成 null，整只 cuora 少两只眼睛（少 8 个顶点）。
        SpineData d = load("cuora");
        SpinePose pose = poseAt(d, "Idle", 0.5f);
        assertNotNull(pose.slotAttachment[slot(d, "F_L_eye")], "F_L_eye 不该是空的");
        assertNotNull(pose.slotAttachment[slot(d, "F_R_eye")], "F_R_eye 不该是空的");
    }

    @Test
    void blazeUnusedPartsStayTransparent() throws IOException {
        // blaze 有 146 个插槽，其中 70 来个是"另一只手/另一套表情/武器变体"，
        // 靠 slot alpha = 0 隐藏。alpha 没生效时头顶会多出一堆零件（实测截图确认）。
        SpineData d = load("blaze");
        SpinePose pose = poseAt(d, "Idle", 1.5f);
        int hidden = 0;
        for (int i = 0; i < d.slotCount(); i++) {
            if (pose.slotColor[3][i] < 0.01f) {
                hidden++;
            }
        }
        assertTrue(hidden > 40, "blaze 的隐藏插槽应当有几十个，实测 " + hidden);
        assertEquals(0f, pose.slotColor[3][slot(d, "F_Weapon2")], 0.01f, "F_Weapon2 应当隐藏");
    }

    @Test
    void amiyaIdleMatchesOfficial() throws IOException {
        // 通用对照（无活跃 transform 约束的模型）：确认拆约束时没有顺手把普通骨骼弄坏
        SpineData d = load("amiya");
        SpinePose pose = poseAt(d, "Idle", 0.5f);
        assertBone(d, pose, "F_Muzzle", 601.644f, 321.060f, 1f);
        assertBone(d, pose, "F_Head", -12.237f, 221.598f, 1f);
    }
}
