# 实施与验收清单

- [x] Phase 1 工程、Room、SAF 素材副本、项目恢复
- [x] Phase 2 系统解码、真实清晰度和关键帧筛选
- [x] Phase 3 OpenCV JNI、旋转相机估计和多频带融合
- [x] Phase 4 球面 Mask、面积加权覆盖率和预览
- [ ] Phase 5 Session、重建、撤销和参考方向
- [ ] Phase 6 CameraX 拍摄与辅助引导
- [ ] Phase 7 ERP 路径与图片/项目导出
- [ ] Phase 8 自动测试、构建、性能与设备验收

只有取得相应证据才勾选阶段；构建通过不等同于真机验收。

2026-10-22：应用层 assembleDebug（skipNative）、8 项 JVM 测试通过；API35 x86_64 模拟器 3 项创建/复制/原件保护/数据库恢复测试通过。初次原件保护测试暴露 REPLACE 级联删除问题，改用 Upsert 后重测通过。后续阶段仍待完整 JNI 集成测试。

Phase 2：真实 H.264 编码样本→系统解码→6 个采样时间戳→实际质量分析→WorkManager 视频关键帧选择与拼接，完整套件通过。样本为已知旋转相机的可复现合成视频，不代表真实手持视频质量验收。

Phase 3：官方 OpenCV 4.12.0 stitching CPU 源码与官方同版本 AAR 联接，arm64/x86_64 构建成功；真实照片匹配、相机优化、GraphCut 与多频带输出测试通过。

Phase 4：球面 Mask 和面积积分 JVM 测试、真实图像透明区域、独立质量数据通过；额外 Compose 测试已验证打开结果、展开图、区域点击和覆盖图切换。全部 APK 原生库与 ZIP 均通过 16KB 对齐检查。
