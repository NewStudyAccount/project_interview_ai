# Java 面试深水区 17 · 缓存架构与一致性

被问「怎么保证缓存和数据库一致」，八成人背出「先更新数据库，再删缓存」，追一句「删缓存失败了呢」就开始含糊，再追「延迟双删那个 500ms 是怎么定的」基本就沉默了。因为在这个方案里，99.9% 的单步成功率意味着每天 200 条脏数据，而那个 500ms 只是个传说。

本期把四种时序画成时间线、把双写失败率做成乘法、把多级缓存的「内存放大」和「广播不可达」算成具体数字。全文 13 层、14 张图、17 段代码、14 条问答、3 道模拟题，所有数字都能自己算出来。

第 06 期讲过缓存穿透/击穿/雪崩的定义，本期不重复定义，只算账和讲治理。

---

## L0 先把分层算清：缓存省的不是延迟，是连接数和热点

几乎所有缓存文章的开头都是「数据库慢，所以加缓存」。这个说法在你把整张表放进 buffer pool 之后就不成立了。先看六个位置的实测量级。

```
位置                     延迟        容量/成本        备注
──────────────────────────────────────────────────────────────
CPU L1 缓存              1 ns        64 KB            不可编程
JVM 本地缓存（堆）        200 ns      100 MB/实例       Caffeine 10 万条
MySQL 内存命中           0.3 ms      buffer pool 内     比 Redis 跨 AZ 还快
Redis 同机房             0.6 ms      10 GB/分片        单分片 8 万 QPS
Redis 跨可用区           1.2 ms      10 GB/分片        多一跳骨干网
MySQL 磁盘随机读          6 ms        TB 级             真正贵的是这一层

              延迟跨 7 个数量级，但对数刻度上 Redis 与 MySQL 内存命中只差 2 倍
```

看第 3 行和第 5 行：MySQL 走 buffer pool 是 0.3ms，而 Redis 跨可用区是 1.2ms。如果你的数据量能全放进 buffer pool，加一层跨 AZ 的 Redis 反而让 RT 变长 4 倍。所以缓存真正的价值不是「快」，是三件别的事。

| 缓存真正解决的问题 | 不管它会发生什么 | 数字 |
|---|---|---|
| 连接数 | MySQL 单实例 2000 连接封顶，一个连接占 8MB 内存 | 1000 QPS × 10ms 只需 10 个活跃连接，但突发 5 万 QPS 要 500 个 |
| 热点争用 | 同一行的读也要过 buffer pool 的 mutex 和事务可见性判断 | 单行 10 万 QPS 时 InnoDB 闩竞争把 CPU 打满 |
| 让 DB 只写 | 读写混跑时大查询把 buffer pool 冲掉，写性能跟着掉 | 一次全表扫 10GB 能冲掉 60% 热页，写入 P99 翻 3 倍 |

### 命中率不是越高越好：长尾税

假设 1000 万商品，访问服从 Zipf α=1。缓存 top k 个 key 的命中率约等于 `H(k)/H(N)`，其中 H 是调和数，`H(n) ≈ ln n + 0.577`。

拿 QPS 10 万来算：

| 缓存份额 | 命中率 | 内存（1KB/条） | 打到 DB 的 QPS |
|---|---|---|---|
| top 1%（10 万条） | 72.4% | 100 MB | **27,600** |
| top 10%（100 万条） | 86.2% | 1 GB | **13,800** |
| top 50%（500 万条） | 95.8% | 5 GB | **4,200** |
| top 100%（1000 万条） | 100% | 10 GB | **0** |

这张表是「要不要给某个对象加缓存」的唯一判据。

- 头部 1% 值得缓存：100MB 内存把 DB 压力从 10 万砍到 2.76 万，ROI 最高。
- 长尾（第 50%~100%）不值得：4200 QPS 打在一张 1000 万行的表上单表完全扛得住，为它多花 5GB 内存和一份一致性风险，是负收益。
- 「全量缓存」只在数据量小到能全放内存且读远多于写时成立（配置表、字典表、城市列表）。它不叫缓存，叫内存副本，一致性要求完全不同。

一句话结论：缓存的本质是「用可控的不一致，换 DB 的连接数与热点承载能力」。所以一致性方案的选择标准不是「能不能做到强一致」，而是「业务能容忍多长的窗口」。

---

## L1 本地缓存的三笔账：内存放大、广播不可达、TTL 窗口

「本地缓存 + Redis」是多级缓存的标准答案，但它在分布式部署下有三个必须自己算的账。

### 第一笔：内存放大 = 实例数

```
有效数据 100 MB
  ├─ ins1  100 MB
  ├─ ins2  100 MB
  ├─ ins3  100 MB
  ├─ ...   
  └─ ins20 100 MB        合计 2 GB，放大 20 倍

每份都独立 GC、独立预热、独立过期 —— 2 GB 全是老年代常驻对象，直接抬高 GC 基线
```

### 第二笔：广播不可达

```
写请求 → DB → MQ 广播 / pub-sub → 20 个实例
```

Kafka 广播要让每个实例独立 groupId，消息复制 20 份，且新实例上线收不到历史消息；Redis pub/sub 不持久化、无 ACK、断连即丢。结论是：**本地缓存失效在这套体系下是「尽力而为」，不是「保证」**。

### 第三笔：TTL 是唯一可靠的兜底

| TTL | 最坏脏读 | 副作用 |
|---|---|---|
| 5 s | 5 s | 本地命中率还行，但 5 秒内同一 key 所有读都是旧值 |
| 30 s | 30 s | 适合商品详情这类 |
| 60 s | 60 s | 发版重启后要 60 秒才「自然恢复」 |

面试要答出的核心是：本地缓存的一致性 = 广播到位率 × TTL，两个都得说。只说 TTL 是及格，只说广播是不及格。

### 版本号：把「删除」降级成「知道它变了」

「删本地缓存」要求消息恰好到达每一个实例，这在工程上不可靠。解法是不要求「删掉」，只要求「知道它变了」。

做法：每个缓存对象带版本号，版本号存在 Redis（`item:123:ver`），写入时 INCR。本地 entry 带上读取时的版本，广播只推「key 123 版本变成 8 了」，每个实例本地记一张 `key → 最新版本` 的小表，请求进来比对版本，不一致当 miss。

```java
// 本地缓存 entry 带版本；版本号来自 Redis，广播只负责"通知变化"
public class VersionedEntry<V> {
    final V value;
    final long version;
    final long loadAtMillis;
    VersionedEntry(V value, long version) {
        this.value = value;
        this.version = version;
        this.loadAtMillis = System.currentTimeMillis();
    }
}

private final ConcurrentHashMap<String, Long> verTable = new ConcurrentHashMap<>();

public Item get(long id) {
    String key = "item:" + id;
    VersionedEntry<Item> e = local.getIfPresent(key);
    Long latest = verTable.get(key);              // 广播推来的版本
    boolean stale = e != null && latest != null && latest > e.version;
    boolean expired = e != null
            && System.currentTimeMillis() - e.loadAtMillis > 30_000;
    if (e != null && !stale && !expired) {
        return e.value;
    }
    ItemDbRow row = redis.getWithVersion(key);     // HMGET value version，一次往返
    local.put(key, new VersionedEntry<>(row.item, row.version));
    return row.item;
}
```

这个方案的价值在于：消息丢失不再是「永久脏」，而是「最多 TTL 秒的脏」。可用性的下限从「取决于 MQ 投递成功率」变成了「取决于 TTL 配置」。

### 失效顺序

| 方案 | 顺序错乱的后果 | 结论 |
|---|---|---|
| 先删本地 → 再删 Redis | 间隙里别的实例从旧 Redis 回填本地，本地又脏了 | 必须反过来，或加版本号 |
| 先删 Redis → 再删本地 | 本实例间隙内仍返回旧值但马上被删，别的实例只可能从新 Redis 回填 | 推荐，窗口 = 一次网络往返 |
| 版本号方案 | 顺序无关，版本大小天然决定谁新 | 最稳，代价是多一次版本读 |

