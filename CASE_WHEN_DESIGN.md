# CASE WHEN 表达式设计文档

## 一、SQL CASE 表达式概述

CASE 表达式是 SQL 中唯一的条件逻辑（类似编程语言中的 `if-else` / `switch-case`），有**两种语法形式**：

### 形式一：搜索式 CASE（Searched CASE）

```sql
CASE
  WHEN condition1 THEN result1
  WHEN condition2 THEN result2
  ...
  ELSE default_result
END
```

每个 `WHEN` 后面跟的是一个**布尔条件表达式**（如 `age > 18`、`status = 'active'`）。

**示例：**

```sql
SELECT name,
  CASE
    WHEN salary > 10000 THEN 'high'
    WHEN salary > 5000 THEN 'medium'
    ELSE 'low'
  END AS salary_level
FROM employees
```

### 形式二：简单式 CASE（Simple CASE）

```sql
CASE expression
  WHEN value1 THEN result1
  WHEN value2 THEN result2
  ...
  ELSE default_result
END
```

`CASE` 后面跟一个**操作数表达式**，每个 `WHEN` 后面跟的是一个**值**，比较 `expression = value`。

**示例：**

```sql
SELECT name,
  CASE status
    WHEN 'active' THEN '活跃'
    WHEN 'inactive' THEN '停用'
    WHEN 'banned' THEN '封禁'
    ELSE '未知'
  END AS status_text
FROM users
```

---

## 二、CASE 可以出现的位置

CASE 表达式可以出现在**任何表达式位置**，因为它本身就是 Expression：

| 位置 | 示例 |
|------|------|
| **SELECT 列** | `SELECT CASE WHEN ... END AS col FROM t` |
| **WHERE 条件** | `WHERE CASE WHEN x > 0 THEN 1 ELSE 0 END = 1` |
| **ORDER BY** | `ORDER BY CASE WHEN status = 'active' THEN 0 ELSE 1 END` |
| **GROUP BY** | `GROUP BY CASE WHEN age > 18 THEN 'adult' ELSE 'minor' END` |
| **聚合函数参数** | `SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END)` |
| **INSERT VALUES** | `INSERT INTO t VALUES (CASE WHEN ... END)` |
| **UPDATE SET** | `UPDATE t SET col = CASE WHEN ... END` |

---

## 三、语法文法（BNF）

```
case_expression
  ::= CASE operand? when_clause+ else_clause? END

when_clause
  ::= WHEN expression THEN expression

else_clause
  ::= ELSE expression

operand
  ::= expression    -- Simple CASE 的操作数
```

区分两种形式：
- 如果 `CASE` 后面紧跟 `WHEN` → **搜索式 CASE**
- 如果 `CASE` 后面不是 `WHEN` → **简单式 CASE**，先解析操作数

---

## 四、AST 节点设计

```scala
// WHEN ... THEN ... 分支
case class WhenClause(condition: Expression, result: Expression)

// CASE 表达式（统一表示两种形式）
case class CaseExpression(
  operand: Option[Expression],       // 简单式 CASE 的操作数，搜索式为 None
  whenClauses: List[WhenClause],     // WHEN-THEN 分支列表（至少一个）
  elseResult: Option[Expression]     // ELSE 分支（可选）
) extends Expression
```

### 设计决策

**为什么用一个节点统一两种形式？**

两种 CASE 只是语义不同（简单式自动做 `=` 比较），语法结构完全一致。一个 `CaseExpression` 通过 `operand` 是否为 `None` 来区分：
- `operand = None` → 搜索式 CASE
- `operand = Some(expr)` → 简单式 CASE

这样 Parser 和打印逻辑都更简洁。

---

## 五、Token 新增

需要新增 **5 个关键字 Token**：

| Token | 关键字 | 用途 |
|-------|--------|------|
| `CASE` | CASE | 开始 CASE 表达式 |
| `WHEN` | WHEN | 条件分支 |
| `THEN` | THEN | 分支结果 |
| `ELSE` | ELSE | 默认分支 |
| `END` | END | 结束 CASE 表达式 |

---

## 六、Parser 修改

### 核心：在 `parsePrimaryExpression()` 中新增 CASE 分支

```scala
case CASE =>
  parseCaseExpression()
```

### 新增方法：`parseCaseExpression()`

