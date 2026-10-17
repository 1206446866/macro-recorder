package com.macro.model;

import lombok.Data;

@Data
public class MacroAction {

    private ActionType type;

    private int x;

    private int y;

    /**
     * 鼠标按键。
     */
    private MouseButton mouseButton;

    /**
     * 鼠标滚轮滚动量。
     */
    private int wheelAmount;

    /**
     * 按键名称，用于显示和保存时提高可读性。
     */
    private String key;

    /**
     * AWT 按键码，用于 Robot 播放。
     */
    private int keyCode;

    /**
     * 距离上一个动作的时间间隔。
     */
    private long delay;

    /**
     * 键盘按键所在的一侧。
     *
     * <p>旧版 MR 文件未保存此字段时，默认不区分左右。</p>
     */
    private KeySide keySide = KeySide.NONE;

    public static MacroAction mouseMove(int x, int y, long delay) {
        MacroAction action = new MacroAction();
        action.type = ActionType.MOUSE_MOVE;
        action.x = x;
        action.y = y;
        action.delay = delay;
        return action;
    }

    public static MacroAction mouseWheel(int x, int y, int wheelAmount, long delay) {
        MacroAction action = new MacroAction();
        action.type = ActionType.MOUSE_WHEEL;
        action.x = x;
        action.y = y;
        action.wheelAmount = wheelAmount;
        action.delay = delay;
        return action;
    }

    public static MacroAction mousePress(int x, int y, MouseButton mouseButton, long delay) {
        MacroAction action = new MacroAction();
        action.type = ActionType.MOUSE_PRESS;
        action.x = x;
        action.y = y;
        action.mouseButton = mouseButton;
        action.delay = delay;
        return action;
    }

    public static MacroAction mouseRelease(int x, int y, MouseButton mouseButton, long delay) {
        MacroAction action = new MacroAction();
        action.type = ActionType.MOUSE_RELEASE;
        action.x = x;
        action.y = y;
        action.mouseButton = mouseButton;
        action.delay = delay;
        return action;
    }

    /**
     * 创建键盘按下动作。
     *
     * @param keyCode AWT 按键码
     * @param key     按键名称
     * @param delay   距离上一个动作的时间间隔
     * @return 键盘按下动作
     */
    public static MacroAction keyPress(int keyCode, String key, long delay) {
        return keyPress(keyCode, key, KeySide.NONE, delay);
    }

    /**
     * 创建键盘按下动作。
     *
     * @param keyCode AWT 按键码
     * @param key     按键名称
     * @param keySide 按键所在的一侧
     * @param delay   距离上一个动作的时间间隔
     * @return 键盘按下动作
     */
    public static MacroAction keyPress(int keyCode, String key, KeySide keySide, long delay) {

        MacroAction action = new MacroAction();
        action.type = ActionType.KEY_PRESS;
        action.keyCode = keyCode;
        action.key = key;
        action.keySide = keySide == null ? KeySide.NONE : keySide;
        action.delay = delay;
        return action;
    }

    /**
     * 创建键盘释放动作。
     *
     * @param keyCode AWT 按键码
     * @param key     按键名称
     * @param delay   距离上一个动作的时间间隔
     * @return 键盘释放动作
     */
    public static MacroAction keyRelease(int keyCode, String key, long delay) {
        return keyRelease(keyCode, key, KeySide.NONE, delay);
    }

    /**
     * 创建键盘释放动作。
     *
     * @param keyCode AWT 按键码
     * @param key     按键名称
     * @param keySide 按键所在的一侧
     * @param delay   距离上一个动作的时间间隔
     * @return 键盘释放动作
     */
    public static MacroAction keyRelease(int keyCode, String key, KeySide keySide, long delay) {

        MacroAction action = new MacroAction();
        action.type = ActionType.KEY_RELEASE;
        action.keyCode = keyCode;
        action.key = key;
        action.keySide = keySide == null ? KeySide.NONE : keySide;
        action.delay = delay;
        return action;
    }

}