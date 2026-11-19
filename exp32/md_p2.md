---

## 06 OOMKilled 解剖：exit 137 vs exit 1/3

三种死法，退出码就是取证的第一页：

| 死法 | 退出码 | 机制 | 现场特征 |
|---|---|---|---|
| Java `OutOfMemoryError`（默认） | 1 | 抛异常，主线程死，JVM 退出 | `Exception in thread "main" java.lang.OutOfMemoryError: Java heap space` |
| Java OOM + `ExitOnOutOfMemoryError` | 3 | JVM 立即自杀 | `Terminating due to java.lang.OutOfMemoryError: ...` |
| cgroup OOMKilled | 137 = 128+9 | 内核 SIGKILL | 无 Java 输出，K8s `State: Terminated, Reason: OOMKilled` |

三组都是本机实测（`Exp32.java`，`-Xmx32m` 灌满堆 / `-XX:MaxDirectMemorySize=32m` 灌满直接内存）：

- 灌堆（默认）：`Exception in thread "main" java.lang.OutOfMemoryError: Java heap space`，exit=1；
- 灌堆（`-XX:+ExitOnOutOfMemoryError`）：`Terminating due to java.lang.OutOfMemoryError: Java heap space`，exit=3；
- 灌直接内存：`java.lang.OutOfMemoryError: Cannot reserve 16777216 bytes of direct buffer memory (allocated: 33554432, limit: 33554432)`，exit=1。

第三条的信息量很足：**直接内存的 OOM 会把 allocated 和 limit 都打出来**，排障时一眼看出是直接内存而不是堆。另外注意 direct OOM 的报错也是 `OutOfMemoryError`——**它和堆 OOM 同名不同病**，修复方向完全相反（一个是减对象，一个是封 `MaxDirectMemorySize` 或找谁在囤 ByteBuffer）。

容器里推荐 `JAVA_TOOL_OPTIONS` 加 `-XX:+ExitOnOutOfMemoryError`：半死不活的进程（主线程死了但容器还在）比立刻重启更糟，编排系统就该在 OOM 后接管。配合 `-XX:+HeapDumpOnOutOfMemoryError` 时注意 dump 本身要写盘，容器只读根文件系统会失败——`-XX:HeapDumpPath` 指到挂载卷。

137 还有一个常见误判：**137 ≠ 只有 cgroup OOM**。节点内存压力下的 kubelet eviction（先杀 Pod，`Reason: Evicted`）和节点级 OOM killer 也能给出 137 系列现场，区分看 K8s 事件（`Evicted` vs `OOMKilled`）和 `dmesg`。cgroup OOM 与 eviction 的修复完全不同：前者改 limit/堆账，后者要改 requests/节点容量。

---

## 07 cgroup v1 vs v2：JVM 读哪张表

JVM 容器感知的全部秘密就是「启动时读几个内核导出的文件」。两个版本的文件名不一样，这就是 8u372/11.0.16 之下会静默失效的原因：

| 语义 | cgroup v1 | cgroup v2 |
|---|---|---|
| 内存上限 | `memory.limit_in_bytes` | `memory.max` |
| 当前用量 | `memory.usage_in_bytes` | `memory.current` |
| swap 上限 | `memory.memsw.limit_in_bytes` | `memory.swap.max` |
| OOM 计数 | `memory.oom_control`（可**关掉** OOM killer） | `memory.events`（含 `oom_kill` 计数） |
| CPU 配额 | `cpu.cfs_quota_us` / `cpu.cfs_period_us` | `cpu.max`（`$MAX $PERIOD`） |
| 挂载判别 | `/proc/self/mountinfo` 里 `cgroup` | 里 `cgroup2`（`stat -fc %T /sys/fs/cgroup/` 为 `cgroup2fs`） |

细节里藏着两个面试区分度：

- v1 的 `memory.oom_control` 可以把 OOM killer **关掉**——某些发行版/运维模板会这么干，结果是进程卡死而不是被杀，比 OOMKilled 更难查。
- v2 的 `memory.swap.max` 默认常为 0（K8s 1.28+ 的 `NodeSwap` 语义另说），内存+swap 的总约束从 v1 的 `memsw` 一分为二，JVM 的 `Metrics.getMemoryAndSwapLimit()` 在 v2 上的行为与 v1 不完全一致，别拿旧结论套。

`jdk.internal.platform.Metrics`（JDK 11+，源码 `java.base/jdk/internal/platform/Metrics.java`）是 JVM 侧的统一读口，`getMemoryLimit()` / `getMemoryFailCount()` / `getMemoryAndSwapLimit()` 等——`getMemoryFailCount` 对应内核的 OOM 计数，是「发生过几次 cgroup OOM」的直接证据。

版本结论再压一遍：**cgroup v2 支持 = JDK 15，回移 11.0.16、8u372**（Kubernetes 官方文档口径）。镜像里的 JDK 小版本号比 JDK 大版本号更重要。

---

## 08 CPU 那本账：从 quota 到 ParallelGCThreads

内存账算完算 CPU 账。链条是：

```text
cgroup cpu.max（quota/period）→ availableProcessors() → GC 线程数 / JIT 线程数 / 公共池
```

本机 16 逻辑核的实测值（`PrintFlagsFinal`）：

