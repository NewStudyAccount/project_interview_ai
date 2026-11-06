# Java 面试深挖 09 · 线上问题排查实战：CPU 100% / OOM / 死锁 / arthas / 连接池

> 「你处理过线上问题吗？」——答「处理过，重启就好了」，面试基本到此为止。
> 因为面试官想听的不是「你重启了」，而是「**你在重启之前，从现场取到了什么证据**」。

前八期把组件一个个拆开看了：HashMap、JVM、并发、Spring、MySQL、Redis、MQ、微服务治理。
这一期反过来——**当这些东西一起在凌晨三点崩掉时，你怎么在 5 分钟内找到那一行代码**。

排查能力的分水岭不在「知道多少种 OOM」，而在三个顺序问题：
**先止血还是先查因？先看哪个指标？拿到线程栈之后看第几行？**
这三个问题答对，你就是那个「能扛事的人」；答错，你就是那个「重启工程师」。

---

## L0 排查方法论：先止血，再定位，最后才谈根因

### 三条铁律

**① 止血优先于定位。**
线上每分钟在亏钱，而你找一个偶发 bug 可能要 3 小时。正确顺序是**先让业务恢复**（限流 / 摘流 / 回滚 / 扩容 / 降级），**再在有余量的环境里查根因**。
只有一种情况要先查因：**问题正在扩散且回滚无效**（例如数据被写坏、脏数据在扩散），此时必须边止血边取证。

**② 只读取证，绝不急着重启。**
重启 = 现场销毁。凡是「重启就好了」的问题，**100% 会复发**，而且下次复发时你手上依然什么都没有。
取证动作全是只读的：`top`、`jstack`、`jstat`、`jmap -histo`、`arthas`、`ss`、`iostat`。这些都不会改变现场。

例外：`jmap -dump:live` 会触发一次 Full GC，`heapdump` 同理。**堆已经快满时先 dump 再重启**，因为满了之后 dump 可能失败。

**③ 结论必须能被数字证实。**
「可能是 GC 问题」不是结论，「FGC 从 0 涨到 120 次/分钟，单次停顿 620ms，吞吐下降 47%」才是结论。
「可能是连接池不够」不是结论，「200 QPS × 200ms = 40 个并发连接需求，池上限 10，排队 30s 后超时」才是结论。

### 双轨时间轴

```
T+0    ┬  告警响起
       │
       │  【止血轨】────────────────────────────────
T+2    │   ① 判断影响面：哪些接口、多少用户、是否在扩散
T+4    │   ② 一招止血：限流 / 摘流 / 回滚 / 扩容 / 降级（选最快的那招）
T+8    │   ③ 确认指标回落，业务恢复
       │
       │  【取证轨】────────────────────────────────
T+1    │   ④ 只读三连：top -Hp、jstack、jstat -gcutil
T+6    │   ⑤ 保存现场：jstack > /tmp/t1.log、jmap -dump、GC 日志
T+15   │   ⑥ arthas 上：thread -n 3 / trace 慢方法 / watch 入参
T+60   │   ⑦ 本地或预发复现，缩小到类 + 方法 + 行
T+180  │   ⑧ 根因 + 修复 + 回归 + 复盘（机制性修复，不是「下次小心」）
```

注意 ④ 在 ② 之前——**止血的同时就并行取证**，因为很多现场（线程状态、堆内容）一旦重启就永远拿不到了。

### 五分钟分诊表

| 现象 | 第一条命令 | 关键读数 | 初判方向 |
|---|---|---|---|
| 接口全超时、CPU 100% | `top -Hp <pid>` | 单线程 100% / 多线程均高 | 死循环 vs GC vs 计算密集 |
| 内存缓慢上涨、3 天必挂 | `jstat -gcutil <pid> 1000` | OU 逼近 100、FGC 递增 | 内存泄漏 |
| 突然 OOM，但堆没满 | `dmesg -T \| tail` | `OOMKilled` / `Out of memory` | 堆外 / 容器限制 |
| 偶发 30s 超时，DB 却不忙 | `jstack \| grep -c getConnection` | 大量 WAITING | 连接池耗尽 |
| 线程数暴涨到几千 | `jstack \| wc -l`、`ss -s` | 线程名前缀集中 | 池配置 / 线程泄漏 |
| RT 抖动 + 周期性卡顿 | GC 日志 | `Pause` 时长、`to-space exhausted` | 大对象 / 晋升失败 |
| 请求卡住不返回、CPU 不高 | `jstack` | 大量 BLOCKED 同锁 / `Found one Java-level deadlock` | 死锁 / 锁竞争 |
| 发布后 5 分钟开始报错、10 分钟自愈 | 看实例级错误率 | 只有新实例报错 | 摘流窗口（见第 08 期） |

---

## L1 CPU 100% 的六种真凶

CPU 打满是最好查的一类问题，因为**它一定会留在线程栈里**。难点不在取证，在**认出是哪种**。

### 取证三步（30 秒内拿到现场）

```bash
# ① 找到进程，再找到进程里最烧 CPU 的线程（-H = 显示线程）
top -Hp 12345
#   PID  USER  %CPU  COMMAND
# 12378  app   98.9  java      ← 就是这个

# ② 十进制 tid 转十六进制（jstack 里的 nid 是十六进制）
printf '%x\n' 12378           # → 305a

# ③ 在 jstack 里定位这一帧
jstack 12345 | grep -A 30 'nid=0x305a'
```

输出长这样，**答案就在第一行 at**：

```
"http-nio-8080-exec-7" #47 daemon prio=5 os_prio=0 tid=0x00007f... nid=0x305a runnable [0x...]
   java.lang.Thread.State: RUNNABLE
        at com.demo.order.OrderService.calcTotal(OrderService.java:88)   ← 现场
        at com.demo.order.OrderController.submit(OrderController.java:41)
```

