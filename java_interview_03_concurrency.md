# Java 面试深挖 03 · 并发编程：锁升级 / volatile / AQS / 线程池

> 每 2 小时一期 · 由浅入深 · 代码 + 图解 + 原理 + 模拟面试

并发题的分水岭不在「你会不会背锁升级」，而在你能不能回答这四个连续追问：

**锁到底锁的是什么 → 不锁的时候编译器凭什么不乱序 → 排队的人怎么被唤醒 → 谁来决定开几个线程**

这四问，正好是 L0 到 L4。

| 层级 | 主题 | 难度 | 一句话 |
|---|---|---|---|
| L0 | JMM 契约 | ★★☆ | 不描述内存，只描述规则 |
| L1 | synchronized 锁升级 | ★★★ | 锁的是对象头里的 Mark Word |
| L2 | volatile 与内存屏障 | ★★★ | 管可见性与有序，不管原子性 |
| L3 | AQS 队列 | ★★★★ | 一个 state + 一条双向队列 |
| L4 | 线程池 | ★★★ | 核心→队列→非核心→拒绝，只有这一条路 |
| L5 | 12 条高频问答 | — | 30 秒答案 + 深挖方向 |
| L6 | 3 道模拟面试 | — | 递进式，含加分点与雷区 |

---

## L0 契约层：JMM 不描述内存，只描述规则

### 一句话答案

JMM（Java 内存模型）是一份**规范**，它不告诉你内存长什么样，只规定**多线程读写共享变量时必须遵守什么约束**。核心图景是：每个线程有自己的**工作内存**（CPU 缓存/寄存器的抽象），共享变量放在**主内存**，线程不能直接改主内存，必须 `read → load` 到工作内存改完再 `store → write` 回去。由此派生三大特性：**原子性、可见性、有序性**——以及唯一能严谨判断「这段代码安不安全」的工具：**happens-before**。

先纠正一个 90% 的人都会犯的错：把 JMM 和 JVM 运行时数据区混为一谈。上一期讲的堆、栈、方法区是**物理划分**（对象放哪）；JMM 讲的是**访问规则**（一个线程写的值，另一个线程什么时候能看到）。堆是共享的没错，但共享 ≠ 可见——这才是所有并发 bug 的根源。

### 图 1　JMM 抽象结构

```
┌─────────────────────────────┐                ┌──────────────────────────────┐
│ 线程 A 工作内存              │   read→load    │  主内存 Main Memory           │
│ 栈帧局部变量/寄存器/L1 缓存  │ ─────────────► │  所有线程共享 · 唯一的「真相」 │
│                             │                │                              │
│  count 副本 = 0             │ ◄───────────── │  count = 0                   │
│  flag  副本 = false         │  store→write   │  flag  = false               │
└─────────────────────────────┘                │                              │
                                               │  实例字段 / 静态变量 / 数组元素 │
┌─────────────────────────────┐   read→load    │  都躺在这里                    │
│ 线程 B 工作内存              │ ─────────────► │                              │
│                             │                │  局部变量不在此列               │
│  count 副本 = 0  ← 也是 0！  │ ◄───────────── │  （栈私有，天然线程安全）        │
│  flag  副本 = false         │  store→write   │                              │
└─────────────────────────────┘                │  8 个原子操作：lock/unlock      │
                                               │  read/load/use/assign/store/write │
                                               └──────────────────────────────┘
```

A 和 B 各自持有 count 的副本，**谁也不知道对方把它改成了几**——这就是「共享但不可见」。

### 先把 bug 摆出来

```java
public class Counter {
    private int count = 0;            // 共享变量，躺在主内存
    public void incr() { count++; }
    public int get()   { return count; }
}

Counter c = new Counter();
Thread t1 = new Thread(() -> { for (int i = 0; i < 10000; i++) c.incr(); });
Thread t2 = new Thread(() -> { for (int i = 0; i < 10000; i++) c.incr(); });
t1.start(); t2.start(); t1.join(); t2.join();
System.out.println(c.get());   // 期望 20000，实际经常是 1.3 万 ~ 1.9 万之间某个数
```

`count++` 在字节码层面是**四步**，不是一步：

```java
// javap -c 反编译 incr() 的真实字节码
aload_0                  // ① 把 this 压栈
dup
getfield  #count         // ② 读字段值到操作数栈顶  ← 读的是【副本】
iconst_1
iadd                     // ③ 栈顶 +1           ← 在【寄存器】里算
putfield  #count         // ④ 写回字段          ← 再【刷回】主内存
```

两个线程交错在 ②③④ 之间，就是经典的**丢失更新**：A 读 0、B 读 0、A 写 1、B 写 1——两次自增，结果只加了 1。这不是「缓存不同步」这么简单，是**读-改-写三个动作不原子**。加 `volatile` 也救不了，因为 volatile 只保证可见性和有序性，**不保证复合操作的原子性**。

### happens-before：唯一严谨的判据

定义很短：如果 **A happens-before B**，那么 A 的执行结果对 B **可见**，且 A 在 B **之前**（在并发语义上，不一定是墙上时钟顺序）。

> **最容易答错的一句**：happens-before **不等于**时间先后。单线程里 `int a = 1; int b = 2;` 有 hb 关系，但 JIT 完全可以先执行 `b = 2`。hb 约束的是**可观测结果**：只要别的线程看到了 b = 2，就必须也能看到 a = 1。这叫 as-if-serial——单线程语义不能被破坏，多线程的观测点被 hb 钉住。

JSR-133 的 8 条规则，面试背 6 条就够，但**必须能举出代码**：

| 规则 | 内容 | 典型场景 |
|---|---|---|
| **程序次序** | 单线程内，书写在前面的操作 hb 后面的操作 | 同一方法内的语句 |
| **监视器锁** | 对同一把锁的 unlock hb 后续的 lock | `synchronized` 释放后另一个线程拿到 |
| **volatile** | 对 volatile 变量的写 hb 后续对该变量的读 | 状态标志位、DCL 单例 |
| **线程启动** | `Thread.start()` hb 该线程内任何操作 | 启动前赋的值，子线程一定看得到 |
| **线程终止** | 线程内所有操作 hb 其他线程从 `join()` 成功返回 | join 之后读结果安全 |
| **中断** | `interrupt()` hb 被中断线程检测到中断 | 中断标志的可见性 |
| **对象终结** | 构造函数结束 hb `finalize()` 开始 | 已废弃，了解即可 |
| **传递性** | A hb B，B hb C ⇒ A hb C | **组合技**，见下图 |

### 图 2　happens-before 的传递链

```
线程 A（写）                                        线程 B（读）

┌──────────────┐   ┌──────────────────┐   ┌──────────────┐   ┌──────────────┐
│ ① a = 1      │   │ ② volatile       │   │ ③ 读 flag     │   │ ④ 读 a       │
│ 程序次序      │   │    flag = true   │   │   == true    │   │ 必须读到 1    │
│ 普通写        │   │ volatile 写       │   │ volatile 读   │   │ 不可能读到 0  │
│ 无屏障        │   │ = 释放锁语义      │   │ = 获取锁语义  │   │              │
└──────┬───────┘   └────────┬─────────┘   └──────┬───────┘   └──────┬───────┘
       │         hb          │         hb          │         hb        │
       │◄───────────────────►│◄───────────────────►│◄────────────────►│
       │                     │                     │                   │
       └─────────────────────┴──── 传递性 ─────────┴───────────────────┘
                    ① hb ② hb ③ hb ④  ⇒  ① hb ④
```

