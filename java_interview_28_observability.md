# Java 面试深挖 28 · 可观测性：指标基数、分位数陷阱、Trace 断链

> 这一期回答一个每个人都吼过、但很少有人答利索的问题：**"我本地测 P99 是 120ms，监控上 800ms，到底谁在撒谎？"** 答案往往不是谁撒谎，而是"P99"这三个字在两边指的不是同一个东西。
>
> 前 27 期把语言、JVM、并发、框架、存储、字节码都过了一遍，唯独没讲"系统上线之后你怎么知道它在干什么"。这一期补上，而且全部数字来自本机真跑（JDK 21.0.7 + Micrometer 1.16.7 + Prometheus 客户端 1.4.3），包括那个能把 P99 报成真实值 1.95 倍的桶。
>
> 可观测性最容易讲成"三支柱 + 上工具"，那是最没区分度的答法。有区分度的讲法是算账：一条标签值多少钱、一个桶宽值多少误差、一次线程池切换丢多少上下文。

---

## L0 先说结论：三种信号各自答一个问题，谁都不能替谁

面试里被问"你们怎么做的可观测性"，答"接了 Prometheus + ELK + SkyWalking"基本等于没答。真正的分工是这样：

| 信号 | 能回答 | 不能回答 | 一条数据的成本 | 天生的缺陷 |
|---|---|---|---|---|
| Metrics | **整体**现在什么状态、趋势怎么走、要不要告警 | 这一次请求为什么慢 | 数百字节 | 聚合后个体消失 |
| Logs | **这一次**请求发生了什么、变量是什么值 | 全局占比、趋势 | 数百字节到数 KB | 无法聚合、量大 |
| Traces | 这一次请求**经过了谁、各花多久** | 历史上是否一直这样 | 每 span 数百字节 | 必须采样 |

三句话概括它们的宿命：

- 指标是**聚合后的数**，所以它天生不知道自己由谁组成——这是分位数不能平均的根源。
- 日志是**个体**，所以它天生无法回答"占比"——你能 grep 出 100 条报错，但不知道是 100 条还是 10 万条里的 100 条。
- 链路是**关系**，所以它天生必须采样——全量链路的数据量等于请求数乘节点数，没有任何公司扛得住。

这三条"天生"决定了后面所有工程选择。看懂这一层，你就不会再问"为什么不能只用日志"或者"为什么链路不能 100% 采样"这种问题。

补一个常被忽略的第四类：**Profiling（持续性能剖析）**。它答的是"CPU 时间花在哪个方法上"，与前三者正交。JFR 的 `jdk.ExecutionSample` 就是这一类。

---

## L1 分位数不可平均：三个答案，只有一个是真话

先上数据。造两组请求：A 实例 10000 次（98% 在 50ms，2% 在 200ms），B 实例 200 次（全部 900ms）。这非常真实——A 是主力集群，B 是刚扩容的一台，或者是一个只接内部流量的旁路实例。

```java
double[] A = new double[10000];
for (int i = 0; i < 10000; i++)
    A[i] = i < 9800 ? 50 + r.nextDouble() * 2 : 200 + r.nextDouble() * 2;
double[] B = new double[200];
for (int i = 0; i < 200; i++) B[i] = 900 + r.nextDouble() * 2;
// 按 nearest-rank 取分位：idx = ceil(q * n) - 1
```

跑出来的结果：

```text
A: n=10000  P50=51.0  P99=201.0
B: n=200    P50=901.1 P99=902.0
平均两个 P99        = 551.5 ms
加权平均(按请求数)  = 214.8 ms   ← 更离谱
真实合并 P99        = 901.1 ms

平均法低估 = 38.8%
加权法低估 = 76.2%
```

**三个答案，差了三倍。** 最讽刺的是"加权平均"——很多人觉得"按请求数加权总该对了吧"，结果它比裸平均错得更狠，低估 76%。

为什么？因为 P99 不是"平均值"，它是**排序后的第 99% 个位置上的那个值**。加权平均算的是"两个边界值的加权中心"，而真实的合并 P99 取决于**排序后第 10098 个元素落在哪一组**。这里 B 的 200 个请求全部 900ms，覆盖了尾部 200 个位置，所以合并后的 P99 就是 901。权重再正确也算不出来——**信息在算之前就已经丢了**。

顺手看 P50 有多荒唐：

```text
P50 平均=476.1   真实合并=51.0   偏差 -832.5%
```

平均两个 P50 得到 476ms，而真实中位数是 51ms。差了一个数量级。如果哪个 dashboard 真的这么算，你会以为系统整体慢了 9 倍。

一句话带走：**分位数是次序统计量，不是线性算子。凡是"先算分位再合并"的做法，数学上就是错的**，跟权重、样本量、算法实现都没关系。

---

## L2 桶为什么能相加：累计计数是线性的

那 Prometheus 怎么解决这个问题？它不比"分位数"，它比**桶（bucket）**。

一个 `le="200"` 的桶，含义是"耗时小于等于 200ms 的请求数"。注意这是**累计计数**，是个普通的整数。整数的加法是封闭的：

```text
A: le<=100 → 9800    B: le<=100 → 0      合并 → 9800
   le<=200 → 10000      le<=200 → 0          10000
   le<=500 → 10000      le<=500 → 0          10000
   le<=1000→ 10000      le<=1000→ 200        10200
   +Inf    → 10000      +Inf    → 200        10200
```

五个数对应位置相加，合并后的桶**精确等于**把两组原始数据合在一起再统计的结果。然后在这张合并后的表上算 `histogram_quantile(0.99, ...)`，才对。

真实 Micrometer + Prometheus 导出的验证（两个 registry 各自记录，各自 scrape，再相加）：

```text
A 桶数 69  B 桶数 69
A 单实例 P99 = 197.3 ms ; B 单实例 P99 = 983.4 ms
桶相加后合并 P99 = 938.6 ms   （真实 901.0，误差 +4.2%）
+Inf 桶总数 = 10200 ; 实际请求数 = 10200
```

两个关键点：

1. `+Inf` 桶加起来正好等于 10200，说明桶的加法没有丢样本——这是"可合并"的硬证据。
2. 合并结果 938.6 而不是 901.0，误差 4.2% 来自桶宽和插值，但**方向和量级都对**。相比之下，平均分位数法的 551.5 已经错到不能看了。

PromQL 里正确的写法只有一个形状：

```promql
histogram_quantile(0.99,
  sum by (le) (rate(http_server_requests_seconds_bucket[5m]))
)
```

顺序不能反：**先 `sum by (le)` 把桶加起来，再 `histogram_quantile`**。写成 `avg(histogram_quantile(0.99, ...))` 就回到了 L1 的错误里，只不过这次是 Prometheus 帮你犯的。

