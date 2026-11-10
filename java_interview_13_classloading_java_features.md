# Java 面试深水区 13 · 类加载机制与 Java 新特性：从 ClassLoader 到虚拟线程

> "讲讲双亲委派"——这题谁都会背："先让父加载器加载，父加载器加载不了自己再加载"。面试官接着问"打破双亲委派有几种方式"，背过的答三种，真做过的会说"四次，而且第四次不是打破，是绕开"。再问"虚拟线程为什么便宜"，答"因为轻量"的人就露馅了——轻量是结果，不是原因；原因是它的栈不再占着操作系统线程的地址空间。
>
> 11 层递进 · 10 张原理图 · 15 段代码 · 14 条高频问答 · 3 道模拟面试

---

## L0 类加载不是"读文件"，是"造类型"

先纠一个概念：ClassLoader 的产物不是"一份字节码"，而是一个**运行时类型**。同一个 `User.class` 被两个不同的 ClassLoader 各加载一次，在 JVM 里就是两个互不相容的类型——`instanceof` 返回 false，强转抛 ClassCastException，尽管它们的字节码一个字节都不差。

### 五个阶段，只有两个能"懒"

```
加载 Loading ──→ 验证 Verification ──→ 准备 Preparation ──→ 解析 Resolution ──→ 初始化 Initialization
  找字节流         格式/语义/字节码/符号引用    静态变量赋零值       符号引用→直接引用      执行 <clinit>
  生成 Klass        （可 -Xverify:none 跳过）   final 常量此时赋值    （可延迟到首次使用）    （严格时机，见 L2）
```

关键点三条：

1. **准备阶段赋的是零值，不是初始值。** `static int a = 10;` 在准备阶段 a=0，到 `<clinit>` 才变 10。但 `static final int A = 10;` 是编译期常量，直接写进常量池，准备阶段就是 10——这就是"为什么 `static final` 常量不触发类初始化"的根。
2. **解析阶段可以延迟**，甚至可以在第一次真正用到某个符号引用时才做（惰性解析），所以"解析"在时间轴上可以插在初始化之后。
3. **只有"初始化"有严格且可枚举的触发时机**，其余阶段都是 JVM 实现自由裁量。面试问"类加载时机"，实际问的是初始化时机。

### 类加载器 = 命名空间，不是"工具类"

用一句话定义：**类加载器是类型的命名空间划分器。** 它决定了"两个类是不是同一个类"。

```java
// 同一个 class 文件，两个加载器，两个类型
ClassLoader l1 = new MyClassLoader("/opt/app/v1");
ClassLoader l2 = new MyClassLoader("/opt/app/v2");
Class<?> c1 = l1.loadClass("com.demo.Plugin");
Class<?> c2 = l2.loadClass("com.demo.Plugin");
System.out.println(c1 == c2);                 // false
System.out.println(c1.isAssignableFrom(c2));  // false
Object o = c2.getDeclaredConstructor().newInstance();
c1.cast(o);                                   // ClassCastException
```

这个特性是所有插件化、热部署、Tomcat 多应用隔离的地基；也是所有 `ClassCastException: com.demo.User cannot be cast to com.demo.User` 这种"见鬼"报错的唯一解释。

**一句话答法：** "类加载器的本质是命名空间，同一个类被不同加载器加载就是两个类型，这是隔离和热部署的基础，也是诡异 ClassCastException 的根因。"

---

## L1 双亲委派：它保护的不是"重复加载"，是"核心类被替换"

### 委托链只有三层

```
Bootstrap ClassLoader（C++ 实现，Java 里表现为 null）
  │  加载 JAVA_HOME/lib/modules 里的 java.base 等核心模块
  ↓
Platform ClassLoader（JDK9 之前叫 ExtClassLoader）
  │  加载 java.sql、java.xml 等部分模块
  ↓
App ClassLoader
  │  加载 -classpath / -cp 指定的业务代码
  ↓
自定义 ClassLoader（可选，parent 默认指向 App）
```

`loadClass` 的逻辑就二十行，但每一行都有用：

```java
protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {     // ① 按类名分段锁，不是锁整个加载器
        Class<?> c = findLoadedClass(name);        // ② 先查自己缓存
        if (c == null) {
            try {
                if (parent != null) c = parent.loadClass(name, false);  // ③ 先问爹
                else c = findBootstrapClassOrNull(name);
            } catch (ClassNotFoundException ignored) { }
            if (c == null) c = findClass(name);    // ④ 爹不行自己来
        }
        if (resolve) resolveClass(c);
        return c;
    }
}
```

**注意 ② 和 ③ 的顺序**：先查自己的缓存，再往上问。这意味着一个类一旦被某个加载器加载过，后续请求直接命中缓存，不会再走委托——所以"打破双亲委派"在已经加载过的类上完全无效，必须换加载器或者重启。这是热部署为什么这么难的第一层原因。

### 它到底防什么

假设没有双亲委派，攻击者写一个 `java.lang.String` 放在 classpath 上，App 加载器一加载，整个 JVM 的 String 就被替换了——所有密码、签名、反射全部可被劫持。双亲委派保证 `java.lang.*` 永远先由 Bootstrap 加载，业务代码根本没机会插手。

所以双亲委派的价值是**安全边界**，"避免重复加载"只是顺带的好处。

### 四次"打破"：前三次是真打破，第四次是绕开

| 序号 | 场景 | 怎么破 | 为什么必须破 |
| --- | --- | --- | --- |
| 1 | JDK 1.2 之前 | 老代码重写 `loadClass` 直接调 `findClass` | 兼容历史代码，属于被迫妥协 |
| 2 | SPI（JDBC/JNDI/JAXP） | 父加载器加载接口，需要回调子加载器加载实现 → 线程上下文类加载器 TCCL | `java.sql.Driver` 在 Bootstrap 里，`com.mysql.cj.jdbc.Driver` 在 classpath 里，爹看不见儿子 |
| 3 | Tomcat / OSGi | 每个 WebApp 一个 `WebappClassLoader`，**先自己找，找不到再问爹** | 两个应用各带 Spring 4 和 Spring 5 必须隔离；但 `jakarta.servlet` 又要共享 |
| 4 | JDK 9 模块化 | 双亲委派改成"委派给负责该包的模块的加载器"，不再按层级 | `java.base` 拆包后，一个类可能不属于父加载器负责的模块，硬按层级走会加载失败 |

第 2 条的机制是面试高频，代码长这样：

