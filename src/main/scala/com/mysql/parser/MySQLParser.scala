package com.mysql.parser

/**
 * SQL Parser Demo01 主入口
 */
object MySQLParser {
  
  /**
   * 解析 SQL 语句（增强模式，错误报告包含行号/列号）
   */
  def parse(sql: String): SQLStatement = {
    val lexer = new Lexer(sql)
    val posTokens = lexer.tokenizeWithPositions()
    val parser = Parser.withPositions(posTokens, sql)
    parser.parse()
  }

  /**
   * 解析 SQL 并返回带位置的 Token 列表（用于 REPL 展示 Token 流）
   */
  def tokenize(sql: String): List[PositionedToken] = {
    val lexer = new Lexer(sql)
    lexer.tokenizeWithPositions()
  }

  /**
   * 解析 SQL 并返回紧凑 JSON 字符串
   */
  def toJson(sql: String): String = {
    val ast = parse(sql)
    ASTJsonSerializer.toJson(ast)
  }

  /**
   * 解析 SQL 并返回格式化 JSON 字符串（带缩进）
   */
  def toJsonPretty(sql: String): String = {
    val ast = parse(sql)
    ASTJsonSerializer.toJsonPretty(ast)
  }

  /**
   * 格式化打印 AST
   */
  def printAST(statement: SQLStatement, indent: Int = 0): Unit = {
    val prefix = "  " * indent
    statement match {
      case SelectStatement(columns, from, where, orderBy, groupBy, having, limit, offset, distinct) =>
        println(s"${prefix}SELECT Statement:")
        if (distinct) println(s"${prefix}  DISTINCT: true")
        println(s"${prefix}  Columns:")
        columns.foreach {
          case AllColumns => println(s"${prefix}    *")
          case NamedColumn(name, alias) =>
            println(s"${prefix}    ${name}${alias.map(a => s" AS $a").getOrElse("")}")
          case QualifiedColumn(table, column, alias) =>
            println(s"${prefix}    ${table}.${column}${alias.map(a => s" AS $a").getOrElse("")}")
          case ExpressionColumn(expr, alias) =>
            print(s"${prefix}    ")
            printExpression(expr, 0)
            println(s"${alias.map(a => s" AS $a").getOrElse("")}")
        }
        from.foreach { f =>
          println(s"${prefix}  FROM:")
          printTableReference(f, indent + 2)
        }
        where.foreach { w =>
          println(s"${prefix}  WHERE:")
          printExpression(w, indent + 2)
        }
        groupBy.foreach { gb =>
          println(s"${prefix}  GROUP BY:")
          gb.foreach(e => printExpression(e, indent + 2))
        }
        having.foreach { h =>
          println(s"${prefix}  HAVING:")
          printExpression(h, indent + 2)
        }
        orderBy.foreach { ob =>
          println(s"${prefix}  ORDER BY:")
          ob.foreach { clause =>
            printExpression(clause.expression, indent + 2)
            println(s"${prefix}    ${if (clause.ascending) "ASC" else "DESC"}")
          }
        }
        limit.foreach(l => println(s"${prefix}  LIMIT: $l"))
        offset.foreach(o => println(s"${prefix}  OFFSET: $o"))

      case InsertStatement(table, columns, values) =>
        println(s"${prefix}INSERT Statement:")
        println(s"${prefix}  Table: $table")
        columns.foreach { cols =>
          println(s"${prefix}  Columns: ${cols.mkString(", ")}")
        }
        println(s"${prefix}  Values:")
        values.foreach { valueList =>
          print(s"${prefix}    (")
          valueList.foreach(v => printExpression(v, 0))
          println(")")
        }

      case UpdateStatement(table, assignments, where) =>
        println(s"${prefix}UPDATE Statement:")
        println(s"${prefix}  Table: $table")
        println(s"${prefix}  Assignments:")
        assignments.foreach { case (col, expr) =>
          print(s"${prefix}    $col = ")
          printExpression(expr, 0)
          println()
        }
        where.foreach { w =>
          println(s"${prefix}  WHERE:")
          printExpression(w, indent + 2)
        }

      case DeleteStatement(table, where) =>
        println(s"${prefix}DELETE Statement:")
        println(s"${prefix}  Table: $table")
        where.foreach { w =>
          println(s"${prefix}  WHERE:")
          printExpression(w, indent + 2)
        }

      case CreateTableStatement(tableName, columns, constraints) =>
        println(s"${prefix}CREATE TABLE Statement:")
        println(s"${prefix}  Table: $tableName")
        println(s"${prefix}  Columns:")
        columns.foreach { col =>
          val constraintStr = if (col.constraints.nonEmpty)
            " [" + col.constraints.map(printColumnConstraint).mkString(", ") + "]"
          else ""
          println(s"${prefix}    ${col.name} ${printDataType(col.dataType)}$constraintStr")
        }
        if (constraints.nonEmpty) {
          println(s"${prefix}  Table Constraints:")
          constraints.foreach { tc =>
            println(s"${prefix}    ${printTableConstraint(tc)}")
          }
        }

      case DropTableStatement(tableName, ifExists) =>
        val ifExistsStr = if (ifExists) " IF EXISTS" else ""
        println(s"${prefix}DROP TABLE$ifExistsStr Statement:")
        println(s"${prefix}  Table: $tableName")

      case AlterTableStatement(tableName, actions) =>
        println(s"${prefix}ALTER TABLE Statement:")
        println(s"${prefix}  Table: $tableName")
        println(s"${prefix}  Actions:")
        actions.foreach {
          case AddColumnAction(col) =>
            val constraintStr = if (col.constraints.nonEmpty)
              " [" + col.constraints.map(printColumnConstraint).mkString(", ") + "]"
            else ""
            println(s"${prefix}    ADD COLUMN: ${col.name} ${printDataType(col.dataType)}$constraintStr")
          case DropColumnAction(name) =>
            println(s"${prefix}    DROP COLUMN: $name")
          case ModifyColumnAction(col) =>
            val constraintStr = if (col.constraints.nonEmpty)
              " [" + col.constraints.map(printColumnConstraint).mkString(", ") + "]"
            else ""
            println(s"${prefix}    MODIFY COLUMN: ${col.name} ${printDataType(col.dataType)}$constraintStr")
          case ChangeColumnAction(oldName, newCol) =>
            val constraintStr = if (newCol.constraints.nonEmpty)
              " [" + newCol.constraints.map(printColumnConstraint).mkString(", ") + "]"
            else ""
            println(s"${prefix}    CHANGE COLUMN: $oldName -> ${newCol.name} ${printDataType(newCol.dataType)}$constraintStr")
          case RenameTableAction(newName) =>
            println(s"${prefix}    RENAME TO: $newName")
          case AddConstraintAction(tc) =>
            println(s"${prefix}    ADD ${printTableConstraint(tc)}")
          case DropConstraintAction(constraintType, name) =>
            println(s"${prefix}    DROP $constraintType${name.map(n => s" $n").getOrElse("")}")
        }

      case CreateIndexStatement(indexName, tableName, columns, unique) =>
        val uniqueStr = if (unique) "UNIQUE " else ""
        println(s"${prefix}CREATE ${uniqueStr}INDEX Statement:")
        println(s"${prefix}  Index: $indexName")
        println(s"${prefix}  Table: $tableName")
        println(s"${prefix}  Columns: ${columns.map(c => s"${c.name}${if (c.ascending) "" else " DESC"}").mkString(", ")}")

      case DropIndexStatement(indexName, tableName) =>
        println(s"${prefix}DROP INDEX Statement:")
        println(s"${prefix}  Index: $indexName")
        println(s"${prefix}  Table: $tableName")

      case UnionStatement(left, right, unionType) =>
        val opLabel = unionType match {
          case UnionAll          => "UNION ALL"
          case UnionDistinct     => "UNION"
          case IntersectAll      => "INTERSECT ALL"
          case IntersectDistinct => "INTERSECT"
          case ExceptAll         => "EXCEPT ALL"
          case ExceptDistinct    => "EXCEPT"
        }
        println(s"${prefix}${opLabel} Statement:")
        println(s"${prefix}  Type: $opLabel")
        println(s"${prefix}  Left:")
        printAST(left, indent + 2)
        println(s"${prefix}  Right:")
        printAST(right, indent + 2)

      case WithStatement(ctes, query, recursive) =>
        println(s"${prefix}WITH Statement${if (recursive) " (RECURSIVE)" else ""}:")
        ctes.foreach { cte =>
          println(s"${prefix}  CTE: ${cte.name}")
          printAST(cte.query, indent + 2)
        }
        println(s"${prefix}  Main Query:")
        printAST(query, indent + 2)

      case CreateViewStatement(viewName, query, orReplace) =>
        val replaceStr = if (orReplace) "OR REPLACE " else ""
        println(s"${prefix}CREATE ${replaceStr}VIEW Statement:")
        println(s"${prefix}  View: $viewName")
        println(s"${prefix}  Query:")
        printAST(query, indent + 2)

      case DropViewStatement(viewName, ifExists) =>
        val ifExistsStr = if (ifExists) " IF EXISTS" else ""
        println(s"${prefix}DROP VIEW$ifExistsStr Statement:")
        println(s"${prefix}  View: $viewName")

      case CreateProcedureStatement(name, params, body) =>
        println(s"${prefix}CREATE PROCEDURE Statement:")
        println(s"${prefix}  Name: $name")
        if (params.nonEmpty) {
          println(s"${prefix}  Parameters:")
          params.foreach { p =>
            val modeStr = p.mode match {
              case InParam    => "IN"
              case OutParam   => "OUT"
              case InOutParam => "INOUT"
            }
            println(s"${prefix}    $modeStr ${p.name} ${printDataType(p.dataType)}")
          }
        }
        println(s"${prefix}  Body:")
        body.foreach(printAST(_, indent + 2))

      case DropProcedureStatement(name, ifExists) =>
        val ifExistsStr = if (ifExists) " IF EXISTS" else ""
        println(s"${prefix}DROP PROCEDURE$ifExistsStr Statement:")
        println(s"${prefix}  Procedure: $name")

      case CallStatement(procName, args) =>
        println(s"${prefix}CALL Statement:")
        println(s"${prefix}  Procedure: $procName")
        if (args.nonEmpty) {
          println(s"${prefix}  Arguments:")
          args.foreach { arg =>
            print(s"${prefix}    ")
            printExpression(arg, 0)
            println()
          }
        }
    }
  }

