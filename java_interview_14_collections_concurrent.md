# Java 面试深水区 14 · 集合框架深水区与并发容器

> "ArrayList 和 LinkedList 什么区别"——这题被问烂了，多数人答"一个数组一个链表，前者查询快后者增删快"。然后面试官让你在这两个里选一个存 10 万条数据做中间插入，你说 LinkedList，跑完发现它慢了三倍。因为"增删快"省掉的是改指针，而链表要先花 O(n) 走到那个位置，这一路的缓存未命中足够 ArrayList 把整段内存搬好几遍。
>
> 本期把集合里所有"背下来的结论"重新算一遍账：1.5 倍扩容的总拷贝量是多少、HashMap 树化概率为什么是千万分之六、LRU 三行代码怎么写、COW 什么时候不能用、七种 BlockingQueue 怎么选。

**本期结构**：12 层递进 · 11 张原理图 · 18 段代码 · 14 条问答 · 3 道模拟面试

---

## L0 全景与选型：第一道题不是"区别"，是"你凭什么这么选"

集合框架里真正需要背的类只有二十来个，剩下七十多个是它们的变体、视图、包装器和历史包袱。

```
List · 有序可重复            Set · 唯一性                Map · 键值映射
├─ ArrayList   数组 1.5x      ├─ HashSet   复用 HashMap     ├─ HashMap      数组+链表+树
├─ LinkedList  双向链表       ├─ LinkedHashSet 插入序      ├─ LinkedHashMap 多个双向链
├─ Vector      全同步（弃）   ├─ TreeSet   红黑树          ├─ TreeMap      红黑树
└─ CopyOnWriteArrayList      └─ ConcurrentSkipListSet     └─ ConcurrentHashMap
   写时复制 读零锁              跳表 并发有序                  CAS+桶锁 禁 null
```

**选型三问**（按顺序问，问到就停）：

1. 要按下标随机访问吗？→ List，且大概率就是 ArrayList
2. 要按 key 找 value，还是要排序/范围查询？→ Map；只有排序需求才上 TreeMap
3. 有多个线程同时写吗？→ 换并发容器，别再给普通容器加 synchronized 外衣

默认值就两个：**ArrayList + HashMap**。90% 的性能事故不是"选错容器"，是"选了容器却忘了它的增长方式"。

### 复杂度表钉正：LinkedList 的 O(1) 是半句真话

| 操作 | ArrayList | LinkedList | 真实差距来自哪里 |
|---|---|---|---|
| `get(i)` | O(1) | O(n) 走指针 | ArrayList 是基址+偏移，一条指令 |
| `add(E)` 尾部 | 均摊 O(1) | O(1) | ArrayList 偶尔扩容拷贝，其余写一个槽位 |
| `add(i,E)` 中间 | O(n) 拷贝 | **O(n) 定位 + O(1) 改指针** | 链表省掉拷贝，没省掉走位 |
| `remove(i)` 中间 | O(n) 拷贝 | O(n) 定位 | 链表每步都可能缓存未命中 |
| 遍历 | 极致快（连续内存） | 慢 3~5 倍 | 数组可预取可向量化，链表每跳都在等内存 |
| 单元素内存 | 4B 引用 | **24B Node** | 每个元素是独立对象，还有 GC 标记成本 |

**必被追问的坑**：10 万元素，在列表正中间反复插入 1000 次，LinkedList 真的一定更快吗？

不一定。ArrayList 每次插入要搬 5 万个引用 = 200KB，memmove 是内存带宽级操作，200KB 大约几十微秒；LinkedList 每次要跳 5 万次指针，每次都是一次 L1/L2 未命中，约 5~20 纳秒，5 万次就是 250 微秒到 1 毫秒。所以在这个量级上 ArrayList 反而更快。

能让 LinkedList 赢的场景极其狭窄：**已经持有节点位置**（ListIterator 停在原地），或者元素本身极大、拷贝成本超过走位成本。

### 补一刀：Java 官方自己都不用 LinkedList

`LinkedList` 真正被 JDK 内部使用的场景几乎为零。它实现了 `Deque`，所以很多人拿它当栈和队列——但 `ArrayDeque` 在栈和队列两种用法上都更快、更省内存（环形数组，无 Node 对象）。`Deque` 接口的文档第一句推荐就是"用 ArrayDeque"。

```java
// 栈和队列：用 ArrayDeque，不要用 LinkedList 也不要再用 Stack
Deque<String> stack = new ArrayDeque<>();
stack.push("a");            // 等价 addFirst
stack.pop();                // 等价 removeFirst

Deque<String> queue = new ArrayDeque<>();
queue.offer("a");           // 等价 offerLast
queue.poll();               // 等价 pollFirst

// 唯一还需要 LinkedList 的场合：频繁在"已持有迭代器位置"处增删
ListIterator<String> it = list.listIterator();
while (it.hasNext()) {
    if (bad(it.next())) it.add("fix");   // 真 O(1)，因为位置就在手上
}
```

> **一句话答法**：ArrayList 是数组，随机访问 O(1)，扩容 1.5 倍，中间插入要 memmove；LinkedList 是双向链表，改指针 O(1) 但定位 O(n)，每个元素多 24 字节对象头开销。实际工程里 95% 用 ArrayList，栈和队列用 ArrayDeque，LinkedList 基本可以不用。

---

## L1 ArrayList 扩容：1.5 倍是从哪来的，代价是多少

### 一行源码，八个容量台阶

核心就一行：`newCapacity = oldCapacity + (oldCapacity >> 1)`。位移代替除法，所以是**下取整的 1.5 倍**，容量序列变成 10 → 15 → 22 → 33 → 49 → 73 → 109 → 163。

```
容量                                                     本次拷贝量(=旧容量)
10   ██                                                   0    首次分配
15   ███                                                  10   ██
22   █████                                                15   ███
33   ████████                                             22   █████
49   ███████████                                          33   ████████
73   █████████████████                                    49   ███████████
109  █████████████████████████                            73   █████████████████
163  █████████████████████████████████████               109   █████████████████████████

累计拷贝 0+10+15+22+33+49+73+109 = 311 ≈ 2 × 163
累计申请容量 ≈ 3 × 163，空洞率上限 1/3
```

两个必须记住的结论：

1. **均摊成本是 O(1)，但常数是 2。** 几何增长因子 g，累计拷贝量约 N/(g−1)。g=1.5 就是 2N，g=2 就只有 N。所以 2 倍增长在拷贝次数上更省，但内存浪费上限是 50%。ArrayList 选了"省内存"；HashMap 选了"2 倍"——但后者不是为了省拷贝，是**必须让长度是 2 的幂**才能用位运算取模。
2. **容量不等于大小。** `size()` 是元素个数，容量是数组长度。存 100 个元素容量 109，占 436 字节还行；但存 10000 个元素时容量会推到 10937，多出 937 个空槽。

### 源码三段：什么时候扩、扩多少、在哪扩

