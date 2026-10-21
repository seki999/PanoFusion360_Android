# 架构

- `MainActivity` / `ProjectDetails`：Compose 首页（缩略图、模式、覆盖率、修改时间；zh/en/ja 字符串资源）、工程、批次/帧管理、补拍目标、参考方向和导出对话框。
- `ProjectViewModel`：项目 Flow、协程作用域和可读错误事件。Hilt Application / AppModule 管理 Room 与存储实例。
- `Storage`：Room 四张业务表（schema v2，`MIGRATION_1_2` 增加参考方向和锚点帧，schema 导出到 `app/schemas/`）、事务导入、私有原件副本、版本清单、工程复制/改名/删除、批次删除、中断恢复。
- `VideoFrameExtractor`：逐输入/输出缓冲区驱动 MediaExtractor/MediaCodec，采样 YUV 帧，处理 rowStride/pixelStride 和旋转。候选写磁盘，不存整段视频。
- `NativePanorama` / JNI：严格的路径、数值和回调边界；异常转换为可读错误。逐像素工作在 C++ 或后台解码协程，UI 不参与图像运算。
- `PanoramaWorker`：可取消前台任务；按资产完成状态恢复解码检查点；融合结果先写暂存目录，再发布完整 revision。传入持久锚点帧和参考 yaw；每个版本写入实测 `metrics.json`。
- `localizer.cpp` / `VisualLocalizer`：把实时画面与已提交版本的关键帧做 ORB 双向匹配 + RANSAC，按旋转相机模型由单应性分解出焦距和旋转，再把全景按该位姿渲染成带 alpha 的叠加图。
- `CaptureScreen`：CameraX 拍照/录像、手电筒、空间检查；约每 1.2 s 取 `PreviewView.bitmap` 视觉定位，成功后显示半透明叠加和转向指引（`Guidance`/`CoverageGrid`），失败显示「尚未定位」。多次拍摄作为一个新批次加入，处理后可保留或撤销。
- `PanoMetadata`：JPEG APP1 / PNG iTXt XMP；仅在完整球面或明确裁剪区域时写 GPano。
- `PanoramaView`：OpenGL ES ERP 射线采样，拖动、缩放、经线环绕与透明空缺。
- `VideoProjection`：有边界的 ISO BMFF box 遍历，明确区分 mono ERP、stereo、其他投影和 unknown。
- `Exporter`：SAF PNG/JPEG（宽度、质量、缺失区背景、裁剪到有效区域、Coverage Mask），不放大超过渲染尺寸；带版本的 ZIP，导入时重分配 UUID、限制路径与解压预算。
- `Settings`：DataStore 持久化采样间隔、清晰度阈值、输出宽度。

工程尚未拆成多 Gradle 模块。业务实现通过真实数据贯通，优先保持易构建和可诊断。
