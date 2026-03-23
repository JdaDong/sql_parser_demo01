# 🗺️ SQL Parser 项目 Roadmap 设计文档

> **文档版本**: v2.0  
> **最后更新**: 2026-03-23  
> **项目路径**: `sql_parser_demo01/`  
> **当前状态**: Phase 1 全部完成 ✅ | 643 测试 | 0 失败

---

## 📌 文档目录

- [项目愿景](#-项目愿景)
- [架构总览](#-架构总览)
- [Phase 1：核心解析器（已完成）](#-phase-1核心解析器已完成-)
- [Phase 2：SQL 语法拓展](#-phase-2sql-语法拓展)
- [Phase 3：执行引擎深度增强](#-phase-3执行引擎深度增强)
- [Phase 4：查询优化器进阶](#-phase-4查询优化器进阶)
- [Phase 5：数据库系统特性](#-phase-5数据库系统特性)
- [Phase 6：生态工具与对外接口](#-phase-6生态工具与对外接口)
- [总体进度看板](#-总体进度看板)
- [实施时间线](#-实施时间线)
- [修改文件速查](#-修改文件速查)

---

## 🎯 项目愿景

> **从教学级 SQL 解析器，演进为功能完整的微型关系数据库系统。**

```
                              ┌─────────────────────────────────────────────┐
                              │           SQL Parser Project                │
                              │                                             │
  Phase 1 ✅                  │   ┌───────┐  ┌────────┐  ┌──────────────┐  │
  核心解析器                   │   │ Lexer │→│ Parser │→│   AST Tree   │  │
  (已完成)                    │   └───────┘  └────────┘  └──────┬───────┘  │
                              │                                  │         │
                              │              ┌───────────────────┼────┐    │
                              │              ▼                   ▼    ▼    │
  Phase 1 ✅                  │   ┌──────────────┐  ┌────────┐ ┌─────┐   │
  分析 & 序列化                │   │SemanticAnalyze│  │  JSON  │ │Print│   │
  (已完成)                    │   │+ TypeChecker  │  │Serializ│ │  er │   │
                              │   └──────────────┘  └────────┘ └─────┘   │
                              │              │                            │
                              │              ▼                            │
  Phase 1 ✅                  │   ┌──────────────────┐                   │
  执行 & 优化                  │   │  Query Optimizer  │                   │
  (已完成)                    │   │  (4 Rules)        │                   │
                              │   └────────┬─────────┘                   │
                              │            ▼                              │
                              │   ┌──────────────────┐                   │
                              │   │ Execution Engine  │                   │
                              │   │ (InMemoryDatabase)│                   │
                              │   └────────┬─────────┘                   │
                              │            ▼                              │
  Phase 2~6 🔮                │   ┌──────────────────┐                   │
  未来演进                     │   │  Transaction      │                   │
                              │   │  Persistence       │                   │
                              │   │  Network Protocol  │                   │
                              │   │  Web UI            │                   │
                              │   └──────────────────┘                   │
                              └─────────────────────────────────────────────┘
```

---

## 🏗️ 架构总览

### 当前项目文件结构

```
src/main/scala/com/mysql/parser/
├── Token.scala              # Token 定义 + Position + PositionedToken
├── Lexer.scala              # 词法分析器（注释、行号/列号跟踪）
├── AST.scala                # AST 节点定义（30+ 语句/表达式类型）
├── Parser.scala             # 递归下降解析器（2162 行）
├── ParseException.scala     # 位置感知错误报告
├── ASTVisitor.scala         # Visitor 模式框架（5 个内置实现）
├── SemanticAnalyzer.scala   # 传统语义分析（8 个检查项）
├── SemanticVisitor.scala    # Visitor 管道语义分析
├── TypeChecker.scala        # 类型系统（8 种 SQLType）
├── ASTJsonSerializer.scala  # AST → JSON 全量序列化
├── ExecutionEngine.scala    # 内存数据库 + 表达式求值 + SQL 执行器（1146 行）
├── QueryPlan.scala          # 逻辑执行计划 + 优化器 + 可视化（639 行）
├── Schema.scala             # 数据库 Schema 定义
├── MySQLParser.scala        # 统一入口 API
└── SQLRepl.scala            # 交互式 REPL 命令行

src/test/scala/com/mysql/parser/
└── MySQLParserTest.scala    # 643 个测试用例
```

### 数据流架构

```
SQL 字符串
    │
    ▼
┌─────────┐     ┌──────────┐     ┌──────────┐     ┌───────────┐     ┌──────────┐
│  Lexer  │ ──▶ │  Parser  │ ──▶ │ Semantic │ ──▶ │ Optimizer │ ──▶ │ Executor │
│ 词法分析 │     │ 语法分析  │     │ 语义分析  │     │  查询优化  │     │ 查询执行  │
└─────────┘     └────┬─────┘     └────┬─────┘     └─────┬─────┘     └────┬─────┘
    │                │                │                  │                │
    ▼                ▼                ▼                  ▼                ▼
Token 流          AST 树         错误报告列表       LogicalPlan       QueryResult
                    │                                                    │
                    ├──▶ JSON 序列化                                      │
                    ├──▶ Pretty Print                                    │
                    └──▶ 类型推断                                    格式化表格输出
```

---

## ✅ Phase 1：核心解析器（已完成 🎉）

> **状态**: 全部完成 | **测试**: 643 通过 / 0 失败

### 1A — 基础框架

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 1 | Lexer → Parser → AST | ✅ | 经典编译器前端三件套 |
| 2 | SELECT / INSERT / UPDATE / DELETE | ✅ | 完整 DML 支持 |
| 3 | CREATE TABLE / DROP TABLE | ✅ | 基础 DDL 支持 |
| 4 | 算术/比较/逻辑表达式 | ✅ | 完整运算符优先级 |
| 5 | 聚合函数 COUNT/SUM/AVG/MAX/MIN | ✅ | 支持 DISTINCT |
| 6 | 谓词 IS NULL/BETWEEN/IN/LIKE | ✅ | 含 NOT 取反 |

### 1B — SQL 语法增强

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 7 | 子查询 | ✅ | WHERE/IN/EXISTS/FROM 派生表/SELECT 标量 |
| 8 | CASE 表达式 | ✅ | 搜索式 + 简单式 |
| 9 | UNION / UNION ALL | ✅ | 链式复合查询 |
| 10 | INTERSECT / EXCEPT | ✅ | 含 ALL 修饰符 |
| 11 | 窗口函数 | ✅ | ROW_NUMBER/RANK/DENSE_RANK/NTILE + PARTITION BY + 帧子句 |
| 12 | CTE — WITH 子句 | ✅ | 多 CTE + WITH RECURSIVE |
| 13 | ALTER TABLE | ✅ | ADD/DROP/MODIFY/CHANGE COLUMN + RENAME + CONSTRAINT |
| 14 | 约束条件 | ✅ | PK/NN/UNIQUE/FK/CHECK/DEFAULT/AUTO_INCREMENT |
| 15 | 索引支持 | ✅ | CREATE/DROP INDEX（多列+排序方向） |
| 16 | 函数调用 | ✅ | UPPER/LOWER/CONCAT/NOW 等 20+ 内置函数 |
| 17 | CAST / CONVERT | ✅ | 类型转换表达式 |
| 18 | SQL 注释 | ✅ | `-- 单行` / `# MySQL` / `/* 块 */` |

### 1C — 工程基础设施

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 19 | 增强错误报告 | ✅ | `[line:column]` + 源码上下文 + `^` 指示器 |
| 20 | AST Visitor 框架 | ✅ | 只读遍历 + AST 变换 + 5 个内置实现 |
| 21 | 语义分析 | ✅ | 传统 + Visitor 管道（8 个检查项） |
| 22 | 类型系统 | ✅ | 8 种 SQLType + TypeInferencer + TypeChecker |
| 23 | AST JSON 序列化 | ✅ | 全量覆盖所有 AST 节点 |
| 24 | SQL Pretty Printer | ✅ | AST → 格式化 SQL |
| 25 | REPL 命令行 | ✅ | `:tokens/:ast/:format/:analyze/:json/:all` 模式 |

### 1D — 终极目标

| # | 功能 | 状态 | 说明 |
|---|------|------|------|
| 26 | 查询执行引擎 | ✅ | InMemoryDatabase + ExpressionEvaluator + QueryExecutor |
| 27 | 查询优化器/执行计划 | ✅ | 9 种 LogicalPlan + 4 种优化规则 + 可视化 |
| 28 | 视图和存储过程 | ✅ | CREATE/DROP VIEW + CREATE/DROP PROCEDURE + CALL |

---

## 🚀 Phase 2：SQL 语法拓展

> **目标**: 补齐实际 MySQL 中常用但当前缺失的语法支持  
> **预计周期**: 2~3 周  
> **难度**: ⭐⭐~⭐⭐⭐

### 2.1 SHOW / DESCRIBE 语句

让 REPL 更像真实的 MySQL 客户端，极大提升交互体验。

```sql
-- 查看所有表
SHOW TABLES;

-- 查看表结构
SHOW COLUMNS FROM users;
DESCRIBE users;
DESC users;

-- 查看建表语句
SHOW CREATE TABLE users;

-- 查看执行计划（复用 QueryPlanPrinter）
EXPLAIN SELECT * FROM users WHERE age > 18;
```

**实现要点**：
- Token：新增 `SHOW`、`DESCRIBE`、`DESC`、`EXPLAIN`、`TABLES`、`COLUMNS` 关键字
- AST：新增 `ShowTablesStatement`、`ShowColumnsStatement`、`ShowCreateTableStatement`、`DescribeStatement`、`ExplainStatement`
- Parser：新增 `parseShow()`、`parseDescribe()`、`parseExplain()` 方法
- ExecutionEngine：从 `InMemoryDatabase` 读取元数据返回结果

**修改文件**: Token → Lexer → AST → Parser → ExecutionEngine → MySQLParser → Test

---

### 2.2 TRUNCATE TABLE

```sql
TRUNCATE TABLE users;
```

**实现要点**：
- Token：新增 `TRUNCATE` 关键字
- AST：新增 `TruncateTableStatement(tableName: String)`
- ExecutionEngine：清空表所有数据，重置 AUTO_INCREMENT

**修改文件**: Token → Lexer → AST → Parser → ExecutionEngine → Test

---

### 2.3 INSERT ... SELECT

```sql
-- 从查询结果插入
INSERT INTO archive_users SELECT * FROM users WHERE status = 'inactive';

-- 指定列插入
INSERT INTO summary (name, total)
SELECT department, SUM(salary) FROM employees GROUP BY department;
```

**实现要点**：
- AST：扩展 `InsertStatement`，`values` 改为 `Either[List[List[Expression]], SelectStatement]`
- Parser：在 `parseInsert()` 中检测 VALUES vs SELECT 分支
- ExecutionEngine：先执行子 SELECT，再批量 insert 结果行

**修改文件**: AST → Parser → ExecutionEngine → PrettyPrinter → JSON Serializer → Test

---

### 2.4 扩展 JOIN 类型

```sql
-- CROSS JOIN（笛卡尔积）
SELECT * FROM colors CROSS JOIN sizes;

-- NATURAL JOIN（自动匹配同名列）
SELECT * FROM orders NATURAL JOIN customers;

-- FULL OUTER JOIN
SELECT * FROM a FULL OUTER JOIN b ON a.id = b.id;
```

**实现要点**：
- Token：新增 `CROSS`、`NATURAL`、`FULL`、`OUTER` 关键字
- AST：扩展 `JoinType` 枚举，新增 `CrossJoin`、`NaturalJoin`、`FullOuterJoin`
- ExecutionEngine：实现 CROSS JOIN（笛卡尔积）和 FULL OUTER JOIN（左右合并）

**修改文件**: Token → Lexer → AST → Parser → ExecutionEngine → PrettyPrinter → Test

---

### 2.5 SET 变量与用户变量

```sql
-- 设置用户变量
SET @threshold = 100;
SET @name = 'Alice';

-- 在查询中使用
SELECT * FROM products WHERE price > @threshold;

-- 系统变量（只读查询）
SELECT @@version;
```

**实现要点**：
- Token：新增 `SET`（已有？需确认）、`AT_SIGN`（@）
- AST：新增 `SetStatement(variable, value)` 和 `UserVariable(name)` 表达式
- ExecutionEngine：`InMemoryDatabase` 维护 `variables: Map[String, Any]`

**修改文件**: Token → Lexer → AST → Parser → ExecutionEngine → Test

---

### 2.6 REPLACE INTO / INSERT ... ON DUPLICATE KEY UPDATE

```sql
-- REPLACE INTO（MySQL 特有）
REPLACE INTO users (id, name, email) VALUES (1, 'Alice', 'alice@new.com');

-- ON DUPLICATE KEY UPDATE（MySQL 特有 upsert）
INSERT INTO users (id, name, score)
VALUES (1, 'Alice', 100)
ON DUPLICATE KEY UPDATE score = score + VALUES(score);
```

**实现要点**：
- Token：新增 `REPLACE`、`DUPLICATE` 关键字
- AST：新增 `ReplaceStatement`，扩展 `InsertStatement` 增加 `onDuplicateKeyUpdate` 子句
- ExecutionEngine：基于 PRIMARY KEY / UNIQUE 判断是 insert 还是 update

**修改文件**: Token → Lexer → AST → Parser → ExecutionEngine → PrettyPrinter → Test

---

### 2.7 多语句批处理

```sql
-- 用分号分隔多条 SQL 一次性执行
CREATE TABLE tmp (id INT);
INSERT INTO tmp VALUES (1);
INSERT INTO tmp VALUES (2);
SELECT * FROM tmp;
DROP TABLE tmp;
```

**实现要点**：
- Parser：顶层 `parseAll(): List[SQLStatement]` 循环按 `;` 拆分
- ExecutionEngine：顺序执行每条语句，收集所有结果
- REPL：支持多语句模式

**修改文件**: Parser → ExecutionEngine → SQLRepl → MySQLParser → Test

---

## 🔧 Phase 3：执行引擎深度增强

> **目标**: 让内存数据库具备真实数据库的核心运行时特性  
> **预计周期**: 3~4 周  
> **难度**: ⭐⭐⭐~⭐⭐⭐⭐

### 3.1 窗口函数执行

当前解析器已完整支持窗口函数语法，但执行引擎尚未实现计算。

```sql
SELECT name, department, salary,
  ROW_NUMBER() OVER (PARTITION BY department ORDER BY salary DESC) AS dept_rank,
  SUM(salary)  OVER (PARTITION BY department) AS dept_total,
  RANK()       OVER (ORDER BY salary DESC) AS global_rank
FROM employees;
```

**实现要点**：
- 按 PARTITION BY 分组，组内按 ORDER BY 排序
- 实现 ROW_NUMBER / RANK / DENSE_RANK / NTILE 的编号逻辑
- 实现聚合窗口函数（SUM/AVG/COUNT/MAX/MIN OVER）
- 实现帧子句（ROWS BETWEEN ... AND ...）滑动窗口计算

**预计行数**: ~200 行  
**修改文件**: ExecutionEngine → Test

---

### 3.2 约束执行（运行时强制）

当前约束仅在解析层支持，执行引擎不做校验。

```sql
CREATE TABLE users (
  id INT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(255) NOT NULL UNIQUE,
  age INT CHECK (age >= 0 AND age <= 150)
);

INSERT INTO users (email, age) VALUES (NULL, 25);         -- ❌ NOT NULL violation
INSERT INTO users (email, age) VALUES ('a@b.com', -1);    -- ❌ CHECK violation
INSERT INTO users (email, age) VALUES ('a@b.com', 25);    -- ✅ OK, id = 1
INSERT INTO users (email, age) VALUES ('a@b.com', 30);    -- ❌ UNIQUE violation
```

**实现要点**：
- `InMemoryTable` 存储列约束元数据
- INSERT/UPDATE 前校验 NOT NULL、UNIQUE、CHECK、PRIMARY KEY
- AUTO_INCREMENT 自动递增生成
- 违反约束时抛出 `ConstraintViolationException`

**预计行数**: ~150 行  
**修改文件**: ExecutionEngine → Test

---

### 3.3 外键引用完整性

```sql
CREATE TABLE departments (id INT PRIMARY KEY, name VARCHAR(100));
CREATE TABLE employees (
  id INT PRIMARY KEY,
  dept_id INT,
  FOREIGN KEY (dept_id) REFERENCES departments(id) ON DELETE CASCADE
);

DELETE FROM departments WHERE id = 1;  -- 级联删除 employees 中 dept_id = 1 的行
```

**实现要点**：
- `InMemoryDatabase` 维护外键关系图
- INSERT 检查引用表中是否存在对应值
- DELETE/UPDATE 根据 `ON DELETE/UPDATE` 动作（CASCADE/SET NULL/RESTRICT/NO ACTION）处理

**预计行数**: ~200 行  
**修改文件**: ExecutionEngine → Test

---

### 3.4 子查询执行增强

```sql
-- 相关子查询（当前未完整支持）
SELECT * FROM employees e
WHERE salary > (SELECT AVG(salary) FROM employees WHERE department = e.department);

-- ALL / ANY / SOME
SELECT * FROM products WHERE price > ALL (SELECT price FROM budget_items);
SELECT * FROM products WHERE price > ANY (SELECT price FROM competitors);
```

**实现要点**：
- Token：新增 `ANY`、`SOME` 关键字
- AST：新增 `AllExpression`、`AnyExpression`
- ExecutionEngine：相关子查询的外行上下文传递

**修改文件**: Token → Lexer → AST → Parser → ExecutionEngine → Test

---

### 3.5 数据类型增强

```sql
-- 日期/时间运算
SELECT DATE_ADD('2026-01-01', INTERVAL 30 DAY);
SELECT DATEDIFF('2026-12-31', '2026-01-01');
SELECT NOW(), CURDATE(), CURTIME();

-- 类型自动转换
SELECT '100' + 50;       -- → 150 (隐式转换)
SELECT CAST('2026-01-01' AS DATE);
```

**实现要点**：
- ExpressionEvaluator：增加日期/时间函数（DATE_ADD/DATE_SUB/DATEDIFF/DATE_FORMAT）
- 隐式类型转换规则（String→Number、String→Date）
- INTERVAL 表达式支持

**修改文件**: Token → AST → Parser → ExecutionEngine → TypeChecker → Test

---

## ⚡ Phase 4：查询优化器进阶

> **目标**: 从基于规则的优化器演进为更智能的优化器  
> **预计周期**: 3~4 周  
> **难度**: ⭐⭐⭐⭐~⭐⭐⭐⭐⭐

### 4.1 统计信息收集

```scala
// 表级统计
case class TableStatistics(
  rowCount: Long,                          // 行数
  columnStats: Map[String, ColumnStats]    // 列级统计
)

// 列级统计
case class ColumnStats(
  distinctCount: Long,    // 不同值数量 (NDV)
  nullCount: Long,        // NULL 值数量
  minValue: Any,          // 最小值
  maxValue: Any,          // 最大值
  avgLength: Double       // 平均长度（字符串）
)
```

**实现要点**：
- `ANALYZE TABLE` 语句触发统计信息收集
- INSERT/UPDATE/DELETE 后自动更新统计
- 统计信息存储在 `InMemoryDatabase` 元数据中

---

### 4.2 物理执行计划

在逻辑计划之上增加物理计划层，选择具体的执行算法：

```
逻辑计划                    物理计划
─────────                  ──────────
ScanPlan          →       FullTableScan / IndexScan
FilterPlan        →       Filter (pushdown to scan)
JoinPlan          →       NestedLoopJoin / HashJoin / SortMergeJoin
AggregatePlan     →       HashAggregate / SortAggregate
SortPlan          →       InMemorySort / ExternalSort
```

**实现要点**：
- 新增 `PhysicalPlan` sealed trait 层次结构
- `PhysicalPlanBuilder`：LogicalPlan → PhysicalPlan 转换
- 基于表大小和统计信息选择算法（小表 NestedLoop，大表 HashJoin）

**预计行数**: ~400 行  
**新增文件**: `PhysicalPlan.scala`

---

### 4.3 Cost-Based Optimizer (CBO)

```scala
// 代价模型
case class PlanCost(
  cpuCost: Double,     // CPU 操作次数
  ioCost: Double,      // I/O 次数（内存数据库中为数组访问次数）
  memoryCost: Double,  // 内存占用
  networkCost: Double  // 网络传输（预留）
) {
  def total: Double = cpuCost + ioCost * 10 + memoryCost * 0.1
}

// 基于代价的优化
object CostBasedOptimizer {
  def optimize(plan: LogicalPlan, stats: TableStatistics): LogicalPlan = {
    val candidates = generateAlternatives(plan)    // 生成候选计划
    candidates.minBy(estimateCost(_, stats))        // 选择代价最低的
  }
}
```

**核心优化**：
- **JOIN 顺序选择**：枚举 JOIN 排列，基于表大小选最优
- **子查询解嵌套**：将 `WHERE x IN (SELECT ...)` 改写为 JOIN
- **索引选择**：有索引时选 IndexScan，否则 FullTableScan

---

### 4.4 新增优化规则

| 规则 | 说明 |
|------|------|
| **DeadCodeElimination** | 移除 `WHERE 1=0` 等永假条件的查询分支 |
| **CommonSubexpressionElimination** | 提取重复子表达式避免重复计算 |
| **JoinReordering** | 基于表大小的 JOIN 顺序重排 |
| **SubqueryUnnesting** | 将相关子查询改写为 JOIN |
| **GroupByPushDown** | 将 GROUP BY 推到 JOIN 之前减少数据量 |

---

## 💾 Phase 5：数据库系统特性

> **目标**: 让项目从"内存引擎"升级为"迷你数据库系统"  
> **预计周期**: 4~6 周  
> **难度**: ⭐⭐⭐~⭐⭐⭐⭐⭐

### 5.1 事务支持（Transaction）

```sql
BEGIN;
INSERT INTO accounts (id, balance) VALUES (1, 1000);
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
COMMIT;

-- 回滚
BEGIN;
DELETE FROM accounts WHERE id = 1;
ROLLBACK;  -- 数据恢复

-- 保存点
BEGIN;
INSERT INTO orders VALUES (1, 'A');
SAVEPOINT sp1;
INSERT INTO orders VALUES (2, 'B');
ROLLBACK TO sp1;  -- 只回滚到 sp1，保留 order 1
COMMIT;
```

**实现要点**：
- Token：新增 `BEGIN`、`COMMIT`、`ROLLBACK`、`SAVEPOINT`、`TRANSACTION` 关键字
- AST：新增 `BeginStatement`、`CommitStatement`、`RollbackStatement`、`SavepointStatement`
- ExecutionEngine：基于快照隔离实现
  - `BEGIN`：拷贝当前数据库状态作为快照
  - `COMMIT`：将修改后的状态替换为当前状态
  - `ROLLBACK`：丢弃修改，恢复快照
  - `SAVEPOINT`：标记中间快照点

**预计行数**: ~250 行  
**修改文件**: Token → Lexer → AST → Parser → ExecutionEngine → Test

---

### 5.2 数据持久化

```scala
// 将数据库保存到文件
database.saveTo("mydb.json")       // JSON 格式
database.saveTo("mydb.bin")        // 二进制格式

// 从文件加载
val db = InMemoryDatabase.loadFrom("mydb.json")

// REPL 中使用
sql> :save mydb.json
✅ Database saved to mydb.json (3 tables, 150 rows)

sql> :load mydb.json
✅ Database loaded from mydb.json
```

**实现要点**：
- 序列化：表结构（Schema）+ 数据（行） → JSON / 自定义二进制格式
- 反序列化：从文件恢复 `InMemoryDatabase` 完整状态
- 自动保存：可选配置定时自动持久化
- WAL（Write-Ahead Log）：可选的预写日志，防止崩溃丢数据

**预计行数**: ~300 行  
**新增文件**: `Persistence.scala`

---

### 5.3 权限与安全

```sql
-- 用户管理
CREATE USER 'admin' IDENTIFIED BY 'password123';
DROP USER 'admin';

-- 权限控制
GRANT SELECT, INSERT ON users TO 'admin';
GRANT ALL ON *.* TO 'admin';
REVOKE INSERT ON users FROM 'admin';

-- 查看权限
SHOW GRANTS FOR 'admin';
```

**实现要点**：
- Token：新增 `GRANT`、`REVOKE`、`USER`、`IDENTIFIED`、`TO`、`PRIVILEGES` 关键字
- `SecurityManager`：维护用户列表和权限矩阵
- 每条 SQL 执行前检查当前用户是否有对应权限

**预计行数**: ~350 行  
**新增文件**: `Security.scala`

---

### 5.4 触发器（Trigger）

```sql
CREATE TRIGGER update_timestamp
BEFORE UPDATE ON users
FOR EACH ROW
SET NEW.updated_at = NOW();

CREATE TRIGGER audit_log
AFTER DELETE ON orders
FOR EACH ROW
INSERT INTO audit (action, table_name, old_data)
VALUES ('DELETE', 'orders', OLD.id);
```

**实现要点**：
- Token：新增 `TRIGGER`、`BEFORE`、`AFTER`、`EACH`、`ROW`、`NEW`、`OLD` 关键字
- AST：新增 `CreateTriggerStatement`、`DropTriggerStatement`
- ExecutionEngine：在 INSERT/UPDATE/DELETE 执行前后触发对应 trigger body

---

### 5.5 游标与流式处理

```sql
-- 在存储过程中使用游标
CREATE PROCEDURE process_all()
BEGIN
  DECLARE done INT DEFAULT 0;
  DECLARE cur CURSOR FOR SELECT * FROM large_table;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
  
  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO @id, @name;
    IF done THEN LEAVE read_loop; END IF;
    -- 处理每一行
  END LOOP;
  CLOSE cur;
END;
```

**实现要点**：
- AST：新增 `DeclareStatement`、`OpenStatement`、`FetchStatement`、`CloseStatement`、`LoopStatement`
- ExecutionEngine：游标作为行迭代器，支持 FETCH NEXT

---

## 🌐 Phase 6：生态工具与对外接口

> **目标**: 从命令行工具演进为可对外提供服务的数据库系统  
> **预计周期**: 4~8 周  
> **难度**: ⭐⭐⭐⭐~⭐⭐⭐⭐⭐

### 6.1 MySQL 协议兼容层

实现 MySQL Wire Protocol 的子集，让标准 MySQL 客户端能连接到本项目：

```bash
# 用标准 MySQL 客户端连接！
$ mysql -h 127.0.0.1 -P 3307 -u root

mysql> SHOW TABLES;
+--------+
| Tables |
+--------+
| users  |
| orders |
+--------+

mysql> SELECT * FROM users;
+----+-------+-----+
| id | name  | age |
+----+-------+-----+
|  1 | Alice |  25 |
|  2 | Bob   |  30 |
+----+-------+-----+
```

**实现要点**：
- TCP Server（Netty / 原生 Java NIO）
- MySQL 协议握手/认证/查询/结果集编解码
- 支持 COM_QUERY、COM_PING、COM_QUIT 等基本命令

**预计行数**: ~800 行  
**新增文件**: `NetworkServer.scala`、`MySQLProtocol.scala`

---

### 6.2 JDBC 驱动

```scala
// Java/Scala 程序通过标准 JDBC API 使用
val conn = DriverManager.getConnection("jdbc:minisql://localhost:3307/mydb")
val stmt = conn.createStatement()
val rs = stmt.executeQuery("SELECT * FROM users WHERE age > 18")
while (rs.next()) {
  println(s"${rs.getString("name")}: ${rs.getInt("age")}")
}
```

**实现要点**：
- 实现 `java.sql.Driver`、`Connection`、`Statement`、`ResultSet` 接口
- 可选：嵌入模式（直接调用 ExecutionEngine，无需网络）

**预计行数**: ~600 行  
**新增文件**: `jdbc/` 包（Driver.scala、Connection.scala、Statement.scala、ResultSet.scala）

---

### 6.3 Web UI 可视化

提供一个浏览器端的 SQL 工作台：

```
┌─────────────────────────────────────────────────────┐
│  🔷 MiniSQL Web Studio                       [⚙️]  │
├─────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────┐    │
│  │ SELECT u.name, COUNT(o.id) AS orders        │    │
│  │ FROM users u LEFT JOIN orders o              │    │
│  │ ON u.id = o.user_id                          │    │
│  │ GROUP BY u.name                              │    │
│  │ ORDER BY orders DESC                         │    │
│  └─────────────────────────────────────────────┘    │
│  [▶ Execute]  [📊 Plan]  [🌳 AST]  [📝 Format]    │
├──────────┬──────────────────────────────────────────┤
│ Tables   │  Results (3 rows, 12ms)                  │
│ ─────    │  ┌──────────┬────────┐                   │
│ 📁 users │  │ name     │ orders │                   │
│ 📁 orders│  ├──────────┼────────┤                   │
│ 📁 prods │  │ Alice    │ 5      │                   │
│          │  │ Bob      │ 3      │                   │
│          │  │ Charlie  │ 1      │                   │
│          │  └──────────┴────────┘                   │
└──────────┴──────────────────────────────────────────┘
```

**实现要点**：
- HTTP Server（http4s / Akka HTTP / 原生 HttpServer）
- REST API：`POST /api/query`、`GET /api/tables`、`GET /api/plan`
- 前端：单页面 HTML + CSS + JavaScript（可选 Scala.js）
- 功能：SQL 编辑器 + 结果表格 + 执行计划可视化 + AST 树形展示

**预计行数**: ~1000 行（后端）+ ~500 行（前端）  
**新增文件**: `web/` 包（WebServer.scala、ApiHandler.scala、static/index.html）

---

### 6.4 SQL 方言支持

```scala
// 支持多种 SQL 方言
val mysqlParser  = new Parser(tokens, dialect = MySQLDialect)
val pgParser     = new Parser(tokens, dialect = PostgreSQLDialect)
val sqliteParser = new Parser(tokens, dialect = SQLiteDialect)
```

**方言差异示例**：

| 特性 | MySQL | PostgreSQL | SQLite |
|------|-------|------------|--------|
| 字符串拼接 | `CONCAT(a, b)` | `a \|\| b` | `a \|\| b` |
| 分页 | `LIMIT n OFFSET m` | `LIMIT n OFFSET m` | `LIMIT n OFFSET m` |
| 布尔 | `TINYINT(1)` | `BOOLEAN` | `INTEGER` |
| 自增 | `AUTO_INCREMENT` | `SERIAL` | `AUTOINCREMENT` |
| 反引号 | `` `name` `` | `"name"` | `` `name` `` 或 `"name"` |

---

### 6.5 SQL 格式化 CLI 工具

```bash
# 格式化单个文件
$ sql-fmt input.sql -o output.sql

# 格式化并原地覆盖
$ sql-fmt --in-place *.sql

# 从 stdin 读取
$ echo "select *from users where age>18" | sql-fmt
SELECT *
FROM users
WHERE age > 18

# 检查格式（CI 模式）
$ sql-fmt --check migrations/*.sql
```

**实现要点**：
- 命令行参数解析（scopt / picocli）
- 文件读写 + glob 匹配
- 格式化配置（缩进宽度、大写关键字、换行策略）
- Exit code：0 = 已格式化 / 1 = 需要格式化（适用于 CI）

---

### 6.6 AST Diff 与 SQL 变更审计

```bash
# 比较两条 SQL 的差异
$ sql-diff "SELECT * FROM users" "SELECT id, name FROM users WHERE age > 18"

 SELECT
-  *
+  id, name
 FROM users
+WHERE
+  age > 18
```

**实现要点**：
- 将两个 AST 树进行节点级 diff
- 输出语义级差异（而非文本级 diff）
- 应用场景：数据库迁移审计、SQL review

---

## 📊 总体进度看板

```
Phase 1：核心解析器          ████████████████████  100%  ✅ 已完成
Phase 2：SQL 语法拓展        ░░░░░░░░░░░░░░░░░░░░    0%  📋 规划中
Phase 3：执行引擎深度增强     ░░░░░░░░░░░░░░░░░░░░    0%  📋 规划中
Phase 4：查询优化器进阶       ░░░░░░░░░░░░░░░░░░░░    0%  📋 规划中
Phase 5：数据库系统特性       ░░░░░░░░░░░░░░░░░░░░    0%  📋 规划中
Phase 6：生态工具与对外接口   ░░░░░░░░░░░░░░░░░░░░    0%  📋 规划中
```

### 各阶段功能统计

| Phase | 功能数 | 预计新增代码行数 | 预计新增测试数 | 难度 |
|-------|--------|-----------------|---------------|------|
| Phase 1 (已完成) | 28 | ~8,500 | 643 | ⭐⭐~⭐⭐⭐⭐⭐ |
| Phase 2 | 7 | ~1,000 | ~100 | ⭐~⭐⭐⭐ |
| Phase 3 | 5 | ~1,200 | ~120 | ⭐⭐⭐~⭐⭐⭐⭐ |
| Phase 4 | 4 | ~1,500 | ~80 | ⭐⭐⭐⭐~⭐⭐⭐⭐⭐ |
| Phase 5 | 5 | ~2,000 | ~150 | ⭐⭐⭐~⭐⭐⭐⭐⭐ |
| Phase 6 | 6 | ~3,500 | ~100 | ⭐⭐⭐⭐~⭐⭐⭐⭐⭐ |
| **合计** | **55** | **~17,700** | **~1,193** | — |

---

## 📅 实施时间线

```
                       2026
         3月              4月              5月              6月              7月
    ─────┼────────────────┼────────────────┼────────────────┼────────────────┼──
         │                │                │                │                │
Phase 1  │████████████████│                │                │                │
已完成 ✅ │ 28项功能完成    │                │                │                │
         │ 643测试通过     │                │                │                │
         │                │                │                │                │
Phase 2  │                │▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│                │                │
SQL拓展   │                │ SHOW/DESCRIBE  │                │                │
         │                │ TRUNCATE       │                │                │
         │                │ INSERT SELECT  │                │                │
         │                │ 扩展JOIN/SET   │                │                │
         │                │                │                │                │
Phase 3  │                │                │▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│▓▓▓▓▓▓▓        │
引擎增强  │                │                │ 窗口函数执行    │ 数据类型增强   │
         │                │                │ 约束/外键执行   │               │
         │                │                │ 子查询增强      │               │
         │                │                │                │                │
Phase 4  │                │                │                │▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│
优化器    │                │                │                │ 统计信息       │
         │                │                │                │ 物理计划       │
         │                │                │                │ CBO            │
         │                │                │                │                │
                                                                       7月+
                                                               ────────────┼──
Phase 5                                                                    │
系统特性                                                        ▓▓▓▓▓▓▓▓▓▓│▓▓▓▓
                                                               事务/持久化  │权限
                                                               触发器/游标  │
                                                                           │
Phase 6                                                                    │
生态工具                                                                    │▓▓▓▓▓▓▓
                                                                           │协议/JDBC
                                                                           │Web UI
```

---

## 📝 修改文件速查

每项新功能通常涉及以下文件（与已有扩展模式一致）：

| 层级 | 文件 | 职责 |
|------|------|------|
| **词法** | `Token.scala` | 新增关键字 Token |
| **词法** | `Lexer.scala` | 注册关键字到 keywords 映射 |
| **语法** | `AST.scala` | 定义新 AST 节点（case class / sealed trait） |
| **语法** | `Parser.scala` | 实现新的解析方法 |
| **语义** | `SemanticAnalyzer.scala` | 新语句的语义检查规则 |
| **语义** | `SemanticVisitor.scala` | Visitor 管道中的语义检查 |
| **类型** | `TypeChecker.scala` | 新表达式/语句的类型推断 |
| **序列化** | `ASTJsonSerializer.scala` | 新 AST 节点的 JSON 序列化 |
| **格式化** | `ASTVisitor.scala` (PrettyPrinter) | 新语句的格式化输出 |
| **执行** | `ExecutionEngine.scala` | 新语句的执行逻辑 |
| **优化** | `QueryPlan.scala` | 新的逻辑计划节点 / 优化规则 |
| **入口** | `MySQLParser.scala` | 统一 API 更新 |
| **REPL** | `SQLRepl.scala` | 新命令/模式支持 |
| **测试** | `MySQLParserTest.scala` | 新功能的测试用例 |

---

## 🏆 里程碑定义

| 里程碑 | 达成条件 | 项目定位 |
|--------|---------|---------|
| **M1** ✅ | Phase 1 完成 | 教学级 SQL 解析器 + 微型内存数据库 |
| **M2** | Phase 1 + 2 完成 | 实用级 SQL 解析器 |
| **M3** | Phase 1~3 完成 | 功能完整的内存数据库引擎 |
| **M4** | Phase 1~4 完成 | 具备智能优化的数据库引擎 |
| **M5** | Phase 1~5 完成 | 迷你关系数据库系统 |
| **M6** | Phase 1~6 完成 | 可对外服务的微型数据库（可被 MySQL 客户端连接） |

---

> **当前状态**: 里程碑 M1 已达成 🎉  
> **下一目标**: M2 — 完成 Phase 2，让 SQL 语法支持更加完整  
> *本文档将随项目进展持续更新。*
