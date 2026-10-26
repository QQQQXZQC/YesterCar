package com.yestercar.runtime.view;

import com.yestercar.common.io.Workspace;
import com.yestercar.runtime.battle.EnemyType;
import com.yestercar.runtime.spine.SpineData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PImage;

/**
 * 诊断工具：把**敌人死亡过程**按时间轴取样铺成一张图。
 *
 * <p>为什么需要它：死亡过程只有 1–2 秒，而且要先打满一场才看得到，
 * 战斗截图很难正好抓到"倒地定格"那一帧。这个工具直接驱动
 * {@link EnemyAnimator}（战斗里同一个类、同一段 {@code drawCorpse} 代码），
 * 沿"Die 播完 → 定格 → 淡出"取 7 个采样点，一眼能核对整段设计。
 *
 * <h2>用法</h2>
 * <pre>
 * java -cp target/YesterCar.jar com.yestercar.runtime.view.EnemyDeathSheet \
 *      --out D:\shots\death.png --ids soldier,hound_pro,big_bob
 * </pre>
 *
 * <p>参数：{@code --out} 输出 png；{@code --ids} 逗号分隔敌人 id（默认取 6 个代表）。
 */
public final class EnemyDeathSheet extends PApplet {

    /** 默认展示的敌人（近战小兵 / 猎狗 / 重装 / 术师 / 精英 / 领袖）。 */
    private static final String[] DEFAULT_IDS =
            {"soldier", "hound_pro", "heavy_defender", "caster_elite", "sarkaz_greatsword",
                "big_bob"};

    private static String outPath = "enemy-death.png";
    private static String idsArg;

    private static final int CELL_W = 150;
    private static final int CELL_H = 170;
    private static final int HEAD = 46;

    private final List<String> names = new ArrayList<>();
    private final List<SpineData> models = new ArrayList<>();
    private final List<PImage> textures = new ArrayList<>();
    private int frameCount;

    /**
     * 入口。
     *
     * @param args 见类注释
     */
    public static void main(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> outPath = args[++i];
                case "--ids" -> idsArg = args[++i];
                default -> System.out.println("[DeathSheet] 未知参数: " + args[i]);
            }
        }
        PApplet.main(EnemyDeathSheet.class);
    }

    /** @return 要展示的敌人 id（只保留素材存在的） */
    private static List<String> ids() {
        Map<String, EnemyType> all = EnemyType.loadAll();
        List<String> out = new ArrayList<>();
        if (idsArg != null && !idsArg.isBlank()) {
            for (String s : idsArg.split(",")) {
                String id = s.trim();
                if (all.containsKey(id)) {
                    out.add(id);
                } else {
                    System.out.println("[DeathSheet] 没有这个敌人: " + id);
                }
            }
            return out;
        }
        for (String id : DEFAULT_IDS) {
            if (all.containsKey(id)) {
                out.add(id);
            }
        }
        return out;
    }

    @Override
    public void settings() {
        List<String> ids = ids();
        size(CELL_W * 7 + 8, HEAD + Math.max(1, ids.size()) * CELL_H + 8, PConstants.P2D);
        pixelDensity(1);
    }

    @Override
    public void setup() {
        surface.setTitle("EnemyDeathSheet");
        for (String id : ids()) {
            Path dir = Workspace.projectRoot().resolve(SpineEnemyLayer.SPINE_DIR + id);
            try {
                SpineData d = SpineData.load(dir);
                PImage tex = loadImage(dir.resolve(d.atlas().page()).toString());
                if (tex == null) {
                    System.out.println("[DeathSheet] 贴图缺失 " + id);
                    continue;
                }
                names.add(id);
                models.add(d);
                textures.add(tex);
            } catch (IOException | RuntimeException ex) {
                System.out.println("[DeathSheet] 加载失败 " + id + ": " + ex);
            }
        }
        System.out.println("[DeathSheet] 共 " + models.size() + " 个敌人");
        background(24);
    }

    @Override
    public void draw() {
        background(24);
        textAlign(PConstants.LEFT, PConstants.TOP);
        fill(220);
        textSize(13);
        // 用 ASCII 标题：Processing 默认字体没有中文字形，写中文会是一片空白
        text("enemy death: Die plays once -> DEATH_HOLD (" + EnemyAnimator.DEATH_HOLD
                + "s, last frame) -> fade out", 8, 8);
        textSize(10);
        fill(150);
        text("each cell = one sample; alpha in the label; dust ring shows in the first 0.45s",
                8, 26);

        for (int row = 0; row < models.size(); row++) {
            String id = names.get(row);
            SpineData d = models.get(row);
            PImage tex = textures.get(row);
            EnemyAnimator probe = new EnemyAnimator(id, d, tex);
            float die = Math.max(0.2f, probe.dieDuration());
            // 采样时刻：倒地中段 / 倒地末帧 / 定格中 / 定格末 / 淡出 1/3 / 淡出 2/3 / 即将消失
            float[] stamps = {die * 0.45f, die, die + EnemyAnimator.DEATH_HOLD * 0.5f,
                die + EnemyAnimator.DEATH_HOLD, die + EnemyAnimator.DEATH_HOLD + 0.18f,
                die + EnemyAnimator.DEATH_HOLD + 0.38f, die + EnemyAnimator.DEATH_HOLD + 0.52f};

            for (int col = 0; col < stamps.length; col++) {
                float target = stamps[col];
                EnemyAnimator anim = new EnemyAnimator(id, d, tex);
                // 用 1/60 固定步长推进到目标时刻（和局内同样的推进方式）
                float t = 0f;
                anim.update(1f / 60f, false, 1f, 0f, false);  // 先切到 DIE
                while (t < target) {
                    anim.update(1f / 60f, false, 1f, 0f, false);
                    t += 1f / 60f;
                }
                int cx = col * CELL_W;
                int cy = HEAD + row * CELL_H;
                noStroke();
                fill(col % 2 == 0 ? 34 : 28);
                rect(cx, cy, CELL_W - 2, CELL_H - 2);
                float px = CELL_H * 0.82f / EnemyAnimator.HEIGHT_TILES;
                anim.drawCorpse(g, cx + CELL_W * 0.5f, cy + CELL_H - 18f, px * 0.62f);
                fill(255, 214, 120);
                textSize(10);
                text(String.format(java.util.Locale.ROOT, "t=%.2fs a=%.2f", target, anim.alpha()),
                        cx + 4, cy + 4);
                if (col == 0) {
                    fill(255, 255, 255);
                    textSize(11);
                    text(id, cx + 4, cy + CELL_H - 16);
                }
            }
        }
        if (frameCount++ >= 1) {
            saveFrame(outPath);
            System.out.println("[DeathSheet] 已保存 " + outPath);
            exit();
        }
    }
}