```java
// 1) 入口：每次 add 都先"预判"，而不是等溢出
public boolean add(E e) {
    ensureCapacityInternal(size + 1);   // 传 size+1，即"加完之后需要多大"
    elementData[size++] = e;
    return true;
}

// 2) 首次 add 才真正分配 10，默认构造器给的是空数组
private static final Object[] DEFAULTCAPACITY_EMPTY_ELEMENTDATA = {};
private void ensureCapacityInternal(int minCapacity) {
    if (elementData == DEFAULTCAPACITY_EMPTY_ELEMENTDATA) {
        minCapacity = Math.max(DEFAULT_CAPACITY, minCapacity);   // 10
    }
    ensureExplicitCapacity(minCapacity);
}

private void ensureExplicitCapacity(int minCapacity) {
    modCount++;                                  // fail-fast 计数器，就在这里 +1
    if (minCapacity - elementData.length > 0)    // 只有真不够才扩
        grow(minCapacity);
}

// 3) 扩容本体：1.5 倍；若还不够（比如 addAll 一大批）就直接给够
private void grow(int minCapacity) {
    int oldCapacity = elementData.length;
    int newCapacity = oldCapacity + (oldCapacity >> 1);      // ★ 1.5 倍
    if (newCapacity - minCapacity < 0)
        newCapacity = minCapacity;                            // 保底：一次满足
    if (newCapacity - MAX_ARRAY_SIZE > 0)
        newCapacity = hugeCapacity(minCapacity);              // 上限 Integer.MAX_VALUE - 8
    elementData = Arrays.copyOf(elementData, newCapacity);    // 整段拷贝
}
```

注意 `modCount++` 的位置：它不在 `grow()` 里，而在 `ensureExplicitCapacity()` 里——也就是说**每次 add 都会 +1，哪怕没扩容**。这正是 fail-fast 能检测"结构性修改"的机制：add/remove 一律 +1，`set` 这类不改变结构的操作不加。

### 预设容量到底能省多少

```java
// 场景：已知要装 100 万个元素
List<String> a = new ArrayList<>();                    // 默认 10 起步
List<String> b = new ArrayList<>(1_000_000);           // 预设

// a 的扩容次数：10 * 1.5^k >= 1e6  →  k ≈ 29 次
// a 的累计拷贝：约 2 * 1e6 = 200 万次引用拷贝 = 8MB 内存搬运
// a 的累计申请：约 300 万个槽位 = 12MB 峰值占用（含旧数组未及回收）
// 额外成本：29 次大数组分配 → 29 次潜在的 YGC
```

预设容量的价值不在"省拷贝字节"（8MB 搬运只要几毫秒），而在**避免那 29 次大对象分配引发的 GC 和复制停顿抖动**。小集合上写 `new ArrayList<>(16)` 纯属自我安慰。

### 数组 vs 链表：差的是缓存行，不是复杂度

```
ArrayList（连续内存）
  ┌────┬────┬────┬────┬────┬────┬────┬────┐
  │ e0 │ e1 │ e2 │ e3 │ e4 │ e5 │ e6 │ e7 │   ← 一个 64B 缓存行覆盖 16 个引用
  └────┴────┴────┴────┴────┴────┴────┴────┘
  第 i+1 个：地址 = base + (i+1)×4，硬件预取器接力
  1000 次访问 ≈ 2 次真实内存读取 + 998 次 L1 命中

LinkedList（堆上散落）
   [Node0] ──────► [Node1]
      │                │
      ▼                ▼
   [Node2] ──────► [Node3]
      │
      ▼
   [Node4]
  第 i+1 个：必须先读 Node_i.next 才知道去哪读
  1000 次访问 ≈ 1000 次潜在 L2/内存未命中，约 10~50 微秒
  每个节点 24B 里只有 4B 是数据
```

这就是为什么现代硬件下"链表在中规模数据上全面劣于数组"。同样的道理适用于 `LinkedList` 当队列、`HashMap` 的链表桶（所以 JDK 8 树化其实也是在缓解长链的访问退化）、以及所有"跳表 vs B 树"的争论。

> **一句话答法**：ArrayList 扩容是 `old + (old >> 1)`，即 1.5 倍下取整；扩容时 `Arrays.copyOf` 整段搬移，累计拷贝约 2N，均摊 O(1)。1.5 而非 2 倍是为了把空间浪费上限压到 1/3。LinkedList 每个节点 24 字节、访问要跳指针，在缓存友好的现代 CPU 上几乎全场景落后。

---

## L2 HashMap 三个常数：8 / 6 / 64 为什么互不相等

第 01 期讲过寻址与扰动，这里只深挖一件事：**为什么树化阈值是 8、退化阈值是 6、而树化前还要求容量至少 64**。

### 8 与 6：这不是两个阈值，是一个"回差"

```
  ┌──────────────┐                          ┌──────────────┐
  │   链表 bin    │  ── binCount ≥ 8 ─────►  │  红黑树 bin   │
  │  查找 O(k)    │      且 table ≥ 64       │  查找 O(log k)│
  │  k 通常 0~2   │                          │  TreeNode 56B │
  └──────────────┘  ◄──── binCount ≤ 6 ───  └──────────────┘
                     （7 = 缓冲带，不上不下）
```

如果两边都用 8：一个桶在 7、8 之间反复抖动，每次都重建整棵树，CPU 全烧在结构变形上。留出 7 这一格，任何一次切换后都要再走过一整格才会反向切换，抖动被彻底掐死。

### 8 的来历：泊松分布，不是经验值

在负载因子 0.75、hash 充分离散的前提下，一个桶内落入 k 个节点的概率服从 λ=0.5 的泊松分布：

| 桶内节点数 k | 概率 | 相当于 | 含义 |
|---|---|---|---|
| 0 | 0.60653066 | 60.7% | 绝大多数桶是空的 |
| 1 | 0.30326533 | 30.3% | 一个桶一个节点，正常态 |
| 2 | 0.07581633 | 7.58% | 偶尔碰撞 |
| 3 | 0.01263606 | 1.26% | 开始不常见 |
| 4 | 0.00157952 | 0.158% | 约 1/633 |
| 6 | 0.00001316 | 0.0013% | 约 1/76000 |
| 7 | 0.00000094 | 0.00009% | 约 1/106 万 |
| **8** | **0.00000006** | **0.000006%** | **约 1/1670 万，比中彩票还难** |

阈值取 8 的真实含义是：**正常情况下你永远不会触发树化**。树化不是为了性能优化，而是为了防御——防御有人构造大量 hash 相同的 key（hash flooding 攻击），或者 key 的 `hashCode()` 写得极烂（比如只返回 `id % 10`），把链表拖成 O(n)。这时红黑树把最坏情况从 O(n) 拉回 O(log n)。

### 64：树化之前先扩容，因为"桶太少"比"链太长"更好治

```java
final void treeifyBin(Node<K,V>[] tab, int hash) {
    int n, index;
    if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY)   // ★ 64
        resize();                    // 表太小：先扩容，而不是树化
    else if ((index = (n - 1) & hash) >= 0) {
        // 容量够了，才真正把这条链表转成双向链 + 红黑树
    }
}
```

逻辑：16 个桶里出现 8 个节点的链表，根因是**桶不够**而不是 hash 不好。扩到 32、64 之后同一批 key 会被分散到不同桶，碰撞自然消失。而树化的代价是每个节点从 32B 涨到约 56B，还要维护红黑树平衡——能扩容解决就别动结构。

