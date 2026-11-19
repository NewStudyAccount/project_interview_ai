---

## 12 七坑清单

1. **`-Xmx` 等于 limit**：把容器内存全给堆，非堆把墙撞破，死于 137 而不是 OOM。留 25%~35% 给非堆是纪律不是保守。
2. **`MaxDirectMemorySize` 不设**：默认上限 = 堆大小，Netty/NIO 服务能再吃一倍内存。显式封顶（如 256m）。
3. **cgroup v2 + 老 JDK**：11.0.15 / 8u371 及以前在新内核上静默退回宿主机口径。版本底线 8u372 / 11.0.16 / 15+。
4. **`-XX:MaxRAM=64G` 这种「写大点」的安慰剂**：显式设 `MaxRAM` 会让基准内存直接等于它，堆被规划得比机器还大（本机 32G 机器实测能规划出 16G 堆）。
5. **`MaxRAMPercentage=65` 不带小数点**：启动直接报错，必须写 `65.0`。CI 里跑一次 `java -version` 级别的参数校验能拦住。
6. **CPU 只设 request 不设 limit**：JDK 19 起 shares 不算数，JVM 看到节点全部核数，GC 线程爆炸。
7. **OOM 后没有现场**：不加 `ExitOnOutOfMemoryError` 会留半死进程；不加 `HeapDumpOnOutOfMemoryError` + 可写路径，内存问题永远在猜。137 是结果，不是证据。

---

## 13 数字速查

| 数字 | 含义 |
|---|---|
| 25% | `MaxRAMPercentage` 默认，堆占基准内存比例 |
| 50% | `MinRAMPercentage` 默认，小内存分支比例 |
| 1/64 (1.5625%) | `InitialRAMPercentage` 默认，初始堆比例 |
| 128G | `MaxRAM` 默认值（min(物理内存, 128G) 的天花板） |
| 8510242816 | 本机 32GiB 默认 MaxHeapSize（7.93G = 31.7GiB × 25%） |
| 126MiB | 本机扫描出的「小内存分支」地板（默认 MaxHeapSize 下限 + 2MiB 对齐） |
| 251658240 | CodeCache 默认预留 240MB |
| 1073741824 | 压缩类空间默认虚拟上限 1G |
| 0 | `MaxDirectMemorySize` 默认值 = 与堆等大 |
| 13 = 8+(16-8)×5/8 | 16 核的 ParallelGCThreads |
| 3 = (13+2)/4 | ConcGCThreads |
| 1 / 3 / 137 | 退出码：OOM 默认 / ExitOnOOM / cgroup SIGKILL |
| 8u131 / 8u191 | 实验性 cgroup 内存 / 完整容器感知（默认开） |
| 15 / 11.0.16 / 8u372 | cgroup v2 支持及回移版本 |
| 19 | CPU shares 退出核数计算的 JDK 版本 |
| 1792MiB / 2 CPU | 退化 Serial GC 的经验门槛 |
| 4096−1430≈2660M | 4Gi limit 的正确 Xmx（倒推账） |

---

## 14 十四问答

**Q1：`-Xmx` 和 `-XX:MaxRAMPercentage` 谁生效？**
写了 `-Xmx` 则百分比全部失效（`FLAG_IS_DEFAULT(MaxHeapSize)` 不成立，整段推导跳过）。反之百分比只在没写 `-Xmx` 时起作用。

**Q2：`UseContainerSupport` 要不要手动加？**
8u191+/JDK10+ 默认 true，加了不亏但没用；真正要查的是**版本够不够新、探测成功没成功**。`-XX:-UseContainerSupport` 才是需要显式写的场景（关掉探测）。

**Q3：容器里 `-Xms=-Xmx` 好不好？**
固定规格 Pod 好：省掉动态扩容和扩容期 GC；但前提是 Xmx 是从 limit 倒推出来的，不是抄来的。

