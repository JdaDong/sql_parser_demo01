package com.mysql.parser

/**
 * AST → JSON 序列化器
 *
 * 将任意 AST 节点转换为 JSON 字符串，无需外部库依赖。
 *
 * 使用方式：
 * {{{
 *   val ast = MySQLParser.parse("SELECT * FROM users")
 *   val json = ASTJsonSerializer.toJson(ast)
 *   val prettyJson = ASTJsonSerializer.toJsonPretty(ast)
 * }}}
 */
object ASTJsonSerializer {

  // ============================================================
  //  公共 API
  // ============================================================

  /** 将 SQLStatement 序列化为紧凑 JSON */
  def toJson(stmt: SQLStatement): String = serializeStatement(stmt)

  /** 将 SQLStatement 序列化为格式化 JSON（带缩进） */
  def toJsonPretty(stmt: SQLStatement): String = prettify(toJson(stmt))

  // ============================================================
  //  语句级序列化
  // ============================================================

  private def serializeStatement(stmt: SQLStatement): String = stmt match {
    case s: SelectStatement      => serializeSelect(s)
    case i: InsertStatement      => serializeInsert(i)
    case u: UpdateStatement      => serializeUpdate(u)
    case d: DeleteStatement      => serializeDelete(d)
    case c: CreateTableStatement => serializeCreateTable(c)
    case dt: DropTableStatement  => serializeDrop(dt)
    case a: AlterTableStatement  => serializeAlter(a)
    case ci: CreateIndexStatement => serializeCreateIndex(ci)
    case di: DropIndexStatement  => serializeDropIndex(di)
    case un: UnionStatement      => serializeUnion(un)
    case w: WithStatement        => serializeWith(w)
    case cv: CreateViewStatement => serializeCreateView(cv)
    case dv: DropViewStatement   => serializeDropView(dv)
    case cp: CreateProcedureStatement => serializeCreateProcedure(cp)
    case dp: DropProcedureStatement   => serializeDropProcedure(dp)
    case cs: CallStatement       => serializeCall(cs)
  }

  private def serializeSelect(s: SelectStatement): String = {
    val parts = List(
      kv("type", "SelectStatement"),
      kv("distinct", s.distinct),
      kvArr("columns", s.columns.map(serializeColumn)),
      kvOpt("from", s.from.map(serializeTableRef)),
      kvOpt("where", s.where.map(serializeExpr)),
      kvOptArr("orderBy", s.orderBy.map(_.map(serializeOrderBy))),
      kvOptArr("groupBy", s.groupBy.map(_.map(serializeExpr))),
      kvOpt("having", s.having.map(serializeExpr)),
      kvOptInt("limit", s.limit),
      kvOptInt("offset", s.offset)
    )
    obj(parts)
  }

  private def serializeInsert(i: InsertStatement): String = {
    val parts = List(
      kv("type", "InsertStatement"),
      kv("table", i.table),
      kvOptArr("columns", i.columns.map(_.map(jsonStr))),
      kvArr("values", i.values.map(row => arr(row.map(serializeExpr))))
    )
    obj(parts)
  }

  private def serializeUpdate(u: UpdateStatement): String = {
    val parts = List(
      kv("type", "UpdateStatement"),
      kv("table", u.table),
      kvArr("assignments", u.assignments.map { case (col, expr) =>
        obj(List(kv("column", col), kvRaw("value", serializeExpr(expr))))
      }),
      kvOpt("where", u.where.map(serializeExpr))
    )
    obj(parts)
  }

  private def serializeDelete(d: DeleteStatement): String = {
    val parts = List(
      kv("type", "DeleteStatement"),
      kv("table", d.table),
      kvOpt("where", d.where.map(serializeExpr))
    )
    obj(parts)
  }

  private def serializeCreateTable(c: CreateTableStatement): String = {
    val parts = List(
      kv("type", "CreateTableStatement"),
      kv("tableName", c.tableName),
      kvArr("columns", c.columns.map(serializeColumnDef)),
      kvArr("constraints", c.constraints.map(serializeTableConstraint))
    )
    obj(parts)
  }

