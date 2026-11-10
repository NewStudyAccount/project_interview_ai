# Java 面试深水区 18 · 接口幂等与防重设计

> 四种方案的边界：token / 唯一索引 / 状态机 / 分布式锁。「幂等」和「去重」不是一回事，重复支付、重复下单、MQ 重复消费三种真实时序。

---

## L0 先把定义掰开：幂等不是"防重复提交"

面试里问"你的接口怎么保证幂等"，回答"加个 Redis 锁"的人，通常分不清下面三件事。

**幂等的数学定义**：`f(f(x)) = f(x)`。翻译成人话——同一个请求打进来 N 次，业务最终状态必须和执行 1 次**完全一致**，包括返回值。

但"去重"只是让第二次请求被丢掉，"幂等"要求第二次请求得到**同一个答案**。这两件事在绝大多数实现里根本不是一回事。

| 概念 | 目标 | 判据 | 典型手段 |
|---|---|---|---|
| 去重 dedup | 同一请求只执行一次 | 请求指纹相同 | token、唯一索引、幂等表 |
| 幂等 idempotent | 执行 N 次 = 执行 1 次的**状态** | 最终状态相同 | 状态机、CAS、覆盖写 |
| 防重 anti-repeat | 窗口期内拒绝重复操作 | 时间 + 用户 | 前端防抖、限流 |

### 天然幂等 vs 需要构造的幂等

```sql
-- 幂等：执行 100 次，balance 都是 100
UPDATE account SET balance = 100 WHERE id = 1;

-- 不幂等：执行 100 次，balance 变成 10000
UPDATE account SET balance = balance + 100 WHERE id = 1;

-- 幂等（绝对值覆盖，但会丢并发更新）
UPDATE account SET balance = balance + 100, version = version + 1
WHERE id = 1 AND version = 3;
```

第一类是**天然幂等**，第二类需要外部机制。注意第三类：它既是幂等（重放不叠加），又是乐观锁（防并发覆盖）——这就是为什么 CAS 是幂等设计里性价比最高的原语。

天然幂等的操作清单（背下来，面试能加分）：

- 查询：`SELECT`
- 覆盖写：`UPDATE ... SET x = 常量 WHERE pk = ?`
- 按主键删除：`DELETE FROM t WHERE id = ?`
- 状态机流转：`UPDATE ... WHERE status = 旧值`

不幂等的清单：

- 自增/自减：`balance = balance + n`
- 追加插入：`INSERT`（无唯一约束）
- 计数器：`INCR`
- 发消息、调第三方、写文件

### 幂等的四个强度等级

大多数人只做到第 2 级就以为完事了，而面试官追问的恰恰是第 3、4 级。

| 等级 | 能力 | 实现 | 缺口 |
|---|---|---|---|
| L1 | 不重复执行 | 唯一索引 / token | 第一次成功、第二次报错，返回值不一致 |
| L2 | 不重复改状态 | 状态机 + CAS | 拿不到第一次的执行结果 |
| L3 | 返回同一结果 | 幂等表存 response | 存储成本、序列化兼容 |
| L4 | 失败也可重放 | 结果含失败语义 + 可重试判定 | 需要区分业务失败与系统异常 |

**面试高频追问 1**："你的幂等只保证不重复执行，但第一次执行到一半失败了，第二次来你怎么返回？"

标准答案是：第一次失败时，幂等表写入 `status=FAIL + 失败原因`；第二次来读到 FAIL，判断失败是否可重试——业务失败（库存不足）直接返回同一失败，系统异常（DB 超时）则允许重置状态重跑。**这就是 L4，也是支付类系统必须做到的一级。**

**面试高频追问 2**："幂等键存 24 小时，第 25 小时同一个请求又来一次呢？"

答案是幂等窗口必须大于业务重复窗口，而这两个窗口由业务决定，不是拍脑袋：

| 场景 | 重复窗口来源 | 建议幂等窗口 |
|---|---|---|
| 支付回调 | 微信/支付宝 24h 内重试 15 次 | 7 天（覆盖退款、对账） |
| 下单 | 用户误操作 + 网关重试，分钟级 | 24 小时 |
| MQ 消费 | broker 保留 3 天，死信队列 7 天 | 7 天，或永久（靠业务唯一键） |
| 内部 RPC | 重试风暴，秒级 | 5 分钟 |

窗口设小了，等于幂等只在白天有效；设大了，存储成本线性上涨。第 18 期的算法是：**窗口 = 上游最长重试周期 × 2**。

---

## L1 重复从哪来：三重放大器

重复请求不是"用户手抖"这么简单。真实系统里它有三个来源，而且会**互相放大**。

```
来源 1：用户侧                     来源 2：框架/网关侧              来源 3：MQ 侧
┌──────────────┐                 ┌──────────────────┐            ┌──────────────────┐
│ 弱网双击      │                 │ Feign 超时重试 3 次│            │ at-least-once    │
│ 间隔中位数180ms│  ──────────▶    │ 网关重试 3 次      │  ──────▶   │ ack 丢失即重投    │
│ 前端防抖覆盖70%│                 │ DB 连接池重连      │            │ 主从切换必重投    │
└──────────────┘                 └──────────────────┘            └──────────────────┘
        ↓                                  ↓                              ↓
   30% 漏网                         3 × 3 = 9 倍                    0.01% ~ 1%
```

### 重试放大公式

一次用户点击，穿过 D 层调用，每层重试 R 次，到达底层的请求数是：

```
N = R^D
```

| 每层重试 R | 调用深度 D=2 | D=3 | D=5 |
|---|---|---|---|
| 2 | 4 | 8 | 32 |
| 3 | **9** | **27** | **243** |
| 4 | 16 | 64 | 1024 |

`R=3, D=5` 就是 243 次。这就是**重试风暴**：用户点了一次"提交订单"，数据库收到 243 条 INSERT。

更可怕的是重试风暴的自我强化：重试导致响应变慢 → 更多超时 → 更多重试 → 雪崩。所以幂等不只是"防重复",它是**熔断体系的地基**——没有幂等，你根本不敢开重试。

### 三个来源的具体数字

1. **用户侧**：移动端按钮双击间隔中位数 180ms，前端 300ms 防抖能覆盖约 70%。剩下的 30% 来自页面卡顿后连点、返回键重进、浏览器回退重提。前端防抖在**任何**情况下都不能当作幂等方案，它只是降低概率。

