package cn.javayong.shardingjdbc5.spring.common.sharding;

import cn.javayong.shardingbase.ShardingConstants;
import cn.javayong.shardingbase.SnowFlakeIdGenerator;
import cn.javayong.shardingbase.StringHashUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 复合分片算法 :
 * 主键id 是雪花算法 workerId = crc32(shardingKey) % 1024
 * 支持范围查询（自动广播到所有分片）
 */
public class HashSlotAlgorithm implements ComplexKeysShardingAlgorithm<Comparable<?>> {

    public final static Logger logger = LoggerFactory.getLogger(HashSlotAlgorithm.class);

    // 是否直接通过 slot/shardingCount
    private final static String DIRECT_INDEX_PROPERTY = "directIndex";
    // 是否启用范围查询自动广播
    private final static String ENABLE_RANGE_BROADCAST = "enableRangeBroadcast";

    private Properties properties;

    @Override
    public void init(Properties properties) {
        logger.info("begin to init HashSlotAlgorithm with properties: {}", properties);
        this.properties = properties;
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        // 真实分片数量
        int count = availableTargetNames.size();
        validateShardCount(count);

        // 1. 检查是否是范围查询
        if (isRangeQuery(shardingValue) && isRangeBroadcastEnabled()) {
            logger.debug("Range query detected, broadcasting to all {} shards", count);
            return availableTargetNames; // 广播到所有分片
        }

        // 2. 尝试通过主键ID路由
        List<Integer> slotList = tryRouteBySnowflakeId(shardingValue);

        // 3. 如果主键路由失败，尝试通过分片键路由
        if (slotList.isEmpty()) {
            slotList = tryRouteByShardingColumns(shardingValue);
        }

        // 4. 如果仍然无法确定路由，且配置了广播，则广播到所有分片
        if (slotList.isEmpty() && isRangeBroadcastEnabled()) {
            logger.warn("No sharding value found, broadcasting to all shards");
            return availableTargetNames;
        }

        // 5. 转换为实际的目标分片名称
        return convertSlotsToTargetNames(slotList, availableTargetNames);
    }

    private boolean isRangeBroadcastEnabled() {
        return Boolean.parseBoolean(properties.getProperty(ENABLE_RANGE_BROADCAST, "true"));
    }

    private void validateShardCount(int count) {
        if ((count & (count - 1)) != 0) {
            throw new RuntimeException("分区数必须是2的次幂,当前分区数是:" + count);
        }
    }

    private boolean isRangeQuery(ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        // 检查主键ID是否有范围查询
        String idColumn = getQuerySnowFlakeIdColumn();
        if (StringUtils.isNotBlank(idColumn) && shardingValue.getColumnNameAndRangeValuesMap().containsKey(idColumn)) {
            return true;
        }

        // 检查分片键是否有范围查询
        for (String column : getShardingColumnsArray(shardingValue)) {
            if (shardingValue.getColumnNameAndRangeValuesMap().containsKey(column)) {
                return true;
            }
        }

        return false;
    }

    private List<Integer> tryRouteBySnowflakeId(ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        List<Integer> slotList = new ArrayList<>();
        String querySnowFlakeIdColumn = getQuerySnowFlakeIdColumn();

        if (StringUtils.isNotBlank(querySnowFlakeIdColumn)) {
            List<String> idValues = getShardingValueListByColumn(querySnowFlakeIdColumn, shardingValue);
            if (CollectionUtils.isNotEmpty(idValues)) {
                for (String idValue : idValues) {
                    try {
                        slotList.add(SnowFlakeIdGenerator.getWorkerId(Long.valueOf(idValue)));
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid snowflake ID: {}", idValue, e);
                    }
                }
            }
        }
        return slotList;
    }

    private List<Integer> tryRouteByShardingColumns(ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        List<Integer> slotList = new ArrayList<>();
        List<String> values = doShardingValuesByShardingColumns(shardingValue);

        if (CollectionUtils.isNotEmpty(values)) {
            for (String value : values) {
                slotList.add(StringHashUtil.hashSlot(value));
            }
        }
        return slotList;
    }

    private Collection<String> convertSlotsToTargetNames(List<Integer> slotList, Collection<String> availableTargetNames) {
        if (slotList.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> result = new HashSet<>();
        boolean directIndex = Boolean.parseBoolean(properties.getProperty(DIRECT_INDEX_PROPERTY, "false"));
        int count = availableTargetNames.size();
        String[] targetNamesArray = availableTargetNames.toArray(new String[0]);

        for (Integer slot : slotList) {
            int index = directIndex ? StringHashUtil.indexDirect(slot, count) : StringHashUtil.index(slot, count);
            if (index >= 0 && index < targetNamesArray.length) {
                result.add(targetNamesArray[index]);
            } else {
                logger.warn("Invalid index {} calculated for slot {}", index, slot);
            }
        }

        return result;
    }

    // 以下是原有方法保持不变
    public String getQuerySnowFlakeIdColumn() {
        return ShardingConstants.DEFAULT_PRIMARY_KEY;
    }

    public int getCombineKeyLength() {
        return ShardingConstants.DEFAULT_SINGLE_COMBINE_KEY_LENGTH;
    }

    private List<String> doShardingValuesByShardingColumns(ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        int combineKeyLength = getCombineKeyLength();
        List<String> shardingColumnsArray = getShardingColumnsArray(shardingValue);
        if (shardingColumnsArray.size() != combineKeyLength) {
            return Collections.emptyList();
        }

        return combineKeyLength == ShardingConstants.DEFAULT_SINGLE_COMBINE_KEY_LENGTH ?
                doSingleSharding(shardingValue, shardingColumnsArray) :
                doMultiSharding(shardingValue, shardingColumnsArray);
    }

    private List<String> doSingleSharding(ComplexKeysShardingValue<Comparable<?>> shardingValue, List<String> shardingColumnsArray) {
        String shardingColumn = shardingColumnsArray.get(0);
        return getShardingValueListByColumn(shardingColumn, shardingValue);
    }

    private List<String> doMultiSharding(ComplexKeysShardingValue<Comparable<?>> shardingValue, List<String> shardingColumnsArray) {
        List<List<String>> collection = new ArrayList<>();
        for (String shardingColumn : shardingColumnsArray) {
            List<String> shardingValueList = getShardingValueListByColumn(shardingColumn, shardingValue);
            if (CollectionUtils.isNotEmpty(shardingValueList)) {
                collection.add(shardingValueList);
            }
        }
        return StringHashUtil.descartes(collection);
    }

    private List<String> getShardingValueListByColumn(String shardingColumn, ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        List<String> values = new ArrayList<>();
        shardingValue.getColumnNameAndShardingValuesMap().forEach((key, valueCollection) -> {
            if (shardingColumn.equals(key)) {
                valueCollection.forEach(value -> {
                    if (value != null) {
                        values.add(String.valueOf(value));
                    }
                });
            }
        });
        return values;
    }

    private List<String> getShardingColumnsArray(ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        List<String> columns = new ArrayList<>(shardingValue.getColumnNameAndShardingValuesMap().keySet());
        Collections.sort(columns); // 对分区键排序便于笛卡尔积计算
        return columns;
    }

//    @Override
//    public Properties getProps() {
//        return this.properties;
//    }

    @Override
    public String getType() {
        return "HASH_SLOT";
    }
}