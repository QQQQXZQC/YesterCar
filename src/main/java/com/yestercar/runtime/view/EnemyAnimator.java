package com.yestercar.runtime.view;

import com.yestercar.runtime.spine.MeshBuffer;
import com.yestercar.runtime.spine.SpineData;
import com.yestercar.runtime.spine.SpinePose;
import processing.core.PGraphics;
import processing.core.PImage;

/**
 * 一个**场上敌人**的骨骼动画播放器（纯表现层）。
 *
 * <p>职责边界与 {@link OperatorAnimator} 相同：只读引擎状态，从不回写战斗数据。
 *
 * <h2>敌我骨骼的差别（实测本仓 13 个敌人素材）</h2>
 * <ul>
 *   <li>敌人多一套**移动**动画，命名还很散：{@code Move} / {@code Move_Loop} /
 *       {@code Move_1} / {@code Run_Loop} / {@code Move_Down} / {@code Move_Up} /
 *       {@code x_Move_Loop}；有的还分 {@code Move_Begin → …_Loop → Move_End} 三段；</li>
 *   <li>攻击动画：{@code Attack} / {@code Attack_1} / {@code Attack_2} / {@code Attack2} /
 *       {@code Attack_NoPre}；</li>
 *   <li><b>幽灵（phantom）没有 Attack 动画</b> —— 它在 {@code enemies.csv} 里 {@code atk = 0}、
 *       穿过干员不攻击，所以"没有攻击动作"是**对的**，这里也不硬凑假动作；</li>
 *   <li>爱国者（patriot）另有 {@code revive_1..3}（转阶段）与 {@code Skill}，
 *       但敌人表里没有"阶段/技能"字段，这两段先不接（见 docs/敌人骨骼动画接入.md）。</li>
 * </ul>
 *
 * <h2>状态机</h2>
 * <pre>
 *   移动中 → MOVE（有 Move_Begin 就先播一次再进 Move_Loop）
 *   停下   → IDLE（有 Move_End 就先播一次再进 Idle）
 *   交手   → ATTACK（按 type().attackInterval() 的节拍循环；没有攻击动画就停在 IDLE）
 *   阵亡   → DIE（播一次，停在最后一帧，随后 0.55s 淡出）
 * </pre>
 *
 * <h2>攻击节拍从哪来</h2>
 * 相位由引擎的 {@code Enemy.attackAge()} 直接给出（不再自己数拍子），
 * 所以"动画打到人"的那一帧与引擎扣血的那一 tick 是同一时刻；
 * 命中时刻表见 {@code data/enemy-attack.csv}。
 */
public final class EnemyAnimator {

    /** 状态。 */
    public enum State {
        /** 移动（循环）。 */
        MOVE,
        /** 停下待机（循环）。 */
        IDLE,
        /** 交手（按攻击间隔循环出手）。 */
        ATTACK,
        /** 阵亡（播一次后淡出）。 */
        DIE
    }

    /** 死亡动画播完后再淡出多少秒。 */
    private static final float FADE_OUT = 0.55f;

    /**
     * 死亡动画播完后**定格**多久再开始淡出（秒）。
     *
     * <p>为什么要定格：`Die` 的最后一帧是"倒地不动"，如果紧接着就开始淡出，
     * 观感是"刚倒下就化掉了"，没有"打死"的实感。留 0.25 秒让玩家看清尸体躺在那儿，
     * 再花 0.55 秒淡出，整个死亡过程约 1.2–1.5 秒。
     */
    public static final float DEATH_HOLD = 0.25f;

    /** 倒地尘土的持续时间（秒）。 */
    private static final float DUST_SECONDS = 0.45f;

    /** 尸体脚下影子的半径（格）。 */
    private static final float CORPSE_SHADOW_RADIUS = 0.26f;

    /** 模型在屏幕上的目标高度（格）。敌人比干员略矮一点。 */
    public static final float HEIGHT_TILES = 0.95f;

    /** 判定"在移动"的速度阈值（格/秒）。低于它当作站住。 */
    private static final float MOVE_EPS = 0.05f;

