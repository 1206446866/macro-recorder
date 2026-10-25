package com.macro.player;

/** 播放线程上报的实际进度，不进入宏文件，也不改变流程节点身份。 */
public record PlaybackProgress(int loop, String nodeId, Phase phase) {
    public enum Phase {
        LOOP_STARTED, HEAD_WAIT_STARTED, HEAD_WAIT_COMPLETED,
        NODE_STARTED, NODE_COMPLETED, FINISHED
    }
}
