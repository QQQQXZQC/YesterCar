package com.yestercar.runtime.view;

import com.yestercar.common.io.Workspace;
import com.yestercar.runtime.battle.BattleEvent;
import com.yestercar.runtime.battle.Enemy;
import com.yestercar.runtime.battle.EnemyType;
import com.yestercar.runtime.spine.SpineData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import processing.core.PApplet;
import processing.core.PImage;

/**
 * 敌人骨骼动画层：把 {@link Enemy} 映射成 {@link EnemyAnimator}。
 *
 * <h2>硬约束</h2>
 * 与 {@link SpineUnitLayer} 一致：**只读**引擎（{@code Enemy} 的公开 getter +
 * {@code BattleEvent}），不改 {@code runtime/battle/} 一行，也不把任何东西回写给逻辑。
 *
 * <h2>素材约定</h2>
 * {@code data/spine/enemy/<敌人 id>/} 下放 {@code *.skel} + {@code *.atlas} + {@code *.png}，
 * 目录名与 {@code enemies.csv} 的 {@code id} 一致。缺素材 / 解析失败 / 贴图读不到时
 * **不抛异常**，该敌人退回原来的色块占位（{@code drawEnemy} 里的椭圆），
 * 并在控制台打一行 {@code [骨骼] 退出 敌人 <id> → 原因}（每个 id 只打一次）。
 *
 * <h2>为什么用 IdentityHashMap</h2>
 * {@link Enemy} 没有对外暴露 id，只能按**对象身份**跟踪"同一只敌人"。
 * 引擎在 {@code scene.enemies()} 里交出的就是同一批对象引用，所以身份是稳定的；
 * 一旦某个引用消失，再结合 {@code DIE} 事件（带世界坐标）判断是"阵亡"还是"漏怪"。
 */
public final class SpineEnemyLayer {

    /** 骨骼素材根目录（相对工程根）。 */
    public static final String SPINE_DIR = "data/spine/enemy/";

    /** 该场战斗的模型缓存（id → 已解析模型）。 */
    private final Map<String, SpineData> models = new HashMap<>();

    /** 该场战斗的贴图缓存（id → 图集贴图）。 */
    private final Map<String, PImage> textures = new HashMap<>();

    /** 加载失败过的 id，避免每帧重试。 */
    private final java.util.Set<String> failed = new java.util.HashSet<>();

    /** 场上活着的敌人（key = 对象身份）。 */
    private final Map<Enemy, EnemyAnimator> live = new IdentityHashMap<>();

    /** 正在播死亡动画的尸体。 */
    private final List<EnemyAnimator> corpses = new ArrayList<>();

    private final PApplet applet;

    private boolean enabled = true;
    private int loadFailures;

    /** 本帧 DIE 事件的世界坐标（用来区分"阵亡"和"漏怪"）。 */
    private final List<float[]> diedPoints = new ArrayList<>();

    /** 诊断开关：{@code -Dyestercar.spine.debug=1} 时打印"敌人进入交手"（每个 id 一次）。 */
    private static final boolean DEBUG =
            "1".equals(System.getProperty("yestercar.spine.debug"));

    /** 已经打过"进入交手"日志的敌人 id。 */
    private final java.util.Set<String> attackLogged = new java.util.HashSet<>();

    /** 诊断：每只敌人上一帧的状态（只在 DEBUG 下维护）。 */
    private final Map<Enemy, EnemyAnimator.State> lastState = new IdentityHashMap<>();

    /** 诊断：本局状态切换次数（验证"移动不再抽搐"用）。 */
    private int transitions;

    /**
     * @param applet 宿主画布（用来 {@code loadImage}）
     */
    public SpineEnemyLayer(PApplet applet) {
        this.applet = applet;
    }

    /** @return 骨骼层是否可用 */
    public boolean enabled() {
        return enabled;
    }

    /** @return 加载失败的敌人数（诊断用） */
    public int loadFailures() {
        return loadFailures;
    }

    /** @return 本次战斗已加载的模型数 */
    public int loadedModels() {
        return models.size();
    }

    /**
     * @param e 敌人
     * @return 该敌人的动画器；素材缺失返回 {@code null}（调用方画色块）
     */
    public EnemyAnimator at(Enemy e) {
        return live.get(e);
    }

