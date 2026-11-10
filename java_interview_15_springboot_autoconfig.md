# Java 面试深水区 15 · Spring Boot 自动配置与启动流程

> "Spring Boot 为什么能自动配置？"——标准背法是："因为它有 `@EnableAutoConfiguration`，启动时会去读 `META-INF/spring.factories`。"
>
> 这句话在 2019 年是满分答案，现在是**错的**。Spring Boot 3.0 已经把 `spring.factories` 里的自动配置条目全部删掉了，改成一个名字长到看不全的 `AutoConfiguration.imports`。你背的那句口诀，指向一个已经不存在的文件。
>
> 更尴尬的是后半段：就算文件找对了，153 个候选配置类也不是全都生效——中间要过一道 ASM 预过滤（连类都不加载）、一道条件装配、一道排序。真正决定"加了 starter 就自动生效"的，是这条六阶段的流水线，不是某一个注解。

**本期结构**：13 层递进 · 10 张原理图 · 12 段代码 · 14 条高频问答 · 3 道模拟面试

**版本口径**：以 Spring Boot 3.4.x 为主线（本地 `spring-boot-autoconfigure-3.4.4.jar` 实测 153 条自动配置），关键处标注 2.7 / 3.0 / 4.0 的差异。

---

## L0 先把账算清：Boot 到底替你省了什么

Spring Boot 不是一个新框架。它一行新的 IoC 能力都没有，全部能力来自 Spring Framework。它做的是**三个工程约定**：

| 承诺 | 实现手段 | 你少写的东西 |
|---|---|---|
| 依赖不用写版本 | starter = 一组库 + 一套经过验证的版本组合 | 30 行 `dependencyManagement`、无数个版本冲突排查 |
| 容器不用手装配 | 自动配置 = 基于 classpath 的**条件装配** | 每个中间件一份 `@Configuration` |
| 部署不用装容器 | 内嵌 Tomcat/Jetty/Netty，`java -jar` 就是服务 | war 包、外部容器、`server.xml` |

所以"Spring Boot 原理"这道题，本质是问两件事：**启动时按什么顺序把东西装起来**，以及**装不装凭什么是条件说了算**。

### 启动是两张网，不是一条线

绝大多数人答这题时会混着讲，结果听起来像一团麻。正确的切法是两张网：

```
外层网 · SpringApplication.run()  ——  进程级引导（11 步）
   ①推断应用类型 → ②加载 Initializer/Listener → ③推断主类
   → ④启动监听器 → ⑤准备 Environment → ⑥打印 Banner
   → ⑦创建 ApplicationContext → ⑧prepareContext
   → ⑨refreshContext ───────────┐
   → ⑩afterRefresh              │
   → ⑪started + Runner + ready  │
                                │
内层网 · AbstractApplicationContext.refresh()  —— 容器级刷新（12 步）
   ①prepareRefresh ②obtainFreshBeanFactory ③prepareBeanFactory
   ④postProcessBeanFactory ⑤invokeBeanFactoryPostProcessors ← 自动配置在这
   ⑥registerBeanPostProcessors ⑦initMessageSource ⑧initApplicationEventMulticaster
   ⑨onRefresh ← Tomcat 在这被 new 出来
   ⑩registerListeners ⑪finishBeanFactoryInitialization ← 单例在这 new
   ⑫finishRefresh ← Tomcat 在这才 start()
```

**这个嵌套关系是必答点**：`run()` 的第 ⑨ 步进去，就是 `refresh()` 的 12 步；`refresh()` 跑完返回，`run()` 继续走第 ⑩ 步。面试官问"Bean 什么时候创建"，答案在 ⑪；问"Tomcat 什么时候监听端口"，答案在 ⑫——**不是 ⑨**。这个错位每年都要坑一批人。

### 一次典型启动的钱花在哪

一个 40 个 starter、600 个 Bean 的中型服务，冷启动约 2.4 秒，账单大致是：

| 阶段 | 耗时 | 占比 | 主要成本 |
|---|---|---|---|
| JVM 启动 + 类加载 | 620ms | 26% | 加载 1.2 万个类，其中 Spring 体系约 8000 个 |
| 自动配置解析 + 条件判定 | 120ms | 5% | 读 153 条候选、ASM 解析注解元数据 |
| 单例实例化 + 依赖注入 | 560ms | 23% | `finishBeanFactoryInitialization`，反射 + 代理生成 |
| AOP 代理 + 数据源 + 连接池 | 200ms | 8% | CGLIB 生成、HikariCP 建连 |
| 内嵌 Tomcat 启动 | 210ms | 9% | 端口绑定 + Servlet 容器初始化 |
| Runner + 事件 + 其他 | 90ms | 4% | `@PostConstruct`、`CommandLineRunner` |

注意一个反直觉的数字：**自动配置的"解析"只占 5%**，真正贵的是单例实例化和类加载。所以"启动慢"这件事，90% 的情况下优化方向不是"少几个自动配置类"，而是"少几个 Bean"和"少几个 starter"。这一点在 L7 展开。

---

## L1 `@SpringBootApplication`：三个注解各干各的，别混着说

这个复合注解拆开是三个，职责完全不重叠：

```java
@SpringBootConfiguration      // 本质就是 @Configuration，允许本类写 @Bean
@EnableAutoConfiguration      // 本质是 @Import(AutoConfigurationImportSelector.class)
@ComponentScan(               // 从主类所在包开始，向下扫
    excludeFilters = {
        @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
    }
)
public @interface SpringBootApplication {
    boolean proxyBeanMethods() default false;   // 2.2 起默认 false
}
```

三个注解，三条独立的链路：

- `@SpringBootConfiguration`：把主类变成一个配置类（`@Configuration` 的语义 + 一个可被测试定位的标记）。
- `@EnableAutoConfiguration`：通过 `@Import` 引入 `AutoConfigurationImportSelector`，这是**自动配置唯一的入口**，L4 整节都在讲它。
- `@ComponentScan`：**只扫主类所在包及其子包**。这一条是"Bean 找不到"的第一大原因。

### 扫描范围是包，不是模块

```
com.example
├── DemoApplication.java        ← @SpringBootApplication 在这里
│   └── 扫描范围 = com.example 及以下全部
├── order/         ✓ 被扫到
├── user/          ✓ 被扫到
└── ...
com.other
└── pay/           ✗ 永远不会被扫到（哪怕在同一个 jar 里）
```

三种修正方式，面试里说出第二种就算合格：

```java
// 1. 扩大扫描范围（不推荐，容易扫进测试类和不该装配的组件）
@SpringBootApplication(scanBasePackages = {"com.example", "com.other"})

// 2. 用 @Import 精确引入，范围可控（推荐）
@SpringBootApplication
@Import(PayConfiguration.class)
public class DemoApplication { }

// 3. 靠自动配置——第三方模块的正确姿势：不要去扫别人的包
//    别人的包里应该有 META-INF/spring/...AutoConfiguration.imports
```

**第三个选项是分水岭**。答出"第三方库不应该要求宿主工程改 `scanBasePackages`，它应该提供自动配置"这句，说明你真的理解 Boot 的设计边界。`scanBasePackages` 是给自家多模块用的补丁，不是给 SDK 用的接口。

### `proxyBeanMethods = false` 到底省了什么

