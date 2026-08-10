# 第 4 期计划：单个 PDF 按页导出 PNG 文件夹

> 规划状态：开发者起草版（2026-07-29），按用户指令完成规划草案，待架构师/规划师独立复核后转入实施。

## 唯一目标

用户选择一个 PDF，再选择一个 Android SAF 文件夹；应用把该 PDF 的全部页面按页序渲染为 PNG，写入一个新建的输出子文件夹。

这是第 4 期唯一新增能力。使用 Android 系统 `PdfRenderer` 和 `OpenDocumentTree`，不增加运行时依赖、网络权限或许可证负担。

## 固定产品规则

- 只接受一个 PDF；最少 1 页、最多 20 页。超过返回 `TOO_MANY_PAGES`，不截断；
- 输入 PDF 上限 100 MiB；无效、损坏、受密码保护或系统无法打开的 PDF 返回 `PDF_OPEN_FAILED`；不支持密码输入；
- 每页输出 PNG，不做 JPG、WebP、ZIP、页码范围、旋转、裁剪或质量设置；
- 渲染目标为 144 dpi；任一页最长边不超过 4096 px、总像素不超过 1600 万，按比例下调；
- 输出子文件夹名称为“原文件名_PNG_时间戳”，其中页文件按 `001.png`、`002.png` … 命名；不覆盖既有用户文件；
- 所有 PDF 验证在请求输出文件夹前完成；输出目录取消则不创建文件；
- 任一页渲染或写入失败时，尽力删除本次新建的页文件和输出子文件夹；清理失败不得覆盖原始错误；
- 转换完成仅返回输出目录 URI、页数和总字节数；全部处理在本地完成。

## Flutter 范围

1. 增加一个轻量入口页，提供“图片转 PDF”和“PDF 转 PNG”两个功能入口；不改视觉体系。
2. 新建 `pdf_to_png` 功能状态和控制器：`idle`、`selected`、`choosingFolder`、`converting`、`success`、`failure`。
3. 选择后显示原生返回的 PDF 文件名、页数、文件大小；不从 `content://` URI 猜名称。
4. 用户选择目标文件夹后才能开始转换；取消文件/目录选择保留当前可用状态；失败保留 PDF 元数据以便重试。
5. MethodChannel 合约：`pickPdf`、`pickOutputDirectory`、`convertPdfToPng`，成功返回 `{directoryUri, pageCount, size}`。

## Android 范围

1. `OpenDocument` 限定 `application/pdf`；通过 `OpenDocumentTree` 获取可写输出树 URI，并保留本次授权。
2. `PdfInputValidator` 在打开目录选择器前完成 MIME、显示名、大小（沿用三段大小查询）与 `PdfRenderer` 页数/可打开性验证。
3. `PdfToPngCoordinator` 延续 `CompletionGuard`、single-flight 和销毁竞争处理；请求使用不可变 PDF/目录元数据。
4. `PdfToPngConverter` 通过 `ParcelFileDescriptor` 和 `PdfRenderer` 顺序处理页面：一次只打开一页、一次只持有一张 Bitmap、渲染后写 PNG 并立即回收。
5. 使用 `DocumentFile`/`ContentResolver` 在新建子文件夹内创建文件；记录仅由本请求创建的 URI，失败时按相反顺序尽力清理。
6. 关闭 `PdfRenderer`、`ParcelFileDescriptor`、页面和输出流均由单一所有者负责，成功和异常路径恰好释放一次。

## 项目结构变化

完全新增文件（不改动既有 `ImageToPdf*` 与 `image_to_pdf` 功能）：

- Android（`mobile/android/app/src/main/kotlin/com/example/docushift_mobile/`）：
  - `PdfToPngPlugin.kt` — 新平台通道 `com.example.docushift_mobile/pdf_to_png`，委派给 Coordinator。
  - `PdfToPngCoordinator.kt` — 镜像 `ImageToPdfCoordinator`：single-flight + `CompletionGuard` + 销毁竞争 + 三方法 `pickPdf` / `pickOutputDirectory` / `convertPdfToPng`。
  - `PdfToPngConverter.kt` — `PdfRenderer` 逐页渲染 + PNG 写入，单一所有者关闭语义。
  - `PdfInputValidator.kt` — MIME / 显示名 / 三段大小 / 页数 / 可打开性门禁。
