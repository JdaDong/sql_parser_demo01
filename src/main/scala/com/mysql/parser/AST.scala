package com.mysql.parser

/**
 * 抽象语法树（AST）定义
 */
sealed trait SQLStatement

// SELECT 语句
case class SelectStatement(
  columns: List[Column],
  from: Option[TableReference],
  where: Option[Expression],
  orderBy: Option[List[OrderByClause]],
  groupBy: Option[List[Expression]],
  having: Option[Expression],
  limit: Option[Int],
  offset: Option[Int],
  distinct: Boolean = false
) extends SQLStatement

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

// INSERT 语句
case class InsertStatement(
  table: String,
  columns: Option[List[String]],
  values: List[List[Expression]]
) extends SQLStatement

// UPDATE 语句
case class UpdateStatement(
  table: String,
  assignments: List[(String, Expression)],
  where: Option[Expression]
) extends SQLStatement

// DELETE 语句
case class DeleteStatement(
  table: String,
  where: Option[Expression]
) extends SQLStatement

// CREATE TABLE 语句
case class CreateTableStatement(
  tableName: String,
  columns: List[ColumnDefinition]
) extends SQLStatement

// DROP TABLE 语句
case class DropTableStatement(
  tableName: String
) extends SQLStatement

// 列定义
case class ColumnDefinition(
  name: String,
  dataType: DataType,
  nullable: Boolean = true,
  defaultValue: Option[Expression] = None
)

// 数据类型
sealed trait DataType
case class IntType(size: Option[Int] = None) extends DataType
case class VarcharType(size: Int) extends DataType
case object TextType extends DataType
case object DateTimeType extends DataType
case object TimestampType extends DataType
case object BooleanType extends DataType

// 列引用
sealed trait Column
case object AllColumns extends Column  // *
case class NamedColumn(name: String, alias: Option[String] = None) extends Column
case class QualifiedColumn(table: String, column: String, alias: Option[String] = None) extends Column
case class ExpressionColumn(expression: Expression, alias: Option[String] = None) extends Column  // 表达式列（如聚合函数）

// 表引用
sealed trait TableReference
case class TableName(name: String, alias: Option[String] = None) extends TableReference
case class DerivedTable(query: SQLStatement, alias: String) extends TableReference  // FROM 子查询（派生表）
case class JoinClause(
  left: TableReference,
  right: TableReference,
  joinType: JoinType,
  condition: Expression
) extends TableReference

// JOIN 类型
sealed trait JoinType
case object InnerJoin extends JoinType
case object LeftJoin extends JoinType
case object RightJoin extends JoinType

// ORDER BY 子句
case class OrderByClause(expression: Expression, ascending: Boolean = true)

// 表达式
sealed trait Expression

case class Identifier(name: String) extends Expression
case class QualifiedIdentifier(table: String, column: String) extends Expression
case class StringLiteral(value: String) extends Expression
case class NumberLiteral(value: String) extends Expression
case object NullLiteral extends Expression
case object AllColumnsExpression extends Expression  // 用于 COUNT(*) 中的 *

// 二元运算表达式
case class BinaryExpression(left: Expression, operator: BinaryOperator, right: Expression) extends Expression

// 二元运算符
sealed trait BinaryOperator
case object Equal extends BinaryOperator
case object NotEqual extends BinaryOperator
case object LessThan extends BinaryOperator
case object GreaterThan extends BinaryOperator
case object LessEqual extends BinaryOperator
case object GreaterEqual extends BinaryOperator
case object Plus extends BinaryOperator
case object Minus extends BinaryOperator
case object Multiply extends BinaryOperator
case object Divide extends BinaryOperator
case object AndOp extends BinaryOperator
case object OrOp extends BinaryOperator

// 一元运算表达式
case class UnaryExpression(operator: UnaryOperator, expression: Expression) extends Expression

// 谓词表达式
case class IsNullExpression(expression: Expression, negated: Boolean = false) extends Expression        // expr IS [NOT] NULL
case class BetweenExpression(expression: Expression, lower: Expression, upper: Expression, negated: Boolean = false) extends Expression  // expr [NOT] BETWEEN lower AND upper
case class InExpression(expression: Expression, values: List[Expression], negated: Boolean = false) extends Expression  // expr [NOT] IN (v1, v2, ...)
case class LikeExpression(expression: Expression, pattern: Expression, negated: Boolean = false) extends Expression    // expr [NOT] LIKE pattern

// 子查询表达式 — 将完整 SELECT 包装成 Expression
case class SubqueryExpression(query: SQLStatement) extends Expression

// EXISTS 谓词
case class ExistsExpression(query: SQLStatement, negated: Boolean = false) extends Expression

// IN 子查询（区别于 IN 值列表）
case class InSubqueryExpression(expression: Expression, query: SQLStatement, negated: Boolean = false) extends Expression

// 聚合函数表达式
case class AggregateFunction(funcType: AggregateType, argument: Expression, distinct: Boolean = false) extends Expression

// 聚合函数类型
sealed trait AggregateType
case object CountFunc extends AggregateType
case object SumFunc extends AggregateType
case object AvgFunc extends AggregateType
case object MaxFunc extends AggregateType
case object MinFunc extends AggregateType

// WHEN ... THEN ... 分支
case class WhenClause(condition: Expression, result: Expression)

// CASE 表达式（统一搜索式和简单式）
case class CaseExpression(
  operand: Option[Expression],       // 简单式 CASE 的操作数，搜索式为 None
  whenClauses: List[WhenClause],     // WHEN-THEN 分支列表（至少一个）
  elseResult: Option[Expression]     // ELSE 分支（可选）
) extends Expression

// 通用函数调用（非聚合函数，如 UPPER, CONCAT, IFNULL, NOW 等）
case class FunctionCall(
  name: String,                   // 函数名（大写标准化）
  arguments: List[Expression]     // 参数列表（可为空，如 NOW()）
) extends Expression

// CAST/CONVERT 目标类型
sealed trait CastType
case class SignedCastType(isInt: Boolean = false) extends CastType
case class UnsignedCastType(isInt: Boolean = false) extends CastType
case class CharCastType(size: Option[Int] = None) extends CastType
case class DecimalCastType(precision: Option[Int] = None, scale: Option[Int] = None) extends CastType
case object DateCastType extends CastType
case object DateTimeCastType extends CastType
case object IntCastType extends CastType
case object BooleanCastType extends CastType
case class VarcharCastType(size: Int) extends CastType

// CAST 表达式：CAST(expression AS type)
case class CastExpression(
  expression: Expression,
  targetType: CastType
) extends Expression

// CONVERT 表达式：CONVERT(expression, type) 或 CONVERT(expression USING charset)
case class ConvertExpression(
  expression: Expression,
  targetType: Option[CastType],   // 函数式
  charset: Option[String]         // USING 式
) extends Expression

// 一元运算符
sealed trait UnaryOperator
case object NotOp extends UnaryOperator
