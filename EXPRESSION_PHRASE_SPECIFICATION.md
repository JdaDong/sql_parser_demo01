# Expression 和 Phrase 的形式化定义与规范

> **文档说明**：本文档详细阐述编译原理和 SQL 标准中 Expression 和 Phrase 的形式化定义

---

## 📚 一、编译原理中的定义

### 1.1 Phrase (短语)

#### 📖 形式化定义

在形式语言理论中，**Phrase** 是指在语法树中，从某个**非终结符**推导出的任何符号串。

**定义**（根据 Chomsky 文法理论）：

> 给定上下文无关文法 G = (N, Σ, P, S)，其中：
> - N: 非终结符集合
> - Σ: 终结符集合（Token 集合）
> - P: 产生式规则集合
> - S: 起始符号
>
> 如果存在推导 S ⇒* αAβ ⇒* αγβ，其中 A ∈ N，γ ∈ (N ∪ Σ)*，
> 则称 **γ 是相对于非终结符 A 的短语（Phrase）**

#### 🎯 简单理解

**Phrase = 文法规则中任何非终结符推导出的符号串**

分类：
- **Simple Phrase（简单短语）**：直接由一个产生式推导出
- **Handle（句柄）**：可归约的最左简单短语
- **Sentential Form（句型）**：推导过程中的任意形式

#### 📝 示例

文法：
```bnf
<Expression> ::= <Term> | <Expression> + <Term>
<Term>       ::= <Factor> | <Term> * <Factor>
<Factor>     ::= id | num | ( <Expression> )
```

对于输入 `a + b * c`：

**Phrases（短语）**：
- `a` - <Factor> 的 phrase
- `b` - <Factor> 的 phrase  
- `c` - <Factor> 的 phrase
- `b * c` - <Term> 的 phrase
- `a + b * c` - <Expression> 的 phrase

---

### 1.2 Expression (表达式)

#### 📖 形式化定义

**Expression** 是编程语言中用于**计算值**的语法结构，由**操作数**和**运算符**组合而成。

**BNF 定义**（经典形式）：

```bnf
<Expression>    ::= <LogicalExpr>

<LogicalExpr>   ::= <CompareExpr> 
                  | <LogicalExpr> OR <CompareExpr>
                  | <LogicalExpr> AND <CompareExpr>

<CompareExpr>   ::= <AdditiveExpr>
                  | <CompareExpr> = <AdditiveExpr>
                  | <CompareExpr> != <AdditiveExpr>
                  | <CompareExpr> < <AdditiveExpr>
                  | <CompareExpr> > <AdditiveExpr>

<AdditiveExpr>  ::= <MultExpr>
                  | <AdditiveExpr> + <MultExpr>
                  | <AdditiveExpr> - <MultExpr>

<MultExpr>      ::= <UnaryExpr>
                  | <MultExpr> * <UnaryExpr>
                  | <MultExpr> / <UnaryExpr>

<UnaryExpr>     ::= <PrimaryExpr>
                  | - <UnaryExpr>
                  | NOT <UnaryExpr>

<PrimaryExpr>   ::= id | number | string
                  | ( <Expression> )
                  | <FunctionCall>
```

#### 🎯 关键特性

1. **可求值性**：Expression 必须能计算出一个值
2. **递归性**：Expression 可以包含子 Expression
3. **类型性**：Expression 有明确的类型（数值、字符串、布尔等）
4. **运算符优先级**：通过文法层次体现

---

## 📊 二、Expression vs Phrase 对比

| 特性 | Phrase（短语） | Expression（表达式） |
|------|---------------|-------------------|
| **定义范围** | 形式语言理论概念 | 编程语言具体概念 |
| **抽象层次** | 更抽象（任何非终结符推导） | 更具体（可求值的语法结构） |
| **求值性** | 不一定可求值 | **必须可求值** |
| **范围** | 所有非终结符的推导 | 特定的语法类别 |
| **用途** | 语法分析理论 | 实际编程语言设计 |

### 🔍 关系说明

```
Phrase（短语）
    ├── Expression（表达式）       ← 可求值的 phrase
    ├── Statement（语句）          ← 执行动作的 phrase
    ├── Declaration（声明）        ← 定义实体的 phrase
    └── Clause（子句）             ← SQL 中的特定 phrase
```

