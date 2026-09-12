#!/usr/bin/env bash
#
# 给 HanimeViewer 装一个「手机 App 直连」的代理服务。
#
# 背景：App 支持 HTTP / SOCKS5 代理，并且支持**用户名 + 密码认证**
# （见 logic/network/HProxyAuthenticator.kt）。所以在自己的 VPS 上跑一个
# 带认证的 SOCKS5 + HTTP 代理，手机端只要在「设置 → 网络 → 代理」里填
# 「服务器IP / 端口 / 用户名 / 密码」就能用，不需要再装任何梯子 App。
#
# ⚠️ 为什么不是 MTProto：MTProto 代理是 **Telegram 专用协议**，服务端收到连接后
# 只会把它转发到 Telegram 的数据中心，不具备「转发任意 HTTP 流量」的能力。
# 所以任何非 Telegram 的 App 都无法使用 MTProto 代理 —— 这不是本 App 的限制，
# 是协议本身的设计。要在 App 里实现 MTProto 级别的伪装，需要 App 自己实现
# VLESS/Trojan/Hysteria 这类隧道（要内置原生内核），那是另一个量级的工程。
#
# ⚠️ 为什么用 `-L` 命令行而不是 YAML 配置文件：
#   gost **v3 改了配置键名**（v2 是 `auth.type` + `auth.users`，v3 是 `auther` + `auths`）。
#   若沿用 v2 的写法，gost **不报错、不警告，直接忽略整个认证段** —— 结果就是
#   一个挂在公网上的**开放代理**，几分钟内就会被扫到并滥用。
#   `-L socks5://user:pass@:port` 这种 URL 内嵌凭据的写法在 v2/v3 都有效，
#   没有版本歧义，所以这里统一用它。脚本末尾还会**强制验证认证真的生效**
#   （不带凭据必须被 407 拒绝），验证不过就报错退出。
#
# 用法（在 VPS 上以 root 执行）：
#   bash setup-app-proxy.sh
#   SOCKS_PORT=7890 HTTP_PORT=7891 PROXY_USER=me bash setup-app-proxy.sh
#
set -euo pipefail

SOCKS_PORT="${SOCKS_PORT:-7890}"
HTTP_PORT="${HTTP_PORT:-7891}"
PROXY_USER="${PROXY_USER:-hanime}"
PROXY_PASS="${PROXY_PASS:-$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)}"

