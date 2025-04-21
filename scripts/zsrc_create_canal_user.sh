#!/bin/bash

# MySQL 实例配置
declare -A mysql_instances=(
  ["src-mysql-0"]="3306"
  ["src-mysql-1"]="3307"
)

# 统一密码
ROOT_PASS="root"
CANAL_USER="canal"
CANAL_PASS="canal"

# 检查 mysql 客户端是否可用
if ! command -v mysql &> /dev/null; then
  echo "错误: mysql 客户端未安装，请先安装 MySQL 客户端"
  exit 1
fi

# 为每个实例创建用户
for instance in "${!mysql_instances[@]}"; do
  port=${mysql_instances[$instance]}
  
  echo "正在处理 ${instance} (端口: ${port})..."
  
  if ! mysql -h 127.0.0.1 -P $port -u root -p$ROOT_PASS -e "SELECT 1" &> /dev/null; then
    echo "错误: 无法连接到 ${instance}，请检查服务是否运行"
    continue
  fi

  mysql -h 127.0.0.1 -P $port -u root -p$ROOT_PASS <<EOF
CREATE USER IF NOT EXISTS '${CANAL_USER}'@'%' IDENTIFIED BY '${CANAL_PASS}';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '${CANAL_USER}'@'%';
FLUSH PRIVILEGES;
EOF

  if [ $? -eq 0 ]; then
    echo "成功为 ${instance} 创建 Canal 用户"
  else
    echo "为 ${instance} 创建 Canal 用户失败"
  fi
done

echo "所有操作完成"