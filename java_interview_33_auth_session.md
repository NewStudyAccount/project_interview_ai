# Java 面试深挖 33 · 认证授权与会话

> 副标题：从「token 到底是谁签的」到「无状态省了什么、又贵在哪」
> 本期数字全部给算式：会话复制带宽 180MB/s、JWT 上行带宽 6.8MB/s、黑名单 1.2MB、bcrypt 与 MD5 的 10 万倍差距、PKCE 43 字符、刷新惊群 1 万倍放大。协议事实以 **RFC 9700（2025-01，BCP 240）** 与 **OAuth 2.1 草案（draft-17，2026-10-31）** 为准，凡版本敏感处标注来源。

---

## 01 先定性：三个问题、三道闸门

「认证、授权、会话」是三个词，不是三个同义词。它们回答三个不同的问题，失败模式完全不同，负责的组件也不同：

- **认证 Authentication（AuthN）**：你是谁。答案是身份标识（`sub` / userId）。
- **授权 Authorization（AuthZ）**：你能干什么。答案是权限（role / scope / 数据归属）。
- **会话 Session**：怎么不用每一步都重答前两问。答案是凭证（Cookie SessionId / JWT / PASETO）。

面试里最高频的区分度问题就是 **401 与 403 的边界**：

> **401 = 你没证明你是谁（认证失败或凭证过期）；403 = 你证明了你是谁，但你没资格做这件事。**
> 把数据级越权（IDOR）返回 200 + 空数据，或返回 404，都是语义错误——它掩盖了攻击信号，也让客户端无法区分「不存在」和「无权」。

三道闸门的分工与失败码：

| 问题 | 学名 | 答案载体 | 失败码 | 谁负责 |
|---|---|---|---|---|
| 你是谁 | 认证 AuthN | 账密 / 短信 / OAuth id_token | 401 | 身份提供方 IdP |
| 你能干什么 | 授权 AuthZ | role / scope / 权限点 / 数据归属 | 403 | 资源服务 / 网关（粗粒度） |
| 怎么记住 | 会话 Session | Cookie SessionId / JWT / PASETO | 401（过期） | 会话存储 / 令牌签发方 |

一条容易被追问的边界：**「网关验过了，服务里还要不要再验？」**——认证可以在网关做一次（验签、验 `exp`、验 `iss`/`aud`），但**数据级授权必须在资源服务做**，因为只有它知道「这条记录属于谁」。网关验的是「你是不是合法用户」，不是「你对这条数据有没有权限」。

---

## 02 Session 的四种存法：一致性、容量与故障账

服务端 Session 本质是一张 `Map<sessionId, UserContext>`。放在哪里，是认证架构的第一个岔路。四种主流做法各有各的账单：

1. **粘性会话（LB ip_hash / cookie hash）**：会话只存在命中的那个节点内存。
2. **会话复制（Tomcat Cluster / jgroups 全网状广播）**：每个节点都有全量副本。
3. **集中式存储（Redis / DB）**：节点无状态，会话在共享存储。
4. **客户端存储（JWT / PASETO）**：服务端干脆不存，状态写进令牌。

- **复制的带宽账（这是全节最有杀伤力的数字）**：设 100,000 个活跃会话、单个会话 2KB、每秒 1,000 次会话变更（登录、写属性、续期）、集群 10 个节点全网状复制：

- 单节点状态量：100,000 × 2KB = **200MB**
- 单节点出向带宽：1,000 次/s × 2KB × 9 个同伴 = **18MB/s**
- 全集群总流量：18MB/s × 10 节点 = **180MB/s**（约 1.44Gbps，这还只是会话复制，不含业务流量）

**集中式的账**：同样是 200MB，但只存一份；读 10,000 次/s × 2KB = **20MB/s**；Redis 单机 10 万 ops/s，水位 20%——这才是大多数系统的正解。代价是 Redis 成为单点：它挂了全员掉线，必须配双活 + 本地降级。

**粘性会话的故障账**：4 节点滚动发布，每批摘 1 节点 → **25% 用户掉线一次**；蓝绿全量切流 → **100% 掉线**。任何「重启就掉线」的投诉，九成是粘性会话。

| 做法 | 状态位置 | 一致性 | 节点故障/发布 | 扩容 | 适用 |
|---|---|---|---|---|---|
| 粘性会话 | 节点内存 | 强（单节点内） | 掉线 | 需重分布 | 单体、小规模 |
| 会话复制 | 每节点全量 | 最终一致 | 可存活 | O(N²) 广播 | 节点少（≤4） |
| 集中式（Redis） | 共享存储 | 强 | 可存活 | 水平扩 Redis | **主流** |
| 客户端（JWT） | 无（令牌内） | 强（自带） | 可存活 | 无限 | 多端、跨域、高 QPS |

---

## 03 JWT 三段解剖：base64url 不是加密

一个真实的 JWT 长这样（RS256）：

