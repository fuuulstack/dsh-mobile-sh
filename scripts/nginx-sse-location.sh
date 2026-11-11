#!/bin/bash
# 给 SSE 变更流加一条专用 location：显式关缓冲、拉长读超时。
# 现有 location / 虽然已经 proxy_buffering off，但 SSE 对「不缓冲 + 不压缩 + 长超时」
# 更敏感，单独写出来以后不必再靠默认行为猜。
#
# 注意：nginx 的 location 正则里，量词花括号必须转义成 \{32\}，否则 nginx 把 {32}
# 当成配置块起始，直接 emerg 拒绝加载（第一次就踩了）。
set -euo pipefail
CONF=/etc/nginx/sites-enabled/relay
BACKUP=/root/nginx-relay-$(date +%Y%m%d-%H%M%S).conf

if grep -q 'proxy_cache off' "$CONF"; then
  echo "已经存在 changes 专用 location，跳过"
  exit 0
fi

cp "$CONF" "$BACKUP"
echo "备份: $BACKUP"

python3 - "$CONF" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
block = '''
    # 会话变更流（SSE）：必须完全关掉缓冲，否则变更会攒在 nginx 里等连接关闭。
    # 超时拉到 1 小时，客户端自己有 retry/退避，不需要 nginx 主动掐。
    location ~ ^/native/v2/hosts/[a-f0-9]\\{32\\}/changes$ {
        proxy_pass http://127.0.0.1:8088;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_cache off;
        gzip off;
        chunked_transfer_encoding on;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
'''
marker = '    location / {\n        proxy_pass http://127.0.0.1:8088;'
if marker not in text:
    raise SystemExit('找不到 location / 的锚点，放弃修改')
text = text.replace(marker, block + marker, 1)
open(path, 'w', encoding='utf-8').write(text)
print('已插入 changes location')
PY

nginx -t
systemctl reload nginx
echo "reload 完成"
