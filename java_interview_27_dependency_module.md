# Java 面试深挖 27 · 依赖冲突、Classpath 与模块化边界

> 本期正面回答一个比"背 GC 参数"更接近日常工作的题目：**为什么代码昨天还能跑，今天启动就抛 NoSuchMethodError**。答案藏在 JVM 的类身份定义里——它只认全限定类名，不认版本。顺着这条线，一路摸到 Maven 的三条调解规则、Spring Boot 那层套娃 fat jar，以及 JPMS 的强封装到底卡住了谁。
>
> 前 26 期把语言、JVM、并发、框架、存储都过了一遍，唯独漏了一件事：这些代码是怎么被"装"进 JVM 的。这一期补上。文中每一条报错都来自本机真跑（JDK 21.0.7），包括那句能让人愣三秒的 `Cannot cast com.demo.Client to com.demo.Client`。

---

## L0 先给结论：JVM 不认识版本

Java 的类身份是一个二元组：**（定义它的 ClassLoader，全限定类名）**。这个二元组里没有版本，没有 jar 名，也没有 Maven 坐标。`com.demo.Client` 就是 `com.demo.Client`，v1 和 v2 在 JVM 眼里是同一个名字的两个候选实现，选谁取决于**谁先被找到**。

由此推出三条铁律，后面所有现象都是它们的推论：

| 铁律 | 内容 | 后果 |
|---|---|---|
| 一、扁平 | 同一个 ClassLoader 把整个 classpath 当成一个没有目录层级的抽屉，包名只是名字的一部分 | 包名不构成隔离 |
| 二、先到先得 | 同名类只会被 defineClass 一次，后面那个永远不会被加载 | 不报错，安静消失 |
| 三、延迟解析 | 编译期只记符号引用，真正找方法是在第一次执行到那条字节码时 | 故障常在运行几分钟后才出现 |

面试里最容易被追问的一句是：**"那如果两个 jar 都声明了同一个版本范围的依赖，Maven 怎么处理？"**——Maven 处理的是坐标，只保证 classpath 上出现一个版本；它管不了 jar 内部到底有没有那个类。这就是为什么 `dependency:tree` 看起来很干净，线上照样 `NoClassDefFoundError`。

一句话带走：依赖冲突之所以难，不是因为它复杂，而是因为**它的报错信息和真实原因隔了三层**——报错说"找不到方法"，真实原因是"classpath 上那个 jar 是旧版"，而旧版为什么在，要回到 Maven 的调解树。

---

## L1 实测：一行 classpath 顺序，四种不同结局

先把最小可复现的现场搭出来。两个 jar 提供同一个类，唯一区别是 v2 多一个方法 `helloV2`。业务代码**用 v2 编译**，运行时把 v1 排到 classpath 前面。

```java
// 两个 jar 里都是 com.demo.Client，只有 v2 有 helloV2
public class Client {
    public static final String TAG = "v1";        // v2 里是 "v2"
    static { System.out.println("[Client static init] loaded from v1"); }
    public String hello(String n) { return "v1:hello " + n; }
}
// v2 额外有：public String helloV2(String n)

public class App {
    public static void main(String[] args) {
        System.out.println("TAG=" + Client.TAG);
        Client c = new Client();
        System.out.println(c.hello("a"));
        System.out.println(c.helloV2("b"));   // 只有 v2 有
    }
}
```

用 `javac -cp client-v2.jar` 编译通过（编译器看到的是 v2）。然后换两种运行顺序：

```text
=== 运行：v1 在前 ===         java -cp "appout;client-v1.jar" App
TAG=v2
[Client static init] loaded from v1
v1:hello a
Exception in thread "main" java.lang.NoSuchMethodError:
  'java.lang.String com.demo.Client.helloV2(java.lang.String)'
        at App.main(App.java:7)

=== 运行：v2 在前 ===         java -cp "appout;client-v2.jar" App
TAG=v2
[Client static init] loaded from v2
v2:hello a
v2:helloV2 b
```

这段输出里有三个细节，每一个单独拿出来都是面试题：

**细节一：`TAG=v2`，但静态块打印的是 `loaded from v1`。** 因为 `static final String` 是编译期常量，`javac` 在编译 `App` 时就把 `"v2"` 这个字面量写进了 App.class 的常量池，运行期根本不去读 `Client.TAG` 这个字段。所以"我把配置常量改了、重新打包、为什么线上不生效"——因为调用方没重新编译。修法：别用 `static final` 存可变配置，或者确保全量重编（Maven 增量编译在这里经常坑人）。

**细节二：静态块只打印了一次。** 同一个 ClassLoader 下，defineClass 有缓存，同名类只有一份 Class 对象。所以"两个版本共存"在单 loader 的 classpath 世界是个伪命题——不是共存，是覆盖。

**细节三：不是启动就炸，而是执行到第 7 行才炸。** App.class 里存的是指向 `Client.helloV2` 的符号引用（常量池里的 Methodref），JVM 采用惰性解析：第一次执行 `invokevirtual` 时才去目标类里找方法，找不到才抛 `NoSuchMethodError`。这就是"服务跑了 40 分钟，某个低频接口第一次被调用时报错"的机制来源。

```text
classpath 是一只扁平抽屉：包名只是名字，不构成隔离

  三个 jar 依次声明            ClassLoader 的命名空间（只有一个）
  ┌──────────────┐            ┌──────────────────────────────┐
  │ client-v1.jar│──┐         │ com.demo.Client      ← 只认这一个 │
  ├──────────────┤  │  摊平    ├──────────────────────────────┤
  │ client-v2.jar│──┼───────▶ │ com.demo.Client(v2)  ← 永不被加载 │
  ├──────────────┤  │         ├──────────────────────────────┤
  │ util-3.1.jar │──┘         │ com.util.Helper      ← 无争议    │
  └──────────────┘            └──────────────────────────────┘

三种结局：① 方法不存在 → NoSuchMethodError
          ② 行为变了   → 静默错误（最贵）
          ③ 常量内联   → 改了也不生效
          都不报"版本冲突"，全靠人推
```

**为什么"加个 jar 就好了"这种修法很危险**：它把 v2 放到 v1 前面，看起来修好了第一处报错，但所有**没报错**的调用点也跟着换成了 v2 的实现——包括那些你以为没动的业务。日志里不会有一行异常，只有报表数字慢慢歪掉。这类问题平均发现时间以"天"计，而 `NoSuchMethodError` 只要几秒。

正确做法是反查"谁把 v1 带进来了"（见 L4），排除掉，而不是盖上去。

---

## L2 七种症状对照：从 NoSuchMethodError 到 Cannot cast X to X

