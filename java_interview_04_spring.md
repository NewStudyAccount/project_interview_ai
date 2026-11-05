# Java 面试深挖 04 · Spring 核心：refresh 十二步 / Bean 生命周期 / 三级缓存 / AOP / 事务

> 每 2 小时一期 · 由浅入深 · 代码 + 图 + 数量级
> 口径：Spring Framework 6.x / Spring Boot 3.x（JDK 17+）

---

## 本期地图

| 层 | 主题 | 难度 | 一句话结论 |
|---|---|---|---|
| L0 | 容器契约 | ★★☆ | Spring 不是「帮你 new 对象」，是「先画图纸，再照图纸生产，生产过程中允许你插队」 |
| L1 | refresh() 十二步 | ★★★ | 12 个方法里只有 4 个真正干活，其余 8 个是给你留的插槽 |
| L2 | Bean 生命周期九步 | ★★★ | 九步的意义在于：**扩展点位置决定你能改什么，改不了什么** |
| L3 | 循环依赖与三级缓存 | ★★★★ | 三级不是「多一层保险」，第三级的唯一存在理由是 **AOP 代理必须提前曝光** |
| L4 | AOP 代理选型 | ★★★ | JDK 代理靠接口，CGLIB 靠继承；Spring Boot 默认走 CGLIB，因为「业务类常常没有接口」 |
| L5 | 事务六种失效 | ★★★★ | 事务不是注解带来的，是**代理**带来的；代理没被经过，注解就是一行注释 |
| L6 | 高频问答 14 条 | ★★★ | —— |
| L7 | 三道模拟面试 | ★★★★ | —— |

**本期的暗线**：从 L0 到 L5 其实只在讲一件事 —— **谁在什么时候拿到了哪个对象**。循环依赖是「拿到的是半成品」，AOP 是「拿到的是替身」，事务失效是「根本没拿到替身，拿到的是本人」。把这句话记住，五个小节会自己串起来。

---

## L0 契约层：BeanDefinition 是图纸，Bean 是房子

### 一句话答案

Spring 的核心不是「IoC 帮你 new 对象」这种幼儿园说法，而是三件事拆开：

1. **BeanDefinition（图纸）**：描述「怎么造」——类名、作用域、是否懒加载、构造参数、初始化方法、依赖谁。它是个纯数据对象，此时**一个业务对象都还没 new**。
2. **BeanFactory（工厂 + 注册表）**：按图纸生产，并把成品放进单例池。核心方法只有一个 `getBean`。
3. **ApplicationContext（工厂 + 一整套企业设施）**：在 BeanFactory 外面套了事件、国际化、资源加载、Environment。

**面试官真正想听的**：`ApplicationContext` 和 `BeanFactory` 的区别不是「一个功能多一个功能少」，而是**实例化时机**。`BeanFactory` 是懒的，`getBean` 才创建；`ApplicationContext` 在 `refresh()` 的最后一步把所有非 lazy 单例**一次性全造出来**，代价是启动慢，收益是**错误提前暴露**——配置错了在启动时就炸，而不是在半夜三点第一次请求时炸。

### 容器里到底有几个 Map

`DefaultListableBeanFactory` 是整个 Spring 的心脏，剥掉所有包装，它就是几个 Map：

```java
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
        implements ConfigurableListableBeanFactory, BeanDefinitionRegistry {

    /** 图纸仓库：beanName -> 图纸。refresh 第 2、5 步往里塞 */
    private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);

    /** 按类型索引：类型 -> 候选 beanName 列表。@Autowired byType 靠它 */
    private final Map<Class<?>, String[]> allBeanNamesByType = new ConcurrentHashMap<>(64);

    // ↓ 以下三个在 DefaultSingletonBeanRegistry 里，就是所谓的「三级缓存」
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);          // 一级：成品
    private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);     // 二级：半成品
    private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);        // 三级：半成品的工厂

    /** 正在创建中的 beanName。只有在这里面的 bean 才会被允许「提前曝光」 */
    private final Set<String> singletonsCurrentlyInCreation =
            Collections.newSetFromMap(new ConcurrentHashMap<>(16));
}
```

注意最后那个 `Set`。它是循环依赖的**开关**：只有「正在创建中」的 Bean 才有资格被提前曝光，否则你拿到一个还没开始造的 Bean 就是见鬼。

### 数量级：一个中等规模 Boot 应用的启动账本

以一个 200 个 Bean 的服务（典型业务中台单体）为例，`refresh()` 的耗时分布大致是：

| 阶段 | 占比 | 典型耗时 | 说明 |
|---|---|---|---|
| 扫描 + 注册 BeanDefinition | ~30% | 600ms | 类路径扫描、注解解析、`@Configuration` 的 CGLIB 增强 |
| BeanPostProcessor 注册 | ~5% | 100ms | 注意：只注册，不执行 |
| **预实例化单例** | ~50% | 1000ms | 反射调构造器 + 属性注入 + 各种回调 |
| 事件 / 监听器 / 收尾 | ~15% | 300ms | `ContextRefreshedEvent`、Lifecycle 启动 |

**这张表的用法**：面试官问「启动慢怎么优化」，你不该答「少写点 Bean」这种废话，而应该答「先用 `ApplicationStartup` 打点定位是扫描慢还是实例化慢」——扫描慢就缩 `@ComponentScan` 范围、关掉不需要的自动配置；实例化慢就看是不是有 Bean 在构造函数里干了 IO（比如连数据库、拉配置）。

### 一段能跑的最小验证

```java
@Configuration
@ComponentScan("com.demo")
public class App {
    public static void main(String[] args) {
        // 用 AnnotationConfigApplicationContext 手动走一遍 refresh
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(App.class);
        // 此刻 beanDefinitionMap 还是空的，因为还没 refresh
        ctx.refresh();
        // 此刻所有单例都已创建完毕
        System.out.println(ctx.getBeanDefinitionCount());   // 图纸数量
        ctx.close();                                        // 触发销毁回调
    }
}
```

> **陷阱**：`ctx.register(App.class)` 之后不 `refresh()` 直接 `getBean`，在 `AnnotationConfigApplicationContext` 里会抛 `IllegalStateException: BeanFactory not initialized or already closed`。很多人背了「ApplicationContext 预实例化」，却不知道手动 new 出来的 Context 也必须显式 `refresh()`。

---

## L1 refresh() 十二步：只有 4 步在干活

### 一句话答案