面试到这里通常会被追问一句："那 `sum by (le) (rate(...))` 里的 `rate` 为什么能先做？"——因为 `rate` 是对**同一个桶的时间序列**做线性运算（差分除以秒数），它作用在单条 series 上，不跨 series 合并，所以没问题。一旦跨 series 就要先对齐再做非线性运算，这是 PromQL 的基本纪律。

---

## L3 桶宽就是误差上限：510ms 那个实验

桶能合并，但桶有精度。这里有个反直觉的事实：**误差不取决于算法，只取决于桶边界画在哪。**

造一份极端数据：10000 个请求全部 510ms。真实 P99 = 510ms。然后换三套桶：

```text
桶 [500, 1000]                P99 = 995.0 ms   误差 +95.1%
桶 [500, 600, 1000]           P99 = 599.0 ms   误差 +17.5%
桶 [500, 520, 600, 1000]      P99 = 519.8 ms   误差  +1.9%
```

同一份数据，同一个 `histogram_quantile` 算法，报出来 995 / 599 / 519.8。

原因在插值公式：落在 `(500, 1000]` 这个桶里时，算法只能**假设桶内均匀分布**，然后按比例线性插值。真实数据全挤在桶的左边缘 510，插值却把它摊到了 99% 的位置，于是算出 995。

这条实验给出三条可直接用的规则：

**规则一：相对误差上限 ≈ 桶宽 ÷ 落点值。** 桶 `(500,1000]` 宽 500，落点 510，比值接近 1，误差就能到 95%。桶 `(500,520]` 宽 20，比值 0.04，误差 1.9%。

**规则二：SLO 边界必须正好是桶边界。** 如果你的 SLO 是"P99 小于 200ms"，但桶是 `(100, 500]`，那么报出来 199ms 和 499ms 在监控上完全一样——你根本无法判定 SLO 是否达标。这条在面试里说出来，比背十个指标名有用得多。

**规则三：默认桶不一定适配你的业务。** Micrometer 的 `publishPercentileHistogram()` 默认给 1ms 到 10s 的一组指数桶（实测导出 69 个 `le`），对 HTTP 接口够用；但对一个 P99 只有 3ms 的本地缓存调用，所有请求都挤在最小的两个桶里，P99 就变成了"桶边界值"。

定制方式就是显式给 SLO 边界：

```java
Timer.builder("order.submit")
     .publishPercentileHistogram()
     .serviceLevelObjectives(
         Duration.ofMillis(50), Duration.ofMillis(100),
         Duration.ofMillis(200), Duration.ofMillis(500),
         Duration.ofMillis(1000))
     .register(registry);
```

顺便提一个真实存在的坑：`.publishPercentileHistogram()` 加上 `serviceLevelObjectives` 时，Micrometer 是**取并集**而不是替换——所以你会看到 69 个桶而不是 5 个。想让桶变少，得把 `publishPercentileHistogram()` 换成 `publishPercentileHistogram(false)`（不导直方图）——那就干脆没有桶了，只剩客户端分位数，回到 L4 的坑里。这个取舍没有免费选项。

---

## L4 客户端分位数：一个看起来能用、实际是陷阱的开关

Micrometer 还有另一个开关：`publishPercentiles(0.5, 0.99)`。它不导桶，它在 **JVM 内部算好分位数**，然后把结果当成一条带 `quantile` 标签的时间序列导出去。

实测导出长这样：

```text
# TYPE api_latency_seconds summary
api_latency_seconds{quantile="0.5"} 0.050331648
api_latency_seconds{quantile="0.99"} 0.19922944
# TYPE api_latency_seconds_max gauge
```

看起来很美：只有一个数，存储便宜，还能直接告警。但它有两个致命缺陷，都是结构性的：

**缺陷一：`quantile` 是标签，不是指标名。** Prometheus 里 `sum()` 只能合并**同一个指标名**下的不同 series。你可以 `sum(api_latency_seconds)`，但那是在把 P50 和 P99 加起来——毫无意义。想"把两个实例的 P99 合并"，语法上根本不存在。

**缺陷二：值在客户端算，随实例重启清零。** 桶是累计计数，重启后从 0 开始涨，趋势连续；而客户端直方图（Micrometer 默认用 `TimeWindowPercentileHistogram`，滑动窗口）重启就空。你会在发布时看到 P99 从 200ms 掉到 5ms 再爬回 200ms 的"改善假象"——因为样本还不够，前几个请求都在 5ms。

所以结论很干脆：**`publishPercentiles` 只能用于单实例调试或本地压测**。生产环境只要是多副本，就必须走直方图。

那为什么这个 API 还存在？因为有些场景确实没有多实例聚合需求，比如单机批处理任务、本地 benchmark。工具没错，错的是把它用在分布式场景。

还有一个中间态值得知道：**t-digest / DDSketch** 这类"可合并的 sketch"。它们把分位数编码成一个可加的数据结构，能跨实例合并且误差可控（t-digest 相对误差约 1%）。代价是要把 sketch 序列化成二进制 blob 再传，Prometheus 的文本协议塞不进去，所以主流做法是走 OTLP 的 exponential histogram（OpenTelemetry 规范里定义的第 4 类指标点）。面试里能说出"exponential histogram 是可合并 sketch 在 OTel 里的落地"，属于加分项。

---

## L5 标签基数：一条 userId 能把 Prometheus 和自己一起打挂

这是可观测性线上事故的第一名，比 OOM 和死锁都常见，因为它是**无声**的——没人改代码逻辑，只是给指标多加了两个标签。

先算乘法。一个 HTTP 接口指标，标签组合数等于各标签取值数的**乘积**：

```text
uri(10) × status(4)                        = 40 条 series
uri(10) × status(4) × userId(100)          = 4,000 条
uri(10) × status(4) × userId(2000)         = 80,000 条
uri(10) × status(4) × userId(100000)       = 4,000,000 条   ← 这里就死了
```

实测三个档位（`PrometheusMeterRegistry` + 逐个建 counter，`Runtime` 前后差值）：

```text
只有 uri × status        series=    40   堆增量= 1.4 MB   scrape=   2.1 KB
加了 userId（100 个）    series= 4,000   堆增量= 3.7 MB   scrape= 249.7 KB
加了 userId（2000 个）   series=80,000   堆增量=57.4 MB   scrape=5113.0 KB
```

三点解读：

**第一，单条 series 的客户端成本约 750 字节。** `4,000 → 965 B/条`、`80,000 → 753 B/条`，规模上去后稳定在 750 左右。第一行 40 条算出 37KB/条纯属噪声——那是 registry 初始化的固定开销，不是 series 的成本。**这也是个方法论提醒：小样本测单条成本，测出来的永远是初始化开销。**