依赖问题的坑在于：**同一个根因，报出来的异常完全不同**，而每种异常都有更常见的"正常原因"。所以面试时能把异常和时机对上号，比背异常名有用得多。

| 异常 | 抛出时机 | 真实含义 | 最典型场景 |
|---|---|---|---|
| NoSuchMethodError | 首次执行调用指令 | 类找到了，方法签名对不上 | 编译用新版、运行用旧版 |
| NoSuchFieldError | 首次读写字段 | 字段被删/改名/改类型 | 常量类被重构后未全量重编 |
| NoClassDefFoundError | 首次触发类初始化 | 编译期存在、运行期找不到；或静态块上次抛过异常（附带 Could not initialize class） | scope 写成 provided、打 fat jar 时漏包 |
| AbstractMethodError | 首次调用接口方法 | 接口新增了方法，实现类是旧版 | 接口 jar 升级、实现 jar 未升级 |
| IncompatibleClassChangeError | 首次使用该类 | class 与 interface 互转、静态/实例成员错位 | SLF4J 1.7（class）→ 2.0（interface）混用 |
| ClassCastException: Cannot cast X to X | 强制转换时 | 同名类被两个 ClassLoader 各定义了一次 | 容器、插件、agent、热部署 |
| LinkageError: loader constraint violation | 链接期 | 两个 loader 定义了同名类，在下游共享类型处冲突 | Tomcat 的 webapp loader 与 shared loader |

最后两条是"看起来最不像 bug 的 bug"。用一段 20 行的代码就能复现：同一个 jar，交给两个互不相干的 URLClassLoader 各加载一次。

```java
URL u = new File("client-v2.jar").toURI().toURL();
URLClassLoader l1 = new URLClassLoader(new URL[]{u}, null);   // parent = null，故意断开父子
URLClassLoader l2 = new URLClassLoader(new URL[]{u}, null);
Class<?> c1 = l1.loadClass("com.demo.Client");
Class<?> c2 = l2.loadClass("com.demo.Client");
System.out.println("c1 == c2 ? " + (c1 == c2));
Object o = c1.getDeclaredConstructor().newInstance();
c2.cast(o);      // 类型完全一样，cast 却失败
```

```text
c1 == c2 ? false
c1 loader=java.net.URLClassLoader@2ff4acd0
c2 loader=java.net.URLClassLoader@54bedef2
[Client static init] loaded from v2
CAST FAILED -> Cannot cast com.demo.Client to com.demo.Client
```

`Cannot cast com.demo.Client to com.demo.Client`——第一次见的人会以为是 JDK 的 bug，其实是类身份的精确表述：类型检查比的是 Class 对象引用，而 Class 对象里记着 loader。**同一个字节数组，被两个 loader 各 defineClass 一次，就是两个类型。**

为什么这条对面试特别加分：它能一路解释到"为什么 Tomcat 里两个 webapp 的同名类不能互相传对象""为什么 Spring Boot DevTools 热重启后会 ClassCastException""为什么 Arthas 的 watch 增强过的类和没增强的不是同一个类"。一个知识点串起三四个场景。

```text
同一个根因，五种抛出时机：越晚出现，越难查

  javac        加载         链接·解析       首次执行        类型转换
    ●────────────●────────────●──────────────●──────────────●──▶
 符号引用       findClass     loader 约束    invokevirtual   checkcast
 写进 class     找不到

 ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
 │ 不报错    │ │NoClassDef│ │LinkageErr│ │NoSuchMet │ │Cast 同名 │
 │ 编译只认  │ │启动即失败│ │两 loader │ │可能几个月│ │最迷惑    │
 │ 签名      │ │好查 5min │ │中查      │ │后才爆    │ │自相矛盾  │
 │ 埋雷阶段  │ │缺 jar    │ │verbose   │ │低频分支  │ │易误判JDK │
 └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘

排查顺序：先看 -verbose:class 确认到底哪个 jar 被加载了，再回去看 Maven 树。
反过来（先改 pom 试版本）是最常见的错误顺序——它把"定位"换成了"试错"。
```

---

## L3 Maven 的三条调解规则与五张牌

Maven 不解决"类"的问题，它只保证一件事：**同一个 groupId:artifactId 在 classpath 上只出现一个版本**。选谁，由三条规则按顺序裁决。

| 规则 | 内容 | 易错点 |
|---|---|---|
| 一、最短路径优先 | 依赖树里深度小的赢。直接依赖（深度 1）永远压过传递依赖（深度 2+） | 与版本高低无关 |
| 二、同深度先声明 | 深度相同时，POM 里写在前面的人赢 | "调换 dependencies 顺序能改行为"是真的 |
| 三、管理段强制 | dependencyManagement 里声明的版本覆盖以上两条，对传递依赖也生效 | BOM 靠这条生效 |

```text
同一棵树上三个候选：谁赢取决于"深度"和"声明顺序"

                        ┌──────────────────┐
                        │  my-app（根）     │
                        └────────┬─────────┘
              ┌──────────────────┼──────────────────┐
    ┌─────────▼────────┐ ┌───────▼────────┐ ┌───────▼────────┐
    │ lib-a 1.0 depth1 │ │ lib-b 1.0      │ │ lib-c 1.0      │
    └─────────┬────────┘ └───────┬────────┘ └───────┬────────┘
              │                  │                  │
    ┌─────────▼────────┐ ┌───────▼────────┐ ┌───────▼────────┐
    │ core 2.0 depth 2 │ │ core 1.5       │ │ core 1.5       │
    └──────────────────┘ └────────────────┘ └────────────────┘

并列 depth 2 → 比"谁先声明"
  lib-a 写在 lib-b 前面 ⇒ core 2.0 胜（与版本高低无关）
若管理段声明 core 1.5 ⇒ 三条规则全部让位，1.5 强制生效
```

注意左下那句：**版本高低完全不参与裁决**——1.5 明明更旧、却有两条路径指向它，仍然输给唯一路径的 2.0。这是被问得最多、也最容易答错的一条。

除了三条规则，还有五张"手动牌"，用途完全不同，能分清就是加分项：

| 手段 | 作用 | 典型误用 |
|---|---|---|
| dependencyManagement | 只**约束**版本，不引入依赖；对传递依赖同样生效 | 以为写了就会把 jar 加进来，结果编译报"找不到包" |
| exclusions | 从某条依赖里**按坐标**摘掉传递项 | 以为能按包名排除；同一 groupId 下多个 artifact 要各写一条 |
| optional=true | 自己不传递，下游要用得自己声明 | 以为能"藏起"依赖，结果下游启动就 NoClassDefFoundError |
| scope | 控制可见性 + 传递性 | 把运行期必须的包写成 provided，本地能跑、容器炸 |
| import + BOM | 把一整套版本搬进管理段 | 忘了 `<type>pom</type>` 与 `<scope>import</scope>` 两个都要写 |

