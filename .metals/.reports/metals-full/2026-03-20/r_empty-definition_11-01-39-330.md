error id: file://<WORKSPACE>/src/main/scala/com/mysql/parser/Parser.scala:com/mysql/parser/COMMA.
file://<WORKSPACE>/src/main/scala/com/mysql/parser/Parser.scala
empty definition using pc, found symbol in pc: com/mysql/parser/COMMA.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -COMMA.
	 -COMMA#
	 -COMMA().
	 -scala/Predef.COMMA.
	 -scala/Predef.COMMA#
	 -scala/Predef.COMMA().
offset: 2440
uri: file://<WORKSPACE>/src/main/scala/com/mysql/parser/Parser.scala
text:
```scala
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
    currentToken() match {
      case SELECT => parseSelect()
      case INSERT => parseInsert()
      case UPDATE => parseUpdate()
      case DELETE => parseDelete()
      case CREATE => parseCreate()
      case DROP => parseDrop()
      case _ => throw new RuntimeException(s"Unexpected token: ${currentToken()}")
    }
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
      if (currentToken() == CO@@MMA) consume(COMMA)
      
      val column = currentToken() match {
        // 聚合函数列：COUNT(...), SUM(...), AVG(...), MAX(...), MIN(...)
        case t if isAggregateToken(t) =>
          val aggExpr = parseAggregateFunction()
          val alias = parseOptionalAlias()
          ExpressionColumn(aggExpr, alias)

        case IdentifierToken(name) =>
          consume(currentToken())
          
          // 检查是否是 table.column 格式
          if (currentToken() == DOT) {
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
        case _ => throw new RuntimeException("Expected column name or aggregate function")
      }
      
      columns = columns :+ column
    } while (currentToken() == COMMA)
    
    columns
  }

  /**
   * 解析可选的 AS 别名
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
    } else None
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
   * 解析表引用
   */
  private def parseTableReference(): TableReference = {
    val left = currentToken() match {
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
        } else None
        TableName(name, alias)
      case _ => throw new RuntimeException("Expected table name")
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
   * 解析比较表达式
   */
  private def parseComparisonExpression(): Expression = {
    var left = parseAdditiveExpression()
    
    currentToken() match {
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
   * 解析一元表达式
   */
  private def parseUnaryExpression(): Expression = {
    if (currentToken() == NOT) {
      consume(NOT)
      UnaryExpression(NotOp, parseUnaryExpression())
    } else {
      parsePrimaryExpression()
    }
  }

  /**
   * 解析基本表达式
   */
  private def parsePrimaryExpression(): Expression = {
    currentToken() match {
      // 聚合函数在表达式上下文中（如 HAVING COUNT(*) > 5）
      case t if isAggregateToken(t) =>
        parseAggregateFunction()
      case IdentifierToken(name) =>
        consume(currentToken())
        if (currentToken() == DOT) {
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
        val expr = parseExpression()
        consume(RPAREN)
        expr
      case _ => throw new RuntimeException(s"Unexpected token in expression: ${currentToken()}")
    }
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
      // 这里可以扩展支持 ASC/DESC
      orderBys = orderBys :+ OrderByClause(expr, ascending = true)
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

```


#### Short summary: 

empty definition using pc, found symbol in pc: com/mysql/parser/COMMA.