package cn.ce.binlog.mysql.parse;

import org.apache.commons.lang.StringUtils;
import org.apache.oro.text.regex.Perl5Matcher;

import cn.ce.binlog.mysql.event.EventType;

/**
 * 简单的ddl解析工具类，后续可使用cobar/druid的SqlParser进行语法树解析
 * 
 * <pre>
 * 解析支持：
 * a. 带schema: retl.retl_mark
 * b. 带引号` :  `retl.retl_mark`
 * c. 存在换行符： create table \n `retl.retl_mark`
 * </pre>
 * 
 * http://dev.mysql.com/doc/refman/5.6/en/sql-syntax-data-definition.html
 * 
 * @author jianghang 2013-1-22 下午10:03:22
 * @version 1.0.0
 */
public class SimpleDdlParser {

	public static final String CREATE_PATTERN = "^\\s*CREATE\\s*(TEMPORARY)?\\s*TABLE\\s*(.*)$";
	public static final String DROP_PATTERN = "^\\s*DROP\\s*(TEMPORARY)?\\s*TABLE\\s*(.*)$";
	public static final String ALERT_PATTERN = "^\\s*ALTER\\s*(IGNORE)?\\s*TABLE\\s*(.*)$";
	public static final String TRUNCATE_PATTERN = "^\\s*TRUNCATE\\s*(TABLE)?\\s*(.*)$";
	public static final String TABLE_PATTERN = "^(IF\\s*NOT\\s*EXIST[S]\\s*)?(IF\\s*EXIST[S]\\s*)?(`?.+?`?[;\\(\\s]+?)?.*$"; // 采用非贪婪模式
	public static final String INSERT_PATTERN = "^\\s*(INSERT|MERGE|REPLACE)(.*)$";
	public static final String UPDATE_PATTERN = "^\\s*UPDATE(.*)$";
	public static final String DELETE_PATTERN = "^\\s*DELETE(.*)$";
	public static final String RENAME_PATTERN = "^\\s*RENAME\\s*TABLE\\s*(.*?)\\s*TO\\s*(.*?)$";
	/**
	 * <pre>
	 * CREATE [UNIQUE|FULLTEXT|SPATIAL] INDEX index_name
	 *         [index_type]
	 *         ON tbl_name (index_col_name,...)
	 *         [algorithm_option | lock_option] ...
	 *         
	 * http://dev.mysql.com/doc/refman/5.6/en/create-index.html
	 * </pre>
	 */
	public static final String CREATE_INDEX_PATTERN = "^\\s*CREATE\\s*.*?\\s*INDEX\\s*(.*?)\\s*ON\\s*(.*?)$";
	public static final String DROP_INDEX_PATTERN = "^\\s*DROP\\s*INDEX\\s*(.*?)\\s*ON\\s*(.*?)$";

	public static DdlResult parse(String queryString, String schmeaName) {
		DdlResult result = parseDdl(queryString, schmeaName, ALERT_PATTERN, 2);
		if (result != null) {
			result.setType(EventType.ALTER);
			return result;
		}

		result = parseDdl(queryString, schmeaName, CREATE_PATTERN, 2);
		if (result != null) {
			result.setType(EventType.CREATE);
			return result;
		}

		result = parseDdl(queryString, schmeaName, DROP_PATTERN, 2);
		if (result != null) {
			result.setType(EventType.ERASE);
			return result;
		}

		result = parseDdl(queryString, schmeaName, TRUNCATE_PATTERN, 2);
		if (result != null) {
			result.setType(EventType.TRUNCATE);
			return result;
		}

		result = parseRename(queryString, schmeaName, RENAME_PATTERN);
		if (result != null) {
			result.setType(EventType.RENAME);
			return result;
		}

		result = parseDdl(queryString, schmeaName, CREATE_INDEX_PATTERN, 2);
		if (result != null) {
			result.setType(EventType.CINDEX);
			return result;
		}

		result = parseDdl(queryString, schmeaName, DROP_INDEX_PATTERN, 2);
		if (result != null) {
			result.setType(EventType.DINDEX);
			return result;
		}

		result = new DdlResult(schmeaName);
		if (isDml(queryString, INSERT_PATTERN)) {
			result.setType(EventType.INSERT);
			return result;
		}

		if (isDml(queryString, UPDATE_PATTERN)) {
			result.setType(EventType.UPDATE);
			return result;
		}

		if (isDml(queryString, DELETE_PATTERN)) {
			result.setType(EventType.DELETE);
			return result;
		}

		result.setType(EventType.QUERY);
		return result;
	}

