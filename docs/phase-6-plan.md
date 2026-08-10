# 第 6 期计划：PDF 连续页范围导出

## 目标

在已支持的 **PDF → PNG** 与 **PDF → JPG** 中，允许用户导出一个连续的页码范围，而不是只能导出全部页面。

这是第 5 期明确延后的用户可用能力。它只扩展现有两条 PDF 栅格化链路，不新增格式、网络、存储权限或第三方 PDF 引擎。

## 产品规则（冻结）

- 输入仍为一个 1—20 页、最大 100 MiB 的 PDF；原有 MIME、大小、可打开性及页数门禁不变。
- 用户在选中 PDF 后，以两个下拉框选择“起始页”和“结束页”；默认值为 `1—总页数`，即与第 4、5 期的全页导出完全一致。
- 只支持连续、闭区间范围：`1 ≤ 起始页 ≤ 结束页 ≤ PDF 总页数`。不提供任意勾选、跳页、反向范围或多段范围。
- 输出页文件名保留**原 PDF 页码**：例如选择 `3—5`，生成 `003.png`、`004.png`、`005.png`（或 `.jpg`）。这样导出文件可以直接追溯原页；选择全页时文件名保持既有的 `001...` 行为。
- 成功结果中的 `pageCount` 为本次实际导出的页数（`结束页 - 起始页 + 1`），总字节数只统计本次输出。
- 范围不合法时，原生层返回稳定错误码 `INVALID_PAGE_RANGE`，错误消息包含起止页、PDF 总页数和显示名；不得创建输出文件夹或页文件。
- Flutter 侧禁止形成无效状态：用户提高起始页到结束页之后时，同步将结束页提高到起始页；降低结束页到起始页之前时，同步将起始页降低到结束页。原生层仍必须再次校验，不能信任 MethodChannel 参数。

## 技术方案

### Flutter

- 为 `pdf_to_png` 与 `pdf_to_jpg` 各自的不可变 state 增加已选范围（或复用一个纯 Dart 的 `PdfPageRange` 值对象）；选择 PDF 成功时初始化为全页范围。
- 两个页面在 `selected`、`failure` 状态展示范围下拉框；`choosingFolder`、`converting` 时禁用。保留当前的选 PDF、选目录、重试与重置语义。
- 控制器在选目录前读取当前范围，并把一基页码透传到各自 platform interface；Fake platform 的回调签名同步升级，以便 controller 测试断言参数。
- 两条 MethodChannel 的 `convert` 参数增加可选 `startPage`、`endPage`。Flutter 每次调用都传入明确范围；原生对缺失参数保留“全页”兼容默认，防止旧调用方行为改变。

### Android

- PNG/JPG plugin 都解析并进行结构校验：参数必须是整数；缺失则交给 coordinator 按全页默认处理；非整数或明显非法参数返回 `INVALID_ARGS`。
- 两个 coordinator 在转换前重新运行 `PdfInputValidator`，再基于新得到的总页数规范化/校验范围；将范围和验证后的总页数一并放入共享 `PdfRasterConverter.ConvertParams`。
- 在 `PdfRasterConverter` 建立唯一的范围循环：把一基闭区间转换为零基 `startIndex..endIndex`，只打开这些页面，并把原始页号交给命名、编码错误信息和输出计数。不得在 PNG、JPG converter 中复制循环。
- `PdfToPngConverter` 与 `PdfToJpgConverter` 保持为策略外观：PNG 的 MIME/无白底，JPG 的 `image/jpeg`、白底预填充及质量 85 均不变。
- 失败时继续复用 `createdSink` 反序清理、single-flight、`CompletionGuard` 与资源单一所有权；无效范围发生在创建输出目录前，因此清理列表应为空。

## 实施顺序

1. 先在共享 Kotlin 核心定义范围值与边界校验，补齐只渲染选中页、原页号命名、无效范围不产生输出的单元测试。
2. 让 PNG/JPG converter 与 coordinator 透传范围，补通道的参数解析、旧调用缺省全页和错误码测试；完整回归第 4、5 期。
3. 增加 Flutter state、controller、平台接口与两个页面的范围下拉框；补默认全页、范围纠正、取消目录、失败重试、参数透传及成功页数测试。
4. 更新 README、支持矩阵、移动端架构和 `artifacts/phase-6/`。先提交实现与测试，再由规划/验收角色独立复跑门禁。

## 必须测试

### Android（标准 Gradle）

- PNG、JPG 均覆盖：默认缺省范围/显式 `1—总页数` 的全页回归；单页；中间范围（如 3—5）；末页；20 页 PDF 的 `20—20`。
- 断言仅打开所选页面、文件名为原页号、输出页数与字节数正确；JPG 仍满足白底在 render 前、质量 85；PNG/JPG 的扩展名与 MIME 不串用。
- `start < 1`、`end > total`、`start > end`、零/负数、PDF 重新验证后页数变化：均为 `INVALID_PAGE_RANGE`，不创建任何 URI、不启动后台转换。
- 原有损坏 PDF、21 页、100 MiB、渲染/编码/写入/关闭失败、反序清理、single-flight 与 onDestroy 测试全部回归。

### Flutter

- 两个功能的默认范围、上下界联动、重选 PDF 后重置为全页、交互态禁用与 UI 文案。
- 范围、PDF URI、目录 URI 正确透传；目录取消不转换；失败重试保留范围；成功显示实际导出页数。
- 原有图片→PDF、PDF→PNG、PDF→JPG 首页入口及状态机回归。

## 验收门禁

- 实现与文档提交后，工作区干净；不以自定义 JUnit 脚本替代标准 Gradle 测试。
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

- 记录 Flutter/Android 测试数、APK 字节数、SHA-256、相对第 5 期独立验收基线的增量；Debug APK 增量不得超过 1 MiB。
- 第 6 期只做工程验收，不进行模拟器/真机安装；实机体验仍统一留给第 10 期用户验收。

## 不做

- 任意页勾选、多段范围、批量 PDF、页重排、旋转、裁剪或预览缩略图。
- PNG/JPG 的质量、分辨率或 DPI 设置；JPG 质量继续固定为 85。
- PDF→DOCX、DOCX/HTML/Markdown 转换、OCR、云端上传、网络权限、广泛存储权限、第三方 PDF 引擎、iOS 实现。
