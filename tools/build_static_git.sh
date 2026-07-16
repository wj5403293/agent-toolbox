#!/bin/bash
# ============================================================
# 静态编译 git for Android arm64 (aarch64)
# 使用 Docker + Android NDK，输出无动态依赖的 git 二进制
#
# 用法:
#   docker run --rm -v $(pwd)/output:/output ubuntu:22.04 bash build_static_git.sh
# 或直接在 Linux 上运行（需安装 NDK）
#
# 产出: output/git (aarch64 静态二进制，约 15-20MB)
# 放到 APK 的 assets/git/git 即可
# ============================================================
set -ex

# ---- 配置 ----
NDK_VERSION="r26d"
API_LEVEL=21
ARCH="aarch64"
PREFIX="/tmp/static_prefix"
GIT_VERSION="2.46.0"

# ---- 安装依赖 ----
apt-get update -qq
apt-get install -y -qq wget xz-utils bzip2 make autoconf automake libtool \
    pkg-config gettext curl ca-certificates python3 perl

# ---- 下载 NDK ----
NDK_DIR="/tmp/android-ndk"
if [ ! -d "$NDK_DIR" ]; then
    echo "下载 Android NDK $NDK_VERSION ..."
    wget -q "https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip" -O /tmp/ndk.zip
    apt-get install -y -qq unzip
    unzip -q /tmp/ndk.zip -d /tmp/
    mv /tmp/android-ndk-* "$NDK_DIR"
    rm /tmp/ndk.zip
fi

# ---- 设置工具链 ----
export NDK="$NDK_DIR"
export TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export API=$API_LEVEL
export TARGET="aarch64-linux-android"
export CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export LD="$TOOLCHAIN/bin/ld.lld"

export CFLAGS="-static -fPIC -O2 -D__ANDROID_API__=$API"
export LDFLAGS="-static -L$PREFIX/lib"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"

mkdir -p "$PREFIX"
cd /tmp

# ---- 1. 编译 zlib (静态) ----
echo "=== 编译 zlib ==="
wget -q "https://zlib.net/zlib-1.3.1.tar.gz" -O zlib.tar.gz
tar xf zlib.tar.gz
cd zlib-1.3.1
./configure --static --prefix="$PREFIX"
make -j$(nproc) clean
make -j$(nproc)
make install
cd ..

# ---- 2. 编译 OpenSSL (静态) ----
echo "=== 编译 OpenSSL ==="
wget -q "https://www.openssl.org/source/openssl-3.3.2.tar.gz" -O openssl.tar.gz
tar xf openssl.tar.gz
cd openssl-3.3.2
./Configure android-arm64 \
    --prefix="$PREFIX" \
    no-shared no-tests \
    -D__ANDROID_API__=$API \
    LDFLAGS="-static"
make -j$(nproc)
make install_sw
cd ..

# ---- 3. 编译 libcurl (静态) ----
echo "=== 编译 libcurl ==="
wget -q "https://curl.se/download/curl-8.9.0.tar.xz" -O curl.tar.xz
tar xf curl.tar.xz
cd curl-8.9.0
./configure \
    --host=$TARGET \
    --build=x86_64-pc-linux-gnu \
    --prefix="$PREFIX" \
    --disable-shared \
    --enable-static \
    --with-openssl="$PREFIX" \
    --with-zlib="$PREFIX" \
    --disable-ldap \
    --disable-ldaps \
    --disable-rtsp \
    --disable-dict \
    --disable-telnet \
    --disable-pop3 \
    --disable-imap \
    --disable-smtp \
    --disable-gopher \
    --disable-mqtt \
    --without-libidn2 \
    --without-libpsl \
    --without-brotli \
    --without-zstd \
    --without-nghttp2 \
    CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS"
make -j$(nproc)
make install
cd ..

# ---- 4. 编译 git (静态) ----
echo "=== 编译 git ==="
wget -q "https://mirrors.edge.kernel.org/pub/software/scm/git/git-${GIT_VERSION}.tar.gz" -O git.tar.gz
tar xf git.tar.gz
cd "git-${GIT_VERSION}"

# 静态编译 git，只编译核心功能（不含 perl/python 脚本部分）
make -j$(nproc) \
    CC="$CC" \
    AR="$AR" \
    CFLAGS="$CFLAGS -I$PREFIX/include" \
    LDFLAGS="$LDFLAGS -all-static" \
    CURL_LDFLAGS="-L$PREFIX/lib -lcurl -lssl -lcrypto -lz" \
    CURLDIR="$PREFIX" \
    OPENSSLDIR="$PREFIX" \
    ZLIB_PATH="$PREFIX" \
    NO_TCLTK=1 \
    NO_PERL=1 \
    NO_PYTHON=1 \
    NO_GETTEXT=1 \
    NO_INSTALL_HARDLINKS=1 \
    prefix=/tmp/git-install \
    git

# strip 减小体积
$STRIP git

# 检查是否真的是静态的
echo "=== 检查二进制 ==="
file git
echo "动态依赖:"
$TOOLCHAIN/bin/llvm-readobj --needed-libs git || true

# 复制到输出目录
mkdir -p /output
cp git /output/git
chmod +x /output/git

echo ""
echo "=== 完成! ==="
echo "输出: /output/git"
echo "大小: $(ls -lh /output/git | awk '{print $5}')"
echo "将此文件放到 APK 的 assets/git/git 即可"
