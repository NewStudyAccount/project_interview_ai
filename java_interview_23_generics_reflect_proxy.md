# Java 面试深挖 23 · 泛型擦除、反射、注解与动态代理

> **一句话主线**：编译期给了你一套类型，运行期只留一个 `Class`。
> 泛型被擦掉，注解被塞进 class 文件，反射把 class 文件重新读出来，动态代理再按读出来的东西生成一个新类。
> 这四件事不是四个知识点，是同一根链条上的四个环。会背"泛型擦除"的人很多，能把"擦除 → 桥方法 → 反射 → 代理 → Spring AOP"讲成一条线的很少。

本期所有数字均为本机 **JDK 21.0.7** 实测（`javac` + `javap` + 三次重复取中位），源码与反编译结果在正文中给出。

---

## L0 一张 class 文件，解释四件事

先把"编译后到底留下了什么"摆出来，后面四节都挂在这张图上。

```text
Order.class
├── 常量池 Constant Pool
│     #1 Utf8  "create"
│     #2 Utf8  "(Ljava/lang/String;I)Ljava/lang/String;"   ← 描述符只有原始类型
│     #3 Utf8  "Signature"                                  ← 泛型的唯一痕迹
│     #4 Utf8  "<T:Ljava/lang/Object;>Ljava/util/List<TT;>;"
│     #5 Utf8  "RuntimeVisibleAnnotations"                   ← RUNTIME 注解存这里
├── 字段表 field_info      descriptor 已擦除，signature 保留泛型
├── 方法表 method_info     descriptor 已擦除，可能多出 ACC_BRIDGE 方法
└── 属性表 attribute_info
      ├── Signature                       → 反射 getGenericType() 读的是它
      ├── RuntimeVisibleAnnotations       → getAnnotation() 读的是它
      ├── RuntimeInvisibleAnnotations     → CLASS 级注解，反射看不见
      └── MethodParameters                → 只有 -parameters 编译才有
```

四个结论直接落在这张表上：

1. **描述符里没有泛型**。`(Ljava/lang/String;I)` 只有擦除后的类型，所以 `List<String>` 和 `List<Integer>` 是同一个类。
2. **泛型信息没消失，只是搬了家**。搬到 `Signature` 属性里，编译器看得懂，字节码执行不认。所以"擦除"准确说是"运行期不参与类型检查"，不是"信息全丢"。
3. **注解能不能被框架读到，取决于它被写进哪个属性**。`RUNTIME` 进 `RuntimeVisibleAnnotations`，`CLASS` 进 `RuntimeInvisibleAnnotations`（反射读不到，只能字节码工具读）。
4. **桥方法是编译器偷偷补的**，标志位 `ACC_BRIDGE | ACC_SYNTHETIC`，它存在的唯一理由是"擦除后父类方法签名变了，子类的重写对不上号"。

---

## L1 泛型擦除：擦到哪、留下什么、炸在哪

### 1.1 擦除规则只有四行

| 声明 | 擦除后 | 说明 |
|---|---|---|
| `<T>` | `Object` | 无界类型变量擦到上界 |
| `<T extends Comparable<T>>` | `Comparable` | 有界擦到**第一个**上界 |
| `<T extends A & B>` | `A` | 第二个上界开始只用于编译期检查 |
| `List<T>` / `T[]` | `List` / `Object[]` | 容器与数组一起擦 |
| `List<String>` | `List` | 实参直接丢弃 |

### 1.2 实验一：两个泛型实参，同一个 Class

```java
List<String> a = new ArrayList<>();
List<Integer> b = new ArrayList<>();
System.out.println(a.getClass() == b.getClass());   // true
System.out.println(a.getClass().getName());         // java.util.ArrayList

List raw = a;                    // 裸类型，编译器放弃检查
List<Integer> hack = (List<Integer>) (Object) a;   // 只有 unchecked 警告
hack.add(1);                     // 运行时畅通无阻
```

实测输出：

```text
List<String>.class == List<Integer>.class ? true
runtime class = java.util.ArrayList
raw view element type = java.lang.Integer
CCE at READ site: class java.lang.Integer cannot be cast to class java.lang.String
```

**关键在最后一行**。编译器在每个"取值点"插入 `checkcast`：

```java
// 源码
String s = a.get(0);
// 字节码
aload_1
iconst_0
invokeinterface java/util/List.get:(I)Ljava/lang/Object;   // 返回 Object
checkcast java/lang/String                                  // ← 编译器补的
astore_2
```

所以泛型 bug 的爆点永远是 `ClassCastException` 在**读取的地方**，而不是写入的地方。线上看到"某个 Service 里莫名 CCE"，八成是前面某处裸引用塞了脏数据进来 —— 这就是为什么 `@SuppressWarnings("unchecked")` 要当红线管，它关掉的正是这道唯一的保险。

### 1.3 实验二：桥方法，编译器替你补的那一层

```java
class Parent<T> {
    T get() { return null; }
    void set(T t) { }
}
class Child extends Parent<String> {
    @Override String get() { return "child"; }
    @Override void set(String s) { }
}
```

`Child.class.getDeclaredMethods()` 实测返回 **4 个**方法，不是 2 个：

```text
get[]                        ret=Object   bridge=true  synthetic=true
get[]                        ret=String   bridge=false synthetic=false
set[class java.lang.Object]  ret=void     bridge=true  synthetic=true
set[class java.lang.String]  ret=void     bridge=false synthetic=false
```

