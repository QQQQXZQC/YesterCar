package com.yestercar.gameopening;

import com.yestercar.common.io.Settings;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import com.yestercar.common.ui.DesignSpace;
import com.yestercar.common.ui.UiButton;
import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PFont;
import processing.core.PImage;

/**
 * 游戏开场：**单窗口三阶段**。
 *
 * <h2>为什么是一个窗口而不是三个</h2>
 * 最初的设计是「启动动画 → 用户名 → 主菜单」三个独立窗口，中间还要
 * {@code runSketch} 互切。那样有三个真问题：
 * <ol>
 *   <li>窗口会闪 —— 每切一次都重新建窗、抢前台；</li>
 *   <li>阶段之间的状态要靠静态变量传，容易漏；</li>
 *   <li>启动动画与代号输入要在<b>同一块画布</b>上衔接 ——
 *       两个窗口之间没有共同的画布可以编排。</li>
 * </ol>
 *
 * <p>所以合并成一个窗口、三个阶段（{@link Phase}）。
 * 启动动画放完直接进入代号输入 —— 同一块画布上的编排，不是两个界面在切。
 *
 * <h2>阶段</h2>
 * <table border="1">
 *   <caption>三个阶段</caption>
 *   <tr><th>阶段</th><th>内容</th><th>可否跳过</th></tr>
 *   <tr><td>{@link Phase#LOADING}</td><td>终端启动动画：YC 标记旋转入场 → 光线展开 →
 *       遮罩淡出（原主菜单的入场遮罩，2026-10 应需求移到这里）</td>
 *       <td><b>不能</b>（时长见 {@link #setLoadingMillis}）</td></tr>
 *   <tr><td>{@link Phase#NAMING}</td><td>输入玩家代号；「开始」按钮与回车都能提交</td>
 *       <td>——</td></tr>
 *   <tr><td>{@link Phase#MENU}</td><td>主菜单（{@code MainMenu}，各行错峰进场）</td>
 *       <td>——</td></tr>
 * </table>
 *
 * <h2>背景是自己画的</h2>
 * 没有可用的美术素材（ak-ui 的包体里没有资源，Defender 的那套是
 * AI 生成的衍生作品、许可不适合直接用），所以背景由程序生成：
 * 深色底 + 细网格 + 缓慢转动的准星 + 几条连接线。
 * 好处是零素材依赖，而且它天然跟着窗口尺寸走。
 */
public class GameOpeningApp extends PApplet {

    /** 目标默认窗口宽（TASKS-ROUND §0）。实际会按屏幕可用尺寸下调。 */
    public static final int WINDOW_W = 1920;   // 默认窗口宽

    /** 目标默认窗口高。 */
    public static final int WINDOW_H = 1440;   // 默认窗口高（用户指定的 1920×1440）

    /** 窗口尺寸上限（TASKS-ROUND §0）。 */
    public static final int WINDOW_MAX_W = 2560;

    /** 窗口尺寸上限。 */
    public static final int WINDOW_MAX_H = 1440;

    /**
     * 角色立绘高度占窗口高的比例 —— TASKS-ROUND-2 §1.2 定为 **0.92**。
     *
     * <p>上一轮是 0.90，与"头顶距顶 ≤8%"互斥（0.90 的高度必然让头顶落在 10%）。
     * 本轮任务单把高度改成 0.92，并不再单独断言头顶间距 ——
     * 因为间距完全由高度决定，重复断言只会再造一个矛盾。
     */
    private static final float CHARACTER_HEIGHT_FRAC = 0.92f;

    /** 屏幕可用区域里允许占用的比例（§0：×0.9）。 */
    private static final float SCREEN_USE = 0.9f;

    /** 加载阶段默认时长（毫秒）；开发者可调，见 {@link #setLoadingMillis}。 */
    private static final int DEFAULT_LOADING_MS = 2500;

    /** 进度条走满后、开始淡出前的停顿。 */
    private static final int HOLD_AFTER_FULL_MS = 300;

    /** 阶段切换的淡入淡出时长。 */
    private static final int PHASE_FADE_MS = 250;

    // ---- 启动动画时序（ak-ui 的 data-entrance="playing" 开场层） ----
    //
    // 原文：遮罩 2400ms（40% 处开始淡出、70% 处消失）、标记 1300ms、
    // 横线 900ms + 延迟 150ms，曲线都是 cubic-bezier(0.16,1,0.3,1) = Easing.TERMINAL。
    // 这套编排原在主菜单入场（2026-10 实现），应需求移到软件启动时。

    /**
     * 标记（那个「YC」）旋转压缩入场的时长（原 1300ms）。
     *
     * <p>按需求<b>加长到 2100ms</b>：原时长下 YC 一闪就过去了，
     * 看不清标识。调整这里会同时影响"YC 停留多久"与后续光线的起始时刻。
     */
    private static final int BOOT_MARK_MS = 1300;

    /** 光线展开的延迟与时长（原文 {@code 150ms} 延迟 + {@code 900ms}）。 */
    private static final int BOOT_LIGHT_DELAY_MS = 250;
    private static final int BOOT_LIGHT_MS = 1400;

    /** 遮罩开始淡出（2400ms 的 40% 关键帧）。 */
    private static final int BOOT_VEIL_FADE_FROM_MS = 1600;

    /** 遮罩完全消失（2400ms 的 70% 关键帧），此后整层不再绘制。 */
    private static final int BOOT_VEIL_GONE_MS = 1680;

    /** 加载阶段的**最短**时长：遮罩放完 + 停顿（loadingMillis 小于它时以它为准）。 */
    private static final int BOOT_MIN_MS = BOOT_VEIL_GONE_MS + HOLD_AFTER_FULL_MS;

    /**
     * 本阶段：加载中。
     */
    public enum Phase {
        /** 进度条推进；不可跳过。 */
        LOADING,
        /** 输入玩家代号。 */
        NAMING,
        /** 主菜单。 */
        MENU,
        /** 战斗（**同一个窗口内**，不再新开窗口）。 */
        BATTLE
    }

    private static int loadingMillis = DEFAULT_LOADING_MS;

    /** 退出作战的黑屏起始时刻（毫秒）；{@code -1} = 没在退出。 */
    private long battleExitAt = -1;

    /** 本局战斗是从官方列表还是自定义列表进来的。 */
    private boolean battleFromOfficial = true;

    /** 调试：{@code --play} 直接进战斗的战役名。 */
    private static String bootBattle;


    /** 调试直开战斗是否已经触发过（只触发一次）。 */
    private boolean bootBattleStarted;

    /** 调试：直接进战斗（{@code --play <战役>} 用）。 */
    public static void skipToBattle(String campaign) {
        bootBattle = campaign;
    }

    /** 供测试与调试预填代号。 */
    private static String presetUsername;

    /**
     * 是否跳过加载与代号阶段、直接进主菜单（命令行 {@code --menu}）。
     *
     * <p>给调试与自动截图用：那两个阶段各有固定的等待时间，
     * 反复测主菜单时每次都要白等几秒，而且自动化里靠"点窗口 + 发回车"
     * 推进阶段并不可靠（实测踩过）。这是**开发入口**，不影响正常启动。
     */
    private static boolean skipToMenu;

    /**
     * 自动截图路径（命令行 {@code --shot <输出文件>}，配合 {@code --menu} 用）。
     *
     * <h2>为什么走这条路，而不是抓窗口</h2>
     * 外部截图（AWT {@code Robot}）在这台机器上不可靠：窗口会被别的窗口遮挡，
     * 显示器 DPI 缩放还会让 {@code GetClientRect} 报出与画布尺寸不一致的值
     * （实测报 853×480，而画布是 1280×720）。**Processing 的 saveFrame()
     * 直接写画布缓冲区**，既不受遮挡影响，也不会被 DPI 缩放。
     *
     * <p>它同时是个"视觉回归"入口：给定参数就能稳定产出一张固定尺寸的图。
     */
    private static String shotPath;

    /** ak-ui 美术资源（决定书 D7）。缺任何一张都只跳过绘制，不报错。 */
    private PImage bgImage;
    private PImage characterImage;
    private PImage paperTexture;

    /**
     * 按当前窗口尺寸**预缩放**过的背景与立绘。
     *
     * <h2>为什么必须预缩放</h2>
     * JAVA2D 是纯 CPU 渲染：每帧把 1920×1080 的背景缩放到窗口尺寸、
     * 再把立绘缩放一次，两次大图双线性采样就能吃掉一帧的一半时间。
     * 实测未优化时 **10.2 fps**。
     *
     * <p>改成窗口尺寸变化时缩放一次、之后 1:1 贴图，采样成本降到 0。
     */
    /** 背景（cover 缩放 + 压暗）。预缩放缓存，避免每帧重新缩放源图。 */
    private PImage bgRawScaled;

    /** 角色立绘（预缩放）。 */
    private PImage charScaled;


    /** 预缩放图对应的窗口尺寸，用来判断是否需要重算。 */
    private int scaledForW = -1;
    private int scaledForH = -1;

    /**
     * 资源就绪门闩（决定书 D16 / L3）。
     *
     * <p>字体注册与图片解码完成之前<b>不得</b>启动入场动画 ——
     * 否则冷启动的第一帧就是"动画已经跑了一半"的状态。
     * 重播走 {@link #replayLoadingPhase()}，与首次启动同一个入口，因此帧序列一致。
     */
    private boolean assetsReady;

    /** §6 验收只跑一次 —— 它每次要现场建 4 个离屏画布，每帧跑会拖死进程。 */
    private boolean acceptanceDone;

    /** 帧率只打一次。 */
    private boolean fpsLogged;

    // ---- TASKS-ROUND-3 §2.1：脏标记 + noLoop/redraw ----
    //
    // JAVA2D 没有 GPU 加速，持续 60fps 重绘是纯浪费：主菜单静止时画面根本不变。
    // 所以进主菜单后停掉循环，只在**真的变了**的时候重画一次。

    /** 主菜单是否已经进入"按需重绘"模式。 */
    private boolean menuIdleMode;

    /** 上一秒（时钟整秒比较用）。 */
    private int lastClockSecond = -1;

    /** 上一次收到鼠标移动的时间，用来做节流。 */
    private int lastMotionMs = -1;

    /** 鼠标移动的节流间隔：60ms ≈ 16fps 的响度，足够跟手。 */
    private static final int MOTION_THROTTLE_MS = 60;

    // ⚠️ 这里曾有两个常量 IDLE_FPS / ACTIVE_FPS，用于在运行时用 frameRate() 调速。
    //    在 P2D 上是致命的：frameRate() 会改 GL 上下文的交换间隔，
    //    必须在"上下文当前"的渲染线程上调用。已全部删除 —— 只保留 setup 里
    //    那一次 frameRate(60)（那时渲染线程还没跑起来，安全）。

