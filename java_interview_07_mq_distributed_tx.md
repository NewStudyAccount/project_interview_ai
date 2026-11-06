# Java 面试深水区 07 · 消息队列与分布式事务

> 每 2 小时 · 由浅入深 · 代码 + 图形 + 数字

「为什么用 MQ」这题，十个人里有八个答「解耦、异步、削峰」，然后面试官问一句「那消息丢了怎么办」，全场安静。这三个词是收益，不是答案——真正的答案是**你为了这三个收益，主动接下了三个新问题：丢、重、乱**。这一期从 Kafka 的一条消息怎么落盘，一路钉到 TCC 的空回滚和悬挂，每一层都给数字。

- 8 层递进
- 10 张原理图
- 14 条高频问答
- 3 道模拟面试

---

## L0 · 契约层：MQ 到底买到了什么，又卖掉了什么

### 1. 三个收益，三张账单

```
            不用 MQ（同步串行）                    用 MQ（异步解耦）
   ┌────────┐   ┌────────┐   ┌────────┐      ┌────────┐      ┌─────┐      ┌────────┐
   │ 下单   │──▶│ 扣库存 │──▶│ 发积分 │      │ 下单   │─────▶│ MQ  │─────▶│ 扣库存 │
   │ 120ms  │   │ 80ms   │   │ 200ms  │      │ 120ms  │      │     │  ├──▶│ 发积分 │
   └────────┘   └────────┘   └────────┘      └────────┘      └─────┘  └──▶│ 发短信 │
   总耗时 400ms，任一环节挂了 → 整个下单失败      总耗时 120ms，下游挂了 → 消息还在，可重试
```

| 你买到的 | 你卖掉的 |
|---|---|
| 解耦：下游加一个「发短信」，下单代码一行不改 | 一致性：本地事务成功 ≠ 消息一定投递成功 |
| 异步：RT 从 400ms 降到 120ms | 可观测性：链路断在 MQ，出问题要顺着 traceId 找 |
| 削峰：10 万 QPS 的洪峰写进 MQ，下游按 2000 TPS 慢慢消费 | 顺序性：多分区并发消费，天然乱序 |

### 2. 一张图记住全篇的因果链

```
                   ┌──────────────────────────────────────────┐
                   │  消息为什么可能丢？                        │
                   │  ① 生产端：发了但没到 broker（网络/超时）    │
                   │  ② 存储端：到了但还在 page cache，机器断电   │
                   │  ③ 消费端：消费了但位移没提交（重复）        │
   ┌─────────┐     │  ④ 消费端：位移提交了但业务没做完（丢）      │     ┌─────────┐
   │Producer │────▶│                                          │────▶│Consumer │
   └─────────┘     │  消息为什么可能重？                        │     └─────────┘
        │          │  at-least-once 的必然结果：先处理后提交      │          │
   acks / 重试     │  rebalance、超时、网络分区都会触发重投        │    幂等 / 去重
        │          └──────────────────────────────────────────┘          │
   ┌─────▼─────┐                                                        │
   │  Broker   │  顺序写 + 页缓存 + 零拷贝 + 副本同步(ISR)              │
   └───────────┘                                                        ▼
                                                              ┌──────────────────┐
                                                              │ 业务侧最终一致     │
                                                              │ 2PC/TCC/SAGA/     │
                                                              │ 本地消息表/事务消息 │
                                                              └──────────────────┘
```

**一句话契约**：MQ 只能给你**至少一次投递**（at-least-once），**恰好一次**必须由业务侧用幂等自己造。这个认知是后面所有答案的地基。

---

## L1 · 存储层：一条消息是怎么落到磁盘上的

### 1. Topic → Partition → Segment 三级结构

```
Topic: order-created  (3 分区)
│
├── Partition-0  ──▶ 一段逻辑上无限长的追加日志
│      ├── 00000000000000000000.log      ← 1GB 段文件（segment.bytes=1073741824）
│      ├── 00000000000000000000.index    ← 稀疏偏移索引
│      ├── 00000000000000000000.timeindex
│      ├── 00000000000001073874123.log   ← 写满 1GB 后滚动出新段
│      └── ...
├── Partition-1
└── Partition-2

单个 .log 文件的物理布局：
 offset:  1000    1001    1002    1003    1004   ...
        ┌───────┬───────┬───────┬───────┬───────┐
        │ batch │ batch │ batch │ batch │ batch │   每条 batch 含 baseOffset / lastOffset
        └───────┴───────┴───────┴───────┴───────┘
```

**关键设计：日志文件只追加，永不修改。** 这一条决定了后面所有性能优势——顺序写、零拷贝、页缓存友好。

### 2. 稀疏索引：为什么不是每条消息都建索引

`.index` 文件里存的是 `相对offset(4B) → 物理位置(4B)`，**每 4KB 日志才记一条**（`index.interval.bytes=4096`）。

