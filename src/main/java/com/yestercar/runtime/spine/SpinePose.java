package com.yestercar.runtime.spine;

/**
 * 一个场上单位的可变姿态：当前动画 + 时间 + 采样结果。
 *
 * <p>典型用法（每帧）：
 * <pre>
 * pose.update(dt);
 * pose.fill(buffer);
 * // 然后 buffer 里的 xy/uv/rgba/tris 直接喂 P2D
 * </pre>
 *
 * <h2>为什么每个单位一份</h2>
 * 采样要改一大堆中间状态（每根骨骼的局部值、世界矩阵、每个插槽的当前附件/颜色/形变）。
 * 这些状态全部放在本类里，{@link SpineData} 保持只读，于是"同一个干员的多个单位"共享
 * 解析结果、各自独立播放，互不影响。
 *
 * <h2>骨骼世界变换</h2>
 * 照官方 {@code Skeleton.updateWorldTransform} / {@code Bone.updateWorldTransformWith}：
 * <pre>
 * parent != null 时：
 *   worldX = pa*x + pb*y + parent.worldX
 *   worldY = pc*x + pd*y + parent.worldY
 *   rotationY = rotation + 90 + shearY
 *   la = cos(rotation+shearX)*scaleX   lb = cos(rotationY)*scaleY
 *   lc = sin(rotation+shearX)*scaleX   ld = sin(rotationY)*scaleY
 *   // normal 模式：
 *   a = pa*la + pb*lc    b = pa*lb + pb*ld
 *   c = pc*la + pd*lc    d = pc*lb + pd*ld
 * </pre>
 * {@code onlyTranslation}（不继承父级旋转/缩放）、{@code noRotationOrReflection}、
 * {@code noScale}、{@code noScaleOrReflection} 也都照官方 {@code Bone.updateWorldTransformWith}
 * 的分支实现了（实测 amiya 有 60 根 noScale 骨、ines 有 16 根 noRotationOrReflection 骨）。
 *
 * <p>没有实现的是 <b>IK / transform / path 约束</b>：那是在骨骼世界变换之后再做的一轮修正，
 * 本类完全不参与。量化影响见 {@link SpineData} 的诚实清单（带动画时包围盒最多差十几单位）。
 */
public final class SpinePose {

    /** 所属模型数据（包内可见：时间轴要读 setup 值）。 */
    final SpineData data;

    /** 每根骨骼的局部值。 */
    final float[] boneRotation;
    final float[] boneX;
    final float[] boneY;
    final float[] boneScaleX;
    final float[] boneScaleY;
    final float[] boneShearX;
    final float[] boneShearY;

    /** 每根骨骼的世界变换矩阵 (a,b,c,d) 与位置。 */
    private final float[] worldX;
    private final float[] worldY;
    private final float[] mtxA;
    private final float[] mtxB;
    private final float[] mtxC;
    private final float[] mtxD;

    /** 每个插槽当前挂的附件。 */
    final SpineAttachment[] slotAttachment;
    /** 每个插槽当前颜色：[通道][插槽]。 */
    final float[][] slotColor;
    /** 每个插槽当前的 deform 数组（null = 用附件基准顶点）。 */
    final float[][] slotDeform;
    /** deform 插值用的复用缓冲。 */
    private final float[][] deformScratch;
    /** 当前绘制顺序（插槽下标的排列）。 */
    private final int[] drawOrder;
    private final int[] setupDrawOrder;

    /**
     * 每个 IK 约束的运行期 {@code mix} / {@code bendDirection}。
     *
     * <p>必须是"每份姿态一份"而不是放在 {@link SpineData} 里：约束强度本身是动画数据
     * （{@link SpineTimeline.Ik}），同一个干员的多个单位可能播不同动画。
     */
    final float[] ikMix;
    final int[] ikBendDir;

    /** 每个 transform 约束的 4 个通道 mix（顺序：rotate / translate / scale / shear）。 */
    final float[] tcMix;

    private SpineAnimation animation;
    private float time;
    private boolean loop;
    private boolean finished;

