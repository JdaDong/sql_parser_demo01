package com.mysql.parser

/**
 * SQL Token 定义
 */
sealed trait Token

// 关键字 Token
case object SELECT extends Token
case object FROM extends Token
case object WHERE extends Token
case object INSERT extends Token
case object INTO extends Token
case object VALUES extends Token
case object UPDATE extends Token
case object SET extends Token
case object DELETE extends Token
case object CREATE extends Token
case object TABLE extends Token
case object DROP extends Token
case object ALTER extends Token
case object AND extends Token
case object OR extends Token
case object NOT extends Token
case object NULL extends Token
case object AS extends Token
case object JOIN extends Token
case object LEFT extends Token
case object RIGHT extends Token
case object INNER extends Token
case object ON extends Token
case object ORDER extends Token
case object BY extends Token
case object GROUP extends Token
case object HAVING extends Token
case object LIMIT extends Token
case object OFFSET extends Token
case object DISTINCT extends Token

// 排序关键字
case object ASC extends Token
case object DESC extends Token

// 谓词关键字
case object IS extends Token
case object BETWEEN extends Token
case object IN extends Token
case object LIKE extends Token
case object EXISTS extends Token

// CASE 表达式关键字
case object CASE extends Token
case object WHEN extends Token
case object THEN extends Token
case object ELSE extends Token
case object END extends Token

// UNION / INTERSECT / EXCEPT 关键字
case object UNION extends Token
case object ALL extends Token
case object INTERSECT extends Token
case object EXCEPT extends Token

// CAST / CONVERT 关键字
case object CAST extends Token
case object CONVERT extends Token
case object SIGNED extends Token
case object UNSIGNED extends Token
case object USING extends Token
case object DECIMAL extends Token
case object CHAR extends Token
case object INTEGER extends Token
case object DATE extends Token

// 聚合函数关键字
case object COUNT extends Token
case object SUM extends Token
case object AVG extends Token
case object MAX extends Token
case object MIN extends Token

// 窗口函数关键字
case object OVER extends Token
case object PARTITION extends Token
case object ROWS extends Token
case object RANGE extends Token
case object UNBOUNDED extends Token
case object PRECEDING extends Token
case object FOLLOWING extends Token
case object CURRENT extends Token
case object ROW extends Token

// CTE 关键字
case object WITH extends Token
case object RECURSIVE extends Token

// ALTER TABLE 关键字
case object ADD extends Token
case object COLUMN_KW extends Token
case object MODIFY extends Token
case object RENAME extends Token
case object TO extends Token
case object CHANGE extends Token
case object IF extends Token

// 约束关键字
case object PRIMARY extends Token
case object KEY extends Token
case object UNIQUE extends Token
case object FOREIGN extends Token
case object REFERENCES extends Token
case object CHECK extends Token
case object DEFAULT extends Token
case object AUTO_INCREMENT extends Token
case object CONSTRAINT extends Token

// 索引关键字
case object INDEX extends Token

// 视图关键字
case object VIEW extends Token
case object REPLACE extends Token

// 存储过程关键字
case object PROCEDURE extends Token
case object CALL extends Token
case object BEGIN_KW extends Token
case object RETURN extends Token
case object INOUT extends Token
case object OUT extends Token

// 数据类型
case object INT extends Token
case object VARCHAR extends Token
case object TEXT extends Token
case object DATETIME extends Token
case object TIMESTAMP extends Token
case object BOOLEAN extends Token
case object BIGINT extends Token
case object SMALLINT extends Token
case object FLOAT extends Token
case object DOUBLE extends Token

// 运算符
case object EQUALS extends Token          // =
case object NOT_EQUALS extends Token      // !=
case object LESS_THAN extends Token       // <
case object GREATER_THAN extends Token    // >
case object LESS_EQUAL extends Token      // <=
case object GREATER_EQUAL extends Token   // >=
case object PLUS_OP extends Token         // +
case object MINUS_OP extends Token        // -
case object MULTIPLY_OP extends Token     // *
case object DIVIDE_OP extends Token       // /

// 分隔符
case object LPAREN extends Token          // (
case object RPAREN extends Token          // )
case object COMMA extends Token           // ,
case object SEMICOLON extends Token       // ;
case object DOT extends Token             // .

// 字面量
case class IdentifierToken(name: String) extends Token
case class StringToken(value: String) extends Token
case class NumberToken(value: String) extends Token

// 其他
case object EOF extends Token
case object WHITESPACE extends Token

// ============================================================
//  Token 位置信息
// ============================================================

/**
 * Token 在源码中的位置
 *
 * @param line   行号（从 1 开始）
 * @param column 列号（从 1 开始）
 * @param offset 在源码字符串中的偏移量（从 0 开始）
 */
case class Position(line: Int, column: Int, offset: Int) {
  override def toString: String = s"$line:$column"
}

/**
 * 携带位置信息的 Token
 *
 * @param token    原始 Token
 * @param position Token 起始位置
 */
case class PositionedToken(token: Token, position: Position) {
  override def toString: String = s"$token@$position"
}
