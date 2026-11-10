# Java 面试深水区 12 · JVM 调优与性能压测：从背参数到能算数

> "你调过 JVM 吗？"这道题有 90% 的人会答成背参数表：-Xms 等于 -Xmx、新生代占 1/3、G1 是 JDK9 默认。面试官听完只会点头，然后给你一个 8G 堆、200ms 目标的场景，让你把 Eden 算出来。这一期把你从"背参数"拽到"能算数"——每一个参数背后都有一个除法，算不出来就说明你没真调过。
>
> 10 层递进 · 9 张原理图 · 14 条高频问答 · 3 道模拟面试

---

## L0 调优的真实边界：先把锅分清楚

### 线上"接口变慢"的根因分布

```
慢 SQL / 无索引      ████████████████████████████████████ 35%
锁竞争 / 串行化      ████████████████████ 20%
GC 停顿 / 内存压力   ███████████████ 15%
线程池 / 连接池耗尽  ███████████████ 15%
下游依赖变慢         ██████████ 10%
JVM 参数本身         █████ 5%
```

这张图的作用不是精确统计，而是让人闭嘴：开口前先问一句"瓶颈在哪一层"。

**线上性能问题里，JVM 参数能解决的不到 10%。** 剩下 90% 是代码和架构的锅。你张嘴就说"我加大堆内存"，等于告诉面试官：我只会拧一个旋钮。

### 调优四问：答不上来就别碰参数

1. **目标是什么？** 吞吐（每秒处理多少个）还是延迟（P99 多少毫秒）？两者常常互斥——Parallel 吞吐高，ZGC 延迟低，选错方向后面全白干。
2. **现在的数是多少？** 没有 baseline 的调优叫瞎改。至少要三个数：Young GC 间隔、单次停顿、Old 增长速度。
3. **钱花在谁身上？** 用 jstat / GC 日志算出"分配速率"和"晋升速率"，这两个数决定 Eden 和 Old 的大小，而不是"新生代占 1/3"这种祖传口诀。
4. **改完怎么证明有效？** 同一份压测脚本、同一批数据、同一个时长，跑三轮取中位数。改一个参数跑一次，别一次改五个——那样你永远不知道是谁起的作用。

**一句话答法：** 我的调优顺序是「先量再调」：先用 GC 日志和 jstat 拿到分配速率、晋升速率、停顿分布三个数，再判断瓶颈是分配太快、晋升太快、还是单次存活对象太多，最后才动参数。绝大多数情况下结论是"改代码"，不是"改 JVM"。

### 停顿预算：一个必须先算的除法

面试官问"GC 停顿多少算合格"，答"越小越好"等于没答。行业里比较通用的口径是：

- **暂停时间 < 目标 RT 的 1/10**。目标 RT 200ms → GC 单次停顿应控制在 20ms 以内。因为一次停顿会让当时在途的所有请求全部 +停顿时间。
- **停顿总量 < 总运行时间的 1%**（延迟敏感型）或 < 5%（吞吐型）。一天 86400 秒，1% 就是 864 秒，看起来很多——但它分散在几万次 GC 里，每次十几毫秒，用户感知不到。

把这两个数写在白板上，后面所有参数都围绕它们服务。

---

## L1 对象内存账本：一个对象到底吃多少字节

这是最容易被跳过、又最能一招致命的基本功。面试官让你估"100 万条记录放 HashMap 要多少内存"，你答"大概几十兆吧"——面试就到此为止了。精确到字节，气场完全不同。

### 64 位 HotSpot 的对象头：12 字节

```
Integer        [ Mark Word 8B ][ Klass 4B ][ value 4B ]            = 16B
Long           [ Mark Word 8B ][ Klass 4B ][ value 8B ][pad 4B]    = 24B
空对象 Object  [ Mark Word 8B ][ Klass 4B ][ pad 4B ]              = 16B
```

记住这三个数：**头 12B、引用 4B（压缩指针）、对齐 8B**。剩下的全靠加法和向上取整。

- 对齐规则：对象起始地址必须 8 的整数倍，所以任何一个对象的实际大小都是 8 的倍数。
- 一个 Integer 引用链 = 4B（引用）+ 16B（对象）= 20B，而一个 int 只要 4B。这不是"稍微多一点"，是 5 倍。
- 1000 万个数字，`int[]` 40MB，`List<Integer>` 至少 200MB。

### 用 JOL 验证，而不是靠猜

```xml
<dependency>
  <groupId>org.openjdk.jol</groupId>
  <artifactId>jol-core</artifactId>
  <version>0.17</version>
</dependency>
```

```java
import org.openjdk.jol.info.ClassLayout;

public class SizeProbe {
    static class Node<K, V> {          // 模仿 HashMap.Node
        final int hash;
        final K key;
        V value;
        Node<K, V> next;
    }

    public static void main(String[] args) {
        System.out.println(ClassLayout.parseInstance(new Integer(1)).toPrintable());
        System.out.println(ClassLayout.parseInstance(new Node<String, String>()).toPrintable());
        System.out.println(ClassLayout.parseInstance(new int[10]).toPrintable());
    }
}
```

输出会告诉你：Node 是 **32 字节**（12 头 + 4 hash + 4 key + 4 value + 4 next = 28，补到 32）。int[10] 是 **56 字节**（16 头 + 40 数据，数组头多了 4B 长度字段）。

### 100 万条数据，三种存法的账

| 存法 | 单条成本 | 100 万条合计 | 什么时候用 |
| --- | --- | --- | --- |
| int[] / long[] | 4B / 8B | 4MB / 8MB | ID 列表、时间序列，能用就用 |
| `ArrayList<Integer>` | 4B 引用 + 16B 对象 | 约 26MB（含数组扩容冗余） | 需要对象语义但不需要键值 |
| `HashMap<Integer,Integer>` | 32B Node + 4B 槽位 | 约 40MB（2^21 槽位）+ 装箱 32MB | 真的要键值查找 |
| `HashMap<Long,Long>` | 32B Node + 24B×2 装箱 | 约 90MB | 能换 long 就换 |

**踩过的坑：** 一个"用户标签缓存"用了 `Map<Long, List<Long>>` 存 200 万用户，堆里吃掉 1.2G，YGC 从 3 秒一次变成 0.3 秒一次，P99 直接顶到 400ms。换成长整型数组 + 开放寻址的自研结构后降到 90MB，YGC 间隔回到 8 秒。参数一个没动，问题"消失"了——这才是真实世界的调优。

**回答模板：** "估内存我先算对象头：64 位压缩指针下 mark word 8 字节加 klass 4 字节，共 12，再按 8 字节对齐。一个 Integer 是 16 字节，HashMap 的 Node 是 32 字节，所以 100 万条 Long→Long 的 Map 光 Node 就 32MB，加上 Long 装箱的 48MB，实测接近 90MB。这种题我一般会先用 JOL 打一遍确认，不靠背。"

