package com.mysql.parser

import scala.collection.mutable

/** 执行引擎的类型别名和工具 */
object ExecutionTypes {
  /** 内存行（一行数据）— 列名(大写) → 值 */
  type Row = Map[String, Any]
}

import ExecutionTypes._

/**
 * 查询结果集
 *
 * @param columns 列名列表（保持输出顺序）
 * @param rows    结果行列表
 */
case class QueryResult(columns: List[String], rows: List[Row]) {
  /** 行数 */
  def rowCount: Int = rows.size

  /** 格式化表格输出 */
  def toTable: String = {
    if (columns.isEmpty) return "(empty result)"

    val header = columns
    val data = rows.map(row => columns.map(c => formatValue(row.getOrElse(c, null))))

    // 计算每列宽度
    val widths = header.indices.map { i =>
      val colWidth = header(i).length
      val dataWidth = if (data.nonEmpty) data.map(_(i).length).max else 0
      math.max(colWidth, dataWidth)
    }

    val sb = new StringBuilder
    val separator = widths.map("-" * _).mkString("+-", "-+-", "-+")

    sb.append(separator).append("\n")
    sb.append(header.zip(widths).map { case (h, w) => h.padTo(w, ' ') }.mkString("| ", " | ", " |")).append("\n")
    sb.append(separator).append("\n")
    data.foreach { row =>
      sb.append(row.zip(widths).map { case (v, w) => v.padTo(w, ' ') }.mkString("| ", " | ", " |")).append("\n")
    }
    sb.append(separator).append("\n")
    sb.append(s"${rows.size} row(s)")

    sb.toString()
  }

  private def formatValue(v: Any): String = v match {
    case null    => "NULL"
    case s: String => s
    case other   => other.toString
  }
}

/**
 * 内存表
 *
 * @param name    表名（大写）
 * @param schema  表 Schema
 * @param rows    行数据
 */
class InMemoryTable(val name: String, val schema: TableSchema, var rows: mutable.ListBuffer[Row] = mutable.ListBuffer.empty) {
  /** 插入一行 */
  def insert(row: Row): Unit = {
    rows += row
  }

  /** 删除满足条件的行，返回删除行数 */
  def delete(predicate: Row => Boolean): Int = {
    val before = rows.size
    rows = rows.filterNot(predicate)
    before - rows.size
  }

  /** 更新满足条件的行，返回更新行数 */
  def update(predicate: Row => Boolean, updates: Row => Row): Int = {
    var count = 0
    rows = rows.map { row =>
      if (predicate(row)) {
        count += 1
        updates(row)
      } else row
    }
    count
  }

  /** 获取所有行 */
  def allRows: List[Row] = rows.toList
}

/**
 * 内存数据库 — 管理多张内存表
 */
class InMemoryDatabase {
  private val tables = mutable.Map[String, InMemoryTable]()
  private val views = mutable.Map[String, SQLStatement]()
  private val procedures = mutable.Map[String, StoredProcedure]()

  /** 注册的存储过程 */
  case class StoredProcedure(name: String, params: List[ProcedureParam], body: List[SQLStatement])

  /** 获取数据库 Schema（用于语义分析） */
  def toSchema: DatabaseSchema = {
    DatabaseSchema(tables.map { case (name, table) => name -> table.schema }.toMap)
  }

  // ========== 表操作 ==========

  /** 创建表 */
  def createTable(stmt: CreateTableStatement): QueryResult = {
    val name = stmt.tableName.toUpperCase
    if (tables.contains(name)) {
      throw new ExecutionException(s"Table '$name' already exists")
    }
    val columns = stmt.columns.map { col =>
      ColumnSchema(col.name.toUpperCase, dataTypeToString(col.dataType), !col.constraints.contains(NotNullConstraint))
    }
    val tableSchema = TableSchema(name, columns)
    tables(name) = new InMemoryTable(name, tableSchema)
    QueryResult(List("Result"), List(Map("Result" -> s"Table '$name' created")))
  }

  /** 删除表 */
  def dropTable(stmt: DropTableStatement): QueryResult = {
    val name = stmt.tableName.toUpperCase
    if (!tables.contains(name)) {
      if (stmt.ifExists) {
        return QueryResult(List("Result"), List(Map("Result" -> s"Table '$name' does not exist (IF EXISTS)")))
      }
      throw new ExecutionException(s"Table '$name' does not exist")
    }
    tables.remove(name)
    QueryResult(List("Result"), List(Map("Result" -> s"Table '$name' dropped")))
  }

  /** 获取表 */
  def getTable(name: String): Option[InMemoryTable] = tables.get(name.toUpperCase)

  /** 判断表是否存在 */
  def hasTable(name: String): Boolean = tables.contains(name.toUpperCase)

  // ========== 视图操作 ==========

  /** 创建视图 */
  def createView(name: String, query: SQLStatement, orReplace: Boolean = false): QueryResult = {
    val upperName = name.toUpperCase
    if (views.contains(upperName) && !orReplace) {
      throw new ExecutionException(s"View '$upperName' already exists")
    }
    views(upperName) = query
    QueryResult(List("Result"), List(Map("Result" -> s"View '$upperName' created")))
  }

