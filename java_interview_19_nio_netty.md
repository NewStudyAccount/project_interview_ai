# Java 面试深水区 19 · NIO 与 Netty：从 1 万线程到 1 个线程

> 被问「Netty 为什么快」，答「用了 NIO 和零拷贝」的人，这题基本就结束了。真正拉开差距的是下一句：**它到底省掉了哪几次拷贝、砍掉了几个线程、又为此付出了什么代价**。本期把 1 万连接从 10GB 栈压到 1 个线程，把 epoll 的 LT/ET 画成就绪队列，把 `LengthFieldBasedFrameDecoder` 的五个参数拆成一把尺子，把 sendfile 的 4 次拷贝减到 0 次 CPU 拷贝，最后把生产上六个真实炸点逐条写成取证命令。
>
> `由浅入深`：L0 一次 write 的四次拷贝 → L1 BIO 的线程账 → L2 NIO 三件套与 Buffer 指针 → L3 select/poll/epoll 与 LT·ET → L4 Reactor 三模型 → L5 Netty 主从 Reactor 与 EventLoop → L6 Pipeline 与 Handler → L7 ByteBuf 与池化 → L8 粘包拆包 → L9 零拷贝两级 → L10 背压与水位线 → L11 生产六坑 → L12 数字速查 → L13 十四问答 → L14 三道模拟面试

---

## L0 一次 write 背后：4 次拷贝、4 次切换

先把地基打掉。所有 IO 优化——NIO、epoll、零拷贝、直接内存——都是在动同一张账单上的两栏：**内存拷贝次数** 和 **上下文切换次数**。

传统 `InputStream.read()` 把一个文件发到网络上，内核实际做了这些事：

```text
① DMA:  磁盘 ──────────→ 内核页缓存 (page cache)          [DMA 拷贝 #1]
② CPU:  内核页缓存 ────→ 用户缓冲区 (byte[] buf)          [CPU 拷贝 #1]
③ CPU:  用户缓冲区 ────→ socket 发送缓冲区                 [CPU 拷贝 #2]
④ DMA:  socket 缓冲区 ─→ 网卡 (NIC buffer)                 [DMA 拷贝 #2]

切换: 用户态→内核态(read) →用户态 →内核态(write) →用户态   [4 次]
```

算一遍：发 1GB 文件，`②③` 这两次 CPU 拷贝就是 2GB 的内存带宽被白白走了一遍，还要切 4 次态。**这就是「零拷贝」这个词的全部生意**——注意它从来不指"零次拷贝"，而是"零次 **CPU** 拷贝"。

第二栏更贵，却常被忽略。**上下文切换一次约 3~8 微秒**，4 次就是 12~32μs。如果一个请求只传 1KB 数据，光切换的开销就比数据本身处理还大。

| 方案 | DMA 拷贝 | CPU 拷贝 | 上下文切换 | 说明 |
|---|---|---|---|---|
| read + write | 2 | 2 | 4 | 传统方式，1GB 数据白走 2GB 带宽 |
| mmap + write | 2 | 1 | 4 | 省掉「内核→用户」这次拷贝 |
| sendfile | 2 | 0 | 2 | 数据不出内核，Java 的 `FileChannel.transferTo` |
| sendfile + SG-DMA | 2 | 0 | 2 | 网卡支持 scatter-gather，连内核缓冲都不用拼 |

`L9` 会把这张表逐行拆开。现在只记住一件事：**IO 的性能问题，90% 不在"读写快不快"，而在"多拷贝了几次、多切了几次、多等了几个人"。**

### 阻塞与非阻塞，到底阻塞了什么

初学者最容易混的一句：**"阻塞"阻塞的是线程，不是数据。** 拆成两个阶段看就清楚了：

| 阶段 | 阻塞 IO (BIO) | 非阻塞 IO (NIO) | IO 多路复用 |
|---|---|---|---|
| ① 等数据到达内核 | **线程挂起等** | 立刻返回 `EWOULDBLOCK`，自己轮询 | **一个线程等 N 个 fd** |
| ② 数据从内核拷到用户 | 线程挂起等 | 线程挂起等 | 线程挂起等 |

看懂这张表就明白：**NIO 并不比 BIO 快**，它甚至在单连接场景下更慢（多了轮询）。NIO 的唯一价值是 **把"等"这件事从 10000 个线程收敛到 1 个线程**。所以「NIO 快」这个说法本身就是错的——应该说是「NIO 在**高并发连接**下省掉了线程成本」。

- **面试口径**：`Java NIO 是非阻塞 IO，但 JDK 的 NIO 在 Linux 上底层仍是 epoll 的 LT 模式（水平触发）`，而 Netty 在其上封装出了 ET 语义的优化（`EpollET`，需 native 传输）。

---

## L1 BIO 的线程账：C10K 为什么死在 1 万线程

「一个连接一个线程」这句话听着没什么，算一遍账就知道它有多荒唐。

```java
// 教科书式 BIO 服务器：能跑，但只能跑到几百连接
ServerSocket server = new ServerSocket(8080);
while (true) {
    Socket socket = server.accept();          // 阻塞点 1
    new Thread(() -> {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {   // 阻塞点 2
                // 业务处理
            }
        } catch (IOException e) { /* ... */ }
    }).start();
}
```

**线程账（1 万连接）**：

| 项目 | 单线程成本 | 1 万连接 | 备注 |
|---|---|---|---|
| 线程栈 | 1MB（Linux x64 默认 `-Xss1m`） | **10 GB** | 虚拟内存，但栈页会被真实触碰 |
| 内核 task_struct | ~8KB | 80 MB | 不可 swap |
| 上下文切换 | 3~8 μs | 轮转一次 30~80 ms | 1 万线程切一遍的耗时 |
| 线程创建 | ~100 μs | 1 秒才能建完 | 建连高峰直接打满 CPU |

也就是说：**1 万连接里，9999 个线程在 `readLine()` 上 Sleeping，只有 1 个在干活。** 你花 10GB 内存买来的是 9999 份"我在等"的证明。

> **这就是 C10K 问题的本质**：不是机器扛不住 1 万个 socket，而是扛不住 1 万个**栈 + 调度实体**。

### 线程池化能救吗

能救一点，救不彻底，还会引入新问题：

```java
// 线程池版 BIO：连接数 > 线程数时，新连接只能排队
ExecutorService pool = new ThreadPoolExecutor(
    200, 200, 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(100),              // 队列 100 → 最多 300 连接
    new ThreadPoolExecutor.AbortPolicy());      // 第 301 个连接直接抛异常
```

代价是 **连接被"降级"成队列元素**：队列里的连接不发数据，但也没法让出线程给后面的人。200 个线程全卡在 200 个慢连接的 `read()` 上时，第 201 个连接哪怕毫秒级完成，也得等前面某个连接超时。**线程池把"内存爆炸"换成了"尾延迟爆炸"。**

这就是为什么 `Tomcat 8.5+` 的默认连接器是 `NIO` 而不是 `BIO`——不是因为它更快，而是因为它**在 8000 连接下只需要 200 个线程**。

---

## L2 NIO 三件套：Channel、Buffer、Selector

NIO 的三个角色，一句话记住：

- **Channel**：双向的管道（BIO 的 Stream 是单向的，读和写要两条流）
- **Buffer**：数据的容器，容量固定、指针自己管
- **Selector**：登记处，一个线程在这里问「谁准备好了」

### Buffer 的四个指针，是全章最该动手画的东西

`ByteBuffer` 里有 `capacity / limit / position / mark` 四个值，初学者 90% 的 bug 都在这里：

```text
初始状态 (allocate(8)):   写入 "hello" (5 字节) 后:
 position=0                position=5   ← 写指针
 limit=8                   limit=8
 capacity=8                capacity=8
 [ . . . . . . . . ]       [ h e l l o . . . ]

flip() 之后 (准备读):      read 3 字节后:
 position=0   ← 读指针     position=3
 limit=5      ← 写指针变上限 limit=5
 [ h e l l o . . . ]       [ h e l l o . . . ]

clear() 之后 (准备重写):
 position=0, limit=capacity=8   ← 只重置指针，数据没清（不擦除！）
```

