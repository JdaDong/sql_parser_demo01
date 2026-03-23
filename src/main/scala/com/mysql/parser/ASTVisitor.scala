package com.mysql.parser

// ============================================================
//  AST Visitor 模式
//
//  提供两种核心 trait：
//    1. ASTVisitor[R]  — 只读遍历，返回泛型结果 R
//    2. ASTTransformer  — 变换遍历，返回新的 AST 节点
//
//  内置实现：
//    - TableExtractor      — 提取所有引用的表名
//    - ColumnExtractor     — 提取所有引用的列名
//    - SQLPrettyPrinter    — AST → 格式化 SQL 字符串
//    - TableRenamer        — 表名重命名变换
//    - ColumnRenamer       — 列名重命名变换
// ============================================================

/**
 * AST 只读遍历器 — 泛型 Visitor 接口
 *
 * 子类需要：
 *   1. 指定返回类型 R
 *   2. 实现 zero 和 combine（Monoid 接口）
 *   3. override 关注的 visit 方法
 *
 * 默认实现递归地深度优先遍历所有子节点，用 combine 合并结果。
 *
 * @tparam R 遍历结果类型
 */
trait ASTVisitor[R] {

  // ====== Monoid 接口 ======

  /** 零值（空结果） */
  def zero: R

  /** 合并两个结果 */
  def combine(a: R, b: R): R

  /** 合并多个结果 */
  def combineAll(rs: Iterable[R]): R = rs.foldLeft(zero)(combine)

  // ====== 语句级入口 ======

  def visitStatement(stmt: SQLStatement): R = stmt match {
    case s: SelectStatement      => visitSelectStatement(s)
    case i: InsertStatement      => visitInsertStatement(i)
    case u: UpdateStatement      => visitUpdateStatement(u)
    case d: DeleteStatement      => visitDeleteStatement(d)
    case c: CreateTableStatement => visitCreateTableStatement(c)
    case dt: DropTableStatement  => visitDropTableStatement(dt)
    case un: UnionStatement      => visitUnionStatement(un)
    case w: WithStatement        => visitWithStatement(w)
    case a: AlterTableStatement  => visitAlterTableStatement(a)
    case ci: CreateIndexStatement => visitCreateIndexStatement(ci)
    case di: DropIndexStatement  => visitDropIndexStatement(di)
    case cv: CreateViewStatement => visitCreateViewStatement(cv)
    case dv: DropViewStatement   => visitDropViewStatement(dv)
    case cp: CreateProcedureStatement => visitCreateProcedureStatement(cp)
    case dp: DropProcedureStatement   => visitDropProcedureStatement(dp)
    case cs: CallStatement       => visitCallStatement(cs)
  }

  def visitSelectStatement(stmt: SelectStatement): R = {
    val colResults = combineAll(stmt.columns.map(visitColumn))
    val fromResult = stmt.from.map(visitTableReference).getOrElse(zero)
    val whereResult = stmt.where.map(visitExpression).getOrElse(zero)
    val groupByResult = stmt.groupBy.map(exprs => combineAll(exprs.map(visitExpression))).getOrElse(zero)
    val havingResult = stmt.having.map(visitExpression).getOrElse(zero)
    val orderByResult = stmt.orderBy.map(obs => combineAll(obs.map(ob => visitExpression(ob.expression)))).getOrElse(zero)
    combineAll(List(colResults, fromResult, whereResult, groupByResult, havingResult, orderByResult))
  }

  def visitInsertStatement(stmt: InsertStatement): R = {
    val valResults = combineAll(stmt.values.flatMap(_.map(visitExpression)))
    valResults
  }

  def visitUpdateStatement(stmt: UpdateStatement): R = {
    val assignResults = combineAll(stmt.assignments.map { case (_, expr) => visitExpression(expr) })
    val whereResult = stmt.where.map(visitExpression).getOrElse(zero)
    combine(assignResults, whereResult)
  }

  def visitDeleteStatement(stmt: DeleteStatement): R = {
    stmt.where.map(visitExpression).getOrElse(zero)
  }

  def visitCreateTableStatement(stmt: CreateTableStatement): R = zero

  def visitDropTableStatement(stmt: DropTableStatement): R = zero

  def visitAlterTableStatement(stmt: AlterTableStatement): R = zero

  def visitCreateIndexStatement(stmt: CreateIndexStatement): R = zero

  def visitDropIndexStatement(stmt: DropIndexStatement): R = zero

  def visitCreateViewStatement(stmt: CreateViewStatement): R = visitStatement(stmt.query)

  def visitDropViewStatement(stmt: DropViewStatement): R = zero

  def visitCreateProcedureStatement(stmt: CreateProcedureStatement): R = {
    combineAll(stmt.body.map(visitStatement))
  }

  def visitDropProcedureStatement(stmt: DropProcedureStatement): R = zero

  def visitCallStatement(stmt: CallStatement): R = {
    combineAll(stmt.arguments.map(visitExpression))
  }

  def visitUnionStatement(stmt: UnionStatement): R = {
    combine(visitStatement(stmt.left), visitSelectStatement(stmt.right))
  }

  def visitWithStatement(stmt: WithStatement): R = {
    val cteResults = combineAll(stmt.ctes.map(cte => visitStatement(cte.query)))
    val queryResult = visitStatement(stmt.query)
    combine(cteResults, queryResult)
  }

  // ====== 列级 ======

  def visitColumn(col: Column): R = col match {
    case AllColumns              => visitAllColumns()
    case nc: NamedColumn        => visitNamedColumn(nc)
    case qc: QualifiedColumn    => visitQualifiedColumn(qc)
    case ec: ExpressionColumn   => visitExpressionColumn(ec)
  }