    public SpinePose(SpineData data) {
        if (data == null) {
            throw new IllegalArgumentException("data 不能为 null");
        }
        this.data = data;
        int boneCount = data.boneCount();
        int slotCount = data.slotCount();
        boneRotation = new float[boneCount];
        boneX = new float[boneCount];
        boneY = new float[boneCount];
        boneScaleX = new float[boneCount];
        boneScaleY = new float[boneCount];
        boneShearX = new float[boneCount];
        boneShearY = new float[boneCount];
        worldX = new float[boneCount];
        worldY = new float[boneCount];
        mtxA = new float[boneCount];
        mtxB = new float[boneCount];
        mtxC = new float[boneCount];
        mtxD = new float[boneCount];
        slotAttachment = new SpineAttachment[slotCount];
        slotColor = new float[][] {new float[slotCount], new float[slotCount],
                new float[slotCount], new float[slotCount]};
        slotDeform = new float[slotCount][];
        deformScratch = new float[slotCount][];
        drawOrder = new int[slotCount];
        setupDrawOrder = new int[slotCount];
        for (int i = 0; i < slotCount; i++) {
            setupDrawOrder[i] = i;
        }
        ikMix = new float[data.ik().length];
        ikBendDir = new int[data.ik().length];
        tcMix = new float[data.transforms().length * 4];
        resetToSetup();
    }

    // ------------------------------------------------------------------
    // 播放控制
    // ------------------------------------------------------------------

    /**
     * 硬切换动画（不做混合）。
     *
     * @param anim 动画名
     * @param loop 是否循环
     * @return 动画存在返回 true；不存在返回 false 且<b>姿态完全不变</b>
     */
    public boolean play(String anim, boolean loop) {
        SpineAnimation found = data.animation(anim);
        if (found == null) {
            return false;
        }
        this.animation = found;
        this.time = 0f;
        this.loop = loop;
        this.finished = false;
        return true;
    }

    /**
     * 推进时间。
     *
     * @param dt 秒；负数按 0 处理
     */
    public void update(float dt) {
        if (animation == null || dt <= 0f) {
            return;
        }
        if (loop) {
            float duration = animation.duration;
            if (duration <= 0f) {
                time = 0f;
                return;
            }
            time += dt;
            if (time >= duration) {
                time -= duration * (float) Math.floor(time / duration);
                if (time >= duration) {
                    time = 0f;
                }
            }
        } else {
            time += dt;
            if (time >= animation.duration) {
                time = animation.duration;
                finished = true;
            }
        }
    }

    /**
     * 定位到指定时刻。
     *
     * @param seconds 秒；循环动画取模到 [0,duration)，非循环夹到 [0,duration]
     */
    public void setTime(float seconds) {
        if (animation == null) {
            this.time = Math.max(0f, seconds);
            return;
        }
        float duration = animation.duration;
        float t = Math.max(0f, seconds);
        if (loop) {
            if (duration > 0f) {
                t = t % duration;
            } else {
                t = 0f;
            }
        } else if (t > duration) {
            t = duration;
        }
        this.time = t;
        this.finished = !loop && duration > 0f && t >= duration;
    }

    /** 当前动画时刻（秒）。 */
    public float time() {
        return time;
    }

    /** 当前动画名；没有则为 null。 */
    public String current() {
        return animation == null ? null : animation.name;
    }

    /** 非循环动画是否已播完。 */
    public boolean finished() {
        return finished;
    }

    // ------------------------------------------------------------------
    // 采样 + 装配
    // ------------------------------------------------------------------

