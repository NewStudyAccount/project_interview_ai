# Java 面试深水区 11 · 设计模式与代码品味

> 这一期不讲"23 种模式是什么"——那本《设计模式》你背得比我熟。这期只讲面试官真正在筛的东西：**你能不能看出变化点在哪、能不能用最小的结构把变化关起来、以及你能不能忍住不乱加一层**。
>
> 所有模式都用同一把尺子量：**下一个需求来了，你要改几个文件？**

---

## 目录

- L0 面试官问设计模式时，实际在考什么
- L1 策略模式：if-else 的四种死法与四种活法
- L2 模板方法与回调：控制权在谁手里
- L3 责任链：一条链上的三种变形
- L4 单例：五种写法的成本表与 DCL 的真相
- L5 工厂三兄弟与建造者
- L6 结构型三兄弟：装饰器 / 代理 / 适配器
- L7 观察者与发布订阅：同一条消息，两种耦合方向
- L8「你项目里用过什么设计模式」怎么答
- L9 速查表与决策图
- L10 14 条高频问答
- L11 3 道模拟面试题

---

## L0 面试官问设计模式时，实际在考什么

大多数人答这道题的方式是："设计模式有 23 种，分为创建型、结构型、行为型……"然后面试官心里给你打了个"背过八股"的标签，接着问下一个问题。因为你答的是**目录**，不是**判断力**。

真实岗位上的模式能力分三层，难度递增，面试官的话术也完全不同：

```
┌──────────────────────────────────────────────────────────────┐
│ 第 1 层 · 会不会写（写法层）                        → 及格线  │
│   话术：「策略模式怎么实现」「单例有几种写法」                │
│   考点：语法正确性 —— 接口抽得对不对、DCL 有没有 volatile     │
├──────────────────────────────────────────────────────────────┤
│ 第 2 层 · 会不会选（取舍层）                      → 分水岭    │
│   话术：「为什么用责任链不用 if-else」「策略和状态怎么选」    │
│   考点：能不能说出代价 —— 多一个类、多一次跳转，值不值        │
├──────────────────────────────────────────────────────────────┤
│ 第 3 层 · 敢不敢删（克制层）                      → 加分项    │
│   话术：「你觉得哪些设计是过度设计」「这段能不能删」          │
│   考点：品味 —— 知道什么时候「不」该加模式，比该加更难        │
└──────────────────────────────────────────────────────────────┘
```

### 23 种模式的分布：真正高频的只有 8 个

| 分类 | 模式 | 业务里的真实身影 | 面试频次 |
|---|---|---|---|
| 创建型 | **单例** Singleton | Spring 单例 Bean、连接池、配置客户端 | ★★★★★ |
| 创建型 | **工厂方法** / 简单工厂 | 支付渠道创建、MQ Producer、按配置选实现 | ★★★★ |
| 创建型 | **建造者** Builder | 请求参数组装、ES Query、Lombok @Builder | ★★★ |
| 结构型 | **代理** Proxy | Spring AOP、MyBatis Mapper、RPC 桩 | ★★★★★ |
| 结构型 | **装饰器** Decorator | Java IO 流、HttpServletRequestWrapper | ★★★ |
| 结构型 | **适配器** Adapter | slf4j 绑定、老接口兼容层、第三方 SDK | ★★ |
| 行为型 | **策略** Strategy | 计费规则、路由选择、风控规则、导出格式 | ★★★★★ |
| 行为型 | **模板方法** Template | JdbcTemplate、refresh()、各种 AbstractXxx | ★★★★ |
| 行为型 | **责任链 / 观察者** | Filter 链、审批流、Spring 事件、MQ 消费 | ★★★★ |

其余的（解释器、备忘录、访问者、中介者、迭代器）知道名字就行，除非你去中间件/编译器团队。

### 一句话总纲

设计模式不是"怎么写代码"，是"**把哪一块变化从主流程里挖出来，单独关起来**"。判断该不该用，只问三个问题：

1. **变的是什么？**（变的是规则？是流程？是创建方式？）
2. **不变的是什么？**（骨架、协议、调用入口）
3. **下一个需求来了，我要改 1 个文件还是 N 个文件？**

答不上第 3 问，就别加模式。

### 反模式信号

下面任意两条出现，说明抽象过头了：

- **抽象类只有一个实现类**，且从没被替换过 —— 这是"预防性抽象"，不是设计。
- **为了加一个字段，改了 6 个文件**：接口、实现、工厂、DTO、Converter、Enum。变化没被关起来，是被摊开了。
- **类名里出现 Abstract + Strategy + Factory + Manager 四个词**。Spring 内部真有 `AbstractSingletonProxyFactoryBean`，但它存在的理由是框架要兼容一切，不是业务该学的榜样。
- **新人要画 3 张图才能读懂一次下单**。主流程从 30 行变成 30 个类，那不是解耦，是分散。

> **记住这个数字**：一个中等规模业务系统（20 万行代码），真正需要显式命名的模式实例通常不超过 **15 处**。简历上写"熟练运用 23 种设计模式"，面试官的第一反应不是"厉害"，而是"这人没在一线写过业务"。

---

## L1 策略模式：if-else 的四种死法与四种活法

### 先算一道题：20 个分支是怎么长出来的

支付模块，5 个渠道（支付宝、微信、银联、余额、积分）× 4 个动作（下单、退款、查单、对账）。

用 if-else 硬写，代码量不是 `5 + 4`，是 `5 × 4 = 20` 个分支。渠道加到 6 个、动作加到 5 个，是 `30` 个分支。**每加一个渠道要动 5 处；每加一个动作要动 6 处。** 这叫乘法级联。

> **面试陷阱**：问"if-else 有什么问题"，答"可读性差、不优雅"会被判定为审美抱怨。正确答法是**用变更成本讲话**：N 个维度 × M 个取值的分支矩阵，每新增一个取值就要改 M 处，且没有任何编译期保护——漏改一处就是线上 bug。

### 四个演进阶段

```
① 硬编码 if-else          ② 策略接口 + 分支        ③ 表驱动 Map          ④ 注解自动注册
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│ if (ali) {...}  │      │ PayStrategy s;  │      │ Map<String,S> m │      │ @PayChannel(ALI)│
│ else if (wx)... │ ───▶ │ if (ali) s=new  │ ───▶ │ map.put("ALI",) │ ───▶ │ class AliPay    │
│ else if (un)... │      │ else s=new Wx() │      │ m.get(ch)       │      │ Spring 注入 List│
│ else if (bal).. │      │                 │      │                 │      │ 自己建注册表    │
├─────────────────┤      ├─────────────────┤      ├─────────────────┤      ├─────────────────┤
│ 新增渠道        │      │ 算法抽走了      │      │ 查表代替判断    │      │ 新增渠道        │
│ 改 20 处        │      │ 分支还在→改1处  │      │ 但注册表手维护  │      │ = 加 1 个类     │
└─────────────────┘      └─────────────────┘      └─────────────────┘      └─────────────────┘
   爆炸半径: 最大           爆炸半径: 1 处           爆炸半径: 1 处           爆炸半径: 0 处
```

