package com.yestercar.runtime;

import com.yestercar.campaigneditor.io.MapJsonIO;
import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.common.io.CampaignLoader;
import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.Campaign;
import com.yestercar.common.util.UITheme;
import com.yestercar.runtime.battle.BattleScene;
import com.yestercar.runtime.battle.Enemy;
import com.yestercar.runtime.battle.EnemyType;
import com.yestercar.runtime.view.BattleGridRenderer;
import com.yestercar.runtime.view.Camera;
import com.yestercar.common.util.Director;
import java.util.List;
import processing.core.PApplet;
import processing.core.PConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 战斗运行时窗口。
 *
 * <h2>P2.3 起它真的会跑了</h2>
 * 不再是占位窗口：加载地图 → 建流场 → 出怪 → 敌人沿流场走向蓝门 → 到达扣血。
 * 这是里程碑「能让敌人从红门走到蓝门」的**可见部分**。
 * 纯逻辑在 {@link BattleScene}（可脱离窗口单测），本类只负责把状态画出来。
 *
 * <h2>绘制顺序</h2>
 * <ol>
 *   <li>地形（{@link BattleGridRenderer}，透视 3D）</li>
 *   <li>门的高亮（红门/蓝门在地形上再加一层色）</li>
 *   <li>敌人（同样走透视投影，所以近大远小）</li>
 *   <li>HUD（屏幕坐标，不受相机影响）</li>
 * </ol>
 *
 * <h2>待办</h2>
 * <ul>
 *   <li><b>波次</b>：现在按固定间隔出怪，不是读 {@code level_xxx.json} 的波次表（P4.1）</li>
 *   <li><b>障碍/干员/费用</b>：P2.4 与 P4</li>
 *   <li><b>动画</b>：敌人目前是色块，接 GIF / 骨骼是乙的活（P4.6 / P4.7）</li>
 *   <li><b>中间少两个界面</b>：自定义战役界面 → 编队界面</li>
 * </ul>
 */
public final class BattleScreen {

    /** 宿主画布（主窗口）。所有绘制都画在它上面。 */
    private final PApplet g;

    /** 宿主时钟（毫秒）。原来是 PApplet.millis()。 */
    private int nowMs;

    /** 鼠标位置（宿主每帧同步进来）。 */
    private float mouseX;
    private float mouseY;

    /** 战斗背景图路径。 */
    private static final String FIGHT_BG = "data/imgs/fighting/fightbackground.png";

    /** 战斗背景（懒加载）。 */
    private processing.core.PImage fightBg;

    /** 是否已尝试加载战斗背景。 */
    private boolean fightBgTried;

    /** 敌人图标缓存（key = 路径）。 */
    private final java.util.Map<String, processing.core.PImage> enemyIcons = new java.util.HashMap<>();

    /** 演示状态是否已经摆好。 */
    private boolean demoApplied;


    /** 该回主菜单了（宿主每帧检查）。 */
    private boolean returnRequested;

    /** 上一次按下的字符（供 isKey 用）。 */
    private char lastKey;

    /**
     * 建一场战斗并立即加载。
     *
     * @param g            宿主画布（主窗口）
     * @param campaignName 战役目录名
     * @param squadIds     编队（干员 id）
     */
    public BattleScreen(PApplet g, String campaignName, List<String> squadIds) {
        this.g = g;
        setPendingCampaign(campaignName);
        setPendingSquad(squadIds);
        start();
    }

    /** @return 是否请求回主菜单 */
    /** @return 结算卡片是否已出现 */
    public boolean resultVisible() {
        return director != null && director.finished();
    }

    /** @return 结算卡片是否已滑出完成 */
    public boolean resultDone() {
        return resultDone;
    }

    public boolean returnRequested() {
        return returnRequested;
    }

    /**
     * 每帧调用：推进时间并绘制。
     *
     * @param nowMs 宿主时钟（毫秒）
     */
    public void render(int nowMs, float mx, float my) {
        this.nowMs = nowMs;
        this.mouseX = mx;
        this.mouseY = my;
        renderFrame();
    }

    /** 视口尺寸。 */
    private static final int WINDOW_W = 1280;
    private static final int WINDOW_H = 720;

    /** 演示出怪间隔（秒）。 */
    private static final float SPAWN_INTERVAL = 2.0f;

    /** 敌人色块的半径（格）。 */
    private static final float ENEMY_RADIUS = 0.26f;

    /**
     * 待加载的战役名，由 {@code GameMain} 在 {@code --play} 模式下设置。
     *
     * <p>用静态字段传值，是因为 {@code PApplet.main()} 只能接受 Class、
     * 无法传构造参数，而游戏与编辑器又分属不同进程、没法用对象传递。
     * 这个字段只承载"启动意图"，不承担运行时状态。
     */
    private static String pendingCampaign;

    /**
     * 待开的编队（干员 id 列表）。
     *
     * <p>与 {@link #pendingCampaign} 同一套机制：主菜单选定后塞进来，
     * 战斗窗口在 {@code setup()} 里取走。为 {@code null} 表示"没编队"，
     * 此时战斗按空编队跑（只观察敌人）。
     */
    private static java.util.List<String> pendingSquad;

    // ---- 状态 ----
    private TileMap map;

    /**
     * 当前加载的战役（含波次与规则参数）。
     *
     * <p>留着它是因为 P4.1 要按波次出怪、按规则参数算费用 ——
     * 那些数据都在战役里，不必再从磁盘读一遍。
     */
    private Campaign campaign;

    private BattleScene scene;
    private Camera camera;
    private BattleGridRenderer renderer;
    private Map<String, EnemyType> enemyTypes;

    /** 出怪计时。 */
    private float spawnTimer;

    /** 出怪调度（第 21 轮：读关卡 waves，替掉写死的每 2 秒一只）。 */
    private com.yestercar.runtime.battle.SpawnManager spawnManager;
    /** 上一帧的时间戳（毫秒），用于算 dt。 */
    private int lastMillis = -1;
    /** 加载失败时的原因，非空时屏幕上显示它。 */
    private String loadError;

    // ---- 阶段 6：费用 / 部署 / 阻挡 / 攻击 / 胜负 ----

    /** 编队（按部署栏顺序）。 */
    private java.util.List<com.yestercar.common.model.Operator> squad = java.util.List.of();

    /**
     * 战斗引擎门面。
     *
     * <p>五个系统本身是包级私有的（引擎内部件），UI 只跟这个门面说话。
     */
    private com.yestercar.runtime.battle.BattleDirector director;

    /** 部署栏当前选中项（-1 = 没选）。 */
    /** 拖拽/朝向选择阶段（照 Defender 的两段式部署）。 */
    private enum DeployPhase {
        /** 没在部署。 */
        IDLE,
        /** 已从待部署区按下，幽灵跟着鼠标，等松手落在格子上。 */
        DRAGGING,
        /** 已落在格子上，正在用鼠标位置选朝向，点击确认。 */
        FACING
    }

    /** 当前部署阶段。 */
    private DeployPhase deployPhase = DeployPhase.IDLE;

    /** 正在拖的干员在编队里的下标（{@code -1} = 没在拖）。 */
    private int dragSlot = -1;

    /** FACING 阶段：候选格列。 */
    private int pendingCol = -1;

    /** FACING 阶段：候选格行。 */
    private int pendingRow = -1;

    /** FACING 阶段：当前选中的朝向。 */
    private Director pendingFacing = Director.RIGHT;

    /** FACING 阶段：鼠标是否已经移动过（未移动则不显示范围，避免默认朝右）。 */
    private boolean facingMoved;

    /** FACING 阶段：是否已经按下左键开始"拖方向"。 */
    private boolean aiming;

    /** 拖方向的起点（屏幕坐标，= 格子中心）。 */
    private float aimAnchorX;
    private float aimAnchorY;

    /** 拖动小于这个像素数不改变朝向（防抖）。 */
    private static final float AIM_DEADZONE = 16f;

    /** 信息面板：选中的干员座位（{@code null} = 没开）。 */
    private int[] panelCell;

    /** 上一次确认过的朝向（下次展开面板时默认选它）。 */
    private Director lastFacing = Director.UP;

    /** 选中的敌人（点敌人弹出的详情面板用）；{@code null} = 没选。 */
    private Enemy panelEnemy;

    /** 撤退按钮命中区（屏幕坐标）。 */
    private final float[] retreatBtn = new float[4];

    private int selectedSlot = -1;

    /** 下一次部署使用的朝向（方向键切换，HUD 会显示）。 */
    private com.yestercar.common.util.Director deployFacing = com.yestercar.common.util.Director.RIGHT;

    /** 诊断用的自动部署：上次尝试的时刻（每 2 秒重试一次，等费用）。 */
    private int lastAutoDeployMs;

    /** 结算是否已经打过日志。 */
    private boolean finishLogged;

    /** 干员诊断是否已打印。 */
    private boolean unitsLogged;

    /** 相机诊断是否已打印。 */
    private boolean cameraLogged;

    /** 贴图诊断是否已打印。 */
    private boolean texLogged;

    /** 诊断截图是否已完成。 */
    private boolean battleShotDone;

    /** 诊断序列截图：已存到第几帧（{@code -1} = 还没开始）。 */
    private int seqShotIndex = -1;

    /** 诊断序列截图：下一帧的时刻（毫秒）。 */
    private int seqShotNextMs;

    /** 截图时刻（毫秒）。 */
    private int shotAtMs = 4000;

    /** 是否已经点过"返回主菜单"（防止重复触发）。 */
    private boolean exited;

    /** 部署栏高度（加高以放 1:2 缩略图，第 17 轮决定）。 */
    private static float BAR_H = 210f;

    /** 击杀计数器图标（懒加载）。 */
    private processing.core.PImage killedIcon;

    /** 当前窗口缩放系数（1.0 = 1000px 高）。 */
    private float uiScale = 1f;

    /** 探针：相机尺寸是否已打印。 */
    private boolean camLogged;
    private int camLoggedW = -1;
    private int camLoggedH = -1;

    /** 部署卡尺寸（1:2 缩略图 + 一行费用）。 */
    private static float CARD_W = 86f;
    private static float CARD_H = 158f;
    private static final float CARD_GAP = 6f;

    /** 缩略图尺寸（1:2，与编队界面的立绘同比例）。 */
    private static final float THUMB_W = 28f;
    private static final float THUMB_H = 56f;

    /** 占位动画：死亡烟尘（x, y, 已存活秒数）。 */
    private final java.util.List<float[]> puffs = new java.util.ArrayList<>();

    /**
     * 干员**普攻特效**（远程弹体 + 近战挥砍），参数读 {@code data/attack-fx.csv}。
     *
     * <p>时序：引擎在出手瞬间把 {@code attackAge}（距上次出手的时间）归零，我们把它当
     * "攻击动画进度"用 —— 先记下"刚出手"（上弦），等它涨到 {@code release}
     * （表里那一列：近战是"挥砍打到"、远程是"离手"，都实测自 Spine 的攻击动画）时才出特效：
     * <ul>
     *   <li>近战（{@code kind=none}）：在目标身上扫两道月牙（Defender {@code _slash}），
     *       **同一 tick** 引擎按这个时刻结算伤害；</li>
     *   <li>远程（{@code arrow/bolt/orb}）：射出弹体（Defender {@code PROJ}），飞到目标处炸开，
     *       引擎在弹体到达那一 tick 结算伤害。</li>
     * </ul>
     *
     * <p>目标点：射程内离干员最近的敌人（引擎的 TargetSelector 也是"最近的优先"，
     * 所以几乎总是同一只）；范围内没有敌人时朝面向方向打一发短的，避免"抬手没特效"。
     *
     * @param dt 本帧真实间隔（秒）
     */
    private void drawAttackFx(float dt) {
        if (director == null || scene == null) {
            return;
        }
        if (attackFx == null) {
            attackFx = new com.yestercar.runtime.view.AttackFx();
        }
        // 敌方远程攻击：引擎在"弹体离手"那一 tick 把起止点交出来，这里照同一条弹道放出去
        for (float[] shot : director.drainEnemyAttackFx()) {
            attackFx.spawnShot("enemy", shot[0], shot[1], shot[2], shot[3]);
            if ("1".equals(System.getProperty("yestercar.fx.debug"))) {
                System.out.printf("[fx] enemyShot from (%.2f,%.2f) to (%.2f,%.2f)%n",
                        shot[0], shot[1], shot[2], shot[3]);
            }
        }
        java.util.List<com.yestercar.runtime.battle.BattleDirector.UnitView> views =
                director.unitViews();

        java.util.Set<String> alive = new java.util.HashSet<>();
        for (com.yestercar.runtime.battle.BattleDirector.UnitView u : views) {
            String cell = u.col() + "," + u.row();
            alive.add(cell);
            float now = u.attackAge();
            float[] st = fxState.get(cell);
            if (st == null) {
                st = new float[] {now, 0f};
                fxState.put(cell, st);
                continue;
            }
            com.yestercar.common.model.Operator op = fxOperator(u.name());
            com.yestercar.common.model.AttackFxTable.Spec spec =
                    op == null ? null : com.yestercar.common.model.AttackFxTable.get(op.id());
            // 医疗是治疗不是攻击（引擎走 heal 分支），不给它们画弹道
            if (spec != null && "orb".equals(spec.kind())) {
                spec = null;
            }
            float release = spec == null ? -1f : spec.release();
            // ① 出手：attackAge 从"接近间隔"跳到 0 附近 → 上弦，并在这一刻锁定目标
            if (now < st[0] - 0.08f) {
                st[1] = 1f;
                fxTarget.put(cell, nearestInFxRange(u));
            }
            // ② 动画播到"打到/离手"那一帧 → 出特效，并下弦（这次出手只发一次）
            boolean fire = release >= 0f && st[1] > 0f && now >= release && now > st[0] - 0.5f;
            st[0] = now;
            if (!fire) {
                continue;
            }
            st[1] = 0f;
            com.yestercar.runtime.battle.Enemy aim = fxTarget.remove(cell);

            float sx = u.col() + 0.5f;
            float sy = u.row() + 0.5f;
            float tx = aim != null ? aim.x() : sx + u.facing().dx() * 0.8f;
            float ty = aim != null ? aim.y() : sy + u.facing().dy() * 0.8f;
            if (!spec.melee()) {
                // 远程：弹体从干员飞向目标（目标已死也照样打过去，观感才对）
                attackFx.spawnShot(spec.kind(), sx, sy, tx, ty);
                if ("1".equals(System.getProperty("yestercar.fx.debug"))) {
                    System.out.printf("[fx] %s from %s @(%d,%d) attackAge=%.3f target=%s%n",
                            spec.kind(), u.name(), u.col(), u.row(), now,
                            aim == null ? "(none)" : aim.type().id());
                }
            }
            // 近战：不画特效，只保留"挥砍打到那一帧才结算伤害"的时序
        }
        fxState.keySet().retainAll(alive);

        attackFx.update(dt);
        if (attackFx.isEmpty()) {
            return;                     // 没有弹体/粒子就别碰矩阵与 hint（P2D 里 hint 会刷状态）
        }
        g.pushMatrix();
        g.resetMatrix();
        g.hint(PConstants.DISABLE_DEPTH_TEST);
        attackFx.draw(g.getGraphics(), camera);
        g.hint(PConstants.ENABLE_DEPTH_TEST);
        g.popMatrix();
    }

