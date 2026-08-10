# DocuShift

移动端本地格式转换工具。当前仅 Android；原 Windows 桌面端已冻结。

当前进度：**第 2—9/10 期工程验收通过；第 10/10 期已规划，等待候选 APK 与用户真机验收。**

## 协作约定

- `开发模式模板-已有代码.md` 是本项目的分期协作约定。
- 开发者负责实现；规划/验收角色负责规划、代码复核、独立测试和验收记录。
- 每期只解决一个主问题；未通过即在原期返工，不占用下一期额度。
- 第 3—9 期每期必须新增或明显完善一个可用能力。
- 第 10 期再交付候选 APK，由用户在自己的 Android 手机上集中体验验收。
- 用户文件只在本地处理，不申请广泛存储或网络权限。

## 当前能力

### 图片转 PDF（第 2—3 期）

选择 1—20 张 PNG/JPG，显示原生文件名，可上移、下移、删除并按当前顺序合并为多页 PDF。支持大小/像素/总量门禁、A4 横竖页、透明白底、EXIF 方向修正、稳定错误码、失败输出清理和 single-flight。已支持 PNG/JPG/BMP 混合输入。

### PDF 转 PNG（第 4 期）

选择一个 1—20 页、最大 100 MiB 的 PDF 和一个 Android SAF 输出目录。应用创建新的输出子文件夹，把全部页面按 `001.png`、`002.png`…顺序渲染为 PNG；144 dpi，最长边不超过 4096 px、单页不超过 1600 万像素。

- 损坏或受密码保护的 PDF 返回 `PDF_OPEN_FAILED`；
- 页渲染/写入错误带页码与原文件名；
- 页文件创建后立即纳入失败清理；创建后打不开流会即时删除；
- PDF 变化导致页数不一致会拒绝转换；fd、页、渲染器、Bitmap 和输出流均有单一资源所有权。

### PDF 转 JPG（第 5 期）

选择一个 1—20 页、最大 100 MiB 的 PDF 和一个 Android SAF 输出目录。应用创建新的输出子文件夹，把全部页面按 `001.jpg`、`002.jpg`…顺序渲染为 JPG；144 dpi，最长边不超过 4096 px、单页不超过 1600 万像素，**固定质量 85**。

- 与 PDF→PNG 共用同一栅格化核心（[`PdfRasterConverter`](mobile/android/app/src/main/kotlin/com/example/docushift_mobile/PdfRasterConverter.kt)），仅输出格式策略不同（扩展名 / MIME / 文件夹后缀 / 白底预填充 / 编码器）；
- JPEG 无透明通道：**每页渲染前以不透明白色预填充 Bitmap**，保证透明/未绘制区域不发黑；
- 损坏或受密码保护的 PDF 返回 `PDF_OPEN_FAILED`；页渲染/编码/写入错误带页码与原文件名；
- 页文件创建后立即纳入失败清理；创建后打不开流会即时删除；PDF 变化导致页数不一致会拒绝转换；复用第 4 期的 fd/页/渲染器/Bitmap/输出流单一资源所有权与反序清理。
- 独立平台通道 `com.example.docushift_mobile/pdf_to_jpg`，与 PDF→PNG 互不干扰，PNG 的 MethodChannel 契约保持不变。

### PDF 连续页范围导出（第 6 期）

在已支持的 PDF→PNG 与 PDF→JPG 两条链路中，允许用户导出一个**连续的页码范围**（1-based 闭区间），而不是只能导出全部页面。这是第 5 期延后的用户可用能力，只扩展现有两条栅格化链路，不新增格式、网络、存储权限或第三方 PDF 引擎。

### 第 6 期首次验收与 R1 复验（2026-07-30，通过）