```java
// 应用代码只面向 java.sql.Driver 这个接口编程（接口由 Bootstrap 加载）
Connection conn = DriverManager.getConnection(url);

// DriverManager 内部做的事：
//   ServiceLoader<Driver> drivers = ServiceLoader.load(Driver.class);
//   ServiceLoader.load 默认使用 TCCL：
//     Thread.currentThread().getContextClassLoader()
// 于是"爹"借"儿子"的手，把实现类加载了进来

// 容器/框架里手动切换 TCCL 的标准写法
ClassLoader old = Thread.currentThread().getContextClassLoader();
try {
    Thread.currentThread().setContextClassLoader(pluginLoader);
    DriverManager.getConnection(url);          // 这次能找到插件里的驱动
} finally {
    Thread.currentThread().setContextClassLoader(old);   // 必须还原，否则线程池复用时污染下一个任务
}
```

**踩过的坑：** 一个插件框架用自定义加载器加载插件，插件里的 JDBC 驱动注册始终失败，报 "No suitable driver found"。原因是 TCCL 还停在主应用的 App 加载器，`ServiceLoader` 在 App 加载器的可见范围里当然找不到插件包里的 driver。修法只有三行：设 TCCL、执行、还原。还原这一步千万别省——线程池里线程是复用的，不还原就等于给下一个请求埋雷。

四档的差距不在定义，而在"知不知道边界"：

| 档位 | 回答 | 面试官心里的分 |
| --- | --- | --- |
| 不及格 | "先父后子，父加载不了自己加载" | 60，背过 |
| 及格 | 加上"防止核心类被篡改" | 70 |
| 良好 | 能说出 SPI/TCCL 和 Tomcat 两种打破 | 82 |
| 优秀 | 说清 JDK9 后不再是层级委托而是按包归属委派；指出打破对已加载类无效；能讲 TCCL 在线程池里的污染 | 92 |

---

## L2 初始化时机：六个主动、三个被动，和一个死锁

### 必须初始化的六个场景（JVM 规范 5.5）

1. `new`、读写静态字段（非 final 常量）、调用静态方法 → 对应四条字节码指令 `new / getstatic / putstatic / invokestatic`。
2. 反射：`Class.forName("X")`（`initialize` 参数默认 true）。
3. 初始化子类时父类先初始化。**接口是例外**：接口初始化不要求父接口初始化，除非真正用到父接口的方法。
4. 虚拟机启动时的主类（含 `main` 的那个）。
5. `MethodHandle` 解析到 `REF_getStatic / REF_putStatic / REF_invokeStatic` 时。
6. 默认方法所在接口被实现类初始化时（JDK8 起的补充规则）。

### 不会初始化的三个经典反例

```java
class Parent { static { System.out.println("Parent init"); } static int VALUE = 1; }
class Child extends Parent { static { System.out.println("Child init"); } static final int CONST = 2; }

// ① 通过子类引用父类静态字段 → 只有 Parent 初始化，Child 不初始化
System.out.println(Child.VALUE);     // 只输出 Parent init

// ② 引用编译期常量 → 谁都不初始化（常量在编译期已折叠进调用方的常量池）
System.out.println(Child.CONST);     // 什么都不输出

// ③ 定义数组 → 只创建数组对象，元素类型不初始化
Child[] arr = new Child[10];         // 什么都不输出
```

第 ② 条有个隐蔽后果：**对方把 `CONST` 的值改了重新编译，你的类不重新编译就还是旧值**。这就是"改了常量不生效"的经典事故，也是排查时第一句该问"你们的包是不是增量编译的"。

### `<clinit>` 是带锁的，而锁会死锁

JVM 保证一个类的 `<clinit>` 在多线程下只执行一次，机制是给 Class 对象加一把初始化锁：其他线程阻塞等待，第一个线程执行完后被唤醒并**直接返回**，不会重复执行。

```java
class A {
    static {
        System.out.println("A init start");
        System.out.println("B.x = " + B.x);     // 正向触发 B 初始化
        System.out.println("A init end");
    }
    static int x = 1;
}
class B {
    static {
        System.out.println("B init start");
        System.out.println("A.x = " + A.x);     // 反向触发 A 初始化
        System.out.println("B init end");
    }
    static int x = 2;
}
// 线程 1 执行 new A()，线程 2 执行 new B() → 各自持有自己的初始化锁，又等对方的锁
```

这个死锁用 `jstack` 抓出来的栈非常怪：两个线程都停在 `<clinit>` 上，都显示 waiting to lock 某个 Class 对象。**它和业务代码一点关系都没有，加锁、超时、重试全都没用**，只能调整初始化顺序，或者把静态初始化改成懒加载（holder 模式）。

**速查口诀：** 静态块里不要调别人的静态方法，不要在静态块里发布 `this`，不要在静态块里起线程——三个"不要"能挡掉八成类初始化事故。

---
## L3 自定义 ClassLoader：热部署的全部难点都在"卸载"两个字上

### 重写 findClass，不要重写 loadClass

```java
public class PluginClassLoader extends ClassLoader {
    private final Path dir;

    public PluginClassLoader(Path dir, ClassLoader parent) {
        super(parent);           // parent 传 App 加载器，插件才能用到 Spring 等公共库
        this.dir = dir;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            byte[] bytes = Files.readAllBytes(dir.resolve(name.replace('.', '/') + ".class"));
            return defineClass(name, bytes, 0, bytes.length);   // 字节数组 → 运行时类型
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }
}
```

只重写 `findClass` 能自动继承双亲委派（先问爹，爹没有才走这里）；重写 `loadClass` 才能打破它（先自己找）。**面试问"怎么打破双亲委派"，答案就是"重写 loadClass，把 findClass 提到 parent.loadClass 之前"。**

还有一个坑：`defineClass` 必须在同一个包名下才能访问包级私有成员，否则要签名的 `defineClass(name, bytes, off, len, protectionDomain)`。跨加载器访问包级私有成员会直接抛 `IllegalAccessError`，这是插件化里第二个"见鬼"报错。

### 类卸载的三个条件，缺一不可

```
① 该类所有实例都已被 GC 回收
② 该类的 Class 对象没有任何引用（包括常量池、静态字段、反射缓存、JNI 全局引用）
③ 加载该类的 ClassLoader 已被 GC 回收
```

再加一条前提：**只有自定义 ClassLoader 加载的类才可能被卸载**。Bootstrap、Platform、App 三个加载器在 JVM 生命周期内不会被回收，所以它们加载的类永远不会卸载——用 `-verbose:class` 看到 `[Unloading class ...]` 的，一定是自定义加载器的类。

第三条是最容易漏的：只要还有一个 `Class` 对象被某个静态 Map 缓存着（比如 `Map<String, Class<?>>` 的反射缓存、MyBatis 的 Mapper 缓存、Spring 的 `typeCache`），加载器就回收不了，整个类加载器连带它加载的几千个类全部滞留。

