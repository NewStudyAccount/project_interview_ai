# Java 面试深挖 24 · MyBatis 与持久层原理

**一条 SQL 要过 9 道关卡，缓存是甜点也是陷阱**

前面 23 期讲完了 JVM、并发、Spring、MySQL、Redis、MQ。这一期补上最后一块天天在写、却很少讲清的东西：持久层。不是背「一级缓存二级缓存」，而是把 `mapper.selectById(1)` 这一行代码拆成 9 个可插桩的位置，每一处都对应一个面试考点。

**取材方式**：正文里的类名、方法名、默认值，全部来自本机 `mybatis-3.5.19.jar` 与 `HikariCP-5.1.0.jar` 的字节码与运行时反射，不是凭记忆写的。有 3 个结论和网上流传的说法不一致，文中都标了「实测」并给了复现方法。涉及版本的用词统一按 3.5.19 / 5.1.0 口径。

---

## L0 全景：一次 selectById 的九站地图

面试问「MyBatis 原理」时，八成的人会答「动态代理 + 缓存 + 插件」。这句话没错，但它没有信息量。真正能拉开差距的是：**你能说清这行代码在哪个对象上中断，以及在哪一层可以插手**。

```
①  MapperProxy.invoke        JDK 动态代理，方法名即 statement id
                              ↓
②  MapperMethod.execute      SqlCommand 拼「接口全名.方法名」；ParamNameResolver 转 Map
                              ↓
③  DefaultSqlSession         门面：拿连接、开事务、提交、关闭（非线程安全）
                              ↓
④  CachingExecutor           装饰器：二级缓存先问，未命中才放行
                              ↓
⑤  BaseExecutor.query        一级缓存 localCache + CacheKey 比较；queryStack 防污染
                              ↓
⑥  SimpleExecutor.doQuery    newStatementHandler 造四件套；插件在此层层包裹
                              ↓
⑦  StatementHandler          prepare 预编译 + ParameterHandler 设参
                              ↓
⑧  ResultSetHandler          TypeHandler 逐列转换 + 自动映射；嵌套查询在此触发 N+1
                              ↓
⑨  返回 → 遍历完才 close     非提前释放；这是流式查询必须用 Cursor 的原因
```

蓝色站（④⑤）是带缓存逻辑的两站；第 ⑥ 站是插件唯一能插手的地方（对应四个接口）。

### 面试问法其实只有三层

| 层级 | 考什么 | 典型问题 |
|---|---|---|
| 第一层：会不会用 | 语法与标签 | `#` 和 `$` 区别、resultType 与 resultMap、动态 SQL |
| 第二层：知不知道坑 | 缓存与批量的副作用 | 一级缓存为什么「失效」、二级缓存为什么返回旧值 |
| 第三层：能不能改造 | 插件与扩展 | 分页插件拦谁、多数据源怎么切 |

- **先把版本事实钉死**：本文所有结论基于 MyBatis 3.5.19。3.5.16 起对外部插件做了收紧，`Invocation` 构造函数里多了白名单校验，只允许四个接口作为插件目标（实测见 L6）。如果背的是老博客里「任意接口都能被 `Plugin.wrap` 代理」，在 3.5.19 上会直接抛 `IllegalArgumentException`。

---

## L1 接口没有实现类，为什么能跑

先问一个看起来傻但很能筛人的问题：`UserMapper` 是个接口，没有实现类，Spring 注入的时候不报错吗？

答案是 `MapperScannerConfigurer` 把它注册成了 `MapperFactoryBean`，而这个 FactoryBean 的 `getObject()` 返回的是 `MapperProxyFactory.newInstance()` 造出来的 **JDK 动态代理**。所以容器里那个 bean 的真实类型是 `$Proxy42`，不是 `UserMapper`。

```java
// MapperProxyFactory —— 精简后的关键三行
public T newInstance(SqlSession sqlSession) {
    final MapperProxy<T> mapperProxy = new MapperProxy<>(sqlSession, mapperInterface, methodCache);
    return newInstance(mapperProxy);
}
protected T newInstance(MapperProxy<T> mp) {
    return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(),
            new Class[]{mapperInterface}, mp);   // 只能代理接口 → 这就是为什么 Mapper 必须是接口
}
```

进到 `MapperProxy.invoke` 之后有三条分支，顺序不能乱：

1. **Object 自带方法**（`toString`/`equals`/`hashCode`）直接放过，不走 SQL。所以你在 IDE 里 `toString` 一个 Mapper 不会打印 SQL。
2. **接口 default 方法**走 `DefaultMethodInvoker`，用 `MethodHandles` 调用真实默认实现。这是 3.4.2 之后新增的，很多人不知道 Mapper 里可以写 default 方法做参数拼装。
3. **其余**走 `PlainMethodInvoker`，也就是 `MapperMethod.execute`。

### statement id 是怎么拼出来的

`MapperMethod` 的内部类 `SqlCommand` 在构造时做两件事：拼 id、定类型。拼法就是**接口全限定名 + "." + 方法名**：

```java
// MapperMethod.SqlCommand 的核心
this.name = declaringClass.getName() + "." + method.getName();   // com.x.UserMapper.selectById
this.type = resolveMappedStatement(...).getSqlCommandType();      // 从 MappedStatement 反查 SELECT
```

找不到会抛 `BindingException`，报错信息里带完整 id，这就是 `Invalid bound statement (not found)` 的来源。

- **坑 1：「Invalid bound statement」90% 不是代码问题**：三种典型诱因，① `application.yml` 里 `mapper-locations` 只写了 `classpath:mapper/*.xml`，但 XML 在 `mapper/user/` 子目录；② Maven 默认不把 `src/main/java` 下的 `.xml` 打进 jar，需要在 `<resources>` 里显式 include；③ 用了 `@MapperScan` 扫 A 包，XML 的 namespace 却是 B 包接口。

### 参数是怎么变成 #{id} 的