  private def serializeDrop(dt: DropTableStatement): String = {
    val parts = List(
      kv("type", "DropTableStatement"),
      kv("tableName", dt.tableName),
      kv("ifExists", dt.ifExists)
    )
    obj(parts)
  }

  private def serializeAlter(a: AlterTableStatement): String = {
    val parts = List(
      kv("type", "AlterTableStatement"),
      kv("tableName", a.tableName),
      kvArr("actions", a.actions.map(serializeAlterAction))
    )
    obj(parts)
  }

  private def serializeCreateIndex(ci: CreateIndexStatement): String = {
    val parts = List(
      kv("type", "CreateIndexStatement"),
      kv("indexName", ci.indexName),
      kv("tableName", ci.tableName),
      kvArr("columns", ci.columns.map(serializeIndexColumn)),
      kv("unique", ci.unique)
    )
    obj(parts)
  }

  private def serializeDropIndex(di: DropIndexStatement): String = {
    val parts = List(
      kv("type", "DropIndexStatement"),
      kv("indexName", di.indexName),
      kv("tableName", di.tableName)
    )
    obj(parts)
  }

  private def serializeUnion(un: UnionStatement): String = {
    val unionTypeStr = un.unionType match {
      case UnionAll       => "UNION ALL"
      case UnionDistinct  => "UNION"
      case IntersectAll   => "INTERSECT ALL"
      case IntersectDistinct => "INTERSECT"
      case ExceptAll      => "EXCEPT ALL"
      case ExceptDistinct => "EXCEPT"
    }
    val parts = List(
      kv("type", "UnionStatement"),
      kv("unionType", unionTypeStr),
      kvRaw("left", serializeStatement(un.left)),
      kvRaw("right", serializeStatement(un.right))
    )
    obj(parts)
  }

  private def serializeWith(w: WithStatement): String = {
    val parts = List(
      kv("type", "WithStatement"),
      kv("recursive", w.recursive),
      kvArr("ctes", w.ctes.map { cte =>
        obj(List(kv("name", cte.name), kvRaw("query", serializeStatement(cte.query))))
      }),
      kvRaw("query", serializeStatement(w.query))
    )
    obj(parts)
  }

  private def serializeCreateView(cv: CreateViewStatement): String = {
    val parts = List(
      kv("type", "CreateViewStatement"),
      kv("viewName", cv.viewName),
      kv("orReplace", cv.orReplace),
      kvRaw("query", serializeStatement(cv.query))
    )
    obj(parts)
  }

  private def serializeDropView(dv: DropViewStatement): String = {
    val parts = List(
      kv("type", "DropViewStatement"),
      kv("viewName", dv.viewName),
      kv("ifExists", dv.ifExists)
    )
    obj(parts)
  }

  private def serializeCreateProcedure(cp: CreateProcedureStatement): String = {
    val parts = List(
      kv("type", "CreateProcedureStatement"),
      kv("name", cp.name),
      kvArr("params", cp.params.map { p =>
        val modeStr = p.mode match {
          case InParam    => "IN"
          case OutParam   => "OUT"
          case InOutParam => "INOUT"
        }
        obj(List(kv("mode", modeStr), kv("name", p.name), kv("dataType", serializeDataType(p.dataType))))
      }),
      kvArr("body", cp.body.map(serializeStatement))
    )
    obj(parts)
  }

  private def serializeDropProcedure(dp: DropProcedureStatement): String = {
    val parts = List(
      kv("type", "DropProcedureStatement"),
      kv("name", dp.name),
      kv("ifExists", dp.ifExists)
    )
    obj(parts)
  }

  private def serializeCall(cs: CallStatement): String = {
    val parts = List(
      kv("type", "CallStatement"),
      kv("procedureName", cs.procedureName),
      kvArr("arguments", cs.arguments.map(serializeExpr))
    )
    obj(parts)
  }

  // ============================================================
  //  列级序列化
  // ============================================================

