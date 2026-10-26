import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 逐像素比较两张 PNG（官方渲染 vs 本工程渲染），并可选输出一张"差异热力图"。
 *
 * <p>用法：
 * {@code java PixelDiff <official.png> <ours.png> [heat.png] [阈值]}
 *
 * <p>输出（stdout，供脚本解析）：
 * <pre>
 * SIZE 512 512
 * DIFF 14900 5.684
 * </pre>
 * 第一行是全图尺寸，第二行是"亮度差 &gt; 阈值"的像素数与百分比。
 */
public final class PixelDiff {

    private PixelDiff() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: PixelDiff <official.png> <ours.png> [heat.png] [阈值]");
            System.exit(2);
        }
        BufferedImage a = ImageIO.read(new File(args[0]));
        BufferedImage b = ImageIO.read(new File(args[1]));
        if (a == null || b == null) {
            System.err.println("读不了图片");
            System.exit(2);
        }
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        int threshold = args.length > 3 ? Integer.parseInt(args[3]) : 24;
        String heatPath = args.length > 2 && !args[2].isEmpty() ? args[2] : null;

        BufferedImage heat = heatPath == null ? null : new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        long bad = 0;
        long veryBad = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int dr = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
                int dg = Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
                int db = Math.abs((p & 0xFF) - (q & 0xFF));
                int d = Math.max(dr, Math.max(dg, db));
                if (d > threshold) {
                    bad++;
                }
                if (d > 96) {
                    veryBad++;
                }
                if (heat != null) {
                    int v = Math.min(255, d * 3);
                    int rgb = d > 12 ? (v << 16) : 0x101018;
                    heat.setRGB(x, y, rgb);
                }
            }
        }
        System.out.println("SIZE " + w + " " + h);
        System.out.printf(java.util.Locale.ROOT, "DIFF %d %.3f%n", bad, bad * 100.0 / (w * (double) h));
        System.out.println("STRONG " + veryBad);
        if (heat != null) {
            ImageIO.write(heat, "png", new File(heatPath));
        }
    }
}