```
查找 offset=10738 的消息：

  ① 二分 index 文件（全部载入内存，约 10MB/GB 日志）
        ┌──────────┬──────────┬──────────┬──────────┐
        │ off 1000 │ off 1050 │ off 1100 │ off 1107 │
        │ pos  0   │ pos 2048 │ pos 4096 │ pos 6000 │
        └──────────┴──────────┴──────────┴──────────┘
                                  ▲ 二分找到 ≤10738 的最大项：off 1100 → pos 4096
  ② 从 pos 4096 顺序扫描 .log，最多扫 4KB
  ③ 命中 offset 10738

总代价：一次二分（内存） + 一次 4KB 顺序读（磁盘）≈ 常数时间
```

如果每条消息都建索引：1GB 日志、每条 1KB → 100 万条 × 8B = 8MB 索引，内存直接翻倍，且**索引本身变成随机读**。稀疏索引是「用一次 4KB 顺序扫换 100 万条索引」的典型 trade-off。

### 3. 顺序写到底快多少（数形结合）

```
吞吐量（单盘 7200rpm HDD，对数坐标）

 1000 MB/s ┤
           │
  600 MB/s ┤  ██████████  ← 顺序写 ~600MB/s
           │  ██████████
  100 MB/s ┤  ██████████
           │  ██████████
   10 MB/s ┤  ██████████
           │  ██████████         ██
    1 MB/s ┤  ██████████         ██
           │  ██████████         ██
  0.1 MB/s ┤  ██████████         ██  ← 随机写 ~100KB/s
           └──────────────────────────────────
              顺序写              随机写
```

差 **6000 倍**。物理原因：机械盘磁头寻道 + 旋转延迟约 8–10ms，随机写 100 次/s；顺序写磁头几乎不动，瓶颈变成盘片线速度。SSD 上差距缩小到 10–30 倍，但依然显著——因为 SSD 还有**写放大**和**垃圾回收**。

> 面试加分点：不要只说「顺序写快」，要补一句「所以 Kafka 宁可让每个分区独占文件、也不做全局有序，就是为了保住单文件追加」。全局有序 = 单分区 = 放弃并行，这是 Kafka 明确的取舍。

### 4. 为什么 Kafka 不用 JVM 堆做缓存

| 方案 | 问题 |
|---|---|
| 堆内缓存（如自己写个 LRU） | ① 几十 GB 的对象让 GC 停顿从 ms 变秒；② 数据要**从页缓存再拷一次到堆**（多一次 CPU 拷贝）；③ 进程重启缓存全丢 |
| OS page cache（Kafka 的选择） | ① 由内核管理，内存不足时自动淘汰冷数据；② 与零拷贝天然衔接（数据本来就在内核态）；③ broker 重启后缓存**还在** |

这也是为什么 Kafka 官方建议 **不要给 broker 太大堆（6–8GB 足够），把剩下的内存全留给页缓存**。`num.io.threads`、`num.network.threads` 才是调优点。

---

## L2 · 零拷贝：4 次拷贝怎么变成 2 次

### 1. 传统 read + write 的完整路径

应用要把磁盘文件发到网络，最朴素的写法：

```java
// 传统方式：4 次拷贝 + 4 次上下文切换
FileInputStream fis = new FileInputStream("00000000000000000000.log");
byte[] buf = new byte[8192];
int len;
while ((len = fis.read(buf)) > 0) {
    socket.getOutputStream().write(buf, 0, len);
}
```

```
┌──────────┐        ┌──────────────────┐        ┌────────────┐
│  磁盘    │        │   内核空间        │        │   用户空间  │
└────┬─────┘        └──────────────────┘        └────────────┘
     │  ① DMA 拷贝           │                        │
     └──────────────────────▶│  page cache            │
                             │      │  ② CPU 拷贝      │
                             │      └─────────────────▶│  buf[]
                             │                        │      │ ③ CPU 拷贝
                             │  socket buffer ◀───────┘      │
                             │      │
     ┌───────────────────────┘  ④ DMA 拷贝
     ▼
   网卡
```

4 次拷贝 = 2 次 DMA + **2 次 CPU**，4 次上下文切换 = 2 次系统调用。CPU 拷贝是纯浪费：数据只是路过，用户态根本没看它一眼。

### 2. sendfile：只留 2 次拷贝

```java
// Linux 2.4+ sendfile，Java 通过 transferTo 暴露
FileChannel channel = new RandomAccessFile("00000000000000000000.log", "r").getChannel();
long position = 0, count = channel.size();
while (position < count) {
    // 零 CPU 拷贝：内核直接把 page cache 的数据 DMA 到网卡
    position += channel.transferTo(position, count - position, socketChannel);
}
```

```
┌──────────┐        ┌───────────────────────────────────────┐
│  磁盘    │        │   内核空间                             │
└────┬─────┘        │                                       │
     │  ① DMA       │  page cache ──② 只传描述符──▶ socket  │
     └─────────────▶│       │                    buffer     │
                    │       │                        │      │
                    │       └────③ DMA gather────────┘      │
                    └───────────────────┬───────────────────┘
                                        ▼
                                      网卡
```

| | 传统 read+write | sendfile |
|---|---|---|
| 拷贝次数 | 4（2 DMA + 2 CPU） | 2（2 DMA + **0 CPU**） |
| 上下文切换 | 4 | 2 |
| 用户态可见数据 | 是 | **否** |