  def visitAllColumns(): R = zero

  def visitNamedColumn(col: NamedColumn): R = zero

  def visitQualifiedColumn(col: QualifiedColumn): R = zero

  def visitExpressionColumn(col: ExpressionColumn): R = visitExpression(col.expression)

  // ====== 表引用级 ======

  def visitTableReference(ref: TableReference): R = ref match {
    case tn: TableName    => visitTableName(tn)
    case dt: DerivedTable => visitDerivedTable(dt)
    case jc: JoinClause   => visitJoinClause(jc)
  }

  def visitTableName(ref: TableName): R = zero

  def visitDerivedTable(ref: DerivedTable): R = visitStatement(ref.query)

  def visitJoinClause(ref: JoinClause): R = {
    val leftResult = visitTableReference(ref.left)
    val rightResult = visitTableReference(ref.right)
    val condResult = visitExpression(ref.condition)
    combineAll(List(leftResult, rightResult, condResult))
  }

  // ====== 表达式级 ======

  def visitExpression(expr: Expression): R = expr match {
    case id: Identifier          => visitIdentifier(id)
    case qi: QualifiedIdentifier => visitQualifiedIdentifier(qi)
    case sl: StringLiteral       => visitStringLiteral(sl)
    case nl: NumberLiteral       => visitNumberLiteral(nl)
    case NullLiteral             => visitNullLiteral()
    case AllColumnsExpression    => visitAllColumnsExpression()
    case be: BinaryExpression    => visitBinaryExpression(be)
    case ue: UnaryExpression     => visitUnaryExpression(ue)
    case isn: IsNullExpression   => visitIsNullExpression(isn)
    case btw: BetweenExpression  => visitBetweenExpression(btw)
    case ine: InExpression       => visitInExpression(ine)
    case le: LikeExpression      => visitLikeExpression(le)
    case af: AggregateFunction   => visitAggregateFunction(af)
    case fc: FunctionCall        => visitFunctionCall(fc)
    case ce: CaseExpression      => visitCaseExpression(ce)
    case cast: CastExpression    => visitCastExpression(cast)
    case conv: ConvertExpression => visitConvertExpression(conv)
    case sq: SubqueryExpression  => visitSubqueryExpression(sq)
    case ex: ExistsExpression    => visitExistsExpression(ex)
    case isq: InSubqueryExpression => visitInSubqueryExpression(isq)
    case wf: WindowFunctionExpression => visitWindowFunctionExpression(wf)
  }

  def visitIdentifier(expr: Identifier): R = zero
  def visitQualifiedIdentifier(expr: QualifiedIdentifier): R = zero
  def visitStringLiteral(expr: StringLiteral): R = zero
  def visitNumberLiteral(expr: NumberLiteral): R = zero
  def visitNullLiteral(): R = zero
  def visitAllColumnsExpression(): R = zero

  def visitBinaryExpression(expr: BinaryExpression): R =
    combine(visitExpression(expr.left), visitExpression(expr.right))

  def visitUnaryExpression(expr: UnaryExpression): R =
    visitExpression(expr.expression)

  def visitIsNullExpression(expr: IsNullExpression): R =
    visitExpression(expr.expression)

  def visitBetweenExpression(expr: BetweenExpression): R =
    combineAll(List(visitExpression(expr.expression), visitExpression(expr.lower), visitExpression(expr.upper)))

  def visitInExpression(expr: InExpression): R =
    combine(visitExpression(expr.expression), combineAll(expr.values.map(visitExpression)))

  def visitLikeExpression(expr: LikeExpression): R =
    combine(visitExpression(expr.expression), visitExpression(expr.pattern))

  def visitAggregateFunction(expr: AggregateFunction): R =
    visitExpression(expr.argument)

  def visitFunctionCall(expr: FunctionCall): R =
    combineAll(expr.arguments.map(visitExpression))

  def visitCaseExpression(expr: CaseExpression): R = {
    val opResult = expr.operand.map(visitExpression).getOrElse(zero)
    val whenResults = combineAll(expr.whenClauses.flatMap(wc =>
      List(visitExpression(wc.condition), visitExpression(wc.result))
    ))
    val elseResult = expr.elseResult.map(visitExpression).getOrElse(zero)
    combineAll(List(opResult, whenResults, elseResult))
  }

  def visitCastExpression(expr: CastExpression): R =
    visitExpression(expr.expression)

  def visitConvertExpression(expr: ConvertExpression): R =
    visitExpression(expr.expression)

  def visitSubqueryExpression(expr: SubqueryExpression): R =
    visitStatement(expr.query)

  def visitExistsExpression(expr: ExistsExpression): R =
    visitStatement(expr.query)

  def visitInSubqueryExpression(expr: InSubqueryExpression): R =
    combine(visitExpression(expr.expression), visitStatement(expr.query))

  def visitWindowFunctionExpression(expr: WindowFunctionExpression): R = {
    val funcResult = visitExpression(expr.function)
    val partitionResult = expr.windowSpec.partitionBy
      .map(exprs => combineAll(exprs.map(visitExpression)))
      .getOrElse(zero)
    val orderByResult = expr.windowSpec.orderBy
      .map(obs => combineAll(obs.map(ob => visitExpression(ob.expression))))
      .getOrElse(zero)
    combineAll(List(funcResult, partitionResult, orderByResult))
  }
}


// ============================================================
//  ASTTransformer — AST 变换器
//
//  默认实现：递归深度优先变换，返回结构相同的新 AST。
//  子类只需 override 需要变换的方法。
// ============================================================

/**
 * AST 变换器 — 将 AST 变换为新的 AST
 *
 * 默认实现递归地对子节点做变换然后重建父节点。
 * 子类只需 override 关注的 transform 方法即可实现特定变换。
 */
