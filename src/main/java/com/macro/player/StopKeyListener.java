package com.macro.player;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 播放快捷键监听器。
 *
 * <p>F11 用于暂停/继续播放，F12 用于停止播放。</p>
 */
public class StopKeyListener implements NativeKeyListener {

    private final MacroPlayer player;
    private final Runnable pauseToggle;

    /** 本次监听中 F11 是否尚未释放；不随播放暂停、焦点或最小化变化复位。 */
    private final AtomicBoolean f11Pressed = new AtomicBoolean();

    /** 使用播放器自身的暂停和继续方法处理 F11，保留 F12 直接停止的默认行为。 */
    public StopKeyListener(MacroPlayer player) {
        this(player, () -> {
            if (player.getState() == PlayerState.PLAYING) player.pause();
            else if (player.getState() == PlayerState.PAUSED) player.resume();
        });
    }

    /** UI 可在继续前恢复目标焦点；F12 始终直接停止，不等待 EDT。 */
    public StopKeyListener(MacroPlayer player, Runnable pauseToggle) {
        this.player = player;
        this.pauseToggle = java.util.Objects.requireNonNull(pauseToggle);
    }

    /** 处理播放快捷键，抑制 F11 长按重复切换，并让 F12 不经过界面队列直接停止。 */
    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        switch (e.getKeyCode()) {
            case NativeKeyEvent.VC_F11 -> {
                if (!f11Pressed.compareAndSet(false, true)) {
                    return;
                }
                pauseToggle.run();
            }

            case NativeKeyEvent.VC_F12 -> player.stop();

            default -> {
            }
        }
    }

    /** 收到 F11 释放时解除长按标记，使下一次按下能够正常切换状态。 */
    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        if (e.getKeyCode() == NativeKeyEvent.VC_F11) {
            // 全局释放事件在暂停、停止或窗口失焦时仍然必须清除按下标记。
            f11Pressed.set(false);
        }
    }
}
