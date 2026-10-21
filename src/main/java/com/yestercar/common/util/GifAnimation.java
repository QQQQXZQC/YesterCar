package com.yestercar.common.util;

import processing.core.PApplet;
import processing.core.PImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * GIF 逐帧动画播放器。
 *
 * <h2>为什么用 {@code javax.imageio} 而不是 Processing 的 {@code loadImage}</h2>
 * {@code PApplet.loadImage()} 对 GIF **只取第一帧**，拿不到动画。
 * 而 {@code javax.imageio} 是 JDK 自带的，能逐帧解码、保留透明通道，
 * <strong>不需要任何外部依赖</strong>（不像 Spine 那套要拖 gdx 进来）。
 *
 * <h2>加载即解码</h2>
 * 构造时一次性把所有帧解成 {@link PImage} 全部放进内存。
 * 对加载动画这种小尺寸短循环（实测 180×213、35 帧、约 1.16 秒）完全够用，
 * 换来的是播放时零解码开销、绝不掉帧。
 *
 * <h2>帧延时</h2>
 * 从 GIF 的 Graphic Control Extension 里读真实的帧延时（单位 10ms）。
 * 读不到时用 {@link #DEFAULT_FRAME_DELAY_MS}。
 */
public final class GifAnimation {

    /** 读不到帧延时时使用的默认值（毫秒），约 30fps。 */
    public static final int DEFAULT_FRAME_DELAY_MS = 33;

    private final PImage[] frames;
    private final int[] frameDelaysMs;
    private final int totalDurationMs;

    private GifAnimation(PImage[] frames, int[] frameDelaysMs, int totalDurationMs) {
        this.frames = frames;
        this.frameDelaysMs = frameDelaysMs;
        this.totalDurationMs = totalDurationMs;
    }

    /**
     * 加载一个 GIF。
     *
     * @param applet   所属窗口，用于把 {@code BufferedImage} 转成 {@code PImage}
     * @param gifFile  GIF 文件路径
     * @return 播放器
     * @throws IOException 文件不存在、无 GIF 解码器、或一帧都解不出来
     */
    public static GifAnimation load(PApplet applet, Path gifFile) throws IOException {
        if (!Files.isRegularFile(gifFile)) {
            throw new IOException("找不到 GIF 文件: " + gifFile);
        }

        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) {
            throw new IOException("当前 JDK 没有 GIF 解码器");
        }
        ImageReader reader = readers.next();

        List<PImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();

        try (InputStream fileStream = Files.newInputStream(gifFile);
             ImageInputStream input = ImageIO.createImageInputStream(fileStream)) {

            reader.setInput(input, false);
            int count = reader.getNumImages(true);
            for (int i = 0; i < count; i++) {
                BufferedImage frame = reader.read(i);
                if (frame == null) {
                    continue;
                }
                frames.add(toPImage(applet, frame));
                delays.add(readFrameDelayMs(reader, i));
            }
        } finally {
            reader.dispose();
        }

        if (frames.isEmpty()) {
            throw new IOException("GIF 里没有可解码的帧: " + gifFile);
        }

        int[] delayArray = new int[delays.size()];
        int total = 0;
        for (int i = 0; i < delayArray.length; i++) {
            delayArray[i] = delays.get(i);
            total += delayArray[i];
        }
        return new GifAnimation(frames.toArray(new PImage[0]), delayArray, total);
    }

    /** 从 GIF 元数据里读第 i 帧的延时（毫秒）。读不到返回默认值。 */
    private static int readFrameDelayMs(ImageReader reader, int index) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(index);
            String format = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

            IIOMetadataNode gce = findChild(root, "GraphicControlExtension");
            if (gce == null) {
                return DEFAULT_FRAME_DELAY_MS;
            }
            String raw = gce.getAttribute("delayTime");
            if (raw == null || raw.isEmpty()) {
                return DEFAULT_FRAME_DELAY_MS;
            }
            int hundredths = Integer.parseInt(raw);
            // GIF 里 0 表示"尽快"，很多导出器会用 0 表示未设置
            if (hundredths <= 0) {
                return DEFAULT_FRAME_DELAY_MS;
            }
            return hundredths * 10;
        } catch (Exception e) {
            return DEFAULT_FRAME_DELAY_MS;
        }
    }

    /** 在元数据树里按名字找一个直接子节点。 */
    private static IIOMetadataNode findChild(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) parent.item(i);
            }
        }
        return null;
    }

    /** 把 {@code BufferedImage} 逐像素拷成 {@code PImage}（保留 alpha）。 */
    private static PImage toPImage(PApplet applet, BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        PImage result = applet.createImage(w, h, PApplet.ARGB);
        image.getRGB(0, 0, w, h, result.pixels, 0, w);
        result.updatePixels();
        return result;
    }

    /**
     * 取指定时刻应显示的帧。
     *
     * <p>按累计延时的真实节奏走，不是简单的「毫秒 / 平均延时」——
     * GIF 每帧延时可能不同（实测本素材是 30/40ms 交替）。
     *
     * @param elapsedMs 从动画开始算起的毫秒数
     * @return 该时刻的帧；永远不为 {@code null}
     */
    public PImage frameAt(long elapsedMs) {
        if (frames.length == 1 || totalDurationMs <= 0) {
            return frames[0];
        }
        long t = elapsedMs % totalDurationMs;
        if (t < 0) {
            t += totalDurationMs;
        }
        int acc = 0;
        for (int i = 0; i < frames.length; i++) {
            acc += frameDelaysMs[i];
            if (t < acc) {
                return frames[i];
            }
        }
        return frames[frames.length - 1];
    }

    /**
     * @return 帧数
     */
    public int frameCount() {
        return frames.length;
    }

    /**
     * @return 一轮播放的总时长（毫秒）
     */
    public int totalDurationMs() {
        return totalDurationMs;
    }

    /**
     * @return 单帧宽度（像素）
     */
    public int width() {
        return frames[0].width;
    }

    /**
     * @return 单帧高度（像素）
     */
    public int height() {
        return frames[0].height;
    }

    @Override
    public String toString() {
        return "GifAnimation[" + width() + "x" + height()
                + " frames=" + frames.length
                + " duration=" + totalDurationMs + "ms]";
    }
}