还有一个常见错误：先更新 Redis 再更新 DB，在任何情况下都是错的。Redis 没有事务回滚，DB 更新失败时缓存已是新值，而它随时可能被淘汰 —— 这是数据丢失，不只是不一致。

---

## L2 Cache Aside 的四种时序：只有一种能上生产

四种写法都被面试者说过，但差别不在「顺序」，在于不一致的窗口有多长、以及窗口能不能自己闭合。

```
① 先删缓存，再更新 DB —— 最坏

读  |--miss--|--读DB=旧--|              |--写缓存=旧--|
写  |              |--删缓存--|--更新DB=新--|
                                    ↑ 旧值被写回，活到 TTL 到期
   删与写之间被读线程整个穿透


② 先更新 DB，再删缓存 —— 主流

读  |--miss--|--读DB=旧--|                       |--写缓存=旧--|
写  |--更新DB=新--|--删缓存--|
         ↑ 要出错，读线程必须完整「穿越」写线程全过程


③ 延迟双删 —— 在 ② 上打补丁

读  |--miss--|--读从库=旧--|--回填旧值--|
写  |--更新DB--|--删缓存--|======= sleep 500ms =======|--再删--|
                        ↑ 这 500ms 内持续脏读


④ 更新缓存而不是删除 —— 并发覆盖

写A |--写DB=1--|                                |--写缓存=1--|
写B |       |--写DB=2--|--写缓存=2--|
               最终 DB=2，缓存=1，永久错到下次写
```

### 为什么 ① 是最坏的：窗口宽度差 1000 倍

① 的窗口从「删缓存」开始到「DB 更新完成」结束，等于一次 DB 写事务的耗时（含行锁等待、redo 刷盘），5ms 是乐观估计，遇锁等待可以到 100ms。而且窗口内的读不是「可能读旧值」，是一定读旧值并回填，因为缓存已经被自己删了。

② 的窗口要求读线程「读 DB」的起点早于写线程「更新 DB」，同时「写缓存」的终点晚于写线程「删缓存」。翻译一下：读线程的整个「读 DB + 写缓存」必须跨越写线程的整个「更新 DB + 删缓存」。读侧 3ms、写侧 5ms，两个事件重叠且方向相反的概率极低。

但「概率低」不等于「可以不管」。QPS 1000 时任意瞬间有 `1000 × 5ms = 5` 个写请求在飞行中，日更新 10 万次意味着窗口被穿越的机会是按亿次计的。这就是为什么大厂最后都走 L3 的 binlog 方案。

### ② 的正确实现：afterCommit + 落重试表

```java
@Transactional
public void updateItem(Item item) {
    itemMapper.updateById(item);            // 1. 先更新 DB

    // 2. 事务提交后再删缓存 —— 不能写在事务体里！
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override public void afterCommit() {
                evictWithRetry(item.getId());
            }
        });
}

private void evictWithRetry(long id) {
    String key = "item:" + id;
    try {
        redis.delete(key);
    } catch (Exception e) {
        // 关键：失败不能只打日志，落本地消息表由定时任务重试
        retryMapper.insert(new RetryTask(key, 0, System.currentTimeMillis()));
        log.warn("evict failed, queued for retry, key={}", key, e);
    }
}
```

这一行代码就是 80% 面试者的分水岭：把 `redis.delete()` 写在 `@Transactional` 方法体里。此时删缓存发生在事务提交之前，只要事务提交慢（比如等行锁 50ms），这 50ms 内所有读都会 miss 并回填未被提交的旧值 —— 和时序 ① 的错误一模一样。必须用 afterCommit。

### 延迟双删：那个 500ms 到底该怎么定

延迟双删的唯一目标是覆盖主从延迟：写走主库、读走从库，删完缓存后 100ms 内来的读请求会从从库读到旧值并回填。所以第二次删除要发生在「所有可能在主从延迟窗口内读到旧值的请求都回填完」之后。

设主从延迟 D，一次读请求从发起到回填结束耗时 R，第二次删除必须晚于 D + R。

- D 实测：同机房 binlog 同步 P99 约 20~50ms，跨机房 100~300ms，大事务或从库压力大时可到 1~3 秒。
- R 实测：读从库 1ms + 写 Redis 1ms，约 2~5ms。
- 所以理论上 100ms 量级就够。500ms 这个数字没有推导依据，它是「拍脑袋 + 安全系数」的产物。正确做法是监控主从延迟的 P99，取 P99 + 200ms。

「线程 sleep 500ms」这个实现还有三个硬伤：

| 硬伤 | 后果 | 正确做法 |
|---|---|---|
| sleep 在请求线程里 | 写接口 RT +500ms，QPS 100 时线程池 50 个线程全被 sleep 占满 | 绝不能在请求线程 sleep |
| 交给异步线程池 | 线程池满 / 进程重启 / 发布滚动 → 第二次删直接丢 | 用延时消息或 Redis ZSet 延时队列 |
| 第二次删仍可能失败 | 失败了没有兜底，白等 500ms | 同样要落重试表，带次数上限 |

```java
// 用延时消息替代 sleep
public void updateWithDelayEvict(Item item) {
    itemMapper.updateById(item);
    redis.delete("item:" + item.getId());
    // 1s 后投递，消费端只做一件事：再删一次
    delayProducer.send(new DelayEvictMsg(item.getId(), item.getVersion()), 1_000);
}

// 消费端：幂等 + 版本校验，重复投递无害
public void onDelayEvict(DelayEvictMsg msg) {
    Long dbVersion = itemMapper.selectVersion(msg.id);
    if (dbVersion != null && dbVersion > msg.version) {
        return;   // 期间又被改过，说明更新的那次已经自己删过了
    }
    redis.delete("item:" + msg.id);
}
```

### ④ 为什么「更新缓存」几乎总是错的

三个理由，面试按顺序说。

1. 并发覆盖：两个写请求按图 4④ 的顺序交错，缓存永久是旧值。要修就得给每个 key 加锁或做 CAS，成本远高于「删掉让下次读回源」。
2. 写多读少时是纯浪费：100 次写只有 1 次读，你算了 100 次缓存值，其中 99 次没人看。删除是懒加载。
3. 缓存 value 常是聚合结果：商品详情 = 商品 + 库存 + 价格 + 促销，任何一个子项变化都要重算整个聚合。删除只要一个 DEL。

唯一的例外是能保证顺序的场景，典型就是 binlog 订阅：binlog 按主键分区、严格有序，消费端按顺序更新缓存天然不会覆盖错乱。所以不是「更新」错了，是「业务代码里并发更新」错了。

---

## L3 双写的失败率是乘法：99.9% 的两次成功只有 99.8%

所有「先写 DB 再删缓存」的讨论，默认了一个前提：这两步都会成功。但工程里没有 100%。

真实量级：写 DB 成功率 99.95%（含超时重试），删缓存成功率 99.9%（Redis 超时、连接池耗尽、主从切换期只读）。两个操作独立：

```
0.9995 × 0.999 = 0.9985
```

```
A. 业务代码双写：两个失败点都在业务线程里，且无法重放

  updateItem() → [写 DB ✓ 99.95%] → [删缓存 ✗ 0.1%] → 脏缓存存活到 TTL
                                    联合成功率 99.85%
                                    日 10 万次更新 → 150 条不一致

B. binlog 订阅：DB 提交成功 = binlog 一定在，之后每步都可重试

  MySQL 提交 → binlog → canal → MQ(按主键分区保序) → 消费者删缓存
                                     ↑__________重试 16 次__________|
                                     ↑ 仍失败 → 死信人工
```

### 把账算到底：1.35 亿次脏读

