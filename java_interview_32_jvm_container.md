# Java 面试深挖 32 · JVM 与容器边界

> 副标题：从「`-Xmx4g` 为什么还被 OOMKilled」到「JVM 到底看见了谁的资源」
> 本期数字来自本机 JDK 21.0.7 实测（PrintFlagsFinal 扫描 20 组、OOM 退出码三组、MaxRAM 扫描 20 点）+ OpenJDK 21 源码 `Arguments::set_heap_size` 逐行对质。容器侧事实（8u131/8u191/cgroup v2 回移版本）标注来源，本机是 Windows、无 Docker，凡未实测处均显式标注。

---

## 01 先定性：两本账、两个杀手

「Pod 内存 limit 4Gi，`-Xmx4g`，为什么还是 OOMKilled？」——这是云原生 Java 面试里区分度最高的一道题。答不上来的人，说明他把两本账当成了一本：

- **JVM 的账**：堆（`-Xmx`）只是进程内存的一部分。堆外还有 Metaspace、CodeCache、线程栈、直接内存、GC 自身的数据结构。JVM 的 `OutOfMemoryError` 是「堆内分配不动了」，进程还活着，甚至还能打 heapdump。
- **内核的账**：cgroup 的 `memory.max`（v2）/ `memory.limit_in_bytes`（v1）管的是**整个进程**的 RSS+swap。超了就直接 SIGKILL，exit 137，没有任何 Java 栈、没有任何日志，Kubernetes 里只留一行 `OOMKilled`。

两个杀手的分工一句话记住：

> **`OutOfMemoryError` 是 JVM 杀你的业务对象，`OOMKilled` 是内核杀你的进程。前者堆还没满也可能发生，后者堆还远没满就会发生。**

`-Xmx4g` + limit 4Gi 的经典死法：堆的确能涨到 4g，但 Metaspace 200M + CodeCache 240M + 300 个线程栈 300M + 直接内存 + GC 开销 ≈ 1.3g，RSS 冲到 5.3g 时堆才用掉 4g 的八成——墙先塌了。进程死于内核，不是死于 JVM。

| 维度 | OutOfMemoryError | OOMKilled |
|---|---|---|
| 杀手 | JVM（GC/分配器） | 内核 cgroup OOM killer |
| 触发 | 某个内存区域耗尽（堆/Metaspace/直接内存） | 进程 RSS 超过 cgroup limit |
| 现场 | Java 异常栈、可 heapdump | 无栈、无日志、exit 137 |
| 堆的状态 | 该区域满了（堆可能还很大） | 堆多半**远未满** |
| 修复方向 | 代码/泄漏/该区域参数 | 内存总账重算：limit 或 `-Xmx` 或非堆 |

---

## 02 时间线：JVM 什么时候才「看得见」容器

老 JDK 在容器里读的是宿主机：64G 内存、64 核的节点上跑一个 limit 2Gi 的容器，JVM 按 64G 的四分之一规划出 16G 的堆，再按 64 核开 GC 线程——启动即 OOMKilled，或慢得莫名其妙。补课的时间线必须背下来，它决定基础镜像的 JDK 版本底线：

| 版本 | 事件 | 要点 |
|---|---|---|
| 8u131 | `UseCGroupMemoryLimitForHeap`（实验性） | 只管内存、要手动解锁实验开关，CPU 不认 |
| 8u191 | `UseContainerSupport` 默认开启（JDK-8146115 回移） | 内存 + CPU 都认，百分比三兄弟可用 |
| JDK 10 | 容器感知进主线 | 与 8u191 同一故事的主线版本 |
| JDK 15 | **cgroup v2 支持**（回移 11.0.16、8u372） | 新内核 + 老 JDK = 静默失效，退回宿主机口径 |
| JDK 19 | CPU shares（K8s request）不再参与核数计算 | 只看 quota/cpuset；有 request 无 limit 的 Pod 看到节点核数 |

来源：JDK-8146115 与各发行版 release notes；cgroup v2 的回移版本（8u372 / 11.0.16 / 15+）以 Kubernetes 官方文档 cgroups 页面为准。

