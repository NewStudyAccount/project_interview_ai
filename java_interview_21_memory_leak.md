# Java 面试深水区 21 · 内存泄漏取证：支配树下的真凶

> 第 09 期讲的是「OOM 之后怎么救火」，这一期讲更难的那一半：**OOM 一直没来，内存却一直在涨**。一条 `jmap -histo` 看不出问题、MAT 打开后引用链最长的那条也不是元凶——因为「谁是元凶」在堆快照里不由引用链长短决定，而由**支配关系**决定。
>
> 本期把 Shallow / Retained / 支配树、四种引用强度、ThreadLocal 的七跳链、ClassLoader 的元空间阶梯、堆外四本账，全部拆成可算的数字与能直接跑的命令。

**目录**：L0 三种「涨」· L1 支配树 · L2 四种引用 · L3 取证四步 · L4 ThreadLocal · L5 ClassLoader · L6 四本账 · L7 RSS 拆账 · L8 五步法 · L9 工具矩阵 · L10 数字 · L11 问答 · L12 模拟题

---

## L0 · 面试官问「你遇到过内存泄漏吗」，九成人答错了一半

标准答案是「ThreadLocal 忘了 remove」。这句话对，但它只覆盖了**三种完全不同的故障里的一种**。线上真正的麻烦在于：这三种故障的**表象几乎一样**（内存曲线都在涨），但排查手段、修复成本、责任人都不是一回事。答错类型，后面全是白干。

### 三种「涨」，各自长什么样

| 类型 | 本质 | 典型代表 | dump 里看起来 |
|---|---|---|---|
| **真泄漏** | 对象本该被回收，但仍有强引用可达 | ThreadLocal 未 remove、监听器未注销、静态 Map 只 put 不 remove、ClassLoader 残留 | 某个业务类实例数单调增长，且**增量恒定** |
| **容量膨胀**（假泄漏） | 每个对象都可回收，但同时活着的太多了 | 无界本地缓存、Session 没设 TTL、批量查询一次拉 10 万行、MQ 积压 | 对象都「合法」，只是**数量是正常值的 100 倍** |
| **账外内存** | 堆完全正常，涨的是堆外 | DirectByteBuffer、Netty ByteBuf 未 release、Metaspace、CodeCache、glibc arena、线程栈 | dump 打开了也**什么都看不到**，因为东西不在堆里 |

判别只需要一条命令，而且是三条命令里最便宜的一条：

```bash
# 一行分出「堆内」还是「堆外」——不要一上来就 dump
jcmd 12345 GC.heap_info
#  garbage-first heap   total 8388608K, used 2146304K [0x0000000700000000, ...)
#   region size 8192K, 12 young (98304K), 1 survivors (8192K)
#  Metaspace       used 258760K, committed 264192K, reserved 1114112K
```

两次采样间隔 10 分钟，看 `used` 是否在涨。**堆在涨 → 前两种；堆不涨但容器 RSS 在涨 → 第三种。** 这个分叉做错，后面所有动作都是无效劳动。

> **为什么第三种最要命**：8G 堆 + 12G 容器 limit，堆外只要吃掉 4G，容器就被 cgroup 杀掉（`exit 137`）。此时 Java 层一条异常都没有：没有 OOM、没有 dump、日志停在最后一条正常请求上。运维看到「进程莫名重启」，开发看到「没有报错」，双方对不上账——所以「RSS 涨但堆不涨」必须当一级故障处理。

```
三条曲线，三种病

① 真泄漏            ② 容量膨胀            ③ 账外内存
堆                   堆                     RSS ────────╱
│      ╱              │      ╱╲    ╱╲       │        ╱
│    ╱                │    ╱  ╲  ╱  ╲       │      ╱
│  ╱                  │  ╱    ╲╱    ╲      │  Java堆 ───────
└──────── 24h         └──────── 24h          └──────── 24h
斜率恒定             谷底抬高               dump 里看不到
永不回落             回落不到原处            Java 堆是水平线
```

> 一句话记忆：**真泄漏看「斜率」、容量膨胀看「谷底」、账外内存看「RSS 减堆」**。三个观测量对应三种病，互不替代。

---

## L1 · Shallow、Retained、支配树：为什么最长的引用链不是元凶

几乎所有人都用过 MAT 的「Path to GC Roots」，并且九成人用它得出了错误结论——因为这个功能回答的问题和你以为的不一样。

### 三个指标，三种问法

| 指标 | 回答的问题 | 怎么算 |
|---|---|---|
| `Shallow Heap` | 这个对象自己占多少 | 对象头 12B + 各字段（压缩指针下引用 4B）+ 对齐到 8B 倍数 |
| `Retained Heap` | 把它干掉，能释放多少 | **它支配的**所有对象的 Shallow 之和 |
| `Path to GC Roots` | 有没有一条路能走到它 | 深度优先找到的**任意一条**路径 |

第三行是坑。**「有一条路径」不等于「这条路径是唯一路径」，更不等于「这条路径上的上一跳是元凶」。** 一个被 200 个线程同时引用的大对象，MAT 只会给你其中一条链，你顺着这条链去改代码，改完内存照涨。

### 支配关系：唯一正确的「谁害了谁」

定义很短，但要读两遍：**如果从 GC Roots 到对象 Y 的每一条路径都必须经过 X，那么 X 支配 Y。** 把「支配」画成树，就叫支配树（Dominator Tree）。

- 支配树的根是虚拟的「所有 GC Roots」。
- 一个节点在支配树里的**父节点**，就是「你把它干掉、这个节点才会被回收」的那个对象。
- 所以 `Retained(B)` = B 的子树里所有对象的 Shallow 之和。

```
对象图（真实引用）                支配树（谁真的害了谁）

 Root1   Root2                      GC Roots
   │  ╲  ╱  │                          │
   │   ╲╱   │                          A
   │   ╱╲   │                         ╱ ╲
   ▼  ▼  ▼  ▼                        ▼   ▼
      A  ──────┐                     B     C
     ╱ ╲       │                  (B 只支配自己)
    ▼   ▼      ▼
    B ──────▶ C               A 的 retained = A + B + C
                               B 的 retained = B 自己
 B→C 这条边在支配树里被剪掉了，
 因为 C 还有一条不经过 B 的路（A 直达）
```

**这就是「按 Retained 排序而不是按引用链找元凶」的全部含义。** 删掉 B，C 仍然能从 A 直达，一分内存都不会释放。真正该修的是 A（或 A 的两个 Root）。

> **实战里最常犯的三个误读**：
> ① 按 `Objects` 列排序（那是实例个数，一个 `char[]` 只有 1 个但可能 2GB）；
> ② 按 `Shallow Heap` 排序（只看谁自己大，看不到谁拖着 10 万个孩子）；
> ③ 顺着「Path to GC Roots」的第一条链去改代码（那条链可能来自 Tomcat 的 `TaskThread`，看着像框架的锅）。
> **正确姿势只有一个：按 Retained Heap 降序，逐个展开看支配子树。**

再补一个数字感：一个 `HashMap.Node` 在压缩指针下是 32B（头 12 + hash 4 + key 4 + value 4 + next 4 = 28，对齐到 32），100 万个条目光节点就 32MB，还不算 key/value 本体和一个必为 2 的幂的 `Node[]`（100 万 → 容量 1048576 → 数组本身 4MB）。

所以看到 `HashMap$Node` 排在 histo 第一页，别急着说「HashMap 泄漏了」——**HashMap 从来不泄漏，泄漏的是往里 put 的那个没上限的缓存**。这个区别决定了你是改配置还是改代码。

---

## L2 · 四种引用强度：弱引用不是用来「优化」，是用来「报警」的

面试里问「四种引用」，大多数人能背出「强、软、弱、虚」，然后卡住——因为背的是**定义**，不是**用途**。真实的用途只有两个：**弱引用当缓存的 key**，**虚引用当内存泄漏的探针**。而这两件事恰好是 JVM 自己都在用的。

