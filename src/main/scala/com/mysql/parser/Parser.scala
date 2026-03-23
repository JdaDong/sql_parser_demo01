package com.mysql.parser

/**
 * 语法分析器 - 将 Token 流转换为 AST
 *
 * 支持两种构造方式：
 *   - new Parser(tokens)                     向后兼容模式（无位置信息）
 *   - new Parser(tokens, posTokens, source)  增强模式（带位置信息）
 */
class Parser(tokens: List[Token], posTokens: List[PositionedToken] = Nil, source: String = "") {
  private var position = 0

  /**
   * 生成 ParseException，如果有位置信息则使用精确定位
   */
  private def parseError(msg: String): ParseException = {
    if (posTokens.nonEmpty && source.nonEmpty) {
      ParseException.fromTokens(msg, posTokens, position, source)
    } else {
      new ParseException(msg, Position(0, 0, 0), "")
    }
  }

  /**
   * 解析 SQL 语句
   */
  def parse(): SQLStatement = {
    val stmt = currentToken() match {
      case WITH => parseWith()
      case SELECT => parseSelect()
      case INSERT => parseInsert()
      case UPDATE => parseUpdate()
      case DELETE => parseDelete()
      case CREATE => parseCreate()
      case DROP => parseDrop()
      case ALTER => parseAlter()
      case CALL => parseCall()
      case _ => throw parseError(s"Unexpected token: ${currentToken()}")
    }

    // 检查 UNION / UNION ALL（WITH 语句不再检查，因为 parseWith 内部已处理）
    stmt match {
      case _: WithStatement => stmt
      case _ => parseUnionTail(stmt)
    }
  }

  /**
   * 解析集合运算尾部（UNION / INTERSECT / EXCEPT，支持链式）
   * 如果当前 Token 不是集合运算关键字，直接返回原语句
   */
  private def parseUnionTail(left: SQLStatement): SQLStatement = {
    var result = left
    while (isSetOperator(currentToken())) {
      val unionType = parseSetOperatorType()
      consume(SELECT)  // 集合运算后面必须跟 SELECT
      // 回退一个位置，让 parseSelect() 能正常消费 SELECT
      position -= 1
      val right = parseSelect()
      result = UnionStatement(result, right, unionType)
    }
    result
  }

  /**
   * 判断当前 Token 是否是集合运算符
   */
  private def isSetOperator(token: Token): Boolean = token match {
    case UNION | INTERSECT | EXCEPT => true
    case _ => false
  }

  /**
   * 解析集合运算类型（消费 UNION/INTERSECT/EXCEPT [ALL] 关键字）
   */
  private def parseSetOperatorType(): UnionType = {
    currentToken() match {
      case UNION =>
        consume(UNION)
        if (currentToken() == ALL) {
          consume(ALL)
          UnionAll
        } else {
          UnionDistinct
        }
      case INTERSECT =>
        consume(INTERSECT)
        if (currentToken() == ALL) {
          consume(ALL)
          IntersectAll
        } else {
          IntersectDistinct
        }
      case EXCEPT =>
        consume(EXCEPT)
        if (currentToken() == ALL) {
          consume(ALL)
          ExceptAll
        } else {
          ExceptDistinct
        }
      case _ => throw parseError(s"Expected UNION, INTERSECT, or EXCEPT, but got ${currentToken()}")
    }
  }

  /**
   * 解析 SELECT 语句或 UNION 组合查询
   * 用于子查询内部，先解析 SELECT 再检查 UNION
   */
  private def parseSelectOrUnion(): SQLStatement = {
    val select = parseSelect()
    parseUnionTail(select)
  }

  /**
   * 解析 WITH 子句 (CTE — Common Table Expression)
   *
   * 语法：
   *   WITH [RECURSIVE]
   *     cte_name AS (SELECT ...),
   *     cte_name2 AS (SELECT ...)
   *   SELECT ...
   */
  private def parseWith(): WithStatement = {
    consume(WITH)

    // 可选的 RECURSIVE 关键字
    val recursive = if (currentToken() == RECURSIVE) {
      consume(RECURSIVE)
      true
    } else false

    // 解析 CTE 定义列表
    val ctes = parseCTEList()

    // 解析主查询（支持 SELECT 或 UNION）
    val query = currentToken() match {
      case SELECT =>
        val select = parseSelect()
        parseUnionTail(select)
      case _ => throw parseError(s"Expected SELECT after WITH clause, but got ${currentToken()}")
    }

    WithStatement(ctes, query, recursive)
  }

  /**
   * 解析 CTE 定义列表：name AS (query), name2 AS (query2), ...
   */
  private def parseCTEList(): List[CTEDefinition] = {
    var ctes = List[CTEDefinition]()

    do {
      if (currentToken() == COMMA) consume(COMMA)

      // CTE 名称
      val cteName = currentToken() match {
        case IdentifierToken(name) =>
          consume(currentToken())
          name
        case _ => throw parseError(s"Expected CTE name, but got ${currentToken()}")
      }

      consume(AS)
      consume(LPAREN)

      // CTE 查询体：SELECT 或 UNION
      val cteQuery = parseSelectOrUnion()

      consume(RPAREN)

      ctes = ctes :+ CTEDefinition(cteName, cteQuery)
    } while (currentToken() == COMMA)

    ctes
  }

  /**
   * 解析 SELECT 语句
   */
  private def parseSelect(): SelectStatement = {
    consume(SELECT)
    
    val distinct = if (currentToken() == DISTINCT) {
      consume(DISTINCT)
      true
    } else false

    val columns = parseColumns()
    
    val from = if (currentToken() == FROM) {
      consume(FROM)
      Some(parseTableReference())
    } else None

    val where = if (currentToken() == WHERE) {
      consume(WHERE)
      Some(parseExpression())
    } else None

    val groupBy = if (currentToken() == GROUP) {
      consume(GROUP)
      consume(BY)
      Some(parseExpressionList())
    } else None

    val having = if (currentToken() == HAVING) {
      consume(HAVING)
      Some(parseExpression())
    } else None

    val orderBy = if (currentToken() == ORDER) {
      consume(ORDER)
      consume(BY)
      Some(parseOrderByList())
    } else None

    val limit = if (currentToken() == LIMIT) {
      consume(LIMIT)
      currentToken() match {
        case NumberToken(value) =>
          consume(currentToken())
          Some(value.toInt)
        case _ => throw parseError("Expected number after LIMIT")
      }
    } else None

    val offset = if (currentToken() == OFFSET) {
      consume(OFFSET)
      currentToken() match {
        case NumberToken(value) =>
          consume(currentToken())
          Some(value.toInt)
        case _ => throw parseError("Expected number after OFFSET")
      }
    } else None

    SelectStatement(columns, from, where, orderBy, groupBy, having, limit, offset, distinct)
  }

  /**
   * 解析列列表
   */
  private def parseColumns(): List[Column] = {
    if (currentToken() == MULTIPLY_OP) {
      consume(MULTIPLY_OP)
      List(AllColumns)
    } else {
      parseColumnList()
    }
  }