### 热部署的账本：为什么"重启一下就好"是真理

一个中等规模的应用，业务类 + 依赖类大约 3000 个，平均每个类元数据 8KB：

```
单次部署元数据     3000 × 8KB           ≈ 24MB
CGLIB 代理 + 匿名类  再多 10%~20%         ≈ 28MB
迭代 200 次         28MB × 200            ≈ 5.6GB
```

如果每次热部署都泄漏一整个加载器，200 次迭代后元空间就是 5.6GB——这不是理论，是很多"热部署很方便"的团队在预发环境跑一周之后的实际数字。

| 现象 | 说明 |
| --- | --- |
| `java.lang.OutOfMemoryError: Metaspace` | 元空间耗尽，通常是加载器泄漏 |
| Full GC 后元空间只涨不降 | 类卸载没发生，加载器被引用住 |
| `jmap -clstats` 里同一个类名出现 N 份 | 每次热部署生成一份新的，一份没卸掉 |
| 堆很正常，进程 RSS 一直涨 | 元空间在本地内存，堆 dump 里看不见 |

**Metaspace 默认是没有上限的**（只受物理内存约束），所以它不会像堆那样"用完就 OOM"，而是慢慢把机器吃干净，最后被容器 OOMKilled（exit 137）。生产环境必须显式设置 `-XX:MaxMetaspaceSize`，让它在变成内核杀进程之前先抛 Java 异常——异常能抓、能告警，SIGKILL 不能。

---

## L4 Metaspace 泄漏取证：五类元凶和一条命令

### 五类元凶，按线上出现频率排

| 元凶 | 机制 | 典型特征 |
| --- | --- | --- |
| 动态代理 / CGLIB | 每次生成新类，类名带序号，缓存 key 设计不当就无限增长 | `$$EnhancerBySpringCGLIB$$xxxx` 数量异常 |
| 热部署 / 插件卸载 | 加载器没被回收，连带整个命名空间 | 同类名多份，`clstats` 里加载器数量持续增长 |
| 脚本引擎 | Groovy/JS 每次 `eval` 可能生成新类 | 类名带 `script` 或随机后缀 |
| 反射 / 动态字节码 | `Proxy.newProxyInstance`、ASM/Javassist 生成 | `$Proxy0`、`$Proxy1` 递增 |
| JSP / 模板编译 | 每次改 JSP 编译成新 Servlet 类 | `org.apache.jsp.xxx_jsp` 多份 |

### 三条命令，按顺序敲

```bash
# ① 先看元空间总量和已用（JDK8+ 有效）
jcmd 12345 VM.metaspace
#   total committed: 1.2GB  ← 如果远超"类数 × 8KB"，就是泄漏

# ② 再看每个加载器各占多少、有多少个
jmap -clstats 12345
#   class_loader  classes  bytes  parent  alive  type
#   0x0000...      3120    25.1MB  null    true   bootstrap
#   0x0000...      18      0.3MB   0x...   false  com.demo.PluginClassLoader   ← alive=false 才有救

# ③ 想看类的创建来源，用 class_stats（需解锁诊断选项）
jcmd 12345 GC.class_stats -csv | head -50
```

`jmap -clstats` 里的 `alive` 列是关键：`alive=false` 说明加载器已经不可达，下一次 GC 就该被回收；如果复查三次还是 `alive=true`，就去找引用链。

### 找引用链：MAT 的两个视图最有效

拿到堆 dump 之后，别急着点 Dominator Tree，先做两件事：

1. **Histogram 搜 `ClassLoader`**，看有几个自定义加载器实例。正常是 1 个，泄漏时是 N 个。
2. 对可疑加载器 **Path to GC Roots → exclude weak/soft references**。绝大多数泄漏的答案长这样：`PluginClassLoader ← static Map in SomeRegistry ← AppClassLoader`。也就是某个全局静态缓存拿着加载器不放。

**踩过的坑：** 一个 SPI 框架用 `Map<String, Object> extensionMap` 缓存插件实例，key 是类名字符串，value 是插件对象。看起来完美，实际上插件对象 → 它的 Class → 它的 ClassLoader，整条链被静态 Map 钉死。改成 `Map<String, SoftReference<Object>>` 之后，元空间从"每周涨 800MB"变成"稳定在 300MB"。

### 一个反直觉的加分点：Lambda 和隐藏类

JDK 15 起，Lambda 和动态代理生成的类如果走 `Lookup.defineHiddenClass`，会变成**隐藏类（hidden class）**：不占用 ClassLoader 的命名空间，不能被 `Class.forName` 找到，**可以被单独卸载**。所以"Lambda 会不会导致元空间泄漏"这个问题的标准答案是：不会，隐藏类随宿主类一起回收，不算加载器的账。这句话说出来，比背十条参数有用。

---
## L5 record / sealed / 模式匹配：把"约定"变成"编译期约束"

这三件事经常被当成三个孤立的新语法背，其实它们是一条线：**record 描述"是什么"，sealed 描述"有哪几种"，模式匹配描述"怎么按种类处理"**。三者合起来，JDK 才能在你漏写一个分支的时候直接编译报错。

### record：值语义，而不是"简化版 POJO"

```java
// 一行代码换来的东西：final 类 + 全参构造 + 访问器 + equals/hashCode/toString
public record Money(long cents, Currency currency) {
    // 紧凑构造器：只写校验，不用写赋值（编译器自动补 this.cents = cents）
    public Money {
        if (cents < 0) throw new IllegalArgumentException("amount must be >= 0");
        Objects.requireNonNull(currency);
    }
    // 可以加普通方法，不能加实例字段
    public Money plus(Money other) {
        if (other.currency != currency) throw new IllegalArgumentException("currency mismatch");
        return new Money(cents + other.cents, currency);
    }
}
```

编译器到底生成了什么，这是面试的分水岭：

| 生成物 | 说明 |
| --- | --- |
| `final class` | record 不能被继承，也不能继承别的类（但可以实现接口） |
| 每个组件一个 `private final` 字段 | 反射拿到的字段名就是组件名，序列化依赖它 |
| 每个组件一个同名访问器 | 是 `cents()` 而不是 `getCents()`，所以大多数 ORM/JSON 框架要额外适配 |
| `equals/hashCode` | 基于**全部组件**逐字段比较，不是基于引用 |
| `toString` | 形如 `Money[cents=100, currency=CNY]` |

四个高频坑，每一个都能单独追问一轮：

