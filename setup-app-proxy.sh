#!/usr/bin/env bash
#
# 给 HanimeViewer 装一个「手机 App 直连」的代理服务。
#
# 背景：App 现在支持 HTTP / SOCKS5 代理，并且支持**用户名 + 密码认证**
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
# 用法（在 VPS 上以 root 执行）：
#   bash setup-app-proxy.sh
#   SOCKS_PORT=7898 HTTP_PORT=7899 PROXY_USER=me bash setup-app-proxy.sh
#
set -euo pipefail

SOCKS_PORT="${SOCKS_PORT:-7898}"
HTTP_PORT="${HTTP_PORT:-7899}"
PROXY_USER="${PROXY_USER:-hanime}"
PROXY_PASS="${PROXY_PASS:-$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)}"
INSTALL_DIR="/usr/local/bin"
CONF_DIR="/etc/gost"

log()  { printf '\033[32m[+]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "请用 root 运行（sudo bash $0）"

# ---------------------------------------------------------------- 1. 端口体检
# 这三个端口是「先看再动」：用户机器上 7898/7899 可能已经有服务在跑，
# 直接覆盖会把人家的代理打断，所以发现被占用就报错退出，让用户自己选端口。
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

# ---------------------------------------------------------------- 3. 写配置
# 认证写进 URL 的 userinfo 里，gost 会强制要求客户端提交凭据。
# 这就是「像 Telegram 的密钥一样」的那一环：没凭据连不上，不怕被扫到白用。
mkdir -p "$CONF_DIR"
cat > "${CONF_DIR}/config.yaml" <<EOF
services:
  - name: app-socks5
    addr: ":${SOCKS_PORT}"
    handler:
      type: socks5
      auth:
        type: userpass
        users:
          - "${PROXY_USER}:${PROXY_PASS}"
    listener:
      type: tcp
  - name: app-http
    addr: ":${HTTP_PORT}"
    handler:
      type: http
      auth:
        type: userpass
        users:
          - "${PROXY_USER}:${PROXY_PASS}"
    listener:
      type: tcp
EOF
chmod 600 "${CONF_DIR}/config.yaml"
log "配置写入 ${CONF_DIR}/config.yaml（权限 600，别让它被别的用户读到密码）"

# ---------------------------------------------------------------- 4. systemd
cat > /etc/systemd/system/gost.service <<EOF
[Unit]
Description=gost proxy for HanimeViewer (SOCKS5 + HTTP)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=${INSTALL_DIR}/gost -C ${CONF_DIR}/config.yaml
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now gost.service >/dev/null
sleep 1
systemctl is-active --quiet gost.service || die "gost 没起来，看日志：journalctl -u gost -n 50 --no-pager"
log "systemd 服务已启动并设为开机自启"

# ---------------------------------------------------------------- 5. 防火墙
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
    ufw allow "${SOCKS_PORT}/tcp" >/dev/null && ufw allow "${HTTP_PORT}/tcp" >/dev/null
    log "ufw 已放行 ${SOCKS_PORT}/tcp 与 ${HTTP_PORT}/tcp"
else
    log "ufw 未启用（或未安装），跳过。若云厂商有安全组，记得在控制台放行这两个端口。"
fi

# ---------------------------------------------------------------- 6. 自检
SERVER_IP="$(curl -fsSL --max-time 8 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"
log "本机探测到的公网 IP：${SERVER_IP}"
log "验证 SOCKS5（走本地回环，不依赖外网）："
if curl -fsS --max-time 10 -x "socks5h://${PROXY_USER}:${PROXY_PASS}@127.0.0.1:${SOCKS_PORT}" \
        https://api.ipify.org >/dev/null 2>&1; then
    log "  SOCKS5 认证 + 转发 ✅"
else
    warn "  SOCKS5 自检失败，看日志：journalctl -u gost -n 50 --no-pager"
fi

cat <<EOF

============================================================
 手机端 HanimeViewer 里这样填（设置 → 网络 → 代理）
============================================================
  代理类型    SOCKS5（推荐；HTTP 也可以）
  Host/IP     ${SERVER_IP}
  端口        ${SOCKS_PORT}          （HTTP 代理用 ${HTTP_PORT}）
  用户名      ${PROXY_USER}
  密码        ${PROXY_PASS}

 ⚠️ 这两行凭据请自己存好，脚本不会写进任何日志或仓库。
 改密码：编辑 ${CONF_DIR}/config.yaml 后 systemctl restart gost
 看状态：systemctl status gost
============================================================
EOF
