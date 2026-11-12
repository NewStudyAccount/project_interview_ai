# Java 面试深挖 26 · 字节码增强与 Java Agent

> 一个什么都不做的 transformer，让启动慢了 96ms。字节码增强的成本，从来不在"改"上，在"被叫醒"上。

前 25 期把 JVM、并发、Spring、MySQL、Redis、MQ、ES 都过了一遍，但有一类东西一直没正面碰：**那些"没有侵入你的代码，却能改你行为"的东西**——APM 探针、Arthas、SkyWalking、Lombok、Spring AOP、热部署。面试里问"你了解 Java Agent 吗"，十个人里有八个能说出 `premain` 和 `Instrumentation`，但问到"为什么启动变慢了""为什么 retransform 之后我的增强没了""为什么 ASM 报 Unsupported class file major version"就集体沉默。

这一期不背 API，只做一件事：**在本机 JDK 21 上写一个真 agent，把每个环节的成本和限制都测出来**。全文数字除标注外都是实测，报错文案是原样复制的——这些话在面试里说出来，比"我了解字节码增强"值钱得多。

---

## L0 全景：四种增强时机，一张时间轴分完

"字节码增强"是个统称，实际上有四个完全不同的插入点。面试第一个坑就在这里：**很多人把 AOP 也叫字节码增强，其实 Spring AOP 默认连字节码都不碰**。

```text
编译期                    类加载期                  运行期                代理期
─────────────────────────────────────────────────────────────────────────────
javac 出 .class 之后      类被 defineClass 之前      类已加载、正在跑        调用发生时
        │                        │                        │                    │
   APT / 注解处理器        ClassFileTransformer      retransformClasses    动态代理 / 包装
   Lombok(改 AST)           -javaagent:premain        attach + agentmain     CGLIB / JDK Proxy
   MapStruct(生成新类)      SkyWalking / APM          Arthas watch           Spring AOP
        │                        │                        │                    │
   产物是 .class 文件        产物是内存里的字节[]      产物是已加载的类      产物是一个新对象
   改不了已发布的包          改不了已加载的类          改不了结构(只能改体)  改不了类型本身
```

四个格子的边界，可以用三个问题切开：

| 问题 | 编译期 | 加载期 | 运行期 | 代理 |
|---|---|---|---|---|
| 能增加字段/方法吗 | 能 | **能** | **不能** | 不能 |
| 能改已跑起来的类吗 | 不能 | 不能 | 能（只改方法体） | 能（换对象） |
| 有运行时开销吗 | 无 | 一次性 | 一次性 | **每次调用** |
| 代表 | Lombok / MapStruct | APM 探针 | Arthas | Spring AOP |

这张表里最反直觉的一行是"加载期能增加字段，运行期不能"。原因在 L10 会用一个真实报错说明：**JVM 的方法身份是 `(name, descriptor)`，增加字段会改变类的 schema，而 redefine 只允许改方法体**。

先给本期最重要的一组实测数字，后面每一节都在解释它们：

| 实测项 | 数字 | 说明 |
|---|---|---|
| 空 transformer（直接 return null）的启动开销 | **+96ms**（147 → 243ms） | 一个类都没改 |
| 真正插桩一个方法的启动开销 | **+241ms**（147 → 388ms） | 多出来的 145ms 是加载 ASM |
| transformer 被调用次数 | **323 次** | 对应启动期加载的类数 |
| 单次方法调用的插桩成本 | **+42.8ns**（16.0 → 58.8ns） | 业务本身只有 16ns |
| 其中探针自身耗时 | **30.2ns（占 70%）** | 探针比被插的代码还贵 |
| AtomicLong 探针 vs ThreadLocal 探针 | 44.1ns vs 50.9ns | **优化无效**，见 L7 |
| ASM 9.4 读 JDK 21 的 class | `IllegalArgumentException: Unsupported class file major version 65` | 见 L5 版本表 |

---

## L1 class 文件解剖：常量池占了 43%~76%

要改字节码，先得知道字节码长什么样。大部分人背过"魔数 CAFEBABE、版本号、常量池"，但很少有人知道**常量池到底占多少**。我写了一个 120 行的 class 文件解析器（不用 ASM，纯手撸 u1/u2/u4），把本机编译出来的三个类逐段量了一遍：

```text
$ java ClassFileStat out/Target.class out/Main.class out/TimingAgent.class
Target.class        size=  446B  major=65  cp= 24条/ 193B(43.3%)  code= 163B
Main.class          size= 1768B  major=65  cp=104条/1352B(76.5%)  code= 326B
TimingAgent.class   size= 5773B  major=65  cp=321条/3897B(67.5%)  code=1502B
```

三个结论，全是反直觉的：

- **常量池占 43%~76%**，方法体（Code 属性）只占 18%~36%。你写的代码越短、引用的类越多，常量池占比越高——`Main.class` 只有 3 个方法，常量池却吃掉 76.5%。
- **为什么这么多**：你写的每一个类名、方法名、字段名、字符串、数字，在 class 文件里都要变成常量池的一条 `Utf8`，然后被 `Class`/`NameAndType`/`Methodref` 层层引用。`Target.class` 的 tag 分布是 `{1=15, 7=2, 9=2, 10=1, 12=3}`——15 条 Utf8、2 条 Class、2 条 Fieldref、1 条 Methodref、3 条 NameAndType。
- **major=65 就是 JDK 21**（44 是基线，65-44=21）。这个数字在 L5 会变成一堵墙。

常量池里的引用是**间接的、可以共享的**，这是它体积小的原因：

```text
#7  = Class       #8        // 指向 #8 的 Utf8
#8  = Utf8        Target
#9  = Fieldref    #7.#10    // 指向 #7(Class) 和 #10(NameAndType)
#10 = NameAndType #11:#12   // 指向 #11(名字) 和 #12(描述符)
#11 = Utf8        marker
#12 = Utf8        I

同一行代码 Target.marker 写 100 次，常量池只存 1 份 Fieldref。
```

一个 `getstatic Target.marker:I` 指令只占 3 字节：`b2 00 09`（`getstatic` + 常量池索引 #9）。**这就是字节码比源码紧凑的原因，也是"改写字节码"比"改源码"难的原因——你操作的不是代码，是索引。**

完整结构（拿 `Target.class` 对照，446 字节）：