  /**
   * 解析列列表（非 *）
   */
  private def parseColumnList(): List[Column] = {
    var columns = List[Column]()
    
    do {
      if (currentToken() == COMMA) consume(COMMA)
      
      val column = currentToken() match {
        // 聚合函数列：COUNT(...), SUM(...), AVG(...), MAX(...), MIN(...)
        // 可能是窗口函数：COUNT(...) OVER (...)
        case t if isAggregateToken(t) =>
          val aggExpr = parseAggregateFunction()
          // 检查是否跟随 OVER（窗口函数）
          val finalExpr = if (currentToken() == OVER) {
            val windowSpec = parseWindowSpec()
            WindowFunctionExpression(aggExpr, windowSpec)
          } else {
            aggExpr
          }
          val alias = parseOptionalAlias()
          ExpressionColumn(finalExpr, alias)

        // CASE 表达式列：CASE WHEN ... END AS alias
        case CASE =>
          val caseExpr = parseCaseExpression()
          val alias = parseOptionalAlias()
          ExpressionColumn(caseExpr, alias)

        // CAST 表达式列：CAST(expr AS type) [AS] alias
        case CAST =>
          val castExpr = parseCastExpression()
          val alias = parseOptionalAlias()
          ExpressionColumn(castExpr, alias)

        // CONVERT 表达式列：CONVERT(expr, type) [AS] alias
        case CONVERT =>
          val convertExpr = parseConvertExpression()
          val alias = parseOptionalAlias()
          ExpressionColumn(convertExpr, alias)

        // 子查询列：(SELECT ...) AS alias
        case LPAREN =>
          consume(LPAREN)
          if (currentToken() == SELECT) {
            val subquery = parseSelectOrUnion()
            consume(RPAREN)
            val alias = parseOptionalAlias()
            ExpressionColumn(SubqueryExpression(subquery), alias)
          } else {
            throw parseError("Expected SELECT in subquery column")
          }

        case IdentifierToken(name) =>
          consume(currentToken())
          
          if (currentToken() == LPAREN) {
            // 函数调用列：UPPER(name), CONCAT(a, b), ROW_NUMBER() 等
            consume(LPAREN)
            val args = if (currentToken() == RPAREN) {
              List.empty[Expression]
            } else {
              parseExpressionList()
            }
            consume(RPAREN)
            val funcExpr: Expression = FunctionCall(name.toUpperCase, args)
            // 检查是否跟随 OVER（窗口函数）
            val finalExpr = if (currentToken() == OVER) {
              val windowSpec = parseWindowSpec()
              WindowFunctionExpression(funcExpr, windowSpec)
            } else {
              funcExpr
            }
            val alias = parseOptionalAlias()
            ExpressionColumn(finalExpr, alias)
          } else if (currentToken() == DOT) {
            // 检查是否是 table.column 格式
            consume(DOT)
            val colName = currentToken() match {
              case IdentifierToken(col) =>
                consume(currentToken())
                col
              case MULTIPLY_OP =>
                consume(MULTIPLY_OP)
                consume(MULTIPLY_OP)
                "*"
              case _ => throw parseError("Expected column name after '.'")
            }
            
            val alias = parseOptionalAlias()
            QualifiedColumn(name, colName, alias)
          } else if (isExpressionOperator(currentToken())) {
            // 标识符后面跟运算符（如 n + 1, a * b），回退并解析为完整表达式
            position -= 1
            val expr = parseExpression()
            val alias = parseOptionalAlias()
            ExpressionColumn(expr, alias)
          } else {
            val alias = parseOptionalAlias()
            NamedColumn(name, alias)
          }

        // 字面量表达式列：SELECT 1, SELECT 'hello'
        case _: NumberToken | _: StringToken =>
          val expr = parseExpression()
          val alias = parseOptionalAlias()
          ExpressionColumn(expr, alias)

        case _ => throw parseError("Expected column name or aggregate function")
      }
      
      columns = columns :+ column
    } while (currentToken() == COMMA)
    
    columns
  }

  /**
   * 解析可选的 AS 别名（支持显式 AS 和隐式别名）
   *
   * 显式别名：... AS alias
   * 隐式别名：... alias（alias 不能是保留关键字）
   */
  private def parseOptionalAlias(): Option[String] = {
    if (currentToken() == AS) {
      consume(AS)
      currentToken() match {
        case IdentifierToken(a) =>
          consume(currentToken())
          Some(a)
        case _ => throw parseError("Expected alias after AS")
      }
    } else {
      // 隐式别名：当前 Token 是普通标识符（非保留关键字）
      currentToken() match {
        case IdentifierToken(a) if !isReservedKeyword(a) =>
          consume(currentToken())
          Some(a)
        case _ => None
      }
    }
  }

  /**
   * 判断标识符是否是保留关键字（不能用作隐式别名）
   */
  private def isReservedKeyword(name: String): Boolean = {
    val reserved = Set(
      "FROM", "WHERE", "ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET",
      "JOIN", "LEFT", "RIGHT", "INNER", "ON", "AND", "OR", "NOT",
      "AS", "IN", "IS", "LIKE", "BETWEEN", "EXISTS", "UNION", "ALL",
      "INTERSECT", "EXCEPT",
      "SET", "VALUES", "INTO", "SELECT", "INSERT", "UPDATE", "DELETE",
      "CREATE", "DROP", "ALTER", "TABLE", "DISTINCT",
      "CASE", "WHEN", "THEN", "ELSE", "END",
      "ASC", "DESC", "NULL",
      "CAST", "CONVERT", "USING", "SIGNED", "UNSIGNED",
      "OVER", "PARTITION", "ROWS", "RANGE", "UNBOUNDED", "PRECEDING",
      "FOLLOWING", "CURRENT", "ROW", "WITH", "RECURSIVE",
      "ADD", "COLUMN", "MODIFY", "RENAME", "TO", "CHANGE", "IF",
      "PRIMARY", "KEY", "UNIQUE", "FOREIGN", "REFERENCES", "CHECK",
      "DEFAULT", "AUTO_INCREMENT", "CONSTRAINT", "INDEX",
      "VIEW", "PROCEDURE", "CALL", "BEGIN", "RETURN", "REPLACE",
      "INOUT", "OUT"
    )
    reserved.contains(name.toUpperCase)
  }

  /**
   * 判断当前 Token 是否是表达式运算符（用于列解析中判断标识符后是否需要继续解析表达式）
   */
  private def isExpressionOperator(token: Token): Boolean = token match {
    case PLUS_OP | MINUS_OP | MULTIPLY_OP | DIVIDE_OP => true
    case EQUALS | NOT_EQUALS | LESS_THAN | GREATER_THAN | LESS_EQUAL | GREATER_EQUAL => true
    case AND | OR => true
    case _ => false
  }

  /**
   * 判断是否是聚合函数 Token
   */
  private def isAggregateToken(token: Token): Boolean = token match {
    case COUNT | SUM | AVG | MAX | MIN => true
    case _ => false
  }

  /**
   * 解析聚合函数：COUNT(*), COUNT(expr), COUNT(DISTINCT expr), SUM(expr), AVG(expr), MAX(expr), MIN(expr)
   */
  private def parseAggregateFunction(): AggregateFunction = {
    val funcType = currentToken() match {
      case COUNT => consume(COUNT); CountFunc
      case SUM   => consume(SUM);   SumFunc
      case AVG   => consume(AVG);   AvgFunc
      case MAX   => consume(MAX);   MaxFunc
      case MIN   => consume(MIN);   MinFunc
      case _ => throw parseError(s"Expected aggregate function but got ${currentToken()}")
    }
    
    consume(LPAREN)
    
    // 检查 DISTINCT 修饰符
    val distinct = if (currentToken() == DISTINCT) {
      consume(DISTINCT)
      true
    } else false
    
    // 解析参数：* 或表达式
    val argument = if (currentToken() == MULTIPLY_OP) {
      consume(MULTIPLY_OP)
      AllColumnsExpression
    } else {
      parseExpression()
    }
    
    consume(RPAREN)
    
    AggregateFunction(funcType, argument, distinct)
  }

