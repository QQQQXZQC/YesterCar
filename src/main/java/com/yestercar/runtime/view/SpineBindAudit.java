package com.yestercar.runtime.view;

import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.Operator;
import com.yestercar.runtime.spine.MeshBuffer;
import com.yestercar.runtime.spine.SpineData;
import com.yestercar.runtime.spine.SpinePose;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * 绑定自检：**逐个**核对"我方干员的骨骼动画到底有没有真的接上"。
 *
 * <p>和 {@code SpineGallery} 的分工：画廊只证明"能画出来"，这个工具证明
 * **游戏运行时会用到的那条链路每一环都通**：
 *
 * <ol>
 *   <li>素材契约：{@code data/spine/operator/<id>/} 正好三件套，atlas 里声明的 png 真的在；</li>
 *   <li>解析：{@code .skel} 版本受支持、骨骼/插槽/动画数非零；</li>
 *   <li>状态机：按 {@link OperatorAnimator} 的候选名规则解析出
 *       <b>登场 / 待机 / 出手 / 阵亡</b> 四个动画名与时长（缺哪个、回退到哪个都要写清楚）；</li>
 *   <li>姿态求值：四个状态各采一帧，{@code fill()} 出的顶点/三角形必须非空、
 *       坐标不能是 NaN、包围盒高度要在人形量级（防止"约束散了/整个人飞出去"）；</li>
 *   <li>贴图：png 能被 ImageIO 解码，且尺寸与 atlas 头部声明一致；</li>
 *   <li>战斗内绑定：{@link SpineUnitLayer} 用<b>中文显示名</b>反查 id，
 *       这里逐条验证"显示名 → id"能回到自己（重名会让两个干员互相串模型）。</li>
 * </ol>
 *
 * <p>无窗口、纯 JDK（不开 P2D），所以能进 CI。
 *
 * <h2>用法</h2>
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp target/YesterCar.jar \
 *      com.yestercar.runtime.view.SpineBindAudit --out docs\骨骼动画检查
 * </pre>
 *
 * <p>产物：{@code <out>/bind-audit.md}（人读）、{@code <out>/bind-audit.tsv}（机读）。
 * 任何一个 FAIL 时进程退出码为 1。
 */
public final class SpineBindAudit {

    /** 三件套扩展名。 */
    private static final String[] EXTS = {".skel", ".atlas", ".png"};

    /** 人形包围盒高度允许范围（世界单位）。正常 300–900，超界基本是"约束散架"。 */
    private static final float MIN_HEIGHT = 80f;
    private static final float MAX_HEIGHT = 3000f;

    /** 四个状态的展示顺序。 */
    private static final String[] STATES = {"登场 Start", "待机 Idle", "出手 Attack", "阵亡 Die"};

    /**
     * 一个干员的体检结果。
     *
     * @param id      干员 id
     * @param name    中文显示名
     * @param ok      是否通过（FAIL 即不通过）
     * @param warns   警告（能用，但降级了）
     * @param errors  错误（绑定不成立）
     */
    private record Row(String id, String name, String version, int bones, int slots, int anims,
                       String start, float startDur, String idle, float idleDur,
                       String attack, float attackDur, String die, float dieDur,
                       int texW, int texH, int atlasW, int atlasH,
                       int verts, int tris, float minX, float minY, float maxX, float maxY,
                       boolean ok, List<String> warns, List<String> errors) {

        /** @return 一句话结论 */
        String verdict() {
            if (!ok) {
                return "FAIL";
            }
            return warns.isEmpty() ? "PASS" : "WARN";
        }
    }

