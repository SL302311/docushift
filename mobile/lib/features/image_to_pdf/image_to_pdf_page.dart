import 'package:flutter/material.dart';
import 'image_to_pdf_controller.dart';
import 'image_to_pdf_state.dart';

/// DocuShift 图片转 PDF 页面。
/// 第 3 期：选择 1—20 张图片，调整顺序/删除，按序生成多页 PDF。
class ImageToPdfPage extends StatefulWidget {
  const ImageToPdfPage({super.key});

  @override
  State<ImageToPdfPage> createState() => _ImageToPdfPageState();
}

class _ImageToPdfPageState extends State<ImageToPdfPage> {
  final _controller = ImageToPdfController();

  @override
  void initState() {
    super.initState();
    _controller.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = _controller.state;
    return Scaffold(
      appBar: AppBar(
        title: const Text('DocuShift'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.picture_as_pdf, size: 64, color: Colors.grey),
              const SizedBox(height: 16),
              Text(
                _statusText(state),
                style: Theme.of(context).textTheme.titleMedium,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              if (state.images.isNotEmpty) _buildList(state),
              if (state.outputPath != null) ...[
                const SizedBox(height: 8),
                Text(
                  '✓ ${_formatBytes(state.outputSizeBytes ?? 0)}',
                  style: const TextStyle(color: Colors.green),
                ),
              ],
              if (state.errorMessage != null) ...[
                const SizedBox(height: 8),
                Text(
                  state.errorMessage!,
                  style: const TextStyle(color: Colors.red),
                  textAlign: TextAlign.center,
                ),
              ],
              const SizedBox(height: 24),
              _buildAction(state),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildList(ImageToPdfState state) {
    return ConstrainedBox(
      constraints: const BoxConstraints(maxHeight: 280),
      child: ListView.separated(
        shrinkWrap: true,
        itemCount: state.images.length,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, index) {
          final img = state.images[index];
          final canMoveUp = state.status == ImageToPdfStatus.selected && index > 0;
          final canMoveDown =
              state.status == ImageToPdfStatus.selected && index < state.images.length - 1;
          return ListTile(
            leading: Text('${index + 1}', style: const TextStyle(color: Colors.grey)),
            title: Text(img.displayName.isEmpty ? img.uri : img.displayName),
            dense: true,
            trailing: state.status == ImageToPdfStatus.selected
                ? Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        icon: const Icon(Icons.arrow_upward, size: 20),
                        tooltip: '上移',
                        onPressed: canMoveUp ? () => _controller.moveImage(index, index - 1) : null,
                      ),
                      IconButton(
                        icon: const Icon(Icons.arrow_downward, size: 20),
                        tooltip: '下移',
                        onPressed: canMoveDown
                            ? () => _controller.moveImage(index, index + 1)
                            : null,
                      ),
                      IconButton(
                        icon: const Icon(Icons.delete, size: 20),
                        tooltip: '删除',
                        onPressed: () => _controller.removeImage(index),
                      ),
                    ],
                  )
                : null,
          );
        },
      ),
    );
  }

  Widget _buildAction(ImageToPdfState state) {
    switch (state.status) {
      case ImageToPdfStatus.idle:
        return ElevatedButton.icon(
          onPressed: _controller.pickImages,
          icon: const Icon(Icons.image),
          label: const Text('选择图片'),
        );

      case ImageToPdfStatus.selected:
        return Column(
          children: [
            ElevatedButton.icon(
              onPressed: _controller.convertAndSave,
              icon: const Icon(Icons.save),
              label: const Text('转换为 PDF'),
            ),
            const SizedBox(height: 12),
            TextButton(
              onPressed: _controller.reset,
              child: const Text('取消'),
            ),
          ],
        );

      case ImageToPdfStatus.converting:
        return const Column(
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 12),
            Text('正在转换…'),
          ],
        );

      case ImageToPdfStatus.success:
        return Column(
          children: [
            ElevatedButton.icon(
              onPressed: () => _sharePdf(context),
              icon: const Icon(Icons.share),
              label: const Text('分享 PDF'),
            ),
            const SizedBox(height: 12),
            ElevatedButton.icon(
              onPressed: _controller.reset,
              icon: const Icon(Icons.refresh),
              label: const Text('重新选择'),
            ),
          ],
        );

      case ImageToPdfStatus.failure:
        return Column(
          children: [
            ElevatedButton.icon(
              onPressed: _controller.convertAndSave,
              icon: const Icon(Icons.refresh),
              label: const Text('重试'),
            ),
            const SizedBox(height: 12),
            TextButton(
              onPressed: _controller.reset,
              child: const Text('取消'),
            ),
          ],
        );
    }
  }

  String _statusText(ImageToPdfState state) {
    switch (state.status) {
      case ImageToPdfStatus.idle:
        return '选择 1—20 张 PNG、JPG 或 BMP\n转换为多页 PDF';
      case ImageToPdfStatus.selected:
        return '已选择 ${state.images.length} 张图片';
      case ImageToPdfStatus.converting:
        return '正在转换…';
      case ImageToPdfStatus.success:
        return '转换完成';
      case ImageToPdfStatus.failure:
        return '转换失败';
    }
  }

  String _formatBytes(int bytes) {
    if (bytes >= 1048576) return '${(bytes / 1048576).toStringAsFixed(1)} MB';
    if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '$bytes B';
  }

  Future<void> _sharePdf(BuildContext context) async {
    final err = await _controller.shareOutput();
    if (err != null && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(err), duration: const Duration(seconds: 3)),
      );
    }
  }
}