```scala
private def parseCaseExpression(): CaseExpression = {
  consume(CASE)
  
  // 判断是搜索式还是简单式
  val operand = if (currentToken() != WHEN) {
    Some(parseExpression())  // 简单式：CASE expr WHEN ...
  } else {
    None                     // 搜索式：CASE WHEN ...
  }
  
  // 解析 WHEN-THEN 分支（至少一个）
  var whenClauses = List[WhenClause]()
  while (currentToken() == WHEN) {
    consume(WHEN)
    val condition = parseExpression()
    consume(THEN)
    val result = parseExpression()
    whenClauses = whenClauses :+ WhenClause(condition, result)
  }
  
  // 解析可选的 ELSE
  val elseResult = if (currentToken() == ELSE) {
    consume(ELSE)
    Some(parseExpression())
  } else {
    None
  }
  
  consume(END)
  
  CaseExpression(operand, whenClauses, elseResult)
}
```

### `parseColumnList()` 修改

CASE 可以作为 SELECT 列：

```sql
SELECT CASE WHEN age > 18 THEN 'adult' ELSE 'minor' END AS age_group FROM users
```

需要在 `parseColumnList()` 中加一个 `CASE` 分支。

---

## 七、AST 树形结构示例

对于：

```sql
SELECT name,
  CASE
    WHEN salary > 10000 THEN 'high'
    WHEN salary > 5000 THEN 'medium'
    ELSE 'low'
  END AS salary_level
FROM employees
```

生成的 AST：

```
SelectStatement
├── columns:
│   ├── NamedColumn("name")
│   └── ExpressionColumn(alias = "salary_level")
│       └── CaseExpression
│           ├── operand: None (搜索式)
│           ├── whenClauses:
│           │   ├── WhenClause
│           │   │   ├── condition: BinaryExpression(Identifier("salary"), GreaterThan, NumberLiteral("10000"))
│           │   │   └── result: StringLiteral("high")
│           │   └── WhenClause
│           │       ├── condition: BinaryExpression(Identifier("salary"), GreaterThan, NumberLiteral("5000"))
│           │       └── result: StringLiteral("medium")
│           └── elseResult: Some(StringLiteral("low"))
├── from: TableName("employees")
└── where: None
```

对于简单式 CASE：

```sql
SELECT CASE status WHEN 'active' THEN 1 WHEN 'inactive' THEN 0 ELSE -1 END FROM users
```

```
SelectStatement
└── columns:
    └── ExpressionColumn
        └── CaseExpression
            ├── operand: Some(Identifier("status"))   // 简单式
            ├── whenClauses:
            │   ├── WhenClause(StringLiteral("active"), NumberLiteral("1"))
            │   └── WhenClause(StringLiteral("inactive"), NumberLiteral("0"))
            └── elseResult: Some(NumberLiteral("-1"))
```

---

## 八、测试用例规划

| # | 测试名 | SQL | 验证点 |
|---|--------|-----|--------|
| 1 | 搜索式 CASE (SELECT 列) | `SELECT CASE WHEN age > 18 THEN 'adult' ELSE 'minor' END AS age_group FROM users` | CaseExpression + operand=None + 2个分支 |
| 2 | 简单式 CASE (SELECT 列) | `SELECT CASE status WHEN 'active' THEN 1 WHEN 'inactive' THEN 0 ELSE -1 END FROM users` | operand=Some + whenClauses |
| 3 | CASE 无 ELSE | `SELECT CASE WHEN score >= 60 THEN 'pass' END FROM exams` | elseResult=None |
| 4 | CASE 在 WHERE 中 | `SELECT * FROM users WHERE CASE WHEN age > 18 THEN 1 ELSE 0 END = 1` | CASE 在条件位置 |
| 5 | CASE 在 ORDER BY 中 | `SELECT * FROM users ORDER BY CASE WHEN status = 'active' THEN 0 ELSE 1 END` | CASE 在排序位置 |
| 6 | CASE 嵌套在聚合函数中 | `SELECT SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END) FROM users` | CASE 作为聚合参数 |
| 7 | 多分支 CASE | `SELECT CASE WHEN score >= 90 THEN 'A' WHEN score >= 80 THEN 'B' WHEN score >= 70 THEN 'C' WHEN score >= 60 THEN 'D' ELSE 'F' END FROM exams` | 4 个 WHEN + ELSE |

---

## 九、注意事项

| 要点 | 说明 |
|------|------|
| **END 关键字** | `END` 是 CASE 的结束标记，不能遗漏。如果未来加 `BEGIN...END` 块，需要做区分 |
| **WHEN 至少一个** | 语法要求至少有一个 WHEN-THEN 分支 |
| **ELSE 可选** | 如果没有 ELSE 且所有 WHEN 不匹配，结果为 NULL（语义分析范畴） |
| **表达式递归** | CASE 内的 condition 和 result 都是完整表达式，可以嵌套其他 CASE、子查询等 |
| **简单式 CASE 的操作数解析** | `CASE expr WHEN ...` 中的 `expr` 需要在遇到 `WHEN` 前停止。因为 `parseExpression()` 不认识 `WHEN`，它会自然在 `WHEN` 处停下 |
