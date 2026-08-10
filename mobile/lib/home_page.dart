import 'package:flutter/material.dart';
import 'features/image_to_pdf/image_to_pdf_page.dart';
import 'features/pdf_to_png/pdf_to_png_page.dart';
import 'features/pdf_to_jpg/pdf_to_jpg_page.dart';

/// DocuShift 轻量三入口首页（第 5 期）。
/// 提供「图片转 PDF」「PDF 转 PNG」「PDF 转 JPG」三个功能入口；不改视觉体系。
class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
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
              const Icon(Icons.swap_horiz, size: 64, color: Colors.grey),
              const SizedBox(height: 16),
              Text(
                '选择一个转换功能',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 32),
              _entryCard(
                context,
                icon: Icons.picture_as_pdf,
                title: '图片转 PDF',
                subtitle: '选择 1—20 张 PNG/JPG，按序合并为多页 PDF',
                page: const ImageToPdfPage(),
              ),
              const SizedBox(height: 16),
              _entryCard(
                context,
                icon: Icons.image,
                title: 'PDF 转 PNG',
                subtitle: '选择 1 个 PDF（1—20 页），按页导出 PNG 文件夹',
                page: const PdfToPngPage(),
              ),
              const SizedBox(height: 16),
              _entryCard(
                context,
                icon: Icons.photo,
                title: 'PDF 转 JPG',
                subtitle: '选择 1 个 PDF（1—20 页），按页导出 JPG 文件夹',
                page: const PdfToJpgPage(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _entryCard(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String subtitle,
    required Widget page,
  }) {
    return Card(
      child: ListTile(
        leading: Icon(icon, size: 36),
        title: Text(title),
        subtitle: Text(subtitle),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => Navigator.of(context).push(
          MaterialPageRoute<void>(builder: (_) => page),
        ),
      ),
    );
  }
}