    /**
     * 采样当前时刻，蒙皮并把三角形写进 {@code out}（先清空）。
     *
     * <p>输出顺序严格按<b>插槽顺序</b>（有 drawOrder 时间轴时按它给出的顺序），
     * 渲染端只要顺序画就是正确的 painter's algorithm。
     *
     * @param out 输出缓冲（会被 clear 后写满）
     */
    public void fill(MeshBuffer out) {
        if (out == null) {
            throw new IllegalArgumentException("out 不能为 null");
        }
        out.clear();
        out.ensure(data.maxVertices(), data.maxTriangles());
        resetToSetup();
        if (animation != null) {
            for (SpineTimeline timeline : animation.timelines) {
                timeline.apply(this, time);
            }
        }
        updateWorldTransforms();
        // 约束必须在世界变换之后解：方舟的腿/脚/武器都是约束驱动的，
        // 不解就会出现"setup 对、动画一跑腿变长、剑柄和刀刃分开"。
        for (SpineConstraints.Ik c : data.ik()) {
            if (c != null) {
                c.apply(this);
            }
        }
        for (SpineConstraints.Transform c : data.transforms()) {
            if (c != null) {
                c.apply(this);
            }
        }
        emit(out);
    }

    private void resetToSetup() {
        int boneCount = data.boneCount();
        for (int i = 0; i < boneCount; i++) {
            SpineBone bone = data.bones[i];
            boneRotation[i] = bone.rotation;
            boneX[i] = bone.x;
            boneY[i] = bone.y;
            boneScaleX[i] = bone.scaleX;
            boneScaleY[i] = bone.scaleY;
            boneShearX[i] = bone.shearX;
            boneShearY[i] = bone.shearY;
        }
        int slotCount = data.slotCount();
        for (int i = 0; i < slotCount; i++) {
            SpineSlot slot = data.slots[i];
            slotAttachment[i] = data.setupAttachment(i);
            slotColor[0][i] = slot.r;
            slotColor[1][i] = slot.g;
            slotColor[2][i] = slot.b;
            slotColor[3][i] = slot.a;
            slotDeform[i] = null;
            drawOrder[i] = i;
        }
        for (int i = 0; i < data.ik().length; i++) {
            SpineConstraints.Ik c = data.ik()[i];
            ikMix[c.index] = c.mix;
            ikBendDir[c.index] = c.bendDirection;
        }
        for (int i = 0; i < data.transforms().length; i++) {
            SpineConstraints.Transform c = data.transforms()[i];
            tcMix[c.index * 4] = c.rotateMix;
            tcMix[c.index * 4 + 1] = c.translateMix;
            tcMix[c.index * 4 + 2] = c.scaleMix;
            tcMix[c.index * 4 + 3] = c.shearMix;
        }
    }

    /** 包内使用：deform 插值缓冲（按插槽复用）。 */
    float[] deformScratch(int slot, int length) {
        float[] buffer = deformScratch[slot];
        if (buffer == null || buffer.length != length) {
            buffer = new float[length];
            deformScratch[slot] = buffer;
        }
        return buffer;
    }

    /** 包内使用：drawOrder 时间轴写入新的绘制顺序。 */
    void setDrawOrder(int[] order) {
        if (order == null || order.length != drawOrder.length) {
            return;
        }
        System.arraycopy(order, 0, drawOrder, 0, drawOrder.length);
    }

    private static float cosDeg(float degrees) {
        return (float) Math.cos(Math.toRadians(degrees));
    }

    /** 度 → 弧度（transform 约束的 offset 换算用）。 */
    static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /** 弧度 → 度（IK 求解用）。 */
    static final float RAD_TO_DEG = (float) (180.0 / Math.PI);

    /** 约束求解用的 2 元复用缓冲（避免每帧分配）。 */
    private final float[] constraintScratch = new float[2];

    /** @return 约束求解用的 2 元临时缓冲 */
    float[] parentFrameScratch() {
        return constraintScratch;
    }

    private static float sinDeg(float degrees) {
        return (float) Math.sin(Math.toRadians(degrees));
    }

    private void updateWorldTransforms() {
        int boneCount = data.boneCount();
        for (int i = 0; i < boneCount; i++) {
            updateSingle(i);
        }
    }

