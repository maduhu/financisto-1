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

import ru.orangesoftware.financisto.R;
import ru.orangesoftware.financisto.db.DatabaseAdapter;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

public abstract class ImportExportAsyncTask extends AsyncTask<String, Void, Object> {
	
	protected final Context context;
	private final ProgressDialog dialog;

    private ImportExportAsyncTaskListener listener;
	
	public ImportExportAsyncTask(Context context, ProgressDialog dialog) {
		this.dialog = dialog;
		this.context = context;
	}

    public void setListener(ImportExportAsyncTaskListener listener) {
        this.listener = listener;
    }

    @Override
	protected Object doInBackground(String... params) {
		DatabaseAdapter db = new DatabaseAdapter(context);
		db.open();
		try {
			return work(context, db, params);
		} catch(Exception ex){
			Log.e("Financisto", "Unable to do import/export", ex);
			return ex;
		} finally {
			db.close();
		}			
	}

	protected abstract Object work(Context context, DatabaseAdapter db, String...params) throws Exception;
	
	protected abstract String getSuccessMessage(Object result);

	@Override
	protected void onPostExecute(Object result) {
		dialog.dismiss();
		String message;
		String title;

		if (result instanceof Exception) 
			return;

		title = context.getString(R.string.success);
		message = getSuccessMessage(result);
		
		if (listener != null) {
			listener.onCompleted();
		}
		
		new AlertDialog.Builder(context)
			.setTitle(title)
			.setMessage(message)
			.setPositiveButton(R.string.ok, null)
			.show();
	}
	
}