    /** 本次静止期一共画了几帧（验收用：静止 10 秒应当不再增长）。 */
    private long idleFrameCount;

    // ==================================================================
    // ★ 音频：改路径就改这里 ★
    // ==================================================================

    /**
     * 主界面**背景音乐**（循环播放）。
     *
     * <p>换曲子：改这一行 + 把新的 WAV 放进 {@code data/music/}。
     * 不能直接放 MP3 —— Java 解不了（见 {@code data/music/README.md}）。
     */
    static final String BGM_PATH = "data/music/fudahuang.wav";

    /** 背景音乐音量（分贝）。0 = 原始；约 -10 相当于全音量 1/3。 */
    private static final float BGM_VOLUME_DB = -10f;

    /**
     * 助理**问候语音**（每次进主界面播一次，**不循环**）。
     *
     * <p>换语音：改这一行。素材要求 PCM WAV（16 bit 即可），
     * 用 {@code ffprobe} 看不是 PCM 的话先转一次。
     */
    static final String VOICE_PATH = "data/music/hello.wav";

    /**
     * 战斗配乐路径（**还没有曲目**）。
     *
     * <p>放一首进去就会自动循环播放；缺失时静音，不伪造。
     */
    static final String BATTLE_BGM_PATH = "data/music/battle.wav";

    /** 结算音乐（循环）。 */
    static final String FIGHTEND_BGM_PATH = "data/music/Fightend.wav";

    /** 战役界面（编队/关卡选择）的背景音乐。 */
    static final String SQUAD_BGM_PATH = "data/music/Broken_Sun.wav";

    /**
     * 问候语音音量（分贝）。
     *
     * <p>比背景音乐高 —— 语音是"内容"，音乐是"垫底"。0 = 原始音量。
     * 如果觉得盖过音乐太多就往下调（-3、-6…）。
     */
    private static final float VOICE_VOLUME_DB = 0f;

    /** 诊断：是否已经重开过一局。 */
    private boolean relaunched;

    /**
     * 战斗屏（画在主窗口里）。
     *
     * <p>第 18 轮之前是 {@code PApplet.runSketch} 开**第二个窗口** ——
     * 于是主菜单仍在后台运行、背景音乐还在放，玩家看到两个窗。
     * 现在战斗是主窗口的一个阶段。
     */
    private com.yestercar.runtime.BattleScreen battleScreen;

    /**
     * 战斗配乐（循环）。
     *
     * <p>目前**没有战斗曲目**，{@link #BATTLE_BGM_PATH} 指向的文件不存在，
     * {@code AudioTrack} 会打一行警告并静音 —— 这是有意的：
     * 不拿主菜单曲冒充战斗曲。将来把文件放进去就自动生效。
     */
    private final AudioTrack battleBgm = new AudioTrack(BATTLE_BGM_PATH, -10f, true);

    /** MS-1..4 与自定义关卡。 */
    private final AudioTrack battle1Bgm = new AudioTrack("data/music/Battle1.wav", -10f, true);
    /** MS-6 / MS-7。 */
    private final AudioTrack battle2Bgm = new AudioTrack("data/music/Battle2.wav", -10f, true);
    /** MS-5（Boss）。 */
    private final AudioTrack boss1Bgm = new AudioTrack("data/music/Boss1.wav", -10f, true);
    /** MS-8（Boss）。 */
    private final AudioTrack boss2Bgm = new AudioTrack("data/music/Boss2.wav", -10f, true);

    /** 结算音乐（循环），退出结算时停止。 */
    private final AudioTrack fightendBgm = new AudioTrack(FIGHTEND_BGM_PATH, -10f, true);

    /** 战役界面配乐（循环）。 */
    private final AudioTrack squadBgm = new AudioTrack(SQUAD_BGM_PATH, -12f, true);

    /** 主界面背景音乐（循环）。 */
    private final AudioTrack bgm = new AudioTrack(BGM_PATH, BGM_VOLUME_DB, true);

    /** 助理问候语音（单次）。 */
    private final AudioTrack voice = new AudioTrack(VOICE_PATH, VOICE_VOLUME_DB, false);

    /** 窗口在屏幕上的位置（收字窗口要靠它换算屏幕坐标）。 */
    private int winX;
    private int winY;

    /**
     * 空闲期的唤醒定时器（§2.1）。
     *
     * <p>⚠️ 为什么必须用外部定时器：`noLoop()` 之后 `draw()` 不再被调用，
     * 所以"在 draw 里检查秒数然后 redraw"是**死循环逻辑** ——
     * 第一次停循环之后就再也没机会唤醒。时钟要动，必须由别的线程踢一脚。
     */
    private java.util.Timer idleTimer;

    /** 上一帧的窗口尺寸 —— 用来检测缩放并重建设计坐标。 */
    private int lastW = -1;
    private int lastH = -1;

    /** settings() 里算出的初始窗口尺寸，setup() 用它居中。 */
    private int startW = WINDOW_W;
    private int startH = WINDOW_H;

    /** 截图前的等待毫秒 —— 要够入场动画跑完。 */
    private static int shotDelayMs = 2600;

    /**
     * 截图要等哪个阶段（{@code --shot-phase}）。
     *
     * <p>默认 {@code MENU}。加这个开关是因为"按钮跑位"这类 bug 只在
     * 命名阶段可见，而原来的 {@code --shot} 只认主菜单 —— 不打开这条缝，
     * 就只能靠肉眼盯屏幕。
     */
    private static String shotPhase = "MENU";

    private Phase phase = Phase.LOADING;
    private int phaseStartedAt;
    private PFont font;
    private DesignSpace design;

    /** 玩家代号（原始输入，提交时才清洗）。 */
    private final StringBuilder nameInput = new StringBuilder();
    private boolean caretOn = true;

    private UiButton startButton;

    /** 主菜单；进入 {@link Phase#MENU} 时才创建。 */
    private MainMenu menu;

    /** 三个字体角色（命令用衬线、数值用等宽、正文无衬线）。 */
    private Fonts fonts3;

    /**
     * 设置加载阶段时长（毫秒）。
     *
     * <p>开发者可调 —— Q22 的决定是"加载阶段不可跳过，但时长可配"。
     * 加载阶段现在是 ak-ui 式终端启动动画（约 2 秒），实际时长取
     * {@code max(设定值, 启动动画最短时长)}。
     *
     * @param millis 时长；小于 0 会被夹到 0
     */
    public static void setLoadingMillis(int millis) {
        loadingMillis = Math.max(0, millis);
    }

    /**
     * 预填一个玩家代号（测试/调试用）。
     *
     * @param name 代号；{@code null} 表示用记忆里的那个
     */
    public static void presetUsername(String name) {
        presetUsername = name;
    }

    /**
     * 跳过加载与代号，直接进主菜单（测试/调试用）。
     *
     * <p>对应命令行参数 {@code --menu}。它会**同时**把加载时长压成 0 ——
     * 否则"跳过"只是把等待挪到了别处。
     */
    public static void skipToMenu() {
        skipToMenu = true;
        loadingMillis = 0;
    }

    /**
     * 重播加载阶段 —— 「重播入场」按钮的落点（决定书 D11）。
     *
     * <p>回到 {@link Phase#LOADING} 并把阶段时钟清零，让开场动画重放一遍。
     * 之后会正常走 {@code LOADING → NAMING → MENU}：代号已经记住，
     * 输入框会预填，用户按回车即可回到主菜单。
     */
    /**
     * 开始战斗 —— **在主窗口内**切到 {@link Phase#BATTLE}。
     *
     * <h2>为什么不新开窗口</h2>
     * 第 18 轮之前这里用 {@code PApplet.runSketch} 开第二个窗口，
     * 结果主菜单仍在后台运行、背景音乐还在放，玩家看到的是两个窗。
     * 战斗本来就该是"同一个游戏里的一个阶段"，所以现在是同一个窗口换内容。
     *
     * @param campaignName 战役目录名
     * @param squad        编队（干员 id），可为空
     */
    public void launchBattle(String campaignName, java.util.List<String> squad) {
        launchBattle(campaignName, squad, true);
    }

    /**
     * 开始战斗（带"来自哪张列表"的信息）。
     *
     * @param campaignName 战役目录名
     * @param squad        编队
     * @param official     是否官方战役（决定打完回哪张列表）
     */
    public void launchBattle(String campaignName, java.util.List<String> squad,
                             boolean official) {
        battleFromOfficial = official;
        if (campaignName == null || campaignName.isEmpty()) {
            System.out.println("[GameOpening] 没有选中的战役，取消开战");
            return;
        }
        System.out.println("[GameOpening] 进入战斗: " + campaignName
                + "，编队 " + (squad == null ? "空" : squad.toString()));
        battleScreen = new com.yestercar.runtime.BattleScreen(this, campaignName, squad);
        phase = Phase.BATTLE;
        phaseStartedAt = millis();
        // 主菜单音乐停、战斗音乐起（暂无战斗曲 → 静音，不留假音乐）
        bgm.stop();
        voice.stop();
        squadBgm.stop();
        // 每关不同的战斗曲（MS-5 / MS-8 用 Boss 曲）
        stopAllBattleMusic();
        battleMusicFor(campaignName).restart();
        noLoop();
        loop();          // 保证战斗阶段是连续绘制的
    }

    /** 停掉全部战斗曲。 */
    private void stopAllBattleMusic() {
        battleBgm.stop();
        battle1Bgm.stop();
        battle2Bgm.stop();
        boss1Bgm.stop();
        boss2Bgm.stop();
    }

    /**
     * 按战役挑战斗曲。
     *
     * @param campaignId 战役 id（如 {@code official_05}）
     * @return 对应的音乐轨
     */
    private AudioTrack battleMusicFor(String campaignId) {
        String id = campaignId == null ? "" : campaignId;
        if (id.endsWith("official_05")) {
            return boss1Bgm;
        }
        if (id.endsWith("official_08")) {
            return boss2Bgm;
        }
        if (id.endsWith("official_06") || id.endsWith("official_07")) {
            return battle2Bgm;
        }
        return battle1Bgm;
    }