1. 日更新 10 万次 × 0.15% = 150 条脏缓存。
2. 每条存活到 TTL 到期，TTL 30 分钟，平均存活 15 分钟 = 900 秒。
3. 如果是热 key（1000 QPS），这 900 秒产生 90 万次脏读。
4. 150 × 90 万 = 1.35 亿次。哪怕只有 10% 是热 key，也是 1350 万次。

而 binlog 链路的窗口是消费延迟：150 × 0.2 秒 × 1000 QPS = 3 万次。而且这 150 条里 99% 会在 200ms 内被消费掉，真正漏到 TTL 的是「MQ 重试 16 次仍失败」的那几条，量级是每天个位数。

| 维度 | 业务双写 | binlog 订阅 | 差距 |
|---|---|---|---|
| 不一致窗口 | 到 TTL 到期（30 min） | 消费延迟（50~200 ms） | 1000 倍 |
| 每日脏数据 | 150 条 | < 5 条（重试耗尽） | 30 倍 |
| 可重放 | 不能 | 能（按位点） | 本质差异 |
| 覆盖批量改库 | 不能 | 能 | 本质差异 |
| 运维成本 | 0 | canal + MQ 一套 | 多 3 个进程 |

```java
// binlog 消费端：三件事必须做对 —— 幂等、保序、可重试
@KafkaListener(topics = "binlog-item", concurrency = "4")
public void onBinlog(BinlogEvent event) {
    if (!event.isDataChange()) return;      // 只关心 INSERT/UPDATE/DELETE

    // 按主键 hash 分区后天然保序；这里再按事件时间丢弃乱序的旧事件
    String key = "item:" + event.getId();
    Long lastTs = redis.get("cache:evict:ts:" + event.getId());
    if (lastTs != null && event.getTs() <= lastTs) return;

    // 删除是天然幂等的 —— 这是「删缓存」优于「更新缓存」的关键
    redis.delete(key);
    redis.setex("cache:evict:ts:" + event.getId(), 60, event.getTs());
    // 抛异常即触发 MQ 重试；超过 16 次进死信，由对账任务补偿
}
```

面试的标准答法：小规模用 Cache Aside + 事务提交后删缓存 + 失败落重试表，能覆盖 99% 的场景。但只要出现「主从延迟 + 高并发写」，业务双写就兜不住了，这时候必须上 binlog 订阅。判断标准很简单：你的删除动作如果失败了，有没有人能替你重做。双写没有，binlog 有。

---

## L4 一致性是可以分级的：先问业务能忍多长

面试里被问「怎么保证缓存和 DB 一致」，如果直接答方案，就已经输了 —— 因为一致性不是技术指标，是产品指标。同一个问题，商品详情能忍 5 秒，支付结果页 300ms 都嫌长，库存扣减一秒都不能错。

```
成本高 ←──────────────────────────────────────────────→ 成本低

┌──────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐
│ 1 强一致  │  │ 2 读自己写    │  │ 3 单调读      │  │ 4 最终一致      │
│ 窗口 0    │  │ 窗口 300~500ms│  │ 窗口 秒级     │  │ 窗口 50ms~30min │
│ 不做读缓存 │  │ 写后短窗读主库 │  │ 路由固定      │  │ binlog / TTL   │
│ 余额/库存  │  │ 支付结果页    │  │ 列表翻页      │  │ 商品详情/评论数  │
└──────────┘  └──────────────┘  └──────────────┘  └────────────────┘
```

### 「读自己写」的三个实现，成本差 10 倍

| 实现 | 做法 | 代价 | 问题 |
|---|---|---|---|
| 写后读主库 | 写成功后本地记 `dirty:userId` 500ms，期间该用户读走主库 | 主库多 5% 读 | 多实例要广播标记，或接受只有本实例生效 |
| 版本号校验 | 写返回 version，读请求带上，服务端比对缓存 version | 每次读多一个字段 | 客户端要能传版本号，改造面大 |
| 会话粘滞 | 同一用户固定路由到同一节点、同一从库 | 0 | 只解决单调读，不解决读到自己刚写的 |

```java
// 方案一：本地脏标记 + 短窗读主库（最常用）
private final Cache<Long, Boolean> dirtyUsers = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMillis(500))   // 窗口 500ms
        .maximumSize(100_000)
        .build();

public void updateProfile(long userId, Profile p) {
    profileMapper.updateById(p);
    dirtyUsers.put(userId, Boolean.TRUE);          // 该用户 500ms 内读主库
}

public Profile getProfile(long userId) {
    if (Boolean.TRUE.equals(dirtyUsers.getIfPresent(userId))) {
        return profileMapper.selectByIdFromMaster(userId);
    }
    return cacheAside(userId);
}
```

取舍很清楚：用 500ms 内的主库读，换「用户看不到自己刚改的数据」这个投诉的消失。而 500ms 这个数字不是拍的 —— 它要覆盖「写事务提交 → 主从同步 → 缓存删除」这条链路的 P99。主从延迟 P99 是 300ms 就设 500ms，延迟 1 秒就设 1.2 秒。这才是「延迟双删那个数字怎么定」的正确答案：从监控取，不要从文章抄。

### 库存为什么是唯一适合「缓存即真相」的场景

因为库存扣减需要的是原子性，而 Redis 单线程 + Lua 天然给你这个。库存全放 Redis，扣减用 Lua 原子执行「判断 + 减」，DB 只做异步落库和对账。

```lua
-- KEYS[1] = stock:item:123   ARGV[1] = 扣减数量
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -1 end             -- 缓存不存在，回源加载
if stock < tonumber(ARGV[1]) then return 0 end -- 库存不足
redis.call('DECRBY', KEYS[1], ARGV[1])
return stock - tonumber(ARGV[1])
```

这个方案的账单必须一起说出来，否则就是背方案。

- Redis 挂了就不能卖货。可用性和一致性在这里是硬二选一，选了就要接受「主从切换 15 秒内下单全失败」。
- 必须有对账。Redis 是真相源，但真相源也会错（Lua 成功但落库失败、切换丢最后一个 AOF 缓冲）。T+0 对账是必需品。
- 适合的场景很窄：秒杀、票务、限量券 —— 共性是「库存是唯一瓶颈且允许超卖 0」。普通商品的库存用不着这么重。

---
## L5 热 key：一个 key 打满一片，集群剩下的 15 片看戏

Redis Cluster 的扩容逻辑是「数据分片」，不是「请求分片」。这个区别在热 key 面前会导致一个反直觉的结果：你加了 16 个分片，总容量标称 160 万 QPS，但一个 10 万 QPS 的热 key 就能让整个集群只剩 10 万 QPS 的可用容量。

```
① 单 key 突增：16 片里 1 片打满

  QPS  █                       ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁
       █  ← 10 万 QPS          （其余 15 片各 6%）
       ────────────────────────────────────────────────────────────────
       片1 片2 片3 片4 片5 片6 片7 片8 片9 片10 片11 片12 片13 片14 片15 片16

  单分片上限 8~10 万 QPS → 整个集群卡在这一片。扩容无效：加片不会拆开一个 key。

② 分片倾斜：key 设计导致流量全挤在少数 slot

  QPS  █  █  █                 ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁  ▁
       3 片 CPU 95%，13 片 20% —— 典型原因是 hash tag 用错，{10001} 全落同一 slot

③ 大 key：带宽先死，不是 CPU 先死

  1 万 QPS × 10 MB = 100 GB/s 需求
  万兆网卡实际上限       12.5 GB/s
  缺口 8 倍。而且 GET 10MB 单次阻塞 1~3 ms，10 万 QPS 直接打死主线程。
```

都是「Redis 超时」，但 ① 要打散、② 要改 key、③ 要拆 value，方案完全不通用。

### 解法一：本地缓存吃掉热 key（最有效）

热 key 的绝对数量极少 —— 全站真正热的不过几百到几万个，正好是本地缓存的舒适区。100MB 本地缓存能装下 10 万个 1KB 的热点数据，而这 10 万个 key 通常承载 70% 以上的流量。

