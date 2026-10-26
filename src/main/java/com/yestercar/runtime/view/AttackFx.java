package com.yestercar.runtime.view;

import processing.core.PConstants;
import processing.core.PGraphics;

/**
 * 干员**普通攻击特效**（远程弹体：狙击弹道 / 法术弹 / 治疗球）。
 *
 * <p>近战不再画特效（白色扇形已按要求取消），但挥砍打到那一帧才结算伤害的时序保留，
 * 时序数据仍在 {@code data/attack-fx.csv}。
 *
 * <h2>来源</h2>
 * 参数、配色、尺寸全部照 {@code D:\dsHarness\Arknights\Defender} 抄，不是自己拍的：
 * <ul>
 *   <li>{@code server/sim/professions.js}：狙击 {@code projectile:'arrow'}、
 *       术师/辅助 {@code 'bolt'}、医疗 {@code 'orb'}、近卫/重装/先锋 {@code 'none'}；</li>
 *   <li>{@code public/js/render/style.js} 的 {@code PROJ}：
 *       {@code arrow}（tracer、14 格/秒、暖白 + 琥珀光）、{@code bolt}（orb、11 格/秒、白紫 + 紫光）、
 *       {@code orb}（orb、10 格/秒、绿）；</li>
 *   <li>{@code public/js/render/fx.js} 的 {@code _stepShot / _muzzle / _impact} 与
 *       （近战不画特效，这里只保留远程弹体那几条）。</li>
 * </ul>
 *
 * <h2>为什么用"程序化图形"而不是贴图</h2>
 * Defender 的光晕/光球/拖尾是运行时生成纹理再加法混合；本工程是 P2D，没有那套纹理管线，
 * 所以用**同心半透明圆 + ADD 混合**等效成径向光斑。
 *
 * <p>单位统一：位置和尺寸都写"格"，画之前乘 {@code Camera.project} 给出的每格像素数。
 */
public final class AttackFx {

    /** 弹体胸口高度（格）：与干员/敌人的绘制尺度对齐。 */
    private static final float CHEST = 0.55f;

    /** 命中后弹体淡出时长（秒），{@code fx.js SHOT_FADE}。 */
    private static final float SHOT_FADE = 0.08f;

    /** 拖尾粒子发射率（个/秒），{@code fx.js TRAIL_HZ}。 */
    private static final float TRAIL_HZ = 36f;

    /**
     * 一种弹体的观感（对应 Defender {@code PROJ} 的一行）。
     *
     * @param look   0 = 光球（orb）；1 = 弹道（tracer）
     * @param hit    0 = arts（光晕 + 冲击环 + 火星）；1 = spark（闪光 + 火星）；2 = heal（绿色光晕）
     * @param core   核心色
     * @param glow   光晕色
     * @param trail  拖尾色
     * @param muzzle 枪口闪光色
     * @param head   头部光球直径（格）
     * @param len    拖尾长度（格）
     * @param width  拖尾/弹道粗细（格）
     * @param speed  飞行速度（格/秒）
     */
    private record Shot(int look, int hit, int core, int glow, int trail, int muzzle,
                        float head, float len, float width, float speed) {
    }

    /** PROJ.bolt：术师 / 辅助。 */
    private static final Shot BOLT =
            new Shot(0, 0, 0xF4E2FF, 0xB36BFF, 0xA35CFF, 0xC77DFF, 0.52f, 0.6f, 0.34f, 11f);

    /** PROJ.arrow：狙击（子弹拖出的细长弹道 + 琥珀色枪口）。 */
    private static final Shot ARROW =
            new Shot(1, 1, 0xFFF6DC, 0xFFC45A, 0xFFD27A, 0xFFD27A, 0.36f, 1.05f, 0.20f, 14f);

    /** PROJ.enemy：敌方远程法术弹（红色光球，speed 10）。 */
    private static final Shot ENEMY =
            new Shot(0, 1, 0xFFE2DA, 0xFF3B30, 0xFF4A3A, 0xFF6A5A, 0.44f, 0.55f, 0.28f, 10f);

    /** PROJ.orb：医疗（绿色治疗球）。 */
    private static final Shot HEAL =
            new Shot(0, 2, 0xEAFFF0, 0x3FE07A, 0x62F08A, 0x62F08A, 0.48f, 0.55f, 0.30f, 10f);

