# 子查询（Subquery）设计与实现文档

## 一、概述

子查询是嵌套在另一个 SQL 语句内部的 `SELECT` 语句。本文档详细说明了在当前 SQL Parser 项目中实现子查询支持的设计方案。

---

## 二、子查询出现的位置

子查询可以出现在 SQL 语句的 **四个位置**：

### 2.1 WHERE 子句中的标量子查询

子查询返回单个值，参与比较运算：

```sql
SELECT * FROM users WHERE salary > (SELECT AVG(salary) FROM employees)
SELECT * FROM products WHERE price = (SELECT MAX(price) FROM products)
```

### 2.2 IN 子句中的子查询

子查询返回一列多行，用于集合判断：

```sql
SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)
SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM blacklist)
```

### 2.3 EXISTS 子查询

判断子查询是否返回结果（关联子查询最常见的形式）：

```sql
SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)
SELECT * FROM users WHERE NOT EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)
```

### 2.4 FROM 子句中的子查询（派生表）

子查询作为临时表使用，必须有别名：

```sql
SELECT t.name, t.total 
FROM (SELECT name, SUM(price) AS total FROM products GROUP BY name) AS t
WHERE t.total > 1000
```

### 2.5 SELECT 列中的标量子查询

子查询作为列值（必须返回单行单列）：

```sql
SELECT name, 
       (SELECT COUNT(*) FROM orders WHERE orders.user_id = users.id) AS order_count 
FROM users
```

---

## 三、AST 设计

### 3.1 新增 AST 节点

```scala
// 子查询表达式 — 将完整 SELECT 包装成 Expression，用于 WHERE/SELECT 列
case class SubqueryExpression(query: SelectStatement) extends Expression

// EXISTS 谓词
case class ExistsExpression(query: SelectStatement, negated: Boolean = false) extends Expression

// IN 子查询（区别于 IN 值列表）
case class InSubqueryExpression(
  expression: Expression, 
  query: SelectStatement, 
  negated: Boolean = false
) extends Expression

// 派生表（FROM 子句中的子查询）
case class DerivedTable(query: SelectStatement, alias: String) extends TableReference
```

### 3.2 设计决策

| 决策 | 理由 |
|------|------|
| `SubqueryExpression` 继承 `Expression` | 让子查询能出现在任何表达式位置（WHERE 比较、SELECT 列） |
| `DerivedTable` 继承 `TableReference` | 让子查询能出现在 FROM 子句中，与 `TableName`、`JoinClause` 并列 |
| `InSubqueryExpression` 独立于 `InExpression` | IN 值列表和 IN 子查询的结构完全不同（`List[Expression]` vs `SelectStatement`） |
| `ExistsExpression` 单独处理 | EXISTS 的语义和语法都很特殊（没有左操作数，只有子查询） |

### 3.3 AST 结构示例

`SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE amount > 100)` 的 AST：

```
SelectStatement
├── columns: [AllColumns]
├── from: TableName("users")
└── where:
    InSubqueryExpression
    ├── expression: Identifier("id")
    ├── negated: false
    └── query:
        SelectStatement
        ├── columns: [NamedColumn("user_id")]
        ├── from: TableName("orders")
        └── where:
            BinaryExpression
            ├── left: Identifier("amount")
            ├── op: GreaterThan
            └── right: NumberLiteral("100")
```

---

## 四、Token 变更

### 4.1 新增 Token

```scala
case object EXISTS extends Token    // EXISTS 关键字
```

### 4.2 Lexer 注册

```scala
"EXISTS" -> EXISTS
```

> 注：`SELECT`、`LPAREN`、`RPAREN`、`NOT` 等已有 Token 无需改动。

---

## 五、Parser 修改详解

### 5.1 核心判断逻辑：如何区分子查询和括号表达式

遇到 `(` 时，只需要**看后面是不是 `SELECT`** — 一步前瞻（look-ahead 1）：

| 遇到的模式 | 判断 | 例子 |
|-----------|------|------|
| `( SELECT ...` | 子查询 | `WHERE id IN (SELECT ...)` |
| `( expression ...` | 括号表达式 | `WHERE (a + b) > 10` |
| `EXISTS ( SELECT ...` | EXISTS 子查询 | `WHERE EXISTS (SELECT 1 ...)` |

SQL 语法保证：**括号后紧跟 `SELECT` 关键字的一定是子查询**。

### 5.2 `parsePrimaryExpression()` — 处理标量子查询和 EXISTS

**修改前**（只处理括号表达式）：

```scala
case LPAREN =>
  consume(LPAREN)
  val expr = parseExpression()
  consume(RPAREN)
  expr
```

**修改后**（增加子查询判断）：

```scala
// EXISTS 子查询
case EXISTS =>
  consume(EXISTS)
  consume(LPAREN)
  val subquery = parseSelect()
  consume(RPAREN)
  ExistsExpression(subquery)

case LPAREN =>
  consume(LPAREN)
  if (currentToken() == SELECT) {
    // 子查询：(SELECT ...)
    val subquery = parseSelect()
    consume(RPAREN)
    SubqueryExpression(subquery)
  } else {
    // 普通括号表达式：(a + b)
    val expr = parseExpression()
    consume(RPAREN)
    expr
  }
```