    /**
     * 入口。
     *
     * @param args {@code --out <目录>}
     * @throws IOException 写文件失败
     */
    public static void main(String[] args) throws IOException {
        String outDir = "docs/骨骼动画检查";
        for (int i = 0; i < args.length; i++) {
            if ("--out".equals(args[i]) && i + 1 < args.length) {
                outDir = args[++i];
            }
        }
        Path root = Workspace.projectRoot();
        Path spineRoot = root.resolve(SpineUnitLayer.SPINE_DIR);
        Map<String, Operator> ops = Operator.loadAll();
        List<String> ids = new ArrayList<>(ops.keySet());
        Collections.sort(ids);

        // 战斗内绑定走的是"显示名 → id"，先把这张表建出来查重
        Map<String, String> nameToId = new LinkedHashMap<>();
        List<String> duplicateNames = new ArrayList<>();
        for (Operator op : ops.values()) {
            String prev = nameToId.put(op.name(), op.id());
            if (prev != null && !prev.equals(op.id())) {
                duplicateNames.add(op.name() + " → " + prev + " / " + op.id());
            }
        }

        List<Row> rows = new ArrayList<>();
        for (String id : ids) {
            rows.add(audit(ops.get(id), spineRoot, nameToId));
        }

        // 目录里多出来的（没有对应干员的）素材也要报出来
        Set<String> orphanDirs = new LinkedHashSet<>();
        if (Files.isDirectory(spineRoot)) {
            try (Stream<Path> s = Files.list(spineRoot)) {
                s.filter(Files::isDirectory).forEach(p -> {
                    String n = p.getFileName().toString();
                    if (!ops.containsKey(n)) {
                        orphanDirs.add(n);
                    }
                });
            }
        }

        Path out = root.resolve(outDir);
        Files.createDirectories(out);
        String md = renderMarkdown(rows, duplicateNames, orphanDirs, nameToId, ops);
        Files.writeString(out.resolve("bind-audit.md"), md, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("bind-audit.tsv"), renderTsv(rows), StandardCharsets.UTF_8);

        long pass = rows.stream().filter(r -> r.ok() && r.warns().isEmpty()).count();
        long warn = rows.stream().filter(r -> r.ok() && !r.warns().isEmpty()).count();
        long fail = rows.stream().filter(r -> !r.ok()).count();
        System.out.printf("[Audit] 干员 %d 名：PASS %d / WARN %d / FAIL %d%n",
                rows.size(), pass, warn, fail);
        System.out.println("[Audit] 报告: " + out.resolve("bind-audit.md"));
        if (fail > 0) {
            for (Row r : rows) {
                if (!r.ok()) {
                    System.out.println("  FAIL " + r.id() + " → " + r.errors());
                }
            }
            System.exit(1);
        }
    }