    /**
     * 从战斗回到主菜单（同一个窗口）。
     */
    private void leaveBattle() {
        System.out.println("[GameOpening] 从战斗返回" + (battleFromOfficial ? "官方" : "自定义") + "战役列表");
        battleScreen = null;
        battleBgm.stop();
        // 回的是**战役列表**（不是主菜单），所以接着放 Broken_Sun；
        // openList() 会走 goScreen → enterCampaignScreen 把曲子接上。
        bgm.stop();
        // 回到对应那张战役列表，而不是主菜单根
        stopAllBattleMusic();
        fightendBgm.stop();
        if (menu != null) {
            menu.openList(battleFromOfficial);
        }
        // 回到列表后按来源从头播：官方 → Broken_Sun，自定义 → fudahuang
        switchCampaignMusic(battleFromOfficial, true);
        phase = Phase.MENU;
        phaseStartedAt = millis();
        requestRedraw();
    }

    /**
     * 进入战役界面（编队/关卡选择）：换背景音乐。
     */
    public void enterCampaignScreen() {
        bgm.stop();
        voice.stop();
        squadBgm.play();
    }

    /**
     * 离开战役界面回到主菜单：恢复主菜单音乐。
     */
    /**
     * 切换战役音乐：官方链路用 Broken_Sun，自定义链路用 fudahuang。
     *
     * <p>⚠️ 两首都要先 stop 再放目标曲 —— 否则会出现"两首歌同时播"。
     *
     * @param official {@code true} = 官方（Broken_Sun）
     * @param restart  {@code true} = 从头放
     */
    /**
     * 设置整体音量（0–1），对所有音乐轨立即生效。
     *
     * @param v 0–1
     */
    public void setMasterVolume(float v) {
        AudioTrack.setMasterGain(v);
        bgm.refreshVolume();
        voice.refreshVolume();
        squadBgm.refreshVolume();
        battleBgm.refreshVolume();
        System.out.println("[音量] 整体音量 = " + Math.round(v * 100) + "%");
    }

    /** @return 当前整体音量（0–1） */
    public float masterVolume() {
        return AudioTrack.masterGain();
    }

    public void switchCampaignMusic(boolean official, boolean restart) {
        // 已经是目标曲、又不要求从头 → 直接返回。
        // 之前这里无条件 stop+play，导致"主菜单 → 自定义关卡"听起来像重新开始。
        boolean playing = official ? squadBgm.isPlaying() : bgm.isPlaying();
        if (!restart && playing) {
            return;
        }
        squadBgm.stop();
        bgm.stop();
        if (official) {
            if (restart) {
                squadBgm.restart();
            } else {
                squadBgm.play();
            }
        } else if (restart) {
            bgm.restart();
        } else {
            bgm.play();
        }
    }

    /**
     * 从主菜单重回战役界面：Broken_Sun **从头**播（不是接着上次中断处）。
     */
    public void restartCampaignScreen() {
        bgm.stop();
        voice.stop();
        squadBgm.stop();
        squadBgm.restart();
    }

    public void leaveCampaignScreen() {
        squadBgm.stop();
        bgm.restart();
    }

    public void replayLoadingPhase() {
        loadingMillis = 0;
        enterPhase(Phase.LOADING);
    }

    /**
     * 设置自动截图（测试/调试用）。
     *
     * @param path    输出文件路径；{@code null} 或空表示不截图
     * @param delayMs 从进主菜单起等多少毫秒再截（要给入场动画留时间）
     */
    public static void autoShot(String path, int delayMs) {
        shotPath = path;
        shotDelayMs = Math.max(0, delayMs);
    }

    /**
     * 指定截图要等的阶段。
     *
     * @param phase {@code LOADING} / {@code NAMING} / {@code MENU}；null 或未知保持 MENU
     */
    public static void autoShotPhase(String phase) {
        if (phase == null) {
            return;
        }
        String up = phase.trim().toUpperCase(java.util.Locale.ROOT);
        if (up.equals("LOADING") || up.equals("NAMING") || up.equals("MENU")) {
            shotPhase = up;
        }
    }

    /** 最小窗口尺寸（TASKS-ROUND-2 §3.5）。 */
    private static final int MIN_W = 1280;

    /** 最小窗口尺寸。 */
    private static final int MIN_H = 720;

    /**
     * 窗口与屏幕边缘之间至少留这么多像素（§3.2 的"边距"）。
     *
     * <p>从 48 收到 <b>16</b>：48 时在 1707×1019 的屏幕上只能开 1659×933，
     * 比屏幕小一圈。收到 16 后能开到 1691×951，更接近满屏，
     * 同时仍留出窗口边框/标题栏的余量，保证四条边都不出屏。
     */
    private static final int SCREEN_MARGIN = 16;

    /**
     * 算出这次该用多大的窗口（TASKS-ROUND §0 + TASKS-ROUND-2 §3）。
     *
     * <h2>规则</h2>
     * <ol>
     *   <li><b>不得有任一边超出屏幕</b>：可用区先减去 {@value #SCREEN_MARGIN}px 边距；</li>
     *   <li>上限 = {@code min(2560×1440, 可用区 − 边距)}，按 16:9 <b>向下取整</b>；</li>
     *   <li>目标 = 1920×1080（或上次记住的尺寸），受上限约束；</li>
     *   <li>不小于 {@code 1280×720}（§3.5）。</li>
     * </ol>
     *
     * <h2>为什么必须向下取整到 16:9</h2>
     * 16:9 是保证 {@code sx == sy} 的前提。差一点，文字就又被非等比拉伸 ——
     * 那正是第 2 轮修掉的病。
     *
     * @return {@code {宽, 高}}
     */
    static int[] resolveWindowSize() {
        Rectangle avail = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();

        // ① 可用区减边距 —— 这一条直接对应 §3.2 的"不得有任一边超出屏幕"
        int roomW = Math.max(MIN_W, avail.width - SCREEN_MARGIN);
        int roomH = Math.max(MIN_H, avail.height - SCREEN_MARGIN);

        // ② 上限，且按 16:9 向下取整
        int maxW = Math.min(WINDOW_MAX_W, roomW);
        int maxH = Math.min(WINDOW_MAX_H, roomH);
        // 不再强制 16:9：用户要求默认 1920×1600（5:4 附近），
        // 只保证不超出可用区。

        // ③ 目标：记住的尺寸优先，其次默认
        // 只认用户**主动调整过**的尺寸；早期默认值（1680×945 之类）不再沿用，
        // 否则改了 WINDOW_W/H 也没用 —— 旧尺寸被记在 settings 里优先命中。
        int[] remembered = Settings.inWorkspace().getWindowSize();
        boolean userChose = remembered[0] > 0
                && remembered[0] != 1680 && remembered[1] != 945;
        // 默认"最大化"：直接占满系统可用区（P2D 窗口是 NEWT 的，没有 Java 侧的
        // setExtendedState 可用，所以用"铺满可用区"来达到最大化效果）。
        // 需要固定尺寸时传 -Dyestercar.ui.nomax=1。
        boolean wantMax = !"1".equals(System.getProperty("yestercar.ui.nomax"));
        int wantW = wantMax ? roomW : (userChose ? remembered[0] : WINDOW_W);

        // ④ 取 min，对齐 16:9，并保证不低于最小尺寸
        int wantH = wantMax ? roomH
                : (userChose && remembered.length > 1 ? remembered[1] : WINDOW_H);
        int w = Math.min(Math.max(wantW, MIN_W), maxW);
        int h = Math.min(Math.max(wantH, MIN_H), maxH);
        // ⑤ 用户指定 1920×1600 后不再强求 16:9 —— 直接把夹取结果返回。
        //    等比由 DesignSpace 自己按 sx/sy 处理，不再依赖窗口是 16:9。
        // 旧的 16:9 精确对齐（w×9 能被 16 整除）已废弃：
        // 用户指定 1920×1600 后不再要求窗口是 16:9，等比由 DesignSpace 处理。
        return new int[]{w, h};
    }

    // ------------------------------------------------------------------
    // 中文输入：独立的 Swing 文本组件（不依赖渲染器）
    // ------------------------------------------------------------------

    /*
     * 为什么用 Swing 而不是给画布挂 InputMethodListener：
     *
     * P2D 的 native 是 JOGL 的 NEWT GLWindow，它不是 AWT 组件
     * （实测 surface.getComponent() 返回 null、GLWindow 不是 java.awt.Component），
     * 所以**根本没有地方挂输入法事件**。
     *
     * 但输入法需要的只是一个"能被 IME 识别的文本组件"，
     * 跟渲染器无关。所以在屏幕外放一个 1×1 的 JWindow + JTextField 专门收字，
     * 画布照常一帧帧画。候选窗由系统弹出，位置跟着那个组件的焦点走。
     */

    /** 收字用的窗口（1×1，移到屏幕外，不参与显示）。 */
    private java.awt.Window imeWindow;

    /** 收字用的文本框 —— 它是 NAMING 阶段唯一的输入源。 */
    private javax.swing.JTextField imeField;

    /** 诊断用的自动输入是否已经做过一次。 */
    private boolean autoTyped;

    /** 诊断：战斗窗口是否已试开过。 */
    private boolean battleProbed;

    /** 诊断用的重播是否已触发。 */
    private boolean replayFired;

    /** 诊断用的自动删除是否已经做过一次。 */
    private boolean autoDeleted;

    /** 诊断用的失焦/恢复测试阶段。 */
    private int reactivateStage;

    /**
     * 鼠标是否处于按下状态。
     *
     * <p>拖窗口/拖选时不要抢焦点，否则会打断系统的拖拽循环（窗口拖不动）。
     */
    private boolean pointerDown;

    /** 按下时的鼠标位置，用来区分"点击"与"拖拽"。 */
    private int pressX;
    private int pressY;

    /** 焦点诊断日志的节流。 */
    private int lastFocusLogMs;

