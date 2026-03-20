# SQL Parser Demo01 — 功能扩展建议

> 本文档记录了对项目"还可以添加什么"的完整分析与建议。

---

## 项目现状分析

当前项目已实现了一个完整的 MySQL SQL 解析器前端，采用经典的 **Lexer → Parser → AST** 编译器前端架构，支持：

- **词法分析**：关键字、标识符、字符串/数字字面量、运算符、分隔符
- **语法分析**：递归下降解析，运算符优先级处理
- **6 种 SQL 语句**：SELECT（含 DISTINCT / JOIN / WHERE / GROUP BY / HAVING / ORDER BY / LIMIT / OFFSET）、INSERT、UPDATE、DELETE、CREATE TABLE、DROP TABLE
- **表达式系统**：算术（+、-、*、/）、比较（=、!=、<、>、<=、>=）、逻辑（AND、OR、NOT）
- **聚合函数**：COUNT、SUM、AVG、MAX、MIN（支持 DISTINCT）
- **谓词表达式**：IS NULL / IS NOT NULL、BETWEEN / NOT BETWEEN、IN / NOT IN、LIKE / NOT LIKE

---

## 扩展建议（按优先级分组）

### 🔴 优先级组 1：SQL 表达式增强

这些是日常 SQL 中最高频使用的特性，补充后能大幅提升解析器的实用价值。

#### 1. 子查询（Subquery）

在 WHERE、FROM、SELECT 中支持嵌套查询：

```sql
-- WHERE 中的子查询
SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)

-- FROM 中的子查询（派生表）
SELECT t.name FROM (SELECT name FROM users WHERE age > 18) AS t

-- SELECT 中的标量子查询
SELECT name, (SELECT COUNT(*) FROM orders WHERE orders.user_id = users.id) AS order_count FROM users

-- EXISTS 子查询
SELECT * FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)
```

**实现思路**：
- AST 新增 `SubQuery(query: SelectStatement)` 表达式节点
- AST 新增 `ExistsExpression(subquery: SubQuery)` 节点
- 在 `parsePrimaryExpression()` 中识别 `(` + `SELECT` 组合
- 在 `parseTableReference()` 中支持派生表

#### 2. CASE 表达式

```sql
-- 简单 CASE
SELECT CASE status WHEN 1 THEN 'active' WHEN 0 THEN 'inactive' ELSE 'unknown' END FROM users

-- 搜索 CASE
SELECT CASE WHEN age < 18 THEN 'minor' WHEN age >= 18 THEN 'adult' END FROM users
```

**实现思路**：
- 新增 `CASE`、`WHEN`、`THEN`、`ELSE`、`END` 关键字 Token
- AST 新增 `CaseExpression` 和 `WhenClause` 节点
- Parser 新增 `parseCaseExpression()` 方法

#### 3. UNION / INTERSECT / EXCEPT

```sql
SELECT name FROM students UNION SELECT name FROM teachers
SELECT name FROM students UNION ALL SELECT name FROM teachers
```

**实现思路**：
- AST 新增 `CompoundQuery(left, operator, right, all)` 节点
- 新增 `UNION`、`INTERSECT`、`EXCEPT`、`ALL` 关键字
- 在顶层 `parse()` 中检测复合查询

---

### 🟠 优先级组 2：高级 SQL 特性

这些在生产 SQL 中非常常见，实现后让解析器更贴近真实数据库。

#### 4. 窗口函数（Window Functions）

```sql
SELECT name, salary,
  ROW_NUMBER() OVER (ORDER BY salary DESC) AS rank,
  SUM(salary) OVER (PARTITION BY department) AS dept_total
FROM employees
```

**实现思路**：
- 新增 `OVER`、`PARTITION`、`ROW_NUMBER`、`RANK`、`DENSE_RANK`、`ROWS`、`RANGE` 关键字
- AST 新增 `WindowFunction` 和 `WindowSpec` 节点
- Parser 新增 `parseWindowFunction()` 和 `parseWindowSpec()` 方法

