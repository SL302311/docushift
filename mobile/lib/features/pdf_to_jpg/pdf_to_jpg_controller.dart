import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'pdf_to_jpg_state.dart';
import 'pdf_to_jpg_platform.dart' as c;
import 'pdf_to_jpg_platform_interface.dart';
import '../pdf_page_range.dart';
import '../raster_resolution.dart';

/// DocuShift PDF 转 JPG — 控制器（第 5 期）。
///
/// 通过 [ChangeNotifier] 驱动 UI 更新。
/// 接受可注入的 [PdfToJpgPlatform]，便于单元测试。
///
/// 两步选择流：选 PDF（原生验证 + 元数据）→ 选输出目录 → 转换。
/// 显示名/页数/大小来自平台，控制器不根据 URI 猜测。
class PdfToJpgController extends ChangeNotifier {
  final PdfToJpgPlatform _platform;

  PdfToJpgController({PdfToJpgPlatform? platform})
      : _platform = platform ?? MethodChannelPdfToJpgPlatform();

  PdfToJpgState _state = const PdfToJpgState();
  PdfToJpgState get state => _state;

  /// 打开系统 PDF 选择器。
  /// 成功返回则进入 selected（携带原生元数据）；取消（null）保持当前状态。
  Future<void> pickPdf() async {
    try {
      final picked = await _platform.pickPdf();
      if (picked == null) return; // 取消：保持当前状态
      final uri = picked[c.kResultUri] as String? ?? '';
      if (uri.isEmpty) return;
      _state = _state.withSelectedPdf(SelectedPdf(
        uri: uri,
        displayName: picked[c.kResultName] as String? ?? '',
        pageCount: (picked[c.kResultPageCount] as num?)?.toInt() ?? 0,
        sizeBytes: (picked[c.kResultSize] as num?)?.toInt() ?? 0,
      ));
      notifyListeners();
    } catch (e) {
      _state = _state.withError(_errMsg('选择', e));
      notifyListeners();
    }
  }

  /// 选择输出目录并启动转换。
  /// 目录取消（null）→ 回到 selected，不启动转换、不创建文件。
  Future<void> chooseFolderAndConvert() async {
    final pdf = _state.pdf;
    if (pdf == null) return;

    _state = _state.toChoosingFolder;
    notifyListeners();

    String? directoryUri;
    try {
      directoryUri = await _platform.pickOutputDirectory();
    } catch (e) {
      _state = _state.withError(_errMsg('选择目录', e));
      notifyListeners();
      return;
    }

    if (directoryUri == null || directoryUri.isEmpty) {
      // 目录取消 → 保留 PDF 元数据回到 selected，不启动转换
      _state = _state.toSelected;
      notifyListeners();
      return;
    }

    await _convert(pdf.uri, directoryUri);
  }

  /// 设置导出起始页（1-based）。
  /// 联动纠正：若该值超过当前结束页，则把结束页同步提高到起始页；
  /// 同时钳制到 [1, 总页数]。原生层仍会再次校验。
  void setStartPage(int value) {
    final pdf = _state.pdf;
    final range = _state.range;
    if (pdf == null || range == null) return;
    final total = pdf.pageCount;
    var s = value.clamp(1, total);
    var e = range.endPage;
    if (s > e) e = s; // 起始超过结束 → 结束同步提高
    _state = _state.withRange(PdfPageRange(startPage: s, endPage: e));
    notifyListeners();
  }

  /// 设置导出结束页（1-based）。
  /// 联动纠正：若该值低于当前起始页，则把起始页同步降低到结束页；
  /// 同时钳制到 [1, 总页数]。原生层仍会再次校验。
  void setEndPage(int value) {
    final pdf = _state.pdf;
    final range = _state.range;
    if (pdf == null || range == null) return;
    final total = pdf.pageCount;
    var e = value.clamp(1, total);
    var s = range.startPage;
    if (e < s) s = e; // 结束低于起始 → 起始同步降低
    _state = _state.withRange(PdfPageRange(startPage: s, endPage: e));
    notifyListeners();
  }

  /// 设置清晰度预设（第 8 期）。
  /// 仅在有已选 PDF 且处于交互态（selected/failure）时允许修改。
  void setResolution(RasterResolution resolution) {
    if (_state.pdf == null || !_state.isInteractive) return;
    _state = _state.withResolution(resolution);
    notifyListeners();
  }

  Future<void> _convert(String pdfUri, String directoryUri) async {
    _state = _state.toConverting;
    notifyListeners();

    final range = _state.range ?? PdfPageRange.all(_state.pdf?.pageCount ?? 1);
    final res = _state.resolution ?? RasterResolution.defaultResolution;
    try {
      final result = await _platform.convertPdfToJpg(
        pdfUri,
        directoryUri,
        range.startPage,
        range.endPage,
        resolution: res.dpi,
      );
      if (result == null) {
        // 意外空返回按取消处理：回到 selected
        _state = _state.toSelected;
        notifyListeners();
        return;
      }
      final dir = result[c.kResultDirectoryUri] as String;
      final size = (result[c.kResultSize] as num).toInt();
      final pageCount = (result[c.kResultPageCount] as num?)?.toInt() ?? range.pageCount;
      _state = _state.withSuccess(dir, size, pageCount);
      notifyListeners();
    } catch (e) {
      // 失败保留 PDF 元数据以便重试
      _state = _state.withError(_errMsg('转换', e));
      notifyListeners();
    }
  }

  /// 失败后重试：重新走「选目录 → 转换」两步（PDF 元数据已保留）。
  Future<void> retry() async {
    if (_state.status != PdfToJpgStatus.failure) return;
    await chooseFolderAndConvert();
  }

  /// 重置到初始状态（重新选择）。
  void reset() {
    _state = _state.toIdle;
    notifyListeners();
  }

  String _errMsg(String action, Object e) =>
      e is PlatformException ? '$action失败: ${e.message}' : '$action失败: $e';
}
