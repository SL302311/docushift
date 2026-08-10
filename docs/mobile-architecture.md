# DocuShift 移动端架构设计

> 版本：v1.5（第 2—9/10 期工程验收通过；第 10 期已规划）
>
> 状态：第 2—9 期工程验收通过；图片→PDF 成功态已具备受限的 Android SAF URI 分享适配层，真机兼容性留至第 10 期

## 架构总览

```
┌─────────────────────────────────────────────┐
│                UI 层 (Flutter Widget)        │
│  ┌──────────┐ ┌──────────┐ ┌──────────────┐  │
│  │ FilePicker │ │ Progress  │ │ ResultView   │  │
│  └────┬─────┘ └──────────┘ └──────┬───────┘  │
├───────┴───────────────────────────┴──────────┤
│          领域层 (Dart, 纯逻辑)                │
│  ┌────────────────────────────────────────┐   │
│  │ ConversionEngine (interface)           │   │
│  │ ConversionJob / ConversionResult       │   │
│  │ ConversionException / ErrorCode        │   │
│  └────────────────────────────────────────┘   │
├──────────────────────────────────────────────┤
│          平台适配层 (Kotlin / Swift)          │
│  ┌─────────────┐  ┌──────────────────────┐   │
│  │ FileAccess   │  │ NativeConverter      │   │
│  │ (SAF/DP)     │  │ (平台特定引擎调用)   │   │
│  └─────────────┘  └──────────────────────┘   │
├──────────────────────────────────────────────┤
│          Android 系统转换能力                   │
│  ┌──────────────┐ ┌────────────────────────┐  │
│  │ PdfDocument  │ │ Bitmap/ImageDecoder    │  │
│  └──────────────┘ └────────────────────────┘  │
└──────────────────────────────────────────────┘
```

## 关键设计决策

1. **领域层隔离**：所有转换逻辑通过 `ConversionEngine` 接口抽象，平台差异由适配层处理，UI 不直接依赖引擎实现。
2. **本地优先**：不上传用户文件，不依赖服务端。
3. **Android 先行**：iOS 在 macOS/Xcode 就绪后对齐，领域层代码共用。

## 领域接口定义

### ConversionJob

```dart
/// 单次转换任务的状态模型。
class ConversionJob {
  final String id;
  final List<String> inputPaths;
  final String targetFormat;
  final JobState state;
  final double progress; // 0.0 ~ 1.0
  final List<ConversionResult> results;
  final List<ConversionException> errors;
}

enum JobState { queued, running, succeeded, partiallyFailed, failed, cancelled }
```

### ConversionResult

```dart
class ConversionResult {
  final String outputPath;
  final int sizeBytes;
  final DateTime completedAt;
  final String? checksum;
}
```

### ConversionEngine（接口）

```dart
/// 转换引擎的抽象接口，所有具体实现必须遵守此接口。
abstract class ConversionEngine {
  /// 引擎名称（用于日志/诊断）。
  String get name;

  /// 本引擎支持的输入格式列表（扩展名，小写，含点号）。
  Set<String> get supportedInputFormats;

  /// 本引擎支持的输出格式列表（扩展名，小写，含点号）。
  Set<String> get supportedOutputFormats;

  /// 判断给定输入→输出是否为本引擎处理。
  bool canHandle(String inputPath, String targetFormat);

  /// 执行单文件转换。
  /// 返回输出文件路径，或抛出 ConversionException。
  Future<ConversionResult> convert({
    required String inputPath,
    required String targetFormat,
    void Function(double progress, String message)? onProgress,
    CancelToken? cancelToken,
  });
}
```

### ConversionException

```dart
class ConversionException implements Exception {
  final ErrorCode code;
  final String message;
  final String? detail;
}

enum ErrorCode {
  fileNotFound,
  unsupportedFormat,
  engineNotAvailable,
  conversionFailed,
  cancelled,
  outOfMemory,
  fileTooLarge,
  permissionDenied,
}
```

## 文件结构（移动端）

