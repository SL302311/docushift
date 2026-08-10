import 'package:flutter_test/flutter_test.dart';
import 'package:docushift_mobile/features/raster_resolution.dart';

void main() {
  group('RasterResolution — 第 8 期', () {
    test('三档预设 dpi 值正确', () {
      expect(RasterResolution.low.dpi, 96);
      expect(RasterResolution.standard.dpi, 144);
      expect(RasterResolution.high.dpi, 216);
    });

    test('defaultResolution 为标准 144', () {
      expect(RasterResolution.defaultResolution, RasterResolution.standard);
      expect(RasterResolution.defaultResolution.dpi, 144);
    });

    test('values 包含全部三档', () {
      expect(RasterResolution.values.length, 3);
      expect(RasterResolution.values, containsAll([
        RasterResolution.low,
        RasterResolution.standard,
        RasterResolution.high,
      ]));
    });

    test('fromDpi 返回正确预设', () {
      expect(RasterResolution.fromDpi(96), RasterResolution.low);
      expect(RasterResolution.fromDpi(144), RasterResolution.standard);
      expect(RasterResolution.fromDpi(216), RasterResolution.high);
    });

    test('fromDpi 对非法值返回 null', () {
      expect(RasterResolution.fromDpi(95), isNull);
      expect(RasterResolution.fromDpi(145), isNull);
      expect(RasterResolution.fromDpi(217), isNull);
      expect(RasterResolution.fromDpi(0), isNull);
      expect(RasterResolution.fromDpi(-1), isNull);
    });

    test('fullLabel 标准含推荐标识', () {
      expect(RasterResolution.standard.fullLabel, '标准（144 dpi，推荐）');
    });

    test('fullLabel 低清和高精不含推荐标识', () {
      expect(RasterResolution.low.fullLabel, '低清（96 dpi）');
      expect(RasterResolution.high.fullLabel, '高精（216 dpi）');
    });

    test('相等性比较基于 dpi', () {
      expect(RasterResolution.fromDpi(96), RasterResolution.low);
      expect(RasterResolution.fromDpi(144), RasterResolution.standard);
      expect(RasterResolution.fromDpi(216), RasterResolution.high);
    });

    test('hashCode 基于 dpi', () {
      expect(RasterResolution.low.hashCode, 96);
      expect(RasterResolution.standard.hashCode, 144);
      expect(RasterResolution.high.hashCode, 216);
    });
  });
}