1. **record 不能做 JPA 实体。** JPA 要求无参构造 + 可变字段 + 代理子类，record 三条全违反。它适合做 DTO、查询结果、事件对象、Map 的复合 key。
2. **record 里放数组，`equals` 是比引用。** 数组的 `equals` 是 Object 的默认实现，所以两个内容相同的 `byte[]` 组件的 record 不相等。要么重写 `equals`，要么用 `List` 或 `ByteBuffer`。
3. **record 不是不可变的，只是"浅不可变"。** 组件是 `List` 时，外部仍能改它。要真不可变，构造器里 `List.copyOf(list)`。
4. **紧凑构造器里不能给字段赋值**，只能校验和规范化（比如 `cents = Math.max(0, cents)` 是允许的，那是给参数重新赋值，编译器再赋给字段；`this.cents = cents` 反而编译不过）。

### sealed：把"继承"从开放改成封闭

```java
public sealed interface Shape permits Circle, Rect, Triangle {}
public record Circle(double r) implements Shape {}
public record Rect(double w, double h) implements Shape {}
public record Triangle(double a, double b, double c) implements Shape {}
```

规则就三条，但每条都常被问：

- 子类必须 `final`、`sealed` 或 `non-sealed` 三选一，不许"什么都不写"。
- `permits` 可以省略，此时允许的子类必须与父类同文件（或同模块），编译器自动推断。
- 子类必须与父类**同模块、同包**（若父类在无名模块，则必须同包）。这是为了防止别处的代码偷偷塞进来一个子类型，把穷尽性打破。

### 模式匹配 switch：穷尽性检查才是真正的收益

```java
// JDK 21 起正式（JEP 441）
static double area(Shape s) {
    return switch (s) {
        case Circle c                -> Math.PI * c.r() * c.r();
        case Rect r                  -> r.w() * r.h();
        case Triangle t              -> heron(t);
        // 没有 default！因为 Shape 是 sealed 且三个子类都覆盖了
    };
}

// 带守卫条件（when），注意顺序：从上往下匹配，先具体后宽泛
static String describe(Object o) {
    return switch (o) {
        case null                    -> "null";              // JDK 21 允许显式 case null
        case String s when s.isEmpty()-> "empty string";
        case String s                -> "string len=" + s.length();
        case int[] arr               -> "int array len=" + arr.length;
        case Point(int x, int y)     -> "point " + x + "," + y;   // 记录模式（JDK 21）
        default                      -> o.getClass().getSimpleName();
    };
}
```

三个必须记住的细节：

1. **`case null` 不写，switch 遇到 null 仍抛 NPE。** 这是和旧 switch 一致的行为，新增的是"你可以显式处理它"。
2. **穷尽性只在三种情况下被检查**：switch 的选择器是 sealed 类型、是枚举、或者有 `default`。用 `Object` 做选择器时，编译器仍然要求 `default`。
3. **守卫条件不算穷尽性。** `case String s when s.isEmpty()` 不能替代 `case String s`，因为编译器无法证明守卫覆盖了全部情况。

**为什么这个组合在生产里真的有用：** 支付渠道从 3 个加到 4 个时，所有 `switch (channel)` 的地方会**编译失败**，而不是运行时走进 default 分支静默返回 null。这一条能省掉的线上事故，比这套语法本身的价值大得多。

四档的差距不在定义，而在"知不知道边界"：

| 档位 | 回答 | 面试官心里的分 |
| --- | --- | --- |
| 不及格 | "record 就是省了 getter/setter 的类" | 55，方向反了（record 没有 setter） |
| 及格 | 能说出 record 是不可变数据载体、sealed 限制继承 | 72 |
| 良好 | 说出 equals 基于组件、record 不适合 JPA 实体 | 83 |
| 优秀 | 讲清 sealed + switch 的穷尽性检查是"把运行时错误前移到编译期"，并指出守卫条件不参与穷尽性 | 92 |

---

## L6 虚拟线程：便宜的根因是"栈不在操作系统线程上"

"虚拟线程很轻量"这句话是结论。面试官想听的是**为什么轻**，以及**轻到什么程度**。

### 平台线程贵在哪：一份 1MB 的栈预留

```
平台线程（JDK 线程 = OS 线程，1:1）
┌──────────────────────────────┐
│ OS Thread  内核调度实体        │  创建要走 clone() 系统调用
│ ├─ 栈：默认预留 1MB 虚拟地址   │  每个线程独立 mmap，TLB 压力大
│ └─ 内核栈 + 用户栈             │  上下文切换 = 陷入内核 + 换页表
└──────────────────────────────┘
1 万个线程 → 10GB 虚拟地址预留 + 内核调度开销 → 实际几百个就到顶
```

### 虚拟线程：栈是堆上的一块可增长数组

```
虚拟线程（M:N，M 个虚拟线程跑在 N 个载体线程上）
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│ VirtualThread│  │ VirtualThread│  │ VirtualThread│   栈帧存在堆里的 StackChunk
│  ( Continuation )              │                  阻塞时整块"快照"出去
└──────┬──────┘  └──────┬──────┘  └──────┬──────┘
       │ mount          │                │
┌──────▼────────────────▼────────────────▼──────┐
│ ForkJoinPool（载体线程数 = CPU 核数）           │
│  载体线程仍是平台线程，但只有 CPU 核数个         │
└────────────────────────────────────────────────┘
```

核心机制是 **Continuation（续体）**：虚拟线程执行时可以"让出"，JVM 把当前栈帧复制成一块 `StackChunk` 存到堆上，载体线程立刻去跑别的虚拟线程；I/O 就绪后再把栈帧恢复回去继续执行。这个"存栈—恢复"的过程叫 mount/unmount，成本是**几百纳秒级的对象拷贝**，而不是内核上下文切换。

| 维度 | 平台线程 | 虚拟线程 |
| --- | --- | --- |
| 栈存放 | 独立内存区，默认预留 1MB | 堆上的 StackChunk，按需增长，初始几百字节 |
| 创建成本 | 数十微秒到毫秒（系统调用 + mmap） | 百纳秒级（纯 Java 对象分配） |
| 数量级 | 数百到数千 | 数十万到百万 |
| 上下文切换 | 内核态，微秒级 | 用户态，纳秒级 |
| 调度者 | 操作系统 | JVM（ForkJoinPool） |
| 100 万个的栈开销 | 1TB 虚拟地址（根本开不出来） | 几百 MB 到 1GB（取决于栈深度） |

**一句话答法：** "虚拟线程是 JVM 调度的 M:N 线程，栈在堆上、以 StackChunk 形式按需增长，阻塞时 unmount 把栈快照出去，载体线程只有 CPU 核数个。所以它的成本从'操作系统资源'降到了'几个对象'，这就是为什么能开一百万。"