  /** 删除视图 */
  def dropView(name: String, ifExists: Boolean = false): QueryResult = {
    val upperName = name.toUpperCase
    if (!views.contains(upperName)) {
      if (ifExists) {
        return QueryResult(List("Result"), List(Map("Result" -> s"View '$upperName' does not exist (IF EXISTS)")))
      }
      throw new ExecutionException(s"View '$upperName' does not exist")
    }
    views.remove(upperName)
    QueryResult(List("Result"), List(Map("Result" -> s"View '$upperName' dropped")))
  }

  /** 获取视图定义 */
  def getView(name: String): Option[SQLStatement] = views.get(name.toUpperCase)

  /** 判断是否是视图 */
  def isView(name: String): Boolean = views.contains(name.toUpperCase)

  // ========== 存储过程操作 ==========

  /** 创建存储过程 */
  def createProcedure(name: String, params: List[ProcedureParam], body: List[SQLStatement]): QueryResult = {
    val upperName = name.toUpperCase
    if (procedures.contains(upperName)) {
      throw new ExecutionException(s"Procedure '$upperName' already exists")
    }
    procedures(upperName) = StoredProcedure(upperName, params, body)
    QueryResult(List("Result"), List(Map("Result" -> s"Procedure '$upperName' created")))
  }

  /** 获取存储过程 */
  def getProcedure(name: String): Option[StoredProcedure] = procedures.get(name.toUpperCase)

  /** 删除存储过程 */
  def dropProcedure(name: String, ifExists: Boolean = false): QueryResult = {
    val upperName = name.toUpperCase
    if (!procedures.contains(upperName)) {
      if (ifExists) {
        return QueryResult(List("Result"), List(Map("Result" -> s"Procedure '$upperName' does not exist (IF EXISTS)")))
      }
      throw new ExecutionException(s"Procedure '$upperName' does not exist")
    }
    procedures.remove(upperName)
    QueryResult(List("Result"), List(Map("Result" -> s"Procedure '$upperName' dropped")))
  }

  private def dataTypeToString(dt: DataType): String = dt match {
    case IntType(_)             => "INT"
    case BigIntType(_)          => "BIGINT"
    case SmallIntType(_)        => "SMALLINT"
    case VarcharType(_)         => "VARCHAR"
    case TextType               => "TEXT"
    case DateTimeType           => "DATETIME"
    case TimestampType          => "TIMESTAMP"
    case BooleanType            => "BOOLEAN"
    case FloatType              => "FLOAT"
    case DoubleType             => "DOUBLE"
    case DecimalDataType(_, _)  => "DECIMAL"
  }
}

/**
 * 执行异常
 */
class ExecutionException(message: String) extends RuntimeException(message)

/**
 * 表达式求值器 — 在给定行上下文中计算 Expression 的值
 */
object ExpressionEvaluator {

  /**
   * 求值表达式
   *
   * @param expr    AST 表达式
   * @param row     当前行数据（列名大写 → 值）
   * @param context 可选的额外上下文（如嵌套查询的外部行）
   * @return 计算结果
   */
  def evaluate(expr: Expression, row: Row, context: Row = Map.empty): Any = {
    val combinedRow = context ++ row  // row 优先级高于 context

    expr match {
      case Identifier(name) =>
        combinedRow.getOrElse(name.toUpperCase,
          throw new ExecutionException(s"Column '$name' not found in row"))

      case QualifiedIdentifier(table, column) =>
        // 尝试 TABLE.COLUMN 格式查找
        val key1 = s"${table.toUpperCase}.${column.toUpperCase}"
        val key2 = column.toUpperCase
        combinedRow.getOrElse(key1, combinedRow.getOrElse(key2,
          throw new ExecutionException(s"Column '${table}.${column}' not found in row")))

      case StringLiteral(value) => value
      case NumberLiteral(value) =>
        if (value.contains(".")) value.toDouble else value.toLong
      case NullLiteral => null

      case BinaryExpression(left, op, right) =>
        val lv = evaluate(left, row, context)
        val rv = evaluate(right, row, context)
        evaluateBinaryOp(op, lv, rv)

      case UnaryExpression(NotOp, expression) =>
        val v = evaluate(expression, row, context)
        v match {
          case b: Boolean => !b
          case null => null
          case _ => throw new ExecutionException(s"NOT requires boolean operand, got: $v")
        }

      case IsNullExpression(expression, negated) =>
        val v = evaluate(expression, row, context)
        if (negated) v != null else v == null

      case BetweenExpression(expression, lower, upper, negated) =>
        val v = evaluate(expression, row, context)
        val lo = evaluate(lower, row, context)
        val hi = evaluate(upper, row, context)
        if (v == null || lo == null || hi == null) null
        else {
          val result = compareValues(v, lo) >= 0 && compareValues(v, hi) <= 0
          if (negated) !result else result
        }

      case InExpression(expression, values, negated) =>
        val v = evaluate(expression, row, context)
        if (v == null) null
        else {
          val evalValues = values.map(evaluate(_, row, context))
          val result = evalValues.contains(v)
          if (negated) !result else result
        }

      case LikeExpression(expression, pattern, negated) =>
        val v = evaluate(expression, row, context)
        val p = evaluate(pattern, row, context)
        if (v == null || p == null) null
        else {
          val regex = p.toString.replace("%", ".*").replace("_", ".")
          val result = v.toString.matches(s"(?i)$regex")
          if (negated) !result else result
        }

      case AggregateFunction(_, _, _) =>
        // 聚合函数的值应由 QueryExecutor 在聚合阶段预先计算
        throw new ExecutionException("Aggregate functions should be pre-evaluated by QueryExecutor")

      case FunctionCall(name, arguments) =>
        val args = arguments.map(evaluate(_, row, context))
        evaluateFunction(name.toUpperCase, args)

      case CaseExpression(operand, whenClauses, elseResult) =>
        operand match {
          case Some(op) =>
            // 简单式 CASE
            val opVal = evaluate(op, row, context)
            val matched = whenClauses.find { wc =>
              val whenVal = evaluate(wc.condition, row, context)
              opVal == whenVal
            }
            matched match {
              case Some(wc) => evaluate(wc.result, row, context)
              case None     => elseResult.map(evaluate(_, row, context)).orNull
            }
          case None =>
            // 搜索式 CASE
            val matched = whenClauses.find { wc =>
              evaluate(wc.condition, row, context) == true
            }
            matched match {
              case Some(wc) => evaluate(wc.result, row, context)
              case None     => elseResult.map(evaluate(_, row, context)).orNull
            }
        }

      case CastExpression(expression, targetType) =>
        val v = evaluate(expression, row, context)
        castValue(v, targetType)

      case ConvertExpression(expression, targetType, _) =>
        val v = evaluate(expression, row, context)
        targetType.map(t => castValue(v, t)).getOrElse(v)

      case AllColumnsExpression => throw new ExecutionException("Cannot evaluate * expression directly")

      case SubqueryExpression(_) =>
        throw new ExecutionException("Subquery evaluation requires QueryExecutor context")

      case ExistsExpression(_, _) =>
        throw new ExecutionException("EXISTS evaluation requires QueryExecutor context")

      case InSubqueryExpression(_, _, _) =>
        throw new ExecutionException("IN subquery evaluation requires QueryExecutor context")

      case WindowFunctionExpression(_, _) =>
        throw new ExecutionException("Window function evaluation is not yet supported")
    }
  }