**线程 B 即使从未见过 a = 1 这行代码，也一定能读到 1 —— 而不是 0。**

这就是 volatile 用来做「安全发布（safe publication）」的全部原理：把「初始化对象」与「写引用」用一条 volatile 写 + StoreStore 屏障捆成一个整体。

把这张图记住，L2 的 DCL 单例就是它的直接应用，L3 的 AQS 解锁-加锁也是同一回事（监视器锁规则）。**并发编程在语义层面只有这一条主线：用某个「同步点」把两段代码焊成一个 hb 链条。**

---

## L1 锁层：synchronized 锁的到底是什么

### 一句话答案

`synchronized` 锁的不是代码块，而是**对象头里的 Mark Word**（或者 Class 对象，针对静态方法）。HotSpot 为省一次系统调用，把锁做成**四级状态**：无锁 → 偏向锁 → 轻量级锁 → 重量级锁，只升不降，标志位就藏在 Mark Word 最低 2 位。

### 先看字节码：两种加锁姿势

```java
public void block() {
    synchronized (this) {   // 同步代码块
        count++;
    }
}
// 字节码：monitorenter ... monitorexit（编译器保证异常路径也有一份 monitorexit）

public synchronized void method() {   // 同步方法
    count++;
}
// 字节码：没有 monitorenter！靠方法访问标志 ACC_SYNCHRONIZED，
//        由 JVM 在方法调用时隐式加锁，退出（含异常）时隐式解锁
```

> **第一个雷区**：答「synchronized 锁的是代码块」直接出局。代码块是**加锁的位置**，不是**锁的对象**。两个线程执行同一个对象的两个不同 synchronized 方法会互斥，因为它们抢的是同一个 `this`；而 `synchronized (new Object())` 等于没锁——每次都是新对象，锁不住任何人。

### 图 3　Mark Word 的复用结构（64 bit）

```
对象头 Mark Word（64 bit）· HotSpot                        ← 高位          低位 →

无锁         ┌──────────────────────┬──────────────────────────┬────────────┐
             │ unused  25 bit       │ identity_hashcode  31 bit│age4 b1 01  │
             └──────────────────────┴──────────────────────────┴────────────┘

偏向锁       ┌─────────────────────────────────────────────────┬────────────┐
             │ ThreadID 54 bit ＋ epoch 2 bit                  │age4 b1 01  │
             └─────────────────────────────────────────────────┴────────────┘

轻量级锁     ┌────────────────────────────────────────────────┬──┐
             │ ptr_to_lock_record（指向栈帧内 Lock Record）62bit│00│
             └────────────────────────────────────────────────┴──┘

重量级锁     ┌────────────────────────────────────────────────┬──┐
             │ ptr_to_ObjectMonitor（指向堆外的监视器）   62bit │10│
             └────────────────────────────────────────────────┴──┘

GC 标记      ┌────────────────────────────────────────────────┬──┐
             │ 空（不存任何信息）  62 bit                       │11│
             └────────────────────────────────────────────────┴──┘

最低 2 bit 是状态开关：01 无锁/偏向 · 00 轻量级 · 10 重量级 · 11 GC
倒数第 3 bit（biased_lock）区分「无锁」和「偏向」
```

**锁升级 = 改写同一块 64 bit**，所以它天然是原子的 CAS 操作对象；也是为什么「hashCode 一旦被调用，对象就不能再偏向」——两者抢同一个字段。

### 图 4　锁升级路径

```
 ┌──────────┐        ┌──────────┐        ┌──────────┐        ┌──────────┐
 │  无锁     │───────►│ 偏向锁    │───────►│ 轻量级锁  │───────►│ 重量级锁  │
 │          │        │          │        │          │        │          │
 │ 标志位 01 │        │标志位01+b=1│       │ 标志位 00 │        │ 标志位 10 │
 │ 零开销    │        │1 次 CAS   │        │CAS+自旋   │        │park/unpark│
 └──────────┘        └────┬─────┘        └────┬─────┘        └──────────┘
                          │                   │
                          ▼                   ▼
              首次进入 CAS 写 ThreadID    CAS 失败 / 自旋超限
              之后重入只比对 ID          → 膨胀为 ObjectMonitor
              同一线程零 CAS             之后只升不降
                          │                   │
              另一线程来抢 → 撤销偏向
              撤销须对齐 safepoint
              代价：一次 STW 全局暂停
```

**关键洞察：升级是「单向棘轮」，一旦膨胀到重量级就再也回不去**——所以 JVM 对「能不能升级」极其谨慎，宁可多自旋几圈。

- 偏向锁 JDK 6 引入、**JDK 15 默认禁用（JEP 374）、JDK 18 彻底移除**。原因：撤销要 STW，而现代应用锁竞争频繁，偏向收益抵不过撤销成本。
- 自旋是**自适应**的：同一个锁上次自旋成功过，这次就多转几圈；从没成功过就干脆不转直接膨胀。
- 自旋必须在**多核**上才有意义。单核自旋等于白烧 CPU——线程不放弃时间片，持锁线程根本没机会跑。
- 另有两个编译器级优化：**锁消除**（逃逸分析证明对象不出方法 → 直接删掉 monitorenter）、**锁粗化**（循环里反复加解锁 → 提到循环外）。

### 图 5　轻量级锁的 CAS 与重量级锁的 ObjectMonitor

```
轻量级锁：一次 CAS 解决，靠栈帧当「锁记录」

┌──────────────────┐   CAS   ┌──────────────────────┐
│ 栈帧 Lock Record │ ──────► │ 对象头 Mark Word      │
│                  │ ◄────── │                      │
│ Displaced MW 备份│  还原    │ 加锁前：hash|age|01  │
│ owner = null     │         │ 加锁后：ptr→|00      │
└──────────────────┘         │ 指针指向栈里的 LR    │
                             └──────────────────────┘

① 在当前线程栈帧里划一块 Lock Record
② 把对象头 Mark Word 复制进去（Displaced）
③ CAS 把对象头改成「指向 Lock Record」的指针
   CAS 失败 → 自适应自旋；再失败 → 膨胀
④ 解锁：CAS 把 Displaced 原值写回对象头


重量级锁：ObjectMonitor，线程真的被 park 住了

┌────────────────────────────────────┐    ┌──────────────────────────────────┐
│ ObjectMonitor（C++ 对象，在堆外）    │    │ _WaitSet 与调度                   │
│ ┌────────────────────────────────┐ │    │ ▸ 竞争失败 → _EntryList，被 park │
│ │ _owner  当前持锁线程 T1         │ │    │ ▸ T1 exit 时唤醒 EntryList 一个  │
│ ├────────────────────────────────┤ │    │ ▸ wait() → 进 _WaitSet（释放锁） │
│ │ _cxq  刚失败、未排队的( LIFO )  │ │    │ ▸ notify() → 搬回 _EntryList     │
│ ├────────────────────────────────┤ │    │ ▸ 每次 park/unpark 可能触发内核  │
│ │ _EntryList 排队等锁的( FIFO )   │ │    │   态切换，微秒级                  │
│ └────────────────────────────────┘ │    └──────────────────────────────────┘
└────────────────────────────────────┘
```

