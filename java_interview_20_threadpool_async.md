# Java 面试深水区 20 · 线程池深水区与异步编排：29 位里装下的并发

> 第 03 期已经讲过「七个参数 + 四种拒绝策略」，那是会背就能过的一层。这一期只干一件事：**把 `ThreadPoolExecutor` 那一个 int 拆开看**——3 位状态 + 29 位线程数，为什么必须挤在同一个 int 里；`execute()` 里那三次 CAS 和一次回滚分别在挡什么；`getTask()` 的 `timedOut` 为什么要多转一圈才退出；`CompletableFuture` 的依赖栈为什么是 Treiber 栈；虚拟线程来了以后，`newFixedThreadPool` 到底该不该删。
>
> `由浅入深`：L0 三笔账 → L1 ctl 29 位 → L2 三次 CAS → L3 参数可算 → L4 getTask → L5 依赖栈 → L6 工作窃取 → L7 编排实战 → L8 拒绝与关闭 → L9 监控 → L10 虚拟线程 → L11 生产六坑 → L12 数字速查 → L13 十四问答 → L14 三道模拟面试
>
> 本期源码口径：**JDK 21.0.7**，关键实现均已对照 `src.zip` 核对，不凭记忆。

---

## L0 线程池不是对象池，是一个带队列的限流器

先纠一个几乎所有人都背错的定性。教科书说「线程池复用线程，避免频繁创建销毁的开销」——这话只对了 10%。

如果目标只是「复用」，那把线程用完放回一个 `List` 里就够了，不需要 `corePoolSize`、`maximumPoolSize`、队列、拒绝策略这一整套。线程池真正在做的事是：**把「无限并发」压缩成「有限并发 + 排队」**。它是流量整形器，池化只是实现手段。

```text
                  创建成本      栈预留        并发上限
─────────────────────────────────────────────────────────────────────
每任务 new Thread   60 μs       1 MB/线程     系统线程上限
固定大小线程池      一次 200 次   200 MB(固定)  200，其余排队
每任务虚拟线程      0.3 μs       按需，KB 级   百万级（堆决定）

1 万并发下的栈内存（对数轴，每格 10 倍）
  10MB ──────┬──────────┬──────────┬──────────┬──────────
             ███        │          │          │
  虚拟线程   16MB        │          │          │
             ██████████ │          │          │
  池化 200      200MB ──┼──────────┤          │
             ██████████████████████████████   │
  直连 1 万               10GB ─────┼──────────┴──────────
```

**池化省的是「200 次创建」（约 12 ms），代价是「9800 个请求排队等 200 个人干完」。** 这就是为什么「线程池调优」调的从来不是性能，而是「并发度与延迟的兑换比例」。

### 三笔账，先算清楚再谈参数

| 账目 | 量级 | 谁在付 | 怎么省 |
|---|---|---|---|
| 创建 / 销毁 | 50~100 μs（Linux `clone()` + 栈分配 + 内核 task_struct） | 频繁创建时 CPU | **池化**：一次创建用 N 年 |
| 栈内存 | 默认 1 MB **预留**，实际 commit 约 8~16 KB（`-Xss`） | 虚拟地址空间 / 物理内存 | 池化限数量；或虚拟线程 |
| 上下文切换 | 3~8 μs，线程越多切换越频繁 | CPU（sys 态） | 线程数别超过核数太多 |

第三条是关键，也是面试里最能分出层次的一句：**线程数不是越多越好，超过「并发度需求」之后每多一个线程都在增加切换成本，而不增加吞吐。** 吞吐在某个点之后就完全由 CPU、IO、锁决定，线程只是排队的人。

- **面试口径**：「线程池的核心作用是**资源隔离与流量整形**：用有限的线程数把无界的并发请求转成有界的队列，从而把『过载』从『打挂数据库』变成『排队变慢』。复用线程只是顺带的收益。」——这句话说出来，比背七个参数有效得多。

---

## L1 ctl：3 位状态 + 29 位线程数，为什么要挤在一起

打开 `ThreadPoolExecutor` 的第一行关键代码就是它。注意 JDK 21 里这个常量已经改名叫 `COUNT_MASK`（JDK 18 及以前叫 `CAPACITY`），面试时能顺口提一句是加分项。

```java
private static final int COUNT_BITS = Integer.SIZE - 3;      // 29
private static final int COUNT_MASK = (1 << COUNT_BITS) - 1; // 536870911 = 2^29 - 1

// 高 3 位存状态（用负数表示，方便 compareAndSet 时整体比较）
private static final int RUNNING    = -1 << COUNT_BITS;  // 111 0...0
private static final int SHUTDOWN   =  0 << COUNT_BITS;  // 000 0...0
private static final int STOP       =  1 << COUNT_BITS;  // 001 0...0
private static final int TIDYING    =  2 << COUNT_BITS;  // 010 0...0
private static final int TERMINATED =  3 << COUNT_BITS;  // 011 0...0

private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));

private static int ctlOf(int rs, int wc) { return rs | wc; }         // 打包
private static int runStateOf(int c)     { return c & ~COUNT_MASK; } // 取高 3 位
private static int workerCountOf(int c)  { return c & COUNT_MASK; }  // 取低 29 位
```

```text
  bit 31·30·29                          bit 28 ───────────── 0
 ┌───────────┬──────────────────────────────────────────────────┐
 │ runState  │              workerCount                         │
 │  3 位     │              29 位 / 最大 536,870,911             │
 └───────────┴──────────────────────────────────────────────────┘

五种状态（高 3 位的十进制值，可直接比大小）
 RUNNING  -536870912  ──→ SHUTDOWN  0  ──→ STOP  536870912
                                              │
                                              ▼
                              TERMINATED 1610612736 ←── TIDYING 1073741824
```

① 状态用「有序的整数」编码，所以判断状态可以直接比大小：`runStateAtLeast(c, SHUTDOWN)` 就是 `c >= SHUTDOWN`。
② 只有 RUNNING 是负数 —— 这让 `isRunning(c)` 简写成 `c < SHUTDOWN`，也让它永远排在最小值。
③ 5 个状态值间隔恰好是 2^29，等于给低 29 位留出了不干扰高位的整数加法空间。

### 为什么不能写成两个字段

这是本题真正的考点。假设拆成 `volatile int runState` 和 `AtomicInteger workerCount`，会立刻出现一个无法消除的窗口：

```java
// 危险写法：两个独立变量
if (runState == RUNNING) {          // ① 检查：此刻是 RUNNING
    // <-- 这个缝隙里，别的线程调用了 shutdown()，runState 变成 SHUTDOWN
    if (workerCount.incrementAndGet() <= maximumPoolSize) {  // ② 加线程
        startNewWorker();           // ③ 在 SHUTDOWN 状态下启动了新线程 → 泄漏
    }
}
```

你没法用一把锁解决它——因为 `execute()` 是热路径，每次提交任务都加 `mainLock` 会把线程池变成串行。把两个值塞进一个 int，**一次 CAS 就能同时校验「状态没变」和「数量没超」**，这是无锁编程里最经典的「打包」手法。

- **面试口径**：「`ctl` 是一个把状态和数量打包进 32 位的整数。这样 `compareAndSet(expect, expect + 1)` 就能在一次原子操作里完成『确认还在 RUNNING』和『worker 数 +1』两件事。如果分成两个字段，无论如何都存在状态检查与数量修改之间的竞态窗口，而加锁会让提交路径串行化。」

### 五种状态的语义差异，只在两个方法上体现

| 状态 | 收新任务 | 跑队列里的任务 | 中断在跑的线程 | 由谁触发 |
|---|---|---|---|---|
| `RUNNING` | 是 | 是 | 否 | 构造 |
| `SHUTDOWN` | **否** | **是**（清空队列） | 否 | `shutdown()` |
| `STOP` | 否 | **否** | **是** | `shutdownNow()` |
| `TIDYING` | 否 | 否 | 否 | 队列空且 worker 为 0 |
| `TERMINATED` | 否 | 否 | 否 | `terminated()` 执行完 |

记住一句话：**`shutdown()` 是「关店门、把手上的人伺候完」，`shutdownNow()` 是「关店门、赶人走、桌上东西全扔」**。`shutdownNow()` 的返回值就是被扔出来的那堆任务——这个返回值不是给你看的，是给你**落盘补偿**用的。

---

## L2 execute()：三次 CAS、一次回滚、一次双重检查

这是全篇最值得逐行读的方法，因为它只有 15 行，却包含了三个面试高频追问。

