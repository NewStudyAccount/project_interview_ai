import socket, threading, time, statistics

# 一个只会"读一行、回一行"的假服务端（模拟 MySQL 的认证往返）
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('127.0.0.1', 0))
srv.listen(200)
port = srv.getsockname()[1]
stop = False

def serve():
    while not stop:
        try:
            c, _ = srv.accept()
        except OSError:
            return
        threading.Thread(target=handle, args=(c,), daemon=True).start()

def handle(c):
    try:
        while True:
            d = c.recv(64)
            if not d:
                break
            c.sendall(b'ok\n')
    except OSError:
        pass
    finally:
        c.close()

threading.Thread(target=serve, daemon=True).start()
time.sleep(0.2)

def new_conn_roundtrips(n_rtt=4):
    """建连 + n 次往返（认证 / SET NAMES / 时区 / autocommit），然后关闭"""
    t0 = time.perf_counter_ns()
    s = socket.create_connection(('127.0.0.1', port))
    for _ in range(n_rtt):
        s.sendall(b'x\n'); s.recv(64)
    s.close()
    return (time.perf_counter_ns() - t0) / 1e6

def reuse_roundtrips(s, n_rtt=4):
    t0 = time.perf_counter_ns()
    for _ in range(n_rtt):
        s.sendall(b'x\n'); s.recv(64)
    return (time.perf_counter_ns() - t0) / 1e6

for _ in range(200): new_conn_roundtrips()      # 预热
fresh = [new_conn_roundtrips() for _ in range(2000)]
s = socket.create_connection(('127.0.0.1', port))
for _ in range(200): reuse_roundtrips(s)
reuse = [reuse_roundtrips(s) for _ in range(2000)]
s.close(); stop = True; srv.close()

f = statistics.median(fresh); r = statistics.median(reuse)
print("== EXP9 建连 vs 复用（本机回环，4 次往返） ==")
print(f"新建连接+4次往返 : {f*1000:.1f} us")
print(f"复用连接+4次往返 : {r*1000:.1f} us")
print(f"建连占比         : {(f-r)/f*100:.1f}%   倍数 {f/r:.1f}x")
print()
print("== 按 RTT 推算（同机房 0.2ms / 跨AZ 1ms / 跨地域 30ms，4 RTT 建连） ==")
print("场景\tRTT\t建连ms\t单查询ms\t建连=查询的几倍")
for name, rtt, q in [("同机房", 0.2, 0.2), ("跨可用区", 1.0, 0.3), ("跨地域", 30.0, 2.0)]:
    conn = 4 * rtt
    print(f"{name}\t{rtt}ms\t{conn:.1f}\t{q}\t\t{conn/q:.0f}x")