trait ASTTransformer {

  // ====== 语句级 ======

  def transformStatement(stmt: SQLStatement): SQLStatement = stmt match {
    case s: SelectStatement      => transformSelectStatement(s)
    case i: InsertStatement      => transformInsertStatement(i)
    case u: UpdateStatement      => transformUpdateStatement(u)
    case d: DeleteStatement      => transformDeleteStatement(d)
    case c: CreateTableStatement => transformCreateTableStatement(c)
    case dt: DropTableStatement  => transformDropTableStatement(dt)
    case un: UnionStatement      => transformUnionStatement(un)
    case w: WithStatement        => transformWithStatement(w)
    case a: AlterTableStatement  => transformAlterTableStatement(a)
    case ci: CreateIndexStatement => transformCreateIndexStatement(ci)
    case di: DropIndexStatement  => transformDropIndexStatement(di)
    case cv: CreateViewStatement => transformCreateViewStatement(cv)
    case dv: DropViewStatement   => transformDropViewStatement(dv)
    case cp: CreateProcedureStatement => transformCreateProcedureStatement(cp)
    case dp: DropProcedureStatement   => transformDropProcedureStatement(dp)
    case cs: CallStatement       => transformCallStatement(cs)
  }

  def transformSelectStatement(stmt: SelectStatement): SelectStatement = {
    SelectStatement(
      columns = stmt.columns.map(transformColumn),
      from = stmt.from.map(transformTableReference),
      where = stmt.where.map(transformExpression),
      orderBy = stmt.orderBy.map(_.map(ob => OrderByClause(transformExpression(ob.expression), ob.ascending))),
      groupBy = stmt.groupBy.map(_.map(transformExpression)),
      having = stmt.having.map(transformExpression),
      limit = stmt.limit,
      offset = stmt.offset,
      distinct = stmt.distinct
    )
  }

  def transformInsertStatement(stmt: InsertStatement): InsertStatement = {
    InsertStatement(
      table = transformTableName(stmt.table),
      columns = stmt.columns.map(_.map(transformColumnName)),
      values = stmt.values.map(_.map(transformExpression))
    )
  }

  def transformUpdateStatement(stmt: UpdateStatement): UpdateStatement = {
    UpdateStatement(
      table = transformTableName(stmt.table),
      assignments = stmt.assignments.map { case (col, expr) =>
        (transformColumnName(col), transformExpression(expr))
      },
      where = stmt.where.map(transformExpression)
    )
  }

  def transformDeleteStatement(stmt: DeleteStatement): DeleteStatement = {
    DeleteStatement(
      table = transformTableName(stmt.table),
      where = stmt.where.map(transformExpression)
    )
  }

  def transformCreateTableStatement(stmt: CreateTableStatement): CreateTableStatement = {
    CreateTableStatement(
      tableName = transformTableName(stmt.tableName),
      columns = stmt.columns.map(transformColumnDefinition)
    )
  }

  def transformDropTableStatement(stmt: DropTableStatement): DropTableStatement = {
    DropTableStatement(
      tableName = transformTableName(stmt.tableName),
      ifExists = stmt.ifExists
    )
  }

  def transformAlterTableStatement(stmt: AlterTableStatement): AlterTableStatement = {
    AlterTableStatement(
      tableName = transformTableName(stmt.tableName),
      actions = stmt.actions.map(transformAlterAction)
    )
  }

  def transformAlterAction(action: AlterAction): AlterAction = action match {
    case AddColumnAction(col) => AddColumnAction(transformColumnDefinition(col))
    case DropColumnAction(name) => DropColumnAction(transformColumnName(name))
    case ModifyColumnAction(col) => ModifyColumnAction(transformColumnDefinition(col))
    case ChangeColumnAction(oldName, newCol) =>
      ChangeColumnAction(transformColumnName(oldName), transformColumnDefinition(newCol))
    case RenameTableAction(newName) => RenameTableAction(newName)
    case AddConstraintAction(constraint) => AddConstraintAction(constraint)
    case dc: DropConstraintAction => dc
  }

  def transformCreateIndexStatement(stmt: CreateIndexStatement): CreateIndexStatement = {
    CreateIndexStatement(
      indexName = stmt.indexName,
      tableName = transformTableName(stmt.tableName),
      columns = stmt.columns,
      unique = stmt.unique
    )
  }

  def transformDropIndexStatement(stmt: DropIndexStatement): DropIndexStatement = {
    DropIndexStatement(
      indexName = stmt.indexName,
      tableName = transformTableName(stmt.tableName)
    )
  }

  def transformUnionStatement(stmt: UnionStatement): UnionStatement = {
    UnionStatement(
      left = transformStatement(stmt.left),
      right = transformSelectStatement(stmt.right),
      unionType = stmt.unionType
    )
  }

  def transformWithStatement(stmt: WithStatement): WithStatement = {
    WithStatement(
      ctes = stmt.ctes.map(cte => CTEDefinition(cte.name, transformStatement(cte.query))),
      query = transformStatement(stmt.query),
      recursive = stmt.recursive
    )
  }

  def transformCreateViewStatement(stmt: CreateViewStatement): CreateViewStatement = {
    CreateViewStatement(stmt.viewName, transformStatement(stmt.query), stmt.orReplace)
  }

  def transformDropViewStatement(stmt: DropViewStatement): DropViewStatement = stmt

  def transformCreateProcedureStatement(stmt: CreateProcedureStatement): CreateProcedureStatement = {
    CreateProcedureStatement(stmt.name, stmt.params, stmt.body.map(transformStatement))
  }

  def transformDropProcedureStatement(stmt: DropProcedureStatement): DropProcedureStatement = stmt

