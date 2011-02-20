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
package ru.orangesoftware.financisto.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.Cursor;
import android.location.Location;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.TextAppearanceSpan;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import ru.orangesoftware.financisto.R;
import ru.orangesoftware.financisto.db.DatabaseHelper;
import ru.orangesoftware.financisto.model.Currency;
import ru.orangesoftware.financisto.model.Total;

import java.math.BigDecimal;

public class Utils {
	
	public static final BigDecimal HUNDRED = new BigDecimal(100);
	private static final int zeroColor = Resources.getSystem().getColor(android.R.color.secondary_text_dark);
	private final int positiveColor;
	private final int negativeColor;	
	
	public Utils(Context context) {
		Resources r = context.getResources();
		positiveColor = r.getColor(R.color.positive_amount);
		negativeColor = r.getColor(R.color.negative_amount);
	}
		
	public static String amountToString(Currency c, long amount) {
		return amountToString(c, amount, false);
	}

	public static String amountToString(Currency c, BigDecimal amount) {		
		StringBuilder sb = new StringBuilder(); 
		return amountToString(sb, c, amount, false).toString();		
	}

	public static StringBuilder amountToString(StringBuilder sb, Currency c, long amount) {
		return amountToString(sb, c, amount, false);
	}
	
	public static String amountToString(Currency c, long amount, boolean addPlus) {
		StringBuilder sb = new StringBuilder(); 
		return amountToString(sb, c, amount, addPlus).toString();		
	}

	public static StringBuilder amountToString(StringBuilder sb, Currency c, long amount, boolean addPlus) {
		return amountToString(sb, c, new BigDecimal(amount), addPlus);
	}
	
	public static StringBuilder amountToString(StringBuilder sb, Currency c, BigDecimal amount, boolean addPlus) {
		if (amount.compareTo(BigDecimal.ZERO) > 0) {
			if (addPlus) {
				sb.append("+");
			}
		}
		if (c == null) {
			c = Currency.EMPTY;
		}
		String s = c.getFormat().format(amount.divide(HUNDRED));
		if (s.endsWith(".")) {
			s = s.substring(0, s.length()-1);
		}
		sb.append(s);
        if (isNotEmpty(c.symbol)) {
		    sb.append(" ").append(c.symbol);
        }
		return sb;		
	}
	
	public static String amountToStringNoCurrency(Currency c, long amount) {
		String s = c.getFormat().format(new BigDecimal(amount).divide(HUNDRED));
		if (s.endsWith(".")) {
			s = s.substring(0, s.length()-1);
		}		
		return s;
	}

	public static boolean checkEditText(EditText editText, String name, boolean required, int length) {
		String text = text(editText);
		if (isEmpty(text) && required) {
			editText.setError("Please specify the "+name+"..");
			return false;
		}
		if (text != null && text.length() > length) {
			editText.setError("Lenght of the "+name+" must not be more than "+length+" chars..");
			return false;
		}
		return true;
	}
	
	public static String text(EditText text) {
		String s = text.getText().toString().trim();
		return s.length() > 0 ? s : null;
	}
	
	public void setAmountText(TextView view, Currency c, long amount, boolean addPlus) {
		setAmountText(new StringBuilder(), view, c, amount, addPlus);
	}

	public void setAmountText(StringBuilder sb, TextView view, Currency c, long amount, boolean addPlus) {
		view.setText(amountToString(sb, c, amount, addPlus).toString());
		view.setTextColor(amount == 0 ? zeroColor : (amount > 0 ? positiveColor : negativeColor));
	}
	
	public int getAmountColor(Context context, long amount) {
		return amount == 0 ? zeroColor : (amount > 0 ? positiveColor : negativeColor);
	}

	public static TextAppearanceSpan getAmountSpan(Context context, long amount) {
		return new TextAppearanceSpan(context, amount >= 0 ? R.style.TextAppearance_PositiveAmount : R.style.TextAppearance_NegativeAmount);
	}
	
	public static int moveCursor(Cursor cursor, String idColumnName, long id) {
		if (id != -1) {			
			int pos = cursor.getColumnIndexOrThrow(idColumnName);
			if (cursor.moveToFirst()) {
				do {
					if (cursor.getLong(pos) == id) {
						return cursor.getPosition();
					}
				} while(cursor.moveToNext());				
			}
		}
		return -1;
	}

	public static void setTotal(Context context, TextView textView, Total t) {
		final SpannableStringBuilder sb = new SpannableStringBuilder();		
		sb.append(Utils.amountToString(t.currency, t.amount, false));
		int x = sb.length();
		sb.setSpan(getAmountSpan(context, t.amount), 0, x, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
		sb.append(" | ");
		sb.append(Utils.amountToString(t.currency, t.balance, false));
		sb.setSpan(getAmountSpan(context, t.balance), x+3, sb.length(), Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
		textView.setText(sb, TextView.BufferType.NORMAL);
	}	
	
	public static String[] joinArrays(String[] a1, String[] a2) {
		if (a1.length == 0) {
			return a2;
		}
		String[] a = new String[a1.length+a2.length];
		System.arraycopy(a1, 0, a, 0, a1.length);
		System.arraycopy(a2, 0, a, a1.length, a2.length);
		return a;
	}

	public static String locationToText(String provider, double latitude, double longitude, float accuracy, String resolvedAddress) {
    	StringBuilder sb = new StringBuilder();
    	sb.append(provider).append(" (");
    	if (resolvedAddress != null) {
    		sb.append(resolvedAddress);
    	} else {    		
    		sb.append("Lat: ").append(Location.convert(latitude, Location.FORMAT_DEGREES)).append(", ");
    		sb.append("Lon: ").append(Location.convert(longitude, Location.FORMAT_DEGREES));
    		if (accuracy > 0) {
    			sb.append(", ");
    			sb.append("±").append(String.format("%.2f", accuracy)).append("m");
    		}    	
    	}
    	sb.append(")");
		return sb.toString();
	}

	public static void setEnabled(ViewGroup layout, boolean enabled) {
		int count = layout.getChildCount();
		for (int i=0; i<count; i++) {
			layout.getChildAt(i).setEnabled(enabled);
		}
	}

	public static boolean isNotEmpty(String s) {
		return s != null && s.length() > 0;
	}
	
	public static boolean isEmpty(String s) {
		return s == null || s.length() == 0;
	}

	public static boolean isEmpty(EditText e) {
		return isEmpty(text(e));
	}

	public static PackageInfo getPackageInfo(Context context) throws NameNotFoundException {
		PackageManager manager = context.getPackageManager();                         
		return manager.getPackageInfo(context.getPackageName(), 0);                         			
	}

    public static String emptyString(String s) {
        return s != null ? s : "";
    }
}
