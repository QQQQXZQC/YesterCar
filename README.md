# YesterCar

明日方舟式关卡设计器与基础战斗 —— 课程设计项目。

数据驱动的 2D 网格塔防原型：**地图编辑器**编辑静态地形，**关卡编辑器**配置波次与规则，
**战斗运行时**执行带权 Dijkstra 动态寻路、障碍放置、干员部署与战斗结算。

---

## 快速开始（新队友两步）

```powershell
tools\install-g4p.cmd     # 首次：把 G4P 装入项目内本地仓库（只需一次）
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
  docs/                      策划方案 / 交接文档 / 阶段规划 / 素材接口说明
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

尚未实现：模块窗口互相唤起、`jpackage` 打 exe、以及全部游戏业务规则。
详见 `docs/第一阶段规划.md` 第十节。

---

## 文档

| 文档 | 内容 |
| --- | --- |
| `docs/策划方案.md` | 完整策划：规则、数据契约、类设计、包结构、排期 |
| `docs/开发交接文档.md` | **环境踩坑清单与已实测事实**——动手前先读这份 |
| `docs/第一阶段规划.md` | 第一阶段的规划与验收记录 |
| `docs/素材接口说明.md` | 美术/平面素材的目录、尺寸、命名规范 |

---

## 给协作者的三条硬性约定

1. **源文件一律 UTF-8 无 BOM**——BOM 会导致编译失败。
2. **`.mvn/maven.config` 与 `tools/*.cmd` 保持纯 ASCII**——它们按平台字符集（GBK）解码，
   写中文会让 Maven 直接启动失败。
3. **`.gitignore` 里绝不能加 `*.jar`**——`third_party/G4P/library/G4P.jar` 是本项目
   唯一的 G4P 来源。