    /**
     * 只重算<b>这一根</b>骨骼的世界变换（不动子骨）。
     *
     * <p>这是约束求解必须的语义：官方 {@code Bone.updateWorldTransform(...)} 就只算自己，
     * 于是"约束改了父骨、子骨仍是旧世界变换"这件事在官方里是<b>有意的中间状态</b>
     * （bagpipe 的武器链 {@code F_Weapon:T → F_Weapon2:T2} 就依赖它）。
     * 若在这里顺手把子树也重算，链式约束会解出完全不同的角度（实测差 600+ 单位）。
     *
     * @param i 骨骼下标
     */
    void updateSingle(int i) {
        SpineBone bone = data.bones[i];
        float px;
        float py;
        float pa;
        float pb;
        float pc;
        float pd;
        int parent = bone.parent;
        if (parent < 0) {
            pa = 1f;
            pb = 0f;
            pc = 0f;
            pd = 1f;
            px = 0f;
            py = 0f;
        } else {
            pa = mtxA[parent];
            pb = mtxB[parent];
            pc = mtxC[parent];
            pd = mtxD[parent];
            px = worldX[parent];
            py = worldY[parent];
        }
        float x = boneX[i];
        float y = boneY[i];
        worldX[i] = pa * x + pb * y + px;
        worldY[i] = pc * x + pd * y + py;

        float rotation = boneRotation[i];
        float scaleX = boneScaleX[i];
        float scaleY = boneScaleY[i];
        float shearX = boneShearX[i];
        float shearY = boneShearY[i];

        switch (bone.transformMode) {
            case 1 -> {
                // onlyTranslation：只继承父级的位置，不继承旋转/缩放
                float rotationY = rotation + 90f + shearY;
                mtxA[i] = cosDeg(rotation + shearX) * scaleX;
                mtxB[i] = cosDeg(rotationY) * scaleY;
                mtxC[i] = sinDeg(rotation + shearX) * scaleX;
                mtxD[i] = sinDeg(rotationY) * scaleY;
            }
            case 2 -> {
                // noRotationOrReflection：不跟随父级的旋转，但保留父级矩阵的"反射"信息。
                // 官方做法：先按 |det|/|第一列|² 把父矩阵规约成"纯缩放+反射"，反解父级角度，
                // 再把本骨角度减去它，最后用 pa*la - pb*lc 这种带减号的组合。
                float s = pa * pa + pc * pc;
                float angle;
                if (s > 1e-4f) {
                    s = Math.abs(pa * pd - pb * pc) / s;
                    pb = pc * s;
                    pd = pa * s;
                    angle = (float) Math.toDegrees(Math.atan2(pc, pa));
                } else {
                    pa = 0f;
                    pc = 0f;
                    angle = 90f - (float) Math.toDegrees(Math.atan2(pd, pb));
                }
                float rx = rotation + shearX - angle;
                float ry = rotation + shearY - angle + 90f;
                float la = cosDeg(rx) * scaleX;
                float lb = cosDeg(ry) * scaleY;
                float lc = sinDeg(rx) * scaleX;
                float ld = sinDeg(ry) * scaleY;
                mtxA[i] = pa * la - pb * lc;
                mtxB[i] = pa * lb - pb * ld;
                mtxC[i] = pc * la + pd * lc;
                mtxD[i] = pc * lb + pd * ld;
            }
            case 3, 4 -> {
                // noScale / noScaleOrReflection：只保留父级的旋转方向，缩放不要
                float cos = cosDeg(rotation);
                float sin = sinDeg(rotation);
                float za = pa * cos + pb * sin;
                float zc = pc * cos + pd * sin;
                float norm = (float) Math.sqrt(za * za + zc * zc);
                if (norm > 1e-5f) {
                    norm = 1f / norm;
                }
                za *= norm;
                zc *= norm;
                float s = (float) Math.sqrt(za * za + zc * zc);
                float worldRotation = (float) (Math.PI / 2.0) + (float) Math.atan2(zc, za);
                float zb = (float) Math.cos(worldRotation) * s;
                float zd = (float) Math.sin(worldRotation) * s;
                float la = cosDeg(shearX) * scaleX;
                float lb = cosDeg(90f + shearY) * scaleY;
                float lc = sinDeg(shearX) * scaleX;
                float ld = sinDeg(90f + shearY) * scaleY;
                mtxA[i] = za * la + zb * lc;
                mtxB[i] = za * lb + zb * ld;
                mtxC[i] = zc * la + zd * lc;
                mtxD[i] = zc * lb + zd * ld;
                // noScale 要消掉父级的镜像；noScaleOrReflection 保留（只跟 skeleton 的 flip 走）
                if (bone.transformMode == 3 && pa * pd - pb * pc < 0f) {
                    mtxB[i] = -mtxB[i];
                    mtxD[i] = -mtxD[i];
                }
            }
            default -> {
                // normal：标准父子矩阵相乘
                float rotationY = rotation + 90f + shearY;
                float la = cosDeg(rotation + shearX) * scaleX;
                float lb = cosDeg(rotationY) * scaleY;
                float lc = sinDeg(rotation + shearX) * scaleX;
                float ld = sinDeg(rotationY) * scaleY;
                mtxA[i] = pa * la + pb * lc;
                mtxB[i] = pa * lb + pb * ld;
                mtxC[i] = pc * la + pd * lc;
                mtxD[i] = pc * lb + pd * ld;
            }
        }
    }