`@Configuration(proxyBeanMethods = true)` 时，Spring 要为每个配置类生成一个 CGLIB 子类，重写 `@Bean` 方法，保证你两次调用 `userService()` 拿到的是同一个对象（从容器里取，而不是 new 一个新的）。

设为 false 之后：

| | `true`（老默认） | `false`（Boot 2.2 起） |
|---|---|---|
| 生成 CGLIB 类 | 每个配置类一个 | 无 |
| `@Bean` 方法互相调用 | 返回容器里的单例 | **普通 Java 调用，会 new 新对象** |
| 启动速度 | 慢一些（配置类越多越明显） | 快 10% 上下（经验值，100+ 配置类项目） |
| Metaspace 占用 | 多一批代理类元数据 | 少 |
| 使用约束 | 随意 | 配置类之间**用方法参数注入**，不要互相调用 |

```java
@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    public OrderService orderService(UserService userService) {  // 参数注入，正确
        return new OrderService(userService);
    }

    @Bean
    public UserService userService() { return new UserService(); }

    // 错误示范：直接调用 userService() 会 new 出第二个对象
    // @Bean public OrderService bad() { return new OrderService(userService()); }
}
```

自动配置类统一用 `proxyBeanMethods = false`，这也是 `@AutoConfiguration` 注解内部定死的值——它的设计前提就是"自动配置类之间不互相调用方法，靠参数注入"。

> **一句话答法**：`@SpringBootApplication` = `@SpringBootConfiguration`（配置类）+ `@EnableAutoConfiguration`（引入自动配置选择器）+ `@ComponentScan`（扫主类所在包及子包）。默认 `proxyBeanMethods=false`，不生成 CGLIB 代理，所以配置类之间必须用参数注入而不是方法调用。

---

## L2 `SpringApplication.run()` 的 11 步：哪一步失败，报什么错

`run()` 的源码一直是"看一眼就忘"，因为步骤平铺、没有分层。按**构造**和**运行**切成两段就清楚了：构造 3 步，运行 8 步。

```java
// 构造阶段：new SpringApplication(primarySources)
public SpringApplication(ResourceLoader loader, Class<?>... primarySources) {
    this.webApplicationType = WebApplicationType.deduceFromClasspath(); // ① 推断类型
    this.bootstrapRegistryInitializers = getBootstrapRegistryInitializers(); // ② 引导期初始化器
    setInitializers(getSpringFactoriesInstances(ApplicationContextInitializer.class));
    setListeners(getSpringFactoriesInstances(ApplicationListener.class));
    this.mainApplicationClass = deduceMainApplicationClass();          // ③ 推断主类
}
```

第 ① 步的判定很朴素，就三个类：

| classpath 里有 | 推出类型 | 用的容器实现 |
|---|---|---|
| `DispatcherServlet`（spring-webmvc） | `SERVLET` | `AnnotationConfigServletWebServerApplicationContext` |
| `DispatcherHandler`（spring-webflux）且没有 mvc | `REACTIVE` | `AnnotationConfigReactiveWebServerApplicationContext` |
| 都没有 | `NONE` | `AnnotationConfigApplicationContext` |

**这就是"我只加了 `spring-boot-starter-webflux` 为什么会起 Netty"的答案**，也是"同时引入 web 和 webflux 时为什么默认是 Tomcat"的答案——SERVLET 判定优先。

运行阶段 8 步：

| # | 方法 | 干了什么 | 失败时的典型症状 |
|---|---|---|---|
| ④ | `getRunListeners` + `starting()` | 发 `ApplicationStartingEvent` | 监听器 `ClassNotFound` |
| ⑤ | `prepareEnvironment` | 加载 `application.yml`、激活 profile、发布 `EnvironmentPreparedEvent` | 占位符解析失败、profile 写错 |
| ⑥ | `printBanner` | 打 Banner（可关） | — |
| ⑦ | `createApplicationContext` | 反射 new 上表里的容器 | 类找不到 |
| ⑧ | `prepareContext` | 设置 environment、**注册主类 BeanDefinition**、执行 Initializer、发 `ContextLoadedEvent` | Initializer 里抛异常 |
| ⑨ | `refreshContext` | **进 refresh() 12 步**（L3） | Bean 创建失败、端口占用、循环依赖 |
| ⑩ | `afterRefresh` | 3.x 里是空实现，历史包袱 | — |
| ⑪ | `started()` + `callRunners` + `running()` | 发 `ApplicationStartedEvent` → 执行 `Runner` → 发 `ApplicationReadyEvent` | Runner 抛异常会导致启动失败 |

三个高价值细节：

**第一，`prepareContext` 只注册了一个 BeanDefinition**——主类本身。所以你在 `refresh` 之前，容器里除了主类什么都没有。这也解释了为什么 `ApplicationContextInitializer` 里拿不到业务 Bean。

**第二，`Runner` 的异常会让启动失败**。`CommandLineRunner` / `ApplicationRunner` 抛异常 → `callRunner` 捕获后包装成 `IllegalStateException` → 走 `handleRunFailure` → 进程退出码 1。K8s 里表现为 CrashLoopBackOff，而日志最后一行是业务异常，很多人误判成"代码 bug 导致启动不了"，其实是 Runner 干的。

**第三，失败报告是 `FailureAnalyzer` 生成的**。你看到的那段：

```
***************************
APPLICATION FAILED TO START
***************************
Description:
Web server failed to start. Port 8080 was already in use.
Action:
Identify and stop the process that's listening on port 8080...
```

来自 `PortInUseFailureAnalyzer`，`Description` 和 `Action` 两行是它拼的。自定义 starter 想给用户友好提示，实现一个 `FailureAnalyzer` + 注册到 `spring.factories` 就行（注意：**这个 key 在 3.0 依然有效**，被移除的只有 `EnableAutoConfiguration` 那一条）。

---

## L3 `refresh()` 的 12 步：Boot 只插了三刀

`refresh()` 是 Spring Framework 的模板方法，Boot 一个字节都没改，只是在三个位置做了扩展：

```
refresh()
 ├─① prepareRefresh              initPropertySources、校验必需属性、清 earlyEvent
 ├─② obtainFreshBeanFactory      注解容器基本空转（不重读 XML）
 ├─③ prepareBeanFactory          注入 Environment、注册 Aware 处理器、注册可解析依赖
 ├─④ postProcessBeanFactory      子类扩展：注册 request/session 作用域
 ├─⑤ invokeBeanFactoryPostProcessors   ★插入点 A：自动配置在这注册 BeanDefinition
 ├─⑥ registerBeanPostProcessors 注册 @Autowired/@Resource/@Value 处理器、AOP 的 AnnotationAwareAspectJAutoProxyCreator
 ├─⑦ initMessageSource          国际化
 ├─⑧ initApplicationEventMulticaster
 ├─⑨ onRefresh                   ★插入点 B：createWebServer() → new Tomcat()，不 start
 ├─⑩ registerListeners
 ├─⑪ finishBeanFactoryInitialization  ★耗时大头：所有单例在这被 new 出来
 └─⑫ finishRefresh               ★插入点 C：lifecycle.onRefresh() → tomcat.start()，发 ContextRefreshedEvent
```

### 插入点 A：自动配置的 BeanDefinition 在这里进厂