最容易踩的是第四行：2024 年后的 Linux 发行版和 Kubernetes 节点几乎都切到了 cgroup v2，一个 11.0.15 的镜像跑上去，容器感知**静默失效**——`-XX:+UseContainerSupport` 还是 true（它只管「要不要探测」，探测不到不报错），JVM 于是按宿主机内存规划，然后死得毫无征兆。所以版本底线是：**8u372 / 11.0.16 / 15 及以上**。

---

## 03 堆是怎么算出来的：源码 + 实测扫描

不写 `-Xmx` 时，最大堆由 `Arguments::set_heap_size()` 推导。OpenJDK 21 的核心分支就这几行（`src/hotspot/share/runtime/arguments.cpp`）：

```cpp
if (FLAG_IS_DEFAULT(MaxHeapSize)) {
  julong reasonable_max = (julong)((phys_mem * MaxRAMPercentage) / 100);
  const julong reasonable_min = (julong)((phys_mem * MinRAMPercentage) / 100);
  if (reasonable_min < MaxHeapSize) {
    // Small physical memory, so use a minimum fraction of it for the heap
    reasonable_max = reasonable_min;
  } else {
    reasonable_max = MAX2(reasonable_max, (julong)MaxHeapSize);
  }
  ...
  FLAG_SET_ERGO(MaxHeapSize, (size_t)reasonable_max);
}
```

三个百分比的本机默认值（`PrintFlagsFinal` 实测）：

- `MaxRAMPercentage=25.000000`：正常内存下的堆比例，**四分之一**；
- `MinRAMPercentage=50.000000`：小内存机器兜底，比例反而更大（小机器更需要堆？不——是小机器上「最小可用堆」的下限策略）；
- `InitialRAMPercentage=1.562500`：即 1/64，`-Xms` 未指定时的初始堆。

「小内存」的阈值是多少？网上流传「250MB」——**本机源码核查结果：`set_heap_size` 及周边没有 250M 常量**。真实分支是拿 `phys_mem × 50%` 与默认 `MaxHeapSize` 比大小（见上方源码），250MB 是某些实践文档的转述，面试引用时要说「与默认 MaxHeapSize 比较」，别说 250。

本机 32GiB 内存的实测账（这是全期最重要的一组数）：

| 探针 | MaxHeapSize | 折算 |
|---|---|---|
| 默认（无 flag） | 8510242816 (7.93G) | = 可见物理内存 × 25% |
| `-XX:MaxRAMPercentage=50` | 17020485632 (15.85G) | 恰好 2 倍 |
| `-XX:MaxRAMPercentage=10` | 3403677696 (3.17G) | 恰好 0.4 倍 |
| `-Xmx4g` | 4294967296 (4G) | 百分比全部失效 |

线性关系 100% 对齐：**堆 = 基准内存 × 百分比**。注意「可见物理内存」是 34040971264（31.7GiB），比标称 32GiB 少一截——JVM 拿到的本来就是「OS 报告的可用物理内存」，不是内存条标称值。`-Xms` 默认 532676608（508MiB）= 可见内存 × 1/64，也对上了。

---

## 04 MaxRAM 的隐藏陷阱：一改 flag 就换基准

上面反复说「基准内存」，那它一定是物理内存吗？**不一定。** 源码第一段：

```cpp
bool override_coop_limit = (!FLAG_IS_DEFAULT(MaxRAMPercentage) ||
                           !FLAG_IS_DEFAULT(MinRAMPercentage) ||
                           !FLAG_IS_DEFAULT(InitialRAMPercentage) ||
                           !FLAG_IS_DEFAULT(MaxRAM));
if (override_coop_limit) {
  if (FLAG_IS_DEFAULT(MaxRAM)) {
    phys_mem = (julong)os::physical_memory();
  } else {
    phys_mem = (julong)MaxRAM;   // 显式设了 MaxRAM → 基准就是 MaxRAM 本身
  }
} else {
  phys_mem = MIN2(os::physical_memory(), (julong)MaxRAM);
}
```

