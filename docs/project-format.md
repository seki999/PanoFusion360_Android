# 工程格式 v1

私有根目录 `files/projects/{UUID}`。

- `project.json`：格式号、工程、批次、资产及关键帧数据；原件通过应用内副本长期恢复。
- `original/{session}/{asset}`：不可破坏性原件副本。
- `frames/{asset}/{timestamp}.jpg`：有界工作帧。
- `history/revision-N/panorama.png`：完整 2:1 球面画布，空缺 alpha=0；180° 目标外同样无效。
- 同级 `coverage.png`、`observed.png`、`quality.png`、`coverage-preview.png`：融合、足迹、质量和可视化。
- `cameras.yml`：OpenCV 格式的焦距、主点及旋转。
- `inputs.json`：本次渲染输入的帧元数据。
- `cache/job-{uuid}`：发布前的暂存结果；取消/失败删除暂存。

ZIP 中清单路径改为相对路径；重导入重映射工程/批次/资产/帧 UUID，恢复关键帧选择。ZIP 包含原件和历史，可能较大。首次工程格式为 v1，未来版本必须添加迁移，禁止使用破坏性 Room fallback。当前 Room v1 是初始版本，无旧数据库迁移需求。

融合过程中若进程退出，数据库仍指向最后成功 revision。若重命名后数据库提交前退出，可能有未引用的历史文件；下一次发布会选择更大编号，不覆盖历史。