  /**
   * 二元运算求值
   */
  private def evaluateBinaryOp(op: BinaryOperator, left: Any, right: Any): Any = {
    // NULL 传播
    if (left == null || right == null) {
      op match {
        case AndOp =>
          // FALSE AND NULL = FALSE
          if (left == false || right == false) return false
          return null
        case OrOp =>
          // TRUE OR NULL = TRUE
          if (left == true || right == true) return true
          return null
        case _ => return null
      }
    }

    op match {
      case Equal        => left == right
      case NotEqual     => left != right
      case LessThan     => compareValues(left, right) < 0
      case GreaterThan  => compareValues(left, right) > 0
      case LessEqual    => compareValues(left, right) <= 0
      case GreaterEqual => compareValues(left, right) >= 0
      case Plus         => numericOp(left, right, _ + _, _ + _)
      case Minus        => numericOp(left, right, _ - _, _ - _)
      case Multiply     => numericOp(left, right, _ * _, _ * _)
      case Divide       =>
        val r = toDouble(right)
        if (r == 0) throw new ExecutionException("Division by zero")
        numericOp(left, right, _ / _, _ / _)
      case AndOp        => toBool(left) && toBool(right)
      case OrOp         => toBool(left) || toBool(right)
    }
  }

  /**
   * 比较两个值
   */
  def compareValues(a: Any, b: Any): Int = {
    (a, b) match {
      case (null, null) => 0
      case (null, _)    => -1
      case (_, null)    => 1
      case (a: Long, b: Long)       => a.compareTo(b)
      case (a: Double, b: Double)   => a.compareTo(b)
      case (a: Long, b: Double)     => a.toDouble.compareTo(b)
      case (a: Double, b: Long)     => a.compareTo(b.toDouble)
      case (a: Int, b: Int)         => a.compareTo(b)
      case (a: Int, b: Long)        => a.toLong.compareTo(b)
      case (a: Long, b: Int)        => a.compareTo(b.toLong)
      case (a: String, b: String)   => a.compareToIgnoreCase(b)
      case (a: Boolean, b: Boolean) => a.compareTo(b)
      case _ =>
        a.toString.compareTo(b.toString)
    }
  }

  private def numericOp(left: Any, right: Any, longOp: (Long, Long) => Long, doubleOp: (Double, Double) => Double): Any = {
    (left, right) match {
      case (a: Long, b: Long)     => longOp(a, b)
      case (a: Int, b: Int)       => longOp(a.toLong, b.toLong)
      case (a: Int, b: Long)      => longOp(a.toLong, b)
      case (a: Long, b: Int)      => longOp(a, b.toLong)
      case _ => doubleOp(toDouble(left), toDouble(right))
    }
  }

  private def toDouble(v: Any): Double = v match {
    case d: Double  => d
    case l: Long    => l.toDouble
    case i: Int     => i.toDouble
    case s: String  => s.toDouble
    case b: Boolean => if (b) 1.0 else 0.0
    case null       => 0.0
    case _          => v.toString.toDouble
  }