  private def printTableReference(table: TableReference, indent: Int): Unit = {
    val prefix = "  " * indent
    table match {
      case TableName(name, alias) =>
        println(s"${prefix}Table: $name${alias.map(a => s" AS $a").getOrElse("")}")
      case DerivedTable(query, alias) =>
        println(s"${prefix}DerivedTable AS $alias:")
        printAST(query, indent + 1)
      case JoinClause(left, right, joinType, condition) =>
        println(s"${prefix}JOIN:")
        println(s"${prefix}  Type: ${joinType}")
        println(s"${prefix}  Left:")
        printTableReference(left, indent + 2)
        println(s"${prefix}  Right:")
        printTableReference(right, indent + 2)
        println(s"${prefix}  ON:")
        printExpression(condition, indent + 2)
    }
  }

  private def printExpression(expr: Expression, indent: Int): Unit = {
    val prefix = "  " * indent
    expr match {
      case Identifier(name) => print(s"${prefix}${name}")
      case QualifiedIdentifier(table, column) => print(s"${prefix}${table}.${column}")
      case StringLiteral(value) => print(s"${prefix}'${value}'")
      case NumberLiteral(value) => print(s"${prefix}${value}")
      case NullLiteral => print(s"${prefix}NULL")
      case AllColumnsExpression => print(s"${prefix}*")
      case AggregateFunction(funcType, argument, distinct) =>
        print(s"${prefix}${printAggregateType(funcType)}(")
        if (distinct) print("DISTINCT ")
        printExpression(argument, 0)
        print(")")
      case IsNullExpression(expression, negated) =>
        print(s"${prefix}(")
        printExpression(expression, 0)
        print(s" IS ${if (negated) "NOT " else ""}NULL)")
      case BetweenExpression(expression, lower, upper, negated) =>
        print(s"${prefix}(")
        printExpression(expression, 0)
        print(s" ${if (negated) "NOT " else ""}BETWEEN ")
        printExpression(lower, 0)
        print(" AND ")
        printExpression(upper, 0)
        print(")")
      case InExpression(expression, values, negated) =>
        print(s"${prefix}(")
        printExpression(expression, 0)
        print(s" ${if (negated) "NOT " else ""}IN (")
        values.zipWithIndex.foreach { case (v, i) =>
          if (i > 0) print(", ")
          printExpression(v, 0)
        }
        print("))")
      case LikeExpression(expression, pattern, negated) =>
        print(s"${prefix}(")
        printExpression(expression, 0)
        print(s" ${if (negated) "NOT " else ""}LIKE ")
        printExpression(pattern, 0)
        print(")")
      case BinaryExpression(left, op, right) =>
        print("(")
        printExpression(left, 0)
        print(s" ${printOperator(op)} ")
        printExpression(right, 0)
        print(")")
      case UnaryExpression(op, expr) =>
        print(s"${printUnaryOperator(op)} ")
        printExpression(expr, 0)
      case SubqueryExpression(query) =>
        print(s"${prefix}(")
        print("SELECT ...")
        print(")")
      case ExistsExpression(query, negated) =>
        print(s"${prefix}${if (negated) "NOT " else ""}EXISTS (")
        print("SELECT ...")
        print(")")
      case InSubqueryExpression(expression, query, negated) =>
        print(s"${prefix}(")
        printExpression(expression, 0)
        print(s" ${if (negated) "NOT " else ""}IN (")
        print("SELECT ...")
        print("))")
      case CaseExpression(operand, whenClauses, elseResult) =>
        print(s"${prefix}CASE")
        operand.foreach { op =>
          print(" ")
          printExpression(op, 0)
        }
        whenClauses.foreach { wc =>
          print(" WHEN ")
          printExpression(wc.condition, 0)
          print(" THEN ")
          printExpression(wc.result, 0)
        }
        elseResult.foreach { er =>
          print(" ELSE ")
          printExpression(er, 0)
        }
        print(" END")

      case FunctionCall(name, arguments) =>
        print(s"${prefix}${name}(")
        arguments.zipWithIndex.foreach { case (arg, i) =>
          if (i > 0) print(", ")
          printExpression(arg, 0)
        }
        print(")")

      case CastExpression(expression, targetType) =>
        print(s"${prefix}CAST(")
        printExpression(expression, 0)
        print(s" AS ${printCastType(targetType)})")

      case ConvertExpression(expression, targetType, charset) =>
        print(s"${prefix}CONVERT(")
        printExpression(expression, 0)
        targetType.foreach { t =>
          print(s", ${printCastType(t)}")
        }
        charset.foreach { c =>
          print(s" USING $c")
        }
        print(")")

      case WindowFunctionExpression(function, windowSpec) =>
        printExpression(function, indent)
        print(" OVER (")
        windowSpec.partitionBy.foreach { exprs =>
          print("PARTITION BY ")
          exprs.zipWithIndex.foreach { case (e, i) =>
            if (i > 0) print(", ")
            printExpression(e, 0)
          }
        }
        if (windowSpec.partitionBy.isDefined && windowSpec.orderBy.isDefined) print(" ")
        windowSpec.orderBy.foreach { obs =>
          print("ORDER BY ")
          obs.zipWithIndex.foreach { case (ob, i) =>
            if (i > 0) print(", ")
            printExpression(ob.expression, 0)
            print(if (ob.ascending) " ASC" else " DESC")
          }
        }
        print(")")
    }
  }