为什么必须补？父类 `Parent` 擦除后是 `Object get()`。子类写的是 `String get()`。**返回值是方法签名的一部分**，`String get()` 不构成对 `Object get()` 的重写，JVM 的多态就断了：用 `Parent p = new Child(); p.get()` 走的是父类声明，找不到 `String get()`。

编译器的解法是生成一个桥方法做转发：

```text
Object get()  [ACC_BRIDGE, ACC_SYNTHETIC]
   └─ invokevirtual Child.get()Ljava/lang/String;
      └─ areturn                         // 协变返回，JVM 允许
```

由此派生三个高频追问：

- **桥方法会出现在反射结果里**，写 `getDeclaredMethods().length == 2` 的断言一定会挂。要过滤 `m.isBridge()`。
- **桥方法可以导致"方法重复"异常**。经典报错 `java.lang.ClassFormatError: Duplicate method name&signature`，多出现在手写字节码 / 老版 Kotlin 编译器 / 泛型接口多层继承时。
- **`@Transactional` 加在 `Parent` 的泛型方法上、子类重写**时，AOP 匹配的是桥方法的签名，容易漏匹配 —— 这是"注解明明加了却不生效"的一个冷门原因。

### 1.4 实验三：相同的擦除 → 无法重载

```java
void m(List<String> a) {}
void m(List<Integer> a) {}      // 编译直接失败
```

```text
错误: 名称冲突: m(List<Integer>) 与 m(List<String>) 具有相同擦除
```

擦除后两个都是 `m(List)`，签名完全一样。**"泛型不参与重载决议"** 这句话的准确含义是：重载决议看的是擦除后的描述符，泛型实参只在"同一擦除签名内部"起作用（比如 `m(T)` 与 `m(String)` 在有界场景下仍可能冲突）。

绕过办法（都不推荐，但面试官爱问）：
1. 换方法名 —— 最干净。
2. 加一个哑参数 `m(List<String> a, String... ignored)` —— 丑陋但有效。
3. 用 `Object` 接参，进去自己判 —— 丢失编译期检查。

### 1.5 实验四：泛型没丢干净，`Signature` 还在

这是最容易被讲错的一点。**泛型实参在运行期可以通过 `getGenericSuperclass()` 拿到**，前提是这个类型出现在"类的签名"里，而不是"局部变量"里。

```java
static class Ref<T> {
    Type captured() {
        return ((ParameterizedType) getClass().getGenericSuperclass())
                .getActualTypeArguments()[0];
    }
}

System.out.println(new Ref<List<String>>() {}.captured());        // java.util.List<java.lang.String>
System.out.println(new Ref<Map<String,Integer>>() {}.captured()); // java.util.Map<java.lang.String, java.lang.Integer>
System.out.println(new Ref<List<String>>().getClass()
        .getGenericSuperclass());                                 // class java.lang.Object
```

```text
anonymous  = java.util.List<java.lang.String>
anonymous2 = java.util.Map<java.lang.String, java.lang.Integer>
plain      = class java.lang.Object
```

第三行是重点：**只有匿名子类才有意义**。因为 `new Ref<List<String>>()` 这个实参写在"使用处"，擦完就没了；而 `new Ref<List<String>>(){}` 生成的是一个真实的 `RefSub extends Ref<List<String>>` 类，`List<String>` 被写进了 `RefSub` 的 `Signature` 属性。

这就是 Jackson 的 `TypeReference`、Guava 的 `TypeToken`、Spring 的 `ParameterizedTypeReference` 必须"匿名子类 + 花括号"的全部原因：

```java
// 错：T 被擦成 Object，反序列化出来是 LinkedHashMap
List<User> a = mapper.readValue(json, new TypeReference<List<User>>().getType());

// 对：多一对花括号，实参进了子类的 Signature
List<User> b = mapper.readValue(json, new TypeReference<List<User>>() {});
```

面试里这样回答比"泛型擦除所以拿不到"高一个档次：**"拿不到"是错的，正确说法是"只能从类型声明（类的 Signature）里拿，拿不到局部变量和方法调用的实参。"**

---

## L2 通配符与 PECS：擦除决定了它们只能是"编译期幻觉"

`List<? extends Number>` 为什么不能 `add`？

因为它擦除后就是 `List`，JVM 层面 `add` 完全合法。编译器唯一能做的是**在编译期假装它不合法**，用一个叫 `capture#1 of ? extends Number` 的虚构类型拦住你。既然运行时没有类型可查，编译器就不敢让你写进去 —— 写进去也没人能校验。

```java
List<? extends Number> src = new ArrayList<Integer>();
Number n = src.get(0);      // 读出来一定是 Number 的子类，安全
src.add(1);                 // 编译错误：capture#1 不接受 Integer
src.add(null);              // 唯一合法，因为 null 是任何引用类型的值
```

PECS（Producer Extends, Consumer Super）在这里不是口诀，是推论：

- 你只**读**（生产者）→ 用 `? extends T`，上界保证读出来是 T。
- 你只**写**（消费者）→ 用 `? super T`，下界保证写进去的 T 一定被接受。
- 又读又写 → 别用通配符，直接用 `T`。

`Collections.copy(List<? super T> dest, List<? extends T> src)` 是 JDK 里最标准的 PECS 样例，背它比背口诀有用。

---

## L3 反射：慢在哪，快在哪，什么时候不能不用

### 3.1 三个入口，代价不同