**读法三条**：
- `nid=0x305a` 必须和 ① 的 tid 对上（十六进制），否则你看到的是别的线程的栈。
- `State: RUNNABLE` + `at 业务代码` = 真的在跑你的代码。
- `State: RUNNABLE` + `at java.net.SocketInputStream.socketRead0` = **在等网络，不算真烧 CPU**（native 方法在 jstack 里也显示 RUNNABLE，这是最大的坑）。

注意 `%CPU` 的口径：**单核跑满 = 100%**，16 核机器 `top` 顶部 1600% 才是全满。
所以「CPU 100%」这个告警，在容器里通常指「**用满了 limit**」，而不是「用满了整机」。

### 六种真凶的指纹表

| # | 真凶 | 线程状态 | 栈特征（第一行 at） | 附带证据 |
|---|---|---|---|---|
| 1 | 死循环 / 无限重试 | RUNNABLE，单线程 100% | 同一个方法反复出现 | 该接口 RT 无限长 |
| 2 | 频繁 Full GC | GC 线程占 CPU | `GC Thread#` / `G1 Evacuation` | `jstat` FGC 递增、堆将满 |
| 3 | 锁争用 / 上下文切换 | 大量 BLOCKED | `Object.wait` / `park` | `vmstat 1` 的 `cs` 飙升、`sy` 高 |
| 4 | 正则回溯 | RUNNABLE | `Pattern$Curly.match` / `Loop.match` | 入参是超长字符串 |
| 5 | 序列化 / 大对象 | RUNNABLE | `ObjectMapper._writeValueAndClose` | YGC 同时飙升（大量临时对象） |
| 6 | 加解密 / 压缩 | RUNNABLE | `Cipher.update` / `Deflater.deflate` | 高 QPS 的小包 HTTPS、RSA |

### 真凶 1：死循环

```java
// 事故代码：用 == 比较 Integer，超过 127 缓存范围就永远不相等
public int findIndex(List<Integer> list, Integer target) {
    int i = 0;
    while (list.get(i) != target) {   // 找不到时 i 一路自增 → IndexOutOfBounds
        i++;                          // 若外层 catch 了异常并 continue → 死循环
    }
    return i;
}
```

更隐蔽的一种：**在循环里做远程调用并无限重试**

```java
while (true) {
    try {
        return remoteClient.query(id);        // 下游挂了 → 每次抛异常 → 立刻重试
    } catch (Exception e) {
        // 没有 sleep、没有次数上限、没有退避
    }
}
```

这种代码的特征是：**CPU 高 + 下游超时 + 日志疯狂刷同一行**。
修复必须是三重：**次数上限 + 指数退避 + 熔断**。只加 `Thread.sleep(100)` 是没用的，它只是把死循环变成慢死循环。

### 真凶 4：正则回溯（最容易被忽略的一种）

```java
// 校验「一段字母后面跟一个数字」，写成了嵌套量词
Pattern P = Pattern.compile("^(a+)+$");
P.matcher("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!").matches();   // 30 个 a + 一个 !
```

`(a+)+` 对于不匹配的输入，会把「30 个 a 怎么分组」的所有组合都试一遍，复杂度是 **2^30 ≈ 10.7 亿次**。
单次调用可能耗时几秒，**10 个并发就把 CPU 打满**，而且**线程栈看起来完全正常**（就是在 `Pattern$Curly.match` 里跑），很多人会误判成「JVM 卡了」。

三种修法：

```java
// ① 消灭嵌套量词：把 (a+)+ 改成 a+
Pattern.compile("^a+$");

// ② 用占有量词 / 独立分组，禁止回溯
Pattern.compile("^(?>a+)+$");

// ③ 最稳的：先卡长度，再匹配（长度超过业务上限的输入直接拒绝）
if (input.length() > 64) return false;
```

**面试口径**：正则回溯是**唯一一种「CPU 打满但代码看起来没问题、GC 也正常」**的故障。
判断标志是「入参是用户可控的长字符串 + 栈停在 regex 包 + 堆和 GC 都健康」。

### 真凶 2：频繁 GC

```bash
jstat -gcutil 12345 1000 10
#   S0     S1     E      O      M     CCS    YGC   YGCT    FGC   FGCT     GCT
#   0.00  96.12  99.31  99.87  94.21  92.10   8421  96.32    317  198.44  294.76
```

读法：
- `O`（老年代使用率）**99.87%** + `FGC` 持续增长 = 老年代满了，正在疯狂 Full GC。
- `FGCT / FGC` = 单次 Full GC 平均耗时 = 198.44/317 ≈ **626ms**。
- 如果 FGC 每分钟 60 次、每次 626ms，**每秒有 0.6 秒在做 GC**，吞吐直接掉 60%。

此时 CPU 高的是 **GC 线程**，不是业务线程。`top -Hp` 里能看到名字是 `GC Thread#0`、`G1 Young RemSet Sampling` 这类。
**这种情况不要再去 jstack 找业务死循环**，方向错了。

处理顺序：先 `jmap -histo:live`（或 dump）看什么对象占大头 → 若是本不该活那么久的对象（缓存/泄漏）→ 修代码；若确实是内存不够 → 调大堆或加机器。**先 dump，再调参**，否则参数一改现场就没了。

### 真凶 3：锁争用

CPU 高但**业务没干多少活**，`vmstat` 里 `cs`（上下文切换）每秒十万级，`sy`（内核态）占比高——这是**线程在抢锁**，不是在做计算。

```bash
jstack 12345 | grep -c "BLOCKED"      # 数量异常高就是锁竞争
vmstat 1 5                            # 看 cs 与 sy
```

修复方向：缩小锁粒度（分段锁）、改无锁（`LongAdder` 替代 `AtomicLong` 的 CAS 自旋）、锁分离（读写锁）、或干脆去掉锁（ThreadLocal / 不可变对象）。