**Q4：`/proc/meminfo` 在容器里为什么不能信？**
它显示的是**宿主机**内存量（部分内核/运行时组合下）。JVM 走 cgroup 文件，人查内存也该看 `memory.max`/`memory.current`。

**Q5：Metaspace 会不会导致 OOMKilled？**
会。Metaspace 在堆外，不封顶的它涨多少 RSS 就涨多少——热部署/大量动态类的场景要 `MaxMetaspaceSize` 封顶并观察。

**Q6：exit 134 和 137 什么区别？**
134 = 128+6 SIGABRT（JVM 内部 fatal、glibc 断言等），137 = 128+9 SIGKILL（内核杀）。两者都不是 Java OOM。

**Q7：`Runtime.availableProcessors()` 准不准？**
JDK 版本对 + cgroup 探测成功就准（= quota/period 向上取整与 cpuset 的结果）。JDK 19 起不再理会 shares。不放心就 `ActiveProcessorCount` 钉死。

**Q8：为什么我的服务堆才用 60% 就 OOMKilled？**
堆只占总账的一半多。看 RSS：直接内存/线程栈/Metaspace 哪一笔在涨。`jcmd VM.native_memory`（需 `-XX:NativeMemoryTracking=summary`）能分区域。

**Q9：`-XX:MaxRAMPercentage` 设多少合适？**
没有普适值。从 limit 减非堆倒推；小服务 70~75% 常见，网关类 50~65%。模板值 75% 是假设非堆只有 25%，先验证假设。

**Q10：小 Pod 为什么 GC 停顿反而长？**
可能是「非 server 级」降级成了 Serial GC（<2 CPU 或 <1792MiB）。查 `UseSerialGC`，显式 `UseG1GC`。

**Q11：JDK 8u191 以下还有什么救？**
临时 `-XX:MaxRAMFraction=4`（旧口径，`UseCGroupMemoryLimitForHeap` + `UnlockExperimentalVMOptions`）只管内存；根治是升级 JDK——8u372/11.0.16/15+ 是 cgroup v2 时代的底线。

**Q12：sidecar 会不会影响内存账？**
会。Pod 里多容器共享 pod cgroup 时，节点侧看的是合计；JVM 自己的 limit 若按容器 cgroup 设，账目要按容器算，别按 Pod 总量给堆。

**Q13：K8s 的 requests 和 JVM 参数有什么关系？**
requests 决定调度与驱逐优先级；limits 才进 cgroup 成为 JVM 的探测源。JDK 19 起 CPU request（shares）与 `availableProcessors` 无关。

**Q14：为什么压测好好的，上容器就 OOMKilled？**
压测机没有 cgroup 墙。本地你有 32G，容器只给 4Gi——「本地的吞吐」和「容器里的存活」是两个问题。第 10 节的倒推账在部署前做完，而不是死后补。

---

## 15 三道模拟面试（四档对照）

### 题 1：Pod 内存 limit=4Gi，启动参数 `-Xmx4g`，服务运行两天后 OOMKilled，重启后反复。为什么？怎么修？

- **不合格**：内存泄漏了，重启就好，加大内存。
- **及格**：`-Xmx4g` 太大了，堆外还有内存，超过 limit 被杀。把 `-Xmx` 调小。
- **良好**：`-Xmx` 只管堆，进程还有 Metaspace、CodeCache、线程栈、直接内存等非堆（约 1.2~1.4G），RSS 峰值约 5.3GiB 超过 4Gi limit，被内核 SIGKILL（exit 137），所以没有 Java 栈。修法：从 limit 倒推，`-Xmx≈2600m` 或 `MaxRAMPercentage≈65.0`，并给 `MaxDirectMemorySize` 封顶、`MaxMetaspaceSize` 封顶。
- **优秀**：在良好基础上给出完整因果链和验证手段。① **机制**：cgroup OOM killer 按 RSS 杀进程，堆只是 RSS 的一部分；本例堆 4g + 非堆 ~1.3g ≈ 5.3g > 4g，且「运行两天后」说明非堆/直接内存在缓慢爬升（连接泄漏囤 ByteBuffer、或 Metaspace 随热部署增长），到某个高峰触线。② **取证**：`kubectl describe pod` 看 `OOMKilled` + `exitCode: 137`；`dmesg | grep -i oom` 看内核记录；`jcmd VM.native_memory`（开 NTT）对比两天前后的区域增量；`memory.current` 曲线是否单调爬升。③ **修复优先级**：先止血（倒推 Xmx + 三处封顶：Direct/Metaspace/CodeCache 已有默认）→ 再定位爬升源（direct 的 allocated 曲线、类加载计数）→ 最后固化（镜像 smoke test 跑 `java -XshowSettings:system`，limit 与 MaxHeapSize 的比例进 CI 校验）。④ **反直觉点**：如果只把 `-Xmx` 降到 3g 而不封直接内存，Netty 服务仍可能死——默认直接内存上限是堆大小，降堆同时把直接内存上限也缩了，但泄漏源没变时依然触线。修复要「账本 + 泄漏源」双管。

