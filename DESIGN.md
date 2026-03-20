# SQL Parser Demo01 设计文档

## 1. 项目概述

### 1.1 项目简介
本项目是一个用 Scala 实现的 MySQL SQL 语句解析器，能够将 SQL 字符串解析为结构化的抽象语法树（AST），为后续的 SQL 分析、优化、执行等操作提供基础。

### 1.2 技术栈
- **编程语言**: Scala 2.13.12
- **构建工具**: SBT (Simple Build Tool)
- **测试框架**: ScalaTest 3.2.17
- **依赖库**: Scala Parser Combinators 2.3.0

### 1.3 核心功能
- ✅ 词法分析（Lexical Analysis）
- ✅ 语法分析（Syntax Analysis）
- ✅ AST 生成（Abstract Syntax Tree Generation）
- ✅ 支持多种 SQL 语句类型

---

## 2. 整体架构设计

### 2.1 架构概览

```
┌─────────────┐
│  SQL 字符串  │
└──────┬──────┘
       │
       ▼
┌─────────────────────┐
│   Lexer (词法分析器)  │
│  - Token 识别        │
│  - 字符流 → Token流   │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   Token Stream      │
│  [SELECT, *, FROM...]│
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│  Parser (语法分析器)  │
│  - 语法规则匹配      │
│  - Token流 → AST     │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   AST (抽象语法树)    │
│  - SelectStatement  │
│  - InsertStatement  │
│  - UpdateStatement  │
│  - etc...           │
└─────────────────────┘
```

### 2.2 编译原理基础

本解析器遵循经典的编译器前端设计，分为两个主要阶段：

#### 2.2.1 词法分析（Lexical Analysis）
- **输入**: 字符串 `"SELECT * FROM users"`
- **输出**: Token 序列 `[SELECT, MULTIPLY_OP, FROM, IdentifierToken("users"), EOF]`
- **职责**: 将连续的字符流分解为有意义的词法单元

#### 2.2.2 语法分析（Syntax Analysis）
- **输入**: Token 序列
- **输出**: AST（抽象语法树）
- **职责**: 根据语法规则验证 Token 序列的合法性，并构建语法树

---

## 3. 核心模块设计

### 3.1 Token 模块 (`Token.scala`)

#### 3.1.1 设计思路
Token 是词法分析的最小单元，表示 SQL 语句中的一个"词"。使用 Scala 的 `sealed trait` 和 `case object/case class` 来实现类型安全的 Token 系统。

#### 3.1.2 Token 分类

```scala
sealed trait Token

// 1. 关键字 Token (Keywords)
case object SELECT extends Token
case object FROM extends Token
case object WHERE extends Token
// ... 更多关键字

// 2. 运算符 Token (Operators)
case object EQUALS extends Token          // =
case object NOT_EQUALS extends Token      // !=
case object PLUS_OP extends Token         // +
case object MINUS_OP extends Token        // -
// ... 更多运算符

// 3. 分隔符 Token (Delimiters)
case object LPAREN extends Token          // (
case object RPAREN extends Token          // )
case object COMMA extends Token           // ,
// ... 更多分隔符

// 4. 字面量 Token (Literals)
case class IdentifierToken(name: String) extends Token
case class StringToken(value: String) extends Token
case class NumberToken(value: String) extends Token
```

#### 3.1.3 设计要点
- **类型安全**: 使用 `sealed trait` 确保所有 Token 类型在编译时已知
- **模式匹配**: 利用 Scala 的模式匹配特性简化 Token 处理
- **命名规范**: Token 名称使用大写，避免与 AST 类名冲突（macOS 大小写不敏感问题）

---

### 3.2 Lexer 模块 (`Lexer.scala`)

#### 3.2.1 核心职责
将输入的 SQL 字符串逐字符扫描，识别并生成 Token 序列。

#### 3.2.2 工作流程

```scala
class Lexer(input: String) {
  private var position = 0  // 当前扫描位置
  
  def tokenize(): List[Token] = {
    // 循环读取所有 Token 直到 EOF
    while (position < input.length) {
      token = nextToken()
      tokens.append(token)
    }
  }
  
  private def nextToken(): Token = {
    // 1. 跳过空白字符
    // 2. 识别数字 -> NumberToken
    // 3. 识别标识符/关键字 -> Keyword 或 IdentifierToken
    // 4. 识别字符串 -> StringToken
    // 5. 识别运算符和分隔符
  }
}
```

#### 3.2.3 关键算法

