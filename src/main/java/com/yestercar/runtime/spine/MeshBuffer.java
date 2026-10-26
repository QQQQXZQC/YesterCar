package com.yestercar.runtime.spine;

/**
 * 可复用的网格输出缓冲。
 *
 * <p>一个实例对应「一次 {@link SpinePose#fill(MeshBuffer)} 调用的产物」：顶点已经过骨骼蒙皮，
 * 三角形已经按绘制顺序拼好。渲染层只需要按 {@link #vertexCount()} / {@link #triCount()}
 * 直接喂给 P2D 的 {@code beginShape(TRIANGLES)} 即可，<b>不需要再做任何坐标或 UV 变换</b>。
 *
 * <p>设计要点：
 * <ul>
 *   <li>三个数组都按「已用长度」语义使用（{@link #xy()} 的长度是容量，不是有效数据长度），
 *       所以每次都要用 {@link #vertexCount()} / {@link #triCount()} 截断；</li>
 *   <li>容量只增不减：这是给每个场上单位各自持有一个实例、逐帧 {@code clear()} 后复用的，
 *       避免每帧 new 出几百个 float[]（一个干员一帧动辄 3000+ 顶点）；</li>
 *   <li>{@link #ensure(int, int)} 允许渲染层提前按 {@link SpineData#maxVertices()} 预分配。</li>
 * </ul>
 *
 * <p>颜色一律是 0..1 的直通（straight）alpha：贴图本身是直通 alpha，<b>不要在这里预乘</b>，
 * 否则会得到一圈发黑的描边。
 */
public final class MeshBuffer {

    private float[] xy = new float[0];
    private float[] uv = new float[0];
    private float[] rgba = new float[0];
    private int[] tris = new int[0];

    private int vertexCount;
    private int triCount;

    /** 清空计数但保留容量。 */
    public void clear() {
        vertexCount = 0;
        triCount = 0;
    }

    /** 当前有效顶点数。 */
    public int vertexCount() {
        return vertexCount;
    }

    /** 当前有效三角形数。 */
    public int triCount() {
        return triCount;
    }

    /** 模型坐标（未缩放，1 单位 = setup 姿态下的像素）。长度 2*vertexCount 为有效区。 */
    public float[] xy() {
        return xy;
    }

    /** 归一化贴图坐标，0..1，原点在贴图左上角。长度 2*vertexCount 为有效区。 */
    public float[] uv() {
        return uv;
    }

    /** 0..1 的 RGBA，已乘好 slot×attachment 颜色。长度 4*vertexCount 为有效区。 */
    public float[] rgba() {
        return rgba;
    }

    /** 顶点索引，长度 3*triCount 为有效区。 */
    public int[] tris() {
        return tris;
    }

    /**
     * 保证至少能容纳指定数量的顶点与三角形。
     *
     * @param vertices 需要的顶点数
     * @param triCount 需要的三角形数
     */
    public void ensure(int vertices, int triCount) {
        if (vertices < 0 || triCount < 0) {
            throw new IllegalArgumentException("vertices/triCount 不能为负: " + vertices + ", " + triCount);
        }
        if (xy.length < vertices * 2) {
            xy = new float[vertices * 2];
            uv = new float[vertices * 2];
            rgba = new float[vertices * 4];
        }
        if (tris.length < triCount * 3) {
            tris = new int[triCount * 3];
        }
    }

    /** 包内使用：追加一个顶点，返回它的索引。 */
    int addVertex(float x, float y, float u, float v, float r, float g, float b, float a) {
        int n = vertexCount;
        if ((n + 1) * 2 > xy.length) {
            growVertices(n + 1);
        }
        int i2 = n * 2;
        xy[i2] = x;
        xy[i2 + 1] = y;
        uv[i2] = u;
        uv[i2 + 1] = v;
        int i4 = n * 4;
        rgba[i4] = r;
        rgba[i4 + 1] = g;
        rgba[i4 + 2] = b;
        rgba[i4 + 3] = a;
        vertexCount = n + 1;
        return n;
    }

    /** 包内使用：追加一个三角形。 */
    void addTriangle(int a, int b, int c) {
        int n = triCount;
        if ((n + 1) * 3 > tris.length) {
            int[] bigger = new int[Math.max(3, tris.length * 2 + 3)];
            System.arraycopy(tris, 0, bigger, 0, n * 3);
            tris = bigger;
        }
        int i3 = n * 3;
        tris[i3] = a;
        tris[i3 + 1] = b;
        tris[i3 + 2] = c;
        triCount = n + 1;
    }

    private void growVertices(int vertices) {
        int cap = Math.max(16, xy.length / 2);
        while (cap < vertices) {
            cap *= 2;
        }
        float[] nxy = new float[cap * 2];
        float[] nuv = new float[cap * 2];
        float[] nrgba = new float[cap * 4];
        System.arraycopy(xy, 0, nxy, 0, vertexCount * 2);
        System.arraycopy(uv, 0, nuv, 0, vertexCount * 2);
        System.arraycopy(rgba, 0, nrgba, 0, vertexCount * 4);
        xy = nxy;
        uv = nuv;
        rgba = nrgba;
    }
}
