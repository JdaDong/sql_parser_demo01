package com.mysql.parser

import scala.util.matching.Regex

/**
 * 词法分析器 - 将 SQL 字符串转换为 Token 流
 *
 * 支持两种模式：
 *   - tokenize()             返回 List[Token]（原有行为，向后兼容）
 *   - tokenizeWithPositions() 返回 List[PositionedToken]（携带行号/列号）
 */
class Lexer(input: String) {
  private var position = 0
  private val length = input.length

  // 行号/列号跟踪
  private var line = 1
  private var column = 1

  // 关键字映射
  private val keywords = Map(
    "SELECT" -> SELECT,
    "FROM" -> FROM,
    "WHERE" -> WHERE,
    "INSERT" -> INSERT,
    "INTO" -> INTO,
    "VALUES" -> VALUES,
    "UPDATE" -> UPDATE,
    "SET" -> SET,
    "DELETE" -> DELETE,
    "CREATE" -> CREATE,
    "TABLE" -> TABLE,
    "DROP" -> DROP,
    "ALTER" -> ALTER,
    "AND" -> AND,
    "OR" -> OR,
    "NOT" -> NOT,
    "NULL" -> NULL,
    "AS" -> AS,
    "JOIN" -> JOIN,
    "LEFT" -> LEFT,
    "RIGHT" -> RIGHT,
    "INNER" -> INNER,
    "ON" -> ON,
    "ORDER" -> ORDER,
    "BY" -> BY,
    "GROUP" -> GROUP,
    "HAVING" -> HAVING,
    "LIMIT" -> LIMIT,
    "OFFSET" -> OFFSET,
    "DISTINCT" -> DISTINCT,
    "ASC" -> ASC,
    "DESC" -> DESC,
    "IS" -> IS,
    "BETWEEN" -> BETWEEN,
    "IN" -> IN,
    "LIKE" -> LIKE,
    "EXISTS" -> EXISTS,
    "CASE" -> CASE,
    "WHEN" -> WHEN,
    "THEN" -> THEN,
    "ELSE" -> ELSE,
    "END" -> END,
    "UNION" -> UNION,
    "ALL" -> ALL,
    "INTERSECT" -> INTERSECT,
    "EXCEPT" -> EXCEPT,
    "CAST" -> CAST,
    "CONVERT" -> CONVERT,
    "SIGNED" -> SIGNED,
    "UNSIGNED" -> UNSIGNED,
    "USING" -> USING,
    "DECIMAL" -> DECIMAL,
    "CHAR" -> CHAR,
    "INTEGER" -> INTEGER,
    "DATE" -> DATE,
    "COUNT" -> COUNT,
    "SUM" -> SUM,
    "AVG" -> AVG,
    "MAX" -> MAX,
    "MIN" -> MIN,
    "INT" -> INT,
    "VARCHAR" -> VARCHAR,
    "TEXT" -> TEXT,
    "DATETIME" -> DATETIME,
    "TIMESTAMP" -> TIMESTAMP,
    "BOOLEAN" -> BOOLEAN,
    // 窗口函数关键字
    "OVER" -> OVER,
    "PARTITION" -> PARTITION,
    "ROWS" -> ROWS,
    "RANGE" -> RANGE,
    "UNBOUNDED" -> UNBOUNDED,
    "PRECEDING" -> PRECEDING,
    "FOLLOWING" -> FOLLOWING,
    "CURRENT" -> CURRENT,
    "ROW" -> ROW,
    // CTE 关键字
    "WITH" -> WITH,
    "RECURSIVE" -> RECURSIVE,
    // ALTER TABLE 关键字
    "ADD" -> ADD,
    "COLUMN" -> COLUMN_KW,
    "MODIFY" -> MODIFY,
    "RENAME" -> RENAME,
    "TO" -> TO,
    "CHANGE" -> CHANGE,
    "IF" -> IF,
    // 约束关键字
    "PRIMARY" -> PRIMARY,
    "KEY" -> KEY,
    "UNIQUE" -> UNIQUE,
    "FOREIGN" -> FOREIGN,
    "REFERENCES" -> REFERENCES,
    "CHECK" -> CHECK,
    "DEFAULT" -> DEFAULT,
    "AUTO_INCREMENT" -> AUTO_INCREMENT,
    "CONSTRAINT" -> CONSTRAINT,
    // 索引关键字
    "INDEX" -> INDEX,
    // 视图关键字
    "VIEW" -> VIEW,
    "REPLACE" -> REPLACE,
    // 存储过程关键字
    "PROCEDURE" -> PROCEDURE,
    "CALL" -> CALL,
    "BEGIN" -> BEGIN_KW,
    "RETURN" -> RETURN,
    "INOUT" -> INOUT,
    "OUT" -> OUT,
    // 新增数据类型
    "BIGINT" -> BIGINT,
    "SMALLINT" -> SMALLINT,
    "FLOAT" -> FLOAT,
    "DOUBLE" -> DOUBLE
  )

  /**
   * 获取所有 Token（向后兼容，不带位置信息）
   */
  def tokenize(): List[Token] = {
    tokenizeWithPositions().map(_.token)
  }

  /**
   * 获取所有 Token（携带位置信息）
   */
  def tokenizeWithPositions(): List[PositionedToken] = {
    var tokens = List[PositionedToken]()
    var pt = nextPositionedToken()
    
    while (pt.token != EOF) {
      if (pt.token != WHITESPACE) {
        tokens = tokens :+ pt
      }
      pt = nextPositionedToken()
    }
    
    tokens :+ pt  // 追加 EOF（也带位置）
  }