### 它解决的是"线程贵"，不是"操作贵"

```
10 万并发请求
  ├─ 虚拟线程：线程本身几乎免费 ✔
  ├─ 数据库连接池：HikariCP 默认 10 个 → 第 11 个请求照样排队 ✘
  ├─ 下游 HTTP 连接池：maxConnections 200 → 该排队还是排队 ✘
  └─ CPU：还是 8 核 → CPU 密集任务一点没变快 ✘
```

**这是虚拟线程落地最大的认知错位**：它把"线程数"这个天花板拆了，但真正的瓶颈从来不在线程数上，而在**连接数**上。上线后最常见的现象不是"变快了"，而是"卡点从 Tomcat 线程池挪到了数据库连接池"。

---
## L7 pinning：JDK 21 的死穴，JDK 24 的手术

### 什么是 pinning

虚拟线程阻塞时本该 unmount，把载体线程让出去。但如果它阻塞在**无法保存栈帧**的位置，JVM 就不敢动它——只能让载体线程陪着一起等。这就是 pinning（钉住）。钉住的后果不是报错，是**静默的吞吐崩塌**：你以为开了 10 万并发，实际上只有 8 个（CPU 核数）在跑。

Netflix 在生产上踩过一次，公开的复盘里最扎眼的一个数字是 **12.7 倍**——同一段逻辑，在 JDK 21 上因为 `synchronized` 里包了一次慢 I/O，吞吐掉了 12.7 倍，而且**没有任何异常、没有任何日志**，就像服务突然变慢了一样。

### 两代 pinning，界限在 JDK 24

```
JDK 21 ~ 23                          JDK 24+（JEP 491）
┌────────────────────────┐          ┌────────────────────────┐
│ synchronized (lock) {  │          │ synchronized (lock) {  │
│     slowIo();   ← 钉住  │          │     slowIo();   ← 可卸载│
│ }                      │          │ }                      │
│ Object.wait()  ← 钉住   │          │ Object.wait()  ← 可卸载 │
└────────────────────────┘          └────────────────────────┘
monitor 记在载体线程身上              monitor 记在虚拟线程自己的身份上
```

JEP 491 的做法是：把 monitor 的持有者从"载体线程 ID"改成"虚拟线程 ID"，JVM 于是可以在持有 monitor 的情况下保存栈帧并卸载。**代价是 JDK 24 不是 LTS**——这就留下了真实世界最常见的困境：团队停在 JDK 21 LTS，就得自己做 `synchronized → ReentrantLock` 的改造；升到 25 LTS 就白拿这个修复。

### JDK 24 之后还剩三种 pinning

| 场景 | 为什么还钉 | 常见触发 |
| --- | --- | --- |
| native 方法 / FFM 调用中阻塞 | 本地代码持有原始栈指针，卸载不安全 | JNI 加密库、某些序列化库、Panama 调用 |
| 类加载 / 类初始化 | 加载过程持有 JVM 内部锁，栈状态不可保存 | 首次加载大量类、动态生成类、本期 L2 的 `<clinit>` |
| 本地文件 I/O（Linux） | JDK 尚未接入 io_uring，文件读写走阻塞系统调用 | `FileInputStream.read`、`Files.readAllBytes` |

注意第二条：**类加载会 pin 住载体线程**。这条和 L2 的 `<clinit>` 死锁连起来看就很有意思——一个在静态初始化里做远程调用的类，会把载体线程钉死，在虚拟线程场景下比在平台线程场景下危险得多。

### 诊断：JDK 24 之后 `-Djdk.tracePinnedThreads` 已经没用了

```bash
# ① JDK 21~23：还能用这个开关，会把 pinning 的栈打到 stdout
java -Djdk.tracePinnedThreads=full -jar app.jar

# ② JDK 24+：改用 JFR，这个属性设了也不生效
jcmd 12345 JFR.start duration=200s filename=/tmp/vt.jfr
jfr print --events jdk.VirtualThreadPinned /tmp/vt.jfr
#   JDK 25 起该事件默认阈值 20ms，小于 20ms 的钉住不记录（避免噪声）
```

还有一个反直觉的点：**`jstack` 看不到虚拟线程的栈**。虚拟线程不是 OS 线程，`jstack` 遍历的是 JVM 里的平台线程。要 dump 虚拟线程，用：

```bash
jcmd 12345 Thread.dump_to_file -format=json /tmp/threads.json
# 文件里能看到每个虚拟线程的状态、栈、以及它当时挂在哪个载体线程上
```

Netflix 那次事故之所以排查了很久，就是因为团队按惯例敲了 `jstack`，看到的是一片"正常"的载体线程。

---

## L8 ThreadLocal 在虚拟线程上的两种静默故障

`ThreadLocal` 是 1998 年为"少量长寿线程 + 线程池复用"设计的。虚拟线程是**短命且不复用**的，这两个前提同时被推翻，于是出现两种不报错、不告警、只慢慢变坏的故障。

### 故障一：缓存悄悄不缓存了

```java
// 经典写法：每个线程懒初始化一个"创建很贵"的对象
private static final ThreadLocal<SimpleDateFormat> FMT =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

// 平台线程池：200 个线程 → 只创建 200 次，之后永远命中
// 虚拟线程：每个请求一个新线程 → 每个请求创建一次，ThreadLocal 变成了"每次 new"
```

后果是**分配速率上升、GC 压力变大**，但代码逻辑完全正确，测试也全绿。团队只会看到"开了虚拟线程之后 YGC 变频繁了"，然后去调 GC 参数——方向完全错了。

**修法**：这类"每线程一个昂贵对象"的缓存，本质是**对象池**，应该换成显式池（`ArrayBlockingQueue` 或 `Pool`），或者换成线程安全的实现（`DateTimeFormatter` 本身就是不可变线程安全的，直接用静态常量）。

### 故障二：内存放大，一百万份 ThreadLocalMap

```
每个虚拟线程一个 ThreadLocalMap
  1,000,000 线程 × 1KB（几个 entry 的 map 开销）≈ 1GB
```

这不是泄漏，是**设计上的成本**：ThreadLocal 天然是"每线程一份"，线程数从 200 涨到 100 万，这份开销就涨了 5000 倍。JDK 没有把它变慢，是你的用法在大规模下不成立。

### 修法：ScopedValue（JDK 25 正式，JEP 506）

```java
public final class Ctx {
    public static final ScopedValue<User> USER = ScopedValue.newInstance();
    public static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();
}

// 绑定一个作用域，作用域结束自动解绑（异常也会解绑）
ScopedValue.where(Ctx.USER, user)
           .where(Ctx.TRACE_ID, traceId)
           .run(() -> service.handle(request));

// 下游任意深度直接读，没有 set，也没有 remove
User u = Ctx.USER.get();
```

