# 昨日圆车（YesterCar）

> 基于《明日方舟》玩法的**塔防二次创作**课程设计作品
> Java 17 + Processing 4 实现，包含可玩的塔防本体与一套关卡编辑器

**本项目为非官方同人作品，无鹰角网络授权，仅用于高校课程设计与个人学习研究，禁止任何形式的商业使用。** 详见文末[版权免责声明](#八版权免责声明)。

`Java 17` · `Processing 4.5.6` · `Maven` · `Windows 11 x64` · `MIT（仅原创代码）`

---

## 一、📖 项目概述

《昨日圆车》是一次「把塔防游戏的完整链路做一遍」的课程设计实践：从开场动画、代号输入、主菜单、作战选择、干员编队，到战斗内的费用回复、阻挡判定、索敌、伤害结算、波次调度、暂停与结算，再到一整套可视化关卡编辑器。

技术上刻意避开游戏引擎，使用 **Java + Processing** 手工实现：

| 模块 | 实现方式 |
| --- | --- |
| 界面 | 自绘（设计稿 1440×810），`DesignSpace` 做等比坐标换算 |
| 战斗场景 | 伪 3D 斜视角，相机投影 + 纹理映射 |
| 角色动画 | **自研 Spine 骨骼运行时**（解析 `.skel` / `.atlas`，无第三方动画库） |
| 寻路 | 流场（Flow Field）+ 航点链 |
| 数据 | JSON / CSV，全部走 `src/main/resources` 与 `data/` 目录 |

项目规模：约 **110 个 Java 类**、**29 名可用干员**、**13 种敌人**、**8 个可通关关卡**。

---

## 二、✨ 功能介绍

### 🎮 2.1 游戏本体

- **开场流程**：Loading → 代号输入 → 主菜单，含逐字出场、音乐切换
- **主菜单**：立绘轮播、等级环、助理播报、音量控制（0–100% 五档）
- **作战选择**：8 个关卡以 M 形节点串连，Boss 关标红；右侧详情面板显示关卡名、简介与「准备作战」
- **编队**：12 格编队上限、职业分类筛选、右栏敌人构成一览（图标 + 数量）
- **干员一览**：6×2 网格翻页、立绘详情、7 项属性、特性说明（特性名 + 一句描述）

**战斗系统**

-  费用系统（初始费用、每秒回费、上限 99）
-  部署上限 10、撤退返还 50% 费用
-  **阻挡判定**：按 `blockWeight` 权重求和，权重 0 的单位不可阻挡
-  **索敌**：近战优先打自己阻挡的敌人，远程按路径进度选最近目标
-  **伤害公式**：物理 `max(atk − def, 5%·atk)`；法术 `max(atk×(1 − res/100), 5%·atk)`
-  **攻击弹体**：远程单位有飞行弹体，命中才结算（高阶术师为法术弹体）
-  **干员特性**：29 条（攻击倍率、阻挡覆盖、减益、治疗光环、吸血、暴击、叠层等）
-  暂停 / 继续 / 退出确认，结算卡片滑入滑出、星级评价
-  **音乐**：主菜单、关卡列表、战斗（4 首）、结算各用独立音轨

### 🛠️ 2.2 关卡编辑器

- 地形绘制（6 种地形）、红/蓝门放置（数量不限）、出生点标记
- 波次编辑：时间、敌人类型、数量、间隔、出门与目标门
- 地图尺寸 1–15 列 × 1–8 行（新建），加载兼容 9 行
- 通行性校验（洪水填充 + 门可达性）
- 撤销 / 重做、一键「测试」直接开战
- 编辑器与游戏共用同一套素材与战斗引擎

---

## 三、🧩 环境依赖

### 3.1 运行环境

| 项 | 要求 |
| --- | --- |
| 操作系统 | **Windows 11 x64**（当前仅在此环境测试通过） |
| JDK | **17**（必须，代码使用 record 等特性） |
| 内存 | 建议 4 GB 以上（素材约 215 MB） |
| 显卡 | 支持 OpenGL 2.0 以上（P2D 渲染） |

### 3.2 构建依赖

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `org.processing:core` | 4.5.6 | 渲染核心、JSON 解析 |
| `org.jogamp.jogl:jogl-all` | 2.6.0 | OpenGL 后端（P2D） |
| `org.jogamp.gluegen:gluegen-rt` | 2.6.0 | JOGL 原生绑定 |
| `g4p`（G4P.jar） | 4.3.11 | GUI 控件（**不在 Maven 仓库**，随 `third_party/` 提供） |
| Maven | 3.9.x | 构建 |

> JOGL / gluegen 还需要 `natives-windows-amd64` 的 native 包，已在 `.mvn/repo` 中准备。

---

## 四、🚀 复现运行流程

### 4.1 获取源码

```bash
git clone https://github.com/QQQQXZQC/YesterCar.git
cd YesterCar
```

### 4.2 准备素材目录

程序运行时读取 `<工程根>/data/`（图片、音乐、骨骼、用户战役）。素材体积约 215 MB，**不随源码包分发**，需按 `docs/` 内说明自行准备或从 Release 的演示包中获取。

```bash
# 方式 A：把 data/ 放在工程根（与 pom.xml 同级）
# 方式 B：用系统属性指向素材根目录
java -Dyestercar.home="D:/assets-root" -jar target/YesterCar.jar
```

### 4.3 构建

```bash
# Windows（CMD / PowerShell）
set JAVA_HOME=C:\Program Files\Java\jdk-17
mvn -B clean package
```

产物：`target/YesterCar.jar`

> 首次构建需联网下载依赖；`.mvn/maven.config` 已把本地仓库固定为 `.mvn/repo`。

### 4.4 运行

```bash
java -jar target/YesterCar.jar               # 游戏本体
java -jar target/YesterCar.jar --editor      # 关卡编辑器
java -jar target/YesterCar.jar --check       # 环境自检（不开窗口，建议先跑）
java -jar target/YesterCar.jar --play ms-1   # 直接进入指定关卡
```

### 4.5 在 IDEA 中运行

1. `File → Open` 选择工程根（含 `pom.xml`），等待 Maven 导入
2. `Project SDK` 设为 **17**
3. 新建两个 Run Configuration：

| 名称 | Main class | VM options | Working directory |
| --- | --- | --- | --- |
| Game | `com.yestercar.GameMain` | `-Dfile.encoding=UTF-8` | 含 `data/` 的目录 |
| Editor | `com.yestercar.EditorMain` | `-Dfile.encoding=UTF-8` | 同上 |

### 4.6 调试开关

```bash
-Dyestercar.trait.debug=1                # 干员特性日志
-Dyestercar.fx.debug=1                   # 攻击弹体日志
-Dyestercar.spine.debug=1                # 骨骼动画日志
-Dyestercar.ui.squad=mlynar,angelina     # 指定编队
-Dyestercar.ui.autodeploy=1              # 自动部署
```

---

## 五、📦 打包发布说明

### 5.1 已发布的预编译包

Release 中提供 **`YesterCar_0.1.0_x64.zip`**，为编译后的演示版本（含内置 JRE，免安装）：

```
dist/
├── YesterCar.exe            游戏本体（Game.ico）
├── YesterCarEditor.exe      关卡编辑器（Editor.ico）
├── app/                     共用 jar 与启动配置
├── runtime/                 内置 JRE 17
└── data/                    素材（两程序共用）
```

双击 `YesterCar.exe` 即可运行，**无需安装 JDK**。整个目录需一起移动或拷贝，`app/`、`runtime/`、`data/` 不能单独删除。

> GitHub 同时提供自动生成的 Source code (zip / tar.gz)，那是源码快照，不含素材与预编译程序。

### 5.2 自行打包

```bash
mvn -B clean package

"C:\Program Files\Java\jdk-17\bin\jpackage.exe" --type app-image \
  --name YesterCar --input target --main-jar YesterCar.jar \
  --main-class com.yestercar.GameMain \
  --icon data/imgs/ico/Game.ico --dest dist --app-version 0.1.0 \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options "-Dyestercar.home=$APPDIR/.."
```

编辑器把 `--name` 改为 `YesterCarEditor`、`--main-class` 改为 `com.yestercar.EditorMain`、`--icon` 改为 `Editor.ico`。
两者可合并到同一目录：共享 `app/YesterCar.jar`、`runtime/`、`data/`，仅 `.cfg` 中的 `main-class` 不同。

---

## 六、🔍 开发参考

本项目的部分设计思路与实现方式参考了以下开源/公开资料，**代码为独立编写**，未直接复制上述仓库源码：

| 参考对象 | 参考内容 |
| --- | --- |
| [YunYouJun/ak-ui](https://github.com/YunYouJun/ak-ui) | **UI 设计方案**：配色、圆角、间距、动效节奏与「相对单位（cqw/cqh）」的布局思路 |
| [sganggs/Stronghold-Protocol](https://github.com/sganggs/Stronghold-Protocol) | **同一套游戏机制的组织方式**（第三方同人项目）：阻挡权重求和与不可阻挡判定、物理/法术伤害公式与 5% 伤害下限、防御与法抗穿透的分层处理、索敌优先级。**这些规则源自《明日方舟》游戏本体，相关设计权利归 HYPERGRYPH 所有；本项目未复制其代码**，仅将其作为"该机制可被如何划分模块"的旁证 |
| [PRTS.Wiki](https://prts.wiki) | 干员 / 敌人的**公开数值与机制说明**，用于二次创作时的数值参考 |

**关于"参考"的性质，需要说明三层区别：**

1. **规则层**——伤害公式、5% 伤害下限、阻挡权重、索敌优先级等，属于《明日方舟》这款游戏的**玩法机制与数值设定**，由 HYPERGRYPH 设计并发布，相关权利归其所有。本项目只是把这些规则**用 Java 复现**，不对规则本身主张任何权利。
2. **表达层**——Stronghold-Protocol 是第三方同人开源项目，用 TypeScript 重新实现了同一套规则。本项目**未读取、未复制其源代码**，仅把它当作"这套机制可以如何被拆分与组织"的旁证；其开源许可证**不覆盖**《明日方舟》的规则与设定，本项目也不从中获得任何素材或内容授权。
3. **实现层**——`src/main/java` 下的全部代码是本项目独立编写的 Java 实现，采用 MIT 许可证（详见[第八节](#八版权免责声明)与 [LICENSE](LICENSE)）。

此外，上述资料中本项目**未参考**的部分包括：元素损伤与爆发机制、隐匿/反隐与起飞规避、技能系统、巨型单位跨格占位。

### 6.1 目录结构

```
YesterCar/
├── pom.xml
├── third_party/G4P/             G4P 控件库（Maven 仓库没有，随附）
├── .mvn/                        本地 Maven 仓库配置
├── data/                        素材：图片、音乐、骨骼、用户战役（单独分发）
└── src/main/
    ├── java/com/yestercar/
    │   ├── GameMain.java / EditorMain.java
    │   ├── gameopening/         开场、主菜单、作战列表、编队、干员页
    │   ├── campaigneditor/      编辑器（模型、命令、视图）
    │   ├── runtime/             战斗引擎、寻路、Spine 运行时、渲染
    │   └── common/              数据模型、IO、主题、工具
    └── resources/               干员、敌人、官方关卡、特性表等只读数据
```

### 6.2 关键实现说明

- **坐标系统** ：设计稿 1440×810，`DesignSpace` 用 `min(sx, sy)` 等比缩放并居中留边，保证窗口任意比例下文字不变形
- **固定步长** ：`BattleDirector.TICK = 1/30` 秒，外部 `dt` 经累加器换算，单帧最多推进 8 tick
- **Spine 运行时** ：自行解析二进制骨骼（`.skel`）、图集（`.atlas`）与时间轴，支持网格蒙皮、插槽隐藏、混合动画
- **寻路** ：每张地图按蓝门预计算流场，敌人沿场强下降方向移动；Boss 可配置手写航点覆盖默认路径

---

## 七、💛 鸣谢

发现做自己喜欢的东西是越做越来劲(bushi)。

做这个项目的过程中，得到过很多公开资料的帮助。在此致谢：

| 致谢对象 | 感谢什么 |
| --- | --- |
| [**YunYouJun/ak-ui**](https://github.com/YunYouJun/ak-ui) | 一套成体系的界面设计语言。配色、圆角、间距与「相对单位」的布局思路，让界面从"能看"变成"愿意多看一眼" |
| [**sganggs/Stronghold-Protocol**](https://github.com/sganggs/Stronghold-Protocol) | 把塔防的战斗规则拆成模块的示范。阻挡、伤害、索敌这些机制，因为看过它的组织方式才想清楚该怎么分层 |
| [**PRTS.Wiki**](https://prts.wiki) | 干员与敌人的公开数值、机制条目。没有这份资料，数值会全是拍脑袋 |
| **Processing 与 G4P 社区** | 让"不依赖游戏引擎也能画出 3D 斜视角战场"成为可能 |
| **《明日方舟》** | 原作本身的玩法设计与美术风格，是这个课程设计最初的、也是唯一的原因 |

> 以上致谢仅为表达感谢，**不表示任何一方对本项目的认可、授权或背书**，也不改变[第八节](#八版权免责声明)所声明的权利归属与使用限制。

---

## 八、版权免责声明

> ⚠️ 本节为项目的重要约束，请在下载、运行、二次分发前完整阅读。

### 8.1 权利归属

1. 本项目涉及的所有《明日方舟》相关**角色名称、敌方单位、主题视觉、背景、原画、立绘、音频、世界观设定**等知识产权与著作权，均归 **HYPERGRYPH / 上海鹰角网络科技有限公司** 所有。
2. 本项目为**非官方同人二次创作**，**未获得鹰角网络的任何授权、许可或认可**，与鹰角网络无任何隶属、合作或代理关系。

### 8.2 使用范围限制

3. 本项目**仅限高校课程设计、个人学习与研究**使用。
4. **严禁任何形式的商业使用**，包括但不限于：售卖、盈利、付费直播、广告变现、付费资源包、作为商业产品的一部分分发。
5. 使用者若自行提取、使用本项目内的方舟相关素材（图片、音频、骨骼等），**一切风险与责任由使用者自行承担**，与本项目作者无关。

### 8.3 关于素材

6. `data/` 目录下的立绘、Spine 骨骼、地形贴图、音乐等资源来自第三方，**不属于本项目原创内容**，仅作为课程学习演示使用。
7. 这些素材**不随开源代码授予任何使用权限**；本项目的开源许可证**不覆盖**任何来自《明日方舟》的美术、音频与 IP 相关素材。
8. 若权利方认为本仓库内容构成侵权，请通过 Issue 联系，作者将在第一时间删除相关内容。

### 8.4 环境与版本

9. 项目当前**仅在 Windows 11 x64 环境测试运行**，其他平台未经验证。

### 8.5 三部分内容的边界

为避免混淆，本项目内容明确分为三类：

| 类别 | 内容 | 权利状态 |
| --- | --- | --- |
| ✅ **【原创代码】** | `src/main/java` 下的全部 Java 代码、编辑器实现、Spine 运行时、战斗引擎、构建脚本 | 采用 **MIT** 许可证 |
| 📚 **【参考开源库】** | G4P（`third_party/`）、Processing、JOGL —— 第三方依赖 | 遵循各自原许可证 |
| ⚠️ **【鹰角 IP 素材】** | 角色立绘、骨骼、敌方立绘、音频、主题视觉、世界观设定 | 版权归鹰角网络，**不在 MIT 授权范围内** |

---

## PS

**另外本项目因为时间紧张、产能不足(bushi)等问题，出现了很多与原作不符的内容，包括但不限于：**

**没做子职业；**

**部分干员如“能天使”，立绘和名称使用的是“新约能天使”，但骨骼动画使用的为“能天使”原皮；**

**每个干员只做了一个特性/天赋，对应名称可能与原始设计不符，因为部分做不了异格所以套用了异格的天赋名称（感觉这样更好听）；**

**战役名称为“怒号光明”实际打的是爱国者；**

**设计师Enkidu-zhe声明战役关卡设计师自己能打过（**

**…………（此处省略n条，n大于等于1）**

**在此统一致歉。欢迎感兴趣的朋友在本项目基础上继续创作打磨（）**


