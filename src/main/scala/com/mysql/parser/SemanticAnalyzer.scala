package com.mysql.parser

/**
 * 语义错误严重级别
 */
sealed trait Severity
case object SError extends Severity {
  override def toString: String = "ERROR"
}
case object SWarning extends Severity {
  override def toString: String = "WARNING"
}

/**
 * 语义错误
 *
 * @param message  错误描述
 * @param severity 严重级别（ERROR / WARNING）
 * @param category 错误分类（TABLE / COLUMN / GROUP_BY / ALIAS / INSERT / UNION / HAVING）
 */
case class SemanticError(
  message: String,
  severity: Severity = SError,
  category: String = "GENERAL"
)

/**
 * 查询作用域 — 当前语句中可用的表和列信息
 *
 * @param tables     别名/表名 → TableSchema 的映射
 * @param allColumns 所有可用列名 → 所属表名集合
 */
private[parser] case class QueryScope(
  tables: Map[String, TableSchema] = Map.empty,
  allColumns: Map[String, Set[String]] = Map.empty  // colName(upper) → Set[tableName(upper)]
) {
  /** 查找列所属的表（可能有多个同名列） */
  def findColumn(colName: String): Option[Set[String]] = {
    val key = colName.toUpperCase
    allColumns.get(key).filter(_.nonEmpty)
  }

  /** 判断某列是否可用 */
  def hasColumn(colName: String): Boolean =
    allColumns.contains(colName.toUpperCase)

  /** 判断某表/别名是否可用 */
  def hasTable(tableNameOrAlias: String): Boolean =
    tables.contains(tableNameOrAlias.toUpperCase)

  /** 通过别名/表名查找 TableSchema */
  def findTable(nameOrAlias: String): Option[TableSchema] =
    tables.get(nameOrAlias.toUpperCase)
}

/**
 * 语义分析器 — 在 AST 之上执行静态语义检查
 *
 * 检查项目:
 *   1. 表存在性检查
 *   2. 列存在性检查
 *   3. 限定列检查（table.column）
 *   4. 聚合函数与 GROUP BY 一致性
 *   5. 列别名唯一性检查
 *   6. HAVING 子句合法性
 *   7. INSERT 列数匹配
 *   8. UNION 列数一致性
 */
object SemanticAnalyzer {

  /**
   * 分析 SQL AST 的语义正确性
   *
   * @param ast    语法分析器生成的 AST
   * @param schema 数据库 Schema（表结构信息）
   * @return 语义错误列表（空列表表示语义正确）
   */
  def analyze(ast: SQLStatement, schema: DatabaseSchema): List[SemanticError] = {
    ast match {
      case s: SelectStatement  => analyzeSelect(s, schema)
      case i: InsertStatement  => analyzeInsert(i, schema)
      case u: UpdateStatement  => analyzeUpdate(u, schema)
      case d: DeleteStatement  => analyzeDelete(d, schema)
      case c: CreateTableStatement => analyzeCreateTable(c, schema)
      case dt: DropTableStatement  => analyzeDropTable(dt, schema)
      case un: UnionStatement  => analyzeUnion(un, schema)
    }
  }

  // ==================================
  //  SELECT 语句分析
  // ==================================

  private def analyzeSelect(stmt: SelectStatement, schema: DatabaseSchema): List[SemanticError] = {
    var errors = List[SemanticError]()

    // 1. 构建查询作用域：从 FROM / JOIN 提取可用的表和列
    val scope = buildScope(stmt.from, schema)
    errors = errors ++ scope._2  // 收集构建作用域时发现的错误
    val queryScope = scope._1

    // 2. 检查 SELECT 列引用
    errors = errors ++ checkColumns(stmt.columns, queryScope)

    // 3. 检查列别名唯一性
    errors = errors ++ checkAliasUniqueness(stmt.columns)

    // 4. 检查 WHERE 表达式中的列引用
    stmt.where.foreach { expr =>
      errors = errors ++ checkExpression(expr, queryScope)
    }

    // 5. 检查 GROUP BY 表达式中的列引用
    stmt.groupBy.foreach { groupByExprs =>
      groupByExprs.foreach { expr =>
        errors = errors ++ checkExpression(expr, queryScope)
      }
    }

    // 6. 检查聚合函数与 GROUP BY 的一致性
    if (stmt.groupBy.isDefined) {
      errors = errors ++ checkGroupByConsistency(stmt.columns, stmt.groupBy.get, queryScope)
    }

    // 7. 检查 HAVING 子句
    stmt.having.foreach { havingExpr =>
      errors = errors ++ checkExpression(havingExpr, queryScope)
      if (stmt.groupBy.isDefined) {
        errors = errors ++ checkHavingConsistency(havingExpr, stmt.groupBy.get)
      }
    }

    // 8. 检查 ORDER BY 表达式
    stmt.orderBy.foreach { orderByClauses =>
      orderByClauses.foreach { clause =>
        errors = errors ++ checkExpression(clause.expression, queryScope)
      }
    }

    errors
  }

