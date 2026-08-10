/// DocuShift 图片转 PDF — 已选图片模型（不可变）。
///
/// 显示名必须由 Android 端经 [OpenableColumns.DISPLAY_NAME] 返回；
/// Flutter 侧不根据 content:// URI 猜测文件名（详见 README 第 3 期合约）。
class SelectedImage {
  /// 图片的 content:// URI（Android）或本地路径。
  final String uri;

  /// Android 返回的原始显示名（不含路径）。
  final String displayName;

  const SelectedImage({required this.uri, required this.displayName});

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SelectedImage && other.uri == uri && other.displayName == displayName;

  @override
  int get hashCode => uri.hashCode ^ displayName.hashCode;

  @override
  String toString() => 'SelectedImage(uri: $uri, displayName: $displayName)';
}