```java
Class<?> c1 = obj.getClass();                 // 最快，已有对象
Class<?> c2 = Order.class;                    // 编译期常量，零成本
Class<?> c3 = Class.forName("com.x.Order");   // 触发类加载 + 初始化，最慢
```

`Class.forName(name)` 默认 `initialize=true`，会跑 `<clinit>`。只想加载不初始化，用 `Class.forName(name, false, loader)`。这一句能救活一批"初始化顺序敏感"的启动类。

### 3.2 `Method.invoke` 的三个成本

```text
① 参数装箱 + 数组分配      每次调用 new Object[]{...}，int → Integer
② 访问权限检查             每次进入都要过 Reflection.quickCheckMemberAccess
③ 调用点对 JIT 不透明      invoke 是"一个 Method 对象的虚调用"，多态性极差
```

JVM 的对策是**膨胀（inflation）**：

```text
sun.reflect.inflationThreshold = 15（默认）
第 1~15 次调用  → NativeMethodAccessorImpl（走 JNI 进 native）
第 16 次以后    → 动态生成 GeneratedMethodAccessor$N 字节码，直接 invokevirtual
```

本机实测（同一个 `int inc(int)`，单次调用计时）：

```text
第 1 次   3,564,100 ns   ≈ 3.6 ms   ← 首次要加载 accessor 类 + 权限校验 + JNI 绑定
第 2 次      46,800 ns   ≈ 47 μs
第 3 次       7,500 ns
第 5 次       2,900 ns
第 12~22 次   1,500~2,600 ns        ← native accessor 的单次成本
```

三条可以用在面试里的结论：

1. **反射的"第一次"是毫秒级的，不是纳秒级**。所以"启动时缓存 Method、运行时零反射"是性能常识，而不是玄学。
2. **单次 1.5μs 的 native 版本，在 15 次后切到字节码版本**，稳态见下节。这就是为什么"反射慢 100 倍"的博客大多是**没有预热**测出来的。
3. **`-Dsun.reflect.noInflation=true`** 可以让它一上来就用字节码版，适合明确要反复调用的场景（如 RPC 框架启动期）。

### 3.3 稳态四方对比（预热 300 万次，N=200 万，单位 ns/op）

| 调用方式 | 三次实测 | 取值 | 相对直接调用 |
|---|---|---|---|
| 直接 `t.inc(7)` | 2.08 / 1.07 / 1.02 | **1.1** | 1× |
| `Method.invoke` | 6.88 / 8.90 / 6.86 | **7.5** | ≈ 7× |
| `MethodHandle.invokeExact` | 6.90 / 5.02 / 2.76 | **~5** | ≈ 4× |
| `LambdaMetafactory` 生成接口 | 1.21 / 1.93 / 1.88 | **1.9** | ≈ 1.7× |

**"反射比直接调用慢 100 倍"是伪知识。** 预热之后是 7 倍，落在纳秒级。真正致命的从来不是单次 7ns，而是：
- **没有缓存 `Method`**，每次 `getDeclaredMethod` 遍历方法表（微秒级，比 invoke 本身贵两个数量级）；
- **在循环里反射**，7ns × 1 亿次 = 0.7 秒；
- **`setAccessible` 每次重设**，触发 SecurityManager/模块检查。

### 3.4 为什么 Lambda 版本能跑到 1.9ns

`LambdaMetafactory.metafactory` 在运行期生成一个实现类，它的方法体是**直接 `invokevirtual`**，没有数组、没有装箱、没有权限检查：

```java
MethodHandle mh = MethodHandles.lookup()
        .findVirtual(Target.class, "inc", MethodType.methodType(int.class, int.class));
IntFn f = (IntFn) LambdaMetafactory.metafactory(
        MethodHandles.lookup(), "apply",
        MethodType.methodType(IntFn.class),                        // 接口
        MethodType.methodType(int.class, Target.class, int.class), // SAM 精确签名
        mh,
        MethodType.methodType(int.class, Target.class, int.class)  // 实例化类型
).getTarget().invoke();
```

**踩坑提醒**：`samMethodType` 必须是接口方法的**精确描述符**。我第一次写成 `(Object,int)int`（以为要按擦除写），运行期直接 `AbstractMethodError: does not define or inherit 'int apply(Target,int)'` —— 因为生成的 `apply(Object,int)` 并没有覆写接口里的 `apply(Target,int)`。这个错误信息非常反直觉，值得记住。

这也是 MyBatis-Plus、MapStruct、Spring 6 的 `@Contract` 一类"把反射转成直接调用"的底层手段：**反射只用来找方法，真正的调用交给方法句柄或字节码。**

### 3.5 `setAccessible` 在 JDK 17+ 已经是"要许可"的操作

JDK 9 引入模块系统，JDK 16 起强封装默认开启（JEP 396），JDK 17 彻底关掉 `--illegal-access=permit`。本机 JDK 21 实测：

```text
Integer.value           -> java.lang.reflect.InaccessibleObjectException
ArrayList.elementData   -> java.lang.reflect.InaccessibleObjectException
自己的类 setAccessible   -> true（同一模块内，无需 open）
```

这意味着：
- 想改 `java.base` 里的私有字段，必须启动参数 `--add-opens java.base/java.lang=ALL-UNNAMED`（**`--add-exports` 不够，那是给编译期和静态访问用的**）。
- 一批老框架（fastjson 1.x 直接塞 `Modifier`、早期 cglib、部分序列化库）在 JDK 17 上启动即崩，根因就是这个，不是"兼容性玄学"。
- **自己的代码不要依赖 `setAccessible` 改 JDK 内部状态**。JDK 21 还能 `--add-opens`，但这条路每年都在收窄。