第 ⑤ 步执行所有 `BeanFactoryPostProcessor`，其中最重要的是 `ConfigurationClassPostProcessor`（一个 `BeanDefinitionRegistryPostProcessor`）。它解析所有配置类上的 `@ComponentScan`、`@Import`、`@Bean`，**递归展开**，把结果注册成 BeanDefinition。

`AutoConfigurationImportSelector` 就是被 `@Import` 带进来的一个 `DeferredImportSelector`——它被**推迟**到所有用户配置类解析完之后才执行。这个"推迟"是关键设计：保证用户的 `@Bean` 先注册，自动配置类里的 `@ConditionalOnMissingBean` 才能看到用户的选择，从而正确退让。

### 插入点 B：Tomcat 只是被 new 出来

`ServletWebServerApplicationContext.onRefresh()` 调 `createWebServer()`：

```java
private void createWebServer() {
    WebServer webServer = this.webServer;
    ServletContext servletContext = getServletContext();
    if (webServer == null && servletContext == null) {
        ServletWebServerFactory factory = getWebServerFactory();
        // 注意：这里只是构造 Tomcat 对象、准备 Connector，没有 bind 端口
        this.webServer = factory.getWebServer(getSelfInitializer());
        getBeanFactory().registerSingleton("webServerGracefulShutdown", ...);
    }
}
```

**端口是在这一步绑定的**（`TomcatWebServer` 构造里 `initialize()` → 启动 Connector），但 **Servlet 的 `init()` 在 ⑫ 才发生**。所以"端口占用"报错发生在 ⑨，而"`DispatcherServlet` 初始化失败"报错发生在 ⑫。能区分这两条，说明你真读过源码。

### 插入点 C：`finishRefresh` 才是真正的"服务可用"

```java
protected void finishRefresh() {
    clearResourceCaches();
    initLifecycleProcessor();                 // 默认 DefaultLifecycleProcessor
    getLifecycleProcessor().onRefresh();       // → WebServerStartStopLifecycle.start()
    publishEvent(new ContextRefreshedEvent(this));  // 业务监听器在这被触发
}
```

`WebServerStartStopLifecycle` 里 `this.webServer.start()`，Tomcat 开始接受请求。

**一个必须纠正的认知**：`ContextRefreshedEvent` 触发时，Tomcat 已经 start 了（因为 `onRefresh` 在 `publishEvent` 之前）。但此时 `ApplicationReadyEvent` 还没发，Runner 还没跑。所以"服务到底算不算就绪"有三个时间点：

| 时间点 | Tomcat | Bean | Runner | 适合做什么 |
|---|---|---|---|---|
| `ContextRefreshedEvent` | 已监听 | 全部就绪 | 未执行 | 缓存预热（要保证幂等，可能被重复触发） |
| `ApplicationStartedEvent` | 已监听 | 全部就绪 | 未执行 | 埋点 |
| `ApplicationReadyEvent` | 已监听 | 全部就绪 | 已执行 | 上报注册中心、标记健康 |

K8s 的 `readinessProbe` 应该打 `/actuator/health/readiness`，它绑定的是 `ApplicationReadyEvent` 之后的状态——而不是 `ContextRefreshedEvent`。这是"服务明明起来了，网关还是 502"的经典原因。

### 第 ⑪ 步为什么最贵

`finishBeanFactoryInitialization` 会遍历所有非懒加载单例，逐个 `getBean`。成本拆开是：

- 反射调用构造器 + 解析构造器参数（`ConstructorResolver`）
- 字段注入：`AutowiredAnnotationBeanPostProcessor` 遍历所有字段
- `@PostConstruct` / `InitializingBean` / `afterPropertiesSet`
- AOP 代理：命中切点就生成 CGLIB 子类（每个类约 1~3ms）

600 个 Bean 里如果有 80 个被代理，光 CGLIB 就是 100~200ms。**"启动慢先看 Bean 数量"比"看自动配置类数量"有效得多**，因为单例实例化占了刷新时间的 60% 左右。

---

## L4 自动配置的六阶段流水线：153 个候选怎么变成 40 个生效

这是本期的核心。答这题不要背注解，要背**流水线**：

```
   ①触发    @EnableAutoConfiguration
              └─ @Import(AutoConfigurationImportSelector)
   ②名单    ImportCandidates.load(AutoConfiguration.class, classLoader)
              └─ 读 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
                 3.x：153 条（3.4.4 实测）
   ③去重     removeDuplicates + 排除（注解 / 属性两种）
   ④过滤    AutoConfigurationImportFilter
              └─ OnClassCondition 用 ASM 读注解元数据，不加载类
                 153 → 约 60（按 classpath 实际存在的类）
   ⑤排序    @AutoConfiguration(before/after) + @AutoConfigureOrder
   ⑥装配    @Conditional* 条件判定 + @EnableConfigurationProperties 绑定
                 60 → 约 40 生效，注册成 BeanDefinition
```

### ② 名单从哪读：这条必须背准

```java
// AutoConfigurationImportSelector.getCandidateConfigurations(3.x)
protected List<String> getCandidateConfigurations(AnnotationMetadata metadata,
                                                 AnnotationAttributes attributes) {
    List<String> configurations = ImportCandidates.load(
            AutoConfiguration.class, getBeanClassLoader()).getCandidates();
    Assert.notEmpty(configurations, "No auto configuration classes found in "
            + "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
    return configurations;
}
```

- **2.7 之前**：`SpringFactoriesLoader.loadFactoryNames(EnableAutoConfiguration.class, classLoader)`，读 `META-INF/spring.factories` 里 `org.springframework.boot.autoconfigure.EnableAutoConfiguration=` 后面那串逗号分隔的类名。
- **2.7**：新增 `AutoConfiguration.imports`，两种都读，`spring.factories` 那条标记 `@Deprecated`。
- **3.0**：**只读新版文件**，`spring.factories` 里的自动配置条目彻底失效（`spring.factories` 文件本身还在，其他扩展点如 `EnvironmentPostProcessor`、`FailureAnalyzer` 继续用它）。
- **4.0**：自动配置类随模块拆分，`spring-boot-autoconfigure` 只剩 12 条，其余分散在 `spring-boot-webmvc`(6)、`spring-boot-tomcat`(5)、`spring-boot-jdbc`(9)、`spring-boot-data-redis`(6) 等 20 多个模块里，各自带一份同名 imports 文件，加载时全部合并。

**这是最容易翻车的一问**：候选人说 `spring.factories`，你说"3.0 就删了"，他立刻露出"那我背错了"的表情。给一个准确的版本矩阵，比背对答案更有说服力。

### ④ 过滤：为什么少个类不会 `NoClassDefFoundError`

如果 classpath 里没有 `DataSource`，那 `DataSourceAutoConfiguration` 的 `@ConditionalOnClass(DataSource.class)` 怎么判定的？直接 `Class.forName` 会抛异常吗？不会——Boot 用的是 **ASM 读字节码**，不是反射：

```java
// OnClassCondition 的核心是这一层间接：
// 1. 用 SimpleMetadataReader 读 .class 文件的注解元数据（不进 JVM、不初始化类）
// 2. 注解值以字符串形式取出："@ConditionalOnClass(\"javax.sql.DataSource\")" → 只拿到类型名
// 3. 用 ClassLoader 判断这个名字能否被加载（只做 canLoad，不做 load）
```