### 题 2：2 核 4Gi 的 Pod，服务 P99 延迟波动大，监控显示 CPU 使用率只有 30%。你怎么排查？

- **不合格**：CPU 不高说明不是性能问题，加机器。
- **及格**：可能是 GC 停顿，看 GC 日志。
- **良好**：CPU 使用率 30% 是**按 2 核配额算的均值**，掩盖了停顿。先看 JVM 看到的核数：`availableProcessors` 若是节点核数（如 64）而非 2，说明容器感知失效（老 JDK/cgroup v2）或没设 limit，GC 线程 13+ 在 2 核上踩踏，上下文切换把 P99 打爆。取证：`jcmd 1 VM.flags` 看 `ParallelGCThreads`、`-Xlog:os+container=info`；GC 日志里安全点与停顿分布。
- **优秀**：① **先分辨「均值骗人」**：30% 可能是 2 核里 1 核打满、另一核闲着，或大量时间在等锁/上下文切换，CPU 均值低不代表没瓶颈。② **机制链**：`availableProcessors` 错 → `ParallelGCThreads`（64 核口径是几十个）+ JIT 线程 + `ForkJoinPool.commonPool` 全按大机器开 → 2 核配额下 CFS 节流 + 自旋 + 缓存颠簸，P99 放大；JDK 19 前 shares 干扰口径、cgroup v2+老 JDK 静默失效都是常见根因。③ **取证链**：`Runtime.availableProcessors()` 打日志、`jcmd VM.flags | grep Threads`、`-Xlog:os+container=info`（Linux）、`pidstat -w` 看上下文切换、GC 日志停顿分布、`memory.high`/`cpu.stat` 的 throttled 计数（v2）。④ **修复**：版本对齐（8u372/11.0.16/15+）或 `-XX:ActiveProcessorCount=2` 钉死 → GC 线程按 2 核重算（`ParallelGCThreads=2`）→ 按停顿目标考虑 ZGC/Shenandoah 或调 G1 停顿目标 → 验证指标改成「P99 与 throttled 时间」而不是 CPU 均值。⑤ **反直觉点**：把 limit 从 2 核加到 4 核有时反而更糟——`availableProcessors` 变 4，GC 线程更多，若内存不变则争抢依旧；核数和内存要联动改，单动一边是新人动作。

### 题 3：服务从物理机迁到 K8s，同一个 jar，参数一字未改，内存占用从 3G 涨到 6G 并被 OOMKilled。列出你的怀疑清单。