```java
private final Cache<String, CachedItem> hotCache = Caffeine.newBuilder()
        .maximumWeight(200L * 1024 * 1024)          // 200 MB 权重上限
        .weigher((String k, CachedItem v) -> v.bytes())
        .expireAfterWrite(Duration.ofSeconds(3))    // 热 key 也不能太长
        .recordStats()                              // 必开，否则不知道它是否有效
        .build();

// 关键：不是所有 key 都进本地缓存，只有被探测为热 key 的才进，
// 否则 200MB 会被长尾数据冲刷，热 key 反而被淘汰（LFU 比 LRU 强的原因）
public Item get(String key) {
    if (!hotKeySet.contains(key)) {
        return cacheAside(key);          // 非热 key：只走 Redis
    }
    CachedItem c = hotCache.getIfPresent(key);
    if (c != null) return c.item();
    Item item = cacheAside(key);
    hotCache.put(key, new CachedItem(item));
    return item;
}
```

### 解法二：key 打散，把 1 个 10 万变成 10 个 1 万

```java
// 写时写 10 份（写放大 10 倍，但热 key 的写极少，划算）
public void putHot(String key, String value, int ttlSec) {
    for (int i = 0; i < 10; i++) {
        // 副本 TTL 加 0~3s 随机，避免 10 份同时过期引发重建尖峰
        redis.setex(key + "#" + i,
                ttlSec + ThreadLocalRandom.current().nextInt(3), value);
    }
}

// 读时随机挑一份，天然分摊
public String getHot(String key) {
    return redis.get(key + "#" + ThreadLocalRandom.current().nextInt(10));
}
```

打散的三个代价要主动交代。① 写放大 N 倍，写 QPS 高时反而更糟，所以只对「读远多于写」的 key 用；② 删除/更新要保证 N 份都改到，漏一份就是脏数据，用 pipeline 别循环单发；③ 它只解决「同一个 key 的 QPS」，如果热的是「同一类 key」（某大商家的 10 万个商品同时热），打散解决不了，得上本地缓存 + 多副本读。

### 解法三：从节点读 + 单 key 限流

Redis Cluster 支持 READONLY + 从节点读，一个主带 2 从就是 3 倍读能力。但从节点可能读到旧值（复制延迟），只适合最终一致级别的数据。

```java
// 一行配置把读能力提到 3 倍
LettuceClientConfiguration cfg = LettuceClientConfiguration.builder()
        .readFrom(ReadFrom.REPLICA_PREFERRED)     // 优先读从节点，主节点兜底
        .commandTimeout(Duration.ofMillis(300))   // 从节点慢就快速失败
        .build();
```

最后的兜底是单 key 级限流：这个 key 超过 5 万 QPS 就返回降级内容（默认文案 / 上次快照 / 排队页），而不是让它把整个分片打死。一个 key 打挂整个集群是可用性事故，这个 key 降级只是体验损失。

---

## L6 热 key 探测：为什么固定窗口一定会漏判

热 key 是动态的。你能预判大促的商品，但预判不了明天哪个明星塌房。所以热点必须实时探测，而第一道坎是计数本身的精度。

```
A. 固定窗口（每秒重置）：突刺正好跨在边界上

   |<---- 窗口1 = 5 万 ---->|<---- 窗口2 = 5 万 ---->|
   0s                      1s                      2s
                          ↑↑ 突刺 10 万（0.9s 5 万 + 1.1s 5 万）
   阈值 8 万 → 两个窗口都判定「不热」→ 漏判，没有任何补偿

B. 滑动窗口（2 格环形）：用上一格的残留比例加权

   |<-- 上一格 5 万 × 0.5 -->|<-- 当前格 5 万 × 1.0 -->|
   估算 7.5 万，接近阈值 → 报警。精度 ±50%，内存只多 1 倍

C. Count-Min Sketch（4 行 × 2000 列）
   4 × 2000 × 4 B = 32 KB，统计任意多 key
   误差 ε = e / 宽度 ≈ 0.14%，失败概率 δ = e^-4 ≈ 1.8%
   只高估不低估 → 用作「判热」是安全方向（宁可误报）
```

### 从「探测」到「生效」：三段式架构

京东的 hotkey 方案是工业标准。

1. Client 端统计：每个实例本地用 CMS 统计 key 频次，每 5 秒把 top 10 上报 worker。在客户端算不增加 Redis 一次往返，且能看到「业务视角的 key」。
2. Worker 聚合：把所有实例的 top 10 按 key 求和，超过阈值（如 5 秒 5000 次 = 1000 QPS）判定为热 key。阈值必须按「聚合后」算。
3. 规则推送：把热 key 列表推到 etcd / 配置中心，客户端 watch 变更后把 key 加入本地缓存白名单。端到端延迟 5~15 秒。

```java
public class HotKeyReporter {
    // 宽度 2000、深度 4，用 4 个不同种子做哈希
    private final CountMinSketch sketch = new CountMinSketch(2000, 4, 0);
    private final ScheduledExecutorService ses =
            Executors.newSingleThreadScheduledExecutor();

    public void record(String key) {
        sketch.add(key.getBytes(StandardCharsets.UTF_8), 1);
    }

    public HotKeyReporter() {
        // 每 5 秒上报 top 10，然后必须 reset，
        // 否则计的是「历史累计」而不是「当前速率」
        ses.scheduleAtFixedRate(() -> {
            List<KeyCount> top = sketch.topK(10);
            reporterClient.report(top);
            sketch.reset();                    // 忘记 reset 是最常见的 bug
        }, 5, 5, TimeUnit.SECONDS);
    }
}
```

三个真实踩过的坑。

- 忘记 reset：统计的是「启动以来累计次数」，所有老 key 永远排最前，新热点永远进不了 top 10。
- 单实例阈值：20 个实例每个 500 QPS，单实例看都不到阈值，聚合后是 1 万 QPS 的真热点，直接漏判。
- 热 key 名单只增不减：热点过去后 key 还在白名单里，本地缓存被历史热点占满。名单必须带 TTL，比如 5 分钟不热就移除。

Redis 侧工具都有明确边界，说清边界比背命令加分。

| 工具 | 能看到什么 | 边界 |
|---|---|---|
| `redis-cli --hotkeys` | 按 LFU 计数排序的 key | 必须 maxmemory-policy 是 LFU；采样统计，冷启动后不准 |
| `MONITOR` | 全部命令实时流 | 10 万 QPS 输出 10 万行/秒，CPU 打满，只能短时用 |
| `LATENCY MONITOR` | 超过阈值的命令耗时 | 只能说「慢了」，不能说「哪个 key」 |
| Proxy 层统计 | 最准，能看到全量 | 多一跳 +0.2ms，且要自己维护 Proxy |

---

## L7 穿透：布隆过滤器的三个数字，比它的原理更值得背

穿透是「查一个一定不存在的数据」，缓存永远 miss，每次都打到 DB。攻击者用随机 ID 刷 10 万 QPS，DB 就没了。布隆过滤器是标准解，但它的大小必须算：

```
m = -n · ln p / (ln 2)²        k = (m / n) · ln 2
```

| 元素数 n | 误判率 p | 位数组 m | 内存 | 哈希个数 k | 10 万次攻击漏进 DB |
|---|---|---|---|---|---|
| 1000 万 | 1% | 9585 万 bit | 11.4 MB | 7 | 1000 次 |
| 1000 万 | 0.1% | 1.44 亿 bit | 17.9 MB | 10 | 100 次 |
| 1000 万 | 0.01% | 1.92 亿 bit | 22.9 MB | 13 | 10 次 |
| 1 亿 | 1% | 9.59 亿 bit | 114 MB | 7 | 1000 次 |

第一个结论：误判率降 10 倍，内存只涨 57%（11.4 → 17.9 → 22.9）。这是布隆最反直觉的性质，所以工程上根本不该用 1%，直接用 0.1%，多花 6MB。

