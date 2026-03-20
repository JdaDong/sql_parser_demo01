# SQL Parser Demo01 — 推荐实施路线图

> **文档版本**: v1.0
> **最后更新**: 2026-03-20
> **项目路径**: `sql_parser_demo01/`

---

## 📊 当前进度概览

| 类别 | 功能 | 状态 |
|------|------|------|
| 核心框架 | Lexer 词法分析器 | ✅ 已完成 |
| 核心框架 | Parser 递归下降语法分析器 | ✅ 已完成 |
| 核心框架 | AST 抽象语法树 | ✅ 已完成 |
| SQL 语句 | SELECT（DISTINCT / JOIN / WHERE / GROUP BY / HAVING / ORDER BY / LIMIT / OFFSET） | ✅ 已完成 |
| SQL 语句 | INSERT / UPDATE / DELETE | ✅ 已完成 |
| SQL 语句 | CREATE TABLE / DROP TABLE | ✅ 已完成 |
| 表达式 | 算术表达式（+、-、*、/） | ✅ 已完成 |
| 表达式 | 比较表达式（=、!=、<、>、<=、>=） | ✅ 已完成 |
| 表达式 | 逻辑表达式（AND、OR、NOT） | ✅ 已完成 |
| 聚合函数 | COUNT / SUM / AVG / MAX / MIN（支持 DISTINCT） | ✅ 已完成 |
| 谓词表达式 | IS NULL / IS NOT NULL | ✅ 已完成 |
| 谓词表达式 | BETWEEN / NOT BETWEEN | ✅ 已完成 |
| 谓词表达式 | IN / NOT IN | ✅ 已完成 |
| 谓词表达式 | LIKE / NOT LIKE | ✅ 已完成 |
| 工程 | build.sh 构建脚本 | ✅ 已完成 |
| 工程 | ScalaTest 单元测试 | ✅ 已完成 |

---

## 🗺️ 实施路线图

### 第一阶段：表达式与查询增强 ⭐ 优先级：高

> 目标：补齐 SQL 查询中最常用的表达式和子句能力

#### 1.1 子查询支持（Subquery）

**复杂度**: ⭐⭐⭐  
**预计工作量**: 中

支持在 SELECT、WHERE、FROM 中使用子查询：

```sql
-- WHERE 子查询
SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)

-- FROM 子查询（派生表）
SELECT t.name FROM (SELECT name FROM users WHERE age > 18) AS t

-- SELECT 子查询（标量子查询）
SELECT name, (SELECT COUNT(*) FROM orders WHERE orders.user_id = users.id) AS order_count FROM users

-- EXISTS 子查询
SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)
```

**实现要点**：
- AST：新增 `SubQuery(query: SelectStatement)` 表达式节点
- AST：新增 `ExistsExpression(subquery: SubQuery)` 节点
- AST：新增 `DerivedTable(subquery: SubQuery, alias: String)` 表引用
- Parser：在 `parsePrimaryExpression()` 中识别左括号 + SELECT → 子查询
- Parser：在 `parseTableReference()` 中识别子查询派生表
- Token：新增 `EXISTS` 关键字

---

#### 1.2 CASE 表达式

**复杂度**: ⭐⭐  
**预计工作量**: 中

```sql
-- 简单 CASE
SELECT name, CASE status WHEN 1 THEN 'active' WHEN 0 THEN 'inactive' ELSE 'unknown' END AS status_text FROM users

-- 搜索 CASE
SELECT name, CASE WHEN age < 18 THEN 'minor' WHEN age < 65 THEN 'adult' ELSE 'senior' END AS age_group FROM users
```

**实现要点**：
- AST：新增 `CaseExpression`（含 `WhenClause` 列表和 `ELSE` 分支）
- Token：新增 `CASE`、`WHEN`、`THEN`、`ELSE`、`END` 关键字
- Parser：新增 `parseCaseExpression()` 方法

---

#### 1.3 UNION / INTERSECT / EXCEPT

**复杂度**: ⭐⭐  
**预计工作量**: 小

```sql
SELECT name FROM students UNION SELECT name FROM teachers
SELECT name FROM students UNION ALL SELECT name FROM teachers
SELECT id FROM a INTERSECT SELECT id FROM b
SELECT id FROM a EXCEPT SELECT id FROM b
```

**实现要点**：
- AST：新增 `CompoundQuery(left, operator, right, all)` 语句节点
- Token：新增 `UNION`、`INTERSECT`、`EXCEPT`、`ALL` 关键字
- Parser：在 `parse()` 顶层检测 UNION/INTERSECT/EXCEPT

---

### 第二阶段：高级 SQL 特性 ⭐ 优先级：中高

> 目标：支持生产环境中常见的高级 SQL 语法

#### 2.1 窗口函数（Window Functions）

**复杂度**: ⭐⭐⭐  
**预计工作量**: 大

```sql
SELECT name, salary,
  ROW_NUMBER() OVER (ORDER BY salary DESC) AS rank,
  SUM(salary) OVER (PARTITION BY department ORDER BY hire_date) AS running_total
FROM employees
```