### 3. 零拷贝什么时候会失效（这题最能筛人）

| 场景 | 为什么失效 |
|---|---|
| 开启 **SSL/TLS** | 数据要加密，必须进用户态处理，内核没法代劳 |
| 需要**解压/重压缩** | broker 端做消息格式转换（如 0.10 的 down-conversion）时，必须解压后重新组织 |
| 消费端**滞后太多** | 数据已被页缓存淘汰，得先从磁盘读回来——退化成普通读，但仍是顺序读 |
| 用 `mmap` 读日志 | Kafka 只用 mmap 读**索引文件**（小、随机访问），日志用 sendfile。mmap 大文件会踩 page fault 和脏页回写 |

> 一句话收口：**零拷贝的前提是「我不需要看这份数据」**。Kafka 的 broker 恰好不需要看消息体，所以能吃满这个红利。

---

## L3 · 生产与存储可靠性：acks / ISR / LEO / HW

### 1. 四个水位概念，一张图说清

```
Partition-0，三个副本：

  Leader    LEO=105 ┤━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━│ 已写入 105 条
  Follower1 LEO=105 ┤━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━│
  Follower2 LEO= 98 ┤━━━━━━━━━━━━━━━━━━━━━┓         │
                                          │
                    HW = min(ISR 的 LEO) = 98 ◀──────┘
                    └── 消费者只能读到 98
                        └── 99~105 对消费者不可见（还没"提交"）

  LEO  = Log End Offset，每个副本自己的写入进度
  HW   = High Watermark，ISR 中最小的 LEO，决定消费者可见边界
  ISR  = In-Sync Replicas，落后 leader 不超过 replica.lag.time.max.ms(默认30s) 的副本
  LSO  = Last Stable Offset，事务场景用，read_committed 时可见到 LSO
```

**为什么消费者只能读到 HW？** 因为 99~105 还没被所有 ISR 副本拿到。如果 leader 此刻挂了，新 leader 可能只有 98 条，已经发给消费者的 99~105 就凭空消失了——消费者看到的消息"倒退"了。**HW 是用「延迟可见」换「不倒退」。**

### 2. acks 三档到底差在哪

```java
Properties props = new Properties();
props.put("bootstrap.servers", "kafka-1:9092");
props.put("acks", "all");                        // 0 / 1 / all(-1)
props.put("retries", Integer.MAX_VALUE);
props.put("enable.idempotence", "true");          // PID + sequence，防重防乱序
props.put("max.in.flight.requests.per.connection", "5");  // 幂等开启时 ≤5 仍保序
props.put("delivery.timeout.ms", "120000");       // 含重试的总预算
props.put("compression.type", "lz4");
props.put("linger.ms", "5");                      // 攒批，换吞吐
props.put("batch.size", "16384");
```

| acks | 谁确认 | 丢失风险 |
|---|---|---|
| `0` | 谁都不确认，发出去就算成功 | 网络抖动、broker 宕机全丢，**吞吐最高** |
| `1` | leader 写入本地即返回 | leader 落盘后、follower 同步前宕机 → **丢** |
| `all` | ISR 全部写入才返回 | 大概率不丢，但**仍不保证刷盘** |

### 3. acks=all 为什么还不是"绝对不丢"

这是本期最容易被追问的点，三个坑：

1. **all 只保证写进 ISR 的页缓存，不保证 fsync。** Kafka 从不主动 fsync（`log.flush.interval.messages` 默认极大），靠多副本冗余代替单机刷盘。三副本同时断电 → 丢。
2. **ISR 可能只剩 leader 一个。** 如果 `min.insync.replicas=1`（默认），`acks=all` 退化成 `acks=1`。
3. **`unclean.leader.election.enable=true`** 会让落后很多的副本当 leader，直接丢数据。

**可靠三件套（必须同时配）**：

```properties
acks=all
min.insync.replicas=2          # ISR 不足 2 个时，生产者收到 NotEnoughReplicasException，宁可失败
unclean.leader.election.enable=false   # 宁可分区不可用，也不接受数据缺失
replication.factor=3
```

代价算清楚：`replication.factor=3` + `min.insync.replicas=2` → **允许挂 1 个 broker 仍可写**；挂 2 个 → 分区不可写。这就是「可用性 vs 一致性」的真金白银。

### 4. 生产端也会丢：那些没发出去的消息

```java
// 错误示范：fire-and-forget，异常被吞
producer.send(new ProducerRecord<>("order-created", orderId, json));

// 正确：拿到 future，失败要有兜底（落库 + 补偿任务）
producer.send(record, (metadata, ex) -> {
    if (ex != null) {
        // ① 落本地失败表  ② 定时任务重投  ③ 报警
        failureRepo.save(orderId, json, ex.getMessage());
    }
});
```

还有两个隐形丢失点：**`linger.ms` 期间进程被 kill**（缓冲区里的批没发出去）、**`delivery.timeout.ms` 到点直接判失败**（你已经重试到超时了，消息彻底没发）。

---

## L4 · 消费端：重复、乱序、积压的根治