```text
mobile/
├── lib/
│   ├── domain/
│   │   ├── models/
│   │   │   ├── conversion_job.dart
│   │   │   ├── conversion_result.dart
│   │   │   └── conversion_error.dart
│   │   └── engine/
│   │       └── conversion_engine.dart          # 抽象接口
│   └── features/
│       ├── image_to_pdf/                        # 第 2—3 期：图片→PDF
│       ├── pdf_to_png/                          # 第 4 期：PDF→PNG（page/controller/state/platform/platform_interface）
│       ├── pdf_to_jpg/                          # 第 5 期：PDF→JPG（同构）
│       └── pdf_page_range.dart                  # 第 6 期：连续页范围值对象（1-based 闭区间）
├── android/app/src/main/kotlin/.../
│   ├── MainActivity.kt
│   ├── ImageToPdfPlugin.kt
│   ├── ImageToPdfCoordinator.kt                # 单请求/单次完成与有序 URI 列表
│   ├── ImageInputValidator.kt                  # 单文件与总量输入门禁
│   ├── ImageToPdfConverter.kt                  # PdfDocument 单页/多页实现
│   ├── PdfRasterConverter.kt                   # 第 5 期：PNG/JPG 共用栅格化核心（含第 6 期范围循环）
│   ├── PdfToPngConverter.kt                    # 第 4 期：PDF→PNG 策略外观（透传范围）
│   ├── PdfToPngCoordinator.kt                  # 第 4 期：PDF、目录 SAF 协调（校验+透传范围）
│   ├── PdfToPngPlugin.kt                       # 第 4 期：PDF→PNG 通道（范围参数解析）
│   ├── PdfToJpgConverter.kt                    # 第 5 期：PDF→JPG 策略外观（透传范围）
│   ├── PdfToJpgCoordinator.kt                  # 第 5 期：PDF→JPG 协调（校验+透传范围）
│   ├── PdfToJpgPlugin.kt                       # 第 5 期：PDF→JPG 通道（范围参数解析）
│   ├── PdfInputValidator.kt                    # 第 4 期：PDF 页数、大小与可打开性门禁
│   └── CompletionGuard.kt                      # 结果恰好完成一次
├── test/
│   ├── domain/
│   │   └── conversion_job_test.dart
│   └── features/image_to_pdf/
├── android/app/src/test/...                    # 原生逻辑与接线测试
└── pubspec.yaml
```

每期测试和构建证据位于项目根目录的 `artifacts/phase-N/`，不在 `mobile/` 内。

## 平台适配层

### Android
- 文件选择：图片为 `OpenMultipleDocuments`；第 4 期 PDF 使用 `OpenDocument`
- 文件保存：`Intent(ACTION_CREATE_DOCUMENT)` / SAF
- 图片解码：系统 `BitmapFactory` / `ImageDecoder`
- PDF 写入：系统 `PdfDocument`
- 第 4 期 PDF 渲染：系统 `PdfRenderer`；文件夹选择使用 `OpenDocumentTree`
- 第 5 期沿用同一渲染、输入验证、SAF 清理和资源所有权链，新增 JPEG 编码；不复制 PNG 转换流程
- 第 6 期连续页范围：扩展点唯一落在共用核心 [`PdfRasterConverter.convert`](mobile/android/app/src/main/kotlin/com/example/docushift_mobile/PdfRasterConverter.kt) 的范围循环；`ConvertParams` 增加 `startPage`/`endPage`（默认 1/1，全页由协调器显式传 `1..total`），循环只打开所选页、文件名沿用原 PDF 页码（`pageFileName` 收 0-based 索引）、计数与错误信息用 1-based 页码；PNG/JPG converter 与 coordinator 仅透传范围
- Flutter 侧新增共用值对象 [`PdfPageRange`](mobile/lib/features/pdf_page_range.dart)（1-based 闭区间，`clamped`/`isValid`/`pageCount`）；`pdf_to_png` 与 `pdf_to_jpg` 的不可变 state 增加已选范围，控制器 `setStartPage`/`setEndPage` 做联动纠正与 `clamp(1, total)`，平台接口 `convertPdfToPng/Jpg` 透传 4 参；原生层 `INVALID_PAGE_RANGE` 在创建输出前校验，非法范围不创建 URI、不启动后台转换
- 第 3 期多图转换逐页解码和回收 Bitmap，不同时持有全部图片
- 第 9 期图片→PDF 分享：复用既有图片通道的 `sharePdf`，只接受本次输出的 `content://` URI；以 `ACTION_SEND`、`application/pdf`、`EXTRA_STREAM`、`ClipData` 和临时读权限启动 Android chooser。无临时副本、FileProvider、持久授权、后台任务或广泛存储权限；PDF→PNG/JPG 文件夹不分享。

### iOS
- 本期不实现；等待 Android 最小闭环稳定后重新规划。

## 状态管理

第 5 期仍不引入状态管理依赖；各功能控制器暴露不可变状态，页面只负责呈现和触发动作。

## 参考资料

- [Flutter 平台通道](https://docs.flutter.dev/platform-integration/platform-channels)
- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [iOS Document Picker](https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller)