### 串成完整判断链

```
put(k, v)
 └─ 桶为空 → 直接放 Node（CAS in CHM）
 └─ 桶非空 → 沿链比较 hash + equals
      └─ 命中 → 覆盖 value
      └─ 未命中 → 尾插新 Node，binCount++
           └─ binCount >= 7（即第 8 个节点）
                ├─ table.length < 64  → resize()     先扩容
                └─ table.length >= 64 → treeifyBin() 转红黑树
```

**最容易被问倒的一问**："既然树化那么难触发，为什么还要写这段代码？"

因为**攻击者可以让它必然触发**。只要提交一批 `hashCode()` 全部相同的字符串作为参数（HTTP 表单、JSON 字段名都行），HashMap 就会退化成一个 O(n²) 的链表，几百 KB 的请求就能打满一个 CPU 核。这就是 2011 年爆出的 HashDoS，也是 JDK 8 引入红黑树的直接原因。

> **一句话答法**：8 是泊松分布下"几乎不可能自然出现"的节点数（概率 6e-8），树化是为了防 hash 碰撞攻击；6 是退化阈值，留出 7 作为防抖缓冲；64 是因为小表碰撞的根因是桶太少，先扩容比树化更划算。

---

## L3 LinkedHashMap：LRU 的三行实现，以及它为什么不该上生产

### 它只是在 HashMap 的节点上多挂了两根指针

`LinkedHashMap` 继承 `HashMap`，一个字都没改哈希逻辑。它把每个 `Node` 换成 `Entry`，在原有 `next`（桶内链表）之外，额外加 `before` / `after` 两根指针，串成一条**贯穿全表的双向链**。于是同一份数据有了两条链：桶链管查找，全局链管顺序。

| 节点类型 | 结构 | 大小（压缩指针） | 说明 |
|---|---|---|---|
| `Node` | hash + key + value + next | 32B | 12B 头 + 4×5，补齐到 8 的倍数 |
| `TreeNode` | 再加 parent/left/right/prev + red | 约 56B | 树化后内存翻 1.75 倍 |
| `LinkedHashMap.Entry` | Node + before + after | 40B | 多 8B，换一条全局有序链 |

### accessOrder 一个开关，决定它是"有序 Map"还是"LRU"

```
访问 C 之前：  head [A] → [B] → [C] → [D] tail
                              ↑ get("C")
访问 C 之后：  head [A] → [B] → [D] → [C] tail

put 新元素时若 removeEldestEntry 返回 true，就删 head（最旧的那个）——
所以 head 一侧是"最久未使用"，tail 一侧是"最近使用"。
```

### 三行代码的 LRU 缓存

```java
public class LruCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;

    public LruCache(int capacity) {
        super(capacity, 0.75f, true);   // ★ 第三个参数 accessOrder = true
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity;       // ★ 超了就删 head
    }
}

// 用法
LruCache<String, String> cache = new LruCache<>(3);
cache.put("a", "1"); cache.put("b", "2"); cache.put("c", "3");
cache.get("a");          // a 变成最新
cache.put("d", "4");     // 淘汰 b（此时 b 是最久未使用）
```

能这么写，是因为 `HashMap` 在三个位置埋了钩子，`LinkedHashMap` 正好全部重写：

| 钩子方法 | 被谁调用 | LinkedHashMap 重写后做什么 |
|---|---|---|
| `afterNodeAccess(e)` | `get` / `getOrDefault` 命中时 | accessOrder=true 时把 e 移到链尾 |
| `afterNodeInsertion(evict)` | `put` 新增节点后 | 调 `removeEldestEntry`，true 就删 head |
| `afterNodeRemoval(e)` | `remove` 时 | 把 e 从双向链上摘掉 |

这三个钩子在 `HashMap` 里是空方法（`void afterNodeAccess(Node<K,V> p) { }`），设计目的是给子类扩展。能说出"模板方法模式 + 空钩子"，这一问就从"背过"跳到"读过设计"。

### 但生产上别用它：三个致命缺陷

- **不是线程安全的。** 要加锁就退化成串行，高并发下等于没缓存。
- **没有过期时间。** LRU 只按"使用顺序"淘汰，一个 10 分钟前写过再没人碰的 key 会一直占位，直到被挤出去。
- **LRU 本身会被"扫描污染"。** 一次全表扫描（比如后台导出）会把所有热数据挤出去，之后缓存全 miss，数据库瞬间被打穿。这个问题有专名：*cache pollution by scan*。

生产答案是 Caffeine：W-TinyLFU——小的 LRU 窗口区 + SLRU 主区，中间用 Count-Min Sketch 记录访问频率做准入。新来的"一次性 key"进不了主区，扫描不会污染热数据，命中率通常比纯 LRU 高 5~15 个百分点。

### 如果面试官说"不许用 LinkedHashMap"

那就手写 `HashMap + 双向链表`，关键是**两个哨兵节点**（head/tail 都指向 dummy），所有增删都不用判空：

```java
class LRUCache {
    class Node { int k, v; Node prev, next; Node(int k, int v) { this.k = k; this.v = v; } }

    private final Map<Integer, Node> map = new HashMap<>();
    private final Node head = new Node(0, 0), tail = new Node(0, 0);  // 哨兵
    private final int cap;

    LRUCache(int cap) { this.cap = cap; head.next = tail; tail.prev = head; }

    public int get(int k) {
        Node n = map.get(k);
        if (n == null) return -1;
        moveToTail(n);              // ★ 访问即提权
        return n.v;
    }

    public void put(int k, int v) {
        Node n = map.get(k);
        if (n != null) { n.v = v; moveToTail(n); return; }
        if (map.size() == cap) {    // 满了，先淘汰 head.next
            Node old = head.next;
            remove(old);
            map.remove(old.k);      // ★ 别忘了从 map 里也删
        }
        n = new Node(k, v);
        map.put(k, n);
        addToTail(n);
    }

    private void remove(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }
    private void addToTail(Node n) {
        n.prev = tail.prev; n.next = tail;
        tail.prev.next = n; tail.prev = n;
    }
    private void moveToTail(Node n) { remove(n); addToTail(n); }
}
```

手写时最常见的三个 bug：① 淘汰时忘了 `map.remove(old.k)`，map 里留着幽灵节点，size 永远不降；② `put` 已存在的 key 时忘了 `moveToTail`，导致它被误判为最旧；③ 不用哨兵，head/tail 判空写崩。这三个点面试官会逐一看。

> **一句话答法**：LinkedHashMap 在 HashMap 节点上多挂 before/after 两根指针串成全局双向链，accessOrder=true 时 get 会把节点移到链尾，再配 removeEldestEntry 就能三行实现 LRU。生产不用它，因为无锁、无 TTL、且 LRU 会被扫描污染，实际用 Caffeine 的 W-TinyLFU。

---

## L4 fail-fast：它只是一个"尽力而为的报警器"

### 机制只有两个字段

`modCount` 记录结构性修改次数（增、删、扩容），迭代器创建时把它抄进 `expectedModCount`，之后每次 `next()` / `remove()` 都对一下，不相等就抛 `ConcurrentModificationException`。