```java
public void execute(Runnable command) {
    if (command == null) throw new NullPointerException();
    int c = ctl.get();
    if (workerCountOf(c) < corePoolSize) {           // ① 第一道：还没到核心数
        if (addWorker(command, true)) return;
        c = ctl.get();                                // 失败就重新读，别用旧值
    }
    if (isRunning(c) && workQueue.offer(command)) {  // ② 第二道：入队
        int recheck = ctl.get();                      // ③ 入队后必须重读
        if (!isRunning(recheck) && remove(command))
            reject(command);                          // 已关闭：把刚入队的捞回来再拒
        else if (workerCountOf(recheck) == 0)
            addWorker(null, false);                   // 队里有活但没人：补一个空 worker
    }
    else if (!addWorker(command, false))              // ④ 第三道：扩容到 max
        reject(command);
}
```

```text
① wc < corePoolSize ?
   addWorker(command, true) 成功 → return
   └─ 失败 → 重读 ctl（不能沿用旧值）
        │ 否 / 加线程失败
        ▼
② isRunning && queue.offer ?
   入队成功 → 立刻 recheck 一次 ctl
        │
        ▼
③ recheck 双重检查
   !isRunning && remove 成功 → reject
   wc == 0                   → addWorker(null, false)
        │ 入队失败（队满）
        ▼
④ addWorker(command, false) 失败 → reject

失败回滚链路：addWorkerFailed → workers.remove(w) → decrementWorkerCount() → tryTerminate()
```

**右侧每一道闸门都在防一件具体的事：**

1. 核心线程数是「常驻编制」，不是「上限」。到编就停，不会为了快而临时多招——这是线程池与缓存池最大的区别。
2. 队列优先于扩容：先排满队，再考虑加人。所以带无界队列时 `maximumPoolSize` 永远不会生效（L3 详证）。
3. 入队到检查之间池可能被关闭，也可能原有线程全部超时退出。所以必须入队后再读一次、必要时回滚（`remove`）或补救（补 worker）。
4. 走到 ④ 说明：池已关 / 已到 max / 系统创建线程失败。**三种原因共用一个 `RejectedExecutionException`，这是排查痛点。**

### 追问一：addWorker 失败后为什么必须重新读 ctl

源码里那句 `c = ctl.get()` 不是冗余。如果继续用进来时读到的旧 `c`，下面 `isRunning(c)` 就会拿一个可能已经过期的状态做判断——池已经被 `shutdown()` 了，你还以为它在 RUNNING，于是把任务入队，然后被 `recheck` 捞回来拒绝。逻辑上不会错（recheck 兜住了），但会多一次无谓的入队和出队。

### 追问二：addWorker 失败是怎么回滚的

```java
private boolean addWorker(Runnable firstTask, boolean core) {
    retry:
    for (int c = ctl.get();;) {
        // 状态检查：SHUTDOWN 且（队列非空 或 firstTask != null）→ 不许加
        if (runStateAtLeast(c, SHUTDOWN)
            && (runStateAtLeast(c, STOP) || firstTask != null || workQueue.isEmpty()))
            return false;
        for (;;) {
            if (workerCountOf(c) >= ((core ? corePoolSize : maximumPoolSize) & COUNT_MASK))
                return false;                                  // 数量到顶，直接失败
            if (compareAndIncrementWorkerCount(c))             // ★ 第一次 CAS
                break retry;
            c = ctl.get();                                     // CAS 失败 → 重读重试
            if (runStateAtLeast(c, SHUTDOWN)) continue retry;
        }
    }
    boolean workerStarted = false, workerAdded = false;
    Worker w = null;
    try {
        w = new Worker(firstTask);                             // 这里可能 OOM / 无法建线程
        final Thread t = w.thread;
        if (t != null) {
            mainLock.lock();                                   // ★ 集合操作仍需加锁
            try {
                int c = ctl.get();
                if (isRunning(c) || (runStateLessThan(c, STOP) && firstTask == null)) {
                    workers.add(w);
                    workerAdded = true;
                }
            } finally { mainLock.unlock(); }
            if (workerAdded) { t.start(); workerStarted = true; }
        }
    } finally {
        if (!workerStarted)
            addWorkerFailed(w);                                // ★ 回滚：数量减回去 + 移除
    }
    return workerStarted;
}
```

三个知识点藏在这一段里：

- **计数先加、线程后建**：所以必须靠 `finally` 里的 `addWorkerFailed` 兜底，否则计数会永久泄漏（池看起来满了，实际没线程干活——这是死锁的一种形态）。
- **`workers` 是 `HashSet`，不是并发容器**：所以 `add` / `remove` 都要 `mainLock`。所谓「线程池是无锁的」只在计数路径上成立。
- **状态检查在锁外做了一遍、锁内又做了一遍**：锁外是为了快速失败，锁内是为了防止「检查后到加锁前」池被关闭。

> **高频踩坑**：`execute()` 抛出 `RejectedExecutionException` 时，很多人只看「池满了」。实际有**三种完全不同的原因**共用这一个异常：① 池已 SHUTDOWN / STOP；② worker 数已达 max；③ **操作系统拒绝创建线程**（`OutOfMemoryError: unable to create native thread` 被包成 `addWorker` 返回 false）。第三种情况下线程池其实是空的，监控面板一片绿，只有日志里那一行异常。定位方法：catch 住之后打印 `poolSize` / `queue.size()` / `isShutdown()`。

---

## L3 线程数到底设多少：从 8 核算出 56

「CPU 密集型设 N+1，IO 密集型设 2N」——这句话在面试里说出来基本等于没答，因为真实业务既不是纯 CPU 也不是纯 IO。正确做法是拿公式算，而公式只有一条：

`N = N_cpu × U_cpu × (1 + W / C)`　其中 W = 单请求等待时间，C = 单请求计算时间。这条式子和 Little's Law 是同一件事的两种写法，可以互相验证。

代入一个具体业务：**8 核机器，单请求 50 ms，其中 CPU 计算 5 ms、等下游 45 ms，目标 CPU 利用率 70%**。

```text
N = 8 × 0.7 × (1 + 45 / 5) = 8 × 0.7 × 10 = 56 个线程

交叉验证（Little's Law）：
  吞吐     = 56 / 0.05s = 1120 QPS
  CPU 占用 = 1120 × 5ms = 5.6 核 = 70% ✓ 恰好命中目标
  顶到 100%：8000ms / 5ms = 1600 QPS → 需要 1600 × 0.05 = 80 个线程
```

```text
QPS
1500┤                                    ●───●───●   80 线程后水平
    │                          ●
1000┤                    ●
    │             ●
 500┤       ●
    │  ●
   0└──┬────┬────┬────┬────┬────┬────┬────┬──── 线程数
      0   32   56   80  120  160  200
              ▲    ▲
           甜点  物理极限（CPU 100%）

延迟
170┤
    │                              ●
 85┤                      ●
    │  ●───●───●───●───●
  0└──┬────┬────┬────┬────┬────┬────┬────
      0   32   56   80  120  160  200
      └── 50ms 平线 ──┘  └─ 排队开始 ─┘
```

**56 是「按目标 CPU 利用率算出的编制」，80 是「CPU 打满的物理极限」。超过 80 之后再加线程，吞吐一根不多、延迟线性变差——这就是为什么「加大线程池」从不是性能优化。**

### 队列容量：一个从延迟倒推出来的数

```text
队列容量 = 到达率 × 可容忍排队时间
         = 1000 QPS × 0.15s = 150

校验：150 个任务 × 单任务对象约 1KB = 150KB（安全）
     若任务里持有一个 1MB 的 byte[]（比如上传文件）→ 150MB（危险）
```

所以「队列设 10000」这句话背后真正的含义是：**你允许最多 1 万个请求在这里干等**。在 1000 QPS 下，第 1 万个请求要等 10 秒——用户早就走了，你还在替他排队。

### 无界队列让 max 变成死配置

```text
A · 有界队列（ArrayBlockingQueue 100）：max 会生效
  [8 核心线程] 满 → [队列 100] 满 → [扩到 32] 仍满 → [拒绝（可见）]
  线程上限 32 + 队列上限 100 = 总容量 132，超了立刻报错，压力可见、可告警

B · 无界队列（LinkedBlockingQueue 默认）：max 永不生效
  [8 核心线程] → [队列 = Integer.MAX_VALUE（21 亿），永远装得下]
                                        [max=32：永不到达]
  32 这个数字从头到尾没被读过一次。第 9 个请求开始全部排队，第 10 万个也在排队
  —— 直到堆被吃满
```

