#!/usr/bin/env bash
# NetPilot 构建脚本。用法：bash _build.sh [gradle 任务...]（默认 :app:assembleDebug）
#
# 为什么堆参数要写在命令行：
#   项目级 gradle.properties 里的 org.gradle.jvmargs 对 Gradle 不生效 —— JVM 在读取
#   工程配置之前就已启动，真正生效的是 $GRADLE_USER_HOME/gradle.properties 的
#   -Xmx1536m，本容器（7.5G 总内存、常驻占用高）下会被 OOM killer 杀掉。
# 不要用 `| tail` 接本脚本来判断成败，那会吞掉退出码。
set -uo pipefail
cd "$(dirname "$0")" || exit 1
export GRADLE_USER_HOME=/root/.gradle
LOG=/tmp/np_build.log
TASK="${*:-:app:assembleDebug}"
./gradlew $TASK --console=plain \
  -Dorg.gradle.jvmargs="-Xmx1152m -XX:MaxMetaspaceSize=320m -XX:+UseSerialGC -Dfile.encoding=UTF-8" \
  -Dorg.gradle.configuration-cache.parallel=false \
  > "$LOG" 2>&1
CODE=$?
echo "=== EXIT=$CODE  (task: $TASK) ==="
grep -nE "^e: |FAILURE|What went wrong|> Task .*FAILED|error:|Caused by|Execution failed|Unresolved reference|daemon disappeared|BUILD (SUCCESSFUL|FAILED)" "$LOG" | head -60
echo "--- tail 25 ---"
tail -25 "$LOG"
exit $CODE
