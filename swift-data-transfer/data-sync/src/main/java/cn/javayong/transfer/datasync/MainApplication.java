package cn.javayong.transfer.datasync;

import cn.javayong.transfer.datasync.config.DataSyncConfig;
import cn.javayong.transfer.datasync.full.FullSyncService;
import cn.javayong.transfer.datasync.incr.IncrSyncService;
import cn.javayong.transfer.datasync.support.YamlLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同步服务主函数
 * Created by zhangyong on 2024/6/15.
 */
public class MainApplication {
    private final static Logger logger = LoggerFactory.getLogger(MainApplication.class);
    public static void main(String[] args) {
        // 在代码中添加检查
        logger.info("开始启动同步服务");
        long start = System.currentTimeMillis();
        // 加载配置
        DataSyncConfig dataSyncConfig = YamlLoader.loadConfig();
        // 启动全量同步
        FullSyncService fullSyncService = new FullSyncService(dataSyncConfig);
        fullSyncService.init();
        fullSyncService.waitForAllThreadsToFinish();
        // 启动增量同步
        IncrSyncService incrSyncService = new IncrSyncService(dataSyncConfig);
        incrSyncService.init();
        incrSyncService.waitForAllThreadsToFinish();
        logger.info("结束启动同步服务 耗时：" + (System.currentTimeMillis() - start) + "毫秒");
    }
}