**1. 关键字识别**
```scala
private val keywords = Map(
  "SELECT" -> SELECT,
  "FROM" -> FROM,
  // ... 更多映射
)

private def readIdentifier(): Token = {
  val text = readUntilNonAlphanumeric()
  val upperText = text.toUpperCase
  // 先查关键字表，如果不是关键字则返回标识符
  keywords.getOrElse(upperText, IdentifierToken(text))
}
```

**2. 字符串字面量识别**
```scala
private def readString(quote: Char): Token = {
  // 支持转义字符
  while (currentChar != quote) {
    if (currentChar == '\\') {
      position += 2  // 跳过转义字符
    } else {
      position += 1
    }
  }
  StringToken(value)
}
```

**3. 多字符运算符识别**
```scala
case '<' =>
  if (peek() == '=') {  // <=
    position += 2
    LESS_EQUAL
  } else {              // <
    position += 1
    LESS_THAN
  }
```

#### 3.2.4 设计特点
- **状态管理**: 使用 `position` 变量跟踪当前扫描位置
- **前瞻机制**: `peek()` 方法用于多字符运算符识别
- **错误处理**: 对非法字符抛出明确的异常
- **大小写处理**: 关键字不区分大小写，但标识符保持原始大小写

---

### 3.3 AST 模块 (`AST.scala`)

#### 3.3.1 设计思路
AST 是 SQL 语句的结构化表示，使用 Scala 的 case class 构建不可变的树形结构。

#### 3.3.2 AST 层次结构

```
SQLStatement (根接口)
├── SelectStatement       (SELECT 查询)
├── InsertStatement       (INSERT 插入)
├── UpdateStatement       (UPDATE 更新)
├── DeleteStatement       (DELETE 删除)
├── CreateTableStatement  (CREATE TABLE)
└── DropTableStatement    (DROP TABLE)
```

#### 3.3.3 核心数据结构

**1. SELECT 语句**
```scala
case class SelectStatement(
  columns: List[Column],              // 选择列
  from: Option[TableReference],       // FROM 子句
  where: Option[Expression],          // WHERE 条件
  orderBy: Option[List[OrderByClause]], // ORDER BY
  groupBy: Option[List[Expression]],  // GROUP BY
  having: Option[Expression],         // HAVING
  limit: Option[Int],                 // LIMIT
  offset: Option[Int],                // OFFSET
  distinct: Boolean = false           // DISTINCT 标志
) extends SQLStatement
```

**2. 表达式系统**
```scala
sealed trait Expression

// 标识符
case class Identifier(name: String) extends Expression
case class QualifiedIdentifier(table: String, column: String) extends Expression

// 字面量
case class StringLiteral(value: String) extends Expression
case class NumberLiteral(value: String) extends Expression
case object NullLiteral extends Expression

// 运算表达式
case class BinaryExpression(
  left: Expression,
  operator: BinaryOperator,
  right: Expression
) extends Expression

case class UnaryExpression(
  operator: UnaryOperator,
  expression: Expression
) extends Expression
```

**3. 运算符系统**
```scala
sealed trait BinaryOperator
case object Equal extends BinaryOperator       // =
case object NotEqual extends BinaryOperator    // !=
case object LessThan extends BinaryOperator    // <
case object GreaterThan extends BinaryOperator // >
case object Plus extends BinaryOperator        // +
case object Minus extends BinaryOperator       // -
case object AndOp extends BinaryOperator       // AND
case object OrOp extends BinaryOperator        // OR
```

#### 3.3.4 设计优势
- **类型安全**: 编译时检查类型正确性
- **不可变性**: case class 默认不可变，线程安全
- **模式匹配**: 方便进行 AST 遍历和转换
- **可扩展性**: 易于添加新的语句类型和表达式

---

### 3.4 Parser 模块 (`Parser.scala`)

#### 3.4.1 设计思路
Parser 实现递归下降解析算法，将 Token 序列转换为 AST。

#### 3.4.2 递归下降解析

```scala
class Parser(tokens: List[Token]) {
  private var position = 0
  
  def parse(): SQLStatement = {
    currentToken() match {
      case SELECT => parseSelect()
      case INSERT => parseInsert()
      case UPDATE => parseUpdate()
      case DELETE => parseDelete()
      case CREATE => parseCreate()
      case DROP => parseDrop()
    }
  }
}
```

#### 3.4.3 核心解析方法