它和 ThreadLocal 的三个差别，正好对上 ThreadLocal 的三个毛病：

| 维度 | ThreadLocal | ScopedValue |
| --- | --- | --- |
| 生命周期 | 手动 `remove()`，忘了就泄漏 | 作用域结束自动失效，异常也保证 |
| 可变性 | 任何下游代码都能 `set` | 作用域内不可变，只能开新作用域 |
| 继承成本 | `InheritableThreadLocal` 复制整个 map | 近似拷贝一个指针，子任务共享同一绑定 |

关键的一句：**子虚拟线程继承 ScopedValue 几乎免费，继承 InheritableThreadLocal 要复制整个 map**。在百万线程的规模下，这个差别就是"能不能用"的差别。

### 结构化并发：把"并行的生命周期"变成一棵树

```java
// JDK 21 起预览，到 JDK 25 仍是第五次预览（JEP 505），API 还会变
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Supplier<User>    user    = scope.fork(() -> userClient.get(id));
    Supplier<List<Order>> orders = scope.fork(() -> orderClient.list(id));

    scope.join();            // 等所有子任务
    scope.throwIfFailed();   // 任一失败则整体失败，并自动取消其他子任务

    return new Detail(user.get(), orders.get());
}
```

它解决的问题是"fork 出去的子任务没人管"：手动 `ExecutorService.submit` 时，父任务超时返回了，子任务还在跑，线程、连接、下游压力全留着。结构化并发强制"父作用域不结束，子任务不许逃逸"，超时/异常时自动取消全部兄弟任务。

一句话答法：**"ThreadLocal 的问题不是慢，是它把一个'每线程一份'的假设带到了百万线程的场景；ScopedValue 把上下文从'线程持有的状态'改成'调用链上的隐式参数'，继承成本从复制 map 降到拷贝指针。"**

---
## L9 落地手册：什么时候该用虚拟线程，什么时候用了更糟

### Spring Boot 里就一行配置，但配完只是开始

```yaml
spring:
  threads:
    virtual:
      enabled: true        # Spring Boot 3.2+，官方建议 JDK 24+，最低 21
  main:
    keep-alive: true       # 纯后台/定时任务型应用必须加，否则主线程退出就整体结束
```

第二个参数是个真坑：虚拟线程全部是 **daemon 线程**，如果应用不是靠 Web 容器（Tomcat 的 accept 线程）撑着，而是一个 `CommandLineRunner` 干完活就结束，JVM 会直接退出，表现是"任务一个都没跑"。

### 四个判断，决定要不要开

| 场景 | 该不该用 | 原因 |
| --- | --- | --- |
| I/O 密集（RPC、DB、缓存、HTTP 调用） | 用 | 线程绝大部分时间在等，等的成本从 MB 级降到字节级 |
| 混合型（少量计算 + 大量等待） | 用，但要限流 | 并发数上去了，下游连接池才是天花板 |
| CPU 密集（加解密、图像、大 JSON 序列化） | 别用 | 只有 8 个核，线程再多也是排队，还多一层调度 |
| 已有固定线程池 + 队列背压 | 别急着换 | 队列本身就是限流器，换成虚拟线程等于把限流器拆了 |

### 必须自己加的资源闸门

```java
// 虚拟线程免费，但它调的数据库连接不免费
private static final Semaphore DB_PERMITS = new Semaphore(20);   // 对齐 HikariCP maximumPoolSize

String handle(long id) throws Exception {
    DB_PERMITS.acquire();
    try {
        return repo.findById(id);      // 真正的并发上限由这里决定
    } finally {
        DB_PERMITS.release();
    }
}
```

**注意：不要池化虚拟线程。** 平台线程要池化是因为创建贵；虚拟线程创建只要几百纳秒，池化之后反而引入了"任务排队"和"ThreadLocal 残留"两个问题。`Executors.newVirtualThreadPerTaskExecutor()` 每次任务一个新线程，这才是正确姿势。

### 一个真实的比例账

```
假设：单请求耗时 50ms，其中 45ms 在等下游，5ms 在占 CPU，8 核机器

平台线程 200 个：
  吞吐上限 = 200 / 0.05s = 4000 QPS
  但 CPU 只需要 4000 × 0.005 = 20 核 → 实际被 CPU 卡在 8 核 ≈ 1600 QPS

虚拟线程不限流：
  线程数不是瓶颈了，吞吐上限 = 8000 QPS（8 核 / 5ms）
  但 8000 QPS 需要 8000 个并发 DB 连接 → 连接池 20 个，直接排队
  真实现象：延迟从 50ms 涨到 2s，错误率上升，看起来"更慢了"
```

结论很反直觉：**不加限流地开虚拟线程，通常会让系统变慢**。虚拟线程把"线程数"这个天然限流器拆掉了，你必须显式地把限流器补回去。

---

## L10 启动优化：从 CDS 到 AOT cache

类加载在冷启动里占的时间比大多数人以为的多——一个 Spring Boot 应用启动 2.5 秒，其中类加载 + 链接 + 初始化大约占 **30%~50%**，剩下才是 Spring 容器本身的反射和 Bean 装配。

### 四代方案，效果递增、限制也递增

```
① 类数据共享 CDS（JDK 5+，默认开）
   把 JDK 核心类的解析结果存成归档，多进程共享  → 省 5%~10%

② AppCDS（JDK 10+ 一键化，JDK 13+ 一行命令）
   把业务类也归档                              → 省 20%~30%

③ AOT cache（JDK 24 起，Project Leyden）
   训练运行一次，把"类加载 + 链接 + 部分 profile"整体缓存 → 省 40%~60%

④ GraalVM native image
   编译期就把类初始化做完，运行时没有类加载     → 启动 20~50ms，但反射/动态代理要配置
```

AppCDS 的两条命令，够用了：

```bash
# 训练：正常跑一次，退出时把加载过的类归档
java -XX:ArchiveClassesAtExit=app.jsa -jar app.jar

# 使用：启动时直接映射归档，跳过大部分解析
java -XX:SharedArchiveFile=app.jsa -jar app.jar
# 验证是否真的命中：加 -Xlog:class+load:file=cds.log，看 shared 标记
```

### 一个和本期无关但很香的参数：紧凑对象头

JDK 25 起可以用 `-XX:+UseCompactObjectHeaders`（JEP 519），对象头从 12 字节压到 **8 字节**。回到第 12 期那个 Integer=16 字节的账本：头从 12 降到 8 之后，很多对象能省掉一次 8 字节对齐填充，堆占用普遍降 **10%~20%**。这是"改一个参数、什么都不用动"的少数情况之一。

