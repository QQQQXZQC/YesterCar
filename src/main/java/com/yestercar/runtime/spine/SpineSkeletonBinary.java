package com.yestercar.runtime.spine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * spine 二进制（{@code .skel}）解析器，同时支持 <b>3.5.51</b> 与 <b>3.8.99</b>。
 *
 * <p>字段顺序不是猜的：全部来自 {@code javap -p -c} 反汇编
 * {@code com.esotericsoftware.spine.SkeletonBinary}（3.5.51.1 / 3.8.99.1 两个 jar），
 * 以及 {@code MeshAttachment} / {@code Animation$*Timeline} / {@code Bone} 的消费端代码。
 *
 * <h2>两版差异总表（本类里的分支都按这张表写）</h2>
 * <table border="1">
 *   <caption>3.5.51 vs 3.8.99 二进制差异</caption>
 *   <tr><th>位置</th><th>3.5.51</th><th>3.8.99</th></tr>
 *   <tr><td>文件头</td><td>hash, version, width, height</td>
 *       <td>hash, version, <b>x, y</b>, width, height</td></tr>
 *   <tr><td>非必要字段块</td><td>fps, imagesPath</td><td>fps, imagesPath, <b>audioPath</b></td></tr>
 *   <tr><td>字符串</td><td>直接用 cstring 风格的变长串</td>
 *       <td><b>先读一张全局字符串表</b>，之后既可用 {@code readString}（内联）也可用
 *           {@code readStringRef}（表下标，0 = null）</td></tr>
 *   <tr><td>骨骼</td><td>…, length, transformMode</td>
 *       <td>…, length, transformMode, <b>skinRequired(bool)</b></td></tr>
 *   <tr><td>插槽</td><td>name, boneIndex, color, attachmentName, blendMode</td>
 *       <td>name, boneIndex, color, <b>darkColor</b>, attachmentName(<b>ref</b>), blendMode</td></tr>
 *   <tr><td>IK 约束</td><td>name, order, bones[], target, mix, bendDirection</td>
 *       <td>+ <b>skinRequired</b>（order 后）、<b>softness</b>（mix 后）、
 *           <b>compress/stretch/uniform</b>（bendDirection 后）</td></tr>
 *   <tr><td>transform 约束</td><td>offset 与 mix 共 11 个 float</td>
 *       <td>+ <b>skinRequired</b>、目标后多 <b>local/relative</b> 两个 bool</td></tr>
 *   <tr><td>path 约束</td><td>order 后直接 bones[]</td><td>+ <b>skinRequired</b></td></tr>
 *   <tr><td>皮肤</td><td>无骨骼/约束白名单</td>
 *       <td>名字后多 <b>骨骼白名单 + 三类约束白名单</b></td></tr>
 *   <tr><td>附件名</td><td>皮肤循环里读 {@code readString} 后传参</td>
 *       <td><b>在 readAttachment 内部用 {@code readStringRef} 读</b></td></tr>
 *   <tr><td>slot 时间轴类型码</td><td>0 = attachment，1 = color</td>
 *       <td>0 = attachment，1 = color，<b>2 = two-color</b>（顺序相同，3.8 多一种）</td></tr>
 *   <tr><td>IK 时间轴帧</td><td>time, mix, bendDirection</td>
 *       <td>time, mix, <b>softness</b>, bendDirection, <b>compress, stretch</b></td></tr>
 *   <tr><td>事件</td><td>intValue, floatValue, stringValue</td>
 *       <td>+ <b>audioPath</b>，且 audioPath 非空时再读 <b>volume, balance</b></td></tr>
 *   <tr><td>曲线编码</td><td colspan="2"><b>完全相同</b>：1 字节类型（0 linear / 1 stepped / 2 bezier）
 *       + bezier 时 4 个 float</td></tr>
 *   <tr><td>mesh / boundingbox / path 附件体</td><td colspan="2"><b>字段相同</b>（只是字符串改 ref）</td></tr>
 * </table>
 *
 * <p>⚠️ <b>版本必须逐文件读</b>：3.8 的运行时读 3.5 的文件不会抛异常，而是静默错位
 * （见 {@link SpineData} 的说明）。本类在读完 hash 后立刻读版本串再决定分支。
 */
final class SpineSkeletonBinary {

    private final SpineBinaryReader in;
    private final SpineAtlas atlas;
    private final boolean v38;
    private final String modelName;

    private final List<SpineBone> bones = new ArrayList<>();
    private SpineConstraints.Ik[] iks = new SpineConstraints.Ik[0];
    private SpineConstraints.Transform[] transforms = new SpineConstraints.Transform[0];
    private SpineConstraints.Path[] paths = new SpineConstraints.Path[0];

    private final List<SpineSlot> slots = new ArrayList<>();
    private final List<SpineSkin> skins = new ArrayList<>();
    private final List<PendingLink> pendingLinks = new ArrayList<>();

    /** 3.8 的全局字符串表。 */
    private String[] strings = new String[0];

    private int missingRegions;

    /** 打开后每解析完一段就往 stderr 打一行"段名 @字节偏移"，排查错位用。 */
    private static final boolean DEBUG = Boolean.getBoolean("spine.debug");

    private void dbg(String message) {
        if (DEBUG) {
            System.err.println("[spine] " + message + " @" + in.position());
        }
    }

    /**
     * @param order 待检查的排列
     * @param n     插槽数
     * @return 是否恰好是 0..n-1 的一个排列
     */
    private static boolean isPermutation(int[] order, int n) {
        boolean[] seen = new boolean[n];
        for (int i = 0; i < n; i++) {
            int v = order[i];
            if (v < 0 || v >= n || seen[v]) {
                return false;
            }
            seen[v] = true;
        }
        return true;
    }

    private int countAttachments() {
        int n = 0;
        for (SpineSkin s : skins) {
            n += s.size();
        }
        return n;
    }

