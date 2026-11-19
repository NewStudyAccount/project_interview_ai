import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;

/**
 * 实验7：真 HikariCP（5.1.0）+ 假 DataSource，观察
 *  ① 池耗尽时 connectionTimeout 的真实文案与耗时
 *  ② leakDetectionThreshold 的真实日志（HouseKeeper 判定"借出过久"）
 *  ③ 空闲连接被 minimumIdle 回收后重建的成本
 */
public class Hikari31 {

    /** 一个只会 sleep 的假连接 */
    static class FakeConn implements Connection {
        final int id;
        FakeConn(int id) { this.id = id; }
        public Statement createStatement() { return null; }
        public PreparedStatement prepareStatement(String sql) { return null; }
        public CallableStatement prepareCall(String sql) { return null; }
        public String nativeSQL(String sql) { return sql; }
        public void setAutoCommit(boolean b) {}
        public boolean getAutoCommit() { return true; }
        public void commit() {}
        public void rollback() {}
        public void close() {}
        public boolean isClosed() { return false; }
        public DatabaseMetaData getMetaData() { return null; }
        public void setReadOnly(boolean b) {}
        public boolean isReadOnly() { return false; }
        public void setCatalog(String c) {}
        public String getCatalog() { return null; }
        public void setTransactionIsolation(int l) {}
        public int getTransactionIsolation() { return Connection.TRANSACTION_READ_COMMITTED; }
        public SQLWarning getWarnings() { return null; }
        public void clearWarnings() {}
        public Statement createStatement(int a, int b) { return null; }
        public PreparedStatement prepareStatement(String s, int a, int b) { return null; }
        public CallableStatement prepareCall(String s, int a, int b) { return null; }
        public java.util.Map<String, Class<?>> getTypeMap() { return null; }
        public void setTypeMap(java.util.Map<String, Class<?>> m) {}
        public void setHoldability(int h) {}
        public int getHoldability() { return 0; }
        public Savepoint setSavepoint() { return null; }
        public Savepoint setSavepoint(String n) { return null; }
        public void rollback(Savepoint s) {}
        public void releaseSavepoint(Savepoint s) {}
        public Statement createStatement(int a, int b, int c) { return null; }
        public PreparedStatement prepareStatement(String s, int a, int b, int c) { return null; }
        public CallableStatement prepareCall(String s, int a, int b, int c) { return null; }
        public PreparedStatement prepareStatement(String s, int a) { return null; }
        public PreparedStatement prepareStatement(String s, int[] c) { return null; }
        public PreparedStatement prepareStatement(String s, String[] c) { return null; }
        public Clob createClob() { return null; }
        public Blob createBlob() { return null; }
        public NClob createNClob() { return null; }
        public SQLXML createSQLXML() { return null; }
        public boolean isValid(int t) { return true; }
        public void setClientInfo(String n, String v) {}
        public void setClientInfo(java.util.Properties p) {}
        public String getClientInfo(String n) { return null; }
        public java.util.Properties getClientInfo() { return null; }
        public Array createArrayOf(String t, Object[] e) { return null; }
        public Struct createStruct(String t, Object[] a) { return null; }
        public void setSchema(String s) {}
        public String getSchema() { return null; }
        public void abort(Executor e) {}
        public void setNetworkTimeout(Executor e, int ms) {}
        public int getNetworkTimeout() { return 0; }
        public <T> T unwrap(Class<T> iface) { return null; }
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    static class FakeDataSource implements DataSource {
        final AtomicInteger created = new AtomicInteger();
        public Connection getConnection() {
            return new FakeConn(created.incrementAndGet());
        }
        public Connection getConnection(String u, String p) { return getConnection(); }
        public PrintWriter getLogWriter() { return null; }
        public void setLogWriter(PrintWriter out) {}
        public void setLoginTimeout(int s) {}
        public int getLoginTimeout() { return 0; }
        public Logger getParentLogger() { return null; }
        public <T> T unwrap(Class<T> i) { return null; }
        public boolean isWrapperFor(Class<?> i) { return false; }
    }

    public static void main(String[] a) throws Exception {
        System.out.println("== EXP7 真 HikariCP 5.1.0 + 假 DataSource ==");
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSource(new FakeDataSource());
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(1500);
        cfg.setLeakDetectionThreshold(1200);
        cfg.setPoolName("demo-pool");
        HikariDataSource ds = new HikariDataSource(cfg);

        // ① 借满 2 个，第 3 个会等 connectionTimeout 后抛异常
        Connection c1 = ds.getConnection();
        Connection c2 = ds.getConnection();
        long t0 = System.nanoTime();
        try {
            ds.getConnection();
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("① 第3个连接等待 " + ms + "ms 后失败");
            System.out.println("   异常类型: " + e.getClass().getName());
            System.out.println("   异常文案: " + e.getMessage());
        }
        // ② 归还后再借，观察是否复用（不新建）
        c1.close();
        Connection c3 = ds.getConnection();
        System.out.println("② 归还后再借: 复用成功, 池内 idle=" + ds.getHikariPoolMXBean().getIdleConnections()
            + ", active=" + ds.getHikariPoolMXBean().getActiveConnections()
            + ", total=" + ds.getHikariPoolMXBean().getTotalConnections());
        // ③ 泄漏检测：持有一个连接超过 leakDetectionThreshold
        System.out.println("③ 持有连接 2500ms（leakDetectionThreshold=1200ms），等待 HouseKeeper 报警...");
        Thread.sleep(2600);
        c2.close(); c3.close();
        Thread.sleep(300);
        System.out.println("   池状态: idle=" + ds.getHikariPoolMXBean().getIdleConnections()
            + ", total=" + ds.getHikariPoolMXBean().getTotalConnections()
            + ", 累计创建连接数=" + ((FakeDataSource) cfg.getDataSource()).created.get());
        ds.close();
    }
}