---

## L2 分代参数推导：每个参数背后都有一个除法

### 核心公式（背下来，面试直接写白板）

```
Eden ≈ 分配速率 × 目标 YGC 间隔
Old 增速 ≈ 分配速率 × 晋升率
YGC 间隔 = Eden ÷ 分配速率
```

"新生代占堆的 1/3"是 -XX:NewRatio=2 的默认值，不是物理定律。真正决定 Eden 大小的是**分配速率**和**你能忍受多长的 YGC 间隔**。

### 推导算例：8G 堆 / 200MB/s 分配 / 目标 10 秒一次 YGC

```
[      Eden 2G        ][S0][S1][        Old 5.5G          ]
       ↑ 分配 200MB/s              ↑ 晋升 20MB/s（存活率 10%）

① Eden = 200MB/s × 10s      = 2048MB
② New  = Eden × 1.25        = 2.5G（S 各 256M）
③ Old  = 8G − 2.5G          = 5.5G
④ 晋升 = 200MB/s × 10%      = 20MB/s → Old 撑 275 秒一轮 Mixed

参数落地：-Xmn2560m -XX:SurvivorRatio=8
```

- YGC 间隔 = Eden ÷ 分配速率 = 2048MB ÷ 200MB/s ≈ 10.2 秒
- 单次 YGC 停顿 ≈ 存活对象拷贝量 ÷ 拷贝带宽，8G 堆量级通常 8~20ms
- 若存活率涨到 30%（缓存吃满）→ 晋升 60MB/s → Old 撑 92 秒就要 Mixed

**两个口误高发点：**

- SurvivorRatio=8 是「Eden : 单个 Survivor」= 8:1，不是 Eden 占 8/10。
- -Xmn 与 -XX:MaxNewSize 同时写会打架；G1 下不要用固定 -Xmn，会锁死停顿自适应。

### 动态年龄判定：Survivor 装不下就提前晋升

对象在 Eden 出生，YGC 时还活着就进 Survivor，每熬过一次年龄 +1，默认 -XX:MaxTenuringThreshold=15 后晋升 Old。但真实规则里有一条隐藏条款，面试常考：

> **如果 Survivor 中「年龄 ≤ N 的所有对象总和」超过了 Survivor 空间的一半，那么年龄 ≥ N 的对象本次直接晋升 Old**，不用等 15 岁。

这就是"动态年龄判定"，目的是防止 Survivor 被撑爆。副作用很隐蔽：某个接口突然批量加载了一批 3 秒生命周期的对象，Survivor 被塞到 55%，这批对象立刻全部晋升 Old，Old 增速从 20MB/s 跳到 200MB/s，Mixed GC 从 5 分钟一次变成 30 秒一次。**现象是"GC 变频繁"，根因是"某段代码的临时对象生命周期正好卡在 Survivor 容量线上"。**

```
# 观察年龄分布（JDK9+），直接看对象在哪个年龄被晋升
-Xlog:gc+age=trace:file=age.log:time,uptime,level,tags

# 输出片段解读
Desired survivor size 33554432 bytes, new threshold 3 (max 15)
- age   1:   41234560 bytes,   41234560 total
- age   2:   20114688 bytes,   61349248 total
- age   3:    5140224 bytes,   66493280 total
# 阈值从 15 掉到 3，说明 age<=3 的总和已超过 survivor 的一半(16MB)
# → 所有 3 岁以上的对象本轮全部晋升
```

### 三种部署形态的参数模板

| 形态 | 关键参数 | 为什么 |
| --- | --- | --- |
| 吞吐优先（批处理、报表） | -XX:+UseParallelGC -Xmn4g -XX:SurvivorRatio=8 -XX:MaxGCPauseMillis=500 | Parallel 没有并发标记开销，吞吐最高；能接受秒级停顿 |
| 延迟优先（在线 API） | -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:G1NewSizePercent=20 -XX:G1MaxNewSizePercent=50 | G1 靠动态调整 Young 大小逼近停顿目标，别写死 -Xmn |
| 极低延迟（交易、竞价） | -XX:+UseZGC -XX:+ZGenerational（JDK21+）-XX:SoftMaxHeapSize=8g -Xmx12g | ZGC 停顿 <1ms，代价是吞吐降 5~15% |

**MaxGCPauseMillis 是"软目标"。** 它是 G1 的期望值，不是保证值——G1 通过减少每次回收的 region 数来逼近它，如果堆里全是存活对象，它也只能硬着头皮扫完然后超时。所以这个值设成 5 不会让停顿变 5ms，只会让 G1 每次只收 2 个 region，然后 YGC 频率暴涨、CPU 打满。设成目标 RT 的 1/10 是合理区间。

---

## L3 GC 日志逐字段：会读日志才算真调过

### 先把日志打开（JDK9+ 统一日志框架）

```
# 生产环境推荐配置：按时间滚动，保留 5 个 50MB 文件
-Xlog:gc*,gc+heap=debug,gc+age=trace,safepoint:file=/var/log/app/gc.log:time,uptime,level,tags:filecount=5,filesize=50m

# JDK8 的写法（老项目还会遇到）
-XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintTenuringDistribution
-Xloggc:/var/log/app/gc.log -XX:+UseGCLogFileRotation
-XX:NumberOfGCLogFiles=5 -XX:GCLogFileSize=50M
```

### 逐字段拆一条 G1 的 YGC 日志

```
[2026-11-09T09:12:33.481+0800][info][gc] GC(142) Pause Young (Normal) (G1 Evacuation Pause) 1024M->186M(4096M) 12.487ms
 |                              |    |    |       |        |            |         |              |          |
 |                              |    |    |       |        |            |         |              |          +- ④ 本次停顿 12.5ms
 |                              |    |    |       |        |            |         |              +- ③ 堆总容量 4096M
 |                              |    |    |       |        |            |         +- ② 回收后占用 186M
 |                              |    |    |       |        |            +- ① 回收前占用 1024M
 |                              |    |    |       |        +- 具体动作：Evacuation = 拷贝存活对象
 |                              |    |    |        +- 触发原因：Normal / Concurrent Start / Mixed
 |                              |    |    +- 停顿类型：Pause Young / Pause Full
 |                              |    +- GC 序号，142 次，用来算频率
 |                              +- 日志级别
 +- 绝对时间戳，和业务监控对齐用
```

**最关键的一个数：1024M → 186M。** 差值 838M 是"本次回收掉多少"，而 **186M 是"活下来的"**。连续看 10 条日志，如果 186M 这个数在单调上涨（200M、260M、340M……），不用怀疑，要么内存泄漏，要么晋升太快。这才是 GC 日志的第一价值。

### 四种日志形态 = 四种状态