    private void emit(MeshBuffer out) {
        int slotCount = data.slotCount();
        for (int orderIndex = 0; orderIndex < slotCount; orderIndex++) {
            int slotIndex = drawOrder[orderIndex];
            SpineAttachment attachment = slotAttachment[slotIndex];
            if (attachment == null || !attachment.renderable()) {
                continue;
            }
            int boneIndex = data.slots[slotIndex].bone;
            int base = out.vertexCount();

            float r = slotColor[0][slotIndex] * attachment.r;
            float g = slotColor[1][slotIndex] * attachment.g;
            float b = slotColor[2][slotIndex] * attachment.b;
            float a = slotColor[3][slotIndex] * attachment.a;
            float[] uv = attachment.uv;
            float[] deform = slotDeform[slotIndex];

            float ba = mtxA[boneIndex];
            float bb = mtxB[boneIndex];
            float bc = mtxC[boneIndex];
            float bd = mtxD[boneIndex];
            float bwx = worldX[boneIndex];
            float bwy = worldY[boneIndex];

            if (attachment.bones == null) {
                float[] local = deform != null ? deform : attachment.localXy;
                int n = attachment.vertexCount;
                for (int v = 0; v < n; v++) {
                    float lx = local[v * 2];
                    float ly = local[v * 2 + 1];
                    float x = lx * ba + ly * bb + bwx;
                    float y = lx * bc + ly * bd + bwy;
                    out.addVertex(x, y, uv[v * 2], uv[v * 2 + 1], r, g, b, a);
                }
            } else {
                int[] bones = attachment.bones;
                float[] weights = attachment.weights;
                int n = attachment.vertexCount;
                int bi = 0;
                int wi = 0;
                int di = 0;
                for (int v = 0; v < n; v++) {
                    int boneCount = bones[bi++];
                    float x = 0f;
                    float y = 0f;
                    for (int k = 0; k < boneCount; k++) {
                        int weightBone = bones[bi++];
                        float wx = weights[wi];
                        float wy = weights[wi + 1];
                        float weight = weights[wi + 2];
                        wi += 3;
                        if (deform != null) {
                            wx += deform[di];
                            wy += deform[di + 1];
                        }
                        di += 2;
                        x += (wx * mtxA[weightBone] + wy * mtxB[weightBone] + worldX[weightBone]) * weight;
                        y += (wx * mtxC[weightBone] + wy * mtxD[weightBone] + worldY[weightBone]) * weight;
                    }
                    out.addVertex(x, y, uv[v * 2], uv[v * 2 + 1], r, g, b, a);
                }
            }

            short[] tris = attachment.tris;
            for (int i = 0; i < tris.length; i += 3) {
                out.addTriangle(base + (tris[i] & 0xFFFF),
                        base + (tris[i + 1] & 0xFFFF),
                        base + (tris[i + 2] & 0xFFFF));
            }
        }
    }
    // ------------------------------------------------------------------
    // 约束求解用的访问器（包内可见）
    // ------------------------------------------------------------------