`AtomicLong` 在高并发下是个典型陷阱：**CAS 失败会自旋**，1000 个线程抢一个计数器时，CPU 全花在自旋上，吞吐反而不如加锁的 `synchronized`（这也是 `LongAdder` 存在的原因）。

---

## L2 内存：八种 OOM 与 dump 分析

「OOM」在面试里是个筛子：只答得出 `Java heap space` 的人，说明只见过这一种。

### 八种 OOM 的落点

```
┌───────────────────────── JVM 进程 ─────────────────────────┐
│                                                            │
│  ┌── 堆 ──────────────┐   ┌── 元空间 ──┐   ┌─ 栈 ───────┐ │
│  │ ① Java heap space  │   │ ③ Metaspace│   │ ⑧ StackOver│ │
│  │ ② GC overhead limit│   │            │   │   flowError│ │
│  └────────────────────┘   └────────────┘   └────────────┘ │
│                                                            │
│  ┌── 堆外 ─────────────────────────────────────────────┐   │
│  │ ④ Direct buffer memory（MaxDirectMemory 默认=堆上限）│   │
│  └─────────────────────────────────────────────────────┘   │
│                                                            │
│  ┌── 操作系统资源 ────────────────────────────────────┐    │
│  │ ⑤ unable to create new native thread（线程数上限） │    │
│  │ ⑥ Requested array size exceeds VM limit（数组>2G） │    │
│  │ ⑦ Map failed / Out of swap space（mmap 失败）      │    │
│  │ ⑨ kill process or sacrifice child（Linux OOM Killer）│  │
│  └────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────┘
```

| # | 报错原文 | 真正原因 | 第一手命令 |
|---|---|---|---|
| ① | `Java heap space` | 堆里对象太多 / 单个对象太大 | `jmap -histo:live` + dump |
| ② | `GC overhead limit exceeded` | 98% 时间在 GC 却回收不到 2% | `jstat -gcutil`，本质同 ① |
| ③ | `Metaspace` | 动态生成类太多（CGLIB/Groovy/热部署） | `jstat -gc` 看 MC/MU |
| ④ | `Direct buffer memory` | Netty/NIO 堆外没释放 | `-XX:MaxDirectMemorySize` + `jcmd VM.native_memory` |
| ⑤ | `unable to create new native thread` | 线程数触顶（`ulimit -u`） | `ulimit -u`、`cat /proc/<pid>/status` |
| ⑥ | `Requested array size exceeds VM limit` | `new byte[Integer.MAX_VALUE]` | 代码 review |
| ⑦ | `Map failed` / `Out of swap space` | 物理内存 + swap 耗尽，mmap 失败 | `free -m`、`dmesg` |
| ⑧ | `StackOverflowError` | 递归太深 / 栈帧太大（**不是 OOM**） | `jstack` 看重复帧 |
| ⑨ | 容器 `OOMKilled`（exit 137） | **被内核杀的，JVM 没机会抛异常** | `dmesg -T`、`kubectl describe` |

**第 ⑨ 种是最高频的坑**：容器内存 limit 4G，JVM 堆设了 3G，看起来留了 1G 余量，但**堆外（元空间 + 直接内存 + 线程栈 + GC 自身结构）**轻松吃掉 1.5G → 进程被内核 OOM Killer 干掉 → 你只能看到 `exit code 137`，**连异常都没有，也没有 dump**。

所以容器里必须显式设 `-XX:MaxRAMPercentage=70`（而不是 `-Xmx` 拍脑袋），并留出堆外余量。

### 三种拿 dump 的方式

```bash
# ① 最推荐：让 JVM 自己吐（OOM 那一刻的现场，最真实）
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/dump

# ② 手动（注意：live 会先触发一次 Full GC，堆快满时可能直接失败）
jmap -dump:live,format=b,file=/data/a.hprof 12345
jcmd 12345 GC.heap_dump /data/a.hprof          # 等价，推荐用 jcmd

# ③ arthas（在线，不用额外 jdk 工具）
heapdump /data/a.hprof
```

**三个必须知道的数字**：
- dump 文件大小 ≈ 堆使用量。4G 堆 → 4G 文件。
- 打开 dump 需要 **2~4 倍**堆大小的内存（MAT 要把对象图建出来）。4G dump 至少给 8G。
- `jmap -dump:live` 的 STW 时间 ≈ 一次 Full GC，**4G 堆大约 0.5~2 秒**，高峰期慎用。

### MAT 三看：从 4G 的堆里找到那一行代码

不要一上来就翻 Histogram 找大对象——**大对象往往不是问题，数量多的小对象才是**。正确路径是三看：

**第一看：Leak Suspects（自动怀疑报告）**
MAT 会直接给「一个实例占 68% 内存」的结论和引用链。80% 的泄漏在这里就结束了。

**第二看：Dominator Tree（支配树）**
按「**这个对象被回收能释放多少内存**」排序，而不是按对象大小排序。
一个 `HashMap` 自身只有 48 字节，但它支配着 3G 的 value——只有支配树能看出来。

**第三看：Path to GC Roots（引用链）**
选中可疑对象 → `Path to GC Roots` → **勾选 exclude weak/soft/phantom references**。
这一步是整个排查的核心：**引用链的终点就是那行代码**。

```
Thread http-nio-8080-exec-3
  └─ ThreadLocalMap (threadLocals)
       └─ Entry[17]  value → byte[52428800]   ← 50MB，就是这个
            ↑ key 是 ThreadLocal 弱引用，value 是强引用
```

### 三个经典泄漏，背下来

**① ThreadLocal 没 remove（最经典）**

```java
public class UserContext {
    private static final ThreadLocal<User> HOLDER = new ThreadLocal<>();

    public static void set(User u) { HOLDER.set(u); }
    // 忘了 remove()
}
```