2. **框架侧**：Spring Cloud OpenFeign 默认 `Retryer.NEVER_RETRY`，但很多团队会改成 3 次；Nginx `proxy_next_upstream` 在 upstream 超时时会转发到下一台——**这是最隐蔽的重复源，因为它对应用完全透明**，日志里只有一条请求。

3. **MQ 侧**：RocketMQ 是 at-least-once。重复率不是"配置错误"，而是设计使然。触发条件：消费成功但 ack 丢包、broker 主从切换、消费端 GC 停顿导致消费超时被重新投递。实测重复率 0.01%~0.1%，大促主从切换时能到 1%。

一句话总结：**只要你的系统有重试，就必须有幂等；只要有 MQ，就必须有幂等。这不是可选项。**

---

## L2 唯一索引：最便宜的兜底，也是最强的一道

为什么唯一索引是"最强"？因为它是唯一一个**由存储引擎在写入路径上原子保证**的去重机制，不依赖网络、不依赖额外组件、不会因为 Redis 挂了而失效。

```
请求 A ─┐
        ├─▶ INSERT INTO t_order(..., idem_key='K1')
请求 B ─┘                    │
                             ▼
                    ┌────────────────────┐
                    │ InnoDB B+ 树唯一校验 │
                    │  K1 已存在？         │
                    └────────────────────┘
                       │             │
                     否│           是│
                       ▼             ▼
                  写入成功      DuplicateKeyException
                  rows=1              │
                                      ▼
                            ┌──────────────────┐
                            │ 查已有记录返回     │
                            │ （或直接返回成功）  │
                            └──────────────────┘
```

### 表结构

```sql
CREATE TABLE `t_order` (
  `id`         bigint       NOT NULL AUTO_INCREMENT,
  `order_sn`   varchar(32)  NOT NULL COMMENT '业务单号',
  `idem_key`   varchar(64)  NOT NULL COMMENT '幂等键 md5(用户+意图+业务参数)',
  `user_id`    bigint       NOT NULL,
  `amount`     decimal(12,2) NOT NULL,
  `status`     tinyint      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_user_sn` (`user_id`, `order_sn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### Java 侧的正确写法

```java
public Order createOrder(CreateReq req) {
    String idemKey = IdemKey.of(req.getUserId(), "CREATE_ORDER", req.getBizNo());
    Order order = buildOrder(req, idemKey);
    try {
        orderMapper.insert(order);
        return order;
    } catch (DuplicateKeyException e) {
        // 走到这里说明：对方事务已提交（详见下方说明）
        Order exist = orderMapper.selectByIdemKey(idemKey);
        if (exist == null) {
            // 理论上不会发生；发生了说明隔离级别/主从读导致不可见
            throw new BizException("并发冲突，请重试");
        }
        return exist.exists() ? exist : order;
    }
}
```

**这里有个 90% 的人不知道的细节**：InnoDB 的唯一索引冲突检测发生在插入时，如果对方事务**未提交**，当前事务不会立刻抛 `DuplicateKeyException`，而是**加 S 锁阻塞等待**对方释放。所以你能 catch 到这个异常，就说明对方一定已经提交（或回滚）——此时再查一定能查到。

这个机制的好处是"天然串行化"，坏处是：**如果对方事务长时间不提交，你会被 `innodb_lock_wait_timeout`（默认 50s）超时打断**。所以幂等键的写入必须在事务的**最开始**，而不是最后，否则长事务会把并发请求全部挂死。

### 三个必须知道的坑

**坑 1：`INSERT ... ON DUPLICATE KEY UPDATE` 会消耗自增 ID**

```sql
-- 看起来很美：一条 SQL 搞定"插入或更新"
INSERT INTO t_order(order_sn, idem_key, amount)
VALUES ('SN1', 'K1', 100)
ON DUPLICATE KEY UPDATE amount = VALUES(amount);
```

在 `innodb_autoinc_lock_mode=1`（连续模式）下，每次冲突都会**申请一个新的自增值并丢弃**。高并发重复提交场景，自增 ID 空洞率实测能到 30%。如果 ID 用于分库分表取模，空洞不影响正确性；但如果用于"ID 连续性"的业务（如对账流水号），就是灾难。

更麻烦的是返回值：`affected_rows` 是 1（插入）/ 2（更新）/ 0（值未变化）。很多 ORM（MyBatis 的 `useGeneratedKeys`）会误判，把"更新"当成"插入成功"。

**坑 2：联合唯一索引里 NULL 不参与唯一性判断**

```sql
-- MySQL 允许插入任意多行 (1, NULL)！
UNIQUE KEY uk_user_sn (user_id, order_sn)
INSERT INTO t_order(user_id, order_sn) VALUES (1, NULL);  -- OK
INSERT INTO t_order(user_id, order_sn) VALUES (1, NULL);  -- OK 又插进去了
```

这个行为来自 SQL 标准（NULL 不等于 NULL）。解法：幂等键字段一律 `NOT NULL DEFAULT ''`，并在业务层保证不写空串。

**坑 3：分库分表后唯一索引只在单 shard 内有效**

`uk_idem` 在 `order_0` 和 `order_1` 上各建一份，同一个幂等键如果路由到不同 shard，两边都能插进去。解法两条：

- 幂等键**包含分片键**（如 `user_id`），保证同一用户的请求路由到同一 shard；
- 或者用独立的**全局去重表**（单表 + 唯一索引），代价是多一次写入。

**唯一索引的长度限制**：InnoDB `DYNAMIC` 行格式下单列索引前缀上限 3072 字节，utf8mb4 下 `varchar(768)`。所以幂等键必须定长——用 MD5（32 字符）或 SHA-1（40 字符），不要直接把整个 JSON 塞进去。

---

## L3 状态机 + 乐观锁：把"重复执行"变成空操作

唯一索引防的是"重复创建"，但管不了"重复流转"。用户点"确认收货"两次、支付回调推"已支付"两次，都是**更新**而不是插入，唯一索引完全无能为力。

### 状态机幂等

思路：把"执行"重新定义为**状态迁移**，非法迁移直接拒绝。

```java
public enum OrderStatus { CREATED, PAID, SHIPPED, RECEIVED, COMPLETED, CANCELLED, REFUNDING, REFUNDED }

private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
    CREATED,   Set.of(PAID, CANCELLED),
    PAID,      Set.of(SHIPPED, REFUNDING),
    SHIPPED,   Set.of(RECEIVED),
    RECEIVED,  Set.of(COMPLETED, REFUNDING),
    COMPLETED, Set.of(REFUNDING),
    REFUNDING, Set.of(REFUNDED, PAID),
    CANCELLED, Set.of(),
    REFUNDED,  Set.of()
);

