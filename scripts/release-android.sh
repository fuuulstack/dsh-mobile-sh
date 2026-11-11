#!/usr/bin/env bash
# 把打好的安卓包发布出去，并让 App 的「检查更新」能看到它。
#
# 两种发布目标：
#
#   ① 中继（推荐，2026-11 起）：中继自己托管安装包与版本清单
#        scripts/release-android.sh --relay http://203.0.113.10 0.2.53 "说明"
#        产物：<中继>/api/dsh-mobile-sh-apk/downloads/version.json
#              <中继>/api/dsh-mobile-sh-apk/downloads/dsh-mobile-<版本>.apk
#        鉴权：本机插件的隧道凭据（hostId + hostTunnelToken），按 relayOrigin
#              从 ~/.dsh/.credentials.yaml 里选对那条记录（多台中继时选错就是 403）。
#
#   ② static-host 静态目录（历史行为，保留为回落）：
#        scripts/release-android.sh 0.2.53 "说明"
#        nginx location ^~ /api/ybs/downloads/ 下的 version.json + 安装包 + latest.apk
#
# 用法：
#   scripts/release-android.sh [--relay <url>] [版本号] [发布说明...]
#   scripts/release-android.sh                      # 版本号取 build.gradle，说明留空
#
# 环境变量（中继模式可以全用环境变量，省掉 --relay 与凭据发现）：
#   RELAY_URL / RELAY_HOST_ID / RELAY_HOST_TOKEN
# 静态回落模式：RELEASE_SSH_HOST（目标主机）、RELEASE_BASE_URL（清单地址前缀）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 静态回落目标主机由部署环境注入；默认值只是占位，不含具体机器名。
SSH_HOST="${RELEASE_SSH_HOST:-static-host}"
REMOTE_DIR="/var/www/dsh-mobile"
# 静态回落模式的清单地址前缀；实际部署用 RELEASE_BASE_URL 覆盖，别把地址写回仓库。
BASE_URL="${RELEASE_BASE_URL:-https://downloads.example.test/api/ybs/downloads}"
# 优先发 release 包：debug 包体积大一倍且带 android:debuggable，只作为兜底。
APK="$HERE/android/app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || APK="$HERE/android/app/build/outputs/apk/debug/app-debug.apk"

RELAY_URL="${RELAY_URL:-}"
RELAY_HOST_ID="${RELAY_HOST_ID:-}"
RELAY_HOST_TOKEN="${RELAY_HOST_TOKEN:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --relay) RELAY_URL="${2:-}"; shift 2 ;;
    --relay=*) RELAY_URL="${1#--relay=}"; shift ;;
    -h|--help) sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) break ;;
  esac
done

if [[ ! -f "$APK" ]]; then
  echo "找不到安装包 —— 先构建：" >&2
  echo "  cd android && ./gradlew assembleRelease   # 推荐" >&2
  echo "  cd android && ./gradlew assembleDebug" >&2
  exit 1
fi

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  # build.gradle 里用的是单引号（versionName '0.2.2'），两种引号都要认。
  VERSION="$(grep -oE "versionName\s+['\"][^'\"]+" "$HERE/android/app/build.gradle" | head -1 | sed -E "s/.*['\"]//")"
fi
if [[ -z "$VERSION" ]]; then
  echo "取不到版本号：请在 android/app/build.gradle 里配 versionName，或把版本号当第一个参数传入" >&2
  exit 1
fi
shift || true
NOTES="${*:-}"

SIZE="$(wc -c < "$APK" | tr -d ' ')"
# macOS 自带的是 shasum；发布机的 PATH 里不一定有 coreutils 的 sha256sum。
if command -v sha256sum >/dev/null 2>&1; then
  SHA256="$(sha256sum "$APK" | cut -d' ' -f1)"
else
  SHA256="$(shasum -a 256 "$APK" | cut -d' ' -f1)"