  def transformCallStatement(stmt: CallStatement): CallStatement = {
    CallStatement(stmt.procedureName, stmt.arguments.map(transformExpression))
  }

  // ====== 列级 ======

  def transformColumn(col: Column): Column = col match {
    case AllColumns => AllColumns
    case NamedColumn(name, alias) =>
      NamedColumn(transformColumnName(name), alias)
    case QualifiedColumn(table, column, alias) =>
      QualifiedColumn(transformQualifier(table), transformColumnName(column), alias)
    case ExpressionColumn(expr, alias) =>
      ExpressionColumn(transformExpression(expr), alias)
  }

  def transformColumnDefinition(colDef: ColumnDefinition): ColumnDefinition = colDef

  // ====== 表引用级 ======

  def transformTableReference(ref: TableReference): TableReference = ref match {
    case TableName(name, alias) =>
      TableName(transformTableName(name), alias)
    case DerivedTable(query, alias) =>
      DerivedTable(transformStatement(query), alias)
    case JoinClause(left, right, joinType, condition) =>
      JoinClause(
        transformTableReference(left),
        transformTableReference(right),
        joinType,
        transformExpression(condition)
      )
  }

  // ====== 表达式级 ======

  def transformExpression(expr: Expression): Expression = expr match {
    case Identifier(name) =>
      Identifier(transformColumnName(name))
    case QualifiedIdentifier(table, column) =>
      QualifiedIdentifier(transformQualifier(table), transformColumnName(column))
    case sl: StringLiteral  => sl
    case nl: NumberLiteral  => nl
    case NullLiteral        => NullLiteral
    case AllColumnsExpression => AllColumnsExpression

    case BinaryExpression(left, op, right) =>
      BinaryExpression(transformExpression(left), op, transformExpression(right))
    case UnaryExpression(op, expression) =>
      UnaryExpression(op, transformExpression(expression))
    case IsNullExpression(expression, negated) =>
      IsNullExpression(transformExpression(expression), negated)
    case BetweenExpression(expression, lower, upper, negated) =>
      BetweenExpression(transformExpression(expression), transformExpression(lower), transformExpression(upper), negated)
    case InExpression(expression, values, negated) =>
      InExpression(transformExpression(expression), values.map(transformExpression), negated)
    case LikeExpression(expression, pattern, negated) =>
      LikeExpression(transformExpression(expression), transformExpression(pattern), negated)
    case AggregateFunction(funcType, argument, distinct) =>
      AggregateFunction(funcType, transformExpression(argument), distinct)
    case FunctionCall(name, arguments) =>
      FunctionCall(name, arguments.map(transformExpression))
    case CaseExpression(operand, whenClauses, elseResult) =>
      CaseExpression(
        operand.map(transformExpression),
        whenClauses.map(wc => WhenClause(transformExpression(wc.condition), transformExpression(wc.result))),
        elseResult.map(transformExpression)
      )
    case CastExpression(expression, targetType) =>
      CastExpression(transformExpression(expression), targetType)
    case ConvertExpression(expression, targetType, charset) =>
      ConvertExpression(transformExpression(expression), targetType, charset)
    case SubqueryExpression(query) =>
      SubqueryExpression(transformStatement(query))
    case ExistsExpression(query, negated) =>
      ExistsExpression(transformStatement(query), negated)
    case InSubqueryExpression(expression, query, negated) =>
      InSubqueryExpression(transformExpression(expression), transformStatement(query), negated)
    case WindowFunctionExpression(function, windowSpec) =>
      WindowFunctionExpression(
        transformExpression(function),
        WindowSpec(
          partitionBy = windowSpec.partitionBy.map(_.map(transformExpression)),
          orderBy = windowSpec.orderBy.map(_.map(ob => OrderByClause(transformExpression(ob.expression), ob.ascending))),
          frame = windowSpec.frame
        )
      )
  }

  // ====== 原子变换钩子（子类重写这些方法实现具体变换） ======

  /** 变换表名（用于 INSERT/UPDATE/DELETE/DROP 中的字符串表名） */
  def transformTableName(name: String): String = name

  /** 变换列名 */
  def transformColumnName(name: String): String = name

  /** 变换限定符（table.column 中的 table 部分） */
  def transformQualifier(qualifier: String): String = qualifier
}


// ============================================================
//  内置 Visitor 实现
// ============================================================

/**
 * 表名提取器 — 收集 SQL 中引用的所有表名
 *
 * 用途：权限检查、数据血缘分析、审计日志
 */
class TableExtractor extends ASTVisitor[List[String]] {
  def zero: List[String] = Nil
  def combine(a: List[String], b: List[String]): List[String] = a ++ b

  override def visitTableName(ref: TableName): List[String] = List(ref.name)

  // INSERT/UPDATE/DELETE 使用字符串表名，需要单独处理
  override def visitInsertStatement(stmt: InsertStatement): List[String] = {
    List(stmt.table) ++ super.visitInsertStatement(stmt)
  }

  override def visitUpdateStatement(stmt: UpdateStatement): List[String] = {
    List(stmt.table) ++ super.visitUpdateStatement(stmt)
  }

  override def visitDeleteStatement(stmt: DeleteStatement): List[String] = {
    List(stmt.table) ++ super.visitDeleteStatement(stmt)
  }

  override def visitCreateTableStatement(stmt: CreateTableStatement): List[String] = {
    List(stmt.tableName)
  }

  override def visitDropTableStatement(stmt: DropTableStatement): List[String] = {
    List(stmt.tableName)
  }

  override def visitAlterTableStatement(stmt: AlterTableStatement): List[String] = {
    List(stmt.tableName)
  }