  private def toLong(v: Any): Long = v match {
    case l: Long    => l
    case i: Int     => i.toLong
    case d: Double  => d.toLong
    case s: String  => s.toLong
    case b: Boolean => if (b) 1L else 0L
    case null       => 0L
    case _          => v.toString.toLong
  }

  private def toBool(v: Any): Boolean = v match {
    case b: Boolean => b
    case l: Long    => l != 0
    case i: Int     => i != 0
    case d: Double  => d != 0
    case s: String  => s.nonEmpty && s != "0" && s.toLowerCase != "false"
    case null       => false
    case _          => true
  }

  /**
   * 类型转换
   */
  private def castValue(v: Any, targetType: CastType): Any = {
    if (v == null) return null
    targetType match {
      case SignedCastType(_) | IntCastType   => toLong(v)
      case UnsignedCastType(_)              => Math.abs(toLong(v))
      case CharCastType(_) | VarcharCastType(_) => v.toString
      case DecimalCastType(_, _)            => toDouble(v)
      case DateCastType | DateTimeCastType  => v.toString
      case BooleanCastType                  => toBool(v)
    }
  }

  /**
   * 内置函数求值
   */
  private def evaluateFunction(name: String, args: List[Any]): Any = name match {
    case "UPPER"  => if (args.head == null) null else args.head.toString.toUpperCase
    case "LOWER"  => if (args.head == null) null else args.head.toString.toLowerCase
    case "LENGTH" | "LEN" => if (args.head == null) null else args.head.toString.length.toLong
    case "TRIM"   => if (args.head == null) null else args.head.toString.trim
    case "LTRIM"  => if (args.head == null) null else args.head.toString.replaceAll("^\\s+", "")
    case "RTRIM"  => if (args.head == null) null else args.head.toString.replaceAll("\\s+$", "")

    case "CONCAT" =>
      if (args.exists(_ == null)) null
      else args.map(_.toString).mkString

    case "CONCAT_WS" =>
      if (args.isEmpty || args.head == null) null
      else {
        val separator = args.head.toString
        args.tail.filter(_ != null).map(_.toString).mkString(separator)
      }

    case "SUBSTRING" | "SUBSTR" =>
      if (args.head == null) null
      else {
        val str = args.head.toString
        val start = (toLong(args(1)) - 1).toInt.max(0)
        if (args.size > 2) {
          val len = toLong(args(2)).toInt
          str.substring(start, (start + len).min(str.length))
        } else {
          str.substring(start)
        }
      }

    case "REPLACE" =>
      if (args.head == null) null
      else args.head.toString.replace(args(1).toString, args(2).toString)

    case "IFNULL" | "COALESCE" =>
      args.find(_ != null).orNull

    case "IF" =>
      if (toBool(args.head)) args(1) else args(2)

    case "NULLIF" =>
      if (args.head == args(1)) null else args.head

    case "ABS" =>
      if (args.head == null) null
      else args.head match {
        case l: Long   => Math.abs(l)
        case d: Double => Math.abs(d)
        case i: Int    => Math.abs(i).toLong
        case _         => Math.abs(toDouble(args.head))
      }

    case "CEIL" | "CEILING" =>
      if (args.head == null) null else Math.ceil(toDouble(args.head)).toLong

    case "FLOOR" =>
      if (args.head == null) null else Math.floor(toDouble(args.head)).toLong

    case "ROUND" =>
      if (args.head == null) null
      else {
        val scale = if (args.size > 1) toLong(args(1)).toInt else 0
        BigDecimal(toDouble(args.head)).setScale(scale, BigDecimal.RoundingMode.HALF_UP).toDouble
      }

    case "MOD" =>
      if (args.head == null || args(1) == null) null
      else toLong(args.head) % toLong(args(1))

    case "NOW" | "CURRENT_TIMESTAMP" =>
      java.time.LocalDateTime.now().toString

    case "CURRENT_DATE" | "CURDATE" =>
      java.time.LocalDate.now().toString

    case _ =>
      throw new ExecutionException(s"Unknown function: $name")
  }
}

/**
 * SQL 查询执行器 — 在 InMemoryDatabase 上执行解析后的 AST
 */
class QueryExecutor(db: InMemoryDatabase) {

  /**
   * 执行任意 SQL 语句
   */
  def execute(stmt: SQLStatement): QueryResult = stmt match {
    case s: SelectStatement      => executeSelect(s)
    case i: InsertStatement      => executeInsert(i)
    case u: UpdateStatement      => executeUpdate(u)
    case d: DeleteStatement      => executeDelete(d)
    case c: CreateTableStatement => db.createTable(c)
    case dt: DropTableStatement  => db.dropTable(dt)
    case un: UnionStatement      => executeUnion(un)
    case w: WithStatement        => executeWith(w)
    case cv: CreateViewStatement => db.createView(cv.viewName, cv.query, cv.orReplace)
    case dv: DropViewStatement   => db.dropView(dv.viewName, dv.ifExists)
    case cp: CreateProcedureStatement => db.createProcedure(cp.name, cp.params, cp.body)
    case dp: DropProcedureStatement   => db.dropProcedure(dp.name, dp.ifExists)
    case cs: CallStatement       => executeCall(cs)
    case _: AlterTableStatement  => QueryResult(List("Result"), List(Map("Result" -> "ALTER TABLE executed (no-op in memory)")))
    case _: CreateIndexStatement => QueryResult(List("Result"), List(Map("Result" -> "CREATE INDEX executed (no-op in memory)")))
    case _: DropIndexStatement   => QueryResult(List("Result"), List(Map("Result" -> "DROP INDEX executed (no-op in memory)")))
  }