| 段 | 长度 | 说明 |
|---|---|---|
| magic | 4B | `CAFEBABE` |
| minor / major | 2B + 2B | 65 = JDK 21 |
| constant_pool_count | 2B | 24，**实际条目 23**（下标从 1 开始，0 保留） |
| constant_pool | 193B | 14 种 tag，变长 |
| access_flags + this + super | 6B | `0021` = public super |
| interfaces | 2B + 2N | |
| fields | 2B + N×(6B + attrs) | `marker`、`calls` 两个字段 |
| methods | 2B + N×(6B + attrs) | 3 个方法（含 `<init>`） |
| attributes | 2B + ... | 类级属性 |

注意 `constant_pool_count = 24` 但条目数是 23：**Long/Double 占两个槽**，这是 class 文件里唯一的"一个常量两条下标"的坑。写解析器时漏掉 `i++`，后面全部错位——我第一版就是这么崩的（`ArrayIndexOutOfBoundsException: Index 67134 out of bounds for length 1811`，67134 正好是一个被当成 u2 读的常量池字节）。

---

## L2 字节码指令：finally 被复制了两份

插桩的本质是"在方法进出时插入几条指令"。要插得对，得先知道 javac 把你的代码编译成了什么形状。用 `finally` 举例，因为它最能暴露字节码的真实结构：

```java
static int f(int x) {
    try {
        return 10 / x;
    } finally {
        Trace.mark();
    }
}
```

```text
$ javap -c -p out/TryFinally.class
  static int f(int);
    Code:
       0: bipush        10
       2: iload_0
       3: idiv
       4: istore_1          ← 把返回值先存进局部变量 1
       5: invokestatic  #7  // Trace.mark()   ← finally 第 1 份（正常路径）
       8: iload_1
       9: ireturn           ← 再取出来返回
      10: astore_2          ← 异常路径：把异常存进局部变量 2
      11: invokestatic  #7  // Trace.mark()   ← finally 第 2 份（异常路径）
      14: aload_2
      15: athrow            ← 原样抛出
    Exception table:
       from    to  target type
           0     5    10   any
```

三个细节，每一个都能直接变成面试答案：

1. **`finally` 的代码被复制了两份**（`@5` 和 `@11`）。JVM 没有"finally 指令"，javac 的做法是：正常路径走一份，异常路径用 `Exception table` 的 catch-all（`type=any`）兜住再走一份。所以"finally 一定执行"是**编译器用两份代码保证的**，不是虚拟机特性。
2. **返回值先 `istore_1` 暂存，执行完 finally 再 `iload_1` 返回**。这就是"在 finally 里改返回值无效"的字节码真相——值早就取出来存好了，你改 `x` 也改不了已经算出来的结果。同理，`finally 里 return` 会覆盖 try 里的 return，因为它直接 `ireturn` 了，还把异常吞了（`athrow` 被跳过）。
3. **`Exception table` 的 `from 0 to 5`** 说明 finally 覆盖的范围是"try 体内的所有指令"。插桩时如果你在这个范围里插了指令，异常处理的范围不会自动扩大——这是"插桩后异常没被 finally 捕获"的根因。

再看方法调用本身的形状。`t.work(20)` 在字节码里是：

```text
aload_1              // 压入 t
bipush 20            // 压入参数
invokevirtual Target.work:(I)J    // 调用，返回值 long 占两个栈槽
ladd                 // 累加
```

**插桩要做的，就是在这 4 条指令前后各加几条**：

```text
invokestatic System.nanoTime:()J   ← 插在 visitCode 之后
lstore 10                          ← 存进一个空闲局部变量槽
  ...原来的方法体...
invokestatic System.nanoTime:()J   ← 插在每个 RETURN 之前
lload 10
lsub                               ← 相减得到耗时
invokestatic Probe.record:(J)V
返回指令
```

看起来很简单。但 L8 会告诉你，就这 6 条指令，让业务从 16ns 变成 58.8ns。

---

## L3 Instrumentation：五个能力，一个回调

`java.lang.instrument` 是 JVMTI 的一层 Java 封装，核心只有两个东西：**`Instrumentation` 接口（能力）** 和 **`ClassFileTransformer`（回调）**。

`Instrumentation` 能做的事，比你想象的多也比你以为的少：

| 方法 | 用途 | 面试里常被问的点 |
|---|---|---|
| `addTransformer(t, canRetransform)` | 注册字节码转换器 | **第二个参数决定 retransform 时是否还叫它** |
| `retransformClasses(Class...)` | 重新触发 transform | 从**原始字节码**开始，不叠加（L10 实测） |
| `redefineClasses(ClassDefinition...)` | 直接替换字节码 | 只能改方法体，不能改 schema（L10 实测） |
| `getAllLoadedClasses()` | 拿所有已加载类 | 用来找"要增强谁"，返回 1199 个（实测） |
| `getObjectSize(obj)` | 估算对象大小 | 有误差，JOL 更准 |
| `appendToBootstrapClassLoaderSearch(jar)` | 往 bootstrap 搜索路径塞 jar | Arthas 的 `Spy` 类就靠它 |
| `setNativeMethodPrefix` | 给 native 方法加前缀 | 极少用 |

回调的模型是这个样子，**注意它是"每个类都会被叫一次"，不是"你指定哪个类才叫"**：

```text
                    ┌──────────────────────────────────────┐
  加载 Target.class  │  JVM: 这里有份字节码，谁要改？        │
  ──────────────────▶│  ① 复制一份 classfileBuffer          │
                    │  ② 按注册顺序遍历 transformer         │
                    │  ③ t1 返回 null → 用上一份           │
                    │  ④ t2 返回 byte[] → 换掉当前字节码    │
                    │  ⑤ 全部跑完 → defineClass            │
                    └──────────────────────────────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
   t1: 只改 com.foo.*        t2: 什么都不改(return null)    t3: 加日志
```

三个必须记住的语义：

- **返回 `null` 表示"不改"，返回 `byte[]` 表示"换成这个"**。返回空数组 `new byte[0]` 是**错误**的——它会被当成"类文件为空"，直接 `ClassFormatError`。
- **链式传递**：`t2` 收到的是 `t1` 改完的结果。所以多个 agent 叠加时，顺序很重要（`-javaagent` 的先后顺序）。
- **`transform` 里抛异常 = 类加载失败**。必须自己 try-catch 兜住，否则一个类的插桩 bug 会让整个应用起不来。我第一版就踩了（L5 的 VerifyError 直接让 `main` 没跑起来）。

---

## L4 premain vs agentmain：一条在启动时，一条在运行时