  private def serializeColumn(col: Column): String = col match {
    case AllColumns =>
      obj(List(kv("type", "AllColumns")))
    case NamedColumn(name, alias) =>
      obj(List(kv("type", "NamedColumn"), kv("name", name), kvOptStr("alias", alias)))
    case QualifiedColumn(table, column, alias) =>
      obj(List(kv("type", "QualifiedColumn"), kv("table", table), kv("column", column), kvOptStr("alias", alias)))
    case ExpressionColumn(expression, alias) =>
      obj(List(kv("type", "ExpressionColumn"), kvRaw("expression", serializeExpr(expression)), kvOptStr("alias", alias)))
  }

  // ============================================================
  //  表引用级序列化
  // ============================================================

  private def serializeTableRef(ref: TableReference): String = ref match {
    case TableName(name, alias) =>
      obj(List(kv("type", "TableName"), kv("name", name), kvOptStr("alias", alias)))
    case DerivedTable(query, alias) =>
      obj(List(kv("type", "DerivedTable"), kvRaw("query", serializeStatement(query)), kv("alias", alias)))
    case JoinClause(left, right, joinType, condition) =>
      val jtStr = joinType match {
        case InnerJoin => "INNER"
        case LeftJoin  => "LEFT"
        case RightJoin => "RIGHT"
      }
      obj(List(
        kv("type", "JoinClause"),
        kv("joinType", jtStr),
        kvRaw("left", serializeTableRef(left)),
        kvRaw("right", serializeTableRef(right)),
        kvRaw("condition", serializeExpr(condition))
      ))
  }

  // ============================================================
  //  表达式级序列化
  // ============================================================

  private def serializeExpr(expr: Expression): String = expr match {
    case Identifier(name) =>
      obj(List(kv("type", "Identifier"), kv("name", name)))
    case QualifiedIdentifier(table, column) =>
      obj(List(kv("type", "QualifiedIdentifier"), kv("table", table), kv("column", column)))
    case StringLiteral(value) =>
      obj(List(kv("type", "StringLiteral"), kv("value", value)))
    case NumberLiteral(value) =>
      obj(List(kv("type", "NumberLiteral"), kv("value", value)))
    case NullLiteral =>
      obj(List(kv("type", "NullLiteral")))
    case AllColumnsExpression =>
      obj(List(kv("type", "AllColumnsExpression")))

    case BinaryExpression(left, op, right) =>
      obj(List(
        kv("type", "BinaryExpression"),
        kv("operator", serializeOp(op)),
        kvRaw("left", serializeExpr(left)),
        kvRaw("right", serializeExpr(right))
      ))
    case UnaryExpression(op, expression) =>
      obj(List(
        kv("type", "UnaryExpression"),
        kv("operator", serializeUnaryOp(op)),
        kvRaw("expression", serializeExpr(expression))
      ))

    case IsNullExpression(expression, negated) =>
      obj(List(kv("type", "IsNullExpression"), kv("negated", negated), kvRaw("expression", serializeExpr(expression))))
    case BetweenExpression(expression, lower, upper, negated) =>
      obj(List(kv("type", "BetweenExpression"), kv("negated", negated),
        kvRaw("expression", serializeExpr(expression)),
        kvRaw("lower", serializeExpr(lower)),
        kvRaw("upper", serializeExpr(upper))))
    case InExpression(expression, values, negated) =>
      obj(List(kv("type", "InExpression"), kv("negated", negated),
        kvRaw("expression", serializeExpr(expression)),
        kvArr("values", values.map(serializeExpr))))
    case LikeExpression(expression, pattern, negated) =>
      obj(List(kv("type", "LikeExpression"), kv("negated", negated),
        kvRaw("expression", serializeExpr(expression)),
        kvRaw("pattern", serializeExpr(pattern))))

    case AggregateFunction(funcType, argument, distinct) =>
      val funcName = funcType match {
        case CountFunc => "COUNT"
        case SumFunc   => "SUM"
        case AvgFunc   => "AVG"
        case MaxFunc   => "MAX"
        case MinFunc   => "MIN"
      }
      obj(List(kv("type", "AggregateFunction"), kv("function", funcName),
        kv("distinct", distinct), kvRaw("argument", serializeExpr(argument))))

    case FunctionCall(name, arguments) =>
      obj(List(kv("type", "FunctionCall"), kv("name", name),
        kvArr("arguments", arguments.map(serializeExpr))))

    case CaseExpression(operand, whenClauses, elseResult) =>
      obj(List(kv("type", "CaseExpression"),
        kvOpt("operand", operand.map(serializeExpr)),
        kvArr("whenClauses", whenClauses.map { wc =>
          obj(List(kvRaw("condition", serializeExpr(wc.condition)), kvRaw("result", serializeExpr(wc.result))))
        }),
        kvOpt("elseResult", elseResult.map(serializeExpr))))

    case CastExpression(expression, targetType) =>
      obj(List(kv("type", "CastExpression"),
        kvRaw("expression", serializeExpr(expression)),
        kv("targetType", serializeCastType(targetType))))

    case ConvertExpression(expression, targetType, charset) =>
      obj(List(kv("type", "ConvertExpression"),
        kvRaw("expression", serializeExpr(expression)),
        kvOptStr("targetType", targetType.map(serializeCastType)),
        kvOptStr("charset", charset)))

    case SubqueryExpression(query) =>
      obj(List(kv("type", "SubqueryExpression"), kvRaw("query", serializeStatement(query))))
    case ExistsExpression(query, negated) =>
      obj(List(kv("type", "ExistsExpression"), kv("negated", negated), kvRaw("query", serializeStatement(query))))
    case InSubqueryExpression(expression, query, negated) =>
      obj(List(kv("type", "InSubqueryExpression"), kv("negated", negated),
        kvRaw("expression", serializeExpr(expression)),
        kvRaw("query", serializeStatement(query))))

    case WindowFunctionExpression(function, windowSpec) =>
      obj(List(kv("type", "WindowFunctionExpression"),
        kvRaw("function", serializeExpr(function)),
        kvRaw("windowSpec", serializeWindowSpec(windowSpec))))
  }

