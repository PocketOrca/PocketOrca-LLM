#!/bin/bash
# T9: build-verify.sh (spec §1.1) — post-build APK integrity check.
# Usage: bash build-verify.sh [apk-path]
set -euo pipefail
APK=${1:-/home/dawoer/npu-llm-apk/app-debug.apk}
WORK=$(mktemp -d)
trap 'rm -rf $WORK' EXIT
FAIL=0

echo "== verify: $APK =="

# 1. manifest essentials
AAPT2="$HOME/llama-apk/sdk/bt34/android-14/aapt2"
"$AAPT2" dump badging "$APK" 2>/dev/null | grep -q "package: name='com.dawoer.npullm'" || { echo 'FAIL: package name'; FAIL=1; }
unzip -p "$APK" AndroidManifest.xml > "$WORK/manifest.bin" 2>/dev/null
# binary axml: foregroundServiceType is a compiled int mask (dataSync=1)
if "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null | grep -q 'foregroundServiceType.*=0x00000001'; then
  echo "manifest: FGS dataSync(0x1) ok"
else
  echo 'FAIL: FGS dataSync type missing'; FAIL=1
fi
if "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null | grep -q 'uses-native-library'; then
  echo "manifest: uses-native-library ok"
else
  echo 'FAIL: uses-native-library missing (ocl profile would break)'; FAIL=1
fi

# 2. native libs: 42 expected, and libOpenCL.so must NOT be packaged (v0.4.3 design)
unzip -q -o "$APK" 'lib/*' -d "$WORK"
N=$(ls "$WORK/lib/arm64-v8a" | wc -l)
echo "native libs: $N"
[ "$N" -ge 30 ] || { echo 'FAIL: too few libs'; FAIL=1; }
for lib in libllamaserver.so libllama-server-impl.so libjnisrv.so libOCLstub.so libggml-htp-v73.so libggml-opencl.so libggml-cpu.so libggml-hexagon.so libllama-common.so; do
  [ -f "$WORK/lib/arm64-v8a/$lib" ] || { echo "FAIL: missing $lib"; FAIL=1; }
done
# v1.2.3: vendor closure MUST be packaged (S25 A15 linker ns does not expose
# vendor libs to /data binaries) + full-symbol libc++.so (S23U OneUI6.1 lacks
# _ZTVNSt3__114basic_ifstream). Closure serves the exec child only.
for lib in libcdsprpc.so libvmmem.so libhidlbase.so libhardware.so libutils.so libcutils.so libdmabufheap.so libbase.so vendor.qti.hardware.dsp-V1-ndk.so android.hardware.common-V2-ndk.so libc++.so; do
  [ -f "$WORK/lib/arm64-v8a/$lib" ] || { echo "FAIL: $lib missing (v1.2.3 vendor closure required)"; FAIL=1; }
done
# old Termux-lineage stack must be GONE (8Gen2 garbled-text root cause)
for lib in libllmoclsrv.so libllmcom.so libllm.so libmtmdp.so libggmbase.so libggmcpu.so libggmoclp.so libggm.so; do
  [ -f "$WORK/lib/arm64-v8a/$lib" ] && { echo "FAIL: $lib still packaged (Termux stack removed in v1.2.3)"; FAIL=1; }
done
[ -f "$WORK/lib/arm64-v8a/libOpenCL.so" ] && { echo 'FAIL: libOpenCL.so packaged (breaks ocl sphal channel)'; FAIL=1; }

# v1.2.4: GPU 真可用断言 —— libggml-opencl 必须直接 NEED libOpenCL.so（sphal
# 通道入口），绝不能再引用打包空壳 libOCLstub（v1.2.3 GPU 假可用根因）。
RE124="$HOME/llama-apk/sdk/bt34/android-14/llvm-readelf"
[ -x "$RE124" ] || RE124=readelf
if "$RE124" -d "$WORK/lib/arm64-v8a/libggml-opencl.so" 2>/dev/null | grep -q 'NEEDED.*\[libOpenCL\.so\]'; then
  echo "v1.2.4: libggml-opencl NEEDs libOpenCL.so (sphal channel) ok"
else
  echo 'FAIL: libggml-opencl does not NEED libOpenCL.so (GPU would fall back to CPU)'; FAIL=1
fi
if "$RE124" -d "$WORK/lib/arm64-v8a/libggml-opencl.so" 2>/dev/null | grep -q 'libOCLstub'; then
  echo 'FAIL: libggml-opencl still references libOCLstub (stub-shell trap)'; FAIL=1
