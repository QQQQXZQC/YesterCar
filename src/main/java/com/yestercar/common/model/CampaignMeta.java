package com.yestercar.common.model;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code campaign.json} 的内存表示。
 *
 * <h2>⚠️ 「一个战役 = 一张地图」，没有中间层</h2>
 * 早期按策划 §3.4 实现时，{@code campaign.json} 里有一个 {@code levels} 数组，
 * 指向 {@code levels/level_xxx.json}，由后者再指向地图。**那一层已经去掉了**，
 * 理由是产品定义：<strong>战役就是关卡地图</strong>，不存在"一个战役包含多个关卡"。
 *
 * <p>留着 {@code levels} 会造出一层「界面上永远看不见、代码里却必须处理」的结构
 * （列表、越界、空列表），那是纯负担。而且它还会让每个战役目录里多一个
 * **永远只有一份**的 {@code level.json} —— 等于把一张表拆成两个文件。
 *
 * <p>所以波次与规则参数直接并进本文件，见 {@link #params()} 与 {@link #waves()}。
 *
 * <h2>字段顺序就是 JSON 里的书写顺序</h2>
 * {@code CampaignJsonIO} 按这个顺序写盘，所以 diff 稳定、人工可读 ——
 * 与 {@code MapJsonIO} 手写 JSON 的理由相同（Processing 的 {@code JSONObject}
 * 没有缩进输出）。
 *
 * @param version     契约版本，必须为 {@value #CURRENT_VERSION}
 * @param id          战役标识，<strong>等于目录名</strong>
 * @param name        显示名
 * @param author      作者；官方战役固定「开发者」，自定义战役默认取玩家代号
 * @param description 简介，可为空字符串
 * @param map         地图文件的相对路径，如 {@code map/演示地图.json}
 * @param params      关卡规则参数
 * @param waves       波次列表（有序）
 */
public record CampaignMeta(int version, String id, String name, String author,
                           String description, String map,
                           LevelParams params, List<Wave> waves) {

    /** 当前契约版本。 */
    public static final int CURRENT_VERSION = 1;

    /** 官方战役固定的作者名。 */
    public static final String OFFICIAL_AUTHOR = "开发者";

    /**
     * 地图文件所在的子目录名。
     *
     * <p>⚠️ 是单数 {@code map}，不是复数 {@code maps}。
     * 一个战役只有**一张**地图，复数会暗示"里面有很多张"。
     */
    public static final String MAP_DIR = "map";

    /**
     * 地图文件的默认名（不含子目录）。
     *
     * <p>⚠️ **地图文件名 ≡ 战役名**，所以本常量只在"还没确定战役名"时兜底，
     * 正常路径一律用 {@link #mapPathFor(String)}。
     */
    public static final String DEFAULT_MAP_FILE = "map.json";

    /**
     * 默认的地图相对路径（兜底用，正常路径见 {@link #mapPathFor(String)}）。
     */
    public static final String DEFAULT_MAP = MAP_DIR + "/" + DEFAULT_MAP_FILE;

    /**
     * 某个战役的**地图相对路径**，如 {@code map/演示地图.json}。
     *
     * <h2>⚠️ 地图文件名跟着战役名走</h2>
     * 曾经这里固定写死 {@code map_001.json}，理由是"内部文件名不该被界面上的名字牵动"。
     * 那条理由站不住：既然**一个战役 = 一张地图**，那战役文件夹里的每一个名字
     * （文件夹名、地图文件名、{@code campaign.json} 里的 {@code name}）
     * 说的都是同一件事，**没有理由让其中一个叫 {@code map_001}**。
     *
     * <p>玩家打开自己战役的文件夹时，看到的是：
     * <pre>
     * data/campaigns/演示地图/
     *   campaign.json
     *   map/演示地图.json
     * </pre>
     * 一眼就能看出这三个名字指的是同一个战役。
     *
     * @param campaignName 战役名（同时是文件夹名）
     * @return 相对路径
     */
    public static String mapPathFor(String campaignName) {
        String n = campaignName == null || campaignName.isBlank()
                ? DEFAULT_MAP_FILE.replace(".json", "")
                : campaignName;
        return MAP_DIR + "/" + n + ".json";
    }

    /**
     * 紧凑构造器：把可空/非法字段兜住，并做不可变拷贝。
     */
    public CampaignMeta {
        id = id == null ? "" : id;
        name = name == null || name.isBlank() ? id : name;
        author = author == null ? "" : author;
        description = description == null ? "" : description;
        map = map == null || map.isBlank() ? DEFAULT_MAP : map;
        params = params == null ? LevelParams.DEFAULT : params;
        waves = waves == null ? List.of() : List.copyOf(waves);
    }

    /**
     * 造一个全新的战役（一张空地图、没有波次）。
     *
     * @param id   战役标识（等于目录名）
     * @param name 显示名
     * @return 版本为当前版本、参数为默认值、无关卡的元数据
     */
    public static CampaignMeta create(String id, String name) {
        String n = name == null || name.isBlank() ? id : name;
        return new CampaignMeta(CURRENT_VERSION, id, n, OFFICIAL_AUTHOR, "",
                mapPathFor(n), LevelParams.DEFAULT, List.of());
    }

    /**
     * 换一个 id，**显示名与地图路径也跟着换**。
     *
     * <p>用于「另存为」：复制一份战役、目录名变了，id、name、map 三者都要跟着变 ——
     * 因为「一个战役 = 一张地图」，而战役名 ≡ 文件夹名 ≡ 地图文件名
     * （见 {@link #mapPathFor(String)}）。留着旧名字会让主菜单里
     * 出现"名字叫 A、文件在 B"的战役。
     *
     * <p>若想只改 id 保留旧显示名，用 {@link #withName} 再改回来。
     *
     * @param newId 新标识
     * @return 新的元数据
     */
    public CampaignMeta withId(String newId) {
        return new CampaignMeta(version, newId, newId, author, description,
                renameMapIfConventional(map, newId), params, waves);
    }

    /**
     * 换显示名，**地图路径跟着换**（id 不变）。
     *
     * @param newName 新显示名
     * @return 新的元数据
     */
    public CampaignMeta withName(String newName) {
        String n = newName == null || newName.isBlank() ? id : newName;
        return new CampaignMeta(version, id, n, author, description,
                renameMapIfConventional(map, n), params, waves);
    }

    /**
     * 如果地图路径走的是"约定命名"，就跟着新名字改；自定义路径原样保留。
     *
     * <h2>为什么要区分</h2>
     * 约定命名是 {@code map/<战役名>.json}（见 {@link #mapPathFor(String)}）。
     * 改名时它当然要跟着改，否则就会出现"战役叫 B、地图文件还叫 A.json"。
     *
     * <p>但 {@code map} 字段是**契约允许自定义**的（手工写的战役可以指向任意文件）。
     * 那种情况下改名去动它，等于把别人精心指定的路径改坏。
     *
     * @param current 当前的地图相对路径
     * @param newName 新的战役名
     * @return 该用的地图相对路径
     */
    private static String renameMapIfConventional(String current, String newName) {
        if (current == null || !current.startsWith(MAP_DIR + "/")) {
            return current;         // 自定义路径（或空）→ 不动
        }
        return mapPathFor(newName);
    }

    /**
     * 换作者。
     *
     * @param newAuthor 新作者名
     * @return 新的元数据
     */
    public CampaignMeta withAuthor(String newAuthor) {
        return new CampaignMeta(version, id, name, newAuthor, description,
                map, params, waves);
    }

    /**
     * 换波次列表。
     *
     * @param newWaves 新列表
     * @return 新的元数据
     */
    public CampaignMeta withWaves(List<Wave> newWaves) {
        return new CampaignMeta(version, id, name, author, description,
                map, params, newWaves);
    }

    /**
     * 换一份规则参数。
     *
     * @param newParams 新参数
     * @return 新的元数据
     */
    public CampaignMeta withParams(com.yestercar.common.model.LevelParams newParams) {
        return new CampaignMeta(version, id, name, author, description,
                map, newParams, waves);
    }

    /**
     * @return 是否配置了任何波次
     */
    public boolean hasWaves() {
        return !waves.isEmpty();
    }

    /**
     * @return 所有波次加起来放多少个敌人
     */
    public int totalEnemies() {
        int n = 0;
        for (Wave w : waves) {
            n += w.totalCount();
        }
        return n;
    }

    /**
     * 所有出怪组引用到的门 id。
     *
     * <p>给 {@code CampaignValidator}（P4.1）校验「引用不存在的门 ID」用，
     * 所以这里只收集不判断 —— 判断需要地图，那是另一个对象。
     *
     * @return 红门 id 列表（可能有重复）
     */
    public List<Integer> referencedRedDoors() {
        List<Integer> out = new ArrayList<>();
        for (Wave w : waves) {
            for (Wave.EnemySpawn s : w.spawns()) {
                out.add(s.door());
            }
        }
        return out;
    }

    /**
     * @return 出怪组引用到的目标蓝门 id 列表（可能有重复）
     */
    public List<Integer> referencedBlueDoors() {
        List<Integer> out = new ArrayList<>();
        for (Wave w : waves) {
            for (Wave.EnemySpawn s : w.spawns()) {
                out.add(s.targetBlueDoor());
            }
        }
        return out;
    }

    /**
     * @return 供日志与状态栏用的一行摘要
     */
    public String summary() {
        return String.format("%s(%s) 地图 %s  波次 %d 共 %d 只",
                name, id, map, waves.size(), totalEnemies());
    }
}