  // ============================================================
  //  辅助序列化
  // ============================================================

  private def serializeOp(op: BinaryOperator): String = op match {
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

  private def serializeUnaryOp(op: UnaryOperator): String = op match {
    case NotOp => "NOT"
  }

  private def serializeOrderBy(ob: OrderByClause): String = {
    obj(List(kvRaw("expression", serializeExpr(ob.expression)), kv("ascending", ob.ascending)))
  }

  private def serializeColumnDef(col: ColumnDefinition): String = {
    obj(List(
      kv("name", col.name),
      kv("dataType", serializeDataType(col.dataType)),
      kvArr("constraints", col.constraints.map(serializeColumnConstraint))
    ))
  }

  private def serializeDataType(dt: DataType): String = dt match {
    case IntType(Some(s))      => s"INT($s)"
    case IntType(None)         => "INT"
    case BigIntType(Some(s))   => s"BIGINT($s)"
    case BigIntType(None)      => "BIGINT"
    case SmallIntType(Some(s)) => s"SMALLINT($s)"
    case SmallIntType(None)    => "SMALLINT"
    case VarcharType(s)        => s"VARCHAR($s)"
    case TextType              => "TEXT"
    case DateTimeType          => "DATETIME"
    case TimestampType         => "TIMESTAMP"
    case BooleanType           => "BOOLEAN"
    case FloatType             => "FLOAT"
    case DoubleType            => "DOUBLE"
    case DecimalDataType(Some(p), Some(s)) => s"DECIMAL($p,$s)"
    case DecimalDataType(Some(p), None)    => s"DECIMAL($p)"
    case DecimalDataType(None, _)          => "DECIMAL"
  }

  private def serializeColumnConstraint(cc: ColumnConstraint): String = cc match {
    case NotNullConstraint       => jsonStr("NOT NULL")
    case PrimaryKeyConstraint    => jsonStr("PRIMARY KEY")
    case UniqueConstraint        => jsonStr("UNIQUE")
    case AutoIncrementConstraint => jsonStr("AUTO_INCREMENT")
    case DefaultConstraint(v)    =>
      obj(List(kv("type", "DEFAULT"), kvRaw("value", serializeExpr(v))))
    case CheckColumnConstraint(c) =>
      obj(List(kv("type", "CHECK"), kvRaw("condition", serializeExpr(c))))
    case ReferencesConstraint(refTable, refCol) =>
      obj(List(kv("type", "REFERENCES"), kv("refTable", refTable), kv("refColumn", refCol)))
  }

  private def serializeTableConstraint(tc: TableConstraint): String = tc match {
    case PrimaryKeyTableConstraint(name, cols) =>
      obj(List(kv("type", "PRIMARY KEY"), kvOptStr("name", name), kvArr("columns", cols.map(jsonStr))))
    case UniqueTableConstraint(name, cols) =>
      obj(List(kv("type", "UNIQUE"), kvOptStr("name", name), kvArr("columns", cols.map(jsonStr))))
    case ForeignKeyTableConstraint(name, cols, refTable, refCols) =>
      obj(List(kv("type", "FOREIGN KEY"), kvOptStr("name", name),
        kvArr("columns", cols.map(jsonStr)),
        kv("refTable", refTable),
        kvArr("refColumns", refCols.map(jsonStr))))
    case CheckTableConstraint(name, condition) =>
      obj(List(kv("type", "CHECK"), kvOptStr("name", name), kvRaw("condition", serializeExpr(condition))))
  }

  private def serializeAlterAction(action: AlterAction): String = action match {
    case AddColumnAction(col) =>
      obj(List(kv("action", "ADD COLUMN"), kvRaw("column", serializeColumnDef(col))))
    case DropColumnAction(name) =>
      obj(List(kv("action", "DROP COLUMN"), kv("columnName", name)))
    case ModifyColumnAction(col) =>
      obj(List(kv("action", "MODIFY COLUMN"), kvRaw("column", serializeColumnDef(col))))
    case ChangeColumnAction(oldName, newCol) =>
      obj(List(kv("action", "CHANGE COLUMN"), kv("oldName", oldName), kvRaw("newColumn", serializeColumnDef(newCol))))
    case RenameTableAction(newName) =>
      obj(List(kv("action", "RENAME TO"), kv("newName", newName)))
    case AddConstraintAction(constraint) =>
      obj(List(kv("action", "ADD CONSTRAINT"), kvRaw("constraint", serializeTableConstraint(constraint))))
    case DropConstraintAction(constraintType, name) =>
      obj(List(kv("action", "DROP CONSTRAINT"), kv("constraintType", constraintType), kvOptStr("name", name)))
  }

  private def serializeIndexColumn(ic: IndexColumn): String = {
    obj(List(kv("name", ic.name), kv("ascending", ic.ascending)))
  }

  private def serializeCastType(ct: CastType): String = ct match {
    case SignedCastType(isInt)   => if (isInt) "SIGNED INT" else "SIGNED"
    case UnsignedCastType(isInt) => if (isInt) "UNSIGNED INT" else "UNSIGNED"
    case CharCastType(Some(s))   => s"CHAR($s)"
    case CharCastType(None)      => "CHAR"
    case VarcharCastType(s)      => s"VARCHAR($s)"
    case DecimalCastType(Some(p), Some(s)) => s"DECIMAL($p,$s)"
    case DecimalCastType(Some(p), None)    => s"DECIMAL($p)"
    case DecimalCastType(None, _)          => "DECIMAL"
    case DateCastType            => "DATE"
    case DateTimeCastType        => "DATETIME"
    case IntCastType             => "INT"
    case BooleanCastType         => "BOOLEAN"
  }

  private def serializeWindowSpec(ws: WindowSpec): String = {
    val parts = List(
      kvOptArr("partitionBy", ws.partitionBy.map(_.map(serializeExpr))),
      kvOptArr("orderBy", ws.orderBy.map(_.map(serializeOrderBy))),
      kvOpt("frame", ws.frame.map(serializeWindowFrame))
    )
    obj(parts)
  }

  private def serializeWindowFrame(f: WindowFrame): String = {
    val ftStr = f.frameType match {
      case RowsFrame  => "ROWS"
      case RangeFrame => "RANGE"
    }
    obj(List(
      kv("frameType", ftStr),
      kv("start", serializeFrameBound(f.start)),
      kvOptStr("end", f.end.map(serializeFrameBound))
    ))
  }

  private def serializeFrameBound(b: FrameBound): String = b match {
    case UnboundedPreceding => "UNBOUNDED PRECEDING"
    case UnboundedFollowing => "UNBOUNDED FOLLOWING"
    case CurrentRowBound    => "CURRENT ROW"
    case PrecedingBound(n)  => s"$n PRECEDING"
    case FollowingBound(n)  => s"$n FOLLOWING"
  }

  // ============================================================
  //  JSON 生成辅助方法
  // ============================================================

  /** 转义 JSON 字符串 */
  private def escape(s: String): String = {
    s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c    => c.toString
    }
  }

