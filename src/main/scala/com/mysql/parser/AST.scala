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

// 集合运算类型（UNION / INTERSECT / EXCEPT）
sealed trait UnionType
case object UnionDistinct extends UnionType        // UNION（去重）
case object UnionAll extends UnionType             // UNION ALL（保留重复）
case object IntersectDistinct extends UnionType    // INTERSECT（去重）
case object IntersectAll extends UnionType         // INTERSECT ALL（保留重复）
case object ExceptDistinct extends UnionType       // EXCEPT（去重）
case object ExceptAll extends UnionType            // EXCEPT ALL（保留重复）

// 集合运算查询（组合多个 SELECT）
case class UnionStatement(
  left: SQLStatement,         // 左侧 SELECT（或嵌套集合运算）
  right: SelectStatement,     // 右侧 SELECT
  unionType: UnionType        // UNION / INTERSECT / EXCEPT [ALL]
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
  columns: List[ColumnDefinition],
  constraints: List[TableConstraint] = Nil
) extends SQLStatement

// DROP TABLE 语句
case class DropTableStatement(
  tableName: String,
  ifExists: Boolean = false
) extends SQLStatement

// ============================================================
//  ALTER TABLE 语句
// ============================================================

// ALTER TABLE 操作类型
sealed trait AlterAction

// ADD COLUMN name type [constraints]
case class AddColumnAction(column: ColumnDefinition) extends AlterAction
// DROP COLUMN name
case class DropColumnAction(columnName: String) extends AlterAction
// MODIFY COLUMN name newType [constraints]
case class ModifyColumnAction(column: ColumnDefinition) extends AlterAction
// CHANGE COLUMN oldName newName newType [constraints]
case class ChangeColumnAction(oldName: String, newColumn: ColumnDefinition) extends AlterAction
// RENAME TO newTableName
case class RenameTableAction(newName: String) extends AlterAction
// ADD CONSTRAINT ... (表级约束)
case class AddConstraintAction(constraint: TableConstraint) extends AlterAction
// DROP PRIMARY KEY / DROP INDEX name / DROP FOREIGN KEY name
case class DropConstraintAction(constraintType: String, name: Option[String] = None) extends AlterAction

// ALTER TABLE 语句
case class AlterTableStatement(
  tableName: String,
  actions: List[AlterAction]
) extends SQLStatement

// ============================================================
//  索引支持
// ============================================================

// CREATE [UNIQUE] INDEX name ON table (col1, col2, ...)
case class CreateIndexStatement(
  indexName: String,
  tableName: String,
  columns: List[IndexColumn],
  unique: Boolean = false
) extends SQLStatement

// DROP INDEX name ON table
case class DropIndexStatement(
  indexName: String,
  tableName: String
) extends SQLStatement

// 索引列（支持排序方向）
case class IndexColumn(
  name: String,
  ascending: Boolean = true
)

// ============================================================
//  约束条件
// ============================================================

// 列级约束（附加在 ColumnDefinition 上）
sealed trait ColumnConstraint
case object NotNullConstraint extends ColumnConstraint            // NOT NULL
case object PrimaryKeyConstraint extends ColumnConstraint         // PRIMARY KEY
case object UniqueConstraint extends ColumnConstraint             // UNIQUE
case object AutoIncrementConstraint extends ColumnConstraint      // AUTO_INCREMENT
case class DefaultConstraint(value: Expression) extends ColumnConstraint  // DEFAULT expr
case class CheckColumnConstraint(condition: Expression) extends ColumnConstraint  // CHECK (expr)
case class ReferencesConstraint(                                  // REFERENCES table(col)
  refTable: String,
  refColumn: String
) extends ColumnConstraint

// 表级约束（在 CREATE TABLE 列定义后面）
sealed trait TableConstraint {
  def name: Option[String]  // 约束名称（CONSTRAINT name）
}

case class PrimaryKeyTableConstraint(
  name: Option[String],
  columns: List[String]
) extends TableConstraint   // PRIMARY KEY (col1, col2)

case class UniqueTableConstraint(
  name: Option[String],
  columns: List[String]
) extends TableConstraint   // UNIQUE (col1, col2)

