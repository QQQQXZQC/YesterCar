package com.yestercar.common.util;

import java.util.List;

/**
 * 贴图路径常量：**美术侧与代码侧的唯一契约**。
 *
 * <p>所有路径均为相对 {@code src/main/resources/data/} 的相对路径，
 * 直接传给 {@link com.yestercar.common.io.Workspace#resourceExists(String)} /
 * {@link com.yestercar.common.io.Workspace#openResource(String)}。
 *
 * <p>命名与尺寸规范见 {@code docs/交接文档-动画与数值.md}——改这里之前先改那份文档，
 * 两边必须一致。
 *
 * <h2>美术规则（策划方案 §2.12）</h2>
 * <ul>
 *   <li>检测贴图文件是否存在；</li>
 *   <li>存在 → 使用贴图；</li>
 *   <li>不存在 → 使用默认色块。</li>
 * </ul>
 * 也就是说：<strong>贴图缺了游戏也能跑</strong>，美术可以随时增量交付，不必等齐。
 *
 * <h2>为什么全部用小写常量而不是 enum</h2>
 * 地形枚举（-1 / 0 / 1 / 2 / 3 / 4）属于数据契约，不在这里重复定义；
 * 本类只负责「贴图文件叫什么名字」，保持单一职责。
 */
public final class AssetPaths {

    /** 属性图标目录（英文文件名）。 */
    public static final String ATTRIBUTE_DIR = "data/imgs/attributes/";

    private static final java.util.Map<String, String> ATTR_ICONS = java.util.Map.of(
            "生命值", "hp",
            "攻击力", "atk",
            "法术攻击力", "atk_arts",
            "防御力", "def",
            "法抗", "res",
            "阻挡数", "block",
            "部署费用", "cost",
            "再部署时间", "redeploy");

    /**
     * 属性名 → 图标文件路径。
     *
     * @param label 中文属性名
     * @return 相对工程根路径；未收录时返回 {@code null}
     */
    public static String attributeIcon(String label) {
        String key = ATTR_ICONS.get(label);
        return key == null ? null : ATTRIBUTE_DIR + key + ".png";
    }


    /** 贴图根目录（相对 {@code resources/data/}）。 */
    public static final String TEXTURE_ROOT = "textures/";

    /** 敌人贴图目录。 */
    public static final String ENEMY_DIR = TEXTURE_ROOT + "enemies/";

    /** 干员贴图目录。 */
    public static final String OPERATOR_DIR = TEXTURE_ROOT + "operators/";

    // ------------------------------------------------------------------
    // 地形贴图（放在可写目录 data/imgs/ 下，不在 classpath 上）
    // ------------------------------------------------------------------

    /**
     * 地形贴图目录，相对 {@code data/imgs/}。
     *
     * <p>实际文件：{@code GROUND.png / HIGH.png / IMPASSABLE.png / RED_DOOR.png / BLUE_DOOR.png}，
     * <strong>尺寸必须是 256 × 256</strong>（= 格子边长 64 的 4 倍，覆盖地图区最大 4× 缩放）。
     *
     * <p>命名与 {@link com.yestercar.campaigneditor.model.TileType} 的枚举名一致 ——
     * 这样「加一种地形」只需要在枚举里加一项、再放一张同名图。
     */
    public static final String TERRAIN_DIR = "Editor/Maps/";

    /**
     * 取某种地形的贴图文件名。
     *
     * <p>用 {@link com.yestercar.campaigneditor.model.TileType} 的**枚举名**做文件名，
     * 如 {@code GROUND} → {@code GROUND.png} ——
     * 这样「加一种地形」只需要在枚举里加一项、再放一张同名图，
     * 不必再到本类里补一个常量。
     *
     * @param tileTypeName 地形枚举名，如 {@code "GROUND"}
     * @return 文件名（不含目录），如 {@code "GROUND.png"}
     */
    public static String terrainFile(String tileTypeName) {
        return tileTypeName + ".png";
    }

