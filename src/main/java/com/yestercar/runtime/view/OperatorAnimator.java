package com.yestercar.runtime.view;

import com.yestercar.common.util.Director;
import com.yestercar.runtime.spine.MeshBuffer;
import com.yestercar.runtime.spine.SpineData;
import com.yestercar.runtime.spine.SpinePose;
import processing.core.PGraphics;
import processing.core.PImage;

/**
 * 一个**场上干员**的骨骼动画播放器（表现层）。
 *
 * <p>职责边界：只读 {@code UnitView} 与事件，从不回写战斗状态。
 *
 * <h2>状态机（方舟骨骼的真实动画名）</h2>
 * <pre>
 *   出现 → START(播一次) → IDLE(循环)
 *   出手 → ATTACK(播一次，用 attackAge 绝对定位，天然与引擎同帧)
 *   受击 → 不换动画，只染色（方舟骨骼里**没有** Hit 动画）
 *   死亡 → DIE(播一次，停在最后一帧，随后淡出)
 * </pre>
 *
 * <h2>为什么 ATTACK 用 setTime 而不是 update</h2>
 * {@code UnitView.attackAge()} 是「距上次出手过了多久」，出手瞬间归零 ——
 * 直接用 {@code setTime(attackAge)} 就与引擎的 1/30 秒固定步长对齐，
 * 不需要自己攒时间，也不会因为掉帧而漂移。
 */
public final class OperatorAnimator {

    /** 状态。 */
    public enum State {
        /** 登场。 */
        START,
        /** 待机（循环）。 */
        IDLE,
        /** 出手。 */
        ATTACK,
        /** 阵亡（播完停在最后一帧）。 */
        DIE
    }

    /** 死亡动画播完后再淡出多少秒。 */
    private static final float FADE_OUT = 0.55f;

    /** 模型在屏幕上的目标高度（格）。1 格 = 一个地砖。 */
    private static final float HEIGHT_TILES = 1.15f;

    /**
     * 受击染色：**亮红**，而且只在 {@link #HIT_TINT_MAX} 以内混合。
     *
     * <p>踩过的坑：最初用同事 viewer 里那档"深红"(0.72,0.06,0.08) 且强度拉满，
     * 结果战斗中频繁挨打的干员整个人变成一坨暗红黑影（截图实测），
     * 因为"染成深红"在满强度下等价于把贴图整片覆盖掉。
     * 现在改成"往亮红方向偏"，上限 0.6，既看得出挨打又不丢细节。
     */
    private static final float HIT_TINT_R = 1.0f;
    private static final float HIT_TINT_G = 0.30f;
    private static final float HIT_TINT_B = 0.30f;

    /** 受击染色最大强度（0–1）。 */
    private static final float HIT_TINT_MAX = 0.6f;

    /** 诊断开关：{@code -Dyestercar.spine.debug=1} 时每个干员首次绘制打印一行。 */
    private static final boolean DEBUG =
            "1".equals(System.getProperty("yestercar.spine.debug"));

    private boolean debugged;

    private final String operatorId;
    private final SpineData data;
    private final SpinePose pose;
    private final MeshBuffer mesh = new MeshBuffer();
    private final PImage texture;

    /** 解析出来的动画名（模型之间命名不完全一致，找不到就用兜底）。 */
    private final String animIdle;
    private final String animAttack;
    private final String animStart;
    private final String animDie;
    private final float durAttack;
    private final float durStart;
    private final float durDie;
    private final float modelHeight;

    private int col;
    private int row;
    private Director facing = Director.RIGHT;

    private State state = State.START;
    private float stateTime;
    private float dieTime;
    private boolean expired;

