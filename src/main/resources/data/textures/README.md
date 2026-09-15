# 贴图目录 / Textures

本目录存放**只读美术素材**，按策划方案 §2.12 的规则加载：

| 情况 | 程序行为 |
| --- | --- |
| 贴图存在 | 画贴图 |
| 贴图不存在 | 画默认色块 |

**因此素材可以增量交付，缺失不影响程序运行。**

完整规范（命名、尺寸、格式、交付清单）见 `docs/具体设计.md` 第五部分。
代码侧的路径常量在 `com.yestercar.common.util.AssetPaths`。

## 命名硬性要求

文件名必须与 `enemies.json` / `operators.json` 里的 `id` **完全一致**，
全小写 + 下划线，后缀 `.png`。

```text
terrain/      每个 64x64 px，铺满，无透明边
  impassable.png     不可通行
  ground.png         可通行地面
  high.png           高台
  red_door.png       红门（敌人出生点）
  blue_door.png      蓝门（防守目标）

enemies/      64x64 px，内容居中，四周留 4~6 px 透明边
  soldier.png
  archer.png

operators/    64x64 px，按「朝右」画，程序自动旋转
  guard_01.png
  sniper_01.png
```

新增敌人/干员时：先在对应 JSON 里定 `id`，再按该 `id` 命名贴图。
