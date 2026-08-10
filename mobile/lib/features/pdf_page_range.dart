/// DocuShift — 连续页范围值对象（第 6 期，PDF/PNG 与 PDF/JPG 共用）。
///
/// 表示 PDF 内的连续页码区间，[startPage]/[endPage] 均为 1-based 闭区间。
/// 文件名沿用原 PDF 页码，因此范围只描述「导出哪些页」，不影响命名。
///
/// 约束（计划冻结）：
/// - `1 ≤ startPage ≤ endPage ≤ PDF 总页数`；
/// - 不提供任意勾选、跳页、反向或多段范围。
class PdfPageRange {
  /// 起始页（1-based，含）。
  final int startPage;

  /// 结束页（1-based，含）。
  final int endPage;

  const PdfPageRange({required this.startPage, required this.endPage});

  /// 全页范围（1..total）。
  factory PdfPageRange.all(int total) =>
      PdfPageRange(startPage: 1, endPage: total);

  /// 本次实际导出的页数（endPage - startPage + 1）。
  int get pageCount => endPage - startPage + 1;

  /// 在约束 [1, total] 内钳制范围；用于把上游/用户选择的越界值拉回合法区间。
  PdfPageRange clamped(int total) {
    final s = startPage < 1 ? 1 : startPage;
    final e = endPage > total ? total : endPage;
    return PdfPageRange(startPage: s, endPage: e);
  }

  /// 是否合法：`1 ≤ startPage ≤ endPage ≤ total`。
  bool isValid(int total) =>
      startPage >= 1 && endPage <= total && startPage <= endPage;

  @override
  bool operator ==(Object other) =>
      other is PdfPageRange &&
      other.startPage == startPage &&
      other.endPage == endPage;

  @override
  int get hashCode => startPage * 31 + endPage;

  @override
  String toString() => 'PdfPageRange($startPage-$endPage)';
}