```java
final void checkForComodification() {
    if (modCount != expectedModCount)
        throw new ConcurrentModificationException();
}

public E next() {
    checkForComodification();     // ★ 只在 next 时检查，不是每次修改都检查
    ...
}
```

三个必须知道的边界：

1. **单线程也会抛**——只要是"迭代中结构被改"，跟线程无关，名字里的 Concurrent 是误导。
2. **多线程不一定抛**——两个线程改完都恰好把 modCount 加回去（一增一删），迭代器看到的值相等，于是**静默错乱**。这才是最危险的：不报错，但你遍历出了重复元素或漏元素。
3. **它没有内存可见性保证**——modCount 不是 volatile，另一个线程的修改可能压根看不到。

### 三种必炸写法，和三种正确写法

```
✗ ① for-each 里 remove（最常见的生产事故）
✗ ② 拿到 subList 后又修改原 list
✗ ③ 一个线程遍历、另一个线程 add
✓ ① iterator.remove() / removeIf()
✓ ② 先 subList(...).clear() 或 copyOf 出新 List
✓ ③ 换 CopyOnWriteArrayList / 外部加锁

共同本质：遍历期间"数组长度"或"元素位置"被改了，游标全部错位。
CME 是"及时报错"，不是"安全"，静默错乱才是真事故。
```

```java
// ✗ 必炸：for-each 本质是 Iterator，remove 走的是 ArrayList.remove
for (String s : list) {
    if (s.isEmpty()) list.remove(s);       // CME
}

// ✓ 写法一：显式迭代器（Iterator.remove 会同步 expectedModCount）
Iterator<String> it = list.iterator();
while (it.hasNext()) {
    if (it.next().isEmpty()) it.remove();
}

// ✓ 写法二：removeIf（JDK 8+，内部就是上一种，但更短且不会写错）
list.removeIf(String::isEmpty);

// ✓ 写法三：倒序下标（删元素只影响后面的，前面下标不漂移）
for (int i = list.size() - 1; i >= 0; i--) {
    if (list.get(i).isEmpty()) list.remove(i);
}

// ✗ 常见误解：以为删一个就 break 就安全
for (String s : list) {
    if (s.isEmpty()) { list.remove(s); break; }  // 不抛，但只删第一个，逻辑错
}
```

### subList 是视图，这个坑比 CME 更隐蔽

```java
List<String> list = new ArrayList<>(List.of("a", "b", "c", "d"));
List<String> sub = list.subList(1, 3);     // ["b","c"]，不复制，只是"窗口"
sub.set(0, "B");                           // ★ 原 list 变成 ["a","B","c","d"]
list.add("e");                             // 原 list 结构性修改
sub.get(0);                                // ✗ ConcurrentModificationException

// ✓ 要脱离关系，就复制一份
List<String> copy = new ArrayList<>(list.subList(1, 3));
// ✓ JDK 10+ 更直白
List<String> copy2 = List.copyOf(list.subList(1, 3));
```

还有一个内存泄漏变体：`subList` 内部持有 `parent` 引用和 `offset`。如果从一个 100 万元素的 list 里 `subList(0, 10)` 出来丢进缓存，这 100 万元素的数组**一个都回收不掉**。在 dump 里表现为"一个只有 10 个元素的 list 却引用了 8MB 数组"，非常反直觉，但排查极快——看 `ArrayList$SubList.parent` 就行。

### Collections.synchronizedList 也不能救你

```java
List<String> sync = Collections.synchronizedList(new ArrayList<>());
sync.add("a");                  // 单个操作安全（整表锁）
for (String s : sync) { ... }   // ✗ 遍历时依然可能 CME！迭代不在锁内

// ✓ 官方文档明确要求手动同步
synchronized (sync) {
    for (String s : sync) { ... }
}
```

原因很简单：`synchronizedList` 只给每个方法加了 `synchronized(this)`，而"遍历"是由几十次 `next()` 组成的**复合操作**，锁不住整体。这也是它被淘汰的原因——复合操作的安全性没法靠单个方法的锁拼出来。

### CopyOnWrite：用"换数组"代替"改数组"

```
写线程（持锁）                    读线程（无锁）
copyOf(old, n+1)        迭代器持有的是 add 之前那次的数组引用
      │                            ▲
      ▼                            │ setArray 原子换引用
新数组 = 旧 + 新元素  ──────────────┘

所以它天然不会 CME，但也读不到写入后的新值——这是"弱一致"的取舍，不是 bug。
代价：每次写复制整个数组，10 万要素写 1 次 = 400KB
适用：读极多、写极少、元素少（监听器表、路由表、白名单）
禁用：写频繁（O(n²)）、元素大、数组长
```

> **一句话答法**：fail-fast 靠 modCount/expectedModCount 比对，只在 next/remove 时检查，所以单线程也会抛、多线程反而可能静默错乱，它只是报警器不是并发控制。正确做法是 iterator.remove / removeIf / 倒序下标；需要并发读就换 COW（读多写极少）或 CHM（Map 场景），synchronizedList 连遍历都要自己加锁。

---

## L5 并发容器：一条"锁粒度"的进化线

并发容器不需要一个个背，它们排在这条轴上：**锁的粒度从"整张表"细到"一个桶"，最后细到"一个指针"**。

```
锁粒度：  整表 ──────────► 1/16 ──────────► 1/桶 ──────────► 单指针
         Hashtable      CHM7 Segment     CHM8 桶首节点     SkipList / COW
         Vector         段内仍一把锁      空桶 CAS 无锁      读完全无锁
         相对吞吐 1×      理想 16×          100×+             写只改局部

内存代价： 低 ───────────────────────────────────────────► 高
结构复杂度：低 ───────────────────────────────────────────► 高
```

### CHM 的 get 为什么完全不用锁

三个 `volatile` 撑起整条无锁读路径：`table` 数组引用、数组元素的读取（`tabAt` 内部是 `Unsafe.getObjectVolatile`）、以及 `Node.val`。写线程即使正在改，读线程最坏也只是读到"旧值"或"null"，不会读到半个对象。

```java
// get 的全部精髓：无锁、无 CAS、两次 volatile 读
public V get(Object key) {
    Node<K,V>[] tab; Node<K,V> e, p; int n, eh; K ek;
    int h = spread(key.hashCode());              // 同 HashMap 的高低位异或
    if ((tab = table) != null && (n = tab.length) > 0 &&
        (e = tabAt(tab, (n - 1) & h)) != null) { // ① volatile 读桶头
        if ((eh = e.hash) == h) {
            if ((ek = e.key) == key || (ek != null && key.equals(ek)))
                return e.val;                    // ② volatile 读 value
        }
        else if (eh < 0)                         // 树节点或正在扩容
            return (p = e.find(h, key)) != null ? p.val : null;
        while ((e = e.next) != null) { ... }      // 遍历链表，全程无锁
    }
    return null;
}
```

### put：三个阶段，只有第二阶段要锁

