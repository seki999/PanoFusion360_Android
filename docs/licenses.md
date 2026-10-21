# 开源依赖许可

| 依赖 | 用途 | 许可 |
|---|---|---|
| Kotlin / kotlinx.coroutines / kotlinx.serialization | 语言、异步、序列化 | Apache-2.0 |
| AndroidX Compose / Material3 / Room / WorkManager / CameraX / DataStore / ExifInterface | UI、本地持久化、后台任务、摄像头 | Apache-2.0 |
| OpenCV 4.12.0 | 特征、相机优化、重映射、接缝和融合 | Apache-2.0；包含第三方组件，遵循官方分发包许可证 |
| Dagger / Hilt | 依赖注入 | Apache-2.0 |
| Gradle | 构建 | Apache-2.0 |
| JUnit 4 | 测试 | EPL-1.0 |
| Android SDK / NDK | 构建工具 | Google Android SDK 条款及各工具/运行库独立许可 |

OpenCV 以官方 Maven `org.opencv:opencv:4.12.0` 分发；官方 AAR SHA-1：73855bf3165a32b5c693b5da5147a05a8334472c。发布前需随应用提供完整第三方 NOTICE 和分发许可；当前未作商业发布准备。

补编 stitching 模块保留每个源文件的 BSD 风格声明，仓库 LICENSE 为 Apache-2.0。APK assets/OpenCV-stitching-NOTICES.txt 包含相应声明。源码版本与 CPU 构建适配见 third_party/opencv_stitching/README.md。