```
eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9
.
eyJzdWIiOiJ1LTEyMyIsInJvbGUiOiJhZG1pbiIsImV4cCI6MTc5OTAwMDAwMH0
.
SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

三段各自的角色：

- **header**（约 36 字节）：`alg`（签名算法）与 `typ`，base64url 编码。
- **payload**（claim，几十到几百字节）：`sub` / `iat` / `exp` / `aud` / `scope` / 自定义业务字段，base64url 编码。
- **signature**：对 `header.payload` 的签名。RS256/ES256 用私钥签、公钥验；HS256 用共享密钥。

三个必考事实：

- **base64url 是编码，不是加密。** 任何人在浏览器控制台 `atob()` 一下就能读出 payload。往里放手机号、身份证、余额 = 裸奔。要保密就得 JWE（加密），而不是 JWS（签名）。
- **签名保证的是完整性与来源，不是身份。** 只有当验签方正确选择了算法与密钥（见 04），签名才等于「这个 token 是我签的」。
- **`exp` 是自带的，但服务端不查库也能放行。** 这就是「无状态」的收益，也正是它无法吊销的根源（见 05）。

**不验签只读 payload**（展示「编码可逆」这件事本身）：

```java
// 不要用这种方式做鉴权！它只是演示 payload 可被任何人读出
static String readPayloadUnverified(String jwt) {
    String[] p = jwt.split("\\.");                 // 三段按 . 切
    byte[] raw = Base64.getUrlDecoder().decode(p[1]);   // base64url 解码
    return new String(raw, StandardCharsets.UTF_8);     // 明文 JSON
}
// 输入 eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1LTEyMyIsInJvbGUiOiJhZG1pbiJ9.xxx
// 输出 {"sub":"u-123","role":"admin"}   ← 根本不需要密钥
```

用 jjwt 做**真正的**验签解析（强制算法白名单）：

```java
Jws<Claims> jws = Jwts.parser()
    .requireIssuer("https://idp.example.com")      // iss 必须匹配
    .requireAudience("order-api")                  // aud 必须匹配
    .clockSkewSeconds(30)                          // 时钟偏移容忍
    .keyLocator(header -> {                        // 密钥来自本地配置，绝不来自 token
        if (!"RS256".equals(header.getAlgorithm())) {
            throw new JwtException("alg not allowed: " + header.getAlgorithm());
        }
        return publicKey;                          // 拒绝 jku/jwk 指向外部
    })
    .parseSignedClaims(token);