为什么泄漏？看 `ThreadLocalMap.Entry` 的定义：

```java
static class Entry extends WeakReference<ThreadLocal<?>> {
    Object value;                 // ← 强引用！
    Entry(ThreadLocal<?> k, Object v) { super(k); value = v; }
}
```

- key 是**弱引用** → ThreadLocal 对象本身可以被 GC 回收，Entry 的 key 变成 `null`。
- value 是**强引用** → 只要 `Thread` 活着，`Thread.threadLocals` 就活着，value 就永远不释放。
- 线程池里线程**几乎永生** → 于是每个 Entry 都是一个永久泄漏点，**而且还会串数据**（下一个请求拿到上一个用户的 User）。

所以 `ThreadLocal` 必须 `try/finally` 里 `remove()`，这不是「好习惯」，是**正确性要求**。

**② 静态集合只加不减**

```java
private static final Map<String, Object> CACHE = new HashMap<>();   // 没有淘汰
```

局部缓存没上限 = 缓慢泄漏。用 Caffeine / Guava 并**必须设 maximumSize 和 expireAfter**。

**③ 连接 / 流没关（以及装饰器陷阱）**

```java
// 坑：包装流的 close 会关掉内层，但这里 new 了两层，只关外层不保险
BufferedReader r = new BufferedReader(new InputStreamReader(url.openStream()));
// 正确：try-with-resources，且注意「谁打开谁关闭」
```

---

## L3 线程：死锁与线程池打满

### jstack 四个指纹，一眼定性

拿到 `jstack` 不要逐行读，先 `grep` 出线程状态分布：

```bash
jstack 12345 > /tmp/t1.log
grep -c "java.lang.Thread.State: RUNNABLE" /tmp/t1.log
grep -c "java.lang.Thread.State: BLOCKED"  /tmp/t1.log
grep -c "java.lang.Thread.State: WAITING"  /tmp/t1.log
grep -A 20 "Found one Java-level deadlock" /tmp/t1.log
```

| 指纹 | 含义 | 结论 |
|---|---|---|
| 大量 `WAITING` 且栈在 `LinkedBlockingQueue.take` | 队列空，线程在等任务 | **不是打满，是闲置**（池开太大了） |
| 大量 `WAITING` 且栈在 `HikariPool.getConnection` | 等数据库连接 | 连接池耗尽 → 见 L5 |
| 大量 `RUNNABLE` 且栈在 `socketRead0` | 在等下游响应 | 下游慢 / 超时设太长 |
| 大量 `BLOCKED` 且同一个 `waiting to lock <0x...>` | 抢同一把锁 | 锁竞争 / 死锁前兆 |
| 全部 `WAITING` 在 `Condition.await` | 等外部信号 | 异步编排卡住（线程池套线程池） |

**特别注意**：`socketRead0` 显示的是 `RUNNABLE`。这是 jstack 最大的坑——
**线程在等网络时也是 RUNNABLE**，因为 native 方法执行中 JVM 无法判断它在阻塞。
所以「RUNNABLE 多」≠「CPU 忙」，要配合 `top -Hp` 的 `%CPU` 一起看。

### 死锁：jstack 会直接告诉你

```java
// 两个人互相转账，锁顺序相反 → 必然死锁
void transfer(Account from, Account to, int amount) {
    synchronized (from) {                  // 线程 A 锁住 acc1
        synchronized (to) {                // 线程 B 锁住 acc2
            from.minus(amount);            // A 等 acc2，B 等 acc1 → 环
            to.plus(amount);
        }
    }
}
```

jstack 输出（JDK 自动检测）：

```
Found one Java-level deadlock:
=============================
"pool-1-thread-2":
  waiting to lock monitor 0x00007f... (object 0x00000000d6f0a1b8, a com.demo.Account),
  which is held by "pool-1-thread-1"
"pool-1-thread-1":
  waiting to lock monitor 0x00007f... (object 0x00000000d6f0a2c8, a com.demo.Account),
  which is held by "pool-1-thread-2"

Java stack information for the threads listed above:
===================================================
```

**死锁的四个必要条件**（互斥、占有且等待、不可剥夺、循环等待）——要破只能破其中一个，
工程上唯一可行的是**破「循环等待」：全局锁顺序**：

```java
// 按账户 id 排序后再加锁，永远只有一个方向
void transfer(Account a, Account b, int amount) {
    Account first  = a.id < b.id ? a : b;
    Account second = a.id < b.id ? b : a;
    synchronized (first) {
        synchronized (second) { /* 转账 */ }
    }
}
```

其他两种工程解法：
- **`tryLock(timeout)`**：拿不到就放弃并回滚，避免无限等待（但要处理「部分成功」）。
- **单锁化**：把所有账户操作串行化到一个锁 / 一个队列（牺牲并发换正确性，很多支付系统真这么干）。

### 线程池「打满」的三种真相

面试问「线程池打满了怎么办」，90% 的人答「加大队列」。**这是最危险的操作**——队列变大只是把「拒绝」变成「超时后拒绝」，故障从秒级拖到分钟级。

先分清是哪一种：

| 类型 | 栈特征 | 真实原因 | 正确动作 |
|---|---|---|---|
| **真·堆积** | 队列满、`RejectedExecutionException` | 生产 > 消费 | 扩容 / 降级 / 限流上游 |
| **假·闲置** | 栈在 `take`，`poolSize` 远大于 `activeCount` | 池开太大 | **缩小池**，省内存省切换 |
| **卡死** | 全部 `socketRead0` 或 `getConnection` | 线程被 IO 占住 | 查下游，调超时，隔离舱 |

**线程数量的账要会算**：

