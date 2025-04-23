package cn.javayong.transfer.datasync.checkpoint;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synchronization context for managing incremental and full sync states for multiple tables.
 * Tracks time thresholds and provides decision logic for sync processes.
 * Implemented as a thread-safe Singleton.
 */
public class SyncContext {

    // Singleton instance
    private static volatile SyncContext instance;
    
    // Default threshold: 1 hour
    private Duration defaultSyncThreshold = Duration.ofSeconds(15);

    private LocalDateTime startUpTime = LocalDateTime.now();
    
    // Per-table thresholds
    private final Map<String, Duration> tableThresholds = new ConcurrentHashMap<>();
    
    // Per-table incremental times
    private final Map<String, LocalDateTime> incrementalTimes = new ConcurrentHashMap<>();
    
    // Per-table last full sync query times
    private final Map<String, LocalDateTime> lastFullSyncTimes = new ConcurrentHashMap<>();
    
    // Per-table incremental sync enabled flags
    private final Map<String, Boolean> incrementalEnabledFlags = new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent direct instantiation.
     */
    private SyncContext() {
    }
    
    /**
     * Get the singleton instance of SyncContext.
     * 
     * @return The singleton instance
     */
    public static SyncContext getInstance() {
        if (instance == null) {
            synchronized (SyncContext.class) {
                if (instance == null) {
                    instance = new SyncContext();
                }
            }
        }
        return instance;
    }

    /**
     * Set the default sync threshold for tables without a specific threshold.
     * 
     * @param defaultSyncThreshold The new default sync threshold duration
     * @return this context instance for method chaining
     */
    public SyncContext setDefaultSyncThreshold(Duration defaultSyncThreshold) {
        this.defaultSyncThreshold = defaultSyncThreshold;
        return this;
    }

    /**
     * Set incremental sync timestamp for a specific table from the first message in the queue
     * or current time if queue is empty.
     * 
     * @param tableName The table name
     * @param firstMessageCreateTime The create_time from the first message in the queue, or null if queue is empty
     * @return this context instance for method chaining
     */
    public SyncContext initializeIncrementalTime(String tableName, LocalDateTime firstMessageCreateTime) {
        incrementalEnabledFlags.put(tableName, true);
        
        if (firstMessageCreateTime != null && incrementalTimes.get(tableName) == null) {
            incrementalTimes.put(tableName, firstMessageCreateTime);
        }
        return this;
    }

    /**
     * Update the last full sync query time for a specific table.
     * 
     * @param tableName The table name
     * @param lastQueryTime The timestamp of the last full sync query
     * @return this context instance for method chaining
     */
    public SyncContext updateLastFullSyncTime(String tableName, LocalDateTime lastQueryTime) {
        lastFullSyncTimes.put(tableName, lastQueryTime);
        return this;
    }

    /**
     * Check if full sync should be stopped for a specific table based on the threshold.
     * If incremental sync is enabled and the time difference between full sync and 
     * incremental sync exceeds the threshold, full sync should stop.
     * 
     * @param tableName The table name
     * @return true if full sync should be stopped, false otherwise
     */
    public boolean shouldStopFullSync(String tableName) {
        // 必须先开启canal监控，然后执行脚本
        Boolean incrementalEnabled = true;
        LocalDateTime incrementalTime = startUpTime;
        LocalDateTime lastFullSyncTime = lastFullSyncTimes.get(tableName);
        if (incrementalEnabled == null || !incrementalEnabled || incrementalTime == null || lastFullSyncTime == null) {
            return false;
        }
        Duration threshold = tableThresholds.getOrDefault(tableName, defaultSyncThreshold);
        Duration timeDifference = Duration.between(incrementalTime, lastFullSyncTime);
        return timeDifference.compareTo(threshold) > 0;
    }

    /**
     * Get the incremental sync timestamp for a specific table.
     * 
     * @param tableName The table name
     * @return The incremental sync timestamp, or null if not set
     */
    public LocalDateTime getIncrementalTime(String tableName) {
        return incrementalTimes.get(tableName);
    }

    /**
     * Get the last full sync query timestamp for a specific table.
     * 
     * @param tableName The table name
     * @return The last full sync query timestamp, or null if not set
     */
    public LocalDateTime getLastFullSyncTime(String tableName) {
        return lastFullSyncTimes.get(tableName);
    }

    /**
     * Get the sync threshold for a specific table.
     * 
     * @param tableName The table name
     * @return The table-specific sync threshold, or the default if not set
     */
    public Duration getSyncThreshold(String tableName) {
        return tableThresholds.getOrDefault(tableName, defaultSyncThreshold);
    }

    /**
     * Set a custom sync threshold for a specific table.
     * 
     * @param tableName The table name
     * @param threshold The new sync threshold duration
     * @return this context instance for method chaining
     */
    public SyncContext setSyncThreshold(String tableName, Duration threshold) {
        tableThresholds.put(tableName, threshold);
        return this;
    }

    /**
     * Check if incremental sync is enabled for a specific table.
     * 
     * @param tableName The table name
     * @return true if incremental sync is enabled, false otherwise
     */
    public boolean isIncrementalEnabled(String tableName) {
        Boolean enabled = incrementalEnabledFlags.get(tableName);
        return enabled != null && enabled;
    }

    /**
     * Get all tables being tracked in this context.
     * 
     * @return A set of table names
     */
    public Iterable<String> getAllTables() {
        return incrementalEnabledFlags.keySet();
    }
    
    /**
     * Clear the sync context for a specific table.
     * 
     * @param tableName The table name to clear
     * @return this context instance for method chaining
     */
    public SyncContext clearTable(String tableName) {
        incrementalTimes.remove(tableName);
        lastFullSyncTimes.remove(tableName);
        incrementalEnabledFlags.remove(tableName);
        tableThresholds.remove(tableName);
        return this;
    }
    
    /**
     * Reset the singleton instance (primarily for testing purposes).
     */
    public static void reset() {
        synchronized (SyncContext.class) {
            instance = null;
        }
    }
} 