#!/usr/bin/env bash
# 一键构建并启动（保留已有数据卷，重复执行安全）
set -euo pipefail
cd "$(dirname "$0")/.."
echo "==> 构建并启动 school-panorama（前端 5180 / 后端 18080 / 数据库 55432）"
docker compose up -d --build
echo "==> 等待健康检查通过"
for i in $(seq 1 90); do
  ok=$(docker compose ps --format json 2>/dev/null | grep -c '"Health":"healthy"' || true)
  [ "$ok" -ge 3 ] && break
  sleep 2
done
docker compose ps
echo ""
echo "  前端页面   http://localhost:5180"
echo "  后端接口   http://localhost:18080/api/health"
echo "  PostgreSQL localhost:55432  (库 schooldemo / 用户 demo / 口令见 .env 或 DEMO_DB_PASSWORD)"
