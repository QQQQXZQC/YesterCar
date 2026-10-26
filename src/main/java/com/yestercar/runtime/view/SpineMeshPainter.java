package com.yestercar.runtime.view;

import com.yestercar.runtime.spine.MeshBuffer;
import processing.core.PConstants;
import processing.core.PGraphics;
import processing.core.PImage;

/**
 * 把 {@link MeshBuffer} 里的三角形**用 P2D 画成贴图网格**。
 *
 * <h2>为什么是"贴着画三角形"而不是 image()</h2>
 * 方舟的干员是 Spine 的**网格附件**（MeshAttachment）拼的：每个部件都是一张
 * 被骨骼变形过的三角网格。所以只能用
 * {@code beginShape(TRIANGLES) + texture(图集) + vertex(x, y, u, v)} 逐三角形画，
 * 每个顶点带自己的 UV 与颜色（受击染色就靠逐顶点颜色做，不需要额外的 shader）。
 *
 * <h2>两个实测出来的约定</h2>
 * <ul>
 *   <li><b>UV 用像素坐标</b>：Processing 的 {@code textureMode} 默认是 {@code IMAGE}，
 *       而 4.5.6 的 {@code PConstants} 里**没有** {@code NORMALIZED} 常量（javap 查过），
 *       所以这里把 0–1 的 UV 乘上贴图宽高再喂给 {@code vertex()}。</li>
 *   <li><b>染色 / 淡出只能靠 {@code tint()}，不能靠逐顶点 {@code fill()}</b>：
 *       4.5.6 的 P2D 在 {@code texture()} + {@code vertex(x,y,u,v)} 这条路上
 *       <b>完全忽略顶点色</b>（实测：同一姿态分别用 {@code fill(255,80,80)} 和
 *       {@code fill(255)} 画两遍，逐像素差 0）。所以受击闪红与尸体淡出改由
 *       {@code tint(r,g,b,a)} 表达 —— 它同样作用在这条 textured triangle 路径上
 *       （实测 alpha=100 时确实半透明）。</li>
 * </ul>
 *
 * <h2>⚠️ 必须显式把 textureMode 设回 IMAGE</h2>
 * {@code BattleGridRenderer} 为了画地形把全局 {@code textureMode} 改成了归一化语义
 * （{@code applet.textureMode(PConstants.NORMAL)}，见该类第 201 行）。
 * 那是**画布级的状态**，不会自己恢复 —— 于是下一帧我这边按像素给的 UV（0–512）
 * 全被夹到边缘同一个纹素，整只干员渲染成一片深色剪影（实测截图确认，
 * 回读像素是单一颜色而不是贴图内容）。所以这里进入前存、退出后还原。
 *
 * <p>颜色变化时才调 {@code fill()}：一帧几千个顶点如果每点都调，是纯浪费。
 */
public final class SpineMeshPainter {

    private SpineMeshPainter() {
        // 工具类
    }

    /**
     * 画一个网格到屏幕坐标。
     *
     * <p><b>锚点是模型原点 {@code (0,0)}</b>，不是包围盒下沿 ——
     * 方舟骨骼的原点就在脚底，而"包围盒下沿"会被没实现的约束甩出去的游离顶点拖走
     * （实测 blaze 的包围盒 minY 是 -521，按它对齐整个人会浮在半空）。
     *
     * @param g      画布
     * @param tex    图集贴图
     * @param mesh   已装配好的网格
     * @param sx     脚底（模型原点）屏幕 x
     * @param sy     脚底（模型原点）屏幕 y
     * @param scale  模型单位 → 屏幕像素
     * @param flip   {@code -1} 水平镜像（朝左），否则 {@code 1}
     * @param tint   受击染色强度 0–1（0 = 原色）
     * @param alpha  整体不透明度 0–1（1 = 原样；尸体淡出用）
     * @param tintR  染色 R
     * @param tintG  染色 G
     * @param tintB  染色 B
     */
    public static void draw(PGraphics g, PImage tex, MeshBuffer mesh,
                            float sx, float sy, float scale, float flip, float tint,
                            float alpha, float tintR, float tintG, float tintB) {
        if (tex == null || mesh.vertexCount() <= 0 || mesh.triCount() <= 0) {
            return;
        }
        float[] xy = mesh.xy();
        float[] uv = mesh.uv();
        float[] rgba = mesh.rgba();
        int[] idx = mesh.tris();
        int tw = tex.width;
        int th = tex.height;

        g.pushStyle();
        g.noStroke();
        // 染色 / 整体透明度走 tint()：P2D 的 textured triangle 会忽略逐顶点 fill，
        // 但会乘上 tint。tint 是有状态的，由 pushStyle/popStyle 负责还原。
        if (tint > 0f || alpha < 1f) {
            int tr = clamp255((1f - tint) + tintR * tint);
            int tg = clamp255((1f - tint) + tintG * tint);
            int tb = clamp255((1f - tint) + tintB * tint);
            g.tint(tr, tg, tb, clamp255(alpha));
        } else {
            g.noTint();
        }
        // 地形渲染把 textureMode 改成了归一化（画布级状态，不会自动恢复）。
        // 这里直接改字段而不是调 textureMode(...)：4.5.6 的 PConstants 没有 NORMALIZED，
        // 调方法还得用一个"合法"的常量名去骗它，不如存/还原字段干净。
        int previousTextureMode = g.textureMode;
        g.textureMode = PConstants.IMAGE;
        g.beginShape(PConstants.TRIANGLES);
        g.texture(tex);

        int triN = mesh.triCount();
        for (int t = 0; t < triN; t++) {
            int v0 = idx[t * 3];
            int v1 = idx[t * 3 + 1];
            int v2 = idx[t * 3 + 2];
            // 整块透明的三角形直接丢掉。方舟把"当前不用的部件"（另一只手、另一套表情、
            // 武器变体）都留在插槽里，靠 slot alpha = 0 隐藏；逐顶点 alpha 在这条
            // texture+vertex 路径上并不可靠（实测会照原样画出来，头顶就多出一堆零件），
            // 所以这里自己判掉。
            if (alphaOf(rgba, v0) * alpha < 0.01f
                    && alphaOf(rgba, v1) * alpha < 0.01f
                    && alphaOf(rgba, v2) * alpha < 0.01f) {
                continue;
            }
            for (int k = 0; k < 3; k++) {
                int v = idx[t * 3 + k];
                float wx = sx + flip * xy[v * 2] * scale;
                float wy = sy - xy[v * 2 + 1] * scale;
                float u = uv[v * 2] * tw;
                float vv = uv[v * 2 + 1] * th;
                g.vertex(wx, wy, u, vv);
            }
        }
        g.endShape();
        g.textureMode = previousTextureMode;
        g.popStyle();
    }

    /**
     * @param v 0–1 分量
     * @return 0–255 整数
     */
    private static int clamp255(float v) {
        int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    /** @param rgba 顶点色数组 @param v 顶点下标 @return 该顶点的 alpha */
    private static float alphaOf(float[] rgba, int v) {
        return rgba[v * 4 + 3];
    }
}