这套机制带来一个非常重要的工程收益：**自动配置类可以引用 classpath 里不存在的类**，只要它出现在 `@ConditionalOnClass` 注解里（注解值用字符串保存，读注解时不会触发类解析）。而如果把 `@ConditionalOnClass` 写在 `@Bean` 方法的**返回类型**上，就真的会去加载——这是"为什么自动配置方法要返回接口/父类型"的原因。

更狠的是 `OnClassCondition` 的**批量优化**：它不是逐个类判定，而是把所有候选的 `@ConditionalOnClass` 收集起来，一次 ASM 扫描全部 `.class`，把结果做成一个 `Set<String>`，然后 `retainAll`。这在 153 个候选类时省下的不是一点——实测从"逐个反射判定 ~300ms"降到"批量 ASM ~30ms"。

### ⑤ 排序：用户配置永远赢

三种表达方式的优先级：

| 写法 | 作用范围 | 说明 |
|---|---|---|
| `@AutoConfiguration(before = X.class, after = Y.class)` | 自动配置类之间 | 3.x 推荐写法（`@AutoConfigureBefore` 已废弃） |
| `@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)` | 只影响**自动配置之间**的顺序 | 对用户 Bean 无效 |
| `@Order` / `Ordered` | 同类型 Bean 的注入顺序 | 不参与配置类解析顺序 |

**底层规则只有一条：所有用户 `@Configuration`（包括 `@ComponentScan` 扫到的）都在自动配置之前解析完成。** 因为 `AutoConfigurationImportSelector` 是 `DeferredImportSelector`，Spring 会把它挪到最后一组处理。这条规则撑起了整个 `@ConditionalOnMissingBean` 体系。

### ⑥ 装配：条件 + 属性绑定

```java
@AutoConfiguration
@ConditionalOnClass(RedisTemplate.class)             // 有 Jedis/Lettuce 才进来
@ConditionalOnProperty(prefix = "app.cache", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CacheProperties.class) // 绑定 @ConfigurationProperties
public class AppCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean                       // 用户自己定义了就不生效
    public CacheService cacheService(CacheProperties props, RedisTemplate<String, Object> tpl) {
        return new RedisCacheService(props.getTtl(), tpl);
    }
}
```

### 调试：怎么知道哪些生效了

```bash
# 启动加 --debug（或 -Ddebug=true），会输出 ConditionEvaluationReport
java -jar app.jar --debug

# 报告包含四段：
# Positive matches:      生效的自动配置（含判定明细）
# Negative matches:      未生效的（含未匹配的原因）
# Exclusions:            被 exclude 的
# Unconditional classes: 无条件生效的（危险信号，说明没写条件）
```

生产环境不要常开：153 个候选的完整判定日志，上千行，启动日志会被淹没。用 `--debug` 单独跑一次拿快照，或者用 `spring.autoconfigure.exclude` 反查。

排除某个自动配置的四种写法：

```java
// 1. 注解 + 类
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)

// 2. 注解 + 类名（避免编译期依赖）
@SpringBootApplication(excludeName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")

// 3. 配置文件（最灵活，可按 profile 排除）
// application.yml
// spring.autoconfigure.exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration

// 4. 条件式排除（自己写一个 AutoConfigurationImportFilter，不常用）
```

`exclude` 生效的时机在阶段 ③，**早于**条件判定，所以它是"根本不进候选集"，比给它加 `@ConditionalOnProperty` 更彻底——不会出现在 `--debug` 的 Negative matches 里。

---

## L5 从 `spring.factories` 到 `AutoConfiguration.imports`：为什么要改

`spring.factories` 本来是 Spring 的 SPI 机制：一个 properties 文件，key 是接口全名，value 是实现类列表。

```properties
# spring.factories（2.6 及以前，200 多行）
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration,\
org.springframework.boot.autoconfigure.aop.AopAutoConfiguration,\
...(一共 140 多行，每行一个类，逗号结尾)
org.springframework.context.ApplicationContextInitializer=\
com.example.MyInitializer
org.springframework.boot.env.EnvironmentPostProcessor=\
com.example.MyEnvPostProcessor
org.springframework.boot.diagnostics.FailureAnalyzer=\
com.example.MyFailureAnalyzer
```

换成一个专用文件：

```properties
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.example.cache.AppCacheAutoConfiguration
com.example.pay.PayAutoConfiguration
```

改动的原因是四条，按说服力排序：

| 问题 | `spring.factories` | `AutoConfiguration.imports` |
|---|---|---|
| 一个 key 写错 | 整个文件解析失败，所有扩展点全挂 | 只影响自动配置这一个文件 |
| 加载粒度 | 必须整个文件读完才能知道有哪些类 | 按需、可按文件来源分开（每个 jar 一份） |
| 顺序表达 | 只能靠类上的 `@AutoConfigureOrder` | 仍可，但文件内自然有序，且支持模块化拆分 |
| ASM 预过滤 | 一大串字符串，解析成本高 | 一行一个类名，直接进集合 |
| IDE 支持 | 不认识，类是灰色（"未使用"） | IDEA 能识别并给出补全/校验 |
| 4.0 模块化 | 无法按模块拆分 | 每个模块自带一份，天然可拆 |

**注意一个常见误解**：不是"`spring.factories` 被删了"，而是**只删了 `EnableAutoConfiguration` 这一个 key**。`ApplicationContextInitializer`、`EnvironmentPostProcessor`、`FailureAnalyzer`、`SpringApplicationRunListener`、`BootstrapRegistryInitializer` 这些依然写 `spring.factories`。答错这一点，等于自曝只看了两篇博客。

### starter 的三段式结构

"自己写一个 starter"这题，90% 的人答成"写个 `@Configuration` 加 `@Bean`"。缺了骨架，等于没答。

```
app-cache-spring-boot-starter/            ← 空壳，只有 pom
└── pom.xml                               依赖 app-cache-spring-boot-autoconfigure
                                          依赖 redis 客户端、连接池
app-cache-spring-boot-autoconfigure/      ← 代码在这
├── pom.xml                               只依赖 spring-boot-autoconfigure（compile）
└── src/main/
    ├── java/com/example/cache/
    │   ├── CacheProperties.java          @ConfigurationProperties("app.cache")
    │   ├── CacheService.java             接口
    │   ├── RedisCacheService.java        实现
    │   └── AppCacheAutoConfiguration.java
    └── resources/META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
用户工程
└── 加一行依赖 app-cache-spring-boot-starter，写两行 yml，完事
```

**为什么拆两个模块**：`autoconfigure` 只依赖 `spring-boot-autoconfigure`（不引入具体实现的强依赖版本），`starter` 负责"选定一个实现 + 拉齐版本"。这样用户想换实现时只换 starter，不用动 autoconfigure。Spring 官方的 `spring-boot-starter-data-redis` 就是这个结构（官方把 starter 和 autoconfigure 合并了，但第三方的规范是拆开）。

一个能过面试的自动配置类，五个要素缺一不可：

```java
@AutoConfiguration                                        // ① 3.x 专用注解，内含 proxyBeanMethods=false
@ConditionalOnClass({RedisTemplate.class, StringRedisTemplate.class})  // ② 缺类就退
@ConditionalOnProperty(prefix = "app.cache", name = "enabled", matchIfMissing = true) // ③ 开关
@EnableConfigurationProperties(CacheProperties.class)     // ④ 属性绑定
public class AppCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean                            // ⑤ 让位给用户
    public CacheService cacheService(CacheProperties props,
                                     ObjectProvider<RedisTemplate<String, Object>> tplProvider) {
        // 用 ObjectProvider 而不是直接注入：模板可能被用户替换，故意做成软依赖
        return new RedisCacheService(props.getTtl(), tplProvider.getIfAvailable());
    }
}
```

