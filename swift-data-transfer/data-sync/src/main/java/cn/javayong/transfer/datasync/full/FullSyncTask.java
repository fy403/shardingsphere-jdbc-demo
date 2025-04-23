package cn.javayong.transfer.datasync.full;

import cn.javayong.transfer.datasync.checkpoint.SyncContext;
import cn.javayong.transfer.datasync.support.Utils;
import com.alibaba.druid.pool.DruidDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全量同步任务（改造版）
 */
public class FullSyncTask {

    private final static Logger logger = LoggerFactory.getLogger(FullSyncTask.class);

    // 批次大小配置
    private static final int BATCH_FETCH_SIZE = 100; // 每次查询获取的记录数
    private static final int BATCH_INSERT_SIZE = 100; // 每次批量插入的记录数

    private FullSyncEnv fullSyncEnv;
    private String tableName;
    private DruidDataSource sourceDataSource;
    private DruidDataSource targetDataSource;
    private Thread executeThread;
    private Long lastCursorId = 0L;
    
    // 获取同步上下文的单例实例
    private final SyncContext syncContext = SyncContext.getInstance();

    public FullSyncTask(FullSyncEnv fullSyncEnv, String tableName) {
        this.fullSyncEnv = fullSyncEnv;
        this.sourceDataSource = fullSyncEnv.getSourceDataSource();
        this.targetDataSource = fullSyncEnv.getTargetDataSource();
        this.tableName = tableName;
        this.lastCursorId = Utils.loadLastCursorId(tableName);
    }

    public void start() {
        this.executeThread = new Thread(() -> {
            try {
                process(tableName);
            } catch (Exception e) {
                logger.error("process error:", e);
            }
        });
        this.executeThread.setName("fullExecuteThread-" + tableName);
        this.executeThread.start();
    }

    public Thread getExecuteThread() {
        return executeThread;
    }

    private void process(String tableName) {
        long start = System.currentTimeMillis();
        logger.info("开始全量同步表：" + tableName);
        int totalCount = 0;

        try {
            LinkedHashMap<String, Integer> columnTypes = Utils.getColumnTypesV2(sourceDataSource, tableName);
            String insertSql = buildInsertSql(tableName, columnTypes);
            logger.info("表 {} 当前游标位置: {}", tableName, lastCursorId);
            // 批次处理循环
            while (true) {
                // 检查是否应该停止全量同步
                if (syncContext.shouldStopFullSync(tableName)) {
                    logger.info("表 {} 全量同步已达到阈值，增量同步时间为 {}，最后全量同步时间为 {}，停止全量同步",
                            tableName, 
                            syncContext.getIncrementalTime(tableName),
                            syncContext.getLastFullSyncTime(tableName));
                    break;
                }
                // 1. 批次查询数据
                List<Map<String, Object>> batchData = fetchBatchData(tableName, columnTypes);
                if (batchData.isEmpty()) {
                    logger.info("表 {} 没有更多数据，退出循环", tableName);
                    break; // 没有更多数据，退出循环
                }

                // 2. 批次插入数据
                int batchCount = batchInsertData(batchData, columnTypes, insertSql);
                totalCount += batchCount;

                // 3. 更新游标位置和同步上下文
                if (!batchData.isEmpty()) {
                    lastCursorId = (Long) batchData.get(batchData.size() - 1).get("id");
                    Utils.saveLastCursorId(tableName, lastCursorId);
                    
                    // 获取最后一条记录的创建时间（如果有）
                    Map<String, Object> lastRecord = batchData.get(batchData.size() - 1);
                    if (lastRecord.containsKey("create_time") && lastRecord.get("create_time") != null) {
                        Object createTimeObj = lastRecord.get("create_time");
                        LocalDateTime lastQueryTime = null;
                        
                        if (createTimeObj instanceof Timestamp) {
                            lastQueryTime = ((Timestamp) createTimeObj).toLocalDateTime();
                        } else if (createTimeObj instanceof String) {
                            lastQueryTime = Utils.parseDateTime((String) createTimeObj);
                        }
                        
                        if (lastQueryTime != null) {
                            // 更新同步上下文中的全量同步时间
                            syncContext.updateLastFullSyncTime(tableName, lastQueryTime);
                            logger.info("表 {} 更新全量同步时间为: {}", tableName, lastQueryTime);
                        }
                    }
                    
                    logger.info("表 {} 同步进度: 已处理 {} 条记录, 当前游标位置: {}",
                            tableName, totalCount, lastCursorId);
                }
            }
        } catch (Exception e) {
            logger.error("处理表 {} 时发生错误:", tableName, e);
        }

        logger.info("结束全量同步表：{} 总耗时: {} ms, 总处理记录数: {}",
                tableName, (System.currentTimeMillis() - start), totalCount);
    }

    /**
     * 构建INSERT SQL语句
     */
    private String buildInsertSql(String tableName, LinkedHashMap<String, Integer> columnTypes) {
        StringBuilder insertSql = new StringBuilder();
        insertSql.append("INSERT INTO ").append(tableName).append(" (");

        // 拼接列名
        for (int i = 0; i < columnTypes.size(); i++) {
            String targetColumnName = columnTypes.keySet().toArray(new String[0])[i];
            insertSql.append("`").append(targetColumnName).append("`");
            if (i < columnTypes.size() - 1) {
                insertSql.append(",");
            }
        }
        insertSql.append(") VALUES (");

        // 拼接占位符
        for (int i = 0; i < columnTypes.size(); i++) {
            insertSql.append("?");
            if (i < columnTypes.size() - 1) {
                insertSql.append(",");
            }
        }
        insertSql.append(")");

        return insertSql.toString();
    }