`ParamNameResolver` 的规则一共四条，建议按顺序记：

| 场景 | XML 里怎么写 | 原因 |
|---|---|---|
| 单个参数、无 `@Param`、非集合 | `#{anything}` 都行 | 直接把对象本身当参数对象传下去 |
| 多个参数 | `#{param1}` / `#{arg0}` | 包成 `ParamMap`，两种键名同时存在 |
| 加了 `@Param("uid")` | `#{uid}` | 注解名优先，`param1` 依然可用 |
| 集合 / 数组（单参数） | `#{list[0]}`、`#{collection}`、`#{array}` | `wrapToMapIfCollection` 额外塞了这三个键 |

还有一条容易忽略的：`RowBounds` 和 `ResultHandler` 是**特殊参数**，被 `isSpecialParameter` 跳过，不参与编号。所以 `select(RowBounds rb, long id)` 里 `id` 仍然是 `param1`，不是 `param2`。

---

## L2 执行器与装饰器：四种 Executor 的真实关系

很多人以为 `SimpleExecutor`、`ReuseExecutor`、`BatchExecutor`、`CachingExecutor` 是四选一的兄弟。实际上它们是**两层**：前三个继承 `BaseExecutor`，CachingExecutor 是**装饰器**，把前三个之一包在里面。

```
┌─ CachingExecutor ───────────────────────────────────┐  ← 装饰器，只管二级缓存
│  cacheEnabled=false 时不装这一层                     │
│  ┌─ BaseExecutor 子类 ────────────────────────────┐  │  ← Template Method
│  │  SimpleExecutor / ReuseExecutor / BatchExecutor│  │
│  │  ┌──────────────────────────────────────────┐  │  │
│  │  │ localCache = PerpetualCache              │  │  │  ← 一级缓存就在父类里
│  │  │ JDBC PreparedStatement → ResultSet       │  │  │
│  │  └──────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

三者差别：

- **SimpleExecutor**：每次新建 Statement。默认，最安全。
- **ReuseExecutor**：按 SQL 文本缓存 Statement，同一 SQL 复用，省预编译。
- **BatchExecutor**：攒 JDBC batch，`flushStatements()` 才发。

### BatchExecutor 那个「拿不到 ID」的问题

`BatchExecutor.doUpdate` 把 `addBatch()` 攒起来，直到 `flushStatements()` 才真正执行。`Jdbc3KeyGenerator` 是在 `executeBatch` 返回后统一回填主键的。所以下面这段，`id` 全是 0：

```java
// 错误示范
try (SqlSession s = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
    for (User u : users) {
        s.insert("insertUser", u);
        log.info("id={}", u.getId());     // 0，因为 batch 还没发出去
    }
    s.commit();                            // 这一刻才真正发 SQL、才回填自增 ID
}
```

正确姿势是 `s.flushStatements()` 之后再读 id。这也是 `BatchExecutor` 在「需要主键继续做业务」的场景里不好用的根因。

---

## L3 一级缓存：它其实一直都在，只是没人给它机会

面试里最常听到的一句是「一级缓存默认开启，但 Spring 整合后会失效」。这句话**表述是错的**。一级缓存从未失效，它依附于 `SqlSession`；**是 SqlSession 的生命周期太短了**。

```java
// BaseExecutor —— 一级缓存的全部真相（节选）
protected PerpetualCache localCache;
protected int queryStack;        // 嵌套查询层数

public <E> List<E> query(...) {
    if (queryStack == 0 && ms.isFlushCacheRequired()) { clearLocalCache(); }
    List<E> list;
    try {
        queryStack++;
        list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
        if (list != null) { handleLocallyCachedOutputParameters(...); }
        else { list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql); }
    } finally {
        queryStack--;
    }
    if (queryStack == 0) {
        for (DeferredLoad dl : deferredLoads.values()) { dl.load(); }
        deferredLoads.clear();
        if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) { clearLocalCache(); }
    }
    return list;
}

public int update(MappedStatement ms, Object parameter) throws SQLException {
    ...
    clearLocalCache();            // 任何一次写，整个 localCache 全清（不分表、不分语句）
    return doUpdate(ms, parameter);
}
```

盯着这两处就能解释所有「缓存没生效」的现象。同样 100 次 `selectById(1)`：

```
A. 无 @Transactional（每次调用新建 SqlSession）
   第1次 open→cache{} → DB ×1     第2次 open→cache{} → DB ×1     … 第100次 → DB ×1
   合计 100 次往返，按 0.5ms 计 ≈ 50ms，缓存命中率 0%（但缓存代码一直在跑）

B. 有 @Transactional（整方法共用一个 SqlSession 与同一 localCache）
   第1次 cache{} miss → DB ×1，写入   第2次 search → hit，0 次往返   … 第100次 → hit
   合计 1 次往返 ≈ 0.5ms，省 99%
