#!/bin/bash
# NPU LLM APK v0.2.0 (spec v0.1.1) — jniLibs launch: the APK ships llama-server
# + full Hexagon runtime in lib/arm64-v8a/ (renamed libllamaserver.so).
# Manual aapt2+d8 chain (no Gradle), toolchain reused from ~/llama-apk.
set -euo pipefail
ROOT=/home/dawoer/npu-llm-apk
APP=$ROOT/app/src/main
BT=$HOME/llama-apk/sdk/bt34/android-14
AJ=$HOME/llama-apk/sdk/android-36/android.jar
JDK=$HOME/llama-apk/sdk/jdk21/bin
OUT=$ROOT/build
# Release signing: credentials live outside the repo in ~/.annpu-sign.env (chmod 600)
source $HOME/.annpu-sign.env
KEYS=$ANNPU_KS
KS_PASS=$ANNPU_KS_PASS
KEY_ALIAS=$ANNPU_KEY_ALIAS
# Runtime source: proven v1.1 build output (llama-server + ggml/HTP + vendor closure)
SRC=$HOME/llama-apk/build/lib/arm64-v8a

# ---- version: SINGLE SOURCE OF TRUTH (v1.2.1) ----
# aapt2 manifest + index.html verLine + app.js APP_VER all injected from here.
# Bump ONLY these two lines each build; build-verify cross-checks the APK.
VC=57
VN=1.3.1
V="$VN (build $VC)"

echo "== version: $V =="
# inject into index.html verLine placeholder
sed -i "s|<div id=\"verLine\">[^<]*</div>|<div id=\"verLine\">$V · llama.cpp (MIT)</div>|" $APP/assets/index.html
grep -q "id=\"verLine\">$V " $APP/assets/index.html || { echo VERLINE_INJECT_FAILED; exit 1; }

rm -rf $OUT/classes $OUT/dex $OUT/base.apk $OUT/aligned.apk $OUT/lib
mkdir -p $OUT/classes $OUT/dex

echo "== terser (minify+obfuscate app.js) =="
npx --yes terser $APP/assets/app.js -o $APP/assets/app.js.min -c -m --comments false
mv $APP/assets/app.js.min $APP/assets/app.js
# inject version into minified JS APP_VER (generic: replaces placeholder OR any previous version)
sed -i -E "s|APP_VER=\"[^\"]*\"|APP_VER=\"$VN (build $VC)\"|" $APP/assets/app.js
grep -q "APP_VER=\"$VN (build $VC)\"" $APP/assets/app.js || { echo APPVER_INJECT_FAILED; exit 1; }