**第二，服务端的成本比客户端高一个数量级。** Prometheus 官方文档给的经验值是每条活跃 series 3~4KB（head block 里的 label 索引 + postings + 时间序列元数据）。按 3.5KB 算，8 万条 = 280MB；400 万条 = **14GB**，还没算磁盘上的 chunk 和倒排索引。一个中等规格的 Prometheus（16GB 内存）会被这一个指标打死。

**第三，scrape 文本体积是隐藏的带宽账。** 8 万条 series 一次 scrape 出来 5.1MB 文本。Prometheus 默认 15 秒抓一次：

```text
5.1 MB × 4 次/分钟  = 20.5 MB/分钟
20.5 MB × 1440      = 约 29.5 GB/天（单个 pod）
100 个 pod          = 约 2.95 TB/天（纯文本传输）
```

这还没算 JSON 解析的 CPU。Prometheus 抓不过来的典型表现是 `scrape timeout`、`context deadline exceeded`，然后 target 变 DOWN，然后你以为服务挂了，其实服务好好的——只是监控自己噎住了。

**四类绝对不能进标签的值**（记住这四条，比记十个最佳实践有用）：

| 类型 | 例子 | 为什么会炸 | 正确做法 |
|---|---|---|---|
| 用户/请求标识 | userId、orderId、traceId | 基数等于用户数 | 放 exemplar 或日志 |
| 原始 URL | `/order/123456` | 基数等于订单数 | 用模板 `/order/{id}` |
| 异常消息 | `msg="timeout after 3000ms"` | 基数等于错误种类×参数组合 | 用 `error.type` 枚举 |
| 时间戳/随机数 | `ts`、`uuid` | 每次请求都是新 series | 永远不要 |

最后那条"traceId 放 exemplar"值得单独说一句，因为它是这些年的正确答案：Prometheus 2.26+ 支持 exemplar，一条 metric sample 上可以挂一个 `(traceId, spanId)` 的标注，不参与 series 身份计算。这样你在 Grafana 上看到一个 P99 尖刺，点一下就能跳到对应那条链路——**既有指标的成本，又有链路的关联**。这就是"指标与链路的缝合点"。

面试话术：「我们给指标标签做过基数预算，所有标签取值域的乘积必须小于 1 万，超了就拆指标名或者改 exemplar。userId 这种一定进 exemplar 不进 label。」——一句话就能证明你被这个坑咬过。

---

## L6 Trace 断链之一：线程池把 MDC 和 ThreadLocal 一起吃了

链路追踪在生产上失效，九成不是探针没埋，而是**上下文没跟着线程走**。

先做一个最小复现。主线程放三样东西：MDC（日志上下文）、普通 ThreadLocal、InheritableThreadLocal。然后丢进单线程池执行两次，中间改一次主线程的值。

```java
MDC.put("traceId", "abc123");
TL.set("tl-abc123");
ITL.set("itl-abc123");
System.out.println("池内第1次  " + pool.submit(Exp4Mdc::probe).get());
MDC.put("traceId", "def456");   // 主线程换上下文
TL.set("tl-def456");
ITL.set("itl-def456");
System.out.println("池内第2次  " + pool.submit(Exp4Mdc::probe).get());
```

输出：

```text
主线程     MDC=abc123  TL=tl-abc123  ITL=itl-abc123
池内第1次  MDC=null    TL=null       ITL=itl-abc123  @pool-1-thread-1
池内第2次  MDC=null    TL=null       ITL=itl-abc123  @pool-1-thread-1   ← 脏值
包裹后     MDC=def456  TL=null       ITL=itl-abc123  @pool-1-thread-1
```

三个结论，一个比一个狠：

1. **MDC 和 ThreadLocal 直接是 `null`。** 因为它们都是"每线程一份"的 `ThreadLocalMap` 里的条目，新线程的表是空的。
2. **InheritableThreadLocal 第一次能用**（`itl-abc123`），因为 `Thread` 构造时会从父线程拷贝。看起来是解药。
3. **第二次就中毒了。** 主线程已经改成 `itl-def456`，可池里的线程还是 `itl-abc123`——因为线程被复用，`Thread` 对象根本没重新构造，拷贝时机早就过去了。**这就是 InheritableThreadLocal 在池化场景下从"能用"变成"更危险"的原因：它给的不是 null，是错的旧值。** null 你还能靠空指针发现，脏值会让日志串成别人的链路，排查时把人带沟里。

为什么 1 和 2 差这么多？看 `Thread` 的构造就知道：

```java
// Thread 构造里（简化）
if (inheritThreadLocals && parent.inheritableThreadLocals != null)
    this.inheritableThreadLocals = ThreadLocal.createInheritedMap(parent.inheritableThreadLocals);
```

拷贝只发生在 `new Thread()` 这一刻。线程池的线程是**复用**的，一辈子只 new 一次。

修复方案有三层，按侵入性从低到高：

**方案一：`TaskDecorator`（Spring 提供，最省事）**

```java
@Bean
public TaskDecorator mdcDecorator() {
    return runnable -> {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();   // 快照
        return () -> {
            Map<String, String> old = MDC.getCopyOfContextMap();
            if (snapshot != null) MDC.setContextMap(snapshot); else MDC.clear();
            try { runnable.run(); }
            finally {                      // 必须还原，否则污染下一个任务
                if (old != null) MDC.setContextMap(old); else MDC.clear();
            }
        };
    };
}

// 挂上去
ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
exec.setTaskDecorator(mdcDecorator());
```

注意 `finally` 里是**还原**而不是 `clear`。因为线程复用，你 clear 掉的可能不是自己的东西；正确姿势是"进来时存旧值、出去时还回去"，跟事务的挂起/恢复一个道理。

**方案二：OTel 的 `ContextSnapshot` / `Context.wrap()`（跨线程传播 trace 的正确做法）**

```java
Context ctx = Context.current().with(Span.current());
Runnable wrapped = ctx.wrap(() -> doWork());   // 执行时自动 attach/detach
executor.submit(wrapped);
```

它的实现方式和方案一结构一样（wrap 就是 decorator），但它顺带把 `Baggage`、`Span` 一起带过去了，而且 `attach/detach` 是成对的，不会漏。

**方案三：虚拟线程场景要换思路。** 虚拟线程数量可以到百万级，每个线程复制一份 ThreadLocal 是实打实的内存（第 13 期算过：100 万虚拟线程 × 1KB ThreadLocal ≈ 1GB）。JDK 25 正式化的 `ScopedValue`（JEP 506）就是为此设计的：它是**不可变、按作用域绑定**的，没有"每线程复制"这回事。在虚拟线程 + 结构化并发的代码里，`ScopedValue` 比 ThreadLocal 更合适——但注意它不可变，所以不适合"过程中还要改值"的场景。

