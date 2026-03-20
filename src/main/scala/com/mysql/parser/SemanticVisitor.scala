package com.mysql.parser

// ============================================================
//  基于 Visitor 模式的语义分析
//
//  利用 ASTVisitor[List[SemanticError]] 框架实现语义检查，
//  相比 SemanticAnalyzer 对象中的手写递归：
//    - 更模块化：每种检查可以独立为单独的 Visitor，按需组合
//    - 更可扩展：新增 AST 节点类型只需 override 对应 visit 方法
//    - 可插拔：可以选择性启用/禁用某类检查
//    - 遵循开放-封闭原则
//
//  架构：
//    SemanticVisitorPipeline（组合多个 Visitor 的管道）
//      ├── TableExistenceVisitor      — 表存在性检查
//      ├── ColumnExistenceVisitor     — 列存在性检查
//      ├── GroupByConsistencyVisitor  — GROUP BY 一致性检查
//      ├── AliasUniquenessVisitor     — 列别名唯一性检查
//      ├── InsertValidationVisitor    — INSERT 列数匹配检查
//      ├── UnionColumnCountVisitor    — UNION 列数一致性检查
//      └── DDLValidationVisitor       — CREATE/DROP TABLE 检查
// ============================================================


/**
 * 语义分析 Visitor 基类
 *
 * 所有语义检查 Visitor 的公共基类，提供：
 *   - R = List[SemanticError] 的 Monoid 定义
 *   - 数据库 Schema 引用
 *   - 通用辅助方法
 */
abstract class SemanticBaseVisitor(protected val schema: DatabaseSchema)
  extends ASTVisitor[List[SemanticError]] {

  def zero: List[SemanticError] = Nil
  def combine(a: List[SemanticError], b: List[SemanticError]): List[SemanticError] = a ++ b

  /** 便捷方法：创建 ERROR 级别的错误 */
  protected def error(message: String, category: String): List[SemanticError] =
    List(SemanticError(message, SError, category))

  /** 便捷方法：创建 WARNING 级别的错误 */
  protected def warning(message: String, category: String): List[SemanticError] =
    List(SemanticError(message, SWarning, category))
}


// ============================================================
//  1. 表存在性检查 Visitor
// ============================================================

/**
 * 检查 SQL 中引用的表是否在 Schema 中存在
 *
 * 覆盖场景：
 *   - FROM / JOIN 中的表引用
 *   - INSERT INTO 表名
 *   - UPDATE 表名
 *   - DELETE FROM 表名
 */
class TableExistenceVisitor(schema: DatabaseSchema)
  extends SemanticBaseVisitor(schema) {

  override def visitTableName(ref: TableName): List[SemanticError] = {
    if (!schema.hasTable(ref.name)) {
      error(s"Table '${ref.name}' does not exist", "TABLE")
    } else zero
  }

  override def visitInsertStatement(stmt: InsertStatement): List[SemanticError] = {
    val tableCheck = if (!schema.hasTable(stmt.table)) {
      error(s"Table '${stmt.table}' does not exist", "TABLE")
    } else zero
    combine(tableCheck, super.visitInsertStatement(stmt))
  }

  override def visitUpdateStatement(stmt: UpdateStatement): List[SemanticError] = {
    val tableCheck = if (!schema.hasTable(stmt.table)) {
      error(s"Table '${stmt.table}' does not exist", "TABLE")
    } else zero
    combine(tableCheck, super.visitUpdateStatement(stmt))
  }

  override def visitDeleteStatement(stmt: DeleteStatement): List[SemanticError] = {
    val tableCheck = if (!schema.hasTable(stmt.table)) {
      error(s"Table '${stmt.table}' does not exist", "TABLE")
    } else zero
    combine(tableCheck, super.visitDeleteStatement(stmt))
  }
}


// ============================================================
//  2. 列存在性检查 Visitor
// ============================================================

/**
 * 检查 SQL 中引用的列是否存在于可用的表中
 *
 * 需要先构建查询作用域（QueryScope），因此 SELECT 语句的列检查
 * 需要知道 FROM 子句引入了哪些表。
 *
 * 覆盖场景：
 *   - SELECT 列引用（NamedColumn, QualifiedColumn）
 *   - WHERE / ORDER BY / GROUP BY / HAVING 中的列引用
 *   - INSERT 列名检查
 *   - UPDATE SET 列名检查
 */
