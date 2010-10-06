package ru.orangesoftware.financisto.db.repository;

import static ru.orangesoftware.financisto.db.DatabaseHelper.CATEGORY_ATTRIBUTE_TABLE;
import static ru.orangesoftware.financisto.db.DatabaseHelper.CATEGORY_TABLE;
import static ru.orangesoftware.financisto.db.DatabaseHelper.TRANSACTION_TABLE;
import static ru.orangesoftware.financisto.db.DatabaseHelper.V_CATEGORY;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import ru.orangesoftware.financisto.db.DatabaseHelper.CategoryAttributeColumns;
import ru.orangesoftware.financisto.db.DatabaseHelper.CategoryColumns;
import ru.orangesoftware.financisto.db.DatabaseHelper.CategoryViewColumns;
import ru.orangesoftware.financisto.db.DatabaseHelper.TransactionColumns;
import ru.orangesoftware.financisto.model.Attribute;
import ru.orangesoftware.financisto.model.Category;
import ru.orangesoftware.financisto.model.CategoryTree;
import ru.orangesoftware.financisto.model.CategoryTree.NodeCreator;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public class CategoryRepository {

	private final SQLiteDatabase db;
	
	/*default*/ CategoryRepository(SQLiteDatabase db) {
		this.db = db;
	}
	
	public long insertOrUpdate(Category category, ArrayList<Attribute> attributes) {
		db.beginTransaction();
		try {
			long id;
			if (category.id == -1) {
				id = insertCategory(category);
			} else {
				updateCategory(category);
				id = category.id;
			}
			addAttributes(id, attributes);
			db.setTransactionSuccessful();
			return id;
		} finally {
			db.endTransaction();
		}
	}
	
	private void addAttributes(long categoryId, ArrayList<Attribute> attributes) {
		db.delete(CATEGORY_ATTRIBUTE_TABLE, CategoryAttributeColumns.CATEGORY_ID+"=?", new String[]{String.valueOf(categoryId)});
		ContentValues values = new ContentValues();
		values.put(CategoryAttributeColumns.CATEGORY_ID, categoryId);
		for (Attribute a : attributes) {
			values.put(CategoryAttributeColumns.ATTRIBUTE_ID, a.id);
			db.insert(CATEGORY_ATTRIBUTE_TABLE, null, values);
		}
	}

	private long insertCategory(Category category) {	
		long parentId = category.getParentId();
		String categoryTitle = category.title; 
		List<Category> subordinates = getSubordinates(parentId);
		if (subordinates.isEmpty()) {
			return insertChildCategory(parentId, categoryTitle);
		} else {
			long mateId = -1;
			for (Category c : subordinates) {
				if (categoryTitle.compareTo(c.title) <= 0) {
					break;
				}
				mateId = c.id;
			}
			if (mateId == -1) {
				return insertChildCategory(parentId, categoryTitle);
			} else {
				return insertMateCategory(mateId, categoryTitle);
			}
		}
	}

	private long updateCategory(Category category) {
		Category oldCategory = getCategory(category.id);
		if (oldCategory.getParentId() == category.getParentId()) {
			updateCategory(category.id, category.title);
		} else {
			moveCategory(category.id, category.getParentId(), category.title);
		}
		return category.id;
	}

	private static final String GET_PARENT_SQL = "(SELECT "
		+ "parent."+CategoryColumns.ID+" AS "+CategoryColumns.ID
		+ " FROM "
		+ CATEGORY_TABLE+" AS node"+","
		+ CATEGORY_TABLE+" AS parent "
		+" WHERE "
		+" node."+CategoryColumns.LEFT+" BETWEEN parent."+CategoryColumns.LEFT+" AND parent."+CategoryColumns.RIGHT
		+" AND node."+CategoryColumns.ID+"=?"
		+" AND parent."+CategoryColumns.ID+"!=?"
		+" ORDER BY parent."+CategoryColumns.LEFT+" DESC)";
	
	public Category getCategory(long id) {
		Cursor c = db.query(V_CATEGORY, CategoryViewColumns.NORMAL_PROJECTION, 
				CategoryViewColumns.ID+"=?", new String[]{String.valueOf(id)}, null, null, null);
		try {
			if (c.moveToNext()) {				
				Category cat = new Category();
				cat.id = id;
				cat.title = c.getString(CategoryViewColumns.Indicies.TITLE);
				cat.level = c.getInt(CategoryViewColumns.Indicies.LEVEL);
				cat.left = c.getInt(CategoryViewColumns.Indicies.LEFT);
				cat.right = c.getInt(CategoryViewColumns.Indicies.RIGHT);
				String s = String.valueOf(id); 
				Cursor c2 = db.query(GET_PARENT_SQL, new String[]{CategoryColumns.ID}, null, new String[]{s,s}, 
						null, null, null, "1");
				try {
					if (c2.moveToFirst()) {
						cat.parent = new Category(c2.getLong(0));
					}
				} finally {
					c2.close();
				}
				return cat;
			} else {
				return new Category(-1);
			}
		} finally {
			c.close();
		}
	}

	public Category getCategoryByLeft(long left) {
		Cursor c = db.query(V_CATEGORY, CategoryViewColumns.NORMAL_PROJECTION, 
				CategoryViewColumns.LEFT+"=?", new String[]{String.valueOf(left)}, null, null, null);
		try {
			if (c.moveToNext()) {				
				return Category.formCursor(c);
			} else {
				return new Category(-1);
			}
		} finally {
			c.close();
		}
	}

	public CategoryTree<Category> getAllCategoriesTree(boolean includeNoCategory) {
		Cursor c = getAllCategories(includeNoCategory);
		try { 
			CategoryTree<Category> tree = CategoryTree.createFromCursor(c, new NodeCreator<Category>(){
				@Override
				public Category createNode(Cursor c) {
					return Category.formCursor(c);
				}				
			});
			return tree;
		} finally {
			c.close();
		}
	}
	
	public HashMap<Long, Category> getAllCategoriesMap(boolean includeNoCategory) {
		return getAllCategoriesTree(includeNoCategory).asMap();
	}

	public ArrayList<Category> getAllCategoriesList(boolean includeNoCategory) {
		ArrayList<Category> list = new ArrayList<Category>();
		Cursor c = getAllCategories(includeNoCategory);
		try { 
			while (c.moveToNext()) {
				Category category = Category.formCursor(c);
				list.add(category);
			}
		} finally {
			c.close();
		}
		return list;
	}

	public Cursor getAllCategories(boolean includeNoCategory) {
		return db.query(V_CATEGORY, CategoryViewColumns.NORMAL_PROJECTION, 
				includeNoCategory ? null : CategoryViewColumns.ID+"!=0", null, null, null, null);
	}
	
	public Cursor getAllCategoriesWithoutSubtree(long id) {
		long left = 0, right = 0;
		Cursor c = db.query(CATEGORY_TABLE, new String[]{CategoryColumns.LEFT, CategoryColumns.RIGHT}, 
				CategoryColumns.ID+"=?", new String[]{String.valueOf(id)}, null, null, null);
		try {
			if (c.moveToFirst()) {
				left = c.getLong(0);
				right = c.getLong(1);
			}
		} finally {
			c.close();
		}
		return db.query(V_CATEGORY, CategoryViewColumns.NORMAL_PROJECTION, 
				"NOT ("+CategoryViewColumns.LEFT+">="+left+" AND "+CategoryColumns.RIGHT+"<="+right+")", null, null, null, null);
	}

	private static final String INSERT_CATEGORY_UPDATE_RIGHT = "UPDATE "+CATEGORY_TABLE+" SET "+CategoryColumns.RIGHT+"="+CategoryColumns.RIGHT+"+2 WHERE "+CategoryColumns.RIGHT+">?";
	private static final String INSERT_CATEGORY_UPDATE_LEFT = "UPDATE "+CATEGORY_TABLE+" SET "+CategoryColumns.LEFT+"="+CategoryColumns.LEFT+"+2 WHERE "+CategoryColumns.LEFT+">?";
	
	public long insertChildCategory(long parentId, String title) {
		//DECLARE v_leftkey INT UNSIGNED DEFAULT 0;
		//SELECT l INTO v_leftkey FROM `nset` WHERE `id` = ParentID;
		//UPDATE `nset` SET `r` = `r` + 2 WHERE `r` > v_leftkey;
		//UPDATE `nset` SET `l` = `l` + 2 WHERE `l` > v_leftkey;
		//INSERT INTO `nset` (`name`, `l`, `r`) VALUES (NodeName, v_leftkey + 1, v_leftkey + 2);
		return insertCategory(CategoryColumns.LEFT, parentId, title);
	}

	public long insertMateCategory(long categoryId, String title) {
		//DECLARE v_rightkey INT UNSIGNED DEFAULT 0;
		//SELECT `r` INTO v_rightkey FROM `nset` WHERE `id` = MateID;
		//UPDATE `	nset` SET `r` = `r` + 2 WHERE `r` > v_rightkey;
		//UPDATE `nset` SET `l` = `l` + 2 WHERE `l` > v_rightkey;
		//INSERT `nset` (`name`, `l`, `r`) VALUES (NodeName, v_rightkey + 1, v_rightkey + 2);
		return insertCategory(CategoryColumns.RIGHT, categoryId, title);
	}

	private long insertCategory(String field, long categoryId, String title) {
		int num = 0;
		Cursor c = db.query(CATEGORY_TABLE, new String[]{field}, 
				CategoryColumns.ID+"=?", new String[]{String.valueOf(categoryId)}, null, null, null);
		try {
			if (c.moveToFirst()) {
				num = c.getInt(0);
			}
		} finally  {
			c.close();
		}
		db.beginTransaction();
		try {
			String[] args = new String[]{String.valueOf(num)};
			db.execSQL(INSERT_CATEGORY_UPDATE_RIGHT, args);
			db.execSQL(INSERT_CATEGORY_UPDATE_LEFT, args);
			ContentValues values = new ContentValues();
			values.put(CategoryColumns.TITLE, title);
			values.put(CategoryColumns.LEFT, num+1);
			values.put(CategoryColumns.RIGHT, num+2);
			long id = db.insert(CATEGORY_TABLE, null, values);
			db.setTransactionSuccessful();
			return id;
		} finally {
			db.endTransaction();
		}
	}

	private static final String V_SUBORDINATES = "(SELECT " 
	+"node."+CategoryColumns.ID+" as "+CategoryViewColumns.ID+", "
	+"node."+CategoryColumns.TITLE+" as "+CategoryViewColumns.TITLE+", "
	+"(COUNT(parent."+CategoryColumns.ID+") - (sub_tree.depth + 1)) AS "+CategoryViewColumns.LEVEL
	+" FROM "
	+CATEGORY_TABLE+" AS node, "
	+CATEGORY_TABLE+" AS parent, "
	+CATEGORY_TABLE+" AS sub_parent, "
	+"("
		+"SELECT node."+CategoryColumns.ID+" as "+CategoryColumns.ID+", "
		+"(COUNT(parent."+CategoryColumns.ID+") - 1) AS depth"
		+" FROM "
		+CATEGORY_TABLE+" AS node, "
		+CATEGORY_TABLE+" AS parent "
		+" WHERE node."+CategoryColumns.LEFT+" BETWEEN parent."+CategoryColumns.LEFT+" AND parent."+CategoryColumns.RIGHT
		+" AND node."+CategoryColumns.ID+"=?"
		+" GROUP BY node."+CategoryColumns.ID
		+" ORDER BY node."+CategoryColumns.LEFT
	+") AS sub_tree "
	+" WHERE node."+CategoryColumns.LEFT+" BETWEEN parent."+CategoryColumns.LEFT+" AND parent."+CategoryColumns.RIGHT
	+" AND node."+CategoryColumns.LEFT+" BETWEEN sub_parent."+CategoryColumns.LEFT+" AND sub_parent."+CategoryColumns.RIGHT
	+" AND sub_parent."+CategoryColumns.ID+" = sub_tree."+CategoryColumns.ID
	+" GROUP BY node."+CategoryColumns.ID
	+" HAVING "+CategoryViewColumns.LEVEL+"=1"
	+" ORDER BY node."+CategoryColumns.LEFT
	+")";
	
	public List<Category> getSubordinates(long parentId) {
		List<Category> list = new LinkedList<Category>();
		Cursor c = db.query(V_SUBORDINATES, new String[]{CategoryViewColumns.ID, CategoryViewColumns.TITLE, CategoryViewColumns.LEVEL}, null, 
				new String[]{String.valueOf(parentId)}, null, null, null);
		//DatabaseUtils.dumpCursor(c);
		try {
			while (c.moveToNext()) {
				long id = c.getLong(0);
				String title = c.getString(1);
				Category cat = new Category();
				cat.id = id;
				cat.title = title;
				list.add(cat);
			}
		} finally {
			c.close();
		}
		return list;
	}
	
	private static final String DELETE_CATEGORY_UPDATE1 = "UPDATE "+TRANSACTION_TABLE
		+" SET "+TransactionColumns.CATEGORY_ID+"=0 WHERE "
		+TransactionColumns.CATEGORY_ID+" IN ("
		+"SELECT "+CategoryColumns.ID+" FROM "+CATEGORY_TABLE+" WHERE "
		+CategoryColumns.LEFT+" BETWEEN ? AND ?)";
	private static final String DELETE_CATEGORY_UPDATE2 = "UPDATE "+CATEGORY_TABLE
		+" SET "+CategoryColumns.LEFT+"=(CASE WHEN "+CategoryColumns.LEFT+">%s THEN "
		+CategoryColumns.LEFT+"-%s ELSE "+CategoryColumns.LEFT+" END),"
		+CategoryColumns.RIGHT+"="+CategoryColumns.RIGHT+"-%s"
		+" WHERE "+CategoryColumns.RIGHT+">%s";

	public void deleteCategory(long categoryId) {
		//DECLARE v_leftkey, v_rightkey, v_width INT DEFAULT 0;
		//
		//SELECT
		//	`l`, `r`, `r` - `l` + 1 INTO v_leftkey, v_rightkey, v_width
		//FROM `nset`
		//WHERE
		//	`id` = NodeID;
		//
		//DELETE FROM `nset` WHERE `l` BETWEEN v_leftkey AND v_rightkey;
		//
		//UPDATE `nset`
		//SET
		//	`l` = IF(`l` > v_leftkey, `l` - v_width, `l`),
		//	`r` = `r` - v_width
		//WHERE
		//	`r` > v_rightkey;
		int left = 0, right = 0;
		Cursor c = db.query(CATEGORY_TABLE, new String[]{CategoryColumns.LEFT, CategoryColumns.RIGHT}, 
				CategoryColumns.ID+"=?", new String[]{String.valueOf(categoryId)}, null, null, null);
		try {
			if (c.moveToFirst()) {
				left = c.getInt(0);
				right = c.getInt(1);
			}
		} finally  {
			c.close();
		}
		db.beginTransaction();
		try {
			int width = right - left + 1;
			String[] args = new String[]{String.valueOf(left), String.valueOf(right)};
			db.execSQL(DELETE_CATEGORY_UPDATE1, args);
			db.delete(CATEGORY_TABLE, CategoryColumns.LEFT+" BETWEEN ? AND ?", args);
			db.execSQL(String.format(DELETE_CATEGORY_UPDATE2, left, width, width, right));
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}
	
	private void updateCategory(long id, String title) {
		ContentValues values = new ContentValues();
		values.put(CategoryColumns.TITLE, title);
		db.update(CATEGORY_TABLE, values, CategoryColumns.ID+"=?", new String[]{String.valueOf(id)});
	}
	
	public void updateCategoryTree(CategoryTree<Category> tree) {
		db.beginTransaction();
		try {
			updateCategoryTreeInTransaction(tree);
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}
	
	private static final String WHERE_CATEGORY_ID = CategoryColumns.ID+"=?";
	
	private void updateCategoryTreeInTransaction(CategoryTree<Category> tree) {
		ContentValues values = new ContentValues();
		String[] sid = new String[1];
		for (Category c : tree) {
			values.put(CategoryColumns.LEFT, c.left);
			values.put(CategoryColumns.RIGHT, c.right);
			sid[0] = String.valueOf(c.id);
			db.update(CATEGORY_TABLE, values, WHERE_CATEGORY_ID, sid);
			if (c.hasChildren()) {
				updateCategoryTreeInTransaction(c.children);
			}
		}
	}

	public void moveCategory(long id, long newParentId, String title) {
		db.beginTransaction();
		try {
			
			updateCategory(id, title);
			
			long origin_lft, origin_rgt, new_parent_rgt;
			Cursor c = db.query(CATEGORY_TABLE, new String[]{CategoryColumns.LEFT, CategoryColumns.RIGHT}, 
					CategoryColumns.ID+"=?", new String[]{String.valueOf(id)}, null, null, null);
			try {
				if (c.moveToFirst()) {
					origin_lft = c.getLong(0);
					origin_rgt = c.getLong(1);
				} else {
					return;
				}
			} finally {
				c.close();
			}
			c = db.query(CATEGORY_TABLE, new String[]{CategoryColumns.RIGHT}, 
					CategoryColumns.ID+"=?", new String[]{String.valueOf(newParentId)}, null, null, null);
			try {
				if (c.moveToFirst()) {
					new_parent_rgt = c.getLong(0);
				} else {
					return;
				}
			} finally {
				c.close();
			}
		
			db.execSQL("UPDATE "+CATEGORY_TABLE+" SET "
				+CategoryColumns.LEFT+" = "+CategoryColumns.LEFT+" + CASE "
				+" WHEN "+new_parent_rgt+" < "+origin_lft
				+" THEN CASE "
				+" WHEN "+CategoryColumns.LEFT+" BETWEEN "+origin_lft+" AND "+origin_rgt
				+" THEN "+new_parent_rgt+" - "+origin_lft
				+" WHEN "+CategoryColumns.LEFT+" BETWEEN "+new_parent_rgt+" AND "+(origin_lft-1)
				+" THEN "+(origin_rgt-origin_lft+1)
				+" ELSE 0 END "
				+" WHEN "+new_parent_rgt+" > "+origin_rgt
				+" THEN CASE "
				+" WHEN "+CategoryColumns.LEFT+" BETWEEN "+origin_lft+" AND "+origin_rgt
				+" THEN "+(new_parent_rgt-origin_rgt-1)
				+" WHEN "+CategoryColumns.LEFT+" BETWEEN "+(origin_rgt+1)+" AND "+(new_parent_rgt-1)
				+" THEN "+(origin_lft - origin_rgt - 1)
				+" ELSE 0 END "
				+" ELSE 0 END,"
				+CategoryColumns.RIGHT+" = "+CategoryColumns.RIGHT+" + CASE "
				+" WHEN "+new_parent_rgt+" < "+origin_lft
				+" THEN CASE "
				+" WHEN "+CategoryColumns.RIGHT+" BETWEEN "+origin_lft+" AND "+origin_rgt
				+" THEN "+(new_parent_rgt-origin_lft)
				+" WHEN "+CategoryColumns.RIGHT+" BETWEEN "+new_parent_rgt+" AND "+(origin_lft - 1)
				+" THEN "+(origin_rgt-origin_lft+1)
				+" ELSE 0 END "
				+" WHEN "+new_parent_rgt+" > "+origin_rgt
				+" THEN CASE "
				+" WHEN "+CategoryColumns.RIGHT+" BETWEEN "+origin_lft+" AND "+origin_rgt
				+" THEN "+(new_parent_rgt-origin_rgt-1)
				+" WHEN "+CategoryColumns.RIGHT+" BETWEEN "+(origin_rgt+1)+" AND "+(new_parent_rgt-1)
				+" THEN "+(origin_lft-origin_rgt-1)
				+" ELSE 0 END "
				+" ELSE 0 END");
			
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}


}