```
每线程栈：默认 1MB（-Xss1m）
200 个线程 × 1MB = 200MB 栈内存（不在堆里！）
ulimit -u = 1024 → 第 1025 个线程直接 OOM: unable to create new native thread
```

所以「线程池开大点」的成本是：**每个线程 1MB 栈 + 一次上下文切换约 2~5μs**。
1000 个线程跑在 8 核上，CPU 有 30% 花在切换上，这不是并发，这是自残。

**线程池大小的经验公式**（Brian Goetz）：

```
CPU 密集型：N + 1
IO  密集型：N × (1 + 等待时间 / 计算时间)
```

8 核、单次请求 200ms 其中 190ms 在等 DB → `8 × (1 + 190/10) = 152` 个线程。
注意这个数字是**理论上限**，实际还要受下游连接池大小约束——**线程池再大，连接池只有 10 个，多出来的线程全在等连接**。

---

## L4 arthas：不用重启的线上手术刀

线上不能加日志、不能重启，arthas 是唯一能在**运行中的 JVM 里**看参数、看耗时、看返回值的工具。

### 六个命令覆盖 90% 场景

| 命令 | 作用 | 典型用法 |
|---|---|---|
| `dashboard` | 全局概览（线程 / 内存 / GC / 运行时） | 进来先看一眼 |
| `thread -n 3` | CPU 最高的 3 个线程 + 栈 | **替代 top -Hp + jstack 两步** |
| `thread -b` | 找出「阻塞其他线程」的那个线程 | 死锁 / 锁竞争神器 |
| `trace` | 方法内部调用链路耗时 | `trace com.x.Svc m -n 5 '#cost>100'` |
| `watch` | 看入参 / 返回值 / 异常 | `watch com.x.Svc m '{params,returnObj}' -x 2` |
| `jad` | 反编译线上类 | **确认线上跑的是哪一版代码** |

### trace：把一次慢请求拆成火焰图

```bash
$ trace com.demo.order.OrderService submit -n 5 '#cost > 100'
`---ts=2026-11-06 03:12:41;thread_name=http-nio-8080-exec-7;id=2f;is_daemon=true
    `---[1284.51ms] com.demo.order.OrderService:submit()
        +---[0.32ms] com.demo.order.OrderService:validate()
        +---[0.18ms] com.demo.order.OrderService:buildOrder()
        +---[1241.07ms] com.demo.order.OrderService:checkStock()     ← 就是这
        |   `---[1240.55ms] com.demo.inventory.InvClient:query()
        |       `---[1240.21ms] org.springframework.web.client.RestTemplate:getForObject()
        `---[42.61ms] com.demo.order.OrderService:save()
```

一眼就能看出：**1284ms 里 1241ms 花在一次 HTTP 调用上**。这才是「慢在哪」的答案，日志打印只能告诉你「慢」，不能告诉你「哪一层慢」。

三个参数必须写全：
- `-n 5`：只采样 5 次。**不写会把生产打爆**（每次调用都增强）。
- `'#cost > 100'`：只打印超过 100ms 的。不写会刷屏。
- 类名要**全限定**，且注意**接口和实现类**——增强接口方法常常不生效，要写实现类。

### watch：抓那个「偶发」的入参

```bash
# 只在这个用户的请求上停住，看入参和返回值
watch com.demo.order.OrderService submit '{params, returnObj, throwExp}' \
      'params[0].userId == 10086' -x 2 -n 5
```

`-x 2` 是展开层级（默认 1，嵌套对象会显示成 `{...}`）。
`throwExp` 必须在条件里——否则异常路径不打出来，你会以为「方法没被调用」。

### 一个真实坑：热部署 + jad

改了代码、发了包、但行为没变？用 `jad` 反编译线上类，对比源码：

```bash
jad com.demo.order.OrderService
# 输出里能看到 Counter 类被重复加载、或方法体是旧版本
```

`Metaspace` OOM 的常见来源就是**热部署不断重新加载类**，旧 ClassLoader 不释放。

---

## L5 慢 SQL 还是连接池耗尽？——最容易误判的一类

现象完全一样：**接口超时，日志里一堆 `timeout`**。但根因和处理方式完全相反。

### 先用一道算术题定位

**Little's Law**（排队论，比任何经验都准）：

```
同时占用连接数 L = QPS(λ) × 平均持有时间 W
```

代入真实数字：**200 QPS，每请求持连接 200ms**

```
L = 200 × 0.2 = 40 个连接
HikariCP maximumPoolSize 默认 10
→ 40 个并发需求挤 10 个坑 → 排队
→ 排到第 30 秒还没轮到 → connectionTimeout 抛异常
```

`HikariPool-1 - Connection is not available, request timed out after 30000ms` —— 看到这句，**不用怀疑，就是池不够或有人不还**。

### 分诊图

```
接口超时
  │
  ├─ jstack 里线程栈是 HikariPool.getConnection
  │     │
  │     ├─ DB 侧 processlist 连接数少、CPU 低、慢日志没记录
  │     │     → 【连接没还】事务里干了别的事（调 HTTP / 发 MQ / sleep）
  │     │
  │     └─ DB 侧 active 高、慢日志有记录、CPU 高
  │           → 【慢 SQL】索引 / 大表 / 锁等待 / 回表
  │
  └─ jstack 里线程栈是 socketRead0（没有 getConnection）
        → 【下游慢】不是 DB 的问题，去查 RPC/HTTP 超时配置