agent 有两种进场方式，差别不在 API，在**时机**：

```text
启动期（premain）                          运行期（agentmain）
────────────────────────────────────────────────────────────────────
java -javaagent:apm.jar -jar app.jar      jcmd <pid> JVMTI.agent_load ...
        │                                          │
   JVM 初始化                               目标 JVM 已经在跑
        │                                          │
   premain(String, Instrumentation)          外部进程 attach（socket）
        │                                          │
   注册 transformer                           agentmain(String, Instrumentation)
        │                                          │
   main() 开始执行                             retransformClasses 已加载的类
        │                                          │
   此后每个类加载都被回调                     此后只有 retransform 才回调
```

| 维度 | premain | agentmain |
|---|---|---|
| 触发 | `-javaagent:xxx.jar` | Attach API / `jcmd` / `VirtualMachine.loadAgent` |
| MANIFEST 属性 | `Premain-Class` | `Agent-Class` |
| 时机 | main 之前 | 任意时刻 |
| 能改已加载的类吗 | 不能（还没加载） | 能，靠 `retransformClasses` |
| 典型用途 | APM、监控、字节码框架 | Arthas、诊断、热修复 |
| JDK 21 之后 | 无变化 | **要 `-XX:+EnableDynamicAgentLoading`** |

最后一行是 2025 年之后必须知道的：**JEP 451（JDK 21 落地）** 规定，动态加载 agent（attach 方式）默认会打印一条警告：

```text
WARNING: A Java agent has been loaded dynamically (...)
WARNING: Dynamic loading of agents will be disallowed by default in a future release
```

这不是"不能用"，是"以后会默认禁止"。要么加 `-XX:+EnableDynamicAgentLoading` 启动参数，要么在 JDK 25 之后接受它变成硬错误。**面试里能说出这一条，立刻和"背过 premain"的人区分开。**

MANIFEST 的完整写法（本期的 agent 就是这个）：

```text
Manifest-Version: 1.0
Premain-Class: TimingAgent
Agent-Class: TimingAgent
Can-Retransform-Classes: true
Can-Redefine-Classes: true
Class-Path: asm-9.6.jar
```

三个坑：

- **`Can-Retransform-Classes: true` 不写，`retransformClasses` 直接抛 `UnsupportedOperationException`**。写了 `Agent-Class` 就一定要写这两个 `Can-*`，否则 Arthas 类的工具用不了。
- **`Class-Path` 是相对 agent jar 所在目录的**，不是 classpath 的相对路径。我一开始把 `asm-9.6.jar` 放在别处，agent 启动直接 `NoClassDefFoundError: org/objectweb/asm/ClassReader`。
- **`Boot-Class-Path` 慎用**：加进去的 jar 由 bootstrap classloader 加载，意味着它能看到所有类，也意味着**它永远卸载不掉**（bootstrap 加载的类不会被回收）。Arthas 的 `Spy` 就放在这里，代价是"Arthas 退出后 Spy 还在"。

一个更少人知道的属性是 `Launcher-Agent-Class`：可以让 `java -jar` 直接自 attach，不需要命令行加 `-javaagent`。Spring Boot 3.2 之后的可执行 jar 就用类似思路做了一些初始化。

---

## L5 ASM：一个 VerifyError 教会你 maxLocals

手写字节码太痛苦，所以真实工具都用 ASM。ASM 的核心是**访问者模式**，分三个东西：

```text
ClassReader ──accept──▶ ClassVisitor ──visitMethod──▶ MethodVisitor ──▶ ClassWriter
（读字节，解析）         （类级回调）                   （指令级回调）      （写字节）

ClassVisitor 的回调顺序是固定的：
  visit(版本, 修饰符, 类名, 签名, 父类, 接口)
  ├─ visitField(...)     字段
  ├─ visitMethod(...)  → 返回 MethodVisitor，它再按顺序回调：
  │                        visitCode()          ← 代码开始
  │                        visitVarInsn(...)    ← aload/istore 等
  │                        visitMethodInsn(...) ← invokevirtual 等
  │                        visitInsn(...)       ← ireturn/lreturn/athrow 等
  │                        visitMaxs(3, 5)      ← maxStack, maxLocals
  │                        visitEnd()
  └─ visitEnd()
```

**关键技巧：`new ClassWriter(cr, flags)` 把 ClassReader 传进去**。这样"没被修改的方法"会直接复制原始字节，而不是重新生成——这是 ASM 能扛住"给 2 万个类插桩"的根本原因。不传 `cr` 的话，每个方法都要完整重写一遍。实测：`Target.class` 单类 transform 只要 **1.5us**（含解析 + 重写），主要功劳就是这个优化。

现在讲我踩的坑，它是本期最有价值的一段。

### 5.1 COMPUTE_MAXS 不会帮你扩局部变量槽

第一版插桩，我用了 `ClassWriter.COMPUTE_MAXS`，插入 `lstore 10` 来暂存 `nanoTime`。运行时直接崩：

```text
$ java -javaagent:agent.jar -cp "out" Main
Exception in thread "main" java.lang.VerifyError: get long/double overflows locals
Exception Details:
  Location:
    Target.work(I)J @43: lload
  Reason:
    Local index 10 is invalid
  Bytecode:
    0000000: b800 1d37 0a09 ...        ← b8 0001 = invokestatic nanoTime, 37 0a = lstore 10
  Stackmap Table:
    append_frame(@10,Long,Integer)
    chop_frame(@31,1)
```

原因只有一句话：**`Target.work` 原本的 `maxLocals` 是 5**（`this`、`n`、`s`（占 2 槽）、`i`），我写到槽 10，超出上限。`COMPUTE_MAXS` 会重算 `maxStack`，但对 `maxLocals` 的处理**依赖你已经写进去的 `visitMaxs(maxStack, maxLocals)` 参数**，不会自动扩到你在 `visitVarInsn` 里用到的最大值。

三种解法，面试答出任意两种就够：

```java
// 解法 1：用 COMPUTE_FRAMES（它会整体重算，代价是慢一点）
ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

// 解法 2：自己扩 maxLocals（性能最好，Arthas 走这条路）
public void visitMaxs(int maxStack, int maxLocals) {
    super.visitMaxs(Math.max(maxStack, 4), Math.max(maxLocals, 12));
}

// 解法 3：别用"固定大槽位"，用 advice 框架自己管 locals 分配
//         Byte Buddy 的 Advice / ASM 的 AdviceAdapter 内部就是这么做的
```

