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

    /** 本次监听中 F11 是否尚未释放；不随播放暂停、焦点或最小化变化复位。 */
    private final AtomicBoolean f11Pressed = new AtomicBoolean();

    public StopKeyListener(MacroPlayer player) {
        this.player = player;
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        switch (e.getKeyCode()) {
            case NativeKeyEvent.VC_F11 -> {
                if (!f11Pressed.compareAndSet(false, true)) {
                    return;
                }
                if (player.getState() == PlayerState.PLAYING) {
                    player.pause();
                } else if (player.getState() == PlayerState.PAUSED) {
                    player.resume();
                }
            }

            case NativeKeyEvent.VC_F12 -> player.stop();

            default -> {
            }
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        if (e.getKeyCode() == NativeKeyEvent.VC_F11) {
            // 全局释放事件在暂停、停止或窗口失焦时仍然必须清除按下标记。
            f11Pressed.set(false);
        }
    }
}