> **为什么轻量级锁比偏向锁「重」却比重量级锁「轻」**：轻量级锁假设「两个线程交替执行，不是同时」，所以 CAS 一次就够，全程用户态；重量级锁假设「真的会同时抢」，于是干脆挂起线程，代价是内核态切换 + 调度延迟（典型 1~10 微秒）。**自旋的意义就是：用几纳秒的 CPU 空转，去赌能省掉几微秒的线程切换。**

---

## L2 可见性层：volatile 与内存屏障

### 一句话答案

`volatile` 干两件事：**① 写后立即刷主存、读时强制从主存读（可见性）**；**② 在前后插入内存屏障，禁止指令重排序（有序性）**。它**不保证原子性**——`i++`、`check-then-act` 照样出错。

### 四种屏障

| 屏障 | 禁止的重排 | 通俗解释 |
|---|---|---|
| **LoadLoad** | 上面的读 与 下面的读 | 先读完上面的，再去读下面的 |
| **StoreStore** | 上面的写 与 下面的写 | 先刷完上面的写，再刷下面的 |
| **LoadStore** | 上面的读 与 下面的写 | 读完了才能写 |
| **StoreLoad** | 上面的写 与 下面的读 | **最贵的一种**，x86 上用 `lock` 前缀实现 |

### 图 6　屏障插入位置

```
volatile 写（释放语义）                       volatile 读（获取语义）

┌──────────────────────────────┐          ┌──────────────────────────────┐
│ 普通写 a = 1（可能还在 buffer）│          │ LoadLoad 屏障  ↓              │
└──────────────────────────────┘          └──────────────────────────────┘
┌──────────────────────────────┐          ┌──────────────────────────────┐
│ StoreStore 屏障  ↑            │   hb     │ volatile 读 flag == true      │
│ 保证 a=1 先刷出                │ ───────► │ 强制走主存                    │
└──────────────────────────────┘          └──────────────────────────────┘
┌──────────────────────────────┐          ┌──────────────────────────────┐
│ volatile 写 flag = true       │   hb     │ LoadStore 屏障  ↓             │
│ （立即刷主存）                 │ ───────► │                              │
└──────────────────────────────┘          └──────────────────────────────┘
┌──────────────────────────────┐          ┌──────────────────────────────┐
│ StoreLoad 屏障  ↓             │          │ 普通读 a（此时一定读到 1）      │
│ 禁止与后面的读重排             │          │                              │
└──────────────────────────────┘          └──────────────────────────────┘
```

JSR-133 的等价视角：**volatile 写 = 释放锁（unlock），volatile 读 = 获取锁（lock）**。所以「volatile 变量 + 它之前的普通变量」构成一个原子发布的整体；而「volatile 变量之后」的代码，两边都不受约束。

x86 是强内存模型（TSO），LoadLoad/LoadStore/StoreStore 天然不重排，只剩 StoreLoad 需要 lock 指令——所以 volatile 写的汇编是：

```asm
lock addl $0x0, (%rsp)   ; 空操作 + lock 前缀，起到全屏障作用
```

**记法：写侧「前 StoreStore、后 StoreLoad」，读侧「前 LoadLoad、后 LoadStore」**，四面围住，只留最新值进出。

### 为什么 DCL 单例非要加 volatile

```java
public class Singleton {
    private static volatile Singleton instance;   // ← 这个 volatile 不是装饰

    public static Singleton getInstance() {
        if (instance == null) {                   // 第一次检查：避免每次加锁
            synchronized (Singleton.class) {
                if (instance == null) {           // 第二次检查：防止重复创建
                    instance = new Singleton();   // ★ 问题就在这一行
                }
            }
        }
        return instance;
    }
}
```

`new Singleton()` 不是原子的，字节码里是三步：

```java
memory = allocate();      // ① 分配内存，此时字段全是默认值 0/null
ctorInstance(memory);     // ② 执行构造函数，字段被真正赋值
instance = memory;        // ③ 把引用写到 instance 字段

// ② 和 ③ 之间没有数据依赖，JIT / CPU 允许重排成 ① → ③ → ②
```

> **不加 volatile 的翻车现场**：线程 A 执行到 ③（引用已可见，对象还是半成品），线程 B 在第一次检查处看到 `instance != null`，**直接 return 了这个半成品**——拿到的对象里所有字段都还是 null。这不是理论问题，是 x86 上真实复现过的经典 bug。加了 volatile 之后，StoreStore 屏障把 ② 和 ③ 焊死，杜绝重排。

那既然锁已经保证了可见性，**第二次检查能不能省？** 不能。两个线程同时通过第一次检查（都是 null），A 拿锁创建完释放，B 随后拿锁——如果里面不再判一次，B 会**再创建一个**，单例变双例。所以：**volatile 管「有序」，内层 if 管「唯一」，外层 if 管「性能」，三者各司其职。**

### 数形结合：这些操作差多少个数量级

| 操作 | 典型耗时 | 相对代价（以 L1 命中为 1） |
|---|---|---|
| L1 缓存命中 | ≈ 1 ns | 1× |
| volatile 读（x86 普通 mov） | ≈ 1 ns | 1× |
| CAS（无竞争） | ≈ 5 ~ 20 ns | 5 ~ 20× |
| volatile 写（lock 前缀，需刷 store buffer） | ≈ 20 ~ 50 ns | 20 ~ 50× |
| 无竞争 synchronized（偏向/轻量级） | ≈ 20 ns | 20× |
| 有竞争 synchronized（膨胀为重量级） | ≈ 1 ~ 10 μs | **1000 ~ 10000×** |
| 线程上下文切换 | ≈ 2 ~ 10 μs | 2000 ~ 10000× |

这张表能直接回答一串「为什么」：为什么说 `synchronized` 重？因为它一旦膨胀就跨了三个数量级。为什么 `ReentrantLock` 不一定比 synchronized 快？因为 JDK 6 之后两者膨胀路径同样走 `LockSupport.park`，差别只在**可中断、可超时、可多条件队列**这些功能面。为什么不能滥用 `AtomicInteger` 做计数器？高竞争下 CAS 不停失败重试，白白烧 CPU，这时候 `LongAdder` 的分段累加（Cell 数组按线程散列）才解决问题——**把一个热点拆成 N 个热点**。

```java
// LongAdder 的核心思想：空间换竞争
// AtomicLong：所有线程 CAS 同一个 value  → 高竞争时大量自旋失败
// LongAdder ：base + Cell[]，线程按 probe 散列到不同 Cell
//             sum() 时把 base 和所有 Cell 累加（所以 sum 不是原子的快照）

LongAdder adder = new LongAdder();
IntStream.range(0, 4).parallel().forEach(i -> {
    for (int j = 0; j < 1_000_000; j++) adder.increment();
});
System.out.println(adder.sum());   // 4000000，吞吐通常高于 AtomicLong 数倍
```

---

## L3 排队层：AQS 只做三件事

### 一句话答案

AQS（`AbstractQueuedSynchronizer`）就是一个 **volatile int state + 一条 FIFO 双向队列**。想抢资源：先 `CAS` 改 state，改成功就拿到，改失败就**把自己包成 Node 挂到队尾并 park**；释放时改回 state 并 **unpark 队头**。至于「state 等于几算拿到」，交给子类实现 `tryAcquire / tryRelease`。

| 层 | AQS 提供的 | 子类只需实现的 |
|---|---|---|
| **状态** | `volatile int state` + `getState/setState/compareAndSetState` | state 的语义（重入次数？许可数？计数？） |
| **排队** | Node 双向队列、`addWaiter`、`acquireQueued`、`park/unpark` | —（完全复用） |
| **策略** | 模板方法骨架 | `tryAcquire / tryRelease`（独占）、`tryAcquireShared / tryReleaseShared`（共享） |

