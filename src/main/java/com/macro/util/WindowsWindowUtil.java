package com.macro.util;

import com.macro.model.TargetWindow;
import com.macro.model.WindowInfo;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows 窗口工具类。
 *
 * <p>用于根据屏幕坐标识别窗口，并获取窗口基本信息。</p>
 */
public final class WindowsWindowUtil {

    private static final User32Api USER32 = Native.load("user32", User32Api.class);

    /** 恢复窗口显示。 */
    private static final int SW_RESTORE = 9;

    private WindowsWindowUtil() {
    }

    /**
     * 根据屏幕坐标获取窗口句柄。
     *
     * @param x 屏幕 X 坐标
     * @param y 屏幕 Y 坐标
     * @return 窗口句柄
     */
    public static HWND getWindowFromPoint(int x, int y) {
        POINT.ByValue point = new POINT.ByValue();
        point.x = x;
        point.y = y;
        return USER32.WindowFromPoint(point);
    }

    /**
     * 获取窗口信息。
     *
     * @param hWnd 窗口句柄
     * @return 目标窗口信息
     */
    public static TargetWindow getTargetWindow(HWND hWnd) {
        if (hWnd == null) {
            return null;
        }

        return TargetWindow.create().setProcessName(getProcessName(hWnd)).setWindowTitle(getWindowTitle(hWnd)).setWindowClass(getWindowClass(hWnd));
    }

    /**
     * 获取窗口标题。
     *
     * @param hwnd 窗口句柄
     * @return 窗口标题
     */
    private static String getWindowTitle(HWND hwnd) {
        char[] buffer = new char[512];
        USER32.GetWindowTextW(hwnd, buffer, buffer.length);
        return Native.toString(buffer);
    }

    /**
     * 获取窗口类名。
     *
     * @param hwnd 窗口句柄
     * @return 窗口类名
     */
    public static String getWindowClass(WinDef.HWND hwnd) {
        char[] buffer = new char[256];
        USER32.GetClassNameW(hwnd, buffer, buffer.length);
        return Native.toString(buffer);
    }

    /**
     * 获取窗口进程 ID。
     *
     * @param hwnd 窗口句柄
     * @return 进程 ID
     */
    public static int getProcessId(WinDef.HWND hwnd) {
        int[] processId = new int[1];
        USER32.GetWindowThreadProcessId(hwnd, processId);
        return processId[0];
    }

    /**
     * 获取窗口所属进程名称。
     *
     * @param hwnd 窗口句柄
     * @return 进程名称，获取失败时返回空字符串
     */
    public static String getProcessName(WinDef.HWND hwnd) {
        int processId = getProcessId(hwnd);

        if (processId <= 0) {
            return "";
        }

        return ProcessHandle.of(processId).flatMap(process -> process.info().command()).map(path -> new java.io.File(path).getName()).orElse("");
    }

    /**
     * 获取进程可执行文件路径，用于窗口识别诊断。
     *
     * @param processId 进程编号
     * @return 可执行文件路径；无法获取时返回空字符串
     */
    public static String getExecutablePath(int processId) {
        if (processId <= 0) {
            return "";
        }

        return ProcessHandle.of(processId)
                .flatMap(process -> process.info().command())
                .orElse("");
    }

    /**
     * 根据屏幕坐标获取顶层窗口信息。
     *
     * @param x 屏幕横坐标
     * @param y 屏幕纵坐标
     * @return 窗口信息；未获取到窗口时返回 null
     */
    public static WindowInfo getWindowInfo(int x, int y) {
        WinDef.HWND hwnd = getWindowFromPoint(x, y);

        if (hwnd == null) {
            return null;
        }

        // 统一转换为顶层窗口，避免获取到 Chrome 等应用的子窗口。
        hwnd = getRootWindow(hwnd);

        return WindowInfo.create()
                .setHwnd(hwnd.toString())
                .setWindowTitle(getWindowTitle(hwnd))
                .setWindowClass(getWindowClass(hwnd))
                .setProcessId(getProcessId(hwnd))
                .setProcessName(getProcessName(hwnd));
    }