`AbstractApplicationContext.refresh()` 是 12 个方法调用，可以粗暴分成三组：

- **真干活（4 个）**：第 2 步（读图纸）、第 5 步（改图纸）、第 6 步（装插件）、第 11 步（造对象）。
- **留插槽（5 个）**：第 1、3、4、9、12 步，全是 `protected` 空方法或半成品，给子类（Web 容器、Boot）覆写。
- **配基础设施（3 个）**：第 7、8、10 步，国际化、事件广播器、监听器。

### 十二步全景

```
refresh()  ──────────────────────────────────────────────────────────────
 ① prepareRefresh()              记启动时间 / 校验必需属性 / 初始化 PropertySource
 ② obtainFreshBeanFactory()      ★ 创建 DefaultListableBeanFactory，解析 XML/注解 → 填 beanDefinitionMap
 ③ prepareBeanFactory()          塞 ClassLoader / SpEL 解析器 / 3 个内置 Bean / Aware 处理器
 ④ postProcessBeanFactory()      [插槽] Web 容器在这里注册 request、session 作用域
 ⑤ invokeBeanFactoryPostProcessors()  ★★ 执行 BFPP：ConfigurationClassPostProcessor 扫 @ComponentScan
                                        → 大量 BeanDefinition 在这一步才被注册（第 2 步只注册了配置类本身）
 ⑥ registerBeanPostProcessors()  ★ 把 BPP 注册进列表（按 PriorityOrdered / Ordered / 无序 分三批）
 ⑦ initMessageSource()           国际化
 ⑧ initApplicationEventMulticaster()  事件广播器
 ⑨ onRefresh()                   [插槽] Boot 在这里启动 Tomcat / Jetty
 ⑩ registerListeners()           注册监听器到广播器
 ⑪ finishBeanFactoryInitialization()  ★★ 冻结配置 + preInstantiateSingletons()：造出所有非 lazy 单例
 ⑫ finishRefresh()               发布 ContextRefreshedEvent / 启动 Lifecycle / 清缓存
```

### 三个必须能说清的细节

**细节一：为什么第 2 步和第 5 步都叫「注册 BeanDefinition」，却不能合并？**

第 2 步只解析「配置源」本身——XML 里的 `<bean>`、或者 `@Configuration` 类自己。真正把 `@ComponentScan` 扫出来的几百个业务类变成 BeanDefinition 的，是第 5 步里的 `ConfigurationClassPostProcessor`（它本身是个 `BeanDefinitionRegistryPostProcessor`）。

所以顺序是：**先有图纸仓库 → 再让一个特殊的 Bean 去仓库里批量添图纸 → 最后才照图纸生产**。这也解释了为什么你在 `@Bean` 方法里拿不到 `Environment` 之外的东西——那时候 Bean 都还没造。

```java
// refresh 里的真实顺序（有删减，保留主干）
@Override
public void refresh() throws BeansException, IllegalStateException {
    synchronized (this.startupShutdownMonitor) {
        prepareRefresh();                                   // ①
        ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();  // ②
        prepareBeanFactory(beanFactory);                    // ③
        try {
            postProcessBeanFactory(beanFactory);            // ④
            invokeBeanFactoryPostProcessors(beanFactory);   // ⑤  ★ 改图纸
            registerBeanPostProcessors(beanFactory);        // ⑥  ★ 装插件
            initMessageSource();                            // ⑦
            initApplicationEventMulticaster();              // ⑧
            onRefresh();                                    // ⑨
            registerListeners();                            // ⑩
            finishBeanFactoryInitialization(beanFactory);   // ⑪  ★ 造对象
            finishRefresh();                                // ⑫
        } catch (BeansException ex) {
            destroyBeans();          // 失败要销毁已创建的，否则连接泄漏
            cancelRefresh(ex);
            throw ex;
        } finally {
            resetCommonCaches();     // 清反射/注解元数据缓存，防止类加载器泄漏
        }
    }
}
```

**细节二：`BeanFactoryPostProcessor` 和 `BeanPostProcessor` 差一个字，差一个世界。**

| 对比项 | BeanFactoryPostProcessor（BFPP） | BeanPostProcessor（BPP） |
|---|---|---|
| 作用对象 | **BeanDefinition（图纸）** | **Bean 实例（房子）** |
| 执行时机 | refresh 第 ⑤ 步，**任何普通 Bean 创建之前** | 每个 Bean 初始化前后（第 ⑪ 步内部） |
| 能否 `getBean` | **不能**（会提前触发实例化，Spring 会警告） | 能，但小心递归 |
| 典型实现 | `ConfigurationClassPostProcessor`、`PropertySourcesPlaceholderConfigurer` | `AutowiredAnnotationBeanPostProcessor`、`AbstractAutoProxyCreator` |
| 数量级 | 通常 3~8 个 | Boot 3 应用通常 20~40 个 |

**细节三：第 ⑥ 步为什么按 PriorityOrdered / Ordered / 无序分三批注册？**

因为 BPP 之间是有依赖的。`AutowiredAnnotationBeanPostProcessor` 必须早于业务 BPP，否则业务 BPP 拿到的对象属性还是 null。分批注册保证「实现了 `PriorityOrdered` 的一定排在只实现 `Ordered` 的前面，没实现接口的垫底」，这是 Spring 里唯一可靠的排序契约。

> **高频追问**：「`@Order` 注解对 BPP 生效吗？」
> 答：对 BPP **排序**不生效，只对 `Ordered` 接口或 `@Order` 标注在**类上**并配合 `OrderComparator` 时生效；BPP 的注册顺序看的是 `PriorityOrdered` / `Ordered` 接口。这个点答错很掉分。

---

## L2 Bean 生命周期九步：扩展点位置决定你能改什么

### 一句话答案

一个单例 Bean 从「图纸」变成「能用的对象」，经过九步。**九步的价值不在背诵，而在于知道每一步对应哪个扩展点**——面试官问「我想在属性注入完成后、初始化方法前做点事，用哪个接口」，你答 `BeanPostProcessor#postProcessBeforeInitialization`，这就是区分度。

### 九步流程

