import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.security.MessageDigest;

/**
 * 第31期实验：连接池与资源隔离
 * 全部用纯 JDK，模拟"假连接"（service time = sleep），测量池大小对吞吐/等待的影响。
 */
public class Exp31 {

    static final long SERVICE_NS = 2_000_000L; // 2ms 固定"查询"耗时

    /** 极简连接池：Semaphore 控总量 + 队列复用 */
    static final class Pool {
        final int max;
        final Semaphore permits;
        final ArrayDeque<Conn> idle = new ArrayDeque<>();

        Pool(int max, boolean fair) {
            this.max = max;
            this.permits = new Semaphore(max, fair);
            for (int i = 0; i < max; i++) idle.add(new Conn(i));
        }

        Conn borrow(long timeoutMs) throws Exception {
            if (!permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new SQLTimeoutException(
                    "connection is not available, request timed out after " + timeoutMs + "ms");
            }
            synchronized (idle) { return idle.poll(); }
        }

        void giveBack(Conn c) {
            synchronized (idle) { idle.add(c); }
            permits.release();
        }
    }

    static final class Conn {
        final int id;
        Conn(int id) { this.id = id; }
        void query() throws Exception { Thread.sleep(SERVICE_NS / 1_000_000L, (int) (SERVICE_NS % 1_000_000L)); }
    }

    static final class SQLTimeoutException extends RuntimeException {
        SQLTimeoutException(String m) { super(m); }
    }

    static double pct(long[] a, double p) {
        long[] b = a.clone();
        Arrays.sort(b);
        int i = (int) Math.min(b.length - 1, Math.ceil(p / 100.0 * b.length) - 1);
        return b[Math.max(0, i)] / 1e6;
    }