| 阶段 | 动作 | 同步手段 | 失败/竞争时 |
|---|---|---|---|
| ① 桶为空 | `casTabAt(tab, i, null, new Node(h,k,v))` | CAS，无锁 | CAS 失败 → 进第二阶段 |
| ② 桶非空 | `synchronized (f)` 锁住桶首节点，遍历/尾插/树插 | 桶级互斥锁 | 遇到 `MOVED` → 协助扩容 |
| ③ 计数 | `addCount(1, binCount)` → `baseCount` + `CounterCell[]` | CAS + 分片累加 | 超过 `sizeCtl` 阈值 → 触发扩容 |

这里的 **CounterCell 借鉴了 LongAdder**：热点计数如果只用单个 `AtomicLong`，几百个线程会挤在同一个 cache line 上自旋失败（伪共享 + CAS 冲突）。CHM 把它拆成一圈单元格，线程按 probe 值各写各的，`size()` 时再求和——代价是 `size()` 只是近似值，且不保证原子快照。

**CHM 为什么禁止 null key 和 null value**：因为并发下 `get` 返回 null 有两种含义——键不存在，或键存在但值是 null。HashMap 允许你 `containsKey` 二次确认，但两次调用之间别的线程可能已经改了这个桶，**你无法得到确定答案**。所以 Doug Lea 直接禁掉 null，让"null 就代表不存在"这个语义永远成立。

### 为什么没有 ConcurrentTreeMap

红黑树的插入需要**旋转**——一次旋转会连着改父、子、叔、祖父四个节点的指针，还可能要沿树回溯着色。这些操作跨越多个节点，无法用一条 CAS 原子完成；如果用锁，就得锁住整条旋转路径，粒度立刻退化成"接近全表锁"。

跳表（SkipList）恰好相反：插入只是在每一层改**相邻两个指针**，每一层都可以独立 CAS。牺牲一点空间（平均每个元素约 2 个额外指针），换来天然的并发友好。`ConcurrentSkipListMap` 是 JDK 里唯一提供并发有序语义的 Map。

> **一句话答法**：并发容器的演进就是锁粒度演进：Hashtable 锁整表 → CHM7 分 16 段 → CHM8 桶级 synchronized + 空桶 CAS，get 靠三个 volatile 完全无锁；size 用 baseCount+CounterCell 分片累加所以是近似值；有序并发只能用跳表，因为红黑树旋转跨多节点，CAS 化不了。

---

## L6 七种 BlockingQueue：线程池排队的七种死法

线程池的拒绝策略、最大线程数、甚至"为什么我的线程池把内存吃满了"，答案几乎都在队列上。七种 BlockingQueue 的区别只有三个维度：**有没有容量上限、底层是数组还是链表、用几把锁**。

| 队列 | 容量 | 结构 / 锁 | 适用与坑 |
|---|---|---|---|
| `SynchronousQueue` | **0**（直接交接） | 公平=TransferQueue，非公平=Stack | 不存元素，put 必须等到有 take；配 CachedThreadPool 会让线程无限涨 |
| `ArrayBlockingQueue` | 有界（必须给） | 环形数组 · **一把** ReentrantLock + 两个 Condition | **生产首选**：容量可控、内存连续 |
| `LinkedBlockingQueue` | 默认 `Integer.MAX_VALUE` | 链表 · **两把**锁（putLock/takeLock）+ AtomicInteger count | 入队出队可并行；默认等于无界，是最大的坑 |
| `LinkedBlockingDeque` | 可选 | 双向链表 · 一把锁两个 Condition | 支持 push/pop 当栈用；fork-join 常用 |
| `PriorityBlockingQueue` | 无界（可逻辑有界） | 二叉堆（数组）· 一把锁 | 出队按优先级；同优先级不保证 FIFO |
| `DelayQueue` | 无界 | PriorityQueue + 延迟到期才可取 | **订单 30 分钟超时关闭的标准答案** |
| `LinkedTransferQueue` | 无界 | 链表 · `transfer()` 阻塞到消费者真正取走 | 性能优于 LinkedBlockingQueue；Netty/Dubbo 用过 |

### 无界队列的两条死路

```java
// ✗ Executors.newFixedThreadPool 的内部实现
new ThreadPoolExecutor(n, n, 0L, MILLISECONDS,
        new LinkedBlockingQueue<Runnable>());   // 默认容量 Integer.MAX_VALUE

// 后果 1：maximumPoolSize 永远不生效
//   队列永不装满 → 永远不会创建第 n+1 个线程 → 任务全在队列里排着

// 后果 2：任务堆积 → GC 压力 → 最终 OutOfMemoryError: Java heap space
//   一个 Runnable 闭包按 200B 算，100 万个就是 200MB，还没算它引用的对象
```

把它算成时间更直观。用 Little's Law：`L = λ × W`，队列里积压的任务数等于到达速率乘以排队时间。

| 场景 | QPS（λ） | 单任务耗时 | 积压 L | 排队等待 W = L/λ |
|---|---|---|---|---|
| 正常 | 100 | 50ms（8 线程刚好） | ≈ 0 | ≈ 0 |
| 下游变慢一倍 | 100 | 100ms | 持续增长 | 无上限，直到 OOM |
| 有界队列 1000 | 100 | 100ms | 封顶 1000 | **10 秒**后开始拒绝 |

第三行的重点：有界队列的价值不是"让任务不丢"，而是**把"无限排队直到崩"变成"10 秒后明确拒绝"**。拒绝是可控的降级，排队到 OOM 是不可控的雪崩。

### 正确姿势：四件套一起定，缺一不可

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    8,                                   // corePoolSize：常驻
    32,                                  // maxPoolSize：峰值
    60L, TimeUnit.SECONDS,               // 空闲回收
    new ArrayBlockingQueue<>(1000),       // ★ 有界队列，不写就是坑
    new ThreadFactoryBuilder().setNameFormat("order-pool-%d").build(),  // ★ 命名救命
    new ThreadPoolExecutor.CallerRunsPolicy()   // ★ 拒绝时调用线程自己跑，天然反压
);

// CallerRunsPolicy 的精妙：任务丢回给提交者（如 Tomcat 的 worker 线程），
// 提交者被拖慢 → 上游请求堆积 → 负载均衡摘流 → 系统整体降速而不是崩掉。
// 这叫"背压（backpressure）"，是拒绝策略里唯一带自我保护的。
```

线程数不是拍脑袋：CPU 密集 ≈ 核数 + 1；IO 密集 ≈ 核数 × (1 + 等待时间/计算时间)。8 核、单任务 90% 在等下游（等待/计算 = 9），那 8 × 10 = 80 个线程才吃满。但线程一多，上下文切换和栈内存（默认 1MB/线程）又成新瓶颈——80 个线程就是 80MB 栈空间，容器里要命。

> **一句话答法**：线程池选队列先看容量：无界队列会让 maximumPoolSize 失效并最终 OOM；有界队列把无限排队转成明确拒绝；SynchronousQueue 配 CachedThreadPool 会让线程无限增长。生产用 ArrayBlockingQueue + CallerRunsPolicy 做背压。

---
## L7 三种"只读"：视图、包装、真不可变

这三个东西都让 `add` 抛异常，但它们的**内存关系完全不同**。分不清就会在生产上遇到"我明明没改这个 list，它的内容却变了"。

```
① Arrays.asList(arr) —— 固定长度视图
   [Arrays$ArrayList] ──► a[]（原数组，共用）
   set 可以 → 直接写原数组；add/remove 抛 UnsupportedOperationException

