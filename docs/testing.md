# 测试与证据

所有设备测试都运行在 **API 35 x86_64 模拟器**（AVD `panofusion_validation`）上，不是真机。数值来自实际运行的日志（`.deps/final-p8*.log`、logcat），不是估算值。

```powershell
.\gradlew.bat :app:testDebugUnitTest            # JVM
.\gradlew.bat :app:connectedDebugAndroidTest    # 设备/模拟器（约 10 分钟）
.\gradlew.bat :app:lintDebug
```

## 结果（2026-10-22）

| 项目 | 结果 |
|---|---|
| `assembleDebug`（含 arm64-v8a / x86_64 JNI） | 通过，`app/build/outputs/apk/debug/app-debug.apk`，151 MB（调试包，含两种 ABI 的 OpenCV） |
| JVM 单元测试 | 16/16 通过（覆盖率面积积分 4、投影识别 4、XMP/GPano 与补拍推荐 8） |
| 设备测试 | 22/22 通过（最终完整运行，10 分 8 秒） |
| Android Lint | 0 错误，28 警告 |
| 16 KB 页对齐 | APK 内全部 14 个 `.so`：PT_LOAD 对齐 = 16384，未压缩存储且 ZIP 偏移为 16 KB 的整数倍 |

### 设备测试与需求对应

| 需求测试 | 覆盖测试 | 关键结果 |
|---|---|---|
| 1 普通视频 | `VideoDecoderIntegrationTest`、`PerformanceIntegrationTest` | 真实 H.264 → MediaCodec → 6 个不重复时间戳 → 原生拼接；覆盖率 12.88% |
| 2 不完整视频 | `PanoramaIntegrationTest.partial…` | 未拍摄区 alpha=0，覆盖率 < 50% |
| 3 补拍 | `ProjectWorkflowIntegrationTest`、`SessionLifecycle…` | boat1+2 → 4.53%；新批次 boat3 → 5.81% |
| 4 不匹配补拍 | `ProjectWorkflow…`、`PanoramaIntegrationTest.unrelated…` | `E_DISCONNECTED`，版本和 PNG 哈希不变 |
| 5 撤销补拍 | `ProjectWorkflow…` | 停用批次后 PNG 的 SHA-256 与第一版完全一致 |
| 6 项目恢复 | `ProjectStorage…`、`PanoramaIntegrationTest.database…`、`SessionLifecycle.version1…`、ZIP 往返 | Room 关闭重开、v1→v2 迁移、ZIP 导入后覆盖率和历史 PNG 一致 |
| 7 导出 | `ErpExportIntegrationTest`、`ProjectWorkflow…` | JPEG/PNG 可解码、尺寸与所选一致、PNG 透明、二值 Mask、完整 ERP 写 GPano、部分全景不写、裁剪写 CroppedArea、拒绝放大 |
| 8 性能 | `PerformanceIntegrationTest` | 见下表 |
| 9 异常 | 空文件/损坏视频/空图片、无关照片、取消（原生与 Worker）、进程中断恢复、缺少相机参数 | 都返回可读错误码，不发布结果 |
| 补拍定位 | `LocalizationIntegrationTest` | 见下文 |
| 参考方向 | `LocalizationIntegrationTest.referenceYaw…`、`SessionLifecycle.referenceYaw…`、ERP 测试 | 锚点相机位于 −30.0°（误差 ≤0.5°）；ERP 为精确的列移位 |
| 锚点稳定 / 批次删除 | `SessionLifecycle.anchor…` | 帧顺序变化后锚点不变（`anchor: 1`）；删除批次时移除副本、帧和清单记录，外部原件保留，该批次参与过的旧版本拒绝恢复 |
| ERP 视频 | `ErpExportIntegrationTest.marked…` | 同一 1024×512 视频：未注入时识别为 UNKNOWN，注入 `st3d`/`sv3d` 后识别为 MONO_ERP，覆盖率 100%，输出 1024×512 |
| UI | `ProjectUiTest`、`ProjectWorkflow…` | 创建工程、打开、展开图点击、覆盖 Mask、导出对话框 |

### 视觉定位（真实照片，OpenCV boat 数据）

基于 boat1+boat2 的拼接版本：

| 查询画面 | 结果 | yaw / pitch | 内点 | 叠加误差* | 耗时 |
|---|---|---|---|---|---|
| boat1（锚点帧） | 定位成功 | −0.002° / 0.012° | 1640 | 10.8（覆盖 99%） | 0.8 s |
| boat2 中心裁剪 70%、缩放 0.5 | 定位成功 | 14.44° / 0.95°（与 cameras.yml 中的值相差 ≤2.5°） | 293 | — | 0.3 s |
| boat3（未参与拼接的新视角） | 定位成功 | 31.70° / −1.28° | 450 | 16.1（覆盖 58%） | 0.8 s |
| 随机噪声 | 拒绝 | — | 0 | — | 0.3 s |

\* 叠加误差：按定位结果渲染的全景与实际画面之间的 RGB 平均绝对差（0–255），只统计有覆盖的像素。

### 性能（模拟器，x86_64，生产 Worker 写入的 `metrics.json`）

| 场景 | 输入 | 关键帧 | 解码+分析 | 拼接 | 总计 | 进程峰值 RSS | 输出 PNG | 覆盖率 |
|---|---|---|---|---|---|---|---|---|
| 3 张照片 boat1–3 | 2.1 MB | 3 | 1.9 s | 9.8 s | 11.8 s | 333 MB | 147 KB | 5.81% |
| H.264 320×240，3 s | 0.2 MB | 6 | 3.0 s | 27.7 s | 30.8 s | 326 MB | 471 KB | 12.88% |
| H.264 1280×720，6 s | 2.2 MB | 12 | 11.8 s | 117.6 s | 129.5 s | 383 MB | 353 KB | 10.48% |

在最终完整运行中重复测量，总耗时为 12.2 / 31.3 / 131.9 s，峰值 RSS 为 331 / 324 / 381 MB，与上表相差不超过 3%。原始数据见 `docs/validation/emulator-metrics-2026-10-22.txt`。

1280×720 场景各阶段耗时：双向匹配 40.6 s、Bundle Adjustment 25.5 s、GraphCut 接缝 31.8 s、多频带融合 16.1 s。两两匹配和 GraphCut 的耗时随帧数超线性增长，这是长视频的主要瓶颈。以上不是 arm64 真机数据。

## 测试中发现并修复的问题

- 恢复历史版本时，只要有任一输入存在就会恢复：删除批次后会把已无法重建的版本显示为可恢复。现在要求全部输入都存在。
- 原生阶段内取消被记为「处理失败」，现在记为「已取消」。
- 进程被杀后工程一直停在「处理中」，现在启动时恢复为「已中断」并清理暂存目录。
- 首页本地化后，UI 测试改为从字符串资源取文字（模拟器语言为 en-US）。

## 未验证

真机安装与手持拍摄、通过 CameraX 实际补拍的端到端流程、arm64 性能与内存、长视频 / 4K 输出、URI 权限失效（代码中有处理，但没有自动测试）、存储空间不足（只有前置检查）。见 [limitations.md](limitations.md)。
