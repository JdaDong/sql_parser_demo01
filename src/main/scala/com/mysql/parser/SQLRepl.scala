package com.mysql.parser

import scala.io.StdIn

/**
 * SQL REPL（Read-Eval-Print Loop）交互式命令行
 *
 * 功能：
 *   - 输入 SQL 语句，实时查看解析结果
 *   - 支持多行输入（以 ; 结尾或空行结束）
 *   - 内置命令：
 *     :tokens   — 显示 Token 流（含位置信息）
 *     :ast      — 显示 AST 树
 *     :format   — 显示 SQL Pretty Print 格式化
 *     :analyze  — 执行语义分析
 *     :all      — 一次性展示所有输出
 *     :schema   — 显示当前 Schema
 *     :load     — 加载预设 Schema
 *     :mode     — 切换默认输出模式
 *     :help     — 显示帮助
 *     :quit     — 退出 REPL
 *
 * 用法：
 * {{{
 *   sbt "run repl"
 *   // 或直接
 *   SQLRepl.main(Array.empty)
 * }}}
 */
object SQLRepl {

  // ANSI 颜色
  private val RESET  = "\u001b[0m"
  private val BOLD   = "\u001b[1m"
  private val RED    = "\u001b[31m"
  private val GREEN  = "\u001b[32m"
  private val YELLOW = "\u001b[33m"
  private val BLUE   = "\u001b[34m"
  private val CYAN   = "\u001b[36m"
  private val DIM    = "\u001b[2m"

  // 默认输出模式
  private sealed trait OutputMode
  private case object ModeTokens  extends OutputMode
  private case object ModeAST     extends OutputMode
  private case object ModeFormat  extends OutputMode
  private case object ModeAnalyze extends OutputMode
  private case object ModeJson    extends OutputMode
  private case object ModeAll     extends OutputMode

  private var currentMode: OutputMode = ModeFormat
  private var schema: DatabaseSchema = defaultSchema()

  /**
   * 默认示例 Schema
   */
  private def defaultSchema(): DatabaseSchema = {
    DatabaseSchema(
      TableSchema.simple("USERS", "ID", "NAME", "AGE", "EMAIL", "ROLE", "DEPARTMENT", "SALARY", "STATUS"),
      TableSchema.simple("ORDERS", "ID", "USER_ID", "PRODUCT_ID", "AMOUNT", "CREATED_AT"),
      TableSchema.simple("PRODUCTS", "ID", "NAME", "CATEGORY", "PRICE"),
      TableSchema.simple("EMPLOYEES", "ID", "NAME", "DEPARTMENT", "SALARY", "HIRE_DATE"),
      TableSchema.simple("ADMINS", "ID", "NAME", "LEVEL")
    )
  }

  def main(args: Array[String]): Unit = {
    printBanner()
    replLoop()
  }

  private def printBanner(): Unit = {
    println(s"""
${BOLD}${CYAN}╔══════════════════════════════════════════════════╗
║           SQL Parser REPL  v0.2.0                ║
║                                                  ║
║  输入 SQL 语句查看解析结果                       ║
║  输入 :help 查看可用命令                         ║
║  以 ; 结尾或直接回车提交                         ║
╚══════════════════════════════════════════════════╝${RESET}
""")
    println(s"  ${DIM}当前模式: ${modeLabel(currentMode)} | Schema: ${schema.tables.size} 个表${RESET}")
    println()
  }

  private def modeLabel(mode: OutputMode): String = mode match {
    case ModeTokens  => "Token 流"
    case ModeAST     => "AST 树"
    case ModeFormat  => "SQL 格式化"
    case ModeAnalyze => "语义分析"
    case ModeJson    => "JSON 序列化"
    case ModeAll     => "全部展示"
  }

