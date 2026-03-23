package com.mysql.parser

// ============================================================
//  SQL 类型系统
//
//  为语义分析提供表达式类型推断和类型兼容性检查。
//
//  架构：
//    SQLType         — 类型层次结构（sealed trait + case objects/classes）
//    TypeInferencer  — 从 AST 表达式推断 SQL 类型
//    TypeChecker     — 检查表达式之间的类型兼容性
//    TypeCheckVisitor — 基于 Visitor 模式的类型检查 Visitor
// ============================================================

// ============================================================
//  SQL 类型层次结构
// ============================================================

/**
 * SQL 类型 — 表达式的推断类型
 *
 * 类型层次（从窄到宽）：
 *   BooleanSQLType < IntegerSQLType < NumericSQLType < StringSQLType
 *   DateSQLType / DateTimeSQLType 独立分支
 *   NullSQLType 可兼容任意类型
 *   UnknownSQLType 表示无法推断
 */
sealed trait SQLType {
  /** 类型的显示名称 */
  def displayName: String
}

/** 整数类型（INT, BIGINT, SMALLINT, TINYINT） */
case object IntegerSQLType extends SQLType {
  def displayName: String = "INTEGER"
}

/** 数值类型（FLOAT, DOUBLE, DECIMAL — 包含整数） */
case object NumericSQLType extends SQLType {
  def displayName: String = "NUMERIC"
}

/** 字符串类型（VARCHAR, TEXT, CHAR） */
case object StringSQLType extends SQLType {
  def displayName: String = "STRING"
}

/** 布尔类型（BOOLEAN, 比较表达式结果） */
case object BooleanSQLType extends SQLType {
  def displayName: String = "BOOLEAN"
}

/** 日期类型（DATE） */
case object DateSQLType extends SQLType {
  def displayName: String = "DATE"
}

/** 日期时间类型（DATETIME, TIMESTAMP） */
case object DateTimeSQLType extends SQLType {
  def displayName: String = "DATETIME"
}

/** NULL 类型 — 可兼容任意类型 */
case object NullSQLType extends SQLType {
  def displayName: String = "NULL"
}

/** 未知类型 — 无法推断（如通用函数调用） */
case object UnknownSQLType extends SQLType {
  def displayName: String = "UNKNOWN"
}


// ============================================================
//  类型推断器
// ============================================================

/**
 * 表达式类型推断器
 *
 * 根据 AST 表达式节点和可选的 Schema 信息推断 SQL 类型。
 *
 * 推断规则：
 *   - 字面量：字符串 → STRING, 数字 → INTEGER/NUMERIC, NULL → NULL
 *   - 标识符：从 Schema 列类型映射
 *   - 算术运算：如果有 NUMERIC 则结果为 NUMERIC，否则 INTEGER
 *   - 比较运算/逻辑运算：→ BOOLEAN
 *   - 聚合函数：COUNT → INTEGER, SUM/AVG → NUMERIC, MAX/MIN → 继承参数类型
 *   - CASE 表达式：取 THEN/ELSE 分支的统一类型
 *   - CAST 表达式：根据目标类型确定
 *   - 函数调用：特定已知函数有确定类型，其他返回 UNKNOWN
 */
object TypeInferencer {