	private static DdlResult parseDdl(String queryString, String schmeaName,
			String pattern, int index) {
		Perl5Matcher matcher = new Perl5Matcher();
		if (matcher.matches(queryString, PatternUtils.getPattern(pattern))) {
			DdlResult result = parseTableName(matcher.getMatch().group(index),
					schmeaName);
			return result != null ? result : new DdlResult(schmeaName); // 无法解析时，直接返回schmea，进行兼容处理
		}

		return null;
	}

	private static boolean isDml(String queryString, String pattern) {
		Perl5Matcher matcher = new Perl5Matcher();
		if (matcher.matches(queryString, PatternUtils.getPattern(pattern))) {
			return true;
		} else {
			return false;
		}
	}

	private static DdlResult parseRename(String queryString, String schmeaName,
			String pattern) {
		Perl5Matcher matcher = new Perl5Matcher();
		if (matcher.matches(queryString, PatternUtils.getPattern(pattern))) {
			DdlResult orign = parseTableName(matcher.getMatch().group(1),
					schmeaName);
			DdlResult target = parseTableName(matcher.getMatch().group(2),
					schmeaName);
			if (orign != null && target != null) {
				return new DdlResult(target.getSchemaName(),
						target.getTableName(), orign.getSchemaName(),
						orign.getTableName());
			}
		}

		return null;
	}

	private static DdlResult parseTableName(String matchString,
			String schmeaName) {
		Perl5Matcher tableMatcher = new Perl5Matcher();
		matchString = matchString + " ";
		if (tableMatcher.matches(matchString,
				PatternUtils.getPattern(TABLE_PATTERN))) {
			String tableString = tableMatcher.getMatch().group(3);

			tableString = StringUtils.removeEnd(tableString, ";");
			tableString = StringUtils.removeEnd(tableString, "(");
			tableString = StringUtils.trim(tableString);
			// 特殊处理引号`
			tableString = removeEscape(tableString);
			// 处理schema.table的写法
			String names[] = StringUtils.split(tableString, ".");
			if (names != null && names.length > 1) {
				return new DdlResult(removeEscape(names[0]),
						removeEscape(names[1]));
			} else {
				if (schmeaName == null || names[0] == null) {
					return null;
				}else{
					return new DdlResult(schmeaName, removeEscape(names[0]));
				}
				
			}
		}

		return null;
	}

	private static String removeEscape(String str) {
		String result = StringUtils.removeEnd(str, "`");
		result = StringUtils.removeStart(result, "`");
		return result;
	}

	public static class DdlResult {

		private String schemaName;
		private String tableName;
		private String oriSchemaName; // rename ddl中的源表
		private String oriTableName; // rename ddl中的目标表
		private EventType type;

		public DdlResult() {
		}

		public DdlResult(String schemaName) {
			this.schemaName = schemaName;
		}

		public DdlResult(String schemaName, String tableName) {
			this.schemaName = schemaName;
			this.tableName = tableName;
		}

		public DdlResult(String schemaName, String tableName,
				String oriSchemaName, String oriTableName) {
			this.schemaName = schemaName;
			this.tableName = tableName;
			this.oriSchemaName = oriSchemaName;
			this.oriTableName = oriTableName;
		}

		public String getSchemaName() {
			return schemaName;
		}

		public void setSchemaName(String schemaName) {
			this.schemaName = schemaName;
		}

		public String getTableName() {
			return tableName;
		}

		public void setTableName(String tableName) {
			this.tableName = tableName;
		}

		public EventType getType() {
			return type;
		}

		public void setType(EventType type) {
			this.type = type;
		}

		public String getOriSchemaName() {
			return oriSchemaName;
		}

		public void setOriSchemaName(String oriSchemaName) {
			this.oriSchemaName = oriSchemaName;
		}

		public String getOriTableName() {
			return oriTableName;
		}

		public void setOriTableName(String oriTableName) {
			this.oriTableName = oriTableName;
		}

		@Override
		public String toString() {
			return "DdlResult [schemaName=" + schemaName + ", tableName="
					+ tableName + ", oriSchemaName=" + oriSchemaName
					+ ", oriTableName=" + oriTableName + ", type=" + type + "]";
		}

	}
}
