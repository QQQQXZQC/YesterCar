package com.yestercar.common.io;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

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
 *     <td>文件系统，见 {@link #imageFile(String)}</td>
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

    private static final String IMGS_DIR_NAME = "imgs";
    private static final String ANIM_DIR_NAME = "anim";
    private static final String CAMPAIGNS_DIR_NAME = "campaigns";

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
     * 返回界面图片目录 {@code <工程根>/data/imgs}。
     *
     * <p>注意这里是<strong>可写数据</strong>而不是 classpath 资源：
     * 界面图（加载背景、菜单底图等）体积大、经常替换，放在 {@code data/} 下便于直接改文件看效果，
     * 不必重新构建。因此用文件系统读取，见 {@link #imageFile(String)}。
     *
     * @return 界面图片目录
     */
    public static Path imgsDir() {
        return dataDir().resolve(IMGS_DIR_NAME);
    }

    /**
     * 解析界面图片 {@code <工程根>/data/imgs/<relativePath>}。
     *
     * <p>示例：{@code imageFile("Editor/Start/加载背景.png")}。
     * 路径允许中文，调用方负责保证名字与磁盘上一致。
     *
     * @param relativePath 相对 {@code data/imgs/} 的路径，可带子目录
     * @return 图片文件路径（不保证文件存在，也不校验扩展名）
     */
    public static Path imageFile(String relativePath) {
        return imgsDir().resolve(relativePath);
    }

    /**
     * 返回动画素材目录 {@code <工程根>/data/anim}。
     *
     * <p>与界面图同理：动画素材体积大、可替换，放可写目录便于直接替换文件看效果。
     * 里面既有程序运行时读的 GIF，也有导出 GIF 用的 Spine 源素材
     * （见 {@link com.yestercar.common.util.AssetPaths#SPINE_SOURCE_SUBDIR}）。
     *
     * @return 动画素材目录
     */
    public static Path animDir() {
        return dataDir().resolve(ANIM_DIR_NAME);
    }

    /**
     * 解析 {@code data/anim/} 下的任意文件。
     *
     * <p>示例：{@code animAsset("w/w.gif")}。
     *
     * @param relativePath 相对 {@code data/anim/} 的路径（含扩展名）
     * @return 文件路径（不保证存在）
     */
    public static Path animAsset(String relativePath) {
        return animDir().resolve(relativePath);
    }

    /**
     * 返回战役根目录 {@code <工程根>/data/campaigns}。
     *
     * <p>玩家自建战役与编辑器产出的**调试战役**都放这里。
     * 官方战役不在这里 —— 它们在 classpath 上的 {@code campaigns/}（只读，见
     * {@link #CAMPAIGNS_RESOURCE_DIR}）。
     *
     * @return 可写战役根目录
     */
    public static Path campaignsDir() {
        return dataDir().resolve(CAMPAIGNS_DIR_NAME);
    }

    /**
     * 返回某个战役的目录 {@code <工程根>/data/campaigns/<name>}。
     *
     * @param campaignName 战役名（等于目录名）
     * @return 战役目录
     */
    public static Path campaignDir(String campaignName) {
        return campaignsDir().resolve(campaignName);
    }

    /**
     * 编辑器「测试」按钮用的调试战役名。
     *
     * <p>固定名字而不是每次新建，是为了不堆出一串 {@code _debug_1} / {@code _debug_2}：
     * 调试产物本来就该被覆盖。以 {@code _} 开头也便于一眼认出是临时目录。
     */
    public static final String DEBUG_CAMPAIGN_NAME = "_debug";

    /**
     * 调试战役里的地图文件名。
     *
     * <p>⚠️ 编辑器写这个文件、游戏端读这个文件，<strong>两边必须用同一个常量</strong>。
     * 以前两边各写一遍字面量 {@code "map_001.json"}，恰好一致所以能用；
     * 等地图名可以自定义之后，那就成了"改了名字、测试按钮静默失效"的种子。
     *
     * <p>现在它与 {@link #DEBUG_CAMPAIGN_NAME} 相等 —— 因为契约是
     * <b>地图文件名 ≡ 战役名</b>（{@code CampaignMeta.mapPathFor}）。
     * 调试战役也不例外：{@code _debug/map/_debug.json}。
     * 保留这个常量只是为了让"调试战役的文件叫什么"有一个名字可引用。
     */
    public static final String DEBUG_MAP_FILE_NAME = DEBUG_CAMPAIGN_NAME + ".json";

    /**
     * 调试战役里地图文件的完整路径。
     *
     * @return {@code <工程根>/data/campaigns/_debug/map/_debug.json}
     */
    public static Path debugMapFile() {
        return campaignDir(DEBUG_CAMPAIGN_NAME)
                .resolve(com.yestercar.common.model.CampaignMeta.MAP_DIR)
                .resolve(DEBUG_MAP_FILE_NAME);
    }

    /**
     * 首次运行时创建可写数据目录（幂等）。
     *
     * <p>⚠️ 这里<strong>不再</strong>创建 {@code data/maps} 与 {@code data/levels}：
     * 那两层在「一个战役 = 一张地图」之后就废弃了 —— 地图现在自包含在
     * {@code data/campaigns/<战役>/map/} 里，由 {@code CampaignStore} 按需创建。
     *
     * @throws IOException 目录创建失败（通常是权限问题）
     */
    public static void ensureWritableDirs() throws IOException {
        Files.createDirectories(imgsDir());
        Files.createDirectories(campaignsDir());
    }

    // ------------------------------------------------------------------
    // 可写数据：战役在地图上的位置由 CampaignMeta 决定
    // ------------------------------------------------------------------

    private static String withJsonSuffix(String name) {
        return name.endsWith(".json") ? name : name + ".json";
    }

    // ------------------------------------------------------------------
    // 只读资源
    //
    // 有两组方法，区别是「相对于谁」：
    //   * xxxResource(name)            → 相对 resources/data/，用于敌人/干员/贴图
    //   * xxxClasspathResource(path)   → 相对 classpath 根，用于 campaigns/ 等
    // 调用时别混用，否则会得到 "data/campaigns/..." 这种拼错的路径。
    // ------------------------------------------------------------------

    /**
     * 以 classpath 方式打开 {@code resources/data/} 下的只读资源。
     *
     * <p>示例：{@code openResource("enemies.json")} 会读取
     * {@code src/main/resources/data/enemies.json}（或 jar 内同路径）。
     *
     * @param relativeName 相对 {@code resources/data/} 的文件名
     * @return 输入流；调用方负责关闭
     * @throws IOException 资源不存在或无法读取
     */
    public static InputStream openResource(String relativeName) throws IOException {
        return openClasspathResource(RESOURCE_DATA_PREFIX + relativeName);
    }

    /**
     * 判断 {@code resources/data/} 下的只读资源是否存在，
     * 用于「贴图存在则用贴图，否则用色块」的美术规则。
     *
     * @param relativeName 相对 {@code resources/data/} 的文件名
     * @return 存在返回 {@code true}
     */
    public static boolean resourceExists(String relativeName) {
        return classpathResourceExists(RESOURCE_DATA_PREFIX + relativeName);
    }

    /**
     * 把 {@code resources/data/} 下的只读资源整体读成字符串（UTF-8）。
     *
     * @param relativeName 相对 {@code resources/data/} 的文件名
     * @return 文件内容
     * @throws IOException 资源不存在或读取失败
     */
    public static String readResourceAsString(String relativeName) throws IOException {
        return readClasspathResourceAsString(RESOURCE_DATA_PREFIX + relativeName);
    }

    /**
     * 以 classpath 方式打开**相对 classpath 根**的只读资源。
     *
     * <p>与 {@link #openResource(String)} 的区别：本方法不加 {@code data/} 前缀，
     * 用于 {@code campaigns/} 这类与 {@code data/} 平级的目录。
     *
     * @param classpathPath 相对 classpath 根的路径，如 {@code "campaigns/index.json"}
     * @return 输入流；调用方负责关闭
     * @throws IOException 资源不存在或无法读取
     */
    public static InputStream openClasspathResource(String classpathPath) throws IOException {
        String path = stripLeadingSlash(classpathPath);
        InputStream in = Workspace.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IOException("找不到 classpath 资源: " + path);
        }
        return in;
    }

    /**
     * 判断**相对 classpath 根**的只读资源是否存在。
     *
     * @param classpathPath 相对 classpath 根的路径
     * @return 存在返回 {@code true}
     */
    public static boolean classpathResourceExists(String classpathPath) {
        return Workspace.class.getClassLoader()
                .getResource(stripLeadingSlash(classpathPath)) != null;
    }

    /**
     * 把**相对 classpath 根**的只读资源整体读成字符串（UTF-8）。
     *
     * @param classpathPath 相对 classpath 根的路径
     * @return 文件内容
     * @throws IOException 资源不存在或读取失败
     */
    public static String readClasspathResourceAsString(String classpathPath) throws IOException {
        try (InputStream in = openClasspathResource(classpathPath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String stripLeadingSlash(String path) {
        String result = path;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 只读资源的目录列举（P0.2 验证项）
    // ------------------------------------------------------------------

    /** 官方战役等只读资源的根目录（相对 classpath 根）。 */
    public static final String CAMPAIGNS_RESOURCE_DIR = "campaigns";

    /**
     * 列出 classpath 上某个资源目录下的**直接子目录**名。
     *
     * <p>这是本工程里唯一需要区分「文件系统运行」与「jar 内运行」的方法，
     * 因为 {@code getResourceAsStream} 只能按名字取、**不能列目录**。两种形态：
     *
     * <table border="1">
     *   <caption>两种运行形态下的资源 URL</caption>
     *   <tr><th>形态</th><th>URL 形如</th><th>列举方式</th></tr>
     *   <tr>
     *     <td>IDEA / {@code target/classes}</td>
     *     <td>{@code file:/D:/.../target/classes/campaigns/}</td>
     *     <td>{@code Files.list}</td>
     *   </tr>
     *   <tr>
     *     <td>打包后的 jar</td>
     *     <td>{@code jar:file:/D:/.../YesterCar.jar!/campaigns/}</td>
     *     <td>{@code JarFile.entries()} 前缀匹配</td>
     *   </tr>
     * </table>
     *
     * <p><strong>不抛异常</strong>：目录不存在、URL 协议不认识、jar 读不了，
     * 一律返回空集合。调用方据此降级到索引文件方案。这样即使将来换了打包方式，
     * 最坏结果也只是「官方战役列不出来」，而不是启动崩溃。
     *
     * @param resourceDir 相对 classpath 根的资源目录，如 {@code "campaigns"}；
     *                    前后带不带 {@code /} 都可以
     * @return 直接子目录名（不含路径），已排序；失败返回空集合
     */
    public static List<String> listResourceChildren(String resourceDir) {
        String normalized = resourceDir;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.isEmpty() && !normalized.endsWith("/")) {
            normalized = normalized + "/";
        }

        URL url = Workspace.class.getClassLoader().getResource(normalized);
        if (url == null) {
            System.out.println("[Workspace] 资源目录不存在: " + normalized);
            return List.of();
        }

        String protocol = url.getProtocol();
        try {
            if ("file".equals(protocol)) {
                return listFromFileSystem(url);
            }
            if ("jar".equals(protocol)) {
                return listFromJar(url, normalized);
            }
            // 其它协议（应用服务器里的 vfs / bundleresource 等）本工程不涉及
            System.out.println("[Workspace] 不认识的资源协议: " + protocol + " @ " + url);
            return List.of();
        } catch (IOException | RuntimeException e) {
            System.out.println("[Workspace] 列举资源目录失败: " + normalized + " — " + e);
            return List.of();
        }
    }

    /** 形态一：IDEA / {@code target/classes}，直接从文件系统读。 */
    private static List<String> listFromFileSystem(URL url) throws IOException {
        Path dir = Path.of(URI.create(url.toString()));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /** 形态二：打包后的 jar，遍历全部条目做前缀匹配。 */
    private static List<String> listFromJar(URL url, String normalized) throws IOException {
        // URL 形如 jar:file:/D:/path/YesterCar.jar!/campaigns/
        // 用字符串切分比走 JarURLConnection 更直接，也不依赖连接状态
        String spec = url.toString();
        int bang = spec.indexOf("!/");
        if (bang < 0) {
            return List.of();
        }
        String jarSpec = spec.substring("jar:".length(), bang);

        Set<String> names = new TreeSet<>();
        try (JarFile jar = new JarFile(Path.of(URI.create(jarSpec)).toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(normalized) || name.length() <= normalized.length()) {
                    continue;
                }
                String rest = name.substring(normalized.length());
                int slash = rest.indexOf('/');
                // 只保留「本层目录」：rest 必须是 "xxx/" 形式，更深的层级不算
                if (slash == rest.length() - 1) {
                    names.add(rest.substring(0, slash));
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * 列出 classpath 上某个资源目录下的**直接子文件**名（不含子目录）。
     *
     * <p>{@link #listResourceChildren} 的姊妹方法 —— 两者都只列"本层"。
     * 拆成两个方法而不是"一次返回全部再让调用方过滤"，是因为
     * <strong>"什么算直接子项"的判断在 file 与 jar 两种形态下不一样</strong>
     * （jar 要走前缀匹配），放在一处才不会两套逻辑走偏。
     *
     * <p>失败的后果同样只是"列不出来"，返回空表而不是抛异常。
     *
     * @param resourceDir 相对 classpath 根的目录，如 {@code campaigns/official_01/maps}
     * @return 直接子文件名（已排序）；不存在时为空表
     */
    public static List<String> listResourceFiles(String resourceDir) {
        String normalized = resourceDir;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.isEmpty() && !normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        URL url = Workspace.class.getClassLoader().getResource(normalized);
        if (url == null) {
            return List.of();
        }
        try {
            if ("file".equals(url.getProtocol())) {
                return listFilesFromFileSystem(url);
            }
            if ("jar".equals(url.getProtocol())) {
                return listFilesFromJar(url, normalized);
            }
            System.out.println("[Workspace] 不认识的资源协议: " + url.getProtocol() + " @ " + url);
            return List.of();
        } catch (IOException | RuntimeException e) {
            System.out.println("[Workspace] 列举资源文件失败: " + normalized + " — " + e);
            return List.of();
        }
    }

    /** 形态一：文件系统下的直接子文件。 */
    private static List<String> listFilesFromFileSystem(URL url) throws IOException {
        Path dir = Path.of(URI.create(url.toString()));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /** 形态二：jar 内的直接子文件（rest 里不含 '/' 即为本层文件）。 */
    private static List<String> listFilesFromJar(URL url, String normalized) throws IOException {
        String spec = url.toString();
        int bang = spec.indexOf("!/");
        if (bang < 0) {
            return List.of();
        }
        String jarSpec = spec.substring("jar:".length(), bang);

        Set<String> names = new TreeSet<>();
        try (JarFile jar = new JarFile(Path.of(URI.create(jarSpec)).toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(normalized)
                        || name.length() <= normalized.length()) {
                    continue;
                }
                String rest = name.substring(normalized.length());
                // 不含 '/' 才是本层文件；含 '/' 说明在更深的层级
                if (rest.indexOf('/') < 0) {
                    names.add(rest);
                }
            }
        }
        return List.copyOf(names);
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    /**
     * 返回一行环境摘要，供启动日志与自检打印。
     *
     * @return 形如 {@code 工程根=... | 可写数据=... | 战役目录=...} 的摘要
     */
    public static String describe() {
        return "工程根=" + projectRoot()
                + System.lineSeparator() + "  可写数据=" + dataDir()
                + System.lineSeparator() + "  战役目录=" + campaignsDir();
    }
}
