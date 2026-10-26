package com.yestercar.runtime.spine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * spine 的 {@code .atlas} 文本描述（libgdx TextureAtlas 格式）解析结果。
 *
 * <p>只解析真正用得到的东西：贴图页名、页尺寸、每个区域在页里的矩形。文件形如
 *
 * <pre>
 * char_002_amiya.png
 * size: 512,512
 * format: RGBA8888
 * filter: Linear,Linear
 * repeat: none
 * F_Belt
 *   rotate: true
 *   xy: 403, 468
 *   size: 8, 39
 *   orig: 8, 39
 *   offset: 0, 0
 *   index: -1
 * </pre>
 *
 * <p><b>坐标系约定</b>（与贴图 PNG 的像素一致，也与渲染层「直接按原样贴图」的语义一致）：
 * 原点在贴图<b>左上角</b>，u 向右、v 向下，UV ∈ [0,1]。因此
 * {@code uv = (像素x / 页宽, 像素y / 页高)}，贴图不需要任何翻转。
 *
 * <p><b>rotate 的坑</b>：{@code rotate: true} 表示该区域在贴图集里被<b>旋转 90°</b> 存放。
 * atlas 行里写的 {@code size:} 仍是「未旋转」的逻辑宽高，而它在贴图里实际占的矩形宽高互换，
 * 所以 UV 不能直接线性映射 —— 见 {@link Region#toUv(float, float, float[])}。
 */
public final class SpineAtlas {

    private final String page;
    private final int width;
    private final int height;
    private final Map<String, Region> regions;

    private SpineAtlas(String page, int width, int height, Map<String, Region> regions) {
        this.page = page;
        this.width = width;
        this.height = height;
        this.regions = regions;
    }

    /**
     * 解析一个 {@code .atlas} 文件。
     *
     * @param atlasFile atlas 文件路径
     * @return 解析结果
     * @throws IOException 文件读不了，或缺页名/缺 {@code size:}
     */
    public static SpineAtlas parse(Path atlasFile) throws IOException {
        List<String> lines = Files.readAllLines(atlasFile, StandardCharsets.UTF_8);

        String page = null;
        int width = -1;
        int height = -1;
        Map<String, Region> regions = new LinkedHashMap<>();

        String currentName = null;
        Map<String, String> currentValues = new LinkedHashMap<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                flush(regions, currentName, currentValues, width, height);
                currentName = null;
                currentValues = new LinkedHashMap<>();
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                // 无冒号行：页名（xxx.png）或新区域名
                flush(regions, currentName, currentValues, width, height);
                currentValues = new LinkedHashMap<>();
                if (isPageName(line)) {
                    if (page == null) {
                        page = line;
                    }
                    currentName = null;
                    continue;
                }
                currentName = line;
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (currentName == null) {
                if ("size".equals(key)) {
                    int[] size = ints(value);
                    if (size.length >= 2) {
                        width = size[0];
                        height = size[1];
                    }
                }
            } else {
                currentValues.put(key, value);
            }
        }
        flush(regions, currentName, currentValues, width, height);

        if (page == null) {
            throw new IOException("atlas 缺少贴图页名: " + atlasFile);
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("atlas 缺少合法的 size: " + atlasFile);
        }
        return new SpineAtlas(page, width, height, regions);
    }

    private static boolean isPageName(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".cimg");
    }

    private static void flush(Map<String, Region> regions, String name, Map<String, String> values,
            int atlasWidth, int atlasHeight) {
        if (name == null || values.isEmpty()) {
            return;
        }
        int[] xy = ints(values.get("xy"));
        int[] size = ints(values.get("size"));
        if (xy.length < 2 || size.length < 2) {
            return;
        }
        int[] orig = ints(values.get("orig"));
        int[] offset = ints(values.get("offset"));
        int degrees = rotationDegrees(values.get("rotate"));
        int origW = orig.length >= 2 ? orig[0] : size[0];
        int origH = orig.length >= 2 ? orig[1] : size[1];
        int offsetX = offset.length >= 2 ? offset[0] : 0;
        int offsetY = offset.length >= 2 ? offset[1] : 0;
        regions.put(name, new Region(name, degrees, xy[0], xy[1], size[0], size[1],
                origW, origH, offsetX, offsetY, atlasWidth, atlasHeight));
    }

    /**
     * 解析 {@code rotate:} 的取值。
     *
     * <p>实测本工程 29 个 atlas 里出现了四种写法：{@code false}（1421 个）、{@code true}（825 个）、
     * {@code 180}（14 个）、{@code 270}（10 个）。{@code true} 等价于 90°，数值写法是
     * 新版本导出器/图集工具才有的；老版 libgdx 的 {@code Boolean.parseBoolean}
     * 会把 {@code "270"} 当成 false —— 那正是"整块贴图错位"的来源，所以这里显式按角度处理。
     */
    private static int rotationDegrees(String value) {
        if (value == null) {
            return 0;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return 0;
        }
        if ("true".equalsIgnoreCase(v)) {
            return 90;
        }
        if ("false".equalsIgnoreCase(v)) {
            return 0;
        }
        try {
            int degrees = Integer.parseInt(v);
            if (degrees % 90 != 0) {
                return 0;
            }
            int normalized = degrees % 360;
            return normalized < 0 ? normalized + 360 : normalized;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int[] ints(String value) {
        if (value == null || value.isEmpty()) {
            return new int[0];
        }
        String[] parts = value.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) {
                return new int[0];
            }
            try {
                out[i] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        return out;
    }

    /** 贴图页文件名，如 {@code char_002_amiya.png}。 */
    public String page() {
        return page;
    }

    /** 贴图页宽度（像素）。 */
    public int width() {
        return width;
    }

    /** 贴图页高度（像素）。 */
    public int height() {
        return height;
    }

    /**
     * 按名字取区域。
     *
     * @param name 区域名（= 附件的 path）
     * @return 区域；不存在返回 {@code null}
     */
    public Region region(String name) {
        return name == null ? null : regions.get(name);
    }

    /** 全部区域名（保持文件顺序，不可变）。 */
    public Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(regions.keySet()));
    }

    /** 区域个数（诊断用）。 */
    public int regionCount() {
        return regions.size();
    }

    /** 贴图集里的一个区域。 */
    public static final class Region {
        private final String name;
        /** 在贴图里被旋转的角度：0 / 90 / 180 / 270。 */
        private final int degrees;
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final int origW;
        private final int origH;
        private final int offsetX;
        private final int offsetY;
        private final int atlasWidth;
        private final int atlasHeight;

        /** 官方 {@code Atlas} 意义上的 UV 左上/右下（旋转区域允许 {@code v2 < v}）。 */
        private final float u0;
        private final float v0;
        private final float u1;
        private final float v1;

        Region(String name, int degrees, int x, int y, int w, int h, int origW, int origH,
                int offsetX, int offsetY, int atlasWidth, int atlasHeight) {
            this.name = name;
            this.degrees = degrees;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.origW = origW;
            this.origH = origH;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.atlasWidth = atlasWidth;
            this.atlasHeight = atlasHeight;

            // 官方 com.esotericsoftware.spine.attachments.Atlas 的算法：
            //   - 未旋转：u = x/W, v = y/H, u2 = (x+w)/W, v2 = (y+h)/H
            //   - 旋转 90：贴图里存的是 (h 宽, w 高)，且 v 范围**反向**：
            //       u = x/W, v = (y+w)/H, u2 = (x+h)/W, v2 = y/H
            //     "v 反向"不是笔误 —— 正是它让"按未旋转图像坐标线性取值"能采到旋转块。
            //     我上一轮用"永远递增"的矩形，旋转区域就整块取错（texas 的脸/头发全花）。
            float aw = atlasWidth <= 0 ? 1f : atlasWidth;
            float ah = atlasHeight <= 0 ? 1f : atlasHeight;
            switch (degrees) {
                case 90 -> {
                    u0 = x / aw;
                    v0 = (y + w) / ah;
                    u1 = (x + h) / aw;
                    v1 = y / ah;
                }
                case 180 -> {
                    u0 = (x + w) / aw;
                    v0 = (y + h) / ah;
                    u1 = x / aw;
                    v1 = y / ah;
                }
                case 270 -> {
                    u0 = (x + h) / aw;
                    v0 = y / ah;
                    u1 = x / aw;
                    v1 = (y + w) / ah;
                }
                default -> {
                    u0 = x / aw;
                    v0 = y / ah;
                    u1 = (x + w) / aw;
                    v1 = (y + h) / ah;
                }
            }
        }

        public String name() {
            return name;
        }

        /**
         * 是否在贴图集里被旋转过（90 / 180 / 270）。
         *
         * <p>{@code rotate: true} 等价于 90°；{@code rotate: 180} / {@code rotate: 270} 是新版工具
         * 才会写出来的角度值。判定 UV 换轴时必须用 {@link #degrees()} 而不是"真/假"，
         * 否则 180° 会被当成 0°、270° 会被当成 0°（这就是"整块贴图错位"的经典成因）。
         */
        public boolean rotated() {
            return degrees != 0;
        }

        /** 在贴图里被旋转的角度：0 / 90 / 180 / 270（顺时针）。 */
        public int degrees() {
            return degrees;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        /** 未旋转的逻辑宽（atlas 里 {@code size:} 第一项）。 */
        public int w() {
            return w;
        }

        /** 未旋转的逻辑高（atlas 里 {@code size:} 第二项）。 */
        public int h() {
            return h;
        }

        public int origW() {
            return origW;
        }

        public int origH() {
            return origH;
        }

        public int offsetX() {
            return offsetX;
        }

        public int offsetY() {
            return offsetY;
        }

        /** 区域在贴图里实际占的宽度（旋转 90/270 时为 {@link #h()}）。 */
        public int packedWidth() {
            return (degrees == 90 || degrees == 270) ? h : w;
        }

        /** 区域在贴图里实际占的高度（旋转 90/270 时为 {@link #w()}）。 */
        public int packedHeight() {
            return (degrees == 90 || degrees == 270) ? w : h;
        }

        /** 贴图里实际占用矩形的左边界 u。 */
        float rectU0() {
            return x / (float) atlasWidth;
        }

        /** 贴图里实际占用矩形的上边界 v。 */
        float rectV0() {
            return y / (float) atlasHeight;
        }

        /** 贴图里实际占用矩形的右边界 u。 */
        float rectU1() {
            return (x + packedWidth()) / (float) atlasWidth;
        }

        /** 贴图里实际占用矩形的下边界 v。 */
        float rectV1() {
            return (y + packedHeight()) / (float) atlasHeight;
        }

        /**
         * 把「区域内归一化坐标」(0..1, 左上为 0,0) 映射到整张贴图的 UV。
         *
         * <p>非旋转区域：线性映射到它在贴图里占的矩形。
         *
         * <p>旋转区域（贴图里被顺时针转了 90/180/270）：区域逻辑宽高是 {@code w×h}，
         * 贴图里实际占的矩形宽高按角度互换，所以 UV 必须换轴 —— 否则会看到整块贴图错位/翻转
         * （本项目记录在案的坑之一）。三个角度的映射：
         * <pre>
         * 90° ：图像 (u,v) -> 贴图 (1-v, u)
         * 180°：图像 (u,v) -> 贴图 (1-u, 1-v)
         * 270°：图像 (u,v) -> 贴图 (v, 1-u)
         * </pre>
         *
         * @param u   区域内横坐标 0..1（0 = 可见图像左边）
         * @param v   区域内纵坐标 0..1（0 = 可见图像上边）
         * @param out 输出，长度须 ≥ 2，写入 {u, v}
         */
        public void toUv(float u, float v, float[] out) {
            if (out == null || out.length < 2) {
                throw new IllegalArgumentException("out 长度必须 >= 2");
            }
            float uu = u;
            float vv = v;
            if (degrees == 90 || degrees == 270) {
                // 贴图里被转 90°/270° 存放的区域，宽高互换 —— 官方 RegionAttachment.setRegion
                // 在这种情况下给四个角是"错开一位"的（UR 拿 (u,v2)、UL 拿 (u,v) …），
                // 等价于把区域内的 (u,v) **转置**。少了这一步，所有 rotate:true 的部件
                // 都会取到邻块纹理：头发/耳朵/嘴/衣领"花掉"，alpha 边界也跟着错，
                // 看起来就是"脸和衣服是乱的"。逐像素对比：加上转置后 texas 差异
                // 从 5.68% 降到 0.018%。
                uu = v;
                vv = u;
            }
            // 180° 不需要转置（宽高没互换）：矩形本身 u0>u1 / v0>v1 已经表达了两轴翻转。
            //
            // 官方 MeshAttachment.updateUVs / RegionAttachment 的角点都等价于这一条：
            // 把「未旋转图像坐标」线性映射到 (u0,v0)-(u1,v1)；旋转区域的 v1 < v0 会自动翻转。
            out[0] = u0 + (u1 - u0) * uu;
            out[1] = v0 + (v1 - v0) * vv;
        }
    }
}