| 日志片段 | 含义 | 该紧张吗 |
| --- | --- | --- |
| Pause Young (Normal) (G1 Evacuation Pause) 1024M->186M(4096M) 12ms | 普通 YGC，只扫年轻代 region | 正常，看频率和停顿 |
| Pause Young (Concurrent Start) + Concurrent Mark Cycle | 老年代占用超阈值（默认 45%），启动并发标记 | 关注，IHOP 可调 |
| Pause Young (Mixed) 2048M->512M(4096M) 38ms | 混合回收，同时清理年轻代 + 部分老年代 | 正常，停顿通常 30~80ms |
| Pause Full (G1 Compaction Pause) 3900M->800M 4521ms | 兜底全量回收，整堆压缩 | 红色警报，4.5 秒全停 |

### 两个一听就该坐直的关键词

- **to-space exhausted**：Survivor 满了，存活对象没地方放，晋升也失败——这是 Full GC 最常见的直接原因。出现它说明"存活对象总量 > Survivor 容量"，要么调大 Survivor，要么减少短命大对象。
- **Evacuation Failure**：拷贝目标 region 已经被占满，拷贝失败，只能回退做 Full GC。G1 里这两个词经常成对出现，看到它们基本可以断定：老年代压力已经溢出到年轻代了。

### G1 的堆：2048 个等大 region

```
E E E E E S O O H     E=Eden  S=Survivor(两块交替)
E E E E E E O O H     O=Old   H=Humongous（大对象独占）
E E E E · · O · ·     ·=Free（已回收，可重新分配）
O O O · · · · · ·
· · · · · · · · ·
```

- G1 把堆切成约 2048 个等大 region（8G 堆 → 每块 4MB），大小由堆 / 2048 取 2 的幂。
- 不再物理分代，逻辑分代；每次只挑垃圾最多的那几个 region 回收，不碰没必要的。
- 对象 > region 一半即 Humongous（4MB region 对应 2MB 以上对象）：占连续整块，且只在大周期才回收——这是"Full GC 莫名反复"的头号嫌疑。

**加分回答：** "我看 GC 日志第一眼不看停顿，看 1024M->186M 里的后一个数。因为它代表存活集大小，是判断内存泄漏最直接的证据。如果存活集在 2 小时内从 186M 涨到 1.2G，那和 GC 参数一点关系都没有，得去 dump 堆找引用链。"

---

## L4 四种病与处方：对症才能下药

GC 问题只有四类，其余都是它们的变种。面试官描述一个现象，你要能三步内落到这四个格子里：**太频繁 / 停太久 / 兜底了 / 根本不是 GC**。第四类最容易翻车——多少人拿着 JVM 参数猛调，最后发现是日志框架同步刷盘。

### 四格判别表

| 现象 | 判据（拿什么数确认） | 处方（按优先级） |
| --- | --- | --- |
| ① 太频繁：YGC 每 1~2 秒一次，P99 规律尖刺 | 分配速率 ≫ Eden ÷ 秒；jstat -gcutil 1000 看 EU 涨速 | 先砍临时对象，再调大 Eden（顺序反了就是掩盖代码问题） |
| ② 停太久：Mixed > 200ms，to-space exhausted | 存活集 > 可用 Old 空间；jmap -histo:live 看 Top10 | 扩容 / 提高 IHOP / 换 ZGC；G1HeapWastePercent 5→10 |
| ③ 兜底了：Pause Full 每几分钟一次 | Humongous / MetaSpace / 显式调用；看 Full 前最后一条日志 | 拆大数组 / 限 MetaSpace / +DisableExplicitGC |
| ④ 根本不是 GC：平均 RT 正常，P99 长尾难看 | TTSP 超 100ms / 线程全阻塞；-Xlog:safepoint | 查 Safepoint 轮询、同步 IO、锁；改参数 100% 无效 |

四格之外还有一个万能判据：**把 GC 停顿总和除以总运行时长**。低于 1% 就别再折腾 JVM 了，去查代码。

### 病① YGC 太频繁：先问"分配了什么"

```
# 一秒一次采样，重点看 EC(eden容量) / EU(eden已用) / YGC / YGCT
jstat -gcutil <pid> 1000

  S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT     GCT
  0.00  62.50  98.20  31.44  94.12  91.80  18234  182.34    0     0.00  182.34
                ↑Eden 已用 98%       ↑ 18234 次 YGC，累计停顿 182 秒

# 换算：182 秒 ÷ 18234 次 = 每次 10ms，单次没问题
# 但若服务跑了 6 小时(21600s)：18234 ÷ 21600 ≈ 0.84 次/秒 —— 太频繁了
```

0.84 次/秒意味着**每 1.2 秒全应用停 10ms**。单看每次不算事，但它把 P99 从 45ms 顶到 90ms——因为任何一个请求有 10% 的概率正好撞上停顿。

处方顺序（这个顺序本身就是考点）：

1. 先查是不是在循环里 new 对象、字符串拼接用 +、日志里 JSON.toJSONString() 无脑打全量对象、Stream 装箱。
2. 再查是不是缓存没设上限，把 DB 数据一股脑塞本地 Map。
3. 最后才是调大 Eden（-Xmn 或 -XX:G1NewSizePercent）。

**反模式：** "YGC 频繁 → 直接 -Xmn 翻倍"。Eden 翻倍后 YGC 间隔确实翻倍了，但单次停顿也翻倍（要拷贝的存活对象更多），而且 Old 变小、Mixed 变频繁。整体停顿时间可能一点没降。先减分配，再调容量，这条顺序答出来就是加分项。

### 病② Mixed 太长 / to-space exhausted

```
# 典型病态日志
[info][gc] GC(2101) Pause Young (Mixed) (G1 Evacuation Pause) 3712M->2840M(4096M) 412.882ms
[info][gc] GC(2102) To-space exhausted
[info][gc] GC(2102) Pause Young (Mixed) (G1 Evacuation Pause) 3900M->3100M(4096M) 688.311ms
[info][gc] GC(2103) Pause Full (G1 Compaction Pause) 3900M->2980M(4096M) 5210.774ms

# 读出来的三件事：
# 1. 回收前后只差 800M —— 存活集 2.98G，占堆 73%，G1 已经没什么可收的了
# 2. To-space exhausted —— 存活对象没地方放，拷贝失败
# 3. 最后 Fallback 到 Full GC，5.2 秒
```

这不是"参数没调好"，这是**堆不够用**或者**真泄漏**。区分方法很干脆：重启后观察存活集是否持续单调上涨。涨 → 泄漏，去 dump；不涨但贴着上限 → 扩容或换 ZGC。

如果确认不是泄漏、只是堆太紧，可调的两个旋钮：