三条铁律，背下来：

1. **`flip()` = `limit = position; position = 0;`** —— 写完要读，必调。
2. **`clear()` 不删数据**，只是把两个指针复位；`compact()` 才保留未读部分。
3. **`remaining() = limit - position`** —— 永远用这个判断"还能读多少"，别自己算。

```java
// 最常见的两个死循环 bug
ByteBuffer buf = ByteBuffer.allocate(8);
buf.put("hello".getBytes());
// 忘了 flip() → buf.get() 直接抛 BufferUnderflowException

// 另一个：读之前不判断 hasRemaining()
while (buf.hasRemaining()) {       // 正确写法
    byte b = buf.get();
}
```

> **面试追问**：`ByteBuffer` 为什么不设计成读写两个指针？答：它是 JDK 的历史包袱，`flip()` 这个操作本身就是"模式切换有成本"的证据。Netty 的 `ByteBuf` 用 `readerIndex / writerIndex` 双指针把这次切换干掉了——见 `L7`。

### Selector：把 1 万个 `read()` 变成 1 次 `epoll_wait()`

```java
Selector selector = Selector.open();
ServerSocketChannel ssc = ServerSocketChannel.open();
ssc.bind(new InetSocketAddress(8080));
ssc.configureBlocking(false);                    // 必须非阻塞
ssc.register(selector, SelectionKey.OP_ACCEPT);  // 登记「我关心连接事件」

while (true) {
    int n = selector.select();          // 阻塞在这里，直到有事件就绪
    if (n == 0) continue;
    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
    while (it.hasNext()) {
        SelectionKey key = it.next();
        it.remove();                    // 必须手动移除，否则下次重复处理
        if (key.isAcceptable()) {
            SocketChannel sc = ((ServerSocketChannel) key.channel()).accept();
            sc.configureBlocking(false);
            sc.register(selector, SelectionKey.OP_READ);
        } else if (key.isReadable()) {
            // 有数据了，放心读
        }
    }
}
```

**关键账本**：1 万连接、平均 1% 活跃。

| 方案 | 线程数 | 每次事件循环的系统调用 |
|---|---|---|
| BIO | 10000 | 10000 次 `read()`（其中 9900 次返回 EAGAIN） |
| NIO + Selector | **1** | **1 次** `epoll_wait()`，返回 100 个就绪 fd |

这就是「一个线程管一万连接」的全部秘密：**不是让一个线程干一万份活，而是让一个线程只处理"已经准备好"的那 100 份。**

---

## L3 select / poll / epoll：O(N) 与 O(1) 的分水岭

三个东西常被当成"一代比一代强"，其实差别只有一个：**谁来告诉你"哪个 fd 就绪了"**。

### select 的三道硬伤

```c
fd_set readfds;                    // 位图，FD_SETSIZE 默认 1024
FD_ZERO(&readfds);
FD_SET(fd, &readfds);
int n = select(maxfd + 1, &readfds, NULL, NULL, &timeout);
// 返回后必须遍历 0..maxfd，用 FD_ISSET 逐个问「你好了吗」
for (int i = 0; i <= maxfd; i++)
    if (FD_ISSET(i, &readfds)) { /* 处理 */ }
```

1. **1024 上限**：`fd_set` 是定长位图，改它要重编内核。
2. **每次调用都要把整个 fd 集合从用户态拷到内核态**：1 万 fd = 每次 1.25KB 拷贝（还只是位图）。
3. **返回后要 O(N) 遍历**：内核只告诉你"有几个好了"，不告诉你是哪几个。

### poll 只解了一半

```c
struct pollfd fds[10000];
// ... 填充 fd、events
int n = poll(fds, 10000, -1);      // 没有 1024 上限了
for (int i = 0; i < 10000; i++)    // 但还是要遍历 1 万次
    if (fds[i].revents & POLLIN) { /* 处理 */ }
```

去掉了数量上限，**但拷贝和遍历都还在**。1 万连接、100 个活跃时，9900 次判断是纯浪费。

### epoll 把"轮询"换成了"回调"

epoll 的三件套：

| 系统调用 | 作用 | 数据结构 |
|---|---|---|
| `epoll_create(1)` | 建一个 epoll 实例 | 内核里一棵**红黑树** + 一个**就绪链表** |
| `epoll_ctl(epfd, ADD/MOD/DEL, fd, event)` | 增删改关注的 fd | 红黑树 O(log N) 插入 |
| `epoll_wait(epfd, events, maxevents, timeout)` | 取就绪的 fd | 只读就绪链表，O(就绪数) |

```text
    用户态                     内核态
                    ┌─────────────────────────────┐
 epoll_ctl(ADD) ───→│  红黑树 (关注集合, O(logN))  │
                    │    fd=5 ─┐                  │
                    │    fd=7  │  每个节点挂一个    │
                    │    fd=9 ─┘  回调 ep_poll_cb  │
                    └──────────────┬──────────────┘
                                   │ 数据到达，网卡中断
                                   ▼ 回调把节点挂到链表
                    ┌─────────────────────────────┐
 epoll_wait()  ←────│  就绪链表 (rdllist)          │  ← 只返回这个链表的内容
   返回 100 个       │  fd=7 → fd=9 → ...          │     不遍历红黑树！
                    └─────────────────────────────┘
```

**核心差异一句话**：`select/poll` 是**你问内核"谁好了"**（每次都要全量问一遍）；`epoll` 是**内核主动把"好了的"放进一个链表，你直接取**。所以 epoll 的复杂度是 `O(就绪数)` 而不是 `O(总数)`。

**3 个数字记住这个差别**：

| 场景 | select/poll | epoll |
|---|---|---|
| 1 万连接、10 个活跃 | 每次 10000 次判断 | 每次 10 次判断 |
| 10 万连接、100 个活跃 | 每次 100000 次判断 + 800KB 拷贝 | 每次 100 次判断 + 0 拷贝 |
| 100 万连接、1% 活跃 | 不可能（select 上限 1024；poll 内存与耗时都爆炸） | 每次 1 万次判断，**C1000K 的现实方案** |

> 注意：连接数很少（少于几百）时，`epoll` 反而比 `poll` 慢一点点——多了红黑树的维护开销。**技术选型永远要说清楚"在什么规模下"**，这句话在面试里非常加分。

### LT 与 ET：一个必考、一个必踩

- **LT（水平触发，默认）**：只要缓冲区还有数据，`epoll_wait` **每次都会返回**这个 fd。写法宽松，忘了读也能下次补上。
- **ET（边缘触发，`EPOLLET`）**：状态**变化**时才通知一次。你必须**一次读到 `EAGAIN`**，否则剩下的数据再也不会通知你——**事件丢了，连接假死**。

```java
// ET 模式的正确读法：必须循环读到 -1 (EAGAIN)
while (true) {
    int n = channel.read(buf);
    if (n > 0) { /* 处理 */ buf.clear(); }
    else if (n == 0) break;              // 对端关闭
    else break;                          // n == -1 且 EAGAIN → 读干净了，退出
}
```

**为什么 ET 更快**：LT 模式下，一次 `epoll_wait` 可能反复返回同一个 fd（如果你的处理速度跟不上到达速度），产生"事件风暴"；ET 保证每个就绪状态只通知一次，**系统调用次数减少一个数量级**。

**Netty 的选择**：默认 `NioEventLoop` 用 JDK 的 `Selector`（LT），要开 ET 必须用 `EpollEventLoopGroup`（native 传输，Linux 专用，需引入 `netty-transport-native-epoll`）。面试里能说出这个差别，基本能筛掉八成竞争者。

---
## L4 Reactor 三模型：谁来 accept，谁来干活

有了 epoll，下一个问题是 **"谁来处理事件"**。Reactor 模式就是把这个问题拆成两个角色：**Reactor（监听 + 分发）** 和 **Handler（干活）**。三种经典组合，本质是**把"分发"和"处理"拆到几个线程上**。

### 模型一：单 Reactor 单线程

```text
        ┌──────────────────────────────────┐
        │  1 个线程                         │
        │  ┌──────────┐   ┌──────────────┐ │
 连接 → │  │ Selector │ → │ accept / read │ │
        │  └──────────┘   │ 解码+业务+编码 │ │
        │                 └──────────────┘ │
        └──────────────────────────────────┘
```