**1. SELECT 语句解析**
```scala
private def parseSelect(): SelectStatement = {
  consume(SELECT)
  
  // 1. 解析 DISTINCT
  val distinct = if (currentToken() == DISTINCT) {
    consume(DISTINCT)
    true
  } else false
  
  // 2. 解析列列表
  val columns = parseColumns()
  
  // 3. 解析 FROM 子句
  val from = if (currentToken() == FROM) {
    consume(FROM)
    Some(parseTableReference())
  } else None
  
  // 4. 解析 WHERE 子句
  val where = if (currentToken() == WHERE) {
    consume(WHERE)
    Some(parseExpression())
  } else None
  
  // 5. 解析其他子句（GROUP BY, ORDER BY, LIMIT, etc.）
  // ...
  
  SelectStatement(columns, from, where, orderBy, groupBy, having, limit, offset, distinct)
}
```

**2. 表达式解析（运算符优先级）**

采用递归下降处理运算符优先级：

```
parseExpression()
  ├── parseOrExpression()           // 优先级最低
  │     └── parseAndExpression()
  │           └── parseComparisonExpression()  // =, !=, <, >, <=, >=
  │                 └── parseAdditiveExpression()    // +, -
  │                       └── parseMultiplicativeExpression()  // *, /
  │                             └── parseUnaryExpression()     // NOT
  │                                   └── parsePrimaryExpression()  // 字面量、标识符
```

**示例：解析 `age > 18 AND status = 'active'`**

```scala
private def parseExpression(): Expression = parseOrExpression()

private def parseOrExpression(): Expression = {
  var left = parseAndExpression()  // 递归到更高优先级
  while (currentToken() == OR) {
    consume(OR)
    val right = parseAndExpression()
    left = BinaryExpression(left, OrOp, right)
  }
  left
}

private def parseAndExpression(): Expression = {
  var left = parseComparisonExpression()
  while (currentToken() == AND) {
    consume(AND)
    val right = parseComparisonExpression()
    left = BinaryExpression(left, AndOp, right)
  }
  left
}

private def parseComparisonExpression(): Expression = {
  val left = parseAdditiveExpression()
  currentToken() match {
    case GREATER_THAN =>
      consume(GREATER_THAN)
      BinaryExpression(left, GreaterThan, parseAdditiveExpression())
    case EQUALS =>
      consume(EQUALS)
      BinaryExpression(left, Equal, parseAdditiveExpression())
    // ... 其他比较运算符
    case _ => left
  }
}
```

**3. JOIN 解析**
```scala
private def parseTableReference(): TableReference = {
  val left = parseSimpleTable()  // users u
  
  if (isJoinToken(currentToken())) {
    val joinType = parseJoinType()  // INNER JOIN
    val right = parseTableReference()  // orders o
    consume(ON)
    val condition = parseExpression()  // u.id = o.user_id
    JoinClause(left, right, joinType, condition)
  } else {
    left
  }
}
```

#### 3.4.4 关键设计模式

**1. 递归下降**
- 每个语法规则对应一个解析方法
- 方法之间相互递归调用
- 简单直观，易于理解和维护

**2. 预测分析**
- 通过 `currentToken()` 预测下一步动作
- 适用于 LL(1) 文法

**3. 错误处理**
```scala
private def consume(expected: Token): Unit = {
  if (currentToken() != expected) {
    throw new RuntimeException(
      s"Expected $expected but got ${currentToken()}"
    )
  }
  position += 1
}
```

---

## 4. 支持的 SQL 语法

### 4.1 SELECT 语句

```sql
SELECT [DISTINCT] column_list
FROM table_reference
[WHERE condition]
[GROUP BY expression_list]
[HAVING condition]
[ORDER BY order_by_list]
[LIMIT number]
[OFFSET number]
```

**支持的特性**:
- `SELECT *` 或具体列名
- 表别名 `table AS alias`
- 列别名 `column AS alias`
- JOIN 操作（INNER, LEFT, RIGHT）
- WHERE 条件（支持复杂表达式）
- GROUP BY 和 HAVING
- ORDER BY
- LIMIT 和 OFFSET

### 4.2 INSERT 语句

```sql
INSERT INTO table_name [(column_list)]
VALUES (value_list), (value_list), ...
```

### 4.3 UPDATE 语句

```sql
UPDATE table_name
SET column1 = value1, column2 = value2, ...
[WHERE condition]
```

### 4.4 DELETE 语句

```sql
DELETE FROM table_name
[WHERE condition]
```

### 4.5 CREATE TABLE 语句

```sql
CREATE TABLE table_name (
  column1 datatype,
  column2 datatype,
  ...
)
```

**支持的数据类型**:
- `INT[(size)]`
- `VARCHAR(size)`
- `TEXT`
- `DATETIME`
- `TIMESTAMP`
- `BOOLEAN`

### 4.6 DROP TABLE 语句

```sql
DROP TABLE table_name
```