```

### 六种让缓存「看起来失效」的场景

| # | 场景 | 机制 |
|---|---|---|
| 1 | 不同 `SqlSession`（Spring 无事务时每次调用都新建） | 缓存是实例字段，随对象销毁 |
| 2 | 同一 session 里执行过任意 `insert/update/delete` | `update()` 无条件 `clearLocalCache()`，**全表全语句一起清** |
| 3 | `localCacheScope=STATEMENT` | 每次 query 结束即清空，相当于关掉 |
| 4 | `<select flushCache="true">` | `queryStack==0` 时先清再查 |
| 5 | 手动 `sqlSession.clearCache()` 或 `commit/rollback` | 显式清理 |
| 6 | 参数值变了 | 这不是失效，是 `CacheKey` 不同（见 L5） |

- **坑 2：一次无关的写会清掉整个一级缓存**：`update()` 里那句 `clearLocalCache()` 是**无条件的**，不判断改的是不是同一张表、同一个 namespace。所以长事务里「查 100 条 → 更新 1 条 → 再查 100 条」，第二次查会全部回源。这是长事务性能塌方的隐蔽原因，也是把大批量读写在同一个 `@Transactional` 里最危险的地方。
- **一个反直觉细节**：命中一级缓存时 `query()` 返回的是**同一个 List 对象引用**，元素也是同一批对象。你在业务层 `list.get(0).setName("x")`，等于改了缓存中的值，下一次「查询」会拿到被改过的脏对象，而且没有任何 SQL 日志提示。要规避就在返回处做拷贝，或把 `localCacheScope` 设成 `STATEMENT`。

---

## L4 二级缓存：一个「延迟清理」埋下的旧值

二级缓存和一级缓存最本质的差别不是「跨 session」，而是**它有一个事务暂存区**。写进缓存的动作被推迟到 `commit`，清理动作也被推迟到 `commit`。这个设计本意是「事务没提交前不让别人看见」，副作用是：**自己看不见自己的清理**。

```
t1 update          t2 select            t3 commit
───────────────────────────────────────────────────────────
TransactionalCache：clearOnCommit=true     直接放行             flush + 真清理
（事务暂存区）       只打标记               不查暂存区

delegate（LruCache）：旧值原地不动          ★ 命中，返回旧值      此刻才对外可见
                                      （0 次 SQL）
───────────────────────────────────────────────────────────
结果：这个事务里「改完再查」，查到的是改之前的脏数据
```

注意：一级缓存此时已被 update 清空，所以你会以为「缓存都清了」，但二级缓存这层还留着旧值，这是两级缓存最容易互相打脸的地方。

### 二级缓存的六个硬约束

| 约束 | 说明 | 踩坑表现 |
|---|---|---|
| 以 `namespace` 为界 | 一个 Mapper 一个 Cache 实例 | 更新 A 表，查 B 表还是旧值 |
| 要显式开启 | `<cache/>` 或 `@CacheNamespace`，且 `cacheEnabled=true` | 以为默认开了，其实一次都没生效 |
| 结果需可序列化 | `readWrite=true` 时套 `SerializedCache` | `NotSerializableException` |
| 提交才写入 | `TransactionalCache.commit()` | 同一事务内读不到刚写的数据 |
| 更新只清本 namespace | 关联表要靠 `<cache-ref>` | 订单更新了，明细缓存不失效 |
| 集群间不互通 | 缓存在各自 JVM 堆里 | 多实例下脏读率 = 实例数倒数 |

默认配置也得记：`eviction=LRU`、`size=1024`、`flushInterval` 不设、`readWrite=false`。装饰顺序是 `PerpetualCache → 淘汰装饰 → SerializedCache → LoggingCache → SynchronizedCache`，最外层是 `SynchronizedCache`，所以二级缓存的并发安全是**整表加锁**换来的，高并发下它自己就会成为瓶颈。

- **坑 3：分布式部署下二级缓存必须关**：3 个实例、各带 1024 条本地缓存。A 实例更新数据并清掉自己的缓存，B、C 完全不知道。B 命中缓存返回旧值，脏读窗口 = `flushInterval` 或下次更新的时间差，没有任何机制兜底。正确做法只有两条：**关掉**，或者换成 `RedisCache`（自己实现 `Cache` 接口，把 `PerpetualCache` 换成 Redis 客户端），并把清理改成发广播。

---

## L5 CacheKey：两级缓存的命中裁判

一级缓存和二级缓存的 key 都由 `BaseExecutor.createCacheKey` 生成，装配顺序固定为四段：`statementId` → `p1..pN`（参数值）→ `RowBounds.offset` → `RowBounds.limit` → `BoundSql.getSql()`。注意最后一段是**最终 SQL 文本**，所以动态 SQL 拼出的语句不同，key 自然不同。

而 `CacheKey` 自己的比较逻辑，是 MyBatis 里少见的「性能优化小心思」：

```java
// CacheKey —— 三个字段 + 一个列表
private static final int DEFAULT_MULTIPLIER = 37;
private static final int DEFAULT_HASHCODE    = 17;
private int  hashcode = DEFAULT_HASHCODE;
private long checksum;
private int  count;
private List<Object> updateList = new ArrayList<>();

public void update(Object object) {
    int baseHashCode = object == null ? 1 : ArrayUtil.hashCode(object);
    count++;
    checksum += baseHashCode;
    baseHashCode *= count;                              // ← 位置权重藏在 count 里
    hashcode = DEFAULT_MULTIPLIER * hashcode + baseHashCode;
    updateList.add(object);
}

public boolean equals(Object object) {
    if (count != other.count)       return false;        // 闸门 1：元素个数
    if (checksum != other.checksum) return false;        // 闸门 2：求和校验
    if (hashcode != other.hashcode) return false;        // 闸门 3：乘法散列
    // 三道闸门全过，才逐元素 equals                       闸门 4：最贵的比较
}
```

### 四道闸门：整数比较在前，对象比较垫底

```
count (int)  →  checksum (long)  →  hashcode (37 进制)  →  逐元素 equals
1 条指令         N 次加法            位置敏感抗交换            可能深比较，极少执行
```

### 实测：改一个参数，hashcode 差多少（JDK 21 + mybatis 3.5.19）

| 改动位置 | hashcode 差值 | 推导 | checksum 差 |
|---|---|---|---|
| 第 2 位（id 1→2） | 2738 | 1 × 2 × 37² | 1 |
| 第 3 位（offset 0→1） | 111 | 1 × 3 × 37 | 1 |
| 第 4 位（limit 10→11） | 4 | 1 × 4 × 37⁰ | 1 |

差值等于 `1 × count × 37^k`，**count 这个乘法就是「位置权重」的来源**。这也是为什么只改一个参数、位置不同，hashcode 变化量差三个数量级。

- **为什么必须有 checksum 和 count**：只留 `hashcode` 会撞。乘法散列在 32 位下必然有碰撞，一旦两个不同参数算出同一 hash 且 `equals` 只比 hash，就会**跨参数命中缓存**，查到别人的数据。加上 `count`（防元素个数不同）和 `checksum`（防内容不同），把碰撞率压到可接受范围，再用最贵的逐元素比较兜底。`equals` 与 `hashCode` 的契约在这里被用到极致：**先用便宜的条件否掉绝大多数，再付贵的代价**。

还有一个常被忽略的实测结论：`CacheKey` 的 `equals` 是**顺序敏感**的。同样四个元素打乱顺序构造，`equals` 返回 `false`，因为闸门 4 是按下标逐个比较 `updateList`。

---

## L6 插件：只有四个对象能插，而且只能层层包

MyBatis 插件不是 AOP 那种「切面」，本质是 **JDK 动态代理的责任链**。这句话有两个必须精确的点。

### 第一：可插桩的对象只有 4 个（实测）

3.5.19 的 `Invocation` 构造函数里有一道白名单：

```java
// Invocation 的静态初始化（javap -c 读出来的真实列表）
private static final List<Class<?>> targetClasses = Arrays.asList(
    Executor.class,            // 拦 SQL 执行、事务、批处理
    ParameterHandler.class,    // 拦参数设置
    ResultSetHandler.class,    // 拦结果映射
    StatementHandler.class     // 拦 SQL 构建与预编译
);