  private def printOperator(op: BinaryOperator): String = op match {
    case Equal => "="
    case NotEqual => "!="
    case LessThan => "<"
    case GreaterThan => ">"
    case LessEqual => "<="
    case GreaterEqual => ">="
    case Plus => "+"
    case Minus => "-"
    case Multiply => "*"
    case Divide => "/"
    case AndOp => "AND"
    case OrOp => "OR"
  }

  private def printUnaryOperator(op: UnaryOperator): String = op match {
    case NotOp => "NOT"
  }

  private def printAggregateType(funcType: AggregateType): String = funcType match {
    case CountFunc => "COUNT"
    case SumFunc   => "SUM"
    case AvgFunc   => "AVG"
    case MaxFunc   => "MAX"
    case MinFunc   => "MIN"
  }

  private def printDataType(dataType: DataType): String = dataType match {
    case IntType(Some(size)) => s"INT($size)"
    case IntType(None) => "INT"
    case BigIntType(Some(size)) => s"BIGINT($size)"
    case BigIntType(None) => "BIGINT"
    case SmallIntType(Some(size)) => s"SMALLINT($size)"
    case SmallIntType(None) => "SMALLINT"
    case VarcharType(size) => s"VARCHAR($size)"
    case TextType => "TEXT"
    case DateTimeType => "DATETIME"
    case TimestampType => "TIMESTAMP"
    case BooleanType => "BOOLEAN"
    case FloatType => "FLOAT"
    case DoubleType => "DOUBLE"
    case DecimalDataType(Some(p), Some(s)) => s"DECIMAL($p,$s)"
    case DecimalDataType(Some(p), None) => s"DECIMAL($p)"
    case DecimalDataType(None, _) => "DECIMAL"
  }

