# 测试与证据

开发中。运行结果将在实际执行后记录；以下为测试范围，不能视为已通过。

- JVM：空/全/半球 Mask、极区面积权重；明确 ERP/双目/未知/损坏 MP4 box。
- Android 集成：Room 关闭后重开；已知旋转相机测试纹理的原生拼接、透明缺失、增量覆盖增加、撤销重建、不匹配拒绝、取消；真实 H.264 编码→MediaCodec 解码→时间戳采样→原生清晰度分析。
- 手工验收：设备拍摄、SAF 导入、后台处理、导出 PNG/JPEG/ZIP、工程重导入。

测试纹理仅用于可复现算法验证；应用不会将测试图当作处理结果。真实拍摄素材未提供，相关测试尚待补充。测试不会生成虚假的处理进度或性能评分。

## 已执行结果 2026-10-22

- Gradle Wrapper 生成/配置：通过。
- `assembleDebug -PskipNative`：通过（仅应用层验证，不作为原生交付 APK）。
- JVM 8 测试：全部通过，覆盖率 4 项、投影识别 4 项。
- API35 x86_64 独立模拟器：项目创建 UI、Room 重开、素材副本/复制/原件保护 3 项全部通过。
- 初次存储集成测试失败，定位到 REPLACE 写入触发外键级联；改用 Room Upsert 后原测试保留并通过。
- OpenCV 官方 AAR 和当前应用层 APK 的全部 arm64/x86_64 ELF 库通过 PT_LOAD 16KB 对齐检查。本项目 JNI 尚需完整构建后检查。

原始日志暂存 `.deps/phase1-retest.log`；Gradle HTML/XML 报告在 `app/build/reports/`、`app/build/test-results/`、`app/build/outputs/androidTest-results/`。