---

## L4 注解：三档保留策略决定了"谁能看见它"

### 4.1 三档 retention，三个不同的读者

| Retention | 写进哪 | 谁能读 | 典型注解 |
|---|---|---|---|
| `SOURCE` | 不写进 class | 只有编译器 / 注解处理器 | `@Override`、`@SuppressWarnings`、Lombok 全套 |
| `CLASS`（默认） | `RuntimeInvisibleAnnotations` | 字节码工具（ASM/Javassist）、编译期 APT | 部分 AOP 库的自定义标记 |
| `RUNTIME` | `RuntimeVisibleAnnotations` | **反射** | `@Transactional`、`@Autowired`、`@RequestMapping` |

记住一句话：**框架能在运行时看见的注解，一定是 `RUNTIME`。** 你自己写的注解忘了加 `@Retention(RUNTIME)`，默认是 `CLASS`，`getAnnotation()` 永远返回 null —— 这个 bug 每年都有人踩。

### 4.2 三个冷门但高频的坑

**坑一：`@Inherited` 只对类生效，且不跨接口。**
```java
@Inherited @Retention(RUNTIME) @interface Marker {}
@Marker class A {}
class B extends A {}                       // B 上 getAnnotation(Marker) 能拿到
interface I {}                              // I 上标 @Marker
class C implements I {}                     // C 上拿不到！接口注解不继承
```
Spring 的 `@Transactional` 就没标 `@Inherited`，但它靠 `AnnotationUtils.findAnnotation` 自己做父类/接口递归查找 —— **"注解继承"是框架行为，不是 Java 语言行为**，这是必须分清的一句。

**坑二：`getAnnotations()` 与 `getDeclaredAnnotations()` 语义不同。** 前者含继承来的，后者只有本类声明的。写框架代码时选错，会得到"父类上有注解但子类查不到"或反之的诡异结果。

**坑三：重复注解是"容器"伪装出来的。**
```java
@Repeatable(Schedules.class) @interface Schedule { String cron(); }
@interface Schedules { Schedule[] value(); }

// 编译后实际存的是
@Schedules({@Schedule(cron="0 0 1 * *"), @Schedule(cron="0 0 2 * *")})
```
所以 `getAnnotation(Schedule.class)` 拿不到，必须 `getAnnotationsByType(Schedule.class)`。手写框架代码时先判 `isAnnotationPresent(容器.class)` 是老写法，新代码统一用 `getAnnotationsByType`。

### 4.3 Spring 为什么不用反射读注解（至少不全用）

`@ConditionalOnClass` 这类注解的语义是"**类不存在时也不能报错**"。如果用 `Class.forName` 再反射读注解，类不存在就直接 `ClassNotFoundException` 了。Spring 的做法是**用 ASM 直接读 class 文件的字节，不加载类**：

```text
ConfigurationClassParser
   └─ MetadataReader（ASM 实现）
        └─ getAnnotationMetadata()   ← 解析 RuntimeVisibleAnnotations，不触发类加载
             └─ 判断 @Conditional 是否匹配
                  └─ 匹配通过才 Class.forName 加载
```

这解释了三件实践中的事：

1. **`@ConditionalOnClass` 里写的类名必须真实存在**（或 `name` 属性写字符串），否则 ASM 层面能过、加载时炸。
2. **`@Conditional` 的实现类不能依赖"注解所在类"**，因为它可能在 ASM 阶段就被求值。
3. **扫描大量 class 时，ASM 比反射快一个量级**，因为省掉了类加载 + 链接 + 初始化。Spring Boot 启动时扫几千个类还能 3 秒起来，靠的就是这个。

---

## L5 动态代理：两种生成方式，两套字节码

### 5.1 JDK 动态代理只能代理接口，因为它是"另起一个类"

```java
OrderService real = (sku, qty) -> "order-" + sku;
OrderService proxy = (OrderService) Proxy.newProxyInstance(
        loader, new Class[]{OrderService.class},
        (p, m, a) -> {
            System.out.println("h.invoke -> " + m.getName());
            return m.invoke(real, a);
        });
```

实测生成物的结构：

```text
proxy class = jdk.proxy1.$Proxy0            （JDK 9+ 在 jdk.proxy1 包里，之前是 com.sun.proxy.$Proxy0）
superclass  = java.lang.reflect.Proxy       （所以它已经继承了 Proxy，不能再继承你的类 → 只能靠接口）
interfaces  = [interface OrderService]
declaredMethods = 5
    private static MethodHandles$Lookup $Proxy0.proxyClassLookup(Lookup)
    public final boolean equals(Object)
    public final String toString()
    public final int hashCode()
    public final String create(String, int)
```

`create` 方法的字节码（`-Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true` 导出后 `javap -c`）：

```text
public final java.lang.String create(java.lang.String, int);
   0: aload_0
   1: getfield      #24   // Field java/lang/reflect/Proxy.h:Ljava/lang/reflect/InvocationHandler;
   4: aload_0
   5: getstatic     #67   // Field m3:Ljava/lang/reflect/Method;   ← 静态缓存，不是每次 getMethod
   8: iconst_2
   9: anewarray     #49   // class java/lang/Object               ← 每次调用都分配数组
  12: dup
  13: iconst_0
  14: aload_1
  15: aastore
  16: dup
  17: iconst_1
  18: iload_2
  19: invokestatic  #71   // Method java/lang/Integer.valueOf:(I)Ljava/lang/Integer;  ← 装箱
  22: aastore
  23: invokeinterface #32, 4  // InvocationHandler.invoke:(Object,Method,[Object)Object
  28: checkcast     #62   // class java/lang/String              ← 返回值强转
  31: areturn
```

