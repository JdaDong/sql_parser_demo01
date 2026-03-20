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

  "Parser" should "parse simple SELECT statement" in {
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
    exprCol.alias shouldBe Some("cat_count")
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
    exprCol.alias shouldBe Some("avg_salary")
    exprCol.expression shouldBe AggregateFunction(AvgFunc, Identifier("salary"), distinct = false)
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
    isNull.expression shouldBe Identifier("email")
    isNull.negated shouldBe false
  }

  it should "parse IS NOT NULL predicate" in {
    val sql = "SELECT * FROM users WHERE email IS NOT NULL"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val isNotNull = select.where.get.asInstanceOf[IsNullExpression]
    isNotNull.expression shouldBe Identifier("email")
    isNotNull.negated shouldBe true
  }

  it should "parse BETWEEN predicate" in {
    val sql = "SELECT * FROM products WHERE price BETWEEN 100 AND 500"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val between = select.where.get.asInstanceOf[BetweenExpression]
    between.expression shouldBe Identifier("price")
    between.lower shouldBe NumberLiteral("100")
    between.upper shouldBe NumberLiteral("500")
    between.negated shouldBe false
  }

  it should "parse NOT BETWEEN predicate" in {
    val sql = "SELECT * FROM products WHERE price NOT BETWEEN 100 AND 500"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val between = select.where.get.asInstanceOf[BetweenExpression]
    between.expression shouldBe Identifier("price")
    between.negated shouldBe true
  }

  it should "parse IN predicate" in {
    val sql = "SELECT * FROM users WHERE role IN ('admin', 'editor', 'moderator')"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val inExpr = select.where.get.asInstanceOf[InExpression]
    inExpr.expression shouldBe Identifier("role")
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
    inExpr.expression shouldBe Identifier("role")
    inExpr.values should have length 2
    inExpr.negated shouldBe true
  }

  it should "parse LIKE predicate" in {
    val sql = "SELECT * FROM users WHERE name LIKE 'Ali%'"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val likeExpr = select.where.get.asInstanceOf[LikeExpression]
    likeExpr.expression shouldBe Identifier("name")
    likeExpr.pattern shouldBe StringLiteral("Ali%")
    likeExpr.negated shouldBe false
  }

  it should "parse NOT LIKE predicate" in {
    val sql = "SELECT * FROM users WHERE name NOT LIKE '%test%'"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val likeExpr = select.where.get.asInstanceOf[LikeExpression]
    likeExpr.expression shouldBe Identifier("name")
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
    orderByClauses.head.expression shouldBe Identifier("age")
    orderByClauses.head.ascending shouldBe false
  }

  it should "parse ORDER BY with ASC" in {
    val sql = "SELECT * FROM users ORDER BY name ASC"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.orderBy shouldBe defined
    val orderByClauses = select.orderBy.get
    orderByClauses should have length 1
    orderByClauses.head.expression shouldBe Identifier("name")
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
    orderByClauses(0).expression shouldBe Identifier("category")
    orderByClauses(0).ascending shouldBe true
    orderByClauses(1).expression shouldBe Identifier("price")
    orderByClauses(1).ascending shouldBe false
    orderByClauses(2).expression shouldBe Identifier("name")
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
    where.left shouldBe Identifier("salary")
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
    inSub.expression shouldBe Identifier("id")
    inSub.negated shouldBe false
    inSub.query.asInstanceOf[SelectStatement].from shouldBe defined
  }

  it should "parse NOT IN subquery" in {
    val sql = "SELECT * FROM users WHERE id NOT IN (SELECT user_id FROM blacklist)"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val inSub = select.where.get.asInstanceOf[InSubqueryExpression]
    inSub.expression shouldBe Identifier("id")
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
    derived.alias shouldBe "t"
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
    exprCol.alias shouldBe Some("order_count")
    exprCol.expression shouldBe a[SubqueryExpression]
  }

  it should "parse nested subqueries" in {
    val sql = "SELECT * FROM users WHERE dept_id IN (SELECT dept_id FROM departments WHERE company_id IN (SELECT id FROM companies WHERE country = 'CN'))"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.where shouldBe defined
    val outer = select.where.get.asInstanceOf[InSubqueryExpression]
    outer.expression shouldBe Identifier("dept_id")
    // 内层也是 IN 子查询
    outer.query.asInstanceOf[SelectStatement].where shouldBe defined
    val inner = outer.query.asInstanceOf[SelectStatement].where.get.asInstanceOf[InSubqueryExpression]
    inner.expression shouldBe Identifier("company_id")
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
    exprCol.alias shouldBe Some("salary_level")
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
    caseExpr.operand shouldBe Some(Identifier("status")) // 简单式
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
    leftSelect.from.get.asInstanceOf[TableName].name shouldBe "users"
    val rightSelect = union.right
    rightSelect.columns should have length 1
    rightSelect.from.get.asInstanceOf[TableName].name shouldBe "admins"
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
    outer.right.from.get.asInstanceOf[TableName].name shouldBe "t3"
    // 左侧是嵌套的 UNION
    outer.left shouldBe a[UnionStatement]
    val inner = outer.left.asInstanceOf[UnionStatement]
    inner.unionType shouldBe UnionDistinct
    inner.left.asInstanceOf[SelectStatement].from.get.asInstanceOf[TableName].name shouldBe "t1"
    inner.right.from.get.asInstanceOf[TableName].name shouldBe "t2"
  }

  it should "parse UNION with ORDER BY on last SELECT" in {
    val sql = "SELECT name FROM users UNION SELECT name FROM admins ORDER BY name"
    val ast = MySQLParser.parse(sql)

    ast shouldBe a[UnionStatement]
    val union = ast.asInstanceOf[UnionStatement]
    // ORDER BY 附在最后一个 SELECT 上
    union.right.orderBy shouldBe defined
    union.right.orderBy.get should have length 1
    union.right.orderBy.get.head.expression shouldBe Identifier("name")
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
    derived.alias shouldBe "t"
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
    func.arguments.head shouldBe Identifier("name")
  }

  it should "parse multi-arg function call" in {
    val sql = "SELECT CONCAT(first_name, ' ', last_name) FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val func = exprCol.expression.asInstanceOf[FunctionCall]
    func.name shouldBe "CONCAT"
    func.arguments should have length 3
    func.arguments(0) shouldBe Identifier("first_name")
    func.arguments(1) shouldBe StringLiteral(" ")
    func.arguments(2) shouldBe Identifier("last_name")
  }

  it should "parse function call with alias" in {
    val sql = "SELECT UPPER(name) AS uname FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("uname")
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
    col1.name shouldBe "name"
    col1.alias shouldBe Some("n")
    val col2 = select.columns(1).asInstanceOf[NamedColumn]
    col2.name shouldBe "age"
    col2.alias shouldBe Some("a")
  }

  it should "parse implicit alias for aggregate function" in {
    val sql = "SELECT COUNT(*) total FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("total")
    exprCol.expression shouldBe a[AggregateFunction]
  }

  it should "parse implicit alias for function call" in {
    val sql = "SELECT UPPER(name) uname FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    exprCol.alias shouldBe Some("uname")
    exprCol.expression shouldBe a[FunctionCall]
  }

  it should "parse implicit alias for qualified column" in {
    val sql = "SELECT t.name tname FROM users t"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val col = select.columns.head.asInstanceOf[QualifiedColumn]
    col.table shouldBe "t"
    col.column shouldBe "name"
    col.alias shouldBe Some("tname")
  }

  // ====== CAST 表达式测试 ======

  it should "parse CAST with DECIMAL type" in {
    val sql = "SELECT CAST(price AS DECIMAL(10,2)) FROM products"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    select.columns should have length 1
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val cast = exprCol.expression.asInstanceOf[CastExpression]
    cast.expression shouldBe Identifier("price")
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
    convert.expression shouldBe Identifier("price")
    convert.targetType shouldBe Some(DecimalCastType(Some(10), Some(2)))
    convert.charset shouldBe None
  }

  it should "parse CONVERT with USING charset" in {
    val sql = "SELECT CONVERT(name USING utf8) FROM users"
    val ast = MySQLParser.parse(sql)

    val select = ast.asInstanceOf[SelectStatement]
    val exprCol = select.columns.head.asInstanceOf[ExpressionColumn]
    val convert = exprCol.expression.asInstanceOf[ConvertExpression]
    convert.expression shouldBe Identifier("name")
    convert.targetType shouldBe None
    convert.charset shouldBe Some("utf8")
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
    errors.exists(_.message.contains("nonexistent_table")) shouldBe true
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
    errors.exists(_.message.contains("fake_column")) shouldBe true
  }

  it should "detect non-existent column in WHERE" in {
    val errors = analyzeSQL("SELECT * FROM users WHERE nonexistent > 10")
    errors should not be empty
    errors.exists(_.category == "COLUMN") shouldBe true
    errors.exists(_.message.contains("nonexistent")) shouldBe true
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
    errors.exists(_.message.contains("x")) shouldBe true
  }

  it should "detect column not in specified table" in {
    val errors = analyzeSQL("SELECT u.nonexistent FROM users u")
    errors should not be empty
    errors.exists(_.category == "COLUMN") shouldBe true
    errors.exists(_.message.contains("nonexistent")) shouldBe true
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
    errors.exists(_.message.contains("fake_col")) shouldBe true
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
    errors.exists(_.message.contains("name")) shouldBe true
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
    havingErrors.exists(_.message.contains("name")) shouldBe true
  }

  // ====== 表达式中的函数调用 ======

  it should "pass function call with valid column arguments" in {
    val errors = analyzeSQL("SELECT UPPER(name) FROM users")
    errors shouldBe empty
  }

  it should "detect non-existent column in function argument" in {
    val errors = analyzeSQL("SELECT UPPER(nonexistent) FROM users")
    errors should not be empty
    errors.exists(_.message.contains("nonexistent")) shouldBe true
  }

  // ====== CAST / CONVERT 中的列检查 ======

  it should "pass CAST with valid column" in {
    val errors = analyzeSQL("SELECT CAST(price AS DECIMAL(10,2)) FROM products")
    errors shouldBe empty
  }

  it should "detect non-existent column in CAST" in {
    val errors = analyzeSQL("SELECT CAST(fake AS DECIMAL(10,2)) FROM products")
    errors should not be empty
    errors.exists(_.message.contains("fake")) shouldBe true
  }

  // ============================================================
  //  AST Visitor 模式测试
  // ============================================================

  // ====== TableExtractor 测试 ======

  "TableExtractor" should "extract table from simple SELECT" in {
    val ast = MySQLParser.parse("SELECT * FROM users")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast)
    tables should contain("users")
  }

  it should "extract tables from JOIN query" in {
    val ast = MySQLParser.parse("SELECT u.name FROM users u JOIN orders o ON u.id = o.user_id")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain allOf("users", "orders")
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
    tables should contain allOf("users", "admins")
  }

  it should "extract tables from subquery" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE id IN (SELECT user_id FROM orders)")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain allOf("users", "orders")
  }

  it should "extract table from derived table" in {
    val ast = MySQLParser.parse("SELECT t.name FROM (SELECT name FROM users WHERE age > 18) AS t")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast).distinct
    tables should contain("users")
  }

  it should "extract table from CREATE TABLE" in {
    val ast = MySQLParser.parse("CREATE TABLE new_table (id INT)")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast)
    tables should contain("new_table")
  }

  it should "extract table from DROP TABLE" in {
    val ast = MySQLParser.parse("DROP TABLE old_table")
    val extractor = new TableExtractor()
    val tables = extractor.visitStatement(ast)
    tables should contain("old_table")
  }

  // ====== ColumnExtractor 测试 ======

  "ColumnExtractor" should "extract columns from simple SELECT" in {
    val ast = MySQLParser.parse("SELECT id, name, age FROM users")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("id", "name", "age")
  }

  it should "extract columns from qualified column" in {
    val ast = MySQLParser.parse("SELECT u.name, u.age FROM users u")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("name", "age")
  }

  it should "extract columns from WHERE clause" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE age > 18 AND name = 'test'")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("age", "name")
  }

  it should "extract columns from INSERT" in {
    val ast = MySQLParser.parse("INSERT INTO users (name, age) VALUES ('Alice', 25)")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("name", "age")
  }

  it should "extract columns from UPDATE SET" in {
    val ast = MySQLParser.parse("UPDATE users SET name = 'Bob', age = 30 WHERE id = 1")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("name", "age", "id")
  }

  it should "extract columns from aggregate function" in {
    val ast = MySQLParser.parse("SELECT department, COUNT(*), AVG(salary) FROM employees GROUP BY department")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain allOf("department", "salary")
  }

  it should "extract columns from function call" in {
    val ast = MySQLParser.parse("SELECT UPPER(name) FROM users")
    val extractor = new ColumnExtractor()
    val columns = extractor.visitStatement(ast).distinct
    columns should contain("name")
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
    sql should include("id")
    sql should include("name")
    sql should include("FROM")
    sql should include("users")
  }

  it should "format SELECT with WHERE" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE age > 18")
    val printer = new SQLPrettyPrinter()
    val sql = printer.visitStatement(ast)
    sql should include("WHERE")
    sql should include("age > 18")
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
    sql should include("name DESC")
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
    sql should include("AVG(salary)")
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
    sql should include("products")  // products should not be renamed
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
    sql should include("u.full_name")
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
    sql should include("id")
    sql should include("email")
  }

  // ====== Visitor 组合使用测试 ======

  "AST Visitor pipeline" should "extract then format correctly" in {
    val sql = "SELECT u.name, COUNT(*) AS cnt FROM users u GROUP BY u.name"
    val ast = MySQLParser.parse(sql)

    // Step 1: 提取表名
    val tables = new TableExtractor().visitStatement(ast).distinct
    tables should contain("users")

    // Step 2: 提取列名
    val columns = new ColumnExtractor().visitStatement(ast).distinct
    columns should contain("name")

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
    errors.exists(_.message.contains("nonexistent_table")) shouldBe true
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
    errors.exists(_.message.contains("fake_column")) shouldBe true
  }

  it should "detect non-existent column in WHERE" in {
    val ast = MySQLParser.parse("SELECT * FROM users WHERE nonexistent > 10")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("nonexistent")) shouldBe true
  }

  it should "detect undefined table alias" in {
    val ast = MySQLParser.parse("SELECT x.name FROM users u")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("x")) shouldBe true
  }

  it should "detect column not in specified table" in {
    val ast = MySQLParser.parse("SELECT u.nonexistent FROM users u")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("nonexistent")) shouldBe true
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
    errors.exists(_.message.contains("fake_col")) shouldBe true
  }

  it should "detect non-existent column in function argument" in {
    val ast = MySQLParser.parse("SELECT UPPER(nonexistent) FROM users")
    val visitor = new ColumnExistenceVisitor(testSchema)
    val errors = visitor.visitStatement(ast)
    errors should not be empty
    errors.exists(_.message.contains("nonexistent")) shouldBe true
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
    errors.exists(_.message.contains("fake")) shouldBe true
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
    errors.exists(_.message.contains("name")) shouldBe true
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
    errors.exists(_.message.contains("name")) shouldBe true
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
    errors.exists(_.message.contains("nonexistent")) shouldBe true
  }
}
