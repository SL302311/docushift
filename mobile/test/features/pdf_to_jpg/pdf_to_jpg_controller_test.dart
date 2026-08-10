import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:docushift_mobile/features/pdf_to_jpg/pdf_to_jpg_controller.dart';
import 'package:docushift_mobile/features/pdf_to_jpg/pdf_to_jpg_platform_interface.dart';
import 'package:docushift_mobile/features/pdf_to_jpg/pdf_to_jpg_state.dart';
import 'package:docushift_mobile/features/pdf_page_range.dart';
import 'package:docushift_mobile/features/raster_resolution.dart';

void main() {
  group('PdfToJpgController — 两步选择流（第 5 期）', () {
    late FakePdfToJpgPlatform platform;
    late PdfToJpgController controller;

    Map<String, dynamic> pickedPdf({
      String uri = 'content://docs/report.pdf',
      String name = 'report.pdf',
      int pageCount = 3,
      int size = 2048,
    }) =>
        {'uri': uri, 'name': name, 'pageCount': pageCount, 'size': size};

    setUp(() {
      platform = FakePdfToJpgPlatform();
      controller = PdfToJpgController(platform: platform);
    });

    test('初始状态为 idle，无 PDF', () {
      expect(controller.state.status, PdfToJpgStatus.idle);
      expect(controller.state.pdf, isNull);
    });

    test('pickPdf 成功 → selected，显示原生元数据（不猜名称）', () async {
      platform.onPickPdf = () async => pickedPdf(name: '月度报告.pdf', pageCount: 5);
      await controller.pickPdf();
      expect(controller.state.status, PdfToJpgStatus.selected);
      expect(controller.state.pdf!.displayName, '月度报告.pdf');
      expect(controller.state.pdf!.pageCount, 5);
      expect(controller.state.pdf!.sizeBytes, 2048);
      expect(controller.state.pdf!.uri, 'content://docs/report.pdf');
    });

    test('pickPdf 取消（null）→ 保持当前状态', () async {
      platform.onPickPdf = () async => pickedPdf();
      await controller.pickPdf();
      expect(controller.state.status, PdfToJpgStatus.selected);

      // 再次选择但取消 → 保留原 PDF 元数据
      platform.onPickPdf = () async => null;
      await controller.pickPdf();
      expect(controller.state.status, PdfToJpgStatus.selected);
      expect(controller.state.pdf!.displayName, 'report.pdf');
    });

    test('pickPdf 验证失败（PlatformException）→ failure 并携带消息', () async {
      platform.onPickPdf = () async =>
          throw PlatformException(code: 'TOO_MANY_PAGES', message: 'PDF 页数超过 20');
      await controller.pickPdf();
      expect(controller.state.status, PdfToJpgStatus.failure);
      expect(controller.state.errorMessage, contains('PDF 页数超过 20'));
    });

    test('目录取消 → 回到 selected，不启动转换', () async {
      platform.onPickPdf = () async => pickedPdf();
      await controller.pickPdf();

      var convertCalled = false;
      platform.onPickOutputDirectory = () async => null;
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async {
        convertCalled = true;
        return {'directoryUri': 'x', 'pageCount': 3, 'size': 1};
      };

      await controller.chooseFolderAndConvert();
      expect(controller.state.status, PdfToJpgStatus.selected);
      expect(controller.state.pdf, isNotNull);
      expect(convertCalled, isFalse);
    });

    test('参数透传：convert 收到选中的 pdfUri 与 directoryUri', () async {
      platform.onPickPdf = () async => pickedPdf(uri: 'content://docs/a.pdf');
      await controller.pickPdf();

      String? gotPdfUri;
      String? gotDirUri;
      platform.onPickOutputDirectory = () async => 'content://tree/out';
      platform.onConvertPdfToJpg = (pdfUri, dirUri, _, _, {int? resolution}) async {
        gotPdfUri = pdfUri;
        gotDirUri = dirUri;
        return {'directoryUri': 'content://tree/out/a_JPG_x', 'pageCount': 3, 'size': 30};
      };

      await controller.chooseFolderAndConvert();
      expect(gotPdfUri, 'content://docs/a.pdf');
      expect(gotDirUri, 'content://tree/out');
    });

    test('转换成功 → success，携带输出目录与总字节数', () async {
      platform.onPickPdf = () async => pickedPdf();
      await controller.pickPdf();

      platform.onPickOutputDirectory = () async => 'content://tree/out';
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async =>
          {'directoryUri': 'content://tree/out/report_JPG_x', 'pageCount': 3, 'size': 999};

      await controller.chooseFolderAndConvert();
      expect(controller.state.status, PdfToJpgStatus.success);
      expect(controller.state.outputDirectoryUri, 'content://tree/out/report_JPG_x');
      expect(controller.state.outputSizeBytes, 999);
      expect(controller.state.pdf, isNotNull);
    });

    test('转换失败 → failure 保留 PDF 元数据以便重试', () async {
      platform.onPickPdf = () async => pickedPdf();
      await controller.pickPdf();

      platform.onPickOutputDirectory = () async => 'content://tree/out';
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async => throw PlatformException(
          code: 'PAGE_RENDER_FAILED', message: '第 2 页（report.pdf）渲染失败');

      await controller.chooseFolderAndConvert();
      expect(controller.state.status, PdfToJpgStatus.failure);
      expect(controller.state.errorMessage, contains('第 2 页'));
      expect(controller.state.pdf!.displayName, 'report.pdf');
    });

    test('失败后 retry → 重新走选目录 + 转换并成功', () async {
      platform.onPickPdf = () async => pickedPdf();
      await controller.pickPdf();

      platform.onPickOutputDirectory = () async => 'content://tree/out';
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async =>
          throw PlatformException(code: 'OUTPUT_WRITE_FAILED', message: '写入失败');
      await controller.chooseFolderAndConvert();
      expect(controller.state.status, PdfToJpgStatus.failure);

      var dirPicks = 0;
      platform.onPickOutputDirectory = () async {
        dirPicks++;
        return 'content://tree/out2';
      };
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async =>
          {'directoryUri': 'content://tree/out2/report_JPG_y', 'pageCount': 3, 'size': 42};

      await controller.retry();
      expect(dirPicks, 1);
      expect(controller.state.status, PdfToJpgStatus.success);
      expect(controller.state.outputDirectoryUri, 'content://tree/out2/report_JPG_y');
    });

    test('retry 仅在 failure 态有效', () async {
      var dirPicks = 0;
      platform.onPickOutputDirectory = () async {
        dirPicks++;
        return 'content://tree/out';
      };
      await controller.retry();
      expect(dirPicks, 0);
      expect(controller.state.status, PdfToJpgStatus.idle);
    });

    test('reset → 回到 idle 清空元数据', () async {
      platform.onPickPdf = () async => pickedPdf();
      await controller.pickPdf();
      controller.reset();
      expect(controller.state.status, PdfToJpgStatus.idle);
      expect(controller.state.pdf, isNull);
    });

    test('未选择 PDF 时 chooseFolderAndConvert 无操作', () async {
      var dirPicks = 0;
      platform.onPickOutputDirectory = () async {
        dirPicks++;
        return 'x';
      };
      await controller.chooseFolderAndConvert();
      expect(dirPicks, 0);
      expect(controller.state.status, PdfToJpgStatus.idle);
    });
  });

  group('PdfToJpgController — 第 6 期连续页范围', () {
    late FakePdfToJpgPlatform platform;
    late PdfToJpgController controller;

    Map<String, dynamic> picked({int pageCount = 5}) =>
        {'uri': 'content://docs/r.pdf', 'name': 'r.pdf', 'pageCount': pageCount, 'size': 2048};

    setUp(() {
      platform = FakePdfToJpgPlatform();
      controller = PdfToJpgController(platform: platform);
    });

    test('默认范围初始化为全页', () async {
      platform.onPickPdf = () async => picked(pageCount: 5);
      await controller.pickPdf();
      final range = controller.state.range!;
      expect(range, PdfPageRange.all(5));
      expect(range.startPage, 1);
      expect(range.endPage, 5);
    });

    test('setStartPage 联动：起始超过结束 → 结束同步提高', () async {
      platform.onPickPdf = () async => picked(pageCount: 5);
      await controller.pickPdf();
      controller.setEndPage(2); // 1..2
      controller.setStartPage(4); // 4 > 2 → 结束提高到 4 → 4..4
      expect(controller.state.range!.startPage, 4);
      expect(controller.state.range!.endPage, 4);
    });

    test('setEndPage 联动：结束低于起始 → 起始同步降低', () async {
      platform.onPickPdf = () async => picked(pageCount: 5);
      await controller.pickPdf();
      controller.setStartPage(4); // 4..5
      controller.setEndPage(2); // 2 < 4 → 起始降低到 2 → 2..2
      expect(controller.state.range!.startPage, 2);
      expect(controller.state.range!.endPage, 2);
    });

    test('setStartPage / setEndPage 钳制到 [1, 总页数]', () async {
      platform.onPickPdf = () async => picked(pageCount: 5);
      await controller.pickPdf();
      controller.setStartPage(99);
      expect(controller.state.range!.startPage, 5);
      expect(controller.state.range!.endPage, 5);
      controller.setEndPage(0);
      expect(controller.state.range!.startPage, 1);
      expect(controller.state.range!.endPage, 1);
    });

    test('重选 PDF 后范围重置为全页', () async {
      platform.onPickPdf = () async => picked(pageCount: 5);
      await controller.pickPdf();
      controller.setStartPage(3); // 3..5
      expect(controller.state.range!.startPage, 3);
      platform.onPickPdf = () async => picked(pageCount: 4);
      await controller.pickPdf();
      expect(controller.state.range!, PdfPageRange.all(4));
    });

    test('参数透传：convert 收到显式范围 startPage/endPage', () async {
      platform.onPickPdf = () async => picked(pageCount: 5);
      await controller.pickPdf();
      controller.setStartPage(2);
      controller.setEndPage(4); // 2..4

      int? gotStart;
      int? gotEnd;
      platform.onPickOutputDirectory = () async => 'content://tree/out';
      platform.onConvertPdfToJpg = (_, _, startPage, endPage, {int? resolution}) async {
        gotStart = startPage;
        gotEnd = endPage;
        return {'directoryUri': 'x', 'pageCount': 3, 'size': 30};
      };
      await controller.chooseFolderAndConvert();
      expect(gotStart, 2);
      expect(gotEnd, 4);
    });

    test('成功结果中的 outputPageCount 为实际导出页数', () async {
      platform.onPickPdf = () async => picked(pageCount: 5);
      await controller.pickPdf();
      controller.setStartPage(2);
      controller.setEndPage(4); // 3 页

      platform.onPickOutputDirectory = () async => 'content://tree/out';
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async =>
          {'directoryUri': 'content://tree/out/x', 'pageCount': 3, 'size': 30};
      await controller.chooseFolderAndConvert();
      expect(controller.state.status, PdfToJpgStatus.success);
      expect(controller.state.outputPageCount, 3);
    });

    test('失败重试保留已选范围', () async {
      platform.onPickPdf = () async => picked(pageCount: 5);
      await controller.pickPdf();
      controller.setStartPage(3);
      controller.setEndPage(5); // 3..5

      platform.onPickOutputDirectory = () async => 'content://tree/out';
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async =>
          throw PlatformException(code: 'OUTPUT_WRITE_FAILED', message: '写入失败');
      await controller.chooseFolderAndConvert();
      expect(controller.state.status, PdfToJpgStatus.failure);

      int? gotStart;
      int? gotEnd;
      platform.onPickOutputDirectory = () async => 'content://tree/out2';
      platform.onConvertPdfToJpg = (_, _, startPage, endPage, {int? resolution}) async {
        gotStart = startPage;
        gotEnd = endPage;
        return {'directoryUri': 'y', 'pageCount': 3, 'size': 9};
      };
      await controller.retry();
      expect(gotStart, 3);
      expect(gotEnd, 5);
    });
  });

  // ================================================================
  // 第 8 期 R1：清晰度参数透传 + 状态切换（JPG 同构）
  // ================================================================

  group('PdfToJpgController — 清晰度（第 8 期）', () {
    late FakePdfToJpgPlatform platform;
    late PdfToJpgController controller;

    Map<String, dynamic> picked({int pageCount = 5}) =>
        {'uri': 'content://docs/r.pdf', 'name': 'r.pdf', 'pageCount': pageCount, 'size': 2048};

    setUp(() {
      platform = FakePdfToJpgPlatform();
      controller = PdfToJpgController(platform: platform);
    });

    test('默认清晰度为标准 144', () async {
      platform.onPickPdf = () async => picked();
      await controller.pickPdf();
      expect(controller.state.resolution, RasterResolution.standard);
    });

    test('setResolution 切换到 96 dpi', () async {
      platform.onPickPdf = () async => picked();
      await controller.pickPdf();
      controller.setResolution(RasterResolution.low);
      expect(controller.state.resolution, RasterResolution.low);
    });

    test('idle 态 setResolution 不生效', () {
      controller.setResolution(RasterResolution.high);
      expect(controller.state.resolution, isNull);
    });

    test('参数透传 96 dpi', () async {
      platform.onPickPdf = () async => picked();
      await controller.pickPdf();
      controller.setResolution(RasterResolution.low);

      int? gotResolution;
      platform.onPickOutputDirectory = () async => 'content://tree/out';
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async {
        gotResolution = resolution;
        return {'directoryUri': 'x', 'pageCount': 5, 'size': 30};
      };
      await controller.chooseFolderAndConvert();
      expect(gotResolution, 96);
    });

    test('参数透传 216 dpi', () async {
      platform.onPickPdf = () async => picked();
      await controller.pickPdf();
      controller.setResolution(RasterResolution.high);

      int? gotResolution;
      platform.onPickOutputDirectory = () async => 'content://tree/out';
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async {
        gotResolution = resolution;
        return {'directoryUri': 'x', 'pageCount': 5, 'size': 30};
      };
      await controller.chooseFolderAndConvert();
      expect(gotResolution, 216);
    });

    test('失败重试保留清晰度', () async {
      platform.onPickPdf = () async => picked();
      await controller.pickPdf();
      controller.setResolution(RasterResolution.high);

      platform.onPickOutputDirectory = () async => 'content://tree/out';
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async =>
          throw PlatformException(code: 'ERROR', message: 'fail');
      await controller.chooseFolderAndConvert();
      expect(controller.state.status, PdfToJpgStatus.failure);
      expect(controller.state.resolution, RasterResolution.high);

      int? gotResolution;
      platform.onPickOutputDirectory = () async => 'content://tree/out2';
      platform.onConvertPdfToJpg = (_, _, _, _, {int? resolution}) async {
        gotResolution = resolution;
        return {'directoryUri': 'y', 'pageCount': 5, 'size': 9};
      };
      await controller.retry();
      expect(gotResolution, 216);
    });
  });
}
