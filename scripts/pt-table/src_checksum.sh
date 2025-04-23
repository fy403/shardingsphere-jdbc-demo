#!/bin/bash

# 安装Percona工具包
if ! command -v pt-table-checksum &> /dev/null; then
    echo "正在安装Percona工具包..."
    sudo apt-get update
    sudo apt-get install -y percona-toolkit
fi

# 检查主从数据库一致性
echo "正在检查主从数据库一致性..."
pt-table-checksum h=127.0.0.1,u=root,p=root,P=3308 \
--databases=sharding_db \
--tables=t_city,t_ent_order,t_ent_order_detail,t_ent_order_item\
--no-check-binlog-format \
--no-check-replication-filters \
--replicate=sharding_db.checksums \
--empty-replicate-table \
--no-create-replicate-table \
--recursion-method=none \
--host=127.0.0.1 \
--port=3313 \