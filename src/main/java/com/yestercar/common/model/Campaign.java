package com.yestercar.common.model;

import com.yestercar.common.io.Workspace;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个战役：元数据 + 它在磁盘上的位置 + <strong>只读标记</strong>。
 *
 * <h2>只读标记来自"加载来源"，不写进 JSON</h2>
 * 官方战役放在 jar 内（{@code src/main/resources/campaigns/}），
 * **物理上就写不回去** —— 这是只读的技术根源，不是一条人为约定。
 * 所以 {@link #readOnly()} 由 {@code CampaignLoader} 按来源判定，
 * {@code campaign.json} 里没有也不需要这个字段。
 *
 * <h2>两个来源</h2>
 * <table border="1">
 *   <caption>官方 vs 自定义</caption>
 *   <tr><th></th><th>官方</th><th>自定义</th></tr>
 *   <tr><td>位置</td><td>jar 内资源</td><td>{@code data/campaigns/&lt;id&gt;/}</td></tr>
 *   <tr><td>{@link #dir()}</td><td>{@code null}</td><td>真实目录</td></tr>
 *   <tr><td>{@link #readOnly()}</td><td>{@code true}</td><td>{@code false}</td></tr>
 *   <tr><td>能否写盘</td><td>不能</td><td>可以</td></tr>
 * </table>
 *
 * <p>官方战役的 {@code dir()} 是 {@code null}，所以"想写官方战役"会立刻失败并
 * 明确报错 —— 这比"写进一个不存在的路径"要早得多，也清楚得多。
 */
public final class Campaign {

    private final CampaignMeta meta;

    /** 战役在磁盘上的目录；官方战役（jar 内）为 {@code null}。 */
    private final Path dir;

    /** 是否只读。来源为 jar 时恒为 {@code true}。 */
    private final boolean readOnly;

    /** 加载时发现的问题（数据不一致、文件缺失等）。不阻断加载，只作提示。 */
    private final List<String> issues;

    /**
     * @param meta     元数据
     * @param dir      战役目录；官方战役传 {@code null}
     * @param readOnly 是否只读
     * @param issues   加载时发现的问题
     */
    public Campaign(CampaignMeta meta, Path dir, boolean readOnly, List<String> issues) {
        this.meta = meta;
        this.dir = dir;
        this.readOnly = readOnly;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /**
     * 造一个可写的自定义战役。
     *
     * @param meta 元数据
     * @param dir  战役目录
     * @return 战役
     */
    public static Campaign writable(CampaignMeta meta, Path dir) {
        return new Campaign(meta, dir, false, List.of());
    }

    /**
     * 造一个只读的官方战役。
     *
     * @param meta 元数据
     * @return 战役
     */
    public static Campaign readOnlyOfficial(CampaignMeta meta) {
        return new Campaign(meta, null, true, List.of());
    }

    /** @return 元数据 */
    public CampaignMeta meta() {
        return meta;
    }

    /** @return 战役 id（等于目录名） */
    public String id() {
        return meta.id();
    }

    /** @return 显示名 */
    public String name() {
        return meta.name();
    }

    /** @return 战役目录；官方战役为 {@code null} */
    public Path dir() {
        return dir;
    }

    /** @return 是否只读 */
    public boolean readOnly() {
        return readOnly;
    }

    /** @return 加载时发现的问题（不可变） */
    public List<String> issues() {
        return issues;
    }

    /** @return 是否发现了任何数据问题 */
    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    // ------------------------------------------------------------------
    // 常用子路径
    // ------------------------------------------------------------------

    /**
     * 取战役内某个子路径。
     *
     * <p>⚠️ 只对自定义战役有意义 —— 官方战役没有 {@link #dir()}。
     * 想读官方战役的文件要走 {@code CampaignLoader} 的资源通道。
     *
     * @param parts 相对路径的各段
     * @return 拼接后的路径；官方战役返回 {@code null}
     */
    public Path resolve(String... parts) {
        if (dir == null) {
            return null;
        }
        Path p = dir;
        for (String s : parts) {
            p = p.resolve(s);
        }
        return p;
    }

    /** @return {@code map} 目录（单数 —— 一个战役只有一张地图）；官方战役为 {@code null} */
    public Path mapsDir() {
        return resolve(CampaignMeta.MAP_DIR);
    }

    /**
     * 地图文件在磁盘上的完整路径。
     *
     * <p>⚠️ 官方战役返回 {@code null} —— 它的地图在 jar 内，要走资源通道读。
     * 调用方应当先看 {@link #isOfficial()}#，
     * 或者用 {@link #mapRelativePath()} 去拼资源名。
     *
     * @return 地图文件路径；官方战役或路径非法时为 {@code null}
     */
    public Path mapFile() {
        if (dir == null) {
            return null;
        }
        return dir.resolve(meta.map());
    }

    /**
     * 地图在战役内的相对路径，如 {@code map/演示地图.json}。
     *
     * <p>这个字符串对两种来源都有效：自定义战役用它拼磁盘路径，
     * 官方战役用它拼 classpath 资源名（{@code campaigns/<id>/<相对路径>}）。
     *
     * @return 相对路径
     */
    public String mapRelativePath() {
        return meta.map();
    }

    /**
     * 取地图在 classpath 上的资源路径。
     *
     * <p>复用 {@link Workspace#CAMPAIGNS_RESOURCE_DIR}，不另写一份字面量 ——
     * 那个常量是"官方战役在 jar 里的根"，两处各写一遍早晚会不一致。
     *
     * @return 如 {@code campaigns/official_01/map/official_01.json}；
     *         自定义战役也返回同样的形式（但通常读不到，应当走 {@link #mapFile()}）
     */
    public String mapResourcePath() {
        return Workspace.CAMPAIGNS_RESOURCE_DIR + "/" + id() + "/" + meta.map();
    }

    /**
     * @return 是否是官方战役（只读、在 jar 内）
     */
    public boolean isOfficial() {
        return readOnly;
    }

    /**
     * @return 供日志与状态栏用的一行摘要
     */
    public String summary() {
        return meta.summary() + (readOnly ? "  [只读]" : "")
                + (hasIssues() ? "  ⚠" + issues.size() + " 个问题" : "");
    }

    @Override
    public String toString() {
        return "Campaign[" + summary() + "]";
    }
}
