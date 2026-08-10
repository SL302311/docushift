/// DocuShift PDF 转 JPG — MethodChannel 合约定义（第 5 期）。
///
/// Flutter ↔ Android 之间的平台通道方法名、参数键与返回值合约。
/// 与 PDF→PNG 同构，仅通道名与方法名不同；参数键与返回键保持一致（pdfUri /
/// directoryUri / uri / name / pageCount / size / directoryUri）。
library;

/// 通道名称。
const String kChannelName = 'com.example.docushift_mobile/pdf_to_jpg';

// ============================================================================
// 方法名
// ============================================================================

/// 打开系统文件选择器，让用户选择 1 个 PDF（application/pdf）。
///
/// 参数：无
/// 返回值：`Map?` — `{ "uri": "...", "name": "...", "pageCount": 3, "size": 12345 }`，
///          用户取消时返回 null。
/// 错误：验证失败抛出 `PlatformException`（TOO_MANY_PAGES / PDF_OPEN_FAILED /
///        FILE_TOO_LARGE / FILE_SIZE_UNKNOWN / UNSUPPORTED_FORMAT）。
const String kMethodPickPdf = 'pickPdf';

/// 打开系统目录选择器（OpenDocumentTree），让用户选择输出文件夹。
///
/// 参数：无
/// 返回值：`String?` — 输出树 URI 字符串，用户取消时返回 null（不创建任何文件）。
const String kMethodPickOutputDirectory = 'pickOutputDirectory';

/// 将 PDF 全部页面按页序渲染为 JPG 写入新建输出子文件夹。
///
/// 参数：
///   - `pdfUri` (`String`) — PDF content:// URI
///   - `directoryUri` (`String`) — 输出树 URI
/// 返回值：`Map` — `{ "directoryUri": "...", "pageCount": 3, "size": 12345 }`。
/// 错误：抛出 `PlatformException`（稳定错误码见下）。
const String kMethodConvertPdfToJpg = 'convertPdfToJpg';

// ============================================================================
// 参数键 / 返回键
// ============================================================================

/// PDF content:// URI（String）。
const String kParamPdfUri = 'pdfUri';

/// 输出树 URI（String）。
const String kParamDirectoryUri = 'directoryUri';

/// 导出起始页（1-based，Int）。
const String kParamStartPage = 'startPage';

/// 导出结束页（1-based，Int）。
const String kParamEndPage = 'endPage';

/// pickPdf 返回的 URI 键。
const String kResultUri = 'uri';

/// pickPdf 返回的显示名键（Android 经 DISPLAY_NAME 返回）。
const String kResultName = 'name';

/// 页数键（pickPdf 与 convertPdfToJpg 返回）。
const String kResultPageCount = 'pageCount';

/// 字节数键（pickPdf 返回文件大小；convertPdfToJpg 返回 JPG 总字节数）。
const String kResultSize = 'size';

/// convertPdfToJpg 返回的输出目录 URI 键。
const String kResultDirectoryUri = 'directoryUri';

/// 清晰度参数（Int，可选；缺失/null 默认 144）。
const String kParamResolution = 'resolution';

// ============================================================================
// 输入边界常量（与 Android 端一致）
// ============================================================================

/// PDF 页数上限。
const int kMaxPageCount = 20;

/// 输入 PDF 大小上限（字节），100 MiB。
const int kMaxPdfSize = 100 * 1024 * 1024;

// ============================================================================
// 错误码（平台端抛出，Flutter 端捕获后映射）
// ============================================================================

/// PDF 页数超过 20。
const String kErrorTooManyPages = 'TOO_MANY_PAGES';

/// PDF 无法打开（损坏 / 受密码保护 / 系统无法解析）。
const String kErrorPdfOpenFailed = 'PDF_OPEN_FAILED';

/// 输出目录不可用（无法创建子文件夹或页文件）。
const String kErrorOutputDirUnavailable = 'OUTPUT_DIR_UNAVAILABLE';

/// 某一页渲染 / 编码失败。
const String kErrorPageRenderFailed = 'PAGE_RENDER_FAILED';

/// JPG 字节写入失败。
const String kErrorOutputWriteFailed = 'OUTPUT_WRITE_FAILED';

/// 文件超过尺寸上限。
const String kErrorFileTooLarge = 'FILE_TOO_LARGE';

/// 无法确定文件大小。
const String kErrorFileSizeUnknown = 'FILE_SIZE_UNKNOWN';

/// 参数缺失 / 空字符串。
const String kErrorInvalidArgs = 'INVALID_ARGS';