三个参数是绑在一起的：**有界队列 ↔ 有意义的最大线程数 ↔ 明确的拒绝策略**。缺一个，另外两个都是装饰品。

### 把四个参数一次配准

```java
int core = 56;                                    // 上式计算
int max  = 80;                                    // CPU 打满的物理极限
BlockingQueue<Runnable> q = new ArrayBlockingQueue<>(150);  // 1000 QPS × 0.15s
ThreadFactory tf = r -> {
    Thread t = new Thread(r, "order-pool-" + SEQ.getAndIncrement());
    t.setDaemon(false);                           // 别用守护线程，否则 JVM 退出会砍任务
    t.setUncaughtExceptionHandler((th, e) -> log.error("worker died: {}", th.getName(), e));
    return t;
};
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        core, max, 60L, TimeUnit.SECONDS, q,
        tf, new ThreadPoolExecutor.CallerRunsPolicy());
```

| 参数 | 值 | 依据 | 改了会怎样 |
|---|---|---|---|
| `core` | 56 | `8 × 0.7 × (1 + 45/5)` | 调小 → CPU 用不满；调大 → 空闲线程多、切换变多 |
| `max` | 80 | CPU 100% 时的并发度 | 调大 → 只增加排队与切换，吞吐不涨 |
| 队列 | 150 | 1000 QPS × 150 ms 容忍 | 调大 → 延迟无限膨胀；调小 → 更早拒绝 |
| `keepAlive` | 60 s | 突发流量后回收非核心线程 | 配合 `allowCoreThreadTimeOut` 连核心一起收 |

> **一个反直觉的事实**：如果你的 `core = max` 且队列无界（也就是 `newFixedThreadPool`），那么 `keepAliveTime`、`maximumPoolSize`、拒绝策略**三样全部不生效**。整个线程池退化成「n 个线程 + 一个无限长的队列」。这不是「简单够用」，这是「没有任何过载保护」。

---

## L4 getTask()：为什么超时之后还要多转一圈

线程池里最反直觉的一段代码就在这。注意那个 `boolean timedOut` 是**方法内局部变量**，每次调用都从 `false` 开始。

```java
private Runnable getTask() {
    boolean timedOut = false;                     // 上一轮 poll 是否超时
    for (;;) {
        int c = ctl.get();
        // 关闭检查：SHUTDOWN 后若队列也空了（或已 STOP），worker 就该退场
        if (runStateAtLeast(c, SHUTDOWN)
            && (runStateAtLeast(c, STOP) || workQueue.isEmpty())) {
            decrementWorkerCount();
            return null;
        }
        int wc = workerCountOf(c);
        // ★ 只有「允许核心回收」或「已经超过核心数」的线程才受超时约束
        boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

        if ((wc > maximumPoolSize || (timed && timedOut))
            && (wc > 1 || workQueue.isEmpty())) {
            if (compareAndDecrementWorkerCount(c)) return null;   // CAS 减成功才真的退出
            continue;                                            // 减失败（别人也在退）→ 重来
        }
        try {
            Runnable r = timed
                ? workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS)  // 有超时
                : workQueue.take();                                    // 死等
            if (r != null) return r;
            timedOut = true;                       // ★ 只是打标记，不退出
        } catch (InterruptedException retry) {
            timedOut = false;                      // 被中断 → 清标记，继续等
        }
    }
}
```

```text
Worker 的一生（runWorker 的外层循环）
 ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────────┐
 │ 出生      │───▶│ getTask()│───▶│ run()    │───▶│ 拿不到 → 退出 │
 │ addWorker│    │take/poll │    │ 执行业务  │    │processWorker │
 └──────────┘    └──────────┘    └──────────┘    └──────────────┘
                       ▲               │
                       └───────────────┘
                  跑完一个再回到 getTask，直到返回 null 才退出

getTask 内部「两轮才退出」：
 第 1 轮  timedOut = false
   timed = true（已超核心数）→ poll(keepAlive) 阻塞 60 秒 → 返回 null
   → 只执行 timedOut = true，然后 continue
   （关键：此时绝不能再 poll 一次，否则又白等 60 秒）

 第 2 轮  timedOut = true
   重新读 ctl（可能这一瞬间又被 offer 了任务）
   (timed && timedOut) 成立 → CAS 递减 workerCount
   CAS 成功 → return null → worker 退出
   CAS 失败（别人也在退）→ continue，从头再来
```

**两轮的意义**：poll 超时的那一刻世界可能刚变（新任务入队 / 池被关闭），必须用最新 ctl 再判一次，否则会丢掉刚入队的任务。这就是 `getTask()` 里 `for(;;)` 存在的唯一理由。

### 三个参数陷阱，全部有源码依据

**陷阱 1：`allowCoreThreadTimeOut(true)` 配 `keepAliveTime = 0` 直接抛异常**

```java
public void allowCoreThreadTimeOut(boolean value) {
    if (value && keepAliveTime <= 0)
        throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
    ...
}
```

而 `Executors.newFixedThreadPool` 的默认 `keepAliveTime` 恰好是 **0**。所以「我想让核心线程也能回收」这个需求，在 `newFixedThreadPool` 上必须先把 keepAlive 改成非 0 才能开。

**陷阱 2：`keepAliveTime` 内部一律折算成纳秒，单位写错 1000 倍**

```java
long keepAliveTime = unit.toNanos(time);   // 存进字段时统一成纳秒
// 于是 1 秒 = 1e9 纳秒；把 SECONDS 误写成 MILLISECONDS，就是 1 秒变 1 毫秒
```

这个字段常从配置中心读。`keepAliveTime=60` 配 `SECONDS` 是 60 秒，配 `MILLISECONDS` 是 60 毫秒——后者会让每个空闲 worker 每 60ms 醒一次、判断、退出，线程反复创建销毁，CPU 的 `sys` 时间莫名升高。真实事故，不是理论。

**陷阱 3：`setCorePoolSize` 调大时不会无条件补线程**

```java
int delta = corePoolSize - this.corePoolSize;
this.corePoolSize = corePoolSize;
if (workerCountOf(ctl.get()) > corePoolSize) interruptIdleWorkers();
else if (delta > 0) {
    int k = Math.min(delta, workQueue.size());     // ★ 上限是「队列里积压的任务数」
    while (k-- > 0 && addWorker(null, true)) {
        if (workQueue.isEmpty()) break;
    }
}
```

也就是说：队列空的时候把 core 从 10 调到 100，线程数**一个都不会变**，要等任务进来才逐个创建。运维平台「我扩容了但没生效」十有八九是这个。

### 动态调参的正确姿势

```java
public class ResizablePool extends ThreadPoolExecutor {
    public ResizablePool(int core, int max, long keepAliveSec, BlockingQueue<Runnable> q) {
        super(core, max, keepAliveSec, TimeUnit.SECONDS, q, NAMED_FACTORY,
              new CallerRunsPolicy());
    }
    // 顺序很重要：扩容先改 max，缩容先改 core
    public void resize(int core, int max) {
        if (max >= core) {
            setMaximumPoolSize(max);      // 扩 max 先
            setCorePoolSize(core);        // 再扩 core
        } else {
            setCorePoolSize(core);        // 缩 core 先
            setMaximumPoolSize(max);      // 再缩 max
        }
    }
}
```

顺序为什么不能反：`setCorePoolSize` 里有一句 `if (maximumPoolSize < corePoolSize) throw new IllegalArgumentException()`。先扩 core 到 100 而 max 还是 80，直接抛异常。缩的时候反过来同理。

---

## L5 CompletableFuture：依赖栈、回调逆序、四种线程归属

99% 的人能说出 `thenApply` 和 `thenApplyAsync` 的区别，但说不清「**thenApply 到底在哪个线程上跑**」。这个问题的答案不是一句「当前线程」，而是一个分四种情况的表。而这张表来自它的数据结构：一个字段 `result` 加一个**栈**。

```java
volatile Object result;      // 结果；null = 未完成，AltResult 包装异常或 null 值
volatile Completion stack;   // 依赖动作的 Treiber 栈（无锁头插）

abstract static class Completion extends ForkJoinTask<Void>
        implements Runnable, AsynchronousCompletionTask {
    volatile Completion next;   // 栈的 next 指针
}
```

```text
CompletableFuture 对象                栈：后注册的在前
 ┌──────────────────────┐
 │ result = null        │       ┌────────────┐   ┌────────────┐   ┌────────────┐
 │ stack ───────────────┼──────▶│ c3 thenApply│──▶│ c2 thenApply│──▶│ c1 thenApply│
 │                      │       │  （栈顶）    │   │             │   │ next = null │
 └──────────────────────┘       └────────────┘   └────────────┘   └────────────┘

注册顺序：① → ② → ③（头插，栈顶是最新的）
触发顺序：③ → ② → ①（postComplete 从头弹，LIFO）
```