### 1. 位移提交决定语义

```
enable.auto.commit=true（默认，每 5s 提交一次已 poll 到的最大位移）
  poll → 处理 A,B,C → [5s 到点自动提交 offset=3] → 处理 D 时崩溃
                                                        ↓
                                        重启从 offset=3 开始 → A,B,C 被重投

手动提交（先处理，后提交）= at-least-once（至少一次，可能重复）
  poll → 处理 A,B,C → commitSync(offset=3) → 崩溃在 commit 之前？
                                                        ↓
                                        A,B,C 全部重投 → 业务必须幂等

手动提交（先提交，后处理）= at-most-once（最多一次，可能丢）
  绝不推荐，除非丢消息无所谓（日志采集）
```

### 2. rebalance：重复消费最大的制造机

```
Consumer Group "order-svc"（3 个消费者，Topic 3 分区）

正常：
  C1 ── P0        C2 ── P1        C3 ── P2

C2 因为 max.poll.interval.ms(默认 5min) 超时被踢出：
  C1 ── P0,P1     C3 ── P2        [C2 的 P1 被重新分配]
                                    ↓
  P1 上 C2 已处理但未提交的位移 → 全部重投给 C1 → 重复消费

触发 rebalance 的四个常见原因：
  ① 消费者加入/离开（发布、扩容）
  ② max.poll.interval.ms 超时（单批处理太慢，最常见！）
  ③ session.timeout.ms 内没收到心跳（GC 停顿、网络分区）
  ④ 订阅的 topic 分区数变化
```

**两个必答的调优**：

```java
// ① 控制单次 poll 的数据量，让处理时间 < max.poll.interval.ms
props.put("max.poll.records", "100");        // 默认 500，大批量处理必调小
// ② 用增量协作式 rebalance，避免全量 stop-the-world
props.put("partition.assignment.strategy",
          "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
```

### 3. 幂等的三种工程写法

```java
// 写法一：唯一键去重表（最通用）
// UNIQUE KEY uk_biz(biz_type, biz_id)
@Transactional
public void onOrderPaid(OrderPaidEvent e) {
    try {
        dedupMapper.insert(e.getEventId());      // 主键/唯一键冲突 → 直接返回
    } catch (DuplicateKeyException dup) {
        log.warn("重复消息，丢弃: {}", e.getEventId());
        return;
    }
    // 同一个本地事务里做业务，保证"去重记录 + 业务变更"原子
    orderMapper.updateStatus(e.getOrderId(), PAID);
}

// 写法二：状态机（天然幂等，最适合订单/支付）
// UPDATE orders SET status='PAID' WHERE id=? AND status='UNPAID'
int affected = orderMapper.casStatus(orderId, UNPAID, PAID);
if (affected == 0) return;   // 已经处理过，或状态不允许

// 写法三：版本号 / 乐观锁（适合累加类）
// UPDATE account SET balance = balance + ?, version = version + 1
//  WHERE id = ? AND version = ?
```

> 面试话术：「Kafka 只能保证 at-least-once，所以幂等不是可选项，是必选项。我们选的是状态机 CAS + 事件 ID 去重表双保险——状态机挡住业务重复，去重表挡住一条消息被两个不同消费者组重复处理。」

### 4. 顺序性：三个条件缺一不可

```
生产端：同一业务 key 走同一分区（key 的 hash 决定分区）
        partition = murmur2(key) % numPartitions
        → orderId 相同的消息必然进同一分区

存储端：单分区内天然有序（追加日志）

消费端：一个分区只能被同组内一个消费者消费
        → 消费线程池必须按 key 取模路由到固定线程，或干脆单线程
```

```java
// 消费端保序：不能随便丢线程池
executor.submit(() -> handle(record));        // ✗ 乱序

// ✓ 按 key hash 到固定线程
int idx = Math.floorMod(record.key().hashCode(), workers.length);
workers[idx].submit(() -> handle(record));
```

**代价必须主动说**：保序 = 热点 key 全压一个分区 = 该分区成为瓶颈。所以只有**强顺序需求**（同一订单的状态流转）才保序，日志、埋点这类不需要。

### 5. 积压了怎么办（数字要能报）

```
积压 2000 万条，单条处理 5ms，当前 4 个消费者（4 分区）

现有吞吐 = 4 / 0.005 = 800 条/s
清空耗时 = 20,000,000 / 800 ≈ 25,000s ≈ 7 小时   ← 业务等不起

方案 A：加消费者？分区只有 4 个 → 第 5 个消费者空转，无效
方案 B：扩分区（3→12）→ 旧数据不会重分布！只有新数据进新分区
       所以积压的老数据仍压在 4 个老分区上 → 只解决"不再恶化"
方案 C（真实做法）：临时把消息转发到 12 分区的新 topic，起 12 个消费者并行处理
       20,000,000 / (12/0.005) ≈ 8,333s ≈ 2.3 小时
方案 D：能降级就降级（比如积分先不入账，事后批量补）
```

---

## L5 · 分布式事务：五种方案的适用边界

### 1. 问题的本质

