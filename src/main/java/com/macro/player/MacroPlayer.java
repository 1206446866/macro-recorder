package com.macro.player;

import com.macro.flow.BranchCondition;
import com.macro.flow.FlowContext;
import com.macro.flow.ImageMatchResult;
import com.macro.flow.ImageMatchStatus;
import com.macro.flow.ImageMatcher;
import com.macro.flow.ImageCheckBehavior;
import com.macro.flow.MacroFlowFactory;
import com.macro.flow.MacroBranch;
import com.macro.flow.MacroFlow;
import com.macro.flow.MacroNode;
import com.macro.flow.NodeResult;
import com.macro.flow.NodeType;
import com.macro.model.MacroAction;
import com.macro.model.MouseButton;
import com.macro.recognition.ImageRecognitionDiagnostics;
import lombok.Getter;
import lombok.Setter;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;


public class MacroPlayer {

    /** 单段毫秒转纳秒的安全边界；仅拆分等待，不限制总延迟。 */
    private static final long MAX_DELAY_SEGMENT_MILLIS = Long.MAX_VALUE / 1_000_000L;

    /** 执行键盘、鼠标和滚轮输入的系统 Robot。 */
    private final Robot robot;

    /** 当前播放状态，供 UI 和全局快捷键监听器读取。 */
    @Getter
    private volatile PlayerState state = PlayerState.IDLE;

    /**
     * 播放等待锁。
     *
     * <p>用于暂停、继续和停止时唤醒播放线程。</p>
     */
    private final Object pauseLock = new Object();

    /**
     * 尚未释放的鼠标按钮掩码。
     */
    private final Set<Integer> pressedMouseButtons = new LinkedHashSet<>();

    /**
     * 尚未释放的键盘按键代码。
     */
    private final Set<Integer> pressedKeys = new LinkedHashSet<>();

    /**
     * 播放状态变化回调。
     */
    @Setter
    private Consumer<PlayerState> onStateChange;

    public MacroPlayer() throws AWTException {

        robot = new Robot();

    }

    /**
     * 播放宏动作。
     *
     * @param actions   宏动作列表
     * @param loopCount 循环次数，-1 表示无限循环
     */
    public void play(List<MacroAction> actions, int loopCount) throws InterruptedException {
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("宏动作列表不能为空");
        }

        if (loopCount == 0 || loopCount < -1) {
            throw new IllegalArgumentException("播放次数必须大于 0");
        }


        for (int i = 0; i < actions.size(); i++) {
            MacroAction action = actions.get(i);
            String prefix = "第 " + (i + 1) + " 个动作：";

            if (action == null) {
                throw new IllegalArgumentException(prefix + "动作数据为空");
            }

            if (action.getType() == null) {
                throw new IllegalArgumentException(prefix + "缺少动作类型");
            }

            if (action.getDelay() < 0) {
                throw new IllegalArgumentException(prefix + "延迟时间不能为负数");
            }

            switch (action.getType()) {
                case MOUSE_MOVE, MOUSE_WHEEL -> {
                    // 无需额外校验。
                }

                case MOUSE_PRESS, MOUSE_RELEASE -> {
                    MouseButton button = action.getMouseButton();

                    if (button == null) {
                        throw new IllegalArgumentException(prefix + "缺少鼠标按键");
                    }

                    if (!button.isSupported()) {
                        throw new IllegalArgumentException(prefix + "暂不支持模拟鼠标侧键");
                    }
                }

                case KEY_PRESS, KEY_RELEASE -> {
                    if (action.getKeyCode() <= KeyEvent.VK_UNDEFINED) {
                        throw new IllegalArgumentException(prefix + "键盘按键代码无效");
                    }
                }
            }
        }
        state = PlayerState.PLAYING;

