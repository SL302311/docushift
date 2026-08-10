# DocuShift 测试用黄金样例

> 用途：格式转换的质量、性能、边界测试。
> 所有样例为自动生成，无版权，可自由再分发。

## 图片样例

| 文件名 | 格式 | 尺寸 | 大小 | 场景 |
|---|---|---|---|---|
| `sample_gradient.png` | PNG | 400×300 | 1.2 KB | 普通渐变图 |
| `测试图片.png` | PNG | 400×200 | 3.6 KB | 中文文件名 |
| `sample_photo.jpg` | JPEG | 800×600 | 12.5 KB | 普通照片 |
| `large_image.png` | PNG | 2000×1500 | 13.2 KB | 大尺寸图片 |
| `transparent_circle.png` | PNG (RGBA) | 256×256 | 1.7 KB | 透明背景 |
| `sample_bitmap.bmp` | BMP | 100×100 | 30 KB | BMP 位图 |

## PDF 样例

| 文件名 | 页数 | 大小 | 场景 |
|---|---|---|---|
| `sample_single.pdf` | 1 | 0.9 KB | 单页纯文字 |
| `sample_multi_page.pdf` | 3 | 8.4 KB | 多页含文字和嵌入图片 |
| `中文测试文档.pdf` | 1 | 0.8 KB | 中文文件名 |
| `large_15pages.pdf` | 15 | 5.6 KB | 多页文档（15页纯文字） |

## 覆盖场景矩阵

| 场景 | 图片覆盖 | PDF 覆盖 |
|---|---|---|
| 普通文件 | sample_gradient.png, sample_photo.jpg | sample_single.pdf |
| 中文文件名 | 测试图片.png | 中文测试文档.pdf |
| 多页 | — | sample_multi_page.pdf, large_15pages.pdf |
| 大尺寸/多页 | large_image.png | large_15pages.pdf |
| 透明/特殊格式 | transparent_circle.png, sample_bitmap.bmp | — |
| 损坏文件 | （手动截断或修改后补充） | （手动截断后补充） |

## 如何补充损坏文件测试

对任意样例执行以下命令生成损坏版本：

```bash
# 截断文件前 100 字节（损坏）
python -c "open('corrupted.pdf','wb').write(open('sample_single.pdf','rb').read()[:100])"
```

## 如何在基准测试中使用

参见 `docs/benchmark-results.md` 的记录模板。