这一步「逆序」是可观测的：同一个 future 上挂三个带副作用的回调，执行顺序与书写顺序相反。面试时提到这点，基本可以结束这道题了。

`postComplete()` 的实现：用 CAS 从栈顶摘一个节点，然后沿着它的 `next` 继续（`NESTED` 模式），所以一条链上的回调会「深度优先」全部跑完，不会来回弹栈。

### 四种线程归属，一张表说清

| 写法 | 上游是否已完成 | 回调在哪个线程跑 | 常见后果 |
|---|---|---|---|
| `thenApply(fn)` | **已完成** | **调用 `thenApply` 的那个线程**（同步执行） | 链路变长 → 栈更深；在 EventLoop 里调用会阻塞 IO |
| `thenApply(fn)` | 未完成 | **完成上游的那个线程**（可能是业务线程、IO 线程、commonPool worker） | 你以为是 A 线程，实际在 B 线程，ThreadLocal 全丢 |
| `thenApplyAsync(fn)` | 无关 | `ForkJoinPool.commonPool()`；若并行度 ≤ 1 则**每次新建线程** | commonPool 被阻塞任务占满 → 全 JVM 的 parallelStream 一起饿死 |
| `thenApplyAsync(fn, pool)` | 无关 | 你给的池 | **唯一可控的写法**，生产代码应该只用这一种 |

```text
① 上游已完成 + thenApply      → 当前线程（同步）       风险：层层嵌套让调用栈线性变深
② 上游未完成 + thenApply      → 完成上游的那个线程     风险：ThreadLocal / MDC 静默丢失
③ thenApplyAsync(fn)          → commonPool（核数-1）   风险：全 JVM 共用一个池
④ thenApplyAsync(fn, myPool)  → 你指定的线程池         ✅ 可限流、可隔离、可监控
```

判据来自源码：`uniApplyStage` 里若 `d.result == null` 就入栈，否则立刻 `e.execute(...)`，而 `SYNC` 模式下 `e` 就是调用者线程。

### allOf 到底会不会抛异常

网上流传「`allOf` 不会传播异常，必须逐个 `join`」——这句话**只说对了一半，而且错的那一半更危险**。

```java
// 源码：allOf 递归建一棵二叉树，每个节点是个 BiRelay
static CompletableFuture<Void> andTree(CompletableFuture<?>[] cfs, int lo, int hi) {
    ...
    if ((r = a.result) == null || (s = b.result) == null)
        a.bipush(b, new BiRelay<>(d, a, b));       // 两个都完成才触发
    else if (r 是异常 || s 是异常)
        d.result = encodeThrowable(x, z);           // 只有一个异常被保留
    else d.result = NIL;
}
```

- **会抛**：`allOf(...).join()` 在上游有异常时确实抛 `CompletionException`。
- **但不是快速失败**：`BiRelay` 要等左右两棵子树**都完成**才 fire。所以第 1 个任务 100ms 就挂了，你仍然要等全部 10 个任务跑完才能拿到异常。
- **而且只留一个异常**：多个失败时你只能看到其中一个，其余被丢弃。

> 所以正确写法是：如果业务要求「任一失败立刻返回」，`allOf` 不满足，得用 `anyOf` + 自建失败信号，或者给每个 future 挂 `whenComplete` 主动 set 一个共享的失败 future。这个追问能答出来，说明你是真读过源码而不是抄博客。

---

## L6 ForkJoinPool：为什么它敢用「双端队列」

普通线程池是「一个队列，所有线程抢」，ForkJoinPool 是「每个线程一个队列，各干各的，闲了去偷」。这一句话的差别，全在队列的**两端**上。

```text
Worker A 的队列
 base（最老的，只有 steal 会碰）                        top（最新的）
 ┌──────┬──────┬──────┬──────┬──────┐                ┌──────┐
 │  t4  │  t3  │  t2  │  t1  │      │◀── steal 从这里 │ 新任务│
 └──────┴──────┴──────┴──────┴──────┘                └──────┘
   ▲                                                      ▲
   │ B 空了就来偷这里（FIFO，取最老的）                     │ A 自己 push/pop（LIFO）

Worker B 的队列
 ┌──────┬──────┐
 │（空） │      │  ← B 空 → 跨过去偷 A 的 base
 └──────┴──────┘
```

**为什么主人 LIFO、小偷 FIFO**：主人取最新的任务，数据大概率还在 CPU 缓存里；小偷取最老的，与主人形成「一前一后」的互补，减少争抢。

代价：`base`/`top` 是两个独立的 int，steal 时用 CAS 只动 `base`；owner 只动 `top` —— 这就是它比「一个共享队列 + 全局锁」快一个数量级的原因。

### 三个必须知道的数字与陷阱

| 项 | 值 / 行为 | 意味着什么 |
|---|---|---|
| commonPool 并行度 | `Math.max(1, CPU 核数 - 1)` | 8 核 = 7 个 worker。7 个阻塞任务就能让全 JVM 的 `parallelStream` 卡死 |
| 并行度 ≤ 1 时 | 每个任务 `new Thread()` | 单核机器上 `thenApplyAsync` 变成「每任务一线程」，比同步还慢 |
| 分治阈值 | `Arrays.parallelSort` 是 8192 | 小于 8192 个元素直接单线程排——切分开销大于收益 |

```java
// 反例：在 commonPool 里跑阻塞 IO，一个池拖死整个 JVM
list.parallelStream().map(id -> httpGet(id)).collect(toList());   // 每个 httpGet 50ms
// 7 个 worker 全被占住 → 同 JVM 里所有 parallelStream、所有 thenApplyAsync 一起排队

// 正解 1：换专用池
ForkJoinPool pool = new ForkJoinPool(32);
pool.submit(() -> list.parallelStream().map(...).collect(toList())).get();

// 正解 2：阻塞任务声明为 ManagedBlocker，让池子临时扩员
ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
    public boolean block() { result = httpGet(id); return true; }
    public boolean isReleasable() { return result != null; }
});
```

- **面试口径**：「ForkJoinPool 适合**可切分、CPU 密集、任务粒度不小于 8192** 的场景。它不适合阻塞 IO——因为 worker 被阻塞时不会自动补人（除非用 `ManagedBlocker`），并行度会被永久吃掉。而 `CompletableFuture` 的 `Async` 系列默认就用这个池，这就是为什么生产代码必须显式传执行器。」

---

## L7 并行调三个下游：从 580 ms 到 200 ms，以及超时的真相

真实场景：下单页要同时拿**库存（60 ms）、价格（220 ms）、优惠券（300 ms 偶尔更慢）**。串行等就是 580 ms，而用户的可忍受上限通常按 200 ms 算。

```text
时间轴（ms）    0    50   100  150  200  250  300
库存 60ms       ████████
价格 220ms      ████████████████████████████████████
优惠券 300ms    ██████████████████████│░░░░░░░░░░░░░░░░
                                      ▲ orTimeout(200ms)
                                   线程仍在跑，没人中断它

串行         ████████████████████████████████████████████  580 ms
并行 allOf   ████████████████████████████                    300 ms
并行+超时    ████████████████████                            200 ms
```

并行能省掉的是「等待的叠加」，省不掉「最慢的那个」。所以真正决定 P99 的永远是慢依赖 —— 这就是必须有超时的原因。

**关键真相**：`orTimeout` 只是让 future 提前以 `TimeoutException` 完成，底层任务不会被中断，仍在占着线程和下游连接。所以「超时 + 重试」会让线程占用翻倍。

### 可落地的写法（每行都有理由）

```java
// 三个下游各自一个池：慢的不会拖累快的（隔离）
ExecutorService inv  = pool("inv",  32);
ExecutorService price = pool("price", 32);
ExecutorService promo = pool("promo", 16);

public OrderView build(long skuId, long userId) {
    // ① 必须显式传执行器。不传就走 commonPool，全 JVM 共享 7 个线程
    CompletableFuture<Stock> f1 = CompletableFuture
            .supplyAsync(() -> invClient.get(skuId), inv)
            .orTimeout(150, TimeUnit.MILLISECONDS)
            .exceptionally(e -> Stock.UNKNOWN);          // ② 降级成「库存未知」，不阻断下单

    CompletableFuture<Price> f2 = CompletableFuture
            .supplyAsync(() -> priceClient.get(skuId), price)
            .orTimeout(180, TimeUnit.MILLISECONDS)
            .exceptionally(e -> Price.CACHED);           // ③ 降级到本地缓存价

    CompletableFuture<Coupon> f3 = CompletableFuture
            .supplyAsync(() -> promoClient.best(userId, skuId), promo)
            .orTimeout(120, TimeUnit.MILLISECONDS)
            .exceptionally(e -> Coupon.NONE);            // ④ 优惠券拿不到就当没有

    // ⑤ join 不会抛受检异常；三个都已兜底，这里不会抛
    CompletableFuture.allOf(f1, f2, f3).join();
    return new OrderView(f1.join(), f2.join(), f3.join());
}
```

