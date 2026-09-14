# third_party / controlP5

本目录存放**已验证可用**的 ControlP5 依赖，供本项目（Processing 4.5.6）直接使用。

## 为什么需要它

课程方案 §2.13 要求 UI 库使用 **ControlP5**。但当前环境的实际情况是：

| 获取途径 | 结果 |
| --- | --- |
| Processing 贡献管理器（`api.processing.org`） | ✗ 网络不可达 |
| GitHub Releases（`github.com/sojamo/controlp5/releases`） | ✗ 连接超时 |
| Maven Central（`search.maven.org` 查 `controlp5`） | ✗ 无此构件（numFound=0） |
| 官方站点 `sojamo.de` → 源码 | ✓ 可达，但下载包指向 GitHub Releases，实际拉不到 |
| **raw.githubusercontent.com / cdn.jsdelivr.net（源码）** | ✓ **可用** |

因此做法是：从 jsDelivr 拉取 ControlP5 2.2.6 全部源码 → 打补丁 → 用 Processing 自带的
JDK 17 针对 `core-4.5.6.jar` 编译 → 产出 `controlP5-2.2.6-processing4.jar`。

## 文件说明

| 文件 | 说明 |
| --- | --- |
| `controlP5-2.2.6-processing4.jar` | 编译产物，150 个 class，放入 sketch 的 `code/` 目录即可 `import controlP5.*;` |
| `src/` | ControlP5 2.2.6 完整源码（77 个 .java，包名 `controlP5`），已含补丁 |
| `ControlWindow.java.patched` | 打过补丁的那个文件的副本，便于对照 |

## 针对 Processing 4 的补丁

ControlP5 2.2.6 发布于 2015 年，早于 Processing 4。直接编译会有 **6 处错误**，
全部集中在 `controlP5/ControlWindow.java`，原因是 Processing 4 删除了 `PApplet.frame`
字段与 `PApplet.setBounds()`：

1. `setUndecorated(boolean)` 中 5 处 `_myApplet.frame.*` / `_myApplet.setBounds(...)`
2. `setLocation(int,int)` 中 1 处 `_myApplet.frame.setLocation(...)`

补丁内容：新增一个从 PSurface 反查 AWT Frame 的辅助方法，替换上述调用。

```java
/** PATCH(processing4): resolve the AWT frame backing a PApplet surface. */
static java.awt.Frame getAWTFrame( PApplet theApplet ) {
    if ( theApplet == null ) return null;
    Object nativeSurface = theApplet.getSurface( ).getNative( );
    if ( nativeSurface instanceof processing.awt.PSurfaceAWT.SmoothCanvas ) {
        return ( ( processing.awt.PSurfaceAWT.SmoothCanvas ) nativeSurface ).getFrame( );
    }
    return null;
}
```

这两个方法只影响「无边框窗口」和「移动窗口位置」两个本项目用不到的特性。

## 验证记录

- **编译**：对 `core-4.5.6.jar` 编译 150 个 class，0 error（仅 deprecation 提示）。
- **运行**：Processing 4.5.6 下实跑通过，输出
  `CP5-SMOKE-OK version=2.2.6 button=true slider=10.0`
  （成功创建 `ControlP5` 实例、`Button`、`Slider` 并取到数值）。

## 用法

把 jar 放进 sketch 的 `code/` 目录即可，**无需**安装到 Processing 的 `libraries/`，
这样课程提交物是自包含的：

```text
Arknights/
  Arknights.pde
  code/
    controlP5-2.2.6-processing4.jar
```

```java
import controlP5.*;
ControlP5 cp5;
void setup() {
  size(800, 600);
  cp5 = new ControlP5(this);
  cp5.addButton("save").setPosition(20, 20).setSize(100, 30);
}
```

## 许可证

ControlP5 由 Andreas Schlegel 以 **LGPL** 发布，源码头部保留原始版权声明。
本目录的源码与 jar 均为原作品的衍生编译产物，未修改许可条款。
