#!/usr/bin/env bash
# ============================================================
# 打「有余」Android apk（WebView 外壳）。
#
# 全程在 docker 里完成，不往本机装 JDK / Android SDK：
#   - SDK 装在名为 youyu-android-sdk 的 docker volume 里（幂等，只装一次）
#   - gradle 缓存放 /tmp/youyu-gradle-home
#
# 本机为 Apple Silicon 时会用 QEMU 跑 amd64 镜像（Google 只发布 linux x86_64 的
# build-tools），首次构建较慢，之后有缓存会快很多。
#
# 用法：
#   bash android/build-apk.sh            # 打 release（用 debug 签名，可直接安装）
#   bash android/build-apk.sh debug      # 打 debug
#
# 清理：
#   docker volume rm youyu-android-sdk && rm -rf /tmp/youyu-gradle-home
# ============================================================
set -euo pipefail

VARIANT="${1:-release}"
case "$VARIANT" in
  release) TASK=assembleRelease; OUT=app/build/outputs/apk/release/app-release.apk ;;
  debug)   TASK=assembleDebug;   OUT=app/build/outputs/apk/debug/app-debug.apk ;;
  *) echo "未知构建类型: $VARIANT（可选 release / debug）"; exit 1 ;;
esac

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_VOLUME=youyu-android-sdk
GRADLE_HOME=/tmp/youyu-gradle-home
PLATFORM=linux/amd64

command -v docker >/dev/null 2>&1 || { echo "需要 docker，请先安装并启动 Docker Desktop"; exit 1; }
docker info >/dev/null 2>&1 || { echo "docker 没在运行，请先启动 Docker Desktop"; exit 1; }

# ---------- 1. 准备 Android SDK（幂等） ----------
if docker run --rm --platform "$PLATFORM" -v "$SDK_VOLUME":/sdk alpine \
     test -d /sdk/platforms/android-34 2>/dev/null; then
  echo "==> Android SDK 已就绪"
else
  echo "==> 首次运行：在 docker volume 里安装 Android SDK（约 1GB，需要几分钟）"
  docker run --rm --platform "$PLATFORM" -v "$SDK_VOLUME":/sdk eclipse-temurin:17-jdk bash -c '
    set -e
    apt-get update -qq && apt-get install -y -qq unzip curl >/dev/null
    cd /tmp
    curl -sSLo cmd.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q cmd.zip
    mkdir -p /sdk/cmdline-tools/latest
    mv cmdline-tools/* /sdk/cmdline-tools/latest/
    SM=/sdk/cmdline-tools/latest/bin/sdkmanager
    yes | "$SM" --sdk_root=/sdk --licenses >/dev/null 2>&1 || true
    "$SM" --sdk_root=/sdk "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
    echo "SDK 安装完成"
  '
fi

# ---------- 2. 构建 ----------
mkdir -p "$GRADLE_HOME"
echo "==> 构建 $VARIANT"
docker run --rm --platform "$PLATFORM" \
  -v "$HERE":/proj -w /proj \
  -v "$SDK_VOLUME":/sdk \
  -v "$GRADLE_HOME":/gradle-home \
  --user "$(id -u):$(id -g)" \
  -e ANDROID_HOME=/sdk \
  -e ANDROID_SDK_ROOT=/sdk \
  -e GRADLE_USER_HOME=/gradle-home \
  -e HOME=/gradle-home \
  gradle:8.7-jdk17 gradle "$TASK" --no-daemon

# ---------- 3. 结果 ----------
APK="$HERE/$OUT"
if [ ! -f "$APK" ]; then
  echo "构建结束但没找到 apk：$APK"
  exit 1
fi
echo
echo "==> 完成"
echo "    $APK"
echo "    大小 $(du -h "$APK" | cut -f1)"
echo
echo "装到手机："
echo "  1) 把 apk 传到手机（微信发给自己 / 数据线 / 网盘都行），点开安装，"
echo "     首次会提示「允许安装未知来源应用」。"
echo "  2) 或用 adb： adb install -r \"$APK\""
