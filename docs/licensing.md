# DocuShift 许可证评估

> 版本：v0.1（第 2/10 期初始稿）
> 状态：待法务确认，不作为最终法律意见

## 评估结论一览

| 引擎/库 | 许可证 | 移动端可用 | 商用限制 | 替代方案 |
|---|---|---|---|---|
| PyMuPDF (MuPDF) | AGPL v3 / 商业双轨 | 可（需选商业许可） | AGPL 要求开源派生作品 | PDF.js (Apache 2.0)、Android PdfDocument (系统内置) |
| Android PdfDocument | Apache 2.0 | 系统内置 | 无 | — |
| Android PdfRenderer | Apache 2.0 | 系统内置 | 无 | — |
| pdf2docx | AGPL v3 | 可 | 同 PyMuPDF | 已弃用，不推荐 |
| python-docx | MIT | Flutter 端不直接使用 | — | 需 Flutter/Dart 替代库 |
| Pandoc | GPL v2+ | 包体过大 (≈200MB) | GPL 传染性 | 包装为独立进程或放弃 |
| docx2pdf | MIT | 移动端不可用（依赖 MS Office） | — | 不可移植 |
| iText (iText 7) | AGPL / 商业双轨 | 可 | AGPL 同 PyMuPDF | 商业许可成本 |
| pdfBox (Android) | Apache 2.0 | 可 | 无 | Java 接入需 JNI/平台通道 |

## 候选引擎详细分析

### MuPDF (PyMuPDF)

- **许可证**：GNU Affero General Public License v3（AGPL v3）或商业许可
- **AGPL 义务**：如果分发包含 MuPDF 的应用，必须提供完整源代码（包括你自己的代码）给所有用户。闭源商用必须购买商业许可。
- **移动端适配**：提供 Android 绑定（Java/Kotlin），Flutter 可通过 platform channel 调用
- **包体**：约 20-30 MB（各平台不同）
- **功能**：PDF 渲染、提取、转换（PDF ↔ 图片）、注释等
- **推荐方向**：MVP 阶段先用系统内置 API（PdfDocument/PdfRenderer）避免许可风险；高级功能需要 MuPDF 时购买商业许可

### Android PDF API（系统内置）

- **PdfDocument**（android.graphics.pdf）：创建 PDF，支持添加图片 → 适合「图片→PDF」
- **PdfRenderer**（android.graphics.pdf）：渲染 PDF 每页为 Bitmap → 适合「PDF→图片」
- **许可证**：Apache 2.0（Android 系统 API），无额外义务
- **限制**：不可用于 iOS，不可做 PDF→DOCX 或 PDF 编辑
- **推荐方向**：MVP 优先使用，零额外包体、零许可风险

### iText 7（Android SDK）

- **许可证**：AGPL v3 或商业许可
- **功能**：PDF 创建、编辑、提取、数字签名等，比 MuPDF 功能更全
- **包体**：约 10-15 MB
- **推荐方向**：如果需要 PDF 编辑/合并/水印等功能需要评估商业许可成本

## 面向 iOS 的注意

- MuPDF 提供 iOS CocoaPods 集成
- Apple PDFKit（系统内置）在 iOS 11+ 可用，可替代 MuPDF 基础功能
- PDFKit 许可证为 Apple 专有许可，随系统授权使用

## 建议行动

1. MVP 阶段优先使用 Android 系统 PDF API（PdfDocument / PdfRenderer）—— 零许可成本
2. 如果系统 API 无法满足需求（如加密 PDF、复杂编辑），再评估 MuPDF 商业许可或 iText
3. Pandoc 因包体过大（≈200MB）不建议移动端内置，考虑独立进程或服务端方案
4. 所有候选库的最终采用结论需在进入第 3 期前形成书面决定

> 免责声明：本文档仅供工程决策参考，不构成法律建议。许可证解释可能随版本变化，请以各项目官方许可文件为准。