**实现要点**：
- AST：新增 `WindowFunction(func, partitionBy, orderBy, frame)` 节点
- AST：新增 `WindowFrame(type, start, end)` 节点
- Token：新增 `OVER`、`PARTITION`、`ROW_NUMBER`、`RANK`、`DENSE_RANK`、`ROWS`、`RANGE` 等关键字
- Parser：新增 `parseWindowFunction()` 和 `parseWindowSpec()` 方法

---

#### 2.2 CTE — WITH 子句（Common Table Expressions）

**复杂度**: ⭐⭐⭐  
**预计工作量**: 中

```sql
WITH active_users AS (
  SELECT * FROM users WHERE status = 'active'
),
user_orders AS (
  SELECT user_id, COUNT(*) AS cnt FROM orders GROUP BY user_id
)
SELECT u.name, uo.cnt
FROM active_users u
JOIN user_orders uo ON u.id = uo.user_id
```

**实现要点**：
- AST：新增 `WithClause(ctes: List[CTE])` 和 `CTE(name, columns, query)`
- Token：新增 `WITH` 关键字
- Parser：在 `parse()` 入口检测 WITH → 解析 CTE 列表 → 再解析主查询

---

#### 2.3 ALTER TABLE 语句

**复杂度**: ⭐⭐  
**预计工作量**: 中

```sql
ALTER TABLE users ADD COLUMN email VARCHAR(255)
ALTER TABLE users DROP COLUMN age
ALTER TABLE users MODIFY COLUMN name VARCHAR(200)
ALTER TABLE users RENAME TO customers
```

**实现要点**：
- AST：新增 `AlterTableStatement` 和 `AlterAction` 密封特质
- Token：新增 `ALTER`、`ADD`、`COLUMN`、`MODIFY`、`RENAME` 关键字
- Parser：新增 `parseAlterTable()` 方法

---

### 第三阶段：工程质量提升 ⭐ 优先级：中

> 目标：提升解析器的健壮性、易用性和可维护性

#### 3.1 增强错误报告（行号 / 列号）

**复杂度**: ⭐⭐  
**预计工作量**: 中

当前错误信息只包含 Token 类型，缺少位置上下文，不便于定位问题。

**改进方案**：
```scala
// Token 携带位置信息
case class TokenWithPosition(token: Token, line: Int, column: Int)

// 错误信息包含位置
case class ParseError(message: String, line: Int, column: Int, context: String)
// 示例输出:
// Parse error at line 3, column 15: Expected ')' but got ','
//   SELECT COUNT(id, FROM users
//                    ^
```

**实现要点**：
- Lexer：在扫描过程中跟踪行号和列号
- Token：每个 Token 附带 `Position(line, col)` 信息
- Parser：错误消息中包含位置和上下文

---

#### 3.2 SQL Pretty Printer（格式化输出）

**复杂度**: ⭐⭐  
**预计工作量**: 中

将 AST 反向转换为格式规范的 SQL 字符串，验证解析结果的正确性：

```scala
object SQLPrettyPrinter {
  def print(ast: SQLStatement): String = ast match {
    case s: SelectStatement => printSelect(s)
    case i: InsertStatement => printInsert(i)
    // ...
  }
}
```

**输出示例**：
```sql
SELECT DISTINCT
    u.name,
    COUNT(o.id) AS order_count
FROM
    users u
    LEFT JOIN orders o ON u.id = o.user_id
WHERE
    u.age > 18
    AND u.status = 'active'
GROUP BY
    u.name
HAVING
    COUNT(o.id) > 5
ORDER BY
    order_count DESC
LIMIT 10
```

---

#### 3.3 REPL 交互式命令行

**复杂度**: ⭐⭐  
**预计工作量**: 小

提供一个交互式命令行，可以即时输入 SQL 查看解析结果：

```
sql-parser> SELECT * FROM users WHERE age > 18
✅ Parsed successfully!

Statement Type: SELECT
Columns: *
From: users
Where: age > 18

sql-parser> SELEC * FROM users
❌ Parse error: Unknown token 'SELEC' at position 0

sql-parser> :tokens SELECT * FROM users
[SELECT, *, FROM, IDENTIFIER("users"), EOF]

sql-parser> :help
Available commands:
  <sql>        Parse and display AST
  :tokens <sql>  Show token stream
  :format <sql>  Format SQL
  :quit          Exit REPL
```

**实现要点**：
- 新增 `REPL.scala` 入口文件
- 支持多行输入（以分号结尾）
- 支持特殊命令（`:tokens`、`:format`、`:help`、`:quit`）
- 彩色输出和友好错误提示

---

### 第四阶段：高级扩展 ⭐ 优先级：低

> 目标：面向生产级解析器演进

#### 4.1 SQL 注释支持

**复杂度**: ⭐  
**预计工作量**: 小

```sql
-- 这是单行注释
SELECT * FROM users /* 这是块注释 */ WHERE age > 18
```