    /** @return 正在播死亡动画的尸体（调用方负责投影与绘制） */
    public List<EnemyAnimator> corpses() {
        return corpses;
    }

    /**
     * 推进一帧：同步场上敌人、收死亡事件、推进尸体。
     *
     * @param dt      本帧真实间隔（秒）
     * @param enemies 本帧场上敌人（{@code scene.enemies()}，对象身份稳定）
     * @param events  本帧事件（调用方传 {@code director.drainEvents()} 的结果，可为 null）
     */
    public void update(float dt, List<Enemy> enemies, List<BattleEvent> events) {
        if (!enabled) {
            return;
        }
        // ① 收集本帧 DIE 坐标（敌人阵亡与"漏怪"都会从列表消失，只有阵亡带 DIE 事件）
        diedPoints.clear();
        if (events != null) {
            for (BattleEvent e : events) {
                if (e.kind() == BattleEvent.Kind.DIE) {
                    diedPoints.add(new float[] {e.x(), e.y()});
                }
            }
        }

        // ② 同步活着的敌人
        java.util.Set<Enemy> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Enemy e : enemies) {
            seen.add(e);
            EnemyAnimator anim = live.get(e);
            if (anim == null) {
                anim = create(e);
                if (anim == null) {
                    continue;                       // 素材缺失 → 调用方画色块
                }
                live.put(e, anim);
            }
            anim.setPosition(e.x(), e.y(), dt);
            EnemyType t = e.type();
            // "在交手"的判据只用引擎已暴露的**状态**，不掺任何时间：
            //   ① 被阻挡（近战贴身打）；
            //   ② 远程敌人站定（引擎让远程敌人 hold 一段再出手，站定即开火姿态）；
            // 站定要连续 0.12 秒才算，避免拐弯那一帧闪成攻击动作。
            boolean engaged = e.blocked()
                    || (t != null && t.isRanged() && anim.stillTime() > 0.12f);
            anim.update(dt, engaged, t == null ? 1f : t.attackInterval(), e.attackAge(), true);
            if (DEBUG && t != null && anim.state() == EnemyAnimator.State.ATTACK
                    && attackLogged.add(t.id())) {
                // ASCII：方便在重定向的日志里 grep（控制台中文在 Windows 上会被转码）
                System.out.println("[spine] enemy ENGAGED id=" + t.id()
                        + " blocked=" + e.blocked() + " ranged=" + t.isRanged()
                        + " interval=" + t.attackInterval() + "s");
            }
            if (DEBUG) {
                // 状态切换计数：用来验证"移动时不再抽搐"（正常应当只有个位数）
                EnemyAnimator.State prev = lastState.put(e, anim.state());
                if (prev != null && prev != anim.state()) {
                    transitions++;
                    if (transitions <= 24) {
                        System.out.println("[spine] enemy SWITCH id=" + (t == null ? "?" : t.id())
                                + " " + prev + "->" + anim.state());
                    }
                }
            }
        }

