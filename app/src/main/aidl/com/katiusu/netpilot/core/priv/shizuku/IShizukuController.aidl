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
    // 1.5.0 新增：Android 11+ 的权威存储（TelephonyProvider siminfo.allowed_network_types）。
    // 用单行编码字符串而不是自定义 Parcelable：AIDL 面越小，越不容易因为签名漂移整条通道失效。
    String readAuthStore(int subId);
    String writeAuthStore(int subId, long networkTypes);
    void destroy();
}