- 首次验收的 R1 为核心将 `startPage < 1`、`endPage > total` 静默夹紧后继续导出。返工已删除夹紧逻辑，改为直接校验原始输入并返回 `INVALID_PAGE_RANGE`。
- `PdfRasterConverterTest` 直接覆盖 `start=0`、负数、`end=total+1`；均断言不创建 URI、不打开页面，且错误消息保留原始页码。
- 复验通过：`flutter analyze --no-pub` 为 0 issue，`flutter test --no-pub` 为 105/105，标准 `gradlew.bat testDebugUnitTest --rerun-tasks` 通过。离线干净 APK 为 146,196,894 B，较第五期独立基线 +7,172 B（≤1 MiB）；本次构建 SHA-256 为 `9528C26FD9186F49B36019048139292E144291937DF086861B726FE0ABB29483`。

- 选中 PDF 后以两个下拉框选择「起始页」「结束页」，默认 `1—总页数`（与全页导出一致）；
- 只支持连续闭区间：`1 ≤ 起始页 ≤ 结束页 ≤ PDF 总页数`；不提供任意勾选、跳页、反向或多段范围；
- 输出页文件名**保留原 PDF 页码**：选 `3—5` 生成 `003.png`/`004.png`/`005.png`（或 `.jpg），可直接追溯原页；全选时保持 `001…`；
- 成功结果的 `pageCount` 为本次实际导出页数（`结束页 - 起始页 + 1`），总字节数只统计本次输出；
- 范围不合法时原生层返回稳定错误码 `INVALID_PAGE_RANGE`（含起止页/总页数/显示名），**不创建输出文件夹或页文件**；
- Flutter 侧禁止无效状态：起始页超过结束页时同步把结束页提高；结束页低于起始页时同步把起始页降低；原生层仍再次校验，不信任 MethodChannel 参数；
- 实现落在共用核心 [`PdfRasterConverter`](mobile/android/app/src/main/kotlin/com/example/docushift_mobile/PdfRasterConverter.kt) 的唯一范围循环与 [`PdfPageRange`](mobile/lib/features/pdf_page_range.dart) 值对象；PNG/JPG converter 与 coordinator 仅透传范围，复用既有白底/质量 85/失败清理。

### PDF 导出清晰度（第 8 期）

在 PDF→PNG 与 PDF→JPG 两条链路中，为 96 dpi（低清）、144 dpi（标准，默认）和 216 dpi（高精）三档预设提供选择。清晰度改变每页渲染 Bitmap 像素尺寸；不影响输出目录命名、页码范围、JPG 质量 85、逐页 Bitmap 回收或输出清理。

- 默认 144 dpi 与第 4—7 期输出尺寸、命名、页数/字节统计完全一致；
- 96/216 dpi 仅改变渲染尺寸：PDF point × (dpi / 72)，仍受最长边 4096 px、单页 1600 万像素上限约束；
- 三档固定，不提供任意输入或滑杆；非法值由插件/协调器/核心三层分别返回 `INVALID_RASTER_RESOLUTION`，不创建输出文件夹或页文件；
- 参数缺失时兼容性默认 144；PNG/JPG 共用同一 `RasterResolution` 预设和 `computeBitmapSize` 尺寸计算核心。[`RasterResolution`](mobile/lib/features/raster_resolution.dart)

### 转换结果分享（第 9 期）

图片转 PDF 成功后可直接调用 Android 系统分享面板发送刚生成的单个 PDF。分享只使用 SAF `content://` 输出 URI 和临时读权限；不创建副本、不上传，也不改变已成功的转换结果。PDF→PNG/JPG 的文件夹结果不在本期范围内。第 9 期工程验收已通过，系统面板与目标应用兼容性留至第 10 期真机验收。

## 第 4 期独立验收（2026-07-29）

结论：**通过。** 未进行模拟器或真机安装验收，按约定留至第 10 期。

验收对象：实施提交 `a519c87`、返工提交 `1039c61`。所有 Flutter 命令在 ASCII 目录联接 `D:\docushift_workspace\mobile` 中执行。

