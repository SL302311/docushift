/// DocuShift 图片转 PDF — MethodChannel 合约定义。
///
/// Flutter ↔ Android 之间的平台通道方法名、参数键与返回值合约。
/// 所有方法名和参数键用字符串常量，避免魔法字符串。
/// 第 3 期起支持多图：pickImages 返回有序 (uri, name) 列表，
/// convertAndSave 接收有序 imageUris 列表。
library;
/// 通道名称。
const String kChannelName = 'com.example.docushift_mobile/image_to_pdf';

// ============================================================================
// 方法名
// ============================================================================

/// 打开系统文件选择器，让用户选择 1—20 张 PNG、JPG 或 BMP 图片。
///
/// 参数：无
/// 返回值：`List<Map<String,String>>?` — 每张图 `{ "uri": "...", "name": "..." }`，
///          用户取消时返回 null。
const String kMethodPickImages = 'pickImages';

/// 将有序图片列表转换为 PDF 并打开系统保存面板让用户选择保存位置。
///
/// 参数：
///   - `imageUris` (`List<String>`) — 有序输入图片 content:// URI 列表
/// 返回值：`Map?` — `{ "path": "...", "size": 12345 }`，
///          用户取消保存时返回 null。
/// 错误：抛出 `PlatformException`（稳定错误码见下）。
const String kMethodConvertAndSave = 'convertAndSave';

// ============================================================================
// 参数键
// ============================================================================

/// 有序输入图片 content:// URI 列表（`List<String>`）。
const String kParamImageUris = 'imageUris';

/// 单张图片结果中的 URI 键（pickImages 返回值）。
const String kResultUri = 'uri';

/// 单张图片结果中的显示名键（pickImages 返回值，由 Android 经 DISPLAY_NAME 返回）。
const String kResultName = 'name';

/// 输出结果路径（String）。
const String kResultPath = 'path';

/// 输出结果字节数（int）。
const String kResultSize = 'size';

// ============================================================================
// 输入边界常量（与 Android 端一致）
// ============================================================================

/// 一次最多选择的图片数量。
const int kMaxImageCount = 20;

/// 本次选择源文件总大小上限（字节），约 200 MB。
const int kMaxTotalSize = 200 * 1024 * 1024;

// ============================================================================
// 错误码（平台端抛出，Flutter 端捕获后映射）
// ============================================================================

/// 平台错误码前缀（用于非图片转换类未知错误）。
const String kErrorCodePrefix = 'CONVERSION_';

/// 图片解码失败（损坏图片等）。
const String kErrorDecodeFailed = 'DECODE_FAILED';

/// 图片超过允许的像素上限。
const String kErrorImageTooLarge = 'IMAGE_TOO_LARGE';

/// 文件超过尺寸上限。
const String kErrorFileTooLarge = 'FILE_TOO_LARGE';

/// 无法确定文件大小（三种来源均缺失/为负）。
const String kErrorFileSizeUnknown = 'FILE_SIZE_UNKNOWN';

/// PDF 写入失败。
const String kErrorWriteFailed = 'WRITE_FAILED';

/// 参数缺失 / 空列表 / 元素非法。
const String kErrorInvalidArgs = 'INVALID_ARGS';

/// 选择图片超过数量上限。
const String kErrorTooManyFiles = 'TOO_MANY_FILES';

/// 选择源文件总大小超过上限。
const String kErrorTotalSizeTooLarge = 'TOTAL_SIZE_TOO_LARGE';

/// 操作被用户取消。
const String kErrorCancelled = '${kErrorCodePrefix}CANCELLED';

// ============================================================================
// 第 9 期：分享 PDF
// ============================================================================

/// 将已生成的 PDF content:// URI 通过系统分享面板发送。
///
/// 参数：
///   - `outputUri` (`String`) — 已成功生成的 PDF content:// URI
/// 返回值：成功返回 null（已交给系统 chooser），错误抛出 PlatformException。
const String kMethodSharePdf = 'sharePdf';

/// 分享目标 URI 参数键。
const String kParamOutputUri = 'outputUri';

/// URI scheme 不是 content。
const String kErrorInvalidOutputUri = 'INVALID_OUTPUT_URI';

/// 分享面板无可用目标 / 启动失败。
const String kErrorShareUnavailable = 'SHARE_UNAVAILABLE';
