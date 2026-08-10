/// DocuShift 图片转 PDF — 状态机模型。
///
/// 产品语义（详见 README 第 3 期方案）：
/// - idle ↔ selected ↔ converting → success / failure
/// - selected → idle（取消选择）
/// - converting → idle（保存取消，不算失败，恢复原列表与顺序）
/// - success → idle / failure → 重试（保留原列表）
///
/// 第 3 期起，已选图片以有序 [SelectedImage] 列表表示；列表顺序即 PDF 页序。
library;

import 'selected_image.dart';
/// 状态枚举。
enum ImageToPdfStatus {
  /// 初始态，等待用户选择图片。
  idle,

  /// 已选择 1—20 张图片，待调整顺序或转换。
  selected,

  /// 正在转换并等待用户选择保存位置。
  converting,

  /// 转换并保存成功。
  success,

  /// 转换失败。
  failure,
}

/// 图片转 PDF 功能的完整状态。
class ImageToPdfState {
  /// 当前状态。
  final ImageToPdfStatus status;

  /// 已选图片的有序列表（顺序即 PDF 页序）。为空表示未选择。
  final List<SelectedImage> images;

  /// 错误消息（仅 failure 时有效）。
  final String? errorMessage;

  /// 输出 PDF 的路径（仅 success 时有效）。
  final String? outputPath;

  /// 输出 PDF 的字节数（仅 success 时有效）。
  final int? outputSizeBytes;

  const ImageToPdfState({
    this.status = ImageToPdfStatus.idle,
    this.images = const [],
    this.errorMessage,
    this.outputPath,
    this.outputSizeBytes,
  });

  /// 是否处于可操作的交互态（idle / selected）。
  bool get isInteractive =>
      status == ImageToPdfStatus.idle || status == ImageToPdfStatus.selected;

  /// 选择图片后的新状态（替换列表为最新选择批次）。
  ImageToPdfState withSelectedImages(List<SelectedImage> images) => ImageToPdfState(
        status: ImageToPdfStatus.selected,
        images: List.unmodifiable(images),
      );

  /// 重置到 idle（取消选择 / 重新选择 / 重试）。
  ImageToPdfState get toIdle => const ImageToPdfState(status: ImageToPdfStatus.idle);

  /// 开始转换（保留当前列表与顺序）。
  ImageToPdfState get toConverting => ImageToPdfState(
        status: ImageToPdfStatus.converting,
        images: images,
      );

  /// 转换成功（保留列表以便重新选择时显示）。
  ImageToPdfState withSuccess(String path, int size) => ImageToPdfState(
        status: ImageToPdfStatus.success,
        images: images,
        outputPath: path,
        outputSizeBytes: size,
      );

  /// 转换失败（保留列表，允许调整或重试）。
  ImageToPdfState withError(String message) => ImageToPdfState(
        status: ImageToPdfStatus.failure,
        images: images,
        errorMessage: message,
      );
}
