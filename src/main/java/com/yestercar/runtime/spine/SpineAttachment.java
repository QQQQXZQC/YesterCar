package com.yestercar.runtime.spine;

/**
 * 一个附件（attachment）。本项目只需要「能画的」那一类，判定框/路径/裁剪框只做占位。
 *
 * <p>为了让渲染路径只有一条，<b>矩形贴图附件在加载期就被折叠成等价的 4 顶点网格</b>
 * （局部坐标 = 官方 {@code RegionAttachment.offset[]}，UV 直接由贴图矩形算出）。
 * 于是 {@link SpinePose#fill(MeshBuffer)} 只处理一种东西：顶点 + UV + 三角形 + 骨骼权重。
 *
 * <h2>顶点数据的两种形态（来自 {@code readVertices} 反汇编）</h2>
 * <ul>
 *   <li><b>无权重</b>：{@code localXy} 是 2*vertexCount 个局部坐标，
 *       整体挂在插槽骨骼上（{@code x = lx*boneA + ly*boneB + boneWorldX}）；</li>
 *   <li><b>有权重</b>（{@code hullLength > 0} 且文件里有权重标记）：{@code bones} 是
 *       {@code [骨骼数, 骨骼下标..., 骨骼数, 骨骼下标...]}，{@code weights} 是每根骨骼
 *       3 个 float {@code (x, y, w)}，世界坐标为
 *       {@code sum(((x*boneA + y*boneB) + boneWorldX) * w)}。
 *       ——顺序就是这三个 float 的含义，见 {@code MeshAttachment.updateWorldVertices} 反汇编。</li>
 * </ul>
 *
 * <h2>deform 数组长度（{@code DeformTimeline} 用）</h2>
 * 无权重时 deform 与 {@code localXy} 等长（2*vertexCount，且含基准值）；
 * 有权重时 deform 长度 = {@code weights.length / 3 * 2}（每根骨骼两个偏移量）。
 */
final class SpineAttachment {

    static final int REGION = 0;
    static final int MESH = 1;
    static final int BOUNDING_BOX = 2;
    static final int PATH = 3;
    static final int POINT = 4;
    static final int CLIPPING = 5;

    final int type;
    final String name;
    /** 贴图区域名（可能和 name 不同）。 */
    final String path;

    /** 顶点数（矩形附件折叠后是 4）。 */
    final int vertexCount;

    /** 无权重的局部坐标（2*vertexCount）；有权重时为 {@code null}。 */
    final float[] localXy;
    /** 有权重的 (x, y, w) 三元组；无权重时为 {@code null}。 */
    final float[] weights;
    /** 有权重的骨骼下标表 {@code [count, idx...]}；无权重时为 {@code null}。 */
    final int[] bones;

    /** 贴图 UV（2*vertexCount），已换算成整页 0..1。 */
    final float[] uv;
    /** 顶点索引（3*triCount）。 */
    final short[] tris;

    /** 附件颜色（0..1）。 */
    final float r;
    final float g;
    final float b;
    final float a;

    /** deform 时间轴的数组长度（见类注释）。 */
    final int deformLength;

    /** 贴图区域；找不到为 {@code null}（此时 UV 兜底成区域归一化坐标，并由上层记警告）。 */
    final SpineAtlas.Region region;

    private SpineAttachment(int type, String name, String path, int vertexCount, float[] localXy,
            float[] weights, int[] bones, float[] uv, short[] tris,
            float r, float g, float b, float a, SpineAtlas.Region region) {
        this.type = type;
        this.name = name;
        this.path = path;
        this.vertexCount = vertexCount;
        this.localXy = localXy;
        this.weights = weights;
        this.bones = bones;
        this.uv = uv;
        this.tris = tris;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        this.region = region;
        this.deformLength = weights != null ? weights.length / 3 * 2 : (localXy != null ? localXy.length : 0);
    }

    /** 能不能画（判定框/路径/裁剪框不能画）。 */
    boolean renderable() {
        return tris != null && tris.length >= 3 && vertexCount > 0;
    }

    /** 不是网格的附件（判定框等）：只用于统计。 */
    static SpineAttachment placeholder(int type, String name, String path, int vertexCount,
            float r, float g, float b, float a) {
        return new SpineAttachment(type, name, path, vertexCount, null, null, null, null, null, r, g, b, a, null);
    }