一句话总结这段：**上下文传播的本质是"快照—恢复"，不是"继承"。继承只看创建时刻，快照看提交时刻，而请求上下文是提交时刻才确定的。**

---

## L7 Trace 断链之二：MQ 是异步边界，也是上下文黑洞

线程池是同一进程内的边界，MQ 是跨进程的边界。两者的失效机理不一样：线程池丢的是 `ThreadLocalMap`，MQ 丢的是 **header**。

标准的 W3C Trace Context 就一个头：`traceparent`。格式固定 55 个字符：

```text
00-fbeb5af0bde7eadf9f48b2d1318a1e61-149e3bb7f24c1b14-01
││ └────────────── 32 hex = 16 字节 traceId ──────────────┘ │└ 2 hex flags
│└ version(2)                          spanId(16 hex = 8 字节)┘
```

实测校验：

```text
长度 = 55 字符, ASCII 字节数 = 55
字段 = version(2) + traceId(32) + spanId(16) + flags(2)
2 + 1 + 32 + 1 + 16 + 1 + 2 = 55
解析: version=00 traceId=fbeb5af0...8a1e61 spanId=149e3bb7f24c1b14 flags=01 sampled=true
全零 traceId 格式合法? true  但规范要求丢弃 -> true
```

三个细节值得记住：

1. **flags 的最低位是采样标记**（`01` = sampled）。这个位决定了这条链路后续所有节点要不要记录——head sampling 的"决定"就写在这 2 个 hex 里。
2. **全零的 traceId / spanId 是格式合法但语义非法的**，必须丢弃。正则校验通不过这一关，得额外判一次。这是规范明确要求的。
3. **55 字节看着小，乘起来不小**：20 跳链路累计 1100 字节 header。3 万 QPS 时全链路累计 33MB/s 的纯 header 流量。所以 baggage（业务自定义透传）一定要设上限——OTel 规范建议 8KB，实际项目里 1KB 就该报警了，因为 baggage 会随 fan-out 放大。

生产上真正会踩的四个坑：

**坑一：只注入了 `traceparent`，没注入 `tracestate`。** `tracestate` 是厂商扩展位（比如各家的采样优先级），长度可变、最多 32 个 entry。丢了它，跨厂商链路会断在中间。

**坑二：批量消费时一个 span 对 N 条消息。** Kafka 一次 `poll()` 拿 500 条，如果你只给整批开一个 span，出问题时你不知道是哪条消息慢。正确做法是每条消息一个 child span，`links` 指回批次 span（OTel 的 Span Link 就是为这个设计的）。

**坑三：重试和死信队列丢 header。** 消息进 DLQ 时，很多中间件会重建消息体，自定义 header 就没了。RabbitMQ 的 `x-death` 会保留一部分，Kafka 的 DLQ 通常要自己复制 header。**排查"为什么 DLQ 里的消息链路全断"时，先查这里。**

**坑四：消费者要不要"续"trace？** 两种选择，语义不同：

| 选择 | 做法 | 适用 |
|---|---|---|
| 续 trace | 用消息里的 traceId，parent 设成生产者 spanId | 同步语义（下单→扣库存） |
| 起新 trace | 新 traceId，用 Span Link 关联生产消息 | 异步解耦（发通知、算报表） |

面试里被问"MQ 后面链路怎么串"，答"必须续 trace"是错的——异步解耦的场景续 trace 会让一条 trace 横跨几小时，把 Jaeger 的时间轴拉成一条线，什么也看不出来。**正确答案是分场景，并且说出 Span Link 这个工具。**

---

## L8 采样：三种采样的冲突与一个必算的数字

链路必须采样，问题是采多少、在哪采。

| 方式 | 在哪决定 | 优点 | 致命缺点 |
|---|---|---|---|
| Head sampling | 请求入口（写进 flags） | 零成本、无状态 | 决策时还不知道结果，错误/慢请求照样被丢 |
| Tail sampling | Collector（攒够一个窗口再决定） | 能按结果采样：错误全留、慢请求全留 | 要缓存整条链路，内存账很贵 |
| 日志采样 | Appender | 省磁盘 | 与链路采样策略不一致，对不上号 |

算一个必算的数字，用来打醒"1% 采样够了"的幻想：

```text
head sampling = 1%，错误率 = 0.1%
要观察到 1 条错误 trace，需要请求数 = 1 / (0.001 × 0.01) = 100,000 次
```

10 万次请求才捞到一条错误样本。1 万 QPS 的话是 10 秒——看起来还行。但如果你要排查的是"某个特定用户的某个特定接口偶发失败"，发生率可能是百万分之一，那就要 1 亿次请求。**这就是 head sampling 在故障排查上的结构性缺陷：它按比例采样，而故障按稀有度分布。**

所以生产标准配置是**组合采样**：

```text
错误请求（status>=500 或 exception） → 100%
慢请求（> P99 阈值）                 → 100%
调试用户（特定 header / 白名单）     → 100%
其余                                → 1%~5%
```

这就是 tail sampling 的典型策略，但它有成本。算 buffer 账：

```text
假设 30 秒窗口、5000 QPS、每链路 20 个 span、每 span 序列化后 1.5KB
在途数据量 = 30 × 5000 × 20 × 1.5KB = 4.5 GB
```

4.5GB 的 collector 内存只为了"等 30 秒再决定"。所以 tail sampling 必须**分片部署**（按 traceId 哈希路由到同一个 collector 实例），否则每个实例都要缓存全量，成本乘实例数。这一层能说出来，面试官就知道你真上过。

还有一条容易被忽略的：**采样率不一致会导致指标和链路对不上**。链路 1% 采样，指标 100% 统计，你在 Grafana 上看到 QPS 1000，在 Jaeger 里只找到 10 条 trace。这不是 bug，是采样。**回答"为什么我的 trace 数量比监控少这么多"时，这就是标准答案。**

---

## L9 日志开销：CPU 几乎不花钱，真正花钱的是另外三样

先拆穿一个流传很广的说法——"打日志很慢，会影响性能"。实测一下（logback 1.5.38 + slf4j 2.0.17，root 级别 INFO，debug 关闭，appender 用 NOPAppender 排除 IO 干扰）：

```java
// 四种写法，各跑 20 万次
for (int i = 0; i < N; i++) log.debug("user=" + expensive() + " done");        // 裸拼
for (int i = 0; i < N; i++) log.debug("user={} done", expensive());            // 参数化
for (int i = 0; i < N; i++) if (log.isDebugEnabled()) log.debug("user={}", expensive());  // 守卫
for (int i = 0; i < N; i++) log.debug("user={} done", i);                      // 对照组
```

