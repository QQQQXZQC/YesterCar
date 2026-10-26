# spine-oracle —— 用官方运行时给本工程的骨骼动画"打分"

本目录是**诊断工具**，不参与游戏构建（不被 `pom.xml` 引用）。

## 它解决什么问题

本工程的 Spine 运行时是手写的（原因见 `docs/骨骼动画接入.md` §2），
"和官方看起来不一样"这类问题光看截图只能猜。这里提供**两条独立的比对线**：

| 线 | 工具 | 比什么 | 能发现什么 |
| --- | --- | --- | --- |
| 数值 | `diff.ps1` | 逐骨骼世界坐标/旋转、逐插槽附件名与 alpha | 骨骼变换、约束、附件切换、`transformMode` |
| 数值 | `slotdiff.ps1` | 逐插槽**网格世界顶点**（只比 mesh，避开 oracle 的 region 假顶点） | 蒙皮、deform、约束传播 |
| **像素** | `pixdiff.ps1` | 官方渲染一张 PNG vs 游戏同一个渲染器渲染一张 PNG，**同相机逐像素** | **UV、drawOrder、alpha、混合** —— 这些在骨骼数值里完全看不出来 |

2026-10 那次"干员动画很乱 → 脸和衣服也花"的排查就是靠它们定位的：
先数值线修掉 5 个（约束/时间轴/图层），再像素线修掉 2 个（drawOrder 解码、旋转图集 UV 换轴）。
完整报告见 `docs/骨骼动画排查报告.md`。

## 前置条件

这些 jar **不在本工程里**，从同事的 viewer 工程借（`target/dependency/` 里都有）：

```
<character-viewer>\fuzhu-viewer\angelina-viewer\target\dependency\  → spine-libgdx-3.5.51.1.jar / gdx-1.10.0.jar
<character-viewer>\jinwei-viewer\maenna-viewer\target\dependency\   → spine-libgdx-3.8.99.1.jar
```

像素线额外要 `gdx-backend-lwjgl3` + `gdx-platform-natives-desktop` + `lwjgl-3.2.3`
（含 `-natives-windows` / `-natives-linux` 各一份，脚本会自己挑）。

默认按 `..\..\..\character-viewer` 找，可用环境变量 `SPINE_VIEWER_ROOT` 覆盖。

## 用法

```powershell
# 一次性编译（需要先 mvn package，脚本要读 target/classes 与 target/YesterCar.jar）
powershell -NoProfile -File tools\spine-oracle\diff.ps1 -Build
powershell -NoProfile -File tools\spine-oracle\pixdiff.ps1 -Build

# 1) 数值：逐模型最大骨骼偏差 + 插槽(附件+alpha)是否全等
powershell -NoProfile -File tools\spine-oracle\diff.ps1 -Time 1.0
powershell -NoProfile -File tools\spine-oracle\diff.ps1 -Anim Attack -Time 0.3 -Only texas

# 2) 数值：逐插槽网格顶点（指出"哪一块"不对，而不是"整个人不对"）
powershell -NoProfile -File tools\spine-oracle\slotdiff.ps1 -Time 1.0 -Top 8

# 3) 像素：官方渲染 vs 游戏渲染的差异百分比（最硬的标准）
powershell -NoProfile -File tools\spine-oracle\pixdiff.ps1 -OutDir D:\shots\cmp
powershell -NoProfile -File tools\spine-oracle\pixdiff.ps1 -Only texas,reed

# 4) 单帧/动画序列（文件可直接拿去拼对照图或 GIF）
java -cp "tools\spine-oracle\out;target\classes" com.yestercar.runtime.view.SpineShot `
     data\spine\operator\texas Idle 0.5 out\ours-texas.png
java -cp "tools\spine-oracle\out;target\classes" com.yestercar.runtime.view.SpineShot `
     data\spine\operator\texas Idle mid out\ours-texas --seq=24

# 两边格式完全一样，可以自己 diff
java -cp "tools\spine-oracle\out;target\classes" com.yestercar.runtime.spine.SpineDump data\spine\operator\texas Idle 0.5
java -cp "tools\spine-oracle\out35;<spine jar>;<gdx jar>" Oracle35 data\spine\operator\texas\char_102_texas.skel Idle 0.5
```

## 读数怎么理解

| 现象 | 结论 |
| --- | --- |
| 骨骼对、顶点偏一点 | 该骨骼被 IK / transform 约束拉着 → 查约束 |
| 骨骼就错了 | 变换 / `transformMode` / 时间轴求值的锅 |
| 骨骼对、附件名不同 | 查 attachment 时间轴与皮肤 |
| 附件名对、alpha 不同 | 查 color 时间轴（隐藏部件靠 alpha=0） |
| **数值全对、像素差却很大** | **UV / drawOrder / alpha 混合**：看 `heat-*.png` 找红块位置，再回去查对应的图集区域（`rotate`）与绘制顺序 |

## 相机约定（`pixdiff.ps1` 能直接比的原因）

`OfficialShot` 与 `SpineShot` 用的是同一套：

- 世界 (0,0) = **脚底**，y 向上；
- 视图中心 (0,200)，缩放 **1.1 px/单位**，画布 **512×512**；
- 背景 **(41,43,51)**；
- 采样时刻支持秒数 / `mid`（动画中点）/ `end`。

## 已知的测量误差

- oracle 用自定义 `AttachmentLoader`（**不读贴图**），`RegionAttachment` 没有 region，
  四角会塌到骨骼原点、还会产出 ±`Float.MAX/2` 的假顶点 ——
  所以 `slotdiff.ps1` 只比 `MeshAttachment`，`diff.ps1` 会把 |坐标| > 1e6 的点剔掉；
  要看 region 的真实位置请用**像素线**。
- `PathAttachment` 的 `worldVerticesLength` 是"顶点数 × 3"，不能当普通网格顶点数用。
- `OfficialShot` 要开一个隐藏的 GLFW 窗口；批量跑偶尔会
  `Win32: Failed to create interface`，`pixdiff.ps1` 已带重试（最多 4 次）。