    /**
     * 体检一个干员。
     *
     * @param op       干员数据
     * @param spineRoot 素材根目录
     * @param nameToId 显示名 → id（战斗内绑定用）
     * @return 结果
     */
    private static Row audit(Operator op, Path spineRoot, Map<String, String> nameToId) {
        String id = op.id();
        List<String> errors = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        Path dir = spineRoot.resolve(id);

        // ① 素材契约
        if (!Files.isDirectory(dir)) {
            errors.add("没有素材目录 " + SpineUnitLayer.SPINE_DIR + id);
            return empty(op, errors, warns);
        }
        for (String ext : EXTS) {
            long n = countExt(dir, ext);
            if (n != 1) {
                errors.add(ext + " 文件数 = " + n + "（要求 1）");
            }
        }
        if (!errors.isEmpty()) {
            return empty(op, errors, warns);
        }

        // ② 解析模型
        SpineData d;
        try {
            d = SpineData.load(dir);
        } catch (IOException | RuntimeException ex) {
            errors.add("解析失败: " + ex);
            return empty(op, errors, warns);
        }
        if (!"3.5.51".equals(d.version()) && !"3.8.99".equals(d.version())) {
            errors.add("没验证过的版本 " + d.version());
        }
        if (d.animations().isEmpty()) {
            errors.add("动画数为 0");
        }
        if (d.boneCount() == 0) {
            errors.add("骨骼数为 0");
        }

        // ③ 状态机四个动画（规则与 OperatorAnimator 完全一致）
        String idle = firstOf(d, OperatorAnimator.IDLE_NAMES);
        String start = firstOf(d, OperatorAnimator.START_NAMES);
        String attack = firstOf(d, OperatorAnimator.ATTACK_NAMES);
        String die = firstOf(d, OperatorAnimator.DIE_NAMES);
        if (idle == null) {
            errors.add("连待机动画都解析不到（候选 " + String.join("/", OperatorAnimator.IDLE_NAMES) + "）");
        }
        if (start == null) {
            warns.add("没有 Start，登场回退到 " + idle);
        }
        if (die == null) {
            warns.add("没有 Die，阵亡回退到 " + idle + "（不会倒下，只会淡出）");
        }
        if (attack == null) {
            warns.add("没有 Attack*，出手回退到 " + idle);
        } else if (!"Attack".equals(attack)) {
            warns.add("出手用的是回退名 " + attack);
        }

        // ④ 姿态求值：四个状态各采一帧，看网格是否成立
        int verts = 0;
        int tris = 0;
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (String anim : new String[]{start != null ? start : idle, idle, attack != null ? attack : idle,
                die != null ? die : idle}) {
            if (anim == null) {
                continue;
            }
            try {
                MeshBuffer mesh = sample(d, anim);
                int v = mesh.vertexCount();
                verts = Math.max(verts, v);
                tris = Math.max(tris, mesh.triCount());
                if (v <= 0) {
                    errors.add(anim + " 采出 0 个顶点");
                    continue;
                }
                float[] xy = mesh.xy();
                for (int i = 0; i < v; i++) {
                    float x = xy[i * 2];
                    float y = xy[i * 2 + 1];
                    if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y)) {
                        errors.add(anim + " 顶点含 NaN/Inf");
                        break;
                    }
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            } catch (RuntimeException ex) {
                errors.add(anim + " 求值抛异常: " + ex);
            }
        }
        float h = maxY - minY;
        if (verts > 0) {
            if (h < MIN_HEIGHT || h > MAX_HEIGHT) {
                errors.add(String.format(Locale.ROOT,
                        "包围盒高度 %.0f 超出人形量级 [%.0f, %.0f]（大概率约束散了）",
                        h, MIN_HEIGHT, MAX_HEIGHT));
            }
            if (Math.abs(minY) > 260f) {
                warns.add(String.format(Locale.ROOT,
                        "包围盒下沿 minY=%.0f（有游离顶点/未实现的约束链）；"
                                + "运行时按模型原点对齐脚底，不影响站位", minY));
            }
        }

        // ⑤ 贴图
        int texW = 0;
        int texH = 0;
        try {
            Path png = single(dir, ".png");
            BufferedImage img = ImageIO.read(png.toFile());
            if (img == null) {
                errors.add("png 解不开: " + png.getFileName());
            } else {
                texW = img.getWidth();
                texH = img.getHeight();
                if (d.atlas().width() > 0 && d.atlas().height() > 0
                        && (texW != d.atlas().width() || texH != d.atlas().height())) {
                    errors.add("png 尺寸 " + texW + "x" + texH + " 与 atlas 声明 "
                            + d.atlas().width() + "x" + d.atlas().height() + " 不一致");
                }
            }
        } catch (IOException ex) {
            errors.add("读 png 失败: " + ex);
        }

        // ⑥ 战斗内绑定：显示名 → id 必须回到自己
        String mapped = nameToId.get(op.name());
        if (!id.equals(mapped)) {
            errors.add("战斗内映射错误：显示名「" + op.name() + "」→ " + mapped);
        }