```
强  Strong   只要可达，永不回收，宁可 OOM
             └ 普通赋值 · 99% 的泄漏都发生在这里

软  Soft     内存不足时才回收（OOM 前最后挣扎）
             └ 本意做缓存，实际被 LRU 缓存全面取代（不可控）

弱  Weak     下一次 GC 必定回收（只要没有强引用）
             └ ThreadLocalMap 的 key · WeakHashMap · 缓存 key

虚  Phantom  回收前后进队列，拿不到对象，只能收到通知
             └ DirectByteBuffer 的 Cleaner · Netty 泄漏检测 · 探针

┌─────────────────────────────────────────────┐
│ ReferenceQueue 是探针                        │
│ 对象被回收的那一刻，它的 Reference 会进队列。 │
│ 于是「回收」这件事变成一个你能 poll 的事件。  │
│ ① 队列空 = 对象都还活着                      │
│ ② 队列满而不清 = 你在漏                      │
│ ③ 唯一能「事后才知道」的 GC 可观测手段        │
└─────────────────────────────────────────────┘
```

注意软引用的处境很尴尬：它的回收时机由 JVM 的「内存压力」决定，而内存压力不是你能配的，所以在有 LRU/TTL 需求的场景里它被 Caffeine 完胜。**面试被问「软引用做缓存好不好」时，答「不可控，别用」比背定义高分。**

### ThreadLocalMap 的设计缺陷：key 弱了，value 没弱

这是理解所有 ThreadLocal 泄漏问题的唯一钥匙。JDK 的实现是：

```java
// java.lang.ThreadLocal.ThreadLocalMap.Entry（JDK 源码，一字未改）
static class Entry extends WeakReference<ThreadLocal<?>> {
    Object value;                                  // ← 强引用！
    Entry(ThreadLocal<?> k, Object v) {
        super(k);                                  // ← key 是弱引用
        value = v;
    }
}
```

设计者的本意是：ThreadLocal 对象本身没人用了（业务代码里那个 static 变量所在的类被卸载了），key 就该自动消失。这个想法只对了一半——key 确实变成了 `null`，但 **Entry 这个对象本身还牢牢躺在 table 数组里**，而 `value` 被 Entry 强引用着。

结果就是一个「key 为 null、value 有 1MB」的僵尸条目。它的释放时机只有一个：**下一次对这个 Map 做 get / set / remove 时，顺带扫到它并 expunge**。如果这个线程之后再也不用这个 ThreadLocal，那就永远不会有人扫它。

把这个机制用代码复现出来，是「自己会清账的缓存」——也就是 ThreadLocalMap 本该有的样子：

```java
// 用「弱引用 key + 引用队列」做一份会自动清账的缓存
static final class Key extends WeakReference<Object> {
    final int hash;
    Key(Object k, ReferenceQueue<Object> q) {
        super(k, q);
        this.hash = System.identityHashCode(k);
    }
}

private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
private final Map<Key, byte[]> map = new HashMap<>();

void put(Object k, byte[] v) {
    expunge();                       // 关键：每次写入先清一遍僵尸条目
    map.put(new Key(k, queue), v);
}

private void expunge() {
    Reference<?> r;
    while ((r = queue.poll()) != null) {
        map.remove(r);               // 不 remove，value 就还是强引用，等于没做
    }
}
```

这段代码解释了三个常被追问的点：

1. **为什么清理要「顺带」而不是「实时」？** 因为 WeakReference 只能告诉你「没了」，不能主动帮你清理。要实时清理就得有一个后台线程轮询队列——ThreadLocalMap 为了零线程成本，选择了顺带清理，代价就是「不调用就永不清理」。
2. **为什么 `map.remove(r)` 这一句是必须的？** 因为 `r` 这个 Reference 对象本身还在 Map 里当 key，value 被它强引用。**这行漏了，弱引用缓存就变成了纯泄漏。**
3. **为什么 `WeakHashMap` 也有同样的问题？** 它内部就是这么实现的，所以它同样只在 get/set 时清理——一个只用一次就再也不碰的 WeakHashMap，**泄漏起来和普通 HashMap 一模一样**。

> **反直觉结论**：「用了弱引用就不会泄漏」是错的。弱引用只保证 key 不会拖住对象，不保证 Map 自己会瘦身。凡是「用弱引用做 key 的容器」，都必须回答「谁在什么时候清理死条目」这个问题；答不上来，它就是一个包装得更漂亮的泄漏源。

---

## L3 · 取证四步：先定性，再定量，最后才 dump

绝大多数人第一步就做错：直接 `jmap -dump` 一个 8G 的堆。后果是——**STW 30~60 秒**、生成 8~12G 文件、MAT 打开要 20G 以上堆、分析三天，最后发现是个无界缓存。

正确顺序是「先便宜的后贵的，先定性的后定量的」，四步，成本递增：

```
 ①基线            ②时序三次          ③判性质           ④dump 支配树
 ●────────────────●─────────────────●─────────────────●
 GC.heap_info     class_histogram    增量是否恒定       dump:live
 <1s 无 STW       每次 2~5s          真泄漏/容量/账外    STW 30~60s
 间隔 2 小时       只看增量           三种分流           够 2G 就 dump

 ┌────────────────────────────────────────────────────────────┐
 │ 第 5 步（现代做法，可跳过 dump）：                          │
 │ JFR 的 OldObjectSample 直接给出「老年代活对象的分配栈」，   │
 │ 开销 <1%，可以生产常开                                      │
 └────────────────────────────────────────────────────────────┘
```

第 4 步的 `dump:live` 会先做一次 Full GC 保证只包含活对象——这个「保证」的代价是 STW，所以**它必须在堆还没满的时候做**：2G 的堆 dump 只要 5~10 秒，8G 的堆要 30~60 秒，而堆满时 dump 出来的图还被大量临时对象污染。

### 一条能直接粘到生产机的命令序列

```bash
PID=12345
mkdir -p /tmp/leak && cd /tmp/leak

for i in 1 2 3; do
  echo "=== round $i  $(date '+%F %T') ===" >> info_$i.txt
  jcmd $PID GC.heap_info >> info_$i.txt            # 堆 used / Metaspace
  jmap -histo $PID | head -60 >> info_$i.txt        # 不触发 GC，先看粗账
  jmap -histo:live $PID | head -60 >> live_$i.txt   # 触发 GC，只看活对象
  sleep 7200                                        # 2 小时
done
```

更省事的是直接用 awk 做差——**只看「增量」，不看绝对值**，因为绝对值里混着 JVM 自己的常驻对象：

```bash
# 把 "  12:   12400   397120  com.foo.Session" 变成 "com.foo.Session 12400"
norm() { awk '/^[ ]*[0-9]+:/ {print $NF, $3}' "$1" | sort > /tmp/leak/$2; }
norm live_1.txt c1
norm live_3.txt c3

# 按增量降序：负数就是有回收，正数才是嫌疑
join -a1 -a2 -e0 -o 0,1.2,2.2 /tmp/leak/c1 /tmp/leak/c3 \
  | awk '{d=$3-$2; if (d>1000) printf "%+8d  %10d  %s\n", d, $3, $1}' | sort -rn | head -20
```

输出长这样，一眼定性：

```
  +4700       21800  com.foo.Session            ← 增量恒定 → 真泄漏
  +1200       98000  java.lang.String           ← 跟着 Session 涨，是它的字段
   +30         1200  com.foo.CacheEntry         ← 有涨但总数很小 → 不是主因
  -8200         900  java.util.HashMap$Node     ← 负数 = 正常回收，别管它
```

**判据表（这就是第 3 步的全部内容）：**