  override def visitCreateIndexStatement(stmt: CreateIndexStatement): List[String] = {
    List(stmt.tableName)
  }

  override def visitDropIndexStatement(stmt: DropIndexStatement): List[String] = {
    List(stmt.tableName)
  }
}

/**
 * 列名提取器 — 收集 SQL 中引用的所有列名（不含表限定）
 *
 * 用途：依赖分析、列级权限检查
 */
class ColumnExtractor extends ASTVisitor[List[String]] {
  def zero: List[String] = Nil
  def combine(a: List[String], b: List[String]): List[String] = a ++ b

  override def visitNamedColumn(col: NamedColumn): List[String] = List(col.name)

  override def visitQualifiedColumn(col: QualifiedColumn): List[String] = List(col.column)

  override def visitIdentifier(expr: Identifier): List[String] = List(expr.name)

  override def visitQualifiedIdentifier(expr: QualifiedIdentifier): List[String] = List(expr.column)

  // INSERT 的列名列表
  override def visitInsertStatement(stmt: InsertStatement): List[String] = {
    val insertCols = stmt.columns.getOrElse(Nil)
    insertCols ++ super.visitInsertStatement(stmt)
  }

  // UPDATE 的 SET 赋值列名
  override def visitUpdateStatement(stmt: UpdateStatement): List[String] = {
    val setCols = stmt.assignments.map(_._1)
    setCols ++ super.visitUpdateStatement(stmt)
  }
}


/**
 * SQL 格式化器 — 将 AST 转换为格式规范的 SQL 字符串
 *
 * 特点：
 *   - 关键字大写
 *   - 缩进对齐
 *   - 每个子句独占一行
 */
class SQLPrettyPrinter extends ASTVisitor[String] {
  def zero: String = ""
  def combine(a: String, b: String): String = a + b

  private val INDENT = "  "

  // ====== 语句级 ======

  override def visitStatement(stmt: SQLStatement): String = stmt match {
    case s: SelectStatement      => visitSelectStatement(s)
    case i: InsertStatement      => visitInsertStatement(i)
    case u: UpdateStatement      => visitUpdateStatement(u)
    case d: DeleteStatement      => visitDeleteStatement(d)
    case c: CreateTableStatement => visitCreateTableStatement(c)
    case dt: DropTableStatement  => visitDropTableStatement(dt)
    case un: UnionStatement      => visitUnionStatement(un)
    case w: WithStatement        => visitWithStatement(w)
    case a: AlterTableStatement  => visitAlterTableStatement(a)
    case ci: CreateIndexStatement => visitCreateIndexStatement(ci)
    case di: DropIndexStatement  => visitDropIndexStatement(di)
    case cv: CreateViewStatement => visitCreateViewStatement(cv)
    case dv: DropViewStatement   => visitDropViewStatement(dv)
    case cp: CreateProcedureStatement => visitCreateProcedureStatement(cp)
    case dp: DropProcedureStatement   => visitDropProcedureStatement(dp)
    case cs: CallStatement       => visitCallStatement(cs)
  }

  override def visitSelectStatement(stmt: SelectStatement): String = {
    val parts = new scala.collection.mutable.ListBuffer[String]()

    // SELECT [DISTINCT]
    val selectKeyword = if (stmt.distinct) "SELECT DISTINCT" else "SELECT"
    val colStr = stmt.columns.map(formatColumn).mkString(s",\n$INDENT")
    parts += s"$selectKeyword\n$INDENT$colStr"

    // FROM
    stmt.from.foreach { ref =>
      parts += s"FROM\n$INDENT${formatTableReference(ref)}"
    }

    // WHERE
    stmt.where.foreach { expr =>
      parts += s"WHERE\n$INDENT${formatExpression(expr)}"
    }

    // GROUP BY
    stmt.groupBy.foreach { exprs =>
      val groupByStr = exprs.map(formatExpression).mkString(", ")
      parts += s"GROUP BY\n$INDENT$groupByStr"
    }

    // HAVING
    stmt.having.foreach { expr =>
      parts += s"HAVING\n$INDENT${formatExpression(expr)}"
    }

    // ORDER BY
    stmt.orderBy.foreach { clauses =>
      val orderByStr = clauses.map { ob =>
        s"${formatExpression(ob.expression)} ${if (ob.ascending) "ASC" else "DESC"}"
      }.mkString(", ")
      parts += s"ORDER BY\n$INDENT$orderByStr"
    }

    // LIMIT
    stmt.limit.foreach(l => parts += s"LIMIT $l")

    // OFFSET
    stmt.offset.foreach(o => parts += s"OFFSET $o")

    parts.mkString("\n")
  }

  override def visitInsertStatement(stmt: InsertStatement): String = {
    val parts = new scala.collection.mutable.ListBuffer[String]()

    parts += s"INSERT INTO ${stmt.table}"

    stmt.columns.foreach { cols =>
      parts += s"  (${cols.mkString(", ")})"
    }

    val valuesStr = stmt.values.map { row =>
      s"(${row.map(formatExpression).mkString(", ")})"
    }.mkString(", ")
    parts += s"VALUES\n$INDENT$valuesStr"

    parts.mkString("\n")
  }

  override def visitUpdateStatement(stmt: UpdateStatement): String = {
    val parts = new scala.collection.mutable.ListBuffer[String]()

    parts += s"UPDATE ${stmt.table}"

    val setStr = stmt.assignments.map { case (col, expr) =>
      s"$col = ${formatExpression(expr)}"
    }.mkString(s",\n$INDENT")
    parts += s"SET\n$INDENT$setStr"

    stmt.where.foreach { expr =>
      parts += s"WHERE\n$INDENT${formatExpression(expr)}"
    }

    parts.mkString("\n")
  }