    /**
     * 根据目标窗口信息查找当前有效的顶层窗口句柄。
     *
     * <p>优先匹配进程名称，再尝试通过窗口标题定位。
     * 窗口类名仅作为辅助信息，不作为强制匹配条件，
     * 以兼容 Chrome 等具有特殊窗口结构的应用。</p>
     *
     * @param targetWindow 目标窗口信息
     * @return 匹配的窗口句柄；无法确定时返回 null
     */
    public static WinDef.HWND findWindow(TargetWindow targetWindow) {
        logFindStart(targetWindow);

        if (targetWindow == null) {
            logFindFailed(0);
            return null;
        }

        List<HWND> candidates = new ArrayList<>();
        int[] candidateCount = {0};

        USER32.EnumWindows((hWnd, data) -> {
            if (!USER32.IsWindowVisible(hWnd)) {
                return true;
            }

            candidateCount[0]++;
            WindowDiagnostic diagnostic = inspectWindow(hWnd);
            logCandidateWindow(targetWindow, diagnostic);

            String processName = diagnostic.processName();
            if (diagnostic.processNameError()
                    || processName == null
                    || targetWindow.getProcessName() == null
                    || !targetWindow.getProcessName().equalsIgnoreCase(processName)) {
                return true;
            }

            candidates.add(hWnd);
            return true;
        }, null);

        if (candidates.isEmpty()) {
            logFindFailed(candidateCount[0]);
            return null;
        }

        // 优先尝试标题精确匹配。
        String targetTitle = targetWindow.getWindowTitle();
        String targetClass = targetWindow.getWindowClass();

        // 浏览器标签页切换会改变顶层窗口标题，浏览器窗口优先按进程名和类名匹配。
        if (isSupportedBrowser(targetWindow.getProcessName())
                && targetClass != null
                && !targetClass.isBlank()) {
            List<HWND> browserMatches = new ArrayList<>();

            for (HWND hWnd : candidates) {
                if (targetClass.equals(getWindowClass(hWnd))) {
                    browserMatches.add(hWnd);
                }
            }

            if (!browserMatches.isEmpty()) {
                HWND matchedWindow = selectLargestWindow(browserMatches);
                logFindSuccess(matchedWindow);
                return matchedWindow;
            }
        }

        // 第一优先级：标题精确匹配。
        List<HWND> titleMatches = new ArrayList<>();

        if (targetTitle != null && !targetTitle.isBlank()) {
            for (HWND hWnd : candidates) {
                if (targetTitle.equals(getWindowTitle(hWnd))) {
                    titleMatches.add(hWnd);
                }
            }
        }

        // 标题唯一匹配时，直接返回。
        if (titleMatches.size() == 1) {
            logFindSuccess(titleMatches.get(0));
            return titleMatches.get(0);
        }

        // 第二优先级：在标题匹配结果中，通过窗口类名消歧。
        if (titleMatches.size() > 1) {
            List<HWND> classMatches = new ArrayList<>();

            if (targetClass != null && !targetClass.isBlank()) {
                for (HWND hWnd : titleMatches) {
                    if (targetClass.equals(getWindowClass(hWnd))) {
                        classMatches.add(hWnd);
                    }
                }
            }

            if (!classMatches.isEmpty()) {
                HWND matchedWindow = selectLargestWindow(classMatches);
                logFindSuccess(matchedWindow);
                return matchedWindow;
            }

            // 标题重复但没有任何窗口通过类名匹配。
            logFindFailed(candidateCount[0]);
            return null;
        }

        // 第三优先级：标题已经变化时，尝试通过类名匹配。
        if (targetClass != null && !targetClass.isBlank()) {
            List<HWND> classMatches = new ArrayList<>();

            for (HWND hWnd : candidates) {
                if (targetClass.equals(getWindowClass(hWnd))) {
                    classMatches.add(hWnd);
                }
            }

            if (classMatches.size() == 1) {
                logFindSuccess(classMatches.get(0));
                return classMatches.get(0);
            }
        }

        // 保留原有的单候选窗口回退逻辑。
        if (candidates.size() == 1) {
            logFindSuccess(candidates.get(0));
            return candidates.get(0);
        }

        // 多个候选窗口仍无法唯一确定。
        logFindFailed(candidateCount[0]);
        return null;
    }

