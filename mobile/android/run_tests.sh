#!/usr/bin/env bash
# DocuShift Android 单元测试直跑脚本（绕开 AGP9 + Flutter Gradle Plugin 的 test worker 缺陷）。
# 前置：已执行 ./gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:printTestCp
# 用法：bash run_tests.sh
set -euo pipefail

cd "$(dirname "$0")"   # 进入 mobile/android

# 本工程 :app 模块的构建输出落在 mobile/build/app/（非 mobile/android/app/build/）。
BUILD_ROOT="../build/app"
APP_DIR="app"
CP_FILE="$APP_DIR/test_cp.txt"

if [ ! -f "$CP_FILE" ]; then
  echo "未找到 $CP_FILE，请先运行 gradle :app:printTestCp" >&2
  exit 1
fi

PKG="com/example/docushift_mobile"

# 收集所有 *Test 类的全限定名（以 $PKG 为锚点），并收集去重的 classpath 根目录。
CLASSES=()
CP_ROOTS=()
while IFS= read -r f; do
  # f 形如 .../com/example/docushift_mobile/FooTest.class
  rel="${f##*/$PKG/}"            # FooTest.class
  root="${f%/$PKG/$rel}"         # classpath 根目录（含 com 的父目录）
  fqcn="com.example.docushift_mobile.${rel%.class}"
  CLASSES+=("$fqcn")
  # 去重加入 classpath 根
  found=0
  for r in "${CP_ROOTS[@]:-}"; do
    [ "$r" = "$root" ] && found=1 && break
  done
  [ "$found" -eq 0 ] && CP_ROOTS+=("$root")
done < <(find "$BUILD_ROOT" -name '*Test.class' ! -name '*$*' 2>/dev/null | sort -u)

if [ "${#CLASSES[@]}" -eq 0 ]; then
  echo "未找到任何 *Test 类，请先编译测试代码" >&2
  exit 1
fi

# 拼接 classpath：导出的依赖 classpath + 各测试类根目录
FULL_CP="$(cat "$CP_FILE" | tr -d '\n' | tr -d '\r')"

# 新鲜主类目录（kotlin-classes/debug）前置：AGP 的 bundleDebugClassesToRuntimeJar 可能打包
# 陈旧的 compile_app_classes_jar（UP-TO-DATE 缺陷），导致主类的接缝/可见性变更不生效。
# URLClassLoader 优先加载靠前的条目，前置可确保使用最新主类。
MAIN_DIR="$(find "$BUILD_ROOT/tmp/kotlin-classes" -maxdepth 1 -type d -name 'debug' 2>/dev/null | head -1)"
if [ -n "$MAIN_DIR" ]; then
  FULL_CP="$MAIN_DIR;$FULL_CP"
  echo ">>> 前置新鲜主类目录: $MAIN_DIR"
fi

for dir in "${CP_ROOTS[@]:-}"; do
  [ -n "$dir" ] && FULL_CP="$FULL_CP;$dir"
done

echo ">>> 测试类根目录: ${CP_ROOTS[*]}"
echo ">>> 将运行 ${#CLASSES[@]} 个测试类:"
printf '    - %s\n' "${CLASSES[@]}"

java -cp "$FULL_CP" org.junit.runner.JUnitCore "${CLASSES[@]}"
