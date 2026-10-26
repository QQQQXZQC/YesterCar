package com.yestercar.runtime.spine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 极简二进制读取器，语义逐条对齐官方 {@code com.badlogic.gdx.utils.DataInput} +
 * 3.8 的 {@code SkeletonBinary$SkeletonInput}。
 *
 * <p>为什么要自己写而不直接用 {@code DataInputStream}：spine 的二进制里有大量
 * 「变长整数 / 可选字符串」编码，gdx 用自己的一套；同时我需要 {@link #position()} 级别的
 * 诊断（"读到第几字节解析错"），这在流上是拿不到的。
 *
 * <h2>基本类型（来自 {@code gdx.utils.DataInput} 反汇编）</h2>
 * <ul>
 *   <li>{@code readFloat()} / {@code readInt()}：继承自 {@code java.io.DataInputStream}，
 *       即 <b>大端</b> 4 字节；float 就是 {@code Float.intBitsToFloat(大端int)}；</li>
 *   <li>{@code readShort()}：大端 2 字节；{@code readByte()}：1 字节（有符号）；</li>
 *   <li>{@code readInt(optimizePositive)}：7 位一组的变长整数（最多 5 组），
 *       {@code optimizePositive == false} 时再做 zigzag 还原
 *       （{@code (v >>> 1) ^ -(v & 1)}）。</li>
 * </ul>
 *
 * <h2>字符串：3.5 与 3.8 的长度语义不同</h2>
 * <ul>
 *   <li>长度前缀都是同一个变长整数：0 = {@code null}，1 = 空串，否则长度再减 1；</li>
 *   <li><b>3.5</b>（gdx {@code DataInput.readString}）：减 1 后是<b>字符数</b>，
 *       每个字符按 1/2/3 字节解（和 3.8 的字节序列规则一样，但循环按"字符"推进）；</li>
 *   <li><b>3.8</b>（{@code SkeletonInput.readString}）：减 1 后是<b>字节数</b>，
 *       循环按"字节"推进（2 字节头推进 2、3 字节头推进 3）。</li>
 * </ul>
 * 对纯 ASCII 名字两者完全等价（本工程 29 个模型的名字全是 ASCII），但这里仍按版本分开实现，
 * 以免将来素材里出现非 ASCII 名字时静默错位。
 */
final class SpineBinaryReader {

    private final byte[] data;
    private int pos;
    private final boolean stringByCharCount;

    /**
     * @param data             文件全部字节
     * @param stringByCharCount {@code true} = 3.5 语义（长度是字符数），
     *                          {@code false} = 3.8 语义（长度是字节数）
     */
    SpineBinaryReader(byte[] data, boolean stringByCharCount) {
        this.data = data;
        this.pos = 0;
        this.stringByCharCount = stringByCharCount;
    }

    int position() {
        return pos;
    }

    int length() {
        return data.length;
    }

    void seek(int newPos) {
        this.pos = newPos;
    }

    /** 无符号 1 字节（-1 表示 EOF，抛异常）。 */
    int readUByte() {
        if (pos >= data.length) {
            throw eof(1);
        }
        return data[pos++] & 0xFF;
    }

    /** 有符号 1 字节，用于 bendDirection 这类可能是 -1 的字段。 */
    int readByteSigned() {
        return (byte) readUByte();
    }

    /** 无符号 1 字节，用于附件类型码 / 时间轴类型码。 */
    int readByteUnsigned() {
        return readUByte();
    }

    boolean readBoolean() {
        return readUByte() != 0;
    }

    /** 大端 4 字节整数（颜色 {@code rgba8888} 用这个）。 */
    int readInt() {
        require(4);
        int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    /** 大端 4 字节 IEEE-754（{@code DataInputStream.readFloat} 语义）。 */
    float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    /** 大端 2 字节。 */
    short readShort() {
        require(2);
        short v = (short) (((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF));
        pos += 2;
        return v;
    }

    /** 非负变长整数（{@code readInt(true)}）。 */
    int readVarInt() {
        int b = readUByte();
        int result = b & 0x7F;
        if ((b & 0x80) != 0) {
            b = readUByte();
            result |= (b & 0x7F) << 7;
            if ((b & 0x80) != 0) {
                b = readUByte();
                result |= (b & 0x7F) << 14;
                if ((b & 0x80) != 0) {
                    b = readUByte();
                    result |= (b & 0x7F) << 21;
                    if ((b & 0x80) != 0) {
                        b = readUByte();
                        result |= (b & 0x7F) << 28;
                    }
                }
            }
        }
        return result;
    }

    /** zigzag 变长整数（{@code readInt(false)}）。 */
    int readVarIntZigZag() {
        int v = readVarInt();
        return (v >>> 1) ^ -(v & 1);
    }

    /**
     * 读字符串：变长长度前缀 + 3.5/3.8 各自的字符/字节语义。
     *
     * @return 字符串；{@code null} 表示文件里存的是"空引用"
     */
    String readString() {
        int len = readVarInt();
        if (len == 0) {
            return null;
        }
        if (len == 1) {
            return "";
        }
        len--;
        return stringByCharCount ? readCharsByCount(len) : readBytesThenDecode(len);
    }

    private String readCharsByCount(int charCount) {
        char[] chars = new char[charCount];
        int i = 0;
        while (i < charCount) {
            int b = readUByte();
            int hi = b >> 4;
            if (hi == 12 || hi == 13) {
                chars[i] = (char) (((b & 0x1F) << 6) | (readUByte() & 0x3F));
            } else if (hi == 14) {
                chars[i] = (char) (((b & 0x0F) << 12) | ((readUByte() & 0x3F) << 6) | (readUByte() & 0x3F));
            } else {
                chars[i] = (char) b;
            }
            i++;
        }
        return new String(chars);
    }

    private String readBytesThenDecode(int byteCount) {
        require(byteCount);
        String s = new String(data, pos, byteCount, StandardCharsets.UTF_8);
        pos += byteCount;
        return s;
    }

    /** {@code readFloatArray}：连续 count 个 float（大端）。 */
    float[] readFloatArray(int count) {
        if (count < 0) {
            throw corrupt("readFloatArray 数量为负: " + count);
        }
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = readFloat();
        }
        return out;
    }

    /** {@code readShortArray}：变长数量前缀 + 若干大端 short。 */
    short[] readShortArray() {
        int n = readVarInt();
        if (n < 0 || n > data.length) {
            throw corrupt("readShortArray 数量异常: " + n + " @ " + pos);
        }
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            out[i] = readShort();
        }
        return out;
    }

    private static UncheckedIOException corrupt(String message) {
        return new UncheckedIOException(new IOException(message));
    }

    private void require(int n) {
        if (n < 0 || pos + n > data.length) {
            throw eof(n);
        }
    }

    private UncheckedIOException eof(int n) {
        return new UncheckedIOException(new IOException(
                "读越界：需要 " + n + " 字节，pos=" + pos + "，文件长度=" + data.length));
    }
}