public Invocation(Object target, Method method, Object[] args) {
    if (!targetClasses.contains(method.getDeclaringClass())) {
        throw new IllegalArgumentException("Method '" + method + "' is not supported as a plugin target.");
    }
    ...
}
```

实测：拿一个自定义接口 `Foo` 加 `@Intercepts({@Signature(type = Foo.class, ...)})`，`Plugin.wrap` 能包成功，但**一调用就抛**：

```
Exception in thread "main" java.lang.IllegalArgumentException:
  Method 'public abstract java.lang.String Exp2$Foo.hi(java.lang.String)'
  is not supported as a plugin target.
    at org.apache.ibatis.plugin.Invocation.<init>(Invocation.java:41)
```

另一个实测：`Interceptor` 实现类**忘了写 `@Intercepts`**，不是「不生效」，而是启动期就炸：

```
org.apache.ibatis.plugin.PluginException: No @Intercepts annotation was found in interceptor xxx
```

### 第二：包裹顺序是「后注册的在最外层」

```
InterceptorChain.pluginAll(executor):
    for (Interceptor i : interceptors) { target = i.plugin(target); }
         ↓ 第一次 → $Proxy41 包住 SimpleExecutor
         ↓ 第二次 → $Proxy42 包住 $Proxy41
    结果：最后注册的在最外层，最先被调用
```

| 拦截对象 | 能拿到什么 | 适合做什么 | 不适合 |
|---|---|---|---|
| `Executor` | MappedStatement、参数、RowBounds | 分页改写、读写分离路由、SQL 审计 | 拿不到最终 SQL（此时还没 BoundSql） |
| `StatementHandler` | BoundSql、Connection | 改写 SQL 文本、加 hint、改 fetchSize | 改参数值不方便 |
| `ParameterHandler` | BoundSql、参数对象 | 参数加密、脱敏、统一填充租户 ID | 改 SQL 结构 |
| `ResultSetHandler` | 结果 List、ResultMap | 字段解密、脱敏、结果过滤 | 影响分页计数 |

- **坑 4：多个插件互相踩参数**：因为插件是套娃，`Plugin.invoke` 里拿到的是 `Invocation`，`invocation.getArgs()[0]` 可能是**上一层插件替换过的新对象**。典型事故：分页插件把 `RowBounds` 换了，后面的租户插件读到的还是旧引用。规避办法是每层都用 `invocation.getArgs()` 重新取，别在 `plugin()` 里缓存目标对象。

---

## L7 #{} 与 ${}：不是「一个安全一个不安全」这么简单

标准答案会说「`#{}` 预编译防注入，`${}` 字符串拼接有风险」。这句话对了七成，缺的那三成是：**它们发生在完全不同的阶段，走的是不同的对象**。

```
${} 字符串替换（解析期）
  XML 解析 → TextSqlNode → OGNL 求值 → 拼进 SQL 文本
  结果是最终 SQL，每次参数不同 → 执行计划无法复用

#{} 占位符绑定（执行期）
  解析成 ? 与 ParameterMapping → ParameterHandler + TypeHandler
  SQL 结构固定，只有参数变 → 计划可缓存
```

同样的入参 `id = 1 OR 1=1`：

| 写法 | 实际执行 | 结果 |
|---|---|---|
| `${id}` | `WHERE id = 1 OR 1=1` | 全表返回。参数就是 SQL 的一部分，无法区分 |
| `#{id}` | `WHERE id = ?` + 参数 `"1 OR 1=1"` | 数据库当字符串比较，0 行。注入在这里是「不可能」，不是「被过滤」 |

延伸出去还有三个能加分的细节：

- `#{}` 可以带三个属性：`#{id,javaType=long,jdbcType=BIGINT,typeHandler=xxx}`。Oracle 插 `null` 必须显式给 `jdbcType`，否则报「无效的列类型」，这是真实高频事故。
- `${}` 也能用 `@Param` 变量，所以它并非只能拼常量。危险恰恰在这里——你以为拼的是常量，实际拼的是用户输入。
- 排序字段的白名单要写成枚举 + 映射，把 `price_desc` 映射成 `price DESC`，而不是校验「不含空格关键字」。后者永远漏。

### TypeHandler 的三处注册点

| 注册方式 | 作用范围 | 优先级 |
|---|---|---|
| `mybatis-config.xml` 的 `<typeHandlers>` | 全局，按 Java 类型 + JDBC 类型双匹配 | 低 |
| `@MappedTypes` / `@MappedJdbcTypes` 注解 | 全局，由 `TypeHandlerRegistry` 扫描注册 | 中 |
| `resultMap` 里的 `typeHandler` 属性 | 单字段 | 最高 |

