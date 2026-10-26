package com.yestercar.runtime.view;

import com.yestercar.common.io.Workspace;
import com.yestercar.common.model.Operator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 骨骼素材的**契约测试**：不解析 .skel，只保证"目录里该有什么、命名怎么对"。
 *
 * <p>为什么单独一个测试类：解析正确性由 {@code runtime.spine} 的测试管，
 * 这里管的是**交付契约**——美术/同事换素材时最容易破坏的就是这条：
 * 少放一张图、贴图名和 atlas 里的 page 对不上、把 .skel 导出成别的版本。
 *
 * <p>素材缺失时<b>跳过</b>而不是失败：策划 §1.3 的规矩是"素材缺了游戏也得能跑"，
 * 所以测试不能因为有人临时清空 {@code data/spine/} 就红。
 */
class SpineAssetsTest {

    /**
     * @return 骨骼素材根目录（{@code data/spine/operator/}）
     */
    private static Path root() {
        return Workspace.projectRoot().resolve(SpineUnitLayer.SPINE_DIR);
    }

    /** 素材不在就跳过整个用例（而不是失败）。 */
    private static void requireAssets() {
        assumeTrue(Files.isDirectory(root()), "没有 data/spine/operator/，跳过素材契约测试");
    }

    /**
     * 每个干员都必须有三个文件：{@code .skel} / {@code .atlas} / {@code .png}。
     *
     * @throws IOException 读目录失败
     */
    @Test
    void everyOperatorHasThreeFiles() throws IOException {
        requireAssets();
        Map<String, Operator> ops = Operator.loadAll();
        List<String> missing = new ArrayList<>();
        for (Operator op : ops.values()) {
            Path dir = root().resolve(op.id());
            if (!Files.isDirectory(dir)) {
                missing.add(op.id() + "(无目录)");
                continue;
            }
            long skel = count(dir, ".skel");
            long atlas = count(dir, ".atlas");
            long png = count(dir, ".png");
            if (skel != 1 || atlas != 1 || png != 1) {
                missing.add(op.id() + "(skel=" + skel + " atlas=" + atlas + " png=" + png + ")");
            }
        }
        assertTrue(missing.isEmpty(), "素材不完整的干员: " + missing);
    }

    /**
     * atlas 里声明的贴图名必须和目录里的 png 一致。
     *
     * <p>错位的后果很隐蔽：贴图能加载、模型也能画，但整张脸是别人的
     * （同一套导出里改过文件名就会这样）。
     *
     * @throws IOException 读文件失败
     */
    @Test
    void atlasPageMustExist() throws IOException {
        requireAssets();
        List<String> bad = new ArrayList<>();
        for (Path dir : operatorDirs()) {
            Path atlas = single(dir, ".atlas");
            if (atlas == null) {
                bad.add(dir.getFileName() + "(没有 atlas)");
                continue;
            }
            String page = atlasPage(atlas);
            if (page == null) {
                bad.add(dir.getFileName() + "(atlas 里没有 page 行)");
            } else if (!Files.isRegularFile(dir.resolve(page))) {
                bad.add(dir.getFileName() + "(缺 " + page + ")");
            }
        }
        assertTrue(bad.isEmpty(), "贴图名对不上的素材: " + bad);
    }

    /**
     * .skel 的版本必须是我们支持的两个之一，并且**两个版本都得有素材**。
     *
     * <p>这条是为了防止将来"顺手把 3.8 的素材全换成 3.5"——
     * 那样双版本解析代码就再也没有样例可以验证了。
     *
     * @throws IOException 读文件失败
     */
    @Test
    void skelVersionsSupported() throws IOException {
        requireAssets();
        Set<String> versions = new LinkedHashSet<>();
        List<String> bad = new ArrayList<>();
        for (Path dir : operatorDirs()) {
            Path skel = single(dir, ".skel");
            if (skel == null) {
                continue;
            }
            String v = skelVersion(skel);
            versions.add(v);
            if (!"3.5.51".equals(v) && !"3.8.99".equals(v)) {
                bad.add(dir.getFileName() + "=" + v);
            }
        }
        assertTrue(bad.isEmpty(), "出现了没验证过的 Spine 版本: " + bad);
        assertTrue(versions.contains("3.5.51") && versions.contains("3.8.99"),
                "两个版本都要有素材才算真的验证过双版本，实际: " + versions);
    }