    /**
     * 停步迟滞（秒）：位置不变要持续这么久才真的切回 IDLE。
     *
     * <h2>为什么必须要这一条</h2>
     * 引擎以固定 1/30 秒 tick 推进，而渲染可能是 60/120 fps ——
     * 也就是说**有一半的渲染帧位置根本没变**。如果按"这一帧位置没动"就切 IDLE，
     * 状态机就会在 60Hz 上反复 IDLE ⇄ MOVE：每切一次都 {@code pose.play()} 把动画
     * 重新从 0 播一遍，看起来就是"敌人走路一直在抽搐"。
     * 迟滞到 0.15 秒（约 4.5 个逻辑 tick）之后，只有真正停下来的敌人才会切状态。
     */
    private static final float STOP_GRACE = 0.15f;

    /** 受击染色（与干员同一套亮红参数，保持观感一致）。 */
    private static final float HIT_TINT_R = 1.0f;
    private static final float HIT_TINT_G = 0.30f;
    private static final float HIT_TINT_B = 0.30f;
    private static final float HIT_TINT_MAX = 0.6f;

    /** 移动动画候选（{@code Move_Loop} 优先，其次整段 {@code Move}）。 */
    public static final String[] MOVE_NAMES = {"Move_Loop", "Move", "Move_1", "Move_2",
        "Run_Loop", "Move_Down", "Move_Up", "x_Move_Loop"};

    /** 起步过渡（播一次）。 */
    public static final String[] MOVE_BEGIN_NAMES = {"Move_Begin", "Run_Begin"};

    /** 停步过渡（播一次）。 */
    public static final String[] MOVE_END_NAMES = {"Move_End", "Run_End"};

    /** 待机候选。 */
    public static final String[] IDLE_NAMES = {"Idle", "Idle_1", "Idle_2", "Default"};

    /** 攻击候选。 */
    public static final String[] ATTACK_NAMES = {"Attack", "Attack_1", "Attack_2", "Attack2",
        "Attack_NoPre"};

    /** 阵亡候选。 */
    public static final String[] DIE_NAMES = {"Die"};

    /**
     * 在候选里挑第一个模型真有的动画（画廊工具与运行时共用同一套规则）。
     *
     * @param data       模型
     * @param candidates 候选名（按优先级）
     * @return 动画名；一个都没有返回 {@code null}
     */
    public static String firstOf(SpineData data, String... candidates) {
        for (String name : candidates) {
            if (name != null && data.duration(name) >= 0f) {
                return name;
            }
        }
        return null;
    }

    private final String enemyId;
    private final SpineData data;
    private final SpinePose pose;
    private final MeshBuffer mesh = new MeshBuffer();
    private final PImage texture;

    private final String animIdle;
    private final String animMove;
    private final String animMoveBegin;
    private final String animMoveEnd;
    private final String animAttack;
    private final String animDie;
    private final float durMoveBegin;
    private final float durMoveEnd;
    private final float durAttack;
    private final float durDie;
    private final float modelHeight;

    private State state = State.IDLE;
    private float dieTime;
    private boolean expired;
    private boolean moving;
    private float lastX;
    private float lastY;
    private boolean hasLast;

    /** 朝向：世界 +x 为右，朝左时水平镜像。 */
    private boolean faceLeft;

    /** 连续站住了多久（秒）；远程敌人"站定 = 开火"要用它去抖。 */
    private float stillTime;

    /**
     * @param enemyId 敌人 id（与 {@code enemies.csv} 一致）
     * @param data    已加载模型
     * @param texture 图集贴图（可为 {@code null}：此时 {@link #draw} 直接跳过，便于无 GL 的测试）
     */
    public EnemyAnimator(String enemyId, SpineData data, PImage texture) {
        this.enemyId = enemyId;
        this.data = data;
        this.texture = texture;
        this.pose = new SpinePose(data);

        this.animIdle = firstOf(data, IDLE_NAMES);
        this.animMove = firstOf(data, MOVE_NAMES);
        this.animMoveBegin = firstOf(data, MOVE_BEGIN_NAMES);
        this.animMoveEnd = firstOf(data, MOVE_END_NAMES);
        this.animAttack = firstOf(data, ATTACK_NAMES);
        this.animDie = firstOf(data, DIE_NAMES);
        this.durMoveBegin = durationOr(animMoveBegin);
        this.durMoveEnd = durationOr(animMoveEnd);
        this.durAttack = durationOr(animAttack);
        this.durDie = Math.max(0.01f, durationOr(animDie));
        this.modelHeight = Math.max(1f, data.bodyHeight());

        if (animIdle != null) {
            pose.play(animIdle, true);
        } else if (animMove != null) {
            pose.play(animMove, true);
        }
    }

