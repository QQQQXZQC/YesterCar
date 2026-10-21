package com.yestercar.common.io;

import com.yestercar.common.model.Campaign;
import com.yestercar.common.model.CampaignMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 从**两个根**加载战役：jar 内的官方战役，与磁盘上的自定义战役。
 *
 * <h2>发现方式：扫描目录为主，索引文件为辅</h2>
 * 按具体设计 §2.5，{@code campaigns/index.json} 从"唯一发现途径"降级为
 * 「官方战役的显式清单（顺序可控）+ 列举失效时的降级方案」。
 * 所以顺序是：
 *
 * <ol>
 *   <li><strong>扫描</strong> {@code campaigns/} 下的子目录 —— 这是主途径；</li>
 *   <li>读 {@code index.json}：里面提到但扫描没发现的，**补进来**（列举失效时靠它兜底）；
 *       里面提到的顺序**排在前面**（开发顺序可控）。</li>
 * </ol>
 *
 * <p>两者都拿不到（目录读不了、jar 协议不认识）时返回空表 ——
 * 与 {@code Workspace.listResourceChildren} 的处理一致：
 * 最坏结果是「战役列不出来」，而不是启动崩溃。
 *
 * <h2>只读判定</h2>
 * 来源是 jar → 只读；来源是 {@code data/campaigns/} → 可写。
 * <strong>不写进 JSON</strong>，因为它是"文件在哪"的属性，不是战役自身的属性。
 *
 * <h2>同名冲突</h2>
 * 自定义战役与官方战役同 id 时，<strong>以自定义为准</strong> ——
 * 玩家应当能用自己的版本覆盖官方版（否则"改不坏原版"就成了"改不了"）。
 * 冲突会记一条 issue 提示。
 */
public final class CampaignLoader {

    /** 官方战役的索引文件（相对 classpath）。 */
    public static final String INDEX_RESOURCE = "campaigns/index.json";

    private CampaignLoader() {
    }

    /**
     * 加载**全部**战役：官方 + 自定义。
     *
     * @return 战役列表，官方在前、自定义在后；同名时只保留自定义
     */
    public static List<Campaign> loadAll() {
        List<Campaign> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // ① 官方战役（jar 内）
        for (Campaign c : loadOfficial()) {
            if (seen.add(c.id())) {
                out.add(c);
            }
        }
        // ② 自定义战役（磁盘）
        for (Campaign c : loadCustom()) {
            if (seen.add(c.id())) {
                out.add(c);
            } else {
                // 同名：用自定义覆盖前面加进去的官方版
                out.removeIf(existing -> existing.id().equals(c.id()));
                out.add(c);
                System.err.println("[CampaignLoader] ⚠ 自定义战役「" + c.id()
                        + "」与官方战役同名，已用自定义版覆盖");
            }
        }
        return out;
    }

    /**
     * 只加载官方战役（jar 内）。
     *
     * @return 战役列表；资源读不到时为空表
     */
    public static List<Campaign> loadOfficial() {
        // 扫描为主。顺序：先按 index.json 排，再把扫描到的补在后面
        List<String> found = new ArrayList<>(
                Workspace.listResourceChildren(Workspace.CAMPAIGNS_RESOURCE_DIR));
        List<String> indexOrder = CampaignJsonIO.loadIndex(INDEX_RESOURCE);

        List<String> ordered = new ArrayList<>();
        for (String id : indexOrder) {
            if (!ordered.contains(id)) {
                ordered.add(id);
            }
        }
        for (String id : found) {
            if (!ordered.contains(id)) {
                ordered.add(id);
            }
        }

        List<Campaign> out = new ArrayList<>();
        for (String id : ordered) {
            CampaignMeta meta = CampaignJsonIO.loadResource(
                    Workspace.CAMPAIGNS_RESOURCE_DIR + "/" + id + "/" + CampaignJsonIO.FILE_NAME,
                    id);
            if (meta == null) {
                System.err.println("[CampaignLoader] 官方战役「" + id + "」缺少 campaign.json，已跳过");
                continue;
            }
            out.add(new Campaign(meta, null, true, collectIssues(meta, null)));
        }
        return out;
    }

