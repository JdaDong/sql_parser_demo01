# UNION / UNION ALL 设计文档

## 1. 语法定义

### 1.1 基本语法

```sql
-- UNION（去重）
SELECT ... UNION SELECT ...

-- UNION ALL（保留重复）
SELECT ... UNION ALL SELECT ...

-- 多个 UNION 链
SELECT ... UNION SELECT ... UNION ALL SELECT ...
```

### 1.2 带 ORDER BY / LIMIT 的 UNION

```sql
-- ORDER BY / LIMIT 作用于整个 UNION 结果
SELECT name FROM users UNION SELECT name FROM admins ORDER BY name LIMIT 10
```

注意：ORDER BY / LIMIT 只能出现在最后一个 SELECT 之后，作用于整个 UNION 的最终结果。

## 2. BNF 语法

```bnf
<union_statement> ::= <select_statement>
                    | <select_statement> <union_clause>+

<union_clause>    ::= UNION [ALL] <select_statement>

<select_statement> ::= SELECT [DISTINCT] <columns> [FROM ...] [WHERE ...] [GROUP BY ...] [HAVING ...] [ORDER BY ...] [LIMIT ...] [OFFSET ...]
```

## 3. 设计决策

### 3.1 AST 节点设计

新增两个类型：

```scala
// UNION 类型
sealed trait UnionType
case object UnionDistinct extends UnionType   // UNION（去重）
case object UnionAll extends UnionType        // UNION ALL（保留重复）

// UNION 查询（组合多个 SELECT）
case class UnionStatement(
  left: SQLStatement,         // 左侧 SELECT（或嵌套 UNION）
  right: SelectStatement,     // 右侧 SELECT
  unionType: UnionType        // UNION 或 UNION ALL
) extends SQLStatement
```

### 3.2 左结合性

多个 UNION 按**左结合**处理：

```sql
SELECT a UNION SELECT b UNION ALL SELECT c
```

解析为：

```
UnionStatement(
  left = UnionStatement(
    left = SELECT a,
    right = SELECT b,
    unionType = UnionDistinct
  ),
  right = SELECT c,
  unionType = UnionAll
)
```

### 3.3 解析策略

在 `parse()` 入口方法中：
1. 先正常解析第一个 `SELECT` 语句
2. 检查后续是否有 `UNION` 关键字
3. 如果有，循环消费 `UNION [ALL]` + `SELECT` 构建 `UnionStatement` 链

## 4. Token 变更

| 新增 Token | 说明 |
|-----------|------|
| `UNION`   | UNION 关键字 |
| `ALL`     | ALL 关键字 |

## 5. UNION 可出现的位置

| 位置 | 示例 |
|------|------|
| 顶层语句 | `SELECT ... UNION SELECT ...` |
| 子查询中 | `WHERE id IN (SELECT id FROM a UNION SELECT id FROM b)` |
| 派生表中 | `FROM (SELECT ... UNION SELECT ...) AS t` |

## 6. 测试用例规划

| 编号 | 测试场景 | SQL |
|------|---------|-----|
| 1 | 基本 UNION | `SELECT name FROM users UNION SELECT name FROM admins` |
| 2 | UNION ALL | `SELECT id FROM a UNION ALL SELECT id FROM b` |
| 3 | 多个 UNION 链 | `SELECT ... UNION SELECT ... UNION ALL SELECT ...` |
| 4 | UNION + ORDER BY | `SELECT ... UNION SELECT ... ORDER BY name` |
| 5 | UNION + LIMIT | `SELECT ... UNION SELECT ... LIMIT 10` |
| 6 | UNION 在子查询中 | `WHERE id IN (SELECT ... UNION SELECT ...)` |
| 7 | UNION 在派生表中 | `FROM (SELECT ... UNION SELECT ...) AS t` |
| 8 | UNION + WHERE 子句 | `SELECT ... WHERE ... UNION SELECT ... WHERE ...` |

## 7. AST 树形结构示例

```
SQL: SELECT name FROM users UNION ALL SELECT name FROM admins ORDER BY name

UnionStatement
├── left: SelectStatement
│   ├── columns: [NamedColumn("name")]
│   └── from: TableName("users")
├── right: SelectStatement
│   ├── columns: [NamedColumn("name")]
│   ├── from: TableName("admins")
│   └── orderBy: [OrderByClause(Identifier("name"), ASC)]
└── unionType: UnionAll
```