  // ============================================================
  //  SELECT 执行
  // ============================================================

  private def executeSelect(stmt: SelectStatement): QueryResult = {
    // 1. FROM — 获取基础行集
    val baseRows = stmt.from match {
      case Some(ref) => resolveTableReference(ref)
      case None      => List(Map.empty[String, Any]) // SELECT 1, SELECT NOW() 等
    }

    // 2. WHERE — 过滤
    val filteredRows = stmt.where match {
      case Some(whereExpr) =>
        baseRows.filter { row =>
          val result = ExpressionEvaluator.evaluate(whereExpr, row)
          result == true
        }
      case None => baseRows
    }

    // 3. GROUP BY — 分组 + 聚合
    val (groupedRows, outputColumns) = stmt.groupBy match {
      case Some(groupExprs) =>
        executeGroupBy(filteredRows, groupExprs, stmt.columns, stmt.having)
      case None =>
        // 检查是否有聚合函数（无 GROUP BY 但有聚合 = 全表聚合）
        if (hasAggregateInColumns(stmt.columns)) {
          executeGroupBy(filteredRows, Nil, stmt.columns, stmt.having)
        } else {
          // 普通查询：投影
          val (projected, cols) = projectColumns(filteredRows, stmt.columns)
          (projected, cols)
        }
    }

    // 4. HAVING（已在 executeGroupBy 中处理）

    // 5. DISTINCT
    val distinctRows = if (stmt.distinct) {
      groupedRows.distinct
    } else groupedRows

    // 6. ORDER BY
    val orderedRows = stmt.orderBy match {
      case Some(orderByClauses) => sortRows(distinctRows, orderByClauses)
      case None => distinctRows
    }

    // 7. LIMIT / OFFSET
    val offsetRows = stmt.offset match {
      case Some(off) => orderedRows.drop(off)
      case None => orderedRows
    }
    val limitedRows = stmt.limit match {
      case Some(lim) => offsetRows.take(lim)
      case None => offsetRows
    }

    QueryResult(outputColumns, limitedRows)
  }

  /**
   * 解析表引用 — 返回行列表
   */
  private def resolveTableReference(ref: TableReference): List[Row] = ref match {
    case TableName(name, alias) =>
      val upperName = name.toUpperCase
      // 先检查是否是视图
      db.getView(upperName) match {
        case Some(viewQuery) =>
          val viewResult = execute(viewQuery)
          val rows = viewResult.rows
          alias match {
            case Some(a) =>
              // 给视图结果的列加表别名前缀
              rows.map { row =>
                row ++ row.map { case (k, v) => s"${a.toUpperCase}.$k" -> v }
              }
            case None => rows
          }
        case None =>
          val table = db.getTable(upperName).getOrElse(
            throw new ExecutionException(s"Table '$name' does not exist"))
          val rows = table.allRows
          alias match {
            case Some(a) =>
              // 给列名加表别名前缀，同时保留原始列名
              rows.map { row =>
                row ++ row.map { case (k, v) => s"${a.toUpperCase}.$k" -> v }
              }
            case None =>
              // 给列名加表名前缀
              rows.map { row =>
                row ++ row.map { case (k, v) => s"${upperName}.$k" -> v }
              }
          }
      }

    case DerivedTable(query, alias) =>
      val result = execute(query)
      result.rows.map { row =>
        row ++ row.map { case (k, v) => s"${alias.toUpperCase}.$k" -> v }
      }

    case JoinClause(left, right, joinType, condition) =>
      executeJoin(left, right, joinType, condition)
  }

  /**
   * 执行 JOIN
   */
  private def executeJoin(left: TableReference, right: TableReference, joinType: JoinType, condition: Expression): List[Row] = {
    val leftRows = resolveTableReference(left)
    val rightRows = resolveTableReference(right)

    joinType match {
      case InnerJoin =>
        for {
          lr <- leftRows
          rr <- rightRows
          combined = lr ++ rr
          if ExpressionEvaluator.evaluate(condition, combined) == true
        } yield combined

      case LeftJoin =>
        leftRows.flatMap { lr =>
          val matches = rightRows.filter { rr =>
            ExpressionEvaluator.evaluate(condition, lr ++ rr) == true
          }
          if (matches.isEmpty) {
            List(lr) // 左表行保留，右表列为 NULL（不在 row 中即 NULL）
          } else {
            matches.map(rr => lr ++ rr)
          }
        }

      case RightJoin =>
        rightRows.flatMap { rr =>
          val matches = leftRows.filter { lr =>
            ExpressionEvaluator.evaluate(condition, lr ++ rr) == true
          }
          if (matches.isEmpty) {
            List(rr)
          } else {
            matches.map(lr => lr ++ rr)
          }
        }
    }
  }

