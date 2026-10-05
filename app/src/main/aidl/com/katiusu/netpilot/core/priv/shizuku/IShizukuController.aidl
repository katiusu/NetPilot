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
    // 1.5.2 新增：取走用户服务进程攒下的诊断行。
    // 为什么必须回传：用户服务跑在独立进程（Shizuku 用 app_process 拉起的 :np_service）里，
    // 那边没有 LogStore 的 Context，诊断写得再细也到不了应用进程的日志页 —— 于是「Shizuku 模式下
    // 切制式失败」在界面上永远只有一句 false。协议与 root 通道的 "DIAG " stdout 行一致：
    // 仍然是单行编码字符串（Binder 字符串 + 行内分隔符），不引入自定义 Parcelable。
    String drainDiag();
    void destroy();
}