② Collections.unmodifiableList(list) —— 只读代理
   [UnmodifiableList] ──► 原 list（内部仍可变）
   你改不了，但别人改原 list → 你看到的也变

③ List.of(...) —— 真不可变（JDK 9+）
   [ImmutableCollections] ──► 内部私有副本（入参被复制）
   外部改不动它，它也改不动外部；禁 null

判断标准只有一句：它有没有自己的底层存储？有 → 副本；没有 → 视图。
```

### Arrays.asList 的四个坑

```java
// 坑 1：基本类型数组 → 得到的是"一个元素"的 list
int[] arr = {1, 2, 3};
List<int[]> bad = Arrays.asList(arr);      // size = 1，元素是 int[] 本身
Integer[] boxed = {1, 2, 3};
List<Integer> ok = Arrays.asList(boxed);   // size = 3
// 因为签名是 asList(T... a)，int[] 被当成一个 T

// 坑 2：固定长度，add/remove 直接炸
List<String> l = Arrays.asList("a", "b");
l.set(0, "A");        // ✓ 可以，写回原数组
l.add("c");           // ✗ UnsupportedOperationException

// 坑 3：它和原数组共用存储
String[] src = {"a", "b"};
List<String> view = Arrays.asList(src);
src[0] = "changed";
System.out.println(view.get(0));            // "changed"

// 坑 4：想要真 List，套一层
List<String> real = new ArrayList<>(Arrays.asList("a", "b"));
real.add("c");                               // ✓
```

### 不可变集合的真正价值：不是"防改"，是"免锁"

- **天然线程安全**：状态不可变就没有可见性问题，跨线程随便传，不需要任何同步。
- **可安全共享与缓存**：返回给调用方不用防御性拷贝（`new ArrayList<>(x)` 那一步可以省）。
- **hashCode 可以缓存**：不可变对象的 hash 只算一次，做 Map 的 key 最理想。
- **JDK 做了紧凑优化**：`List.of()` 空集合是单例，`List.of(a)` 是一个只有 value 字段的对象，比 `ArrayList`（对象 + 数组）省一半内存。

API 设计上的铁律：**方法入参可以接受可变集合，方法返回值必须返回不可变集合或副本**。`return Collections.unmodifiableList(this.items)` 看似安全，但如果这个 `items` 之后被内部修改，调用方手里的"只读视图"内容会变——正确写法是 `return List.copyOf(this.items)`，一次性复制断开关系。

> **一句话答法**：Arrays.asList 是原数组的固定长度视图（可 set 不可增删，基本类型数组会退化成单元素）；unmodifiableList 是只读代理，底层仍可变所以不安全；List.of 才是 JDK 9 的真不可变，复制存储、禁 null、内存更紧凑。判断视图还是副本，就看它有没有自己的底层数组。

---

## L8 比较器契约：为什么数组小于 32 时不报错，上线三个月后才炸

### 契约只有三条

- **自反**：`compare(a, a) == 0`
- **反对称**：`compare(a, b)` 与 `compare(b, a)` 符号相反
- **传递**：`a < b` 且 `b < c`，则必有 `a < c`

```
传递性被破坏 → 排序陷入"环"            为什么小数组检测不出来
        (a)                            n < 32  → binarySort（二分插入）
       ／   ＼                                    只做两两比较，不校验契约
  a<b /     \ c<a                     n ≥ 32  → 进入归并，检查 run 单调性
     ／       ＼                                发现"环" → IllegalArgumentException
   (b) ────── (c)
        b<c                            所以单元测试放 5 条数据永远测不出来，
三个都成立，排序就不存在唯一解。        生产数据一多立刻 500。
```

### 三种典型错误写法

```java
// ✗ 全部会破坏契约
Comparator<User> bad1 = (a, b) -> a.getAge() - b.getAge();             // 整数溢出
Comparator<User> bad2 = (a, b) -> (int)(a.getScore() - b.getScore());  // 浮点截断
Comparator<User> bad3 = (a, b) -> a.getName().length() - b.getName().length()
                              == 0 ? 0 : (a.getId() > b.getId() ? 1 : -1); // 传递性断裂

// ✓ 用比较函数，不要自己算差
Comparator<User> ok1 = Comparator.comparingInt(User::getAge);
Comparator<User> ok2 = Comparator.comparingDouble(User::getScore);
Comparator<User> ok3 = Comparator.comparingInt((User u) -> u.getName().length())
                                 .thenComparingLong(User::getId);   // 链式，天然传递
Comparator<User> ok4 = Comparator.nullsFirst(Comparator.comparing(User::getAge));
```

溢出的经典触发点：当 `a.age = -2147483648`、`b.age = 1` 时，相减溢出成正整数，a 被判定大于 b，整个序就乱了。

### 比溢出更阴的：compareTo 与 equals 不一致

```java
Set<BigDecimal> set = new TreeSet<>();
set.add(new BigDecimal("1.0"));
set.add(new BigDecimal("1.00"));
System.out.println(set.size());     // 1 ！因为 compareTo 认为它们相等

Set<BigDecimal> hash = new HashSet<>();
hash.add(new BigDecimal("1.0"));
hash.add(new BigDecimal("1.00"));
System.out.println(hash.size());    // 2
```

`TreeSet` / `TreeMap` 判重走的是 `compareTo() == 0`，不是 `equals()`。JDK 文档对 `Comparable` 的建议是"强烈推荐与 equals 保持一致"，但 BigDecimal 故意不一致（`1.0` 和 `1.00` 精度语义不同）。这就是为什么金额字段丢进 `TreeSet` 会"莫名其妙少一条"。

**稳定性顺带一问**：`Collections.sort` / `List.sort` 用 TimSort，**稳定**（相等元素保持原序）；`Arrays.sort(int[])` 用双轴快排，**不稳定**。所以"先按时间排、再按状态排"这种多级排序，必须从后往前逐级排，依赖的就是稳定性。

> **一句话答法**：Comparator 必须满足自反、反对称、传递；用 `a-b` 会整数溢出、浮点截断，必须用 `Integer.compare` / `Comparator.comparingXxx`。TimSort 只在 n≥32 时才做归并检查，所以小数据测试全绿、生产数据必炸。TreeSet 用 compareTo 判重，与 equals 不一致的类型会丢元素。

---

## L9 内存账本：100 万条数据到底占多少

"100 万条数据放内存要多少"是集合类里最能拉开差距的一问，它逼你把**对象头、引用宽度、对齐填充、装载因子**四件事同时算清楚。

```
100 万个 entry（key/value 本身不计）· 压缩指针开启（引用 4B）

HashMap        [████████ Node 32MB ████████][table 8MB]           ≈ 40MB
LinkedHashMap  [██████████ Entry 40MB ██████████][table 8MB]       48MB
TreeMap        [██████████ Entry 40MB ██████████]（无数组）        40MB
CHM            [████████ Node 32MB ████████][table 8MB]           ≈ 40MB

单条 entry 的 32B 怎么来的：
  12B 对象头 + 4B hash + 4B key 引用 + 4B value 引用 + 4B next = 28B
  再按 8 字节对齐补齐 → 32B

