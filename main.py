#!/usr/bin/env python3
"""
DocuShift — 本地文档格式流转中心
=================================
SL B&W Minimalist Edition

架构:
  • UI 框架:  CustomTkinter + tkinterdnd2 (无边框拖拽窗口)
  • 转换引擎: core/converter.py (pdf2docx / PyMuPDF / pypandoc / docx2pdf)
  • 多线程:   所有转换在 threading.Thread 中执行，UI 线程仅更新进度

使用:
  python main.py              # 直接运行
  build.bat                   # PyInstaller 打包为 EXE
"""

from __future__ import annotations

import os
import sys
import threading
import subprocess
import tkinter as tk
import ctypes
from tkinter import filedialog, messagebox

import customtkinter as ctk
from tkinterdnd2 import TkinterDnD, DND_FILES

# 确保 core 包可导入
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from core.converter import (
    DocumentConverter,
    get_output_formats,
    FORMAT_LABELS,
    SUPPORTED_FORMATS,
)

# ===========================================================================
# SL B&W Minimalist 色板
# ===========================================================================
C_BG          = "#0F0F0F"   # 窗口底色
C_BG_LIGHT    = "#1A1A1A"   # 拖放区底色
C_CARD        = "#1E1E1E"   # 控件底色
C_BORDER      = "#333333"   # 虚线边框 / 分割线
C_TEXT        = "#E0E0E0"   # 主文字
C_TEXT_DIM    = "#888888"   # 次要文字
C_TEXT_FAINT  = "#555555"   # 弱化文字
C_HOVER       = "#2A2A2A"   # 悬停反馈
C_DANGER      = "#E81123"   # 关闭按钮悬停红
C_WATERMARK   = "#333333"   # 水印色
C_SUCCESS     = "#4CAF50"   # 成功绿（极简灰底中的唯一亮色）
C_ERROR       = "#EF4444"   # 错误红

WINDOW_W = 600
WINDOW_H = 400


# ===========================================================================
# DnD 窗口基类 — CustomTkinter + tkinterdnd2 混合
# ===========================================================================

class DnDCTk(ctk.CTk, TkinterDnD.DnDWrapper):
    """CustomTkinter 根窗口，混入 tkinterdnd2 拖放能力。"""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.TkdndVersion = TkinterDnD._require(self)


# ===========================================================================
# 主应用
# ===========================================================================