**实现要点**：
- Lexer：在扫描时识别 `--` 和 `/* ... */` 并跳过
- 可选保留注释信息用于 Pretty Printer

---

#### 4.2 约束条件（Constraints）

**复杂度**: ⭐⭐  
**预计工作量**: 中

```sql
CREATE TABLE users (
  id INT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(255) NOT NULL UNIQUE,
  department_id INT,
  FOREIGN KEY (department_id) REFERENCES departments(id),
  CHECK (age >= 0)
)
```

**实现要点**：
- AST：扩展 `ColumnDefinition` 添加约束列表
- AST：新增 `Constraint` 密封特质（PrimaryKey, ForeignKey, Unique, NotNull, Check, Default）
- Token：新增 `PRIMARY`、`KEY`、`FOREIGN`、`REFERENCES`、`UNIQUE`、`CHECK`、`DEFAULT`、`AUTO_INCREMENT` 关键字

---

#### 4.3 索引支持

**复杂度**: ⭐⭐  
**预计工作量**: 小

```sql
CREATE INDEX idx_name ON users(name)
CREATE UNIQUE INDEX idx_email ON users(email)
DROP INDEX idx_name ON users
```

**实现要点**：
- AST：新增 `CreateIndexStatement` 和 `DropIndexStatement`
- Token：新增 `INDEX` 关键字

---

#### 4.4 语义分析器（Semantic Analyzer）

**复杂度**: ⭐⭐⭐⭐  
**预计工作量**: 大

在语法分析之后增加一层语义检查：

```scala
object SemanticAnalyzer {
  def analyze(ast: SQLStatement, schema: DatabaseSchema): List[SemanticError] = {
    // 1. 检查表是否存在
    // 2. 检查列是否属于对应的表
    // 3. 检查数据类型是否匹配
    // 4. 检查聚合函数与 GROUP BY 的一致性
    // 5. 检查别名是否重复
  }
}
```

---

#### 4.5 查询执行引擎（Query Executor）

**复杂度**: ⭐⭐⭐⭐⭐  
**预计工作量**: 非常大

实现一个内存数据库引擎，执行解析后的 SQL：

```scala
object QueryExecutor {
  def execute(ast: SQLStatement, database: InMemoryDatabase): ResultSet = {
    ast match {
      case s: SelectStatement => executeSelect(s, database)
      case i: InsertStatement => executeInsert(i, database)
      // ...
    }
  }
}
```

这是终极目标，可以让整个项目成为一个完整的微型数据库系统。

---

## 🔧 已知问题（待修复）

| 问题 | 描述 | 优先级 |
|------|------|--------|
| 标识符大小写 | Lexer 对标识符的大小写处理不一致，导致部分测试失败 | 高 |
| JOIN 别名解析 | JOIN 子句中表别名解析存在问题 | 高 |

> 建议在推进新功能之前先修复上述 7 个失败测试。

---

## 📅 建议实施时间线

```
第1周    ─── 修复已知问题（标识符大小写 + JOIN 别名）
             ├── 修复 7 个失败测试
             └── 确保所有现有测试全部通过

第2-3周  ─── 第一阶段
             ├── 子查询支持
             ├── CASE 表达式
             └── UNION / INTERSECT / EXCEPT

第4-5周  ─── 第二阶段
             ├── 窗口函数
             ├── CTE (WITH 子句)
             └── ALTER TABLE

第6-7周  ─── 第三阶段
             ├── 增强错误报告
             ├── SQL Pretty Printer
             └── REPL 交互式命令行

第8周+   ─── 第四阶段（按需推进）
             ├── SQL 注释
             ├── 约束条件
             ├── 索引支持
             ├── 语义分析器
             └── 查询执行引擎
```

---

## 📝 每个功能的修改文件清单

每项新功能通常需要修改以下文件（与已有功能扩展模式一致）：

| 文件 | 修改内容 |
|------|----------|
| `Token.scala` | 添加新关键字 Token |
| `Lexer.scala` | 在 keywords 映射中注册新关键字 |
| `AST.scala` | 定义新的 AST 节点（case class / sealed trait） |
| `Parser.scala` | 实现新的解析方法 |
| `MySQLParser.scala` | 添加打印分支和示例 SQL |
| `MySQLParserTest.scala` | 编写对应的单元测试 |

---

## 🎯 最终愿景

```
SQL 字符串
    │
    ▼
┌─────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│  Lexer  │ ──▶ │  Parser  │ ──▶ │ Semantic │ ──▶ │ Executor │
│ 词法分析 │     │ 语法分析  │     │ 语义分析  │     │ 查询执行  │
└─────────┘     └──────────┘     └──────────┘     └──────────┘
                     │                                  │
                     ▼                                  ▼
                ┌──────────┐                      ┌──────────┐
                │  Pretty  │                      │  Result  │
                │ Printer  │                      │   Set    │
                └──────────┘                      └──────────┘
```

从一个教学级 SQL 解析器，逐步演进为一个功能完整的微型 SQL 数据库系统。

---

*本路线图基于项目当前状态制定，可根据实际需求和优先级灵活调整。*
