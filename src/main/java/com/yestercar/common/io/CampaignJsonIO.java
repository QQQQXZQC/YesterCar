package com.yestercar.common.io;

import com.yestercar.common.model.CampaignMeta;
import com.yestercar.common.model.LevelParams;
import com.yestercar.common.model.Wave;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code campaign.json} 的读写。
 *
 * <h2>读：两种来源</h2>
 * 官方战役在 <strong>jar 内</strong>（{@code src/main/resources/campaigns/}），
 * 只能当资源流读；玩家自建战役在磁盘上（{@code data/campaigns/}）。
 * 两条路必须都支持 —— 这是"官方战役只读"的技术根源：
 * <strong>jar 里的东西物理上写不回去</strong>。
 *
 * <h2>写：手拼 JSON，不用 {@code JSONObject}</h2>
 * 与 {@link com.yestercar.campaigneditor.io.MapJsonIO} 同一个理由：
 * Processing 的 {@code JSONObject} <strong>没有缩进输出</strong>
 * （只有无参 {@code toString()} 与 {@code write(PrintWriter)}）。
 * 战役文件要给人看、要进 git，压成一行 diff 没法读。
 * <strong>读取仍用 Processing 的解析器</strong> —— 那部分工作正常。
 */
public final class CampaignJsonIO {

    /** 契约文件名。 */
    public static final String FILE_NAME = "campaign.json";