这就是**模板方法模式**的标准示范：AQS 把「怎么排队、怎么阻塞、怎么唤醒」这套又脏又容易写错的活全包了，子类只回答一个业务问题——「什么条件下算抢到」。

### 图 7　AQS 队列结构

```
┌───────────┐      ┌────────────┐  next  ┌────────────┐  next  ┌────────────┐  next  ┌──────────┐
│  state    │─────►│   head     │───────►│  Node T2   │───────►│  Node T3   │───────►│   tail   │
│ volatile  │      │  哨兵节点   │        │ ws = -1    │        │ ws = 0     │        │ 新来的   │
│ int       │      │ thread=null│        │ SIGNAL     │        │ 初始态     │        │ 挂这里   │
│ CAS 战场  │      │ 持锁者不入队│        │ 已 park    │        │ 也 park    │        │CAS 尾插  │
└───────────┘      └────────────┘        └────────────┘        └────────────┘        └──────────┘
                     ◄──── prev ──────────── ◄──── prev ──────────── ◄──── prev ────────

一个 Node 的四个字段：

┌──────────────┬──────────────┬──────────────┬──────────────┐
│ prev         │ next         │ waitStatus   │ thread       │
│ 前驱（取消时  │ 后继（唤醒时  │ volatile int │ 被挂起的线程  │
│ 必须用到）    │ 可能为 null） │ 0/-1/-2/-3/1 │ unpark 时取  │
└──────────────┴──────────────┴──────────────┴──────────────┘
```

waitStatus 取值（考官爱问「SIGNAL 是干嘛的」）：

- `CANCELLED = 1`：线程超时/中断，已放弃，要从队列里摘掉
- `SIGNAL = -1`：**后继节点在睡，我释放时必须 unpark 它** ← 唯一会被主动设置的「承诺」
- `CONDITION = -2`：在 Condition 的单向条件队列里（不在同步队列）
- `PROPAGATE = -3`：共享模式下，唤醒要向后传播（CountDownLatch 靠它）

为什么需要 SIGNAL：线程 park 前必须先给前驱「留话」——如果前驱释放时我还不在队列里，那一唤醒就丢了。所以 acquireQueued 里是「先 CAS 把前驱置为 SIGNAL，再 park」，宁可多一次循环，也不能漏唤醒（lost wakeup）。

### acquire 四步，逐行拆

```java
// AQS 源码（简化注释，逻辑未改）
public final void acquire(int arg) {
    if (!tryAcquire(arg) &&                             // ① 先试一把，子类决定怎么算成功
        acquireQueued(addWaiter(Node.EXCLUSIVE), arg))   // ② 失败：入队 + 排队等
        selfInterrupt();                                 // ④ 补上被吞掉的中断标志
}

// ② 入队：标准 CLH 尾插，CAS 失败就死循环重试
private Node addWaiter(Node mode) {
    Node node = new Node(Thread.currentThread(), mode);
    Node pred = tail;
    if (pred != null) {
        node.prev = pred;
        if (compareAndSetTail(pred, node)) {   // 只有这一步是原子的
            pred.next = node;                  // 这一步晚一点没关系，prev 已经接上了
            return node;
        }
    }
    enq(node);   // 队列为空或 CAS 失败，走 for(;;) 兜底
    return node;
}

// ③ 排队：这才是真正的「等」
final boolean acquireQueued(final Node node, int arg) {
    boolean interrupted = false;
    for (;;) {
        final Node p = node.predecessor();
        if (p == head && tryAcquire(arg)) {   // 只有前驱是 head 才配去抢（保证 FIFO）
            setHead(node);                    // 抢到就自己当哨兵，把老 head 丢出队列
            p.next = null;
            return interrupted;
        }
        if (shouldParkAfterFailedAcquire(p, node) &&   // 把前驱置 SIGNAL，返回 true
            parkAndCheckInterrupt())                   // LockSupport.park() 真睡
            interrupted = true;
    }
}

// 释放：改 state + 唤醒队头
public final boolean release(int arg) {
    if (tryRelease(arg)) {                 // 子类判断「能不能真释放」（重入次数归零）
        Node h = head;
        if (h != null && h.waitStatus != 0)
            unparkSuccessor(h);            // 唤醒 head 后面那个
        return true;
    }
    return false;
}
```

> **为什么是「前驱是 head 才去 tryAcquire」，而不是「队头直接去抢」？** 因为 head 是哨兵、不代表任何线程。真正排队的是 `head.next`。这个设计的妙处：**队列里每个节点只需要盯着自己的前驱**，不需要知道全局谁排第几——出队时把前驱变成新 head 即可，O(1) 复杂度，也没有惊群效应（一次只唤醒一个）。

### 公平锁：只多了一行判断

```java
// ReentrantLock.NonfairSync（默认，非公平）
final void lock() {
    if (compareAndSetState(0, 1))    // ★ 上来就抢，完全不管队列里有没有人排着
        setExclusiveOwnerThread(Thread.currentThread());
    else
        acquire(1);
}

// ReentrantLock.FairSync（公平）
final void lock() {
    acquire(1);                       // 老老实实走 AQS 流程
}

// FairSync.tryAcquire 里比非公平多这一行
protected final boolean tryAcquire(int acquires) {
    if (getState() == 0 && !hasQueuedPredecessors() &&   // ★ 队列前面有人就让路
        compareAndSetState(0, acquires)) {
        setExclusiveOwnerThread(Thread.currentThread());
        return true;
    }
    return false;
}
```

所以「公平锁慢在哪」有精确答案：**每次抢锁前都要判断 `hasQueuedPredecessors()`，而且必须严格排队，导致每次锁交接都伴随一次线程唤醒（park → unpark → 重新调度 ≈ 微秒级）**。非公平锁允许刚到的线程「插队」直接 CAS 成功——锁甚至没有真正释放给排队者，吞吐自然高。代价极端情况下会**饿死**队尾线程。

### state 的数字语义：一个字段，五种解释

| 实现类 | 模式 | state 的含义 |
|---|---|---|
| `ReentrantLock` | 独占 | **重入次数**。首次获取 0→1，同线程再进 1→2，释放到 0 才真正放锁 |
| `Semaphore` | 共享 | **剩余许可数**。`acquire()` 递减，减到负数就排队 |
| `CountDownLatch` | 共享 | **剩余计数**。构造传 N，`countDown()` 递减，归零唤醒**全部**等待者 |
| `ReentrantReadWriteLock` | 共享+独占 | **高 16 位 = 读锁持有数，低 16 位 = 写锁重入数**（一个 int 掰成两半用） |
| `CyclicBarrier` | — | 不是 AQS 实现，用的是 ReentrantLock + Condition（常被拿来对比） |

```java
// ReentrantReadWriteLock 的位运算，很值得在面试里写一笔
static final int SHARED_SHIFT   = 16;
static final int SHARED_UNIT    = (1 << SHARED_SHIFT);      // 0x00010000，读锁 +1
static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;   // 65535，读写各自上限

static int sharedCount(int c)   { return c >>> SHARED_SHIFT; }        // 读锁数
static int exclusiveCount(int c){ return c & ((1 << SHARED_SHIFT) - 1); } // 写锁数

// 为什么读锁 +1 不是 c+1 而是 c+0x10000？因为高位那半段才是读锁的地盘
```