四阶段的核心差异不是"优雅"，是「变更爆炸半径」。第 ④ 阶段才是 OCP（对扩展开放、对修改关闭）的真实含义：**新增渠道时零改动已有代码**。

代价：类数量 1 → 1+N，调用链深度 0 → 3 层。**所以 3 个以内且不再增长的分支，老老实实写 if-else 才是对的**。判据不是"有几行"，而是"这个维度还会不会长"。

### 写法 ③：表驱动（最小可用版）

```java
public interface PayStrategy {
    PayResult pay(PayRequest req);
    String channel();          // 让策略自报家门，注册时不用外部写死 key
}

// 注册中心：启动时扫一遍所有策略，建 Map
@Component
public class PayStrategyRegistry {
    private final Map<String, PayStrategy> map = new HashMap<>();

    public PayStrategyRegistry(List<PayStrategy> strategies) {
        for (PayStrategy s : strategies) {
            PayStrategy old = map.put(s.channel(), s);
            if (old != null) throw new IllegalStateException("渠道重复注册: " + s.channel());
        }
    }

    public PayStrategy get(String channel) {
        PayStrategy s = map.get(channel);
        if (s == null) throw new BizException("不支持的支付渠道: " + channel);
        return s;              // 不要 return null，让调用方 NPE 是最难查的 bug
    }
}
```

### 写法 ④：Spring 直接注入 Map

Spring 支持按 Bean 名称注入 `Map<String, T>`，key 是 beanName，value 是 Bean 本身。所以只要给每个策略起好 bean 名，连注册表都不用写。

```
PayController                    容器构建的 Map                三个策略 Bean
┌──────────────────┐          ┌──────────────────────┐      ┌──────────────────────┐
│ Map<String,      │  get(ch) │ "aliPay"  → AliPay   │ ───▶ │ @Service("aliPay")   │
│   PayStrategy>   │ ───────▶ │ "wxPay"   → WxPay    │ ───▶ │ @Service("wxPay")    │
│                  │          │ "balance" → Balance  │ ───▶ │ @Service("balance")  │
│ 不注入具体实现   │          └──────────────────────┘      └──────────────────────┘
└──────────────────┘
```

三个必须知道的细节：

1. key 是 **beanName**（默认首字母小写的类名），不是枚举值 —— 想用 `"ALI"` 必须在 `@Service` 里显式写名字。
2. 加了 `@Order` 只影响 List 注入顺序，**Map 注入不保证顺序** —— 需要优先级就用 List 自己排。
3. **致命坑**：如果手工 `new AliPayStrategy()`，它不经过容器 → 没有代理 → `@Transactional` / `@Async` 形同虚设。策略必须从容器拿。

```java
@Service("aliPay")                       // ← beanName 即 Map 的 key
public class AliPayStrategy implements PayStrategy {
    @Override public String channel() { return "ALIPAY"; }
    @Override public PayResult pay(PayRequest req) { /* ... */ }
}

@Service
public class PayService {
    private final Map<String, PayStrategy> strategies;   // key = beanName

    public PayService(Map<String, PayStrategy> strategies) {
        this.strategies = strategies;
    }

    public PayResult pay(String channel, PayRequest req) {
        // 用 channel() 反查更稳：避免 beanName 与业务渠道码耦合
        return strategies.values().stream()
                .filter(s -> s.channel().equals(channel))
                .findFirst()
                .orElseThrow(() -> new BizException("不支持: " + channel))
                .pay(req);
    }
}
```

> **加分回答**：上面为了讲清 Map 注入用了 `values().stream()` 线性查找。生产上应改成构造期建一次 `Map<String, PayStrategy> byChannel`（O(1)），否则每次支付都遍历一遍所有策略。主动指出这个 O(n) → O(1) 的优化，比背模式定义有用得多。

### 策略 vs 状态

| 维度 | 策略 Strategy | 状态 State |
|---|---|---|
| 谁决定切换 | **外部**（调用方传 channel / 配置 / 用户选择） | **内部**（对象自己根据状态决定下一状态） |
| 策略之间关系 | 互不知情，平级并列 | 知道彼此的转移关系，形成状态机 |
| 典型场景 | 支付渠道、计费规则、导出格式 | 订单状态流转、审批流、TCP 连接状态 |
| 一句话记法 | "我选一个算法来跑" | "我自己跑到下一步去了" |

两者代码骨架几乎一样（接口 + 多实现 + 上下文持有接口），**差别全在"谁来触发切换"**。

---

## L2 模板方法与回调：控制权在谁手里

模板方法最本质的一句话是**控制权反转**：父类掌握流程（什么时候做什么），子类只提供细节（怎么做）。所以骨架方法必须是 `final` —— 这是模式的契约，一旦允许覆盖，模式就退化成"可选的建议"。

```
AbstractExporter（父类）                        CsvExporter（子类 A）
┌────────────────────────────────────┐        ┌──────────────────────────────┐
│ public final void export()         │        │ open()      → 建 Writer      │
│   1. validate();    // 可覆盖      │        │ doExport()  → 逐行写 CSV     │
│   2. open();        // 抽象，必须  │        │ needHeader()→ 用默认值 true  │
│   3. if (needHeader()) writeHeader │        │ 只写 2 个方法，流程一行没碰  │
│   4. doExport();    // 抽象，必须  │        └──────────────────────────────┘
│   5. close();       // finally 中  │        ┌──────────────────────────────┐
│                                    │        │ XlsxExporter（子类 B）       │
│ protected boolean needHeader()     │        │ open()      → 建 Workbook    │
│   { return true; }   ← 钩子        │        │ doExport()  → 写 Sheet       │
│ 空实现钩子：子类不关心就不用写     │        │ needHeader()→ false（无表头）│
└────────────────────────────────────┘        └──────────────────────────────┘
   final 锁死顺序 ↑                              同一个骨架，行为不同
```

三类方法要分清：

- **抽象方法**：必须实现（`open`、`doExport`）
- **钩子方法**：可选覆盖，给默认值（`needHeader`）
- **具体方法**：可覆盖但要谨慎（`validate`）

### 你在用的模板方法

```java
// 1. JdbcTemplate：连接获取/释放/异常翻译全在骨架里，你只写 SQL 映射
jdbcTemplate.query("select * from t_order where id = ?",
    (rs, i) -> new Order(rs.getLong("id"), rs.getString("no")),
    1L);   // ← 第三个参数是 RowMapper，就是钩子

// 2. Spring 的 refresh()：12 步流程写死，子类只能通过覆盖钩子插手
public void refresh() throws BeansException {
    prepareRefresh();            // 可覆盖
    ConfigurableListableBeanFactory bf = obtainFreshBeanFactory();
    prepareBeanFactory(bf);
    postProcessBeanFactory(bf);  // ← 典型钩子，空实现
    invokeBeanFactoryPostProcessors(bf);
    registerBeanPostProcessors(bf);
    initMessageSource();
    initApplicationEventMulticaster();
    onRefresh();                 // ← 典型钩子，Boot 用它启动内嵌 Tomcat
    registerListeners();
    finishBeanFactoryInitialization(bf);
    finishRefresh();             // 可覆盖，发布 ContextRefreshedEvent
}

// 3. HttpServlet.service()：按 method 分发到 doGet/doPost，你只写 doGet
protected void service(HttpServletRequest req, HttpServletResponse resp) {
    String method = req.getMethod();
    if ("GET".equals(method)) doGet(req, resp);
    else if ("POST".equals(method)) doPost(req, resp);
}
```