scope 的传递矩阵是必背的六行，它决定了"本地 IDE 能跑、打出来的包却缺类"：

| 本项目 \ 下游 | compile | runtime | test | provided | 编译可见 |
|---|---|---|---|---|---|
| compile | ✓ | ✓ | ✗ | ✗ | ✓ |
| runtime | ✓ | ✓ | ✗ | ✗ | ✗ |
| test | ✗ | ✗ | ✗ | ✗ | ✓ |
| provided | ✗ | ✗ | ✗ | ✗ | ✓ |
| system（已废弃） | ✓（需 systemPath） | ✗ | ✗ | ✗ | ✓ |

记住一行就够：只有 compile 和 runtime 会往下游传递；**provided 不传递**——这正是"本地能跑、部署缺类"最常见的原因（容器提供了 servlet-api，你的下游却以为它还在）。

一个常被追问的细节：`dependencyManagement` 到底管不管传递依赖？管。只要坐标匹配，**无论它出现在树的多深**，都会被强制改成管理段里的版本。所以 BOM 才能一次锁住上百个包：它不是逐条排除，而是"凡匹配即强制"。

---

## L4 四道防线：让冲突在"发生之前"被拦住

面试里问"你怎么排查依赖冲突"，八成人只答得出 `mvn dependency:tree`。这只回答了"怎么查"，没回答"怎么不让它发生"。真正的工程答案是四道防线：

| 防线 | 手段 | 能拦住什么 | 漏什么 |
|---|---|---|---|
| 编译/构建期 | enforcer 的 bannedDependencies / dependencyConvergence | 黑名单包、同坐标多版本未收敛 | 拦不住"版本收敛了但 jar 里没这个类" |
| 打包期 | shade + relocation | 两个库依赖同一第三方包的不同版本，把其中一个改名隔离 | 打平会丢 META-INF/versions |
| 测试期 | ArchUnit | 层次越界、跨模块引用 internal 包 | 需要人先写规则 |
| 运行期 | -verbose:class / -Xlog:class+load | 事后定位"到底是哪个 jar 被加载了" | 只能事后，日志量大 |

最有用的一条命令，是把"谁把旧版带进来了"直接问出来——注意 `-Dverbose`，它会显示被 omitted for conflict 的分支，那才是真相：

```bash
# 只显示某个坐标的所有路径（含被裁决掉的分支）
mvn dependency:tree -Dverbose -Dincludes=com.google.guava:guava

# 输出形如：
# [INFO] +- com.foo:bar:jar:1.0:compile
# [INFO] |  \- (com.google.guava:guava:jar:31.1-jre:compile - omitted for conflict with 32.1.3-jre)
# [INFO] \- com.google.guava:guava:jar:32.1.3-jre:compile

# 检查同一坐标是否被重复声明
mvn dependency:analyze-duplicate

# 收敛失败就构建失败：适合放进 CI
mvn enforcer:enforce -Drules=dependencyConvergence
```

还有一个只在真实项目里才会遇到的坑：**Spring Boot 的版本仲裁有两套入口，优先级不同**。`spring-boot-dependencies` 把上千个版本写成了 Maven 属性（`<guava.version>` 这种），你在自己 pom 里重定义属性只对 BOM 内部生效；要覆盖某个包的版本，正确做法是在自己的 `dependencyManagement` 里显式写坐标——它的优先级高于 BOM 的属性替换。

```text
四道防线：越往左越便宜，越往右越接近真相

 ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
 │ ① 构建期      │ │ ② 打包期      │ │ ③ 测试期      │ │ ④ 运行期      │
 │ enforcer     │ │ shade        │ │ ArchUnit     │ │ verbose:class│
 │              │ │              │ │              │ │              │
 │ 禁黑名单      │ │ 允许共存      │ │ 守依赖方向    │ │ 事后取证      │
 │ 成本：配一次  │ │ 成本：包变大  │ │ 成本：写规则  │ │ 成本：日志几个G│
 │ 盲区：jar不全 │ │ 盲区：丢 MR   │ │ 盲区：只管自己│ │ 价值：能证伪  │
 └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘

① 抓"不该出现" ② 抓"必须共存" ③ 抓"方向不对" ④ 抓"到底是谁"
一句话：冲突要在还没人写代码的时候就变红（CI 挂掉），而不是等
线上某个低频接口第一次被调用时才变红（凌晨三点）。
```

---

## L5 Spring Boot 的套娃 jar：为什么 java -cp app.jar 跑不起来

这是一个非常好的"分层理解"考点。执行 `mvn package` 后，Spring Boot 的产物长这样：

```text
可执行 jar 的三层结构：类不在根目录，这在 classpath 眼里等于不存在

 ┌────────────────────────────────────┐   ┌──────────────────────────────┐
 │ app.jar                            │   │ MANIFEST 里两个 Main 的分工   │
 │ ┌────────────────────────────────┐ │   │ Main-Class:  ...JarLauncher  │
 │ │ META-INF/MANIFEST.MF           │ │   │ Start-Class: com.demo.App    │
 │ ├────────────────────────────────┤ │   │ java -jar 只认 Main-Class；   │
 │ │ BOOT-INF/classes/   ← 你的类    │ │   │ Start-Class 是 Boot 的约定    │
 │ ├────────────────────────────────┤ │   └──────────────────────────────┘
 │ │ BOOT-INF/lib/       ← 几百个 jar│ │
 │ │  spring-core / jackson / tomcat │ │   ┌──────────────────────────────┐
 │ │  每一个都是完整 jar，不是解开的类│ │   │ 为什么 java -cp app.jar 必失败 │
 │ ├────────────────────────────────┤ │   │ classpath 只认 jar 根目录下的  │
 │ │ org/springframework/boot/loader │ │   │ com/demo/App.class，而它在     │
 │ └────────────────────────────────┘ │   │ BOOT-INF/classes 里 → CNFE     │
 └────────────────────────────────────┘   └──────────────────────────────┘

JarLauncher 做的事：用自定义 URLStreamHandler 把「jar 里的 jar」变成可读的 URL，
再交给 LaunchedURLClassLoader —— 标准 URLClassLoader 读不了嵌套 jar，这是必须自研的原因。
```

由此推出三个高频追问的答案：