```text
debug 是否开启 = false
裸拼字符串            217.8 ns/次
参数化 {}              64.2 ns/次
isDebugEnabled         21.4 ns/次
参数化(常量参)          9.4 ns/次     ← 基线
expensive() 本身       22.7 ns/次
```

四个结论，第三个是反直觉的：

**第一，裸拼的代价是基线的 23 倍（217.8 / 9.4）。** 因为它无条件构造 `StringBuilder`、逐段 append、最后 `toString()`，然后再被 logger 丢掉。这条大家都知道。

**第二，参数化的 64.2ns 里，22.7ns 是 `expensive()` 自己的成本**，剩下约 41ns 是 `Object[]` 数组分配 + `int` 装箱 + 调用开销。注意：**参数化并不会省掉"参数求值"**，它只省掉"字符串拼接"。所以 `log.debug("x={}", slowQuery())` 在 debug 关闭时仍然会执行 `slowQuery()`——这是最常见的误解。

**第三，参数便宜时，`isDebugEnabled()` 守卫反而更慢。** 守卫 21.4ns，直接参数化传常量只有 9.4ns。也就是说，为了省一次字符串拼接而加一层 `if`，在参数本身很便宜（字面量、已算好的变量）的场景下是**净亏 12ns**。守卫只在"参数求值很贵"时才划算——这里省下的是 64.2 − 21.4 = **42.8ns**。

所以正确的取舍是：

```text
参数很贵（查库、序列化、拼 JSON）  → 加 isDebugEnabled 守卫
参数很便宜（变量、常量）           → 直接 {} 参数化，别加守卫
```

**第四，CPU 根本不是瓶颈。** 3 万 QPS 下打得最狠的裸拼也就 `30000 × 217.8ns = 6.5ms/s`，**0.65% 的单核**。所以"日志拖慢 CPU"是个伪命题。真正的成本在另外三处：

| 成本 | 账 | 后果 |
|---|---|---|
| 内存分配 | 裸拼一次约 120B 垃圾，3 万 QPS = 3.6MB/s，**约 13GB/小时** | 年轻代 GC 次数上升，P99 抖动 |
| 同步 IO | 同步 appender 每写一行都要拿锁 + 写盘 | 磁盘抖动时业务线程跟着卡 |
| 磁盘 | 单行 500B × 3 万 QPS = 15MB/s = **1.3TB/天** | 日志盘三天写满 |

第三行才是真实事故：**15MB/s 的日志写入，任何一台普通云盘的持续写能力都会被吃掉大半**。

异步 appender 是解药，但它自带一个静默失效的坑：

```xml
<appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
  <queueSize>1024</queueSize>
  <discardingThreshold>0</discardingThreshold>   <!-- 0 = 不丢弃 -->
  <neverBlock>true</neverBlock>                  <!-- 队列满时直接丢 -->
</appender>
```

- `queueSize` 默认只有 256（很小）。
- `discardingThreshold` 默认是队列容量的 20%——也就是说**队列用到 80% 时，INFO 和 DEBUG 级别的日志会被无声丢弃**。这是"日志少了"最常见的原因，而且不会报任何错。
- `neverBlock=true` 换成"队列满就丢"，好处是绝不阻塞业务，坏处是**丢在你最需要日志的时刻**（流量洪峰）。

取舍很清楚：要么可能阻塞业务，要么可能丢日志。**生产建议 `queueSize` 拉到 8192~16384、`discardingThreshold=0`，然后用监控盯住 appender 的队列长度**——这本身就该是一个指标。

---

## L10 "我的 P99 和监控对不上"：五步归因法

这是可观测性最经典的面试题，也是最容易答散的一题。给一个固定顺序，逐层排除：

| 步骤 | 要问的问题 | 典型差异量级 | 怎么验 |
|---|---|---|---|
| 1. 计时口径 | 计的是哪一段？含不含排队、序列化、网络？ | 3~10 倍 | 看埋点位置 |
| 2. 统计窗口 | 1 分钟瞬时 vs 5 分钟滑动 vs 一小时平均 | 2~5 倍 | 换个窗口再看 |
| 3. 聚合范围 | 单实例 vs 全局（全局要按桶合并） | 1~5 倍 | 看是不是单 pod 异常 |
| 4. 谁是服务端 | 客户端计时 vs 服务端计时 vs 网关计时 | 加一个 RTT | 对比两侧 |
| 5. 采样与桶宽 | 链路采样率、直方图桶宽 | 1.2~2 倍 | 看桶定义 |

**第 1 步是杀伤力最大的一步。** 同一个接口，"我本地测"通常只计 `controller` 方法体；而监控的 `http.server.requests` 计的是从收到请求到写出响应，包含：反序列化 → 拦截器 → 事务开启/提交 → 序列化 → 写回。这中间随便一个 `@Transactional` 的提交、一次 `flush`，就能把 120ms 变成 800ms。第 09 期讲过 `@Transactional` 套远程调用导致连接池打满，本质就是"计时口径不同"——你以为测的是方法，实际测的是等连接。

**第 2 步的坑在"瞬时 vs 窗口"。** 你手测的是 100 次请求的 P99，监控给的是 5 分钟窗口内所有请求的 P99。如果这 5 分钟里有一波突发（比如定时任务撞上来了），那 800ms 是真的，你的 120ms 也是真的——**它们统计的不是同一批请求**。

**第 3 步就是本篇 L1~L3 的内容**：全局 P99 必须靠桶合并，不能靠分位数平均。如果你发现"单实例看都正常，全局就很差"，先怀疑这里。

**第 4 步的差值有名字：网络 RTT + 排队。** 客户端 P99 = 服务端 P99 + RTT + 客户端排队。同机房 RTT 0.5ms，跨可用区 2ms，跨地域 30ms 起。差了 30ms 不一定是服务端的问题。

**第 5 步是"解释器"级别的问题**：链路 1% 采样，你找到的 10 条 trace 不代表性；桶宽 500ms，P99 报 995 也可能只是插值。

面试答题模板（背下来就能用）：

> "先对齐口径——比的都是服务端耗时还是含网络；再对齐窗口——瞬时还是滑动；再看聚合——单实例还是全局，全局必须是桶合并；最后看采样和桶宽。这四层排完，差异一定能归因到某一层，剩下的才是真的性能问题。"

---

## L11 四档回答对照：面试官问"你们怎么做的可观测性"

