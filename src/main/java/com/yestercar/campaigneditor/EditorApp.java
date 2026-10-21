package com.yestercar.campaigneditor;

import com.yestercar.campaigneditor.command.UndoManager;
import com.yestercar.campaigneditor.io.MapJsonIO;
import com.yestercar.campaigneditor.model.Door;
import com.yestercar.campaigneditor.model.EditMode;
import com.yestercar.campaigneditor.model.TileMap;
import com.yestercar.campaigneditor.model.TileType;
import com.yestercar.campaigneditor.util.MapNaming;
import com.yestercar.campaigneditor.util.MapValidator;
import com.yestercar.campaigneditor.view.GridRenderer;
import com.yestercar.campaigneditor.view.LayoutRegions;
import com.yestercar.campaigneditor.view.LoadingView;
import com.yestercar.campaigneditor.view.MapViewport;
import com.yestercar.campaigneditor.view.NewMapPanel;
import com.yestercar.common.io.CampaignJsonIO;
import com.yestercar.common.io.CampaignLoader;
import com.yestercar.common.io.CampaignStore;
import com.yestercar.common.io.Settings;
import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.Campaign;
import com.yestercar.common.model.CampaignMeta;
import com.yestercar.common.ui.FileDialogs;
import com.yestercar.common.ui.UiButton;
import com.yestercar.common.util.AssetPaths;
import com.yestercar.common.util.UITheme;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PFont;
import processing.core.PGraphics;
import processing.core.PImage;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 战役编辑器窗口。
 *
 * <p>窗口尺寸 <strong>1600 × 900</strong>，与加载背景图 1:1，避免缩放造成的模糊。
 *
 * <h2>阶段（状态机）</h2>
 * <pre>
 *   LOADING  加载界面，约 2.2 秒
 *      ↓
 *   BLACK    黑屏过渡：淡入 250ms → 全黑 500ms → 淡出 250ms
 *      ↓
 *   EDITING  编辑器：顶部工具栏 / 左侧地图区 / 右侧属性栏 / 波次区 / 状态栏
 * </pre>
 *
 * <h2>编辑器底色是白的</h2>
 * 与加载界面、主菜单的深色不同，编辑器用浅色底（{@link UITheme#WINDOW_BG_EDITOR}）——
 * 这样浅色的地砖、高台才看得清，也接近参考图里"浅色地图 + 深色 UI 框"的感觉。
 * <strong>注意：白底必须配深色文字</strong>，用 {@link UITheme#TEXT_ON_LIGHT} 那一套。
 *
 * <h2>彩蛋</h2>
 * 加载过程中按下 <strong>W</strong>，黑屏转场时会多出一张图，随黑幕一起淡入淡出。
 *
 * <h2>⚠️ 「地图」与「战役」两个词的用法（刻意不统一）</h2>
 * 同一样东西在两个视角下叫法不同，<strong>这是有意的</strong>：
 * <ul>
 *   <li><b>编辑器里一律叫「地图」</b> —— 你在这里操作的是"一张图的格子"。
 *       工具栏是「新建战役／打开战役」——见 {@link #HEADER_LABELS} 上方的说明。</li>
 *   <li><b>游戏主菜单里叫「战役」</b> —— 呈现给玩家的是"一个能玩的关卡"。</li>
 * </ul>
 * 数据结构上二者<strong>是同一个东西</strong>：一个文件夹 = 一张地图 + 它自己的配置
 * （{@code campaign.json} + {@code map/<战役名>.json}）。因为「战役就是关卡地图，
 * 不存在一个战役包含多个关卡」，所以文件夹名 = 地图名 = 主菜单里显示的名字。
 *
 * <p>强行统一成一个词，必然有一边别扭：编辑器里说"保存战役"不像在存图，
 * 主菜单里说"选择地图"又不像在选关卡。所以别去"修"这个不一致。
 *
 * <h2>待办</h2>
 * 「测试」按钮目前<strong>跳过</strong>了「自定义战役界面」与「编队界面」，
 * 直接启动游戏进程进战斗。那两个界面做出来之后，把它们接进这条链路。
 */
public class EditorApp extends PApplet {

    /** 窗口宽度。 */
    public static final int WINDOW_W = LayoutRegions.WINDOW_W;

    /** 窗口高度。 */
    public static final int WINDOW_H = LayoutRegions.WINDOW_H;

    // ---- 黑屏过渡 ----
    public static final int FADE_IN_MS = 250;
    public static final int BLACK_HOLD_MS = 500;
    public static final int FADE_OUT_MS = 250;
    public static final int BLACK_TOTAL_MS = FADE_IN_MS + BLACK_HOLD_MS + FADE_OUT_MS;

    /** 触发彩蛋的按键。 */
    public static final char EASTER_EGG_KEY = 'w';

    /** 地形工具的排列顺序（工具栏按钮从左到右）。 */
    private static final TileType[] TOOLS = {
            TileType.GROUND, TileType.HIGH, TileType.IMPASSABLE,
            TileType.RED_DOOR, TileType.BLUE_DOOR
    };

    /** 工具栏里每个按钮的宽度。 */
    private static final float TOOL_BTN_W = 96f;

    /** 编辑器阶段。 */
    private enum Phase {
        LOADING, BLACK, EDITING
    }

    private Phase phase;
    private long blackStartTime;
    private LoadingView loadingView;

    private PGraphics blackOverlay;
    private PFont font;

    // ---- 彩蛋 ----
    private boolean easterEggArmed;
    private PImage easterEggImage;

    // ---- 编辑器状态 ----
    private TileMap map;
    private MapViewport viewport;
    private GridRenderer gridRenderer;
    private UndoManager undoManager;

    /**
     * 当前在放什么：一个地形工具，或者擦除。
     *
     * <p>把两者收进一个对象，是为了让"互斥"成为结构上的保证 ——
     * 曾经是两个独立 boolean，会出现两个按钮同时高亮、实际只有擦除生效的情况。
     */
    private EditMode editMode;

    /** 当前这一笔的描述，提交历史时用。 */
    private String strokeLabel = "编辑";

    /** 侧栏「门」小节里被选中的门，用于改 HP / 删除。 */
    private boolean selectedIsRedDoor;
    private int selectedDoorId = -1;

    private boolean dragging;
    private float lastDragX;
    private float lastDragY;

    /** 状态栏文字。 */
    private String status = "就绪";

    // ------------------------------------------------------------------
    // UI 组件（P2.2 追加）
    // ------------------------------------------------------------------

    /**
     * 本帧所有可点的按钮。
     *
     * <p>每帧重建：按钮的位置和数量都会变（门列表随地图上的门增减）。
     * 绘制、命中判定、光标判定<strong>都读这一个列表</strong>，
     * 于是不可能出现"画在这里、判定在那里"的错位。
     */
    private final List<UiButton> buttonList = new ArrayList<>();

    // ---- 波次表（第 22 轮接入编辑） ----

    /** 当前战役的波次表（可编辑模型，纯逻辑在 WaveEditor）。 */
    private WaveEditor waves = new WaveEditor(null);

    /** 当前战役目录（波次写回这里）。 */
    private java.nio.file.Path currentCampaignDir;

    /** 波次是否已经挂上撤销栈。 */
    private boolean waveUndoBound;

    /** 本关规则参数（生命 / 初始费用 / 每秒回费）。 */
    private com.yestercar.common.model.LevelParams params =
            com.yestercar.common.model.LevelParams.DEFAULT;

    /** 当前选中的波次下标。 */
    private int selectedWave = 0;

    /** 波次面板的按钮池（按钮对象复用，动作每帧重算）。 */
    private final List<UiButton> waveButtons = new ArrayList<>();

    /** 与 waveButtons 一一对应的动作。 */
    private final List<Runnable> waveActions = new ArrayList<>();

    /** 模态的「新建战役」面板；{@code null} 表示没开。 */
    private NewMapPanel newMapPanel;

    /** 文件对话框（打开 / 另存为）。 */
    private FileDialogs fileDialogs;

    /** 编辑器配置（记住上次目录）。 */
    private Settings settings;

    /** 地图类型按钮：与 {@link #TOOLS} 一一对应。 */
    private final List<UiButton> toolButtons = new ArrayList<>();

    /** 「擦除」开关按钮。 */
    private final UiButton eraserButton = new UiButton(this).text("擦除  [E]");

    /** 工具栏左侧的文件按钮，与 {@link #HEADER_LABELS} 一一对应。 */
    private final List<UiButton> headerButtons = new ArrayList<>();

    /** 工具栏右侧的主按钮（测试），黄色三态。 */
    private final UiButton testButton = new UiButton(this).text("测试");

    /**
     * 「门」小节的按钮，按需增长后复用。
     *
     * <p>门会增减，所以不能像地形按钮那样一次性建好；但也别每帧都 new ——
     * 复用能保住按下状态（否则鼠标按住时按钮会被换成新的，状态丢失）。
     */
    private final List<UiButton> doorButtons = new ArrayList<>();

    /** 取第 {@code index} 个门行按钮，不够就补一个。 */
    private UiButton doorButton(int index) {
        while (doorButtons.size() <= index) {
            doorButtons.add(new UiButton(this));
        }
        return doorButtons.get(index);
    }

    // ------------------------------------------------------------------
    // 文件状态（P2.2）
    // ------------------------------------------------------------------

    /**
     * 当前地图对应的文件路径；{@code null} 表示还没保存过（新建的图）。
     *
     * <p>「保存」写回这里。这样从哪读的就写回哪，不会出现"存了但游戏读不到"。
     */
    private Path currentPath;

    /**
     * 上次保存时的地图指纹，用来判断有没有未保存改动。
     *
     * <p>存指纹而不是存整份快照：只要比"是否相同"，没必要再压一份几百 KB 的图。
     */
    private String savedFingerprint;

    /**
     * @return 当前这一笔的描述
     */
    private String lastStrokeLabel() {
        return strokeLabel;
    }

    @Override
    public void settings() {
        size(WINDOW_W, WINDOW_H, JAVA2D);
        UITheme.disablePixelDensityScaling(this);
    }

    @Override
    public void setup() {
        UITheme.apply(this);
        surface.setTitle("YesterCar — 战役编辑器");

        font = createFont(UITheme.resolvedFontFamily(), UITheme.FONT_SIZE, true);

        loadingView = new LoadingView(this);
        phase = Phase.LOADING;

        easterEggImage = loadEasterEgg();

        // 载入样例地图；失败就开一张空白的，不让编辑器打不开
        map = loadSampleMap();
        viewport = new MapViewport(map.cols(), map.rows(), LayoutRegions.mapArea());
        gridRenderer = new GridRenderer(this);
        // 历史栈必须在载入地图之后建 —— 它把当前状态当作"初始状态"
        undoManager = new UndoManager(map);

        // 当前在放什么（地形工具 or 擦除）—— 两者互斥由 EditMode 保证
        editMode = new EditMode(List.of(TOOLS));

        // 文件对话框与配置：用来记住"上次用过的目录"
        settings = Settings.inWorkspace();
        fileDialogs = new FileDialogs(settings);
        System.out.println("[EditorApp] 配置文件: " + settings.file()
                + "  上次目录=" + settings.getLastMapDir(Workspace.campaignsDir()));

        // 按钮：位置每帧算，这里只建出来
        for (String label : HEADER_LABELS) {
            headerButtons.add(new UiButton(this).text(label));
        }
        for (TileType tool : TOOLS) {
            toolButtons.add(new UiButton(this).text(tool.displayName()));
        }
        // 地形贴图在 setup 里一次性加载（256×256 × 5），之后每帧只是贴上去
        int texCount = gridRenderer.loadTextures();

        System.out.println("[EditorApp] " + map);
        System.out.println("[EditorApp] " + viewport);
        System.out.println("[EditorApp] " + gridRenderer.textureSummary());
        gridRenderer.textureFailures().forEach((type, why) ->
                System.out.println("[EditorApp]   缺图 " + type.displayName() + ": " + why));
        System.out.println("[EditorApp] 布局: " + LayoutRegions.describe().replaceAll("\\R", " | "));

        // 复述载入结果，让状态栏一开始就说清楚"地图来了没、贴图来了没"
        status = "已载入 " + map.name() + "，" + gridRenderer.textureSummary()
                + (texCount == 0 ? "（全部缺图，用默认色块）" : "");
        // 刚载入就算"已保存"状态 —— 否则一开窗就提示未保存
        savedFingerprint = fingerprint();
        applyTitle();
    }

    /**
     * 载入启动时打开的战役；一个都没有就开一张 12×8 的空图。
     *
     * <h2>优先找「演示地图」那个战役</h2>
     * {@link #DEMO_MAP_NAME} 是**代码里的约定**，不是文档规定的 ——
     * 文档里 {@code map_001} 的每一处都只是契约示例 / 测试输入，
     * 从没说过它是"默认地图"。选它当启动图，只是为了开窗就有一张能看的图。
     *
     * <p>它不存在时退回"随便哪个自定义战役"，再不行才开空图 ——
     * 这样克隆一个干净仓库、或者把演示战役删掉，编辑器都还能用。
     *
     * <p>同时把"当前战役目录"记下来，这样「保存」知道该写回哪。
     */
    private TileMap loadSampleMap() {
        Campaign demo = CampaignLoader.findById(DEMO_MAP_NAME);
        if (demo != null && demo.dir() != null) {
            TileMap loaded = tryLoadCampaign(demo);
            if (loaded != null) {
                return loaded;
            }
        }
        // 退而求其次：任何一个可写的自定义战役
        for (Campaign c : CampaignLoader.loadCustom()) {
            if (c.dir() == null) {
                continue;
            }
            TileMap loaded = tryLoadCampaign(c);
            if (loaded != null) {
                return loaded;
            }
        }
        // 一个战役都没有：开一张空图，等玩家「另存为」时再落成战役
        currentPath = null;
        status = "还没有任何战役，已新建一张空图（保存时会落成战役）";
        return new TileMap("untitled", 12, 8);
    }

    /**
     * 尝试从战役目录读地图。
     *
     * @param campaign 战役
     * @return 读到的地图；失败返回 {@code null}
     */
    private TileMap tryLoadCampaign(Campaign campaign) {
        Path mapFile = campaign.mapFile();
        if (mapFile == null || !Files.isRegularFile(mapFile)) {
            return null;
        }
        try {
            TileMap loaded = MapJsonIO.load(mapFile);
            // 严格绑定：地图名以战役目录名为准（一个战役 = 一张地图）
            loaded.setName(campaign.id());
            currentPath = campaign.dir();
            loadWavesFrom(campaign.dir());
            status = "已打开战役「" + campaign.name() + "」";
            System.out.println("[EditorApp] 启动载入战役: " + campaign.dir());
            return loaded;
        } catch (IOException e) {
            System.err.println("[EditorApp] 战役「" + campaign.id() + "」地图载入失败: "
                    + e.getMessage());
            return null;
        }
    }

    /**
     * 启动时优先打开的战役名（同时也是目录名）。
     *
     * <p>改名只需改这一处；文档里提到的 {@code map_001} 是**契约示例**，与此无关。
     */
    public static final String DEMO_MAP_NAME = "演示地图";

    private PImage loadEasterEgg() {
        Path path = Workspace.animAsset(AssetPaths.EASTER_EGG_IMAGE);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return loadImage(path.toString());
        } catch (RuntimeException e) {
            System.err.println("[EditorApp] 彩蛋素材加载失败: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void draw() {
        waveActions.clear();     // 每帧重建动作表（侧栏与波次面板共用这个池）
        switch (phase) {
            case LOADING -> {
                drawLoadingBuffered();
                if (loadingView.isFinished()) {
                    enterBlack();
                }
            }
            case BLACK -> drawBlackTransition();
            case EDITING -> drawEditor();
        }
    }

    // ==================================================================
    // 编辑器界面
    // ==================================================================

    private void drawEditor() {
        // 白底 + 深色文字：与加载界面/主菜单的深色相反，见类注释
        background(UITheme.WINDOW_BG_EDITOR);

        // ⚠️ 清空按钮列表必须在这里，**不能**放到某个 drawXxx 里面 ——
        // 各区的绘制顺序就是登记顺序，清空若晚于第一个区的绘制，那个区的按钮会被抹掉。
        // （实测踩过：clear 放在 drawSidebar 里，工具栏的按钮全被清光，于是"点不动"。）
        buttonList.clear();

        // 编辑器界面照常画 —— **面板是叠在它上面的**，不是替换它。
        // ⚠️ 这里踩过坑：面板开着时曾用 `return` 提前结束整帧绘制，
        // 于是底下什么都没画、只剩一层灰蒙层，看起来像"跳到了另一个窗口"。
        // 模态是"盖住 + 拦截输入"，不是"不画"。
        drawHeader();
        drawMapArea();
        drawSidebar();
        drawWavePanel();
        drawStatusBar();

        // 面板画在最上层；它自己会先铺一层压暗的蒙层
        if (newMapPanel != null) {
            newMapPanel.draw(mouseX, mouseY);
        }

        updateCursor();

        // 诊断：按钮登记数变了才打一行，避免每帧刷屏
        if (DEBUG_UI && buttonList.size() != lastLoggedButtonCount) {
            lastLoggedButtonCount = buttonList.size();
            System.out.printf("[UI] 本帧登记按钮 %d 个（工具栏 %d + 地形 %d + 擦除 1 + 门 %d）"
                            + "  地图上的门=%d%n",
                    buttonList.size(), headerButtons.size() + 1, toolButtons.size(),
                    Math.max(0, buttonList.size() - headerButtons.size() - 1
                            - toolButtons.size() - 1),
                    allDoors().size());
        }
    }

    /** 上一次打过的按钮数，只在变化时输出。 */
    private int lastLoggedButtonCount = -1;

    /**
     * 鼠标光标提示。
     *
     * <p>判据是<strong>光标下的格子在地图范围内</strong>，而不是"光标在地图区矩形内" ——
     * 地图是居中放在地图区画布里的，四周有留白；那些留白上不该给手形提示。
     *
     * <p>面板开着时只给面板自己的按钮手形（它是模态的）；否则给
     * <strong>所有可点的按钮</strong> —— 手形是"这里能点"的统一信号，
     * 只给其中几个会让人觉得别的都点不了。
     */
    private void updateCursor() {
        if (newMapPanel != null) {
            cursor(newMapPanel.isOverClickable(mouseX, mouseY)
                    ? PConstants.HAND : PConstants.ARROW);
            return;
        }
        cursor(isOverMap() || isOverAnyButton() ? PConstants.HAND : PConstants.ARROW);
    }

    /**
     * @return 光标是否落在某个按钮上（工具栏 / 侧栏 / 门列表）
     */
    private boolean isOverAnyButton() {
        for (UiButton b : buttonList) {
            if (b.isHovered(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return 光标是否落在地图的某一格上
     */
    private boolean isOverMap() {
        if (viewport == null || !viewport.containsPointer(mouseX, mouseY)) {
            return false;
        }
        int[] cell = viewport.screenToCell(mouseX, mouseY);
        return viewport.isInsideMap(cell[0], cell[1]);
    }

    // ---- ① 顶部工具栏 ----

    private void drawHeader() {
        LayoutRegions.Region r = LayoutRegions.header();

        noStroke();
        fill(UITheme.PANEL_BG_LIGHT);
        rect(r.x(), r.y(), r.width(), r.height());

        // ⚠️ 画完必须 add 进 buttonList —— 列表既是命中判定的依据，也是手形光标的依据。
        // 只画不登记的话，按钮长得像能点、实际点不动（这个 bug 实测踩过）。
        for (int i = 0; i < headerButtons.size(); i++) {
            float[] b = headerButtonBox(i);
            UiButton btn = headerButtons.get(i).at(b[0], b[1], b[2], b[3]);
            btn.draw(mouseX, mouseY, false, false, true);
            buttonList.add(btn);
        }

        // 右侧：测试按钮（主按钮，黄色三态）
        float[] testBox = headerButtonBox(BTN_TEST);
        testButton.at(testBox[0], testBox[1], testBox[2], testBox[3])
                .draw(mouseX, mouseY, false, true, true);
        buttonList.add(testButton);

        // 中间：地图名
        fill(UITheme.TEXT_DIM_ON_LIGHT);
        textFont(font);
        textAlign(PConstants.RIGHT, PConstants.CENTER);
        text("地图: " + map.name(), testBox[0] - UITheme.SPACE_4, r.centerY());
        textAlign(PConstants.LEFT, PConstants.BASELINE);
    }

    // ---- ② 地图区 ----

    private void drawMapArea() {
        LayoutRegions.Region r = LayoutRegions.mapArea();

        noStroke();
        fill(255, 255, 255, 255);
        rect(r.x(), r.y(), r.width(), r.height());

        clip(r.x(), r.y(), r.width(), r.height());
        gridRenderer.draw(map, viewport);

        // 悬停高亮只在**地图范围内**画。画在留白上会让人以为那里也是地图的一格，
        // 而且越界的格子在渲染上会被画到地图外面去。
        if (isOverMap()) {
            int[] hoverCell = viewport.screenToCell(mouseX, mouseY);
            gridRenderer.drawHover(viewport, hoverCell[0], hoverCell[1], true);
        }
        noClip();

        // 地图区边框
        noFill();
        stroke(UITheme.LINE_ON_LIGHT);
        strokeWeight(1f);
        rect(r.x(), r.y(), r.width(), r.height());
        noStroke();

        // 左下角操作提示
        int[] cell = viewport.screenToCell(mouseX, mouseY);
        fill(UITheme.TEXT_DIM_ON_LIGHT);
        textFont(font);
        textAlign(PConstants.LEFT, PConstants.BOTTOM);
        text(String.format("缩放 %.0f%%   左键放置 / 右键擦除   滚轮缩放   中键平移   光标 (%d, %d)   [R 复位]",
                        viewport.scale() * 100, cell[0], cell[1]),
                r.x() + UITheme.SPACE_2, r.bottom() - UITheme.SPACE_1);
    }

    // ---- ③ 属性栏 ----

    private void drawSidebar() {
        LayoutRegions.Region r = LayoutRegions.sidebar();

        noStroke();
        fill(UITheme.PANEL_BG_LIGHT);
        rect(r.x(), r.y(), r.width(), r.height());

        float x = r.x() + UITheme.SPACE_3;
        float y = r.y() + UITheme.SPACE_3;
        float contentW = r.width() - UITheme.SPACE_3 * 2;

        // --- 地形工具 ---
        y = drawSectionTitle("地形工具", x, y, contentW);
        float[] m = sidebarMetrics();
        float btnW = m[3];
        float btnH = TOOL_BTN_H;
        for (int i = 0; i < TOOLS.length; i++) {
            float bx = x + (i % 2) * (btnW + UITheme.SPACE_2);
            float by = y + (i / 2) * (btnH + UITheme.SPACE_1);
            boolean active = editMode.toolIndex() == i;
            toolButtons.get(i).at(bx, by, btnW, btnH).draw(mouseX, mouseY, active, false, true);
            buttonList.add(toolButtons.get(i));
        }
        y += 3 * (btnH + UITheme.SPACE_1);

        // 擦除（开关：开着时左键 = 擦除，方便拖着擦一片）
        eraserButton.at(x, y, contentW, btnH).draw(mouseX, mouseY, editMode.isEraser(), false, true);
        buttonList.add(eraserButton);
        y += btnH + UITheme.SPACE_3;

        // --- 门 ---
        y = drawSectionTitle("门", x, y, contentW);
        List<DoorRef> doors = allDoors();
        if (doors.isEmpty()) {
            fill(UITheme.TEXT_DIM_ON_LIGHT);
            textFont(font);
            textAlign(PConstants.LEFT, PConstants.CENTER);
            text("还没有放置任何门", x, y + DOOR_ROW_H / 2f);
            textAlign(PConstants.LEFT, PConstants.BASELINE);
        } else {
            for (int i = 0; i < doors.size(); i++) {
                DoorRef ref = doors.get(i);
                float[] b = doorRowBox(i);
                // 选中判定必须**同时**看 id 与红/蓝 —— 两者 id 独立编号，只看 id 会把
                // 「红门 #0」和「蓝门 #0」一起点亮（实测踩过）
                boolean selected = ref.d().id() == selectedDoorId
                        && ref.red() == selectedIsRedDoor;
                UiButton btn = doorButton(i).at(b[0], b[1], b[2], b[3])
                        .text(doorLabel(ref));
                btn.draw(mouseX, mouseY, selected, false, true);
                buttonList.add(btn);
            }
        }
        y += Math.max(1, doors.size()) * (DOOR_ROW_H + 2f) + UITheme.SPACE_3;

        // --- 地图参数 ---
        y = drawSectionTitle("地图参数", x, y, contentW);
        fill(UITheme.TEXT_ON_LIGHT);
        textFont(font);
        textAlign(PConstants.LEFT, PConstants.CENTER);
        text(String.format("列 %d    行 %d    格子 %d px", map.cols(), map.rows(), map.tileSize()),
                x, y + 12f);
        text(String.format("红门 %d / 2    蓝门 %d / 2",
                        map.redDoors().size(), map.blueDoors().size()),
                x, y + 34f);
        textAlign(PConstants.LEFT, PConstants.BASELINE);
        y += 56f;

        // --- 关卡参数 ---
        y = drawSectionTitle("关卡参数", x, y, contentW);
        textFont(font);
        fill(UITheme.TEXT_ON_LIGHT);
        textAlign(PConstants.LEFT, PConstants.BASELINE);

        // 生命
        text("生命", x, y + 16f);
        addWaveButton(x + 90f, y + 2f, 26f, 20f, "-", false, () ->
                params = withLife(params, params.life() - 1));
        textAlign(PConstants.CENTER, PConstants.BASELINE);
        text(String.valueOf(params.life()), x + 143f, y + 16f);
        textAlign(PConstants.LEFT, PConstants.BASELINE);
        addWaveButton(x + 170f, y + 2f, 26f, 20f, "+", false, () ->
                params = withLife(params, params.life() + 1));
        y += 28f;

        // 初始费用
        text("初始费用", x, y + 16f);
        addWaveButton(x + 90f, y + 2f, 26f, 20f, "-", false, () ->
                params = withCost(params, params.initialCost() - 1));
        textAlign(PConstants.CENTER, PConstants.BASELINE);
        text(String.valueOf(params.initialCost()), x + 143f, y + 16f);
        textAlign(PConstants.LEFT, PConstants.BASELINE);
        addWaveButton(x + 170f, y + 2f, 26f, 20f, "+", false, () ->
                params = withCost(params, params.initialCost() + 1));
        y += 28f;

        // 每秒回费
        text("每秒回费", x, y + 16f);
        addWaveButton(x + 90f, y + 2f, 26f, 20f, "-", false, () ->
                params = withRate(params, params.costPerSecond() - 0.1f));
        textAlign(PConstants.CENTER, PConstants.BASELINE);
        text(String.format("%.1f", params.costPerSecond()), x + 143f, y + 16f);
        textAlign(PConstants.LEFT, PConstants.BASELINE);
        addWaveButton(x + 170f, y + 2f, 26f, 20f, "+", false, () ->
                params = withRate(params, params.costPerSecond() + 0.1f));
    }

    /**
     * @param p    原参数
     * @param life 新生命（会被夹到 ≥1）
     * @return 改好生命的新参数
     */
    private com.yestercar.common.model.LevelParams withLife(
            com.yestercar.common.model.LevelParams p, int life) {
        return new com.yestercar.common.model.LevelParams(p.initialCost(), p.costPerSecond(),
                p.obstacleCost(), p.obstacleHp(), p.obstacleBlock(), p.obstacleCount(),
                p.obstacleRedeployTime(), Math.max(1, life));
    }

    /**
     * @param p    原参数
     * @param cost 新初始费用（≥0）
     * @return 改好的新参数
     */
    private com.yestercar.common.model.LevelParams withCost(
            com.yestercar.common.model.LevelParams p, int cost) {
        // 初始费用夹到 0–99（与战斗内费用上限一致）
        return new com.yestercar.common.model.LevelParams(Math.max(0, Math.min(99, cost)), p.costPerSecond(),
                p.obstacleCost(), p.obstacleHp(), p.obstacleBlock(), p.obstacleCount(),
                p.obstacleRedeployTime(), p.life());
    }

    /**
     * @param p    原参数
     * @param rate 新回费（0–10）
     * @return 改好的新参数
     */
    private com.yestercar.common.model.LevelParams withRate(
            com.yestercar.common.model.LevelParams p, float rate) {
        return new com.yestercar.common.model.LevelParams(p.initialCost(),
                Math.max(0f, Math.min(10f, Math.round(rate * 10f) / 10f)),
                p.obstacleCost(), p.obstacleHp(), p.obstacleBlock(), p.obstacleCount(),
                p.obstacleRedeployTime(), p.life());
    }

    /** 画一个小节标题，返回下一行的 y。 */
    private float drawSectionTitle(String title, float x, float y, float width) {
        fill(UITheme.COLOR_PRIMARY);
        textFont(font);
        textAlign(PConstants.LEFT, PConstants.TOP);
        text(title, x, y);
        stroke(UITheme.LINE_ON_LIGHT);
        strokeWeight(1f);
        line(x, y + 20f, x + width, y + 20f);
        noStroke();
        return y + 30f;
    }

    // ---- ④ 波次列表 ----

    private void drawWavePanel() {
        LayoutRegions.Region r = LayoutRegions.wavePanel();

        noStroke();
        fill(UITheme.PANEL_BG_LIGHT);
        rect(r.x(), r.y(), r.width(), r.height());

        fill(UITheme.COLOR_PRIMARY);
        textFont(font);
        textAlign(PConstants.LEFT, PConstants.TOP);
        text("波次列表", r.x() + UITheme.SPACE_3, r.y() + UITheme.SPACE_2);

        fill(UITheme.TEXT_DIM_ON_LIGHT);
        text("共 " + waves.size() + " 波 / " + waves.totalCount() + " 只",
                r.x() + 96, r.y() + UITheme.SPACE_2 + 2);

        // 本帧重建**动作表**，但按钮对象必须复用。
        // ⚠️ 不能每帧 clear：activateButton 是按对象身份分派的，
        //    池一清空就每帧 new 一批新对象，鼠标松开时拿的是上一帧的旧对象，
        //    永远匹配不上 —— 表现就是"波次面板的按钮点了没反应"。
        float x0 = r.x() + UITheme.SPACE_3;
        float rowY = r.y() + 30;
        float chipW = 136f;
        float chipH = 26f;
        java.util.List<com.yestercar.common.model.Wave> list = waves.waves();

        // ---- 波次小标签（横向排列，点选） ----
        // 每页最多 3 个波次，多了用 ◀ ▶ 翻页（与出怪组的 3 行一致）
        int perPage = 3;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        wavePage = Math.max(0, Math.min(wavePage, pages - 1));
        int from = wavePage * perPage;
        int to = Math.min(list.size(), from + perPage);
        for (int i = from; i < to; i++) {
            float cx = x0 + (i - from) * (chipW + 6f);
            com.yestercar.common.model.Wave w = list.get(i);
            int idx = i;
            addWaveButton(cx, rowY, chipW, chipH,
                    "#" + (i + 1) + "  t=" + String.format("%.1f", w.time())
                            + "s  " + w.totalCount() + "只",
                    i == selectedWave, () -> {
                        selectedWave = idx;
                        status = "选中波次 #" + (idx + 1);
                    });
        }

        // ---- 翻页 ----
        float navX = x0 + perPage * (chipW + 6f);
        addWaveButton(navX, rowY, 26f, chipH, "<", false,
                () -> wavePage = Math.max(0, wavePage - 1));
        addWaveButton(navX + 30f, rowY, 26f, chipH, ">", false,
                () -> wavePage = Math.min(pages - 1, wavePage + 1));
        textFont(font);
        fill(UITheme.TEXT_DIM_ON_LIGHT);
        text((wavePage + 1) + "/" + pages, navX + 62f, rowY + 17);

        // ---- 新增 / 删除 ----
        float addX = navX + 100f;
        addWaveButton(addX, rowY, 64f, chipH, "+ 波次", false, () -> {
            selectedWave = waves.addWave();
            status = "新增波次 #" + (selectedWave + 1);
        });
        addWaveButton(addX + 70f, rowY, 64f, chipH, "删除", false, () -> {
            if (waves.removeWave(selectedWave)) {
                selectedWave = Math.max(0, Math.min(selectedWave, waves.size() - 1));
                status = "已删除波次";
            } else {
                status = "没有可删除的波次";
            }
        });

        // ---- 选中波次的出怪组 ----
        com.yestercar.common.model.Wave sel = waves.wave(selectedWave);
        if (sel == null) {
            textFont(font);
            fill(UITheme.TEXT_DIM_ON_LIGHT);
            text("（还没有波次，点「+ 波次」新建）", x0, rowY + 44);
            textAlign(PConstants.LEFT, PConstants.BASELINE);
            return;
        }

        float y = rowY + 34;
        textFont(font);
        fill(UITheme.TEXT_ON_LIGHT);
        // 输入框外观：白底 + 灰边（编辑中金色边），让用户知道这里可以直接打字
        noStroke();
        fill(255, 255, 255);
        rect(x0 - 4f, y + 2f, 86f, 20f, 3f);
        noFill();
        stroke(numEditKind == 1 ? UITheme.SIGNAL_ACTION : 0xFF222222, 255f);
        strokeWeight(numEditKind == 1 ? 2f : 1f);
        rect(x0 - 4f, y + 2f, 86f, 20f, 3f);
        noStroke();
        fill(UITheme.TEXT_ON_LIGHT);
        String timeText = (numEditKind == 1)
                ? "时刻 " + numBuf + "s"
                : "时刻 " + String.format("%.1f", sel.time()) + "s";
        text(timeText, x0, y + 16);
        addNumField(1, -1, x0 - 4f, y + 2f, 86f, 20f);
        final float baseTime = sel.time();
        addWaveButton(x0 + 92f, y, 34f, 22f, "-", false, () ->
                waves.setWaveTime(selectedWave, baseTime - 1f));
        addWaveButton(x0 + 130f, y, 34f, 22f, "+", false, () ->
                waves.setWaveTime(selectedWave, baseTime + 1f));

        float sx = x0 + 184f;
        final float rowH = 41f;             // 一行出怪组的高度（含图标 35px）
        final float iconPx = 35f;
        // 每屏最多 3 行，多出来的靠 ▲▼ 滚动
        int maxRows = 3;
        spawnScroll = Math.max(0, Math.min(spawnScroll,
                Math.max(0, sel.spawns().size() - maxRows)));
        for (int i = spawnScroll; i < sel.spawns().size() && i < spawnScroll + maxRows; i++) {
            com.yestercar.common.model.Wave.EnemySpawn sp = sel.spawns().get(i);
            int si = i;
            float cx = sx;

            // 敌人图片 + 名字（点图标看详情）。⚠️ 图必须画在按钮**之后**，
            //    否则会被 UiButton 的白色底整个盖住（截图里只剩一个白方块）。
            final String enemyOfRow = sp.enemy();
            addWaveButton(cx, y, iconPx, iconPx, "", false, () -> {
                detailEnemy = enemyOfRow;
                status = "查看敌人：" + enemyName(enemyOfRow);
            });
            processing.core.PImage ic = editorEnemyIcon(sp.enemy());
            if (ic != null) {
                imageMode(PConstants.CORNER);
                image(ic, cx + 1, y + 1, iconPx, iconPx);
            }
            cx += iconPx + 6f;

            final float rowYFinal = y;
            final float enemyBtnX = cx;
            addWaveButton(cx, y, 92f, 22f, fitLabel(enemyName(sp.enemy()), 88f), false, () -> {
                dropKind = "enemy";
                dropWave = selectedWave;
                dropSpawn = si;
                dropScroll = 0;
                dropX = enemyBtnX;
                dropY = rowYFinal + 22f;
            });
            cx += 96f;
            final float redBtnX = cx;
            addWaveButton(cx, y, 52f, 22f, "红" + doorIdText(map.redDoors(), sp.door()), false, () -> {
                dropKind = "red";
                dropWave = selectedWave;
                dropSpawn = si;
                dropScroll = 0;
                dropX = redBtnX;
                dropY = rowYFinal + 22f;
            });
            cx += 52f;
            final float blueBtnX = cx;
            addWaveButton(cx, y, 52f, 22f, "蓝" + doorIdText(map.blueDoors(), sp.targetBlueDoor()), false, () -> {
                dropKind = "blue";
                dropWave = selectedWave;
                dropSpawn = si;
                dropScroll = 0;
                dropX = blueBtnX;
                dropY = rowYFinal + 22f;
            });
            cx += 54f;
            textFont(font);
            fill(UITheme.TEXT_ON_LIGHT);
            noStroke();
            fill(255, 255, 255);
            rect(cx - 4f, y + 2f, 30f, 20f, 3f);
            noFill();
            stroke((numEditKind == 2 && numEditIdx == si)
                    ? UITheme.SIGNAL_ACTION : 0xFF222222, 255f);
            strokeWeight((numEditKind == 2 && numEditIdx == si) ? 2f : 1f);
            rect(cx - 4f, y + 2f, 30f, 20f, 3f);
            noStroke();
            fill(UITheme.TEXT_ON_LIGHT);
            text((numEditKind == 2 && numEditIdx == si)
                    ? "x" + numBuf : "x" + sp.count(), cx, y + 16);
            addNumField(2, si, cx - 4f, y + 2f, 30f, 20f);
            addWaveButton(cx + 26f, y, 22f, 22f, "-", false,
                    () -> waves.bumpCount(selectedWave, si, -1));
            addWaveButton(cx + 50f, y, 22f, 22f, "+", false,
                    () -> waves.bumpCount(selectedWave, si, 1));
            cx += 80f;
            text("每" + String.format("%.1f", sp.interval()) + "s", cx, y + 16);
            addWaveButton(cx + 52f, y, 22f, 22f, "-", false,
                    () -> waves.bumpInterval(selectedWave, si, -0.1f));
            addWaveButton(cx + 76f, y, 22f, 22f, "+", false,
                    () -> waves.bumpInterval(selectedWave, si, 0.1f));
            cx += 106f;
            addWaveButton(cx, y, 22f, 22f, "X", false, () -> {
                waves.removeSpawn(selectedWave, si);
                status = "已删除出怪组";
            });

            y += rowH;
        }

        addWaveButton(sx, y, 88f, 22f, "+ 出怪组", false, () -> {
            waves.addSpawn(selectedWave);
            status = "已新增出怪组";
        });

        // 出怪组多于 3 行：滚动箭头
        if (sel.spawns().size() > maxRows) {
            float ay = rowY + 34 + rowH * maxRows + 4;
            addWaveButton(sx + 400f, ay, 26f, 20f, "^", false,
                    () -> spawnScroll = Math.max(0, spawnScroll - 1));
            addWaveButton(sx + 430f, ay, 26f, 20f, "v", false,
                    () -> spawnScroll = Math.min(
                            Math.max(0, sel.spawns().size() - maxRows), spawnScroll + 1));
            textFont(font);
            fill(UITheme.TEXT_DIM_ON_LIGHT);
            text((spawnScroll + 1) + "-"
                            + Math.min(sel.spawns().size(), spawnScroll + maxRows)
                            + " / " + sel.spawns().size(),
                    sx + 464f, ay + 15);
        }

        textAlign(PConstants.LEFT, PConstants.BASELINE);
        drawEnemyDetail(r);
        drawDropdown(r);
    }

    /**
     * 画当前打开的下拉框。
     *
     * <p>点外部取消（不选中），由 {@code mousePressed} 处理。
     *
     * @param r 波次区矩形
     */
    private void drawDropdown(LayoutRegions.Region r) {
        dropButtons.clear();
        dropItems.clear();
        if (dropKind == null) {
            return;
        }
        java.util.List<String> items = new ArrayList<>();
        if ("enemy".equals(dropKind)) {
            for (String id : enemyIdList()) {
                dropItems.add(id);
                items.add(enemyName(id));
            }
        } else {
            // 门是**动态**的：擦掉再放会得到新的 id，编号会往后顺延。
            // 所以这里每次都从地图上现读，标签用真实 id，值仍是"第几个门"。
            java.util.List<Door> doors = "red".equals(dropKind) ? map.redDoors() : map.blueDoors();
            String prefix = "red".equals(dropKind) ? "红门 #" : "蓝门 #";
            for (Door d : doors) {
                items.add(prefix + d.id());
            }
        }
        if (items.isEmpty()) {
            dropKind = null;
            return;
        }

        float itemW = "enemy".equals(dropKind) ? 150f : 110f;
        float itemH = 24f;
        int maxVisible = 6;
        int show = Math.min(items.size(), maxVisible);
        int maxScroll = Math.max(0, items.size() - maxVisible);
        dropScroll = Math.max(0, Math.min(dropScroll, maxScroll));
        // 锚定到被点按钮的**左下角**（之前写死在区域右侧，离按钮很远）
        float x = dropX;
        float y = dropY;
        // 下面放不下就翻到按钮上方
        if (y + show * itemH > r.bottom()) {
            y = dropY - 22f - show * itemH;
        }

        noStroke();
        fill(255, 255, 255, 248);
        rect(x - 2, y - 2, itemW + 4, show * itemH + 4, 3f);
        stroke(UITheme.LINE_ON_LIGHT);
        strokeWeight(1f);
        noFill();
        rect(x - 2, y - 2, itemW + 4, show * itemH + 4, 3f);
        noStroke();

        for (int i = 0; i < show; i++) {
            final int idx = dropScroll + i;
            final String item = items.get(idx);
            addWaveButton(x, y + i * itemH, itemW, itemH - 2,
                    fitLabel(item, itemW - 8f), false, () -> {
                        applyDrop(item, idx);
                        dropKind = null;
                    });
            // dropButtons 与 waveButtons 是同一批对象；这里补记一下用于"点外部取消"判断
            dropButtons.add(waveButtons.get(waveButtons.size() - 1));
        }

        dropRect[0] = x - 4f;
        dropRect[1] = y - 4f;
        dropRect[2] = itemW + 30f;
        dropRect[3] = show * itemH + 8f;

        // 滚动条（选项多于 6 个时出现）
        if (items.size() > maxVisible) {
            addWaveButton(x + itemW + 4f, y, 20f, show * itemH / 2f - 2f, "^", false,
                    () -> dropScroll = Math.max(0, dropScroll - 1));
            dropButtons.add(waveButtons.get(waveButtons.size() - 1));
            addWaveButton(x + itemW + 4f, y + show * itemH / 2f, 20f,
                    show * itemH / 2f - 2f, "v", false,
                    () -> dropScroll = Math.min(maxScroll, dropScroll + 1));
            dropButtons.add(waveButtons.get(waveButtons.size() - 1));
        }
    }

    /**
     * 应用下拉框选择结果。
     *
     * @param item 选中的文本
     * @param idx  选项下标
     */
    private void applyDrop(String item, int idx) {
        if ("enemy".equals(dropKind)) {
            String id = idx < dropItems.size() ? dropItems.get(idx) : item;
            waves.setSpawnEnemy(dropWave, dropSpawn, id, enemyIdList());
            status = "敌人 -> " + item;
            return;
        }
        if ("red".equals(dropKind)) {
            com.yestercar.common.model.Wave.EnemySpawn sp = spawnAt(dropWave, dropSpawn);
            if (sp != null) {
                waves.setSpawnDoors(dropWave, dropSpawn, idx, sp.targetBlueDoor());
            }
            status = "红门 -> #" + idx;
            return;
        }
        com.yestercar.common.model.Wave.EnemySpawn sp = spawnAt(dropWave, dropSpawn);
        if (sp != null) {
            waves.setSpawnDoors(dropWave, dropSpawn, sp.door(), idx);
        }
        status = "蓝门 -> #" + idx;
    }

    /**
     * @param waveIndex  波次下标
     * @param spawnIndex 组下标
     * @return 出怪组；越界返回 {@code null}
     */
    private com.yestercar.common.model.Wave.EnemySpawn spawnAt(int waveIndex, int spawnIndex) {
        com.yestercar.common.model.Wave w = waves.wave(waveIndex);
        if (w == null || spawnIndex < 0 || spawnIndex >= w.spawns().size()) {
            return null;
        }
        return w.spawns().get(spawnIndex);
    }

    /**
     * 登记一个数值输入框的命中区。
     *
     * @param kind 1=波次时刻 2=出怪数量
     * @param idx  出怪组下标（时刻用 -1）
     * @param x    左
     * @param y    上
     * @param w    宽
     * @param h    高
     */
    private void addNumField(int kind, int idx, float x, float y, float w, float h) {
        numFields.add(new float[]{kind, idx, x, y, w, h});
    }

    /** 提交当前数值输入。 */
    private void commitNumEdit() {
        if (numEditKind == 0 || numBuf.length() == 0) {
            numEditKind = 0;
            numBuf.setLength(0);
            return;
        }
        try {
            if (numEditKind == 1) {
                float v = Float.parseFloat(numBuf.toString());
                v = Math.max(0.1f, Math.min(999.9f, v));
                waves.setWaveTime(selectedWave, v);
            } else {
                int v = Integer.parseInt(numBuf.toString());
                v = Math.max(1, Math.min(99, v));
                int delta = v - waves.waves().get(selectedWave).spawns()
                        .get(numEditIdx).count();
                waves.bumpCount(selectedWave, numEditIdx, delta);
            }
            status = "已输入数值";
        } catch (RuntimeException ex) {
            status = "输入无效，已忽略";
        }
        numEditKind = 0;
        numBuf.setLength(0);
    }

    /**
     * 加一个波次面板按钮（登记进 buttonList 并记下动作）。
     *
     * @param x        x
     * @param y        y
     * @param w        宽
     * @param h        高
     * @param label    文字
     * @param selected 是否高亮
     * @param action   点击动作
     */
    /**
     * 按像素宽度裁剪文字（超出加省略号）。
     *
     * @param text 原文
     * @param maxW 可用宽度（像素）
     * @return 裁剪后的文字
     */
    private String fitLabel(String text, float maxW) {
        if (text == null) {
            return "";
        }
        textFont(font);
        if (textWidth(text) <= maxW) {
            return text;
        }
        String t = text;
        while (t.length() > 1 && textWidth(t + "..") > maxW) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "..";
    }

    /**
     * 画选中敌人的详情卡（与游戏里点干员那个面板同一套信息，但**没有撤退**）。
     *
     * @param r   波次区矩形
     */
    private void drawEnemyDetail(LayoutRegions.Region r) {
        if (detailEnemy == null) {
            return;
        }
        com.yestercar.runtime.battle.EnemyType t = enemyTypes().get(detailEnemy);
        if (t == null) {
            detailEnemy = null;
            return;
        }
        float w = 300f;
        float h = 178f;
        float x = r.right() - w - 8f;
        float y = r.y() + 30f;

        noStroke();
        fill(255, 255, 255, 210);
        rect(x, y, w, h, 4f);
        stroke(UITheme.LINE_ON_LIGHT);
        strokeWeight(1f);
        noFill();
        rect(x, y, w, h, 4f);
        noStroke();

        // 图标 + 名字
        processing.core.PImage img = editorEnemyIcon(t.id());
        float iconSize = 96f;
        if (img != null) {
            imageMode(PConstants.CORNER);
            image(img, x + 10, y + 10, iconSize, iconSize);
        }
        fill(UITheme.TEXT_ON_LIGHT);
        textFont(font);
        textAlign(PConstants.LEFT, PConstants.TOP);
        text(fitLabel(t.name(), w - iconSize - 28f), x + iconSize + 22, y + 14);

        textFont(font);
        fill(UITheme.TEXT_DIM_ON_LIGHT);
        text("HP " + t.hp() + "   攻 " + t.atk() + "   防 " + t.def(),
                x + 10, y + 120);
        text("法抗 " + t.res() + "   速 " + t.speed() + "   间隔 " + t.attackInterval() + "s",
                x + 10, y + 138);
        text("阻 " + t.blockWeight() + "   射程 "
                        + (t.isRanged() ? (t.rangeRadius() + " 格") : "近战"),
                x + 10, y + 156);
        textAlign(PConstants.LEFT, PConstants.BASELINE);
    }

    private void addWaveButton(float x, float y, float w, float h, String label,
                               boolean selected, Runnable action) {
        while (waveButtons.size() <= waveActions.size()) {
            waveButtons.add(new UiButton(this));
        }
        UiButton btn = waveButtons.get(waveActions.size()).at(x, y, w, h).text(label);
        btn.draw(mouseX, mouseY, selected, false, true);
        buttonList.add(btn);
        // 每次波次编辑都单独记一笔历史：beginStroke → 改 → commit
        waveActions.add(() -> {
            undoManager.beginStroke();
            action.run();
            undoManager.noteChange(true);
            undoManager.commit("波次：" + label);
        });
    }

    /**
     * @return 可选的敌人 id（按数值表顺序）
     */
    /** 敌人数值表缓存。⚠️ 不要每帧 loadAll() —— 那会每帧读一次 CSV 并解析，编辑器帧率会被拖死。 */
    private static java.util.Map<String, com.yestercar.runtime.battle.EnemyType> enemyTypeCache;

    /** 当前打开的下拉框：{@code null} / "enemy" / "red" / "blue"。 */
    private String dropKind;

    /** 下拉框弹出的锚点（被点按钮的左下角），在按钮的 lambda 里记下。 */
    private float dropX;
    private float dropY;

    /** 下拉框对应的波次与出怪组。 */
    private int dropWave;
    private int dropSpawn;

    /** 下拉框选项对应的**值**（显示的是中文名，值的 id）。 */
    private final List<String> dropItems = new ArrayList<>();

    /** 下拉框里的按钮（点外部取消时要判断"有没有点在选项上"）。 */
    private final List<UiButton> dropButtons = new ArrayList<>();

    /** 弹出框矩形（屏幕坐标），用于"点在框内就别当外部点击"。 */
    private final float[] dropRect = new float[4];

    /** 下拉框内部滚动（每屏 6 项）。 */
    private int dropScroll;

    /** 波次分页（每页 3 个）。 */
    private int wavePage;

    /** 出怪组垂直滚动（每屏最多 3 行）。 */
    private int spawnScroll;

    /** 数值输入：正在编辑的字段（0=无，1=波次时刻，2=出怪数量）。 */
    private int numEditKind;

    /** 正在编辑的字段对应的出怪组下标。 */
    private int numEditIdx;

    /** 输入缓冲。 */
    private final StringBuilder numBuf = new StringBuilder();

    /** 数值输入框的命中区：{kind, idx, x, y, w, h}（窗口坐标，每帧重建）。 */
    private final java.util.List<float[]> numFields = new java.util.ArrayList<>();

    /** 波次列表水平滚动。 */
    private int waveScroll;

    /** 选中的敌人（详情卡用）；{@code null} = 没选。 */
    private String detailEnemy;

    /**
     * @return 敌人数值表（首次调用读盘，之后走缓存）
     */
    private java.util.Map<String, com.yestercar.runtime.battle.EnemyType> enemyTypes() {
        if (enemyTypeCache == null) {
            enemyTypeCache = com.yestercar.runtime.battle.EnemyType.loadAll();
        }
        return enemyTypeCache;
    }

    /** 敌人图片缓存（key = 敌人 id）。 */
    private final java.util.Map<String, processing.core.PImage> enemyIconCache =
            new java.util.HashMap<>();

    /**
     * 取某个敌人的图片（{@code data/imgs/enemy/<名称>.png}）。
     *
     * @param enemyId 敌人 id
     * @return 图片；没有或读不到返回 {@code null}
     */
    private processing.core.PImage editorEnemyIcon(String enemyId) {
        if (enemyId == null) {
            return null;
        }
        if (enemyIconCache.containsKey(enemyId)) {
            return enemyIconCache.get(enemyId);
        }
        com.yestercar.runtime.battle.EnemyType t = enemyTypes().get(enemyId);
        processing.core.PImage img = t == null || t.icon() == null ? null : loadImage(t.icon());
        enemyIconCache.put(enemyId, img);
        return img;
    }

    /**
     * @param id 敌人 id
     * @return 中文显示名；查不到时退回 id
     */
    private String enemyName(String id) {
        com.yestercar.runtime.battle.EnemyType t = enemyTypes().get(id);
        return t == null || t.name().isBlank() ? id : t.name();
    }

    /**
     * @param doors 门列表
     * @param index 下标
     * @return 该下标的门的真实 id；越界返回 {@code "—"}
     */
    private String doorIdText(java.util.List<Door> doors, int index) {
        return index >= 0 && index < doors.size() ? String.valueOf(doors.get(index).id()) : "未择";
    }

    private List<String> enemyIdList() {
        return new ArrayList<>(enemyTypes().keySet());
    }

    /**
     * 从一个战役目录载入波次表。
     *
     * @param dir 战役目录（{@code null} 表示没有，清空波次）
     */
    /**
     * 把波次挂到撤销栈上（只挂一次）。
     *
     * <p>放在 {@link #loadWavesFrom} 里调用，是因为启动载入与手动打开两条路径都会经过它，
     * 且那时 {@code undoManager} 一定已经建好了。
     */
    private void ensureWaveUndoBound() {
        if (waveUndoBound || undoManager == null) {
            return;
        }
        waveUndoBound = true;
        undoManager.bindWaves(new com.yestercar.campaigneditor.command.UndoManager.WaveSnapshotter() {
            @Override
            public java.util.List<com.yestercar.common.model.Wave> snapshot() {
                return waves.waves();
            }

            @Override
            public void restore(java.util.List<com.yestercar.common.model.Wave> w) {
                waves = new WaveEditor(w);
                selectedWave = Math.max(0, Math.min(selectedWave, Math.max(0, waves.size() - 1)));
            }
        });
    }

    private void loadWavesFrom(java.nio.file.Path dir) {
        currentCampaignDir = dir;
        selectedWave = 0;
        waves = new WaveEditor(null);
        ensureWaveUndoBound();
        if (dir == null) {
            return;
        }
        java.nio.file.Path json = dir.resolve(com.yestercar.common.io.CampaignJsonIO.FILE_NAME);
        if (!java.nio.file.Files.isRegularFile(json)) {
            return;
        }
        try {
            com.yestercar.common.model.CampaignMeta meta =
                    com.yestercar.common.io.CampaignJsonIO.load(json);
            waves = new WaveEditor(meta.waves());
            params = meta.params();
            System.out.println("[EditorApp] 载入波次 " + waves.size()
                    + " 波 / " + waves.totalCount() + " 只");
        } catch (java.io.IOException e) {
            System.err.println("[EditorApp] 波次载入失败: " + e.getMessage());
        }
    }

    // ---- (5) 状态栏 ----

    private void drawStatusBar() {
        LayoutRegions.Region r = LayoutRegions.statusBar();

        noStroke();
        fill(UITheme.PANEL_BG_LIGHT);
        rect(r.x(), r.y(), r.width(), r.height());
        textFont(font);
        textAlign(PConstants.CENTER, PConstants.CENTER);

        // 右侧：校验结果，一眼看出能不能存
        MapValidator.ValidationResult v = MapValidator.validate(map);
        // 波次也要能存 —— 地图合法但波次坏了，同样算"不能存"
        java.util.List<WaveEditor.Problem> waveProblems = waves.validate(
                map.redDoors().size(), map.blueDoors().size(), enemyIdList());
        boolean wavesOk = waveProblems.isEmpty();
        fill(!wavesOk ? UITheme.COLOR_ACCENT
                : (v.ok() ? (v.clean() ? UITheme.COLOR_PRIMARY : UITheme.COLOR_ADVANCED)
                : UITheme.COLOR_ACCENT));
        textAlign(PConstants.RIGHT, PConstants.CENTER);
        String summary = wavesOk ? v.summary() : waveProblems.get(0).toString();
        text(summary, r.right() - UITheme.SPACE_2, r.centerY());
        float rightEdge = r.right() - UITheme.SPACE_2
                - textWidth(summary) - UITheme.SPACE_4;

        // 中右：只留"● 未保存"提示。
        // 撤销/重做计数按用户要求去掉（右下角不再显示深度）。
        if (isDirty()) {
            fill(UITheme.COLOR_ACCENT);
            textAlign(PConstants.RIGHT, PConstants.CENTER);
            text("● 未保存", rightEdge, r.centerY());
        }

        // 左侧：当前操作描述
        fill(UITheme.TEXT_ON_LIGHT);
        textAlign(PConstants.LEFT, PConstants.CENTER);
        text(status, r.x() + UITheme.SPACE_2, r.centerY());

        textAlign(PConstants.LEFT, PConstants.BASELINE);
    }







    // ==================================================================
    // 黑屏过渡
    // ==================================================================

    /**
     * 把开场动画画进半尺寸离屏缓冲，再放大铺满窗口。
     *
     * <p>高像素密度下动画只要 1/4 的像素量，既流畅又不影响编辑界面的字体清晰度。
     * ⚠️ 所有调用点都必须走这里 —— 之前有一处直接 {@code loadingView.draw()}，
     * 而缓冲目标已被置空，直接 NPE。
     */
    private void drawLoadingBuffered() {
        if (loadingView != null) {
            loadingView.draw();
        }
    }


    private void drawBlackTransition() {
        long elapsed = millis() - blackStartTime;

        if (elapsed < FADE_IN_MS) {
            drawLoadingBuffered();
            float alpha = map(elapsed, 0, FADE_IN_MS, 0, 255);
            overlayBlack(alpha);
            drawEasterEgg(alpha);
            return;
        }

        long fadeOutStart = FADE_IN_MS + BLACK_HOLD_MS;
        if (elapsed < fadeOutStart) {
            background(0);
            drawEasterEgg(255f);
            return;
        }

        if (elapsed < BLACK_TOTAL_MS) {
            drawEditor();
            float alpha = map(elapsed, fadeOutStart, BLACK_TOTAL_MS, 255, 0);
            overlayBlack(alpha);
            drawEasterEgg(alpha);
            return;
        }

        enterEditing();
    }

    /** 彩蛋必须画在黑幕**之后**，否则会被盖住。 */
    private void drawEasterEgg(float alpha) {
        if (!easterEggArmed || easterEggImage == null || alpha <= 0f) {
            return;
        }
        float h = height * 0.72f;
        float w = h * easterEggImage.width / (float) easterEggImage.height;

        imageMode(PConstants.CENTER);
        tint(255, alpha);
        image(easterEggImage, width / 2f, height / 2f, w, h);
        noTint();
        imageMode(PConstants.CORNER);
    }

    private void overlayBlack(float alpha) {
        if (alpha <= 0f) {
            return;
        }
        if (blackOverlay == null) {
            blackOverlay = createGraphics(1, 1, JAVA2D);
            blackOverlay.beginDraw();
            blackOverlay.background(0);
            blackOverlay.endDraw();
        }
        tint(255, alpha);
        image(blackOverlay, 0, 0, width, height);
        noTint();
    }

    private static float map(long value, long inMin, long inMax, float outMin, float outMax) {
        if (inMax <= inMin) {
            return outMax;
        }
        float t = (value - inMin) / (float) (inMax - inMin);
        t = t < 0f ? 0f : (t > 1f ? 1f : t);
        return outMin + (outMax - outMin) * t;
    }

    // ==================================================================
    // 阶段切换
    // ==================================================================

    private void enterBlack() {
        if (phase != Phase.LOADING) {
            return;
        }
        phase = Phase.BLACK;
        blackStartTime = millis();
    }

    private void enterEditing() {
        if (phase == Phase.EDITING) {
            return;
        }
        phase = Phase.EDITING;
        System.out.println("[EditorApp] 进入编辑器");
    }

    // ==================================================================
    // 输入
    // ==================================================================

    /**
     * 处理**已上屏的字符**（Processing 的 {@code keyTyped}）。
     *
     * <h2>⚠️ 中文输入法必须走这条路，{@code keyPressed} 收不到</h2>
     * 按 Java IMF 规范（Passive Clients 一节），画布是被动客户：
     * 输入法组合期间用的按键（拼音字母、空格/数字选字、退格）会被输入法
     * <strong>标记为 consumed</strong>，压根不产生 sketch 的 {@code keyPressed()}；
     * 上屏时才送出 {@code KEY_TYPED} —— 而 Processing 只把它交给
     * {@link PApplet#keyTyped()}，<b>不会</b>交给 {@code keyPressed()}。
     *
     * <p>所以没有这个覆写，<strong>中文一个字都进不来</strong> ——
     * 这不是"字符过滤得太严"，而是**输入通道根本没接上**。
     * 曾经的现象就是"文本框无法输入中文"，根因在此。
     *
     * <p>ASCII 字符两条路都会到（keyPressed 有 KEY_PRESSED，keyTyped 有 KEY_TYPED），
     * 所以面板那边要有防重复：见 {@code NewMapPanel.handleTyped}。
     */
    @Override
    public void keyTyped() {
        if (phase != Phase.EDITING || newMapPanel == null) {
            return;
        }
        newMapPanel.handleTyped(key);
    }

    @Override
    public void keyPressed() {
        // ---- 数值输入态：只吃数字、小数点和控制键 ----
        if (numEditKind != 0 && phase == Phase.EDITING) {
            char c = key;
            if (c >= '0' && c <= '9') {
                numBuf.append(c);
            } else if (c == '.') {
                numBuf.append(c);
            } else if (key == PConstants.BACKSPACE && numBuf.length() > 0) {
                numBuf.setLength(numBuf.length() - 1);
            } else if (key == PConstants.ENTER || key == PConstants.RETURN) {
                commitNumEdit();
            } else if (key == PConstants.ESC) {
                numEditKind = 0;
                numBuf.setLength(0);
            }
            return;
        }

        if (phase == Phase.LOADING && isEasterEggKey()) {
            easterEggArmed = easterEggImage != null;
            return;
        }
        if (phase != Phase.EDITING) {
            return;
        }

        // 面板是模态的：键盘全部归它，编辑器快捷键一律不响应
        // （否则输入名字时按 r 会顺手把视图复位了）
        if (newMapPanel != null) {
            newMapPanel.handleKey(keyCode);
            if (newMapPanel.isClosed()) {
                finishNewMapPanel();
            }
            return;
        }

        // 撤销 / 重做 / 保存：Ctrl+Z / Ctrl+Y / Ctrl+S。
        // 必须用 keyEvent 判断修饰键 —— PApplet 本身没有 isControlDown()。
        if (keyEvent != null && keyEvent.isControlDown()) {
            if (isKey('z', 'Z')) {
                doUndo();
                return;
            }
            if (isKey('y', 'Y')) {
                doRedo();
                return;
            }
            if (isKey('s', 'S')) {
                saveMap();
                return;
            }
        }

        if (key == 'r' || key == 'R') {
            viewport.resetView();
            status = "视图已复位";
        } else if (key == 'e' || key == 'E') {
            boolean nowEraser = editMode.toggleEraser();
            status = nowEraser ? "擦除：开" : "擦除：关，已切回 " + editMode.describe();
        } else if (key == '1' || key == '2' || key == '3' || key == '4' || key == '5') {
            editMode.selectTool(key - '1');
            status = "已选择工具：" + editMode.describe();
        } else if (key == ESC) {
            // 拦截 ESC：Processing 默认用 ESC 直接退进程，容易误关
            key = 0;
        }
    }

    /**
     * 判断按下的键是不是某个字母（大小写都认）。
     *
     * <p>按住 Ctrl 时 {@code key} 有时会变成控制字符而不是字母，
     * 所以 {@code keyCode} 也要看 —— 两个都对不上才算不是。
     *
     * @param lower 小写字母
     * @param upper 大写字母
     * @return 是这个键返回 {@code true}
     */
    private boolean isKey(char lower, char upper) {
        if (key == lower || key == upper) {
            return true;
        }
        return keyCode == Character.toUpperCase(lower);
    }

    /** 撤销一步，并把结果写到状态栏。 */
    private void doUndo() {
        String label = undoManager.undo();
        status = label == null ? "没有可撤销的操作" : "已撤销：" + label;
        syncDoorSelection();
        applyTitle();
    }

    /** 重做一步，并把结果写到状态栏。 */
    private void doRedo() {
        String label = undoManager.redo();
        status = label == null ? "没有可重做的操作" : "已重做：" + label;
        syncDoorSelection();
        applyTitle();
    }

    private boolean isEasterEggKey() {
        return key == EASTER_EGG_KEY || key == Character.toUpperCase(EASTER_EGG_KEY);
    }

    @Override
    public void mousePressed() {
        // ---- 数值输入框：点中就开始输入，点别处先提交 ----
        for (float[] f : numFields) {
            if (mouseX >= f[2] && mouseX <= f[2] + f[4]
                    && mouseY >= f[3] && mouseY <= f[3] + f[5]) {
                if (numEditKind != 0) {
                    commitNumEdit();
                }
                numEditKind = (int) f[0];
                numEditIdx = (int) f[1];
                numBuf.setLength(0);
                return;
            }
        }
        if (numEditKind != 0) {
            commitNumEdit();
        }

        if (phase != Phase.EDITING) {
            return;
        }

        // ---- 下拉框开着时：点到选项之外就取消（不选中） ----
        if (dropKind != null) {
            // ⚠️ 只能按**弹出框矩形**判断"是不是点在外面"。
            //    之前挨个比 dropButtons 的命中，结果点滚动箭头也算"点外部" → 直接把框关了。
            boolean inside = mouseX >= dropRect[0] && mouseX <= dropRect[0] + dropRect[2]
                    && mouseY >= dropRect[1] && mouseY <= dropRect[1] + dropRect[3];
            if (!inside) {
                dropKind = null;
                return;
            }
        }

        // ---- 新建战役面板是模态的：开着时所有输入都归它 ----
        if (newMapPanel != null) {
            newMapPanel.mousePressed(mouseX, mouseY);
            return;
        }

        // ---- 按钮：先把按下状态记给命中的那个，动作留到松开时触发 ----
        // 「按下高亮、松开触发」是常规按钮行为 —— 按住后拖出去再松开不会误触发。
        boolean pressedAny = false;
        // ⚠️ **逆序**遍历：buttonList 是"先画的在前"，后画的是更上层。
        //    之前正序按下所有命中按钮，松手时按添加顺序第一个匹配 -> 波次按钮抢走了
        //    下拉框按钮的点击（图层明明在上面）。
        for (int i = buttonList.size() - 1; i >= 0; i--) {
            UiButton b = buttonList.get(i);
            if (b.isHovered(mouseX, mouseY)) {
                b.press();
                pressedAny = true;
                if (DEBUG_UI) {
                    // ⚠️ mouseX/mouseY 是 int —— 用 %f 会抛 IllegalFormatConversionException
                    System.out.printf("[UI] 按下 (%d,%d) → 「%s」%n",
                            mouseX, mouseY, b.text());
                }
                break;
            }
        }
        if (DEBUG_UI && !pressedAny) {
            System.out.printf("[UI] 按下 (%d,%d) → 没有命中按钮%n", mouseX, mouseY);
            for (UiButton b : buttonList) {
                System.out.printf("[UI]    候选「%s」x=%.0f..%.0f y=%.0f..%.0f%n",
                        b.text(), b.x(), b.x() + b.width(), b.y(), b.y() + b.height());
            }
        }
        if (pressedAny) {
            return;
        }

        // ---- 地图区 ----
        // 平移只支持中键（左键拖动专属"刷地形"）。
        // 中键允许在整块地图区画布上拖（含留白），这样即使地图还没画满也能挪动视野。
        if (mouseButton == PConstants.CENTER && viewport.containsPointer(mouseX, mouseY)) {
            dragging = true;
            lastDragX = mouseX;
            lastDragY = mouseY;
            return;
        }

        // 放置 / 擦除只在**地图范围内**生效 —— 与光标提示用同一个判据
        if (!isOverMap()) {
            return;
        }
        int[] cell = viewport.screenToCell(mouseX, mouseY);

        // 开一笔历史：本次「按下 → 抬起」期间的所有改动算一笔
        undoManager.beginStroke();
        if (mouseButton == PConstants.RIGHT) {
            // 右键永远是擦除，与当前模式无关
            editCell(cell[0], cell[1], null);
        } else if (mouseButton == PConstants.LEFT) {
            // 左键做什么由 editMode 唯一决定 —— 不会出现"按钮说在放地形、实际在擦"
            editCell(cell[0], cell[1], editMode.placeTarget());
        }
    }

    @Override
    public void mouseReleased() {
        if (phase != Phase.EDITING) {
            return;
        }

        // ---- 面板 ----
        if (newMapPanel != null) {
            newMapPanel.mouseReleased(mouseX, mouseY);
            if (newMapPanel.isClosed()) {
                finishNewMapPanel();
            }
            return;
        }

        // ---- 按钮：松手时若仍停在同一个按钮上，才算点中 ----
        UiButton hit = null;
        for (UiButton b : buttonList) {
            if (b.isPressed()) {
                hit = b;
            }
            b.release();
        }
        if (hit != null) {
            boolean stillOn = hit.isHovered(mouseX, mouseY);
            if (DEBUG_UI) {
                System.out.printf("[UI] 松开 (%d,%d) → 「%s」仍在其上=%s%n",
                        mouseX, mouseY, hit.text(), stillOn);
            }
            if (stillOn) {
                activateButton(hit);
            }
            return;
        }

        dragging = false;
        // 提交这一笔。没有实际改动时 commit 会返回 false，不会留下空历史
        if (undoManager.commit(lastStrokeLabel())) {
            status = status + "    (" + undoManager.summary() + ")";
            applyTitle();
        }
    }

    /**
     * 触发一个按钮的动作。
     *
     * <p>按身份比对而不是下标：<strong>按下与松开之间按钮列表可能被重建</strong>
     * （地图改动会增减门行），下标会错位。
     *
     * @param button 被点中的按钮
     */
    private void activateButton(UiButton button) {
        if (DEBUG_UI) {
            System.out.println("[UI] 触发按钮: " + button.text());
        }
        if (button == testButton) {
            launchDebugGame();
            return;
        }
        if (button == eraserButton) {
            // 与地形工具互斥，由 EditMode 在结构上保证 —— 这里不用手写"清掉对方"
            editMode.toggleEraser();
            status = editMode.describe();
            return;
        }
        for (int i = 0; i < headerButtons.size(); i++) {
            if (button == headerButtons.get(i)) {
                activateHeaderButton(i);
                return;
            }
        }
        for (int i = 0; i < toolButtons.size(); i++) {
            if (button == toolButtons.get(i)) {
                editMode.selectTool(i);
                status = "已选择工具：" + editMode.describe();
                return;
            }
        }
        int doorIndex = doorButtons.indexOf(button);
        if (doorIndex >= 0) {
            selectDoorRow(doorIndex);
            return;
        }
        int wi = waveButtons.indexOf(button);
        if (wi >= 0) {
            if (wi < waveActions.size()) {
                waveActions.get(wi).run();
            }
            return;
        }
        if (DEBUG_UI) {
            System.out.println("[UI] 按钮没有被任何分支认领（可能是列表已重建）");
        }
    }

    /**
     * 打开 UI 诊断日志。
     *
     * <p>用 {@code -Dyestercar.ui.debug=1} 开启：会打印每次鼠标事件的坐标、
     * 命中的按钮、以及触发结果。排查"某个按钮点不动"这类问题时，
     * 光看代码猜不出来（几何、列表、事件三者都可能），必须有实测数据。
     */
    private static final boolean DEBUG_UI =
            System.getProperty("yestercar.ui.debug") != null;

    /** 工具栏文件按钮的动作。 */
    private void activateHeaderButton(int index) {
        switch (index) {
            case BTN_NEW -> newMap();
            case BTN_OPEN -> openMap();
            case BTN_SAVE -> saveMap();
            case BTN_SAVE_AS -> saveMapAs();
            default -> {
                // 没有其它按钮；BTN_TEST 走 activateButton 里的单独分支
            }
        }
    }

    /** 选中「门」小节里的第 {@code index} 行。 */
    private void selectDoorRow(int index) {
        List<DoorRef> doors = allDoors();
        if (index < 0 || index >= doors.size()) {
            return;
        }
        DoorRef ref = doors.get(index);
        selectedIsRedDoor = ref.red();
        selectedDoorId = ref.d().id();
        status = "已选中 " + doorLabel(ref);
    }

    @Override
    public void mouseDragged() {
        if (phase != Phase.EDITING) {
            return;
        }
        // ⚠️ 面板是模态的，拖动也必须归它 —— 它要用拖动来"选中文字"。
        //
        // 这里曾经漏了这条判断（mousePressed 与 mouseReleased 都有、唯独这里没有），
        // 后果很隐蔽：面板的「列数」「行数」输入框下半部分**正压在地图格子上**，
        // 于是"在输入框里按下、往下拖想选中文字"这个最自然的手势，
        // 会滑过地图区、**在面板底下刷出一片地形** —— 而面板本身毫无反应
        // （实测被用户报出来）。
        if (newMapPanel != null) {
            newMapPanel.mouseDragged(mouseX, mouseY);
            return;
        }
        if (dragging) {
            viewport.panBy(mouseX - lastDragX, mouseY - lastDragY);
            lastDragX = mouseX;
            lastDragY = mouseY;
            return;
        }
        // 按住左键连续放置，画起来更顺。
        // 拖到地图外就停手（而不是留个洞）—— 与光标提示用同一个判据。
        if (mouseButton == PConstants.LEFT && isOverMap()) {
            int[] cell = viewport.screenToCell(mouseX, mouseY);
            editCell(cell[0], cell[1], editMode.placeTarget());
        }
    }

    @Override
    public void mouseWheel(processing.event.MouseEvent event) {
        // 滚轮允许在整块地图区画布上生效（含留白）：缩放的锚点可以在地图外，
        // 这不影响正确性（围绕光标的换算本来就支持任意锚点），而且缩放时手不用挪回地图上。
        if (phase != Phase.EDITING || !viewport.containsPointer(mouseX, mouseY)) {
            return;
        }
        viewport.zoomAt(mouseX, mouseY, -event.getCount());
        status = String.format("缩放 %.0f%%", viewport.scale() * 100);
    }

    /**
     * 改一格地形。
     *
     * <p>三种情况分开处理：
     * <ul>
     *   <li>放门 → 走 {@code TileMap.placeRedDoor/placeBlueDoor}，它会同时更新门列表与地形；</li>
     *   <li>擦除 → 若是门，先移门再清地形，避免"地形没了门还在"；</li>
     *   <li>普通地形 → 直接设。</li>
     * </ul>
     *
     * <p>每一次真的改动都要 {@link UndoManager#noteChange} 报备 ——
     * 历史栈靠它判断这一笔到底有没有产生变化（没变化就不该留空历史）。
     *
     * @param col  列
     * @param row  行
     * @param type 目标地形；{@code null} 表示擦除
     */
    private void editCell(int col, int row, TileType type) {
        TileType before = map.tileAt(col, row);

        if (type == null) {
            if (before == TileType.EMPTY) {
                return;                     // 本来就是空的，不算改动
            }
            if (before.isDoor()) {
                map.removeDoorAt(col, row);
            }
            map.setTile(col, row, TileType.EMPTY);
            strokeLabel = "擦除";
            status = String.format("已擦除 (%d, %d)", col, row);
            undoManager.noteChange(true);
            return;
        }

        switch (type) {
            case RED_DOOR -> {
                if (before == TileType.RED_DOOR) {
                    return;
                }
                if (map.redDoors().size() >= 2) {
                    status = "红门最多 2 道";
                    return;
                }
                int id = map.nextRedDoorId();
                map.placeRedDoor(id, col, row);
                strokeLabel = "放置 红门 #" + id;
                status = String.format("已放置 红门 #%d 于 (%d, %d)", id, col, row);
                undoManager.noteChange(true);
            }
            case BLUE_DOOR -> {
                if (before == TileType.BLUE_DOOR) {
                    return;
                }
                if (map.blueDoors().size() >= 2) {
                    status = "蓝门最多 2 道";
                    return;
                }
                int id = map.nextBlueDoorId();
                map.placeBlueDoor(id, col, row, 3);
                strokeLabel = "放置 蓝门 #" + id;
                status = String.format("已放置 蓝门 #%d 于 (%d, %d)", id, col, row);
                undoManager.noteChange(true);
            }
            default -> {
                if (before == type) {
                    return;                 // 已经是这种地形了，不算改动
                }
                // 往门上盖普通地形 = 把那道门拆掉
                if (before.isDoor()) {
                    map.removeDoorAt(col, row);
                }
                map.setTile(col, row, type);
                strokeLabel = "放置 " + type.displayName();
                status = String.format("已放置 %s 于 (%d, %d)", type.displayName(), col, row);
                undoManager.noteChange(true);
            }
        }
    }

    // ==================================================================
    // 门列表（侧栏「门」小节）
    // ==================================================================

    /** 侧栏「门」小节里一行的几何。 */
    private static final float DOOR_ROW_H = 26f;

    /**
     * @return 「门」小节的起始 y；没有门时也返回一个有效位置
     */
    /**
     * 属性栏内容区的起点与尺寸。
     *
     * <p>「地形工具」小节的位置由它算出来 —— <strong>绘制与命中判定都用它</strong>，
     * 否则会出现"看着在按钮上、点了没反应"。
     *
     * @return {@code [x, 内容首行 y, 内容宽, 地形按钮的宽]}
     */
    private float[] sidebarMetrics() {
        LayoutRegions.Region r = LayoutRegions.sidebar();
        float x = r.x() + UITheme.SPACE_3;
        float contentW = r.width() - UITheme.SPACE_3 * 2;
        // 与 drawSidebar 一致：顶部间距 + 小节标题（30）
        float y = r.y() + UITheme.SPACE_3 + 30f;
        float btnW = (contentW - UITheme.SPACE_2) / 2f;
        return new float[]{x, y, contentW, btnW};
    }

    /** 地形工具按钮行的固定高度。 */
    private static final float TOOL_BTN_H = 34f;

    /**
     * @return 「门」小节的起始 y；没有门时也返回一个有效位置
     */
    private float doorSectionTop() {
        float[] m = sidebarMetrics();
        // 地形工具 3 行 + 擦除按钮 + 间距 + 「门」标题
        return m[1] + 3 * (TOOL_BTN_H + UITheme.SPACE_1)
                + TOOL_BTN_H + UITheme.SPACE_3      // 擦除
                + 30f;                              // 小节标题高度
    }

    /**
     * 侧栏「门」小节里第 {@code index} 行的矩形。
     *
     * <p>红门排在蓝门前。绘制与命中判定都用这一个方法，避免两边算得不一样
     * （那样会出现"看着在按钮上、点了没反应"）。
     *
     * @param index 行序号，0 起
     * @return {@code [x, y, w, h]}
     */
    private float[] doorRowBox(int index) {
        LayoutRegions.Region r = LayoutRegions.sidebar();
        float x = r.x() + UITheme.SPACE_3;
        float w = r.width() - UITheme.SPACE_3 * 2;
        float y = doorSectionTop() + index * (DOOR_ROW_H + 2f);
        return new float[]{x, y, w, DOOR_ROW_H};
    }

    /**
     * 列表里的一道门，带上它是红还是蓝。
     *
     * <p>⚠️ <strong>为什么必须显式带 {@code red}，而不能靠 {@code redDoorById(id)} 反查</strong>：
     * 红门和蓝门的 id 是<strong>各自独立</strong>编号的，所以「红门 #0」和「蓝门 #0」可以同时存在。
     * 这时按 id 反查必然出错 —— 会把蓝门认成红门（并因此不显示 HP），
     * 也会让"选中门"的判定把两个门一起点亮。**实测踩过这个 bug。**
     *
     * @param d   门
     * @param red {@code true} = 红门，{@code false} = 蓝门
     */
    private record DoorRef(Door d, boolean red) {
    }

    /** @return 红门在前、蓝门在后的完整列表（与「门」小节的显示顺序一致）。 */
    private List<DoorRef> allDoors() {
        List<DoorRef> all = new ArrayList<>();
        for (Door d : map.redDoors()) {
            all.add(new DoorRef(d, true));
        }
        for (Door d : map.blueDoors()) {
            all.add(new DoorRef(d, false));
        }
        return all;
    }

    /**
     * @param ref 门
     * @return 门的显示文案
     *
     * <p>蓝门带上 HP —— 策划 §2.1 要求「蓝门生命 默认 3，编辑器可改」，
     * 但 §6.2 的属性栏里**没有对应的编辑控件**。
     * 所以这里先按**只读**显示，让用户至少看得到当前值；
     * 编辑控件留到 P4 做波次表单、属性栏整体布局敲定之后再补。
     */
    private String doorLabel(DoorRef ref) {
        return doorLabel(ref.d(), ref.red());
    }

    /**
     * 门的显示文案。
     *
     * <p><strong>做成 public static，是为了能被单元测试直接验证</strong> ——
     * 这个方法曾经出过一个很难发现的 bug：它靠 {@code redDoorById(id)} 反查门的类型，
     * 而红门与蓝门的 id 各自独立编号，所以「红门 #0」一放下去，
     * 「蓝门 #0」就被认成红门（并因此不显示 HP）。见 {@code UndoManagerTest} 的回归用例。
     *
     * @param d   门
     * @param red {@code true} = 红门，{@code false} = 蓝门。<strong>必须由调用方按列表来源给出</strong>，
     *            不能靠 id 反查
     * @return 形如 {@code "红门 #0 (0,0)"} 或 {@code "蓝门 #0 (11,7)"}
     */
    public static String doorLabel(Door d, boolean red) {
        if (red) {
            return String.format("红门 #%d (%d,%d)", d.id(), d.col(), d.row());
        }
        return String.format("蓝门 #%d (%d,%d)", d.id(), d.col(), d.row());
    }

    /**
     * 撤销/重做之后校正门选中状态 —— 选中的门可能已经不存在了。
     */
    private void syncDoorSelection() {
        if (selectedDoorId < 0) {
            return;
        }
        boolean stillThere = selectedIsRedDoor
                ? map.redDoorById(selectedDoorId) != null
                : map.blueDoorById(selectedDoorId) != null;
        if (!stillThere) {
            selectedDoorId = -1;
        }
    }

    // ==================================================================
    // 按钮命中判定
    // ==================================================================

    // ==================================================================
    // 文件操作（P2.2）
    // ==================================================================

    /**
     * 地图指纹：把"这张图长什么样"压成一个字符串，用来判断有没有未保存改动。
     *
     * <p>只比"是否相同"，所以没必要再压一份几百 KB 的快照。
     * 参与计算的是所有**用户能改**的东西：地图名、尺寸、每格地形、每道门。
     *
     * @return 指纹字符串
     */
    private String fingerprint() {
        StringBuilder sb = new StringBuilder(map.cols() * map.rows() + 128);
        sb.append(map.name()).append('|')
                .append(map.cols()).append('x').append(map.rows())
                .append('@').append(map.tileSize()).append('|');
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.cols(); col++) {
                sb.append(map.tileAt(col, row).code()).append(',');
            }
        }
        sb.append('|');
        for (Door d : map.redDoors()) {
            sb.append('R').append(d.id()).append(':').append(d.col()).append(',').append(d.row()).append(';');
        }
        for (Door d : map.blueDoors()) {
            sb.append('B').append(d.id()).append(':').append(d.col()).append(',').append(d.row())
                    .append(",hp=").append(d.hp()).append(';');
        }
        return sb.toString();
    }

    /**
     * @return 有未保存的改动时返回 {@code true}
     */
    private boolean isDirty() {
        return savedFingerprint != null && !savedFingerprint.equals(fingerprint());
    }

    /** 把当前状态记为"已保存"。 */
    private void markSaved() {
        savedFingerprint = fingerprint();
        applyTitle();
    }

    /** 刷新窗口标题：带地图名，未保存时加 {@code *}。 */
    private void applyTitle() {
        surface.setTitle("YesterCar — 战役编辑器  " + map.name() + (isDirty() ? " *" : ""));
    }

    /**
     * 工具栏「保存」：有归属战役就写回它，没有就当成"另存为"。
     *
     * @return 真的写出去了返回 {@code true}
     */
    private boolean saveMap() {
        if (currentPath == null) {
            return saveMapAs();
        }
        return writeTo(currentPath);
    }

    /**
     * 工具栏「另存为」：**另存成一个新战役**（或覆盖指定的那个）。
     *
     * <h2>⚠️ 名字取自对话框，不是取自当前地图</h2>
     * 曾经这里写成"只取对话框的父目录、文件夹名一律用 {@code map.name()}" ——
     * 理由是"一个东西两个名字是混乱"。那条理由本身没错，但**实现是错的**：
     * {@code map.name()} 从头到尾没变过，所以无论用户在框里输什么名字，
     * 目标永远是<b>当前那个战役文件夹</b> ——
     * 于是"另存为"变成了"保存"，用户输入的新名字被无声丢掉。
     *
     * <p>正确的理解是：**另存为时，用户在对话框里输的名字就是新战役名**。
     * 输入不同的名字 → 新建一个战役；保持原名 → 那就是覆盖。
     * 名字与文件夹名仍然一致，只是"谁说了算"变了。
     *
     * <h2>位置与名字都来自对话框</h2>
     * 原生保存框返回的完整路径里两者都有，直接用它的文件名即可 ——
     * 不必再从面板里取名字，也就没有"两个名字打架"的问题。
     *
     * <h2>用户想存哪就存哪</h2>
     * {@code data/campaigns/} 只是**默认落点**（游戏能读到的那个目录），不是限制。
     * 存到别处会明确提示"游戏里看不到"，但**不拦**。
     *
     * @return 真的写出去了返回 {@code true}
     */
    private boolean saveMapAs() {
        // ⚠️ 预填一个**副本名**，不预填原名。
        //
        // 预填原名的话，"另存为"这个动作点两下确定就变成了"覆盖当前战役" ——
        // 用户以为自己在另存，其实原文件被改写了（实测踩过：
        // 文件夹里凭空多出几个名字相近的战役，就是试另存为留下的）。
        // 预填 <原名>_副本 让默认行为真的是"另存一份"。
        String base = MapNaming.sanitizeFileName(map.name());
        if (base.isBlank()) {
            base = "我的战役";
        }
        String suggested = base + "_副本";
        // 如果这个名字已经存在，就往后找 _副本2、_副本3…
        for (int i = 2; CampaignLoader.findById(suggested) != null && i < 100; i++) {
            suggested = base + "_副本" + i;
        }

        // 默认从"当前战役的上一层"开始 —— 便于"另存为兄弟战役"；
        // 没有当前战役时从游戏能读到的那个目录开始
        Path startAt = currentPath != null && currentPath.getParent() != null
                ? currentPath.getParent()
                : Workspace.campaignsDir();

        Path chosen = fileDialogs.saveWithNativeDialog(
                "另存为战役（改个名字就是新建，保持原名就是覆盖）", startAt, suggested);
        if (chosen == null) {
            status = "已取消另存为";
            return false;
        }

        // 名字与位置都取自对话框；扩展名由对话框预填成 .json，这里去掉
        Path parent = chosen.getParent();
        Path fileName = chosen.getFileName();
        if (parent == null || fileName == null) {
            status = "保存失败：看不出选的位置";
            return false;
        }
        String campaignName = MapNaming.sanitizeFileName(
                MapNaming.nameFromFile(fileName));

        String problem = CampaignStore.checkId(campaignName);
        if (problem != null) {
            status = "战役名不可用：" + problem;
            JOptionPane.showMessageDialog(null,
                    problem + "。" + System.lineSeparator() + System.lineSeparator()
                            + "战役名同时是文件夹名与地图文件名，请改个名字。",
                    "战役名不可用", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        Path target = parent.resolve(campaignName);

        // 兜住"战役根目录本身被当成战役"这条路径 —— 见 isCampaignsRoot 的说明
        if (isCampaignsRoot(target)) {
            status = "另存为失败：不能直接存在战役根目录下";
            JOptionPane.showMessageDialog(null,
                    "战役根目录" + System.lineSeparator()
                            + "  " + Workspace.campaignsDir() + System.lineSeparator()
                            + System.lineSeparator()
                            + "只用来装一个个战役文件夹，不能直接作为战役存放。"
                            + System.lineSeparator()
                            + "请在它下面选一个子目录（或换个位置）。",
                    "存放位置不合适", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        // ★ 覆盖前先问（Q 的决定）：官方战役不许覆盖；自定义战役要用户点头。
        // "存回它自己原来那个位置"属于正常保存，不打扰。
        Campaign existing = CampaignLoader.findById(campaignName);
        if (existing != null && !isSamePlace(existing, target)) {
            if (existing.isOfficial()) {
                status = "「" + campaignName + "」是官方战役，不能覆盖";
                JOptionPane.showMessageDialog(null,
                        "「" + campaignName + "」是官方战役，不能被覆盖。"
                                + System.lineSeparator() + System.lineSeparator()
                                + "请换一个名字。",
                        "不能覆盖官方战役", JOptionPane.ERROR_MESSAGE);
                return false;
            }
            if (!confirmOverwrite(target, campaignName)) {
                status = "已取消：未覆盖「" + campaignName + "」";
                return false;
            }
        } else if (existing == null && Files.isDirectory(target)
                && Files.exists(target.resolve(CampaignJsonIO.FILE_NAME))) {
            // 磁盘上有这个文件夹，但加载器不认识它（比如 campaign.json 坏了）。
            // 那也是别人的东西，覆盖前一样要问。
            if (!confirmOverwrite(target, campaignName)) {
                status = "已取消：未覆盖「" + campaignName + "」";
                return false;
            }
        }
        return writeTo(target);
    }

    /**
     * 覆盖已有战役前征求同意。
     *
     * <h2>为什么不直接覆盖</h2>
     * 「另存为」时名字撞上别的战役，很可能只是<b>重名</b>而不是"我就是想覆盖它"。
     * 直接覆盖会无声地毁掉另一个战役 —— 那是不可撤销的。
     *
     * <p>所以这里把"要动的是哪个文件夹、里面有什么"摆出来，让用户看清再决定。
     *
     * @param target       将被覆盖的战役目录
     * @param campaignName 战役名
     * @return 用户同意覆盖返回 {@code true}
     */
    private static boolean confirmOverwrite(Path target, String campaignName) {
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(campaignName).append("」已经存在，继续保存会覆盖它。")
                .append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("位置：").append(target).append(System.lineSeparator());
        Path json = target.resolve(CampaignJsonIO.FILE_NAME);
        if (Files.isRegularFile(json)) {
            try {
                CampaignMeta old = CampaignJsonIO.load(json);
                sb.append("现有内容：").append(old.waves().size()).append(" 个波次，")
                        .append(old.totalEnemies()).append(" 只敌人")
                        .append(System.lineSeparator());
                if (!old.description().isBlank()) {
                    sb.append("简介：").append(old.description())
                            .append(System.lineSeparator());
                }
            } catch (IOException e) {
                sb.append("（现有的 campaign.json 读不出来）").append(System.lineSeparator());
            }
        }
        sb.append(System.lineSeparator()).append("要覆盖它吗？");

        Object[] options = {"覆盖", "取消"};
        int pick = JOptionPane.showOptionDialog(null, sb.toString(), "确认覆盖",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        return pick == 0;
    }

    /**
     * 目标目录是不是"战役的存放根"本身（而不是根下面的某个战役文件夹）。
     *
     * <h2>⚠️ 为什么要专门挡这一条</h2>
     * 战役根目录（{@code data/campaigns/}）<strong>只能装战役文件夹</strong>。
     * 一旦战役被直接写到它下面，就会在根里留下散落的 {@code campaign.json} 与
     * {@code maps/}，和各个战役文件夹混在一起 —— 清理时根本分不清哪个是垃圾。
     *
     * <p>这个错误真实发生过：旧版用 {@code JFileChooser} 选目录，
     * 用户进到 {@code data/campaigns} 直接点保存，控件把当前目录当成了用户的选择，
     * 于是战役名变成 {@code campaigns}，文件写进了根下
     * （产物签名就是 {@code "id": "campaigns"}）。
     *
     * <p>现在换成原生保存框之后这条路径已经走不通（文件夹名固定取战役名），
     * 但仍然保留这道检查：<strong>它挡的是"结果"，不是"路径"</strong> ——
     * 将来无论怎么改对话框，只要解析出的目标目录等于战役根，就会被拦下。
     *
     * @param target 解析出的目标战役目录
     * @return 目标是战役根本身返回 {@code true}
     */
    private static boolean isCampaignsRoot(Path target) {
        if (target == null) {
            return true;
        }
        return target.toAbsolutePath().normalize()
                .equals(Workspace.campaignsDir().toAbsolutePath().normalize());
    }

    /**
     * 已有战役是不是就在目标位置上（那就不是"撞名"，而是"覆盖保存"）。
     *
     * @param existing 已加载的战役
     * @param target   目标目录
     * @return 同一个位置返回 {@code true}
     */
    private static boolean isSamePlace(Campaign existing, Path target) {
        if (existing.dir() == null) {
            return false;   // 官方战役在 jar 内，永远不可能是同一个位置
        }
        return existing.dir().toAbsolutePath().normalize()
                .equals(target.toAbsolutePath().normalize());
    }

    /**
     * 把当前地图写成指定位置的战役。先校验，有错误就拦下。
     *
     * @param campaignDir 目标战役目录（默认落点是 {@code data/campaigns/<战役名>}，
     *                    但玩家可以选到任何地方）
     * @return 真的写出去了返回 {@code true}
     */
    private boolean writeTo(Path campaignDir) {
        if (!confirmValidation(campaignDir)) {
            return false;
        }
        return doWrite(campaignDir);
    }

    /**
     * 校验并决定要不要继续写。
     *
     * <h2>「仍然保存」只对**通路**这一条开放</h2>
     * 这是唯一一条"正常会拦住、但用户能明确绕过"的规则。
     * 理由是编辑器的现实：**"红蓝门还没连通"恰恰是画到一半时最常见的状态**，
     * 硬拦会让人陷入"要么画通、要么白画"的两难。
     *
     * <p>其它错误（没有红门、空图、尺寸超限）**不给绕过的选项** ——
     * 那些不是"图不好玩"，而是"图没法运行"或"违反契约"，
     * 让它们能存下去只会把问题推到运行时。
     *
     * @param campaignDir 保存目标（绕过通路检查时直接接着写它）
     * @return 可以继续写返回 {@code true}
     */
    private boolean confirmValidation(Path campaignDir) {
        MapValidator.ValidationResult v = MapValidator.validate(map);
        if (v.ok()) {
            return true;
        }

        // 只有"通路"这一类错误时才多给一个「仍然保存」。
        // 判据是：放开通路检查之后就通过了 —— 那就说明其余错误一条都没有。
        MapValidator.ValidationResult withoutReachability =
                MapValidator.validate(map, false);
        if (!withoutReachability.ok()) {
            status = "保存被拦下：" + v.summary();
            JOptionPane.showMessageDialog(null,
                    "这张地图还没法保存：" + System.lineSeparator() + System.lineSeparator()
                            + v.detail(),
                    "校验未通过", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        status = "保存被拦下：" + v.summary();
        Object[] options = {"仍然保存", "回去改"};
        int pick = JOptionPane.showOptionDialog(null,
                "红门和蓝门之间没有通路，敌人进去会原地不动。"
                        + System.lineSeparator() + System.lineSeparator()
                        + "如果你还在画、想先存个草稿，可以「仍然保存」。",
                "没有通路", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        // 返回 0 才是点了「仍然保存」；关掉对话框或点「回去改」都不写
        return pick == 0;
    }

    /**
     * 真正落盘那一步（校验已在 {@link #confirmValidation} 里做过）。
     *
     * @param campaignDir 目标战役目录
     * @return 写出去了返回 {@code true}
     */
    private boolean doWrite(Path campaignDir) {
        Path name = campaignDir.getFileName();
        if (name == null) {
            status = "保存失败：目标路径没有文件夹名";
            return false;
        }
        String campaignName = name.toString();
        try {
            // saveWithMapAt 会：写地图 → 写/更新 campaign.json（保留已配好的波次）
            CampaignStore.saveWithMapAt(campaignDir, campaignName, campaignName, map,
                    waves.waves(), params);
        } catch (IOException e) {
            status = "保存失败：" + e.getMessage();
            System.err.println("[EditorApp] 保存失败: " + e);
            JOptionPane.showMessageDialog(null, "写盘失败：" + System.lineSeparator() + e.getMessage(),
                    "保存失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        currentPath = campaignDir;
        // 显示名与目录名对齐 —— 与写盘的规则一致，编辑器里也照着显示
        map.setName(campaignName);
        markSaved();
        applyTitle();
        MapValidator.ValidationResult v = MapValidator.validate(map);
        status = "已保存为战役「" + campaignName + "」"
                + (v.warnings().isEmpty() ? "" : "（" + v.summary() + "）");

        // 存到"游戏能读到的目录"之外不是错误，但游戏里确实看不到 —— 明确告知
        Path readable = Workspace.campaignsDir().toAbsolutePath().normalize();
        if (!campaignDir.toAbsolutePath().normalize().startsWith(readable)) {
            status += "    ⚠ 在游戏目录外，游戏里看不到";
        }
        System.out.println("[EditorApp] 已保存战役: " + campaignDir);
        return true;
    }

    /**
     * 工具栏「打开」：选一个**战役文件夹**，默认从 {@code data/campaigns/} 开始。
     *
     * <h2>为什么改成选文件夹</h2>
     * 「一个战役 = 一张地图」，而战役是一个文件夹（{@code campaign.json} + {@code maps/}）。
     * 让玩家选文件夹，比让他记住"要打开的是 {@code map/<战役名>.json} 而不是别的"
     * 直觉得多 —— 后者要求他先理解内部目录约定。
     *
     * <p>打开之后<strong>该路径就成为新的"当前路径"</strong>，
     * 之后「保存」写回那里 —— 否则从别处打开、一保存却跑到默认目录去了。
     * 这正是"用户想存哪就存哪"的另一半：**从哪读的就写回哪**。
     */
    private void openMap() {
        if (!confirmDiscardChanges("打开另一个战役")) {
            return;
        }
        Path chosen = fileDialogs.openDirectory("打开战役（选文件夹）", Workspace.campaignsDir());
        if (chosen == null) {
            status = "已取消打开";
            return;
        }
        loadFrom(chosen);
    }

    /**
     * 从一个**战役目录**载入地图，并把该目录记为"当前路径"。
     *
     * <h2>按三条线索找地图，逐级放宽</h2>
     * <ol>
     *   <li><b>{@code campaign.json} 里 {@code map} 声明的路径</b> —— 正常路径。
     *       地图文件名 ≡ 战役名，所以这里必须读声明，不能自己拼。</li>
     *   <li><b>约定位置 {@code map/<战役名>.json}</b> —— 给"有地图但没写
     *       campaign.json"的文件夹兜底。</li>
     *   <li><b>目录里任意一个能解析成地图的 {@code .json}</b> —— 给手工整理的
     *       文件夹兜底（玩家从别处拷来一个只含地图的目录时不该被挡在门外）。</li>
     * </ol>
     *
     * @param dir 战役目录
     */
    private void loadFrom(Path dir) {
        String dirName = dir.getFileName() == null ? "" : dir.getFileName().toString();

        Path mapFile = null;
        // ① 读 campaign.json 的声明
        Path json = dir.resolve(CampaignJsonIO.FILE_NAME);
        if (Files.isRegularFile(json)) {
            try {
                Path declared = dir.resolve(CampaignJsonIO.load(json).map());
                if (Files.isRegularFile(declared)) {
                    mapFile = declared;
                }
            } catch (IOException e) {
                System.err.println("[EditorApp] campaign.json 读不出来，改按约定位置找: "
                        + e.getMessage());
            }
        }
        // ② 约定位置
        if (mapFile == null) {
            Path conventional = dir.resolve(CampaignMeta.mapPathFor(dirName));
            if (Files.isRegularFile(conventional)) {
                mapFile = conventional;
            }
        }
        // ③ 目录里随便一张地图
        if (mapFile == null) {
            mapFile = findLooseMapFile(dir);
        }

        if (mapFile == null) {
            status = "这个文件夹里没有地图：" + dir;
            JOptionPane.showMessageDialog(null,
                    "这个文件夹里找不到地图。" + System.lineSeparator()
                            + System.lineSeparator()
                            + "期望的位置：" + System.lineSeparator()
                            + "  " + dir.resolve(CampaignMeta.mapPathFor(dirName)),
                    "打开失败", JOptionPane.ERROR_MESSAGE);
            return;
        }

        TileMap loaded;
        try {
            loaded = MapJsonIO.load(mapFile);
        } catch (IOException e) {
            status = "打开失败：" + e.getMessage();
            System.err.println("[EditorApp] 打开失败: " + e);
            JOptionPane.showMessageDialog(null,
                    "读不了这个文件：" + System.lineSeparator() + e.getMessage(),
                    "打开失败", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 严格绑定：地图名以**战役目录名**为准（一个战役 = 一张地图）
        loaded.setName(dirName.isEmpty() ? loaded.name() : dirName);

        map = loaded;
        currentPath = dir;
        loadWavesFrom(dir);
        viewport.setMapSize(map.cols(), map.rows());
        viewport.resetView();
        // 换了一张图，旧的历史没有意义了（撤销到上一张图上是灾难）
        undoManager.bindWaves(new com.yestercar.campaigneditor.command.UndoManager.WaveSnapshotter() {
            @Override
            public java.util.List<com.yestercar.common.model.Wave> snapshot() {
                return waves.waves();
            }

            @Override
            public void restore(java.util.List<com.yestercar.common.model.Wave> w) {
                waves = new WaveEditor(w);
                selectedWave = Math.max(0, Math.min(selectedWave, Math.max(0, waves.size() - 1)));
            }
        });
        undoManager.clear();
        selectedDoorId = -1;
        markSaved();
        applyTitle();
        status = "已打开战役「" + map.name() + "」    " + map;
        System.out.println("[EditorApp] 已打开战役: " + dir + "  地图=" + mapFile);
    }

    /**
     * 在一个目录里找一张"散装"地图（不作为约定位置存在时）。
     *
     * <p>取第一个 {@code .json} 且能解析成地图的文件。
     *
     * @param dir 目录
     * @return 找到的地图文件；没有返回 {@code null}
     */
    private static Path findLooseMapFile(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                if (p.getFileName().toString().equals(CampaignJsonIO.FILE_NAME)) {
                    continue;   // 那是战役描述，不是地图
                }
                try {
                    MapJsonIO.load(p);
                    return p;
                } catch (IOException | RuntimeException ignored) {
                    // 不是地图，继续找
                }
            }
        } catch (IOException e) {
            System.err.println("[EditorApp] 列举目录失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 工具栏「新建战役」：打开自绘面板，问战役名与尺寸。
     *
     * <p>原先会连弹三个 {@code JOptionPane} 输入框，外观与编辑器不搭、按钮也改不了样式。
     * 现在收进一个 {@link NewMapPanel}。
     *
     * <p>名字就是文件名（严格绑定），所以面板里问的名字会直接决定
     * 「保存」时写到 {@code data/maps/<名字>.json}。
     */
    private void newMap() {
        if (!confirmDiscardChanges("新建战役")) {
            return;
        }
        newMapPanel = new NewMapPanel(this, font);
    }

    /**
     * 面板关闭后的收尾：确认了就换上新地图，取消了就什么都不做。
     */
    private void finishNewMapPanel() {
        NewMapPanel panel = newMapPanel;
        newMapPanel = null;
        if (panel == null) {
            return;
        }
        NewMapPanel.Result r = panel.result();
        if (r == null) {
            status = "已取消新建";
            return;
        }

        map = new TileMap(r.name(), r.cols(), r.rows());
        // 还没有对应文件 —— 「保存」时会落到 data/maps/<名字>.json
        currentPath = null;
        viewport.setMapSize(r.cols(), r.rows());
        viewport.resetView();
        undoManager.clear();
        selectedDoorId = -1;
        markSaved();
        status = "已新建空地图 " + r.name() + "（" + r.cols() + "×" + r.rows() + "）"
                + "，保存时会写到 data/campaigns/" + r.name() + "/";
    }

    /**
     * 有未保存改动时问一句。
     *
     * @param what 接下来要做的事，用于文案，如 {@code "新建战役"}
     * @return 可以继续时返回 {@code true}
     */
    private boolean confirmDiscardChanges(String what) {
        if (!isDirty()) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(null,
                "当前更改未保存，仍要" + what + "吗？",
                "未保存的更改", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.YES_OPTION;
    }

    /**
     * 关窗拦截（Q4）。
     *
     * <p>Processing 4 里 AWT 的 {@code windowClosing} 直接调 {@code PApplet.exit()}
     * （见 {@code PSurfaceAWT$2}），所以覆写 {@code exit()} 就是唯一的拦截点。
     * <strong>选「否」时不能调 {@code super.exit()} —— 那样窗口就真关了。</strong>
     */
    @Override
    public void exit() {
        if (phase == Phase.EDITING && isDirty()) {
            int answer = JOptionPane.showConfirmDialog(null,
                    "当前更改未保存，是否关闭？",
                    "未保存的更改", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                status = "已取消关闭（改动未保存）";
                return;
            }
        }
        super.exit();
    }

    /**
     * 工具栏上从左到右的按钮。
     *
     * <h2>⚠️ 这两个按钮的用词改过两次，别再改回去</h2>
     * <ol>
     *   <li>策划 §6.2 画的是「新建战役／打开战役」（战役级）；</li>
     *   <li>P2.2 只做「保存/加载<b>地图</b> JSON」，用战役的名字做地图的事会被当成 bug，
     *       所以先改成「新建地图／打开地图」；</li>
     *   <li>P3 战役结构落地后（一个文件夹 = 一张地图 + 它的配置），
     *       操作对象是"一个可玩的关卡包"，于是<b>改回战役级</b>。</li>
     * </ol>
     *
     * <p>注意这与"编辑器里说地图、主菜单里说战役"的约定**不冲突**：
     * 那条约定管的是"你在操作什么"（图的格子 → 地图），
     * 而这两个按钮管的是"你创建/打开什么"（一个关卡包 → 战役）。
     *
     * <p>撤销/重做<strong>不在这里</strong>：只有快捷键（`Ctrl+Z` / `Ctrl+Y`），
     * 深度显示在状态栏。
     */
    private static final int BTN_NEW = 0;
    private static final int BTN_OPEN = 1;
    private static final int BTN_SAVE = 2;
    private static final int BTN_SAVE_AS = 3;

    /** 按钮文案，下标与上面的常量对应。 */
    private static final String[] HEADER_LABELS = {
            "新建战役", "打开战役", "保存", "另存为"
    };

    /** 测试按钮单独靠右，所以给它一个不在 {@link #HEADER_LABELS} 里的下标。 */
    private static final int BTN_TEST = 99;

    /**
     * 工具栏按钮的矩形。
     *
     * <p>绘制与命中判定共用这一个方法，避免两边各算一套坐标后对不上
     * （那样会出现"看着在按钮上、点了没反应"）。
     *
     * @param which 按钮下标，见 {@code BTN_*}
     * @return {@code [x, y, w, h]}
     */
    private float[] headerButtonBox(int which) {
        LayoutRegions.Region r = LayoutRegions.header();
        float y = r.centerY() - 16f;
        float h = 32f;
        float w = 88f;

        if (which == BTN_TEST) {
            float testW = 120f;
            return new float[]{r.right() - UITheme.SPACE_2 - testW, y, testW, h};
        }

        float x = r.x() + UITheme.SPACE_2;
        for (int i = 0; i < HEADER_LABELS.length; i++) {
            if (i == which) {
                return new float[]{x, y, w, h};
            }
            x += w + UITheme.SPACE_1;
        }
        // 未知下标：返回一个屏幕外的矩形，永远不会被点到
        return new float[]{-1000f, -1000f, 1f, 1f};
    }

    // ==================================================================
    // 调试：把当前地图写成临时战役，再启动游戏进程
    // ==================================================================

    /**
     * 「测试」按钮：把当前地图写成一份临时战役，然后启动游戏进程加载它。
     *
     * <h2>为什么写临时战役而不是直接传内存</h2>
     * 游戏与编辑器是<strong>两个独立进程</strong>（用户要求分开），
     * 内存传递不可能，文件是唯一的通道。写进 {@code data/campaigns/_debug/}
     * 也顺便让这个战役能在游戏里被正常发现。
     *
     * <h2>⚠️ 待办</h2>
     * 完整流程应当是「自定义战役界面 → 选中该战役 → 编队界面 → 确认开战」。
     * 那两个界面尚未实现，所以现在用 {@code --play} <strong>直接进战斗</strong>。
     * 等它们做出来之后，把 {@code --play} 换成普通启动 + 自动选中即可。
     */
    private void launchDebugGame() {
        MapValidator.ValidationResult v = MapValidator.validate(map);
        if (!v.ok()) {
            status = "无法测试：" + v.summary();
            System.err.println("[EditorApp] " + status);
            JOptionPane.showMessageDialog(null,
                    "这张地图还没法测试：" + System.lineSeparator() + System.lineSeparator()
                            + v.detail(),
                    "校验未通过", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 波次也要合法才允许测试（Boss 超量、门越界、空组都会让战斗行为很怪）
        java.util.List<WaveEditor.Problem> waveProblems = waves.validate(
                map.redDoors().size(), map.blueDoors().size(), enemyIdList());
        if (!waveProblems.isEmpty()) {
            status = "无法测试：" + waveProblems.get(0);
            System.err.println("[EditorApp] " + status);
            JOptionPane.showMessageDialog(null,
                    "波次表有问题，先修好再测试：" + System.lineSeparator()
                            + System.lineSeparator() + waveProblems.get(0),
                    "波次校验未通过", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            Path dir = Workspace.campaignDir(Workspace.DEBUG_CAMPAIGN_NAME);

            // ⚠️ 走**统一的写盘出口**，不要自己拼 campaign.json。
            //
            // 这里曾经手写了一份 JSON 字面量，于是绕开了一切约定：它写的是
            // `"levels": []`（那一层早就删了），而且 name 是「调试战役」、
            // id 是 `_debug` —— 一个战役两个名字。用 saveWithMapAt 之后，
            // 战役名、文件夹名、地图文件名三者由同一处决定，不可能再对不上。
            //
            // ⚠️ 必须存**副本**，不能存活地图：MapJsonIO.save 会把 name 对齐到
            // 传进去的名字，直接存活地图就会把用户手里这张改名
            // （实测踩过：点一次「测试」，侧栏的地图名永久变成别的，退出也回不来）。
            // ⚠️ 必须把**编辑后的波次**一起写进去。
            //    走 4 参重载会让 waves=null（沿用文件里的旧值），
            //    于是"编辑器里改了波次，点测试还是老出怪逻辑"。
            CampaignStore.saveWithMapAt(dir, Workspace.DEBUG_CAMPAIGN_NAME,
                    Workspace.DEBUG_CAMPAIGN_NAME, map, waves.waves(), params);

            status = "已写出调试战役，正在启动游戏…";
            System.out.println("[EditorApp] 调试战役已写出: " + dir);

            startGameProcess(Workspace.DEBUG_CAMPAIGN_NAME);
        } catch (IOException e) {
            status = "测试失败: " + e.getMessage();
            System.err.println("[EditorApp] " + status);
        }
    }

    /**
     * 启动一个独立的游戏进程。
     *
     * <p>复用当前 JVM 的启动方式（同样的 java 可执行文件与 classpath），
     * 这样在 IDEA 里跑和在命令行跑都能work，不必区分打包前后。
     *
     * @param campaignName 要加载的战役名
     * @throws IOException 进程启动失败
     */
    private void startGameProcess(String campaignName) throws IOException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-Dfile.encoding=UTF-8",
                "-cp", classpath,
                "com.yestercar.GameMain",
                "--play", campaignName);
        pb.inheritIO();          // 让游戏进程的日志直接打到当前控制台
        Process process = pb.start();
        System.out.println("[EditorApp] 游戏进程已启动 pid=" + process.pid()
                + " 战役=" + campaignName);
    }
}