  /**
   * 解析表引用（支持派生表子查询）
   */
  private def parseTableReference(): TableReference = {
    val left = currentToken() match {
      // 派生表：(SELECT ...) AS alias
      case LPAREN =>
        consume(LPAREN)
        if (currentToken() == SELECT) {
          val subquery = parseSelectOrUnion()
          consume(RPAREN)
          // 派生表必须有别名
          val alias = if (currentToken() == AS) {
            consume(AS)
            currentToken() match {
              case IdentifierToken(a) =>
                consume(currentToken())
                a
              case _ => throw parseError("Expected alias for derived table")
            }
          } else {
            currentToken() match {
              case IdentifierToken(a) =>
                consume(currentToken())
                a
              case _ => throw parseError("Derived table must have an alias")
            }
          }
          DerivedTable(subquery, alias)
        } else {
          throw parseError("Expected SELECT in subquery")
        }

      case IdentifierToken(name) =>
        consume(currentToken())
        val alias = if (currentToken() == AS) {
          consume(AS)
          currentToken() match {
            case IdentifierToken(a) =>
              consume(currentToken())
              Some(a)
            case _ => None
          }
        } else {
          // 隐式表别名：FROM users u（不带 AS）
          currentToken() match {
            case IdentifierToken(a) if !isReservedKeyword(a) && !isJoinToken(currentToken()) =>
              consume(currentToken())
              Some(a)
            case _ => None
          }
        }
        TableName(name, alias)
      case _ => throw parseError("Expected table name or subquery")
    }

    // 检查 JOIN
    if (isJoinToken(currentToken())) {
      val joinType = parseJoinType()
      val right = parseTableReference()
      consume(ON)
      val condition = parseExpression()
      JoinClause(left, right, joinType, condition)
    } else {
      left
    }
  }

  /**
   * 检查是否是 JOIN token
   */
  private def isJoinToken(token: Token): Boolean = token match {
    case JOIN | LEFT | RIGHT | INNER => true
    case _ => false
  }

  /**
   * 解析 JOIN 类型
   */
  private def parseJoinType(): JoinType = {
    currentToken() match {
      case LEFT =>
        consume(LEFT)
        consume(JOIN)
        LeftJoin
      case RIGHT =>
        consume(RIGHT)
        consume(JOIN)
        RightJoin
      case INNER =>
        consume(INNER)
        consume(JOIN)
        InnerJoin
      case JOIN =>
        consume(JOIN)
        InnerJoin
      case _ => throw parseError("Expected JOIN keyword")
    }
  }

  /**
   * 解析表达式
   */
  private def parseExpression(): Expression = {
    parseOrExpression()
  }

  /**
   * 解析 OR 表达式
   */
  private def parseOrExpression(): Expression = {
    var left = parseAndExpression()
    
    while (currentToken() == OR) {
      consume(OR)
      val right = parseAndExpression()
      left = BinaryExpression(left, OrOp, right)
    }
    
    left
  }

  /**
   * 解析 AND 表达式
   */
  private def parseAndExpression(): Expression = {
    var left = parseComparisonExpression()
    
    while (currentToken() == AND) {
      consume(AND)
      val right = parseComparisonExpression()
      left = BinaryExpression(left, AndOp, right)
    }
    
    left
  }

  /**
   * 解析比较表达式（含谓词：IS [NOT] NULL, [NOT] BETWEEN, [NOT] IN, [NOT] LIKE）
   */
  private def parseComparisonExpression(): Expression = {
    val left = parseAdditiveExpression()
    
    currentToken() match {
      // 常规比较运算符
      case EQUALS =>
        consume(EQUALS)
        BinaryExpression(left, Equal, parseAdditiveExpression())
      case NOT_EQUALS =>
        consume(NOT_EQUALS)
        BinaryExpression(left, NotEqual, parseAdditiveExpression())
      case LESS_THAN =>
        consume(LESS_THAN)
        BinaryExpression(left, LessThan, parseAdditiveExpression())
      case GREATER_THAN =>
        consume(GREATER_THAN)
        BinaryExpression(left, GreaterThan, parseAdditiveExpression())
      case LESS_EQUAL =>
        consume(LESS_EQUAL)
        BinaryExpression(left, LessEqual, parseAdditiveExpression())
      case GREATER_EQUAL =>
        consume(GREATER_EQUAL)
        BinaryExpression(left, GreaterEqual, parseAdditiveExpression())

      // IS [NOT] NULL
      case IS =>
        consume(IS)
        if (currentToken() == NOT) {
          consume(NOT)
          consume(NULL)
          IsNullExpression(left, negated = true)
        } else {
          consume(NULL)
          IsNullExpression(left, negated = false)
        }

      // BETWEEN lower AND upper
      case BETWEEN =>
        consume(BETWEEN)
        val lower = parseAdditiveExpression()
        consume(AND)
        val upper = parseAdditiveExpression()
        BetweenExpression(left, lower, upper, negated = false)

      // IN (值列表) 或 IN (子查询)
      case IN =>
        consume(IN)
        consume(LPAREN)
        if (currentToken() == SELECT) {
          val subquery = parseSelectOrUnion()
          consume(RPAREN)
          InSubqueryExpression(left, subquery, negated = false)
        } else {
          val values = parseExpressionList()
          consume(RPAREN)
          InExpression(left, values, negated = false)
        }

      // LIKE pattern
      case LIKE =>
        consume(LIKE)
        val pattern = parseAdditiveExpression()
        LikeExpression(left, pattern, negated = false)

      // NOT BETWEEN / NOT IN / NOT LIKE
      case NOT =>
        consume(NOT)
        currentToken() match {
          case BETWEEN =>
            consume(BETWEEN)
            val lower = parseAdditiveExpression()
            consume(AND)
            val upper = parseAdditiveExpression()
            BetweenExpression(left, lower, upper, negated = true)
          case IN =>
            consume(IN)
            consume(LPAREN)
            if (currentToken() == SELECT) {
              val subquery = parseSelectOrUnion()
              consume(RPAREN)
              InSubqueryExpression(left, subquery, negated = true)
            } else {
              val values = parseExpressionList()
              consume(RPAREN)
              InExpression(left, values, negated = true)
            }
          case LIKE =>
            consume(LIKE)
            val pattern = parseAdditiveExpression()
            LikeExpression(left, pattern, negated = true)
          case _ => throw parseError(s"Expected BETWEEN, IN or LIKE after NOT, but got ${currentToken()}")
        }

      case _ => left
    }
  }

  /**
   * 解析加减表达式
   */
  private def parseAdditiveExpression(): Expression = {
    var left = parseMultiplicativeExpression()
    
    while (currentToken() == PLUS_OP || currentToken() == MINUS_OP) {
      val op = if (currentToken() == PLUS_OP) {
        consume(PLUS_OP)
        Plus
      } else {
        consume(MINUS_OP)
        Minus
      }
      val right = parseMultiplicativeExpression()
      left = BinaryExpression(left, op, right)
    }
    
    left
  }

  /**
   * 解析乘除表达式
   */
  private def parseMultiplicativeExpression(): Expression = {
    var left = parseUnaryExpression()
    
    while (currentToken() == MULTIPLY_OP || currentToken() == DIVIDE_OP) {
      val op = if (currentToken() == MULTIPLY_OP) {
        consume(MULTIPLY_OP)
        Multiply
      } else {
        consume(DIVIDE_OP)
        Divide
      }
      val right = parseUnaryExpression()
      left = BinaryExpression(left, op, right)
    }
    
    left
  }

  /**
   * 解析一元表达式（支持 NOT EXISTS 和一元负号）
   */
  private def parseUnaryExpression(): Expression = {
    if (currentToken() == NOT) {
      consume(NOT)
      if (currentToken() == EXISTS) {
        // NOT EXISTS (SELECT ...)
        consume(EXISTS)
        consume(LPAREN)
        val subquery = parseSelectOrUnion()
        consume(RPAREN)
        ExistsExpression(subquery, negated = true)
      } else {
        UnaryExpression(NotOp, parseUnaryExpression())
      }
    } else if (currentToken() == MINUS_OP) {
      // 一元负号：-expr（如 -1, -salary）
      consume(MINUS_OP)
      val expr = parsePrimaryExpression()
      // 如果是数字字面量，直接合并为负数
      expr match {
        case NumberLiteral(value) => NumberLiteral("-" + value)
        case other => BinaryExpression(NumberLiteral("0"), Minus, other)
      }
    } else {
      parsePrimaryExpression()
    }
  }