    // ------------------------------------------------------------------
    // 界面图（放在可写目录 data/imgs/ 下，不在 classpath 上）
    // ------------------------------------------------------------------

    /** 战役编辑器启动 / 加载背景，相对 {@code data/imgs/}。 */
    public static final String EDITOR_LOADING_BACKGROUND = "Editor/Start/EditorBackground.png";

    /**
     * 启动自检要确认存在的界面图。
     *
     * <p>集中在这里而<strong>不是</strong>放在各窗口类里，是为了让
     * {@link com.yestercar.common.AppBoot} 能在不认识任何窗口类的前提下做检查 ——
     * 这是「import 列表就是职责边界」那条约定的具体落实。
     */
    public static final List<String> REQUIRED_UI_IMAGES = List.of(
            EDITOR_LOADING_BACKGROUND);

    // ------------------------------------------------------------------
    // 动画素材（放在可写目录 data/anim/ 下，不在 classpath 上）
    // ------------------------------------------------------------------

    /** 动画素材根目录名，位于 {@code data/} 下。 */
    public static final String ANIM_DIR_NAME = "anim";

    /**
     * 加载界面小人的 GIF 动画，相对 {@code data/anim/}。
     *
     * <p><strong>换素材只需要改这一行</strong>：把新 GIF 放进 {@code data/anim/} 下，
     * 把这里改成它的相对路径即可。帧数、尺寸、帧延时都由
     * {@link com.yestercar.common.util.GifAnimation} 从文件里读，不用改代码。
     */
    public static final String LOADING_CHARACTER_GIF = "w/w.gif";

    /**
     * Spine <strong>源素材</strong>子目录名，相对 {@code data/anim/}。
     *
     * <p>里面的 {@code .skel} / {@code .atlas} / {@code .png} 是导出 GIF 的**源头**，
     * 供 Spine 编辑器与预览工具使用，<strong>程序运行时并不读它们</strong>。
     * 之所以一并放在 {@code data/anim/} 下而不是单独一个目录，
     * 是为了让「某一角色的全部动画素材」集中在一处，便于替换与版本管理。
     */
    public static final String SPINE_SOURCE_SUBDIR = "spine";

    /**
     * 彩蛋图片，相对 {@code data/anim/}。
     *
     * <p>加载过程中按下 {@code W} 触发：黑屏转场时它会叠在黑幕<strong>之上</strong>，
     * 随黑幕一起淡入淡出。见 {@code EditorApp}。
     *
     * <p><strong>注意扩展名是 {@code .png} 而不是原始素材的 {@code .webp}</strong>：
     * Java 17 的 {@code ImageIO} 与 Processing 的 {@code loadImage()} 都<strong>不支持 WebP</strong>，
     * 直接用 {@code .webp} 只会得到 {@code null}。若要换新素材，
     * 请先转成 PNG（Windows 上可用 WPF 解码器：
     * {@code BitmapDecoder.Create} + {@code PngBitmapEncoder}）。
     */
    public static final String EASTER_EGG_IMAGE = "w/mystic.png";

    /** 默认格子边长（像素），与地图契约的 {@code tileSize} 默认值一致。 */
    public static final int TILE_SIZE = 64;

    private AssetPaths() {
        // 常量类，不允许实例化
    }

    /**
     * 敌人贴图路径。
     *
     * @param enemyId 敌人 ID，取自 {@code enemies.json} 的 {@code id}，如 {@code soldier}
     * @return 相对 {@code resources/data/} 的路径
     */
    public static String enemy(String enemyId) {
        return ENEMY_DIR + enemyId + ".png";
    }

    /**
     * 干员贴图路径。
     *
     * @param operatorId 干员 ID，取自 {@code operators.json} 的 {@code id}，如 {@code guard_01}
     * @return 相对 {@code resources/data/} 的路径
     */
    public static String operator(String operatorId) {
        return OPERATOR_DIR + operatorId + ".png";
    }
}