    private SpineSkeletonBinary(SpineBinaryReader in, SpineAtlas atlas, boolean v38, String modelName) {
        this.in = in;
        this.atlas = atlas;
        this.v38 = v38;
        this.modelName = modelName;
    }

    /**
     * 解析一个 {@code .skel}。
     *
     * @param bytes     文件字节
     * @param atlas     贴图集（用于解析附件的贴图区域与 UV）
     * @param modelName 模型名（文件名去扩展名）
     * @return 解析好的模型数据
     * @throws IOException 版本不支持或字节流与格式不符
     */
    static SpineData parse(byte[] bytes, SpineAtlas atlas, String modelName) throws IOException {
        // 先按 3.5 语义读 hash + version（两者对 ASCII 完全一样），拿到版本后再决定真正的读取器
        SpineBinaryReader probe = new SpineBinaryReader(bytes, true);
        String hash = probe.readString();
        String version = probe.readString();
        int dataStart = probe.position();
        if (version == null || version.isEmpty()) {
            throw new IOException("skel 文件头没有版本串（hash=" + hash + "），无法判定格式版本");
        }
        int major = parseVersionPart(version, 0);
        int minor = parseVersionPart(version, 1);
        if (major != 3) {
            throw new IOException("不支持的 spine 版本: " + version + "（本解析器只支持 3.5 / 3.8）");
        }
        // 3.8 起：多了 x/y、audioPath、字符串表、skinRequired、两类颜色时间轴、IK 新字段
        boolean v38 = minor >= 8;
        if (minor != 5 && minor < 8) {
            throw new IOException("不支持的 spine 3." + minor + "（只支持 3.5 / 3.8）: " + version);
        }
        SpineBinaryReader reader = new SpineBinaryReader(bytes, !v38);
        reader.seek(dataStart);
        SpineSkeletonBinary parser = new SpineSkeletonBinary(reader, atlas, v38, modelName);
        return parser.read(version);
    }

    private static int parseVersionPart(String version, int index) {
        String[] parts = version.split("\\.");
        if (index >= parts.length) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    private SpineData read(String version) throws IOException {
        try {
            return readSkeleton(version);
        } catch (UncheckedIOException e) {
            throw new IOException("解析 .skel 失败 @byte " + in.position() + ": " + e.getCause().getMessage(), e);
        }
    }

    private SpineData readSkeleton(String version) throws IOException {
        // 头部
        if (v38) {
            in.readFloat(); // x
            in.readFloat(); // y
        }
        float artWidth = in.readFloat();
        float artHeight = in.readFloat();
        boolean nonessential = in.readBoolean();
        if (nonessential) {
            in.readFloat();    // fps
            in.readString();   // imagesPath
            if (v38) {
                in.readString(); // audioPath
            }
        }

        if (v38) {
            int stringCount = in.readVarInt();
            strings = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                strings[i] = in.readString();
            }
        }

        dbg("header");
        readBones(nonessential);
        dbg("bones=" + bones.size());
        readSlots();
        dbg("slots=" + slots.size());
        readIkConstraints();
        readTransformConstraints();
        readPathConstraints();
        dbg("constraints");
        readSkins(nonessential);
        dbg("skins=" + skins.size() + " attachments=" + countAttachments());
        resolveLinkedMeshes();
        dbg("linkedMeshes");
        readEvents();
        dbg("events");
        List<SpineAnimation> animations = readAnimations();
        dbg("animations=" + animations.size());
        // 完整性自检：所有字段读完后必须正好停在文件末尾。
        // 中途任何一处字段读错（少读/多读几个字节）都会让尾部对不上，这里直接失败，
        // 绝不"加载成功但数据是错的"。
        if (in.position() != in.length()) {
            throw new IOException("解析后仍剩 " + (in.length() - in.position()) + " 字节未消费（pos="
                    + in.position() + "，len=" + in.length() + "），说明字段顺序有误");
        }

        SpineSkin[] skinArray = skins.toArray(new SpineSkin[0]);
        SpineData data = new SpineData(modelName, version, atlas,
                bones.toArray(new SpineBone[0]), slots.toArray(new SpineSlot[0]),
                skinArray, animations.toArray(new SpineAnimation[0]), artWidth, artHeight);
        data.setConstraints(iks, transforms, paths);
        for (int i = 0; i < missingRegions; i++) {
            data.addMissingAtlasRegion();
        }
        return data;
    }

    // ------------------------------------------------------------------
    // 骨骼 / 插槽
    // ------------------------------------------------------------------

    private void readBones(boolean nonessential) {
        int boneCount = in.readVarInt();
        for (int i = 0; i < boneCount; i++) {
            String name = in.readString();
            int parent = i == 0 ? -1 : in.readVarInt();
            float rotation = in.readFloat();
            float x = in.readFloat();
            float y = in.readFloat();
            float scaleX = in.readFloat();
            float scaleY = in.readFloat();
            float shearX = in.readFloat();
            float shearY = in.readFloat();
            float length = in.readFloat();
            int transformMode = in.readVarInt();
            if (v38) {
                // 3.8 新增：该骨骼是否只在特定皮肤里存在（本项目用不到）
                in.readBoolean();
            }
            if (nonessential) {
                readRgba(in.readInt()); // 骨骼颜色，渲染不用
            }
            bones.add(new SpineBone(name, parent, rotation, x, y, scaleX, scaleY,
                    shearX, shearY, length, transformMode));
        }
    }

    private void readSlots() {
        int slotCount = in.readVarInt();
        for (int i = 0; i < slotCount; i++) {
            String name = in.readString();
            int boneIndex = in.readVarInt();
            float[] color = readRgba(in.readInt());
            if (v38) {
                // 3.8 新增：dark color（two-color 时间轴用）。0xFFFFFFFF 表示"没有"。
                in.readInt();
            }
            String attachmentName = v38 ? readStringRef() : in.readString();
            in.readVarInt(); // blendMode
            slots.add(new SpineSlot(name, boneIndex, color[0], color[1], color[2], color[3],
                    attachmentName, 0));
        }
    }