public void transit(long orderId, OrderStatus from, OrderStatus to) {
    if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
        throw new IllegalStateException(from + " -> " + to + " 非法迁移");
    }
    int rows = orderMapper.casStatus(orderId, from, to);
    if (rows == 0) {
        // 关键：0 行有两种含义，必须再查一次才能区分
        OrderStatus now = orderMapper.selectStatus(orderId);
        if (now == to) return;          // 已成功，幂等返回
        throw new ConcurrentModifyException("状态已被并发修改为 " + now);
    }
}
```

**8 个状态 → 56 种有序对（8×7），合法只有 12 条 → 78.6% 的重复执行被状态机直接挡掉。** 剩下 21.4% 是"同状态重复"（PAID → PAID），由 CAS 的 `rows == 0 → 再查一次` 兜住。

### "0 行"的双重语义是本节最重要的一句话

```sql
UPDATE t_order
SET status = 2, version = version + 1, pay_time = NOW()
WHERE id = #{id} AND status = 1 AND version = #{version};
```

返回 0 行，可能是：

1. **别人先改了**，而且改成了别的状态 → 真冲突，要报错
2. **我自己改过了**（重复请求）→ 假冲突，要返回成功

`if (rows == 0) throw new Exception()` 是错的——它会把重复请求变成可见的错误，用户看到"支付失败"但钱已经扣了。正确做法必须再查一次状态。**这就是幂等和乐观锁的分界线：乐观锁只告诉你"没改成功"，幂等要你判断"是不是我已经改成功了"。**

### 组合用法：唯一索引 + 状态机

两者覆盖的路径完全不同，是正交的：

| 场景 | 唯一索引 | 状态机 |
|---|---|---|
| 重复创建订单 | ✅ 挡住 | ❌ 不管插入 |
| 重复支付回调 | ❌ 不是插入 | ✅ 挡住 PAID→PAID |
| 重复确认收货 | ❌ | ✅ 挡住 RECEIVED→RECEIVED |
| 并发创建（不同参数） | ✅ | ❌ |

生产环境的标准配置是**两者都要**，成本几乎为零（一条 DDL + 一个 CAS SQL）。

---

## L4 token 令牌：把"能不能做"和"做没做过"分离

token 方案的本质是**两阶段提交**：申请阶段发一个一次性凭证，消费阶段原子地作废它。

```
阶段 1（渲染页面）              阶段 2（提交）
┌────────────┐              ┌──────────────────────────┐
│ GET /form  │              │ POST /submit             │
│ 生成 UUID   │  ──────────▶ │ 携带 token               │
│ SETNX idem: │              │ Lua: GET + DEL 原子执行   │
│  token=1    │              │ 返回 nil → 重复提交       │
│  EX 300     │              │ 返回值   → 放行           │
└────────────┘              └──────────────────────────┘
```

### 反例：99% 的人这么写

```java
// 错误示范：两个 RTT，中间有窗口
public void submit(String token) {
    String key = "idem:token:" + token;
    if (Boolean.TRUE.equals(redis.hasKey(key))) {   // RTT 1
        throw new BizException("请勿重复提交");
    }
    redis.delete(key);                               // RTT 2
    doBusiness();                                    // 非原子！
}
```

两个并发线程可以**同时**通过 `hasKey`，然后都执行 `delete`（第二次删不存在的 key，无副作用），最后都进入 `doBusiness`。这个方案的失败率不是"偶发"，而是**并发越高失败越多**。

### 正确写法：用 DEL 的返回值，或用 Lua

```java
// 方案 A：Redis 6.2+ 的 GETDEL，一条命令原子完成
public void submit(String token) {
    String v = redis.opsForValue().getAndDelete("idem:token:" + token);
    if (v == null) throw new BizException("请勿重复提交");
    doBusiness();
}

// 方案 B：Lua 脚本（兼容老版本）
private static final String LUA = """
    local v = redis.call('GET', KEYS[1])
    if v then redis.call('DEL', KEYS[1]) end
    return v
    """;
```

`DEL` 的返回值就是删除数量：1 = 首次，0 = 重复。用返回值判断，天然原子。

### token 的三个设计决策

**决策 1：token 什么时候发？**

| 发放时机 | 能防 | 不能防 |
|---|---|---|
| 页面渲染时（每表单一个） | 刷新重放、后退重提 | 同页面连点（两次提交用同一 token，第二次被挡，✅ 其实能防） |
| 打开弹窗时 | 同上 | 弹窗内改参数后重提 |
| 前端每次点击 new UUID | 什么都防不了 | 全部（这是最常见的错误） |

第三种是灾难：每次点击生成新 token，服务端看到的是 N 个不同 token，全部放行。**token 的价值在于"同一个意图复用同一个 token"，而不是"每次请求一个新 token"。**

**决策 2：TTL 设多长？**

必须**大于业务最长执行时间**。设 5 分钟覆盖 99.9% 的接口；支付类要 30 分钟。设短了，用户填表慢，token 过期，提交被拒——这是"防重把自己防死"的经典事故。

**决策 3：消费失败了要不要把 token 还回去？**

**不要。** 还回去等于允许重试，而重试就是重复执行的开始。正确做法：失败时在幂等表里写 `FAIL` 状态，允许**同 key 带状态重试**，而不是恢复 token。这两者的区别是：前者有记录、可审计、可判定失败类型；后者是"假装什么都没发生"。

### token 的致命缺陷

token 只能防"同一个 token 被用两次"，**防不了"用户点了两次、前端发了两个 token"**。前端防抖失效、或者用户开了两个标签页，token 方案直接穿帮。

所以 token 永远只能作为**第一道闸门**，后面必须有业务幂等键（唯一索引）兜底。单靠 token 的幂等，在"用户手抖"这个最高频场景下恰恰是失效的——这是个非常讽刺但真实的结论。

---

## L5 分布式锁：最容易过度设计的方案

### 先说结论：80% 的场景不需要锁

面试官问"怎么保证幂等"，你答"加分布式锁"，他会追问："唯一索引能不能解决？" 如果你答不上来，就暴露了"只会背方案，不会做选型"。

锁的三个劣势：

1. **可用性更低**：锁依赖 Redis/ZK，多一个故障点。Redis 挂了，唯一索引还能用，锁直接不可用。
2. **性能更差**：一次加锁 + 一次解锁 = 2 RTT，比一次 INSERT 贵 5~10 倍。
3. **粒度难控**：锁 `userId`？锁 `orderSn`？锁太粗影响吞吐，锁太细等于没锁。

真正需要锁的只剩一种场景：**跨多张表、多个服务的复合操作，且没有天然唯一键**。比如"扣库存 + 建订单 + 发优惠券"三件事必须原子，而库存表没有业务唯一键。

### 三个经典 bug

**Bug 1：误删别人的锁**

```
T0  线程 A 加锁（TTL 30s），开始执行
T30 A 还没执行完，锁自动过期
T31 线程 B 拿到同一把锁，开始执行
T32 A 执行完，执行 unlock() → 删掉了 B 的锁！
T33 线程 C 拿到锁 → A、B、C 三个线程同时在跑
```

解法：value 存唯一标识（`UUID + threadId`），删除时用 Lua 校验：

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
```

