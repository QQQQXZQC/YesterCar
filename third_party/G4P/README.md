# third_party / G4P

本目录存放本项目的 **UI 组件库依赖**：G4P 4.3.11（官方完整发布包）。

## 为什么用它

课程方案 §2.13 原定使用 ControlP5，V1.4 修订后更换为 G4P。选型依据（均为本机 Processing 4.5.6 环境实测）：

| 对比项 | ControlP5 2.2.6 | G4P 4.3.11 |
| --- | --- | --- |
| 发布年份 / 维护状态 | 2015 年，已停止更新 | 持续更新，4.3.11 为当前版本 |
| Processing 4 兼容性 | 源码编译报 6 处错误，须打补丁 | **零修改直接可用** |
| 多窗口支持 | 需手工包装，第二窗口行为未经验证 | **`GWindow` 原生支持** |
| 数值输入 | 无专用控件 | **`GSpinner` 数值微调** |
| 全局配色 | 需逐控件 `setColor()` | **`GCScheme` 全局配色方案** |
| 中文字体 | 需逐窗口 `setFont()` | `G4P.setDisplayFont/setInputFont/setSliderFont` |
| 依赖获取 | 官方渠道不可达，需从源码编译 + 打补丁 | 官方完整发布包 |

关键的一条：**ControlP5 在 Processing 4 下 `PApplet.frame` 已被移除**，`ControlWindow.java` 有 6 处编译错误；
而 G4P 虽然同样使用 AWT（`java.awt.Frame`、`java.awt.FileDialog`），但取其窗口引用的方式不受该变更影响，**开箱即用**。

## 目录内容

| 路径 | 说明 |
| --- | --- |
| `library/G4P.jar` | 运行时依赖，255 KB |
| `library/changes.txt` | 官方版本变更记录（4.3.11 为最新） |
| `library.properties` | Processing 库元数据（`version=50`、`prettyVersion=4.3.11`、`categories=GUI`） |
| `src/` | 95 个文件：`g4p_controls` 源码 + 皮肤贴图（`src/data/*.png`） |
| `examples/` | 官方示例 28 个，含 `G4P_WindowsOnDemand`、`G4P_WindowsStarter`（多窗口） |
| `reference/` | 空 —— 官方发布包未附带 API 文档 |

> **未包含**：配套的 G4P GUI Builder（`g4ptool`，可视化拖拽设计表单的工具）。
> 它在 SourceForge 上单独发布，当前网络不可达。手写布局完全可行，不影响开发。

## 用法

把 jar 放进 sketch 的 `code/` 目录即可，**无需**安装到 Processing 的 `libraries/`，
这样课程提交物是自包含的：

```text
Arknights/
  Arknights.pde
  code/
    G4P.jar
```

```java
import g4p_controls.*;

void setup() {
  size(800, 600, JAVA2D);
  G4P.messagesEnabled(false);
  G4P.setGlobalColorScheme(GCScheme.BLUE_SCHEME);
  G4P.setDisplayFont("Microsoft YaHei", G4P.PLAIN, 15);   // 中文字体全局注入

  new GButton(this, 20, 20, 150, 32, "保存地图");
  GSpinner sp = new GSpinner(this, 20, 70, 140, 28);       // 数值输入
  sp.setValue(100);
  GDropList dl = new GDropList(this, 20, 110, 200, 90, 3);
  dl.setItems(new String[] { "士兵 soldier", "弓箭手 archer" }, 0);
}
```

### 多窗口（本项目架构的基础）

```java
GWindow win = GWindow.getWindow(this, "地图编辑器", 90, 90, 400, 250, JAVA2D);
win.setActionOnClose(G4P.HIDE_WINDOW);   // 关键：关窗只隐藏，不退出整个程序
win.addDrawHandler(this, "win_draw");
new GButton(win, 20, 20, 170, 32, "窗口内按钮");   // 控件挂到该窗口上
```

`setActionOnClose()` 的可选值：`G4P.KEEP_OPEN` / `G4P.HIDE_WINDOW` / `G4P.CLOSE_WINDOW` / `G4P.EXIT_APP`。

## 验证记录

在本机 Processing 4.5.6 + 自带 JDK 17.0.19 上实测，全部通过：

| # | 测试项 | 结果 |
| --- | --- | --- |
| 1 | jar 放 `code/` 能否编译 | ✅ 零错误 |
| 2 | 能否运行 | ✅ 输出 `G4P V4.3.11 created by Peter Lager` |
| 3 | 中文按钮 / 标签 / 输入框 / 下拉 | ✅ 截图确认无豆腐块 |
| 4 | `GWindow` 第二窗口 | ✅ 第二窗口正常绘制 |
| 5 | **sketch 存在 `data/` 目录时是否冲突** | ✅ **不冲突**（G4P 从 classpath 读皮肤，不受 sketch 的 `data/` 影响） |
| 6 | `--export` 打包 | ✅ `lib\G4P.jar` 被带上，`data/` 也被一并复制 |

第 5 项是重点：G4P 的 jar 内部自带 `data/` 皮肤目录，而本项目必须有 `Arknights/data/`，
实测证明二者不冲突。

## 已知注意点

1. **`GLabel` 在深色背景下默认对比度不足**，需显式设置文字颜色（由 `UITheme` 统一处理）。
2. `G4P.setDisplayFont/setInputFont` 只作用于 **G4P 控件**；sketch 里直接调用的 `text()` 仍需
   自己 `createFont()` + `textFont()`，且**每个窗口都要各建一次**。
3. 原始 `text()` 与 G4P 控件的绘制阶段不同：若要在 `saveFrame()` 中同时抓到两者，
   需把自己的回调注册在 G4P 之后（`registerMethod("post", ...)`）。

## 许可证

G4P 由 **Peter Lager** 开发，以 **GNU Lesser General Public License 2.1 或更高版本**发布。
源码头部保留原始版权声明。本目录为其官方发布包的原样副本，未作任何修改。