```
本地事务：ACID，一个数据库，靠 undo/redo + 锁
分布式事务：跨库/跨服务，没有统一的锁和日志 → 只能靠"协议"逼近一致性

CAP 的取舍：分区必然发生 → 只能在 C 和 A 之间选
BASE：Basically Available + Soft state + Eventually consistent
      分布式事务的工程答案，几乎都是"最终一致"
```

### 2. 2PC / XA：理论最优雅，实践最难受

```
阶段一 Prepare：
  协调者 ──prepare──▶ 参与者A（锁住资源，写 undo/redo，返回 ready）
         ──prepare──▶ 参与者B（锁住资源，返回 ready）

阶段二 Commit：
  协调者 ──commit───▶ 参与者A（释放锁）
         ──commit───▶ 参与者B（释放锁）

  阶段一  ┌─────────┐  阶段二
  ────────│ 锁持有  │──────────  锁从 prepare 一直持有到 commit
          └─────────┘
```

| 问题 | 后果 |
|---|---|
| **同步阻塞** | prepare 之后资源全程加锁，MySQL XA 下并发度断崖下跌 |
| **协调者单点** | 协调者挂了，参与者全部挂在 prepare 状态（in-doubt），要人工介入 |
| **数据不一致** | 阶段二部分 commit 成功、部分失败 → 只能靠重试和对账 |
| **不适合长事务** | 跨服务调用（HTTP/RPC）不该被数据库锁住 |

> 结论话术：XA 在**同构数据库、短事务、低并发**场景能用（如银行内部跨库转账），互联网高并发订单链路基本不用。

### 3. TCC：把锁下沉到业务，代价是三个坑

```
Try     ：检查 + 预留资源（不真正扣减）
          库存：available - 1, frozen + 1
Confirm ：真正扣减，只用 Try 预留的资源，必须幂等
          库存：frozen - 1
Cancel  ：释放预留，必须幂等 + 能处理"空回滚"
          库存：available + 1, frozen - 1
```

```
正常时序：
  Try(订单) ─┐
  Try(库存) ─┼─▶ 全成功 ─▶ Confirm(全部)
  Try(积分) ─┘

异常时序（Try 库存失败）：
  Try(订单) ✓   Try(库存) ✗   Try(积分) 未执行
        └───────────▶ Cancel(全部已 Try 的) ──▶ Cancel 要能处理"自己没 Try 过"

三个坑：
  ① 空回滚：Cancel 比 Try 先到（Try 超时但没失败），Cancel 找不到预留记录
  ② 悬挂：Cancel 已经执行完，迟到的 Try 又来了 → 资源被永久冻死
  ③ 幂等：Confirm/Cancel 都可能被重试多次
```

```java
// 防空回滚 + 防悬挂：事务控制表
@Transactional
public boolean tryFreeze(String xid, Long skuId, int n) {
    // ① 先插控制记录，状态 TRYING（唯一键 xid + branchId 防悬挂）
    int inserted = txControlMapper.insertIfAbsent(xid, BRANCH_STOCK, "TRYING");
    if (inserted == 0) {
        // 已经 Cancel 过（CANCELLED）→ 这是一条"悬挂"的 Try，直接拒绝
        String st = txControlMapper.status(xid, BRANCH_STOCK);
        if ("CANCELLED".equals(st)) return false;
        return true;  // 已 TRYING，幂等返回
    }
    // ② 再冻结库存（带 condition 的 CAS，防超卖）
    int affected = stockMapper.freeze(skuId, n);   // WHERE available >= n
    if (affected == 0) throw new BizException("库存不足");
    return true;
}

@Transactional
public boolean cancelFreeze(String xid, Long skuId, int n) {
    // ① 空回滚：Try 从没来过 → 也插一条 CANCELLED 记录，把"坑"占住
    int inserted = txControlMapper.insertIfAbsent(xid, BRANCH_STOCK, "CANCELLED");
    if (inserted == 0) {
        String st = txControlMapper.status(xid, BRANCH_STOCK);
        if ("CANCELLED".equals(st)) return true;       // 幂等
        if ("TRYING".equals(st)) {                     // 正常取消
            stockMapper.unfreeze(skuId, n);
            txControlMapper.updateStatus(xid, BRANCH_STOCK, "CANCELLED");
        }
        // TRYING 不存在但记录存在 = 之前的空回滚占位，直接返回
    }
    return true;
}
```

**用的人话总结**：TCC 的复杂度全在「三张表 + 三个异常」。它的优点是**不锁数据库行、性能好**；缺点是**侵入性极强**，每个参与方都得写三个方法。所以它适合**核心资金链路**（库存、余额），不适合全站铺开。

### 4. SAGA：长流程的编排，牺牲隔离性

```
订单服务 ──▶ 库存服务 ──▶ 支付服务 ──▶ 积分服务
    │           │            │            │
  C1          C2           C3           C4
    ▲           ▲            ▲            ▲
 补偿1 ◀──── 补偿2 ◀───── 补偿3 ◀───── 补偿4   （逆序补偿）

每一步都是本地事务，失败就逆序执行补偿
```

