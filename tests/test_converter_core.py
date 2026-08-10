"""
DocuShift 转换引擎核心逻辑测试（无需 GUI）。
覆盖：路由表、格式探测、错误路径、缺失依赖探测。
"""

import os
import sys
import tempfile
import pytest

# 确保 core 包可导入
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from core.converter import (
    SUPPORTED_FORMATS,
    FORMAT_LABELS,
    OUTPUT_EXTENSIONS,
    get_output_formats,
    DocumentConverter,
    ConversionError,
)


class TestRouteTable:
    """SUPPORTED_FORMATS 路由表完整性测试。"""

    def test_all_output_formats_have_labels(self):
        """每个输出格式都有对应的标签。"""
        all_outputs = set()
        for outputs in SUPPORTED_FORMATS.values():
            all_outputs.update(outputs)
        for fmt in all_outputs:
            assert fmt in FORMAT_LABELS, f"格式 {fmt} 缺少 FORMAT_LABELS"

    def test_all_output_formats_have_extensions(self):
        """每个输出格式都有扩展名映射。"""
        all_outputs = set()
        for outputs in SUPPORTED_FORMATS.values():
            all_outputs.update(outputs)
        for fmt in all_outputs:
            assert fmt in OUTPUT_EXTENSIONS, f"格式 {fmt} 缺少 OUTPUT_EXTENSIONS"

    def test_pdf_routes(self):
        assert SUPPORTED_FORMATS[".pdf"] == ["docx", "png", "jpg"]

    def test_docx_routes(self):
        assert SUPPORTED_FORMATS[".docx"] == ["pdf"]

    def test_doc_routes(self):
        assert SUPPORTED_FORMATS[".doc"] == ["pdf"]

    def test_md_routes(self):
        assert sorted(SUPPORTED_FORMATS[".md"]) == ["docx", "html", "pdf"]

    def test_html_routes(self):
        assert sorted(SUPPORTED_FORMATS[".html"]) == ["docx", "pdf"]

    def test_image_routes(self):
        for ext in (".png", ".jpg", ".jpeg", ".bmp", ".tiff"):
            assert SUPPORTED_FORMATS[ext] == ["pdf"], f"{ext} 应只支持转 PDF"

    def test_unsupported_input_returns_empty(self):
        assert get_output_formats("test.xyz") == []
        assert get_output_formats("test.mp4") == []
        assert get_output_formats("test") == []


class TestGetOutputFormats:
    """get_output_formats 函数测试。"""

    def test_pdf_to_docx(self):
        assert "docx" in get_output_formats("report.pdf")

    def test_pdf_to_png(self):
        assert "png" in get_output_formats("report.pdf")

    def test_pdf_to_jpg(self):
        assert "jpg" in get_output_formats("report.pdf")

    def test_docx_to_pdf(self):
        assert get_output_formats("letter.docx") == ["pdf"]

    def test_md_to_multiple(self):
        fmts = get_output_formats("readme.md")
        assert "html" in fmts
        assert "docx" in fmts
        assert "pdf" in fmts

    def test_case_insensitive(self):
        fmts_upper = get_output_formats("DOCUMENT.PDF")
        fmts_lower = get_output_formats("document.pdf")
        assert fmts_upper == fmts_lower


class TestConversionError:
    """ConversionError 异常测试。"""

    def test_conversion_error_is_exception(self):
        assert issubclass(ConversionError, Exception)

    def test_conversion_error_message(self):
        err = ConversionError("测试错误")
        assert str(err) == "测试错误"


class TestDocumentConverter:
    """DocumentConverter 实例方法测试（静态/纯逻辑部分）。"""

    def test_get_missing_deps_returns_dict(self):
        """get_missing_deps 应返回 dict（可能为空）。"""
        deps = DocumentConverter.get_missing_deps()
        assert isinstance(deps, dict)

    def test_convert_raises_on_nonexistent_file(self):
        converter = DocumentConverter()
        with pytest.raises(ConversionError, match="文件不存在"):
            converter.convert("no_such_file.pdf", "docx")

    def test_convert_raises_on_unsupported_extension(self):
        converter = DocumentConverter()
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = os.path.join(tmpdir, "test.xyz")
            with open(tmp_path, "w") as f:
                f.write("test")
            with pytest.raises(ConversionError, match="不支持的输入格式"):
                converter.convert(tmp_path, "docx")

    def test_convert_raises_on_invalid_format(self):
        converter = DocumentConverter()
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = os.path.join(tmpdir, "test.pdf")
            with open(tmp_path, "w") as f:
                f.write("test")
            with pytest.raises(ConversionError, match="不支持"):
                converter.convert(tmp_path, "mp4")

    def test_get_output_path_structure(self):
        """验证 _get_output_path 输出路径结构。"""
        converter = DocumentConverter()
        with tempfile.TemporaryDirectory() as tmpdir:
            input_path = os.path.join(tmpdir, "report.pdf")
            with open(input_path, "w") as f:
                f.write("test")
            result = converter._get_output_path(input_path, ".docx")
            assert result.endswith(".docx")
            assert "DocuShift_Output" in result
            assert "report" in result

    def test_get_output_path_avoids_overwrite(self):
        """同名文件存在时自动追加序号避免覆盖。"""
        converter = DocumentConverter()
        with tempfile.TemporaryDirectory() as tmpdir:
            input_path = os.path.join(tmpdir, "report.pdf")
            with open(input_path, "w") as f:
                f.write("test")
            # 第一调用：返回 report.docx（新建，文件不存在）
            first = converter._get_output_path(input_path, ".docx")
            assert first.endswith("report.docx")
            # 创建文件模拟已有输出
            open(first, "w").close()
            # 第二调用：应返回 report_1.docx（因为 report.docx 已存在）
            second = converter._get_output_path(input_path, ".docx")
            assert second.endswith("report_1.docx")
            # 创建文件模拟已有输出
            open(second, "w").close()
            # 第三调用：应返回 report_2.docx
            third = converter._get_output_path(input_path, ".docx")
            assert third.endswith("report_2.docx")
            # 不同扩展名，report.png 不存在 → 直接返回
            first_png = converter._get_output_path(input_path, ".png")
            assert first_png.endswith("report.png")
