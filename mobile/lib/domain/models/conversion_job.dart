import 'conversion_error.dart';
import 'conversion_result.dart';

/// DocuShift 转换任务状态模型。

/// 任务状态枚举。
enum JobState {
  /// 任务已创建，等待执行。
  queued,

  /// 任务正在执行转换。
  running,

  /// 所有输入文件转换成功。
  succeeded,

  /// 部分文件转换成功，部分失败。
  partiallyFailed,

  /// 所有文件转换失败。
  failed,

  /// 任务被用户取消。
  cancelled,
}

/// 单次转换任务。
class ConversionJob {
  /// 唯一标识。
  final String id;

  /// 输入文件路径列表。
  final List<String> inputPaths;

  /// 目标输出格式（小写扩展名，如 "pdf"、"png"）。
  final String targetFormat;

  /// 当前状态。
  final JobState state;

  /// 整体进度（0.0 ~ 1.0）。
  final double progress;

  /// 已成功完成的结果。
  final List<ConversionResult> results;

  /// 转换过程中的错误。
  final List<ConversionException> errors;

  const ConversionJob({
    required this.id,
    required this.inputPaths,
    required this.targetFormat,
    this.state = JobState.queued,
    this.progress = 0.0,
    this.results = const [],
    this.errors = const [],
  });

  /// 创建状态已更新的副本。
  ConversionJob copyWith({
    JobState? state,
    double? progress,
    List<ConversionResult>? results,
    List<ConversionException>? errors,
  }) {
    return ConversionJob(
      id: id,
      inputPaths: inputPaths,
      targetFormat: targetFormat,
      state: state ?? this.state,
      progress: progress ?? this.progress,
      results: results ?? this.results,
      errors: errors ?? this.errors,
    );
  }

  /// 任务是否已完成（成功/部分失败/失败/取消均为结束态）。
  bool get isFinished =>
      state == JobState.succeeded ||
      state == JobState.partiallyFailed ||
      state == JobState.failed ||
      state == JobState.cancelled;

  /// 成功文件数。
  int get successCount => results.length;

  /// 失败文件数。
  int get failureCount => errors.length;

  /// 总文件数。
  int get totalCount => inputPaths.length;

  /// 格式化的进度百分比字符串，如 "45.2%"。
  String get progressPercent => '${(progress * 100).toStringAsFixed(1)}%';
}
