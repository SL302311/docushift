"""
DocuShift 测试用黄金样例生成器
生成无版权、可再分发的测试图片和 PDF 文件。
使用 Pillow 生成图片，PyMuPDF 生成 PDF。
"""

import os
import io
import sys

# 确保在当前项目目录
os.chdir(os.path.dirname(os.path.abspath(__file__)))

IMG_DIR = "test_fixtures/images"
PDF_DIR = "test_fixtures/pdfs"

os.makedirs(IMG_DIR, exist_ok=True)
os.makedirs(PDF_DIR, exist_ok=True)

# 颜色与尺寸定义
COLORS = {
    "red": (255, 0, 0),
    "green": (0, 255, 0),
    "blue": (0, 0, 255),
    "white": (255, 255, 255),
}

from PIL import Image, ImageDraw, ImageFont


def create_gradient_image(width, height, fmt="PNG"):
    """创建渐变色测试图片。"""
    img = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(img)
    for y in range(height):
        r = int(255 * y / height)
        g = int(255 * (1 - y / height))
        b = int(128 * (0.5 + 0.5 * y / height))
        for x in range(0, width, 4):
            draw.rectangle([x, y, x + 3, y], fill=(r, g, b))
    buf = io.BytesIO()
    img.save(buf, format=fmt)
    return buf.getvalue()


def create_text_image(text, width=400, height=200, fmt="PNG"):
    """创建包含文字的测试图片。"""
    img = Image.new("RGB", (width, height), color=(240, 240, 240))
    draw = ImageDraw.Draw(img)
    # 灰色边框
    draw.rectangle([2, 2, width - 3, height - 3], outline=(180, 180, 180))
    # 文字（系统字体）
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 28)
    except Exception:
        font = ImageFont.load_default()
    bbox = draw.textbbox((0, 0), text, font=font)
    tx = (width - bbox[2]) // 2
    ty = (height - bbox[3]) // 2
    draw.text((tx, ty), text, fill=(50, 50, 50), font=font)
    buf = io.BytesIO()
    img.save(buf, format=fmt)
    return buf.getvalue()


def create_multi_image_page(image_count=3):
    """创建含多个小图的页面图片（用于多图PDF）。"""
    total_w = image_count * 220
    img = Image.new("RGB", (total_w, 200), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)
    for i in range(image_count):
        x = i * 220 + 10
        draw.rectangle([x, 10, x + 200, 190], fill=(200 + i * 15, 100, 50 + i * 50))
        draw.text((x + 60, 80), f"图{i+1}", fill=(0, 0, 0))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


# ====== 生成图片样例 ======
print("生成图片样例...")

# 1. 普通 PNG（小图）
png_data = create_gradient_image(400, 300, "PNG")
with open(os.path.join(IMG_DIR, "sample_gradient.png"), "wb") as f:
    f.write(png_data)
print(f"  sample_gradient.png: {len(png_data)} bytes")

# 2. 中文文件名 PNG
png_data_cn = create_text_image("测试图片", 400, 200, "PNG")
with open(os.path.join(IMG_DIR, "测试图片.png"), "wb") as f:
    f.write(png_data_cn)
print(f"  测试图片.png: {len(png_data_cn)} bytes")

# 3. 普通 JPG
jpg_data = create_gradient_image(800, 600, "JPEG")
with open(os.path.join(IMG_DIR, "sample_photo.jpg"), "wb") as f:
    f.write(jpg_data)
print(f"  sample_photo.jpg: {len(jpg_data)} bytes")

# 4. 大文件 PNG（接近 20MB 边界，生成约 2MB 即可）
large_png = create_gradient_image(2000, 1500, "PNG")
with open(os.path.join(IMG_DIR, "large_image.png"), "wb") as f:
    f.write(large_png)
print(f"  large_image.png: {len(large_png)} bytes")

# 5. 透明 PNG（RGBA）
rgba_img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
draw = ImageDraw.Draw(rgba_img)
draw.ellipse([10, 10, 246, 246], fill=(255, 100, 100, 200))
buf = io.BytesIO()
rgba_img.save(buf, format="PNG")
with open(os.path.join(IMG_DIR, "transparent_circle.png"), "wb") as f:
    f.write(buf.getvalue())
