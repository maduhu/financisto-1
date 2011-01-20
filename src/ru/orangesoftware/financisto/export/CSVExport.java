/*******************************************************************************
 * Copyright (c) 2010 Denis Solonenko.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     Denis Solonenko - initial API and implementation
 ******************************************************************************/
package ru.orangesoftware.financisto.export;

import static ru.orangesoftware.financisto.utils.DateUtils.FORMAT_DATE_ISO_8601;
import static ru.orangesoftware.financisto.utils.DateUtils.FORMAT_TIME_ISO_8601;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Date;
import java.util.HashMap;

import ru.orangesoftware.financisto.blotter.WhereFilter;
import ru.orangesoftware.financisto.db.DatabaseAdapter;
import ru.orangesoftware.financisto.db.DatabaseHelper.BlotterColumns;
import ru.orangesoftware.financisto.model.Category;
import ru.orangesoftware.financisto.model.Currency;
import ru.orangesoftware.financisto.utils.CurrencyCache;
import ru.orangesoftware.financisto.utils.Utils;
import android.database.Cursor;

public class CSVExport extends Export {

	private final DatabaseAdapter db;
	private final WhereFilter filter;
	private final NumberFormat f;
	private final char fieldSeparator;
	private final boolean includeHeader;
	
	public CSVExport(DatabaseAdapter db, WhereFilter filter, Currency currency,
			char fieldSeparator, boolean includeHeader) {
		this.db = db;
		this.filter = filter;
		this.f = CurrencyCache.createCurrencyFormat(currency);
		this.fieldSeparator = fieldSeparator;
		this.includeHeader = includeHeader;
	}
	
	public CSVExport(DatabaseAdapter db, WhereFilter filter, Currency currency) {
		this(db, filter, currency, ',', true);
	}
	
	@Override
	protected String getExtension() {
		return ".csv";
	}

	@Override
	protected void writeHeader(BufferedWriter bw) throws IOException  {
		if (includeHeader) {
			Csv.Writer w = new Csv.Writer(bw).delimiter(fieldSeparator);
			w.value("date").value("time").value("account").value("amount").value("currency");
			w.value("category").value("parent").value("payee").value("location").value("project").value("note");
			w.newLine();
		}
	}

	@Override
	protected void writeBody(BufferedWriter bw) throws IOException {
		Csv.Writer w = new Csv.Writer(bw).delimiter(fieldSeparator);
		try {
			HashMap<Long, Category> categoriesMap = db.getAllCategoriesMap(false);
			Cursor c = db.getBlotter(filter);
			try {			
				StringBuilder sb = new StringBuilder();
				while (c.moveToNext()) {
					writeLine(w, c, categoriesMap, sb);			
				}					
			} finally {
				c.close();
			}
		} finally {
			w.close();
		}
	}

	private void writeLine(Csv.Writer w, Cursor cursor, HashMap<Long, Category> categoriesMap, StringBuilder sb) {
		long date = cursor.getLong(BlotterColumns.datetime.ordinal());
		Date dt = new Date(date);
		long categoryId = cursor.getLong(BlotterColumns.category_id.ordinal());
		Category category = getCategoryById(categoriesMap, categoryId);
		long toAccountId = cursor.getLong(BlotterColumns.to_account_id.ordinal());
		String project = cursor.getString(BlotterColumns.project.ordinal());
		if (toAccountId > 0) {
			String fromAccountTitle = cursor.getString(BlotterColumns.from_account_title.ordinal());
			String toAccountTitle = cursor.getString(BlotterColumns.to_account_title.ordinal());
			long fromCurrencyId = cursor.getLong(BlotterColumns.from_account_currency_id.ordinal());
			long toCurrencyId = cursor.getLong(BlotterColumns.to_account_currency_id.ordinal());
			long fromAmount = cursor.getLong(BlotterColumns.from_amount.ordinal());
			long toAmount = cursor.getLong(BlotterColumns.to_amount.ordinal());
			String note = cursor.getString(BlotterColumns.note.ordinal());
			writeLine(w, dt, fromAccountTitle, fromAmount, fromCurrencyId, category, "", "Transfer Out", project, note);
			writeLine(w, dt, toAccountTitle, toAmount, toCurrencyId, category, "", "Transfer In", project, note);
		} else {
			String fromAccountTitle = cursor.getString(BlotterColumns.from_account_title.ordinal());
			String note = cursor.getString(BlotterColumns.note.ordinal());
			String location = cursor.getString(BlotterColumns.location.ordinal());
			long fromCurrencyId = cursor.getLong(BlotterColumns.from_account_currency_id.ordinal());
			long amount = cursor.getLong(BlotterColumns.from_amount.ordinal());
            String payee = cursor.getString(BlotterColumns.payee.ordinal());
			writeLine(w, dt, fromAccountTitle, amount, fromCurrencyId, category, payee, location, project, note);
		}
	}
	
	private void writeLine(Csv.Writer w, Date dt, String account, long amount, long currencyId, 
			Category category, String payee, String location, String project, String note) {
		w.value(FORMAT_DATE_ISO_8601.format(dt));
		w.value(FORMAT_TIME_ISO_8601.format(dt));
		w.value(account);
		w.value(f.format(new BigDecimal(amount).divide(Utils.HUNDRED)));
		Currency c = CurrencyCache.getCurrency(currencyId);
		w.value(c.name);
		w.value(category != null ? category.title : "");
		String sParent = buildPath(category);
		w.value(sParent);
        w.value(payee);
		w.value(location);
		w.value(project);
		w.value(note);
		w.newLine();
	}

	private String buildPath(Category category) {
		if (category == null || category.parent == null) {
			return "";
		} else {
            StringBuilder sb = new StringBuilder(category.parent.title);
			for (Category cat = category.parent.parent; cat != null; cat = cat.parent) {
                sb.insert(0,":").insert(0, cat.title);
			}
			return sb.toString();
		}
	}

	@Override
	protected void writeFooter(BufferedWriter bw) throws IOException {
	}

	public Category getCategoryById(HashMap<Long, Category> categoriesMap, long id) {
		return categoriesMap.get(id);
	}
	
}