        try {
            int currentLoop = 0;

            while (loopCount == -1 || currentLoop < loopCount) {
                if (!waitForDelay(0L)) {
                    return;
                }

                currentLoop++;

                for (MacroAction action : actions) {
                    if (!waitForDelay(action.getDelay())) {
                        return;
                    }

                    if (state != PlayerState.PLAYING) {
                        return;
                    }

                    executeAction(action);
                }
            }

        } finally {
            try {
                releasePressedInputs();
            } finally {
                state = PlayerState.IDLE;

                try {
                    notifyStateChange(state);
                } finally {
                    synchronized (pauseLock) {
                        pauseLock.notifyAll();
                    }
                }
            }
        }
    }

    /**
     * 播放流程图。
     *
     * <p>循环、暂停、停止和输入释放仍然复用当前播放器状态机。</p>
     *
     * @param flow         待播放流程
     * @param loopCount    循环次数，-1 表示无限循环
     * @param imageMatcher IMAGE_CHECK 节点的图像匹配扩展点
     * @return 本次播放使用的流程上下文
     * @throws Exception 流程配置、图像匹配或等待失败
     */
    public FlowContext play(
            MacroFlow flow,
            int loopCount,
            ImageMatcher imageMatcher
    ) throws Exception {
        validateLoopCount(loopCount);
        validateFlow(flow);
        // 全部图像节点的外部资源先检查完，再进入 PLAYING 或执行任何输入。
        for (MacroNode node : flow.getNodeMap().values()) {
            if (node.getType() == NodeType.IMAGE_CHECK) {
                if (imageMatcher == null) {
                    throw new IllegalArgumentException("图像识别节点尚未配置 ImageMatcher：" + node.getId());
                }
                imageMatcher.validate(node);
            }
        }

        FlowContext context = new FlowContext();
        state = PlayerState.PLAYING;

        try {
            notifyStateChange(state);
            int currentLoop = 0;
            while (loopCount == -1 || currentLoop < loopCount) {
                currentLoop++;
                String currentId = flow.getStartId();

                while (true) {
                    if (!waitForDelay(0L)) {
                        return context;
                    }

                    MacroNode node = flow.getNodeMap().get(currentId);
                    if (node == null) {
                        throw new IllegalStateException(
                                "流程节点不存在：" + currentId
                        );
                    }
                    if (node.getType() == NodeType.END) {
                        break;
                    }

                    NodeResult result = executeNode(
                            node,
                            context,
                            imageMatcher
                    );
                    if (result == null) {
                        return context;
                    }

                    if (node.getType() == NodeType.IMAGE_CHECK) {
                        ImageCheckBehavior behavior = node.getImageCheckConfig()
                                .behaviorFor(result == NodeResult.FOUND);
                        ImageRecognitionDiagnostics.log("PLAYBACK", "BEHAVIOR",
                                "NodeId: " + node.getId()
                                        + "\nLoop: " + currentLoop
                                        + "\nNodeResult: " + result.name()
                                        + "\nsuccessBehavior: " + node.getImageCheckConfig().getSuccessBehavior().name()
                                        + "\nfailureBehavior: " + node.getImageCheckConfig().getFailureBehavior().name()
                                        + "\nSelectedBehavior: " + behavior.name()
                                        + "\nMatchStatus: " + context.getLastImageMatch().getStatus()
                                        + "\nSimilarity: " + ImageRecognitionDiagnostics.similarity(
                                                context.getLastImageMatch().getSimilarity())
                                        + "\nMatchBounds: " + context.getLastImageMatch().getMatchBounds());
                        if (behavior == ImageCheckBehavior.TERMINATE) {
                            ImageRecognitionDiagnostics.log("PLAYBACK", "TERMINATE",
                                    "NodeId: " + node.getId() + "\nScope: ENTIRE_PLAYBACK_TASK");
                            // 退出整个任务，有限/无限循环都经过下方 finally 释放输入。
                            return context;
                        }
                        if (behavior == ImageCheckBehavior.RETRY) {
                            ImageRecognitionDiagnostics.log("PLAYBACK", "RETRY",
                                    "NodeId: " + node.getId() + "\nTargetId: " + node.getId()
                                            + "\nDelayMillis: " + MacroFlowFactory.findRetryDelay(node));
                            if (!waitForDelay(MacroFlowFactory.findRetryDelay(node))) {
                                return context;
                            }
                            continue;
                        }
                    }
                    MacroBranch branch = selectBranch(node, result);
                    if (!waitForDelay(branch.getDelay())) {
                        return context;
                    }
                    if (node.getType() == NodeType.IMAGE_CHECK) {
                        ImageRecognitionDiagnostics.log("PLAYBACK", "EXECUTE_JUMP",
                                "NodeId: " + node.getId()
                                        + "\nNodeResult: " + result.name()
                                        + "\nBranchCondition: " + branch.getCondition()
                                        + "\nTargetId: " + branch.getTargetId()
                                        + "\nDelayMillis: " + branch.getDelay());
                    }
                    currentId = branch.getTargetId();
                }
            }
            return context;
        } finally {
            try {
                releasePressedInputs();
            } finally {
                state = PlayerState.IDLE;
                try {
                    notifyStateChange(state);
                } finally {
                    synchronized (pauseLock) {
                        pauseLock.notifyAll();
                    }
                }
            }
        }
    }

    /**
     * 播放不包含图像识别节点的流程。
     *
     * @param flow 待播放流程
     * @param loopCount 循环次数，-1 表示无限循环
     * @return 本次播放产生的流程上下文
     * @throws Exception 流程校验或动作执行失败
     */
    public FlowContext play(MacroFlow flow, int loopCount) throws Exception {
        return play(flow, loopCount, null);
    }

    /** 执行单个流程节点，并返回用于选择下一条分支的结果。 */
    private NodeResult executeNode(
            MacroNode node,
            FlowContext context,
            ImageMatcher imageMatcher
    ) throws Exception {
        return switch (node.getType()) {
            case ACTION -> {
                MacroAction action = node.getAction();
                if (!waitForDelay(action.getDelay())) {
                    yield null;
                }
                executeAction(action);
                yield NodeResult.ALWAYS;
            }
            case IMAGE_CHECK -> {
                if (imageMatcher == null) {
                    throw new IllegalStateException(
                            "IMAGE_CHECK 节点尚未配置 ImageMatcher："
                                    + node.getId()
                    );
                }
                ImageMatchResult imageMatch = imageMatcher.match(node, context);
                if (imageMatch == null || imageMatch.getStatus() == null) {
                    throw new IllegalStateException(
                            "ImageMatcher 未返回有效结果：" + node.getId()
                    );
                }
                context.setLastImageMatch(imageMatch);
                if (!waitForDelay(0L)) {
                    yield null;
                }
                yield imageMatch.getStatus() == ImageMatchStatus.FOUND
                        ? NodeResult.FOUND
                        : NodeResult.NOT_FOUND;
            }
            case END -> throw new IllegalStateException(
                    "END 节点不应进入节点执行器"
            );
        };
    }

    /** 根据节点结果选择精确分支，未配置精确条件时允许 ALWAYS 兜底。 */
    private MacroBranch selectBranch(MacroNode node, NodeResult result) {
        BranchCondition expected = switch (result) {
            case ALWAYS -> BranchCondition.ALWAYS;
            case FOUND -> BranchCondition.FOUND;
            case NOT_FOUND -> BranchCondition.NOT_FOUND;
        };
        MacroBranch fallback = null;
        for (MacroBranch branch : node.getChildren()) {
            if (branch.getCondition() == expected) {
                return branch;
            }
            if (branch.getCondition() == BranchCondition.ALWAYS) {
                fallback = branch;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException(
                "节点 " + node.getId() + " 没有可用于结果 " + result + " 的分支"
        );
    }

    /** 在改变播放状态前校验流程入口、节点、动作和全部分支引用。 */
    private void validateFlow(MacroFlow flow) {
        if (flow == null) {
            throw new IllegalArgumentException("宏流程不能为空");
        }
        if (flow.getStartId() == null || flow.getStartId().isBlank()) {
            throw new IllegalArgumentException("宏流程缺少 startId");
        }
        Map<String, MacroNode> nodeMap = flow.getNodeMap();
        if (nodeMap == null || nodeMap.isEmpty()) {
            throw new IllegalArgumentException("宏流程缺少节点");
        }
        if (!nodeMap.containsKey(flow.getStartId())) {
            throw new IllegalArgumentException(
                    "宏流程入口节点不存在：" + flow.getStartId()
            );
        }

        for (Map.Entry<String, MacroNode> entry : nodeMap.entrySet()) {
            String nodeId = entry.getKey();
            MacroNode node = entry.getValue();
            if (nodeId == null || nodeId.isBlank() || node == null) {
                throw new IllegalArgumentException("宏流程包含无效节点");
            }
            if (!nodeId.equals(node.getId())) {
                throw new IllegalArgumentException(
                        "节点索引与节点 ID 不一致：" + nodeId
                );
            }
            if (node.getType() == null) {
                throw new IllegalArgumentException(
                        "节点 " + nodeId + " 缺少节点类型"
                );
            }
            if (node.getType() == NodeType.ACTION) {
                validateAction(node.getAction(), "节点 " + nodeId + "：");
            }
            if (node.getType() == NodeType.IMAGE_CHECK
                    && node.getImageCheckConfig() == null) {
                throw new IllegalArgumentException(
                        "节点 " + nodeId + " 缺少图像识别配置"
                );
            }
            if (node.getChildren() == null) {
                throw new IllegalArgumentException(
                        "节点 " + nodeId + " 缺少分支列表"
                );
            }
            if (node.getType() == NodeType.IMAGE_CHECK
                    && MacroFlowFactory.findRetryDelay(node) < 0L) {
                throw new IllegalArgumentException("节点 " + nodeId + " 的重试延迟不能为负数");
            }
            for (MacroBranch branch : node.getChildren()) {
                validateBranch(nodeId, branch, nodeMap);
            }
            // 已有分支合法仍不代表具备执行出口；必须在任何输入动作前补齐检查。
            if (node.getType() == NodeType.ACTION) {
                validateRequiredBranch(node, NodeResult.ALWAYS);
            } else if (node.getType() == NodeType.IMAGE_CHECK) {
                if (node.getImageCheckConfig().getSuccessBehavior() == ImageCheckBehavior.EXECUTE
                        || node.getImageCheckConfig().getSuccessBehavior() == ImageCheckBehavior.JUMP) {
                    validateRequiredBranch(node, NodeResult.FOUND);
                }
                if (node.getImageCheckConfig().getFailureBehavior() == ImageCheckBehavior.EXECUTE
                        || node.getImageCheckConfig().getFailureBehavior() == ImageCheckBehavior.JUMP) {
                    validateRequiredBranch(node, NodeResult.NOT_FOUND);
                }
            }
        }
        MacroFlowFactory.validateImageJumps(flow);
    }

    /** 复用运行时的精确条件及 ALWAYS 后备规则，不要求重试或终止存在普通后继。 */
    private void validateRequiredBranch(MacroNode node, NodeResult result) {
        try {
            selectBranch(node, result);
        } catch (IllegalStateException exception) {
            throw new IllegalArgumentException(
                    "节点 " + node.getId() + " 缺少结果 " + result + " 所需的后继分支", exception
            );
        }
    }

    private void validateBranch(
            String nodeId,
            MacroBranch branch,
            Map<String, MacroNode> nodeMap
    ) {
        if (branch == null || branch.getCondition() == null) {
            throw new IllegalArgumentException(
                    "节点 " + nodeId + " 包含无效分支"
            );
        }
        if (branch.getDelay() < 0L) {
            throw new IllegalArgumentException(
                    "节点 " + nodeId + " 的分支延迟不能为负数"
            );
        }
        String targetId = branch.getTargetId();
        if (targetId == null || targetId.isBlank()
                || !nodeMap.containsKey(targetId)) {
            throw new IllegalArgumentException(
                    "节点 " + nodeId + " 的分支目标不存在：" + targetId
            );
        }
    }

    /** 校验流程 ACTION 节点中的现有宏动作。 */
    private void validateAction(MacroAction action, String prefix) {
        if (action == null) {
            throw new IllegalArgumentException(prefix + "动作数据为空");
        }
        if (action.getType() == null) {
            throw new IllegalArgumentException(prefix + "缺少动作类型");
        }
        if (action.getDelay() < 0L) {
            throw new IllegalArgumentException(prefix + "延迟时间不能为负数");
        }
        switch (action.getType()) {
            case MOUSE_MOVE, MOUSE_WHEEL -> {
                // 无需额外校验。
            }
            case MOUSE_PRESS, MOUSE_RELEASE -> {
                MouseButton button = action.getMouseButton();
                if (button == null) {
                    throw new IllegalArgumentException(prefix + "缺少鼠标按键");
                }
                if (!button.isSupported()) {
                    throw new IllegalArgumentException(prefix + "暂不支持模拟鼠标侧键");
                }
            }
            case KEY_PRESS, KEY_RELEASE -> {
                if (action.getKeyCode() <= KeyEvent.VK_UNDEFINED) {
                    throw new IllegalArgumentException(prefix + "键盘按键代码无效");
                }
            }
        }
    }

    private void validateLoopCount(int loopCount) {
        if (loopCount == 0 || loopCount < -1) {
            throw new IllegalArgumentException("播放次数必须大于 0");
        }
    }

    /**
     * 停止播放。
     */
    public void stop() {
        synchronized (pauseLock) {
            if (state == PlayerState.PLAYING || state == PlayerState.PAUSED) {
                state = PlayerState.STOPPING;
                notifyStateChange(state);
                pauseLock.notifyAll();
            }
        }
    }

    /**
     * 暂停播放。
     */
    public void pause() {
        synchronized (pauseLock) {
            if (state == PlayerState.PLAYING) {
                state = PlayerState.PAUSED;
                notifyStateChange(state);
                pauseLock.notifyAll();
            }
        }
    }

    /**
     * 继续播放。
     */
    public void resume() {
        synchronized (pauseLock) {
            if (state == PlayerState.PAUSED) {
                state = PlayerState.PLAYING;
                notifyStateChange(state);
                pauseLock.notifyAll();
            }
        }
    }

    /**
     * 等待动作或流程分支的延迟时间。
     *
     * <p>暂停时会保留剩余等待时间，继续播放后从暂停位置继续等待。</p>
     *
     * @param delay 延迟时间，单位毫秒
     * @return 是否可以继续执行后续节点
     */
    private boolean waitForDelay(long delay) throws InterruptedException {
        long remainingMillis = delay;
        do {
            long segmentMillis = Math.min(remainingMillis, MAX_DELAY_SEGMENT_MILLIS);
            if (!waitForDelaySegment(segmentMillis)) {
                return false;
            }
            remainingMillis -= segmentMillis;
        } while (remainingMillis > 0L);
        return true;
    }

    /** 每段均在安全换算范围内，暂停时仍保留该段剩余时间，停止时立即唤醒。 */
    private boolean waitForDelaySegment(long delay) throws InterruptedException {
        long remainingNanos = delay * 1_000_000L;

        synchronized (pauseLock) {
            while (true) {
                if (state == PlayerState.STOPPING || state == PlayerState.IDLE) {
                    return false;
                }

                while (state == PlayerState.PAUSED) {
                    pauseLock.wait();

                    if (state == PlayerState.STOPPING || state == PlayerState.IDLE) {
                        return false;
                    }
                }

                if (remainingNanos <= 0L) {
                    return state == PlayerState.PLAYING;
                }

                long start = System.nanoTime();

                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);

                pauseLock.wait(millis, nanos);

                long elapsed = System.nanoTime() - start;
                remainingNanos -= elapsed;
            }
        }
    }

    /**
     * 通知播放状态变化。
     *
     * @param state 当前播放状态
     */
    private void notifyStateChange(PlayerState state) {
        if (onStateChange != null) {
            onStateChange.accept(state);
        }
    }

    /** 执行一个已经校验过的现有宏动作。 */
    private void executeAction(MacroAction action) {
        switch (action.getType()) {
            case MOUSE_MOVE -> robot.mouseMove(action.getX(), action.getY());

            case MOUSE_PRESS -> {
                robot.mouseMove(action.getX(), action.getY());
                int mask = getMouseButtonMask(action.getMouseButton());

                // 先记录，确保后续出现异常时清理逻辑能够尝试释放。
                pressedMouseButtons.add(mask);
                robot.mousePress(mask);
            }

            case MOUSE_RELEASE -> {
                robot.mouseMove(action.getX(), action.getY());
                int mask = getMouseButtonMask(action.getMouseButton());
                robot.mouseRelease(mask);
                pressedMouseButtons.remove(mask);
            }

            case MOUSE_WHEEL -> {
                robot.mouseMove(action.getX(), action.getY());
                robot.mouseWheel(action.getWheelAmount());
            }

            case KEY_PRESS -> {
                int keyCode = action.getKeyCode();
                pressedKeys.add(keyCode);
                robot.keyPress(keyCode);
            }

            case KEY_RELEASE -> {
                int keyCode = action.getKeyCode();
                robot.keyRelease(keyCode);
                pressedKeys.remove(keyCode);
            }
        }
    }

    /**
     * 获取鼠标按键对应的 AWT 掩码。
     *
     * @param button 鼠标按键
     * @return AWT 鼠标按键掩码
     */
    private int getMouseButtonMask(MouseButton button) {
        return switch (button) {
            case LEFT -> InputEvent.BUTTON1_DOWN_MASK;
            case RIGHT -> InputEvent.BUTTON3_DOWN_MASK;
            case MIDDLE -> InputEvent.BUTTON2_DOWN_MASK;
            case X1, X2 -> throw new UnsupportedOperationException("暂不支持模拟鼠标侧键");
        };
    }

    /**
     * 释放所有尚未释放的鼠标按钮和键盘按键。
     *
     * <p>单个输入释放失败时，继续尝试释放其他输入。</p>
     */
    private void releasePressedInputs() {
        for (int mask : pressedMouseButtons) {
            try {
                robot.mouseRelease(mask);
            } catch (RuntimeException e) {
                System.err.println("释放鼠标按钮失败：" + mask);
                e.printStackTrace();
            }
        }

        pressedMouseButtons.clear();

        for (int keyCode : pressedKeys) {
            try {
                robot.keyRelease(keyCode);
            } catch (RuntimeException e) {
                System.err.println("释放键盘按键失败：" + keyCode);
                e.printStackTrace();
            }
        }

        pressedKeys.clear();
    }
}
