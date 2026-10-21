# 构建环境

要求和命令见 README。仅官方 Google Maven/Maven Central、Gradle 分发使用网络。应用 Manifest 不包含 INTERNET。

本机初始有 JDK17、SDK35、Gradle8.9，无 NDK/CMake，已按官方包安装流程补齐。新版 Android CLI 使用：

```powershell
android sdk install ndk/28.2.13676358
android sdk install cmake/3.22.1
```

旧版 sdkmanager 使用带分号的标准 SDK 路径；不要混用两个 CLI 的参数语法。

NDK r28 的本应用 JNI 链接参数包括 `-Wl,-z,max-page-size=16384`。OpenCV 预编译库也必须通过 ELF LOAD 对齐检查，不能仅凭 JNI 链接参数宣称整个 APK 支持 16KB。最终记录见 testing.md。

实际安装 NDK r28c Windows 官方完整包 748118221 字节，SHA-1 086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d，与 Google 仓库元数据一致。OpenCV stitching 模块的独立 CPU 构建与共享 STL 配置已实际通过两种 ABI 编译。
