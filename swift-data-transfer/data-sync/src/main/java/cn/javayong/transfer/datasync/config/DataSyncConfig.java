package cn.javayong.transfer.datasync.config;

import java.util.ArrayList;
import java.util.HashMap;

public class DataSyncConfig {

    private HashMap<String, HashMap<String, Object>> fullStrategy = new HashMap<String, HashMap<String, Object>>();

    // =========================================== 全量配置 start =======================================================
    public HashMap<String, HashMap<String, Object>> getFullStrategy() {
        return fullStrategy;
    }

    public void setFullStrategy(HashMap<String, HashMap<String, Object>> fullStrategy) {
        this.fullStrategy = fullStrategy;
    }

    // =========================================== 全量配置 end  =======================================================

    // =========================================== 增量配置 start =======================================================

    private HashMap<String, HashMap<String, Object>> incrStrategy = new HashMap<String, HashMap<String, Object>>();

    public HashMap<String, HashMap<String, Object>> getIncrStrategy() {
        return incrStrategy;
    }

    public void setIncrStrategy(HashMap<String, HashMap<String, Object>> incrStrategy) {
        this.incrStrategy = incrStrategy;
    }

    private ArrayList<HashMap<String, HashMap<String, Object>>> incrStrategies = new ArrayList<>();

    // 新增方法，兼容之前的版本
    public ArrayList<HashMap<String, HashMap<String, Object>>> getIncrStrategies() {
        return incrStrategies;
    }
    public void setIncrStrategies(ArrayList<HashMap<String, HashMap<String, Object>>> incrStrategies) {
        this.incrStrategies = incrStrategies;
    }

// =========================================== 增量配置 end  =======================================================

}