class ColumnExistenceVisitor(schema: DatabaseSchema)
  extends SemanticBaseVisitor(schema) {

  override def visitSelectStatement(stmt: SelectStatement): List[SemanticError] = {
    // 1. 构建查询作用域
    val (scope, scopeErrors) = ScopeBuilder.buildScope(stmt.from, schema)

    // 2. 检查 SELECT 列引用
    val colErrors = checkColumns(stmt.columns, scope)

    // 3. 检查 WHERE 表达式中的列引用
    val whereErrors = stmt.where.map(expr => checkExpression(expr, scope)).getOrElse(zero)

    // 4. 检查 GROUP BY 表达式中的列引用
    val groupByErrors = stmt.groupBy.map(exprs =>
      combineAll(exprs.map(expr => checkExpression(expr, scope)))
    ).getOrElse(zero)

    // 5. 检查 HAVING 子句
    val havingErrors = stmt.having.map(expr => checkExpression(expr, scope)).getOrElse(zero)

    // 6. 检查 ORDER BY 表达式
    val orderByErrors = stmt.orderBy.map(obs =>
      combineAll(obs.map(ob => checkExpression(ob.expression, scope)))
    ).getOrElse(zero)

    // 7. 检查 JOIN ON 条件中的列引用
    val joinOnErrors = stmt.from.map(ref => checkJoinConditions(ref, scope)).getOrElse(zero)

    combineAll(List(scopeErrors, colErrors, whereErrors, groupByErrors, havingErrors, orderByErrors, joinOnErrors))
  }

  /** 递归检查 JOIN ON 条件中的列引用 */
  private def checkJoinConditions(ref: TableReference, scope: QueryScope): List[SemanticError] = {
    ref match {
      case JoinClause(left, right, _, condition) =>
        val condErrors = checkExpression(condition, scope)
        val leftErrors = checkJoinConditions(left, scope)
        val rightErrors = checkJoinConditions(right, scope)
        combineAll(List(condErrors, leftErrors, rightErrors))
      case _ => zero
    }
  }

  override def visitInsertStatement(stmt: InsertStatement): List[SemanticError] = {
    if (!schema.hasTable(stmt.table)) return zero  // 表不存在时由 TableExistenceVisitor 报错

    val table = schema.findTable(stmt.table).get
    stmt.columns.map { cols =>
      combineAll(cols.map { colName =>
        if (!table.hasColumn(colName)) {
          error(s"Column '${colName}' does not exist in table '${stmt.table}'", "COLUMN")
        } else zero
      })
    }.getOrElse(zero)
  }

  override def visitUpdateStatement(stmt: UpdateStatement): List[SemanticError] = {
    if (!schema.hasTable(stmt.table)) return zero

    val table = schema.findTable(stmt.table).get

    // 检查 SET 赋值列
    val setErrors = combineAll(stmt.assignments.map { case (colName, _) =>
      if (!table.hasColumn(colName)) {
        error(s"Column '${colName}' does not exist in table '${stmt.table}'", "COLUMN")
      } else zero
    })

    // 检查 WHERE 中的列引用
    val scope = QueryScope(
      tables = Map(table.name.toUpperCase -> table),
      allColumns = table.columns.map(c => c.name.toUpperCase -> Set(table.name.toUpperCase)).toMap
    )
    val whereErrors = stmt.where.map(expr => checkExpression(expr, scope)).getOrElse(zero)

    combine(setErrors, whereErrors)
  }

  override def visitDeleteStatement(stmt: DeleteStatement): List[SemanticError] = {
    if (!schema.hasTable(stmt.table)) return zero

    val table = schema.findTable(stmt.table).get
    val scope = QueryScope(
      tables = Map(table.name.toUpperCase -> table),
      allColumns = table.columns.map(c => c.name.toUpperCase -> Set(table.name.toUpperCase)).toMap
    )
    stmt.where.map(expr => checkExpression(expr, scope)).getOrElse(zero)
  }

  // -- 私有辅助方法 --

  private def checkColumns(columns: List[Column], scope: QueryScope): List[SemanticError] = {
    if (scope.tables.isEmpty) return zero

    combineAll(columns.map {
      case AllColumns => zero
      case NamedColumn(name, _) =>
        if (!scope.hasColumn(name)) {
          error(s"Column '${name}' does not exist in any table in scope", "COLUMN")
        } else zero
      case QualifiedColumn(table, column, _) =>
        checkQualifiedColumn(table, column, scope)
      case ExpressionColumn(expr, _) =>
        checkExpression(expr, scope)
    })
  }

  private def checkQualifiedColumn(table: String, column: String, scope: QueryScope): List[SemanticError] = {
    scope.findTable(table) match {
      case None =>
        error(s"Table or alias '${table}' is not defined in the current scope", "TABLE")
      case Some(tableSchema) =>
        if (!tableSchema.hasColumn(column)) {
          error(s"Column '${column}' does not exist in table '${table}'", "COLUMN")
        } else zero
    }
  }

  private def checkExpression(expr: Expression, scope: QueryScope): List[SemanticError] = {
    if (scope.tables.isEmpty) return zero

    expr match {
      case Identifier(name) =>
        if (!scope.hasColumn(name)) {
          error(s"Column '${name}' does not exist in any table in scope", "COLUMN")
        } else zero
      case QualifiedIdentifier(table, column) =>
        checkQualifiedColumn(table, column, scope)
      case BinaryExpression(left, _, right) =>
        combine(checkExpression(left, scope), checkExpression(right, scope))
      case UnaryExpression(_, expression) =>
        checkExpression(expression, scope)
      case IsNullExpression(expression, _) =>
        checkExpression(expression, scope)
      case BetweenExpression(expression, lower, upper, _) =>
        combineAll(List(checkExpression(expression, scope),
          checkExpression(lower, scope), checkExpression(upper, scope)))
      case InExpression(expression, values, _) =>
        combine(checkExpression(expression, scope),
          combineAll(values.map(v => checkExpression(v, scope))))
      case LikeExpression(expression, pattern, _) =>
        combine(checkExpression(expression, scope), checkExpression(pattern, scope))
      case AggregateFunction(_, argument, _) =>
        checkExpression(argument, scope)
      case FunctionCall(_, arguments) =>
        combineAll(arguments.map(a => checkExpression(a, scope)))
      case CaseExpression(operand, whenClauses, elseResult) =>
        combineAll(
          operand.toList.map(o => checkExpression(o, scope)) ++
          whenClauses.flatMap(wc =>
            List(checkExpression(wc.condition, scope), checkExpression(wc.result, scope))
          ) ++
          elseResult.toList.map(e => checkExpression(e, scope))
        )
      case CastExpression(expression, _) =>
        checkExpression(expression, scope)
      case ConvertExpression(expression, _, _) =>
        checkExpression(expression, scope)
      case SubqueryExpression(_) => zero  // 子查询有自己的作用域
      case ExistsExpression(_, _) => zero
      case InSubqueryExpression(expression, _, _) =>
        checkExpression(expression, scope)
      case _: StringLiteral | _: NumberLiteral | NullLiteral | AllColumnsExpression => zero
    }
  }
}


