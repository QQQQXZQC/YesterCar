package com.yestercar.runtime.spine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一套皮肤：{@code 插槽下标 + 附件名 → 附件}。
 *
 * <p>本工程 29 个素材都只有一套 default 皮肤，所以这里把所有皮肤合并成一张表
 * （后读到的覆盖先读到的）。3.8 的皮肤可以引用骨骼/约束白名单，本项目用不到，解析时读掉即可。
 */
final class SpineSkin {

    final String name;

    private final Map<Integer, Map<String, SpineAttachment>> bySlot = new LinkedHashMap<>();

    SpineSkin(String name) {
        this.name = name;
    }

    void put(int slotIndex, String attachmentName, SpineAttachment attachment) {
        bySlot.computeIfAbsent(slotIndex, k -> new LinkedHashMap<>()).put(attachmentName, attachment);
    }

    SpineAttachment get(int slotIndex, String attachmentName) {
        Map<String, SpineAttachment> map = bySlot.get(slotIndex);
        return map == null ? null : map.get(attachmentName);
    }


    /** 全部附件。 */
    Collection<SpineAttachment> all() {
        List<SpineAttachment> out = new ArrayList<>();
        for (Map<String, SpineAttachment> map : bySlot.values()) {
            out.addAll(map.values());
        }
        return out;
    }

    int size() {
        int n = 0;
        for (Map<String, SpineAttachment> map : bySlot.values()) {
            n += map.size();
        }
        return n;
    }
}