    /** 单发弹体。 */
    private static final class Bolt {
        Shot spec;
        float x0;
        float y0;
        float x1;
        float y1;
        float t;
        float dur;
        boolean hit;
        float fade;
        float emit;
        float ang;
    }

    /** 一个粒子（光晕 / 冲击环 / 火星 / 月牙）。 */
    private static final class Part {
        float x;
        float y;
        float vx;
        float vy;
        float age;
        float life;
        float r0;
        float r1;
        float a0;
        int color;
        int kind;          // 0 = 实心光晕；1 = 空心环；2 = 火星；3 = 月牙
    }

    private final java.util.List<Bolt> bolts = new java.util.ArrayList<>();
    private final java.util.List<Part> parts = new java.util.ArrayList<>();

    /** 诊断：本帧活着的弹体数。 */
    public int boltCount() {
        return bolts.size();
    }

    /** 诊断：粒子数。 */
    public int particleCount() {
        return parts.size();
    }

    /** @return 当前没有任何弹体/粒子（调用方据此跳过整段绘制） */
    public boolean isEmpty() {
        return bolts.isEmpty() && parts.isEmpty();
    }

    /**
     * 发射一发普攻弹体。{@code kind} 取 {@code data/attack-fx.csv} 里那几种：
     * {@code arrow}（狙击）、{@code bolt}（术师/辅助）、{@code orb}（医疗）、
     * {@code enemy}（敌方远程法术弹）。
     *
     * @param kind 弹体种类
     * @param x0   起点世界 x（格）
     * @param y0   起点世界 y（格）
     * @param x1   终点世界 x（格）
     * @param y1   终点世界 y（格）
     */
    public void spawnShot(String kind, float x0, float y0, float x1, float y1) {
        Shot spec = switch (kind == null ? "" : kind) {
            case "arrow" -> ARROW;
            case "orb" -> HEAL;
            case "enemy" -> ENEMY;
            default -> BOLT;
        };
        spawn(spec, x0, y0, x1, y1);
    }

    /**
     * @param spec 弹体观感
     * @param x0   起点 x
     * @param y0   起点 y
     * @param x1   终点 x
     * @param y1   终点 y
     */
    private void spawn(Shot spec, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1e-4f) {
            return;
        }
        float ux = dx / dist;
        float uy = dy / dist;
        // 手上出弹：起点沿方向前移最多 0.28 格（fx.js: hand = min(0.28, dist*0.3)）
        float hand = Math.min(0.28f, dist * 0.3f);

        Bolt b = new Bolt();
        b.spec = spec;
        b.x0 = x0 + ux * hand;
        b.y0 = y0 + uy * hand;
        b.x1 = x1;
        b.y1 = y1;
        b.dur = Math.max(0.04f, Math.min(1.5f, dist / spec.speed()));
        b.t = 0f;
        b.emit = (float) Math.random();
        b.ang = (float) Math.atan2(uy, ux);
        bolts.add(b);