**Bug 2：业务没执行完锁就过期**

TTL 30s，业务跑了 45s → 锁提前释放 → 并发进入。解法是看门狗续期：Redisson 的 `lockWatchdogTimeout` 默认 30s，**每 10s（TTL/3）续一次**。

但看门狗的真相是：它依赖客户端进程存活。一次 Full GC 停顿 12s，续期线程没跑，锁就没了。ZGC 的 P99 停顿 < 1ms，但内存不足触发 Full GC 时仍是秒级。**所以"用了看门狗就安全"是幻觉。**

**Bug 3：锁与事务的顺序**

```java
@Transactional
public void createOrder() {
    lock.lock();          // 锁在事务内！
    try {
        // ... 业务
    } finally {
        lock.unlock();    // 事务还没提交，锁就释放了
    }
}
```

`unlock()` 在方法返回前执行，而事务提交在方法返回**之后**（Spring AOP 包裹）。所以锁释放时数据还没提交，下一个线程拿到锁读到旧数据，幂等失效。

解法：把锁放在事务外层——

```java
public void createOrder() {
    lock.lock();
    try {
        txTemplate.execute(status -> doCreate());   // 事务在锁内
    } finally {
        lock.unlock();
    }
}
```

### 锁的正确替代品：一条带租期的 CAS SQL

```sql
-- "带租期的锁"，还自带持久化
UPDATE t_task
SET status = 'PROCESSING', owner = #{node}, update_time = NOW()
WHERE id = #{id}
  AND (status = 'PENDING'
       OR (status = 'PROCESSING' AND update_time < NOW() - INTERVAL 30 SECOND));
```

`rows = 1` 表示抢到，`rows = 0` 表示别人在跑。这条 SQL 等价于一把 30s 租期的分布式锁，但没有额外组件、没有误删问题、宕机后 30s 自动可抢。**这是幂等设计里我最推荐的"反直觉"答案：锁的很多需求，其实一条 UPDATE 就解决了。**

---

## L6 幂等表：把"结果"也存下来

唯一索引只能告诉你"存在过"，不能告诉你"当时返回了什么"。要返回同一结果（L3/L4 级幂等），必须有幂等表。

### 三态设计

```sql
CREATE TABLE `t_idempotent` (
  `idem_key`    varchar(64) NOT NULL COMMENT 'MD5(bizType+userId+意图+参数)',
  `biz_type`    varchar(32) NOT NULL,
  `status`      tinyint     NOT NULL COMMENT '0=处理中 1=成功 2=失败',
  `result`      text        COMMENT '序列化后的响应体，成功时写入',
  `err_msg`     varchar(512) COMMENT '失败原因',
  `retryable`   tinyint     NOT NULL DEFAULT 0 COMMENT '1=系统异常可重试',
  `expire_at`   datetime    NOT NULL,
  `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`idem_key`),
  KEY `idx_expire` (`expire_at`),
  KEY `idx_status_time` (`status`, `update_time`)
) ENGINE=InnoDB;
```

### 四段式流程

```
① 抢占
   INSERT INTO t_idempotent(idem_key, biz_type, status, expire_at)
   VALUES (?, ?, 0, NOW() + INTERVAL 1 DAY)
   ├─ 成功 → 我是第一个，继续 ②
   └─ 冲突 → 读现有记录，进 ③

② 执行
   业务逻辑
   ├─ 成功 → UPDATE status=1, result=?, 返回结果
   └─ 失败 → UPDATE status=2, err_msg=?, retryable=?（业务失败 retryable=0，系统异常=1）

③ 读现有记录
   ├─ status=1 → 直接返回 result（L3 幂等达成）
   ├─ status=2 && retryable=0 → 返回同一失败
   ├─ status=2 && retryable=1 → CAS 重置为 0 并重跑
   └─ status=0 → 进 ④

④ "处理中"的三种策略（见下表）
```

### "处理中"必须带租期，否则就是生产事故

这是幂等表**唯一**的致命缺陷：进程在 `status=0` 时崩溃（OOM、发布、kill -9），这条记录会**永久卡在"处理中"**，同一个请求再也进不来。

真实事故形态：用户支付成功 → 回调进来 → 幂等表写入 0 → 服务重启 → 记录永久卡死 → 回调重试 15 次全部被拒 → **用户钱扣了，订单永远不生成**。对账要等到第二天才发现。

解法：给"处理中"加租期（30s~5min），超过租期的 0 状态视为僵死，允许 CAS 抢占：

```sql
UPDATE t_idempotent
SET update_time = NOW(), owner = #{node}
WHERE idem_key = #{key}
  AND status = 0
  AND update_time < NOW() - INTERVAL 30 SECOND;