    /** 判断目标进程是否为允许窗口标题变化的浏览器。 */
    private static boolean isSupportedBrowser(String processName) {
        return "chrome.exe".equalsIgnoreCase(processName)
                || "msedge.exe".equalsIgnoreCase(processName)
                || "firefox.exe".equalsIgnoreCase(processName);
    }

    /**
     * 从完全匹配的可见窗口中选择面积最大的窗口。
     *
     * <p>所有矩形都无法读取时，回退到列表中的第一个窗口。</p>
     */
    private static HWND selectLargestWindow(List<HWND> windows) {
        HWND largestWindow = windows.get(0);
        long largestArea = -1L;

        for (HWND hWnd : windows) {
            RECT rect = new RECT();
            if (!USER32.GetWindowRect(hWnd, rect)) {
                continue;
            }

            long width = Math.max(0L, (long) rect.right - rect.left);
            long height = Math.max(0L, (long) rect.bottom - rect.top);
            long area = width * height;

            if (area > largestArea) {
                largestArea = area;
                largestWindow = hWnd;
            }
        }

        return largestWindow;
    }

    /** 输出窗口查找开始时的目标信息。 */
    private static void logFindStart(TargetWindow targetWindow) {
        System.out.println("========== Find Window Start ==========");
        System.out.println("Target HWND: <目标数据未保存窗口句柄>");
        System.out.println("Target ProcessName: " + diagnosticValue(
                targetWindow == null ? null : targetWindow.getProcessName()
        ));
        System.out.println("Target WindowTitle: " + diagnosticValue(
                targetWindow == null ? null : targetWindow.getWindowTitle()
        ));
        System.out.println("Target WindowClass: " + diagnosticValue(
                targetWindow == null ? null : targetWindow.getWindowClass()
        ));
        System.out.println("=======================================");
    }

    /** 读取候选窗口的诊断字段，单个字段失败时仍保留其他字段。 */
    private static WindowDiagnostic inspectWindow(HWND hWnd) {
        int processId = 0;
        boolean processIdError = false;
        String processName = null;
        boolean processNameError = false;
        String windowTitle = null;
        boolean windowTitleError = false;
        String windowClass = null;
        boolean windowClassError = false;

        try {
            processId = getProcessId(hWnd);
        } catch (RuntimeException | LinkageError error) {
            processIdError = true;
        }
        try {
            processName = getProcessName(hWnd);
        } catch (RuntimeException | LinkageError error) {
            processNameError = true;
        }
        try {
            windowTitle = getWindowTitle(hWnd);
        } catch (RuntimeException | LinkageError error) {
            windowTitleError = true;
        }
        try {
            windowClass = getWindowClass(hWnd);
        } catch (RuntimeException | LinkageError error) {
            windowClassError = true;
        }

        return new WindowDiagnostic(
                hWnd,
                processId,
                processIdError,
                processName,
                processNameError,
                windowTitle,
                windowTitleError,
                windowClass,
                windowClassError
        );
    }

    /** 输出枚举到的候选窗口。 */
    private static void logCandidateWindow(
            TargetWindow targetWindow,
            WindowDiagnostic diagnostic
    ) {
        System.out.println("---------- Candidate Window ----------");
        System.out.println("HWND: " + diagnostic.hWnd());
        System.out.println("PID: " + (diagnostic.processIdError()
                ? "<error>"
                : diagnostic.processId()));
        System.out.println("ProcessName: " + diagnosticField(
                diagnostic.processName(),
                diagnostic.processNameError()
        ));
        System.out.println("WindowTitle: " + diagnosticField(
                diagnostic.windowTitle(),
                diagnostic.windowTitleError()
        ));
        System.out.println("WindowClass: " + diagnosticField(
                diagnostic.windowClass(),
                diagnostic.windowClassError()
        ));
        System.out.println("Match Result: " + describeMatchResult(targetWindow, diagnostic));
        System.out.println("--------------------------------------");
    }