        // 枪口闪光（muzzle）：一颗快速膨胀并淡出的光晕
        Part m = new Part();
        m.x = b.x0;
        m.y = b.y0;
        m.kind = 0;
        m.color = spec.muzzle();
        m.life = spec.look() == 1 ? 0.10f : 0.16f;
        m.r0 = spec.head() * 0.45f;
        m.r1 = spec.head() * 0.8f;
        m.a0 = 0.9f;
        parts.add(m);
    }

    /**
     * 一圈火星（{@code fx.js burst}）。
     *
     * @param x     世界 x
     * @param y     世界 y
     * @param n     数量
     * @param tint  颜色
     * @param speed 初速（格/秒）
     * @param size  大小（格）
     */
    private void burst(float x, float y, int n, int tint, float speed, float size) {
        for (int i = 0; i < n; i++) {
            double a = Math.random() * Math.PI * 2;
            float sp = speed * (0.6f + 0.4f * (float) Math.random());
            Part s = new Part();
            s.x = x;
            s.y = y;
            s.vx = (float) Math.cos(a) * sp;
            s.vy = (float) Math.sin(a) * sp;
            s.kind = 2;
            s.color = tint;
            s.life = 0.3f;
            s.r0 = size * 0.35f;
            s.r1 = 0f;
            s.a0 = 0.95f;
            parts.add(s);
        }
    }

    /**
     * 推进一帧。
     *
     * @param dt 本帧真实间隔（秒）
     */
    public void update(float dt) {
        if (dt <= 0f) {
            return;
        }
        for (int i = bolts.size() - 1; i >= 0; i--) {
            Bolt b = bolts.get(i);
            b.t += dt;
            if (b.t >= b.dur && !b.hit) {
                b.hit = true;
                b.fade = 0f;
                impact(b);
            }
            if (b.hit) {
                b.fade += dt;
                if (b.fade >= SHOT_FADE) {
                    bolts.remove(i);
                    continue;
                }
            }
            // 拖尾粒子：按 TRAIL_HZ 的节奏在弹头处撒点
            if (!b.hit) {
                b.emit += dt * TRAIL_HZ;
                float k = Math.min(1f, b.t / b.dur);
                float px = b.x0 + (b.x1 - b.x0) * k;
                float py = b.y0 + (b.y1 - b.y0) * k;
                while (b.emit >= 1f) {
                    b.emit -= 1f;
                    Part p = new Part();
                    p.x = px;
                    p.y = py;
                    p.vx = (float) ((Math.random() - 0.5) * 0.6f);
                    p.vy = (float) ((Math.random() - 0.5) * 0.6f);
                    p.kind = 2;
                    p.color = b.spec.trail();
                    p.life = 0.26f;
                    p.r0 = b.spec.width() * 0.34f;
                    p.r1 = 0f;
                    p.a0 = 0.85f;
                    parts.add(p);
                }
            }
        }
        for (int i = parts.size() - 1; i >= 0; i--) {
            Part p = parts.get(i);
            p.age += dt;
            if (p.age >= p.life) {
                parts.remove(i);
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }
    }

    /**
     * 命中爆发（{@code fx.js _impact}）：arts = 光晕 + 冲击环 + 火星；spark = 闪光 + 火星；heal = 绿色光晕。
     *
     * @param b 刚到达的弹体
     */
    private void impact(Bolt b) {
        Shot s = b.spec;
        Part glow = new Part();
        glow.x = b.x1;
        glow.y = b.y1;
        glow.kind = 0;
        glow.color = s.glow();
        glow.life = 0.24f;
        glow.r0 = s.head() * 0.7f;
        glow.r1 = s.head() * 1.2f;
        glow.a0 = 0.95f;
        parts.add(glow);

        if (s.hit() == 0) {
            Part shock = new Part();
            shock.x = b.x1;
            shock.y = b.y1;
            shock.kind = 1;
            shock.color = s.glow();
            shock.life = 0.26f;
            shock.r0 = s.head() * 0.25f;
            shock.r1 = s.head() * 1.05f;
            shock.a0 = 0.9f;
            parts.add(shock);
        }
        burst(b.x1, b.y1, 4, s.trail(), 2.2f, s.head() * 0.7f);
    }

    /**
     * 画到屏幕（调用方负责 pushMatrix / resetMatrix，并且已经关掉深度测试）。
     *
     * @param canvas 画布（{@code g.getGraphics()}）
     * @param camera 相机（世界格 → 屏幕像素）
     */
    public void draw(PGraphics canvas, Camera camera) {
        if (isEmpty()) {
            return;
        }
        float[] p = new float[4];
        float[] q = new float[4];
        canvas.pushStyle();
        canvas.blendMode(PConstants.ADD);      // 加法混合：光晕叠在暗背景上才有"发光"感

        for (Bolt b : bolts) {
            Shot spec = b.spec;
            float k = Math.min(1f, b.t / b.dur);
            float fk = b.hit ? Math.min(1f, b.fade / SHOT_FADE) : 0f;
            camera.project(b.x0 + (b.x1 - b.x0) * k, b.y0 + (b.y1 - b.y0) * k, CHEST, p);
            float s = p[2];
            float pathLen = (float) Math.sqrt(sq(b.x1 - b.x0) + sq(b.y1 - b.y0));
            // 光球外径 = head（PROJ.head）：脉动 ±12%，与 fx.js 的 0.12*sin(t*40) 同款
            float headR = spec.head() * 0.5f * s * (1f + 0.12f * (float) Math.sin(b.t * 40f));

            // ① 拖尾：沿来路撒一串逐渐变细变淡的圆点（等价于 tracer 精灵 + dot 粒子）
            float flown = k * pathLen;
            float len = Math.min(spec.len(), flown) * (1f - fk);
            int steps = 14;
            for (int i = 1; i < steps; i++) {
                float back = len * i / steps;
                if (back <= 0f || pathLen < 1e-4f) {
                    continue;
                }
                float t = k - back / pathLen;
                if (t < 0f) {
                    continue;
                }
                camera.project(b.x0 + (b.x1 - b.x0) * t, b.y0 + (b.y1 - b.y0) * t, CHEST, q);
                float f = 1f - (float) i / steps;
                float r = spec.width() * 0.5f * q[2] * f * (spec.look() == 1 ? 0.5f : 0.75f);
                int a = (int) (120 * f * f * (1f - fk));
                if (a <= 2 || r <= 0.5f) {
                    continue;
                }
                fill(canvas, spec.trail(), a);
                canvas.ellipse(q[0], q[1], r * 2f, r * 2f);
            }

            // ② 光晕 + 核心：多层同心圆伪造径向渐变
            glow(canvas, p[0], p[1], headR, spec.glow(), 0.55f * (1f - fk));
            glow(canvas, p[0], p[1], headR * 0.62f, spec.core(), 0.95f * (1f - fk));
        }

        for (Part pt : parts) {
            float k = pt.age / pt.life;
            camera.project(pt.x, pt.y, CHEST, p);
            float r = (pt.r0 + (pt.r1 - pt.r0) * k) * p[2];
            float a = pt.a0 * (1f - k);
            if (a <= 0.01f || r <= 0.4f) {
                continue;
            }
            if (pt.kind == 1) {
                // 冲击环：空心
                canvas.noFill();
                canvas.strokeWeight(Math.max(1f, p[2] * 0.04f));
                canvas.stroke((pt.color >> 16) & 0xFF, (pt.color >> 8) & 0xFF, pt.color & 0xFF,
                        (int) (a * 255f));
                canvas.ellipse(p[0], p[1], r * 2f, r * 2f);
                canvas.noStroke();
            } else if (pt.kind == 0) {
                glow(canvas, p[0], p[1], r, pt.color, a * 0.9f);
            } else {
                fill(canvas, pt.color, (int) (a * 255f));
                canvas.ellipse(p[0], p[1], r * 2f, r * 2f);
            }
        }

        canvas.popStyle();
    }

    /**
     * 画一个"径向光斑"：由外到内若干层同心圆，alpha 按幂次递增 —— 在暗背景 + 加法混合下
     * 观感就是一团柔和的发光，等价于 Defender 那张运行时生成的 glow/orb 贴图。
     *
     * @param canvas    画布
     * @param x         屏幕 x
     * @param y         屏幕 y
     * @param rOuterPx  最外层半径（像素）
     * @param rgb       0xRRGGBB
     * @param peakAlpha 中心最亮处的不透明度（0–1）
     */
    private static void glow(PGraphics canvas, float x, float y, float rOuterPx, int rgb,
                             float peakAlpha) {
        if (rOuterPx <= 0.5f || peakAlpha <= 0.01f) {
            return;
        }
        final int layers = 6;
        for (int i = 0; i < layers; i++) {
            float t = (i + 1) / (float) layers;          // 0→1：由外到内
            float r = rOuterPx * (1f - 0.80f * t);
            float a = peakAlpha * (float) Math.pow(t, 1.7) * 255f;
            fill(canvas, rgb, (int) a);
            canvas.ellipse(x, y, r * 2f, r * 2f);
        }
    }

    /**
     * @param canvas 画布
     * @param rgb    0xRRGGBB
     * @param a      0–255
     */
    private static void fill(PGraphics canvas, int rgb, int a) {
        canvas.noStroke();
        canvas.fill((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, Math.max(0, Math.min(255, a)));
    }

    /**
     * @param v 值
     * @return 平方
     */
    private static float sq(float v) {
        return v * v;
    }

    /** 清空（切关卡/重开时调）。 */
    public void clear() {
        bolts.clear();
        parts.clear();
    }
}