  override def visitDeleteStatement(stmt: DeleteStatement): String = {
    val parts = new scala.collection.mutable.ListBuffer[String]()
    parts += s"DELETE FROM ${stmt.table}"

    stmt.where.foreach { expr =>
      parts += s"WHERE\n$INDENT${formatExpression(expr)}"
    }

    parts.mkString("\n")
  }

  override def visitCreateTableStatement(stmt: CreateTableStatement): String = {
    val items = new scala.collection.mutable.ListBuffer[String]()

    // 列定义
    stmt.columns.foreach { col =>
      val constraintStr = formatColumnConstraints(col.constraints)
      items += s"$INDENT${col.name} ${formatDataType(col.dataType)}$constraintStr"
    }

    // 表级约束
    stmt.constraints.foreach { tc =>
      items += s"$INDENT${formatTableConstraint(tc)}"
    }

    s"CREATE TABLE ${stmt.tableName} (\n${items.mkString(",\n")}\n)"
  }

  override def visitDropTableStatement(stmt: DropTableStatement): String = {
    val ifExistsStr = if (stmt.ifExists) " IF EXISTS" else ""
    s"DROP TABLE$ifExistsStr ${stmt.tableName}"
  }

  override def visitAlterTableStatement(stmt: AlterTableStatement): String = {
    val actionsStr = stmt.actions.map(formatAlterAction).mkString(",\n$INDENT")
    s"ALTER TABLE ${stmt.tableName}\n$INDENT$actionsStr"
  }

  override def visitCreateIndexStatement(stmt: CreateIndexStatement): String = {
    val uniqueStr = if (stmt.unique) "UNIQUE " else ""
    val colsStr = stmt.columns.map { ic =>
      s"${ic.name}${if (ic.ascending) "" else " DESC"}"
    }.mkString(", ")
    s"CREATE ${uniqueStr}INDEX ${stmt.indexName} ON ${stmt.tableName} ($colsStr)"
  }

  override def visitDropIndexStatement(stmt: DropIndexStatement): String = {
    s"DROP INDEX ${stmt.indexName} ON ${stmt.tableName}"
  }

  override def visitUnionStatement(stmt: UnionStatement): String = {
    val leftStr = visitStatement(stmt.left)
    val rightStr = visitSelectStatement(stmt.right)
    val unionKeyword = stmt.unionType match {
      case UnionAll          => "UNION ALL"
      case UnionDistinct     => "UNION"
      case IntersectAll      => "INTERSECT ALL"
      case IntersectDistinct => "INTERSECT"
      case ExceptAll         => "EXCEPT ALL"
      case ExceptDistinct    => "EXCEPT"
    }
    s"$leftStr\n$unionKeyword\n$rightStr"
  }

  override def visitWithStatement(stmt: WithStatement): String = {
    val parts = new scala.collection.mutable.ListBuffer[String]()

    // WITH [RECURSIVE]
    val withKeyword = if (stmt.recursive) "WITH RECURSIVE" else "WITH"

    // CTE 定义
    val cteStr = stmt.ctes.map { cte =>
      s"${cte.name} AS (\n${visitStatement(cte.query)}\n)"
    }.mkString(s",\n$INDENT")

    parts += s"$withKeyword\n$INDENT$cteStr"

    // 主查询
    parts += visitStatement(stmt.query)

    parts.mkString("\n")
  }

  override def visitCreateViewStatement(stmt: CreateViewStatement): String = {
    val replaceStr = if (stmt.orReplace) "OR REPLACE " else ""
    s"CREATE ${replaceStr}VIEW ${stmt.viewName} AS\n${visitStatement(stmt.query)}"
  }

  override def visitDropViewStatement(stmt: DropViewStatement): String = {
    val ifExistsStr = if (stmt.ifExists) " IF EXISTS" else ""
    s"DROP VIEW$ifExistsStr ${stmt.viewName}"
  }

  override def visitCreateProcedureStatement(stmt: CreateProcedureStatement): String = {
    val paramsStr = stmt.params.map { p =>
      val modeStr = p.mode match {
        case InParam    => "IN"
        case OutParam   => "OUT"
        case InOutParam => "INOUT"
      }
      s"$modeStr ${p.name} ${formatDataType(p.dataType)}"
    }.mkString(", ")
    val bodyStr = stmt.body.map(visitStatement).mkString(";\n  ")
    s"CREATE PROCEDURE ${stmt.name} ($paramsStr)\nBEGIN\n  $bodyStr;\nEND"
  }

  override def visitDropProcedureStatement(stmt: DropProcedureStatement): String = {
    val ifExistsStr = if (stmt.ifExists) " IF EXISTS" else ""
    s"DROP PROCEDURE$ifExistsStr ${stmt.name}"
  }

  override def visitCallStatement(stmt: CallStatement): String = {
    val argsStr = stmt.arguments.map(formatExpression).mkString(", ")
    s"CALL ${stmt.procedureName}($argsStr)"
  }

  // ====== 格式化辅助方法 ======

  private def formatColumn(col: Column): String = col match {
    case AllColumns => "*"
    case NamedColumn(name, alias) =>
      s"$name${alias.map(a => s" AS $a").getOrElse("")}"
    case QualifiedColumn(table, column, alias) =>
      s"$table.$column${alias.map(a => s" AS $a").getOrElse("")}"
    case ExpressionColumn(expr, alias) =>
      s"${formatExpression(expr)}${alias.map(a => s" AS $a").getOrElse("")}"
  }