---

## 5. 运算符优先级

从低到高：

1. **OR** - 逻辑或
2. **AND** - 逻辑与
3. **=, !=, <, >, <=, >=** - 比较运算符
4. **+, -** - 加减
5. **\*, /** - 乘除
6. **NOT** - 逻辑非（一元运算符）
7. **括号** - 最高优先级

---

## 6. 使用示例

### 6.1 基本用法

```scala
import com.mysql.parser.MySQLParser

// 解析 SQL
val sql = "SELECT id, name FROM users WHERE age > 18"
val ast = MySQLParser.parse(sql)

// 打印 AST
MySQLParser.printAST(ast)
```

### 6.2 AST 遍历

```scala
ast match {
  case SelectStatement(columns, from, where, _, _, _, _, _, _) =>
    println(s"查询 ${columns.length} 列")
    from.foreach(table => println(s"从表: $table"))
    where.foreach(condition => println(s"条件: $condition"))
  
  case InsertStatement(table, columns, values) =>
    println(s"插入到表: $table")
    println(s"插入 ${values.length} 行数据")
  
  // ... 处理其他语句类型
}
```

---

## 7. 扩展性设计

### 7.1 添加新的 SQL 语句类型

**步骤**:

1. 在 `Token.scala` 中添加新的关键字 Token
2. 在 `AST.scala` 中定义新的 Statement case class
3. 在 `Parser.scala` 中实现对应的解析方法
4. 在 `MySQLParser.scala` 中添加打印方法

**示例：添加 ALTER TABLE 支持**

```scala
// 1. Token.scala
case object ALTER extends Token
case object ADD extends Token
case object COLUMN extends Token

// 2. AST.scala
case class AlterTableStatement(
  tableName: String,
  action: AlterAction
) extends SQLStatement

sealed trait AlterAction
case class AddColumn(column: ColumnDefinition) extends AlterAction

// 3. Parser.scala
private def parseAlter(): AlterTableStatement = {
  consume(ALTER)
  consume(TABLE)
  val tableName = parseIdentifier()
  val action = parseAlterAction()
  AlterTableStatement(tableName, action)
}
```

### 7.2 添加新的表达式类型

支持函数调用、子查询等：

```scala
// AST.scala
case class FunctionCall(
  name: String,
  arguments: List[Expression]
) extends Expression

case class SubQuery(
  query: SelectStatement
) extends Expression
```

### 7.3 添加 SQL 优化

可以在 AST 上实现优化器：

```scala
object SQLOptimizer {
  def optimize(ast: SQLStatement): SQLStatement = ast match {
    case select: SelectStatement =>
      // 常量折叠
      // 谓词下推
      // 列裁剪
      optimizeSelect(select)
    case _ => ast
  }
}
```

---

## 8. 性能考虑

### 8.1 当前实现
- **时间复杂度**: O(n)，其中 n 是 SQL 字符串长度
- **空间复杂度**: O(n)，用于存储 Token 列表和 AST

### 8.2 优化建议

**1. 惰性求值**
```scala
class Lexer(input: String) {
  def tokenize(): LazyList[Token] = {
    // 使用 LazyList 延迟计算 Token
  }
}
```

**2. Token 池化**
```scala
object TokenPool {
  private val keywordTokens = Map(
    "SELECT" -> SELECT,
    // ... 预创建的 Token 对象
  )
}
```

**3. 并行解析**
对于大型 SQL 文件，可以并行解析多个语句：
```scala
statements.par.map(sql => MySQLParser.parse(sql))
```

---

## 9. 测试策略

### 9.1 单元测试

**Lexer 测试**:
```scala
"Lexer" should "tokenize SELECT statement" in {
  val tokens = new Lexer("SELECT * FROM users").tokenize()
  tokens should contain theSameElementsInOrderAs List(
    SELECT, MULTIPLY_OP, FROM, IdentifierToken("users"), EOF
  )
}
```

**Parser 测试**:
```scala
"Parser" should "parse SELECT with WHERE" in {
  val ast = MySQLParser.parse("SELECT * FROM users WHERE age > 18")
  ast shouldBe a[SelectStatement]
  val select = ast.asInstanceOf[SelectStatement]
  select.where shouldBe defined
}
```

### 9.2 集成测试

测试完整的 SQL 解析流程：
```scala
"MySQLParser" should "handle complex queries" in {
  val sql = """
    SELECT DISTINCT u.name, COUNT(o.id) as order_count
    FROM users u
    LEFT JOIN orders o ON u.id = o.user_id
    WHERE u.age > 18 AND u.status = 'active'
    GROUP BY u.name
    HAVING COUNT(o.id) > 5
    ORDER BY order_count DESC
    LIMIT 10
  """
  val ast = MySQLParser.parse(sql)
  // 验证 AST 结构
}
```

### 9.3 错误处理测试

```scala
"Parser" should "throw exception for invalid SQL" in {
  assertThrows[RuntimeException] {
    MySQLParser.parse("SELECT * FORM users")  // 拼写错误
  }
}
```

---

## 10. 已知限制与未来改进

### 10.1 当前限制

1. **不支持子查询**
2. **不支持聚合函数**（COUNT, SUM, AVG, MAX, MIN）
3. **不支持 UNION/INTERSECT/EXCEPT**
4. **不支持复杂的 JOIN 条件**
5. **错误提示不够友好**（缺少行号、列号信息）
6. **不支持 SQL 注释**

### 10.2 未来改进方向

**1. 增强错误提示**
```scala
case class Position(line: Int, column: Int)
case class ParseError(message: String, position: Position)
```

**2. 支持更多 SQL 特性**
- 子查询
- 窗口函数
- CTE (WITH 子句)
- 存储过程
- 触发器

**3. 添加语义分析**
```scala
object SemanticAnalyzer {
  def analyze(ast: SQLStatement, schema: DatabaseSchema): ValidationResult = {
    // 检查表是否存在
    // 检查列类型是否匹配
    // 检查权限
  }
}
```

**4. SQL 格式化输出**
```scala
object SQLFormatter {
  def format(ast: SQLStatement): String = {
    // 将 AST 转换回格式化的 SQL 字符串
  }
}
```

**5. 查询执行器**
```scala
object SQLExecutor {
  def execute(ast: SQLStatement, database: Database): ResultSet = {
    // 执行 SQL 并返回结果
  }
}
```

---

## 11. 总结

### 11.1 设计亮点

1. **清晰的架构**: 词法分析 → 语法分析 → AST，职责明确
2. **类型安全**: 充分利用 Scala 类型系统，编译时捕获错误
3. **可扩展性**: 易于添加新的 SQL 语句和表达式类型
4. **模式匹配**: 利用 Scala 特性简化代码
5. **不可变性**: AST 使用不可变数据结构，线程安全

### 11.2 适用场景

- **SQL 分析工具**: 分析 SQL 复杂度、依赖关系
- **SQL 格式化器**: 格式化 SQL 代码
- **SQL 优化器**: 优化 SQL 查询
- **代码生成器**: 从 SQL 生成 ORM 代码
- **教学演示**: 编译原理教学示例

### 11.3 学习价值

本项目是学习以下知识的良好示例：

- **编译原理**: 词法分析、语法分析、AST
- **Scala 编程**: trait、case class、模式匹配
- **函数式编程**: 不可变数据结构、递归
- **软件设计**: 模块化、可扩展性、测试驱动开发

---

## 附录

### A. 文法定义（BNF）

```bnf
<statement> ::= <select> | <insert> | <update> | <delete> | <create_table> | <drop_table>

<select> ::= SELECT [DISTINCT] <column_list> 
             [FROM <table_reference>]
             [WHERE <expression>]
             [GROUP BY <expression_list>]
             [HAVING <expression>]
             [ORDER BY <order_by_list>]
             [LIMIT <number>]
             [OFFSET <number>]

<column_list> ::= * | <column> [, <column>]*

<table_reference> ::= <table_name> [AS <alias>] [<join>]

<join> ::= [INNER | LEFT | RIGHT] JOIN <table_reference> ON <expression>

<expression> ::= <or_expression>
<or_expression> ::= <and_expression> [OR <and_expression>]*
<and_expression> ::= <comparison> [AND <comparison>]*
<comparison> ::= <additive> [<comp_op> <additive>]
<additive> ::= <multiplicative> [<add_op> <multiplicative>]*
<multiplicative> ::= <unary> [<mul_op> <unary>]*
<unary> ::= [NOT] <primary>
<primary> ::= <identifier> | <string> | <number> | NULL | ( <expression> )
```

### B. 参考资料

1. [编译原理](https://en.wikipedia.org/wiki/Compilers:_Principles,_Techniques,_and_Tools) - Dragon Book
2. [MySQL 官方文档](https://dev.mysql.com/doc/)
3. [Scala 官方文档](https://docs.scala-lang.org/)
4. [递归下降解析](https://en.wikipedia.org/wiki/Recursive_descent_parser)

---

**文档版本**: v1.0  
**最后更新**: 2026-03-18  
**作者**: SQL Parser Demo01 Team