  private def printColumnConstraint(c: ColumnConstraint): String = c match {
    case NotNullConstraint => "NOT NULL"
    case PrimaryKeyConstraint => "PRIMARY KEY"
    case UniqueConstraint => "UNIQUE"
    case AutoIncrementConstraint => "AUTO_INCREMENT"
    case DefaultConstraint(_) => "DEFAULT ..."
    case CheckColumnConstraint(_) => "CHECK (...)"
    case ReferencesConstraint(refTable, refCol) => s"REFERENCES $refTable($refCol)"
  }

  private def printTableConstraint(tc: TableConstraint): String = {
    val nameStr = tc.name.map(n => s"CONSTRAINT $n ").getOrElse("")
    tc match {
      case PrimaryKeyTableConstraint(_, cols) => s"${nameStr}PRIMARY KEY (${cols.mkString(", ")})"
      case UniqueTableConstraint(_, cols) => s"${nameStr}UNIQUE (${cols.mkString(", ")})"
      case ForeignKeyTableConstraint(_, cols, refTable, refCols) =>
        s"${nameStr}FOREIGN KEY (${cols.mkString(", ")}) REFERENCES $refTable(${refCols.mkString(", ")})"
      case CheckTableConstraint(_, _) => s"${nameStr}CHECK (...)"
    }
  }