第二个结论，也是面试的分水岭：误判率 1% 意味着 10 万次攻击仍有 1000 次漏进 DB。所以布隆不能单独用，它必须和「空值缓存 + 单 IP 限流」组成三件套：布隆挡掉 99% 的随机 ID，空值缓存挡掉重复的同一个假 ID，限流挡掉绕过前两者的高频来源。

```
32 位示意（■ = 1，□ = 0）

item:1001（真实存在）  h1=3, h2=17, h3=26
  □ ■ □ ■ □ □ ■ □ □ ■ □ □ ■ □ □ □ □ ■ □ □ □ □ ■ □ □ □ ■ □ □ ■ □ □
        ↑3      ↑17              ↑26
    三位全为 1 → 放行

item:99999（不存在）  h1=9, h2=17, h3=26
  □ ■ □ ■ □ □ ■ □ □ ■ □ □ ■ □ □ □ □ ■ □ □ □ □ ■ □ □ □ ■ □ □ ■ □ □
          ↑9      ↑17              ↑26
    三位恰好都被别的 key 置 1 → 误判放行（概率 1%）

item:88888（不存在）  h1=5, h2=17, h3=26
  □ ■ □ ■ □ □ ■ □ □ ■ □ □ ■ □ □ □ □ ■ □ □ □ □ ■ □ □ □ ■ □ □ ■ □ □
      ↑5 为 0 → 直接拒绝，这一位就是「绝对不存在」的证明
```

布隆只回答「一定不存在」或「可能存在」。它宁可误报、绝不漏报，这个方向性决定了它只能做前置过滤。

```java
// 三段式防穿透：布隆 → 空值缓存 → 限流，缺一段就漏
public Item getItem(long id) {
    // ① 布隆：一定不存在的直接返回，成本 7 次位运算 ≈ 100ns，零网络
    if (!bloom.mightContain(id)) {
        return null;
    }
    // ② 空值缓存：同一个假 ID 被重复刷时，连 Redis 之后都不再回源
    String key = "item:" + id;
    String cached = redis.get(key);
    if (cached != null) {
        return NULL_MARK.equals(cached) ? null : deserialize(cached);
    }
    // ③ 回源 + 缓存空值（TTL 要短，60s；太长会被「先删后建」的业务误伤）
    Item item = itemMapper.selectById(id);
    if (item == null) {
        redis.setex(key, 60, NULL_MARK);
        return null;
    }
    redis.setex(key, 1800, serialize(item));
    return item;
}
```

空值缓存的隐藏代价：攻击者用「永不重复的随机 ID」时，每个 ID 都会在 Redis 留一个 60 秒的 NULL 标记。10 万 QPS × 60s = 600 万个垃圾 key，直接把内存打满，触发淘汰把真数据挤出去 —— 这比穿透本身更致命。所以布隆在前、限流在后，空值缓存只负责「重复假 ID」这一种情况。

### 布隆的三个坑

1. 重启要重建：1000 万 key 从 DB 扫一遍要 10 分钟左右，这 10 分钟等于没有布隆，全部穿透。位数组要持久化（Redis bitmap 或本地文件 mmap）。
2. 数据变了要同步：新增商品必须加进布隆，否则新商品被判「一定不存在」—— 这是业务级 bug，不是性能问题。删除不能做，只能逻辑删除并保留在布隆里。
3. 不支持范围/模糊查询：只能做精确 key 的存在性判断。防「按条件查列表」的穿透，得靠参数校验 + 空结果缓存。

---

## L8 击穿与雪崩：一个是并发问题，一个是时间问题

击穿是「单个热 key 过期，1000 个并发同时回源」；雪崩是「大量 key 同时过期，DB 被同一秒的重建请求打穿」。前者是并发控制问题，后者是时间分布问题，方案不能互换。

```
A. 互斥锁重建（等，但一定拿到新值）      B. 逻辑过期（不等，但读到旧值）

1000 并发读到 miss                       value 里带 expireAt，物理永不过期
        ↓                                        ↓
SETNX cache:lock:item:123                1000 并发都判出「已逻辑过期」
1 个成功 → 查 DB → 写缓存 → 释放              全部立刻返回旧值 —— 0 阻塞
        ↓                                        ↓
999 个自旋等待：50ms × 4 次               只有 1 个线程抢到本地锁去异步刷新
        ↓                                        ↓
DB 查询 1 次，RT = 200 ms                 DB 查询 1 次，RT = 0

风险：锁超时 < 重建耗时 → 重复重建          代价：刷新期间返回过期数据
必须给锁 TTL + 唯一 value 防误删           适合商品详情、榜单；不适合价格、库存
```

```java
// A. 互斥锁重建：三个细节决定它是否真的安全
public Item getWithMutex(long id) {
    String key = "item:" + id;
    Item cached = redis.get(key);
    if (cached != null) return cached;

    String lockKey = "lock:" + key;
    String token = UUID.randomUUID().toString();
    // ① TTL 必须大于重建耗时，否则锁提前释放 → 第二个线程也去重建
    boolean locked = redis.setnx(lockKey, token, 3, TimeUnit.SECONDS);
    if (locked) {
        try {
            // ② 双重检查：拿到锁后必须再读一次缓存，可能别人刚建好
            cached = redis.get(key);
            if (cached != null) return cached;
            Item item = itemMapper.selectById(id);
            redis.setex(key, 1800, serialize(item));
            return item;
        } finally {
            // ③ 只能删自己的锁：Lua 比对 token，否则会删掉别人续期后的锁
            redis.eval(LUA_UNLOCK, List.of(lockKey), List.of(token));
        }
    }
    for (int i = 0; i < 4; i++) {          // 没抢到锁：短暂自旋，别死等
        sleep(50);
        cached = redis.get(key);
        if (cached != null) return cached;
    }
    return itemMapper.selectById(id);      // 兜底，宁可多查一次也不能报错
}
```

### 雪崩：随机 TTL 有效，但你得先算 R 该设多大

先纠正一个流传很广的错误说法：「TTL 加随机值就能防雪崩」。它对，也不对 —— 取决于随机范围 R 和 key 数量的比值。

设同一时刻有 N 个 key 被写入（发版预热、批量导入、缓存重建都会造成这种「同时写入」），基础 TTL = T，随机偏移 U(0, R)。过期时刻均匀落在 [T, T+R]：

```
重建 QPS ≈ N / R      →     R ≥ N / (DB 可承受的重建 QPS)
```

10 万个 key 在 t=1800s 被同时写入，三种策略：

```
A. 不加随机：全部在 1800 s 过期
   █ 10 万/s ← 瞬时
   每次重建 5 ms → 需 500 个并发线程；DB 连接池 200 → 排队、超时、雪崩
   └1800s

B. 随机 R = 300 s（按公式算出来的）
   ████████████████████████████████  333/s
   摊平成 300 s 平台：10 万 / 300 ≈ 333/s，每次 5 ms → 只需 1.7 个并发
   └1800s ────────────────── 2100s

C. 随机 R = 10 s（「随便加个随机」的典型）
   ██ 1 万/s ← 仍然雪崩
   50 个并发线程抢 200 连接，P99 已经开始飙
   └1800s
```

R 的取值逻辑：N = 10 万 key，DB 能承受 500 QPS 重建 → R ≥ 10 万 / 500 = 200s。

这个公式顺手解释了「为什么随机 TTL 经常无效」的三个真实原因。

| 失效原因 | 机制 | 判断 |
|---|---|---|
| R 远小于 N / 容忍 QPS | 随机了 10 秒，10 万 key 仍挤在 10 秒内 | 最常见的错误：只加了随机，没算过 |
| R 相对 T 太大 | T=60s 却随机 ±300s，一半 key 的实际存活期翻了好几倍 | 防雪崩了，但一致性窗口不可控 |
| 不是「同时过期」而是「同时 miss」 | 发版重启后本地和 Redis 都空，全是 miss，跟 TTL 无关 | 随机 TTL 完全无效，必须靠预热 |