`@AutoConfiguration` 在 3.x 里还多了一个 `replace` 属性（`@AutoConfiguration(replace = XxxAutoConfiguration.class)`），用来在同一个 jar 内替换旧实现，比 `@AutoConfigureBefore` + 重名 Bean 干净。

---

## L6 条件装配的 12 个注解：顺序错了，注解就是装饰

自动配置的所有"智能"都来自条件注解。高频的 12 个：

| 注解 | 判定依据 | 典型用途 | 易错点 |
|---|---|---|---|
| `@ConditionalOnClass` | classpath 能否加载指定类（ASM） | 有依赖才装配 | 写在 `@Bean` 方法返回类型上会真加载 |
| `@ConditionalOnMissingClass` | 类不存在 | 排除冲突实现 | 少用，容易留死路 |
| `@ConditionalOnBean` | 容器里有指定 Bean | 依赖某个 Bean 才装配 | **只能用在自动配置类里** |
| `@ConditionalOnMissingBean` | 容器里没有 | 让用户覆盖 | 判定时机依赖解析顺序 |
| `@ConditionalOnSingleCandidate` | 某类型恰好一个（或标了 `@Primary`） | 有唯一实现才装配 | 两个同类型就退让 |
| `@ConditionalOnProperty` | 配置项值匹配 | 功能开关 | `havingValue` 默认 `""`，即"存在且不为 false" |
| `@ConditionalOnResource` | 资源文件存在 | 有 `logback.xml` 才装配 | 路径用 `classpath:` 前缀 |
| `@ConditionalOnWebApplication` | 上下文是 Web 类型 | 只在 web 环境生效 | 有 `SERVLET`/`REACTIVE` 两种 `type` |
| `@ConditionalOnNotWebApplication` | 非 Web | 批处理、CLI | — |
| `@ConditionalOnExpression` | SpEL 求值 | 复杂组合条件 | 慢，且难排查，慎用 |
| `@ConditionalOnJava` | JDK 版本区间 | 特定版本特性 | 用 `JavaVersion` 枚举，别写数字 |
| `@ConditionalOnWarDeployment` | 部署在外部容器 | war 包场景 | 内嵌容器时不生效 |

### 三个真会咬人的陷阱

**陷阱一：`@ConditionalOnBean` 写在普通 `@Configuration` 上不可靠**

官方文档的原话是：这个注解"只应该在自动配置类上使用"。原因很直白——条件判定发生在**配置类解析时**，而普通配置类的解析顺序由扫描顺序和 `@Order` 决定，你无法保证被依赖的那个 Bean 已经注册了。自动配置类之所以可靠，是因为它们统一在用户配置之后、且彼此顺序由 `@AutoConfiguration(before/after)` 显式声明。

```java
// 别这么写：userService 是否"已注册"取决于扫描顺序
@Configuration
@ConditionalOnBean(UserService.class)
public class ReportConfig { }

// 要这么写：依赖注入本身就会失败，让它失败更清晰
@Configuration
public class ReportConfig {
    @Bean
    public ReportService reportService(UserService userService) { ... }
}
```

**陷阱二：同一个自动配置类里的 `@Bean` 方法之间，`@ConditionalOnMissingBean` 不可靠**

```java
@AutoConfiguration
public class FooAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FooTemplate fooTemplate() { return new FooTemplate(); }

    @Bean
    @ConditionalOnMissingBean
    public FooService fooService(FooTemplate tpl) { return new FooService(tpl); }
}
```

看起来没问题。但如果用户只定义了 `FooService` 没定义 `FooTemplate`，`fooService` 的条件退让会生效，而 `fooTemplate` 仍然创建——**结果是一个没人用的 Bean**。这不是 bug，只是"条件只保证自己那个 Bean 不被创建"。

需要"整个配置一起退让"时，把条件写在**类上**：

```java
@AutoConfiguration
@ConditionalOnMissingBean(FooService.class)   // 类级条件：有一个就不装配整组
public class FooAutoConfiguration { ... }
```

**陷阱三：`@ConditionalOnProperty` 的三种"假"**

```java
// 情形 A：只写 name，配了 app.cache.enabled=false
@ConditionalOnProperty(prefix = "app.cache", name = "enabled")
// → 不匹配。规则是"值不等于 false 即匹配"，false 视为关闭

// 情形 B：写了 havingValue="true"，但没配
@ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "true")
// → 不匹配。matchIfMissing 默认 false

// 情形 C：配了 app.cache.enabled=TRUE（大写）
@ConditionalOnProperty(..., havingValue = "true")
// → 不匹配。字符串比较，大小写敏感
```

这三种情况的判定结果在 `--debug` 的 Negative matches 里都长得一样（`did not find property`），所以线上排查时别靠猜——把 `ConditionEvaluationReport` 打出来看 `OnPropertyCondition` 那一段。

---

## L7 启动耗时：优化方向不在自动配置，在 Bean 数量

回到 L0 的那张账单。用一张图把"哪一段能省"标出来：

```
JVM + 类加载         ████████████████ 620ms   省：CDS / AOT / 少 starter
自动配置解析         ███ 120ms                省：exclude 无用配置（收益最小）
单例实例化           ██████████████ 560ms     省：lazy-init / 减少 Bean / 少代理
AOP + 连接池         █████ 200ms              省：收窄切点 / 池初始化改懒
Tomcat 启动          █████ 210ms              省：改 Jetty/Netty 收益不大，几乎不可省
Runner + 事件        ██ 90ms                  省：把重活挪出启动路径
```

### 五档手段，按性价比排

**第一档：删 starter（收益最大，风险最低）**

```xml
<!-- 常见冗余：只想用 Redis，结果引入了整个 spring-boot-starter-data-redis -->
<!-- 它带 Jedis/Lettuce、连接池、Reactive 支持、Spring Data Redis 全套 -->
<!-- 以及 6 个自动配置类。改用 spring-boot-starter-data-redis 的裁剪版或裸依赖 -->
```

一个 starter 平均带 15~25 个 jar。每少一个 starter，类加载少几百个类，自动配置候选少几条。**先做这一步，再谈别的。**

**第二档：`spring.main.lazy-initialization=true`（快 30%，但有毒）**

```yaml
spring:
  main:
    lazy-initialization: true      # 全局懒加载
  # 或只懒加载指定类
```

代价要说清楚，这是面试的加分点：

- 首次请求变慢（Bean 在第一次用到时才创建），P99 尖刺；
- **错误延后暴露**：配置错误、依赖缺失从"启动失败"变成"运行到某个分支才 500"；
- 循环依赖、`@PostConstruct` 的时序问题会以更诡异的方式出现；
- 有状态初始化（连接池预热、本地缓存加载）失效。

正确用法是**只对非关键路径的 Bean 开**：`@Lazy` 精确标注，或者用 `spring.main.lazy-initialization=true` 配合 `@Lazy(false)` 把核心 Bean 钉住。

**第三档：`spring-context-indexer`（省 100~300ms）**

