package com.yestercar.common.model;

/**
 * 关卡规则参数（策划 §3.2 除去 {@code map} 与 {@code waves} 的部分）。
 *
 * <p>这些数值原本放在 {@code level_xxx.json} 里。既然"一个战役 = 一张地图"
 * （见 {@link CampaignMeta} 的说明），它们就并进了 {@code campaign.json} ——
 * 否则会出现"一个战役对应一个永远只有一份的关卡文件"这种把一张表拆成两个文件的结构。
 *
 * <p>字段名与策划 §3.2 <strong>逐字保持一致</strong>，这样文档与代码不用互相翻译。
 *
 * @param initialCost           初始费用
 * @param costPerSecond         每秒回费
 * @param obstacleCost          障碍费用
 * @param obstacleHp            障碍 HP
 * @param obstacleBlock         障碍阻挡数
 * @param obstacleCount         障碍初始数量
 * @param obstacleRedeployTime  障碍再部署（全局冷却，秒）
 */
public record LevelParams(int initialCost, float costPerSecond,
                          int obstacleCost, int obstacleHp, int obstacleBlock,
                          int obstacleCount, float obstacleRedeployTime,
                          int life) {

    /** 策划 §2.4 / §2.6 的默认值。新建关卡时用它。 */
    public static final LevelParams DEFAULT =
            new LevelParams(10, 1f, 3, 100, 30, 9, 5f, 3);

    /**
     * 紧凑构造器：把明显非法的值拉回合理范围。
     *
     * <p>手工改坏 JSON 是常见情形（策划 §3.2 的字段多、容易打错），
     * 与其让运行时算出负数费用，不如在这里兜住。
     */
    public LevelParams {
        initialCost = Math.max(0, initialCost);
        costPerSecond = Math.max(0f, costPerSecond);
        obstacleCost = Math.max(0, obstacleCost);
        obstacleHp = Math.max(1, obstacleHp);
        life = Math.max(1, life);
        obstacleBlock = Math.max(0, obstacleBlock);
        obstacleCount = Math.max(0, obstacleCount);
        obstacleRedeployTime = Math.max(0f, obstacleRedeployTime);
    }

    /**
     * @return 供日志与测试用的一行摘要
     */
    public String summary() {
        return String.format("初始费用 %d  每秒回费 %.1f  障碍 %d费/%dHP/阻挡%d/%d个/冷却%.0fs",
                initialCost, costPerSecond, obstacleCost, obstacleHp,
                obstacleBlock, obstacleCount, obstacleRedeployTime);
    }
}
