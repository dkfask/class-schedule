#!/usr/bin/env bash
# 真实 SMTP 验收冒烟脚本：直接向 SMTP 服务器发一封测试邮件。
# 用法：
#   SMTP_HOST=smtp.example.com SMTP_PORT=587 SMTP_USERNAME=xxx SMTP_PASSWORD=xxx \
#   MAIL_FROM=no-reply@example.com SMTP_TO=receiver@example.com \
#   ./scripts/smtp-acceptance.sh
# 凭据来自环境变量，不会被写入任何文件。
set -euo pipefail
: "${SMTP_HOST:?need SMTP_HOST}" "${SMTP_USERNAME:?need SMTP_USERNAME}" \
  "${SMTP_PASSWORD:?need SMTP_PASSWORD}" "${MAIL_FROM:?need MAIL_FROM}" "${SMTP_TO:?need SMTP_TO}"
SMTP_PORT="${SMTP_PORT:-587}"

python3 - "$SMTP_HOST" "$SMTP_PORT" "$SMTP_USERNAME" "$SMTP_PASSWORD" "$MAIL_FROM" "$SMTP_TO" <<'PY'
import smtplib, ssl, sys, time
host, port, user, password, sender, to = sys.argv[1:7]
msg = f"""From: {sender}
To: {to}
Subject: [class-schedule] SMTP 验收 {time.strftime('%Y-%m-%d %H:%M:%S')}

这是一封排课系统真实 SMTP 验收测试邮件，收到即表示投递链路正常。
"""
ctx = ssl.create_default_context()
with smtplib.SMTP(host, int(port), timeout=30) as s:
    s.ehlo()
    if int(port) == 587:
        s.starttls(context=ctx)
        s.ehlo()
    s.login(user, password)
    s.sendmail(sender, [to], msg.encode())
    print(f"OK: SMTP 服务器已接受发往 {to} 的邮件")
PY
