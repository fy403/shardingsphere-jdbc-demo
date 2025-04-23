package cn.javayong.transfer.datasync.incr;

import cn.javayong.transfer.datasync.config.DataSyncConfig;
import cn.javayong.transfer.datasync.full.FullSyncEnv;
import cn.javayong.transfer.datasync.full.FullSyncTask;
import com.alibaba.druid.pool.DruidDataSource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.Duration;

/**
 * 全量同步服务
 */
public class IncrSyncService {

    private final static Logger logger = LoggerFactory.getLogger(IncrSyncService.class);

    private DataSyncConfig dataSyncConfig;

    private List<IncrSyncEnv> incrSyncEnvs = new ArrayList<>(); // 存储多个增量环境
    private List<IncrSyncTask> incrSyncTasks = new ArrayList<>(); // 存储多个增量任务

    // 新增线程列表，用于存储启动的线程
    private List<Thread> threadList = new ArrayList<>();

    public IncrSyncService(DataSyncConfig dataSyncConfig) {
        this.dataSyncConfig = dataSyncConfig;
    }

    public void init() {
        // 获取增量策略列表
        ArrayList<HashMap<String, HashMap<String, Object>>> incrStrategies = dataSyncConfig.getIncrStrategies();
        if (incrStrategies != null && !incrStrategies.isEmpty()) {
            for (HashMap<String, HashMap<String, Object>> strategy : incrStrategies) {
                Map<String, Object> tableConfig = (Map<String, Object>) strategy.get("tableConfig");
                if (tableConfig == null || !(Boolean) tableConfig.get("switchOpen")) {
                    continue; // 如果任务开关未打开，则跳过
                }

                // 解析数据源参数
                Map<String, Object> targetMap = (Map<String, Object>) strategy.get("target");
                DruidDataSource targetDataSource = initDataSource(targetMap);

                // 获取任务配置
                String topic = (String) tableConfig.get("topic");
                String tables = (String) tableConfig.get("tables");
                String nameServer = (String) tableConfig.get("nameServer");

                // 创建增量环境
                IncrSyncEnv env = new IncrSyncEnv(nameServer, topic, tables, targetDataSource);
                incrSyncEnvs.add(env);

                // 创建增量任务
                IncrSyncTask task = new IncrSyncTask(env);
                incrSyncTasks.add(task);
                task.start(); // 启动任务
                threadList.add(task.getExecuteThread());
            }
        }
        logger.info("incrSyncTasks:{}", incrSyncTasks);
//        waitForAllThreadsToFinish();
    }


    // 新增方法：等待所有线程完成
    public void waitForAllThreadsToFinish() {
        for (Thread thread : threadList) {
            try {
                thread.join(); // 等待线程结束
            } catch (InterruptedException e) {
                logger.error("Thread join interrupted:", e);
                Thread.currentThread().interrupt(); // 恢复中断状态
            }
        }
        logger.info("增量数据同步结束.");
    }
    //==============================================================================================  set method ====================================================================
    private DruidDataSource initDataSource(Map<String, Object> map) {
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl(String.valueOf(map.get("url")));
        dataSource.setUsername(String.valueOf(map.get("username")));
        dataSource.setPassword(String.valueOf(map.get("password")));
        dataSource.setMinIdle(1);
        dataSource.setMaxActive(30);
        try {
            dataSource.init();
        } catch (Exception e) {
            logger.error("init error:", e);
        }
        return dataSource;
    }
}