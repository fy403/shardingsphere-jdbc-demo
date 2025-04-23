package cn.javayong.transfer.datasync.incr;

import cn.javayong.transfer.datasync.checkpoint.SyncContext;
import cn.javayong.transfer.datasync.full.FullSyncEnv;
import cn.javayong.transfer.datasync.support.Utils;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fastjson2.JSON;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalMQConnector;
import com.alibaba.otter.canal.client.rocketmq.RocketMQCanalConnector;
import com.alibaba.otter.canal.protocol.FlatMessage;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 增量同步任务
 */
public class IncrSyncTask {

    private final static Logger logger = LoggerFactory.getLogger(IncrSyncTask.class);

    private final static Integer BATCH_SIZE = 16;

    private IncrSyncEnv incrSyncEnv;

    private Thread executeThread;

    private DefaultLitePullConsumer litePullConsumer;
    
    // 获取同步上下文的单例实例
    private final SyncContext syncContext = SyncContext.getInstance();

    public IncrSyncTask(IncrSyncEnv incrSyncEnv) {
        this.incrSyncEnv = incrSyncEnv;
    }

    private volatile boolean dataMarking = false;

    public void start() {
        try {
            this.litePullConsumer = new DefaultLitePullConsumer("incrDataSyn-" + incrSyncEnv.getTopic());
            litePullConsumer.setNamesrvAddr(this.incrSyncEnv.getNameServer());
            litePullConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            litePullConsumer.setPullBatchSize(BATCH_SIZE);
            // 订阅主题 TopicTest
            litePullConsumer.subscribe(this.incrSyncEnv.getTopic(), "*");
            // 开启独立的线程执行任务
            // 自动提交消费偏移量的选项设置为 false
            litePullConsumer.setAutoCommit(false);
            litePullConsumer.start();
            logger.info("IncrSyncTask start success, subscribe to {}, consumerGrop {}", incrSyncEnv.getTopic(), litePullConsumer.getConsumerGroup());
            this.executeThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        process();
                    } catch (Exception e) {
                        logger.error("process error:", e);
                    }
                }
            });
            this.executeThread.setName("incrExecuteThread-" + incrSyncEnv.getTopic());
            this.executeThread.start();
        } catch (Exception e) {
            logger.error("IncrSyncTask start error:", e);
        }
    }

    public Thread getExecuteThread() {
        return executeThread;
    }

    private void process() {
        this.dataMarking = fetchDataMarkingFlagFromCenterStore(this.incrSyncEnv.getTopic());
        while (true) {
            boolean success = false;
            MessageExt commitMessage = null;
            MessageQueue commitCursor = null;
            try {
                logger.info("开始尝试从RocketMQ拉取消息...");
                // 增加超时时间至3秒，避免因网络延迟导致的消息丢失
                List<MessageExt> messageExtList = litePullConsumer.poll(3000);
                logger.info("从RocketMQ拉取消息完成，消息数量: {}", messageExtList == null ? "null" : messageExtList.size());
                if (CollectionUtils.isNotEmpty(messageExtList)) {

                    commitMessage = messageExtList.get(0);
                    commitCursor = new MessageQueue(commitMessage.getTopic(), commitMessage.getBrokerName(), commitMessage.getQueueId());

                    // 整体步骤：
                    // 1、过滤循环消息
                    // 2、数据合并
                    // 3、update 转 insert
                    // 4、按新表合并
                    logger.info("开始收到消息");
                    Map<String, List<FlatMessage>> tableGroup = new HashMap<>();

                    // 用于记录每个表的第一条消息的创建时间
                    Map<String, LocalDateTime> firstMessageTimes = new HashMap<>();

                    for (MessageExt messageExt : messageExtList) {
                        FlatMessage flatMessage = JSON.parseObject(messageExt.getBody(), FlatMessage.class);
                        logger.info("flatMessage:" + JSON.toJSONString(flatMessage));
                        List<Map<String, String>> data = flatMessage.getData();
                        String table = flatMessage.getTable();
                        List<String> pkNames = flatMessage.getPkNames();

                        if (!table.equals("tb_transaction")) {
                            if (!dataMarking) {
                                List<FlatMessage> tableItems = tableGroup.get(table);
                                if (tableItems == null) {
                                    tableItems = new ArrayList<>();

                                    // 为每个表记录第一条消息的创建时间
                                    if (!data.isEmpty() && data.get(0).containsKey("create_time")) {
                                        String createTimeStr = data.get(0).get("create_time");
                                        if (createTimeStr != null) {
                                            LocalDateTime createTime = Utils.parseDateTime(createTimeStr);
                                            firstMessageTimes.put(table, createTime);
                                        }
                                    }
                                }
                                tableItems.add(flatMessage);
                                tableGroup.put(table, tableItems);
                            }
                        } else {
                            // 当遇到 table = order 时，且 status = 1 则表明是 染色数据 开始部分
                            Map<String, String> item = data.get(0);
                            if (item.get("status").equals("1") && flatMessage.getType().equals("UPDATE")) {
                                dataMarking = true;
                            }
                            // 当遇到 table = order 时，且 status = 0 则表明是 染色数据 结束
                            if (item.get("status").equals("0") && flatMessage.getType().equals("UPDATE")) {
                                dataMarking = false;
                            }
                        }
                    }

                    logger.info("结束收到消息");

                    if (MapUtils.isNotEmpty(tableGroup)) {
                        // 更新同步上下文中每个表的增量时间
                        for (Map.Entry<String, LocalDateTime> entry : firstMessageTimes.entrySet()) {
                            String tableName = entry.getKey();
                            LocalDateTime createTime = entry.getValue();

                            // 初始化该表的增量同步时间
                            syncContext.initializeIncrementalTime(tableName, createTime);
                            logger.info("Table {} incremental sync time initialized to: {}",
                                    tableName, createTime != null ? createTime : "current time");
                        }

                        Connection targetConnection = incrSyncEnv.getTargetDataSource().getConnection();
                        targetConnection.setAutoCommit(false);
                        // STEP 1: 首先将事务染色表 状态修改为 1
                        dataMarkTransaction(targetConnection, 1);
                        // STEP 2:  处理真实的去掉数据染色部分数据
                        for (Map.Entry<String, List<FlatMessage>> entry : tableGroup.entrySet()) {
                            List<FlatMessage> messages = entry.getValue();
                            for (FlatMessage flatMessage : messages) {
                                writeRowDataToTargetDataSource(targetConnection, flatMessage);
                            }
                        }
                        // STEP 3:  最后将事务染色表 状态修改为 0
                        dataMarkTransaction(targetConnection, 0);
                        targetConnection.commit();
                    }

                    success = true;

                    // 处理染色标识
                    commitDataDataMarkingFlag(incrSyncEnv.getTopic(), dataMarking);

                    if (success) {
                        litePullConsumer.commitSync();
                    }

                }
            } catch (Exception e) {
                logger.error("process error:", e);
                success = false;
            }
            if (!success) {
                if (commitCursor != null && commitMessage != null) {
                    try {
                        litePullConsumer.seek(commitCursor, commitMessage.getQueueOffset());
                        Thread.sleep(500L);
                    } catch (Exception e) {
                        logger.error("seek error:", e);
                    }
                }
            }
        }
    }
    private void writeRowDataToTargetDataSource(Connection connection, FlatMessage flatMessage) throws Exception {
        List<Map<String, String>> data = flatMessage.getData();
        Map<String, Integer> sqlType = flatMessage.getSqlType();
        
        // 根据操作类型生成不同的 SQL
        if ("UPDATE".equals(flatMessage.getType())) {
            for (Map<String, String> item : data) {
                Map<String, String> rowData = new LinkedHashMap<>();
                rowData.putAll(item);
                // 组装 UPDATE SQL
                StringBuilder updateSql = new StringBuilder();
                updateSql.append("UPDATE ").append(flatMessage.getTable()).append(" SET ");

                List<String> params = new ArrayList<>();
                rowData.forEach((key, value) -> {
                    if (!"id".equals(key)) {
                        params.add(key);
                        updateSql.append(key + "= ?,");
                    }
                });
                int len = updateSql.length();
                updateSql.delete(len - 1, len).append(" WHERE id = ?");
                System.out.println(updateSql);
                params.add("id");

                // 设置预编译
                PreparedStatement targetPreparedStatement = connection.prepareStatement(updateSql.toString());
                // 设置 targetPreparedStatement 的每个字段值
                for (int i = 0; i < params.size(); i++) {
                    String columnName = params.get(i);
                    String value = rowData.get(columnName);
                    Integer type = sqlType.get(columnName);
                    Utils.setPStmt(type, targetPreparedStatement, value, i + 1);
                }
                targetPreparedStatement.executeUpdate();
                targetPreparedStatement.close();
            }
        } else if ("INSERT".equals(flatMessage.getType())) {
            for (Map<String, String> item : data) {
                Map<String, String> rowData = new LinkedHashMap<>();
                rowData.putAll(item);
                // 组装 INSERT SQL
                StringBuilder insertSql = new StringBuilder();
                insertSql.append("INSERT INTO ").append(flatMessage.getTable()).append(" (");

                List<String> columns = new ArrayList<>();
                List<String> placeholders = new ArrayList<>();
                rowData.forEach((key, value) -> {
                    columns.add(key);
                    placeholders.add("?");
                });

                insertSql.append(String.join(", ", columns)).append(") VALUES (");
                insertSql.append(String.join(", ", placeholders)).append(")");
                System.out.println(insertSql);

                // 设置预编译
                PreparedStatement targetPreparedStatement = connection.prepareStatement(insertSql.toString());
                // 设置 targetPreparedStatement 的每个字段值
                int index = 1;
                for (String columnName : columns) {
                    String value = rowData.get(columnName);
                    Integer type = sqlType.get(columnName);
                    Utils.setPStmt(type, targetPreparedStatement, value, index++);
                }

                try {
                    targetPreparedStatement.executeUpdate();
                } catch (Exception e) {
                    // 捕获插入重复的异常，认为是正确的，不报错
                    if (e.getMessage().contains("Duplicate entry")) {
                        logger.warn("Duplicate entry detected for table {}: {}", flatMessage.getTable(), rowData);
                    } else {
                        throw e; // 其他异常继续抛出
                    }
                } finally {
                    targetPreparedStatement.close();
                }
            }
        } else if ("DELETE".equals(flatMessage.getType())) {
            for (Map<String, String> item : data) {
                Map<String, String> rowData = new LinkedHashMap<>();
                rowData.putAll(item);
                // 组装 DELETE SQL
                StringBuilder deleteSql = new StringBuilder();
                deleteSql.append("DELETE FROM ").append(flatMessage.getTable()).append(" WHERE id = ?");
                System.out.println(deleteSql);

                // 设置预编译
                PreparedStatement targetPreparedStatement = connection.prepareStatement(deleteSql.toString());
                // 设置 targetPreparedStatement 的主键值
                String idValue = rowData.get("id");
                Integer idType = sqlType.get("id");
                Utils.setPStmt(idType, targetPreparedStatement, idValue, 1);
                targetPreparedStatement.executeUpdate();
                targetPreparedStatement.close();
            }
        }
    }

    private void dataMarkTransaction(Connection connection, Integer status) throws Exception {
        Statement statement = connection.createStatement();
        String sql = "update tb_transaction set status = " + status + " where tablename = 'order'";
        statement.executeUpdate(sql);
        statement.close();
    }
    // 防止消费者挂掉丢失染色标记
    private boolean fetchDataMarkingFlagFromCenterStore(String topic) {
        // TODO 从 zookeeper 或者 MySQL 查询整个存储当前增量同步任务是否处于染色中
        return false;
    }

    private void commitDataDataMarkingFlag(String topic, boolean dataMarking) {
        // TODO 写入到 zookeeper 或者 MySQL
    }

}
