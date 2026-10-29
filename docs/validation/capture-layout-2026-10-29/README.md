# 0.1.1 拍摄操作栏与图标验证

用户截图显示拍照/录像按钮被系统导航栏遮挡。原实现位于全屏 Dialog 中，只有 safeDrawingPadding，按钮仍落在可视区域外；新增回归测试在修复前失败。

修复：拍摄界面使用主窗口内的独立全屏层，保留工程页面状态；读取拍摄窗口系统栏/刘海安全区，Scaffold 优先测量顶部信息和底部操作栏，相机仅占用剩余区域并裁剪越界绘制。短窗口的说明与操作区域可滚动，底部状态文字缩短。返回时解除本次 CameraX 绑定，取消尚未完成的初始化回调。主界面在拍摄期间不参与辅助功能焦点。

图标：深蓝底、青色全景环绕箭头、白色相机镜头，使用 Android 矢量资源；API 26 自适应图标，API 33 提供独立单色层。不引入图标图片下载或新依赖。

## 实际结果

Android 15 / API 35 x86_64 模拟器，界面测试等待 CameraX 初始化完成，验证按钮未裁剪的窗口坐标，而非只检测文字是否存在。

| 配置 | 结果 |
|---|---|
| 1080×1920，三键导航，竖屏 | 通过；按钮底部 1740 px，导航区从 1776 px 开始 |
| 1920×1080，三键导航，横屏 | 通过；验证右侧导航区不与按钮重叠 |
| 1080×1920，手势导航，1.3 倍字体 | 通过；换行后的叠加按钮底部 1817 px，导航区从 1848 px 开始 |
| 返回工程 | 通过；工程页面仍存在，拍摄页面消失 |
| 应用图标 | 通过；Manifest 指向自定义自适应图标，单色层存在，Android 实际渲染为 512×512 PNG |
| 既有项目创建 UI | 通过 |
| JVM 单元测试 | 18 项通过 |
| Android Lint | 通过，0 错误；30 个非阻断警告、6 个提示（包含既有依赖版本和存储 API 建议） |
| APK 签名、16 KB ELF/ZIP 对齐 | 通过，arm64-v8a 与 x86_64 |

截图为模拟器实际输出：

- [竖屏三键导航](portrait-three-button.png)
- [横屏三键导航](landscape-three-button.png)
- [手势导航与大字体](portrait-gestural-large-font.png)
- [图标预览](icon-preview.png)

对应测试：`CaptureLayoutTest`、`LauncherIconTest`、`ProjectUiTest`。日志保留在同目录。没有在用户的 ColorOS 真机上复测，不将模拟器结果表述为真机验收。

## 安装包

版本 0.1.1 / versionCode 2，包名 `com.panofusion.app`，调试签名。

文件：`app/build/outputs/apk/debug/PanoFusion360-0.1.1-debug.apk`

大小：99,031,683 字节。

SHA-256：`E975A2C01289053724EB8B58E5A0593F12F6337CB308AE55AE23BA432D20BAC2`

已上传并通过 Drive 元数据读回核对文件名、大小和父文件夹：[下载安装包](https://drive.google.com/file/d/1KRIvQrdUO9doN9VS6pz8hZ5n2pVecjuL/view?usp=drivesdk)。