// ============================================================
//  3. GROUP BY 一致性检查 Visitor
// ============================================================

/**
 * 检查 SELECT 中的非聚合列是否全部在 GROUP BY 中出现
 */
class GroupByConsistencyVisitor(schema: DatabaseSchema)
  extends SemanticBaseVisitor(schema) {

  override def visitSelectStatement(stmt: SelectStatement): List[SemanticError] = {
    // 同时递归检查子查询
    val fromErrors = stmt.from.map(visitTableReference).getOrElse(zero)

    val myErrors = stmt.groupBy match {
      case None => zero
      case Some(groupByExprs) =>
        val groupByColumnNames = groupByExprs.flatMap(ExpressionHelper.extractIdentifiers).map(_.toUpperCase).toSet

        combineAll(stmt.columns.map {
          case AllColumns => zero
          case NamedColumn(name, _) =>
            if (!groupByColumnNames.contains(name.toUpperCase)) {
              error(s"Column '${name}' must appear in GROUP BY clause or be used in an aggregate function", "GROUP_BY")
            } else zero
          case QualifiedColumn(table, column, _) =>
            if (!groupByColumnNames.contains(column.toUpperCase) &&
                !groupByColumnNames.contains(s"${table}.${column}".toUpperCase)) {
              error(s"Column '${table}.${column}' must appear in GROUP BY clause or be used in an aggregate function", "GROUP_BY")
            } else zero
          case ExpressionColumn(expr, _) =>
            if (!ExpressionHelper.containsAggregateFunction(expr)) {
              val identifiers = ExpressionHelper.extractIdentifiers(expr)
              combineAll(identifiers.map { id =>
                if (!groupByColumnNames.contains(id.toUpperCase)) {
                  error(s"Column '${id}' must appear in GROUP BY clause or be used in an aggregate function", "GROUP_BY")
                } else zero
              })
            } else zero
        })
    }

    combine(fromErrors, myErrors)
  }
}