---

## L11 速查：版本矩阵、命令、参数

### 特性版本矩阵（面试报版本号能加分）

| 特性 | 状态 | 版本 |
| --- | --- | --- |
| 双亲委派 + TCCL | 一直存在 | JDK 1.2 / 1.2 起 |
| 模块化 JPMS | 正式 | JDK 9 |
| 隐藏类 hidden class | 正式 | JDK 15 |
| record | 正式 | JDK 16 |
| sealed class | 正式 | JDK 17 |
| switch 模式匹配（含 record 模式） | 正式 | JDK 21（JEP 441） |
| 虚拟线程 | 正式 | JDK 21（JEP 444） |
| synchronized 不再 pinning | 正式 | JDK 24（JEP 491） |
| AOT class loading & linking | 正式 | JDK 24 |
| ScopedValue | 正式 | JDK 25（JEP 506） |
| 紧凑对象头 | 正式 | JDK 25（JEP 519） |
| 结构化并发 | **仍是预览** | JDK 25 第五次预览（JEP 505） |

### 命令速查

| 目的 | 命令 |
| --- | --- |
| 看类加载过程 | `-verbose:class` 或 `-Xlog:class+load=info` |
| 看类卸载 | `-Xlog:class+unload=info` |
| 看元空间总量 | `jcmd PID VM.metaspace` |
| 看每个加载器占用 | `jmap -clstats PID` |
| 导出类统计 | `jcmd PID GC.class_stats -csv` |
| 导出虚拟线程栈 | `jcmd PID Thread.dump_to_file -format=json f.json` |
| 抓 pinning 事件 | `jcmd PID JFR.start duration=200s filename=vt.jfr` 后 `jfr print --events jdk.VirtualThreadPinned vt.jfr` |
| 生成 CDS 归档 | `java -XX:ArchiveClassesAtExit=app.jsa -jar app.jar` |

### 参数速查

| 参数 | 作用 | 建议 |
| --- | --- | --- |
| `-XX:MaxMetaspaceSize=512m` | 元空间上限 | 生产必设，让泄漏变成可捕获的 OOM 而不是 exit 137 |
| `-XX:MetaspaceSize=256m` | 首次 Full GC 阈值 | 设大一点，避免启动期反复 Full GC |
| `-Xlog:class+unload=info` | 类卸载日志 | 热部署/插件化必开 |
| `-Djdk.virtualThreadScheduler.parallelism=N` | 载体线程数 | 默认 = CPU 核数，一般不动 |
| `-Djdk.tracePinnedThreads=full` | pinning 诊断 | **仅 JDK 21~23 有效**，24+ 用 JFR |
| `-XX:+UseCompactObjectHeaders` | 紧凑对象头 | JDK 25+，堆省 10%~20% |

---

## 十四条高频问答

### Q1：类加载器和 Class 对象是什么关系？

- **答：** 一个 Class 对象唯一对应"某个加载器 + 某个类名"。所以判断"是不是同一个类"必须同时比较两者，`Class.equals` 内部也是这么做的。这就是为什么同一个 jar 被两个加载器加载会出 ClassCastException。

### Q2：双亲委派被打破有几种？各自解决什么问题？

- **答：** 四次。第一次是 JDK 1.2 前的历史代码；第二次是 SPI 用 TCCL 让父加载器回调子加载器；第三次是 Tomcat/OSGi 为应用隔离改成"先自己后父"；第四次是 JDK 9 模块化后不再按层级委托，而是委派给"负责该包的模块"的加载器。前三次是打破规则，第四次是规则本身变了。

### Q3：为什么 `Class.forName` 会初始化类，而 `loadClass` 不会？

- **答：** `Class.forName(name)` 默认 `initialize=true`，会走到初始化阶段；`ClassLoader.loadClass` 只到"加载 + 链接"，不触发 `<clinit>`。JDBC 老代码必须用 `Class.forName` 就是因为要触发驱动类的静态块去注册自己。

### Q4：`static final` 常量为什么不触发类初始化？

- **答：** 编译期常量会被折叠进调用方的常量池，运行时根本不访问那个类，自然不触发初始化。代价是常量改了要重新编译调用方。

### Q5：什么情况下类会被卸载？

- **答：** 三个条件同时满足：该类的实例全被回收、Class 对象无引用、加载它的 ClassLoader 被回收。而且只有自定义加载器加载的类才可能卸载。任何一个静态缓存持有 Class 或加载器，整批类都留着。

### Q6：Metaspace 溢出怎么排查？

- **答：** 先 `jcmd VM.metaspace` 看总量是否远超"类数 × 8KB"，再 `jmap -clstats` 看加载器数量和 `alive` 标记，然后 dump 堆、用 MAT 对可疑加载器做 Path to GC Roots（排除弱引用/软引用）。九成答案是"某个静态 Map 拿着插件对象"。

### Q7：热部署为什么总会泄漏？

- **答：** 因为卸载要求加载器整体不可达，而框架的反射缓存、Spring 的 typeCache、线程的 TCCL、甚至一个未关闭的线程，都会持有加载器。少一个都不行，所以工程上通常选择"重启"而不是"热部署"。

### Q8：record 能不能做 JPA 实体？为什么？

- **答：** 不能。JPA 需要无参构造、可变字段、可被代理继承，record 三条都不满足（final 类、final 字段、只有全参构造）。record 适合 DTO、事件、查询投影、Map 的复合 key。

### Q9：sealed 的子类为什么必须同包/同模块？

- **答：** 为了保证穷尽性可判定。如果允许跨包随意扩展，编译器就永远无法枚举全部子类型，`switch` 的穷尽性检查就失效了，这个特性最大的价值也就没了。

### Q10：虚拟线程为什么比平台线程便宜？

- **答：** 栈在堆上以 StackChunk 形式按需增长，初始只有几百字节；创建只是分配对象，不走系统调用；阻塞时 unmount 把栈快照出去，载体线程只有 CPU 核数个。三个因素叠加，成本从"操作系统资源"降到"几个对象"。

### Q11：JDK 21 上虚拟线程最大的坑是什么？

- **答：** `synchronized` 块内阻塞会 pin 住载体线程，表现为吞吐静默崩塌（Netflix 实测 12.7 倍）。JDK 24 的 JEP 491 修掉了这个问题。停在 21 的团队需要把持锁慢 I/O 改成 `ReentrantLock`，或者升级到 25 LTS。

### Q12：JDK 24 之后还剩哪些 pinning？

