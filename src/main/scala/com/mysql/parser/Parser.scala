package com.mysql.parser

/**
 * 语法分析器 - 将 Token 流转换为 AST
 */
class Parser(tokens: List[Token]) {
  private var position = 0

  /**
   * 解析 SQL 语句
   */
  def parse(): SQLStatement = {
    val stmt = currentToken() match {
      case SELECT => parseSelect()
      case INSERT => parseInsert()
      case UPDATE => parseUpdate()
      case DELETE => parseDelete()
      case CREATE => parseCreate()
      case DROP => parseDrop()
      case _ => throw new RuntimeException(s"Unexpected token: ${currentToken()}")
    }

    // 检查 UNION / UNION ALL
    parseUnionTail(stmt)
  }

  /**
   * 解析 UNION 尾部（支持链式 UNION）
   * 如果当前 Token 不是 UNION，直接返回原语句
   */
  private def parseUnionTail(left: SQLStatement): SQLStatement = {
    var result = left
    while (currentToken() == UNION) {
      consume(UNION)
      val unionType = if (currentToken() == ALL) {
        consume(ALL)
        UnionAll
      } else {
        UnionDistinct
      }
      consume(SELECT)  // UNION 后面必须跟 SELECT
      // 回退一个位置，让 parseSelect() 能正常消费 SELECT
      position -= 1
      val right = parseSelect()
      result = UnionStatement(result, right, unionType)
    }
    result
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
        case _ => throw new RuntimeException("Expected number after LIMIT")
      }
    } else None

    val offset = if (currentToken() == OFFSET) {
      consume(OFFSET)
      currentToken() match {
        case NumberToken(value) =>
          consume(currentToken())
          Some(value.toInt)
        case _ => throw new RuntimeException("Expected number after OFFSET")
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
        case t if isAggregateToken(t) =>
          val aggExpr = parseAggregateFunction()
          val alias = parseOptionalAlias()
          ExpressionColumn(aggExpr, alias)

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
            throw new RuntimeException("Expected SELECT in subquery column")
          }

        case IdentifierToken(name) =>
          consume(currentToken())
          
          if (currentToken() == LPAREN) {
            // 函数调用列：UPPER(name), CONCAT(a, b) 等
            consume(LPAREN)
            val args = if (currentToken() == RPAREN) {
              List.empty[Expression]
            } else {
              parseExpressionList()
            }
            consume(RPAREN)
            val alias = parseOptionalAlias()
            ExpressionColumn(FunctionCall(name.toUpperCase, args), alias)
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
              case _ => throw new RuntimeException("Expected column name after '.'")
            }
            
            val alias = parseOptionalAlias()
            QualifiedColumn(name, colName, alias)
          } else {
            val alias = parseOptionalAlias()
            NamedColumn(name, alias)
          }

        // 字面量表达式列：SELECT 1, SELECT 'hello'
        case _: NumberToken | _: StringToken =>
          val expr = parseExpression()
          val alias = parseOptionalAlias()
          ExpressionColumn(expr, alias)

        case _ => throw new RuntimeException("Expected column name or aggregate function")
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
        case _ => throw new RuntimeException("Expected alias after AS")
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
      "SET", "VALUES", "INTO", "SELECT", "INSERT", "UPDATE", "DELETE",
      "CREATE", "DROP", "ALTER", "TABLE", "DISTINCT",
      "CASE", "WHEN", "THEN", "ELSE", "END",
      "ASC", "DESC", "NULL",
      "CAST", "CONVERT", "USING", "SIGNED", "UNSIGNED"
    )
    reserved.contains(name.toUpperCase)
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
      case _ => throw new RuntimeException(s"Expected aggregate function but got ${currentToken()}")
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
              case _ => throw new RuntimeException("Expected alias for derived table")
            }
          } else {
            currentToken() match {
              case IdentifierToken(a) =>
                consume(currentToken())
                a
              case _ => throw new RuntimeException("Derived table must have an alias")
            }
          }
          DerivedTable(subquery, alias)
        } else {
          throw new RuntimeException("Expected SELECT in subquery")
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
      case _ => throw new RuntimeException("Expected table name or subquery")
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
      case _ => throw new RuntimeException("Expected JOIN keyword")
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
          case _ => throw new RuntimeException(s"Expected BETWEEN, IN or LIKE after NOT, but got ${currentToken()}")
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
      case t if isAggregateToken(t) =>
        parseAggregateFunction()
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
          FunctionCall(name.toUpperCase, args)
        } else if (currentToken() == DOT) {
          consume(DOT)
          currentToken() match {
            case IdentifierToken(col) =>
              consume(currentToken())
              QualifiedIdentifier(name, col)
            case _ => throw new RuntimeException("Expected column name after '.'")
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
      case _ => throw new RuntimeException(s"Unexpected token in expression: ${currentToken()}")
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
          case _ => throw new RuntimeException("Expected charset name after USING")
        }
        consume(RPAREN)
        ConvertExpression(expr, None, Some(charset))

      case COMMA =>
        // CONVERT(expression, type)
        consume(COMMA)
        val targetType = parseCastType()
        consume(RPAREN)
        ConvertExpression(expr, Some(targetType), None)

      case _ => throw new RuntimeException(s"Expected USING or ',' in CONVERT, but got ${currentToken()}")
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
            case _ => throw new RuntimeException("Expected number in CHAR size")
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
          case _ => throw new RuntimeException("Expected number in VARCHAR size")
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
            case _ => throw new RuntimeException("Expected number in DECIMAL precision")
          }
          val s = if (currentToken() == COMMA) {
            consume(COMMA)
            currentToken() match {
              case NumberToken(v) =>
                consume(currentToken())
                Some(v.toInt)
              case _ => throw new RuntimeException("Expected number in DECIMAL scale")
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

      case _ => throw new RuntimeException(s"Unknown cast type: ${currentToken()}")
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
      throw new RuntimeException("CASE expression must have at least one WHEN clause")
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
      case _ => throw new RuntimeException("Expected table name")
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
        case _ => throw new RuntimeException("Expected identifier")
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
      case _ => throw new RuntimeException("Expected table name")
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
        case _ => throw new RuntimeException("Expected column name")
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
      case _ => throw new RuntimeException("Expected table name")
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
  private def parseCreate(): CreateTableStatement = {
    consume(CREATE)
    consume(TABLE)
    
    val tableName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw new RuntimeException("Expected table name")
    }

    consume(LPAREN)
    val columns = parseColumnDefinitions()
    consume(RPAREN)

    CreateTableStatement(tableName, columns)
  }

  /**
   * 解析列定义列表
   */
  private def parseColumnDefinitions(): List[ColumnDefinition] = {
    var columns = List[ColumnDefinition]()
    
    do {
      if (currentToken() == COMMA) consume(COMMA)
      
      val columnName = currentToken() match {
        case IdentifierToken(name) =>
          consume(currentToken())
          name
        case _ => throw new RuntimeException("Expected column name")
      }
      
      val dataType = parseDataType()
      
      columns = columns :+ ColumnDefinition(columnName, dataType)
    } while (currentToken() == COMMA)
    
    columns
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
            case _ => throw new RuntimeException("Expected number")
          }
          consume(RPAREN)
          IntType(Some(size))
        } else {
          IntType()
        }
      case VARCHAR =>
        consume(VARCHAR)
        consume(LPAREN)
        val size = currentToken() match {
          case NumberToken(value) =>
            consume(currentToken())
            value.toInt
          case _ => throw new RuntimeException("Expected number")
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
      case _ => throw new RuntimeException(s"Unknown data type: ${currentToken()}")
    }
  }

  /**
   * 解析 DROP TABLE 语句
   */
  private def parseDrop(): DropTableStatement = {
    consume(DROP)
    consume(TABLE)
    
    val tableName = currentToken() match {
      case IdentifierToken(name) =>
        consume(currentToken())
        name
      case _ => throw new RuntimeException("Expected table name")
    }

    DropTableStatement(tableName)
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
      throw new RuntimeException(s"Expected $expected but got ${currentToken()}")
    }
    position += 1
  }
}

object Parser {
  def apply(tokens: List[Token]): Parser = new Parser(tokens)
}