  private def formatTableReference(ref: TableReference): String = ref match {
    case TableName(name, alias) =>
      s"$name${alias.map(a => s" $a").getOrElse("")}"
    case DerivedTable(query, alias) =>
      s"(\n${visitStatement(query)}\n) AS $alias"
    case JoinClause(left, right, joinType, condition) =>
      val joinStr = joinType match {
        case InnerJoin => "JOIN"
        case LeftJoin  => "LEFT JOIN"
        case RightJoin => "RIGHT JOIN"
      }
      s"${formatTableReference(left)}\n$INDENT$joinStr ${formatTableReference(right)} ON ${formatExpression(condition)}"
  }

  def formatExpression(expr: Expression): String = expr match {
    case Identifier(name) => name
    case QualifiedIdentifier(table, column) => s"$table.$column"
    case StringLiteral(value) => s"'$value'"
    case NumberLiteral(value) => value
    case NullLiteral => "NULL"
    case AllColumnsExpression => "*"

    case BinaryExpression(left, op, right) =>
      s"${formatExpression(left)} ${formatOperator(op)} ${formatExpression(right)}"
    case UnaryExpression(op, expression) =>
      s"${formatUnaryOperator(op)} ${formatExpression(expression)}"

    case IsNullExpression(expression, negated) =>
      s"${formatExpression(expression)} IS ${if (negated) "NOT " else ""}NULL"
    case BetweenExpression(expression, lower, upper, negated) =>
      s"${formatExpression(expression)} ${if (negated) "NOT " else ""}BETWEEN ${formatExpression(lower)} AND ${formatExpression(upper)}"
    case InExpression(expression, values, negated) =>
      s"${formatExpression(expression)} ${if (negated) "NOT " else ""}IN (${values.map(formatExpression).mkString(", ")})"
    case LikeExpression(expression, pattern, negated) =>
      s"${formatExpression(expression)} ${if (negated) "NOT " else ""}LIKE ${formatExpression(pattern)}"

    case AggregateFunction(funcType, argument, distinct) =>
      val funcName = formatAggregateType(funcType)
      val distinctStr = if (distinct) "DISTINCT " else ""
      s"$funcName($distinctStr${formatExpression(argument)})"

    case FunctionCall(name, arguments) =>
      s"$name(${arguments.map(formatExpression).mkString(", ")})"

    case CaseExpression(operand, whenClauses, elseResult) =>
      val opStr = operand.map(o => s" ${formatExpression(o)}").getOrElse("")
      val whenStr = whenClauses.map { wc =>
        s" WHEN ${formatExpression(wc.condition)} THEN ${formatExpression(wc.result)}"
      }.mkString
      val elseStr = elseResult.map(e => s" ELSE ${formatExpression(e)}").getOrElse("")
      s"CASE$opStr$whenStr$elseStr END"

    case CastExpression(expression, targetType) =>
      s"CAST(${formatExpression(expression)} AS ${formatCastType(targetType)})"
    case ConvertExpression(expression, targetType, charset) =>
      targetType match {
        case Some(t) => s"CONVERT(${formatExpression(expression)}, ${formatCastType(t)})"
        case None    => s"CONVERT(${formatExpression(expression)} USING ${charset.getOrElse("")})"
      }

    case SubqueryExpression(query) =>
      s"(${visitStatement(query)})"
    case ExistsExpression(query, negated) =>
      val notStr = if (negated) "NOT " else ""
      s"${notStr}EXISTS (${visitStatement(query)})"
    case InSubqueryExpression(expression, query, negated) =>
      val notStr = if (negated) "NOT " else ""
      s"${formatExpression(expression)} ${notStr}IN (${visitStatement(query)})"

    case WindowFunctionExpression(function, windowSpec) =>
      val funcStr = formatExpression(function)
      val overParts = new scala.collection.mutable.ListBuffer[String]()

      windowSpec.partitionBy.foreach { exprs =>
        overParts += s"PARTITION BY ${exprs.map(formatExpression).mkString(", ")}"
      }

      windowSpec.orderBy.foreach { obs =>
        val orderByStr = obs.map { ob =>
          s"${formatExpression(ob.expression)} ${if (ob.ascending) "ASC" else "DESC"}"
        }.mkString(", ")
        overParts += s"ORDER BY $orderByStr"
      }

      windowSpec.frame.foreach { frame =>
        val frameTypeStr = frame.frameType match {
          case RowsFrame => "ROWS"
          case RangeFrame => "RANGE"
        }
        val boundsStr = frame.end match {
          case Some(endBound) =>
            s"BETWEEN ${formatFrameBound(frame.start)} AND ${formatFrameBound(endBound)}"
          case None =>
            formatFrameBound(frame.start)
        }
        overParts += s"$frameTypeStr $boundsStr"
      }

      s"$funcStr OVER (${overParts.mkString(" ")})"
  }

  private def formatOperator(op: BinaryOperator): String = op match {
    case Equal        => "="
    case NotEqual     => "!="
    case LessThan     => "<"
    case GreaterThan  => ">"
    case LessEqual    => "<="
    case GreaterEqual => ">="
    case Plus         => "+"
    case Minus        => "-"
    case Multiply     => "*"
    case Divide       => "/"
    case AndOp        => "AND"
    case OrOp         => "OR"
  }

  private def formatUnaryOperator(op: UnaryOperator): String = op match {
    case NotOp => "NOT"
  }

  private def formatAggregateType(funcType: AggregateType): String = funcType match {
    case CountFunc => "COUNT"
    case SumFunc   => "SUM"
    case AvgFunc   => "AVG"
    case MaxFunc   => "MAX"
    case MinFunc   => "MIN"
  }