| 档位 | 回答 | 问题在哪 |
|---|---|---|
| 不及格 | "接了 Prometheus + Grafana + ELK + SkyWalking，都有。" | 报菜名，零信息量 |
| 及格 | "指标看 QPS、RT、错误率，日志用 ELK 查，链路用 SkyWalking 看调用链。" | 说清了工具分工，但没说设计 |
| 良好 | "指标分三层：基础资源、应用 RED、业务自定义；P99 用直方图桶不用客户端分位数；链路做了线程池和 MQ 的上下文透传；日志规范了 traceId 透传和异步 appender。" | 有了具体设计决策 |
| 优秀 | "我先定 SLO，再反推要什么信号。P99 的桶边界就是 SLO 边界（200ms 必须落在桶上）；指标标签做过基数预算，所有标签域乘积控制在 1 万以内，userId 走 exemplar 不进 label；链路用 tail sampling，错误和慢请求 100% 留，正常 1%；日志只保证 traceId 和关键业务字段，其余打结构化 JSON 让采集端裁剪。这套东西的成本我也算过——8 万 series 一次 scrape 5MB，100 个 pod 一天近 3TB 文本，所以标签必须省。" | 从 SLO 倒推、有成本约束、有具体数字 |

"优秀"档的核心不是知道得多，而是**每条设计都能说出代价**。这一点几乎是所有技术面试的通用加分项。

---

## L12 数字速查（本期 20 条）

| 数字 | 含义 |
|---|---|
| 551.5 vs 901.1 | 平均两个 P99（551.5）与真实合并（901.1）的差距，低估 38.8% |
| 76.2% | 按请求数加权平均 P99 的低估幅度（更错） |
| −832% | 平均两个 P50 的偏差（476 vs 51） |
| 4.2% | 直方图桶合并后的 P99 误差（938.6 vs 901.0） |
| 10200 = 10200 | `+Inf` 桶相加后总数与请求数一致，桶可合并的硬证据 |
| 69 | Micrometer `publishPercentileHistogram()` 默认导出的 `le` 桶数 |
| 995 / 599 / 519.8 | 数据全 510ms 时，三套桶算出的 P99（真值 510） |
| 桶宽÷落点值 | 相对误差上限的经验公式 |
| 750 B | 单条 counter series 的客户端内存成本（实测 80,000 条 → 57.4MB） |
| 3.5 KB | 单条 series 的 Prometheus 服务端内存成本（官方经验值） |
| 40 → 80,000 | 加一个 `userId` 标签导致的 series 膨胀（2000 倍） |
| 5.1 MB | 8 万 series 一次 scrape 的文本体积 |
| 2.95 TB/天 | 100 个 pod 都被抓 8 万 series 时的日传输量 |
| 4,000,000 | 10 万 userId × 10 uri × 4 status 的 series 数（服务端约 14GB） |
| 55 字节 | `traceparent` 的固定长度（2+1+32+1+16+1+2） |
| 1100 字节 | 20 跳链路累计的 header 体积 |
| 100,000 | 1% 采样 + 0.1% 错误率下，捞到 1 条错误 trace 所需请求数 |
| 4.5 GB | 30 秒 tail sampling 窗口在途数据（5000 QPS × 20 span × 1.5KB） |
| 218 / 64 / 21 / 9 ns | 裸拼 / 参数化 / 守卫 / 基线的单次日志成本 |
| 13 GB/小时 | 3 万 QPS 下裸拼日志产生的小时级垃圾量 |

---

## L13 十四问答（由浅入深）

**Q1：为什么 P99 不能跨实例求平均？**

因为 P99 是次序统计量，不是线性算子。平均两个 P99 得到的是"两个边界值的中心"，而真实合并 P99 取决于排序后第 99% 个位置落在哪一组数据上。实测：两组合并真实 P99 是 901ms，平均法是 551.5ms（低估 38.8%），按请求数加权是 214.8ms（低估 76.2%）。加权更错，因为权重修正不了"信息已丢失"这个事实——算之前原始分布就被压缩成一个数了。

**Q2：`publishPercentiles` 和 `publishPercentileHistogram` 到底差在哪？**

前者在 JVM 内算好分位数，导出一条带 `quantile` 标签的 series；后者导出 `le` 桶的累计计数。关键差别是**可合并性**：`quantile` 是标签，`sum()` 没法跨实例合并（合并 P50 和 P99 毫无意义）；`le` 桶是普通整数，位置相加即可。另外前者随实例重启清零、受滑动窗口影响，后者是累计计数、趋势连续。生产多副本必须用后者。

**Q3：`histogram_quantile` 的误差从哪来？**

两个来源。一是**桶内均匀分布假设**——落在 `(500,1000]` 桶里时算法只能线性插值。实测数据全在 510ms 时，这个桶报出 995ms，误差 +95%。二是**桶边界没覆盖业务区间**，比如 SLO 是 200ms 但最近桶是 `(100,500]`，199 和 499 在监控上无法区分。经验公式：相对误差上限 ≈ 桶宽 ÷ 落点值。所以桶边界要按 SLO 定制，并且 SLO 值必须正好是桶边界。

**Q4：为什么加一个 `userId` 标签就能打挂 Prometheus？**

因为标签组合数是**乘积**。`uri(10) × status(4) × userId(10万) = 400 万` 条 series。客户端每条约 750 字节（实测），400 万条约 3GB 堆；服务端每条 3~4KB，约 14GB——Prometheus 直接 OOM。同时 scrape 文本体积爆炸：8 万条 series 一次 scrape 就 5.1MB，100 个 pod 一天近 3TB 纯文本。表现是 `scrape timeout`、target DOWN，看起来像服务挂了，其实服务好好的。

**Q5：那要把 traceId 和 userId 关联起来怎么办？exemplar 是什么？**

exemplar 是挂在单个 metric sample 上的 `(traceId, spanId, timestamp)` 标注，**不参与 series 身份计算**。Prometheus 2.26+ 支持，抓取时通过 `OpenMetrics` 格式的 `# {trace_id="..."}` 注释携带。这样你在 Grafana 上看到一个 P99 尖刺，点一下就能跳到那条具体链路——既有指标的低成本，又有链路的可追溯性。这是"指标和链路的缝合点"，也是当前的最佳实践。

**Q6：ThreadLocal 为什么过不去线程池？`InheritableThreadLocal` 为什么"更危险"？**

ThreadLocal 的值存在 `Thread.threadLocals`（一个 `ThreadLocalMap`）里，是**每线程一份**，新线程的表是空的，所以是 `null`。`InheritableThreadLocal` 在 `new Thread()` 时从父线程拷贝，所以第一次能用；但线程池的线程是**复用**的，一辈子只 new 一次，拷贝时机早就过了。实测第二次提交时主线程已经改成 `itl-def456`，池里还是 `itl-abc123`——它给的不是 null，而是**别人的旧值**。null 会空指针，脏值会让日志串链，更难查。

**Q7：`TaskDecorator` 里为什么要在 `finally` 里"还原"而不是 `clear()`？**