### Condition：第二条队列

`await()` 做的事和 `Object.wait()` 类似但更干净：释放锁 → 把自己从**同步队列**摘掉 → 挂进**条件队列（单向链表）** → park。等别的线程 `signal()`，再把它从条件队列搬回同步队列**尾部**重新排队，此时它**还没拿到锁**，要等前面的人都走了才轮到它。

> **经典坑**：`await()` 必须包在 `while (!条件)` 里，不能用 `if`。原因是 `signal()` 只是「搬回队列」，被唤醒的线程**不保证立刻拿锁、也不保证条件此刻仍成立**（可能被别的线程抢先又改回去了）。这叫**虚假唤醒**（spurious wakeup），是 POSIX 和 JVM 规范都明确允许的行为。

| 对比项 | Object.wait / notify | Condition.await / signal |
|---|---|---|
| 绑定对象 | 只能绑 `synchronized` 的 monitor | 可绑 `ReentrantLock` |
| **条件队列数量** | **1 个**（唤醒只能 notifyAll 或赌运气） | **任意多个**（如 ArrayBlockingQueue 用 notEmpty + notFull） |
| 超时/中断 | 支持超时，不支持中断返回 | `await(timeout, unit)` 返回 boolean；`awaitNanos` 精度更高 |
| 唤醒丢失 | 先 notify 再 wait → 永久丢失 | 同样存在，但多队列设计基本规避了 |

---

## L4 调度层：线程池七参数与拒绝策略

### 一句话答案

七参数：**核心数、最大数、空闲存活时间、时间单位、工作队列、线程工厂、拒绝策略**。执行顺序**只有一条**：核心线程 → 队列 → 非核心线程 → 拒绝。记住这条顺序，90% 的「为什么我的 maximumPoolSize 不生效」都能自己答出来。

### 图 8　线程池四道闸门

```
     execute(command)

 ① 当前线程数 < corePoolSize ？  ── 是 ──► addWorker(command, true)
        │                                  直接新建核心线程
        │ 否                               （注意：只有线程数 < 核心数才建）
        ▼
 ② workQueue.offer() 成功 ？    ── 是 ──► 任务入队
        │                                  核心线程闲下来自己来取
        │ 否                               （入队后会再查一次池状态，兜住竞态）
        ▼
 ③ 当前线程数 < maximumPoolSize ？── 是 ──► addWorker(command, false)
        │                                  新建非核心线程
        │ 否                               （能走到这说明队列已满）
        ▼
 ④ 全部满了                    ───────► handler.rejectedExecution(r, this)
                                           Abort / CallerRuns / Discard / DiscardOldest

⚠ 最大陷阱：若 workQueue 是无界队列（LinkedBlockingQueue 默认 Integer.MAX_VALUE），
   第 ② 步永远成功 → 第 ③ 步永不执行 → maximumPoolSize 形同虚设。
```

### ctl：一个 int 存两种信息

```java
// ThreadPoolExecutor 用一个 AtomicInteger ctl 同时表达「状态 + 线程数」
private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));

private static final int COUNT_BITS = Integer.SIZE - 3;              // 29
private static final int CAPACITY   = (1 << COUNT_BITS) - 1;         // 536870911 ≈ 5.36 亿

// 高 3 位 = 池状态，低 29 位 = 有效线程数
private static int runStateOf(int c)  { return c & ~CAPACITY; }      // 取高 3 位
private static int workerCountOf(int c){ return c & CAPACITY; }      // 取低 29 位

// 五种状态，数值单调递增，所以可以用大小比较判断「至少到哪一步了」
RUNNING    = -1 << 29;   // 接受新任务 + 处理队列
SHUTDOWN   =  0 << 29;   // 不接受新任务，但把队列做完（shutdown()）
STOP       =  1 << 29;   // 不接新、不做队列、中断在跑（shutdownNow()）
TIDYING    =  2 << 29;   // 全空转，准备收尾（钩子 terminated()）
TERMINATED =  3 << 29;   // 彻底结束
```

为什么线程数上限是 `2^29 - 1 = 536,870,911`？因为 32 位 int 被切走 3 位给状态。这个数字你可能一辈子用不到，但**「为什么要这样设计」值得讲**：一次 CAS 就能同时原子地改「状态 + 线程数」，避免两把锁、两个字段的不一致窗口。

### 四种拒绝策略

| 策略 | 行为 | 适用场景 / 风险 |
|---|---|---|
| **AbortPolicy**（默认） | 直接抛 `RejectedExecutionException` | 核心业务，**宁可失败也不能静默丢**；调用方必须 catch |
| **CallerRunsPolicy** | 由**提交任务的线程自己执行** | 天然的**反压（backpressure）**：提交者被拖慢 → 上游自然减速。代价是可能阻塞主线程/IO 线程 |
| **DiscardPolicy** | 静默丢弃，**连异常都不抛** | 几乎等于埋雷。只在「丢了也无所谓」的埋点/日志上报里考虑 |
| **DiscardOldestPolicy** | 丢掉队列里**最老的**一个，再重试提交 | 适合「只要最新数据」的场景；但会静默丢任务，且重试可能导致递归 |

```java
// 生产环境的标准写法：显式命名 + 有界队列 + 自定义拒绝
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    8,                              // corePoolSize
    32,                             // maximumPoolSize
    60L, TimeUnit.SECONDS,          // keepAliveTime：非核心线程空闲 60s 回收
    new ArrayBlockingQueue<>(500),  // ★ 有界！容量按 QPS × 可接受延迟估
    new ThreadFactory() {           // ★ 必须命名，否则 jstack 里全是 pool-1-thread-1
        private final AtomicInteger seq = new AtomicInteger(1);
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "order-async-" + seq.getAndIncrement());
            t.setDaemon(false);
            t.setUncaughtExceptionHandler((th, e) ->
                log.error("async task failed, thread={}", th.getName(), e));
            return t;
        }
    },
    new ThreadPoolExecutor.CallerRunsPolicy()   // 反压，而不是丢任务
);
```

### 数形结合：线程数到底开几个

```java
线程数 = CPU 核数 × (1 + 等待时间 / 计算时间)

// 场景 A：纯计算（加解密、序列化、图像处理）  等待时间 ≈ 0
//   → 线程数 = N + 1（多的 1 个在偶发缺页时顶上，避免 CPU 空转）
//   8 核 → 9 个线程。再多人就是纯抢 CPU，只会增加切换开销

// 场景 B：IO 密集（查库、调 RPC）  等待:计算 = 9:1
//   8 核 × (1 + 9) = 80 个线程
//   这类任务 90% 时间在等网络，CPU 闲着，所以要靠线程数堆并发

// 场景 C：混合型，拆开算
//   假设 50% 时间在等：8 × (1 + 0.5/0.5) = 16
```

但这只是**起点**，不是答案。真实定参的三条约束依次收窄：

1. **下游能扛多少？** 数据库连接池只有 20 个连接，你开 200 个线程只是把 180 个线程变成「排队等连接」的状态，还多消耗了栈内存（默认 1MB/线程，200 个就是 200MB 虚拟内存）。
2. **内存预算够不够？** 每线程栈 1MB（`-Xss` 可调小到 256KB）。1000 线程 ≈ 1GB 栈空间，这在容器里是要命的。
3. **可接受的排队延迟是多少？** 用 Little's Law 反推：并发数 = QPS × 平均耗时。QPS 500、P99 200ms → 至少 100 并发，再留 30% 余量。

