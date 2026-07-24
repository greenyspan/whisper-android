#!/bin/bash
# 安卓 whisper 项目初始化脚本
# 在项目根目录下运行此脚本下载 whisper.cpp 源码

set -e

CPP_DIR="app/src/main/cpp"

if [ -d "$CPP_DIR/whisper.cpp" ]; then
    echo "whisper.cpp 已存在，跳过下载。"
    echo "如需重新下载，请先删除 $CPP_DIR/whisper.cpp 目录。"
    exit 0
fi

echo "正在下载 whisper.cpp 源码…"
cd "$CPP_DIR"

# Clone whisper.cpp（国内可用 gitee 镜像）
if command -v git &> /dev/null; then
    git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git
    echo "下载完成！"
else
    echo "错误：未找到 git，请先安装 git。"
    exit 1
fi

echo ""
echo "初始化完成！现在可以用 Android Studio 打开项目了。"