```

再配一个扫描任务（每 10s）把超期 0 状态标记为 `status=2, retryable=1`，或直接告警。**没有租期的幂等表，比没有幂等更危险**——它会把偶发失败变成永久失败。

### 三种"处理中"返回策略

| 策略 | 实现 | 优点 | 缺点 |
|---|---|---|---|
| 直接返回"处理中" | 立即返回 code=202 | 简单、不占线程 | 前端要轮询，体验差 |
| 自旋等待 | `for 10 次 × sleep 100ms` 后查结果 | 大多数场景能等到结果 | **每个请求占 1 个 Tomcat 线程 1 秒** |
| 挂起 + 回调 | 注册回调，完成后通知 | 最优雅 | 实现复杂，要处理通知丢失 |

自旋等待的代价要算清楚：默认 Tomcat 线程池 200，如果有 100 个并发请求卡在同一个幂等键上自旋 1 秒，**一半线程池直接瘫痪**。所以自旋只能用于"低并发 + 强一致"的场景（如后台管理），C 端接口一律返回 202。

---

## L7 三种真实时序

### 7.1 重复支付：支付回调是最大的重复源

微信支付的回调重试策略：`15s / 15s / 30s / 3m / 10m / 20m / 30m / 30m / 30m / 60m / 3h / 3h / 3h / 6h / 6h`，共 **15 次，覆盖 24 小时**。支付宝类似（`4m / 10m / 10m / 1h / 2h / 6h / 15h`，共 8 次）。也就是说：**同一笔支付，你的接口最多会被调用 15 次。**

错误时序：

```
T0    用户在微信完成支付
T1    回调 #1 到达 → 生成订单 → 更新 DB → 返回 success
      但网络抖动，微信没收到 success
T2    回调 #2 到达 → 又生成一单 → 用户被扣两次钱、收到两个发货
T3~T15 回调 #3..#15 → 继续生成订单
```

正确时序：

```
T1    回调 #1 → INSERT t_pay_callback(out_trade_no) UNIQUE
                 ├─ 成功 → 执行业务 → 更新 status=SUCCESS → 返回 success
                 └─ 冲突 → 查 status
                            ├─ SUCCESS → 返回 success（幂等）
                            └─ PROCESSING → 返回 success？还是 fail？
T2    回调 #2 → 唯一索引冲突 → status=SUCCESS → 返回 success
```

**这里有个反直觉的判断**：如果回调 #2 到达时第一笔还在 `PROCESSING`，应该返回什么？

- 返回 `success` → 微信停止重试，但第一笔可能失败 → **掉单**
- 返回 `fail` → 微信继续重试（15 次机会） → **安全**

**正确答案是返回 fail（或非 success 的任何值），让微信继续重试。** 因为支付回调的设计哲学是"宁可重复，不可丢失"——重复有幂等兜底，丢失就是资损。

这就引出支付幂等的第一原则：**"返回 success 的时机"必须是"业务已确定成功"，而不是"收到请求"。** 返回早了 → 业务失败微信不再重试 → 掉单；返回晚了 → 重复调用 → 靠幂等兜住。两害相权，宁可晚。

### 7.2 重复下单：幂等键必须由"业务意图"决定

```
用户双击
  ├─ 请求 A：client_req_id = uuid-1
  └─ 请求 B：client_req_id = uuid-2   ← 前端每次点击都 new UUID
                 ↓
        唯一索引 uk(user_id, client_req_id) 两边都不冲突
                 ↓
              两个订单
```

这是唯一索引"失效"的最常见原因——**不是索引没用，是幂等键选错了**。

关键认知：**幂等键必须由"业务意图"决定，而不是由"请求"决定。** 同一个表单的一次填写 = 一个意图 = 一个幂等键。前端必须在**表单打开时**生成 UUID 并保存在表单状态里，而不是在点击时生成。

```javascript
// 错误：每次点击新 UUID
onClick: () => api.submit({ clientReqId: uuidv4(), ... })