**这十行字节码解释了 JDK 代理的全部开销来源**：`anewarray`（数组）、`Integer.valueOf`（装箱）、`invokeinterface`（handler 虚调用）、`checkcast`（返回强转）。三次调用 300 万次的实测是 **8.3 / 9.0 ns/op**，直接调用 0.6ns。

注意 `m0..m3` 是**静态字段**：`Method` 对象在类初始化时就缓存好了。所以"JDK 代理每次都用反射找方法"是错的 —— 它缓存了 `Method`，只是每次还得走 `invoke`。

### 5.2 CGLIB 是"继承你"，所以能代理类，但代理不了 final

```java
Enhancer e = new Enhancer();
e.setSuperclass(OrderService.class);
e.setCallback((MethodInterceptor) (obj, m, a, proxy) -> {
    System.out.println("intercept -> " + m.getName());
    return proxy.invokeSuper(obj, a);
});
OrderService p = (OrderService) e.create();
```

实测生成物（`-Dcglib.debugLocation=out` 导出）：

```text
class OrderService$$EnhancerByCGLIB$$beaa34fd
        extends OrderService implements Factory
  fields:
    private boolean CGLIB$BOUND;
    private MethodInterceptor CGLIB$CALLBACK_0;
    private static final Method     CGLIB$create$0$Method;
    private static final MethodProxy CGLIB$create$0$Proxy;   ← FastClass 的入口
```

`create` 方法有**三级分支**，这是 CGLIB 相对 JDK 代理最大的结构性差异：

```text
public final String create(String, int);
   0: getfield  CGLIB$CALLBACK_0            // ① 拦截器已绑定？
   5: ifnonnull 17
   9: invokestatic CGLIB$BIND_CALLBACKS     //    没绑定，先绑（ThreadLocal → 实例）
  17: ifnull 57                             // ② 确实没有拦截器？
  21..48: 装箱 + MethodProxy → intercept(...)  //    有：走拦截
  57: aload_0; aload_1; iload_2
  60: invokespecial OrderService.create     // ③ 没有：直接调父类，零开销
```

**注意 ③**：CGLIB 在没有拦截器时是**直接 `invokespecial` 到父类**，完全不过反射。JDK 代理做不到这一点，因为 `Proxy` 的构造器强制要求一个非 null 的 `InvocationHandler`，没有"空 handler 直接放行"的路径。

`proxy.invokeSuper(obj, args)` 走的是 `MethodProxy` + **FastClass**：CGLIB 额外生成了 `OrderService$$FastClassByCGLIB` 和 `Enhancer$$FastClassByCGLIB` 两个类，用 **int 下标 + switch** 代替反射调用：

```text
CGLIB 目录实测生成 3 个 class：
  OrderService$$EnhancerByCGLIB$$xxx.class          ← 代理类（继承）
  OrderService$$EnhancerByCGLIB$$xxx$$FastClass.class  ← 被代理类的索引表
  OrderService$$FastClassByCGLIB$$xxx.class         ← 代理类的索引表
```

### 5.3 实测对比与选型

| 方式 | 三次实测 ns/op | 相对直接调用 | 首次创建成本 |
|---|---|---|---|
| 直接调用 | 0.59 / 0.63 | 1× | 0 |
| JDK 动态代理 | 8.27 / 9.04 | ≈ 14× | 低（复用 `Proxy` 的类缓存，同接口只生成一次） |
| CGLIB | 17.12 / 18.51 | ≈ 28× | **高**（ASM 生成字节码 + FastClass + defineClass，毫秒级） |

这里有个反常识：**本机测出 CGLIB 的单次调用比 JDK 代理还慢**。原因是 `invokeSuper` 要走 `MethodProxy` → FastClass `switch` → 仍然装箱数组。所以"CGLIB 一定比 JDK 代理快"是错的。真正该记的是**成本结构**：

- **创建成本**：CGLIB ≫ JDK 代理。所以高频动态创建代理（比如每个请求造一个）用 CGLIB 是灾难。
- **调用成本**：同一量级（都是 10ns 级），都远小于一次 RPC 或一次 SQL。
- **能力差异**：CGLIB 能代理类（无接口），JDK 只能代理接口；CGLIB 不能代理 `final` 类和 `final`/`private` 方法（`private` 方法本来就不参与重写，等于没代理）。

Spring 的选择演进：

```text
Spring Framework ProxyFactory：
    目标类实现了接口 → 默认 JDK 代理
    未实现接口       → 强制 CGLIB
Spring Boot 2.0 起：
    spring.aop.proxy-target-class 默认 true → 一律优先 CGLIB
原因：CGLIB 能代理"只实现了一个接口的类"的全部 public 方法，
      避免"注入接口时是代理、注入实现类时是原对象"的经典陷阱
```

那个经典陷阱值得单独说：`@Autowired UserServiceImpl impl`（具体类）在 JDK 代理下会 `NoSuchBeanDefinitionException`，因为容器里只有一个 `$Proxy0`，它不是 `UserServiceImpl` 的子类。CGLIB 因为继承原类，这个注入就成立。**Boot 把它设成默认 `true`，本质是用"多生成一个类"换"少一类启动失败"。**