  // ==================================
  //  INSERT 语句分析
  // ==================================

  private def analyzeInsert(stmt: InsertStatement, schema: DatabaseSchema): List[SemanticError] = {
    var errors = List[SemanticError]()

    // 1. 检查表存在性
    if (!schema.hasTable(stmt.table)) {
      errors = errors :+ SemanticError(
        s"Table '${stmt.table}' does not exist",
        SError, "TABLE"
      )
      return errors  // 表不存在则无法继续检查列
    }

    val table = schema.findTable(stmt.table).get

    // 2. 检查指定列是否存在于表中
    stmt.columns.foreach { cols =>
      cols.foreach { colName =>
        if (!table.hasColumn(colName)) {
          errors = errors :+ SemanticError(
            s"Column '${colName}' does not exist in table '${stmt.table}'",
            SError, "COLUMN"
          )
        }
      }
    }

    // 3. 检查列数与 VALUES 值数匹配
    stmt.columns.foreach { cols =>
      stmt.values.zipWithIndex.foreach { case (valueRow, rowIdx) =>
        if (cols.length != valueRow.length) {
          errors = errors :+ SemanticError(
            s"INSERT has ${cols.length} columns but VALUES row ${rowIdx + 1} has ${valueRow.length} values",
            SError, "INSERT"
          )
        }
      }
    }

    errors
  }

  // ==================================
  //  UPDATE 语句分析
  // ==================================

  private def analyzeUpdate(stmt: UpdateStatement, schema: DatabaseSchema): List[SemanticError] = {
    var errors = List[SemanticError]()

    // 1. 检查表存在性
    if (!schema.hasTable(stmt.table)) {
      errors = errors :+ SemanticError(
        s"Table '${stmt.table}' does not exist",
        SError, "TABLE"
      )
      return errors
    }

    val table = schema.findTable(stmt.table).get

    // 2. 检查 SET 赋值中的列是否存在
    stmt.assignments.foreach { case (colName, _) =>
      if (!table.hasColumn(colName)) {
        errors = errors :+ SemanticError(
          s"Column '${colName}' does not exist in table '${stmt.table}'",
          SError, "COLUMN"
        )
      }
    }

    // 3. 检查 WHERE 表达式中的列引用
    val queryScope = QueryScope(
      tables = Map(table.name.toUpperCase -> table),
      allColumns = table.columns.map(c => c.name.toUpperCase -> Set(table.name.toUpperCase)).toMap
    )
    stmt.where.foreach { expr =>
      errors = errors ++ checkExpression(expr, queryScope)
    }

    errors
  }

  // ==================================
  //  DELETE 语句分析
  // ==================================

  private def analyzeDelete(stmt: DeleteStatement, schema: DatabaseSchema): List[SemanticError] = {
    var errors = List[SemanticError]()

    // 1. 检查表存在性
    if (!schema.hasTable(stmt.table)) {
      errors = errors :+ SemanticError(
        s"Table '${stmt.table}' does not exist",
        SError, "TABLE"
      )
      return errors
    }

    val table = schema.findTable(stmt.table).get

    // 2. 检查 WHERE 表达式中的列引用
    val queryScope = QueryScope(
      tables = Map(table.name.toUpperCase -> table),
      allColumns = table.columns.map(c => c.name.toUpperCase -> Set(table.name.toUpperCase)).toMap
    )
    stmt.where.foreach { expr =>
      errors = errors ++ checkExpression(expr, queryScope)
    }

    errors
  }

  // ==================================
  //  CREATE TABLE 语句分析
  // ==================================

  private def analyzeCreateTable(stmt: CreateTableStatement, schema: DatabaseSchema): List[SemanticError] = {
    var errors = List[SemanticError]()

    // 检查表是否已存在
    if (schema.hasTable(stmt.tableName)) {
      errors = errors :+ SemanticError(
        s"Table '${stmt.tableName}' already exists",
        SError, "TABLE"
      )
    }

    // 检查列名是否重复
    val colNames = stmt.columns.map(_.name.toUpperCase)
    val duplicates = colNames.diff(colNames.distinct)
    duplicates.distinct.foreach { dup =>
      errors = errors :+ SemanticError(
        s"Duplicate column name '${dup}' in CREATE TABLE",
        SError, "COLUMN"
      )
    }

    errors
  }

