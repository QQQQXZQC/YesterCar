package com.yestercar.runtime.battle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * 目标选择（**以 Defender 为准**，第 17 轮决定）。
 *
 * <h2>干员选敌 —— {@code targeting.js:192-200}</h2>
 * 排序键逐级比较：
 * <pre>
 *   ① 是否被我阻挡（我挡的那个优先）
 *   ② 职业/技能优先级   ← 我们暂时没有，恒 0
 *   ③ 嘲讽值（降序，高优先）  ← 我们暂时没有，恒 0
 *   ④ 到终点的剩余路程（升序）
 *   ⑤ 出生序号（升序）
 * </pre>
 * 上一版用"流场 dist 取整 + 最早进入范围"，两个键都有问题：
 * <b>取整</b>造成大量并列；<b>最早进入范围</b>那个 Map 从来没被写入过（死键）。
 * 现在距离改用 float，次级键换成 Defender 的"出生序号"。
 *
 * <h2>敌人选干员 —— {@code targeting.js:211-228}</h2>
 * <pre>
 *   ① 阻挡我的那个干员
 *   ② 嘲讽值（降序）  ← 暂无
 *   ③ 后部署优先（deploySeq 大者优先）
 * </pre>
 * Defender **没有距离项**，所以这里也不比较距离。
 */
final class TargetSelector {

    private TargetSelector() {
    }

    /**
     * 给干员挑一个敌人。
     *
     * @param unit      干员
     * @param enemies   场上敌人
     * @param blockedByMe 判定"这个敌人是不是我挡的"
     * @param remaining 到终点的剩余路程（**float，不要取整**）
     * @param taunt     嘲讽值（没有就给 0）
     * @param spawnSeq  出生序号
     * @return 目标；范围内没有敌人返回 {@code null}
     */
    static Enemy pickEnemy(OperatorUnit unit, List<Enemy> enemies,
                           Predicate<Enemy> blockedByMe,
                           ToDoubleFunction<Enemy> remaining,
                           ToIntFunction<Enemy> taunt,
                           ToLongFunction<Enemy> spawnSeq) {
        if (unit == null || !unit.alive() || enemies == null) {
            return null;
        }
        List<Enemy> cands = new ArrayList<>();
        for (Enemy e : enemies) {
            if (e == null || !e.alive()) {
                continue;
            }
            // ⚠️ 候选 = 射程内的敌人 **∪ 自己挡住的敌人**。
            //
            // 只取射程内是不够的：阻挡用 0.707 格半径，敌人会停在干员**侧后方**
            // （面朝右时常常停在左边那格）。此时敌人不在攻击范围内，
            // 干员就永远打不到自己挡着的人 —— 表现为"敌人在眼前却不动"。
            //
            // Defender 在 ai.js:133-137 就是显式补了这一步：
            //   if (u.blocking.length) for (const e of b.blockedTargets(u, prof)) …
            boolean inRange = unit.covers(e.col(), e.row());
            boolean mine = blockedByMe != null && blockedByMe.test(e);
            if (inRange || mine) {
                cands.add(e);
            }
        }
        if (cands.isEmpty()) {
            return null;
        }
        Comparator<Enemy> cmp = Comparator
                .comparingInt((Enemy e) -> blockedByMe != null && blockedByMe.test(e) ? 0 : 1)
                .thenComparingInt(e -> taunt == null ? 0 : -taunt.applyAsInt(e))
                .thenComparingDouble(e -> remaining == null ? 0d : remaining.applyAsDouble(e))
                .thenComparingLong(e -> spawnSeq == null ? 0L : spawnSeq.applyAsLong(e));
        cands.sort(cmp);
        return cands.get(0);
    }

    /**
     * 给敌人挑一个要打的干员。
     *
     * @param enemy     敌人
     * @param units     场上干员
     * @param isBlocker 判定"这个干员是不是正挡着我"
     * @return 目标；范围内没有干员返回 {@code null}
     */
    static OperatorUnit pickOperator(Enemy enemy, List<OperatorUnit> units,
                                     Predicate<OperatorUnit> isBlocker) {
        if (enemy == null || !enemy.alive() || units == null) {
            return null;
        }
        List<OperatorUnit> cands = new ArrayList<>();
        for (OperatorUnit u : units) {
            if (u != null && u.alive()) {
                cands.add(u);
            }
        }
        if (cands.isEmpty()) {
            return null;
        }
        Comparator<OperatorUnit> cmp = Comparator
                .comparingInt((OperatorUnit u) -> isBlocker != null && isBlocker.test(u) ? 0 : 1)
                .thenComparingLong(u -> -u.deploySeq());
        cands.sort(cmp);
        return cands.get(0);
    }
}
