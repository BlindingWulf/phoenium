package dev.phoenium.stage5probe;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Actual child JVM counters at the same native tick cuts as completed work. */
final class Stage5ResourceMeasurements {
    static Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        result.put("processCpuNanos", os.getProcessCpuTime());
        result.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        result.put("heapCommittedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted());
        result.put("nonHeapUsedBytes", ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        Map<String, Object> gc = new LinkedHashMap<>();
        ManagementFactory.getGarbageCollectorMXBeans().forEach(bean -> gc.put(bean.getName(),
            Map.of("count", bean.getCollectionCount(), "millis", bean.getCollectionTime())));
        result.put("gc", gc);
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled()) {
            result.put("allocatedBytes", threads.getTotalThreadAllocatedBytes());
        } else result.put("allocationUnavailable", true);
        return result;
    }
}