| 门禁 | 独立结果 |
|---|---|
| `flutter analyze --no-pub` | 0 issue |
| `flutter test --no-pub` | 68/68 通过 |
| 标准 `gradlew.bat testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL；JUnit 114/114，0 failure，0 error |
| `flutter clean` + 离线 `flutter pub get --offline` + `flutter build apk --debug --no-pub` | 成功 |
| Debug APK | 146,161,906 字节，较第 3 期 +27,272 字节（≤1 MiB） |
| APK SHA-256 | `EF3F58BFCDD929B03F614F447D8FC6D5748AFD446CF6BC11F04CF834DE579463` |

标准 Gradle 入口在当前环境实际可用；`run_tests.sh` 不属于验收替代命令。

## 第 5 期返工与独立验收（2026-07-30）

第 5 期初验发现 JPG 白底填充顺序和 Android 测试编译问题；返工后已由规划/验收角色独立复验并**通过**。

### 返工执行记录（2026-07-30）

- 初验阻塞项：R1（JPG 白底 `eraseColor` 在 `page.render` 之后，覆盖已渲染内容→空白页）；
  R2（本期新增 Android 测试 `FakeRenderer.pages` 缺失从未编译运行、生产 `RealChildOutputOpener` 删除契约未直接覆盖）。
- 返工要点：
  - R1：白底 `eraseColor(Color.WHITE)` 移至 `page.render(bitmap)` **之前**（[`PdfRasterConverter`](mobile/android/app/src/main/kotlin/com/example/docushift_mobile/PdfRasterConverter.kt) 第 172–176 行）；
  - R2a：补全 `FakeRenderer.pages` 字段，使共用核心测试可编译运行；
  - R2b：新增生产 `RealChildOutputOpener` 删除契约测试（创建成功但流为 null → 立即删除并返回 null），`RealJpgStreamWriter` 提为 `internal`；
  - 补强：白底断言 erase 早于 render（同页顺序）、20 页成功/0 页拒绝测试、JPG 编码异常区分 `IOException`→`OUTPUT_WRITE_FAILED`、其余→`PAGE_RENDER_FAILED`、`buildFolderName` 改用 UTC 时区保证跨环境时间戳确定性。
- 返工后门禁：

| 门禁 | 结果 |
|---|---|
| `flutter analyze --no-pub` | 0 issue |
| `flutter test --no-pub` | 89/89 通过 |
| `run_tests.sh`（JUnitCore，绕开 AGP9 worker 缺陷） | 150/150（13 类，0 failure，0 error） |
| `flutter clean` + 离线 `flutter pub get --offline` + `flutter build apk --debug --no-pub` | 成功 |

### 实施要点（工程基线）

- 抽取共用栅格化核心 [`PdfRasterConverter`](mobile/android/app/src/main/kotlin/com/example/docushift_mobile/PdfRasterConverter.kt)，将第 4 期 PDF→PNG 逐页流程（PDF/页/fd/Bitmap/输出流单一所有权、严格页数校验、createdSink 登记与失败清理）收敛为 PNG 与 JPG 共用入口；
- [`PdfToPngConverter`](mobile/android/app/src/main/kotlin/com/example/docushift_mobile/PdfToPngConverter.kt) 重构为向共用核心传入 PNG 策略的兼容外观，公开 API（`ConvertParams` / `convert` 签名 / `PngEncoder` / 命名与尺寸函数 / 端口接口）完全不变，确保第 4 期全部测试原样回归；
- 新增 [`PdfToJpgConverter`](mobile/android/app/src/main/kotlin/com/example/docushift_mobile/PdfToJpgConverter.kt)（JPG 策略：扩展名 jpg、MIME `image/jpeg`、文件夹后缀 `_JPG_`、白底预填充、`Bitmap.compress(JPEG, 85, stream)` 检查返回值）、[`PdfToJpgCoordinator`](mobile/android/app/src/main/kotlin/com/example/docushift_mobile/PdfToJpgCoordinator.kt) 与 [`PdfToJpgPlugin`](mobile/android/app/src/main/kotlin/com/example/docushift_mobile/PdfToJpgPlugin.kt)（独立通道 `com.example.docushift_mobile/pdf_to_jpg`）；
- Flutter 新增「PDF 转 JPG」首页入口与 [`pdf_to_jpg`](mobile/lib/features/pdf_to_jpg/) 功能包，与 `pdf_to_png` 同构；
- 不新增运行时依赖、网络/广泛存储权限或第三方 PDF 引擎。

### 第 5 期独立验收（通过）

- 结论：**通过**。返工后的白底顺序、Android 测试编译与页范围外的既有 PNG/JPG 行为均已复核。
- 验收对象：第 5 期工程实施提交 + 返工（R1/R2）。所有 Flutter / 验证命令在 ASCII 目录联接 `D:\docushift_workspace\mobile` 中执行。
- 详细阻塞项/返工/门禁见 [`artifacts/phase-5/independent-acceptance.md`](artifacts/phase-5/independent-acceptance.md)；构建证据见 [`artifacts/phase-5/build-evidence.md`](artifacts/phase-5/build-evidence.md)。

| 门禁 | 独立结果 |
|---|---|
| `flutter analyze --no-pub` | 0 issue |
| `flutter test --no-pub` | 89/89 通过 |
| 标准 `gradlew.bat testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL；163/163，0 failure，0 error |
| `flutter clean` + 离线 `flutter pub get --offline` + `flutter build apk --debug --no-pub` | 成功 |
| Debug APK | 146,189,722 字节，较第 4 期 +27,816 字节（≤1 MiB） |
| APK SHA-256 | `A449A2B564ECB114F8351B0DE5C856591D810BC8D038083B879FA21EE62B61E4` |