- -XX:InitiatingHeapOccupancyPercent=35（默认 45）：更早启动并发标记，代价是 CPU 占用上升，收益是避免"标记没做完堆就满了"。
- -XX:G1HeapWastePercent=10（默认 5）：允许更多"垃圾占比不足"的 region 不回收，减少 Mixed 次数，换更长的停顿预算给每次回收。

### 病③ Full GC 反复：三个惯犯

| 惯犯 | 特征 | 处置 |
| --- | --- | --- |
| Humongous 大对象（一次查 50 万条、Excel 导入、大 JSON） | 对象 > region 50%，直接进 Humongous region，只在大周期才回收，且容易产生碎片 | 流式处理（游标分批）、限制单次返回条数、大 byte[] 换 DirectBuffer 或落盘 |
| MetaSpace 膨胀（动态代理、热部署、CGLIB 狂生成类） | jstat -gc 里 M 列单调上涨到 90%+ | 设 -XX:MaxMetaspaceSize=512m 让它早点报 OOM；用 -verbose:class 看谁在造类 |
| 显式 System.gc()（老代码、某些 RPC 框架的"内存保护"） | Full GC 时间点毫无规律，和业务量无关；堆占用很低也触发 | -XX:+DisableExplicitGC，同时排查 NIO 直接内存的清理依赖 |

### 病④ 没 GC 也慢：Safepoint 这个隐形杀手

JVM 要做 GC、要撤销偏向锁、要 dump 线程、要做 JIT 去优化，都必须让所有线程跑到"安全点"停下来。这个等待时间叫 **TTSP（Time To Safepoint）**，它**不计入 GC 停顿**，但用户感知是一样的。

```
-Xlog:safepoint

[info][safepoint] Safepoint "G1CollectForAllocation", Reaching safepoint: 183421 ns, At safepoint: 12003456 ns
                                                        ↑ 0.18ms 正常              ↑ 这是 GC 本身
[info][safepoint] Safepoint "Cleanup", Reaching safepoint: 41288231 ns, At safepoint: 88012 ns
                                        ↑ 41ms！所有线程等一个线程到安全点
```

典型原因：某个线程卡在 counted loop 里没插入 poll（编译器 bug 或极端长循环）、大量线程在做 System.arraycopy 大拷贝、或者线程处于 native 状态迟迟不返回。另一种常见情况是 **JIT 去优化**：某方法被判定为热点编译后，发现假设失效（比如新加载了一个子类），整段退回解释执行，你会看到 P99 每隔几分钟抖一下，GC 日志干干净净。

**高分句式：** "如果 GC 日志里停顿总量只占总运行时间的 0.3%，但 P99 很难看，我会先排除 GC，去看 safepoint 日志和线程栈。因为很多'GC 问题'其实是同步日志刷盘、synchronized 大对象、或者连接池拿不到连接在等——这些调 JVM 参数一点用都没有。"

---

## L5 容器内存账本：90% 的 OOMKilled 不是堆的锅

### -Xmx4g 跑在 4G 的 Pod 里，一定被杀

容器的 memory limit 是给整个进程的，包括堆、元空间、线程栈、直接内存、CodeCache、GC 自身的数据结构、以及 JVM 之外的 native 分配。你只给堆留 4G，剩下的一切都在抢同一块内存。

```
4 GiB = 4096 MB limit（1px ≈ 6.8MB）
[      堆 2.4G (60%)      ][元256M][栈200M][Direct300M][CC240M][余量640M]
                                                              ↑ 15.6%

线程栈是最容易被忽略的变量：Tomcat maxThreads=200 × 1MB ≈ 200MB
如果把 maxThreads 调到 1000，光线程栈就吃掉 1G，堆却一点没多。
```

| 组成 | 典型值 | 参数 |
| --- | --- | --- |
| 堆 | limit × 60% | -Xmx / MaxRAMPercentage |
| 元空间 | 256~512M | -XX:MaxMetaspaceSize |
| 线程栈 | 线程数 × 1M | -Xss |
| 直接内存 | 300~500M | -XX:MaxDirectMemorySize |
| CodeCache | 240M | -XX:ReservedCodeCacheSize |
| 余量 | 15~20% | 内核页缓存、GC 自身结构 |

**超出 limit 的后果：内核 OOMKiller 直接 SIGKILL，容器 exit 137。** 日志里连 OutOfMemoryError 都没有，只有一句 "Killed" —— 这是最坑的一种"OOM"。排查方式：docker inspect 看 OOMKilled=true，或 kubectl describe pod 看 Last State: OOMKilled / Exit Code: 137。

### 参数怎么写才对（JDK 10+ 推荐写法）

```
# 写法一：按比例给堆，不写死绝对值 —— 换规格时不用改配置
-XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=60.0

# 写法二：显式定住，运维看得懂
-Xms2400m -Xmx2400m -XX:MaxMetaspaceSize=256m -Xss1m
-XX:MaxDirectMemorySize=300m -XX:ReservedCodeCacheSize=240m
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/dump

# 关键：Xms 与 Xmx 必须相等，避免堆动态伸缩
# 原因：堆扩缩会触发 Full GC（G1 的 MinHeapFreeRatio 逻辑），
#       而且操作系统层面的页表与 THP 行为会抖，延迟不可控
```

**JDK 版本陷阱：** JDK 8u191 之前，JVM 读不到 cgroup 的 memory limit，会按宿主机的物理内存算默认堆——一台 64G 的机器上，容器限 4G，JVM 默认堆算出 16G，进程一启动就被 OOMKilled。-XX:+UseContainerSupport 在 8u191+ 默认开启；更老的版本要手工加 -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap。

### CPU limit 造成的假 GC 问题

比内存更隐蔽的是 CPU。K8s 里 limits.cpu: 2 表示"最多用 2 核的时间片"，但 JVM 在 JDK 8u191 之前读的是宿主机的核数。于是：

- G1 按 64 核算出 ParallelGCThreads≈50，一开 GC 就是 50 个线程抢 2 核的时间片。
- ForkJoinPool.commonPool 按 63 个并行度创建，parallelStream() 一跑就把 CPU 配额打满。
- 结果：CPU throttling，应用每 100ms 周期里被强制停 60ms，表现为"GC 停顿不长但接口就是慢"。

```
# 显式告诉 JVM 真实核数（微服务容器里强烈建议写）
-XX:ActiveProcessorCount=2

# 验证：容器内 jvm 看到的核数
java -XX:+PrintFlagsFinal -version | grep -i activeprocessor
java -XshowSettings:system -version        # JDK10+
```

**这一节的杀手句：** "容器里我从不写 -Xmx 等于 limit。我会按 limit 的 60~70% 给堆，把 MetaSpace、线程栈、Direct、CodeCache 和 15% 余量都算进去。因为 OOMKilled 是内核发的 SIGKILL，进程连写日志的机会都没有——只有 exit 137 和一句 Killed，比 OutOfMemoryError 难查得多。"

