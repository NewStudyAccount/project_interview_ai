## 06 四种超时：出事时到底谁先响

连接池相关超时至少有四层，配置错顺序就会出现「明明 SQL 只要 2ms，接口却卡了 30 秒」：

| 超时 | 归属 | 典型值 | 管的是 |
|---|---|---|---|
| `connectionTimeout` | 连接池 | 30s（HikariCP 默认） | 在池里等一个**可用连接**的最长时间 |
| `validationTimeout` | 连接池 | 5s | 借出前校验连接是否还活着 |
| `socketTimeout` | 驱动 | 0（不超时！） | 一条 SQL 发出去后等结果的最长时间 |
| 事务/请求超时 | 框架 | 3s / 5s | 整个业务操作的时间预算 |

真 HikariCP 5.1.0 跑池耗尽（`maximumPoolSize=2`、`connectionTimeout=1500`、借满后借第三个）：

```text
① 第3个连接等待 1517ms 后失败
   异常类型: java.sql.SQLTransientConnectionException
   异常文案: demo-pool - Connection is not available, request timed out after 1508ms
             (total=2, active=2, idle=0, waiting=0)
```

三个细节，都是能当场加分的：

1. **抛的是 `SQLTransientConnectionException`**，`SQLState` 属于 08 类（连接异常），Spring 会把它翻译成 `CannotGetJdbcConnectionException`/`TransientDataAccessResourceException`，**不是** `BadSqlGrammarException`。你看到"连不上"类异常时，先怀疑池，不要先怀疑 SQL。
2. **文案自带四元组**：`total=2, active=2, idle=0, waiting=0`。这一行就是现场诊断：`active == total` 且 `idle == 0` → 池满；`waiting` 大于 0 → 有排队；`active` 长期顶格且不回落 → 泄漏。
3. **实测 1508ms > 配置 1500ms，多 8ms**。超时不是精确闹钟：等待循环按 `30ms` 递增轮询（`getConnection` 内部有 `if (timeout > 0) { ... }` 的递减逻辑），加上一次 HouseKeeper 的 50ms 周期对齐，实际会超配 0.5%~2%。做链路预算时**不要按标称值算**，留 5% 余量。

正确的嵌套关系，只有一条规则：**内层必须比外层小。**

```java
// 反例：外层请求预算 3s，池等待 30s —— 池还没超时，请求早被网关砍了
spring.datasource.hikari.connection-timeout=30000
server.tomcat.connection-timeout=3000

// 正例：预算倒推，每层留余量
// 请求 3000ms → 事务 2000ms → SQL 1500ms → 池等待 800ms
spring.datasource.hikari.connection-timeout=800
spring.datasource.hikari.validation-timeout=500
spring.datasource.hikari.max-lifetime=1200000   // 必须 < DB 的 wait_timeout
spring.datasource.hikari.leak-detection-threshold=10000
```

为什么 `max-lifetime` 必须小于数据库的 `wait_timeout`？因为**主动关掉一个自己知道已经死掉的连接，比借出去才发现它已死要好**。MySQL 默认 `wait_timeout=28800s`（8 小时），但中间还有防火墙、云负载均衡的空闲连接回收（常见 300~900s）。所以 `maxLifetime` 一般设 20~30 分钟，留足余量——HikariCP 的做法是让连接在 `maxLifetime ± 2.5%` 的随机区间内退休，避免所有连接同一秒集体退休。

---

## 07 连接泄漏：不是斜坡，是断崖

所有讲连接泄漏的文章都会说「连接不还，池子就满了」。但现场到底是什么形状，很少有人画。自建池实测（`pool=10`、`connectionTimeout=500ms`、每 20 次请求漏 1 个连接）：

| 请求序号 | 累计泄漏 | 等待 | 结果 |
|---|---|---|---|
| 1 | 0 | 2ms | OK |
| 2 | 0 | 2ms | OK |
| 20 | 1 | 2ms | OK |
| 100 | 5 | 2ms | OK |
| 200 | 10 | 2ms | OK（最后一个连接被漏掉） |
| **201** | 10 | **500ms** | **首个 TIMEOUT** |
| 240 / 280 / … / 560 | 10 | 500ms | **100% TIMEOUT** |

请注意这条曲线：**前 200 次请求的延迟一条平线，全是 2ms，没有任何"慢慢变慢"的前兆；第 201 次直接 100%。**

这就是泄漏最坑的地方——它不是资源耗尽后的性能劣化，它是**可用连接数从 1 到 0 的阶跃**。在你的监控上，形态是「P99 平滑，然后瞬间 100% 失败」，中间没有过渡带给你反应时间。更坑的是：如果泄漏速度慢（比如某个定时任务每小时漏 1 个），你可能要 10 小时才撞墙，那时候发版记录早就翻篇了。

泄漏的三个典型形态：

1. **异常路径没还**——`try { conn = ds.getConnection(); ... }` 后面没有 `finally`，或者 `finally` 里又抛了异常；
2. **异步线程里借、主线程里还**——`CompletableFuture` 里拿了连接，回调在另一个线程，`close` 丢在了 `thenApply` 的异常分支；
3. **包装类吞掉了 `close`**——自己写了个 `ConnectionWrapper` 做统计，`close()` 里只记了日志没调 `delegate.close()`。