> **为什么《阿里巴巴 Java 开发手册》禁止用 `Executors` 造线程池？** 三个工厂方法各有各的雷：
> - `newFixedThreadPool` / `newSingleThreadExecutor`：队列是 `LinkedBlockingQueue`（无界），任务堆积 → **OOM: Java heap space**，且 max 永远不生效。
> - `newCachedThreadPool` / `newScheduledThreadPool`：max 是 `Integer.MAX_VALUE`，来多少任务建多少线程 → **OOM: unable to create new native thread**。
>
> 两个 OOM 方向相反，一个吃堆一个吃线程栈，但结局一样：服务挂掉，`jstack` 都打不出来。

---

## L5 十二条高频问答

每条按「结论 → 依据 → 反问钩子」三段写。面试时先说结论（显专业），再说依据（显扎实），最后留一个钩子让面试官顺着问（显掌控）。

### 1. synchronized 和 ReentrantLock 怎么选？

功能上 ReentrantLock 是超集：**可中断（lockInterruptibly）、可超时（tryLock(3, SECONDS)）、可公平、可多条件队列**。JDK 6 之后两者在无竞争路径上性能相当，**所以别再说「synchronized 比 ReentrantLock 慢」**。

- 能用 synchronized 就用它：语法级、不可能忘记释放（异常自动 monitorexit）、JIT 可做锁消除/粗化。
- 需要「超时放弃」防死锁、需要「可中断」响应取消、需要多个等待条件 → 用 ReentrantLock。

**雷区**：ReentrantLock 必须在 `finally` 里 unlock，而且 `lock()` 要写在 try **外面**——写在里面，若 lock 本身抛异常，finally 会去解锁一把没锁上的锁，抛 `IllegalMonitorStateException` 掩盖真实异常。

### 2. 偏向锁明明能省 CAS，为什么 JDK 15 要默认禁用、JDK 18 直接移除？

因为**撤销偏向的代价远超它省下的东西**。偏向锁的撤销不是简单改个标记位：必须让持锁线程走到一个**安全点（safepoint）**，确认它当前没有在执行同步块，然后才能改 Mark Word。这个过程要 **STW** 全局暂停。

三个叠加因素：

- **现代应用锁竞争变频繁**：偏向锁只在「同一个线程反复进同一个锁、且没有第二个线程」时才有收益；微服务/线程池场景下这个前提经常不成立，撤销却照样付 STW 成本。
- **safepoint 开销被放大**：STW 要等所有线程到达安全点，一个做长循环或大数组拷贝的线程就能把暂停拖到几十毫秒。JEP 374 原文明确写了这是主要动机。
- **维护成本**：偏向锁让 Mark Word 状态机、hashCode 交互、GC 交互全都变复杂，收益却在缩水。

**补一句**：JEP 374（JDK 15）只是默认关闭、仍可用 `-XX:+UseBiasedLocking` 打开；JDK 18 起该参数被标记 obsolete，**彻底退役**。所以现在讲锁升级，标准说法是**「三级：无锁 → 轻量级 → 重量级」**，偏向锁作为历史演进讲。

### 3. volatile 能替代锁吗？

不能。**volatile 只管「可见性 + 有序性」，不管「原子性」**。判断标准只有一条：**这个变量的所有写操作，是否都是「单次读写」而非「读-改-写」复合操作**。

够用的三个场景：

- **状态标志位**：`volatile boolean running`，一个线程写 false，另一个线程循环里读。
- **安全发布**：DCL 单例、初始化后不再改的配置对象引用。
- **一写多读且写不依赖当前值**：如 `volatile int threshold`，配置热更新。

不够用的场景：`count++`、`if (x == null) x = new X()`（check-then-act）、任何「先读后写」的逻辑。

### 4. DCL 单例为什么要判空两次，而且一定要加 volatile？

**两次判空**：外层 if 是**性能**（实例已存在时不用进锁）；内层 if 是**正确性**（两个线程同时通过外层检查，A 建完释放锁，B 拿锁后必须再确认一次，否则会重复创建）。

**volatile**：`new` 分三步——分配内存 → 构造 → 赋值引用。后两步无数据依赖，可以被重排。重排后另一个线程可能拿到「引用非空但字段全是默认值」的**半成品对象**。volatile 的 StoreStore 屏障把「构造」与「赋值」焊死。

**追问预案**：「那静态内部类 / 枚举为什么不用 volatile？」→ 类加载由 JVM 保证线程安全（`<clinit>` 有隐式同步），静态内部类靠**延迟初始化类**实现懒加载；枚举靠 JVM 保证实例唯一，还能防反射和反序列化破坏。这是《Effective Java》第 3 条推荐的做法。

### 5. AQS 的队列为什么必须是双向的？

核心原因：**节点会被「中途取消」**。线程 `tryLock(timeout)` 超时或被中断后，要把自己从队列里摘掉——这需要同时改**前驱的 next** 和**后继的 prev**。单向链表拿不到前驱，摘除就得 O(n) 遍历整个队列。

三个顺带细节：

- **头结点是哨兵**：持锁线程不在队列里，head 的 thread 为 null。这样「出队」变成「把自己设为新 head」，O(1)。
- **入队时先接 prev 再 CAS tail**：CAS 成功后 `pred.next = node` 是普通赋值。哪怕这行还没执行，别人从 tail 反向遍历也能找到新节点——**prev 是「强一致」的，next 是「最终一致」的**，所以 `unparkSuccessor` 在 next 为 null 时要从 tail 往前找。
- **为什么不用 synchronized 包住入队**：AQS 的整个卖点就是无阻塞 + CAS，加锁就白设计了。

### 6. 非公平锁会饿死线程吗？为什么默认非公平？

理论上会，实际上极少。非公平只在**「锁刚释放的瞬间」**允许插队：新线程 CAS 抢到就赢，抢不到照样入队排在后面。但在高竞争 + 短临界区场景下，队尾线程确实可能等很久，这就是所谓的「饥饿」，不是死锁但延迟不可控。

**默认非公平的原因**：公平锁每次交接都要 strict 排队 → 必须唤醒排队线程 → 一次 park/unpark + 上下文切换（微秒级）；非公平允许「刚到的线程直接接上刚释放的锁」，**省掉一次线程切换，吞吐能高一个量级**。绝大多数业务场景要的是吞吐，不是绝对公平。

### 7. 为什么 await() 必须放在 while 循环里？

- **虚假唤醒**：JVM/OS 允许 `wait` 在没有 notify 的情况下返回（POSIX 规范允许）。
- **条件被抢改**：即使是真的被 signal 唤醒，从「被唤醒」到「真正拿到锁」之间还有时间窗口，别的线程可能已经把条件改回去了。
- **notifyAll 误伤**：多消费者场景下，notifyAll 唤醒了 5 个但只有 1 个位置，另外 4 个必须自己重新检查。

标准范式是 `while (!condition) { lock.await(); }`，**而不是 if**。`ArrayBlockingQueue.take()` 的源码就是 `while (count == 0) notEmpty.await();`。

### 8. 我设了 maximumPoolSize = 200，为什么线上只跑到 8 个线程？

因为**队列还没满**。执行顺序是「核心 → 队列 → 非核心 → 拒绝」，只要 `workQueue.offer()` 成功，线程数就永远停在 corePoolSize。而 `new LinkedBlockingQueue<>()` 的默认容量是 `Integer.MAX_VALUE`——**它永远不满**，于是 maximumPoolSize 和 keepAliveTime 全部失效。

