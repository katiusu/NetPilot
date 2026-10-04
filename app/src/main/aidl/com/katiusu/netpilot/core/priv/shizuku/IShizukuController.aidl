package com.katiusu.netpilot.core.priv.shizuku;

interface IShizukuController {
    boolean probe();
    int getCurrentNetworkMode(int subId);
    boolean setNetworkMode(int subId, int networkMode);
    int getDefaultSlot();
    boolean setDefaultSlot(int slot);
    int[] activeSlots();   // 扁平数组 [slot, subId, slot, subId, ...]
    // 回收上一次运行遗留的 np_service 孤儿进程（客户端被系统杀掉时来不及 unbind），返回杀掉的个数
    int pruneStaleProcesses();
    void destroy();
}