**结论**：
- ✅ **所有 Expression 都是 Phrase**
- ❌ **并非所有 Phrase 都是 Expression**

---

## 🗃️ 三、SQL 标准中的定义

### 3.1 ISO/IEC 9075-2 (SQL/Foundation) 规范

#### 📖 Value Expression（值表达式）

根据 **ISO/IEC 9075-2:2016**，SQL 中的 Expression 正式定义为：

```bnf
<value expression> ::=
      <numeric value expression>
    | <string value expression>
    | <datetime value expression>
    | <interval value expression>
    | <boolean value expression>
    | <user-defined type value expression>
    | <row value expression>
    | <collection value expression>

<numeric value expression> ::=
      <term>
    | <numeric value expression> <plus sign> <term>
    | <numeric value expression> <minus sign> <term>

<term> ::=
      <factor>
    | <term> <asterisk> <factor>
    | <term> <solidus> <factor>

<factor> ::=
      [ <sign> ] <numeric primary>

<numeric primary> ::=
      <value expression primary>
    | <numeric value function>
```

#### 📋 SQL Expression 分类

##### 1. **数值表达式 (Numeric Value Expression)**
```sql
-- 算术运算
salary * 1.1
price + tax
quantity / 2

-- 函数调用
ABS(-10)
ROUND(3.14159, 2)
```

##### 2. **字符串表达式 (String Value Expression)**
```sql
-- 连接操作
first_name || ' ' || last_name
CONCAT('Hello', 'World')

-- 函数
UPPER(name)
SUBSTRING(text FROM 1 FOR 10)
```

##### 3. **布尔表达式 (Boolean Value Expression)**
```sql
-- 比较
age > 18
status = 'active'

-- 逻辑运算
age >= 18 AND status = 'active'
is_admin OR is_moderator

-- 谓词
name IS NOT NULL
price BETWEEN 10 AND 100
city IN ('Beijing', 'Shanghai')
```

##### 4. **日期时间表达式 (Datetime Value Expression)**
```sql
-- 运算
CURRENT_DATE + INTERVAL '7' DAY
end_time - start_time

-- 函数
EXTRACT(YEAR FROM birth_date)
```

##### 5. **CASE 表达式**
```sql
CASE 
  WHEN age < 18 THEN 'Minor'
  WHEN age < 65 THEN 'Adult'
  ELSE 'Senior'
END
```

##### 6. **子查询表达式**
```sql
-- 标量子查询
(SELECT MAX(salary) FROM employees)

-- EXISTS 谓词
EXISTS (SELECT 1 FROM orders WHERE user_id = users.id)
```

---

### 3.2 SQL Phrase 的非正式使用

在 SQL 中，**"Phrase"** 通常指代**语法片段**，不是正式术语，但常见用法：

| SQL 术语 | 说明 | 示例 |
|---------|------|------|
| **Clause（子句）** | 完整的语法单元 | `WHERE age > 18` |
| **Predicate（谓词）** | 布尔表达式 | `age > 18` |
| **Condition（条件）** | 布尔表达式的别称 | `status = 'active'` |
| **Phrase（短语）** | 非正式，指任何语法片段 | `ORDER BY name` |

---

## 🔧 四、在我们的解析器中的应用

### 4.1 Expression 的实现

在我们的 `AST.scala` 中，Expression 定义如下：

```scala
// 表达式基类
sealed trait Expression

// 基础表达式（Primary Expression）
case class Identifier(name: String) extends Expression
case class QualifiedIdentifier(table: String, column: String) extends Expression
case class StringLiteral(value: String) extends Expression
case class NumberLiteral(value: String) extends Expression
case object NullLiteral extends Expression

// 复合表达式
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

**运算符定义**：

```scala
// 二元运算符
sealed trait BinaryOperator
case object Equal extends BinaryOperator         // =
case object NotEqual extends BinaryOperator      // !=
case object LessThan extends BinaryOperator      // <
case object GreaterThan extends BinaryOperator   // >
case object LessEqual extends BinaryOperator     // <=
case object GreaterEqual extends BinaryOperator  // >=
case object Plus extends BinaryOperator          // +
case object Minus extends BinaryOperator         // -
case object Multiply extends BinaryOperator      // *
case object Divide extends BinaryOperator        // /
case object AndOp extends BinaryOperator         // AND
case object OrOp extends BinaryOperator          // OR

