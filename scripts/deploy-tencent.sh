#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# 腾讯云轻量云服务器 / CVM 一键构建与生产部署脚本
# 适用环境：Ubuntu / Debian / CentOS / TencentOS (需提前安装 docker & docker-compose)
# ==============================================================================

echo "=================================================="
echo "  智程排课系统 · 腾讯云生产环境一键部署"
echo "=================================================="

# 1. 检查环境变量文件
if [ ! -f .env ]; then
  if [ -f .env.prod.example ]; then
    echo "⚠️ 未检测到 .env 文件，正在从 .env.prod.example 自动生成..."
    cp .env.prod.example .env
    echo "✅ 已生成 .env 文件，请务必检查并修改数据库及管理员密码！"
  else
    echo "❌ 缺少 .env 配置文件，请先创建 .env 文件后再执行此脚本。"
    exit 1
  fi
fi

# 2. 构建前端生产静态资源
echo ""
echo "📦 [1/3] 正在打包前端静态资源 (Vite build)..."
if command -v npm >/dev/null 2>&1; then
  npm ci || npm install
  npm run build
  echo "✅ 前端构建完成，产物已就绪。"
else
  echo "⚠️ 当前宿主机未安装 npm，跳过本地 build，将直接依赖容器内前端上下文。"
fi

# 3. 构建并启动生产容器
echo ""
echo "🚀 [2/3] 正在构建后端镜像并启动生产容器集群..."
docker compose -f docker-compose.prod.yml --env-file .env build
docker compose -f docker-compose.prod.yml --env-file .env up -d

# 4. 健康检查与上线验证
echo ""
echo "🩺 [3/3] 正在等待服务就绪并执行健康检查..."
sleep 10

if curl -s -f "http://127.0.0.1:${PROD_PORT:-80}/api/health" >/dev/null 2>&1; then
  echo ""
  echo "🎉=================================================="
  echo "  部署成功！排课系统已在腾讯云服务器正常运行。"
  echo "  访问地址: http://<你的腾讯云公网IP>:${PROD_PORT:-80}"
  echo "  默认管理员账号: admin"
  echo "  已开放公开注册: 注册用户即为排课员 (PLANNER)"
  echo "  已接通 DeepSeek AI 智能助手与 5 阶段工作流控制台"
  echo "=================================================="
else
  echo ""
  echo "⚠️ 容器已启动，但端口暂未返回就绪状态。请通过以下命令检查日志："
  echo "   docker compose -f docker-compose.prod.yml logs -f"
fi
