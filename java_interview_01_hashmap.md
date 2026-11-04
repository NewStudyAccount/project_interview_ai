# Java 面试深挖 01 · HashMap → ConcurrentHashMap

> 一条链串起：equals/hashCode 契约 → 位运算寻址 → 链表到红黑树 → 扩容 rehash 的数学 → JDK8 的 CAS + 桶锁并发。
> 口径：默认 JDK8 源码，JDK7 差异单独标注。

| 层级 | 主题 | 难度 | 面试权重 |
|---|---|---|---|
| L0 | equals / hashCode 契约 | ★☆☆ | 高（开场题） |
| L1 | 2 的幂与扰动函数 | ★★☆ | 高 |
| L2 | 树化三个阈值 | ★★★ | 高（区分度） |
| L3 | 扩容拆分 rehash | ★★★ | 高 |
| L4 | HashMap 并发缺陷 / CHM 锁设计 | ★★★★ | 很高（二面） |
| L5 | 高频问答速查 | — | — |
| L6 | 3 道模拟面试 | — | — |

---

## L0 契约层：equals 和 hashCode 是一对，拆开就出事

**一句话答案**：equals 相等的两个对象，hashCode 必须相等；反过来 hashCode 相等，equals 不一定相等。HashMap 先用 hashCode 找桶，再用 equals 在桶里认人——少写一个，等于给了它一张假门牌号。

```java
class Key {
    private final String id;
    Key(String id) { this.id = id; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Key)) return false;
        return id.equals(((Key) o).id);   // 业务上：id 相同即同一个 key
    }
    // 故意不重写 hashCode()
}

Map<Key, String> map = new HashMap<>();
map.put(new Key("A"), "v1");
map.get(new Key("A"));      // null —— 明明 equals 相等
map.put(new Key("A"), "v2");
map.size();                 // 2 —— 出现了两个"相同"的 key
```

原因：Object.hashCode() 默认与对象身份绑定，两个 new Key("A") 是两个对象，hashCode 不同 → 桶下标不同 → 查找时只翻自己的桶，永远看不到另一个。

```
Key("A")  hash=0x1a2b  ──┐
                          ├─ equals 为 true，却落进不同桶
Key("A")  hash=0x7f9c  ──┘
        ↓
table:  [0][1][2:K1][3][4][5:K2][6]...
                    ↑            ↑
                get 找这里     put 落在那里
```

**面试陷阱**：别背"Java 规范要求"。要落到场景——基于哈希的集合用 hashCode 决定落桶、equals 决定桶内认人，只改一半等于改了门牌规则没改门牌号。

**String 为什么是天选 key**：不可变（hash 不会变，不会被"搬家"）；hashCode 结果缓存在私有字段 hash 里，第二次直接返回；算法固定 s[0]*31^(n-1)+...+s[n-1]，31 是奇质数且 31*i == (i<<5)-i 可被 JIT 优化成移位减法。

---

## L1 寻址层：为什么容量非得是 2 的幂

**一句话答案**：n 是 2 的幂时，hash % n 完全等价于 hash & (n-1)，位与更快；更关键的是扩容时只需多看一位即可决定新位置，不用重算哈希。

很多答案只说到"位运算快"，这只对了一半（JIT 早就会优化 % 2^k）。真正的价值在扩容。

```java
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
int index = (n - 1) & hash;      // n = 16 时即 hash & 15
```

**扰动函数的动机**：表长 16 时 n-1 = 0b1111，只用到 hash 的低 4 位，高位全被扔掉。若一批 key 低位规律性强（自增 ID、a*16+b），会全挤一个桶。右移 16 位异或把高 16 位"折"进低位，成本约一个时钟周期。

**数形结合：位与 ≡ 取模（n = 8）**

| hash | 二进制 | hash & 7 | hash % 8 |
|---|---|---|---|
| 13 | 0b1101 | 5 | 5 |
| 21 | 0b10101 | 5 | 5 |
| 36 | 0b100100 | 4 | 4 |
| 47 | 0b101111 | 7 | 7 |

n-1 = 0b111，按位与等于"高位清零、只保留下 3 位"，取值范围正好 0..7，与 %8 重合。

