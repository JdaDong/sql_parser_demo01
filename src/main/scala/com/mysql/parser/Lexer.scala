package com.mysql.parser

import scala.util.matching.Regex

/**
 * 词法分析器 - 将 SQL 字符串转换为 Token 流
 */
class Lexer(input: String) {
  private var position = 0
  private val length = input.length

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
    "BOOLEAN" -> BOOLEAN
  )

  /**
   * 获取所有 Token
   */
  def tokenize(): List[Token] = {
    var tokens = List[Token]()
    var token = nextToken()
    
    while (token != EOF) {
      if (token != WHITESPACE) {
        tokens = tokens :+ token
      }
      token = nextToken()
    }
    
    tokens :+ EOF
  }

  /**
   * 获取下一个 Token
   */
  private def nextToken(): Token = {
    if (position >= length) return EOF

    val currentChar = input.charAt(position)

    // 跳过空白字符
    if (currentChar.isWhitespace) {
      position += 1
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
        position += 1
        EQUALS
      case '!' =>
        if (peek() == '=') {
          position += 2
          NOT_EQUALS
        } else {
          position += 1
          NOT
        }
      case '<' =>
        if (peek() == '=') {
          position += 2
          LESS_EQUAL
        } else {
          position += 1
          LESS_THAN
        }
      case '>' =>
        if (peek() == '=') {
          position += 2
          GREATER_EQUAL
        } else {
          position += 1
          GREATER_THAN
        }
      case '+' =>
        position += 1
        PLUS_OP
      case '-' =>
        position += 1
        MINUS_OP
      case '*' =>
        position += 1
        MULTIPLY_OP
      case '/' =>
        position += 1
        DIVIDE_OP
      case '(' =>
        position += 1
        LPAREN
      case ')' =>
        position += 1
        RPAREN
      case ',' =>
        position += 1
        COMMA
      case ';' =>
        position += 1
        SEMICOLON
      case '.' =>
        position += 1
        DOT
      case _ =>
        throw new RuntimeException(s"Unexpected character: $currentChar at position $position")
    }
  }

  /**
   * 读取数字
   */
  private def readNumber(): Token = {
    val start = position
    while (position < length && (input.charAt(position).isDigit || input.charAt(position) == '.')) {
      position += 1
    }
    NumberToken(input.substring(start, position))
  }

  /**
   * 读取标识符或关键字
   */
  private def readIdentifier(): Token = {
    val start = position
    while (position < length && (input.charAt(position).isLetterOrDigit || input.charAt(position) == '_')) {
      position += 1
    }
    val text = input.substring(start, position)
    val upperText = text.toUpperCase
    keywords.getOrElse(upperText, IdentifierToken(text))
  }

  /**
   * 读取字符串字面量
   */
  private def readString(quote: Char): Token = {
    position += 1 // 跳过开始引号
    val start = position
    
    while (position < length && input.charAt(position) != quote) {
      if (input.charAt(position) == '\\' && position + 1 < length) {
        position += 2 // 跳过转义字符
      } else {
        position += 1
      }
    }
    
    if (position >= length) {
      throw new RuntimeException("Unterminated string literal")
    }
    
    val value = input.substring(start, position)
    position += 1 // 跳过结束引号
    StringToken(value)
  }

  /**
   * 查看下一个字符但不移动位置
   */
  private def peek(): Char = {
    if (position + 1 < length) input.charAt(position + 1)
    else '\u0000'
  }
}

object Lexer {
  def apply(input: String): Lexer = new Lexer(input)
}