  /**
   * 投影列 — 从行中提取指定列
   */
  private def projectColumns(rows: List[Row], columns: List[Column]): (List[Row], List[String]) = {
    if (columns == List(AllColumns)) {
      // SELECT * — 返回所有不含 '.' 的列
      if (rows.isEmpty) return (Nil, Nil)
      val allCols = rows.head.keys.filter(!_.contains(".")).toList.sorted
      val projectedRows = rows.map(row => allCols.map(c => c -> row.getOrElse(c, null)).toMap)
      (projectedRows, allCols)
    } else {
      val outputCols = columns.map(columnToName)
      val projectedRows = rows.map { row =>
        columns.map { col =>
          val name = columnToName(col)
          val value = evaluateColumn(col, row)
          name -> value
        }.toMap
      }
      (projectedRows, outputCols)
    }
  }

  /**
   * 获取列的输出名称
   */
  private def columnToName(col: Column): String = col match {
    case AllColumns                             => "*"
    case NamedColumn(name, alias)              => alias.getOrElse(name).toUpperCase
    case QualifiedColumn(_, column, alias)     => alias.getOrElse(column).toUpperCase
    case ExpressionColumn(_, alias)            => alias.map(_.toUpperCase).getOrElse(expressionToName(col.asInstanceOf[ExpressionColumn].expression))
  }

  /**
   * 表达式转输出列名
   */
  private def expressionToName(expr: Expression): String = expr match {
    case Identifier(name) => name.toUpperCase
    case QualifiedIdentifier(t, c) => s"${t.toUpperCase}.${c.toUpperCase}"
    case AggregateFunction(ft, arg, distinct) =>
      val funcName = ft match {
        case CountFunc => "COUNT"
        case SumFunc   => "SUM"
        case AvgFunc   => "AVG"
        case MaxFunc   => "MAX"
        case MinFunc   => "MIN"
      }
      val distStr = if (distinct) "DISTINCT " else ""
      s"$funcName($distStr${expressionToName(arg)})"
    case FunctionCall(name, _) => name
    case AllColumnsExpression  => "*"
    case _ => expr.toString
  }

  /**
   * 求值列
   */
  private def evaluateColumn(col: Column, row: Row): Any = col match {
    case AllColumns => throw new ExecutionException("Cannot evaluate AllColumns")
    case NamedColumn(name, _) =>
      row.getOrElse(name.toUpperCase,
        throw new ExecutionException(s"Column '$name' not found"))
    case QualifiedColumn(table, column, _) =>
      val key1 = s"${table.toUpperCase}.${column.toUpperCase}"
      val key2 = column.toUpperCase
      row.getOrElse(key1, row.getOrElse(key2,
        throw new ExecutionException(s"Column '${table}.${column}' not found")))
    case ExpressionColumn(expr, _) =>
      ExpressionEvaluator.evaluate(expr, row)
  }

  // ============================================================
  //  GROUP BY + 聚合
  // ============================================================

  private def executeGroupBy(rows: List[Row], groupExprs: List[Expression], columns: List[Column], having: Option[Expression]): (List[Row], List[String]) = {
    // 分组
    val groups: List[(List[Any], List[Row])] = if (groupExprs.isEmpty) {
      // 全表聚合（无 GROUP BY 但有聚合函数）
      List((Nil, rows))
    } else {
      rows.groupBy { row =>
        groupExprs.map(ExpressionEvaluator.evaluate(_, row))
      }.toList
    }

    // 计算输出列名
    val outputCols = columns.map(columnToName)

    // 对每个组计算输出行
    val resultRows = groups.flatMap { case (_, groupRows) =>
      val resultRow = columns.map { col =>
        val name = columnToName(col)
        val value = evaluateColumnWithAgg(col, groupRows)
        name -> value
      }.toMap

      // HAVING 过滤
      having match {
        case Some(havingExpr) =>
          // 为 HAVING 创建包含聚合结果的上下文行
          val aggContext = resultRow ++ createAggContext(groupRows)
          val havingResult = evaluateWithAggContext(havingExpr, groupRows, aggContext)
          if (havingResult == true) Some(resultRow) else None
        case None =>
          Some(resultRow)
      }
    }

    (resultRows, outputCols)
  }

  /**
   * 带聚合的列求值
   */
  private def evaluateColumnWithAgg(col: Column, groupRows: List[Row]): Any = col match {
    case AllColumns => throw new ExecutionException("Cannot use * with GROUP BY")
    case NamedColumn(name, _) =>
      // GROUP BY 列 — 取组内第一行的值
      groupRows.headOption.flatMap(_.get(name.toUpperCase)).orNull
    case QualifiedColumn(table, column, _) =>
      val key1 = s"${table.toUpperCase}.${column.toUpperCase}"
      val key2 = column.toUpperCase
      groupRows.headOption.flatMap(r => r.get(key1).orElse(r.get(key2))).orNull
    case ExpressionColumn(expr, _) =>
      evaluateAggExpression(expr, groupRows)
  }

