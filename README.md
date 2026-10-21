# PanoFusion 360

离线 Android 全景应用，包名 `com.panofusion.app`。源码已实现 Phase 1–8 的功能，验收状态见 [进度](docs/progress.md)。Phase 6/8 还缺真机验收，未通过测试的功能不视为交付完成。

## 构建

JDK 17、Gradle 8.9、AGP 8.7.3、Kotlin/Compose compiler 2.0.21、Compose BOM 2024.12.01、Room 2.6.1、Hilt 2.52、CameraX 1.4.1、WorkManager 2.10.0、OpenCV 4.12.0、NDK 28.2.13676358、CMake 3.22.1。minSdk 26，compile/targetSdk 35，arm64-v8a / x86_64。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

macOS/Linux 使用 `./gradlew`。设置 `ANDROID_HOME` 或 `local.properties` 中的 `sdk.dir`。首次构建需要从 Google Maven、Maven Central 和 Gradle 官方仓库下载依赖，应用运行时不需要网络。OpenCV 使用官方 Maven AAR 的 Prefab 头文件与原生库；AAR 未导出的 stitching/detail 由仓库内同版本官方 CPU 源码补编，不使用运行时下载模型。

调试 APK 目标路径：`app/build/outputs/apk/debug/app-debug.apk`。该路径存在且构建通过后才表示已生成 APK。`-PskipNative` 仅供原生工具链安装期间检查 Kotlin/数据库，不可用于最终交付。

## 运行

新建 360°、180° 或自定义工程，导入视频/多张照片或进入摄像头拍摄，点击普通视频/照片拼接。拍摄时围绕相机光心旋转，保持足够重叠。缺少纹理、视差或不连通素材可能失败。失败会保留最后成功版本。关键帧列表可禁用重复或问题帧，随后重建；关闭某批次会触发重建。

普通视频采用 MediaExtractor/MediaCodec 流式解码；清晰度、曝光与 ORB 匹配用于候选帧筛选。原生引擎使用双向 Hamming KNN、比例测试、RANSAC、旋转相机估计、Bundle Adjustment、球面反向投影、曝光补偿、GraphCut 接缝和多频带融合。透明 Mask 与输出共用球面坐标；覆盖率按球面行面积积分计算。自定义工程未定义目标面积时不显示百分比。

补拍时，摄像头页面会把实时画面与已提交版本的关键帧做视觉匹配。定位成功后显示真实全景的半透明叠加和转向指引（目标是在展开图上点选的缺失区域，或系统推荐的、与已覆盖区相邻的区域）；定位失败时明确显示「尚未定位」。新批次处理完成后会显示覆盖率变化，可以保留，也可以撤销并恢复之前的版本。参考方向可以旋转。导出可选择宽度（不超过渲染尺寸）、JPEG 质量、缺失区背景、裁剪到有效区域，以及单独导出 Coverage Mask；只有完整球面或明确的裁剪区域才写入 GPano 元数据。

## 数据与隐私

Room 保存项目、批次、素材与关键帧。素材导入为应用内副本；外部原件不会被修改或删除。每次成功结果保存在 `files/projects/{UUID}/history/revision-N/`，失败不覆盖旧结果。工程 ZIP 带格式版本，包含原始素材和历史输出，可以重新导入。删除工程需要确认。没有 INTERNET 权限、账号、云服务、广告或统计 SDK。

## 限制与验证

见 [已知限制](docs/limitations.md)、[测试说明](docs/testing.md)、[进度](docs/progress.md)。真机操作、性能和图像质量需分别验收；模拟器测试不能替代真实手持拍摄验证。

## 官方参考

- [AGP 8.7 兼容要求](https://developer.android.com/build/releases/past-releases/agp-8-7-0-release-notes)
- [OpenCV Android 使用方式](https://opencv.org/opencv4android-usage-models/)
- [OpenCV stitching detailed example](https://docs.opencv.org/4.13.0/d9/dd8/samples_2cpp_2stitching_detailed_8cpp-example.html)