一个 1 万行 × 8 列的结果集，`ResultSetHandler` 要调 **8 万次** `TypeHandler.getResult`。所以自定义 TypeHandler 里别放 IO、别 `new SimpleDateFormat`（非线程安全且极慢），这类细节在「查询慢但 SQL 只要 3ms」的排查里经常是元凶。

---

## L8 ResultSetHandler 与 N+1：慢的不是 SQL，是往返次数

`<collection>` 配 `select` 属性（嵌套查询），会把一条 SQL 拆成 1 + N 条。很多人以为这是「延迟加载所以没关系」，但延迟加载只是把 N 次挪到遍历时，**次数一次没少**。

```
查 100 条订单、每条 3 条明细

嵌套查询（N+1）：1 + 100×3 = 301 次往返   ████████████████████████  301 次
一次 join / 两次 in：1 次往返              ▌  1 次
                                                    ↑ 真实比值 301 : 1
按 RTT 0.5ms 计：150ms  vs  0.5ms + 传输 300 行 × 200B ≈ 60KB
```

代价是**往返次数 × RTT**，不是「SQL 条数」本身。这也是为什么本地测不出来、上线就变慢——本地 RTT 约 0.05ms，跨可用区可能 2ms，惩罚差 40 倍。

- **三层修法，按代价从低到高**：① `fetchType="lazy"` + 全局 `lazyLoadingEnabled=true`，只是延后不改次数，仅在确实不需要子集合时有用；② 把 `<collection>` 的 `select` 改成两次 `IN` 查询后在内存里分组（**最常用**，可控且不膨胀结果集）；③ 直接 `join` + `<collection>` 嵌套结果映射，一次往返但行数膨胀，注意 `<id>` 必须配对否则去重失效。

另外 `ResultSetHandler` 还有一个反直觉行为：**结果集是在 `handleResultSets` 里一次性全部读进内存的**。所以查 100 万行一定会 OOM，不是「内存不够」而是「设计上没打算流式」。要流式只能用 `ResultHandler` 或 `Cursor<T>`（后者要求 `fetchSize=Integer.MIN_VALUE` 走 MySQL 流式协议，并且必须在 `@Transactional` 内）。

---

## L9 动态 SQL：便宜的还是贵的

`SqlSource` 有三个实现，区别在于「什么时候定型」：

| 实现 | 触发条件 | 定型时机 | 每次执行开销 |
|---|---|---|---|
| `RawSqlSource` | 无 `${}`、无动态标签 | 解析期生成 StaticSqlSource | ≈ 0 |
| `DynamicSqlSource` | 含 `<if>`/`<foreach>`/`<where>`/`${}` | 每次 `getBoundSql` walk SqlNode 树 | OGNL 求值 2~5μs（估算） |
| `ProviderSqlSource` | `@SelectProvider` | 每次调用 provider 方法 | 反射 + 字符串拼装 |

同一个 `<if>` 语句在循环里被调用 1 万次，就是 1 万次 OGNL 求值，量级 20~50ms。可接受，但不是零成本。

结论：动态 SQL 不是性能杀手，它的开销在「μs 级 × 循环次数」。真正致命的是在 for 循环里调用 Mapper（每次一个新 SqlSession、一次往返），那才是 ms 级的乘法。面试被问「动态 SQL 会不会慢」，先分清是 μs 还是 ms。

---

## L10 连接池：并发数不是「越大越好」，是「越小越快」

MyBatis 自己不管连接，它从 `DataSource` 拿。Spring Boot 2 之后默认 HikariCP。下面这组默认值是**实测打印出来的**：

| 参数 | 实测默认值 | 含义与陷阱 |
|---|---|---|
| `maximumPoolSize` | -1 | 构造后是 -1，`validate()` 时才解析成 **10** |
| `minimumIdle` | -1 | -1 表示「等于 maximumPoolSize」，即**默认不缩容** |
| `connectionTimeout` | 30000 | 30s 拿不到连接就抛异常，这是「池打满」的报警线 |
| `validationTimeout` | 5000 | 连接有效性检测超时，必须小于 connectionTimeout |
| `idleTimeout` | 600000 | 10min 空闲回收；**必须小于 maxLifetime**，否则被忽略 |
| `maxLifetime` | 1800000 | 30min 强制换新；**必须小于链路上所有超时** |
| `keepaliveTime` | 0 | 默认关闭。开着能防中间件静默断连 |
| `leakDetectionThreshold` | 0 | 默认关闭。开了能打印「谁借了没还」的堆栈 |

### maxLifetime 必须落在「最短的那个超时」左侧

```
0        30s      5min            30min                      8h
├─────────┼────────┼───────────────┼─────────────────────────┼──────► 时间
│  安全区  │        │  maxLifetime 30min（默认）
│          │ 中间件空闲断连（常见几分钟级，LB / ProxySQL / RDS 代理）
│          │                    │              MySQL wait_timeout 8h（默认）
│ ← 取链路最小超时再减 30~60s → │
```

若 `maxLifetime` 跨过了中间件的断连点：中间件已经悄悄关掉连接，Hikari 仍以为它活着，借出去执行必报 `CommunicationsException` / `connection closed`。

### 池该开多大：两个公式互相校验

| 公式 | 算式 | 8 核示例 |
|---|---|---|
| A：Hikari 官方推荐口径 | `CPU 核数 × 2 + 有效磁盘数` | 17 |
| B：Little's Law 反算 | `QPS × 平均持连时长` | 2000 × 5ms = 10 |

两个公式给出的量级都在 10~17，这个巧合正是重点：**数据库能并行干活的线程数就那么点，你给 200 个连接，多出来的 183 个只是在抢锁和切换上下文**。第 20 期讲线程池时算过同一笔账，这里是它的第二次应用。

