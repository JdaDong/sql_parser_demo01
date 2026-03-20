# 函数调用、表达式别名、CAST / CONVERT 设计文档

## 一、概述

本次新增三项功能：

1. **通用函数调用** — 支持 MySQL 内置函数如 `UPPER()`, `LOWER()`, `CONCAT()`, `IFNULL()`, `COALESCE()`, `LENGTH()`, `TRIM()`, `NOW()`, `DATE_FORMAT()` 等
2. **表达式别名（隐式别名）** — 支持省略 `AS` 关键字的别名语法，如 `SELECT name n FROM users`
3. **CAST / CONVERT** — 类型转换表达式，如 `CAST(price AS DECIMAL(10,2))`、`CONVERT(val, UNSIGNED)`

---

## 二、语法定义

### 2.1 通用函数调用

```sql
-- 无参函数
SELECT NOW()

-- 单参函数
SELECT UPPER(name) FROM users
SELECT LENGTH(name) FROM users

-- 多参函数
SELECT CONCAT(first_name, ' ', last_name) FROM users
SELECT IFNULL(nickname, name) FROM users
SELECT COALESCE(a, b, c) FROM t

-- 函数在 WHERE 中
SELECT * FROM users WHERE UPPER(name) = 'ALICE'

-- 函数嵌套
SELECT UPPER(CONCAT(first_name, ' ', last_name)) FROM users
```

### 2.2 表达式别名（隐式别名）

```sql
-- 显式别名（已支持）
SELECT name AS n FROM users

-- 隐式别名（省略 AS）
SELECT name n FROM users
SELECT COUNT(*) total FROM users
SELECT UPPER(name) uname FROM users
SELECT t.name tname FROM t
```

**隐式别名判定规则**：当前 Token 是 `IdentifierToken` 且**不是**保留关键字（如 `FROM`, `WHERE`, `ORDER` 等）时，视为隐式别名。

### 2.3 CAST 表达式

```sql
-- CAST(expression AS data_type)
SELECT CAST(price AS DECIMAL(10,2)) FROM products
SELECT CAST(created_at AS DATE) FROM orders
SELECT CAST(age AS CHAR) FROM users
SELECT CAST(val AS SIGNED) FROM t
SELECT CAST(val AS UNSIGNED) FROM t
```

### 2.4 CONVERT 表达式

```sql
-- MySQL 函数式语法：CONVERT(expression, data_type)
SELECT CONVERT(price, DECIMAL(10,2)) FROM products
SELECT CONVERT(val, UNSIGNED) FROM t

-- MySQL USING 语法：CONVERT(expression USING charset)
SELECT CONVERT(name USING utf8) FROM users
```

---

## 三、BNF 文法

```bnf
(* 通用函数调用 *)
function_call ::= function_name "(" [ argument_list ] ")"
function_name ::= IDENTIFIER
argument_list ::= expression { "," expression }

(* CAST 表达式 *)
cast_expression ::= "CAST" "(" expression "AS" cast_type ")"

(* CONVERT 表达式 *)
convert_expression ::= "CONVERT" "(" expression "," cast_type ")"
                      | "CONVERT" "(" expression "USING" IDENTIFIER ")"

(* CAST/CONVERT 目标类型 *)
cast_type ::= "SIGNED" [ "INT" | "INTEGER" ]
            | "UNSIGNED" [ "INT" | "INTEGER" ]
            | "CHAR" [ "(" number ")" ]
            | "VARCHAR" "(" number ")"
            | "DECIMAL" [ "(" number [ "," number ] ")" ]
            | "DATE"
            | "DATETIME"
            | "INT"
            | "BOOLEAN"

(* 隐式别名 *)
column_with_alias ::= column_expression [ "AS" IDENTIFIER | IDENTIFIER ]
```

---

## 四、AST 节点设计

### 4.1 新增 AST 节点

```scala
// 通用函数调用
case class FunctionCall(
  name: String,                   // 函数名（大写）
  arguments: List[Expression]     // 参数列表（可为空，如 NOW()）
) extends Expression

// CAST 表达式
case class CastExpression(
  expression: Expression,         // 被转换的表达式
  targetType: CastType            // 目标类型
) extends Expression

// CONVERT 表达式
case class ConvertExpression(
  expression: Expression,         // 被转换的表达式
  targetType: Option[CastType],   // 函数式：CONVERT(expr, type)
  charset: Option[String]         // USING 式：CONVERT(expr USING charset)
) extends Expression

// CAST/CONVERT 目标类型
sealed trait CastType
case class SignedType(isInt: Boolean = false) extends CastType
case class UnsignedType(isInt: Boolean = false) extends CastType
case class CharCastType(size: Option[Int] = None) extends CastType
case class DecimalCastType(precision: Option[Int] = None, scale: Option[Int] = None) extends CastType
// 复用已有的 DateTimeType, IntType, VarcharType, BooleanType
```

### 4.2 修改已有节点

`parseOptionalAlias()` 方法增强：在 AS 显式别名之外，增加隐式别名支持。

---

## 五、解析策略

### 5.1 函数调用 vs 标识符

在 `parsePrimaryExpression()` 中，当遇到 `IdentifierToken` 时：
- 如果后续是 `LPAREN`，则判定为**函数调用**，解析参数列表
- 否则按原有逻辑处理为普通标识符或 `table.column`

### 5.2 函数调用 vs 聚合函数

聚合函数（COUNT, SUM, AVG, MAX, MIN）已有专门解析，保持不变。
通用函数调用作为补充，处理所有非聚合函数的 `name(...)` 语法。

### 5.3 CAST / CONVERT

CAST 和 CONVERT 作为特殊关键字在 `parsePrimaryExpression()` 中处理，因为它们的语法与普通函数不同（含 AS / USING 关键字）。

### 5.4 隐式别名

在 `parseOptionalAlias()` 中：
1. 检查 `AS` 关键字 → 显式别名
2. 如果当前 Token 是 `IdentifierToken` 且不是语句结构关键字 → 隐式别名
3. 否则 → 无别名

---

## 六、测试用例

| 测试 | SQL |
|------|-----|
| 无参函数 | `SELECT NOW()` |
| 单参函数 | `SELECT UPPER(name) FROM users` |
| 多参函数 | `SELECT CONCAT(first_name, ' ', last_name) FROM users` |
| 函数 + 别名 | `SELECT UPPER(name) AS uname FROM users` |
| 函数嵌套 | `SELECT UPPER(CONCAT(first_name, last_name)) FROM users` |
| 函数在 WHERE 中 | `SELECT * FROM users WHERE LENGTH(name) > 10` |
| IFNULL 函数 | `SELECT IFNULL(nickname, name) FROM users` |
| 隐式别名 | `SELECT name n, age a FROM users` |
| 聚合+隐式别名 | `SELECT COUNT(*) total FROM users` |
| CAST 基本 | `SELECT CAST(price AS DECIMAL(10,2)) FROM products` |
| CAST SIGNED | `SELECT CAST(val AS SIGNED) FROM t` |
| CAST UNSIGNED | `SELECT CAST(val AS UNSIGNED) FROM t` |
| CAST CHAR | `SELECT CAST(age AS CHAR) FROM users` |
| CONVERT 函数式 | `SELECT CONVERT(price, DECIMAL(10,2)) FROM products` |
| CONVERT USING | `SELECT CONVERT(name USING utf8) FROM users` |