// 一元运算符
sealed trait UnaryOperator
case object NotOp extends UnaryOperator          // NOT
```

---

### 4.2 Phrase 的实现

在我们的解析器中，Phrase 对应各种**语法单元**：

#### 📌 **Statement Level Phrases**（语句级短语）

```scala
sealed trait SQLStatement

case class SelectStatement(...) extends SQLStatement
case class InsertStatement(...) extends SQLStatement
case class UpdateStatement(...) extends SQLStatement
case class DeleteStatement(...) extends SQLStatement
case class CreateTableStatement(...) extends SQLStatement
case class DropTableStatement(...) extends SQLStatement
```

#### 📌 **Clause Level Phrases**（子句级短语）

虽然不是独立类型，但在 `SelectStatement` 中体现：

```scala
case class SelectStatement(
  columns: List[Column],           // SELECT clause
  from: Option[TableReference],    // FROM clause
  where: Option[Expression],       // WHERE clause
  orderBy: Option[List[OrderByClause]], // ORDER BY clause
  groupBy: Option[List[Expression]],    // GROUP BY clause
  having: Option[Expression],      // HAVING clause
  limit: Option[Int],              // LIMIT clause
  offset: Option[Int],             // OFFSET clause
  distinct: Boolean = false        // DISTINCT modifier
)
```

#### 📌 **Sub-phrase Components**（子短语组件）

```scala
// 列引用短语
sealed trait Column
case object AllColumns extends Column
case class NamedColumn(name: String, alias: Option[String]) extends Column
case class QualifiedColumn(table: String, column: String, alias: Option[String]) extends Column

// 表引用短语
sealed trait TableReference
case class TableName(name: String, alias: Option[String]) extends TableReference
case class JoinClause(
  left: TableReference,
  right: TableReference,
  joinType: JoinType,
  condition: Expression
) extends TableReference

// ORDER BY 短语
case class OrderByClause(expression: Expression, ascending: Boolean = true)
```

---

### 4.3 实际解析示例

#### 示例 SQL
```sql
SELECT u.name, o.total 
FROM users u 
JOIN orders o ON u.id = o.user_id 
WHERE o.total > 100 
ORDER BY o.total DESC
```

#### 解析结果（AST）

```scala
SelectStatement(
  // SELECT clause (Phrase)
  columns = List(
    QualifiedColumn("u", "name", None),      // Expression in SELECT
    QualifiedColumn("o", "total", None)      // Expression in SELECT
  ),
  
  // FROM clause with JOIN (Phrase)
  from = Some(JoinClause(
    left = TableName("users", Some("u")),
    right = TableName("orders", Some("o")),
    joinType = InnerJoin,
    condition = BinaryExpression(            // Expression in ON clause
      QualifiedIdentifier("u", "id"),
      Equal,
      QualifiedIdentifier("o", "user_id")
    )
  )),
  
  // WHERE clause (Phrase)
  where = Some(BinaryExpression(             // Expression in WHERE
    QualifiedIdentifier("o", "total"),
    GreaterThan,
    NumberLiteral("100")
  )),
  
  // ORDER BY clause (Phrase)
  orderBy = Some(List(
    OrderByClause(
      QualifiedIdentifier("o", "total"),     // Expression in ORDER BY
      ascending = false
    )
  )),
  
  groupBy = None,
  having = None,
  limit = None,
  offset = None,
  distinct = false
)
```

---

### 4.4 层次关系图

```
SQLStatement (最高层 Phrase)
    │
    ├─ SelectStatement
    │      ├─ columns: List[Column] ──────┐
    │      │                              │ Phrase: SELECT clause
    │      │   └─ QualifiedColumn ────────┤
    │      │                              │
    │      ├─ from: TableReference ───────┤ Phrase: FROM clause
    │      │   └─ JoinClause              │
    │      │       └─ condition: Expression ◄── Expression!
    │      │                              │
    │      ├─ where: Expression ──────────┤ Phrase: WHERE clause
    │      │   └─ BinaryExpression ────◄─── Expression!
    │      │                              │
    │      └─ orderBy: OrderByClause ─────┘ Phrase: ORDER BY clause
    │             └─ expression: Expression ◄── Expression!
    │
    └─ InsertStatement
           ├─ table: String
           ├─ columns: List[String]
           └─ values: List[List[Expression]] ◄── Expression!