> **面试可直接引用的观察**：Spring 的 `refresh()` 之所以能同时支撑 XML 配置、注解配置、Spring Boot 三种形态，就是因为它把"顺序"锁在 `refresh()` 里，把"差异"放到 `onRefresh()`、`postProcessBeanFactory()` 这些钩子里。**你不需要读懂每一步，只需要知道哪几步是空的** —— 那才是留给扩展的口子。

### 模板方法 vs 策略

| 维度 | 模板方法 | 策略 |
|---|---|---|
| 复用方式 | 继承（父类骨架 + 子类填空） | 组合（上下文持有策略接口） |
| 扩展粒度 | 部分步骤 | 整个算法 |
| 运行期可换 | **不能**（编译期定死） | **能**（set 一下换算法） |
| 致命限制 | Java 单继承，占掉唯一 extends 名额 | 类数量膨胀，调用链变深 |
| 选它的信号 | 流程固定、只有个别步骤不同 | 算法整体可替换、且要能动态切换 |

- **易错补充**：模板方法里父类调用子类方法确实有风险，叫**构造器里调用可覆盖方法**。父类构造器调了子类实现的方法时，子类字段还没初始化（全是默认值 0/null），会拿到脏数据。这是《Effective Java》第 19 条明令禁止的写法。
---

## L3 责任链：一条链上的三种变形

### 本质：把"判断"换成"传递"

一段校验代码长这样：`if (a) return err; if (b) return err; if (c) return err;`。责任链把它拆成 `a → b → c` 三个对象，每个对象只回答两个问题：**我要不要处理？处理完是停下还是交给下一个？**

这个"停下还是继续"的开关，分出了三种形态 —— 面试里 90% 的混乱都源于把它们当成一个东西。

| 形态 | 典型实现 | 行为 | 真实场景 |
|---|---|---|---|
| **纯责任链** | 自己写 `next` 引用 | 找到能处理的那个就**停** | 审批流、异常处理链、工单升级 |
| **过滤链** | Servlet Filter、Netty ChannelHandler | 每个节点**都执行**，任一可短路 | 鉴权、限流、日志、XSS 清洗 |
| **拦截器** | Spring Interceptor、AOP Around | 每个节点**前后都能插逻辑** | 耗时统计、事务、TraceId 透传 |

### 为什么 Spring MVC 要搞三层"链"

标准答法不是背定义，而是**说清每一层能拿到什么、拿不到什么** —— 能力边界决定了它必须存在。

```
┌─────────────────────────────────────────────────────────────────┐
│ ① Filter 链（Servlet 容器层）                                   │
│   看得到 HttpServletRequest / Response，看不到 Controller 方法  │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │ ② Interceptor（DispatcherServlet 内）                   │   │
│   │   看得到 HandlerMethod，看不到方法参数值                │   │
│   │   ┌─────────────────────────────────────────────────┐   │   │
│   │   │ ③ AOP（Bean 代理层）                            │   │   │
│   │   │   看得到方法签名 + 实参 + 返回值 + 异常         │   │   │
│   │   │   ┌─────────────────────────────────────────┐   │   │   │
│   │   │   │   Controller.method()  ← 最内核         │   │   │   │
│   │   │   └─────────────────────────────────────────┘   │   │   │
│   │   └─────────────────────────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

一次请求的完整时序（含异常分支）：

```
Filter#1 ─▶ Filter#2 ─▶ preHandle ─▶ Controller ─▶ postHandle ─▶ afterCompletion ─▶ Filter#2 回程
            才能拦       返回 false     AOP 环绕      异常时跳过      一定执行         response 回写
            静态资源     即中断
```

### 手写一条责任链

```java
public abstract class Validator {
    private Validator next;                       // 单链表，不是 List

    public Validator linkWith(Validator next) {   // 返回 next 便于链式拼装
        this.next = next;
        return next;
    }

    public abstract String validate(OrderContext ctx);   // 返回 null 表示通过

    protected String checkNext(OrderContext ctx) {       // 统一"递下去"
        return next == null ? null : next.validate(ctx);
    }
}

public class StockValidator extends Validator {
    @Override public String validate(OrderContext ctx) {
        if (ctx.getStock() < ctx.getQty()) return "库存不足";
        return checkNext(ctx);                    // 只有通过才往下走
    }
}

public class PriceValidator extends Validator {
    @Override public String validate(OrderContext ctx) {
        if (ctx.getPrice().compareTo(BigDecimal.ZERO) <= 0) return "价格非法";
        return checkNext(ctx);
    }
}

// 组装（通常放 @PostConstruct 或配置类里，只组装一次）
Validator chain = new StockValidator();
chain.linkWith(new PriceValidator())
     .linkWith(new CouponValidator())
     .linkWith(new RiskValidator());

String err = chain.validate(ctx);   // 一条链跑完，短路返回
```

> **注意这里用了两个模式**：责任链（结构）+ 模板方法（`checkNext` 统一了"要不要继续"）。真实代码里模式很少单独出现，**能指出"这里是两个模式叠加"是加分项**。

### 三个工程坑

1. **链太长导致排查困难**。10 个节点的链，出问题时你不知道是哪一环返回的。解法：每个节点返回结构化结果（`errCode + nodeName`），不要只返回字符串。
2. **节点顺序敏感但没人记录**。鉴权必须在限流之前、限流必须在业务之前。解法：给节点加 `order()`，启动时打印一次完整链顺序到日志。
3. **节点内部有状态**。节点被 Spring 做成单例，如果节点里有成员变量存"当前请求的上下文"，就是并发 bug。上下文必须**作为参数传递**，不能存字段。

### Filter 读 body 的两个必踩坑

```java
// 坑 1：Filter 里 request.getInputStream() 读一次就空了 → Controller 拿不到 body
// 解法：用 HttpServletRequestWrapper 缓存（这是装饰器模式的典型应用）
public class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
    private final byte[] body;
    public CachedBodyRequestWrapper(HttpServletRequest req) throws IOException {
        super(req);
        this.body = StreamUtils.copyToByteArray(req.getInputStream());  // 只读一次
    }
    @Override public ServletInputStream getInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(body);
        return new ServletInputStream() {                                // 每次返回新流
            public int read() { return bais.read(); }
            public boolean isFinished() { return bais.available() == 0; }
            public boolean isReady() { return true; }
            public void setReadListener(ReadListener l) {}
        };
    }
}

// 坑 2：postHandle 在抛异常时被跳过 → 清理逻辑（如 MDC.remove）必须放 afterCompletion 或 finally
```

---

## L4 单例：五种写法的成本表与 DCL 的真相

### 先看坐标，再背写法

面试问"单例有几种写法"，从"饿汉"开始一个个背是记忆型回答。先画一张**安全性 × 复杂度**的坐标，再说明为什么枚举落在右上角，是理解型回答。

```
安全性 ↑
  高 │                    ④ 静态内部类        ⑤ 枚举
     │                    (懒加载+无锁)     (防反射/反序列化)
     │        ③ DCL+volatile
     │        (漏 volatile 就半初始化)
     │  ② 同步方法
     │  (线程安全但每次加锁)
 ────┼──────────────────────────────────────────────▶ 复杂度
     │  ① 懒汉（无锁）
     │  (并发下产生多实例)              ⑥ 容器托管 @Component
  低 │                                    (Spring 单例，非 GoF)