Long exp = jws.getBody().getExpiration().getTime();  // exp 校验由库完成
```

- **体积账（数形结合）**：一个中等复杂度的 JWT：
| 段 | 典型字节 | 说明 |
|---|---|---|
| header | 36 | `{"alg":"RS256","typ":"JWT"}` base64url 后 |
| payload | 300 | 6~8 个 claim |
| signature | 344 | RSA-2048 签名 256B → base64url 344B |
| **合计** | **约 680B** | 每个请求都要带上 |

对比 SessionId Cookie（32 字节随机串）：

- 10,000 QPS × 680B = **6.8MB/s** 上行（JWT）
- 10,000 QPS × 32B = **0.32MB/s** 上行（SessionId）
- **差 21 倍**。HTTP/2 的 HPACK 能压缩重复头部，但移动端弱网下，Cookie 大户依然是首字节延迟的元凶之一。ES256（ECDSA P-256）签名只有 64B → base64url 86B，能把总长压到 420B 左右——**选 ES256 比 RS256 省 38% 体积**，这是多端高 QPS 场景一个被低估的优化点。

---

## 04 签名验的是什么：算法混淆攻击

经典攻击（CVE-2015-9235 一族）的完整链条：

1. 服务端用 **RS256**：私钥签发，公钥验签。
2. 攻击者拿到一个合法 token，把 header 的 `alg` 改成 **HS256**，删掉原签名，然后用**服务端的 RSA 公钥当 HMAC 密钥**，给伪造的 payload（`role=admin`）签名。
3. 服务端解析 header 时若「按 token 里说的 alg 选验证算法」，并把公钥当对称密钥传给 HMAC → **验签通过**，攻击者拿到管理员。

问题的根子在一句话：**JWT 的验签算法不应该由 token 自己决定。** token 说「我是 HS256」，服务端就用 HS256 验，等于让攻击者自己挑考卷。

三道防线，缺一不可：

- **alg 白名单**：只接受 RS256 / ES256，显式拒绝 `none` 与 HS256（除非你本来就用对称密钥，且与公钥完全分开管理）。
- **密钥隔离**：公钥绝不能出现在对称签名的密钥材料里。
- **claim 全量校验**：`iss` / `aud` / `exp` / `nbf` 逐个验，别只看签名。

另外两类同源攻击：

- **`alg=none`**：早期 JWT 库允许「无签名」，攻击者直接把签名段清空。现代库默认拒绝，但自研解析要显式拦。
- **`jku` / `jwk` 注入**：header 里可以写「公钥去这个 URL 取」，攻击者指向自己的服务器返回自己的公钥 → 自签自验。**永远不允许从 token 里读取密钥地址**，密钥只能来自本地配置或受信 JWKS 端点。

---

## 05 无状态的账单：吊销的四种方案

「无状态」省掉的是什么？——**每请求一次会话查库**。10,000 QPS 就是 10,000 次/s 的 Redis 读。这是真金白银。

贵的是什么？——**在 `exp` 到来之前，你没有任何办法让一个已签发的 token 失效。** 用户被封禁、改了密码、管理员点了「强制下线」，token 照样能用到过期。这是所有「无状态鉴权」面试题的题眼。

四个方案，按「无状态纯度」从高到低：

| 方案 | 做法 | 吊销延迟 | 状态成本 | 适用 |
|---|---|---|---|---|
| 短 access + 刷新 | access 15min / refresh 7d | 最长 15min | 仅 refresh 表 | **默认首选** |
| 黑名单 | Redis 存被吊销的 `jti` | 实时 | 每次吊销 1 条 | 改密码/封禁 |
| 白名单 | 服务端存「有效 token 表」 | 实时 | 每会话 1 条 | 等于退回 Session |
| 版本号 | token 嵌 `pwd_ver`，服务端存用户当前版本 | 实时 | 每用户 1 个整数 | 踢下线/改密码 |

**黑名单的内存账**：100 万个被吊销的 `jti`，Redis SET 存 8 字节整数 → **8MB**。用布隆过滤器（1% 误判率）：

```
m = -n·ln(p) / (ln2)² = -10^6 × ln(0.01) / 0.4805 = 9.58×10^6 bit ≈ 1.2MB
```

**省 6.7 倍，但换来 1% 误判**（合法 token 被误杀 → 需要二次回查确认）。误判率压到 0.1% 需要 1.8MB，压到 0.01% 需要 2.4MB——**布隆的内存与误判率是对数关系，不是线性**。

**版本号方案的账**：100 万用户 × 1 个 8 字节版本号 = **8MB**，与黑名单同量级，但它覆盖的是「这个用户的所有 token」而不是「这一次吊销」，对「改密码必须全端下线」这类需求是更优解。

> - **一句话记法**：access token 短命 + refresh token 可吊销 + 版本号处理「改密码/封禁」，三件套组合是工程上的标准答案。纯黑名单只在「单次会话踢下线」时用。

---

## 06 双令牌刷新时序：惊群、轮换与重用检测

标准形态是双令牌：**短命的 access token**（无状态验签，15 分钟）+ **长命的 refresh token**（只存在授权服务器，7~30 天，可吊销）。刷新这件事有三个必须画时序的坑：

**坑一：正常刷新要走后台单飞（single flight）**
不是「请求失败了才去刷新」，而是拦截器发现 `exp` 将近就主动续期，且**同一个 refresh token 在同一时刻只允许一个线程真正发刷新请求**，其余并发请求等待同一个 Future 结果。

**坑二：刷新惊群（thundering herd）**
access token 过期的那一瞬间，1 万个并发请求同时发现过期，如果每个都去刷新：

- 瞬时刷新请求 = **10,000 次**，授权服务器被打爆
- 而且多半都用同一个 refresh token → 后续的会被拒绝（见坑三），用户大面积 401

单飞之后：**1 万次 → 1 次**，放大约 **1 万倍**。这就是为什么「刷新也要加锁」是道区分度题。

**坑三：轮换 + 重用检测（rotation & reuse detection）**
每次刷新都要签发新的 refresh token 并作废旧的。若一个**已经作废的 refresh token 再次被使用**，只可能是两种情况：网络重放，或者 token 被盗、攻击者与真实用户在轮流刷新。安全上不能赌是前者——标准做法是**吊销整个 token family**（这一串刷新链上的全部 token），用户被迫重新登录。

- 数字：一个 family 里可能有 50 个历史 refresh token，重用一次全部作废。
- 这是「安全 > 体验」的典型案例，面试要能说出「宁可误杀一次登录，也不放过一次泄漏」。

**单飞刷新的代码骨架**（一个 refresh token 只允许一个在途请求）：

```java
public class TokenRefresher {
    private final ConcurrentHashMap<String, CompletableFuture<Tokens>> inflight =
            new ConcurrentHashMap<>();

