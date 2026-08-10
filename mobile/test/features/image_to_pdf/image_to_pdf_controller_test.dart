import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:docushift_mobile/features/image_to_pdf/image_to_pdf_controller.dart';
import 'package:docushift_mobile/features/image_to_pdf/image_to_pdf_state.dart';
import 'package:docushift_mobile/features/image_to_pdf/image_to_pdf_platform_interface.dart';

/// 模拟“用户取消保存”：返回 null（控制器以此判定取消）。
/// 必须带显式返回类型，否则闭包推断为 `Future<Null>` 会被字段类型拒绝。
Future<Map<String, dynamic>?> _cancelSave(List<String> _) async => null;

void main() {
  late FakeImageToPdfPlatform fakePlatform;
  late ImageToPdfController controller;

  setUp(() {
    fakePlatform = FakeImageToPdfPlatform();
    controller = ImageToPdfController(platform: fakePlatform);
  });

  group('pickImages', () {
    test('选择成功 → 进入 selected 状态，显示名来自平台而非 URI', () async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'content://img/photo.jpg', 'name': 'photo.jpg'},
      ];
      await controller.pickImages();
      expect(controller.state.status, ImageToPdfStatus.selected);
      expect(controller.state.images.length, 1);
      expect(controller.state.images[0].uri, 'content://img/photo.jpg');
      expect(controller.state.images[0].displayName, 'photo.jpg');
    });

    test('显示名不根据 URI 猜测：平台返回空名则显示名为空', () async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'content://img/photo.jpg', 'name': ''},
      ];
      await controller.pickImages();
      expect(controller.state.images[0].displayName, isEmpty);
    });

    test('多张选择保持顺序进入列表', () async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'u1', 'name': 'n1'},
        {'uri': 'u2', 'name': 'n2'},
        {'uri': 'u3', 'name': 'n3'},
      ];
      await controller.pickImages();
      expect(controller.state.images.map((i) => i.uri).toList(), ['u1', 'u2', 'u3']);
    });

    test('用户取消选择 → 保持原列表不覆盖', () async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'u1', 'name': 'n1'},
        {'uri': 'u2', 'name': 'n2'},
      ];
      await controller.pickImages();
      final before = controller.state.images;
      fakePlatform.onPickImages = () async => null;
      await controller.pickImages();
      expect(controller.state.status, ImageToPdfStatus.selected);
      expect(controller.state.images, before);
    });

    test('平台异常 → failure 状态', () async {
      fakePlatform.onPickImages = () => throw Exception('平台错误');
      await controller.pickImages();
      expect(controller.state.status, ImageToPdfStatus.failure);
      expect(controller.state.errorMessage, contains('平台错误'));
    });
  });

  group('列表调整', () {
    Future<void> pickThree() async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'u1', 'name': 'n1'},
        {'uri': 'u2', 'name': 'n2'},
        {'uri': 'u3', 'name': 'n3'},
      ];
      await controller.pickImages();
    }

    test('上移改变顺序', () async {
      await pickThree();
      controller.moveImage(2, 1);
      expect(controller.state.images.map((i) => i.uri).toList(), ['u1', 'u3', 'u2']);
    });

    test('下移改变顺序', () async {
      await pickThree();
      controller.moveImage(0, 1);
      expect(controller.state.images.map((i) => i.uri).toList(), ['u2', 'u1', 'u3']);
    });

    test('删除指定图片', () async {
      await pickThree();
      controller.removeImage(1);
      expect(controller.state.images.map((i) => i.uri).toList(), ['u1', 'u3']);
    });

    test('删除到空列表 → idle', () async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'u1', 'name': 'n1'},
      ];
      await controller.pickImages();
      controller.removeImage(0);
      expect(controller.state.status, ImageToPdfStatus.idle);
      expect(controller.state.images, isEmpty);
    });
  });

  group('convertAndSave', () {
    setUp(() {
      fakePlatform.onPickImages = () async => [
        {'uri': 'content://img/test.png', 'name': 'test.png'},
      ];
    });

    test('单张转换成功 → success（第 2 期回归）', () async {
      await controller.pickImages();
      fakePlatform.onConvertAndSave = (_) async => <String, dynamic>{'path': '/storage/output.pdf', 'size': 12345};
      await controller.convertAndSave();
      expect(controller.state.status, ImageToPdfStatus.success);
      expect(controller.state.outputPath, '/storage/output.pdf');
      expect(controller.state.outputSizeBytes, 12345);
    });

    test('多张按 UI 顺序传给平台', () async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'u1', 'name': 'n1'},
        {'uri': 'u2', 'name': 'n2'},
        {'uri': 'u3', 'name': 'n3'},
      ];
      await controller.pickImages();
      late List<String> passed;
      fakePlatform.onConvertAndSave = (uris) async {
        passed = uris;
        return <String, dynamic>{'path': '/o.pdf', 'size': 9};
      };
      await controller.convertAndSave();
      expect(passed, ['u1', 'u2', 'u3']);
    });

    test('用户取消保存 → 返回 selected 且保留原列表与顺序', () async {
      await controller.pickImages();
      final before = controller.state.images;
      fakePlatform.onConvertAndSave = _cancelSave;
      await controller.convertAndSave();
      expect(controller.state.status, ImageToPdfStatus.selected);
      expect(controller.state.images, before);
    });

    test('转换失败 → failure 且保留列表可重试', () async {
      await controller.pickImages();
      fakePlatform.onConvertAndSave = (_) => throw Exception('转换失败');
      await controller.convertAndSave();
      expect(controller.state.status, ImageToPdfStatus.failure);
      expect(controller.state.errorMessage, contains('转换失败'));
      expect(controller.state.images.length, 1); // 列表保留
    });

    test('无选中图片时调用 convertAndSave 无效果', () async {
      await controller.convertAndSave();
      expect(controller.state.status, ImageToPdfStatus.idle);
    });

    test('converting 状态在调用期间设置', () async {
      await controller.pickImages();
      late ImageToPdfStatus duringCall;
      fakePlatform.onConvertAndSave = (_) async {
        duringCall = controller.state.status;
        return <String, dynamic>{'path': '/o.pdf', 'size': 1};
      };
      await controller.convertAndSave();
      expect(duringCall, ImageToPdfStatus.converting);
    });
  });

  group('reset', () {
    test('从任意状态重置到 idle', () async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'uri', 'name': 'img.jpg'},
      ];
      await controller.pickImages();
      expect(controller.state.status, ImageToPdfStatus.selected);
      controller.reset();
      expect(controller.state.status, ImageToPdfStatus.idle);
      expect(controller.state.images, isEmpty);
    });
  });

  // ================================================================
  // 第 9 期：shareOutput
  // ================================================================

  group('shareOutput', () {
    Future<void> goToSuccess() async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'u1', 'name': 'img.png'},
      ];
      await controller.pickImages();
      fakePlatform.onConvertAndSave = (_) async =>
          {'path': 'content://docushift/out.pdf', 'size': 999};
      await controller.convertAndSave();
    }

    test('success 态调用 sharePdf 传 outputPath', () async {
      await goToSuccess();
      String? gotUri;
      fakePlatform.onSharePdf = (uri) async { gotUri = uri; };
      expect(controller.state.status, ImageToPdfStatus.success);
      await controller.shareOutput();
      expect(gotUri, 'content://docushift/out.pdf');
    });

    test('success 态分享后状态保持不变', () async {
      await goToSuccess();
      fakePlatform.onSharePdf = (_) async {};
      await controller.shareOutput();
      expect(controller.state.status, ImageToPdfStatus.success);
      expect(controller.state.outputPath, 'content://docushift/out.pdf');
      expect(controller.state.outputSizeBytes, 999);
    });

    test('分享平台异常后仍为 success 状态', () async {
      await goToSuccess();
      fakePlatform.onSharePdf = (_) async =>
          throw PlatformException(code: 'SHARE_UNAVAILABLE', message: '无可用应用');
      final err = await controller.shareOutput();
      expect(err, isNotNull);
      expect(err, contains('无可用应用'));
      // 状态不能变成 failure
      expect(controller.state.status, ImageToPdfStatus.success);
      expect(controller.state.outputPath, isNotNull);
    });

    test('idle 态不调用 platform', () async {
      var called = false;
      fakePlatform.onSharePdf = (_) async { called = true; };
      final err = await controller.shareOutput();
      expect(err, isNull);
      expect(called, isFalse);
    });

    test('selected 态不调用 platform', () async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'u1', 'name': 'img.png'},
      ];
      await controller.pickImages();
      var called = false;
      fakePlatform.onSharePdf = (_) async { called = true; };
      await controller.shareOutput();
      expect(called, isFalse);
    });

    test('failure 态不调用 platform', () async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'u1', 'name': 'img.png'},
      ];
      await controller.pickImages();
      fakePlatform.onConvertAndSave = (_) async =>
          throw PlatformException(code: 'ERROR', message: 'fail');
      await controller.convertAndSave();
      expect(controller.state.status, ImageToPdfStatus.failure);
      var called = false;
      fakePlatform.onSharePdf = (_) async { called = true; };
      await controller.shareOutput();
      expect(called, isFalse);
    });

    // ================================================================
    // 第 9 期 R1：converting 态 + success 空 URI 不调用平台
    // ================================================================

    test('converting 态不调用 platform', () async {
      // 构造一个处于 converting 异步中的状态：直接设 state
      // 但 controller 不暴露 setState，这里用 controller 正常流程达到 converting
      fakePlatform.onPickImages = () async => [
        {'uri': 'u1', 'name': 'img.png'},
      ];
      await controller.pickImages();
      // 让 convertAndSave 异步挂起，期间检查 shareOutput
      fakePlatform.onConvertAndSave = (_) async {
        // 在异步回调中模拟 shareOutput 调用
        var called = false;
        fakePlatform.onSharePdf = (_) async { called = true; };
        await controller.shareOutput();
        expect(called, isFalse);
        return {'path': 'content://out.pdf', 'size': 999};
      };
      await controller.convertAndSave();
    });

    test('success 但 outputPath 空不调用 platform', () async {
      fakePlatform.onPickImages = () async => [
        {'uri': 'u1', 'name': 'img.png'},
      ];
      await controller.pickImages();
      // 返回空 path 仍为 success 但 outputPath 为空
      fakePlatform.onConvertAndSave = (_) async =>
          {'path': '', 'size': 0};
      await controller.convertAndSave();
      expect(controller.state.status, ImageToPdfStatus.success);
      expect(controller.state.outputPath, isEmpty);
      var called = false;
      fakePlatform.onSharePdf = (_) async { called = true; };
      await controller.shareOutput();
      expect(called, isFalse);
    });
  });
}