**代表**：Redis 6.0 之前、Netty 的 `NioEventLoop` 单个实例。
**优点**：零锁、零线程切换、代码最简单。
**致命点**：一个慢请求（比如一次 Redis 的 `KEYS *`）卡死整个进程。Redis 6.0 加多线程 IO 就是因为它在这栽过。

### 模型二：单 Reactor 多线程

```text
        ┌────────────────┐
        │ Reactor 线程    │  accept + read + 解码
        └───────┬────────┘
                │ 投递任务
        ┌───────▼────────────────────┐
        │  业务线程池 (N 个)          │  业务处理 + 编码
        └───────┬────────────────────┘
                │ 回写
        ┌───────▼────────┐
        │ Reactor 线程    │  write
        └────────────────┘
```

**代表**：Tomcat 的 NIO 连接器、Dubbo 的 `ThreadPool` 分发。
**优点**：业务阻塞不影响 IO。
**代价**：**跨线程回写**——业务线程写完 `response` 必须 `selector.wakeup()` 把结果交回 Reactor 线程，多一次队列投递 + 线程切换（约 5~20μs）。而这个模型在**连接数极高、业务极轻**（比如长连接推送）的场景下，这次切换纯属浪费。

### 模型三：主从 Reactor（Netty 的答案）

```text
  ┌──────────────────┐        ┌──────────────────────────────┐
  │ bossGroup        │ accept │  workerGroup (EventLoop × N) │
  │ EventLoop × 1    │───────→│  ┌────────┐ ┌────────┐       │
  │ 只做 accept      │ 注册    │  │EL-0    │ │EL-1    │ ...   │
  └──────────────────┘        │  │read    │ │read    │       │
                              │  │decode  │ │decode  │       │
                              │  │业务/编码│ │业务/编码│       │
                              │  └────────┘ └────────┘       │
                              └──────────────────────────────┘
```

**为什么 accept 要单独一个组**：`accept` 是个"低频、但一旦发生就必须立刻处理"的操作。建连高峰时（比如 1 秒 5000 个新连接），如果 accept 和 read 抢同一个线程，已经在服务的连接会被拖慢。拆开后，boss 线程只干一件事，**逻辑上永远不排队**。

**线程数账**（Netty 默认值）：

```java
// NioEventLoopGroup 默认线程数 = max(1, CPU 核数 × 2)
// 8 核机器 → boss 1 个 + worker 16 个 = 17 个 IO 线程
EventLoopGroup boss = new EventLoopGroup(1, ...);      // 通常 1 就够
EventLoopGroup worker = new NioEventLoopGroup();       // 默认 16
```

| 配置 | 线程数 | 适用 |
|---|---|---|
| boss=1, worker=CPU×2 | 17 | **默认，90% 场景够用** |
| boss=1, worker=CPU | 9 | 业务全异步、无阻塞调用 |
| boss=1, worker=CPU×4 | 33 | 有少量阻塞（日志落盘、本地缓存） |
| worker=100 | 100+ | **反模式**：线程多了只会增加切换，不会增加吞吐 |

> **面试口径**：「Netty 线程池开多大」不是越大越好。`EventLoop` 是**串行无锁**的，线程多了只会把"无锁"变成"多核并行"，但每个 Channel 的处理仍然是串行的。真正的上限取决于**每个 Channel 的处理耗时**：如果每个 handler 只花 50μs，16 个线程能撑 32 万 QPS；如果某个 handler 里查了一次 DB（20ms），那 16 个线程只能撑 800 QPS——**这才是要调的地方**。

---

## L5 EventLoop：Netty 最重要的三条铁律

`EventLoop` 是 Netty 的心脏。它本质是 **"一个 Selector + 一个任务队列 + 一个永不退出的线程"**：

```java
// 简化版 EventLoop.run()，抄自 NioEventLoop.run() 的核心结构
protected void run() {
    for (;;) {                                  // 死循环，永不退出
        select();                               // ① epoll_wait，有超时
        processSelectedKeys();                  // ② 处理 IO 事件（read/write/accept）
        runAllTasks();                          // ③ 处理投递进来的普通任务
    }
}
```

就这三行，但它决定了 Netty 的三个铁律。

### 铁律一：一个 Channel 终生只属于一个 EventLoop

```java
// 注册是一次性的，之后不可更换
Channel ch = ...;
EventLoop loop = ch.eventLoop();     // 一旦注册，终生不变
```

**为什么**：因为 `Channel` 的所有状态（`pipeline`、`unsafe`、缓冲区）都**没有加锁**。如果允许两个 EventLoop 同时操作一个 Channel，就必须加锁——那 Netty 的性能优势立刻归零。

**这条铁律的推论（面试高频）**：

```java
// 在任何线程里写数据，都会先调度回该 Channel 的 EventLoop
channel.writeAndFlush(msg);
// 内部：if (inEventLoop()) doWrite(); else eventLoop.execute(() -> doWrite());
```

所以 **`Channel` 的 `write` 是线程安全的**（它会自动切回自己的 EventLoop），但**多步操作不原子**：

```java
// 反模式：3 个线程同时执行，顺序不可控
channel.write(a);
channel.write(b);
channel.writeAndFlush(c);
// 结果可能是 a, c, b —— 因为三次都是"投递任务"，线程调度决定顺序

// 正确：合并成一次投递，保证原子性
channel.eventLoop().execute(() -> {
    channel.write(a).write(b).writeAndFlush(c);   // 同一个 EventLoop 内，顺序确定
});
```

### 铁律二：EventLoop 里绝不能有阻塞调用

这是**生产事故第一名**。因为一个 EventLoop 上挂着 **几百到几千个 Channel**：

| 阻塞操作 | 单次耗时 | 卡住 1000 个 Channel 的代价 |
|---|---|---|
| 一次 MySQL 查询 | 5 ms | 该线程上 1000 个请求全部 +5ms 尾延迟 |
| 一次 HTTP 远程调用 | 50 ms | 全部 +50ms，且排队累积 |
| `Thread.sleep(1000)` | 1000 ms | 直接 P99 爆到秒级 |
| 读一个大文件 | 200 ms | 连接超时、心跳丢失、误判断线 |

**正确做法：把耗时操作扔给业务线程池。**

```java
// 方案 A：给这组 handler 单独的 EventExecutorGroup
pipeline.addLast(new DefaultEventExecutorGroup(16), new SlowBusinessHandler());

// 方案 B：handler 内部手动丢线程池
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    bizPool.execute(() -> {
        Result r = doSlowQuery(msg);              // 阻塞在这里无所谓
        ctx.writeAndFlush(r);                     // write 是线程安全的
    });
}
```

> **辨别技巧**：`channelRead` 里出现 `jdbc`、`http`、`sleep`、`synchronized(全局锁)`、`File`，一律是事故。用 `arthas trace` 打一下 `NioEventLoop.run`，如果 `processSelectedKeys` 耗时超过 1ms 就该查了。

### 铁律三：EventLoop 是"串行无锁"的，所以 P99 取决于最慢的那次处理

因为一个 EventLoop 一个线程、串行处理它名下所有 Channel 的事件，所以：

```text
EventLoop-3 上挂了 800 个 Channel
一次事件循环耗时 = 800 个 channel 的事件处理时间之和 + 任务队列时间
如果每个 channel 平均 30μs，一次循环 = 24ms
→ 任何单个请求的等待时间上界 ≈ 24ms（最坏情况）
```

**这就是"为什么 Netty 在 handler 里加一行日志就会掉性能"**：日志的同步 IO 是微秒级，但乘以 800 就是毫秒级。

**数字速记**：`单 EventLoop 承载的连接数 ≈ 1 秒 / (单连接单次处理耗时 × 事件频率)`。1 万连接、每个连接每秒 1 个事件、每次处理 10μs → 需要 10000×10μs = 100ms/秒，一个 EventLoop 就用掉 10% 的 CPU，**理论上 1 个线程能扛，但为了抗抖动按 CPU×2 配**。

---

