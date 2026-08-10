import 'package:flutter/services.dart';
import 'pdf_to_png_platform.dart' as c;

/// DocuShift PDF 转 PNG — 平台接口抽象（第 4 期）。
///
/// 将 MethodChannel 调用抽象为可注入接口，便于控制器测试。
abstract class PdfToPngPlatform {
  /// 打开系统文件选择器，选择 1 个 PDF 并在原生完成全部验证。
  /// 返回 `{uri, name, pageCount, size}` map，或 null（用户取消）。
  /// 验证失败抛出 [PlatformException]。
  Future<Map<String, dynamic>?> pickPdf();

  /// 打开系统目录选择器，返回输出树 URI 字符串，或 null（用户取消）。
  Future<String?> pickOutputDirectory();

  /// 将 PDF 指定连续页范围渲染为 PNG 写入输出目录下新建子文件夹。
  /// [startPage]/[endPage] 为 1-based 闭区间；[resolution] 为清晰度 dpi 值
  /// （96/144/216），缺失/null 由原生层默认 144。
  /// 返回 `{directoryUri, pageCount, size}` map，
  /// 其中 pageCount 为本次实际导出的页数。失败时抛出 [PlatformException]。
  Future<Map<String, dynamic>?> convertPdfToPng(
    String pdfUri,
    String directoryUri,
    int startPage,
    int endPage, {
    int? resolution,
  });
}

/// 生产实现：通过 MethodChannel 调用 Android 原生。
class MethodChannelPdfToPngPlatform implements PdfToPngPlatform {
  final MethodChannel _channel;

  MethodChannelPdfToPngPlatform()
      : _channel = const MethodChannel(c.kChannelName);

  @override
  Future<Map<String, dynamic>?> pickPdf() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(c.kMethodPickPdf);
    if (result == null) return null;
    return Map<String, dynamic>.from(result);
  }

  @override
  Future<String?> pickOutputDirectory() async {
    return await _channel.invokeMethod<String>(c.kMethodPickOutputDirectory);
  }

  @override
  Future<Map<String, dynamic>?> convertPdfToPng(
    String pdfUri,
    String directoryUri,
    int startPage,
    int endPage, {
    int? resolution,
  }) async {
    final args = <String, dynamic>{
      c.kParamPdfUri: pdfUri,
      c.kParamDirectoryUri: directoryUri,
      c.kParamStartPage: startPage,
      c.kParamEndPage: endPage,
    };
    if (resolution != null) {
      args[c.kParamResolution] = resolution;
    }
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      c.kMethodConvertPdfToPng,
      args,
    );
    if (result == null) return null;
    return Map<String, dynamic>.from(result);
  }
}

/// 假实现（用于单元测试）。
class FakePdfToPngPlatform implements PdfToPngPlatform {
  /// 下一个 pickPdf 调用的行为。
  Future<Map<String, dynamic>?> Function()? onPickPdf;

  /// 下一个 pickOutputDirectory 调用的行为。
  Future<String?> Function()? onPickOutputDirectory;

  /// 下一个 convertPdfToPng 调用的行为（可断言参数透传）。
  Future<Map<String, dynamic>?> Function(String pdfUri, String directoryUri, int startPage, int endPage, {int? resolution})?
      onConvertPdfToPng;

  @override
  Future<Map<String, dynamic>?> pickPdf() async => onPickPdf?.call();

  @override
  Future<String?> pickOutputDirectory() async =>
      onPickOutputDirectory?.call();

  @override
  Future<Map<String, dynamic>?> convertPdfToPng(
    String pdfUri,
    String directoryUri,
    int startPage,
    int endPage, {
    int? resolution,
  }) async {
    final fn = onConvertPdfToPng;
    if (fn == null) return null;
    return fn(pdfUri, directoryUri, startPage, endPage, resolution: resolution);
  }
}