    // ------------------------------------------------------------------
    // 约束（本工程素材全为空；为了字节对齐仍然逐字段读掉）
    // ------------------------------------------------------------------

    private void readIkConstraints() {
        int count = in.readVarInt();
        iks = new SpineConstraints.Ik[count];
        for (int i = 0; i < count; i++) {
            SpineConstraints.Ik c = new SpineConstraints.Ik();
            c.name = in.readString();
            c.order = in.readVarInt();
            c.index = i;
            if (v38) {
                in.readBoolean();
            }
            int boneCount = in.readVarInt();
            int[] bs = new int[boneCount];
            for (int b = 0; b < boneCount; b++) {
                bs[b] = in.readVarInt();
            }
            c.bone0 = boneCount > 0 ? bs[0] : -1;
            c.bone1 = boneCount > 1 ? bs[1] : -1;
            c.target = in.readVarInt();
            c.mix = in.readFloat();
            if (v38) {
                c.softness = in.readFloat();
            }
            c.bendDirection = in.readByteSigned();
            if (v38) {
                c.compress = in.readBoolean();
                c.stretch = in.readBoolean();
                in.readBoolean();
            }
            iks[i] = c;
        }
    }

    private void readTransformConstraints() {
        int count = in.readVarInt();
        transforms = new SpineConstraints.Transform[count];
        for (int i = 0; i < count; i++) {
            SpineConstraints.Transform c = new SpineConstraints.Transform();
            c.name = in.readString();
            c.order = in.readVarInt();
            c.index = i;
            if (v38) {
                in.readBoolean();
            }
            int boneCount = in.readVarInt();
            c.bones = new int[boneCount];
            for (int b = 0; b < boneCount; b++) {
                c.bones[b] = in.readVarInt();
            }
            c.target = in.readVarInt();
            if (v38) {
                in.readBoolean();
                in.readBoolean();
            }
            c.offsetRotation = in.readFloat();
            c.offsetX = in.readFloat();
            c.offsetY = in.readFloat();
            c.offsetScaleX = in.readFloat();
            c.offsetScaleY = in.readFloat();
            c.offsetShearY = in.readFloat();
            c.rotateMix = in.readFloat();
            c.translateMix = in.readFloat();
            c.scaleMix = in.readFloat();
            c.shearMix = in.readFloat();
            transforms[i] = c;
        }
    }

    private void readPathConstraints() {
        int count = in.readVarInt();
        paths = new SpineConstraints.Path[count];
        for (int i = 0; i < count; i++) {
            SpineConstraints.Path c = new SpineConstraints.Path();
            c.name = in.readString();
            c.order = in.readVarInt();
            if (v38) {
                in.readBoolean();
            }
            int boneCount = in.readVarInt();
            c.bones = new int[boneCount];
            for (int b = 0; b < boneCount; b++) {
                c.bones[b] = in.readVarInt();
            }
            c.targetSlot = in.readVarInt();
            in.readVarInt();
            in.readVarInt();
            in.readVarInt();
            in.readFloat();
            in.readFloat();
            in.readFloat();
            in.readFloat();
            in.readFloat();
            paths[i] = c;
        }
    }

    // ------------------------------------------------------------------
    // 皮肤 + 附件
    // ------------------------------------------------------------------

    private void readSkins(boolean nonessential) throws IOException {
        SpineSkin defaultSkin;
        if (v38) {
            defaultSkin = readSkin("default", true, nonessential);
            if (defaultSkin != null) {
                skins.add(defaultSkin);
            }
            int count = in.readVarInt();
            for (int i = 0; i < count; i++) {
                skins.add(readSkin(null, false, nonessential));
            }
        } else {
            defaultSkin = readSkin("default", false, nonessential);
            if (defaultSkin != null) {
                skins.add(defaultSkin);
            }
            int count = in.readVarInt();
            for (int i = 0; i < count; i++) {
                String name = in.readString();
                skins.add(readSkin(name, false, nonessential));
            }
        }
    }

    /**
     * @param name     皮肤名（3.8 传 null，名字在方法内部读）
     * @param isDefault 是否是 default 皮肤（这决定"先读条目数"还是"先读名字/白名单"）
     */
    private SpineSkin readSkin(String name, boolean isDefault, boolean nonessential) throws IOException {
        int entryCount;
        SpineSkin skin;
        if (v38) {
            if (isDefault) {
                entryCount = in.readVarInt();
                if (entryCount == 0) {
                    return null;
                }
                skin = new SpineSkin("default");
            } else {
                String skinName = readStringRef();
                skin = new SpineSkin(skinName);
                // 3.8 新增：皮肤的骨骼白名单 + 三类约束白名单
                int boneCount = in.readVarInt();
                for (int b = 0; b < boneCount; b++) {
                    in.readVarInt();
                }
                int ikCount = in.readVarInt();
                for (int b = 0; b < ikCount; b++) {
                    in.readVarInt();
                }
                int transformCount = in.readVarInt();
                for (int b = 0; b < transformCount; b++) {
                    in.readVarInt();
                }
                int pathCount = in.readVarInt();
                for (int b = 0; b < pathCount; b++) {
                    in.readVarInt();
                }
                entryCount = in.readVarInt();
            }
        } else {
            entryCount = in.readVarInt();
            if (entryCount == 0) {
                return null;
            }
            skin = new SpineSkin(name);
        }

        for (int i = 0; i < entryCount; i++) {
            int slotIndex = in.readVarInt();
            int attachmentCount = in.readVarInt();
            for (int ii = 0; ii < attachmentCount; ii++) {
                // 注意：皮肤条目名与附件自己的名字是**两次独立读取**。
                // 皮肤循环先读一个名字（作为 skin.getAttachment 的键），readAttachment 里还会再读一个
                // （通常为空 = "与皮肤条目名相同"）。两版都这样，少读一次就会整体错位。
                String entryName = v38 ? readStringRef() : in.readString();
                SpineAttachment attachment = readAttachment(slotIndex, entryName, nonessential, skin);
                if (attachment != null) {
                    skin.put(slotIndex, attachment.name, attachment);
                }
            }
        }
        return skin;
    }

