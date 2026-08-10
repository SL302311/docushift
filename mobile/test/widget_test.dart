import 'package:flutter_test/flutter_test.dart';
import 'package:docushift_mobile/main.dart';

void main() {
  testWidgets('DocuShift app renders home page with two entries', (WidgetTester tester) async {
    await tester.pumpWidget(const DocuShiftApp());

    // 第 4 期起首页为双功能入口页
    expect(find.text('DocuShift'), findsOneWidget);
    expect(find.text('图片转 PDF'), findsOneWidget);
    expect(find.text('PDF 转 PNG'), findsOneWidget);
  });

  testWidgets('从首页进入图片转 PDF 功能页', (WidgetTester tester) async {
    await tester.pumpWidget(const DocuShiftApp());
    await tester.tap(find.text('图片转 PDF'));
    await tester.pumpAndSettle();

    // 既有功能入口保留
    expect(find.text('选择图片'), findsOneWidget);
  });
}
