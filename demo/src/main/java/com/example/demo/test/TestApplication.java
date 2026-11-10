package com.example.demo.test;

public class TestApplication {

    public static void main(String[] args) {
        // 两个线程各跑 10000 次
        Counter c = new Counter();
        Thread t1 = new Thread(() -> { for (int i = 0; i < 10000; i++) c.incr(); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 10000; i++) c.incr(); });
        t1.start();
        t2.start();
        try {
            t1.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            t2.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.out.println(c.get());   // 期望 20000，实际经常是 1.3 万 ~ 1.9 万之间某个数
    }

}
