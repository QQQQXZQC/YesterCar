package com.yestercar.common.io;

import com.yestercar.common.model.Campaign;
import com.yestercar.common.model.CampaignMeta;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 战役的**磁盘写操作**：新建、复制、另存为、删除。
 *
 * <h2>为什么单独一个类</h2>
 * {@link CampaignJsonIO} 只管"一个文件怎么读写"，{@link CampaignLoader} 只管"从哪读"。
 * 「整个战役目录怎么建、怎么整份复制」是第三件事，混进前两者会让它们各自多出无关的职责。
 *
 * <h2>它是「官方战役只读」的落地点</h2>
 * 官方战役在 jar 内、{@link Campaign#dir()} 是 {@code null}，
 * 所以"想写官方战役"在这里会**立刻明确失败**，而不是写到一个不存在的路径上。
 * 按具体设计 §3.5，官方战役的保存流程是：
 *
 * <pre>
 * 打开官方战役 → 点「保存」→ 提示不可修改，是否另存为？→ {@link #copyAs}
 * </pre>
 *
 * <p>本类<strong>不碰界面</strong>：只提供操作与明确的失败原因。
 */
public final class CampaignStore {

    private CampaignStore() {
    }

    /**
     * 新建一个空的战役目录。
     *
     * <p>建出 {@code campaign.json} 与空的 {@code maps/} ——
     * 子目录先建好，是为了让"往里放地图"这件事不需要调用方先记得建目录。
     *
     * <p>⚠️ 没有 {@code levels/}：**一个战役 = 一张地图**，
     * 波次与规则参数都在 {@code campaign.json} 里。见 {@link CampaignMeta} 的说明。
     *
     * @param id   战役标识（同时是目录名）
     * @param name 显示名
     * @return 新建的战役
     * @throws IOException 目录已存在、或 id 非法、或写盘失败
     */
    public static Campaign create(String id, String name) throws IOException {
        requireUsableId(id);
        Path dir = Workspace.campaignDir(id);
        if (Files.exists(dir)) {
            throw new FileAlreadyExistsException("战役「" + id + "」已经存在: " + dir);
        }
        Files.createDirectories(dir.resolve(CampaignMeta.MAP_DIR));
        CampaignMeta meta = CampaignMeta.create(id, name);
        CampaignJsonIO.save(meta, dir.resolve(CampaignJsonIO.FILE_NAME));
        return Campaign.writable(CampaignJsonIO.load(dir.resolve(CampaignJsonIO.FILE_NAME)), dir);
    }

    /**
     * 新建或**覆盖**一个自定义战役，并把地图一起写进去。
     *
     * <p>写到默认落点 {@code data/campaigns/<id>/}。
     * 想写到别处（玩家自己挑的目录）用
     * {@link #saveWithMapAt(Path, String, String, TileMap)}。
     *
     * @param id   战役标识（同时是目录名）；已存在时覆盖
     * @param name 显示名
     * @param map  地图数据
     * @return 写好的战役
     * @throws IOException id 非法或写盘失败
     */
    public static Campaign saveWithMap(String id, String name,
                                       com.yestercar.campaigneditor.model.TileMap map)
            throws IOException {
        return saveWithMapAt(Workspace.campaignDir(id), id, name, map);
    }

    /**
     * 新建或**覆盖**一个战役，写到**指定目录**。
     *
     * <h2>为什么要有这个重载</h2>
     * ⚠️ 默认落点（{@code data/campaigns/}）只是"游戏能读到的那个目录"，
     * <strong>不是限制</strong> —— 玩家要把战役存到别处、备份、发给同学，
     * 都是他的自由。所以写盘必须能接受一个显式目录。
     *
     * <p>（曾经这里只按 id 拼默认路径，于是编辑器里"选存放位置"那一步
     * 被无声地丢掉了 —— 用户选到桌面，文件还是写进了工程目录。）
     *
     * @param campaignDir 目标战役目录
     * @param id          战役标识（应当等于目录名）
     * @param name        显示名
     * @param map         地图数据
     * @return 写好的战役
     * @throws IOException 写盘失败
     */
    public static Campaign saveWithMapAt(Path campaignDir, String id, String name,
                                         com.yestercar.campaigneditor.model.TileMap map)
            throws IOException {
        return saveWithMapAt(campaignDir, id, name, map, null);
    }

    /**
     * 保存地图**并把波次表一起写进 {@code campaign.json}**。
     *
     * @param campaignDir 战役目录
     * @param id          战役 id
     * @param name        显示名
     * @param map         地图
     * @param waves       波次表；{@code null} 表示沿用文件里已有的
     * @return 保存后的战役
     * @throws IOException 写盘失败
     */
    public static Campaign saveWithMapAt(Path campaignDir, String id, String name,
                                         com.yestercar.campaigneditor.model.TileMap map,
                                         java.util.List<com.yestercar.common.model.Wave> waves)
            throws IOException {
        return saveWithMapAt(campaignDir, id, name, map, waves, null);
    }

    /**
     * 保存地图 + 波次 + 关卡规则参数。
     *
     * @param campaignDir 战役目录
     * @param id          战役 id
     * @param name        显示名
     * @param map         地图
     * @param waves       波次；{@code null} 沿用文件里的
     * @param params      规则参数；{@code null} 沿用文件里的
     * @return 保存后的战役
     * @throws IOException 写盘失败
     */
    public static Campaign saveWithMapAt(Path campaignDir, String id, String name,
                                         com.yestercar.campaigneditor.model.TileMap map,
                                         java.util.List<com.yestercar.common.model.Wave> waves,
                                         com.yestercar.common.model.LevelParams params)
            throws IOException {
        if (campaignDir == null) {
            throw new IOException("目标目录为 null");
        }
        if (map == null) {
            throw new IOException("地图为 null");
        }
        Files.createDirectories(campaignDir);

        // ---- 先定下 campaign.json 的内容，再照它的 map 字段写地图 ----
        //
        // ⚠️ 顺序很重要：地图路径由 **id** 决定（map/<id>.json），
        // 而 id 由**目录名**权威决定（CampaignJsonIO.alignTo）。
        // 所以必须先算出对齐后的元数据，才知道地图往哪儿写。
        // 反过来先写地图会写出 map/map.json，而 campaign.json 里声明的是
        // map/<id>.json —— 两者对不上，游戏就找不到地图（实测踩过）。
        Path json = campaignDir.resolve(CampaignJsonIO.FILE_NAME);
        CampaignMeta meta;
        if (Files.isRegularFile(json)) {
            try {
                meta = CampaignJsonIO.load(json);
                if (!name.isBlank() && !name.equals(meta.name())) {
                    meta = meta.withName(name);
                }
            } catch (IOException e) {
                System.err.println("[CampaignStore] 已有 campaign.json 读不出来，将重建: "
                        + e.getMessage());
                meta = CampaignMeta.create(id, name);
            }
        } else {
            meta = CampaignMeta.create(id, name);
        }

        // 对齐到目录名 —— 与 CampaignJsonIO.save 用**同一个方法**，
        // 这样"我算出的路径"与"文件里声明的路径"必然一致（只有一处推导）
        if (waves != null) {
            meta = meta.withWaves(waves);
        }
        if (params != null) {
            meta = meta.withParams(params);
        }

        Path dirName = campaignDir.getFileName();
        meta = CampaignJsonIO.alignTo(meta, dirName == null ? id : dirName.toString());

        Path mapFile = campaignDir.resolve(meta.map());
        Files.createDirectories(mapFile.getParent());

        // 地图内部记录的 name 也要对齐到 id，否则打开后标题栏显示的是别的名字
        com.yestercar.campaigneditor.io.MapJsonIO.save(map.copy(meta.id()), mapFile);

        CampaignJsonIO.save(meta, json);
        return Campaign.writable(CampaignJsonIO.load(json), campaignDir);
    }

    /**
     * 把一个战役整份复制成另一个 id。
     *
     * <h2>这就是「另存为自定义战役」</h2>
     * 官方战役的目录是 {@code null}（在 jar 内），**不能**用文件系统复制。
     * 所以来源改用资源通道：把 jar 里的每个文件读成字节写进新目录。
     * 一条代码路径同时支持两种来源，是因为"复制"这件事对调用方是同一件事。
     *
     * @param source 来源战役（官方或自定义都行）
     * @param newId  新战役 id（同时是新目录名）
     * @return 复制出来的可写战役
     * @throws IOException 新 id 已存在、或读写失败
     */
    public static Campaign copyAs(Campaign source, String newId) throws IOException {
        requireUsableId(newId);
        if (source == null) {
            throw new IOException("来源战役为 null");
        }
        Path target = Workspace.campaignDir(newId);
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException("战役「" + newId + "」已经存在: " + target);
        }
        Files.createDirectories(target);
        try {
            if (source.dir() != null) {
                copyFromDisk(source.dir(), target);
            } else {
                copyFromClasspath(source.id(), target);
            }
            // 复制完把 id 改成新名字（目录名变了，id 必须跟着变）
            Path json = target.resolve(CampaignJsonIO.FILE_NAME);
            CampaignMeta meta = CampaignJsonIO.load(json).withId(newId);
            CampaignJsonIO.save(meta, json);
            return Campaign.writable(meta, target);
        } catch (IOException | RuntimeException e) {
            // 复制到一半失败会留下半个战役，清理掉 —— 半个战役比没有更糟
            deleteRecursively(target);
            throw new IOException("复制战役失败，已清理半成品: " + e.getMessage(), e);
        }
    }

    /**
     * 删除一个自定义战役目录。
     *
     * <p>官方战役没有目录，会明确失败。
     *
     * @param campaign 要删除的战役
     * @throws IOException 只读战役、或删除失败
     */
    public static void delete(Campaign campaign) throws IOException {
        if (campaign == null || campaign.dir() == null) {
            throw new IOException("只读战役（官方战役）不能删除");
        }
        deleteRecursively(campaign.dir());
    }

    /**
     * 列出 {@code data/campaigns/} 下的全部目录名。
     *
     * @return 目录名列表（已排序）；目录不存在时为空表
     */
    public static List<String> listCustomIds() {
        Path root = Workspace.campaignsDir();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .forEach(out::add);
        } catch (IOException e) {
            System.err.println("[CampaignStore] 列举失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 检查一个战役 id 能不能当目录名用。
     *
     * <p>规则与地图文件名一致：不能为空、不能含路径分隔符、不能有 Windows 非法字符。
     *
     * @param id 待检查的 id
     * @return 合法返回 {@code null}，否则返回原因
     */
    public static String checkId(String id) {
        if (id == null || id.isBlank()) {
            return "战役名不能为空";
        }
        String trimmed = id.trim();
        if (!trimmed.equals(id)) {
            return "战役名首尾不能有空格";
        }
        if (trimmed.equals(".") || trimmed.equals("..")) {
            return "战役名不能是 . 或 ..";
        }
        // 复用地图文件名的清洗规则，保持"什么算非法字符"只有一处定义
        String cleaned = com.yestercar.campaigneditor.util.MapNaming.sanitizeFileName(trimmed);
        if (!cleaned.equals(trimmed)) {
            return "战役名含有不能用于文件名的字符（\\ / : * ? \" < > |）";
        }
        return null;
    }

    /** 校验 id，不合法就抛。 */
    private static void requireUsableId(String id) throws IOException {
        String problem = checkId(id);
        if (problem != null) {
            throw new IOException(problem + "：" + id);
        }
    }

    /** 从磁盘目录整份复制（跳过备份文件之类的噪声）。 */
    private static void copyFromDisk(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            List<Path> all = walk.toList();
            for (Path src : all) {
                Path rel = from.relativize(src);
                Path dst = to.resolve(rel.toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 从 jar 内资源整份复制。
     *
     * <p>用 {@link Workspace#listResourceChildren} 与 {@link Workspace#listResourceFiles}
     * 递归列举 —— 它们已经处理了 "file:" 与 "jar:" 两种形态的差别，这里不必重复。
     */
    private static void copyFromClasspath(String campaignId, Path to) throws IOException {
        String base = Workspace.CAMPAIGNS_RESOURCE_DIR + "/" + campaignId;
        copyResourceFile(base + "/" + CampaignJsonIO.FILE_NAME, to.resolve(CampaignJsonIO.FILE_NAME));
        for (String sub : List.of(CampaignMeta.MAP_DIR, "textures")) {
            String subResource = base + "/" + sub;
            List<String> files = Workspace.listResourceFiles(subResource);
            if (files.isEmpty()) {
                continue;
            }
            Files.createDirectories(to.resolve(sub));
            for (String name : files) {
                copyResourceFile(subResource + "/" + name, to.resolve(sub).resolve(name));
            }
        }
    }

    /** 把一个资源读成字节写进目标文件。 */
    private static void copyResourceFile(String resourcePath, Path dst) throws IOException {
        try (java.io.InputStream in =
                     CampaignStore.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("资源读不到: " + resourcePath);
            }
            Files.createDirectories(dst.getParent());
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 递归删除。尽力而为，失败不抛（用于清理半成品）。 */
    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    System.err.println("[CampaignStore] 删除失败 " + p + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.println("[CampaignStore] 清理失败 " + root + ": " + e.getMessage());
        }
    }
}