  private def printCastType(castType: CastType): String = castType match {
    case SignedCastType(isInt) => if (isInt) "SIGNED INT" else "SIGNED"
    case UnsignedCastType(isInt) => if (isInt) "UNSIGNED INT" else "UNSIGNED"
    case CharCastType(Some(size)) => s"CHAR($size)"
    case CharCastType(None) => "CHAR"
    case VarcharCastType(size) => s"VARCHAR($size)"
    case DecimalCastType(Some(p), Some(s)) => s"DECIMAL($p,$s)"
    case DecimalCastType(Some(p), None) => s"DECIMAL($p)"
    case DecimalCastType(None, _) => "DECIMAL"
    case DateCastType => "DATE"
    case DateTimeCastType => "DATETIME"
    case IntCastType => "INT"
    case BooleanCastType => "BOOLEAN"
  }

  /**
   * 解析并执行语义分析
   *
   * @param sql    SQL 字符串
   * @param schema 数据库 Schema
   * @return (AST, 语义错误列表)
   */
  def parseAndAnalyze(sql: String, schema: DatabaseSchema): (SQLStatement, List[SemanticError]) = {
    val ast = parse(sql)
    val errors = SemanticAnalyzer.analyze(ast, schema)
    (ast, errors)
  }

  /**
   * 打印语义分析结果
   */
  def printSemanticResult(sql: String, errors: List[SemanticError]): Unit = {
    if (errors.isEmpty) {
      println(s"  ✅ 语义检查通过")
    } else {
      errors.foreach { err =>
        val icon = err.severity match {
          case SError   => "❌"
          case SWarning => "⚠️"
        }
        println(s"  ${icon} [${err.severity}][${err.category}] ${err.message}")
      }
    }
  }