- ① 某个业务类实例数**单调增长、每次增量近似恒定** → 真泄漏，增量 ÷ 间隔 = 每秒泄漏对象数 × 单对象大小 = 泄漏速率；
- ② 实例数**涨到某个值就不再涨**，或与在线用户数/QPS 同步 → 容量膨胀，去查缓存上限与批量大小；
- ③ 堆里**所有类都平稳**，但 RSS 在涨 → 账外，直接跳到 L6。

### 把「泄漏速率」算成「几天后爆炸」

这一步是面试的加分项，也是判断紧急程度的唯一依据。上面那组数字：

- 每 2 小时 +4700 个 `Session` → **2350 个/小时**；
- 每个 `Session`（含 3 个 String、1 个 `long[]`、1 个权限 Set）**约 1.2KB**；
- 泄漏速率 = 2350 × 1.2KB = **2.82 MB/小时 ≈ 66 MB/天**；
- 8G 堆，运行基线占 2G，可用 6G = 6144MB → **6144 ÷ 66 ≈ 93 天**。

**「93 天」这个数字，比任何定性描述都有用。** 它说明三件事：① 这不是紧急故障，可以按版本节奏修；② 但它是**必然**故障，不修就是三个月后重演；③ 灰度/扩容都会掩盖它（堆越大，天数越长），所以「上了大堆就没再出现」是自欺欺人。

```
堆占用
 8G ┤ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ Xmx 8G → OOM
    │                                        ╱
 6G ┤                                   ●╱  第 60 天
    │                              ╱
 4G ┤                        ●╱  第 30 天（只表现为 YGC 变频繁）
    │                  ╱
 2G ┤●──────────────╱
    └──┬──────┬──────┬──────┬──────┬──────┬──
      0     15     30     45     60     93 天
```

缓慢泄漏的全部杀伤力在于「斜率太小」：93 天里它一直伪装成「业务在增长」，直到某天 YGC 从 20 分钟一次变成 20 秒一次，然后 Full GC 连发、接口超时、容器重启。**能在第 2 天用一个 5 秒的命令看清的斜率，不要留到第 93 天用 60 秒的 STW 去换。**

---

## L4 · ThreadLocal：一条七跳引用链，和 200MB 的算术

「ThreadLocal 会内存泄漏」这句话人人会说，但面试官接着问「key 不是弱引用吗，为什么还会漏」，能答完整的不超过三成。答案就藏在一条七跳的链上。

```
1. Thread（Tomcat worker）        生命周期 = 应用生命周期
        │
        ▼
2. Thread.threadLocals            类型 ThreadLocalMap
        │
        ▼
3. table：Entry[]                 长度 16 起，2 的幂，装载因子 2/3
        │
        ▼
4. Entry extends WeakReference    key 是弱引用 ⇒ 可以被 GC 置 null
        │
        ▼
5. Entry.value（强引用）          key 死了它也不死 —— 泄漏就在这一跳
        │
        ▼
6. 业务对象 / byte[] 1MB          本该随请求结束就回收

┌──────────────────────────────────────────────────────────┐
│ 为什么「key 是弱引用」救不了它                            │
│ · key 弱引用：ThreadLocal 变量没人用了，下次 GC 置 null    │
│   —— 这一步确实生效了。                                   │
│ · 但 Entry 对象自己还躺在 table 数组里，它的 value 字段    │
│   是强引用，于是 value 依然可达。                          │
│ · 唯一的清理时机：get/set/remove 时顺带 expungeStaleEntry  │
│   不再调用 = 永不清理。                                    │
│                                                          │
│ 泄漏量 = 存活线程数 × 每个 value 大小                      │
│ 200 线程 × 1MB = 200MB；漏 5 个地方 = 1GB                 │
└──────────────────────────────────────────────────────────┘
```

注意第 3 跳的细节：`Entry[]` 的长度是 2 的幂、装载因子是 2/3——这意味着即使只有 1 个漏掉的 ThreadLocal，**整个数组（16 个槽 = 16 × 16B 引用 + 头）也要跟着活**。所以泄漏的从来不只是那 1MB。

### 事故代码长什么样

```java
// 每天都在写的写法，异常路径上必漏
public class UserContextFilter implements Filter {

    private static final ThreadLocal<UserContext> CTX = new ThreadLocal<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        UserContext ctx = loadUser(req);        // 假设 1MB（含权限、组织树快照）
        CTX.set(ctx);
        chain.doFilter(req, res);               // ← 这里一旦抛异常……
        CTX.remove();                           // ← ……这行就永远不执行
    }
}
```

在「一个请求一个线程」的年代，这个 bug 的后果是**一次请求漏 1MB，线程死了就回收**，几乎无害。在 Tomcat 的 200 个 worker 线程下，后果是**1MB 永久驻留**——因为线程不死，`threadLocals` 就不死，Entry 就不死。

正确写法只有一种，且必须用 `try/finally`：

```java
@Override
public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
        throws IOException, ServletException {
    UserContext ctx = loadUser(req);
    CTX.set(ctx);
    try {
        chain.doFilter(req, res);
    } finally {
        CTX.remove();     // 不是 set(null)：set(null) 只清值，Entry 槽位还在
    }
}
```

> **三个必须知道的细节**：
> ① `set(null)` 不等于 `remove()`。前者把 value 置 null 但保留 Entry（槽位仍被占，key 的弱引用清理逻辑也不会主动跑）；后者把整个 Entry 删掉并把槽位设为可复用状态。
> ② 拦截器/AOP/异步线程里都要成对写。跨线程传递要用 `InheritableThreadLocal`（只在线程**创建时**拷贝一次，线程池里无效）或 `TransmittableThreadLocal`。
> ③ **虚拟线程时代这个 bug 会自己变小**：ThreadLocal 存在虚拟线程的 StackChunk 里，虚拟线程结束即释放，天然不泄漏（第 13 期讲过）。但注意——别把大对象放进 carrier 线程的 ThreadLocal，那是 ForkJoinPool 的线程，又回到「永不结束」的老问题上。

```
同一个 bug，三种线程模型下的净泄漏（每个 value 1MB）

1 个漏掉的 ThreadLocal × 200 线程  ████████ 200 MB
再加 1 个漏掉的 UserContext       ████████████████ 400 MB
一个中型项目里常见的 5 处          ████████████████████████████████████ 1 GB
                                  └──┬──────┬──────┬──────┬──────┬──┘
                                     0     250    500    750   1000 MB

注意：这 1GB 不会触发 OOM，它只是让每次 YGC 的存活率上升、晋升变快
—— 表现为「GC 变慢」，不是「内存不足」。
```

> **面试怎么讲才算过关（三层递进）**：
> 第一层说现象：ThreadLocal 不 remove 会泄漏。
> 第二层说机制：key 弱、value 强，Entry 留在 table 里，只有 get/set/remove 才顺带清理。
> 第三层说边界：单请求线程模型下无害，线程池下才致命；虚拟线程又把它变回无害；跨线程传递是另一个问题（InheritableThreadLocal 只在线程创建时拷贝，线程池失效）。**能讲到第三层，这个问题就结束了。**

---

## L5 · ClassLoader 泄漏：热部署 14 次，元空间就满了

这类泄漏在业务代码里几乎见不到，但在**容器、插件化、热部署、字节码增强**这四类场景里非常典型。而且它有一个其他泄漏都没有的特征：**泄漏的不是堆，是元空间**——所以 `jmap -histo` 看着一切正常，堆也很健康，只有 Metaspace 在阶梯式上涨。

### 为什么一个 loader 挂住 = 几万个 Class 挂住

类卸载的条件有三个，缺一不可：**① 该类的所有实例都不可达；② 该类的 `Class` 对象不可达；③ 加载它的 ClassLoader 不可达。**

条件 ③ 是死穴。一个 `WebappClassLoader` 加载了 5000 个类，每个类的元数据（方法字节码、常量池、字段描述符）平均 5~6KB，加上静态字段引用的对象——**只要 loader 自己被任何一个强引用挂住，这 5000 个类一个都卸载不掉**。

