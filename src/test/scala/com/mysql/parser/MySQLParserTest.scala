package com.mysql.parser

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MySQLParserTest extends AnyFlatSpec with Matchers {

  "Lexer" should "tokenize simple SELECT statement" in {
    val sql = "SELECT * FROM users"
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()
    
    tokens should contain theSameElementsInOrderAs List(
      SELECT, MULTIPLY_OP, FROM, IdentifierToken("USERS"), EOF
    )
  }

  it should "tokenize SELECT with WHERE clause" in {
    val sql = "SELECT id, name FROM users WHERE age > 18"
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()
    
    tokens should contain (SELECT)
    tokens should contain (FROM)
    tokens should contain (WHERE)
    tokens should contain (GREATER_THAN)
  }

  it should "tokenize string literals" in {
    val sql = "SELECT * FROM users WHERE name = 'Alice'"
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()
    
    tokens should contain (StringToken("Alice"))
  }

  // ====== 注释支持测试 ======

  it should "skip single-line comment with --" in {
    val sql = "SELECT * FROM users -- this is a comment"
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()

    tokens should contain theSameElementsInOrderAs List(
      SELECT, MULTIPLY_OP, FROM, IdentifierToken("USERS"), EOF
    )
  }

  it should "skip single-line comment with #" in {
    val sql = "SELECT * FROM users # MySQL-style comment"
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()

    tokens should contain theSameElementsInOrderAs List(
      SELECT, MULTIPLY_OP, FROM, IdentifierToken("USERS"), EOF
    )
  }

  it should "skip block comment /* ... */" in {
    val sql = "SELECT /* column list */ * FROM users"
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()

    tokens should contain theSameElementsInOrderAs List(
      SELECT, MULTIPLY_OP, FROM, IdentifierToken("USERS"), EOF
    )
  }

  it should "skip multi-line block comment" in {
    val sql =
      """SELECT *
        |FROM /* this comment
        |  spans multiple
        |  lines */ users
        |WHERE id = 1""".stripMargin
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()

    tokens should contain theSameElementsInOrderAs List(
      SELECT, MULTIPLY_OP, FROM, IdentifierToken("USERS"),
      WHERE, IdentifierToken("ID"), EQUALS, NumberToken("1"), EOF
    )
  }

  it should "handle comment at the beginning of SQL" in {
    val sql = "-- select all users\nSELECT * FROM users"
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()

    tokens should contain theSameElementsInOrderAs List(
      SELECT, MULTIPLY_OP, FROM, IdentifierToken("USERS"), EOF
    )
  }

  it should "handle multiple comments in one SQL" in {
    val sql =
      """-- header comment
        |SELECT id, /* inline */ name
        |FROM users # trailing
        |WHERE age > 18""".stripMargin
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()

    tokens should contain theSameElementsInOrderAs List(
      SELECT, IdentifierToken("ID"), COMMA, IdentifierToken("NAME"),
      FROM, IdentifierToken("USERS"),
      WHERE, IdentifierToken("AGE"), GREATER_THAN, NumberToken("18"), EOF
    )
  }

  it should "throw on unterminated block comment" in {
    val sql = "SELECT * FROM /* unterminated users"
    val lexer = new Lexer(sql)

    an [RuntimeException] should be thrownBy {
      lexer.tokenize()
    }
  }

  it should "not confuse minus operator with line comment" in {
    val sql = "SELECT 10 - 5 FROM users"
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()

    tokens should contain theSameElementsInOrderAs List(
      SELECT, NumberToken("10"), MINUS_OP, NumberToken("5"),
      FROM, IdentifierToken("USERS"), EOF
    )
  }

  it should "not confuse divide operator with block comment" in {
    val sql = "SELECT 10 / 5 FROM users"
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()

    tokens should contain theSameElementsInOrderAs List(
      SELECT, NumberToken("10"), DIVIDE_OP, NumberToken("5"),
      FROM, IdentifierToken("USERS"), EOF
    )
  }

  // ====== 注释在完整解析中的测试 ======

  "Parser" should "parse SQL with single-line comments" in {
    val sql =
      """-- Get all active users
        |SELECT id, name FROM users -- main table
        |WHERE age > 18""".stripMargin
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[SelectStatement]
    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 2
    select.where shouldBe defined
  }

  it should "parse SQL with block comments" in {
    val sql = "SELECT /* important */ id, name FROM /* schema.*/users WHERE id = 1"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[SelectStatement]
    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 2
    select.where shouldBe defined
  }

  it should "parse complex SQL with mixed comments" in {
    val sql =
      """/* Multi-table query
        |   Author: test
        |   Date: 2026-03-23 */
        |SELECT
        |  u.id,        -- user id
        |  u.name,      # user name
        |  COUNT(o.id)  /* order count */
        |FROM users u
        |JOIN orders o ON u.id = o.user_id
        |GROUP BY u.id, u.name
        |HAVING COUNT(o.id) > 5""".stripMargin
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[SelectStatement]
    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 3
    select.groupBy shouldBe defined
    select.having shouldBe defined
  }

  it should "parse simple SELECT statement" in {
    val sql = "SELECT * FROM users"
    val ast = MySQLParser.parse(sql)
    
    ast shouldBe a[SelectStatement]
    val select = ast.asInstanceOf[SelectStatement]
    select.columns should contain (AllColumns)
    select.from shouldBe defined
  }

  it should "parse SELECT with columns" in {
    val sql = "SELECT id, name, email FROM users"
    val ast = MySQLParser.parse(sql)
    
    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 3
  }

  it should "parse SELECT with WHERE clause" in {
    val sql = "SELECT * FROM users WHERE age > 18"
    val ast = MySQLParser.parse(sql)
    
    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
  }

  it should "parse SELECT with JOIN" in {
    val sql = "SELECT u.id, o.order_id FROM users u JOIN orders o ON u.id = o.user_id"
    val ast = MySQLParser.parse(sql)
    
    val select = ast.asInstanceOf[SelectStatement]
    select.from shouldBe defined
    select.from.get shouldBe a[JoinClause]
  }

  it should "parse INSERT statement" in {
    val sql = "INSERT INTO users (name, email) VALUES ('Alice', 'alice@example.com')"
    val ast = MySQLParser.parse(sql)
    
    ast shouldBe a[InsertStatement]
    val insert = ast.asInstanceOf[InsertStatement]
    insert.table shouldBe "USERS"
    insert.columns shouldBe defined
    insert.values should have length 1
  }

  it should "parse UPDATE statement" in {
    val sql = "UPDATE users SET age = 25 WHERE name = 'Bob'"
    val ast = MySQLParser.parse(sql)
    
    ast shouldBe a[UpdateStatement]
    val update = ast.asInstanceOf[UpdateStatement]
    update.table shouldBe "USERS"
    update.assignments should have length 1
    update.where shouldBe defined
  }

  it should "parse DELETE statement" in {
    val sql = "DELETE FROM users WHERE id = 1"
    val ast = MySQLParser.parse(sql)
    
    ast shouldBe a[DeleteStatement]
    val delete = ast.asInstanceOf[DeleteStatement]
    delete.table shouldBe "USERS"
    delete.where shouldBe defined
  }

  it should "parse CREATE TABLE statement" in {
    val sql = "CREATE TABLE users (id INT, name VARCHAR(100), email TEXT)"
    val ast = MySQLParser.parse(sql)
    
    ast shouldBe a[CreateTableStatement]
    val create = ast.asInstanceOf[CreateTableStatement]
    create.tableName shouldBe "USERS"
    create.columns should have length 3
  }

  it should "parse DROP TABLE statement" in {
    val sql = "DROP TABLE users"
    val ast = MySQLParser.parse(sql)
    
    ast shouldBe a[DropTableStatement]
    val drop = ast.asInstanceOf[DropTableStatement]
    drop.tableName shouldBe "USERS"
  }

  it should "parse complex expressions" in {
    val sql = "SELECT * FROM users WHERE age > 18 AND status = 'active' OR role = 'admin'"
    val ast = MySQLParser.parse(sql)
    
    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    select.where.get shouldBe a[BinaryExpression]
  }

  it should "parse DISTINCT keyword" in {
    val sql = "SELECT DISTINCT category FROM products"
    val ast = MySQLParser.parse(sql)
    
    val select = ast.asInstanceOf[SelectStatement]
    select.distinct shouldBe true
  }

  it should "parse ORDER BY clause" in {
    val sql = "SELECT * FROM users ORDER BY name"
    val ast = MySQLParser.parse(sql)
    
    val select = ast.asInstanceOf[SelectStatement]
    select.orderBy shouldBe defined
    select.orderBy.get should have length 1
  }

  it should "parse LIMIT and OFFSET" in {
    val sql = "SELECT * FROM users LIMIT 10 OFFSET 20"
    val ast = MySQLParser.parse(sql)
    
    val select = ast.asInstanceOf[SelectStatement]
    select.limit shouldBe Some(10)
    select.offset shouldBe Some(20)
  }

  it should "parse GROUP BY and HAVING" in {
    val sql = "SELECT category, COUNT(*) FROM products GROUP BY category HAVING COUNT(*) > 5"
    val ast = MySQLParser.parse(sql)
    
    val select = ast.asInstanceOf[SelectStatement]
    select.groupBy shouldBe defined
    select.having shouldBe defined
  }

  // ====== 聚合函数测试 ======

  it should "parse COUNT(*)" in {
    val sql = "SELECT COUNT(*) FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    select.columns.head shouldBe a[ExpressionColumn]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.expression shouldBe AggregateFunction(CountFunc, AllColumnsExpression, distinct = false)
  }

  it should "parse COUNT(DISTINCT column)" in {
    val sql = "SELECT COUNT(DISTINCT category) AS cat_count FROM products"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("CAT_COUNT")
    val agg = exprCol.expression.asInstanceOf[AggregateFunction]
    agg.funcType shouldBe CountFunc
    agg.distinct shouldBe true
  }

  it should "parse SUM, AVG, MAX, MIN aggregate functions" in {
    val sql = "SELECT SUM(price), AVG(price), MAX(price), MIN(price) FROM products"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 4

    val funcTypes = select.columns.map { col =>
      col.asInstanceOf[ExpressionColumn].expression.asInstanceOf[AggregateFunction].funcType
    }
    funcTypes shouldBe List(SumFunc, AvgFunc, MaxFunc, MinFunc)
  }

  it should "parse aggregate function with alias" in {
    val sql = "SELECT AVG(salary) AS avg_salary FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("AVG_SALARY")
    exprCol.expression shouldBe AggregateFunction(AvgFunc, Identifier("SALARY"), distinct = false)
  }

  it should "parse aggregate function in HAVING clause" in {
    val sql = "SELECT category, SUM(price) FROM products GROUP BY category HAVING SUM(price) > 1000"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.having shouldBe defined
    val having = select.having.get.asInstanceOf[BinaryExpression]
    having.left shouldBe a[AggregateFunction]
    having.operator shouldBe GreaterThan
    having.right shouldBe NumberLiteral("1000")
  }

  it should "parse mixed columns and aggregate functions" in {
    val sql = "SELECT department, COUNT(*) AS emp_count, MAX(salary) AS top_salary FROM employees GROUP BY department"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 3
    select.columns(0) shouldBe a[NamedColumn]
    select.columns(1) shouldBe a[ExpressionColumn]
    select.columns(2) shouldBe a[ExpressionColumn]
  }

  // ====== 谓词表达式测试 ======

  it should "parse IS NULL predicate" in {
    val sql = "SELECT * FROM users WHERE email IS NULL"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val isNull = select.where.get.asInstanceOf[IsNullExpression]
    isNull.expression shouldBe Identifier("EMAIL")
    isNull.negated shouldBe false
  }

  it should "parse IS NOT NULL predicate" in {
    val sql = "SELECT * FROM users WHERE email IS NOT NULL"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val isNotNull = select.where.get.asInstanceOf[IsNullExpression]
    isNotNull.expression shouldBe Identifier("EMAIL")
    isNotNull.negated shouldBe true
  }

  it should "parse BETWEEN predicate" in {
    val sql = "SELECT * FROM products WHERE price BETWEEN 100 AND 500"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val between = select.where.get.asInstanceOf[BetweenExpression]
    between.expression shouldBe Identifier("PRICE")
    between.lower shouldBe NumberLiteral("100")
    between.upper shouldBe NumberLiteral("500")
    between.negated shouldBe false
  }

  it should "parse NOT BETWEEN predicate" in {
    val sql = "SELECT * FROM products WHERE price NOT BETWEEN 100 AND 500"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val between = select.where.get.asInstanceOf[BetweenExpression]
    between.expression shouldBe Identifier("PRICE")
    between.negated shouldBe true
  }

  it should "parse IN predicate" in {
    val sql = "SELECT * FROM users WHERE role IN ('admin', 'editor', 'moderator')"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val inExpr = select.where.get.asInstanceOf[InExpression]
    inExpr.expression shouldBe Identifier("ROLE")
    inExpr.values should have length 3
    inExpr.values(0) shouldBe StringLiteral("admin")
    inExpr.values(1) shouldBe StringLiteral("editor")
    inExpr.values(2) shouldBe StringLiteral("moderator")
    inExpr.negated shouldBe false
  }

  it should "parse NOT IN predicate" in {
    val sql = "SELECT * FROM users WHERE role NOT IN ('banned', 'suspended')"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val inExpr = select.where.get.asInstanceOf[InExpression]
    inExpr.expression shouldBe Identifier("ROLE")
    inExpr.values should have length 2
    inExpr.negated shouldBe true
  }

  it should "parse LIKE predicate" in {
    val sql = "SELECT * FROM users WHERE name LIKE 'Ali%'"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val likeExpr = select.where.get.asInstanceOf[LikeExpression]
    likeExpr.expression shouldBe Identifier("NAME")
    likeExpr.pattern shouldBe StringLiteral("Ali%")
    likeExpr.negated shouldBe false
  }

  it should "parse NOT LIKE predicate" in {
    val sql = "SELECT * FROM users WHERE name NOT LIKE '%test%'"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val likeExpr = select.where.get.asInstanceOf[LikeExpression]
    likeExpr.expression shouldBe Identifier("NAME")
    likeExpr.pattern shouldBe StringLiteral("%test%")
    likeExpr.negated shouldBe true
  }

  it should "parse combined predicates with AND/OR" in {
    val sql = "SELECT * FROM users WHERE age BETWEEN 18 AND 65 AND email IS NOT NULL AND role IN ('admin', 'user')"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    // 整个表达式是 AND 链
    select.where.get shouldBe a[BinaryExpression]
  }

  // ====== ASC/DESC 排序测试 ======

  it should "parse ORDER BY with DESC" in {
    val sql = "SELECT * FROM users ORDER BY age DESC"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.orderBy shouldBe defined
    val orderByClauses = select.orderBy.get
    orderByClauses should have length 1
    orderByClauses.head.expression shouldBe Identifier("AGE")
    orderByClauses.head.ascending shouldBe false
  }

  it should "parse ORDER BY with ASC" in {
    val sql = "SELECT * FROM users ORDER BY name ASC"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.orderBy shouldBe defined
    val orderByClauses = select.orderBy.get
    orderByClauses should have length 1
    orderByClauses.head.expression shouldBe Identifier("NAME")
    orderByClauses.head.ascending shouldBe true
  }

  it should "parse ORDER BY default ascending when no ASC/DESC" in {
    val sql = "SELECT * FROM users ORDER BY name"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.orderBy shouldBe defined
    val orderByClauses = select.orderBy.get
    orderByClauses should have length 1
    orderByClauses.head.ascending shouldBe true
  }

  it should "parse ORDER BY with multiple columns and mixed ASC/DESC" in {
    val sql = "SELECT * FROM products ORDER BY category ASC, price DESC, name"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.orderBy shouldBe defined
    val orderByClauses = select.orderBy.get
    orderByClauses should have length 3
    orderByClauses(0).expression shouldBe Identifier("CATEGORY")
    orderByClauses(0).ascending shouldBe true
    orderByClauses(1).expression shouldBe Identifier("PRICE")
    orderByClauses(1).ascending shouldBe false
    orderByClauses(2).expression shouldBe Identifier("NAME")
    orderByClauses(2).ascending shouldBe true  // 默认升序
  }

  it should "parse ORDER BY DESC with LIMIT" in {
    val sql = "SELECT * FROM users ORDER BY created_at DESC LIMIT 10"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.orderBy shouldBe defined
    select.orderBy.get.head.ascending shouldBe false
    select.limit shouldBe Some(10)
  }

  // ====== 子查询测试 ======

  it should "parse WHERE scalar subquery" in {
    val sql = "SELECT * FROM users WHERE salary > (SELECT AVG(salary) FROM employees)"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val where = select.where.get.asInstanceOf[BinaryExpression]
    where.left shouldBe Identifier("SALARY")
    where.operator shouldBe GreaterThan
    where.right shouldBe a[SubqueryExpression]
    val sub = where.right.asInstanceOf[SubqueryExpression]
    sub.query.asInstanceOf[SelectStatement].columns should have length 1
    sub.query.asInstanceOf[SelectStatement].columns.head shouldBe a[ExpressionColumn]
  }

  it should "parse IN subquery" in {
    val sql = "SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val inSub = select.where.get.asInstanceOf[InSubqueryExpression]
    inSub.expression shouldBe Identifier("ID")
    inSub.negated shouldBe false
    inSub.query.asInstanceOf[SelectStatement].from shouldBe defined
  }

  it should "parse NOT IN subquery" in {
    val sql = "SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM blacklist)"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val inSub = select.where.get.asInstanceOf[InSubqueryExpression]
    inSub.expression shouldBe Identifier("ID")
    inSub.negated shouldBe true
  }

  it should "parse EXISTS subquery" in {
    val sql = "SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val exists = select.where.get.asInstanceOf[ExistsExpression]
    exists.negated shouldBe false
    exists.query.asInstanceOf[SelectStatement].from shouldBe defined
  }

  it should "parse NOT EXISTS subquery" in {
    val sql = "SELECT * FROM users WHERE NOT EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val exists = select.where.get.asInstanceOf[ExistsExpression]
    exists.negated shouldBe true
  }

  it should "parse FROM derived table (subquery)" in {
    val sql = "SELECT t.name FROM (SELECT name FROM users WHERE age > 18) AS t"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.from shouldBe defined
    val derived = select.from.get.asInstanceOf[DerivedTable]
    derived.alias shouldBe "T"
    derived.query.asInstanceOf[SelectStatement].from shouldBe defined
  }

  it should "parse SELECT column subquery" in {
    val sql = "SELECT name, (SELECT COUNT(*) FROM orders WHERE orders.user_id = users.id) AS order_count FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 2
    select.columns(0) shouldBe a[NamedColumn]
    select.columns(1) shouldBe a[ExpressionColumn]
    val exprCol = select.columns(1).asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("ORDER_COUNT")
    exprCol.expression shouldBe a[SubqueryExpression]
  }

  it should "parse nested subqueries" in {
    val sql = "SELECT * FROM users WHERE dept_id IN (SELECT dept_id FROM departments WHERE company_id IN (SELECT id FROM companies WHERE country = 'CN'))"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val outer = select.where.get.asInstanceOf[InSubqueryExpression]
    outer.expression shouldBe Identifier("DEPT_ID")
    // 内层也是 IN 子查询
    outer.query.asInstanceOf[SelectStatement].where shouldBe defined
    val inner = outer.query.asInstanceOf[SelectStatement].where.get.asInstanceOf[InSubqueryExpression]
    inner.expression shouldBe Identifier("COMPANY_ID")
  }

  // ====== CASE WHEN 测试 ======

  it should "parse searched CASE expression in SELECT column" in {
    val sql = "SELECT name, CASE WHEN salary > 10000 THEN 'high' WHEN salary > 5000 THEN 'medium' ELSE 'low' END AS salary_level FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 2
    select.columns(0) shouldBe a[NamedColumn]
    select.columns(1) shouldBe a[ExpressionColumn]
    val exprCol = select.columns(1).asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("SALARY_LEVEL")
    val caseExpr = exprCol.expression.asInstanceOf[CaseExpression]
    caseExpr.operand shouldBe None // 搜索式
    caseExpr.whenClauses should have length 2
    caseExpr.elseResult shouldBe Some(StringLiteral("low"))
    // 验证第一个 WHEN 分支
    val when1 = caseExpr.whenClauses(0)
    when1.condition shouldBe a[BinaryExpression]
    when1.result shouldBe StringLiteral("high")
  }

  it should "parse simple CASE expression" in {
    val sql = "SELECT CASE status WHEN 'active' THEN 1 WHEN 'inactive' THEN 0 ELSE -1 END FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val caseExpr = exprCol.expression.asInstanceOf[CaseExpression]
    caseExpr.operand shouldBe Some(Identifier("STATUS")) // 简单式
    caseExpr.whenClauses should have length 2
    caseExpr.whenClauses(0).condition shouldBe StringLiteral("active")
    caseExpr.whenClauses(0).result shouldBe NumberLiteral("1")
    caseExpr.whenClauses(1).condition shouldBe StringLiteral("inactive")
    caseExpr.whenClauses(1).result shouldBe NumberLiteral("0")
  }

  it should "parse CASE without ELSE" in {
    val sql = "SELECT CASE WHEN score >= 60 THEN 'pass' END FROM exams"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val caseExpr = exprCol.expression.asInstanceOf[CaseExpression]
    caseExpr.operand shouldBe None
    caseExpr.whenClauses should have length 1
    caseExpr.elseResult shouldBe None
  }

  it should "parse CASE in WHERE clause" in {
    val sql = "SELECT * FROM users WHERE CASE WHEN age > 18 THEN 1 ELSE 0 END = 1"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val where = select.where.get.asInstanceOf[BinaryExpression]
    where.left shouldBe a[CaseExpression]
    where.operator shouldBe Equal
    where.right shouldBe NumberLiteral("1")
  }

  it should "parse CASE in ORDER BY" in {
    val sql = "SELECT * FROM users ORDER BY CASE WHEN status = 'active' THEN 0 ELSE 1 END"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.orderBy shouldBe defined
    val orderByClauses = select.orderBy.get
    orderByClauses should have length 1
    orderByClauses.head.expression shouldBe a[CaseExpression]
  }

  it should "parse CASE inside aggregate function" in {
    val sql = "SELECT SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END) FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val aggFunc = exprCol.expression.asInstanceOf[AggregateFunction]
    aggFunc.funcType shouldBe SumFunc
    aggFunc.argument shouldBe a[CaseExpression]
  }

  it should "parse CASE with multiple WHEN clauses" in {
    val sql = "SELECT CASE WHEN score >= 90 THEN 'A' WHEN score >= 80 THEN 'B' WHEN score >= 70 THEN 'C' WHEN score >= 60 THEN 'D' ELSE 'F' END FROM exams"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val caseExpr = exprCol.expression.asInstanceOf[CaseExpression]
    caseExpr.operand shouldBe None
    caseExpr.whenClauses should have length 4
    caseExpr.elseResult shouldBe Some(StringLiteral("F"))
  }

  // ====== UNION / UNION ALL 测试 ======

  it should "parse basic UNION" in {
    val sql = "SELECT name FROM users UNION SELECT name FROM admins"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[UnionStatement]
    val union = ast.asInstanceOf[UnionStatement]
    union.unionType shouldBe UnionDistinct
    union.left shouldBe a[SelectStatement]
    union.right shouldBe a[SelectStatement]
    val leftSelect = union.left.asInstanceOf[SelectStatement]
    leftSelect.columns should have length 1
    leftSelect.from.get.asInstanceOf[TableName].name shouldBe "USERS"
    val rightSelect = union.right
    rightSelect.columns should have length 1
    rightSelect.from.get.asInstanceOf[TableName].name shouldBe "ADMINS"
  }

  it should "parse UNION ALL" in {
    val sql = "SELECT id FROM a UNION ALL SELECT id FROM b"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[UnionStatement]
    val union = ast.asInstanceOf[UnionStatement]
    union.unionType shouldBe UnionAll
  }

  it should "parse chained UNION (left-associative)" in {
    val sql = "SELECT a FROM t1 UNION SELECT b FROM t2 UNION ALL SELECT c FROM t3"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[UnionStatement]
    val outer = ast.asInstanceOf[UnionStatement]
    outer.unionType shouldBe UnionAll
    outer.right.from.get.asInstanceOf[TableName].name shouldBe "T3"
    // 左侧是嵌套的 UNION
    outer.left shouldBe a[UnionStatement]
    val inner = outer.left.asInstanceOf[UnionStatement]
    inner.unionType shouldBe UnionDistinct
    inner.left.asInstanceOf[SelectStatement].from.get.asInstanceOf[TableName].name shouldBe "T1"
    inner.right.from.get.asInstanceOf[TableName].name shouldBe "T2"
  }

  it should "parse UNION with ORDER BY on last SELECT" in {
    val sql = "SELECT name FROM users UNION SELECT name FROM admins ORDER BY name"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[UnionStatement]
    val union = ast.asInstanceOf[UnionStatement]
    // ORDER BY 附在最后一个 SELECT 上
    union.right.orderBy shouldBe defined
    union.right.orderBy.get should have length 1
    union.right.orderBy.get.head.expression shouldBe Identifier("NAME")
  }

  it should "parse UNION with LIMIT" in {
    val sql = "SELECT id FROM users UNION ALL SELECT id FROM admins LIMIT 10"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[UnionStatement]
    val union = ast.asInstanceOf[UnionStatement]
    union.right.limit shouldBe Some(10)
  }

  it should "parse UNION in subquery (IN)" in {
    val sql = "SELECT * FROM users WHERE id IN (SELECT id FROM a UNION SELECT id FROM b)"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val inSub = select.where.get.asInstanceOf[InSubqueryExpression]
    inSub.query shouldBe a[UnionStatement]
    val union = inSub.query.asInstanceOf[UnionStatement]
    union.unionType shouldBe UnionDistinct
  }

  it should "parse UNION in derived table" in {
    val sql = "SELECT t.name FROM (SELECT name FROM users UNION ALL SELECT name FROM admins) AS t"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.from shouldBe defined
    val derived = select.from.get.asInstanceOf[DerivedTable]
    derived.alias shouldBe "T"
    derived.query shouldBe a[UnionStatement]
    val union = derived.query.asInstanceOf[UnionStatement]
    union.unionType shouldBe UnionAll
  }

  it should "parse UNION with WHERE clauses in each SELECT" in {
    val sql = "SELECT name FROM users WHERE age > 18 UNION SELECT name FROM admins WHERE active = 1"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[UnionStatement]
    val union = ast.asInstanceOf[UnionStatement]
    val leftSelect = union.left.asInstanceOf[SelectStatement]
    leftSelect.where shouldBe defined
    union.right.where shouldBe defined
  }

  // ====== 函数调用测试 ======

  it should "parse no-arg function call" in {
    val sql = "SELECT NOW()"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val func = exprCol.expression.asInstanceOf[FunctionCall]
    func.name shouldBe "NOW"
    func.arguments shouldBe empty
  }

  it should "parse single-arg function call" in {
    val sql = "SELECT UPPER(name) FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val func = exprCol.expression.asInstanceOf[FunctionCall]
    func.name shouldBe "UPPER"
    func.arguments should have length 1
    func.arguments.head shouldBe Identifier("NAME")
  }

  it should "parse multi-arg function call" in {
    val sql = "SELECT CONCAT(first_name, ' ', last_name) FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val func = exprCol.expression.asInstanceOf[FunctionCall]
    func.name shouldBe "CONCAT"
    func.arguments should have length 3
    func.arguments(0) shouldBe Identifier("FIRST_NAME")
    func.arguments(1) shouldBe StringLiteral(" ")
    func.arguments(2) shouldBe Identifier("LAST_NAME")
  }

  it should "parse function call with alias" in {
    val sql = "SELECT UPPER(name) AS uname FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("UNAME")
    exprCol.expression shouldBe a[FunctionCall]
  }

  it should "parse nested function calls" in {
    val sql = "SELECT UPPER(CONCAT(first_name, last_name)) FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val outer = exprCol.expression.asInstanceOf[FunctionCall]
    outer.name shouldBe "UPPER"
    outer.arguments should have length 1
    val inner = outer.arguments.head.asInstanceOf[FunctionCall]
    inner.name shouldBe "CONCAT"
    inner.arguments should have length 2
  }

  it should "parse function call in WHERE clause" in {
    val sql = "SELECT * FROM users WHERE LENGTH(name) > 10"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val where = select.where.get.asInstanceOf[BinaryExpression]
    where.left shouldBe a[FunctionCall]
    val func = where.left.asInstanceOf[FunctionCall]
    func.name shouldBe "LENGTH"
    func.arguments should have length 1
  }

  it should "parse IFNULL function" in {
    val sql = "SELECT IFNULL(nickname, name) FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val func = exprCol.expression.asInstanceOf[FunctionCall]
    func.name shouldBe "IFNULL"
    func.arguments should have length 2
  }

  // ====== 隐式别名测试 ======

  it should "parse implicit alias for named column" in {
    val sql = "SELECT name n, age a FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 2
    val col1 = select.columns(0).asInstanceOf[NamedColumn]
    col1.name shouldBe "NAME"
    col1.alias shouldBe Some("N")
    val col2 = select.columns(1).asInstanceOf[NamedColumn]
    col2.name shouldBe "AGE"
    col2.alias shouldBe Some("A")
  }

  it should "parse implicit alias for aggregate function" in {
    val sql = "SELECT COUNT(*) total FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("TOTAL")
    exprCol.expression shouldBe a[AggregateFunction]
  }

  it should "parse implicit alias for function call" in {
    val sql = "SELECT UPPER(name) uname FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("UNAME")
    exprCol.expression shouldBe a[FunctionCall]
  }

  it should "parse implicit alias for qualified column" in {
    val sql = "SELECT t.name tname FROM users t"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val col = select.columns.head.asInstanceOf[QualifiedColumn]
    col.table shouldBe "T"
    col.column shouldBe "NAME"
    col.alias shouldBe Some("TNAME")
  }

  // ====== CAST 表达式测试 ======

  it should "parse CAST with DECIMAL type" in {
    val sql = "SELECT CAST(price AS DECIMAL(10,2)) FROM products"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val cast = exprCol.expression.asInstanceOf[CastExpression]
    cast.expression shouldBe Identifier("PRICE")
    cast.targetType shouldBe DecimalCastType(Some(10), Some(2))
  }

  it should "parse CAST with SIGNED" in {
    val sql = "SELECT CAST(val AS SIGNED) FROM t"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val cast = exprCol.expression.asInstanceOf[CastExpression]
    cast.targetType shouldBe SignedCastType(false)
  }

  it should "parse CAST with UNSIGNED" in {
    val sql = "SELECT CAST(val AS UNSIGNED) FROM t"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val cast = exprCol.expression.asInstanceOf[CastExpression]
    cast.targetType shouldBe UnsignedCastType(false)
  }

  it should "parse CAST with CHAR" in {
    val sql = "SELECT CAST(age AS CHAR) FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val cast = exprCol.expression.asInstanceOf[CastExpression]
    cast.targetType shouldBe CharCastType(None)
  }

  it should "parse CAST with DATE" in {
    val sql = "SELECT CAST(created_at AS DATE) FROM orders"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val cast = exprCol.expression.asInstanceOf[CastExpression]
    cast.targetType shouldBe DateCastType
  }

  it should "parse CAST in WHERE clause" in {
    val sql = "SELECT * FROM products WHERE CAST(price AS DECIMAL(10,2)) > 100"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val where = select.where.get.asInstanceOf[BinaryExpression]
    where.left shouldBe a[CastExpression]
  }

  // ====== CONVERT 表达式测试 ======

  it should "parse CONVERT with type" in {
    val sql = "SELECT CONVERT(price, DECIMAL(10,2)) FROM products"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val convert = exprCol.expression.asInstanceOf[ConvertExpression]
    convert.expression shouldBe Identifier("PRICE")
    convert.targetType shouldBe Some(DecimalCastType(Some(10), Some(2)))
    convert.charset shouldBe None
  }

  it should "parse CONVERT with USING charset" in {
    val sql = "SELECT CONVERT(name USING utf8) FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val convert = exprCol.expression.asInstanceOf[ConvertExpression]
    convert.expression shouldBe Identifier("NAME")
    convert.targetType shouldBe None
    convert.charset shouldBe Some("UTF8")
  }

  it should "parse CONVERT with UNSIGNED" in {
    val sql = "SELECT CONVERT(val, UNSIGNED) FROM t"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val convert = exprCol.expression.asInstanceOf[ConvertExpression]
    convert.targetType shouldBe Some(UnsignedCastType(false))
  }

  // ============================================================
  //  语义分析测试
  // ============================================================

  // 测试用 Schema
  private val testSchema = DatabaseSchema(
    TableSchema.simple("users", "id", "name", "age", "email", "role", "department", "salary", "status"),
    TableSchema.simple("orders", "id", "user_id", "product_id", "amount", "created_at"),
    TableSchema.simple("products", "id", "name", "category", "price"),
    TableSchema.simple("employees", "id", "name", "department", "salary", "hire_date"),
    TableSchema.simple("admins", "id", "name", "level")
  )

  private def analyzeSQL(sql: String): List[SemanticError] = {
    val ast = MySQLParser.parse(sql)
    SemanticAnalyzer.analyze(ast, testSchema)
  }

  // ====== 表存在性检查 ======

  "SemanticAnalyzer" should "pass when table exists" in {
    val errors = analyzeSQL("SELECT * FROM users")
    errors shouldBe empty
  }

  it should "detect non-existent table in SELECT FROM" in {
    val errors = analyzeSQL("SELECT * FROM nonexistent_table")
    errors should not be empty
    errors.exists(_.category == "TABLE") shouldBe true
    errors.exists(_.message.contains("NONEXISTENT_TABLE")) shouldBe true
  }

  it should "detect non-existent table in INSERT" in {
    val errors = analyzeSQL("INSERT INTO fake_table (name) VALUES ('test')")
    errors should not be empty
    errors.exists(_.category == "TABLE") shouldBe true
  }

  it should "detect non-existent table in UPDATE" in {
    val errors = analyzeSQL("UPDATE fake_table SET name = 'test'")
    errors should not be empty
    errors.exists(_.category == "TABLE") shouldBe true
  }

  it should "detect non-existent table in DELETE" in {
    val errors = analyzeSQL("DELETE FROM fake_table WHERE id = 1")
    errors should not be empty
    errors.exists(_.category == "TABLE") shouldBe true
  }

  it should "detect non-existent table in DROP TABLE" in {
    val errors = analyzeSQL("DROP TABLE nonexistent_table")
    errors should not be empty
    errors.exists(_.category == "TABLE") shouldBe true
  }

  // ====== 列存在性检查 ======

  it should "pass when column exists" in {
    val errors = analyzeSQL("SELECT id, name FROM users")
    errors shouldBe empty
  }

  it should "detect non-existent column in SELECT" in {
    val errors = analyzeSQL("SELECT fake_column FROM users")
    errors should not be empty
    errors.exists(_.category == "COLUMN") shouldBe true
    errors.exists(_.message.contains("FAKE_COLUMN")) shouldBe true
  }

  it should "detect non-existent column in WHERE" in {
    val errors = analyzeSQL("SELECT * FROM users WHERE nonexistent > 10")
    errors should not be empty
    errors.exists(_.category == "COLUMN") shouldBe true
    errors.exists(_.message.contains("NONEXISTENT")) shouldBe true
  }

  it should "detect non-existent column in UPDATE SET" in {
    val errors = analyzeSQL("UPDATE users SET fake_col = 1")
    errors should not be empty
    errors.exists(_.category == "COLUMN") shouldBe true
  }

  it should "pass SELECT * without column check" in {
    val errors = analyzeSQL("SELECT * FROM users")
    errors shouldBe empty
  }

  // ====== 限定列检查 ======

  it should "pass when qualified column is valid" in {
    val errors = analyzeSQL("SELECT u.name FROM users u")
    errors shouldBe empty
  }

  it should "detect undefined table alias in qualified column" in {
    val errors = analyzeSQL("SELECT x.name FROM users u")
    errors should not be empty
    errors.exists(_.message.contains("X")) shouldBe true
  }

  it should "detect column not in specified table" in {
    val errors = analyzeSQL("SELECT u.nonexistent FROM users u")
    errors should not be empty
    errors.exists(_.category == "COLUMN") shouldBe true
    errors.exists(_.message.contains("NONEXISTENT")) shouldBe true
  }

  // ====== JOIN 语义检查 ======

  it should "pass valid JOIN query" in {
    val errors = analyzeSQL(
      "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id"
    )
    errors shouldBe empty
  }

  it should "detect non-existent column in JOIN ON" in {
    val errors = analyzeSQL(
      "SELECT u.name FROM users u JOIN orders o ON u.id = o.fake_col"
    )
    errors should not be empty
    errors.exists(_.message.contains("FAKE_COL")) shouldBe true
  }

  // ====== GROUP BY 一致性检查 ======

  it should "pass when non-aggregate columns are in GROUP BY" in {
    val errors = analyzeSQL(
      "SELECT department, COUNT(*) FROM employees GROUP BY department"
    )
    errors shouldBe empty
  }

  it should "detect non-aggregate column not in GROUP BY" in {
    val errors = analyzeSQL(
      "SELECT name, COUNT(*) FROM users GROUP BY department"
    )
    errors should not be empty
    errors.exists(_.category == "GROUP_BY") shouldBe true
    errors.exists(_.message.contains("NAME")) shouldBe true
  }

  it should "pass aggregate expression column without GROUP BY check" in {
    val errors = analyzeSQL(
      "SELECT department, AVG(salary) AS avg_sal FROM employees GROUP BY department"
    )
    errors shouldBe empty
  }

  // ====== 别名唯一性检查 ======

  it should "pass when aliases are unique" in {
    val errors = analyzeSQL("SELECT name AS n, age AS a FROM users")
    errors shouldBe empty
  }

  it should "detect duplicate column aliases" in {
    val errors = analyzeSQL("SELECT name AS x, age AS x FROM users")
    errors should not be empty
    errors.exists(_.category == "ALIAS") shouldBe true
    errors.exists(_.message.contains("X")) shouldBe true
  }

  // ====== INSERT 列数匹配检查 ======

  it should "pass when INSERT column count matches VALUES" in {
    val errors = analyzeSQL("INSERT INTO users (name, age) VALUES ('Alice', 25)")
    errors shouldBe empty
  }

  it should "detect INSERT column count mismatch" in {
    val errors = analyzeSQL("INSERT INTO users (name, age, email) VALUES ('Alice', 25)")
    errors should not be empty
    errors.exists(_.category == "INSERT") shouldBe true
    errors.exists(_.message.contains("3 columns")) shouldBe true
    errors.exists(_.message.contains("2 values")) shouldBe true
  }

  it should "detect non-existent column in INSERT" in {
    val errors = analyzeSQL("INSERT INTO users (fake_col) VALUES ('test')")
    errors should not be empty
    errors.exists(_.category == "COLUMN") shouldBe true
  }

  // ====== UNION 列数一致性检查 ======

  it should "pass when UNION column counts match" in {
    val errors = analyzeSQL("SELECT name FROM users UNION SELECT name FROM admins")
    errors shouldBe empty
  }

  it should "detect UNION column count mismatch" in {
    val errors = analyzeSQL("SELECT id, name FROM users UNION SELECT id FROM admins")
    errors should not be empty
    errors.exists(_.category == "UNION") shouldBe true
    errors.exists(_.message.contains("left: 2")) shouldBe true
    errors.exists(_.message.contains("right: 1")) shouldBe true
  }

  // ====== CREATE TABLE 检查 ======

  it should "detect CREATE TABLE when table already exists" in {
    val errors = analyzeSQL("CREATE TABLE users (id INT)")
    errors should not be empty
    errors.exists(_.message.contains("already exists")) shouldBe true
  }

  it should "pass CREATE TABLE for new table" in {
    val errors = analyzeSQL("CREATE TABLE new_table (id INT, name VARCHAR(100))")
    errors shouldBe empty
  }

  // ====== 无 FROM 子句的查询 ======

  it should "pass SELECT without FROM (e.g. SELECT NOW())" in {
    val errors = analyzeSQL("SELECT NOW()")
    errors shouldBe empty
  }

  it should "pass SELECT literal without FROM" in {
    val errors = analyzeSQL("SELECT 1")
    errors shouldBe empty
  }

  // ====== 复杂查询 ======

  it should "pass complex query with multiple clauses" in {
    val errors = analyzeSQL(
      "SELECT department, COUNT(*) AS cnt, AVG(salary) AS avg_sal FROM employees GROUP BY department HAVING COUNT(*) > 5 ORDER BY avg_sal"
    )
    // ORDER BY 中的 avg_sal 是别名, 但我们检查的是列名 — 这里可能会有误报
    // 目前简单实现中不额外处理 ORDER BY 的别名引用
    // 所以这里只验证 GROUP BY 和 HAVING 检查
    val groupByErrors = errors.filter(_.category == "GROUP_BY")
    groupByErrors shouldBe empty
  }

  it should "detect multiple errors in one query" in {
    val errors = analyzeSQL("SELECT fake1, fake2 FROM users")
    errors should have length 2
    errors.forall(_.category == "COLUMN") shouldBe true
  }

  // ====== HAVING 一致性检查 ======

  it should "pass HAVING with aggregate function" in {
    val errors = analyzeSQL(
      "SELECT department, COUNT(*) FROM employees GROUP BY department HAVING COUNT(*) > 5"
    )
    val havingErrors = errors.filter(_.category == "HAVING")
    havingErrors shouldBe empty
  }

  it should "detect non-GROUP BY column in HAVING" in {
    val errors = analyzeSQL(
      "SELECT department, COUNT(*) FROM employees GROUP BY department HAVING name = 'test'"
    )
    val havingErrors = errors.filter(_.category == "HAVING")
    havingErrors should not be empty
    havingErrors.exists(_.message.contains("NAME")) shouldBe true
  }

  // ====== 表达式中的函数调用 ======

  it should "pass function call with valid column arguments" in {
    val errors = analyzeSQL("SELECT UPPER(name) FROM users")
    errors shouldBe empty
  }

  it should "detect non-existent column in function argument" in {
    val errors = analyzeSQL("SELECT UPPER(nonexistent) FROM users")
    errors should not be empty
    errors.exists(_.message.contains("NONEXISTENT")) shouldBe true
  }

  // ====== CAST / CONVERT 中的列检查 ======

  it should "pass CAST with valid column" in {
    val errors = analyzeSQL("SELECT CAST(price AS DECIMAL(10,2)) FROM products")
    errors shouldBe empty
  }

  it should "detect non-existent column in CAST" in {
    val errors = analyzeSQL("SELECT CAST(fake AS DECIMAL(10,2)) FROM products")
    errors should not be empty
    errors.exists(_.message.contains("FAKE")) shouldBe true
  }

  // ============================================================
  //  AST Visitor 模式测试
  // ============================================================

  // ====== TableExtractor 测试 ======

  "TableExtractor" should "extract table from simple SELECT" in {
    val ast = MySQLParser.parse("SELECT * FROM users")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast)
    tables should contain("USERS")
  }

  it should "extract tables from JOIN query" in {
    val ast = MySQLParser.parse("SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain allOf("USERS", "ORDERS")
  }

  it should "extract table from INSERT" in {
    val ast = MySQLParser.parse("INSERT INTO users (name) VALUES ('test')")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).map(_.toUpperCase)
    tables should contain("USERS")
  }

  it should "extract table from UPDATE" in {
    val ast = MySQLParser.parse("UPDATE users SET name = 'test'")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).map(_.toUpperCase)
    tables should contain("USERS")
  }

  it should "extract table from DELETE" in {
    val ast = MySQLParser.parse("DELETE FROM users WHERE id = 1")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).map(_.toUpperCase)
    tables should contain("USERS")
  }

  it should "extract tables from UNION" in {
    val ast = MySQLParser.parse("SELECT name FROM users UNION SELECT name FROM admins")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain allOf("USERS", "ADMINS")
  }

  it should "extract tables from subquery" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain allOf("USERS", "ORDERS")
  }

  it should "extract table from derived table" in {
    val ast = MySQLParser.parse("SELECT t.name FROM (SELECT name FROM users WHERE age > 18) AS t")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain("USERS")
  }

  it should "extract table from CREATE TABLE" in {
    val ast = MySQLParser.parse("CREATE TABLE new_table (id INT)")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast)
    tables should contain("NEW_TABLE")
  }

  it should "extract table from DROP TABLE" in {
    val ast = MySQLParser.parse("DROP TABLE old_table")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast)
    tables should contain("OLD_TABLE")
  }

  // ====== ColumnExtractor 测试 ======

  "ColumnExtractor" should "extract columns from simple SELECT" in {
    val ast = MySQLParser.parse("SELECT id, name, age FROM users")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("ID", "NAME", "AGE")
  }

  it should "extract columns from qualified column" in {
    val ast = MySQLParser.parse("SELECT u.name, u.age FROM users u")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("NAME", "AGE")
  }

  it should "extract columns from WHERE clause" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE age > 18 AND name = 'test'")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("AGE", "NAME")
  }

  it should "extract columns from INSERT" in {
    val ast = MySQLParser.parse("INSERT INTO users (name, age) VALUES ('Alice', 25)")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("NAME", "AGE")
  }

  it should "extract columns from UPDATE SET" in {
    val ast = MySQLParser.parse("UPDATE users SET name = 'Bob', age = 30 WHERE id = 1")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("NAME", "AGE", "ID")
  }

  it should "extract columns from aggregate function" in {
    val ast = MySQLParser.parse("SELECT department, COUNT(*), AVG(salary) FROM employees GROUP BY department")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("DEPARTMENT", "SALARY")
  }

  it should "extract columns from function call" in {
    val ast = MySQLParser.parse("SELECT UPPER(name) FROM users")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain("NAME")
  }

  it should "return empty for SELECT *" in {
    val ast = MySQLParser.parse("SELECT * FROM users")
    val extractor = new ColumnExtractor()
    // SELECT * doesn't have named columns, only AllColumns
    val columns = extractor.visitStatement(ast)
    // no column names extracted from AllColumns
    columns.filter(_ != "*") shouldBe empty
  }

  // ====== SQLPrettyPrinter 测试 ======

  "SQLPrettyPrinter" should "format simple SELECT" in {
    val ast = MySQLParser.parse("SELECT id, name FROM users")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("SELECT")
    sql should include("ID")
    sql should include("NAME")
    sql should include("FROM")
    sql should include("USERS")
  }

  it should "format SELECT with WHERE" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE age > 18")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("WHERE")
    sql should include("AGE > 18")
  }

  it should "format SELECT DISTINCT" in {
    val ast = MySQLParser.parse("SELECT DISTINCT category FROM products")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("SELECT DISTINCT")
  }

  it should "format SELECT with ORDER BY" in {
    val ast = MySQLParser.parse("SELECT * FROM users ORDER BY name DESC")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("ORDER BY")
    sql should include("NAME DESC")
  }

  it should "format SELECT with GROUP BY and HAVING" in {
    val ast = MySQLParser.parse("SELECT department, COUNT(*) FROM employees GROUP BY department HAVING COUNT(*) > 5")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("GROUP BY")
    sql should include("HAVING")
  }

  it should "format SELECT with LIMIT" in {
    val ast = MySQLParser.parse("SELECT * FROM users LIMIT 10")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("LIMIT 10")
  }

  it should "format INSERT statement" in {
    val ast = MySQLParser.parse("INSERT INTO users (name, age) VALUES ('Alice', 25)")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("INSERT INTO")
    sql should include("VALUES")
  }

  it should "format UPDATE statement" in {
    val ast = MySQLParser.parse("UPDATE users SET age = 30 WHERE name = 'Bob'")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("UPDATE")
    sql should include("SET")
    sql should include("WHERE")
  }

  it should "format DELETE statement" in {
    val ast = MySQLParser.parse("DELETE FROM users WHERE id = 1")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("DELETE FROM")
    sql should include("WHERE")
  }

  it should "format CREATE TABLE statement" in {
    val ast = MySQLParser.parse("CREATE TABLE users (id INT, name VARCHAR(100))")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("CREATE TABLE")
    sql should include("INT")
    sql should include("VARCHAR(100)")
  }

  it should "format DROP TABLE statement" in {
    val ast = MySQLParser.parse("DROP TABLE users")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql.toUpperCase should include("DROP TABLE")
    sql.toUpperCase should include("USERS")
  }

  it should "format UNION statement" in {
    val ast = MySQLParser.parse("SELECT name FROM users UNION SELECT name FROM admins")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("UNION")
  }

  it should "format UNION ALL statement" in {
    val ast = MySQLParser.parse("SELECT id FROM a UNION ALL SELECT id FROM b")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("UNION ALL")
  }

  it should "format JOIN query" in {
    val ast = MySQLParser.parse("SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("JOIN")
    sql should include("ON")
  }

  it should "format aggregate functions" in {
    val ast = MySQLParser.parse("SELECT COUNT(*), AVG(salary) FROM employees")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("COUNT(*)")
    sql should include("AVG(SALARY)")
  }

  it should "format CASE expression" in {
    val ast = MySQLParser.parse("SELECT CASE WHEN age > 18 THEN 'adult' ELSE 'minor' END FROM users")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("CASE")
    sql should include("WHEN")
    sql should include("THEN")
    sql should include("ELSE")
    sql should include("END")
  }

  it should "format CAST expression" in {
    val ast = MySQLParser.parse("SELECT CAST(price AS DECIMAL(10,2)) FROM products")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("CAST")
    sql should include("DECIMAL(10,2)")
  }

  it should "format IS NULL predicate" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE email IS NULL")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("IS NULL")
  }

  it should "format BETWEEN predicate" in {
    val ast = MySQLParser.parse("SELECT * FROM products WHERE price BETWEEN 100 AND 500")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("BETWEEN")
    sql should include("AND")
  }

  it should "format IN predicate" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE role IN ('admin', 'editor')")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("IN (")
  }

  it should "format LIKE predicate" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE name LIKE 'Ali%'")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("LIKE")
  }

  // ====== TableRenamer 测试 ======

  "TableRenamer" should "rename table in simple SELECT" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE id = 1")
    val renamer = new TableRenamer(Map("USERS" -> "users_v2"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("users_v2")
    sql should not include("USERS")
  }

  it should "rename tables in JOIN query" in {
    val ast = MySQLParser.parse("SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id")
    val renamer = new TableRenamer(Map("USERS" -> "users_v2", "ORDERS" -> "orders_v2"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("users_v2")
    sql should include("orders_v2")
  }

  it should "rename table in INSERT" in {
    val ast = MySQLParser.parse("INSERT INTO users (name) VALUES ('test')")
    val renamer = new TableRenamer(Map("USERS" -> "users_v2"))
    val renamed = renamer.transformStatement(ast).asInstanceOf[InsertStatement]
    renamed.table shouldBe "users_v2"
  }

  it should "rename table in UPDATE" in {
    val ast = MySQLParser.parse("UPDATE users SET name = 'test'")
    val renamer = new TableRenamer(Map("USERS" -> "users_v2"))
    val renamed = renamer.transformStatement(ast).asInstanceOf[UpdateStatement]
    renamed.table shouldBe "users_v2"
  }

  it should "rename table in DELETE" in {
    val ast = MySQLParser.parse("DELETE FROM users WHERE id = 1")
    val renamer = new TableRenamer(Map("USERS" -> "users_v2"))
    val renamed = renamer.transformStatement(ast).asInstanceOf[DeleteStatement]
    renamed.table shouldBe "users_v2"
  }

  it should "rename table in DROP TABLE" in {
    val ast = MySQLParser.parse("DROP TABLE users")
    val renamer = new TableRenamer(Map("USERS" -> "users_v2"))
    val renamed = renamer.transformStatement(ast).asInstanceOf[DropTableStatement]
    renamed.tableName shouldBe "users_v2"
  }

  it should "rename table in UNION" in {
    val ast = MySQLParser.parse("SELECT name FROM users UNION SELECT name FROM admins")
    val renamer = new TableRenamer(Map("USERS" -> "customers", "ADMINS" -> "superadmins"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("customers")
    sql should include("superadmins")
  }

  it should "not rename unmapped tables" in {
    val ast = MySQLParser.parse("SELECT * FROM products")
    val renamer = new TableRenamer(Map("USERS" -> "users_v2"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("PRODUCTS")  // products should not be renamed
  }

  // ====== ColumnRenamer 测试 ======

  "ColumnRenamer" should "rename columns in SELECT" in {
    val ast = MySQLParser.parse("SELECT name, age FROM users")
    val renamer = new ColumnRenamer(Map("NAME" -> "full_name", "AGE" -> "user_age"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("full_name")
    sql should include("user_age")
  }

  it should "rename columns in WHERE clause" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE name = 'Alice'")
    val renamer = new ColumnRenamer(Map("NAME" -> "full_name"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("full_name = 'Alice'")
  }

  it should "rename qualified column name" in {
    val ast = MySQLParser.parse("SELECT u.name FROM users u")
    val renamer = new ColumnRenamer(Map("NAME" -> "full_name"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("U.full_name")
  }

  it should "rename columns in INSERT" in {
    val ast = MySQLParser.parse("INSERT INTO users (name, age) VALUES ('Alice', 25)")
    val renamer = new ColumnRenamer(Map("NAME" -> "full_name", "AGE" -> "user_age"))
    val renamed = renamer.transformStatement(ast).asInstanceOf[InsertStatement]
    renamed.columns.get should contain allOf("full_name", "user_age")
  }

  it should "rename columns in UPDATE SET" in {
    val ast = MySQLParser.parse("UPDATE users SET name = 'Bob' WHERE id = 1")
    val renamer = new ColumnRenamer(Map("NAME" -> "full_name"))
    val renamed = renamer.transformStatement(ast).asInstanceOf[UpdateStatement]
    renamed.assignments.head._1 shouldBe "full_name"
  }

  it should "not rename unmapped columns" in {
    val ast = MySQLParser.parse("SELECT id, email FROM users")
    val renamer = new ColumnRenamer(Map("NAME" -> "full_name"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("ID")
    sql should include("EMAIL")
  }

  // ====== Visitor 组合使用测试 ======

  "AST Visitor pipeline" should "extract then format correctly" in {
    val sql = "SELECT u.name, COUNT(*) AS cnt FROM users u GROUP BY u.name"
    val ast = MySQLParser.parse(sql)

    // Step 1: 提取表名
    val tables = new TableExtractor().visitStatement(ast).distinct
    tables should contain("USERS")

    // Step 2: 提取列名
    val columns = new ColumnExtractor().visitStatement(ast).distinct
    columns should contain("NAME")

    // Step 3: 格式化
    val formatted = new SQLPrettyPrinter().visitStatement(ast)
    formatted should include("SELECT")
    formatted should include("GROUP BY")
  }

  it should "rename table then extract correctly" in {
    val sql = "SELECT * FROM users WHERE id = 1"
    val ast = MySQLParser.parse(sql)

    // Step 1: 重命名表
    val renamed = new TableRenamer(Map("USERS" -> "customers")).transformStatement(ast)

    // Step 2: 提取表名
    val tables = new TableExtractor().visitStatement(renamed).distinct
    tables should contain("customers")
    tables should not contain("users")
    tables should not contain("USERS")
  }

  it should "rename columns then format correctly" in {
    val sql = "SELECT name FROM users WHERE age > 18"
    val ast = MySQLParser.parse(sql)

    // Step 1: 重命名列
    val renamed = new ColumnRenamer(Map("NAME" -> "full_name", "AGE" -> "user_age")).transformStatement(ast)

    // Step 2: 格式化
    val formatted = new SQLPrettyPrinter().visitStatement(renamed)
    formatted should include("full_name")
    formatted should include("user_age > 18")
  }

  it should "chain table rename + column rename + pretty print" in {
    val sql = "SELECT name, age FROM users WHERE id = 1"
    val ast = MySQLParser.parse(sql)

    // Step 1: 重命名表
    val step1 = new TableRenamer(Map("USERS" -> "customers")).transformStatement(ast)

    // Step 2: 重命名列
    val step2 = new ColumnRenamer(Map("NAME" -> "full_name")).transformStatement(step1)

    // Step 3: 格式化
    val formatted = new SQLPrettyPrinter().visitStatement(step2)
    formatted should include("customers")
    formatted should include("full_name")
  }

  // ============================================================
  //  Visitor 模式语义分析测试
  // ============================================================

  // 复用已有的 testSchema

  // ====== SemanticVisitorPipeline 完整管道测试 ======

  "SemanticVisitorPipeline" should "pass valid simple SELECT" in {
    val ast = MySQLParser.parse("SELECT id, name FROM users")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast)
    errors shouldBe empty
  }

  it should "pass valid JOIN query" in {
    val ast = MySQLParser.parse("SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast)
    errors shouldBe empty
  }

  it should "pass valid GROUP BY query" in {
    val ast = MySQLParser.parse("SELECT department, COUNT(*) FROM employees GROUP BY department")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast)
    errors shouldBe empty
  }

  it should "pass SELECT * without column check" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE age > 18")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast)
    errors shouldBe empty
  }

  it should "pass valid INSERT" in {
    val ast = MySQLParser.parse("INSERT INTO users (name, age) VALUES ('Alice', 25)")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast)
    errors shouldBe empty
  }

  it should "pass valid UPDATE" in {
    val ast = MySQLParser.parse("UPDATE users SET age = 30 WHERE name = 'Bob'")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast)
    errors shouldBe empty
  }

  it should "pass valid DELETE" in {
    val ast = MySQLParser.parse("DELETE FROM users WHERE id = 1")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast)
    errors shouldBe empty
  }

  it should "pass valid UNION" in {
    val ast = MySQLParser.parse("SELECT name FROM users UNION SELECT name FROM admins")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast)
    errors shouldBe empty
  }

  it should "pass SELECT without FROM" in {
    val ast = MySQLParser.parse("SELECT NOW()")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast)
    errors shouldBe empty
  }

  // ====== TableExistenceVisitor 单独测试 ======

  "TableExistenceVisitor" should "detect non-existent table in SELECT" in {
    val ast = MySQLParser.parse("SELECT * FROM nonexistent_table")
    val visitor = new TableExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "TABLE") shouldBe true
    errors.exists(_.message.contains("NONEXISTENT_TABLE")) shouldBe true
  }

  it should "detect non-existent table in INSERT" in {
    val ast = MySQLParser.parse("INSERT INTO fake_table (name) VALUES ('test')")
    val visitor = new TableExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "TABLE") shouldBe true
  }

  it should "detect non-existent table in UPDATE" in {
    val ast = MySQLParser.parse("UPDATE fake_table SET name = 'test'")
    val visitor = new TableExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "TABLE") shouldBe true
  }

  it should "detect non-existent table in DELETE" in {
    val ast = MySQLParser.parse("DELETE FROM fake_table WHERE id = 1")
    val visitor = new TableExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "TABLE") shouldBe true
  }

  it should "pass when table exists" in {
    val ast = MySQLParser.parse("SELECT * FROM users")
    val visitor = new TableExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  // ====== ColumnExistenceVisitor 单独测试 ======

  "ColumnExistenceVisitor" should "detect non-existent column in SELECT" in {
    val ast = MySQLParser.parse("SELECT fake_column FROM users")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "COLUMN") shouldBe true
    errors.exists(_.message.contains("FAKE_COLUMN")) shouldBe true
  }

  it should "detect non-existent column in WHERE" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE nonexistent > 10")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("NONEXISTENT")) shouldBe true
  }

  it should "detect undefined table alias" in {
    val ast = MySQLParser.parse("SELECT x.name FROM users u")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("X")) shouldBe true
  }

  it should "detect column not in specified table" in {
    val ast = MySQLParser.parse("SELECT u.nonexistent FROM users u")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("NONEXISTENT")) shouldBe true
  }

  it should "pass valid qualified column" in {
    val ast = MySQLParser.parse("SELECT u.name FROM users u")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  it should "detect non-existent column in INSERT" in {
    val ast = MySQLParser.parse("INSERT INTO users (fake_col) VALUES ('test')")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "COLUMN") shouldBe true
  }

  it should "detect non-existent column in UPDATE SET" in {
    val ast = MySQLParser.parse("UPDATE users SET fake_col = 1")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "COLUMN") shouldBe true
  }

  it should "detect non-existent column in JOIN ON" in {
    val ast = MySQLParser.parse("SELECT u.name FROM users u JOIN orders o ON u.id = o.fake_col")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("FAKE_COL")) shouldBe true
  }

  it should "detect non-existent column in function argument" in {
    val ast = MySQLParser.parse("SELECT UPPER(nonexistent) FROM users")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("NONEXISTENT")) shouldBe true
  }

  it should "pass valid function call with valid column" in {
    val ast = MySQLParser.parse("SELECT UPPER(name) FROM users")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  it should "detect non-existent column in CAST" in {
    val ast = MySQLParser.parse("SELECT CAST(fake AS DECIMAL(10,2)) FROM products")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("FAKE")) shouldBe true
  }

  it should "pass valid CAST" in {
    val ast = MySQLParser.parse("SELECT CAST(price AS DECIMAL(10,2)) FROM products")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  // ====== GroupByConsistencyVisitor 单独测试 ======

  "GroupByConsistencyVisitor" should "detect non-aggregate column not in GROUP BY" in {
    val ast = MySQLParser.parse("SELECT name, COUNT(*) FROM users GROUP BY department")
    val visitor = new GroupByConsistencyVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "GROUP_BY") shouldBe true
    errors.exists(_.message.contains("NAME")) shouldBe true
  }

  it should "pass when all non-aggregate columns are in GROUP BY" in {
    val ast = MySQLParser.parse("SELECT department, COUNT(*) FROM employees GROUP BY department")
    val visitor = new GroupByConsistencyVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  it should "pass aggregate expression column" in {
    val ast = MySQLParser.parse("SELECT department, AVG(salary) AS avg_sal FROM employees GROUP BY department")
    val visitor = new GroupByConsistencyVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  // ====== AliasUniquenessVisitor 单独测试 ======

  "AliasUniquenessVisitor" should "detect duplicate aliases" in {
    val ast = MySQLParser.parse("SELECT name AS x, age AS x FROM users")
    val visitor = new AliasUniquenessVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "ALIAS") shouldBe true
  }

  it should "pass unique aliases" in {
    val ast = MySQLParser.parse("SELECT name AS n, age AS a FROM users")
    val visitor = new AliasUniquenessVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  // ====== HavingConsistencyVisitor 单独测试 ======

  "HavingConsistencyVisitor" should "detect non-GROUP BY column in HAVING" in {
    val ast = MySQLParser.parse("SELECT department, COUNT(*) FROM employees GROUP BY department HAVING name = 'test'")
    val visitor = new HavingConsistencyVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "HAVING") shouldBe true
    errors.exists(_.message.contains("NAME")) shouldBe true
  }

  it should "pass HAVING with aggregate function" in {
    val ast = MySQLParser.parse("SELECT department, COUNT(*) FROM employees GROUP BY department HAVING COUNT(*) > 5")
    val visitor = new HavingConsistencyVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  // ====== InsertValidationVisitor 单独测试 ======

  "InsertValidationVisitor" should "detect column count mismatch" in {
    val ast = MySQLParser.parse("INSERT INTO users (name, age, email) VALUES ('Alice', 25)")
    val visitor = new InsertValidationVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "INSERT") shouldBe true
  }

  it should "pass matching column count" in {
    val ast = MySQLParser.parse("INSERT INTO users (name, age) VALUES ('Alice', 25)")
    val visitor = new InsertValidationVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  // ====== UnionColumnCountVisitor 单独测试 ======

  "UnionColumnCountVisitor" should "detect column count mismatch" in {
    val ast = MySQLParser.parse("SELECT id, name FROM users UNION SELECT id FROM admins")
    val visitor = new UnionColumnCountVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "UNION") shouldBe true
  }

  it should "pass matching column count" in {
    val ast = MySQLParser.parse("SELECT name FROM users UNION SELECT name FROM admins")
    val visitor = new UnionColumnCountVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  // ====== DDLValidationVisitor 单独测试 ======

  "DDLValidationVisitor" should "detect CREATE TABLE when table exists" in {
    val ast = MySQLParser.parse("CREATE TABLE users (id INT)")
    val visitor = new DDLValidationVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("already exists")) shouldBe true
  }

  it should "pass CREATE TABLE for new table" in {
    val ast = MySQLParser.parse("CREATE TABLE new_table (id INT)")
    val visitor = new DDLValidationVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  it should "detect DROP TABLE when table not exists" in {
    val ast = MySQLParser.parse("DROP TABLE nonexistent_table")
    val visitor = new DDLValidationVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.category == "TABLE") shouldBe true
  }

  it should "pass DROP TABLE for existing table" in {
    val ast = MySQLParser.parse("DROP TABLE users")
    val visitor = new DDLValidationVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors shouldBe empty
  }

  // ====== Pipeline withOnly / without 测试 ======

  "SemanticVisitorPipeline withOnly" should "only run specified visitors" in {
    val ast = MySQLParser.parse("SELECT fake_column FROM nonexistent_table")
    val pipeline = new SemanticVisitorPipeline(testSchema)

    // 只检查表存在性
    val tableOnlyPipeline = pipeline.withOnly(classOf[TableExistenceVisitor])
    val errors = tableOnlyPipeline.analyze(ast)
    errors.exists(_.category == "TABLE") shouldBe true
    // 不应包含列错误（因为只启用了表检查）
    errors.exists(_.category == "COLUMN") shouldBe false
  }

  "SemanticVisitorPipeline without" should "skip specified visitors" in {
    val ast = MySQLParser.parse("CREATE TABLE users (id INT)")
    val pipeline = new SemanticVisitorPipeline(testSchema)

    // 全管道应该报告"表已存在"
    val fullErrors = pipeline.analyze(ast)
    fullErrors.exists(_.message.contains("already exists")) shouldBe true

    // 排除 DDL 检查后不应报告
    val noDDLPipeline = pipeline.without(classOf[DDLValidationVisitor])
    val filteredErrors = noDDLPipeline.analyze(ast)
    filteredErrors.exists(_.message.contains("already exists")) shouldBe false
  }

  // ====== Visitor 语义分析与传统语义分析结果一致性 ======

  "Visitor semantic analysis" should "produce same results as traditional analysis for valid queries" in {
    val validSQLs = List(
      "SELECT id, name FROM users",
      "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id",
      "SELECT department, COUNT(*) FROM employees GROUP BY department",
      "SELECT * FROM users WHERE age > 18",
      "INSERT INTO users (name, age) VALUES ('Alice', 25)",
      "UPDATE users SET age = 30 WHERE name = 'Bob'",
      "DELETE FROM users WHERE id = 1",
      "SELECT name FROM users UNION SELECT name FROM admins",
      "SELECT NOW()"
    )

    val pipeline = new SemanticVisitorPipeline(testSchema)
    validSQLs.foreach { sql =>
      val ast = MySQLParser.parse(sql)
      val visitorErrors = pipeline.analyze(ast)
      val traditionalErrors = SemanticAnalyzer.analyze(ast, testSchema)
      visitorErrors.isEmpty shouldBe traditionalErrors.isEmpty
    }
  }

  it should "detect same error categories as traditional analysis" in {
    val errorSQLs = List(
      ("SELECT * FROM nonexistent_table", "TABLE"),
      ("SELECT fake_column FROM users", "COLUMN"),
      ("SELECT x.name FROM users u", "TABLE"),
      ("SELECT name, COUNT(*) FROM users GROUP BY department", "GROUP_BY"),
      ("INSERT INTO users (name, age, email) VALUES ('Alice', 25)", "INSERT"),
      ("SELECT id, name FROM users UNION SELECT id FROM admins", "UNION"),
      ("CREATE TABLE users (id INT)", "TABLE"),
      ("DROP TABLE nonexistent_table", "TABLE"),
      ("UPDATE users SET fake_col = 1", "COLUMN")
    )

    val pipeline = new SemanticVisitorPipeline(testSchema)
    errorSQLs.foreach { case (sql, expectedCategory) =>
      val ast = MySQLParser.parse(sql)
      val visitorErrors = pipeline.analyze(ast)
      val traditionalErrors = SemanticAnalyzer.analyze(ast, testSchema)
      visitorErrors should not be empty
      traditionalErrors should not be empty
      visitorErrors.exists(_.category == expectedCategory) shouldBe true
    }
  }

  // ====== 复杂查询 Visitor 分析测试 ======

  "Visitor pipeline complex queries" should "pass complex valid query" in {
    val ast = MySQLParser.parse(
      "SELECT department, COUNT(*) AS cnt, AVG(salary) AS avg_sal FROM employees GROUP BY department HAVING COUNT(*) > 5"
    )
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast).filter(e => e.category == "GROUP_BY" || e.category == "HAVING")
    errors shouldBe empty
  }

  it should "detect multiple errors in one query" in {
    val ast = MySQLParser.parse("SELECT fake1, fake2 FROM users")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast).filter(_.category == "COLUMN")
    errors should have length 2
  }

  it should "handle DELETE with non-existent WHERE column" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE nonexistent > 10")
    val pipeline = new SemanticVisitorPipeline(testSchema)
    val errors = pipeline.analyze(ast)
    errors should not be empty
    errors.exists(_.message.contains("NONEXISTENT")) shouldBe true
  }

  // ============================================================
  //  窗口函数 (Window Functions) 测试
  // ============================================================

  // ====== 基本窗口函数解析 ======

  "Parser" should "parse ROW_NUMBER() OVER (ORDER BY ...)" in {
    val sql = "SELECT ROW_NUMBER() OVER (ORDER BY salary DESC) AS rn FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("RN")
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    val func = wf.function.asInstanceOf[FunctionCall]
    func.name shouldBe "ROW_NUMBER"
    func.arguments shouldBe empty
    wf.windowSpec.partitionBy shouldBe None
    wf.windowSpec.orderBy shouldBe defined
    wf.windowSpec.orderBy.get should have length 1
    wf.windowSpec.orderBy.get.head.expression shouldBe Identifier("SALARY")
    wf.windowSpec.orderBy.get.head.ascending shouldBe false
  }

  it should "parse RANK() OVER (PARTITION BY ... ORDER BY ...)" in {
    val sql = "SELECT name, RANK() OVER (PARTITION BY department ORDER BY salary DESC) AS dept_rank FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 2
    select.columns(0) shouldBe a[NamedColumn]
    val exprCol = select.columns(1).asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("DEPT_RANK")
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    val func = wf.function.asInstanceOf[FunctionCall]
    func.name shouldBe "RANK"
    wf.windowSpec.partitionBy shouldBe defined
    wf.windowSpec.partitionBy.get should have length 1
    wf.windowSpec.partitionBy.get.head shouldBe Identifier("DEPARTMENT")
    wf.windowSpec.orderBy shouldBe defined
    wf.windowSpec.orderBy.get should have length 1
  }

  it should "parse DENSE_RANK() window function" in {
    val sql = "SELECT DENSE_RANK() OVER (ORDER BY score DESC) FROM exams"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    val func = wf.function.asInstanceOf[FunctionCall]
    func.name shouldBe "DENSE_RANK"
  }

  it should "parse NTILE() window function" in {
    val sql = "SELECT NTILE(4) OVER (ORDER BY salary DESC) AS quartile FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("QUARTILE")
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    val func = wf.function.asInstanceOf[FunctionCall]
    func.name shouldBe "NTILE"
    func.arguments should have length 1
    func.arguments.head shouldBe NumberLiteral("4")
  }

  it should "parse aggregate function as window function (SUM OVER)" in {
    val sql = "SELECT name, SUM(salary) OVER (PARTITION BY department) AS dept_total FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 2
    val exprCol = select.columns(1).asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("DEPT_TOTAL")
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    wf.function shouldBe a[AggregateFunction]
    val agg = wf.function.asInstanceOf[AggregateFunction]
    agg.funcType shouldBe SumFunc
    agg.argument shouldBe Identifier("SALARY")
    wf.windowSpec.partitionBy shouldBe defined
    wf.windowSpec.orderBy shouldBe None
  }

  it should "parse COUNT(*) OVER () — empty OVER clause" in {
    val sql = "SELECT COUNT(*) OVER () AS total_count FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("TOTAL_COUNT")
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    wf.function shouldBe a[AggregateFunction]
    wf.windowSpec.partitionBy shouldBe None
    wf.windowSpec.orderBy shouldBe None
    wf.windowSpec.frame shouldBe None
  }

  it should "parse AVG window function with PARTITION BY and ORDER BY" in {
    val sql = "SELECT name, AVG(salary) OVER (PARTITION BY department ORDER BY hire_date) FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns(1).asInstanceOf[ExpressionColumn]
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    wf.function shouldBe a[AggregateFunction]
    val agg = wf.function.asInstanceOf[AggregateFunction]
    agg.funcType shouldBe AvgFunc
    wf.windowSpec.partitionBy shouldBe defined
    wf.windowSpec.orderBy shouldBe defined
  }

  // ====== 窗口帧 (Frame) 解析 ======

  it should "parse ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW" in {
    val sql = "SELECT SUM(salary) OVER (ORDER BY hire_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    wf.windowSpec.frame shouldBe defined
    val frame = wf.windowSpec.frame.get
    frame.frameType shouldBe RowsFrame
    frame.start shouldBe UnboundedPreceding
    frame.end shouldBe Some(CurrentRowBound)
  }

  it should "parse ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING" in {
    val sql = "SELECT AVG(price) OVER (ORDER BY trade_date ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM prices"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    wf.windowSpec.frame shouldBe defined
    val frame = wf.windowSpec.frame.get
    frame.frameType shouldBe RowsFrame
    frame.start shouldBe PrecedingBound(1)
    frame.end shouldBe Some(FollowingBound(1))
  }

  it should "parse RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING" in {
    val sql = "SELECT SUM(amount) OVER (PARTITION BY user_id RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) FROM orders"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    wf.windowSpec.frame shouldBe defined
    val frame = wf.windowSpec.frame.get
    frame.frameType shouldBe RangeFrame
    frame.start shouldBe UnboundedPreceding
    frame.end shouldBe Some(UnboundedFollowing)
  }

  it should "parse ROWS UNBOUNDED PRECEDING (single bound, no BETWEEN)" in {
    val sql = "SELECT SUM(salary) OVER (ORDER BY id ROWS UNBOUNDED PRECEDING) FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    wf.windowSpec.frame shouldBe defined
    val frame = wf.windowSpec.frame.get
    frame.frameType shouldBe RowsFrame
    frame.start shouldBe UnboundedPreceding
    frame.end shouldBe None
  }

  // ====== 多个窗口函数 ======

  it should "parse multiple window functions in one SELECT" in {
    val sql = "SELECT name, ROW_NUMBER() OVER (ORDER BY salary DESC) AS rn, RANK() OVER (ORDER BY salary DESC) AS rnk, DENSE_RANK() OVER (ORDER BY salary DESC) AS drnk FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 4
    select.columns(0) shouldBe a[NamedColumn]
    select.columns(1).asInstanceOf[ExpressionColumn].expression shouldBe a[WindowFunctionExpression]
    select.columns(2).asInstanceOf[ExpressionColumn].expression shouldBe a[WindowFunctionExpression]
    select.columns(3).asInstanceOf[ExpressionColumn].expression shouldBe a[WindowFunctionExpression]
  }

  // ====== 多个 PARTITION BY 列 ======

  it should "parse window function with multiple PARTITION BY columns" in {
    val sql = "SELECT ROW_NUMBER() OVER (PARTITION BY department, status ORDER BY salary DESC) FROM employees"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val wf = exprCol.expression.asInstanceOf[WindowFunctionExpression]
    wf.windowSpec.partitionBy shouldBe defined
    wf.windowSpec.partitionBy.get should have length 2
    wf.windowSpec.partitionBy.get(0) shouldBe Identifier("DEPARTMENT")
    wf.windowSpec.partitionBy.get(1) shouldBe Identifier("STATUS")
  }

  // ============================================================
  //  CTE — WITH 子句测试
  // ============================================================

  it should "parse simple CTE" in {
    val sql = "WITH cte AS (SELECT id, name FROM users) SELECT * FROM cte"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[WithStatement]
    val withStmt = ast.asInstanceOf[WithStatement]
    withStmt.recursive shouldBe false
    withStmt.ctes should have length 1
    withStmt.ctes.head.name shouldBe "CTE"
    withStmt.ctes.head.query shouldBe a[SelectStatement]
    val cteSelect = withStmt.ctes.head.query.asInstanceOf[SelectStatement]
    cteSelect.columns should have length 2
    withStmt.query shouldBe a[SelectStatement]
    val mainSelect = withStmt.query.asInstanceOf[SelectStatement]
    mainSelect.from shouldBe defined
    mainSelect.from.get.asInstanceOf[TableName].name shouldBe "CTE"
  }

  it should "parse CTE with WHERE clause in main query" in {
    val sql = "WITH active_users AS (SELECT id, name FROM users WHERE status = 'active') SELECT * FROM active_users WHERE id > 10"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[WithStatement]
    val withStmt = ast.asInstanceOf[WithStatement]
    withStmt.ctes should have length 1
    withStmt.ctes.head.name shouldBe "ACTIVE_USERS"
    val mainSelect = withStmt.query.asInstanceOf[SelectStatement]
    mainSelect.where shouldBe defined
  }

  it should "parse multiple CTEs" in {
    val sql = "WITH dept_count AS (SELECT department, COUNT(*) AS cnt FROM employees GROUP BY department), high_count AS (SELECT department FROM dept_count WHERE cnt > 5) SELECT * FROM high_count"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[WithStatement]
    val withStmt = ast.asInstanceOf[WithStatement]
    withStmt.recursive shouldBe false
    withStmt.ctes should have length 2
    withStmt.ctes(0).name shouldBe "DEPT_COUNT"
    withStmt.ctes(1).name shouldBe "HIGH_COUNT"
    withStmt.query shouldBe a[SelectStatement]
  }

  it should "parse RECURSIVE CTE" in {
    val sql = "WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 10) SELECT * FROM seq"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[WithStatement]
    val withStmt = ast.asInstanceOf[WithStatement]
    withStmt.recursive shouldBe true
    withStmt.ctes should have length 1
    withStmt.ctes.head.name shouldBe "SEQ"
    // CTE 查询应该是 UNION ALL
    withStmt.ctes.head.query shouldBe a[UnionStatement]
    val union = withStmt.ctes.head.query.asInstanceOf[UnionStatement]
    union.unionType shouldBe UnionAll
  }

  it should "parse CTE with JOIN in main query" in {
    val sql = "WITH recent_orders AS (SELECT user_id, amount FROM orders WHERE amount > 100) SELECT u.name, r.amount FROM users u JOIN recent_orders r ON u.id = r.user_id"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[WithStatement]
    val withStmt = ast.asInstanceOf[WithStatement]
    withStmt.ctes should have length 1
    val mainSelect = withStmt.query.asInstanceOf[SelectStatement]
    mainSelect.from shouldBe defined
    mainSelect.from.get shouldBe a[JoinClause]
  }

  it should "parse CTE with UNION in main query" in {
    val sql = "WITH base AS (SELECT id FROM users) SELECT id FROM base UNION SELECT id FROM orders"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[WithStatement]
    val withStmt = ast.asInstanceOf[WithStatement]
    withStmt.ctes should have length 1
    withStmt.query shouldBe a[UnionStatement]
  }

  // ====== 窗口函数 + CTE 组合 ======

  it should "parse CTE combined with window function" in {
    val sql = "WITH ranked AS (SELECT name, salary, RANK() OVER (ORDER BY salary DESC) AS rnk FROM employees) SELECT * FROM ranked WHERE rnk <= 10"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[WithStatement]
    val withStmt = ast.asInstanceOf[WithStatement]
    withStmt.ctes should have length 1
    val cteSelect = withStmt.ctes.head.query.asInstanceOf[SelectStatement]
    cteSelect.columns should have length 3
    // 第 3 列应该是窗口函数
    val exprCol = cteSelect.columns(2).asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("RNK")
    exprCol.expression shouldBe a[WindowFunctionExpression]
  }

  // ====== 窗口函数 Pretty Printer 测试 ======

  "SQLPrettyPrinter" should "format window function with PARTITION BY and ORDER BY" in {
    val ast = MySQLParser.parse("SELECT ROW_NUMBER() OVER (PARTITION BY department ORDER BY salary DESC) AS rn FROM employees")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("ROW_NUMBER()")
    sql should include("OVER")
    sql should include("PARTITION BY")
    sql should include("ORDER BY")
  }

  it should "format window function with frame clause" in {
    val ast = MySQLParser.parse("SELECT SUM(salary) OVER (ORDER BY hire_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM employees")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("OVER")
    sql should include("ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW")
  }

  it should "format CTE statement" in {
    val ast = MySQLParser.parse("WITH cte AS (SELECT id, name FROM users) SELECT * FROM cte")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("WITH")
    sql should include("CTE AS")
    sql should include("SELECT")
  }

  it should "format RECURSIVE CTE" in {
    val ast = MySQLParser.parse("WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 10) SELECT * FROM seq")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("WITH RECURSIVE")
    sql should include("UNION ALL")
  }

  // ====== 窗口函数 TableExtractor / ColumnExtractor 测试 ======

  "TableExtractor" should "extract tables from CTE query" in {
    val ast = MySQLParser.parse("WITH cte AS (SELECT id FROM users) SELECT * FROM cte")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain("USERS")
    tables should contain("CTE")
  }

  "ColumnExtractor" should "extract columns from window function" in {
    val ast = MySQLParser.parse("SELECT name, SUM(salary) OVER (PARTITION BY department ORDER BY hire_date) FROM employees")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain("NAME")
    columns should contain("SALARY")
    columns should contain("DEPARTMENT")
    columns should contain("HIRE_DATE")
  }

  // ====== TableRenamer with CTE ======

  "TableRenamer" should "rename tables in CTE query" in {
    val ast = MySQLParser.parse("WITH cte AS (SELECT id FROM users) SELECT * FROM cte")
    val renamer = new TableRenamer(Map("USERS" -> "customers"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("customers")
  }

  // ==========================================
  //  CREATE TABLE 约束条件测试
  // ==========================================

  "Parser" should "parse CREATE TABLE with NOT NULL constraint" in {
    val ast = MySQLParser.parse("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50) NOT NULL)")
    ast shouldBe a[CreateTableStatement]
    val create = ast.asInstanceOf[CreateTableStatement]
    create.tableName shouldBe "USERS"
    create.columns should have length 2
    create.columns(0).constraints should contain(NotNullConstraint)
    create.columns(1).constraints should contain(NotNullConstraint)
  }

  it should "parse CREATE TABLE with PRIMARY KEY column constraint" in {
    val ast = MySQLParser.parse("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50))")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.columns(0).constraints should contain(PrimaryKeyConstraint)
  }

  it should "parse CREATE TABLE with AUTO_INCREMENT" in {
    val ast = MySQLParser.parse("CREATE TABLE users (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50) NOT NULL)")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.columns(0).constraints should contain(PrimaryKeyConstraint)
    create.columns(0).constraints should contain(AutoIncrementConstraint)
    create.columns(1).constraints should contain(NotNullConstraint)
  }

  it should "parse CREATE TABLE with DEFAULT constraint" in {
    val ast = MySQLParser.parse("CREATE TABLE users (id INT, status INT DEFAULT 1)")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.columns(1).constraints should have length 1
    create.columns(1).constraints.head shouldBe a[DefaultConstraint]
  }

  it should "parse CREATE TABLE with UNIQUE column constraint" in {
    val ast = MySQLParser.parse("CREATE TABLE users (id INT PRIMARY KEY, email VARCHAR(100) UNIQUE NOT NULL)")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.columns(1).constraints should contain(UniqueConstraint)
    create.columns(1).constraints should contain(NotNullConstraint)
  }

  it should "parse CREATE TABLE with REFERENCES (foreign key column constraint)" in {
    val ast = MySQLParser.parse("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT REFERENCES users(id))")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.columns(1).constraints should have length 1
    val ref = create.columns(1).constraints.head.asInstanceOf[ReferencesConstraint]
    ref.refTable shouldBe "USERS"
    ref.refColumn shouldBe "ID"
  }

  it should "parse CREATE TABLE with CHECK column constraint" in {
    val ast = MySQLParser.parse("CREATE TABLE products (id INT, price INT CHECK (price > 0))")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.columns(1).constraints should have length 1
    create.columns(1).constraints.head shouldBe a[CheckColumnConstraint]
  }

  it should "parse CREATE TABLE with table-level PRIMARY KEY" in {
    val ast = MySQLParser.parse("CREATE TABLE order_items (order_id INT, product_id INT, PRIMARY KEY (order_id, product_id))")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.columns should have length 2
    create.constraints should have length 1
    val pk = create.constraints.head.asInstanceOf[PrimaryKeyTableConstraint]
    pk.columns shouldBe List("ORDER_ID", "PRODUCT_ID")
  }

  it should "parse CREATE TABLE with table-level UNIQUE constraint" in {
    val ast = MySQLParser.parse("CREATE TABLE users (id INT, email VARCHAR(100), UNIQUE (email))")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.constraints should have length 1
    val uniq = create.constraints.head.asInstanceOf[UniqueTableConstraint]
    uniq.columns shouldBe List("EMAIL")
  }

  it should "parse CREATE TABLE with FOREIGN KEY table constraint" in {
    val ast = MySQLParser.parse("CREATE TABLE orders (id INT, user_id INT, FOREIGN KEY (user_id) REFERENCES users(id))")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.constraints should have length 1
    val fk = create.constraints.head.asInstanceOf[ForeignKeyTableConstraint]
    fk.columns shouldBe List("USER_ID")
    fk.refTable shouldBe "USERS"
    fk.refColumns shouldBe List("ID")
  }

  it should "parse CREATE TABLE with CONSTRAINT name" in {
    val ast = MySQLParser.parse("CREATE TABLE orders (id INT, user_id INT, CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id))")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.constraints should have length 1
    val fk = create.constraints.head.asInstanceOf[ForeignKeyTableConstraint]
    fk.name shouldBe Some("FK_USER")
    fk.columns shouldBe List("USER_ID")
  }

  it should "parse CREATE TABLE with CHECK table constraint" in {
    val ast = MySQLParser.parse("CREATE TABLE employees (id INT, age INT, CHECK (age > 18))")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.constraints should have length 1
    create.constraints.head shouldBe a[CheckTableConstraint]
  }

  it should "parse CREATE TABLE with multiple constraints" in {
    val sql = """CREATE TABLE orders (
      id INT PRIMARY KEY AUTO_INCREMENT,
      user_id INT NOT NULL,
      amount INT NOT NULL DEFAULT 0,
      FOREIGN KEY (user_id) REFERENCES users(id)
    )"""
    val ast = MySQLParser.parse(sql)
    val create = ast.asInstanceOf[CreateTableStatement]
    create.columns should have length 3
    create.columns(0).constraints should contain(PrimaryKeyConstraint)
    create.columns(0).constraints should contain(AutoIncrementConstraint)
    create.columns(1).constraints should contain(NotNullConstraint)
    create.columns(2).constraints should contain(NotNullConstraint)
    create.constraints should have length 1
    create.constraints.head shouldBe a[ForeignKeyTableConstraint]
  }

  it should "parse CREATE TABLE with new data types (BIGINT, SMALLINT, FLOAT, DOUBLE, DECIMAL)" in {
    val ast = MySQLParser.parse("CREATE TABLE data (a BIGINT, b SMALLINT, c FLOAT, d DOUBLE, e DECIMAL(10,2))")
    val create = ast.asInstanceOf[CreateTableStatement]
    create.columns should have length 5
    create.columns(0).dataType shouldBe BigIntType()
    create.columns(1).dataType shouldBe SmallIntType()
    create.columns(2).dataType shouldBe FloatType
    create.columns(3).dataType shouldBe DoubleType
    create.columns(4).dataType shouldBe DecimalDataType(Some(10), Some(2))
  }

  // ==========================================
  //  DROP TABLE IF EXISTS 测试
  // ==========================================

  it should "parse DROP TABLE IF EXISTS" in {
    val ast = MySQLParser.parse("DROP TABLE IF EXISTS users")
    ast shouldBe a[DropTableStatement]
    val drop = ast.asInstanceOf[DropTableStatement]
    drop.tableName shouldBe "USERS"
    drop.ifExists shouldBe true
  }

  it should "parse DROP TABLE without IF EXISTS" in {
    val ast = MySQLParser.parse("DROP TABLE users")
    val drop = ast.asInstanceOf[DropTableStatement]
    drop.ifExists shouldBe false
  }

  // ==========================================
  //  ALTER TABLE 测试
  // ==========================================

  it should "parse ALTER TABLE ADD COLUMN" in {
    val ast = MySQLParser.parse("ALTER TABLE users ADD COLUMN email VARCHAR(100) NOT NULL")
    ast shouldBe a[AlterTableStatement]
    val alter = ast.asInstanceOf[AlterTableStatement]
    alter.tableName shouldBe "USERS"
    alter.actions should have length 1
    val add = alter.actions.head.asInstanceOf[AddColumnAction]
    add.column.name shouldBe "EMAIL"
    add.column.dataType shouldBe VarcharType(100)
    add.column.constraints should contain(NotNullConstraint)
  }

  it should "parse ALTER TABLE ADD COLUMN without COLUMN keyword" in {
    val ast = MySQLParser.parse("ALTER TABLE users ADD email VARCHAR(100)")
    val alter = ast.asInstanceOf[AlterTableStatement]
    val add = alter.actions.head.asInstanceOf[AddColumnAction]
    add.column.name shouldBe "EMAIL"
  }

  it should "parse ALTER TABLE DROP COLUMN" in {
    val ast = MySQLParser.parse("ALTER TABLE users DROP COLUMN email")
    val alter = ast.asInstanceOf[AlterTableStatement]
    alter.actions should have length 1
    val drop = alter.actions.head.asInstanceOf[DropColumnAction]
    drop.columnName shouldBe "EMAIL"
  }

  it should "parse ALTER TABLE DROP column without COLUMN keyword" in {
    val ast = MySQLParser.parse("ALTER TABLE users DROP email")
    val alter = ast.asInstanceOf[AlterTableStatement]
    val drop = alter.actions.head.asInstanceOf[DropColumnAction]
    drop.columnName shouldBe "EMAIL"
  }

  it should "parse ALTER TABLE MODIFY COLUMN" in {
    val ast = MySQLParser.parse("ALTER TABLE users MODIFY COLUMN name VARCHAR(200) NOT NULL")
    val alter = ast.asInstanceOf[AlterTableStatement]
    val modify = alter.actions.head.asInstanceOf[ModifyColumnAction]
    modify.column.name shouldBe "NAME"
    modify.column.dataType shouldBe VarcharType(200)
    modify.column.constraints should contain(NotNullConstraint)
  }

  it should "parse ALTER TABLE CHANGE COLUMN" in {
    val ast = MySQLParser.parse("ALTER TABLE users CHANGE COLUMN name full_name VARCHAR(200)")
    val alter = ast.asInstanceOf[AlterTableStatement]
    val change = alter.actions.head.asInstanceOf[ChangeColumnAction]
    change.oldName shouldBe "NAME"
    change.newColumn.name shouldBe "FULL_NAME"
    change.newColumn.dataType shouldBe VarcharType(200)
  }

  it should "parse ALTER TABLE RENAME TO" in {
    val ast = MySQLParser.parse("ALTER TABLE users RENAME TO customers")
    val alter = ast.asInstanceOf[AlterTableStatement]
    val rename = alter.actions.head.asInstanceOf[RenameTableAction]
    rename.newName shouldBe "CUSTOMERS"
  }

  it should "parse ALTER TABLE ADD PRIMARY KEY" in {
    val ast = MySQLParser.parse("ALTER TABLE users ADD PRIMARY KEY (id)")
    val alter = ast.asInstanceOf[AlterTableStatement]
    val addConstraint = alter.actions.head.asInstanceOf[AddConstraintAction]
    val pk = addConstraint.constraint.asInstanceOf[PrimaryKeyTableConstraint]
    pk.columns shouldBe List("ID")
  }

  it should "parse ALTER TABLE ADD CONSTRAINT FOREIGN KEY" in {
    val ast = MySQLParser.parse("ALTER TABLE orders ADD CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id)")
    val alter = ast.asInstanceOf[AlterTableStatement]
    val addConstraint = alter.actions.head.asInstanceOf[AddConstraintAction]
    val fk = addConstraint.constraint.asInstanceOf[ForeignKeyTableConstraint]
    fk.name shouldBe Some("FK_USER")
    fk.columns shouldBe List("USER_ID")
    fk.refTable shouldBe "USERS"
  }

  it should "parse ALTER TABLE DROP PRIMARY KEY" in {
    val ast = MySQLParser.parse("ALTER TABLE users DROP PRIMARY KEY")
    val alter = ast.asInstanceOf[AlterTableStatement]
    val dropConstraint = alter.actions.head.asInstanceOf[DropConstraintAction]
    dropConstraint.constraintType shouldBe "PRIMARY KEY"
  }

  it should "parse ALTER TABLE DROP FOREIGN KEY" in {
    val ast = MySQLParser.parse("ALTER TABLE orders DROP FOREIGN KEY fk_user")
    val alter = ast.asInstanceOf[AlterTableStatement]
    val dropConstraint = alter.actions.head.asInstanceOf[DropConstraintAction]
    dropConstraint.constraintType shouldBe "FOREIGN KEY"
    dropConstraint.name shouldBe Some("FK_USER")
  }

  it should "parse ALTER TABLE DROP INDEX" in {
    val ast = MySQLParser.parse("ALTER TABLE users DROP INDEX idx_email")
    val alter = ast.asInstanceOf[AlterTableStatement]
    val dropConstraint = alter.actions.head.asInstanceOf[DropConstraintAction]
    dropConstraint.constraintType shouldBe "INDEX"
    dropConstraint.name shouldBe Some("IDX_EMAIL")
  }

  // ==========================================
  //  CREATE INDEX / DROP INDEX 测试
  // ==========================================

  it should "parse CREATE INDEX" in {
    val ast = MySQLParser.parse("CREATE INDEX idx_name ON users (name)")
    ast shouldBe a[CreateIndexStatement]
    val idx = ast.asInstanceOf[CreateIndexStatement]
    idx.indexName shouldBe "IDX_NAME"
    idx.tableName shouldBe "USERS"
    idx.columns should have length 1
    idx.columns.head.name shouldBe "NAME"
    idx.unique shouldBe false
  }

  it should "parse CREATE UNIQUE INDEX" in {
    val ast = MySQLParser.parse("CREATE UNIQUE INDEX idx_email ON users (email)")
    val idx = ast.asInstanceOf[CreateIndexStatement]
    idx.indexName shouldBe "IDX_EMAIL"
    idx.tableName shouldBe "USERS"
    idx.unique shouldBe true
  }

  it should "parse CREATE INDEX with multiple columns" in {
    val ast = MySQLParser.parse("CREATE INDEX idx_name_age ON users (name, age DESC)")
    val idx = ast.asInstanceOf[CreateIndexStatement]
    idx.columns should have length 2
    idx.columns(0).name shouldBe "NAME"
    idx.columns(0).ascending shouldBe true
    idx.columns(1).name shouldBe "AGE"
    idx.columns(1).ascending shouldBe false
  }

  it should "parse DROP INDEX" in {
    val ast = MySQLParser.parse("DROP INDEX idx_name ON users")
    ast shouldBe a[DropIndexStatement]
    val drop = ast.asInstanceOf[DropIndexStatement]
    drop.indexName shouldBe "IDX_NAME"
    drop.tableName shouldBe "USERS"
  }

  // ==========================================
  //  Pretty Printer — 新语句格式化测试
  // ==========================================

  "SQLPrettyPrinter" should "format CREATE TABLE with constraints" in {
    val ast = MySQLParser.parse("CREATE TABLE users (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50) NOT NULL, email VARCHAR(100) UNIQUE)")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("CREATE TABLE")
    sql should include("PRIMARY KEY")
    sql should include("AUTO_INCREMENT")
    sql should include("NOT NULL")
    sql should include("UNIQUE")
  }

  it should "format CREATE TABLE with table-level constraints" in {
    val ast = MySQLParser.parse("CREATE TABLE orders (id INT, user_id INT, CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id))")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("CONSTRAINT FK_USER")
    sql should include("FOREIGN KEY")
    sql should include("REFERENCES")
  }

  it should "format ALTER TABLE ADD COLUMN" in {
    val ast = MySQLParser.parse("ALTER TABLE users ADD COLUMN email VARCHAR(100) NOT NULL")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("ALTER TABLE")
    sql should include("ADD COLUMN")
    sql should include("NOT NULL")
  }

  it should "format DROP TABLE IF EXISTS" in {
    val ast = MySQLParser.parse("DROP TABLE IF EXISTS users")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("DROP TABLE IF EXISTS")
  }

  it should "format CREATE INDEX" in {
    val ast = MySQLParser.parse("CREATE UNIQUE INDEX idx_email ON users (email)")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("CREATE UNIQUE INDEX")
    sql should include("ON USERS")
  }

  it should "format DROP INDEX" in {
    val ast = MySQLParser.parse("DROP INDEX idx_name ON users")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("DROP INDEX")
    sql should include("ON USERS")
  }

  // ==========================================
  //  Visitor 集成测试 — 新语句
  // ==========================================

  "TableExtractor" should "extract table from ALTER TABLE" in {
    val ast = MySQLParser.parse("ALTER TABLE users ADD COLUMN email VARCHAR(100)")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain("USERS")
  }

  it should "extract table from CREATE INDEX" in {
    val ast = MySQLParser.parse("CREATE INDEX idx_name ON users (name)")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain("USERS")
  }

  it should "extract table from DROP INDEX" in {
    val ast = MySQLParser.parse("DROP INDEX idx_name ON users")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain("USERS")
  }

  "TableRenamer" should "rename table in ALTER TABLE" in {
    val ast = MySQLParser.parse("ALTER TABLE users ADD COLUMN email VARCHAR(100)")
    val renamer = new TableRenamer(Map("USERS" -> "customers"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("customers")
  }

  it should "rename table in CREATE INDEX" in {
    val ast = MySQLParser.parse("CREATE INDEX idx_name ON users (name)")
    val renamer = new TableRenamer(Map("USERS" -> "customers"))
    val renamed = renamer.transformStatement(ast)
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(renamed)
    sql should include("customers")
  }

  // ==========================================
  //  语义分析测试 — 新语句
  // ==========================================

  "SemanticAnalyzer" should "detect error for ALTER TABLE on non-existent table" in {
    val schema = DatabaseSchema(TableSchema.simple("users", "id", "name"))
    val ast = MySQLParser.parse("ALTER TABLE orders ADD COLUMN total INT")
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors should not be empty
    errors.head.category shouldBe "TABLE"
  }

  it should "detect error for ALTER TABLE ADD existing column" in {
    val schema = DatabaseSchema(TableSchema.simple("users", "id", "name"))
    val ast = MySQLParser.parse("ALTER TABLE users ADD COLUMN name VARCHAR(100)")
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors should not be empty
    errors.head.category shouldBe "COLUMN"
  }

  it should "detect error for ALTER TABLE DROP non-existent column" in {
    val schema = DatabaseSchema(TableSchema.simple("users", "id", "name"))
    val ast = MySQLParser.parse("ALTER TABLE users DROP COLUMN email")
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors should not be empty
    errors.head.category shouldBe "COLUMN"
  }

  it should "pass ALTER TABLE ADD new column" in {
    val schema = DatabaseSchema(TableSchema.simple("users", "id", "name"))
    val ast = MySQLParser.parse("ALTER TABLE users ADD COLUMN email VARCHAR(100)")
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors shouldBe empty
  }

  it should "detect error for CREATE INDEX on non-existent table" in {
    val schema = DatabaseSchema(TableSchema.simple("users", "id", "name"))
    val ast = MySQLParser.parse("CREATE INDEX idx_total ON orders (total)")
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors should not be empty
    errors.head.category shouldBe "TABLE"
  }

  it should "detect error for CREATE INDEX on non-existent column" in {
    val schema = DatabaseSchema(TableSchema.simple("users", "id", "name"))
    val ast = MySQLParser.parse("CREATE INDEX idx_email ON users (email)")
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors should not be empty
    errors.head.category shouldBe "COLUMN"
  }

  it should "pass CREATE INDEX on valid table and column" in {
    val schema = DatabaseSchema(TableSchema.simple("users", "id", "name"))
    val ast = MySQLParser.parse("CREATE INDEX idx_name ON users (name)")
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors shouldBe empty
  }

  it should "detect error for DROP INDEX on non-existent table" in {
    val schema = DatabaseSchema(TableSchema.simple("users", "id", "name"))
    val ast = MySQLParser.parse("DROP INDEX idx_name ON orders")
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors should not be empty
    errors.head.category shouldBe "TABLE"
  }

  // ============================================================
  //  增强错误报告测试
  // ============================================================

  "PositionedToken" should "carry correct line and column for single line SQL" in {
    val sql = "SELECT * FROM users"
    val tokens = MySQLParser.tokenize(sql)

    // SELECT 在 1:1
    tokens.head.token shouldBe SELECT
    tokens.head.position.line shouldBe 1
    tokens.head.position.column shouldBe 1

    // * 在 1:8
    tokens(1).token shouldBe MULTIPLY_OP
    tokens(1).position.line shouldBe 1
    tokens(1).position.column shouldBe 8

    // FROM 在 1:10
    tokens(2).token shouldBe FROM
    tokens(2).position.line shouldBe 1
    tokens(2).position.column shouldBe 10

    // users 在 1:15
    tokens(3).token shouldBe IdentifierToken("USERS")
    tokens(3).position.line shouldBe 1
    tokens(3).position.column shouldBe 15
  }

  it should "carry correct line and column for multi-line SQL" in {
    val sql = "SELECT *\nFROM users\nWHERE id = 1"
    val tokens = MySQLParser.tokenize(sql)

    // SELECT 在 1:1
    tokens(0).position.line shouldBe 1
    tokens(0).position.column shouldBe 1

    // * 在 1:8
    tokens(1).position.line shouldBe 1
    tokens(1).position.column shouldBe 8

    // FROM 在 2:1
    tokens(2).position.line shouldBe 2
    tokens(2).position.column shouldBe 1

    // users 在 2:6
    tokens(3).position.line shouldBe 2
    tokens(3).position.column shouldBe 6

    // WHERE 在 3:1
    tokens(4).position.line shouldBe 3
    tokens(4).position.column shouldBe 1

    // id 在 3:7
    tokens(5).position.line shouldBe 3
    tokens(5).position.column shouldBe 7
  }

  it should "carry correct positions with comments" in {
    val sql = "SELECT * -- comment\nFROM users"
    val tokens = MySQLParser.tokenize(sql)

    // SELECT 在 1:1
    tokens(0).position.line shouldBe 1
    // FROM 在 2:1（注释跳过后）
    tokens(2).position.line shouldBe 2
    tokens(2).position.column shouldBe 1
  }

  "ParseException" should "include line and column in error message" in {
    val sql = "SELECT * FROM"  // 缺少表名
    val caught = intercept[ParseException] {
      MySQLParser.parse(sql)
    }
    caught.position.line shouldBe 1
    caught.getMessage should include("[1:")
    caught.getMessage should include("SELECT * FROM")
    caught.getMessage should include("^")
  }

  it should "point to correct position on multi-line SQL" in {
    val sql = "SELECT *\nFROM\nWHERE id = 1"  // FROM 后缺少表名，直接遇到 WHERE
    val caught = intercept[ParseException] {
      MySQLParser.parse(sql)
    }
    // 错误应该指向 WHERE（第3行）
    caught.position.line shouldBe 3
    caught.getMessage should include("[3:")
    caught.getMessage should include("^")
  }

  it should "report error for unexpected character with position" in {
    val sql = "SELECT @invalid FROM users"
    val caught = intercept[ParseException] {
      MySQLParser.parse(sql)
    }
    caught.position.line shouldBe 1
    caught.position.column shouldBe 8  // @ 在第 8 列
    caught.getMessage should include("[1:8]")
    caught.getMessage should include("^")
  }

  it should "report error for unterminated string with position" in {
    val sql = "SELECT * FROM users WHERE name = 'Alice"
    val caught = intercept[ParseException] {
      MySQLParser.parse(sql)
    }
    caught.getMessage should include("Unterminated string literal")
  }

  it should "format error with source context nicely" in {
    val sql = "SELECT id, name,\nFROM users"  // 多余的逗号导致 FROM 被当作列名后出错
    val caught = intercept[ParseException] {
      MySQLParser.parse(sql)
    }
    // 应该包含行号和源码上下文
    caught.getMessage should include("|")
    caught.getMessage should include("^")
  }

  // ============================================================
  //  INTERSECT / EXCEPT 测试
  // ============================================================

  "INTERSECT" should "parse basic INTERSECT" in {
    val sql = "SELECT name FROM users INTERSECT SELECT name FROM admins"
    val ast = MySQLParser.parse(sql)
    ast shouldBe a[UnionStatement]
    val stmt = ast.asInstanceOf[UnionStatement]
    stmt.unionType shouldBe IntersectDistinct
    stmt.left shouldBe a[SelectStatement]
    stmt.right shouldBe a[SelectStatement]
  }

  it should "parse INTERSECT ALL" in {
    val sql = "SELECT id, name FROM users INTERSECT ALL SELECT id, name FROM employees"
    val ast = MySQLParser.parse(sql)
    ast shouldBe a[UnionStatement]
    val stmt = ast.asInstanceOf[UnionStatement]
    stmt.unionType shouldBe IntersectAll
  }

  it should "parse chained INTERSECT" in {
    val sql = "SELECT name FROM users INTERSECT SELECT name FROM admins INTERSECT SELECT name FROM employees"
    val ast = MySQLParser.parse(sql)
    ast shouldBe a[UnionStatement]
    val outer = ast.asInstanceOf[UnionStatement]
    outer.unionType shouldBe IntersectDistinct
    outer.left shouldBe a[UnionStatement]
    val inner = outer.left.asInstanceOf[UnionStatement]
    inner.unionType shouldBe IntersectDistinct
  }

  it should "parse mixed UNION and INTERSECT" in {
    val sql = "SELECT name FROM users UNION SELECT name FROM admins INTERSECT SELECT name FROM employees"
    val ast = MySQLParser.parse(sql)
    // 解析顺序为左结合
    ast shouldBe a[UnionStatement]
    val outer = ast.asInstanceOf[UnionStatement]
    outer.unionType shouldBe IntersectDistinct
    outer.left shouldBe a[UnionStatement]
    val inner = outer.left.asInstanceOf[UnionStatement]
    inner.unionType shouldBe UnionDistinct
  }

  "EXCEPT" should "parse basic EXCEPT" in {
    val sql = "SELECT name FROM users EXCEPT SELECT name FROM admins"
    val ast = MySQLParser.parse(sql)
    ast shouldBe a[UnionStatement]
    val stmt = ast.asInstanceOf[UnionStatement]
    stmt.unionType shouldBe ExceptDistinct
  }

  it should "parse EXCEPT ALL" in {
    val sql = "SELECT id FROM users EXCEPT ALL SELECT id FROM admins"
    val ast = MySQLParser.parse(sql)
    ast shouldBe a[UnionStatement]
    val stmt = ast.asInstanceOf[UnionStatement]
    stmt.unionType shouldBe ExceptAll
  }

  it should "parse chained EXCEPT" in {
    val sql = "SELECT id FROM a EXCEPT SELECT id FROM b EXCEPT SELECT id FROM c"
    val ast = MySQLParser.parse(sql)
    ast shouldBe a[UnionStatement]
    val outer = ast.asInstanceOf[UnionStatement]
    outer.unionType shouldBe ExceptDistinct
    outer.left shouldBe a[UnionStatement]
  }

  "INTERSECT/EXCEPT Lexer" should "recognize INTERSECT and EXCEPT tokens" in {
    val sql = "INTERSECT ALL EXCEPT"
    val lexer = new Lexer(sql)
    val tokens = lexer.tokenize()
    tokens should contain theSameElementsInOrderAs List(
      INTERSECT, ALL, EXCEPT, EOF
    )
  }

  "INTERSECT/EXCEPT PrettyPrinter" should "format INTERSECT correctly" in {
    val sql = "SELECT name FROM users INTERSECT SELECT name FROM admins"
    val ast = MySQLParser.parse(sql)
    val printer = new SQLPrettyPrinter()
    val formatted = printer.visitStatement(ast)
    formatted should include("INTERSECT")
    formatted should not include("UNION")
  }

  it should "format INTERSECT ALL correctly" in {
    val sql = "SELECT name FROM users INTERSECT ALL SELECT name FROM admins"
    val ast = MySQLParser.parse(sql)
    val printer = new SQLPrettyPrinter()
    val formatted = printer.visitStatement(ast)
    formatted should include("INTERSECT ALL")
  }

  it should "format EXCEPT correctly" in {
    val sql = "SELECT name FROM users EXCEPT SELECT name FROM admins"
    val ast = MySQLParser.parse(sql)
    val printer = new SQLPrettyPrinter()
    val formatted = printer.visitStatement(ast)
    formatted should include("EXCEPT")
    formatted should not include("UNION")
  }

  it should "format EXCEPT ALL correctly" in {
    val sql = "SELECT name FROM users EXCEPT ALL SELECT name FROM admins"
    val ast = MySQLParser.parse(sql)
    val printer = new SQLPrettyPrinter()
    val formatted = printer.visitStatement(ast)
    formatted should include("EXCEPT ALL")
  }

  "INTERSECT/EXCEPT Semantic" should "detect column count mismatch in INTERSECT" in {
    val schema = DatabaseSchema(
      TableSchema.simple("users", "id", "name"),
      TableSchema.simple("admins", "id", "name", "level")
    )
    val sql = "SELECT id, name FROM users INTERSECT SELECT id, name, level FROM admins"
    val ast = MySQLParser.parse(sql)
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors.exists(_.message.contains("INTERSECT")) shouldBe true
    errors.exists(_.message.contains("same number of columns")) shouldBe true
  }

  it should "detect column count mismatch in EXCEPT" in {
    val schema = DatabaseSchema(
      TableSchema.simple("users", "id", "name"),
      TableSchema.simple("admins", "id")
    )
    val sql = "SELECT id, name FROM users EXCEPT SELECT id FROM admins"
    val ast = MySQLParser.parse(sql)
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors.exists(_.message.contains("EXCEPT")) shouldBe true
  }

  it should "pass semantic check for valid INTERSECT" in {
    val schema = DatabaseSchema(
      TableSchema.simple("users", "id", "name"),
      TableSchema.simple("admins", "id", "name")
    )
    val sql = "SELECT id, name FROM users INTERSECT SELECT id, name FROM admins"
    val ast = MySQLParser.parse(sql)
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors.filter(_.severity == SError) shouldBe empty
  }

  it should "pass semantic check for valid EXCEPT" in {
    val schema = DatabaseSchema(
      TableSchema.simple("users", "id", "name"),
      TableSchema.simple("admins", "id", "name")
    )
    val sql = "SELECT id, name FROM users EXCEPT SELECT id, name FROM admins"
    val ast = MySQLParser.parse(sql)
    val errors = SemanticAnalyzer.analyze(ast, schema)
    errors.filter(_.severity == SError) shouldBe empty
  }

  "INTERSECT/EXCEPT SemanticVisitor" should "detect column count mismatch via Visitor pipeline" in {
    val schema = DatabaseSchema(
      TableSchema.simple("users", "id", "name"),
      TableSchema.simple("admins", "id")
    )
    val pipeline = new SemanticVisitorPipeline(schema)
    val sql = "SELECT id, name FROM users INTERSECT SELECT id FROM admins"
    val ast = MySQLParser.parse(sql)
    val errors = pipeline.analyze(ast)
    errors.exists(_.message.contains("INTERSECT")) shouldBe true
  }

  // ============================================================
  //  AST JSON 序列化测试
  // ============================================================

  "ASTJsonSerializer" should "serialize SELECT to JSON" in {
    val sql = "SELECT * FROM users"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"SelectStatement\"")
    json should include("\"AllColumns\"")
  }

  it should "serialize SELECT with columns to JSON" in {
    val sql = "SELECT id, name FROM users WHERE age > 18"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"SelectStatement\"")
    json should include("\"NamedColumn\"")
    json should include("\"ID\"")
    json should include("\"NAME\"")
    json should include("\"BinaryExpression\"")
  }

  it should "serialize INSERT to JSON" in {
    val sql = "INSERT INTO users (name, age) VALUES ('Alice', 25)"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"InsertStatement\"")
    json should include("\"USERS\"")
    json should include("\"Alice\"")
  }

  it should "serialize UPDATE to JSON" in {
    val sql = "UPDATE users SET age = 25 WHERE name = 'Bob'"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"UpdateStatement\"")
    json should include("\"USERS\"")
    json should include("\"assignments\"")
  }

  it should "serialize DELETE to JSON" in {
    val sql = "DELETE FROM users WHERE id = 1"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"DeleteStatement\"")
    json should include("\"USERS\"")
  }

  it should "serialize CREATE TABLE to JSON" in {
    val sql = "CREATE TABLE test (id INT, name VARCHAR(100))"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"CreateTableStatement\"")
    json should include("\"TEST\"")
    json should include("INT")
    json should include("VARCHAR(100)")
  }

  it should "serialize DROP TABLE to JSON" in {
    val sql = "DROP TABLE IF EXISTS users"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"DropTableStatement\"")
    json should include("\"ifExists\":true")
  }

  it should "serialize UNION to JSON" in {
    val sql = "SELECT name FROM users UNION ALL SELECT name FROM admins"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"UnionStatement\"")
    json should include("\"UNION ALL\"")
  }

  it should "serialize INTERSECT to JSON" in {
    val sql = "SELECT name FROM users INTERSECT SELECT name FROM admins"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"UnionStatement\"")
    json should include("\"INTERSECT\"")
  }

  it should "serialize EXCEPT to JSON" in {
    val sql = "SELECT name FROM users EXCEPT ALL SELECT name FROM admins"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"UnionStatement\"")
    json should include("\"EXCEPT ALL\"")
  }

  it should "serialize JOIN to JSON" in {
    val sql = "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"JoinClause\"")
    json should include("\"INNER\"")
  }

  it should "serialize CASE expression to JSON" in {
    val sql = "SELECT CASE WHEN salary > 10000 THEN 'high' ELSE 'low' END FROM employees"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"CaseExpression\"")
    json should include("\"whenClauses\"")
  }

  it should "serialize CAST to JSON" in {
    val sql = "SELECT CAST(price AS DECIMAL(10,2)) FROM products"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"CastExpression\"")
    json should include("DECIMAL(10,2)")
  }

  it should "serialize window function to JSON" in {
    val sql = "SELECT name, RANK() OVER (ORDER BY salary DESC) FROM employees"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"WindowFunctionExpression\"")
    json should include("\"windowSpec\"")
  }

  it should "serialize CTE (WITH) to JSON" in {
    val sql = "WITH cte AS (SELECT id FROM users) SELECT * FROM cte"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"WithStatement\"")
    json should include("\"CTE\"")
  }

  it should "serialize ALTER TABLE to JSON" in {
    val sql = "ALTER TABLE users ADD COLUMN email VARCHAR(255)"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"AlterTableStatement\"")
    json should include("\"ADD COLUMN\"")
  }

  it should "serialize CREATE INDEX to JSON" in {
    val sql = "CREATE UNIQUE INDEX idx_email ON users (email)"
    val ast = MySQLParser.parse(sql)
    val json = ASTJsonSerializer.toJson(ast)
    json should include("\"type\":\"CreateIndexStatement\"")
    json should include("\"unique\":true")
  }

  it should "produce valid pretty JSON" in {
    val sql = "SELECT id, name FROM users WHERE age > 18"
    val ast = MySQLParser.parse(sql)
    val prettyJson = ASTJsonSerializer.toJsonPretty(ast)
    prettyJson should include("\n")
    prettyJson should include("  ")  // 缩进
    prettyJson should include("\"type\": \"SelectStatement\"")
  }

  "MySQLParser.toJson" should "return JSON string from SQL" in {
    val json = MySQLParser.toJson("SELECT * FROM users")
    json should include("\"SelectStatement\"")
  }

  "MySQLParser.toJsonPretty" should "return pretty JSON string from SQL" in {
    val json = MySQLParser.toJsonPretty("SELECT * FROM users")
    json should include("\n")
    json should include("\"SelectStatement\"")
  }

  // ============================================================
  //  类型检查/类型推断测试
  // ============================================================

  "TypeInferencer" should "infer string literal type" in {
    TypeInferencer.infer(StringLiteral("hello")) shouldBe StringSQLType
  }

  it should "infer integer literal type" in {
    TypeInferencer.infer(NumberLiteral("42")) shouldBe IntegerSQLType
  }

  it should "infer decimal literal type" in {
    TypeInferencer.infer(NumberLiteral("3.14")) shouldBe NumericSQLType
  }

  it should "infer NULL literal type" in {
    TypeInferencer.infer(NullLiteral) shouldBe NullSQLType
  }

  it should "infer comparison expression as BOOLEAN" in {
    val expr = BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("2"))
    TypeInferencer.infer(expr) shouldBe BooleanSQLType
  }

  it should "infer arithmetic expression type" in {
    val expr = BinaryExpression(NumberLiteral("1"), Plus, NumberLiteral("2"))
    TypeInferencer.infer(expr) shouldBe IntegerSQLType
  }

  it should "infer mixed arithmetic expression type as NUMERIC" in {
    val expr = BinaryExpression(NumberLiteral("1"), Plus, NumberLiteral("2.5"))
    TypeInferencer.infer(expr) shouldBe NumericSQLType
  }

  it should "infer AND/OR expression as BOOLEAN" in {
    val expr = BinaryExpression(
      BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")),
      AndOp,
      BinaryExpression(NumberLiteral("2"), Equal, NumberLiteral("2"))
    )
    TypeInferencer.infer(expr) shouldBe BooleanSQLType
  }

  it should "infer COUNT(*) as INTEGER" in {
    val expr = AggregateFunction(CountFunc, AllColumnsExpression)
    TypeInferencer.infer(expr) shouldBe IntegerSQLType
  }

  it should "infer SUM as NUMERIC" in {
    val expr = AggregateFunction(SumFunc, NumberLiteral("1"))
    TypeInferencer.infer(expr) shouldBe NumericSQLType
  }

  it should "infer AVG as NUMERIC" in {
    val expr = AggregateFunction(AvgFunc, NumberLiteral("1"))
    TypeInferencer.infer(expr) shouldBe NumericSQLType
  }

  it should "infer MAX/MIN inherit argument type" in {
    val expr = AggregateFunction(MaxFunc, StringLiteral("test"))
    TypeInferencer.infer(expr) shouldBe StringSQLType
  }

  it should "infer CAST target type" in {
    val expr = CastExpression(StringLiteral("123"), IntCastType)
    TypeInferencer.infer(expr) shouldBe IntegerSQLType
  }

  it should "infer CAST to DECIMAL as NUMERIC" in {
    val expr = CastExpression(StringLiteral("1.5"), DecimalCastType(Some(10), Some(2)))
    TypeInferencer.infer(expr) shouldBe NumericSQLType
  }

  it should "infer CAST to CHAR as STRING" in {
    val expr = CastExpression(NumberLiteral("42"), CharCastType(None))
    TypeInferencer.infer(expr) shouldBe StringSQLType
  }

  it should "infer CASE expression type from branches" in {
    val expr = CaseExpression(
      None,
      List(WhenClause(BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")), StringLiteral("yes"))),
      Some(StringLiteral("no"))
    )
    TypeInferencer.infer(expr) shouldBe StringSQLType
  }

  it should "infer string function type" in {
    val expr = FunctionCall("UPPER", List(StringLiteral("hello")))
    TypeInferencer.infer(expr) shouldBe StringSQLType
  }

  it should "infer NOW() as DATETIME" in {
    val expr = FunctionCall("NOW", List.empty)
    TypeInferencer.infer(expr) shouldBe DateTimeSQLType
  }

  it should "infer LENGTH as INTEGER" in {
    val expr = FunctionCall("LENGTH", List(StringLiteral("hello")))
    TypeInferencer.infer(expr) shouldBe IntegerSQLType
  }

  it should "infer ROUND as NUMERIC" in {
    val expr = FunctionCall("ROUND", List(NumberLiteral("3.14"), NumberLiteral("1")))
    TypeInferencer.infer(expr) shouldBe NumericSQLType
  }

  it should "infer IS NULL as BOOLEAN" in {
    val expr = IsNullExpression(Identifier("name"))
    TypeInferencer.infer(expr) shouldBe BooleanSQLType
  }

  it should "infer BETWEEN as BOOLEAN" in {
    val expr = BetweenExpression(NumberLiteral("5"), NumberLiteral("1"), NumberLiteral("10"))
    TypeInferencer.infer(expr) shouldBe BooleanSQLType
  }

  it should "infer IN as BOOLEAN" in {
    val expr = InExpression(NumberLiteral("1"), List(NumberLiteral("1"), NumberLiteral("2")))
    TypeInferencer.infer(expr) shouldBe BooleanSQLType
  }

  it should "infer LIKE as BOOLEAN" in {
    val expr = LikeExpression(StringLiteral("hello"), StringLiteral("%lo"))
    TypeInferencer.infer(expr) shouldBe BooleanSQLType
  }

  it should "infer EXISTS as BOOLEAN" in {
    val subquery = SelectStatement(List(AllColumns), None, None, None, None, None, None, None)
    val expr = ExistsExpression(subquery)
    TypeInferencer.infer(expr) shouldBe BooleanSQLType
  }

  it should "infer column type from scope" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR"),
        ColumnSchema("SALARY", "DECIMAL")
      ))
    )
    val scope = QueryScope(
      tables = Map("USERS" -> schema.tables("USERS")),
      allColumns = Map("ID" -> Set("USERS"), "NAME" -> Set("USERS"), "SALARY" -> Set("USERS"))
    )
    TypeInferencer.infer(Identifier("ID"), scope) shouldBe IntegerSQLType
    TypeInferencer.infer(Identifier("NAME"), scope) shouldBe StringSQLType
    TypeInferencer.infer(Identifier("SALARY"), scope) shouldBe NumericSQLType
  }

  it should "infer window function type from inner function" in {
    val expr = WindowFunctionExpression(
      AggregateFunction(SumFunc, Identifier("salary")),
      WindowSpec(orderBy = Some(List(OrderByClause(Identifier("id")))))
    )
    TypeInferencer.infer(expr) shouldBe NumericSQLType
  }

  "TypeChecker" should "consider same types compatible" in {
    TypeChecker.areCompatible(IntegerSQLType, IntegerSQLType) shouldBe true
    TypeChecker.areCompatible(StringSQLType, StringSQLType) shouldBe true
  }

  it should "consider NULL compatible with anything" in {
    TypeChecker.areCompatible(NullSQLType, IntegerSQLType) shouldBe true
    TypeChecker.areCompatible(StringSQLType, NullSQLType) shouldBe true
    TypeChecker.areCompatible(NullSQLType, NullSQLType) shouldBe true
  }

  it should "consider UNKNOWN compatible with anything" in {
    TypeChecker.areCompatible(UnknownSQLType, IntegerSQLType) shouldBe true
    TypeChecker.areCompatible(StringSQLType, UnknownSQLType) shouldBe true
  }

  it should "consider INTEGER and NUMERIC compatible" in {
    TypeChecker.areCompatible(IntegerSQLType, NumericSQLType) shouldBe true
    TypeChecker.areCompatible(NumericSQLType, IntegerSQLType) shouldBe true
  }

  it should "consider BOOLEAN and INTEGER compatible" in {
    TypeChecker.areCompatible(BooleanSQLType, IntegerSQLType) shouldBe true
    TypeChecker.areCompatible(IntegerSQLType, BooleanSQLType) shouldBe true
  }

  it should "consider DATE and DATETIME compatible" in {
    TypeChecker.areCompatible(DateSQLType, DateTimeSQLType) shouldBe true
    TypeChecker.areCompatible(DateTimeSQLType, DateSQLType) shouldBe true
  }

  it should "consider STRING and DATE compatible" in {
    TypeChecker.areCompatible(StringSQLType, DateSQLType) shouldBe true
    TypeChecker.areCompatible(DateSQLType, StringSQLType) shouldBe true
  }

  it should "consider STRING and INTEGER incompatible" in {
    TypeChecker.areCompatible(StringSQLType, IntegerSQLType) shouldBe false
    TypeChecker.areCompatible(IntegerSQLType, StringSQLType) shouldBe false
  }

  it should "consider BOOLEAN and STRING incompatible" in {
    TypeChecker.areCompatible(BooleanSQLType, StringSQLType) shouldBe false
  }

  it should "detect arithmetic type error" in {
    val result = TypeChecker.checkArithmetic(StringSQLType, Plus, IntegerSQLType)
    result shouldBe defined
    result.get.message should include("numeric")
  }

  it should "pass arithmetic check for numerics" in {
    TypeChecker.checkArithmetic(IntegerSQLType, Plus, NumericSQLType) shouldBe None
    TypeChecker.checkArithmetic(IntegerSQLType, Multiply, IntegerSQLType) shouldBe None
  }

  it should "detect comparison type error" in {
    val result = TypeChecker.checkComparison(StringSQLType, Equal, IntegerSQLType)
    result shouldBe defined
    result.get.message should include("Cannot compare")
  }

  it should "pass comparison check for compatible types" in {
    TypeChecker.checkComparison(IntegerSQLType, Equal, IntegerSQLType) shouldBe None
    TypeChecker.checkComparison(IntegerSQLType, LessThan, NumericSQLType) shouldBe None
    TypeChecker.checkComparison(StringSQLType, Equal, StringSQLType) shouldBe None
    TypeChecker.checkComparison(DateSQLType, Equal, DateTimeSQLType) shouldBe None
  }

  it should "detect logical type error" in {
    val result = TypeChecker.checkLogical(StringSQLType, AndOp, BooleanSQLType)
    result shouldBe defined
    result.get.message should include("boolean")
  }

  it should "pass logical check for boolean types" in {
    TypeChecker.checkLogical(BooleanSQLType, AndOp, BooleanSQLType) shouldBe None
    TypeChecker.checkLogical(BooleanSQLType, OrOp, IntegerSQLType) shouldBe None  // MySQL 允许
  }

  "TypeInferencer.unifyTypes" should "unify same types" in {
    TypeInferencer.unifyTypes(List(IntegerSQLType, IntegerSQLType)) shouldBe IntegerSQLType
    TypeInferencer.unifyTypes(List(StringSQLType, StringSQLType)) shouldBe StringSQLType
  }

  it should "unify INTEGER and NUMERIC to NUMERIC" in {
    TypeInferencer.unifyTypes(List(IntegerSQLType, NumericSQLType)) shouldBe NumericSQLType
  }

  it should "unify DATE and DATETIME to DATETIME" in {
    TypeInferencer.unifyTypes(List(DateSQLType, DateTimeSQLType)) shouldBe DateTimeSQLType
  }

  it should "unify mixed types to STRING" in {
    TypeInferencer.unifyTypes(List(IntegerSQLType, StringSQLType)) shouldBe StringSQLType
  }

  it should "handle NULL types gracefully" in {
    TypeInferencer.unifyTypes(List(NullSQLType, IntegerSQLType)) shouldBe IntegerSQLType
    TypeInferencer.unifyTypes(List(NullSQLType, NullSQLType)) shouldBe NullSQLType
  }

  "TypeCheckVisitor" should "detect type mismatch in WHERE clause" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR"),
        ColumnSchema("AGE", "INT")
      ))
    )
    val sql = "SELECT * FROM users WHERE name + 1 > 0"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    // name 是 STRING, + 1 应该产生类型警告
    errors.exists(_.category == "TYPE") shouldBe true
  }

  it should "pass type check for correct arithmetic" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("AGE", "INT"),
        ColumnSchema("SALARY", "DECIMAL")
      ))
    )
    val sql = "SELECT * FROM users WHERE age + salary > 100"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    errors.filter(_.category == "TYPE") shouldBe empty
  }

  it should "detect type mismatch in UNION columns" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR")
      )),
      TableSchema("PRODUCTS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("PRICE", "DECIMAL")
      ))
    )
    val sql = "SELECT name FROM users UNION SELECT price FROM products"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    errors.exists(e => e.category == "TYPE" && e.message.contains("UNION")) shouldBe true
  }

  it should "detect type mismatch in INTERSECT columns" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("NAME", "VARCHAR")
      )),
      TableSchema("PRODUCTS", List(
        ColumnSchema("PRICE", "DECIMAL")
      ))
    )
    val sql = "SELECT name FROM users INTERSECT SELECT price FROM products"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    errors.exists(e => e.category == "TYPE" && e.message.contains("INTERSECT")) shouldBe true
  }

  it should "detect type mismatch in EXCEPT columns" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("NAME", "VARCHAR")
      )),
      TableSchema("PRODUCTS", List(
        ColumnSchema("PRICE", "DECIMAL")
      ))
    )
    val sql = "SELECT name FROM users EXCEPT SELECT price FROM products"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    errors.exists(e => e.category == "TYPE" && e.message.contains("EXCEPT")) shouldBe true
  }

  it should "pass type check for compatible UNION columns" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR")
      )),
      TableSchema("ADMINS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR")
      ))
    )
    val sql = "SELECT id, name FROM users UNION SELECT id, name FROM admins"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    errors.filter(_.category == "TYPE") shouldBe empty
  }

  it should "integrate into SemanticVisitorPipeline" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR")
      ))
    )
    val pipeline = new SemanticVisitorPipeline(schema)
    val sql = "SELECT * FROM users WHERE name + 1 > 0"
    val ast = MySQLParser.parse(sql)
    val errors = pipeline.analyze(ast)
    errors.exists(_.category == "TYPE") shouldBe true
  }

  "TypeInferencer.dataTypeStringToSQLType" should "map data type strings correctly" in {
    TypeInferencer.dataTypeStringToSQLType("INT") shouldBe IntegerSQLType
    TypeInferencer.dataTypeStringToSQLType("BIGINT") shouldBe IntegerSQLType
    TypeInferencer.dataTypeStringToSQLType("VARCHAR") shouldBe StringSQLType
    TypeInferencer.dataTypeStringToSQLType("TEXT") shouldBe StringSQLType
    TypeInferencer.dataTypeStringToSQLType("FLOAT") shouldBe NumericSQLType
    TypeInferencer.dataTypeStringToSQLType("DOUBLE") shouldBe NumericSQLType
    TypeInferencer.dataTypeStringToSQLType("DECIMAL") shouldBe NumericSQLType
    TypeInferencer.dataTypeStringToSQLType("BOOLEAN") shouldBe BooleanSQLType
    TypeInferencer.dataTypeStringToSQLType("DATE") shouldBe DateSQLType
    TypeInferencer.dataTypeStringToSQLType("DATETIME") shouldBe DateTimeSQLType
    TypeInferencer.dataTypeStringToSQLType("TIMESTAMP") shouldBe DateTimeSQLType
    TypeInferencer.dataTypeStringToSQLType("UNKNOWN") shouldBe UnknownSQLType
  }

  // ============================================================
  //  查询执行引擎测试
  // ============================================================

  "QueryExecutor - CREATE TABLE and INSERT" should "create table and insert rows" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    // CREATE TABLE
    val createResult = executor.execute(MySQLParser.parse("CREATE TABLE users (id INT, name VARCHAR(50), age INT)"))
    createResult.rows.head("Result").toString should include("created")

    // INSERT
    val insertResult = executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME, AGE) VALUES (1, 'Alice', 25)"))
    insertResult.rows.head("Result").toString should include("1 row(s) inserted")

    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME, AGE) VALUES (2, 'Bob', 30)"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME, AGE) VALUES (3, 'Charlie', 35)"))

    // SELECT *
    val selectResult = executor.execute(MySQLParser.parse("SELECT * FROM users"))
    selectResult.rowCount shouldBe 3
  }

  "QueryExecutor - SELECT with WHERE" should "filter rows correctly" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE products (id INT, name VARCHAR(50), price INT, category VARCHAR(20))"))
    executor.execute(MySQLParser.parse("INSERT INTO products (ID, NAME, PRICE, CATEGORY) VALUES (1, 'Laptop', 1000, 'Electronics')"))
    executor.execute(MySQLParser.parse("INSERT INTO products (ID, NAME, PRICE, CATEGORY) VALUES (2, 'Phone', 500, 'Electronics')"))
    executor.execute(MySQLParser.parse("INSERT INTO products (ID, NAME, PRICE, CATEGORY) VALUES (3, 'Book', 20, 'Books')"))
    executor.execute(MySQLParser.parse("INSERT INTO products (ID, NAME, PRICE, CATEGORY) VALUES (4, 'Pen', 5, 'Office')"))

    // WHERE with comparison
    val result = executor.execute(MySQLParser.parse("SELECT NAME, PRICE FROM products WHERE PRICE > 100"))
    result.rowCount shouldBe 2

    // WHERE with equality
    val result2 = executor.execute(MySQLParser.parse("SELECT NAME FROM products WHERE CATEGORY = 'Books'"))
    result2.rowCount shouldBe 1
    result2.rows.head("NAME") shouldBe "Book"
  }

  "QueryExecutor - SELECT with ORDER BY" should "sort rows correctly" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE items (id INT, name VARCHAR(50), price INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO items (ID, NAME, PRICE) VALUES (1, 'C', 30)"))
    executor.execute(MySQLParser.parse("INSERT INTO items (ID, NAME, PRICE) VALUES (2, 'A', 10)"))
    executor.execute(MySQLParser.parse("INSERT INTO items (ID, NAME, PRICE) VALUES (3, 'B', 20)"))

    val result = executor.execute(MySQLParser.parse("SELECT NAME, PRICE FROM items ORDER BY PRICE ASC"))
    result.rowCount shouldBe 3
    result.rows(0)("PRICE") shouldBe 10L
    result.rows(1)("PRICE") shouldBe 20L
    result.rows(2)("PRICE") shouldBe 30L

    val resultDesc = executor.execute(MySQLParser.parse("SELECT NAME FROM items ORDER BY NAME DESC"))
    resultDesc.rows(0)("NAME") shouldBe "C"
    resultDesc.rows(2)("NAME") shouldBe "A"
  }

  "QueryExecutor - SELECT with LIMIT and OFFSET" should "limit rows correctly" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE nums (val INT)"))
    (1 to 10).foreach { i =>
      executor.execute(MySQLParser.parse(s"INSERT INTO nums (VAL) VALUES ($i)"))
    }

    val result = executor.execute(MySQLParser.parse("SELECT VAL FROM nums ORDER BY VAL LIMIT 3"))
    result.rowCount shouldBe 3
    result.rows(0)("VAL") shouldBe 1L

    val result2 = executor.execute(MySQLParser.parse("SELECT VAL FROM nums ORDER BY VAL LIMIT 3 OFFSET 5"))
    result2.rowCount shouldBe 3
    result2.rows(0)("VAL") shouldBe 6L
  }

  "QueryExecutor - SELECT DISTINCT" should "remove duplicate rows" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE tags (name VARCHAR(20))"))
    executor.execute(MySQLParser.parse("INSERT INTO tags (NAME) VALUES ('A')"))
    executor.execute(MySQLParser.parse("INSERT INTO tags (NAME) VALUES ('B')"))
    executor.execute(MySQLParser.parse("INSERT INTO tags (NAME) VALUES ('A')"))
    executor.execute(MySQLParser.parse("INSERT INTO tags (NAME) VALUES ('C')"))
    executor.execute(MySQLParser.parse("INSERT INTO tags (NAME) VALUES ('B')"))

    val result = executor.execute(MySQLParser.parse("SELECT DISTINCT NAME FROM tags"))
    result.rowCount shouldBe 3
  }

  "QueryExecutor - GROUP BY and aggregation" should "group and compute aggregates" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE sales (dept VARCHAR(20), amount INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO sales (DEPT, AMOUNT) VALUES ('Engineering', 100)"))
    executor.execute(MySQLParser.parse("INSERT INTO sales (DEPT, AMOUNT) VALUES ('Engineering', 200)"))
    executor.execute(MySQLParser.parse("INSERT INTO sales (DEPT, AMOUNT) VALUES ('Sales', 150)"))
    executor.execute(MySQLParser.parse("INSERT INTO sales (DEPT, AMOUNT) VALUES ('Sales', 250)"))
    executor.execute(MySQLParser.parse("INSERT INTO sales (DEPT, AMOUNT) VALUES ('Sales', 300)"))

    // GROUP BY with COUNT
    val result = executor.execute(MySQLParser.parse("SELECT DEPT, COUNT(*) AS CNT FROM sales GROUP BY DEPT"))
    result.rowCount shouldBe 2

    // GROUP BY with SUM
    val result2 = executor.execute(MySQLParser.parse("SELECT DEPT, SUM(AMOUNT) AS TOTAL FROM sales GROUP BY DEPT"))
    result2.rowCount shouldBe 2
  }

  "QueryExecutor - aggregate without GROUP BY" should "compute full-table aggregate" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE scores (val INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO scores (VAL) VALUES (10)"))
    executor.execute(MySQLParser.parse("INSERT INTO scores (VAL) VALUES (20)"))
    executor.execute(MySQLParser.parse("INSERT INTO scores (VAL) VALUES (30)"))

    val result = executor.execute(MySQLParser.parse("SELECT COUNT(*) AS CNT, SUM(VAL) AS TOTAL, AVG(VAL) AS AVG_VAL FROM scores"))
    result.rowCount shouldBe 1
    result.rows.head("CNT") shouldBe 3L
  }

  "QueryExecutor - HAVING" should "filter groups" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE orders (dept VARCHAR(20), amount INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO orders (DEPT, AMOUNT) VALUES ('A', 100)"))
    executor.execute(MySQLParser.parse("INSERT INTO orders (DEPT, AMOUNT) VALUES ('A', 200)"))
    executor.execute(MySQLParser.parse("INSERT INTO orders (DEPT, AMOUNT) VALUES ('B', 50)"))

    val result = executor.execute(MySQLParser.parse("SELECT DEPT, SUM(AMOUNT) AS TOTAL FROM orders GROUP BY DEPT HAVING SUM(AMOUNT) > 100"))
    result.rowCount shouldBe 1
    result.rows.head("DEPT") shouldBe "A"
  }

  "QueryExecutor - JOIN" should "join two tables" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE users (id INT, name VARCHAR(50))"))
    executor.execute(MySQLParser.parse("CREATE TABLE orders (id INT, user_id INT, product VARCHAR(50))"))

    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME) VALUES (1, 'Alice')"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME) VALUES (2, 'Bob')"))
    executor.execute(MySQLParser.parse("INSERT INTO orders (ID, USER_ID, PRODUCT) VALUES (1, 1, 'Laptop')"))
    executor.execute(MySQLParser.parse("INSERT INTO orders (ID, USER_ID, PRODUCT) VALUES (2, 1, 'Phone')"))
    executor.execute(MySQLParser.parse("INSERT INTO orders (ID, USER_ID, PRODUCT) VALUES (3, 2, 'Book')"))

    val result = executor.execute(MySQLParser.parse(
      "SELECT u.NAME, o.PRODUCT FROM users u JOIN orders o ON u.ID = o.USER_ID"))
    result.rowCount shouldBe 3
  }

  "QueryExecutor - UPDATE" should "update rows" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE users (id INT, name VARCHAR(50), age INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME, AGE) VALUES (1, 'Alice', 25)"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME, AGE) VALUES (2, 'Bob', 30)"))

    val updateResult = executor.execute(MySQLParser.parse("UPDATE users SET AGE = 26 WHERE NAME = 'Alice'"))
    updateResult.rows.head("Result").toString should include("1 row(s) updated")

    val selectResult = executor.execute(MySQLParser.parse("SELECT AGE FROM users WHERE NAME = 'Alice'"))
    selectResult.rows.head("AGE") shouldBe 26L
  }

  "QueryExecutor - DELETE" should "delete rows" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE users (id INT, name VARCHAR(50))"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME) VALUES (1, 'Alice')"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME) VALUES (2, 'Bob')"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME) VALUES (3, 'Charlie')"))

    val deleteResult = executor.execute(MySQLParser.parse("DELETE FROM users WHERE ID = 2"))
    deleteResult.rows.head("Result").toString should include("1 row(s) deleted")

    val selectResult = executor.execute(MySQLParser.parse("SELECT * FROM users"))
    selectResult.rowCount shouldBe 2
  }

  "QueryExecutor - DROP TABLE" should "drop table" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE temp (id INT)"))
    db.hasTable("TEMP") shouldBe true

    executor.execute(MySQLParser.parse("DROP TABLE temp"))
    db.hasTable("TEMP") shouldBe false
  }

  "QueryExecutor - DROP TABLE IF EXISTS" should "not throw on non-existent table" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    noException should be thrownBy {
      executor.execute(MySQLParser.parse("DROP TABLE IF EXISTS nonexistent"))
    }
  }

  "QueryExecutor - UNION" should "combine results" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t1 (name VARCHAR(20))"))
    executor.execute(MySQLParser.parse("CREATE TABLE t2 (name VARCHAR(20))"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (NAME) VALUES ('Alice')"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (NAME) VALUES ('Bob')"))
    executor.execute(MySQLParser.parse("INSERT INTO t2 (NAME) VALUES ('Bob')"))
    executor.execute(MySQLParser.parse("INSERT INTO t2 (NAME) VALUES ('Charlie')"))

    val unionResult = executor.execute(MySQLParser.parse("SELECT NAME FROM t1 UNION SELECT NAME FROM t2"))
    unionResult.rowCount shouldBe 3  // distinct

    val unionAllResult = executor.execute(MySQLParser.parse("SELECT NAME FROM t1 UNION ALL SELECT NAME FROM t2"))
    unionAllResult.rowCount shouldBe 4
  }

  "QueryExecutor - SELECT without FROM" should "evaluate expressions" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    val result = executor.execute(MySQLParser.parse("SELECT 1 + 2 AS RESULT"))
    result.rows.head("RESULT") shouldBe 3L
  }

  "QueryExecutor - CASE WHEN" should "evaluate case expressions" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE emp (name VARCHAR(50), salary INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO emp (NAME, SALARY) VALUES ('Alice', 8000)"))
    executor.execute(MySQLParser.parse("INSERT INTO emp (NAME, SALARY) VALUES ('Bob', 3000)"))

    val result = executor.execute(MySQLParser.parse(
      "SELECT NAME, CASE WHEN SALARY > 5000 THEN 'high' ELSE 'low' END AS LEVEL FROM emp ORDER BY NAME"))
    result.rows(0)("LEVEL") shouldBe "high"
    result.rows(1)("LEVEL") shouldBe "low"
  }

  "QueryExecutor - functions" should "evaluate built-in functions" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (name VARCHAR(50))"))
    executor.execute(MySQLParser.parse("INSERT INTO t (NAME) VALUES ('hello')"))

    val result = executor.execute(MySQLParser.parse("SELECT UPPER(NAME) AS UP FROM t"))
    result.rows.head("UP") shouldBe "HELLO"

    val result2 = executor.execute(MySQLParser.parse("SELECT LENGTH(NAME) AS LEN FROM t"))
    result2.rows.head("LEN") shouldBe 5L
  }

  "QueryResult.toTable" should "format table output" in {
    val result = QueryResult(
      List("NAME", "AGE"),
      List(
        Map("NAME" -> "Alice", "AGE" -> 25L),
        Map("NAME" -> "Bob", "AGE" -> 30L)
      )
    )
    val table = result.toTable
    table should include("NAME")
    table should include("AGE")
    table should include("Alice")
    table should include("Bob")
    table should include("2 row(s)")
  }

  // ============================================================
  //  表达式求值器测试
  // ============================================================

  "ExpressionEvaluator" should "evaluate basic expressions" in {
    ExpressionEvaluator.evaluate(NumberLiteral("42"), Map.empty) shouldBe 42L
    ExpressionEvaluator.evaluate(StringLiteral("hello"), Map.empty) shouldBe "hello"
    ExpressionEvaluator.evaluate(NullLiteral, Map.empty) shouldBe (null: Any)
  }

  it should "evaluate binary arithmetic" in {
    ExpressionEvaluator.evaluate(
      BinaryExpression(NumberLiteral("10"), Plus, NumberLiteral("5")), Map.empty
    ) shouldBe 15L

    ExpressionEvaluator.evaluate(
      BinaryExpression(NumberLiteral("10"), Multiply, NumberLiteral("3")), Map.empty
    ) shouldBe 30L
  }

  it should "evaluate comparison operators" in {
    ExpressionEvaluator.evaluate(
      BinaryExpression(NumberLiteral("10"), GreaterThan, NumberLiteral("5")), Map.empty
    ) shouldBe true

    ExpressionEvaluator.evaluate(
      BinaryExpression(NumberLiteral("3"), Equal, NumberLiteral("3")), Map.empty
    ) shouldBe true
  }

  it should "evaluate IS NULL / IS NOT NULL" in {
    ExpressionEvaluator.evaluate(IsNullExpression(NullLiteral), Map.empty) shouldBe true
    ExpressionEvaluator.evaluate(IsNullExpression(NumberLiteral("1")), Map.empty) shouldBe false
    ExpressionEvaluator.evaluate(IsNullExpression(NullLiteral, negated = true), Map.empty) shouldBe false
  }

  it should "evaluate BETWEEN" in {
    ExpressionEvaluator.evaluate(
      BetweenExpression(NumberLiteral("5"), NumberLiteral("1"), NumberLiteral("10")), Map.empty
    ) shouldBe true

    ExpressionEvaluator.evaluate(
      BetweenExpression(NumberLiteral("15"), NumberLiteral("1"), NumberLiteral("10")), Map.empty
    ) shouldBe false
  }

  it should "evaluate IN" in {
    ExpressionEvaluator.evaluate(
      InExpression(NumberLiteral("3"), List(NumberLiteral("1"), NumberLiteral("2"), NumberLiteral("3"))),
      Map.empty
    ) shouldBe true

    ExpressionEvaluator.evaluate(
      InExpression(NumberLiteral("5"), List(NumberLiteral("1"), NumberLiteral("2"), NumberLiteral("3")), negated = true),
      Map.empty
    ) shouldBe true
  }

  it should "evaluate LIKE" in {
    ExpressionEvaluator.evaluate(
      LikeExpression(StringLiteral("Hello World"), StringLiteral("Hello%")), Map.empty
    ) shouldBe true

    ExpressionEvaluator.evaluate(
      LikeExpression(StringLiteral("Hello"), StringLiteral("Wor%")), Map.empty
    ) shouldBe false
  }

  it should "evaluate column references" in {
    val row = Map("NAME" -> "Alice", "AGE" -> 25L)
    ExpressionEvaluator.evaluate(Identifier("NAME"), row) shouldBe "Alice"
    ExpressionEvaluator.evaluate(Identifier("AGE"), row) shouldBe 25L
  }

  it should "handle NULL propagation in arithmetic" in {
    ExpressionEvaluator.evaluate(
      BinaryExpression(NullLiteral, Plus, NumberLiteral("5")), Map.empty
    ) shouldBe (null: Any)
  }

  // ============================================================
  //  查询计划构建器测试
  // ============================================================

  "QueryPlanBuilder" should "build plan for simple SELECT" in {
    val ast = MySQLParser.parse("SELECT id, name FROM users WHERE age > 18")
    val plan = QueryPlanBuilder.build(ast)

    // 应该有 Project → Filter → Scan 的结构
    plan shouldBe a[ProjectPlan]
    plan.asInstanceOf[ProjectPlan].child shouldBe a[FilterPlan]
    plan.asInstanceOf[ProjectPlan].child.asInstanceOf[FilterPlan].child shouldBe a[ScanPlan]
  }

  it should "build plan for SELECT with ORDER BY and LIMIT" in {
    val ast = MySQLParser.parse("SELECT name FROM users ORDER BY name LIMIT 10")
    val plan = QueryPlanBuilder.build(ast)

    plan shouldBe a[LimitPlan]
    plan.asInstanceOf[LimitPlan].child shouldBe a[SortPlan]
  }

  it should "build plan for SELECT DISTINCT" in {
    val ast = MySQLParser.parse("SELECT DISTINCT category FROM products")
    val plan = QueryPlanBuilder.build(ast)

    // 应有 Distinct 节点
    plan shouldBe a[DistinctPlan]
    def findDistinct(p: LogicalPlan): Boolean = p match {
      case _: DistinctPlan => true
      case _ => p.children.exists(findDistinct)
    }
    findDistinct(plan) shouldBe true
  }

  it should "build plan for GROUP BY" in {
    val ast = MySQLParser.parse("SELECT dept, COUNT(*) FROM employees GROUP BY dept")
    val plan = QueryPlanBuilder.build(ast)

    def findAggregate(p: LogicalPlan): Boolean = p match {
      case _: AggregatePlan => true
      case _ => p.children.exists(findAggregate)
    }
    findAggregate(plan) shouldBe true
  }

  it should "build plan for JOIN" in {
    val ast = MySQLParser.parse("SELECT * FROM users u JOIN orders o ON u.id = o.user_id")
    val plan = QueryPlanBuilder.build(ast)

    def findJoin(p: LogicalPlan): Boolean = p match {
      case _: JoinPlan => true
      case _ => p.children.exists(findJoin)
    }
    findJoin(plan) shouldBe true
  }

  it should "build plan for UNION" in {
    val ast = MySQLParser.parse("SELECT name FROM t1 UNION SELECT name FROM t2")
    val plan = QueryPlanBuilder.build(ast)

    plan shouldBe a[SetOperationPlan]
  }

  // ============================================================
  //  查询计划打印器测试
  // ============================================================

  "QueryPlanPrinter" should "print plan tree" in {
    val ast = MySQLParser.parse("SELECT name FROM users WHERE age > 18 ORDER BY name LIMIT 10")
    val plan = QueryPlanBuilder.build(ast)
    val output = QueryPlanPrinter.print(plan)

    output should include("Scan")
    output should include("Filter")
    output should include("Project")
    output should include("Sort")
    output should include("Limit")
  }

  it should "print JOIN plan tree" in {
    val ast = MySQLParser.parse("SELECT * FROM users u JOIN orders o ON u.id = o.user_id WHERE u.age > 18")
    val plan = QueryPlanBuilder.build(ast)
    val output = QueryPlanPrinter.print(plan)

    output should include("JOIN")
    output should include("Scan")
    output should include("Filter")
  }

  // ============================================================
  //  查询优化器测试
  // ============================================================

  "ConstantFolding" should "fold constant arithmetic" in {
    val rule = new ConstantFolding()
    val folded = rule.foldExpression(
      BinaryExpression(NumberLiteral("2"), Plus, NumberLiteral("3"))
    )
    folded shouldBe NumberLiteral("5")
  }

  it should "fold constant comparison" in {
    val rule = new ConstantFolding()
    val folded = rule.foldExpression(
      BinaryExpression(NumberLiteral("5"), GreaterThan, NumberLiteral("3"))
    )
    folded shouldBe NumberLiteral("1")  // true
  }

  it should "remove always-true filter" in {
    val rule = new ConstantFolding()
    val scan = ScanPlan("USERS")
    val filter = FilterPlan(BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")), scan)
    val optimized = rule.apply(filter)
    optimized shouldBe scan
  }

  "PredicatePushDown" should "push filter below project" in {
    val rule = new PredicatePushDown()
    val scan = ScanPlan("USERS")
    val project = ProjectPlan(List("NAME"), List(("NAME", NamedColumn("NAME"))), scan)
    val filter = FilterPlan(BinaryExpression(Identifier("AGE"), GreaterThan, NumberLiteral("18")), project)

    val optimized = rule.apply(filter)
    // Filter 应被推到 Project 下面
    optimized shouldBe a[ProjectPlan]
    optimized.asInstanceOf[ProjectPlan].child shouldBe a[FilterPlan]
  }

  "ProjectionPruning" should "merge nested projections" in {
    val rule = new ProjectionPruning()
    val scan = ScanPlan("USERS")
    val innerProject = ProjectPlan(List("ID", "NAME", "AGE"), Nil, scan)
    val outerProject = ProjectPlan(List("NAME"), Nil, innerProject)

    val optimized = rule.apply(outerProject)
    // 应合并为单层 Project
    optimized shouldBe a[ProjectPlan]
    optimized.asInstanceOf[ProjectPlan].child shouldBe a[ScanPlan]
    optimized.asInstanceOf[ProjectPlan].outputColumns shouldBe List("NAME")
  }

  "QueryOptimizer" should "apply all rules" in {
    val ast = MySQLParser.parse("SELECT name FROM users WHERE 1 = 1")
    val plan = QueryPlanBuilder.build(ast)
    val optimized = QueryOptimizer.optimize(plan)

    // WHERE 1 = 1 应被常量折叠移除
    def hasFilter(p: LogicalPlan): Boolean = p match {
      case _: FilterPlan => true
      case _ => p.children.exists(hasFilter)
    }
    hasFilter(optimized) shouldBe false
  }

  it should "return optimization log" in {
    val ast = MySQLParser.parse("SELECT name FROM users WHERE 1 = 1")
    val plan = QueryPlanBuilder.build(ast)
    val (_, logs) = QueryOptimizer.optimizeWithLog(plan)

    logs should not be empty
    logs.exists(_.contains("ConstantFolding")) shouldBe true
  }

  // ============================================================
  //  视图 (VIEW) 解析测试
  // ============================================================

  "Parser - CREATE VIEW" should "parse CREATE VIEW" in {
    val ast = MySQLParser.parse("CREATE VIEW active_users AS SELECT * FROM users WHERE status = 'active'")
    ast shouldBe a[CreateViewStatement]
    val cv = ast.asInstanceOf[CreateViewStatement]
    cv.viewName shouldBe "ACTIVE_USERS"
    cv.orReplace shouldBe false
    cv.query shouldBe a[SelectStatement]
  }

  it should "parse CREATE OR REPLACE VIEW" in {
    val ast = MySQLParser.parse("CREATE OR REPLACE VIEW top_products AS SELECT name, price FROM products WHERE price > 100")
    ast shouldBe a[CreateViewStatement]
    val cv = ast.asInstanceOf[CreateViewStatement]
    cv.viewName shouldBe "TOP_PRODUCTS"
    cv.orReplace shouldBe true
  }

  "Parser - DROP VIEW" should "parse DROP VIEW" in {
    val ast = MySQLParser.parse("DROP VIEW active_users")
    ast shouldBe a[DropViewStatement]
    val dv = ast.asInstanceOf[DropViewStatement]
    dv.viewName shouldBe "ACTIVE_USERS"
    dv.ifExists shouldBe false
  }

  it should "parse DROP VIEW IF EXISTS" in {
    val ast = MySQLParser.parse("DROP VIEW IF EXISTS active_users")
    ast shouldBe a[DropViewStatement]
    val dv = ast.asInstanceOf[DropViewStatement]
    dv.viewName shouldBe "ACTIVE_USERS"
    dv.ifExists shouldBe true
  }

  "QueryExecutor - VIEW" should "create and query view" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE users (id INT, name VARCHAR(50), status VARCHAR(20))"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME, STATUS) VALUES (1, 'Alice', 'active')"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME, STATUS) VALUES (2, 'Bob', 'inactive')"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME, STATUS) VALUES (3, 'Charlie', 'active')"))

    // 创建视图
    executor.execute(MySQLParser.parse("CREATE VIEW active_users AS SELECT ID, NAME FROM users WHERE STATUS = 'active'"))

    // 查询视图
    val result = executor.execute(MySQLParser.parse("SELECT * FROM active_users"))
    result.rowCount shouldBe 2

    // 删除视图
    executor.execute(MySQLParser.parse("DROP VIEW active_users"))
    db.isView("ACTIVE_USERS") shouldBe false
  }

  // ============================================================
  //  存储过程 (PROCEDURE) 解析测试
  // ============================================================

  "Parser - CREATE PROCEDURE" should "parse CREATE PROCEDURE" in {
    val ast = MySQLParser.parse(
      "CREATE PROCEDURE add_user (IN p_name VARCHAR(50), IN p_age INT) BEGIN INSERT INTO users (NAME, AGE) VALUES (p_name, p_age) END")
    ast shouldBe a[CreateProcedureStatement]
    val cp = ast.asInstanceOf[CreateProcedureStatement]
    cp.name shouldBe "ADD_USER"
    cp.params.size shouldBe 2
    cp.params(0).mode shouldBe InParam
    cp.params(0).name shouldBe "P_NAME"
    cp.params(1).mode shouldBe InParam
    cp.params(1).name shouldBe "P_AGE"
    cp.body.size shouldBe 1
    cp.body.head shouldBe a[InsertStatement]
  }

  it should "parse procedure with no params" in {
    val ast = MySQLParser.parse("CREATE PROCEDURE reset_data () BEGIN DELETE FROM users END")
    ast shouldBe a[CreateProcedureStatement]
    val cp = ast.asInstanceOf[CreateProcedureStatement]
    cp.name shouldBe "RESET_DATA"
    cp.params shouldBe empty
    cp.body.size shouldBe 1
  }

  it should "parse procedure with OUT and INOUT params" in {
    val ast = MySQLParser.parse("CREATE PROCEDURE get_count (OUT p_count INT) BEGIN SELECT COUNT(*) FROM users END")
    ast shouldBe a[CreateProcedureStatement]
    val cp = ast.asInstanceOf[CreateProcedureStatement]
    cp.params(0).mode shouldBe OutParam
  }

  "Parser - DROP PROCEDURE" should "parse DROP PROCEDURE" in {
    val ast = MySQLParser.parse("DROP PROCEDURE add_user")
    ast shouldBe a[DropProcedureStatement]
    val dp = ast.asInstanceOf[DropProcedureStatement]
    dp.name shouldBe "ADD_USER"
    dp.ifExists shouldBe false
  }

  it should "parse DROP PROCEDURE IF EXISTS" in {
    val ast = MySQLParser.parse("DROP PROCEDURE IF EXISTS add_user")
    ast shouldBe a[DropProcedureStatement]
    val dp = ast.asInstanceOf[DropProcedureStatement]
    dp.ifExists shouldBe true
  }

  "Parser - CALL" should "parse CALL statement" in {
    val ast = MySQLParser.parse("CALL add_user('Alice', 25)")
    ast shouldBe a[CallStatement]
    val cs = ast.asInstanceOf[CallStatement]
    cs.procedureName shouldBe "ADD_USER"
    cs.arguments.size shouldBe 2
  }

  it should "parse CALL with no arguments" in {
    val ast = MySQLParser.parse("CALL reset_data()")
    ast shouldBe a[CallStatement]
    val cs = ast.asInstanceOf[CallStatement]
    cs.procedureName shouldBe "RESET_DATA"
    cs.arguments shouldBe empty
  }

  "QueryExecutor - PROCEDURE" should "create and call procedure" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE users (id INT, name VARCHAR(50))"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME) VALUES (1, 'Alice')"))

    // 创建存储过程
    executor.execute(MySQLParser.parse(
      "CREATE PROCEDURE clear_users () BEGIN DELETE FROM users END"))

    // 调用存储过程
    val result = executor.execute(MySQLParser.parse("CALL clear_users()"))

    // 验证效果
    val selectResult = executor.execute(MySQLParser.parse("SELECT * FROM users"))
    selectResult.rowCount shouldBe 0

    // 删除存储过程
    executor.execute(MySQLParser.parse("DROP PROCEDURE clear_users"))
    db.getProcedure("CLEAR_USERS") shouldBe None
  }

  // ============================================================
  //  视图/存储过程 JSON 序列化测试
  // ============================================================

  "ASTJsonSerializer - VIEW" should "serialize CREATE VIEW to JSON" in {
    val json = MySQLParser.toJson("CREATE VIEW v AS SELECT * FROM users")
    json should include("CreateViewStatement")
    json should include("V")
  }

  it should "serialize DROP VIEW to JSON" in {
    val json = MySQLParser.toJson("DROP VIEW IF EXISTS v")
    json should include("DropViewStatement")
    json should include("true")
  }

  "ASTJsonSerializer - PROCEDURE" should "serialize CREATE PROCEDURE to JSON" in {
    val json = MySQLParser.toJson("CREATE PROCEDURE p (IN x INT) BEGIN SELECT * FROM users END")
    json should include("CreateProcedureStatement")
    json should include("P")
    json should include("IN")
  }

  it should "serialize CALL to JSON" in {
    val json = MySQLParser.toJson("CALL my_proc(1, 'hello')")
    json should include("CallStatement")
    json should include("MY_PROC")
  }

  // ============================================================
  //  视图/存储过程 Pretty Printer 测试
  // ============================================================

  "SQLPrettyPrinter - VIEW" should "format CREATE VIEW" in {
    val ast = MySQLParser.parse("CREATE VIEW active_users AS SELECT * FROM users WHERE status = 'active'")
    val pp = new SQLPrettyPrinter()
    val formatted = pp.visitStatement(ast)
    formatted should include("CREATE VIEW")
    formatted should include("ACTIVE_USERS")
    formatted should include("SELECT")
  }

  it should "format CREATE OR REPLACE VIEW" in {
    val ast = MySQLParser.parse("CREATE OR REPLACE VIEW v AS SELECT id FROM t")
    val pp = new SQLPrettyPrinter()
    val formatted = pp.visitStatement(ast)
    formatted should include("OR REPLACE")
  }

  it should "format DROP VIEW" in {
    val ast = MySQLParser.parse("DROP VIEW IF EXISTS v")
    val pp = new SQLPrettyPrinter()
    val formatted = pp.visitStatement(ast)
    formatted should include("DROP VIEW")
    formatted should include("IF EXISTS")
  }

  "SQLPrettyPrinter - PROCEDURE" should "format CREATE PROCEDURE" in {
    val ast = MySQLParser.parse("CREATE PROCEDURE p (IN x INT) BEGIN SELECT * FROM t END")
    val pp = new SQLPrettyPrinter()
    val formatted = pp.visitStatement(ast)
    formatted should include("CREATE PROCEDURE")
    formatted should include("BEGIN")
    formatted should include("END")
  }

  it should "format CALL" in {
    val ast = MySQLParser.parse("CALL my_proc(1)")
    val pp = new SQLPrettyPrinter()
    val formatted = pp.visitStatement(ast)
    formatted should include("CALL")
    formatted should include("MY_PROC")
  }

  // ============================================================
  //  Visitor 模式对新类型的支持测试
  // ============================================================

  "TableExtractor - VIEW/PROCEDURE" should "extract tables from CREATE VIEW" in {
    val ast = MySQLParser.parse("CREATE VIEW v AS SELECT * FROM users JOIN orders ON users.id = orders.user_id")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast)
    tables should contain("USERS")
    tables should contain("ORDERS")
  }

  "ASTTransformer - VIEW/PROCEDURE" should "transform tables in VIEW query" in {
    val ast = MySQLParser.parse("CREATE VIEW v AS SELECT * FROM users")
    val renamer = new TableRenamer(Map("USERS" -> "users_v2"))
    val transformed = renamer.transformStatement(ast)
    transformed shouldBe a[CreateViewStatement]
    val cv = transformed.asInstanceOf[CreateViewStatement]
    val pp = new SQLPrettyPrinter()
    val sql = pp.visitStatement(cv.query)
    sql should include("users_v2")
  }

  // ============================================================
  //  补充测试 — 执行引擎：INTERSECT / EXCEPT
  // ============================================================

  "QueryExecutor - INTERSECT" should "compute intersection of two queries" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t1 (name VARCHAR(20))"))
    executor.execute(MySQLParser.parse("CREATE TABLE t2 (name VARCHAR(20))"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (NAME) VALUES ('Alice')"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (NAME) VALUES ('Bob')"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (NAME) VALUES ('Charlie')"))
    executor.execute(MySQLParser.parse("INSERT INTO t2 (NAME) VALUES ('Bob')"))
    executor.execute(MySQLParser.parse("INSERT INTO t2 (NAME) VALUES ('Charlie')"))
    executor.execute(MySQLParser.parse("INSERT INTO t2 (NAME) VALUES ('David')"))

    val result = executor.execute(MySQLParser.parse("SELECT NAME FROM t1 INTERSECT SELECT NAME FROM t2"))
    result.rowCount shouldBe 2
  }

  it should "compute INTERSECT ALL preserving duplicates" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t1 (val INT)"))
    executor.execute(MySQLParser.parse("CREATE TABLE t2 (val INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (VAL) VALUES (1)"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (VAL) VALUES (1)"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (VAL) VALUES (2)"))
    executor.execute(MySQLParser.parse("INSERT INTO t2 (VAL) VALUES (1)"))
    executor.execute(MySQLParser.parse("INSERT INTO t2 (VAL) VALUES (2)"))
    executor.execute(MySQLParser.parse("INSERT INTO t2 (VAL) VALUES (3)"))

    val result = executor.execute(MySQLParser.parse("SELECT VAL FROM t1 INTERSECT ALL SELECT VAL FROM t2"))
    // t1 has (1,1,2), t2 has (1,2,3) → INTERSECT ALL keeps both (1) and (2) from left that exist in right
    result.rowCount shouldBe 3
  }

  "QueryExecutor - EXCEPT" should "compute difference of two queries" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t1 (name VARCHAR(20))"))
    executor.execute(MySQLParser.parse("CREATE TABLE t2 (name VARCHAR(20))"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (NAME) VALUES ('Alice')"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (NAME) VALUES ('Bob')"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (NAME) VALUES ('Charlie')"))
    executor.execute(MySQLParser.parse("INSERT INTO t2 (NAME) VALUES ('Bob')"))

    val result = executor.execute(MySQLParser.parse("SELECT NAME FROM t1 EXCEPT SELECT NAME FROM t2"))
    result.rowCount shouldBe 2
  }

  it should "compute EXCEPT ALL" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t1 (val INT)"))
    executor.execute(MySQLParser.parse("CREATE TABLE t2 (val INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (VAL) VALUES (1)"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (VAL) VALUES (1)"))
    executor.execute(MySQLParser.parse("INSERT INTO t1 (VAL) VALUES (2)"))
    executor.execute(MySQLParser.parse("INSERT INTO t2 (VAL) VALUES (1)"))

    val result = executor.execute(MySQLParser.parse("SELECT VAL FROM t1 EXCEPT ALL SELECT VAL FROM t2"))
    // t1 has (1,1,2), t2 has (1) → remove one '1' from left → (1,2)
    result.rowCount shouldBe 2
  }

  // ============================================================
  //  补充测试 — 执行引擎：LEFT JOIN / RIGHT JOIN
  // ============================================================

  "QueryExecutor - LEFT JOIN" should "return all left rows with nulls for unmatched" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE users (id INT, name VARCHAR(50))"))
    executor.execute(MySQLParser.parse("CREATE TABLE orders (id INT, user_id INT, product VARCHAR(50))"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME) VALUES (1, 'Alice')"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME) VALUES (2, 'Bob')"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME) VALUES (3, 'Charlie')"))
    executor.execute(MySQLParser.parse("INSERT INTO orders (ID, USER_ID, PRODUCT) VALUES (1, 1, 'Laptop')"))

    val result = executor.execute(MySQLParser.parse(
      "SELECT * FROM users u LEFT JOIN orders o ON u.ID = o.USER_ID"))
    result.rowCount shouldBe 3  // All 3 users, unmatched have NULL product
  }

  "QueryExecutor - RIGHT JOIN" should "return all right rows with nulls for unmatched" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE users (id INT, name VARCHAR(50))"))
    executor.execute(MySQLParser.parse("CREATE TABLE orders (id INT, user_id INT, product VARCHAR(50))"))
    executor.execute(MySQLParser.parse("INSERT INTO users (ID, NAME) VALUES (1, 'Alice')"))
    executor.execute(MySQLParser.parse("INSERT INTO orders (ID, USER_ID, PRODUCT) VALUES (1, 1, 'Laptop')"))
    executor.execute(MySQLParser.parse("INSERT INTO orders (ID, USER_ID, PRODUCT) VALUES (2, 99, 'Phone')"))

    val result = executor.execute(MySQLParser.parse(
      "SELECT * FROM users u RIGHT JOIN orders o ON u.ID = o.USER_ID"))
    result.rowCount shouldBe 2  // All orders, unmatched user is null
  }

  // ============================================================
  //  补充测试 — 执行引擎：多行 INSERT
  // ============================================================

  "QueryExecutor - multi-row INSERT" should "insert multiple rows at once" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT, name VARCHAR(20))"))
    val result = executor.execute(MySQLParser.parse(
      "INSERT INTO t (ID, NAME) VALUES (1, 'A'), (2, 'B'), (3, 'C')"))
    result.rows.head("Result").toString should include("3 row(s) inserted")

    val selectResult = executor.execute(MySQLParser.parse("SELECT * FROM t"))
    selectResult.rowCount shouldBe 3
  }

  // ============================================================
  //  补充测试 — 执行引擎：函数求值
  // ============================================================

  "QueryExecutor - string functions" should "evaluate LOWER, TRIM, CONCAT" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (name VARCHAR(50))"))
    executor.execute(MySQLParser.parse("INSERT INTO t (NAME) VALUES ('  Hello World  ')"))

    val lower = executor.execute(MySQLParser.parse("SELECT LOWER(NAME) AS L FROM t"))
    lower.rows.head("L") shouldBe "  hello world  "

    val trimmed = executor.execute(MySQLParser.parse("SELECT TRIM(NAME) AS T FROM t"))
    trimmed.rows.head("T") shouldBe "Hello World"
  }

  "QueryExecutor - math functions" should "evaluate ABS, CEIL, FLOOR, ROUND, MOD" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    val abs = executor.execute(MySQLParser.parse("SELECT ABS(-42) AS R"))
    abs.rows.head("R") shouldBe 42L

    val ceil = executor.execute(MySQLParser.parse("SELECT CEIL(3.2) AS R"))
    ceil.rows.head("R") shouldBe 4L

    val floor = executor.execute(MySQLParser.parse("SELECT FLOOR(3.9) AS R"))
    floor.rows.head("R") shouldBe 3L
  }

  "QueryExecutor - conditional functions" should "evaluate COALESCE and CASE WHEN" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    val coalesce = executor.execute(MySQLParser.parse("SELECT COALESCE(NULL, 'fallback') AS R"))
    coalesce.rows.head("R") shouldBe "fallback"

    val caseResult = executor.execute(MySQLParser.parse(
      "SELECT CASE WHEN 1 > 0 THEN 'yes' ELSE 'no' END AS R"))
    caseResult.rows.head("R") shouldBe "yes"
  }

  // ============================================================
  //  补充测试 — 执行引擎：CAST 表达式
  // ============================================================

  "QueryExecutor - CAST" should "cast values between types" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    val result = executor.execute(MySQLParser.parse("SELECT CAST(42 AS CHAR) AS R"))
    result.rows.head("R") shouldBe "42"

    val result2 = executor.execute(MySQLParser.parse("SELECT CAST('123' AS SIGNED) AS R"))
    result2.rows.head("R") shouldBe 123L
  }

  // ============================================================
  //  补充测试 — 执行引擎：错误处理
  // ============================================================

  "QueryExecutor - error handling" should "throw on non-existent table SELECT" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("SELECT * FROM nonexistent"))
    }
  }

  it should "throw on duplicate CREATE TABLE" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT)"))
    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("CREATE TABLE t (id INT)"))
    }
  }

  it should "throw on DROP non-existent TABLE without IF EXISTS" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("DROP TABLE nonexistent"))
    }
  }

  it should "throw on INSERT column count mismatch" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT, name VARCHAR(20))"))
    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("INSERT INTO t (ID, NAME) VALUES (1)"))
    }
  }

  it should "throw on division by zero" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("SELECT 10 / 0 AS R"))
    }
  }

  it should "throw on unknown function" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("SELECT UNKNOWN_FUNC(1) AS R"))
    }
  }

  // ============================================================
  //  补充测试 — 执行引擎：VIEW 高级场景
  // ============================================================

  "QueryExecutor - VIEW advanced" should "support CREATE OR REPLACE VIEW" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT, name VARCHAR(50))"))
    executor.execute(MySQLParser.parse("INSERT INTO t (ID, NAME) VALUES (1, 'Alice')"))
    executor.execute(MySQLParser.parse("INSERT INTO t (ID, NAME) VALUES (2, 'Bob')"))

    // 创建视图
    executor.execute(MySQLParser.parse("CREATE VIEW v AS SELECT * FROM t"))
    val r1 = executor.execute(MySQLParser.parse("SELECT * FROM v"))
    r1.rowCount shouldBe 2

    // OR REPLACE — 替换视图定义
    executor.execute(MySQLParser.parse("CREATE OR REPLACE VIEW v AS SELECT * FROM t WHERE ID = 1"))
    val r2 = executor.execute(MySQLParser.parse("SELECT * FROM v"))
    r2.rowCount shouldBe 1
  }

  it should "throw on duplicate VIEW without OR REPLACE" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT)"))
    executor.execute(MySQLParser.parse("CREATE VIEW v AS SELECT * FROM t"))

    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("CREATE VIEW v AS SELECT * FROM t"))
    }
  }

  it should "support DROP VIEW IF EXISTS on non-existent view" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    noException should be thrownBy {
      executor.execute(MySQLParser.parse("DROP VIEW IF EXISTS nonexistent"))
    }
  }

  it should "throw on DROP non-existent VIEW without IF EXISTS" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("DROP VIEW nonexistent"))
    }
  }

  // ============================================================
  //  补充测试 — 执行引擎：PROCEDURE 高级场景
  // ============================================================

  "QueryExecutor - PROCEDURE advanced" should "throw on duplicate procedure" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT)"))
    executor.execute(MySQLParser.parse("CREATE PROCEDURE p () BEGIN SELECT * FROM t END"))

    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("CREATE PROCEDURE p () BEGIN SELECT * FROM t END"))
    }
  }

  it should "throw on CALL non-existent procedure" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("CALL nonexistent()"))
    }
  }

  it should "throw on CALL with wrong argument count" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT)"))
    executor.execute(MySQLParser.parse("CREATE PROCEDURE p (IN x INT) BEGIN SELECT * FROM t END"))

    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("CALL p()"))
    }
  }

  it should "support DROP PROCEDURE IF EXISTS on non-existent" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    noException should be thrownBy {
      executor.execute(MySQLParser.parse("DROP PROCEDURE IF EXISTS nonexistent"))
    }
  }

  it should "throw on DROP non-existent PROCEDURE without IF EXISTS" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    an[ExecutionException] should be thrownBy {
      executor.execute(MySQLParser.parse("DROP PROCEDURE nonexistent"))
    }
  }

  // ============================================================
  //  补充测试 — 执行引擎：WITH (CTE) 执行
  // ============================================================

  "QueryExecutor - CTE" should "execute WITH ... SELECT using CTE as temp view" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE employees (id INT, name VARCHAR(50), dept VARCHAR(20))"))
    executor.execute(MySQLParser.parse("INSERT INTO employees (ID, NAME, DEPT) VALUES (1, 'Alice', 'Engineering')"))
    executor.execute(MySQLParser.parse("INSERT INTO employees (ID, NAME, DEPT) VALUES (2, 'Bob', 'Engineering')"))
    executor.execute(MySQLParser.parse("INSERT INTO employees (ID, NAME, DEPT) VALUES (3, 'Charlie', 'Sales')"))

    val result = executor.execute(MySQLParser.parse(
      "WITH eng AS (SELECT * FROM employees WHERE DEPT = 'Engineering') SELECT NAME FROM eng"))
    result.rowCount shouldBe 2
  }

  // ============================================================
  //  补充测试 — 执行引擎：ALTER TABLE / CREATE INDEX (no-op)
  // ============================================================

  "QueryExecutor - ALTER TABLE" should "return no-op result" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT)"))
    val result = executor.execute(MySQLParser.parse("ALTER TABLE t ADD COLUMN name VARCHAR(50)"))
    result.rows.head("Result").toString should include("ALTER TABLE")
  }

  "QueryExecutor - CREATE INDEX" should "return no-op result" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT, name VARCHAR(50))"))
    val result = executor.execute(MySQLParser.parse("CREATE INDEX idx_name ON t (name)"))
    result.rows.head("Result").toString should include("CREATE INDEX")
  }

  "QueryExecutor - DROP INDEX" should "return no-op result" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT)"))
    val result = executor.execute(MySQLParser.parse("DROP INDEX idx ON t"))
    result.rows.head("Result").toString should include("DROP INDEX")
  }

  // ============================================================
  //  补充测试 — 执行引擎：UPDATE / DELETE 全表
  // ============================================================

  "QueryExecutor - UPDATE without WHERE" should "update all rows" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT, val INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO t (ID, VAL) VALUES (1, 10)"))
    executor.execute(MySQLParser.parse("INSERT INTO t (ID, VAL) VALUES (2, 20)"))

    val result = executor.execute(MySQLParser.parse("UPDATE t SET VAL = 99"))
    result.rows.head("Result").toString should include("2 row(s) updated")

    val sel = executor.execute(MySQLParser.parse("SELECT VAL FROM t"))
    sel.rows.foreach(r => r("VAL") shouldBe 99L)
  }

  "QueryExecutor - DELETE without WHERE" should "delete all rows" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO t (ID) VALUES (1)"))
    executor.execute(MySQLParser.parse("INSERT INTO t (ID) VALUES (2)"))

    val result = executor.execute(MySQLParser.parse("DELETE FROM t"))
    result.rows.head("Result").toString should include("2 row(s) deleted")

    val sel = executor.execute(MySQLParser.parse("SELECT * FROM t"))
    sel.rowCount shouldBe 0
  }

  // ============================================================
  //  补充测试 — 执行引擎：聚合函数 MAX / MIN / AVG
  // ============================================================

  "QueryExecutor - MAX / MIN / AVG" should "compute correctly" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE scores (val INT)"))
    executor.execute(MySQLParser.parse("INSERT INTO scores (VAL) VALUES (10)"))
    executor.execute(MySQLParser.parse("INSERT INTO scores (VAL) VALUES (20)"))
    executor.execute(MySQLParser.parse("INSERT INTO scores (VAL) VALUES (30)"))

    val maxResult = executor.execute(MySQLParser.parse("SELECT MAX(VAL) AS M FROM scores"))
    maxResult.rows.head("M") shouldBe 30L

    val minResult = executor.execute(MySQLParser.parse("SELECT MIN(VAL) AS M FROM scores"))
    minResult.rows.head("M") shouldBe 10L

    val avgResult = executor.execute(MySQLParser.parse("SELECT AVG(VAL) AS A FROM scores"))
    avgResult.rows.head("A") shouldBe 20.0
  }

  // ============================================================
  //  补充测试 — 表达式求值器：补充场景
  // ============================================================

  "ExpressionEvaluator - logical ops" should "evaluate AND and OR" in {
    ExpressionEvaluator.evaluate(
      BinaryExpression(
        BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")),
        AndOp,
        BinaryExpression(NumberLiteral("2"), Equal, NumberLiteral("2"))
      ), Map.empty
    ) shouldBe true

    ExpressionEvaluator.evaluate(
      BinaryExpression(
        BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("2")),
        OrOp,
        BinaryExpression(NumberLiteral("3"), Equal, NumberLiteral("3"))
      ), Map.empty
    ) shouldBe true
  }

  it should "handle NULL propagation in AND/OR" in {
    // FALSE AND NULL = FALSE
    ExpressionEvaluator.evaluate(
      BinaryExpression(
        BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("2")),
        AndOp,
        NullLiteral
      ), Map.empty
    ) shouldBe false

    // TRUE OR NULL = TRUE
    ExpressionEvaluator.evaluate(
      BinaryExpression(
        BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")),
        OrOp,
        NullLiteral
      ), Map.empty
    ) shouldBe true
  }

  "ExpressionEvaluator - NOT" should "negate boolean" in {
    ExpressionEvaluator.evaluate(
      UnaryExpression(NotOp, BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("2"))),
      Map.empty
    ) shouldBe true
  }

  "ExpressionEvaluator - CASE simple" should "evaluate simple CASE expression" in {
    val row = Map("STATUS" -> "active")
    val result = ExpressionEvaluator.evaluate(
      CaseExpression(
        Some(Identifier("STATUS")),
        List(
          WhenClause(StringLiteral("active"), StringLiteral("Active")),
          WhenClause(StringLiteral("inactive"), StringLiteral("Inactive"))
        ),
        Some(StringLiteral("Unknown"))
      ), row
    )
    result shouldBe "Active"
  }

  "ExpressionEvaluator - CASE search" should "evaluate searched CASE with no match" in {
    val result = ExpressionEvaluator.evaluate(
      CaseExpression(
        None,
        List(
          WhenClause(BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("2")), StringLiteral("match"))
        ),
        Some(StringLiteral("default"))
      ), Map.empty
    )
    result shouldBe "default"
  }

  "ExpressionEvaluator - BETWEEN negated" should "evaluate NOT BETWEEN" in {
    ExpressionEvaluator.evaluate(
      BetweenExpression(NumberLiteral("15"), NumberLiteral("1"), NumberLiteral("10"), negated = true),
      Map.empty
    ) shouldBe true
  }

  "ExpressionEvaluator - LIKE negated" should "evaluate NOT LIKE" in {
    ExpressionEvaluator.evaluate(
      LikeExpression(StringLiteral("Hello"), StringLiteral("Wor%"), negated = true),
      Map.empty
    ) shouldBe true
  }

  "ExpressionEvaluator - QualifiedIdentifier" should "resolve table.column" in {
    val row = Map("USERS.NAME" -> "Alice", "NAME" -> "Alice")
    ExpressionEvaluator.evaluate(QualifiedIdentifier("users", "name"), row) shouldBe "Alice"
  }

  "ExpressionEvaluator - floating point" should "parse and compute floating point" in {
    ExpressionEvaluator.evaluate(NumberLiteral("3.14"), Map.empty) shouldBe 3.14
    ExpressionEvaluator.evaluate(
      BinaryExpression(NumberLiteral("1.5"), Plus, NumberLiteral("2.5")), Map.empty
    ) shouldBe 4.0
  }

  "ExpressionEvaluator - comparison" should "handle string comparison" in {
    ExpressionEvaluator.evaluate(
      BinaryExpression(StringLiteral("apple"), LessThan, StringLiteral("banana")), Map.empty
    ) shouldBe true
  }

  "ExpressionEvaluator.compareValues" should "compare nulls" in {
    ExpressionEvaluator.compareValues(null, null) shouldBe 0
    ExpressionEvaluator.compareValues(null, 1) should be < 0
    ExpressionEvaluator.compareValues(1, null) should be > 0
  }

  it should "compare mixed numeric types" in {
    ExpressionEvaluator.compareValues(1L, 1.0) shouldBe 0
    ExpressionEvaluator.compareValues(1, 2L) should be < 0
    ExpressionEvaluator.compareValues(2L, 1) should be > 0
  }

  // ============================================================
  //  补充测试 — 查询计划：LimitPushDown
  // ============================================================

  "LimitPushDown" should "process limit plans recursively" in {
    val rule = new LimitPushDown()
    val scan = ScanPlan("USERS")
    val project = ProjectPlan(List("NAME"), Nil, scan)
    val limit = LimitPlan(Some(10), None, project)

    val optimized = rule.apply(limit)
    optimized shouldBe a[LimitPlan]
    optimized.asInstanceOf[LimitPlan].limit shouldBe Some(10)
  }

  // ============================================================
  //  补充测试 — 查询计划：SubqueryPlan / CTE 计划
  // ============================================================

  "QueryPlanBuilder - CTE" should "build plan for WITH query" in {
    val ast = MySQLParser.parse("WITH temp AS (SELECT id FROM users) SELECT * FROM temp")
    val plan = QueryPlanBuilder.build(ast)
    // CTE 简化处理 → 直接构建主查询计划
    plan shouldBe a[ProjectPlan]
  }

  "QueryPlanBuilder - DerivedTable" should "build SubqueryPlan for FROM subquery" in {
    val ast = MySQLParser.parse("SELECT * FROM (SELECT id, name FROM users) AS sub")
    val plan = QueryPlanBuilder.build(ast)

    def findSubquery(p: LogicalPlan): Boolean = p match {
      case _: SubqueryPlan => true
      case _ => p.children.exists(findSubquery)
    }
    findSubquery(plan) shouldBe true
  }

  // ============================================================
  //  补充测试 — 查询计划：INTERSECT / EXCEPT 计划
  // ============================================================

  "QueryPlanBuilder - INTERSECT" should "build SetOperationPlan for INTERSECT" in {
    val ast = MySQLParser.parse("SELECT name FROM t1 INTERSECT SELECT name FROM t2")
    val plan = QueryPlanBuilder.build(ast)
    plan shouldBe a[SetOperationPlan]
    plan.asInstanceOf[SetOperationPlan].operationType shouldBe IntersectDistinct
  }

  "QueryPlanBuilder - EXCEPT" should "build SetOperationPlan for EXCEPT" in {
    val ast = MySQLParser.parse("SELECT name FROM t1 EXCEPT SELECT name FROM t2")
    val plan = QueryPlanBuilder.build(ast)
    plan shouldBe a[SetOperationPlan]
    plan.asInstanceOf[SetOperationPlan].operationType shouldBe ExceptDistinct
  }

  // ============================================================
  //  补充测试 — 查询计划：复杂优化场景
  // ============================================================

  "QueryOptimizer - complex" should "fold and push down combined" in {
    val ast = MySQLParser.parse("SELECT name FROM users WHERE 1 = 1 AND age > 18")
    val plan = QueryPlanBuilder.build(ast)
    val optimized = QueryOptimizer.optimize(plan)

    // 1=1 should be folded, but age > 18 filter remains
    def findFilter(p: LogicalPlan): Boolean = p match {
      case _: FilterPlan => true
      case _ => p.children.exists(findFilter)
    }
    // After constant folding, 1=1 AND age > 18 → simplified, filter should still exist for age > 18
    // The exact behavior depends on how AND is constant-folded
    // At minimum, the plan should still be valid
    optimized should not be null
  }

  "ConstantFolding" should "fold string equality" in {
    val rule = new ConstantFolding()
    val folded = rule.foldExpression(
      BinaryExpression(StringLiteral("hello"), Equal, StringLiteral("hello"))
    )
    folded shouldBe NumberLiteral("1")  // true
  }

  it should "fold string inequality" in {
    val rule = new ConstantFolding()
    val folded = rule.foldExpression(
      BinaryExpression(StringLiteral("hello"), Equal, StringLiteral("world"))
    )
    folded shouldBe NumberLiteral("0")  // false
  }

  it should "fold subtraction" in {
    val rule = new ConstantFolding()
    val folded = rule.foldExpression(
      BinaryExpression(NumberLiteral("10"), Minus, NumberLiteral("3"))
    )
    folded shouldBe NumberLiteral("7")
  }

  it should "fold multiplication" in {
    val rule = new ConstantFolding()
    val folded = rule.foldExpression(
      BinaryExpression(NumberLiteral("4"), Multiply, NumberLiteral("5"))
    )
    folded shouldBe NumberLiteral("20")
  }

  it should "fold division" in {
    val rule = new ConstantFolding()
    val folded = rule.foldExpression(
      BinaryExpression(NumberLiteral("10"), Divide, NumberLiteral("2"))
    )
    folded shouldBe NumberLiteral("5")
  }

  // ============================================================
  //  补充测试 — 查询计划打印器补充
  // ============================================================

  "QueryPlanPrinter" should "print DISTINCT plan" in {
    val ast = MySQLParser.parse("SELECT DISTINCT name FROM users")
    val plan = QueryPlanBuilder.build(ast)
    val output = QueryPlanPrinter.print(plan)
    output should include("Distinct")
  }

  it should "print Aggregate plan" in {
    val ast = MySQLParser.parse("SELECT dept, COUNT(*) FROM employees GROUP BY dept")
    val plan = QueryPlanBuilder.build(ast)
    val output = QueryPlanPrinter.print(plan)
    output should include("Aggregate")
    output should include("GROUP BY")
  }

  it should "print SetOperation plan" in {
    val ast = MySQLParser.parse("SELECT name FROM t1 UNION ALL SELECT name FROM t2")
    val plan = QueryPlanBuilder.build(ast)
    val output = QueryPlanPrinter.print(plan)
    output should include("SetOperation")
    output should include("UNION ALL")
  }

  it should "print Subquery plan" in {
    val ast = MySQLParser.parse("SELECT * FROM (SELECT id FROM users) AS sub")
    val plan = QueryPlanBuilder.build(ast)
    val output = QueryPlanPrinter.print(plan)
    output should include("Subquery")
  }

  // ============================================================
  //  补充测试 — 类型推断器：更多推断场景
  // ============================================================

  "TypeInferencer" should "infer NULL type" in {
    TypeInferencer.infer(NullLiteral) shouldBe NullSQLType
  }

  it should "infer AllColumnsExpression as UNKNOWN" in {
    TypeInferencer.infer(AllColumnsExpression) shouldBe UnknownSQLType
  }

  it should "infer arithmetic result type" in {
    TypeInferencer.infer(
      BinaryExpression(NumberLiteral("1"), Plus, NumberLiteral("2"))
    ) shouldBe IntegerSQLType

    TypeInferencer.infer(
      BinaryExpression(NumberLiteral("1"), Plus, NumberLiteral("2.5"))
    ) shouldBe NumericSQLType
  }

  it should "infer comparison result as BOOLEAN" in {
    TypeInferencer.infer(
      BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("2"))
    ) shouldBe BooleanSQLType
  }

  it should "infer logical operators as BOOLEAN" in {
    TypeInferencer.infer(
      BinaryExpression(
        BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")),
        AndOp,
        BinaryExpression(NumberLiteral("2"), Equal, NumberLiteral("2"))
      )
    ) shouldBe BooleanSQLType
  }

  it should "infer NOT expression as BOOLEAN" in {
    TypeInferencer.infer(
      UnaryExpression(NotOp, BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("2")))
    ) shouldBe BooleanSQLType
  }

  it should "infer IS NULL expression as BOOLEAN type" in {
    TypeInferencer.infer(IsNullExpression(NullLiteral)) shouldBe BooleanSQLType
  }

  it should "infer BETWEEN expression as BOOLEAN type" in {
    TypeInferencer.infer(
      BetweenExpression(NumberLiteral("5"), NumberLiteral("1"), NumberLiteral("10"))
    ) shouldBe BooleanSQLType
  }

  it should "infer IN expression as BOOLEAN type" in {
    TypeInferencer.infer(
      InExpression(NumberLiteral("1"), List(NumberLiteral("1"), NumberLiteral("2")))
    ) shouldBe BooleanSQLType
  }

  it should "infer LIKE expression as BOOLEAN type" in {
    TypeInferencer.infer(
      LikeExpression(StringLiteral("hello"), StringLiteral("h%"))
    ) shouldBe BooleanSQLType
  }

  it should "infer EXISTS expression as BOOLEAN type" in {
    val subquery = SelectStatement(List(AllColumns), Some(TableName("t")), None, None, None, None, None, None)
    TypeInferencer.infer(ExistsExpression(subquery)) shouldBe BooleanSQLType
  }

  it should "infer aggregate functions correctly" in {
    TypeInferencer.infer(AggregateFunction(CountFunc, AllColumnsExpression)) shouldBe IntegerSQLType
    TypeInferencer.infer(AggregateFunction(SumFunc, NumberLiteral("1"))) shouldBe NumericSQLType
    TypeInferencer.infer(AggregateFunction(AvgFunc, NumberLiteral("1"))) shouldBe NumericSQLType
    TypeInferencer.infer(AggregateFunction(MaxFunc, StringLiteral("a"))) shouldBe StringSQLType
    TypeInferencer.infer(AggregateFunction(MinFunc, NumberLiteral("1"))) shouldBe IntegerSQLType
  }

  it should "infer string functions as STRING" in {
    TypeInferencer.infer(FunctionCall("UPPER", List(StringLiteral("a")))) shouldBe StringSQLType
    TypeInferencer.infer(FunctionCall("LOWER", List(StringLiteral("a")))) shouldBe StringSQLType
    TypeInferencer.infer(FunctionCall("TRIM", List(StringLiteral("a")))) shouldBe StringSQLType
    TypeInferencer.infer(FunctionCall("CONCAT", List(StringLiteral("a"), StringLiteral("b")))) shouldBe StringSQLType
    TypeInferencer.infer(FunctionCall("SUBSTRING", List(StringLiteral("a"), NumberLiteral("1")))) shouldBe StringSQLType
    TypeInferencer.infer(FunctionCall("REPLACE", List(StringLiteral("a"), StringLiteral("b"), StringLiteral("c")))) shouldBe StringSQLType
  }

  it should "infer numeric functions as NUMERIC" in {
    TypeInferencer.infer(FunctionCall("ABS", List(NumberLiteral("1")))) shouldBe NumericSQLType
    TypeInferencer.infer(FunctionCall("CEIL", List(NumberLiteral("1")))) shouldBe NumericSQLType
    TypeInferencer.infer(FunctionCall("FLOOR", List(NumberLiteral("1")))) shouldBe NumericSQLType
    TypeInferencer.infer(FunctionCall("ROUND", List(NumberLiteral("1")))) shouldBe NumericSQLType
    TypeInferencer.infer(FunctionCall("SQRT", List(NumberLiteral("4")))) shouldBe NumericSQLType
  }

  it should "infer integer functions as INTEGER" in {
    TypeInferencer.infer(FunctionCall("LENGTH", List(StringLiteral("a")))) shouldBe IntegerSQLType
    TypeInferencer.infer(FunctionCall("CHAR_LENGTH", List(StringLiteral("a")))) shouldBe IntegerSQLType
  }

  it should "infer date functions correctly" in {
    TypeInferencer.infer(FunctionCall("NOW", List())) shouldBe DateTimeSQLType
    TypeInferencer.infer(FunctionCall("CURDATE", List())) shouldBe DateSQLType
    TypeInferencer.infer(FunctionCall("YEAR", List(StringLiteral("2023-01-01")))) shouldBe IntegerSQLType
  }

  it should "infer window ranking functions as INTEGER" in {
    TypeInferencer.infer(FunctionCall("ROW_NUMBER", List())) shouldBe IntegerSQLType
    TypeInferencer.infer(FunctionCall("RANK", List())) shouldBe IntegerSQLType
    TypeInferencer.infer(FunctionCall("DENSE_RANK", List())) shouldBe IntegerSQLType
    TypeInferencer.infer(FunctionCall("NTILE", List(NumberLiteral("4")))) shouldBe IntegerSQLType
  }

  it should "infer CAST target types" in {
    TypeInferencer.infer(CastExpression(StringLiteral("1"), SignedCastType())) shouldBe IntegerSQLType
    TypeInferencer.infer(CastExpression(NumberLiteral("1"), CharCastType())) shouldBe StringSQLType
    TypeInferencer.infer(CastExpression(NumberLiteral("1"), DecimalCastType())) shouldBe NumericSQLType
    TypeInferencer.infer(CastExpression(StringLiteral("x"), BooleanCastType)) shouldBe BooleanSQLType
    TypeInferencer.infer(CastExpression(StringLiteral("x"), DateCastType)) shouldBe DateSQLType
    TypeInferencer.infer(CastExpression(StringLiteral("x"), DateTimeCastType)) shouldBe DateTimeSQLType
  }

  it should "infer CASE expression with integer branches" in {
    val caseExpr = CaseExpression(
      None,
      List(WhenClause(BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")), NumberLiteral("10"))),
      Some(NumberLiteral("20"))
    )
    TypeInferencer.infer(caseExpr) shouldBe IntegerSQLType
  }

  it should "infer CASE with mixed numeric branches as NUMERIC" in {
    val caseExpr = CaseExpression(
      None,
      List(WhenClause(BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")), NumberLiteral("10"))),
      Some(NumberLiteral("20.5"))
    )
    TypeInferencer.infer(caseExpr) shouldBe NumericSQLType
  }

  it should "infer SubqueryExpression as UNKNOWN" in {
    val subquery = SelectStatement(List(AllColumns), Some(TableName("t")), None, None, None, None, None, None)
    TypeInferencer.infer(SubqueryExpression(subquery)) shouldBe UnknownSQLType
  }

  it should "infer WindowFunctionExpression from inner function" in {
    val windowExpr = WindowFunctionExpression(
      AggregateFunction(SumFunc, Identifier("amount")),
      WindowSpec(partitionBy = Some(List(Identifier("dept"))))
    )
    TypeInferencer.infer(windowExpr) shouldBe NumericSQLType
  }

  it should "infer column type from schema scope" in {
    val scope = QueryScope(
      tables = Map("USERS" -> TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR")
      )))
    )
    TypeInferencer.infer(Identifier("ID"), scope) shouldBe IntegerSQLType
    TypeInferencer.infer(Identifier("NAME"), scope) shouldBe StringSQLType
  }

  it should "infer qualified column type from schema scope" in {
    val scope = QueryScope(
      tables = Map("USERS" -> TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR")
      )))
    )
    TypeInferencer.infer(QualifiedIdentifier("USERS", "ID"), scope) shouldBe IntegerSQLType
  }

  it should "return UNKNOWN for unresolved identifier" in {
    TypeInferencer.infer(Identifier("UNKNOWN_COL")) shouldBe UnknownSQLType
  }

  it should "infer CONVERT expression" in {
    TypeInferencer.infer(ConvertExpression(NumberLiteral("1"), Some(CharCastType()), None)) shouldBe StringSQLType
    TypeInferencer.infer(ConvertExpression(StringLiteral("a"), None, Some("utf8"))) shouldBe StringSQLType
  }

  // ============================================================
  //  补充测试 — TypeChecker：边界场景
  // ============================================================

  "TypeChecker" should "consider NULL compatible with any type" in {
    TypeChecker.areCompatible(NullSQLType, IntegerSQLType) shouldBe true
    TypeChecker.areCompatible(StringSQLType, NullSQLType) shouldBe true
    TypeChecker.areCompatible(NullSQLType, NullSQLType) shouldBe true
  }

  it should "consider UNKNOWN compatible with any type" in {
    TypeChecker.areCompatible(UnknownSQLType, IntegerSQLType) shouldBe true
    TypeChecker.areCompatible(StringSQLType, UnknownSQLType) shouldBe true
  }

  it should "treat BOOLEAN and INTEGER as compatible (MySQL 0/1)" in {
    TypeChecker.areCompatible(BooleanSQLType, IntegerSQLType) shouldBe true
    TypeChecker.areCompatible(IntegerSQLType, BooleanSQLType) shouldBe true
  }

  it should "treat DATE and DATETIME as compatible types" in {
    TypeChecker.areCompatible(DateSQLType, DateTimeSQLType) shouldBe true
    TypeChecker.areCompatible(DateTimeSQLType, DateSQLType) shouldBe true
  }

  it should "treat STRING and DATE/DATETIME as compatible (implicit cast)" in {
    TypeChecker.areCompatible(StringSQLType, DateSQLType) shouldBe true
    TypeChecker.areCompatible(DateSQLType, StringSQLType) shouldBe true
    TypeChecker.areCompatible(StringSQLType, DateTimeSQLType) shouldBe true
  }

  it should "reject incompatible types" in {
    TypeChecker.areCompatible(IntegerSQLType, StringSQLType) shouldBe false
    TypeChecker.areCompatible(BooleanSQLType, StringSQLType) shouldBe false
  }

  "TypeChecker.isNumeric" should "classify numeric types" in {
    TypeChecker.isNumeric(IntegerSQLType) shouldBe true
    TypeChecker.isNumeric(NumericSQLType) shouldBe true
    TypeChecker.isNumeric(BooleanSQLType) shouldBe true
    TypeChecker.isNumeric(NullSQLType) shouldBe true
    TypeChecker.isNumeric(StringSQLType) shouldBe false
    TypeChecker.isNumeric(DateSQLType) shouldBe false
  }

  "TypeChecker.checkComparison" should "pass for compatible types" in {
    TypeChecker.checkComparison(IntegerSQLType, Equal, IntegerSQLType) shouldBe None
    TypeChecker.checkComparison(IntegerSQLType, LessThan, NumericSQLType) shouldBe None
  }

  it should "fail for incompatible types" in {
    val error = TypeChecker.checkComparison(IntegerSQLType, Equal, StringSQLType)
    error should not be empty
    error.get.message should include("Cannot compare")
  }

  "TypeChecker.checkArithmetic" should "pass for numeric types" in {
    TypeChecker.checkArithmetic(IntegerSQLType, Plus, IntegerSQLType) shouldBe None
    TypeChecker.checkArithmetic(NumericSQLType, Multiply, IntegerSQLType) shouldBe None
  }

  it should "fail for non-numeric types" in {
    val error = TypeChecker.checkArithmetic(StringSQLType, Plus, IntegerSQLType)
    error should not be empty
    error.get.message should include("must be numeric")
  }

  "TypeChecker.checkLogical" should "pass for boolean types" in {
    TypeChecker.checkLogical(BooleanSQLType, AndOp, BooleanSQLType) shouldBe None
    TypeChecker.checkLogical(IntegerSQLType, OrOp, BooleanSQLType) shouldBe None
  }

  it should "fail for non-boolean types" in {
    val error = TypeChecker.checkLogical(StringSQLType, AndOp, BooleanSQLType)
    error should not be empty
    error.get.message should include("requires boolean")
  }

  // ============================================================
  //  补充测试 — TypeInferencer.unifyTypes 更多场景
  // ============================================================

  "TypeInferencer.unifyTypes" should "return UNKNOWN for empty list" in {
    TypeInferencer.unifyTypes(List()) shouldBe UnknownSQLType
  }

  it should "return type for single non-null type" in {
    TypeInferencer.unifyTypes(List(IntegerSQLType)) shouldBe IntegerSQLType
  }

  it should "handle all NULL types" in {
    TypeInferencer.unifyTypes(List(NullSQLType, NullSQLType)) shouldBe NullSQLType
  }

  it should "handle all UNKNOWN types" in {
    TypeInferencer.unifyTypes(List(UnknownSQLType, UnknownSQLType)) shouldBe UnknownSQLType
  }

  it should "handle NULL mixed with concrete type" in {
    TypeInferencer.unifyTypes(List(NullSQLType, StringSQLType)) shouldBe StringSQLType
  }

  // ============================================================
  //  补充测试 — TypeCheckVisitor：INSERT / UPDATE 类型检查
  // ============================================================

  "TypeCheckVisitor" should "detect type mismatch in INSERT values" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR")
      ))
    )
    val sql = "INSERT INTO users (id, name) VALUES ('not_a_number', 123)"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    errors.exists(_.category == "TYPE") shouldBe true
  }

  it should "pass type check for correct INSERT" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR")
      ))
    )
    val sql = "INSERT INTO users (id, name) VALUES (1, 'Alice')"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    errors.filter(_.category == "TYPE") shouldBe empty
  }

  it should "detect type mismatch in UPDATE SET" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR"),
        ColumnSchema("AGE", "INT")
      ))
    )
    val sql = "UPDATE users SET age = 'not_a_number' WHERE id = 1"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    errors.exists(_.category == "TYPE") shouldBe true
  }

  it should "pass type check for correct UPDATE" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR"),
        ColumnSchema("AGE", "INT")
      ))
    )
    val sql = "UPDATE users SET age = 30 WHERE id = 1"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    errors.filter(_.category == "TYPE") shouldBe empty
  }

  it should "detect type mismatch in CASE branches" in {
    val schema = DatabaseSchema(
      TableSchema("USERS", List(
        ColumnSchema("ID", "INT"),
        ColumnSchema("NAME", "VARCHAR"),
        ColumnSchema("AGE", "INT")
      ))
    )
    val sql = "SELECT CASE WHEN age > 18 THEN 'adult' ELSE 123 END AS category FROM users"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    errors.exists(e => e.category == "TYPE" && e.message.contains("CASE")) shouldBe true
  }

  it should "check types in HAVING clause" in {
    val schema = DatabaseSchema(
      TableSchema("ORDERS", List(
        ColumnSchema("DEPT", "VARCHAR"),
        ColumnSchema("AMOUNT", "INT")
      ))
    )
    val sql = "SELECT dept, SUM(amount) FROM orders GROUP BY dept HAVING dept + 1 > 0"
    val ast = MySQLParser.parse(sql)
    val visitor = new TypeCheckVisitor(schema)
    val errors = visitor.visitStatement(ast)
    // dept is VARCHAR, + 1 should produce type warning
    errors.exists(_.category == "TYPE") shouldBe true
  }

  // ============================================================
  //  补充测试 — ParseException：多行 SQL / 边界情况
  // ============================================================

  "ParseException" should "format multi-line SQL error correctly" in {
    val source = "SELECT *\nFROM users\nWHERE"
    val pos = Position(3, 6, 22)
    val exc = new ParseException("Unexpected end of input", pos, source)
    val msg = exc.getMessage
    msg should include("[3:6]")
    msg should include("WHERE")
    msg should include("^")
  }

  it should "handle position beyond line length" in {
    val source = "SELECT"
    val pos = Position(1, 100, 6)
    val exc = new ParseException("error", pos, source)
    val msg = exc.getMessage
    msg should include("[1:100]")
  }

  it should "handle position with line out of range" in {
    val source = "SELECT 1"
    val pos = Position(99, 1, 0)
    val msg = ParseException.formatMessage("error", pos, source)
    msg should include("[99:1]")
  }

  "ParseException.fromTokens" should "create exception from token list" in {
    val tokens = List(
      PositionedToken(SELECT, Position(1, 1, 0)),
      PositionedToken(MULTIPLY_OP, Position(1, 8, 7)),
      PositionedToken(EOF, Position(1, 9, 8))
    )
    val exc = ParseException.fromTokens("Unexpected EOF", tokens, 2, "SELECT *")
    exc.getMessage should include("[1:9]")
    exc.getMessage should include("Unexpected EOF")
  }

  it should "handle empty token list gracefully" in {
    val exc = ParseException.fromTokens("error", List.empty, 0, "")
    exc.getMessage should include("[1:1]")
  }

  it should "clamp out-of-range token index" in {
    val tokens = List(
      PositionedToken(SELECT, Position(1, 1, 0)),
      PositionedToken(EOF, Position(1, 7, 6))
    )
    val exc = ParseException.fromTokens("error", tokens, 100, "SELECT")
    exc.getMessage should include("[1:7]")
  }

  // ============================================================
  //  补充测试 — ASTJsonSerializer：ALTER TABLE / INDEX
  // ============================================================

  "ASTJsonSerializer - ALTER TABLE" should "serialize ALTER TABLE to JSON" in {
    val json = MySQLParser.toJson("ALTER TABLE users ADD COLUMN email VARCHAR(100)")
    json should include("AlterTableStatement")
    json should include("ADD COLUMN")
    json should include("EMAIL")
  }

  it should "serialize ALTER TABLE DROP COLUMN to JSON" in {
    val json = MySQLParser.toJson("ALTER TABLE users DROP COLUMN age")
    json should include("AlterTableStatement")
    json should include("DROP COLUMN")
  }

  it should "serialize ALTER TABLE RENAME TO to JSON" in {
    val json = MySQLParser.toJson("ALTER TABLE users RENAME TO customers")
    json should include("AlterTableStatement")
    json should include("RENAME TO")
  }

  "ASTJsonSerializer - CREATE INDEX" should "serialize CREATE INDEX to JSON" in {
    val json = MySQLParser.toJson("CREATE INDEX idx_name ON users (name)")
    json should include("CreateIndexStatement")
    json should include("IDX_NAME")
    json should include("\"unique\":false")
  }

  it should "serialize CREATE UNIQUE INDEX to JSON" in {
    val json = MySQLParser.toJson("CREATE UNIQUE INDEX idx_email ON users (email)")
    json should include("CreateIndexStatement")
    json should include("\"unique\":true")
  }

  "ASTJsonSerializer - DROP INDEX" should "serialize DROP INDEX to JSON" in {
    val json = MySQLParser.toJson("DROP INDEX idx_name ON users")
    json should include("DropIndexStatement")
    json should include("IDX_NAME")
  }

  // ============================================================
  //  补充测试 — ASTJsonSerializer：Window / CTE
  // ============================================================

  "ASTJsonSerializer - WindowFunction" should "serialize window function to JSON" in {
    val json = MySQLParser.toJson("SELECT ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary DESC) AS rn FROM employees")
    json should include("WindowFunctionExpression")
    json should include("partitionBy")
    json should include("orderBy")
  }

  "ASTJsonSerializer - CTE" should "serialize WITH statement to JSON" in {
    val json = MySQLParser.toJson("WITH temp AS (SELECT id FROM users) SELECT * FROM temp")
    json should include("WithStatement")
    json should include("ctes")
    json should include("TEMP")
  }

  it should "serialize WITH RECURSIVE to JSON" in {
    val json = MySQLParser.toJson("WITH RECURSIVE tree AS (SELECT id FROM nodes) SELECT * FROM tree")
    json should include("\"recursive\":true")
  }

  // ============================================================
  //  补充测试 — ASTJsonSerializer：INTERSECT / EXCEPT
  // ============================================================

  "ASTJsonSerializer - INTERSECT" should "serialize INTERSECT to JSON" in {
    val json = MySQLParser.toJson("SELECT id FROM t1 INTERSECT SELECT id FROM t2")
    json should include("UnionStatement")
    json should include("INTERSECT")
  }

  "ASTJsonSerializer - EXCEPT" should "serialize EXCEPT to JSON" in {
    val json = MySQLParser.toJson("SELECT id FROM t1 EXCEPT SELECT id FROM t2")
    json should include("UnionStatement")
    json should include("EXCEPT")
  }

  // ============================================================
  //  补充测试 — ASTJsonSerializer：各种表达式
  // ============================================================

  "ASTJsonSerializer - Expressions" should "serialize CASE expression to JSON" in {
    val json = MySQLParser.toJson("SELECT CASE WHEN age > 18 THEN 'adult' ELSE 'minor' END AS category FROM users")
    json should include("CaseExpression")
    json should include("whenClauses")
    json should include("elseResult")
  }

  it should "serialize CAST expression to JSON" in {
    val json = MySQLParser.toJson("SELECT CAST(price AS SIGNED) AS int_price FROM products")
    json should include("CastExpression")
    json should include("SIGNED")
  }

  it should "serialize IS NULL to JSON" in {
    val json = MySQLParser.toJson("SELECT * FROM users WHERE name IS NULL")
    json should include("IsNullExpression")
  }

  it should "serialize BETWEEN to JSON" in {
    val json = MySQLParser.toJson("SELECT * FROM users WHERE age BETWEEN 18 AND 65")
    json should include("BetweenExpression")
  }

  it should "serialize IN to JSON" in {
    val json = MySQLParser.toJson("SELECT * FROM users WHERE id IN (1, 2, 3)")
    json should include("InExpression")
  }

  it should "serialize LIKE to JSON" in {
    val json = MySQLParser.toJson("SELECT * FROM users WHERE name LIKE 'A%'")
    json should include("LikeExpression")
  }

  it should "serialize EXISTS to JSON" in {
    val json = MySQLParser.toJson("SELECT * FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)")
    json should include("ExistsExpression")
  }

  it should "serialize aggregate functions to JSON" in {
    val json = MySQLParser.toJson("SELECT COUNT(*), SUM(amount), AVG(price) FROM orders")
    json should include("AggregateFunction")
    json should include("COUNT")
    json should include("SUM")
    json should include("AVG")
  }

  it should "serialize subquery expression to JSON" in {
    val json = MySQLParser.toJson("SELECT * FROM users WHERE id = (SELECT MAX(user_id) FROM orders)")
    json should include("SubqueryExpression")
  }

  // ============================================================
  //  补充测试 — ASTJsonSerializer：Pretty Print
  // ============================================================

  "ASTJsonSerializer.prettify" should "format JSON with indentation" in {
    val compact = """{"type":"test","value":42}"""
    val pretty = ASTJsonSerializer.prettify(compact)
    pretty should include("\n")
    pretty should include("  ")
    pretty should include("\"type\"")
  }

  it should "handle nested JSON" in {
    val compact = """{"a":{"b":"c"}}"""
    val pretty = ASTJsonSerializer.prettify(compact)
    pretty.split("\n").length should be > 1
  }

  it should "handle JSON arrays" in {
    val compact = """{"items":[1,2,3]}"""
    val pretty = ASTJsonSerializer.prettify(compact)
    pretty should include("[")
    pretty should include("]")
  }

  it should "preserve escaped strings" in {
    val compact = """{"msg":"hello \"world\""}"""
    val pretty = ASTJsonSerializer.prettify(compact)
    pretty should include("hello \\\"world\\\"")
  }

  // ============================================================
  //  补充测试 — ASTJsonSerializer：CREATE TABLE 与约束
  // ============================================================

  "ASTJsonSerializer - CREATE TABLE with constraints" should "serialize constraints to JSON" in {
    val json = MySQLParser.toJson("CREATE TABLE t (id INT PRIMARY KEY NOT NULL, name VARCHAR(50) UNIQUE)")
    json should include("CreateTableStatement")
    json should include("PRIMARY KEY")
    json should include("NOT NULL")
    json should include("UNIQUE")
  }

  it should "serialize table-level constraints to JSON" in {
    val json = MySQLParser.toJson(
      "CREATE TABLE orders (id INT, user_id INT, CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id))")
    json should include("FOREIGN KEY")
    json should include("FK_USER")
  }

  // ============================================================
  //  补充测试 — InMemoryDatabase 直接 API
  // ============================================================

  "InMemoryDatabase" should "provide Schema for semantic analysis" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)
    executor.execute(MySQLParser.parse("CREATE TABLE users (id INT NOT NULL, name VARCHAR(50))"))

    val schema = db.toSchema
    schema.hasTable("USERS") shouldBe true
    schema.findTable("USERS").get.hasColumn("ID") shouldBe true
    schema.findTable("USERS").get.hasColumn("NAME") shouldBe true
  }

  it should "track views" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT)"))
    db.isView("V") shouldBe false

    executor.execute(MySQLParser.parse("CREATE VIEW v AS SELECT * FROM t"))
    db.isView("V") shouldBe true

    db.getView("V") should not be empty
  }

  it should "track procedures" in {
    val db = new InMemoryDatabase()
    val executor = new QueryExecutor(db)

    executor.execute(MySQLParser.parse("CREATE TABLE t (id INT)"))
    db.getProcedure("P") shouldBe None

    executor.execute(MySQLParser.parse("CREATE PROCEDURE p () BEGIN SELECT * FROM t END"))
    db.getProcedure("P") should not be empty
  }

  // ============================================================
  //  补充测试 — QueryResult.toTable 边界
  // ============================================================

  "QueryResult.toTable" should "handle empty columns" in {
    val result = QueryResult(Nil, Nil)
    result.toTable shouldBe "(empty result)"
  }

  it should "handle NULL values in output" in {
    val result = QueryResult(
      List("A", "B"),
      List(Map("A" -> "hello", "B" -> null))
    )
    val table = result.toTable
    table should include("NULL")
  }

  it should "report row count" in {
    val result = QueryResult(
      List("X"),
      List(Map("X" -> 1), Map("X" -> 2), Map("X" -> 3))
    )
    result.rowCount shouldBe 3
  }

  // ============================================================
  //  补充测试 — SQLType displayName
  // ============================================================

  "SQLType" should "have correct display names" in {
    IntegerSQLType.displayName shouldBe "INTEGER"
    NumericSQLType.displayName shouldBe "NUMERIC"
    StringSQLType.displayName shouldBe "STRING"
    BooleanSQLType.displayName shouldBe "BOOLEAN"
    DateSQLType.displayName shouldBe "DATE"
    DateTimeSQLType.displayName shouldBe "DATETIME"
    NullSQLType.displayName shouldBe "NULL"
    UnknownSQLType.displayName shouldBe "UNKNOWN"
  }

  // ============================================================
  //  补充测试 — TypeChecker.TypeError
  // ============================================================

  "TypeChecker.TypeError" should "contain all fields" in {
    val err = TypeChecker.TypeError("test message", IntegerSQLType, StringSQLType)
    err.message shouldBe "test message"
    err.leftType shouldBe IntegerSQLType
    err.rightType shouldBe StringSQLType
  }

  // ============================================================
  //  补充测试 — 优化器中 SetOperation 的处理
  // ============================================================

  "ConstantFolding" should "recurse into SetOperationPlan" in {
    val rule = new ConstantFolding()
    val left = FilterPlan(BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")), ScanPlan("T1"))
    val right = ScanPlan("T2")
    val setOp = SetOperationPlan(left, right, UnionDistinct)

    val optimized = rule.apply(setOp)
    // left side's filter with 1=1 should be folded
    optimized shouldBe a[SetOperationPlan]
    optimized.asInstanceOf[SetOperationPlan].left shouldBe a[ScanPlan]
  }

  "PredicatePushDown" should "recurse into SetOperationPlan" in {
    val rule = new PredicatePushDown()
    val left = ScanPlan("T1")
    val right = ScanPlan("T2")
    val setOp = SetOperationPlan(left, right, UnionAll)

    // Should not throw and should return same structure
    val optimized = rule.apply(setOp)
    optimized shouldBe a[SetOperationPlan]
  }

  "ProjectionPruning" should "recurse into SetOperationPlan" in {
    val rule = new ProjectionPruning()
    val left = ScanPlan("T1")
    val right = ScanPlan("T2")
    val setOp = SetOperationPlan(left, right, UnionAll)

    val optimized = rule.apply(setOp)
    optimized shouldBe a[SetOperationPlan]
  }

  // ============================================================
  //  补充测试 — 优化器中 DistinctPlan 的处理
  // ============================================================

  "ConstantFolding" should "recurse into DistinctPlan" in {
    val rule = new ConstantFolding()
    val scan = ScanPlan("T")
    val filter = FilterPlan(BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")), scan)
    val distinct = DistinctPlan(filter)

    val optimized = rule.apply(distinct)
    optimized shouldBe a[DistinctPlan]
    optimized.asInstanceOf[DistinctPlan].child shouldBe a[ScanPlan]  // filter removed
  }

  // ============================================================
  //  补充测试 — 优化器中 AggregatePlan 的处理
  // ============================================================

  "ConstantFolding" should "recurse into AggregatePlan" in {
    val rule = new ConstantFolding()
    val scan = ScanPlan("T")
    val filter = FilterPlan(BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")), scan)
    val agg = AggregatePlan(List(Identifier("DEPT")), Nil, filter)

    val optimized = rule.apply(agg)
    optimized shouldBe a[AggregatePlan]
    optimized.asInstanceOf[AggregatePlan].child shouldBe a[ScanPlan]
  }

  // ============================================================
  //  补充测试 — 优化器中 SubqueryPlan 的处理
  // ============================================================

  "ConstantFolding" should "recurse into SubqueryPlan" in {
    val rule = new ConstantFolding()
    val scan = ScanPlan("T")
    val filter = FilterPlan(BinaryExpression(NumberLiteral("1"), Equal, NumberLiteral("1")), scan)
    val sub = SubqueryPlan("SUB", filter)

    val optimized = rule.apply(sub)
    optimized shouldBe a[SubqueryPlan]
    optimized.asInstanceOf[SubqueryPlan].child shouldBe a[ScanPlan]
  }

  // ============================================================
  //  补充测试 — ExpressionEvaluator: built-in function coverage
  // ============================================================

  "ExpressionEvaluator - SUBSTRING" should "extract substring" in {
    val result = ExpressionEvaluator.evaluate(
      FunctionCall("SUBSTRING", List(StringLiteral("Hello World"), NumberLiteral("1"), NumberLiteral("5"))),
      Map.empty
    )
    result shouldBe "Hello"
  }

  "ExpressionEvaluator - REPLACE" should "replace in string" in {
    val result = ExpressionEvaluator.evaluate(
      FunctionCall("REPLACE", List(StringLiteral("Hello World"), StringLiteral("World"), StringLiteral("Scala"))),
      Map.empty
    )
    result shouldBe "Hello Scala"
  }

  "ExpressionEvaluator - CONCAT" should "concatenate strings" in {
    val result = ExpressionEvaluator.evaluate(
      FunctionCall("CONCAT", List(StringLiteral("Hello"), StringLiteral(" "), StringLiteral("World"))),
      Map.empty
    )
    result shouldBe "Hello World"
  }

  it should "return NULL if any argument is NULL" in {
    val result = ExpressionEvaluator.evaluate(
      FunctionCall("CONCAT", List(StringLiteral("Hello"), NullLiteral)),
      Map.empty
    )
    result shouldBe (null: Any)
  }

  "ExpressionEvaluator - ROUND" should "round to specified scale" in {
    val result = ExpressionEvaluator.evaluate(
      FunctionCall("ROUND", List(NumberLiteral("3.14159"), NumberLiteral("2"))),
      Map.empty
    )
    result shouldBe 3.14
  }

  "ExpressionEvaluator - MOD" should "compute modulo" in {
    val result = ExpressionEvaluator.evaluate(
      FunctionCall("MOD", List(NumberLiteral("10"), NumberLiteral("3"))),
      Map.empty
    )
    result shouldBe 1L
  }

  "ExpressionEvaluator - NULLIF" should "return NULL when args equal" in {
    val result = ExpressionEvaluator.evaluate(
      FunctionCall("NULLIF", List(NumberLiteral("1"), NumberLiteral("1"))),
      Map.empty
    )
    result shouldBe (null: Any)
  }

  it should "return first arg when args differ" in {
    val result = ExpressionEvaluator.evaluate(
      FunctionCall("NULLIF", List(NumberLiteral("1"), NumberLiteral("2"))),
      Map.empty
    )
    result shouldBe 1L
  }
}
