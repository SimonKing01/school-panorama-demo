#!/usr/bin/env bash
# 一键构建并启动（保留已有数据卷，重复执行安全）
set -euo pipefail
cd "$(dirname "$0")/.."
echo "==> 构建并启动 school-panorama（原业务 5180/18080/55432 · 本体平台 5181/18090/55433）"
docker compose up -d --build
echo "==> 等待健康检查通过"
for i in $(seq 1 90); do
  ok=$(docker compose ps --format json 2>/dev/null | grep -c '"Health":"healthy"' || true)
  [ "$ok" -ge 6 ] && break
  sleep 2
done
docker compose ps
echo ""
echo "  原业务页面   http://localhost:5180"
echo "  原业务接口   http://localhost:18080/api/health"
echo "  业务库       localhost:55432  (schooldemo / demo / 口令见 DEMO_DB_PASSWORD)"
echo "  本体平台     http://localhost:5181"
echo "  本体服务     http://localhost:18090/api/ontology/overview"
echo "  本体库       localhost:55433  (ontologydb / onto)"
echo "  首次启动后请在本体平台执行「全量导入」，或调用 POST /api/ontology/admin/import"