  /** JSON 字符串值 */
  private def jsonStr(s: String): String = s""""${escape(s)}""""

  /** JSON 键值对：字符串值 */
  private def kv(key: String, value: String): String = s""""${escape(key)}":${jsonStr(value)}"""

  /** JSON 键值对：布尔值 */
  private def kv(key: String, value: Boolean): String = s""""${escape(key)}":$value"""

  /** JSON 键值对：整数值 */
  private def kv(key: String, value: Int): String = s""""${escape(key)}":$value"""

  /** JSON 键值对：原始 JSON 值（不加引号） */
  private def kvRaw(key: String, rawJson: String): String = s""""${escape(key)}":$rawJson"""

  /** JSON 键值对：可选字符串 */
  private def kvOptStr(key: String, opt: Option[String]): String = opt match {
    case Some(v) => kv(key, v)
    case None    => s""""${escape(key)}":null"""
  }

  /** JSON 键值对：可选 raw JSON */
  private def kvOpt(key: String, opt: Option[String]): String = opt match {
    case Some(v) => kvRaw(key, v)
    case None    => s""""${escape(key)}":null"""
  }

  /** JSON 键值对：可选整数 */
  private def kvOptInt(key: String, opt: Option[Int]): String = opt match {
    case Some(v) => kv(key, v)
    case None    => s""""${escape(key)}":null"""
  }

