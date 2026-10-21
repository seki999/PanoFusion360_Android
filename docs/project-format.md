# 工程格式 v1

私有根目录 `files/projects/{UUID}`。

- `project.json`：格式号、工程、批次、资产及关键帧数据；原件通过应用内副本长期恢复。
- `original/{session}/{asset}`：不可破坏性原件副本。
- `frames/{asset}/{timestamp}.jpg`：有界工作帧。
- `history/revision-N/panorama.png`：完整 2:1 球面画布，空缺 alpha=0；180° 目标外同样无效。
- 同级 `coverage.png`、`observed.png`、`quality.png`、`coverage-preview.png`：融合、足迹、质量和可视化。
- `cameras.yml`：OpenCV 格式（version 2）的锚点序号、参考 yaw、各帧焦距、主点及旋转；视觉定位按 `inputs.json` 的顺序对应关键帧。
- `metrics.json`：该版本实测的分析/拼接耗时、各阶段耗时、进程峰值 RSS、输出大小。
- `inputs.json`：本次渲染输入的帧元数据。
- `cache/job-{uuid}`：发布前的暂存结果；取消/失败删除暂存。

ZIP 中清单路径改为相对路径；重导入重映射工程/批次/资产/帧 UUID，恢复关键帧选择。ZIP 包含原件和历史，可能较大。工程清单格式仍为 v1：新增的 `referenceYaw`、`anchorFrame` 有默认值，旧清单可直接读取。Room 已升至 v2，`MIGRATION_1_2` 以 `ALTER TABLE` 保留原有数据（有迁移测试）；禁止使用破坏性 fallback。

融合过程中若进程退出，数据库仍指向最后成功 revision；下次启动时没有存活任务的「处理中」工程标为「已中断」，残留 `cache/job-*` 被清理。删除批次会移除其原件副本与关键帧，引用它的历史版本不再可恢复（恢复时明确报错）。若重命名后数据库提交前退出，可能有未引用的历史文件；下一次发布会选择更大编号，不覆盖历史。