    public Tokens refresh(String refreshToken) {
        return inflight.computeIfAbsent(refreshToken, k ->
                CompletableFuture.supplyAsync(() -> doRefresh(k))   // 只有一个线程真发请求
        ).handle((r, e) -> {
            inflight.remove(refreshToken);                          // 完成后清掉，允许下次刷新
            if (e != null) throw new CompletionException(e);
            return r;
        }).join();
    }
}
// 1 万并发同时发现过期 → 只有 1 次真正的刷新请求，其余 9999 个等同一个 Future
```

- **一句话记法**：三条时序（正常 / 并发竞争 / 重用检测）在图 6 里并排画，是本期最值得背的一张图。
---

## 07 OAuth2：四个角色、四种模式、为什么必须 PKCE

OAuth2 不是「登录协议」，是**委托授权**协议：用户把自己的部分权限委托给第三方 App，而不交出密码。

**四个角色**（RFC 6749 §1.1）：

| 角色 | 是谁 | 例子 |
|---|---|---|
| Resource Owner | 资源属主 | 用户本人 |
| Client | 想拿权限的应用 | 第三方小程序 / SPA |
| Authorization Server | 发令牌的 | 微信开放平台 / 公司 IdP |
| Resource Server | 存资源的 | 你的业务 API |

**四种经典授权模式与现状**（这条表格是面试必背，因为「为什么隐式模式不能用了」是高频追问）：

| 模式 | 用途 | 现状 |
|---|---|---|
| 授权码 Authorization Code | 服务端 Web / 移动 App | **唯一推荐**，必须配 PKCE |
| 隐式 Implicit | 浏览器 SPA | **RFC 9700 明确禁用**，OAuth 2.1 草案删除 |
| 密码 ROPC | 第一方 App 直接收账密 | **RFC 9700 禁用**，OAuth 2.1 草案删除 |
| 客户端凭证 Client Credentials | 机器对机器（M2M） | 保留 |

**为什么隐式和密码模式死了**：

- 隐式：token 直接放在 URL fragment 回传，会进浏览器历史、Referer、页面上任何脚本都能读走。
- 密码：用户把真实密码交给第三方客户端，既训练用户在陌生界面输密码，也让客户端拿到了不该拿的长期凭据。

**为什么要 PKCE（RFC 7636）**——这是本节的核心，也是一道完整的时序题：

授权码在 `redirect_uri` 回跳时会经过浏览器历史 / Referer / 移动 App 的自定义 scheme（`myapp://callback` 可以被恶意 App 抢注）。拦截到 `code` 的人，如果没有 `client_secret`（**公共客户端本来就没有**）就能去换 token。

PKCE 的做法是「先押题、后对答案」：

1. 客户端生成 `code_verifier`：43~128 字符的高熵随机串。
2. 请求授权时只发 `code_challenge = BASE64URL(SHA256(ASCII(code_verifier)))`（固定 43 字符）。
3. 拿 `code` 换 token 时，必须出示原始 `code_verifier`。
4. 授权服务器自己算一遍 SHA256 比对，一致才发 token。

拦截到 `code` 的人不知道 `verifier` → 换不到 token。**S256 是唯一可接受的方法**（`plain` 等于把 verifier 先发出去，形同虚设）；OAuth 2.1 草案已移除 `plain`。

**生成 PKCE 三件套的代码**：

```java
// 1. verifier：43~128 字符的高熵随机串
byte[] rnd = new byte[32];
new SecureRandom().nextBytes(rnd);
String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd); // 43 字符

// 2. challenge = BASE64URL(SHA256(ASCII(verifier)))，固定 43 字符
byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(verifier.getBytes(StandardCharsets.US_ASCII));
String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

// 3. 换 token 时把 verifier 原样带上；授权服务器自己算一遍 SHA256 比对
// 授权请求: code_challenge=challenge&code_challenge_method=S256
// token 请求: code_verifier=verifier   ← 拦截到 code 的人拿不出这一项
```

- **版本事实（报版本号必须准）**：
- RFC 9700（2025-01，BCP 240）是**当下可引用的正式安全基线**：公共客户端必须用 PKCE，建议所有客户端都用，`redirect_uri` 精确匹配，禁止 token 出现在 query string。
- **OAuth 2.1 截至 2026-10 仍是 IETF 草案**（draft-17，2026-10-31；WG 里程碑计划 2026-12 提交 IESG），它把 RFC 9700 的建议变成了唯一合规路径：授权码流程一律要 PKCE、删除隐式与密码模式、`plain` 删除、`redirect_uri` 精确匹配、公共客户端的 refresh token 必须轮换或做发送方绑定。
- 公共客户端的 refresh token 还受 RFC 10017（浏览器端应用，2026-08 成为 BCP）的生命周期约束。
- 发送方绑定的进阶方案：DPoP（RFC 9449）把 token 绑到客户端密钥上，被偷走也无法在别的设备重放。

**PKCE 的数字**：

- `code_verifier`：43~128 字符（`[A-Za-z0-9-._~]`）。
- `code_challenge`：S256 下固定 43 字符（SHA-256 的 32 字节 → base64url 43 字符）。
- 授权码 `code`：有效期 60~600 秒，**一次性使用**。
- `redirect_uri`：必须**精确字符串匹配**，`https://app.example.com/cb` 与 `https://app.example.com/cb/extra` 是两个不同的 URI，前缀匹配是大坑。

---

## 08 网关鉴权边界：能做什么、不能做什么

「网关统一鉴权」是微服务时代的标配，但它的边界画错了就是事故。先说清楚 **80% 与 20% 的分工**：

- **网关该做的（粗粒度，做一次就够）**：
- 认证：验签、验 `exp`、验 `iss` / `aud`，把身份转成可信 Header 下发（`X-User-Id`，且**必须剥掉外部传入的同名 Header**）。
- 粗粒度授权：路由级（`/admin/**` 要 `ROLE_ADMIN`）、租户级、`scope` 级。
- 限流、熔断、审计日志、灰度染色。

- **网关做不了的（细粒度，必须下沉到资源服务）**：
- **数据级权限**：这条订单是不是你的。网关只知道「你是 userId=123」，不知道「order#45678 属于谁」。
- **方法级**：`@PreAuthorize("hasPermission(#id, 'order', 'read')")`。
- **复杂业务规则**：金额分级审批、字段级脱敏、部门数据可见性。