// ============================================================
//  4. 列别名唯一性检查 Visitor
// ============================================================

/**
 * 检查 SELECT 列别名是否有重复
 */
class AliasUniquenessVisitor(schema: DatabaseSchema)
  extends SemanticBaseVisitor(schema) {

  override def visitSelectStatement(stmt: SelectStatement): List[SemanticError] = {
    // 同时递归检查子查询
    val fromErrors = stmt.from.map(visitTableReference).getOrElse(zero)

    val aliases = stmt.columns.flatMap {
      case NamedColumn(_, alias) => alias
      case QualifiedColumn(_, _, alias) => alias
      case ExpressionColumn(_, alias) => alias
      case _ => None
    }.map(_.toUpperCase)

    val duplicates = aliases.diff(aliases.distinct).distinct
    val myErrors = duplicates.map { dup =>
      SemanticError(s"Duplicate column alias '${dup}'", SWarning, "ALIAS")
    }

    combine(fromErrors, myErrors)
  }
}


// ============================================================
//  5. HAVING 一致性检查 Visitor
// ============================================================

/**
 * 检查 HAVING 子句中的非聚合列是否在 GROUP BY 中
 */
class HavingConsistencyVisitor(schema: DatabaseSchema)
  extends SemanticBaseVisitor(schema) {

  override def visitSelectStatement(stmt: SelectStatement): List[SemanticError] = {
    // 同时递归检查子查询
    val fromErrors = stmt.from.map(visitTableReference).getOrElse(zero)

    val myErrors = (stmt.having, stmt.groupBy) match {
      case (Some(havingExpr), Some(groupByExprs)) =>
        val groupByColumnNames = groupByExprs.flatMap(ExpressionHelper.extractIdentifiers).map(_.toUpperCase).toSet
        val nonAggIdentifiers = ExpressionHelper.extractNonAggregateIdentifiers(havingExpr)

        combineAll(nonAggIdentifiers.map { id =>
          if (!groupByColumnNames.contains(id.toUpperCase)) {
            error(s"Column '${id}' in HAVING clause must appear in GROUP BY clause or be used in an aggregate function", "HAVING")
          } else zero
        })
      case _ => zero
    }

    combine(fromErrors, myErrors)
  }
}


// ============================================================
//  6. INSERT 验证 Visitor
// ============================================================

/**
 * 检查 INSERT 语句的列数与 VALUES 值数是否匹配
 */
class InsertValidationVisitor(schema: DatabaseSchema)
  extends SemanticBaseVisitor(schema) {

  override def visitInsertStatement(stmt: InsertStatement): List[SemanticError] = {
    if (!schema.hasTable(stmt.table)) return zero

    stmt.columns.map { cols =>
      combineAll(stmt.values.zipWithIndex.map { case (valueRow, rowIdx) =>
        if (cols.length != valueRow.length) {
          error(
            s"INSERT has ${cols.length} columns but VALUES row ${rowIdx + 1} has ${valueRow.length} values",
            "INSERT"
          )
        } else zero
      })
    }.getOrElse(zero)
  }
}


// ============================================================
//  7. UNION 列数一致性检查 Visitor
// ============================================================