    /**
     * 建立收字用的 Swing 组件（只建一次）。
     *
     * <p>规格要求 2.5：代号由画布绘制，Swing 组件只收字 ——
     * 所以窗口被移到屏幕外、尺寸 1×1，视觉上完全不存在。
     */
    private void installInputMethod() {
        try {
            imeField = new javax.swing.JTextField();

            // 长度与字符校验交给 NameFieldFilter（独立类，有单元测试覆盖）。
            // ⚠️ 绝不能在 DocumentListener 里回写 setText —— 那会抛
            //    IllegalStateException: Attempt to mutate in notification，
            //    导致"打了字不显示 / 删除回滚 / 无限增长"三个症状。
            if (imeField.getDocument() instanceof javax.swing.text.AbstractDocument doc) {
                doc.setDocumentFilter(new NameFieldFilter());
            }

            imeField.setOpaque(false);
            imeField.setBorder(null);
            imeField.setFocusTraversalKeysEnabled(false);

            // ⚠️ 先销毁可能残留的旧窗：installInputMethod 若被调用多次，
            //    旧窗没人引用却仍然在屏幕上吃鼠标事件（"幽灵窗口"）。
            if (imeWindow != null) {
                imeWindow.dispose();
                imeWindow = null;
            }
            javax.swing.JDialog dlg = new javax.swing.JDialog();
            dlg.setUndecorated(true);
            dlg.setModal(false);
            imeWindow = dlg;
            imeWindow.setFocusableWindowState(true);
            imeWindow.add(imeField);
            imeWindow.pack();

            // 只读镜像：这里**只读**文本框，绝不回写（回写就会撞上上面的异常）
            imeField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    onFieldChanged();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    onFieldChanged();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    onFieldChanged();
                }
            });

            // 回车 = 提交（此时键盘焦点在文本框里，画布收不到 keyPressed）
            imeField.addActionListener(e -> {
                if (phase == Phase.NAMING) {
                    submitName();
                }
            });

            System.out.println("[IME] 已建立 Swing 收字组件（JDialog + JTextField + DocumentFilter）");
        } catch (Exception ex) {
            imeWindow = null;
            imeField = null;
            System.out.println("[IME] 建立 Swing 收字组件失败，降级为 ASCII 输入：" + ex);
        }
    }

    /**
     * 只读镜像：把文本框内容同步到代号。
     *
     * <p>⚠️ 这里**只能读，不能写**。上一版在这里调用 {@code setText()} 回写清洗结果，
     * Swing 抛 {@code IllegalStateException: Attempt to mutate in notification}，
     * 于是同步被中断 —— 画布拿不到字、删除被回滚、上限失效。清洗与截断已移到
     * {@code DocumentFilter}，这里只做拷贝。
     */
    private void onFieldChanged() {
        if (phase != Phase.NAMING || imeField == null) {
            return;
        }
        String text = imeField.getText();
        nameInput.setLength(0);
        nameInput.append(text);
    }

    /**
     * 把收字窗口摆到画布输入栏的位置（屏幕坐标）。
     *
     * <p>为什么要摆到可见区而不是丢到屏幕外：部分 Windows 环境下，
     * 完全在屏幕外、尺寸 1×1 的窗口**拿不到输入焦点**，输入法也就不弹候选窗。
     * 现在它盖在输入栏上，但透明度设到 0.01（肉眼不可见），
     * 既能稳定获得焦点，候选窗也会出现在正确位置。
     */
    private void placeInputWindow() {
        if (imeWindow == null) {
            return;
        }
        try {
            int barY = Math.round(progressBarBox()[1]);
            int w = Math.min(320, Math.round(design.wFraction(0.42f)));
            // ⚠️ 高度只能覆盖文字行。之前给到 40，窗口下沿盖到了「开始」按钮，
            //    鼠标移过去变成文本光标、点击被输入框吃掉。
            int h = Math.max(24, Math.round(design.h(28)));
            int nx = winX + (width - w) / 2;
            // 放到文字行**上方**：它的下沿绝不能碰到「开始」按钮，
            // 否则鼠标移过去会变成文本光标、点击被输入框吃掉。
            int ny = winY + barY - h - 12;
            if (imeWindow.getWidth() != w || imeWindow.getHeight() != h) {
                imeWindow.setSize(w, h);
            }
            if (imeWindow.getX() != nx || imeWindow.getY() != ny) {
                imeWindow.setLocation(nx, ny);
            }
            // 近透明：肉眼看不见，但仍是真实在屏幕上的窗口 —— 能拿焦点、能被输入法挂上。
            // 0.01 而不是 0：完全 0 透明度的分层窗口在某些驱动上会被跳过命中测试。
            float op = Float.parseFloat(System.getProperty("yestercar.ui.opacity", "0.01"));
            imeWindow.setOpacity(op);
            imeWindow.setCursor(java.awt.Cursor.getDefaultCursor());
        } catch (Exception ex) {
            // 某些环境不支持窗口透明度 —— 退回"屏幕外 1×1"，至少不挡画面
            imeWindow.setSize(1, 1);
            imeWindow.setLocation(-200, -200);
        }
    }

    /**
     * 进入某个阶段时切换收字组件的可见性（规格 2.2）。
     *
     * @param naming 是否处于「输入代号」阶段
     */
    private void setInputActive(boolean naming) {
        if (imeWindow == null || imeField == null) {
            return;
        }
        if (naming) {
            imeField.setText(nameInput.toString());
            // ⚠️ 预填之后必须把光标移到**末尾**。
            //    否则光标停在位置 0，Backspace 在开头什么也删不掉 ——
            //    用户会以为"删除失效"，必须先按方向键把光标挪走才行（实测报过）。
            imeField.setCaretPosition(imeField.getText().length());
            // ⚠️ 顺序：先 setVisible 再 setOpacity —— 窗口不可显示时 setOpacity 会抛
            //    IllegalComponentStateException，落到 catch 分支就白白退回屏幕外 1×1。
            imeWindow.setVisible(true);
            placeInputWindow();
            activateInputWindow();
        } else {
            imeWindow.setVisible(false);
        }
    }

    /**
     * 规格 2.6：焦点跑回画布时把焦点还给它。
     *
     * <p>点一下画布，NEWT 窗口会抢走键盘焦点，之后就打不了字。
     * 每帧检查一次比去挂 NEWT 的焦点回调简单也够稳。
     */
    /**
     * 诊断用：把 {@code -Dyestercar.ui.autotype=<文本>} 注入收字框。
     *
     * <p>为什么要它：中文输入法没法程序化驱动，但"输入 → 画布显示"这条链路可以。
     * 有了它，"打了字却显示默认博士"这种 bug 就能用截图证明修好了，
     * 不必靠肉眼。
     */
    private void autoTypeOnce() {
        if (autoTyped || imeField == null) {
            return;
        }
        String text = System.getProperty("yestercar.ui.autotype");
        if (text == null) {
            return;
        }
        if (millis() - phaseStartedAt < 1200) {
            return;
        }
        autoTyped = true;
        imeField.setText(text);
        if (System.getProperty("yestercar.ui.autosubmit") != null) {
            // 诊断用：等一下让 DocumentListener 同步完，再走一次"提交"
            new java.util.Timer(true).schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        System.out.printf("[诊断] 提交前代号=\"%s\"%n", nameInput);
                        submitName();
                    });
                }
            }, 400L);
        }
        System.out.printf("[诊断] 已注入输入 \"%s\" → 收字框=\"%s\"，代号=\"%s\"%n",
                text, imeField.getText(), nameInput);
    }

    /**
     * 让收字窗口真正**成为活动窗口**并把焦点给文本框。
     *
     * <h2>⚠️ 两个实测教训（都踩过）</h2>
     * <ol>
     *   <li>{@code requestFocusInWindow()} 只对**已经是活动窗口**的窗口有效。
     *       只调它，实测 {@code isFocused()} 一直是 false ——
     *       窗口可见但从未激活，输入法自然挂不上。</li>
     *   <li><b>用 {@code JWindow} 怎么都激活不了</b>：{@code toFront()} + {@code requestFocus()}
     *       + 在 EDT 上重试 + 真实鼠标点击，四种方式实测
     *       {@code KeyboardFocusManager.getActiveWindow()} 全是 {@code null}
     *       （因为前台被 NEWT 的窗口占着，AWT 侧没有活动窗口）。
     *       换成 {@code JDialog} 立刻生效：{@code 已激活=true 当前活动窗口=JDialog}。</li>
     * </ol>
     */
    private void activateInputWindow() {
        if (imeWindow == null || imeField == null) {
            return;
        }
        imeWindow.setAlwaysOnTop(true);
        imeWindow.toFront();
        imeWindow.requestFocus();
        imeField.requestFocus();
        // 变体：EDT 上再来一次（激活往往需要事件循环转过一圈）
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                imeWindow.setAutoRequestFocus(true);
                imeWindow.toFront();
                imeWindow.requestFocus();
                imeField.requestFocusInWindow();
            } catch (Exception ignored) {
                // 忽略：诊断阶段
            }
        });
    }

    /**
     * 诊断用：模拟"直接按 Backspace"。
     *
     * <p>复现用户报的场景：预填代号后不先按方向键，直接删 ——
     * 判断光标是否在末尾（在开头的话 Backspace 什么也删不掉）。
     */
    private void autoDeleteOnce() {
        if (autoDeleted || imeField == null) {
            return;
        }
        if (System.getProperty("yestercar.ui.deleteafter") == null) {
            return;
        }
        if (millis() - phaseStartedAt < 1200) {
            return;
        }
        autoDeleted = true;
        String before = imeField.getText();
        int caret = imeField.getCaretPosition();
        // 用 JTextField 自己注册的 Backspace 动作（DefaultEditorKit.deletePrevCharAction），
        // 这样走的是和真实按键**完全同一条**路径。
        javax.swing.Action del = imeField.getActionMap().get(
                javax.swing.text.DefaultEditorKit.deletePrevCharAction);
        if (del != null) {
            del.actionPerformed(new java.awt.event.ActionEvent(imeField,
                    java.awt.event.ActionEvent.ACTION_PERFORMED, "delete"));
        }
        String afterDel = imeField.getText();
        // 再输入一个字符，验证"删掉的字不会复活"
        appendTyped("新");
        System.out.printf("[诊断] \"%s\"(光标%d) → 删除后=\"%s\" → 再输入后=\"%s\" 代号=\"%s\"%n",
                before, caret, afterDel, imeField.getText(), nameInput);
    }

    /**
     * 诊断用：制造一次"收字窗失焦"，再走 keyPressed 里那条恢复路径，
     * 看焦点能不能抢回来。用来验证"拖完窗口后中文还能不能打"。
     */
    private void autoReactivateTest() {
        String at = System.getProperty("yestercar.ui.reactivatetest");
        if (at == null || imeWindow == null) {
            return;
        }
        int ms = Integer.parseInt(at);
        if (reactivateStage == 0 && millis() - phaseStartedAt > ms) {
            reactivateStage = 1;
            imeWindow.setVisible(false);       // 强制失焦
            imeWindow.setVisible(true);
            System.out.printf("[诊断] 已强制失焦：文本框焦点=%s%n",
                    imeField.isFocusOwner());
        } else if (reactivateStage == 1 && millis() - phaseStartedAt > ms + 800) {
            reactivateStage = 2;
            activateInputWindow();             // 与 keyPressed 完全同一条恢复路径
            javax.swing.SwingUtilities.invokeLater(() ->
                    System.out.printf("[诊断] 恢复后：已激活=%s 文本框焦点=%s%n",
                            imeWindow.isFocused(), imeField.isFocusOwner()));
        }
    }

    private void keepInputFocus() {
        // ⚠️ 这里**故意什么都不做**（只保留这个方法以便将来扩展）。
        //
        // 上一版在这里每帧调 activateInputWindow()（toFront + requestFocus）。
        // 后果：**主窗口拖不动**。因为按窗口标题栏拖动时，Processing 的
        // mousePressed 根本不会触发（那是画布之外），于是 pointerDown 一直是 false，
        // 我们每帧都把激活状态从主窗口抢回收字窗 —— 系统的拖拽循环当场被打断。
        //
        // 现在焦点只在两个明确的时机抢：进入 NAMING 时、以及在画布上松开鼠标时。
    }

    /**
     * 往代号里追加一批字符（中文合成结果与 ASCII 键都走这里）。
     *
     * <p>⚠️ 上限按**清洗后**的长度算，否则用户能靠敲空格把真实名字挤没。
     *
     * @param text 要追加的文本
     */
    private void appendTyped(String text) {
        if (phase != Phase.NAMING || text == null || text.isEmpty()) {
            return;
        }
        // 收字组件在场时，**它是唯一数据源**：
        // 往文本框里追加，由 DocumentListener 同步回 nameInput。
        // 否则两条写入路径会互相覆盖（画布上敲的字符会被下一次
        // DocumentListener 用旧文本盖掉）。
        if (imeField != null && imeWindow != null && imeWindow.isVisible()) {
            // 在光标处插入，而不是 setText(旧全文 + 新字符)：
            // 整篇重写会覆盖掉用户已经删除的内容（并破坏输入法合成区）。
            try {
                imeField.getDocument().insertString(
                        imeField.getCaretPosition(), text, null);
            } catch (javax.swing.text.BadLocationException ex) {
                imeField.setText(imeField.getText() + text);
            }
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) {
                continue;
            }
            String next = nameInput.toString() + c;
            if (Username.sanitize(next).length() > Username.MAX_LENGTH) {
                return;
            }
            nameInput.append(c);
        }
    }

    /**
     * 把窗口摆到**可用屏幕区域的中央**（TASKS-ROUND-2 §3.1）。
     *
     * <p>为什么必须显式摆：Processing 默认把窗口放在左上角，
     * 于是大尺寸窗口会有右下角超出屏幕 —— 任务单说的"部分超出屏幕、不可移动"
     * 就是这个。
     *
     * @param w 窗口宽
     * @param h 窗口高
     */
    private void centerWindow(int w, int h) {
        Rectangle avail = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int x = avail.x + (avail.width - w) / 2;
        int y = avail.y + (avail.height - h) / 2;
        // 再夹一次，保证四条边都在可用区内
        x = Math.max(avail.x, Math.min(x, avail.x + avail.width - w));
        y = Math.max(avail.y, Math.min(y, avail.y + avail.height - h));
        surface.setLocation(x, y);
        winX = x;
        winY = y;

        // 打印出来才算"验证过"——§3.1/§3.2 的验收要的是数字，不是"应该居中"
        System.out.printf(
                "[窗口] 可用区 %dx%d @(%d,%d)  窗口 %dx%d @(%d,%d)  "
                        + "四边余量 左%d 右%d 上%d 下%d%n",
                avail.width, avail.height, avail.x, avail.y, w, h, x, y,
                x - avail.x, avail.x + avail.width - (x + w),
                y - avail.y, avail.y + avail.height - (y + h));
    }

    @Override
    public void settings() {
        // P2D（OpenGL）：2026-10 应需求从 JAVA2D 切换——解锁 blendMode 等
        // 叠层能力，且大批量 rect/text 渲染通常更快。若出现兼容问题可改回 JAVA2D。
        // §3.2：尺寸必须走 resolveWindowSize() —— 它会先减屏幕边距再按 16:9
        // 向下取整，保证**没有一边超出屏幕**。上一版这里直接用了记住的尺寸，
        // 于是 1920×1080 的窗口在 1707×1019 的屏幕上右侧溢出 213px（实测）。
        int[] wh = resolveWindowSize();
        startW = wh[0];
        startH = wh[1];
        // 渲染器：**P2D（OpenGL）**。
        //
        // 曾经为了中文输入切到 JAVA2D，但那是纯 CPU 渲染：
        // 每帧贴 1920×1080 的 RGBA 大图就是几十 MB 的软件内存拷贝，
        // 结果卡顿。中文输入已改用**独立的 Swing 文本组件**收字
        // （见 installInputMethod），不需要渲染器配合。
        size(wh[0], wh[1], P2D);
        UITheme.disablePixelDensityScaling(this);
    }

    @Override
    public void setup() {
        UITheme.apply(this);
        surface.setTitle("YesterCar");
        if (System.getProperty("yestercar.ui.ime.probe") != null) {
            System.out.println("[IME探针] surface=" + surface.getClass().getName());
            System.out.println("[IME探针] native=" + surface.getNative());
            try {
                Object comp = surface.getClass().getMethod("getComponent").invoke(surface);
                System.out.println("[IME探针] component="
                        + (comp == null ? "null" : comp.getClass().getName()));
            } catch (NoSuchMethodException e) {
                System.out.println("[IME探针] 没有 getComponent()");
            } catch (Exception e) {
                System.out.println("[IME探针] getComponent 失败: " + e);
            }
        }
        // §2.1：每秒唤醒一次（时间块与右上角时钟都按秒变）。
        idleTimer = new java.util.Timer("yestercar-idle", true);
        idleTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                if (phase == Phase.MENU && menuIdleMode) {
                    redraw();       // 从定时器线程请求重绘（Processing 允许）
                }
            }
        }, 1000L, 1000L);

        // 中文输入法：JAVA2D 下 native 是 AWT Canvas，能收 InputMethodEvent。
        installInputMethod();

        // TASKS-ROUND-2 §3.1：居中于可用屏幕区域
        centerWindow(startW, startH);
        // §3.3：可缩放。**必须在 size() 之后**调用，否则 Processing 会忽略。
        surface.setResizable(true);
        frameRate(60);

        font = createFont(UITheme.resolvedFontFamily(), UITheme.FONT_SIZE, true);
        textFont(font);
        design = new DesignSpace(width, height);
        fonts3 = new Fonts(this, design);

        // D7：ak-ui 原资源。必须从 data/imgs/akui/ 读（不从 vendored 目录），
        //     只用 PNG（Processing 默认不解 WebP，不为一个格式加依赖）。
        bgImage = AkuiAssets.load(this, AkuiAssets.BG);
        characterImage = AkuiAssets.load(this, AkuiAssets.CHARACTER);
        paperTexture = AkuiAssets.load(this, AkuiAssets.PAPER);
        CommandCard.setPaperTexture(paperTexture);

        // D16 / L3：**资源与字体就绪后才允许启动入场动画**。
        // 原来的问题：入场动画先跑、字体与图片还在解码，首帧看到的是
        // "动画已经进行了一半"的状态 —— 冷启动突兀，重播却正常（因为那时已就绪）。
        // 门闩在这里置位，且「重播」走同一入口（replayLoadingPhase），
        // 所以两条路径的帧序列一致。
        assetsReady = true;

        // Q4：记住上次的代号并预填，但仍然显示输入界面
        nameInput.setLength(0);
        nameInput.append(presetUsername != null ? presetUsername : Username.remembered());

        startButton = new UiButton(this).text("开始");
        layoutStartButton();

        // D16：门闩保证入场只在资源就绪后开始
        phase = skipToMenu || bootBattle != null ? Phase.MENU : Phase.LOADING;
        phaseStartedAt = millis();
        if (skipToMenu) {
            enterPhase(Phase.MENU);
        }
        System.out.println("[GameOpening] 窗口 " + width + "x" + height
                + "  " + design);
    }

    @Override
    public void draw() {
        // 预加载跨阶段继续：开场 8 秒往往解不完 42 张，
        // 若只在这里泵一次，剩下的图会永远不加载（轮播只有前几张）。
        pumpPreload();
        long frameStartNs = System.nanoTime();
        idleFrameCount++;

        // D14：窗口可缩放。尺寸一变就重建换算与字体，并让菜单重建
        // （菜单里的卡片位置是构造时算好的，不重建会停在旧坐标）。
        syncDesignToWindow();

        // D16 / L3：资源没就绪时不启动入场 —— 第一帧只画背景。
        if (!assetsReady) {
            drawBackdrop();
            return;
        }

        drawBackdrop();

        // 调试直开战斗：**必须等到这里**（字体与设计尺寸已就绪）。
        // 之前放在 setup() 里，那时 design/fonts 还没建好，启动即白屏卡死。
        if (bootBattle != null && !bootBattleStarted && assetsReady) {
            bootBattleStarted = true;
            java.util.List<com.yestercar.common.model.Operator> all =
                    new java.util.ArrayList<>(com.yestercar.common.model.Operator.loadAll().values());
            all.sort(java.util.Comparator
                    .comparingInt(com.yestercar.common.model.Operator::cost)
                    .thenComparing(com.yestercar.common.model.Operator::id));
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(4, all.size()); i++) {
                ids.add(all.get(i).id());
            }
            launchBattle(bootBattle, ids);
        }

        switch (phase) {
            case LOADING -> drawLoading();
            case NAMING -> drawNaming();
            case MENU -> {
                if (menu != null) {
                    menu.draw(mouseX, mouseY);
                }
            }
            case BATTLE -> {
                if (battleScreen != null) {
                    if (battleExitAt < 0) {
                        battleScreen.render(millis(), mouseX, mouseY);
                    } else {
                        // 黑屏共 1.5 秒：前 600ms 淡出到全黑，之后保持纯黑 900ms
                        long t = millis() - battleExitAt;
                        int a = (int) Math.min(255, t * 255 / 600);
                        noStroke();
                        fill(0, Math.min(255, a));
                        rect(0, 0, width, height);
                    }
                    // 结算卡片出现 → 播结算音乐；滑出完成/返回 → 停
                    if (battleScreen.resultVisible() && !battleScreen.resultDone()) {
                        if (!fightendBgm.isPlaying()) {
                            stopAllBattleMusic();
                            fightendBgm.play();
                            System.out.println("[结算] 播放结算音乐");
                        }
                    } else if (fightendBgm.isPlaying()) {
                        fightendBgm.stop();
                    }
                    // 退出作战：测试模式直接关进程；正常模式先黑屏 1 秒再接列表
                    if (battleScreen.returnRequested()) {
                        if (bootBattle != null) {
                            System.out.println("[GameOpening] 测试结束，关闭游戏进程");
                            exit();
                        } else if (battleExitAt < 0) {
                            battleExitAt = millis();
                        } else if (millis() - battleExitAt >= 1500) {
                            battleExitAt = -1;
                            leaveBattle();
                        }
                    }
                }
            }
            default -> { }
        }

        if (phase == Phase.NAMING) {
            placeInputWindow();      // 跟随窗口移动/缩放
            keepInputFocus();
            autoTypeOnce();
            autoDeleteOnce();
            autoReactivateTest();
            if (System.getProperty("yestercar.ui.focuslog") != null
                    && millis() - lastFocusLogMs > 1000) {
                lastFocusLogMs = millis();
                java.awt.Window aw = java.awt.KeyboardFocusManager
                        .getCurrentKeyboardFocusManager().getActiveWindow();
                System.out.printf("[焦点] 可见=%s 已激活=%s 文本框焦点=%s 当前活动窗口=%s%n",
                        imeWindow != null && imeWindow.isVisible(),
                        imeWindow != null && imeWindow.isFocused(),
                        imeField != null && imeField.isFocusOwner(),
                        aw == null ? "null" : aw.getClass().getSimpleName());
            }
        }

        drawPhaseFade();

        // 渲染器性能自检：在**命名阶段**（未进入按需模式、持续 60fps）打一次实测帧率。
        // 主菜单进入按需模式后帧率被刻意压到 IDLE_FPS，在那里测没有意义。
        if (!fpsLogged && phase == Phase.NAMING && millis() > 9000) {
            fpsLogged = true;
            System.out.printf("[性能] P2D 实测 %.1f fps（命名阶段，窗口 %dx%d）%n",
                    frameRate, width, height);
        }

        // §6 验收：必须等菜单**画过几帧**再跑 —— 卡片要在 draw() 里才算得出
        // "实际渲染字号"（宽度不足会被缩），构造期跑只会拿到 0。
        maybeRunAcceptance();

        maybeSaveShot();

        // §2.1：主菜单画完这一帧就停循环，改为按需 redraw。
        // 时钟每秒变一次，所以每秒唤醒一次；其余靠鼠标/按键/尺寸事件。
        if (phase == Phase.MENU && !battleProbed) {
            String c = System.getProperty("yestercar.ui.quickbattle");
            if (c != null) {
                battleProbed = true;
                launchBattle(c, java.util.List.of("guard_01", "sniper_01"));
            }
        }
        if (phase == Phase.MENU && !replayFired) {
            String at = System.getProperty("yestercar.ui.replayafter");
            if (at != null && millis() - phaseStartedAt > Integer.parseInt(at)) {
                replayFired = true;
                System.out.println("[诊断] 触发重播入场");
                replayLoadingPhase();
            }
        }
        if (phase == Phase.MENU && !menuIdleMode) {
            menuIdleMode = true;
            // ⚠️ **绝对不要在运行时调 frameRate()**。P2D 的 frameRate 会去改
            //    GL 上下文的交换间隔（PJOGL.setFps → setSwapInterval），
            //    必须在"上下文当前"的渲染线程上调用；在按键线程或 draw 里反复调
            //    会抛 `GLException: This context is not current`，并连带把
            //    FPSAnimator 停掉 → 整个窗口卡死/崩溃。
            //    noLoop() 本身不碰 GL 上下文，安全。
            noLoop();
            System.out.println("[渲染] 进入按需重绘模式（noLoop）");
        }
        if (System.getProperty("yestercar.ui.drawnlog") != null) {
            System.out.printf("[单帧] %.1f ms（阶段 %s）%n",
                    (System.nanoTime() - frameStartNs) / 1_000_000.0, phase);
        }
        if (phase != Phase.MENU) {
            menuIdleMode = false;
        } else if (false) {
            System.out.printf("[绘制] 第 %d 帧  时钟秒=%d%n",
                    idleFrameCount, java.time.LocalTime.now().getSecond());
        }
    }

    /**
     * 请求重画一帧（脏标记）。
     *
     * <p>只有在 {@code noLoop()} 生效时才需要；没停循环时它是空操作。
     */
    private void requestRedraw() {
        if (!menuIdleMode) {
            return;
        }
        redraw();
    }

    /**
     * 鼠标移动 —— 主菜单下按节流请求重绘（§2.1）。
     */
    @Override
    public void mouseMoved() {
        if (phase != Phase.MENU) {
            return;
        }
        int now = millis();
        if (lastMotionMs >= 0 && now - lastMotionMs < MOTION_THROTTLE_MS) {
            return;      // 节流：别让抖动的 mouseMoved 把重绘打成连续
        }
        lastMotionMs = now;
        requestRedraw();
    }

    @Override
    public void mouseDragged() {
        if (phase == Phase.BATTLE && battleScreen != null) {
            battleScreen.mouseDragged(mouseX, mouseY, mouseButton);
            requestRedraw();
            return;
        }
        mouseMoved();
    }

    /**
     * 跑 §6 验收并退出（{@code -Dyestercar.ui.verify=true}）。
     *
     * <p>为什么不在菜单构造时跑：3.5 要报告**实际渲染字号**，而那个值
     * 只有在 {@code draw()} 走过一次之后才存在。构造期跑会全部拿到 0，
     * 那是假数据 —— 比不报还糟。
     */
    private void maybeRunAcceptance() {
        if (acceptanceDone || !Boolean.getBoolean("yestercar.ui.verify")
                || phase != Phase.MENU || menu == null) {
            return;
        }
        if (millis() - phaseStartedAt < 800) {
            return;
        }
        acceptanceDone = true;
        System.out.println("=== §6 验收 ===");
        for (String line : menu.runAcceptance()) {
            System.out.println(line);
        }
        System.out.println("=== 验收结束 ===");
    }

    /**
     * 到点就把当前画布写盘，然后退出。
     *
     * <p>只统计**进入 MENU 之后**的时间 —— 截图要的是"主菜单最终态"，
     * 不是加载动画的某一帧。
     */
    private void maybeSaveShot() {
        if (shotPath == null || shotPath.isEmpty() || !phase.name().equals(shotPhase)) {
            return;
        }
        if (millis() - phaseStartedAt < shotDelayMs) {
            return;
        }
        saveFrame(shotPath);
        System.out.println("[GameOpeningApp] 已截图 " + shotPath
                + "  (" + width + "x" + height + ")");
        shotPath = null;
        exit();
    }

    /**
     * 窗口尺寸变了就重建设计换算与字体（决定书 D14：窗口可缩放）。
     *
     * <h2>为什么菜单也要重建</h2>
     * 主菜单里的每张卡在<b>构造时</b>就把设计坐标换算成了窗口坐标
     * （`CommandCard.at(...)` 存的是像素）。窗口一变，那些坐标就过期了。
     * 所以这里把 {@code menu} 置空，让它在本帧按新尺寸重建 ——
     * 重建一次约 40ms，缩放时看不出来。
     *
     * <p>⚠️ 只重建 {@code menu}，不重建窗口、不重置阶段：
     * 缩放不应当把用户踢回加载动画。
     */
    private void syncDesignToWindow() {
        if (width == lastW && height == lastH) {
            return;
        }
        lastW = width;
        lastH = height;
        design = new DesignSpace(width, height);
        fonts3 = new Fonts(this, design);
        // ⚠️ 窗口尺寸一变，"开始"按钮必须跟着重排。
        //    它原来只在 setup() 里放过一次，于是放大窗口后输入栏跑到新位置、
        //    按钮还留在旧坐标 —— 看起来就是"按钮跑到输入栏左上角"（用户报告的 bug）。
        layoutStartButton();
        requestRedraw();          // 尺寸变了要重画
        if (menu != null) {
            String display = Username.display(nameInput.toString());
            // ⚠️ 窗口尺寸变化（含全屏）会重建 MainMenu，默认回到 MENU；
            //    这里把当前屏幕带过去，否则一全屏就"跳回主界面"。
            String keepScreen = menu.screenName();
            MainMenu old = menu;
            MainMenu prevMenu = menu;
        menu = new MainMenu(this, design, fonts3, display);
        if (prevMenu != null) {
            prevMenu.transferStateTo(menu);
        }
            menu.restoreScreen(keepScreen);
            old.transferStateTo(menu);      // 编队内容跨重建保留
        }
        System.out.printf("[GameOpening] 窗口 %dx%d，sx=%.4f sy=%.4f（等比=%s）%n",
                width, height, design.sx(), design.sy(),
                Math.abs(design.sx() - design.sy()) < 1e-4f);
    }

    @Override
    public void dispose() {
        // §2.1 验收：总绘制帧数。按需重绘生效时它应当只比阶段数多一点点，
        // 而不是"运行秒数 × 60"。
        System.out.printf("[渲染] 本次运行共绘制 %d 帧%n", idleFrameCount);
        if (idleTimer != null) {
            idleTimer.cancel();
        }
        if (imeWindow != null) {
            imeWindow.dispose();
        }
        bgm.dispose();
        voice.dispose();
        // D14：退出时记住尺寸，下次启动还原
        Settings.inWorkspace().setWindowSize(width, height);
        super.dispose();
    }

    /**
     * 背景 —— 决定书 D7：**必须使用 ak-ui 原资源，禁止自绘**。
     *
     * <h2>层级（D13 的图层分组）</h2>
     * <pre>
     *   1. 背景图      cover 铺满（不拉伸）
     *   2. 压暗层      让前景文字有对比度
     *   3. 角色立绘    底部居中，不裁头
     *   4. 左 / 右菜单 由 MainMenu 画
     * </pre>
     *
     * <p>角色只在主菜单阶段显示；加载与代号阶段压暗更重，
     * 免得立绘在还没进界面时抢注意力。
     */
    /** 立绘的屏幕位置与尺寸（预缩放时算一次）。 */
    private float[] charBox = new float[]{0f, 0f, 0f, 0f};

    /**
     * 窗口尺寸变了就重新预缩放背景与立绘。
     *
     * <p>JAVA2D 是纯 CPU 渲染：每帧把背景缩放一次、立绘再缩放一次，
     * 两次大图双线性采样就能吃掉一帧的大半。实测未预缩放时 **10.2 fps**。
     * 这里改成尺寸变化时缩放一次、之后 1:1 贴图。
     */
    private void ensureScaledImages() {
        if (scaledForW == width && scaledForH == height) {
            return;
        }
        scaledForW = width;
        scaledForH = height;

        // ---- ① 背景：cover 铺满 + 压暗（烘进图里，免得每帧再盖一层全屏矩形）----
        if (bgImage != null) {
            float scale = Math.max(width / (float) bgImage.width,
                    height / (float) bgImage.height);
            int dw = Math.max(width, Math.round(bgImage.width * scale));
            int dh = Math.max(height, Math.round(bgImage.height * scale));
            PImage tmp = bgImage.copy();
            tmp.resize(dw, dh);
            PImage cover = tmp.get((dw - width) / 2, (dh - height) / 2, width, height);

            processing.core.PGraphics pg = createGraphics(width, height);
            pg.beginDraw();
            pg.image(cover, 0, 0);
            pg.noStroke();
            pg.fill(0f, 0f, 0f, 0.34f * 255f);
            pg.rect(0, 0, width, height);
            pg.endDraw();
            bgRawScaled = pg.get();
            pg.dispose();
        }

        // ---- ② 角色：预缩放（保留这项与渲染器无关的优化）----
        //
        // ⚠️ **不再把背景与角色合成一张大图**。那在 JAVA2D 上是每帧几十 MB 的
        // 软件拷贝，实测把操作拖卡。P2D 下两次贴图都是 GPU 上的纹理四边形，很便宜。
        if (characterImage != null) {
            float[] box = AkuiAssets.characterBox(this, characterImage, 1.0f,
                    CHARACTER_HEIGHT_FRAC);
            if (box != null) {
                charBox = box;
                PImage tmp = characterImage.copy();
                tmp.resize(Math.max(1, Math.round(box[2])), Math.max(1, Math.round(box[3])));
                charScaled = tmp;
            }
        }
    }

    private void drawBackdrop() {
        ensureScaledImages();
        boolean inMenu = phase == Phase.MENU;

        // 图层顺序（用户第 7 轮明确）：
        //   ① 背景（最底）  ② 角色立绘  ③ 经验圈/代号/播报/立绘框（由 MainMenu 画）
        //
        // ⚠️ 这里**不再有压暗层**。上一版为了左栏可读性在角色之上盖了一层
        // 32 条离散的渐隐矩形 —— 结果在立绘上表现为**一列一列明暗交替**，
        // 用户报告的正是这个。可读性改用文字阴影解决（见 LeftColumn）。
        if (inMenu) {
            if (bgRawScaled != null) {
                imageMode(PConstants.CORNER);
                image(bgRawScaled, 0f, 0f);
            } else {
                background(UITheme.WINDOW_BG_DARK);
            }
            if (charScaled != null) {
                image(charScaled, charBox[0], charBox[1]);
            }
        } else {
            // 加载 / 命名阶段：只有背景 + 更重的一层压暗
            if (bgRawScaled != null) {
                imageMode(PConstants.CORNER);
                image(bgRawScaled, 0f, 0f);
            } else {
                background(UITheme.WINDOW_BG_DARK);
            }
            noStroke();
            fill(0f, 0f, 0f, 0.24f * 255f);
            rect(0, 0, width, height);
        }
    }

    // ------------------------------------------------------------------
    // 阶段一：启动动画（原主菜单的入场遮罩，2026-10 应需求移到这里）
    // ------------------------------------------------------------------

    /** 预加载队列（主菜单立绘），每帧解码一张。 */
    private static final java.util.List<java.io.File> preloadQueue = new java.util.ArrayList<>();

    /** 预加载总项数与已完成数。 */
    private static int preloadTotal;
    private static int preloadDone;

    /** @return 预加载进度 0–1（无任务时为 1） */
    public static float preloadProgress() {
        return preloadTotal <= 0 ? 1f : Math.min(1f, preloadDone / (float) preloadTotal);
    }

    /**
     * 建立预加载队列：按目录实际数量生成（不写死 42），解码结果交给 LeftColumn 复用。
     */
    private static void buildPreloadQueue(processing.core.PApplet app) {
        if (preloadTotal > 0) {
            return;
        }
        java.io.File dir = new java.io.File(app.sketchPath("data/imgs/Mainshow"));
        java.io.File[] files = dir.listFiles((dd, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".webp");
        });
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
        preloadQueue.addAll(java.util.Arrays.asList(files));
        preloadTotal = preloadQueue.size();
    }


    /** 上次解码时刻（节流用）。 */
    private static long lastPreloadAt;


    /** 每帧最多解一张（节流到 120ms，避免主菜单卡顿）。 */
    private void pumpPreload() {
        buildPreloadQueue(this);
        if (preloadQueue.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPreloadAt < 120) {
            return;                       // 节流：每 120ms 只解一张
        }
        lastPreloadAt = now;
        java.io.File f = preloadQueue.remove(0);
        processing.core.PImage raw = loadImage(f.getPath());
        if (raw != null) {
            processing.core.PImage img = raw;                 // 不再裁剪，由素材本身裁好
            img.resize(480, 0);
            com.yestercar.gameopening.LeftColumn.addPreloaded(img);
        }
        preloadDone++;
    }

    private void drawLoading() {
        int elapsed = millis() - phaseStartedAt;
        drawBootEntrance(elapsed);

        // 阶段推进：遮罩放完（恢复原条件——加上资源进度后 YC 动画会整段消失）
        if (elapsed >= Math.max(loadingMillis, BOOT_MIN_MS)) {
            enterPhase(Phase.NAMING);
        }
    }

    /**
     * 终端启动编排 —— {@code data-entrance="playing"} 的开场层：
     * 「YC 标记旋转压缩入场 → 光线展开 → 整层遮罩淡出」。
     * 曲线全部是 {@link Easing#TERMINAL}；遮罩消失（70% 关键帧）后整层不再绘制。
     *
     * @param entMs 阶段经过的毫秒
     */
    private void drawBootEntrance(float entMs) {
        if (entMs >= BOOT_VEIL_GONE_MS) {
            return;
        }
        float veil = entMs <= BOOT_VEIL_FADE_FROM_MS ? 1f
                : 1f - (entMs - BOOT_VEIL_FADE_FROM_MS)
                        / (float) (BOOT_VEIL_GONE_MS - BOOT_VEIL_FADE_FROM_MS);

        noStroke();

        // 遮罩本体 rgba(12,19,25,.3)
        fill(12, 19, 25, 0.3f * 255f * veil);
        rect(0f, 0f, width, height);

        // 标记：15cqw 见方，旋转 + 透视压缩入场
        float p = Easing.TERMINAL.progressFromStart(entMs, 0f, BOOT_MARK_MS);
        float markA = Math.min(1f, p / 0.45f);
        float markS = DesignSpace.BASE_W * 0.15f;
        float lineH = DesignSpace.BASE_H * 0.005f;
        float gap = DesignSpace.BASE_H * 0.03f;
        float caption = 18f;
        float topY = (DesignSpace.BASE_H - (markS + gap * 2f + lineH + caption)) / 2f;
        float cx = DesignSpace.BASE_W / 2f;

        pushMatrix();
        translate(design.x(cx), design.y(topY + markS / 2f));
        rotate(radians(-25f * (1f - p)));
        scale(1f - 0.28f * (1f - p), 1f);   // 仿 rotateY(45deg) 的透视压缩

        float side = design.w(markS);
        // 光晕（box-shadow 0 0 0 1.2cqw rgba(255,230,0,.08)）
        float halo = design.w(DesignSpace.BASE_W * 0.012f);
        fill(255, 230, 0, 0.08f * 255f * markA * veil);
        rect(-side / 2f - halo, -side / 2f - halo, side + halo * 2f, side + halo * 2f);
        // 面底 #151b21
        fill(21, 27, 33, 255f * markA * veil);
        rect(-side / 2f, -side / 2f, side, side);
        // 边框 0.45cqw
        noFill();
        stroke(UITheme.SIGNAL_ACTION, 255f * markA * veil);
        strokeWeight(Math.max(1.5f, design.w(DesignSpace.BASE_W * 0.0045f)));
        rect(-side / 2f, -side / 2f, side, side);
        noStroke();

        fonts3.apply(this, Fonts.Role.COMMAND, 82f);
        textAlign(PConstants.CENTER, PConstants.CENTER);
        fill(247, 247, 244, 255f * markA * veil);
        text("YC", 0f, -design.h(10f));
        fonts3.apply(this, Fonts.Role.MONO, 11f);
        fill(247, 247, 244, 0.78f * 255f * markA * veil);
        text("YESTERCAR / ONLINE", 0f, design.h(38f));
        popMatrix();

        // 光线：从中心向两侧展开；下方带白投影
        float pl = Easing.TERMINAL.progressFromStart(entMs, BOOT_LIGHT_DELAY_MS, BOOT_LIGHT_MS);
        float lineY = topY + markS + gap + lineH / 2f;
        float wNow = DesignSpace.BASE_W * 0.64f * pl;
        float shadowOff = DesignSpace.BASE_H * 0.012f;
        fill(255, 255, 255, 0.3f * 255f * veil);
        rect(design.x(cx - wNow / 2f), design.y(lineY + shadowOff), design.w(wNow), design.h(lineH));
        fill(UITheme.SIGNAL_ACTION, 255f * veil);
        rect(design.x(cx - wNow / 2f), design.y(lineY), design.w(wNow), design.h(lineH));

        // 说明字 1cqw
        fonts3.apply(this, Fonts.Role.MONO, 13.7f);
        textAlign(PConstants.CENTER, PConstants.TOP);
        fill(247, 247, 244, 0.68f * 255f * veil);
        text("SYSTEM LINK ESTABLISHED", design.x(cx), design.y(lineY + shadowOff + gap));
    }

    /** @return 进度条（也是输入框下划线）的矩形 {@code {x, y, w, h}}。 */
    private float[] progressBarBox() {
        float w = design.wFraction(0.42f);
        float h = design.h(6);
        return new float[]{(width - w) / 2f, height / 2f + design.h(46), w, h};
    }

    // ------------------------------------------------------------------
    // 阶段二：输入代号
    // ------------------------------------------------------------------

    private void drawNaming() {

        float fade = phaseFadeIn();
        if (fade <= 0f) {
            return;
        }

        float[] bar = progressBarBox();
        float barY = bar[1];
        // 输入文字的基线在横线上方一点；标签再往上 ——
        // ⚠️ 两者以前都锚在 bar[1] - 14 上，于是**重叠成了一团**（实测截图看到）。
        float inputBaseline = barY - design.h(10);
        float labelBaseline = inputBaseline - design.h(30);

        // 标签
        textAlign(PConstants.CENTER, PConstants.BOTTOM);
        fill(UITheme.TEXT_ON_DARK, 255 * fade);
        textSize(design.font(15));
        text("请输入你的代号", width / 2f, labelBaseline);

        // 输入框：底下一道线（就是进度条那条线，位置不变 —— 它是"变形"过来的）
        noStroke();
        fill(0xFF2A2E33, 255 * fade);
        rect(bar[0], barY, bar[2], bar[3], bar[3] / 2f);

        // 输入内容（空的时候显示占位说明）
        String shown = Username.sanitize(nameInput.toString());
        textAlign(PConstants.CENTER, PConstants.BOTTOM);
        textSize(design.font(22));
        if (shown.isEmpty()) {
            fill(UITheme.TEXT_DIM_ON_DARK, 150 * fade);
            text("（不填就是「" + Username.DEFAULT_NAME + "」）",
                    width / 2f, inputBaseline);
        } else {
            fill(UITheme.TEXT_ON_DARK, 255 * fade);
            text(shown, width / 2f, inputBaseline);
        }

        // 光标：贴在文字右侧
        if (caretOn) {
            float textW = textWidth(shown);
            float caretX = width / 2f + textW / 2f + design.w(3);
            stroke(UITheme.MENU_ACCENT, 255 * fade);
            strokeWeight(design.len(2));
            line(caretX, inputBaseline - design.h(24), caretX, inputBaseline + design.h(4));
            noStroke();
        }

        // 提示
        textAlign(PConstants.CENTER, PConstants.TOP);
        fill(UITheme.TEXT_DIM_ON_DARK, 255 * fade);
        textSize(design.font(12));
        text(Username.limitHint() + "    Enter 或点「开始」继续",
                width / 2f, barY + design.h(18));

        // 按钮。
        // ⚠️ 第 5 个参数是 enabled（boolean），不是不透明度 ——
        // 淡入由 drawPhaseFade() 压的那层黑幕负责，元素不必各自淡。
        startButton.draw(mouseX, mouseY, false, true, true);

        tickCaret();
    }

    /** 光标闪烁。 */
    private void tickCaret() {
        caretOn = (millis() % 1000) < 600;
    }

    private void layoutStartButton() {
        float w = design.w(140);
        float h = design.h(40);
        startButton.at((width - w) / 2f, progressBarBox()[1] + design.h(46), w, h);
        System.out.printf("[布局] 窗口 %dx%d  输入栏 y=%.1f  开始按钮 [%.1f, %.1f %.1fx%.1f]%n",
                width, height, progressBarBox()[1],
                (width - w) / 2f, progressBarBox()[1] + design.h(46), w, h);
    }

    // ------------------------------------------------------------------
    // 阶段切换
    // ------------------------------------------------------------------

    private void enterPhase(Phase next) {
        int prevStart = phaseStartedAt;
        phase = next;
        phaseStartedAt = millis();
        System.out.println("[GameOpening] 进入阶段 " + next);
        // 音频：进主界面时**背景音乐（循环）+ 助理问候（单次）同时开始**；
        // 离开主界面（重播回到 LOADING）两者都停。
        if (next == Phase.MENU) {
            bgm.play();
            voice.play();      // 单次：每次进主界面都从头播一遍
        } else {
            // ⚠️ 这里只能 stop，不能 dispose。
            //    dispose 是**终止性**的：它把 clip 置空、且不再重新加载，
            //    所以"重播回到 LOADING"之后再进主界面就**彻底静音**了（踩过）。
            //    dispose 只用在真正退出程序的时候。
            bgm.stop();
            voice.stop();
        }

        // ⚠️ 主菜单用 noLoop() 省电；一旦离开主菜单（比如点了「重播入场」
        //    回到 LOADING），必须把循环开回来，否则 draw() 再也不被调用，
        //    界面就定格不动 —— 看起来就是"点重播直接死程序"。
        if (next != Phase.MENU) {
            menuIdleMode = false;
            loop();
        }
        setInputActive(next == Phase.NAMING);
        if (next == Phase.NAMING) {
            // 客观验证启动动画时长（目标约 2.5s，见 DEFAULT_LOADING_MS）
            System.out.println("[GameOpening] 启动动画实际播放 "
                    + (phaseStartedAt - prevStart) + "ms");
            caretOn = true;
        } else if (next == Phase.MENU) {
            String display = Username.display(nameInput.toString());
            Username.remember(display);         // Q4：记住，下次预填
            int t0 = millis();
            MainMenu prev = menu;
            menu = new MainMenu(this, design, fonts3, display);
            if (prev != null) {
                prev.transferStateTo(menu);      // 编队内容跨重建保留
            }
            System.out.println("[GameOpening] 代号 = " + display
                    + "，主菜单构造耗时 " + (millis() - t0) + "ms");
        }
    }

    /** @return 这一阶段的淡入进度 0~1。 */
    private float phaseFadeIn() {
        return constrain((millis() - phaseStartedAt) / (float) PHASE_FADE_MS, 0f, 1f);
    }

    /** 加载阶段结束时压一层黑幕淡出，让切换不生硬。 */
    private void drawPhaseFade() {
        // 只有"离开加载阶段"的那一小段需要压黑 ——
        // 之后两个阶段之间是元素变形，不该再盖一层。
        if (phase != Phase.NAMING) {
            return;
        }
        float t = 1f - phaseFadeIn();
        if (t <= 0f) {
            return;
        }
        noStroke();
        fill(0, 200 * t);
        rect(0, 0, width, height);
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public void keyPressed() {
        // 战斗中：按键交给战斗屏（Esc 回主菜单也由它决定）
        if (phase == Phase.BATTLE && battleScreen != null) {
            battleScreen.keyPressed(key, keyCode);
            requestRedraw();
            return;
        }
        requestRedraw();
        if (phase != Phase.NAMING) {
            return;
        }

        // ⚠️ 拖动窗口之类会把焦点从收字窗拿走，之后就再也打不进中文。
        //    所以**只要在命名阶段收到按键**，就先把焦点还给收字窗。
        //    注意不能每帧抢（那会让窗口拖不动），只能在"用户正在按键"时抢 ——
        //    按键和拖动不会同时发生，所以不冲突。
        if (imeField != null && imeWindow != null && imeWindow.isVisible()
                && !imeField.isFocusOwner()) {
            activateInputWindow();
        }

        if (keyCode == PConstants.ENTER || keyCode == PConstants.RETURN) {
            submitName();
            return;
        }
        if (keyCode == PConstants.BACKSPACE) {
            // ⚠️ 必须删**收字框**里的字，不能只删 nameInput。
            //
            // 上一版只对 nameInput 做 deleteCharAt，收字框里的旧文本还在；
            // 下一次按键 appendTyped 会拿"收字框旧文本 + 新字符"回写，
            // 于是刚删掉的字**又回来了**（用户报的症状）。
            // 收字框在场时它就是唯一数据源，删它才会同步到 nameInput。
            if (imeField != null && imeWindow != null && imeWindow.isVisible()) {
                javax.swing.Action del = imeField.getActionMap().get(
                        javax.swing.text.DefaultEditorKit.deletePrevCharAction);
                if (del != null) {
                    del.actionPerformed(new java.awt.event.ActionEvent(imeField,
                            java.awt.event.ActionEvent.ACTION_PERFORMED, "delete"));
                }
            } else if (nameInput.length() > 0) {
                nameInput.deleteCharAt(nameInput.length() - 1);
            }
            return;
        }
    }

    /**
     * 收已上屏的字符。
     *
     * <p>⚠️ 中文输入法只能走这条路：组合期的按键被输入法消费，
     * 不产生 {@code keyPressed()}；上屏时只有 {@code KEY_TYPED}。
     * 详见 {@code NewMapPanel.handleTyped} 的说明。
     */
    @Override
    public void keyTyped() {
        if (phase != Phase.NAMING) {
            return;
        }
        // ⚠️ 收字框在场时，**画布不再接收字符**。
        //
        // 原因：一旦焦点被拿回画布（比如拖过窗口），用户敲的第一个键通常是
        // 拼音的开头字母（n / zh ...）。如果在这里把它当字符塞进代号，
        // 名字里就会多出一个莫名其妙的字母。所以这种状态下只做"恢复焦点"这件事
        // （见 keyPressed），字符交给收字框自己处理。
        //
        // 只有收字框**不可用**时（Swing 建不起来），才退回画布收字。
        if (imeField != null && imeWindow != null && imeWindow.isVisible()) {
            return;
        }
        appendTyped(String.valueOf(key));
    }

    private void submitName() {
        if (phase != Phase.NAMING) {
            return;
        }
        // ⚠️ 必须在这里把菜单建出来。
        //    menu 原本只在"窗口尺寸变化"时创建，点「开始」进来后 menu==null，
        //    于是阶段切到 MENU 却没有任何东西可画 —— 表现就是"点了没反应"。
        if (menu == null) {
            String display = Username.display(nameInput.toString());
            Username.remember(display);
            menu = new MainMenu(this, design, fonts3, display);
            System.out.println("[GameOpening] 代号 = " + display + "，已创建主菜单");
        }
        enterPhase(Phase.MENU);
    }

    @Override
    public void mousePressed() {
        if (phase == Phase.BATTLE && battleScreen != null) {
            battleScreen.mousePressed(mouseX, mouseY, mouseButton);
            requestRedraw();
            return;
        }
        pointerDown = true;
        pressX = mouseX;
        pressY = mouseY;
        requestRedraw();
        if (phase == Phase.NAMING && startButton.isHovered(mouseX, mouseY)) {
            startButton.press();
            return;
        }
        // 菜单自己管命中 —— 卡片的形状是剪裁过的，但命中区是矩形
        if (phase == Phase.MENU && menu != null) {
            menu.mousePressed(mouseX, mouseY);
        }
    }

    @Override
    public void mouseReleased() {
        // 战斗中：松手交给战斗屏（拖拽部署要靠它落地）
        if (phase == Phase.BATTLE && battleScreen != null) {
            battleScreen.mouseReleased(mouseX, mouseY, mouseButton);
            requestRedraw();
            return;
        }
        // 松手时判断是"点击"还是"拖拽"：只有点击才把焦点还给收字框。
        // 拖拽（比如在画布上划动）不该抢焦点 —— 那正是"窗口拖不动"的同类问题。
        boolean wasClick = pointerDown
                && Math.abs(mouseX - pressX) < 6 && Math.abs(mouseY - pressY) < 6;
        pointerDown = false;
        if (phase == Phase.NAMING && wasClick) {
            activateInputWindow();
        }
        requestRedraw();
        if (phase == Phase.MENU) {
            if (menu != null) {
                menu.mouseReleased(mouseX, mouseY);
            }
            return;
        }
        if (phase != Phase.NAMING) {
            return;
        }
        boolean onButton = startButton.isPressed() && startButton.isHovered(mouseX, mouseY);
        startButton.release();
        if (onButton) {
            submitName();
        }
    }
}
