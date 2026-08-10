import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:docushift_mobile/features/image_to_pdf/image_to_pdf_state.dart';
import 'package:docushift_mobile/features/image_to_pdf/image_to_pdf_constants.dart';
import 'package:docushift_mobile/features/image_to_pdf/image_to_pdf_platform.dart';
import 'package:docushift_mobile/features/image_to_pdf/image_to_pdf_page.dart';
import 'package:docushift_mobile/features/image_to_pdf/selected_image.dart';
import 'package:docushift_mobile/home_page.dart';

void main() {
  group('ImageToPdfState — 状态转换（第 3 期列表模型）', () {
    test('初始状态为 idle，图片列表为空', () {
      const state = ImageToPdfState();
      expect(state.status, ImageToPdfStatus.idle);
      expect(state.images, isEmpty);
    });

    test('选择图片后进入 selected 状态（有序列表）', () {
      const state = ImageToPdfState();
      final selected = state.withSelectedImages([
        const SelectedImage(uri: 'content://a/1.jpg', displayName: '1.jpg'),
        const SelectedImage(uri: 'content://a/2.jpg', displayName: '2.jpg'),
      ]);
      expect(selected.status, ImageToPdfStatus.selected);
      expect(selected.images.length, 2);
      expect(selected.images[0].uri, 'content://a/1.jpg');
      expect(selected.images[1].displayName, '2.jpg');
    });

    test('selected → idle（取消选择）清空列表', () {
      final selected = const ImageToPdfState().withSelectedImages(
        [const SelectedImage(uri: 'uri', displayName: 'img.jpg')],
      );
      final idle = selected.toIdle;
      expect(idle.status, ImageToPdfStatus.idle);
      expect(idle.images, isEmpty);
    });

    test('selected → converting 保留列表与顺序', () {
      final images = [const SelectedImage(uri: 'u1', displayName: 'n1')];
      final selected = const ImageToPdfState().withSelectedImages(images);
      final converting = selected.toConverting;
      expect(converting.status, ImageToPdfStatus.converting);
      expect(converting.images, images);
    });

    test('converting → success 保留列表', () {
      final images = [const SelectedImage(uri: 'u1', displayName: 'n1')];
      final conv = const ImageToPdfState()
          .withSelectedImages(images)
          .toConverting;
      final success = conv.withSuccess('/storage/output.pdf', 123456);
      expect(success.status, ImageToPdfStatus.success);
      expect(success.images, images);
      expect(success.outputPath, '/storage/output.pdf');
      expect(success.outputSizeBytes, 123456);
    });

    test('converting → failure 保留列表（可重试）', () {
      final images = [const SelectedImage(uri: 'u1', displayName: 'n1')];
      final conv = const ImageToPdfState()
          .withSelectedImages(images)
          .toConverting;
      final fail = conv.withError('图片解码失败');
      expect(fail.status, ImageToPdfStatus.failure);
      expect(fail.images, images);
      expect(fail.errorMessage, '图片解码失败');
    });

    test('success → idle / failure → idle 清空', () {
      final images = [const SelectedImage(uri: 'u1', displayName: 'n1')];
      final success = const ImageToPdfState()
          .withSelectedImages(images)
          .toConverting
          .withSuccess('/out.pdf', 500);
      expect(success.toIdle.images, isEmpty);

      final fail = const ImageToPdfState()
          .withSelectedImages(images)
          .toConverting
          .withError('错误');
      expect(fail.toIdle.images, isEmpty);
    });

    test('isInteractive 在 idle 和 selected 时为 true', () {
      expect(const ImageToPdfState().isInteractive, true);
      expect(
        const ImageToPdfState()
            .withSelectedImages([const SelectedImage(uri: 'u', displayName: 'n')])
            .isInteractive,
        true,
      );
    });

    test('isInteractive 在非交互态时为 false', () {
      final images = [const SelectedImage(uri: 'u', displayName: 'n')];
      final converting = const ImageToPdfState().withSelectedImages(images).toConverting;
      expect(converting.isInteractive, false);
      expect(converting.withSuccess('/o', 1).isInteractive, false);
      expect(converting.withError('e').isInteractive, false);
    });
  });

  group('calculatePageLayout — 页面布局计算', () {
    test('竖图（400×600）输出纵向 A4，图片等比缩放', () {
      final r = calculatePageLayout(400, 600);
      expect(r.pageWidthPt, closeTo(595, 1));
      expect(r.pageHeightPt, closeTo(842, 1));
      expect(r.drawWidth / r.drawHeight, closeTo(400 / 600, 0.01));
      expect(r.drawX, greaterThanOrEqualTo(kPageMarginPt));
      expect(r.drawY, greaterThanOrEqualTo(kPageMarginPt));
    });

    test('横图（800×400）输出横向 A4（842×595）', () {
      final r = calculatePageLayout(800, 400);
      expect(r.pageWidthPt, closeTo(842, 1));
      expect(r.pageHeightPt, closeTo(595, 1));
      expect(r.drawWidth / r.drawHeight, closeTo(800 / 400, 0.01));
    });

    test('正方形图（500×500）输出纵向 A4', () {
      final r = calculatePageLayout(500, 500);
      expect(r.pageWidthPt, closeTo(595, 1));
      expect(r.pageHeightPt, closeTo(842, 1));
      expect(r.drawWidth, closeTo(r.drawHeight, 0.1));
    });

    test('超大图片（4000×3000）等比缩放不超出内容区域', () {
      final r = calculatePageLayout(4000, 3000);
      expect(r.drawX, greaterThanOrEqualTo(kPageMarginPt));
      expect(r.drawY, greaterThanOrEqualTo(kPageMarginPt));
      expect(r.drawX + r.drawWidth,
          lessThanOrEqualTo(r.pageWidthPt - kPageMarginPt + 1));
      expect(r.drawY + r.drawHeight,
          lessThanOrEqualTo(r.pageHeightPt - kPageMarginPt + 1));
    });

    test('小图（50×30）等比放大到内容区域内', () {
      final r = calculatePageLayout(50, 30);
      expect(r.drawWidth, greaterThanOrEqualTo(50.0));
      expect(r.drawHeight, greaterThanOrEqualTo(30.0));
      expect(r.drawWidth / r.drawHeight, closeTo(50 / 30, 0.01));
    });

    test('缩放比例 scale 为正数', () {
      final r1 = calculatePageLayout(400, 600);
      final r2 = calculatePageLayout(4000, 3000);
      final r3 = calculatePageLayout(50, 30);
      expect(r1.scale, greaterThan(0));
      expect(r2.scale, greaterThan(0));
      expect(r3.scale, greaterThan(0));
    });
  });

  group('输入限制常量', () {
    test('kMaxFileSize 为 30 MB', () {
      expect(kMaxFileSize, 30 * 1024 * 1024);
    });

    test('kMaxPixelCount 为 4000 万像素', () {
      expect(kMaxPixelCount, 40000000);
    });

    test('第 3 期多图边界常量', () {
      expect(kMaxImageCount, 20);
      expect(kMaxTotalSize, 200 * 1024 * 1024);
    });
  });

  // ================================================================
  // 第 9 期：分享 PDF UI
  // ================================================================

  group('ImageToPdfPage — 分享 UI（第 9 期）', () {
    testWidgets('idle 态不显示分享按钮', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: ImageToPdfPage()));
      expect(find.text('分享 PDF'), findsNothing);
    });

    testWidgets('仍显示三功能入口', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      expect(find.text('图片转 PDF'), findsOneWidget);
      expect(find.text('PDF 转 PNG'), findsOneWidget);
      expect(find.text('PDF 转 JPG'), findsOneWidget);
    });
  });
}
