# 第 10 期计划：候选 APK 与用户手机验收

## 目标

在不扩大产品能力的前提下，把已经通过工程验收的 Android 本地转换工具交付为可安装的候选 APK，并由用户在自己的 Android 手机上完成首次真实使用验收。

第 10 期的交付不是新增一种格式，而是确认现有闭环在真实设备上成立：安装与启动、图片转 PDF、系统分享、PDF 转 PNG/JPG，以及 SAF 文件选择和目录保存。

## 冻结范围

- 保留首页的三个入口：图片转 PDF、PDF 转 PNG、PDF 转 JPG。
- 保留第 9 期的图片→PDF 单文件系统分享；只分享本次成功产生的 `content://` PDF。
- 交付一个 Debug 候选 APK，附带精确路径、字节数、SHA-256、构建命令和手机验收记录模板。
- 只允许为构建证据、安装说明和真实验收结果补充文档；除非自动化门禁或真机验收发现阻塞缺陷，不修改产品源码。

## 不做

- 不新增 Word、Office、HTML、OCR、压缩、扫描、美化、预览、历史记录或新的首页入口。
- 不引入网络、账号、广告、统计、云端转换、第三方转换引擎、FileProvider、广泛存储权限、iOS 或发布商店。
- 不把“系统 chooser 已显示”误写成“文件已经发送成功”；用户取消分享也不视为失败。

## 实施与交付顺序

1. **冻结候选提交**：确认工作区无待提交源码改动，记录候选提交号与 Flutter / Gradle 环境版本。
2. **全量自动化门禁**：在 `D:\docushift_workspace\mobile` 依次执行静态分析、Flutter 测试、Android 单元测试；不得以手工运行或旧证据替代。
3. **干净构建**：执行 `flutter clean`，恢复依赖配置，再构建 Debug APK；记录 APK 的绝对路径、字节数和 SHA-256。`clean` 后不得直接使用 `build --no-pub`，必须先执行 `pub get`，否则 `.dart_tool/package_config.json` 缺失并非产品故障。
4. **交付材料**：建立 `artifacts/phase-10/build-evidence.md` 与 `artifacts/phase-10/user-acceptance.md`。前者记录命令和结果，后者预置下列手机测试表，待用户填写实际结果；不得在真机测试前标注通过。
5. **用户手机验收**：把 APK 交给用户安装，由用户按“手机验收清单”执行。规划/验收角色只依据用户实际反馈决定通过、返工或记录后续产品路线。

## 构建门禁

```powershell
cd D:\docushift_workspace\mobile
D:\flutter\bin\flutter.bat analyze --no-pub
D:\flutter\bin\flutter.bat test --no-pub

cd android
.\gradlew.bat testDebugUnitTest --rerun-tasks
cd ..

D:\flutter\bin\flutter.bat clean
D:\flutter\bin\flutter.bat pub get --offline
D:\flutter\bin\flutter.bat build apk --debug --no-pub
Get-Item .\build\app\outputs\flutter-apk\app-debug.apk
Get-FileHash .\build\app\outputs\flutter-apk\app-debug.apk -Algorithm SHA256
```

- `flutter analyze` 必须为 0 issue；Flutter 与 Android 测试必须全通过。
- APK 基于冻结提交构建，路径为 `D:\docushift_workspace\mobile\build\app\outputs\flutter-apk\app-debug.apk`；交付时不得用旧包替代。
- 记录包体；相对第 9 期独立基线 `146,203,046 B` 的增量不得超过 1 MiB。超出时先定位原因，不以“第十期”为理由放宽。
- 检查 Manifest 及变更范围：不得新增网络、广泛存储权限或 FileProvider。

## 手机验收清单（由用户执行）

安装前，用户在手机上允许所使用的“文件管理器/浏览器/聊天应用”安装未知来源 APK；这是 Android 对安装来源的授权，不是应用申请的存储或网络权限。

| 编号 | 操作 | 期望结果 | 级别 |
|---|---|---|---|
| M1 | 安装候选 APK 并从桌面启动 | 可安装、可启动，首页显示三个功能入口，无闪退 | P0 |
| M2 | 选择至少两张 PNG/JPG/BMP 中的不同图片，调整顺序并保存为 PDF | 生成的 PDF 可打开，页数与顺序正确；保存取消不丢失已选图片 | P0 |
| M3 | 在 M2 成功页点击“分享 PDF”，选择一个已安装且能接收 PDF 的应用或文件工具 | 出现 Android 分享面板；目标能读取同一 PDF；取消/返回后仍是成功页且可再次分享 | P0 |
| M4 | 选择一个至少 3 页的 PDF，导出 PNG 第 2—3 页到指定目录（96 或 144 dpi） | 指定目录中新建结果文件夹，包含 `002.png`、`003.png`，图片可打开 | P0 |
| M5 | 用同一 PDF 导出 JPG（可选 216 dpi）到另一个目录 | 输出 JPG 可打开，页码命名与所选范围一致，未出现黑底或明显页面错位 | P0 |
| M6 | 分别取消一次文件选择或输出目录选择；返回首页后重新进入任一功能 | 无闪退、无误删原输入文件，界面仍可继续操作 | P1 |

验收记录必须包含：手机型号、Android 版本、APK SHA-256、每项 M1—M6 的通过/失败、失败步骤、屏幕截图或录屏（若方便）及产生的输出文件。P0 失败则第十期不通过并在第十期内返工；P1 作为修复优先级由用户确认后处理。

## 第十期完成定义

同时满足以下条件才可标记第十期完成：

1. 自动化门禁和干净构建全部通过，候选 APK 的路径、字节数与 SHA-256 已记录；
2. 用户在一台真实 Android 手机完成 M1—M5，且所有 P0 项通过；
3. 用户确认 APK 可继续保留，或明确列出待改进项；
4. README、支持矩阵、架构文档和 `artifacts/phase-10/` 如实记录最终状态。

## 给实施 agent 的约束

本期只做打包、证据和验收协作。不要因“第十期”自行加入 Word 或任何新格式；真实设备发现的问题先复现、最小修复、补回归测试，并继续停留在第十期直到通过。
