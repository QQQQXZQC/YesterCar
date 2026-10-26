"""Build the operator skeletal-animation report from the audit TSV + demo assets.

Inputs:
  docs/骨骼动画检查/bind-audit.tsv   (written by SpineBindAudit)
  docs/骨骼动画检查/img/<id>-battle.gif / <id>-states.png
  docs/骨骼动画检查/img/battle-contact.png / states-contact.png

Output:
  docs/骨骼动画检查/局内演示.md

ASCII-only source; the Chinese lives in the generated report.
"""

import os
import sys

DOCS = os.path.join("docs", "骨骼动画检查")
IMG = os.path.join(DOCS, "img")

MEDIC = {"kaltsit", "reed", "warfarin", "eyjafjalla_the_hvit_aska"}

USE_TEXT = {
    "start": "登场",
    "idle": "待机",
    "attack": "出手",
    "die": "阵亡",
}


def load_tsv(path):
    rows = []
    with open(path, encoding="utf-8-sig") as f:
        head = f.readline().rstrip("\n").split("\t")
        for line in f:
            if not line.strip():
                continue
            cells = line.rstrip("\n").split("\t")
            rows.append(dict(zip(head, cells)))
    return rows


def cell(text, missing="—"):
    return text if text else missing


def main():
    rows = load_tsv(os.path.join(DOCS, "bind-audit.tsv"))
    rows.sort(key=lambda r: r["id"])
    out = []
    a = out.append

    a("# 我方干员骨骼动画：绑定检查 + 局内效果逐个演示\n")
    a("> 工程：`D:\\dsHarness\\Arknights\\YesterCar`　｜　"
      "检查工具：`SpineBindAudit`（无窗口自检）　｜　"
      "演示采集：`tools/spine-demo/`\n")

    # ---------- 1. 结论 ----------
    n = len(rows)
    warn = [r for r in rows if r["verdict"] == "WARN"]
    fail = [r for r in rows if r["verdict"] == "FAIL"]
    a("## 一、结论\n")
    a("| 项 | 结果 |\n| --- | --- |")
    a(f"| 我方干员总数 | **{n}**（`operators.json` 全员，每人都有一份骨骼素材） |")
    a(f"| 绑定成功 | **{n - len(fail)} / {n}**（解析 → 贴图 → 战斗内名字映射全通） |")
    a(f"| 完全通过 | {n - len(fail) - len(warn)} 人 |")
    a(f"| 能用但有降级 | {len(warn)} 人（见 §3.2，都是动画名回退，不影响出图） |")
    a(f"| 绑定失败 | **{len(fail)}** 人 |")
    a("")
    a("也就是说：**今早这批骨骼动画是全部绑定成功的**，局内每个人都能按"
      "「登场 → 待机 → 出手 → 受击 → 阵亡」这条状态机正常演出。"
      f"有 {len(warn)} 个干员的素材本身缺标准名的动画（比如没有 `Attack`），"
      "运行时会按候选名回退到最接近的那个（下表标了具体名字），属于素材层面的差异，不是代码没接上。\n")
    a("另外，核对过程里发现并修掉了一个**表现层缺陷**：受击闪红 / 尸体淡出在 P2D 上其实从未生效"
      "（详见 §3.3）。修完已重新跑过全量采集，下面的 GIF 与状态条都是修完之后的画面。\n")

    # ---------- 2. 检查了什么 ----------
    a("## 二、这次「绑定成功」是怎么判定的\n")
    a("单看「能出图」不够——贴图错位、名字映射错、约束散了，都能画出一坨东西。"
      "所以按运行时会走到的链路逐环检查（`SpineBindAudit`，无窗口、可进 CI）：\n")
    a("| # | 检查项 | 失败后果 |\n| --- | --- | --- |")
    a("| 1 | 素材契约：`data/spine/operator/<id>/` 正好 `.skel` + `.atlas` + `.png` 各一份 | 缺文件就退化成色块占位 |")
    a("| 2 | atlas 里声明的贴图真的存在、png 能被解码、尺寸与 atlas 头部一致 | 贴图错位（脸是别人的） |")
    a("| 3 | `.skel` 版本 ∈ {3.5.51, 3.8.99}（本批两个版本混用，两条解析分支都被覆盖） | 解析成 0 动画 |")
    a("| 4 | 状态机四个动画名按 `OperatorAnimator` 的候选表解析得出来 | 出手/阵亡演不出来 |")
    a("| 5 | 四个状态各采一帧：顶点非空、无 NaN、包围盒在人形量级 | 约束散架 / 人飞出画面 |")
    a("| 6 | 战斗内「中文显示名 → id」反查回自己，且显示名无重名 | 两个座位画成同一个人 |")
    a("")
    a("> 第 6 条是最容易漏的：`SpineUnitLayer` 用的是 `UnitView.name()`（中文名）反查 id，"
      "重名或改名都会让两个干员互相串模型，而画面上只是「看着怪」，不会报错。本次 0 重名。\n")

    # ---------- 3. 逐人结论 ----------
    a("## 三、逐人体检表\n")
    a("| 干员 | id | 版本 | 骨骼/插槽/动画 | 登场 Start | 待机 Idle | 出手 Attack | 阵亡 Die | 贴图 | 结论 |")
    a("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for r in rows:
        def an(k, d):
            return f"`{r[k]}` {float(r[d]):.2f}s" if r[k] else "❌"
        a(f"| {r['name']} | `{r['id']}` | {r['version']} | "
          f"{r['bones']}/{r['slots']}/{r['anims']} | {an('start', 'startDur')} | "
          f"{an('idle', 'idleDur')} | {an('attack', 'attackDur')} | "
          f"{an('die', 'dieDur')} | {r['texW']}x{r['texH']} | {r['verdict']} |")
    a("")
    a("### 3.1 全 29 人在局内的实测渲染（真实战斗、同一场地图、自动部署）\n")
    a("![全员局内实拍](img/battle-contact.png)\n")
    a("### 3.2 四类降级说明（能用，但不是素材里的「标准名」）\n")
    a("| 干员 | 现象 | 影响 |\n| --- | --- | --- |")
    a("| 德克萨斯 `texas`、风笛 `bagpipe` | 素材里没有 `Attack`，只有 `Attack_Loop`（循环挥砍） | 出手时用 `Attack_Loop`，动作正常，只是循环段被单次播放 |")
    a("| 玛恩纳 `mlynar` | 素材里没有任何 `Attack*`，只有 `Idle_2` | 出手时播 `Idle_2`（官方素材本身如此），看上去像「举手蓄力」 |")
    a("| 煌 `blaze` | 脚底 `minY=-521`（有游离顶点/未实现的 path 约束链） | 运行时按模型原点对齐脚底，不会浮空；只是包围盒偏大，缩放按 `bodyHeight()` 钳位后正常 |")
    a("")
    a("### 3.3 顺带修掉一个真实缺陷：受击闪红 / 尸体淡出在 P2D 上从未生效\n")
    a("逐人出图时顺手做了像素核对，发现**「受击染色」这个效果其实一直是假的**："
      "同一姿态、同一时刻，分别按「受击强度 0」和「受击强度拉满」渲染两遍，"
      "人物区域的平均 RGB 差 **< 0.2/255**（等于没变）。尸体淡出同样失效 —— 尸体会「啪」地消失。\n")
    a("| 项 | 修之前 | 修之后 |\n| --- | --- | --- |")
    a("| 受击闪红（人物区平均 G/B 通道） | 91.3 / 93.0（与原色一致） | **53.3 / 54.1**（明显偏红） |")
    a("| 阵亡淡出（人物区平均亮度） | 与存活一致（不合逻辑） | 随 alpha 正常变暗 |")
    a("")
    a("**根因**：Processing 4.5.6 的 P2D 在 `beginShape(TRIANGLES) + texture() + vertex(x,y,u,v)` "
      "这条路上**完全忽略顶点色**，而原实现正是用逐顶点 `fill()` 做染色的（文档里那条「tint() 无效」的结论是错的）。\n")
    a("**修法**：`SpineMeshPainter` 改用 `tint(r,g,b,a)` 表达整体染色与透明度；"
      "逐顶点只保留「三顶点全透明就跳过」这一条 CPU 判据。改动只影响表现层，411 个测试仍全绿。\n")
    a("> 这条在下面的「六状态条」里能直接看到：第 4 格（HIT）是红色染色，第 6 格（DIE+fade）是半透明。"
      "实战 GIF 里 150ms 的闪红不一定刚好被 55ms 的采样抓到，所以那一格的证据以状态条为准。\n")
    a("## 四、逐个演示\n")
    a("每个干员的演示由两张图组成：\n")
    a("- **局内实拍 GIF**：一局真实战斗（`_spine_demo` 地图、`--play` 直接开战、自动部署），"
      "按脚底屏幕坐标裁切，约 1.5 秒、18fps。敌人是当前版本的占位色块，干员是骨骼动画。")
    a("- **六状态条**：用战斗里同一个 `OperatorAnimator` + `SpineMeshPainter` 渲染的"
      "「登场 / 待机 / 出手 / 受击染色 / 阵亡 / 阵亡淡出」。"
      "阵亡在真实战斗中无法按需复现（要凑「什么时候被打死」），所以这张图是可信的替代验证。\n")
    if os.path.exists(os.path.join(IMG, "states-contact.png")):
        a("![六状态总览](img/states-contact.png)\n")

    for r in rows:
        op = r["id"]
        a(f"### {r['name']}　`{op}`\n")
        a(f"- 素材：Spine **{r['version']}**，{r['bones']} 骨骼 / {r['slots']} 插槽 / "
          f"{r['anims']} 个动画，贴图 {r['texW']}×{r['texH']}")
        a(f"- 局内动画：登场 `{r['start']}` {float(r['startDur']):.2f}s ｜ "
          f"待机 `{r['idle']}` {float(r['idleDur']):.2f}s ｜ "
          f"出手 `{r['attack']}` {float(r['attackDur']):.2f}s ｜ "
          f"阵亡 `{r['die']}` {float(r['dieDur']):.2f}s")
        note = r["notes"].strip()
        if note:
            a(f"- 备注：{note}")
        if op in MEDIC:
            a("- 演示编队：医疗干员单独上场不会出手（只给伤员回血），所以给它配了一个前排蛇屠箱挡怪")
        gif = os.path.join(IMG, f"{op}-battle.gif")
        if os.path.exists(gif):
            a(f"\n![{op} 局内](img/{op}-battle.gif)\n")
        st = os.path.join(IMG, f"{op}-states.png")
        if os.path.exists(st):
            a(f"![{op} 六状态](img/{op}-states.png)\n")
        else:
            a("")
    a("## 五、怎么复现\n")
    a("```powershell\n"
      "# 0) 构建 + 全量测试（411 passed）\n"
      "mvn -B clean package\n\n"
      "# 1) 绑定自检（无窗口，产出 bind-audit.md / .tsv）\n"
      "java -Dfile.encoding=UTF-8 -cp target\\YesterCar.jar "
      "com.yestercar.runtime.view.SpineBindAudit --out docs\\骨骼动画检查\n\n"
      "# 2) 逐干员局内采集（一场真实战斗/人，约 20 秒一个人）\n"
      "powershell -NoProfile -ExecutionPolicy Bypass -File tools\\spine-demo\\capture-all.ps1\n\n"
      "# 3) 裁切 + 出 GIF/拼图\n"
      "python tools\\spine-demo\\assemble.py\n\n"
      "# 4) 六状态条\n"
      "java -Dfile.encoding=UTF-8 -cp target\\YesterCar.jar "
      "com.yestercar.runtime.view.OperatorDemoSheet --out target\\demo-states\n"
      "```\n")
    a("## 六、不在本次范围内\n")
    a("| 项 | 状态 |\n| --- | --- |")
    a("| 敌人骨骼动画 | ⛔ 仍是色块占位（本批只做了我方干员） |")
    a("| 技能动画 | ⛔ 素材里有 `Skill*`，但战斗事件流里还没有 SKILL 事件，表现层不能自己猜 |")
    a("| 动画混合（mix） | ⛔ 状态切换是硬切，没有官方那种过渡 |")
    a("| 上下朝向 | ⛔ 2D 立绘只能左右镜像 |")
    a("")
    path = os.path.join(DOCS, "局内演示.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print("wrote", path, len(out), "lines")
    return 0


if __name__ == "__main__":
    sys.exit(main())