正确做法：

- 用 `ArrayBlockingQueue(capacity)`，容量按「QPS × 可接受排队秒数」估。比如 QPS 1000、允许排队 0.5s → 容量 500。
- 或者改用 `SynchronousQueue`（不存储，直接交给线程），此时 max 立刻生效——`newCachedThreadPool` 就是这种组合。
- 监控 `getQueue().size()` 和 `getActiveCount()`，队列持续高位说明该扩 core 而不是 max。

### 9. 核心线程数到底设多少？

三段式回答：

1. **起点用公式**：CPU 密集 N+1；IO 密集 N × (1 + WT/ST)。8 核、等待:计算 = 9:1 → 80。
2. **再被下游约束**：数据库连接池 20 个，那线程开到 80 也只是一半在排队等连接，还可能把连接池打满拖垮其他服务。
3. **最后压测校准**：从公式值往下调，观察 QPS 和 P99——QPS 不再涨、P99 开始抖，就是拐点，回退 20% 作为生产值。

**加分点**：主动提「不同业务用不同池，不要共用一个池」——订单、通知、日志的耗时和重要性完全不同，混在一起会互相拖累。这就是**线程池隔离**，也是 Hystrix/Sentinel 舱壁模式的基础。

### 10. 线上的死锁怎么发现、怎么避免？

四个必要条件：互斥、占有并等待、不可抢占、循环等待。破坏任意一个即可——工程上最容易做的是**破坏循环等待：全局规定加锁顺序**（比如按对象 ID 排序后再加锁）。

```bash
jps -l                          # 先找到进程 pid
jstack <pid> > stack.txt        # 导出线程栈
# 搜索 "Found one Java-level deadlock" —— JVM 会自动检测并打印环形依赖
jcmd <pid> Thread.print -l      # 等价写法，-l 额外打印 ownable synchronizers
arthas: thread -b               # 直接列出阻塞其他线程的「元凶」
```

**更隐蔽的一种——线程池死锁**：任务 A 提交任务 B 到同一个池，然后 `future.get()` 等 B 的结果；池里线程全被 A 类任务占满 → B 永远排不上 → 全池永久挂起。`jstack` 里看不到 "deadlock"，只有一堆 WAITING。解法：父子任务用不同池，或改用 `CompletableFuture` 的异步链。

### 11. ThreadLocal 在池化环境里为什么会串数据？

ThreadLocal 的值挂在 `Thread.threadLocals` 上，**生命周期跟着线程走**。线程池的线程是**复用**的——任务结束线程不死，ThreadLocal 里上一个请求的 UserContext 还在。下一个请求拿到同一个线程，就读到了别人的数据。这是真实的越权事故来源。

```java
try {
    UserContext.set(user);
    doBusiness();
} finally {
    UserContext.remove();   // ★ 必须 remove，不是 set(null)
}
```

为什么必须 `remove()` 而不是 `set(null)`：`set(null)` 会保留 Entry（key 还在），只是 value 变 null，槽位不被回收；`remove()` 才会真正删掉 Entry 并清理 key 为 null 的陈旧节点。异步场景可上 Alibaba TTL（TransmittableThreadLocal）。

### 12. JDK 21 的虚拟线程会不会取代线程池？

取代的是**「为了 IO 密集而把线程数堆到几百」这种做法**，不是线程池本身。虚拟线程是 JVM 调度的用户态线程，阻塞时自动让出载体线程（ForkJoinPool），**一个平台线程可承载成千上万个虚拟线程**——所以 IO 密集场景不再需要精心计算核心数。

但三个坑还在：

- **CPU 密集无收益**：算力就那么多，虚拟线程不会变快。
- **synchronized 会钉住载体线程**（pinning）：JDK 21/22 里在 synchronized 块内阻塞，虚拟线程无法卸载，退化成平台线程。JDK 24（JEP 491）才修掉。所以新代码建议用 `ReentrantLock`。
- **下游照样被打爆**：能开 10 万个虚拟线程去查库，数据库连接池还是 20 个。并发控制要从「限线程数」换成 `Semaphore` 限流量。

---

## L6 三道模拟面试

### Q1　「写一个线程安全的单例。要求：延迟加载、高并发下性能好、不被反射和反序列化破坏。」

**第一层：能跑就行**

```java
// 饿汉式：靠类加载保证线程安全，但没有延迟加载
public class Singleton {
    private static final Singleton INSTANCE = new Singleton();
    private Singleton() {}
    public static Singleton getInstance() { return INSTANCE; }
}
```

**第二层：加延迟加载 → DCL + volatile**

```java
public class Singleton {
    private static volatile Singleton instance;   // ★ 少这个，半成品对象会漏出去
    private Singleton() {}
    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) instance = new Singleton();
            }
        }
        return instance;
    }
}
```

**第三层：面试官说「反射能破」→ 构造函数防御**

```java
private Singleton() {
    if (instance != null) {
        throw new IllegalStateException("单例被反射攻击");
    }
}
// 但反序列化还是能造新对象 → 加 readResolve()
private Object readResolve() { return getInstance(); }
```

**最优解：静态内部类（延迟 + 线程安全 + 无锁）**

```java
public class Singleton {
    private Singleton() {
        if (Holder.INSTANCE != null) throw new IllegalStateException();
    }
    private static class Holder {                    // 类加载时才初始化，天然懒加载
        private static final Singleton INSTANCE = new Singleton();
    }
    public static Singleton getInstance() { return Holder.INSTANCE; }
    private Object readResolve() { return Holder.INSTANCE; }
}
// 原理：JVM 保证 <clinit>() 的线程安全（同一时刻只有一个线程能初始化类，
//       其他线程阻塞等待）。所以「无锁 + 线程安全 + 延迟」三者可以同时成立。
```

**终解：枚举（《Effective Java》推荐）**

```java
public enum Singleton {
    INSTANCE;
    public void doSomething() { }
}
// 反射：Constructor.newInstance() 对 enum 直接抛 IllegalArgumentException
// 反序列化：enum 走 valueOf，不会新建实例
```

**雷区**

- 写 DCL 忘了 volatile —— 直接判不及格，这是本题的核心考点。
- 说「静态内部类也是懒加载，所以比 DCL 好」却解释不出为什么无锁也安全 —— 要提 `<clinit>` 的 JVM 级同步。
- 反问「你需要防反射吗」——如果只是内部工具类，DCL 足够，过度设计反而减分。能主动指出这点是加分项。

### Q2　「线上订单服务 CPU 打到 100%，接口大面积超时，线程池队列堆到几万。你怎么排查？」

**第一步：先分清是「忙」还是「卡」**

```bash
top -Hp <pid>              # 看哪个线程吃 CPU
printf '%x\n' <tid>        # 线程 id 转 16 进制
jstack <pid> | grep -A 30 "nid=0x<hex>"   # 定位到具体栈帧

# 三种典型形态：
# ① 大量线程卡在 RUNNABLE + 业务代码  → 真的在算（死循环 / 正则回溯 / 大对象序列化）
# ② 大量线程卡在 BLOCKED on monitor   → 锁竞争，看 "waiting to lock"
# ③ 线程数极少但 CPU 高              → GC 线程在拼命（jstat 一看便知）
```

**第二步：分清是「池满了」还是「下游慢了」**

