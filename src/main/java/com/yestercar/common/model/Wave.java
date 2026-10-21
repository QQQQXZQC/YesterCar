package com.yestercar.common.model;

import java.util.List;

/**
 * 一个波次：在某个时刻放出一组或多组敌人。
 *
 * <p>契约见策划 §3.2 的 {@code waves} 数组。
 *
 * @param time   触发时刻（秒）
 * @param spawns 这一波里的出怪组；一组对应一道红门与一道目标蓝门
 */
public record Wave(float time, List<EnemySpawn> spawns) {

    /** 紧凑构造器：时间不为负、列表不可变。 */
    public Wave {
        time = Math.max(0f, time);
        spawns = spawns == null ? List.of() : List.copyOf(spawns);
    }

    /**
     * @return 这一波总共放多少个敌人
     */
    public int totalCount() {
        int n = 0;
        for (EnemySpawn s : spawns) {
            n += s.count();
        }
        return n;
    }

    /**
     * @return 供日志与测试用的一行摘要
     */
    public String summary() {
        return String.format("t=%.1fs 共 %d 只（%d 组）", time, totalCount(), spawns.size());
    }

    /**
     * 一个出怪组。
     *
     * <p>⚠️ {@code door} 与 {@code targetBlueDoor} 引用的是**地图里的门 ID**。
     * 策划 §6.2 的联动规则第 2 条要求「引用不存在的门 ID → 保存时拦截」，
     * 那条校验属于 {@code CampaignValidator}（P4.1）。
     *
     * @param enemy          敌人类型 id（对应 {@code enemies.json} 里的 {@code id}）
     * @param door           出发的红门 id
     * @param targetBlueDoor 目标蓝门 id
     * @param count          数量
     * @param interval       出怪间隔（秒）
     */
    public record EnemySpawn(String enemy, int door, int targetBlueDoor,
                             int count, float interval) {

        /** 紧凑构造器：数量至少 1、间隔为正。 */
        public EnemySpawn {
            enemy = enemy == null ? "" : enemy;
            count = Math.max(1, count);
            interval = Math.max(0.01f, interval);
        }

        /**
         * @return 供日志与测试用的一行摘要
         */
        public String summary() {
            return String.format("%s×%d 红门%d→蓝门%d 间隔%.1fs",
                    enemy, count, door, targetBlueDoor, interval);
        }
    }
}