  /**
   * 推断表达式的 SQL 类型
   *
   * @param expr  AST 表达式
   * @param scope 当前查询作用域（可选，用于列类型查找）
   * @return 推断出的 SQL 类型
   */
  def infer(expr: Expression, scope: QueryScope = QueryScope()): SQLType = expr match {
    // -- 字面量 --
    case StringLiteral(_) => StringSQLType
    case NumberLiteral(value) =>
      if (value.contains('.')) NumericSQLType else IntegerSQLType
    case NullLiteral => NullSQLType
    case AllColumnsExpression => UnknownSQLType

    // -- 标识符 --
    case Identifier(name) =>
      inferColumnType(name, scope)
    case QualifiedIdentifier(table, column) =>
      inferQualifiedColumnType(table, column, scope)

    // -- 算术运算 --
    case BinaryExpression(left, op, right) =>
      op match {
        case Plus | Minus | Multiply | Divide =>
          promoteNumeric(infer(left, scope), infer(right, scope))
        case Equal | NotEqual | LessThan | GreaterThan | LessEqual | GreaterEqual =>
          BooleanSQLType
        case AndOp | OrOp =>
          BooleanSQLType
      }

    // -- 一元运算 --
    case UnaryExpression(NotOp, _) => BooleanSQLType

    // -- 谓词 --
    case IsNullExpression(_, _)   => BooleanSQLType
    case BetweenExpression(_, _, _, _) => BooleanSQLType
    case InExpression(_, _, _)    => BooleanSQLType
    case LikeExpression(_, _, _)  => BooleanSQLType
    case ExistsExpression(_, _)   => BooleanSQLType
    case InSubqueryExpression(_, _, _) => BooleanSQLType

    // -- 聚合函数 --
    case AggregateFunction(funcType, argument, _) =>
      funcType match {
        case CountFunc => IntegerSQLType
        case SumFunc | AvgFunc => NumericSQLType
        case MaxFunc | MinFunc => infer(argument, scope)
      }

    // -- 函数调用 --
    case FunctionCall(name, args) =>
      inferFunctionType(name, args, scope)

    // -- CASE 表达式 --
    case CaseExpression(_, whenClauses, elseResult) =>
      val branchTypes = whenClauses.map(wc => infer(wc.result, scope)) ++
        elseResult.map(e => infer(e, scope)).toList
      unifyTypes(branchTypes)

    // -- CAST/CONVERT --
    case CastExpression(_, targetType) =>
      castTypeToSQLType(targetType)
    case ConvertExpression(_, targetType, _) =>
      targetType.map(castTypeToSQLType).getOrElse(StringSQLType)

    // -- 子查询表达式 --
    case SubqueryExpression(_) => UnknownSQLType

    // -- 窗口函数 --
    case WindowFunctionExpression(function, _) =>
      infer(function, scope)
  }

  /**
   * 从 Schema 列类型推断 SQL 类型
   */
  private def inferColumnType(colName: String, scope: QueryScope): SQLType = {
    // 遍历 scope 中的表，查找列的类型
    scope.tables.values.foreach { table =>
      table.findColumn(colName).foreach { col =>
        return dataTypeStringToSQLType(col.dataType)
      }
    }
    UnknownSQLType
  }

  /**
   * 从限定列引用推断类型
   */
  private def inferQualifiedColumnType(table: String, column: String, scope: QueryScope): SQLType = {
    scope.findTable(table).flatMap(_.findColumn(column)).map { col =>
      dataTypeStringToSQLType(col.dataType)
    }.getOrElse(UnknownSQLType)
  }

  /**
   * 将 Schema 中的数据类型字符串映射为 SQLType
   */
  def dataTypeStringToSQLType(dataType: String): SQLType = {
    dataType.toUpperCase match {
      case "INT" | "INTEGER" | "BIGINT" | "SMALLINT" | "TINYINT" => IntegerSQLType
      case "FLOAT" | "DOUBLE" | "DECIMAL" | "NUMERIC" | "REAL"  => NumericSQLType
      case "VARCHAR" | "TEXT" | "CHAR" | "LONGTEXT" | "MEDIUMTEXT" => StringSQLType
      case "BOOLEAN" | "BOOL" => BooleanSQLType
      case "DATE" => DateSQLType
      case "DATETIME" | "TIMESTAMP" => DateTimeSQLType
      case _ => UnknownSQLType  // UNKNOWN 或未识别类型
    }
  }

  /**
   * 将 CastType 映射为 SQLType
   */
  private def castTypeToSQLType(ct: CastType): SQLType = ct match {
    case SignedCastType(_) | UnsignedCastType(_) | IntCastType => IntegerSQLType
    case DecimalCastType(_, _) => NumericSQLType
    case CharCastType(_) | VarcharCastType(_) => StringSQLType
    case BooleanCastType => BooleanSQLType
    case DateCastType => DateSQLType
    case DateTimeCastType => DateTimeSQLType
  }