  /**
   * 求值可能包含聚合函数的表达式
   */
  private def evaluateAggExpression(expr: Expression, groupRows: List[Row]): Any = expr match {
    case AggregateFunction(funcType, argument, distinct) =>
      computeAggregate(funcType, argument, distinct, groupRows)
    case BinaryExpression(left, op, right) =>
      val lv = evaluateAggExpression(left, groupRows)
      val rv = evaluateAggExpression(right, groupRows)
      ExpressionEvaluator.evaluate(BinaryExpression(NumberLiteral("0"), op, NumberLiteral("0")), Map.empty) match {
        case _ =>
          // 重新求值
          val leftExpr = anyToExpression(lv)
          val rightExpr = anyToExpression(rv)
          ExpressionEvaluator.evaluate(BinaryExpression(leftExpr, op, rightExpr), Map.empty)
      }
    case _ =>
      // 非聚合表达式 — 取第一行的值
      groupRows.headOption.map(ExpressionEvaluator.evaluate(expr, _)).orNull
  }

  /**
   * 将值转为字面量表达式（用于二次求值）
   */
  private def anyToExpression(v: Any): Expression = v match {
    case null      => NullLiteral
    case s: String => StringLiteral(s)
    case l: Long   => NumberLiteral(l.toString)
    case i: Int    => NumberLiteral(i.toString)
    case d: Double => NumberLiteral(d.toString)
    case b: Boolean => if (b) NumberLiteral("1") else NumberLiteral("0")
    case _         => StringLiteral(v.toString)
  }

  /**
   * 计算聚合函数
   */
  private def computeAggregate(funcType: AggregateType, argument: Expression, distinct: Boolean, rows: List[Row]): Any = {
    if (argument == AllColumnsExpression) {
      // COUNT(*)
      return rows.size.toLong
    }

    var values = rows.map(row => ExpressionEvaluator.evaluate(argument, row))
    val nonNullValues = values.filter(_ != null)

    if (distinct) {
      val uniqueValues = nonNullValues.distinct
      return computeAggOnValues(funcType, uniqueValues)
    }

    computeAggOnValues(funcType, nonNullValues)
  }

  private def computeAggOnValues(funcType: AggregateType, values: List[Any]): Any = funcType match {
    case CountFunc => values.size.toLong

    case SumFunc =>
      if (values.isEmpty) null
      else values.map(toNumeric).sum

    case AvgFunc =>
      if (values.isEmpty) null
      else values.map(toNumeric).sum / values.size

    case MaxFunc =>
      if (values.isEmpty) null
      else values.reduce((a, b) => if (ExpressionEvaluator.compareValues(a, b) >= 0) a else b)

    case MinFunc =>
      if (values.isEmpty) null
      else values.reduce((a, b) => if (ExpressionEvaluator.compareValues(a, b) <= 0) a else b)
  }

  private def toNumeric(v: Any): Double = v match {
    case d: Double  => d
    case l: Long    => l.toDouble
    case i: Int     => i.toDouble
    case s: String  => try { s.toDouble } catch { case _: NumberFormatException => 0.0 }
    case b: Boolean => if (b) 1.0 else 0.0
    case null       => 0.0
    case _          => 0.0
  }

  /**
   * 创建聚合上下文（用于 HAVING）
   */
  private def createAggContext(groupRows: List[Row]): Row = {
    // HAVING 子句中可能用到聚合函数，此方法预计算一些常见聚合
    Map.empty
  }

  /**
   * 在聚合上下文中求值 HAVING 表达式
   */
  private def evaluateWithAggContext(expr: Expression, groupRows: List[Row], context: Row): Any = expr match {
    case AggregateFunction(funcType, argument, distinct) =>
      computeAggregate(funcType, argument, distinct, groupRows)
    case BinaryExpression(left, op, right) =>
      val lv = evaluateWithAggContext(left, groupRows, context)
      val rv = evaluateWithAggContext(right, groupRows, context)
      val leftExpr = anyToExpression(lv)
      val rightExpr = anyToExpression(rv)
      ExpressionEvaluator.evaluate(BinaryExpression(leftExpr, op, rightExpr), Map.empty)
    case _ =>
      // 尝试从 context 获取，否则取组内首行
      try {
        ExpressionEvaluator.evaluate(expr, context)
      } catch {
        case _: ExecutionException =>
          groupRows.headOption.map(ExpressionEvaluator.evaluate(expr, _)).orNull
      }
  }

  // ============================================================
  //  ORDER BY
  // ============================================================

  private def sortRows(rows: List[Row], orderByClauses: List[OrderByClause]): List[Row] = {
    rows.sortWith { (a, b) =>
      orderByClauses.foldLeft(Option.empty[Boolean]) {
        case (Some(result), _) => Some(result) // 已确定顺序
        case (None, clause) =>
          val av = ExpressionEvaluator.evaluate(clause.expression, a)
          val bv = ExpressionEvaluator.evaluate(clause.expression, b)
          val cmp = ExpressionEvaluator.compareValues(av, bv)
          if (cmp == 0) None
          else Some(if (clause.ascending) cmp < 0 else cmp > 0)
      }.getOrElse(false)
    }
  }

  // ============================================================
  //  INSERT / UPDATE / DELETE
  // ============================================================

