package com.macro.ui;

import com.macro.player.PlaybackProgress;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 汇总播放线程产生的进度，交给 Swing 事件线程批量刷新时间线。
 *
 * <p>同一批次只安排一次刷新，已完成记录按节点去重，避免重试或快速播放堆积界面任务。
 * 对缓冲状态的读写均通过同步方法完成。</p>
 */
final class PlaybackProgressBuffer {
    /** 一次界面刷新所需的快照，包含本轮完成节点、当前节点和头部待机状态。 */
    record Batch(int loop, boolean reset, Set<String> completed, String current,
                 boolean headCurrent, boolean headCompleted) { }

    private final Set<String> completed = new LinkedHashSet<>();
    private int loop;
    private boolean reset;
    private String current;
    private boolean headCurrent;
    private boolean headCompleted;
    private boolean scheduled;
    private boolean dirty;

    /**
     * 合并一条进度，并判断调用方是否需要安排一次界面刷新。
     *
     * <p>轮次开始时清空上一轮记录；不属于当前轮次的其他通知直接忽略。</p>
     *
     * @return 仅在本次取得刷新占位时返回 true；已有待处理任务时返回 false
     */
    synchronized boolean offer(PlaybackProgress progress) {
        if (progress.phase() == PlaybackProgress.Phase.LOOP_STARTED) {
            loop = progress.loop();
            reset = true;
            completed.clear();
            current = null;
            headCurrent = false;
            headCompleted = false;
        } else if (progress.loop() != loop) {
            return false;
        } else {
            switch (progress.phase()) {
                case HEAD_WAIT_STARTED -> headCurrent = true;
                case HEAD_WAIT_COMPLETED -> { headCurrent = false; headCompleted = true; }
                case NODE_STARTED -> current = progress.nodeId();
                case NODE_COMPLETED -> completed.add(progress.nodeId());
                case FINISHED -> { current = null; headCurrent = false; }
                default -> { }
            }
        }
        dirty = true;
        if (scheduled) return false;
        scheduled = true;
        return true;
    }

    /**
     * 取走本批变化并释放刷新占位，没有新进度时返回 null。
     *
     * <p>完成节点集合会复制为快照，调用方可以在锁外使用；当前节点和待机状态继续保留。</p>
     */
    synchronized Batch drain() {
        scheduled = false;
        if (!dirty) return null;
        Batch batch = new Batch(loop, reset, Set.copyOf(completed), current, headCurrent, headCompleted);
        reset = false;
        completed.clear();
        dirty = false;
        return batch;
    }

    /**
     * 清空上次播放的数据，保留已经排队的刷新占位。
     *
     * <p>旧任务仍可读取下一次播放积累的进度，因此这里不再额外安排刷新任务。</p>
     */
    synchronized void clear() {
        completed.clear();
        current = null;
        headCurrent = false;
        headCompleted = false;
        loop = 0;
        reset = false;
        dirty = false;
    }
}