> 标准 Gradle 门禁在独立复验环境中可用；不以 `run_tests.sh` 作为验收替代。未做真机/模拟器安装，按约定留至第 10 期用户验收。

## 验证命令

```powershell
cd D:\docushift_workspace\mobile
D:\flutter\bin\flutter.bat pub get
D:\flutter\bin\flutter.bat analyze --no-pub
D:\flutter\bin\flutter.bat test --no-pub

cd android
.\gradlew.bat testDebugUnitTest --rerun-tasks
cd ..

D:\flutter\bin\flutter.bat clean
D:\flutter\bin\flutter.bat pub get --offline
D:\flutter\bin\flutter.bat build apk --debug --no-pub
```

## 十期额度

| 期数 | 主问题 | 状态 |
|---|---|---|
| 1/10 | Flutter Android 工程基线 | 已完成 |
| 2/10 | 单张 PNG/JPG 转单页 PDF 工程闭环 | 工程验收通过 |
| 3/10 | 多张 PNG/JPG 按序合并为多页 PDF | 工程验收通过 |
| 4/10 | 单个 PDF 按页导出为 PNG 文件夹 | 工程验收通过 |
| 5/10 | 单个 PDF 按页导出为 JPG 文件夹 | 工程验收通过 |
| 6/10 | PDF→PNG/JPG 连续页范围导出 | 工程验收通过 |
| 7/10 | PNG/JPG/BMP 混合图片按序合并为多页 PDF | 工程验收通过 |
| 8/10 | PDF→PNG/JPG 三档清晰度（96/144/216 dpi）导出 | 工程验收通过 |
| 9/10 | 图片→PDF 成功结果的 Android 系统分享 | 工程验收通过 |
| 10/10 | 全量回归、候选 APK 与用户验收交付 | 已规划，待实施 |

## 相关文档

- `artifacts/phase-2/`、`artifacts/phase-3/`、`artifacts/phase-4/`：各期独立证据。
- `artifacts/phase-5/`：第 5 期工程实施证据（build-evidence.md / automated-tests.md / independent-acceptance.md）。
- `docs/phase-4-plan.md`：第 4 期范围与门禁。
- `docs/phase-5-plan.md`：第 5 期 PDF 转 JPG 实施与验收计划。
- `docs/phase-6-plan.md`：第 6 期 PDF 连续页范围导出实施与验收计划。
- `docs/phase-7-plan.md`：第 7 期 BMP 图片合并为 PDF 实施与验收计划。
- `docs/phase-8-plan.md`：第 8 期 PDF 导出清晰度预设实施与验收计划。
- `docs/phase-9-plan.md`：第 9 期图片→PDF 结果系统分享实施与验收计划。
- `docs/phase-10-plan.md`：第 10 期候选 APK 与用户手机验收计划。
- `docs/conversion-support-matrix.md`：格式支持范围。
- `docs/mobile-architecture.md`：移动端架构。
