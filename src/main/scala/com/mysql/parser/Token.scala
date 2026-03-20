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

// UNION 关键字
case object UNION extends Token
case object ALL extends Token

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

// 数据类型
case object INT extends Token
case object VARCHAR extends Token
case object TEXT extends Token
case object DATETIME extends Token
case object TIMESTAMP extends Token
case object BOOLEAN extends Token

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
