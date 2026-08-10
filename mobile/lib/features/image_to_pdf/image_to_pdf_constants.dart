/// DocuShift 图片转 PDF — 输入限制与页面布局计算。
///
/// 所有输入边界在实现前固化为常量。
/// 页面布局算法为纯函数，可独立单元测试。
library;
// ============================================================================
// 输入限制常量
// ============================================================================

/// 允许的最大输入文件大小（字节），约 30 MB。
const int kMaxFileSize = 30 * 1024 * 1024;

/// 允许的最大解码后像素总数（宽 × 高），约 4000 万像素。
const int kMaxPixelCount = 40000000;

/// 采样前允许的最大解码尺寸（单边），超过此值强制降采样。
const int kMaxSampleDimension = 4096;

/// 降采样目标尺寸（单边），用于超大图片的安全降采样。
const int kTargetSampleDimension = 2048;

// ============================================================================
// 输出页面常量
// ============================================================================

/// A4 页面宽度（点），纵向。
const double kPageWidthPt = 595.0;

/// A4 页面高度（点），纵向。
const double kPageHeightPt = 842.0;

/// 白边距（点），对应约 12 mm（1 pt ≈ 0.353 mm）。
const double kPageMarginPt = 34.0;

/// 内容区域宽度（纵向）：595 - 2×34 = 527 pt。
double get contentWidthPortraitPt => kPageWidthPt - 2 * kPageMarginPt;

/// 内容区域高度（纵向）：842 - 2×34 = 774 pt。
double get contentHeightPortraitPt => kPageHeightPt - 2 * kPageMarginPt;

// ============================================================================
// 页面布局计算（纯函数）
// ============================================================================

/// 根据图片方向决定页面方向与缩放。
///
/// [imageWidthPx]  图片像素宽度。
/// [imageHeightPx]  图片像素高度。
///
/// 返回 `(pageWidth, pageHeight, drawX, drawY, drawWidth, drawHeight)`，
/// 所有值以点（pt）为单位。
PageLayoutResult calculatePageLayout(int imageWidthPx, int imageHeightPx) {
  final isLandscape = imageWidthPx > imageHeightPx;

  final pageW = isLandscape ? kPageHeightPt : kPageWidthPt;
  final pageH = isLandscape ? kPageWidthPt : kPageHeightPt;

  final contentW = pageW - 2 * kPageMarginPt;
  final contentH = pageH - 2 * kPageMarginPt;

  // 等比缩放：图片在内容区域内居中显示
  final scaleX = contentW / imageWidthPx;
  final scaleY = contentH / imageHeightPx;
  final scale = scaleX < scaleY ? scaleX : scaleY;

  final drawW = imageWidthPx * scale;
  final drawH = imageHeightPx * scale;

  final drawX = kPageMarginPt + (contentW - drawW) / 2;
  final drawY = kPageMarginPt + (contentH - drawH) / 2;

  return PageLayoutResult(
    pageWidthPt: pageW,
    pageHeightPt: pageH,
    drawX: drawX,
    drawY: drawY,
    drawWidth: drawW,
    drawHeight: drawH,
    scale: scale,
  );
}

/// 页面布局的计算结果。
class PageLayoutResult {
  /// 页面宽度（点）。
  final double pageWidthPt;

  /// 页面高度（点）。
  final double pageHeightPt;

  /// 图片绘制区域的左上角 X（点）。
  final double drawX;

  /// 图片绘制区域的左上角 Y（点）。
  final double drawY;

  /// 图片绘制宽度（点）。
  final double drawWidth;

  /// 图片绘制高度（点）。
  final double drawHeight;

  /// 缩放比例。
  final double scale;

  const PageLayoutResult({
    required this.pageWidthPt,
    required this.pageHeightPt,
    required this.drawX,
    required this.drawY,
    required this.drawWidth,
    required this.drawHeight,
    required this.scale,
  });
}