## L6 Pipeline 与 Handler：事件往哪个方向流

`Pipeline` 是 Netty 对"责任链"的实现，但它比 GoF 的责任链难一个量级，因为**事件是双向的**。

```text
    inbound 事件（读进来、连接建立）：
    head → h1 → h2 → h3 → tail        方向：head → tail (向下)

    outbound 事件（写出去、连接关闭）：
    tail → h3 → h2 → h1 → head        方向：tail → head (向上)

        head ──→ [h1 解码] ──→ [h2 业务] ──→ [h3 编码] ──→ tail
          ↑                                              │
          └────────────── write 从这里出发 ←──────────────┘
```

**三条最容易答错的地方**：

### ① `ctx.write()` 与 `channel.write()` 差一个方向

```java
// ctx.write()：从"当前 handler 的下一个 outbound handler"开始往前传
//               → 会跳过当前 handler 之前的编码器
ctx.writeAndFlush(msg);

// channel.write()：从 pipeline 的 tail 开始，走完整条 outbound 链
//                   → 所有编码器都会经过
channel.writeAndFlush(msg);
```

**踩坑现场**：在 `h3`（最后一个 handler）里用 `ctx.write()` 写一个 `String`，期望被 `h1` 的编码器转成 `ByteBuf`——结果直接抛 `UnsupportedMessageTypeException`，因为 `ctx.write()` 从 `h3` 往后走，**根本不会经过 `h1`**。

> 记忆口诀：**`ctx.write()` 只往后走，`channel.write()` 从尾巴走。** 需要经过前面编码器时，用 `channel.write()` 或 `ctx.channel().write()`。

### ② 入站/出站的方向由"handler 类型"决定，不是由"添加顺序"决定

```java
pipeline.addLast("decoder", new LengthFieldBasedFrameDecoder(...));  // inbound
pipeline.addLast("encoder", new LengthFieldPrepender(4));           // outbound
pipeline.addLast("business", new BusinessHandler());               // inbound
```

`addLast` 的顺序决定**链上的位置**，但事件传播方向由 handler 实现了哪个方法决定：

- 实现 `channelRead` / `channelActive` → **inbound**，事件 head→tail
- 实现 `write` / `flush` / `close` → **outbound**，事件 tail→head

**后果**：`LengthFieldPrepender`（outbound）放在 `decoder` 前面，那么 `write` 时它是**最后一个**被调用的；如果你把它加在 `business` 后面，`business` 用 `ctx.write()` 就**跳过了它**——长度头没加上，对端解析全乱。**这是 Netty 最常见的"能连上但数据错位"事故。**

### ③ `@Sharable` 只对"无状态 handler"合法

```java
@ChannelHandler.Sharable          // 声明：这个实例可以挂到多个 Channel 上
public class AuthHandler extends ChannelInboundHandlerAdapter {
    private int counter = 0;      // 有状态！共享 = 竞态
    public void channelRead(...) { counter++; }   // 多线程并发 ++，必错
}
```

`@Sharable` 的作用是**复用同一个 handler 实例**（省内存：10 万连接 × 每个 handler 200B = 20MB）。但一旦实例被共享，所有字段都变成**跨 Channel 的共享状态**，而多个 Channel 可能落在**不同 EventLoop 线程**上——直接数据竞争。

| 场景 | 是否加 `@Sharable` |
|---|---|
| 无状态编解码器（`LengthFieldPrepender`） | ✅ 加，官方自带的就是 sharable |
| 无状态鉴权/日志 | ✅ 加 |
| 带计数器、带本地缓存、带 `ByteBuf` 字段 | ❌ 绝不能加 |
| 每个连接需要独立状态 | ❌ 每个连接 `new` 一个（`ChannelInitializer` 里 new 就对了） |

> **顺带一个必答点**：`ChannelInitializer` 本身是 `@Sharable` 的（框架声明了），但它内部的 `initChannel` 每次连接都会执行，所以里面 `new` 出来的 handler 都是**每个连接独享**的——这就是 Netty 的推荐写法。

### 异常传播：`exceptionCaught` 只往一个方向走

```java
public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    // inbound 方向的异常，只会传给"后面的" inbound handler
    // 如果你在这里 swallow 掉（不调 ctx.fireExceptionCaught），后面就永远不知道
    log.error("boom", cause);
    ctx.close();                  // 最常见处理：记日志 + 关连接
}
```

**注意**：`exceptionCaught` 只处理 **inbound** 链上的异常。`outbound` 操作（`write`）抛的异常，会作为 `write` 的 `Promise` 失败回调返回，**不会**自动进 `exceptionCaught`：

```java
channel.writeAndFlush(msg).addListener(future -> {
    if (!future.isSuccess()) log.error("write failed", future.cause());  // 必须自己接
});
```

---

## L7 ByteBuf：双指针、池化、引用计数

`ByteBuf` 是 Netty 相对 JDK `ByteBuffer` 最大的改进。三个设计点，每一个都能单独出一道题。

### ① 双指针：干掉 `flip()`

```text
ByteBuf (容量 8)，写入 "hello"：
  0   1   2   3   4   5   6   7
 [h] [e] [l] [l] [o] [ ] [ ] [ ]
  ↑                   ↑
readerIndex=0     writerIndex=5

此时可以直接读（不需要 flip）：
  buf.readByte() → 'h'，readerIndex 变 1

也可以继续写（不需要 compact）：
  buf.writeByte('!') → writerIndex 变 6  ← 读指针不受影响
```

| 对比项 | `ByteBuffer` | `ByteBuf` |
|---|---|---|
| 模式切换 | 必须 `flip()` / `clear()` / `compact()` | **不需要**，读写指针独立 |
| 扩容 | **不能扩容**（`allocate` 定死） | 可 `capacity(newSize)` 自动扩到 `maxCapacity` |
| 引用计数 | 无，靠 GC | **有 `refCnt`**，手动 `release()` |
| 池化 | 无 | `PooledByteBufAllocator`（默认） |
| 切片/合并 | `slice()` 共享内容但有独立 position | `slice()` / `duplicate()` / `CompositeByteBuf` |
| 字节序 | 全局 `order()` | 每个实例可独立设 `order()`（处理协议头很实用） |

**扩容细节（常考）**：`writeXxx` 时若 `writerIndex + n > capacity`，触发 `ensureWritable0`，新容量按 `4MB 阈值` 分档增长：

```java
// AbstractByteBuf.calculateNewCapacity 的核心逻辑
int newCapacity = Math.max(minNewCapacity, capacity << 1);   // 先翻倍
if (newCapacity > maxCapacity) newCapacity = maxCapacity;
if (newCapacity < 0) throw new IllegalStateException("maxCapacity exceeded");
```

**注意**：扩容会**拷贝全部数据**。所以高并发场景下 `Unpooled.buffer()` 而不估容量，会造成大量"翻倍→拷贝→丢弃旧数组"，这正是池化要解决的第一件事。

### ② 池化：Netty 的内存账本

`PooledByteBufAllocator`（Netty 4.1 起是默认）借鉴了 jemalloc 的四级结构：

```text
PoolArena (每个 EventLoop 一个，减少竞争)
  └── PoolChunk × N          默认 16MB，是一块连续内存
        └── PoolPage × 2048  默认 8KB（16MB / 8KB = 2048）
              └── PoolSubpage 小于 8KB 时再切
```

**规格分级**：

| 规格 | 范围 | 分配路径 |
|---|---|---|
| Tiny | ≤ 496 B | `PoolSubpage`，按 16B 步长切 |
| Small | 497 B ~ 4 KB | `PoolSubpage` |
| Normal | 4 KB ~ 16 MB | 直接占整页/多页 |
| Huge | 大于 16 MB | **不池化**，直接 `allocate`，用完即弃 |

**为什么这个设计有意义**，算一笔：

| 指标 | 不池化 | 池化 |
|---|---|---|
| 一次 1KB 分配 | `new byte[1024]`（堆内）或 `malloc`（直接内存） | 从 Subpage 切一块，**0 次系统调用** |
| 每秒 10 万次分配 | 10 万次 GC 压力 / malloc 竞争 | 池内复用，接近 0 |
| 直接内存分配成本 | ~1~10 μs（`malloc` + 页表） | ~50 ns（指针偏移） |

