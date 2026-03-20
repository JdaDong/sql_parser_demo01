# 运算符优先级是怎么确定的？

> 本文档解释 SQL 解析器中运算符优先级的**理论来源**和**代码实现原理**。

---

## 一、优先级顺序的来源

运算符优先级是 **SQL 标准（ISO/IEC 9075）和 MySQL 官方文档** 规定的，核心依据是**数学惯例 + 逻辑语义**：

| 优先级 | 运算符 | 来源/原因 |
|--------|--------|-----------|
| 最高 | `()` 括号 | 数学惯例 — 括号优先 |
| ↑ | `NOT` 一元运算 | 逻辑学 — 否定绑定最紧 |
| ↑ | `*` `/` 乘除 | 数学惯例 — 先乘除 |
| ↑ | `+` `-` 加减 | 数学惯例 — 后加减 |
| ↑ | `=` `!=` `<` `>` `<=` `>=` 比较 | 比较产生布尔值 |
| ↑ | `IS NULL` `BETWEEN` `IN` `LIKE` 谓词 | SQL 特有谓词，同比较级 |
| ↑ | `AND` 逻辑与 | 逻辑学 — 与 优先于 或 |
| 最低 | `OR` 逻辑或 | 逻辑学 — 或 优先级最低 |

### 直观理解 — 为什么 AND 优先于 OR？

```sql
WHERE age > 18 OR name = 'admin' AND status = 1
```

等价于：
```sql
WHERE age > 18 OR (name = 'admin' AND status = 1)
```

而**不是**：
```sql
WHERE (age > 18 OR name = 'admin') AND status = 1
```

这和数学中 `×` 优先于 `+` 是同一个道理（AND 类似乘法，OR 类似加法）。

---

## 二、代码中如何实现 — 递归下降的"分层调用"

本项目的 Parser 采用**递归下降（Recursive Descent）**方式实现优先级。核心思想是：

> **优先级越低的运算符，其解析方法越靠近调用链顶层（越先被调用）；优先级越高的运算符，越在底层。**

### 调用链结构

```
parseExpression()                              ← 入口
  └── parseOrExpression()                      ← 优先级 1（最低）：OR
        └── parseAndExpression()               ← 优先级 2：AND
              └── parseComparisonExpression()   ← 优先级 3：= != < > 和谓词
                    └── parseAdditiveExpression()        ← 优先级 4：+ -
                          └── parseMultiplicativeExpression()  ← 优先级 5：* /
                                └── parseUnaryExpression()     ← 优先级 6：NOT
                                      └── parsePrimaryExpression()  ← 优先级 7（最高）：字面量、标识符、()
```

### 对应代码

**入口 — `parseExpression()`**：

```scala
private def parseExpression(): Expression = {
  parseOrExpression()
}
```

**优先级 1 — `parseOrExpression()` （OR，最低优先级）**：

```scala
private def parseOrExpression(): Expression = {
  var left = parseAndExpression()          // ← 先调用更高优先级
  while (currentToken() == OR) {
    consume(OR)
    val right = parseAndExpression()
    left = BinaryExpression(left, OrOp, right)
  }
  left
}
```

**优先级 2 — `parseAndExpression()` （AND）**：

```scala
private def parseAndExpression(): Expression = {
  var left = parseComparisonExpression()   // ← 先调用更高优先级
  while (currentToken() == AND) {
    consume(AND)
    val right = parseComparisonExpression()
    left = BinaryExpression(left, AndOp, right)
  }
  left
}
```

**优先级 3 — `parseComparisonExpression()` （比较和谓词）**：

```scala
private def parseComparisonExpression(): Expression = {
  val left = parseAdditiveExpression()     // ← 先调用更高优先级

  currentToken() match {
    case EQUALS       => /* ... */ BinaryExpression(left, Equal, parseAdditiveExpression())
    case NOT_EQUALS   => /* ... */ BinaryExpression(left, NotEqual, parseAdditiveExpression())
    case LESS_THAN    => /* ... */ BinaryExpression(left, LessThan, parseAdditiveExpression())
    case GREATER_THAN => /* ... */ BinaryExpression(left, GreaterThan, parseAdditiveExpression())
    case LESS_EQUAL   => /* ... */ BinaryExpression(left, LessEqual, parseAdditiveExpression())
    case GREATER_EQUAL => /* ... */ BinaryExpression(left, GreaterEqual, parseAdditiveExpression())
    case IS           => /* IS [NOT] NULL */
    case BETWEEN      => /* [NOT] BETWEEN ... AND ... */
    case IN           => /* [NOT] IN (...) */
    case LIKE         => /* [NOT] LIKE ... */
    case NOT          => /* NOT BETWEEN / NOT IN / NOT LIKE */
    case _            => left
  }
}
```

**优先级 4 — `parseAdditiveExpression()` （加减）**：

```scala
private def parseAdditiveExpression(): Expression = {
  var left = parseMultiplicativeExpression()  // ← 先调用更高优先级
  while (currentToken() == PLUS_OP || currentToken() == MINUS_OP) {
    val op = if (currentToken() == PLUS_OP) { consume(PLUS_OP); Plus }
             else { consume(MINUS_OP); Minus }
    val right = parseMultiplicativeExpression()
    left = BinaryExpression(left, op, right)
  }
  left
}
```

**优先级 5 — `parseMultiplicativeExpression()` （乘除）**：

