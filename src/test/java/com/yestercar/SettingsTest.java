package com.yestercar;

import com.yestercar.common.io.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑器配置：记忆「上次用过的目录」。
 *
 * <p>这条功能的失败方式都很讨厌 —— 记不住用户最多嫌烦，但**读坏配置导致编辑器打不开**
 * 是无法接受的。所以本类重点测"坏输入不致命"。
 */
@DisplayName("编辑器配置（Settings）")
class SettingsTest {

    @Test
    @DisplayName("文件不存在时一切取默认值，不抛异常")
    void 文件不存在时取默认值(@TempDir Path dir) {
        Settings s = new Settings(dir.resolve("nope.properties"));

        assertEquals("fallback", s.get("missing.key", "fallback"));
        Path fallback = dir.resolve("somewhere");
        assertEquals(fallback, s.getLastMapDir(fallback), "没记录时应退回默认目录");
        assertFalse(Files.exists(s.file()));
    }

    @Test
    @DisplayName("写入再读出，值能往返")
    void 往返一致(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Path mapDir = Files.createDirectories(dir.resolve("maps"));

        Settings a = new Settings(file);
        a.setLastMapDir(mapDir);
        assertTrue(a.save(), "写盘应当成功");
        assertTrue(Files.isRegularFile(file));

        Settings b = new Settings(file);
        assertEquals(mapDir.toAbsolutePath(), b.getLastMapDir(dir),
                "应当读出刚才记下的目录");
    }

    @Test
    @DisplayName("记录的目录被删掉后，自动退回默认值")
    void 目录消失后退回默认(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Path gone = Files.createDirectories(dir.resolve("will_be_deleted"));

        Settings a = new Settings(file);
        a.setLastMapDir(gone);
        a.save();

        // 删掉那个目录 —— 用户的 U 盘拔了、临时目录被清了，都是这种情形
        Files.delete(gone);

        Settings b = new Settings(file);
        Path fallback = dir.resolve("fallback");
        assertEquals(fallback, b.getLastMapDir(fallback),
                "目录不存在时不该返回一个已经失效的路径");
    }

    @Test
    @DisplayName("配置文件损坏时不致命，退回默认值")
    void 配置损坏不致命(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        // 写一堆不是 properties 的字节（含无法解码的序列）
        Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x41, 0x3D, 0x42});

        Settings s = new Settings(file);   // 不该抛异常
        assertNotNull(s);
        assertEquals("fallback", s.get("anything", "fallback"));
    }

    @Test
    @DisplayName("路径字符串非法时也不致命")
    void 路径非法不致命(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, Settings.KEY_LAST_MAP_DIR + "=\u0000bad\u0000path\n",
                StandardCharsets.UTF_8);

        Settings s = new Settings(file);
        Path fallback = dir.resolve("fallback");
        assertEquals(fallback, s.getLastMapDir(fallback),
                "非法路径应当被忽略，而不是抛异常");
    }

    @Test
    @DisplayName("设成空白等于删除该键")
    void 空白等于删除(@TempDir Path dir) {
        Settings s = new Settings(dir.resolve("settings.properties"));

        s.set("k", "v");
        assertEquals("v", s.get("k", "d"));

        s.set("k", "   ");
        assertEquals("d", s.get("k", "d"), "空白值应当被当作删除");

        s.set("k", null);
        assertEquals("d", s.get("k", "d"), "null 也应当被当作删除");
    }

    @Test
    @DisplayName("写盘失败不抛异常（目录不可写也不该崩）")
    void 写盘失败不抛异常(@TempDir Path dir) throws IOException {
        // 拿一个「文件」当父目录用 —— createDirectories 必然失败
        Path blocker = Files.writeString(dir.resolve("blocker"), "x");
        Settings s = new Settings(blocker.resolve("sub/settings.properties"));

        assertFalse(s.save(), "写不进去应当返回 false，而不是抛异常");
    }

    @Test
    @DisplayName("工作区配置落在 data/ 下")
    void 工作区配置位置正确() {
        Settings s = Settings.inWorkspace();
        assertEquals(com.yestercar.common.io.Workspace.dataDir()
                        .resolve(Settings.FILE_NAME),
                s.file(), "配置应当落在 data/settings.properties");
        assertTrue(s.file().startsWith(com.yestercar.common.io.Workspace.dataDir()));
    }
}