case class ForeignKeyTableConstraint(
  name: Option[String],
  columns: List[String],
  refTable: String,
  refColumns: List[String]
) extends TableConstraint   // FOREIGN KEY (col) REFERENCES table(col)

case class CheckTableConstraint(
  name: Option[String],
  condition: Expression
) extends TableConstraint   // CHECK (condition)

// 列定义（增强版，支持约束）
case class ColumnDefinition(
  name: String,
  dataType: DataType,
  constraints: List[ColumnConstraint] = Nil
)

// 数据类型
sealed trait DataType
case class IntType(size: Option[Int] = None) extends DataType
case class BigIntType(size: Option[Int] = None) extends DataType
case class SmallIntType(size: Option[Int] = None) extends DataType
case class VarcharType(size: Int) extends DataType
case object TextType extends DataType
case object DateTimeType extends DataType
case object TimestampType extends DataType
case object BooleanType extends DataType
case object FloatType extends DataType
case object DoubleType extends DataType
case class DecimalDataType(precision: Option[Int] = None, scale: Option[Int] = None) extends DataType

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

// ============================================================
//  窗口函数 AST 定义
// ============================================================

// 窗口帧边界类型
sealed trait FrameBound
case object UnboundedPreceding extends FrameBound   // UNBOUNDED PRECEDING
case object CurrentRowBound extends FrameBound      // CURRENT ROW
case object UnboundedFollowing extends FrameBound   // UNBOUNDED FOLLOWING
case class PrecedingBound(n: Int) extends FrameBound  // N PRECEDING
case class FollowingBound(n: Int) extends FrameBound  // N FOLLOWING

// 窗口帧类型
sealed trait FrameType
case object RowsFrame extends FrameType   // ROWS
case object RangeFrame extends FrameType  // RANGE

// 窗口帧子句：ROWS/RANGE BETWEEN start AND end
case class WindowFrame(
  frameType: FrameType,
  start: FrameBound,
  end: Option[FrameBound]    // None 表示没有 BETWEEN，只有单个边界
)

// 窗口规格（OVER 子句内容）
case class WindowSpec(
  partitionBy: Option[List[Expression]] = None,
  orderBy: Option[List[OrderByClause]] = None,
  frame: Option[WindowFrame] = None
)

// 窗口函数表达式：func(...) OVER (...)
case class WindowFunctionExpression(
  function: Expression,   // 被调用的函数（AggregateFunction 或 FunctionCall，如 ROW_NUMBER()）
  windowSpec: WindowSpec
) extends Expression

// ============================================================
//  CTE (Common Table Expression) AST 定义
// ============================================================

// 单个 CTE 定义：name AS (query)
case class CTEDefinition(
  name: String,
  query: SQLStatement
)

// WITH 语句：WITH [RECURSIVE] cte1, cte2 ... SELECT ...
case class WithStatement(
  ctes: List[CTEDefinition],
  query: SQLStatement,       // 主查询（SELECT / UNION 等）
  recursive: Boolean = false
) extends SQLStatement

// ============================================================
//  视图 (View) AST 定义
// ============================================================

// CREATE [OR REPLACE] VIEW name AS query
case class CreateViewStatement(
  viewName: String,
  query: SQLStatement,
  orReplace: Boolean = false
) extends SQLStatement

// DROP VIEW [IF EXISTS] name
case class DropViewStatement(
  viewName: String,
  ifExists: Boolean = false
) extends SQLStatement

// ============================================================
//  存储过程 (Stored Procedure) AST 定义
// ============================================================

// 存储过程参数模式
sealed trait ParamMode
case object InParam extends ParamMode       // IN（默认）
case object OutParam extends ParamMode      // OUT
case object InOutParam extends ParamMode    // INOUT

// 存储过程参数定义
case class ProcedureParam(
  mode: ParamMode,
  name: String,
  dataType: DataType
)

// CREATE PROCEDURE name (params) BEGIN ... END
case class CreateProcedureStatement(
  name: String,
  params: List[ProcedureParam],
  body: List[SQLStatement]
) extends SQLStatement

// DROP PROCEDURE [IF EXISTS] name
case class DropProcedureStatement(
  name: String,
  ifExists: Boolean = false
) extends SQLStatement

// CALL procedure_name(args)
case class CallStatement(
  procedureName: String,
  arguments: List[Expression]
) extends SQLStatement
