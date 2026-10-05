# NetPilot release 构建启用 R8 —— 与参考模板 MiuixGuiExample 的 release 配置一致
# （isMinifyEnabled = true + getDefaultProguardFile("proguard-android-optimize.txt")）。
#
# 为什么本工程不能像纯 GUI 示例那样「一条 keep 都不用写」：
# 纯 GUI 示例的入口全在 manifest 里（AGP 的默认规则自动保留），而 NetPilot 有两处入口是
# **由外部进程按类名加载**的。这两处一旦被 R8 改名或裁掉，**不会有任何编译期报错**，
# 只会在真机上表现为「Root 通道自检失败」「Shizuku 用户服务绑不上」这类静默失效。
#
# 反射用的系统类（android.os.SystemProperties / miui.os.Build / android.app.ActivityThread /
# 各版本 ITelephony 等）都在 boot classpath 里，R8 不会改名，也不需要在这里 keep。

# ---------------------------------------------------------------------------
# 1) Root 通道的命令行入口：由 root 的 app_process 直接按类名启动
#
#    CLASSPATH=/data/app/~~xxx/base.apk app_process /system/bin \
#        com.katiusu.netpilot.core.priv.PrivilegedCli probe
#
#    类名写死在 WriteCompat.MAIN_CLASS（core/priv/WriteCompat.kt 的 MAIN_CLASS 常量）；
#    app_process 只认 public static void main(String[])。
# ---------------------------------------------------------------------------
-keep class com.katiusu.netpilot.core.priv.PrivilegedCli {
    public static void main(java.lang.String[]);
}

# ---------------------------------------------------------------------------
# 2) Shizuku 用户服务：Shizuku 侧按 ComponentName 里的类名反射实例化（无参构造）
#
#    类名由 ShizukuController.buildUserServiceArgs() 传出去
#    （core/priv/shizuku/ShizukuController.kt:339），实例化发生在**另一个进程**
#    （Shizuku 用 app_process 拉起的用户服务），所以「类名 + 无参构造」必须保留；
#    跨进程调用的方法体也要跟着 AIDL 一起留下。
# ---------------------------------------------------------------------------
-keep class com.katiusu.netpilot.core.priv.shizuku.ShizukuControllerService {
    <init>();
    *;
}

# ---------------------------------------------------------------------------
# 3) 上面那个用户服务用的 AIDL 接口（app/src/main/aidl/.../IShizukuController.aidl）
#
#    Stub / Proxy 分别跑在两个进程里，靠 binder 事务码通信；事务码虽是编译期常量，
#    但接口名与 Stub/Proxy 类是两端各自加载的，显式保留最稳妥（AGP 只对 AIDL 生成的
#    类做基本保护，接口本身在混淆后仍可能被内联/裁剪）。
# ---------------------------------------------------------------------------
-keep interface com.katiusu.netpilot.core.priv.shizuku.IShizukuController { *; }
-keep class com.katiusu.netpilot.core.priv.shizuku.IShizukuController$Stub { *; }
-keep class com.katiusu.netpilot.core.priv.shizuku.IShizukuController$Stub$Proxy { *; }
