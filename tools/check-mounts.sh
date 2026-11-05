#!/usr/bin/env bash
# 混淆挂载点体检：只读 app/src/main/resources/anchors.json（唯一一份表），
# 用 DexSQL 对目标 APK 逐项核对方法/字段锚点与类锚点。
#
# 用法：
#   tools/check-mounts.sh [APK路径] [--apk APK] [--anchors PATH] [--dexsql PATH] [--aapt2 PATH]
#
# 参数优先级：CLI > 环境变量 > 常见安装路径。
# 环境变量：
#   TARGET_APK / APK  目标 APK（小红书 9.53.0）
#   DEXSQL            dexsql 可执行文件
#   AAPT2             aapt2 可执行文件（可选，仅用于显示 APK versionName）
#
# 缓存固定在 <repo>/tools/.dexsql-cache/（已在 .gitignore 中忽略）。
# 缓存 META 记录 APK 路径 + 大小 + mtime + sha256；任一项不匹配都会自动重建。
#
# 判定规则：
#   OK   = 类和方法都在，hook 大概还能挂上
#   GONE = 类不存在了（R8 改了混淆名），需要按锚点 key 重新定位
#   SIG  = 类还在但方法/参数/返回类型变了，需要看具体差异再决定
#
# 退出码：0 全部 OK；1 锚点表不完整或存在 GONE/SIG；2 参数/工具/APK 缺失。

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="$REPO_ROOT/tools/.dexsql-cache"

TARGET_APK="${TARGET_APK:-${APK:-}}"
ANCHORS="${ANCHORS:-$REPO_ROOT/app/src/main/resources/anchors.json}"
DEXSQL="${DEXSQL:-}"
AAPT2="${AAPT2:-}"
POSITIONAL=""

usage() {
    cat <<'EOF'
Usage:
  tools/check-mounts.sh [APK_PATH] [--apk APK] [--anchors PATH] [--dexsql PATH] [--aapt2 PATH]

Arguments:
  APK_PATH / --apk   目标 APK（小红书 9.53.0）；也可用 TARGET_APK / APK
  --anchors PATH     anchors.json 路径（默认 app/src/main/resources/anchors.json）
  --dexsql PATH      dexsql 可执行文件；也可用 DEXSQL
  --aapt2 PATH       aapt2 可执行文件；也可用 AAPT2（可选，仅显示 versionName）
  -h, --help         显示本帮助

Resolution order: CLI > environment > common install paths.
Cache: <repo>/tools/.dexsql-cache/
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --apk)
            [ $# -ge 2 ] || die "--apk needs a value"
            TARGET_APK="$2"; shift 2 ;;
        --apk=*)
            TARGET_APK="${1#*=}"; shift ;;
        --anchors)
            [ $# -ge 2 ] || die "--anchors needs a value"
            ANCHORS="$2"; shift 2 ;;
        --anchors=*)
            ANCHORS="${1#*=}"; shift ;;
        --dexsql)
            [ $# -ge 2 ] || die "--dexsql needs a value"
            DEXSQL="$2"; shift 2 ;;
        --dexsql=*)
            DEXSQL="${1#*=}"; shift ;;
        --aapt2)
            [ $# -ge 2 ] || die "--aapt2 needs a value"
            AAPT2="$2"; shift 2 ;;
        --aapt2=*)
            AAPT2="${1#*=}"; shift ;;
        -h|--help)
            usage; exit 0 ;;
        -*)
            echo "ERROR: unknown option $1" >&2
            usage >&2
            exit 2 ;;
        *)
            if [ -n "$POSITIONAL" ]; then
                die "unexpected argument $1"
            fi
            POSITIONAL="$1"; shift ;;
    esac
done

# ---- 工具定位辅助 ---------------------------------------------------------
is_executable_file() {
    local path="$1"
    [ -n "$path" ] && [ -f "$path" ] || return 1
    case "$path" in
        *.exe|*.EXE) return 0 ;;
    esac
    [ -x "$path" ]
}