```

**结论**：业务代码首选 **④ 静态内部类**（可读、懒加载、无锁）；对防破坏有要求选 **⑤ 枚举**（《Effective Java》推荐）。

注意 ⑥ 不是 GoF 单例 —— Spring 的"单例"只是「容器里只有一个实例」，构造器仍是 public，谁都能 new。

### DCL 为什么必须加 volatile

`instance = new Singleton()` 在字节码层面是三步，不是一步：

```
正常顺序                          重排后（无 volatile，JMM 允许）
① 分配内存                        ① 分配内存
② 调用构造器                      ③ 引用先赋值  ← 提前了
③ 引用赋值                        ② 构造器

线程 B 在这条缝里进来：
  检查 instance != null → true（引用已非空）
  直接 return instance
  → 拿到一个字段全是 null / 0 的对象 ← 半初始化对象
```

**volatile 做两件事**：① 写屏障，禁止 ②③ 重排；② 可见性，A 写完立刻刷主存。底层靠 lock 前缀指令 + 内存屏障实现，代价是多一次主存访问，微乎其微。

那为什么还推荐静态内部类？因为**类加载机制自带同步**（JVM 保证 `<clinit>` 只执行一次），天然线程安全 + 天然懒加载（内部类首次被引用才加载），不需要 volatile，也不需要 DCL。"用语言机制代替手写同步"才是最高级的写法。

### 三段必须能默写的代码

```java
// ① 静态内部类（业务首选）
public class ConfigHolder {
    private ConfigHolder() {
        if (Holder.INSTANCE != null) throw new IllegalStateException("禁止反射创建");
    }
    private static class Holder {
        private static final ConfigHolder INSTANCE = new ConfigHolder();
    }
    public static ConfigHolder getInstance() { return Holder.INSTANCE; }
}

// ② 枚举（最安全）
public enum Singleton {
    INSTANCE;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    public String get(String k) { return cache.get(k); }
}
// 为什么防得住？
//  - 反射：Constructor.newInstance 判断 (clazz.getModifiers() & ENUM) != 0 就抛异常
//  - 反序列化：readObject 对枚举走 valueOf 分支，不调构造器
//  - 克隆：Enum 的 clone() 直接抛 CloneNotSupportedException

// ③ DCL（要能讲清每个关键字的作用）
public class LazySingleton {
    private static volatile LazySingleton instance;     // 少了 volatile 就是 bug
    private LazySingleton() {}
    public static LazySingleton getInstance() {
        if (instance == null) {                          // 第一次检查：避免每次都进锁
            synchronized (LazySingleton.class) {
                if (instance == null) {                   // 第二次检查：防重复创建
                    instance = new LazySingleton();
                }
            }
        }
        return instance;
    }
}
```

> **面试官最爱追的一刀**："DCL 里第一个 if 去掉行不行？" —— 行，但每次调用都要抢锁，在热点路径上（每秒百万次的配置读取）会形成锁竞争。**第一个 if 是性能优化，第二个 if 是正确性保证**。能分清"哪个是为了对、哪个是为了快"，是这道题的分水岭。

### Spring 的"单例"和 GoF 单例不是一回事

| 维度 | GoF 单例 | Spring singleton scope |
|---|---|---|
| 保证什么 | JVM 里只有**一个实例**（构造器私有） | 容器里只有**一个 Bean 定义对应的实例** |
| 能否 new | 不能 | 能，你 new 出来的是另一个对象 |
| 线程隐患 | 成员变量天然共享 | **同样共享** —— 最常见的并发 bug 来源 |

**真实事故模板**：Service 里写了 `private List<String> tempList = new ArrayList<>();` 当方法内临时变量用。因为 Service 是单例，这个 list 被所有线程共享 → 用户 A 的请求数据出现在用户 B 的响应里。这类 bug 复现率极低、排查极难。正确做法：**所有临时变量都在方法内声明**，成员变量只能是 `final` 的无状态依赖。

---

## L5 工厂三兄弟与建造者

### 先把"三个工厂"分清

| 模式 | 解决的问题 | 结构 | 真实场景 |
|---|---|---|---|
| **简单工厂**（不在 GoF 23 里） | 把 new 和分支收进一个方法 | 一个工厂类 + `create(type)` + if/switch | 轻量工具类；加产品要改工厂，违反 OCP |
| **工厂方法** | 加产品**不改**已有代码 | 抽象工厂 + 每产品一个具体工厂 | MQ Producer、不同协议客户端创建 |
| **抽象工厂** | 创建**一族**配套对象 | 一个工厂接口产多个产品 | 多数据库方言、跨云 SDK |

大多数业务代码里，**策略模式 + Map 注册** 已经替代了简单工厂。真正还需要"工厂"的场景只有两类：

1. **创建过程重**：需要连接、握手、加载配置、预热连接池。创建和使用必须分离。
2. **创建结果要复用**：IM 客户端按 appId 缓存，同一 appId 只建一次。这时工厂其实是"带缓存的对象池"。

```java
public interface MqProducerFactory {
    MqProducer create(MqConfig cfg);
    String type();
}

@Component
public class KafkaProducerFactory implements MqProducerFactory {
    private final Map<String, KafkaProducer<String, String>> cache = new ConcurrentHashMap<>();

    @Override public String type() { return "KAFKA"; }

    @Override public MqProducer create(MqConfig cfg) {
        return cache.computeIfAbsent(cfg.getServers(), k -> build(cfg));
    }

    private KafkaProducer<String, String> build(MqConfig cfg) {
        Properties p = new Properties();
        p.put("bootstrap.servers", cfg.getServers());
        p.put("acks", "all");                    // 不要用默认值，默认是 1
        p.put("enable.idempotence", "true");     // 幂等生产者，防重
        p.put("max.in.flight.requests.per.connection", "5");
        return new KafkaProducer<>(p);
    }
}
```

> **一个真实的坑**：`computeIfAbsent` 里做阻塞操作（建连接、调远程）会锁住整个桶，其他 key 的读写也被拖慢。生产上应"先 get，miss 后加锁重建"的双检模式，或直接用 Caffeine 的 `LoadingCache`。这段话在面试里主动说出来，说明你真处理过并发。

### 建造者：参数超过 4 个就该考虑

判定标准不是"参数多"，而是**存在同类型参数的顺序风险**：

```java
// 反面：三个 String 挨着，传错顺序编译器毫无意见
new Order("U1001", "S2002", "C3003");
new Order("S2002", "U1001", "C3003");   // 编译通过，运行时数据全错

// 正面：命名即约束
Order order = Order.builder()
        .userId("U1001")
        .skuId("S2002")
        .couponId("C3003")
        .build();      // build() 里统一做必填校验