```
Metaspace                     三个最常见的「挂钩」
512M ┤ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  MaxMetaspaceSize
     │                    ┌─┐   ┌──────────────────────────┐
360M ┤              ┌─┬─┬─┘ │   │ ① 自己 new 的线程池/定时器 │
     │        ┌─┬─┬─┘       │   │   核心线程是 GC Root，     │
240M ┤    ┌─┬─┘             │   │   线程的 contextClassLoader│
     │  ┌─┘                 │   │   指向旧 loader            │
120M ┤──┘                   │   ├──────────────────────────┤
     └──┬──┬──┬──┬──┬──┬──┬─┘   │ ② JVM 级注册表             │
        1  3  5  7  9 11 13     │   DriverManager / Logger / │
        每次 +30MB，从不回落     │   JMX / shutdown hook      │
                               ├──────────────────────────┤
                               │ ③ ThreadLocal              │
                               │   应用重启了，线程没重启   │
                               └──────────────────────────┘

卸载三条件：实例 / Class / ClassLoader 三者全部不可达才卸载，
任一被钉住即残留。
```

为什么是「阶梯」而不是「斜坡」：每次 redeploy 新增一个 loader（+30MB），旧的**一次都不会回收**，所以图形是台阶式上升——**「阶梯」是引用残留型泄漏的图形指纹，看到它就该去查 loader，而不是查缓存。**

### 自己在本地复现一次（面试讲这个比背定义强十倍）

```java
public class LoaderLeakDemo {
    // 关键点：这个 Timer 的线程是「应用级」的，redeploy 时不会停
    static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor();

    static {
        // 陷阱：静态块里注册到 JVM 级注册表
        TIMER.scheduleAtFixedRate(() -> {
            // 这里只要引用到本应用的任何一个类，loader 就被钉住了
            System.out.println(Config.INSTANCE.getName());
        }, 0, 1, TimeUnit.SECONDS);
    }
}
```

复现步骤（Tomcat 里最直观）：把上面这段打进 WAR，用 `-XX:MaxMetaspaceSize=128m` 起 Tomcat，然后连续 redeploy。你会看到：

- `jcmd <pid> VM.metaspace` 里 `used` 每次 +30MB 左右，且**从不回落**；
- 第 5 次左右开始报 `java.lang.OutOfMemoryError: Metaspace`；
- heap dump 里 `WebappClassLoader` 有 5 个实例——**正常应该只有 1 个**。

最后那条是最快的判据：**dump 里同一个 ClassLoader 类出现多个实例，就是没卸干净。** 不需要看引用链，数量本身就是证据。

```bash
# 更精确的判据：直接看每个 loader 挂着多少个类
jcmd 12345 VM.classloader_stats
# ClassLoader         Parent              CLD*         Classes   ChunkSz
# 0x0000...Webapp    0x0000...App      0x0000...      5120      31.2M   ← 旧应用残留
# 0x0000...App       0x0000...Plat     0x0000...       840       4.1M
# 0x0000...Plat      null              0x0000...      1200       6.8M
```

> **标准修法（四条，缺一不可）**：
> ① 所有 `ExecutorService` 在 `contextDestroyed()` 里 `shutdownNow()` 并 `awaitTermination`；
> ② 所有 `ThreadLocal` 在线程归还前 `remove()`，或用 `contextDestroyed` 统一清；
> ③ 所有注册到 JVM 级的位置（`DriverManager`、`Logger`、`JMX`、`Runtime.addShutdownHook`）都要成对注销；
> ④ `MaxMetaspaceSize` 必须显式设置（默认无限），把它当成「泄漏的保险丝」而不是「容量参数」。

---

## L6 · 账外内存四本账：dump 里看不见的那 4G

这是「内存泄漏」里最容易被误判的一类，因为**所有堆内工具都告诉你一切正常**：dump 干净、histo 平稳、GC 日志漂亮。可是 `ps` 里的 RSS 一直在涨，最后容器 `exit 137`。

四本账，各有各的记账人，各有各的取证命令：

| 账目 | 怎么分配 | 谁负责释放 | 典型症状 |
|---|---|---|---|
| **DirectByteBuffer**（堆外直接内存） | `ByteBuffer.allocateDirect(n)`，受 `-XX:MaxDirectMemorySize` 限制（**默认 = 最大堆**） | 一个虚引用 `Cleaner`，**依赖 GC 触发** | 堆很空却报 `OutOfMemoryError: Direct buffer memory`；或容器 137 而 Java 无异常 |
| **Netty ByteBuf**（池化的直接内存） | `PooledByteBufAllocator` 按 chunk 预分配（默认 16MB） | **你手动 `release()`**，引用计数归零才归还池 | 跑几天才涨；`LEAK: ByteBuf.release() was not called` |
| **Metaspace**（类元数据） | 类加载时分配，**默认无上限** | 类卸载（见 L5 三条件） | 阶梯上涨，`OutOfMemoryError: Metaspace` |
| **glibc arena**（分配器碎片） | JVM 通过 `malloc` 申请本地内存，glibc 按 arena 管理 | **基本不还**，只在自己的 arena 内复用 | RSS 比堆大 1~2G，NMT 里记在 Other/Internal |

### 第一本账：Direct，为什么「堆越空，堆外越容易炸」

这个反直觉的因果链值得单独讲，因为它把两个看似无关的机制串起来了：

1. `allocateDirect` 时，堆内只创建一个很小的 `DirectByteBuffer` 对象（约 100B），真正的 1MB 在堆外。
2. 释放堆外内存的是挂在它上面的 `Cleaner`（一个虚引用）。**虚引用要被触发，前提是这个堆内对象被 GC 回收。**
3. 于是：**堆内压力小 → YGC 少 → 小对象迟迟不被回收 → Cleaner 不跑 → 堆外只进不出。**

所以「`-Xmx8g` 而 `MaxDirectMemorySize` 默认也是 8g」是一件很恐怖的事：堆外可以吃到 8G，容器 limit 只有 12G，**你会在堆用到 4G 的时候被 cgroup 杀掉**，而且什么异常都看不到。

> **结论性配置（可直接抄）**：
> ① 显式设 `-XX:MaxDirectMemorySize=512m`（按业务实测 × 2），让它**先于容器报 Java OOM**，至少留个 dump 和异常栈；
> ② 大量小 buffer 的场景加 `-Djdk.nio.maxCachedBufferSize=262144`，避免 ThreadLocal 缓存里攒一堆 1MB 的临时 buffer；
> ③ 一定要让「Java OOM」先于「容器 137」发生——**OOM 可查，137 不可查**，这是可观测性的最基本原则。

### 第二本账：Netty 的引用计数，漏一次就是 16MB

Netty 不用 Cleaner，它自己管引用计数。原因很实际：一条连接上每秒几千次分配，靠 GC 触发释放太慢也太随机。

```
refCnt 正常轨迹                    泄漏轨迹
 2 ┤      ┌──┐                    2 ┤      ┌──┐
 1 ┤──┐   │  └──┐                 1 ┤──┐   │  └──┐ ─ ─ ─ ─ ─ 卡在 1
 0 ┤  └───┘     └──● 归还池        0 ┤  └───┘     └──╳ 永不归还
   └──┬────┬────┬────┬──            └──┬────┬────┬────┬──
    retain release retain            每漏一次 = 一个 chunk
                                        （默认 16MB）长期不释放

ResourceLeakDetector 四档：
DISABLED   不检测，性能最好（生产默认）
SIMPLE     约 1% 采样，只报「有没有漏」
ADVANCED   约 1% 采样，附最近访问记录
PARANOID   100% 采样，只用于排障，别常开
```

