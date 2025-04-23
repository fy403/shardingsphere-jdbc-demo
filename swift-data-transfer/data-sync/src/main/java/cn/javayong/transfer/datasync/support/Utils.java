package cn.javayong.transfer.datasync.support;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.TimeZone;

public class Utils {

    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    public final static String  timeZone;    // 当前时区
    private static DateTimeZone dateTimeZone;
    // 持久化游标位置的文件路径
    private static final String CURSOR_FILE_PATH = "cursor_positions.txt";

    static {
        TimeZone localTimeZone = TimeZone.getDefault();
        int rawOffset = localTimeZone.getRawOffset();
        String symbol = "+";
        if (rawOffset < 0) {
            symbol = "-";
        }
        rawOffset = Math.abs(rawOffset);
        int offsetHour = rawOffset / 3600000;
        int offsetMinute = rawOffset % 3600000 / 60000;
        String hour = String.format("%1$02d", offsetHour);
        String minute = String.format("%1$02d", offsetMinute);
        timeZone = symbol + hour + ":" + minute;
        dateTimeZone = DateTimeZone.forID(timeZone);
        TimeZone.setDefault(TimeZone.getTimeZone("GMT" + timeZone));
    }

    /**
     * 加载上次游标的最后位置
     * @param tableName 表名
     * @return 上次游标的最后位置，如果不存在则返回 0L
     */
    public static Long loadLastCursorId(String tableName) {
        try (BufferedReader reader = new BufferedReader(new FileReader(CURSOR_FILE_PATH))) {
            log.info("加载上次游标的最后位置");
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 2 && parts[0].equals(tableName)) {
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (IOException e) {
            // 文件不存在或读取失败，返回默认值 0L
        }
        return 0L;
    }

    public static void saveLastCursorId(String tableName, Long cursorId) {
        File file = new File(CURSOR_FILE_PATH);
        boolean append = true;
        if (!file.exists()) {
            append = false;
        } else {
            // 检查是否需要更新现有记录
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                StringBuilder content = new StringBuilder();
                boolean found = false;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(tableName + "=")) {
                        content.append(tableName).append("=").append(cursorId).append("\n");
                        found = true;
                    } else {
                        content.append(line).append("\n");
                    }
                }
                if (!found) {
                    content.append(tableName).append("=").append(cursorId).append("\n");
                }
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                    writer.write(content.toString());
                }
            } catch (IOException e) {
                // 忽略错误，尝试追加新记录
            }
        }
        if (!append) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, append))) {
                writer.write(tableName + "=" + cursorId + "\n");
            } catch (IOException e) {
                // 记录日志或抛出异常
            }
        }
    }

    public static LinkedHashMap<String, Integer> getColumnTypes(DataSource dataSource, String tableName) {
        LinkedHashMap<String, Integer> columnTypes = new LinkedHashMap<>(16);
        Connection sourceConnection = null;
        ResultSet columnsResultSet = null;
        try {
            sourceConnection = dataSource.getConnection();
            DatabaseMetaData SourceMetaData = sourceConnection.getMetaData();

            columnsResultSet = SourceMetaData.getColumns(
                    null,
                    null,
                    tableName,
                    null
            );
            // 4. 遍历结果集并输出列信息
            while (columnsResultSet.next()) {
                String columnName = columnsResultSet.getString("COLUMN_NAME");
                String columnType = columnsResultSet.getString("TYPE_NAME");
                int dataType = columnsResultSet.getInt("DATA_TYPE");
                columnTypes.put(columnName, dataType);
            }
        } catch (Exception e) {
            log.error("表名：" + tableName + " 获取列类型异常:", e);
        } finally {
            if (columnsResultSet != null) {
                try {
                    columnsResultSet.close();
                } catch (Exception e) {
                    log.error("columnsResultSet close error:", e);
                }
            }
            if (sourceConnection != null) {
                try {
                    sourceConnection.close();
                } catch (Exception e) {
                    log.error("sourceConnection close error:", e);
                }
            }
        }
        return columnTypes;
    }

    public static LinkedHashMap<String, Integer> getColumnTypesV2(DataSource dataSource, String tableName) {
        LinkedHashMap<String, Integer> columnTypes = new LinkedHashMap<>(16);
        String sql = "SHOW COLUMNS FROM " + tableName;

        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String columnName = rs.getString("Field");
                String typeInfo = rs.getString("Type");
                int dataType = parseTypeFromShowColumns(typeInfo);
                columnTypes.put(columnName, dataType);
            }
        } catch (Exception e) {
            log.error("表名：" + tableName + " 获取列类型异常:", e);
        }
        return columnTypes;
    }

    private static int parseTypeFromShowColumns(String typeInfo) {
        if (typeInfo == null) return Types.OTHER;

        // 处理类似 "varchar(255)" 或 "int(11)" 这样的类型
        String baseType = typeInfo.split("\\(")[0].toUpperCase();

        switch (baseType) {
            case "INT": return Types.INTEGER;
            case "VARCHAR": return Types.VARCHAR;
            case "CHAR": return Types.CHAR;
            case "BIGINT": return Types.BIGINT;
            case "DECIMAL": return Types.DECIMAL;
            case "DATE": return Types.DATE;
            case "DATETIME":
            case "TIMESTAMP": return Types.TIMESTAMP;
            case "TINYINT": return Types.TINYINT;
            case "TEXT": return Types.LONGVARCHAR;
            // 添加更多类型...
            default: return Types.OTHER;
        }
    }

    private static String getTypeName(int dataType) {
        switch (dataType) {
            case Types.VARCHAR:
                return "VARCHAR";
            case Types.CHAR:
                return "CHAR";
            case Types.LONGVARCHAR:
                return "LONGVARCHAR";
            case Types.NUMERIC:
                return "NUMERIC";
            case Types.DECIMAL:
                return "DECIMAL";
            case Types.BIT:
                return "BIT";
            case Types.TINYINT:
                return "TINYINT";
            case Types.SMALLINT:
                return "SMALLINT";
            case Types.INTEGER:
                return "INTEGER";
            case Types.BIGINT:
                return "BIGINT";
            case Types.REAL:
                return "REAL";
            case Types.FLOAT:
                return "FLOAT";
            case Types.DOUBLE:
                return "DOUBLE";
            case Types.BINARY:
                return "BINARY";
            case Types.VARBINARY:
                return "VARBINARY";
            case Types.LONGVARBINARY:
                return "LONGVARBINARY";
            case Types.DATE:
                return "DATE";
            case Types.TIME:
                return "TIME";
            case Types.TIMESTAMP:
                return "TIMESTAMP";
            case Types.CLOB:
                return "CLOB";
            case Types.BLOB:
                return "BLOB";
            case Types.ARRAY:
                return "ARRAY";
            case Types.STRUCT:
                return "STRUCT";
            case Types.REF:
                return "REF";
            case Types.DATALINK:
                return "DATALINK";
            case Types.BOOLEAN:
                return "BOOLEAN";
            case Types.ROWID:
                return "ROWID";
            case Types.NCHAR:
                return "NCHAR";
            case Types.NVARCHAR:
                return "NVARCHAR";
            case Types.LONGNVARCHAR:
                return "LONGNVARCHAR";
            case Types.NCLOB:
                return "NCLOB";
            case Types.SQLXML:
                return "SQLXML";
            case Types.REF_CURSOR:
                return "REF_CURSOR";
            case Types.TIME_WITH_TIMEZONE:
                return "TIME_WITH_TIMEZONE";
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return "TIMESTAMP_WITH_TIMEZONE";
            default:
                return "UNKNOWN";
        }

    }

    /**
     * 设置 preparedStatement
     *
     * @param type sqlType
     * @param pstmt 需要设置的preparedStatement
     * @param value 值
     * @param i 索引号
     */
    public static void setPStmt(int type, PreparedStatement pstmt, Object value, int i) throws SQLException {
        switch (type) {
            case Types.BIT:
            case Types.BOOLEAN:
                if (value instanceof Boolean) {
                    pstmt.setBoolean(i, (Boolean) value);
                } else if (value instanceof String) {
                    boolean v = !value.equals("0");
                    pstmt.setBoolean(i, v);
                } else if (value instanceof Number) {
                    boolean v = ((Number) value).intValue() != 0;
                    pstmt.setBoolean(i, v);
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.CHAR:
            case Types.NCHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
                if (value instanceof String) {
                    pstmt.setString(i, (String) value);
                } else if (value == null) {
                    pstmt.setNull(i, type);
                } else {
                    pstmt.setString(i, value.toString());
                }
                break;
            case Types.TINYINT:
                if (value instanceof Number) {
                    pstmt.setShort(i, ((Number) value).shortValue());
                } else if (value instanceof String) {
                    pstmt.setShort(i, Short.parseShort((String) value));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.SMALLINT:
                if (value instanceof Number) {
                    pstmt.setInt(i, ((Number) value).intValue());
                } else if (value instanceof String) {
                    pstmt.setInt(i, Integer.parseInt((String) value));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.INTEGER:
                if (value instanceof Number) {
                    pstmt.setLong(i, ((Number) value).longValue());
                } else if (value instanceof String) {
                    pstmt.setLong(i, Long.parseLong((String) value));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.BIGINT:
                if (value instanceof Number) {
                    pstmt.setBigDecimal(i, new BigDecimal(value.toString()));
                } else if (value instanceof String) {
                    pstmt.setBigDecimal(i, new BigDecimal(value.toString()));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.DECIMAL:
            case Types.NUMERIC:
                if (value instanceof BigDecimal) {
                    pstmt.setBigDecimal(i, (BigDecimal) value);
                } else if (value instanceof Byte) {
                    pstmt.setInt(i, ((Byte) value).intValue());
                } else if (value instanceof Short) {
                    pstmt.setInt(i, ((Short) value).intValue());
                } else if (value instanceof Integer) {
                    pstmt.setInt(i, (Integer) value);
                } else if (value instanceof Long) {
                    pstmt.setLong(i, (Long) value);
                } else if (value instanceof Float) {
                    pstmt.setBigDecimal(i, new BigDecimal((float) value));
                } else if (value instanceof Double) {
                    pstmt.setBigDecimal(i, new BigDecimal((double) value));
                } else if (value != null) {
                    pstmt.setBigDecimal(i, new BigDecimal(value.toString()));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.REAL:
                if (value instanceof Number) {
                    pstmt.setFloat(i, ((Number) value).floatValue());
                } else if (value instanceof String) {
                    pstmt.setFloat(i, Float.parseFloat((String) value));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.FLOAT:
            case Types.DOUBLE:
                if (value instanceof Number) {
                    pstmt.setDouble(i, ((Number) value).doubleValue());
                } else if (value instanceof String) {
                    pstmt.setDouble(i, Double.parseDouble((String) value));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                if (value instanceof Blob) {
                    pstmt.setBlob(i, (Blob) value);
                } else if (value instanceof byte[]) {
                    pstmt.setBytes(i, (byte[]) value);
                } else if (value instanceof String) {
                    pstmt.setBytes(i, ((String) value).getBytes(StandardCharsets.ISO_8859_1));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.CLOB:
                if (value instanceof Clob) {
                    pstmt.setClob(i, (Clob) value);
                } else if (value instanceof byte[]) {
                    pstmt.setBytes(i, (byte[]) value);
                } else if (value instanceof String) {
                    Reader clobReader = new StringReader((String) value);
                    pstmt.setCharacterStream(i, clobReader);
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.DATE:
                if (value instanceof java.sql.Date) {
                    pstmt.setDate(i, (java.sql.Date) value);
                } else if (value instanceof java.util.Date) {
                    pstmt.setDate(i, new java.sql.Date(((java.util.Date) value).getTime()));
                } else if (value instanceof String) {
                    String v = (String) value;
                    if (!v.startsWith("0000-00-00")) {
                        java.util.Date date = Utils.parseDate(v);
                        if (date != null) {
                            pstmt.setDate(i, new Date(date.getTime()));
                        } else {
                            pstmt.setNull(i, type);
                        }
                    } else {
                        pstmt.setObject(i, value);
                    }
                } else if (value instanceof java.time.LocalDateTime) {
                    java.time.LocalDateTime ldt = (java.time.LocalDateTime) value;
                    pstmt.setDate(i, java.sql.Date.valueOf(ldt.toLocalDate()));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.TIME:
                if (value instanceof java.sql.Time) {
                    pstmt.setTime(i, (java.sql.Time) value);
                } else if (value instanceof java.util.Date) {
                    pstmt.setTime(i, new java.sql.Time(((java.util.Date) value).getTime()));
                } else if (value instanceof String) {
                    String v = (String) value;
                    java.util.Date date = Utils.parseDate(v);
                    if (date != null) {
                        pstmt.setTime(i, new Time(date.getTime()));
                    } else {
                        pstmt.setNull(i, type);
                    }
                } else if (value instanceof java.time.LocalDateTime) {
                    java.time.LocalDateTime ldt = (java.time.LocalDateTime) value;
                    pstmt.setTime(i, java.sql.Time.valueOf(ldt.toLocalTime()));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            case Types.TIMESTAMP:
                if (value instanceof java.sql.Timestamp) {
                    pstmt.setTimestamp(i, (java.sql.Timestamp) value);
                } else if (value instanceof java.util.Date) {
                    pstmt.setTimestamp(i, new java.sql.Timestamp(((java.util.Date) value).getTime()));
                } else if (value instanceof String) {
                    String v = (String) value;
                    if (!v.startsWith("0000-00-00")) {
                        java.util.Date date = Utils.parseDate(v);
                        if (date != null) {
                            pstmt.setTimestamp(i, new Timestamp(date.getTime()));
                        } else {
                            pstmt.setNull(i, type);
                        }
                    } else {
                        pstmt.setObject(i, value);
                    }
                } else if (value instanceof java.time.LocalDateTime) {
                    java.time.LocalDateTime ldt = (java.time.LocalDateTime) value;
                    pstmt.setTimestamp(i, java.sql.Timestamp.valueOf(ldt));
                } else {
                    pstmt.setNull(i, type);
                }
                break;
            default:
                pstmt.setObject(i, value, type);
        }
    }

    /**
     * 通用日期时间字符解析
     *
     * @param datetimeStr 日期时间字符串
     * @return Date
     */
    public static java.util.Date parseDate(String datetimeStr) {
        if (StringUtils.isEmpty(datetimeStr)) {
            return null;
        }
        datetimeStr = datetimeStr.trim();
        if (datetimeStr.contains("-")) {
            if (datetimeStr.contains(":")) {
                datetimeStr = datetimeStr.replace(" ", "T");
            }
        } else if (datetimeStr.contains(":")) {
            datetimeStr = "T" + datetimeStr;
        }
        DateTime dateTime = new DateTime(datetimeStr, dateTimeZone);
        return dateTime.toDate();
    }

    /**
     * Parse a string representation of a timestamp into a LocalDateTime object.
     * Supports multiple common datetime formats.
     *
     * @param timeStr The string representation of the timestamp
     * @return The parsed LocalDateTime, or null if parsing fails
     */
    public static LocalDateTime parseDateTime(String timeStr) {
        if (StringUtils.isBlank(timeStr)) {
            return null;
        }
        
        // Common date-time formats
        String[] patterns = {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy/MM/dd HH:mm:ss",
            "yyyyMMdd HHmmss"
        };
        
        for (String pattern : patterns) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
                return LocalDateTime.parse(timeStr, formatter);
            } catch (DateTimeParseException e) {
                // Try the next pattern
            }
        }
        
        // Log if all parsing attempts failed
        log.warn("Failed to parse datetime string: {}", timeStr);
        return null;
    }

}