但 n 不是 2 的幂时立刻崩塌：n=7 时 n-1=0b110，最低位恒为 0，索引 1/3/5 永远取不到，一半桶报废。所以 new HashMap(10) 会被 tableSizeFor 悄悄改成 16。

**加分细节**：tableSizeFor 把任意容量向上取整到 2 的幂，且数组在第一次 put 时才真正分配（懒初始化）。

---

## L2 冲突层：链表什么时候变成红黑树

**一句话答案**：三个常量 —— TREEIFY_THRESHOLD=8、UNTREEIFY_THRESHOLD=6、MIN_TREEIFY_CAPACITY=64。链表插到第 9 个节点时触发树化尝试，表长不足 64 优先扩容；节点删到 ≤6 退回链表。

```java
final void treeifyBin(Node<K,V>[] tab, int hash) {
    int n, index; Node<K,V> e;
    if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY)   // 64
        resize();                                                // 表太小，先扩容
    else if ((e = tab[index = (n - 1) & hash]) != null) {
        // 把整条链的 Node 逐个换成 TreeNode，最后 hd.treeify(tab)
    }
}
```

**决策流程**

```
put 遍历到链尾插入新节点
        │
        ▼
   binCount >= 7 ?  ──否──▶ 保持链表（O(n)）
        │是
        ▼
  table.length >= 64 ? ──否──▶ 先 resize() 扩容，树化推迟
        │是
        ▼
   treeifyBin → 转红黑树（O(log n)）

反向：remove 后树节点 <= 6 → untreeify() 退回链表
```

**为什么是 8：泊松分布说了算**

JDK8 源码注释给了一组数据，理想哈希分布（负载因子 0.75）下桶内节点数服从 λ≈0.5 的泊松分布：

| k | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|---|
| P(k) | 0.607 | 0.303 | 0.076 | 0.0126 | 1.6e-3 | 1.6e-4 | 1.3e-5 | 9.4e-7 | 6e-8 |

链长到 8 的概率是千万分之六 —— 正常情况根本碰不到，只有 hash 函数被写坏才会。TreeNode 内存约为 Node 的两倍，所以"宁可多存 8 个节点也不轻易建树"是统计学决策，不是拍脑袋。

**为什么不用 AVL**：红黑树插入最多 2 次旋转、删除最多 3 次；AVL 严格平衡但删除可能一路旋转到根。HashMap 写操作也不能太贵，红黑树是综合成本更低的折中。

**8 与 6 之间留 7 作缓冲带**，避免临界值反复树化/退化造成抖动。

---

## L3 扩容层：rehash 为什么可以不重算哈希

**一句话答案**：扩容只是把掩码从 n-1 变成 2n-1，多用了一位。这一位就是 hash & oldCap：为 0 留原位，为 1 移到 原下标 + oldCap。扩容退化成"把一条链劈成两条"，O(n) 且无需重算 hash。

**负载因子 0.75**：threshold = capacity × loadFactor。1.0 意味着塞满才扩容、冲突率飙升；0.5 意味着永远浪费一半空间。0.75 是泊松分布下冲突概率还能接受、空间利用率又不太难看的交点。

```
扩容前：oldCap = 16，某桶 index = 2
  hash=2(0b00010) → 18(0b10010) → 34(0b100010) → 50(0b110010) → null
  低 4 位都是 0010，& 15 全等于 2

            判断 hash & oldCap(16)
              ┌────────┴────────┐
           = 0（低位）        = 16（高位）
              ▼                  ▼
   newTab[2] : 2 → 34      newTab[18] : 18 → 50
```

```java
final Node<K,V>[] resize() {
    for (int j = 0; j < oldCap; ++j) {
        Node<K,V> e = oldTab[j];
        if (e == null) continue;
        if (e.next == null)
            newTab[e.hash & (newCap - 1)] = e;
        else if (e instanceof TreeNode)
            ((TreeNode<K,V>)e).split(this, newTab, j, oldCap);
        else {                                  // 劈成 lo / hi 两条
            Node<K,V> loHead = null, loTail = null;
            Node<K,V> hiHead = null, hiTail = null, next;
            do {
                next = e.next;
                if ((e.hash & oldCap) == 0) {   // 只看新增的那一位
                    if (loTail == null) loHead = e; else loTail.next = e;
                    loTail = e;
                } else {
                    if (hiTail == null) hiHead = e; else hiTail.next = e;
                    hiTail = e;
                }
            } while ((e = next) != null);
            if (loTail != null) { loTail.next = null; newTab[j] = loHead; }
            if (hiTail != null) { hiTail.next = null; newTab[j + oldCap] = hiHead; }
        }
    }
}
```