- **"为什么我的 Boot 可执行 jar 不能被别的模块依赖？"** 因为类在 `BOOT-INF/classes`，不在 jar 根目录，javac 找不到。要用 `spring-boot-maven-plugin` 的 `classifier=exec` 保留一份普通 jar，或者用 repackage 留下的 `.original`。
- **"BOOT-INF/lib 里几百个 jar，同名类怎么裁决？"** 同样是先到先得：LaunchedURLClassLoader 按 BOOT-INF/lib 里 jar 的名字排序遍历。**注意这一点和 Maven 调解无关**：Maven 决定"放哪个版本进来"，loader 决定"先看哪个 jar"，两层各管一段。
- **"怎么外挂一个不在包里的驱动？"** 用 PropertiesLauncher + `loader.path`，它支持目录与通配符，是 Boot 里少见的"运维友好"入口。

---

## L6 JPMS：模块三个字，其实是三张清单

JDK 9 引入模块系统，十年来真正落地的项目不多——但它**对所有人都有影响**，因为 java.base 自己被模块化了，强封装从 JDK 17 起彻底生效。所以哪怕你的项目完全不用 module-info，你也得知道"为什么 JDK 17 之后反射 String 的内部字段会报错"。

`module-info.java` 里的关键字看着多，本质只有三张清单：**我读谁**、**我给谁看**、**我给谁反射**。第四组 uses/provides 是 SPI 的模块化写法，属于加分项。

```text
module-info 三张清单：读谁 / 给谁看 / 给谁反射

 module com.lib {
   requires com.base;                ← 清单① 我读谁（编译期+运行期都要在）
   requires transitive com.api;      ← 读，并且传给下游（"我也需要"）
   exports com.lib.api;              ← 清单② 我给谁看（导出 ≠ 能反射）
   opens com.lib.entity to com.orm;  ← 清单③ 我给谁反射（setAccessible 要它）
   uses com.spi.Codec;               ← SPI：我是消费者
   provides com.spi.Codec with com.lib.JsonCodec;   ← SPI：我是提供者
 }

 三句话对齐：
   requires 不写   → 编译期报 package ... is not visible（硬门槛）
   exports 只导出  → 别人能 import、能 new，但 setAccessible 仍然失败
   opens 只 opens  → 别人不能 import，但能反射进去（给 ORM 用）
```

实测：两句话就把"强封装"讲清楚了。

先造一个模块，只导出 `com.lib.api`，另一个包 `com.lib.internal` 故意不导出。让 `com.app` 去用它：

```text
Main.java:3: error: package com.lib.internal is not visible
import com.lib.internal.Secret;
              ^
  (package com.lib.internal is declared in module com.lib, which does not export it)
1 error
```

这不是运行时错误，是**编译期**就被挡了。模块系统最有价值的地方就在这：它把"你这个包是内部实现，别用"从注释约定变成了编译错误。

更难理解的是反射那条。同样是 `com.app`，去反射 JDK 内部的 `String.value`：

```text
# 以命名模块运行（module-path）
java.lang.reflect.InaccessibleObjectException: Unable to make field private final
byte[] java.lang.String.value accessible: module java.base does not "opens java.lang"
to module com.app

# 以 classpath 运行（unnamed module）
java.lang.reflect.InaccessibleObjectException: Unable to make field private final
byte[] java.lang.String.value accessible: module java.base does not "opens java.lang"
to unnamed module @2ff4acd0
```

两条文案只差最后半句，但它精确区分了两种身份：**你的代码是命名模块，还是 unnamed module**（classpath 上的一切都被归入 unnamed module）。这决定了 `--add-opens` 后面该写谁：

```bash
# 命名模块：目标模块要写你自己的模块名
java --add-opens java.base/java.lang=com.app --module-path out -m com.app/com.app.Peek

# classpath：写 ALL-UNNAMED
java --add-opens java.base/java.lang=ALL-UNNAMED -cp out com.app.Peek

# 验证结果（加上之后）
length=5 -> setAccessible OK
```

三个容易记反的点：

1. **`--add-opens` 与 `--add-exports` 不能互相替代。** 前者放宽反射（setAccessible），后者放宽编译期可见性（import 与直接调用）。给 Jackson 加 `--add-exports` 是无效的，它要的是 opens。
2. **`--illegal-access` 已经死了。** 本机实测：`Ignoring option --illegal-access=permit; support was removed in 17.0`。JDK 9 默认 permit（只警告）、JDK 16 默认 deny、JDK 17 删掉选项——所以"升到 17 之后一堆框架报 InaccessibleObjectException"不是框架退步，是这条路被拆了。
3. **强封装只针对 JDK 自己的模块和显式模块化的库。** 你自己放在 classpath 上的 jar（unnamed module）内部互相反射，一切照旧。

```text
强封装的四级台阶：从"只警告"到"只剩 --add-opens"

  JDK 9~15        JDK 16          JDK 17 起        今天
    ●───────────────●───────────────●───────────────●──▶
 ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐
 │ permit默认 │  │ deny 默认  │  │ 选项被删除 │  │ Spring    │
 │ 能反射只警告│  │ 需显式permit│  │ 只剩 add-  │  │ Boot 仍跑 │
 │ 框架密集使能│  │ 大量项目爆 │  │ opens/exports│ │ classpath │
 └───────────┘  └───────────┘  └───────────┘  └───────────┘

一句话：JDK 9 说"我要开始关门了"，JDK 16 把门关上，JDK 17 把钥匙扔掉。
这不是兼容性事故，是设计意图——尤其对"读 String.value 做缓存优化"这类黑魔法。
```

---

## L7 split package：模块路径上最硬的一堵墙

classpath 允许一百个 jar 都有 `com.dup` 这个包（L1 的扁平抽屉）。模块路径不允许——模块系统的基本假设是"**一个包只能属于一个模块**"，因为包是 exports 的最小单位，如果两个模块都导出同名包，那"这个包该听谁的"就没有答案了。

实测是这么报的（all.use 同时 requires 两个都含 com.dup 的模块）：

```text
module-info.java:1: error: module all.use reads package com.dup from both dup.one and dup.two
module all.use { requires dup.one; requires dup.two; }
^
1 error
```

注意它甚至不是运行时错误，而是**编译期解析失败**。而如果你绕开编译、直接启动一个已存在的模块，报的是另一条：

```text
Error occurred during initialization of boot layer
java.lang.module.FindException: Module all.use not found
```

第二条更阴——它不告诉你"包冲突"，只说"模块找不到"，因为 boot layer 在解析阶段就整个失败了。问"为什么启动报 Module not found 但 jar 明明在"的时候，答案往往就是某个包被两个模块切了。

