// DocuShift 转换引擎抽象接口。

import '../models/conversion_error.dart';
import '../models/conversion_result.dart';

/// 取消令牌——引擎应在检查到此标记为已取消时停止转换。
class CancelToken {
  bool _cancelled = false;

  /// 是否已请求取消。
  bool get isCancelled => _cancelled;

  /// 请求取消。
  void cancel() => _cancelled = true;

  /// 重置取消状态。
  void reset() => _cancelled = false;
}

/// 转换引擎的抽象接口。
///
/// 所有具体引擎必须实现此接口。
/// UI 层通过此接口调度转换，不依赖具体引擎实现。
abstract class ConversionEngine {
  /// 引擎名称（用于诊断和日志）。
  String get name;

  /// 本引擎支持的输入格式集合。
  /// 格式为小写扩展名，含点号，如 `{'.png', '.jpg', '.pdf'}`。
  Set<String> get supportedInputFormats;

  /// 本引擎支持的输出格式集合。
  /// 格式同上，如 `{'.pdf'}`、`{'.png', '.jpg'}`。
  Set<String> get supportedOutputFormats;

  /// 判断本引擎是否能处理指定的输入→输出转换。
  bool canHandle(String inputPath, String targetFormat) {
    final ext = _extension(inputPath);
    if (ext == null || !supportedInputFormats.contains(ext)) return false;
    final outExt = '.${targetFormat.toLowerCase()}';
    return supportedOutputFormats.contains(outExt);
  }

  /// 执行单文件转换。
  ///
  /// [inputPath] 输入文件绝对路径。
  /// [targetFormat] 目标格式（小写，不含点号，如 "pdf"、"png"）。
  /// [onProgress] 进度回调 (0.0~1.0 的进度, 消息)。
  /// [cancelToken] 取消令牌。
  ///
  /// 返回 [ConversionResult]。
  /// 失败时抛出 [ConversionException]。
  Future<ConversionResult> convert({
    required String inputPath,
    required String targetFormat,
    void Function(double progress, String message)? onProgress,
    CancelToken? cancelToken,
  });

  /// 从路径提取小写扩展名（含点号）。
  String? _extension(String path) {
    final dot = path.lastIndexOf('.');
    if (dot < 0) return null;
    return path.substring(dot).toLowerCase();
  }
}