- Flutter（`mobile/lib/features/pdf_to_png/`）：
  - `pdf_to_png_state.dart` — 状态机 `idle / selected / choosingFolder / converting / success / failure`。
  - `pdf_to_png_platform.dart` + `pdf_to_png_platform_interface.dart` — 通道合约常量 + 抽象/生产/`Fake` 实现。
  - `pdf_to_png_controller.dart` — 镜像 `ImageToPdfController`，含「选 PDF → 选目录 → 转换」两步。
  - `pdf_to_png_page.dart` — 显示 PDF 原生名/页数/大小；目录未选不启动；取消保留状态。
- Flutter 入口：`mobile/lib/home_page.dart`（轻量双入口页）；修改 `mobile/lib/main.dart` 以 `HomePage` 为首页。

修改文件：

- `MainActivity.kt` — 新增 `pdfToPngCoordinator` 字段，onCreate 初始化 + `registerLaunchers`，`configureFlutterEngine` 注册 `PdfToPngPlugin`，`onDestroy` 调用其 `onDestroy`。

复用与共享：

- 直接复用 `CompletionGuard.kt` 与 `ImageToPdfCoordinator` 的 single-flight / 恰好一次回调模式。
- 显示名查询与三段大小查询逻辑若第 4 期需要，抽取到共享 `FileProbe.kt` 供两 Validator 共用；否则在 `PdfInputValidator` 内联等价实现（优先内联，避免跨功能耦合）。
- 不新增任何第三方运行时依赖、不新增权限。

## 实施顺序

按「先平台能力、后 UI、再门禁」的顺序，每步可独立编译与测试：

1. **共享探查（可选）**：确认 `PdfInputValidator` 是否复用显示名/三段大小查询；若复用则抽取 `FileProbe.kt`，否则内联。
2. **`PdfInputValidator.kt`**：MIME=`application/pdf`、显示名、大小（三段查询，上限 100 MiB）、页数（用 `PdfRenderer` 断言 `pageCount in 1..20`，>20 → `TOO_MANY_PAGES`）、可打开性（密码/损坏 → `PDF_OPEN_FAILED`）。返回不可变 `ValidatedPdf(name, sizeBytes, pageCount, uri)`。附纯逻辑单测：0/1/20/21 页、100 MiB 边界、损坏/受保护映射。
3. **`PdfToPngConverter.kt`**：注入 `PdfRendererFactory` / 页面渲染 / PNG 写入端口；`convert(pdfUri, outputDirUri)` 顺序处理——开 `PdfRenderer` → 逐页 `openPage` → 按 144 dpi 与「最长边 ≤4096 px、总像素 ≤1600 万」缩放渲染到 Bitmap → 写 `NNN.png` → 立即 `closePage` 与 `recycle`；单一所有者 `finally` 关闭 renderer / fd / stream，恰好一次。页失败带「第 N 页（显示名）」稳定码 `PAGE_RENDER_FAILED` / `OUTPUT_WRITE_FAILED` 并停止后续页。附注入端口的顺序/资源释放/清理单测。
4. **`PdfToPngCoordinator.kt`**：实现 `pickPdf`（`OpenDocument` 限定 pdf）、`pickOutputDirectory`（`OpenDocumentTree` 保留授权）、`convertPdfToPng`（先验证再请求目录→转换）。沿用 `AtomicReference` + `CompletionGuard` 保证 single-flight 与恰好一次；`onDestroy` 与后台完成竞争只回调一次。注入接缝同 `ImageToPdfCoordinator`（`resolver` / `convertExecutor` / `pickLauncher` / `treeLauncher` / `validateProvider` / `uriParser` / `outputDeleter`）。失败时按相反顺序删除本次新建页文件与输出子文件夹，清理异常不覆盖原始错误。
5. **`PdfToPngPlugin.kt`**：新通道 + 三方法；快速失败 `INVALID_ARGS` / `TOO_MANY_PAGES` / `BUSY`，其余委派 Coordinator。
6. **`MainActivity.kt`**：接线新 coordinator 与 plugin 的生命周期。
7. **Flutter `pdf_to_png` feature**：state → platform 合约 → controller → page，逐一镜像 `image_to_pdf` 并实现两步选择流。
8. **`home_page.dart` + `main.dart`**：轻量双入口页，不改视觉体系。
9. **Flutter 单测**：功能入口、PDF 元数据显示、选择取消保留状态、目录取消不启动转换、参数透传、成功/失败/重试。
10. **文档与门禁**：更新 `conversion-support-matrix.md`（PDF→PNG 改 ✅）、README 第 4 期小节与十期额度改「工程验收通过」、`artifacts/phase-4/` 记录提交号/命令/测试数/APK 大小/SHA-256；依次通过 `dart analyze --no-pub`、`flutter test --no-pub`、标准 `gradlew.bat testDebugUnitTest --rerun-tasks`、干净构建 APK 且增量 ≤1 MiB。

