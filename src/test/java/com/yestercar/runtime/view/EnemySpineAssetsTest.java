package com.yestercar.runtime.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yestercar.common.io.Workspace;
import com.yestercar.runtime.battle.EnemyType;
import com.yestercar.runtime.spine.SpineData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 敌人骨骼素材契约 + 动画解析。
 *
 * <p>守三条：
 * <ol>
 *   <li>{@code enemies.csv} 里每个敌人都要有 {@code data/spine/enemy/<id>/} 且能解析；</li>
 *   <li>每个敌人都能解析出<b>待机 + 移动</b>动画名（否则站着时是死的）；</li>
 *   <li>攻击/阵亡允许缺失，但**必须登记在案**：幽灵（phantom）数值上 {@code atk = 0}
 *       本来就不攻击，所以它没有 Attack 动画是对的。</li>
 * </ol>
 */
class EnemySpineAssetsTest {

    /** 允许没有攻击动画的敌人（附原因，别随手往这里加）。 */
    private static final Map<String, String> NO_ATTACK = Map.of(
            "phantom", "官方的幽灵「攻击方式=不攻击」（伤害类型=无）：不可阻挡、近战，"
                    + "所以它本来就没有 Attack 动画");

    private static Path enemyRoot() {
        return Workspace.projectRoot().resolve("data").resolve("spine").resolve("enemy");
    }

    @Test
    void everyEnemyHasLoadableSpineAssets() throws IOException {
        Map<String, EnemyType> all = EnemyType.loadAll();
        assertFalse(all.isEmpty(), "敌人表不该为空");
        Path root = enemyRoot();
        Set<String> missing = new TreeSet<>();
        for (String id : all.keySet()) {
            Path dir = root.resolve(id);
            if (!Files.isDirectory(dir)) {
                missing.add(id + "(目录)");
                continue;
            }
            long skel = Files.list(dir).filter(p -> p.toString().endsWith(".skel")).count();
            long atlas = Files.list(dir).filter(p -> p.toString().endsWith(".atlas")).count();
            long png = Files.list(dir).filter(p -> p.toString().endsWith(".png")).count();
            if (skel != 1 || atlas != 1 || png != 1) {
                missing.add(id + "(文件数 skel=" + skel + " atlas=" + atlas + " png=" + png + ")");
            }
        }
        assertTrue(missing.isEmpty(), "缺骨骼素材的敌人: " + missing);
    }

    @Test
    void everyEnemyResolvesIdleAndMoveAnimations() throws IOException {
        Map<String, EnemyType> all = EnemyType.loadAll();
        Set<String> bad = new TreeSet<>();
        for (String id : all.keySet()) {
            SpineData d = SpineData.load(enemyRoot().resolve(id));
            String idle = EnemyAnimator.firstOf(d, EnemyAnimator.IDLE_NAMES);
            String move = EnemyAnimator.firstOf(d, EnemyAnimator.MOVE_NAMES);
            if (idle == null || move == null) {
                bad.add(id + "(idle=" + idle + " move=" + move + ")");
            }
        }
        assertTrue(bad.isEmpty(), "解析不出待机/移动动画的敌人: " + bad);
    }

    @Test
    void attackAnimationPresenceIsAsDocumented() throws IOException {
        Map<String, EnemyType> all = EnemyType.loadAll();
        for (String id : all.keySet()) {
            SpineData d = SpineData.load(enemyRoot().resolve(id));
            String attack = EnemyAnimator.firstOf(d, EnemyAnimator.ATTACK_NAMES);
            if (NO_ATTACK.containsKey(id)) {
                assertNull(attack, id + " 不该有攻击动画（" + NO_ATTACK.get(id) + "），"
                        + "但解析到了 " + attack + " —— 素材换了就要同步更新这张登记表");
            } else {
                assertNotNull(attack, id + " 缺少攻击动画");
            }
            assertNotNull(EnemyAnimator.firstOf(d, EnemyAnimator.DIE_NAMES), id + " 缺少 Die 动画");
        }
    }

    /** 素材根目录里不应有多余目录（防止改 id 后留下没人用的素材）。 */
    @Test
    void noOrphanEnemyAssetDirectories() throws IOException {
        Map<String, EnemyType> all = EnemyType.loadAll();
        Set<String> extra = new TreeSet<>();
        try (var dirs = Files.list(enemyRoot())) {
            for (Path p : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                String id = p.getFileName().toString();
                if (!all.containsKey(id)) {
                    extra.add(id);
                }
            }
        }
        assertTrue(extra.isEmpty(), "这些目录不对应任何敌人 id: " + extra);
    }

    /** 13 个敌人里有 12 个带攻击动画（幽灵除外）。 */
    @Test
    void attackAnimationCoverage() throws IOException {
        Map<String, EnemyType> all = EnemyType.loadAll();
        int withAttack = 0;
        for (String id : all.keySet()) {
            SpineData d = SpineData.load(enemyRoot().resolve(id));
            if (EnemyAnimator.firstOf(d, EnemyAnimator.ATTACK_NAMES) != null) {
                withAttack++;
            }
        }
        assertEquals(all.size() - NO_ATTACK.size(), withAttack,
                "带攻击动画的敌人数量与登记表不符");
    }
}
