import random, math, statistics
random.seed(7)

def erlang_c(c, a):
    s = sum(a**k / math.factorial(k) for k in range(c))
    term = a**c / math.factorial(c) * (c / (c - a)) if a < c else float('inf')
    return term / (s + term)

def theory_wq(c, lam, mu):
    a = lam / mu
    if a >= c: return float('inf')
    return erlang_c(c, a) / (c * mu - lam)

def simulate(c, lam, mu, n=300_000, warm=30_000):
    """M/M/c 离散事件。返回 (Wq均值ms, p99 ms, 实测吞吐, 系统中平均请求数L)"""
    t = 0.0; free = [0.0]*c; waits = []; served = 0
    next_arr = random.expovariate(lam); k = 0
    area = 0.0; last_t = 0.0; busy = 0
    while k < n:
        k += 1; t = next_arr; next_arr += random.expovariate(lam)
        i = min(range(c), key=lambda x: free[x])
        start = max(t, free[i]); w = start - t
        free[i] = start + random.expovariate(mu)
        if k > warm:
            waits.append(w*1000); served += 1
        if k == warm: last_t = t
    end = max(free); span = end - last_t
    # Little 定律：L = λ_eff * W，用总停留时间均值近似
    avg_wait = statistics.mean(waits)
    p99 = sorted(waits)[int(0.99*len(waits))]
    return avg_wait, p99, served/span, avg_wait

print("== A. 容量就是 c/S：c=10，单查询 2ms → 理论上限 5000 qps ==")
print("λ\t实测吞吐\tWq实测ms\tWq理论ms\t相对误差\tp99 ms")
c, mu = 10, 500.0
for lam in [1000, 2500, 4000, 4500, 4800, 4950]:
    w, p99, thr, _ = simulate(c, float(lam), mu)
    th = theory_wq(c, float(lam), mu)*1000
    err = abs(w-th)/th*100 if th > 0.01 else 0
    print(f"{lam}\t{thr:.0f}\t\t{w:.2f}\t\t{th:.2f}\t\t{err:.1f}%\t\t{p99:.1f}")

print()
print("== B. 同样 90% 利用率，池越大等待越短（M/M/c 汇聚效应） ==")
print("c\t容量qps\tλ=0.9容量\tWq实测ms\tp99 ms")
for cc in [2, 5, 10, 20, 40]:
    cap = cc*mu; lam = 0.9*cap
    w, p99, thr, _ = simulate(cc, lam, mu, n=200_000, warm=20_000)
    print(f"{cc}\t{cap:.0f}\t\t{lam:.0f}\t\t{w:.2f}\t\t{p99:.1f}")

print()
print("== C. 连接数 vs DB 核数（k=12，单查询 CPU 2ms） ==")
print("c\t吞吐qps\t单次延迟ms\t结论")
k, s0 = 12, 2.0
for c in [4, 8, 12, 24, 48, 96]:
    if c <= k: thr, lat, note = c/s0*1000, s0, "未饱和，随 c 线性增长"
    else: thr, lat, note = k/s0*1000, s0*c/k, f"超配 {c/k:.0f}x，只加延迟不加吞吐"
    print(f"{c}\t{thr:.0f}\t\t{lat:.1f}\t\t{note}")

print()
print("== D. 反推池大小：c = 目标QPS × 单次耗时（× 1.3 余量） ==")
print("目标QPS\t平均耗时ms\t理论c\t建议c")
for qps, s in [(500, 2), (1000, 5), (2000, 5), (300, 20), (100, 50)]:
    cc = qps*s/1000
    print(f"{qps}\t{s}\t\t{cc:.1f}\t{max(1, round(cc*1.3))}")