  /**
   * 解析基本表达式（支持子查询、EXISTS、CASE WHEN、CAST、CONVERT、函数调用）
   */
  private def parsePrimaryExpression(): Expression = {
    currentToken() match {
      // CASE WHEN ... THEN ... ELSE ... END
      case CASE =>
        parseCaseExpression()

      // CAST(expression AS type)
      case CAST =>
        parseCastExpression()

      // CONVERT(expression, type) 或 CONVERT(expression USING charset)
      case CONVERT =>
        parseConvertExpression()

      // EXISTS (SELECT ...)
      case EXISTS =>
        consume(EXISTS)
        consume(LPAREN)
        val subquery = parseSelectOrUnion()
        consume(RPAREN)
        ExistsExpression(subquery)
      // 聚合函数在表达式上下文中（如 HAVING COUNT(*) > 5）
      // 可能是窗口函数：COUNT(*) OVER (...)
      case t if isAggregateToken(t) =>
        val aggExpr = parseAggregateFunction()
        if (currentToken() == OVER) {
          val windowSpec = parseWindowSpec()
          WindowFunctionExpression(aggExpr, windowSpec)
        } else {
          aggExpr
        }
      case IdentifierToken(name) =>
        consume(currentToken())
        if (currentToken() == LPAREN) {
          // 函数调用：name(arg1, arg2, ...)
          consume(LPAREN)
          val args = if (currentToken() == RPAREN) {
            List.empty[Expression]  // 无参函数如 NOW()
          } else {
            parseExpressionList()
          }
          consume(RPAREN)
          val funcExpr: Expression = FunctionCall(name.toUpperCase, args)
          // 检查是否跟随 OVER（窗口函数）
          if (currentToken() == OVER) {
            val windowSpec = parseWindowSpec()
            WindowFunctionExpression(funcExpr, windowSpec)
          } else {
            funcExpr
          }
        } else if (currentToken() == DOT) {
          consume(DOT)
          currentToken() match {
            case IdentifierToken(col) =>
              consume(currentToken())
              QualifiedIdentifier(name, col)
            case _ => throw parseError("Expected column name after '.'")
          }
        } else {
          Identifier(name)
        }
      case StringToken(value) =>
        consume(currentToken())
        StringLiteral(value)
      case NumberToken(value) =>
        consume(currentToken())
        NumberLiteral(value)
      case NULL =>
        consume(NULL)
        NullLiteral
      case LPAREN =>
        consume(LPAREN)
        if (currentToken() == SELECT) {
          // 子查询：(SELECT ...) 或 (SELECT ... UNION SELECT ...)
          val subquery = parseSelectOrUnion()
          consume(RPAREN)
          SubqueryExpression(subquery)
        } else {
          // 普通括号表达式：(a + b)
          val expr = parseExpression()
          consume(RPAREN)
          expr
        }
      case _ => throw parseError(s"Unexpected token in expression: ${currentToken()}")
    }
  }

  /**
   * 解析 CAST(expression AS type)
   */
  private def parseCastExpression(): CastExpression = {
    consume(CAST)
    consume(LPAREN)
    val expr = parseExpression()
    consume(AS)
    val targetType = parseCastType()
    consume(RPAREN)
    CastExpression(expr, targetType)
  }

  /**
   * 解析 CONVERT(expression, type) 或 CONVERT(expression USING charset)
   */
  private def parseConvertExpression(): ConvertExpression = {
    consume(CONVERT)
    consume(LPAREN)
    val expr = parseExpression()

    currentToken() match {
      case USING =>
        // CONVERT(expression USING charset)
        consume(USING)
        val charset = currentToken() match {
          case IdentifierToken(name) =>
            consume(currentToken())
            name
          case _ => throw parseError("Expected charset name after USING")
        }
        consume(RPAREN)
        ConvertExpression(expr, None, Some(charset))

      case COMMA =>
        // CONVERT(expression, type)
        consume(COMMA)
        val targetType = parseCastType()
        consume(RPAREN)
        ConvertExpression(expr, Some(targetType), None)

      case _ => throw parseError(s"Expected USING or ',' in CONVERT, but got ${currentToken()}")
    }
  }

  /**
   * 解析 CAST/CONVERT 目标类型
   */
  private def parseCastType(): CastType = {
    currentToken() match {
      case SIGNED =>
        consume(SIGNED)
        val isInt = currentToken() match {
          case INT =>
            consume(INT)
            true
          case INTEGER =>
            consume(INTEGER)
            true
          case _ => false
        }
        SignedCastType(isInt)

      case UNSIGNED =>
        consume(UNSIGNED)
        val isInt = currentToken() match {
          case INT =>
            consume(INT)
            true
          case INTEGER =>
            consume(INTEGER)
            true
          case _ => false
        }
        UnsignedCastType(isInt)

      case CHAR =>
        consume(CHAR)
        val size = if (currentToken() == LPAREN) {
          consume(LPAREN)
          val n = currentToken() match {
            case NumberToken(v) =>
              consume(currentToken())
              v.toInt
            case _ => throw parseError("Expected number in CHAR size")
          }
          consume(RPAREN)
          Some(n)
        } else None
        CharCastType(size)

      case VARCHAR =>
        consume(VARCHAR)
        consume(LPAREN)
        val size = currentToken() match {
          case NumberToken(v) =>
            consume(currentToken())
            v.toInt
          case _ => throw parseError("Expected number in VARCHAR size")
        }
        consume(RPAREN)
        VarcharCastType(size)

      case DECIMAL =>
        consume(DECIMAL)
        val (precision, scale) = if (currentToken() == LPAREN) {
          consume(LPAREN)
          val p = currentToken() match {
            case NumberToken(v) =>
              consume(currentToken())
              v.toInt
            case _ => throw parseError("Expected number in DECIMAL precision")
          }
          val s = if (currentToken() == COMMA) {
            consume(COMMA)
            currentToken() match {
              case NumberToken(v) =>
                consume(currentToken())
                Some(v.toInt)
              case _ => throw parseError("Expected number in DECIMAL scale")
            }
          } else None
          consume(RPAREN)
          (Some(p), s)
        } else (None, None)
        DecimalCastType(precision, scale)

      case DATE =>
        consume(DATE)
        DateCastType

      case DATETIME =>
        consume(DATETIME)
        DateTimeCastType

      case INT =>
        consume(INT)
        IntCastType

      case BOOLEAN =>
        consume(BOOLEAN)
        BooleanCastType

      case _ => throw parseError(s"Unknown cast type: ${currentToken()}")
    }
  }

  /**
   * 解析 CASE 表达式
   * 搜索式：CASE WHEN condition THEN result ... [ELSE default] END
   * 简单式：CASE operand WHEN value THEN result ... [ELSE default] END
   */
  private def parseCaseExpression(): CaseExpression = {
    consume(CASE)

    // 判断是搜索式还是简单式：CASE 后紧跟 WHEN 则为搜索式
    val operand = if (currentToken() != WHEN) {
      Some(parseExpression())  // 简单式：先解析操作数
    } else {
      None                     // 搜索式
    }

    // 解析 WHEN-THEN 分支（至少一个）
    var whenClauses = List[WhenClause]()
    while (currentToken() == WHEN) {
      consume(WHEN)
      val condition = parseExpression()
      consume(THEN)
      val result = parseExpression()
      whenClauses = whenClauses :+ WhenClause(condition, result)
    }

    if (whenClauses.isEmpty) {
      throw parseError("CASE expression must have at least one WHEN clause")
    }

    // 解析可选的 ELSE
    val elseResult = if (currentToken() == ELSE) {
      consume(ELSE)
      Some(parseExpression())
    } else {
      None
    }

    consume(END)

    CaseExpression(operand, whenClauses, elseResult)
  }

