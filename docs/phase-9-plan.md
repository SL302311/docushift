# 第 9 期计划：图片转 PDF 结果的系统分享

## 目标

让用户在“图片转 PDF”成功后，可直接将刚生成的 PDF 交给 Android 系统分享面板（聊天、邮件、网盘等已安装目标），补齐移动端“转换完成 → 使用结果”的最小闭环。

本期只分享应用刚刚成功生成的**单个 PDF** SAF `content://` URI；不生成副本、不上传、不引入分享 SDK，也不扩展 PDF→PNG/JPG 文件夹结果的分享。

## 产品规则（冻结）

- 仅在图片→PDF 成功态且存在非空输出 URI 时显示“分享 PDF”按钮；转换中、失败、已选择和空闲态均不显示，也不得调用平台分享。
- 分享以 Android 系统 chooser 发起：`ACTION_SEND`、`type = application/pdf`、`EXTRA_STREAM` 为原始输出 URI，并设置 `FLAG_GRANT_READ_URI_PERMISSION` 和 `ClipData`。只临时授予目标应用读取该文件的能力，不复制文件、不持久化分享授权。
- 系统选择器成功启动仅表示“已交给系统”，不等于用户已发送或对方已接收。用户取消/返回分享面板时，图片→PDF 页面仍保持成功态、文件 URI、字节数和“分享 PDF”按钮，不显示转换失败。
- Android 原生层只接受非空 `content://` 输出 URI；空值或非 `content` scheme 返回 `INVALID_ARGS` / `INVALID_OUTPUT_URI`，不得启动 intent。没有可处理目标或启动系统选择器失败返回 `SHARE_UNAVAILABLE`。
- Flutter 发起分享失败时仅用页面临时提示告知用户，不能把已成功的转换状态改成 failure、不能丢失输出 URI，也不能触发重新转换或删除输出。
- 首页保持三个转换入口。PDF→PNG/JPG 输出的是 SAF 文件夹，本期不提供文件夹分享、批量图片分享、ZIP 打包、打开目录或预览。

## 技术方案

### Android

- 在既有 `com.example.docushift_mobile/image_to_pdf` MethodChannel 增加 `sharePdf`，参数为 `outputUri`；不创建第二条 channel，不改变 `pickImages` / `convertAndSave` 契约。
- `ImageToPdfPlugin` 只做结构性参数校验并委托 `ImageToPdfCoordinator`；协调器持有可注入的 `shareLauncher` 接缝，生产实现构建显式 `Intent` 后经 Activity 启动 chooser。单元测试必须检查 intent action、MIME、`EXTRA_STREAM`、`ClipData`、读授权 flag 以及不触发转换/删除。
- 分享前在原生层再次解析并校验 URI scheme；不能把 `file://`、路径字符串、任意输出目录或未知 scheme 包装成可分享文件。异常映射为稳定码，原异常不能导致崩溃。
- 分享只读已有 URI，不使用 `FileProvider`，不写临时文件，不申请或改变广泛存储/持久 URI 授权，不进入后台线程，也不干扰转换的 single-flight、输出清理或 `onDestroy`。

### Flutter

- 扩展 `image_to_pdf_platform.dart` / interface 的常量与 `sharePdf(String outputUri)` 方法；MethodChannel 参数名唯一且使用现有输出 URI，不根据名称猜路径。
- `ImageToPdfController.shareOutput()` 只在 success 且 URI 非空时调用平台；成功与用户从 chooser 返回都保持 state 不变。平台异常向页面返回可显示错误，不得把 success 改成 failure。
- 成功态的“重新选择”前增加“分享 PDF”按钮；按钮以 `async` 调用并以 `ScaffoldMessenger` 显示分享启动失败提示。失败态重试、selected 列表编辑、取消保存、排序和原有转换按钮不变。

## 实施顺序

1. 先在 Android 协调器定义安全的分享 intent 工厂/启动接缝和稳定错误映射，补 JVM intent 与无副作用测试。
2. 接入现有图片→PDF Plugin 的 `sharePdf` 方法与参数校验，回归原有选择、保存、转换、清理和 single-flight。
3. 扩展 Flutter platform interface、控制器和 success UI；补 state 不变、平台参数、错误提示与其他状态不触发分享的测试。
4. 更新 README、支持矩阵、架构、`artifacts/phase-9/`；由规划/验收角色独立执行门禁。

## 必须测试

### Android（标准 Gradle）

- 有效 `content://` URI 构造的 intent 为 `ACTION_SEND`、`application/pdf`、同一 `EXTRA_STREAM` 与 `ClipData` URI，并含 `FLAG_GRANT_READ_URI_PERMISSION`；生产 launcher 被调用一次。
- 空/空白 URI → `INVALID_ARGS`；`file://`、裸路径、`http(s)://` 和未知 scheme → `INVALID_OUTPUT_URI`；上述路径不构造/不启动 intent、不调用转换器或输出删除器。
- launcher 无目标/抛 `ActivityNotFoundException` 或普通启动异常 → `SHARE_UNAVAILABLE`；原错误不崩溃，不影响既有转换请求与已成功输出。
- Plugin 对缺失、空、非字符串 `outputUri` 返回 `INVALID_ARGS`；有效参数精确委托 coordinator；未知方法、原 `pickImages` 与 `convertAndSave` 全量回归。
- 既有图片→PDF 的 MIME/大小/像素、多图排序、保存取消、写入失败清理、single-flight 和 `onDestroy` 全量回归。

### Flutter

- MethodChannel 的 `sharePdf` 使用唯一方法名/参数键；输出 URI 原样透传，平台异常正常传播给调用者。
- controller 仅在 success + 非空 URI 时调用一次；idle/selected/converting/failure 或空 URI 不调用平台；成功后状态、图片列表、输出 URI 和字节数保持不变。
- success 页面显示“分享 PDF”与“重新选择”；其他页面不显示分享；分享失败显示临时提示且页面仍是 success；既有成功、失败重试、取消保存和重新选择回归。

## 验收门禁

- 工作区干净；新增源码仅限现有图片→PDF 成功结果的分享契约、Android intent、必要 UI/测试/文档。不得引入运行时依赖、网络、广告、分析、云端、FileProvider、NDK、广泛存储权限或新的格式/首页入口。
- 在 `D:\docushift_workspace\mobile` 独立执行：

  ```powershell
  D:\flutter\bin\flutter.bat analyze --no-pub
  D:\flutter\bin\flutter.bat test --no-pub
  cd android
  .\gradlew.bat testDebugUnitTest --rerun-tasks
  cd ..
  D:\flutter\bin\flutter.bat clean
  D:\flutter\bin\flutter.bat pub get --offline
  D:\flutter\bin\flutter.bat build apk --debug --no-pub
  ```

- 记录 Flutter/Android 测试数、Debug APK 字节数与 SHA-256；相对第八期独立基线 `146,201,866 B` 的增量不得超过 1 MiB。
- 本期只验收 intent 契约、代码、自动化测试和构建。实际是否出现系统面板、各目标应用能否读取 PDF、用户取消行为及不同厂商 ROM 兼容性，统一留到第 10 期用户手机验收。

## 不做

- PDF→PNG/JPG 文件夹、图片、ZIP、多个文件或任意 URI 的分享；预览/打开文件、打开目录、最近记录、复制路径、删除输出、再转换。
- 通过网络上传、云盘登录、第三方分享 SDK、FileProvider、创建临时副本、持久分享权限、广泛存储权限、iOS、格式转换、清晰度或 JPG 质量调整。