```
① 实例化前拦截   InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
                 └─ 返回非 null 则【短路】：直接当成品，后面的 ②~⑧ 全跳过（AOP 的 TargetSource 用法）
② 推断构造器 + 实例化   createBeanInstance → 反射调构造器 / Supplier / 工厂方法
                 └─ 此刻对象已在堆上，但所有字段是默认值（null / 0）
③ 属性填充       populateBean → @Autowired / @Value 在这里注入
                 └─ 循环依赖的「提前曝光」就发生在这一步之前
④ Aware 回调     BeanNameAware → BeanClassLoaderAware → BeanFactoryAware
⑤ 初始化前       BPP#postProcessBeforeInitialization   ← @PostConstruct 在这里被触发
⑥ 初始化         InitializingBean#afterPropertiesSet()
⑦ 自定义初始化   init-method / @Bean(initMethod=...)
⑧ 初始化后       BPP#postProcessAfterInitialization    ← ★ AOP 代理在这里生成
⑨ 使用 → 销毁    @PreDestroy → DisposableBean#destroy → destroy-method
```

### 三处最容易背错的顺序

**错误一：以为 `@PostConstruct` 早于属性注入。**

不可能。`@PostConstruct` 的语义就是「依赖都注入完了，你可以开始初始化了」，它在 ⑤ 步，属性注入在 ③ 步。

**错误二：以为 `afterPropertiesSet()` 早于 `@PostConstruct`。**

实际顺序是：`@PostConstruct` → `afterPropertiesSet()` → `init-method`。
原因很物理：`@PostConstruct` 是 `CommonAnnotationBeanPostProcessor`（一个 BPP）在 `postProcessBeforeInitialization` 里反射调用的，而 `afterPropertiesSet()` 是 `invokeInitMethods` 里调的，发生在**所有 BPP 的前置回调之后**。

```java
// AbstractAutowireCapableBeanFactory#initializeBean 的真实骨架
protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
    invokeAwareMethods(beanName, bean);                                    // ④

    Object wrappedBean = bean;
    if (mbd == null || !mbd.isSynthetic()) {
        wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);  // ⑤ @PostConstruct 在此
    }
    try {
        invokeInitMethods(beanName, wrappedBean, mbd);                     // ⑥⑦ afterPropertiesSet + init-method
    } catch (Throwable ex) {
        throw new BeanCreationException((mbd != null ? mbd.getResourceDescription() : null), beanName,
                "Invocation of init method failed", ex);
    }
    if (mbd == null || !mbd.isSynthetic()) {
        wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);   // ⑧ 代理在此诞生
    }
    return wrappedBean;
}

// invokeInitMethods 内部：先接口，后自定义
protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd) throws Throwable {
    boolean isInitializingBean = (bean instanceof InitializingBean);
    if (isInitializingBean && (mbd == null || !mbd.isExternallyManagedInitMethod("afterPropertiesSet"))) {
        ((InitializingBean) bean).afterPropertiesSet();                    // ⑥
    }
    if (mbd != null && bean.getClass() != NullBean.class) {
        String initMethodName = mbd.getInitMethodName();
        if (StringUtils.hasLength(initMethodName) && !(isInitializingBean && "afterPropertiesSet".equals(initMethodName))) {
            invokeCustomInitMethod(beanName, bean, mbd);                   // ⑦
        }
    }
}
```

**错误三：以为 AOP 代理在「创建时」生成。**

代理在 ⑧ 步，即**初始化之后**。这条事实是理解 L3 循环依赖的唯一钥匙——因为属性注入在 ③ 步，早于代理生成，所以如果 B 在 ③ 步要注入 A，此时 A 还是个**没有代理的裸对象**。三级缓存存在的全部意义就是解决这个时间差。

### 数量级：一个 Bean 要经过多少次回调

| 场景 | 回调次数 | 说明 |
|---|---|---|
| 最简 Bean（无注解、无 Aware） | 2 次 | 前后置 BPP 各一次，且大部分 BPP 直接 return |
| 带 `@Autowired` 的单例 | 5~8 次 | 属性注入的 BPP + `@PostConstruct` BPP + 代理判断 |
| 带 `@Transactional` 的 Service | 12~20 次 | 每个 BPP 都要过一遍，`AbstractAutoProxyCreator` 还要跑一遍 advisor 匹配 |
| 200 个 Bean 的应用 | 数千次 | 这就是「Bean 多了启动慢」的真实原因——不是 new 慢，是**回调次数线性膨胀** |

---

## L3 循环依赖与三级缓存：第三级到底在救谁

### 一句话答案

循环依赖能解决，靠的是**「不等成品，先给半成品」**：A 实例化完（字段还是 null）就把自己的一个 **ObjectFactory** 曝光出去，B 注入时通过这个工厂拿到 A 的早期引用，B 先完成，A 再回头注入 B。

**为什么必须三级，二级不够？**
因为如果 A 需要被 AOP 代理（有 `@Transactional`、`@Async`、自定义切面），B 注入的必须是**代理对象**，而不是裸对象。第三级的 `ObjectFactory` 是一个**延迟回调**，它触发 `getEarlyBeanReference`，让 `AbstractAutoProxyCreator` 在「属性填充之前」就把代理造出来并缓存。二级缓存只能存对象，存不了「还没决定要不要代理」这个状态。

### 三级缓存的物理结构

```
singletonObjects         (一级)  beanName → 完整成品（属性注入完 + 初始化完 + 代理已包好）
earlySingletonObjects    (二级)  beanName → 早期引用（实例化完，属性还没填，但可能已经是代理）
singletonFactories       (三级)  beanName → ObjectFactory<Object>  (一个 lambda，调用才产出早期引用)
singletonsCurrentlyInCreation     正在创建中的 beanName 集合（循环依赖的准入开关）
alreadyCreated / registeredSingletons   已完成创建的 beanName（用于销毁顺序、containsSingleton）
```

### A → B → A 的完整时序

```
┌────────────── getBean("a") ──────────────┐
│ ① 一级无 → 二级无 → 三级无（首次）        │
│ ② createBeanInstance(a)  → 裸 a（字段 null）│
│ ③ addSingletonFactory("a", () -> getEarlyBeanReference(a))   ← 三级缓存写入
│ ④ populateBean(a)：发现依赖 b             │
│      └─ getBean("b")                      │
│          ⑤ 创建裸 b                       │
│          ⑥ addSingletonFactory("b", ...)  │
│          ⑦ populateBean(b)：发现依赖 a    │
│               └─ getSingleton("a", true)  │
│                    ├ 一级：无             │
│                    ├ 二级：无             │
│                    └ 三级：命中！调用工厂 │
│                         → 得到 a 的早期引用（可能是代理）
│                         → 放入二级，删除三级
│          ⑧ b 完成 → 放入一级              │
│      ← 返回 b                             │
│ ⑨ populateBean(a) 注入 b                  │
│ ⑩ initializeBean(a) → 这里会再判一次要不要代理（已曝光则复用）
│ ⑪ a 完成 → 放入一级，删除二级             │
└───────────────────────────────────────────┘
```

