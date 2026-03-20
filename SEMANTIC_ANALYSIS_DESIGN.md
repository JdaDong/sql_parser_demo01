# 语义分析阶段设计文档

> **版本**: v1.0  
> **日期**: 2026-03-20  
> **项目**: sql_parser_demo01

---

## 1. 概述

语义分析（Semantic Analysis）是编译器前端管道中介于**语法分析**和**代码生成/执行**之间的关键阶段。语法分析器只验证 SQL 的**结构**是否合法（能否构建 AST），而语义分析在 AST 上验证**含义**是否正确。

```
SQL String ──▶ Lexer ──▶ Parser ──▶ [AST] ──▶ SemanticAnalyzer ──▶ 合法 AST / 错误列表
```

### 1.1 语义分析 vs 语法分析

| 维度 | 语法分析 | 语义分析 |
|------|----------|----------|
| 检查内容 | SQL 结构是否符合文法规则 | SQL 含义是否正确 |
| 典型错误 | 缺少关键字、括号不匹配 | 引用不存在的表、列类型不匹配 |
| 是否需要上下文 | 不需要（仅 Token 流） | 需要（Schema 信息） |
| 错误处理 | 遇到第一个错误立即中止 | 收集所有错误，一次性报告 |

---

## 2. 检查项设计

### 2.1 表存在性检查（Table Existence）

验证 FROM、JOIN、INSERT INTO、UPDATE、DELETE FROM 中引用的表是否在 Schema 中存在。

```sql
-- ❌ 表 orders 不存在
SELECT * FROM orders
-- Error: Table 'orders' does not exist
```

### 2.2 列存在性检查（Column Existence）

验证 SELECT 列、WHERE 条件、ORDER BY、GROUP BY、HAVING 中引用的列是否属于相关的表。

```sql
-- ❌ 列 email 不属于 users 表
SELECT email FROM users
-- Error: Column 'email' does not exist in table 'users'
```

### 2.3 限定列检查（Qualified Column）

当使用 `table.column` 格式时，验证表别名/表名是否有效，以及列是否属于该表。

```sql
-- ❌ 别名 x 未定义
SELECT x.name FROM users u
-- Error: Table alias 'x' is not defined
```

### 2.4 聚合函数与 GROUP BY 一致性检查

当查询包含 GROUP BY 子句时，SELECT 列表中的非聚合列必须全部出现在 GROUP BY 中。

```sql
-- ❌ name 不在 GROUP BY 中
SELECT department, name, COUNT(*) FROM employees GROUP BY department
-- Error: Column 'name' must appear in GROUP BY clause or be used in an aggregate function
```

### 2.5 别名唯一性检查（Alias Uniqueness）

SELECT 列表中的列别名不能重复。

```sql
-- ❌ 别名 x 重复
SELECT name AS x, age AS x FROM users
-- Error: Duplicate column alias 'x'
```

### 2.6 HAVING 子句合法性检查

HAVING 子句中的非聚合列引用必须出现在 GROUP BY 中。

```sql
-- ❌ name 不在 GROUP BY 中
SELECT department, COUNT(*) FROM employees GROUP BY department HAVING name = 'Alice'
-- Error: Column 'name' in HAVING clause must appear in GROUP BY clause or be used in an aggregate function
```

### 2.7 INSERT 列数匹配检查

INSERT 语句中指定列数量与 VALUES 每行值的数量必须一致。

```sql
-- ❌ 3 列但只提供了 2 个值
INSERT INTO users (name, age, email) VALUES ('Alice', 25)
-- Error: INSERT has 3 columns but VALUES has 2 values
```

### 2.8 UNION 列数一致性检查

UNION 两侧的 SELECT 列数必须相同。

```sql
-- ❌ 左边 2 列，右边 1 列
SELECT id, name FROM users UNION SELECT id FROM admins
-- Error: UNION queries must have the same number of columns (left: 2, right: 1)
```

### 2.9 ORDER BY 引用检查

ORDER BY 中引用的列必须在 SELECT 列表或 FROM 的表中可找到。