    /** 3.8 的字符串引用：0 = null，其余是字符串表下标（1 基）。 */
    private String readStringRef() {
        int index = in.readVarInt();
        if (index == 0) {
            return null;
        }
        int i = index - 1;
        return i >= 0 && i < strings.length ? strings[i] : null;
    }

    private SpineAttachment readAttachment(int slotIndex, String nameArg, boolean nonessential, SpineSkin skin)
            throws IOException {
        String name = v38 ? readStringRef() : in.readString();
        if (name == null) {
            name = nameArg;
        }
        int type = in.readByteUnsigned();
        switch (type) {
            case 0:
                return readRegionAttachment(name, nonessential);
            case 1:
                return readBoundingBoxAttachment(name, nonessential);
            case 2:
                return readMeshAttachment(name, nonessential);
            case 3:
                return readLinkedMesh(name, slotIndex, nonessential, skin);
            case 4:
                return readPathAttachment(name, nonessential);
            case 5:
                return readPointAttachment(name, nonessential);
            case 6:
                return readClippingAttachment(name, nonessential);
            default:
                // 未知类型：无法知道字段宽度，只能放弃（官方运行时也是直接返回 null）
                throw new IOException("未知附件类型 " + type + "（name=" + name + "，pos=" + in.position() + "）");
        }
    }

    /** 0 = region。字段：path, rotation, x, y, scaleX, scaleY, width, height, color。 */
    private SpineAttachment readRegionAttachment(String name, boolean nonessential) {
        String path = v38 ? readStringRef() : in.readString();
        if (path == null) {
            path = name;
        }
        float rotation = in.readFloat();
        float x = in.readFloat();
        float y = in.readFloat();
        float scaleX = in.readFloat();
        float scaleY = in.readFloat();
        float width = in.readFloat();
        float height = in.readFloat();
        float[] color = readRgba(in.readInt());
        SpineAtlas.Region region = atlas.region(path);
        if (region == null) {
            missingRegions++;
            dbg("region 缺贴图区域 name=" + name + " path=[" + path + "]");
        }
        return SpineAttachment.region(name, path, rotation, x, y, scaleX, scaleY, width, height,
                color[0], color[1], color[2], color[3], region);
    }

    /** 1 = boundingbox（判定框，不参与渲染与包围盒）。 */
    private SpineAttachment readBoundingBoxAttachment(String name, boolean nonessential) {
        int vertexCount = in.readVarInt();
        Vertices vertices = readVertices(vertexCount);
        float[] color = nonessential ? readRgba(in.readInt()) : new float[] {1f, 1f, 1f, 1f};
        return SpineAttachment.placeholder(SpineAttachment.BOUNDING_BOX, name, name, vertexCount,
                color[0], color[1], color[2], color[3]);
    }

    /** 2 = mesh。字段：path, color, uvCount, regionUVs, triangles, vertices, hullLength, [edges, w, h]。 */
    private SpineAttachment readMeshAttachment(String name, boolean nonessential) {
        String path = v38 ? readStringRef() : in.readString();
        if (path == null) {
            // 官方：path 为空表示"和附件名相同"（导出器对同名区域会写空）
            path = name;
        }
        float[] color = readRgba(in.readInt());
        int vertexCount = in.readVarInt();
        float[] regionUvs = in.readFloatArray(vertexCount * 2);
        short[] tris = in.readShortArray();
        Vertices vertices = readVertices(vertexCount);
        in.readVarInt(); // hullLength（= 轮廓顶点数 * 2；权重判定看 readVertices 的布尔，不靠它）
        if (nonessential) {
            in.readShortArray(); // edges
            in.readFloat();      // width
            in.readFloat();      // height
        }
        SpineAtlas.Region region = atlas.region(path);
        if (region == null) {
            missingRegions++;
            dbg("mesh 缺贴图区域 name=" + name + " path=[" + path + "]");
        }
        return SpineAttachment.mesh(name, path, vertexCount, regionUvs, tris,
                vertices.unweighted, vertices.weights, vertices.bones,
                color[0], color[1], color[2], color[3], region);
    }

    /** 3 = linkedmesh：几何数据继承自另一个网格，只在解析完皮肤后才能解析。 */
    private SpineAttachment readLinkedMesh(String name, int slotIndex, boolean nonessential, SpineSkin owner) {
        String path = v38 ? readStringRef() : in.readString();
        if (path == null) {
            path = name;
        }
        float[] color = readRgba(in.readInt());
        String parentSkin = v38 ? readStringRef() : in.readString();
        String parentName = v38 ? readStringRef() : in.readString();
        in.readBoolean(); // inheritDeform
        if (nonessential) {
            in.readFloat(); // width
            in.readFloat(); // height
        }
        PendingLink link = new PendingLink(name, path, parentSkin, parentName, slotIndex, color, owner);
        pendingLinks.add(link);
        // 先返回一个占位（链接解析完再换成真附件），保证字节流继续对齐
        return SpineAttachment.placeholder(SpineAttachment.MESH, name, path, 0, color[0], color[1], color[2], color[3]);
    }