---

## L6 压测模型：没有压测的调优都是玄学

### Little's Law：并发、QPS、RT 三个数只自由两个

```
       N        =        λ        ×        W
   并发(在途)         QPS(每秒到达)      RT(平均响应,秒)

例一：目标 1000 QPS、单请求 50ms → 需要 50 个并发线程。Tomcat 200 线程绰绰有余。
例二：同一个系统 RT 恶化到 500ms，还是 50 个线程 → QPS 塌到 100。
```

推论一：压测时不能同时定并发和定 QPS。定并发就只看 RT 变化，定 QPS 就只看成功率与 RT。
推论二：线程池打满不等于 QPS 到顶——打满只是说明 N 卡住了，真正的上限由 W 决定。

### 阶梯压测：找的是"拐点"，不是"最大值"

```
QPS ▲
    │        ●——●——●  ← 平台期（瓶颈已到）
    │     ●
    │   ●                ╲ P99 在拐点处竖直起飞
    │ ●                    ╲╱
    └──────────────────────────────► 并发
            拐点前留 30% 余量 = 安全容量
```

平台期长度由瓶颈决定：CPU 打满、连接池耗尽、GC 停顿抬升，三种瓶颈的平台期长度和形状都不一样。只看"最大 QPS"没意义，拐点位置才是容量规划的依据。

### 压测四大常见错误

| 错误 | 后果 | 正确做法 |
| --- | --- | --- |
| 压测机与被测机同机 / 同 Pod | 压测工具自己吃掉 30% CPU，测出来的是"两者抢资源的能力" | 压测机独立部署，同可用区；压测工具自身 CPU 不超 60% |
| 只压不预热，直接计时 | 前 30 秒是解释执行 + 连接池冷启动，误判"性能差" | 预热 5~10 分钟（JIT 分层编译完成、连接池填满、缓存热），只统计后 80% |
| 只看平均 RT | 平均 50ms 掩盖了 5% 的 800ms 请求，而用户恰好是那 5% | 看 P99 / P999，同时看错误率和 GC 停顿分布 |
| 用 /health 或空接口压 | 测的是 Spring MVC 的调度能力，和真实业务无关 | 压真实接口，带真实数据量与缓存命中率，最好用线上流量回放 |

```
# wrk：定并发测 RT，适合快速摸底
wrk -t8 -c200 -d120s --latency http://10.0.1.20:8080/api/order/list

Latency Distribution
     50%   42.31ms
     75%   58.02ms
     90%   91.44ms
     99%  218.77ms      ← 这才是要看的数
Requests/sec:   1842.33

# JMeter 阶梯加压：用 Concurrency Thread Group，每分钟 +50 并发
# 观察点：每次加并发后 QPS 是否仍线性增长；一旦不涨而 RT 涨，拐点到了
```

**瓶颈定位顺序（写死这个顺序，别乱试）：** CPU（top -H + jstack 看热点线程）→ GC（jstat / GC 日志）→ 锁（jstack 找 BLOCKED）→ 连接池（HikariCP active/waiting）→ DB（慢查询、执行计划）→ 下游 RPC（超时率、RT 分位）。前一步没排除，不要跳到下一步，否则你会在五个地方同时"优化"，然后不知道是谁起的作用。

---

## L7 完整调优案例：从 800ms 到 80ms 的账

场景：订单列表接口 /api/order/list，P99 800ms。运维说"加机器吧"，架构师说"调调 JVM"，你被叫去定位。

### 耗时瀑布

```
改前 800ms
[============ DB 循环查 50 次 × 12ms = 600ms ============][序列化80][日志+金额120]

改后 80ms
[批量25][序列化26][其他25]
        ↑ 同一比例尺下，改后的条形几乎看不见
```

注意最扎心的一点：**慢的根源是 50 次数据库往返，而 GC 频繁是它的副产品**——修好前者，后者自动消失。

### 第一步：先量，别猜

```
# 1. 方法级耗时分解（arthas，线上可安全使用）
trace com.example.order.OrderService list '#cost > 100' -n 5

`---[648.221ms] com.example.order.OrderService:list()
    +---[0.412ms] 校验参数
    +---[12.014ms] OrderMapper.selectByUser()          # 1 次，正常
    +---[612.882ms] com.example.order.OrderService:buildVO()   ← 元凶
    |   `---[601.221ms] ProductMapper.selectById()     # 循环里调了 50 次
    `---[33.441ms] 序列化

# 2. GC 现状
jstat -gcutil 12345 1000
  S0  S1   E     O     M    YGC   YGCT  FGC
  0   58   97.3  41.2  93.1  18234 182.3   0
# YGC 0.84 次/秒（服务已跑 6 小时），O 稳定在 41% —— 没有泄漏，只是分配太快
```

### 第二步：分清主次，别被 GC 带跑

看到 YGC 0.84 次/秒，很多人的第一反应是调大 Eden。但如果这时候动手，会发生什么？

- Eden 从 2G 调到 4G → YGC 间隔从 1.2 秒变 2.4 秒，单次停顿从 10ms 变 20ms。**总停顿时间一模一样**，P99 一点没降（因为 600ms 的 DB 往返还在）。
- Old 从 5.5G 缩到 3.5G → Mixed 反而更频繁。

这就是"调参能解决 5%"的真实含义。先把 600ms 干掉，再看还剩什么问题。

### 第三步：改代码（N+1 查询 → 批量查询）

```java
// 改前：50 次数据库往返，600ms
for (Order o : orders) {
    Product p = productMapper.selectById(o.getProductId());
    vo.setProductName(p.getName());
}

// 改后：1 次 IN 查询，25ms
Set<Long> ids = orders.stream().map(Order::getProductId).collect(Collectors.toSet());
Map<Long, Product> map = productMapper.selectBatchIds(ids)
        .stream().collect(Collectors.toMap(Product::getId, p -> p));
for (Order o : orders) {
    Product p = map.get(o.getProductId());
    vo.setProductName(p == null ? "" : p.getName());
}
```

### 第四步：顺手砍掉临时对象

```java
// 改前：每个请求 new 出大量临时对象
log.debug("order detail: {}", JSON.toJSONString(order));   // 即使 debug 关闭也会先序列化！
BigDecimal total = orders.stream()
        .map(o -> new BigDecimal(o.getAmount()).multiply(new BigDecimal("1.13")))
        .reduce(BigDecimal.ZERO, BigDecimal::add);