```

**关键观察**：
- ✅ **Expression** 出现在各个 Phrase 中
- ✅ **Phrase** 包含 Expression 和其他语法元素
- ✅ **嵌套结构**：Phrase 可以包含子 Phrase

---

## 📖 五、权威参考资料

### 5.1 编译原理经典著作

1. **《编译原理》（龙书）**  
   - 作者：Alfred V. Aho, Monica S. Lam, Ravi Sethi, Jeffrey D. Ullman
   - ISBN: 978-0321486813
   - 第 4.5 节详细讨论 Phrase 和 Handle

2. **《形式语言与自动机理论》**  
   - 作者：John E. Hopcroft, Rajeev Motwani, Jeffrey D. Ullman
   - Chomsky 文法和 Phrase Structure Grammar

3. **《程序设计语言：实践之路》**  
   - 作者：Michael L. Scott
   - 表达式求值和语法树构造

---

### 5.2 SQL 标准文档

#### 官方标准（需购买）

1. **ISO/IEC 9075-1:2016** - SQL Framework
2. **ISO/IEC 9075-2:2016** - SQL/Foundation  
   ✅ **最重要**：包含完整的 BNF 文法定义
3. **ISO/IEC 9075-11:2016** - SQL/Schemata

🔗 购买地址：https://www.iso.org/standard/63555.html

#### 免费资源

1. **MySQL 8.0 Reference Manual**  
   🔗 https://dev.mysql.com/doc/refman/8.0/en/
   - Grammar and Syntax
   - Expression Syntax
   - Operator Precedence

2. **PostgreSQL Documentation**  
   🔗 https://www.postgresql.org/docs/current/sql-syntax.html
   - SQL Syntax
   - Value Expressions

3. **SQL:2023 Draft（工作草案）**  
   可从 ISO JTC1/SC32/WG3 工作组网站获取部分草稿

---

### 5.3 在线资源

1. **BNF Grammars**  
   🔗 https://www.cs.man.ac.uk/~pjj/bnf/bnf.html  
   BNF 文法教程

2. **SQL Grammar Reference**  
   🔗 https://jakewheat.github.io/sql-overview/sql-2011-foundation-grammar.html  
   SQL-2011 Foundation Grammar 在线版本

3. **ANTLR Grammars**  
   🔗 https://github.com/antlr/grammars-v4/tree/master/sql  
   各种 SQL 方言的 ANTLR 文法

---

## 💡 六、常见问题解答

### Q1: Expression 和 Phrase 哪个更基础？

**A**: **Phrase 更基础**。
- Phrase 是形式语言理论的概念，适用于所有文法
- Expression 是特定的 Phrase 类型，专门用于可求值的语法结构

### Q2: 在实现解析器时，需要区分它们吗？

**A**: **实现上不需要严格区分**，但**设计上需要理解**：
- 实现时：都是 AST 节点
- 设计时：理解层次关系有助于设计清晰的类型系统

### Q3: SQL 标准对 Expression 有什么特殊要求？

**A**: SQL 标准要求：
1. ✅ **类型安全**：Expression 必须有明确的数据类型
2. ✅ **可求值**：在运行时能产生值
3. ✅ **NULL 处理**：必须定义 NULL 值的语义
4. ✅ **运算符优先级**：必须遵循标准定义的优先级

### Q4: 我们的解析器是否符合标准？

**A**: **部分符合**：
- ✅ 基本 Expression 类型（数值、字符串、标识符）
- ✅ 二元和一元运算符
- ✅ 运算符优先级
- ⚠️ 缺少：
  - CASE 表达式
  - 子查询表达式
  - 聚合函数（COUNT, SUM 等）
  - CAST 类型转换
  - NULL 谓词（IS NULL, IS NOT NULL）

### Q5: 如何扩展支持更多 Expression 类型？

**A**: 添加新的 Expression 子类型：

```scala
// 1. 在 AST.scala 中添加新类型
case class CaseExpression(
  conditions: List[(Expression, Expression)],
  elseExpr: Option[Expression]
) extends Expression