    /**
     * @param operatorId     干员 id（与 {@code operators.json} 一致）
     * @param data           已加载的模型
     * @param texture        图集贴图
     * @param attackInterval 该干员的攻击间隔（秒）；{@code <= 0} 表示未知（退回只看动画时长）
     */
    public OperatorAnimator(String operatorId, SpineData data, PImage texture,
                            float attackInterval) {
        this.operatorId = operatorId;
        this.data = data;
        this.texture = texture;
        this.pose = new SpinePose(data);

        this.animIdle = firstExisting(IDLE_NAMES);
        // 个别干员可以在 data/spine-anims.json 里指定"普攻用哪段动画"：
        // 玛恩纳没有 Attack，官方是拿 3 技能的循环动作当普攻（Skill_3_Loop）。
        String override = com.yestercar.common.model.SpineAnims.attackOf(operatorId);
        if (override != null && data.animations().contains(override)) {
            this.animAttack = override;
        } else {
            this.animAttack = firstExisting(ATTACK_NAMES) != null
                    ? firstExisting(ATTACK_NAMES) : animIdle;
        }
        this.animStart = firstExisting(START_NAMES) != null
                ? firstExisting(START_NAMES) : animIdle;
        this.animDie = firstExisting(DIE_NAMES) != null
                ? firstExisting(DIE_NAMES) : animIdle;
        this.durAttack = durationOr(animAttack, 0.01f);
        this.durStart = durationOr(animStart, 0f);
        this.durDie = durationOr(animDie, 0.01f);
        // 缩放基准用文件头的画布高（见 SpineData.artHeight 的说明），不用实测包围盒
        this.modelHeight = data.bodyHeight();
        this.attackInterval = attackInterval;

        if (animStart != null) {
            pose.play(animStart, false);
        }
    }

    /**
     * 该干员的攻击间隔（秒，来自 {@code operators.json}）。
     *
     * <p>它决定"一次出手最多占用多久的动画时间"，见 {@link #update} 里的
     * {@code attackAge} 判据 —— 少了它，攻击动画比间隔长的干员会永远停在攻击帧。
     */
    private final float attackInterval;

    /** 待机候选名（方舟各干员的命名并不统一）。 */
    public static final String[] IDLE_NAMES = {"Idle", "Idle_1", "Default", "Start"};

    /**
     * 出手候选名。
     *
     * <p>实测这批素材里至少有五种写法：{@code Attack} / {@code Attack_Loop}（循环挥砍）/
     * {@code Attack_Start + Attack_Loop + Attack_End}（连段）/ {@code Attack_2} / {@code Combat}；
     * 玛恩纳（mlynar）干脆没有 {@code Attack*}，只有 {@code Idle_2}。
     * 所以必须按优先级回退，否则"攻击时站着不动"（texas、bagpipe 实测过）。
     */
    public static final String[] ATTACK_NAMES = {"Attack", "Attack_Loop", "Attack_Start", "Attack_2",
        "Combat", "Skill_2_Attack", "Idle_2"};

    /** 登场候选名。 */
    public static final String[] START_NAMES = {"Start"};

    /** 阵亡候选名。 */
    public static final String[] DIE_NAMES = {"Die"};

    /**
     * 在候选里挑第一个模型真的有的动画（画廊工具与运行时共用，避免两套规则）。
     *
     * @param data       模型
     * @param candidates 候选名（按优先级）
     * @return 动画名；一个都没有时返回 {@code null}
     */
    public static String firstOf(SpineData data, String... candidates) {
        for (String name : candidates) {
            if (name != null && data.duration(name) >= 0f) {
                return name;
            }
        }
        return null;
    }

    /**
     * 在候选里挑第一个模型真的有的动画。
     *
     * @param candidates 候选名（按优先级）
     * @return 动画名；一个都没有时返回 {@code null}
     */
    private String firstExisting(String... candidates) {
        for (String name : candidates) {
            if (name != null && data.duration(name) >= 0f) {
                return name;
            }
        }
        return null;
    }

    /**
     * 动画时长，缺动画时给一个安全值（避免"模型没有动画"这种残缺素材把整层搞崩）。
     *
     * @param anim    动画名（可为 {@code null}）
     * @param fallback 没有该动画时的兜底时长
     * @return 秒
     */
    private float durationOr(String anim, float fallback) {
        return anim == null ? fallback : Math.max(fallback, data.duration(anim));
    }

    /** @return 干员 id */
    public String operatorId() {
        return operatorId;
    }

    /** @return 所在列 */
    public int col() {
        return col;
    }

    /** @return 所在行 */
    public int row() {
        return row;
    }

    /** @return 朝向 */
    public Director facing() {
        return facing;
    }

    /** @return 是否已经播完死亡动画、可以移出列表 */
    public boolean expired() {
        return expired;
    }

    /** @return 当前状态 */
    public State state() {
        return state;
    }

    /**
     * 同步位置与朝向（每帧调用）。
     *
     * @param newCol 列
     * @param newRow 行
     * @param f      朝向
     */
    public void setCell(int newCol, int newRow, Director f) {
        this.col = newCol;
        this.row = newRow;
        if (f != null) {
            this.facing = f;
        }
    }