  /**
   * 获取下一个 PositionedToken（记录起始位置后调用 nextToken）
   */
  private def nextPositionedToken(): PositionedToken = {
    val pos = Position(line, column, position)
    val token = nextToken()
    PositionedToken(token, pos)
  }

  /**
   * 前进一个字符并更新行号/列号
   */
  private def advance(): Char = {
    val ch = input.charAt(position)
    if (ch == '\n') {
      line += 1
      column = 1
    } else {
      column += 1
    }
    position += 1
    ch
  }

  /**
   * 前进 n 个字符并更新行号/列号
   */
  private def advanceN(n: Int): Unit = {
    var i = 0
    while (i < n && position < length) {
      advance()
      i += 1
    }
  }

  /**
   * 获取下一个 Token
   */
  private def nextToken(): Token = {
    if (position >= length) return EOF

    val currentChar = input.charAt(position)

    // 跳过空白字符
    if (currentChar.isWhitespace) {
      advance()
      return WHITESPACE
    }

    // 跳过注释
    // 单行注释: -- ...
    if (currentChar == '-' && peek() == '-') {
      skipLineComment()
      return WHITESPACE
    }
    // 单行注释: # ...（MySQL 特有）
    if (currentChar == '#') {
      skipLineComment()
      return WHITESPACE
    }
    // 块注释: /* ... */
    if (currentChar == '/' && peek() == '*') {
      skipBlockComment()
      return WHITESPACE
    }

    // 识别数字
    if (currentChar.isDigit) {
      return readNumber()
    }

    // 识别标识符和关键字
    if (currentChar.isLetter || currentChar == '_') {
      return readIdentifier()
    }

    // 识别字符串字面量
    if (currentChar == '\'' || currentChar == '"') {
      return readString(currentChar)
    }

    // 识别运算符和分隔符
    currentChar match {
      case '=' =>
        advance()
        EQUALS
      case '!' =>
        if (peek() == '=') {
          advanceN(2)
          NOT_EQUALS
        } else {
          advance()
          NOT
        }
      case '<' =>
        if (peek() == '=') {
          advanceN(2)
          LESS_EQUAL
        } else {
          advance()
          LESS_THAN
        }
      case '>' =>
        if (peek() == '=') {
          advanceN(2)
          GREATER_EQUAL
        } else {
          advance()
          GREATER_THAN
        }
      case '+' =>
        advance()
        PLUS_OP
      case '-' =>
        advance()
        MINUS_OP
      case '*' =>
        advance()
        MULTIPLY_OP
      case '/' =>
        advance()
        DIVIDE_OP
      case '(' =>
        advance()
        LPAREN
      case ')' =>
        advance()
        RPAREN
      case ',' =>
        advance()
        COMMA
      case ';' =>
        advance()
        SEMICOLON
      case '.' =>
        advance()
        DOT
      case _ =>
        throw new ParseException(
          s"Unexpected character: '$currentChar'",
          Position(line, column, position),
          input
        )
    }
  }

  /**
   * 读取数字
   */
  private def readNumber(): Token = {
    val start = position
    while (position < length && (input.charAt(position).isDigit || input.charAt(position) == '.')) {
      advance()
    }
    NumberToken(input.substring(start, position))
  }

  /**
   * 读取标识符或关键字
   */
  private def readIdentifier(): Token = {
    val start = position
    while (position < length && (input.charAt(position).isLetterOrDigit || input.charAt(position) == '_')) {
      advance()
    }
    val text = input.substring(start, position)
    val upperText = text.toUpperCase
    keywords.getOrElse(upperText, IdentifierToken(upperText))
  }

  /**
   * 读取字符串字面量
   */
  private def readString(quote: Char): Token = {
    advance() // 跳过开始引号
    val start = position
    
    while (position < length && input.charAt(position) != quote) {
      if (input.charAt(position) == '\\' && position + 1 < length) {
        advanceN(2) // 跳过转义字符
      } else {
        advance()
      }
    }
    
    if (position >= length) {
      throw new ParseException(
        "Unterminated string literal",
        Position(line, column, position),
        input
      )
    }
    
    val value = input.substring(start, position)
    advance() // 跳过结束引号
    StringToken(value)
  }

  /**
   * 查看下一个字符但不移动位置
   */
  private def peek(): Char = {
    if (position + 1 < length) input.charAt(position + 1)
    else '\u0000'
  }

  /**
   * 跳过单行注释（-- 或 #）
   * 从当前位置一直跳到行尾（不含换行符，换行符由下一轮 WHITESPACE 处理）
   */
  private def skipLineComment(): Unit = {
    while (position < length && input.charAt(position) != '\n') {
      advance()
    }
  }

  /**
   * 跳过块注释 /* ... */
   * 支持嵌套检测（发现未闭合的块注释抛出异常）
   */
  private def skipBlockComment(): Unit = {
    val startPos = Position(line, column, position)
    advanceN(2) // 跳过 /*
    while (position + 1 < length) {
      if (input.charAt(position) == '*' && input.charAt(position + 1) == '/') {
        advanceN(2) // 跳过 */
        return
      }
      advance()
    }
    throw new ParseException(
      "Unterminated block comment",
      startPos,
      input
    )
  }
}

object Lexer {
  def apply(input: String): Lexer = new Lexer(input)
}