| 决策 | 为什么 | 不这么做会怎样 |
|---|---|---|
| 每个下游一个池 | 慢下游只吃自己的线程 | 优惠券慢的时候，库存查询也被堵在同一条队列里 |
| 显式传执行器 | commonPool 并行度 7，且全局共享 | 一段阻塞代码影响同 JVM 所有 `Async` 调用与 `parallelStream` |
| `orTimeout` | 给每个依赖设独立上限 | 总耗时 = 最慢依赖的无限长尾 |
| `exceptionally` 降级 | 单点失败不该阻断主流程 | `allOf` 等全部完成后抛异常，整页 500 |
| `allOf().join()` | 等待全部（已兜底） | 少写一个 join，可能拿到未完成的值 |

### 超时之后，那个线程去哪了

```java
public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
    if (unit == null) throw new NullPointerException();
    if (result == null)
        whenComplete(new Canceller(Delayer.delay(new Timeout(this), timeout, unit)));
    return this;                      // ★ 返回 this，不是新对象
}
static final class Timeout implements Runnable {
    public void run() {
        if (f != null && !f.isDone())
            f.completeExceptionally(new TimeoutException());   // ★ 只改状态，不 interrupt
    }
}
```

> **由此推出的三个生产事故**
>
> 1. **线程被「僵尸请求」占满**：接口 200ms 就返回了，但下游调用还在跑 5s。QPS 1000 时，5 秒内积压 5000 个还在跑的任务——池子看起来「活跃线程数 100%」，但你早就超时返回了。这就是「超时了但线程池还是满」的根因。
> 2. **写操作超时 ≠ 没执行**：下单接口 200ms 超时返回失败，下游其实 800ms 后成功了。用户重试 → 重复下单。**超时只对读接口安全，写接口必须配幂等键**（第 18 期讲过）。
> 3. **重试放大**：超时 + 3 次重试 = 一个用户请求占用 4 个下游连接。下游只是慢了 10%，被你重试打成雪崩。

要真正「取消」，只能靠下游客户端自己的读超时（HTTP client 的 `readTimeout`、gRPC 的 `deadline`），让连接层主动断开。**`orTimeout` 是给你自己看的，不是给下游看的。**

### 上下文传递：TaskDecorator 是唯一干净的解法

```java
// 问题：MDC / TraceId 存在 ThreadLocal 里，切线程就丢
log.info("before");                                  // traceId=abc
CompletableFuture.runAsync(() -> log.info("in"));     // traceId=null ← 丢了

// 解法：装饰每一个提交的任务，把上下文搬过去再搬回来
public class MdcDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable r) {
        Map<String, String> ctx = MDC.getCopyOfContextMap();   // 提交线程的上下文
        return () -> {
            Map<String, String> old = MDC.getCopyOfContextMap();
            try {
                if (ctx != null) MDC.setContextMap(ctx);
                r.run();
            } finally {
                if (old != null) MDC.setContextMap(old);       // ★ 必须还原
                else MDC.clear();                              // ★ 池化线程会复用，不还原就是脏数据
            }
        };
    }
}
executor.setTaskDecorator(new MdcDecorator());   // Spring 一行接上
```

> **最容易漏的那一行**是 `finally` 里的还原。线程池的线程是复用的，如果任务结束时不清 MDC，下一个任务会带着上一个请求的 traceId 打日志——排查线上问题时看到「两个不同用户的日志串在一个 traceId 下」，九成是这个原因。

---

## L8 拒绝策略：四种行为，一个反压回路

拒绝策略只有在「队列有界」时才会被触发。一旦触发，四种策略的差别不是「优雅 vs 粗暴」，而是**「谁来承担过载」**。

```text
[提交线程] → [核心线程 已满] → [队列 已满] → [扩到 max 已满] → reject()
                                                                  │
        ┌─────────────────┬───────────────────┬───────────────────┤
        ▼                 ▼                   ▼                   ▼
   AbortPolicy      CallerRunsPolicy     DiscardPolicy    DiscardOldestPolicy
   抛异常            提交线程里同步跑      静默丢弃          poll 掉队头再 execute
   过载可见          反压：提交变慢        最难查（无日志）   丢的是等最久的

CallerRunsPolicy 的反压回路：
   过载 → 提交线程自己跑任务 → 提交变慢 → 上游流量自然下降
```

| 策略 | 行为 | 后果 |
|---|---|---|
| `AbortPolicy`（默认） | 抛 `RejectedExecutionException` | 过载被显式暴露，但调用方必须 catch |
| `CallerRunsPolicy` | 在提交线程里同步执行 `r.run()` | 反压：提交变慢 → 上游流量自然下降 |
| `DiscardPolicy` | 空实现，静默丢弃 | 最难查：数据静默丢失，监控一片绿 |
| `DiscardOldestPolicy` | `poll` 掉队头，再 `execute` 自己 | 丢的是「等最久的」，通常是最该被服务的 |

### CallerRunsPolicy 的双面性

- ✅ 批处理 / 后台任务：天然的背压，让生产者自己慢下来，比抛异常温和得多。
- ❌ Netty EventLoop / Tomcat 请求线程：一个 50ms 的任务就卡住 800 个连接，把线程池问题升级成全站阻塞。
- ❌ 定时任务：它在调度线程里跑，会拖慢后续所有 trigger。

判断口诀：**提交线程是「可以慢的业务线程」就用 CallerRuns；是「不能慢的 IO 线程」就绝对不能用。**

### shutdown 与 shutdownNow，差了两个字，差了一个世界

| | `shutdown()` | `shutdownNow()` |
|---|---|---|
| 状态 | RUNNING → SHUTDOWN | RUNNING → STOP |
| 队列里的任务 | **全部执行完** | **返回给你**（不执行） |
| 正在跑的任务 | 不打断 | `interrupt()` |
| 返回值 | void | `List<Runnable>`（没跑的任务） |
| 适用 | 优雅下线 | 测试清理 / 紧急止血 |

> **`interrupt()` 只对「可中断的阻塞点」有效**。如果任务正在跑一个死循环、或者在做不可中断的 IO（老式 socket 读写、某些 native 调用），`interrupt()` 只是把标志位设成 true，任务照样跑到底。**所以「`shutdownNow` 会立刻停」是错的**，它只是「请求你停」。

### 优雅关闭：顺序错了就是丢任务

```java
// ❌ 常见错误写法：顺序全错
pool.shutdownNow();                              // 立刻中断 → 正在处理的订单被砍半
pool.awaitTermination(30, TimeUnit.SECONDS);     // 再等（此时队列已经被清空了）

// ✅ 正确顺序
pool.shutdown();                                 // 1. 不再接新任务，队列继续消费
try {
    if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {   // 2. 等最多 30 秒
        List<Runnable> pending = pool.shutdownNow();      // 3. 还没完 → 强制中断
        log.warn("force shutdown, {} tasks dropped", pending.size());  // 4. ★ 落盘补偿
        if (!pool.awaitTermination(5, TimeUnit.SECONDS))
            log.error("pool did not terminate");          // 5. 真没救了，告警
    }
} catch (InterruptedException e) {
    pool.shutdownNow();
    Thread.currentThread().interrupt();          // ★ 恢复中断标志，别吞
}
```

> **Spring 里对应的两个开关，缺一个就等于没配**：`setWaitForTasksToCompleteOnShutdown(true)` 决定「等不等队列」，`setAwaitTerminationSeconds(30)` 决定「等多久」。只配第一个不配第二个，等价于无限等待——K8s 的 `terminationGracePeriodSeconds`（默认 30s）到点直接 `SIGKILL`，你等的那 30 秒白等，任务照样被砍。

### 下线窗口里的第二波雪崩

被 `shutdown()` 之后、进程还没退出之前，如果注册中心还没摘掉这个实例，流量仍在进来。此时 `execute()` 会走 `reject()`，而 `CallerRunsPolicy` 里有一句 `if (!e.isShutdown())`——**已关闭时它直接丢弃，不跑也不抛**。于是调用方看到的是「请求成功了但什么也没做」。

