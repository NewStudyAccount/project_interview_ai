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