### 5.3 `parseComparisonExpression()` — 处理 IN 子查询

**修改前**（IN 只接受值列表）：

```scala
case IN =>
  consume(IN)
  consume(LPAREN)
  val values = parseExpressionList()
  consume(RPAREN)
  InExpression(left, values, negated = false)
```

**修改后**（IN 也接受子查询）：

```scala
case IN =>
  consume(IN)
  consume(LPAREN)
  if (currentToken() == SELECT) {
    val subquery = parseSelect()
    consume(RPAREN)
    InSubqueryExpression(left, subquery, negated = false)
  } else {
    val values = parseExpressionList()
    consume(RPAREN)
    InExpression(left, values, negated = false)
  }
```

同理 `NOT IN` 分支也需要相应修改。

### 5.4 `parseTableReference()` — 处理 FROM 子查询（派生表）

**修改前**（只接受表名）：

```scala
val left = currentToken() match {
  case IdentifierToken(name) => ...
  case _ => throw new RuntimeException("Expected table name")
}
```

**修改后**（增加派生表支持）：

```scala
val left = currentToken() match {
  case LPAREN =>
    consume(LPAREN)
    val subquery = parseSelect()
    consume(RPAREN)
    // 派生表必须有别名
    val alias = parseRequiredAlias()
    DerivedTable(subquery, alias)
  case IdentifierToken(name) => ...
  case _ => throw new RuntimeException("Expected table name or subquery")
}
```

### 5.5 `parseUnaryExpression()` — 处理 NOT EXISTS

**修改前**：

```scala
if (currentToken() == NOT) {
  consume(NOT)
  UnaryExpression(NotOp, parseUnaryExpression())
}
```

**修改后**（NOT 后面如果是 EXISTS，走 ExistsExpression 路径）：

```scala
if (currentToken() == NOT) {
  consume(NOT)
  if (currentToken() == EXISTS) {
    consume(EXISTS)
    consume(LPAREN)
    val subquery = parseSelect()
    consume(RPAREN)
    ExistsExpression(subquery, negated = true)
  } else {
    UnaryExpression(NotOp, parseUnaryExpression())
  }
}
```

---

## 六、递归与嵌套

子查询的实现天然支持**任意深度嵌套**，因为：

1. `parsePrimaryExpression()` 遇到子查询时调用 `parseSelect()`
2. `parseSelect()` 内部调用 `parseExpression()` 解析 WHERE
3. `parseExpression()` 最终又可能调用 `parsePrimaryExpression()`
4. 形成递归，自然支持多层嵌套

```sql
-- 三层嵌套子查询，天然支持
SELECT * FROM users 
WHERE dept_id IN (
  SELECT dept_id FROM departments 
  WHERE company_id IN (
    SELECT id FROM companies WHERE country = 'CN'
  )
)
```

---

## 七、语法分析 vs 语义分析

以下检查属于**语义分析**范畴，语法解析器不负责：

| 检查项 | 说明 |
|--------|------|
| 标量子查询返回行数 | `WHERE salary > (SELECT ...)` 语义上要求返回 0 或 1 行 |
| 关联子查询的列引用 | `WHERE orders.user_id = users.id` 涉及外层表的列引用有效性 |
| SELECT 列子查询结果数 | 必须返回恰好 1 行 1 列 |
| IN 子查询列数 | 必须返回恰好 1 列 |

当前项目只做语法分析，这些检查可在后续语义分析阶段实现。

---

## 八、实现计划

| 步骤 | 文件 | 改动 | 复杂度 |
|------|------|------|--------|
| 1 | `Token.scala` | 新增 `EXISTS` Token | ⭐ |
| 2 | `Lexer.scala` | 注册 `"EXISTS"` 关键字 | ⭐ |
| 3 | `AST.scala` | 新增 4 个 AST 节点 | ⭐⭐ |
| 4 | `Parser.scala` | 修改 4 个解析方法 | ⭐⭐⭐ |
| 5 | `MySQLParser.scala` | 新增打印逻辑 + 示例 SQL | ⭐⭐ |
| 6 | `MySQLParserTest.scala` | 新增测试用例 | ⭐⭐ |

预计复杂度：**中等**，核心工作量在 Parser 的 4 处修改。

---

## 九、测试用例规划

```sql
-- WHERE 标量子查询
SELECT * FROM users WHERE salary > (SELECT AVG(salary) FROM employees)

-- IN 子查询
SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)

-- NOT IN 子查询
SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM blacklist)

-- EXISTS 子查询
SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)

-- NOT EXISTS 子查询
SELECT * FROM users WHERE NOT EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)

-- FROM 派生表
SELECT t.name FROM (SELECT name FROM users WHERE age > 18) AS t

-- SELECT 列子查询
SELECT name, (SELECT COUNT(*) FROM orders WHERE orders.user_id = users.id) AS order_count FROM users

-- 嵌套子查询
SELECT * FROM users WHERE dept_id IN (SELECT dept_id FROM departments WHERE company_id IN (SELECT id FROM companies WHERE country = 'CN'))
```