还有一层常被忽略：Redis 自己的过期删除成本。同一秒 10 万个 key 过期，定期删除会连续扫描并释放，单次删除约 0.5~1μs，10 万次就是 50~100ms 的主线程阻塞。这时候所有请求 P99 都翻 10 倍，而 DB 还没开始被打。「同时过期」的伤害是双份的：先卡死 Redis，再打穿 DB。

### 预热：解决「同时 miss」的唯一解

```python
# 分批 + 限速 + 加随机 TTL，三件事缺一不可
def warmup(hot_keys, batch=500, qps_limit=500):
    for i in range(0, len(hot_keys), batch):
        pipe = redis.pipeline()                    # 一次往返写 500 个
        for k in hot_keys[i:i + batch]:
            # 关键：预热时就把 TTL 打散，否则 30 分钟后又是同一秒集体过期
            ttl = 1800 + random.randint(0, 300)
            pipe.setex(k, ttl, serialize(load_from_db(k)))
        pipe.execute()
        time.sleep(batch / qps_limit)              # 限速到 500 QPS，DB 无感

# 调用时机：服务就绪前（readinessProbe 未通过时不接流量）
# 顺序：先热 key（top 1%），再长尾 —— 热 key 先到位，命中率立刻见效
```

为什么预热能解决随机 TTL 解决不了的问题：随机 TTL 处理的是「过期时刻」的分布，预热处理的是「加载时刻」的分布。发版重启后缓存全空，全部请求同时 miss，这时候 TTL 怎么随机都没用。

### 真正的兜底是三层

- 本地缓存挡第一层：热 key 在本地，Redis 全挂时本地还能撑住 70% 的读。
- 限流挡第二层：漏过去的请求限到 DB 能承受的 QPS，其余快速失败。让 20% 的用户看到降级页，好过 100% 的用户看到 502。
- 降级挡第三层：返回兜底数据（上次快照、默认推荐、静态文案）。缓存故障时最忌讳透传错误。

```java
public Item getWithFallback(long id) {
    Item local = localCache.getIfPresent("item:" + id);   // L1 本地
    if (local != null) return local;

    try {                                                  // L2 Redis
        Item cached = redis.getWithTimeout("item:" + id, Duration.ofMillis(200));
        if (cached != null) { localCache.put("item:" + id, cached); return cached; }
    } catch (Exception ignore) {
        // 别在这里重试，重试会把 RT 从 200ms 放大到 600ms
    }

    if (!dbQueryLimiter.tryAcquire()) {                    // L3 限流 + 降级
        return fallbackSnapshot(id);   // 上次快照 / 默认值，绝不抛异常
    }
    return itemMapper.selectById(id);
}
```

---

## L9 治理视角：五道闸门、一笔内存账、一张淘汰表

前面八节讲「怎么让缓存对」，这一节讲「怎么让缓存不倒」。先看一个 100 万 QPS 的读链路怎么逐层衰减。

```
入口         ████████████████████████████████████████████████████  100 万 QPS
             │ 网关层：鉴权、WAF、灰度
本地缓存      ████████████████████████████  30 万（命中 70%，RT 0.2 μs）
             │ 挡掉热 key：Redis 全挂时唯一还能工作的层
Redis        ███  1.5 万（命中 95%，RT 0.6 ms）
             │ 挡掉长尾：靠大内存 + 高并发，不讲情面地 miss
限流         █  5,000（另外 1 万直接降级）
             │ 这层存在的意义：宁可 1 万人看降级页，不要 100 万人看 502
DB           █  5,000 QPS（单表读写混合的实际上限）
```

这个漏斗里最容易被砍掉的是「限流」，但它是唯一能在 Redis 挂掉时保护 DB 的层。

### 内存账：200B 的 value 实际占 328B

| 组成 | 字节 | 说明 |
|---|---|---|
| key SDS | 40 | 32B 数据 + 3B 头 + jemalloc 对齐到 40 |
| value SDS | 224 | 200B 数据 + 3B 头 + 对齐到 224 |
| redisObject | 16 | type / encoding / lru / refcount |
| dictEntry | 24 | 哈希桶里的链表节点，含 key 指针与 next |
| expires entry | 24 | 只有设了 TTL 才有 |
| 合计 | 328 | 数据本身只有 232B，放大 41% |

1000 万条 → 3.28 GB。加哈希表桶（rehash 期间两张表，1000 万 × 8B × 2 = 160MB）→ 3.44 GB。再加从库 1 份 + AOF rewrite 期间 COW 峰值 1.5 倍 → 单实例规划要按 5.2 GB，主从共 10.4 GB。

所以「10GB 放 1000 万条 200B 数据」刚好卡在边缘，没有任何余量，一旦碎片率上到 1.5 就要 OOM。

两个常见误算。

- jemalloc 的阶梯分配：SDS 不按实际长度分配，而是对齐到 8/16/32/48/64/80/96/112/128… 的档位。200B 会分到 224B，这是 24B 纯浪费。value 长度设计成「刚好在档位边界下方一点」最省。
- 过期字典是独立的一张表：每个带 TTL 的 key 在 expires 字典里还有一条 dictEntry，多 24B。全量设 TTL 比不设多 7% 内存。

### 淘汰策略：LRU 会被一次全表扫描打成废人

```
              LRU（最近访问）      LFU（访问频次）        random          ttl
allkeys     allkeys-lru           allkeys-lfu ★        allkeys-random   （不存在）
            被扫描污染            纯缓存首选            命中率最差
volatile    volatile-lru          volatile-lfu         volatile-random  volatile-ttl
            混存可用              缓存+持久数据混存      几乎不用         快过期的先淘汰

noeviction：内存满时写入直接报错 OOM command not allowed
            只适合「数据绝对不能丢」的存储型用法。做缓存时选它 = 定时炸弹
```

为什么 LFU 抗扫描：扫描任务的每个 key 只被访问 1 次，计数停在 1，永远竞争不过热 key 的计数 200+。LRU 只看「最近有没有被访问」，一次「导出全部商品」的运营任务逐个 GET 200 万个 key，就全部插到 LRU 链表头部，把原本 100 万热数据挤到尾部淘汰 —— 一次导出任务，命中率从 96% 掉到 30%，DB 直接被打穿。

LFU 计数器是 8 bit 的对数概率计数（0~255），配两个参数：`lfu-log-factor`（增长斜率，默认 10）、`lfu-decay-time`（衰减周期，默认 1 分钟）。坑在冷启动：新 key 计数为 5，很可能一进来就被淘汰。

```bash
# 生产配置：纯缓存实例
maxmemory 10gb
maxmemory-policy allkeys-lfu
lfu-log-factor 10
lfu-decay-time 1           # 每分钟衰减一半，避免历史热点永久占位

# 混存实例（既有缓存又有队列/锁）
maxmemory-policy volatile-lfu    # 只淘汰带 TTL 的，队列和锁不会被淘汰
# 代价：带 TTL 的 key 被淘汰光了内存还是满的 → 必须监控 used_memory 曲线
```

### 大 key：先测出来，再异步删

| 危害 | 量化 | 处理 |
|---|---|---|
| 阻塞主线程 | DEL 一个 100 万元素的 Hash ≈ 300ms~1s，期间所有请求排队 | 用 UNLINK（4.0+，后台线程释放） |
| 网络带宽 | 1 万 QPS × 10MB = 100 GB/s，万兆网卡上限 12.5 GB/s | 拆分：大 String 拆成 Hash 的多个 field |
| 集群倾斜 | 一个 5GB 的 key 落在某片，该片内存是别片的 3 倍 | 单 key 控制在 100KB 内 |
| 迁移卡死 | Cluster 迁 slot 时按 key 搬，5GB 的 key 让迁移卡几分钟 | 同上，单 key 体积是可运维性前提 |

