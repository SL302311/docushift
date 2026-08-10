/// DocuShift PDF 转 JPG — 状态机模型（第 5 期）。
///
/// 产品语义（详见 docs/phase-5-plan.md）：
/// - idle → selected（选择 PDF 成功，含原生元数据）
/// - selected → choosingFolder（请求输出目录）
/// - choosingFolder → selected（目录取消，不启动转换）
/// - choosingFolder → converting（拿到目录后启动转换）
/// - converting → success / failure
/// - failure 保留 PDF 元数据以便重试；success → idle（重新选择）
library;

import '../pdf_page_range.dart';
import '../raster_resolution.dart';

/// 状态枚举。
enum PdfToJpgStatus {
  /// 初始态，等待用户选择 PDF。
  idle,

  /// 已选择并验证 1 个 PDF，显示原生元数据，待选择输出目录。
  selected,

  /// 正在等待用户选择输出文件夹。
  choosingFolder,

  /// 正在逐页渲染并写入 JPG。
  converting,

  /// 转换成功。
  success,

  /// 转换失败（保留 PDF 元数据以便重试）。
  failure,
}

/// 已选择并通过原生验证的 PDF 元数据（不可变）。
/// 名称/页数/大小均来自 Android 原生返回，不从 content:// URI 猜测。
class SelectedPdf {
  /// content:// URI 字符串。
  final String uri;

  /// 原生 DISPLAY_NAME 返回的显示名。
  final String displayName;

  /// PdfRenderer 验证得到的页数（1—20）。
  final int pageCount;

  /// 文件字节数。
  final int sizeBytes;

  const SelectedPdf({
    required this.uri,
    required this.displayName,
    required this.pageCount,
    required this.sizeBytes,
  });
}

/// PDF 转 JPG 功能的完整状态。
class PdfToJpgState {
  /// 当前状态。
  final PdfToJpgStatus status;

  /// 已选 PDF 元数据。为 null 表示未选择。
  final SelectedPdf? pdf;

  /// 导出页范围（1-based 闭区间）；选 PDF 成功后初始为全页范围。
  final PdfPageRange? range;

  /// 清晰度预设（第 8 期）；选 PDF 成功后初始为标准 144 dpi，
  /// 重选/重置时复位，目录取消/转换失败重试保留用户选择。
  final RasterResolution? resolution;

  /// 错误消息（仅 failure 时有效）。
  final String? errorMessage;

  /// 输出目录 URI（仅 success 时有效）。
  final String? outputDirectoryUri;

  /// 输出 JPG 总字节数（仅 success 时有效）。
  final int? outputSizeBytes;

  /// 本次实际导出的页数（仅 success 时有效；来自原生返回）。
  final int? outputPageCount;

  const PdfToJpgState({
    this.status = PdfToJpgStatus.idle,
    this.pdf,
    this.range,
    this.resolution,
    this.errorMessage,
    this.outputDirectoryUri,
    this.outputSizeBytes,
    this.outputPageCount,
  });

  /// 是否处于可操作的交互态（idle / selected / failure）。
  bool get isInteractive =>
      status == PdfToJpgStatus.idle ||
      status == PdfToJpgStatus.selected ||
      status == PdfToJpgStatus.failure;

  /// 选择 PDF 成功后的新状态（范围初始化为全页，清晰度初始化为标准 144）。
  PdfToJpgState withSelectedPdf(SelectedPdf pdf) => PdfToJpgState(
        status: PdfToJpgStatus.selected,
        pdf: pdf,
        range: PdfPageRange.all(pdf.pageCount),
        resolution: RasterResolution.defaultResolution,
      );

  /// 更新导出范围（保留其余状态，含清晰度）。
  PdfToJpgState withRange(PdfPageRange range) => PdfToJpgState(
        status: status,
        pdf: pdf,
        range: range,
        resolution: resolution,
        errorMessage: errorMessage,
        outputDirectoryUri: outputDirectoryUri,
        outputSizeBytes: outputSizeBytes,
        outputPageCount: outputPageCount,
      );

  /// 更新清晰度（保留其余状态，含页范围）。
  PdfToJpgState withResolution(RasterResolution resolution) => PdfToJpgState(
        status: status,
        pdf: pdf,
        range: range,
        resolution: resolution,
        errorMessage: errorMessage,
        outputDirectoryUri: outputDirectoryUri,
        outputSizeBytes: outputSizeBytes,
        outputPageCount: outputPageCount,
      );

  /// 重置到 idle（重新选择）。
  PdfToJpgState get toIdle => const PdfToJpgState(status: PdfToJpgStatus.idle);

  /// 进入目录选择（保留 PDF 元数据、页范围和清晰度）。
  PdfToJpgState get toChoosingFolder => PdfToJpgState(
        status: PdfToJpgStatus.choosingFolder,
        pdf: pdf,
        range: range,
        resolution: resolution,
      );

  /// 目录取消 / 回到已选择态（保留 PDF 元数据、页范围和清晰度）。
  PdfToJpgState get toSelected => PdfToJpgState(
        status: PdfToJpgStatus.selected,
        pdf: pdf,
        range: range,
        resolution: resolution,
      );

  /// 开始转换（保留 PDF 元数据、页范围和清晰度）。
  PdfToJpgState get toConverting => PdfToJpgState(
        status: PdfToJpgStatus.converting,
        pdf: pdf,
        range: range,
        resolution: resolution,
      );

  /// 转换成功（保留 PDF 元数据、页范围和清晰度以便显示）。
  PdfToJpgState withSuccess(String directoryUri, int size, int pageCount) =>
      PdfToJpgState(
        status: PdfToJpgStatus.success,
        pdf: pdf,
        range: range,
        resolution: resolution,
        outputDirectoryUri: directoryUri,
        outputSizeBytes: size,
        outputPageCount: pageCount,
      );

  /// 转换失败（保留 PDF 元数据、页范围和清晰度，允许重试）。
  PdfToJpgState withError(String message) => PdfToJpgState(
        status: PdfToJpgStatus.failure,
        pdf: pdf,
        range: range,
        resolution: resolution,
        errorMessage: message,
      );
}
