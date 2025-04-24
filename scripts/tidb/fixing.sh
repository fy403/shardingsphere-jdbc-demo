#!/bin/bash
set -e  # �������������˳�

HOST="127.0.0.1"
PORT="3313"
USER="root"
PASS="root"
DB="sharding_db"

for sql_file in ./output/fix-on-slave/*.sql; do
    echo "Executing $sql_file..."
    if !  mysql --default-character-set=utf8mb4 -h$HOST -P$PORT -u$USER -p$PASS $DB < "$sql_file"; then
        echo "Error executing $sql_file. Exiting."
        exit 1
    fi
done