  /**
   * 主函数 - 示例用法
   *
   * 用法：
   *   sbt run          — 运行示例演示
   *   sbt "run repl"   — 启动交互式 REPL
   */
  def main(args: Array[String]): Unit = {
    if (args.nonEmpty && args(0).toLowerCase == "repl") {
      SQLRepl.main(args.drop(1))
      return
    }

    println("=== SQL Parser Demo01 ===\n")
    println(s"提示: 运行 sbt \"run repl\" 启动交互式 REPL\n")

    // 示例 SQL 语句
    val examples = List(
      "SELECT * FROM users",
      "SELECT id, name, email FROM users WHERE age > 18",
      "SELECT u.id, u.name, o.order_id FROM users u JOIN orders o ON u.id = o.user_id",
      "INSERT INTO users (name, email) VALUES ('Alice', 'alice@example.com')",
      "UPDATE users SET age = 25 WHERE name = 'Bob'",
      "DELETE FROM users WHERE id = 1",
      "CREATE TABLE users (id INT, name VARCHAR(100), email TEXT)",
      "SELECT DISTINCT category FROM products WHERE price > 100 ORDER BY category LIMIT 10",
      // 聚合函数示例
      "SELECT COUNT(*) FROM users",
      "SELECT COUNT(DISTINCT category) AS cat_count FROM products",
      "SELECT department, AVG(salary) AS avg_salary, MAX(salary) AS max_salary, MIN(salary) AS min_salary FROM employees GROUP BY department",
      "SELECT category, SUM(price) AS total FROM products GROUP BY category HAVING SUM(price) > 1000",
      // 谓词表达式示例
      "SELECT * FROM users WHERE email IS NULL",
      "SELECT * FROM users WHERE email IS NOT NULL",
      "SELECT * FROM products WHERE price BETWEEN 100 AND 500",
      "SELECT * FROM products WHERE price NOT BETWEEN 100 AND 500",
      "SELECT * FROM users WHERE role IN ('admin', 'editor', 'moderator')",
      "SELECT * FROM users WHERE role NOT IN ('banned', 'suspended')",
      "SELECT * FROM users WHERE name LIKE 'Ali%'",
      "SELECT * FROM users WHERE name NOT LIKE '%test%'",
      // ASC/DESC 排序示例
      "SELECT * FROM users ORDER BY age DESC",
      "SELECT * FROM products ORDER BY category ASC, price DESC",
      // 子查询示例
      "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)",
      "SELECT * FROM users WHERE salary > (SELECT AVG(salary) FROM employees)",
      "SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)",
      "SELECT t.name FROM (SELECT name FROM users WHERE age > 18) AS t",
      // CASE WHEN 示例
      "SELECT name, CASE WHEN salary > 10000 THEN 'high' WHEN salary > 5000 THEN 'medium' ELSE 'low' END AS salary_level FROM employees",
      "SELECT CASE status WHEN 'active' THEN 1 WHEN 'inactive' THEN 0 ELSE -1 END FROM users",
      // UNION 示例
      "SELECT name FROM users UNION SELECT name FROM admins",
      "SELECT id, name FROM employees UNION ALL SELECT id, name FROM contractors",
      // 函数调用示例
      "SELECT UPPER(name) FROM users",
      "SELECT CONCAT(first_name, ' ', last_name) AS full_name FROM users",
      "SELECT IFNULL(nickname, name) FROM users",
      "SELECT NOW()",
      // 隐式别名示例
      "SELECT name n, age a FROM users",
      "SELECT COUNT(*) total FROM users",
      // CAST / CONVERT 示例
      "SELECT CAST(price AS DECIMAL(10,2)) FROM products",
      "SELECT CAST(val AS SIGNED) FROM t",
      "SELECT CONVERT(price, DECIMAL(10,2)) FROM products",
      "SELECT CONVERT(name USING utf8) FROM users"
    )

    examples.foreach { sql =>
      println(s"SQL: $sql")
      println("-" * 60)
      try {
        val ast = parse(sql)
        printAST(ast)
      } catch {
        case e: Exception =>
          println(s"Error: ${e.getMessage}")
      }
      println("\n")
    }

    // ============================================
    //  语义分析示例
    // ============================================
    println("\n" + "=" * 60)
    println("=== 语义分析示例 ===")
    println("=" * 60 + "\n")

    // 构建示例 Schema
    val schema = DatabaseSchema(
      TableSchema.simple("users", "id", "name", "age", "email", "role", "department", "salary", "status"),
      TableSchema.simple("orders", "id", "user_id", "product_id", "amount", "created_at"),
      TableSchema.simple("products", "id", "name", "category", "price"),
      TableSchema.simple("employees", "id", "name", "department", "salary", "hire_date"),
      TableSchema.simple("admins", "id", "name", "level")
    )

    // 正确的 SQL（应通过）
    val validExamples = List(
      "SELECT id, name FROM users",
      "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id",
      "SELECT department, COUNT(*) FROM employees GROUP BY department",
      "SELECT * FROM users WHERE age > 18",
      "INSERT INTO users (name, age) VALUES ('Alice', 25)",
      "UPDATE users SET age = 30 WHERE name = 'Bob'",
      "DELETE FROM users WHERE id = 1",
      "SELECT name FROM users UNION SELECT name FROM admins",
      "SELECT NOW()"
    )

    println("--- 正确的 SQL ---\n")
    validExamples.foreach { sql =>
      println(s"SQL: $sql")
      try {
        val (_, errors) = parseAndAnalyze(sql, schema)
        printSemanticResult(sql, errors)
      } catch {
        case e: Exception => println(s"  Parse Error: ${e.getMessage}")
      }
      println()
    }

    // 有语义错误的 SQL
    val errorExamples = List(
      // 表不存在
      "SELECT * FROM nonexistent_table",
      // 列不存在
      "SELECT fake_column FROM users",
      // 别名未定义
      "SELECT x.name FROM users u",
      // 列不属于指定的表
      "SELECT u.nonexistent FROM users u",
      // GROUP BY 一致性
      "SELECT name, COUNT(*) FROM users GROUP BY department",
      // 别名重复
      "SELECT name AS x, age AS x FROM users",
      // INSERT 列数不匹配
      "INSERT INTO users (name, age, email) VALUES ('Alice', 25)",
      // UNION 列数不一致
      "SELECT id, name FROM users UNION SELECT id FROM admins",
      // CREATE TABLE 表已存在
      "CREATE TABLE users (id INT)",
      // DROP TABLE 表不存在
      "DROP TABLE nonexistent_table",
      // UPDATE 列不存在
      "UPDATE users SET fake_col = 1",
      // WHERE 中引用不存在的列
      "SELECT * FROM users WHERE nonexistent > 10"
    )

    println("--- 有语义错误的 SQL ---\n")
    errorExamples.foreach { sql =>
      println(s"SQL: $sql")
      try {
        val (_, errors) = parseAndAnalyze(sql, schema)
        printSemanticResult(sql, errors)
      } catch {
        case e: Exception => println(s"  Parse Error: ${e.getMessage}")
      }
      println()
    }

    // ============================================
    //  AST Visitor 模式示例
    // ============================================
    println("\n" + "=" * 60)
    println("=== AST Visitor 模式示例 ===")
    println("=" * 60 + "\n")

    val visitorExamples = List(
      "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id WHERE o.amount > 100 ORDER BY u.name",
      "SELECT department, COUNT(*) AS cnt, AVG(salary) AS avg_sal FROM employees GROUP BY department HAVING COUNT(*) > 5",
      "INSERT INTO users (name, age) VALUES ('Alice', 25)",
      "UPDATE users SET age = 30 WHERE name = 'Bob'",
      "DELETE FROM users WHERE id = 1",
      "SELECT name FROM users UNION SELECT name FROM admins"
    )

    val tableExtractor = new TableExtractor()
    val columnExtractor = new ColumnExtractor()
    val prettyPrinter = new SQLPrettyPrinter()

    println("--- 1. 表名提取（TableExtractor） ---\n")
    visitorExamples.foreach { sql =>
      println(s"SQL: $sql")
      try {
        val ast = parse(sql)
        val tables = tableExtractor.visitStatement(ast).distinct
        println(s"  Tables: ${tables.mkString(", ")}")
      } catch {
        case e: Exception => println(s"  Error: ${e.getMessage}")
      }
      println()
    }

    println("--- 2. 列名提取（ColumnExtractor） ---\n")
    visitorExamples.foreach { sql =>
      println(s"SQL: $sql")
      try {
        val ast = parse(sql)
        val columns = columnExtractor.visitStatement(ast).distinct
        println(s"  Columns: ${columns.mkString(", ")}")
      } catch {
        case e: Exception => println(s"  Error: ${e.getMessage}")
      }
      println()
    }

    println("--- 3. SQL 格式化（SQLPrettyPrinter） ---\n")
    visitorExamples.foreach { sql =>
      println(s"原始 SQL: $sql")
      try {
        val ast = parse(sql)
        val formatted = prettyPrinter.visitStatement(ast)
        println("格式化:")
        formatted.split("\n").foreach(line => println(s"  $line"))
      } catch {
        case e: Exception => println(s"  Error: ${e.getMessage}")
      }
      println()
    }

    println("--- 4. 表名重命名（TableRenamer） ---\n")
    val renameExamples = List(
      "SELECT * FROM users WHERE id = 1",
      "SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id"
    )
    val renamer = new TableRenamer(Map("USERS" -> "users_v2", "ORDERS" -> "orders_v2"))
    renameExamples.foreach { sql =>
      println(s"原始 SQL: $sql")
      try {
        val ast = parse(sql)
        val renamed = renamer.transformStatement(ast)
        val renamedSql = prettyPrinter.visitStatement(renamed)
        println("重命名后:")
        renamedSql.split("\n").foreach(line => println(s"  $line"))
      } catch {
        case e: Exception => println(s"  Error: ${e.getMessage}")
      }
      println()
    }

    println("--- 5. 列名重命名（ColumnRenamer） ---\n")
    val colRenameExamples = List(
      "SELECT name, age FROM users WHERE name = 'Alice'",
      "SELECT u.name FROM users u"
    )
    val colRenamer = new ColumnRenamer(Map("NAME" -> "full_name", "AGE" -> "user_age"))
    colRenameExamples.foreach { sql =>
      println(s"原始 SQL: $sql")
      try {
        val ast = parse(sql)
        val renamed = colRenamer.transformStatement(ast)
        val renamedSql = prettyPrinter.visitStatement(renamed)
        println("重命名后:")
        renamedSql.split("\n").foreach(line => println(s"  $line"))
      } catch {
        case e: Exception => println(s"  Error: ${e.getMessage}")
      }
      println()
    }

    // ============================================
    //  基于 Visitor 模式的语义分析示例
    // ============================================
    println("\n" + "=" * 60)
    println("=== Visitor 模式语义分析示例 ===")
    println("=" * 60 + "\n")

    val pipeline = new SemanticVisitorPipeline(schema)

    println("--- 1. 完整管道分析（所有检查） ---\n")
    val pipelineExamples = List(
      "SELECT id, name FROM users",
      "SELECT * FROM nonexistent_table",
      "SELECT fake_column FROM users",
      "SELECT name, COUNT(*) FROM users GROUP BY department",
      "INSERT INTO users (name, age, email) VALUES ('Alice', 25)",
      "SELECT id, name FROM users UNION SELECT id FROM admins"
    )
    pipelineExamples.foreach { sql =>
      println(s"SQL: $sql")
      try {
        val ast = parse(sql)
        val errors = pipeline.analyze(ast)
        printSemanticResult(sql, errors)
      } catch {
        case e: Exception => println(s"  Parse Error: ${e.getMessage}")
      }
      println()
    }

    println("--- 2. 只检查表存在性（单个 Visitor） ---\n")
    val tableOnlyPipeline = pipeline.withOnly(classOf[TableExistenceVisitor])
    val tableCheckExamples = List(
      "SELECT * FROM users",
      "SELECT * FROM nonexistent_table",
      "INSERT INTO fake_table (name) VALUES ('test')"
    )
    tableCheckExamples.foreach { sql =>
      println(s"SQL: $sql")
      try {
        val ast = parse(sql)
        val errors = tableOnlyPipeline.analyze(ast)
        printSemanticResult(sql, errors)
      } catch {
        case e: Exception => println(s"  Parse Error: ${e.getMessage}")
      }
      println()
    }

    println("--- 3. 排除 DDL 检查 ---\n")
    val noDDLPipeline = pipeline.without(classOf[DDLValidationVisitor])
    val ddlExamples = List(
      "CREATE TABLE users (id INT)",      // DDL 检查被排除，不会报"表已存在"
      "DROP TABLE nonexistent_table"      // DDL 检查被排除，不会报"表不存在"
    )
    ddlExamples.foreach { sql =>
      println(s"SQL: $sql")
      try {
        val ast = parse(sql)
        val errors = noDDLPipeline.analyze(ast)
        printSemanticResult(sql, errors)
      } catch {
        case e: Exception => println(s"  Parse Error: ${e.getMessage}")
      }
      println()
    }

    println("--- 4. Visitor 语义分析 vs 传统语义分析（结果对比） ---\n")
    val compareExamples = List(
      "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id",
      "SELECT x.name FROM users u",
      "SELECT department, COUNT(*) FROM employees GROUP BY department HAVING COUNT(*) > 5",
      "SELECT department, COUNT(*) FROM employees GROUP BY department HAVING name = 'test'"
    )
    compareExamples.foreach { sql =>
      println(s"SQL: $sql")
      try {
        val ast = parse(sql)
        val visitorErrors = pipeline.analyze(ast)
        val traditionalErrors = SemanticAnalyzer.analyze(ast, schema)
        println(s"  Visitor 分析:  ${if (visitorErrors.isEmpty) "✅ 通过" else s"${visitorErrors.length} 个错误"}")
        visitorErrors.foreach(e => println(s"    ❌ [${e.category}] ${e.message}"))
        println(s"  传统分析:      ${if (traditionalErrors.isEmpty) "✅ 通过" else s"${traditionalErrors.length} 个错误"}")
        traditionalErrors.foreach(e => println(s"    ❌ [${e.category}] ${e.message}"))
      } catch {
        case e: Exception => println(s"  Parse Error: ${e.getMessage}")
      }
      println()
    }
  }
}
