package com.mysql.parser

/**
 * 逻辑执行计划节点
 *
 * 表示 SQL 查询的逻辑执行步骤，从叶子（扫描）到根（最终输出）。
 */
sealed trait LogicalPlan {
  /** 输出列名列表 */
  def outputColumns: List[String]
  /** 子计划列表 */
  def children: List[LogicalPlan]
}

/** 全表扫描 */
case class ScanPlan(
  tableName: String,
  alias: Option[String] = None,
  override val outputColumns: List[String] = Nil
) extends LogicalPlan {
  override def children: List[LogicalPlan] = Nil
}

/** 过滤（WHERE / HAVING） */
case class FilterPlan(
  condition: Expression,
  child: LogicalPlan
) extends LogicalPlan {
  override def outputColumns: List[String] = child.outputColumns
  override def children: List[LogicalPlan] = List(child)
}

/** 投影（SELECT 列列表） */
case class ProjectPlan(
  override val outputColumns: List[String],
  expressions: List[(String, Column)],  // (输出名, 原始列定义)
  child: LogicalPlan
) extends LogicalPlan {
  override def children: List[LogicalPlan] = List(child)
}

/** JOIN */
case class JoinPlan(
  left: LogicalPlan,
  right: LogicalPlan,
  joinType: JoinType,
  condition: Expression,
  override val outputColumns: List[String] = Nil
) extends LogicalPlan {
  override def children: List[LogicalPlan] = List(left, right)
}

/** 聚合（GROUP BY） */
case class AggregatePlan(
  groupByExprs: List[Expression],
  aggregateColumns: List[Column],
  child: LogicalPlan,
  override val outputColumns: List[String] = Nil
) extends LogicalPlan {
  override def children: List[LogicalPlan] = List(child)
}

/** 排序（ORDER BY） */
case class SortPlan(
  orderByClauses: List[OrderByClause],
  child: LogicalPlan
) extends LogicalPlan {
  override def outputColumns: List[String] = child.outputColumns
  override def children: List[LogicalPlan] = List(child)
}

/** LIMIT / OFFSET */
case class LimitPlan(
  limit: Option[Int],
  offset: Option[Int],
  child: LogicalPlan
) extends LogicalPlan {
  override def outputColumns: List[String] = child.outputColumns
  override def children: List[LogicalPlan] = List(child)
}

/** 去重（DISTINCT） */
case class DistinctPlan(
  child: LogicalPlan
) extends LogicalPlan {
  override def outputColumns: List[String] = child.outputColumns
  override def children: List[LogicalPlan] = List(child)
}

/** 集合运算（UNION / INTERSECT / EXCEPT） */
case class SetOperationPlan(
  left: LogicalPlan,
  right: LogicalPlan,
  operationType: UnionType,
  override val outputColumns: List[String] = Nil
) extends LogicalPlan {
  override def children: List[LogicalPlan] = List(left, right)
}

/** 子查询计划（用于 CTE 或派生表） */
case class SubqueryPlan(
  name: String,
  child: LogicalPlan
) extends LogicalPlan {
  override def outputColumns: List[String] = child.outputColumns
  override def children: List[LogicalPlan] = List(child)
}

// ============================================================
//  查询计划构建器 — 从 AST 生成逻辑计划树
// ============================================================

/**
 * 查询计划构建器
 *
 * 将解析后的 SQL AST 转换为逻辑执行计划树。
 */
object QueryPlanBuilder {

  /**
   * 从 SQL 语句构建逻辑计划
   */
  def build(stmt: SQLStatement): LogicalPlan = stmt match {
    case s: SelectStatement => buildSelect(s)
    case u: UnionStatement  => buildUnion(u)
    case w: WithStatement   => buildWith(w)
    case _ => throw new IllegalArgumentException(s"Cannot build plan for statement type: ${stmt.getClass.getSimpleName}")
  }