class DocuShiftApp:
    """DocuShift 主控制器。"""

    def __init__(self) -> None:
        self.window = DnDCTk()
        self.converter = DocumentConverter()
        self.dropped_files: list[str] = []
        self.selected_format: str = ""
        self.is_converting: bool = False
        self._drag_active: bool = False
        self._drag_x: int = 0
        self._drag_y: int = 0
        self._canvas_items: list[int] = []

        self._setup_window()
        self._build_title_bar()
        self._build_drop_zone()
        self._build_controls()
        self._build_progress()
        self._build_watermark()

        # 检查依赖
        missing = self.converter.get_missing_deps()
        if missing:
            self._set_status(
                f"部分库缺失: {', '.join(missing.keys())}（对应转换将不可用）",
                color=C_TEXT_DIM,
            )

        self.window.mainloop()

    # ------------------------------------------------------------------ 窗口

    def _setup_window(self) -> None:
        self.window.title("DocuShift")
        self.window.geometry(f"{WINDOW_W}x{WINDOW_H}")
        self.window.overrideredirect(True)        # 无边框
        self.window.configure(fg_color=C_BG)
        self.window.resizable(False, False)
        self.window.attributes("-topmost", True)

        # ---- Windows 任务栏支持 ----
        # overrideredirect 会把窗口设为 WS_EX_TOOLWINDOW（不在任务栏显示）
        # 需要用 ctypes 改为 WS_EX_APPWINDOW 让它出现在任务栏
        self._enable_taskbar_icon()

        # 居中
        self.window.update_idletasks()
        sw = self.window.winfo_screenwidth()
        sh = self.window.winfo_screenheight()
        x = (sw - WINDOW_W) // 2
        y = (sh - WINDOW_H) // 2
        self.window.geometry(f"{WINDOW_W}x{WINDOW_H}+{x}+{y}")

        # 短暂置顶后取消，避免遮挡其他窗口
        self.window.after(200, lambda: self.window.attributes("-topmost", False))

        # 窗口从最小化恢复时重新启用无边框 + 任务栏
        self.window.bind("<Map>", self._on_window_map)

        ctk.set_appearance_mode("dark")

    def _enable_taskbar_icon(self) -> None:
        """让无边框窗口出现在 Windows 任务栏中。"""
        if sys.platform != "win32":
            return
        try:
            self.window.update_idletasks()
            # 获取真正的 Win32 窗口句柄
            hwnd = ctypes.windll.user32.GetParent(self.window.winfo_id())
            if not hwnd:
                hwnd = self.window.winfo_id()

            GWL_EXSTYLE  = -20
            WS_EX_TOOLWINDOW = 0x00000080
            WS_EX_APPWINDOW  = 0x00040000

            # 读取当前扩展样式
            ex_style = ctypes.windll.user32.GetWindowLongPtrW(hwnd, GWL_EXSTYLE)
            # 去掉 TOOLWINDOW，加上 APPWINDOW
            ex_style = (ex_style & ~WS_EX_TOOLWINDOW) | WS_EX_APPWINDOW
            ctypes.windll.user32.SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex_style)

            # 刷新窗口框架使更改生效
            SWP_NOMOVE    = 0x0002
            SWP_NOSIZE    = 0x0001
            SWP_NOZORDER  = 0x0004
            SWP_FRAMECHANGE = 0x0020
            ctypes.windll.user32.SetWindowPos(
                hwnd, 0, 0, 0, 0, 0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGE
            )
        except Exception:
            pass  # 非 Windows 或权限不足时静默跳过

    # ------------------------------------------------------------------ 标题栏

    def _build_title_bar(self) -> None:
        bar = ctk.CTkFrame(self.window, fg_color=C_BG, height=36, corner_radius=0)
        bar.pack(fill="x", side="top")
        bar.pack_propagate(False)

        # 标题
        title = ctk.CTkLabel(
            bar, text="DocuShift",
            font=ctk.CTkFont(family="Segoe UI", size=14, weight="bold"),
            text_color=C_TEXT_DIM,
        )
        title.pack(side="left", padx=16)

        # 版本标签
        ver = ctk.CTkLabel(
            bar, text="v1.0",
            font=ctk.CTkFont(family="Segoe UI", size=10),
            text_color=C_TEXT_FAINT,
        )
        ver.pack(side="left", padx=(0, 0))

        # 窗口拖拽
        for w in (bar, title, ver):
            w.bind("<Button-1>", self._start_drag)
            w.bind("<B1-Motion>", self._on_drag)

        # 最小化按钮
        btn_min = ctk.CTkButton(
            bar, text="—", width=36, height=36,
            fg_color=C_BG, hover_color=C_HOVER,
            text_color=C_TEXT_DIM,
            font=ctk.CTkFont(size=14),
            corner_radius=0,
            command=self._minimize,
        )
        btn_min.pack(side="right")

        # 关闭按钮
        btn_close = ctk.CTkButton(
            bar, text="✕", width=36, height=36,
            fg_color=C_BG, hover_color=C_DANGER,
            text_color=C_TEXT_DIM,
            font=ctk.CTkFont(size=13),
            corner_radius=0,
            command=self._on_close,
        )
        btn_close.pack(side="right")

    def _start_drag(self, event: tk.Event) -> None:
        self._drag_x = event.x
        self._drag_y = event.y

    def _on_drag(self, event: tk.Event) -> None:
        x = self.window.winfo_x() + event.x - self._drag_x
        y = self.window.winfo_y() + event.y - self._drag_y
        self.window.geometry(f"+{x}+{y}")

    def _on_close(self) -> None:
        if self.is_converting:
            if not messagebox.askyesno(
                "DocuShift", "转换正在进行中，确定退出？",
                parent=self.window,
            ):
                return
        self.window.destroy()

    def _minimize(self) -> None:
        """无边框窗口最小化到任务栏：临时取消 overrideredirect 以支持 iconify。"""
        self.window.overrideredirect(False)
        self.window.iconify()

    def _on_window_map(self, event: tk.Event) -> None:
        """窗口从最小化恢复时重新启用无边框模式 + 任务栏图标。"""
        if self.window.state() == "normal":
            self.window.overrideredirect(True)
            self._enable_taskbar_icon()

    # ------------------------------------------------------------------ 拖放区

    def _build_drop_zone(self) -> None:
        """构建占屏幕 70% 的虚线拖放区 + 结果列表区（互斥切换）。"""
        # 容器框架
        self.drop_container = ctk.CTkFrame(self.window, fg_color=C_BG, corner_radius=0)
        self.drop_container.pack(fill="both", expand=True)
        self.drop_container.pack_propagate(False)

        # ---- 结果列表区（默认隐藏，转换完成后显示）----
        self.results_frame = ctk.CTkScrollableFrame(
            self.drop_container, fg_color=C_BG, corner_radius=0,
            scrollbar_button_color=C_CARD, scrollbar_button_hover_color=C_HOVER,
        )
        # 不 pack，转换完成时才显示

        # ---- Canvas 拖放区 ----
        self.drop_canvas = tk.Canvas(
            self.drop_container,
            bg=C_BG,
            highlightthickness=0,
            bd=0,
        )
        self.drop_canvas.pack(fill="both", expand=True, padx=30, pady=(5, 5))

        # 绑定拖放
        self.drop_canvas.drop_target_register(DND_FILES)
        self.drop_canvas.dnd_bind("<<Drop>>", self._on_drop)
        self.drop_canvas.dnd_bind("<<DragEnter>>", self._on_drag_enter)
        self.drop_canvas.dnd_bind("<<DragLeave>>", self._on_drag_leave)

        # 点击选择文件
        self.drop_canvas.bind("<Button-1>", lambda e: self._select_files())

        # 窗口渲染后延迟绘制（此时 winfo_width 才返回真实尺寸）
        self.drop_canvas.bind("<Configure>", self._on_canvas_configure)
        self.window.after(100, self._draw_idle_state)

    def _clear_canvas(self) -> None:
        for item in self._canvas_items:
            self.drop_canvas.delete(item)
        self._canvas_items.clear()

    def _on_canvas_configure(self, event: tk.Event) -> None:
        """Canvas 尺寸变化时重绘当前状态。"""
        if self._drag_active:
            self._draw_drag_active()
        elif self.dropped_files:
            self._draw_file_loaded()
        else:
            self._draw_idle_state()

    def _draw_dashed_rect(self, color: str = C_BORDER) -> int:
        """在 Canvas 上绘制虚线矩形边框。"""
        self.drop_canvas.update_idletasks()
        w = self.drop_canvas.winfo_width()
        h = self.drop_canvas.winfo_height()
        if w <= 1:
            w = 540
        if h <= 1:
            h = 230
        item = self.drop_canvas.create_rectangle(
            4, 4, w - 4, h - 4,
            outline=color,
            dash=(10, 6),
            width=2,
        )
        self._canvas_items.append(item)
        return item

    def _draw_idle_state(self) -> None:
        """绘制空闲态：拖放提示。"""
        self._clear_canvas()
        self._draw_dashed_rect()

        cx = self.drop_canvas.winfo_width() // 2 or 270
        cy = self.drop_canvas.winfo_height() // 2 or 115

        # 大箭头图标（用 Unicode）
        arrow = self.drop_canvas.create_text(
            cx, cy - 35,
            text="\u2B07",   # ⬇
            fill=C_TEXT_FAINT,
            font=("Segoe UI", 28),
        )
        self._canvas_items.append(arrow)

        # 主提示文字
        main_text = self.drop_canvas.create_text(
            cx, cy + 10,
            text="拖放文件至此",
            fill=C_TEXT_DIM,
            font=("Segoe UI", 16, "bold"),
        )
        self._canvas_items.append(main_text)

        # 支持格式
        sub_text = self.drop_canvas.create_text(
            cx, cy + 42,
            text="或点击选择文件",
            fill=C_TEXT_FAINT,
            font=("Segoe UI", 11),
        )
        self._canvas_items.append(sub_text)

        # 支持格式列表
        fmt_text = self.drop_canvas.create_text(
            cx, cy + 68,
            text="PDF  ·  DOCX  ·  Markdown  ·  HTML  ·  PNG/JPG",
            fill=C_TEXT_FAINT,
            font=("Segoe UI", 10),
        )
        self._canvas_items.append(fmt_text)

    def _draw_drag_active(self) -> None:
        """绘制拖入态：高亮边框。"""
        self._clear_canvas()
        self._draw_dashed_rect(color=C_TEXT)

        cx = self.drop_canvas.winfo_width() // 2 or 270
        cy = self.drop_canvas.winfo_height() // 2 or 115

        item = self.drop_canvas.create_text(
            cx, cy,
            text="松开以添加文件",
            fill=C_TEXT,
            font=("Segoe UI", 16, "bold"),
        )
        self._canvas_items.append(item)

    def _draw_file_loaded(self) -> None:
        """绘制文件已加载态：显示文件名与格式。"""
        self._clear_canvas()
        self._draw_dashed_rect(color=C_BORDER)

        cx = self.drop_canvas.winfo_width() // 2 or 270
        cy = self.drop_canvas.winfo_height() // 2 or 115

        if len(self.dropped_files) == 1:
            fpath = self.dropped_files[0]
            fname = os.path.basename(fpath)
            ext = os.path.splitext(fpath)[1].lower()

            # 文件图标
            icon = self.drop_canvas.create_text(
                cx, cy - 45,
                text="\U0001F4C4",   # 📄
                font=("Segoe UI", 32),
            )
            self._canvas_items.append(icon)

            # 文件名
            name_text = self.drop_canvas.create_text(
                cx, cy,
                text=fname if len(fname) <= 40 else fname[:37] + "...",
                fill=C_TEXT,
                font=("Segoe UI", 14, "bold"),
            )
            self._canvas_items.append(name_text)

            # 格式信息
            fmt_str = f"{ext}  →  可选: {', '.join(get_output_formats(fpath))}"
            info = self.drop_canvas.create_text(
                cx, cy + 30,
                text=fmt_str,
                fill=C_TEXT_DIM,
                font=("Segoe UI", 11),
            )
            self._canvas_items.append(info)

        else:
            # 多文件
            icon = self.drop_canvas.create_text(
                cx, cy - 35,
                text=f"\U0001F4C1",   # 📁
                font=("Segoe UI", 28),
            )
            self._canvas_items.append(icon)

            count_text = self.drop_canvas.create_text(
                cx, cy + 5,
                text=f"已选择 {len(self.dropped_files)} 个文件",
                fill=C_TEXT,
                font=("Segoe UI", 14, "bold"),
            )
            self._canvas_items.append(count_text)

            # 列出前几个文件名
            names = [os.path.basename(f) for f in self.dropped_files[:3]]
            display = "  ·  ".join(names)
            if len(self.dropped_files) > 3:
                display += f"  ·  等 {len(self.dropped_files)} 个"
            if len(display) > 50:
                display = display[:47] + "..."

            files_text = self.drop_canvas.create_text(
                cx, cy + 35,
                text=display,
                fill=C_TEXT_DIM,
                font=("Segoe UI", 10),
            )
            self._canvas_items.append(files_text)

        # 提示重新拖放
        hint = self.drop_canvas.create_text(
            cx, cy + 65,
            text="拖放新文件可替换",
            fill=C_TEXT_FAINT,
            font=("Segoe UI", 9),
        )
        self._canvas_items.append(hint)

    # ------------------------------------------------------------------ DnD 事件

    def _on_drop(self, event: tk.Event) -> None:
        """文件拖放完成。"""
        self._drag_active = False
        raw = event.data
        # tk.splitlist 正确处理含空格路径的花括号包裹
        files = list(self.window.tk.splitlist(raw))
        files = [f.strip("{}") for f in files if f]
        files = [f for f in files if os.path.isfile(f)]

        if not files:
            return

        self.dropped_files = files
        self._draw_file_loaded()
        self._update_format_selector()
        self._set_status("已就绪，选择目标格式后点击转换", C_TEXT_DIM)
        self.progress_bar.set(0)

    def _on_drag_enter(self, event: tk.Event) -> None:
        if not self._drag_active:
            self._drag_active = True
            self._draw_drag_active()

    def _on_drag_leave(self, event: tk.Event) -> None:
        if self._drag_active:
            self._drag_active = False
            if self.dropped_files:
                self._draw_file_loaded()
            else:
                self._draw_idle_state()

    def _select_files(self) -> None:
        """通过文件对话框选择文件（DnD 备选方案）。"""
        types = [
            ("文档文件", "*.pdf *.docx *.doc *.md *.markdown *.html *.htm *.png *.jpg *.jpeg *.bmp *.tiff"),
            ("所有文件", "*.*"),
        ]
        files = filedialog.askopenfilenames(
            title="选择要转换的文件",
            filetypes=types,
            parent=self.window,
        )
        if files:
            self.dropped_files = list(files)
            self._draw_file_loaded()
            self._update_format_selector()
            self._set_status("已就绪，选择目标格式后点击转换", C_TEXT_DIM)
            self.progress_bar.set(0)

    # ------------------------------------------------------------------ 控制区

    def _build_controls(self) -> None:
        """构建输出格式选择器与转换按钮。"""
        self.controls_frame = ctk.CTkFrame(
            self.window, fg_color=C_BG, height=42, corner_radius=0
        )
        self.controls_frame.pack(fill="x", side="bottom", padx=0, pady=0)
        self.controls_frame.pack_propagate(False)

        # 输出格式下拉
        self.format_menu = ctk.CTkOptionMenu(
            self.controls_frame,
            values=["—"],
            width=160, height=32,
            fg_color=C_CARD,
            button_color=C_CARD,
            button_hover_color=C_HOVER,
            text_color=C_TEXT,
            dropdown_fg_color=C_CARD,
            dropdown_hover_color=C_HOVER,
            dropdown_text_color=C_TEXT,
            font=ctk.CTkFont(family="Segoe UI", size=12),
            dropdown_font=ctk.CTkFont(family="Segoe UI", size=12),
            command=self._on_format_change,
        )
        self.format_menu.set("选择格式")
        self.format_menu.pack(side="left", padx=(40, 12), pady=5)
        self.format_menu.configure(state="disabled")

        # 转换按钮
        self.convert_btn = ctk.CTkButton(
            self.controls_frame,
            text="开始转换",
            width=110, height=32,
            fg_color=C_CARD,
            hover_color=C_HOVER,
            text_color=C_TEXT,
            border_width=1,
            border_color=C_BORDER,
            font=ctk.CTkFont(family="Segoe UI", size=12, weight="bold"),
            corner_radius=6,
            command=self._start_conversion,
            state="disabled",
        )
        self.convert_btn.pack(side="left", padx=4, pady=5)

        # 打开输出目录按钮（转换完成后显示）
        self.open_dir_btn = ctk.CTkButton(
            self.controls_frame,
            text="打开输出目录",
            width=120, height=32,
            fg_color="transparent",
            hover_color=C_HOVER,
            text_color=C_TEXT_DIM,
            border_width=1,
            border_color=C_BORDER,
            font=ctk.CTkFont(family="Segoe UI", size=12),
            corner_radius=6,
            command=self._open_output_dir,
        )
        # 不初始 pack，转换完成后才显示

        # 重新选择按钮
        self.reset_btn = ctk.CTkButton(
            self.controls_frame,
            text="重新选择",
            width=90, height=32,
            fg_color="transparent",
            hover_color=C_HOVER,
            text_color=C_TEXT_DIM,
            font=ctk.CTkFont(family="Segoe UI", size=12),
            corner_radius=6,
            command=self._reset,
        )

    def _update_format_selector(self) -> None:
        """根据已加载文件更新输出格式选项。"""
        if not self.dropped_files:
            return

        # 取所有文件的可用输出格式交集
        all_formats: set[str] | None = None
        for f in self.dropped_files:
            fmts = set(get_output_formats(f))
            if all_formats is None:
                all_formats = fmts
            else:
                all_formats &= fmts

        if not all_formats:
            self._set_status("文件格式不受支持", C_ERROR)
            return

        formats = sorted(all_formats)
        labels = [FORMAT_LABELS.get(f, f) for f in formats]
        self.format_menu.configure(values=labels, state="normal")
        self.format_menu.set(labels[0])
        self.selected_format = formats[0]

        self.convert_btn.configure(state="normal")

        # 如果只有一个格式选项，可以直接转换
        if len(formats) == 1 and len(self.dropped_files) == 1:
            self._set_status(f"可直接点击「开始转换」", C_TEXT_DIM)

    def _on_format_change(self, choice: str) -> None:
        """下拉选择回调。"""
        # 从 label 反查 format key
        for key, label in FORMAT_LABELS.items():
            if label == choice:
                self.selected_format = key
                return
        # 如果没匹配到 label，直接用原始值
        self.selected_format = choice.lower()

    # ------------------------------------------------------------------ 进度区

    def _build_progress(self) -> None:
        """构建进度条与状态文字。"""
        self.progress_frame = ctk.CTkFrame(
            self.window, fg_color=C_BG, height=36, corner_radius=0
        )
        self.progress_frame.pack(fill="x", side="bottom")
        self.progress_frame.pack_propagate(False)

        self.progress_bar = ctk.CTkProgressBar(
            self.progress_frame,
            progress_color=C_TEXT,
            fg_color=C_CARD,
            height=6,
            corner_radius=3,
        )
        self.progress_bar.set(0)
        self.progress_bar.pack(fill="x", padx=40, pady=(8, 2))

        self.status_label = ctk.CTkLabel(
            self.progress_frame,
            text="就绪",
            font=ctk.CTkFont(family="Segoe UI", size=11),
            text_color=C_TEXT_DIM,
        )
        self.status_label.pack(side="left", padx=40, pady=(0, 4))

    def _set_status(self, text: str, color: str = C_TEXT_DIM) -> None:
        self.status_label.configure(text=text, text_color=color)

    # ------------------------------------------------------------------ 水印

    def _build_watermark(self) -> None:
        """右下角固定水印 © SL。"""
        wm = ctk.CTkLabel(
            self.progress_frame,
            text="© SL",
            font=ctk.CTkFont(family="Segoe UI", size=10),
            text_color=C_WATERMARK,
        )
        wm.pack(side="right", padx=40, pady=(0, 4))

    # ------------------------------------------------------------------ 转换流程

    def _start_conversion(self) -> None:
        """启动转换（多线程）。"""
        if self.is_converting or not self.dropped_files:
            return
        if not self.selected_format:
            self._set_status("请先选择输出格式", C_ERROR)
            return

        self.is_converting = True
        self.convert_btn.configure(state="disabled", text="转换中…")
        self.format_menu.configure(state="disabled")
        self.progress_bar.set(0)
        self.progress_bar.configure(progress_color=C_TEXT)
        self._set_status("正在转换…", C_TEXT)

        thread = threading.Thread(
            target=self._conversion_worker,
            daemon=True,
        )
        thread.start()

    def _conversion_worker(self) -> None:
        """后台线程：逐个文件执行转换。"""
        total = len(self.dropped_files)
        output_dirs: set[str] = set()
        output_files: list[str] = []   # 所有成功输出的文件路径
        errors: list[str] = []

        for idx, fpath in enumerate(self.dropped_files):
            file_label = os.path.basename(fpath)
            if len(file_label) > 30:
                file_label = file_label[:27] + "..."

            self._post_status(
                f"[{idx + 1}/{total}] {file_label}  正在转换…", C_TEXT
            )

            def progress_cb(pct: int, msg: str, _idx=idx, _total=total, _label=file_label):
                # 整体进度 = 已完成文件占比 + 当前文件进度占比
                overall = (_idx / _total) + (pct / 100.0) / _total
                self.window.after(0, lambda v=overall: self.progress_bar.set(v))
                self._post_status(
                    f"[{_idx + 1}/{_total}] {_label}  {msg}", C_TEXT
                )

            try:
                results = self.converter.convert(
                    fpath, self.selected_format, progress_cb
                )
                for r in results:
                    output_dirs.add(os.path.dirname(os.path.abspath(r)))
                    output_files.append(r)
                self._post_status(
                    f"[{idx + 1}/{total}] {file_label}  完成", C_SUCCESS
                )
            except Exception as e:
                err_msg = str(e).split("\n")[0][:80]
                errors.append(f"{file_label}: {err_msg}")
                self._post_status(
                    f"[{idx + 1}/{total}] {file_label}  失败: {err_msg}",
                    C_ERROR,
                )

        # 保存输出信息供 UI 使用
        self._last_output_dirs = output_dirs
        self._last_output_files = output_files

        # 完成 — 在主线程中刷新 UI
        self.window.after(0, lambda: self._on_conversion_done(total, errors))

    def _on_conversion_done(self, total: int, errors: list[str]) -> None:
        self.is_converting = False
        self.progress_bar.set(1.0)
        self.convert_btn.configure(state="normal", text="开始转换")
        self.format_menu.configure(state="normal")

        success_count = total - len(errors)
        if not errors:
            self.progress_bar.configure(progress_color=C_SUCCESS)
            self._set_status(
                f"全部转换完成（{success_count}/{total}）", C_SUCCESS
            )
        elif success_count > 0:
            self.progress_bar.configure(progress_color=C_TEXT)
            self._set_status(
                f"完成 {success_count}/{total}，{len(errors)} 个失败", C_TEXT_DIM
            )
        else:
            self.progress_bar.configure(progress_color=C_ERROR)
            self._set_status("转换失败", C_ERROR)

        # 显示结果文件列表
        if hasattr(self, "_last_output_files") and self._last_output_files:
            self._show_results_list(self._last_output_files)

        # 显示"打开输出目录"和"重新选择"按钮
        if hasattr(self, "_last_output_dirs") and self._last_output_dirs:
            self.open_dir_btn.pack(side="left", padx=4, pady=5)
        self.reset_btn.pack(side="left", padx=4, pady=5)

    def _show_results_list(self, files: list[str]) -> None:
        """切换到结果列表视图：隐藏 Canvas，显示文件列表。"""
        # 隐藏拖放区 Canvas
        self.drop_canvas.pack_forget()
        # 显示结果列表
        self.results_frame.pack(fill="both", expand=True, padx=30, pady=(5, 5))

        # 清空旧内容
        for child in self.results_frame.winfo_children():
            child.destroy()

        # 标题行
        header = ctk.CTkLabel(
            self.results_frame,
            text=f"转换完成 · {len(files)} 个文件",
            font=ctk.CTkFont(family="Segoe UI", size=14, weight="bold"),
            text_color=C_SUCCESS,
        )
        header.pack(anchor="w", padx=12, pady=(12, 8))

        # 逐个文件行
        for fpath in files:
            row = ctk.CTkFrame(
                self.results_frame, fg_color=C_CARD, corner_radius=6,
            )
            row.pack(fill="x", padx=4, pady=3)

            fname = os.path.basename(fpath)
            fdir = os.path.dirname(os.path.abspath(fpath))
            fsize = os.path.getsize(fpath) if os.path.exists(fpath) else 0
            size_str = f"{fsize // 1024}KB" if fsize < 1048576 else f"{fsize / 1048576:.1f}MB"

            # 文件名（可点击打开）
            name_label = ctk.CTkLabel(
                row, text=fname,
                font=ctk.CTkFont(family="Segoe UI", size=12, weight="bold"),
                text_color=C_TEXT,
                cursor="hand2",
            )
            name_label.pack(side="left", padx=(10, 4), pady=6)
            # 点击文件名 → 用默认程序打开
            name_label.bind("<Button-1>", lambda e, p=fpath: self._open_file(p))

            size_label = ctk.CTkLabel(
                row, text=size_str,
                font=ctk.CTkFont(family="Segoe UI", size=10),
                text_color=C_TEXT_FAINT,
            )
            size_label.pack(side="left", padx=(0, 8), pady=6)

            # 路径（灰色小字，截断过长路径，可点击定位）
            display_dir = fdir if len(fdir) <= 40 else "..." + fdir[-37:]
            path_label = ctk.CTkLabel(
                row, text=display_dir,
                font=ctk.CTkFont(family="Segoe UI", size=10),
                text_color=C_TEXT_DIM,
                cursor="hand2",
            )
            path_label.pack(side="left", fill="x", expand=True, pady=6)
            path_label.bind("<Button-1>", lambda e, p=fpath: self._locate_file(p))

            # "定位"按钮 — 在资源管理器中选中文件
            loc_btn = ctk.CTkButton(
                row, text="\U0001F4C2 定位", width=72, height=28,
                fg_color=C_CARD, hover_color=C_HOVER,
                text_color=C_TEXT_DIM,
                border_width=1, border_color=C_BORDER,
                font=ctk.CTkFont(family="Segoe UI", size=11),
                corner_radius=4,
                command=lambda p=fpath: self._locate_file(p),
            )
            loc_btn.pack(side="right", padx=(2, 6), pady=4)

            # "打开"按钮 — 用默认程序直接打开文件
            open_btn = ctk.CTkButton(
                row, text="\U0001F4C4 打开", width=72, height=28,
                fg_color=C_CARD, hover_color=C_HOVER,
                text_color=C_TEXT,
                border_width=1, border_color=C_BORDER,
                font=ctk.CTkFont(family="Segoe UI", size=11, weight="bold"),
                corner_radius=4,
                command=lambda p=fpath: self._open_file(p),
            )
            open_btn.pack(side="right", padx=(2, 0), pady=4)

    def _open_file(self, file_path: str) -> None:
        """用系统默认程序直接打开文件（非阻塞）。"""
        try:
            if sys.platform == "win32":
                os.startfile(file_path)
            elif sys.platform == "darwin":
                subprocess.Popen(["open", file_path])
            else:
                subprocess.Popen(["xdg-open", file_path])
        except Exception as e:
            self._set_status(f"打开失败: {e}", C_ERROR)

    def _locate_file(self, file_path: str) -> None:
        """在资源管理器中打开并选中文件（非阻塞，支持中文路径）。"""
        try:
            if sys.platform == "win32":
                # explorer /select,"path" — /select, 必须和路径连在一起传
                # 用 shell=True 确保中文路径正确解析
                subprocess.Popen(
                    f'explorer /select,"{file_path}"',
                    shell=True,
                )
            elif sys.platform == "darwin":
                subprocess.Popen(["open", "-R", file_path])
            else:
                d = os.path.dirname(file_path)
                subprocess.Popen(["xdg-open", d])
        except Exception as e:
            self._set_status(f"定位失败: {e}", C_ERROR)

    def _open_output_dir(self) -> None:
        """打开第一个输出文件所在目录（非阻塞）。"""
        if not hasattr(self, "_last_output_dirs") or not self._last_output_dirs:
            return
        d = next(iter(self._last_output_dirs))
        try:
            if sys.platform == "win32":
                subprocess.Popen(["explorer", d])
            elif sys.platform == "darwin":
                subprocess.Popen(["open", d])
            else:
                subprocess.Popen(["xdg-open", d])
        except Exception as e:
            self._set_status(f"打开失败: {e}", C_ERROR)

    # ------------------------------------------------------------------ 重置

    def _reset(self) -> None:
        """重置到初始状态。"""
        self.dropped_files.clear()
        self.selected_format = ""
        self.is_converting = False
        self.progress_bar.set(0)
        self.progress_bar.configure(progress_color=C_TEXT)

        # 隐藏结果列表，恢复拖放区 Canvas
        self.results_frame.pack_forget()
        self.drop_canvas.pack(fill="both", expand=True, padx=30, pady=(5, 5))

        self._draw_idle_state()
        self.format_menu.configure(values=["—"], state="disabled")
        self.format_menu.set("选择格式")
        self.convert_btn.configure(state="disabled", text="开始转换")
        self._set_status("就绪", C_TEXT_DIM)

        # 隐藏辅助按钮
        self.open_dir_btn.pack_forget()
        self.reset_btn.pack_forget()

    def _post_status(self, text: str, color: str) -> None:
        """线程安全地更新状态文字。"""
        self.window.after(
            0, lambda t=text, c=color: self._set_status(t, c)
        )


# ===========================================================================
# 入口
# ===========================================================================

def main() -> None:
    try:
        DocuShiftApp()
    except Exception as e:
        # 如果 GUI 初始化失败，回退到控制台输出
        print(f"DocuShift 启动失败: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        input("按回车键退出…")
        sys.exit(1)


if __name__ == "__main__":
    main()