  /**
   * 推断函数调用的返回类型
   */
  private def inferFunctionType(name: String, args: List[Expression], scope: QueryScope): SQLType = {
    name.toUpperCase match {
      // 字符串函数
      case "UPPER" | "LOWER" | "TRIM" | "LTRIM" | "RTRIM" | "CONCAT" |
           "SUBSTRING" | "SUBSTR" | "REPLACE" | "REVERSE" | "LEFT" | "RIGHT" |
           "LPAD" | "RPAD" | "REPEAT" | "SPACE" => StringSQLType
      // 数值函数
      case "ABS" | "CEIL" | "CEILING" | "FLOOR" | "ROUND" | "TRUNCATE" |
           "MOD" | "POWER" | "SQRT" | "LOG" | "LOG2" | "LOG10" |
           "SIN" | "COS" | "TAN" | "RAND" => NumericSQLType
      // 整数函数
      case "LENGTH" | "CHAR_LENGTH" | "CHARACTER_LENGTH" |
           "INSTR" | "LOCATE" | "POSITION" | "SIGN" => IntegerSQLType
      // 日期函数
      case "CURDATE" | "CURRENT_DATE" => DateSQLType
      case "NOW" | "CURRENT_TIMESTAMP" | "SYSDATE" => DateTimeSQLType
      case "DATE" => DateSQLType
      case "YEAR" | "MONTH" | "DAY" | "HOUR" | "MINUTE" | "SECOND" |
           "DAYOFWEEK" | "DAYOFYEAR" | "WEEK" | "QUARTER" | "DATEDIFF" => IntegerSQLType
      // 条件函数
      case "IFNULL" | "COALESCE" | "NULLIF" =>
        if (args.nonEmpty) infer(args.head, scope) else UnknownSQLType
      case "IF" =>
        if (args.length >= 2) infer(args(1), scope) else UnknownSQLType
      // 类型转换
      case "CAST" | "CONVERT" => UnknownSQLType
      // 窗口排名函数
      case "ROW_NUMBER" | "RANK" | "DENSE_RANK" | "NTILE" => IntegerSQLType
      // 未知函数
      case _ => UnknownSQLType
    }
  }

  /**
   * 数值类型提升：两个操作数取更宽的类型
   */
  private def promoteNumeric(left: SQLType, right: SQLType): SQLType = {
    (left, right) match {
      case (NullSQLType, other) => other
      case (other, NullSQLType) => other
      case (NumericSQLType, _) | (_, NumericSQLType) => NumericSQLType
      case (IntegerSQLType, IntegerSQLType) => IntegerSQLType
      case (IntegerSQLType, _) | (_, IntegerSQLType) => NumericSQLType
      case (UnknownSQLType, _) | (_, UnknownSQLType) => UnknownSQLType
      case _ => NumericSQLType  // 其他组合默认为 NUMERIC
    }
  }

  /**
   * 统一多个类型为一个公共类型（用于 CASE 分支、UNION 列等）
   */
  def unifyTypes(types: List[SQLType]): SQLType = {
    val nonNull = types.filterNot(_ == NullSQLType).filterNot(_ == UnknownSQLType)
    if (nonNull.isEmpty) {
      if (types.contains(NullSQLType)) NullSQLType else UnknownSQLType
    } else if (nonNull.distinct.length == 1) {
      nonNull.head
    } else {
      // 数值类型之间可以统一
      val allNumeric = nonNull.forall(t => t == IntegerSQLType || t == NumericSQLType)
      if (allNumeric) NumericSQLType
      // 日期类型可以统一为 DATETIME
      else if (nonNull.forall(t => t == DateSQLType || t == DateTimeSQLType)) DateTimeSQLType
      // 混合类型统一为 STRING（MySQL 隐式转换）
      else StringSQLType
    }
  }
}


