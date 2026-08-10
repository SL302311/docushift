@echo off
chcp 65001 >nul 2>&1
title DocuShift Build Script
echo ============================================
echo   DocuShift  EXE  打包脚本
echo ============================================
echo.

REM ---- 检查 Python ----
where python >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] 未找到 Python，请先安装 Python 3.10+
    pause
    exit /b 1
)

REM ---- 安装依赖 ----
echo [1/4] 安装 Python 依赖...
pip install -r requirements.txt pyinstaller --quiet
if %ERRORLEVEL% neq 0 (
    echo [ERROR] 依赖安装失败
    pause
    exit /b 1
)
echo       完成.
echo.

REM ---- 下载 pandoc.exe（如不存在）----
echo [2/4] 检查 pandoc.exe...
if exist "pandoc.exe" (
    echo       pandoc.exe 已存在.
) else (
    echo       正在下载 pandoc.exe...
    REM 尝试从 GitHub 下载 pandoc 独立可执行文件
    powershell -Command ^
        "$url = 'https://github.com/jgm/pandoc/releases/download/3.1.11/pandoc-3.1.11-windows-x86_64.zip';" ^
        "$tmp = '$env:TEMP\pandoc_dl.zip';" ^
        "$dest = '$env:TEMP\pandoc_extract';" ^
        "Invoke-WebRequest -Uri $url -OutFile $tmp;" ^
        "Expand-Archive -Path $tmp -DestinationPath $dest -Force;" ^
        "Copy-Item '$dest\pandoc-3.1.11\pandoc.exe' 'pandoc.exe' -Force;" ^
        "Remove-Item $tmp -Force;" ^
        "Remove-Item $dest -Recurse -Force"
    if exist "pandoc.exe" (
        echo       pandoc.exe 下载完成.
    ) else (
        echo [WARN] pandoc.exe 自动下载失败，MD/HTML 转换将依赖系统 pandoc.
        echo        请手动下载 pandoc.exe 放至本项目根目录后重新运行.
    )
)
echo.

REM ---- PyInstaller 打包 ----
echo [3/4] 正在打包（PyInstaller）...
set ADD_DATA_FLAG=
if exist "pandoc.exe" (
    set ADD_DATA_FLAG=--add-data "pandoc.exe;."
)

pyinstaller --noconfirm --onedir --windowed ^
    --name "DocuShift_SL" ^
    --collect-all customtkinter ^
    --collect-all tkinterdnd2 ^
    --collect-all fitz ^
    --collect-all pdf2docx ^
    %ADD_DATA_FLAG% ^
    main.py

if %ERRORLEVEL% neq 0 (
    echo [ERROR] PyInstaller 打包失败
    pause
    exit /b 1
)
echo       打包完成.
echo.

REM ---- 完成 ----
echo [4/4] 全部完成!
echo.
echo   输出目录:  dist\DocuShift_SL\
echo   可执行文件: dist\DocuShift_SL\DocuShift_SL.exe
echo.
pause