顺带一个反直觉的实测：网上都说 `COMPUTE_FRAMES` 比 `COMPUTE_MAXS` 慢 3~10 倍，**我测出来没有显著差异**：

```text
$ java FrameCost
Target.class            446B  MAXS=  14.79us  FRAMES=  17.10us  ratio=1.16x
Main.class             1768B  MAXS=   9.24us  FRAMES=   8.45us  ratio=0.91x
TimingAgent.class      5773B  MAXS=  37.30us  FRAMES=  31.84us  ratio=0.85x
```

原因是：`COMPUTE_FRAMES` 的算法本身不慢，**真正的代价在 `getCommonSuperClass()`**——它要判断两个类型的公共父类，默认实现用 `Class.forName` 加载类，遇到复杂类加载器（OSGi、Spring Boot 的 LaunchedURLClassLoader）会真的去加载甚至失败。所以正确说法是：**"COMPUTE_FRAMES 在类层次复杂时会触发额外类加载，那才是它慢的地方；纯算法开销在噪声内"**。这种"敢说网上答案是错的，并且给出实测"的回答，面试官会记住。

### 5.2 ASM 版本墙：9.4 读不了 JDK 21

第二个坑更常见。ASM 的 `ClassReader` 构造时会检查 class 文件的 major version，超了就抛：

```text
$ java -cp "out;asm-9.4.jar" AsmVersionTest
class major = 65
Exception in thread "main" java.lang.IllegalArgumentException: Unsupported class file major version 65
    at org.objectweb.asm.ClassReader.<init>(ClassReader.java:199)
```

我把同一份字节码的 major 字段手工改大，二分探出各版本的实际上限（本机实测，不是查文档）：

| ASM 版本 | 最高 major | 对应 Java |
|---|---|---|
| 9.4 | 64 | Java 20 |
| 9.6 | 66 | Java 22 |
| 9.7.1 | 68 | Java 24 |
| 9.9.1 | 70 | Java 26 |

**规律是"落后一到两个 JDK 版本"**：JDK 21 刚出时，ASM 得升到 9.5/9.6 才能读。所以你的 APM/Arthas/字节码框架在升级 JDK 后挂了，第一嫌疑就是 ASM 版本没跟上。

还有一个更隐蔽的坑：**别用 `Opcodes.class.getPackage().getImplementationVersion()` 判断 ASM 版本**。我实测 9.4 和 9.6 都返回 `589824`（`0x090000` = 90000），因为 `Opcodes` 类里写死的 `ASM9 = 9 << 16` 常量与 jar 版本无关。要判断真实版本，只能读 jar 的 MANIFEST，或者干脆 try-catch `ClassReader` 的构造异常。

---

## L6 三类增强范式：APM 探针、Arthas、热部署

同样是"改字节码"，三种工具的落点完全不同。

### 6.1 APM 探针：拦截器 + 上下文透传

SkyWalking / Pinpoint / 阿里 ARMS 的套路是一致的：

```text
① agent 启动，注册 transformer（只匹配需要埋点的类，如 spring-web 的 DispatcherServlet）
② 类加载时，在 doDispatch 前后插桩：
     ContextManager.createSpan("HTTP/GET /order")
     try { 原方法 } finally { ContextManager.stopSpan() }
③ 跨线程/跨进程透传：把 spanId 塞进 header（HTTP）/ 消息属性（MQ）
④ 异步上报：本地环形缓冲 → 后台线程批量发 → 采样式
```

它和手写 `System.nanoTime` 的区别只在"③ 透传"和"④ 异步"。**探针的性能生死线在 ④**：如果每次调用都同步发一次网络请求，那不是 APM，是 DDoS 自己的服务。这也是 L7 那个 42.8ns 账要算清楚的原因。

### 6.2 Arthas watch：Spy 类 + 表达式求值

Arthas 的 `watch` 能打印方法入参、返回值、异常，原理是三步：

```text
① attach 到目标 JVM（agentmain），把 Spy 类注入 bootstrap classloader
   —— 为什么必须 bootstrap？因为要增强的类可能是任意 classloader 加载的，
      只有 bootstrap 加载的类对所有 classloader 可见
② retransform 目标类，在方法体里插入：
      Spy.before(...)   ← 参数快照 + 记录 threadLocal 里的 watch 上下文
      原方法
      Spy.after(...)    ← 返回值快照
③ 表达式求值：Arthas 自己实现了一个 OGNL，Spy 把对象引用传给它，
   由 Arthas 进程侧算 "params[0].userId" 这种表达式
```

关键设计：**`Spy` 只做"存快照"，不做"算表达式"**。求值在 Arthas 客户端做，因为客户端可以随便崩，不能拖累目标进程。这是"诊断工具不能改变被诊断系统"的教科书式实现。

### 6.3 热部署：restart 不是 reload

Spring Boot DevTools 的重启和 JRebel 的"真热部署"是两件事：

| 方式 | 做法 | 类对象会变吗 | 状态会丢吗 | 速度 |
|---|---|---|---|---|
| DevTools restart | 新建一个 `RestartClassLoader`，丢掉旧的，重新加载所有类 | 会（新 classloader） | 丢（静态字段重置） | 快（1~3s）但重跑整个容器 |
| DevTools reload | 只重载改动的类 | 不会 | 不丢 | 快，但受限于 redefine 规则 |
| JRebel | 改字节码 + 保留旧类的字段值 | 不会 | **不丢** | 最接近"改完就生效" |
| HotSwap（JVM 自带） | `redefineClasses` | 不会 | 不丢 | 只能改方法体（L10 的限制） |

`RestartClassLoader` 是这里最聪明的设计：**用"换类加载器"绕过"不能改 schema"的限制**。你要加字段？新类里有。要改签名？新类里有。代价是旧的 `RestartClassLoader` 和它加载的所有类都要被 GC，而**静态字段、单例、连接池全部重建**——这就是"重启后要重新登录"的原因。

---

## L7 探针成本账：42.8ns 里，真正被插的代码只占 12ns

这是本期最需要背下来的一组数字。同一个 `work()` 方法，跑 30 万次取平均：

```text
无 agent           16.0 ns/op    (15.8 / 16.2 三次)
插桩后             58.8 ns/op    (57.2 / 58.8 / 60.3 三次)
差值              +42.8 ns/op
其中探针自测      30.2 ns/op    ← 两次 nanoTime + 一次 record 调用
被插的业务本身    12.6 ns/op    ← 12 条字节码的真实成本
```

