CREATE TABLE `checksums` (
    db CHAR(64) NOT NULL,
    tbl CHAR(64) NOT NULL,
    chunk INT NOT NULL,
    chunk_time FLOAT NULL,
    chunk_index VARCHAR(200) NULL,
    lower_boundary TEXT NULL,
    upper_boundary TEXT NULL,
    this_crc CHAR(40) NOT NULL,
    this_cnt INT NOT NULL,
    master_crc CHAR(40) NULL,
    master_cnt INT NULL,
    ts TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (db, tbl, chunk),
    INDEX ts_db_tbl (ts, db, tbl)
) ENGINE = InnoDB DEFAULT CHARSET = utf8;