#### 5. CTE — WITH 子句

```sql
WITH active_users AS (
  SELECT * FROM users WHERE status = 'active'
)
SELECT * FROM active_users WHERE age > 18
```

**实现思路**：
- 新增 `WITH` 关键字
- AST 新增 `WithClause` 和 `CommonTableExpression` 节点
- 在 `parse()` 入口检测 WITH 关键字

#### 6. ALTER TABLE

```sql
ALTER TABLE users ADD COLUMN email VARCHAR(255)
ALTER TABLE users DROP COLUMN age
ALTER TABLE users MODIFY COLUMN name VARCHAR(200)
```

**实现思路**：
- 新增 `ALTER`、`ADD`、`COLUMN`、`MODIFY`、`RENAME` 关键字
- AST 新增 `AlterTableStatement` 和 `AlterAction` 密封特质

---

### 🟡 优先级组 3：工程质量提升

提升解析器的健壮性、易用性和开发者体验。

#### 7. 增强错误报告（行号 / 列号）

当前错误信息缺少位置信息。改进后：

```
Parse error at line 3, column 15: Expected ')' but got ','
  SELECT COUNT(id, FROM users
                   ^
```

**实现思路**：
- Lexer 跟踪行号和列号
- Token 携带 `Position(line, col)` 信息
- Parser 错误消息中包含位置和上下文代码片段

#### 8. SQL Pretty Printer（格式化输出）

将 AST 反向转换为格式规范的 SQL 字符串：

```scala
object SQLPrettyPrinter {
  def print(ast: SQLStatement): String = {
    // AST → 格式化 SQL 字符串
  }
}
```

可以用于：
- 验证解析结果（round-trip 测试）
- SQL 代码格式化工具
- SQL 规范化

#### 9. REPL 交互式命令行

```
sql-parser> SELECT * FROM users WHERE age > 18
✅ Parsed successfully!
Statement Type: SELECT
...

sql-parser> :tokens SELECT * FROM users
[SELECT, *, FROM, IDENTIFIER("users"), EOF]

sql-parser> :format SELECT * FROM users WHERE age>18
SELECT *
FROM users
WHERE age > 18

sql-parser> :quit
```

---

### 🟢 优先级组 4：扩展 SQL 语法

#### 10. SQL 注释支持

```sql
-- 单行注释
SELECT * FROM users /* 块注释 */ WHERE age > 18
```

#### 11. 约束条件

```sql
CREATE TABLE users (
  id INT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(255) NOT NULL UNIQUE,
  FOREIGN KEY (dept_id) REFERENCES departments(id)
)
```

#### 12. 索引支持

```sql
CREATE INDEX idx_name ON users(name)
CREATE UNIQUE INDEX idx_email ON users(email)
DROP INDEX idx_name ON users
```

---

### 🔵 优先级组 5：高级架构演进

#### 13. 语义分析器（Semantic Analyzer）

```scala
object SemanticAnalyzer {
  def analyze(ast: SQLStatement, schema: DatabaseSchema): List[SemanticError] = {
    // 检查表是否存在、列类型是否匹配、聚合函数与 GROUP BY 一致性等
  }
}
```

#### 14. 查询执行引擎（Query Executor）

```scala
object QueryExecutor {
  def execute(ast: SQLStatement, database: InMemoryDatabase): ResultSet = {
    // 在内存数据库上执行 SQL
  }
}
```

实现一个微型内存数据库，让项目成为完整的 SQL 数据库系统。

---

## 推荐实施顺序

```
子查询 → CASE 表达式 → UNION → 窗口函数 → CTE
                                            ↓
查询执行引擎 ← 语义分析器 ← REPL ← Pretty Printer ← 错误报告增强
```

**建议先修复当前已知的 7 个失败测试**（标识符大小写问题和 JOIN 别名解析问题），再推进新功能开发。

---

*本建议基于项目当前代码结构和已实现功能进行分析。*