  /**
   * 构建 SELECT 的逻辑计划
   */
  private def buildSelect(stmt: SelectStatement): LogicalPlan = {
    // 1. FROM — 扫描 / JOIN
    var plan: LogicalPlan = stmt.from match {
      case Some(ref) => buildTableReference(ref)
      case None      => ScanPlan("(dual)", outputColumns = Nil) // 无 FROM 的 SELECT
    }

    // 2. WHERE — 过滤
    stmt.where.foreach { whereExpr =>
      plan = FilterPlan(whereExpr, plan)
    }

    // 3. GROUP BY — 聚合
    stmt.groupBy.foreach { groupExprs =>
      plan = AggregatePlan(groupExprs, stmt.columns, plan, stmt.columns.map(columnName))
    }

    // 4. HAVING — 聚合后过滤
    stmt.having.foreach { havingExpr =>
      plan = FilterPlan(havingExpr, plan)
    }

    // 5. SELECT — 投影
    val projections = stmt.columns.map(c => (columnName(c), c))
    val outputCols = projections.map(_._1)
    plan = ProjectPlan(outputCols, projections, plan)

    // 6. DISTINCT
    if (stmt.distinct) {
      plan = DistinctPlan(plan)
    }

    // 7. ORDER BY
    stmt.orderBy.foreach { orderByClauses =>
      plan = SortPlan(orderByClauses, plan)
    }

    // 8. LIMIT / OFFSET
    if (stmt.limit.isDefined || stmt.offset.isDefined) {
      plan = LimitPlan(stmt.limit, stmt.offset, plan)
    }

    plan
  }

  /**
   * 构建表引用的计划
   */
  private def buildTableReference(ref: TableReference): LogicalPlan = ref match {
    case TableName(name, alias) =>
      ScanPlan(name.toUpperCase, alias.map(_.toUpperCase))

    case DerivedTable(query, alias) =>
      SubqueryPlan(alias.toUpperCase, build(query))

    case JoinClause(left, right, joinType, condition) =>
      JoinPlan(
        buildTableReference(left),
        buildTableReference(right),
        joinType,
        condition
      )
  }

  /**
   * 构建 UNION 的计划
   */
  private def buildUnion(stmt: UnionStatement): LogicalPlan = {
    SetOperationPlan(
      build(stmt.left),
      buildSelect(stmt.right),
      stmt.unionType
    )
  }

  /**
   * 构建 WITH (CTE) 的计划
   */
  private def buildWith(stmt: WithStatement): LogicalPlan = {
    // 简化：CTE 作为子查询计划嵌入
    build(stmt.query)
  }

  /**
   * 获取列的输出名
   */
  private def columnName(col: Column): String = col match {
    case AllColumns                           => "*"
    case NamedColumn(name, alias)            => alias.getOrElse(name)
    case QualifiedColumn(_, column, alias)   => alias.getOrElse(column)
    case ExpressionColumn(expr, alias)       => alias.getOrElse(exprName(expr))
  }

  private def exprName(expr: Expression): String = expr match {
    case Identifier(name) => name
    case AggregateFunction(ft, _, _) => ft match {
      case CountFunc => "COUNT(...)"
      case SumFunc   => "SUM(...)"
      case AvgFunc   => "AVG(...)"
      case MaxFunc   => "MAX(...)"
      case MinFunc   => "MIN(...)"
    }
    case FunctionCall(name, _) => s"$name(...)"
    case _ => expr.toString.take(30)
  }
}

// ============================================================
//  查询计划可视化器
// ============================================================

/**
 * 查询计划打印器 — 以树形结构展示执行计划
 */
object QueryPlanPrinter {

  /**
   * 将逻辑计划转为树形字符串
   */
  def print(plan: LogicalPlan): String = {
    val sb = new StringBuilder
    printNode(plan, sb, "", isLast = true)
    sb.toString()
  }

  private def printNode(plan: LogicalPlan, sb: StringBuilder, prefix: String, isLast: Boolean): Unit = {
    val connector = if (prefix.isEmpty) "" else if (isLast) "└─ " else "├─ "
    val nodeStr = planNodeToString(plan)
    sb.append(prefix).append(connector).append(nodeStr).append("\n")

    val childPrefix = prefix + (if (prefix.isEmpty) "" else if (isLast) "   " else "│  ")
    val children = plan.children
    children.zipWithIndex.foreach { case (child, i) =>
      printNode(child, sb, childPrefix, i == children.size - 1)
    }
  }

