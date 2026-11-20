#!/bin/bash
# 从 aidl/ 下接口生成 Java stub 到 src-gen/（需要 apt-get install aidl）
set -eu
cd "$(dirname "$0")/.."
mkdir -p src-gen
for f in aidl/moe/shizuku/server/*.aidl; do
    aidl -Iaidl -o src-gen "$f"
done
echo "生成完毕: $(find src-gen -name '*.java' | wc -l) 个文件"