    /**
     * 推进一帧。
     *
     * @param dt        本帧真实间隔（秒）
     * @param attackAge 距上次出手的时间（秒）；未知传 {@code -1}
     * @param alive     是否还活着
     */
    public void update(float dt, float attackAge, boolean alive) {
        stateTime += dt;
        if (!alive) {
            if (state != State.DIE) {
                state = State.DIE;
                dieTime = 0f;
                if (animDie != null) {
                    pose.play(animDie, false);
                }
                pose.setTime(0f);
            }
            dieTime += dt;
            pose.update(dt);
            if (dieTime > durDie + FADE_OUT) {
                expired = true;
            }
            return;
        }
        // 转成"尸体"之后又被 update 成活的？不会发生：调用方只在活着时调
        if (state == State.DIE) {
            return;
        }

        // 出手优先：attackAge 落在"这次出手允许占用的时间窗"内就播攻击。
        //
        // ⚠️ 这个窗口必须被**攻击间隔**夹住，不能只看 durAttack。
        //    Engine 的 timeSinceAttack() = max(0, 间隔 - 冷却)，而冷却**初始为 0**，
        //    所以刚部署（还没出手过）时 attackAge 恰好等于攻击间隔，并且会一直停在那儿。
        //    于是当 `间隔 < 动画时长` 时，旧判据 `attackAge < durAttack` 永远成立 ——
        //    干员被永久钉在攻击动画的第 `间隔` 秒那一帧上，待机/登场动画全被它顶掉。
        //    实测受害的 9 个：amiya、chen、dusk、exusiai、ines、kroos、myrtle、reed、skadi
        //    （它们的攻击动画都比攻击间隔长）。
        float span = attackInterval > 0.05f ? attackInterval : durAttack;
        if (attackAge >= 0f && attackAge < span - 1e-4f) {
            if (state != State.ATTACK) {
                state = State.ATTACK;
                if (animAttack != null) {
                    pose.play(animAttack, false);
                }
            }
            // 动画比间隔长时按比例压缩播放时长，保证一次出手能完整播完（而不是被砍掉半截）
            pose.setTime(durAttack > span ? attackAge * (durAttack / span) : attackAge);
            return;
        }
        // 登场没播完就继续播（除非刚才在攻击）
        if (state == State.START && stateTime < durStart) {
            pose.update(dt);
            return;
        }
        if (state != State.IDLE) {
            state = State.IDLE;
            if (animIdle != null) {
                pose.play(animIdle, true);
            }
        }
        pose.update(dt);
    }

    /**
     * 画到屏幕坐标。
     *
     * @param g      画布
     * @param sx     脚底所在屏幕 x
     * @param sy     脚底所在屏幕 y
     * @param px     该处每格多少像素（{@code Camera.project} 的 p[2]）
     * @param tint   受击染色强度 0–1
     */
    public void draw(PGraphics g, float sx, float sy, float px, float tint) {
        if (texture == null) {
            return;
        }
        mesh.clear();
        pose.fill(mesh);
        if (mesh.vertexCount() <= 0) {
            return;
        }
        float scale = (HEIGHT_TILES * px) / modelHeight;
        float flip = facing == Director.LEFT ? -1f : 1f;
        if (DEBUG && !debugged) {
            debugged = true;
            float[] c = mesh.rgba();
            float[] xy = mesh.xy();
            System.out.printf("[SPINE_DBG] id=%s tex=%dx%d verts=%d tris=%d v0=(%.1f,%.1f) "
                            + "rgba=(%.2f,%.2f,%.2f,%.2f) tint=%.2f scalar=%.3f bodyH=%.0f "
                            + "canvas=%s anim=%s/%s%n",
                    operatorId, texture.width, texture.height, mesh.vertexCount(), mesh.triCount(),
                    xy[0], xy[1], c[0], c[1], c[2], c[3], tint, scale, modelHeight,
                    g.getClass().getSimpleName(), animIdle, animAttack);
        }
        SpineMeshPainter.draw(g, texture, mesh, sx, sy, scale, flip,
                Math.min(HIT_TINT_MAX, tint), alpha(),
                HIT_TINT_R, HIT_TINT_G, HIT_TINT_B);
    }

    /**
     * 当前整体不透明度：活着是 1；死亡动画播完后按 {@link #FADE_OUT} 淡出。
     *
     * @return 0–1
     */
    public float alpha() {
        if (state != State.DIE) {
            return 1f;
        }
        float over = dieTime - durDie;
        if (over <= 0f) {
            return 1f;
        }
        float a = 1f - over / FADE_OUT;
        return a < 0f ? 0f : a;
    }
}