    /** @param b 骨骼下标 @return 世界 x */
    float worldX(int b) {
        return worldX[b];
    }

    /** @param b 骨骼下标 @return 世界 y */
    float worldY(int b) {
        return worldY[b];
    }

    /** @param b 骨骼下标 @return 世界矩阵 a（x' = a*x + b*y） */
    float mtxA(int b) {
        return mtxA[b];
    }

    /** @param b 骨骼下标 @return 世界矩阵 b */
    float mtxB(int b) {
        return mtxB[b];
    }

    /** @param b 骨骼下标 @return 世界矩阵 c */
    float mtxC(int b) {
        return mtxC[b];
    }

    /** @param b 骨骼下标 @return 世界矩阵 d */
    float mtxD(int b) {
        return mtxD[b];
    }

    /** @param b 骨骼下标 @return 局部 x */
    float localX(int b) {
        return boneX[b];
    }

    /** @param b 骨骼下标 @return 局部 y */
    float localY(int b) {
        return boneY[b];
    }

    /** @param b 骨骼下标 @return 局部 scaleX */
    float localScaleX(int b) {
        return boneScaleX[b];
    }

    /** @param b 骨骼下标 @return 局部 scaleY */
    float localScaleY(int b) {
        return boneScaleY[b];
    }

    /** @param b 骨骼下标 @return 局部 shearX */
    float localShearX(int b) {
        return boneShearX[b];
    }

    /** @param b 骨骼下标 @return 局部 shearY */
    float localShearY(int b) {
        return boneShearY[b];
    }

    /** @param b 骨骼下标 @return 父骨骼下标（根骨 -1） */
    int parentOf(int b) {
        return data.bones[b].parent;
    }

    /**
     * 把世界点换算到 {@code b} 的<b>父骨骼坐标系</b>，再减去 {@code b} 的局部偏移。
     *
     * <p>等价于官方 {@code Bone.updateWorldTransform} 里给 IK 用的那段预处理：
     * {@code pid = 1/(pa*pd - pb*pc); x = (dx*pd - dy*pb)*pid; y = (dy*pa - dx*pc)*pid}。
     * 因为方舟的骨骼父级缩放一律均匀，这里换算出来的角度与"世界角度 - 父级世界角度"一致。
     *
     * @param b  骨骼下标
     * @param wx 世界 x
     * @param wy 世界 y
     * @param out 输出 [x, y]（相对 b 的局部原点、在 b 的父坐标系里）
     */
    void toParentFrame(int b, float wx, float wy, float[] out) {
        int p = data.bones[b].parent;
        if (p < 0) {
            out[0] = wx - boneX[b];
            out[1] = wy - boneY[b];
            return;
        }
        float pa = mtxA[p];
        float pb = mtxB[p];
        float pc = mtxC[p];
        float pd = mtxD[p];
        float det = pa * pd - pb * pc;
        float pid = Math.abs(det) < 1e-9f ? 0f : 1f / det;
        float dx = wx - worldX[p];
        float dy = wy - worldY[p];
        out[0] = (dx * pd - dy * pb) * pid - boneX[b];
        out[1] = (dy * pa - dx * pc) * pid - boneY[b];
    }

    /** transform 约束用：直接改写某根骨骼的世界矩阵（官方就是在世界矩阵上做旋转/缩放/切变混合的）。 */
    void setWorldMatrix(int b, float a, float bb, float c, float d) {
        mtxA[b] = a;
        mtxB[b] = bb;
        mtxC[b] = c;
        mtxD[b] = d;
    }

    /** transform 约束用：把某根骨骼的世界位置平移 {@code (dx, dy)}。 */
    void shiftWorld(int b, float dx, float dy) {
        worldX[b] += dx;
        worldY[b] += dy;
    }