case class FunctionCall(
  name: String,
  arguments: List[Expression]
) extends Expression

case class SubqueryExpression(
  query: SelectStatement
) extends Expression

// 2. 在 Parser.scala 中添加解析逻辑
private def parsePrimary(): Expression = {
  currentToken() match {
    case CASE => parseCaseExpression()
    case IdentifierToken(name) if peek() == LPAREN => 
      parseFunctionCall()
    case LPAREN if isSubquery() => 
      parseSubquery()
    // ... 其他情况
  }
}
```

---

## 🎯 七、总结

### 核心要点

1. **Phrase（短语）**
   - ✅ 形式语言理论概念
   - ✅ 任何非终结符推导的符号串
   - ✅ 包含 Statement、Clause、Expression 等所有语法单元

2. **Expression（表达式）**
   - ✅ 特定的 Phrase 类型
   - ✅ 必须可求值
   - ✅ 有明确的类型
   - ✅ 递归组合结构

3. **在 SQL 中**
   - ✅ Expression 有正式的 ISO 标准定义
   - ✅ Phrase 通常指代 Clause（子句）
   - ✅ 两者共同构成完整的 SQL 语法树

4. **在我们的解析器中**
   - ✅ `sealed trait Expression` 实现可求值的表达式
   - ✅ `SQLStatement`、`TableReference`、`Column` 等实现 Phrase
   - ✅ 清晰的层次结构和类型安全

---

## 📚 附录：完整 BNF 定义

### A.1 通用 Expression BNF

```bnf
<expression>            ::= <logical_or_expression>

<logical_or_expression> ::= <logical_and_expression>
                          | <logical_or_expression> OR <logical_and_expression>

<logical_and_expression> ::= <equality_expression>
                           | <logical_and_expression> AND <equality_expression>

<equality_expression>   ::= <relational_expression>
                          | <equality_expression> = <relational_expression>
                          | <equality_expression> != <relational_expression>

<relational_expression> ::= <additive_expression>
                          | <relational_expression> < <additive_expression>
                          | <relational_expression> > <additive_expression>
                          | <relational_expression> <= <additive_expression>
                          | <relational_expression> >= <additive_expression>

<additive_expression>   ::= <multiplicative_expression>
                          | <additive_expression> + <multiplicative_expression>
                          | <additive_expression> - <multiplicative_expression>

<multiplicative_expression> ::= <unary_expression>
                              | <multiplicative_expression> * <unary_expression>
                              | <multiplicative_expression> / <unary_expression>

<unary_expression>      ::= <primary_expression>
                          | - <unary_expression>
                          | NOT <unary_expression>

<primary_expression>    ::= <identifier>
                          | <literal>
                          | ( <expression> )
                          | <function_call>
```

### A.2 SQL SELECT Statement BNF

```bnf
<select_statement> ::= 
    SELECT [DISTINCT] <select_list>
    [<from_clause>]
    [<where_clause>]
    [<group_by_clause>]
    [<having_clause>]
    [<order_by_clause>]
    [<limit_clause>]

<select_list>      ::= * | <column_spec> [, <column_spec>]*

<column_spec>      ::= <expression> [AS <alias>]

<from_clause>      ::= FROM <table_reference> [, <table_reference>]*

<table_reference>  ::= <table_name> [AS <alias>]
                     | <joined_table>

<joined_table>     ::= <table_reference> [INNER] JOIN <table_reference>
                       ON <expression>

<where_clause>     ::= WHERE <expression>

<group_by_clause>  ::= GROUP BY <expression_list>

<having_clause>    ::= HAVING <expression>

<order_by_clause>  ::= ORDER BY <order_item> [, <order_item>]*

<order_item>       ::= <expression> [ASC | DESC]

<limit_clause>     ::= LIMIT <number> [OFFSET <number>]
```

---

## 📝 文档修订历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0  | 2026-03-18 | 初始版本，包含完整定义和规范 |

---

**文档作者**：CodeBuddy AI  
**最后更新**：2026-03-18  
**许可证**：MIT License

<function_calls>
<invoke name="read_file">
<parameter name="filePath">/Users/jiangdadong/CodeBuddy/scalademo01/src/main/scala/com/mysql/parser/AST.scala