echo "== javac =="
$JDK/javac --release 11 -encoding UTF-8 -cp $AJ -d $OUT/classes $APP/java/com/dawoer/npullm/*.java
[ -f $OUT/classes/com/dawoer/npullm/MainActivity.class ] || { echo JAVAC_FAILED; exit 1; }

echo "== d8 =="
$JDK/jar cf $OUT/classes.jar -C $OUT/classes .
$BT/d8 --release --lib $AJ --min-api 33 --output $OUT/dex $OUT/classes.jar
[ -f $OUT/dex/classes.dex ] || { echo D8_FAILED; exit 1; }

echo "== aapt2 compile res =="
$BT/aapt2 compile --dir $APP/res -o $OUT/res.zip

echo "== aapt2 link =="
$BT/aapt2 link -o $OUT/base.apk -I $AJ \
  --manifest $APP/AndroidManifest.xml \
  -A $APP/assets \
  --min-sdk-version 33 --target-sdk-version 36 \
  --version-code $VC --version-name $VN \
  $OUT/res.zip

echo "== add dex =="
(cd $OUT/dex && zip -q $OUT/base.apk classes.dex)

echo "== pack native runtime into jniLibs =="
mkdir -p $OUT/lib/arm64-v8a
# --- htp engine (exec path, verified v1.1): launcher exe + htp tree + vendor closure ---
# launcher exe — MUST be named lib*.so for Android to extract it exec-ready
cp $SRC/libllama-server.so $OUT/lib/arm64-v8a/libllamaserver.so
# vendor closure + full-symbol libc++ (v1.2.3: exec 子进程两台通吃)
cp $ROOT/vendor-libs/libcdsprpc.so $ROOT/vendor-libs/libhidlbase.so \
   $ROOT/vendor-libs/libhardware.so $ROOT/vendor-libs/libutils.so \
   $ROOT/vendor-libs/libcutils.so $ROOT/vendor-libs/libbase.so \
   $ROOT/vendor-libs/libdmabufheap.so $ROOT/vendor-libs/libvmmem.so \
   $ROOT/vendor-libs/android.hardware.common-V2-ndk.so \
   $ROOT/vendor-libs/vendor.qti.hardware.dsp-V1-ndk.so \
   $OUT/lib/arm64-v8a/
cp $ROOT/vendor-libs/libc++.so $OUT/lib/arm64-v8a/libc++.so
# full runtime: ggml/llama/mtmd/HTP skels + OpenCL stub
# copy non-htp-skel libs (htp skels come from htp-libs-16k, rebuilt 16KB-aligned)
# v1.2.3 NPU 修复（横跳终结）：恢复 libcdsprpc vendor 闭包 + 打包全量 libc++.so。
# 历史教训：带闭包(≤v1.1.9) S25 活/S23U 死（OneUI6.1 libc++ 缺符号）；去闭包
# (v1.2.0) S23U 活/S25 死（A15 linker namespace 不放行 vendor 库给 /data 二进制）。
# 根解 = 闭包 + 全符号 libc++.so（从 S25 /system/lib64 提取，2513 符号含
# _ZTVNSt3__114basic_ifstream），两台 exec 子进程都从 jniLibs 解析闭包依赖。
# libcdsprpc.so 必须自带：S25 上 ggml-hexagon dlopen("libcdsprpc.so") 在
# /data 二进制上下文不查 vendor（0907 S25 NPU 死因实锤）。
# 注：打包的 libc++.so 只进 exec 子进程链（soname=libc++.so，LD_LIBRARY_PATH
# 指向 nativeLibraryDir），app 进程不受影响（app 不带这个 NEEDED）。
for f in $SRC/libggml*.so $SRC/libllama*.so $SRC/libmtmd.so $SRC/libOCLstub.so; do
  cp $f $OUT/lib/arm64-v8a/
done
# HTP skels: 0906 实测 16KB 重编版 (htp-libs-16k) 导致 NPU 引擎不可用，
# 回滚到 v0.4.3 验证过的原始 skel（4KB 对齐）。16KB 合规将随上游修复恢复。
# 备份的 16KB 版在 /tmp/htp16k-backup/ 与 G14 归档。
# --- v1.2.3: ocl/cpu engine = NDK verified runtime, JNI in-process ---
# 0907 v1.2.2 真机报告：Termux 血统旧栈(libggm*/libllm*)在 8G2 上乱码
# (S23U GPU 乱码) —— 全部移除，ocl 改链 NDK 运行时（v1.1 验证树）：
# JniServer 顺序加载 libggml-base→libggml→libggml-cpu→libggml-opencl→
# libllama→libmtmd→libllama-common→libllama-server-impl→libjnisrv。
# v1.2.4: libggml-opencl 的 DT_NEEDED 在打包后改写为 libOpenCL.so（见下方
# 补丁），JNI 加载经 sphal（manifest uses-native-library）命中真 Adreno
# 驱动；exec 子进程（cpu/htp）由 Java 侧预置 filesDir stub 符号链接兜底。
for f in libggml-base.so libggml.so libggml-cpu.so libggml-opencl.so \
         libllama.so libmtmd.so libllama-common.so libllama-server-impl.so; do
  cp $SRC/$f $OUT/lib/arm64-v8a/
