# DocuShift

DocuShift 是一款 Android 本地文档格式转换工具。所有文件都在设备本地处理，不需要账号、网络存储或上传权限。

## 当前版本

`v0.1.0`（Android）

## 功能

- 图片转 PDF：选择多张 PNG、JPG 或 BMP，调整顺序后合成为多页 PDF。
- PDF 转 PNG：将 PDF 页面导出为按页编号的 PNG 图片。
- PDF 转 JPG：将 PDF 页面导出为 JPG 图片。
- 页码范围：可只导出连续的指定页码范围。
- 清晰度选项：支持低、标准和高清预设。
- 系统分享：图片转 PDF 成功后可直接调用 Android 分享面板。

## 快速开始

### 从源码运行

需要 Flutter SDK、Android SDK 和已连接的 Android 设备或模拟器。

\`\`\`powershell
cd mobile
flutter pub get
flutter run
\`\`\`

### 使用流程

1. 在首页选择转换类型。
2. 选择输入文件与 Android 系统提供的输出文件夹。
3. 按需要调整页码范围和输出清晰度。
4. 开始转换；完成后可在所选文件夹中查看结果。

## 版本记录

| 版本 | 主要内容 |
| --- | --- |
| v0.1.0 | 图片转 PDF、PDF 转 PNG/JPG、页码范围、清晰度预设与结果分享。 |

## 项目结构

\`\`\`text
mobile/         Flutter 与 Android 应用源码
core/           桌面端历史原型代码
test_fixtures/  自动化测试样例
docs/           使用与验证说明
\`\`\`

## 限制

- 当前只支持 Android。
- PDF 转换支持范围受设备内存、文件大小和页面复杂度限制。
- 发布 APK 不提交到此仓库；请从源码构建或关注 Releases。