HikariCP 的泄漏检测有两个必须记住的细节，都来自本机实测。

**细节一：阈值小于 2000ms 会被静默禁用。** 设 `leakDetectionThreshold=1200`，启动日志直接告诉你：

```text
WARN com.zaxxer.hikari.HikariConfig -- demo-pool - leakDetectionThreshold is less than
2000ms or more than maxLifetime, disabling it.
```

配置项还在，`toString()` 里也还打着 `leakDetectionThreshold..........0`——**它从 1200 被改成了 0（关闭）**。这是「配了等于没配」的经典案例，唯一的发现方式是启动日志。

**细节二：告警带完整堆栈，而且是"借出时刻"的堆栈。** 阈值设 3000ms、持有 4200ms：

```text
09:46:45.157 [leak-pool housekeeper] WARN com.zaxxer.hikari.pool.ProxyLeakTask
  -- Connection leak detection triggered for Hikari31$FakeConn@7946e1f4 on thread main,
     stack trace follows
java.lang.Exception: Apparent connection leak detected
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:99)
	at HikariLeak.main(HikariLeak.java:19)
09:46:46.368 [main] INFO com.zaxxer.hikari.pool.ProxyLeakTask
  -- Previously reported leaked connection Hikari31$FakeConn@7946e1f4 on thread main
     was returned to the pool (unleaked)
```

两句话读法：
- 报警时间 = 借出时刻 + 阈值（42.14s 借出 → 45.15s 报警），**不是** HouseKeeper 的 30s 周期。因为它在 `borrow` 时给你的每个连接挂了一个 `ScheduledFuture`，到点就 dump 借出点的栈。
- 如果连接后来还回来了，会补一条 `(unleaked)`。所以**看到 triggered 不等于泄漏，看到 triggered 但没有 unleaked 才是**。这条能直接把你和背概念的人区分开。

生产上怎么用它？把 `leakDetectionThreshold` 设成「正常最长事务时间 × 2」，比如 10 秒。低于这个值的慢查询不该报警，高于这个值的持有一定有问题。别设 2 秒——定时任务、报表导出、批量对账会天天误报，最后所有人都把这条 WARN 加进忽略列表，检测就废了。

---

## 08 池内嵌套借连接 = 必然死锁

这是连接池最经典的死锁，也是最容易被"我只是在 service 里再调一个 service"这种无害写法踩到的。

自建池实测：`maximumPoolSize=10`、10 个工作线程、每个请求需要**同时持有两个连接**（用 `CyclicBarrier` 保证 10 个线程都先握住第 1 个）：

```text
== EXP4 嵌套借连接（pool=10, threads=10, 每请求需 2 个连接） ==
  首个失败文案: connection is not available, request timed out after 3000ms
  ok=0 fail=10 elapsed=3040ms
```

**10 个请求，10 个全部失败，一个都没成功。** 静态地看：需要 20 个连接，池里只有 10 个，必然有人拿不到。动态地看更狠：每个线程都握着第 1 个不放，等第 2 个，而第 2 个只能由别人释放第 1 个才有——**这是一个标准的持有并等待（hold and wait）循环，四个死锁必要条件全齐**，而且不会自己解开，只能等超时。

三种最常见的触发方式：

```java
// ① 事务方法内部再调一个 @Transactional 方法，且传播行为是 REQUIRES_NEW
@Transactional
public void outer() {
    repoA.update(x);          // 占住连接 1
    innerService.other();     // REQUIRES_NEW → 新事务要连接 2
}

// ② 一个请求要查两个数据源（分库），各借一个连接，且嵌套在同一个事务里
@Transactional("orderTx")
public void place() {
    orderRepo.save(o);        // 数据源 A 的连接
    inventoryRepo.deduct();   // 数据源 B 的连接（分布式事务场景必然发生）
}

// ③ 批处理里循环借：外层流式查询（游标占用连接）内部再按 id 查明细
@Transactional
public void export() {
    try (Stream<Order> s = repo.streamAll()) {   // 连接 1 被游标一直占着
        s.forEach(o -> detailRepo.findByOrderId(o.id));  // 每次再借 1 个
    }
}
```

第 ③ 种最隐蔽：`Stream` 查询在 MySQL 上要靠 `useCursorFetch` + 服务端游标，**整个遍历期间连接不归还**，而循环体里每查一次明细又借一个。池子小的时候（10 个），并发 10 个导出任务就死锁；池子大的时候（50 个），能扛到并发 50 才死——**所以你调大池子只是把死锁推迟，不是修好它。**

修法按优先级：

1. **一个请求一个连接**——`REQUIRES_NEW` 改成默认的 `REQUIRED`，让内层复用外层事务的连接（同一线程、同一 `DataSource`，`DataSourceUtils` 会返回同一个 Connection）；
2. **传 `Connection` 而不是开新事务**——确实需要跨方法共享时，把连接当参数传下去，别让每个方法自己去借；
3. **别在事务里做流式遍历**——分页查（`limit/offset` 或游标分片），每页一个短事务，把"长占坑"改成"多次短占坑"；
4. **真需要两把锁，就统一加锁顺序**——两个数据源的连接按固定顺序借（先 A 后 B），至少不会和另一个"先 B 后 A"的线程互锁。