    /** @param b 骨骼下标 @return 世界旋转（度，含剪切影响，与官方 worldRotationX 同算） */
    float worldRotation(int b) {
        return (float) Math.toDegrees(Math.atan2(mtxC[b], mtxA[b]));
    }

    /** @param b 骨骼下标 @return 局部旋转（度） */
    float localRotation(int b) {
        return boneRotation[b];
    }

    /** @param b 骨骼下标 @param value 局部旋转（度） */
    void setLocalRotation(int b, float value) {
        boneRotation[b] = value;
    }

    /** @param b 骨骼下标 @return 骨骼长度（setup） */
    float boneLength(int b) {
        return data.bones[b].length;
    }

    /** @param b 骨骼下标 @return 父骨骼的世界旋转；根骨返回 0 */
    float parentWorldRotation(int b) {
        int p = data.bones[b].parent;
        return p < 0 ? 0f : worldRotation(p);
    }

    /**
     * 重算 {@code root} 的<b>所有后代</b>骨骼（不含 root 自己）。
     *
     * <p>约束改完一个骨骼之后必须做这一步：官方运行时在约束之后，子骨（手指、刀身、头发）
     * 拿到的都是"改过的父矩阵"，所以整条链要跟着走。实测只改父骨不传播，29 个模型里
     * 有 22 个会偏 20–100 单位（手/武器/尾巴直接错位）。
     *
     * <p>骨骼数组本身就是拓扑序（父下标 &lt; 子下标），所以只要遍历 {@code root} 之后的骨骼、
     * 沿父链往上找能不能碰到 {@code root} 即可，O(骨骼数 × 深度)。
     *
     * @param root 骨骼下标
     */
    void updateSubtree(int root) {
        int boneCount = data.boneCount();
        for (int j = root + 1; j < boneCount; j++) {
            int p = data.bones[j].parent;
            boolean descendant = false;
            while (p >= root) {
                if (p == root) {
                    descendant = true;
                    break;
                }
                p = data.bones[p].parent;
            }
            if (descendant) {
                updateSingle(j);
            }
        }
    }

    /**
     * 重算世界变换（约束改完局部值后调用）。
     *
     * <p>整棵重算是 O(骨骼数)，100 根骨骼 × 十几个约束仍是微秒级，不值得为"只重算受影响子树"
     * 引入复杂度。而且<b>必须传播到子骨</b>：约束改了父骨的局部值之后，子骨（手、手指、刀身）
     * 都得跟着走 —— 这一点是拿官方运行时逐骨骼数值比对定下来的。
     *
     * @param b 骨骼下标
     */
    void updateFrom(int b) {
        updateWorldTransforms();
    }

    /**
     * 让某个骨骼的世界位置平移 {@code (dx, dy)}（transform 约束的位移混合用）。
     *
     * <p>做法是把世界位移用父骨骼矩阵的逆变换回局部 x/y。
     *
     * @param b  骨骼下标
     * @param dx 世界位移 x
     * @param dy 世界位移 y
     */
    void moveWorld(int b, float dx, float dy) {
        int p = data.bones[b].parent;
        float pa = 1f;
        float pb = 0f;
        float pc = 0f;
        float pd = 1f;
        if (p >= 0) {
            pa = mtxA[p];
            pb = mtxB[p];
            pc = mtxC[p];
            pd = mtxD[p];
        }
        float det = pa * pd - pb * pc;
        if (Math.abs(det) < 1e-6f) {
            return;
        }
        float invA = pd / det;
        float invB = -pb / det;
        float invC = -pc / det;
        float invD = pa / det;
        boneX[b] += invA * dx + invB * dy;
        boneY[b] += invC * dx + invD * dy;
    }

    /**
     * 把角度差折算到 (-180, 180]，避免绕远路。
     *
     * @param degrees 角度差
     * @return 最短角度差
     */
    static float shortestAngle(float degrees) {
        float a = degrees % 360f;
        if (a > 180f) {
            a -= 360f;
        } else if (a < -180f) {
            a += 360f;
        }
        return a;
    }
}