        return new Row(id, op.name(), d.version(), d.boneCount(), d.slotCount(),
                d.animations().size(),
                start, dur(d, start), idle, dur(d, idle),
                attack, dur(d, attack), die, dur(d, die),
                texW, texH, d.atlas().width(), d.atlas().height(),
                verts, tris, minX, minY, maxX, maxY,
                errors.isEmpty(), warns, errors);
    }

    /**
     * 采一帧网格（取动画中点，和画廊 {@code --t mid} 同一时刻）。
     *
     * @param d    模型
     * @param anim 动画名
     * @return 网格
     */
    private static MeshBuffer sample(SpineData d, String anim) {
        SpinePose pose = new SpinePose(d);
        pose.play(anim, false);
        pose.setTime(Math.max(0f, d.duration(anim)) * 0.5f);
        MeshBuffer mesh = new MeshBuffer();
        pose.fill(mesh);
        return mesh;
    }

    /**
     * @param d    模型
     * @param anim 动画名（可为 null）
     * @return 时长；null 时 0
     */
    private static float dur(SpineData d, String anim) {
        return anim == null ? 0f : Math.max(0f, d.duration(anim));
    }

    /**
     * @param d          模型
     * @param candidates 候选名
     * @return 第一个真实存在的动画名
     */
    private static String firstOf(SpineData d, String... candidates) {
        return OperatorAnimator.firstOf(d, candidates);
    }

    /**
     * @param op     干员
     * @param errors 错误
     * @param warns  警告
     * @return 空结果的失败行
     */
    private static Row empty(Operator op, List<String> errors, List<String> warns) {
        return new Row(op.id(), op.name(), "-", 0, 0, 0, null, 0, null, 0, null, 0, null, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, warns, errors);
    }

    /**
     * @param dir 目录
     * @param ext 扩展名
     * @return 文件数
     */
    private static long countExt(Path dir, String ext) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(ext)).count();
        } catch (IOException ex) {
            return -1;
        }
    }

    /**
     * @param dir 目录
     * @param ext 扩展名
     * @return 唯一的那个文件；没有或多个返回 null
     */
    private static Path single(Path dir, String ext) {
        List<Path> hits = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(ext)).forEach(hits::add);
        } catch (IOException ex) {
            return null;
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /**
     * @param rows     结果
     * @param dupNames 重名
     * @param orphans  没有干员对应的素材目录
     * @param nameToId 映射表
     * @param ops      干员表
     * @return Markdown
     */
    private static String renderMarkdown(List<Row> rows, List<String> dupNames, Set<String> orphans,
                                         Map<String, String> nameToId, Map<String, Operator> ops) {
        StringBuilder b = new StringBuilder();
        long pass = rows.stream().filter(r -> r.ok() && r.warns().isEmpty()).count();
        long warn = rows.stream().filter(r -> r.ok() && !r.warns().isEmpty()).count();
        long fail = rows.stream().filter(r -> !r.ok()).count();

        b.append("# 我方干员骨骼动画绑定自检\n\n");
        b.append("> 工具：`com.yestercar.runtime.view.SpineBindAudit`（无窗口，纯 JDK）。\n");
        b.append("> 覆盖：`data/spine/operator/` × `operators.json` 的全部我方干员。\n\n");
        b.append("## 一、总览\n\n");
        b.append("| 项 | 数值 |\n| --- | --- |\n");
        b.append("| 干员数 | ").append(rows.size()).append(" |\n");
        b.append("| 绑定通过（PASS） | ").append(pass).append(" |\n");
        b.append("| 能用但有降级（WARN） | ").append(warn).append(" |\n");
        b.append("| 绑定失败（FAIL） | ").append(fail).append(" |\n");
        b.append("| 显示名重名（会让两名干员串模型） | ").append(dupNames.size()).append(" |\n");
        b.append("| 没有干员对应的素材目录 | ").append(orphans.size()).append(" |\n\n");
        if (!dupNames.isEmpty()) {
            b.append("重名：").append(String.join("；", dupNames)).append("\n\n");
        }
        if (!orphans.isEmpty()) {
            b.append("多余素材目录：").append(String.join("、", orphans)).append("\n\n");
        }

        b.append("## 二、逐人体检表\n\n");
        b.append("| 干员 | id | 版本 | 骨骼/插槽/动画 | Start | Idle | Attack | Die | 贴图 | 顶点 | 结论 |\n");
        b.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | ---: | --- |\n");
        for (Row r : rows) {
            b.append("| ").append(r.name()).append(" | `").append(r.id()).append("` | ")
                    .append(r.version()).append(" | ")
                    .append(r.bones()).append('/').append(r.slots()).append('/').append(r.anims())
                    .append(" | ").append(animCell(r.start(), r.startDur()))
                    .append(" | ").append(animCell(r.idle(), r.idleDur()))
                    .append(" | ").append(animCell(r.attack(), r.attackDur()))
                    .append(" | ").append(animCell(r.die(), r.dieDur()))
                    .append(" | ").append(r.texW() > 0 ? r.texW() + "x" + r.texH() : "缺")
                    .append(" | ").append(r.verts())
                    .append(" | ").append(r.ok() ? (r.warns().isEmpty() ? "PASS" : "WARN") : "**FAIL**")
                    .append(" |\n");
        }

        b.append("\n## 三、警告与错误明细\n\n");
        boolean any = false;
        for (Row r : rows) {
            if (r.warns().isEmpty() && r.errors().isEmpty()) {
                continue;
            }
            any = true;
            b.append("### ").append(r.name()).append("（`").append(r.id()).append("`）\n\n");
            for (String e : r.errors()) {
                b.append("- ❌ ").append(e).append('\n');
            }
            for (String w : r.warns()) {
                b.append("- ⚠️ ").append(w).append('\n');
            }
            b.append('\n');
        }
        if (!any) {
            b.append("没有警告，也没有错误。\n");
        }

        b.append("\n## 四、姿态采样（四状态各取动画中点）\n\n");
        b.append("| 干员 | 顶点 | 三角形 | 包围盒 X | 包围盒 Y | 高度 |\n");
        b.append("| --- | ---: | ---: | --- | --- | ---: |\n");
        for (Row r : rows) {
            if (r.verts() <= 0) {
                continue;
            }
            b.append("| ").append(r.name())
                    .append(" | ").append(r.verts())
                    .append(" | ").append(r.tris())
                    .append(String.format(Locale.ROOT, " | %.0f..%.0f | %.0f..%.0f | %.0f |\n",
                            r.minX(), r.maxX(), r.minY(), r.maxY(), r.maxY() - r.minY()));
        }

        b.append("\n## 五、战斗内绑定链路\n\n");
        b.append("战斗里 `SpineUnitLayer` 拿 `UnitView.name()`（**中文显示名**）反查 id，所以这条链路必须一一对应。\n\n");
        b.append("| 显示名 | id | 反查回自己 |\n| --- | --- | --- |\n");
        List<String> names = new ArrayList<>(nameToId.keySet());
        Collections.sort(names);
        for (String n : names) {
            String id = nameToId.get(n);
            b.append("| ").append(n).append(" | `").append(id).append("` | ")
                    .append(ops.containsKey(id) ? "✅" : "❌").append(" |\n");
        }
        b.append("\n（命名重复会让两个座位画成同一个模型，这是最隐蔽的一类绑定错误，已单独查重。）\n");
        return b.toString();
    }

    /**
     * @param anim 动画名
     * @param dur  时长
     * @return 表格单元
     */
    private static String animCell(String anim, float dur) {
        if (anim == null) {
            return "❌ 无";
        }
        return String.format(Locale.ROOT, "`%s` %.2fs", anim, dur);
    }

    /**
     * @param rows 结果
     * @return TSV（表头 + 每行）
     */
    private static String renderTsv(List<Row> rows) {
        StringBuilder b = new StringBuilder();
        b.append("id\tname\tversion\tbones\tslots\tanims\tstart\tstartDur\tidle\tidleDur\t"
                + "attack\tattackDur\tdie\tdieDur\ttexW\ttexH\tverts\ttris\tminX\tminY\tmaxX\tmaxY\t"
                + "verdict\tnotes\n");
        for (Row r : rows) {
            List<String> notes = new ArrayList<>();
            notes.addAll(r.errors());
            notes.addAll(r.warns());
            b.append(String.join("\t",
                    r.id(), r.name(), r.version(), "" + r.bones(), "" + r.slots(), "" + r.anims(),
                    nz(r.start()), fmt(r.startDur()), nz(r.idle()), fmt(r.idleDur()),
                    nz(r.attack()), fmt(r.attackDur()), nz(r.die()), fmt(r.dieDur()),
                    "" + r.texW(), "" + r.texH(), "" + r.verts(), "" + r.tris(),
                    fmt(r.minX()), fmt(r.minY()), fmt(r.maxX()), fmt(r.maxY()),
                    r.verdict(), notes.isEmpty() ? "" : String.join(" | ", notes)))
                    .append('\n');
        }
        return b.toString();
    }

    /**
     * @param s 字符串
     * @return 非空字符串
     */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * @param f 浮点
     * @return 保留两位的字符串
     */
    private static String fmt(float f) {
        return String.format(Locale.ROOT, "%.2f", f);
    }
}