// 改后：
if (log.isDebugEnabled()) { log.debug("order detail: {}", order.getId()); }  // 只打 ID
long totalFen = orders.stream().mapToLong(Order::getAmountFen).sum();       // 用分做整数运算
BigDecimal total = BigDecimal.valueOf(totalFen, 2);                          // 只在出口转一次
```

**顺带一个高频考点：** log.debug("x: {}", JSON.toJSONString(obj)) 是**参数先求值**——即使日志级别是 INFO，序列化照样执行。这就是"没打日志却疯狂产生对象"的经典来源。正确写法是 log.debug("x: {}", obj)（惰性 toString，但仍是 toString）或者外面套 isDebugEnabled()。

### 第五步：验证，用数字说话

| 指标 | 改前 | 改后 | 变化 |
| --- | --- | --- | --- |
| P99 RT | 800ms | 80ms | 下降 90% |
| DB 往返次数/请求 | 51 次 | 2 次 | 下降 96% |
| 分配速率 | 200MB/s | 60MB/s | 下降 70% |
| YGC 频率 | 0.84 次/秒 | 0.11 次/秒 | 下降 87% |
| JVM 参数改动 | — | — | 零 |

**这个故事怎么讲给面试官：** "我不会一上来就动 JVM。我会先用 arthas 的 trace 把 800ms 拆开，看是 CPU、IO 还是等待。那次拆出来 600ms 在 buildVO 里，是循环查库，属于 N+1。改完之后分配速率自然从 200MB/s 掉到 60MB/s，YGC 频率跟着降了 87%，参数一个没动。这件事让我形成一个判断顺序：先拆耗时，再看 GC，最后才看参数。"

---

## L8 速查：面试前扫一眼就够

### 参数地图

| 分组 | 参数 | 默认 | 什么时候动它 |
| --- | --- | --- | --- |
| 容量 | -Xms / -Xmx | 物理内存 1/64 / 1/4 | 永远设成相等，避免扩缩引发的 Full GC |
| 容量 | -Xmn / -XX:NewRatio=2 | 新生代 1/3 | Parallel 下用；G1 下别用 |
| 容量 | -XX:SurvivorRatio=8 | 8 | Eden : 单个 Survivor = 8 : 1 |
| 容量 | -XX:MaxTenuringThreshold=15 | 15 | 很少动；动态年龄判定会覆盖它 |
| G1 | -XX:MaxGCPauseMillis=200 | 200 | 设成目标 RT 的 1/10，如 20 |
| G1 | -XX:InitiatingHeapOccupancyPercent=45 | 45 | 老年代涨太快就降到 35，提前并发标记 |
| G1 | -XX:G1HeapWastePercent=5 | 5 | Mixed 太频繁就提到 10 |
| G1 | -XX:G1NewSizePercent=5 / G1MaxNewSizePercent=60 | 5 / 60 | 想让 Eden 有下限就抬前者 |
| G1 | -XX:G1ReservePercent=10 | 10 | 出现 to-space exhausted 时提到 15~20 |
| 容器 | -XX:MaxRAMPercentage=25.0 | 25.0 | 容器里按 limit 比例给堆，常用 60~70 |
| 容器 | -XX:ActiveProcessorCount | 宿主机核数 | CPU limit 小于宿主机核数时必写 |
| 容器 | -XX:MaxMetaspaceSize | 不限 | 必写，256~512m，否则吃光容器内存 |
| 容器 | -XX:MaxDirectMemorySize | = Xmx | 用 Netty / NIO 时必写 |
| 诊断 | -XX:+HeapDumpOnOutOfMemoryError | 关 | 必开，配合 -XX:HeapDumpPath |
| 诊断 | -Xlog:gc*:file=gc.log:time:filecount=5,filesize=50m | — | 必开，没日志等于没有证据 |
| 诊断 | -XX:+DisableExplicitGC | 关 | 确认有人乱调 System.gc() 后开 |

### 命令速查（按"我现在想知道什么"排）

| 我想知道 | 命令 |
| --- | --- |
| 进程号是多少 | jps -l |
| GC 频率和每次停顿 | `jstat -gcutil <pid> 1000`（看 YGC/FGC/YGCT/FGCT） |
| 堆里谁在占内存 | `jmap -histo:live <pid>` 加 head -20（会触发一次 Full GC，慎用） |
| 完整堆快照 | `jmap -dump:live,format=b,file=/tmp/h.hprof <pid>` |
| 线程都在干嘛 | `jstack <pid> > t.txt`，隔 5 秒抓三次对比 |
| 哪个线程吃 CPU | `top -Hp <pid>` 拿 tid → printf "%x" tid → jstack 里搜 nid |
| 实际生效的参数是多少 | `jinfo -flags <pid>` / `java -XX:+PrintFlagsFinal -version` |
| 方法级耗时 | arthas：trace Class method '#cost > 100' |
| 方法入参出参 | arthas：watch Class method '{params,returnObj}' -x 2 |
| 类从哪加载的 | arthas：sc -d ClassName |

**生产禁用清单：** jmap -histo:live 和 jmap -dump 都会触发 STW（前者一次 Full GC，后者在全堆遍历期间停应用）。12G 堆的 dump 可能停 10~30 秒。必须先在摘流节点上做，或者用 -XX:+HeapDumpOnOutOfMemoryError 让它在崩溃时自动留证据。

---

## L9 十四条高频问答

### 1. -Xms 和 -Xmx 为什么要设成一样？

因为堆动态扩缩本身有成本：扩容要向内申请并可能触发 Full GC，缩容后下次又要扩；同时堆边界变化会打乱 G1 的 region 规划与操作系统的透明大页行为，延迟不可预测。设成相等等于把"内存换确定性"，生产环境基本都这么干。代价是启动就占满，但对常驻服务无所谓。

### 2. 新生代越大越好吗？

不是。Eden 变大 → YGC 间隔变长，但单次停顿也变长（要扫描、拷贝的存活对象更多），总停顿时间基本不变；同时 Old 变小，Mixed 反而更频繁。真正该做的是先降低分配速率。判断标准是"停顿总量占总运行时间的比例"，不是"YGC 间隔"。

### 3. G1 相比 CMS 强在哪？为什么 JDK9 换成默认？

三点：① region 化让回收可以"挑着做"，停顿可控可预测，CMS 只能整代回收；② 有压缩（compaction），不会像 CMS 那样堆碎片化到只能靠 Serial Old 兜底，那是 CMS 最痛的长 Full GC；③ 可管理大堆（几十 G）而停顿仍可接受。CMS 在 JDK 9 被标记废弃，JDK 14 移除（JEP 363）。

### 4. ZGC 凭什么做到亚毫秒停顿？

两个关键技术：**染色指针**（把标记位存在 64 位指针的高位里，对象本身不用改）和**读屏障**（读取引用时由 JIT 插入一小段检查，发现指针指向已搬迁的对象就当场修正）。这样"移动对象"和"应用线程访问对象"可以并发进行，STW 只剩根扫描那一小段。代价是吞吐降 5~15%、内存开销更大（多重映射需要预留地址空间），所以它不是"更好的 G1"，是"用吞吐换延迟"。

### 5. 为什么 GC 必须 Stop The World，不能完全并发吗？

根节点的枚举必须一致：如果一边遍历引用链、一边应用线程在改引用，就会漏标或多标，导致活对象被当垃圾回收（这是致命的正确性问题）。所以至少要停一小段做根扫描和"标记结束时的修正"。ZGC 把这段压到亚毫秒，但没有消除它。想彻底不停，只能不做回收（Epsilon 那种 no-op GC，只适合压测）。

### 6. 什么情况会触发 Full GC？

常见五种：① 晋升失败（Survivor 或 Old 装不下存活对象，出现 to-space exhausted / Evacuation Failure）；② Humongous 对象占满 region 且碎片化严重；③ MetaSpace 扩容失败；④ 显式 System.gc()（RMI 定时调用是隐形来源）；⑤ G1 的并发标记没跟上分配速度，触发保底回收。判据是看 Full GC 前最后一条日志是什么。

### 7. 内存泄漏怎么定位？

三步：① 先用 GC 日志的"回收后占用"确认是不是真泄漏——单调上涨才是，贴着上限波动只是容量不足；② jmap -histo:live 看 Top 对象类型，找出数量异常的那个（比如某个 DTO 有 800 万个实例）；③ dump 堆，用 MAT 的 dominator tree 找"谁持有它"，再看 GC Roots 路径。90% 的答案在 ThreadLocal、静态 Map 缓存、监听器未注销、连接未关闭这四类里。

### 8. OOM 有哪几种？分别怎么产生？

至少七种：Java heap space（堆满，最常见）、GC overhead limit exceeded（98% 时间在 GC 却只回收不到 2%）、Metaspace（动态生成类太多）、unable to create new native thread（线程数到上限，通常是 -Xss 太大或线程泄漏）、Direct buffer memory（Netty 堆外没释放）、Requested array size exceeds VM limit（数组长度超 int 上限）、以及容器里的 exit 137（内核 OOMKilled，不算 Java 的 OOM）。

### 9. 生产上取堆 dump 要注意什么？

两件事：① **会 STW**，jmap -dump 期间全堆遍历，12G 堆可能停 10~30 秒，必须先摘流；② **文件很大**，dump 出来和堆差不多大，要确认磁盘够、别写进容器临时目录。更稳的做法是开 -XX:+HeapDumpOnOutOfMemoryError 让它在崩溃瞬间自动 dump，或者用 `jcmd <pid> GC.heap_dump` 配合流量摘除。

### 10. MaxGCPauseMillis 设成 5 会怎样？

不会让停顿变成 5ms。G1 会认为"每次只能收很少的 region"，于是 YGC 频率暴涨、并发标记更频繁、CPU 占用上升，吞吐下降；而且如果存活对象本来就多，它扫完照样超时。它是软目标，靠调整 Young 大小来逼近，不是硬保证。合理值是目标 RT 的 1/10，一般 20~50ms。

### 11. 为什么 G1 下不建议写 -Xmn？

因为 G1 的核心机制就是"动态调整年轻代 region 数量来逼近停顿目标"。写死 -Xmn 等于把新生代大小锁死，G1 失去唯一的调节手段，MaxGCPauseMillis 直接失效。想给 Eden 设下限应该用 -XX:G1NewSizePercent，那是"百分比下限"，仍然允许动态调整。

### 12. 容器里 OOMKilled 和 Java OOM 有什么区别？

Java OOM 是 JVM 自己判断堆/元空间/直接内存不够，抛 OutOfMemoryError，进程还活着能写日志。OOMKilled 是内核发现 cgroup 内存超限，直接 SIGKILL，进程连日志都来不及写，容器退出码 137。后者常见于堆外内存（线程栈、DirectBuffer、MetaSpace）没算进预算。排查靠 docker inspect 的 OOMKilled=true 或 kubectl describe pod 的 Exit Code 137。

### 13. 大对象一定进老年代吗？

分收集器。Parallel/Serial 下超过 -XX:PretenureSizeThreshold 的对象直接进 Old；G1 没有这个参数，改成"超过 region 一半就是 Humongous"，直接占用连续整块 region。G1 的 Humongous 只在并发周期或 Full GC 时才回收，所以频繁创建 2MB 以上数组会导致"老年代没满却频繁 Full GC"这种诡异现象。

### 14. 压测 QPS 上不去，你怎么判断是不是 GC 的问题？

算一个比值：**GC 停顿总时间 ÷ 压测总时长**。低于 1% 就基本可以排除，去查 CPU 使用率（是否打满）、线程栈（是否大量 BLOCKED/WAITING）、连接池（active 是否等于 max）、下游 RT。高于 5% 才是 GC 主导，这时候看是 YGC 太频繁还是 Mixed 太长，两者处方完全不同。

---

## L10 三道模拟面试

### Q1 一个 8G 堆的服务，YGC 从 10 秒一次变成 1 秒一次，P99 从 50ms 涨到 200ms。你怎么查？

**考察点：** 不是考参数，是考排查顺序和能不能区分"分配变快"和"存活变多"。

**不及格：** "把 -Xmn 调大，或者换成 G1 / ZGC。" —— 直接给方案，没有任何测量，等于在赌。

**及格：** "先看 GC 日志，确认 YGC 间隔和单次停顿；再 jstat 看 Eden 涨速；如果分配速率高就查代码里有没有循环 new 对象。参数上可以调大 Eden。" —— 顺序对，但缺少"存活集"这个关键区分。

**良好：** "先分两件事：是分配变快了，还是回收后剩得更多了。看日志里 xxxM->yyyM 的后一个数——如果它还稳定在 186M，说明只是分配速率上升，属于'生产垃圾变快'，去查最近上线的代码、日志级别、批量任务；如果它在涨（186→400→800M），说明存活集变大，可能是缓存没上限或泄漏，得 dump。这两条路后面的动作完全相反。"

**优秀：** "我会按四步：① 拿 baseline，日志算 YGC 间隔、单次停顿、回收后占用三条曲线；② 判断性质——回收后占用稳定就是分配速率问题，单调上涨就是存活集问题；③ 如果是分配速率，用 arthas 的 trace 或 async-profiler 的 alloc 采样定位到具体方法，通常是循环 new、日志里 JSON.toJSONString、Stream 装箱这几类；④ 最后才谈参数，而且我会说明调大 Eden 只是把停顿从'频繁短停'变成'稀疏长停'，总停顿时间不变，P99 未必改善。另外要确认这 1 秒一次是不是只在某个时间窗出现，如果是，先看是不是定时任务或批量导入。"

**追问：调大 Eden 之后 P99 没改善，为什么？**
因为 P99 的构成里 GC 只占一部分。假设原来每 1.2 秒停 10ms，一个 200ms 的请求有 8% 概率撞上；调大后每 2.4 秒停 20ms，概率还是 8%，期望增量一样。要让 P99 真的降，得降"停顿总量占比"，也就是降分配速率。

### Q2 K8s 里 limit 4G 的 Pod，-Xmx3g，跑十分钟被 OOMKilled，日志里没有任何 OutOfMemoryError。怎么定位？

**考察点：** 容器内存账本 + 堆外内存的五个来源 + 会不会区分 Java OOM 和内核 OOMKilled。

**不及格：** "加大 limit 到 8G。" —— 能跑起来，但没解释为什么，下次换个服务还会踩。

**及格：** "是堆外内存超了，可能元空间或线程栈太大。加 -XX:MaxMetaspaceSize=256m，把线程数降下来。" —— 方向对，但没说全，也没给验证方法。

**良好：** "OOMKilled 是内核发的 SIGKILL，进程没机会写日志，所以看不到 OOM 异常。4G limit 里 -Xmx3g 只占 75%，剩下 1G 要装元空间、线程栈（Tomcat 200 线程 ≈ 200M）、DirectBuffer（默认等于 Xmx，这里可能吃到 3G！）、CodeCache 240M、GC 自身结构。最可能的是 MaxDirectMemorySize 没设，Netty 一上来就超。"

**优秀：** "先确认死因：kubectl describe pod 看 Last State 是不是 OOMKilled、Exit Code 137，用这个把'Java OOM'和'内核 kill'分开。然后算账：4G limit 减去 3G 堆只剩 1G，要装元空间、线程栈、Direct、CodeCache——其中 MaxDirectMemorySize 默认等于 Xmx，也就是允许吃 3G 堆外，这就是最大隐患。所以改法是把账算清：堆压到 2.4G（60%）、MaxMetaspaceSize=256m、MaxDirectMemorySize=300m、ReservedCodeCacheSize=240m、-Xss1m 且线程数控制在 200，留 15% 余量。同时要验证：`jcmd <pid> VM.native_memory summary`（先开 NMT）看 native 到底花在哪，或者用 pmap -x 看进程 RSS 分布。如果 NMT 显示 Direct 在涨，那就是 Netty 的 ByteBuf 没释放，属于代码问题不是参数问题。"

**追问：为什么 JDK8 特别容易踩？**
8u191 之前 JVM 读不到 cgroup 的 memory limit，默认堆按宿主机物理内存算。一台 64G 的机器上，容器限 4G，JVM 默认 -Xmx 会算出 16G，启动就死。-XX:+UseContainerSupport 是 8u191 起默认开启的。

### Q3 给你一个全新的订单服务，4C8G 容器，目标 1000 QPS、P99 100ms。你怎么定 JVM 参数？

**考察点：** 能不能从目标反推参数，而不是套模板。

**不及格：** "-Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200。" —— 标准的抄来的模板，和"目标 P99 100ms"没有任何关系。

**及格：** "用 G1，堆设 4~5G，停顿目标设 20ms（因为 P99 是 100ms），开启 GC 日志和 HeapDumpOnOutOfMemoryError。"

**良好：** "先算停顿预算：P99 100ms，GC 停顿应控制在 10ms 量级，所以 MaxGCPauseMillis=20 留点余量。4G limit 按 60% 给堆即 2.4G，MetaSpace 256M、Direct 300M、线程栈 200M、CodeCache 240M，留 15% 余量。用 G1，不写 -Xmn，因为它要动态调 Young。"

**优秀：** "我会分三步定。第一步定方向：100ms 的 P99 属于常规在线服务，G1 够用，不用上 ZGC（那是用 15% 吞吐换亚毫秒停顿，这里不划算）。第二步定容量：4G 容器按 60% 给堆 = 2.4G，其余按账本分配。第三步定 Eden——这个是算出来的，不是拍的：压测先拿到分配速率，假设是 150MB/s，我希望 YGC 间隔 10 秒（太短会让 P99 抬高），那 Eden 就需要 1.5G，加上两块 Survivor 各 190M，正好吃掉 2G 的年轻代，剩下 Old 约 1.6G。然后必须验证：压 1000 QPS 跑 30 分钟，看三件事——YGC 间隔是否达到 10 秒、Mixed 是否超过 20ms、有没有出现 Full GC。如果 Old 增长太快导致频繁 Mixed，就说明存活集偏大，要么扩堆要么查缓存。最后，我会在启动参数里加 -XX:ActiveProcessorCount=4，因为 K8s 的 CPU limit 和宿主机核数经常不一致，不写就会按宿主机 64 核创建 GC 线程，直接把自己限流死。"

**追问：为什么不直接用 ZGC？**
ZGC 的吞吐损失在 5~15%，而 100ms 的 P99 用 G1 完全能覆盖（YGC 10~20ms、Mixed 30~50ms）。只有目标 P99 进入个位数毫秒、且算力有富余时才值得。另外 ZGC 需要更大的地址空间预留，小堆上收益不明显。技术选型要能说出"为什么不选"，这才是分水岭。

---

## 本系列进度与下期预告

**12 期已覆盖：** 01 HashMap/ConcurrentHashMap · 02 JVM 内存模型与 GC · 03 并发编程（锁升级/AQS/线程池）· 04 Spring 核心 · 05 MySQL 索引与事务 · 06 Redis · 07 消息队列与分布式事务 · 08 Spring Cloud 与微服务治理 · 09 线上问题排查 · 10 大厂场景设计 · 11 设计模式与代码品味 · 12 JVM 调优与性能压测（本期）

**下期预告 · 第 13 期：类加载机制与 Java 新特性实战**

双亲委派的四次被打破（SPI / Tomcat / OSGi / 热部署）、类初始化时机与被动引用陷阱、自定义 ClassLoader 的三种真实用途、方法区到元空间的迁移、以及 JDK 17/21 里那些面试会问的新特性（record / sealed / virtual threads / pattern matching）。虚拟线程会重点讲——它把"线程池怎么配"这道老题直接作废了一半，但也会带来新的 pinning 问题。

### 本期三条结论

1. **先量再调，顺序是死的：** 拆耗时 → 看 GC → 查堆外 → 最后才动参数。跳过任何一步都会让你在白板上写出一个自己都解释不了的参数。
2. **每个参数背后都有一个除法：** Eden = 分配速率 × YGC 间隔；Old 增速 = 分配速率 × 晋升率；堆 = 容器 limit × 60%。算不出来的参数就是抄来的。
3. **能说出"什么时候不用 JVM 参数"才是真会：** GC 停顿占比低于 1%、P99 长尾来自同步 IO 或锁竞争、OOMKilled 来自堆外——这几种情况下调参数 100% 无效。

---

> 参数是结果，不是原因。｜ Java 面试深水区 · 每两小时一期