  /**
   * 解析表达式列表
   */
  private def parseExpressionList(): List[Expression] = {
    var expressions = List[Expression]()
    
    do {
      if (currentToken() == COMMA) consume(COMMA)
      expressions = expressions :+ parseExpression()
    } while (currentToken() == COMMA)
    
    expressions
  }

  /**
   * 解析 ORDER BY 列表
   */
  private def parseOrderByList(): List[OrderByClause] = {
    var orderBys = List[OrderByClause]()
    
    do {
      if (currentToken() == COMMA) consume(COMMA)
      val expr = parseExpression()
      // 解析排序方向：ASC（默认） / DESC
      val ascending = currentToken() match {
        case ASC =>
          consume(ASC)
          true
        case DESC =>
          consume(DESC)
          false
        case _ => true  // 默认升序
      }
      orderBys = orderBys :+ OrderByClause(expr, ascending)
    } while (currentToken() == COMMA)
    
    orderBys
  }

  // ============================================================
  //  窗口函数解析
  // ============================================================

  /**
   * 解析 OVER 子句（窗口规格）
   *
   * 语法：OVER ([PARTITION BY expr, ...] [ORDER BY expr [ASC|DESC], ...] [frame_clause])
   *
   * frame_clause:
   *   {ROWS | RANGE} frame_start
   *   {ROWS | RANGE} BETWEEN frame_start AND frame_end
   *
   * frame_start / frame_end:
   *   UNBOUNDED PRECEDING | N PRECEDING | CURRENT ROW | N FOLLOWING | UNBOUNDED FOLLOWING
   */
  private def parseWindowSpec(): WindowSpec = {
    consume(OVER)
    consume(LPAREN)

    // PARTITION BY
    val partitionBy = if (currentToken() == PARTITION) {
      consume(PARTITION)
      consume(BY)
      Some(parseExpressionList())
    } else None

    // ORDER BY
    val orderBy = if (currentToken() == ORDER) {
      consume(ORDER)
      consume(BY)
      Some(parseOrderByList())
    } else None

    // Frame clause (ROWS / RANGE)
    val frame = if (currentToken() == ROWS || currentToken() == RANGE) {
      val frameType = if (currentToken() == ROWS) {
        consume(ROWS)
        RowsFrame
      } else {
        consume(RANGE)
        RangeFrame
      }

      if (currentToken() == BETWEEN) {
        // BETWEEN frame_start AND frame_end
        consume(BETWEEN)
        val start = parseFrameBound()
        consume(AND)
        val end = parseFrameBound()
        Some(WindowFrame(frameType, start, Some(end)))
      } else {
        // 单边界：frame_start
        val start = parseFrameBound()
        Some(WindowFrame(frameType, start, None))
      }
    } else None

    consume(RPAREN)

    WindowSpec(partitionBy, orderBy, frame)
  }

  /**
   * 解析窗口帧边界
   *
   * UNBOUNDED PRECEDING | UNBOUNDED FOLLOWING | CURRENT ROW | N PRECEDING | N FOLLOWING
   */
  private def parseFrameBound(): FrameBound = {
    currentToken() match {
      case UNBOUNDED =>
        consume(UNBOUNDED)
        currentToken() match {
          case PRECEDING =>
            consume(PRECEDING)
            UnboundedPreceding
          case FOLLOWING =>
            consume(FOLLOWING)
            UnboundedFollowing
          case _ => throw parseError(s"Expected PRECEDING or FOLLOWING after UNBOUNDED, but got ${currentToken()}")
        }
      case CURRENT =>
        consume(CURRENT)
        consume(ROW)
        CurrentRowBound
      case NumberToken(value) =>
        consume(currentToken())
        val n = value.toInt
        currentToken() match {
          case PRECEDING =>
            consume(PRECEDING)
            PrecedingBound(n)
          case FOLLOWING =>
            consume(FOLLOWING)
            FollowingBound(n)
          case _ => throw parseError(s"Expected PRECEDING or FOLLOWING after number, but got ${currentToken()}")
        }
      case _ => throw parseError(s"Expected frame bound (UNBOUNDED/CURRENT/N), but got ${currentToken()}")
    }
  }

  /**
   * 解析 INSERT 语句
   */
  private def parseInsert(): InsertStatement = {
    consume(INSERT)
    consume(INTO)
    
    val tableName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError("Expected table name")
    }

    val columns = if (currentToken() == LPAREN) {
      consume(LPAREN)
      val cols = parseIdentifierList()
      consume(RPAREN)
      Some(cols)
    } else None

    consume(VALUES)
    
    val values = parseValuesList()

