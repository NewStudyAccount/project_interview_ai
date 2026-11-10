package com.example.demo.test;

public class Counter {

    private volatile int count;


    public synchronized void incr() {
        count++;
    }    // 看起来只有一行


    public int get()   {
        return count;
    }

}