table 长度 = 2^21 ≈ 209 万槽位（100 万 / 0.75 向上取 2 的幂）× 4B = 8MB
```

### 把账算完整的四步

1. **单个 entry 多大**：HashMap.Node 32B、LinkedHashMap.Entry 40B、TreeNode 56B、TreeMap.Entry 40B。
2. **数组多大**：容量 = 向上取到 2 的幂（1,000,000 / 0.75 = 1,333,333 → 2²¹ = 2,097,152），乘 4B = 8MB，再加 16B 数组头。
3. **key/value 本身多大**：最容易被忽略的一项。一个 `Long` 是 24B（12B 头 + 8B long + 对齐），一个 10 字符 `String` 约 56B（对象 24B + byte[] 32B）。
4. **GC 与碎片**：100 万个独立对象意味着 100 万个标记位、100 万次指针追踪，堆上实际占用往往比理论值高 10~20%。

```java
// 常被忽略的账：List<Integer> 放 100 万个 int
List<Integer> list = new ArrayList<>();
for (int i = 0; i < 1_000_000; i++) list.add(i);

// 引用数组：1M × 4B = 4MB（容量按 1.5 倍推到 1,091,000 左右 → 4.4MB）
// Integer 对象：1M × 16B = 16MB   ← 12B 头 + 4B int，正好 16B
// 合计 ≈ 20MB

