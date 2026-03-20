# SQL Parser Demo01 (Scala)

一个用 Scala 实现的 MySQL SQL 语句解析器，支持词法分析和语法分析。

## 功能特性

- ✅ **词法分析**：将 SQL 字符串分解为 Token 流
- ✅ **语法分析**：将 Token 流解析为抽象语法树（AST）
- ✅ **支持的 SQL 语句**：
  - `SELECT`（支持 DISTINCT、JOIN、WHERE、GROUP BY、HAVING、ORDER BY、LIMIT、OFFSET）
  - `INSERT`
  - `UPDATE`
  - `DELETE`
  - `CREATE TABLE`
  - `DROP TABLE`

## 项目结构

```
sql_parser_demo01/
├── build.sbt                 # SBT 构建文件
├── src/
│   ├── main/
│   │   └── scala/
│   │       └── com/
│   │           └── mysql/
│   │               └── parser/
│   │                   ├── Token.scala          # Token 定义
│   │                   ├── Lexer.scala          # 词法分析器
│   │                   ├── AST.scala            # AST 结构定义
│   │                   ├── Parser.scala         # 语法分析器
│   │                   └── MySQLParser.scala    # 主入口
│   └── test/
│       └── scala/
│           └── com/
│               └── mysql/
│                   └── parser/
│                       └── MySQLParserTest.scala # 单元测试
└── README.md
```

## 快速开始

### 编译项目

```bash
sbt compile
```

### 运行示例

```bash
sbt run
```

### 运行测试

```bash
sbt test
```

## 使用示例

### 基本用法

```scala
import com.mysql.parser.MySQLParser

// 解析 SQL 语句
val sql = "SELECT * FROM users WHERE age > 18"
val ast = MySQLParser.parse(sql)

// 打印 AST
MySQLParser.printAST(ast)
```

### 支持的 SQL 示例

```sql
-- 简单查询
SELECT * FROM users

-- 带条件查询
SELECT id, name, email FROM users WHERE age > 18

-- JOIN 查询
SELECT u.id, u.name, o.order_id 
FROM users u 
JOIN orders o ON u.id = o.user_id

-- INSERT 语句
INSERT INTO users (name, email) VALUES ('Alice', 'alice@example.com')

-- UPDATE 语句
UPDATE users SET age = 25 WHERE name = 'Bob'

-- DELETE 语句
DELETE FROM users WHERE id = 1

-- CREATE TABLE 语句
CREATE TABLE users (
  id INT,
  name VARCHAR(100),
  email TEXT
)

-- 复杂查询
SELECT DISTINCT category 
FROM products 
WHERE price > 100 
ORDER BY category 
LIMIT 10
```

## 核心组件

### 1. Lexer（词法分析器）

将 SQL 字符串分解为 Token 流：

```scala
val lexer = new Lexer("SELECT * FROM users")
val tokens = lexer.tokenize()
// 输出: List(SELECT, MULTIPLY, FROM, IDENTIFIER("USERS"), EOF)
```

### 2. Parser（语法分析器）

将 Token 流解析为 AST：

```scala
val parser = new Parser(tokens)
val ast = parser.parse()
// 输出: SelectStatement(...)
```

### 3. AST（抽象语法树）

表示 SQL 语句的结构化数据：

```scala
case class SelectStatement(
  columns: List[Column],
  from: Option[TableReference],
  where: Option[Expression],
  // ...
)
```

## 依赖项

- Scala 2.13.12
- ScalaTest 3.2.17（测试）
- Scala Parser Combinators 2.3.0

## 扩展功能

当前解析器支持核心 SQL 功能，可以进一步扩展：

- [ ] 子查询支持
- [ ] 聚合函数（COUNT、SUM、AVG 等）
- [ ] UNION/INTERSECT/EXCEPT
- [ ] 更多数据类型
- [ ] 约束条件（PRIMARY KEY、FOREIGN KEY 等）
- [ ] 索引支持
- [ ] 视图和存储过程

## 许可证

MIT License
# sql_parser_demo01