对应源码（`DefaultSingletonBeanRegistry#getSingleton`）：

```java
protected Object getSingleton(String beanName, boolean allowEarlyReference) {
    Object singletonObject = this.singletonObjects.get(beanName);
    if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
        singletonObject = this.earlySingletonObjects.get(beanName);       // 二级
        if (singletonObject == null && allowEarlyReference) {
            synchronized (this.singletonObjects) {
                singletonObject = this.singletonObjects.get(beanName);
                if (singletonObject == null) {
                    singletonObject = this.earlySingletonObjects.get(beanName);
                    if (singletonObject == null) {
                        ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);  // 三级
                        if (singletonFactory != null) {
                            singletonObject = singletonFactory.getObject();   // ★ 此刻才决定是否代理
                            this.earlySingletonObjects.put(beanName, singletonObject);
                            this.singletonFactories.remove(beanName);        // 三级 → 二级，单向迁移
                        }
                    }
                }
            }
        }
    }
    return singletonObject;
}
```

**注意「三级 → 二级，单向迁移」这个细节**。如果你在面试里能说出「三级缓存的条目用完即删，迁移到二级，所以二级存的是**已经确定形态**的早期引用」，面试官会知道你读过源码，不是背博客。

### 第三级的核心：getEarlyBeanReference

```java
// AbstractAutowireCapableBeanFactory
protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
    Object exposedObject = bean;
    if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
        for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
            exposedObject = bp.getEarlyBeanReference(exposedObject, beanName);
        }
    }
    return exposedObject;
}

// AbstractAutoProxyCreator 的实现：提前生成代理并记一笔
@Override
public Object getEarlyBeanReference(Object bean, String beanName) {
    Object cacheKey = getCacheKey(bean.getClass(), beanName);
    this.earlyProxyReferences.put(cacheKey, bean);      // 标记「这个 bean 已经提前代理过了」
    return wrapIfNecessary(bean, beanName, cacheKey);   // 真的生成代理
}
```

然后到了 ⑧ 步 `postProcessAfterInitialization`，`AbstractAutoProxyCreator` 会先查 `earlyProxyReferences`：

```java
@Override
public Object postProcessAfterInitialization(@Nullable Object bean, String beanName) {
    if (bean != null) {
        Object cacheKey = getCacheKey(bean.getClass(), beanName);
        if (this.earlyProxyReferences.remove(cacheKey) != bean) {   // 没提前代理过，才在这里代理
            return wrapIfNecessary(bean, beanName, cacheKey);
        }
    }
    return bean;
}
```

**这段代码就是「为什么三级缓存不会重复生成两个代理对象」的答案**。少了这个 `remove` 判断，A 会被代理两次，B 持有的代理和一级缓存里的代理不是同一个实例，`==` 比较直接翻车。

### 三级缓存解决不了的三种循环

| 场景 | 为什么救不了 | 报错 |
|---|---|---|
| **构造器注入循环** | 对象还没 new 出来，没有东西可曝光 | `BeanCurrentlyInCreationException: Is there an unresolvable circular reference?` |
| **prototype 作用域循环** | 原型 Bean 不进单例池，没有缓存可用 | 同上 |
| **`@Async` / 自定义 BPP 产生的代理** | 该代理在 ⑧ 步才生成，`getEarlyBeanReference` 的默认实现返回原对象 | `Bean with name 'a' has been injected into other beans [...] in its raw version as part of a circular reference, but has eventually been wrapped` |

第三种是最有区分度的。原因：`AsyncAnnotationBeanPostProcessor` 继承的是普通 `BeanPostProcessor`，**不实现** `SmartInstantiationAwareBeanPostProcessor`，所以它的代理只能在 `postProcessAfterInitialization` 生成。B 注入到的是裸 A，最终一级缓存里是代理 A，Spring 检测到「曝光过的对象和最终对象不一致且有人依赖过它」，直接抛异常而不是让你带着 bug 上线。

```java
// 触发这个报错的真实检查（AbstractAutowireCapableBeanFactory#doCreateBean 尾部）
if (earlySingletonExposure) {
    Object earlySingletonReference = getSingleton(beanName, false);
    if (earlySingletonReference != null) {
        if (exposedObject == bean) {
            exposedObject = earlySingletonReference;
        } else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
            String[] dependentBeans = getDependentBeans(beanName);
            throw new BeanCurrentlyInCreationException(beanName,
                "Bean with name '" + beanName + "' has been injected into other beans [" +
                StringUtils.arrayToCommaDelimitedString(dependentBeans) +
                "] in its raw version as part of a circular reference, but has eventually been " +
                "wrapped. This means that said other beans do not use the final version of the " +
                "bean. This is often the result of over-eager type matching - consider using " +
                "'getBeanNamesForType' with the 'allowEagerInit' flag turned off, for example.");
        }
    }
}
```

### 数量级：循环依赖的修复成本

- 三级缓存带来的内存开销：每个**正在创建中**的 Bean 多一个 lambda 对象 + 一个 Map 条目。稳态下 `singletonFactories` 基本是**空的**（创建完就删），所以别担心内存。
- 真正的成本是**设计债**：一个 200 Bean 的项目里出现 5 组循环依赖，通常意味着分层被打穿了（Service 互相调、Controller 注 Service 又注 Controller）。Spring 能救你，但救不了你的架构。
- `spring.main.allow-circular-references=false`（Boot 2.6+ 默认）就是官方在说：**别再写循环依赖了**。

---

## L4 AOP 代理：JDK 靠接口，CGLIB 靠继承

### 一句话答案

Spring AOP 是**运行时代理**，不是字节码编织（那是 AspectJ）。代理只有两种实现：

- **JDK 动态代理**：目标类**必须实现接口**。生成的 `$Proxy0` 实现同一接口，方法调用转给 `InvocationHandler#invoke`，靠**反射**调目标方法。
- **CGLIB**：不需要接口。用 ASM 生成目标类的**子类**，覆写非 final 方法，方法体里直接 `super.xxx()`，并通过 `FastClass` 用**索引**代替反射。

