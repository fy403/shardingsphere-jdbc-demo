#!/bin/bash
HOST="127.0.0.1"
PORT="3313"
USER="root"
PASS="root"
DB="sharding_db"

for sql_file in ./output/fix-on-slave/*.sql; do
    echo "Executing $sql_file..."
    mysql -h$HOST -P$PORT -u$USER -p$PASS $DB < "$sql_file"
done

#./sync_diff_inspector --config=./config.toml
#