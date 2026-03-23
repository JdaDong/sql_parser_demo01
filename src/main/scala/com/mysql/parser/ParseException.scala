package com.mysql.parser

/**
 * SQL 解析异常 — 携带精确的错误位置信息和源码上下文
 *
 * 错误消息格式：
 * {{{
 *   [1:15] Unexpected token: EOF
 *
 *   1 | SELECT * FROM
 *                     ^
 * }}}
 *
 * @param msg      错误描述
 * @param position 错误在源码中的位置
 * @param source   完整的 SQL 源码字符串
 */
class ParseException(
  val msg: String,
  val position: Position,
  val source: String
) extends RuntimeException(ParseException.formatMessage(msg, position, source))

object ParseException {

  /**
   * 格式化错误消息，包含：
   *   - 位置前缀 [line:column]
   *   - 错误描述
   *   - 源码上下文（出错行 + 指示器 ^）
   */
  def formatMessage(msg: String, pos: Position, source: String): String = {
    val header = s"[${pos.line}:${pos.column}] $msg"

    // 提取出错行的内容
    val lines = source.split("\n", -1)
    if (pos.line < 1 || pos.line > lines.length) {
      return header
    }

    val errorLine = lines(pos.line - 1)
    val lineNumStr = pos.line.toString
    val gutter = " " * lineNumStr.length

    // 构建指示器（^）
    val pointerOffset = math.max(0, math.min(pos.column - 1, errorLine.length))
    val pointer = " " * pointerOffset + "^"

    s"""$header
       |
       |$lineNumStr | $errorLine
       |$gutter | $pointer""".stripMargin
  }

  /**
   * 从 PositionedToken 列表和当前索引创建 ParseException
   *
   * @param msg            错误描述
   * @param tokens         带位置的 Token 列表
   * @param tokenIndex     出错的 Token 索引
   * @param source         完整 SQL 源码
   */
  def fromTokens(msg: String, tokens: List[PositionedToken], tokenIndex: Int, source: String): ParseException = {
    val idx = math.max(0, math.min(tokenIndex, tokens.length - 1))
    val pos = if (tokens.nonEmpty) tokens(idx).position else Position(1, 1, 0)
    new ParseException(msg, pos, source)
  }
}