    /**
     * atlas 里至少有旋转区域。
     *
     * <p>旋转区域的 UV 映射和普通区域不同（错了就整块贴图错位），
     * 素材里只要还有 rotate 区域，这条契约就不能去掉。
     *
     * @throws IOException 读文件失败
     */
    @Test
    void atlasHasRotatedRegions() throws IOException {
        requireAssets();
        int rotated = 0;
        for (Path dir : operatorDirs()) {
            Path atlas = single(dir, ".atlas");
            if (atlas == null) {
                continue;
            }
            for (String line : Files.readAllLines(atlas, StandardCharsets.UTF_8)) {
                if ("rotate: true".equals(line.trim())) {
                    rotated++;
                }
            }
        }
        assertTrue(rotated > 0, "所有 atlas 都没有 rotate: true，旋转区域的解析就没被验证到了");
    }

    /**
     * 目录里只允许三件套（防止把导出的临时文件一起拷进来）。
     *
     * @throws IOException 列目录失败
     */
    @Test
    void noExtraFiles() throws IOException {
        requireAssets();
        List<String> extra = new ArrayList<>();
        for (Path dir : operatorDirs()) {
            String prefix = dir.getFileName().toString();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.forEach(p -> {
                    String n = p.getFileName().toString();
                    if (!n.endsWith(".skel") && !n.endsWith(".atlas") && !n.endsWith(".png")) {
                        extra.add(prefix + "/" + n);
                    }
                });
            }
        }
        assertEquals(List.of(), extra, "素材目录里有多余文件");
    }

    /**
     * @return 素材目录下的干员目录（按名字排序）
     * @throws IOException 列目录失败
     */
    private static List<Path> operatorDirs() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root())) {
            stream.filter(Files::isDirectory).sorted().forEach(out::add);
        }
        return out;
    }

    /**
     * @param dir 目录
     * @param ext 扩展名（含点）
     * @return 该扩展名的文件数
     * @throws IOException 列目录失败
     */
    private static long count(Path dir, String ext) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(ext)).count();
        }
    }

    /**
     * @param dir 目录
     * @param ext 扩展名（含点）
     * @return 唯一的那个文件；没有或多个返回 {@code null}
     * @throws IOException 列目录失败
     */
    private static Path single(Path dir, String ext) throws IOException {
        List<Path> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(ext)).forEach(hits::add);
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /**
     * 取 atlas 的贴图页码行（Spine atlas 的第一行非空文本就是 png 文件名）。
     *
     * @param atlas atlas 文件
     * @return 贴图文件名；找不到返回 {@code null}
     * @throws IOException 读文件失败
     */
    private static String atlasPage(Path atlas) throws IOException {
        for (String line : Files.readAllLines(atlas, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && t.toLowerCase(Locale.ROOT).endsWith(".png")) {
                return t;
            }
        }
        return null;
    }

    /**
     * 读 .skel 文件头里声明的 Spine 版本。
     *
     * <p>二进制布局（实测两个版本一致）：<b>变长长度前缀的 hash</b> → <b>变长长度前缀的 version</b>
     * → width/height（3.5）或 x/y/width/height（3.8）。
     * 长度前缀的语义是 {@code 0 = null、1 = 空串、n = 长度 n-1}。
     *
     * <p>实测字节（阿米娅）：{@code 1c "Tt6prsy…gNgA"(27) 07 "3.5.51"(6)}。
     * 这里不写成"跳过固定 N 字节"就是因为被这个坑过一次 ——
     * 读错起点不会报错，只会拿到一段乱码，然后<strong>整条版本断言静默失效</strong>。
     *
     * @param skel .skel 文件
     * @return 版本字符串，如 {@code "3.5.51"}
     * @throws IOException 读文件失败
     */
    private static String skelVersion(Path skel) throws IOException {
        byte[] b = Files.readAllBytes(skel);
        int[] pos = {0};
        skipPackedString(b, pos);                 // hash
        int len = readVarInt(b, pos);
        int charCount = Math.max(0, len - 1);
        int start = pos[0];
        int end = Math.min(b.length, start + charCount);
        return new String(b, start, Math.max(0, end - start), StandardCharsets.US_ASCII);
    }

    /**
     * 跳过一个变长长度前缀的字符串（3.5 按字符数、3.8 按字节数，版本串都是 ASCII，等价）。
     *
     * @param b   文件字节
     * @param pos 游标（会被推进）
     */
    private static void skipPackedString(byte[] b, int[] pos) {
        int len = readVarInt(b, pos);
        int count = Math.max(0, len - 1);
        pos[0] = Math.min(b.length, pos[0] + count);
    }

    /**
     * 读一个非负变长整数（7 位一组、低位在前）。
     *
     * @param b   文件字节
     * @param pos 游标（会被推进）
     * @return 数值
     */
    private static int readVarInt(byte[] b, int[] pos) {
        int value = 0;
        int shift = 0;
        while (pos[0] < b.length) {
            int v = b[pos[0]++] & 0xFF;
            value |= (v & 0x7F) << shift;
            if ((v & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 28) {
                break;
            }
        }
        return value;
    }
}
