import java.util.*;
import java.util.concurrent.*;

/**
 * 确定性复现 JDK 8 ConcurrentHashMap 迭代器在扩容期间的"漏看"，并验证"重复访问"不可构造。
 *
 * 机制（源码级）：
 *   - Traverser 按列扫描旧表（步长 baseSize），每个 bin 恰好访问一次；
 *   - 撞到 ForwardingNode 时下降，只扫 {i, i+n}（旧 bin i 分裂出的两个新 bin）；
 *   - 若迭代器已扫过 bin i，之后再向该 bin 插入元素、且该 bin 随后被迁移，
 *     新表 {i, i+n} 没有任何可达路径 -> 漏看。重复访问则要求旧表扫过一次、
 *     新表再扫一次同一节点，而新表 {i, i+n} 只能由"在旧 bin i 处下降"到达，
 *     迭代器经过旧 bin i 时若未转发就只访问一次、不下降 -> 结构上不可能重复。
 *
 * 步骤：
 *   1. 装载到恰好低于扩容阈值（表长 2^18 = 262144，阈值 196608）；
 *   2. 迭代器走几步（扫过 bin 0）；
 *   3. 挑选 6 个 spread(k) & (n-1) == 0 的 key，插入到 bin 0（transfer 尚未开始，bin 0 还是活 bin）；
 *   4. 触发扩容（put 越过阈值，transfer 在触发线程内联完成，bin 0 最后被转发）；
 *   5. 完成本趟遍历：断言恰好漏掉那 6 个 key，且全程无重复；
 *   6. 再跑一趟完整遍历：全部可见（漏看只在当趟成立，下一趟从当前表重新扫描）。
 */
public class ChmIteratorMissProbe {

    public static void main(String[] args) throws Exception {
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>(16);

        // 1. 预装载: 表长 2^18, 阈值 = 0.75 * 262144 = 196608
        int threshold = 196_608;
        for (int k = 1; k < threshold; k++) map.put(k, k);
        int n = tableLen(map);
        System.out.println("preload size=" + map.size() + " tableLen=" + n + " threshold=" + threshold);

        // 挑选 6 个落在旧表 bin 0 的 key: spread(k) & (n-1) == 0
        List<Integer> targets = new ArrayList<>();
        for (int k = 262144; targets.size() < 6; k++) {
            if (((k ^ (k >>> 16)) & (n - 1)) == 0) targets.add(k);
        }
        System.out.println("target keys (old-table bin 0): " + targets);

        // 2. 迭代器走几步: 构造器里的首次 advance() 即已扫过 bin 0
        Iterator<Integer> it = map.keySet().iterator();
        Set<Integer> seen1 = new HashSet<>();
        for (int i = 0; i < 3 && it.hasNext(); i++) seen1.add(it.next());

        // 3. 向 bin 0 插入目标 key (bin 0 尚未被转发)
        for (int k : targets) map.put(k, k);

        // 4. 触发扩容: 越过阈值后 transfer 在当前线程内联跑完, 全表转发
        for (int k = threshold; k < threshold + 2; k++) map.put(k, k);
        System.out.println("after resize trigger: size=" + map.size() + " tableLen=" + tableLen(map));

        // 5. 完成本趟遍历
        long visited = 0, dup = 0;
        while (it.hasNext()) {
            int k = it.next();
            visited++;
            if (!seen1.add(k)) dup++;
        }
        List<Integer> missed = new ArrayList<>();
        for (int k : targets) if (!seen1.contains(k)) missed.add(k);
        System.out.printf("pass1: visited=%d (size=%d) dup=%d missed=%s%n",
                visited, map.size(), dup, missed);

        // 6. 第二趟: 全部可见
        Set<Integer> seen2 = new HashSet<>();
        long visited2 = 0, dup2 = 0;
        for (int k : map.keySet()) {
            visited2++;
            if (!seen2.add(k)) dup2++;
        }
        List<Integer> missed2 = new ArrayList<>();
        for (int k : targets) if (!seen2.contains(k)) missed2.add(k);
        System.out.printf("pass2: visited=%d dup=%d missed=%s%n", visited2, dup2, missed2);
    }

    /** 反射读当前 table 长度, 仅用于展示 */
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