```java
// 三种必然泄漏的写法（都是「没有 release」的变体）
// ① 异常路径跳过 release
ByteBuf buf = ctx.alloc().buffer();
buf.writeBytes(data);
channel.writeAndFlush(buf);      // 若前面抛了异常，这个 buf 就漏了

// ② 只 release 了一半（CompositeByteBuf 的子 buffer）
CompositeByteBuf all = ctx.alloc().compositeBuffer();
all.addComponent(true, head);
all.addComponent(true, body);    // body 的 refCnt 被 +1，只 release all 是不够的

// ③ SimpleChannelInboundHandler 用错基类
// 继承 ChannelInboundHandlerAdapter 就必须自己 release；
// 继承 SimpleChannelInboundHandler 它帮你 release —— 两者混用必漏。
```

**排障姿势**：把 `-Dio.netty.leakDetection.level=paranoid` 加到启动参数，复现 1~2 小时（**不要常开，100% 采样会拖慢 20%~30%**），日志里会出现：

```
LEAK: ByteBuf.release() was not called before it's garbage-collected.
Recent access records:
  #1: io.netty.buffer.AdvancedLeakAwareByteBuf.writeBytes(...)
      io.netty.handler.codec.http.HttpObjectEncoder.encode(...)
Created at:
      io.netty.buffer.PooledByteBufAllocator.newDirectBuffer(...)
```

这个栈里最关键的是 **Created at**——它直接告诉你哪个 handler 分配的。**这就是「分配栈」相对「引用链」的优势：引用链告诉你是谁抓着它，分配栈告诉你是谁生的它。** 查泄漏时，后者往往更接近答案。

---

## L7 · RSS 拆账：把 12G 拆成能对账的六段

面试官问「容器里堆该设多大」，标准答案是「limit 的 60%~70%」。但这个答案只有在你**能说出剩下 30%~40% 花在哪**的时候才成立，否则就是背了个数。

下面是一个真实的 12G 容器、`-Xmx8g` 的账单：

```
RSS = 12.0 GB                             容器 limit 12G，没有余量

┌──────────────────────────────────┬───┬─┬─────┬──────┬──────┐
│  Java 堆 committed  8.0G  (67%)  │Me │C│  Di │glibc │ 其他 │
│                                  │0.5│0│ 1.2G│ 1.0G │ 1.04G│
└──────────────────────────────────┴───┴─┴─────┴──────┴──────┘
 └──────────── 只有这一段能 dump 到 ────────────┘
               剩下 4G 全在账外

图例：Me=Metaspace 0.50G   C=CodeCache 0.24G   Di=Direct 1.20G
      glibc arena/碎片 1.00G   线程栈+JVM 自身+其他 1.04G

拆账命令（必须提前开，重启才生效）：
  -XX:NativeMemoryTracking=summary
  jcmd <pid> VM.native_memory summary
  开销约 5%~10% 内存与少量 CPU，只在对账期开，事后关掉
```

12G 里只有 8G 是你能用 dump 看到的。两张最大的「隐形账单」是 Direct（1.2G）和 glibc 碎片（1.0G）——**前者是你分配的，后者是分配器留下的**。前者靠改代码或配 `MaxDirectMemorySize`，后者靠 `MALLOC_ARENA_MAX=2` 抑制。

| 账目 | 取值 | 怎么估 | 怎么压 |
|---|---|---|---|
| Java 堆 committed | 8.0G | `-Xmx`，注意是 **committed** 不是 used | 按 limit 的 60% 反推 |
| Metaspace | 0.50G | `jcmd VM.metaspace`，看 committed | 设 `MaxMetaspaceSize` 当保险丝 |
| CodeCache | 0.24G | JIT 编译的代码，**默认预留 240M** | 基本不用管，除非疯狂生成字节码 |
| Direct | 1.20G | `BufferPoolMXBean` 的 `memoryUsed` | `MaxDirectMemorySize` + 查 ByteBuf 泄漏 |
| 线程栈 | ~0.04G | `-Xss` × 线程数（虚拟地址），RSS 里只算真实触碰的页 | 控线程数，别每请求 new Thread |
| glibc arena | 1.00G | NMT 归到 Other，RSS 减各项 | `MALLOC_ARENA_MAX=2` |

实时读 Direct 用量最准的方式不是猜，是直接问 JVM：

```java
// 直接读 MXBean，不用重启、不用开 NMT
for (BufferPoolMXBean p : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
    System.out.printf("%-12s count=%-8d used=%-12d capacity=%d%n",
            p.getName(), p.getCount(), p.getMemoryUsed(), p.getTotalCapacity());
}
// 输出：direct       count=18342    used=1288490188    capacity=1342177280
//      mapped       count=4        used=8388608       capacity=8388608
```

> **容器里的三个必设参数（缺一个都可能 137）**：
> ① `-XX:MaxRAMPercentage=60`（**不要用 -Xmx 写死**，否则改 limit 不生效，第 12 期讲过）；
> ② `-XX:MaxDirectMemorySize=512m`（否则默认跟着堆走）；
> ③ `MALLOC_ARENA_MAX=2`（环境变量，不是 JVM 参数）。
> 三条一起用，才能保证「**Java 先 OOM，容器后杀**」——这个顺序决定了你事后有没有东西可查。

---

## L8 · 五步法：从「RSS 在涨」到「改哪一行代码」

排查内存问题的最大浪费不是工具不好，而是**顺序错了**：一上来就 dump，或者在堆外的病历上翻堆内的检查。下面这张决策树是本期全部内容的收束——每一步都是一个「几秒钟能做完、且能砍掉一半可能性」的动作。

```
              ┌──────────────────────────┐
              │ 第 1 步：RSS 单调上涨？   │
              └───────────┬──────────────┘
            否 │                       │ 是
     ┌─────────▼────────┐    ┌─────────▼─────────────────┐
     │ 正常波动，不是泄漏│    │ 第 2 步：堆 committed 也涨？│
     └──────────────────┘    └──────┬──────────────┬─────┘
                              是 │                │ 否
                   ┌─────────────▼──┐   ┌─────────▼─────────┐
                   │ 堆内问题        │   │ 账外内存（L6）     │
                   └───┬────────┬───┘   └─────────┬─────────┘
          实例单调↑ │        │ 涨到平台          │
        ┌───────────▼──┐  ┌──▼──────────┐  ┌─────▼──────────────┐
        │ 真泄漏        │  │ 容量膨胀     │  │ NMT 拆账：          │
        └───┬──────────┘  └──┬──────────┘  │ Meta/Direct/Other  │
            │                │             └─────┬──────────────┘
   ┌────────▼─────────┐ ┌────▼─────────┐  ┌──────▼─────────────┐
   │ dump 趁早(2G<8G) │ │ 查上限：缓存 │  │ MaxDirectMemorySize│
   │ + 支配树 Retained│ │ /TTL/批量    │  │ MALLOC_ARENA_MAX=2 │
   │   降序展开        │ │ 不是引用问题 │  │ MaxMetaspaceSize   │
   └──────────────────┘ └──────────────┘  │ ByteBuf refCnt     │
                                          └────────────────────┘
```

最值得注意的是左侧「否 → 正常波动」这条出口：**大量「内存泄漏」报案实际是 GC 抖动或流量上涨**，先做掉这条分支能省掉八成的无效 dump。

把它压成一张可以直接贴到故障群里的表：

| 症状 | 最先做的三件事 | 成本 |
|---|---|---|
| RSS 涨、堆不涨 | `NMT summary` → 看 Metaspace/Thread/Other 哪项涨 → `BufferPoolMXBean` 读 Direct | 低，需提前开 NMT |
| 堆涨、YGC 变频繁 | `GC.class_histogram` 三次做差 → 圈定类 → dump + 支配树 | 中，dump 有 STW |
| OOM 已经发生 | 拿 `HeapDumpOnOutOfMemoryError` 落下的文件 → 按 Retained 降序 → 只展开前三棵子树 | 高，但证据最全 |
| Metaspace 阶梯涨 | `VM.classloader_stats` 看 loader 个数 → `VM.metaspace` 看 class 数 | 低 |