```text
同一个包被两个模块切走：classpath 装作没看见，模块路径直接拒绝解析

 classpath（允许，先到先得）
   one.jar  [com/dup/X]  ┐
                         ├──▶  合成一个 com.dup 包（X 与 Y 并存）  →  ✓ 能跑
   two.jar  [com/dup/Y]  ┘

 module path（拒绝解析）
   dup.one  (exports com.dup)  ┐
                               ├──✕  all.use 同时 requires 两者
   dup.two  (exports com.dup)  ┘        →  编译期直接失败

为什么这是迁移的最大障碍：很多老 jar 会"顺手"带一个 javax.annotation /
com.google.common 的影子包，classpath 上相安无事，一上模块路径就两两冲突。

三条出路：
 ① 把冲突包从其中一个模块里 shade 掉（relocation 改名），让它不再叫 com.dup
 ② 放弃模块路径，全放 classpath（Boot 项目的现实选择：一切归入 unnamed module）
```

### 自动模块名：一个连老手都会踩的坑

jar 放到模块路径上却没有 `module-info.class`，它就变成「自动模块」。模块名由**文件名推导**：去掉 `.jar`、把非字母数字换成点、去掉尾部形如 `-1.4.2` 的版本段。看着合理，实测就出问题：

```text
$ jar --describe-module --file mylib.jar
mylib automatic

$ jar --describe-module --file mylib-SNAPSHOT.jar
mylib.SNAPSHOT automatic     # 版本段没被识别，模块名变了
```

| 文件名 | 推导出的模块名 | 结论 |
|---|---|---|
| guava-32.1.3-jre.jar | com.google.common | ✓ 多段版本被识别并剥掉 |
| commons-lang3-3.14.0.jar | commons.lang3 | ✓ 连字符变点 |
| mylib-SNAPSHOT.jar | mylib.SNAPSHOT | ✗ 版本段没识别，名字带进版本 |
| my.lib.v2.jar | my.lib.v2 | ✗ 版本进了名字 |

解法：在 MANIFEST 里写 `Automatic-Module-Name: com.foo.mylib`，名字就固定了，与文件名无关。

这解释了一个真实痛点：**你的 module-info 里写着 `requires mylib;`，某天依赖从 `mylib.jar` 变成 `mylib-SNAPSHOT.jar`，编译突然报 module mylib not found**——因为它的模块名变成了 `mylib.SNAPSHOT`。所以凡是要上模块路径的库，都该显式声明 Automatic-Module-Name。

顺带把一个常混的概念说清：**unnamed module**。classpath 上的一切（所有 jar + 你的 classes 目录）被整体归入一个叫 unnamed module 的虚拟模块。它的特权是：能读所有模块、导出所有包。这就是"我什么 module-info 都没写，为什么还能反射"的答案——不是模块系统放过你，是你压根没进模块系统。反过来，它也有一个致命缺陷：**不能被命名模块 requires**。所以"我们的库想被别人模块化引用"这件事，unnamed module 做不到。

---

## L8 Multi-Release JAR：一份包，多个 JDK

JDK 9 之后很多库要"在 JDK 8 上用 8 的实现，在 17 上用 17 的实现"，但不想发两个 jar。解法是 Multi-Release JAR：把不同版本的 class 放进 `META-INF/versions/N/`，再在 MANIFEST 里加一行 `Multi-Release: true`。

```bash
# 打包：base 是 8 的版本，versions/17 是 17 的版本
javac -d bo base/com/demo/Ver.java
javac --release 17 -d v17o v17/com/demo/Ver.java
mkdir -p jarroot/META-INF/versions/17
cp -r bo/. jarroot/ && cp -r v17o/. jarroot/META-INF/versions/17/
printf 'Multi-Release: true\n' > mf.txt
jar cfm mylib.jar mf.txt -C jarroot .

# 实测：在 JDK 21 上跑
runtime=21  Ver.get()=versioned(17)
```

选择规则一句话：**取 `versions/` 下不超过当前运行版本的最高目录**。所以在 21 上取 17 那份，在 8 上取 base 那份。目录号必须 ≥ 9（9 以下不允许）。

```text
MR-JAR 的选择规则：取「不超过运行版本」的最高目录

 mylib.jar                      运行在 JDK 21
 ┌──────────────────────────┐     8     11      17      21      26
 │ MANIFEST.MF (MR: true)   │     ├──────┼───────┼───────┼───────┤
 ├──────────────────────────┤            ▲候选区（≤21）▲
 │ com/demo/Ver.class  base │
 ├──────────────────────────┤   ┌─────────────────────────────────┐
 │ versions/11/ ...         │   │ 命中 versions/17（候选里最高）    │
 ├──────────────────────────┤   │ JDK 8 → base；11 → 11；17/21 → 17│
 │ versions/17/ Ver.class   │   │ 版本目录 ≤ 8 → 打包直接报错       │
 └──────────────────────────┘   └─────────────────────────────────┘

最大陷阱：shade / assembly 打平依赖时，容易把 META-INF/versions 丢掉，
或把两个版本的同名类塞进同一目录，结果"在 17 上却跑 8 的实现"，且不报错
——症状是"升级 JDK 后性能反而变差"。
```

---

## L9 谁在偷偷要 opens：一张迁移成本清单

「要不要把项目模块化」这个问题，真正卡住的从来不是 module-info 怎么写，而是**你依赖的框架有多少在靠反射活着**。下面这张表是迁移前该先盘的东西（经验清单，不是规范）：

| 组件 | 它要什么 | 为什么 |
|---|---|---|
| Spring（CGLIB 代理） | opens 被代理类所在包 | 运行期生成子类、反射调父类方法；@Transactional 打在类上就触发 |
| Spring（字段注入） | opens 含 @Autowired 字段的包 | Field.set 需要深度访问 |
| Jackson | opens DTO 包 | 反射调 getter/setter；record 的组件访问器也走反射 |
| Hibernate / JPA | opens 实体包 | 无参构造 + 字段直写；字节码增强还要 Instrumentation |
| MyBatis | opens 实体包 | 反射 setXxx 或直接写字段 |
| Mockito（inline） | --add-opens + agent | inline mock maker 直接改字节码 |
| Netty | --add-exports java.base/jdk.internal.misc | PlatformDependent 探测 Unsafe、堆外地址 |
| Lombok | 编译期 --add-exports jdk.compiler/... | 它改的是 AST，跟运行时 opens 无关——**这点最常被搞混** |

看到这张表你就明白为什么 Spring Boot 到今天还是 classpath 模式：把上面这些全搬上模块路径，需要一个几十条 `--add-opens` 的启动脚本，收益却只是"编译期能挡住内部包被引用"——而这件事故完全可以用 ArchUnit 在测试期做到（见 L10）。**投入产出比不成立，不是技术不行。**