**JDK7 与 JDK8 的关键差异**：JDK7 的 transfer() 用头插法——并发扩容时可能把链表接成环，之后 get 命中该桶就无限循环，CPU 打满。JDK8 改尾插并重写扩容，环消失（但仍线程不安全）。

---

## L4 并发层：HashMap 怎么死，CHM 怎么活

**一句话答案**：JDK8 的 ConcurrentHashMap 把锁粒度降到单个桶：空桶 CAS 直接插入，非空桶 synchronized 锁住桶头；扩容多线程协同搬迁；计数用 baseCount + CounterCell[] 分散热点。读操作完全无锁。

**HashMap 的三种死法**

| 版本 | 故障 | 成因 |
|---|---|---|
| JDK7 | 扩容成环 → CPU 100% | 头插 + 无同步 transfer，两线程交错改 next，首尾相接 |
| JDK8 | 数据覆盖 | 两线程同时判断桶为空，各自 newNode 后写回，后写覆盖先写 |
| JDK8 | size 不准 / 树化异常 | ++size 非原子；并发 treeify 结构错乱 |

```
JDK7 成环：
  T1 读到 e=A, next=B 后被挂起
  T2 完整跑完 transfer，链变成 B→A
  T1 恢复，把头插顺序套用到过期快照上
  → A.next=B, B.next=A  → 环形成，get 死循环
```

**CHM JDK8 的锁设计**

| 场景 | 手段 | 为什么可以 |
|---|---|---|
| 空桶插入 | casTabAt（无锁） | 只有一个线程 CAS 成功，失败自旋重试 |
| 桶内已有节点 | synchronized(f) 锁桶头 | 不同桶互不影响，粒度 = 1 个桶 |
| 读操作 | 完全无锁 | Node.val 和 next 都是 volatile |
| 扩容 | helpTransfer 协助搬迁 | 分段搬迁，ForwardingNode 标记已搬完的桶 |
| 计数 | baseCount + CounterCell[] | LongAdder 思路，CAS 热点分散 |

**sizeCtl：一个 int 四种状态**

| 取值 | 含义 |
|---|---|
| > 0 | 扩容阈值 = capacity × 0.75 |
| 0 | 默认值，尚未初始化 |
| -1 | 正在初始化（CAS 抢占标志） |
| < -1 | 正在扩容：高 16 位是扩容戳，低 16 位是参与线程数 + 1 |

**put 主链路**

```
putVal(key, value)
   │
   ├─ key/value 为 null ─────────────▶ 抛 NPE
   ▼
table 未初始化？ ──是──▶ initTable()：CAS 抢 sizeCtl=-1
   ▼ 否
桶 (n-1)&hash 为空？ ──是──▶ casTabAt 无锁插入（失败 continue）
   ▼ 否
f.hash == MOVED(-1)？ ──是──▶ helpTransfer 协助扩容
   ▼ 否
synchronized(f) { 链表 / 红黑树插入 }
   ▼
addCount(1, binCount)
   ▼
size > sizeCtl ──▶ transfer() 扩容（多线程分段搬迁，stride >= 16）
```

```java
final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null) throw new NullPointerException();
    int hash = spread(key.hashCode());
    int binCount = 0;
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; int n, i, fh;
        if (tab == null || (n = tab.length) == 0)
            tab = initTable();                            // ① 懒初始化，CAS 抢 sizeCtl
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value, null)))
                break;                                    // ② 空桶：无锁 CAS，成功即走
        }
        else if ((fh = f.hash) == MOVED)
            tab = helpTransfer(tab, f);                   // ③ 该桶在扩容：顺手帮个忙
        else {
            V oldVal = null;
            synchronized (f) {                            // ④ 只锁桶头
                if (tabAt(tab, i) == f) {                 // 双重检查：确认头没被换
                    if (fh >= 0) {
                        // 链表遍历插入；binCount 记录长度
                    } else if (f instanceof TreeBin) {
                        // 红黑树插入
                    }
                }
            }
            if (binCount != 0) {
                if (binCount >= TREEIFY_THRESHOLD) treeifyBin(tab, i);
                if (oldVal != null) return oldVal;
                break;
            }
        }
    }
    addCount(1L, binCount);
    return null;
}
```