fi
# vc37 S25 实测：verneed 按「文件名+SONAME 双匹配」——stub SONAME 必须同步改写，
# verneed 文件名必须指向 libOpenCL.so，否则 exec 三引擎 CANNOT LINK 全灭。
if "$RE124" -V "$WORK/lib/arm64-v8a/libggml-opencl.so" 2>/dev/null | grep -q 'File: libOpenCL\.so'; then
  echo "v1.2.4: opencl verneed File: libOpenCL.so ok"
else
  echo 'FAIL: verneed file-name mismatch (bionic CANNOT LINK on device)'; FAIL=1
fi
STUB_SONAME=$("$RE124" -d "$WORK/lib/arm64-v8a/libOCLstub.so" 2>/dev/null | grep SONAME | sed 's/.*\[\(.*\)\]/\1/')
if [ "$STUB_SONAME" = "libOpenCL.so" ]; then
  echo "v1.2.4: stub SONAME=libOpenCL.so ok (verneed dual-match)"
else
  echo "FAIL: stub SONAME=$STUB_SONAME must be libOpenCL.so (verneed dual-match)"; FAIL=1
fi
# JNI shim 必须显式依赖 libllama-server-impl（vc38 回归教训：重编丢了该 NEEDED
# → dlopen 时 llama_server 符号无处归属 → UnsatisfiedLinkError）
"$RE124" -d "$WORK/lib/arm64-v8a/libjnisrv.so" 2>/dev/null | grep -q 'NEEDED.*\[libllama-server-impl\.so\]' \
  || { echo 'FAIL: libjnisrv.so missing NEEDED libllama-server-impl.so (llama_server unresolvable)'; FAIL=1; }

# 通用不变式（vc37/vc38 两种死法的统一根因）：bionic 解析任何依赖时按
# 「NEEDED 文件名 ↔ 目标库 SONAME」双匹配。包内任一库被其他库按名 NEED 时，
# 目标文件的 SONAME（若有）必须等于被引用的文件名，否则设备上必然
# CANNOT LINK / 符号无法绑定（如 libc++ SONAME=libc++_shared 但被 NEED 作
# libc++.so → liblog 的 C++ 符号全部无法绑定）。
python3 - "$WORK/lib/arm64-v8a" "$RE124" <<'PYSONAME'
import subprocess, sys, glob, os, re
libdir, re_ = sys.argv[1], sys.argv[2]
WHITELIST = {'libm.so','libdl.so','libc.so','liblog.so','libOpenCL.so','libc++.so'}
def fields(path):
    out = subprocess.run([re_, '-d', path], capture_output=True, text=True).stdout
    needed, soname = [], None
    for line in out.splitlines():
        m = re.search(r'NEEDED\)\s+Shared library: \[(.*)\]', line)
        if m: needed.append(m.group(1))
        m2 = re.search(r'SONAME\)\s+Library soname: \[(.*)\]', line)
        if m2: soname = m2.group(1)
    return needed, soname
bad = []
for p in sorted(glob.glob(libdir + '/*.so')):
    needed, _ = fields(p)
    for n in needed:
        if n in WHITELIST: continue
        f = os.path.join(libdir, n)
        if not os.path.exists(f): continue  # 缺失由 NEEDED closure 检查负责
        _, son = fields(f)
        if son and son != n:
            bad.append('%s NEEDs %s but target SONAME=%s (bionic dual-match violation)' % (os.path.basename(p), n, son))
if bad:
    for b in bad: print('FAIL: ' + b)
    sys.exit(1)
print('bionic dual-match invariant: all packaged NEEDED targets SONAME-consistent')
PYSONAME
[ $? -eq 0 ] || FAIL=1

# 3. assets present & current
unzip -q -o "$APK" 'assets/*' -d "$WORK"
for a in index.html app.js profiles.json; do
  [ -s "$WORK/assets/$a" ] || { echo "FAIL: asset $a missing/empty"; FAIL=1; }
done
grep -q 'welcomeInit' "$WORK/assets/app.js" || { echo 'FAIL: app.js stale (no welcomeInit)'; FAIL=1; }
# minified JS sanity: entry points must survive terser
for fn in switchTab onMainButton chatSend cliSend welcomeAccept confirmOk clearLog onChatChunk onChatDone; do
  grep -qE "function $fn\b|$fn=function|window\.$fn" "$WORK/assets/app.js" || { echo "FAIL: minified JS lost entry point $fn"; FAIL=1; }