    /**
     * 在干员的**攻击范围格子**里找最近的敌人（出手那一刻调用，用来锁定目标）。
     *
     * <p>按范围格子而不是半径：术师是 3-1 的扇形，按半径会把背后的敌人也算进来，
     * 法球就会往回飞。
     *
     * @param u 干员快照
     * @return 最近的敌人；范围内没有返回 {@code null}
     */
    private com.yestercar.runtime.battle.Enemy nearestInFxRange(
            com.yestercar.runtime.battle.BattleDirector.UnitView u) {
        if (scene == null) {
            return null;
        }
        java.util.Set<com.yestercar.common.model.CellOffset> cells =
                fxRangeCells(u.name(), u.facing());
        float sx = u.col() + 0.5f;
        float sy = u.row() + 0.5f;
        com.yestercar.runtime.battle.Enemy best = null;
        float bestD = Float.MAX_VALUE;
        for (com.yestercar.runtime.battle.Enemy e : scene.enemies()) {
            if (!e.alive()) {
                continue;
            }
            boolean inRange = cells.isEmpty()
                    ? true                                   // 查不到范围就退化到半径
                    : cells.contains(new com.yestercar.common.model.CellOffset(
                            (int) Math.floor(e.x()) - u.col(),
                            (int) Math.floor(e.y()) - u.row()));
            if (!inRange) {
                continue;
            }
            float dx = e.x() - sx;
            float dy = e.y() - sy;
            float d = dx * dx + dy * dy;
            if (d < bestD && d <= 4.5f * 4.5f) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * 显示名 → 干员定义（只建一次；特效选目标与查普攻参数都用它）。
     *
     * @param displayName 中文显示名（{@code UnitView.name()}）
     * @return 干员；查不到返回 {@code null}
     */
    private static com.yestercar.common.model.Operator fxOperator(String displayName) {
        if (fxOpByName == null) {
            java.util.Map<String, com.yestercar.common.model.Operator> m = new java.util.HashMap<>();
            for (com.yestercar.common.model.Operator o
                    : com.yestercar.common.model.Operator.loadAll().values()) {
                m.put(o.name(), o);
            }
            fxOpByName = m;
        }
        return fxOpByName.get(displayName);
    }

    /**
     * 取某个干员朝向 d 的攻击范围格子。
     *
     * @param displayName 中文显示名（{@code UnitView.name()}）
     * @param d           朝向
     * @return 格子偏移集合；查不到返回空集合（调用方退化成半径判定）
     */
    private static java.util.Set<com.yestercar.common.model.CellOffset> fxRangeCells(
            String displayName, com.yestercar.common.util.Director d) {
        com.yestercar.common.model.Operator op = fxOperator(displayName);
        return op == null ? java.util.Set.of() : op.rangeFor(d);
    }

    /** 显示名 → 干员（只建一次；只在特效选目标时用）。 */
    private static java.util.Map<String, com.yestercar.common.model.Operator> fxOpByName;

    /**
     * 干员骨骼动画层（Spine）。
     *
     * <p>惰性创建：第一次画干员时才建，避免没进战斗的路径也去读素材。
     * 素材缺失/解析失败时这一层不抛异常，{@link #drawOperators} 自动退回色块占位。
     */
    private com.yestercar.runtime.view.SpineUnitLayer spine;

    /** 普攻特效层（远程弹体 + 近战挥砍）；惰性创建，第一次画特效时才建。 */
    private com.yestercar.runtime.view.AttackFx attackFx;

    /**
     * 每个格子的特效状态：{@code [0] = 上一帧的 attackAge}，{@code [1] = 是否已"上弦"}。
     *
     * <p>"上弦"= 检测到了一次新的出手（attackAge 从大跳到 0），随后等它涨到
     * 该干员的 {@code release}（{@code data/attack-fx.csv}）才真正出特效 —— 这样弹体/月牙
     * 正好落在**动画打到（或离手）那一帧**，而不是在出手瞬间就飞出去。
     */
    private final java.util.Map<String, float[]> fxState = new java.util.HashMap<>();

    /**
     * 上弦时锁定的目标（格子 → 敌人）。
     *
     * <p>为什么要锁：出手到离手隔了 0.6 秒，敌人以 1.0–1.9 格/秒在走，
     * 到发射那一刻它往往已经走出射程 —— 那时再选目标就只能"朝空气打"。
     * 所以在**出手那一帧**就选定目标，发射时朝它当时所在的位置飞（目标已死就用它倒下的位置）。
     */
    private final java.util.Map<String, com.yestercar.runtime.battle.Enemy> fxTarget =
            new java.util.HashMap<>();

    /**
     * 敌人骨骼动画层（Spine）。
     *
     * <p>和干员层同样的惰性创建与容错：素材缺失/解析失败时这一层返回 {@code null} 动画器，
     * {@link #drawEnemy} 自动退回原来的色块占位。
     */
    private com.yestercar.runtime.view.SpineEnemyLayer enemySpine;

    /**
     * 本帧的事件快照（只 drain 一次，敌人层与干员层共用）。
     *
     * <p>事件是"一次取走就没了"的队列，而这一帧有两层要看它，
     * 所以在画敌人那一步统一 {@code drainEvents()} 存到这里。
     */
    private java.util.List<com.yestercar.runtime.battle.BattleEvent> frameEvents = java.util.List.of();

    /** 占位动画：本局累计消费的事件数（诊断用）。 */
    private int eventCount;

    /** 立绘缩略图缓存（key = 路径）。 */
    private final java.util.Map<String, processing.core.PImage> thumbs = new java.util.HashMap<>();

    /** 本局放出过的敌人总数（用于判定"波次放完了"）。 */
    private int spawned;

    /** 本局上限（波次表里的总数；没有波次表时用默认值）。 */
    private int spawnLimit = 12;

    /** 结算状态。 */
    private boolean finished;

    /** 是否暂停（右上角停止键）。 */
    private boolean paused;

    /** 结算动画：0=未开始 1=滑入 2=停留(可点击) 3=滑出。 */
    private int resultPhase;

    /** 结算动画起始时刻（毫秒）。 */
    private long resultAt;

    /** 结算卡片是否已滑出完成（交给上层做黑屏返回）。 */
    private boolean resultDone;

    /** 是否显示"退出作战？"确认框。 */
    private boolean askQuit;
    private boolean won;
    private int stars;

    /** 最近一次操作反馈（部署失败原因）。 */
    private String hint = "";

    /** 返回主菜单的回调（由 GameOpeningApp 设置）。 */
    private static Runnable onExitToMenu;
    /** 中文字体（与编辑器同一套取法，见 UITheme.resolvedFontFamily）。 */
    private processing.core.PFont font;

    /**
     * 设置启动时要加载的战役。
     *
     * <p>必须在 {@code PApplet.main(GameRuntimeApp.class)} 之前调用。
     *
     * @param campaignName 战役名（{@code data/campaigns/} 下的目录名）
     */
    public static void setPendingCampaign(String campaignName) {
        pendingCampaign = campaignName;
    }

    /**
     * @return 待加载的战役名；正常启动流程下为 {@code null}
     */
    /**
     * 设置待开编队。
     *
     * @param ids 干员 id 列表；{@code null} 或空表示空编队
     */
    public static void setPendingSquad(java.util.List<String> ids) {
        pendingSquad = ids == null ? null : java.util.List.copyOf(ids);
    }

    /**
     * @return 待开编队；可能为 {@code null}（表示没编队）
     */
    public static java.util.List<String> pendingSquad() {
        return pendingSquad;
    }

    public static String pendingCampaign() {
        return pendingCampaign;
    }

    private void start() {
        UITheme.apply(g);
        
        // 与编辑器同一套字体取法：不设的话所有中文都会画成方框
        font = g.createFont(UITheme.resolvedFontFamily(), UITheme.FONT_SIZE, true);

        enemyTypes = EnemyType.loadAll();
        System.out.println("[BattleScreen] 敌人类型: " + EnemyType.ids(enemyTypes));

        if (pendingCampaign == null) {
            loadError = "未指定战役（正常启动流程下应当先经过主菜单）";
            System.out.println("[BattleScreen] " + loadError);
            return;
        }
        loadCampaign(pendingCampaign);
    }

    /**
     * 加载战役里的地图并布置战场。
     *
     * <h2>⚠️ 地图位置**读声明**，不要自己拼路径</h2>
     * 这里曾经写死 {@code dir.resolve("maps").resolve("map_001.json")} ——
     * 于是战役结构一改（子目录从 {@code maps} 变成 {@code map}、
     * 文件名从固定的 {@code map_001.json} 变成跟随战役名），
     * 运行时就报"找不到地图文件"，而编辑器明明存好了。
     *
     * <p>正确的做法是走 {@link CampaignLoader} 读 {@code campaign.json}，
     * 用它的 {@code map} 字段解析 —— <strong>编辑器写什么，运行时就读什么</strong>，
     * 两边不会各有一套约定。
     *
     * <p>顺带把波次与规则参数也拿到了（P4.1 要用），不必再各自找一遍。
     *
     * @param campaignName 战役目录名
     */
    private void loadCampaign(String campaignName) {
        Campaign campaign = CampaignLoader.findById(campaignName);
        System.out.println("[BattleScreen] 找战役: " + campaignName
                + "  -> " + (campaign == null ? "没找到" : campaign.summary()));

        if (campaign == null) {
            loadError = "找不到战役「" + campaignName + "」";
            System.err.println("[BattleScreen] " + loadError);
            return;
        }
        for (String issue : campaign.issues()) {
            System.err.println("[BattleScreen] 战役数据问题: " + issue);
        }

        // ⚠️ 官方战役在 jar 内（{@code dir() == null}），必须走**资源通道**。
        //
        // 这里曾经直接 return 并报"只能加载磁盘上的自定义战役" —— 于是从主菜单
        // 点官方关卡进战斗永远是失败。官方与自定义的差别只在**读取来源**，
        // 解析用的是同一个 {@link MapJsonIO#parse}，不该分成两条路。
        try {
            if (campaign.dir() == null) {
                String resourcePath = campaign.mapResourcePath();
                System.out.println("[BattleScreen] 官方战役，读 jar 内地图: " + resourcePath);
                if (!Workspace.classpathResourceExists(resourcePath)) {
                    loadError = "jar 内找不到地图资源：" + resourcePath;
                    System.err.println("[BattleScreen] " + loadError);
                    return;
                }
                map = MapJsonIO.parse(
                        Workspace.readClasspathResourceAsString(resourcePath), campaignName);
            } else {
                Path mapFile = campaign.mapFile();
                System.out.println("[BattleScreen] 地图文件: " + mapFile
                        + "  存在=" + Files.isRegularFile(mapFile));
                if (mapFile == null || !Files.isRegularFile(mapFile)) {
                    loadError = "找不到地图文件：" + mapFile;
                    System.err.println("[BattleScreen] " + loadError);
                    return;
                }
                map = MapJsonIO.load(mapFile);
            }
        } catch (IOException e) {
            loadError = "地图读取失败：" + e.getMessage();
            System.err.println("[BattleScreen] " + loadError);
            return;
        }

        this.campaign = campaign;
        System.out.println("[BattleScreen] 已加载 " + map);

        scene = new BattleScene(map);
        camera = new Camera(g.width, g.height);
        camera.frameMap(map.cols(), map.rows());
        renderer = new BattleGridRenderer(g);
        int loaded = renderer.loadTextures();
        System.out.println("[BattleScreen] 地形贴图 " + loaded + " 张");

        // ---- 阶段 6：把编队、费用、部署接上 ----
        buildSquad();
        spawnManager = new com.yestercar.runtime.battle.SpawnManager(campaign.meta().waves());
        spawnLimit = Math.max(1, spawnManager.totalCount());
        System.out.println("[BattleScreen] 关卡参数: 生命=" + campaign.meta().params().life()
                + " 初始费用=" + campaign.meta().params().initialCost()
                + " 回费=" + campaign.meta().params().costPerSecond());
        scene.setLife(campaign.meta().params().life());
        director = new com.yestercar.runtime.battle.BattleDirector(
                scene, map, campaign.meta().params(), squad);
        System.out.println("[BattleScreen] 编队 " + squad.size() + " 人"
                + "，波次 " + spawnManager.waveCount()
                + "，敌人总数 " + spawnLimit);

        // 开局不放：第一只由 SpawnManager 按波次的 time 决定
        // （time=0 的波次会在第一帧就吐出来，不会空等）
        spawnManager.update(0.001f);
        for (com.yestercar.common.model.Wave.EnemySpawn sp : spawnManager.drainDue()) {
            spawnFromWave(sp);
        }
        spawned = spawnManager.spawnedCount();
    }

    /**
     * 按待开编队把干员类型查出来。
     *
     * <p>编队里出现不认识的 id 时用 {@code Operator.FALLBACK} —— 与敌人表同样的宽容策略：
     * 让这一局能跑起来，比因为一个拼错的 id 直接崩掉更利于排查。
     */
    private void buildSquad() {
        java.util.List<String> ids = pendingSquad();
        java.util.List<com.yestercar.common.model.Operator> out = new java.util.ArrayList<>();
        if (ids != null) {
            java.util.Map<String, com.yestercar.common.model.Operator> all =
                    com.yestercar.common.model.Operator.loadAll();
            for (String id : ids) {
                out.add(com.yestercar.common.model.Operator.resolve(all, id));
            }
        }
        // 部署栏顺序：**费用升序、同费按 id**（第 17 轮决定，与编队界面的列表同一套规则）。
        // 与"编队时点选的先后"无关。
        out.sort(java.util.Comparator
                .comparingInt(com.yestercar.common.model.Operator::cost)
                .thenComparing(com.yestercar.common.model.Operator::id));
        squad = java.util.List.copyOf(out);
    }

    /**
     * 按波次数据放一只敌人。
     *
     * @param sp 出怪组（含敌人 id、红门下标、目标蓝门下标）
     */
    private void spawnFromWave(com.yestercar.common.model.Wave.EnemySpawn sp) {
        if (scene == null) {
            return;
        }
        java.util.List<Door> reds = map.redDoors();
        java.util.List<Door> blues = map.blueDoors();
        if (reds.isEmpty() || blues.isEmpty()) {
            System.out.println("[BattleScreen] 地图上没有门，无法出怪");
            return;
        }
        // 门下标越界时夹到范围内（数据写错不该让整关卡死）
        int ri = Math.max(0, Math.min(reds.size() - 1, sp.door()));
        int bi = Math.max(0, Math.min(blues.size() - 1, sp.targetBlueDoor()));
        EnemyType type = EnemyType.resolve(enemyTypes, sp.enemy());
        Enemy e = scene.spawn(type, reds.get(ri).id(), blues.get(bi).id());
        if (e != null) {
            System.out.println("[BattleScreen] 出怪 "
                    + (spawnManager == null ? spawned : spawnManager.spawnedCount())
                    + "/" + spawnLimit
                    + " （" + type.name() + " ← 红门" + ri + " → 蓝门" + bi + "）");
        }
    }

    private void renderFrame() {
        UITheme.background(g);
        if (!fightBgTried) {
            fightBgTried = true;
            fightBg = g.loadImage(FIGHT_BG);
            if (fightBg == null) {
                System.out.println("[BattleScreen] 战斗背景读取失败: " + FIGHT_BG);
            }
        }
        if (fightBg != null) {
            g.imageMode(PConstants.CORNER);
            float sc = Math.max(g.width / (float) fightBg.width,
                    g.height / (float) fightBg.height);
            float bw = fightBg.width * sc;
            float bh = fightBg.height * sc;
            g.image(fightBg, (g.width - bw) / 2f, (g.height - bh) / 2f, bw, bh);
        }

        if (loadError != null) {
            drawCenteredError();
            return;
        }
        if (scene == null || camera == null) {
            return;
        }

        // 诊断演示状态：必须在 draw 之前摆好，否则截图里看不到
        applyDemoState();

        // ---- 推进逻辑 ----
        float dt = computeDeltaSeconds();
        if (director != null) {
            director.setSpawnProgress(spawned, spawnLimit);
        }
        // ⚠️ 暂停时必须连**出怪队列**一起冻结：
        //    之前只挡了 director.update，spawnManager 仍按 dt 推进，
        //    导致暂停期间积压的敌人一次性吐出、互相重叠。
        if (spawnManager != null && !paused) {
            spawnManager.update(dt);
            for (com.yestercar.common.model.Wave.EnemySpawn sp : spawnManager.drainDue()) {
                spawnFromWave(sp);
            }
            spawned = spawnManager.spawnedCount();
            if (director != null) {
                director.setAllSpawned(spawnManager.allSpawned());
            }
        }
        if (director != null) {
            // ⚠️ 暂停必须挡住**整条**推进入口。
            //    原来写成 `if (director != null && !paused) ... else scene.update(dt)`，
            //    暂停时反而落进 else 走了 scene.update —— 敌人照常移动。
            if (!paused) {
                director.update(dt);
                director.setSpawnProgress(spawned, spawnLimit);
            }
        } else {
            scene.update(dt);
        }

        if (!texLogged && nowMs > 3000 && renderer != null) {
            texLogged = true;
            System.out.println("[诊断] 贴图摘要: " + renderer.textureSummary()
                    + "  地图尺寸 " + (map == null ? "null" : map.cols() + "x" + map.rows()));
        }

        // ⚠️ 必须复位绘制状态：现在战斗和主菜单**共用同一块画布**，
        //    菜单里用过的 tint()/stroke()/fill() 会留到这一帧。
        //    tint 只作用于 image()，一旦残留，贴图会被整片染色/透明掉，
        //    而纯色填充照常显示 —— 表现就是"地形全变纯色块"。
        g.noTint();
        g.noStroke();
        g.strokeWeight(1f);
        g.fill(255);
        g.imageMode(PConstants.CORNER);

        // ---- 画地形 ----
        if (!unitsLogged && nowMs > 6000 && director != null) {
            unitsLogged = true;
            System.out.println("[诊断] 场上干员 " + director.unitViews().size() + " 个:");
            for (com.yestercar.runtime.battle.BattleDirector.UnitView u : director.unitViews()) {
                float[] q = new float[4];
                camera.project(u.col() + 0.5f, u.row() + 0.5f, riseAt(u.col(), u.row()), q);
                System.out.printf("    %s @(%d,%d) 屏幕(%.0f,%.0f) 每格%.1fpx hp=%.0f/%.0f%n",
                        u.name(), u.col(), u.row(), q[0], q[1], q[2], u.hp(), u.maxHp());
            }
        }
        if (!cameraLogged && nowMs > 2000) {
            cameraLogged = true;
            float[] a = new float[4];
            float[] b = new float[4];
            camera.project(0, 0, 0, a);
            camera.project(map.cols(), map.rows(), 0, b);
            System.out.printf("[相机] 窗口 %dx%d 地图 %dx%d  "
                            + "(0,0)→(%.1f,%.1f)  (cols,rows)→(%.1f,%.1f)  每格像素 %.2f%n",
                    g.width, g.height, map.cols(), map.rows(),
                    a[0], a[1], b[0], b[1], a[2]);
        }
        // ⚠️ 相机原来只在 setup() 里按当时的窗口建一次 ——
        //    缩放窗口后它还是旧尺寸，所以地图/组件都不跟着变。
        //    另外底部待部署区占 BAR_H，地图可用高度要扣掉它才会往上让。
        int viewW = g.width;
        // ⚠️ 暂不扣 BAR_H：扣掉后地图投影错位（右下角跑出画面），
        //    等把渲染与命中的视口统一后再启用。
        int viewH = g.height;
        // 待部署区与卡片随窗口高等比放大（否则全屏后地图变大、底栏还是原尺寸）
        uiScale = Math.max(0.9f, Math.min(2.2f, g.height / 1000f));
        BAR_H = 210f * uiScale;
        CARD_W = 86f * uiScale;
        CARD_H = 158f * uiScale;
        if (camera == null) {
            camera = new com.yestercar.runtime.view.Camera(viewW, viewH);
        } else if (camera.viewWidth() != viewW || camera.viewHeight() != viewH) {
            camera.resize(viewW, viewH);      // 只改视口，保住平移/缩放/俯仰
        }
        if (!camLogged || camLoggedW != camera.viewWidth() || camLoggedH != camera.viewHeight()) {
            camLogged = true;
            camLoggedW = camera.viewWidth();
            camLoggedH = camera.viewHeight();
            System.out.printf("[探针] 相机 视口=%dx%d 中心=(%.1f,%.1f) 画布=%dx%d BAR_H=%.0f%n",
                    camera.viewWidth(), camera.viewHeight(),
                    camera.centerX(), camera.centerY(), g.width, g.height, BAR_H);
        }
        // 地图整体上移半个待部署区，避免和底栏重叠
        camera.shiftUp(BAR_H * 0.5f);
        renderer.draw(map, camera);

        // ---- 门的高亮框：默认不画 ----
        // 门的位置与状态由贴图本身表达（红门/蓝门贴图就在地图上了），
        // 再叠一层框属于调试辅助，正式使用时应当看不到。
        // 按 F1 可以打开，用来核对"门到底在哪一格"。
        if (showDoorMarks) {
            drawDoorMarks();
        }

        // ---- 画敌人（透视投影 → 近大远小）----
        // 事件流**每帧只 drain 一次**：敌人层与干员层读同一份快照。
        // （以前只有 drawOperators 在 drain；现在敌人也要用 DIE 事件补尸体，
        //   所以挪到这里统一下发，drawOperators 直接用 frameEvents。）
        frameEvents = director == null
                ? java.util.List.of()
                : director.drainEvents();
        if (enemySpine == null && scene != null) {
            enemySpine = new com.yestercar.runtime.view.SpineEnemyLayer(g);
        }
        if (enemySpine != null) {
            enemySpine.update(dt, scene.enemies(), frameEvents);
            // 尸体先画：人已经倒下，被活人盖住才合理
            for (com.yestercar.runtime.view.EnemyAnimator corpse : enemySpine.corpses()) {
                drawEnemyCorpse(corpse);
            }
        }
        for (Enemy e : scene.enemies()) {
            drawEnemy(e);
        }
        pruneEnemyFx(scene.enemies());

        // ---- 画死亡烟尘（占位动画）----
        // ⚠️ 只能复用本帧的 dt，**绝不能第二次调 computeDeltaSeconds()** ——
        //    它会推进 lastMillis，于是下一帧只量到"从这一句到下一帧"的几毫秒，
        //    模拟时间整体慢约 30 倍（实测：墙钟 12.1s 只跑了 1.7s 模拟）。
        drawPuffs(dt);

        // ---- 画干员（阶段 6）----
        if (!"1".equals(System.getProperty("yestercar.ui.noopdraw"))) {
            drawOperators(dt);
            // 普攻特效：画在干员之后（弹体要盖在人身上），屏幕空间、关深度测试
            drawAttackFx(dt);
        }

        // 选朝向阶段：黄框覆盖攻击范围
        if (deployPhase == DeployPhase.FACING && pendingCol >= 0 && selectedSlot >= 0
                && squad != null && selectedSlot < squad.size()) {
            // 朝向跟着鼠标走：比较鼠标与格心的偏移，取主轴方向
            float[] ctr = new float[4];
            camera.project(pendingCol + 0.5f, pendingRow + 0.5f,
                    riseAt(pendingCol, pendingRow), ctr);
            float ddx = mouseX - ctr[0];
            float ddy = mouseY - ctr[1];
            if (Math.abs(ddx) > 6f || Math.abs(ddy) > 6f) {
                facingMoved = true;
                pendingFacing = Math.abs(ddx) >= Math.abs(ddy)
                        ? (ddx > 0 ? Director.RIGHT : Director.LEFT)
                        : (ddy > 0 ? Director.DOWN : Director.UP);
            }
            // 还没动过鼠标就不画范围（否则会默认显示"朝右"）
            if (facingMoved) {
                drawRangeHighlight(pendingCol, pendingRow, pendingFacing,
                        squad.get(selectedSlot));
            }
        }
        // 点击已部署干员：同样显示范围
        if (panelCell != null && director != null) {
            for (com.yestercar.runtime.battle.BattleDirector.UnitView v : director.unitViews()) {
                if (v.col() == panelCell[0] && v.row() == panelCell[1]) {
                    // v.name() 是**中文显示名**，不是 id —— 按显示名查表
                    com.yestercar.common.model.Operator op = null;
                    for (com.yestercar.common.model.Operator cand
                            : com.yestercar.common.model.Operator.loadAll().values()) {
                        if (cand.name().equals(v.name())) {
                            op = cand;
                            break;
                        }
                    }
                    if (op != null) {
                        drawRangeHighlight(v.col(), v.row(), facingOf(v.facing()), op);
                    }
                    break;
                }
            }
        }

        // 叠加层画在 HUD **之前**：之前放在 HUD 之后，图形完全画不出来（原因未查明）
        drawDeployOverlay(mouseX, mouseY);
        drawInfoPanel();
        drawEnemyPanel();
        drawHud();
    }

    /**
     * 画干员：**骨骼动画** + 血条 + 阻挡数（素材缺失时退回色块）。
     *
     * <h2>两套分支为什么都要留</h2>
     * 策划 §1.3 的规矩是"贴图存在则用，不存在则色块"，骨骼素材同理：
     * 美术只交一部分干员时，缺的那些必须还能正常跑、正常玩 ——
     * 所以 {@link com.yestercar.runtime.view.SpineUnitLayer} 拿不到模型时返回 {@code null}，
     * 这里就退回原来的色块 + 朝向箭头。
     *
     * @param dt 本帧真实间隔（秒）。骨骼动画按真实时间推进，与 1/30 秒固定步长的
     *           战斗逻辑解耦 —— 这正是"动画只在表现层"的含义
     */
    private void drawOperators(float dt) {
        if (director == null) {
            return;
        }
        if (spine == null) {
            spine = new com.yestercar.runtime.view.SpineUnitLayer(g);
        }
        float[] p = new float[4];
        // ⚠️ 必须自己成对 push/pop：renderer 画完地形后矩阵状态不保证是单位阵，
        //    在屏幕坐标里直接画会被上一次的变换带偏（实测地图会整体下移）。
        g.pushMatrix();
        g.resetMatrix();
        // 关深度测试：高台上的干员会和高台自身的侧面抢深度，实测被完全挡住。
        // 原型阶段"单位始终可见"比"正确遮挡"更重要（也更好操作）。
        g.hint(PConstants.DISABLE_DEPTH_TEST);

        java.util.List<com.yestercar.runtime.battle.BattleDirector.UnitView> views =
                director.unitViews();
        // 骨骼层**只读**事件流。事件已在画敌人那一步 drain 过（敌人也要用 DIE 补尸体），
        // 这里直接用同一份快照 —— 事件队列"每帧只有一个消费者"的规矩不变。
        // 阵亡单位在引擎里当 tick 就被移出快照，靠 DIE 事件的世界坐标转成"尸体"续播死亡动画。
        spine.update(dt, views, frameEvents);

        // 骨骼绘制要走 PGraphics（PApplet 只是宿主，真正的画布是 g.getGraphics()）
        processing.core.PGraphics canvas = g.getGraphics();

        // 尸体先画：人已经倒下，被活人盖住才合理
        for (com.yestercar.runtime.view.OperatorAnimator corpse : spine.corpses()) {
            camera.project(corpse.col() + 0.5f, corpse.row() + 0.5f,
                    riseAt(corpse.col(), corpse.row()), p);
            corpse.draw(canvas, p[0], p[1], p[2], 0f);
        }

        for (com.yestercar.runtime.battle.BattleDirector.UnitView u : views) {
            // ⚠️ 必须按**地形抬升高度**投影：高台干员若按 z=0 画，
            //    会被高台自身的侧面挡在下面（实测：狙击完全看不见）。
            camera.project(u.col() + 0.5f, u.row() + 0.5f,
                    riseAt(u.col(), u.row()), p);
            // 脚底锚点：骨骼动画从这里往上长（未加"占位前冲"的原始位置）
            float footX = p[0];
            // 站到格子靠下的位置：格心 → 格底约半格，脚停在离格底 1/5 格处
            float footY = p[1] + 0.30f * p[2];
            float sx = footX;
            float sy = footY;
            // ⚠️ p[2] 是"该处每格多少像素"（实测约 66），不是缩放系数。
            //    这里踩过一次：写成 `26f * p[2]` 会把干员画成 26 格宽，
            //    那块蓝色大矩形直接盖住整张地图，看起来像"地图塌成一条"。
            //    尺寸以**格**为单位，再乘每格像素数。
            float size = 0.62f * p[2];

            // 占位动画①：出手后 0.15 秒内朝面向方向前冲一点（代替攻击动作）
            float lunge = 0f;
            if (u.attackAge() >= 0f && u.attackAge() < 0.15f) {
                float k = 1f - u.attackAge() / 0.15f;
                sx += u.facing().dx() * size * 0.22f * k;
                sy += u.facing().dy() * size * 0.22f * k;
                lunge = k;
            }

            com.yestercar.runtime.view.OperatorAnimator anim = spine.at(u.col(), u.row());
            if (anim != null) {
                // 骨骼动画：位置用未加"占位前冲"的脚底锚点，位移交给动画本身。
                // 受击**不换动画**，只染色 —— 方舟骨骼里根本没有 Hit 动画。
                anim.draw(canvas, footX, footY, p[2], u.hitFlash());
            } else {
                // 描边 + 底色：地面干员蓝、高台干员紫。
                // 高台地形贴图本身也是浅蓝，没描边会和地形糊在一起（实测）。
                g.stroke(0xFFFFFFFF);
                g.strokeWeight(2f);
                // 占位动画②：受击闪白（Defender 的 hit flash 是纯表现）
                int base = u.block() > 0 ? 0xFF2F6BDE : 0xFF8A5BE0;
                g.fill(u.hitFlash() > 0f ? 0xFFFFFFFF : base);
                g.rect(sx - size / 2f, sy - size, size, size, 4);
                if (u.hitFlash() <= 0f && lunge > 0f) {
                    // 出手瞬间描一圈亮边，替代"攻击动画"
                    g.stroke(0xFFE8C15A, 200f * lunge);
                    g.strokeWeight(2f);
                    g.noFill();
                    g.rect(sx - size / 2f - 2f, sy - size - 2f, size + 4f, size + 4f, 5);
                }
                g.noStroke();

                // 朝向箭头
                g.fill(0xFFFFFFFF);
                float a = size * 0.42f;
                float dx = u.facing().dx();
                float dy = u.facing().dy();
                g.triangle(sx + dx * a, sy - size / 2f + dy * a,
                        sx + dx * a - dy * a * 0.7f, sy - size / 2f + dy * a + dx * a * 0.7f,
                        sx + dx * a + dy * a * 0.7f, sy - size / 2f + dy * a - dx * a * 0.7f);
            }

            // 血条
            float w = size;
            float ratio = u.maxHp() <= 0 ? 0f : u.hp() / (float) u.maxHp();
            g.noStroke();
            g.fill(0, 0, 0, 170);
            g.rect(sx - w / 2f, sy + 3, w, 4);
            g.fill(ratio > 0.5f ? 0xFF62C46A : (ratio > 0.25f ? 0xFFE8C15A : 0xFFE05A5A));
            g.rect(sx - w / 2f, sy + 3, w * Math.max(0f, ratio), 4);

            // 阻挡数（挡住人时才显示）
            if (u.blocking() > 0) {
                g.fill(0xFFFFFFFF);
                g.textAlign(PConstants.CENTER, PConstants.BOTTOM);
                g.textSize(11);
                g.text(u.blocking() + "/" + u.block(), sx, sy - size - 2);
            }
        }
        g.popMatrix();
        g.hint(PConstants.ENABLE_DEPTH_TEST);
    }

    /**
     * 某格的地形抬升高度（格）。
     *
     * <p>与 {@code BattleGridRenderer} 用的是同一组常量，保证"干员站在地形表面上"。
     *
     * @param col 列
     * @param row 行
     * @return 抬升高度
     */
    private float riseAt(int col, int row) {
        com.yestercar.campaigneditor.model.TileType t = map.tileAt(col, row);
        if (t == null) {
            return 0f;
        }
        return switch (t) {
            case HIGH -> com.yestercar.runtime.view.BattleGridRenderer.HIGH_RISE;
            case GROUND -> com.yestercar.runtime.view.BattleGridRenderer.GROUND_RISE;
            case IMPASSABLE -> com.yestercar.runtime.view.BattleGridRenderer.IMPASSABLE_RISE;
            case RED_DOOR, BLUE_DOOR -> com.yestercar.runtime.view.BattleGridRenderer.DOOR_RISE;
            default -> 0f;
        };
    }

    /**
     * 消费本帧事件，驱动**零资源占位动画**。
     *
     * <h2>为什么要有这一层</h2>
     * 合作者负责真正的骨骼/攻击动画，但在他交付之前我们必须能自测 ——
     * 所以这里按 {@link com.yestercar.runtime.battle.BattleEvent} 的语义做最简表现：
     * 死亡留一团淡出的烟尘，其余动作靠 {@code UnitView} 的 {@code attackAge/hitFlash}
     * （那两项本来就是为表现层准备的只读字段）。
     *
     * <p>⚠️ 这一层**只读事件**，绝不回报任何东西给逻辑（Defender 的硬规则）。
     */
    private void consumeEvents() {
        if (director == null) {
            return;
        }
        java.util.List<com.yestercar.runtime.battle.BattleEvent> evs = director.drainEvents();
        if (evs.isEmpty()) {
            return;
        }
        eventCount += evs.size();
        for (com.yestercar.runtime.battle.BattleEvent e : evs) {
            if (e.kind() == com.yestercar.runtime.battle.BattleEvent.Kind.DIE) {
                puffs.add(new float[]{e.x(), e.y(), 0f});
            }
        }
    }

    /** 画死亡烟尘（0.5 秒扩散淡出）。 */
    private void drawPuffs(float dt) {
        if (puffs.isEmpty()) {
            return;
        }
        float[] p = new float[4];
        for (int i = puffs.size() - 1; i >= 0; i--) {
            float[] puff = puffs.get(i);
            puff[2] += dt;
            if (puff[2] > 0.5f) {
                puffs.remove(i);
                continue;
            }
            camera.project(puff[0], puff[1], 0f, p);
            float a = 1f - puff[2] / 0.5f;
            float r = (0.18f + puff[2] * 0.5f) * p[2];
            g.noStroke();
            g.fill(0xFFFFFFFF, 150f * a);
            g.ellipse(p[0], p[1] - r * 0.4f, r * 2f, r * 2f);
        }
    }

    /**
     * 调试开关：门的高亮框。
     *
     * <p>默认关闭 —— 需求是"正式使用时仅保留贴图"。
     * 但保留一个入口，因为"门的坐标对不对"是排查寻路问题时的第一个要看的。
     */
    private boolean showDoorMarks;

    public void keyPressed(char key, int keyCode) {
        lastKey = key;
        // M 切换门标注。
        // 用字母而不是 F1：Processing 的 PConstants **没有功能键常量**
        // （javap 查过 4.5.6 的 PConstants，只有 CODED/UP/DOWN 这类），
        // 而 `key` 对普通字符一定有值 —— 用字母是有保证的。
        if (isKey('m')) {
            showDoorMarks = !showDoorMarks;
            System.out.println("[BattleScreen] 门标注: " + (showDoorMarks ? "开" : "关"));
            return;
        }

        // Esc：返回主菜单（结算后任意键也可以）
        if (key == PConstants.ESC) {
            returnToMenu();
            return;
        }
        if (director != null && director.finished()) {
            // 结算卡片到正中心后才接受"返回"
            if (resultPhase == 2 && (key == PConstants.ENTER
                    || key == PConstants.RETURN || key == PConstants.ESC)) {
                resultPhase = 3;
                resultAt = nowMs;
            }
            return;
        }

        // 方向键切换**下一次部署**的朝向（策划 §2.8：部署时选择四向之一）
        if (key == PConstants.CODED) {
            switch (keyCode) {
                case PConstants.LEFT -> deployFacing = com.yestercar.common.util.Director.LEFT;
                case PConstants.RIGHT -> deployFacing = com.yestercar.common.util.Director.RIGHT;
                case PConstants.UP -> deployFacing = com.yestercar.common.util.Director.UP;
                case PConstants.DOWN -> deployFacing = com.yestercar.common.util.Director.DOWN;
                default -> {
                }
            }
            return;
        }

        // 数字键 1~9 直接选部署栏
        if (key >= '1' && key <= '9') {
            int idx = key - '1';
            if (squad != null && idx < squad.size()) {
                selectedSlot = idx;
            }
        }
    }

    public void mousePressed(float mouseX, float mouseY, int mouseButton) {
        if (director == null) {
            return;
        }
        // 退出确认框：继续 / 退出
        if (askQuit) {
            float bw = 180f * uiScale;
            float bh = 60f * uiScale;
            float cx = g.width / 2f;
            float cy = g.height / 2f;
            if (inside(mouseX, mouseY, cx - bw - 20f * uiScale, cy + 20f * uiScale, bw, bh)) {
                askQuit = false;                 // 继续作战
                paused = false;
            } else if (inside(mouseX, mouseY, cx + 20f * uiScale, cy + 20f * uiScale, bw, bh)) {
                askQuit = false;
                returnToMenu();                  // 退出
            }
            return;
        }
        // 右上角停止键
        if (inside(mouseX, mouseY, g.width - 64f * uiScale, 12f * uiScale,
                52f * uiScale, 52f * uiScale)) {
            paused = true;
            askQuit = true;
            return;
        }
        if (paused) {
            return;                              // 暂停时其余操作一律无效
        }
        // 结算后点任意位置返回
        if (director.finished()) {
            if (resultDone) {
                // 滑出屏幕 → 直接进黑屏返回，不用再点一次
                returnToMenu();
            } else if (resultPhase == 2) {
                resultPhase = 3;                 // 开始滑出
                resultAt = nowMs;
            }
            return;
        }

        // ① 右键：**不再撤退**，只用来取消当前操作（第 18 轮改）
        if (mouseButton == PConstants.RIGHT) {
            if (deployPhase != DeployPhase.IDLE) {
                deployPhase = DeployPhase.IDLE;
                dragSlot = -1;
                aiming = false;
                hint = "已取消";
            } else if (panelCell != null) {
                panelCell = null;
            } else if (panelEnemy != null) {
                panelEnemy = null;
            }
            return;
        }

        // ② 信息面板上的「撤退」
        if (panelCell != null) {
            if (hitRetreat(mouseX, mouseY)) {
                int back = director.retreatAt(panelCell[0], panelCell[1]);
                hint = back > 0 ? "撤退成功，返还 " + back + " 费" : "这一格已经没有干员";
                panelCell = null;
                return;
            }
            panelCell = null;      // 点面板外 = 关掉
            return;
        }

        // ③ FACING 阶段：按下左键 = 开始拖方向（松手才确认）
        if (deployPhase == DeployPhase.FACING) {
            float[] c = new float[4];
            camera.project(pendingCol + 0.5f, pendingRow + 0.5f,
                    riseAt(pendingCol, pendingRow), c);
            aimAnchorX = c[0];
            aimAnchorY = c[1];
            aiming = true;
            return;
        }

        // ④ 待部署区按下 → 开始拖
        int slot = deployBarHit(mouseX, mouseY);
        if (slot >= 0 && slot < squad.size()) {
            if (director.onField(squad.get(slot).id())) {
                hint = "「" + squad.get(slot).name() + "」已经在场上";
                return;
            }
            if (!director.readyToDeploy(squad.get(slot).id())) {
                hint = String.format("「%s」还在冷却（%.0f 秒）", squad.get(slot).name(),
                        Math.ceil(director.cooldownOf(squad.get(slot).id())));
                return;
            }
            if (director.costWhole() < squad.get(slot).cost()) {
                hint = "费用不足（需要 " + squad.get(slot).cost() + "）";
                return;
            }
            // 再点同一张卡 = 取消选中
            if (selectedSlot == slot && deployPhase == DeployPhase.IDLE && dragSlot < 0) {
                selectedSlot = -1;
                pendingCol = -1;
                pendingRow = -1;
                facingMoved = false;
                hint = "已取消选择";
                return;
            }
            deployPhase = DeployPhase.DRAGGING;
            dragSlot = slot;
            selectedSlot = slot;
            // 选中新卡时清掉上一张残留的候选格，否则会在地图上画出错位的范围
            pendingCol = -1;
            pendingRow = -1;
            facingMoved = false;
            hint = "拖到地面上松手";
            return;
        }

        // ⑤ 点敌人 → 开敌人详情面板（优先于干员，因为敌人是移动的、更难点中）
        Enemy hitEnemy = enemyAt(mouseX, mouseY);
        if (hitEnemy != null) {
            panelEnemy = hitEnemy;
            panelCell = null;
            return;
        }

        // ⑥ 点已部署的干员 → 开信息面板
        int[] cell = cellAt(mouseX, mouseY);
        if (cell != null && unitAt(cell[0], cell[1])) {
            panelCell = cell;
            panelEnemy = null;
            return;
        }

        hint = "从底部待部署区拖一名干员到地面";
    }

    /**
     * 松手：DRAGGING → 落在格子上则进入选朝向阶段。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @param mouseButton 按键
     */
    public void mouseReleased(float mouseX, float mouseY, int mouseButton) {
        if (director == null || director.finished()) {
            return;
        }
        if (mouseButton == PConstants.RIGHT) {
            return;
        }
        // FACING 阶段松手 = 确认部署
        if (deployPhase == DeployPhase.FACING && aiming) {
            aiming = false;
            lastFacing = pendingFacing;
            commitDeploy();
            return;
        }
        if (deployPhase != DeployPhase.DRAGGING || dragSlot < 0) {
            return;
        }
        int[] cell = cellAt(mouseX, mouseY);
        if (cell == null) {
            deployPhase = DeployPhase.IDLE;
            dragSlot = -1;
            hint = "松手的位置不在战场上";
            return;
        }
        String err = director.checkDeploy(squad.get(dragSlot), cell[0], cell[1]);
        if (err != null) {
            deployPhase = DeployPhase.IDLE;
            dragSlot = -1;
            hint = err;
            return;
        }
        pendingCol = cell[0];
        facingMoved = false;
        pendingRow = cell[1];
        // 展开朝向面板：默认沿用上一次确认的朝向，**不**用这次松手的位置去猜
        pendingFacing = lastFacing;
        aiming = false;
        deployPhase = DeployPhase.FACING;
        hint = "按住左键拖向要朝向的方向，松手确认（右键取消）";
    }

    /**
     * 拖动：FACING 阶段实时更新朝向。
     *
     * @param mouseX 鼠标 x
     * @param mouseY 鼠标 y
     * @param mouseButton 按键
     */
    public void mouseDragged(float mouseX, float mouseY, int mouseButton) {
        if (deployPhase != DeployPhase.FACING || !aiming) {
            return;
        }
        float dx = mouseX - aimAnchorX;
        float dy = aimAnchorY - mouseY;       // 屏幕 y 向下，世界 y 向上
        if (Math.hypot(dx, dy) < AIM_DEADZONE) {
            return;                            // 只动了一点点，不切方向
        }
        if (Math.abs(dx) >= Math.abs(dy)) {
            pendingFacing = dx > 0 ? Director.RIGHT : Director.LEFT;
        } else {
            pendingFacing = dy > 0 ? Director.UP : Director.DOWN;
        }
    }

    /**
     * 用鼠标相对格子中心的方向定四向朝向。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @param col 格列
     * @param row 格行
     * @return 朝向；鼠标正好在格中心时保持原值（默认朝右）
     */
    private Director facingFromDelta(float mx, float my, int col, int row) {
        float[] c = new float[4];
        float[] u = new float[4];
        camera.project(col + 0.5f, row + 0.5f, riseAt(col, row), c);
        camera.project(col + 0.5f, row - 0.5f, riseAt(col, row), u);
        float dx = mx - c[0];
        // 屏幕 y 越小越"上"，所以取反
        float dy = c[1] - my;
        if (Math.abs(dx) < 1e-3f && Math.abs(dy) < 1e-3f) {
            return pendingFacing;
        }
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx > 0 ? Director.RIGHT : Director.LEFT;
        }
        return dy > 0 ? Director.UP : Director.DOWN;
    }

    /** 提交部署。 */
    private void commitDeploy() {
        if (dragSlot < 0 || dragSlot >= squad.size()) {
            deployPhase = DeployPhase.IDLE;
            return;
        }
        com.yestercar.common.model.Operator op = squad.get(dragSlot);
        String err = director.deploy(op, pendingCol, pendingRow, pendingFacing);
        if (err == null) {
            hint = "已部署「" + op.name() + "」@(" + pendingCol + "," + pendingRow
                    + ") 朝向 " + facingName(pendingFacing);
        } else {
            hint = err;
        }
        deployPhase = DeployPhase.IDLE;
        dragSlot = -1;
        selectedSlot = -1;
    }

    /**
     * @param col 列
     * @param row 行
     * @return 该格上有没有干员
     */
    private boolean unitAt(int col, int row) {
        for (com.yestercar.runtime.battle.BattleDirector.UnitView u : director.unitViews()) {
            if (u.col() == col && u.row() == row) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找出鼠标点中的敌人。
     *
     * <p>按**屏幕投影**判断：敌人画在透视投影后的位置，用世界坐标做矩形判定会偏很多。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return 命中的敌人；没命中返回 {@code null}
     */
    private Enemy enemyAt(float mx, float my) {
        if (scene == null || camera == null) {
            return null;
        }
        float[] base = new float[4];
        float[] top = new float[4];
        Enemy best = null;
        float bestD = Float.MAX_VALUE;
        for (Enemy e : scene.enemies()) {
            if (!e.alive()) {
                continue;
            }
            camera.project(e.x(), e.y(), 0.02f, base);
            camera.project(e.x(), e.y(), 0.55f, top);
            float size = Math.max(14f, Math.min(120f, base[2] * ENEMY_RADIUS * 2f));
            float dx = mx - top[0];
            float dy = my - top[1];
            float d = (float) Math.hypot(dx, dy);
            if (d <= size * 0.75f && d < bestD) {
                best = e;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return 是否点在「撤退」按钮上
     */
    private boolean hitRetreat(float mx, float my) {
        return mx >= retreatBtn[0] && mx <= retreatBtn[0] + retreatBtn[2]
                && my >= retreatBtn[1] && my <= retreatBtn[1] + retreatBtn[3];
    }

    /**
     * 部署叠加层：拖拽幽灵 + 朝向选择箭头（照 Defender 的两段式）。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     */
    private void drawDeployOverlay(float mx, float my) {
        if (director == null) {
            return;
        }
        // 诊断兜底：直接把状态摆好（前面 applyDemoState 的路径在实机上没生效）
        if ("facing".equals(System.getProperty("yestercar.ui.demo", ""))
                && deployPhase == DeployPhase.IDLE && pendingCol >= 0) {
            deployPhase = DeployPhase.FACING;
            dragSlot = 0;
        }
        if (deployPhase == DeployPhase.IDLE) {
            return;                 // ⚠️ 必须在 push **之前**返回
        }
        // ⚠️ push/pop 必须严格配对。
        //    之前 DRAGGING 分支里有一个 return 在 push 之后，每帧漏一个 pop，
        //    拖动到第 32 帧 Processing 的矩阵栈就爆：
        //    java.lang.RuntimeException: Too many calls to pushMatrix()
        g.pushMatrix();
        g.resetMatrix();
        g.hint(PConstants.DISABLE_DEPTH_TEST);
        if (deployPhase == DeployPhase.DRAGGING) {
            int[] cell = cellAt(mx, my);
            if (cell != null) {
                boolean ok = director.checkDeploy(squad.get(dragSlot), cell[0], cell[1]) == null;
                highlightCell(cell[0], cell[1], ok ? 0xFF7BD88F : 0xFFE05A5A);
            }
            // 幽灵卡片跟着鼠标
            g.noStroke();
            g.fill(0xCC101418);
            g.rect(mx - 34f, my - 26f, 68f, 30f, 4f);
            g.fill(0xFFFFFFFF);
            g.textAlign(PConstants.CENTER, PConstants.CENTER);
            g.textSize(12f);
            g.text(squad.get(dragSlot).name(), mx, my - 11f);
        } else if (deployPhase == DeployPhase.FACING) {
            highlightCell(pendingCol, pendingRow, 0xFFE8C15A);
            float[] c = new float[4];
            camera.project(pendingCol + 0.5f, pendingRow + 0.5f,
                    riseAt(pendingCol, pendingRow), c);
            float r = Math.max(30f, c[2] * 0.6f);

            // 正在拖方向：画一条从格中心到鼠标的指示线
            if (aiming) {
                g.stroke(0xFFE8C15A, 200f);
                g.strokeWeight(2f);
                g.line(c[0], c[1], mx, my);
                g.noStroke();
            }

            // 四向面板：当前朝向金色放大，其余半透明
            Director[] all = {Director.UP, Director.RIGHT, Director.DOWN, Director.LEFT};
            for (Director d : all) {
                boolean sel = d == pendingFacing;
                float ax = c[0] + d.dx() * r;
                float ay = c[1] + d.dy() * r;
                g.noStroke();
                g.fill(sel ? 0xFFE8C15A : 0xB3101418);
                g.ellipse(ax, ay, sel ? 30f : 24f, sel ? 30f : 24f);
                if (sel) {
                    g.noFill();
                    g.stroke(0xFFFFFFFF, 220f);
                    g.strokeWeight(2f);
                    g.ellipse(ax, ay, 38f, 38f);
                    g.noStroke();
                }
                g.fill(sel ? 0xFF101418 : 0xFFFFFFFF);
                g.textAlign(PConstants.CENTER, PConstants.CENTER);
                g.textSize(13f);
                g.text(facingName(d), ax, ay);
            }

            g.fill(0xFFFFFFFF);
            g.textAlign(PConstants.CENTER, PConstants.TOP);
            g.textSize(12f);
            g.text(aiming ? "拖向要朝向的方向，松手确认"
                            : "按住左键拖向朝向，松手确认（右键取消）",
                    c[0], c[1] + r + 26f);
        }
        g.popMatrix();
    }

    /**
     * 高亮一个格子（屏幕空间四边形）。
     *
     * @param col  列
     * @param row  行
     * @param argb 颜色
     */
    private void highlightCell(int col, int row, int argb) {
        float h = riseAt(col, row);
        float[] p0 = new float[4];
        float[] p1 = new float[4];
        float[] p2 = new float[4];
        float[] p3 = new float[4];
        camera.project(col, row, h, p0);
        camera.project(col + 1, row, h, p1);
        camera.project(col + 1, row + 1, h, p2);
        camera.project(col, row + 1, h, p3);
        g.noStroke();
        g.fill(argb, 90f);
        g.beginShape();
        g.vertex(p0[0], p0[1]);
        g.vertex(p1[0], p1[1]);
        g.vertex(p2[0], p2[1]);
        g.vertex(p3[0], p3[1]);
        g.endShape(PConstants.CLOSE);
        g.noFill();
        g.stroke(argb, 230f);
        g.strokeWeight(2f);
        g.beginShape();
        g.vertex(p0[0], p0[1]);
        g.vertex(p1[0], p1[1]);
        g.vertex(p2[0], p2[1]);
        g.vertex(p3[0], p3[1]);
        g.endShape(PConstants.CLOSE);
        g.noStroke();
    }

    /**
     * 敌人详情面板（点敌人时出现）。
     *
     * <p>与干员面板同一套口径，但**没有撤退按钮** —— 敌人不是自己的单位。
     */
    private void drawEnemyPanel() {
        if (panelEnemy == null || !panelEnemy.alive()) {
            panelEnemy = null;
            return;
        }
        com.yestercar.runtime.battle.EnemyType t = panelEnemy.type();
        float w = 300f;
        float h = 274f;
        float x = g.width - w - 24f;
        float y = 90f;

        g.noStroke();
        g.fill(0xF0101418);
        g.rect(x, y, w, h, 6f);
        g.noFill();
        g.stroke(0xFF4A5560);
        g.strokeWeight(1f);
        g.rect(x, y, w, h, 6f);
        g.noStroke();

        // 图标 + 名字
        processing.core.PImage icon = enemyIcon(t.icon());
        if (icon != null) {
            g.imageMode(PConstants.CORNER);
            g.image(icon, x + 16f, y + 12f, 40f, 40f);
        }
        g.fill(0xFFFFFFFF);
        g.textAlign(PConstants.LEFT, PConstants.TOP);
        g.textSize(20f);
        g.text(t.name(), x + (icon == null ? 18f : 64f), y + 16f);

        // 血条
        float barW = w - 150f - 36f;
        g.fill(0xFF2A3038);
        g.rect(x + 18f, y + 62f, barW, 10f, 3f);
        g.fill(0xFFE05A5A);
        g.rect(x + 18f, y + 62f,
                barW * Math.max(0f, panelEnemy.hp() / Math.max(1f, panelEnemy.maxHp())),
                10f, 3f);

        g.fill(0xFFC9D1DA);
        g.textSize(13f);
        int line = 0;
        for (String txt : new String[]{
                String.format("生命 %.0f / %.0f", panelEnemy.hp(), panelEnemy.maxHp()),
                String.format("速度 %.1f   间隔 %.1fs",
                        t.speed(), t.attackInterval()),
                "阻挡权重 " + t.blockWeight()}) {
            g.text(txt, x + 18f, y + 82f + line * 22f);
            line++;
        }

        // 防御/法抗：显示**被削减后的实际值**，与基础值不同时标红
        // ⚠️ 必须用**战斗内时间**（减益存的是 scene.time()，从 0 起算）
        float nowT = scene == null ? 0f : scene.time();
        float defNow = t.def() * panelEnemy.defFactor(nowT);
        float resNow = t.res() * panelEnemy.resFactor(nowT);
        g.fill(0xFFC9D1DA);
        g.text("攻 " + t.atk(), x + 18f, y + 82f + line * 22f);
        float tx = x + 18f + g.textWidth("攻 " + t.atk() + "   ");
        boolean defDown = defNow < t.def() - 0.01f;
        g.fill(defDown ? 0xFFE05A5A : 0xFFC9D1DA);
        g.text("防 " + Math.round(defNow), tx, y + 82f + line * 22f);
        tx += g.textWidth("防 " + Math.round(defNow) + "   ");
        boolean resDown = resNow < t.res() - 0.01f;
        g.fill(resDown ? 0xFFE05A5A : 0xFFC9D1DA);
        g.text("法抗 " + Math.round(resNow), tx, y + 82f + line * 22f);
        line++;

        // 攻击范围：小方格示意（金色 = 覆盖）
        com.yestercar.runtime.battle.EnemyType.ensureRangesLoaded();
        java.util.List<int[]> cells = t.rangeCells();
        float gy = y + 82f + line * 22f + 8f;
        g.textSize(12f);
        g.fill(0xFFC9D1DA);
        g.text("攻击范围", x + 18f, gy);
        int minX = -1;
        int maxX = 0;
        int minY = -1;
        int maxY = 1;
        for (int[] c : cells) {
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minY = Math.min(minY, c[1]);
            maxY = Math.max(maxY, c[1]);
        }
        float cell = 12f;
        float ox = x + 86f;
        float oy = gy - 10f;
        for (int cy = minY; cy <= maxY; cy++) {
            for (int cx = minX; cx <= maxX; cx++) {
                boolean on = false;
                for (int[] c : cells) {
                    if (c[0] == cx && c[1] == cy) {
                        on = true;
                        break;
                    }
                }
                if (on) {
                    g.fill(0xFFE8C15A);
                } else {
                    g.fill(255, 255, 255, 26);
                }
                g.rect(ox + (cx - minX) * (cell + 2f),
                        oy + (cy - minY) * (cell + 2f), cell, cell, 2f);
            }
        }
        g.fill(0xFFC9D1DA);
    }

    /** 干员信息面板（点击已部署干员时出现），带「撤退」按钮。 */
    private void drawInfoPanel() {
        if (panelCell == null || director == null) {
            return;
        }
        com.yestercar.runtime.battle.BattleDirector.UnitView u = null;
        for (com.yestercar.runtime.battle.BattleDirector.UnitView v : director.unitViews()) {
            if (v.col() == panelCell[0] && v.row() == panelCell[1]) {
                u = v;
                break;
            }
        }
        if (u == null) {
            panelCell = null;
            return;
        }
        float w = 440f;
        float h = 470f;   // 干员面板（原 520，按要求减少 50）
        float x = g.width - w - 24f;
        float y = 90f;

        g.noStroke();
        g.fill(0xF0101418);
        g.rect(x, y, w, h, 6f);
        g.noFill();
        g.stroke(0xFF4A5560);
        g.strokeWeight(1f);
        g.rect(x, y, w, h, 6f);
        g.noStroke();

        // 左侧立绘（等比 contain，底部贴面板底边之上）
        com.yestercar.common.model.Operator opMeta = null;
        for (com.yestercar.common.model.Operator cand
                : com.yestercar.common.model.Operator.loadAll().values()) {
            if (cand.name().equals(u.name())) {
                opMeta = cand;
                break;
            }
        }
        float portraitW = 150f;
        if (opMeta != null && opMeta.hasPortrait()) {
            processing.core.PImage pImg = g.loadImage(opMeta.portrait());
            if (pImg != null) {
                float availH = h - 130f;
                float sc = Math.min(portraitW / pImg.width, availH / pImg.height);
                float iw = pImg.width * sc;
                float ih = pImg.height * sc;
                g.imageMode(PConstants.CORNER);
                g.image(pImg, x + (portraitW - iw) / 2f, y + 42f, iw, ih);
            }
        }

        g.fill(0xFFFFFFFF);
        g.textAlign(PConstants.LEFT, PConstants.TOP);
        g.textSize(20f);
        g.text(u.name(), x + portraitW + 12f, y + 16f);

        // 血条
        float barW = w - 150f - 60f;   // 扣掉左侧立绘列
        g.fill(0xFF2A3038);
        g.rect(x + portraitW + 12f, y + 50f, barW, 10f, 3f);
        g.fill(0xFF7BD88F);
        g.rect(x + portraitW + 12f, y + 50f,
                barW * Math.max(0f, u.hp() / Math.max(1f, u.maxHp())), 10f, 3f);

        // 与「干员」页详情一致：图标 + 中文 + 数值，逐行排列
        com.yestercar.common.model.Operator metaOp = null;
        for (com.yestercar.common.model.Operator cand
                : com.yestercar.common.model.Operator.loadAll().values()) {
            if (cand.name().equals(u.name())) {
                metaOp = cand;
                break;
            }
        }
        // 伤害类型以**实时**为准（特性可覆盖），图标与文字同步
        com.yestercar.common.model.DamageType liveType =
                director.damageTypeOfName(u.name());
        if (liveType == null && opMeta != null) {
            liveType = opMeta.damageType();
        }
        boolean isArts = liveType == com.yestercar.common.model.DamageType.ARTS;
        String[][] rows = {
                {"生命值", String.format("%.0f/%.0f", u.hp(), u.maxHp())},
                {isArts ? "法术攻击力" : "攻击力", String.valueOf(u.atk())},
                {"防御力", String.valueOf(u.def())},
                {"法抗", String.valueOf(u.res())},
                {"阻挡数", String.valueOf(u.block())},
                {"部署费用", metaOp == null ? "-" : String.valueOf(metaOp.cost())},
                {"再部署时间", metaOp == null ? "-" : metaOp.redeployTime() + "s"},
        };
        float rowY = y + 74f;
        for (String[] row : rows) {
            processing.core.PImage ic = g.loadImage(com.yestercar.common.util.AssetPaths.attributeIcon(row[0]));
            if (ic != null) {
                g.imageMode(PConstants.CORNER);
                g.image(ic, x + portraitW + 12f, rowY, 22f, 22f);
            }
            g.fill(0xFFC9D1DA);
            g.textAlign(PConstants.LEFT, PConstants.CENTER);
            g.textSize(14f);
            g.text(row[0], x + portraitW + 42f, rowY + 11f);
            g.fill(0xFFFFFFFF);
            g.text(row[1], x + portraitW + 140f, rowY + 11f);
            rowY += 26f;
        }

        // 数值下方补两行纯文字：朝向 / 位置
        g.fill(0xFFC9D1DA);
        g.textAlign(PConstants.LEFT, PConstants.CENTER);
        g.textSize(14f);
        g.text("朝向 " + facingName(u.facing()), x + portraitW + 12f, rowY + 8f);
        rowY += 24f;
        g.text("位置 (" + u.col() + ", " + u.row() + ")", x + portraitW + 12f, rowY + 8f);

        // 撤退按钮
        retreatBtn[0] = x + 18f;
        retreatBtn[1] = y + h - 52f;
        retreatBtn[2] = w - 36f;
        retreatBtn[3] = 34f;
        g.fill(0xFFB7362F);
        g.rect(retreatBtn[0], retreatBtn[1], retreatBtn[2], retreatBtn[3], 4f);
        g.fill(0xFFFFFFFF);
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        g.textSize(15f);
        g.text("撤退", retreatBtn[0] + retreatBtn[2] / 2f, retreatBtn[1] + retreatBtn[3] / 2f);
    }


    /**
     * 诊断：把交互状态摆到某个阶段，用来截图验证渲染。
     *
     * <p>必须在 **draw 之前**调用 —— 之前在截图函数里设置，
     * 画布那一帧已经画完了，于是截图里什么都看不到。
     */
    private void applyDemoState() {
        if (demoApplied || director == null || map == null) {
            return;
        }
        String demo = System.getProperty("yestercar.ui.demo", "");
    if ("facing".equals(demo) && !squad.isEmpty()) {
        demoApplied = true;
        for (int r = 0; r < map.rows() && pendingCol < 0; r++) {
            for (int c = 0; c < map.cols(); c++) {
                if (director.checkDeploy(squad.get(0), c, r) == null) {
                    pendingCol = c;
                    pendingRow = r;
                    break;
                }
            }
        }
        if (pendingCol < 0) {
            // 兜底：费用不够时 checkDeploy 一律不通过，这里直接找一块空地演示
            outer:
            for (int r = 0; r < map.rows(); r++) {
                for (int c = 0; c < map.cols(); c++) {
                    if (map.tileAt(c, r) == com.yestercar.campaigneditor.model.TileType.GROUND) {
                        pendingCol = c;
                        pendingRow = r;
                        break outer;
                    }
                }
            }
        }
        if (pendingCol >= 0) {
            dragSlot = 0;
            deployPhase = DeployPhase.FACING;
            pendingFacing = Director.DOWN;
            float[] c = new float[4];
            camera.project(pendingCol + 0.5f, pendingRow + 0.5f,
                    riseAt(pendingCol, pendingRow), c);
            mouseX = c[0];
            mouseY = c[1] + 60f;      // 鼠标在格子下方 → 朝向"下"
            pendingFacing = facingFromDelta(mouseX, mouseY, pendingCol, pendingRow);
            System.out.println("[诊断] 演示朝向选择: (" + pendingCol + "," + pendingRow
                    + ") 朝向 " + facingName(pendingFacing));
        }
    } else if ("release".equals(demo)) {
        // 验证"松手"状态机：直接进 DRAGGING，然后调 useRelease，看能否走到 FACING
        int c = -1;
        int r = -1;
        outer2:
        for (int rr = 0; rr < map.rows(); rr++) {
            for (int cc = 0; cc < map.cols(); cc++) {
                if (map.tileAt(cc, rr) == com.yestercar.campaigneditor.model.TileType.GROUND) {
                    c = cc;
                    r = rr;
                    break outer2;
                }
            }
        }
        if (c >= 0 && !squad.isEmpty()) {
            deployPhase = DeployPhase.DRAGGING;
            dragSlot = 0;
            float[] pt = new float[4];
            camera.project(c + 0.5f, r + 0.5f, riseAt(c, r), pt);
            mouseReleased(pt[0], pt[1], PConstants.LEFT);
            demoApplied = true;
            System.out.println("[诊断] 模拟松手 → 阶段 " + deployPhase
                    + " 候选格 (" + pendingCol + "," + pendingRow + ")");
        }
    } else if ("enemypanel".equals(demo)) {
        if (scene == null || scene.enemies().isEmpty()) {
            return;          // 还没出怪，下一帧再试
        }
        panelEnemy = scene.enemies().get(0);
        demoApplied = true;
        System.out.println("[诊断] 演示敌人面板: " + panelEnemy.type().name());
    } else if ("panel".equals(demo)) {
        if (director.unitViews().isEmpty()) {
            return;          // 还没部署，下一帧再试（不能提前置位）
        }
        com.yestercar.runtime.battle.BattleDirector.UnitView u = director.unitViews().get(0);
        panelCell = new int[]{u.col(), u.row()};
        demoApplied = true;
        System.out.println("[诊断] 演示信息面板: " + u.name());
    }

    }

    /**
     * 诊断：{@code -Dyestercar.ui.battleshot=<路径>} 进战斗若干秒后截图。
     *
     * <p>{@code saveFrame} 直接取画布，不受窗口遮挡与 DPI 影响（与主菜单同一套做法）。
     */
    private void maybeBattleShot() {
        if (battleShotDone) {
            return;
        }
        String path = System.getProperty("yestercar.ui.battleshot");
        String at = System.getProperty("yestercar.ui.battleshotms");
        int atMs = at == null ? shotAtMs : Integer.parseInt(at);
        if (path == null || nowMs < atMs) {
            return;
        }
        battleShotDone = true;
        g.saveFrame(path);
        System.out.printf("[诊断] 帧率 %.1f  模拟时长 %.1fs  墙钟 %.1fs  费用 %d  已出 %d/%d%n",
                g.frameRate,
                director == null ? -1f : director.time(),
                nowMs / 1000f,
                director == null ? -1 : director.costWhole(),
                spawned, spawnLimit);
        System.out.println("[BattleScreen] 已截图 " + path);
        if ("1".equals(System.getProperty("yestercar.ui.shotexit"))) {
            returnToMenu();
        }
    }

    /**
     * 诊断：{@code -Dyestercar.ui.seqshotdir=<目录>} 在 {@code battleshotms} 之后
     * 按固定间隔连续存帧，用来做"局内动画 GIF"。
     *
     * <p>为什么不复用 {@link #maybeBattleShot()}：那个只存一张。一张静帧无法区分
     * "骨骼真的在动"和"色块占位"，必须沿时间轴取一串帧。
     *
     * <p>配合的属性：{@code yestercar.ui.seqshotcount}（默认 24 帧）、
     * {@code yestercar.ui.seqshotinterval}（默认 50ms ≈ 20fps）。
     */
    private void maybeSequenceShot() {
        String dir = System.getProperty("yestercar.ui.seqshotdir");
        if (dir == null || dir.isBlank()) {
            return;
        }
        String at = System.getProperty("yestercar.ui.battleshotms");
        int startMs = at == null ? shotAtMs : Integer.parseInt(at);
        int count = Integer.getInteger("yestercar.ui.seqshotcount", 24);
        int interval = Integer.getInteger("yestercar.ui.seqshotinterval", 50);
        if (seqShotIndex < 0) {
            if (nowMs < startMs) {
                return;
            }
            seqShotIndex = 0;
            seqShotNextMs = nowMs;
            System.out.printf("[诊断] 序列截图开始 %s 帧数=%d 间隔=%dms 模拟时刻=%.2fs%n",
                    dir, count, interval, director == null ? -1f : director.time());
        }
        if (seqShotIndex >= count || nowMs < seqShotNextMs) {
            return;
        }
        java.io.File out = new java.io.File(dir);
        if (!out.isDirectory() && !out.mkdirs()) {
            System.out.println("[诊断] 序列截图建不了目录: " + dir);
            seqShotIndex = count;
            return;
        }
        String frame = new java.io.File(out,
                String.format(java.util.Locale.ROOT, "frame-%03d.png", seqShotIndex)).getPath();
        g.saveFrame(frame);
        seqShotIndex++;
        seqShotNextMs = nowMs + interval;
        if (seqShotIndex >= count) {
            System.out.println("[诊断] 序列截图完成 " + count + " 帧 → " + dir
                    + "  模拟时刻 " + String.format(java.util.Locale.ROOT, "%.2fs",
                    director == null ? -1f : director.time()));
            if ("1".equals(System.getProperty("yestercar.ui.shotexit"))) {
                returnToMenu();
            }
        }
    }

    /**
     * 诊断：{@code -Dyestercar.ui.autoreturn=1} 结算后自动返回主菜单。
     *
     * <p>验证"战斗窗口隐藏 + 主菜单回到前台 + 能再次开战"这条链路。
     */
    private void maybeAutoReturn() {
        if (director == null || !director.finished() || exited) {
            return;
        }
        System.out.println("[诊断] autoreturn 触发: won=" + director.won()
                + " 漏怪=" + scene.leaked() + " 已出=" + spawned + "/" + spawnLimit
                + " 门上=" + scene.enemies().size() + " 门全丢=" + scene.allDoorsLost());
        if (!"1".equals(System.getProperty("yestercar.ui.autoreturn"))) {
            return;
        }
        returnToMenu();
    }

    /**
     * 诊断：{@code -Dyestercar.ui.autodeploy=1} 自动把编队放到离蓝门最近的合法格子上。
     *
     * <p>我点不了鼠标，用这个开关把"部署 → 阻挡 → 攻击 → 结算"整条链路跑通，
     * 日志与结算画面就是证据。走的是**和点击完全一样**的 {@code director.deploy}。
     */
    private void maybeAutoDeploy() {
        if (director == null || director.finished()) {
            return;
        }
        if (!"1".equals(System.getProperty("yestercar.ui.autodeploy"))) {
            return;
        }
        if (nowMs - lastAutoDeployMs < 2000) {
            return;
        }
        lastAutoDeployMs = nowMs;

        for (com.yestercar.common.model.Operator op : squad) {
            if (!director.readyToDeploy(op.id())) {
                continue;                       // 已在场或冷却中
            }
            if (director.costWhole() < op.cost()) {
                continue;                       // 再等等，费用会涨
            }
            int[] cell = bestCellFor(op);
            if (cell == null) {
                continue;
            }
            com.yestercar.common.util.Director facing = bestFacingFor(op, cell[0], cell[1]);
            String err = director.deploy(op, cell[0], cell[1], facing);
            System.out.println("[诊断] 部署 " + op.name() + " @(" + cell[0] + "," + cell[1]
                    + ") 朝向 " + facing + " → " + (err == null ? "成功" : err));
            if (err == null) {
                // 诊断：把刚部署干员的**屏幕脚底坐标**打出来，截图工具据此裁图
                float[] q = new float[4];
                camera.project(cell[0] + 0.5f, cell[1] + 0.5f, riseAt(cell[0], cell[1]), q);
                System.out.println("[诊断] 屏幕坐标 " + op.id() + " = " + Math.round(q[0]) + ","
                        + Math.round(q[1]) + " 格宽 " + Math.round(q[2]));
            }
        }
    }

    /**
     * 诊断：给自动部署挑一个朝向。
     *
     * <p>为什么需要：以前固定朝上，遇到"敌人沿横向通道过来"的地图，干员会站着不动
     * （等于没演示到出手动画）。现在优先**朝最近的活着的敌人**；场上没有敌人时
     * 退到"攻击范围覆盖最多敌人路径格"的朝向。
     *
     * @param op  干员
     * @param col 部署列
     * @param row 部署行
     * @return 朝向
     */
    private com.yestercar.common.util.Director bestFacingFor(
            com.yestercar.common.model.Operator op, int col, int row) {
        com.yestercar.common.util.Director fallback = com.yestercar.common.util.Director.UP;
        if (scene == null || scene.enemies().isEmpty()) {
            return fallback;
        }
        // ① 有活着的敌人就朝最近的敌人：它会被挡在身前，正好落在射程里。
        //    比"按路径覆盖打分"可靠 —— 路径格在流场里连通，四个朝向常常同分。
        com.yestercar.runtime.battle.Enemy nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        for (com.yestercar.runtime.battle.Enemy e : scene.enemies()) {
            if (!e.alive()) {
                continue;
            }
            int d = Math.abs(e.col() - col) + Math.abs(e.row() - row);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = e;
            }
        }
        if (nearest != null) {
            int dc = nearest.col() - col;
            int dr = nearest.row() - row;
            if (dc != 0 || dr != 0) {
                if (Math.abs(dc) >= Math.abs(dr)) {
                    return dc > 0 ? com.yestercar.common.util.Director.RIGHT
                            : com.yestercar.common.util.Director.LEFT;
                }
                return dr > 0 ? com.yestercar.common.util.Director.DOWN
                        : com.yestercar.common.util.Director.UP;
            }
        }
        com.yestercar.runtime.pathfinding.FlowField f =
                scene.fieldFor(scene.enemies().get(0).targetBlueDoorId());
        if (f == null) {
            return fallback;
        }
        com.yestercar.common.util.Director best = fallback;
        int bestCount = -1;
        float bestFlow = -1f;
        for (com.yestercar.common.util.Director d : com.yestercar.common.util.Director.values()) {
            int count = 0;
            float flow = 0f;
            for (com.yestercar.common.model.CellOffset off : op.rangeFor(d)) {
                int c = col + off.dx();
                int r = row + off.dy();
                if (f.isReachable(c, r)) {
                    count++;                // 敌人真的会走到这一格
                    flow += f.distanceAt(c, r);   // 流场距离越大 = 越靠敌人上游
                }
            }
            // 先比覆盖格数，再比"朝着敌人来向"（否则横向通道会挑到朝上）
            if (count > bestCount || (count == bestCount && flow > bestFlow)) {
                bestCount = count;
                bestFlow = flow;
                best = d;
            }
        }
        return best;
    }

    /**
     * 找离蓝门最近、且该干员能站的格子（判定完全交给 {@code DeploymentSystem}）。
     *
     * @param op 干员
     * @return {@code {col,row}}；找不到返回 {@code null}
     */
    private int[] bestCellFor(com.yestercar.common.model.Operator op) {
        if (scene == null || map == null || scene.enemies().isEmpty()) {
            return null;
        }
        com.yestercar.runtime.pathfinding.FlowField f =
                scene.fieldFor(scene.enemies().get(0).targetBlueDoorId());
        if (f == null) {
            return null;
        }
        int bestCol = -1;
        int bestRow = -1;
        float bestScore = Float.MAX_VALUE;
        for (int c = 0; c < map.cols(); c++) {
            for (int r = 0; r < map.rows(); r++) {
                if (director.checkDeploy(op, c, r) != null) {
                    continue;           // 纯判定，无副作用
                }
                float score = pathDistanceNear(f, c, r);
                if (score < bestScore) {
                    bestScore = score;
                    bestCol = c;
                    bestRow = r;
                }
            }
        }
        return bestCol < 0 ? null : new int[]{bestCol, bestRow};
    }

    /**
     * 某一格"离敌人路径有多近"的评分（越小越靠近蓝门）。
     *
     * <p>⚠️ 不能直接读该格的流场距离：**高台在流场里是不可达的**
     * （流场只对可走地面求距离），直接读会得到无穷大，于是高台干员永远找不到格子。
     * 所以高台取"四邻里最小的可达距离 + 1"，等价于"贴着路线的平台"。
     *
     * @param f   流场
     * @param col 列
     * @param row 行
     * @return 评分；完全不可达返回 {@link Float#MAX_VALUE}
     */
    private float pathDistanceNear(com.yestercar.runtime.pathfinding.FlowField f, int col, int row) {
        float own = f.distanceAt(col, row);
        if (Float.isFinite(own) && own > 0f) {
            return own;
        }
        float best = Float.MAX_VALUE;
        int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int[] d : dirs) {
            float v = f.distanceAt(col + d[0], row + d[1]);
            if (Float.isFinite(v)) {
                best = Math.min(best, v + 1f);
            }
        }
        return best;
    }

    /**
     * 部署栏命中测试。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return 命中第几张卡；未命中返回 -1
     */
    private int deployBarHit(float mx, float my) {
        if (squad == null) {
            return -1;
        }
        float y = g.height - BAR_H + 8f;
        for (int i = 0; i < squad.size(); i++) {
            float x = 12f + i * (CARD_W + CARD_GAP);
            if (mx >= x && mx <= x + CARD_W && my >= y && my <= y + CARD_H) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 屏幕坐标 → 格子。
     *
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @return {@code {col,row}}；点到相机背后或地图外返回 {@code null}
     */
    private int[] cellAt(float mx, float my) {
        float[] out = new float[2];
        if (camera == null || !camera.unproject(mx, my, 0f, out)) {
            return null;
        }
        int col = (int) Math.floor(out[0]);
        int row = (int) Math.floor(out[1]);
        if (map == null || !map.inBounds(col, row)) {
            return null;
        }
        return new int[]{col, row};
    }

    /**
     * 返回主菜单：隐藏本窗口并通知宿主。
     *
     * <p>⚠️ 用 {@code setVisible(false) + noLoop()}，**不调 {@code exit()}** ——
     * 这个 sketch 是 {@code PApplet.runSketch} 开的第二个窗口，
     * 强制退出可能连带结束整个进程。
     */
    private void returnToMenu() {
        if (exited) {
            return;
        }
        exited = true;
        System.out.println("[BattleScreen] 返回主菜单");
        returnRequested = true;
    }

    /**
     * 隐藏本窗口并停止循环（宿主开下一局前调用）。
     *
     * <p>不销毁 —— 销毁（{@code exit()}）会连带影响进程，而 {@code runSketch}
     * 开的窗口只应隐藏。
     */
    public void hideWindow() {
        exited = true;
    }

    /**
     * 设置"返回主菜单"的回调（由 {@code GameOpeningApp} 在开战前设置）。
     *
     * @param callback 回调
     */
    public static void setOnExitToMenu(Runnable callback) {
        onExitToMenu = callback;
    }

    /**
     * 重开一局：复用同一个窗口（不新建）。
     *
     * <p>{@code setup()} 只会跑一次，所以重开必须自己把状态清干净。
     *
     * @param campaignName 战役目录名
     * @param squadIds     编队
     */
    public void reload(String campaignName, java.util.List<String> squadIds) {
        System.out.println("[BattleScreen] 复用窗口重开: " + campaignName);
        pendingCampaign = campaignName;
        setPendingSquad(squadIds);
        spawned = 0;
        spawnTimer = 0f;
        selectedSlot = -1;
        hint = "";
        exited = false;
        finishedReset();
        loadError = null;
        scene = null;
        loadCampaign(campaignName);
        System.out.println("[BattleScreen] 重开结果: loadError=" + loadError
                + " scene=" + (scene != null) + " director=" + (director != null)
                + " 编队=" + squad.size());
    }

    /** 清掉结算状态（{@link #reload} 用；director 会重建，这里只是兜底）。 */
    private void finishedReset() {
        won = false;
        stars = 0;
    }

    /** @return 按下的是不是这个字母（不分大小写） */
    private boolean isKey(char lower) {
        return lastKey == lower || lastKey == Character.toUpperCase(lower);
    }

    /**
     * 算这一帧过了多少秒。
     *
     * 按固定值算会让慢帧时敌人走得比设定速度慢。
     * 上限 0.1 秒 —— 窗口被拖动或系统卡顿后 dt 可能是几秒，
     * 那样敌人会瞬间冲过整个地图。
     */
    private float computeDeltaSeconds() {
        int now = nowMs;
        if (lastMillis < 0) {
            lastMillis = now;
            return 0f;
        }
        float dt = (now - lastMillis) / 1000f;
        lastMillis = now;
        return Math.min(Math.max(dt, 0f), 0.1f);
    }

    /**
     * 门的位置画一个高亮框（调试用，默认关闭）。
     *
     * <p>让"从哪出、去哪"一眼可见 —— 排查寻路时很有用，
     * 但正式使用时要靠贴图本身表达，所以默认不画。
     */
    private void drawDoorMarks() {
        for (Door d : map.redDoors()) {
            drawDoorSquare(d, 0xFFFF3B30);
        }
        for (Door d : map.blueDoors()) {
            // 血越少越淡：一眼能看出还剩几条命
            int alpha = d.hp() <= 0 ? 60 : 255;
            drawDoorSquare(d, (alpha << 24) | 0x004AABEA);
        }
    }

    private void drawDoorSquare(Door d, int color) {
        float[] p = new float[4];
        camera.project(d.col() + 0.5f, d.row() + 0.5f, 0.02f, p);
        // p[2] 是"该处每格多少像素"，乘一个小于 1 的系数让框落在格子内部
        float size = PApplet.constrain(p[2] * 0.8f, 6f, 200f);
        g.noFill();
        g.stroke(color);
        g.strokeWeight(3f);
        g.rect(p[0] - size / 2f, p[1] - size / 2f, size, size, 4f);
        g.noStroke();
    }

    /**
     * 画一个敌人：骨骼动画（有素材时）+ 头顶血条。
     *
     * <p>没有素材 / 解析失败时退回原来的色块占位（椭圆），
     * 这样美术可以随时增量交付，不会挡住别人跑游戏。
     */
    private void drawEnemy(Enemy e) {
        float[] base = new float[4];
        camera.project(e.x(), e.y(), 0.02f, base);
        float[] top = new float[4];
        camera.project(e.x(), e.y(), 0.55f, top);

        // 用"该处每格像素数"换算大小：近大远小是透视的自然结果，不用另写换算
        float size = PApplet.constrain(base[2] * ENEMY_RADIUS * 2f, 5f, 120f);

        // 影子：无论骨骼还是色块都先垫一层，让敌人看起来站在地上
        g.noStroke();
        g.fill(0, 0, 0, 70);
        g.ellipse(base[0], base[1], size * 1.1f, size * 0.5f);

        com.yestercar.runtime.view.EnemyAnimator anim =
                enemySpine == null ? null : enemySpine.at(e);
        if (anim != null) {
            // 骨骼绘制要走 PGraphics（PApplet 只是宿主，真正的画布是 g.getGraphics()），
            // 和干员层同一条路径；受击染红用 0.45 的强度（敌人比干员挨打更频繁）。
            float tint = 0f;
            if (e.hp() < lastHpOf(e)) {
                hitFlashUntil.put(e, System.currentTimeMillis() + 120);
            }
            Long until = hitFlashUntil.get(e);
            if (until != null && until > System.currentTimeMillis()) {
                tint = 0.45f;
            }
            g.pushMatrix();
            g.resetMatrix();
            g.hint(PConstants.DISABLE_DEPTH_TEST);
            anim.draw(g.getGraphics(), base[0], base[1], base[2], tint);
            g.hint(PConstants.ENABLE_DEPTH_TEST);
            g.popMatrix();
        } else {
            // 身体：色块占位。
            // ⚠️ data/imgs/enemy 的图**不是**作战贴图 —— 那批图只用于编辑器选敌人、
            //    以及关卡信息里的"图标 + 数量"。所以这里不画图，素材缺失时才用它兜底。
            g.fill(UITheme.COLOR_ACCENT);
            g.stroke(0xFFFFFFFF);
            g.strokeWeight(1.5f);
            g.ellipse(top[0], top[1], size, size);
            g.noStroke();
        }

        // 血条（满血也画，便于看出这是敌人）；挂在"头顶上方"，跟着该处每格像素数走
        float hpRatio = e.maxHp() <= 0 ? 0f : (float) e.hp() / e.maxHp();
        float barW = Math.max(18f, base[2] * 0.5f);
        float spriteH = anim != null
                ? com.yestercar.runtime.view.EnemyAnimator.HEIGHT_TILES * base[2]
                : size * 1.6f;
        float barY = base[1] - spriteH - 8f;
        g.fill(0, 0, 0, 120);
        g.rect(base[0] - barW / 2f, barY, barW, 4f);
        g.fill(0xFF4CD964);
        g.rect(base[0] - barW / 2f, barY, barW * hpRatio, 4f);
    }

    /** 上一帧看到的每个敌人的血量（只用于"掉血 → 闪一下"的表现判断）。 */
    private final java.util.Map<Enemy, Float> lastHp =
            new java.util.IdentityHashMap<>();

    /** 受击闪红截止时刻（毫秒）。 */
    private final java.util.Map<Enemy, Long> hitFlashUntil =
            new java.util.IdentityHashMap<>();

    /**
     * 清掉已经不在场上的敌人留下的表现状态（血量记录 / 闪红截止时刻）。
     *
     * <p>两张表都按对象身份存，敌人一死就再也不会被访问；不清理的话一整局会慢慢涨，
     * 虽然量很小（每只几十字节），但这种"只增不减"的表在长局里迟早会被发现。
     *
     * @param alive 本帧场上的敌人
     */
    private void pruneEnemyFx(java.util.List<Enemy> alive) {
        if (lastHp.isEmpty() && hitFlashUntil.isEmpty()) {
            return;
        }
        java.util.Set<Enemy> keep =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        keep.addAll(alive);
        lastHp.keySet().retainAll(keep);
        hitFlashUntil.keySet().retainAll(keep);
    }

    /**
     * @param e 敌人
     * @return 上一帧的血量（第一次见到时返回当前血量，不会误触发闪红）
     */
    private float lastHpOf(Enemy e) {
        Float prev = lastHp.get(e);
        float now = e.hp();
        if (prev == null) {
            lastHp.put(e, now);
            return now;
        }
        lastHp.put(e, now);
        return prev;
    }

    /**
     * 画一具敌人尸体：死亡动画续播 → 定格 → 淡出，配一圈扩散的尘土和淡出的影子。
     *
     * <p>引擎在敌人阵亡的当 tick 就把它从 {@code scene.enemies()} 里移走，
     * 所以尸体只能靠表现层续播（动画器记着它最后一帧的世界坐标）。
     * 具体画法在 {@link com.yestercar.runtime.view.EnemyAnimator#drawCorpse}，
     * 与诊断工具 {@code EnemyDeathSheet} 共用同一段代码。
     *
     * @param anim 尸体动画器
     */
    private void drawEnemyCorpse(com.yestercar.runtime.view.EnemyAnimator anim) {
        float[] p = new float[4];
        camera.project(anim.worldX(), anim.worldY(), 0.02f, p);
        g.pushMatrix();
        g.resetMatrix();
        g.hint(PConstants.DISABLE_DEPTH_TEST);
        anim.drawCorpse(g.getGraphics(), p[0], p[1], p[2]);
        g.hint(PConstants.ENABLE_DEPTH_TEST);
        g.popMatrix();
    }

    /**
     * 取敌人图标（带缓存）。
     *
     * @param path {@code data/enemies.csv} 里的 {@code icon} 列
     * @return 图片；路径为空或读不到返回 {@code null}（调用方退化成色块）
     */
    private processing.core.PImage enemyIcon(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (enemyIcons.containsKey(path)) {
            return enemyIcons.get(path);
        }
        processing.core.PImage img = g.loadImage(path);
        if (img == null) {
            System.out.println("[BattleScreen] 敌人图标读取失败: " + path);
        }
        enemyIcons.put(path, img);
        return img;
    }

    /** 上次看到的心跳生命值（用于判断"减少了"→红 1 秒）。 */
    private int lastBlueHp = -1;

    /** 掉血高亮截止时刻（毫秒）。 */
    private int blueHpFlashUntil;

    /** 心形图标缓存。 */
    private processing.core.PImage healthIcon;

    /**
     * 居中正上方的生命显示：灰底方框 + 图标 + 蓝字数字。
     *
     * <p>生命**减少时**数字变红并持续 1 秒。
     */
    /**
     * 顶部击杀计数器：图标 + {@code a/b}（a=已消灭，b=本关总数），白字灰底。
     *
     * <p>它的左边缘落在屏幕中心，正好与生命值框的右边缘相接。
     */
    private void drawKilledCounter() {
        if (scene == null) {
            return;
        }
        float w = 144f;
        float h = 51f;
        float x = g.width / 2f;
        float y = 42f;

        g.noStroke();
        g.fill(64f, 66f, 70f, 230f);
        g.rect(x, y, w, h, 3f);

        processing.core.PImage icon = killedIcon;
        if (icon == null) {
            killedIcon = g.loadImage("data/imgs/enemy/killed.png");
            icon = killedIcon;
        }
        float iconSize = 30f;
        if (icon != null) {
            float sc = iconSize / Math.max(icon.width, icon.height);
            float iw = icon.width * sc;
            float ih = icon.height * sc;
            g.imageMode(PConstants.CORNER);
            g.image(icon, x + 12f + (iconSize - iw) / 2f, y + (h - ih) / 2f, iw, ih);
        }

        int total = director == null ? 0 : director.totalEnemies();
        int killed = director == null ? 0 : director.killedCount();
        // 漏怪也算"已清理"：走到蓝门即离场，计入分子，使结束时 a == b
        int leaked = scene == null ? 0 : scene.leaked();
        int done = killed + leaked;
        g.fill(0xFFFFFFFF);
        g.textAlign(PConstants.LEFT, PConstants.CENTER);
        g.textSize(30);
        g.text(done + "/" + total, x + 12f + iconSize + 12f, y + h / 2f);
    }

    private void drawBlueHp() {
        if (scene == null) {
            return;
        }
        int hp = scene.blueHp();
        int now = nowMs;
        if (lastBlueHp < 0) {
            lastBlueHp = hp;
        } else if (hp < lastBlueHp) {
            blueHpFlashUntil = now + 1000;      // 掉血 → 红 1 秒
            lastBlueHp = hp;
        } else if (hp > lastBlueHp) {
            lastBlueHp = hp;
        }
        boolean flash = now < blueHpFlashUntil;

        float w = 144f;          // 96 * 1.5
        float h = 51f;           // 34 * 1.5
        // 两个计数器以屏幕中心为交界：生命框整体左移半个宽度
        float x = g.width / 2f - w;
        float y = 42f;

        g.noStroke();
        g.fill(0xFF3A3F45);
        g.rect(x, y, w, h, 4f);
        g.noFill();
        g.stroke(0xFF22262A);
        g.strokeWeight(1f);
        g.rect(x, y, w, h, 4f);
        g.noStroke();

        if (healthIcon == null) {
            healthIcon = g.loadImage("data/imgs/fighting/health.png");
        }
        if (healthIcon != null) {
            g.imageMode(PConstants.CORNER);
            g.image(healthIcon, x + 12f, y + 10f, 30f, 30f);
        }

        g.fill(flash ? 0xFFE05A5A : 0xFF3FA9F5);
        g.textAlign(PConstants.LEFT, PConstants.CENTER);
        g.textSize(30f);
        g.text(String.valueOf(hp), x + 54f, y + h / 2f);
    }

    /** HUD：屏幕坐标，不受相机影响。 */
    /**
     * 战斗 HUD（策划 §10.4.4 的 {@code HUDRenderer} + {@code DeployPanel}）。
     *
     * <p>本轮的排版是**最小可用**：顶栏费用/漏怪/波次，底栏部署卡。
     * 视觉打磨留到 P5.5。
     */
    /**
     * @param mx 鼠标 x
     * @param my 鼠标 y
     * @param x  矩形左
     * @param y  矩形上
     * @param w  宽
     * @param h  高
     * @return 是否落在矩形内
     */
    /**
     * @param facing 朝向名（"right"/"up"…）
     * @return 对应枚举；无法识别返回 RIGHT
     */
    private static com.yestercar.common.util.Director facingOf(
            com.yestercar.common.util.Director facing) {
        return facing == null ? com.yestercar.common.util.Director.RIGHT : facing;
    }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /**
     * 在选中地块上覆盖"攻击范围"（黄框）。
     *
     * @param col    干员所在列
     * @param row    干员所在行
     * @param facing 朝向
     * @param op     干员（提供射程）
     */
    private void drawRangeHighlight(int col, int row,
            com.yestercar.common.util.Director facing,
            com.yestercar.common.model.Operator op) {
        if (camera == null || map == null || op == null) {
            return;
        }
        // ⚠️ Camera.project 会写 4 个分量（x, y, k, depth）
        float[] a = new float[4];
        float[] b = new float[4];
        float[] c = new float[4];
        float[] e = new float[4];
        java.util.Set<com.yestercar.common.model.CellOffset> cells =
                new java.util.LinkedHashSet<>(op.rangeFor(facing));
        cells.add(com.yestercar.common.model.CellOffset.self());   // 自身格必须有
        for (com.yestercar.common.model.CellOffset off : cells) {
            int tc = col + off.dx();
            int tr = row + off.dy();
            // 越界格子直接不画（以地图边界为准，绝不画到地图外）
            if (tc < 0 || tr < 0 || tc >= map.cols() || tr >= map.rows()) {
                continue;
            }
            float z = riseAt(tc, tr) + 0.01f;
            camera.project(tc, tr, z, a);
            camera.project(tc + 1, tr, z, b);
            camera.project(tc + 1, tr + 1, z, c);
            camera.project(tc, tr + 1, z, e);
            g.noStroke();
            g.fill(232f, 193f, 90f, 70f);
            g.beginShape();
            g.vertex(a[0], a[1]);
            g.vertex(b[0], b[1]);
            g.vertex(c[0], c[1]);
            g.vertex(e[0], e[1]);
            g.endShape(PConstants.CLOSE);
            g.stroke(232f, 193f, 90f, 200f);
            g.strokeWeight(1.5f);
            g.noFill();
            g.beginShape();
            g.vertex(a[0], a[1]);
            g.vertex(b[0], b[1]);
            g.vertex(c[0], c[1]);
            g.vertex(e[0], e[1]);
            g.endShape(PConstants.CLOSE);
            g.noStroke();
        }
    }

    /** 右上角停止键 + 退出确认框。 */
    private void drawPauseUi() {
        float sz = 52f * uiScale;
        float x = g.width - 64f * uiScale;
        float y = 12f * uiScale;
        g.noStroke();
        g.fill(0f, 0f, 0f, 150f);
        g.rect(x, y, sz, sz, 4f);
        g.fill(0xFFFFFFFF);
        float bx = x + sz * 0.36f;
        float by = y + sz * 0.28f;
        float bw = sz * 0.1f;
        g.rect(bx, by, bw, sz * 0.44f);
        g.rect(bx + sz * 0.18f, by, bw, sz * 0.44f);

        if (!askQuit) {
            return;
        }
        g.fill(0f, 0f, 0f, 180f);
        g.rect(0, 0, g.width, g.height);
        float bw2 = 180f * uiScale;
        float bh = 60f * uiScale;
        float cx = g.width / 2f;
        float cy = g.height / 2f;
        g.fill(0xFFE8C15A);
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        g.textSize(30 * uiScale);
        g.text("是否退出作战？", cx, cy - 40f * uiScale);

        g.fill(0xFF3A4048);
        g.rect(cx - bw2 - 20f * uiScale, cy + 20f * uiScale, bw2, bh, 4f);
        g.fill(0xFFC0392B);                     // 退出 = 红
        g.rect(cx + 20f * uiScale, cy + 20f * uiScale, bw2, bh, 4f);
        g.fill(0xFFFFFFFF);
        g.textSize(24 * uiScale);
        g.text("继续", cx - bw2 / 2f - 20f * uiScale, cy + 20f * uiScale + bh / 2f);
        g.text("退出", cx + bw2 / 2f + 20f * uiScale, cy + 20f * uiScale + bh / 2f);
    }

    private void drawHud() {
        drawBlueHp();
        drawKilledCounter();
        drawPauseUi();
        if (director == null) {
            return;
        }
        g.textFont(font);
        g.textAlign(PConstants.LEFT, PConstants.BASELINE);
        g.noStroke();

        // ---- 顶栏已移除：费用/部署/击杀改到下方待部署区与顶部计数器 ----

        // ---- 底部部署栏 ----
        drawDeployBar();

        // 常驻操作提示已按需求移除

        // ---- 瞬时提示 ----
        if (!hint.isEmpty()) {
            g.fill(0, 0, 0, 150);
            float hintH = 34f * uiScale;
            g.rect(0, g.height - BAR_H - hintH, g.width, hintH);
            g.fill(0xFFE8C15A);
            g.textAlign(PConstants.LEFT, PConstants.CENTER);
            g.textSize((UITheme.FONT_SIZE + 8) * uiScale);   // 放大提示文字
            g.text(hint, 14, g.height - BAR_H - hintH / 2f);
        }

        // ---- 结算 ----
        if (director.finished()) {
            if (!finishLogged) {
                finishLogged = true;
                System.out.println("[BattleScreen] 结算: "
                        + (director.won() ? "胜利" : "失败")
                        + "  星级 " + director.stars()
                        + "  漏怪 " + scene.leaked()
                        + "  用时 " + String.format("%.1f", director.time()) + "s");
            }
            drawSettlement();
        }

        maybeAutoDeploy();
        maybeAutoReturn();
        maybeBattleShot();
        maybeSequenceShot();
    }

    /**
     * 底栏部署卡（第 17 轮决定：加高到 92、每张放 1:2 缩略图、只显示实际编队人数）。
     *
     * <p>顺序由 {@link #buildSquad()} 排好（费用升序、同费按 id），这里不再排。
     */
    /** 待部署区最右侧：费用 / 部署限制（白字，与顶部计数器同一套样式）。 */
    private void drawDeployStats() {
        if (director == null) {
            return;
        }
        g.noStroke();
        g.fill(0xFFFFFFFF);
        g.textAlign(PConstants.RIGHT, PConstants.BOTTOM);
        // 两行整体居中于待部署区
        float midY = g.height - BAR_H / 2f;
        g.textSize(34 * uiScale);
        g.fill(0xFFE8C15A);                       // 费用（含数字）用金色
        g.text("费用 " + director.costWhole(), g.width - 24f, midY - 6f);
        g.textSize(22 * uiScale);
        g.fill(0xFFFFFFFF);
        g.text("部署 " + director.deployedCount() + "/"
                + com.yestercar.runtime.battle.BattleDirector.DEPLOY_LIMIT,
                g.width - 24f, midY + 30f);
    }

    private void drawDeployBar() {
        drawDeployStats();
        if (squad == null || squad.isEmpty()) {
            return;
        }
        float y = g.height - BAR_H + 8f;
        for (int i = 0; i < squad.size(); i++) {
            float x = 12f + i * (CARD_W + CARD_GAP);
            if (x + CARD_W > g.width - 12f) {
                break;          // 窗口太窄就截断（最少 1 人，正常窗口放得下 12 张）
            }
            com.yestercar.common.model.Operator op = squad.get(i);
            boolean onField = director.onField(op.id());
            boolean affordable = director.costWhole() >= op.cost();
            boolean ready = director.readyToDeploy(op.id());
            boolean usable = affordable && ready;

            g.noStroke();
            g.fill(i == selectedSlot ? 0xFFE8C15A : (usable ? 0xCC2A3038 : 0x88202529));
            g.rect(x, y, CARD_W, CARD_H, 3);

            // 缩略图：等比 contain（不拉伸，避免"图片压缩严重"）
            float tx = x + 4f;
            float ty = y + 4f;
            float tw = CARD_W - 8f;
            float th = CARD_H - 26f;
            processing.core.PImage img = thumbOf(op);
            if (img != null) {
                float sc = Math.min(tw / img.width, th / img.height);
                float iw = img.width * sc;
                float ih = img.height * sc;
                g.imageMode(PConstants.CORNER);
                g.image(img, tx + (tw - iw) / 2f, ty + (th - ih) / 2f, iw, ih);
            } else {
                g.fill(0x33FFFFFF);
                g.rect(tx, ty, THUMB_W, THUMB_H, 2);
                g.fill(0x99C9D1DA);
                g.textAlign(PConstants.CENTER, PConstants.CENTER);
                g.textSize(14 * uiScale);
                g.text("?", tx + THUMB_W / 2f, ty + THUMB_H / 2f);
            }

            // 左上角职业图标：必须画在缩略图**之后**，否则会被立绘盖住
            processing.core.PImage clsIcon =
                    g.loadImage("data/imgs/Class/" + op.opClass() + ".png");
            if (clsIcon != null) {
                float iconSide = CARD_W / 3f;
                g.imageMode(PConstants.CORNER);
                g.image(clsIcon, x + 3f, y + 3f, iconSide, iconSide);
            }

            // 费用
            g.fill(usable ? 0xFFFFFFFF : 0xFF8A939D);
            g.textAlign(PConstants.CENTER, PConstants.BOTTOM);
            g.textSize(11 * uiScale);
            g.text(op.cost() + "费", x + CARD_W / 2f, y + CARD_H - 3f);

            // 不可部署的两种状态要分开画：
            //   · 已在场上 → 「在场」（不是冷却，冷却是 0 秒，画出来像坏了）
            //   · 冷却中   → 剩余秒数
            if (onField) {
                g.fill(0, 0, 0, 165);
                g.rect(x, y, CARD_W, CARD_H, 3);
                g.fill(0xFF9BD3A2);
                g.textAlign(PConstants.CENTER, PConstants.CENTER);
                g.textSize(12 * uiScale);
                g.text("在场", x + CARD_W / 2f, y + CARD_H / 2f);
            } else if (!ready) {
                g.fill(0, 0, 0, 175);
                g.rect(x, y, CARD_W, CARD_H, 3);
                g.fill(0xFFFFFFFF);
                g.textAlign(PConstants.CENTER, PConstants.CENTER);
                g.textSize(16 * uiScale);
                g.text(String.format("%.0f", Math.ceil(director.cooldownOf(op.id()))),
                        x + CARD_W / 2f, y + CARD_H / 2f);
            }
        }
    }

    /**
     * 取部署栏缩略图（1:2，带缓存）。
     *
     * @param op 干员
     * @return 图片；没有立绘或读不到返回 {@code null}
     */
    private processing.core.PImage thumbOf(com.yestercar.common.model.Operator op) {
        if (!op.hasPortrait()) {
            return null;
        }
        String path = op.portrait();
        if (thumbs.containsKey(path)) {
            return thumbs.get(path);
        }
        processing.core.PImage img = g.loadImage(path);
        if (img == null) {
            System.out.println("[BattleScreen] 缩略图读取失败: " + path);
        }
        thumbs.put(path, img);
        return img;
    }

    /** 结算面板（策划 §2.3：3 星 / 2 星 / 0 星）。 */
    private void drawSettlement() {
        g.fill(0, 0, 0, 190);
        g.rect(0, 0, g.width, g.height);

        drawResultCard();
        if (resultDone) {
            returnToMenu();          // 滑出结束 → 立即黑屏返回（不必等点击）
        }
    }

    /**
     * 结算卡片：半透明灰底 + 居中卡片，从左侧滑入，停留等待点击，再从右侧滑出。
     *
     * <p>卡片左侧是可选的 {@code MissionComplete.png}，右侧分五部分：
     * 粗白线 / 星级 + 理由 / Mission / Complete / 粗白线。
     */
    private void drawResultCard() {
        float now = nowMs;    // 用外部传入的毫秒（本类不是 PApplet）
        if (resultPhase == 0) {
            resultPhase = 1;
            resultAt = (long) now;
        }
        float cw = g.width * 0.62f;
        float ch = g.height * 0.42f;
        float cx = g.width / 2f;
        float cy = g.height / 2f;
        float inMs = 450f;
        float outMs = 350f;

        // 卡片左上角 x：滑入时从 -cw 到 cx-cw/2；滑出时到 g.width
        float x;
        if (resultPhase == 1) {
            float t = Math.min(1f, (now - resultAt) / inMs);
            float e = 1f - (1f - t) * (1f - t);          // ease-out
            x = -cw + (cx - cw / 2f + cw) * e;
            if (t >= 1f) {
                resultPhase = 2;
            }
        } else if (resultPhase == 2) {
            x = cx - cw / 2f;
        } else {
            float t = Math.min(1f, (now - resultAt) / outMs);
            float e = t * t;                              // ease-in
            x = (cx - cw / 2f) + (g.width - (cx - cw / 2f)) * e;
            if (t >= 1f) {
                resultDone = true;
            }
        }
        float y = cy - ch / 2f;

        // 半透明灰底
        g.noStroke();
        g.fill(0f, 0f, 0f, 130f);
        g.rect(0, 0, g.width, g.height);

        // 卡片底板
        g.fill(28f, 30f, 34f, 235f);
        g.rect(x, y, cw, ch, 6f);

        // 左侧图片（边长 = 卡片高 × 0.9，垂直居中）
        float side = ch * 0.9f;
        processing.core.PImage art = g.loadImage("data/imgs/fighting/MissionComplete.png");
        if (art != null) {
            g.imageMode(PConstants.CORNER);
            g.image(art, x + (ch - side) / 2f, y + (ch - side) / 2f, side, side);
        }

        // 右侧五部分
        float rx = x + (ch - side) / 2f + side + 24f;
        float rw = x + cw - rx - 24f;
        float band = ch / 5f;
        g.stroke(255, 255, 255, 235f);
        g.strokeWeight(4f);
        g.line(rx, y + band * 0.5f, rx + rw, y + band * 0.5f);
        g.line(rx, y + ch - band * 0.5f, rx + rw, y + ch - band * 0.5f);
        g.noStroke();

        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        if (director.won()) {
            // 星级图标：1 星只画 one，2 星 one+two，3 星全部
            int stars = Math.max(1, Math.min(3, director.stars()));
            String[] icons = {"oneStar.png", "twoStar.png", "threeStar.png"};
            float iw = band * 0.85f;
            float ih = iw * 180f / 160f;
            float totalW = stars * iw + (stars - 1) * 8f;
            float sx = rx + (rw - totalW) / 2f;
            for (int i = 0; i < stars; i++) {
                processing.core.PImage star =
                        g.loadImage("data/imgs/fighting/" + icons[i]);
                if (star != null) {
                    g.imageMode(PConstants.CORNER);
                    g.image(star, sx + i * (iw + 8f), y + band * 1.0f
                            + (band - ih) / 2f - 6f, iw, ih);
                }
            }
            g.fill(0xFFB9C2CC);
            g.textSize(band * 0.20f);
            g.text(director.stars() == 3 ? "所有蓝门均未扣血" : "有蓝门被扣过血",
                    rx + rw / 2f, y + band * 2f + 8f);   // 累计下移 20
            g.fill(0xFFFFFFFF);
            g.textSize(band * 0.46f);
            g.text("Mission", rx + rw / 2f, y + band * 3f);
            g.text("Complete", rx + rw / 2f, y + band * 4f);
        } else {
            g.fill(0xFFE05A5A);
            g.textSize(band * 0.34f);
            g.text("请选择合适的编队后再来挑战吧！", rx + rw / 2f, y + band * 1.5f);
            g.fill(0xFFFFFFFF);
            g.textSize(band * 0.46f);
            g.text("Mission", rx + rw / 2f, y + band * 3f);
            g.text("Failed", rx + rw / 2f, y + band * 4f);
        }
    }

    /**
     * @param dir 朝向
     * @return 中文名（HUD 显示用）
     */
    private String facingName(com.yestercar.common.util.Director dir) {
        return switch (dir) {
            case UP -> "上";
            case DOWN -> "下";
            case LEFT -> "左";
            case RIGHT -> "右";
        };
    }

    /** 加载失败时的提示页。 */
    private void drawCenteredError() {
        g.textFont(font);
        g.textAlign(PConstants.CENTER, PConstants.CENTER);
        g.fill(UITheme.COLOR_ACCENT);
        g.textSize(UITheme.TITLE_SIZE);
        g.text("战斗运行时 · 无法开始", g.width / 2f, g.height / 2f - 50);

        g.fill(UITheme.TEXT_ON_DARK);
        g.textSize(UITheme.FONT_SIZE + 2);
        g.text(loadError, g.width / 2f, g.height / 2f);

        g.fill(UITheme.COLOR_LOW);
        g.textSize(UITheme.FONT_SIZE);
        g.text("从编辑器点「测试」会自动写好这份数据", g.width / 2f, g.height / 2f + 40);
    }
}
