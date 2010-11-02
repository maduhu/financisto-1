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
package ru.orangesoftware.financisto.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.google.ical.values.RRule;
import ru.orangesoftware.financisto.db.DatabaseAdapter;
import ru.orangesoftware.financisto.db.MyEntityManager;
import ru.orangesoftware.financisto.model.RestoredTransaction;
import ru.orangesoftware.financisto.model.SystemAttribute;
import ru.orangesoftware.financisto.model.TransactionAttributeInfo;
import ru.orangesoftware.financisto.model.info.TransactionInfo;
import ru.orangesoftware.financisto.recur.Recurrence;
import ru.orangesoftware.financisto.recur.RecurrenceIterator;
import ru.orangesoftware.financisto.utils.MyPreferences;

import java.util.*;

public class RecurrenceScheduler {

    private static final String TAG = "RecurrenceScheduler";
	private static final Date NULL_DATE = new Date(0);
	private static final int MAX_RESTORED = 1000;

    public static final String SCHEDULED_TRANSACTION_ID = "scheduledTransactionId";

    private final DatabaseAdapter db;
    private final MyEntityManager em;

    public RecurrenceScheduler(DatabaseAdapter db) {
        this.db = db;
        this.em = db.em();
    }

    public int scheduleAll(Context context) {
        long now = System.currentTimeMillis();
        int restoredTransactionsCount = 0;
        if (MyPreferences.isRestoreMissedScheduledTransactions(context)) {
            restoredTransactionsCount = restoreMissedSchedules(now);
            // all transactions up to and including now has already been restored
            now += 1000;
        }
        scheduleAll(context, now);
        return restoredTransactionsCount;
    }

    public TransactionInfo scheduleOne(Context context, long scheduledTransactionId) {
        Log.i(TAG, "Alarm for "+scheduledTransactionId+" received..");
        TransactionInfo transaction = em.getTransactionInfo(scheduledTransactionId);
        if (transaction != null) {
            long transactionId = duplicateTransactionFromTemplate(transaction);
            boolean hasBeenRescheduled = rescheduleTransaction(context, transaction);
            if (!hasBeenRescheduled) {
                deleteTransactionIfNeeded(transaction);
                Log.i(TAG, "Expired transaction "+transaction.id+" has been deleted");
            }
            transaction.id = transactionId;
            return transaction;
        }
        return null;
    }

    private void deleteTransactionIfNeeded(TransactionInfo transaction) {
        TransactionAttributeInfo a = em.getSystemAttributeForTransaction(SystemAttribute.DELETE_AFTER_EXPIRED, transaction.id);
        if (a != null && Boolean.valueOf(a.value)) {
            db.deleteTransaction(transaction.id);
        }
    }

    /**
     * Restores missed scheduled transactions on backup and on phone restart
     * @param now current time
     * @return restored transactions count
     */
    private int restoreMissedSchedules(long now) {
        try {
            List<RestoredTransaction> restored = getMissedSchedules(now);
            if (restored.size() > 0) {
                db.storeMissedSchedules(restored, now);
                Log.i(TAG, "["+restored.size()+"] scheduled transactions have been restored:");
                for (int i=0; i<10 && i<restored.size(); i++) {
                    RestoredTransaction rt = restored.get(i);
                    Log.i(TAG, rt.transactionId+" at "+rt.dateTime);
                }
                return restored.size();
            }
        } catch (Exception ex) {
            // eat all exceptions
            Log.e(TAG, "Unexpected error while restoring schedules", ex);
        }
        return 0;
    }

    private long duplicateTransactionFromTemplate(TransactionInfo transaction) {
        return db.duplicateTransaction(transaction.id);
    }

	public List<RestoredTransaction> getMissedSchedules(long now) {
		long t0 = System.currentTimeMillis();
		try {
			Date endDate = new Date(now);
			List<RestoredTransaction> restored = new ArrayList<RestoredTransaction>();
			ArrayList<TransactionInfo> list = em.getAllScheduledTransactions();
			for (TransactionInfo t : list) {
				if (t.recurrence != null) {
					long lastRecurrence = t.lastRecurrence;
					if (lastRecurrence > 0) {
						RecurrenceIterator ri = createIterator(t.recurrence, lastRecurrence);
						while (ri.hasNext()) {
							Date nextDate = ri.next();
							if (nextDate.after(endDate)) {
								break;
							}
							addRestoredTransaction(restored, t, nextDate);
						}
					}
				} else {
					Date nextDate = new Date(t.dateTime);
					if (nextDate.before(endDate)) {
						addRestoredTransaction(restored, t, nextDate);
					}
				}				
			}
			if (restored.size() > MAX_RESTORED) {
				Collections.sort(restored, new Comparator<RestoredTransaction>(){
					@Override
					public int compare(RestoredTransaction t0, RestoredTransaction t1) {
						return t1.dateTime.compareTo(t0.dateTime);
					}
				});
				restored = restored.subList(0, MAX_RESTORED);
			}
			return restored;
		} finally {
			Log.i(TAG, "getSortedSchedules="+(System.currentTimeMillis()-t0)+"ms");
		}		
	}