- **不合格**：K8s 不稳定，回滚物理机。
- **及格**：容器内存限制太小，调大 limit。
- **良好**：怀疑顺序：① JVM 容器感知失效（JDK 老 / cgroup v2）按宿主机规划堆；② `-Xmx` 或百分比在容器里的基准变了；③ 直接内存/线程数按物理机口径配置（线程池按 64 核开）；④ limit 太小没算非堆。逐条验证：`XshowSettings:system`、`VM.flags`、线程数、RSS 分解。
- **优秀**：给出带判据的排查矩阵。① **基准漂移**：物理机 64G 时 Xmx=4g 手写死没问题；进容器若用 `MaxRAMPercentage`，基准从 64G 变 4Gi，或者反过来——老 JDK 在容器里仍按宿主机 64G 的 25% 算出 16G 堆，直接超 limit。判据：`MaxHeapSize` 与 limit 的比值是否合理。② **探测失效**：cgroup v2 内核 + 11.0.15 镜像的组合，`-Xlog:os+container` 打印的是宿主机数据。③ **线程/CPU 账**：物理机 64 核开的池子（Tomcat 800、commonPool 63）到 2 核 Pod 里全部过载，RSS 里线程栈 800M+，且延迟抖动。④ **swap 语义**：物理机有 swap、容器 `memory.swap.max=0`，物理机上「涨到 5G 还活着」靠 swap 撑着，容器里 4Gi 是硬墙。⑤ **修复顺序**：先用三板斧把「JVM 看见了什么」钉死 → 重算总账（第 10 节倒推法）→ 线程参数按 `availableProcessors` 重设 → 最后才是调 limit。⑥ **反思**：「一字未改」本身就是问题——参数的隐含假设（机器多大、几个核、有没有 swap）在两个平台上完全不同，**迁移的本质是重算假设，不是搬参数**。

---

## 16 32 期地图与下期预告

| 板块 | 期号 | 一句话抓手 |
|---|---|---|
| JVM 内存与 GC | 02 / 12 / 21 / 32 | 02 讲结构，12 讲调优，21 讲泄漏，32 讲容器里的账 |
| 并发 | 03 / 14 / 20 | 锁升级、并发容器、线程池与异步 |
| 框架 | 04 / 15 / 24 | Spring 核心、Boot 自动配置、MyBatis |
| 存储 | 05 / 16 / 25 | 索引与 MVCC、分库分表、倒排检索 |
| 中间件 | 06 / 07 / 17 / 18 | Redis、MQ、缓存一致性、幂等 |
| 网络与通信 | 19 / 30 / 31 | NIO/Netty、超时重试、连接池 |
| 容器与云原生 | **32** | **两本账：JVM 的堆 × 内核的墙** |
| 工程化 | 13 / 23 / 26 / 27 | 类加载、反射代理、字节码、依赖冲突 |
| 排查与观测 | 09 / 28 | 五步归因、P99 对不上先查口径 |
| 设计 | 10 / 11 | 场景题四步骨架、设计模式 |

面试官问容器里的 JVM，本质是问三件事：**你知不知道堆外还有账（第 05/10 节）、你知不知道 JVM 会看错机器（第 02/08 节）、你知不知道死亡分两种（第 01/06 节）**。三条都答上来，这题就是送分；答不上来，前面背的 GC 参数全是空中楼阁。

- **下期预告 · 第 33 期：认证授权与会话**——JWT 无状态的代价（吊销难题与黑名单账）、OAuth2 四种角色一次讲清、Session 一致性的三种做法、Token 刷新的时序坑、「网关统一鉴权」的边界在哪里。

---

> 本期实验：`Exp32.java`（info/oom/direct/apc 四模式，JDK 21.0.7 实跑）+ `PrintFlagsFinal` 扫描 20 组（MaxRAM 32M→2G、MaxRAMPercentage 10/25/50、-Xmx4g）+ OOM 退出码三组 + `set_heap_size` 源码对质（openjdk/jdk21u）。
> 标注「文档事实」的（8u131/8u191/cgroup v2 回移版本、Serial 门槛 1792MiB/2CPU、JDK 19 shares 口径）来自 Kubernetes 官方文档与 JVM 容器 ergonomics 公开资料，本机为 Windows 无 Docker、未在 Linux 容器内复验；标注「实测」的均可在本机复现。MinRAMPercentage「250MB 阈值」一说在 JDK 21 源码中未找到常量佐证，本文以源码分支逻辑为准。
