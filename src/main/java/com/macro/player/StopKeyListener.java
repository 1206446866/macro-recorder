package com.macro.player;

import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

/**
 * 播放快捷键监听器。
 *
 * <p>F11 用于暂停/继续播放，F12 用于停止播放。</p>
 */
public class StopKeyListener implements NativeKeyListener {

    private final MacroPlayer player;

    public StopKeyListener(MacroPlayer player) {
        this.player = player;
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        switch (e.getKeyCode()) {
            case NativeKeyEvent.VC_F11 -> {
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
}