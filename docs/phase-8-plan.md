# 第 8 期计划：PDF 导出清晰度预设

## 目标

让现有“PDF 转 PNG”和“PDF 转 JPG”在导出前可选择**低清（96 dpi）**、**标准（144 dpi）**或**高精（216 dpi）**。用户可在清晰度、文件大小与转换耗时之间作出明确选择；默认标准 144 dpi，维持第 4—6 期的输出效果与所有既有语义。

本期只扩展两条既有 PDF 栅格化链路的渲染尺寸；不新增格式、入口、文件权限、网络、第三方 PDF/图像依赖或后台服务。

## 产品规则（冻结）

- 三档且仅三档：`96`、`144`、`216` dpi，UI 分别显示“低清（96 dpi）”“标准（144 dpi，推荐）”“高精（216 dpi）”。不提供任意数值输入或滑杆。
- PDF 被选中后显示清晰度选择；首次选择、重新选择 PDF、取消重置均回到 144 dpi。目录选择取消仍回到 selected 且保留当前清晰度；失败重试也保留当前清晰度。
- 默认 144 dpi 的 PNG/JPG 输出尺寸、范围、文件名、输出目录命名、JPG 质量 85、白底顺序、页数和字节统计，必须与第 6 期完全一致。
- 96/216 dpi 只改变每页渲染 Bitmap 的目标尺寸。输出仍为按原 PDF 页码命名的 PNG 或 JPG 文件，页码范围、1—20 页/100 MiB PDF 门禁、SAF 文件夹、single-flight、失败反序清理和生命周期语义不变。
- 所有档位都继续受最长边 4096 px、单页 1600 万像素和逐页 Bitmap 回收限制。高精不是绕过内存门禁；触达上限时按现有等比缩放规则执行。
- 平台调用缺少清晰度参数时兼容性默认 144；任何非 `96/144/216` 的值必须在原生插件、协调器和共享核心分别拒绝，使用稳定码 `INVALID_RASTER_RESOLUTION`，且**不得创建输出文件夹或页文件**。
- 输出目录不以 dpi 改名，避免把本期配置变化混入既有文件命名契约；结果页数、目录 URI、总字节数的返回结构不变。

## 技术方案

### Android

- 在 `PdfRasterConverter.ConvertParams` 新增一个有默认值的、受限的清晰度字段（推荐以内部枚举/值对象表达三档，而不是把任意 `Int` 传进核心）。PNG 与 JPG 外观转换器只透传该字段，不能各自维护计算公式。
- 将 `computeBitmapSize` 从硬编码 144 dpi 改为以经过校验的预设计算：PDF point 到 px 的比例为 `dpi / 72`。尺寸上限和循环缩放算法保持原样，计算过程使用安全数值类型，避免高精档溢出。
- 两个 MethodChannel 的 `convertPdfToPng` / `convertPdfToJpg` 新增可选 `resolution` 参数。缺失或 `null` 均为 144；字符串、浮点、布尔等非整数返回 `INVALID_ARGS`，整数但不在白名单的值返回 `INVALID_RASTER_RESOLUTION`。协调器和核心再次防御，不信任 Flutter/插件输入。
- 清晰度必须在创建 `createdSink`、输出子文件夹或页文件之前完成校验。`PdfRasterConverter` 仍是唯一的范围循环、尺寸计算、Bitmap/页/fd/流所有权与写入清理核心。
- 不改变 `PdfInputValidator`、PDF MIME/页数/大小限制、`PdfRenderer` 渲染模式、PNG 编码、JPG 的白底预填充和固定 quality=85；不提升 minSdk、不使用 `ImageDecoder`、NDK 或第三方库。

### Flutter

- 新建小型不可变 `RasterResolution` 值对象（或等价枚举），唯一真值为 96/144/216 和标准 144。PNG/JPG 两份 state 都持有该值，选择 PDF 时初始化、重选/重置时复位，目录取消/失败重试时保留。
- 两个页面复用同一清晰度选择组件或严格同构实现，放在页码范围选择旁；转换中禁用选择。说明须写清“清晰度改变像素尺寸与文件大小，JPG 压缩质量固定 85”。
- 两个 platform interface 与 MethodChannel 实现只传已经受限的整数值；不得基于文件名、DPI 文本或 UI 状态猜测清晰度。
- 首页仍是图片→PDF、PDF→PNG、PDF→JPG 三入口；本期是现有页面能力增强，不添加第四入口。

## 实施顺序

1. 先在共享 Android 核心定义三档预设、默认值和“创建任何输出前”的严格校验，补纯 JVM 尺寸/非法值测试。
2. 将清晰度从两条插件→协调器→转换器的参数链完整透传；PNG/JPG 必须使用同一预设和尺寸计算。
3. 接入 Flutter 值对象、state、platform contract、控制器和两个页面选择器，锁定初始化、取消、失败重试和参数透传。
4. 更新 README、支持矩阵、架构文档和 `artifacts/phase-8/`；提交后由规划/验收角色独立复跑门禁。

## 必须测试

### Android（标准 Gradle）

- 96/144/216 dpi 的标准 A4 尺寸分别为 `(793,1122)`、`(1190,1684)`、`(1785,2526)`；横页比例正确；超长边和超过 1600 万像素时三个档位都等比受限。
- 缺失参数默认 144；插件对非整数返回 `INVALID_ARGS`；插件、协调器、核心各自对 `95/145/217` 等非法整数返回 `INVALID_RASTER_RESOLUTION`。核心测试断言非法值时未调用创建子文件夹、未创建页文件、未打开页面。
- PNG 与 JPG 均验证 96/144/216 实际将对应尺寸交给 Bitmap 工厂，并保持原有页码范围/原页码命名。JPG 三档都在 `render` 前白底且 quality=85。
- 144 dpi 的既有尺寸、范围校验、输出/关闭/反序清理、保存取消、single-flight、onDestroy 与 PNG/JPG 互不串用的全量测试回归。

### Flutter

- `RasterResolution` 仅允许三档且默认 144；选 PDF 初始化为标准，重选/重置复位，目录取消/转换失败重试保留用户选择。
- PNG/JPG 页面在 selected/failure 显示三档选择与当前值；converting 时不可改；说明文字明确 JPG 质量固定 85。
- 两条 controller→platform 调用准确传 96/144/216；原有范围、成功页数、取消和重试测试全部回归；首页仍仅三个入口。

## 验收门禁

- 工作区干净；新增源码仅限清晰度预设、必要的 Android/Flutter 传参与 UI、测试和文档。不得引入运行时依赖、网络、广泛存储权限、NDK、第三方 PDF/图像库或新的格式入口。
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

- 记录 Flutter/Android 测试数、Debug APK 字节数与 SHA-256；相对第七期独立基线 `146,196,914 B` 的增量不得超过 1 MiB。
- 第 8 期仅做代码、自动化测试和构建验收。实际 96/144/216 dpi 的视觉清晰度、转换耗时和手机存储表现仍留到第 10 期用户手机集中验收。

## 不做

- 任意 DPI/滑杆、每页不同 DPI、JPEG 质量调整、PNG 压缩级别、颜色/灰度、裁剪/旋转、长图拼接、OCR、PDF 编辑、批量 PDF、ZIP 打包、分享、云端上传、网络、广泛存储权限、iOS。
- 更改第 7 期 BMP 输入白名单，或改变现有 PDF→PNG/JPG 的默认 144 dpi 输出、页码范围、防御校验、命名、保存和清理语义。