Spring Boot 2.x 起 `spring.aop.proxy-target-class` 默认 `true`，即**默认 CGLIB**。理由很现实：业务 Service 常常直接实现类而不写接口，用 JDK 代理会得到一个「没有实现接口就无法代理」的报错，用户体验极差。

### 两种代理的结构对比

```
【JDK 动态代理】
  interface UserService
        △                    △
        │ implements         │ implements
  UserServiceImpl       $Proxy0（运行时生成，实现同一接口）
                              │ 持有
                              ▼
                      InvocationHandler
                              │ 反射调用 method.invoke(target, args)
                              ▼
                        UserServiceImpl

  限制：目标类必须有接口；只能代理接口里声明的方法
  优势：代理类和目标类是「兄弟」，不侵入继承链


【CGLIB】
  UserServiceImpl
        △
        │ extends
  UserServiceImpl$$EnhancerBySpringCGLIB$$a1b2c3（子类，覆写非 final 方法）
        │ 方法体内
        ▼
  MethodInterceptor.intercept() → methodProxy.invokeSuper(obj, args)
                              → 直接调 super，走 FastClass 索引，避免反射

  限制：不能代理 final 类 / final 方法 / private 方法 / static 方法
  优势：不需要接口；包级、protected 方法也能拦
```

### 选型表（面试可以直接背这张）

| 维度 | JDK 动态代理 | CGLIB |
|---|---|---|
| 前提 | 目标类实现接口 | 无（但不能是 final 类） |
| 生成方式 | `Proxy.newProxyInstance` | ASM 生成子类字节码 |
| 调用方式 | 反射 `Method.invoke` | FastClass 索引 + `super` 调用 |
| 能否代理非 public 方法 | 否（接口方法都是 public） | 能（protected / 包级） |
| 首次创建成本 | 低 | 较高（生成并加载类） |
| 单次调用成本 | 较高（反射，但有 inflation 优化） | 较低 |
| Spring 默认 | 否 | **是（Boot 2.x+）** |
| 强制指定 | `@EnableAspectJAutoProxy(proxyTargetClass=false)` | `proxyTargetClass=true` |

**一个必须说清的点**：`AopContext.currentProxy()` 只有 JDK 代理模式下才一定需要，因为它依赖 `ThreadLocal` 存当前代理；CGLIB 下同样需要 `exposeProxy=true`。别把这两件事混成一件。

### 自调用：AOP 世界的头号坑

```java
@Service
public class OrderService {

    @Transactional
    public void createOrder(Order o) {
        // ... 下单
        this.calcPrice(o);        // ★ 这里是 this 调用，不经过代理！
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void calcPrice(Order o) {
        // 期望：新事务；实际：跑在 createOrder 的事务里，甚至完全没有事务
    }
}
```

**为什么失效**：外部调用 `orderService.createOrder()` 时，调用方持有的是**代理**，代理开启事务后调 `target.createOrder()`。进入目标对象后，`this` 指向**目标对象本身**，不是代理，所以 `this.calcPrice()` 就是一次普通的 Java 方法调用，切面根本没机会插入。

```
外部调用者 ──→ [代理] ──(开启事务)──→ [目标对象].createOrder()
                                          │
                                          └─ this.calcPrice()  ← 直接进目标对象，绕过代理
```

四种修法，按推荐度排序：

```java
// ① 拆到另一个 Bean（最干净，架构上也更合理）
@Service
public class PriceService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void calcPrice(Order o) { /* ... */ }
}

// ② 注入自身（注意用 @Lazy 打破构造器循环）
@Service
public class OrderService {
    @Lazy @Autowired private OrderService self;
    public void createOrder(Order o) { self.calcPrice(o); }
}

// ③ 暴露代理，从 AopContext 取（需 @EnableAspectJAutoProxy(exposeProxy = true)）
@EnableAspectJAutoProxy(exposeProxy = true)
public class AopConfig {}

public void createOrder(Order o) {
    ((OrderService) AopContext.currentProxy()).calcPrice(o);
}
// 坑：exposeProxy 有 ThreadLocal 开销；异步线程里取不到

// ④ 干脆用编程式事务（最不容易错）
public void createOrder(Order o) {
    transactionTemplate.execute(status -> { /* ... */ return null; });
}
```

### 数量级：代理到底慢多少

这是我最喜欢用来打「AOP 影响性能」这个说法的数据（量级为常见 JVM 上的典型值，具体随 JDK 版本浮动）：

| 操作 | 耗时量级 | 相对倍数 |
|---|---|---|
| 直接方法调用 | ~2 ns | 1× |
| CGLIB 代理调用 | ~5~15 ns | 3~7× |
| JDK 代理调用（预热后） | ~10~30 ns | 5~15× |
| 一次本地 Redis GET | ~100,000 ns（0.1ms） | 50,000× |
| 一次 MySQL 主键查询 | ~500,000 ns（0.5ms） | 250,000× |

结论很清楚：**代理的相对开销是 3~15 倍，但绝对开销是纳秒级**。拿「AOP 慢」当不用 AOP 的理由，就像嫌鞋带太紧不肯出门——真正的开销在 IO，不在这几纳秒。这个数字对比在面试里非常加分，因为它证明你会**分数量级**而不是背结论。

---

## L5 @Transactional：六种失效，一条原理

### 一句话答案

`@Transactional` **本身不开启任何事务**。它只是往 `BeanDefinition` 上挂了一个「属性」，然后由 `InfrastructureAdvisorAutoProxyCreator` 在 ⑧ 步生成代理，由 `TransactionInterceptor` 在方法调用前后开/提交/回滚。所以：

> **没有经过代理的调用 = 没有事务。**
> 所有失效场景，都可以归结为「代理没被经过」或「异常没被看见」。

### 原理链路（一句话串起来）

```
@EnableTransactionManagement
   └─ 导入 TransactionManagementConfigurationSelector
        └─ ProxyTransactionManagementConfiguration 注册两样东西
             ① BeanFactoryTransactionAttributeSourceAdvisor（切面）
                  ├─ Pointcut：TransactionAttributeSourcePointcut（判断方法/类上有没有 @Transactional）
                  └─ Advice：TransactionInterceptor（MethodInterceptor）
             ② InfrastructureAdvisorAutoProxyCreator（BPP，负责生成代理）

调用时：代理 → TransactionInterceptor.invoke → invokeWithinTransaction
        ├─ createTransactionIfNecessary   → 拿连接、setAutoCommit(false)、绑定到 ThreadLocal
        ├─ invocation.proceed()           → 真正的业务方法
        ├─ commitTransactionAfterReturning
        └─ completeTransactionAfterThrowing → rollbackOn(ex) 判断是否回滚
```

