package com.yestercar.runtime.battle;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敌人的固定路线（{@code data/enemy_paths.json}）。
 *
 * <p>目前的唯一用途是 MS-8 的爱国者：出生后在红门前一格停留 20 秒，
 * 然后沿一组手写航点行进，而不是走流场默认寻路。
 */
final class EnemyPaths {

    /** 一条固定路线。 */
    record Path(float delay, List<int[]> cells, int[] stopAt) {
    }

    private static final Map<String, Path> PATHS = new HashMap<>();
    private static boolean loaded;

    private EnemyPaths() {
    }

    /**
     * @param enemyId 敌人 id
     * @return 固定路线；没有配置返回 {@code null}
     */
    static Path of(String enemyId) {
        ensureLoaded();
        return PATHS.get(enemyId);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try (InputStream in = EnemyPaths.class.getResourceAsStream("/data/enemy_paths.json")) {
            if (in == null) {
                return;
            }
            String txt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            processing.data.JSONObject root = processing.data.JSONObject.parse(txt);
            processing.data.JSONObject paths = root.getJSONObject("paths");
            if (paths == null) {
                return;
            }
            for (Object k : paths.keys()) {
                String id = String.valueOf(k);
                processing.data.JSONObject one = paths.getJSONObject(id);
                if (one == null) {
                    continue;
                }
                float wait = one.getFloat("delay");
                processing.data.JSONArray arr = one.getJSONArray("cells");
                java.util.ArrayList<int[]> cells = new java.util.ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        processing.data.JSONArray c = arr.getJSONArray(i);
                        cells.add(new int[]{c.getInt(0), c.getInt(1)});
                    }
                }
                int[] stopAt = null;
                processing.data.JSONArray sa = one.getJSONArray("stopAt");
                if (sa != null && sa.size() >= 2) {
                    stopAt = new int[]{sa.getInt(0), sa.getInt(1)};
                }
                PATHS.put(id, new Path(wait, cells, stopAt));
            }
        } catch (Exception ex) {
            System.out.println("[EnemyPaths] 读取 enemy_paths.json 失败: " + ex);
        }
    }
}