### 5.4 代理的三个结构性限制（必背）

1. **`this` 调用不走代理**。`this.method()` 是 `invokevirtual` 到本对象，不经过代理实例，所以 `@Transactional`、`@Async`、`@Cacheable` 全部失效。解法：注入自己（`@Lazy` 破循环）、`AopContext.currentProxy()`（要开 `exposeProxy`）、拆到另一个 Bean。
2. **`private` / `static` / `final` 方法代理不了**。JDK 代理靠接口，接口里没有 `private`；CGLIB 靠重写，`final` 不能被重写。
3. **代理对象的 `getClass()` 不是你的类**。写 `obj.getClass() == Order.class` 永远 false；`instanceof` 才靠谱。同理 `obj.getClass().getAnnotation(...)` 也拿不到你标在原类上的注解 —— 要 `AopUtils.getTargetClass(obj)`。

---

## L6 生产七坑（都是本人或同事真踩过的）

1. **编译没加 `-parameters`，`@PathVariable` 拿不到名字。** Spring Framework 6.1 起明确要求 `-parameters`（Boot 3.2 的 parent 已默认加），否则参数名是 `arg0`。同理 MyBatis 的 `#{name}` 单参数场景也会翻车。
2. **注解忘了 `@Retention(RUNTIME)`**，本地单测用手动 new 能过，一上容器就 null。
3. **`getDeclaredMethods()` 顺序不保证**。用它做"第一个匹配"的逻辑，在不同 JDK / 不同编译参数下行为会变。要稳定就按名字排序或显式指定。
4. **反射缓存没做 key 设计**。`Map<Class, Map<String, Method>>` 是基本盘；用 `Method` 当 key 会因不同 `ClassLoader` 的同名类不 equals 而漏命中。
5. **JDK 17 上老框架反射崩**（`InaccessibleObjectException`）。应急 `--add-opens`，正解是升框架版本。
6. **CGLIB 代理 final 类启动即失败**：`Cannot subclass final class`。Kotlin 的类默认 final，所以 Kotlin + Spring AOP 必须给类加 `open` 或用 `all-open` 插件 —— 这是 Kotlin 后端的头号坑。
7. **`Proxy.newProxyInstance` 的 ClassLoader 选错**，导致代理类和接口不在同一命名空间，`ClassCastException: $Proxy0 cannot be cast to X`。要用**接口的** ClassLoader，不是当前线程的。

---

## L7 数字速查

| 数字 | 含义 |
|---|---|
| 15 | `sun.reflect.inflationThreshold`，超过后 native accessor 换字节码版 |
| 3.6 ms | 反射首次调用的实测耗时（含 accessor 类加载） |
| 1.5 μs | native accessor 稳态单次成本 |
| 7.5 ns | 反射稳态 vs 直接调用 1.1 ns ≈ 7 倍 |
| 1.9 ns | LambdaMetafactory 生成接口的调用成本 |
| 8.3 ns | JDK 代理单次调用 |
| 17 ns | CGLIB 单次调用（本机，invokeSuper 路径） |
| 5 | `$Proxy0` 的方法数 = 1 业务 + equals/toString/hashCode + 1 个 lookup 辅助 |
| 3 | CGLIB 一次 `Enhancer.create()` 生成的 class 数 |
| 4 / 2 | `Child` 的声明方法数 / 真实方法数（多出的 2 个是桥方法） |
| 1 | 擦除后 `List<String>` 与 `List<Integer>` 的 Class 个数 |
| 0 | `List<? extends T>` 能 `add` 的元素个数（除 null） |

---

## L8 十四个高频问答

**Q1：泛型擦除是什么？为什么要擦？**
擦除是 javac 在生成字节码时把类型实参丢掉，只保留原始类型和上界。两个原因：① 兼容 JDK 5 之前的字节码和已有库（`List` 与 `List<String>` 必须能互相赋值）；② 避免 JVM 为每个泛型实参生成新类（`List<String>` 一个类、`List<Integer>` 又一个），那会引发 C++ 模板式的代码膨胀。代价是运行期拿不到实参、不能 `new T[]`、不能 `instanceof List<String>`、不能按泛型重载。

**Q2：那框架怎么拿到泛型？**
从 `Signature` 属性拿。`Field.getGenericType()`、`Method.getGenericParameterTypes()`、`Class.getGenericSuperclass()` 读的都是它。前提是泛型出现在**声明处**（类、字段、方法签名），而不是方法体里的局部变量。所以 `TypeReference` 必须用匿名子类把实参"落到一个真实类的父类签名里"。

**Q3：桥方法是什么，什么时候会出现？**
当子类重写泛型父类方法、且擦除后的签名不一致时（典型是返回值协变、参数是类型变量），编译器生成一个 `ACC_BRIDGE | ACC_SYNTHETIC` 的转发方法，签名对齐父类，内部 `invokevirtual` 到真实方法。出现场景：泛型父类的子类、泛型接口的实现类、协变返回值。副作用是反射方法数变多、AOP 切点可能匹配错。

**Q4：为什么 `List<String>` 不能 `new T[]`？**
数组是**协变且运行期带类型**的（`String[]` 的 `getClass()` 是 `[Ljava.lang.String;`）。擦除后 `new T[]` 编译成 `new Object[]`，但你要的是 `String[]`，两者运行期类型不同，`ArrayStoreException`/CCE 就会在别处炸。所以编译器直接禁止。替代：`(T[]) new Object[n]`（带 unchecked，危险）、`Array.newInstance(clazz, n)`（传 Class，安全）。