done

# 3b. 16KB page-size compliance (handles 64-bit AND 32-bit Hexagon ELF)
python3 - "$WORK/lib/arm64-v8a" <<'PYCHK'
import struct, glob, os, sys
def maxalign(path):
    data = open(path,'rb').read()
    if data[:4] != b'\x7fELF': return -1
    if data[4] == 2:
        off = struct.unpack_from('<Q', data, 0x20)[0]
        entsz = struct.unpack_from('<H', data, 0x36)[0]
        num = struct.unpack_from('<H', data, 0x38)[0]
        aoff, fmt = 0x30, '<Q'
    else:
        off = struct.unpack_from('<I', data, 0x1C)[0]
        entsz = struct.unpack_from('<H', data, 0x2A)[0]
        num = struct.unpack_from('<H', data, 0x2C)[0]
        aoff, fmt = 0x1C, '<I'
    m = 0
    for i in range(num):
        o = off + i*entsz
        if struct.unpack_from('<I', data, o)[0] != 1: continue
        m = max(m, struct.unpack_from(fmt, data, o+aoff)[0])
    return m
bad = [os.path.basename(p) for p in sorted(glob.glob(sys.argv[1] + '/*.so')) if maxalign(p) < 16384]
# Hexagon HTP skels are QURT DSP objects: 4KB aligned by Qualcomm toolchain,
# loaded by the hexagon loader (not the Android kernel page table).
# 0906 实测：16KB 重编版导致 NPU 引擎不可用，回滚 0.4.3 原版（4KB）。
# Play 16KB 政策针对 Android runtime 加载的 ELF；DSP skel 走 FastRPC 通道，
# 待上游 (ggml-hexagon) 适配后恢复检查。
HTP_SKELS = {'libggml-htp-v73.so','libggml-htp-v75.so','libggml-htp-v79.so','libggml-htp-v81.so'}
bad = [n for n in bad if n not in HTP_SKELS]
if bad:
    print('FAIL: not 16KB-aligned: ' + ', '.join(bad)); sys.exit(1)
print('16KB page-size: all Android-loaded libs compliant (HTP DSP skels exempt by design)')
PYCHK

# 4. NEEDED closure: every non-system dependency must exist in jniLibs
RE="$HOME/llama-apk/sdk/bt34/android-14/llvm-readelf"
[ -x "$RE" ] || RE=readelf
WHITELIST='libm\.so|libdl\.so|libc\.so|liblog\.so|libstdc..\.so|libz\.so|libc.._shared\.so|libc..\.so|libnativewindow\.so|libEGL\.so|libGLESv2\.so|libvulkan\.so|libandroid\.so|libOpenSLES\.so|libaaudio\.so|libamidi\.so|libicu_jni\.so|libjnigraphics\.so|libmediandk\.so|libnativehelper\.so|libwebviewchromium_plat_support\.so|libRS\.so|libsync\.so|libvndksupport\.so|libion\.so|libbinder_ndk\.so|libOpenCL\.so|libOpenCL_adreno\.so|libgcc\.so'
# design notes for whitelisted-but-not-packaged libs:
# - libOpenCL.so: sphal design — manifest <uses-native-library> pulls the real
#   vendor libOpenCL.so for the ocl JNI profile; jniLibs must NOT contain a
#   libOpenCL.so-named file. v1.2.4: libggml-opencl itself NEEDs libOpenCL.so;
#   exec children (cpu/htp) satisfy it via a filesDir symlink to the renamed
#   stub (libOCLstub.so) pre-seeded by LlamaServerManager.launchExec.
MISSING=0
for so in "$WORK"/lib/arm64-v8a/*.so; do
  while IFS= read -r need; do
    base=$(basename "$need")
    [ -f "$WORK/lib/arm64-v8a/$base" ] && continue
    echo "$base" | grep -qE "^($WHITELIST)$" && continue
    echo "NEEDED-unresolved: $base (required by $(basename "$so"))"
    MISSING=1
  done < <("$RE" -d "$so" 2>/dev/null | grep NEEDED | sed 's/.*Shared library: \[\(.*\)\]/\1/' | sed 's/[^A-Za-z0-9_.-]/./g')
done
[ "$MISSING" -eq 0 ] || { echo 'FAIL: NEEDED closure broken'; FAIL=1; }

echo
if [ "$FAIL" -eq 0 ]; then echo 'VERIFY: ALL OK'; else echo 'VERIFY: FAILED'; exit 1; fi