  private def formatDataType(dt: DataType): String = dt match {
    case IntType(Some(size)) => s"INT($size)"
    case IntType(None)       => "INT"
    case BigIntType(Some(size)) => s"BIGINT($size)"
    case BigIntType(None)       => "BIGINT"
    case SmallIntType(Some(size)) => s"SMALLINT($size)"
    case SmallIntType(None)       => "SMALLINT"
    case VarcharType(size)   => s"VARCHAR($size)"
    case TextType            => "TEXT"
    case DateTimeType        => "DATETIME"
    case TimestampType       => "TIMESTAMP"
    case BooleanType         => "BOOLEAN"
    case FloatType           => "FLOAT"
    case DoubleType          => "DOUBLE"
    case DecimalDataType(Some(p), Some(s)) => s"DECIMAL($p,$s)"
    case DecimalDataType(Some(p), None)    => s"DECIMAL($p)"
    case DecimalDataType(None, _)          => "DECIMAL"
  }

  private def formatColumnConstraints(constraints: List[ColumnConstraint]): String = {
    if (constraints.isEmpty) return ""
    val parts = constraints.map {
      case NotNullConstraint       => "NOT NULL"
      case PrimaryKeyConstraint    => "PRIMARY KEY"
      case UniqueConstraint        => "UNIQUE"
      case AutoIncrementConstraint => "AUTO_INCREMENT"
      case DefaultConstraint(v)    => s"DEFAULT ${formatExpression(v)}"
      case CheckColumnConstraint(c) => s"CHECK (${formatExpression(c)})"
      case ReferencesConstraint(refTable, refCol) => s"REFERENCES $refTable($refCol)"
    }
    " " + parts.mkString(" ")
  }

  private def formatTableConstraint(tc: TableConstraint): String = {
    val nameStr = tc.name.map(n => s"CONSTRAINT $n ").getOrElse("")
    tc match {
      case PrimaryKeyTableConstraint(_, cols) =>
        s"${nameStr}PRIMARY KEY (${cols.mkString(", ")})"
      case UniqueTableConstraint(_, cols) =>
        s"${nameStr}UNIQUE (${cols.mkString(", ")})"
      case ForeignKeyTableConstraint(_, cols, refTable, refCols) =>
        s"${nameStr}FOREIGN KEY (${cols.mkString(", ")}) REFERENCES $refTable(${refCols.mkString(", ")})"
      case CheckTableConstraint(_, condition) =>
        s"${nameStr}CHECK (${formatExpression(condition)})"
    }
  }

  private def formatAlterAction(action: AlterAction): String = action match {
    case AddColumnAction(col) =>
      val constraintStr = formatColumnConstraints(col.constraints)
      s"ADD COLUMN ${col.name} ${formatDataType(col.dataType)}$constraintStr"
    case DropColumnAction(name) =>
      s"DROP COLUMN $name"
    case ModifyColumnAction(col) =>
      val constraintStr = formatColumnConstraints(col.constraints)
      s"MODIFY COLUMN ${col.name} ${formatDataType(col.dataType)}$constraintStr"
    case ChangeColumnAction(oldName, newCol) =>
      val constraintStr = formatColumnConstraints(newCol.constraints)
      s"CHANGE COLUMN $oldName ${newCol.name} ${formatDataType(newCol.dataType)}$constraintStr"
    case RenameTableAction(newName) =>
      s"RENAME TO $newName"
    case AddConstraintAction(tc) =>
      s"ADD ${formatTableConstraint(tc)}"
    case DropConstraintAction(constraintType, name) =>
      name match {
        case Some(n) => s"DROP $constraintType $n"
        case None    => s"DROP $constraintType"
      }
  }

  private def formatCastType(castType: CastType): String = castType match {
    case SignedCastType(isInt)               => if (isInt) "SIGNED INT" else "SIGNED"
    case UnsignedCastType(isInt)             => if (isInt) "UNSIGNED INT" else "UNSIGNED"
    case CharCastType(Some(size))            => s"CHAR($size)"
    case CharCastType(None)                  => "CHAR"
    case VarcharCastType(size)               => s"VARCHAR($size)"
    case DecimalCastType(Some(p), Some(s))   => s"DECIMAL($p,$s)"
    case DecimalCastType(Some(p), None)      => s"DECIMAL($p)"
    case DecimalCastType(None, _)            => "DECIMAL"
    case DateCastType                        => "DATE"
    case DateTimeCastType                    => "DATETIME"
    case IntCastType                         => "INT"
    case BooleanCastType                     => "BOOLEAN"
  }

  private def formatFrameBound(bound: FrameBound): String = bound match {
    case UnboundedPreceding   => "UNBOUNDED PRECEDING"
    case UnboundedFollowing   => "UNBOUNDED FOLLOWING"
    case CurrentRowBound      => "CURRENT ROW"
    case PrecedingBound(n)    => s"$n PRECEDING"
    case FollowingBound(n)    => s"$n FOLLOWING"
  }
}


/**
 * 表名重命名变换器 — 按映射表重命名 AST 中的所有表名
 *
 * 用途：分表路由、多租户查询重写、Schema 迁移
 *
 * @param mapping 旧表名(大写) → 新表名 的映射
 */
class TableRenamer(mapping: Map[String, String]) extends ASTTransformer {

  override def transformTableName(name: String): String =
    mapping.getOrElse(name.toUpperCase, name)

  override def transformQualifier(qualifier: String): String =
    mapping.getOrElse(qualifier.toUpperCase, qualifier)
}


/**
 * 列名重命名变换器 — 按映射表重命名 AST 中的所有列名
 *
 * 用途：Schema 迁移、字段映射
 *
 * @param mapping 旧列名(大写) → 新列名 的映射
 */
class ColumnRenamer(mapping: Map[String, String]) extends ASTTransformer {

  override def transformColumnName(name: String): String =
    mapping.getOrElse(name.toUpperCase, name)
}
