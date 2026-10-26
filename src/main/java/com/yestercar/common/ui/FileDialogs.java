package com.yestercar.common.ui;

import com.yestercar.common.io.Settings;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.nio.file.Path;

/**
 * 文件对话框。
 * <h2>两个控件分工，别混用</h2>
 * <ul>
 * <li><b>{@link #saveWithNativeDialog}</b> —— 系统原生保存框（{@link FileDialog}）。
 * 用于「另存为」，因为它有真正的文本框（拖动选中、中文输入都正常）
 * 和「新建文件夹」按钮。</li>
 * <li><b>{@link JFileChooser}</b> —— 用于「打开」与"只选目录"。
 * 它最大的好处是<strong>能记住上次目录</strong>：
 * 原生框<strong>无法</strong>预设目录，每次都从头开始找，
 * 用户选一次目录要翻好几层。</li>
 * </ul>
 * <p>做成实例而不是全静态，是为了让单元测试能指向临时目录。
 */
public final class FileDialogs {

    /** 地图文件的扩展名。 */
    public static final String JSON = "json";

    private final Settings settings;

    /**
 * @param settings 用来记住/恢复上次目录
 */
    public FileDialogs(Settings settings) {
        this.settings = settings;
    }

    /**
 * 「打开」对话框。
 * @param title 对话框标题
 * @param defaultDir 没有历史记录时用的目录
 * @return 选中的文件；用户取消时返回 {@code null}
 */
    public Path open(String title, Path defaultDir) {
        JFileChooser chooser = build(title, defaultDir);
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return null;
        }
        remember(selected);
        return selected.toPath();
    }

    /**
 * 决定对话框从哪个目录开始。
 * <h2>⚠️ 调用方显式给的默认目录优先于"上次用过的目录"</h2>
 * 这里踩过坑：反过来写的话，记住了的旧目录会**盖掉**调用方的意图 ——
 * 比如「另存为战役」明确要求默认从 {@code data/campaigns} 开始，
 * 但上次用过的目录还停在 {@code data/maps}，于是玩家看到一个错的起点。
 * <p>所以顺序是：<b>默认目录存在就用它</b>；否则才退回记住的目录；
 * 都没有就用默认目录（哪怕还不存在，{@code JFileChooser} 会自己处理）。
 * <p>代价是"记住上次目录"在**同一入口连续使用**时不再生效 ——
 * 但那是可接受的：不同入口本来就有各自的合理起点，
 * 而"记住"最主要的用途是**在同一个入口里连续操作**，
 * 那种情况由 {@code remember} 写回、下次仍从默认开始，
 * 只是不再跨入口串味。
 * @param defaultDir 调用方指定的默认目录
 * @return 起始目录
 */
    private Path startDir(Path defaultDir) {
        if (defaultDir != null && defaultDir.toFile().isDirectory()) {
            return defaultDir;
        }
        return settings.getLastMapDir(defaultDir);
    }

    /**
 * 「另存为」对话框：**系统原生的保存框**（与记事本「另存为」同款）。
 * <h2>⚠️ 为什么不用 {@code JFileChooser}</h2>
 * 踩过两次，都是这个控件在"选目录"这个用途上不可靠：
 * <ol>
 * <li><b>选中当前目录时它把当前目录当成用户的选择。</b>
 * 实测：设好 {@code setSelectedFile(...\campaigns\新名字)} 之后打开，
 * 对话框里「文件夹名」显示的却是 {@code ...\campaigns} 本身 ——
 * 于是"战役名"变成了 {@code campaigns}，战役文件直接写进了
 * {@code data/campaigns/} 根下，而不是新建的文件夹里。</li>
 * <li><b>它的文本框手感是 Swing 自己实现的</b>，而我们只拿得到返回值 ——
 * 用户想要的"鼠标拖动选中"在画布上的输入框里永远做不到。</li>
 * </ol>
 * <p>原生框两条都没有：它有真正的文本框（拖动选中、中文输入都正常）、
 * 有「新建文件夹」按钮，返回的就是用户输入的路径。
 * <h2>返回的是"位置"</h2>
 * 本方法只负责让用户选出<b>存在哪、叫什么</b>。
 * 因为我们的落盘结构是固定的（一个文件夹 + 里面的 {@code campaign.json} 与
 * {@code maps/}），调用方通常只取父目录、文件夹名另按规则定 ——
 * 这样即使原生框的默认扩展名与我们的约定不同，也不影响结果。
 * @param title 对话框标题
 * @param defaultDir 默认起始目录；存在时以它为准
 * @param suggestedName 预填的名字（不含扩展名）
 * @return 用户选定的路径；取消时返回 {@code null}
 */
    public Path saveWithNativeDialog(String title, Path defaultDir, String suggestedName) {
        Path start = startDir(defaultDir);
        Frame owner = activeFrame();

        FileDialog dialog = new FileDialog(owner, title, FileDialog.SAVE);
        if (start != null && start.toFile().isDirectory()) {
            dialog.setDirectory(start.toFile().getAbsolutePath());
        }
        if (suggestedName != null && !suggestedName.isBlank()) {
 // 带扩展名，看起来才像个正常的"保存"对话框
            dialog.setFile(suggestedName + ".json");
        }
        dialog.setVisible(true);        // 模态，阻塞到用户关掉

        String dir = dialog.getDirectory();
        String file = dialog.getFile();
        if (dir == null || file == null) {
            return null;                // 取消
        }
        Path chosen = Path.of(dir, file);

 // 记住父目录：下次从同一层开始
        Path parent = chosen.getParent();
        if (parent != null) {
            settings.setLastMapDir(parent);
            settings.save();
        }
        return chosen;
    }

    /**
 * 找一个可当对话框 owner 的窗口。
 * <p>拿不到就用 {@code null}（无 owner）。原生对话框在 Windows 上
 * 即使没有 owner 也能正常弹出，只是不一定居中于编辑器窗口。
 * @return 当前可见的 Frame；没有返回 {@code null}
 */
    private static Frame activeFrame() {
        for (Frame f : Frame.getFrames()) {
            if (f.isVisible() && f.isActive()) {
                return f;
            }
        }
        for (Frame f : Frame.getFrames()) {
            if (f.isVisible()) {
                return f;
            }
        }
        return null;
    }

    /**
 * 「另存为」对话框：选一个**目录**（战役文件夹）。
 * <p>一个战役就是一个文件夹，所以让用户选目录比让他输文件名更贴切 ——
 * 文件夹名就是战役名，内部的文件名由程序按约定拼。
 * @param title 对话框标题
 * @param defaultDir 默认起始目录；存在时以它为准
 * @param suggestedName 预填的文件夹名（战役名）；{@code null} 表示不预填
 * @return 选中的目录（可能还不存在，由调用方创建）；取消时返回 {@code null}
 */
    public Path chooseDirectory(String title, Path defaultDir, String suggestedName) {
        JFileChooser chooser = baseChooser(title, defaultDir);
        if (suggestedName != null && !suggestedName.isBlank()) {
            chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), suggestedName));
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return null;
        }
 // 记住**父目录**：下次从同一层开始找
        File parent = selected.getParentFile();
        if (parent != null) {
            settings.setLastMapDir(parent.toPath());
            settings.save();
        }
        return selected.toPath();
    }

    /**
 * 「打开」对话框：选一个**目录**（战役文件夹）。
 * @param title 对话框标题
 * @param defaultDir 默认起始目录；存在时以它为准
 * @return 选中的目录；用户取消时返回 {@code null}
 */
    public Path openDirectory(String title, Path defaultDir) {
        JFileChooser chooser = baseChooser(title, defaultDir);
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return null;
        }
        remember(selected);
        return selected.toPath();
    }

    /**
 * 建一个"只选目录"的选择器。
 * <p>⚠️ <strong>必须保留「所有文件」过滤器</strong>：目录模式下没有可选的文件类型，
 * 一旦 {@code setAcceptAllFileFilterUsed(false)}，那个下拉框就是**空白**的 ——
 * 用户看到一个没有内容的控件，会以为界面坏了（实测踩过）。
 * <p>所以这里显式加一个"文件夹"过滤器并选中它，让下拉框有内容可显示。
 */
    private JFileChooser baseChooser(String title, Path defaultDir) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(true);

 // 给下拉框一个明确的名字 —— 只选目录时它就是"文件夹"
        javax.swing.filechooser.FileFilter folderFilter =
                new javax.swing.filechooser.FileFilter() {
                    @Override
                    public boolean accept(File f) {
                        return f.isDirectory();
                    }

                    @Override
                    public String getDescription() {
                        return "文件夹";
                    }
                };
        chooser.addChoosableFileFilter(folderFilter);
        chooser.setFileFilter(folderFilter);

        Path start = startDir(defaultDir);
        if (start != null && start.toFile().isDirectory()) {
            chooser.setCurrentDirectory(start.toFile());
        }
        return chooser;
    }

    /**
 * 建一个统一配置好的选择器。
 * <p>两个过滤器都加上，并<strong>允许"所有文件"</strong>：只留 json 的话，
 * 用户想打开一个扩展名写错的文件就彻底没辙。
 */
    private JFileChooser build(String title, Path defaultDir) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(true);

        FileNameExtensionFilter jsonFilter =
                new FileNameExtensionFilter("地图文件 (*.json)", JSON);
        chooser.addChoosableFileFilter(jsonFilter);
 // 默认选中 json，但下拉里仍能切到「所有文件」
        chooser.setFileFilter(jsonFilter);

        Path start = startDir(defaultDir);
        if (start != null && start.toFile().isDirectory()) {
            chooser.setCurrentDirectory(start.toFile());
        }
        return chooser;
    }

    /** 记住这次选的目录（取它所在的那一层）。 */
    private void remember(File selected) {
        File dir = selected.isDirectory() ? selected : selected.getParentFile();
        if (dir != null) {
            settings.setLastMapDir(dir.toPath());
            settings.save();
        }
    }
}
