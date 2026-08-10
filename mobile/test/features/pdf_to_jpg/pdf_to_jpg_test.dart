import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:docushift_mobile/features/pdf_to_jpg/pdf_to_jpg_page.dart';
import 'package:docushift_mobile/features/pdf_to_jpg/pdf_to_jpg_state.dart';
import 'package:docushift_mobile/features/raster_resolution.dart';

void main() {
  group('PdfToJpgState — 状态转换（第 5 期）', () {
    const pdf = SelectedPdf(
      uri: 'content://docs/report.pdf',
      displayName: 'report.pdf',
      pageCount: 3,
      sizeBytes: 2048,
    );

    test('初始状态为 idle，无 PDF', () {
      const state = PdfToJpgState();
      expect(state.status, PdfToJpgStatus.idle);
      expect(state.pdf, isNull);
      expect(state.isInteractive, isTrue);
    });

    test('选择 PDF 后进入 selected（原生元数据）', () {
      final s = const PdfToJpgState().withSelectedPdf(pdf);
      expect(s.status, PdfToJpgStatus.selected);
      expect(s.pdf!.displayName, 'report.pdf');
      expect(s.pdf!.pageCount, 3);
      expect(s.pdf!.sizeBytes, 2048);
    });

    test('selected → choosingFolder → selected（目录取消）保留元数据', () {
      final choosing = const PdfToJpgState().withSelectedPdf(pdf).toChoosingFolder;
      expect(choosing.status, PdfToJpgStatus.choosingFolder);
      expect(choosing.pdf, isNotNull);
      final back = choosing.toSelected;
      expect(back.status, PdfToJpgStatus.selected);
      expect(back.pdf!.displayName, 'report.pdf');
    });

    test('converting → success 保留元数据并携带输出信息', () {
      final success = const PdfToJpgState()
          .withSelectedPdf(pdf)
          .toChoosingFolder
          .toConverting
          .withSuccess('content://tree/out/report_JPG_x', 999, 3);
      expect(success.status, PdfToJpgStatus.success);
      expect(success.pdf, isNotNull);
      expect(success.outputDirectoryUri, 'content://tree/out/report_JPG_x');
      expect(success.outputSizeBytes, 999);
    });

    test('converting → failure 保留元数据（可重试）', () {
      final fail = const PdfToJpgState()
          .withSelectedPdf(pdf)
          .toConverting
          .withError('第 2 页渲染失败');
      expect(fail.status, PdfToJpgStatus.failure);
      expect(fail.pdf!.displayName, 'report.pdf');
      expect(fail.errorMessage, '第 2 页渲染失败');
      expect(fail.isInteractive, isTrue);
    });

    test('toIdle 清空全部元数据', () {
      final idle = const PdfToJpgState().withSelectedPdf(pdf).toIdle;
      expect(idle.status, PdfToJpgStatus.idle);
      expect(idle.pdf, isNull);
    });

    test('choosingFolder / converting 非交互态', () {
      final choosing = const PdfToJpgState().withSelectedPdf(pdf).toChoosingFolder;
      expect(choosing.isInteractive, isFalse);
      expect(choosing.toConverting.isInteractive, isFalse);
    });
  });

  group('PdfToJpgPage — 初始 UI（第 5 期）', () {
    testWidgets('idle 态显示选择按钮与说明', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: PdfToJpgPage()));
      expect(find.text('选择 PDF'), findsOneWidget);
      expect(find.textContaining('按页导出 JPG 文件夹'), findsOneWidget);
    });
  });

  // ================================================================
  // 第 8 期 R1：清晰度状态流转（JPG 同构）
  // ================================================================

  group('PdfToJpgState — 清晰度状态（第 8 期）', () {
    const pdf = SelectedPdf(
      uri: 'content://docs/report.pdf',
      displayName: 'report.pdf',
      pageCount: 3,
      sizeBytes: 2048,
    );

    test('选择 PDF 后清晰度初始化为标准 144', () {
      final s = const PdfToJpgState().withSelectedPdf(pdf);
      expect(s.resolution, RasterResolution.standard);
      expect(s.resolution!.dpi, 144);
    });

    test('withResolution 更新清晰度而保留页范围', () {
      final s = const PdfToJpgState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.high);
      expect(s.resolution, RasterResolution.high);
      expect(s.range!.startPage, 1);
    });

    test('toIdle 清空清晰度', () {
      final idle = const PdfToJpgState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.low)
          .toIdle;
      expect(idle.resolution, isNull);
    });

    test('toChoosingFolder 保留清晰度', () {
      final s = const PdfToJpgState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.high)
          .toChoosingFolder;
      expect(s.resolution, RasterResolution.high);
    });

    test('目录取消 → toSelected 保留清晰度', () {
      final s = const PdfToJpgState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.low)
          .toChoosingFolder
          .toSelected;
      expect(s.resolution, RasterResolution.low);
    });

    test('toConverting 保留清晰度', () {
      final s = const PdfToJpgState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.high)
          .toConverting;
      expect(s.resolution, RasterResolution.high);
    });

    test('withSuccess 保留清晰度', () {
      final s = const PdfToJpgState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.low)
          .toConverting
          .withSuccess('content://out', 99, 3);
      expect(s.resolution, RasterResolution.low);
    });

    test('失败重试保留清晰度', () {
      final s = const PdfToJpgState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.high)
          .toConverting
          .withError('失败');
      expect(s.resolution, RasterResolution.high);
    });

    test('重选 PDF 后清晰度重置为标准 144', () {
      final s = const PdfToJpgState()
          .withSelectedPdf(pdf)
          .withResolution(RasterResolution.low)
          .withSelectedPdf(const SelectedPdf(
            uri: 'content://docs/b.pdf',
            displayName: 'b.pdf',
            pageCount: 5,
            sizeBytes: 4096,
          ));
      expect(s.resolution, RasterResolution.standard);
    });
  });

  group('PdfToJpgPage — 清晰度 UI（第 8 期）', () {
    testWidgets('idle 态不显示清晰度选择', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: PdfToJpgPage()));
      expect(find.textContaining('清晰度'), findsNothing);
    });
  });
}
