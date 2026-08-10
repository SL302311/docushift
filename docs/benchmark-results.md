# DocuShift 基准记录

> 版本：v0.2（第 2/10 期验收）
>
> 状态：第 2 期没有提交真实设备记录；下方仍是模板，不代表已测结果

## 单条记录格式

```yaml
# 引擎：MuPDF / PdfDocument / PdfRenderer / other
engine: "引擎名称"
format_pair: "输入格式 → 输出格式"

# 输入
input_file: "样例文件名"
input_size_bytes: 0
input_pages: null    # PDF 页数、图片张数等
input_dimensions: null  # 宽×高（图片）

# 输出
output_file: "输出文件名"
output_size_bytes: 0
output_pages: null

# 质量判定
quality_assessment: "pass / fail / partial"
quality_notes: ""

# 性能
duration_ms: 0       # 耗时（毫秒）
peak_memory_mb: 0    # 峰值内存（MB）
cpu_usage_pct: null  # CPU 使用率（可选）

# 环境
device_model: ""
android_version: ""
flutter_version: ""

# 异常
error: null           # 如果有错误/异常，记录消息
```

## 样例覆盖要求

每个格式对至少覆盖以下 5 种场景：

| # | 场景 | 说明 |
|---|---|---|
| 1 | 普通文件 | 标准中文文件名、正常内容 |
| 2 | 中文文件名 | 含中文字符的文件名 |
| 3 | 特殊字符 | 含空格、括号、Unicode 的文件名 |
| 4 | 边界文件 | 超大文件、超多页、极高分辨率 |
| 5 | 损坏文件 | 内容损坏或不完整的文件 |

## 示例格式（非测试结果）

```yaml
engine: "MuPDF (fitz)"
format_pair: "PNG → PDF"
input_file: "sample_photo.png"
input_size_bytes: 2457600
input_pages: 1
input_dimensions: "1920×1080"
output_file: "sample_photo.pdf"
output_size_bytes: 51200
output_pages: 1
quality_assessment: "pass"
quality_notes: "色彩还原好，页面尺寸正确"
duration_ms: 350
peak_memory_mb: 45
device_model: "Xiaomi 14"
android_version: "15"
flutter_version: "3.44.2"
error: null
```

> 第 2 期返工必须把真实记录和设备证据写入 `artifacts/phase-2/`，再在本文汇总；不得把本示例作为完成证据。