/**
 * 检查 UNION 两侧 SELECT 的列数是否一致
 */
class UnionColumnCountVisitor(schema: DatabaseSchema)
  extends SemanticBaseVisitor(schema) {

  override def visitUnionStatement(stmt: UnionStatement): List[SemanticError] = {
    // 递归检查左右子语句
    val leftErrors = visitStatement(stmt.left)
    val rightErrors = visitSelectStatement(stmt.right)

    val leftCount = countColumns(stmt.left)
    val rightCount = stmt.right.columns.length

    val myErrors = if (leftCount > 0 && rightCount > 0 && leftCount != rightCount) {
      error(
        s"UNION queries must have the same number of columns (left: ${leftCount}, right: ${rightCount})",
        "UNION"
      )
    } else zero

    combineAll(List(leftErrors, rightErrors, myErrors))
  }

  private def countColumns(stmt: SQLStatement): Int = stmt match {
    case s: SelectStatement => s.columns.length
    case u: UnionStatement  => countColumns(u.left)
    case _ => 0
  }
}


// ============================================================
//  8. DDL 验证 Visitor
// ============================================================

/**
 * 检查 CREATE TABLE 和 DROP TABLE 的语义
 *   - CREATE TABLE：表是否已存在 + 列名是否重复
 *   - DROP TABLE：表是否存在
 */
class DDLValidationVisitor(schema: DatabaseSchema)
  extends SemanticBaseVisitor(schema) {

  override def visitCreateTableStatement(stmt: CreateTableStatement): List[SemanticError] = {
    var errors = List[SemanticError]()

    // 检查表是否已存在
    if (schema.hasTable(stmt.tableName)) {
      errors = errors ++ error(s"Table '${stmt.tableName}' already exists", "TABLE")
    }

    // 检查列名重复
    val colNames = stmt.columns.map(_.name.toUpperCase)
    val duplicates = colNames.diff(colNames.distinct)
    duplicates.distinct.foreach { dup =>
      errors = errors ++ error(s"Duplicate column name '${dup}' in CREATE TABLE", "COLUMN")
    }

    errors
  }

  override def visitDropTableStatement(stmt: DropTableStatement): List[SemanticError] = {
    if (!schema.hasTable(stmt.tableName)) {
      error(s"Table '${stmt.tableName}' does not exist", "TABLE")
    } else zero
  }
}


// ============================================================
//  作用域构建辅助对象
// ============================================================

/**
 * 查询作用域构建器 — 从 FROM 子句提取可用的表和列
 */
object ScopeBuilder {

  def buildScope(from: Option[TableReference], schema: DatabaseSchema): (QueryScope, List[SemanticError]) = {
    from match {
      case None => (QueryScope(), List.empty)
      case Some(tableRef) => buildScopeFromTableRef(tableRef, schema)
    }
  }

  private def buildScopeFromTableRef(tableRef: TableReference, schema: DatabaseSchema): (QueryScope, List[SemanticError]) = {
    tableRef match {
      case TableName(name, alias) =>
        schema.findTable(name) match {
          case Some(table) =>
            val key = alias.getOrElse(name).toUpperCase
            val tables = Map(key -> table)
            val tablesWithOriginal = if (alias.isDefined) {
              tables + (name.toUpperCase -> table)
            } else tables
            val allColumns = table.columns.map(c =>
              c.name.toUpperCase -> Set(key)
            ).toMap
            (QueryScope(tablesWithOriginal, allColumns), List.empty)
          case None =>
            (QueryScope(), List.empty)  // 表不存在的错误由 TableExistenceVisitor 报告
        }

      case DerivedTable(query, alias) =>
        val derivedColumns = extractColumnNames(query)
        val derivedTable = TableSchema(
          name = alias.toUpperCase,
          columns = derivedColumns.map(c => ColumnSchema(c.toUpperCase))
        )
        val tables = Map(alias.toUpperCase -> derivedTable)
        val allColumns = derivedColumns.map(c =>
          c.toUpperCase -> Set(alias.toUpperCase)
        ).toMap
        (QueryScope(tables, allColumns), List.empty)

      case JoinClause(left, right, _, condition) =>
        val (leftScope, leftErrors) = buildScopeFromTableRef(left, schema)
        val (rightScope, rightErrors) = buildScopeFromTableRef(right, schema)
        val mergedTables = leftScope.tables ++ rightScope.tables
        val mergedColumns = mergeColumnMaps(leftScope.allColumns, rightScope.allColumns)
        (QueryScope(mergedTables, mergedColumns), leftErrors ++ rightErrors)
    }
  }

