package com.yestercar.common.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工程目录与两类数据的统一入口。
 *
 * <p>Processing 的 sketch 有内置 {@code dataPath()} 自动指向 {@code <sketch>/data}，
 * Maven 工程没有这个机制，且实测 {@code sketchPath() = <user.dir>}——即「启动时的工作目录」，
 * 在 IDE 里跑和双击 exe 跑并不一致。因此本类不依赖 {@code dataPath()}，
 * 而是显式区分两类数据：
 *
 * <table border="1">
 *   <caption>两类数据的存放位置</caption>
 *   <tr><th>类别</th><th>位置</th><th>读取方式</th></tr>
 *   <tr>
 *     <td>只读默认数据<br>（enemies.json / operators.json / 默认贴图）</td>
 *     <td>{@code src/main/resources/data/}，打包进 jar</td>
 *     <td>classpath，见 {@link #openResource(String)}</td>
 *   </tr>
 *   <tr>
 *     <td>用户产出数据<br>（map_xxx.json / level_xxx.json）</td>
 *     <td>{@code <工程根>/data/}</td>
 *     <td>文件系统，见 {@link #mapFile(String)}</td>
 *   </tr>
 * </table>
 *
 * @see com.yestercar.common.util.Director 朝向枚举
 */
public final class Workspace {

    /** 只读默认数据在 classpath 上的前缀。 */
    public static final String RESOURCE_DATA_PREFIX = "data/";

    /** 可写数据目录名，位于工程根下。 */
    public static final String DATA_DIR_NAME = "data";

    /**
     * 显式指定工程根的系统属性，优先级最高。
     *
     * <p>用法：{@code java -Dyestercar.home=D:\path\to\YesterCar -jar YesterCar.jar}
     */
    public static final String HOME_PROPERTY = "yestercar.home";

    private static final String MAPS_DIR_NAME = "maps";
    private static final String LEVELS_DIR_NAME = "levels";

    /** 用于识别工程根的标志文件。 */
    private static final String ROOT_MARKER = "pom.xml";

    private static Path cachedRoot;

    private Workspace() {
        // 工具类，不允许实例化
    }

    // ------------------------------------------------------------------
    // 工程根
    // ------------------------------------------------------------------

    /**
     * 返回工程根目录。
     *
     * <p>实测：{@code System.getProperty("user.dir")} 是「启动时的工作目录」，并不可靠——
     * IDEA 里是工程根，而双击 exe 时可能退化成 {@code C:\Users\<用户>\Documents}
     * （Windows 下从资源管理器启动 java 的默认工作目录）。因此按以下顺序解析：
     *
     * <ol>
     *   <li>系统属性 {@value #HOME_PROPERTY}（显式覆盖，优先级最高）；</li>
     *   <li>从本类的 class 文件位置向上查找含 {@code pom.xml} 的目录
     *       （IDEA 运行时为 {@code target/classes}，即向上两级）；</li>
     *   <li>{@code user.dir}/data 已存在时采用 {@code user.dir}；</li>
     *   <li>兜底回退到 {@code user.dir}。</li>
     * </ol>
     *
     * @return 工程根目录的绝对路径
     */
    public static synchronized Path projectRoot() {
        if (cachedRoot == null) {
            cachedRoot = resolveProjectRoot();
        }
        return cachedRoot;
    }

    /** 清空工程根缓存，仅供测试使用。 */
    static synchronized void resetProjectRootCache() {
        cachedRoot = null;
    }

    private static Path resolveProjectRoot() {
        String override = System.getProperty(HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }

        Path fromCodeSource = locateRootFromCodeSource();
        if (fromCodeSource != null) {
            return fromCodeSource;
        }

        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(userDir.resolve(DATA_DIR_NAME))) {
            return userDir;
        }
        return userDir;
    }

    /**
     * 从本类所在位置向上查找工程根。
     *
     * <p>依次检查 {@code target/classes} 所在层级与 jar 所在层级；找到含 {@code pom.xml}
     * 的最近祖先即认为是工程根。打包后若 jar 被复制到别处，此路径不成立，
     * 此时应通过 {@value #HOME_PROPERTY} 指定。
     *
     * @return 工程根；无法定位时返回 {@code null}
     */
    private static Path locateRootFromCodeSource() {
        try {
            Path location = Paths.get(
                    Workspace.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();

            // jar 形式时 location 是 jar 文件本身，取其父目录
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            if (dir == null) {
                return null;
            }
            for (Path p = dir; p != null; p = p.getParent()) {
                if (Files.isRegularFile(p.resolve(ROOT_MARKER))) {
                    return p;
                }
            }
        } catch (Exception e) {
            // 定位失败不是致命错误，交由调用方回退
        }
        return null;
    }

    /**
     * 返回可写数据根目录 {@code <工程根>/data}。
     *
     * @return 可写数据根目录
     */
    public static Path dataDir() {
        return projectRoot().resolve(DATA_DIR_NAME);
    }

    /**
     * 返回地图目录 {@code <工程根>/data/maps}。
     *
     * @return 地图目录
     */
    public static Path mapsDir() {
        return dataDir().resolve(MAPS_DIR_NAME);
    }

    /**
     * 返回关卡目录 {@code <工程根>/data/levels}。
     *
     * @return 关卡目录
     */
    public static Path levelsDir() {
        return dataDir().resolve(LEVELS_DIR_NAME);
    }

    /**
     * 首次运行时创建可写数据目录（幂等）。
     *
     * @throws IOException 目录创建失败（通常是权限问题）
     */
    public static void ensureWritableDirs() throws IOException {
        Files.createDirectories(mapsDir());
        Files.createDirectories(levelsDir());
    }

    // ------------------------------------------------------------------
    // 可写数据：地图 / 关卡
    // ------------------------------------------------------------------

    /**
     * 解析地图文件 {@code <工程根>/data/maps/<name>.json}。
     *
     * <p>若传入的 {@code name} 已带 {@code .json} 后缀则不再追加。
     *
     * @param name 地图名，如 {@code map_001}
     * @return 地图文件路径（不保证文件存在）
     */
    public static Path mapFile(String name) {
        return mapsDir().resolve(withJsonSuffix(name));
    }

    /**
     * 解析关卡文件 {@code <工程根>/data/levels/<name>.json}。
     *
     * @param name 关卡名，如 {@code level_001}
     * @return 关卡文件路径（不保证文件存在）
     */
    public static Path levelFile(String name) {
        return levelsDir().resolve(withJsonSuffix(name));
    }

    private static String withJsonSuffix(String name) {
        return name.endsWith(".json") ? name : name + ".json";
    }

    // ------------------------------------------------------------------
    // 只读资源
    // ------------------------------------------------------------------

    /**
     * 以 classpath 方式打开只读资源。
     *
     * <p>示例：{@code openResource("enemies.json")} 会读取
     * {@code src/main/resources/data/enemies.json}（或 jar 内同路径）。
     *
     * @param relativeName 相对 {@code resources/data/} 的文件名
     * @return 输入流；调用方负责关闭
     * @throws IOException 资源不存在或无法读取
     */
    public static InputStream openResource(String relativeName) throws IOException {
        String path = RESOURCE_DATA_PREFIX + relativeName;
        InputStream in = Workspace.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IOException("找不到 classpath 资源: " + path);
        }
        return in;
    }

    /**
     * 判断只读资源是否存在，用于「贴图存在则用贴图，否则用色块」的美术规则。
     *
     * @param relativeName 相对 {@code resources/data/} 的文件名
     * @return 存在返回 {@code true}
     */
    public static boolean resourceExists(String relativeName) {
        String path = RESOURCE_DATA_PREFIX + relativeName;
        return Workspace.class.getClassLoader().getResource(path) != null;
    }

    /**
     * 把只读资源整体读成字符串（UTF-8）。
     *
     * @param relativeName 相对 {@code resources/data/} 的文件名
     * @return 文件内容
     * @throws IOException 资源不存在或读取失败
     */
    public static String readResourceAsString(String relativeName) throws IOException {
        try (InputStream in = openResource(relativeName)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    /**
     * 返回一行环境摘要，供启动日志与自检打印。
     *
     * @return 形如 {@code root=... | data=... | maps=...} 的摘要
     */
    public static String describe() {
        return "工程根=" + projectRoot()
                + System.lineSeparator() + "  可写数据=" + dataDir()
                + System.lineSeparator() + "  地图目录=" + mapsDir()
                + System.lineSeparator() + "  关卡目录=" + levelsDir();
    }
}