first_executable() {
    local candidate
    for candidate in "$@"; do
        if is_executable_file "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

sdk_roots() {
    local value line
    for value in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [ -n "$value" ] && printf '%s\n' "$value"
    done
    if [ -f "$REPO_ROOT/local.properties" ]; then
        line="$(grep -E '^[[:space:]]*sdk\.dir[[:space:]]*=' \
            "$REPO_ROOT/local.properties" | tail -n 1 || true)"
        value="${line#*=}"
        value="$(printf '%s' "$value" \
            | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
                  -e 's/\\\\/\\/g' -e 's/\\:/:/g')"
        [ -n "$value" ] && printf '%s\n' "$value"
    fi
    printf '%s\n' \
        "$HOME/Android/Sdk" \
        "$HOME/Library/Android/sdk" \
        "$HOME/AppData/Local/Android/Sdk" \
        "/usr/lib/android-sdk" \
        "/opt/android-sdk"
}

# ---- APK 定位 -------------------------------------------------------------
if [ -n "$POSITIONAL" ]; then
    TARGET_APK="$POSITIONAL"
fi
if [ -z "$TARGET_APK" ]; then
    for candidate in \
        "$PWD"/base*.apk \
        "$HOME/Downloads"/base*.apk \
        "$HOME/Downloads"/*xhs*.apk \
        "$HOME/Downloads"/*xiaohongshu*.apk \
        "$HOME/Desktop"/base*.apk; do
        if [ -f "$candidate" ]; then
            TARGET_APK="$candidate"
            echo "APK (auto-detected): $TARGET_APK" >&2
            break
        fi
    done
fi

# ---- dexsql / aapt2 定位 --------------------------------------------------
if [ -z "$DEXSQL" ]; then
    if command -v dexsql >/dev/null 2>&1; then
        DEXSQL="$(command -v dexsql)"
    elif command -v dexsql.exe >/dev/null 2>&1; then
        DEXSQL="$(command -v dexsql.exe)"
    else
        DEXSQL="$(first_executable \
            "$REPO_ROOT/tools/dexsql/dexsql" \
            "$REPO_ROOT/tools/dexsql/dexsql.exe" \
            "$REPO_ROOT/tools/dexsql.exe" \
            "$HOME/tools/dexsql/dexsql" \
            "$HOME/tools/dexsql/dexsql.exe" \
            "$HOME/.local/bin/dexsql" \
            "$HOME/bin/dexsql" \
            "/usr/local/bin/dexsql" \
            "/opt/dexsql/dexsql" \
            "/e/tools/dexsql/dexsql.exe" || true)"
    fi
fi

if [ -z "$AAPT2" ]; then
    if command -v aapt2 >/dev/null 2>&1; then
        AAPT2="$(command -v aapt2)"
    else
        while IFS= read -r sdk_root; do
            [ -n "$sdk_root" ] || continue
            AAPT2="$(first_executable \
                "$sdk_root"/build-tools/*/aapt2.exe \
                "$sdk_root"/build-tools/*/aapt2 || true)"
            [ -n "$AAPT2" ] && break
        done < <(sdk_roots)
    fi
fi

# ---- 输入校验 -------------------------------------------------------------
if [ -z "$TARGET_APK" ] || [ ! -f "$TARGET_APK" ]; then
    echo "ERROR: APK not found: ${TARGET_APK:-<unset>}" >&2
    echo "  Fix: pass --apk /path/to/xhs.apk, or export TARGET_APK=/path/to/xhs.apk," >&2
    echo "       or place base*.apk in the current directory / ~/Downloads." >&2
    exit 2
fi
if [ ! -f "$ANCHORS" ]; then
    echo "ERROR: anchors.json not found: $ANCHORS" >&2
    exit 2
fi
if [ -z "$DEXSQL" ] \
    || { ! is_executable_file "$DEXSQL" && ! command -v "$DEXSQL" >/dev/null 2>&1; }; then
    echo "ERROR: dexsql not found: ${DEXSQL:-<unset>}" >&2
    echo "  Fix: install dexsql and put it on PATH, or pass --dexsql /path/to/dexsql," >&2
    echo "       or export DEXSQL=/path/to/dexsql." >&2
    exit 2
fi
if [ -n "$AAPT2" ] && ! is_executable_file "$AAPT2"; then
    echo "WARN: aapt2 is not executable: $AAPT2; skipping versionName display" >&2
    AAPT2=""
fi

echo
echo "APK: $TARGET_APK"
echo "anchors: $ANCHORS"
if is_executable_file "$AAPT2"; then
    VERSION_NAME="$("$AAPT2" dump badging "$TARGET_APK" 2>/dev/null \
        | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
    if [ -n "$VERSION_NAME" ]; then
        echo "versionName: $VERSION_NAME"
        [ "$VERSION_NAME" = "9.53.0" ] \
            || echo "WARN: expected 9.53.0, got $VERSION_NAME" >&2
    else
        echo "WARN: cannot read versionName from $TARGET_APK" >&2
    fi
else
    echo "WARN: aapt2 not found; skipping versionName display" >&2
fi

# Git Bash 下 /tmp、/e/... 这类 MSYS 路径 Python 无法直接识别，先转成 Windows 路径。
to_native_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w -- "$1" 2>/dev/null || printf '%s' "$1"
    else
        printf '%s' "$1"
    fi
}
APK_NATIVE="$(to_native_path "$TARGET_APK")"
ANCHORS_NATIVE="$(to_native_path "$ANCHORS")"
DEXSQL_NATIVE="$(to_native_path "$DEXSQL")"

PYTHON="${PYTHON:-python}"
command -v "$PYTHON" >/dev/null 2>&1 \
    || die "未找到 python；安装 Python 3 或设置 PYTHON 环境变量"

CHECK_MOUNTS_REPO_ROOT="$REPO_ROOT" \
CHECK_MOUNTS_APK="$APK_NATIVE" \
CHECK_MOUNTS_ANCHORS="$ANCHORS_NATIVE" \
CHECK_MOUNTS_DEXSQL="$DEXSQL_NATIVE" \
CHECK_MOUNTS_CACHE="$CACHE_DIR" \
"$PYTHON" -I - <<'PY'
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(os.environ["CHECK_MOUNTS_REPO_ROOT"])
APK = Path(os.environ["CHECK_MOUNTS_APK"])
ANCHORS_PATH = Path(os.environ["CHECK_MOUNTS_ANCHORS"])
DEXSQL = os.environ["CHECK_MOUNTS_DEXSQL"]
CACHE_DIR = Path(os.environ["CHECK_MOUNTS_CACHE"])

SCHEMA_VERSION = 1
CHECKLIST_SIZE = 64
TARGET_PACKAGE = "com.xingin.xhs"

errors = []


def out(message=""):
    print(message)


def fail(message):
    errors.append(message)
    print("FAIL: %s" % message)


def warn(message):
    print("WARN: %s" % message)


def load_json_no_duplicates(path):
    def no_duplicates(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("重复键：%s" % key)
            result[key] = value
        return result

    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=no_duplicates)


# ------------------------------------------------------------ 锚点表完整性
out("==> anchors: %s" % ANCHORS_PATH)
try:
    payload = load_json_no_duplicates(ANCHORS_PATH)
except (OSError, ValueError, json.JSONDecodeError) as exc:
    out("FAIL: 无法解析 anchors.json：%s" % exc)
    raise SystemExit(1)

if not isinstance(payload, dict):
    fail("anchors.json 顶层不是对象")
    payload = {}

app_version = payload.get("appVersion")
version_code = payload.get("versionCode")
schema_version = payload.get("schemaVersion")
generated_by = payload.get("generatedBy")
anchors = payload.get("anchors")
classes = payload.get("classes")

if isinstance(schema_version, bool) or schema_version != SCHEMA_VERSION:
    fail("schemaVersion 缺失或不是 %d：%r" % (SCHEMA_VERSION, schema_version))
if not isinstance(app_version, str) or not app_version.strip():
    fail("appVersion 缺失或不是非空字符串")
if isinstance(version_code, bool) or not isinstance(version_code, int) or version_code <= 0:
    fail("versionCode 缺失或不是正整数")
if generated_by != "tools/gen_anchors.py":
    fail("generatedBy 不是 tools/gen_anchors.py：%r" % generated_by)
if not isinstance(anchors, dict):
    fail("anchors 缺失或不是对象")
    anchors = {}
if not isinstance(classes, dict):
    fail("classes 缺失或不是对象")
    classes = {}

overlap = sorted(set(anchors) & set(classes))
if overlap:
    fail("anchors 与 classes 存在同名 key：%s" % ", ".join(overlap))

for key, entry in sorted(anchors.items()):
    if not isinstance(key, str) or not key:
        fail("锚点 key 非法：%r" % (key,))
        continue
    if not isinstance(entry, dict):
        fail("锚点 %s 不是对象" % key)
        continue
    for field in ("c", "m", "s", "feature", "note"):
        value = entry.get(field)
        if not isinstance(value, str) or not value:
            fail("锚点 %s 缺少必填字段 %s" % (key, field))
    if not isinstance(entry.get("critical"), bool):
        fail("锚点 %s 的 critical 不是布尔值" % key)
    feature = entry.get("feature")
    if isinstance(feature, str) and feature \
            and not feature.startswith(("AD-", "UI-", "PV-", "EN-", "EX-")):
        fail("锚点 %s 的 feature 不是统一功能编号：%r" % (key, feature))

for key, cls in sorted(classes.items()):
    if not isinstance(key, str) or not key:
        fail("类锚点 key 非法：%r" % (key,))
    if not isinstance(cls, str) or not cls:
        fail("类锚点 %s 不是非空字符串" % key)

if len(anchors) != CHECKLIST_SIZE:
    fail("anchors 条目数 %d，必须覆盖 check-mounts 的 %d 项"
         % (len(anchors), CHECKLIST_SIZE))

# 版本元数据与 README 首屏目标版本保持一致。
try:
    readme_text = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
except OSError:
    readme_text = ""
match = re.search(r"适配小红书\s*([0-9][0-9.]*)\s*（([0-9]+)）", readme_text)
if match:
    readme_version, readme_code = match.group(1), int(match.group(2))
    if app_version != readme_version or version_code != readme_code:
        fail("anchors.json 版本元数据 %s/%s 与 README 目标版本 %s/%s 不一致"
             % (app_version, version_code, readme_version, readme_code))
else:
    warn("未能从 README.md 解析目标版本，跳过版本元数据对照")

out("==> 锚点表：%d anchors + %d classes（schema v%d，appVersion %s / versionCode %s）"
    % (len(anchors), len(classes), schema_version, app_version, version_code))
if not errors:
    out("==> 锚点表完整性 OK（schema、必填字段、feature/critical、版本元数据）")

# ------------------------------------------------------------ DexSQL 缓存
def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_cache():
    cache_file = CACHE_DIR / "cache.dxc2"
    meta_path = CACHE_DIR / "apk.meta"
    size = APK.stat().st_size
    digest = sha256_of(APK)
    reusable = False
    if cache_file.is_file() and meta_path.is_file():
        meta = {}
        for line in meta_path.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                meta[key] = value
        reusable = (meta.get("path") == str(APK.resolve())
                    and meta.get("size") == str(size)
                    and meta.get("sha256") == digest)
    if reusable:
        return cache_file

    shutil.rmtree(CACHE_DIR, ignore_errors=True)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    out("==> 首次为该 apk 建 DexSQL 缓存（约 40 秒）...")
    result = subprocess.run(
        [DEXSQL, "prepare-cache", "--input", str(APK),
         "--output", str(CACHE_DIR), "--trusted-input"],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0 or not cache_file.is_file():
        out("FAIL: dexsql prepare-cache failed")
        print((result.stderr or result.stdout)[:2000])
        raise SystemExit(1)
    meta_path.write_text(
        "version=2\npath=%s\nsize=%s\nmtime=%s\nsha256=%s\n"
        % (APK.resolve(), size, int(APK.stat().st_mtime), digest),
        encoding="utf-8")
    return cache_file


def query_count(cache_file, sql):
    result = subprocess.run(
        [DEXSQL, "query-cache", "--cache-file", str(cache_file),
         "--trusted-input", "--sql", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    rows = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if not line.startswith("|") or set(line) <= set("|-+ "):
            continue
        rows.append([cell.strip() for cell in line.strip("|").split("|")])
    if not rows:
        return 0
    try:
        return int(rows[-1][0])
    except (ValueError, IndexError):
        return 0


cache_file = ensure_cache()
out("==> 缓存: %s" % cache_file)

# ------------------------------------------------------------ 逐项校验
method_ok = method_sig = method_gone = 0
field_ok = field_sig = field_gone = 0
class_ok = class_gone = 0

for key, entry in sorted(anchors.items()):
    is_method = entry["s"].startswith("(")
    table = "methods" if is_method else "fields"
    if is_method:
        descriptor = "L%s;->%s%s" % (
            entry["c"].replace(".", "/"), entry["m"], entry["s"])
    else:
        descriptor = "L%s;->%s:%s" % (
            entry["c"].replace(".", "/"), entry["m"], entry["s"])
    try:
        count = query_count(
            cache_file, "SELECT count(*) AS c FROM %s WHERE descriptor='%s'"
                        % (table, descriptor))
    except RuntimeError as exc:
        fail("dexsql 查询%s %s 失败：%s"
             % ("方法" if is_method else "字段", key, exc))
        continue
    if count > 0:
        if is_method:
            method_ok += 1
        else:
            field_ok += 1
        continue
    try:
        class_count = query_count(
            cache_file, "SELECT count(*) AS c FROM types WHERE java_name='%s'"
                        % entry["c"])
    except RuntimeError as exc:
        fail("dexsql 查询类 %s 失败：%s" % (entry["c"], exc))
        continue
    if class_count > 0:
        if is_method:
            method_sig += 1
        else:
            field_sig += 1
        print("SIG %s: %s" % (key, descriptor))
    else:
        if is_method:
            method_gone += 1
        else:
            field_gone += 1
        print("GONE %s: %s" % (key, descriptor))

for key, cls in sorted(classes.items()):
    try:
        count = query_count(
            cache_file, "SELECT count(*) AS c FROM types WHERE java_name='%s'" % cls)
    except RuntimeError as exc:
        fail("dexsql 查询类 %s 失败：%s" % (key, exc))
        continue
    if count > 0:
        class_ok += 1
    else:
        class_gone += 1
        print("GONE class %s: %s" % (key, cls))

if method_sig or method_gone:
    fail("方法锚点未全命中：SIG %d / GONE %d" % (method_sig, method_gone))
if field_sig or field_gone:
    fail("字段锚点未全命中：SIG %d / GONE %d" % (field_sig, field_gone))
if class_gone:
    fail("类锚点未全命中：GONE %d" % class_gone)

out("==> 结果：methods OK %d/%d（SIG %d / GONE %d）；"
    "fields OK %d/%d（SIG %d / GONE %d）；classes OK %d/%d（GONE %d）"
    % (method_ok, method_ok + method_sig + method_gone, method_sig, method_gone,
       field_ok, field_ok + field_sig + field_gone, field_sig, field_gone,
       class_ok, class_ok + class_gone, class_gone))
if errors:
    out("==> 结果：FAIL（%d 项）" % len(errors))
    raise SystemExit(1)
out("==> check-mounts OK")
raise SystemExit(0)
PY
exit $?