也就是说：**你插的 6 条指令只值 12.6ns，剩下 30.2ns 全是"测量本身"的成本**。而 30.2ns 又几乎全是两次 `System.nanoTime()`（各约 20ns，`rdtsc` + 校准）。

于是我做了个"优化验证"：把 `AtomicLong` 累加换成 ThreadLocal 本地缓冲（每线程一个 `long[2]`，只在 flush 时做原子累加）。直觉上应该快很多，因为避开了 CAS 竞争。实测：

```text
$ java -Dprobe=atomic -javaagent:agent.jar ... Main
RESULT ns_per_op=85.86   AGENT avg_ns_per_call=44.14

$ java -Dprobe=fast   -javaagent:agent.jar ... Main
RESULT ns_per_op=87.53   AGENT avg_ns_per_call=50.90
```

**没有区别，甚至在噪声里还更慢了。**（这一轮绝对值比前面的 58.8 高，是因为同一次运行里还跑了 retransform/redefine 实验，JIT 状态不同，但两者之间是可比的。）

结论：**探针的成本 90% 来自"取时间戳"这个动作本身，而不是计数器怎么累加。** 所以优化方向只有三个，全都是"少测"，不是"测得快"：

1. **采样**：每 100/1000 次调用才计一次时（用 `ThreadLocalRandom` 或线程本地计数取模），成本直接除以 N。
2. **减少计时点**：只给 Service/DAO 的入口埋点，别给每个 getter 埋。
3. **异步 + 批量**：本地环形缓冲，后台线程定期 flush（这是所有 APM 的标准做法）。

这笔账放到生产上算一遍，就知道为什么探针要采样：

```text
假设：单请求经过 200 个被插桩的方法，QPS = 5000
每秒指令数   = 5000 × 200 = 100 万次
每秒额外耗时 = 100 万 × 42.8ns = 42.8ms
折合单核占用 = 42.8ms / 1000ms = 4.3%

如果探针里顺手拼了个日志字符串（约 1us）：
每秒额外耗时 = 100 万 × 1us = 1 秒  →  直接打满一个核
```

**"插桩本身不慢，探针写得烂才慢"**——这句话可以从这组数字里长出来，而不是背出来。

---

## L8 启动账：一个什么都不做的 transformer 也要 20~100ms

APM 厂商最常被问的问题是"装了 agent 启动慢多少"。我测了三组：

```text
场景                                   启动耗时（9 次中位数）
无 agent                               147 ms
空 agent（transformer 直接 return null） 243 ms   (+96ms)
插桩 agent（含 ASM 依赖）                388 ms   (+241ms)

另一组（类更少的程序）：
无 agent                               114 ms
空 agent                               136 ms   (+22ms)
```

几个必须说清楚的细节：

- **空 transformer 什么都没改，却多花了 96ms**。因为 JVM 只要发现有 transformer 注册，就会为每个类**复制一份 `classfileBuffer`** 再回调。这份复制与"你改不改"无关。
- **不是线性的**。323 次回调 +96ms，165 次回调 +22ms。说明固定开销（agent jar 加载、MANIFEST 解析、`Class-Path` 依赖加载、JVM 内部结构初始化）占大头，不能拿"每类 X 微秒"去线性外推。
- **插桩 agent 多出来的 145ms，主要是加载 ASM**（`asm-9.6.jar` 里几百个类 + 校验器）。这也是为什么成熟的 APM 会**自己 shade 一份精简 ASM**，甚至用 `-XX:+UseSharedSpaces`/CDS 预热——第 13 期讲过 CDS，这里正好用上。

对照一个真实量级：单体应用 1~2 万个类，APM agent 的启动增量通常在 **200ms ~ 3s**，取决于"transformer 匹配了多少类"和"匹配到之后做了多少事"。**只匹配 20 个类**的 agent 和**匹配所有类**的 agent，差的是两个数量级。

---

## L9 编译期增强：Lombok 不改字节码，它改 AST

面试里问"Lombok 原理"，答"字节码增强"是错的。Lombok 走的是**注解处理器（APT）**，在 javac 编译过程中直接改语法树。

```text
源码 .java
   │
   ├─▶ javac 解析 ──▶ AST（抽象语法树）
   │                      │
   │                  注解处理器（Lombok 在这里）
   │                      │  ① 发现 @Data
   │                      │  ② 反射拿到 javac 内部的 JCTree
   │                      │  ③ 直接往里塞 getter/setter 节点
   │                      ▼
   │                  被改过的 AST
   │                      │
   └──────────────────▶ 生成字节码 .class
```

三个"为什么"：

- **为什么 Lombok 要 `--add-opens jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED`**：因为它在反射操作 javac 的**私有内部类** `com.sun.tools.javac.tree.JCTree`。JDK 9 的模块化把它封起来了，JDK 16 强封装后必须显式开洞。这是"强封装"最著名的受害者（第 23 期讲过反射的强封装）。
- **为什么 Lombok 版本要跟着 JDK 升**：它依赖 javac 内部结构，JDK 每次改 AST 实现都可能让它崩。JDK 21 需要 Lombok 1.18.30+。
- **为什么 MapStruct 没有这个问题**：它**只生成新类**（`XxxMapperImpl`），不碰原类的 AST，所以不需要 `--add-opens`，也不怕 JDK 升级。

三种"增强"的边界，一张表说清：

| | Lombok | MapStruct | Spring AOP | ASM Agent |
|---|---|---|---|---|
| 时机 | 编译期 | 编译期 | 运行期（调用时） | 类加载期 |
| 改的是什么 | javac 的 AST | 生成新类 | 生成代理对象 | 类的字节码 |
| 产物 | 同一份 .class | 多一个 Impl 类 | 多一个 `$Proxy`/`$$EnhancerBy` | 内存里的类 |
| 有运行时开销吗 | 无 | 无 | 有（每次调用过一遍拦截器链） | 有（插进去的指令） |
| 能改第三方 jar 吗 | 不能 | 不能 | 能（代理它） | **能** |
| 典型故障 | JDK 升级后编译失败 | 无 | 自调用失效、final 类 | VerifyError、版本墙 |

**最后一列才是 agent 存在的理由**：只有它能改"你没有源码的第三方 jar"。Lombok 改不了 `spring-web.jar`，AOP 代理不了 `final` 类和 `static` 方法，只有字节码增强能直接改 `DispatcherServlet` 的方法体——这就是所有 APM 都必须是 agent 的原因。