  private def executeInsert(stmt: InsertStatement): QueryResult = {
    val table = db.getTable(stmt.table).getOrElse(
      throw new ExecutionException(s"Table '${stmt.table}' does not exist"))

    val columnNames = stmt.columns match {
      case Some(cols) => cols.map(_.toUpperCase)
      case None       => table.schema.columns.map(_.name)
    }

    var insertCount = 0
    stmt.values.foreach { valueExprs =>
      if (valueExprs.size != columnNames.size) {
        throw new ExecutionException(
          s"Column count (${columnNames.size}) doesn't match value count (${valueExprs.size})")
      }
      val row = columnNames.zip(valueExprs).map { case (col, expr) =>
        col -> ExpressionEvaluator.evaluate(expr, Map.empty)
      }.toMap
      table.insert(row)
      insertCount += 1
    }

    QueryResult(List("Result"), List(Map("Result" -> s"$insertCount row(s) inserted")))
  }

  private def executeUpdate(stmt: UpdateStatement): QueryResult = {
    val table = db.getTable(stmt.table).getOrElse(
      throw new ExecutionException(s"Table '${stmt.table}' does not exist"))

    val predicate: Row => Boolean = stmt.where match {
      case Some(whereExpr) => row => ExpressionEvaluator.evaluate(whereExpr, row) == true
      case None            => _ => true
    }

    val updateFn: Row => Row = row => {
      stmt.assignments.foldLeft(row) { case (r, (col, expr)) =>
        r + (col.toUpperCase -> ExpressionEvaluator.evaluate(expr, r))
      }
    }

    val count = table.update(predicate, updateFn)
    QueryResult(List("Result"), List(Map("Result" -> s"$count row(s) updated")))
  }

  private def executeDelete(stmt: DeleteStatement): QueryResult = {
    val table = db.getTable(stmt.table).getOrElse(
      throw new ExecutionException(s"Table '${stmt.table}' does not exist"))

    val predicate: Row => Boolean = stmt.where match {
      case Some(whereExpr) => row => ExpressionEvaluator.evaluate(whereExpr, row) == true
      case None            => _ => true
    }

    val count = table.delete(predicate)
    QueryResult(List("Result"), List(Map("Result" -> s"$count row(s) deleted")))
  }

  // ============================================================
  //  UNION / WITH
  // ============================================================

  private def executeUnion(stmt: UnionStatement): QueryResult = {
    val leftResult = execute(stmt.left)
    val rightResult = executeSelect(stmt.right)

    val combinedRows = stmt.unionType match {
      case UnionAll          => leftResult.rows ++ rightResult.rows
      case UnionDistinct     => (leftResult.rows ++ rightResult.rows).distinct
      case IntersectDistinct =>
        leftResult.rows.filter(lr => rightResult.rows.exists(_ == lr)).distinct
      case IntersectAll =>
        leftResult.rows.filter(lr => rightResult.rows.exists(_ == lr))
      case ExceptDistinct =>
        leftResult.rows.filterNot(lr => rightResult.rows.exists(_ == lr)).distinct
      case ExceptAll =>
        val rightCopy = rightResult.rows.toBuffer
        leftResult.rows.filter { lr =>
          val idx = rightCopy.indexOf(lr)
          if (idx >= 0) { rightCopy.remove(idx); false }
          else true
        }
    }

    QueryResult(leftResult.columns, combinedRows)
  }

  private def executeWith(stmt: WithStatement): QueryResult = {
    // CTE 暂简化处理：将 CTE 作为临时视图注册，查询后删除
    val cteNames = stmt.ctes.map { cte =>
      val name = cte.name.toUpperCase
      db.createView(name, cte.query, orReplace = true)
      name
    }

    try {
      execute(stmt.query)
    } finally {
      cteNames.foreach(name => db.dropView(name, ifExists = true))
    }
  }

  // ============================================================
  //  CALL（存储过程调用）
  // ============================================================

  private def executeCall(stmt: CallStatement): QueryResult = {
    val proc = db.getProcedure(stmt.procedureName).getOrElse(
      throw new ExecutionException(s"Procedure '${stmt.procedureName}' does not exist"))

    if (stmt.arguments.size != proc.params.size) {
      throw new ExecutionException(
        s"Procedure '${stmt.procedureName}' expects ${proc.params.size} arguments, got ${stmt.arguments.size}")
    }

    // 求值参数
    val argValues = stmt.arguments.map(ExpressionEvaluator.evaluate(_, Map.empty))

    // 创建参数绑定（作为变量上下文暂不实现，简单执行 body）
    var lastResult = QueryResult(List("Result"), List(Map("Result" -> "Procedure executed")))
    proc.body.foreach { bodyStmt =>
      lastResult = execute(bodyStmt)
    }
    lastResult
  }

  // ============================================================
  //  辅助方法
  // ============================================================

  /** 检查列列表中是否包含聚合函数 */
  private def hasAggregateInColumns(columns: List[Column]): Boolean = {
    columns.exists {
      case ExpressionColumn(expr, _) => containsAggregate(expr)
      case _ => false
    }
  }

  private def containsAggregate(expr: Expression): Boolean = expr match {
    case _: AggregateFunction => true
    case BinaryExpression(left, _, right) => containsAggregate(left) || containsAggregate(right)
    case UnaryExpression(_, e) => containsAggregate(e)
    case _ => false
  }
}