    /** 4 = path（跟随路径的附件，本项目素材里没有；只读掉）。 */
    private SpineAttachment readPathAttachment(String name, boolean nonessential) {
        in.readBoolean();  // closed
        in.readBoolean();  // constantSpeed
        int vertexCount = in.readVarInt();
        readVertices(vertexCount);
        int lengthCount = vertexCount / 3;
        in.readFloatArray(lengthCount);
        float[] color = nonessential ? readRgba(in.readInt()) : new float[] {1f, 1f, 1f, 1f};
        return SpineAttachment.placeholder(SpineAttachment.PATH, name, name, vertexCount,
                color[0], color[1], color[2], color[3]);
    }

    /** 5 = point（3.5 的运行时直接返回 null 且不读字段；这里同样处理）。 */
    private SpineAttachment readPointAttachment(String name, boolean nonessential) {
        if (!v38) {
            return null;
        }
        in.readFloat(); // x
        in.readFloat(); // y
        in.readFloat(); // rotation
        float[] color = nonessential ? readRgba(in.readInt()) : new float[] {1f, 1f, 1f, 1f};
        return SpineAttachment.placeholder(SpineAttachment.POINT, name, name, 0,
                color[0], color[1], color[2], color[3]);
    }

    /** 6 = clipping（同上）。 */
    private SpineAttachment readClippingAttachment(String name, boolean nonessential) {
        if (!v38) {
            return null;
        }
        in.readVarInt(); // endSlot
        int vertexCount = in.readVarInt();
        readVertices(vertexCount);
        float[] color = nonessential ? readRgba(in.readInt()) : new float[] {1f, 1f, 1f, 1f};
        return SpineAttachment.placeholder(SpineAttachment.CLIPPING, name, name, vertexCount,
                color[0], color[1], color[2], color[3]);
    }

    /** {@code readVertices}：无权重的 x/y 对，或带骨骼权重的 (x, y, weight) 三元组。 */
    private Vertices readVertices(int vertexCount) {
        Vertices v = new Vertices();
        boolean weighted = in.readBoolean();
        if (!weighted) {
            v.unweighted = in.readFloatArray(vertexCount * 2);
            return v;
        }
        // 权重版：每个顶点先给"骨骼数"，再逐骨骼给 (骨骼下标, x, y, 权重)。
        // 注意一个顶点可以挂多根骨骼，所以三元组总数 >= 顶点数，缓冲要能自动扩容。
        int[] boneList = new int[Math.max(8, vertexCount * 2)];
        float[] weightList = new float[Math.max(8, vertexCount * 6)];
        int bn = 0;
        int wn = 0;
        for (int i = 0; i < vertexCount; i++) {
            int count = in.readVarInt();
            if (count < 0 || count > in.length()) {
                throw new java.io.UncheckedIOException(new IOException(
                        "骨骼权重数异常: " + count + " @ " + in.position()));
            }
            if (bn + 1 + count > boneList.length) {
                boneList = java.util.Arrays.copyOf(boneList, boneList.length * 2 + 1 + count);
            }
            boneList[bn++] = count;
            if (wn + count * 3 > weightList.length) {
                weightList = java.util.Arrays.copyOf(weightList, weightList.length * 2 + count * 3);
            }
            for (int b = 0; b < count; b++) {
                boneList[bn++] = in.readVarInt();
                weightList[wn++] = in.readFloat();
                weightList[wn++] = in.readFloat();
                weightList[wn++] = in.readFloat();
            }
        }
        v.bones = java.util.Arrays.copyOf(boneList, bn);
        v.weights = java.util.Arrays.copyOf(weightList, wn);
        return v;
    }

    private static final class Vertices {
        float[] unweighted;
        float[] weights;
        int[] bones;
    }

    private static final class PendingLink {
        final String name;
        final String path;
        final String parentSkin;
        final String parentName;
        final int slotIndex;
        final float[] color;
        final SpineSkin owner;

        PendingLink(String name, String path, String parentSkin, String parentName, int slotIndex,
                float[] color, SpineSkin owner) {
            this.name = name;
            this.path = path;
            this.parentSkin = parentSkin;
            this.parentName = parentName;
            this.slotIndex = slotIndex;
            this.color = color;
            this.owner = owner;
        }
    }

    /**
     * 解析 linked mesh：把自己的颜色盖到父网格的几何数据上。
     *
     * <p>官方运行时会保留 {@code parentMesh} 指针并调用 {@code updateUVs()}，
     * 这里语义等价地"复制父网格的几何"，因为渲染只用到几何 + UV，且
     * {@code inheritDeform} 本项目素材没出现。
     */
    private void resolveLinkedMeshes() {
        for (PendingLink link : pendingLinks) {
            SpineAttachment parent = null;
            if (link.parentSkin == null) {
                if (!skins.isEmpty()) {
                    parent = skins.get(0).get(link.slotIndex, link.parentName);
                }
            } else {
                for (SpineSkin s : skins) {
                    if (link.parentSkin.equals(s.name)) {
                        parent = s.get(link.slotIndex, link.parentName);
                        break;
                    }
                }
            }
            if (parent == null) {
                for (SpineSkin s : skins) {
                    parent = s.get(link.slotIndex, link.parentName);
                    if (parent != null) {
                        break;
                    }
                }
            }
            if (parent == null || parent.vertexCount == 0) {
                continue; // 父网格找不到：这个附件就退化成不画（SpineInspect 里能看到附件数为 0）
            }
            SpineAttachment resolved = SpineAttachment.mesh(link.name, link.path, parent.vertexCount,
                    parent.uv, parent.tris, parent.localXy, parent.weights, parent.bones,
                    link.color[0], link.color[1], link.color[2], link.color[3], null);
            // 注意：parent.uv 已经是"整页 UV"，这里不能再乘一遍矩形，所以 region 传 null
            if (link.owner != null) {
                link.owner.put(link.slotIndex, link.name, resolved);
            }
        }
        pendingLinks.clear();
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /** 每个事件的 audioPath 是否非空（3.8 的动画事件关键帧据此决定要不要多读 2 个 float）。 */
    private final List<Boolean> eventHasAudio = new ArrayList<>();

    private void readEvents() {
        int count = in.readVarInt();
        for (int i = 0; i < count; i++) {
            // 3.8 的事件名走字符串表引用，3.5 是内联串
            String name = v38 ? readStringRef() : in.readString();
            in.readVarIntZigZag();           // intValue
            in.readFloat();                  // floatValue
            in.readString();                 // stringValue
            boolean hasAudio = false;
            if (v38) {
                // 3.8 新增：audioPath；非空时紧跟 volume / balance
                String audioPath = in.readString();
                hasAudio = audioPath != null;
                if (hasAudio) {
                    in.readFloat();          // volume
                    in.readFloat();          // balance
                }
            }
            eventHasAudio.add(hasAudio);
        }
    }

    // ------------------------------------------------------------------
    // 动画
    // ------------------------------------------------------------------

    private List<SpineAnimation> readAnimations() throws IOException {
        int count = in.readVarInt();
        List<SpineAnimation> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = in.readString();
            dbg("anim " + name);
            out.add(readAnimation(name));
        }
        return out;
    }