## 稳定错误码

保留既有 `INVALID_ARGS`、`BUSY`、`CANCELLED`、`DESTROYED`、`FILE_TOO_LARGE`、`FILE_SIZE_UNKNOWN`。新增：

- `TOO_MANY_PAGES`
- `PDF_OPEN_FAILED`
- `OUTPUT_DIR_UNAVAILABLE`
- `PAGE_RENDER_FAILED`
- `OUTPUT_WRITE_FAILED`

页级错误必须携带“第 N 页”和原生 PDF 显示名。目录清理错误不得替换这些错误码。

## 必须测试

Flutter：功能入口、PDF 原生名称/页数进入状态、选择取消保留状态、目录取消不启动转换、参数透传、成功/失败/重试。

Android 标准 `testDebugUnitTest`：

- 0、1、20、21 页及 100 MiB 边界；损坏/受保护 PDF 映射 `PDF_OPEN_FAILED`；
- 全部输入验证发生在目录请求之前；
- 三页按 `001`、`002`、`003` 顺序输出；单页兼容；
- 逐页打开、渲染、PNG 写入、回收；中途渲染或写入失败停止后续页且释放当前资源；
- 页级错误包含序号与显示名；
- 失败时仅清理本次创建的输出，清理异常不覆盖原错误；
- 目录选择取消、第二次请求 `BUSY`、`onDestroy` 与后台完成竞争仍恰好回调一次；
- 输出流、页面、`PdfRenderer`、文件描述符的成功/失败关闭语义。

测试不能只依赖纯函数：至少通过可注入的 `PdfRenderer`/目录写入端口，从 Coordinator 和 Converter 生产入口证明顺序、资源释放与清理。

## 本期不做

- PDF 转 JPG、WebP、ZIP，或多 PDF 批量转换；
- PDF 页面范围、密码输入、注释、编辑、合并、分享、后台任务、历史记录；
- iOS、第三方 PDF 引擎、网络服务或广泛存储权限；
- 模拟器/真机作为硬门禁（仍统一留至第 10 期）。

## 通过条件

- 代码定义的完整流程可将 1—20 页 PDF 导出为按页序命名的 PNG 文件夹；
- 所有输入、页数、大小、资源释放、失败清理和生命周期条件均有自动测试；
- `flutter analyze --no-pub`、全部 Flutter 测试和标准 `gradlew.bat testDebugUnitTest --rerun-tasks` 通过；
- 不新增运行时依赖；Debug APK 相对第 3 期独立基线增量不超过 1 MiB；
- 从干净、已提交的源码构建 APK，并在 `artifacts/phase-4/` 记录提交号、命令、测试数、大小和 SHA-256；
- 本期只做工程验收，不取代第 10 期用户真机验收。
