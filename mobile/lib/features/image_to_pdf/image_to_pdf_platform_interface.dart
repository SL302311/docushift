import 'package:flutter/services.dart';
import 'image_to_pdf_platform.dart' as c;

/// DocuShift 图片转 PDF — 平台接口抽象。
///
/// 将 MethodChannel 调用抽象为可注入接口，便于控制器测试。
/// 第 3 期起支持多图：pickImages 返回有序 (uri, name) 列表，convertAndSave 接收有序 URI 列表。
abstract class ImageToPdfPlatform {
  /// 打开系统图片选择器，选择 1—20 张图片。
  /// 返回每张图的 `{uri, name}` 列表，或 null（用户取消）。
  Future<List<Map<String, String>>?> pickImages();

  /// 将有序图片列表转换为 PDF 并保存。
  /// 返回 `{path, size}` map，或 null（用户取消保存）。
  /// 失败时抛出 [PlatformException]。
  Future<Map<String, dynamic>?> convertAndSave(List<String> imageUris);

  /// 将已生成的 PDF 通过系统分享面板发送（第 9 期）。
  /// [outputUri] 为 convertAndSave 成功返回的 content:// URI；
  /// 分享成功时返回 null（chooser 已启动，结果由用户决定）。
  /// 失败时抛出 [PlatformException]（INVALID_OUTPUT_URI / SHARE_UNAVAILABLE）。
  Future<void> sharePdf(String outputUri);
}

/// 生产实现：通过 MethodChannel 调用 Android 原生。
class MethodChannelImageToPdfPlatform implements ImageToPdfPlatform {
  final MethodChannel _channel;

  MethodChannelImageToPdfPlatform()
      : _channel = const MethodChannel(c.kChannelName);

  @override
  Future<List<Map<String, String>>?> pickImages() async {
    final result = await _channel.invokeMethod<List<dynamic>>(c.kMethodPickImages);
    if (result == null) return null;
    return result
        .map((e) => Map<String, String>.from(e as Map))
        .toList();
  }

  @override
  Future<Map<String, dynamic>?> convertAndSave(List<String> imageUris) async {
    return await _channel.invokeMethod<Map<String, dynamic>>(c.kMethodConvertAndSave, {
      c.kParamImageUris: imageUris,
    });
  }

  @override
  Future<void> sharePdf(String outputUri) async {
    await _channel.invokeMethod(c.kMethodSharePdf, {
      c.kParamOutputUri: outputUri,
    });
  }
}

/// 假实现（用于单元测试）。
class FakeImageToPdfPlatform implements ImageToPdfPlatform {
  /// 下一个 pickImages 调用的返回值。
  Future<List<Map<String, String>>?> Function()? onPickImages;

  /// 下一个 convertAndSave 调用的返回值。
  /// 与 [ImageToPdfPlatform.convertAndSave] 一致：返回可空 inner 的 Future。
  Future<Map<String, dynamic>?> Function(List<String>)? onConvertAndSave;

  @override
  Future<List<Map<String, String>>?> pickImages() async =>
      onPickImages?.call();

  @override
  Future<Map<String, dynamic>?> convertAndSave(List<String> imageUris) async {
    final fn = onConvertAndSave;
    if (fn == null) return null;
    return fn(imageUris);
  }

  /// 下一个 sharePdf 调用的行为（可断言参数透传）。
  Future<void> Function(String)? onSharePdf;

  @override
  Future<void> sharePdf(String outputUri) async {
    final fn = onSharePdf;
    if (fn == null) return;
    return fn(outputUri);
  }
}