```

这个分诊的关键动作只有一个：**同时看应用侧线程栈 + 数据库侧 `show processlist`**。
只看一边，必然误判。

### 「连接没还」的五种写法（每一种我都见过线上事故）

**① `@Transactional` 里调外部接口**

```java
@Transactional
public void createOrder(Order o) {
    orderMapper.insert(o);              // 拿到连接，事务开始
    smsClient.send(o.getPhone());       // 外部 HTTP，可能 3 秒
    inventoryClient.lock(o.getSku());   // 又一个远程调用
}                                       // 全部做完才提交、才还连接
```

一个请求持有连接 3 秒以上，200 QPS 需要 **600 个连接**。池只有 10 个 → 立刻雪崩。
**正确做法：事务内只做 DB 操作，远程调用放到事务外**（或事务提交后异步做）。

**② 流式查询没关 ResultSet / 用了 `selectList` 查百万行**
**③ 手写 JDBC 忘了 `close()`**（`try-with-resources` 能解决）
**④ 嵌套事务 `REQUIRES_NEW` 在同一线程里开了两个连接**（内层不提交，外层一直等）
**⑤ 连接池 leakDetectionThreshold 没开**，泄漏了也不知道

```yaml
# HikariCP 生产必备三项
maximum-pool-size: 20                 # 按 Little's Law 算，不是拍脑袋
connection-timeout: 3000              # 默认 30s 太长，故障时线程全挂死
leak-detection-threshold: 20000       # 借出 20s 未归还就打印堆栈（排泄漏神器）
```

---

## L6 速查表：一张纸的命令清单

```bash
# ── CPU ──────────────────────────────────────────────
top -Hp <pid>                        # 线程级 CPU
printf '%x\n' <tid>                  # 十→十六进制
jstack <pid> | grep -A 30 'nid=0x<hex>'

# ── 内存 ─────────────────────────────────────────────
jstat -gcutil <pid> 1000             # GC 使用率与次数
jmap -histo:live <pid> | head -30    # 对象直方图（会触发 FGC）
jcmd <pid> GC.heap_dump /data/a.hprof
jcmd <pid> VM.native_memory summary  # 需 -XX:NativeMemoryTracking=summary

# ── 线程 ─────────────────────────────────────────────
jstack <pid> > /tmp/t1.log
grep -c BLOCKED /tmp/t1.log
grep -A 20 "Found one Java-level deadlock" /tmp/t1.log
cat /proc/<pid>/status | grep Threads

# ── 系统 ─────────────────────────────────────────────
vmstat 1 5                           # cs / sy / wa
iostat -x 1                          # %util 接近 100 = IO 打满
ss -s                                # socket 总数与状态
dmesg -T | grep -i "killed process"  # 是不是被内核杀的
ulimit -u                            # 进程/线程数上限

# ── 数据库 ───────────────────────────────────────────
show processlist;                    # 谁在跑、跑了多久
show engine innodb status\G          # 锁等待、最近死锁
show variables like 'slow_query%';   # 慢日志开关

# ── arthas ───────────────────────────────────────────
dashboard
thread -n 3
thread -b
trace com.x.Svc method -n 5 '#cost > 100'
watch com.x.Svc method '{params,returnObj,throwExp}' 'params[0].id==1' -x 2
jad com.x.Svc
heapdump /data/a.hprof
stop                                 # 退出时务必 stop，别留着增强
```

---

## L7 十四条高频问答

**Q1：线上 CPU 100%，你的排查步骤？**
`top -Hp` 找最烧 CPU 的线程 → `printf %x` 转十六进制 → `jstack | grep nid=0x` 定位栈。
然后按栈定性：业务代码循环（死循环）、`Pattern`（正则回溯）、`Cipher`（加解密）、GC 线程（内存问题）、大量 BLOCKED（锁竞争）。
一句话加分项：**先看是不是 GC 线程，因为方向完全相反**。

**Q2：jstack 里 RUNNABLE 很多，是不是 CPU 打满了？**
不一定。`socketRead0` / `epollWait` 这些 native 阻塞方法在 jstack 里也显示 RUNNABLE。
必须配合 `top -Hp` 的 `%CPU`，**线程状态和 CPU 占用是两回事**。

**Q3：OOM 有哪几种？**
堆（Java heap space / GC overhead）、元空间（Metaspace）、堆外（Direct buffer）、资源（new native thread / array size / Map failed）、以及**被内核杀的 OOMKilled（exit 137，没有异常也没有 dump）**。

**Q4：容器里为什么老是被 OOMKilled，堆明明没满？**
JVM 总内存 = 堆 + 元空间 + 直接内存 + 线程栈 + GC 结构 + JIT 代码缓存。堆设 3G 而 limit 是 4G 时，堆外轻松超 1G。
解药：`-XX:MaxRAMPercentage=70`，剩余 30% 留给堆外。

**Q5：dump 怎么拿？拿不到怎么办？**
首选 `-XX:+HeapDumpOnOutOfMemoryError`（OOM 瞬间自动吐）。
在线用 `jcmd GC.heap_dump` 或 arthas `heapdump`。
拿不到的情况：被内核 kill（无 dump）、堆太大磁盘不够、dump 到 tmpfs 把内存打爆。
备选：`jmap -histo:live` 看类维度分布（轻量，只有统计没有对象图）。

**Q6：MAT 里怎么快速找到泄漏点？**
Leak Suspects → Dominator Tree（按「回收能释放多少」排序）→ 选中对象 → Path to GC Roots（**exclude 软/弱引用**）。
引用链的终点就是持有它的那个字段，那就是代码位置。

**Q7：ThreadLocal 为什么会内存泄漏？**
Entry 的 key 是弱引用（ThreadLocal 本身可回收），value 是强引用；线程不死（线程池），value 就永远挂在 `Thread.threadLocals` 上。
而且 key 变 null 后，下一个请求复用该线程时会**读到别人的数据**。
所以必须 `try/finally remove()`。

**Q8：怎么排查死锁？**
`jstack` 会直接打印 `Found one Java-level deadlock` 和环形等待的两个线程。
没有自动检测时（比如 `ReentrantLock` 的 `lockInterruptibly` 场景），看「waiting to lock」的对象地址是否互指。
预防：**全局锁顺序**（按 id 排序）、`tryLock` 带超时、减少锁粒度。

**Q9：线程池打满了怎么处理？**
先分清：真堆积（队列满、有拒绝）、假闲置（栈在 take、pool 远大于 active）、卡死（全在 socketRead/getConnection）。
- 真堆积 → 限流上游 + 扩容 + 降级，**不要盲目加大队列**（把拒绝变成超时）。
- 卡死 → 查下游 + 缩短超时 + 线程池隔离。
- 假闲置 → 缩小池，省 1MB × N 的栈内存。

**Q10：线程池大小怎么定？**
CPU 密集 `N+1`；IO 密集 `N × (1 + 等待/计算)`。
但**上限由下游连接池决定**——线程池 200 而连接池 10，多出来的 190 个线程全在 `getConnection` 上排队，等于白开。

**Q11：接口超时，怎么区分慢 SQL 和连接池耗尽？**
看应用线程栈：栈里是 `HikariPool.getConnection` → 连接池问题；是 `socketRead0` 且没有 getConnection → 下游问题。
再看 DB 侧：`processlist` 连接少 + DB 空闲 → **连接没还**（事务里调外部接口）；`active` 高 + 慢日志有记录 → **慢 SQL**。
**两边一起看**才不会误判。

**Q12：为什么 `@Transactional` 里调 HTTP 会导致雪崩？**
事务开启即占用连接，直到方法返回才提交归还。一次 HTTP 3 秒 = 这个连接 3 秒不可用。
200 QPS × 3s = 600 个连接需求，池 10 个 → 排队 30s 超时 → 线程池被打满 → 整个服务不可用。
解药：事务只包 DB 操作，远程调用移出事务。

**Q13：arthas 的 trace 会不会影响性能？**
会。它做的是**字节码增强**，每次命中方法都要走增强逻辑。
所以生产必须 `-n`（采样次数）+ `#cost`（耗时过滤）。用完 `stop` 或 `reset` 撤销增强。
高 QPS 接口用 `watch` 加条件比 `trace` 更安全。

