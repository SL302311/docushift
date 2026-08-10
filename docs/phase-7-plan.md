# 第 7 期计划：BMP 图片合并为 PDF

## 目标

让现有“图片转 PDF”功能接受 BMP，并可与 PNG/JPG 混合选择后按用户当前顺序合并为一个多页 PDF。

这是支持矩阵中已列出的低风险格式缺口：Android 官方媒体格式表将 BMP 列为系统可解码图片格式；本期复用现有 `BitmapFactory` 解码、EXIF 方向兜底、A4 布局、SAF 保存、失败清理和 single-flight，不引入第三方图像/PDF 引擎。[Android supported media formats](https://developer.android.com/media/platform/supported-formats)

## 产品规则（冻结）

- 输入允许 PNG、JPEG、BMP；一次选择 1—20 张，允许三种格式混合，顺序仍由用户上移/下移/删除决定。
- BMP 只接受内容提供方报告为 `image/bmp` 或 `image/x-ms-bmp` 的文件。不得根据文件名后缀把未知 MIME、`application/octet-stream` 或 GIF/WebP/HEIF 当作 BMP 放行。
- 单文件 30 MiB、总量 200 MiB、原始像素最多 4,000 万、解码失败、选择取消、保存取消、失败输出删除和单请求语义保持不变。
- BMP 与 PNG 一样不要求 EXIF 方向信息；现有 EXIF 读取失败必须安全地降级为“正常方向”，不能使 BMP 转换失败。
- 输出仍为一个单独 PDF：每张已验证图片对应一页，保持当前 A4 横竖页、白底、等比缩放和实际字节数返回方式。BMP 不新增输出格式、质量、压缩或页面设置。
- 错误仍使用既有稳定码：不支持 MIME 为 `UNSUPPORTED_FORMAT`，不可解码/尺寸非法为 `DECODE_FAILED`，超像素为 `IMAGE_TOO_LARGE`；多图错误须保留第几张和原生显示名。

## 技术方案

### Android

- `ImageInputValidator` 将 BMP 的两个允许 MIME 纳入统一白名单；`ValidatedImage.mimeType` 原样保留，禁止引入“按后缀猜测 MIME”的旁路。
- 继续用 `BitmapFactory` 先做 bounds 解码、按既有 `calculateSampleSize` 降采样，再完成实际解码。不得为了 BMP 替换整个 PNG/JPG 解码链，也不得提升 `minSdk` 或引入 `ImageDecoder`/NDK/第三方依赖。
- `ImageToPdfConverter.decodeOrientedBitmap` 保持公共生产路径。确认 BMP 的 EXIF 读取异常由既有 catch 变为 `ORIENTATION_NORMAL`；不得对 BMP 强行旋转或为此新增元数据写入。
- `ImageToPdfCoordinator` 与 `ImageToPdfPlugin` 的 MethodChannel 方法、参数、并发和清理契约不变；系统选择器现为 `image/*`，只在原生验证层决定放行。

### Flutter

- 不增加首页入口、页面或运行时依赖。更新图片转 PDF 的选择说明、已选格式提示与测试文案为“PNG/JPG/BMP”。
- Dart 控制器仍只接收原生已验证的 `{uri, name}` 列表，不自行识别扩展名，也不改变排序、删除、保存取消和重试行为。

## 实施顺序

1. 先为 `ImageInputValidator` 增加 BMP MIME 白名单与纯 JVM 测试，锁定两个允许值及 GIF/未知 MIME 的拒绝行为。
2. 以受控 bounds/decoder 接缝验证 BMP 单张与 PNG/JPG/BMP 混合多张进入同一个 `ImageToPdfConverter.convertMany` 流程，顺序、页数、回收和错误定位不回退。
3. 更新图片转 PDF 页面说明及 Flutter 测试；不得改动 PDF→PNG/JPG 的页面、通道或范围功能。
4. 更新 README、支持矩阵、移动端架构和 `artifacts/phase-7/`，提交后由规划/验收角色独立复跑门禁。

## 必须测试

### Android（标准 Gradle）

- `image/bmp`、`image/x-ms-bmp` 在大小和像素均合法时通过；`image/gif`、`application/octet-stream`、空 MIME 仍为 `UNSUPPORTED_FORMAT`。
- BMP 边界：30 MiB 通过/超过拒绝；4,000 万像素通过/超过拒绝；bounds 无法解码为 `DECODE_FAILED`。
- 单张 BMP；PNG/JPG/BMP 混合三张按原顺序生成三页；BMP 位于中间时的解码失败须包含第 2 张及显示名，后续页不再处理，输出由协调器清理。
- BMP 走正常方向的 EXIF 兜底，不创建额外旋转 Bitmap；现有 PNG/JPG EXIF 旋转测试、1/20 张、总量、保存取消、single-flight、onDestroy、写入失败和反序清理全部回归。
- 仅在源码支持的 JVM 接缝中测试，不以 `run_tests.sh` 替代 `gradlew.bat testDebugUnitTest --rerun-tasks`。

### Flutter

- 图片转 PDF 页面在 idle/selected 状态明确写出 PNG/JPG/BMP；首页说明同步。
- BMP 元数据仅从原生返回的显示名呈现；混合列表的上移、下移、删除、取消保存、失败重试和按顺序传给平台的现有测试全部回归。
- PDF→PNG/JPG 的范围选择与首页三个入口回归。

## 验收门禁

- 提交后的工作区干净；新增源码仅限 BMP 输入白名单、必要测试和文档，不能新增运行时依赖、网络/广泛存储权限、NDK 配置或第三方编解码库。
- 在 `D:\docushift_workspace\mobile` 独立执行：

  ```powershell
  D:\flutter\bin\flutter.bat analyze --no-pub
  D:\flutter\bin\flutter.bat test --no-pub
  cd android
  .\gradlew.bat testDebugUnitTest --rerun-tasks
  cd ..
  D:\flutter\bin\flutter.bat clean
  D:\flutter\bin\flutter.bat pub get --offline
  D:\flutter\bin\flutter.bat build apk --debug --no-pub
  ```

- 记录测试数、Debug APK 字节数、SHA-256 与相对第 6 期独立验收基线的增量；增量不得超过 1 MiB。
- 本期只做代码、自动化测试和构建验收。真实 BMP 文件选择及实际 PDF 页面效果仍留在第 10 期用户手机集中验收。

## 不做

- GIF、WebP、HEIF、ICO、WBMP、AVIF、TIFF，或按文件名后缀猜测未知 MIME。
- 图片→BMP、图片压缩、图片裁剪/旋转/滤镜、PDF 编辑、OCR、批量 PDF、云端上传、网络或广泛存储权限、iOS 实现。
- 更改 PNG/JPG 的现有输入语义、图片数量/大小/像素门禁、PDF 布局和输出保存流程。