### 2.10 星号列与 GROUP BY 不兼容检查

使用 `SELECT *` 时不应与 GROUP BY 一起使用（MySQL 实际允许但不推荐）。

---

## 3. Schema 模型设计

```scala
// 列定义（元数据）
case class ColumnSchema(
  name: String,           // 列名
  dataType: String,       // 类型（如 "INT", "VARCHAR"）
  nullable: Boolean       // 是否允许 NULL
)

// 表定义（元数据）
case class TableSchema(
  name: String,                    // 表名
  columns: List[ColumnSchema]      // 列列表
)

// 数据库 Schema（所有表的集合）
case class DatabaseSchema(
  tables: Map[String, TableSchema] // 表名(大写) → 表定义
)
```

---

## 4. 错误模型设计

```scala
// 语义错误严重级别
sealed trait Severity
case object Error extends Severity     // 致命错误（必须修复）
case object Warning extends Severity   // 警告（可能有问题）

// 语义错误
case class SemanticError(
  message: String,         // 错误描述
  severity: Severity,      // 严重级别
  category: String         // 分类（如 "TABLE", "COLUMN", "GROUP_BY"）
)
```

---

## 5. 分析器接口设计

```scala
object SemanticAnalyzer {
  /**
   * 分析 SQL AST 的语义正确性
   * @param ast    解析后的 AST
   * @param schema 数据库 Schema（表结构信息）
   * @return       语义错误列表（空列表表示无错误）
   */
  def analyze(ast: SQLStatement, schema: DatabaseSchema): List[SemanticError]
}
```

---

## 6. 核心算法

### 6.1 作用域构建

对于 SELECT 语句，首先构建**可用表和列的作用域**：

1. 从 `FROM` 子句获取主表和 JOIN 表
2. 将表别名映射到真实表名
3. 收集所有可用的列

```
Scope = {
  tables: Map[alias/name → TableSchema],
  columns: Map[columnName → Set[tableName]]  // 同名列可能属于多张表
}
```

### 6.2 递归遍历 AST

对 AST 的每个节点执行对应的语义检查：

```
analyzeStatement(stmt) →
  case SelectStatement   → analyzeSelect()
  case InsertStatement   → analyzeInsert()
  case UpdateStatement   → analyzeUpdate()
  case DeleteStatement   → analyzeDelete()
  case UnionStatement    → analyzeUnion()
  case CreateTable...    → skip（DDL 不需要列存在性检查）
  case DropTable...      → analyzeDropTable()
```

---

## 7. 测试用例设计

### 7.1 正确用例（应无错误）

| SQL | 说明 |
|-----|------|
| `SELECT id, name FROM users` | 普通查询 |
| `SELECT u.name FROM users u` | 表别名 |
| `SELECT COUNT(*) FROM users GROUP BY department` | 聚合 + GROUP BY |
| `SELECT * FROM users WHERE age > 18` | WHERE 条件 |

### 7.2 错误用例（应检测到语义错误）

| SQL | 预期错误 |
|-----|----------|
| `SELECT * FROM nonexistent` | 表不存在 |
| `SELECT fake_col FROM users` | 列不存在 |
| `SELECT x.name FROM users u` | 表别名未定义 |
| `SELECT name, COUNT(*) FROM users GROUP BY department` | 非聚合列不在 GROUP BY 中 |
| `SELECT name AS x, age AS x FROM users` | 别名重复 |
| `INSERT INTO users (name, age) VALUES ('a')` | 列数不匹配 |
| `SELECT id FROM users UNION SELECT id, name FROM admins` | UNION 列数不一致 |

---

## 8. 文件修改清单

| 文件 | 修改内容 |
|------|----------|
| `Schema.scala`（新建） | Schema 模型定义 |
| `SemanticAnalyzer.scala`（新建） | 语义分析器实现 |
| `MySQLParser.scala` | 集成语义分析 + 示例 |
| `MySQLParserTest.scala` | 语义分析测试用例 |

注意：**无需修改** Token.scala、Lexer.scala、AST.scala、Parser.scala — 语义分析完全工作在 AST 层面之上。