面试里这样答，比背概念高一个档：

> "我们没上 JPMS，但不是因为不了解——我评估过：module-info 的收益是编译期强边界和更小的 jlink 镜像；成本是 Spring/Hibernate/Jackson 全家桶的 opens 清单，以及一堆第三方 jar 的 split package 与自动模块名漂移。所以我选了中间路线：用 ArchUnit 守边界、用 enforcer 守依赖、用 -verbose:class 兜底取证，把 JPMS 留给未来的 jlink 场景（比如 CLI 工具、Serverless 冷启动优化）。"

---

## L10 工程化边界：让"不该依赖"变成构建失败

模块系统想解决的"边界"问题，有更便宜的做法。核心只有一句：**依赖方向必须单一，且能被机器检查**。

```java
// ArchUnit：分层守卫，跑在单元测试里，改坏了 CI 就红
@ArchTest
static final ArchRule layers = layeredArchitecture().consideringAllDependencies()
    .layer("Web").definedBy("..controller..")
    .layer("App").definedBy("..service..")
    .layer("Dao").definedBy("..repository..")
    .whereLayer("Web").mayNotBeAccessedByAnyLayer()      // 没人能反向依赖 controller
    .whereLayer("Dao").mayOnlyBeAccessedByLayers("App"); // dao 只给 service 用

// 另一条常用规则：禁止跨模块使用 internal 包
@ArchTest
static final ArchRule noInternalLeak = noClasses()
    .that().resideOutsideOfPackage("..order.internal..")
    .should().dependOnClassesThat().resideInAPackage("..order.internal..");
```

而"到底谁依赖了谁、有没有环"，交给 jdeps：

```bash
# 看模块依赖（模块化项目）
jdeps --print-module-deps --module-path out -m com.app

# 找循环依赖（包级）
jdeps -verbose:package -cp "target/classes;libs/*" target/classes \
  | awk '{print $1" -> "$3}' | sort -u | head -40

# 统计每个包的扇入/扇出，扇出特别大的是"上帝包"
jdeps -summary -cp libs/* target/classes
```

```text
依赖方向单一 + 边界可见：模块化的收益，ArchUnit 也能给

 ┌─────────────────┐        ┌─────────────────┐
 │ order-api       │◀───────│ order-web       │
 │ 只放接口与 DTO   │        │ Controller      │
 │ OrderService    │        │ 只依赖 api 接口  │
 │ CreateOrderCmd  │        │ 不知道 impl 存在 │
 │ 依赖：无        │        └─────────────────┘
 └────────▲────────┘
          │ implements        三条机器能查的边界
 ┌────────┴────────┐         ① 方向：web → api ← impl
 │ order-impl      │         ② 可见：internal 不跨模块
 │ 实现 + internal │         ③ 传递：api 的依赖必须"薄"
 │ 依赖 api + 三方  │
 └─────────────────┘         module-info 只能做 ② 的一部分，
                             ① ③ 仍要靠测试与人工评审
```

---

## L11 七条最容易记反的结论

| 直觉 | 实际 | 为什么 |
|---|---|---|
| 冲突时 Maven 会选版本更高的 | 选**路径短的**；同深度看声明顺序 | 调解只看树形结构，不看版本号大小 |
| dependencyManagement 写了就会引入依赖 | 只约束，不引入 | 它是"版本字典"，不是"依赖清单" |
| exclusions 能按包名排除 | 只能按**坐标**排除 | Maven 在坐标层面工作，不解析 jar 内容 |
| optional=true 只是"可选依赖" | 它**彻底不传递** | 下游必须自己声明，否则运行期缺类 |
| exports 开了就能反射 | 反射要 opens | 两套门禁：编译可见性 vs 深度访问 |
| --illegal-access=permit 还能救一下 | JDK 17 起该选项被删 | 实测输出 Ignoring option ... removed in 17.0 |
| 可执行 fat jar 可以当普通依赖用 | 不能，类在 BOOT-INF/classes | classpath 只认 jar 根目录 |

最后补一个只在生产里才看得到的现象：**UnsupportedClassVersionError 经常被误当成"依赖冲突"**。它的文案是 `class file version 61.0, this version only recognizes up to 55.0`——意思是编译用了 JDK 17（major 61），运行在 JDK 11（认识 55）。所以看到它的第一反应应该是查 `java -version` 与 `maven.compiler.release`，而不是查依赖树。数字对照：Java 8 = 52、11 = 55、17 = 61、21 = 65。

---

## L12 数字速查：这一期值得记的量

| 数字 | 含义 |
|---|---|
| 1 次 | 同一 ClassLoader 下同名类只被 defineClass 一次，之后走缓存 |
| 0 次 | static final 常量在运行期的字段读取次数（编译期已内联） |
| 2 个 | 构成类身份的要素个数：ClassLoader + 全限定名 |
| 7 种 | 依赖问题引发的异常类型（NoSuchMethod 到 loader constraint violation） |
| 3 条 | Maven 调解规则：最短路径 → 同深度先声明 → 管理段强制 |
| 5 张 | 手动牌：dependencyManagement / exclusions / optional / scope / import-BOM |
| 2 种 | 会向下游传递的 scope：compile 与 runtime |
| 4 道 | 防线：enforcer / shade / ArchUnit / -verbose:class |
| 3 层 | Boot 可执行 jar 结构：BOOT-INF/classes + BOOT-INF/lib + loader 包 |
| 2 行 | MANIFEST 里的双 Main：Main-Class=JarLauncher、Start-Class=你的 main |
| 3 张 | 模块清单：requires（读谁）/ exports（给谁看）/ opens（给谁反射） |
| 17 | 从 JDK 17 起 --illegal-access 被彻底移除，只剩 --add-opens |
| 1 个 | 一个包只能属于一个模块——split package 的判据 |
| 3 步 | 自动模块名推导：去 .jar → 非字母数字转点 → 剥尾部版本段 |
| ≥ 9 | META-INF/versions/N 的目录号下限 |
| 52/55/61/65 | Java 8 / 11 / 17 / 21 的 class major 版本号 |

---

## L13 十四条问答

**Q1：线上突然大面积 NoSuchMethodError，但你只改了一个配置，怎么查？**

先确认是不是真的"只改了配置"。两条线索：① 报错的方法在哪个类，用 `-verbose:class`（或 `-Xlog:class+load`）看这个类是从哪个 jar 加载的，路径里的版本号立刻暴露；② 如果最近重打过包，可能是依赖被别的团队间接升级了——`mvn dependency:tree -Dverbose -Dincludes=groupId:artifactId` 看 omitted for conflict 的分支。只有先定位"谁带进来的"，改 pom 才有方向。