---

## L9 · 工具矩阵：哪个能常开，哪个只能救火

| 工具 | STW | 看到什么 | 生产可用性 |
|---|---|---|---|
| `jcmd GC.heap_info` | 无 | 堆 used / committed、Metaspace | **可常开**，小于 1s |
| `jmap -histo` | 无 | 类实例数（含垃圾对象） | 可随时跑，但含垃圾会误导 |
| `jmap -histo:live` | **有**（先 Full GC） | 只含活对象的类直方图 | 低峰期，2~5s |
| `jmap -dump:live` | **有**，30~60s（8G） | 完整对象图 | 救火用，**越早越好** |
| MAT | 无（离线） | Retained、支配树、引用链 | 需 1.5× dump 大小的堆来打开 |
| **JFR OldObjectSample** | 无 | **老年代活对象的分配栈** | **可常开**，开销小于 1% |
| NMT | 无 | 本地内存分项明细 | 可开，开销 5%~10% |
| async-profiler `-e alloc` | 无（采样） | 分配热点（含堆外） | 短时开，最适合「谁在疯狂分配」 |
| arthas `vmtool` | 短暂 | 直接按类名取实例、看字段值 | 可在线用，**最适合验证猜想** |

### JFR 的 OldObjectSample：这一段是这一期最值钱的技术点

传统流程是「dump 8G → 拷回来 → MAT 打开 → 找支配树」，一套下来半小时起，还要停机。而 JFR 的 `OldObjectSample` 事件解决的是**同一个问题的一半，但成本差两个数量级**：它按自适应采样保留少量「老年代存活对象」，每条样本都带着**它被分配时的完整调用栈**。

```bash
# 1. 启动时挂上去（也可以在运行中用 jcmd 动态开）
java -XX:StartFlightRecording=filename=/tmp/app.jfr,settings=profile,dumponexit=true ...

# 2. 让它跑够一个业务周期（至少一次完整的流量高峰）
sleep 3600

# 3. 直接打老年代活对象的分配栈，不用 dump 8G
jfr print --events jdk.OldObjectSample /tmp/app.jfr | head -120

# 4. JDK 17+ 还能直接看聚合视图
jfr view --width 160 --truncate 120 allocation-by-site /tmp/app.jfr
```

输出里每一段长这样，重点看 `objectAge` 与栈顶：

```
jdk.OldObjectSample {
  startTime = 10:24:31.882
  object =  com.foo.Session  (0x7f3a2c10)
  allocationTime = 2026-11-11T08:11:02.114
  objectAge = 8789 s              // ← 已经活了 2.4 小时，还没被回收
  lastKnownHeapUsage = 3.2 GB
  stackTrace = [
    com.foo.cache.SessionCache.put(SessionCache.java:78)
    com.foo.auth.LoginService.login(LoginService.java:132)
    ...
  ]
}
```

> **为什么这个栈比 MAT 的引用链更有用**：MAT 回答「谁抓着它」，JFR 回答「谁生了它」。而缓存/泄漏类问题的答案几乎总是「谁生了它」——因为「谁抓着它」往往是 `ConcurrentHashMap` 的 table，答了等于没答。**先看分配栈定位到代码行，再用 MAT 确认支配关系，是当前最快的组合。**

### arthas 的用法：验证猜想，而不是大海捞针

```bash
# 直接按类名取实例，看字段值 —— 不用 dump，不用重启
vmtool --action getInstances --className com.foo.cache.SessionCache --limit 1 \
       --express 'instances[0].map.size()'

# 如果返回 1200000，说明缓存根本没上限，问题当场结束
# 对比一下配置的预期上限（比如 10000），差了 120 倍
```

这个命令的价值在于**把「怀疑」变成「数字」只要 3 秒**。凡是「怀疑某个静态集合在无限增长」的场合，它都比 dump 快一百倍。

---

## L10 · 20 个数字速查

| # | 数字 | 含义 |
|---|---|---|
| 1 | 12B / 4B / 8B | 压缩指针下对象头 12B、引用 4B、对齐到 8B 倍数 |
| 2 | 32B | 一个 `HashMap$Node` 的大小（100 万条 = 32MB） |
| 3 | 2/3 | ThreadLocalMap 的装载因子（比 HashMap 的 0.75 更高，为了省空间） |
| 4 | 16 | ThreadLocalMap 初始 table 长度 |
| 5 | 1MB | 一个典型 UserContext（含权限+组织树快照）的大小量级 |
| 6 | 200MB | 1 个漏掉的 1MB ThreadLocal × 200 个 worker 线程 |
| 7 | 30MB | 一次热部署残留的 Metaspace（5000 个类 × 6KB 元数据 + 静态字段） |
| 8 | 512M | 推荐的 `MaxMetaspaceSize`（≈ 14 次 redeploy 的保险丝） |
| 9 | 240M | CodeCache 默认预留大小 |
| 10 | 16MB | Netty PooledByteBufAllocator 的默认 chunk 大小 |
| 11 | 1% | Netty 泄漏检测 SIMPLE / ADVANCED 的采样率（PARANOID 为 100%） |
| 12 | 512m | 推荐的 `MaxDirectMemorySize`（**默认等于 -Xmx，很危险**） |
| 13 | 0.6 | 容器里堆占 limit 的比例上限（`MaxRAMPercentage=60`） |
| 14 | 30~60s | 8G 堆做一次 `dump:live` 的 STW 时间 |
| 15 | 1.5× | MAT 打开 dump 需要的堆大小（8G dump 要 12G+） |
| 16 | 2.82MB/h | 一个真实泄漏案例的速率（2350 个 × 1.2KB / 小时） |
| 17 | 93 天 | 上面那个速率撑满 8G 堆所需时间（6144MB ÷ 66MB/天） |
| 18 | 137 | 容器被 cgroup 杀掉的 exit code（无异常、无 dump） |
| 19 | 小于 1% | JFR OldObjectSample 的开销，可以生产常开 |
| 20 | 5%~10% | NMT summary 的开销，只在对账期开 |

```
四类泄漏的「锚点」在哪：一张图看清为什么断不掉

┌──────────────────────────────┐ ┌──────────────────────────────┐
│ ① ThreadLocal 未 remove      │ │ ② ClassLoader 残留            │
│ 锚点：Tomcat worker 线程      │ │ 锚点：JVM 级注册表 /           │
│       （永不死）              │ │       自己 new 的线程池        │
│ 断不掉：Entry.value 是强引用， │ │ 断不掉：loader 不可达是类卸载   │
│   只清 key（弱），不清 value   │ │   的必要条件，挂住 loader =    │
│ 修法：finally 里 remove()     │ │   挂住 5000 个类               │
│ 判据：value 类型单调增长       │ │ 修法：成套注销 + MaxMetaspace  │
│                              │ │ 判据：dump 里 loader 多个实例   │
├──────────────────────────────┤ ├──────────────────────────────┤
│ ③ DirectByteBuffer / ByteBuf │ │ ④ 监听器 / 回调未注销          │
│ 锚点：堆外 native 内存，       │ │ 锚点：被注册到的「全局单例」    │
│   堆内只有 100B               │ │ 断不掉：注册表只加不减，        │
│ 断不掉：Cleaner 要等 GC 才跑， │ │   每条回调拖着调用方的大对象    │
│   堆越空越不 GC → 堆外先炸     │ │ 修法：成对 register/unregister │
│ 修法：MaxDirectMemorySize     │ │ 判据：回调列表 size 单调增长    │
│   + 配对 release              │ │                              │
└──────────────────────────────┘ └──────────────────────────────┘
```