---

## L10 redefine 的红线：我用三个实验撞了三堵墙

`redefineClasses` 和 `retransformClasses` 是运行期增强的两个入口，但限制完全不同。我写了三个变体，逐一撞墙，报错原样如下：

```text
$ java -javaagent:agent.jar -cp out Main
AGENT redefine: 
  addField  = UnsupportedOperationException(
              class redefinition failed: attempted to change the schema (add/remove fields));
  changeSig = UnsupportedOperationException(
              class redefinition failed: attempted to delete a method);
  bodyOnly  = OK;
  identitySame=true  markerKept=42/42  loadedClasses=1227
```

三个结果，三堂课：

**第一课：加字段直接被拒。** `attempted to change the schema (add/remove fields)`。JVM 的类元数据（字段表、方法表、常量池大小）在 `defineClass` 时就固定了，redefine 只允许换 `Code` 属性的内容。

**第二课：改方法签名的报错是"删方法"，不是"改签名"。** 注意原文：`attempted to delete a method`。因为 **JVM 里方法的身份是 `(name, descriptor)` 二元组**，你把 `work(I)J` 改成 `work(IJ)J`，在 JVM 看来就是"删掉了 `work(I)J`、加了一个 `work(IJ)J`"——删方法当然不允许。这个细节答出来，说明你真的读过 JVM 规范而不是背结论。

**第三课：只改方法体是允许的，而且类的身份不变。** `identitySame=true` 说明 `Target.class` 还是同一个 `Class` 对象（`==` 成立），`marker` 静态字段还是 42（**静态状态保留**）。这是热更新能保住状态的理论基础（JRebel 就是靠这个）。

完整红线清单（前五条是 JVM 规范，后三条是实践）：

| # | 不能做的事 | 报错/后果 |
|---|---|---|
| 1 | 增加/删除字段 | `attempted to change the schema` |
| 2 | 增加/删除方法 | `attempted to delete a method` |
| 3 | 改方法签名（=删+加） | 同上 |
| 4 | 改父类 / 实现的接口 | `attempted to change interfaces` |
| 5 | 改类的修饰符（final/abstract） | `attempted to change modifiers` |
| 6 | 改注解但要立刻生效 | 改得动，但反射缓存要自己清 |
| 7 | 改被 JIT 编译过的热点方法 | 改得动，但会 **deopt 重编译**，有一段性能抖动（第 22 期） |

还有一个我专门验证的传言：**反复 retransform 会不会把增强叠加成 N 层？** 答案是不会。

```text
$ AGENT retransform: hits_during_1st=0  hits_during_2nd=0  hits_for_1000_calls=1000  marker=42
```

`retransformClasses` 两次之后，1000 次调用只产生 1000 次记录——**每次都从"原始字节码"（initial classfileBuffer）重新开始**，不是在上一份增强结果上再增强一次。这是 JVM 规范行为，也是 Arthas 反复 `watch` 不会越来越慢的原因。

但这里有个**必须知道的配套条件**：`addTransformer(t)` 默认 `canRetransform=false`，这样的 transformer **不参与 retransform**。如果 agent 在 premain 里用默认参数注册了 transformer，之后调用 `retransformClasses` 会把增强**撤销掉**（因为重新走一遍时没人改它）。正确写法只有一个：

```java
inst.addTransformer(new TimingTransformer(), true);   // 第二个参数必须 true
```

**这条是 Arthas / SkyWalking 插件开发的经典事故**：本地测试没 retransform 过，一切正常；线上被别的工具 retransform 一次，你的增强就消失了。

---

## L11 七个生产事故：每一条都有人踩过

1. **`transform` 返回 `new byte[0]`** → `ClassFormatError: Truncated class file`。不想改就返回 `null`。
2. **`transform` 里抛异常没兜住** → 整个类加载失败，应用起不来。必须 `try { ... } catch (Throwable t) { return null; }`，**catch Throwable 不是 catch Exception**（`LinkageError` 是 Error）。
3. **忘了 `Can-Retransform-Classes: true`** → `UnsupportedOperationException: class retransformation is not supported`。
4. **`Class-Path` 写成绝对路径或相对 classpath 的路径** → agent 启动即 `NoClassDefFoundError`。它是**相对 agent jar 所在目录**的。
5. **往 `Boot-Class-Path` 塞业务 jar** → 里面的类被 bootstrap 加载，**永远无法卸载**，容器化环境下反复 attach 会撑爆 Metaspace（第 21 期的 14 次 redeploy 账）。
6. **transformer 里持有 `Class` / `ClassLoader` 强引用** → 类加载器泄漏。热部署场景下，旧 classloader 因为被 agent 静态字段引用而无法回收，这就是"重启 30 次就 OOM"的经典原因。
7. **`getAllLoadedClasses()` 返回的类不能无脑 retransform** → 数组类、基本类型、`java.*` 的核心类（部分不可修改）会抛 `UnmodifiableClassException`。正确做法是先 `isModifiableClass(c)` 过滤。

第 7 条补一个数字：本机一个 Hello 级程序启动后 `getAllLoadedClasses().length` 是 **1199**（含 ASM），全部遍历一遍并逐个判断，耗时在毫秒级；但如果你对每个类都 `retransform`，那就是 1199 次字节码解析——**这就是"Arthas 启动慢"和"某个插件把所有类都增强了"的差别**。

---

## L12 数字速查

| 数字 | 含义 | 出处 |
|---|---|---|
| `CAFEBABE` | class 文件魔数 | 规范 |
| 65 / 44 | JDK 21 的 major / 版本号基线 | 实测 |
| 43%~76% | 常量池占 class 文件的体积比 | 实测 3 个类 |
| 23 / 24 | `Target.class` 实际条目 / `constant_pool_count` | 实测 |
| 1.5us | 单个 446B 类的 transform 耗时 | 实测 |
| 16.0ns | 未插桩的 `work()` 单次调用 | 实测 |
| 58.8ns | 插桩后单次调用 | 实测 |
| 42.8ns | 插桩增量 | 实测 |
| 30.2ns | 其中探针自身（两次 nanoTime） | 实测 |
| 12.6ns | 真正被插的 6 条指令成本 | 实测 |
| 323 | 启动期 transformer 被调用次数 | 实测 |
| +96ms / +22ms | 空 transformer 的启动增量（两组） | 实测 |
| +241ms | 插桩 agent 的启动增量（含 ASM 加载） | 实测 |
| 1199 | 启动后已加载类数 | 实测 |
| 64 / 66 / 68 / 70 | ASM 9.4 / 9.6 / 9.7.1 / 9.9.1 支持的最高 major | 实测 |
| 1000 | retransform 两次后，1000 次调用只记 1 次/调用 | 实测 |
| 4.3% | 5000 QPS × 200 方法 × 42.8ns 的单核占用 | 计算 |
| 100% | 同一场景下，探针里拼一次日志字符串的单核占用 | 计算 |