```java
// TransactionAspectSupport#invokeWithinTransaction 主干
protected Object invokeWithinTransaction(Method method, @Nullable Class<?> targetClass,
        final InvocationCallback invocation) throws Throwable {

    TransactionAttributeSource tas = getTransactionAttributeSource();
    final TransactionAttribute txAttr = (tas != null ? tas.getTransactionAttribute(method, targetClass) : null);
    final TransactionManager tm = determineTransactionManager(txAttr);
    // ...
    TransactionInfo txInfo = createTransactionIfNecessary(tm, txAttr, joinpointIdentification);
    Object retVal;
    try {
        retVal = invocation.proceedWithInvocation();       // 业务方法
    } catch (Throwable ex) {
        completeTransactionAfterThrowing(txInfo, ex);      // ★ 这里判断回滚
        throw ex;
    } finally {
        cleanupTransactionInfo(txInfo);
    }
    commitTransactionAfterReturning(txInfo);
    return retVal;
}
```

### 六种失效（附最小复现代码）

**① 自调用 / 同类内部调用**

```java
@Service
public class UserService {
    @Transactional
    public void a() { this.b(); }        // ✗ b 的事务不生效
    @Transactional(propagation = REQUIRES_NEW)
    public void b() { /* ... */ }
}
```

**② 方法不可见（private）**

```java
@Service
public class UserService {
    @Transactional
    private void doSave() { /* ... */ }   // ✗ Spring 6 之前连 protected 都不行
}
```

Spring Framework 6.0 起把「只允许 public」放宽到「非 private 方法」，但 **private 永远不行**：CGLIB 覆写不了 private 方法，代理链根本不经过它。这个版本差异是现在的加分点。

**③ 异常被吞 / 抛受检异常**

```java
@Transactional
public void a() {
    try {
        int i = 1 / 0;
    } catch (Exception e) {
        log.error("忽略", e);              // ✗ 异常没抛出去，Spring 认为一切正常 → 提交
    }
}

@Transactional
public void b() throws IOException {
    throw new IOException();               // ✗ 默认只回滚 RuntimeException 和 Error
}

@Transactional(rollbackFor = Exception.class)   // ✓ 显式指定
public void c() throws IOException { throw new IOException(); }
```

默认规则来自 `DefaultTransactionAttribute#rollbackOn`：`RuntimeException` 或 `Error` 才回滚。**为什么这么设计**：受检异常在 Java 里代表「可预期的业务分支」，框架不敢替你决定回滚。

**④ 类没被 Spring 管理**

```java
UserService us = new UserService();   // ✗ 裸对象，没有代理
us.save();
```

同理：`@Controller` 里 `new` 出来的 Service、工具类里静态持有的 Service、`@Bean` 方法里返回的对象被再次 `new`。

**⑤ 存储引擎 / 数据源不支持事务**

MyISAM 表、某些 NoSQL、部分分库分表中间件的「非事务路由」。代码没问题，底层不认。

**⑥ 跨线程 / 异步调用**

```java
@Transactional
public void a() {
    new Thread(() -> b()).start();      // ✗ 事务绑定在 ThreadLocal，子线程拿不到
}

@Async
public void b() { /* 独立线程，没有事务上下文 */ }
```

事务上下文存在 `TransactionSynchronizationManager` 的 `ThreadLocal` 里（`resources`、`synchronizations`），线程一换就断。这也是 `@Async` + `@Transactional` 组合经常出问题的根因。

### 补充：传播行为的「连接数账本」（数形结合）

七种传播行为里，只有三个会决定**物理连接数**：

| 传播行为 | 连接数 | 外层回滚 → 内层 | 内层回滚 → 外层 | 典型用途 |
|---|---|---|---|---|
| `REQUIRED`（默认） | **1** | 一起回滚 | 外层被标记 rollback-only，提交时抛 `UnexpectedRollbackException` | 90% 的场景 |
| `REQUIRES_NEW` | **2** | 内层已独立提交，不受影响 | 不影响外层 | 记日志、发消息、幂等表 |
| `NESTED` | **1 + savepoint** | 一起回滚 | 回滚到 savepoint，外层可继续 | 批量导入「部分失败不影响整体」 |

**这张表能算出一道真实事故题**：

> 连接池 `maximumPoolSize = 10`，10 个并发请求，每个请求在外层 `REQUIRED` 事务里调一个 `REQUIRES_NEW` 的方法。

需要的连接数是 **10 × 2 = 20 > 10**。而 `REQUIRES_NEW` 会**挂起**外层事务（连接不释放，只是解绑），于是 10 个线程各占 1 个连接等第 2 个连接 → 全部阻塞 → 默认 30s 后 `Connection is not available, request timed out`。

**这不是 Spring 的 bug，是配置与传播行为的乘法**。面试里能把这道题算出 20 这个数字的人，比背七种传播行为定义的人值钱得多。

---

## L6 高频问答 14 条

**Q1. BeanFactory 和 ApplicationContext 的区别？**
功能上：ApplicationContext 继承 BeanFactory，额外提供事件发布、国际化、资源加载、Environment、自动注册 BPP/BFPP。行为上：BeanFactory 懒加载，ApplicationContext 启动时 `preInstantiateSingletons()` 预实例化所有非 lazy 单例。实现上：ApplicationContext 内部**委托**一个 `DefaultListableBeanFactory`，不是继承它的实现。

**Q2. `@Autowired` 和 `@Resource` 的区别？**
`@Autowired`：Spring 的，`byType` 优先，找不到唯一类型再按名字，`required=true` 可改；配合 `@Qualifier` 精确指定。`@Resource`：JSR-250 标准，`byName` 优先（字段名），再 `byType`；不支持 `required`。构造器注入只能用 `@Autowired`（或单构造器省略注解）。

**Q3. 构造器注入和字段注入怎么选？**
构造器注入：依赖不可变（`final`）、对象创建即完整、便于单测、循环依赖会直接暴露（这是优点）。字段注入：写法短，但隐藏依赖、不能 `final`、单测必须启动容器或用反射。**官方推荐构造器注入**，Spring 团队从 4.3 起就在文档里明确说了。