// 正确：表单挂载时生成，提交时复用
const formKey = useMemo(() => uuidv4(), []);
onClick: () => api.submit({ clientReqId: formKey, ... })
```

如果前端不可控（比如第三方调用、老版本 App），就退到服务端生成业务指纹：

```java
String idemKey = DigestUtils.md5Hex(
    userId + "|CREATE_ORDER|" + skuId + "|" + quantity + "|" + couponId
);
```

代价是：用户**真的想**下两单同样的商品时，第二单会被拒。所以指纹里要加时间窗口（如 `+ 分钟级时间戳`），或者干脆要求前端传 `clientReqId`。**没有完美的方案，只有明确取舍的方案。**

### 7.3 MQ 重复消费：区分"业务失败"和"系统异常"

RocketMQ 至少一次投递，触发重复的四个条件：

1. 消费成功但 ack 丢包（网络抖动）
2. broker 主从切换（消费位点回退）
3. 消费超时（`consumeTimeout` 默认 15 分钟）被重新投递
4. 消费端 GC 停顿导致心跳丢失，被踢出消费组后 rebalance

重复率估算：ack 丢失率 0.01% → 每天 1000 万条消息 → **1000 条重复**。看着不多，但如果业务是"扣库存"，就是 1000 次超卖。

消费端幂等的四种做法：

| 做法 | 原理 | 优点 | 缺点 |
|---|---|---|---|
| 幂等表（msgId 唯一索引） | 消息级去重 | 通用、可靠 | 每条消息多一次写 |
| 业务唯一键 | 业务级去重 | 最省，无需额外表 | 要求消息带业务键 |
| Redis SETNX | 缓存去重 | 快（0.5ms） | Redis 挂了失效，非持久 |
| 服务端去重（RocketMQ 5.x） | broker 侧 | 对业务透明 | 仅特定场景，有窗口限制 |

**MQ 幂等的特殊之处：必须区分"业务失败"和"系统异常"。**

```java
@RocketMQMessageListener(topic = "order-paid", consumerGroup = "g1")
public void onMessage(MessageExt msg) {
    try {
        idemService.execute(msg.getKeys(), () -> orderService.handlePaid(msg));
    } catch (BizException e) {
        // 业务失败：库存不足、订单不存在 → 重试也没用
        log.warn("业务失败，不重试: {}", e.getMessage());
        return;   // 正常返回 = ack，进不了重试队列
    }
    // 系统异常（DB 超时、NPE）不捕获 → 抛出让 MQ 重试
}
```

区分错了的后果：

- 业务失败当成系统异常抛出去 → 重试 16 次（RocketMQ 默认）→ 进死信队列 → 要人工处理
- 系统异常当成业务失败吞掉 → 消息丢失 → 数据不一致，且**没有任何告警**

第二种更可怕，因为它静默。

---

## L8 幂等键设计：整节课的地基

幂等键选错了，后面所有方案都是白搭。四种生成方式：

| 方式 | 唯一性 | 防重能力 | 适用 |
|---|---|---|---|
| 客户端 UUID（表单级） | 高 | 强（同表单复用） | 表单提交、App 下单 |
| 业务自然键 | 完美 | 最强 | 支付流水号、外部单号 |
| 请求指纹 hash | 高 | 中（同参数同 hash） | 开放 API、无前端配合 |
| 服务端雪花 ID | 高 | **弱**（每次新 ID） | 内部调用、日志追踪 |

**服务端生成的 ID 天生不能当幂等键**——服务端没法知道"这次请求和上次是不是同一个意图"。这是很多人踩的坑：用 `SnowflakeId` 当幂等键，等于没做幂等。

### 请求指纹的三个坑

```java
// 看似合理，实际很容易失效
String raw = method + path + userId + JSON.toJSONString(body);
String key = DigestUtils.md5Hex(raw);
```

1. **body 里含时间戳/随机数/nonce** → 每次指纹都不同 → 完全失效
2. **body 字段顺序不稳定** → 同样的数据算出不同 hash → 该拦的没拦住。解法：`TreeMap` 排序后序列化，或用 protobuf/canonical JSON
3. **body 里含 traceId、requestId** → 同上。这类字段必须在计算指纹**之前**剔除

### MD5 够用吗？算一下

MD5 输出 128 bit。生日攻击下，碰撞概率约 `n²/2^129`：

- n = 10 万 → 1.5 × 10⁻³⁰
- n = 10 亿 → 1.4 × 10⁻²⁰

完全可以忽略。但**绝对不要用 `hashCode()`**：32 bit，10 万条数据碰撞概率约 1%（`n²/2^33 ≈ 1.16%`）。一旦碰撞，两个不同的请求被当成同一个 → 第二个请求被错误地幂等掉 → **静默丢单**。

### 雪花 ID 的时钟回拨

如果非要用雪花 ID 做幂等键（比如消息去重），必须处理时钟回拨：回拨 5ms 就可能生成重复 ID，而重复的幂等键 = 两个不同请求被合并。

```java
if (timestamp < lastTimestamp) {
    long offset = lastTimestamp - timestamp;
    if (offset <= 5) {
        Thread.sleep(offset << 1);        // 小幅回拨：等待
        timestamp = timeGen();
    } else {
        throw new IllegalStateException("时钟回拨 " + offset + "ms，拒绝生成");
    }
}
```

**回拨时降级成随机数是最坏的选择**——那等于放弃了唯一性保证。

---

## L9 选型：四方案能力矩阵

| 方案 | 防并发重复 | 结果可回放 | 额外组件 | 单次损耗 | 跨库跨表 | 实现成本 |
|---|---|---|---|---|---|---|
| 唯一索引 | ✅ | ❌（需再查表） | 无 | 0.1ms | ❌ | 极低（一条 DDL） |
| 状态机 + CAS | ✅ | ✅（状态即结果） | 无 | 0.1ms | ✅ | 低 |
| token | ✅ | ❌ | Redis | 0.5ms | ✅ | 中（前后端配合） |
| 幂等表 | ✅ | ✅ | 无 | 0.3ms | ✅ | 中 |
| 分布式锁 | ✅ | ❌ | Redis/ZK | 1~3ms | ✅ | 高（三个 bug 要处理） |

### 选型决策树

```
有天然唯一业务键（订单号/流水号）？
├─ 是 ──▶ 唯一索引（成本最低，先上这个）
└─ 否
   ├─ 是状态流转（支付/发货/退款）？
   │  └─ 是 ──▶ 状态机 + CAS
   ├─ 需要返回与首次相同的结果？
   │  └─ 是 ──▶ 幂等表（唯一索引 + result 字段）
   ├─ 跨多表/多服务的复合操作？
   │  └─ 是 ──▶ 幂等表 + 状态机（不要上锁）
   └─ 只是防用户连点？
      └─ 是 ──▶ token 或前端防抖（成本最低）