**Q2：为什么 static final String 改了不生效？static final 的 int 呢？**

编译期常量（基本类型与 String 字面量）会被 javac 直接内联进调用方的常量池，运行期不读原字段。所以改值后必须**重新编译调用方**。int 也一样内联。反例：`static final String X = UUID.randomUUID().toString()` 不是编译期常量，不会被内联。判断标准就一条：编译器能不能在编译期算出值。

**Q3：两个 jar 都有 com.foo.Bar，JVM 会报冲突吗？**

不会。同一个 ClassLoader 只认先找到的那个，后面的安静消失——这比报错危险得多。会报冲突的场景只有两个：① 两个不同 ClassLoader 各定义一次（报 Cannot cast X to X 或 loader constraint violation）；② 上了模块路径且两个模块都导出该包（报 reads package ... from both）。

**Q4：Maven 会选版本更高的那个吗？**

不会。调解只看树：**路径短的赢**；同深度看 POM 里谁先声明。版本号高低完全不参与。唯一能压过这两条的是 dependencyManagement（对传递依赖也强制生效）。

**Q5：dependencyManagement 写了 guava 32，为什么 tree 里还是 31？**

两种可能：① 坐标写错（比如少了 `<type>`）；② 那个 31 是另一个 groupId 下的同 artifact（`com.google.collections:google-collections` 这种历史包）。第二种在 -Dverbose 下能看到两条不同坐标并列，是最容易被漏掉的一类"假冲突"。

**Q6：为什么 Spring Boot 的 jar 不能用 java -cp 跑？**

类在 `BOOT-INF/classes` 里，而 classpath 只认 jar 根目录下的包路径。真正能跑的方式只有 `java -jar`（走 MANIFEST 的 Main-Class: JarLauncher），由 LaunchedURLClassLoader 用自定义 URLStreamHandler 去读"jar 里的 jar"。衍生结论：这个 jar 也不能被别的项目当依赖。

**Q7：exports 和 opens 的区别，一句话？**

exports 管"编译期能不能 import 与普通调用"，opens 管"运行期能不能 setAccessible 做深度反射"。给 Jackson / Hibernate 加 `--add-exports` 是无效的，它们要的是 opens。

**Q8：为什么升到 JDK 17 之后一堆框架报 InaccessibleObjectException？**

因为强封装分三步走完：JDK 9 默认 `--illegal-access=permit`（只警告）、JDK 16 默认改 deny、JDK 17 直接把该选项删掉（实测提示 Ignoring option ... removed in 17.0）。之前靠"默认放行"活着的老版本框架，在这一步同时失效。应对只有两条：升级框架，或显式 `--add-opens`。

**Q9：你项目为什么不上模块化？**

按收益/成本答：收益是编译期强边界 + jlink 瘦镜像；成本是 Spring / Hibernate / Jackson / MyBatis 一长串 opens 清单，加上第三方 jar 的 split package 和自动模块名漂移。所以我们用中间路线：ArchUnit 守依赖方向、enforcer 守版本收敛、-verbose:class 兜底取证。真需要瘦镜像时（CLI、Serverless）再对那个模块单独模块化。

**Q10：什么是 split package，为什么它挡住迁移？**

同一个包被两个模块各声明一次。模块系统以包为 exports 的最小单位，必须能判定归属，判不出来就整个 boot layer 解析失败。classpath 相反：它会把两个 jar 的同一个包"合成"成一个包，能跑。所以老项目里那些"顺手带了个 javax.annotation 影子包"的 jar，一上模块路径就互相打架，而且报错常常是误导性的 Module ... not found。

**Q11：自动模块名有什么坑？**

它由文件名推导，`-SNAPSHOT` 这类版本段识别不了。实测 `mylib.jar` → `mylib`，而 `mylib-SNAPSHOT.jar` → `mylib.SNAPSHOT`。于是 `requires mylib;` 在换包之后突然编不过。解法：MANIFEST 里写 Automatic-Module-Name，把名字钉死。

**Q12：什么是 Multi-Release JAR？怎么选版本？**

把一个类的多个实现放在 `META-INF/versions/N/`，MANIFEST 加 `Multi-Release: true`。选择规则：从所有 ≤ 当前运行版本的目录里取最大的那个。实测在 JDK 21 上，base 返回 `base(8)`、versions/17 返回 `versioned(17)`，运行时拿到 versioned(17)。坑在打包：shade/assembly 打平依赖会把 META-INF/versions 丢掉，症状是"升了 JDK 却还在跑旧实现"。

**Q13：Cannot cast com.demo.Client to com.demo.Client 怎么解释？**

类型检查比的是 Class 对象身份，而 Class 的身份里含 ClassLoader。同一个 jar 被两个 loader 各 defineClass 一次，就是两个不同的类型，名字一样也没用。复现只要两行：两个 `new URLClassLoader(..., null)` 各 loadClass 同一个类，`c1 == c2` 返回 false。容器、插件框架、热部署、agent 都会造出这种局面。

**Q14：UnsupportedClassVersionError 是依赖冲突吗？**

不是。它是"编译用的 JDK 比运行的高"。文案里的两个数字就是 major 版本：`class file version 61.0`（Java 17 编的）而 `only recognizes up to 55.0`（运行在 Java 11）。对照表：8→52、11→55、17→61、21→65。第一反应查 `java -version` 与 `maven.compiler.release`，不要查依赖树。

---

## L14 三道模拟面试题

### Q1 老服务本地启动正常，打到预发就抛 NoSuchMethodError: com.google.common.base.Preconditions.checkArgument，你怎么定位？

考点不是"怎么改 pom"，而是"能不能按证据链推"。

- **不及格**：「加个 guava 依赖就好了」「把版本升到最新」——没有定位动作，属于试错。
- **及格**：「用 dependency:tree 看 guava 版本，找到冲突的排除掉」。方向对，但没解释"为什么本地正常、预发异常"，也没说怎么确认。
- **良好**：「本地与预发的差异只可能在打包或依赖来源。① 先用 `-verbose:class` 或 `-Xlog:class+load` 确认 Preconditions 实际从哪个 jar 加载，路径带版本号，一眼看出是旧版；② 再 `dependency:tree -Dverbose -Dincludes=com.google.guava:guava` 找 omitted for conflict 的分支，通常是某个中间件 SDK 传递带进来的；③ 用 dependencyManagement 强制版本（比 exclusions 更彻底，因为对全树生效）。」
- **优秀**：在上面基础上补三点：**④** 为什么是 NoSuchMethodError 而不是 NoClassDefFound——类找到了、方法没有，说明是"同 artifact 不同版本"，而不是"jar 缺失"，这直接排除了 scope 写错的可能；**⑤** 为什么可能只在某个低频接口触发——符号引用惰性解析，第一次执行 invokevirtual 才报；**⑥** 收口动作：CI 加 `enforcer:dependencyConvergence`，让同类问题在构建期就红。