    /** @param anim 动画名（可为 null） @return 秒（没有该动画返回 0） */
    private float durationOr(String anim) {
        return anim == null ? 0f : Math.max(0f, data.duration(anim));
    }

    /** @return 敌人 id */
    public String enemyId() {
        return enemyId;
    }

    /** @return 当前状态 */
    public State state() {
        return state;
    }

    /** @return 是否已经播完死亡动画、可以移出列表 */
    public boolean expired() {
        return expired;
    }

    /** @return 是否朝左 */
    public boolean faceLeft() {
        return faceLeft;
    }

    /** @return 最后一帧的世界 x（格）—— 尸体续播时用它定位 */
    public float worldX() {
        return lastX;
    }

    /** @return 最后一帧的世界 y（格）—— 尸体续播时用它定位 */
    public float worldY() {
        return lastY;
    }

    /** @return 模型原始高度（模型单位），供缩放计算与测试用 */
    public float modelHeight() {
        return modelHeight;
    }

    /** @return 解析出的攻击动画名（可能 {@code null}，如幽灵） */
    public String attackAnimation() {
        return animAttack;
    }

    /** @return 解析出的移动动画名（可能 {@code null}） */
    public String moveAnimation() {
        return animMove;
    }

    /** @return 解析出的待机动画名（可能 {@code null}） */
    public String idleAnimation() {
        return animIdle;
    }

    /** @return 解析出的阵亡动画名（可能 {@code null}） */
    public String dieAnimation() {
        return animDie;
    }

    /** @return 当前实际在播的动画名（由 {@link SpinePose#current()} 给出） */
    public String currentAnimation() {
        return pose.current();
    }

    /**
     * 同步世界位置并据此判断是否在移动。
     *
     * @param x  世界 x（格）
     * @param y  世界 y（格）
     * @param dt 本帧间隔（秒）
     */
    public void setPosition(float x, float y, float dt) {
        if (hasLast && dt > 1e-5f) {
            float dx = x - lastX;
            float dy = y - lastY;
            float speed = (float) Math.sqrt(dx * dx + dy * dy) / dt;
            moving = speed > MOVE_EPS;
            if (moving && Math.abs(dx) > 1e-3f) {
                faceLeft = dx < 0f;             // 按水平运动方向翻面
            }
        }
        if (moving) {
            stillTime = 0f;
        } else if (dt > 0f) {
            stillTime += dt;
        }
        lastX = x;
        lastY = y;
        hasLast = true;
    }

    /** @return 已经连续站住多久（秒） */
    public float stillTime() {
        return stillTime;
    }

    /**
     * 推进一帧。
     *
     * @param dt             本帧间隔（秒）
     * @param engaged        是否在交手（被阻挡 / 站定开火）
     * @param attackInterval 该敌人类型的攻击间隔（秒，取自 {@code EnemyType}）
     * @param attackAge      距上次出手过了多久（秒，{@code Enemy.attackAge()}）——
     *                       用它而不是自己数拍子，动画的"打到人"那一帧才和引擎的扣血时刻严丝合缝
     * @param alive          是否还活着
     */
    public void update(float dt, boolean engaged, float attackInterval, float attackAge,
                       boolean alive) {
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
            if (dieTime > durDie + DEATH_HOLD + FADE_OUT) {
                expired = true;
            }
            return;
        }
        if (state == State.DIE) {
            return;
        }

        if (engaged && animAttack != null) {
            // 交手：相位直接用引擎的 attackAge，动画播到"打到人"那一帧 = 引擎扣血的那一 tick；
            // 动画比攻击间隔短时停在最后一帧（方舟的观感就是这样）
            if (state != State.ATTACK) {
                state = State.ATTACK;
                pose.play(animAttack, false);
            }
            pose.setTime(Math.min(durAttack, Math.max(0f, attackAge)));
            return;
        }