# 凭据会内嵌进 URL，含特殊字符（@ : / 等）会把 URL 解析搞坏，所以只留字母数字。
PROXY_USER="$(printf '%s' "$PROXY_USER" | tr -dc 'A-Za-z0-9')"
PROXY_PASS="$(printf '%s' "$PROXY_PASS" | tr -dc 'A-Za-z0-9')"
[[ ${#PROXY_USER} -ge 1 ]] || { printf '\033[31m[x]\033[0m PROXY_USER 不能为空\n' >&2; exit 1; }
[[ ${#PROXY_PASS} -ge 12 ]] || { printf '\033[31m[x]\033[0m 密码太短（去掉特殊字符后不足 12 位），请换一个\n' >&2; exit 1; }

INSTALL_DIR="/usr/local/bin"
UNIT_FILE="/etc/systemd/system/gost.service"

log()  { printf '\033[32m[+]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "请用 root 运行（sudo bash $0）"

# ---------------------------------------------------------------- 0. 幂等处理
# 重跑本脚本（换密码 / 换端口）时，上一次装的服务还占着端口 —— 先停掉自己，
# 否则下面的端口体检会把「自己」当成冲突然后拒绝执行。
if systemctl is-active --quiet gost.service 2>/dev/null; then
    log "检测到已存在的 gost 服务，先停止以便重新配置"
    systemctl stop gost.service
    sleep 1
fi

# ---------------------------------------------------------------- 1. 端口体检
# 这两个端口是「先看再动」：机器上可能已经有服务在跑（比如 xray、docker 映射），
# 直接覆盖会把人家的服务打断，所以发现被占用就报错退出，让用户自己选端口。
check_port() {
    local port="$1" who
    who="$(ss -lntp 2>/dev/null | awk -v p=":${port}$" '$4 ~ p {print $NF; exit}' || true)"
    if [[ -n "$who" ]]; then
        warn "端口 ${port} 已被占用：${who}"
        warn "换个端口重跑，例如：SOCKS_PORT=10808 HTTP_PORT=10809 bash $0"
        return 1
    fi
    return 0
}
log "检查端口占用情况"
check_port "$SOCKS_PORT" || exit 1
check_port "$HTTP_PORT" || exit 1

# ---------------------------------------------------------------- 2. 装 gost
# 选 gost 的理由：单文件静态二进制、零依赖、原生支持 SOCKS5/HTTP 的
# 用户名密码认证，systemd 一条 unit 就能常驻。
if [[ -x "${INSTALL_DIR}/gost" ]]; then
    log "gost 已存在：$(${INSTALL_DIR}/gost -V 2>&1 | head -1)"
else
    log "下载 gost"
    ARCH="$(uname -m)"
    case "$ARCH" in
        x86_64|amd64) GOARCH=amd64 ;;
        aarch64|arm64) GOARCH=arm64 ;;
        *) die "不支持的架构：$ARCH" ;;
    esac
    VER="$(curl -fsSL https://api.github.com/repos/go-gost/gost/releases/latest \
            | grep -oP '"tag_name":\s*"\K[^"]+' | head -1)"
    [[ -n "$VER" ]] || die "拿不到 gost 版本号（服务器能访问 GitHub 吗？）"
    TMP="$(mktemp -d)"
    curl -fsSL "https://github.com/go-gost/gost/releases/download/${VER}/gost_${VER#v}_linux_${GOARCH}.tar.gz" \
        -o "${TMP}/gost.tgz"
    tar -xzf "${TMP}/gost.tgz" -C "$TMP"
    install -m 0755 "${TMP}/gost" "${INSTALL_DIR}/gost"
    rm -rf "$TMP"
    log "安装完成：$(${INSTALL_DIR}/gost -V 2>&1 | head -1)"
fi

# ---------------------------------------------------------------- 3. systemd
# 凭据内嵌在 -L 的 URL 里，这就是「像 Telegram 的密钥一样」的那一环：
# 没凭据连不上，不怕被扫到白用。
cat > "$UNIT_FILE" <<EOF
[Unit]
Description=gost proxy for HanimeViewer (SOCKS5 + HTTP, userpass)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=${INSTALL_DIR}/gost -L socks5://${PROXY_USER}:${PROXY_PASS}@:${SOCKS_PORT} -L http://${PROXY_USER}:${PROXY_PASS}@:${HTTP_PORT}
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF
# unit 文件里含明文密码，收紧权限（默认 644 任何用户都能读到）。
chmod 600 "$UNIT_FILE"
systemctl daemon-reload
systemctl enable --now gost.service >/dev/null
systemctl restart gost.service
sleep 2
systemctl is-active --quiet gost.service || die "gost 没起来，看日志：journalctl -u gost -n 50 --no-pager"
log "systemd 服务已启动并设为开机自启"

# ---------------------------------------------------------------- 4. 防火墙
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
    ufw allow "${SOCKS_PORT}/tcp" >/dev/null && ufw allow "${HTTP_PORT}/tcp" >/dev/null
    log "ufw 已放行 ${SOCKS_PORT}/tcp 与 ${HTTP_PORT}/tcp"
else
    log "ufw 未启用（或未安装），跳过。若云厂商有安全组，记得在控制台放行这两个端口。"
fi

# ---------------------------------------------------------------- 5. 自检
# 两件事都要验：① 凭据对的时候能转发；② 凭据缺/错的时候**必须被拒**。
# 只验 ① 是上一版脚本的教训 —— 认证被静默忽略时照样「能转发」，看着是好的。
log "自检 1/2：带凭据应能转发"
curl -fsS --max-time 12 -o /dev/null -x "http://${PROXY_USER}:${PROXY_PASS}@127.0.0.1:${HTTP_PORT}" \
    http://example.com/ \
    && log "  HTTP 代理转发 ✅" \
    || warn "  HTTP 代理自检失败，看日志：journalctl -u gost -n 50 --no-pager"
curl -fsS --max-time 12 -o /dev/null -x "socks5h://${PROXY_USER}:${PROXY_PASS}@127.0.0.1:${SOCKS_PORT}" \
    http://example.com/ \
    && log "  SOCKS5 代理转发 ✅" \
    || warn "  SOCKS5 代理自检失败，看日志：journalctl -u gost -n 50 --no-pager"

log "自检 2/2：不带凭据必须被拒绝"
CODE="$(curl -sS --max-time 12 -o /dev/null -w '%{http_code}' \
        -x "http://127.0.0.1:${HTTP_PORT}" http://example.com/ 2>/dev/null || echo 000)"
if [[ "$CODE" == "407" ]]; then
    log "  匿名访问被拒（407）✅ 认证生效"
else
    die "  ⚠️ 匿名访问竟然返回 ${CODE} —— 认证没生效，这是个**开放代理**，请立刻停止：
     systemctl stop gost && systemctl disable gost
   然后检查 gost 版本与 -L 参数写法（见本脚本头部说明）。"
fi

SERVER_IP="$(curl -fsSL --max-time 8 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"
log "本机探测到的公网 IP：${SERVER_IP}"

cat <<EOF

============================================================
 手机端 HanimeViewer 里这样填（设置 → 网络 → 代理）
============================================================
  代理类型    SOCKS5（推荐）
  Host/IP     ${SERVER_IP}
  端口        ${SOCKS_PORT}          （HTTP 代理用 ${HTTP_PORT}）
  用户名      ${PROXY_USER}
  密码        ${PROXY_PASS}

 ⚠️ 这两行凭据请自己存好，脚本不会写进任何日志或仓库。
 改密码：重跑本脚本（先 systemctl stop gost，再带 SOCKS_PORT/HTTP_PORT 重跑）
 看状态：systemctl status gost      看日志：journalctl -u gost -f
============================================================
EOF