    /**
     * 批次查询数据
     */
    private List<Map<String, Object>> fetchBatchData(String tableName,
                                                     LinkedHashMap<String, Integer> columnTypes) throws SQLException {
        List<Map<String, Object>> batchData = new ArrayList<>(BATCH_FETCH_SIZE);
        String querySQL = "SELECT * FROM " + tableName +
                " WHERE id > " + lastCursorId +
                " ORDER BY id LIMIT " + BATCH_FETCH_SIZE;

        Connection sourceConnection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;

        try {
            sourceConnection = sourceDataSource.getConnection();
            preparedStatement = sourceConnection.prepareStatement(querySQL,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

            preparedStatement.setQueryTimeout(30);
            preparedStatement.setFetchSize(Math.min(BATCH_FETCH_SIZE, 1000));

            resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                Map<String, Object> rowData = new LinkedHashMap<>(columnTypes.size());
                for (String columnName : columnTypes.keySet()) {
                    rowData.put(columnName, resultSet.getObject(columnName));
                }
                batchData.add(rowData);
            }
        } finally {
            // 确保资源按正确顺序关闭
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    logger.error("关闭ResultSet失败", e);
                }
            }
            if (preparedStatement != null) {
                try {
                    preparedStatement.close();
                } catch (SQLException e) {
                    logger.error("关闭PreparedStatement失败", e);
                }
            }
            if (sourceConnection != null) {
                try {
                    sourceConnection.close();
                } catch (SQLException e) {
                    logger.error("关闭Connection失败", e);
                }
            }
        }

        return batchData;
    }

    /**
     * 批次插入数据
     */
    private int batchInsertData(List<Map<String, Object>> batchData,
                                LinkedHashMap<String, Integer> columnTypes, String insertSql) {
        int totalInserted = 0;
        Connection targetConnection = null;

        try {
            targetConnection = targetDataSource.getConnection();
            targetConnection.setAutoCommit(false); // 开启事务

            PreparedStatement targetPreparedStatement = targetConnection.prepareStatement(insertSql);

            // 分批处理，每BATCH_INSERT_SIZE条提交一次
            for (int i = 0; i < batchData.size(); i++) {
                Map<String, Object> rowData = batchData.get(i);

                // 设置参数
                List<Map.Entry<String, Object>> rowDataForList =
                        rowData.entrySet().stream().collect(Collectors.toList());

                for (int j = 0; j < rowDataForList.size(); j++) {
                    Map.Entry<String, Object> columnObject = rowDataForList.get(j);
                    int type = columnTypes.get(columnObject.getKey());
                    Object value = columnObject.getValue();
                    Utils.setPStmt(type, targetPreparedStatement, value, j + 1);
                }

                targetPreparedStatement.addBatch();

                // 达到批次大小或最后一条记录时执行
                if ((i + 1) % BATCH_INSERT_SIZE == 0 || i == batchData.size() - 1) {
                    try {
                        int[] results = targetPreparedStatement.executeBatch();
                        targetConnection.commit(); // 提交事务
                        totalInserted += results.length;
                    } catch (BatchUpdateException e) {
                        // 处理批量插入中的重复键等错误
                        targetConnection.rollback(); // 回滚事务
                        logger.warn("批次插入失败，尝试单条插入", e);
                        totalInserted += insertOneByOne(targetConnection, batchData,
                                i - (i % BATCH_INSERT_SIZE), i, columnTypes, insertSql);
                    }
                }
            }
        } catch (Exception e) {
            try {
                if (targetConnection != null) {
                    targetConnection.rollback(); // 出错时回滚
                }
            } catch (SQLException ex) {
                logger.error("回滚事务失败", ex);
            }
            logger.error("批次插入数据失败", e);
        } finally {
            try {
                if (targetConnection != null) {
                    targetConnection.setAutoCommit(true); // 恢复自动提交
                    targetConnection.close();
                }
            } catch (SQLException e) {
                logger.error("关闭连接失败", e);
            }
        }

        return totalInserted;
    }

    /**
     * 单条插入数据（用于处理批次插入失败的情况）
     */
    private int insertOneByOne(Connection connection, List<Map<String, Object>> batchData,
                               int start, int end, LinkedHashMap<String, Integer> columnTypes, String insertSql)
            throws SQLException {
        int successCount = 0;

        for (int i = start; i <= end; i++) {
            try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
                Map<String, Object> rowData = batchData.get(i);
                List<Map.Entry<String, Object>> rowDataForList =
                        rowData.entrySet().stream().collect(Collectors.toList());

                for (int j = 0; j < rowDataForList.size(); j++) {
                    Map.Entry<String, Object> columnObject = rowDataForList.get(j);
                    int type = columnTypes.get(columnObject.getKey());
                    Object value = columnObject.getValue();
                    Utils.setPStmt(type, stmt, value, j + 1);
                }

                try {
                    stmt.executeUpdate();
                    successCount++;
                } catch (SQLException e) {
                    if (e.getMessage().contains("Duplicate entry") || e.getMessage().startsWith("ORA-00001:")) {
                        // 忽略重复键错误
                        logger.warn("忽略重复键错误：{}", e.getMessage());
                        successCount++;
                    } else {
                        throw e;
                    }
                }
            }
        }

        connection.commit();
        return successCount;
    }
}