public Order build() {
    Objects.requireNonNull(userId, "userId 必填");
    Objects.requireNonNull(skuId, "skuId 必填");
    if (qty <= 0) throw new IllegalArgumentException("qty 必须 > 0");
    return new Order(this);       // 对外不可变
}
```

**Lombok @Builder 的三个坑（能说出来就是老手）**：

1. **没有默认值**：`@Builder` 生成的 builder 字段不取类字段初始值，`int qty = 1;` 在 builder 里会是 0。必须加 `@Builder.Default`。
2. **父类字段丢失**：子类加 `@Builder` 不带父类字段，必须手写全参构造 + `@SuperBuilder`。
3. **和 @NoArgsConstructor 冲突**：加了 `@Builder` 后全参构造被生成、无参构造消失，Jackson 反序列化直接失败 —— 必须同时加 `@NoArgsConstructor @AllArgsConstructor`。

### 原型：为什么它几乎只在"创建成本极高"时才有意义

原型的本质是**用拷贝代替创建**。两个坑：`Object.clone()` 是浅拷贝（引用类型字段共享），深拷贝要么手写递归、要么序列化（慢 10~100 倍，且 `transient` 字段会丢）。

```java
// 浅拷贝的经典事故：改副本，原件也变了
Order copy = origin.clone();
copy.getItems().add(newItem);      // items 是同一个 List → origin 也被改了

// 序列化深拷贝（简单但慢，transient 字段丢失）
public Order deepCopy(Order o) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    new ObjectOutputStream(bos).writeObject(o);
    return (Order) new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray())).readObject();
}

// 更好的做法：显式拷贝构造器，字段变化时编译器会提醒你
public Order(Order other) {
    this.userId = other.userId;
    this.items = new ArrayList<>(other.items);   // 显式深拷，一眼看清
}
```

---

## L6 结构型三兄弟：装饰器 / 代理 / 适配器

三者都是"包一层"，都持有被包装对象的引用，都实现或继承同一个目标。区分的唯一办法是问一句：**包这一层，是为了加功能、控访问、还是转接口？**

```
装饰器 Decorator                代理 Proxy                   适配器 Adapter
 动机：加功能，可叠 N 层        动机：控访问，通常一层        动机：转接口，两侧不同

┌──────────────────┐          ┌──────────────────┐         ┌────────────────────────┐
│ InputStream      │          │ UserService      │         │ 我方：PayService.pay() │
├──────────────────┤          ├──────────────────┤         ├────────────────────────┤
│ BufferedInput... │          │ $Proxy（代理）   │         │ AlipayAdapter          │
├──────────────────┤          │ 事务/权限/日志   │         │ 参数转换 + 错误码翻译  │
│ GZIPInputStream  │          ├──────────────────┤         ├────────────────────────┤
├──────────────────┤          │ UserServiceImpl  │         │ 支付宝 SDK             │
│ FileInputStream  │          │ （真身）         │         │ trade.create()         │
└──────────────────┘          └──────────────────┘         └────────────────────────┘
 每层同接口 → 可任意嵌套       客户端无感知，以为调真身       接口不同 → 必须翻译
```

**一句话判别法（面试可直接背）**：

- **装饰器**：同接口 + 同接口 + 同接口，功能累加，客户端自己能拼（`new A(new B(new C()))`）
- **代理**：同接口，但客户端拿不到真身，代理是框架塞给你的 —— 你写 implementation，Spring 给你 proxy
- **适配器**：两侧接口不同，适配器负责翻译，它的存在本身就是接口不兼容的证据

### 装饰器：顺序会改变行为

```java
// 两种写法，语义完全不同
new BufferedInputStream(new GZIPInputStream(new FileInputStream(f)));   // 先解压再缓冲 ✅
new GZIPInputStream(new BufferedInputStream(new FileInputStream(f)));   // 先缓冲再解压 ⚠️

// 第二种为什么不好：GZIP 解压按块读，中间加一层缓冲会让每次 read 先穿过缓冲区，
// 多一次内存拷贝；严重时（缓冲区小 + 大文件）性能下降 20%~40%。
```

### 代理：JDK 动态代理 vs CGLIB

| 维度 | JDK 动态代理 | CGLIB |
|---|---|---|
| 前提 | 目标类**必须实现接口** | 目标类**不能是 final**，方法不能 final/private |
| 实现方式 | 生成 `$Proxy0` 实现同一接口，持 InvocationHandler | 生成子类，字节码级别覆写方法 |
| 创建速度 | 快（反射调用 Method） | 慢（ASM 生成字节码），但调用快 |
| 调用速度 | JDK 8+ 已优化，差距很小 | 直接 invoke，无反射开销 |
| Spring Boot 默认 | — | **CGLIB**（2.x 起默认） |

- **易错补充**：Spring Boot 之所以改成默认 CGLIB，是因为业务代码大量是"类不实现接口"的写法。坚持 JDK 代理，这些类根本没法被代理，`@Transactional` 直接失效。改成 CGLIB 后，只要不是 final 类就能代理，心智负担更小 —— **这是框架向"人的写法"妥协，不是技术更优**。这个回答比"因为 CGLIB 快"高一个层次。
---

## L7 观察者与发布订阅：同一条消息，两种耦合方向

### 观察者 ≠ 发布订阅，差在"中间有没有人"

区别就一句话：**观察者模式里主题直接持有观察者列表（强耦合）；发布订阅里双方只认识中间件（解耦）**。

| 维度 | 观察者 Observer | 发布订阅 Pub/Sub |
|---|---|---|
| 耦合点 | Subject 持有 `List<Observer>` | Publisher / Subscriber 只认识 Broker |
| 通信方式 | 方法调用（进程内、同步） | 消息（可跨进程、可异步） |
| 典型实现 | Spring `ApplicationEvent`、`PropertyChangeListener` | Kafka、RocketMQ、Redis Pub/Sub |
| 失败影响 | 一个观察者抛异常，可能拖垮发布者 | 消费者挂了不影响生产者（消息堆积） |

### Spring 事件：最方便，也最容易踩事务坑

Spring 的 `ApplicationEventPublisher` 默认是**同步调用**，而且在**发布者当前的事务上下文里执行**。也就是说，事件发出去了、监听器也执行了，但发布者的事务后来回滚了。

```
❌ @EventListener（默认同步 + 同事务）        ✅ @TransactionalEventListener(AFTER_COMMIT)

 下单                                          下单
  │ begin tx                                   │ begin tx
  │ insert order                               │ insert order
  │ publishEvent ──▶ 发送短信 ✅ 已发出         │ commit ✅
  │ 后续步骤抛异常                              │        └─▶ 事件此刻才触发
  │ rollback                                   │             发短信 / 发 MQ
  ▼                                            ▼
 结果：订单不存在，用户收到「下单成功」短信      结果：只有数据真的落库了才通知
 监听器抛异常还会连带回滚主流程 → 更糟          代价：commit 之后异常无法再回滚主事务
```

还有一个坑：**@Async 让事件变异步后，事务注解反而失效**。`@Async` 在新线程执行 → 新线程没有原事务上下文 → 监听器里写库是独立事务，与主流程无关，也不会被回滚。另外 `@Async` 方法抛异常会被线程池吞掉（默认只打日志）→ 必须配 `AsyncUncaughtExceptionHandler`。

所以 **AFTER_COMMIT + @Async 组合最常用，但必须自己兜住异常 + 幂等**，否则可靠性不如本地消息表。

```java
@Service
public class OrderService {
    private final ApplicationEventPublisher publisher;

