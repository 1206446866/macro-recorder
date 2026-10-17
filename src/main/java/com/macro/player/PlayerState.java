package com.macro.player;

/**
 * 宏播放状态。
 */
public enum PlayerState {

    /** 空闲。 */
    IDLE,

    /** 正在播放。 */
    PLAYING,

    /** 已暂停。 */
    PAUSED,

    /** 正在停止。 */
    STOPPING
}
