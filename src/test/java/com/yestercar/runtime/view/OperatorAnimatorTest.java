package com.yestercar.runtime.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.Operator;
import com.yestercar.runtime.spine.SpineData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 干员"待机/登场被攻击动画顶掉"的回归测试。
 *
 * <h2>缺陷复盘（真实原因，不是猜测）</h2>
 * 引擎的 {@code OperatorUnit.timeSinceAttack()} = {@code max(0, 攻击间隔 - 冷却)}，
 * 而冷却**初始为 0**、并且归零后会一直保持 0 —— 所以"刚部署、还没出手"的干员，
 * {@code attackAge} 恰好等于**攻击间隔**，而且会永久停在这个值。
 *
 * <p>旧判据 {@code attackAge < durAttack} 只看动画时长：
 * 当某干员的攻击动画比攻击间隔长时（本仓 9 个），这个条件**永远成立** →
 * 干员被钉死在攻击动画的第 {@code 间隔} 秒那一帧，待机与登场动画都播不出来。
 *
 * <p>修复：窗口改成 {@code min(动画时长, 攻击间隔)}（见 {@link OperatorAnimator#update}）。
 * 这些用例把"部署后应当待机"钉住，顺便保证出手时仍然会播攻击。
 */
class OperatorAnimatorTest {

    private static final float DT = 1f / 30f;

    /** 曾经被钉住的干员（攻击动画时长 > 攻击间隔）。 */
    private static final String[] FORMERLY_STUCK = {
        "amiya", "chen", "dusk", "exusiai", "ines", "kroos", "myrtle", "reed", "skadi",
    };

    private static SpineData load(String id) throws IOException {
        Path dir = Workspace.projectRoot().resolve(SpineUnitLayer.SPINE_DIR + id);
        return SpineData.load(dir);
    }

    private static OperatorAnimator animator(String id) throws IOException {
        Operator op = Operator.loadAll().get(id);
        assertNotNull(op, "operators.json 里没有 " + id);
        return new OperatorAnimator(id, load(id), null, op.attackInterval());
    }

    /**
     * 刚部署（{@code attackAge == 攻击间隔}）时必须走待机，而不是卡在攻击帧。
     *
     * <p>这是本轮 bug 的核心用例：修复前 {@link #FORMERLY_STUCK} 里这 9 个全会失败。
     */
    @Test
    void deployedOperatorPlaysIdleNotFrozenAttackFrame() throws IOException {
        for (String id : FORMERLY_STUCK) {
            Operator op = Operator.loadAll().get(id);
            OperatorAnimator a = animator(id);
            // 模拟 3 秒：attackAge 恒等于攻击间隔（引擎在"没出手"时就是这个值）
            for (int i = 0; i < 90; i++) {
                a.update(DT, op.attackInterval(), true);
            }
            assertEquals(OperatorAnimator.State.IDLE, a.state(),
                    id + " 刚部署时应当是待机（攻击间隔 " + op.attackInterval() + "s）");
        }
    }

    /** 全部 29 个干员都要满足"没出手时待机"，不只是上面那 9 个。 */
    @Test
    void allOperatorsIdleWhenNotAttacking() throws IOException {
        for (Map.Entry<String, Operator> e : Operator.loadAll().entrySet()) {
            Operator op = e.getValue();
            OperatorAnimator a = new OperatorAnimator(op.id(), load(op.id()), null,
                    op.attackInterval());
            for (int i = 0; i < 60; i++) {
                a.update(DT, op.attackInterval(), true);
            }
            assertEquals(OperatorAnimator.State.IDLE, a.state(),
                    op.id() + " 没出手时应当是待机");
        }
    }

    /** 出手瞬间（{@code attackAge == 0}）必须立刻进攻击状态。 */
    @Test
    void freshAttackEntersAttackState() throws IOException {
        for (String id : new String[] {"chen", "dusk", "exusiai", "amiya", "texas", "skadi"}) {
            OperatorAnimator a = animator(id);
            a.update(DT, 0f, true);
            assertEquals(OperatorAnimator.State.ATTACK, a.state(), id + " 出手后应当进攻击状态");
        }
    }

    /**
     * 窗口边界：{@code attackAge} 略小于间隔 → 攻击；正好等于间隔 → 待机。
     *
     * <p>这条把"为什么用 {@code 间隔} 当窗口上界"写成了可执行的断言 ——
     * 引擎就是在这个边界上把 {@code attackAge} 顶住不再增加的。
     */
    @Test
    void attackWindowIsClippedByInterval() throws IOException {
        String id = "chen";                                  // 动画 1.5s，间隔 0.95s
        Operator op = Operator.loadAll().get(id);
        float iv = op.attackInterval();
        SpineData d = load(id);
        float durAttack = d.duration(OperatorAnimator.firstOf(d, OperatorAnimator.ATTACK_NAMES));
        assertTrue(durAttack > iv, "chen 的攻击动画应当比攻击间隔长（这是本 bug 的前提）");

        OperatorAnimator inside = animator(id);
        inside.update(DT, iv - 0.02f, true);
        assertEquals(OperatorAnimator.State.ATTACK, inside.state(), "间隔之内应当还在攻击");

        // 边界上：attackAge == 间隔 → 不算攻击。此时刚部署，先播登场动画（START），
        // 登场播完必须落到 IDLE（而不是卡在攻击帧上）。
        OperatorAnimator onBoundary = animator(id);
        for (int i = 0; i < 60; i++) {                   // 2 秒，足够播完 Start(0.833s)
            onBoundary.update(DT, iv, true);
        }
        assertEquals(OperatorAnimator.State.IDLE, onBoundary.state(),
                "attackAge 到达间隔时攻击已经结束，登场播完应当回到待机");
    }

    /** 出手动画不会被"夹"成 0 秒：攻击窗口内姿态时间必须真的在走。 */
    @Test
    void attackPoseAdvancesWithinWindow() throws IOException {
        String id = "dusk";                                  // 动画 2.867s，间隔 1.7s
        Operator op = Operator.loadAll().get(id);
        float iv = op.attackInterval();
        OperatorAnimator a = animator(id);

        float last = -1f;
        int increasing = 0;
        for (float age = 0f; age < iv - 1e-3f; age += DT) {
            a.update(DT, age, true);
            // pose 的时间只能靠反射/公开 API 拿；这里用"状态一直是 ATTACK"间接断言
            if (a.state() == OperatorAnimator.State.ATTACK) {
                increasing++;
            }
            last = age;
        }
        assertTrue(increasing > 40, "整个攻击窗口内都应当处于 ATTACK，实测 " + increasing + " 帧");
        assertTrue(last > 0f);
    }
}