**经典漏洞：水平越权（IDOR）**。网关只验了 token 合法、没验数据归属，攻击者改 URL 里的 id 就能看到别人的资源。

> - **这个漏洞不是概率事件，是必然事件**：一个电商 100 万用户 × 人均 50 单 = 5000 万个订单 id，只要有一个接口直接按 id 取数不做归属校验，撞上「别人的单」是时间问题，不是运气问题。

修法只有一句话：**取数的同时带上属主条件**（`where id=? and user_id=?`），而不是「先查出来、再判断是不是你的」——后者在并发与缓存场景下会漏。

代码骨架（Spring Security 资源服务器 + 数据级校验）：

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
          .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
          .authorizeHttpRequests(a -> a
              .requestMatchers("/admin/**").hasRole("ADMIN")
              .anyRequest().authenticated());
        return http.build();
    }
}

// 数据级：网关管不了，必须在服务里
@Service
public class OrderService {
    @PreAuthorize("hasPermission(#id, 'order', 'read')")
    public Order get(long id) {
        return orderMapper.selectByIdAndOwner(id, currentUserId());
    }
}

@Component("perm")
public class DbPermissionEvaluator implements PermissionEvaluator {
    public boolean hasPermission(Authentication a, Object id, String type, Object perm) {
        return orderMapper.countOwner((Long) id, a.getName()) > 0;
    }
    public boolean hasPermission(Authentication a, Serializable id, String type, Object perm) {
        return hasPermission(a, (Object) id, type, perm);
    }
}
```

---

## 09 密码存储四代：从 MD5 到 Argon2id

密码学里有一条反直觉的原则：**好的密码哈希函数要「慢」，而且慢得可控。** 因为服务器只算一次，攻击者要算十亿次。

四代演进（以破解 8 位「小写字母+数字」为例，空间 36^8 = 2.8×10^12）：

| 代 | 做法 | 单卡暴力速度 | 破解耗时 | 结论 |
|---|---|---|---|---|
| 0 | 明文 | — | — | 泄库即全军覆没 |
| 1 | MD5 / SHA1 | GPU 约 10^10 次/s | **约 280 秒** | 等于明文 |
| 2 | 加盐 MD5 | 同上 | 280 秒（盐只防彩虹表） | 防批量，不防定向 |
| 3 | bcrypt / scrypt（慢哈希） | GPU 约 10^5 次/s | **约 324 天** | 今天的基本盘 |
| 4 | Argon2id（内存硬） | 抗 GPU / ASIC | 进一步上升 | 新系统首选 |

**算式**（这是全节的说服力来源）：

- 空间：26 + 10 = 36 个字符，长度 8 → 36^8 = **2.8×10^12**
- MD5（GPU 10^10/s）：2.8×10^12 ÷ 10^10 = **280 秒**
- bcrypt cost=12（GPU 约 10^5/s）：2.8×10^12 ÷ 10^5 = 2.8×10^7 秒 = **约 324 天**
- **差距 10 万倍**，而这一切只换来「登录慢 400ms」。

**bcrypt 的 cost 参数**：耗时随 `2^cost` 线性放大。cost=10 约 100ms，cost=12 约 400ms，cost=14 约 1.6s。调参原则：**让一次登录验证占用 100~400ms**，随着硬件变快逐年上调 cost。

**加盐（salt）的作用说清楚**：不是加密，是让每个用户的哈希值不同——这样彩虹表失效、批量破解退化成逐个破解。盐必须每用户唯一且与哈希一起存储。

**Spring Security 的工程做法**：`DelegatingPasswordEncoder`，哈希值自带前缀，可平滑升级算法：

```java
// 存储形态：{bcrypt}$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW
PasswordEncoder enc = PasswordEncoderFactories.createDelegatingPasswordEncoder();
String stored = enc.encode("s3cret");        // 自动用 bcrypt
boolean ok    = enc.matches("s3cret", stored);
// 前缀还有 {argon2} {sha256} {noop} {pbkdf2}，换算法不用迁移老数据
```

一个常见追问：**「为什么不用 SHA-256 加盐？」**——SHA-256 是快哈希，GPU 上仍是 10^9~10^10/s 量级，加盐只防彩虹表不防暴力。慢哈希（bcrypt/scrypt/Argon2id）才是正解。

---

## 10 token 放哪里：存储位置 × 攻击面

「token 放 localStorage 还是 Cookie」是道送分题，也是一道送命题。答案取决于你要防谁。

| 存储位置 | XSS 能否读走 | CSRF 能否被利用 | 结论 |
|---|---|---|---|
| localStorage / sessionStorage | ✅ JS 直接读 | ❌ 不会自动携带 | 防 CSRF 不防 XSS |
| Cookie（非 HttpOnly） | ✅ JS 读 `document.cookie` | ✅ 自动携带 | 两头都怕，最差 |
| Cookie（HttpOnly + Secure + SameSite=Strict） | ❌ JS 读不到 | ❌ 跨站完全不带 | 最安全，跨站跳转体验差 |
| Cookie（HttpOnly + Secure + SameSite=Lax） | ❌ | ⚠️ 顶层 GET 导航会带 | **工程折中** |
| 内存变量（SPA 变量） | ✅ | ❌ | 刷新即丢，需配合刷新机制 |

**经典两难**：token 放 localStorage 防 CSRF、不防 XSS；放 Cookie 防 XSS、不防 CSRF。**答案是 HttpOnly Cookie + SameSite，再加 CSRF 双提交 token**（表单里放一个随机值，服务端比对 Cookie 里的同名值）。

另外三件必须做的事：

- **Session 固定攻击（Session Fixation）**：登录成功后**必须换一个新的 session id**。否则攻击者事先塞给用户的那个 id，在用户登录后就变成了合法会话，攻击者直接顶着用。
- **绝不把 token 放 URL query**：会进浏览器历史、Referer、Nginx 访问日志、CDN 日志、客服粘贴的链接。只放 Header（`Authorization: Bearer`）或 HttpOnly Cookie。
- **`SameSite=None` 必须配 `Secure`**，否则浏览器直接拒绝写入这条 Cookie（Chrome 80+ 行为）。

一句话记法：**「XSS 是万能钥匙，防住 XSS，其他所有方案才有意义。」** 内容安全策略（CSP）、输出转义、不拼接 HTML，是比 token 放哪更重要的第一道防线。

---

## 11 单点登出与踢下线：会话版本号

会话管理里有三个高频需求，它们的答案都在「版本号」这一个抽象上：

- **一、单点登出（SSO 全端下线）**：OIDC 的做法是 front-channel logout：登出时在各依赖方（RP）页面里嵌 iframe 逐个通知；或 back-channel logout（服务端直接回调）。工程上更常见的是：**授权服务器维护一张「会话表」，登出即删，各 RP 的 access token 因为短命，最多再活 15 分钟。**

**二、踢下线 / 改密码强制下线**
黑名单要为每一次吊销存一条记录；版本号只需要每个用户一个整数：

- token 里嵌 `sv`（session version / password version）。
- 服务端存 `user.sv`。
- 每请求比对 `token.sv == user.sv`，不等即 401。

**账**：100 万用户 × 8 字节 = **8MB**，与黑名单同量级，但它覆盖「这个用户的全部 token」而不是「这一次吊销」，对「改密码必须全端下线」这类需求是更优解。代价是每请求多一次读（可以用本地缓存 + 短 TTL 压到 1 秒级延迟）。

**版本号校验的代码骨架**：

```java
// 签发时：把当前版本写进 token
claims.put("sv", user.getSessionVersion());