因为线程是复用的。你 `clear()` 掉的可能不是自己写进去的东西（上一个任务残留的、框架写的），也可能把外层上下文的残留清成空。正确做法是"进来先存旧值 → 设置快照 → `finally` 还回旧值"，这跟事务的挂起/恢复、`Thread.currentThread().setContextClassLoader` 的用法是同一个模式。判断标准很简单：**这段代码是不是"借用"了一个共享资源？是就要还原。**

**Q8：MQ 消费端要不要"续"上生产者的 trace？**

分语义。同步语义（下单 → 扣库存，同一次业务动作）应该续，parent 设成生产者的 spanId，这样一条 trace 能看到端到端。异步解耦（发通知、算报表、异步补偿）应该**起新 trace + Span Link 关联**，否则一条 trace 会横跨几小时，Jaeger 的时间轴被拉成一条线，什么也看不出来。面试时能主动区分这两种并说出 Span Link，是明显的加分项。

**Q9：`traceparent` 里那 2 位 flags 有什么用？**

最低位是采样标记（`01` = sampled）。它的意义是：**采样决策在入口做一次，沿链路透传**，后续所有节点只看这个位决定记不记，不再各自掷骰子。这样能保证一条 trace 要么全有要么全无（不会出现 A 服务记了、B 服务没记的"半条链路"）。这也是 head sampling 的实现方式——成本最低，但决策时不知道结果，所以错误请求也可能被丢。

**Q10：head sampling 和 tail sampling 怎么选？tail 的成本怎么算？**

head 零成本、无状态，但按比例采样，而故障按稀有度分布——1% 采样 + 0.1% 错误率，要 10 万请求才捞到 1 条错误 trace。tail 在 collector 攒窗口按结果决定（错误全留、慢请求全留），代价是要缓存整条链路：30 秒窗口 × 5000 QPS × 20 span × 1.5KB ≈ **4.5GB 在途数据**。所以 tail sampling 必须按 traceId 哈希分片部署，否则每个实例都缓存全量。生产标准答案是组合策略，不是二选一。

**Q11：为什么 Jaeger 里的 trace 数量比监控上的 QPS 少那么多？**

采样率。链路可能只采 1%，指标是 100% 统计。QPS 1000 只留 10 条 trace 是正常的。这也是"用链路数量估算流量"必然错的原因。反过来也一样：**不能用 trace 算 QPS，也不能用 metric 找单条请求**，两种信号的分辨率不同。

**Q12：异步 appender 会丢日志吗？**

会，而且默认配置下就会。logback 的 `AsyncAppender` 默认 `queueSize=256`、`discardingThreshold=20%`，意思是**队列用到 80% 时，INFO 和 DEBUG 会被无声丢弃**。`neverBlock=true` 则是"队列满就丢"。两种设计的取舍都是"宁可丢日志也不阻塞业务"，但代价是**丢在你最需要日志的时刻**。生产要么把 `queueSize` 拉到 8192 以上并 `discardingThreshold=0`（可能阻塞），要么接受丢弃但**把队列长度做成指标告警**——丢弃必须是可观测的，不能是静默的。

**Q13：虚拟线程场景下，ThreadLocal 和 ScopedValue 怎么选？**

虚拟线程可以到百万级，每个线程复制一份 ThreadLocal 是实打实的内存（100 万 × 1KB ≈ 1GB）。`ScopedValue`（JEP 506，JDK 25 正式）是**不可变、按作用域绑定**的，没有"每线程复制"，用完自动失效，天然适合虚拟线程 + 结构化并发。但它不可变——过程中要改值的场景（比如累加、动态追加）还是得用 ThreadLocal 或显式传递。判断标准：**只读的请求上下文用 ScopedValue，需要可变状态的用 ThreadLocal 并严格控制生命周期。**

**Q14：日志打多了真的会拖慢 CPU 吗？**

CPU 层面几乎不会。实测裸拼字符串（最贵写法）也就 217.8ns/次，3 万 QPS 是 6.5ms/s，**0.65% 单核**。真正的成本在三处：① 内存分配——裸拼一次约 120B 垃圾，3 万 QPS 约 13GB/小时，年轻代 GC 频率上升，P99 抖动；② 同步 IO——磁盘抖动时业务线程跟着阻塞；③ 磁盘——单行 500B × 3 万 QPS = 15MB/s ≈ 1.3TB/天，普通云盘三天写满。所以优化日志不该盯 CPU，该盯**分配率、appender 队列、磁盘带宽**。

---

## L14 三道模拟面试题（含四档对照）

### 题一：P99 对不上（排查类）

> 线上订单接口 P99 从 120ms 涨到 800ms，Grafana 上所有实例一起涨。但你本地用 JMeter 压同一份代码，P99 稳定 120ms。给你 30 分钟，怎么定位？

**不及格**："先看 CPU、内存、GC，然后看慢 SQL，再看是不是流量涨了。"——全是动作，没有假设，30 分钟能翻完所有面板然后什么也没结论。

**及格**："按 CPU → GC → 慢 SQL → 下游依赖 → 连接池的顺序查一遍。"——有顺序，但还是"猜"，而且没解释"为什么本地不复现"这个最关键的线索。

**良好**："本地不复现说明差异在环境或口径。先对齐口径：本地压的是 controller 方法，监控计的是整个 HTTP 生命周期，含反序列化、事务提交、序列化。然后看是不是所有实例真的都涨——如果是，排除单机问题（GC、磁盘、宿主机）；重点看下游依赖的 RT 和连接池等待时间。"

**优秀**：在上面基础上补三层——

1. **先验口径差**：用 `http.server.requests` 的 `uri` 标签确认统计的是同一批请求（有没有混进别的接口）；对比"客户端计时"和"服务端计时"，差值就是网络 + 排队。
2. **再验全局 P99 的算法**：确认 dashboard 是 `histogram_quantile(0.99, sum by (le)(rate(...)))` 而不是 `avg(...)`。如果是后者，而恰好有一台机器在跑定时任务（B 实例效应），全局 P99 会被一台机器绑架——这正好能解释"所有实例一起涨"的假象。
3. **然后看时间维度**：把窗口从 5 分钟切到 1 分钟，看是不是突发尖刺被平均进了窗口。同时看 `hikaricp_connections_pending`——连接池等待是典型的"方法体很快但端到端很慢"。
4. **最后给验证动作**：在入口和出口各埋一个 timer（`order.controller` / `order.service` / `order.dao` 三段），把 800ms 拆到具体段。**这一步才是真正的定位，前面的都是缩小范围。**

### 题二：从零设计可观测性（设计类）

> 日订单 500 万，20 个微服务，SLO 是 P99 低于 300ms、可用性 99.95%。设计一套可观测性方案，说清指标、链路、日志，以及成本。

**不及格**："上 Prometheus + Grafana + SkyWalking + ELK。"——没有一条和 500 万订单、20 个服务、300ms 有关系。

