package com.mysql.parser

/**
 * 数据库 Schema 模型 — 用于语义分析阶段的元数据定义
 *
 * Schema 提供了数据库中表和列的结构信息，
 * 语义分析器据此验证 SQL 语句中的引用是否合法。
 */

/**
 * 列 Schema — 描述表中一列的元数据
 *
 * @param name     列名（大写标准化）
 * @param dataType 数据类型名称（如 "INT", "VARCHAR", "TEXT"）
 * @param nullable 是否允许 NULL 值
 */
case class ColumnSchema(
  name: String,
  dataType: String = "UNKNOWN",
  nullable: Boolean = true
)

/**
 * 表 Schema — 描述一张表的元数据
 *
 * @param name    表名（大写标准化）
 * @param columns 列定义列表
 */
case class TableSchema(
  name: String,
  columns: List[ColumnSchema]
) {
  /** 列名集合（大写），用于快速查找 */
  lazy val columnNames: Set[String] = columns.map(_.name.toUpperCase).toSet

  /** 按列名查找列 Schema */
  def findColumn(colName: String): Option[ColumnSchema] =
    columns.find(_.name.equalsIgnoreCase(colName))

  /** 判断是否包含指定列 */
  def hasColumn(colName: String): Boolean =
    columnNames.contains(colName.toUpperCase)
}

/**
 * 数据库 Schema — 所有表的集合
 *
 * @param tables 表名(大写) → 表定义 的映射
 */
case class DatabaseSchema(
  tables: Map[String, TableSchema]
) {
  /** 按表名查找表 Schema */
  def findTable(tableName: String): Option[TableSchema] =
    tables.get(tableName.toUpperCase)

  /** 判断是否包含指定表 */
  def hasTable(tableName: String): Boolean =
    tables.contains(tableName.toUpperCase)
}

object DatabaseSchema {
  /** 创建空 Schema */
  def empty: DatabaseSchema = new DatabaseSchema(Map.empty[String, TableSchema])

  /**
   * 便捷构建方法：从 TableSchema 列表创建 DatabaseSchema
   */
  def apply(tableList: TableSchema*): DatabaseSchema =
    DatabaseSchema(tableList.map(t => t.name.toUpperCase -> t).toMap)
}

object TableSchema {
  /**
   * 便捷构建方法：用列名列表快速创建表
   * 所有列默认类型为 "UNKNOWN"、可 NULL
   */
  def simple(name: String, columnNames: String*): TableSchema =
    TableSchema(
      name = name.toUpperCase,
      columns = columnNames.map(c => ColumnSchema(c.toUpperCase)).toList
    )
}