    private SpineAnimation readAnimation(String name) throws IOException {
        List<SpineTimeline> timelines = new ArrayList<>();
        float duration = 0f;

        // ---- 插槽时间轴 ----
        int slotTimelineCount = in.readVarInt();
        for (int i = 0; i < slotTimelineCount; i++) {
            int slotIndex = in.readVarInt();
            int timelineCount = in.readVarInt();
            for (int ii = 0; ii < timelineCount; ii++) {
                int timelineType = in.readByteUnsigned();
                int frameCount = in.readVarInt();
                // 两版一致（反汇编 lookupswitch 确认）：0 = attachment，1 = color；
                // 3.8 多一个 2 = two-color。
                if (timelineType == 0) {
                    float[] frames = new float[frameCount];
                    String[] names = new String[frameCount];
                    for (int frame = 0; frame < frameCount; frame++) {
                        frames[frame] = in.readFloat();
                        names[frame] = v38 ? readStringRef() : in.readString();
                    }
                    timelines.add(new SpineTimeline.Attachment(slotIndex, frames, names));
                    duration = Math.max(duration, last(frames));
                } else if (timelineType == 1) {
                    float[] frames = new float[frameCount];
                    float[] rgba = new float[frameCount * 4];
                    float[] curves = new float[frameCount * SpineCurves.STRIDE];
                    for (int frame = 0; frame < frameCount; frame++) {
                        frames[frame] = in.readFloat();
                        float[] c = readRgba(in.readInt());
                        rgba[frame * 4] = c[0];
                        rgba[frame * 4 + 1] = c[1];
                        rgba[frame * 4 + 2] = c[2];
                        rgba[frame * 4 + 3] = c[3];
                        if (frame < frameCount - 1) {
                            readCurve(curves, frame);
                        }
                    }
                    timelines.add(new SpineTimeline.Color(slotIndex, frames, rgba, curves));
                    duration = Math.max(duration, last(frames));
                } else if (v38 && timelineType == 2) {
                    // twoColor：rgb(a) + dark rgb，主色之外都丢弃
                    float[] frames = new float[frameCount];
                    float[] rgba = new float[frameCount * 4];
                    float[] curves = new float[frameCount * SpineCurves.STRIDE];
                    for (int frame = 0; frame < frameCount; frame++) {
                        frames[frame] = in.readFloat();
                        float[] c = readRgba(in.readInt());
                        in.readInt(); // dark color（rgb888）
                        rgba[frame * 4] = c[0];
                        rgba[frame * 4 + 1] = c[1];
                        rgba[frame * 4 + 2] = c[2];
                        rgba[frame * 4 + 3] = c[3];
                        if (frame < frameCount - 1) {
                            readCurve(curves, frame);
                        }
                    }
                    timelines.add(new SpineTimeline.Color(slotIndex, frames, rgba, curves));
                    duration = Math.max(duration, last(frames));
                } else {
                    throw new IOException("未知插槽时间轴类型 " + timelineType + " @ " + in.position());
                }
            }
        }

        // ---- 骨骼时间轴 ----
        int boneTimelineCount = in.readVarInt();
        for (int i = 0; i < boneTimelineCount; i++) {
            int boneIndex = in.readVarInt();
            int timelineCount = in.readVarInt();
            for (int ii = 0; ii < timelineCount; ii++) {
                int timelineType = in.readByteUnsigned();
                int frameCount = in.readVarInt();
                if (timelineType == 0) {
                    float[] frames = new float[frameCount];
                    float[] values = new float[frameCount];
                    float[] curves = new float[frameCount * SpineCurves.STRIDE];
                    for (int frame = 0; frame < frameCount; frame++) {
                        frames[frame] = in.readFloat();
                        values[frame] = in.readFloat();
                        if (frame < frameCount - 1) {
                            readCurve(curves, frame);
                        }
                    }
                    timelines.add(new SpineTimeline.Rotate(boneIndex, frames, values, curves));
                    duration = Math.max(duration, last(frames));
                } else if (timelineType >= 1 && timelineType <= 3) {
                    SpineTimeline.BoneKind kind = timelineType == 2 ? SpineTimeline.BoneKind.SCALE
                            : timelineType == 3 ? SpineTimeline.BoneKind.SHEAR : SpineTimeline.BoneKind.TRANSLATE;
                    float[] frames = new float[frameCount];
                    float[] values = new float[frameCount * 2];
                    float[] curves = new float[frameCount * SpineCurves.STRIDE];
                    for (int frame = 0; frame < frameCount; frame++) {
                        frames[frame] = in.readFloat();
                        values[frame * 2] = in.readFloat();
                        values[frame * 2 + 1] = in.readFloat();
                        if (frame < frameCount - 1) {
                            readCurve(curves, frame);
                        }
                    }
                    timelines.add(new SpineTimeline.BoneXy(boneIndex, kind, frames, values, curves));
                    duration = Math.max(duration, last(frames));
                } else {
                    throw new IOException("未知骨骼时间轴类型 " + timelineType + " @ " + in.position());
                }
            }
        }

        // ---- IK 约束时间轴 ----
        // ⚠️ 这些不是"可跳过"的装饰：方舟把约束强度做成动画，setup 里 mix=0、
        //    Idle/Attack 里把 mix 抬到 1。以前只读掉不应用，表现就是"该被约束拉过去的
        //    武器/烟斗原地不动"，整只干员看起来是散的。
        int ikCount = in.readVarInt();
        for (int i = 0; i < ikCount; i++) {
            int ikIndex = in.readVarInt();
            int frameCount = in.readVarInt();
            float[] frames = new float[frameCount];
            float[] mix = new float[frameCount];
            int[] bend = new int[frameCount];
            float[] curves = new float[frameCount * SpineCurves.STRIDE];
            for (int frame = 0; frame < frameCount; frame++) {
                frames[frame] = in.readFloat();
                mix[frame] = in.readFloat();
                if (v38) {
                    in.readFloat();             // softness（软约束，本实现按 0 处理）
                }
                bend[frame] = in.readByteSigned();
                if (v38) {
                    in.readBoolean();           // compress
                    in.readBoolean();           // stretch
                }
                if (frame < frameCount - 1) {
                    readCurve(curves, frame);
                }
                duration = Math.max(duration, frames[frame]);
            }
            timelines.add(new SpineTimeline.Ik(ikIndex, frames, mix, bend, curves));
        }

        // ---- transform 约束时间轴（一帧四通道：rotate/translate/scale/shear）----
        int transformCount = in.readVarInt();
        for (int i = 0; i < transformCount; i++) {
            int tcIndex = in.readVarInt();
            int frameCount = in.readVarInt();
            float[] frames = new float[frameCount];
            float[] rot = new float[frameCount];
            float[] trans = new float[frameCount];
            float[] scale = new float[frameCount];
            float[] shear = new float[frameCount];
            float[] curves = new float[frameCount * SpineCurves.STRIDE];
            for (int frame = 0; frame < frameCount; frame++) {
                frames[frame] = in.readFloat();
                rot[frame] = in.readFloat();
                trans[frame] = in.readFloat();
                scale[frame] = in.readFloat();
                shear[frame] = in.readFloat();
                if (frame < frameCount - 1) {
                    readCurve(curves, frame);
                }
                duration = Math.max(duration, frames[frame]);
            }
            timelines.add(new SpineTimeline.TransformMix(tcIndex, frames, rot, trans, scale, shear, curves));
        }

        // ---- path 约束时间轴（只跳过）----
        int pathCount = in.readVarInt();
        for (int i = 0; i < pathCount; i++) {
            in.readVarInt();                    // pathConstraintIndex
            int timelineCount = in.readVarInt();
            for (int ii = 0; ii < timelineCount; ii++) {
                int timelineType = in.readByteUnsigned();
                int frameCount = in.readVarInt();
                int channels = timelineType == 2 ? 2 : 1; // 0/1 = position/spacing（1 值），2 = mix（2 值）
                for (int frame = 0; frame < frameCount; frame++) {
                    float time = in.readFloat();
                    for (int c = 0; c < channels; c++) {
                        in.readFloat();
                    }
                    if (frame < frameCount - 1) {
                        skipCurve();
                    }
                    duration = Math.max(duration, time);
                }
            }
        }

        // ---- deform（网格形变）----
        int deformSkinCount = in.readVarInt();
        for (int i = 0; i < deformSkinCount; i++) {
            int skinIndex = in.readVarInt();
            int deformSlotCount = in.readVarInt();
            for (int ii = 0; ii < deformSlotCount; ii++) {
                int slotIndex = in.readVarInt();
                int deformAttCount = in.readVarInt();
                for (int iii = 0; iii < deformAttCount; iii++) {
                    String attachmentName = v38 ? readStringRef() : in.readString();
                    SpineAttachment attachment = null;
                    if (skinIndex >= 0 && skinIndex < skins.size()) {
                        attachment = skins.get(skinIndex).get(slotIndex, attachmentName);
                    }
                    boolean weighted = attachment != null && attachment.bones != null;
                    int deformLength = attachment != null ? attachment.deformLength : 0;
                    int frameCount = in.readVarInt();
                    float[] frames = new float[frameCount];
                    float[][] deforms = new float[frameCount][];
                    float[] curves = new float[frameCount * SpineCurves.STRIDE];
                    for (int frame = 0; frame < frameCount; frame++) {
                        frames[frame] = in.readFloat();
                        int end = in.readVarInt();
                        float[] deform = new float[deformLength];
                        if (end != 0) {
                            int start = in.readVarInt();
                            end += start;
                            // 注意：**一定要把窗口内的 float 读完**，否则后面的字节会整体错位。
                            // 即使附件找不到（deformLength == 0）也要照样读掉。
                            for (int v = start; v < end; v++) {
                                float value = in.readFloat();
                                if (v >= 0 && v < deform.length) {
                                    deform[v] = value;
                                }
                            }
                            if (!weighted && attachment != null && attachment.localXy != null) {
                                for (int v = 0; v < deform.length; v++) {
                                    deform[v] += attachment.localXy[v];
                                }
                            } else if (attachment == null) {
                                dbg("deform 找不到附件，丢弃: slot=" + slotIndex + " name=" + attachmentName);
                            }
                        } else if (!weighted && attachment != null && attachment.localXy != null) {
                            System.arraycopy(attachment.localXy, 0, deform, 0, deform.length);
                        }
                        deforms[frame] = deform;
                        if (frame < frameCount - 1) {
                            readCurve(curves, frame);
                        }
                    }
                    if (attachment != null && attachment.renderable()) {
                        timelines.add(new SpineTimeline.Deform(slotIndex, attachment, frames, deforms, curves));
                    }
                    duration = Math.max(duration, last(frames));
                }
            }
        }

        // ---- 绘制顺序 ----
        int drawOrderFrameCount = in.readVarInt();
        if (drawOrderFrameCount > 0) {
            float[] frames = new float[drawOrderFrameCount];
            int[][] orders = new int[drawOrderFrameCount][];
            for (int frame = 0; frame < drawOrderFrameCount; frame++) {
                frames[frame] = in.readFloat();
                int drawCount = in.readVarInt();
                int slotCount = slots.size();
                if (drawCount < 0 || drawCount > slotCount) {
                    throw new IOException("drawOrder 条目数异常: " + drawCount + "（插槽 " + slotCount + "）");
                }
                // 文件里存的是"没被点名的插槽"与"被点名的插槽 + 新位置"的压缩形式。
                //
                // ⚠️ 解码必须逐行照官方的 SkeletonBinary.readAnimation：
                //     drawOrder[originalIndex + offset] = originalIndex
                // 也就是"把第 originalIndex 号插槽放到第 originalIndex+offset 个绘制位"。
                // 以前写成了 drawOrder[i1] = order + n1（位置和值都错），还原出来的排列是乱的：
                // 头发/衣领/披风被画到脸前面 —— 这就是"面部和服饰看起来凌乱"的直接原因。
                int[] drawOrder = new int[slotCount];
                for (int s = 0; s < slotCount; s++) {
                    drawOrder[s] = -1;
                }
                int[] unchanged = new int[slotCount - drawCount];
                int originalIndex = 0;
                int unchangedIndex = 0;
                for (int ii = 0; ii < drawCount; ii++) {
                    int slotIndex = in.readVarInt();
                    while (originalIndex != slotIndex) {
                        unchanged[unchangedIndex++] = originalIndex++;
                    }
                    drawOrder[originalIndex + in.readVarInt()] = originalIndex;
                    originalIndex++;
                }
                while (originalIndex < slotCount) {
                    unchanged[unchangedIndex++] = originalIndex++;
                }
                // 空位按"未被点名的插槽"从后往前回填（与官方同一个循环方向）
                for (int ii = slotCount - 1; ii >= 0; ii--) {
                    if (drawOrder[ii] == -1) {
                        drawOrder[ii] = unchanged[--unchangedIndex];
                    }
                }
                // 最后只做"是不是合法排列"的校验：不合法就退回默认顺序并报一次，
                // 绝不再自作聪明地"补位"（补位会掩盖解码错误，让画面错得很隐蔽）。
                if (!isPermutation(drawOrder, slotCount)) {
                    dbg("drawOrder 帧 " + frame + " 解出来不是合法排列，退回默认顺序");
                    for (int ii = 0; ii < slotCount; ii++) {
                        drawOrder[ii] = ii;
                    }
                }
                orders[frame] = drawOrder;
                duration = Math.max(duration, frames[frame]);
            }
            timelines.add(new SpineTimeline.DrawOrder(frames, orders));
        }

        // ---- 事件 ----
        int eventFrameCount = in.readVarInt();
        if (eventFrameCount > 0) {
            for (int frame = 0; frame < eventFrameCount; frame++) {
                float time = in.readFloat();
                int eventIndex = in.readVarInt();    // event 下标
                in.readVarIntZigZag();               // intValue
                in.readFloat();                      // floatValue
                if (in.readBoolean()) {
                    in.readString();                 // 覆盖 stringValue
                }
                if (v38 && eventIndex >= 0 && eventIndex < eventHasAudio.size()
                        && eventHasAudio.get(eventIndex)) {
                    // 3.8：该事件定义了 audioPath 时，关键帧里还带 volume / balance
                    in.readFloat();
                    in.readFloat();
                }
                duration = Math.max(duration, time);
            }
        }

        return new SpineAnimation(name, duration, timelines.toArray(new SpineTimeline[0]));
    }