`@ComponentScan` 要遍历 classpath 下所有 `.class` 读注解，一个 5000 个类的工程能吃掉 300ms。加一个编译期处理器，把候选组件写进 `META-INF/spring.components`，运行时直接读索引：

```xml
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-context-indexer</artifactId>
    <optional>true</optional>
</dependency>
```

**第四档：AOT + CDS（省 30%~40%，生产可用）**

```bash
# 1. 生成 AOT 处理后的类（编译期确定 Bean 定义、代理类）
java -Dspring.aot.enabled=true -jar app.jar     # 需先用 spring-boot-maven-plugin 的 aot 目标

# 2. 打 AppCDS 归档（把类加载结果缓存到文件）
java -XX:ArchiveClassesAtExit=app.jsa -jar app.jar
java -XX:SharedArchiveFile=app.jsa -jar app.jar  # 二次启动省 30%+
```

**第五档：GraalVM Native Image（0.1 秒级）**

| 指标 | JVM 模式 | Native Image |
|---|---|---|
| 启动时间 | 2.4s | 0.06~0.12s |
| 常驻内存 | 300~400MB | 40~70MB |
| 构建耗时 | 30s | 5~10min |
| 峰值吞吐 | 基准 | 通常低 5%~20% |
| 动态能力 | 全支持 | 反射/动态代理/资源需显式 hint |

面试里问"要不要上 Native"，标准答法是**看场景**：Serverless / CLI / 边缘计算 / 需要极速扩缩容的场景值得，常规常驻 Web 服务不值得——构建慢、调试难、生态有坑（某些库的反射配置要手写 `reflect-config.json`），省下的 2 秒换不来这些成本。

---

## L8 配置加载：13 档优先级与 `bootstrap.yml` 的消失

### 优先级从高到低

| 优先级 | 来源 | 说明 |
|---|---|---|
| 1 | 命令行参数 `--server.port=9090` | 最高，测试时最常用 |
| 2 | `SPRING_APPLICATION_JSON` | 环境变量里塞一段 JSON |
| 3 | `ServletConfig` / `ServletContext` 参数 | war 部署才有 |
| 4 | JNDI (`java:comp/env`) | 传统容器 |
| 5 | `System.getProperties()` | `-Dkey=value` |
| 6 | OS 环境变量 | `SERVER_PORT=9090`，K8s 注入 |
| 7 | `RandomValuePropertySource` | `random.int` |
| 8 | jar 外 `application-{profile}.yml` | **运维覆盖的首选位置** |
| 9 | jar 外 `application.yml` | — |
| 10 | jar 内 `application-{profile}.yml` | — |
| 11 | jar 内 `application.yml` | 开发默认值 |
| 12 | `@PropertySource` | 注意：**加载晚于 6，早于 13** |
| 13 | `setDefaultProperties` | 兜底默认值 |

**一个反直觉点**：`@PropertySource` 的优先级非常低（第 12 档），比环境变量还低。所以"我在 `@PropertySource` 里写了数据库地址，为什么被环境变量覆盖了"不是 bug，是设计。想让 `@PropertySource` 里的值优先，只能改配置的 key 或用 `EnvironmentPostProcessor`。

### `bootstrap.yml` 为什么不生效了

这是 2.4 升级最经典的坑：

- **2.3 及以前**：Spring Cloud 提供 `BootstrapApplicationListener`，在主应用启动**之前**建一个父容器，专门读 `bootstrap.yml`，用它去连配置中心，拿到远程配置后再启动主容器。
- **2.4 起**：引入 `ConfigData` API，配置加载统一走 `spring.config.import`。`bootstrap.yml` 不再被自动加载。
- **现状（3.x）**：要么加 `spring-cloud-starter-bootstrap` 恢复旧行为，要么改成：

```yaml
# application.yml（3.x 推荐）
spring:
  config:
    import: "optional:nacos:${spring.application.name}.yml"
  application:
    name: order-service
```

`optional:` 前缀的意思是"连不上配置中心也继续启动"。**不加 `optional:` 时配置中心不可用会直接启动失败**——这个细节决定了你的服务在配置中心挂掉时是"降级运行"还是"全线雪崩"。

### ConfigData 带来的三个新能力

```yaml
# 1. 多文档 + 条件激活（一个文件写多环境）
spring:
  config:
    activate:
      on-profile: prod
server:
  port: 80
---
spring:
  config:
    activate:
      on-profile: dev
server:
  port: 8080

# 2. profile 分组（一个 group 激活多个 profile）
spring:
  profiles:
    group:
      prod: [prod-db, prod-redis, prod-mq]

# 3. 导入额外的配置文件
spring:
  config:
    import:
      - classpath:extra/db.yml
      - optional:file:./override.yml
```

---

## L9 六个高频坑：启动失败先查这六条

**坑一：Bean 覆盖默认被禁止（2.1 起）**

```yaml
spring:
  main:
    allow-bean-definition-overriding: false   # 2.1 起默认值
```

两个同名 Bean（比如你自己写了 `dataSource`，某个自动配置里也有）→ 启动直接失败：

```
The bean 'dataSource', defined in class path resource [...],
could not be registered. A bean with that name has already been defined
and overriding is disabled.
```

这其实是好事。修复方向不是打开覆盖开关，而是改名或加 `@Primary`——**打开开关只是把"启动时报错"变成"运行到某个分支用错 Bean"**。

**坑二：循环依赖默认被禁止（2.6 起）**

```yaml
spring:
  main:
    allow-circular-references: false   # 2.6 起默认值
```

字段注入的循环依赖以前靠三级缓存能兜住，2.6 起默认直接失败。注意：**构造器注入的循环依赖任何版本都救不了**（对象都没造出来，没法提前暴露引用）。修复方式只有三种：拆服务、`@Lazy` 打断、改用 setter/字段注入（最差的选择）。

**坑三：自动配置类被 `@ComponentScan` 扫到了**

如果第三方 starter 的自动配置类正好在你的扫描包下（比如都是 `com.example`），它会被当成**普通配置类**先实例化一次。此时 `@ConditionalOnMissingBean` 的判定时机就乱了——因为它是被 `@ComponentScan` 拉进来的，不是被 `AutoConfigurationImportSelector` 拉进来的，**不享受"用户配置先行"的保证**。

官方规范明确要求：自动配置类必须放在独立的包，且不能落在使用方的 `@ComponentScan` 范围内。自研 starter 时这条最容易踩。

**坑四：`@ConfigurationProperties` 不生效的三种原因**

```java
@ConfigurationProperties(prefix = "app.cache")   // ① 少了 @Component 或 @EnableConfigurationProperties
public class CacheProperties { }
```

| 原因 | 症状 | 修复 |
|---|---|---|
| 没标 `@Component` 也没在别处 `@EnableConfigurationProperties` | 字段全是默认值 | 二选一补上 |
| 没有 `spring-boot-configuration-processor` | 能注入但 IDE 无提示、`metadata.json` 缺失 | 加 `optional` 依赖 |
| 用了 `@Value` 风格的松散绑定 | `app.cache.max-size` 绑定到 `maxSize` 失败 | 松散绑定只对 `@ConfigurationProperties` 生效 |

**坑五：多主类 / 扫进了测试类**