```bash
# 检测三件套（都有代价，别在生产高峰跑）
redis-cli --bigkeys                    # 采样统计，会短暂阻塞；只看每类型 top1
redis-cli --memkeys                    # 4.0+，按内存排序
MEMORY USAGE bigkey SAMPLES 5          # 单 key 估算，SAMPLES 越小越快越不准

# 删除：千万不要 DEL 大集合
UNLINK bigkey                          # 异步释放，主线程只摘链
# 更稳的做法：分批删
redis-cli HSCAN bigkey 0 COUNT 100 | ...  # 逐批 HDEL，每批 100 个
```

治理的三个常设指标：命中率 > 90%，掉 5 个点就告警；内存碎片率 > 1.5 且 used_memory > 1GB 就处理（`activedefrag yes` 或择机重启）；单分片 CPU 标准差超过 20 个点说明有倾斜，别等某片跑到 100% 才发现。

---

## L10 数字速查

| 结论 | 数字 | 出处 / 推导 |
|---|---|---|
| 双写两步联合成功率 | 99.85% | 0.9995 × 0.999，日 10 万次更新 = 150 条脏缓存 |
| 热 key 脏读放大 | 1.35 亿次 | 150 条 × 900 s × 1000 QPS |
| 双写 vs binlog 的不一致窗口 | 1000 倍 | TTL 30 min vs 消费延迟 200 ms |
| 本地缓存内存放大 | = 实例数 | 20 实例 × 100 MB = 2 GB，有效数据只有 100 MB |
| Cache Aside ② 的残余窗口 | 3~5 ms | 读侧「读 DB + 写缓存」需完整穿越写侧 |
| 延迟双删的 sleep 该设多少 | 主从延迟 P99 + 200 ms | 不是固定的 500 ms，从监控取 |
| 布隆 1000 万 key @ 1% 误判 | 11.4 MB / 7 个哈希 | m = -n·ln p / (ln 2)²，k = (m/n)·ln 2 |
| 布隆误判率降 10 倍的代价 | 内存 +57% | 11.4 → 17.9 → 22.9 MB |
| CMS 统计任意 key 的内存 | 32 KB | 4 行 × 2000 列 × 4 B，误差 0.14% |
| 随机 TTL 的范围 R | ≥ N / DB 容忍 QPS | 10 万 key / 500 QPS = 200 s |
| Redis 单 key 内存放大 | 1.41 倍 | 328 B / 232 B，含 dictEntry、redisObject、对齐 |
| 1000 万条 200B 的实际占用 | 3.44 GB | 加从库 + COW → 规划 10.4 GB |
| 单分片读上限 | 8~10 万 QPS | 简单 GET，pipeline 不适用时 |
| 一次 200 万 key 的导出任务 | 命中率 96% → 30% | LRU 被扫描污染，LFU 免疫 |
| DEL 100 万元素 Hash | 阻塞 300 ms~1 s | 改用 UNLINK 或 HSCAN + HDEL 分批 |

---

## L11 14 条问答

**Q1：为什么是「删缓存」而不是「更新缓存」？**
三个理由。① 并发写会互相覆盖，后写的更旧值可能覆盖先写的新值，缓存永久错到下次写；删除最坏只是 miss 一次。② 懒加载省算力，写 100 次只读 1 次时更新缓存算了 99 次没人看。③ 缓存值常是聚合结果，更新要重算整个聚合，删除只要一个 DEL。唯一例外是 binlog 顺序消费场景，那里能保证顺序。

**Q2：先删缓存再更新 DB，到底错在哪？**
错在窗口宽度。从删缓存到 DB 更新完成这整段时间（一次写事务，5ms 起，遇行锁等待可到 100ms），任何读请求都会 miss、读旧 DB、回填旧值，而且这个旧值会活到 TTL 到期。它不是「可能错」，是「窗口内一定错」。

**Q3：延迟双删那个 500ms 是怎么定的？**
大部分人答不出，因为那个数字本来就是拍的。它唯一的目标是覆盖主从延迟：第二次删除必须晚于「主从延迟 D + 一次读回填耗时 R」。同机房 D 的 P99 约 20~50ms，跨机房 100~300ms，R 约 2~5ms，理论上 100ms 就够。正确做法是取监控里主从延迟的 P99 加 200ms 余量。

**Q4：删缓存失败了怎么办？**
这是及格和良好的分界线。① 删除放在 afterCommit 里，不能写在事务方法体内（提交前删等于退化成最坏时序）；② 失败落本地消息表由定时任务重试，不能只打日志；③ 重试要带版本校验，避免「重试把更新的新值又删了」。再往上就是 binlog 订阅，天生可重试 16 次加死信兜底。

**Q5：为什么说 binlog 订阅是唯一可靠的方案？**
三个本质差异。① 单一真相源：DB 提交成功 binlog 必然存在，缓存只是投影，投影错了随时可重算。② 不侵入业务：批量更新、运维直改库、老系统写入都能覆盖，双写做不到 —— 只要有一条写路径忘了删缓存就留脏数据。③ 可重放：位点可回溯，补数据、重建缓存都能重放。代价是多一套 canal + MQ 的运维。

**Q6：主从延迟导致的脏读怎么根治？**
三层。① 写后短窗读主库：本地记 `dirty:userId` 500ms，期间该用户读走主库。② binlog 订阅删缓存：消费延迟通常 50~200ms，比主从延迟还短，等于缓存失效跑在从库同步之前。③ 关键读强制主库：支付结果、订单详情直接走主库，别在这里省钱。

**Q7：多实例的本地缓存怎么保证一致？**
先说结论：做不到强一致，只能做到有界窗口。方案是三层叠加：版本号保证正确性（版本由 Redis 的 INCR 产生）、广播降低延迟（MQ 广播或 pub/sub，但 pub/sub 断连即丢）、TTL 兜底（5~30s）。三层都在，就不需要讨论「消息丢了怎么办」。

**Q8：能不能用分布式事务保证缓存和 DB 强一致？**
技术上可以，工程上不该。① 缓存没有事务回滚能力，Redis 的 MULTI 只是命令批处理。② 2PC 的协调者一旦挂掉，资源锁不释放，可用性从 99.99% 掉到 99%。③ 更根本的是没必要：缓存是可丢失的加速层，为它付出分布式事务的代价等于给自行车装安全带。真正需要强一致的地方，做法是「不缓存」或「缓存即真相 + 对账」。

**Q9：热 key 怎么发现？发现之后怎么办？**
发现靠「客户端 CMS 统计 → worker 聚合 → 配置中心推送」三段式，端到端 5~15 秒。三个坑：忘记 reset、单实例阈值漏判、热 key 名单只增不减。处置四层：本地缓存（最有效）、key 打散（10 副本，写放大 10 倍）、从节点读、单 key 限流降级。

**Q10：布隆过滤器能不能删除元素？误判率高怎么办？**
不能删。删一个元素要把它置的 k 位清零，会连带把共用这些位的其它 key 判成「不存在」—— 假阴性比误判严重得多，因为它会把真实存在的数据挡在外面。替代方案：Counting Bloom（内存 ×4~8）、布谷鸟过滤器、按天分桶重建。误判率高就加内存：误判率降 10 倍只多 57% 内存，所以直接用 0.1% 而不是 1%。

**Q11：缓存雪崩，随机 TTL 为什么有时无效？**
因为随机范围没算。重建速率约等于 N/R，10 万 key 随机 10 秒仍然是 1 万/s，照样打死 DB。R 必须 ≥ N / DB 容忍的重建 QPS（10 万 / 500 = 200s）。另外两种失效：R 相对 T 太大（一致性窗口失控）、以及根本不是「同时过期」而是「同时 miss」（发版重启后都空，随机 TTL 完全无效，只能靠预热）。

