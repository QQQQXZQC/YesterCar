package com.yestercar.runtime.spine;

/**
 * 三类约束的松数据 + 求解器（IK / transform / path）。
 *
 * <p><b>为什么必须有它</b>：方舟的骨骼里，腿、脚、武器、小道具都被约束驱动。
 * 没有约束时 setup 姿态能对上，但<b>动画一跑就散</b> —— 实测德克萨斯
 * {@code F_pocky} 偏 152 单位 / 177°，{@code F_Weapon} 偏 52 单位 / 52°，双腿偏 10–19 单位。
 * 表现就是"腿的方向反了"+"武器/道具飘在身上"。
 *
 * <p>本类是两个来源的合成，两个都不能少：
 * <ol>
 *   <li><b>setup 数据</b>（名字、骨骼、目标、offset）；</li>
 *   <li><b>动画里的{@link SpineTimeline.Ik 约束时间轴}</b> —— 它逐帧改 {@code mix} 和
 *       {@code bendDirection}。setup 里 {@code mix=0} 的约束在动画里常被设成 1，
 *       只读 setup 就会"该被拉过去的部位原地不动"。</li>
 * </ol>
 * 因此求解时 {@code mix} 一律从 {@link SpinePose} 里取，不再读本类的 setup 值。
 *
 * <p>算法照官方 {@code IkConstraint.apply(Bone...) / TransformConstraint.update()} 逐行对齐：
 * 先转成"父骨骼坐标系"，再用余弦定理/二次方程解，末骨直接用夹角，不做二次反解。
 */
final class SpineConstraints {

    private SpineConstraints() {
    }

    /** IK 约束（1 根或 2 根骨骼）。 */
    static final class Ik {
        String name;
        int order;
        /** 在文件里的下标（{@link SpineTimeline.Ik} 用它索引运行期 mix）。 */
        int index;
        int bone0;
        int bone1 = -1;
        int target;
        /** setup 强度；实际求解用 {@link SpinePose#ikMix}。 */
        float mix;
        int bendDirection;
        /** 3.8 才有的软约束参数；3.5 恒为 0/false。 */
        float softness;
        boolean compress;
        boolean stretch;

        /**
         * 按官方 {@code IkConstraint.update()} 的分派解算。
         *
         * @param pose 当前姿态
         */
        void apply(SpinePose pose) {
            float alpha = pose.ikMix[index];
            if (bone1 < 0) {
                applyOneBone(pose, alpha);
                return;
            }
            // 官方：alpha == 0 时只把末骨的世界变换重算一遍就返回
            if (alpha == 0f) {
                pose.updateSingle(bone1);
                pose.updateSubtree(bone1);
                return;
            }
            applyTwoBones(pose, alpha, pose.ikBendDir[index]);
        }

        /**
         * 单骨 IK：把骨骼转到指向目标（官方 {@code apply(Bone, float, float, float)}）。
         *
         * <p>公式逐行照反汇编：
         * <pre>
         * tx = parent_sapce(target) - ax;  ty = ... - ay;
         * rotationIK = atan2(ty,tx)*rad2deg - ashearX - arotation;
         * if (ascaleX &lt; 0) rotationIK += 180;
         * 归一到 (-180,180]，然后 arotation += rotationIK * alpha
         * </pre>
         */
        private void applyOneBone(SpinePose pose, float alpha) {
            float[] p = pose.parentFrameScratch();
            pose.toParentFrame(bone0, pose.worldX(target), pose.worldY(target), p);
            float rotationIK = (float) Math.toDegrees(Math.atan2(p[1], p[0]))
                    - pose.localShearX(bone0) - pose.localRotation(bone0);
            if (pose.localScaleX(bone0) < 0f) {
                rotationIK += 180f;
            }
            rotationIK = SpinePose.shortestAngle(rotationIK);
            pose.setLocalRotation(bone0, pose.localRotation(bone0) + rotationIK * alpha);
            pose.updateSingle(bone0);
            pose.updateSubtree(bone0);
        }