**Q5：反射为什么慢？慢多少？**
三个成本：参数装 `Object[]` + 基本类型装箱、每次调用的访问权限检查、调用点对 JIT 不透明导致难以内联。但"慢 100 倍"是没预热的结论。预热后实测约 7 倍（7.5ns vs 1.1ns）。真正贵的是**每次 `getDeclaredMethod`**（微秒级，遍历方法表），所以框架都缓存 `Method`。

**Q6：inflation 是什么？**
`sun.reflect.inflationThreshold=15`。前 15 次调用走 `NativeMethodAccessorImpl`（JNI），第 16 次起动态生成 `GeneratedMethodAccessor` 字节码类替换掉委托对象。设计动机：反射大多只调几次，生成字节码不值；少数热点才值得。`-Dsun.reflect.noInflation=true` 跳过判断直接用字节码版。

**Q7：MethodHandle 和反射的区别？**
反射是"运行期查找 + 每次检查"的 API，面向"我不知道要调什么"；MethodHandle 是"编译期就确定类型、运行期只做一次链接"的句柄，面向"我要反复调同一个"。`invokeExact` 要求类型精确匹配（否则 `WrongMethodTypeException`），能被 JIT 内联，接近直接调用；`invoke` 会自动 `asType`，慢一些。MethodHandle 还有 `VarHandle`（替代 `Unsafe` 做字段原子访问）和 `LambdaMetafactory`（把句柄变成接口实现）两条延伸。

**Q8：注解的三种 Retention 区别？**
`SOURCE` 不进 class（编译器/APT 用，如 `@Override`、Lombok）；`CLASS` 进 `RuntimeInvisibleAnnotations`，反射看不见，字节码工具（ASM/Javassist）能读；`RUNTIME` 进 `RuntimeVisibleAnnotations`，反射可读（Spring 的注解全是这个）。默认是 `CLASS` —— 这是最常见的"注解不生效"根因。

**Q9：Spring 怎么读注解？为什么不用反射？**
`@ConditionalOnClass` 要求"类不存在也不能报错"，反射做不到（`Class.forName` 直接抛）。Spring 用 ASM 的 `MetadataReader` 直接解析 class 文件的注解属性，不加载类；条件通过后才 `Class.forName`。好处是省掉类加载/链接/初始化的开销，扫几千个类也就百毫秒级。

**Q10：JDK 动态代理为什么只能代理接口？**
生成的 `$Proxy0 extends java.lang.reflect.Proxy implements 你的接口`。Java 单继承，既然已经继承了 `Proxy`，就不能再继承你的目标类，只能通过实现接口来"变成"目标类型。这也是"注入实现类而非接口会失败"的根因。

**Q11：CGLIB 怎么实现的？有什么限制？**
用 ASM 生成目标类的**子类**，覆写所有非 `final` 的 public/protected 方法，方法体里先判是否绑定了 `MethodInterceptor`：绑了就装箱参数、调 `intercept(...)`；没绑就直接 `invokespecial` 调父类实现（实测反编译确认了这条"零开销快路"）。`invokeSuper` 通过 `MethodProxy` + FastClass（int 下标 + switch）回调父类，避免反射。限制：不能代理 `final` 类与 `final`/`private`/`static` 方法（`private` 不参与重写）；因为是子类，构造器必须能调到父类构造器，父类没有无参构造时要用 `Enhancer` 传参或 `Objenesis` 绕过（Spring 就带了这个依赖）；每次 `create()` 生成 3 个 class（代理类 + 两个 FastClass），Metaspace 有成本。

**Q12：Spring Boot 为什么默认 CGLIB？**
`spring.aop.proxy-target-class=true`。核心动机是消除"注入接口能拿到代理、注入实现类拿不到"的不一致：JDK 代理下容器里是 `$Proxy0`，它不 `instanceof UserServiceImpl`，按类型注入实现类直接失败；CGLIB 继承原类，两种注入都成立。代价是多生成一个类（启动慢一点）、final 类/方法失效。

**Q13：`this` 调用为什么绕过代理？**
`this` 是原始对象的引用，`this.method()` 编译成 `invokevirtual` 直接打到本对象，根本不经过代理实例。代理只在"外部通过引用调用"时生效。这是 `@Transactional`、`@Async`、`@Cacheable` 自调用失效的统一原因。解法：自注入、`AopContext.currentProxy()`、拆类。

**Q14：怎么判断一个对象是不是代理？**
`AopUtils.isAopProxy(obj)` / `isJdkDynamicProxy` / `isCglibProxy`（Spring 工具类）；纯 JDK 层面用 `Proxy.isProxyClass(obj.getClass())` 判 JDK 代理、类名含 `$$` 判 CGLIB（不可靠）。注意 `obj.getClass()` 拿到的是代理类，要拿原类注解必须 `AopUtils.getTargetClass(obj)`。

---

## L9 三道模拟面试题

### 题 1（基础偏上）· 为什么我的 `@Cacheable` 在同一个类里调用不生效？

**不及格回答**：缓存没配好 / 注解没加对 / 重启试试。

**及格回答**：Spring 的缓存是 AOP 实现的，AOP 靠代理。`this.method()` 不经过代理，所以拦截器不执行。