    private CampaignJsonIO() {
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 从磁盘读一个战役。
     *
     * @param file {@code campaign.json} 路径
     * @return 元数据
     * @throws IOException 文件不存在或格式非法
     */
    public static CampaignMeta load(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        // 严格绑定：以**目录名**为准，不信 JSON 里写的 id
        String dirName = dirNameOf(file);
        return parse(text, dirName);
    }

    /**
     * 从 jar 内的资源读一个战役。
     *
     * @param resourcePath 相对 classpath 的路径，如 {@code campaigns/official_01/campaign.json}
     * @param dirName      战役目录名（用于严格绑定的 id）
     * @return 元数据；资源读不到时返回 {@code null}
     */
    public static CampaignMeta loadResource(String resourcePath, String dirName) {
        try (InputStream in = CampaignJsonIO.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(text, dirName);
        } catch (IOException | RuntimeException e) {
            System.err.println("[CampaignJsonIO] 读取资源失败 " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 解析 {@code campaign.json} 的文本。
     *
     * <h2>{@code id} 与目录名严格绑定</h2>
     * 与地图名 ≡ 文件名的规则完全一致（见 {@code MapNaming}）：
     * <strong>{@code id} 以目录名为准</strong>，手改过 JSON 的 {@code id} 会被纠正回来。
     * 理由是同一个 —— 与其指望每个调用方都记得同步，不如在唯一的读入口钉死。
     *
     * <p>文件里写的 id 与目录名不符时**只告警不失败**：
     * 战役仍可玩，只是提示数据有问题。
     *
     * @param text    文件内容
     * @param dirName 战役目录名；{@code null} 时退回用文件里写的 id
     * @return 元数据
     * @throws IOException 不是合法 JSON、或缺少必要字段
     */
    public static CampaignMeta parse(String text, String dirName) throws IOException {
        processing.data.JSONObject o;
        try {
            o = processing.data.JSONObject.parse(text);
        } catch (RuntimeException e) {
            throw new IOException("campaign.json 解析失败: " + e.getMessage(), e);
        }
        if (o == null) {
            throw new IOException("campaign.json 内容为空");
        }

        int version = o.getInt("version", 0);
        if (version != CampaignMeta.CURRENT_VERSION) {
            // 不抛异常：将来加版本迁移时，这里是最自然的接入点
            System.err.println("[CampaignJsonIO] 契约版本不是 " + CampaignMeta.CURRENT_VERSION
                    + "，实际 " + version + "（仍尝试按当前格式读）");
        }

        String declaredId = o.getString("id", "");
        String id = dirName != null && !dirName.isBlank() ? dirName : declaredId;
        if (dirName != null && !declaredId.isEmpty() && !declaredId.equals(dirName)) {
            System.err.println("[CampaignJsonIO] ⚠ id 与目录名不符：文件里写「" + declaredId
                    + "」、目录叫「" + dirName + "」—— 以目录名为准");
        }

        List<Wave> waves = parseWaves(o);

        return new CampaignMeta(version, id,
                o.getString("name", ""),
                o.getString("author", ""),
                o.getString("description", ""),
                o.getString("map", CampaignMeta.DEFAULT_MAP),
                parseParams(o),
                waves);
    }

    /**
     * 读关卡规则参数。
     *
     * <p>字段缺一个就用那个字段的默认值 —— 与 {@code LevelParams.DEFAULT} 一致。
     * 这些字段是 P4.1 的表单才写的，所以**现在读到的官方战役很可能一个都没写**，
     * 那时整份用默认值。
     */
    private static LevelParams parseParams(processing.data.JSONObject o) {
        LevelParams d = LevelParams.DEFAULT;
        return new LevelParams(
                o.getInt("initialCost", d.initialCost()),
                o.getFloat("costPerSecond", d.costPerSecond()),
                o.getInt("obstacleCost", d.obstacleCost()),
                o.getInt("obstacleHp", d.obstacleHp()),
                o.getInt("obstacleBlock", d.obstacleBlock()),
                o.getInt("obstacleCount", d.obstacleCount()),
                o.getFloat("obstacleRedeployTime", d.obstacleRedeployTime()),
                o.getInt("life", d.life()));
    }

    /**
     * 读波次列表。
     *
     * <p>⚠️ {@code waves} 是 {@code null} 或不是数组时要**当作空表**而不是抛异常 ——
     * 它们是有效输入（"这一关还没配波次"），而且 P4.1 之前官方战役里可能根本没有。
     */
    private static List<Wave> parseWaves(processing.data.JSONObject o) {
        List<Wave> out = new ArrayList<>();
        processing.data.JSONArray arr;
        try {
            arr = o.getJSONArray("waves");
        } catch (RuntimeException e) {
            System.err.println("[CampaignJsonIO] waves 不是数组，按空表处理: " + e.getMessage());
            return out;
        }
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.size(); i++) {
            processing.data.JSONObject w;
            try {
                w = arr.getJSONObject(i);
            } catch (RuntimeException e) {
                System.err.println("[CampaignJsonIO] 第 " + i + " 个波次不是对象，已跳过");
                continue;
            }
            List<Wave.EnemySpawn> spawns = new ArrayList<>();
            processing.data.JSONArray sa;
            try {
                sa = w.getJSONArray("spawns");
            } catch (RuntimeException e) {
                sa = null;
            }
            if (sa != null) {
                for (int j = 0; j < sa.size(); j++) {
                    processing.data.JSONObject sp;
                    try {
                        sp = sa.getJSONObject(j);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    String enemy = sp.getString("enemy", "");
                    if (enemy.isBlank()) {
                        System.err.println("[CampaignJsonIO] 第 " + i + " 波第 " + j
                                + " 组没有 enemy 字段，已跳过");
                        continue;
                    }
                    spawns.add(new Wave.EnemySpawn(enemy,
                            sp.getInt("door", 0),
                            sp.getInt("targetBlueDoor", 0),
                            sp.getInt("count", 1),
                            sp.getFloat("interval", 1f)));
                }
            }
            out.add(new Wave(w.getFloat("time", 0f), spawns));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 把战役元数据写成 {@code campaign.json} 的文本。
     *
     * <p>2 空格缩进、字段顺序固定、{@code levels} 写成单行数组
     * （关卡名很短，一行更易读）。
     *
     * @param meta 元数据
     * @return JSON 文本
     */
    public static String toJson(CampaignMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(meta.version()).append(",\n");
        sb.append("  \"id\": ").append(quote(meta.id())).append(",\n");
        sb.append("  \"name\": ").append(quote(meta.name())).append(",\n");
        sb.append("  \"author\": ").append(quote(meta.author())).append(",\n");
        sb.append("  \"description\": ").append(quote(meta.description())).append(",\n");
        sb.append("  \"map\": ").append(quote(meta.map())).append(",\n");

        // 关卡规则参数：逐行写出，字段名与策划 §3.2 一致
        LevelParams p = meta.params();
        sb.append("  \"initialCost\": ").append(p.initialCost()).append(",\n");
        sb.append("  \"costPerSecond\": ").append(num(p.costPerSecond())).append(",\n");
        sb.append("  \"obstacleCost\": ").append(p.obstacleCost()).append(",\n");
        sb.append("  \"obstacleHp\": ").append(p.obstacleHp()).append(",\n");
        sb.append("  \"obstacleBlock\": ").append(p.obstacleBlock()).append(",\n");
        sb.append("  \"obstacleCount\": ").append(p.obstacleCount()).append(",\n");
        sb.append("  \"obstacleRedeployTime\": ").append(num(p.obstacleRedeployTime())).append(",\n");

        sb.append("  \"life\": ").append(p.life()).append(",\n");
        appendWaves(sb, meta.waves());
        sb.append("}\n");
        return sb.toString();
    }

    /** 写波次数组。空数组写成一行 `[]`，便于一眼看出"还没配波次"。 */
    private static void appendWaves(StringBuilder sb, List<Wave> waves) {
        if (waves.isEmpty()) {
            sb.append("  \"waves\": []\n");
            return;
        }
        sb.append("  \"waves\": [\n");
        for (int i = 0; i < waves.size(); i++) {
            Wave w = waves.get(i);
            sb.append("    {\n");
            sb.append("      \"time\": ").append(num(w.time())).append(",\n");
            if (w.spawns().isEmpty()) {
                sb.append("      \"spawns\": []\n");
            } else {
                sb.append("      \"spawns\": [\n");
                for (int j = 0; j < w.spawns().size(); j++) {
                    Wave.EnemySpawn s = w.spawns().get(j);
                    sb.append("        {\n");
                    sb.append("          \"enemy\": ").append(quote(s.enemy())).append(",\n");
                    sb.append("          \"door\": ").append(s.door()).append(",\n");
                    sb.append("          \"targetBlueDoor\": ").append(s.targetBlueDoor()).append(",\n");
                    sb.append("          \"count\": ").append(s.count()).append(",\n");
                    sb.append("          \"interval\": ").append(num(s.interval())).append('\n');
                    sb.append("        }").append(j < w.spawns().size() - 1 ? "," : "").append('\n');
                }
                sb.append("      ]\n");
            }
            sb.append("    }").append(i < waves.size() - 1 ? "," : "").append('\n');
        }
        sb.append("  ]\n");
    }

    /**
     * 把浮点数写成 JSON。
     *
     * <p>整数形式的浮点（{@code 1.0}）写成 {@code 1} —— 文件更干净，
     * 而且读回来是等值的（{@code getFloat} 对整数也成立）。
     *
     * @param v 数值
     * @return JSON 里的字面量
     */
    public static String num(float v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /**
     * 写盘。
     *
     * <h2>{@code id} 强制对齐到目录名</h2>
     * 与 {@code MapJsonIO.save} 对齐 {@code name} 完全同一个思路：
     * 在<strong>唯一的写盘出口</strong>上钉死，就不可能写出"目录叫 A、里面写着 B"的战役。
     *
     * <p>⚠️ 本重载按**可写战役**对齐（显示名 ≡ 目录名）。
     * 要写"显示名与目录名不同"的官方战役，用
     * {@link #save(CampaignMeta, Path, boolean)} 并传 {@code writable=false}。
     *
     * @param meta 元数据
     * @param file 目标路径
     * @throws IOException 写失败
     */
    public static void save(CampaignMeta meta, Path file) throws IOException {
        save(meta, file, true);
    }

    /**
     * 写 {@code campaign.json}，可指定它是不是"可写战役"。
     *
     * @param meta     元数据
     * @param file     目标路径
     * @param writable {@code false} 时保留原有显示名（官方战役的用法）
     * @throws IOException 写失败
     */
    public static void save(CampaignMeta meta, Path file, boolean writable)
            throws IOException {
        CampaignMeta aligned = alignTo(meta, dirNameOf(file), writable);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, toJson(aligned), StandardCharsets.UTF_8);
    }

    /**
     * 把元数据对齐到它所在的**目录**。
     *
     * <h2>字段来源：只有 {@code id} 是权威的，其余两个派生</h2>
     *
     * <table border="1">
     *   <caption>字段的来源</caption>
     *   <tr><th>字段</th><th>来源</th><th>理由</th></tr>
     *   <tr><td>{@code id}</td><td><b>目录名</b></td>
     *       <td>契约硬规则；加载时也一律以目录名为准 —— 两边必须一致</td></tr>
     *   <tr><td>{@code map}</td><td>{@code map/<id>.json}</td>
     *       <td>与地图的落点由同一个规则决定（见 {@link CampaignStore#saveWithMapAt}）</td></tr>
     *   <tr><td>{@code name}</td><td>默认同 {@code id}</td>
     *       <td>玩家自己起的战役名就是文件夹名；<b>只有只读的官方战役</b>
     *           才允许显示名与目录名不同（它要写「第一关：边境哨站」，
     *           含全角冒号，不能当目录名）</td></tr>
     * </table>
     *
     * <h2>⚠️ {@code name} 的对齐规则</h2>
     * 玩家战役（可写）的 name <b>必须</b>等于目录名 —— 否则主菜单会显示
     * 一个跟文件夹对不上的名字。官方战役（只读）保留它自己的显示名。
     *
     * <p>曾经这里用了一个更弱的启发式（"原 name 等于原 id 才更新"），
     * 结果是：以 {@code (id=旧名, name="我的战役")} 存盘时名字不更新，
     * 而 {@code map} 已经改成新路径了 —— 名字与路径双双不一致，
     * 表现就是"存好了但打开后名字不对、地图还找不到"。
     *
     * <h2>这里修过一个真 bug</h2>
     * {@code map} 曾经是**只读随调用方传进来的普通字段**，于是
     * {@code name} 更新了、{@code map} 还指着旧文件名。
     * 把它钉在唯一写盘出口上推导，这种不一致就不可能发生
     * （与"id ≡ 目录名"同一条思路：<b>能推导的就不存两份</b>）。
     *
     * @param meta    元数据
     * @param dirName 战役目录名
     * @param writable 这个战役是否可写（{@code false} = 官方只读战役）
     * @return 对齐后的元数据
     */
    public static CampaignMeta alignTo(CampaignMeta meta, String dirName, boolean writable) {
        String newId = dirName == null || dirName.isBlank() ? meta.id() : dirName;

        // 官方（只读）保留自己的显示名；玩家战役的显示名 ≡ 目录名
        String name = meta.name();
        if (writable || name == null || name.isBlank()) {
            name = newId;
        }

        return new CampaignMeta(meta.version(), newId, name,
                meta.author(), meta.description(),
                CampaignMeta.mapPathFor(newId), meta.params(), meta.waves());
    }

    /**
     * {@link #alignTo(CampaignMeta, String, boolean)} 的可写版本。
     *
     * @param meta    元数据
     * @param dirName 战役目录名
     * @return 对齐后的元数据
     */
    public static CampaignMeta alignTo(CampaignMeta meta, String dirName) {
        return alignTo(meta, dirName, true);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 取 {@code campaign.json} 所在的目录名。
     *
     * @param campaignJsonFile {@code campaign.json} 的路径
     * @return 目录名；路径没有父目录时返回空串
     */
    public static String dirNameOf(Path campaignJsonFile) {
        Path parent = campaignJsonFile.getParent();
        if (parent == null || parent.getFileName() == null) {
            return "";
        }
        return parent.getFileName().toString();
    }

    /**
     * 把字符串转义成 JSON 字面量。
     *
     * <p>只处理必须转义的字符。中文<strong>不转义</strong> ——
     * 文件里直接写中文更好读，而 JSON 本身允许 UTF-8。
     *
     * @param s 原字符串
     * @return 带引号的 JSON 字符串
     */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * 读官方战役的清单 {@code campaigns/index.json}。
     *
     * <p>按具体设计 §2.5：索引文件**不再是必需品**，用途降级为
     * "官方战役的显式清单（顺序可控）"与"列举失效时的降级方案"。
     * 所以读不到时返回<strong>空表</strong>而不是报错 ——
     * 调用方应当以扫描目录为主、索引为辅。
     *
     * @param resourcePath 索引文件在 classpath 里的路径
     * @return 战役 id 列表（保持文件里的顺序）；读不到时为空表
     */
    public static List<String> loadIndex(String resourcePath) {
        List<String> out = new ArrayList<>();
        try (InputStream in = CampaignJsonIO.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                return out;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            processing.data.JSONArray arr =
                    processing.data.JSONObject.parse(text).getJSONArray("campaigns");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.size(); i++) {
                String id = arr.getString(i, "");
                if (!id.isBlank()) {
                    out.add(id);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[CampaignJsonIO] 索引读取失败（将回退到扫描目录）: " + e.getMessage());
        }
        return out;
    }

    /**
     * 给测试与诊断用：把元数据摊成字符串。
     *
     * @param meta 元数据
     * @return 多行文本
     */
    public static String describe(CampaignMeta meta) {
        return "id=" + meta.id() + '\n'
                + "name=" + meta.name() + '\n'
                + "author=" + meta.author() + '\n'
                + "version=" + meta.version() + '\n'
                + "description=" + meta.description() + '\n'
                + "map=" + meta.map() + '\n'
                + "params=" + meta.params().summary() + '\n'
                + "waves=" + meta.waves().size() + " 波，共 " + meta.totalEnemies() + " 只";
    }
}
