package com.yestercar.runtime.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.common.io.Workspace;
import com.yestercar.runtime.spine.SpineData;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link EnemyAnimator} 的状态机（无 GL：贴图传 {@code null}，{@code draw} 直接跳过）。
 *
 * <p>这些用例对应"局内看到的东西"：
 * 走动 → Move、站住/被挡 → Attack、被打死 → Die 且淡出、
 * 以及"幽灵没有攻击动画时不许硬凑假动作"。
 */
class EnemyAnimatorTest {

    private static final float DT = 1f / 30f;         // 与战斗引擎同样的固定步长

    private static SpineData load(String id) throws IOException {
        Path dir = Workspace.projectRoot().resolve("data").resolve("spine")
                .resolve("enemy").resolve(id);
        return SpineData.load(dir);
    }

    /** 造一个动画器（贴图为 null，不影响状态机）。 */
    private static EnemyAnimator animator(String id) throws IOException {
        return new EnemyAnimator(id, load(id), null);
    }

    /** 走一段时间，位置按 speed 格/秒推进。 */
    private static void walk(EnemyAnimator a, float seconds, float speed) {
        float x = 0f;
        int steps = Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) {
            x += speed * DT;
            a.setPosition(x, 0f, DT);
            a.update(DT, false, 1f, 0f, true);
        }
    }

    /** 原地站一段时间（位置不变）。 */
    private static void stand(EnemyAnimator a, float seconds) {
        for (float t = 0; t < seconds; t += DT) {
            a.setPosition(1f, 0f, DT);
            a.update(DT, false, 1f, 0f, true);
        }
    }

    @Test
    void startsInIdleWithIdleAnimation() throws IOException {
        for (String id : new String[] {"soldier", "originium_slug", "big_bob", "patriot"}) {
            EnemyAnimator a = animator(id);
            assertEquals(EnemyAnimator.State.IDLE, a.state(), id + " 初始应当待机");
            assertEquals(a.idleAnimation(), a.currentAnimation(), id + " 初始应当播待机动画");
            assertTrue(a.alpha() == 1f, id + " 活着时应当完全不透明");
        }
    }

    @Test
    void movingSwitchesToMoveAnimation() throws IOException {
        for (String id : new String[] {"soldier", "hound_pro", "big_bob", "crusher"}) {
            EnemyAnimator a = animator(id);
            walk(a, 0.5f, 1.0f);
            assertEquals(EnemyAnimator.State.MOVE, a.state(), id + " 走动时应当是 MOVE");
            // 有 Move_Begin 的模型会先播过渡，过渡完必须落到循环移动动画上
            walk(a, 1.0f, 1.0f);
            String cur = a.currentAnimation();
            assertTrue(cur != null && (cur.equals(a.moveAnimation())
                            || cur.startsWith("Move_Begin") || cur.startsWith("Run_Begin")),
                    id + " 走动时应当播移动动画，实际 " + cur);
        }
    }

    @Test
    void facingFollowsHorizontalMotion() throws IOException {
        EnemyAnimator a = animator("soldier");
        // 往 +x 走
        for (int i = 0; i < 30; i++) {
            a.setPosition(0.03f * i, 0f, DT);
            a.update(DT, false, 1f, 0f, true);
        }
        assertFalse(a.faceLeft(), "往右走不该朝左");
        // 往 -x 走
        for (int i = 30; i > 0; i--) {
            a.setPosition(0.03f * i, 0f, DT);
            a.update(DT, false, 1f, 0f, true);
        }
        assertTrue(a.faceLeft(), "往左走应当朝左");
    }

    @Test
    void engagedPlaysAttackAndLoopsByInterval() throws IOException {
        EnemyAnimator a = animator("soldier");
        stand(a, 0.2f);
        // 进入交手：相位由引擎的 attackAge 决定（表现层不再自己数拍子）
        float interval = 1.0f;
        float age = 0f;
        a.update(DT, true, interval, age, true);
        assertEquals(EnemyAnimator.State.ATTACK, a.state());
        assertEquals(a.attackAnimation(), a.currentAnimation());

        // 跑满一个间隔：动画时间跟着 attackAge 走（不会超过动画自身时长）
        for (int i = 0; i < 30; i++) {
            age = Math.min(interval, age + DT);
            a.update(DT, true, interval, age, true);
        }
        assertEquals(EnemyAnimator.State.ATTACK, a.state(), "一直在交手就该一直是 ATTACK");

        // 间隔到点后引擎把 attackAge 归零（重新起手），仍在交手状态
        a.update(DT, true, interval, 0f, true);
        assertEquals(EnemyAnimator.State.ATTACK, a.state(), "相位归零后仍是 ATTACK");
    }

    @Test
    void deathPlaysDieThenFadesAndExpires() throws IOException {
        EnemyAnimator a = animator("soldier");
        walk(a, 0.3f, 1.0f);
        // 第一帧死亡：切 DIE
        a.update(DT, false, 1f, 0f, false);
        assertEquals(EnemyAnimator.State.DIE, a.state());
        assertEquals(a.dieAnimation(), a.currentAnimation());
        assertFalse(a.expired(), "刚死不该立刻过期");
        assertEquals(1f, a.alpha(), 1e-4f, "死亡动画播完前应当完全不透明");

        // ① 死亡动画播完时：仍然完全不透明（开始"定格"，而不是立刻化掉）
        float dieDur = load("soldier").duration(a.dieAnimation());
        while (a.dieElapsed() < dieDur - DT) {
            a.update(DT, false, 1f, 0f, false);
        }
        assertEquals(1f, a.alpha(), 1e-4f, "Die 播完的瞬间应当还是实心的");
        assertFalse(a.expired(), "定格期间不该过期");

        // ② 定格期（DEATH_HOLD）内也保持不透明
        for (float t = 0; t < EnemyAnimator.DEATH_HOLD * 0.5f; t += DT) {
            a.update(DT, false, 1f, 0f, false);
        }
        assertEquals(1f, a.alpha(), 1e-4f, "定格期内尸体应当保持不透明");

        // ③ 定格结束 + 淡出 0.55s 之后才过期
        for (float t = 0; t < EnemyAnimator.DEATH_HOLD + 0.8f; t += DT) {
            a.update(DT, false, 1f, 0f, false);
        }
        assertTrue(a.expired(), "死亡动画 + 定格 + 淡出之后应当过期，让尸体被移除");
        assertEquals(0f, a.alpha(), 1e-4f, "过期时应当已经完全透明");
    }

    /** 死亡过程中 alpha 必须单调下降（不能闪一下又实心）。 */
    @Test
    void deathFadeIsMonotonic() throws IOException {
        EnemyAnimator a = animator("soldier");
        a.update(DT, false, 1f, 0f, false);
        float prev = a.alpha();
        for (int i = 0; i < 200 && !a.expired(); i++) {
            a.update(DT, false, 1f, 0f, false);
            float cur = a.alpha();
            assertTrue(cur <= prev + 1e-5f, "alpha 不应回升: " + prev + " -> " + cur);
            prev = cur;
        }
    }

    /**
     * 幽灵（phantom）没有 Attack 动画：交手时**不能**硬凑一个动作，
     * 应当保持待机姿势（它在数值上本来就不攻击）。
     */
    @Test
    void ghostWithoutAttackStaysIdleWhenEngaged() throws IOException {
        EnemyAnimator a = animator("phantom");
        assertNull(a.attackAnimation(), "幽灵素材本来就没有 Attack 动画");
        stand(a, 0.2f);
        for (int i = 0; i < 60; i++) {
            a.update(DT, true, 1.0f, 0f, true);
        }
        assertEquals(EnemyAnimator.State.IDLE, a.state(), "没有攻击动画就停在待机");
        assertEquals(a.idleAnimation(), a.currentAnimation());
    }

    /** 每个敌人的动画名都能在模型里找到（防止候选表写错导致整段动不起来）。 */
    @Test
    void resolvedAnimationNamesExistInModel() throws IOException {
        for (String id : new String[] {"soldier", "originium_slug", "hound_pro", "phantom",
                "dual_swordsman", "scavenger", "berserk_leader", "sarkaz_greatsword",
                "heavy_defender", "caster_elite", "crusher", "big_bob", "patriot"}) {
            SpineData d = load(id);
            EnemyAnimator a = new EnemyAnimator(id, d, null);
            assertNotNull(a.idleAnimation(), id + " 没有待机动画");
            assertNotNull(a.moveAnimation(), id + " 没有移动动画");
            assertNotNull(a.dieAnimation(), id + " 没有死亡动画");
            assertTrue(d.duration(a.idleAnimation()) >= 0f);
            assertTrue(d.duration(a.moveAnimation()) >= 0f);
            assertTrue(d.duration(a.dieAnimation()) >= 0f);
            if (a.attackAnimation() != null) {
                assertTrue(d.duration(a.attackAnimation()) > 0f,
                        id + " 的攻击动画时长应当 > 0");
            }
        }
    }

    /**
     * 60fps 渲染 + 1/30 秒逻辑 tick 时，移动状态**不许**抖动。
     *
     * <h2>缺陷复盘</h2>
     * 旧实现按"这一帧位置有没有变"当帧判移动。引擎是固定 1/30 秒 tick，
     * 而渲染 60fps —— 有一半的帧位置根本不变，于是状态机在 60Hz 上反复
     * IDLE ⇄ MOVE，每切一次都会 {@code pose.play()} 把移动动画从 0 重播，
     * 看起来就是"敌人走路一直在抽搐"。
     *
     * <p>修复是在 {@link EnemyAnimator} 里加停步迟滞（{@code STOP_GRACE}）。
     * 这条用例用"位置每两帧才前进一次"复现那个场景，断言稳态里**一次状态切换都没有**。
     */
    @Test
    void movementDoesNotFlapWhenLogicTickIsSlowerThanRender() throws IOException {
        String id = "soldier";
        EnemyAnimator a = animator(id);
        int transitions = 0;
        EnemyAnimator.State prev = a.state();
        float x = 0f;
        // 2 秒 × 60fps：位置每两帧（=1/30 秒）才前进一次，模拟引擎的真实节奏
        for (int frame = 0; frame < 120; frame++) {
            if (frame % 2 == 0) {
                x += 0.035f;                        // 约 1.05 格/秒
            }
            a.setPosition(x, 0f, 1f / 60f);
            a.update(1f / 60f, false, 1f, 0f, true);
            if (a.state() != prev) {
                transitions++;
                prev = a.state();
            }
        }
        assertEquals(EnemyAnimator.State.MOVE, a.state(), "全程都在移动");
        assertEquals(1, transitions,
                "60fps 渲染下只允许 IDLE→MOVE 这一次切换，实测切换 " + transitions + " 次");
        assertEquals(a.moveAnimation(), a.currentAnimation(),
                "稳态应当停在移动循环动画上");
    }

    /** 真的停下来之后，迟滞窗一过就要切回待机（迟滞不能把"停"也吃掉）。 */
    @Test
    void stoppingStillReachesIdleAfterGrace() throws IOException {
        EnemyAnimator a = animator("soldier");
        float x = 0f;
        for (int frame = 0; frame < 60; frame++) {          // 先走 1 秒
            x += 0.035f;
            a.setPosition(x, 0f, 1f / 60f);
            a.update(1f / 60f, false, 1f, 0f, true);
        }
        assertEquals(EnemyAnimator.State.MOVE, a.state());
        for (int frame = 0; frame < 60; frame++) {          // 再站 1 秒
            a.setPosition(x, 0f, 1f / 60f);
            a.update(1f / 60f, false, 1f, 0f, true);
        }
        assertEquals(EnemyAnimator.State.IDLE, a.state(), "停满 1 秒后应当回到待机");
    }
}