**计数：为什么 size() 是估算值**
高并发下所有线程 CAS 同一个 baseCount 会把缓存行打成战场。LongAdder 思路是 CAS 失败就换一个 CounterCell 去加，把热点摊到多个格子；size() 时把 baseCount 和所有 cell 加起来。代价是读到的永远是某个瞬间的近似值 —— 这也符合 CHM 的弱一致语义（迭代器同样弱一致）。

```java
if (counterCells != null || !casBase(b = baseCount, b + x)) {
    CounterCell[] cs; long v; int m; CounterCell c;
    boolean uncontended = true;
    if ((cs = counterCells) == null || (m = cs.length - 1) < 0 ||
        (c = cs[ThreadLocalRandom.getProbe() & m]) == null ||
        !(uncontended = casCell(c, v = c.value, v + x))) {
        fullAddCount(x, uncontended);     // 兜底：扩容 cell 数组 / 自旋
        return;
    }
}
```

**JDK7 vs JDK8 一句话**：JDK7 是分段锁（Segment 继承 ReentrantLock，默认 16 段，并发度上限 16）；JDK8 抛弃 Segment，改成桶级锁 + CAS，并发度 = 桶数量，空桶插入彻底无锁。

---

## L5 高频问答速查

| 问题 | 一句话答案 |
|---|---|
| HashMap 为什么线程不安全？ | JDK7 扩容头插可成环致死循环；JDK8 有数据覆盖、size 不准、结构竞争 |
| key 可以是 null 吗？ | HashMap 可以（hash 记 0，落 0 号桶）；CHM 和 Hashtable 直接 NPE |
| 初始容量为什么 16？ | 太小频繁扩容，太大浪费；16 是经验值，且为 2 的幂便于位运算 |
| 负载因子为什么 0.75？ | 时间空间折中：泊松下冲突可接受，数组利用率也不至于太差 |
| 长度到 8 就变树吗？ | 不是。插入第 9 个才触发，且 table.length ≥ 64，否则先扩容 |
| 为什么树化 8、退化 6？ | 留 7 作缓冲带，避免临界值反复树化/退化抖动 |
| 扩容后元素去哪？ | 只有两处：原位 j，或 j + oldCap；依据 hash & oldCap |
| 为什么红黑树不用 AVL？ | 红黑树插删旋转次数少（≤2 / ≤3），AVL 严格平衡维护成本高 |
| modCount 干什么？ | fail-fast：迭代中结构被改就抛 ConcurrentModificationException |
| CHM 的 get 要加锁吗？ | 不要。Node 的 val 和 next 都是 volatile，不会读到半成品 |

---

## L6 3 道模拟面试题

### 题 1（初级 · 3 分钟）说说 HashMap put 一个键值对的完整过程。什么情况下链表会转成红黑树？

**考察意图**：不考背源码，考"一条完整链路"的思维，以及阈值条件会不会说错。

**回答骨架**

1. 初始化：table 为空 → resize() 分配（默认 16），threshold = 12。
2. 算哈希：h = key.hashCode(); hash = h ^ (h >>> 16)。
3. 定位：index = (n-1) & hash。
4. 空桶：直接 newNode 放入，结束。
5. 非空：先比 hash 再比 equals（== 或 equals），相同则覆盖 value 并返回旧值。
6. 树节点：instanceof TreeNode → putTreeVal。
7. 链表：遍历到尾部插入；binCount >= 7 时调用 treeifyBin。
8. 收尾：++modCount；++size > threshold 则 resize()。

**追问**
- 所有 key 的 hashCode 都一样呢？→ 全落一个桶，退化成链表 O(n)；树化后 O(log n)，但树的比较需要 Comparable 或 tieBreakOrder 兜底。
- 覆盖 value 会触发扩容吗？→ 不会，size 没变。