    InsertStatement(tableName, columns, values)
  }

  /**
   * 解析标识符列表
   */
  private def parseIdentifierList(): List[String] = {
    var identifiers = List[String]()
    
    do {
      if (currentToken() == COMMA) consume(COMMA)
      currentToken() match {
        case IdentifierToken(name) =>
          consume(currentToken())
          identifiers = identifiers :+ name
        case _ => throw parseError("Expected identifier")
      }
    } while (currentToken() == COMMA)
    
    identifiers
  }

  /**
   * 解析 VALUES 列表
   */
  private def parseValuesList(): List[List[Expression]] = {
    var valuesList = List[List[Expression]]()
    
    do {
      if (currentToken() == COMMA) consume(COMMA)
      consume(LPAREN)
      val values = parseExpressionList()
      consume(RPAREN)
      valuesList = valuesList :+ values
    } while (currentToken() == COMMA)
    
    valuesList
  }

  /**
   * 解析 UPDATE 语句
   */
  private def parseUpdate(): UpdateStatement = {
    consume(UPDATE)
    
    val tableName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError("Expected table name")
    }

    consume(SET)
    val assignments = parseAssignmentList()

    val where = if (currentToken() == WHERE) {
      consume(WHERE)
      Some(parseExpression())
    } else None

    UpdateStatement(tableName, assignments, where)
  }

  /**
   * 解析赋值列表
   */
  private def parseAssignmentList(): List[(String, Expression)] = {
    var assignments = List[(String, Expression)]()
    
    do {
      if (currentToken() == COMMA) consume(COMMA)
      
      val columnName = currentToken() match {
        case IdentifierToken(name) =>
          consume(currentToken())
          name
        case _ => throw parseError("Expected column name")
      }
      
      consume(EQUALS)
      val value = parseExpression()
      
      assignments = assignments :+ (columnName, value)
    } while (currentToken() == COMMA)
    
    assignments
  }

  /**
   * 解析 DELETE 语句
   */
  private def parseDelete(): DeleteStatement = {
    consume(DELETE)
    consume(FROM)
    
    val tableName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError("Expected table name")
    }

    val where = if (currentToken() == WHERE) {
      consume(WHERE)
      Some(parseExpression())
    } else None

    DeleteStatement(tableName, where)
  }

  /**
   * 解析 CREATE TABLE 语句
   */
  private def parseCreate(): SQLStatement = {
    consume(CREATE)

    currentToken() match {
      case UNIQUE =>
        // CREATE UNIQUE INDEX ...
        consume(UNIQUE)
        consume(INDEX)
        parseCreateIndexBody(unique = true)
      case INDEX =>
        // CREATE INDEX ...
        consume(INDEX)
        parseCreateIndexBody(unique = false)
      case TABLE =>
        consume(TABLE)
        parseCreateTableBody()
      case VIEW =>
        // CREATE VIEW name AS query
        parseCreateView(orReplace = false)
      case OR =>
        // CREATE OR REPLACE VIEW name AS query
        consume(OR)
        consume(REPLACE)
        consume(VIEW)
        parseCreateViewBody(orReplace = true)
      case REPLACE =>
        // CREATE REPLACE → 不太标准，但兼容
        consume(REPLACE)
        consume(VIEW)
        parseCreateViewBody(orReplace = true)
      case PROCEDURE =>
        // CREATE PROCEDURE name (params) BEGIN ... END
        parseCreateProcedure()
      case _ => throw parseError(s"Expected TABLE, INDEX, UNIQUE, VIEW, or PROCEDURE after CREATE, but got ${currentToken()}")
    }
  }

  /**
   * 解析 CREATE TABLE 主体（TABLE 关键字已消费）
   */
  private def parseCreateTableBody(): CreateTableStatement = {
    val tableName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError("Expected table name")
    }

    consume(LPAREN)
    val (columns, constraints) = parseColumnDefinitionsAndConstraints()
    consume(RPAREN)

    CreateTableStatement(tableName, columns, constraints)
  }

  /**
   * 解析 CREATE INDEX 主体（INDEX 关键字已消费）
   */
  private def parseCreateIndexBody(unique: Boolean): CreateIndexStatement = {
    val indexName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError("Expected index name")
    }

    consume(ON)

    val tableName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError("Expected table name after ON")
    }

    consume(LPAREN)
    val columns = parseIndexColumnList()
    consume(RPAREN)

    CreateIndexStatement(indexName, tableName, columns, unique)
  }

  /**
   * 解析索引列列表：col1 [ASC|DESC], col2 [ASC|DESC], ...
   */
  private def parseIndexColumnList(): List[IndexColumn] = {
    var cols = List[IndexColumn]()
    do {
      if (currentToken() == COMMA) consume(COMMA)
      val colName = currentToken() match {
        case IdentifierToken(name) =>
          consume(currentToken())
          name
        case _ => throw parseError("Expected column name in index definition")
      }
      val ascending = currentToken() match {
        case ASC => consume(ASC); true
        case DESC => consume(DESC); false
        case _ => true // 默认升序
      }
      cols = cols :+ IndexColumn(colName, ascending)
    } while (currentToken() == COMMA)
    cols
  }

  /**
   * 解析列定义列表 + 表级约束
   * 返回 (列定义列表, 表级约束列表)
   */
  private def parseColumnDefinitionsAndConstraints(): (List[ColumnDefinition], List[TableConstraint]) = {
    var columns = List[ColumnDefinition]()
    var constraints = List[TableConstraint]()

    do {
      if (currentToken() == COMMA) consume(COMMA)

      // 判断是否是表级约束（以 CONSTRAINT / PRIMARY / UNIQUE / FOREIGN / CHECK 开头）
      currentToken() match {
        case CONSTRAINT | PRIMARY | UNIQUE | FOREIGN | CHECK if isTableConstraintStart() =>
          constraints = constraints :+ parseTableConstraint()
        case _ =>
          columns = columns :+ parseColumnDefinition()
      }
    } while (currentToken() == COMMA)

    (columns, constraints)
  }

  /**
   * 判断当前位置是否为表级约束开始
   */
  private def isTableConstraintStart(): Boolean = {
    currentToken() match {
      case CONSTRAINT => true
      case PRIMARY => true    // PRIMARY KEY (...)
      case FOREIGN => true    // FOREIGN KEY (...)
      case CHECK => true      // CHECK (...)
      case UNIQUE =>
        // UNIQUE 可能是列约束也可能是表级约束
        // 如果后面是 KEY 或 LPAREN 或 CONSTRAINT 名，则是表级约束
        // 否则是列类型（不太可能），需要前瞻
        val savedPos = position
        position += 1 // 跳过 UNIQUE
        val next = currentToken()
        position = savedPos
        next == KEY || next == LPAREN || next.isInstanceOf[IdentifierToken]
      case _ => false
    }
  }

  /**
   * 解析单个列定义：name TYPE [NOT NULL] [PRIMARY KEY] [UNIQUE] [DEFAULT expr] [AUTO_INCREMENT] [REFERENCES tbl(col)] [CHECK (expr)]
   */
  private def parseColumnDefinition(): ColumnDefinition = {
    val columnName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError(s"Expected column name, but got ${currentToken()}")
    }

    val dataType = parseDataType()
    val colConstraints = parseColumnConstraints()

    ColumnDefinition(columnName, dataType, colConstraints)
  }

  /**
   * 解析列级约束列表
   */
  private def parseColumnConstraints(): List[ColumnConstraint] = {
    var constraints = List[ColumnConstraint]()

    var continue = true
    while (continue) {
      currentToken() match {
        case NOT =>
          consume(NOT)
          consume(NULL)
          constraints = constraints :+ NotNullConstraint
        case PRIMARY =>
          consume(PRIMARY)
          consume(KEY)
          constraints = constraints :+ PrimaryKeyConstraint
        case UNIQUE =>
          // 列级 UNIQUE（如果后面不是 KEY + LPAREN 的话）
          consume(UNIQUE)
          constraints = constraints :+ UniqueConstraint
        case AUTO_INCREMENT =>
          consume(AUTO_INCREMENT)
          constraints = constraints :+ AutoIncrementConstraint
        case DEFAULT =>
          consume(DEFAULT)
          val value = parsePrimaryExpression()
          constraints = constraints :+ DefaultConstraint(value)
        case CHECK =>
          consume(CHECK)
          consume(LPAREN)
          val condition = parseExpression()
          consume(RPAREN)
          constraints = constraints :+ CheckColumnConstraint(condition)
        case REFERENCES =>
          consume(REFERENCES)
          val refTable = currentToken() match {
            case IdentifierToken(name) =>
              consume(currentToken())
              name
            case _ => throw parseError("Expected table name after REFERENCES")
          }
          consume(LPAREN)
          val refColumn = currentToken() match {
            case IdentifierToken(name) =>
              consume(currentToken())
              name
            case _ => throw parseError("Expected column name in REFERENCES")
          }
          consume(RPAREN)
          constraints = constraints :+ ReferencesConstraint(refTable, refColumn)
        case _ =>
          continue = false
      }
    }

    constraints
  }

  /**
   * 解析表级约束：
   *   [CONSTRAINT name] PRIMARY KEY (col, ...)
   *   [CONSTRAINT name] UNIQUE [KEY] (col, ...)
   *   [CONSTRAINT name] FOREIGN KEY (col, ...) REFERENCES table (col, ...)
   *   [CONSTRAINT name] CHECK (condition)
   */
  private def parseTableConstraint(): TableConstraint = {
    // 可选的 CONSTRAINT name
    val constraintName = if (currentToken() == CONSTRAINT) {
      consume(CONSTRAINT)
      currentToken() match {
        case IdentifierToken(name) =>
          consume(currentToken())
          Some(name)
        case _ => throw parseError("Expected constraint name after CONSTRAINT")
      }
    } else None

    currentToken() match {
      case PRIMARY =>
        consume(PRIMARY)
        consume(KEY)
        consume(LPAREN)
        val cols = parseIdentifierList()
        consume(RPAREN)
        PrimaryKeyTableConstraint(constraintName, cols)

      case UNIQUE =>
        consume(UNIQUE)
        if (currentToken() == KEY) consume(KEY)
        consume(LPAREN)
        val cols = parseIdentifierList()
        consume(RPAREN)
        UniqueTableConstraint(constraintName, cols)

      case FOREIGN =>
        consume(FOREIGN)
        consume(KEY)
        consume(LPAREN)
        val cols = parseIdentifierList()
        consume(RPAREN)
        consume(REFERENCES)
        val refTable = currentToken() match {
          case IdentifierToken(name) =>
            consume(currentToken())
            name
          case _ => throw parseError("Expected table name after REFERENCES")
        }
        consume(LPAREN)
        val refCols = parseIdentifierList()
        consume(RPAREN)
        ForeignKeyTableConstraint(constraintName, cols, refTable, refCols)

      case CHECK =>
        consume(CHECK)
        consume(LPAREN)
        val condition = parseExpression()
        consume(RPAREN)
        CheckTableConstraint(constraintName, condition)

      case _ => throw parseError(s"Expected constraint type (PRIMARY, UNIQUE, FOREIGN, CHECK), but got ${currentToken()}")
    }
  }

  /**
   * 解析数据类型
   */
  private def parseDataType(): DataType = {
    currentToken() match {
      case INT =>
        consume(INT)
        if (currentToken() == LPAREN) {
          consume(LPAREN)
          val size = currentToken() match {
            case NumberToken(value) =>
              consume(currentToken())
              value.toInt
            case _ => throw parseError("Expected number")
          }
          consume(RPAREN)
          IntType(Some(size))
        } else {
          IntType()
        }
      case BIGINT =>
        consume(BIGINT)
        if (currentToken() == LPAREN) {
          consume(LPAREN)
          val size = currentToken() match {
            case NumberToken(value) =>
              consume(currentToken())
              value.toInt
            case _ => throw parseError("Expected number")
          }
          consume(RPAREN)
          BigIntType(Some(size))
        } else {
          BigIntType()
        }
      case SMALLINT =>
        consume(SMALLINT)
        if (currentToken() == LPAREN) {
          consume(LPAREN)
          val size = currentToken() match {
            case NumberToken(value) =>
              consume(currentToken())
              value.toInt
            case _ => throw parseError("Expected number")
          }
          consume(RPAREN)
          SmallIntType(Some(size))
        } else {
          SmallIntType()
        }
      case VARCHAR =>
        consume(VARCHAR)
        consume(LPAREN)
        val size = currentToken() match {
          case NumberToken(value) =>
            consume(currentToken())
            value.toInt
          case _ => throw parseError("Expected number")
        }
        consume(RPAREN)
        VarcharType(size)
      case TEXT =>
        consume(TEXT)
        TextType
      case DATETIME =>
        consume(DATETIME)
        DateTimeType
      case TIMESTAMP =>
        consume(TIMESTAMP)
        TimestampType
      case BOOLEAN =>
        consume(BOOLEAN)
        BooleanType
      case FLOAT =>
        consume(FLOAT)
        FloatType
      case DOUBLE =>
        consume(DOUBLE)
        DoubleType
      case DECIMAL =>
        consume(DECIMAL)
        if (currentToken() == LPAREN) {
          consume(LPAREN)
          val precision = currentToken() match {
            case NumberToken(value) =>
              consume(currentToken())
              value.toInt
            case _ => throw parseError("Expected number")
          }
          val scale = if (currentToken() == COMMA) {
            consume(COMMA)
            currentToken() match {
              case NumberToken(value) =>
                consume(currentToken())
                Some(value.toInt)
              case _ => throw parseError("Expected number")
            }
          } else None
          consume(RPAREN)
          DecimalDataType(Some(precision), scale)
        } else {
          DecimalDataType()
        }
      case _ => throw parseError(s"Unknown data type: ${currentToken()}")
    }
  }

  /**
   * 解析 DROP 语句：DROP TABLE [IF EXISTS] name / DROP INDEX name ON table
   */
  private def parseDrop(): SQLStatement = {
    consume(DROP)

    currentToken() match {
      case TABLE =>
        consume(TABLE)
        val ifExists = if (currentToken() == IF) {
          consume(IF)
          consume(EXISTS)
          true
        } else false
        val tableName = currentToken() match {
          case IdentifierToken(name) =>
            consume(currentToken())
            name
          case _ => throw parseError("Expected table name")
        }
        DropTableStatement(tableName, ifExists)

      case INDEX =>
        consume(INDEX)
        val indexName = currentToken() match {
          case IdentifierToken(name) =>
            consume(currentToken())
            name
          case _ => throw parseError("Expected index name")
        }
        consume(ON)
        val tableName = currentToken() match {
          case IdentifierToken(name) =>
            consume(currentToken())
            name
          case _ => throw parseError("Expected table name after ON")
        }
        DropIndexStatement(indexName, tableName)

      case VIEW =>
        consume(VIEW)
        val ifExists = if (currentToken() == IF) {
          consume(IF)
          consume(EXISTS)
          true
        } else false
        val viewName = currentToken() match {
          case IdentifierToken(name) =>
            consume(currentToken())
            name
          case _ => throw parseError("Expected view name")
        }
        DropViewStatement(viewName, ifExists)

      case PROCEDURE =>
        consume(PROCEDURE)
        val ifExists = if (currentToken() == IF) {
          consume(IF)
          consume(EXISTS)
          true
        } else false
        val procName = currentToken() match {
          case IdentifierToken(name) =>
            consume(currentToken())
            name
          case _ => throw parseError("Expected procedure name")
        }
        DropProcedureStatement(procName, ifExists)

      case _ => throw parseError(s"Expected TABLE, INDEX, VIEW, or PROCEDURE after DROP, but got ${currentToken()}")
    }
  }

  // ============================================================
  //  ALTER TABLE 解析
  // ============================================================

  /**
   * 解析 ALTER TABLE 语句
   *
   * ALTER TABLE table_name
   *   ADD [COLUMN] name TYPE [constraints]
   *   DROP [COLUMN] name
   *   MODIFY [COLUMN] name TYPE [constraints]
   *   CHANGE [COLUMN] old_name new_name TYPE [constraints]
   *   RENAME TO new_table_name
   *   ADD [CONSTRAINT name] PRIMARY KEY / UNIQUE / FOREIGN KEY / CHECK
   *   DROP PRIMARY KEY
   *   DROP INDEX name
   *   DROP FOREIGN KEY name
   */
  private def parseAlter(): AlterTableStatement = {
    consume(ALTER)
    consume(TABLE)

    val tableName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError("Expected table name after ALTER TABLE")
    }

    var actions = List[AlterAction]()

    do {
      if (currentToken() == COMMA) consume(COMMA)
      actions = actions :+ parseAlterAction()
    } while (currentToken() == COMMA)

    AlterTableStatement(tableName, actions)
  }

  /**
   * 解析单个 ALTER TABLE 操作
   */
  private def parseAlterAction(): AlterAction = {
    currentToken() match {
      case ADD =>
        consume(ADD)
        // ADD CONSTRAINT ... / ADD PRIMARY KEY / ADD UNIQUE / ADD FOREIGN KEY / ADD CHECK
        currentToken() match {
          case CONSTRAINT | PRIMARY | FOREIGN | CHECK =>
            AddConstraintAction(parseTableConstraint())
          case UNIQUE =>
            // 判断是 ADD UNIQUE KEY/INDEX(表级约束) 还是 ADD UNIQUE 列？
            // 在 ALTER TABLE 上下文中，UNIQUE 后跟 KEY/LPAREN/CONSTRAINT 是约束
            val savedPos = position
            position += 1
            val next = currentToken()
            position = savedPos
            if (next == KEY || next == LPAREN) {
              AddConstraintAction(parseTableConstraint())
            } else {
              // ADD [COLUMN] ...
              if (currentToken() == COLUMN_KW) consume(COLUMN_KW)
              AddColumnAction(parseColumnDefinition())
            }
          case INDEX =>
            // ADD INDEX name (col, ...) — 当作 UNIQUE(false) 约束添加
            consume(INDEX)
            val indexName = currentToken() match {
              case IdentifierToken(name) =>
                consume(currentToken())
                Some(name)
              case _ => None
            }
            consume(LPAREN)
            val cols = parseIdentifierList()
            consume(RPAREN)
            AddConstraintAction(UniqueTableConstraint(indexName, cols)) // 简化处理
          case _ =>
            // ADD [COLUMN] column_definition
            if (currentToken() == COLUMN_KW) consume(COLUMN_KW)
            AddColumnAction(parseColumnDefinition())
        }

      case DROP =>
        consume(DROP)
        currentToken() match {
          case COLUMN_KW =>
            consume(COLUMN_KW)
            val colName = currentToken() match {
              case IdentifierToken(name) =>
                consume(currentToken())
                name
              case _ => throw parseError("Expected column name after DROP COLUMN")
            }
            DropColumnAction(colName)
          case PRIMARY =>
            consume(PRIMARY)
            consume(KEY)
            DropConstraintAction("PRIMARY KEY")
          case INDEX =>
            consume(INDEX)
            val idxName = currentToken() match {
              case IdentifierToken(name) =>
                consume(currentToken())
                name
              case _ => throw parseError("Expected index name after DROP INDEX")
            }
            DropConstraintAction("INDEX", Some(idxName))
          case FOREIGN =>
            consume(FOREIGN)
            consume(KEY)
            val fkName = currentToken() match {
              case IdentifierToken(name) =>
                consume(currentToken())
                name
              case _ => throw parseError("Expected foreign key name after DROP FOREIGN KEY")
            }
            DropConstraintAction("FOREIGN KEY", Some(fkName))
          case IdentifierToken(name) =>
            // DROP column_name (省略 COLUMN 关键字)
            consume(currentToken())
            DropColumnAction(name)
          case _ => throw parseError(s"Expected COLUMN, PRIMARY, INDEX, FOREIGN, or column name after DROP, but got ${currentToken()}")
        }

      case MODIFY =>
        consume(MODIFY)
        if (currentToken() == COLUMN_KW) consume(COLUMN_KW)
        ModifyColumnAction(parseColumnDefinition())

      case CHANGE =>
        consume(CHANGE)
        if (currentToken() == COLUMN_KW) consume(COLUMN_KW)
        val oldName = currentToken() match {
          case IdentifierToken(name) =>
            consume(currentToken())
            name
          case _ => throw parseError("Expected old column name after CHANGE")
        }
        val newCol = parseColumnDefinition()
        ChangeColumnAction(oldName, newCol)

      case RENAME =>
        consume(RENAME)
        if (currentToken() == TO) consume(TO)
        val newName = currentToken() match {
          case IdentifierToken(name) =>
            consume(currentToken())
            name
          case _ => throw parseError("Expected new table name after RENAME [TO]")
        }
        RenameTableAction(newName)

      case _ => throw parseError(s"Expected ALTER action (ADD, DROP, MODIFY, CHANGE, RENAME), but got ${currentToken()}")
    }
  }

  // ============================================================
  //  VIEW 解析
  // ============================================================

  /**
   * 解析 CREATE VIEW（VIEW 关键字尚未消费）
   */
  private def parseCreateView(orReplace: Boolean): CreateViewStatement = {
    consume(VIEW)
    parseCreateViewBody(orReplace)
  }

  /**
   * 解析 CREATE VIEW 主体（VIEW 关键字已消费）
   * CREATE [OR REPLACE] VIEW name AS query
   */
  private def parseCreateViewBody(orReplace: Boolean): CreateViewStatement = {
    val viewName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError("Expected view name after VIEW")
    }

    consume(AS)

    // 视图查询（支持 SELECT 或 UNION）
    val query = currentToken() match {
      case SELECT =>
        val select = parseSelect()
        parseUnionTail(select)
      case _ => throw parseError(s"Expected SELECT after AS, but got ${currentToken()}")
    }

    CreateViewStatement(viewName, query, orReplace)
  }

  // ============================================================
  //  PROCEDURE 解析
  // ============================================================

  /**
   * 解析 CREATE PROCEDURE
   * CREATE PROCEDURE name (params) BEGIN stmt1; stmt2; ... END
   */
  private def parseCreateProcedure(): CreateProcedureStatement = {
    consume(PROCEDURE)

    val procName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError("Expected procedure name after PROCEDURE")
    }

    // 参数列表
    consume(LPAREN)
    val params = if (currentToken() == RPAREN) {
      Nil
    } else {
      parseProcedureParams()
    }
    consume(RPAREN)

    // 过程体：BEGIN ... END
    consume(BEGIN_KW)

    val body = parseProcedureBody()

    consume(END)

    CreateProcedureStatement(procName, params, body)
  }

  /**
   * 解析存储过程参数列表
   * [IN|OUT|INOUT] name TYPE, ...
   */
  private def parseProcedureParams(): List[ProcedureParam] = {
    var params = List[ProcedureParam]()

    do {
      if (currentToken() == COMMA) consume(COMMA)

      // 可选的参数模式：IN, OUT, INOUT
      val mode: ParamMode = currentToken() match {
        case IN =>
          consume(IN)
          InParam
        case OUT =>
          consume(OUT)
          OutParam
        case INOUT =>
          consume(INOUT)
          InOutParam
        case _ => InParam // 默认 IN
      }

      val paramName = currentToken() match {
        case IdentifierToken(name) =>
          consume(currentToken())
          name
        case _ => throw parseError(s"Expected parameter name, but got ${currentToken()}")
      }

      val paramType = parseDataType()

      params = params :+ ProcedureParam(mode, paramName, paramType)
    } while (currentToken() == COMMA)

    params
  }

  /**
   * 解析过程体（BEGIN 和 END 之间的语句列表）
   */
  private def parseProcedureBody(): List[SQLStatement] = {
    var stmts = List[SQLStatement]()

    while (currentToken() != END) {
      val stmt = currentToken() match {
        case SELECT => parseSelect()
        case INSERT => parseInsert()
        case UPDATE => parseUpdate()
        case DELETE => parseDelete()
        case _ => throw parseError(s"Unexpected token in procedure body: ${currentToken()}")
      }
      stmts = stmts :+ stmt

      // 跳过可选的分号
      if (currentToken() == SEMICOLON) consume(SEMICOLON)
    }

    stmts
  }

  // ============================================================
  //  CALL 解析
  // ============================================================

  /**
   * 解析 CALL 语句
   * CALL procedure_name(arg1, arg2, ...)
   */
  private def parseCall(): CallStatement = {
    consume(CALL)

    val procName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw parseError("Expected procedure name after CALL")
    }

    consume(LPAREN)
    val args = if (currentToken() == RPAREN) {
      Nil
    } else {
      parseExpressionList()
    }
    consume(RPAREN)

    CallStatement(procName, args)
  }

  /**
   * 获取当前 Token
   */
  private def currentToken(): Token = {
    if (position < tokens.length) tokens(position)
    else EOF
  }

  /**
   * 消费当前 Token
   */
  private def consume(expected: Token): Unit = {
    if (currentToken() != expected) {
      throw parseError(s"Expected $expected but got ${currentToken()}")
    }
    position += 1
  }
}

object Parser {
  /** 向后兼容：从纯 Token 列表创建 Parser */
  def apply(tokens: List[Token]): Parser = new Parser(tokens)

  /** 增强模式：从带位置的 Token 列表创建 Parser */
  def withPositions(posTokens: List[PositionedToken], source: String): Parser = {
    val tokens = posTokens.map(_.token)
    new Parser(tokens, posTokens, source)
  }
}
