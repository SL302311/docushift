// DocuShift 转换结果模型。

import 'package:meta/meta.dart';

/// 单个文件的转换结果。
@immutable
class ConversionResult {
  /// 输出文件绝对路径。
  final String outputPath;

  /// 输出文件字节数。
  final int sizeBytes;

  /// 转换完成时间戳（毫秒）。
  final DateTime completedAt;

  /// 文件校验和（SHA-256），可选。
  final String? checksum;

  const ConversionResult({
    required this.outputPath,
    required this.sizeBytes,
    required this.completedAt,
    this.checksum,
  });

  /// 是否为有效结果（文件存在且非空）。
  bool get isValid => sizeBytes > 0;

  /// 友好的大小描述，如 "1.2 MB"。
  String get sizeDescription {
    if (sizeBytes >= 1048576) {
      return '${(sizeBytes / 1048576).toStringAsFixed(1)} MB';
    } else if (sizeBytes >= 1024) {
      return '${(sizeBytes / 1024).toStringAsFixed(1)} KB';
    } else {
      return '$sizeBytes B';
    }
  }

  @override
  String toString() =>
      'ConversionResult($outputPath, $sizeDescription)';
}