        if (moving || stillTime < STOP_GRACE) {
            // 迟滞：位置刚停下来的那几帧仍然算"在走"，避免 1/30 逻辑 tick 与
            // 60/120fps 渲染不同步导致状态机抖动（详见 STOP_GRACE 的说明）。
            if (state != State.MOVE) {
                state = State.MOVE;
                String first = animMoveBegin != null ? animMoveBegin : animMove;
                if (first != null && !first.equals(pose.current())) {
                    pose.play(first, animMoveBegin == null);   // 有过渡就先播一次
                    pose.setTime(0f);
                }
            } else if (animMoveBegin != null && animMove != null
                    && animMoveBegin.equals(pose.current()) && pose.time() >= durMoveBegin) {
                pose.play(animMove, true);                      // 起步播完切循环
            }
            pose.update(dt);
            return;
        }

        if (state != State.IDLE) {
            state = State.IDLE;
            if (animMoveEnd != null) {
                pose.play(animMoveEnd, false);
            } else if (animIdle != null) {
                pose.play(animIdle, true);
            }
            pose.setTime(0f);
        } else if (animMoveEnd != null && animMoveEnd.equals(pose.current())
                && pose.time() >= durMoveEnd && animIdle != null) {
            pose.play(animIdle, true);
        }
        pose.update(dt);
    }

    /**
     * 画到屏幕坐标。
     *
     * @param g    画布
     * @param sx   脚底屏幕 x
     * @param sy   脚底屏幕 y
     * @param px   该处每格多少像素（{@code Camera.project} 的 p[2]）
     * @param tint 受击染色强度 0–1
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
        float flip = faceLeft ? -1f : 1f;
        SpineMeshPainter.draw(g, texture, mesh, sx, sy, scale, flip,
                Math.min(HIT_TINT_MAX, tint), alpha(),
                HIT_TINT_R, HIT_TINT_G, HIT_TINT_B);
    }

    /**
     * @return 当前整体不透明度：死亡动画期间 1.0，{@link #DEATH_HOLD} 定格后线性淡出到 0
     */
    public float alpha() {
        if (state != State.DIE) {
            return 1f;
        }
        float over = dieTime - durDie - DEATH_HOLD;
        if (over <= 0f) {
            return 1f;
        }
        float a = 1f - over / FADE_OUT;
        return a < 0f ? 0f : a;
    }

    /** @return 进入死亡状态后过了多少秒（画"倒地尘土"用；活着返回 0） */
    public float dieElapsed() {
        return state == State.DIE ? dieTime : 0f;
    }

    /**
     * 画一具**尸体**：影子淡出 + 倒地尘土 + 本体（按"播完 → 定格 → 淡出"给 alpha）。
     *
     * <p>为什么把这段放进动画器：局内（{@code BattleScreen}）和诊断工具
     * （{@code EnemyDeathSheet}）必须画得一模一样，否则"工具里好看、进游戏不对"。
     *
     * @param canvas 画布（{@code BattleScreen} 传 {@code g.getGraphics()}）
     * @param sx     脚底屏幕 x
     * @param sy     脚底屏幕 y
     * @param px     该处每格多少像素
     */
    public void drawCorpse(PGraphics canvas, float sx, float sy, float px) {
        if (texture == null) {
            return;
        }
        float a = alpha();
        if (a <= 0.01f) {
            return;
        }
        float size = Math.max(6f, px * CORPSE_SHADOW_RADIUS * 2f);

        // ① 影子：跟着一起淡出，否则会出现"尸体没了影子还在"
        canvas.noStroke();
        canvas.fill(0, 0, 0, 70f * a);
        canvas.ellipse(sx, sy, size * 1.1f, size * 0.5f);

        // ② 倒地尘土：前 0.45 秒放一圈迅速扩散、越来越淡的环
        float dust = dieElapsed();
        if (dust < DUST_SECONDS) {
            float k = dust / DUST_SECONDS;
            float r = size * (0.6f + 0.9f * k);
            canvas.noFill();
            canvas.stroke(200, 200, 190, 90f * (1f - k));
            canvas.strokeWeight(Math.max(1f, px * 0.03f));
            canvas.ellipse(sx, sy - size * 0.1f, r * 1.4f, r * 0.7f);
            canvas.noStroke();
        }

        // ③ 本体
        draw(canvas, sx, sy, px, 0f);
    }

    /** @return 死亡动画总时长（秒），供调试/测试用 */
    public float dieDuration() {
        return durDie;
    }
}
