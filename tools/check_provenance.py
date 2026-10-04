#!/usr/bin/env python3
"""检查「借鉴来源」文件的 clean-room 状态：与上游参考实现的文本重合度。

用法：
    python3 tools/check_provenance.py [--ref <参考仓库根目录>]

参考实现默认在 `_ref/networkswitch`（已列入 .gitignore，需自备 clone：
`git clone --depth 1 https://github.com/aunchagaonkar/NetworkSwitch _ref/networkswitch`）。

输出每对文件的：
  ratio        —— token 级相似度（0~1）
  dup>=12      —— 长度 ≥12 的连续重合串覆盖了上游多少 token（百分比）
  max run      —— 最长连续重合串长度
  verdict      —— OK / REVIEW

判据（见 docs/PROVENANCE.md）：OK 表示重合只剩「必须与平台一致的事实」
（隐藏 API 的类名/方法名、常量表、接口签名）；REVIEW 表示需要人工确认是否仍有表达层重合 ——
已在 PROVENANCE 里逐条写清结论的条目登记在下面的 REVIEWED，打印 reviewed 说明且不影响退出码。
"""
import argparse, difflib, os, re, sys

PAIRS = [
    ("app/src/main/java/com/katiusu/netpilot/core/mode/NetworkMode.kt",
     "app/src/main/java/com/supernova/networkswitch/domain/model/NetworkSwitchModels.kt"),
    ("app/src/main/java/com/katiusu/netpilot/core/mode/NetworkModeBitmaskMapper.kt",
     "app/src/main/java/com/supernova/networkswitch/service/NetworkModeBitmaskMapper.kt"),
    ("app/src/main/java/com/katiusu/netpilot/core/priv/TelephonyReflection.kt",
     "app/src/main/java/com/supernova/networkswitch/service/TelephonyReflection.kt"),
    ("app/src/main/java/com/katiusu/netpilot/core/priv/ControlManager.kt",
     "app/src/main/java/com/supernova/networkswitch/data/source/NetworkControlDataSource.kt"),
    ("app/src/main/java/com/katiusu/netpilot/core/priv/shizuku/ShizukuController.kt",
     "app/src/main/java/com/supernova/networkswitch/data/source/ShizukuNetworkControlDataSource.kt"),
    ("app/src/main/java/com/katiusu/netpilot/core/priv/shizuku/ShizukuControllerService.kt",
     "app/src/main/java/com/supernova/networkswitch/service/ShizukuControllerService.kt"),
    ("app/src/main/aidl/com/katiusu/netpilot/core/priv/shizuku/IShizukuController.aidl",
     "app/src/main/aidl/com/supernova/networkswitch/IShizukuController.aidl"),
]

DUP_MIN = 12          # 认定为「连续重合」的最小 token 串长度
DUP_REVIEW_PCT = 25.0 # 重合占比超过该值就需要人工复核

# 已人工复核并写明结论的重合（key = PAIRS 里的「我们的文件」相对路径）。
# 这些行仍打印 REVIEW，但会带 reviewed 说明且不影响退出码；未列入的行出现 REVIEW 会 exit 1。
# 结论依据见 docs/PROVENANCE.md「剩余重合的构成」。
REVIEWED = {
    "app/src/main/java/com/katiusu/netpilot/core/priv/shizuku/ShizukuControllerService.kt":
        "AIDL 接口签名 + 类声明（由本项目的 .aidl 契约决定）",
    "app/src/main/java/com/katiusu/netpilot/core/priv/TelephonyReflection.kt":
        "HiddenApiBypass 豁免前缀清单与调用惯用法（平台事实 + 第三方 API 惯用法）",
}


def tokens(path):
    src = open(path, encoding="utf-8").read()
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)   # 块注释
    src = re.sub(r"//[^\n]*", "", src)                # 行注释
    return re.findall(r"[A-Za-z_][A-Za-z0-9_]*|\d+|[^\sA-Za-z0-9_]", src)


def compare(ours, ref):
    a, b = tokens(ours), tokens(ref)
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
    blocks = [bl for bl in sm.get_matching_blocks() if bl.size >= DUP_MIN]
    dup = sum(bl.size for bl in blocks)
    pct = 100.0 * dup / max(1, len(b))
    ratio = sm.ratio()
    verdict = "OK" if pct <= DUP_REVIEW_PCT else "REVIEW"
    if pct > DUP_REVIEW_PCT and ratio > 0.6:
        verdict = "REVIEW"
    return ratio, pct, max([bl.size for bl in blocks], default=0), verdict


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref", default=os.path.join(root, "_ref", "networkswitch"))
    args = ap.parse_args()
    if not os.path.isdir(args.ref):
        sys.exit("reference tree not found: %s\nsee the docstring for the clone command" % args.ref)

    print("%-52s %6s %9s %8s  %s" % ("our file", "ratio", "dup>=12", "max run", "verdict"))
    worst = 0
    checked = 0
    unreviewed = False
    for ours_rel, ref_rel in PAIRS:
        ours, ref = os.path.join(root, ours_rel), os.path.join(args.ref, ref_rel)
        short = ours_rel.split("com/katiusu/netpilot/")[-1]
        if not os.path.exists(ours):
            print("%-52s %6s %9s %8s  %s" % (short, "-", "-", "-", "MISSING"))
            continue
        if not os.path.exists(ref):
            print("%-52s %6s %9s %8s  %s" % (short, "-", "-", "-", "no upstream counterpart"))
            continue
        ratio, pct, longest, verdict = compare(ours, ref)
        checked += 1
        worst = max(worst, pct)
        label = verdict
        if verdict == "REVIEW":
            if ours_rel in REVIEWED:
                label = "REVIEW (reviewed: %s)" % REVIEWED[ours_rel]
            else:
                unreviewed = True
        print("%-52s %6.3f %8.1f%% %8d  %s" % (short, ratio, pct, longest, label))
    print("\nchecked %d pair(s), worst duplicated share %.1f%% (threshold %.0f%%)" % (checked, worst, DUP_REVIEW_PCT))
    if unreviewed:
        print("UNREVIEWED overlap above threshold -> document it in docs/PROVENANCE.md or refactor")
    return 1 if unreviewed else 0


if __name__ == "__main__":
    sys.exit(main())
