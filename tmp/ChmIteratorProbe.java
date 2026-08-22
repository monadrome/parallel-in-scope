import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/**
 * 验证 ConcurrentHashMap（JDK 8）迭代器在并发扩容期间：
 *   1) 是否可能重复访问同一个节点（同一 key 在一趟遍历中出现两次）；
 *   2) 是否可能漏看（遍历起点已存在、但本趟从未出现的 key）。
 *
 * 方法：writer 线程分批写入，让 map 反复跨越扩容阈值触发 transfer；
 * 主线程反复做慢速遍历与扩容窗口重叠。每趟用 HashSet 记录已见 key：
 *   - 重复出现 = 重复访问（key 全局唯一且不删除，同 key 两次 ⇔ 同一 Node 被访问两次）
 *   - 起点快照里有、本趟没见 = 漏看（writer 只增不删，快照内 key 必然存在）
 */
public class ChmIteratorProbe {

    public static void main(String[] args) throws Exception {
        int preload       = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;   // 初始装载
        long durationMs   = args.length > 1 ? Long.parseLong(args[1]) : 20_000;      // 运行时长
        int parkNanos     = args.length > 2 ? Integer.parseInt(args[2]) : 2_000;     // 每个元素 park 纳秒（放慢遍历）
        int burst         = args.length > 3 ? Integer.parseInt(args[3]) : 100_000;   // writer 每批写入量
        long burstPauseMs = args.length > 4 ? Long.parseLong(args[4]) : 1_000;       // 每批之间的停顿
        long fastPauseNs  = args.length > 5 ? Long.parseLong(args[5]) : 100;         // 批内每次 put 的停顿

        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>(16);
        for (int i = 0; i < preload; i++) map.put(i, i);
        System.out.println("preload=" + preload + " size=" + map.size()
                + " tableLen=" + tableLen(map)
                + "（遍历每个元素 park " + parkNanos + "ns）");

        AtomicBoolean stop = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            int k = preload;
            while (!stop.get()) {
                for (int b = 0; b < burst; b++) {          // 快速加一批，跨过扩容阈值
                    map.put(k, k);
                    k++;
                    if (fastPauseNs > 0) LockSupport.parkNanos(fastPauseNs);
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(burstPauseMs)); // 停一下，让慢遍历走进扩容窗口
            }
        }, "writer");
        writer.setDaemon(true);
        writer.start();

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
        long passes = 0, totalVisited = 0, totalDup = 0, totalMiss = 0;
        long passesWithDup = 0, passesWithMiss = 0;

        while (System.nanoTime() < deadline) {
            passes++;
            Set<Integer> startKeys = new HashSet<>(map.keySet()); // 本趟起点快照
            Set<Integer> seenKeys = new HashSet<>();

            long visited = 0, dup = 0, miss = 0;
            int firstDupKey = -1;

            for (Integer k : map.keySet()) {
                visited++;
                if (!seenKeys.add(k)) {
                    dup++;
                    if (firstDupKey < 0) firstDupKey = k;
                }
                LockSupport.parkNanos(parkNanos);
            }

            for (int k : startKeys) if (!seenKeys.contains(k)) miss++;

            totalVisited += visited;
            totalDup += dup;
            totalMiss += miss;
            if (dup > 0) {
                passesWithDup++;
                System.out.printf("PASS %d: visited=%d dup=%d miss=%d firstDupKey=%d%n",
                        passes, visited, dup, miss, firstDupKey);
            }
            if (miss > 0) passesWithMiss++;
        }

        stop.set(true);
        System.out.printf("==== %dms: passes=%d totalVisited=%d finalTableLen=%d%n"
                        + "     重复访问节点: %d（命中 %d 趟）%n"
                        + "     漏看 key: %d（命中 %d 趟）%n",
                durationMs, passes, totalVisited, tableLen(map),
                totalDup, passesWithDup, totalMiss, passesWithMiss);
    }

    /** 反射读当前 table 长度，仅用于展示扩容进度 */
    static int tableLen(ConcurrentHashMap<?, ?> map) {
        try {
            java.lang.reflect.Field f = ConcurrentHashMap.class.getDeclaredField("table");
            f.setAccessible(true);
            Object[] t = (Object[]) f.get(map);
            return t == null ? 0 : t.length;
        } catch (Exception e) {
            return -1;
        }
    }
}