| 项 | 值 | 公式/说明 |
|---|---|---|
| `availableProcessors` | 16 | `Runtime.getRuntime().availableProcessors()` |
| `ParallelGCThreads` | 13 | `ncpus ≤ 8 ? ncpus : 8 + (ncpus-8)×5/8`，16 → 8+5 = 13 ✓ |
| `ConcGCThreads` | 3 | `(ParallelGCThreads + 2) / 4`，(13+2)/4 = 3 ✓ |
| `CICompilerCount` | 12 | 与核数联动的 ergonomic 值 |
| `ActiveProcessorCount` | -1 | -1 = 自动探测 |

两个公式都可以现场口算验证（13 = 8 + 40/8，3 = 15/4），这就是「数形结合」里最硬的那种数——**面试官可以拿计算器验你**。

容器场景的坑全在「探测错了」：

- 老 JDK 不认 CPU quota → 64 核节点上 limit 2 核的 Pod 开出 13+ 个 GC 线程（按 64 核算更多），在 2 个配额核里互相踩踏，延迟爆炸。**「节点越大，未感知的 JVM 死得越快」**。
- JDK 19 起 CPU shares（K8s 的 `requests.cpu`）不再参与计算，**只有 quota（`limits.cpu`）和 cpuset 算数**。有 request 没 limit 的 Pod 会看到节点全部核数——这不是 bug，是口径变了。
- `ForkJoinPool.commonPool()` 并行度 = 核数 - 1，虚拟线程调度器的载体线程数 = 核数，全跟着 `availableProcessors()` 走——一个错的核数扭曲所有池。

手动校正一行就够：`-XX:ActiveProcessorCount=2`。本机实测该 flag 生效（`availableProcessors` 16 → 2），适合「limits 还没配上但你想先限住」的过渡期，长期还是让 cgroup 说话。

---

## 09 隐形门槛：小容器会「降级」成 Serial GC

还有个纯数字触发的隐形行为：**JVM 判定「非 server 级」机器时会自动换收集器**。经验值口径（JVM 容器 ergonomics，文档事实）：

- 可用处理器 < 2，或可用内存 < 约 1792MiB → 不选 G1，退化成 **Serial GC**（单线程 STW）；
- 1 核 + 1Gi 的小 Pod 因此跑着单线程 GC，停顿随堆增大而变长，而运维完全不知情。

排查入口是 `PrintFlagsFinal` 里的 `UseSerialGC` / `UseG1GC`，或 `jcmd 1 VM.flags`。小 Pod 上要吞吐就显式 `-XX:+UseG1GC`（或按业务选 ZGC/Shenandoah），不要赌默认。

这条和第 08 节是同一枚硬币：**容器的「小」有两层——内存小、CPU 小——两层都触发 JVM 的降级逻辑**，而降级的动机是「这台机器大概率是玩具」，在容器时代这个启发式经常误伤。

---

## 10 从 limit 倒推 Xmx：4Gi 案例完整走一遍

把 05 的账本变成可执行的决策流（4Gi limit、300 线程、常规 Spring Boot + 少量 Netty）：

1. **列非堆清单**：Metaspace 封顶 384M（`-XX:MaxMetaspaceSize=384m`）+ CodeCache 240M + 线程栈 300M（先别动 `-Xss`）+ 直接内存 256M（显式封顶）+ GC/本地余量 250M ≈ **1430M**；
2. **倒推堆**：4096M − 1430M ≈ **2660M**，取整 `-Xmx2600m`；
3. **二选一落地**：固定规格就写 `-Xms2600m -Xmx2600m`（免动态扩容），镜像要复用多规格就写 `-XX:MaxRAMPercentage=65.0`（记得带小数点，`65` 会启动失败）；
4. **留观察位**：上线后看 RSS 与 `memory.current`，若长期贴 limit，把余量从 250M 加到 350M 而不是先杀 `-Xmx`。

对照「网上流传最广的模板」`-XX:MaxRAMPercentage=75.0`：它隐含「非堆只占 25%」的假设。对 300 线程 + Netty 的网关，25% 根本装不下非堆——75% 模板是「从内向外碰运气」，正确姿势永远是**从外向内倒推**（本节数字全部可加可验，这就是数形结合的意义）。

---

## 11 现场取证：三板斧 + 一个 Windows 冷知识

容器里 JVM 到底看见了什么，不要猜，问它：

```bash
# 1. 系统视角：容器里会打出 cgroup 限额与核数
java -XshowSettings:system -version

# 2. 探测日志：JVM 读了哪些 cgroup 文件、结论是什么
java -Xlog:os+container=info -version

# 3. 运行中进程的最终 ergonomics
jcmd 1 VM.flags | tr ' ' '\n' | grep -E "MaxHeapSize|ParallelGCThreads|ActiveProcessorCount|UseG1GC"
jcmd 1 VM.info | grep -A5 container
```

本机（Windows）实测的冷知识：`-Xlog:os+container=info` 直接报 `No tag set matches selection: os+container`——**这个 tag 只存在于 Linux 构建**。也就是说容器取证没有本地替身，必须在目标容器里跑；Windows 上的 `Max. Heap Size (Estimated): 7.93G` 只能验证百分比公式，验证不了 cgroup。这也是本期把版本事实与文档事实显式标注的原因：本机能实测的全部实测，不能实测的绝不装作实测。

- **镜像 smoke test**：直接固化第一条命令 `java -XshowSettings:system -version`，打出来的内存若是节点内存而不是 limit，后面所有参数都不用看了——先换 JDK。