```scala
private def parseMultiplicativeExpression(): Expression = {
  var left = parseUnaryExpression()           // ← 先调用更高优先级
  while (currentToken() == MULTIPLY_OP || currentToken() == DIVIDE_OP) {
    val op = if (currentToken() == MULTIPLY_OP) { consume(MULTIPLY_OP); Multiply }
             else { consume(DIVIDE_OP); Divide }
    val right = parseUnaryExpression()
    left = BinaryExpression(left, op, right)
  }
  left
}
```

**优先级 6 — `parseUnaryExpression()` （NOT 一元）**：

```scala
private def parseUnaryExpression(): Expression = {
  if (currentToken() == NOT) {
    consume(NOT)
    UnaryExpression(NotOp, parseUnaryExpression())
  } else {
    parsePrimaryExpression()
  }
}
```

**优先级 7 — `parsePrimaryExpression()` （最高优先级）**：

```scala
private def parsePrimaryExpression(): Expression = {
  currentToken() match {
    case t if isAggregateToken(t) => parseAggregateFunction()
    case IdentifierToken(name)    => /* 标识符或 table.column */
    case StringToken(value)       => StringLiteral(value)
    case NumberToken(value)       => NumberLiteral(value)
    case NULL                     => NullLiteral
    case LPAREN                   => /* 括号表达式：递归回 parseExpression() */
    case _                        => throw RuntimeException(...)
  }
}
```

---

## 三、为什么这样就能实现优先级？

以 `price > 100 AND stock > 0 OR featured = 1` 为例，看解析过程：

```
parseOrExpression()
│
├── parseAndExpression()              ← 先解析左侧 AND 部分
│   ├── parseComparison()  → price > 100
│   │                       （AND）
│   └── parseComparison()  → stock > 0
│   结果: (price > 100) AND (stock > 0)
│
│                                     （OR）
│
└── parseAndExpression()              ← 再解析右侧
    └── parseComparison()  → featured = 1
    结果: featured = 1

最终 AST:
         OR
        /  \
      AND    featured = 1
     /    \
price>100  stock>0
```

**关键原理**：每个函数先递归调用更高优先级的解析方法来获取操作数，这保证了高优先级运算符的操作数**先被绑定**，自然形成了正确的 AST 树形结构。

- `parseOrExpression` 的操作数是 `parseAndExpression` 的结果 → AND 比 OR 绑定更紧
- `parseAndExpression` 的操作数是 `parseComparisonExpression` 的结果 → 比较比 AND 绑定更紧
- 以此类推...

---

## 四、另一个例子：算术与比较混合

解析 `price * 0.9 + tax > 100`：

```
parseOrExpression()
  └── parseAndExpression()
        └── parseComparisonExpression()
              ├── parseAdditiveExpression()          ← 左操作数
              │   ├── parseMultiplicative()
              │   │   ├── parsePrimary() → price
              │   │   │         （*）
              │   │   └── parsePrimary() → 0.9
              │   │   结果: price * 0.9
              │   │              （+）
              │   └── parseMultiplicative()
              │       └── parsePrimary() → tax
              │   结果: (price * 0.9) + tax
              │
              │              （>）
              │
              └── parseAdditiveExpression()          ← 右操作数
                  └── parsePrimary() → 100

最终 AST:
            >
           / \
          +   100
         / \
        *   tax
       / \
   price  0.9
```

可以看到：`*` 在树的最深处（最先计算），`+` 在中间，`>` 在最顶层（最后计算），完美对应优先级。

---

## 五、总结规律

| 你想要的效果 | 在递归下降中的做法 |
|-------------|-------------------|
| 运算符 A 优先级比 B **高** | A 的解析方法在调用链中比 B **更深**（更靠底层） |
| 运算符 A 优先级比 B **低** | A 的解析方法在调用链中比 B **更浅**（更靠顶层） |
| 新增一种运算符 | 在调用链中的合适层级**插入**一个新的解析方法 |

### 新增运算符的示例

如果要加**位运算** `&` `|`（优先级在算术和比较之间），只需要：

```
parseComparisonExpression()
  └── parseBitwiseOrExpression()        ← 新增！位或 |
        └── parseBitwiseAndExpression() ← 新增！位与 &
              └── parseAdditiveExpression()
                    └── parseMultiplicativeExpression()
                          └── ...
```

这就是递归下降解析器处理运算符优先级的全部秘密——**调用链的深度 = 优先级的高低**。

---

## 附录：MySQL 完整运算符优先级（供参考）

```
优先级从高到低：

1.  INTERVAL
2.  BINARY, COLLATE
3.  !  (逻辑非)
4.  - (一元减), ~ (按位取反)
5.  ^  (按位异或)
6.  *, /, DIV, %, MOD
7.  -, +
8.  <<, >>
9.  &  (按位与)
10. |  (按位或)
11. = (比较), <=>, >=, >, <=, <, <>, !=, IS, LIKE, REGEXP, IN, MEMBER OF
12. BETWEEN, CASE, WHEN, THEN, ELSE
13. NOT
14. AND, &&
15. XOR
16. OR, ||
17. = (赋值), :=
```

> 本项目目前实现了其中最核心的子集（第 6-14 级），已经覆盖了日常 SQL 的绝大多数场景。

---

*本文档基于项目 `Parser.scala` 源码分析生成。*
