package cn.javayong.transfer.datasync.full;

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
    private static final int BATCH_FETCH_SIZE = 300; // 每次查询获取的记录数
    private static final int BATCH_INSERT_SIZE = 300; // 每次批量插入的记录数

    private FullSyncEnv fullSyncEnv;
    private String tableName;
    private DruidDataSource sourceDataSource;
    private DruidDataSource targetDataSource;
    private Thread executeThread;
    private Long lastCursorId = 0L;
    private Long maxIdBoundary = 0L; // 新增：截至边界

    public FullSyncTask(FullSyncEnv fullSyncEnv, String tableName) {
        this.fullSyncEnv = fullSyncEnv;
        this.sourceDataSource = fullSyncEnv.getSourceDataSource();
        this.targetDataSource = fullSyncEnv.getTargetDataSource();
        this.tableName = tableName;
        initCursorAndBoundary(); // 新增：初始化游标和截至边界
    }

    // 新增：初始化游标和截至边界
    private void initCursorAndBoundary() {
        try {
            // 从目标库读取表的最大 id 作为上次游标索引
            this.lastCursorId = getMaxIdFromTarget(tableName);
            // 从源库读取表的最大 id 作为截至边界
            this.maxIdBoundary = getMaxIdFromSource(tableName);
            logger.info("表 {} 初始化游标位置: {}, 截至边界: {}", tableName, lastCursorId, maxIdBoundary);
        } catch (SQLException e) {
            logger.error("初始化游标和截至边界失败", e);
            throw new RuntimeException("初始化失败", e);
        }
    }

    // 新增：从目标库读取表的最大 id
    private Long getMaxIdFromTarget(String tableName) throws SQLException {
        String querySQL = "SELECT MAX(id) FROM " + tableName;
        try (Connection connection = targetDataSource.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(querySQL);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getLong(1);
            }
        }
        return 0L;
    }

    // 新增：从源库读取表的最大 id
    private Long getMaxIdFromSource(String tableName) throws SQLException {
        String querySQL = "SELECT MAX(id) FROM " + tableName;
        try (Connection connection = sourceDataSource.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(querySQL);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getLong(1);
            }
        }
        return 0L;
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
            logger.info("表 {} 当前游标位置: {}, 截至边界: {}", tableName, lastCursorId, maxIdBoundary);

            // 批次处理循环
            while (true) {
                // 1. 批次查询数据
                List<Map<String, Object>> batchData = fetchBatchData(tableName, columnTypes);
                if (batchData.isEmpty()) {
                    logger.info("表 {} 没有更多数据，退出循环", tableName);
                    break; // 没有更多数据，退出循环
                }

                // 终止条件：当一次批量查询的第一个数据的 id 大于截至边界时，停止全量同步
                if (((Long) batchData.get(0).get("id")) > maxIdBoundary) {
                    logger.info("表 {} 同步完成，当前游标位置: {}, 截至边界: {}", tableName, lastCursorId, maxIdBoundary);
                    break;
                }

                // 2. 批次插入数据
                int batchCount = batchInsertData(batchData, columnTypes, insertSql);
                totalCount += batchCount;

                // 3. 更新游标位置
                if (!batchData.isEmpty()) {
                    lastCursorId = (Long) batchData.get(batchData.size() - 1).get("id");
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