// ============================================================
//  类型兼容性检查器
// ============================================================

/**
 * 类型兼容性检查器
 *
 * 检查二元运算符两侧、函数参数、赋值等场景下的类型兼容性。
 *
 * 规则：
 *   - NULL 兼容任何类型
 *   - UNKNOWN 兼容任何类型（宽容模式）
 *   - 同类型总是兼容
 *   - INTEGER 兼容 NUMERIC（子类型关系）
 *   - DATE 兼容 DATETIME（子类型关系）
 *   - 比较运算：两侧必须是同类或可隐式转换
 *   - 算术运算：两侧必须是数值类型
 *   - 逻辑运算：两侧必须是布尔类型
 */
object TypeChecker {

  /**
   * 类型不兼容错误
   */
  case class TypeError(
    message: String,
    leftType: SQLType,
    rightType: SQLType
  )

  /**
   * 检查两个类型是否兼容
   */
  def areCompatible(left: SQLType, right: SQLType): Boolean = {
    (left, right) match {
      case (NullSQLType, _) | (_, NullSQLType) => true
      case (UnknownSQLType, _) | (_, UnknownSQLType) => true
      case (l, r) if l == r => true
      // 数值类型之间兼容
      case (IntegerSQLType, NumericSQLType) | (NumericSQLType, IntegerSQLType) => true
      // 布尔和整数兼容（MySQL 中 TRUE=1, FALSE=0）
      case (BooleanSQLType, IntegerSQLType) | (IntegerSQLType, BooleanSQLType) => true
      // 日期类型之间兼容
      case (DateSQLType, DateTimeSQLType) | (DateTimeSQLType, DateSQLType) => true
      // 字符串可以和日期比较（隐式转换）
      case (StringSQLType, DateSQLType) | (DateSQLType, StringSQLType) => true
      case (StringSQLType, DateTimeSQLType) | (DateTimeSQLType, StringSQLType) => true
      case _ => false
    }
  }

  /**
   * 检查类型是否是数值类型
   */
  def isNumeric(t: SQLType): Boolean = t match {
    case IntegerSQLType | NumericSQLType | BooleanSQLType => true
    case NullSQLType | UnknownSQLType => true  // 宽容模式
    case _ => false
  }

  /**
   * 检查比较运算符的类型兼容性
   */
  def checkComparison(left: SQLType, op: BinaryOperator, right: SQLType): Option[TypeError] = {
    if (areCompatible(left, right)) None
    else Some(TypeError(
      s"Cannot compare ${left.displayName} with ${right.displayName} using ${opSymbol(op)}",
      left, right
    ))
  }

  /**
   * 检查算术运算符的类型兼容性
   */
  def checkArithmetic(left: SQLType, op: BinaryOperator, right: SQLType): Option[TypeError] = {
    if (!isNumeric(left)) {
      Some(TypeError(
        s"Left operand of ${opSymbol(op)} must be numeric, but got ${left.displayName}",
        left, right
      ))
    } else if (!isNumeric(right)) {
      Some(TypeError(
        s"Right operand of ${opSymbol(op)} must be numeric, but got ${right.displayName}",
        left, right
      ))
    } else None
  }

  /**
   * 检查逻辑运算符的类型兼容性
   */
  def checkLogical(left: SQLType, op: BinaryOperator, right: SQLType): Option[TypeError] = {
    val leftOk = left match {
      case BooleanSQLType | NullSQLType | UnknownSQLType => true
      case IntegerSQLType => true  // MySQL 允许 0/1 作为布尔值
      case _ => false
    }
    val rightOk = right match {
      case BooleanSQLType | NullSQLType | UnknownSQLType => true
      case IntegerSQLType => true
      case _ => false
    }
    if (!leftOk || !rightOk) {
      Some(TypeError(
        s"${opSymbol(op)} requires boolean operands, but got ${left.displayName} and ${right.displayName}",
        left, right
      ))
    } else None
  }