**百倍差距**，这就是"Netty 快"最实在的一条。

### ③ 引用计数：`release()` 忘了写就是内存泄漏

```java
ByteBuf buf = ctx.alloc().buffer();
try {
    buf.writeBytes(data);
    // 用 buf
} finally {
    buf.release();     // 引用计数 -1，到 0 时归还池
}
```

**为什么需要手动管**：池化的内存不会被 GC 回收（它挂在池的树上），只能靠计数归还。计数不归零 = 这块内存永远回不到池里 = **直接内存泄漏 → `OutOfDirectMemoryError`**。

| Handler 类型 | 谁负责 `release()` |
|---|---|
| `ChannelInboundHandlerAdapter` | **你自己**，必须在 `finally` 里 release |
| `SimpleChannelInboundHandler<T>` | **框架**，`channelRead0` 返回后自动 release |
| `MessageToMessageDecoder` | 框架 release 输入，你负责输出 |
| `ByteToMessageDecoder` | 框架管（它自己累积 `cumulation`） |

```java
// 最推荐：继承 SimpleChannelInboundHandler，根本不用想 release
public class MyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        // msg 在这里有效，方法返回后框架自动 release
        // 注意：不能把 msg 存到字段里跨方法用！方法一返回它就废了
    }
}
```

**泄漏检测（生产必开）**：

```bash
-Dio.netty.leakDetection.level=PARANOID      # 开发/压测：每次分配都采样，最准最慢
-Dio.netty.leakDetection.level=SIMPLE        # 默认：1% 采样
-Dio.netty.leakDetection.level=ADVANCED      # 采样率更高，带访问记录
```

检测到泄漏会打一条 `LEAK: ByteBuf.release() was not called before it's garbage-collected`，**并带上分配时的完整堆栈**——这是排查 Netty 内存问题唯一有效的线索，务必在预发打开 `PARANOID` 跑一轮。

---
## L8 粘包拆包：TCP 根本不认识"一条消息"

这题几乎每场面试都问，但答对的人很少。**先说结论：不存在"粘包"这个东西，只有"你的应用层没有定义边界"。**

TCP 是**字节流**协议，它只保证"字节按序到达"，**不保证任何消息边界**。所以：

```text
你写了 3 次：  "HELLO" + "WORLD" + "HI"
TCP 收到可能是：
  情况 1（粘）：  "HELLOWORLDHI"          → 一次 read 全拿到
  情况 2（拆）：  "HEL" + "LOWORLDHI"     → 两次 read，第一条被劈开
  情况 3（混）：  "HELLOW" + "ORLDHI"     → 又粘又拆
```

**三种成因**（面试要能分开说）：

| 成因 | 说明 |
|---|---|
| Nagle 算法 | 小包合并发送，默认开启；`TCP_NODELAY` 关掉它 |
| MSS/MTU 分片 | 超过 1460 字节被 IP 层切开 |
| 接收方缓冲区 | `read(buf)` 里 `buf` 多大就取多少，与发送方无关 |

**注意**：`TCP_NODELAY` 只能缓解成因 1，**解决不了粘包**。因为成因 3 是接收方的事——只要你的 `read` 缓冲区大小不等于消息长度，就一定会切。**唯一解法是应用层自己定边界。**

### 四种解码器，对应四种边界定义方式

| 边界定义 | Netty 解码器 | 适用协议 |
|---|---|---|
| 定长 | `FixedLengthFrameDecoder` | 极简私有协议 |
| 分隔符 | `DelimiterBasedFrameDecoder` / `LineBasedFrameDecoder` | Redis 协议、文本日志 |
| 长度字段 | `LengthFieldBasedFrameDecoder` | **事实标准**：Dubbo、gRPC、自定义 RPC |
| 自定义 | 继承 `ByteToMessageDecoder` | 复杂协议（如 HTTP/2） |

### 长度字段解码器的 5 个参数，必须能画出来

`LengthFieldBasedFrameDecoder` 是所有 RPC 框架的标配，它的五个参数长这样：

```text
原始字节流：
  ┌────────┬────────┬──────────────────────┬────────┐
  │ magic  │ length │       body           │  crc   │
  │ 2 byte │ 4 byte │     length 字节       │ 2 byte │
  └────────┴────────┴──────────────────────┴────────┘
   0        2        6                      6+len

参数含义：
  lengthFieldOffset   = 2     长度字段从第 2 字节开始
  lengthFieldLength   = 4     长度字段占 4 字节
  lengthAdjustment    = 0     长度值之后还有多少字节属于本帧（可负）
  initialBytesToStrip = 0     解码后扔掉前几个字节（通常 = 2+4 = 6）
  maxFrameLength      = 1MB   最大帧长，防止恶意大包打爆内存
```

**`lengthAdjustment` 是最容易搞错的一个**。它解决的是"长度字段的值到底指什么"的问题：

```text
协议 A：length 只表示 body 长度
  | length(4) | body(length) |
  lengthAdjustment = 0

协议 B：length 表示"从 length 字段之后到帧尾"的总长度，且帧尾有 4 字节 crc
  | length(4) | body | crc(4) |
  lengthAdjustment = 4          ← 因为 length 没算 crc

协议 C：length 表示"整帧长度（含 length 自己）"
  | length(4) | body |
  lengthAdjustment = -4         ← 要把 length 字段自己扣掉
```

**面试标准答法**：

```java
// 完整配置示例：magic(2) + len(4) + body(len) + crc(4)
pipeline.addLast(new LengthFieldBasedFrameDecoder(
        1024 * 1024,   // maxFrameLength，防 OOM 的第一道闸门
        2,             // lengthFieldOffset：跳过 magic
        4,             // lengthFieldLength
        4,             // lengthAdjustment：body 后面还有 4 字节 crc
        6));           // initialBytesToStrip：把 magic+len 剥掉，业务只看到 body+crc
```

> **陷阱**：`maxFrameLength` 千万别省。对端发个 `length = 0x7FFFFFFF` 的头，解码器就会去申请 2GB 缓冲——**一个畸形包就能打挂你的服务**。这是真实存在的 CVE 级问题。

**半包（不足一帧）怎么处理**：`ByteToMessageDecoder` 内部有个 `cumulation` 累积缓冲区，读到的字节不够一帧时**不消费**，留在 cumulation 里等下次。所以：

```java
// 反模式：自己写解码器时把不完整的数据也消费了
if (in.readableBytes() < len) return;   // ✅ 正确：直接 return，不 readIndex 前进
```

只要不调 `readBytes`，`readerIndex` 不动，数据就还在——这是 Netty 解码器的核心机制。

---

## L9 零拷贝的两级：OS 层省 CPU，Netty 层省复制

「零拷贝」这个词在面试里必须**分层说**，否则一定露馅：OS 层的零拷贝（sendfile/mmap）和 Netty 的零拷贝（组合/切片）**完全不是一回事**。

### 第一级：OS 层，省的是 CPU 拷贝

回头看 `L0` 的表，逐行拆开：

```text
【read + write】4 次拷贝，4 次切换
  磁盘 →[DMA]→ 页缓存 →[CPU]→ 用户buf →[CPU]→ socket buf →[DMA]→ 网卡

【mmap + write】3 次拷贝，4 次切换
  磁盘 →[DMA]→ 页缓存 ←──(用户buf 直接映射页缓存，无需拷贝)
                     →[CPU]→ socket buf →[DMA]→ 网卡
  代价：页缓存映射有 TLB 开销，大文件下缺页中断明显

【sendfile】2 次拷贝，2 次切换
  磁盘 →[DMA]→ 页缓存 ─────────→[DMA]→ 网卡
                    ↑ 数据完全不出内核，CPU 拷贝 = 0
  代价：用户态看不到数据，无法在传输中修改内容

【sendfile + SG-DMA】2 次 DMA，0 次 CPU，且不需要拼成连续内存
  磁盘 →[DMA]→ 页缓存(散页) →[SG-DMA gather]→ 网卡
```

**1GB 文件的账**：