**只要你显式写了 `MaxRAM`，基准内存就直接等于 `MaxRAM`，与真实物理内存脱钩。** 本机实测验证了这个陷阱（32GiB 机器）：

| 命令行 | MaxHeapSize | 解释 |
|---|---|---|
| `-XX:MaxRAM=1G` | 256MiB | = 1G × 25% |
| `-XX:MaxRAM=64G` | 16GiB | = 64G × 25%，**超过本机真实内存的一半** |
| `-XX:MaxRAM=65G` | 16.25GiB | = 65G × 25% |

第二行是事故素材：在 32G 的机器上写 `-XX:MaxRAM=64G -XX:MaxRAMPercentage=25`，JVM 会心安理得地规划出 16G 的堆——`MaxRAM` 不是「上限」，在显式设置时它就是「假装拥有的内存」。默认路径（什么都不写）才是 `min(物理内存, MaxRAM=128G)`。

再用 20 个点扫描「小内存分支」的拐点（`-XX:MaxRAM=X` 扫描，X 从 32M 到 2G）：

| MaxRAM | 32M | 64M | 128M | 240M | 250M | 252M | 256M | 400M | 504M | 508M | 512M | 1G | 2G |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| MaxHeapSize | 16M | 32M | 64M | 120M | 126M | 126M | 126M | 126M | 126M | 128M | 128M | 256M | 512M |
| 比例 | 50% | 50% | 50% | 50% | 平台 | 平台 | 平台 | 平台 | 平台 | 25%↑ | 25% | 25% | 25% |

三段式清清楚楚：小内存走 50%（`MinRAMPercentage` 分支），中间有约 126MiB 的地板（默认 `MaxHeapSize` 的下限 + 2MiB 对齐），大内存走 25%。这条曲线背下来，`MinRAMPercentage` 什么时候生效就永远不会答错。

---

## 05 内存总账：RSS = 堆 + 五笔非堆

容器 limit 管的是整个进程，所以必须先算总账再定 `-Xmx`。以 limit=4Gi、300 线程、Spring Boot 常规服务为例：

| 区域 | 典型占用 | 是否可控 | 说明 |
|---|---|---|---|
| Java 堆 | `-Xmx` 定 | 是 | 唯一被百分比/`-Xmx` 控制的部分 |
| Metaspace | 100~400M | 半可控 | 默认无上限，`-XX:MaxMetaspaceSize` 可封顶 |
| 压缩类空间 | 虚拟 1G | 半可控 | `CompressedClassSpaceSize` 默认 1073741824，提交量小 |
| CodeCache | 240M | 是 | 本机实测 `ReservedCodeCacheSize=251658240` |
| 线程栈 | 线程数 × 1M | 是 | `-Xss` 可降；300 线程 = 300M |
| 直接内存 | 0 ~ Xmx | **必须显式限** | `MaxDirectMemorySize=0` 时**默认等于堆大小** |
| GC/本地/JNI | 100~300M | 难 | G1 记忆集、native malloc、页表等 |

两个最容易被忘的数：

- **直接内存的默认上限是堆大小本身**。本机实测 `MaxDirectMemorySize=0`（默认），含义是「与 MaxHeapSize 相同」——`-Xmx4g` 的进程，NIO/Netty 理论上还能再吃 4g 直接内存。Netty 重度服务必须显式 `-XX:MaxDirectMemorySize=256m` 之类封顶。
- **CodeCache 默认就 240M**，这不是可选项，是起步价。

于是 4Gi limit 的正确算法是**从外向内倒推**：

```text
limit 4096MiB
  - Metaspace 封顶      384M
  - CodeCache          240M
  - 线程栈 300×1M       300M
  - 直接内存（显式）    256M
  - GC/本地余量        250M
  ────────────────────────
  非堆合计            ~1430M
  可给堆             ~2660M（≈ limit 的 65%）
```

- **反过来看错误答案**：`-Xmx4g` 等于 limit，再加 1430M 非堆，RSS 上限 5.4GiB 大于 4Gi limit，必然 OOMKilled。「limit 就给堆」是把 JVM 当成只有堆的进程，这是本题最大的认知分水岭。
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
