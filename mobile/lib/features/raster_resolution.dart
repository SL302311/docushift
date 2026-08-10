/// DocuShift — 清晰度预设值对象（第 8 期，PDF/PNG 与 PDF/JPG 共用）。
///
/// 三档且仅三档：96 dpi（低清）、144 dpi（标准，推荐）、216 dpi（高精）。
/// 改变每页渲染 Bitmap 的像素尺寸；不影响输出目录命名、页码范围、
/// JPG 质量 85 或逐页 Bitmap 回收。
///
/// 约束（计划冻结）：
/// - 仅允许 `96`、`144`、`216` 三个值；其他任何值由原生层拒绝。
/// - 默认 144 dpi（标准），与第 4—7 期输出效果完全一致。
/// - 不支持任意数值输入或滑杆。
class RasterResolution {
  /// 低清：96 dpi。
  static const RasterResolution low = RasterResolution._(96, '低清');

  /// 标准：144 dpi（默认，推荐）。
  static const RasterResolution standard = RasterResolution._(144, '标准');

  /// 高精：216 dpi。
  static const RasterResolution high = RasterResolution._(216, '高精');

  /// 全部三档预设。
  static const List<RasterResolution> values = [low, standard, high];

  /// 默认清晰度（标准 144 dpi）。
  static const RasterResolution defaultResolution = standard;

  /// 原始 dpi 值。
  final int dpi;

  /// 用户可见标签。
  final String label;

  const RasterResolution._(this.dpi, this.label);

  /// 根据 dpi 值查找对应预设，未知值返回 null。
  static RasterResolution? fromDpi(int dpi) {
    for (final r in values) {
      if (r.dpi == dpi) return r;
    }
    return null;
  }

  /// 完整 UI 标签，含 dpi 信息。
  String get fullLabel {
    if (this == standard) {
      return '$label（$dpi dpi，推荐）';
    }
    return '$label（$dpi dpi）';
  }

  @override
  bool operator ==(Object other) =>
      other is RasterResolution && other.dpi == dpi;

  @override
  int get hashCode => dpi;

  @override
  String toString() => 'RasterResolution($label, $dpi dpi)';
}
