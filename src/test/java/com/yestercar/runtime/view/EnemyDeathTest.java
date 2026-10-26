package com.yestercar.runtime.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.runtime.battle.Enemy;
import com.yestercar.runtime.battle.EnemyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "敌人是不是被打死的"判定（决定要不要播死亡动画）。
 *
 * <h2>这条判据为什么必须存在</h2>
 * 引擎里"阵亡"和"漏进蓝门"最终都是 {@code Enemy.kill()}（{@code alive=false}），
 * 光看存活标志分不出来。之前是用 {@code DIE} 事件的世界坐标做距离匹配，
 * 一旦对不上尸体就不生成 —— 表现就是**血量清零的瞬间敌人凭空消失**（本轮报的问题）。
 *
 * <p>现在改成看血量：{@code takeDamage} 会把 hp 夹到 0，而漏怪只置 alive=false、hp 原封不动。
 */
class EnemyDeathTest {

    private static final EnemyType[] TYPES = {null};

    private static EnemyType type() {
        if (TYPES[0] == null) {
            TYPES[0] = EnemyType.loadAll().get("soldier");
        }
        return TYPES[0];
    }

    @Test
    @DisplayName("★ 血量清零 = 被打死（要播死亡动画）")
    void 血量清零是阵亡() {
        Enemy e = new Enemy(type(), 1.5f, 1.5f, 0);
        assertFalse(SpineEnemyLayer.diedByDamage(e), "满血时不该判成阵亡");
        e.takeDamage(99999f);
        assertEquals(0f, e.hp(), 1e-4f, "血量应当被夹到 0");
        assertTrue(SpineEnemyLayer.diedByDamage(e), "血量清零应当判成阵亡");
    }

    @Test
    @DisplayName("★ 走进蓝门（kill 但不掉血）不算阵亡")
    void 漏怪不是阵亡() {
        Enemy e = new Enemy(type(), 11.5f, 7.5f, 0);
        float hp = e.hp();
        e.kill();
        assertFalse(e.alive(), "应当已离场");
        assertEquals(hp, e.hp(), 1e-4f, "漏怪不掉血");
        assertFalse(SpineEnemyLayer.diedByDamage(e), "漏怪不该播死亡动画");
    }

    /** 空指针防御：没有类型也不该炸。 */
    @Test
    void 空值安全() {
        assertFalse(SpineEnemyLayer.diedByDamage(null));
    }
}