    @Transactional
    public void createOrder(OrderCmd cmd) {
        orderMapper.insert(cmd.toOrder());
        // 只是"注册事件"，不是"执行监听器" —— 这句话能讲清就说明你真懂
        publisher.publishEvent(new OrderCreatedEvent(cmd.getOrderNo()));
        // 如果这里后续抛异常，事务回滚，AFTER_COMMIT 的监听器不会执行
    }
}

@Component
public class OrderNotifier {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("notifyExecutor")
    public void onCreated(OrderCreatedEvent e) {
        try {
            smsClient.send(e.getOrderNo());       // 必须自己 try-catch，@Async 会吞异常
        } catch (Exception ex) {
            log.error("通知失败, orderNo={}", e.getOrderNo(), ex);
            retryQueue.offer(e);                  // 兜底：重试队列 / 本地消息表
        }
    }
}
```

> **面试可以这样收尾**："Spring 事件适合**进程内、弱一致性、可容忍丢失**的通知场景（发短信、刷缓存、记审计日志）。如果这条消息关系到钱或库存，我会用**本地消息表 + MQ**，而不是 Spring 事件 —— 因为事件是内存里的，进程一挂就没了。"

---

## L8「你项目里用过什么设计模式」怎么答

### 这道题考的是叙事能力

绝大多数人答成"我们用了工厂模式创建对象、用了单例模式管理配置"，然后就没了。面试官听完只有一个印象：**你能识别模式，但没做过决策**。

好的回答有固定结构，五步缺一不可：

1. **场景**：一句话说清业务是什么（支付 / 审批 / 报表导出）。
2. **痛点 + 数字**：改动前是什么样，成本多大。**必须有数字。**
3. **方案**：用了什么结构，**以及为什么不用另一个**。
4. **结果 + 数字**：改动量、耗时、缺陷率的变化。
5. **反思（代价）**：这个方案带来了什么新问题。**这一段最能体现深度。**

### 四档回答对照（同一道题）

| 档位 | 回答 | 面试官判断 |
|---|---|---|
| **不及格** | "我们用过工厂模式、单例模式、观察者模式，Spring 里到处都是。" | 只会背名字，没有决策经历 |
| **及格** | "支付模块有多个渠道，我们用策略模式把 if-else 拆成多个实现类，加新渠道只要加一个类。" | 知道怎么用，但说不出代价 |
| **良好** | "原来 pay() 里有 20 多个分支，5 个渠道 × 4 个动作。加一个渠道要改 5 处，去年漏改过一次导致对账差异 3 万。改成策略 + Spring Map 注入后，新增渠道只加一个类，改动量从 5 处降到 0 处。代价是类数量从 1 个变成 8 个，新人要花半天理解路由逻辑，所以我们在类上加了一行注释说明怎么注册。" | 有数字、有取舍、有反思 |
| **优秀** | 在"良好"基础上补："后来发现 3 个渠道的退款逻辑高度相似，我们没有再抽一层策略，而是把公共部分下沉到 `AbstractPayStrategy`，只留 3 个真正不同的钩子。再后来有个渠道要求同步返回 + 异步对账，我们评估后决定**不**在这个模块里加复杂度，而是拆到独立的对账服务 —— 因为支付主链路不能被对账的失败率拖累。" | 知道什么时候**停手**，有边界意识 |

> **「优秀」那一段为什么值钱**：它包含三个高级信号 —— ① 用模板方法消除重复（会做二次抽象）；② 主动判断"不该在这里加复杂度"（有架构边界感）；③ 用失败率/影响面做决策（工程思维）。这三条比背 23 个模式难得多。

### 三个可现套的真实叙事（按你的项目改数字）

**叙事 A · 支付/渠道路由（策略 + 工厂）**

痛点：`switch(channel)` 里 5 个渠道 × 4 个动作 = 20 个分支，最长方法 400 行，单测覆盖率 12%。方案：策略接口 + 注解注册，`@PayChannel("ALIPAY")` 自动进注册表。结果：新增渠道 0 改动已有代码，单测覆盖率提到 68%。代价：调试断点要跳 3 层，在入口加 `log.debug("route to {}", channel)` 缓解。

**叙事 B · 审批流（责任链 + 状态机）**

痛点：审批规则由 8 个 `if` 串成，产品每次改规则都要发版，平均 2 周一次。方案：责任链把"谁审、审不审"拆成节点，节点配置化（从 DB 读，热更新）。结果：规则调整从"改代码 + 发版 2 周"变成"改配置 10 分钟"。代价：链路长了排查困难 → 每个节点输出结构化日志 `{node, result, cost}`，一次请求的完整链路可回放。

**叙事 C · 多数据源/多租户（抽象工厂 + 适配器）**

痛点：同一套查询逻辑要跑 MySQL 和 ClickHouse，SQL 方言差异导致 `if (isClickHouse)` 散落 30 多处。方案：抽象工厂产出"方言族"（Dialect + Parser + TypeMapper），适配器把两边 ResultSet 统一成同一结构。结果：方言差异收敛到 2 个类。代价：新增一种数据库要实现 3 个接口，前期成本比 if-else 高 —— **但如果只有一种数据库，这套东西就是纯负债**。

> **绝对不要说的话**："我们项目用了 12 种设计模式。" —— 面试官下一句一定是"哪 12 种？分别解决什么问题？"然后你开始背目录，必然被追问到卡壳。**主动收缩到 2~3 个讲透，永远优于铺开 12 个讲浅。**

---

## L9 速查表与决策图

### 决策图：四个问题问完，答案自然出现

```
① 这段逻辑会变吗？（未来 6 个月）
   │
   ├─ 否 ─────────────────▶ 别动它，写注释标记 TODO
   ▼ 会
② 变化是「多取值」还是「多步骤」？
   │
   ├─ 多取值（换算法）              ├─ 多步骤（加环节）
   ▼                                ▼
③ 调用方需要动态切换吗？         ③ 流程顺序固定、只差细节吗？
   │                                │
   ├─ 是 ──▶ 策略模式（组合）        ├─ 是 ──▶ 模板方法（继承，final 锁顺序）
   └─ 否 ──▶ 简单工厂或直接 new      └─ 否 ──▶ 责任链
