package com.yestercar.gameopening;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;

/**
 * 一条音频轨（背景音乐 / 助理问候语音都用它）。
 *
 * <h2>为什么是 WAV 而不是 MP3</h2>
 * Java 自带的 {@code javax.sound.sampled} **不能解 MP3**（JDK 从来没带过 MP3 解码器），
 * 而本构建环境取不到第三方解码库（`mp3spi` / `jlayer` 下载失败）。
 * MP3 素材需要用 ffmpeg 转成 WAV，命令写在 {@code data/music/README.md}。
 *
 * <h2>为什么用 {@link Clip}</h2>
 * {@code Clip} 把音频一次读进内存，之后由声卡自己播/循环：
 * <ul>
 *   <li>循环是**采样级**的，没有可听的接缝；</li>
 *   <li>不占用渲染线程，帧率不受影响。</li>
 * </ul>
 *
 * <h2>加载策略</h2>
 * 打开文件放在后台线程（WAV 可能有几 MB，同步读会让进主界面卡一下）。
 * 播放请求会被记下来，等加载完成再真正开始 —— 所以调用方直接调就行。
 */
final class AudioTrack {

    private static final Logger LOG = Logger.getLogger(AudioTrack.class.getName());

    /** 音频文件路径（相对工程根）。 */
    private final String path;

    /** 音量（分贝）。0 = 原始音量。 */
    private final float volumeDb;

    /** 全局音量倍率（0–1），与实例音量相乘。 */
    private static float masterGain = 1f;

    /**
     * 设置全局音量倍率（对所有轨道生效）。
     *
     * @param g 0–1
     */
    static void setMasterGain(float g) {
        masterGain = Math.max(0f, Math.min(1f, g));
    }

    /** @return 当前全局音量倍率 */
    static float masterGain() {
        return masterGain;
    }

    /** 是否循环。 */
    private final boolean looping;

    /** 已加载的 clip。 */
    private Clip clip;

    /** 下一次 {@link #play()} 是否强制回到开头（{@link #restart()} 置位）。 */
    private volatile boolean rewindNext;

    /** 是否已经开始过加载线程（只加载一次）。 */
    private boolean loadStarted;

    /** 加载失败过（只警告一次，避免每次进主界面都刷屏）。 */
    private boolean failed;

    /** 加载完成后是否要立刻播放（用于"还没加载完就要求播放"）。 */
    private boolean playRequested;

    /**
     * @param path     音频文件（相对工程根）
     * @param volumeDb 音量，分贝；0 = 原始
     * @param looping  是否循环
     */
    AudioTrack(String path, float volumeDb, boolean looping) {
        this.path = path;
        this.volumeDb = volumeDb;
        this.looping = looping;
    }

    /**
     * 开始播放。
     *
     * <p>循环轨已在播则什么也不做；单次轨会**从头重放**（先停再定位到 0）。
     *
     * <p>加载未完成时会记下请求，加载完自动开始。
     */
    void play() {
        if (failed) {
            return;
        }
        if (clip == null) {
            playRequested = true;
            ensureLoading();
            return;
        }
        try {
            if (clip.isRunning()) {
                if (looping) {
                    return;             // 背景音乐已在放，不打断
                }
                clip.stop();            // 语音：重进主界面时从头再播
            }
            // 单次轨（语音）每次都从头；循环轨默认"从原处续播"，
            // 只有 restart() 显式要求时才回到 0。
            if (!looping || rewindNext) {
                clip.setFramePosition(0);
                rewindNext = false;
            }
            if (looping) {
                clip.loop(Clip.LOOP_CONTINUOUSLY);
            }
            clip.start();
            LOG.info("开始播放: " + new File(path).getName()
                    + (looping ? "（循环）" : "（单次 " + Math.round(
                            clip.getMicrosecondLength() / 1000.0) + " ms）"));
        } catch (Exception ex) {
            warnOnce(ex);
        }
    }

    /**
     * 从头重播（循环轨也要回到 0）。
     *
     * <p>{@link #play()} 对循环轨是"接着放"的（避免主菜单每次重进都突兀地从头开始），
     * 但**从战斗返回主菜单**时玩家听到的应该是重新开始的曲子，
     * 所以那条路径要调这个而不是 {@code play()}。
     */
    void restart() {
        rewindNext = true;
        play();
    }

    /**
     * @return 是否正在播放
     */
    boolean isPlaying() {
        Clip c = clip;
        return c != null && c.isRunning();
    }

    /** 停止播放并释放资源。 */
    void stop() {
        playRequested = false;
        Clip c = clip;
        if (c == null) {
            return;
        }
        try {
            c.stop();
            c.flush();
        } catch (Exception ignored) {
            // 停止失败不影响主流程
        }
    }

    /** 彻底释放（退出程序时）。 */
    void dispose() {
        playRequested = false;
        Clip c;
        synchronized (this) {
            c = clip;
            clip = null;
            // 允许将来重新加载：万一 dispose 之后又被要求播放（例如阶段回退），
            // 不至于变成永久静音。
            loadStarted = false;
        }
        if (c == null) {
            return;
        }
        try {
            c.stop();
            c.close();
        } catch (Exception ignored) {
            // 关闭失败不影响退出
        }
    }

    /** 后台加载（只做一次）。 */
    private synchronized void ensureLoading() {
        if (loadStarted) {
            return;
        }
        loadStarted = true;
        Thread t = new Thread(() -> {
            File f = new File(path);
            if (!f.isFile()) {
                failed = true;
                LOG.warning("缺音频文件，跳过播放: " + f.getAbsolutePath());
                return;
            }
            try (AudioInputStream in = AudioSystem.getAudioInputStream(f)) {
                Clip c = AudioSystem.getClip();
                c.open(in);
                applyVolume(c);
                synchronized (this) {
                    clip = c;
                }
                LOG.info("音频已就绪: " + f.getName()
                        + (looping ? "（循环）" : "（单次）"));
                if (playRequested) {
                    play();
                }
            } catch (Exception ex) {
                warnOnce(ex);
            }
        }, "yestercar-audio-" + path.substring(path.lastIndexOf('/') + 1));
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把主音量调到构造时给的分贝。
     *
     * @param c 目标 clip
     */
    /** 全局音量变化后，把当前 clip 重新调一次。 */
    void refreshVolume() {
        Clip c = clip;
        if (c != null) {
            applyVolume(c);
        }
    }

    private void applyVolume(Clip c) {
        try {
            if (c.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
                // 倍率换算成分贝：gain 0 → -80dB（近似静音）
                float mag = Math.max(0.0001f, masterGain);
                float db = volumeDb + (float) (20.0 * Math.log10(mag));
                db = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db));
                gain.setValue(db);
            }
        } catch (Exception ignored) {
            // 某些声卡不支持主音量控制 —— 不影响播放
        }
    }

    /**
     * 只警告一次。
     *
     * @param ex 原因
     */
    private void warnOnce(Exception ex) {
        if (failed) {
            return;
        }
        failed = true;
        LOG.log(Level.WARNING, "音频加载/播放失败: " + new File(path).getAbsolutePath(), ex);
    }
}