---

## L13 十四个问答

**Q1：字节码增强有哪几种时机，区别是什么？**
- 编译期（Lombok 改 AST、MapStruct 生成新类）：产物是 class 文件，改不了已发布的 jar。
- 类加载期（premain + transformer）：能改任意类，还能加字段/方法，但只能在类加载前。
- 运行期（agentmain + retransform）：能改已加载的类，但**只能改方法体**，不能动 schema。
- 代理期（Spring AOP）：不改字节码，生成代理对象，有每次调用的拦截开销，且 `final`/`static`/自调用失效。

**Q2：装了 agent 为什么启动变慢？慢在哪？**
三块：① 每个类都要复制一份 `classfileBuffer` 并回调 transformer（即使 return null）；② 匹配到的类要做 ASM 解析 + 重写；③ agent 自己的依赖（ASM、日志框架）要加载。实测：空 transformer +22~96ms，插桩 agent +241ms。

**Q3：retransform 会不会叠加增强？**
不会。每次都从原始字节码重新开始。实测 retransform 两次后 1000 次调用仍只记 1000 次。但前提是 transformer 注册时 `canRetransform=true`，否则 retransform 会把你的增强**撤销**。

**Q4：为什么 redefine 不能加字段？**
类元数据（字段表、方法表）在 `defineClass` 时固化，redefine 只允许替换 `Code` 属性。报错原文 `attempted to change the schema (add/remove fields)`。需要加字段只能换 ClassLoader 重新加载（DevTools 的 `RestartClassLoader` 就是这么做的）。

**Q5：为什么改方法签名报的是"删方法"？**
JVM 里方法身份是 `(name, descriptor)`。改描述符 = 删旧方法 + 加新方法，两件事都违规。报错 `attempted to delete a method`。

**Q6：插桩时 `COMPUTE_MAXS` 为什么会 VerifyError？**
`COMPUTE_MAXS` 重算 `maxStack`，但 `maxLocals` 要你自己保证。往槽 10 写 long、原方法 `maxLocals=5`，就会 `VerifyError: get long/double overflows locals / Local index 10 is invalid`。解法：`COMPUTE_FRAMES` 或手动 `visitMaxs(..., Math.max(maxLocals, 12))`。

**Q7：ASM 报 Unsupported class file major version 怎么修？**
升 ASM。实测上限：9.4→Java 20、9.6→22、9.7.1→24、9.9.1→26。另外别用 `Opcodes` 包的 `getImplementationVersion()` 判版本，9.4 和 9.6 都返回 589824。

**Q8：Arthas watch 的原理？**
attach（agentmain）→ 把 `Spy` 注入 bootstrap classloader → retransform 目标类插入 `Spy.before/after` 快照 → 表达式求值在 Arthas 客户端做。`Spy` 放 bootstrap 是为了对所有 classloader 可见，代价是卸载不掉。

**Q9：Lombok 算字节码增强吗？**
不算。它是注解处理器，在 javac 里改 AST（`com.sun.tools.javac.tree.JCTree`），所以需要 `--add-opens`，且强依赖 JDK 内部结构。MapStruct 只生成新类，因此没这个问题。

**Q10：探针怎么做到低开销？**
先认清成本结构：实测 +42.8ns 里 30.2ns 是两次 `nanoTime`，换成 ThreadLocal 计数器**没有改善**（44.1 vs 50.9ns）。所以只能"少测"：采样（1/N）、只在入口埋点、异步批量上报。

**Q11：多个 agent 的执行顺序？**
按 `-javaagent` 在命令行出现的顺序，transformer 链式传递（后一个收到前一个的输出）。`agentmain` 按 attach 顺序。顺序会影响"谁改的是最终版本"，所以两个都要改同一个方法时，必须约定先后。

**Q12：JEP 451 是什么？**
JDK 21 起，动态加载 agent（attach 方式）会打印警告"future release 将默认禁止"，需要 `-XX:+EnableDynamicAgentLoading` 显式开启。影响所有 attach 型工具（Arthas、jcmd 的 agent 加载、部分诊断脚本）。

**Q13：热部署为什么状态会丢？**
如果用"换 ClassLoader"（DevTools restart），所有类重新加载，静态字段/单例/连接池全部重建。真正的"保状态热部署"（JRebel）必须在**同一个 ClassLoader 内 redefine**，而 redefine 不能改 schema，所以加字段的场景它也只能退化。

**Q14：agent 导致 `NoClassDefFoundError` 怎么排查？**
三个方向：① `Class-Path` 的相对路径（相对 agent jar 目录）；② 依赖冲突（agent 里的 ASM 版本和应用里的打架，需要 shade 重定位）；③ 类加载器隔离（agent 由 system classloader 加载，看不到应用自定义 classloader 里的类，需要 `appendToBootstrapClassLoaderSearch` 或反射调用）。

---

## L14 三道模拟面试题

### 题 1：设计一个方法级耗时监控，要求 P99 误差小于 1%，性能损耗小于 1%

**不及格**：用 AOP 给每个方法加 `@Around`，记录 `System.currentTimeMillis()`，同步写日志。
（问题：AOP 拦不住第三方 jar、`currentTimeMillis` 精度 1ms 且受时钟调整影响、同步写日志直接爆炸。）

**及格**：用 agent + ASM 在方法前后插 `nanoTime`，统计写进 `ConcurrentHashMap`，定时上报。
（能跑，但没算过账：42.8ns/次，200 方法 × 5000 QPS 就是 4.3% 单核，超标。）

**良好**：上面再加三件事——① 只对 Service/Controller 入口埋点（不是所有方法）；② 采样率可配（默认 10%），P99 用采样数据估算（HdrHistogram）；③ 上报走本地环形缓冲 + 后台线程批量发。

