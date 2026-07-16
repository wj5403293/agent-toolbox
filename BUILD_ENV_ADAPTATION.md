# 构建环境适配说明

## 目标环境
- **Gradle 版本**: 7.5.1（2022年8月发布）
- **Kotlin**: 1.6.21
- **Groovy**: 3.0.10
- **JDK 版本**: 17（OpenJDK，内部构建版）
- **操作系统**: Linux（Android 环境，aarch64 架构）

## 修改内容

### 1. 创建 Gradle Wrapper 配置
**文件**: `gradle/wrapper/gradle-wrapper.properties`

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-7.5.1-bin.zip
networkTimeout=10000
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

### 2. 更新 build.gradle
**主要变更**:
- 添加 `buildscript` 块，配置 AGP 7.4.2 和 Kotlin 1.6.21
- 应用 `kotlin-android` 插件
- 将 `compileOptions` 从 Java 1.8 升级到 Java 17
- 添加 `kotlinOptions` 配置 JVM 目标为 17
- 添加 Kotlin stdlib 依赖

**AGP 版本选择**:
- AGP 7.2.1 是 Gradle 7.5.1 的兼容版本
- AGP 7.2.x 要求 Gradle 7.3.3+

### 3. 更新 settings.gradle
**变更**:
- 添加 `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` 以符合 Gradle 7.x 规范

### 4. 更新 gradle.properties
**新增属性**:
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.java.home=/data/user/0/aidepro.top/files/framework/jdk
android.useAndroidX=true
kotlin.code.style=official
```

## 兼容性说明

### Java 版本
- 源代码兼容性: Java 17
- 目标字节码: Java 17
- 注意: 如果代码使用了 Java 8 以上的特性，需要确保 JDK 17 支持

### Kotlin 支持
- 虽然当前项目是纯 Java，但已添加 Kotlin 支持以备将来使用
- Kotlin 版本 1.6.21 与 Gradle 7.5.1 兼容

### Android SDK
- compileSdk: 33
- minSdk: 24
- targetSdk: 32
- NDK ABI: arm64-v8a

## 构建命令

使用 Gradle Wrapper 构建（需要先生成 wrapper 脚本）:
```bash
# 生成 Gradle Wrapper（如果需要）
gradle wrapper --gradle-version 7.5.1

# 构建调试版本
./gradlew assembleDebug

# 构建发布版本
./gradlew assembleRelease
```

## 注意事项

1. **AIDE Pro 兼容性**: 此配置针对 AIDE Pro 优化，AIDE Pro 内置了 Gradle 和 Android SDK
2. **JDK 路径**: `org.gradle.java.home` 需要根据实际 JDK 安装路径调整
3. **构建目录**: 构建输出目录设置为 `/storage/emulated/0/Download/agent-toolbox-main/build`，这是 Android 存储路径
4. **Native 库**: 项目包含 arm64-v8a 架构的 native 库，确保构建环境支持该架构

## 依赖项

### Gradle 插件
- Android Gradle Plugin: 7.2.1
- Kotlin Gradle Plugin: 1.6.21

### 项目依赖
- luaj-jse-3.0.2.jar (Lua 引擎)
- Kotlin stdlib 1.6.21
- AndroidX (通过 android.useAndroidX=true 启用)

### Native 库 (arm64-v8a)
- libpython3.14.so (Python 3.14.6)
- libpython_bridge.so (JNI 桥接)
- libcrypto.so / libssl.so (OpenSSL)
- libsqlite3.so (SQLite)

## 静态 Git 二进制构建

`assets/git/git` 是独立的静态编译产物，与 APK 的 Gradle 构建流程分离，需要单独编译后放入 `assets/git/` 目录。

### 构建环境
- **NDK**: r26d（独立于 AIDE Pro 的 SDK，下载至 `/tmp/android-ndk`）
- **工具链**: clang / clang++ / llvm-ar / llvm-strip / ld.lld
- **依赖源码**: git 2.46.0、zlib 1.3.1、OpenSSL 3.3.2、curl 8.9.0
- **目标**: aarch64-linux-android，API 21

### 构建方式

**Docker（推荐，可复现）**：
```bash
cd tools
docker build -t git-builder .
docker cp $(docker create git-builder):/output/git ../assets/git/git
```

**本地 Linux**：
```bash
bash tools/build_static_git.sh
# 产物: /output/git → 复制到 assets/git/git
```

### 与 APK 构建的关系
- Git 二进制作为 `assets` 资源打包进 APK，不需要 NDK 参与 APK 构建
- 缺失 `assets/git/git` 时，shell 工具会自动回退到 dulwich（纯 Python Git）
- 详见 [README.md - Git 集成架构](./README.md#git-集成架构) 和 [tools/build_static_git.sh](./tools/build_static_git.sh)

### 产物验证
```bash
file assets/git/git
# 期望: ELF 64-bit LSB executable, ARM aarch64, statically linked, stripped
readelf -d assets/git/git | grep NEEDED
# 期望: 空（0 动态依赖）
ls -lh assets/git/git
# 期望: 约 4.2MB
```