  private def mergeColumnMaps(
    left: Map[String, Set[String]],
    right: Map[String, Set[String]]
  ): Map[String, Set[String]] = {
    val allKeys = left.keySet ++ right.keySet
    allKeys.map { key =>
      key -> (left.getOrElse(key, Set.empty) ++ right.getOrElse(key, Set.empty))
    }.toMap
  }

  private def extractColumnNames(stmt: SQLStatement): List[String] = stmt match {
    case s: SelectStatement =>
      s.columns.flatMap {
        case AllColumns => List("*")
        case NamedColumn(name, alias) => List(alias.getOrElse(name))
        case QualifiedColumn(_, column, alias) => List(alias.getOrElse(column))
        case ExpressionColumn(_, alias) => alias.toList
      }
    case u: UnionStatement => extractColumnNames(u.left)
    case _ => List.empty
  }
}


// ============================================================
//  表达式辅助工具
// ============================================================

/**
 * 表达式辅助方法 — 提取标识符、判断是否含聚合函数等
 */
object ExpressionHelper {

  /** 从表达式中提取所有标识符名 */
  def extractIdentifiers(expr: Expression): List[String] = expr match {
    case Identifier(name) => List(name)
    case QualifiedIdentifier(_, column) => List(column)
    case BinaryExpression(left, _, right) =>
      extractIdentifiers(left) ++ extractIdentifiers(right)
    case UnaryExpression(_, expression) =>
      extractIdentifiers(expression)
    case AggregateFunction(_, _, _) => List.empty  // 聚合函数内的不算
    case FunctionCall(_, arguments) =>
      arguments.flatMap(extractIdentifiers)
    case CaseExpression(operand, whenClauses, elseResult) =>
      operand.toList.flatMap(extractIdentifiers) ++
        whenClauses.flatMap(wc =>
          extractIdentifiers(wc.condition) ++ extractIdentifiers(wc.result)
        ) ++
        elseResult.toList.flatMap(extractIdentifiers)
    case CastExpression(expression, _) => extractIdentifiers(expression)
    case ConvertExpression(expression, _, _) => extractIdentifiers(expression)
    case IsNullExpression(expression, _) => extractIdentifiers(expression)
    case BetweenExpression(expression, lower, upper, _) =>
      extractIdentifiers(expression) ++ extractIdentifiers(lower) ++ extractIdentifiers(upper)
    case InExpression(expression, values, _) =>
      extractIdentifiers(expression) ++ values.flatMap(extractIdentifiers)
    case LikeExpression(expression, pattern, _) =>
      extractIdentifiers(expression) ++ extractIdentifiers(pattern)
    case _ => List.empty
  }

  /** 从表达式中提取不在聚合函数内的标识符 */
  def extractNonAggregateIdentifiers(expr: Expression): List[String] = expr match {
    case Identifier(name) => List(name)
    case QualifiedIdentifier(_, column) => List(column)
    case BinaryExpression(left, _, right) =>
      extractNonAggregateIdentifiers(left) ++ extractNonAggregateIdentifiers(right)
    case UnaryExpression(_, expression) =>
      extractNonAggregateIdentifiers(expression)
    case AggregateFunction(_, _, _) => List.empty
    case FunctionCall(_, arguments) =>
      arguments.flatMap(extractNonAggregateIdentifiers)
    case CaseExpression(operand, whenClauses, elseResult) =>
      operand.toList.flatMap(extractNonAggregateIdentifiers) ++
        whenClauses.flatMap(wc =>
          extractNonAggregateIdentifiers(wc.condition) ++ extractNonAggregateIdentifiers(wc.result)
        ) ++
        elseResult.toList.flatMap(extractNonAggregateIdentifiers)
    case IsNullExpression(expression, _) => extractNonAggregateIdentifiers(expression)
    case BetweenExpression(expression, lower, upper, _) =>
      extractNonAggregateIdentifiers(expression) ++
        extractNonAggregateIdentifiers(lower) ++
        extractNonAggregateIdentifiers(upper)
    case InExpression(expression, values, _) =>
      extractNonAggregateIdentifiers(expression) ++ values.flatMap(extractNonAggregateIdentifiers)
    case LikeExpression(expression, pattern, _) =>
      extractNonAggregateIdentifiers(expression) ++ extractNonAggregateIdentifiers(pattern)
    case _ => List.empty
  }