    /**
     * 只加载磁盘上的自定义战役。
     *
     * @return 战役列表；{@code data/campaigns/} 不存在时为空表
     */
    public static List<Campaign> loadCustom() {
        return loadCustom(false);
    }

    /**
     * @param includeDebug 是否包含编辑器「测试」生成的 {@code _debug}
     * @return 自定义战役
     */
    public static List<Campaign> loadCustom(boolean includeDebug) {
        Path root = Workspace.campaignsDir();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Campaign> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            List<Path> dirs = children
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                // 编辑器「测试」生成的临时战役不该出现在玩家的关卡列表里
                if (!includeDebug
                        && com.yestercar.common.io.Workspace.DEBUG_CAMPAIGN_NAME.equals(id)) {
                    continue;                 // 玩家列表里不显示调试战役
                }
                Path json = dir.resolve(CampaignJsonIO.FILE_NAME);
                if (!Files.isRegularFile(json)) {
                    // _debug 之类的临时目录可能没有 campaign.json（不该发生，但别崩）
                    System.err.println("[CampaignLoader] 「" + id + "」没有 " + CampaignJsonIO.FILE_NAME
                            + "，已跳过");
                    continue;
                }
                try {
                    CampaignMeta meta = CampaignJsonIO.load(json);
                    out.add(new Campaign(meta, dir, false, collectIssues(meta, dir)));
                } catch (IOException e) {
                    System.err.println("[CampaignLoader] 「" + id + "」读取失败: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[CampaignLoader] 列举 " + root + " 失败: " + e.getMessage());
            return List.of();
        }
        return out;
    }

    /**
     * 按 id 找一个战役，官方与自定义都找。
     *
     * @param id 战役 id
     * @return 战役；找不到返回 {@code null}
     */
    public static Campaign findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Campaign c : loadAll()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        // 玩家列表屏蔽了 _debug，但编辑器的「测试」需要按 id 打开它
        for (Campaign c : loadCustom(true)) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 检查战役声明的关卡/地图文件是否**真的存在**，把不一致记下来。
     *
     * <h2>为什么"只记不拦"</h2>
     * 声明了却不存在的文件是<strong>数据错误</strong>，但拦下整个战役的加载
     * 会让主菜单里连它都看不见 —— 用户就没法发现问题、更没法修。
     * 所以策略是：**照常加载，把问题挂在 {@link Campaign#issues()} 上**，
     * 由界面显示。真正的"保存时拦截"属于 P4.2 的只读/另存为流程。
     *
     * @param meta 元数据
     * @param dir  战役目录；官方战役传 {@code null}
     * @return 问题列表，没有问题时空表
     */
    private static List<String> collectIssues(CampaignMeta meta, Path dir) {
        List<String> issues = new ArrayList<>();

        // ① 声明的地图文件必须真的存在。官方只读资源也要查 ——
        //    少一张地图就是"点进去打不开"，那是最该被发现的错误。
        String mapRel = meta.map();
        if (dir == null) {
            String resource = Workspace.CAMPAIGNS_RESOURCE_DIR + "/" + meta.id() + "/" + mapRel;
            if (CampaignLoader.class.getClassLoader().getResource(resource) == null) {
                issues.add("地图文件不存在: " + mapRel);
            }
        } else {
            if (!Files.isRegularFile(dir.resolve(mapRel))) {
                issues.add("地图文件不存在: " + mapRel);
            }
        }

        // ② 没有波次 = 进去之后一个敌人都不会出。不是错误（图可能刚画好），
        //    但值得提示 —— 玩家点进去会以为游戏坏了。
        if (!meta.hasWaves()) {
            issues.add("还没有配置任何波次（进去不会出怪）");
        }
        return issues;
    }
}