	private void addRestoredTransaction(List<RestoredTransaction> restored,
			TransactionInfo t, Date nextDate) {
		RestoredTransaction rt = new RestoredTransaction(t.id, nextDate);
		restored.add(rt);							
	}

	public ArrayList<TransactionInfo> getSortedSchedules(long now) {
		long t0 = System.currentTimeMillis();
		try {
			 ArrayList<TransactionInfo> list = em.getAllScheduledTransactions();
			 calculateNextScheduleDate(list, now);
			 sortTransactionsByScheduleDate(list, now);
			 return list;
		} finally {
			Log.i(TAG, "getSortedSchedules="+(System.currentTimeMillis()-t0)+"ms");
		}
	}

    public ArrayList<TransactionInfo> scheduleAll(Context context, long now) {
        ArrayList<TransactionInfo> scheduled = getSortedSchedules(now);
        for (TransactionInfo transaction : scheduled) {
            scheduleAlarm(context, transaction, now);
        }
        return scheduled;
    }

    public boolean scheduleAlarm(Context context, TransactionInfo transaction, long now) {
        if (shouldSchedule(transaction, now)) {
            Date scheduleTime = transaction.nextDateTime;
            AlarmManager service = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pendingIntent = createPendingIntentForScheduledAlarm(context, transaction);
            service.set(AlarmManager.RTC_WAKEUP, scheduleTime.getTime(), pendingIntent);
            Log.i(TAG, "Scheduling alarm for "+transaction.id+" at "+scheduleTime);
            return true;
        }
        return false;
    }

    public boolean rescheduleTransaction(Context context, TransactionInfo transaction) {
        if (transaction.recurrence != null) {
            long now = System.currentTimeMillis()+1000;
            calculateNextDate(transaction, now);
            return scheduleAlarm(context, transaction, now);
        }
        return false;
    }

    private boolean shouldSchedule(TransactionInfo transaction, long now) {
        return transaction.nextDateTime != null && now < transaction.nextDateTime.getTime();
    }

    private PendingIntent createPendingIntentForScheduledAlarm(Context context, TransactionInfo transaction) {
        Intent intent = new Intent("ru.orangesoftware.financisto.SCHEDULED_ALARM");
        intent.putExtra(SCHEDULED_TRANSACTION_ID, transaction.id);
        return PendingIntent.getBroadcast(context, (int)transaction.id, intent, PendingIntent.FLAG_CANCEL_CURRENT);
    }

	private void sortTransactionsByScheduleDate(ArrayList<TransactionInfo> list, long now) {
		final Date today = new Date(now);
		Collections.sort(list, new Comparator<TransactionInfo>(){
			@Override
			public int compare(TransactionInfo o1, TransactionInfo o2) {
				Date d1 = o1 != null ? o1.nextDateTime : NULL_DATE;
				Date d2 = o2 != null ? o2.nextDateTime : NULL_DATE;
				if (d1 == null) {
					d1 = NULL_DATE;
				}
				if (d2 == null) {
					d2 = NULL_DATE;
				}
				if (d1.after(today)) {
					if (d2.after(today)) {
						return o1.nextDateTime.compareTo(o2.nextDateTime);
					} else {
						return -1;
					}
				} else {
					if (d2.after(today)) {
						return 1;
					} else {
						return -o1.nextDateTime.compareTo(o2.nextDateTime);
					}
				}
			}
		});
	}

	private long calculateNextScheduleDate(ArrayList<TransactionInfo> list, long now) {
		for (TransactionInfo t : list) {
			if (t.recurrence != null) {
				calculateNextDate(t, now);
			} else {
				t.nextDateTime = new Date(t.dateTime);
			}
		 }
		return now;
	}

	public Date calculateNextDate(TransactionInfo transaction, long now) {
		return transaction.nextDateTime = calculateNextDate(transaction.recurrence, now);
	}
	
	public Date calculateNextDate(String recurrence, long now) {
		RecurrenceIterator ri = createIterator(recurrence, now);
		if (ri.hasNext()) {
			return ri.next();
		} else {
			return null;
		}
	}

	private RecurrenceIterator createIterator(String recurrence, long now) {
		Recurrence r = Recurrence.parse(recurrence);
		Date startDate = r.getStartDate().getTime();
		RRule rrule = r.createRRule();
		RecurrenceIterator c = RecurrenceIterator.create(rrule, startDate);
		Date advanceDate = new Date(now);
		c.advanceTo(advanceDate);
		return c;
	}



}