  /**
   * REPL 主循环
   */
  private def replLoop(): Unit = {
    var running = true

    while (running) {
      val input = readInput()
      if (input == null) {
        running = false
      } else {
        val trimmed = input.trim
        if (trimmed.nonEmpty) {
          trimmed match {
            case cmd if cmd.startsWith(":") =>
              running = handleCommand(cmd)
            case sql =>
              // 去除末尾的分号
              val cleanSql = if (sql.endsWith(";")) sql.dropRight(1).trim else sql
              if (cleanSql.nonEmpty) {
                processSql(cleanSql)
              }
          }
        }
      }
    }

    println(s"\n${DIM}Bye! 👋${RESET}\n")
  }

  /**
   * 读取用户输入（支持多行）
   */
  private def readInput(): String = {
    print(s"${BOLD}${GREEN}sql>${RESET} ")
    val firstLine = StdIn.readLine()
    if (firstLine == null) return null

    // 如果第一行以冒号开头，直接返回（命令）
    if (firstLine.trim.startsWith(":")) return firstLine

    // 如果第一行以分号结尾，直接返回
    if (firstLine.trim.endsWith(";")) return firstLine

    // 如果第一行为空，直接返回
    if (firstLine.trim.isEmpty) return firstLine

    // 多行输入模式：继续读取直到遇到分号或空行
    val sb = new StringBuilder(firstLine)
    var done = false

    while (!done) {
      print(s"${DIM}  ...>${RESET} ")
      val line = StdIn.readLine()
      if (line == null || line.trim.isEmpty) {
        done = true
      } else {
        sb.append("\n").append(line)
        if (line.trim.endsWith(";")) {
          done = true
        }
      }
    }

    sb.toString()
  }

  /**
   * 处理 REPL 命令
   * @return false 表示应退出 REPL
   */
  private def handleCommand(cmd: String): Boolean = {
    val parts = cmd.split("\\s+", 2)
    val command = parts(0).toLowerCase

    command match {
      case ":quit" | ":q" | ":exit" =>
        false

      case ":help" | ":h" | ":?" =>
        printHelp()
        true

      case ":tokens" | ":t" =>
        currentMode = ModeTokens
        println(s"  ${DIM}✓ 切换到 Token 流模式${RESET}\n")
        true

      case ":ast" | ":a" =>
        currentMode = ModeAST
        println(s"  ${DIM}✓ 切换到 AST 树模式${RESET}\n")
        true

      case ":format" | ":f" =>
        currentMode = ModeFormat
        println(s"  ${DIM}✓ 切换到 SQL 格式化模式${RESET}\n")
        true

      case ":analyze" | ":an" =>
        currentMode = ModeAnalyze
        println(s"  ${DIM}✓ 切换到语义分析模式${RESET}\n")
        true

      case ":json" | ":j" =>
        currentMode = ModeJson
        println(s"  ${DIM}✓ 切换到 JSON 序列化模式${RESET}\n")
        true

      case ":all" =>
        currentMode = ModeAll
        println(s"  ${DIM}✓ 切换到全部展示模式${RESET}\n")
        true

      case ":mode" | ":m" =>
        println(s"  当前模式: ${BOLD}${modeLabel(currentMode)}${RESET}")
        println(s"  ${DIM}可用模式: :tokens :ast :format :analyze :json :all${RESET}\n")
        true

      case ":schema" | ":s" =>
        printSchema()
        true

      case ":load" =>
        schema = defaultSchema()
        println(s"  ${DIM}✓ 已加载默认 Schema（${schema.tables.size} 个表）${RESET}\n")
        true

      case ":clear" | ":cls" =>
        print("\u001b[2J\u001b[H")
        printBanner()
        true

      case _ =>
        println(s"  ${RED}未知命令: $command${RESET}")
        println(s"  ${DIM}输入 :help 查看可用命令${RESET}\n")
        true
    }
  }

  /**
   * 处理 SQL 输入
   */
  private def processSql(sql: String): Unit = {
    println()

    currentMode match {
      case ModeTokens  => showTokens(sql)
      case ModeAST     => showAST(sql)
      case ModeFormat  => showFormat(sql)
      case ModeAnalyze => showAnalyze(sql)
      case ModeJson    => showJson(sql)
      case ModeAll     => showAll(sql)
    }

    println()
  }