**及格**："每个服务埋 RED 指标（Rate/Errors/Duration），链路用 SkyWalking，日志进 ELK 并带 traceId。"——方向对，但没算账，也没处理采样和多实例聚合。

**良好**：能说出——指标用 Micrometer 自动埋 `http.server.requests`，P99 用直方图并且桶边界对齐 300ms（含 100/200/300/500/1000）；链路全量采样在 20 个服务下扛不住，用 tail sampling，错误和 >300ms 的请求 100% 留；日志只打结构化 JSON，强制带 traceId/orderId，异步 appender。

**优秀**：把账算出来——

1. **指标量**：20 服务 × 平均 30 个接口 × 4 个 status × 15 个桶 ≈ 3.6 万 series，客户端约 27MB（750B/条），服务端约 126MB（3.5KB/条），可接受。**明确设定"任何标签域乘积低于 1 万"的预算线**，userId/orderId 一律走 exemplar。
2. **链路量**：500 万订单/天 ≈ 平均 58 QPS，峰值按 10 倍算 580 QPS。若 20 个 span/链路、每 span 1.5KB，全量 = 580 × 20 × 1.5KB ≈ 17MB/s，一天 1.5TB——扛不住。所以 tail sampling 只留"错误 + 慢请求 + 1% 正常"，实际存储降到约 1/50。
3. **日志量**：只打状态变更点（下单、支付、发货）而非每步，单订单 5 行、每行 400B → 500 万 × 2KB = 10GB/天，可接受。
4. **SLO 落地**：99.95% 一个月允许 21.6 分钟不可用，用 1 分钟粒度的错误预算燃烧率告警（1h 窗口烧 14.4 倍 → 立即告警；6h 窗口烧 6 倍 → 工单）。
5. **反过来验**：用这套数据能不能回答"昨天 14:00 那波 P99 尖刺是什么原因"——能，因为错误/慢请求 100% 有链路，链路里有 span 耗时分解。

### 题三：监控自己挂了（故障类）

> 大促前 Prometheus 内存从 8GB 涨到 30GB，多个 target 报 `context deadline exceeded` 然后 DOWN。但服务本身 QPS、错误率都正常。怎么定位和修复？

**不及格**："扩容 Prometheus，加内存。"——治症状，下次还会炸，而且大促前扩容审批走不完。

**及格**："查 `prometheus_tsdb_head_series` 看 series 数是不是暴涨，找出是哪个指标贡献的。"——方向对，但没到根因（为什么暴涨）。

**良好**："先确认是基数问题不是样本量问题——`head_series` 暴涨而 QPS 没变，就是标签爆炸。用 `/api/v1/status/tsdb` 的 `seriesCountByMetricName` 排序，找 top 指标；如果是某个自定义指标，检查最近有没有加标签（userId、orderId、原始 URL、异常 message）。多实例确认：每个 pod 的 series 数应该接近，如果某个 pod 特别多，说明数据分布不均（比如分片键设计问题）。"

**优秀**：在上面基础上给出完整的"定位—止血—根治—预防"四段——

1. **止血（5 分钟）**：在抓取侧加 `metric_relabel_configs` 直接 drop 掉高基数指标或该标签，配置热加载不用重启。这一步先让 Prometheus 活过来，比查根因优先。
2. **定位（10 分钟）**：`seriesCountByMetricName` 找 top，`seriesCountByLabelValuePair` 定位是哪个标签的哪个值。注意看**是不是某个值独占了几十万条**（比如某个 `uri` 标签被写成了原始路径，`/order/20241113001` 这种）。
3. **根治**：代码侧把标签改成模板化（`/order/{id}`），ID 类信息移到 exemplar 或日志；同时把"基数预算"写进 code review checklist——**新增标签必须说明取值域上限**。
4. **预防**：给 Prometheus 加 `prometheus_tsdb_head_series` 的容量告警（阈值定在实测容量的 70%），并给 scrape 加 `sample_limit` / `label_limit`，超限直接拒抓——**让爆炸在抓取端就被拦住，而不是等它把内存吃光**。

最后补一句成本视角：「30GB 内存对应大约 800 万 series，这已经超出单机 Prometheus 的舒适区。真要长期跑，得上分片（按 job 或 hashmod）或者换 VictoriaMetrics / Mimir 这类针对高基数优化的存储。」——这一句能把答案从"修好了"抬到"架构层面想过了"。

---

## L15 二十八期地图（收束）

| 板块 | 期号 | 一句话抓手 |
|---|---|---|
| 集合 | 01 / 14 | HashMap 寻址与树化；1.5 倍扩容与 CHM 锁粒度 |
| JVM | 02 / 12 / 21 / 22 | 分代与三色标记；参数从除法推；泄漏看支配树；JIT 让压测变脸 |
| 并发 | 03 / 20 | 锁升级 + AQS；ctl 位运算与依赖栈 |
| 语言底层 | 13 / 23 / 26 | 双亲委派四次打破；擦除与桥方法；agent 改字节码 |
| 框架 | 04 / 15 / 24 | refresh 十二步；自动配置六阶段；MyBatis 九站链 |
| 存储 | 05 / 16 / 25 | B+ 树三层与 MVCC；分片键四炸点；倒排三层与段不可变 |
| 中间件 | 06 / 07 / 17 / 19 | 单线程为什么快；零拷贝与 ISR；缓存一致性算账；epoll 与 Pipeline |
| 分布式 | 08 / 10 / 18 | 注册发现三级缓存；秒杀四道闸门；幂等键由意图决定 |
| 工程 | 09 / 11 / 27 / 28 | 三分钟分诊；策略替 if-else；类身份=loader+类名；**本篇：信号的成本** |

28 期下来，一条主线越来越清楚：**面试的区分度不在"知道多少名词"，而在"能不能把一个定性判断换算成一个数"。**

这期最典型的三处：P99 不可平均 → 551.5 vs 901.1；一条 userId → 40 变 80,000 条 series、5.1MB 一次 scrape；日志"很慢" → 其实只占 0.65% 单核，真正的账在 13GB/小时垃圾和 1.3TB/天磁盘。

**下一期预告**：第 29 期讲 **分布式事务与一致性协议再深入**——不是第 07 期讲过的 2PC/TCC/SAGA 方案对比，而是往下挖：Raft 的日志复制与选主、etcd 的 lease 与 watch 可靠性、以及"为什么你的分布式锁在 GC 停顿后会失效两次"。这一期会把 07 期的"方案层"接到"协议层"上。

---

*本期全部实验代码在本机 JDK 21.0.7 实跑，依赖 Micrometer 1.16.7、Prometheus client 1.4.3、logback 1.5.38。文中标注"实测"的数字均为真跑结果，标注"经验值/官方"的已注明来源。*
