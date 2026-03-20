# AST Visitor 模式设计文档

> **文档版本**: v1.0
> **最后更新**: 2026-03-20
> **模块位置**: `src/main/scala/com/mysql/parser/ASTVisitor.scala`

---

## 1. 设计目标

在编译器前端中，语法分析器生成的 AST 通常会被**多个后续阶段**反复遍历：
- 语义检查（列名是否存在）
- SQL 格式化（Pretty Print）
- 统计分析（提取所有表名、列名）
- AST 变换（查询重写、优化）

如果每个阶段都用 `match` 穷举所有 AST 节点类型，会导致大量重复的模式匹配代码。

**Visitor 模式** 将"遍历逻辑"与"操作逻辑"分离，提供统一的遍历框架，让用户只需重写关注的节点方法。

---

## 2. 架构总览

```
                            ┌──────────────────┐
                            │    ASTVisitor[R]  │  （只读遍历，返回 R）
                            │  visit(node): R   │
                            └──────────────────┘
                                    │
                     ┌──────────────┼──────────────┐
                     │              │              │
              ┌──────┴──────┐ ┌────┴────┐  ┌──────┴───────┐
              │ColumnExtractor│ │TableExtractor│ │SQLPrettyPrinter│
              │  R = List[String]│ │ R = List[String]│ │ R = String      │
              └─────────────┘ └──────────┘  └──────────────┘

                            ┌──────────────────┐
                            │  ASTTransformer   │  （变换 AST，返回新 AST）
                            │ transform(node)   │
                            └──────────────────┘
                                    │
                     ┌──────────────┼──────────────┐
                     │              │              │
              ┌──────┴──────┐ ┌────┴────┐  ┌──────┴───────┐
              │TableRenamer │ │ColumnRenamer│ │StarExpander   │
              └─────────────┘ └──────────┘  └──────────────┘
```

---

## 3. 核心接口设计

### 3.1 ASTVisitor[R] — 只读遍历

```scala
trait ASTVisitor[R] {
  // 结果合并策略（由子类定义）
  def zero: R                          // 零值
  def combine(a: R, b: R): R          // 合并两个结果

  // === 语句级入口 ===
  def visitStatement(stmt: SQLStatement): R
  def visitSelectStatement(stmt: SelectStatement): R
  def visitInsertStatement(stmt: InsertStatement): R
  def visitUpdateStatement(stmt: UpdateStatement): R
  def visitDeleteStatement(stmt: DeleteStatement): R
  def visitCreateTableStatement(stmt: CreateTableStatement): R
  def visitDropTableStatement(stmt: DropTableStatement): R
  def visitUnionStatement(stmt: UnionStatement): R

  // === 列级 ===
  def visitColumn(col: Column): R
  def visitAllColumns(col: AllColumns.type): R
  def visitNamedColumn(col: NamedColumn): R
  def visitQualifiedColumn(col: QualifiedColumn): R
  def visitExpressionColumn(col: ExpressionColumn): R

  // === 表引用级 ===
  def visitTableReference(ref: TableReference): R
  def visitTableName(ref: TableName): R
  def visitDerivedTable(ref: DerivedTable): R
  def visitJoinClause(ref: JoinClause): R

  // === 表达式级 ===
  def visitExpression(expr: Expression): R
  // ... 每种 Expression 子类一个方法
}
```

**关键设计**：
- **泛型 R**：Visitor 的返回值类型，不同 Visitor 返回不同类型
- **zero / combine**：Monoid-like 接口，用于合并子节点的遍历结果
- **默认实现**：基类提供默认的深度优先递归遍历，子类只需 override 关注的节点

### 3.2 ASTTransformer — AST 变换

```scala
trait ASTTransformer {
  def transformStatement(stmt: SQLStatement): SQLStatement
  def transformExpression(expr: Expression): Expression
  def transformColumn(col: Column): Column
  def transformTableReference(ref: TableReference): TableReference
}
```

**关键设计**：
- 每个 `transform*` 方法返回**同类型的新节点**
- 默认实现递归地对子节点做变换，然后重建父节点
- 子类只需 override 需要变换的节点类型

---

## 4. 内置 Visitor 实现

### 4.1 ColumnExtractor（列名提取器）

```scala
class ColumnExtractor extends ASTVisitor[List[String]] {
  // 提取查询中引用的所有列名
  // 用途：依赖分析、审计日志
}
```

### 4.2 TableExtractor（表名提取器）

```scala
class TableExtractor extends ASTVisitor[List[String]] {
  // 提取查询中引用的所有表名（含 JOIN、子查询中的表）
  // 用途：权限检查、血缘分析
}
```

### 4.3 SQLPrettyPrinter（SQL 格式化器）

```scala
class SQLPrettyPrinter extends ASTVisitor[String] {
  // 将 AST 反向转换为格式规范的 SQL 字符串
  // AST → SQL 的往返验证
}
```

### 4.4 TableRenamer（表名重命名变换器）

```scala
class TableRenamer(mapping: Map[String, String]) extends ASTTransformer {
  // 将 AST 中的表名按映射关系重命名
  // 用途：分表路由、多租户查询重写
}
```

### 4.5 ColumnRenamer（列名重命名变换器）

```scala
class ColumnRenamer(mapping: Map[String, String]) extends ASTTransformer {
  // 将 AST 中的列名按映射关系重命名
  // 用途：Schema 迁移、字段映射
}
```

---

## 5. Monoid 合并策略

不同 Visitor 需要不同的合并方式：

| Visitor | R 类型 | zero | combine |
|---------|--------|------|---------|
| ColumnExtractor | `List[String]` | `Nil` | `++` |
| TableExtractor | `List[String]` | `Nil` | `++` |
| SQLPrettyPrinter | `String` | `""` | `+` |

---

## 6. 使用示例

### 6.1 提取所有表名

```scala
val ast = MySQLParser.parse("SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id")
val extractor = new TableExtractor()
val tables = extractor.visitStatement(ast)
// tables = List("users", "orders")
```

### 6.2 SQL 格式化

```scala
val ast = MySQLParser.parse("SELECT id,name FROM users WHERE age>18 ORDER BY name")
val printer = new SQLPrettyPrinter()
val sql = printer.visitStatement(ast)
// sql = "SELECT\n  id,\n  name\nFROM\n  users\nWHERE\n  age > 18\nORDER BY\n  name ASC"
```

### 6.3 表名重命名

```scala
val ast = MySQLParser.parse("SELECT * FROM users WHERE id = 1")
val renamer = new TableRenamer(Map("users" -> "users_v2"))
val newAst = renamer.transformStatement(ast)
// newAst 中 users 被替换为 users_v2
```

---

## 7. 文件清单

| 文件 | 内容 |
|------|------|
| `ASTVisitor.scala` | `ASTVisitor[R]` trait + `ASTTransformer` trait + 5 个内置实现 |
| `MySQLParser.scala` | 集成 Visitor 演示（提取表名/列名、格式化、变换） |
| `MySQLParserTest.scala` | Visitor/Transformer 测试用例 |

---

## 8. 与现有 SemanticAnalyzer 的关系

当前 `SemanticAnalyzer` 使用手动 `match` 遍历 AST。Visitor 模式实现后：
- SemanticAnalyzer 可以**可选地**重构为 ASTVisitor 的子类
- 但考虑到 SemanticAnalyzer 的逻辑较复杂（需要 QueryScope 上下文），暂不强制改造
- Visitor 模式主要面向**新的分析/转换需求**

---

*本设计基于 GoF Visitor Pattern + Scala trait 混入特性，兼顾类型安全和扩展灵活性。*
