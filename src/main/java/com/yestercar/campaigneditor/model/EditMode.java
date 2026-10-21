package com.yestercar.campaigneditor.model;

import java.util.List;

/**
 * 编辑器的「当前在放什么」：一个地形工具，或者擦除。
 *
 * <h2>为什么把这两件事绑在一起</h2>
 * 它们**不可能同时生效** —— 左键一次只能做一件事。
 * 但状态上曾经是两个独立的 boolean（{@code selectedTool} + {@code eraserMode}），
 * 于是「选了高台，再点擦除」会让**两个按钮同时高亮**，
 * 用户以为两个都要按，实际只有擦除起作用（实测踩过）。
 *
 * <p>把状态收成一个类之后，互斥是**结构上保证的**，不靠两处代码各自记得清干净的对方。
 */
public final class EditMode {

    /** 可选的擦除动作，用 {@code null} 表示"不擦除" 太隐晦，所以给个常量。 */
    private static final int ERASER = -1;

    /** 可选的工具列表（顺序与界面按钮一致）。 */
    private final List<TileType> tools;

    /** 当前工具的下标；{@link #ERASER} 表示擦除。 */
    private int current;

    /**
     * @param tools 可选的工具，至少一个
     * @throws IllegalArgumentException 工具列表为空
     */
    public EditMode(List<TileType> tools) {
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("工具列表不能为空");
        }
        this.tools = List.copyOf(tools);
        this.current = 0;
    }

    /**
     * 选中第 {@code index} 个工具（同时关掉擦除）。
     *
     * @param index 下标，越界时忽略
     */
    public void selectTool(int index) {
        if (index >= 0 && index < tools.size()) {
            current = index;
        }
    }

    /** 切到擦除（同时不再是任何地形工具）。 */
    public void selectEraser() {
        current = ERASER;
    }

    /**
     * 开关擦除：在擦除时切回上一个工具的行为不明确，所以"关"就切回第一个工具。
     *
     * @return 切换后是否处于擦除状态
     */
    public boolean toggleEraser() {
        if (isEraser()) {
            current = 0;
            return false;
        }
        current = ERASER;
        return true;
    }

    /**
     * @return 是否处于擦除状态
     */
    public boolean isEraser() {
        return current == ERASER;
    }

    /**
     * @return 当前工具；处于擦除状态时返回 {@code null}
     */
    public TileType tool() {
        return isEraser() ? null : tools.get(current);
    }

    /**
     * @return 当前工具的下标；处于擦除状态时返回 {@code -1}
     */
    public int toolIndex() {
        return isEraser() ? -1 : current;
    }

    /**
     * 左键按下时该做什么。
     *
     * <p>这是**唯一**的判定点：界面绘制、状态栏提示、实际改格都读它，
     * 不会出现"按钮高亮说在放地形、实际却在擦"。
     *
     * @return 要放的地形；{@code null} 表示擦除
     */
    public TileType placeTarget() {
        return tool();
    }

    /**
     * @return 状态栏提示文字
     */
    public String describe() {
        return isEraser() ? "擦除" : "放置 " + tool().displayName();
    }

    /**
     * @return 可选的工具列表（不可变）
     */
    public List<TileType> tools() {
        return tools;
    }
}