  // ==================================
  //  DROP TABLE 语句分析
  // ==================================

  private def analyzeDropTable(stmt: DropTableStatement, schema: DatabaseSchema): List[SemanticError] = {
    if (!schema.hasTable(stmt.tableName)) {
      List(SemanticError(
        s"Table '${stmt.tableName}' does not exist",
        SError, "TABLE"
      ))
    } else {
      List.empty
    }
  }

  // ==================================
  //  UNION 语句分析
  // ==================================

  private def analyzeUnion(stmt: UnionStatement, schema: DatabaseSchema): List[SemanticError] = {
    var errors = List[SemanticError]()

    // 1. 分别分析左右两侧
    errors = errors ++ analyze(stmt.left, schema)
    errors = errors ++ analyzeSelect(stmt.right, schema)

    // 2. 检查两侧列数是否一致
    val leftCount = countColumns(stmt.left)
    val rightCount = stmt.right.columns.length

    if (leftCount > 0 && rightCount > 0 && leftCount != rightCount) {
      errors = errors :+ SemanticError(
        s"UNION queries must have the same number of columns (left: ${leftCount}, right: ${rightCount})",
        SError, "UNION"
      )
    }

    errors
  }

  /** 统计语句的列数 */
  private def countColumns(stmt: SQLStatement): Int = stmt match {
    case s: SelectStatement => s.columns.length
    case u: UnionStatement  => countColumns(u.left) // UNION 链最终以 SELECT 结尾
    case _ => 0
  }

  // ==================================
  //  作用域构建
  // ==================================

  /**
   * 从 FROM 子句构建查询作用域
   * @return (作用域, 构建过程中发现的错误)
   */
  private def buildScope(from: Option[TableReference], schema: DatabaseSchema): (QueryScope, List[SemanticError]) = {
    from match {
      case None =>
        // 无 FROM 子句（如 SELECT NOW()），返回空作用域
        (QueryScope(), List.empty)
      case Some(tableRef) =>
        buildScopeFromTableRef(tableRef, schema)
    }
  }

  private def buildScopeFromTableRef(tableRef: TableReference, schema: DatabaseSchema): (QueryScope, List[SemanticError]) = {
    tableRef match {
      case TableName(name, alias) =>
        schema.findTable(name) match {
          case Some(table) =>
            // 表存在，用别名或表名作为 key
            val key = alias.getOrElse(name).toUpperCase
            val tables = Map(key -> table)
            // 同时用原表名也注册（如果有别名的话）
            val tablesWithOriginal = if (alias.isDefined) {
              tables + (name.toUpperCase -> table)
            } else tables
            val allColumns = table.columns.map(c =>
              c.name.toUpperCase -> Set(key)
            ).toMap
            (QueryScope(tablesWithOriginal, allColumns), List.empty)
          case None =>
            (QueryScope(), List(SemanticError(
              s"Table '${name}' does not exist",
              SError, "TABLE"
            )))
        }

      case DerivedTable(query, alias) =>
        // 派生表：递归分析子查询，但不检查列引用（派生表的列来自子查询本身）
        // 为简化，我们为派生表创建一个"通用"表 Schema（列名为子查询的 SELECT 列名）
        val subErrors = analyze(query, schema)
        val derivedColumns = extractColumnNames(query)
        val derivedTable = TableSchema(
          name = alias.toUpperCase,
          columns = derivedColumns.map(c => ColumnSchema(c.toUpperCase))
        )
        val tables = Map(alias.toUpperCase -> derivedTable)
        val allColumns = derivedColumns.map(c =>
          c.toUpperCase -> Set(alias.toUpperCase)
        ).toMap
        (QueryScope(tables, allColumns), subErrors)

      case JoinClause(left, right, _, condition) =>
        // JOIN：合并左右两侧的作用域
        val (leftScope, leftErrors) = buildScopeFromTableRef(left, schema)
        val (rightScope, rightErrors) = buildScopeFromTableRef(right, schema)
        val mergedTables = leftScope.tables ++ rightScope.tables
        val mergedColumns = mergeColumnMaps(leftScope.allColumns, rightScope.allColumns)
        val combinedScope = QueryScope(mergedTables, mergedColumns)
        // 检查 ON 条件中的列引用
        val condErrors = checkExpression(condition, combinedScope)
        (combinedScope, leftErrors ++ rightErrors ++ condErrors)
    }
  }

  /** 合并两个列映射 */
  private def mergeColumnMaps(
    left: Map[String, Set[String]],
    right: Map[String, Set[String]]
  ): Map[String, Set[String]] = {
    val allKeys = left.keySet ++ right.keySet
    allKeys.map { key =>
      key -> (left.getOrElse(key, Set.empty) ++ right.getOrElse(key, Set.empty))
    }.toMap
  }