**Q4. `@Component`、`@Service`、`@Repository`、`@Controller` 有什么区别？**
语义上没区别，都是 `@Component` 的派生（元注解）。实际差异只有一处：`@Repository` 会被 `PersistenceExceptionTranslationPostProcessor` 增强，把厂商异常翻译成 `DataAccessException`。`@Controller` 是 `@RequestMapping` 处理器的识别标记。

**Q5. 单例 Bean 是线程安全的吗？**
不是。Spring 只保证**创建过程**线程安全（`singletonObjects` 是 `ConcurrentHashMap` + 双重检查），不保证**使用过程**线程安全。单例 Bean 里的可变成员变量就是共享状态，需要自己加锁或用无状态设计。这是最高频的送分题，也是最容易被反问「那 `@Scope("prototype")` 能解决吗」的题——不能，prototype 只是每次注入新对象，如果它被注入到单例里，依然只有一个实例。

**Q6. Bean 的作用域有哪些？**
`singleton`（默认）、`prototype`、`request`、`session`、`application`、`websocket`。后四种需要 Web 环境，靠 `RequestContextHolder` + `ThreadLocal` 实现。**坑**：单例注入 prototype 只会注入一次，要用 `@Lookup` 或 `ObjectProvider` 才能每次拿到新的。

**Q7. 三级缓存为什么不能只有两级？**
二级缓存只能存「已确定形态」的对象，而 AOP 代理要不要生成、生成成什么样，取决于 `getEarlyBeanReference` 的回调。三级缓存存的是 `ObjectFactory`，把「要不要代理」这个决策**推迟到真正有人来取的时候**。如果只有两级，早期引用只能是裸对象，B 注入裸 A、一级缓存里是代理 A，同一个 bean 出现两个实例，`==` 判断、`@Transactional` 全部失效。更关键的是：**不是所有 Bean 都需要提前代理**，用工厂可以实现「按需代理」，不给没有循环依赖的 Bean 增加开销。

**Q8. 构造器循环依赖为什么解决不了？能怎么绕？**
因为曝光的前提是「对象已经 new 出来」，构造器注入要求参数先就绪，形成死锁式等待。绕法：改用字段/setter 注入；用 `@Lazy` 在其中一个参数上生成懒代理；重构拆层（正确做法）。

**Q9. `BeanPostProcessor` 里能 `getBean` 吗？**
能，但极度危险。BPP 在 `registerBeanPostProcessors` 之后才生效，此时 `getBean` 会触发目标 Bean 的完整创建流程，而创建流程又要过 BPP——如果这个 Bean 依赖了当前 BPP 正在处理的东西，就是无限递归或 `BeanCurrentlyInCreationException`。正确做法是用 `ObjectProvider` 延迟获取，或在 `SmartInitializingSingleton#afterSingletonsInstantiated` 里做。

**Q10. AOP 为什么代理不了 `final` 方法？**
CGLIB 靠生成子类覆写方法，`final` 方法不能被覆写，代理类里根本不存在这个方法，调用直接落到父类实现，切面无从插入。同理 `private`（子类看不见）、`static`（编译期绑定）。

**Q11. `@Transactional` 加在类上和加在方法上，哪个优先？**
方法级优先。`AbstractFallbackTransactionAttributeSource` 的查找顺序是「方法 → 类」，找到即返回。类级相当于给所有方法一个默认值。

**Q12. 事务里调用了远程接口 / 发 MQ，怎么保证一致性？**
事务内做 IO 是反模式：连接被占住、超时拖长事务、回滚了但消息已发出。正确做法是**事务提交后再执行**：`TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { afterCommit() {...} })`，或本地消息表 + 定时补偿。别在 `@Transactional` 方法里直接 `mq.send()`。

**Q13. `@Async` 和 `@Transactional` 能同时用在一个方法上吗？**
能，但顺序很微妙。两个注解各自对应一个 BPP：`AsyncAnnotationBeanPostProcessor` 在 ⑧ 步生成异步代理，`AbstractAutoProxyCreator` 也在这附近生成事务代理。谁先谁后取决于 BPP 的 `Ordered` 值，结果是「异步代理包事务代理」或反过来。实践上更稳的做法是拆成两个方法：外层 `@Async` 提交任务，内层 `@Transactional` 执行。

**Q14. 怎么排查「事务没生效」？**
四步：① 确认类被 Spring 管理（`ctx.getBean(X.class).getClass()` 打印出来是不是 `$$EnhancerBySpringCGLIB` 或 `$Proxy`）；② 确认调用方持有的是代理（打断点看 `this` 的运行时类型）；③ 打开 `logging.level.org.springframework.transaction=TRACE` 看 `Creating new transaction` 有没有打印；④ 检查异常类型与 `rollbackFor`。**第 ② 步能省掉 80% 的排查时间**。

---

## L7 三道模拟面试题

### 题一：refresh 流程与扩展点选型

> **面试官**：「说说 Spring 启动都做了什么。如果我想在所有 Bean 实例化之前，动态往容器里注册一批 Bean，应该用哪个扩展点？为什么不用另一个？」

**优秀回答的骨架**：

1. 先给结构：12 步，四步真干活（② 读图纸、⑤ 改图纸、⑥ 装插件、⑪ 造对象），其余是插槽和基础设施。
2. 强调 ② 和 ⑤ 的差异：② 只注册配置类本身，⑤ 里的 `ConfigurationClassPostProcessor` 才扫 `@ComponentScan`、解析 `@Import`、处理 `@Bean`。
3. 选型：用 `BeanDefinitionRegistryPostProcessor`（BFPP 的子接口），在 `postProcessBeanDefinitionRegistry` 里调 `registry.registerBeanDefinition(...)`。它比普通 BFPP 更早执行（`invokeBeanFactoryPostProcessors` 里先处理 `BeanDefinitionRegistryPostProcessor`，且按 `PriorityOrdered` → `Ordered` → 其余 三批）。
4. 为什么不用 `BeanPostProcessor`：BPP 处理的是**实例**，那时所有 BeanDefinition 已经定型，你注册的新图纸永远不会被 `preInstantiateSingletons` 之外的路径自动实例化；而且 BPP 在 ⑥ 步才注册，时间上已经晚了。
5. 加分：提到 `ImportBeanDefinitionRegistrar`——这是写 starter 时的标准姿势，`@EnableXxx` 注解内部 `@Import` 一个 Registrar，比实现 BFPP 更符合 Spring 的分层。

