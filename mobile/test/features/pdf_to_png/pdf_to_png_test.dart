import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:docushift_mobile/features/pdf_to_jpg/pdf_to_jpg_page.dart';
import 'package:docushift_mobile/features/pdf_to_png/pdf_to_png_page.dart';
import 'package:docushift_mobile/features/pdf_to_png/pdf_to_png_state.dart';
import 'package:docushift_mobile/features/raster_resolution.dart';
import 'package:docushift_mobile/home_page.dart';

void main() {
  group('PdfToPngState — 状态转换（第 4 期）', () {
    const pdf = SelectedPdf(
      uri: 'content://docs/report.pdf',
      displayName: 'report.pdf',
      pageCount: 3,
      sizeBytes: 2048,
    );

    test('初始状态为 idle，无 PDF', () {
      const state = PdfToPngState();
      expect(state.status, PdfToPngStatus.idle);
      expect(state.pdf, isNull);
      expect(state.isInteractive, isTrue);
    });

    test('选择 PDF 后进入 selected（原生元数据）', () {
      final s = const PdfToPngState().withSelectedPdf(pdf);
      expect(s.status, PdfToPngStatus.selected);
      expect(s.pdf!.displayName, 'report.pdf');
      expect(s.pdf!.pageCount, 3);
      expect(s.pdf!.sizeBytes, 2048);
    });

    test('selected → choosingFolder → selected（目录取消）保留元数据', () {
      final choosing = const PdfToPngState().withSelectedPdf(pdf).toChoosingFolder;
      expect(choosing.status, PdfToPngStatus.choosingFolder);
      expect(choosing.pdf, isNotNull);
      final back = choosing.toSelected;
      expect(back.status, PdfToPngStatus.selected);
      expect(back.pdf!.displayName, 'report.pdf');
    });

    test('converting → success 保留元数据并携带输出信息', () {
      final success = const PdfToPngState()
          .withSelectedPdf(pdf)
          .toChoosingFolder
          .toConverting
          .withSuccess('content://tree/out/report_PNG_x', 999, 3);
      expect(success.status, PdfToPngStatus.success);
      expect(success.pdf, isNotNull);
      expect(success.outputDirectoryUri, 'content://tree/out/report_PNG_x');
      expect(success.outputSizeBytes, 999);
    });

    test('converting → failure 保留元数据（可重试）', () {
      final fail = const PdfToPngState()
          .withSelectedPdf(pdf)
          .toConverting
          .withError('第 2 页渲染失败');
      expect(fail.status, PdfToPngStatus.failure);
      expect(fail.pdf!.displayName, 'report.pdf');
      expect(fail.errorMessage, '第 2 页渲染失败');
      expect(fail.isInteractive, isTrue);
    });

    test('toIdle 清空全部元数据', () {
      final idle = const PdfToPngState().withSelectedPdf(pdf).toIdle;
      expect(idle.status, PdfToPngStatus.idle);
      expect(idle.pdf, isNull);
    });

    test('choosingFolder / converting 非交互态', () {
      final choosing = const PdfToPngState().withSelectedPdf(pdf).toChoosingFolder;
      expect(choosing.isInteractive, isFalse);
      expect(choosing.toConverting.isInteractive, isFalse);
    });
  });

  group('HomePage — 三功能入口（第 5 期）', () {
    testWidgets('显示三个功能入口', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      expect(find.text('图片转 PDF'), findsOneWidget);
      expect(find.text('PDF 转 PNG'), findsOneWidget);
      expect(find.text('PDF 转 JPG'), findsOneWidget);
    });

    testWidgets('点击 PDF 转 PNG 进入功能页', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.tap(find.text('PDF 转 PNG'));
      await tester.pumpAndSettle();
      expect(find.byType(PdfToPngPage), findsOneWidget);
      expect(find.text('选择 PDF'), findsOneWidget);
    });

    testWidgets('点击 PDF 转 JPG 进入功能页', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.tap(find.text('PDF 转 JPG'));
      await tester.pumpAndSettle();
      expect(find.byType(PdfToJpgPage), findsOneWidget);
      expect(find.text('选择 PDF'), findsOneWidget);
    });
  });

  group('PdfToPngPage — 初始 UI（第 4 期）', () {
    testWidgets('idle 态显示选择按钮与说明', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: PdfToPngPage()));
      expect(find.text('选择 PDF'), findsOneWidget);
      expect(find.textContaining('按页导出 PNG 文件夹'), findsOneWidget);
    });
  });

  // ================================================================
  // 第 8 期 R1：清晰度状态流转
  // ================================================================

  group('PdfToPngState — 清晰度状态（第 8 期）', () {
    const pdf = SelectedPdf(
      uri: 'content://docs/report.pdf',
      displayName: 'report.pdf',
      pageCount: 3,
      sizeBytes: 2048,
    );

    test('选择 PDF 后清晰度初始化为标准 144', () {
      final s = const PdfToPngState().withSelectedPdf(pdf);
      expect(s.resolution, RasterResolution.standard);
      expect(s.resolution!.dpi, 144);
    });

    test('withResolution 更新清晰度而保留页范围', () {
      final s = const PdfToPngState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.low);
      expect(s.resolution, RasterResolution.low);
      expect(s.resolution!.dpi, 96);
      expect(s.range!.startPage, 1); // 范围未变
    });

    test('toIdle 清空清晰度', () {
      final idle = const PdfToPngState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.high)
          .toIdle;
      expect(idle.resolution, isNull);
      expect(idle.pdf, isNull);
    });

    test('toChoosingFolder 保留清晰度', () {
      final s = const PdfToPngState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.high)
          .toChoosingFolder;
      expect(s.resolution, RasterResolution.high);
    });

    test('toSelected（目录取消）保留清晰度', () {
      final s = const PdfToPngState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.low)
          .toChoosingFolder
          .toSelected;
      expect(s.resolution, RasterResolution.low);
    });

    test('toConverting 保留清晰度', () {
      final s = const PdfToPngState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.high)
          .toConverting;
      expect(s.resolution, RasterResolution.high);
    });

    test('withSuccess 保留清晰度', () {
      final s = const PdfToPngState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.low)
          .toConverting
          .withSuccess('content://out', 99, 3);
      expect(s.resolution, RasterResolution.low);
    });

    test('withError 保留清晰度（失败重试）', () {
      final s = const PdfToPngState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.high)
          .toConverting
          .withError('失败');
      expect(s.resolution, RasterResolution.high);
      expect(s.errorMessage, '失败');
    });

    test('重选 PDF 后清晰度重置为标准 144', () {
      final s = const PdfToPngState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.low)
          .withSelectedPdf(const SelectedPdf(
            uri: 'content://docs/b.pdf',
            displayName: 'b.pdf',
            pageCount: 5,
            sizeBytes: 4096,
          ));
      expect(s.resolution, RasterResolution.standard);
      expect(s.pdf!.pageCount, 5);
    });
  });

  group('PdfToPngPage — 清晰度 UI（第 8 期）', () {
    testWidgets('idle 态不显示清晰度选择', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: PdfToPngPage()));
      expect(find.textContaining('清晰度'), findsNothing);
    });

    testWidgets('仍显示三功能入口', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      expect(find.text('图片转 PDF'), findsOneWidget);
      expect(find.text('PDF 转 PNG'), findsOneWidget);
      expect(find.text('PDF 转 JPG'), findsOneWidget);
    });
  });
}