        /**
         * 双骨 IK：官方 {@code apply(Bone, Bone, float, float, int, float)} 的完整移植。
         *
         * <p>关键三点（旧的近似实现每一条都踩了）：
         * <ol>
         *   <li>全部运算在<b>根骨骼的父坐标系</b>里做（{@code toParentFrame}）；</li>
         *   <li>根骨角度 = 目标角 <b>减去</b> {@code atan2(l2·sin θ, l + l2·cos θ)}，
         *       θ 已经带上了 {@code bendDirection} 的符号 —— 旧实现是"加"，膝盖会朝反方向弯；</li>
         *   <li>末骨角度直接等于 θ（不再做一次反解），并带上 {@code atan2(bone1.ay, bone1.ax)} 的修正项
         *       与两侧 scale 为负时的 180° 修正。</li>
         * </ol>
         */
        private void applyTwoBones(SpinePose pose, float alpha, int bendDir) {
            float ax = pose.localX(bone0);
            float ay = pose.localY(bone0);
            float sxRaw = pose.localScaleX(bone0);
            float syRaw = pose.localScaleY(bone0);
            float s1Raw = pose.localScaleX(bone1);

            int rot1;
            int sgn;
            if (sxRaw < 0f) {
                rot1 = 180;
                sgn = -1;
            } else {
                rot1 = 0;
                sgn = 1;
            }
            if (syRaw < 0f) {
                sgn = -sgn;
            }
            int rot2 = s1Raw < 0f ? 180 : 0;

            float asx = Math.abs(sxRaw);
            float asy = Math.abs(syRaw);
            float as1 = Math.abs(s1Raw);
            boolean uniform = Math.abs(asx - asy) <= 1e-4f;
            // 镜像骨骼（scaleY &lt; 0）在父空间里"左右是反的"，弯曲方向要跟着翻一次，
            // 否则膝盖/肘会朝反方向折（实测 bagpipe 的左臂手肘差 122°）
            int bend = bendDir * sgn;

            float a1 = pose.localX(bone1);
            float b1 = uniform ? 0f : pose.localY(bone1);

            // bone1 的世界位置：官方是拿 bone0 的世界矩阵乘 (a1, b1) 算的，不用它自己（可能已过期）的世界值
            float c;
            float d;
            if (uniform) {
                c = pose.mtxA(bone0) * a1 + pose.worldX(bone0);
                d = pose.mtxC(bone0) * a1 + pose.worldY(bone0);
            } else {
                c = pose.mtxA(bone0) * a1 + pose.mtxB(bone0) * b1 + pose.worldX(bone0);
                d = pose.mtxC(bone0) * a1 + pose.mtxD(bone0) * b1 + pose.worldY(bone0);
            }

            float[] p = pose.parentFrameScratch();
            pose.toParentFrame(bone0, pose.worldX(target), pose.worldY(target), p);
            float tx = p[0];
            float ty = p[1];
            pose.toParentFrame(bone0, c, d, p);
            float ux = p[0];
            float uy = p[1];

            float l = (float) Math.sqrt(ux * ux + uy * uy);
            float l2 = pose.boneLength(bone1) * as1;

            float rotationIK;
            float angle;
            if (uniform && l > 1e-5f && l2 > 1e-5f) {
                l2 *= asx;
                float cos = (tx * tx + ty * ty - l * l - l2 * l2) / (2f * l * l2);
                cos = Math.max(-1f, Math.min(1f, cos));
                angle = (float) Math.acos(cos) * bend;
                float ca = l + l2 * cos;
                float sa = l2 * (float) Math.sin(angle);
                rotationIK = (float) Math.atan2(ty * ca - tx * sa, tx * ca + ty * sa);
            } else {
                // 非均匀缩放（或退化）走官方的二次方程分支
                float solveA = asx * l2;
                float solveB = asy * l2;
                float a2 = solveA * solveA;
                float b2 = solveB * solveB;
                float c2 = tx * tx + ty * ty;
                float targetAngle = (float) Math.atan2(ty, tx);
                float q = b2 * l * l + a2 * c2 - a2 * b2;
                float p1 = -2f * b2 * l;
                float disc = b2 - a2;
                float det = p1 * p1 - 4f * disc * q;
                float rootAngle;
                float rootOffset;
                if (det >= 0f && Math.abs(disc) > 1e-9f) {
                    float sq = (float) Math.sqrt(det);
                    if (p1 < 0f) {
                        sq = -sq;
                    }
                    float r1 = (-(p1 + sq)) / 2f;
                    float r2 = r1 / disc;
                    float r3 = q / r1;
                    float pick = Math.abs(r2) < Math.abs(r3) ? r2 : r3;
                    float sy = (float) Math.sqrt(Math.max(0f, c2 - pick * pick)) * bendDir;
                    rotationIK = targetAngle - (float) Math.atan2(sy, pick);
                    angle = (float) Math.atan2(sy / (asy > 1e-5f ? asy : 1e-5f),
                            (pick - l) / (asx > 1e-5f ? asx : 1e-5f));
                } else {
                    // 够不着：退化成长直线
                    rotationIK = targetAngle;
                    angle = 0f;
                }
            }

            // 官方尾段：两骨的定角修正与写入
            float l35 = (float) Math.atan2(b1, a1) * sgn;
            float cur0 = pose.localRotation(bone0);
            float d0 = SpinePose.shortestAngle((rotationIK - l35) * SpinePose.RAD_TO_DEG + rot1 - cur0);
            pose.setLocalRotation(bone0, cur0 + d0 * alpha);
            pose.updateSingle(bone0);
            pose.updateSubtree(bone0);

            float cur1 = pose.localRotation(bone1);
            float d1 = SpinePose.shortestAngle((angle + l35) * SpinePose.RAD_TO_DEG
                    - pose.localShearX(bone1) * sgn + rot2 - cur1);
            pose.setLocalRotation(bone1, cur1 + d1 * alpha);
            pose.updateSingle(bone1);
                pose.updateSubtree(bone1);
        }
    }