正确顺序是**先摘流、再 sleep 一个心跳周期、最后才 shutdown**。第 08 期讲过的优雅下线四步，在这里是唯一能救命的顺序。

---

## L9 监控：五个指标，一个骗人的方法和一个吞异常的地方

线程池出问题从来不是「突然挂」，而是「队列一点点变长，直到某天越过了线」。所以监控的关键不是看 CPU，而是看**队列**。

| 指标 | 说明 |
|---|---|
| `getQueue().size()` / 容量 | 最灵敏。超过 50% 就该告警，不用等满 |
| `getActiveCount()` | **近似值**：遍历 workers 数 `isLocked()`，O(n) |
| `getPoolSize()` | 当前线程数。长期等于 core 说明容量刚好，长期等于 max 说明已过载 |
| `getCompletedTaskCount()` 的增量 | 累计完成数，取 delta 得到吞吐。增量为 0 而队列在涨 = 全卡住 |
| reject 次数（自定义 handler 埋点） | 必须自己加。内置策略不打点，等于没有这个指标 |

```text
队列长度
150┤                                        ██████████  ← 雪崩：贴顶后全转拒绝
   │                                   ▁▂▄▆
   │                              ▁▂▄▆        ← 承压：单调抬升（不再回落）
 75┤            ▁▂▁▂▁▂▁▂▁▂▁▂▁▂▂▁▂
   │  ▁▂▁▂▁▂▁▂▁                        ← 健康：锯齿状波动
  0└────────────────────────────────────────────────── 时间
     └─ 健康 ─┘└──── 承压 ────┘└────── 雪崩 ──────┘
```

判据：**连续 3 个采样周期队列「只涨不回落」，就可以定性为消费能力不足。** 此时加线程通常无效——先查下游 RT，90% 是下游慢了而不是池小了。

### `getActiveCount()` 为什么骗人

```java
// 源码：遍历所有 worker，数「持有锁」的个数
public int getActiveCount() {
    final ReentrantLock mainLock = this.mainLock;
    mainLock.lock();
    try {
        int n = 0;
        for (Worker w : workers)
            if (w.isLocked()) ++n;
        return n;
    } finally { mainLock.unlock(); }
}
```

三个问题：

1. 它是**瞬时的**，采样间隔内的峰值完全看不到。
2. 它**要拿 `mainLock`**，高并发下这个调用本身会拖慢线程池。
3. `isLocked()` 判的是「worker 是否在执行任务」，正在 `take()` 阻塞等待的线程算「不活跃」——所以**活跃线程数 0 不代表池空闲，只代表没有任务正在执行**。

### `afterExecute` 拿不到异常，除非你主动去取

```java
// ❌ 以为能在这里抓到任务异常 —— 抓不到
protected void afterExecute(Runnable r, Throwable t) {
    if (t != null) log.error("task failed", t);   // submit 提交时 t 永远是 null
}

// ✅ 因为 submit 把任务包进了 FutureTask，异常被存在 outcome 字段里，只有 get() 才抛
protected void afterExecute(Runnable r, Throwable t) {
    super.afterExecute(r, t);
    if (t == null && r instanceof Future<?> f && f.isDone()) {
        try { f.get(); }                        // 借 get() 把异常「逼」出来
        catch (CancellationException ignored) { }
        catch (ExecutionException e) { log.error("task failed", e.getCause()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

> **这就是「`submit` 会吞异常」的完整机制**：`execute` 提交的裸 `Runnable` 抛异常会让 worker 线程死掉（由 `processWorkerExit` 补一个新线程），`UncaughtExceptionHandler` 能收到；而 `submit` 返回的 `FutureTask` 把异常吞进 `outcome`，线程安然无恙、异常无声无息。**「线程池里任务失败了但日志里什么都没有」——99% 是用了 submit 却没 get。**

---

## L10 虚拟线程来了，线程池该删吗

先给结论：**「每任务一线程」的线程池该删；「限流与隔离」的那部分职责，要换成 `Semaphore` 保留下来。**

```text
1 万个虚拟线程，跑在 8 个载体线程上；栈在堆里，不在 OS 里

虚拟线程（堆对象，按需增长，初始几百字节）
 ▢▢▢▢▢▢▢▢▢▢▢▢▢▢  …… × 10000

        │ 由 ForkJoinPool 调度，8 个 carrier 轮流执行
        ▼
 ┌──────────┬──────────┬──────────┬──────────┐
 │carrier-1 │carrier-2 │carrier-3 │carrier-8 │
 │同时只跑 1 │遇到阻塞   │   …      │parallelism│
 │个虚拟线程 │自动让出   │          │= 核数    │
 └──────────┴──────────┴──────────┴──────────┘

默认参数（JDK 21 VirtualThread.createDefaultScheduler）：
  parallelism = availableProcessors（注意：不是 commonPool 的核数 - 1）
  maxPoolSize = max(parallelism, 256)，FIFO 模式（asyncMode = true）
```

### 为什么不能池化虚拟线程

| 维度 | 平台线程 | 虚拟线程 | 结论 |
|---|---|---|---|
| 创建成本 | 数十微秒（`clone` + 栈 + 内核结构） | **百纳秒级**（堆上分配一个对象） | 池化的收益消失了 |
| 内存 | 1 MB **预留**虚拟地址 | 几百字节起，按需增长 | 差 2~3 个数量级 |
| 上限 | 几千（受内核 / 内存限制） | 百万级（受堆限制） | 可以「一请求一线程」 |
| 复用 | 有意义（省创建成本） | **无意义**（省不出一顿饭钱） | 别池化 |

所以「把 `newFixedThreadPool(200)` 换成 `Executors.newVirtualThreadPerTaskExecutor()`」在**正确性上**是可行的，但会立刻引出两个必须一起改的地方：

```java
// ❌ 直接换掉：并发度失去控制，下游被打穿
ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
for (Order o : orders) pool.submit(() -> callDownstream(o));
// 10 万个任务 = 10 万个并发请求砸向下游（平台线程池时最多 200 个）

// ✅ 正解：用 Semaphore 显式限流，把「池大小」的职责接过来
private static final Semaphore PERMITS = new Semaphore(200);
ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

for (Order o : orders) {
    pool.submit(() -> {
        PERMITS.acquire();
        try { callDownstream(o); }
        finally { PERMITS.release(); }
    });
}
```

> **虚拟线程仍然「需要」线程池的三个理由**
>
> 1. **限流**：虚拟线程不限并发，`Semaphore` / 连接池才是新的闸门。数据库连接只有 20 个，你开 10 万虚拟线程也还是 20 个能干活。
> 2. **隔离**：不同下游用不同的 `Semaphore` 配额，否则慢下游会把共享的连接池吃光。
> 3. **资源池本身**：连接池、`HttpClient` 的连接、文件句柄——这些昂贵资源依然要池化，虚拟线程只是让「等待」变便宜。

> **pinning 还没完全消失**：JDK 21~23 里，虚拟线程在 `synchronized` 块内阻塞会「钉住」载体线程（pin），让 8 个 carrier 之一停摆。JDK 24 的 **JEP 491** 已经解决了 `synchronized` 的 pinning，但仍有三类残留：native / FFM 调用、类加载与初始化过程、以及部分 Linux 文件 IO。**所以「虚拟线程 + `synchronized` 老代码」在 JDK 21 上依然会出问题**，迁到 24+ 才安全。

### ThreadLocal：虚拟线程下的隐性炸弹

```java
// 平台线程：200 个线程 × 1 个 ThreadLocal 对象 = 200 份
// 虚拟线程：100 万个虚拟线程 × 1 份 = 100 万份 ← 每份哪怕只有 1KB 就是 1 GB
static final ThreadLocal<byte[]> BUF = ThreadLocal.withInitial(() -> new byte[1024]);