  /** 判断表达式是否包含聚合函数 */
  def containsAggregateFunction(expr: Expression): Boolean = expr match {
    case _: AggregateFunction => true
    case BinaryExpression(left, _, right) =>
      containsAggregateFunction(left) || containsAggregateFunction(right)
    case UnaryExpression(_, expression) =>
      containsAggregateFunction(expression)
    case FunctionCall(_, arguments) =>
      arguments.exists(containsAggregateFunction)
    case CaseExpression(operand, whenClauses, elseResult) =>
      operand.exists(containsAggregateFunction) ||
        whenClauses.exists(wc =>
          containsAggregateFunction(wc.condition) || containsAggregateFunction(wc.result)
        ) ||
        elseResult.exists(containsAggregateFunction)
    case CastExpression(expression, _) => containsAggregateFunction(expression)
    case ConvertExpression(expression, _, _) => containsAggregateFunction(expression)
    case _ => false
  }
}


// ============================================================
//  语义分析管道 — 组合多个 Visitor
// ============================================================

/**
 * 语义分析 Visitor 管道
 *
 * 将多个独立的语义检查 Visitor 组合成一个管道，
 * 对 AST 执行一次遍历即可收集所有语义错误。
 *
 * 使用方式：
 * {{{
 *   val pipeline = new SemanticVisitorPipeline(schema)
 *   val errors = pipeline.analyze(ast)
 * }}}
 *
 * 也可以自定义检查项：
 * {{{
 *   val pipeline = new SemanticVisitorPipeline(schema,
 *     visitors = List(
 *       new TableExistenceVisitor(schema),
 *       new ColumnExistenceVisitor(schema)
 *     )
 *   )
 * }}}
 */
class SemanticVisitorPipeline(
  schema: DatabaseSchema,
  visitors: List[SemanticBaseVisitor] = Nil
) {
  /** 实际使用的 Visitor 列表 */
  private val activeVisitors: List[SemanticBaseVisitor] =
    if (visitors.nonEmpty) visitors
    else List(
      new TableExistenceVisitor(schema),
      new ColumnExistenceVisitor(schema),
      new GroupByConsistencyVisitor(schema),
      new AliasUniquenessVisitor(schema),
      new HavingConsistencyVisitor(schema),
      new InsertValidationVisitor(schema),
      new UnionColumnCountVisitor(schema),
      new DDLValidationVisitor(schema)
    )

  /**
   * 对 AST 执行全部语义检查
   *
   * @param ast SQL 语句的 AST
   * @return 语义错误列表（空列表表示语义正确）
   */
  def analyze(ast: SQLStatement): List[SemanticError] = {
    activeVisitors.flatMap(_.visitStatement(ast))
  }

  /**
   * 获取管道中的 Visitor 列表
   */
  def getVisitors: List[SemanticBaseVisitor] = activeVisitors

  /**
   * 创建一个只包含指定 Visitor 的子管道
   */
  def withOnly(visitorClasses: Class[_ <: SemanticBaseVisitor]*): SemanticVisitorPipeline = {
    val filtered = activeVisitors.filter(v => visitorClasses.exists(_.isInstance(v)))
    new SemanticVisitorPipeline(schema, filtered)
  }

  /**
   * 创建一个排除指定 Visitor 的子管道
   */
  def without(visitorClasses: Class[_ <: SemanticBaseVisitor]*): SemanticVisitorPipeline = {
    val filtered = activeVisitors.filterNot(v => visitorClasses.exists(_.isInstance(v)))
    new SemanticVisitorPipeline(schema, filtered)
  }
}