| 优点 | 缺点 |
|---|---|
| 无锁、长事务友好、跨异构系统 | **没有隔离性**：中间状态对外可见（脏读） |
| 每个服务只写正向 + 补偿两个方法 | 补偿本身可能失败 → 需要人工兜底 |
| 用状态机/编排器可视化 | 补偿要考虑「重试」「部分成功」 |

**补隔离性的做法：语义锁**。订单表加个字段 `status=PAYING`，对外查询时过滤掉中间态，等于用业务手段模拟读已提交。

### 5. 本地消息表：最土但最可靠的方案

```
① 本地事务（一个数据库，ACID 保证原子）
   BEGIN;
     INSERT INTO orders(...) VALUES(...);
     INSERT INTO mq_outbox(id, topic, payload, status) VALUES(..., 'PENDING');
   COMMIT;                          ← 业务和"待发消息"要么都成功，要么都失败

② 独立投递线程（定时扫表）
   SELECT * FROM mq_outbox WHERE status='PENDING' LIMIT 100;
   for each: producer.send(...) → 成功后 UPDATE status='SENT'
             失败保持 PENDING，下轮重试（at-least-once）

③ 下游消费，靠唯一键幂等

④ 兜底：SENT 超过 N 分钟仍未确认的，对账任务查下游状态
```

**为什么它能成立？** 因为「业务写入」和「消息记录」在**同一个本地事务**里——这正是 2PC 想解决但跨不过去的原子性，被我们用一个库给"作弊"绕过去了。代价是：DB 多一次写、有轮询延迟（秒级）、消息表要清理。

### 6. RocketMQ 事务消息：把消息表搬进 MQ

```
① 发半消息（half message）→ broker 存起来但对消费者不可见
② 执行本地事务
③ 根据结果 commit / rollback 半消息
   └─ 若②之后进程挂了，broker 定时回查（默认最多 15 次，指数退避）
      生产者实现 checkLocalTransaction() 查库返回 COMMIT/ROLLBACK/UNKNOWN
```

好处是把「消息表 + 投递线程」都省了；坏处是**绑定 RocketMQ**，且回查接口要幂等。

### 7. 五种方案横向对比（面试直接背这张表）

| 方案 | 一致性 | 性能 | 侵入性 | 适用场景 | 一句话记忆 |
|---|---|---|---|---|---|
| **2PC/XA** | 强一致 | 差（锁到 commit） | 低（数据库层） | 同构库、短事务、低并发 | 锁太久，协调者一挂全挂 |
| **TCC** | 最终一致 | 好（无行锁） | **高**（三方法） | 资金、库存等核心链路 | 三个坑：空回滚、悬挂、幂等 |
| **SAGA** | 最终一致 | 好 | 中（正向+补偿） | 长流程、跨异构系统 | 无隔离性，靠语义锁补 |
| **本地消息表** | 最终一致 | 中（多一次写+轮询） | 中 | 已有 MQ、要求可靠 | 同库同事务，土但稳 |
| **事务消息** | 最终一致 | 中 | 低（仅生产者） | 用 RocketMQ 的场景 | 消息表内置 + 回查兜底 |
| **最大努力通知** | 最终一致 | 好 | 低 | 对接外部（支付回调） | 通知 + 重试 + 主动查询对账 |

### 8. Seata AT：给不想改代码的人

```java
@GlobalTransactional(timeoutMills = 30000)
public void createOrder(...) {
    orderService.insert(...);      // 本地事务，自动生成 undo_log
    stockService.deduct(...);      // 远程分支事务
    // 任一步抛异常 → TC 通知所有分支按 undo_log 回滚
}
```

原理：**一阶段直接提交本地事务**（所以不锁行），同时把「修改前镜像」写进 `undo_log`；全局回滚时用前镜像反向补偿。为了保证「回滚时数据没被别人改过」，AT 在提交前会抢**全局锁**。

两个必答问题：
- **AT 的脏读**：一阶段已提交，其他事务能读到未回滚的中间态（默认读未提交）。解决：`@GlobalLock` + `SELECT ... FOR UPDATE` 让查询也抢全局锁。
- **AT 的性能损耗**：每条 SQL 多一次前镜像查询 + 一次 undo_log 写入 + 回滚时的锁校验，实测约 **10–20%**。

---

## L6 · 速查表

### 1. 可靠性三段的检查清单

| 环节 | 保证手段 | 遗漏点 |
|---|---|---|
| 生产 | `acks=all` + 回调兜底 + 失败落表重投 | 回调里没处理异常；`linger.ms` 期间进程被杀 |
| 存储 | `replication.factor=3` + `min.insync.replicas=2` + `unclean.leader.election=false` | 三副本同机架；`acks=all` 但 `min.insync=1` |
| 消费 | 处理完再 `commitSync` + 业务幂等 + 死信队列 | `auto.commit=true`；异常被 catch 吞掉仍提交 |

### 2. 关键参数速查

