import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'image_to_pdf_state.dart';
import 'image_to_pdf_platform.dart' as c;
import 'image_to_pdf_platform_interface.dart';
import 'selected_image.dart';

/// DocuShift 图片转 PDF — 控制器。
///
/// 通过 [ChangeNotifier] 驱动 UI 更新。
/// 接受可注入的 [ImageToPdfPlatform]，便于单元测试。
///
/// 第 3 期：支持一次选择 1—20 张图片，调整顺序/删除后按序生成多页 PDF。
/// 显示名来自平台（Android 经 DISPLAY_NAME 返回），控制器不根据 URI 猜测文件名。
class ImageToPdfController extends ChangeNotifier {
  final ImageToPdfPlatform _platform;

  ImageToPdfController({ImageToPdfPlatform? platform})
      : _platform = platform ?? MethodChannelImageToPdfPlatform();

  ImageToPdfState _state = const ImageToPdfState();
  ImageToPdfState get state => _state;

  /// 打开系统图片选择器（多选）。
  /// 成功返回则替换当前列表为最新选择批次；取消（null）不覆盖原列表。
  Future<void> pickImages() async {
    try {
      final picked = await _platform.pickImages();
      if (picked == null) return; // 取消：保持原列表与顺序
      final images = picked
          .map((m) => SelectedImage(
                uri: m[c.kResultUri] ?? '',
                displayName: m[c.kResultName] ?? '',
              ))
          .where((i) => i.uri.isNotEmpty)
          .toList();
      if (images.isEmpty) return;
      _state = _state.withSelectedImages(images);
      notifyListeners();
    } catch (e) {
      _state = _state.withError(_errMsg('选择', e));
      notifyListeners();
    }
  }

  /// 上移图片（保持列表有序）。仅在 selected 态有效。
  void moveImage(int from, int to) {
    if (_state.status != ImageToPdfStatus.selected) return;
    final list = List<SelectedImage>.from(_state.images);
    if (from < 0 || from >= list.length) return;
    if (to < 0 || to >= list.length) return;
    if (from == to) return;
    final item = list.removeAt(from);
    list.insert(to, item);
    _state = _state.withSelectedImages(list);
    notifyListeners();
  }

  /// 删除指定位置图片。删除到空列表返回 idle。
  void removeImage(int index) {
    if (_state.status != ImageToPdfStatus.selected) return;
    final list = List<SelectedImage>.from(_state.images);
    if (index < 0 || index >= list.length) return;
    list.removeAt(index);
    _state = list.isEmpty ? _state.toIdle : _state.withSelectedImages(list);
    notifyListeners();
  }

  /// 将当前有序图片列表转换为 PDF 并保存。
  Future<void> convertAndSave() async {
    final uris = _state.images.map((i) => i.uri).toList();
    if (uris.isEmpty) return;

    _state = _state.toConverting;
    notifyListeners();

    try {
      final result = await _platform.convertAndSave(uris);

      if (result == null) {
        // 用户取消保存 → 恢复原列表与顺序（保留 images）
        _state = _state.withSelectedImages(_state.images);
        notifyListeners();
        return;
      }

      final path = result[c.kResultPath] as String;
      final size = result[c.kResultSize] as int;
      _state = _state.withSuccess(path, size);
      notifyListeners();
    } catch (e) {
      _state = _state.withError(_errMsg('转换', e));
      notifyListeners();
    }
  }

  /// 重置到初始状态（重新选择 / 重试）。
  void reset() {
    _state = _state.toIdle;
    notifyListeners();
  }

  /// 将已生成的 PDF 通过系统分享面板发送（第 9 期）。
  /// 仅在 success 态且 outputPath 非空时调用；分享成功/取消均不改变状态。
  /// 平台异常向调用方抛出，由页面以临时提示展示，不把 success 改成 failure。
  Future<String?> shareOutput() async {
    if (_state.status != ImageToPdfStatus.success) return null;
    final path = _state.outputPath;
    if (path == null || path.isEmpty) return null;
    try {
      await _platform.sharePdf(path);
      return null; // 成功（chooser 已启动）
    } catch (e) {
      return e is PlatformException ? '分享失败: ${e.message}' : '分享失败: $e';
    }
  }

  String _errMsg(String action, Object e) =>
      e is PlatformException ? '$action失败: ${e.message}' : '$action失败: $e';
}