        // ③ 消失的敌人：有 DIE 事件落在它附近 → 尸体；否则（漏怪/清场）直接移除
        java.util.Iterator<Map.Entry<Enemy, EnemyAnimator>> it = live.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Enemy, EnemyAnimator> entry = it.next();
            if (seen.contains(entry.getKey())) {
                continue;
            }
            EnemyAnimator anim = entry.getValue();
            Enemy gone = entry.getKey();
            if (diedByDamage(gone) || hasDiedNear(gone)) {
                anim.update(0f, false, 1f, 0f, false);   // 切到 DIE
                corpses.add(anim);
                if (DEBUG) {
                    System.out.println("[spine] enemy CORPSE id=" + idOf(gone)
                            + " hp=" + gone.hp() + " at=" + gone.x() + "," + gone.y()
                            + " dieAnim=" + anim.dieAnimation() + " corpses=" + corpses.size());
                }
            } else if (DEBUG) {
                System.out.println("[spine] enemy LEAKED(hp=" + gone.hp() + ") id=" + idOf(gone)
                        + " at=" + gone.x() + "," + gone.y());
            }
            it.remove();
        }

        // ④ 尸体推进 + 清理
        for (int i = corpses.size() - 1; i >= 0; i--) {
            EnemyAnimator c = corpses.get(i);
            c.update(dt, false, 1f, 0f, false);
            if (c.expired()) {
                corpses.remove(i);
            }
        }
    }

    /**
     * @param e 敌人
     * @return 敌人类型 id（诊断用；{@code Enemy} 本身没有 id getter）
     */
    private static String idOf(Enemy e) {
        return e != null && e.type() != null ? e.type().id() : "?";
    }

    /**
     * 判断一只刚消失的敌人是不是**被打死**的（要播死亡动画），而不是走进蓝门漏掉的。
     *
     * <h2>为什么看血量而不是看 DIE 事件</h2>
     * 最早这里是拿 {@code DIE} 事件的世界坐标去和敌人最后一帧位置做距离匹配，
     * 只要对不上（事件被别处先取走一帧、坐标是格子中心而不是浮点位置、两只怪挤在一起…）
     * 尸体就不会生成 —— 表现就是"血量清零的瞬间人直接没了"，也就是这一轮报上来的问题。
     *
     * <p>血量是**引擎自己的结算结果**，不会认错：
     * <ul>
     *   <li>被打死 → {@code takeDamage} 把 hp 夹到 0；</li>
     *   <li>走到蓝门 → {@code kill()} 只置 alive=false，<b>hp 不变</b>（还是满的）。</li>
     * </ul>
     * 所以 {@code hp <= 0} 就是"阵亡"，否则就是"漏怪/清场"。事件只作为兜底保留
     * （万一将来有"无伤害移除"的怪）。
     *
     * @param e 刚消失的敌人
     * @return {@code true} = 被打死，应当播死亡动画
     */
    static boolean diedByDamage(Enemy e) {
        return e != null && e.hp() <= 0f;
    }

    /**
     * 判断某个刚消失的敌人是不是阵亡（DIE 事件位置与它最后一帧位置足够近）。
     *
     * <p>敌人之间可能很近（同一格挤两只），所以容差取 0.9 格，并且**只用于"是否播死亡"**
     * 这一个表现决策：就算误判，最多是多播/少播一段死亡动画，不影响任何战斗数值。
     *
     * @param e 刚消失的敌人
     * @return 是否有 DIE 事件落在它附近
     */
    private boolean hasDiedNear(Enemy e) {
        for (float[] p : diedPoints) {
            float dx = p[0] - e.x();
            float dy = p[1] - e.y();
            if (dx * dx + dy * dy <= 0.9f * 0.9f) {
                return true;
            }
        }
        return false;
    }

    /**
     * 给一只敌人建动画器（首次见到时才加载素材）。
     *
     * @param e 敌人
     * @return 动画器；素材缺失返回 {@code null}
     */
    private EnemyAnimator create(Enemy e) {
        EnemyType t = e.type();
        if (t == null) {
            return null;
        }
        String id = t.id();
        if (failed.contains(id)) {
            return null;
        }
        SpineData data = models.get(id);
        PImage tex = textures.get(id);
        if (data == null || tex == null) {
            Path dir = Workspace.projectRoot().resolve(SPINE_DIR + id);
            if (!Files.isDirectory(dir)) {
                fail(id, "目录不存在: " + dir);
                return null;
            }
            try {
                data = SpineData.load(dir);
                tex = applet.loadImage(dir.resolve(data.atlas().page()).toString());
            } catch (IOException | RuntimeException ex) {
                fail(id, "解析失败: " + ex);
                return null;
            }
            if (tex == null) {
                fail(id, "贴图读不到: " + data.atlas().page());
                return null;
            }
            models.put(id, data);
            textures.put(id, tex);
        }
        return new EnemyAnimator(id, data, tex);
    }

    /**
     * 记一次加载失败（每个 id 只打印一次，避免刷屏）。
     *
     * @param id  敌人 id
     * @param why 原因
     */
    private void fail(String id, String why) {
        if (failed.add(id)) {
            loadFailures++;
            System.out.println("[骨骼] 退出 敌人 " + id + " → " + why);
        }
    }
}
