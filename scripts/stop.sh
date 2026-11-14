#!/usr/bin/env bash
# 停止服务但保留命名卷中的调课结果；不要加 -v，否则数据被删除
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down
echo "已停止。数据卷 school-panorama_dbdata 保留，下次 start 后调课结果仍在。"