| 方案 | CPU 拷贝量 | 内存带宽占用 | 上下文切换 |
|---|---|---|---|
| read+write | 2 GB | 4 GB（读写各算一次） | 4 |
| mmap+write | 1 GB | 3 GB | 4 |
| sendfile | **0** | 2 GB | 2 |

Java 对应的是 **`FileChannel.transferTo()`**：

```java
// 底层就是 sendfile，Kafka/RocketMQ/Netty 都用它
try (FileChannel in = FileChannel.open(path)) {
    long pos = 0;
    long size = in.size();
    while (pos < size) {
        long n = in.transferTo(pos, size - pos, socketChannel);  // 一次最多 8MB(依平台)
        if (n <= 0) break;
        pos += n;                       // 注意：必须循环，一次不一定传完
    }
}
```

> **为什么必须循环**：`transferTo` 单次上限受内核 `MAX_RW_COUNT`（约 2GB）和 socket 缓冲区限制，会返回已传字节数。**不循环 = 静默丢数据**，这是 Kafka 早期的一个著名 bug。

### 第二级：Netty 层，省的是"应用层的数据搬运"

Netty 的"零拷贝"跟 OS 那个没关系，它指**在 JVM 堆内不复制字节**：

| 手段 | 作用 | 复制次数 |
|---|---|---|
| `CompositeByteBuf` | 把 header 和 body 逻辑上拼成一个 buf | **0**（传统做法要 copy 到一个新数组） |
| `ByteBuf.slice()` | 切一段视图，共享底层内存 | **0** |
| `ByteBuf.duplicate()` | 复制指针，共享内容 | **0** |
| `Unpooled.wrappedBuffer(byte[])` | 包装数组，不复制 | **0** |
| `FileRegion` | 包装 `FileChannel`，走 `transferTo` | **0**（连用户态都不进） |
| `ByteBuf.copy()` | 真复制 | 1（**这个不是零拷贝**） |

```java
// CompositeByteBuf：协议头 + 文件内容，不拷贝
CompositeByteBuf composite = Unpooled.compositeBuffer();
composite.addComponents(true,
    Unpooled.wrappedBuffer(header),     // 10 字节头
    Unpooled.wrappedBuffer(body));      // 1MB body
// 内存里还是两块，只是逻辑上连续 → 写入时分散写(sendmsg)

// 反面教材：多了一次 1MB 拷贝
ByteBuf copy = Unpooled.buffer(header.length + body.length);
copy.writeBytes(header).writeBytes(body);     // 1MB 白拷
```

**算一笔**：10 万 QPS 的网关，每个响应 10KB。用 `CompositeByteBuf` 省掉的拷贝是 `10 万 × 10KB = 1 GB/s` 的内存带宽。**这就是"Netty 网关比 Tomcat 网关吞吐高"的物理原因之一。**

> **面试反问的坑**：「Netty 的零拷贝需要操作系统支持吗？」答：**不需要**。`CompositeByteBuf` 是纯 Java 层的引用共享，跟 OS 无关；只有 `FileRegion` 才依赖 `sendfile`（且 Windows 上退化为普通写）。

---

## L10 背压：水位线、`isWritable` 与 OOM 的真实路径

这是 Netty 最容易被忽略、又最容易炸的一节。

**问题**：上游生产快、下游消费慢（客户端网络差），`writeAndFlush` 的数据会堆在 Netty 的 `ChannelOutboundBuffer` 里。堆到什么时候爆？

```text
业务线程 ──write──→ ChannelOutboundBuffer ──flush──→ socket 发送缓冲区 ──→ 网络
                     ↑ 就堆在这里，无上限！
```

```java
// 灾难写法：没有任何背压
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    for (int i = 0; i < 1_000_000; i++) {
        ctx.write(bigData);                  // 全堆进 outboundBuffer
    }
    ctx.flush();
}
// 结果：堆内存 OOM，或直接内存 OOM（取决于 ByteBuf 类型）
```

### 水位线机制

Netty 给 `ChannelOutboundBuffer` 设了两个阈值：

```java
// 默认值（ChannelOption.WRITE_BUFFER_WATER_MARK）
// 低水位 32KB，高水位 64KB
new WriteBufferWaterMark(32 * 1024, 64 * 1024);
```

| 状态 | 触发 | 表现 |
|---|---|---|
| 待发字节 **小于低水位** | — | `channel.isWritable() == true` |
| 待发字节 **超过高水位** | 触发 `channelWritabilityChanged` | `isWritable() == false` |
| 回落到低水位以下 | 再次触发 `channelWritabilityChanged` | `isWritable() == true` |

```java
// 正确用法：写之前判断，不可写就暂停读取
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (!ctx.channel().isWritable()) {
        ReferenceCountUtil.release(msg);
        ctx.channel().config().setAutoRead(false);    // 停止从 socket 读 → TCP 窗口收缩 → 上游自动减速
        return;
    }
    ctx.write(msg);
}

@Override
public void channelWritabilityChanged(ChannelHandlerContext ctx) {
    if (ctx.channel().isWritable()) {
        ctx.channel().config().setAutoRead(true);      // 恢复读取
    }
    ctx.fireChannelWritabilityChanged();
}
```

**为什么 `setAutoRead(false)` 能产生真正的背压**：停止读 → 接收缓冲区满 → TCP 通告窗口变成 0 → 对端 `send` 阻塞 → **压力沿着 TCP 协议栈传回上游**。这是唯一"不丢数据又能减速"的机制。

> **注意**：`AUTO_READ=false` 后必须**手动 `ctx.read()`** 才会读下一批。Netty 的 `read()` 是"请求读一次"，不是"读一次数据"——它相当于向 Selector 重新注册 `OP_READ`。这个语义搞错会导致"设置了 AutoRead=false 后连接彻底不响应"。

### OOM 的三种真实路径

| 路径 | 现象 | 取证 |
|---|---|---|
| 堆内 ByteBuf 堆积 | `java.lang.OutOfMemoryError: Java heap space` | `jmap -histo:live` 看 `PooledUnsafeHeapByteBuf` 实例数 |
| 直接内存泄漏 | `OutOfDirectMemoryError: failed to allocate N byte(s) of direct memory` | `-XX:MaxDirectMemorySize` + `-Dio.netty.leakDetection.level=PARANOID` |
| 未 release 导致池耗尽 | 越跑越慢，最终 `OutOfDirectMemoryError` | 泄漏日志里的分配堆栈 |

**关键区分**：`OutOfDirectMemoryError`（Netty 自己抛的）vs `OutOfMemoryError: Direct buffer memory`（JDK 抛的）。前者说明 Netty 的计数器认为超了 `maxDirectMemory`，通常是**没 release**；后者是真正耗尽。能分清这两个，面试官会记住你。

---

## L11 生产六坑：能连上、能跑、然后炸了

### 坑 1：EventLoop 里做同步调用
**症状**：QPS 上不去，`arthas thread` 看到 `nioEventLoopGroup-*-*` 全部 `RUNNABLE` 卡在某个业务方法。
**取证**：`arthas trace io.netty.channel.nio.NioEventLoop run` → 看 `processSelectedKeys` 耗时。
**修复**：业务 handler 前面加 `DefaultEventExecutorGroup`，或内部丢线程池。

### 坑 2：忘了 `release()`，或者说错了谁该 release
**症状**：跑 3~7 天后 `OutOfDirectMemoryError`，重启就好，反复。
**取证**：`PARANOID` 级别泄漏检测；`-Dio.netty.leakDetection.targetRecords=32` 拿到分配栈。
**修复**：改用 `SimpleChannelInboundHandler`；或在 `finally` 里 `ReferenceCountUtil.release(msg)`。

### 坑 3：`@Sharable` 加在了有状态的 handler 上
**症状**：偶发数据错乱、计数器不准，压测才复现。
**取证**：`grep -rn "@Sharable"`，逐个看有没有非 `final` 实例字段。
**修复**：状态挪到 `Channel.attr()` 或 `AttributeKey` 里（每个连接独立）。

### 坑 4：解码器位置错，`ctx.write()` 跳过编码器
**症状**：能连上，但对端收到乱码 / 长度头丢失。
**取证**：`pipeline.names()` 打印链顺序；在编码器里打断点看有没有进。
**修复**：统一用 `channel.writeAndFlush()`，或把编码器加在 handler 之前。