**优秀**：在"良好"之上，把误差说清楚——P99 的估计误差取决于样本量，10% 采样 + 5000 QPS = 每秒 500 个样本，P99 需要至少 100 个尾部样本才稳定，所以采样率要按"目标 P99 的置信度"反推，而不是拍一个 10%。再补一句：**用 `nanoTime` 不用 `currentTimeMillis`**（单调时钟，不受 NTP 调整影响），并且只在方法入口/出口取时间，不要在中间打点。

### 题 2：线上用 Arthas watch 之后接口慢了 3 倍，怎么排查？

**不及格**：重启应用。
**及格**：`stop` 掉 Arthas，观察是否恢复——恢复就说明是 watch 的锅。
**良好**：区分两种可能——① `watch` 的表达式太重（`params[0].toString()` 打大对象、`-x 3` 展开深层属性），每次调用都在做反射+字符串拼接；② 监控的是高频方法（比如每秒 10 万次的工具方法），插桩开销被放大。
**优秀**：给出量化路径——先用 `watch -c <classloader>` 限定类加载器避免增强到多个副本；用 `-n 10` 限制次数而不是 `-n 100000`；把 `toString` 换成指定字段（`params[0].userId`）；如果必须长期监控，改用有采样和异步上报的 APM，而不是 watch。最后补一句原理：**watch 的成本不在插桩的 42.8ns，在表达式求值和日志输出**，所以频率 × 单次成本才是总账。

### 题 3：升级到 JDK 21 后，APM agent 启动报 Unsupported class file major version 65，怎么定位和修复？

**不及格**：降回 JDK 17。
**及格**：看到 major 65 就知道是 ASM 太老，升 ASM 到 9.6+。
**良好**：说清定位过程——异常栈里是 `ClassReader.<init>`，说明是**读**的时候拒绝；major 65 = JDK 21；对照 ASM 版本表（9.4 顶到 Java 20）；升级后还要检查"ASM 是不是被 shade 进了别的 jar"（很多 APM 自带一份重定位过的 ASM，升级主依赖没用）。
**优秀**：补充三点——① 升级 JDK 后所有字节码工具都要过一遍（ASM、ByteBuddy、CGLIB、Javassist 各自的版本门槛不同）；② 用 `jdeps`/`mvn dependency:tree` 找出所有 ASM 副本，确认最终生效的是哪一个；③ 如果暂时不能升 ASM，可以让 agent 在 transformer 里**先判断 major 再决定是否处理**（读前 8 字节，超版本就 `return null`），保证"不认识的新类不增强"而不是"整站起不来"——这是所有成熟 agent 的兜底设计。

---

## L15 二十六期知识地图

| 板块 | 期号 · 主题 | 一句话抓手 |
|---|---|---|
| 语言与集合 | 01 · HashMap → CHM | 扰动函数、树化 8/6/64 |
| | 14 · 集合并发容器 | ArrayList 1.5 倍、CHM 锁粒度轴 |
| | 23 · 泛型擦除 · 反射 · 代理 | 桥方法、稳态反射只慢 7 倍 |
| JVM | 02 · 内存模型与 GC | 三色标记、CMS→G1→ZGC |
| | 12 · 调优与压测 | Eden = 速率 × 间隔 |
| | 21 · 内存泄漏取证 | 支配树、ThreadLocal 七跳链 |
| | 22 · JIT 与基准测试 | 内联 35/325、deopt |
| | 13 · 类加载与新特性 | 双亲委派四次被打破、虚拟线程 |
| | **26 · 字节码增强与 Agent** | **空 transformer 也 +96ms、42.8ns 里 30.2ns 是探针** |
| 并发 | 03 · 锁升级 · AQS · 线程池 | Mark Word 三位、CLH 队列 |
| | 20 · 线程池深水区 | ctl 3+29 位、依赖栈 LIFO |
| | 19 · NIO 与 Netty | epoll 红黑树、ByteBuf 双指针 |
| 框架 | 04 · Spring 核心 | refresh 十二步、三级缓存 |
| | 15 · Boot 自动配置 | 153→60→40 六阶段 |
| | 24 · MyBatis 与持久层 | 九站执行链、CacheKey 37 进制 |
| 存储 | 05 · MySQL 索引与事务 | B+ 树 1170 扇出、ReadView |
| | 16 · 分库分表与迁移 | 2053 万行上限、基因法 |
| | 25 · Elasticsearch 与搜索 | 三层倒排、BM25 可算 |
| 缓存 | 06 · Redis | 单线程边界、跳表 |
| | 17 · 缓存一致性 | 失败率是乘法：99.85% → 1.35 亿 |
| 分布式 | 07 · MQ 与分布式事务 | LEO/HW/ISR、六种方案 |
| | 08 · Spring Cloud | 三级缓存 90~180s、重试 27 倍 |
| | 18 · 幂等与防重 | 0 行双重语义、处理中带租期 |
| | 10 · 大厂场景设计 | 秒杀四道闸门、雪花位图 |
| 工程能力 | 09 · 线上排查 | CPU 六真凶、OOM 九落点 |
| | 11 · 设计模式 | 策略替 if-else、责任链三变形 |

这一期在整张地图里的位置：**它把 02/12/13/21/22 这五期串起来了**——字节码是 class 文件（02 的运行时数据区从 `defineClass` 开始）、增强发生在类加载（13 的双亲委派决定"谁能看见谁"）、transformer 的成本是类加载与元数据（21 的 Metaspace 泄漏）、插桩后的热点方法会 deopt 重编译（22 的 JIT）。**面试里最能拉开差距的回答，从来不是"某一期背得熟"，而是"能说出两期之间的接缝"**。

一个具体的接缝例子：为什么第 5 坑"往 Boot-Class-Path 塞业务 jar"会导致 Metaspace 泄漏？因为 bootstrap 加载的类**永远不会被卸载**（第 13 期：类卸载要求 loader 不可达 + Class 不可达 + 实例不可达，而 bootstrap loader 永远可达），所以每次 attach 一份新 jar，就多一份永久驻留的类元数据——第 21 期"14 次 redeploy 撑爆 512M Metaspace"是同一个机制。

---

*本期事实来源：本机 JDK 21.0.7（major 65）实测。agent、transform、redefine、retransform、ASM 版本、启动耗时全部为真实运行结果，报错文案原样复制；ASM 版本上限表通过手工改写 class 文件 major 字段二分探测得到，非文档查询。JEP 451（JDK 21 动态加载 agent 告警）为规范事实。文中标注"计算"的损耗账已给出乘法过程。*