**Q14：怎么证明「不是我们的问题」？**
用数据把链路切开：本服务 RT（arthas trace）、下游 RT（`socketRead0` 耗时）、连接池指标、DB 慢日志、出口 QPS。
**只说自己没问题不算证据，能指出「在我们这里耗时 3ms，在调用下游时耗时 1200ms」才算。**
顺便检查重试放大：3 层重试 × 3 次 = 27 倍流量，可能是你把下游打挂的。

---

## L8 三道模拟面试题

### 题一：凌晨 3 点告警「核心接口 CPU 100%、全部超时」。你只有一台跳板机和 5 分钟，怎么办？

**考察点**：止血与取证的优先级 + 是否知道「重启会销毁现场」+ 命令是否真的敲过。

**参考答案骨架**

1. **第一分钟：判断影响面，不是先看 CPU。**
   `curl` 一下接口、看监控大盘的 QPS 与错误率。是全挂还是部分挂？只有一台实例还是一批？**决定是「摘这一台」还是「回滚整个版本」**。

2. **第二步：止血（可以在 1 分钟内完成）。**
   如果只有一台异常 → 直接从负载均衡摘掉这台，业务立刻恢复，然后从容排查。
   如果全集群 → 先限流（网关降到 30%），保住核心链路，避免雪崩到 DB。

3. **第三步：并行取证（关键，且必须只读）。**
   ```bash
   top -Hp <pid>                    # 找线程
   printf '%x\n' <tid>
   jstack <pid> > /tmp/t1.log
   jstack <pid> > /tmp/t2.log       # 隔 5 秒再打一次
   jstat -gcutil <pid> 1000 5
   ```
   **打两次 jstack 是关键技巧**：对比两次之间哪个线程的位置没变 → 那就是卡住的地方（死循环 / 阻塞点）。

4. **第四步：按栈定性。** GC 线程高 → 内存问题，转 `jmap`；业务代码 → 死循环/正则；大量 BLOCKED → 锁。
   **不要在没有 dump/jstack 的情况下重启**。

5. **第五步：根因与修复。** 找到具体行 → 判断是「代码缺陷」还是「容量不足」→ 前者改代码 + 回归，后者扩容 + 加限流。

**满分线**：主动说出「我会先摘流再查，因为重启之后 `jstack` 就没了」，以及「打两次 jstack 做 diff」。

**常见坑**：一上来 `top` 看到 100% 就重启；或者盯着 `jstack` 从头读 400 行线程栈，5 分钟过去了还没定位。

---

### 题二：服务运行 3 天必 OOM，重启就恢复。给你一份 4G 的 dump，怎么定位到那一行代码？

**考察点**：是否真的打开过 MAT + 是否理解「引用链」+ 是否知道常见泄漏模式。

**参考答案骨架**

1. **先看 OOM 报错类型**：`Java heap space` → 堆内泄漏；`Metaspace` → 类加载泄漏；`Direct buffer` → 堆外。
   「3 天必挂」这个时间特征说明是**缓慢累积型**，不是突发的流量尖峰。

2. **MAT 三看**（说清顺序，别说「我一般随便点」）：
   - Leak Suspects：MAT 会直接指出「某实例占 68%」并给出引用链，80% 在这里结束。
   - Dominator Tree：按「回收它能释放多少内存」排序，**不是按对象大小**。因为真正的泄漏点往往是个很小的容器持有巨大的 value。
   - Path to GC Roots：**必须 exclude 弱/软引用**，否则会看到一堆无关的 `WeakReference` 干扰。

3. **三种最可能的模式，按概率排**：
   - `ThreadLocal` 没 remove（线程池线程永生 → 每个 Entry 都是永久泄漏，还会串数据）。
   - 静态 Map 缓存无上限 / 无过期。
   - 监听器、连接、流没关（注册了不注销）。