### 坑 5：`writeAndFlush` 调太多次
**症状**：syscall 数量异常高（`strace -c` 看 `writev` 次数），QPS 上不去。
**取证**：`strace -cp <pid>` 或 `perf top` 看 `sys_write`。
**修复**：批量写用 `write()` 多次 + 最后 `flush()` 一次；或配 `FlushConsolidationHandler`。

```java
// 一次 flush vs 十次 flush 的账
for (Msg m : list) ctx.write(m);     // 10 次入队，0 次 syscall
ctx.flush();                          // 1 次 syscall（writev 批量）
// 对比 for(...) ctx.writeAndFlush(m) → 10 次 syscall，每次 ~2μs，白烧 20μs
```

### 坑 6：关闭时不优雅
**症状**：发布重启时有少量请求 `Connection reset by peer`。
**取证**：看 `shutdownGracefully` 的静默期参数。
**修复**：

```java
// 正确关闭：先停止接收新连接，给在途请求留时间
boss.shutdownGracefully(0, 5, TimeUnit.SECONDS);      // quietPeriod=0, timeout=5s
worker.shutdownGracefully(2, 15, TimeUnit.SECONDS);   // 静默 2s 等在途完成
// 配合 k8s preStop sleep 5s，才能做到真正的零中断
```

---

## L12 数字速查表

| 数字 | 含义 |
|---|---|
| 4 / 4 | read+write 的 4 次拷贝 / 4 次上下文切换 |
| 2 / 2 | sendfile 的 2 次 DMA 拷贝 / 2 次切换，CPU 拷贝 0 |
| 3~8 μs | 一次上下文切换成本 |
| 1 MB | 默认线程栈（`-Xss`），1 万线程 = 10 GB |
| 1024 | `select` 的 `FD_SETSIZE` 硬上限 |
| O(就绪数) | epoll 的复杂度，vs select/poll 的 O(总数) |
| 1 | Netty 默认 boss 线程数（够用） |
| CPU × 2 | Netty 默认 worker 线程数 |
| 16 MB / 8 KB / 2048 | PoolChunk / PoolPage / 每 Chunk 页数 |
| 496 B / 4 KB | Tiny 上限 / Small 上限 |
| 32 KB / 64 KB | 默认写水位线（低 / 高） |
| 1460 B | 以太网 MSS，超过就 IP 分片 |
| 1 MB | `maxFrameLength` 的常见取值，防畸形包 |
| 8 MB | `transferTo` 单次常见上限，必须循环 |
| 1% | `SIMPLE` 泄漏检测的采样率 |
| 0 | `CompositeByteBuf` / `slice` / `wrap` 的复制次数 |

---
## L13 十四问答：面试官真正想听的那句

**Q1：BIO、NIO、AIO 的区别？**
- **一句话**：BIO 是"一个连接一个线程等"，NIO 是"一个线程等 N 个连接"，AIO 是"内核读完叫你"。
- **加分层**：AIO（`AsynchronousSocketChannel`）在 Linux 上底层仍是 epoll 模拟（没有真正的 io_uring 支持），所以**Java 的 AIO 在 Linux 上并没有性能优势**，Netty 也因此没做 AIO 传输。这是区分"背过书"和"踩过坑"的关键一句。

**Q2：NIO 一定比 BIO 快吗？**
不。**连接数少、数据量大**时 BIO 更快（没有 Selector 的注册/就绪检查开销）。NIO 的收益只在**高并发连接、低活跃度**场景。说"NIO 快"是不准确的，应该说"NIO 的**线程成本**更低"。

**Q3：epoll 为什么效率高？LT 和 ET 怎么选？**
三句话：① 红黑树存关注集合，增删 O(log N)；② 就绪 fd 由内核回调挂到链表，`epoll_wait` 只读链表，**O(就绪数)**；③ 不需要每次把 fd 集合拷进内核。
LT 更安全（漏读还能补），ET 更省 syscall（必须读到 EAGAIN）。**默认 LT，追求极限用 ET + native transport。**

**Q4：Netty 的线程模型说清楚。**
主从 Reactor：`bossGroup`（1 线程）只 accept，`workerGroup`（CPU×2）负责 read/write/编解码/业务。**一个 Channel 终生绑定一个 EventLoop，一个 EventLoop 一个线程串行处理多个 Channel**——所以 Netty 的 handler 天然无锁，但**绝不能阻塞**。

**Q5：Netty 为什么不用加锁？**
因为 `Channel` 的所有状态只被它自己的 `EventLoop` 线程访问。跨线程 `write` 时，Netty 把任务投递回该 EventLoop 的 `taskQueue`，**用"线程封闭"替代了锁**。这是 Netty 性能的地基。

**Q6：`ctx.write()` 和 `channel.write()` 有什么区别？**
`ctx.write()` 从**当前 handler 的下一个 outbound** 开始往前传，会跳过之前的 handler；`channel.write()` 从 **pipeline 尾部** 开始，走完整条链。需要经过前面的编码器时必须用后者——这是"数据错位"类事故的头号原因。

**Q7：粘包怎么解决？**
先纠正：TCP 没有"包"，只有字节流，所以**不存在粘包，只有应用层没定边界**。解法四选一：定长、分隔符、长度字段（主流）、自定义解码器。生产标配是 `LengthFieldBasedFrameDecoder`，五个参数（offset / length / adjustment / strip / maxFrame）必须能画图说清，尤其 `maxFrameLength` 是防 OOM 的闸门。

**Q8：零拷贝到底省了什么？**
分两层说：
- **OS 层**（`sendfile`）：省掉 2 次 CPU 拷贝（1GB 数据省 2GB 内存带宽），数据不出内核；代价是用户态拿不到数据。
- **Netty 层**（`CompositeByteBuf` / `slice` / `wrap`）：省掉 JVM 堆内的字节复制，是纯 Java 行为，**不依赖操作系统**。

**Q9：`ByteBuf` 比 `ByteBuffer` 好在哪？**
① 双指针免 `flip()`；② 可自动扩容（`ByteBuffer` 定长）；③ 引用计数 + 池化，避免 GC 与 malloc；④ `CompositeByteBuf` 零拷贝合并；⑤ 每个实例可独立设字节序。代价是**必须手动 `release()`**，忘了就泄漏。

**Q10：Netty 内存泄漏怎么排查？**
① 开 `-Dio.netty.leakDetection.level=PARANOID` + `targetRecords=32`，等 `LEAK:` 日志（带分配堆栈）；② `jmap -histo:live` 看 `Pooled*ByteBuf` 实例数是否只增不减；③ 检查所有 `ChannelInboundHandlerAdapter` 是否有 `release`，优先改 `SimpleChannelInboundHandler`；④ `-XX:MaxDirectMemorySize` 与 `PlatformDependent.maxDirectMemory()` 对齐核对。

**Q11：`@Sharable` 什么时候能用？**
只有**完全无状态**（无实例字段，或字段全 `final` 且不可变）时能用。带计数、带缓存、带 `ByteBuf` 字段的一律不能用。连接级状态放 `Channel.attr(AttributeKey)`。

**Q12：`writeAndFlush` 为什么要少调？**
每次 `flush` 至少一次 `writev` syscall（约 2μs）+ 可能触发 `OP_WRITE` 注册。批量场景应 `write()` 多次 + `flush()` 一次，或用 `FlushConsolidationHandler` 自动合并。10 次 flush = 白烧 20μs，在 800 连接的 EventLoop 上就是 16ms。

**Q13：怎么防止 Netty 被慢客户端拖死？**
三道闸门：
① `WriteBufferWaterMark` + `isWritable()`；
② `AutoRead=false`，让 TCP 窗口反向施压；
③ `IdleStateHandler`（读空闲 / 写空闲 / 全空闲）踢掉僵死连接。

**Q14：Netty 的 `EventLoop` 和 JDK 的线程池有什么本质区别？**
线程池是"任务来了找线程"，EventLoop 是"**线程绑死一组 Channel，事件来了自己处理**"。前者需要任务队列做缓冲与调度，后者靠**线程封闭**消除竞争。所以 EventLoop 不能随便扩缩容（会破坏绑定关系），而线程池可以。