**所有真泄漏都是「有一个比泄漏体活得更久的东西，握着一条强引用」。** 所以排查永远先问一句：**谁比它活得久？**——是线程、是 loader、是全局注册表、还是容器里的静态 Map。锚点找到了，代码行就在锚点旁边。

---

## L11 · 高频问答：从「定义」问到「你凭什么这么说」

**Q1：线上报警内存使用率高，你怎么判断是不是泄漏？**
先做一次分叉，不要直接 dump：`jcmd <pid> GC.heap_info` 看堆 used 与 committed，再 `ps -o rss= -p <pid>` 看 RSS。**堆涨 → 堆内问题；堆平、RSS 涨 → 账外内存**。堆内在涨的话，隔 2 小时取三次 `-histo:live`，只比同一个类的实例增量：增量恒定 = 真泄漏，涨到平台 = 容量膨胀。这一步能在 6 小时内定性，且几乎零成本。

**Q2：jmap -histo 和 -histo:live 有什么区别？为什么生产上慎用 live？**
`-histo` 不触发 GC，输出里包含**还没被回收的垃圾对象**，所以一次采样可能把临时对象也补进去，同一个类两次采样差几倍都可能；`-histo:live` 会先做一次 Full GC，只统计活对象，数据可信但**有 STW**（8G 堆通常 2~5 秒，老年代大时更久）。所以：粗看用 `-histo`，做增长对比用 `-histo:live` 且放在低峰。**面试如果只答「一个 GC 一个不 GC」是及格线，答出「含垃圾对象会误导对比」才是良好。**

**Q3：Shallow Heap 和 Retained Heap 的区别？为什么排障要按 Retained 排序？**
Shallow 是对象自身占用（头 + 字段 + 对齐）；Retained 是**它被回收后能一起释放的总量**，也就是它在支配树里整棵子树的 Shallow 之和。按 Shallow 排序只能找到「自己很大的对象」（比如一个 2GB 的 `byte[]`），而绝大多数泄漏是「自己很小、拖着几十万个孩子」的容器——**一个 `HashMap` 自己只有 48B，却能拖住 32MB 的 Node 数组**。只有 Retained 能看见这种结构。

**Q4：什么是支配树？为什么说「最长的引用链不是元凶」？**
若从 GC Roots 到 Y 的**每一条**路径都经过 X，则 X 支配 Y；把支配关系画成树就是支配树，父节点就是「干掉它、子树才会释放」的那个对象。**「Path to GC Roots」给的是任意一条路径，不是唯一路径**：一个被 200 个线程引用的大对象，MAT 只会显示其中一条链，顺着它改代码毫无意义。经典反例：A 被两个 Root 引用、B 只被 A 引用、C 被 A 和 B 同时引用——此时 `Retained(B) = B 自己`，因为删掉 B 之后 C 还能从 A 直达。

**Q5：ThreadLocal 为什么会内存泄漏？key 明明是弱引用。**
因为 `Entry extends WeakReference<ThreadLocal>` 只弱化了 key，**value 是普通强引用字段**。ThreadLocal 实例没人引用后，key 会在下次 GC 变成 null，但 **Entry 对象本身仍留在 `table` 数组里**，value 被它牢牢抓着。清理时机只有 `get/set/remove` 时顺带执行的 `expungeStaleEntry`——线程池里的 worker 线程不死、之后再也不碰这个 ThreadLocal，就永远没人来清。泄漏量 = 存活线程数 × value 大小。

**Q6：那 set(null) 和 remove() 有区别吗？**
有，而且很关键。`set(null)` 会创建一个 value 为 null 的 Entry 占着槽位，**不会触发对陈旧条目的清理**；`remove()` 会真正把 Entry 从 table 里摘掉，并把槽位置为可复用状态，同时顺带做一次探测式清理。**正确姿势是 `try/finally` 里调 `remove()`**——这也是唯一能保证异常路径也执行的位置。

**Q7：InheritableThreadLocal 为什么在线程池里失效？**
它的复制发生在 `Thread` 构造时（`init()` 里从父线程 copy 一份），**一次性拷贝**。线程池的线程是复用的、创建时机不确定，任务提交时并不会新建线程，所以拿到的永远是「创建那个线程时」的父线程上下文——大概率是错的。解法是 `TransmittableThreadLocal`（阿里 TTL，在任务提交/执行时做 capture/replay）或显式把上下文作为参数传递。

**Q8：ClassLoader 泄漏是怎么产生的？怎么快速确认？**
类卸载需要三个条件同时成立：该类所有实例不可达、`Class` 对象不可达、**加载它的 ClassLoader 不可达**。热部署时容器会 new 一个新的 `WebappClassLoader`，只要旧的被任何一个 JVM 级锚点挂住（自己 new 的线程池、`DriverManager`/`Logger` 这类注册表、`ThreadLocal`、shutdown hook），它加载的几千个类就一个都卸不掉。最快的判据不是看引用链，而是**数数量**：dump 里 `WebappClassLoader` 有多个实例，或者 `jcmd VM.classloader_stats` 里出现多个 classes 数很大的 loader——数量本身就是证据。

**Q9：堆外内存泄漏怎么查？dump 里看不到怎么办？**
三条线并行：① `BufferPoolMXBean` 读 Direct 的 `memoryUsed`（最准，代码里几行就能打）；② `-XX:NativeMemoryTracking=summary` + `VM.native_memory summary` 看分项（能分出 Thread / Code / GC / Other）；③ Netty 场景把 `io.netty.leakDetection.level` 调到 `paranoid` 短时复现，让它自己报分配栈。**不要指望 dump——堆外的东西根本不在对象图里。**

**Q10：8G 的堆，为什么容器 RSS 会到 12G？**
因为 RSS 记的是进程所有内存，不只是堆：堆 committed 8G + Metaspace ~0.5G + CodeCache ~0.24G + Direct ~1.2G + glibc arena/碎片 ~1G + JVM 自身与线程栈。**而且 `-Xmx` 限制的是 used，不是 committed**——堆一旦扩到 8G 就不会还回去，即使里面只有 3G 活对象。所以「堆 dump 只有 3G」和「RSS 12G」完全不矛盾。

**Q11：一定要 dump 吗？JFR 能不能替代？**
能替代一半。**JFR 的 `OldObjectSample` 给的是「老年代活对象的分配栈」**，直接回答「谁生的它」，开销小于 1% 可常开，这是 dump 做不到的。但 dump 能回答的「谁抓着它、支配关系如何」JFR 给不了。**正确组合是：JFR 定位到代码行 → dump + 支配树确认影响面**。而且 JFR 有个前提——**必须提前开**，泄漏发生时再开就晚了。

**Q12：OOM 时自动 dump 和 OOM 前手动 dump，哪个更有价值？**
OOM 前的。原因有三：① OOM 时堆已满，dump 出来的图被大量「本该被回收的临时对象」污染，真正的泄漏体反而不显眼；② 8G 堆在 OOM 时 dump 的 STW 更长，容易把服务彻底打死；③ 泄漏的斜率在第 2 天就能算出来，没必要等到第 93 天。**但 `HeapDumpOnOutOfMemoryError` 仍然必须开**——它是兜底，不是首选。

**Q13：用 WeakHashMap 做缓存是不是就不会泄漏了？**
不一定。WeakHashMap 的 key 是弱引用，**但它同样只在 get/set 时清理死条目**。一个「写一次、再也不访问」的 WeakHashMap，泄漏行为和普通 HashMap 一样；更糟的是 value 如果反过来强引用了 key（比如 `map.put(k, new Foo(k))`），那 key 永远有强引用，弱引用形同虚设——**这是弱引用最经典的失效方式，叫「值反向持有键」**。

