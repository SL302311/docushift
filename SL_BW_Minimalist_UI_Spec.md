# SL B&W Minimalist UI 设计规范

> 本文档定义 **SL B&W Minimalist**（SL 黑白极简）桌面应用 UI 风格的完整规范。
> 基于 DocuShift 项目提炼，适用于所有 CustomTkinter + tkinterdnd2 桌面工具应用。

---

## 1. 设计理念

| 关键词 | 说明 |
|-------|------|
| **纯黑底** | 深色模式为主，背景接近纯黑 `#0F0F0F`，不用蓝/紫渐变 |
| **灰阶分层** | 通过 5 级灰度（`#0F0F0F` → `#555555` → `#E0E0E0`）建立层次 |
| **零彩噪** | 仅在成功/错误状态使用极少量彩色，其余全部灰阶 |
| **虚线元素** | 拖放区使用虚线边框 `dash=(10,6)`，呼应极简手稿质感 |
| **大留白** | 控件间距宽裕（padx ≥ 12px），不拥挤 |
| **水印签名** | 右下角固定 `© SL` 标识，低调存在 |

---

## 2. 色板

### 2.1 核心灰阶

| 变量 | 色值 | 用途 |
|------|------|------|
| `C_BG` | `#0F0F0F` | 窗口/主容器底色（最深） |
| `C_BG_LIGHT` | `#1A1A1A` | 次级容器底色 |
| `C_CARD` | `#1E1E1E` | 按钮/下拉框/卡片控件底色 |
| `C_BORDER` | `#333333` | 虚线边框、分割线、水印 |
| `C_HOVER` | `#2A2A2A` | 鼠标悬停反馈色 |

### 2.2 文字层级

| 变量 | 色值 | 用途 | 示例 |
|------|------|------|------|
| `C_TEXT` | `#E0E0E0` | 主文字（文件名、按钮文字） | 最亮，用于关键信息 |
| `C_TEXT_DIM` | `#888888` | 次要文字（提示、状态栏） | 常规信息 |
| `C_TEXT_FAINT` | `#555555` | 弱化文字（版本号、说明） | 最暗，辅助信息 |

### 2.3 状态色（极少量使用）

| 变量 | 色值 | 用途 |
|------|------|------|
| `C_SUCCESS` | `#4CAF50` | 转换成功、绿色进度条 |
| `C_ERROR` | `#EF4444` | 转换失败、错误状态 |
| `C_DANGER` | `#E81123` | 关闭按钮悬停红 |
| `C_WATERMARK` | `#333333` | 右下角 `© SL` 水印 |

> **原则：灰底中不出现彩色装饰。** `C_SUCCESS` / `C_ERROR` 仅在进度条颜色和状态文字中使用，不用于按钮或背景。

---

## 3. 窗口规格

### 3.1 基本参数

```
尺寸:     600 × 400 px（固定不可缩放）
模式:     Frameless Window（overrideredirect(True)）
位置:     屏幕居中
置顶:     启动时短暂 topmost，200ms 后取消
主题:     ctk.set_appearance_mode("dark")
```

### 3.2 任务栏支持（Windows）

无边框窗口默认不出现在任务栏，需用 Win32 API 修正：

```python
import ctypes

def _enable_taskbar_icon(self):
    hwnd = ctypes.windll.user32.GetParent(self.window.winfo_id())
    GWL_EXSTYLE      = -20
    WS_EX_TOOLWINDOW = 0x00000080
    WS_EX_APPWINDOW  = 0x00040000

    ex_style = ctypes.windll.user32.GetWindowLongPtrW(hwnd, GWL_EXSTYLE)
    ex_style = (ex_style & ~WS_EX_TOOLWINDOW) | WS_EX_APPWINDOW
    ctypes.windll.user32.SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex_style)

    # 刷新窗口框架
    SWP_NOMOVE = 0x0002; SWP_NOSIZE = 0x0001
    SWP_NOZORDER = 0x0004; SWP_FRAMECHANGE = 0x0020
    ctypes.windll.user32.SetWindowPos(
        hwnd, 0, 0, 0, 0, 0,
        SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGE
    )
```

- 启动时调用一次
- 窗口从最小化恢复（`<Map>` 事件）时再调用一次

### 3.3 最小化逻辑

无边框窗口不能直接 `iconify()`，需临时取消 `overrideredirect`：

```python
def _minimize(self):
    self.window.overrideredirect(False)
    self.window.iconify()

def _on_window_map(self, event):
    if self.window.state() == "normal":
        self.window.overrideredirect(True)
        self._enable_taskbar_icon()
```

