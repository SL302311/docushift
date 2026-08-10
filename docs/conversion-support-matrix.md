# DocuShift 转换支持矩阵

> 版本：v1.6（第 2—9/10 期工程验收通过；第 10 期已规划）
>
> 状态：第 2—9 期工程验收通过；第 10 期只交付候选 APK 并由用户完成 Android 真机验收

## 标注说明

| 标记 | 含义 |
|---|---|
| ✅ 工程支持 | 已有实现、自动测试和构建证据并通过阶段工程验收 |
| 🛠️ 本期实施 | 已规划，等待当前期实现和工程验收 |
| ⏳ 延期 | 需先满足门禁条件 |
| ❌ 放弃 | 技术或许可不可行 |

## 图片 ↔ PDF

| 输入 | 输出 | 候选引擎 | 状态 | 备注 |
|---|---|---|---|---|
| PNG → PDF | PDF | Android PdfDocument | ✅ 工程支持 | 支持 1—20 张按序合并为多页 PDF，已通过第 3 期工程验收 |
| JPG → PDF | PDF | Android PdfDocument | ✅ 工程支持 | 支持 1—20 张按序合并为多页 PDF，已通过第 3 期工程验收 |
| BMP → PDF | PDF | Android BitmapFactory + PdfDocument | ✅ 工程支持 | 第 7 期：已验证 `image/bmp` / `image/x-ms-bmp`，与 PNG/JPG 混合按序合并；不按后缀猜测未知 MIME |
| TIFF → PDF | PDF | MuPDF | ❌ 放弃 | 系统 API 不直接支持；MuPDF 需商业许可 |
| PDF → PNG | PNG | Android PdfRenderer | ✅ 工程支持（第 8 期扩展） | 单个 PDF 全部页导出至 SAF 文件夹；1—20 页；支持连续页范围；三档清晰度 96/144/216 dpi，默认 144 dpi |
| PDF → JPG | JPG | Android PdfRenderer | ✅ 工程支持（第 8 期扩展） | 单个 PDF 全部页导出至 SAF 文件夹；1—20 页；固定质量 85、白底预填充、支持连续页范围；三档清晰度 96/144/216 dpi |

## 高级文档（均延期）

| 输入 | 输出 | 原因 |
|---|---|---|
| PDF → DOCX | DOCX | 高保真版式还原不可保证，移动端无可靠引擎 |
| DOCX → PDF | PDF | 移动端不可用 Word COM，Pandoc 包体/引擎依赖未评估 |
| MD → DOCX/HTML/PDF | DOCX/HTML/PDF | Pandoc 包体（约 200MB）在移动端不可接受 |
| HTML → DOCX/PDF | DOCX/PDF | 同上 |

## 多图合并

| 功能 | 状态 | 备注 |
|---|---|---|
| 多张 PNG/JPG 按序合并为单个多页 PDF | ✅ 工程支持 | 1—20 张，可上移/下移/删除，逐页解码和回收 |

## 结果交付

| 结果 | 状态 | 备注 |
|---|---|---|
| 图片→PDF 单文件系统分享 | ✅ 工程支持（第 9 期） | 只分享刚生成的 `content://` PDF，以临时读权限交给 Android chooser；不分享文件夹或任意 URI；真机兼容性待第 10 期用户验收 |

## 移动端限制

- iOS 构建需要 macOS/Xcode，Android 先行
- PDF → DOCX 在 MVP 中明确定义为「内容重排」而非「版式还原」
- 包体增量需在每期门禁中确认，超过预算则放弃或更换引擎
- Android 真机统一延后至第 10 期由用户验收；第 2—9 期采用代码、自动测试和构建门禁