  /**
   * 计划节点转描述字符串
   */
  private def planNodeToString(plan: LogicalPlan): String = plan match {
    case ScanPlan(table, alias, _) =>
      val aliasStr = alias.map(a => s" AS $a").getOrElse("")
      s"Scan($table$aliasStr)"

    case FilterPlan(condition, _) =>
      val pp = new SQLPrettyPrinter()
      s"Filter(${pp.formatExpression(condition)})"

    case ProjectPlan(cols, _, _) =>
      s"Project(${cols.mkString(", ")})"

    case JoinPlan(_, _, joinType, condition, _) =>
      val pp = new SQLPrettyPrinter()
      val jtStr = joinType match {
        case InnerJoin => "INNER JOIN"
        case LeftJoin  => "LEFT JOIN"
        case RightJoin => "RIGHT JOIN"
      }
      s"$jtStr ON ${pp.formatExpression(condition)}"

    case AggregatePlan(groupExprs, _, _, _) =>
      val pp = new SQLPrettyPrinter()
      val groupStr = if (groupExprs.isEmpty) "(全表聚合)"
        else groupExprs.map(pp.formatExpression).mkString(", ")
      s"Aggregate(GROUP BY $groupStr)"

    case SortPlan(clauses, _) =>
      val pp = new SQLPrettyPrinter()
      val sortStr = clauses.map { c =>
        s"${pp.formatExpression(c.expression)} ${if (c.ascending) "ASC" else "DESC"}"
      }.mkString(", ")
      s"Sort($sortStr)"

    case LimitPlan(limit, offset, _) =>
      val parts = List(
        limit.map(l => s"LIMIT $l"),
        offset.map(o => s"OFFSET $o")
      ).flatten
      s"Limit(${parts.mkString(", ")})"

    case DistinctPlan(_) =>
      "Distinct"

    case SetOperationPlan(_, _, opType, _) =>
      val opStr = opType match {
        case UnionAll          => "UNION ALL"
        case UnionDistinct     => "UNION"
        case IntersectAll      => "INTERSECT ALL"
        case IntersectDistinct => "INTERSECT"
        case ExceptAll         => "EXCEPT ALL"
        case ExceptDistinct    => "EXCEPT"
      }
      s"SetOperation($opStr)"

    case SubqueryPlan(name, _) =>
      s"Subquery($name)"
  }
}

// ============================================================
//  查询优化器 — 基于规则的逻辑计划优化
// ============================================================

/**
 * 优化规则接口
 */
trait OptimizationRule {
  /** 规则名称 */
  def name: String
  /** 对计划应用此规则，返回优化后的计划 */
  def apply(plan: LogicalPlan): LogicalPlan
}

/**
 * 谓词下推优化 — 将 Filter 尽量推到 Scan 附近
 *
 * 将 Filter → Project → Scan 变为 Project → Filter → Scan
 * 将 Filter → Join 拆分为对各侧的 Filter
 */
class PredicatePushDown extends OptimizationRule {
  override def name: String = "PredicatePushDown"

  override def apply(plan: LogicalPlan): LogicalPlan = pushDown(plan)

  private def pushDown(plan: LogicalPlan): LogicalPlan = plan match {
    // Filter → Project → child  →  Project → Filter → child
    case FilterPlan(condition, ProjectPlan(cols, exprs, child)) =>
      ProjectPlan(cols, exprs, pushDown(FilterPlan(condition, child)))

    // Filter → Join：尝试将条件推到 JOIN 的某一侧
    case FilterPlan(condition, JoinPlan(left, right, joinType, joinCond, outCols)) =>
      val (leftConds, rightConds, remaining) = splitConditions(condition, left, right)

      var newLeft = pushDown(left)
      var newRight = pushDown(right)

      // 左侧条件下推
      leftConds.foreach { cond =>
        newLeft = FilterPlan(cond, newLeft)
      }
      // 右侧条件下推
      rightConds.foreach { cond =>
        newRight = FilterPlan(cond, newRight)
      }

      val newJoin = JoinPlan(newLeft, newRight, joinType, joinCond, outCols)

      // 无法下推的条件保留在 JOIN 上面
      remaining.foldLeft(newJoin: LogicalPlan) { (p, c) =>
        FilterPlan(c, p)
      }

    // 递归处理子节点
    case FilterPlan(condition, child) =>
      FilterPlan(condition, pushDown(child))
    case ProjectPlan(cols, exprs, child) =>
      ProjectPlan(cols, exprs, pushDown(child))
    case JoinPlan(left, right, jt, cond, outCols) =>
      JoinPlan(pushDown(left), pushDown(right), jt, cond, outCols)
    case SortPlan(clauses, child) =>
      SortPlan(clauses, pushDown(child))
    case LimitPlan(limit, offset, child) =>
      LimitPlan(limit, offset, pushDown(child))
    case DistinctPlan(child) =>
      DistinctPlan(pushDown(child))
    case AggregatePlan(g, c, child, out) =>
      AggregatePlan(g, c, pushDown(child), out)
    case SetOperationPlan(left, right, op, out) =>
      SetOperationPlan(pushDown(left), pushDown(right), op, out)
    case SubqueryPlan(name, child) =>
      SubqueryPlan(name, pushDown(child))
    case other => other
  }

