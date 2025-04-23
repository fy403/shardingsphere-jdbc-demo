#!/bin/bash

# 使用pt-table-sync修复不一致数据
# 以主库为基准修复从库

echo "正在修复不一致数据..."

# 先打印出将要执行的修复操作（不实际执行）
echo "以下是将要执行的修复操作（预览模式）："
pt-table-sync h=127.0.0.1,u=root,p=root,P=3308 \
--databases=sharding_db \
--tables=t_city,t_ent_order,t_ent_order_detail,t_ent_order_item\
--replicate=sharding_db.checksums \
h=127.0.0.1,u=root,p=root,P=3313 \
--print \
--verbose

# read -p "是否要实际执行上述修复操作？(y/n) " -n 1 -r
# echo
# if [[ $REPLY =~ ^[Yy]$ ]]; then
#     echo "正在执行修复操作..."
#     pt-table-sync h=127.0.0.1,u=root,p=root,P=3308 \
#     --databases=sharding_db \
#     --tables=t_city,t_ent_order,t_ent_order_detail,t_ent_order_item\
#     --replicate=sharding_db.checksums \
#     h=127.0.0.1,u=root,p=root,P=3313 \
#     --execute \
#     --verbose
    
#     echo "修复完成！请检查从库数据是否已与主库一致"
#     echo "从库当前数据:"
#     docker exec mysql-slave mysql -uroot -proot -e "SELECT * FROM db1.users; SELECT * FROM db1.orders;"
# else
#     echo "已取消修复操作。"
# fi