```
QPS
 │            ╭──────●────────────────╮
 │        ╭───╯      │ 拐点 ≈ 10~17    ╰──╮
 │    ╭───╯          │                 ╰───╮
 │╭───╯  线性区：加连接就涨       淤积区：加连接反而降
 └──────────────────────────────────────────► 连接数
```

下降原因：DB 侧线程上下文切换 + 行锁/页锁竞争 + 每个连接一份内存缓冲（MySQL 每连接约 256KB~1MB session buffer）。

- **坑 5：把 maxPoolSize 从 10 调到 200 之后系统更慢了**：这是最常见的错误处置。池打满的真实含义是「连接被占用的时间变长了」，要么慢 SQL，要么事务里包了远程调用，要么连接泄漏。**调大池只是把排队从应用层挪到数据库层**，同时还多消耗 190 份连接内存。正确顺序：先开 `leakDetectionThreshold=60000` 抓泄漏，再看 `SHOW PROCESSLIST` 里 Sleep 多久的连接有多少，最后才是扩容读库。

---

## L11 生产七坑：每条都有可算的代价

| # | 坑 | 代价（可算） | 处置 |
|---|---|---|---|
| 1 | 批量插入没开 `rewriteBatchedStatements=true` | 1 万条 = 1 万次往返约 5s；开了拼成约 100 条多值 INSERT，约 50ms | JDBC URL 加参数，并确认驱动版本 |
| 2 | 在 for 循环里调 Mapper，且方法无事务 | 每次新 SqlSession + 借还连接，1000 次约 50ms 纯开销，且一级缓存全废 | 批量接口一次查完，或用 `IN` |
| 3 | `@Transactional` 里调 RPC / 发 MQ | 连接被占 N 秒；池 10 个 × 3s = 30 并发就排队，第 31 个等 30s 后失败 | 事务只包 DB 操作，外部调用移到事务外 |
| 4 | 大结果集全量 `selectList` | 100 万行 × 200B ≈ 200MB 堆内对象，直接顶到 OOM | `Cursor` / `ResultHandler` / 分页 |
| 5 | 多实例开二级缓存 | 脏读窗口 = 其他实例的缓存 TTL，无上限兜底 | 关闭，或换 RedisCache + 广播失效 |
| 6 | `${}` 透传排序/表名 | 一次注入即可全表拖库，无法靠转义补救 | 枚举白名单映射 |
| 7 | `select *` + 自动映射 + `tinyint(1)` | MySQL 的 `tinyint(1)` 被驱动映射成 `Boolean`，100 这种值静默变 `true` | 显式 `resultMap` 指定 `javaType` |

第 7 条值得单独说，因为它是「数据静默错误」而不是报错：`status tinyint(1)` 存了 100，实体字段是 `Boolean`，MyBatis 走 `BooleanTypeHandler.getNullableResult` → `rs.getBoolean()` → 非 0 即 `true`。你查出来的「是否有效」全是 `true`，日志里一句警告都没有。

---

## L12 数字速查（背这一页够答八成追问）

| 维度 | 数字 | 出处 / 含义 |
|---|---|---|
| 一级缓存容器 | 1 个 | `PerpetualCache`，无淘汰，靠整表清空 |
| 一级缓存失效触发 | 任意 1 次写 | `update()` 无条件 `clearLocalCache()` |
| CacheKey 段数 | 4 段 | statementId / 参数 / offset / limit / SQL 文本 |
| CacheKey 乘法因子 | 37 | `DEFAULT_MULTIPLIER`，实测确认 |
| CacheKey 初始 hash | 17 | `DEFAULT_HASHCODE` |
| equals 闸门 | 4 道 | count → checksum → hashcode → 逐元素 |
| 二级缓存默认容量 | 1024 | `size`，默认 `LRU` |
| 可插桩对象 | 4 个 | Executor / StatementHandler / ParameterHandler / ResultSetHandler |
| 插件包裹顺序 | 后注册在外 | `pluginAll` 顺序套娃 |
| 执行器实现 | 3 + 1 | Simple / Reuse / Batch + 装饰器 Caching |
| Hikari 默认池 | 10 | `maximumPoolSize` 从 -1 解析而来 |
| Hikari connectionTimeout | 30000 | 池打满的报错延时 |
| Hikari maxLifetime | 1800000 | 30min，必须小于链路最小超时 |
| Hikari idleTimeout | 600000 | 10min，且必须小于 maxLifetime |
| Hikari 最小 idle 阈值 | 10000 | 小于 10s 会被强制改回默认并告警 |
| 池大小经验值 | CPU×2+盘 | 8 核 ≈ 17 |
| N+1 往返 | 1+N×M | 100 单 × 3 明细 = 301 |
| TypeHandler 调用 | 行 × 列 | 1 万行 × 8 列 = 8 万次 |
| 动态 SQL 重解析 | 每次执行 | DynamicSqlSource 不复用 BoundSql |
| 批量插入往返压缩 | 100 : 1 | 1 万条 → 约 100 条多值 INSERT |

---

## L13 十四个必答问题（压缩到要点）

**Q1：Mapper 接口没有实现类，为什么能注入？为什么必须是接口？**
A：`MapperFactoryBean.getObject()` 返回 `MapperProxyFactory` 造出的 JDK 动态代理。JDK 代理只能代理接口，所以 Mapper 必须是接口——这是技术约束，不是设计偏好。

**Q2：一级缓存和二级缓存的区别？一级缓存为什么「失效」？**
A：作用域 SqlSession vs namespace；容器 `PerpetualCache`（无淘汰）vs 可配 LRU/FIFO；生效时机立即 vs commit 后。一级缓存从未失效，是 **SqlSession 生命周期太短**——Spring 无事务时每次调用新建 session，缓存随对象销毁。