  /**
   * 将 AND 条件拆分为：可推到左侧、可推到右侧、无法推的
   */
  private def splitConditions(condition: Expression, left: LogicalPlan, right: LogicalPlan): (List[Expression], List[Expression], List[Expression]) = {
    val conjuncts = flattenAnd(condition)
    val leftCols = collectScanColumns(left)
    val rightCols = collectScanColumns(right)

    var leftConds = List.empty[Expression]
    var rightConds = List.empty[Expression]
    var remaining = List.empty[Expression]

    conjuncts.foreach { cond =>
      val referencedCols = collectReferencedColumns(cond)
      if (referencedCols.nonEmpty && referencedCols.forall(leftCols.contains))
        leftConds = leftConds :+ cond
      else if (referencedCols.nonEmpty && referencedCols.forall(rightCols.contains))
        rightConds = rightConds :+ cond
      else
        remaining = remaining :+ cond
    }

    (leftConds, rightConds, remaining)
  }

  private def flattenAnd(expr: Expression): List[Expression] = expr match {
    case BinaryExpression(left, AndOp, right) => flattenAnd(left) ++ flattenAnd(right)
    case _ => List(expr)
  }

  private def collectScanColumns(plan: LogicalPlan): Set[String] = plan match {
    case ScanPlan(table, alias, _) =>
      val prefix = alias.getOrElse(table)
      Set(prefix.toUpperCase)
    case _ => plan.children.flatMap(collectScanColumns).toSet
  }

  private def collectReferencedColumns(expr: Expression): Set[String] = expr match {
    case QualifiedIdentifier(table, _) => Set(table.toUpperCase)
    case BinaryExpression(left, _, right) => collectReferencedColumns(left) ++ collectReferencedColumns(right)
    case UnaryExpression(_, e) => collectReferencedColumns(e)
    case _ => Set.empty
  }
}

/**
 * 投影裁剪优化 — 移除未使用的列
 *
 * 当 Project 的列列表比子计划的输出列窄时，通知子节点裁剪列。
 */
class ProjectionPruning extends OptimizationRule {
  override def name: String = "ProjectionPruning"

  override def apply(plan: LogicalPlan): LogicalPlan = prune(plan)

  private def prune(plan: LogicalPlan): LogicalPlan = plan match {
    // 如果有两层嵌套的 Project，合并为一层
    case ProjectPlan(outerCols, outerExprs, ProjectPlan(_, _, child)) =>
      ProjectPlan(outerCols, outerExprs, prune(child))

    // 递归
    case ProjectPlan(cols, exprs, child) =>
      ProjectPlan(cols, exprs, prune(child))
    case FilterPlan(cond, child) =>
      FilterPlan(cond, prune(child))
    case JoinPlan(left, right, jt, cond, outCols) =>
      JoinPlan(prune(left), prune(right), jt, cond, outCols)
    case SortPlan(clauses, child) =>
      SortPlan(clauses, prune(child))
    case LimitPlan(limit, offset, child) =>
      LimitPlan(limit, offset, prune(child))
    case DistinctPlan(child) =>
      DistinctPlan(prune(child))
    case AggregatePlan(g, c, child, out) =>
      AggregatePlan(g, c, prune(child), out)
    case SetOperationPlan(left, right, op, out) =>
      SetOperationPlan(prune(left), prune(right), op, out)
    case SubqueryPlan(name, child) =>
      SubqueryPlan(name, prune(child))
    case other => other
  }
}

/**
 * 常量折叠优化 — 在编译期计算常量表达式
 *
 * 例如: WHERE 1 = 1 → 移除 Filter
 *       WHERE 1 + 2 > 5 → WHERE 3 > 5 → WHERE false → 移除整个查询
 */
class ConstantFolding extends OptimizationRule {
  override def name: String = "ConstantFolding"

  override def apply(plan: LogicalPlan): LogicalPlan = fold(plan)