fi
PUBLISHED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
REMOTE_APK="dsh-mobile-${VERSION}.apk"

echo "版本    $VERSION"
echo "大小    $SIZE 字节"
echo "sha256  $SHA256"

urlencode() { python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1"; }

# ---------------------------------------------------------------- 中继模式
if [[ -n "$RELAY_URL" ]]; then
  RELAY_URL="${RELAY_URL%/}"
  # 凭据发现：文件里每台中继都有一条隧道记录，**必须按 relayOrigin 匹配**。
  # 选错那条不是「权限不足」那么直白——它同样是 403 credential-rejected，
  # 看起来像「这台中继不认这台机器」，实际只是拿错了行。
  if [[ -z "$RELAY_HOST_ID" || -z "$RELAY_HOST_TOKEN" ]]; then
    # 为什么把 python 小程序写进临时文件再跑：macOS 自带的是 bash 3.2，它在
    # `$()` 里遇到 heredoc 会把 body 当成代码去解析（实测报「未闭合的单引号」），
    # 而这段 python 里到处是单引号。命令替换 + heredoc 的组合在 3.2 上不可用，
    # 顶层 heredoc 可以——所以先落一个临时脚本。
    SCRATCH="$(mktemp -d)"
    cat > "$SCRATCH/creds.py" <<'PYPARSE'
import sys
path, want = sys.argv[1], sys.argv[2].rstrip('/')
blocks, cur = [], None
try:
    for line in open(path, encoding='utf-8'):
        row = line.rstrip('\n')
        if row.startswith('  ') and not row.startswith('    ') and row.rstrip().endswith(':'):
            cur = {}
            blocks.append(cur)
            continue
        if cur is not None and row.startswith('      ') and ': ' in row:
            key, _, value = row.strip().partition(': ')
            cur[key] = value.strip().strip('"\'')
except OSError:
    pass
pick = None
for block in blocks:
    if not block.get('hostId') or not block.get('hostTunnelToken'):
        continue
    if block.get('relayOrigin', '').rstrip('/') == want:
        pick = block
        break
print(pick.get('hostId', '') if pick else '')
print(pick.get('hostTunnelToken', '') if pick else '')
PYPARSE
    found="$(python3 "$SCRATCH/creds.py" "$HOME/.dsh/.credentials.yaml" "$RELAY_URL")"
    rm -rf "$SCRATCH"
    [[ -n "$RELAY_HOST_ID" ]] || RELAY_HOST_ID="$(sed -n '1p' <<<"$found")"
    [[ -n "$RELAY_HOST_TOKEN" ]] || RELAY_HOST_TOKEN="$(sed -n '2p' <<<"$found")"
  fi
  if [[ -z "$RELAY_HOST_ID" || -z "$RELAY_HOST_TOKEN" ]]; then
    echo "取不到该中继的主机凭据（${RELAY_URL}）。" >&2
    echo "  插件连过这台中继之后 ~/.dsh/.credentials.yaml 才会有对应记录；" >&2
    echo "  也可以直接给 RELAY_HOST_ID / RELAY_HOST_TOKEN 两个环境变量。" >&2
    exit 1
  fi

  QUERY="hostId=$RELAY_HOST_ID&version=$(urlencode "$VERSION")"
  [[ -n "$NOTES" ]] && QUERY="$QUERY&notes=$(urlencode "$NOTES")"
  UPLOAD_URL="$RELAY_URL/control/release?$QUERY"

  echo "目标    ${RELAY_URL}（中继托管）"
  # 「先传包、再写清单」的顺序由服务端在一次请求里保证；脚本这边补的是**交叉校验**：
  # 带上 X-Content-Sha256，传输被截断时服务端当场 400，而不是登记一个装不上的包。
  OUT="$(mktemp)"
  code="$(curl -sS --noproxy '*' --max-time 600 -o "$OUT" -w '%{http_code}' \
    -X POST \
    -H "Authorization: Bearer $RELAY_HOST_TOKEN" \
    -H 'Content-Type: application/vnd.android.package-archive' \
    -H "X-Content-Sha256: $SHA256" \
    --data-binary "@$APK" \
    "$UPLOAD_URL")"
  if [[ "$code" != "200" ]]; then
    echo "上传失败（HTTP ${code}）：" >&2
    cat "$OUT" >&2; rm -f "$OUT"
    exit 1
  fi
  echo "服务端  $(tr -d '\n' < "$OUT")"
  rm -f "$OUT"

  # 自检：清单里的 latest 与 sha256 必须就是刚发的这个。防的是「200 了但清单没变」
  # 这类静默失败——那种情况下 App 会永远提示「已是最新」。
  MANIFEST="$RELAY_URL/api/dsh-mobile-sh-apk/downloads/version.json"
  remote="$(curl -sS --noproxy '*' --max-time 30 "$MANIFEST")"
  python3 - "$remote" "$VERSION" "$SHA256" <<'PY'
import json, sys
raw, version, sha = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    m = json.loads(raw)
except ValueError:
    raise SystemExit(f"清单不是合法 JSON：{raw[:200]}")
if m.get('latest') != version:
    raise SystemExit(f"清单 latest={m.get('latest')!r}，刚发布的是 {version!r}（服务端没接受？）")
if m.get('sha256') != sha:
    raise SystemExit(f"清单 sha256 与本地不一致：{m.get('sha256')} != {sha}")
print(f"清单    latest={m['latest']} size={m['size']} 校验通过")
print(f"下载    {m['url']}")
PY

  echo
  echo "已发布到中继。手机上「检查更新」会直接从这台中继取清单。"
  echo "  清单  $MANIFEST"
  echo "  安装  $RELAY_URL/api/dsh-mobile-sh-apk/downloads/$REMOTE_APK"
  exit 0
fi

# ---------------------------------------------------------------- 静态目录模式（历史行为）
echo "目标    $SSH_HOST:$REMOTE_DIR/$REMOTE_APK"

# 先传包，再写清单：清单是「这个版本可以下载了」的唯一信号，
# 顺序反了会让 App 轮询到一个还不存在的文件。
scp -q "$APK" "$SSH_HOST:/tmp/$REMOTE_APK"

ssh "$SSH_HOST" "sudo mkdir -p $REMOTE_DIR && sudo mv /tmp/$REMOTE_APK $REMOTE_DIR/$REMOTE_APK && sudo chown ec2-user:ec2-user $REMOTE_DIR/$REMOTE_APK"

# 版本清单：App 只读这一个文件判断要不要更新。
# minSupported 留空表示不强制升级；真要卡老版本时在这里填版本号。
ssh "$SSH_HOST" "cat > /tmp/version.json <<JSON
{
  \"latest\": \"$VERSION\",
  \"minSupported\": \"\",
  \"url\": \"$BASE_URL/$REMOTE_APK\",
  \"latestUrl\": \"$BASE_URL/latest.apk\",
  \"size\": $SIZE,
  \"sha256\": \"$SHA256\",
  \"notes\": \"$(printf '%s' "$NOTES" | tr '\n' ' ')\",
  \"publishedAt\": \"$PUBLISHED\"
}
JSON
sudo mv /tmp/version.json $REMOTE_DIR/version.json && sudo chown ec2-user:ec2-user $REMOTE_DIR/version.json
# 固定链接：同一个 inode 的符号链接，不额外占空间，改指向即刻生效。
sudo ln -sfn $REMOTE_DIR/$REMOTE_APK $REMOTE_DIR/latest.apk
ls -l $REMOTE_DIR/"

echo
echo "已发布。可直接下载："
echo "  $BASE_URL/$REMOTE_APK"
echo "  $BASE_URL/latest.apk"
echo "版本清单："
echo "  $BASE_URL/version.json"