// 每请求：本地缓存读版本（Caffeine 1s TTL，Redis 打底），不等即踢
int tokenSv = jwt.getBody().get("sv", Integer.class);
int liveSv  = sessionVersionCache.get(userId);      // 本地 1s + Redis
if (tokenSv != liveSv) {
    throw new BadCredentialsException("session revoked");   // 401，不是 403
}

// 改密码 / 管理员踢下线：版本号加一，存量 token 全部失效，无需遍历黑名单
redis.incr("user:" + userId + ":sv");
```

**三、多设备管理**
一张设备表：`device_id, user_id, last_login, token_ver, client_ip, ua`，支持「登出其他设备」（把其他设备的 `token_ver` 加一）与「查看登录历史」。

**顺带把 OIDC 三个 token 的职责说清**（面试常混）：

| token | 回答的问题 | 受众 | 能否当凭证用 |
|---|---|---|---|
| id_token | 你是谁（身份） | 客户端 | **不能**，它是给客户端看的身份证明 |
| access_token | 你能访问什么（凭证） | 资源服务器 | 能，但只对声明的 `aud` 有效 |
| refresh_token | 怎么再拿一个 | 授权服务器 | 不能直接访问资源 |

> - **最常见的错误**：拿 id_token 当 access_token 去调业务 API。两者受众（`aud`）不同，资源服务器应当拒绝 id_token。反过来，把 access_token 当身份展示也是错的，它可能没有 `email` 之类的 claim。
---

## 12 数字速查

| 项 | 数字 | 算式 / 出处 |
|---|---|---|
| 会话复制单节点状态 | 200MB | 100,000 会话 × 2KB |
| 会话复制单节点出向带宽 | 18MB/s | 1,000 变更/s × 2KB × 9 同伴 |
| 会话复制全集群带宽 | 180MB/s | 18MB/s × 10 节点 |
| 集中式 Redis 读带宽 | 20MB/s | 10,000 读/s × 2KB |
| Redis 会话水位 | 20% | 10,000 ops/s ÷ 100,000 ops/s |
| JWT 典型长度 | 680B | 36 + 300 + 344 |
| ES256 JWT 长度 | 约 420B | 签名仅 86B |
| JWT 上行带宽 | 6.8MB/s | 10,000 QPS × 680B |
| SessionId 上行带宽 | 0.32MB/s | 10,000 QPS × 32B，**差 21 倍** |
| 黑名单（SET） | 8MB | 100 万 jti × 8B |
| 黑名单（布隆 1% 误判） | 1.2MB | m = -n·ln p / (ln2)² |
| 布隆 0.1% 误判 | 1.8MB | 误判率与内存是对数关系 |
| 会话版本号 | 8MB | 100 万用户 × 8B |
| 粘性会话滚动发布掉线 | 25% | 4 节点摘 1 节点 |
| PKCE verifier 长度 | 43~128 字符 | RFC 7636 |
| PKCE challenge（S256） | 固定 43 字符 | SHA-256 32B → base64url |
| 授权码有效期 | 60~600 秒，一次性 | RFC 6749 |
| refresh 惊群放大量 | 10,000 倍 | 1 万并发 → 单飞后 1 次刷新 |
| token family 重用检测 | 一次重用废 50 个 | 家族整体吊销 |
| MD5 GPU 暴力速度 | 10^10 次/s | 8 位 36 字符集 280 秒 |
| bcrypt cost=12 GPU | 10^5 次/s | 同空间 **324 天**，差 10 万倍 |
| bcrypt cost 步进 | 2^cost 线性 | 10→100ms，12→400ms，14→1.6s |
| 401 vs 403 | 认证 vs 授权 | 越权必须 403，不能 200 空数据 |

---

## 13 十四问答

**Q1：401 和 403 到底怎么分？**
- 401 = 未认证：没有凭证、凭证无效、凭证过期。响应应带 `WWW-Authenticate`。
- 403 = 已认证但无权限：身份合法，但对这个资源/操作没资格。
- 一个常见错误是把「数据越权」返回 200 + 空数据或 404，这掩盖了攻击信号。正确是 403。

**Q2：JWT 的 payload 是加密的吗？**
- 不是。base64url 是可逆编码，任何人 `atob()` 即可读。要保密必须用 JWE（加密）而不是 JWS（签名）。签名只保证完整性与来源。

**Q3：为什么 JWT 无法吊销？**
- 因为无状态验签不查任何存储，token 在 `exp` 前一律放行。这是无状态的收益所对应的必然代价。工程解法：短 access + 可吊销 refresh + 版本号处理改密码/封禁。

**Q4：算法混淆攻击是什么？**
- 攻击者把 header 的 `alg` 从 RS256 改成 HS256，用服务端公钥当 HMAC 密钥签名。若验签算法由 token 说了算，就中招。防线：alg 白名单、公钥不进对称密钥材料、全量校验 `iss`/`aud`/`exp`。

**Q5：为什么隐式模式被废弃？**
- token 直接经 URL fragment 回传，会进浏览器历史与 Referer，页面上任何脚本可读。RFC 9700 禁用，OAuth 2.1 草案删除。SPA 应改用授权码 + PKCE。

**Q6：PKCE 解决什么问题？**
- 授权码被拦截后无法换取 token。客户端先发 `code_challenge = BASE64URL(SHA256(verifier))`，换 token 时出示 verifier，拦截者拿不到 verifier。S256 是唯一可接受方法，`plain` 已被 OAuth 2.1 移除。

**Q7：`redirect_uri` 为什么要精确匹配？**
- 前缀/通配匹配允许攻击者把回调地址指向自己可控的子路径或开放重定向，截走授权码。OAuth 2.1 强制精确字符串匹配。

**Q8：为什么要用双令牌？**
- access 短命（15 分钟）让泄漏窗口最小化且无状态验签扛 QPS；refresh 长命但只存服务端、可吊销，负责续期。单令牌要么长命（泄漏窗口大）要么短命（频繁重登）。

**Q9：刷新惊群怎么解？**
- 同一个 refresh token 在同一时刻只允许一个线程真正发刷新请求，其余等待同一个 Future（single flight）。1 万并发过期 → 1 次刷新，降 1 万倍。

**Q10：refresh token 轮换与重用检测是什么？**
- 每次刷新签发新的、作废旧的。若作废的 refresh token 再次被使用，判定为泄漏，吊销整个 token family。宁可误杀一次登录，也不放过一次泄漏。

**Q11：网关统一鉴权能做到什么程度？**
- 认证（验签、验 claim）与粗粒度授权（路由/租户/scope）可以；数据级归属、方法级、复杂业务规则必须下沉到资源服务。只在网关验 token 的系统几乎必然有 IDOR。

**Q12：为什么密码哈希要「慢」？**
- 服务器每次登录只算一次，攻击者要算十亿次。bcrypt 的 `2^cost` 让合法登录慢 100~400ms，却让 GPU 暴力从 280 秒拉到 324 天（10 万倍）。快哈希（MD5/SHA-256）加盐也不防定向暴力。

**Q13：token 放 localStorage 还是 Cookie？**
- 各有漏洞：localStorage 防 CSRF 不防 XSS；Cookie 防 XSS 不防 CSRF。答案是 HttpOnly + Secure + SameSite Cookie，再加 CSRF 双提交 token。而防住 XSS 才是第一道防线（CSP、输出转义）。

**Q14：id_token 和 access_token 有什么区别？**
- id_token 回答「你是谁」，受众是客户端，不能当凭证调 API；access_token 回答「你能访问什么」，受众是资源服务器。两者 `aud` 不同，拿 id_token 调 API 应当被拒绝。

---

## 14 三道模拟面试（四档对照）

### 题 1：JWT 被盗，用户改了密码，但攻击者手里的 token 还能再用 15 分钟。怎么处理？

- **不及格**：「把 token 过期时间改短一点」——没解决改密码的即时失效问题。
- **及格**：「用黑名单，改密码时把该用户的 jti 加进 Redis 黑名单」——方向对，但每次吊销都要存一条，且没说清粒度。
- **良好**：「access 短命 15 分钟 + refresh 可吊销；再加会话版本号：token 里嵌 `pwd_ver`，改密码时把用户 `pwd_ver` 加一，存量 token 全部失效。版本号每个用户只占 8 字节。」
- **优秀**：在良好基础上补三条：① 401 与 403 的语义要分开，token 版本不匹配返回 401；② 改密码之外还要「登出其他设备」（多设备表 + `token_ver`）；③ 指出纯黑名单对「改密码」这种高频批量场景的内存与写放大问题，给出版本号方案的取舍；④ 提到 refresh token 也要一并吊销，否则攻击者能自己刷新续命。

### 题 2：移动 App 走 OAuth 登录，授权码可能被恶意 App 抢走。怎么防？

- **不及格**：「加长 code 的长度」——code 长度与拦截无关。
- **及格**：「用 PKCE，换 token 时校验 code_verifier」——说对了名字，没说清机制。
- **良好**：「PKCE（RFC 7636）：生成 43~128 字符 verifier，只发 `code_challenge = BASE64URL(SHA256(verifier))`，换 token 时出示 verifier，S256 而不是 plain。再配 `redirect_uri` 精确匹配，防自定义 scheme 被抢注。」
- **优秀**：在良好基础上补：① 说明 code 是一次性的、有效期 60~600 秒，拦截窗口本就极小，PKCE 是把「窗口」变成「即使拿到也没用」；② 指出公共客户端没有 `client_secret`，所以 PKCE 不是可选项而是必需项（RFC 9700 对公共客户端是 MUST）；③ 提到 OAuth 2.1 草案已把 PKCE 提为所有授权码流程的默认要求、移除 `plain`；④ 移动端还应配合 App Links / Universal Links 优先于自定义 scheme，从源头减少抢注面；⑤ 提到 DPoP（RFC 9449）可把 token 绑到客户端密钥，进一步防重放。

### 题 3：网关统一鉴权上线后，测试发现「改订单 id 就能看别人的订单」。为什么？怎么修？

- **不及格**：「网关加个拦截器再校验一次」——网关不知道数据归属，加几次都没用。
- **及格**：「在订单服务里校验订单属于当前用户」——说对了位置，但做法含糊。
- **良好**：「根因是 IDOR：网关只做了认证与路由级授权，数据级归属只能在资源服务做。修法是 SQL 里带属主条件：`select * from orders where id=? and user_id=?`，而不是先查出来再判断。接口返回 403 而不是 200 空数据。」
- **优秀**：在良好基础上补：① 指出「先查再判」在缓存与并发下会漏，必须让属主条件进查询；② 给出系统性修法：`@PreAuthorize("hasPermission(#id,'order','read')")` + 自定义 `PermissionEvaluator`，把归属校验做成可复用能力而不是散落各处的 if；③ 提到网关应剥掉外部传入的 `X-User-Id` 之类身份 Header，防止伪造；④ 说明 5000 万订单 id 上，水平越权是必然事件而非概率事件，需要一次全量审计而不只是修这一个接口；⑤ 补一句审计与告警：同一用户短时间大量访问非本人资源应触发风控。

---

## 15 33 期地图与下期预告

33 期下来，Java 后端面试的主线已经覆盖完：

- **Java 语言与底层**：01 HashMap / 03 并发 / 13 类加载与新特性 / 14 集合 / 19 NIO·Netty / 20 线程池 / 22 JIT / 23 泛型反射代理 / 26 字节码 Agent
- **JVM**：02 GC / 12 调优 / 21 内存泄漏 / 32 容器边界
- **框架**：04 Spring / 15 Spring Boot / 24 MyBatis / 27 依赖与模块化
- **数据与存储**：05 MySQL / 06 Redis / 16 分库分表 / 17 缓存一致性 / 25 Elasticsearch
- **分布式**：07 MQ 与分布式事务 / 08 Spring Cloud / 18 幂等 / 29 共识 / 30 超时重试 / 31 连接池
- **工程与质量**：09 排查 / 10 场景设计 / 11 设计模式 / 28 可观测性 / 33 认证授权与会话

本期与前后期的接缝：08 讲网关限流与 TraceId，33 讲网关鉴权边界——**同一个网关，两张职责清单**；18 讲幂等、30 讲重试，33 讲刷新惊群与 token 重用检测——**都是「重复请求在鉴权域的变形」**。

**下期建议主题：第 34 期 微服务数据一致性与对账（本地消息表 / 事务消息 / TCC 补偿 / 最终一致性校对与差异修复 / 「钱账对不上」怎么排查）**，可在文末预告。