---

## 4. 布局结构

窗口从上到下分为 5 个区域：

```
┌──────────────────────────────────────────┐
│  标题栏 (36px)                           │  ← 自定义拖拽 + 最小化/关闭
├──────────────────────────────────────────┤
│                                          │
│                                          │
│         主交互区 (~280px, 70%)           │  ← Canvas 拖放区 ⇄ 结果列表
│                                          │
│                                          │
├──────────────────────────────────────────┤
│  控制区 (42px)                           │  ← 格式选择 + 转换按钮
├──────────────────────────────────────────┤
│  进度区 (36px)                           │  ← 进度条 + 状态文字 + © SL
└──────────────────────────────────────────┘
```

### 4.1 标题栏 (36px)

| 元素 | 规格 |
|------|------|
| 高度 | 36px，`pack_propagate(False)` |
| 底色 | `C_BG` (#0F0F0F) |
| 应用名 | Segoe UI 14px Bold，`C_TEXT_DIM` (#888888) |
| 版本号 | Segoe UI 10px，`C_TEXT_FAINT` (#555555) |
| 最小化按钮 | `—`，36×36，`corner_radius=0`，hover → `C_HOVER` |
| 关闭按钮 | `✕`，36×36，`corner_radius=0`，hover → `C_DANGER` (#E81123) |
| 拖拽 | 标题栏所有子控件绑定 `<Button-1>` + `<B1-Motion>` |

### 4.2 主交互区 — Canvas 拖放态

| 元素 | 规格 |
|------|------|
| 占比 | 屏幕约 70%（`fill="both", expand=True`） |
| 边距 | `padx=30, pady=(5,5)` |
| 虚线边框 | `create_rectangle`，`dash=(10,6)`，`width=2`，颜色 `C_BORDER` |
| 空闲态文字 | `⬇ 拖放文件至此` (16px Bold) + `或点击选择文件` (11px) |
| 支持格式 | `PDF · DOCX · Markdown · HTML · PNG/JPG` (10px，`C_TEXT_FAINT`) |
| 拖入态 | 边框变亮 (`C_TEXT`)，文字变为 `松开以添加文件` |
| 文件已加载 | 显示 📄 图标 + 文件名 (14px Bold) + 格式信息 (11px) |

Canvas 尺寸变化时需重绘（绑定 `<Configure>` 事件）。

### 4.3 主交互区 — 结果列表态

转换完成后，Canvas 隐藏，`CTkScrollableFrame` 显示：

| 元素 | 规格 |
|------|------|
| 标题行 | `转换完成 · N 个文件`，14px Bold，`C_SUCCESS` |
| 文件行 | `CTkFrame`，`fg_color=C_CARD`，`corner_radius=6` |
| 文件名 | 12px Bold，`C_TEXT` |
| 文件大小 | 10px，`C_TEXT_FAINT` |
| 完整路径 | 10px，`C_TEXT_DIM`（超 45 字符截断为 `...` + 后 42 字符） |
| 打开目录按钮 | 70×26，transparent 底 + `C_BORDER` 描边，调用 `explorer /select` |

### 4.4 控制区 (42px)

| 元素 | 规格 |
|------|------|
| 格式下拉 | `CTkOptionMenu`，160×32，`C_CARD` 底，Segoe UI 12px |
| 转换按钮 | 110×32，`C_CARD` 底 + `C_BORDER` 描边，12px Bold |
| 打开目录按钮 | 120×32，transparent 底 + `C_BORDER` 描边（转换后显示） |
| 重新选择按钮 | 90×32，transparent 底（转换后显示） |
| 圆角 | 所有按钮 `corner_radius=6` |

### 4.5 进度区 (36px)

| 元素 | 规格 |
|------|------|
| 进度条 | `CTkProgressBar`，height=6，`corner_radius=3`，`padx=40` |
| 进度色 | 默认 `C_TEXT`，成功 → `C_SUCCESS`，失败 → `C_ERROR` |
| 状态文字 | Segoe UI 11px，`C_TEXT_DIM`，左对齐 `padx=40` |
| 水印 | `© SL`，Segoe UI 10px，`C_WATERMARK` (#333333)，右对齐 `padx=40` |

---

## 5. 字体规范

全局使用 **Segoe UI**（Windows 系统字体），不引入外部字体：

| 场景 | 字号 | 字重 | 颜色 |
|------|------|------|------|
| 应用标题 | 14px | Bold | `C_TEXT_DIM` |
| 主提示文字 | 16px | Bold | `C_TEXT_DIM` |
| 文件名 | 14px | Bold | `C_TEXT` |
| 按钮文字 | 12px | Bold | `C_TEXT` |
| 下拉框 | 12px | Regular | `C_TEXT` |
| 状态文字 | 11px | Regular | `C_TEXT_DIM` |
| 辅助说明 | 10px | Regular | `C_TEXT_FAINT` |
| 版本号 | 10px | Regular | `C_TEXT_FAINT` |
| 水印 | 10px | Regular | `C_WATERMARK` |

---

## 6. 交互规范

### 6.1 拖放 (Drag & Drop)

- 使用 `tkinterdnd2` 绑定 `<<Drop>>` / `<<DragEnter>>` / `<<DragLeave>>`
- Canvas 注册 `drop_target_register(DND_FILES)`
- 路径解析：`tk.splitlist(event.data)` → `strip("{}")` 处理含空格路径
- 点击 Canvas 也可打开文件选择对话框（备选方案）

### 6.2 多线程转换

```python
thread = threading.Thread(target=self._conversion_worker, daemon=True)
thread.start()
```

- 所有转换函数在后台线程执行，主线程仅更新 UI
- 通过 `window.after(0, callback)` 线程安全地操作 UI
- 进度回调：`(percent: int, message: str)` → 更新进度条 + 状态文字

### 6.3 状态反馈

| 状态 | 进度条颜色 | 状态文字颜色 |
|------|-----------|------------|
| 转换中 | `C_TEXT` (#E0E0E0) | `C_TEXT` |
| 全部成功 | `C_SUCCESS` (#4CAF50) | `C_SUCCESS` |
| 部分失败 | `C_TEXT` (#E0E0E0) | `C_TEXT_DIM` |
| 全部失败 | `C_ERROR` (#EF4444) | `C_ERROR` |

---

## 7. 技术栈

| 层 | 技术 | 用途 |
|----|------|------|
| UI 框架 | CustomTkinter 5.2+ | 深色主题控件 |
| 拖放 | tkinterdnd2 0.4+ | 文件拖拽绑定 |
| 窗口控制 | ctypes (Win32 API) | 任务栏图标、无边框窗口 |
| 多线程 | threading.Thread | 后台转换防假死 |
| 打包 | PyInstaller --onefile | 单文件 EXE 分发 |

### DnD 窗口基类

CustomTkinter 和 tkinterdnd2 需要混合继承：

```python
class DnDCTk(ctk.CTk, TkinterDnD.DnDWrapper):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.TkdndVersion = TkinterDnD._require(self)
```

---

## 8. 图标规范

### 应用图标 (.ico)

- 尺寸：256×256 主图，导出多尺寸 (16/32/48/64/128/256)
- 风格：圆角方块底 `#0F0F0F` + 虚线内框 `#333333` + 白色 `DS` 字样
- 生成：Pillow `ImageDraw` 绘制 → `img.save('app.ico', format='ICO')`
- 打包：`pyinstaller --icon app.ico`

---

## 9. PyInstaller 打包参数

```bash
pyinstaller --noconfirm --onefile --windowed \
    --name "DocuShift" \
    --icon "app.ico" \
    --collect-all customtkinter \
    --collect-all tkinterdnd2 \
    --collect-all fitz \
    --collect-all pdf2docx \
    --exclude-module tkinter.test \
    --exclude-module unittest \
    --exclude-module email \
    --exclude-module http \
    main.py
```

**体积优化清单：**
- 不捆绑 pandoc.exe（204MB），改为首次使用时自动下载到 `~/.docushift/`
- 删除跨平台 tkdnd 库（仅保留 win-x64）
- 删除 opencv ffmpeg DLL（27MB）
- 删除 PIL AVIF 编解码（7.5MB）
- 删除 `__pycache__` 目录
- `--exclude-module` 排除不用的标准库

---

## 10. 代码结构模板

```
项目根/
├── main.py              # UI 层：窗口、布局、交互、多线程调度
├── core/
│   ├── __init__.py
│   └── converter.py     # 业务层：转换引擎（独立于 UI）
├── app.ico              # 应用图标
├── requirements.txt
└── build.bat            # 打包脚本
```

**分层原则：**
- `main.py` 只管 UI 和交互逻辑，不包含业务实现
- `core/` 只管业务逻辑，不引用任何 UI 库
- 转换引擎通过 `progress_callback` 回调与 UI 通信，完全解耦