// JDK 21+ 的替代：ScopedValue（不可变、有明确作用域、可被 JIT 优化）
static final ScopedValue<User> CURRENT = ScopedValue.newInstance();
ScopedValue.where(CURRENT, user).run(() -> handle());   // 出了这个 lambda 就没了
```

---

## L11 六个真实炸点，按炸得响的程度排

### 坑 1：无界队列 —— `max` 是个摆设

`newFixedThreadPool` / `newSingleThreadExecutor` 都用 `LinkedBlockingQueue`（默认 `Integer.MAX_VALUE`）。队列装得下 21 亿个任务，**你的堆装不下**。1000 QPS 下 10 分钟积压 60 万任务。表现出来是 `OutOfMemoryError: Java heap space`，但 heap dump 里全是 `LinkedBlockingQueue$Node`——看到这个类名就该知道根因不在内存，在队列。

### 坑 2：`newCachedThreadPool` 的线程爆炸

它的参数是 `core=0, max=Integer.MAX_VALUE, queue=SynchronousQueue, keepAlive=60s`。SynchronousQueue 容量为 0（不存任务，直接把任务交给线程），所以**每来一个任务、没有空闲线程，就新建一个**。

账：单请求 100 ms，下游故障时变成 10 s，QPS 500 → 需要 500 × 10 = 5000 个线程同时存在。每个 1 MB 栈 → 5 GB。`unable to create native thread`，进程直接崩，连 OOM 日志都没有。

### 坑 3：`submit` 吞异常

L9 已讲透。**一句话记住：`execute` 会死线程、`submit` 会死得无声无息。**

### 坑 4：父子任务共用同一个池 → 死锁

```text
父任务把子任务提交到「自己所在的池」，池满 → 环形成

 ┌──────────────────────┐      ┌──────────────────┐      ┌─────────────────┐
 │ 池内 8 个线程         │ ───▶ │ 队列 100/100 已满 │ ───▶ │ CallerRuns →    │
 │ 全部 WAITING          │      │ 子任务 offer 失败 │      │ 父线程跑子任务   │
 │ 都在 future.get() 等  │      │ 扩到 max 也是满   │      │ 或 Abort 断链   │
 └──────────────────────┘      └──────────────────┘      └─────────────────┘
         ▲                                                       │
         └───────────────────────────────────────────────────────┘
              环：父等子，子等线程，线程全被父占着