**Q14：虚拟线程普及之后，ThreadLocal 泄漏还存在吗？**
对虚拟线程本身基本不存在：ThreadLocal 存在虚拟线程的 StackChunk 里，虚拟线程结束即整体释放，不依赖 remove。但两个残留风险：① **carrier 线程（ForkJoinPool）上的 ThreadLocal 依然与应用同寿**，把大对象放进去照样漏；② **线程池 + 虚拟线程混用**时，任务在平台线程上执行期间的上下文仍可能残留。**结论：默认不变坏，但也别指望它帮你擦屁股——remove 仍然要写。**

---

## L12 · 3 道模拟面试

**题 1：订单服务上线 3 个月，每 2 个月必须重启一次。堆 8G，Full GC 后仍有 6.5G 存活。请说你的排查路径。**

**不及格**：「用 jmap dump 下来用 MAT 分析，看哪个对象最多。」——没说怎么定性、没说 dump 的代价、也没说怎么从对象找到代码。

**及格**：「先 dump，用 MAT 看 Retained Heap 最大的对象，顺着引用链找到业务代码，加日志确认。」——方向对，但漏了最重要的一步：**你没有先证明它是真泄漏**，Full GC 后 6.5G 存活也可能只是缓存太大。

**良好**：「先算斜率：连续三次 `-histo:live` 间隔 2 小时做差。如果某个业务类实例增量恒定，就是真泄漏；同时用增量算出速率和爆堆天数，决定是紧急修还是排期修。**dump 要在堆还没满的时候做**——现在 6.5G 存活、8G 上限，已经是危险区，应该先扩容或摘流再做。拿到 dump 后按 Retained 降序，只展开前三棵子树。」

**优秀**：在「良好」之上补三点：① **先区分三种「涨」**——用 `GC.heap_info` 确认堆 committed 在涨（排除账外），再看 Full GC 后存活是否单调上升；② **用 JFR 的 OldObjectSample 直接拿分配栈**，比 dump 快两个数量级，能在 10 分钟内定位到 `SessionCache.put()` 这一行；③ **给出可验证的结论和回归方案**：修复后用同一套 histo 对比脚本跑 48 小时，确认该类实例数不再单调增长，并在监控上加「该类实例数」这个自定义指标做长期哨兵。**还要说明为什么「上了大堆就没再出现」是自欺欺人（斜率不变，只是爆堆天数变长）。**

**题 2：容器 limit 12G、`-Xmx8g`，进程每 5 天被 exit 137 杀掉，Java 层无任何异常，堆 dump 里只有 3G 活对象。怎么查？**

**不及格**：「加大内存 / 调大 -Xmx。」——这是在给泄漏留更多空间，下次变成 7 天杀一次，问题还在。

**及格**：「怀疑是堆外内存泄漏，用 `top` 看 RES，用 `pmap` 看内存分布，检查是否有 DirectByteBuffer 或 Netty 没 release。」——方向对，但「用 pmap 看分布」对定位帮助很小，没有可执行的量化手段。

**良好**：「先确认堆不是主因：`GC.heap_info` 看 committed 是否稳定在 8G、used 是否有周期性。然后开 NMT（`-XX:NativeMemoryTracking=summary`）拿 `VM.native_memory summary` 分项，重点看 `Other`（glibc）和 `Internal`（Direct）。用 `BufferPoolMXBean` 精确读 Direct 用量。如果是 Netty，把泄漏检测调到 paranoid 复现。」

**优秀**：在「良好」之上补四点：① **把账先对上**：8G 堆 committed + 0.5G Metaspace + 0.24G CodeCache + 1.2G Direct + 1G glibc ≈ 11G，**在 12G limit 下本来就没有余量**，所以这不一定是「泄漏」，可能是「账没算对」；② **指出关键因果**：`MaxDirectMemorySize` 默认等于 `-Xmx` 8G，堆外可以吃到 8G，容器根本兜不住，且 **Direct 的释放依赖 Full GC**，堆越空越不 GC，**堆外先炸而 Java 层无异常**——这正好解释了「无异常 + 137」；③ **给出配置级修法**：`-XX:MaxRAMPercentage=60` + `-XX:MaxDirectMemorySize=512m` + `MALLOC_ARENA_MAX=2`，核心原则是**让 Java 先 OOM、容器后杀**，因为 OOM 可查、137 不可查；④ **排查顺序上先说「先证明它在涨」**：RSS 每 10 分钟采一次画斜率，斜率大于 0 才是泄漏，否则就是容量规划问题。

**题 3：Tomcat 应用每次热部署后 Metaspace 涨 30MB，第 14 次 `OutOfMemoryError: Metaspace`。给你 dump，怎么 5 分钟内出结论？**

**不及格**：「调大 `-XX:MaxMetaspaceSize`。」——把 14 次变成 28 次，问题一点没解决，而且下次还是在生产上炸。

**及格**：「看 dump 里的 `WebappClassLoader` 是不是有多个实例，如果有就是类加载器泄漏，检查应用里有没有未关闭的线程池。」

**良好**：「dump 里搜 `ParallelWebappClassLoader`，数量大于 1 就证明旧 loader 没卸。对其中一个旧实例做 Path to GC Roots，看是哪个锚点挂住它——通常是 `ThreadPoolExecutor$Worker`（自己 new 的线程池）、`DriverManager` 的注册表、或者某个 `ThreadLocalMap`。然后成对补齐 `shutdown`/注销逻辑。」

**优秀**：在「良好」之上补三点：① **先讲清机制再动手**：类卸载三条件（实例、Class、loader 全不可达），所以**挂住 loader 就等于挂住它加载的全部 5000 个类和静态字段**，这才是「+30MB」的来源；② **给出比 dump 更快的判据**：`jcmd VM.classloader_stats` 直接列每个 loader 的 Classes 数与 ChunkSz，一眼看到两个 5120 类的 loader——**不用下载 dump、不用装 MAT，5 秒出结论**；③ **给出体系化修法而不是逐条打补丁**：把所有「注册到 JVM 级的东西」列成清单（Executor、ThreadLocal、Driver、Logger、JMX、ShutdownHook），在 `contextDestroyed()` 里逐项反注册，并把 `MaxMetaspaceSize` 显式设成 512M 当保险丝，**让下一次泄漏在测试环境就爆，而不是在生产爆第 14 次**。

---

## 小结 · 如果只记三句

1. **先分型，再动手。** 真泄漏看斜率、容量膨胀看谷底、账外内存看「RSS 减堆」。这三个观测量对应三种病，**顺序错了，所有工具都是白用**——在堆外的病历上翻堆内的检查，是最常见的浪费。
2. **元凶由支配关系决定，不由引用链长短决定。** 按 Retained 降序展开支配树，而不是顺着「Path to GC Roots」的第一条链改代码。**一个 `HashMap` 自己只有 48B，却能拖住 32MB**——只有 Retained 看得见这种结构。
3. **所有泄漏都是「有个东西活得比它久」。** 线程、ClassLoader、全局注册表、容器里的静态 Map——排查永远先问「谁比它活得久」。而工程上唯一稳的防线是**让 Java 先 OOM、容器后杀**：`MaxDirectMemorySize` 写死、`MaxRAMPercentage` 留余量、`HeapDumpOnOutOfMemoryError` 兜底，因为 **OOM 可查，137 不可查**。

**下期预告**：下一期往更底层走——**JIT 编译与基准测试**。分层编译的五层是怎么爬上去的、内联的阈值和「热点方法太大反而不优化」、逃逸分析如何把对象直接消灭在栈上（标量替换）、去优化（deopt）为什么会让你的压测数据突然变脸，以及为什么 `-Xcomp`、`for` 循环预热、`System.nanoTime` 裸测这三件事会让你得出完全错误的结论。这期会给你一套能直接跑的 JMH 骨架，和一张「我的压测数字为什么不可信」的检查清单。

---

*Java 面试深水区 · 第 21 期 · 12 张手绘 SVG · 21 段源码与命令 · 20 个速查数字 · 14 问答 · 3 道模拟面试*