一个工程里两个 `@SpringBootApplication` → 第二个会被第一个扫到，变成 `@Configuration` 被重复解析；`src/test` 下的 mock 配置如果包名在主类之下，也会被扫进来，表现为"测试能跑、生产启动报 Bean 冲突"。

**坑六：3.0 升级后自研 starter 失效**

症状：升级到 Boot 3 后，自己的 starter 加了依赖但 `@Bean` 完全没注册，`--debug` 里连候选都没有。原因：代码还在写 `spring.factories` 的 `EnableAutoConfiguration` key。

```properties
# 旧（3.0 起失效）
# META-INF/spring.factories
org.springframework.boot.autoconfigure.EnableAutoConfiguration=com.example.FooAutoConfiguration

# 新
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
com.example.FooAutoConfiguration
```

顺手补一刀：Boot 3 是 `javax.*` → `jakarta.*` 的大迁移，`javax.servlet`、`javax.persistence`、`javax.validation` 全部要换包名。这是 2.7→3.0 升级的第一大失败原因，比 `spring.factories` 更常见。

---

## L10 速查：一张表记住这期的数字

| 数字 | 含义 |
|---|---|
| 153 | Boot 3.4.4 的自动配置候选类总数 |
| 12 | Boot 4.0 时代 `spring-boot-autoconfigure` 核心包剩下的条数（其余拆到 20+ 模块） |
| 6 | 自动配置流水线阶段数：触发 → 名单 → 去重 → 过滤 → 排序 → 装配 |
| 11 | `SpringApplication.run()` 的步骤数（构造 3 + 运行 8） |
| 12 | `refresh()` 的步骤数，Boot 只插 3 刀（⑤⑨⑫） |
| 3 | `@SpringBootApplication` 拆开的注解数 |
| 13 | 配置源的优先级档数 |
| 60% | 单例实例化在刷新耗时里的占比 |
| 5% | 自动配置解析在总启动耗时里的占比（所以优化它收益最小） |
| 30% | `lazy-initialization` 大约能省的启动时间，代价是首请求变慢 |
| 0.1s | Native Image 的启动量级（对比 JVM 2.4s） |

常用命令：

```bash
java -jar app.jar --debug                        # 打印条件评估报告
java -jar app.jar --spring.autoconfigure.exclude=...  # 临时排除
curl localhost:8080/actuator/conditions          # 线上看条件报告（需暴露）
curl localhost:8080/actuator/beans               # 看 Bean 装配关系
curl localhost:8080/actuator/startup             # 看每个启动步骤的耗时（buffering）
java -Xlog:class+load=info -jar app.jar | wc -l   # 数一下加载了多少类
```

`/actuator/startup` 需要先配：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,conditions,beans,startup
  endpoint:
    startup:
      enabled: true