---

## L14 三道模拟面试题

### 题 1：设计一个支持 50 万长连接的推送网关

**考察点**：连接数规模下的资源账、心跳与内存、广播效率。

**回答框架**：

1. **连接容量账**：50 万连接，单连接的内存 = `Channel` 对象（~1KB）+ `Pipeline`（3~5 个 handler，~1KB）+ 读写缓冲（池化后按需，平均 2KB）≈ **4KB**。50 万 × 4KB = **2GB**，加上 JVM 自身，一台 8C16G 的机器能承载，但**必须开池化 + 精简 handler 链**。
2. **线程账**：`workerGroup = CPU × 2 = 16`，平均每个 EventLoop 承载 **3.1 万连接**。这意味着单次事件处理超过 **32μs** 就会让一轮循环超过 1 秒——所以 handler 里绝不能有任何阻塞。
3. **心跳**：`IdleStateHandler(readerIdle=90s)`，服务端 90 秒没收到心跳就关。50 万连接 / 90s ≈ 5500 次/秒的心跳处理，完全可承受。**不要用 30 秒**——50 万 × (1/30s) = 1.7 万 QPS 纯心跳，白烧资源。
4. **广播**：给全部 50 万连接推一条消息，串行 `write` 要 50 万次。优化：① 用 `ChannelGroup.writeAndFlush`（内部批量）；② 消息体是同一个 `ByteBuf` 时用 `retainedDuplicate()` 共享内容，**只增引用计数不拷贝**；③ 分片广播，每 1000 个一批，避免单个 EventLoop 队列爆掉。
5. **文件句柄**：50 万连接 = 50 万 fd，必须调 `ulimit -n 1000000`，且 `net.ipv4.tcp_max_syn_backlog`、`somaxconn` 都要调。

| 档位 | 表现 |
|---|---|
| **不及格** | 说"用 Netty，主从 Reactor"，然后讲不出连接数怎么算 |
| **及格** | 能算出内存和线程账，知道要调 ulimit |
| **良好** | 加上心跳周期推导、广播用 `retainedDuplicate` |
| **优秀** | 主动提"50 万连接的 GC 压力"——池化后 `ByteBuf` 不进堆，但要关注 `Channel` 对象本身在老年代的存活；并给出"连接分片到多台网关 + 注册中心维护路由"的水平扩展方案 |

---

### 题 2：线上 Netty 服务 CPU 100%，怎么定位

**考察点**：从现象到根因的完整链路，而不是背工具名。

**回答框架**：

1. **先分诊**：`top -Hp <pid>` 看是哪个线程吃 CPU。是 `nioEventLoopGroup-*` 还是 `GC` 还是业务线程？三种情况的结论完全不同。
2. **若是 EventLoop 线程**：
   - `arthas thread -n 3` 看栈 → 若卡在 `processSelectedKeys`，说明**事件太多或单次处理太重**；
   - `arthas trace io.netty.channel.nio.NioEventLoop run '#cost > 50'` → 找出耗时的方法；
   - 常见根因：handler 里有同步 DB 调用、日志同步落盘、`synchronized` 全局锁、正则回溯。
3. **若是空转（RUNNABLE 但没干活）**：经典的 **`epoll` 空轮询 bug**（JDK NIO 的 selector 在特定内核下会返回 0 但 `select` 不阻塞），Netty 用 `selectCnt` 计数解决——连续空轮询 512 次就重建 Selector。

```java
// NioEventLoop.select() 里的防护逻辑（简化）
if (selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {   // 默认 512
    rebuildSelector();                                 // 重建，摘掉有 bug 的 selector
}
```

4. **若是 GC 线程**：`jstat -gcutil <pid> 1000`，看是否 Young GC 频繁 → 大概率是 `ByteBuf` 没池化或对象分配过快。
5. **修复要落到数字上**：别说"优化代码"，要说"把 handler 里的 MySQL 查询移到 `DefaultEventExecutorGroup(16)`，`processSelectedKeys` 的 P99 从 47ms 降到 0.8ms"。

| 档位 | 表现 |
|---|---|
| **不及格** | 只会说 `jstack`，不知道 EventLoop 线程名 |
| **及格** | 能定位到 EventLoop 被阻塞 |
| **良好** | 能区分"阻塞"与"空轮询"，知道 512 次重建 Selector |
| **优秀** | 主动指出"CPU 100% 但 QPS 掉零"是空轮询、"CPU 100% 且 QPS 也高"是真在干活——**先看 QPS 再看栈** |

---

### 题 3：客户端 30% 的连接偶发收不到消息，怎么查

**考察点**：Netty 的"静默失败"路径，这是最难查的一类线上问题。

**回答框架**：

1. **先问清"偶发"的分布**：是固定某几个客户端？还是随机的？是低峰期多还是高峰期多？——**这是把"偶发"变成"条件"的第一步**。
2. **列出 Netty 的四条静默失败路径**：

| 路径 | 表现 | 排查点 |
|---|---|---|
| `ctx.write()` 跳过了编码器 | 对端收到乱码，连接还在 | 打印 `pipeline.names()` 顺序 |
| `EventLoop` 被阻塞 | 消息延迟数秒后到达 | `trace NioEventLoop.run` |
| `outboundBuffer` 溢出被丢弃 | **完全没报错**，消息消失 | 看水位线与 `isWritable` |
| `ByteBuf` 提前 release | 发出去的是脏数据/全 0 | 开 `PARANOID` 泄漏检测 |

3. **重点怀疑第 3 条**：如果客户端网络差，服务端 `writeAndFlush` 会堆到 outboundBuffer。Netty **不会因为堆满而报错**，只会在 `channelWritabilityChanged` 里通知——**如果你没监听，就是静默丢消息**。这是"偶发 30%（正好是网络差的那批客户端）"的最合理解释。
4. **验证手段**：加监控打点 ——  `channel.bytesBeforeUnwritable()`、`channel.bytesBeforeWritable()`，以及 `channelWritabilityChanged` 的触发次数。**如果这个计数在涨，就是背压没做。**
5. **修复**：`isWritable()==false` 时 `setAutoRead(false)`，并给上游返回"限流"信号；同时业务侧做消息确认（ACK）与重发，不能假设写了就一定到。

| 档位 | 表现 |
|---|---|
| **不及格** | 说"加日志看看" |
| **及格** | 能想到编码器顺序问题 |
| **良好** | 提到 outboundBuffer 与水位线 |
| **优秀** | 指出"Netty 的写是异步的，`writeAndFlush` 返回成功不代表对端收到"，从而引出 ACK 机制与业务层可靠性——把 Netty 问题上升到**消息可靠性语义**，这是架构师的答法 |

---

## 本期小结：三句话带走

1. **IO 优化的账只有两栏**：内存拷贝几次、上下文切换几次。所有方案（mmap / sendfile / 直接内存 / 池化）都在动这两栏。
2. **Netty 快的根本原因是"线程封闭"**，不是"用了 NIO"。一个 Channel 只被一个 EventLoop 线程碰，所以整条链路无锁——代价是**这批线程一个都不能阻塞**。
3. **Netty 的事故几乎都是"静默"的**：忘了 `release`、`ctx.write` 跳过编码器、`outboundBuffer` 堆满丢消息，都不报错。所以工程上要靠泄漏检测、水位线监控、`pipeline.names()` 自检把"静默"变成"可见"。

---

> **下期预告（第 20 期）**：Java 线程池深水区与异步编排 —— `ThreadPoolExecutor` 的 `ctl` 32 位拆解、`execute` 的三次 `ctl` CAS 与 `addWorker` 失败回滚、`allowCoreThreadTimeOut` 的 `1e9` 纳秒技巧、`CompletableFuture` 的依赖栈与 `thenApply` 的 4 种线程归属、`ForkJoinPool` 工作窃取与 `commonPool` 的并行度陷阱、虚拟线程下线程池是否需要重写的真实答案。**这期会把九成人都答错的「线程池参数怎么设」换成一张可算的表。**