- **答：** 三类：native/JNI/FFM 调用中阻塞、类加载与类初始化、Linux 上的本地文件 I/O。诊断方式从 `-Djdk.tracePinnedThreads` 换成 JFR 的 `jdk.VirtualThreadPinned` 事件。

### Q13：开了虚拟线程为什么反而更慢？

- **答：** 因为拆掉了"线程数"这个天然限流器，请求直接涌到数据库连接池/下游，排队时间变长、超时变多。正确做法是用 `Semaphore` 按真实资源容量显式限流，而不是把虚拟线程数调小去模仿旧线程池。

### Q14：ThreadLocal 在虚拟线程下有什么问题？

- **答：** 两个静默故障：一是"每线程缓存"变成"每请求新建"，分配速率上升但不报错；二是内存放大，一百万线程一百万份 ThreadLocalMap。上下文传递应改用 ScopedValue（JDK 25 正式），继承成本近似拷贝指针。

---

## 三道模拟面试

### 模拟一：插件热部署的元空间泄漏

**题目：** 你们做了一个插件化框架，支持运行时上传 jar 并热加载。上线两周后预发环境频繁 `OutOfMemoryError: Metaspace`，但堆内存一直很平稳。请描述你的排查路径，并说明可能的根因和修复方案。

**考察点：** 类卸载三条件、加载器生命周期、jmap -clstats 的读法、引用链定位。

优秀回答的骨架，按这个顺序说：

1. 先确认是元空间而不是堆：`jcmd VM.metaspace` 看 committed 是否持续增长，同时 `jstat -gc` 看老年代是否平稳。堆平稳 + 元空间涨，基本锁定类泄漏。
2. 再确认类没被卸载：`jmap -clstats` 看自定义加载器数量是否随上传次数线性增长，同名类是否有多份。开 `-Xlog:class+unload` 看有没有 `Unloading class`。
3. 然后找根因：dump 堆，MAT 里对 `PluginClassLoader` 做 Path to GC Roots，排除弱引用/软引用。常见答案有四类——静态缓存持有插件对象、线程的 TCCL 指向插件加载器、未关闭的线程池、JDK 动态代理/反射缓存。
4. 最后修复：缓存改成弱引用或按版本号清理；插件销毁时显式关闭线程池并还原 TCCL；`-XX:MaxMetaspaceSize` 兜底。
5. 加分：说明"为什么 App 加载器加载的类永远不卸载"，以及 Lambda 的隐藏类不计入加载器账本。

**不及格的表现：** 直接说"加大 Metaspace"或者"重启就好"。加大只是延后爆炸，重启是掩盖问题。

### 模拟二：从 JDK 8 升到 JDK 21 的兼容性评估

**题目：** 一个跑了六年的单体应用要从 JDK 8 升到 JDK 21，你来出升级方案。说出你会按什么顺序做、每一阶段验证什么、最容易出事的地方在哪。

**考察点：** 模块化封装、内部 API、依赖生态、GC 参数、构建工具链。

优秀回答的骨架，按这个顺序说：

1. 依赖先行：先升级 Spring、ORM、字节码工具（CGLIB/ASM）、Maven/Gradle 插件、Java Agent（arthas、APM）。字节码工具不升级会在生成代理类时直接崩。
2. 再处理封装：JDK 17 起强封装 JDK 内部 API，`sun.misc.Unsafe` 的反射访问、`--illegal-access` 都已经收紧。用 `jdeps --jdk-internals` 扫一遍，迁移期可以用 `--add-opens`，但不能当永久方案。
3. 移除项排查：JDK 11 移除了 Java EE/CORBA 模块，`javax.xml.bind`、`javax.annotation` 要显式引入替代依赖；JDK 21 移除了 Security Manager 的启用能力。
4. GC 与参数：JDK 8 默认 Parallel，JDK 9+ 默认 G1。老的 `-XX:NewRatio`、`-XX:SurvivorRatio` 在 G1 下基本失效，要重算（回到第 12 期的 Eden=分配速率×间隔）。
5. 分阶段：CI 编译通过 → 单测 → 集成测试 → 预发压测对比 P99 → 灰度 1% → 全量。每一步都要有可回滚的开关。

**最容易出事的三处：** 反射访问 JDK 内部类、字节码增强库版本过低、GC 参数照抄旧脚本。

### 模拟三：虚拟线程改造后反而变慢

**题目：** 一个 I/O 密集的订单查询服务，从平台线程池 200 改成虚拟线程后，压测显示 QPS 没有提升，P99 从 80ms 涨到 900ms，错误率上升。请分析原因并给出改造方案。

**考察点：** 虚拟线程的真实瓶颈、pinning、连接池、限流、诊断手段。

优秀回答的骨架，按这个顺序说：

1. 先建立判断：虚拟线程只解决"线程数"，不解决"连接数"和"CPU"。所以第一反应应该是去看下游资源，而不是 JVM 参数。
2. 取证三件事：JFR 抓 `jdk.VirtualThreadPinned`（JDK 24+）或 `-Djdk.tracePinnedThreads`（21~23）；`jcmd Thread.dump_to_file` 看虚拟线程堆积在哪个方法；看 HikariCP 的 `activeConnections` 和等待队列长度。
3. 两个最可能的根因：一是在 `synchronized` 块里做远程调用导致 pinning（21~23）；二是连接池没变，200 线程时最多 200 个并发，现在 8000 个并发全堵在 20 个连接上，排队时间被放大。
4. 修复：pinning 改成 `ReentrantLock` 或升 JDK 24+；连接池用 `Semaphore` 显式限流到真实容量；下游 HTTP 客户端单独配连接池；CPU 密集段（大 JSON 序列化）保持在小线程池里，不要放进虚拟线程。
5. 验证：同一份压测脚本、同一批数据，对比 QPS/P99/连接池等待时间三个指标，而不是只看 QPS。

**加分的一句：** "虚拟线程的正确用法不是'把线程池换成虚拟线程'，而是'把线程模型换成每请求一线程，同时把原来靠线程池隐式完成的限流显式补回来'。"

---

## 下期预告

第 14 期：**Java 集合框架深水区与并发容器**——`ArrayList` 扩容的 1.5 倍为什么不是 2 倍、`HashMap` 树化阈值的泊松分布账、`ConcurrentHashMap` 的 `sizeCtl` 与协助扩容、`CopyOnWriteArrayList` 的适用边界、`BlockingQueue` 七种实现的吞吐对比、`Collections.unmodifiableList` 为什么不是不可变。

> 一句话记住这一期：**类加载器决定"你是谁"，record/sealed 决定"你能是什么"，虚拟线程决定"你能同时存在多少个"。前两个在编译期救你，最后一个在运行期救你——但它救不了你的数据库连接池。**
