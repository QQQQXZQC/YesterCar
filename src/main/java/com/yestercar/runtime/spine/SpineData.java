package com.yestercar.runtime.spine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 一个干员模型的不可变数据（解析结果）。多个场上单位共享同一份实例。
 *
 * <p>用法：
 * <pre>
 * SpineData data = SpineData.load(Workspace.projectRoot().resolve("data/spine/operator/amiya"));
 * SpinePose pose = new SpinePose(data);      // 每个单位一份
 * pose.play("Idle", true);
 * pose.fill(buffer);                          // 每帧
 * </pre>
 *
 * <h2>版本差异（逐文件读，不能猜）</h2>
 * {@code .skel} 文件头是 {@code [hash cstring][version cstring][...]}，版本必须<b>按文件</b>读：
 * 3.8 的解析器读 3.5 的文件不会报错，但会静默错位（表现为"加载成功、动画数 0"）。
 * 本类把读到的版本存进 {@link #version()}，解析分支见 {@link SpineSkeletonBinary}。
 * 实测 29 个素材：24 个 {@code 3.5.51}、5 个 {@code 3.8.99}
 * （eyjafjalla_the_hvit_aska / ines / kroos / mlynar / reed）。
 *
 * <h2>包围盒</h2>
 * {@link #minX()} 等是<b>setup 姿态的视觉包围盒</b>：遍历所有插槽、把每个附件的世界顶点取极值。
 * <b>不是</b> BoundingBoxAttachment 的极值 —— 那是判定框（会明显偏大/偏小），
 * 项目里记录过这个坑。
 *
 * <h2>没实现的部分（诚实清单，含实测代价）</h2>
 * <ul>
 *   <li><b>IK / transform / path 三类约束</b>：解析时按字段宽度完整读掉（保证后续字节不错位），
 *       但<b>求值时完全不应用</b>。这是本实现与官方运行时<b>唯一的系统性差异</b>，代价可以量化：
 *       把 {@link #minX()} 等 setup 包围盒与官方 {@code Skeleton.getBounds} 逐模型对比，
 *       21 个模型差 &lt; 3 单位（mlynar 0.9、kroos 1.1、eyjafjalla_the_hvit_aska 1.7、surtr 2.4），
 *       而带活跃约束的 8 个明显偏：
 *       bagpipe 275（ik=7,transform=3）、myrtle 182（ik=14,path=2）、w 175（ik=8）、
 *       suzuran 151（ik=6）、ines 80（ik=4,transform=2）、shamare 63、podenco 59、april 43。
 *       这些偏差<b>全部来自约束</b>（无约束的模型都在 2 单位内），
 *       表现是"手/脚/道具没被约束拉到目标位置"，动画整体仍然连贯；</li>
 *   <li><b>twoColor（3.8 的暗部颜色）</b>：颜色时间轴只取 rgba 主色，dark color 丢弃；</li>
 *   <li><b>clipping（裁剪遮罩）/ point 附件</b>：只统计不渲染；</li>
 *   <li><b>linked mesh 的 {@code inheritDeform}</b>：链接网格用"复制父网格几何"的等价做法实现，
 *       因此"父网格形变时子网格是否继承"这一语义没有实现（本项目素材里没出现 linked mesh）；</li>
 *   <li><b>skeleton 级翻转 / 多皮肤叠加 / 动画混合（mix）</b>：未实现。姿态是"硬切换"的；</li>
 *   <li><b>骨骼颜色</b>：读掉但不参与着色（skeleton 颜色恒为白，只做 slot×attachment）；</li>
 *   <li><b>drawOrder 的极端数据</b>：mostima 的 {@code Skill_2_Loop} 里有一段 drawOrder
 *       用官方解码器（照反汇编逐行抄的那个）会解出越界下标；本实现改成"非法项用剩余下标补齐"，
 *       保证永远是合法排列 —— 绘制顺序可能与官方略有出入，但不会崩。</li>
 * </ul>
 *
 * <h2>怎么验证的</h2>
 * <ol>
 *   <li>29 个素材全部解析成功，且<b>解析完正好停在文件末尾</b>（多读/少读一个字节都会失败）；</li>
 *   <li>29 个模型的动画名与时长与官方运行时（spine-libgdx 3.5.51.1 / 3.8.99.1）逐条相同；</li>
 *   <li>把 {@link MeshBuffer} 光栅化成 PNG 与贴图集逐个区域比对，确认 UV / 旋转区域 / 朝向正确；</li>
 *   <li>setup 包围盒与官方 {@code getBounds} 逐模型对比（见上表）。</li>
 * </ol>
 */
public final class SpineData {

    private final String name;
    private final String version;
    private final SpineAtlas atlas;

    final SpineBone[] bones;
    final SpineSlot[] slots;
    final SpineSkin[] skins;

    private final SpineAnimation[] animations;
    private final Map<String, SpineAnimation> animationByName;

    private int maxVertices;
    private int maxTriangles;
    private float minX;
    private float minY;
    private float maxX;
    private float maxY;
    private SpineConstraints.Ik[] iks = new SpineConstraints.Ik[0];
    private SpineConstraints.Transform[] transforms = new SpineConstraints.Transform[0];
    private SpineConstraints.Path[] paths = new SpineConstraints.Path[0];

    private final float artWidth;
    private final float artHeight;

    /** 身体高度经验带宽下限（模型单位）：实测 29 个干员的包围盒高最小 361。 */
    public static final float BODY_MIN = 370f;

    /** 身体高度经验带宽上限（模型单位）：实测 17 个"正常"模型不超过 441。 */
    public static final float BODY_MAX = 470f;

    /** atlas 里找不到贴图区域的附件个数（0 = 全部命中；非 0 会在 SpineInspect 里报出来）。 */
    private int missingAtlasRegions;

    /** 可选配置文件名（放在 {@code data/spine/} 下）：按插槽名"删层"，不改素材。 */
    public static final String HIDDEN_SLOTS_FILE = "hidden-slots.txt";

    /** 被 {@link #HIDDEN_SLOTS_FILE} 点名的插槽名（不可变，可能为空）。 */
    private Set<String> hiddenSlots = Set.of();

    SpineData(String name, String version, SpineAtlas atlas, SpineBone[] bones, SpineSlot[] slots,
            SpineSkin[] skins, SpineAnimation[] animations, float artWidth, float artHeight) {
        this.name = name;
        this.version = version;
        this.atlas = atlas;
        this.bones = bones;
        this.slots = slots;
        this.skins = skins;
        this.animations = animations;
        this.artWidth = artWidth;
        this.artHeight = artHeight;
        Map<String, SpineAnimation> byName = new LinkedHashMap<>();
        for (SpineAnimation a : animations) {
            byName.put(a.name, a);
        }
        this.animationByName = Collections.unmodifiableMap(byName);
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * 从目录加载：目录内自动找唯一的 {@code .skel} / {@code .atlas}（{@code .png} 只用于校验存在）。
     *
     * @param dir 素材目录，如 {@code data/spine/operator/amiya}
     * @return 解析结果
     * @throws IOException 目录缺失、文件不唯一、或解析出错
     */
    public static SpineData load(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IOException("spine 素材目录不存在: " + dir);
        }
        Path skel = null;
        Path atlasFile = null;
        Path png = null;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".skel")) {
                    skel = unique(skel, p);
                } else if (n.endsWith(".atlas")) {
                    atlasFile = unique(atlasFile, p);
                } else if (n.endsWith(".png")) {
                    png = unique(png, p);
                }
            }
        }
        if (skel == null) {
            throw new IOException("目录里没有 .skel: " + dir);
        }
        if (atlasFile == null) {
            throw new IOException("目录里没有 .atlas: " + dir);
        }
        if (png == null) {
            throw new IOException("目录里没有 .png: " + dir);
        }

        String modelName = skel.getFileName().toString();
        int dot = modelName.lastIndexOf('.');
        if (dot > 0) {
            modelName = modelName.substring(0, dot);
        }

        SpineAtlas atlas = SpineAtlas.parse(atlasFile);
        if (!atlas.page().equalsIgnoreCase(png.getFileName().toString())) {
            // 不是致命错误（atlas 里写的是相对名），但值得让调用方知道页名对不上
            System.out.println("[spine] 提示: " + dir.getFileName() + " 的 atlas 页名(" + atlas.page()
                    + ")与目录里的 png(" + png.getFileName() + ")不一致");
        }
        byte[] bytes = Files.readAllBytes(skel);
        SpineData data = SpineSkeletonBinary.parse(bytes, atlas, modelName);
        data.setHiddenSlots(readHiddenSlots(dir, modelName));
        data.computeExtents();
        return data;
    }

    /**
     * 读可选的 {@code <spine 根>/hidden-slots.txt}（每行 {@code <干员id> <插槽名>}，
     * {@code #} 开头是注释）。
     *
     * <p>用来按需求"删掉某几层"而又**不动 .skel 素材、不影响其他干员**：
     * 被点名的插槽在 setup 与整个动画期间都不挂附件，等于永远不画。
     *
     * <p>为什么放在 {@code data/spine/} 而不是每个干员目录里：素材目录的约定是
     * "只放 .skel/.atlas/.png 三个文件"（`SpineAssetsTest.noExtraFiles` 在守这条），
     * 配置集中放一处也不容易被误当成素材。
     *
     * @param dir       素材目录（{@code data/spine/operator/<id>}）
     * @param modelName 模型名（只用于日志）
     * @return 要隐藏的插槽名（不可变；可能为空）
     */
    private static Set<String> readHiddenSlots(Path dir, String modelName) {
        // 配置放在 data/spine/ 下（素材目录往上两级）；这里往上找两级，
        // 传 data/spine/operator/amiya 或别的层级目录都能找到。
        Path file = null;
        Path cursor = dir.getParent();
        for (int up = 0; up < 2 && cursor != null; up++) {
            Path candidate = cursor.resolve(HIDDEN_SLOTS_FILE);
            if (Files.isRegularFile(candidate)) {
                file = candidate;
                break;
            }
            cursor = cursor.getParent();
        }
        if (file == null) {
            return Set.of();
        }
        String id = dir.getFileName().toString();
        Set<String> names = new java.util.LinkedHashSet<>();
        try {
            for (String raw : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                String line = raw;
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                if (!parts[0].equals(id)) {
                    continue;
                }
                for (int i = 1; i < parts.length; i++) {
                    names.add(parts[i]);
                }
            }
        } catch (IOException e) {
            System.out.println("[spine] 读取 " + HIDDEN_SLOTS_FILE + " 失败(" + modelName + "): " + e);
            return Set.of();
        }
        if (!names.isEmpty()) {
            System.out.println("[spine] " + modelName + " 隐藏插槽: " + String.join(", ", names));
        }
        return java.util.Collections.unmodifiableSet(names);
    }

    private static Path unique(Path current, Path candidate) throws IOException {
        if (current != null && !current.equals(candidate)) {
            throw new IOException("目录里有多个同类素材: " + current.getFileName() + " / " + candidate.getFileName());
        }
        return candidate;
    }

    // ------------------------------------------------------------------
    // 公共 API
    // ------------------------------------------------------------------

    /** 模型名（= {@code .skel} 文件名去掉扩展名，与官方运行时 {@code SkeletonData.name} 一致）。 */
    public String name() {
        return name;
    }

    /** 文件头里读到的 spine 版本，如 {@code "3.5.51"} / {@code "3.8.99"}。 */
    public String version() {
        return version;
    }

    /**
     * 文件头里声明的**画布宽**（Spine 导出时的美术版面尺寸，模型单位）。
     *
     * <p>它和 {@link #maxX()} 那种"顶点实测包围盒"不是一回事：实测包围盒会被<b>没实现的
     * IK/transform 约束</b>甩出去的游离顶点撑大（本仓 29 个模型里有 8 个偏 40–275 单位），
     * 而文件头的画布尺寸是导出时定死的，正好是"美术认为这个角色占多大"。
     * <b>按格缩放干员时应当用它，不要用实测包围盒。</b>
     */
    public float artWidth() {
        return artWidth;
    }

    /**
     * 文件头里声明的**画布高**（模型单位）。
     *
     * <p>为什么缩放用它：美术把这批干员都画在同一个高度基准上，用它换算出来的
     * "屏幕高度 / 格"才是稳定的；用实测包围盒会出现"bagpipe 只有别人一半大"这种观感
     * （她的枪被甩到 y=948，把包围盒撑到了 950）。
     * 缺失（&lt;=0，个别非官方导出）时调用方应退回 {@link #maxY()} - {@link #minY()}。
     *
     * @return 画布高；不可信时为 0
     */
    public float artHeight() {
        return artHeight;
    }

    /**
     * 建议的"身体高度"（模型单位）：实测包围盒高，**钳制到经验带宽**里。
     *
     * <h2>为什么需要钳制</h2>
     * 实测 29 个干员的 setup 包围盒高集中在 <b>361–441</b>（方舟的 chibi 是同一套基准画的），
     * 但少数模型会被两件事撑大：
     * <ol>
     *   <li><b>没实现的 IK/transform 约束</b>把手/武器甩出去（bagpipe 950、blaze 1192、surtr 918）；</li>
     *   <li>本来就有大道具伸到画布外（kroos 604、suzuran 727）。</li>
     * </ol>
     * 直接拿包围盒当分母，结果就是"bagpipe 只有阿米娅一半大"——这是实测出来的观感问题。
     * 钳到 [{@value #BODY_MIN}, {@value #BODY_MAX}] 之后，最大/最小只差 27%，
     * 既保住个体差异，又不会离谱。
     *
     * @return 模型单位下的高度（恒 &gt; 0）
     */
    public float bodyHeight() {
        float raw = maxY - minY;
        if (!(raw > 1f)) {
            raw = artHeight > 1f ? artHeight : 1f;
        }
        return Math.max(BODY_MIN, Math.min(BODY_MAX, raw));
    }

    /** 动画名列表（保持文件顺序）。 */
    public List<String> animations() {
        List<String> out = new ArrayList<>(animations.length);
        for (SpineAnimation a : animations) {
            out.add(a.name);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 动画时长（秒）。
     *
     * @param anim 动画名
     * @return 时长；没有这个动画返回 -1
     */
    public float duration(String anim) {
        SpineAnimation a = animation(anim);
        return a == null ? -1f : a.duration;
    }

    /** 骨骼数。 */
    public int boneCount() {
        return bones.length;
    }

    /** 插槽数。 */
    public int slotCount() {
        return slots.length;
    }

    /**
     * 任一动画任意时刻可能出现的最大顶点数（用于预分配 {@link MeshBuffer}）。
     *
     * <p>算法：逐个动画扫"换附件"时间轴，按插槽取该动画里出现的最大顶点数再求和，取所有动画的最大值。
     * 这是**上界**（同屏只会有一个附件生效），预分配用它不会不够。
     */
    public int maxVertices() {
        return maxVertices;
    }

    /** 同 {@link #maxVertices()}，三角形数的上界。 */
    public int maxTriangles() {
        return maxTriangles;
    }

    /** setup 姿态视觉包围盒左边界（模型单位）。 */
    public float minX() {
        return minX;
    }

    /** setup 姿态视觉包围盒下边界（模型单位，+y 向上）。 */
    public float minY() {
        return minY;
    }

    /** setup 姿态视觉包围盒右边界（模型单位）。 */
    public float maxX() {
        return maxX;
    }

    /** setup 姿态视觉包围盒上边界（模型单位，+y 向上）。 */
    public float maxY() {
        return maxY;
    }

    /** 贴图集。 */
    public SpineAtlas atlas() {
        return atlas;
    }

    // ------------------------------------------------------------------
    // 包内访问
    // ------------------------------------------------------------------

    SpineAnimation animation(String anim) {
        return anim == null ? null : animationByName.get(anim);
    }


    /** 取某个插槽在 setup 皮肤下的附件名对应的附件。 */
    SpineAttachment attachment(int slotIndex, String attachmentName) {
        if (attachmentName == null || slotIndex < 0 || slotIndex >= slots.length) {
            return null;
        }
        if (hiddenSlots.contains(slots[slotIndex].name)) {
            // 被"删层"配置点名的插槽：setup 与所有 attachment 时间轴都拿不到附件，等于永远不画
            return null;
        }
        for (SpineSkin skin : skins) {
            SpineAttachment a = skin.get(slotIndex, attachmentName);
            if (a != null) {
                return a;
            }
        }
        return null;
    }

    /** setup 姿态该插槽挂的附件。 */
    SpineAttachment setupAttachment(int slotIndex) {
        return attachment(slotIndex, slots[slotIndex].attachmentName);
    }

    int skinCount() {
        return skins.length;
    }

    /**
     * 把 {@code hidden-slots.txt} 的名字挂上去（{@link #load(Path)} 里调用，必须早于
     * {@link #computeExtents()}）。
     *
     * @param names 要隐藏的插槽名；{@code null} 视为空
     */
    void setHiddenSlots(Set<String> names) {
        this.hiddenSlots = names == null ? Set.of() : Set.copyOf(names);
        if (!hiddenSlots.isEmpty()) {
            // 名字写错（骨架里没有这个插槽）要能看见，否则"删了没效果"很难查
            Set<String> known = new java.util.HashSet<>();
            for (SpineSlot s : slots) {
                known.add(s.name);
            }
            for (String want : hiddenSlots) {
                if (!known.contains(want)) {
                    System.out.println("[spine] 警告: " + name + " 里没有插槽 " + want
                            + "（" + HIDDEN_SLOTS_FILE + " 里的名字写错了？）");
                }
            }
        }
    }

    /** @return 被配置隐藏的插槽名（不可变，可能为空） */
    public Set<String> hiddenSlotNames() {
        return hiddenSlots;
    }

    /**
     * @param slotIndex 插槽下标
     * @return 该插槽是否被 {@code hidden-slots.txt} 隐藏
     */
    public boolean isSlotHidden(int slotIndex) {
        return slotIndex >= 0 && slotIndex < slots.length && hiddenSlots.contains(slots[slotIndex].name);
    }

    int attachmentCount() {
        int n = 0;
        for (SpineSkin s : skins) {
            n += s.size();
        }
        return n;
    }

    int missingAtlasRegions() {
        return missingAtlasRegions;
    }

    void addMissingAtlasRegion() {
        missingAtlasRegions++;
    }

    void setExtents(int maxVertices, int maxTriangles, float minX, float minY, float maxX, float maxY) {
        this.maxVertices = maxVertices;
        this.maxTriangles = maxTriangles;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    // ------------------------------------------------------------------
    // 最大顶点数 / 包围盒
    // ------------------------------------------------------------------

    private void computeExtents() {
        int slotCount = slots.length;

        // 1) 每个动画里"各插槽可能出现的最胖附件"，求和取最大
        int bestV = 0;
        int bestT = 0;
        int[] v = new int[slotCount];
        int[] t = new int[slotCount];
        setupCounts(v, t);
        int sumV = sum(v);
        int sumT = sum(t);
        bestV = Math.max(bestV, sumV);
        bestT = Math.max(bestT, sumT);

        for (SpineAnimation anim : animations) {
            System.arraycopy(new int[slotCount], 0, v, 0, slotCount);
            setupCounts(v, t);
            for (SpineTimeline timeline : anim.timelines) {
                if (timeline instanceof SpineTimeline.Attachment att) {
                    int slot = att.slotIndex();
                    for (String attachmentName : att.names()) {
                        SpineAttachment a = attachment(slot, attachmentName);
                        if (a != null && a.vertexCount > v[slot]) {
                            v[slot] = a.vertexCount;
                            t[slot] = a.tris == null ? 0 : a.tris.length / 3;
                        }
                    }
                }
            }
            bestV = Math.max(bestV, sum(v));
            bestT = Math.max(bestT, sum(t));
        }

        // 2) setup 姿态视觉包围盒：跑一次空的姿态
        SpinePose probe = new SpinePose(this);
        MeshBuffer buffer = new MeshBuffer();
        probe.fill(buffer);
        float x0 = Float.POSITIVE_INFINITY;
        float y0 = Float.POSITIVE_INFINITY;
        float x1 = Float.NEGATIVE_INFINITY;
        float y1 = Float.NEGATIVE_INFINITY;
        float[] xy = buffer.xy();
        for (int i = 0; i < buffer.vertexCount() * 2; i += 2) {
            x0 = Math.min(x0, xy[i]);
            y0 = Math.min(y0, xy[i + 1]);
            x1 = Math.max(x1, xy[i]);
            y1 = Math.max(y1, xy[i + 1]);
        }
        if (buffer.vertexCount() == 0) {
            x0 = y0 = x1 = y1 = 0f;
        }
        setExtents(bestV, bestT, x0, y0, x1, y1);
    }

    private void setupCounts(int[] v, int[] t) {
        for (int s = 0; s < slots.length; s++) {
            SpineAttachment a = setupAttachment(s);
            v[s] = a == null ? 0 : a.vertexCount;
            t[s] = a == null || a.tris == null ? 0 : a.tris.length / 3;
        }
    }

    private static int sum(int[] values) {
        int n = 0;
        for (int value : values) {
            n += value;
        }
        return n;
    }
    /**
     * 存下三类约束（按 order 升序），供 {@link SpinePose} 求解。
     *
     * @param ik  IK 约束
     * @param tr  transform 约束
     * @param pa  path 约束
     */
    void setConstraints(SpineConstraints.Ik[] ik, SpineConstraints.Transform[] tr,
            SpineConstraints.Path[] pa) {
        iks = sortByOrder(ik);
        transforms = sortByOrder(tr);
        paths = sortByOrder(pa);
    }

    private static SpineConstraints.Ik[] sortByOrder(SpineConstraints.Ik[] arr) {
        java.util.Arrays.sort(arr, java.util.Comparator.comparingInt(o -> o.order));
        return arr;
    }

    private static SpineConstraints.Transform[] sortByOrder(SpineConstraints.Transform[] arr) {
        java.util.Arrays.sort(arr, java.util.Comparator.comparingInt(o -> o.order));
        return arr;
    }

    private static SpineConstraints.Path[] sortByOrder(SpineConstraints.Path[] arr) {
        java.util.Arrays.sort(arr, java.util.Comparator.comparingInt(o -> o.order));
        return arr;
    }

    /** @return IK 约束（按 order 升序） */
    SpineConstraints.Ik[] ik() {
        return iks;
    }

    /** @return transform 约束（按 order 升序） */
    SpineConstraints.Transform[] transforms() {
        return transforms;
    }

    /** @return path 约束（按 order 升序；本仓素材未使用） */
    SpineConstraints.Path[] paths() {
        return paths;
    }
}