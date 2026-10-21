# 架构

- `MainActivity` / `ProjectDetails`：Compose 首页、工程、帧管理和导出入口。
- `ProjectViewModel`：项目 Flow、协程作用域和可读错误事件。Hilt Application / AppModule 管理 Room 与存储实例。
- `Storage`：Room 四张业务表、事务导入、私有原件副本、版本清单、工程复制/改名/删除。
- `VideoFrameExtractor`：逐输入/输出缓冲区驱动 MediaExtractor/MediaCodec，采样 YUV 帧，处理 rowStride/pixelStride 和旋转。候选写磁盘，不存整段视频。
- `NativePanorama` / JNI：严格的路径、数值和回调边界；异常转换为可读错误。逐像素工作在 C++ 或后台解码协程，UI 不参与图像运算。
- `PanoramaWorker`：可取消前台任务；按资产完成状态恢复解码检查点；融合结果先写暂存目录，再发布完整 revision。
- `PanoramaView`：OpenGL ES ERP 射线采样，拖动、缩放、经线环绕与透明空缺。
- `VideoProjection`：有边界的 ISO BMFF box 遍历，明确区分 mono ERP、stereo、其他投影和 unknown。
- `Exporter`：SAF PNG/JPEG 和带版本的 ZIP；导入时重分配 UUID、限制路径与解压预算。
- `Settings`：DataStore 持久化采样间隔、清晰度阈值、输出宽度。

工程尚未拆成多 Gradle 模块。业务实现通过真实数据贯通，优先保持易构建和可诊断。