  /**
   * 显示 Token 流
   */
  private def showTokens(sql: String): Unit = {
    try {
      val tokens = MySQLParser.tokenize(sql)
      println(s"  ${BOLD}${BLUE}═══ Token 流 (${tokens.length} 个) ═══${RESET}")
      println()

      // 表头
      println(s"  ${DIM}${"位置".padTo(10, ' ')} ${"Token 类型".padTo(25, ' ')} 值${RESET}")
      println(s"  ${DIM}${"─" * 60}${RESET}")

      tokens.foreach { pt =>
        val posStr = pt.position.toString.padTo(10, ' ')
        val (tokenType, tokenValue) = formatToken(pt.token)
        val typeStr = tokenType.padTo(25, ' ')
        println(s"  ${CYAN}$posStr${RESET} ${YELLOW}$typeStr${RESET} $tokenValue")
      }
    } catch {
      case e: ParseException =>
        printError(e)
      case e: Exception =>
        println(s"  ${RED}Error: ${e.getMessage}${RESET}")
    }
  }

  /**
   * 格式化单个 Token 为 (类型, 值)
   */
  private def formatToken(token: Token): (String, String) = token match {
    case IdentifierToken(name) => ("IDENTIFIER", name)
    case StringToken(value)    => ("STRING", s"'$value'")
    case NumberToken(value)    => ("NUMBER", value)
    case EOF                   => ("EOF", "")
    case WHITESPACE            => ("WHITESPACE", "")
    case t                     => (t.toString, "")
  }

  /**
   * 显示 AST 树
   */
  private def showAST(sql: String): Unit = {
    try {
      val ast = MySQLParser.parse(sql)
      println(s"  ${BOLD}${BLUE}═══ AST 树 ═══${RESET}")
      println()
      MySQLParser.printAST(ast, indent = 1)
    } catch {
      case e: ParseException =>
        printError(e)
      case e: Exception =>
        println(s"  ${RED}Error: ${e.getMessage}${RESET}")
    }
  }

  /**
   * 显示格式化 SQL
   */
  private def showFormat(sql: String): Unit = {
    try {
      val ast = MySQLParser.parse(sql)
      val printer = new SQLPrettyPrinter()
      val formatted = printer.visitStatement(ast)
      println(s"  ${BOLD}${BLUE}═══ 格式化 SQL ═══${RESET}")
      println()
      formatted.split("\n").foreach(line => println(s"  ${GREEN}$line${RESET}"))
    } catch {
      case e: ParseException =>
        printError(e)
      case e: Exception =>
        println(s"  ${RED}Error: ${e.getMessage}${RESET}")
    }
  }

  /**
   * 显示语义分析结果
   */
  private def showAnalyze(sql: String): Unit = {
    try {
      val ast = MySQLParser.parse(sql)
      println(s"  ${BOLD}${BLUE}═══ 语义分析 ═══${RESET}")
      println()

      // 传统分析
      val errors = SemanticAnalyzer.analyze(ast, schema)
      if (errors.isEmpty) {
        println(s"  ${GREEN}✅ 语义检查通过${RESET}")
      } else {
        errors.foreach { err =>
          val icon = err.severity match {
            case SError   => s"${RED}❌"
            case SWarning => s"${YELLOW}⚠️"
          }
          println(s"  $icon [${err.severity}][${err.category}] ${err.message}${RESET}")
        }
      }

      // 同时提取表名和列名
      val tableExtractor = new TableExtractor()
      val tables = tableExtractor.visitStatement(ast).distinct
      if (tables.nonEmpty) {
        println(s"\n  ${DIM}引用的表: ${tables.mkString(", ")}${RESET}")
      }
    } catch {
      case e: ParseException =>
        printError(e)
      case e: Exception =>
        println(s"  ${RED}Error: ${e.getMessage}${RESET}")
    }
  }