  /** 从 SELECT 语句中提取列名（用于派生表） */
  private def extractColumnNames(stmt: SQLStatement): List[String] = stmt match {
    case s: SelectStatement =>
      s.columns.flatMap {
        case AllColumns => List("*")
        case NamedColumn(name, alias) => List(alias.getOrElse(name))
        case QualifiedColumn(_, column, alias) => List(alias.getOrElse(column))
        case ExpressionColumn(_, alias) => alias.toList  // 表达式列需要别名
      }
    case u: UnionStatement => extractColumnNames(u.left)
    case _ => List.empty
  }

  // ==================================
  //  列引用检查
  // ==================================

  /** 检查 SELECT 列列表中的列引用 */
  private def checkColumns(columns: List[Column], scope: QueryScope): List[SemanticError] = {
    if (scope.tables.isEmpty) return List.empty  // 无 FROM 子句时不检查列

    columns.flatMap {
      case AllColumns => List.empty  // SELECT * 不需要检查具体列
      case NamedColumn(name, _) =>
        if (!scope.hasColumn(name)) {
          List(SemanticError(
            s"Column '${name}' does not exist in any table in scope",
            SError, "COLUMN"
          ))
        } else List.empty
      case QualifiedColumn(table, column, _) =>
        checkQualifiedColumn(table, column, scope)
      case ExpressionColumn(expr, _) =>
        checkExpression(expr, scope)
    }
  }

  /** 检查限定列引用 (table.column) */
  private def checkQualifiedColumn(table: String, column: String, scope: QueryScope): List[SemanticError] = {
    scope.findTable(table) match {
      case None =>
        List(SemanticError(
          s"Table or alias '${table}' is not defined in the current scope",
          SError, "TABLE"
        ))
      case Some(tableSchema) =>
        if (!tableSchema.hasColumn(column)) {
          List(SemanticError(
            s"Column '${column}' does not exist in table '${table}'",
            SError, "COLUMN"
          ))
        } else List.empty
    }
  }

  // ==================================
  //  表达式检查
  // ==================================

  /** 递归检查表达式中的列引用 */
  private def checkExpression(expr: Expression, scope: QueryScope): List[SemanticError] = {
    if (scope.tables.isEmpty) return List.empty  // 无 FROM 子句时不检查

    expr match {
      case Identifier(name) =>
        if (!scope.hasColumn(name)) {
          List(SemanticError(
            s"Column '${name}' does not exist in any table in scope",
            SError, "COLUMN"
          ))
        } else List.empty

      case QualifiedIdentifier(table, column) =>
        checkQualifiedColumn(table, column, scope)

      case BinaryExpression(left, _, right) =>
        checkExpression(left, scope) ++ checkExpression(right, scope)

      case UnaryExpression(_, expression) =>
        checkExpression(expression, scope)

      case IsNullExpression(expression, _) =>
        checkExpression(expression, scope)

      case BetweenExpression(expression, lower, upper, _) =>
        checkExpression(expression, scope) ++
          checkExpression(lower, scope) ++
          checkExpression(upper, scope)

      case InExpression(expression, values, _) =>
        checkExpression(expression, scope) ++
          values.flatMap(v => checkExpression(v, scope))

      case LikeExpression(expression, pattern, _) =>
        checkExpression(expression, scope) ++
          checkExpression(pattern, scope)

      case AggregateFunction(_, argument, _) =>
        checkExpression(argument, scope)

      case FunctionCall(_, arguments) =>
        arguments.flatMap(a => checkExpression(a, scope))

      case CaseExpression(operand, whenClauses, elseResult) =>
        operand.toList.flatMap(o => checkExpression(o, scope)) ++
          whenClauses.flatMap(wc =>
            checkExpression(wc.condition, scope) ++ checkExpression(wc.result, scope)
          ) ++
          elseResult.toList.flatMap(e => checkExpression(e, scope))

      case CastExpression(expression, _) =>
        checkExpression(expression, scope)

      case ConvertExpression(expression, _, _) =>
        checkExpression(expression, scope)

      case SubqueryExpression(_) => List.empty  // 子查询有自己的作用域
      case ExistsExpression(_, _) => List.empty
      case InSubqueryExpression(expression, _, _) =>
        checkExpression(expression, scope)

      // 字面量和特殊标记不需要检查
      case _: StringLiteral | _: NumberLiteral | NullLiteral | AllColumnsExpression =>
        List.empty
    }
  }

