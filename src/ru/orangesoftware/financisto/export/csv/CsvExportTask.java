package ru.orangesoftware.financisto.export.csv;

import android.app.ProgressDialog;
import android.content.Context;
import ru.orangesoftware.financisto.db.DatabaseAdapter;
import ru.orangesoftware.financisto.export.ImportExportAsyncTask;

public class CsvExportTask extends ImportExportAsyncTask {

    private final CsvExportOptions options;

	public CsvExportTask(Context context, ProgressDialog dialog, CsvExportOptions options) {
		super(context, dialog, null);
		this.options = options;
	}
	
	@Override
	protected Object work(Context context, DatabaseAdapter db, String...params) throws Exception {
		CsvExport export = new CsvExport(db, options);
		return export.export();
	}

	@Override
	protected String getSuccessMessage(Object result) {
		return String.valueOf(result);
	}

}