  /**
   * 显示 JSON 序列化结果
   */
  private def showJson(sql: String): Unit = {
    try {
      val ast = MySQLParser.parse(sql)
      val prettyJson = ASTJsonSerializer.toJsonPretty(ast)
      println(s"  ${BOLD}${BLUE}═══ AST JSON ═══${RESET}")
      println()
      prettyJson.split("\n").foreach(line => println(s"  ${GREEN}$line${RESET}"))
    } catch {
      case e: ParseException =>
        printError(e)
      case e: Exception =>
        println(s"  ${RED}Error: ${e.getMessage}${RESET}")
    }
  }

  /**
   * 显示所有输出
   */
  private def showAll(sql: String): Unit = {
    showTokens(sql)
    println()
    showAST(sql)
    println()
    showFormat(sql)
    println()
    showJson(sql)
    println()
    showAnalyze(sql)
  }

  /**
   * 打印 ParseException（带颜色高亮的错误消息）
   */
  private def printError(e: ParseException): Unit = {
    val lines = e.getMessage.split("\n")
    if (lines.length >= 1) {
      println(s"  ${RED}${BOLD}${lines(0)}${RESET}")
      if (lines.length > 1) {
        println()
        lines.drop(1).foreach { line =>
          if (line.contains("^")) {
            println(s"  ${RED}$line${RESET}")
          } else if (line.contains("|")) {
            println(s"  ${DIM}$line${RESET}")
          } else {
            println(s"  $line")
          }
        }
      }
    }
  }

  /**
   * 打印当前 Schema
   */
  private def printSchema(): Unit = {
    println(s"  ${BOLD}${BLUE}═══ 当前 Schema ═══${RESET}")
    println()
    schema.tables.foreach { case (name, table) =>
      val colNames = table.columns.map(_.name).mkString(", ")
      println(s"  ${YELLOW}${name}${RESET} (${colNames})")
    }
    println(s"\n  ${DIM}共 ${schema.tables.size} 个表${RESET}\n")
  }

  /**
   * 打印帮助信息
   */
  private def printHelp(): Unit = {
    println(s"""
  ${BOLD}${BLUE}═══ SQL REPL 帮助 ═══${RESET}

  ${BOLD}输入方式:${RESET}
    直接输入 SQL 语句，按回车提交
    多行 SQL 以 ${CYAN};${RESET} 结尾，或输入空行提交
    支持 ${DIM}-- 单行注释${RESET} 和 ${DIM}/* 块注释 */${RESET}

  ${BOLD}输出模式命令:${RESET}
    ${CYAN}:tokens${RESET}  (:t)   显示 Token 流（含行号/列号）
    ${CYAN}:ast${RESET}     (:a)   显示 AST 树
    ${CYAN}:format${RESET}  (:f)   显示格式化 SQL ${DIM}(默认)${RESET}
    ${CYAN}:analyze${RESET} (:an)  执行语义分析
    ${CYAN}:json${RESET}    (:j)   显示 AST 的 JSON 序列化
    ${CYAN}:all${RESET}            一次展示所有输出
    ${CYAN}:mode${RESET}   (:m)   查看当前模式

  ${BOLD}其他命令:${RESET}
    ${CYAN}:schema${RESET}  (:s)   显示当前 Schema
    ${CYAN}:load${RESET}          加载默认 Schema
    ${CYAN}:clear${RESET}  (:cls) 清屏
    ${CYAN}:help${RESET}   (:h)   显示此帮助
    ${CYAN}:quit${RESET}   (:q)   退出

  ${BOLD}示例 SQL:${RESET}
    ${DIM}SELECT * FROM users WHERE age > 18
    SELECT u.name, COUNT(*) FROM users u GROUP BY u.name
    CREATE TABLE orders (id INT PRIMARY KEY, amount DECIMAL(10,2))
    SELECT name, RANK() OVER (ORDER BY salary DESC) FROM employees
    WITH cte AS (SELECT * FROM users) SELECT * FROM cte${RESET}
""")
  }
}