---

## 09 资源隔离：舱壁模式为什么值那点钱

第 07、08 节讲的是「一个池子内部出事」。这一节讲「一个池子出事，会不会带走整个应用」。

实测场景：A 是快依赖（2ms），B 是慢依赖（500ms），各 50 个线程打，2.5 秒窗口。

| 模式 | A 成功 | A 超时 | A 成功率 |
|---|---|---|---|
| **共享池**（A、B 共用一个 20 连接池） | 588 | 219 | **72.9%** |
| **隔离池**（A 20 + B 20） | 3270 | 18 | **99.5%** |

A 的吞吐从 588 涨到 3270（**5.6 倍**），失败的 219 次直接归零到 18 次。原因不复杂：B 的 50 个线程各占连接 500ms，共享池 20 个连接被 B 一秒内抢光，A 连"借"的资格都没有，只能等到 500ms 超时——**A 根本没做错任何事，它只是和 B 住在同一间屋子。**

隔离的三个层次，从粗到细：

| 隔离方式 | 隔离的东西 | 成本 | 适用 |
|---|---|---|---|
| 连接池隔离 | 连接 | 连接总数翻倍，DB 侧连接预算要重新算 | 同一应用访问多个库/多种慢查询 |
| 线程池隔离 | 线程（+ 连接） | 线程数翻倍，上下文切换上升 | 强弱依赖混部，需要限制慢依赖的并发 |
| 信号量隔离 | 并发数（不换线程） | 最轻，无线程切换 | 只限制并发，不关心排队时长 |

```java
// 按依赖拆池：快查询一个池，报表/慢查询另一个池
@Bean("fastDs")
@ConfigurationProperties("app.datasource.fast")
public HikariDataSource fastDs() { return new HikariDataSource(); }   // max=20

@Bean("slowDs")
@ConfigurationProperties("app.datasource.slow")
public HikariDataSource slowDs() { return new HikariDataSource(); }   // max=5，故意小

// 慢查询路由到小池：它再怎么堵，也只能堵自己那 5 个连接
@Transactional("slowTx")
public Report buildReport() { ... }
```

但隔离不是免费的，三笔成本必须说清楚，否则面试官会追一句「那你怎么不全隔离」：

1. **连接总数上升**。两个池各 20，实例从 4 台扩到 20 台，就是 20 × 20 × 2 = 800 个连接，MySQL 默认 `max_connections=151` 直接顶死。所以隔离前先算总账：`池大小 × 实例数 × 池个数 ≤ max_connections × 0.8`。
2. **小池会放大尾部延迟**。慢依赖只给 5 个连接，它的 P99 一定比给 20 个更差——**隔离是把痛苦集中给一个依赖，不是消灭痛苦**。
3. **多数据源就没有本地事务了**。跨池操作要么走分布式事务，要么接受最终一致，别指望 `@Transactional` 还能救你。

---

## 10 HikariCP 参数速查：默认值就是答案，改之前先想清楚

| 参数 | 默认值 | 什么时候改 | 改错的后果 |
|---|---|---|---|
| `maximumPoolSize` | 10 | 按 `c = λ × S` 算出来明显大于 10，且 DB 核数撑得住 | 调大→DB 超配，延迟升、吞吐不升 |
| `minimumIdle` | 与 max 相同 | 一般不改 | 调小→流量突增时要现场建连，尖刺 |
| `connectionTimeout` | 30000ms | 按上游请求预算倒推 | 太大→线程被拖死；太小→误伤慢查询 |
| `validationTimeout` | 5000ms | 一般不动 | 大于 connectionTimeout 会启动报错 |
| `idleTimeout` | 600000ms（10min） | 只有 `minimumIdle < maximumPoolSize` 时才生效 | 设了没用，因为默认 min=max |
| `maxLifetime` | 1800000ms（30min） | 云上必须 < LB/DB 的空闲回收时间 | 太大→借到死连接，报 Communications link failure |
| `keepaliveTime` | 0（关闭） | 中间有防火墙掐空闲连接时设 30~60s | 探测本身要一条 SQL，别设太密 |
| `leakDetectionThreshold` | 0（关闭） | 排查期设「最长事务 × 2」，且 ≥2000ms | 小于 2000 被静默禁用；太小会误报成噪声 |
| `connectionTestQuery` | 无（用 JDBC4 `isValid`） | 驱动不支持 `isValid` 才需要 | 设了等于每次借出多一个 RTT |

`minimumIdle` 等于 `maximumPoolSize` 是 HikariCP 的默认，也是它跟很多老池子最大的理念差异：**固定大小的池，行为最可预测**。C3P0/DBCP 那种"最小 5 最大 50"的配置，在流量突增时会出现「先排队建连、再排队拿连接」的二次延迟，而且你永远测不出稳态。

---