    /** 实验2：池大小 vs 吞吐/等待（200 工作线程闭环） */
    static void expPoolSize() throws Exception {
        System.out.println("== EXP2 池大小 x 吞吐/等待（200 工作线程，service=2ms） ==");
        System.out.println("pool\tthruput_qps\tp50_wait_ms\tp99_wait_ms\tmax_wait_ms");
        int workers = 200, durMs = 3000;
        for (int c : new int[]{2, 5, 10, 20, 40, 80}) {
            Pool pool = new Pool(c, false);
            ExecutorService es = Executors.newFixedThreadPool(workers);
            AtomicInteger done = new AtomicInteger();
            AtomicBoolean stop = new AtomicBoolean();
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                fs.add(es.submit(() -> {
                    while (!stop.get()) {
                        long t0 = System.nanoTime();
                        Conn conn;
                        try {
                            conn = pool.borrow(30_000);
                        } catch (Exception e) { return null; }
                        long t1 = System.nanoTime();
                        try { conn.query(); } catch (Exception ignore) { }
                        pool.giveBack(conn);
                        long t2 = System.nanoTime();
                        rec(t1 - t0, t2 - t0);
                        done.incrementAndGet();
                    }
                    return null;
                }));
            }
            Thread.sleep(durMs);
            stop.set(true);
            for (Future<?> f : fs) f.get(35, TimeUnit.SECONDS);
            es.shutdownNow();
            long[] w = WAITS.stream().mapToLong(Long::longValue).toArray();
            System.out.printf("%d\t%d\t\t%.1f\t\t%.1f\t\t%.1f%n",
                c, done.get() * 1000 / durMs, pct(w, 50), pct(w, 99), pct(w, 100));
            WAITS.clear();
        }
    }
    static final long[] WAIT = new long[1];
    static final List<Long> WAITS = Collections.synchronizedList(new ArrayList<>());
    static void rec(long waitNs, long sojournNs) { WAITS.add(waitNs); }

    /** 实验3：CPU 超配（模拟 DB 侧连接数超过核数） */
    static void expOversubscribe() throws Exception {
        System.out.println("\n== EXP3 并发度 x 吞吐（CPU 密集，本机 12 核 / 16 逻辑核） ==");
        System.out.println("threads\telapsed_ms\tthruput_ops_s\tvs_12");
        int tasks = 2400;
        double base = 0;
        // 预热：先跑一轮 12 线程，消掉 JIT 冷启动偏差
        runCpu(12, 600);
        for (int t : new int[]{4, 8, 12, 16, 24, 48, 96}) {
            long best = Long.MAX_VALUE;
            for (int rep = 0; rep < 2; rep++) best = Math.min(best, runCpu(t, tasks));
            double thr = tasks * 1000.0 / best;
            if (t == 12) base = thr;
            System.out.printf("%d\t%d\t\t%.0f\t\t%s%n", t, best, thr,
                base == 0 ? "-" : String.format("%.2fx", thr / base));
        }
    }

    static long runCpu(int threads, int tasks) throws Exception {
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            fs.add(es.submit(() -> {
                start.await();
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] b = "abcdefgh".getBytes();
                for (int k = 0; k < 8000; k++) { md.update(b); b = md.digest(); b = Arrays.copyOf(b, 8); }
                return null;
            }));
        }
        long t0 = System.nanoTime();
        start.countDown();
        for (Future<?> f : fs) f.get();
        long el = (System.nanoTime() - t0) / 1_000_000;
        es.shutdownNow();
        return el;
    }

    /** 实验4：池内嵌套获取 —— 10 连接 / 10 线程 / 每请求要 2 个连接 */
    static void expNestedDeadlock() throws Exception {
        System.out.println("\n== EXP4 嵌套借连接（pool=10, threads=10, 每请求需 2 个连接） ==");
        Pool pool = new Pool(10, false);
        ExecutorService es = Executors.newFixedThreadPool(10);
        AtomicInteger ok = new AtomicInteger(), fail = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(10);
        CyclicBarrier allGotFirst = new CyclicBarrier(10);
        long t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            es.submit(() -> {
                try {
                    Conn a = pool.borrow(3000);
                    allGotFirst.await(5, TimeUnit.SECONDS); // 10 个线程都先握住第 1 个
                    Conn b = pool.borrow(3000);            // 第 2 个永远拿不到
                    a.query(); b.query();
                    pool.giveBack(b); pool.giveBack(a);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                    if (fail.get() == 1) System.out.println("  首个失败文案: " + e.getMessage());
                } finally { latch.countDown(); }
            });
        }
        latch.await(15, TimeUnit.SECONDS);
        long el = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("  ok=%d fail=%d elapsed=%dms%n", ok.get(), fail.get(), el);
        es.shutdownNow();
    }

    /** 实验5：泄漏 —— 每 20 请求漏 1 个连接，看从"变慢"到"报错" */
    static void expLeak() throws Exception {
        System.out.println("\n== EXP5 连接泄漏（pool=10, timeout=500ms, 每 20 次漏 1 个） ==");
        System.out.println("req\tleaked\twait_ms\tresult");
        Pool pool = new Pool(10, false);
        int leaked = 0, timeouts = 0;
        long firstTimeoutReq = -1, firstSlowReq = -1;
        for (int r = 1; r <= 700; r++) {
            long t0 = System.nanoTime();
            try {
                Conn c = pool.borrow(500);
                c.query();
                if (r % 20 == 0) { leaked++; }   // 忘了归还
                else pool.giveBack(c);
                long w = (System.nanoTime() - t0) / 1_000_000;
                if (firstSlowReq < 0 && w > 20) {
                    firstSlowReq = r;
                    System.out.printf("%d\t%d\t\t%d\t\tOK(开始变慢)%n", r, leaked, w);
                }
                if (r <= 2) System.out.printf("%d\t%d\t\t%d\t\tOK%n", r, leaked, w);
            } catch (Exception e) {
                timeouts++;
                if (firstTimeoutReq < 0) {
                    firstTimeoutReq = r;
                    System.out.printf("%d\t%d\t\t500\t\t首个TIMEOUT%n", r, leaked);
                    System.out.println("  文案: " + e.getMessage());
                }
                if (r % 40 == 0) System.out.printf("%d\t%d\t\t500\t\tTIMEOUT%n", r, leaked);
            }
        }
        System.out.printf("  首次变慢@req=%d, 首次超时@req=%d, 累计泄漏=%d, 累计超时=%d, 剩余idle=%d%n",
            firstSlowReq, firstTimeoutReq, leaked, timeouts, pool.idle.size());
    }

    /** 实验6：资源隔离 —— 慢依赖拖垮快依赖 */
    static void expIsolation() throws Exception {
        System.out.println("\n== EXP6 共享池 vs 隔离池（A=2ms 快，B=500ms 慢，流量 50/50） ==");
        System.out.println("mode\tA_p50_ms\tA_p99_ms\tA_ok\tA_timeout");
        // 6a 共享池 20
        runIso(true, 20, 20);
        // 6b 隔离池 20 + 20
        runIso(false, 20, 20);
    }

    static void runIso(boolean shared, int capA, int capB) throws Exception {
        Pool poolA = new Pool(capA, false);
        Pool poolB = shared ? poolA : new Pool(capB, false);
        ExecutorService es = Executors.newFixedThreadPool(100);
        List<Long> waitsA = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger okA = new AtomicInteger(), toA = new AtomicInteger();
        long end = System.currentTimeMillis() + 2500;
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final boolean isA = (i % 2 == 0);
            fs.add(es.submit(() -> {
                while (System.currentTimeMillis() < end) {
                    try {
                        if (isA) {
                            long t0 = System.nanoTime();
                            Conn c = poolA.borrow(500);
                            waitsA.add(System.nanoTime() - t0);
                            Thread.sleep(2);
                            poolA.giveBack(c);
                            okA.incrementAndGet();
                        } else {
                            Conn c = poolB.borrow(500);
                            Thread.sleep(500);
                            poolB.giveBack(c);
                        }
                    } catch (Exception e) {
                        if (isA) toA.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(10, TimeUnit.SECONDS);
        es.shutdownNow();
        long[] w = waitsA.stream().mapToLong(Long::longValue).toArray();
        System.out.printf("%s\t%d\t\t%d\t\t%.1f\t\t%d%n",
            shared ? "SHARED" : "ISOLATED", okA.get(), toA.get(), pct(w, 99), w.length);
    }

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? args[0] : "all";
        if (which.equals("all") || which.equals("2")) expPoolSize();
        if (which.equals("all") || which.equals("3")) expOversubscribe();
        if (which.equals("all") || which.equals("4")) expNestedDeadlock();
        if (which.equals("all") || which.equals("5")) expLeak();
        if (which.equals("all") || which.equals("6")) expIsolation();
    }
}