```

三种解法，按推荐度：

1. **父子分池**：父任务在 A 池，子任务提交到 B 池（A 等 B 不会自锁）。
2. **改成串行**：子任务本来能合并成一个批量调用（最优，但需要改业务）。
3. **ForkJoinPool / 虚拟线程**：能「帮助执行」或无限扩员，把环打破（治标）。

> 排查方法：`jstack` 里看到全部线程处于 `WAITING (parking)` 且栈顶是 `CompletableFuture.waitingGet` / `FutureTask.get`，基本就是它。

### 坑 5：ThreadLocal 不复位

池化线程的 `ThreadLocal` 是跨任务存活的。上一个任务 `set` 了 `UserContext` 没清，下一个任务读到别人的身份——这是**数据越权的经典成因**，而且测试环境用小流量很难复现。防御只有一条：所有 `set` 必须配 `finally { remove(); }`。

### 坑 6：commonPool 被阻塞任务占满

`parallelStream()` 和 `thenApplyAsync` 都走 `ForkJoinPool.commonPool()`，并行度 = 核数 - 1。一段 `parallelStream().map(this::httpGet)` 就能把整个 JVM 的并行能力吃干净，而且**症状是「别处的异步任务莫名变慢」，完全指不到这里**——没有线程池监控，因为 commonPool 不受你管。

> **六坑的一句话总结**：队列要有界、拒绝要可见、`submit` 要 `get`、`ThreadLocal` 要 `remove`、父子别同池、`Async` 必传池。六条都是「加一行代码」的事，但每一条都能独立引发一次 P1。

---

## L12 二十个数字，面试前扫一眼

| 数字 | 含义 |
|---|---|
| **3 + 29** | `ctl` 的位分配：高 3 位状态，低 29 位 workerCount |
| **536,870,911** | `COUNT_MASK` = 2^29 − 1，线程数上限（JDK 18 前叫 `CAPACITY`） |
| **−536870912 / 0 / 536870912 / 1073741824 / 1610612736** | RUNNING / SHUTDOWN / STOP / TIDYING / TERMINATED 的十进制值，可直接比大小 |
| **50~100 μs** | 创建一个平台线程的耗时（`clone()` + 栈 + 内核结构） |
| **1 MB / 8~16 KB** | 平台线程栈的预留量 / 实际提交量（`-Xss`） |
| **3~8 μs** | 一次上下文切换的成本 |
| **56** | 8 核、70% 利用率、等待比 45:5 时的线程数：`8 × 0.7 × 10` |
| **1120 QPS** | 56 线程 / 50 ms 的吞吐（Little's Law 交叉验证） |
| **80 / 1600 QPS** | CPU 打满时的线程数与吞吐，超过它加线程无效 |
| **150** | 1000 QPS × 150 ms 容忍延迟 → 队列容量 |
| **7** | 8 核机器上 commonPool 的并行度（核数 − 1） |
| **8 / 256 / FIFO** | 虚拟线程调度器：parallelism = 核数，maxPoolSize = max(p, 256)，asyncMode = true |
| **8192** | `Arrays.parallelSort` 的最小切分粒度，小于它直接单线程 |
| **1e9** | 1 秒折算成的纳秒数；`keepAliveTime` 内部统一存纳秒 |
| **Integer.MAX_VALUE** | `LinkedBlockingQueue` 默认容量 / `newCachedThreadPool` 的 max，两个「无限」凑成了最危险的组合 |
| **2 轮** | `getTask()` 退出前要经过的循环次数（poll 超时只打标记） |
| **min(delta, queue.size())** | `setCorePoolSize` 调大时能立刻补的线程数上限 |
| **4 种** | `thenApply` 的线程归属只有四种，只有传执行器那种是确定的 |
| **LIFO** | Completion 栈的弹出顺序 → 同一 future 上的回调按注册逆序触发 |
| **30 s** | 优雅关闭的等待上限，也是 K8s `terminationGracePeriodSeconds` 的默认值 |

---

## L13 十四问答：面试官真正想听的那一句

**Q1：线程池的七个参数分别解决什么问题？**
`corePoolSize` 是常驻编制，`maximumPoolSize` 是应急扩编上限，`keepAliveTime` 是临时工的合同期，`workQueue` 是等待区，`threadFactory` 决定线程名字与异常处理器（排查靠它），`handler` 是过载时谁承担成本，`unit` 只是个单位。**七个参数里真正互相耦合的只有四个：core / max / queue / handler**，其余三个是运维与排查用的。

**Q2：为什么状态和线程数要挤在一个 int 里？**
为了让「状态校验」和「计数增减」能在一次 CAS 里原子完成。拆成两个字段后，`execute()` 里必然存在「检查是 RUNNING → 池被关闭 → 加线程」的窗口，而加 `mainLock` 会让提交路径串行化。代价是线程数上限被压到 2^29（5.36 亿），这个代价可以忽略。

**Q3：execute() 里为什么入队之后还要 recheck 一次？**
因为「入队」和「检查」之间世界会变：池可能被 `shutdown()`，原有线程可能全部超时退出。所以入队后必须重读 `ctl`——若已非 RUNNING 就 `remove` 回来再拒绝（`remove` 失败说明任务已被执行，不能拒）；若 worker 数为 0 就补一个空 worker 去消费队列。

**Q4：为什么用了无界队列，maximumPoolSize 就永远不生效？**
看 `execute()` 的分支顺序：**先尝试入队，入队失败才扩容**。`LinkedBlockingQueue` 默认容量 21 亿，`offer` 永远成功，所以永远走不到 `addWorker(command, false)` 那一行。结果就是「线程数恒等于 core，队列无限长，拒绝策略永不触发」。

**Q5：核心线程能回收吗？**
能，但要 `allowCoreThreadTimeOut(true)`，且 `keepAliveTime` 必须大于 0，否则构造时就抛 `IllegalArgumentException`——而 `newFixedThreadPool` 的 keepAlive 恰好是 0，得先改。回收逻辑在 `getTask()` 里：`timed = allowCoreThreadTimeOut || wc > corePoolSize`。

**Q6：线程数设多少？**
`N = 核数 × 目标利用率 × (1 + 等待时间/计算时间)`。8 核、70%、45:5 → 56。然后用 Little's Law 交叉验证：56 / 0.05s = 1120 QPS，CPU 占用 1120 × 5ms = 5.6 核 = 70%，闭环。**能说出「我要交叉验证」比说出 56 这个数更重要。**

**Q7：队列设多大？**
`队列 = 到达率 × 可容忍排队时间`，1000 QPS × 150ms = 150。设成 10000 的真实含义是「允许 1 万个请求干等 10 秒」——用户早走了，但这些任务还占着堆。

**Q8：submit 和 execute 有什么区别？**
`execute` 只收 `Runnable`、无返回值，任务抛异常会让 worker 线程死掉并被重建，`UncaughtExceptionHandler` 能收到。`submit` 收 `Runnable`/`Callable`、返回 `Future`，内部包成 `FutureTask`，**异常被存进 outcome，只有 get() 才抛出**——不 get 就静默丢失。这是「任务失败但没日志」的头号原因。

**Q9：四种拒绝策略怎么选？**
核心判断是「过载时谁该难受」。后台批处理用 `CallerRuns`（背压到生产者，天然限流）；对外接口用 `Abort` + 上层统一转 503（过载必须可见）；`Discard` 系列只适合「可丢」的日志、埋点。**`CallerRuns` 绝对不能用在 Netty EventLoop、Tomcat 请求线程、定时任务调度线程上**——它会把一次线程池过载升级成全站阻塞。

**Q10：shutdown 和 shutdownNow 的区别？**
`shutdown` = 停止接收 + 把队列跑完；`shutdownNow` = 停止接收 + 清空队列（返回未执行的任务）+ `interrupt` 所有 worker。但要强调：**`interrupt` 只是「请求停止」**，任务若不在可中断的阻塞点上，照样跑完。

**Q11：thenApply 的回调在哪个线程执行？**
四种情况：① 上游已完成 → 调用 `thenApply` 的当前线程同步执行；② 上游未完成 → **完成上游的那个线程**；③ `thenApplyAsync` → commonPool（并行度 ≤ 1 时每任务新建线程）；④ `thenApplyAsync(fn, pool)` → 指定池。**只有第四种是你能控制的**，所以生产代码只该用第四种。

**Q12：allOf 会传播异常吗？**
会，但**不是快速失败**。源码里 `andTree` 建的是二叉树，每个 `BiRelay` 要等左右子树都完成才 fire，所以第一个任务失败了也要等全部跑完才拿到异常，而且多个异常只保留一个。要求「任一失败立刻返回」就得自己用 `anyOf` 或共享失败信号实现。

**Q13：ForkJoinPool 为什么比普通线程池快？**
每个 worker 一个双端队列：主人从 `top` 端 push/pop（LIFO，数据在缓存里），闲着的线程从别人的 `base` 端偷（FIFO，取最老的，减少争抢）。两端由不同角色操作，`base` 和 `top` 分属不同字段，把竞争降到接近零。**但它只适合可切分、CPU 密集、粒度 ≥ 8192 的任务**；阻塞任务要配 `ManagedBlocker`，否则并行度被永久吃掉。

**Q14：有了虚拟线程，线程池还要不要？**
「每任务一线程」形态的池该删（池化虚拟线程没有收益），但**线程池的三个职责要换成别的东西保留**：限流 → `Semaphore`；隔离 → 每个下游独立配额；昂贵资源复用 → 连接池仍然必须。另外 JDK 21~23 的 `synchronized` pinning 会让载体线程停摆，迁到 JDK 24+（JEP 491）才安全。

---

## L14 三道模拟面试题

### 题 1　线上告警：某服务线程池队列持续积压，活跃线程数 100%，运维把 core 从 50 调到 200 后毫无改善。你怎么查？

**及格**
先看线程池指标：poolSize、queue.size()、completedTaskCount 增量。判断是「消费不足」还是「生产突增」。

**良好**
补上：加线程无效说明瓶颈不在线程数，在**每个任务的执行时间**。用 `jstack` 抓三次，看 worker 的栈顶停在哪个下游调用上；同时看下游 RT 的 P99 是否抬升。另外要检查 `setCorePoolSize` 的行为——如果队列已满，它确实会补线程；如果队列是满的但补了也没用，说明是下游慢了。

**优秀**
再补三层：

1. **区分「慢」和「卡」**——如果 activeCount 100% 但 CPU 低于 10%，全是 IO 等待，去看下游连接池是否耗尽（第 09 期的 Little's Law）；如果 CPU 也高，是本地计算或 GC。
2. **检查僵尸任务**：如果代码里用了 `orTimeout` 或 Feign 的 readTimeout，接口早就超时返回了，但线程还在跑——此时 poolSize 满、queue 涨、但吞吐（completedTaskCount 增量）是正常甚至偏高的，这个组合叫「超时后线程泄漏」，唯一解法是缩短下游客户端的读超时。
3. **反过来质疑队列设计**：队列积压本身就是设计缺陷，正确做法是队列有界 + 快速拒绝 + 上游限流，而不是靠扩容硬扛。

### 题 2　设计下单页的并行查询：要拿库存、价格、优惠券、用户等级四个依赖，要求 P99 小于 200 ms，且任一依赖故障不能阻断下单。

**及格**
用 `CompletableFuture.supplyAsync` 并行发起四个请求，`allOf` 等全部完成后组装返回。

**良好**
补上：每个 future 加 `orTimeout` 设独立上限（库存 150 / 价格 180 / 优惠券 120），加 `exceptionally` 降级到默认值；`allOf().join()`；四个下游用各自独立的线程池做隔离。

**优秀**
再补四点：

1. **显式传执行器**，并说明原因——不传走 commonPool（并行度 7、全局共享），一段阻塞代码会拖死同 JVM 所有异步任务。
2. **说清 `orTimeout` 的局限**：它只让 future 提前异常完成，**不会中断底层任务**，所以下游线程仍在占资源；真正的取消要靠 HTTP client 的 readTimeout，且写操作超时后必须配幂等键。
3. **降级要有业务语义**：库存未知 ≠ 库存为 0，前者允许下单后校验，后者直接拦死；优惠券拿不到应按「无券」计价但要标记，便于事后补发。
4. **加熔断**：超时率达到阈值就跳过该依赖直接降级，不再每次都白等 120 ms——这一步才能把 P99 真正压进 200 ms。

### 题 3　秒杀活动中用线程池异步写订单，压测到 3000 QPS 后大量 RejectedExecutionException，但 CPU 只有 40%。给出定位思路和改造方案。

**及格**
是队列满了。调大队列或调大 max，或者换 `CallerRunsPolicy` 让调用方自己跑。

**良好**
先别急着调参：CPU 40% 而队列满，说明任务都在等 IO（写库）。查数据库连接池是否只有 20 个连接——线程池再大，连接池才是真闸门。改造方向是把线程数对齐连接数，而不是无限加。

**优秀**
分三层答：

1. **先分清三种拒绝原因**（池关闭 / 到 max / 创建线程失败），catch 时打印 `poolSize + queue.size() + isShutdown()` 才能定性；这里 CPU 40% + 队列满，几乎肯定是「下游连接池耗尽导致任务变慢」而不是「池太小」。
2. **架构上不该用线程池扛削峰**：秒杀的正确形态是 Redis 预扣减 + MQ 异步落单（第 10 期），线程池只是进程内的缓冲，容量只有几百，挡不住 3000 QPS 的洪峰。
3. **如果必须用，改造要点**：队列有界（按「连接数 × 可容忍等待」算）、max 对齐连接池大小、`AbortPolicy` + 快速失败返回「排队中，请稍后查询」、接口层用令牌桶限流（第 08 期）、并考虑虚拟线程 + `Semaphore` 限流替代固定池——因为这里 100% 是 IO 等待，虚拟线程能把「等」的成本降两个数量级。

---

## 小结：如果只记三句

1. **线程池是限流器，不是对象池。** 所有参数选择都是在「并发度」和「排队延迟」之间兑换，调优调的从来不是性能，是兑换比例。
2. **一个 int 撑起了整个并发控制。** 3 位状态 + 29 位数量挤在一起，为的是让「状态没变」和「数量 +1」能在一次 CAS 里完成——这是无锁设计里最漂亮的一手。
3. **异步编排里，只有显式传执行器的那一种写法是你能控制的。** `thenApply` 的线程归属有四种，commonPool 只有 7 个线程且全 JVM 共享，`orTimeout` 也不会真的中断下游——这三条漏掉任何一条，线上都会以「莫名变慢」的形式还给你。

> **下期预告**：下一期换个方向——**Java 内存泄漏与对象生命周期取证**。从 `jmap -histo` 的支配树到 MAT 的 GC Roots 路径，把「内存缓慢上涨但不 OOM」这类最难查的问题拆成可执行的五步；顺带讲清 `ThreadLocal`、`ClassLoader`、`DirectByteBuffer`、监听器未注销这四类经典泄漏各自的引用链长什么样。**这期会给你一条能直接跑的取证命令序列。**

---

*Java 面试深水区 · 第 20 期 · 线程池深水区与异步编排*
*14 张手绘 SVG · 21 段源码与可跑代码 · 20 个速查数字 · 14 问答 · 3 道模拟面试*