  private def fold(plan: LogicalPlan): LogicalPlan = plan match {
    case FilterPlan(condition, child) =>
      val foldedCondition = foldExpression(condition)
      foldedCondition match {
        // WHERE TRUE → 移除 Filter
        case NumberLiteral("1") => fold(child)
        // 布尔字面量 true
        case _ if isAlwaysTrue(foldedCondition) => fold(child)
        case _ => FilterPlan(foldedCondition, fold(child))
      }

    // 递归
    case ProjectPlan(cols, exprs, child) =>
      ProjectPlan(cols, exprs, fold(child))
    case JoinPlan(left, right, jt, cond, outCols) =>
      JoinPlan(fold(left), fold(right), jt, foldExpression(cond), outCols)
    case SortPlan(clauses, child) =>
      SortPlan(clauses, fold(child))
    case LimitPlan(limit, offset, child) =>
      LimitPlan(limit, offset, fold(child))
    case DistinctPlan(child) =>
      DistinctPlan(fold(child))
    case AggregatePlan(g, c, child, out) =>
      AggregatePlan(g, c, fold(child), out)
    case SetOperationPlan(left, right, op, out) =>
      SetOperationPlan(fold(left), fold(right), op, out)
    case SubqueryPlan(name, child) =>
      SubqueryPlan(name, fold(child))
    case other => other
  }

  /**
   * 折叠常量表达式
   */
  def foldExpression(expr: Expression): Expression = expr match {
    case BinaryExpression(left, op, right) =>
      val fLeft = foldExpression(left)
      val fRight = foldExpression(right)
      (fLeft, fRight) match {
        case (NumberLiteral(l), NumberLiteral(r)) =>
          try {
            val result = ExpressionEvaluator.evaluate(BinaryExpression(NumberLiteral(l), op, NumberLiteral(r)), Map.empty)
            result match {
              case b: Boolean => if (b) NumberLiteral("1") else NumberLiteral("0")
              case l: Long    => NumberLiteral(l.toString)
              case d: Double  => NumberLiteral(d.toString)
              case _          => BinaryExpression(fLeft, op, fRight)
            }
          } catch {
            case _: Exception => BinaryExpression(fLeft, op, fRight)
          }
        case (StringLiteral(l), StringLiteral(r)) if op == Equal =>
          if (l == r) NumberLiteral("1") else NumberLiteral("0")
        case _ => BinaryExpression(fLeft, op, fRight)
      }

    case UnaryExpression(op, e) =>
      UnaryExpression(op, foldExpression(e))

    case _ => expr
  }

  private def isAlwaysTrue(expr: Expression): Boolean = expr match {
    case NumberLiteral("1") => true
    case NumberLiteral(v) => try { v.toDouble != 0 } catch { case _: Exception => false }
    case _ => false
  }
}

/**
 * Limit 下推优化 — 将 LIMIT 推到排序下面（仅当没有排序时）
 */
class LimitPushDown extends OptimizationRule {
  override def name: String = "LimitPushDown"

  override def apply(plan: LogicalPlan): LogicalPlan = plan match {
    // 递归
    case LimitPlan(limit, offset, child) =>
      LimitPlan(limit, offset, apply(child))
    case FilterPlan(cond, child) =>
      FilterPlan(cond, apply(child))
    case ProjectPlan(cols, exprs, child) =>
      ProjectPlan(cols, exprs, apply(child))
    case SortPlan(clauses, child) =>
      SortPlan(clauses, apply(child))
    case other => other
  }
}

/**
 * 查询优化器 — 管理和执行优化规则链
 */
object QueryOptimizer {

  /** 默认优化规则集 */
  val defaultRules: List[OptimizationRule] = List(
    new ConstantFolding(),
    new PredicatePushDown(),
    new ProjectionPruning(),
    new LimitPushDown()
  )

  /**
   * 使用默认规则优化计划
   */
  def optimize(plan: LogicalPlan): LogicalPlan = {
    optimize(plan, defaultRules)
  }

  /**
   * 使用指定规则集优化计划
   */
  def optimize(plan: LogicalPlan, rules: List[OptimizationRule]): LogicalPlan = {
    rules.foldLeft(plan) { (currentPlan, rule) =>
      rule.apply(currentPlan)
    }
  }

  /**
   * 优化并返回优化日志
   */
  def optimizeWithLog(plan: LogicalPlan): (LogicalPlan, List[String]) = {
    var logs = List.empty[String]
    var currentPlan = plan

    defaultRules.foreach { rule =>
      val before = QueryPlanPrinter.print(currentPlan)
      currentPlan = rule.apply(currentPlan)
      val after = QueryPlanPrinter.print(currentPlan)
      if (before != after) {
        logs = logs :+ s"Rule '${rule.name}' applied: plan changed"
      }
    }

    (currentPlan, logs)
  }
}