  private def opSymbol(op: BinaryOperator): String = op match {
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
}


// ============================================================
//  类型检查 Visitor — 集成到语义分析管道
// ============================================================

/**
 * 类型检查 Visitor
 *
 * 遍历 AST 检查表达式中的类型兼容性：
 *   1. 比较运算两侧类型兼容
 *   2. 算术运算两侧都是数值类型
 *   3. 逻辑运算两侧都是布尔类型
 *   4. CASE 分支类型一致
 *   5. UNION/INTERSECT/EXCEPT 对应列类型兼容
 *
 * 使用方式：
 * {{{
 *   val visitor = new TypeCheckVisitor(schema)
 *   val errors = visitor.visitStatement(ast)
 * }}}
 */
class TypeCheckVisitor(schema: DatabaseSchema)
  extends SemanticBaseVisitor(schema) {

  override def visitSelectStatement(stmt: SelectStatement): List[SemanticError] = {
    // 构建查询作用域
    val (scope, scopeErrors) = ScopeBuilder.buildScope(stmt.from, schema)

    // 检查 WHERE 子句中的类型
    val whereErrors = stmt.where.map(expr => checkExpressionTypes(expr, scope)).getOrElse(zero)

    // 检查 HAVING 子句中的类型
    val havingErrors = stmt.having.map(expr => checkExpressionTypes(expr, scope)).getOrElse(zero)

    // 检查 SELECT 列中的表达式类型
    val colErrors = combineAll(stmt.columns.collect {
      case ExpressionColumn(expr, _) => checkExpressionTypes(expr, scope)
    })

    // 递归检查子查询
    val fromErrors = stmt.from.map(visitTableReference).getOrElse(zero)

    combineAll(List(scopeErrors, whereErrors, havingErrors, colErrors, fromErrors))
  }

  override def visitUnionStatement(stmt: UnionStatement): List[SemanticError] = {
    // 递归检查左右子语句
    val leftErrors = visitStatement(stmt.left)
    val rightErrors = visitSelectStatement(stmt.right)

    // 检查对应列的类型兼容性（只在可推断时检查）
    val colTypeErrors = checkUnionColumnTypes(stmt)

    combineAll(List(leftErrors, rightErrors, colTypeErrors))
  }

  override def visitInsertStatement(stmt: InsertStatement): List[SemanticError] = {
    // 检查 INSERT VALUES 中表达式的类型
    if (!schema.hasTable(stmt.table)) return zero

    val table = schema.findTable(stmt.table).get
    val scope = QueryScope()

    stmt.columns match {
      case Some(cols) =>
        combineAll(stmt.values.flatMap { valueRow =>
          cols.zip(valueRow).map { case (colName, expr) =>
            table.findColumn(colName) match {
              case Some(col) =>
                val expectedType = TypeInferencer.dataTypeStringToSQLType(col.dataType)
                val actualType = TypeInferencer.infer(expr, scope)
                if (expectedType != UnknownSQLType && actualType != UnknownSQLType &&
                    actualType != NullSQLType && !TypeChecker.areCompatible(expectedType, actualType)) {
                  List(SemanticError(
                    s"Type mismatch in INSERT: column '${colName}' expects ${expectedType.displayName} but got ${actualType.displayName}",
                    SWarning, "TYPE"
                  ))
                } else zero
              case None => zero
            }
          }
        })
      case None => zero
    }
  }

  override def visitUpdateStatement(stmt: UpdateStatement): List[SemanticError] = {
    if (!schema.hasTable(stmt.table)) return zero

    val table = schema.findTable(stmt.table).get
    val scope = QueryScope(
      tables = Map(table.name.toUpperCase -> table),
      allColumns = table.columns.map(c => c.name.toUpperCase -> Set(table.name.toUpperCase)).toMap
    )

    // 检查 SET 赋值的类型兼容性
    val setErrors = combineAll(stmt.assignments.map { case (colName, expr) =>
      table.findColumn(colName) match {
        case Some(col) =>
          val expectedType = TypeInferencer.dataTypeStringToSQLType(col.dataType)
          val actualType = TypeInferencer.infer(expr, scope)
          if (expectedType != UnknownSQLType && actualType != UnknownSQLType &&
              actualType != NullSQLType && !TypeChecker.areCompatible(expectedType, actualType)) {
            List(SemanticError(
              s"Type mismatch in UPDATE SET: column '${colName}' expects ${expectedType.displayName} but got ${actualType.displayName}",
              SWarning, "TYPE"
            ))
          } else zero
        case None => zero
      }
    })

    // 检查 WHERE 中的类型
    val whereErrors = stmt.where.map(expr => checkExpressionTypes(expr, scope)).getOrElse(zero)

    combine(setErrors, whereErrors)
  }

  // -- 私有方法 --

  /**
   * 递归检查表达式中的类型兼容性
   */
  private def checkExpressionTypes(expr: Expression, scope: QueryScope): List[SemanticError] = {
    expr match {
      case BinaryExpression(left, op, right) =>
        val leftType = TypeInferencer.infer(left, scope)
        val rightType = TypeInferencer.infer(right, scope)

        val opError = op match {
          case Plus | Minus | Multiply | Divide =>
            TypeChecker.checkArithmetic(leftType, op, rightType).map(e =>
              SemanticError(e.message, SWarning, "TYPE")
            )
          case Equal | NotEqual | LessThan | GreaterThan | LessEqual | GreaterEqual =>
            TypeChecker.checkComparison(leftType, op, rightType).map(e =>
              SemanticError(e.message, SWarning, "TYPE")
            )
          case AndOp | OrOp =>
            TypeChecker.checkLogical(leftType, op, rightType).map(e =>
              SemanticError(e.message, SWarning, "TYPE")
            )
        }

        // 递归检查子表达式
        val childErrors = checkExpressionTypes(left, scope) ++ checkExpressionTypes(right, scope)
        opError.toList ++ childErrors

      case CaseExpression(operand, whenClauses, elseResult) =>
        // 检查分支类型一致性
        val branchTypes = whenClauses.map(wc => TypeInferencer.infer(wc.result, scope)) ++
          elseResult.map(e => TypeInferencer.infer(e, scope)).toList
        val nonTrivialTypes = branchTypes.filterNot(t => t == NullSQLType || t == UnknownSQLType)
        val caseErrors = if (nonTrivialTypes.distinct.length > 1) {
          val allNumeric = nonTrivialTypes.forall(t => t == IntegerSQLType || t == NumericSQLType)
          if (!allNumeric) {
            List(SemanticError(
              s"CASE expression branches have inconsistent types: ${nonTrivialTypes.map(_.displayName).distinct.mkString(", ")}",
              SWarning, "TYPE"
            ))
          } else zero
        } else zero

        // 递归检查子表达式
        val childErrors =
          operand.toList.flatMap(o => checkExpressionTypes(o, scope)) ++
          whenClauses.flatMap(wc =>
            checkExpressionTypes(wc.condition, scope) ++ checkExpressionTypes(wc.result, scope)
          ) ++
          elseResult.toList.flatMap(e => checkExpressionTypes(e, scope))

        caseErrors ++ childErrors

      case BetweenExpression(expression, lower, upper, _) =>
        val exprType = TypeInferencer.infer(expression, scope)
        val lowerType = TypeInferencer.infer(lower, scope)
        val upperType = TypeInferencer.infer(upper, scope)
        val errors = List(
          if (!TypeChecker.areCompatible(exprType, lowerType))
            Some(SemanticError(
              s"BETWEEN: expression type ${exprType.displayName} is not compatible with lower bound type ${lowerType.displayName}",
              SWarning, "TYPE"))
          else None,
          if (!TypeChecker.areCompatible(exprType, upperType))
            Some(SemanticError(
              s"BETWEEN: expression type ${exprType.displayName} is not compatible with upper bound type ${upperType.displayName}",
              SWarning, "TYPE"))
          else None
        ).flatten
        errors ++ checkExpressionTypes(expression, scope) ++
          checkExpressionTypes(lower, scope) ++ checkExpressionTypes(upper, scope)

      case InExpression(expression, values, _) =>
        val exprType = TypeInferencer.infer(expression, scope)
        val valueErrors = values.flatMap { v =>
          val vType = TypeInferencer.infer(v, scope)
          if (!TypeChecker.areCompatible(exprType, vType))
            List(SemanticError(
              s"IN: expression type ${exprType.displayName} is not compatible with value type ${vType.displayName}",
              SWarning, "TYPE"))
          else zero
        }
        valueErrors ++ checkExpressionTypes(expression, scope) ++
          values.flatMap(v => checkExpressionTypes(v, scope))

      case UnaryExpression(_, inner) =>
        checkExpressionTypes(inner, scope)
      case IsNullExpression(inner, _) =>
        checkExpressionTypes(inner, scope)
      case LikeExpression(expression, pattern, _) =>
        checkExpressionTypes(expression, scope) ++ checkExpressionTypes(pattern, scope)
      case AggregateFunction(_, argument, _) =>
        checkExpressionTypes(argument, scope)
      case FunctionCall(_, arguments) =>
        arguments.flatMap(a => checkExpressionTypes(a, scope))
      case CastExpression(expression, _) =>
        checkExpressionTypes(expression, scope)
      case ConvertExpression(expression, _, _) =>
        checkExpressionTypes(expression, scope)
      case InSubqueryExpression(expression, _, _) =>
        checkExpressionTypes(expression, scope)
      case WindowFunctionExpression(function, windowSpec) =>
        checkExpressionTypes(function, scope) ++
          windowSpec.partitionBy.toList.flatMap(_.flatMap(e => checkExpressionTypes(e, scope))) ++
          windowSpec.orderBy.toList.flatMap(_.flatMap(ob => checkExpressionTypes(ob.expression, scope)))

      // 叶子节点不需要递归检查
      case _ => zero
    }
  }

  /**
   * 检查 UNION/INTERSECT/EXCEPT 对应列的类型兼容性
   */
  private def checkUnionColumnTypes(stmt: UnionStatement): List[SemanticError] = {
    // 提取左侧列类型
    val leftTypes = extractColumnTypes(stmt.left)
    val rightTypes = extractColumnTypes(stmt.right)

    if (leftTypes.isEmpty || rightTypes.isEmpty) return zero
    if (leftTypes.length != rightTypes.length) return zero  // 列数不一致由其他 Visitor 检查

    val opName = stmt.unionType match {
      case UnionAll | UnionDistinct       => "UNION"
      case IntersectAll | IntersectDistinct => "INTERSECT"
      case ExceptAll | ExceptDistinct     => "EXCEPT"
    }

    leftTypes.zip(rightTypes).zipWithIndex.flatMap { case ((lt, rt), idx) =>
      if (lt != UnknownSQLType && rt != UnknownSQLType &&
          lt != NullSQLType && rt != NullSQLType &&
          !TypeChecker.areCompatible(lt, rt)) {
        List(SemanticError(
          s"$opName column ${idx + 1} type mismatch: left is ${lt.displayName}, right is ${rt.displayName}",
          SWarning, "TYPE"
        ))
      } else zero
    }
  }

  /**
   * 提取 SELECT 语句各列的推断类型
   */
  private def extractColumnTypes(stmt: SQLStatement): List[SQLType] = stmt match {
    case s: SelectStatement =>
      val (scope, _) = ScopeBuilder.buildScope(s.from, schema)
      s.columns.map {
        case AllColumns => UnknownSQLType
        case NamedColumn(name, _) =>
          TypeInferencer.infer(Identifier(name), scope)
        case QualifiedColumn(table, column, _) =>
          TypeInferencer.infer(QualifiedIdentifier(table, column), scope)
        case ExpressionColumn(expr, _) =>
          TypeInferencer.infer(expr, scope)
      }
    case u: UnionStatement => extractColumnTypes(u.left)
    case _ => List.empty
  }
}