| 参数 | 默认 | 调优方向 |
|---|---|---|
| `acks` | `all`（3.x） | 可靠性优先保持 all |
| `min.insync.replicas` | `1` | 生产环境设 2 |
| `replica.lag.time.max.ms` | `30000` | 越小 ISR 越敏感，越容易收缩 |
| `max.poll.records` | `500` | 处理慢就调小到 100 |
| `max.poll.interval.ms` | `300000` | 批处理长任务要调大 |
| `session.timeout.ms` | `45000` | 与 `heartbeat.interval.ms` 保持 3:1 |
| `segment.bytes` | `1073741824` | 1GB 段，影响日志清理粒度 |
| `index.interval.bytes` | `4096` | 稀疏索引密度 |

### 3. 一条消息的生命周期（口述版）

```
Producer 攒批(linger 5ms) → 压缩(lz4) → 按 key hash 选分区 → 发到 leader
  → leader 追加到 .log 末尾（顺序写 + 页缓存）
  → ISR follower 主动 fetch 拉取同步（不是 push！）
  → 全部 ISR 写入后更新 HW，向 producer 返回 ack
  → Consumer fetch(offset) → 命中页缓存 → sendfile 零拷贝到网卡
  → 处理完 commitSync → offset 写回 __consumer_offsets
```

---

## L7 · 高频问答 14 条

**Q1：为什么用 MQ？**
解耦、异步、削峰。但要立刻补代价：一致性变弱、链路变长、顺序性丢失。**成熟的答法是先说收益，再主动说「所以我们引入了幂等和补偿」**。

**Q2：Kafka 为什么快？**
四点：① 顺序写（600MB/s vs 随机 100KB/s）；② 零拷贝 sendfile（4 次拷贝→2 次，0 次 CPU 拷贝）；③ 攒批 + 压缩（batch.size 16KB，lz4）；④ 依赖 OS page cache 而非 JVM 堆，无 GC 压力。

**Q3：零拷贝的原理？**
`read()` 把数据从内核 page cache 拷到用户缓冲区，`write()` 再从用户缓冲区拷到 socket buffer。`sendfile` 让内核直接把 page cache 的数据 DMA gather 到网卡，只传描述符到 socket buffer。Java 里是 `FileChannel.transferTo()`。

**Q4：零拷贝什么情况下失效？**
SSL/TLS 加密、broker 端需要解压/格式转换、消费端落后导致页缓存失效（退化成磁盘读）。**本质是「只要需要看数据内容，就必须进用户态」。**

**Q5：acks=all 就绝对不丢吗？**
不是。① all 只保证写入 ISR 的内存，不 fsync（靠副本冗余）；② `min.insync.replicas=1` 时 all 退化成 1；③ `unclean.leader.election=true` 会选落后副本。必须三件套同配。

**Q6：ISR 是怎么维护的？**
leader 记录每个 follower 最后一次 fetch 的时间，超过 `replica.lag.time.max.ms`（默认 30s）没追上就踢出 ISR；追上来了再加回来。**注意是 follower 主动 pull，不是 leader push**。

**Q7：HW 和 LEO 的区别？**
LEO 是每个副本自己的写入进度；HW = ISR 中最小的 LEO，是**消费者可见的边界**。消费者只能读到 HW，因为超过 HW 的部分还没被所有 ISR 确认，leader 挂了会丢。

**Q8：怎么保证消息不重复消费？**
不可能完全杜绝（at-least-once 的必然），只能业务幂等：① 唯一键去重表；② 状态机 CAS（`WHERE status='UNPAID'`）；③ 版本号乐观锁。**推荐状态机 + 去重表双保险**，因为状态机管业务幂等，去重表管消息级幂等。

**Q9：重复消费的来源有哪些？**
① `auto.commit` 按时间提交，崩在提交与处理之间；② rebalance 导致未提交位移重投；③ 生产端重试（`retries>0` 且没开幂等）导致 broker 存了两份；④ 消费成功但 `commitSync` 网络超时后又重试。

**Q10：怎么保证顺序？**
三条件：生产端同 key 同分区、单分区内有序、同分区只被一个消费者消费。消费端**不能随便丢线程池**，要按 key hash 路由到固定线程。代价是热点 key 压单分区。

**Q11：消息积压怎么处理？**
先算：积压量 ÷ 现有吞吐 = 清空耗时。加消费者受分区数限制；扩分区对**已有积压无效**（旧数据不重分布）。真实做法是临时转发到多分区新 topic 并行消费，或降级。

**Q12：2PC 有什么问题？**
同步阻塞（锁持有到 commit）、协调者单点（in-doubt 事务要人工介入）、阶段二部分失败导致不一致、不适合跨服务长事务。

**Q13：TCC 的空回滚和悬挂是什么？**
空回滚：Try 没到（超时未失败），Cancel 先到，Cancel 找不到预留记录。悬挂：Cancel 执行完后，迟到的 Try 才到，资源被永久冻结。**解法都是事务控制表**：Cancel 时插一条 CANCELLED 占位，Try 时先查状态，发现 CANCELLED 就拒绝。

**Q14：本地消息表 vs 事务消息 vs 最大努力通知？**
本地消息表：业务和消息同库同事务，靠轮询投递，最通用。事务消息：消息表内置在 MQ（RocketMQ 半消息 + 回查），少维护一张表但绑定 MQ。最大努力通知：通知 + 指数退避重试 + 主动查询对账，用于**对外部系统**（支付回调），因为对方不可控。

