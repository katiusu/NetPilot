package com.katiusu.netpilot.core.priv.shizuku;

interface IShizukuController {
    boolean probe();
    int getCurrentNetworkMode(int subId);
    boolean setNetworkMode(int subId, int networkMode);
    int getDefaultSlot();
    boolean setDefaultSlot(int slot);
    int[] activeSlots();   // 扁平数组 [slot, subId, slot, subId, ...]
    void destroy();
}