    /**
     * 把矩形贴图附件折叠成 4 顶点网格。
     *
     * <p>局部顶点完全照官方 {@code RegionAttachment.updateOffset} 的公式算：
     * 中心在 (x, y)，先取 ±width/2、±height/2，再按贴图集的 {@code offset}/{@code orig}
     * 修正「被切掉的透明边」（这是贴图打包留白时位置会跑偏的原因），然后乘 scale、转 rotation。
     *
     * <p>UV 的 4 个角与顶点的配对也照官方 {@code RegionAttachment.setRegion}：
     * <pre>
     * 顶点 0 (localX, localY)   → (u2, v2)  [非旋转] / (u2, v)  [旋转 90°]
     * 顶点 1 (localX, localY2)  → (u,  v2)  [非旋转] / (u2, v2) [旋转 90°]
     * 顶点 2 (localX2, localY2) → (u,  v)   [非旋转] / (u,  v2) [旋转 90°]
     * 顶点 3 (localX2, localY)  → (u2, v)   [非旋转] / (u,  v)  [旋转 90°]
     * </pre>
     * 旋转分支就是官方那段「4 个角整体错一位」的赋值，错位一位 = 90°。
     */
    static SpineAttachment region(String name, String path, float rotation, float x, float y,
            float scaleX, float scaleY, float width, float height,
            float r, float g, float b, float a, SpineAtlas.Region region) {
        float localX2 = width / 2f;
        float localY2 = height / 2f;
        float localX = -localX2;
        float localY = -localY2;
        if (region != null) {
            localX += region.offsetX() / (float) region.origW() * width;
            localY += region.offsetY() / (float) region.origH() * height;
            if (region.degrees() == 90 || region.degrees() == 270) {
                // 宽高在贴图里被互换：修正量也要互换着减
                localX2 -= (region.origW() - region.offsetX() - region.packedHeight())
                        / (float) region.origW() * width;
                localY2 -= (region.origH() - region.offsetY() - region.packedWidth())
                        / (float) region.origH() * height;
            } else {
                localX2 -= (region.origW() - region.offsetX() - region.packedWidth())
                        / (float) region.origW() * width;
                localY2 -= (region.origH() - region.offsetY() - region.packedHeight())
                        / (float) region.origH() * height;
            }
        }
        localX *= scaleX;
        localY *= scaleY;
        localX2 *= scaleX;
        localY2 *= scaleY;

        double rad = Math.toRadians(rotation);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float localXCos = localX * cos + x;
        float localXSin = localX * sin;
        float localYCos = localY * cos + y;
        float localYSin = localY * sin;
        float localX2Cos = localX2 * cos + x;
        float localX2Sin = localX2 * sin;
        float localY2Cos = localY2 * cos + y;
        float localY2Sin = localY2 * sin;

        float[] xy = new float[8];
        xy[0] = localXCos - localYSin;
        xy[1] = localYCos + localXSin;
        xy[2] = localXCos - localY2Sin;
        xy[3] = localY2Cos + localXSin;
        xy[4] = localX2Cos - localY2Sin;
        xy[5] = localY2Cos + localX2Sin;
        xy[6] = localX2Cos - localYSin;
        xy[7] = localYCos + localX2Sin;

        float[] uvs = new float[8];
        if (region != null) {
            // 四个角按"图像局部坐标"给 UV：lu 0=左 1=右，lv 0=上 1=下。
            // 角 0/1/2/3 分别对应模型空间的下左/上左/上右/下右。
            float[][] corners = {{0f, 1f}, {0f, 0f}, {1f, 0f}, {1f, 1f}};
            float[] tmpUv = new float[2];
            for (int c = 0; c < 4; c++) {
                region.toUv(corners[c][0], corners[c][1], tmpUv);
                uvs[c * 2] = tmpUv[0];
                uvs[c * 2 + 1] = tmpUv[1];
            }
        }
        short[] tris = {0, 1, 2, 2, 3, 0};
        return new SpineAttachment(REGION, name, path, 4, xy, null, null, uvs, tris,
                r, g, b, a, region);
    }

    /**
     * 网格附件。
     *
     * @param regionUVs 文件里存的"区域内归一化"UV（2*vertexCount）
     */
    static SpineAttachment mesh(String name, String path, int vertexCount, float[] regionUVs,
            short[] tris, float[] localXy, float[] weights, int[] bones,
            float r, float g, float b, float a, SpineAtlas.Region region) {
        float[] uv = new float[vertexCount * 2];
        if (region != null) {
            // ⚠️ 这里**必须**走 region.toUv 而不是直接线性映射：
            //    这批素材里有大量 rotate: true / 180 / 270 的区域，网格 UV 是"相对区域"的 0–1，
            //    旋转区域的贴图矩形宽高是互换的，直接线性映射会把整块部件采样到错误位置 ——
            //    表现就是"轮廓是对的、内容全花"（texas 的脸、头发、护手全部错位）。
            //    之前只有矩形附件走了 toUv，网格漏了，这就是整体变形的主因。
            float[] tmp = new float[2];
            for (int i = 0; i < vertexCount * 2; i += 2) {
                meshUv(region, regionUVs[i], regionUVs[i + 1], tmp);
                uv[i] = tmp[0];
                uv[i + 1] = tmp[1];
            }
        } else {
            System.arraycopy(regionUVs, 0, uv, 0, Math.min(uv.length, regionUVs.length));
        }
        return new SpineAttachment(MESH, name, path, vertexCount, localXy, weights, bones, uv, tris,
                r, g, b, a, region);
    }

    /** 网格 UV 映射。
     *
     * <p>⚠️ <b>这一处目前仍未与官方运行时对齐</b>（详见 docs/骨骼动画接入.md §7）：
     * 官方 {@code MeshAttachment.updateUVs} 是
     * {@code u = region.u + (region.u2-region.u)*u0}，对旋转区域依赖 libgdx
     * {@code TextureAtlas.AtlasRegion} 的 u/v/u2/v2 约定；本实现没有 libgdx，
     * 只能按自己的 {@link SpineAtlas.Region} 复算，旋转区域的映射仍在验证中。
     * 已确认：骨骼世界变换、网格顶点几何、drawOrder 都与官方逐项吻合，
     * 偏差集中在"贴图取哪儿"，所以表现为"轮廓对、内容花"。
     *
     * @param region 贴图区域
     * @param u      区域内横坐标 0..1
     * @param v      区域内纵坐标 0..1
     * @param out    输出
     */
    static void meshUv(SpineAtlas.Region region, float u, float v, float[] out) {
        if (region == null) {
            out[0] = u;
            out[1] = v;
            return;
        }
        region.toUv(u, v, out);
    }
}