  // ==================================
  //  别名唯一性检查
  // ==================================

  /** 检查 SELECT 列别名是否重复 */
  private def checkAliasUniqueness(columns: List[Column]): List[SemanticError] = {
    val aliases = columns.flatMap {
      case NamedColumn(_, alias) => alias
      case QualifiedColumn(_, _, alias) => alias
      case ExpressionColumn(_, alias) => alias
      case _ => None
    }.map(_.toUpperCase)

    val duplicates = aliases.diff(aliases.distinct).distinct
    duplicates.map { dup =>
      SemanticError(
        s"Duplicate column alias '${dup}'",
        SWarning, "ALIAS"
      )
    }
  }

  // ==================================
  //  GROUP BY 一致性检查
  // ==================================

  /**
   * 检查 SELECT 中的非聚合列是否全部在 GROUP BY 中出现
   */
  private def checkGroupByConsistency(
    columns: List[Column],
    groupByExprs: List[Expression],
    scope: QueryScope
  ): List[SemanticError] = {
    // 提取 GROUP BY 中的列名集合
    val groupByColumnNames = groupByExprs.flatMap(extractIdentifiers).map(_.toUpperCase).toSet

    // 检查每个 SELECT 列
    columns.flatMap {
      case AllColumns => List.empty  // SELECT * 暂不检查
      case NamedColumn(name, _) =>
        if (!groupByColumnNames.contains(name.toUpperCase)) {
          List(SemanticError(
            s"Column '${name}' must appear in GROUP BY clause or be used in an aggregate function",
            SError, "GROUP_BY"
          ))
        } else List.empty
      case QualifiedColumn(table, column, _) =>
        // 检查 table.column 或 column 是否在 GROUP BY 中
        if (!groupByColumnNames.contains(column.toUpperCase) &&
            !groupByColumnNames.contains(s"${table}.${column}".toUpperCase)) {
          List(SemanticError(
            s"Column '${table}.${column}' must appear in GROUP BY clause or be used in an aggregate function",
            SError, "GROUP_BY"
          ))
        } else List.empty
      case ExpressionColumn(expr, _) =>
        // 表达式列：如果是聚合函数则 OK，否则其中的标识符必须在 GROUP BY 中
        if (!containsAggregateFunction(expr)) {
          val identifiers = extractIdentifiers(expr)
          identifiers.flatMap { id =>
            if (!groupByColumnNames.contains(id.toUpperCase)) {
              List(SemanticError(
                s"Column '${id}' must appear in GROUP BY clause or be used in an aggregate function",
                SError, "GROUP_BY"
              ))
            } else List.empty
          }
        } else List.empty
    }
  }

  // ==================================
  //  HAVING 一致性检查
  // ==================================

  /**
   * 检查 HAVING 中的非聚合列是否在 GROUP BY 中
   */
  private def checkHavingConsistency(
    havingExpr: Expression,
    groupByExprs: List[Expression]
  ): List[SemanticError] = {
    val groupByColumnNames = groupByExprs.flatMap(extractIdentifiers).map(_.toUpperCase).toSet
    val nonAggIdentifiers = extractNonAggregateIdentifiers(havingExpr)

    nonAggIdentifiers.flatMap { id =>
      if (!groupByColumnNames.contains(id.toUpperCase)) {
        List(SemanticError(
          s"Column '${id}' in HAVING clause must appear in GROUP BY clause or be used in an aggregate function",
          SError, "HAVING"
        ))
      } else List.empty
    }
  }

  // ==================================
  //  辅助方法
  // ==================================

  /** 从表达式中提取所有标识符名 */
  private def extractIdentifiers(expr: Expression): List[String] = expr match {
    case Identifier(name) => List(name)
    case QualifiedIdentifier(_, column) => List(column)
    case BinaryExpression(left, _, right) =>
      extractIdentifiers(left) ++ extractIdentifiers(right)
    case UnaryExpression(_, expression) =>
      extractIdentifiers(expression)
    case AggregateFunction(_, _, _) => List.empty  // 聚合函数内的标识符不算
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
  private def extractNonAggregateIdentifiers(expr: Expression): List[String] = expr match {
    case Identifier(name) => List(name)
    case QualifiedIdentifier(_, column) => List(column)
    case BinaryExpression(left, _, right) =>
      extractNonAggregateIdentifiers(left) ++ extractNonAggregateIdentifiers(right)
    case UnaryExpression(_, expression) =>
      extractNonAggregateIdentifiers(expression)
    case AggregateFunction(_, _, _) => List.empty  // 聚合函数完全跳过
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
  private def containsAggregateFunction(expr: Expression): Boolean = expr match {
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