    /** 根据现有匹配字段描述候选窗口匹配情况。 */
    private static String describeMatchResult(
            TargetWindow targetWindow,
            WindowDiagnostic diagnostic
    ) {
        if (diagnostic.processNameError()) {
            return "process error";
        }
        if (diagnostic.processName() == null
                || targetWindow.getProcessName() == null
                || !targetWindow.getProcessName().equalsIgnoreCase(diagnostic.processName())) {
            return "process mismatch";
        }

        boolean titleMatches = targetWindow.getWindowTitle() != null
                && !targetWindow.getWindowTitle().isBlank()
                && !diagnostic.windowTitleError()
                && targetWindow.getWindowTitle().equals(diagnostic.windowTitle());
        boolean classMatches = targetWindow.getWindowClass() != null
                && !targetWindow.getWindowClass().isBlank()
                && !diagnostic.windowClassError()
                && targetWindow.getWindowClass().equals(diagnostic.windowClass());

        if (titleMatches && classMatches) return "process/title/class match";
        if (titleMatches) return "process/title match; class mismatch";
        if (classMatches) return "process/class match; title mismatch";
        return "process match; title/class mismatch";
    }

    /** 输出窗口查找成功结果。 */
    private static void logFindSuccess(HWND hWnd) {
        System.out.println("========== Find Window Success ==========");
        System.out.println("HWND: " + hWnd);
        System.out.println("=========================================");
    }

    /** 输出窗口查找失败结果。 */
    private static void logFindFailed(int candidateCount) {
        System.out.println("========== Find Window Failed ==========");
        System.out.println("Candidate Count: " + candidateCount);
        System.out.println("=======================================");
    }

    /** 格式化目标窗口的诊断值。 */
    private static String diagnosticValue(String value) {
        return value == null || value.isBlank() ? "<未提供>" : value;
    }

    /** 格式化候选窗口字段。 */
    private static String diagnosticField(String value, boolean error) {
        if (error) return "<error>";
        return value == null || value.isBlank() ? "<empty>" : value;
    }

    /** 候选窗口诊断快照。 */
    private record WindowDiagnostic(
            HWND hWnd,
            int processId,
            boolean processIdError,
            String processName,
            boolean processNameError,
            String windowTitle,
            boolean windowTitleError,
            String windowClass,
            boolean windowClassError
    ) {
    }


    /**
     * 激活指定窗口。
     *
     * <p>如果目标窗口处于最小化状态，会先恢复窗口，
     * 然后将其设置为前台窗口。</p>
     *
     * @param hWnd 窗口句柄
     * @return 是否成功激活
     */
    public static boolean activateWindow(HWND hWnd) {
        if (hWnd == null) {
            return false;
        }

        if (USER32.IsIconic(hWnd)) {
            USER32.ShowWindow(hWnd, SW_RESTORE);
        }

        return USER32.SetForegroundWindow(hWnd);
    }

    /**
     * 获取当前前台窗口。
     *
     * @return 当前前台窗口句柄
     */
    public static HWND getForegroundWindow() {
        return USER32.GetForegroundWindow();
    }

    /** 获取最外层顶级窗口的标志。 */
    private static final int GA_ROOT = 2;

    /**
     * 获取指定窗口所属的顶层窗口。
     *
     * @param hWnd 窗口句柄
     * @return 顶层窗口句柄
     */
    public static HWND getRootWindow(HWND hWnd) {
        if (hWnd == null) {
            return null;
        }

        HWND rootWindow = USER32.GetAncestor(hWnd, GA_ROOT);
        return rootWindow == null ? hWnd : rootWindow;
    }
}