// 换成 int[]
int[] arr = new int[1_000_000];       // 4MB
// 差 5 倍。这就是为什么"数值集合"必须考虑原始类型专用容器。
```

**Integer 缓存陷阱**：`-128 ~ 127` 之间的 Integer 走缓存池，所以 `Integer a = 100, b = 100; a == b` 为 true；`1000 == 1000` 为 false。范围可用 `-XX:AutoBoxCacheMax` 调整上界（下界固定 -128）。用 `==` 比较包装类型是集合类代码里最常见的一类 bug。

> **一句话答法**：HashMap 存 100 万条，纯结构约 40MB（Node 32MB + table 8MB），LinkedHashMap 48MB，TreeNode 树化后能到 56MB+。算法是：单个 entry 字节数 × 条数 + 2 的幂容量 × 4B。真正的内存大头是 key/value 对象本身和 GC 的对象头成本。

---

## L10 速查：十二个数字记住整个集合框架

| 数字 | 含义 | 答不出来的后果 |
|---|---|---|
| 1.5 | ArrayList 扩容因子（old + old>>1） | 说不出为什么不是 2 倍 |
| 10 | ArrayList 默认容量，且首次 add 才分配 | 以为构造器就分配了 |
| 2 | ArrayList 累计拷贝量 ≈ 2N | 说"扩容是 O(1)"却讲不出均摊来源 |
| 0.75 | HashMap 负载因子 | 说不清空间与冲突的折中 |
| 8 / 6 / 64 | 树化 / 退化 / 最小树化容量 | 把 8 说成"经验值" |
| 6e-8 | 桶内 8 个节点的泊松概率 | 不知道树化是防攻击不是优化 |
| 32B | HashMap.Node 大小 | 算不出 100 万条占多少内存 |
| 16 / 32 | CHM7 的 Segment 数 / TimSort 检测阈值 | 混淆两个"16" |
| 12B | 对象头（压缩指针下） | 所有内存计算从第一步就错 |
| 4B | 压缩后的引用宽度 | 以为引用是 8B，账翻倍 |
| 7 种 | BlockingQueue 实现 | 只会说 Array 和 Linked 两种 |
| 3 层 | Arrays.asList / unmodifiable / List.of | 分不清视图和副本 |

### 十四问速答

**Q1：ArrayList 扩容为什么是 1.5 倍，不是 2 倍？**

几何增长因子 g 下累计拷贝量约 N/(g−1)，1.5 倍是 2N、2 倍是 N，所以 2 倍在拷贝上更省；但空间浪费上限分别是 1/3 和 1/2。ArrayList 选了省内存。另外 1.5 可以用 `old + (old >> 1)` 写成位移，比乘法便宜——虽然这不是主因。

**Q2：ArrayList 和 LinkedList 到底怎么选？**

95% 场景用 ArrayList。LinkedList 的"增删快"只在**已持有节点位置**时成立，否则定位 O(n) 且每步缓存未命中，实测比 ArrayList 的 memmove 慢。栈和队列用 ArrayDeque。LinkedList 每个节点 24B，还有 GC 压力。

**Q3：HashMap 为什么容量必须是 2 的幂？**

为了让 `(n-1) & hash` 等价于 `hash % n`。只有 n 是 2 的幂时，n-1 的二进制才全是 1，位与才能均匀映射到 `[0, n-1]`。若 n 不是 2 的幂，某些桶永远取不到，冲突率飙升。构造器传 10 也会被 `tableSizeFor` 拉到 16。

**Q4：树化阈值 8 和退化阈值 6 为什么不一样？**

8 来自泊松分布（λ=0.5），概率 6e-8，正常永不触发，树化是防 hash 攻击的兜底。6 是退化阈值，中间留 7 做迟滞带，避免在 7/8 之间反复建树拆树。

**Q5：HashMap 线程不安全，具体会坏成什么样？**

JDK 7 头插法 + 并发扩容会形成**环形链表**，之后 get 死循环，CPU 100%。JDK 8 改成尾插，死循环没了，但仍然会丢数据（两个线程同时 put 到同一个空桶，后写的覆盖先写的）、size 不准、扩容时读到中间态。所以"JDK 8 的 HashMap 线程安全了"是错的。

**Q6：ConcurrentHashMap 的 get 为什么完全不加锁？**

靠三个 volatile：table 数组引用、桶头节点（tabAt 用 Unsafe 的 volatile 读）、Node.val。写线程即使正在改，读线程最多读到旧值，绝不会读到"半个对象"（引用赋值本身原子）。典型的"用可见性换互斥"。

**Q7：CHM 的 size() 为什么是近似值？**

计数拆成了 `baseCount` + `CounterCell[]`（LongAdder 思路），线程各写各的单元格避免 CAS 冲突。求和时各格子还在变，所以是近似快照。要精确值只能 `mappingCount()`（返回 long），且依然不保证并发准确。

**Q8：CHM 为什么不允许 null？**

并发下 get 返回 null 无法区分"不存在"和"值为 null"，而 containsKey 二次确认在两次调用之间可能已被别的线程改变，逻辑上无法给出确定答案。禁掉 null 让"null 即不存在"永远成立，顺带堵住 `if (v == null) put(k, v)` 这种非原子写法。

**Q9：为什么没有 ConcurrentTreeMap？**

红黑树插入要旋转，一次旋转跨父、子、叔、祖父多个节点，无法用单条 CAS 完成；加锁又得锁住整条路径，粒度退化成准全表锁。跳表每层只改相邻两个指针，可以逐层 CAS，所以并发有序结构选了跳表。

**Q10：CopyOnWriteArrayList 什么时候能用？**

读极多、写极少、元素少（几十到几百）：监听器列表、白名单、路由表、配置快照。写一次要复制整个数组，1 万元素就是 40KB；写频繁会退化成 O(n²)。而且迭代器拿到的是快照，读不到最新写入——这是设计取舍不是 bug。

**Q11：fail-fast 和 fail-safe 的区别？**

fail-fast（ArrayList/HashMap）用 modCount 比对，检测到并发修改立刻抛 CME，**只是尽力检测**：单线程也会抛，多线程反而可能漏检。fail-safe（COW/CHM）在副本或弱一致视图上遍历，不抛异常但可能读到旧数据。前者是报警器，后者是隔离。

**Q12：LinkedHashMap 怎么实现 LRU？**

构造时 `accessOrder=true`，`get` 命中会触发 `afterNodeAccess` 把节点移到链尾；重写 `removeEldestEntry` 在 `size() > capacity` 时返回 true，`afterNodeInsertion` 就会删掉链头。三行代码，本质是 HashMap 留的三个空钩子。

**Q13：Arrays.asList 和 List.of 有什么区别？**

Arrays.asList 是原数组的**固定长度视图**：可 set（写回原数组）、不可增删、基本类型数组会退化成单元素。List.of 是 JDK 9 的**真不可变**：复制存储、禁 null、内存更紧凑、可安全跨线程共享。

**Q14：100 万个 Integer 放进 List 要多少内存？**

约 20MB：引用数组 4.4MB（1.5 倍扩容后）+ 100 万个 Integer 对象 16MB。换成 `int[]` 只要 4MB，差 5 倍。要点是记得算对象头（12B）和对齐填充，以及装箱对象是独立分配的。

---

## L11 三道模拟面试

### Q1 设计一个本地缓存：容量 1 万、支持 TTL 过期、扛住每秒 10 万次读

**考察点**：LRU 实现 / 过期清理策略 / 并发读性能 / 缓存污染

**及格**：用 `LinkedHashMap(accessOrder=true)` 重写 `removeEldestEntry` 实现 LRU，另外记一个 `Map<K, Long> expireAt` 做 TTL，用 `synchronized` 或 `ConcurrentHashMap` 保线程安全。

**良好**：指出三点问题——① LinkedHashMap 不是线程安全的，加锁后读也串行，10 万 QPS 撑不住；② 惰性过期（get 时判断）会导致冷 key 永久占位，需要配定时清扫线程；③ TTL 和 LRU 是两个淘汰维度，`removeEldestEntry` 只能处理容量，处理不了过期。改进：读路径用 `ConcurrentHashMap`（无锁读），淘汰用分段 + 时间轮，或者直接上 Caffeine。

**优秀**：进一步说明**纯 LRU 会被扫描污染**——一次全表导出会把 1 万个热 key 全挤出去，之后缓存全 miss 打穿 DB。所以要用 W-TinyLFU（窗口 LRU + Count-Min Sketch 准入），新 key 必须先证明自己有访问频率才能进主区。Caffeine 的实现是 1% 窗口区 + 99% SLRU，命中率比纯 LRU 高 5~15 个点。再补容量账：1 万条 × 每条约 100B（含 key/value 和 Node 开销）= 1MB，对内存毫无压力，瓶颈全在锁和 GC，所以"无锁读"比"省内存"重要得多。

---

### Q2 线上服务每隔几天 CPU 打满，线程 dump 显示大量 RUNNABLE 卡在 HashMap.get，怎么排查

**考察点**：HashMap 并发退化 / 死循环 / 哈希碰撞攻击 / 取证手段

**及格**：判断是 HashMap 在多线程下被并发修改，建议换成 ConcurrentHashMap。

**良好**：区分 JDK 版本——JDK 7 头插法 + 并发扩容会形成环形链表，get 沿环无限循环，表现为**单线程 CPU 100% 且栈顶永远在 HashMap.getEntry**；JDK 8 改尾插后死循环消失，但仍有数据丢失、size 不准。取证：`jstack` 连打三次，如果同一个线程栈顶三次都在 `HashMap.get` 的同一行号，基本可确认死循环；配合 `top -Hp` 找到最热线程 ID 转十六进制再对 jstack 里的 nid。

**优秀**：再补两层——① 如果线程栈显示大量线程在同一个桶上自旋，要考虑**哈希碰撞攻击**：攻击者构造大量同 hash 的 key（比如作为请求参数名），把 O(1) 打成 O(n)，几百 KB 请求就能烧掉一个核。这是 HashDoS，也是 JDK 8 引入红黑树的直接动机（桶内 ≥8 且容量 ≥64 就树化，把最坏情况从 O(n) 拉回 O(log n)）。② 定位手段升级：`arthas` 的 `profiler` 火焰图能看到热点集中在 `HashMap.getNode`；`jmap -histo:live` 看是否有个 HashMap 的 size 异常大；用自定义 key 类型加随机化 hash。③ 根治：共享可变状态一律换 CHM；能用不可变集合（`List.of`）就绝不放共享可变集合。

---

### Q3 接口 P99 从 80ms 涨到 3s，监控显示线程池队列持续增长但 CPU 只有 30%

**考察点**：Little's Law / 队列选型 / 线程数与 IO 等待比 / 拒绝策略

**及格**：队列堆积说明消费速度跟不上生产速度，先看下游依赖是不是变慢，再看线程池参数是否合理。

**良好**：用 Little's Law 定量：`L = λ × W`。若 QPS 100、队列积压 300，则排队时间 ≈ 300/100 = 3s——正好对上 P99 的劣化幅度，说明**耗时全花在排队，不在计算**。CPU 只有 30% 也印证了这点：线程都在等 IO，不是在算。然后看线程池：`maximumPoolSize` 是否因为用了无界 `LinkedBlockingQueue` 而永远不生效；线程数是否按 IO 密集公式算过（核数 × (1 + 等待/计算)）。

**优秀**：把因果链讲完整——① 下游某接口 RT 从 50ms 涨到 200ms → 单线程吞吐从 20/s 降到 5/s → 8 个线程只吃 40 QPS，而流量是 100 → 队列每秒净增 60 个 → 队列无限增长（无界）→ 排队时间线性上涨 → P99 崩。② 关键判断：**这是容量问题还是故障问题**？如果下游 RT 会自己恢复，队列是"缓冲"；如果下游已经病态，队列只是在延迟暴露故障，还额外增加内存和超时风险。所以别急着加线程，先确认下游。③ 整改：队列改有界（`ArrayBlockingQueue(1000)`）+ `CallerRunsPolicy` 做背压——提交者被拖慢，上游自然降速，系统整体变慢但不崩。同时加队列深度监控（`getQueue().size()` 打点到 Prometheus），把"事后发现"变成"事前告警"。④ 最后一句加分：线程不是越多越好，80 个线程就是 80MB 栈内存，容器里 `-Xmx` 一共才 2G，这一步很容易把自己 OOM 掉。

---

## 下期预告

**Spring Boot 自动配置与启动流程**——`@SpringBootApplication` 三个注解的真实分工、`spring.factories` 到 `AutoConfiguration.imports` 的迁移、条件装配的 12 个 `@Conditional`、启动耗时 60% 花在哪、以及"为什么加了 starter 就自动生效"这条链路怎么答才不像背书。