```

判据总结：**变化的维度决定模式类型，变化的频率决定要不要抽**。只出现一次的"变化"，抽象它就是在赌未来。

### 12 条速查（面试前一晚只看这个）

| # | 知识点 | 一句话答案 |
|---|---|---|
| 1 | 模式的三层考点 | 会不会写 → 会不会选 → 敢不敢删；分水岭在第 2 层 |
| 2 | 该不该用模式 | 看「下一个需求来了改几个文件」，不是看代码好不好看 |
| 3 | if-else 的真实成本 | N 个维度 × M 个取值 = 分支矩阵，加一个取值改 M 处，无编译期保护 |
| 4 | 策略的 Spring 落地 | 注入 `Map<String,T>`，key 是 beanName；手工 new 会丢 AOP |
| 5 | 策略 vs 状态 | 谁来触发切换：外部选是策略，内部流转是状态 |
| 6 | 模板方法的核心 | 骨架方法必须 final；父类构造器里绝不能调可覆盖方法 |
| 7 | 模板方法 vs 策略 | 继承 vs 组合；能否运行期切换是唯一硬判据 |
| 8 | 责任链三种形态 | 纯链（找到就停）/ 过滤链（都过一遍）/ 拦截器（前后都能插） |
| 9 | Filter 读 body | 流只能读一次 → 必须 ContentCachingRequestWrapper |
| 10 | DCL 为什么 volatile | 「引用可见 ≠ 状态可见」，禁止 ②③ 重排，防半初始化对象 |
| 11 | 枚举单例为什么安全 | 反射 newInstance 抛异常、反序列化走 valueOf、clone 抛异常 |
| 12 | Spring 事件最大的坑 | 默认同步 + 同事务 → 回滚了但短信已发；要用 AFTER_COMMIT |

### 模式 ↔ 框架 对照

| 框架位置 | 用的模式 | 为什么 |
|---|---|---|
| `JdbcTemplate.query()` | 模板方法 | 连接/释放/异常翻译固定，只留 RowMapper 钩子 |
| `AbstractApplicationContext.refresh()` | 模板方法 | 12 步顺序锁死，`onRefresh()` 给 Boot 启动 Tomcat |
| `BeanFactory` / `FactoryBean` | 工厂方法 | 创建逻辑复杂（注入、代理、作用域）必须收口 |
| Spring AOP / `@Transactional` | 代理 | 不改业务代码就插入事务边界 |
| `HttpServletRequestWrapper` | 装饰器 | 同接口增强，后续代码无感知 |
| `slf4j` + `logback-classic` | 适配器 + 门面 | API 与实现解耦，换实现不改代码 |
| `ApplicationEventPublisher` | 观察者 | 业务解耦，但同步执行、进程内容易丢 |
| Servlet `FilterChain` | 责任链 | 鉴权/限流/日志可插拔、可短路 |
| `BeanDefinition` 的 Builder | 建造者 | 属性多且有必填项，避免构造器参数爆炸 |

---

## L10 14 条高频问答

**Q1：设计模式到底解决什么问题？一句话。**

把「会变的部分」从「不变的部分」里隔离出来，让变化只影响一个地方。所有 23 种模式都是这句话的不同切法。

**Q2：单例有几种写法？推荐哪个？**

五种主流：饿汉、懒汉（不安全）、同步方法、DCL + volatile、静态内部类，加上枚举共六种。业务代码推荐**静态内部类**（懒加载 + 无锁 + 可读）；对防反射/防反序列化有要求选**枚举**。饿汉的问题不是"不懒加载"，而是**类加载时就初始化，初始化失败会让整个类不可用**（ExceptionInInitializerError）。

**Q3：DCL 为什么必须加 volatile？去掉会怎样？**

`new` 分三步：分配内存、调构造器、引用赋值。②③ 可能重排，导致另一个线程看到非 null 引用但字段还是默认值（半初始化对象）。volatile 通过写屏障禁止重排 + 保证可见性。去掉后高并发下会偶发拿到字段为 null 的对象，且极难复现。

**Q4：策略模式怎么替代 if-else？替代后有什么坏处？**

抽接口 → 每分支一个实现 → 用 `Map` 或 Spring `Map<String,T>` 注入做路由。坏处：① 类数量从 1 变 N，新人理解成本上升；② 调用链多 2~3 层，调试要跳转；③ 分支只有 2~3 个时纯属过度设计。判据是"这个维度还会不会长"，不是"分支有几行"。

**Q5：策略模式和状态模式的区别？**

结构几乎一样，差别在**谁触发切换**：策略由外部选择（用户选支付方式），状态由对象内部根据当前状态决定下一步（订单从待支付变已支付）。状态模式通常还有转移表/状态机，策略之间互不知情。

**Q6：模板方法为什么骨架方法要加 final？**

因为模式的核心契约是"顺序由父类控制"。不加 final，子类重写就能绕过校验、跳过 close、改变步骤顺序，父类的复用价值瞬间归零，退化成"一堆可选建议"。这是正确性问题，不是编码规范问题。

**Q7：模板方法有什么已知缺陷？**

① 继承占掉唯一 extends 名额，业务类往往已有父类；② 编译期绑定，运行期不能换算法（不如策略）；③ **父类构造器里调可覆盖方法**会读到子类未初始化的字段（Effective Java 第 19 条）。

**Q8：责任链、过滤器链、拦截器有什么区别？**

都是链，差别在"能不能环绕"和"能不能中断"。纯责任链找到处理者就停；过滤链每个都执行、可短路；拦截器支持前后各插一段逻辑（preHandle/postHandle），AOP 甚至能改返回值。Spring MVC 里三层并存是因为能拿到的上下文不同：Filter 只有报文，Interceptor 有 HandlerMethod，AOP 有方法实参。

**Q9：Filter 里读了 request body，Controller 为什么拿不到？**

Servlet 的 `getInputStream()` 是流，读完就到底，没有 reset。解法是用 `HttpServletRequestWrapper` 在 Filter 里把 body 全读进 byte[] 缓存，重写 `getInputStream()` 每次返回新的 ByteArrayInputStream。这本质是**装饰器模式**的典型应用。

**Q10：JDK 动态代理和 CGLIB 怎么选？**

JDK 要求目标类实现接口，CGLIB 要求类和方法非 final。Spring Boot 2.x 起默认 CGLIB，原因是业务代码大量"类不实现接口"，用 JDK 代理会导致 `@Transactional` 静默失效 —— 这是框架对开发习惯的妥协，不是性能考虑。

**Q11：装饰器、代理、适配器的区别？**

看动机：装饰器是**加功能且可叠加**（IO 流），代理是**控访问且客户端拿不到真身**（AOP），适配器是**两侧接口不同要翻译**（slf4j 绑定）。代码结构 90% 相似，靠"客户端是否知情""能否嵌套"来区分。

**Q12：Spring 的 Bean 是单例，会有线程安全问题吗？**

会，而且这是最常见的事故源。Spring 只管"容器里一个实例"，不管"实例里字段是否被并发修改"。判据：Bean 是否**无状态**。成员变量只能是 final 的依赖注入对象；任何临时数据必须在方法内声明，或放 ThreadLocal（用完必须 remove）。

**Q13：Spring 事件有什么坑？**

三个：① 默认同步，监听器异常会连带回滚/中断主流程；② 在发布者事务内部执行，事务回滚了但短信已发 → 要用 `@TransactionalEventListener(AFTER_COMMIT)`；③ 加 `@Async` 后异常被线程池吞掉，必须配 `AsyncUncaughtExceptionHandler`，且需要自己做幂等。

**Q14：你见过哪些过度设计？**

（这题是送分的，一定要接。）典型三种：① 只有一个实现类的接口，且永远不会有第二个；② 为了"未来可能换数据库"写的 Repository 抽象层，结果 SQL 方言根本没被隔离住；③ 5 个字段的 DTO 用建造者 + 工厂 + 转换器三件套。判断标准统一：**抽象的成本是"每次改动多改 3 个文件"，收益是"未来少改 1 处"，如果未来不确定，这个交易就是亏的**。

---

## L11 3 道模拟面试题

### 第 1 题 · 重构设计（中等偏难）

**我们有个结算服务，`settle()` 方法 600 行，里面有 6 个渠道 × 5 个动作的分支，还有 3 段几乎一样的重试逻辑。你会怎么重构？**

**考察点**：能不能识别"两种不同性质的变化"（渠道维度 vs 通用横切逻辑），并给出分步落地方案，而不是一次性推倒重写。

**参考答法**：先分类，不要一上来就抽接口。

- **第一步，拆两类变化。** 渠道 × 动作的矩阵是"业务差异"，属策略维度；重试是"技术横切"，属模板方法或装饰器维度。这两类必须分开处理，混在一起抽会得到一个既像策略又像模板的怪物。
- **第二步，先处理重试（风险最低）。** 把 3 段重复的重试下沉到 `RetryTemplate` 或自写的 `executeWithRetry(supplier, policy)`。不改业务逻辑，只消除重复，可独立上线验证。
- **第三步，再抽策略。** 定义 `SettleStrategy`（`channel()` + `settle()` + `refund()` + `query()` + `reconcile()`），6 个实现类，用 Spring `Map` 注入。原 `settle()` 缩成约 10 行的路由方法。
- **第四步，兜底。** 加 `DefaultSettleStrategy` 抛明确异常，防新增渠道时忘实现导致 NPE；加启动时校验（6 个渠道是否注册齐）。

**追问链**：

1. "重构期间怎么保证不出错？" → 先补特征测试（characterization test），把现有 30 个分支的输入输出录成快照，重构后逐条比对；灰度按渠道切流，一个渠道一个渠道迁。
2. "600 行里有一些渠道的特例逻辑，抽不出来怎么办？" → 允许策略实现里保留私有方法，但禁止跨策略调用。如果两个渠道有 80% 相同，用 `AbstractSettleStrategy` 下沉公共部分（模板方法和策略叠加，能说出来加分）。
3. "如果只有 2 个渠道呢？" → 那就不重构。600 行的根源是"6×5 的矩阵 + 重复重试"，不是"方法长"。只有 2 个渠道且不增长时，抽策略的收益低于理解成本。

### 第 2 题 · 原理深挖（难）

**你说项目里用 Spring 事件做了解耦。下单成功后要发短信、加积分、推消息给物流，用 Spring 事件合适吗？事务回滚了怎么办？某个监听器特别慢怎么办？**

**考察点**：能不能从"模式能用"走到"模式在事务/分布式语境下的可靠性边界"。这题是筛高级岗的。

**参考答法**：分层回答，先说能用，再说不能用的边界。

- **事务回滚**：默认 `@EventListener` 在事务内部同步执行，后续步骤抛异常则事务回滚但短信已发出。改用 `@TransactionalEventListener(phase = AFTER_COMMIT)` 把时机挪到提交之后 —— 但代价是提交后异常无法回滚主事务，所以监听器必须**自己保证幂等 + 失败重试**。
- **慢监听器**：默认同步，一个监听器耗时 800ms 会把接口整体拖慢 800ms。解法是 `@Async` + 独立线程池（不能共用业务线程池，否则一个慢通知就打满主链路线程）。但 `@Async` 会让异常被吞，必须配 `AsyncUncaughtExceptionHandler`；线程池队列要有界 + 拒绝策略要明确（用 CallerRuns 反而会阻塞主线程）。
- **关键判断**：如果是"发短信、加积分、推物流"这种**丢了要赔钱/要客服介入**的场景，Spring 事件不够 —— 它是内存里的，进程重启就丢。这时应该用**本地消息表 + MQ**：业务和消息在同一本地事务里落库，再由定时任务/CDC 投递，靠 MQ 至少一次投递 + 消费端幂等保证最终一致。

**追问链**：

1. "本地消息表和事务消息（RocketMQ）怎么选？" → 事务消息需 MQ 支持半消息 + 回查，运维成本高但不用自己扫表；本地消息表通用、不绑 MQ，但要自己写补偿任务和清理。中小团队优先本地消息表。
2. "`@Async` 方法里调另一个 `@Async` 方法会怎样？" → 自调用不走代理，第二个 `@Async` 失效，仍在第一个线程里同步执行。这是 AOP 自调用通病，同类问题还有 `@Transactional` 自调用失效。
3. "监听器里再发布一个事件，会怎样？" → 默认同步，形成嵌套调用链；两个事件互相发布就是死循环，直到 StackOverflow。生产上建议事件只做"一跳"。

### 第 3 题 · 品味与边界（拉开差距）

**你怎么判断一段代码"设计得好"？如果给你一个 8000 行的 Service 类，你怎么决定要不要拆、怎么拆？**

**考察点**：有没有自己的判断体系。这题没有标准答案，但答得出来的人极少。

**参考答法**：先给判断标准，再给操作路径。

判断标准只看三条，按优先级：

1. **变更成本**：一个典型需求要改几个文件、几个类？一个字段改动要穿 6 层，说明分层错了，不是"设计严谨"。
2. **失败可见性**：出问题时能不能 5 分钟内定位到哪一层？链条太长导致日志无法串联，这个抽象已经在损害可维护性。
3. **新人上手时间**：熟悉业务但不熟悉代码的人，读懂主流程要多久？超过半天就是抽象过度的信号。

**8000 行怎么拆：不要按"技术分层"拆，要按"变化原因"拆。** 先把方法按业务动作列出来（可能 40 个），然后标两列：这个方法属于哪个业务概念？它和谁一起变？同一列聚到一起就是一个类。这叫"按变更原因聚合"，比按"工具类/帮助类"拆稳得多 —— 后者拆出来的往往是 `XxxUtils` 这种什么都往里塞的垃圾桶。

**拆的顺序也有讲究**：先拆**纯函数**（无状态、无 IO 的计算逻辑，风险最低、最好测），再拆**外部依赖**（把 RPC/DB 调用收口到 Repository/Gateway），最后才拆**流程编排**（这层最容易拆错，因为流程是业务核心，拆散了就看不懂）。

**追问链**：

1. "拆完类变多了，是不是更难懂？" → 会。所以要控制"入口数"：对外只暴露 1~2 个 public 方法，其他全部包私有。类多但入口少可接受；类多且每个都能被随便调，才是灾难。
2. "什么情况下你会选择不拆？" → 三种：马上要下线/替换的模块；业务已冻结、半年没改过的模块；没有测试覆盖兜底的模块（没测试的重构是赌博）。
3. "怎么说服团队接受这个拆法？" → 不讲"设计模式"，讲"上次那个 bug 我们查了 6 小时，就是因为这段逻辑分散在 4 个类里"。用历史故障成本说话，比讲 SOLID 有效一百倍。

---

## 本期收尾

设计模式的最高境界不是"会用 23 种"，而是**能对着一段代码说出"这里该抽"和"这里别动"**。前者靠经验，后者靠品味 —— 而品味，本质上是你交过的学费。

**下一期预告**：JVM 调优与性能压测（堆参数推导 / GC 日志解读 / 压测模型与瓶颈定位），把这些结构放回真实流量下检验。