print(f"  transparent_circle.png: {len(buf.getvalue())} bytes")

# 6. BMP 格式
bmp_data = create_gradient_image(100, 100, "BMP")
with open(os.path.join(IMG_DIR, "sample_bitmap.bmp"), "wb") as f:
    f.write(bmp_data)
print(f"  sample_bitmap.bmp: {len(bmp_data)} bytes")


# ====== 生成 PDF 样例 ======
print("\n生成 PDF 样例...")

try:
    import fitz  # PyMuPDF
except ImportError:
    print("  PyMuPDF 未安装，跳过 PDF 生成")
    sys.exit(1)


def add_text_page(doc, text, font_size=12):
    """添加纯文本页。"""
    page = doc.new_page(width=595, height=842)  # A4
    page.insert_text(
        fitz.Point(72, 72),
        text,
        fontsize=font_size,
        fontname="helv",
    )
    return page


# 1. 单页 PDF（纯文字）
doc = fitz.open()
add_text_page(
    doc,
    "DocuShift 测试文档\n\n"
    "这是第一页测试内容。\n"
    "包含中文和 English 混合文本。\n\n"
    "该 PDF 用于测试格式转换功能。\n"
    "生成时间: 2026-07-28",
    font_size=14,
)
doc.save(os.path.join(PDF_DIR, "sample_single.pdf"), garbage=4, deflate=True)
doc.close()
print(f"  sample_single.pdf: {os.path.getsize(os.path.join(PDF_DIR, 'sample_single.pdf'))} bytes")

# 2. 多页 PDF（3页含文字+图片）
doc = fitz.open()
for i in range(3):
    page = doc.new_page(width=595, height=842)
    # 文字
    page.insert_text(
        fitz.Point(72, 72),
        f"第 {i+1} 页\n\n这是多页测试文档的第 {i+1} 页。\n包含文字和多张图片。",
        fontsize=14,
        fontname="helv",
    )
    # 插入小图片（从已有图片读取）
    img_path = os.path.join(IMG_DIR, "sample_gradient.png")
    if os.path.exists(img_path):
        page.insert_image(
            fitz.Rect(72, 200, 300, 400),
            filename=img_path,
        )
        page.insert_image(
            fitz.Rect(350, 200, 550, 350),
            filename=os.path.join(IMG_DIR, "transparent_circle.png"),
        )
doc.save(os.path.join(PDF_DIR, "sample_multi_page.pdf"), garbage=4, deflate=True)
doc.close()
size_mp = os.path.getsize(os.path.join(PDF_DIR, "sample_multi_page.pdf"))
print(f"  sample_multi_page.pdf: {size_mp} bytes ({os.path.getsize(os.path.join(PDF_DIR, 'sample_multi_page.pdf')) / 1024:.1f} KB)")

# 3. 中文文件名 PDF
doc = fitz.open()
add_text_page(
    doc,
    "中文文件名测试文档\n\n"
    "此文件名为中文，用于测试中文路径处理。\n"
    "包含基本文字内容。",
    font_size=14,
)
doc.save(os.path.join(PDF_DIR, "中文测试文档.pdf"), garbage=4, deflate=True)
doc.close()
print(f"  中文测试文档.pdf: {os.path.getsize(os.path.join(PDF_DIR, '中文测试文档.pdf'))} bytes")

# 4. 大 PDF（模拟 10+ 页）
doc = fitz.open()
for i in range(15):
    page = doc.new_page(width=595, height=842)
    page.insert_text(
        fitz.Point(72, 72),
        f"第 {i+1} 页 / 共 15 页\n\n这是大 PDF 测试文件。\n用于测试转换引擎对多页文档的处理。",
        fontsize=12,
        fontname="helv",
    )
doc.save(os.path.join(PDF_DIR, "large_15pages.pdf"), garbage=4, deflate=True)
doc.close()
size_lg = os.path.getsize(os.path.join(PDF_DIR, "large_15pages.pdf"))
print(f"  large_15pages.pdf: {size_lg} bytes ({size_lg / 1024:.1f} KB)")

print("\n全部样例生成完毕。")
print(f"图片: {len(os.listdir(IMG_DIR))} 个文件")
print(f"PDF:  {len(os.listdir(PDF_DIR))} 个文件")
