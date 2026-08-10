// DocuShift 转换异常定义。

/// 错误码枚举。
enum ErrorCode {
  /// 输入文件不存在。
  fileNotFound,

  /// 不支持的输入或输出格式。
  unsupportedFormat,

  /// 所选引擎不可用。
  engineNotAvailable,

  /// 转换执行失败。
  conversionFailed,

  /// 用户取消转换。
  cancelled,

  /// 内存不足。
  outOfMemory,

  /// 文件超过大小限制。
  fileTooLarge,

  /// 文件/目录权限不足。
  permissionDenied,
}

/// 转换过程中的异常。
class ConversionException implements Exception {
  /// 错误码。
  final ErrorCode code;

  /// 人类可读的错误描述。
  final String message;

  /// 详细的错误信息（可选，如堆栈跟踪）。
  final String? detail;

  const ConversionException({
    required this.code,
    required this.message,
    this.detail,
  });

  @override
  String toString() => 'ConversionException($code): $message';

  /// 文件不存在快捷构造。
  factory ConversionException.fileNotFound(String path) =>
      ConversionException(
        code: ErrorCode.fileNotFound,
        message: '文件不存在: $path',
      );

  /// 格式不支持快捷构造。
  factory ConversionException.unsupportedFormat(String format) =>
      ConversionException(
        code: ErrorCode.unsupportedFormat,
        message: '不支持的格式: $format',
      );
}