done
# --- JNI shim (NDK-built, links libllama-server-impl) ---
cp $ROOT/build/libjnisrv.so $OUT/lib/arm64-v8a/
# --- v1.2.4: GPU 真可用 —— libggml-opencl 依赖改写（两处 dynstr 就地改） ---
# 必须在两个拷贝循环全部结束后执行（v1.2.3 的 ocl 拷贝循环会复制原始版，
# 补丁若在其之前执行会被覆盖——vc36 首构建实测踩坑）。
# ① libggml-opencl 的 DT_NEEDED/verneed 文件名 libOCLstub.so → libOpenCL.so
#   （12→11 字符补零，两处共用 dynstr 同一命中）：
#   - ocl(JNI) app 进程：走 manifest uses-native-library 的 sphal vendor 通道，
#     命中真 Adreno 驱动（v0.4.3~v1.2.1 验证过的机制）；
#   - cpu/htp exec 子进程：无 sphal 通道，由 Java 在 filesDir/ocl-stub 预置
#     libOpenCL.so -> jniLibs/libOCLstub.so 符号链接并置于 LD_LIBRARY_PATH
#     首位（LlamaServerManager.launchExec）。
# ② libOCLstub.so 的 SONAME libOCLstub.so → libOpenCL.so（同长改写）：
#   vc37 S25 实测：bionic verneed 按「文件名+SONAME 双匹配」定位依赖，
#   只改文件名不改 SONAME → CANNOT LINK "cannot find libOpenCL.so from
#   verneed[0]"，三引擎全灭。文件名+SONAME 一致后匹配必过。
python3 - "$OUT/lib/arm64-v8a/libggml-opencl.so" "$OUT/lib/arm64-v8a/libOCLstub.so" <<'PYOCL'
import sys
p1, p2 = sys.argv[1], sys.argv[2]
d1 = open(p1, 'rb').read()
old = b'libOCLstub.so\x00'
n = d1.count(old)
assert n == 1, 'opencl dynstr anchor not unique: %d' % n
open(p1, 'wb').write(d1.replace(old, b'libOpenCL.so\x00\x00', 1))
d2 = open(p2, 'rb').read()
n2 = d2.count(old)
assert n2 == 1, 'stub dynstr anchor not unique: %d' % n2
open(p2, 'wb').write(d2.replace(old, b'libOpenCL.so\x00', 1))
print('   patched: opencl NEEDED+verneed -> libOpenCL.so; stub SONAME -> libOpenCL.so')
PYOCL
RE=$HOME/llama-apk/sdk/bt34/android-14/llvm-readelf
[ -x "$RE" ] || RE=readelf
$RE -d $OUT/lib/arm64-v8a/libggml-opencl.so | grep -q 'NEEDED.*\[libOpenCL\.so\]' \
  || { echo OPENCL_PATCH_VERIFY_FAILED; exit 1; }
$RE -V $OUT/lib/arm64-v8a/libggml-opencl.so | grep -q 'File: libOpenCL\.so' \
  || { echo OPENCL_VERNEED_VERIFY_FAILED; exit 1; }
$RE -d $OUT/lib/arm64-v8a/libOCLstub.so | grep -q 'SONAME.*\[libOpenCL\.so\]' \
  || { echo STUB_SONAME_VERIFY_FAILED; exit 1; }
$RE -d $OUT/lib/arm64-v8a/libggml-opencl.so | grep -q 'libOCLstub' \
  && { echo OPENCL_PATCH_RESIDUE; exit 1; }
echo "   opencl dependency rewrite verify ok (NEEDED+verneed+stub SONAME)"
N=$(ls $OUT/lib/arm64-v8a | wc -l)
echo "   native libs: $N"
[ -f $OUT/lib/arm64-v8a/libllamaserver.so ] || { echo NO_EXE; exit 1; }
[ -f $OUT/lib/arm64-v8a/libllama-server-impl.so ] || { echo NO_OCL_SRV; exit 1; }
[ -f $OUT/lib/arm64-v8a/libjnisrv.so ] || { echo NO_JNI_SHIM; exit 1; }
[ $N -ge 22 ] || { echo TOO_FEW_LIBS; exit 1; }
(cd $OUT && zip -q -r base.apk lib)

echo "== zipalign =="
$BT/zipalign -f 4 $OUT/base.apk $OUT/aligned.apk

echo "== sign =="
$BT/apksigner sign --ks $KEYS --ks-pass pass:$KS_PASS --key-pass pass:$KS_PASS \
  --ks-key-alias $KEY_ALIAS \
  --out $ROOT/app-release.apk $OUT/aligned.apk
$BT/apksigner verify $ROOT/app-release.apk && echo VERIFY_OK
ls -la $ROOT/app-release.apk
