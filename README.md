# YesterCar

明日方舟式关卡设计器与基础战斗 —— 课程设计项目。

数据驱动的 2D 网格塔防原型：**地图编辑器**编辑静态地形，**关卡编辑器**配置波次与规则，
**战斗运行时**执行带权 Dijkstra 动态寻路、障碍放置、干员部署与战斗结算。

---

## 快速开始（新队友两步）

```powershell
tools\install-g4p.cmd     # 首次：装 G4P（只需一次）
tools\build.cmd           # 构建 + 跑测试
```

`tools\build.cmd` 会自动把 `JAVA_HOME` 切到 JDK 17 —— 本机系统默认的 `JAVA_HOME`
指向 JDK 11，Processing 4 用不了。

运行主程序：

```powershell
java -jar target\YesterCar.jar          # 启动动画 → 主菜单
java -jar target\YesterCar.jar --check  # 无窗口环境自检，排查问题用
```

或在 IDEA 里直接运行 `com.yestercar.Main`。

### 在 IDEA 里报「程序包 g4p_controls 不存在」？

说明 `tools\install-g4p.cmd` 没跑过。G4P 不在任何 Maven 仓库上，必须从仓库自带的
jar 装入本地仓库。这个脚本会同时装入**项目内仓库**（命令行用）和**全局 `~/.m2`**
（IDEA 默认用），装完在 IDEA 里执行 **Maven 工具窗口 → Reload All Maven Projects**。

详见 `docs/具体设计.md` §1.5-③。

---

## 技术栈

| 项 | 选型 |
| --- | --- |
| 构建 | Maven 3.9.9 |
| JDK | **17**（Processing 4 要求） |
| 渲染 | `org.processing:core:4.5.6` |
| UI 控件 | G4P 4.3.11（不在任何 Maven 仓库上，随仓库携带） |
| 测试 | JUnit 5 |
| 打包 | `maven-shade-plugin` → fat-jar；`jpackage` → 含内嵌 JRE 的 exe |

---

## 目录结构

```text
YesterCar/
  pom.xml                    构建配置
  .mvn/maven.config          固定项目内本地仓库（纯 ASCII，勿写中文）
  .mvn/repo/                 项目内本地仓库（gitignore，脚本可重建）
  tools/                     构建脚本
  third_party/G4P/           G4P 4.3.11 官方完整包（必须入库）
  docs/                      策划方案.md + 具体设计.md（只有这两份）
  src/main/java/com/yestercar/
    Main.java                入口 + --check 自检
    splash/                  启动动画
    launcher/                主菜单
    mapeditor/               地图编辑器
    leveleditor/             关卡编辑器
    runtime/                 战斗运行时
    common/                  公共类：Workspace / Director / UITheme / AssetPaths
  src/main/resources/data/   只读数据：enemies.json、operators.json、textures/
  data/                      可写数据：maps/、levels/（运行时产出）
  src/test/java/             JUnit 测试
```

**两类 `data/` 的区别**（重要，别放错）：

| 类别 | 位置 | 说明 |
| --- | --- | --- |
| 只读默认数据 | `src/main/resources/data/` | 打包进 jar；敌人/干员数据、贴图 |
| 用户产出数据 | `data/` | 运行时读写；地图、关卡 JSON |

---

## 当前进度

第一阶段「可编译可运行的空骨架」已完成：`mvn clean test` 10 个用例全绿，
启动动画 → 主菜单链路实测通过，fat-jar 可独立运行。

下一步按 `docs/具体设计.md` 第 6.2 节的路线图推进：
**P0**（双入口 + jar 目录风险验证）→ **P1**（编辑器与运行时各自起手，两人并行）。

尚未实现：双入口、战役结构、窗口互相唤起、全部游戏业务规则、`jpackage` 打 exe。

---

## 文档（只有两份）

| 文档 | 内容 |
| --- | --- |
| `docs/策划方案.md` | 游戏规则、数据契约、类职责、数值、排期 |
| `docs/具体设计.md` | **动手前先读这份**：环境与踩坑、架构与包结构、技术实现要点、素材接口、开发路线图 |

---

## 给协作者的三条硬性约定

1. **源文件一律 UTF-8 无 BOM**——BOM 会导致编译失败。
2. **`.mvn/maven.config` 与 `tools/*.cmd` 保持纯 ASCII**——它们按平台字符集（GBK）解码，
   写中文会让 Maven 直接启动失败。
3. **`.gitignore` 里绝不能加 `*.jar`**——`third_party/G4P/library/G4P.jar` 是本项目
   唯一的 G4P 来源。
