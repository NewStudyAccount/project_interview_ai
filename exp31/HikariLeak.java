import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;

/** 实验8：真 HikariCP 泄漏检测（阈值必须 >= 2000ms，否则被静默禁用） */
public class HikariLeak {
    public static void main(String[] a) throws Exception {
        int leakMs = a.length > 0 ? Integer.parseInt(a[0]) : 3000;
        int holdMs = a.length > 1 ? Integer.parseInt(a[1]) : 4200;
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSource(new Hikari31.FakeDataSource());
        cfg.setMaximumPoolSize(3);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(1000);
        cfg.setLeakDetectionThreshold(leakMs);
        cfg.setPoolName("leak-pool");
        HikariDataSource ds = new HikariDataSource(cfg);
        System.out.println(">>> leakDetectionThreshold=" + leakMs + "ms, 持有 " + holdMs + "ms");
        Connection c = ds.getConnection();
        Thread.sleep(holdMs);
        c.close();                     // 归还
        Thread.sleep(1500);
        ds.close();
    }
}