```bash
jstat -gcutil <pid> 1000 10    # 看 FGC 次数和耗时；老年代持续高位 → 内存问题伪装成 CPU 问题
jcmd <pid> VM.native_memory

# 线程池侧：队列积压 + 活跃线程数已到 max → 瓶颈在下游（DB/Redis/RPC）
#           队列积压 + 活跃线程数远小于 core → 瓶颈在提交侧或锁
```

**第三步：定位到根因后的三种处置**

- **下游超时导致线程被长时间占用**：加超时（Feign/HttpClient 默认可能没有读超时！）、加熔断降级，把「等待」变成「快速失败」。这是最常见的真实原因。
- **慢 SQL**：`show processlist` 看执行中的语句，配合 `explain`。线程池被慢查询钉住是最典型的「池子看起来很忙，其实是下游在拖」。
- **真·死循环或锁竞争**：按第一步的栈帧定位到代码行，热修复或回滚。

**加分点：说出「为什么不能用无界队列」**

如果队列是无界的，堆积到几万时堆内存会先炸（每个 Runnable 都持有业务对象引用），而且**拒绝策略永远不触发，反压传导不上去**——上游以为请求成功了，其实全在内存里排队。有界队列 + CallerRunsPolicy 能在第 500 个任务时就告诉上游「我扛不住了」。**队列容量本质上是「你愿意用多少内存换多少延迟」的交易。**

**雷区**

- 一上来就说「扩机器 / 调大线程池」——这是掩盖问题，且可能让下游更早崩溃。
- 只会 `jstack` 不会 `jstat`——GC 问题被当成业务问题排查，方向全错。
- 没提「限流」——高并发排查的终点一定是「挡住不该进来的流量」，而不是「接住所有流量」。

### Q3　「基于 AQS 手写一个不可重入的互斥锁，并加一个 Condition 实现生产者-消费者。」

**第一步：继承 AQS，实现 tryAcquire / tryRelease**

```java
public class MutexLock implements Lock {
    private final Sync sync = new Sync();

    private static class Sync extends AbstractQueuedSynchronizer {
        // 0 = 空闲，1 = 已占用（不可重入，所以不累加）
        protected boolean isHeldExclusively() { return getState() == 1; }

        protected boolean tryAcquire(int acquires) {
            if (compareAndSetState(0, 1)) {          // ★ 核心只有这一行 CAS
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;                            // 失败 → 交给 AQS 去排队
        }

        protected boolean tryRelease(int releases) {
            if (getState() == 0) throw new IllegalMonitorStateException();
            setExclusiveOwnerThread(null);
            setState(0);                             // 先清 owner 再改 state
            return true;
        }

        Condition newCondition() { return new ConditionObject(); }
    }

    public void lock()        { sync.acquire(1); }
    public void unlock()      { sync.release(1); }
    public boolean tryLock()  { return sync.tryAcquire(1); }
    public Condition newCondition() { return sync.newCondition(); }
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }
    public boolean tryLock(long t, TimeUnit u) throws InterruptedException {
        return sync.tryAcquireNanos(1, u.toNanos(t));
    }
}
```

**第二步：用它写一个有界队列**

```java
public class BoundedQueue<T> {
    private final Object[] items;
    private int count, putIdx, takeIdx;
    private final MutexLock lock = new MutexLock();
    private final Condition notFull  = lock.newCondition();   // ★ 两条独立条件队列
    private final Condition notEmpty = lock.newCondition();

    public BoundedQueue(int cap) { items = new Object[cap]; }

    public void put(T x) throws InterruptedException {
        lock.lock();
        try {
            while (count == items.length) notFull.await();     // ★ while 不是 if
            items[putIdx] = x;
            if (++putIdx == items.length) putIdx = 0;          // 环形复用
            count++;
            notEmpty.signal();                                 // 精确唤醒「消费者」
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) notEmpty.await();
            T x = (T) items[takeIdx];
            items[takeIdx] = null;                             // 帮助 GC，防内存滞留
            if (++takeIdx == items.length) takeIdx = 0;
            count--;
            notFull.signal();                                  // 精确唤醒「生产者」
            return x;
        } finally {
            lock.unlock();
        }
    }
}
```

**面试官会追的四个点（提前答掉）**

1. **为什么不用 `setState` 而是 `compareAndSetState`？** tryAcquire 必须用 CAS，因为可能有多个线程同时进来；tryRelease 可以安全用 `setState`，因为只有持锁线程能释放。**能说出这个区别，说明真读过源码。**
2. **不可重入意味着什么？** 同一线程第二次 lock 会把自己 park 死——它已持锁，tryAcquire 必然失败，然后排队等自己释放。`ReentrantLock` 的 `if (current == getExclusiveOwnerThread()) setState(c + acquires)` 就是为解决这个。
3. **为什么两个 Condition 而不是一个？** 一个只能用 `signalAll()`，唤醒 5 个线程只有 1 个能干活，其余 4 个白跑一轮上下文切换。**这就是「惊群」问题。**
4. **为什么 `items[takeIdx] = null`？** 出队后引用还在数组里，对象无法被 GC——长生命周期队列会造成内存泄漏。这个细节很少有人主动提。

**雷区**

- 把 `tryAcquire` 写成 `if (getState() == 0) { setState(1); return true; }`——非原子，两个线程可能同时成功。**这是本题最容易挂的地方。**
- 忘了 `setExclusiveOwnerThread`，导致 `isHeldExclusively` 判断失真，Condition 的 await 会抛异常。
- 在 `finally` 外 unlock，或 catch 里吞异常导致锁泄漏。

---

## 本轮自测清单

合上文档，能不看稿讲出来才算过。

- **L0**　画出工作内存/主内存图，用 `count++` 的字节码解释「为什么加 volatile 还是错」。
- **L0**　默写 6 条 happens-before，并用传递性推一遍「volatile 安全发布」。
- **L1**　画出 Mark Word 四种状态的位布局，说清 `biased_lock` 那一位和 hashCode 为什么互斥。
- **L1**　讲清偏向锁为什么被 JDK 15 废弃（必须提到 safepoint 与 STW）。
- **L2**　说出四种屏障分别阻止哪种重排，以及 x86 上只剩哪个需要真指令。
- **L3**　不看源码讲完 `acquire` 四步，解释 SIGNAL 是干什么用的、为什么不能漏。
- **L3**　说出 state 在五个同步器里各代表什么，包括读写锁的位拆分。
- **L4**　背出七参数和执行顺序，解释「无界队列让 max 失效」的完整链路。
- **L4**　用 8 核算一遍 IO 密集线程数，并说出为什么这只是起点。

---

## 下一轮预告

**Spring 核心** —— 从 `refresh()` 的 12 个方法讲起，串到 **Bean 生命周期九步**、**循环依赖的三级缓存**（为什么二级不够、为什么构造器注入解决不了）、**AOP 的 JDK 动态代理与 CGLIB 选型**，最后落到最常见的生产事故：**@Transactional 六种失效场景**（其中「同类内部调用」和「异常被吞」直接和代理机制挂钩）。

本期 L1 讲的锁与 AOP 代理有交集——`synchronized` 加在代理方法上锁不住目标方法，这个坑留到下一期拆。

---

*Java 面试深挖 · 第 03 期｜主题：并发编程（锁升级 / volatile / AQS / 线程池）*
*口径说明：偏向锁按 JDK 8 讲、按 JEP 374 标注废弃；虚拟线程按 JDK 21 讲、pinning 修复标注为 JDK 24*