**常见扣分点**：说"链表长度大于 8 就转红黑树"——漏了"插入第 9 个才触发"和"表长 ≥ 64"。这两点一说，面试官会把你归到"读过源码"那档。

### 题 2（中级 · 5 分钟）多线程往 HashMap 里 put 会怎样？CHM 在 JDK8 怎么保证线程安全？size() 准确吗？

**考察意图**：三个层次 —— 知道 HashMap 坏在哪、知道 CHM 好在哪、知道 CHM 的代价（弱一致性）。答到第三层是分水岭。

**回答骨架**

- HashMap 的故障：JDK7 头插扩容可能成环，get 死循环打满 CPU；JDK8 尾插解决环，但仍有并发覆盖、++size 丢更新、树化竞争。
- CHM 结构：Node[] table，无 Segment。
- 写路径：空桶 casTabAt 无锁插入；非空桶 synchronized(f) 锁桶头；遇 MOVED 就 helpTransfer 一起搬。锁粒度 = 1 个桶。
- 读路径：全程无锁，靠 volatile 保证可见性。
- 计数：baseCount + CounterCell[]（LongAdder 思想），CAS 热点分散；size() 是估算值、弱一致。

**追问**
- 为什么不用 ReentrantLock 锁桶？→ 每个桶维护锁对象内存开销大；synchronized 有锁升级，JVM 层面更省。
- 为什么 CHM 不允许 null？→ 并发下无法区分"key 不存在"和"value 就是 null"，containsKey 与 get 产生二义性。

**常见扣分点**：把 JDK7 的 Segment 说成 JDK8 实现；说"CHM 是绝对线程安全的"——它是单操作原子，复合操作（先 get 再 put）仍需自己加锁或用 computeIfAbsent。

### 题 3（高级 · 6 分钟）自定义一个类作 HashMap 的 key 要注意什么？扩容时为什么不用重算 hash？

**考察意图**：前半是工程判断（契约、不可变性），后半是数学直觉（2 的幂 + 掩码）。两段能连起来讲，说明理解设计动机而非背结论。

**回答骨架**

- 契约：同时重写 equals 和 hashCode，且用同一组字段。equals 说相等，hashCode 必须相等。
- 不可变性：参与 hash 的字段进 map 后不能再变，否则桶下标变了，entry 就"失踪"。范本是 String / Integer。
- 分布：hashCode 别返回常量，也别只用一两个字段，否则全挤一个桶退化成 O(n)。
- rehash 的数学：n 是 2 的幂 → index 只取决于 hash 的低 log₂n 位；扩容到 2n 只多用 1 位，即 hash & oldCap。为 0 留原位，为 1 移到 j + oldCap，无需重算 hashCode。
- 工程建议：优先不可变类型或 record（自动生成 equals/hashCode）。

**追问**
- hashCode 相同但 equals 不同会怎样？→ 落同一桶，用 equals 区分，功能正确但性能下降。
- hashCode 会溢出吗？→ 会，但无所谓，它只用来算下标，& (n-1) 会屏蔽符号位。

**加分点**：主动提"如果 key 是可变对象且已放进 map，正确做法是移除 → 修改 → 重新 put，而不是原地改"。

---

## 本轮自测清单

合上文档，能不看稿讲出来才算过。

- **L0** 说出 equals 与 hashCode 的双向契约，以及破坏它会导致什么现象。
- **L1** 手推 (n-1)&hash 与 hash%n 的等价条件，解释扰动函数解决什么问题。
- **L2** 准确说出三个阈值，以及"第 9 个节点才触发树化"这个细节。
- **L3** 画出一条链在扩容时被劈成 lo/hi 两条的过程，说明判断依据是哪一位。
- **L4** 讲清 CHM 的三条路径（CAS 空桶 / synchronized 桶头 / helpTransfer），以及 size 为什么是估算值。

---

**下一轮预告**：JVM 内存模型与 GC —— 从对象在堆里怎么躺，到 Minor GC / Mixed GC 的触发时机，再串到 ThreadLocal 内存泄漏与 OOM 排查。同样是一层层往下挖的结构。
