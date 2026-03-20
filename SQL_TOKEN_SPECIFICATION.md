# SQL Token 规范文档

## 目录
1. [官方标准](#官方标准)
2. [Token 分类](#token-分类)
3. [关键字和保留字](#关键字和保留字)
4. [标识符规则](#标识符规则)
5. [字面量规则](#字面量规则)
6. [运算符](#运算符)
7. [分隔符](#分隔符)
8. [注释](#注释)
9. [参考资源](#参考资源)

---

## 官方标准

### ISO/IEC 9075 SQL 标准

SQL 的词法结构在 **ISO/IEC 9075-2:2023 (SQL/Foundation)** 中定义，具体章节：

- **5.2 <token> and <separator>** - Token 和分隔符定义
- **5.3 <literal>** - 字面量
- **5.4 Names and identifiers** - 名称和标识符
- **Annex C - SQL Reserved Words** - 保留字列表

### MySQL 官方文档

🔗 https://dev.mysql.com/doc/refman/8.0/en/lexical-structure.html

MySQL 的词法结构遵循 SQL 标准，并有自己的扩展。

---

## Token 分类

根据 SQL 标准，Token 分为以下几类：

```
Token
├── Keywords (关键字)
│   ├── Reserved Keywords (保留关键字)
│   └── Non-reserved Keywords (非保留关键字)
├── Identifiers (标识符)
│   ├── Regular Identifiers (常规标识符)
│   └── Delimited Identifiers (界定标识符)
├── Literals (字面量)
│   ├── String Literals (字符串字面量)
│   ├── Numeric Literals (数字字面量)
│   ├── Date/Time Literals (日期时间字面量)
│   ├── Boolean Literals (布尔字面量)
│   └── NULL Literal (空值字面量)
├── Operators (运算符)
│   ├── Comparison Operators (比较运算符)
│   ├── Logical Operators (逻辑运算符)
│   ├── Arithmetic Operators (算术运算符)
│   └── String Operators (字符串运算符)
├── Delimiters (分隔符)
│   ├── Parentheses (括号)
│   ├── Commas (逗号)
│   ├── Semicolons (分号)
│   └── Dots (点)
└── Comments (注释)
    ├── Single-line Comments (单行注释)
    └── Multi-line Comments (多行注释)
```

---

## 关键字和保留字

### SQL 标准保留字（部分）

根据 SQL:2023 标准，以下是主要的保留关键字：

```sql
-- A
ABSOLUTE, ACTION, ADD, AFTER, ALL, ALLOCATE, ALTER, AND, ANY, ARE, 
ARRAY, AS, ASC, ASSERTION, AT, AUTHORIZATION, AVG

-- B
BEFORE, BEGIN, BETWEEN, BIGINT, BINARY, BIT, BLOB, BOOLEAN, BOTH, BY

-- C
CALL, CASCADE, CASCADED, CASE, CAST, CATALOG, CHAR, CHARACTER, CHECK, 
CLOB, CLOSE, COALESCE, COLLATE, COLUMN, COMMIT, CONDITION, CONNECT, 
CONSTRAINT, CONTINUE, CORRESPONDING, COUNT, CREATE, CROSS, CUBE, 
CURRENT, CURSOR

-- D
DATE, DAY, DEALLOCATE, DEC, DECIMAL, DECLARE, DEFAULT, DEFERRABLE, 
DEFERRED, DELETE, DESC, DESCRIBE, DESCRIPTOR, DETERMINISTIC, 
DIAGNOSTICS, DISCONNECT, DISTINCT, DO, DOMAIN, DOUBLE, DROP, DYNAMIC

-- E
EACH, ELSE, ELSEIF, END, ESCAPE, EXCEPT, EXCEPTION, EXEC, EXECUTE, 
EXISTS, EXIT, EXTERNAL, EXTRACT

-- F
FALSE, FETCH, FILTER, FIRST, FLOAT, FOR, FOREIGN, FOUND, FROM, FULL, 
FUNCTION

-- G
GET, GLOBAL, GO, GOTO, GRANT, GROUP, GROUPING

-- H
HANDLER, HAVING, HOLD, HOUR

-- I
IDENTITY, IF, IMMEDIATE, IN, INDICATOR, INNER, INOUT, INPUT, INSENSITIVE, 
INSERT, INT, INTEGER, INTERSECT, INTERVAL, INTO, IS, ISOLATION, ITERATE

-- J
JOIN

-- K
KEY

-- L
LANGUAGE, LARGE, LAST, LATERAL, LEADING, LEAVE, LEFT, LEVEL, LIKE, 
LIMIT, LOCAL, LOCALTIME, LOCALTIMESTAMP, LOOP

-- M
MATCH, MAX, MEMBER, MERGE, METHOD, MIN, MINUTE, MODIFIES, MODULE, MONTH

-- N
NATIONAL, NATURAL, NCHAR, NCLOB, NEW, NEXT, NO, NONE, NOT, NULL, NULLIF, 
NUMERIC

-- O
OBJECT, OF, OLD, ON, ONLY, OPEN, OPTION, OR, ORDER, OUT, OUTER, OUTPUT, 
OVER, OVERLAPS

-- P
PAD, PARAMETER, PARTIAL, PARTITION, PATH, POSITION, PRECISION, PREPARE, 
PRESERVE, PRIMARY, PRIOR, PRIVILEGES, PROCEDURE, PUBLIC

-- R
RANGE, READ, READS, REAL, RECURSIVE, REF, REFERENCES, REFERENCING, 
RELATIVE, RELEASE, REPEAT, RESIGNAL, RESTRICT, RESULT, RETURN, RETURNS, 
REVOKE, RIGHT, ROLLBACK, ROLLUP, ROW, ROWS

-- S
SAVEPOINT, SCHEMA, SCOPE, SCROLL, SEARCH, SECOND, SECTION, SELECT, 
SENSITIVE, SESSION, SET, SIGNAL, SIMILAR, SIZE, SMALLINT, SOME, SPACE, 
SPECIFIC, SQL, SQLCODE, SQLERROR, SQLEXCEPTION, SQLSTATE, SQLWARNING, 
START, STATIC, SUBMULTISET, SUBSTRING, SUM, SYMMETRIC, SYSTEM

-- T
TABLE, TEMPORARY, THEN, TIME, TIMESTAMP, TIMEZONE_HOUR, TIMEZONE_MINUTE, 
TO, TRAILING, TRANSACTION, TRANSLATE, TRANSLATION, TREAT, TRIGGER, TRIM, 
TRUE

-- U
UNDO, UNION, UNIQUE, UNKNOWN, UNNEST, UNTIL, UPDATE, USAGE, USER, USING

-- V
VALUE, VALUES, VARCHAR, VARYING, VIEW

-- W
WHEN, WHENEVER, WHERE, WHILE, WINDOW, WITH, WITHIN, WITHOUT, WORK, WRITE

-- Y
YEAR

-- Z
ZONE
```

### MySQL 特有关键字（部分）

```sql
-- MySQL 扩展
AUTO_INCREMENT, CHARSET, COLLATION, COMMENT, DATABASE, 
DELAYED, DISTINCT, ENGINE, ENUM, EXPLAIN, FORCE, FULLTEXT, 
HIGH_PRIORITY, IGNORE, INDEX, INFILE, KEYS, KILL, 
LIMIT (MySQL 特色), LOAD, LOCK, LOW_PRIORITY, 
OPTIMIZE, OUTFILE,QUER, REGEXP, RENAME, REPLACE, 
SHOW, SPATIAL, SSL, STRAIGHT_JOIN, TABLES, 
TERMINATED, UNLOCK, UNSIGNED, USE, ZEROFILL
```

### 非保留关键字

这些关键字可以用作标识符（不推荐）：

```sql
ACTION, AFTER, AGGREGATE, ALIAS, ALWAYS, ANALYZE, ASC, ATTRIBUTE,
BEFORE, BERNOULLI, BREADTH, CASCADE, CATALOG, CHARACTERISTICS,
CLASS, CLUSTERING, COLUMN, COLUMNS, COMMENTS, COMPLETION, CONDITION,
CONNECTION, CONSTRAINTS, CONSTRUCTOR, CONTINUE, CYCLE, DATA, DEPTH,
DESC, DIAGNOSTICS, DOMAIN, EQUALS, EXCEPTION, EXCLUDE, EXCLUDING,
FINAL, FIRST, FOLLOWING, GENERATED, IDENTITY, IGNORE, INCLUDING,
INCREMENT, INITIALLY, INPUT, ISOLATION, LAST, LEVEL, MAXVALUE,
MINVALUE, NAME, NAMES, NEXT, NO, NULLS, OBJECT, OPTION, OPTIONS,
ORDERING, OTHERS, OUTPUT, OVER, OVERRIDING, PAD, PARAMETER,
PARAMETERS, PARTIAL, PARTITION, PATH, PLACING, PRECEDING, PRIVILEGES,
RANGE, READ, RELATIVE, RENAME, REPEATABLE, RESPECT, RESTART, RESTRICT,
ROLE, ROUTINE, ROW, ROWS, SCHEMA, SECTION, SEQUENCE, SERIALIZABLE,
SESSION, SIMPLE, SIZE, SOURCE, SPACE, START, STATE, STATEMENT,
STRUCTURE, STYLE, SUBLIST, TABLESAMPLE, TEMPORARY, TIES, TRANSACTION,
TRANSFORM, TRIGGER, TYPE, UNBOUNDED, UNCOMMITTED, UNDER, USAGE,
USER, VIEW, WORK, WRITE, ZONE
```

---

## 标识符规则

### 常规标识符 (Regular Identifiers)

根据 SQL 标准：

**规则**:
```
<identifier> ::= <identifier start> [<identifier part>...]

<identifier start> ::= <letter> | "_"

<identifier part> ::= <letter> | <digit> | "_"
```

**示例**:
```sql
user_name      -- 合法
UserName       -- 合法
_private       -- 合法
table123       -- 合法
123table       -- 非法（不能以数字开头）
user-name      -- 非法（不能包含连字符）
```

### 界定标识符 (Delimited Identifiers)

使用反引号（MySQL）或双引号（SQL 标准）界定：

**MySQL 语法**:
```sql
`select`       -- 使用保留字作为标识符
`user-name`    -- 包含特殊字符
`table 1`      -- 包含空格
```

**SQL 标准语法**:
```sql
"select"       -- 使用保留字作为标识符
"user-name"    -- 包含特殊字符
"table 1"      -- 包含空格
```

### 大小写规则

**SQL 标准**:
- 关键字不区分大小写：`SELECT` = `select` = `SeLeCt`
- 常规标识符不区分大小写（会被转换为大写）
- 界定标识符区分大小写

**MySQL**:
- 关键字不区分大小写
- 标识符大小写敏感性取决于操作系统：
  - Linux: 区分大小写
  - Windows/macOS: 不区分大小写（默认）

### 长度限制

- **SQL 标准**: 最少支持 128 字符
- **MySQL**: 
  - 数据库名/表名: 64 字符
  - 列名: 64 字符
  - 索引名: 64 字符
  - 别名: 256 字符

---

## 字面量规则

### 字符串字面量

#### 标准语法
```sql
-- 单引号
'Hello World'
'It''s a test'        -- 转义单引号

-- 字符串连接（SQL 标准）
'Hello' 'World'       -- 结果: 'HelloWorld'
```

#### MySQL 扩展
```sql
-- 单引号或双引号
'Hello World'
"Hello World"

-- 转义字符
'Hello\nWorld'        -- 换行
'It\'s a test'        -- 转义单引号
'Path: C:\\data'      -- 反斜杠

-- 特殊字符串
_utf8'你好'           -- 字符集前缀
N'Unicode string'     -- 国家字符集
```

#### 转义序列
```
\0    - ASCII NUL (0x00)
\'    - 单引号
\"    - 双引号
\b    - 退格
\n    - 换行
\r    - 回车
\t    - 制表符
\\    - 反斜杠
\%    - % 字符
\_    - _ 字符
```

### 数字字面量

#### 整数
```sql
42              -- 十进制
0x2A            -- 十六进制 (MySQL)
0b101010        -- 二进制 (MySQL)
```

#### 浮点数
```sql
3.14
-0.001
1.23E-10        -- 科学计数法
1.23e+10
```

#### 精确数值
```sql
DECIMAL '123.45'
NUMERIC '123.45'
```

### 日期时间字面量

#### SQL 标准语法
```sql
DATE '2023-12-31'
TIME '23:59:59'
TIMESTAMP '2023-12-31 23:59:59'
INTERVAL '5' DAY
```

#### MySQL 语法
```sql
'2023-12-31'                    -- 日期字符串
'23:59:59'                      -- 时间字符串
'2023-12-31 23:59:59'           -- 日期时间字符串
INTERVAL 5 DAY                  -- 时间间隔
```

### 布尔字面量

```sql
TRUE
FALSE
UNKNOWN         -- 三值逻辑
```

### NULL 字面量

```sql
NULL            -- 空值
```

### 十六进制和位值字面量（MySQL）

```sql
-- 十六进制
X'4D7953514C'   -- 标准语法
0x4D7953514C    -- MySQL 语法
x'0a0b0c'       -- 小写也可以

-- 位值
B'1010'         -- 标准语法
0b1010          -- MySQL 语法
b'1010'         -- 小写也可以
```

---

## 运算符

### 比较运算符

```sql
=               -- 等于
<> 或 !=        -- 不等于
<               -- 小于
>               -- 大于
<=              -- 小于等于
>=              -- 大于等于
<=>             -- NULL 安全等于 (MySQL)
IS              -- IS NULL, IS NOT NULL
IS NOT          
IN              -- 在集合中
NOT IN          
BETWEEN         -- 范围
NOT BETWEEN     
LIKE            -- 模式匹配
NOT LIKE        
REGEXP          -- 正则表达式 (MySQL)
NOT REGEXP      
```

### 逻辑运算符

```sql
AND             -- 逻辑与
OR              -- 逻辑或
NOT             -- 逻辑非
XOR             -- 异或 (MySQL)
```

**优先级（从高到低）**:
1. `NOT`
2. `AND`
3. `OR`, `XOR`

### 算术运算符

```sql
+               -- 加
-               -- 减
*               -- 乘
/               -- 除
DIV             -- 整除 (MySQL)
%               -- 取模
MOD             -- 取模（函数形式）
```

### 位运算符（MySQL）

```sql
&               -- 按位与
|               -- 按位或
^               -- 按位异或
~               -- 按位取反
<<              -- 左移
>>              -- 右移
```

### 赋值运算符

```sql
:=              -- 赋值 (MySQL)
=               -- 赋值（在 SET 语句中）
```

### 字符串运算符

```sql
||              -- 字符串连接（SQL 标准）
CONCAT()        -- 字符串连接函数（MySQL）
```

### 运算符优先级（完整）

从高到低：

```
1.  INTERVAL
2.  BINARY, COLLATE
3.  !
4.  - (一元减), ~ (位取反)
5.  ^ (异或)
6.  *, /, DIV, %, MOD
7.  -, + (算术)
8.  <<, >>
9.  &
10. |
11. = (比较), <=>, >=, >, <=, <, <>, !=, IS, LIKE, REGEXP, IN
12. BETWEEN, CASE, WHEN, THEN, ELSE
13. NOT
14. AND, &&
15. XOR
16. OR, ||
17. = (赋值), :=
```

---

## 分隔符

### 标准分隔符

```sql
(       -- 左括号
)       -- 右括号
,       -- 逗号
;       -- 分号（语句终止符）
.       -- 点（限定符）
```

### MySQL 特有分隔符

```sql
`       -- 反引号（标识符界定符）
@@      -- 系统变量前缀
@       -- 用户变量前缀
#       -- 单行注释
--      -- 单行注释（后面必须有空格）
/*      -- 多行注释开始
*/      -- 多行注释结束
```

---

## 注释

### 单行注释

#### SQL 标准
```sql
-- 这是注释（-- 后必须有空格）
SELECT * FROM users -- 行尾注释
```

#### MySQL 扩展
```sql
# 这也是注释（MySQL 特有）
SELECT * FROM users # 行尾注释
```

### 多行注释

```sql
/* 这是
   多行
   注释 */

SELECT * /* 内联注释 */ FROM users

/* 嵌套注释不支持
   /* 这样会出错 */
*/
```

### MySQL 可执行注释

MySQL 特有的条件注释：

```sql
/*! MySQL 特定代码 */
/*!50000 MySQL 5.0 及以上版本执行 */
/*!80000 MySQL 8.0 及以上版本执行 */

-- 示例
CREATE TABLE t1 (
  id INT PRIMARY KEY,
  data VARCHAR(100) /*!50600 COMPRESSED */
);
```

---

## 空白字符

SQL 标准定义的空白字符：

```
Space           ( )     U+0020
Tab             (\t)    U+0009
Newline         (\n)    U+000A
Carriage Return (\r)    U+000D
Form Feed       (\f)    U+000C
```

**规则**:
- 空白字符用于分隔 Token
- 多个连续空白字符等同于一个
- 在字符串字面量内部保留空白字符

---

## Token 识别优先级

当出现歧义时，遵循"最长匹配"原则：

```sql
-- 示例 1: >= 还是 > 和 =？
WHERE age >= 18     -- 识别为 >=（最长匹配）

-- 示例 2: 123.45 是一个 Token 还是三个？
SELECT 123.45       -- 识别为一个数字字面量

-- 示例 3: user_id 还是 user, _, id？
SELECT user_id      -- 识别为一个标识符（最长匹配）
```

---

## 字符集和排序规则

### 字符集前缀（MySQL）

```sql
_utf8'字符串'
_latin1'string'
_binary'data'

-- 示例
SELECT _utf8'你好' COLLATE utf8_general_ci;
```

### 国家字符集前缀（SQL 标准）

```sql
N'Unicode string'

-- 等价于
_ucs2'Unicode string'
```

---

## 词法规则总结

### Token 识别流程

```
1. 跳过空白字符
2. 检查是否是注释
3. 检查是否是字符串字面量（引号开头）
4. 检查是否是数字字面量（数字开头）
5. 检查是否是标识符或关键字（字母或下划线开头）
6. 检查是否是运算符或分隔符
7. 错误：非法字符
```

### BNF 形式的词法规则

```bnf
<token> ::= <keyword> | <identifier> | <literal> | <operator> | <delimiter>

<keyword> ::= SELECT | INSERT | UPDATE | DELETE | ...

<identifier> ::= <regular_identifier> | <delimited_identifier>

<regular_identifier> ::= <identifier_start> [<identifier_part>]*
<identifier_start> ::= <letter> | "_"
<identifier_part> ::= <letter> | <digit> | "_"

<delimited_identifier> ::= "`" <identifier_body> "`"
                         | '"' <identifier_body> '"'

<literal> ::= <string_literal> | <numeric_literal> | <datetime_literal> 
            | <boolean_literal> | <null_literal>

<string_literal> ::= "'" [<string_character>]* "'"

<numeric_literal> ::= <integer> | <decimal> | <float>

<integer> ::= [<sign>] <digit>+

<decimal> ::= [<sign>] <digit>+ "." <digit>+
            | [<sign>] "." <digit>+

<float> ::= <decimal> "E" [<sign>] <digit>+

<operator> ::= "=" | "<>" | "!=" | "<" | ">" | "<=" | ">=" 
             | "+" | "-" | "*" | "/" | "%" 
             | "AND" | "OR" | "NOT" | ...

<delimiter> ::= "(" | ")" | "," | ";" | "."

<letter> ::= "A" | "B" | ... | "Z" | "a" | "b" | ... | "z"

<digit> ::= "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9"

<sign> ::= "+" | "-"
```

---

## 参考资源

### 官方文档

1. **SQL 标准**
   - ISO/IEC 9075-1:2023 - SQL Framework
   - ISO/IEC 9075-2:2023 - SQL Foundation
   - 🔗 https://www.iso.org/standard/76583.html

2. **MySQL 文档**
   - Lexical Structure: https://dev.mysql.com/doc/refman/8.0/en/lexical-structure.html
   - Keywords and Reserved Words: https://dev.mysql.com/doc/refman/8.0/en/keywords.html
   - String Literals: https://dev.mysql.com/doc/refman/8.0/en/string-literals.html
   - Identifiers: https://dev.mysql.com/doc/refman/8.0/en/identifiers.html

3. **PostgreSQL 文档**
   - Lexical Structure: https://www.postgresql.org/docs/current/sql-syntax-lexical.html

4. **Oracle 文档**
   - SQL Language Reference: https://docs.oracle.com/en/database/oracle/oracle-database/

5. **SQL Server 文档**
   - Transact-SQL Reference: https://docs.microsoft.com/en-us/sql/t-sql/language-reference

### 在线工具

1. **SQL 格式化器**: https://www.dpriver.com/pp/sqlformat.htm
2. **SQL 语法检查器**: https://www.eversql.com/sql-syntax-check-validator/
3. **SQL 标准文档（非官方）**: http://www.contrib.andrew.cmu.edu/~shadow/sql/

### 参考书籍

1. **《SQL-92 标准》** - ANSI/ISO 官方文档
2. **《SQL and Relational Theory》** - C.J. Date
3. **《Database System Concepts》** - Silberschatz, Korth, Sudarshan
4. **《MySQL 技术内幕》** - Paul DuBois

### 开源项目参考

1. **MySQL Parser** (C++): https://github.com/mysql/mysql-server/tree/8.0/sql
2. **PostgreSQL Parser** (C): https://github.com/postgres/postgres/tree/master/src/backend/parser
3. **SQLite Parser** (C): https://github.com/sqlite/sqlite/blob/master/src/parse.y
4. **JSqlParser** (Java): https://github.com/JSQLParser/JSqlParser
5. **sqlparse** (Python): https://github.com/andialbrecht/sqlparse

---

## 附录：MySQL 8.0 完整保留字列表

```sql
ACCESSIBLE, ACCOUNT, ACTION, ACTIVE, ADD, ADMIN, AFTER, AGAINST, AGGREGATE,
ALGORITHM, ALL, ALTER, ALWAYS, ANALYZE, AND, ANY, ARRAY, AS, ASC, ASCII,
ASENSITIVE, AT, ATTRIBUTE, AUTOEXTEND_SIZE, AUTO_INCREMENT, AVG, AVG_ROW_LENGTH,
BACKUP, BEFORE, BEGIN, BETWEEN, BIGINT, BINARY, BINLOG, BIT, BLOB, BLOCK, BOOL,
BOOLEAN, BOTH, BTREE, BY, BYTE, CACHE, CALL, CASCADE, CASCADED, CASE, CATALOG_NAME,
CHAIN, CHANGE, CHANGED, CHANNEL, CHAR, CHARACTER, CHARSET, CHECK, CHECKSUM, CIPHER,
CLASS_ORIGIN, CLIENT, CLONE, CLOSE, COALESCE, CODE, COLLATE, COLLATION, COLUMN,
COLUMNS, COLUMN_FORMAT, COLUMN_NAME, COMMENT, COMMIT, COMMITTED, COMPACT, COMPLETION,
COMPONENT, COMPRESSED, COMPRESSION, CONCURRENT, CONDITION, CONNECTION, CONSISTENT,
CONSTRAINT, CONSTRAINT_CATALOG, CONSTRAINT_NAME, CONSTRAINT_SCHEMA, CONTAINS, CONTEXT,
CONTINUE, CONVERT, CPU, CREATE, CROSS, CUBE, CUME_DIST, CURRENT, CURRENT_DATE,
CURRENT_TIME, CURRENT_TIMESTAMP, CURRENT_USER, CURSOR, CURSOR_NAME, DATA, DATABASE,
DATABASES, DATAFILE, DATE, DATETIME, DAY, DAY_HOUR, DAY_MICROSECOND, DAY_MINUTE,
DAY_SECOND, DEALLOCATE, DEC, DECIMAL, DECLARE, DEFAULT, DEFAULT_AUTH, DEFINER,
DEFINITION, DELAYED, DELAY_KEY_WRITE, DELETE, DENSE_RANK, DESC, DESCRIBE, DESCRIPTION,
DES_KEY_FILE, DETERMINISTIC, DIAGNOSTICS, DIRECTORY, DISABLE, DISCARD, DISK, DISTINCT,
DISTINCTROW, DIV, DO, DOUBLE, DROP, DUAL, DUMPFILE, DUPLICATE, DYNAMIC, EACH, ELSE,
ELSEIF, EMPTY, ENABLE, ENCLOSED, ENCRYPTION, END, ENDS, ENFORCED, ENGINE, ENGINES,
ENGINE_ATTRIBUTE, ENUM, ERROR, ERRORS, ESCAPE, ESCAPED, EVENT, EVENTS, EVERY, EXCEPT,
EXCHANGE, EXCLUDE, EXECUTE, EXISTS, EXIT, EXPANSION, EXPIRE, EXPLAIN, EXPORT, EXTENDED,
EXTENT_SIZE, FACTOR, FAILED_LOGIN_ATTEMPTS, FALSE, FAST, FAULTS, FETCH, FIELDS, FILE,
FILE_BLOCK_SIZE, FILTER, FINISH, FIRST, FIRST_VALUE, FIXED, FLOAT, FLOAT4, FLOAT8,
FLUSH, FOLLOWING, FOLLOWS, FOR, FORCE, FOREIGN, FORMAT, FOUND, FROM, FULL, FULLTEXT,
FUNCTION, GENERAL, GENERATE, GENERATED, GEOMCOLLECTION, GEOMETRY, GEOMETRYCOLLECTION,
GET, GET_FORMAT, GET_MASTER_PUBLIC_KEY, GET_SOURCE_PUBLIC_KEY, GLOBAL, GRANT, GRANTS,
GROUP, GROUPING, GROUPS, GROUP_REPLICATION, GTID_ONLY, HANDLER, HASH, HAVING, HELP,
HIGH_PRIORITY, HISTOGRAM, HISTORY, HOST, HOSTS, HOUR, HOUR_MICROSECOND, HOUR_MINUTE,
HOUR_SECOND, IDENTIFIED, IF, IGNORE, IGNORE_SERVER_IDS, IMPORT, IN, INACTIVE, INDEX,
INDEXES, INFILE, INITIAL, INITIAL_SIZE, INITIATE, INNER, INOUT, INSENSITIVE, INSERT,
INSERT_METHOD, INSTALL, INSTANCE, INT, INT1, INT2, INT3, INT4, INT8, INTEGER, INTERVAL,
INTO, INVISIBLE, INVOKER, IO, IO_AFTER_GTIDS, IO_BEFORE_GTIDS, IO_THREAD, IPC, IS,
ISOLATION, ISSUER, ITERATE, JOIN, JSON, JSON_TABLE, JSON_VALUE, KEY, KEYRING, KEYS,
KEY_BLOCK_SIZE, KILL, LAG, LANGUAGE, LAST, LAST_VALUE, LATERAL, LEAD, LEADING, LEAVE,
LEAVES, LEFT, LESS, LEVEL, LIKE, LIMIT, LINEAR, LINES, LINESTRING, LIST, LOAD, LOCAL,
LOCALTIME, LOCALTIMESTAMP, LOCK, LOCKED, LOCKS, LOGFILE, LOGS, LONG, LONGBLOB, LONGTEXT,
LOOP, LOW_PRIORITY, MASTER, MASTER_AUTO_POSITION, MASTER_BIND, MASTER_COMPRESSION_ALGORITHMS,
MASTER_CONNECT_RETRY, MASTER_DELAY, MASTER_HEARTBEAT_PERIOD, MASTER_HOST, MASTER_LOG_FILE,
MASTER_LOG_POS, MASTER_PASSWORD, MASTER_PORT, MASTER_PUBLIC_KEY_PATH, MASTER_RETRY_COUNT,
MASTER_SERVER_ID, MASTER_SSL, MASTER_SSL_CA, MASTER_SSL_CAPATH, MASTER_SSL_CERT,
MASTER_SSL_CIPHER, MASTER_SSL_CRL, MASTER_SSL_CRLPATH, MASTER_SSL_KEY,
MASTER_SSL_VERIFY_SERVER_CERT, MASTER_TLS_CIPHERSUITES, MASTER_TLS_VERSION, MASTER_USER,
MASTER_ZSTD_COMPRESSION_LEVEL, MATCH, MAXVALUE, MAX_CONNECTIONS_PER_HOUR, MAX_QUERIES_PER_HOUR,
MAX_ROWS, MAX_SIZE, MAX_UPDATES_PER_HOUR, MAX_USER_CONNECTIONS, MEDIUM, MEDIUMBLOB,
MEDIUMINT, MEDIUMTEXT, MEMBER, MEMORY, MERGE, MESSAGE_TEXT, MICROSECOND, MIDDLEINT, MIGRATE,
MINUTE, MINUTE_MICROSECOND, MINUTE_SECOND, MIN_ROWS, MOD, MODE, MODIFIES, MODIFY, MONTH,
MULTILINESTRING, MULTIPOINT, MULTIPOLYGON, MUTEX, MYSQL_ERRNO, NAME, NAMES, NATIONAL, NATURAL,
NCHAR, NDB, NDBCLUSTER, NESTED, NETWORK_NAMESPACE, NEVER, NEW, NEXT, NO, NODEGROUP, NONE,
NOT, NOWAIT, NO_WAIT, NO_WRITE_TO_BINLOG, NTH_VALUE, NTILE, NULL, NULLS, NUMBER, NUMERIC,
NVARCHAR, OF, OFF, OFFSET, OJ, OLD, ON, ONE, ONLY, OPEN, OPTIMIZE, OPTIMIZER_COSTS, OPTION,
OPTIONAL, OPTIONALLY, OPTIONS, OR, ORDER, ORDINALITY, ORGANIZATION, OTHERS, OUT, OUTER,
OUTFILE, OVER, OWNER, PACK_KEYS, PAGE, PARSER, PARSE_GCOL_EXPR, PARTIAL, PARTITION,
PARTITIONING, PARTITIONS, PASSWORD, PASSWORD_LOCK_TIME, PATH, PERCENT_RANK, PERSIST,
PERSIST_ONLY, PHASE, PLUGIN, PLUGINS, PLUGIN_DIR, POINT, POLYGON, PORT, PRECEDES, PRECEDING,
PRECISION, PREPARE, PRESERVE, PREV, PRIMARY, PRIVILEGES, PRIVILEGE_CHECKS_USER, PROCEDURE,
PROCESS, PROCESSLIST, PROFILE, PROFILES, PROXY, PURGE, QUARTER, QUERY, QUICK, RANDOM, RANGE,
RANK, READ, READS, READ_ONLY, READ_WRITE, REAL, REBUILD, RECOVER, RECURSIVE, REDOFILE,
REDO_BUFFER_SIZE, REDUNDANT, REFERENCE, REFERENCES, REGEXP, REGISTRATION, RELAY, RELAYLOG,
RELAY_LOG_FILE, RELAY_LOG_POS, RELAY_THREAD, RELEASE, RELOAD, REMOTE, REMOVE, RENAME,
REORGANIZE, REPAIR, REPEAT, REPEATABLE, REPLACE, REPLICA, REPLICAS, REPLICATE_DO_DB,
REPLICATE_DO_TABLE, REPLICATE_IGNORE_DB, REPLICATE_IGNORE_TABLE, REPLICATE_REWRITE_DB,
REPLICATE_WILD_DO_TABLE, REPLICATE_WILD_IGNORE_TABLE, REPLICATION, REQUIRE, REQUIRE_ROW_FORMAT,
RESET, RESIGNAL, RESOURCE, RESPECT, RESTART, RESTORE, RESTRICT, RESUME, RETAIN, RETURN, RETURNED_SQLSTATE,
RETURNING, RETURNS, REUSE, REVERSE, REVOKE, RIGHT, RLIKE, ROLE, ROLLBACK, ROLLUP, ROTATE, ROUTINE,
ROW, ROWS, ROW_COUNT, ROW_FORMAT, ROW_NUMBER, RTREE, SAVEPOINT, SCHEDULE, SCHEMA, SCHEMAS,
SCHEMA_NAME, SECOND, SECONDARY, SECONDARY_ENGINE, SECONDARY_ENGINE_ATTRIBUTE, SECONDARY_LOAD,
SECONDARY_UNLOAD, SECOND_MICROSECOND, SECURITY, SELECT, SENSITIVE, SEPARATOR, SERIAL, SERIALIZABLE,
SERVER, SESSION, SET, SHARE, SHOW, SHUTDOWN, SIGNAL, SIGNED, SIMPLE, SKIP, SLAVE, SLOW, SMALLINT,
SNAPSHOT, SOCKET, SOME, SONAME, SOUNDS, SOURCE, SOURCE_AUTO_POSITION, SOURCE_BIND, SOURCE_COMPRESSION_ALGORITHMS,
SOURCE_CONNECT_RETRY, SOURCE_DELAY, SOURCE_HEARTBEAT_PERIOD, SOURCE_HOST, SOURCE_LOG_FILE, SOURCE_LOG_POS,
SOURCE_PASSWORD, SOURCE_PORT, SOURCE_PUBLIC_KEY_PATH, SOURCE_RETRY_COUNT, SOURCE_SSL, SOURCE_SSL_CA,
SOURCE_SSL_CAPATH, SOURCE_SSL_CERT, SOURCE_SSL_CIPHER, SOURCE_SSL_CRL, SOURCE_SSL_CRLPATH, SOURCE_SSL_KEY,
SOURCE_SSL_VERIFY_SERVER_CERT, SOURCE_TLS_CIPHERSUITES, SOURCE_TLS_VERSION, SOURCE_USER, SOURCE_ZSTD_COMPRESSION_LEVEL,
SPATIAL, SPECIFIC, SQL, SQLEXCEPTION, SQLSTATE, SQLWARNING, SQL_AFTER_GTIDS, SQL_AFTER_MTS_GAPS,
SQL_BEFORE_GTIDS, SQL_BIG_RESULT, SQL_BUFFER_RESULT, SQL_CACHE, SQL_CALC_FOUND_ROWS, SQL_NO_CACHE,
SQL_SMALL_RESULT, SQL_THREAD, SQL_TSI_DAY, SQL_TSI_HOUR, SQL_TSI_MINUTE, SQL_TSI_MONTH, SQL_TSI_QUARTER,
SQL_TSI_SECOND, SQL_TSI_WEEK, SQL_TSI_YEAR, SRID, SSL, STACKED, START, STARTING, STARTS, STATS_AUTO_RECALC,
STATS_PERSISTENT, STATS_SAMPLE_PAGES, STATUS, STOP, STORAGE, STORED, STRAIGHT_JOIN, STREAM, STRING, SUBCLASS_ORIGIN,
SUBJECT, SUBPARTITION, SUBPARTITIONS, SUPER, SUSPEND, SWAPS, SWITCHES, SYSTEM, TABLE, TABLES, TABLESPACE,
TABLE_CHECKSUM, TABLE_NAME, TEMPORARY, TEMPTABLE, TERMINATED, TEXT, THAN, THEN, THREAD_PRIORITY, TIES, TIME,
TIMESTAMP, TIMESTAMPADD, TIMESTAMPDIFF, TINYBLOB, TINYINT, TINYTEXT, TLS, TO, TRAILING, TRANSACTION, TRIGGER,
TRIGGERS, TRUE, TRUNCATE, TYPE, TYPES, UNBOUNDED, UNCOMMITTED, UNDEFINED, UNDO, UNDOFILE, UNDO_BUFFER_SIZE,
UNICODE, UNINSTALL, UNION, UNIQUE, UNKNOWN, UNLOCK, UNREGISTER, UNSIGNED, UNTIL, UPDATE, UPGRADE, USAGE,
USE, USER, USER_RESOURCES, USE_FRM, USING, UTC_DATE, UTC_TIME, UTC_TIMESTAMP, VALIDATION, VALUE, VALUES,
VARBINARY, VARCHAR, VARCHARACTER, VARIABLES, VARYING, VCPU, VIEW, VIRTUAL, VISIBLE, WAIT, WARNINGS, WEEK,
WEIGHT_STRING, WHEN, WHERE, WHILE, WINDOW, WITH, WITHOUT, WORK, WRAPPER, WRITE, X509, XA, XID, XML, XOR,
YEAR, YEAR_MONTH, ZEROFILL, ZONE
```

---

**文档版本**: v1.0  
**最后更新**: 2026-03-18  
**参考标准**: ISO/IEC 9075:2023, MySQL 8.0