  /** JSON 键值对：数组值 */
  private def kvArr(key: String, items: List[String]): String = {
    s""""${escape(key)}":${arr(items)}"""
  }

  /** JSON 键值对：可选数组值 */
  private def kvOptArr(key: String, opt: Option[List[String]]): String = opt match {
    case Some(items) => kvArr(key, items)
    case None        => s""""${escape(key)}":null"""
  }

  /** JSON 数组 */
  private def arr(items: List[String]): String = s"[${items.mkString(",")}]"

  /** JSON 对象 */
  private def obj(fields: List[String]): String = s"{${fields.mkString(",")}}"

  // ============================================================
  //  JSON 美化（简单实现）
  // ============================================================

  /** 将紧凑 JSON 格式化为带缩进的漂亮输出 */
  def prettify(json: String): String = {
    val sb = new StringBuilder
    var indent = 0
    var inString = false
    var escaped = false
    val indentStr = "  "

    for (ch <- json) {
      if (escaped) {
        sb.append(ch)
        escaped = false
      } else if (ch == '\\' && inString) {
        sb.append(ch)
        escaped = true
      } else if (ch == '"') {
        sb.append(ch)
        inString = !inString
      } else if (inString) {
        sb.append(ch)
      } else {
        ch match {
          case '{' | '[' =>
            sb.append(ch)
            indent += 1
            sb.append('\n')
            sb.append(indentStr * indent)
          case '}' | ']' =>
            indent -= 1
            sb.append('\n')
            sb.append(indentStr * indent)
            sb.append(ch)
          case ',' =>
            sb.append(ch)
            sb.append('\n')
            sb.append(indentStr * indent)
          case ':' =>
            sb.append(": ")
          case c if c.isWhitespace =>
            // skip
          case c =>
            sb.append(c)
        }
      }
    }

    sb.toString()
  }
}