    /** transform 约束：骨骼跟随目标骨骼的旋转/位移/缩放/剪切（官方 {@code TransformConstraint.update()}）。 */
    static final class Transform {
        String name;
        int order;
        /** 在文件里的下标（{@link SpineTimeline.TransformMix} 用它索引运行期 mix）。 */
        int index;
        int[] bones = new int[0];
        int target;
        float offsetRotation;
        float offsetX;
        float offsetY;
        float offsetScaleX;
        float offsetScaleY;
        float offsetShearY;
        float rotateMix;
        float translateMix;
        float scaleMix;
        float shearMix;

        /**
         * 按官方语义把目标的变换按混合比例叠到骨骼上。
         *
         * <p>注意官方是直接改<b>世界矩阵</b>（旋转/缩放/切变）与<b>世界位置</b>（位移），
         * 不是在局部角度上叠加 —— 所以这里也这么做，最后再折算回局部值供重算使用。
         *
         * @param pose 当前姿态
         */
        void apply(SpinePose pose) {
            int base = index * 4;
            float rotMix = pose.tcMix[base];
            float transMix = pose.tcMix[base + 1];
            float scaleMix = pose.tcMix[base + 2];
            float shearMix = pose.tcMix[base + 3];
            if (rotMix == 0f && transMix == 0f && scaleMix <= 0f && shearMix <= 0f) {
                return;
            }
            float ta = pose.mtxA(target);
            float tb = pose.mtxB(target);
            float tc = pose.mtxC(target);
            float td = pose.mtxD(target);
            float psign = ta * td - tb * tc > 0f ? SpinePose.DEG_TO_RAD : -SpinePose.DEG_TO_RAD;
            float offRotation = offsetRotation * psign;
            float offShearY = offsetShearY * psign;

            for (int bone : bones) {
                if (rotMix != 0f) {
                    float a = pose.mtxA(bone);
                    float b = pose.mtxB(bone);
                    float c = pose.mtxC(bone);
                    float d = pose.mtxD(bone);
                    float r = (float) Math.atan2(tc, ta) - (float) Math.atan2(c, a) + offRotation;
                    if (r > Math.PI) {
                        r -= (float) (Math.PI * 2);
                    } else if (r < -Math.PI) {
                        r += (float) (Math.PI * 2);
                    }
                    r *= rotMix;
                    float cos = (float) Math.cos(r);
                    float sin = (float) Math.sin(r);
                    pose.setWorldMatrix(bone, cos * a - sin * c, cos * b - sin * d,
                            sin * a + cos * c, sin * b + cos * d);
                }
                if (transMix != 0f) {
                    // 目标局部点 (offsetX, offsetY) 变到世界，再把本骨世界位置插值过去
                    float wx = offsetX * ta + offsetY * tb + pose.worldX(target);
                    float wy = offsetX * tc + offsetY * td + pose.worldY(target);
                    pose.shiftWorld(bone, (wx - pose.worldX(bone)) * transMix,
                            (wy - pose.worldY(bone)) * transMix);
                }
                if (scaleMix > 0f) {
                    float curA = pose.mtxA(bone);
                    float curC = pose.mtxC(bone);
                    float curB = pose.mtxB(bone);
                    float curD = pose.mtxD(bone);
                    float s = (float) Math.sqrt(curA * curA + curC * curC);
                    float ts = (float) Math.sqrt(ta * ta + tc * tc);
                    if (s > 1e-5f) {
                        s = (s + (ts - s + offsetScaleX) * scaleMix) / s;
                    }
                    curA *= s;
                    curC *= s;
                    s = (float) Math.sqrt(curB * curB + curD * curD);
                    ts = (float) Math.sqrt(tb * tb + td * td);
                    if (s > 1e-5f) {
                        s = (s + (ts - s + offsetScaleY) * scaleMix) / s;
                    }
                    curB *= s;
                    curD *= s;
                    pose.setWorldMatrix(bone, curA, curB, curC, curD);
                }
                if (shearMix > 0f) {
                    float b = pose.mtxB(bone);
                    float d = pose.mtxD(bone);
                    float by = (float) Math.atan2(d, b);
                    float r = (float) Math.atan2(td, tb) - (float) Math.atan2(tc, ta)
                            - (by - (float) Math.atan2(pose.mtxC(bone), pose.mtxA(bone)));
                    if (r > Math.PI) {
                        r -= (float) (Math.PI * 2);
                    } else if (r < -Math.PI) {
                        r += (float) (Math.PI * 2);
                    }
                    r = by + (r + offShearY) * shearMix;
                    float s = (float) Math.sqrt(b * b + d * d);
                    float a = pose.mtxA(bone);
                    float c = pose.mtxC(bone);
                    pose.setWorldMatrix(bone, a, (float) Math.cos(r) * s, c, (float) Math.sin(r) * s);
                }
                // 世界矩阵被直接改写过，子骨必须按新父矩阵重算（官方运行时实测就是这个结果）
                if (System.getProperty("yc.noprop") == null) {
                    pose.updateSubtree(bone);
                }
            }
        }
    }

    /** path 约束：让骨骼沿目标插槽上的路径排布（本仓素材未使用）。 */
    static final class Path {
        String name;
        int order;
        int[] bones = new int[0];
        int targetSlot;
    }
}