    private static float last(float[] values) {
        return values.length == 0 ? 0f : values[values.length - 1];
    }

    /** {@code readCurve}：1 字节类型 + 可选 4 个 float 控制点。两版编码相同。 */
    private void readCurve(float[] curves, int frame) {
        int type = in.readByteUnsigned();
        if (type == SpineCurves.STEPPED) {
            SpineCurves.stepped(curves, frame);
        } else if (type == SpineCurves.BEZIER) {
            float cx1 = in.readFloat();
            float cy1 = in.readFloat();
            float cx2 = in.readFloat();
            float cy2 = in.readFloat();
            SpineCurves.bezier(curves, frame, cx1, cy1, cx2, cy2);
        }
        // type == 0（linear）：曲线表保持全 0，即线性
    }

    /** 跳过一段曲线数据（不关心的时间轴用）。 */
    private void skipCurve() {
        int type = in.readByteUnsigned();
        if (type == SpineCurves.BEZIER) {
            in.readFloat();
            in.readFloat();
            in.readFloat();
            in.readFloat();
        }
    }

    /** {@code rgba8888} → 0..1 的 RGBA（与 gdx {@code Color.rgba8888ToColor} 同序：R 在高字节）。 */
    private static float[] readRgba(int rgba8888) {
        float[] out = new float[4];
        out[0] = (rgba8888 >>> 24 & 0xFF) / 255f;
        out[1] = (rgba8888 >>> 16 & 0xFF) / 255f;
        out[2] = (rgba8888 >>> 8 & 0xFF) / 255f;
        out[3] = (rgba8888 & 0xFF) / 255f;
        return out;
    }
}