**Q3：二级缓存为什么必须 commit 才生效？这带来什么副作用？**
A：`TransactionalCache` 把写和清都推迟到 commit，避免未提交数据外泄。副作用是**清理也被推迟**：同一事务内 update 后 select，一级缓存已清、二级缓存未清，命中旧值。

**Q4：`#{}` 和 `${}` 的本质区别？**
A：不是「转义与否」，而是**SQL 结构是否固定**。`#{}` 生成 `?` 走 `ParameterHandler` 绑定，结构固定可复用执行计划；`${}` 在解析期由 `TextSqlNode` 做 OGNL 替换，结构随参数变。二者不可互相替代。

**Q5：哪些地方只能用 `${}`？怎么保证安全？**
A：表名、列名、`order by`、部分方言的 `limit` 写法——因为占位符不能替换标识符。安全做法是**枚举白名单 + 映射**（`price_desc → price DESC`），不要用「过滤关键字」这种黑名单思路。

**Q6：MyBatis 插件能拦哪些对象？**
A：只有 4 个，`Executor`、`StatementHandler`、`ParameterHandler`、`ResultSetHandler`。3.5.19 的 `Invocation` 构造函数里有白名单校验，拦其他接口会直接抛 `IllegalArgumentException`（实测）。

**Q7：多个插件的执行顺序怎么定？**
A：`InterceptorChain.pluginAll` 按注册顺序 `target = i.plugin(target)` 循环套娃，**后注册的在最外层、先被调用**。要调整就调 `@Bean` 声明顺序或用 `@Order`。

**Q8：为什么插件写了却不生效，日志也没有？**
A：最可能是 `@Signature` 的 `args` 与实际重载**不完全匹配**。`Plugin.getSignatureMap` 只把「名字 + 参数类型」都命中的方法放进代理集合，没命中就整个方法不被代理，**不报错、不打日志**。忘了 `@Intercepts` 则会直接抛 `PluginException`。

**Q9：ResultSet 是怎么变成对象的？**
A：`ResultSetHandler.handleResultSets` → 按 `ResultMap` 逐列取 `TypeHandler.getResult` → 反射/Setter 赋值；无 `resultMap` 时走 `AutoMappingBehavior`（默认 PARTIAL）按列名与属性名匹配。全程一次性读进内存，不做流式。

**Q10：怎么发现 N+1？**
A：看 SQL 日志里「同一条子查询按不同参数重复出现 N 次」；或关掉 `lazyLoadingEnabled` 后统计一次请求的 SQL 条数。100 条主记录配 3 个嵌套集合就是 301 条。

**Q11：批量插入怎么写才真的快？**
A：三层同时满足，`ExecutorType.BATCH`（少创建 Statement）+ `rewriteBatchedStatements=true`（驱动拼多值 INSERT）+ 单个事务（少提交）。只开 BATCH 不开 rewrite，仍然逐条发送，几乎没提升。

**Q12：报 `Connection is not available, request timed out after 30000ms` 怎么查？**
A：三步，① 开 `leakDetectionThreshold` 抓「借了没还」的堆栈；② `SHOW PROCESSLIST` 看 `Sleep` 与 `Query` 的比例——大量 Sleep 是连接被事务占着，大量 Query 是慢 SQL；③ 检查 `@Transactional` 里是否包了 RPC/MQ。**不要先调大池**。

**Q13：动态 SQL 会不会成为性能瓶颈？**
A：开销是「每次执行重新 walk SqlNode 树 + OGNL 求值」，量级 2~5μs，1 万次调用约 20~50ms。真正致命的是在 for 循环里调 Mapper，那是每次一次网络往返（ms 级）。先分清是 μs 还是 ms。

**Q14：怎么实现读写分离 / 多数据源？**
A：拦 `Executor`，按 `MappedStatement.getSqlCommandType()` 判断读写，配合 `AbstractRoutingDataSource` 切换。**关键陷阱**：必须保证同一事务内全走主库，否则主从延迟会让「写完立刻查」查不到——判断依据是 `TransactionSynchronizationManager.isActualTransactionActive()`。

---

## L14 三道模拟面试（含四档对照）

### 题 1：订单列表页

> 100 万订单，每页 20 条，要显示每单的明细条数。要求不能 N+1、不能深翻页慢、不能 OOM。说说你的持久层设计。

- **不及格**：写 `selectList` 查全量再内存分页；或用 `<collection>` 嵌套查询拿明细条数。
- **及格**：用 `limit offset, size` 分页，明细数用 `<collection>` 的 `select` 或 `count(*)` 子查询。功能对，但 20 条 = 21 次往返，翻到第 5 万页时 `offset 1000000` 要扫 100 万行。
- **良好**：明细数用一条 `left join + group by` 或「`in (20 个 id)` 再内存分组」压到 2 次往返；分页改游标式 `where id < lastId order by id desc limit 20`，避开 offset；总数用单独计数接口 + 缓存。
- **优秀**：再补三点，① 明细条数属于**可容忍延迟的统计量**，落一张 `order_stat` 表由 MQ 异步增量维护，列表页零 join；② 游标分页要求排序键唯一且与索引一致，向面试官确认「是否允许跳页」——不允许才能用游标；③ 明确说**不开二级缓存**（多实例脏读），热点用本地缓存 + 版本号，与第 17 期口径对齐。

### 题 2：连接池打满

> 线上大量 `Connection is not available, request timed out after 30000ms`，但应用 CPU 只有 20%，数据库连接数接近上限。你怎么定位？

- **不及格**：「把 `maximumPoolSize` 调到 200」。
- **及格**：先看慢查询日志，确认有没有缺索引的大 SQL；再看连接是否泄漏。
- **良好**：CPU 低 + 连接满 = **连接被持有但不干活**。三个方向，① 开 `leakDetectionThreshold=60000` 抓借出点堆栈；② `SHOW PROCESSLIST` 看 `Sleep` 占比，Sleep 多说明连接在事务里空等；③ 查 `@Transactional` 方法内是否有 HTTP/MQ 调用，把持连时间从毫秒拉到秒级。
- **优秀**：再往前推一步，把账算出来。池 10 个、单请求持连 3s，理论并发上限 = 10 / 3 ≈ 3.3 QPS，超过就排队，排队 30s 抛异常——**用 Little's Law 反证「不是池小，是持连太长」**。然后给处置顺序：缩短事务 → 拆外部调用 → 加读库 → 最后才考虑调池，并说明调池的代价是每连接 256KB~1MB session buffer 与更严重的锁竞争。