**追问预判**：
- 「`BeanDefinitionRegistryPostProcessor` 里能 `getBean` 吗？」→ 几乎不能，会提前触发实例化，Spring 启动日志会警告 `Bean 'x' of type [...] is not eligible for getting processed by all BeanPostProcessors`。
- 「动态注册的 Bean 支持 `@Autowired` 吗？」→ 支持，属性填充在 ⑪ 步，晚于注册。

### 题二：循环依赖 + @Async

> **面试官**：「A 依赖 B、B 依赖 A，能启动吗？如果 A 上加了 `@Async` 呢？」

**优秀回答的骨架**：

1. 第一问直接过：能，靠三级缓存，条件是**单例 + setter/字段注入**。
2. 讲时序：实例化 A → 写三级缓存 → 填充属性触发 B → B 填充属性时 `getSingleton("a", true)` 命中三级 → 调 `getEarlyBeanReference` → 得到早期引用 → B 完成 → A 完成。
3. 第二问是分水岭：**不能**。`@Async` 的代理由 `AsyncAnnotationBeanPostProcessor` 在 `postProcessAfterInitialization` 生成，它不实现 `SmartInstantiationAwareBeanPostProcessor`，所以 `getEarlyBeanReference` 返回的是原对象。B 拿到裸 A，最终一级缓存里是代理 A，Spring 在 `doCreateBean` 尾部检测到「曝光对象 ≠ 最终对象且有依赖者」，抛 `BeanCurrentlyInCreationException`，报错信息里带 `in its raw version as part of a circular reference, but has eventually been wrapped`。
4. 修复：`@Lazy` 打破循环 / 抽第三个 Bean / 把 `@Async` 挪到独立组件 / `spring.main.allow-circular-references` 不是解法只是掩盖。
5. 拔高：说出「三级缓存不是银弹，它只解决**代理时机早于属性填充**这一类问题」，并指出 `@Cacheable` 同理（它也是普通 BPP）。

**失分点**：只答「三级缓存能解决」不看注解；或者答「加 `@Lazy` 就行」但说不出为什么（`@Lazy` 注入的是代理，代理在被调用时才 `getBean`，此时循环已解开）。

### 题三：线上事务事故分析

> **面试官**：「生产环境报 `Connection is not available, request timed out after 30000ms`，连接池 10 个，QPS 不高，你怎么查？」

**优秀回答的骨架**：

1. **先算账，别先猜**：连接池 10，超时 30s，说明有线程长期占着连接不放。QPS 不高还打满，只有两种可能——连接泄漏，或**传播行为导致的连接翻倍**（`REQUIRES_NEW` / `NESTED` 会挂起外层而不释放连接）。
2. 给公式：`需要连接数 = 并发事务数 × 事务嵌套层数`（`REQUIRED` 不增加，`REQUIRES_NEW` 每层 +1）。10 并发 × 2 层 = 20 > 10。
3. 排查路径：`SHOW PROCESSLIST` 看是不是一堆 `Sleep` 的长事务；开 `spring.jpa.open-in-view=false`（Open Session In View 会让连接在**整个请求**期间被持有，这是最常见的隐形元凶）；看 `TransactionSynchronizationManager` 的 `resources` 是否被正确清理。
4. 修复：把 `REQUIRES_NEW` 改成 `REQUIRED`（如果业务允许）；把发 MQ / 调远程移到 `afterCommit`；调大池子只是延后爆炸。
5. 顺带补一刀：`@Transactional` + `@Async` 组合、事务里做 HTTP 调用、`open-in-view=true` 是三大长事务元凶。

**失分点**：一上来就「调大 `maximumPoolSize`」；或者只答「有慢 SQL」，却解释不了「QPS 不高为什么也会打满」。

---

## 本轮自测清单

合上页面，能不看稿讲出来才算过。

- [ ] **L0** 画出 `beanDefinitionMap` / 一级 / 二级 / 三级缓存 / `singletonsCurrentlyInCreation` 五个结构，说清各自存什么。
- [ ] **L0** 解释为什么 ApplicationContext 启动慢但错误暴露早。
- [ ] **L1** 默写 refresh 十二步，标出哪 4 步真干活。
- [ ] **L1** 说清 ② 和 ⑤ 都注册 BeanDefinition 却不合并的原因。
- [ ] **L1** 用一张表对比 BFPP 和 BPP（对象、时机、能否 getBean、典型实现）。
- [ ] **L2** 默写 Bean 生命周期九步，标出 `@PostConstruct`、`afterPropertiesSet`、`init-method`、AOP 代理各自的位置。
- [ ] **L2** 解释「AOP 代理在初始化后生成」如何导致循环依赖需要三级缓存。
- [ ] **L3** 画出 A→B→A 的时序，说清三级 → 二级的单向迁移。
- [ ] **L3** 说出三级缓存救不了的三种循环，以及报错信息的关键词。
- [ ] **L4** 对比 JDK 代理与 CGLIB 的六个维度，说清 Boot 默认选 CGLIB 的原因。
- [ ] **L4** 用 ns / ms 的数量级反驳「AOP 影响性能」。
- [ ] **L5** 列出六种事务失效，每种给出最小复现代码。
- [ ] **L5** 算出「池 10 + 10 并发 + REQUIRES_NEW = 需要 20 个连接」。
- [ ] **L7** 完整讲一遍「循环依赖 + @Async」那道题，包括报错原文关键词。

---

## 下一轮预告

**第 05 期：MySQL 索引与事务隔离** —— 从 B+ 树的三层高度讲起，算清「一张 2000 万行的表为什么是 3 次 IO」；再讲清最左前缀、索引下推、覆盖索引的物理含义；最后落到 `MVCC` 的 `ReadView` 四字段与「RR 到底能不能防幻读」这个经典争论。

**本期与下期的接点**：`@Transactional` 的隔离级别参数最终是发给数据库的 `SET TRANSACTION ISOLATION LEVEL`，Spring 只是传话筒；而 `rollbackFor` 与 MySQL 的隐式提交（DDL）之间的冲突，是下一期要拆的第一个坑。

---

> 口径说明：Spring Framework 6.x / Boot 3.x；`@Transactional` 非 public 方法支持从 6.0 开始（private 始终不支持）；三级缓存对所有 Spring 5.x / 6.x 一致。