**Q12：击穿用互斥锁还是逻辑过期？**
看业务能不能忍旧值。互斥锁：1000 并发只有 1 个查 DB，其余自旋等待，RT 等于重建耗时（200ms 级），拿到的一定是新值，适合价格、库存。逻辑过期：value 带 expireAt、物理永不过期，读到过期立刻返回旧值加异步刷新，RT 不增加，适合商品详情、榜单、推荐。互斥锁三个细节：锁 TTL 要大于重建耗时、拿到锁后要双重检查、释放锁要用 Lua 比对 token。

**Q13：线上发现缓存和 DB 数据对不上，怎么排查？**
四步不要跳。① 确认是不是缓存问题：直接查 DB 看值对不对，DB 对缓存错才往下查。② 看这条数据的 TTL 和最后修改时间：修改时间晚于缓存写入时间，说明删除丢了。③ 查删除链路：双写看有没有 evict failed 日志、有没有事务提交前就删的代码；binlog 看 canal 位点有没有滞后、MQ 有没有堆积。④ 确认影响面：按 TTL 和 QPS 估脏读次数，决定是手动删这一个 key 还是批量重建。

**Q14：缓存该设多大？命中率多少算合格？**
容量按头部数据定，不是按全量。Zipf 分布下缓存 top 1% 覆盖 72% 请求，top 10% 覆盖 86%，再多就是长尾税（多 5 倍内存只换 4% 命中率）。命中率按业务定：商品详情 90% 合格，配置类应该 99.9%，本地缓存 70% 就算发挥了作用。真正该盯的不是绝对命中率，是「掉了 5 个点」这个变化。

---

## L12 3 道模拟面试题

### 模拟题 1：设计商品详情页缓存，QPS 50 万，商品 1000 万，改价 1 秒内生效

考察点：分层设计 / 命中率推导 / 一致性窗口 / 失效链路

不及格：「用 Redis 缓存商品详情，30 分钟过期，更新的时候删缓存。」没有容量账，没说明 50 万 QPS 怎么扛，1 秒生效这个硬要求直接被 TTL 违背。

及格：说清三层 —— 本地 Caffeine + Redis + DB，本地 3 秒 TTL，Redis 30 分钟，更新时删 Redis 加广播删本地。能说出 50 万 QPS 靠本地缓存扛掉大部分。

良好：把数字算出来。① 容量：1000 万商品按 1KB 算全量 10GB，但 top 1%（10 万条）覆盖 72% 请求，本地 100MB 就装得下，Redis 存 top 10%（100 万条）1GB 足够。② 流量：50 万 QPS，本地命中 70% → 15 万到 Redis，Redis 命中 95% → 7500 到 DB。③ 一致性：价格单独拆出来 —— 详情走最终一致（binlog 1 秒内），价格走「读自己写 + 短 TTL」甚至不缓存。

优秀：再补四个工程判断。

- 拆缓存粒度：详情页拆成「基础信息（TTL 30min）+ 价格库存（TTL 3s 或不缓存）+ 促销（TTL 1min）」。混在一起缓存，任何一个字段变都要整条失效，命中率会被拖垮。这才是「1 秒生效」的真正解法 —— 不是把 TTL 调到 1 秒，而是把会变的字段拆出去。
- 失效链路：binlog → canal → MQ（按 item_id 分区保序）→ 消费者删 Redis 加广播版本号，端到端 P99 控制在 500ms 内。
- 本地缓存一致性：不追求实时失效，用「版本号 + 3s TTL」。价格特殊处理：比对本地 entry 的 loadAt 是否落在最近一次调价的 3 秒内，是就走 Redis。
- 兜底与验收：Redis 挂了本地还能撑 70%；DB 前限流 2000 QPS；详情页降级到静态快照而不是报错。验收指标是「改价 P99 端到端 800ms」，要有监控埋点，不能只说方案不验证。

### 模拟题 2：大促零点 Redis 某片 CPU 100%，接口大面积超时，怎么定位和处置

考察点：故障定位 / 热 key 判别 / 应急处置 / 根因修复

不及格：「扩容 Redis。」扩容对单 key 热点完全无效，这是本题最大的陷阱。

及格：先看监控 —— 某一片 CPU 高而其它片正常，说明是热 key 或分片倾斜，不是整体容量不够。处置：把这个 key 加到本地缓存，或者限流降级。事后做 key 打散。

良好：分三步。① 判别：看 CPU 分布标准差，一片 100% 其余 20% → 热点或倾斜；全部 80%+ → 容量不足，那就扩容。再看 `--hotkeys` 或 proxy 日志定位具体 key。② 应急：本地缓存加热补丁，或把这个 key 写到配置中心让所有实例本地读。③ 根因：热 key 探测上线，大促爆款提前预热到本地。

优秀：补四个层次。

- 先排除「假热点」：CPU 高不一定是 QPS 高。SLOWLOG 看有没有慢命令（KEYS、大 hash 的 HGETALL、大集合的 SMEMBERS）；INFO commandstats 看是不是某个命令的 usec_per_call 异常。如果是大 key 导致的慢查询，加本地缓存没用，得拆 key。
- 算账：单个 key 的 QPS = 该片总 QPS × 该 key 占比。某片 10 万 QPS 里 8 万是一个 key → 这个 key 是根因；10 万 QPS 均匀分布在 100 万个 key 上 → 片容量不够，扩容有效。这个判别决定后面所有动作。
- 处置优先级：先降级（返回兜底数据，让用户能看不能用）→ 再限流（保护 Redis 不被彻底打挂）→ 最后才是热补丁。顺序反了会延长故障。
- 事后三件事：热点探测上线（5~15 秒发现）、大促前按 3 倍峰值压测、以及「单 key 打满一片」这个故障模式的演练预案。

### 模拟题 3：运营改了商品价格，用户端还是旧价，20 分钟才恢复

考察点：排查路径 / 一致性窗口估算 / 方案选型 / 流程改进

不及格：「清一下缓存就好了。」没有定位为什么没删掉，下次还会发生。

及格：先手动删这个 key 止血，然后查删除日志：是删除失败了，还是根本没执行删除。20 分钟这个时长很可疑 —— 如果是 TTL 到期才恢复，说明删除完全丢了。

良好：按 20 分钟反推。① 如果 TTL 是 30 分钟，20 分钟恢复更像「延时任务生效」或「用户命中另一节点读到新值」。② 查代码：删除是否写在 `@Transactional` 方法体里（提交前删 → 并发读回填旧值）；是否只在「单品改价」路径删了缓存，而运营用的是「批量改价」接口 —— 漏了删除逻辑，这是最常见的原因。③ 查主从延迟：改价后立刻有大量读，从库还是旧值，回填后活到 TTL。

优秀：四层根治。

- 漏路径是系统性风险，不是代码 bug：只要有 N 个写入口（单品改价、批量改价、导入 Excel、运营后台、数据修复脚本），就有 N 次忘记删缓存的机会。正确做法是把失效从「业务代码职责」变成「数据变更事件」—— 上 binlog 订阅，任何写路径都自动覆盖，包括运维直连数据库改数据。
- 价格这个字段不该和详情一起缓存：它高频变更且强时效，混在 30 分钟 TTL 的详情缓存里，等于把「1 秒生效」的需求交给「30 分钟窗口」来兜。拆出来单独短 TTL 或不缓存。
- 加对账而不是只加日志：每分钟抽样比对 Redis 里的价格和 DB 里的价格，不一致就告警并自动修正。这是唯一能发现「沉默失败」的手段 —— 删除失败的日志可能压根没打。
- 验收要有数字：改价后 P99 生效时间 < 1s，用埋点度量而不是靠用户投诉。这次能发现是因为用户投诉，说明监控体系缺了「缓存与 DB 差异」这一环。

---

## 下期预告

第 18 期：接口幂等与防重设计 —— token / 唯一索引 / 状态机 / 分布式锁四种方案的边界，「幂等」和「去重」不是一回事，以及重复支付、重复下单、MQ 重复消费的三种真实时序。