```

**面试的高分答法不是"我用 XX 方案"，而是"我先判断有没有唯一业务键，有就用唯一索引，没有再看是不是状态流转……"** 这段话展示的是选型能力，而不是背方案的能力。

### 分层防御：四道闸门

真实系统不会只用一种方案，而是串成漏斗：

```
第 1 道  前端防抖 / 按钮置灰        拦截 70% 的连点，成本 0
第 2 道  token（一次性凭证）          拦截同一 token 重放
第 3 道  唯一索引（幂等键）           拦截并发插入，数据库级原子
第 4 道  状态机 + CAS               拦截重复流转
第 5 道  幂等表（结果回放）           返回同一结果，兜住"处理中"
```

每一道都有明确的职责，且**互相不替代**。少一道就有对应的漏洞：

- 少了第 3 道 → 并发插入穿透（token 只能防同 token）
- 少了第 4 道 → 重复回调把订单状态改乱
- 少了第 5 道 → 用户看到"重复提交"但不知道第一次成功没

---

## L10 数字速查

| 数字 | 含义 |
|---|---|
| `R^D` | 重试放大：3 层 3 次 = 27 倍，5 层 = 243 倍 |
| 15 次 / 24h | 微信支付回调最大重试次数 |
| 8 次 / 15h | 支付宝回调最大重试次数 |
| 180ms | 移动端双击间隔中位数 |
| 70% | 300ms 前端防抖的覆盖率（剩下的靠服务端） |
| 78.6% | 8 状态状态机能挡掉的非法迁移比例（44/56） |
| 30s / 10s | 看门狗 TTL / 续期间隔（TTL/3） |
| 50s | `innodb_lock_wait_timeout` 默认值（唯一索引阻塞上限） |
| 30% | `ON DUPLICATE KEY UPDATE` 的自增 ID 空洞率 |
| 128 bit | MD5 输出，10 亿条碰撞概率 1.4e-20 |
| 32 bit | `hashCode()` 输出，10 万条碰撞概率 1% |
| 3072 B | InnoDB 单列索引前缀上限（utf8mb4 → varchar(768)） |
| 202 | "处理中"应该返回的 HTTP 状态码 |
| 16 次 | RocketMQ 默认最大重试次数，之后进死信 |

---

## L11 十四条高频问答

### Q1：幂等和去重有什么区别？

去重是手段，幂等是目标。去重只保证"第二次请求被丢弃"，幂等要求"第二次请求返回和第一次相同的结果"。一个接口可以做到去重但不幂等（第二次返回"重复提交"错误码，而第一次返回订单详情），也可以幂等但不做去重（覆盖写 `SET x=1` 执行 100 次结果一样，但写了 100 次 binlog）。

### Q2：为什么不能用 `SELECT ... WHERE` 判断是否存在，再决定插不插入？

因为"查"和"插"之间有时间窗口，两个并发请求都能查到"不存在"，然后都插入。这是最经典的 check-then-act 竞态。除非你在同一个事务里加 `FOR UPDATE`（悲观锁，性能差）或者用唯一索引（推荐）。

### Q3：唯一索引冲突时，为什么能 catch 到异常就说明对方已提交？

InnoDB 在插入时如果发现唯一键冲突且对方事务未提交，会给这条索引记录加 S 锁并**阻塞等待**，而不是立刻报错。等对方提交后，当前事务才拿到 `DuplicateKeyException`（或锁超时）。所以 catch 到异常时，对方的数据一定已可见。这个机制是"阻塞式检测"而不是"快照式检测"。

### Q4：`INSERT IGNORE` 和 `ON DUPLICATE KEY UPDATE` 哪个适合做幂等？

都不推荐作为主方案。`INSERT IGNORE` 会吞掉**所有**错误（包括字段超长、类型错误），不只是唯一键冲突，容易掩盖 bug。`ON DUPLICATE KEY UPDATE` 会消耗自增 ID（空洞率 30%）、返回值语义混乱（1/2/0）。推荐显式 catch `DuplicateKeyException`，代码意图清晰，异常也精确。

### Q5：token 方案能不能只用 `SETNX`？

不能。`SETNX` 是"占位"，不是"消费"。正确语义是"取出并删除"（原子），因为 token 是一次性的。用 `SETNX` 你得先 `DEL` 再 `SETNX`，两个 RTT 且非原子。要么 `GETDEL`（Redis 6.2+），要么 Lua。

### Q6：分布式锁和唯一索引怎么选？

先问"有没有天然唯一键"。有 → 唯一索引，成本低一个数量级。没有且是跨表复合操作 → 用"带租期的 CAS SQL"（`UPDATE ... WHERE status='PROCESSING' AND update_time < NOW()-30s`），而不是真的上 Redis 锁。真需要锁的情况极少：跨服务、跨数据源、且没有可 CAS 的状态字段。

### Q7：Redisson 看门狗能保证锁不丢吗？

不能。看门狗每 TTL/3（默认 10s）续期一次，依赖客户端进程存活。一次 Full GC 停顿 > 10s、网络分区 > 10s、容器被 kill，锁都会丢。看门狗只是降低概率，不是保证。真正的保证来自"业务侧幂等"——即使锁丢了，重复执行也无害。

### Q8：幂等表"处理中"卡死了怎么办？

三件事：① 加租期（`update_time < NOW() - 30s` 可被抢占）；② 定时扫描超期记录并标记失败 + 告警；③ 关键业务（支付）用对账兜底——每天比对第三方账单和本地订单，发现"支付成功但订单未生成"就补单。第三件事是唯一能发现"沉默失败"的手段。

### Q9：MQ 消费幂等，用 msgId 还是业务键？

优先业务键（订单号、流水号）。原因：msgId 在"消息重发"（生产者重试）时会变，而业务键不变。如果一条消息因为生产端重试产生了两个 msgId，用 msgId 去重会执行两次。业务键才是"意图"的标识。

### Q10：幂等键要不要包含用户 ID？

要。除非是全局唯一的业务单号（如支付平台的 `transaction_id`）。以 `(user_id, client_req_id)` 做联合唯一键，能避免不同用户的 UUID 碰撞（虽然概率极低），也让索引更有利于按用户查询。

### Q11：接口幂等和 HTTP 方法的幂等是一回事吗？

概念同源但范围不同。HTTP 规范里 GET/PUT/DELETE 是幂等的，POST 不是。但 HTTP 幂等说的是"语义"，不保证"实现"——一个 PUT 接口内部做 `balance = balance + n` 就不幂等了。反过来，POST 接口可以通过 token/唯一索引实现幂等。**HTTP 方法只是约定，实现要靠自己。**

### Q12：为什么说"重试必须有幂等"？

因为重试的本质是"我不确定上次成功没"，而幂等是"重复执行无害"。没有幂等就开重试，等于把"不确定"放大成"确定地重复执行"。`R^D` 的放大效应下，一次点击能变成 243 次写入。所以幂等是熔断/重试体系的前置条件，不是可选项。

### Q13：幂等窗口设成永久有什么问题？

存储无限增长 + 性能下降（索引变大）。而且很多业务天然有窗口：订单 30 天后归档，支付 7 天后对账结束，超窗口的重复请求本来就不会发生。正确做法是"窗口 = 上游最长重试周期 × 2"，再配 TTL 清理（`DELETE FROM t_idempotent WHERE expire_at < NOW() LIMIT 1000` 分批删）。

### Q14：如果第三方回调没有业务单号，怎么幂等？

用"回调内容指纹"：`md5(第三方单号 + 金额 + 时间 + 状态)`。但要注意：第三方可能在**不同回调**里传相同内容（如重复通知"支付成功"），这正好是我们要去重的；也可能在**状态变化**时传相似内容（如"支付成功"→"退款成功"），此时指纹里的 `status` 字段会变，不会误判。所以指纹必须包含**状态字段**。

---

## L12 三道模拟面试题

### 模拟题 1：设计一个"下单接口"的幂等方案，要求支撑 1 万 QPS

**考察点**：分层设计 / 幂等键选择 / 唯一索引与状态机的配合 / 性能预算

**不及格**：「加 Redis 分布式锁，锁 userId。」1 万 QPS 下锁争用直接把 Redis 打满，而且锁粒度太粗（同一用户并发下单本来就少）。

**及格**：前端防抖 + 服务端唯一索引。`uk(user_id, client_req_id)`，冲突就查已有订单返回。能扛住绝大多数重复。

**良好**：三层。① 前端表单级 UUID（不是点击级）；② 唯一索引 `uk_idem(md5(user_id+意图+参数))`；③ 捕获 `DuplicateKeyException` 后查订单返回。并说明"幂等键在事务最开始写入，避免长事务阻塞"。

**优秀**：补五个层次。

- **性能预算**：1 万 QPS，唯一索引写入增加约 0.1ms，可忽略；但如果用 Redis 锁，2 RTT ≈ 1ms，且锁 key 集中在热点用户上，Redis 单分片 10 万 QPS 上限会被打穿。所以选唯一索引不是"够用"，是"唯一能扛住"的方案。
- **幂等键的粒度**：不能只用 `user_id`（会把用户正常的多次下单拦住），也不能只用 `client_req_id`（前端不可信）。正确的是 `(user_id, client_req_id)` 联合唯一，且 `client_req_id` 在表单挂载时生成——这样"用户真的想下两单"（重新打开表单）也能成功。
- **写入顺序**：幂等键的 INSERT 必须在事务第一句。因为唯一索引冲突会让并发事务**阻塞等待**，如果幂等键在事务末尾写入，前面已经做了 200ms 的业务，等待时间被放大到 200ms+，`innodb_lock_wait_timeout` 更容易触发。
- **降级预案**：唯一索引依赖数据库，数据库主库不可用时下单本来也不可写，所以没有额外降级需求。但如果用了 Redis token，Redis 挂了必须有"跳过 token 校验"的开关——此时靠唯一索引兜底。这就是"分层"的价值：**每层都可降级，因为下一层能兜住**。
- **可观测**：埋点统计 `DuplicateKeyException` 的次数和占比。正常值 < 0.5%；突增说明前端防抖失效或有人在压测。这个指标比任何日志都灵敏。

### 模拟题 2：支付回调被重复推了 15 次，第 3 次时发现订单状态错了，怎么排查

**考察点**：时序推理 / 幂等实现审查 / 资损评估 / 修复方案

**不及格**：「加重试。」回调是对方重试，你加重试只会更乱。

**及格**：先看回调日志，确认每次回调的 `out_trade_no` 是否相同；相同说明是同一笔支付的重复推送，应该在入口用唯一索引挡住。

**良好**：分两步。① 止血：立即在回调入口加 `INSERT IGNORE` 到 `t_pay_callback`，冲突直接返回 success。② 排查为什么前 3 次没挡住：是幂等键用了 `msgId`（每次回调 msgId 不同）？还是幂等逻辑写在业务事务**内部**（业务回滚时幂等记录也回滚了，等于没记）？

**优秀**：补四层。

- **第 2 个原因极其常见**：幂等表的 INSERT 和业务 UPDATE 在同一个 `@Transactional` 里，业务抛异常 → 事务回滚 → 幂等记录一起没了 → 下一次回调又能进来。正确做法是幂等记录用**独立事务**（`REQUIRES_NEW`）写入，或者先写幂等再开业务事务。
- **状态为什么"错了"**：15 次回调如果每次都执行 `UPDATE order SET status='PAID'`，状态本身不会错（幂等）。错的原因通常是回调里做了**非幂等**操作：`balance = balance + amount`（余额加多了）、`stock = stock - 1`（库存多扣了）、`INSERT 积分流水`（积分翻倍）。所以排查要按"回调里有哪些副作用"逐项核对，而不是只看订单状态。
- **资损评估**：算出"重复次数 × 每次副作用"，比如余额多加了 14 × 100 元 = 1400 元。然后按 `out_trade_no` 反查所有受影响的用户，出对账清单。**这一步不能省，因为它决定了要不要上报。**
- **根治**：把回调处理改成"状态机 + 唯一索引"双保险：`t_pay_callback(out_trade_no)` 唯一索引挡重复，`UPDATE ... WHERE status != 'PAID'` 挡重复流转。再加一条对账任务：每天比对微信账单和本地订单，差异自动告警。**对账是支付系统的最后一道防线，也是最容易被省略的一道。**

### 模拟题 3：MQ 消费端偶发重复扣库存，日志里看不出异常，怎么定位

**考察点**：MQ 语义理解 / 幂等设计审查 / 静默失败排查

**不及格**：「消费端加 synchronized。」单机锁对多实例无效，而且 JVM 重启就失效。

**及格**：消费逻辑里加"先查库存流水是否存在，不存在再扣"，也就是 check-then-act——但这本身有竞态，只是降低了概率。

**良好**：用 `msgId` 做 Redis SETNX 去重，TTL 24 小时。能挡住大部分重复，但要说明 Redis 挂了会失效。

**优秀**：四层。

- **先确认重复的真实原因**：日志"看不出异常"恰好说明重复发生在 **ack 阶段**——业务执行成功、日志正常打印，但 ack 因为网络抖动/主从切换丢失，broker 重投。所以不能只看业务日志，要看 broker 侧的消费位点（`ConsumerProgress`）和消费组的 rebalance 记录。
- **为什么 `msgId` 不够**：如果生产端有重试（比如发送超时后重发），两条消息的 `msgId` 不同但业务语义相同，`msgId` 去重会漏。正确做法是用**业务键**（`orderNo + skuId`）做幂等，因为业务键由业务意图决定，不受重试影响。
- **扣库存的正确写法**：`UPDATE stock SET qty = qty - 1 WHERE sku_id = ? AND qty >= 1` 本身是原子的，但**不幂等**（执行两次扣两次）。要么加库存流水表 `uk(biz_no)`，要么改成 `SET qty = 100` 这种覆盖写（但会丢并发）。所以库存扣减必须配流水表，这是行业标准做法。
- **静默失败的监测**：加"库存流水条数 vs 订单条数"的对账（每分钟），以及"同一 `biz_no` 出现多条流水"的实时告警。另外，`SETNX` 方案要监控 Redis 的 `evicted_keys`——如果 TTL 未到就被淘汰，幂等就失效了，而这种失效完全静默。

---

## 下期预告

第 19 期：分布式 ID 与时钟问题 —— 雪花算法的位分配与 QPS 上限、时钟回拨的三种应对、号段模式的双 buffer、以及"为什么你的 UUID 让 InnoDB 插入慢了 3 倍"（页分裂与顺序写入的真实代价）。