4. **从 dump 到代码行**：找到持有者对象 → 看它是哪个类的哪个字段 → 结合业务判断「这个对象本来该活多久」→ 找出「谁忘了清理」。
   典型答案形态：「`OrderService` 的静态 `Map<String, OrderContext>` 里堆了 120 万个 `OrderContext`，因为下单成功后没有 remove」。

5. **验证与修复**：本地用同样流量跑 3 小时复现曲线 → 改完后压测 24 小时看堆曲线是否**走平**（不是「涨得慢」，是要平）。

**满分线**：说出「dump 文件 4G，MAT 至少要 8G 内存才打得开」，以及「3 天必挂是累积型泄漏的典型时间特征」。

**常见坑**：只看 Histogram 里最大的对象（往往是 `byte[]` 或 `char[]`，那是结果不是原因）；忘了 exclude 弱引用，被 `ThreadLocalMap` 的 null key 搞晕。

---

### 题三：下游团队投诉「你们把我们打挂了」，但你确信自己 QPS 没涨。怎么自证并定位？

**考察点**：有没有「流量放大」的概念 + 会不会用数据切链路 + 是否理解重试与连接池的放大效应。

**参考答案骨架**

1. **先别急着否认，先算放大量。**
   出口 QPS 才是证据，不是入口 QPS。查三处：
   ```bash
   # 出口流量（真实打到下游的量）
   ss -tn state established '( dport = :8080 )' | wc -l
   # 重试次数
   grep -c "retry" /var/log/app.log
   ```
   常见放大源：**Feign/Ribbon 自动重试 × 网关重试 × 业务手动重试 = 3×3×3 = 27 倍**。
   入口 200 QPS → 出口 5400 QPS，你「没涨」，但下游被打爆了。

2. **再查连接层放大。**
   如果用了连接池 + 长连接，**连接数**可能比 QPS 更致命：短连接每次 TCP 握手 + TLS，SYN 队列打满看起来就像 DDoS。
   检查 `TIME_WAIT` 数量、`CLOSE_WAIT`（后者说明对端关了你不关，是 bug）。

3. **三个自证工具**：
   - 全链路 Trace：你的 span 时长 vs 下游 span 时长，**分别多少 ms**，一眼看出谁慢。
   - 出口 QPS 曲线 vs 入口 QPS 曲线：两条线重合 = 没放大；出口远高于入口 = 你放大了。
   - 下游错误码分布：如果是 `429/503`，是限流/过载；如果是 `connection reset`，是连接层。

4. **一种常见的「无辜但确实是你」**：
   慢请求导致超时 → 重试 → 重试请求又慢 → 更多重试。**你没有增加流量，但你的超时设置制造了流量。**
   修复：缩短超时（3s → 800ms）、限制重试次数（最多 1 次）、只重试幂等接口、加重试预算（retry budget）。

5. **收口**：给出「入口 QPS 200，出口 QPS 5400，放大 27 倍，原因是三层重试叠加」这样的数字结论，而不是「我们真的没干什么」。

**满分线**：主动区分「入口 QPS」与「出口 QPS」，并指出**超时时间过长本身就是一种流量放大器**（线程被占住 → 上游重试 → 更多线程被占住）。

**常见坑**：只拿入口监控截图自证；或者只解释「我们代码没改」，而不去算出口流量。

---

## 本期收口

1. **排查的第一原则是「先止血、再取证、最后根因」**，而取证必须只读——重启会销毁 `jstack` 里唯一的证据。
2. **CPU 100% 先看是不是 GC 线程**：是 → 转内存方向；不是 → 按栈定性（业务循环 / 正则 / 加解密 / 锁）。
3. **`RUNNABLE` 不等于在烧 CPU**：`socketRead0`、`epollWait` 也是 RUNNABLE，必须配合 `top -Hp` 的 `%CPU`。
4. **打两次 jstack 做 diff**，位置没变的那个线程就是卡住的地方——这比读 400 行栈快十倍。
5. **OOM 有八种，但线上最高频的是第九种**：容器 `OOMKilled`（exit 137），没有异常、没有 dump，只能靠 `MaxRAMPercentage` 留出堆外余量。
6. **MAT 的核心不是「找大对象」而是「找引用链」**：Dominator Tree 排序 + Path to GC Roots exclude 弱引用，引用链的终点就是那行代码。
7. **ThreadLocal 泄漏的本质是「key 弱、value 强 + 线程永生」**，而且它还会串数据，所以 `remove()` 是正确性要求而非好习惯。
8. **死锁唯一可行的工程解是破「循环等待」**：全局锁顺序，或 `tryLock` 带超时。
9. **线程池打满有三种真相**，盲目加大队列只是把「秒级拒绝」拖成「分钟级超时」，是让故障变得更糟。
10. **接口超时的分诊只有一个动作：同时看应用线程栈和数据库 `processlist`**。只 `getConnection` 堆着而 DB 闲着 → 连接没还（事务里调 HTTP）；`active` 高 + 慢日志有记录 → 慢 SQL。
11. **`@Transactional` 里调外部接口是雪崩的头号写法**：持连接 3 秒 × 200 QPS = 600 个连接需求，池 10 个直接死。
12. **重试是放大器不是修复器**：3 层 × 3 次 = 27 倍流量；超时时间太长本身就是流量放大器。

---

下期预告：**大厂场景设计题** —— 秒杀系统的四道闸门（页面静态化 / 库存预热 / Redis 原子扣减 / MQ 异步下单）、订单 30 分钟超时关闭的五种方案（扫表 / 延时消息 / Redis 过期 / 时间轮 / CDC）、分布式 ID 五种发号器（UUID / 雪花 / 号段 / Redis / DB）、短链与 Feed 流的推拉模型。前面把零件都拆完了，下一期开始组装成系统。
