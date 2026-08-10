import 'package:flutter/material.dart';
import 'pdf_to_png_controller.dart';
import 'pdf_to_png_state.dart';
import '../raster_resolution.dart';

/// DocuShift PDF 转 PNG 页面（第 4 期）。
/// 两步选择流：选 PDF（显示原生名/页数/大小）→ 选输出目录 → 转换。
/// 目录未选不启动转换；取消保留当前状态；失败保留元数据以便重试。
class PdfToPngPage extends StatefulWidget {
  const PdfToPngPage({super.key});

  @override
  State<PdfToPngPage> createState() => _PdfToPngPageState();
}

class _PdfToPngPageState extends State<PdfToPngPage> {
  final _controller = PdfToPngController();

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
        title: const Text('PDF 转 PNG'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.image, size: 64, color: Colors.grey),
              const SizedBox(height: 16),
              Text(
                _statusText(state),
                style: Theme.of(context).textTheme.titleMedium,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              if (state.pdf != null) _buildPdfInfo(state),
              if (state.range != null &&
                  (state.status == PdfToPngStatus.selected ||
                      state.status == PdfToPngStatus.failure))
                _buildRangeSelector(state),
              if (state.resolution != null &&
                  (state.status == PdfToPngStatus.selected ||
                      state.status == PdfToPngStatus.failure))
                _buildResolutionSelector(state),
              if (state.outputDirectoryUri != null) ...[
                const SizedBox(height: 8),
                Text(
                  '✓ 已导出 ${state.outputPageCount ?? state.pdf?.pageCount ?? 0} 页，共 ${_formatBytes(state.outputSizeBytes ?? 0)}',
                  style: const TextStyle(color: Colors.green),
                  textAlign: TextAlign.center,
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

  /// 显示原生返回的 PDF 元数据（名称 / 页数 / 大小）。
  Widget _buildPdfInfo(PdfToPngState state) {
    final pdf = state.pdf!;
    return Card(
      child: ListTile(
        leading: const Icon(Icons.picture_as_pdf, color: Colors.redAccent),
        title: Text(
          pdf.displayName.isEmpty ? pdf.uri : pdf.displayName,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Text('${pdf.pageCount} 页 · ${_formatBytes(pdf.sizeBytes)}'),
        dense: true,
      ),
    );
  }

  /// 页码范围选择（起始页 / 结束页两个下拉框）。
  /// 仅在 selected / failure 交互态显示；choosingFolder / converting 时不显示。
  Widget _buildRangeSelector(PdfToPngState state) {
    final pdf = state.pdf!;
    final range = state.range!;
    final options = <int>[for (var i = 1; i <= pdf.pageCount; i++) i];
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            children: [
              Text('导出页码范围（共 ${pdf.pageCount} 页）',
                  style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 8),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  DropdownButton<int>(
                    value: range.startPage,
                    onChanged: (v) {
                      if (v != null) _controller.setStartPage(v);
                    },
                    items: options
                        .map((p) => DropdownMenuItem(
                            value: p, child: Text('起始 $p')))
                        .toList(),
                  ),
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 12),
                    child: Text('—'),
                  ),
                  DropdownButton<int>(
                    value: range.endPage,
                    onChanged: (v) {
                      if (v != null) _controller.setEndPage(v);
                    },
                    items: options
                        .map((p) => DropdownMenuItem(
                            value: p, child: Text('结束 $p')))
                        .toList(),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 清晰度选择（第 8 期）：三档 Dropdown。
  /// 仅在 selected / failure 交互态可修改；converting 时不可改。
  Widget _buildResolutionSelector(PdfToPngState state) {
    final current = state.resolution!;
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            children: [
              const Text('清晰度（改变像素尺寸与文件大小，JPG 压缩质量固定 85）',
                  style: TextStyle(fontSize: 12, color: Colors.grey)),
              const SizedBox(height: 8),
              DropdownButton<RasterResolution>(
                value: current,
                onChanged: (v) {
                  if (v != null) _controller.setResolution(v);
                },
                items: RasterResolution.values
                    .map((r) => DropdownMenuItem(
                        value: r, child: Text(r.fullLabel)))
                    .toList(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildAction(PdfToPngState state) {
    switch (state.status) {
      case PdfToPngStatus.idle:
        return ElevatedButton.icon(
          onPressed: _controller.pickPdf,
          icon: const Icon(Icons.picture_as_pdf),
          label: const Text('选择 PDF'),
        );

      case PdfToPngStatus.selected:
        return Column(
          children: [
            ElevatedButton.icon(
              onPressed: _controller.chooseFolderAndConvert,
              icon: const Icon(Icons.folder_open),
              label: const Text('选择输出文件夹并转换'),
            ),
            const SizedBox(height: 12),
            TextButton(
              onPressed: _controller.pickPdf,
              child: const Text('重新选择 PDF'),
            ),
            TextButton(
              onPressed: _controller.reset,
              child: const Text('取消'),
            ),
          ],
        );

      case PdfToPngStatus.choosingFolder:
        return const Column(
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 12),
            Text('请选择输出文件夹…'),
          ],
        );

      case PdfToPngStatus.converting:
        return const Column(
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 12),
            Text('正在逐页导出 PNG…'),
          ],
        );

      case PdfToPngStatus.success:
        return ElevatedButton.icon(
          onPressed: _controller.reset,
          icon: const Icon(Icons.refresh),
          label: const Text('重新选择'),
        );

      case PdfToPngStatus.failure:
        return Column(
          children: [
            ElevatedButton.icon(
              onPressed: _controller.retry,
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

  String _statusText(PdfToPngState state) {
    switch (state.status) {
      case PdfToPngStatus.idle:
        return '选择 1 个 PDF（1—20 页）\n按页导出 PNG 文件夹';
      case PdfToPngStatus.selected:
        return '已选择 PDF';
      case PdfToPngStatus.choosingFolder:
        return '选择输出文件夹';
      case PdfToPngStatus.converting:
        return '正在转换…';
      case PdfToPngStatus.success:
        return '导出完成';
      case PdfToPngStatus.failure:
        return '转换失败';
    }
  }

  String _formatBytes(int bytes) {
    if (bytes >= 1048576) return '${(bytes / 1048576).toStringAsFixed(1)} MB';
    if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '$bytes B';
  }
}