### Q2 我们要把一个 SDK 打成可执行 jar 交付给客户，客户又想把我们的 jar 当依赖嵌进他的 Spring Boot 应用。有什么坑？

- **不及格**：「打个 jar 给他就行」——没说清可执行 jar 与普通 jar 的区别。
- **及格**：「可执行 jar 的类在 BOOT-INF/classes 里，不能被依赖，要用 classifier 出两份」。
- **良好**：「出两个产物：xxx-exec.jar（repackage，供客户命令行跑）和 xxx.jar（普通 jar，供依赖）。同时注意：客户是 Boot 应用，我们的 starter 依赖很容易和他的版本打架，所以我们的 pom 要把 Boot 相关依赖设成 provided 或 optional。」
- **优秀**：再补两层：**①** 依赖面收窄——SDK 的公共 API 不应暴露第三方类型（避免把 guava/jackson 的版本强加给客户），必要时用 shade + relocation 把内部依赖改包名彻底隔离；**②** 交付验证——用一个"最小客户工程"在 CI 里真实依赖我们的 jar 并启动一次，这才是唯一能证明"可被依赖"的方式；**③** 如果客户将来模块化，我们的 jar 要带 Automatic-Module-Name，否则名字会随文件名漂移。

### Q3 团队想上 JPMS，让你评估。你怎么给结论？

- **不及格**：「JPMS 是好东西，我们应该上」——没有成本评估。
- **及格**：「收益是强封装和 jlink 瘦镜像；成本是要处理 opens 和 split package」。
- **良好**：「先盘依赖：Spring/Hibernate/Jackson/MyBatis 都靠反射，需要成批 opens；再盘第三方 jar 的 split package 与自动模块名。如果成本高于收益，就先做中间方案：ArchUnit 守依赖方向、enforcer 守版本收敛、api/impl 分包。」
- **优秀**：给出分层结论：**①** 对"库"（被依赖的 SDK）值得模块化——module-info 能让"internal 包别用"从注释变成编译错误，这是它最硬的收益；**②** 对"应用"（Boot 单体/微服务）不值得——启动脚本要挂几十条 `--add-opens`，收益已被 ArchUnit 覆盖；**③** 对特定场景值得——CLI 工具、Serverless 冷启动可以用 jlink 裁到几十 MB；**④** 落地路径是"先库后应用、先新模块后老模块"，并明确一条红线：不允许为了上模块化去 fork 第三方 jar。

---

## L15 二十七期，一张地图

```text
27 期轨道：这一期补的是"代码怎么进 JVM"这一段

 [01·14 集合][23 泛型·反射][03·20 并发][02·12·21·22 JVM][04·15·24 框架][05·16·25 存储][07·08·18 分布式]
                                                    │
                          ┌─────────────────────────┴──────────────────────────┐
                          │ 13 类加载 + 26 字节码增强 + 27 边界                  │
                          │ 从"谁能看见谁"到"看见的是哪个版本"                    │
                          └────────────────────────────────────────────────────┘

 03 讲"线程怎么跑" → 13 讲"类怎么进来" → 26 讲"类进来时怎么被改" → 27 讲"进来的是哪一个"

 这四期连起来是一条完整的装载流水线：读字节码 → 找类 → 定义类 → 增强类 → 解析符号引用。
 面试里只要能把这条线讲通，几乎所有"偶发/环境相关"的诡异 bug 都有解释框架。
```

| 板块 | 期号 · 主题 | 一句话抓手 |
|---|---|---|
| 语言与底层 | 01 · 14 集合 / 23 泛型·反射·代理 | 树化泊松 6e-8、稳态反射只慢 7 倍 |
| | **27 · 依赖冲突与模块化边界** | **类身份 = loader + 全限定名，版本不在里面** |
| 并发 | 03 锁升级·AQS / 20 线程池 / 19 NIO·Netty | ctl 3+29 位、依赖栈 LIFO、epoll 红黑树 |
| JVM | 02 GC / 12 调优 / 21 内存泄漏 / 22 JIT / 13 类加载 / 26 Agent | 三色标记、Eden=速率×间隔、支配树、内联 35/325 |
| 框架 | 04 Spring / 15 Boot / 24 MyBatis | refresh 十二步、153→60→40、九站执行链 |
| 存储 | 05 MySQL / 16 分库分表 / 25 ES | 1170 扇出、2053 万行上限、BM25 可算 |
| 分布式 | 07 MQ / 08 Spring Cloud / 18 幂等 / 10 场景 / 17 缓存 / 06 Redis | ISR 水位、重试 27 倍、0 行双重语义 |

这一期真正的价值：给"诡异 bug"一个解释框架。面试里最难答的从来不是"讲讲 GC"，而是"讲一个你排查过的线上问题"。而线上问题里最难查的一类，就是"代码没改、环境变了"。这一期给的框架是三问：**① 这个类是哪个 jar 提供的（-verbose:class）？② 这个 jar 是谁带进来的（dependency:tree -Dverbose）？③ 它的哪个版本被编译期记住了（常量内联 / 符号引用）？** 三问答完，绝大多数"偶发、环境相关、重启就好"的问题都能收敛到一个具体坐标上。

---

**下期预告 · 第 28 期：Java 与可观测性**

Micrometer 的 MeterRegistry 与指标基数爆炸 / 直方图与分位数为什么不能平均 / OpenTelemetry 的 Trace 上下文传播（含 MQ 与线程池两处断链）/ 日志、指标、链路三者的采样冲突 / "为什么我的 P99 和监控对不上"。这一期会把 08 期的 TraceId 与 09 期的排查串成一条完整的可观测链路。

---

> 本期事实来源：本机 JDK 21.0.7（major 65）与 Maven 3.8.8 实测。L1 的 classpath 覆盖、L2 的双 ClassLoader 强转、L6 的 package is not visible 与 InaccessibleObjectException（命名模块 / unnamed 两种文案）、L7 的 split package 报错、L8 的 MR-JAR 选择结果与自动模块名推导，全部为真实运行输出，报错文案原样复制。--illegal-access 在 JDK 17 被移除为实测提示。JDK 8/11/17/21 的 class major 版本号为规范事实。L3、L4 的 Maven 调解规则与插件行为为文档事实，本机未逐个跑通（maven-dependency-plugin 未安装）。L9 的框架 opens 需求为经验清单，实施前应以目标版本实测为准。