```

---

## L11 十四条高频问答

**Q1：`@SpringBootApplication` 是哪三个注解组成的？各自作用？**

`@SpringBootConfiguration`（= `@Configuration`，声明配置类）+ `@EnableAutoConfiguration`（`@Import` 引入 `AutoConfigurationImportSelector`）+ `@ComponentScan`（扫主类所在包及子包）。另外 `proxyBeanMethods` 默认 false，不生成 CGLIB 代理，配置类之间必须用参数注入。

**Q2：`SpringApplication.run()` 都做了什么？**

构造阶段三步：推断应用类型（看 classpath 有没有 `DispatcherServlet`/`DispatcherHandler`）、加载 Initializer 与 Listener、从调用栈推断主类。运行阶段八步：启动监听器 → 准备 Environment → 打 Banner → 创建容器 → prepareContext（只注册主类 BeanDefinition）→ refreshContext（进 12 步）→ afterRefresh（空实现）→ started + Runner + ready。

**Q3：自动配置的原理，用一句话说清。**

`@EnableAutoConfiguration` 通过 `@Import` 引入一个 `DeferredImportSelector`，它在所有用户配置解析完之后，读取 classpath 上所有 jar 的 `AutoConfiguration.imports` 名单（3.x，153 条），经 ASM 预过滤筛掉缺类的、按 `before/after` 排序、再跑 `@Conditional` 条件判定，最后把通过的注册成 BeanDefinition。

**Q4：为什么 Boot 3 废弃了 `spring.factories` 里的自动配置？**

四点：一个 key 写错整个文件报废；无法表达模块化拆分（4.0 每个模块自带一份）；一大串逗号分隔字符串不利于 ASM 批量预过滤；IDE 不认识。注意只废了 `EnableAutoConfiguration` 一个 key，`EnvironmentPostProcessor`、`FailureAnalyzer` 等继续用 `spring.factories`。

**Q5：`@ConditionalOnMissingBean` 什么时候会失效？**

三种：① 写在普通 `@Configuration` 上（解析顺序不可控，官方明确只该用在自动配置类）；② 同一个自动配置类里，前一个 `@Bean` 已经被创建（条件只保证自己不创建，不保证整组退让，需要时把条件提到类上）；③ 自动配置类被 `@ComponentScan` 扫到，失去"用户配置先行"的保证。

**Q6：classpath 里没有 `DataSource`，`DataSourceAutoConfiguration` 为什么不会 `NoClassDefFoundError`？**

因为 `@ConditionalOnClass` 的判定走 ASM：读 `.class` 文件的注解元数据，注解值以字符串形式存在，不触发类加载；只做 `canLoad` 判断。前提是类名只出现在注解值里，如果出现在方法签名/返回类型上就会真加载。

**Q7：启动慢怎么定位？**

先看三段账：类加载、单例实例化、容器启动。手段：`/actuator/startup` 看每步耗时；`-Xlog:class+load` 数类加载量；`--debug` 看自动配置判定（但只占 5%，通常不是主因）。优化顺序：删 starter > 减 Bean > 收窄切点 > context-indexer > AOT/CDS > Native。

**Q8：`lazy-initialization` 能开吗？**

能，但要分清场景。它省 30% 启动时间，代价是首次请求慢、错误从"启动失败"变成"运行时报错"、有状态初始化失效。生产上更稳的做法是精确 `@Lazy` 非核心 Bean，而不是全局打开。

**Q9：为什么 `bootstrap.yml` 不生效了？**

Boot 2.4 引入 ConfigData API，配置加载统一走 `spring.config.import`，`bootstrap.yml` 不再自动加载。恢复方式：加 `spring-cloud-starter-bootstrap` 依赖，或改成 `spring.config.import: optional:nacos:xxx.yml`。注意 `optional:` 不能省，否则配置中心不可用会直接启动失败。

**Q10：`@ConfigurationProperties` 和 `@Value` 怎么选？**

`@ConfigurationProperties` 支持松散绑定（`max-size` → `maxSize`）、批量绑定、JSR-303 校验、IDE 元数据提示、可读性好的嵌套结构；`@Value` 只能单个注入，支持 SpEL。三个以上相关配置项就用 `@ConfigurationProperties`，这是规范不是偏好。

**Q11：怎么排除一个自动配置？**

四种：`@SpringBootApplication(exclude=...)`、`excludeName`（避免编译依赖）、`spring.autoconfigure.exclude` 配置项（可按 profile 排除）、自定义 `AutoConfigurationImportFilter`。前三种都在"去重"阶段生效，早于条件判定，所以不会出现在 `--debug` 的 Negative matches 里。

**Q12：写一个 starter 要几步？**

五步：① 建两个模块（`xxx-spring-boot-starter` 空壳 + `xxx-spring-boot-autoconfigure` 代码）；② 写 `@ConfigurationProperties` 类；③ 写 `@AutoConfiguration` 类，带 `@ConditionalOnClass` + `@ConditionalOnProperty` + `@ConditionalOnMissingBean`；④ 在 `META-INF/spring/...AutoConfiguration.imports` 里登记类名；⑤ starter 的 pom 里依赖 autoconfigure 模块和第三方库。

**Q13：Boot 2.6 之后循环依赖为什么起不来了？**

`spring.main.allow-circular-references` 默认改为 false。字段/setter 注入的循环以前靠三级缓存兜住，现在直接抛 `BeanCurrentlyInCreationException`；构造器注入的循环任何版本都不行。修复：拆服务（首选）、`@Lazy` 打断、重新设计依赖方向。

**Q14：`ContextRefreshedEvent` 和 `ApplicationReadyEvent` 有什么区别？**

`ContextRefreshedEvent` 在 `finishRefresh` 里发出，此时 Tomcat 已 start、Bean 全就绪，但 Runner 未执行；`ApplicationReadyEvent` 在 `callRunners` 之后发出，代表"完全就绪"。K8s readiness 探针应对应后者，否则会出现"服务已监听但依赖尚未初始化完成"的 502。

---

## L12 三道模拟面试

### Q1 设计一个分布式限流 starter：接入方加一行依赖 + 三行配置就能用

**考察点**：自动配置类设计 / 条件装配 / 属性绑定 / 与既有组件的顺序关系

**及格**：写一个 `@Configuration` 类，里面 `@Bean` 一个 `RateLimiter`，配置项用 `@Value` 注入，告诉用户"手动 `@Import` 一下"。

**良好**：补齐 starter 骨架——两个模块、`@ConfigurationProperties(prefix="app.ratelimit")`、`@ConditionalOnProperty` 做开关、`@ConditionalOnMissingBean` 让用户可以自定义实现、在 `AutoConfiguration.imports` 里登记。会说"用户加依赖就生效，不用改代码"。

**优秀**：再补四层。① **条件分层**：`@ConditionalOnClass(RedisTemplate.class)` 决定用 Redis 实现还是本地令牌桶，两个实现放在两个自动配置类里，用 `@AutoConfiguration(after = RedisAutoConfiguration.class)` 保证顺序，靠 `@ConditionalOnBean`/`@ConditionalOnMissingBean` 二选一。② **失败策略**：Redis 不可用时不能把业务打死，`CacheProperties` 里加 `fail-open`（默认 true），限流器内部 `try/catch` 后放行并打点——这个设计决策比代码本身更能体现工程判断。③ **可观测**：注册 `MeterBinder` 暴露 `ratelimit.passed/rejected` 计数，接 Actuator。④ **顺序陷阱**：如果要拦 `HandlerInterceptor`，必须用 `WebMvcConfigurer` 注册，且自动配置类要 `@AutoConfiguration(after = WebMvcAutoConfiguration.class)`，否则拦截器不生效——这是自研 starter 最高频的"代码没错但不生效"。

---

### Q2 老系统从 Boot 2.7 升到 3.2，启动直接失败，你的排查顺序是什么

**考察点**：大版本迁移的失败模式 / 依赖冲突定位 / 兼容性判断

**及格**：先看启动日志第一段异常，按报错改代码；重点提 `javax` → `jakarta` 换包。

**良好**：给一个有序的检查清单，而不是"看日志"。① 依赖树 `mvn dependency:tree` 找还在引 `javax.*` 的老版本库；② 编译期就报错的直接换包名（`javax.servlet` → `jakarta.servlet`、`javax.persistence` → `jakarta.persistence`、`javax.validation` → `jakarta.validation`）；③ 检查自研 starter 的 `spring.factories` 是否还在写 `EnableAutoConfiguration`；④ 检查 `bootstrap.yml` 是否还在用（2.4 起就不加载，升级时经常被"顺手"改坏）；⑤ 检查配置项改名（`spring.redis.*` → `spring.data.redis.*`，`management.metrics.*` → `management.*.metrics.*`）。

**优秀**：再补三点。① **JDK 门槛**：Boot 3 要求 JDK 17+，很多项目升级失败根本不是 Boot 的问题，是还跑在 JDK 8/11 上，先确认 `java -version` 和 `maven.compiler.release`。② **定位手段**：用 `mvn dependency:tree -Dincludes=javax.*` 精确定位"谁还在拖 javax"；`--debug` 看自动配置大面积 Negative 是不是因为某个 `@ConditionalOnClass` 的类不存在（往往是依赖被 exclude 掉了）。③ **灰度策略**：不要一次性全量升，先升一个非核心服务跑一周，把 `FailureAnalyzer` 的报错收集成清单，再批量迁移。同时准备好回滚——Boot 2.7 是 2.x 最后一个版本，升级是不可逆的架构决策，不是"改个版本号"。

---

### Q3 一个巨型单体启动要 40 秒，K8s 滚动发布时经常超 readiness 探针被 kill，怎么优化

**考察点**：启动耗时归因 / 探针语义 / 优化手段的取舍

**及格**：加大探针的 `initialDelaySeconds`，或者提高 `failureThreshold`，先让它别被 kill。

**良好**：先归因再动手。用 `/actuator/startup` 拿到每步耗时（需要 `buffering` 配置），把 40 秒拆成类加载 / 单例实例化 / 数据源 / 缓存预热 / Runner 五段。经验上巨型单体里"缓存预热"和"Runner 里的全量加载"经常占一半以上。然后针对性处理：预热改异步、Runner 里的重活挪到 `ApplicationReadyEvent` 之后的线程池、删掉不用的 starter。

**优秀**：把问题拆成三个独立问题。① **启动太慢**（40s）：删 starter、`spring-context-indexer`、AOT + AppCDS，通常能砍到 15~20s；剩余部分靠异步化预热——注意预热不能阻塞 readiness，但必须让 readiness 在预热完成前返回 DOWN，否则流量打进来照样 500。② **探针语义错**：`initialDelaySeconds` 是"延迟多久开始探"，`startupProbe` 才是"给多久启动时间"的正解——用 `startupProbe`（`failureThreshold × periodSeconds = 60s`）覆盖慢启动，同时 `livenessProbe` 保持严格（3 次失败就重启），这样"慢"和"死"被区分开，滚动发布不会被误杀。③ **架构层面**：40 秒启动说明这个单体已经不适合"频繁滚动发布"的模型了，短期靠 `startupProbe` + 预热异步化撑住，中期拆出高频变更的模块独立部署（启动 8 秒的 3 个服务比启动 40 秒的 1 个服务好运维），长期才考虑 Native Image（构建 10 分钟换启动 0.1 秒，只对需要快速扩缩容的部分值得）。

---

## 下期预告

**分库分表与不停机数据迁移**——为什么单表 2000 万行是经验值而不是定律（B+ 树三层容纳量与行宽的真实关系）、分片键选错后哪些 SQL 会炸（跨库 join / 跨库分页 / 非分片键查询的四种兜底）、路由算法从取模到一致性哈希再到基因法的演进、以及"双写 + 影子表 + 校验补偿"这套不停机迁移的完整时序。第 05 期把单库索引算到了 B+ 树的三层高度，这期把镜头再拉远一层。
