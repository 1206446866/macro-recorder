package com.macro.recognition;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** 在识别期间采样当前 Windows 进程的实际工作集。 */
final class ProcessMemoryMonitor implements AutoCloseable {

    private static final long SAMPLE_INTERVAL_NANOS = 5_000_000L;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong peakWorkingSetBytes = new AtomicLong();
    private final long baselineWorkingSetBytes;
    private final Thread samplerThread;

    /** 记录当前进程工作集基线，能够查询时启动后台采样；不支持查询时保留不可用状态。 */
    private ProcessMemoryMonitor() {
        baselineWorkingSetBytes = queryWorkingSetBytes();
        peakWorkingSetBytes.set(Math.max(0L, baselineWorkingSetBytes));
        if (baselineWorkingSetBytes < 0L) {
            samplerThread = null;
            return;
        }

        running.set(true);
        samplerThread = new Thread(this::sampleLoop, "recognition-memory-monitor");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    /** 开始一次识别期间的内存采样，调用方在识别结束后负责关闭。 */
    static ProcessMemoryMonitor start() {
        return new ProcessMemoryMonitor();
    }

    /** 返回采样峰值相对基线的非负增量；系统无法查询工作集时返回 -1。 */
    long peakIncreaseBytes() {
        if (baselineWorkingSetBytes < 0L) {
            return -1L;
        }
        return Math.max(
                0L,
                peakWorkingSetBytes.get() - baselineWorkingSetBytes
        );
    }

    /** 按采样间隔更新峰值，收到停止请求后再保留最后一次有效采样。 */
    private void sampleLoop() {
        while (running.get()) {
            sampleNow();
            LockSupport.parkNanos(SAMPLE_INTERVAL_NANOS);
        }
        sampleNow();
    }

    /** 读取一次工作集并原子更新峰值，忽略本次不可用的查询结果。 */
    private void sampleNow() {
        long workingSetBytes = queryWorkingSetBytes();
        if (workingSetBytes >= 0L) {
            peakWorkingSetBytes.accumulateAndGet(workingSetBytes, Math::max);
        }
    }

    /** 停止采样线程并等待短暂收尾，再记录最终样本；重复关闭不再启动清理。 */
    @Override
    public void close() {
        if (samplerThread == null || !running.getAndSet(false)) {
            return;
        }
        samplerThread.interrupt();
        try {
            samplerThread.join(100L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        sampleNow();
    }

    /** 查询当前 Windows 进程的工作集字节数；平台不支持或原生查询失败时返回 -1。 */
    private long queryWorkingSetBytes() {
        if (!Platform.isWindows()) {
            return -1L;
        }
        ProcessMemoryCounters counters = new ProcessMemoryCounters();
        WinNT.HANDLE process = Kernel32.INSTANCE.GetCurrentProcess();
        boolean success = PsapiApi.INSTANCE.GetProcessMemoryInfo(
                process,
                counters,
                counters.size()
        );
        return success ? counters.workingSetSize.longValue() : -1L;
    }

    /** Windows PSAPI 中的进程内存查询入口。 */
    private interface PsapiApi extends StdCallLibrary {

        PsapiApi INSTANCE = Native.load(
                "psapi",
                PsapiApi.class,
                W32APIOptions.DEFAULT_OPTIONS
        );

        /** 读取指定进程的内存统计到调用方提供的原生结构。 */
        boolean GetProcessMemoryInfo(
                WinNT.HANDLE process,
                ProcessMemoryCounters counters,
                int size
        );
    }

    /** PROCESS_MEMORY_COUNTERS 的 JNA 映射。 */
    @Structure.FieldOrder({
            "size",
            "pageFaultCount",
            "peakWorkingSetSize",
            "workingSetSize",
            "quotaPeakPagedPoolUsage",
            "quotaPagedPoolUsage",
            "quotaPeakNonPagedPoolUsage",
            "quotaNonPagedPoolUsage",
            "pagefileUsage",
            "peakPagefileUsage"
    })
    public static final class ProcessMemoryCounters extends Structure {

        public WinDef.DWORD size = new WinDef.DWORD();
        public WinDef.DWORD pageFaultCount = new WinDef.DWORD();
        public BaseTSD.SIZE_T peakWorkingSetSize = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T workingSetSize = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T quotaPeakPagedPoolUsage = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T quotaPagedPoolUsage = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T quotaPeakNonPagedPoolUsage = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T quotaNonPagedPoolUsage = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T pagefileUsage = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T peakPagefileUsage = new BaseTSD.SIZE_T();

        /** 按当前进程位数计算结构长度，供 Windows 校验内存统计缓冲区。 */
        private ProcessMemoryCounters() {
            size.setValue(size());
        }
    }
}