---

## L8 · 三道模拟面试题

### 题一：订单支付成功后要扣库存、加积分、发通知，怎么保证不丢不重？

**考察点**：MQ 可靠性三段 + 幂等 + 分布式事务选型。

**参考答案骨架**：

1. **不要把所有事都塞进一个事务。** 支付成功（第三方回调）是事实，必须落库；库存和积分是**派生动作**，用 MQ 异步。
2. **生产端**：支付回调里，本地事务写「支付流水 + outbox 消息」，提交后由投递线程发 MQ。这就是本地消息表，比"直接 send 再写库"可靠得多。
3. **存储端**：`acks=all` + `replication.factor=3` + `min.insync.replicas=2` + `unclean.leader.election=false`。
4. **消费端**：`enable.auto.commit=false`，处理完 `commitSync`；每个消费者用 `eventId` 唯一键去重 + 状态机 CAS。
5. **顺序**：同一 `orderId` 作 key，保证扣库存和加积分看到的是同一个订单状态序列。
6. **兜底**：定时对账任务扫「支付成功但库存未扣」的订单，补偿重投；超过 N 次进死信队列人工处理。

**满分线**：主动说出「支付回调本身就是 at-least-once（第三方会重推），所以幂等要从入口就做，不能只在消费端做」。

**扣分点**：上来就答「用 Seata AT 包住三个服务」——跨服务长事务用 AT 会长时间持有全局锁，性能崩。

### 题二：线上告警，订单消息积压 2000 万，消费者组每 3 分钟 rebalance 一次，怎么办？

**考察点**：真实排障顺序 + rebalance 根因。

**参考答案骨架**：

1. **先止血**：确认是不是发布引起的（新版本消费者 `max.poll.records` 没调，单批处理超 `max.poll.interval.ms`）。
2. **定位 rebalance 根因**：看日志 `Attempt to heartbeat failed` / `member was removed`。三个方向：① 处理超时被踢（最常见）；② Full GC 停顿超过 `session.timeout.ms`；③ 消费者频繁重启（OOM / 滚动发布）。
3. **立刻调参**：`max.poll.records` 500→100，`max.poll.interval.ms` 5min→10min，换 `CooperativeStickyAssignor` 减少全量停顿。
4. **处理积压**：算清空耗时；扩分区对旧数据无效，临时起转发消费者把积压搬到 12 分区新 topic 并行处理；同时把通知类消费降级（先不入库，事后补）。
5. **防复发**：加积压监控（`kafka-consumer-groups.sh --describe` 的 LAG）、单批耗时打点、消费者内存与 GC 告警。

**满分线**：说出「rebalance 期间分区会被重复分配，所以这次的重复消息量会非常大，我要同时准备好幂等去重表，不然下游会被打穿」。

### 题三：设计一个「不丢、不重、不乱序」的订单事件管道，说清每层的取舍。

**考察点**：综合设计 + 知道"三全"在物理上不可能，必须分层妥协。

**参考答案骨架**：

| 层 | 做法 | 取舍 |
|---|---|---|
| 生产 | 本地消息表（业务与消息同事务）+ 幂等 producer | 多一次 DB 写，换"绝不丢" |
| 传输 | `acks=all` + RF=3 + `min.insync=2` | 可用性：挂 2 个 broker 就不可写 |
| 分区 | 按 `orderId` 分区，单分区单消费者 | 吞吐受单分区限制，热点订单排队 |
| 消费 | 处理完再提交 + 状态机 CAS + 去重表 | 允许重复投递，用幂等消化 |
| 兜底 | 对账任务 + 死信队列 + 人工工单 | 复杂度换最终确定性 |

**关键收口**：「所谓『不重』，工程上从来不是『消息不重复』，而是『重复了也不产生副作用』。所以我在架构图里不会画『exactly-once』这个词，我画的是『幂等消费 + 对账』。」

---

## 本期收口

1. **MQ 只给 at-least-once**，exactly-once 是业务用幂等造的幻觉——这句话能贯穿整场面试。
2. **顺序写 600MB/s vs 随机 100KB/s**，差 6000 倍，这是 Kafka 所有设计的原点。
3. **零拷贝的前提是「不看数据」**，SSL 一开就失效。
4. **可靠性三件套必须同配**，`acks=all` 单拎出来没有意义。
5. **rebalance 是重复消费的头号制造机**，`max.poll.interval.ms` 是最常被忽略的参数。
6. **分布式事务没有银弹**：核心资金用 TCC，长流程用 SAGA，通用场景用本地消息表，对外用最大努力通知。

---

下期预告：**Spring Cloud 与微服务治理** —— 服务注册发现的 AP/CP 取舍（Nacos vs Eureka vs ZK）、OpenFeign 与负载均衡、熔断降级（Sentinel 滑动窗口与令牌桶的区别）、网关限流算法、链路追踪 TraceId 透传与跨线程丢失问题、灰度发布与流量染色。