**良好回答**：在及格的基上补机制 —— JDK 代理时容器里的 Bean 是 `$Proxy0`，调用顺序是 `Proxy.method → InvocationHandler → 拦截器链 → 目标方法`。而 `this` 是目标对象本身的引用，`invokevirtual` 直接打到目标方法，代理这一层被跳过。这个限制对 `@Transactional`、`@Async`、`@Cacheable`、`@Validated` 全部成立，因为它们共用同一套 AOP 基础设施。

**优秀回答**：再补三件事 —— ① 为什么 Spring 不"自动修复"：因为要修复就得让目标方法知道自己被代理（注入 self 引用），那会破坏代理的透明性，并且循环依赖；② 三种解法及代价：自注入（最干净，注意 `@Lazy` 或 `@EnableAspectJAutoProxy(exposeProxy=true)`）、`AopContext.currentProxy()`（侵入代码，依赖 ThreadLocal）、拆成独立 Bean（架构上最优但改动大）；③ 边界：CGLIB 代理下 `private` 方法也不生效，`final` 方法直接被跳过，`static` 更不用说 —— 判定标准是"这个方法是否可被覆写、调用是否经过代理实例"。

---

### 题 2（进阶）· 反序列化一个 `List<User>` 时拿到的全是 `LinkedHashMap`，为什么？

**不及格回答**：Jackson 有 bug / 换个库试试。

**及格回答**：泛型被擦除了，Jackson 不知道元素类型，只能按默认的 `Object` 反序列化成 `LinkedHashMap`。

**良好回答**：补上"擦除不等于全丢" —— 泛型实参保留在 class 文件的 `Signature` 属性里，反射可以通过 `getGenericSuperclass()` / `getGenericParameterTypes()` 读到。所以解法是让实参出现在某个类的签名里，这就是 `new TypeReference<List<User>>() {}` 那对花括号的作用：它生成了一个匿名子类，父类签名被写成 `TypeReference<List<User>>`。

**优秀回答**：再往下讲三层 —— ① 为什么不能省花括号：`new TypeReference<List<User>>()` 的实参在使用处，擦完即消失，`getGenericSuperclass()` 只能看到 `Object`（实验实测：`plain = class java.lang.Object`）；② 更通用的写法：`mapper.getTypeFactory().constructCollectionType(List.class, User.class)`，不依赖签名，适合类型在运行期才决定的场景；③ 同类机制的家族：Guava `TypeToken`、Spring `ParameterizedTypeReference`、`@RequestBody List<User>`（Spring 从方法签名的 `MethodParameter` 取 `getGenericParameterTypes()`，走的是同一条路）。最后补一句 Trade-off：签名方案优雅但只在"编译期已知类型"时可用，RPC 泛化调用这类场景只能用 `constructXxxType` 手工构造。

---

### 题 3（架构）· 让你设计一个 RPC 框架的方法调用层，用反射还是字节码？

**不及格回答**：用反射，简单。

**良好回答**：反射简单通用，字节码快但复杂；先反射，性能不够再优化。

**优秀回答**：分三段给方案 + 数据 ——
① **启动期用反射做"元数据采集"**：扫描接口、缓存 `Method` 到 `Map<String, Method>`（key 用 `interfaceName#methodName#desc`，不要用 `Method` 对象），这一步只做一次，毫秒级可接受。
② **运行期不要用 `Method.invoke`**：单次 7.5ns 看似不高，但 RPC 一次调用的总开销在微秒级，反射占比不大 —— 真正的问题是 `invoke` 的 `Object[]` 分配和装箱，在 10 万 QPS 下就是每秒 10 万个短命数组，抬高 YGC 频率。用 `LambdaMetafactory` 把 `Method` 转成函数式接口（实测 1.9ns、零分配），或者用 ASM/ByteBuddy 生成一个 `Invoker` 类做静态转发，这是 Dubbo（`JavassistProxyFactory`/`JdkProxyFactory`）、gRPC、Spring 6 的共同做法。
③ **兜底与可观测**：方法句柄链接失败（类型不匹配、`invokeExact` 抛 `WrongMethodTypeException`）时降级到反射，并打点；对 `final` 类、`private` 方法、JDK 内部类（`InaccessibleObjectException`）要有明确的降级路径，否则升级 JDK 就是线上事故。

**加分项**：指出"字节码生成的类会占用 Metaspace，且每个接口一套" —— 接口数量上千的网关要评估 `Metaspace` 预算（每类约 1~3KB），必要时限制生成粒度或用 `WeakCache` 让类可卸载。

---

## L10 一句话收尾

**泛型是给编译器的契约，注解是给框架的便签，反射是把便签读出来的手，动态代理是照着便签伪造一个替身。**

四者共同的底座只有一句话：**运行期只有一个 `Class` 对象，所有类型信息都要么被擦掉，要么被塞进 class 文件的属性里。** 想清楚这一点，"为什么 TypeReference 要花括号""为什么 CGLIB 代理不了 final""为什么 JDK 17 上老框架崩"就不再是三个孤立的知识点。

> 下期预告：**Java 模块化与工程化边界**（`module-info` 的 `requires/exports/opens` 三件套、JPMS 与 classpath hell 的真实代价、`jdeps` 查循环依赖、Multi-Release JAR 与 shade 冲突、为什么大厂内部更愿意用"包约定 + ArchUnit"而不是 JPMS）。如果时间允许，会顺带做一次 `jdeps` 实测，把"依赖泥球"画出来。