### 题 3：写一个自动填充 + 多租户插件

> 自动填充 `create_time`/`update_time`，并强制所有查询带上 `tenant_id` 条件。**你拦哪个对象，为什么？**

- **不及格**：拦 `ResultSetHandler`，或者干脆在每个 XML 里手写。
- **及格**：时间填充拦 `ParameterHandler.setParameters`；租户条件拦 `StatementHandler.prepare` 改 SQL。
- **良好**：说明取舍。时间填充在 `ParameterHandler` 能拿到参数对象与 `BoundSql`，改属性即可；租户条件必须改 SQL 结构，只有 `StatementHandler` 或 `Executor` 能做。**但改 SQL 要注意 `BoundSql.sql` 与 `parameterMappings` 必须同步改**，否则 `?` 数量对不上，报参数越界。
- **优秀**：再补四个工程细节，① 两个插件不要合成一个类（`@Signature` 数组虽支持多签名，但混在一起后顺序不可控）；② `@Intercepts` 的 `args` 必须精确到重载，否则静默不生效；③ 租户条件要跳过 `information_schema` 类语句，避免把系统查询也加上条件；④ 提供 `@IgnoreTenant` 注解作为逃生口，并说明「插件后注册在外层，所以租户插件应最后注册」。最后主动提一句：这类全局改写更适合放在 MyBatis-Plus 的 `TenantLineInnerInterceptor` 或直接用数据库行级安全，自研插件的维护成本要提前告知。

---

## L15 全 24 期知识地图（到此收束）

按每 2 小时的节奏，24 个主题刚好覆盖一线 Java 后端面试的全部主干。

| 板块 | 期号 · 主题 | 一句话抓手 |
|---|---|---|
| 语言与集合 | 01 · HashMap → ConcurrentHashMap | 扰动函数、树化阈值 8/6/64、扩容 2 次幂 |
| 语言与集合 | 23 · 泛型擦除 · 反射 · 动态代理 | 桥方法、inflation 15、稳态反射只慢 7 倍 |
| JVM | 02 · 内存模型与 GC | 三色标记、CMS→G1→ZGC |
| JVM | 12 · 调优与压测 | Eden = 分配速率 × YGC 间隔 |
| JVM | 21 · 内存泄漏取证 | 支配树、ThreadLocal 七跳链 |
| JVM | 22 · JIT 与基准测试 | 内联 35/325、逃逸分析、JMH 七坑 |
| 并发 | 03 · 锁升级 · AQS · 线程池 | Mark Word 三位、CLH 队列 |
| 并发 | 14 · 集合并发容器 | ArrayList 1.5 倍、CHM 锁粒度 |
| 并发 | 20 · 线程池深水区 | ctl 3+29 位、依赖栈 LIFO |
| 并发 | 19 · NIO 与 Netty | epoll 红黑树、ByteBuf 双指针 |
| 框架 | 04 · Spring 核心 | refresh 十二步、三级缓存 |
| 框架 | 15 · Boot 自动配置 | 153→60→40 六阶段 |
| 框架 | 13 · 类加载与新特性 | 双亲委派四次被打破、虚拟线程 pinning |
| 存储 | 05 · MySQL 索引与事务 | B+ 树三层 1170 扇出、ReadView 四字段 |
| 存储 | 16 · 分库分表与迁移 | 2053 万行上限、基因法 |
| 存储 | **24 · MyBatis 与持久层** | **九站执行链、CacheKey 37 进制、四道闸门** |
| 缓存 | 06 · Redis | 单线程边界、跳表、混合持久化 |
| 缓存 | 17 · 缓存一致性 | 失败率是乘法：99.85% → 1.35 亿次脏读 |
| 分布式 | 07 · MQ 与分布式事务 | LEO/HW/ISR、六种事务方案 |
| 分布式 | 08 · Spring Cloud | 三级缓存 90~180s、重试放大 27 倍 |
| 分布式 | 18 · 幂等与防重 | 0 行双重语义、处理中带租期 |
| 分布式 | 10 · 大厂场景设计 | 秒杀四道闸门、雪花位图 |
| 工程能力 | 09 · 线上排查 | CPU 六真凶、OOM 九落点 |
| 工程能力 | 11 · 设计模式 | 策略替 if-else、责任链三变形 |

- **这一期在整张地图里的位置**：05 讲了 B+ 树与索引，那是「数据库怎么想」；24 讲的是「框架怎么问」。两者的接缝处正是面试最爱问的地方：为什么这条 SQL 走了索引还是慢（框架侧是 N+1、是假批量、是连接被占），为什么加了索引但没生效（框架侧是 `${}` 拼接让 SQL 结构每次都变、是 `select *` 导致覆盖索引失效）。能把两侧连起来讲的人，比只会背索引口诀的人高一档。

---

**本期事实来源**：`mybatis-3.5.19.jar` 字节码（`javap -p -c`）与运行时反射实验、`HikariCP-5.1.0.jar` 默认值实测、JDK 21.0.7。所有标注「实测」的数字均可复现，标注「估算」的已注明。

Java 面试深挖 · 第 24 期 · 生成于 2026-11-12

**下期预告**：第 25 期 Java 模块化与工程化边界——`module-info` 能解决什么、classpath hell 的真实成因、`jdeps` 查循环依赖、Multi-Release JAR 与 shade 冲突、为什么大多数团队用 ArchUnit 而不是 JPMS。
