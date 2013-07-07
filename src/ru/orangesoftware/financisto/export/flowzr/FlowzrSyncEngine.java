/*
 * Copyright (c) 2012 Emmanuel Florent.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 */

package ru.orangesoftware.financisto.export.flowzr;

import static ru.orangesoftware.financisto.db.DatabaseHelper.TRANSACTION_ATTRIBUTE_TABLE;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import ru.orangesoftware.financisto.export.flowzr.FlowzrSyncOptions;
import ru.orangesoftware.financisto.model.Attribute;
import ru.orangesoftware.financisto.model.TransactionAttribute;

import ru.orangesoftware.financisto.activity.FlowzrSyncActivity;
import ru.orangesoftware.financisto.db.DatabaseAdapter;
import ru.orangesoftware.financisto.db.DatabaseHelper;
import ru.orangesoftware.financisto.db.MyEntityManager;
import ru.orangesoftware.financisto.export.ProgressListener;
import ru.orangesoftware.financisto.model.Account;
import ru.orangesoftware.financisto.model.Budget;
import ru.orangesoftware.financisto.model.Category;
import ru.orangesoftware.financisto.model.Currency;
import ru.orangesoftware.financisto.model.MyEntity;
import ru.orangesoftware.financisto.model.MyLocation;
import ru.orangesoftware.financisto.model.Payee;
import ru.orangesoftware.financisto.model.Project;
import ru.orangesoftware.financisto.model.Transaction;
import ru.orangesoftware.financisto.model.TransactionStatus;
import ru.orangesoftware.financisto.rates.ExchangeRate;
import ru.orangesoftware.financisto.utils.CurrencyCache;
import ru.orangesoftware.financisto.utils.IntegrityFix;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.Html;
import android.util.Log;

//TODO : rates, batch
public class FlowzrSyncEngine  {
	private String TAG;
	String FLOWZR_API_URL;
	private final String FLOWZR_MSG_DELETED="DELETED";
	private final String FLOWZR_MSG_NET_ERROR="FLOWZR_MSG_NET_ERROR";
	public final FlowzrSyncOptions options;
    private final SQLiteDatabase db;
    private final DatabaseAdapter dba;
    private final MyEntityManager em;
    private DefaultHttpClient http_client;    
    static InputStream isHttpcontent = null;
    static JSONObject jObj = null;
    static String json = "";
    private final long KEY_CREATE=-1;
    private ProgressListener progressListener;
    private Context context;
    private FlowzrSyncActivity flowzrSyncActivity; 
    private String[] tableNames= {"attributes","currency","category","category","project","payee","account","LOCATIONS","transactions","currency_exchange_rate",DatabaseHelper.BUDGET_TABLE};  
	private Class[] clazzArray = {Attribute.class,Currency.class,Category.class,Category.class,Project.class,Payee.class,Account.class,MyLocation.class,Transaction.class,null,Budget.class};        
	

    
    public FlowzrSyncEngine(FlowzrSyncActivity a, Context c, DatabaseAdapter dba, FlowzrSyncOptions o, DefaultHttpClient pHttp_client) {
    	this.dba=dba;
    	this.context=c;
    	this.flowzrSyncActivity=a;
    	this.em=dba.em();
		this.db = dba.db();			
		this.options = o;
		this.http_client=pHttp_client;
		this.options.startTimestamp=System.currentTimeMillis();	
		this.TAG=flowzrSyncActivity.TAG;
    	this.FLOWZR_API_URL=flowzrSyncActivity.FLOWZR_API_URL;
    }
    
    public Object doSync() throws Exception {
    	Object o=null;
    	
    	fixCreatedEntities();
    	if (o instanceof Exception) {
    		return o;
    	}    		
		o=pullDelete(options.lastSyncLocalTimestamp);
        if (o instanceof Exception) {
        	return o;
        }    	
        progressListener.onProgress(10);        
    	o=pushDelete();
        if (o instanceof Exception) {
        	return o;
        }
        progressListener.onProgress(20);        
    	o=pullUpdate();
        if (o instanceof Exception) {
        	return o;
        }
        progressListener.onProgress(25);
        o=pushUpdate();      
        progressListener.onProgress(50);        
        if (o instanceof Exception) {
        	return o;
        }
		ArrayList<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
        nameValuePairs.add(new BasicNameValuePair("action","balancesRecalc"));
		nameValuePairs.add(new BasicNameValuePair("lastSyncLocalTimestamp",String.valueOf(options.lastSyncLocalTimestamp)));        
		String strResponse=httpPush(nameValuePairs);    

        progressListener.onProgress(95);        
        IntegrityFix fix = new IntegrityFix(dba);
        fix.fix();        
        progressListener.onProgress(100);
        return null;
    }

    /*
     * Push job
     */    
    private Object pushUpdate() {
    	Object o= null;
    	int i=0;
    	for (String t : tableNames) {     
            o=pushUpdate(t,clazzArray[i]);
            progressListener.onProgress(Math.round(i*tableNames.length/100));
            if (o instanceof Exception) {
            	return o;
            }
            i++;
        }      
        return o;
    }

    /**
     * According to :
     * http://sqlite.1065341.n5.nabble.com/Can-t-insert-timestamp-field-with-value-CURRENT-TIME-td42729.html
     * it is not possible to make alter table add column updated with a default timestamp at current_timestamp
     * so default is set as zero and this pre-sync function make all 0 at lastSyncLocalTimestamp + 1
     */    
    private void fixCreatedEntities() {        	
    	Object o=null;
    	long ctime=options.lastSyncLocalTimestamp + 1;
    	for (String t : tableNames) {         		
    		db.execSQL("update "  + t + " set updated_on=" + ctime + " where updated_on=0");
    	}
    }
    
	private <T extends MyEntity> Object pushUpdate(String tableName,Class<T> clazz)  {	
		SQLiteDatabase db2=dba.db();
		String sql="select count(*) from " + tableName ;
		if (options.lastSyncLocalTimestamp > 0) {
			sql=sql + " where updated_on > " + options.lastSyncLocalTimestamp  + " and updated_on<" + options.startTimestamp ;			
		}
		
		Cursor cursorCursor=db.rawQuery(sql, null);
		cursorCursor.moveToFirst();
		long total=cursorCursor.getLong(0);

		sql="select * from " + tableName +  " where updated_on > " + options.lastSyncLocalTimestamp +  " and updated_on<" + options.startTimestamp ;
		//if (options.lastSyncLocalTimestamp>0) {
		//	sql=sql +  " where updated_on > " + options.lastSyncLocalTimestamp +  " and updated_on<" + options.startTimestamp ;
		//}

		
		if (!tableName.equals("currency_exchange_rate") && !tableName.equals("currency")) {
			sql+= " order by _id asc";	
		}
		cursorCursor=db2.rawQuery(sql, null);

						
		Object o=null;
		int i=0;
		if (cursorCursor.moveToFirst()) {			
			do {								
				if (progressListener != null) {	            
	                progressListener.onProgress((int)(Math.round(i*100/total)));	                
	            }  	
				if (((FlowzrSyncActivity)context).isCanceled)
				{
					return new Exception("operation canceled");
				}
				try {
					pushEntity(tableName,cursorCursor);
				} catch (Exception e) {
     				ArrayList<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
     				nameValuePairs.add(new BasicNameValuePair("action","error"));
     				nameValuePairs.add(new BasicNameValuePair("stack",e.getStackTrace().toString()));					
     		        HttpPost httppost = new HttpPost(FLOWZR_API_URL + options.useCredential + "/error/");
     		        e.printStackTrace();
     		        try {
     					httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs,HTTP.UTF_8));
     				} catch (UnsupportedEncodingException e2) {
     					e2.printStackTrace();
     				}
				}
				if (o!=null) {
					return o;
				}			
				i++;
			} while (cursorCursor.moveToNext());						
		}	
		cursorCursor.close();
		return o;
	}

	private Class getClassForColName(String colName) {
		if (colName.equals("category_id")) {
			return Category.class;
		}
		if (colName.equals("right")) {
			return Category.class;
		}
		if (colName.equals("left")) {
			return Category.class;
		}		
		if (colName.equals("project_id")) {
			return Project.class;
		}		
		if (colName.equals("payee_id")) {
			return Payee.class;
		}
		if (colName.equals("currency_id")) {
			return Currency.class;
		}
		if (colName.equals("from_currency_id")) {
			return Currency.class;
		}
		if (colName.equals("to_currency_id")) {
			return Currency.class;
		}		
		if (colName.equals("original_currency_id")) {
			return Currency.class;
		}	
		if (colName.equals("account_id")) {
			return Account.class;
		}		
		if (colName.equals("from_account_id")) {
			return Account.class;
		}
		if (colName.equals("to_account_id")) {
			return Account.class;
		}
		if (colName.equals("transaction_id")) {
			return Transaction.class;
		}	
		if (colName.equals("parent_id")) {
			return Transaction.class;
		}
		if (colName.equals("location_id")) {
			return MyLocation.class;
		}
		if (colName.equals("parent_budget_id")) {
			return Budget.class;
		}		
		if (colName.equals("budget_id")) {
			return Budget.class;
		}		
		return null;
	}
	
	//When pushed all entities need to send an account to be linked.
	//this is usefull for flowzr to set the namespace in case of cross-user accounts
	//flowzr allow one user to share an account to one other so we need to guess 
	//a user for an entity from an account
	private String getAccountRemoteKeyFromEntity(String tableName,int id) {
		String sql=null;
		if (tableName.equals("currency")) {
			sql="select account.remote_key from account where currency_id=" + id;
		} else if (tableName.equals("project") || tableName.equals("payee") || tableName.equals("category")) {
		sql="select account.remote_key " +
				"		from account,transactions, " + tableName + 
				"		where transactions.from_account_id=account._id and transactions." + tableName + "_id=" +id;
		} else if (tableName.equals("LOCATIONS")) {
			sql="select account.remote_key " +
					"		from account,transactions, " + tableName + 
					"		where transactions.from_account_id=account._id and transactions.location_id=" +id;			
		}
		if (sql!=null) {
			Cursor c= em.db().rawQuery(sql, null);
			if (c.moveToFirst()) {
				return c.getString(0);
			}
		}
		//account, transactions, budget have their own infos.
		return null;
	}
	
    private <T extends MyEntity> Object pushEntity(String tableName,Cursor c) {    	    			
			ArrayList<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
			nameValuePairs.add(new BasicNameValuePair("action","push" + tableName));
			nameValuePairs.add(new BasicNameValuePair("clientTimestamp",String.valueOf((System.currentTimeMillis()))));							
			String remote_key=c.getString(c.getColumnIndex(DatabaseHelper.CategoryColumns.remote_key.toString()));
			if (tableName.equals(DatabaseHelper.ACCOUNT_TABLE)) {
				String sql="select max(dateTime) as maxDate, min(dateTime) as mintDate from " + DatabaseHelper.TRANSACTION_TABLE + " where from_account_id=" + c.getInt(c.getColumnIndex("_id")) ;		
				Cursor cursorCursor=db.rawQuery(sql, null);
				cursorCursor.moveToFirst();	
				nameValuePairs.add(new BasicNameValuePair("dateOfFirstTransaction",cursorCursor.getString(1)));					
				nameValuePairs.add(new BasicNameValuePair("dateOfLastTransaction",cursorCursor.getString(0)));
			} else if (tableName.equals(DatabaseHelper.CATEGORY_TABLE)) {
				//load parent id
				Category cat=dba.getCategory(c.getInt(0)); // sql build/load parentId	
				if (cat.getParentId()>KEY_CREATE) {
					Category pcat=em.load(Category.class, cat.getParentId());
					nameValuePairs.add(new BasicNameValuePair("parent",pcat.remoteKey));					
				}
				String attrPushString="";
				
				for (Attribute attr: dba.getAttributesForCategory(c.getInt(0))) {						
					attrPushString=attrPushString + attr.remoteKey + ";";
				}
				if (attrPushString!="") {
					nameValuePairs.add(new BasicNameValuePair("attributes",attrPushString));	
				}
			} else if (tableName.equals(DatabaseHelper.TRANSACTION_TABLE)) {
				//Transaction t=dba.getTransaction() ;
	            Map<Long, String> attributesMap = dba.getAllAttributesForTransaction(c.getInt(0));
	            LinkedList<TransactionAttribute> attributes = new LinkedList<TransactionAttribute>();
	            String transaction_attribute="";
	            for (long attributeId : attributesMap.keySet()) {
	                String attr_key = dba.getAttribute(attributeId).remoteKey;	                
	                String attr_value = attributesMap.get(attributeId);
	                transaction_attribute+= dba.getAttribute(attributeId).remoteKey + "=" + attributesMap.get(attributeId) +";";
	                nameValuePairs.add(new BasicNameValuePair("transaction_attribute",transaction_attribute));	                
	            }
				
			} else if (tableName.equals(DatabaseHelper.BUDGET_TABLE)) {
				//String d=c.getString(c.getColumnIndex("parent"));
				
				//if (d>(System.currentTimeMillis() + 31556926000.0)) {
				//	Log.e(TAG,"avoiding pulling too much budgets");
				//	return null;
				//}
			}
			for (String colName: c.getColumnNames()) {			
				if ((colName.endsWith("_id") || colName.equals("right") || colName.equals("left") )&&  getClassForColName(colName)!=null) {	
					if (colName.equals("location_id") && c.getInt(c.getColumnIndex(colName))>=0) {
						int entity_id=c.getInt(c.getColumnIndex(colName));
						MyLocation myEntityEntityLoaded=(MyLocation) em.load(getClassForColName(colName), entity_id);
						nameValuePairs.add(new BasicNameValuePair(colName,myEntityEntityLoaded.remoteKey));						
					} else if (colName.equals("parent_budget_id")) {
						String k=getRemoteKey(DatabaseHelper.BUDGET_TABLE, String.valueOf(c.getInt(c.getColumnIndex(colName))));
						nameValuePairs.add(new BasicNameValuePair(colName,k));						
					}	else if (colName.equals("parent_id")) {
						int entity_id=c.getInt(c.getColumnIndex(colName));		
						try {
						Transaction myEntityEntityLoaded=(Transaction) em.load(getClassForColName(colName), entity_id);
						nameValuePairs.add(new BasicNameValuePair(colName,myEntityEntityLoaded.remoteKey));						
						} catch (Exception e) {
							Log.w("financisto" ,"unable to link " + tableName + " parent_id with " + getClassForColName(colName) +"::"+colName + " " + entity_id);							
							Log.w("financisto",e.getMessage());
						}
					}  else {
						String[] entities=c.getString(c.getColumnIndex(colName)).split(",");	
						String keys="";
						for (String entity_id2: entities) {

								try {
									T myEntityEntityLoaded=(T) em.load(getClassForColName(colName), entity_id2);	
									keys+=myEntityEntityLoaded.remoteKey + ",";								
								} catch (Exception e) {									
									Log.w("financisto" ,"unable to link " + tableName + " with " + getClassForColName(colName) +"::"+colName + " " + entity_id2);
									Log.w("financisto",e.getMessage());
								}
						}
						if (keys.endsWith(",")) {
							keys=keys.substring(0,keys.length()-1);
						}

						nameValuePairs.add(new BasicNameValuePair(colName,keys));
					}
				} else {
					nameValuePairs.add(new BasicNameValuePair(colName,c.getString(c.getColumnIndex(colName))));					
				}
			}		

			if (c.getColumnIndex("_id")>KEY_CREATE) {
				String ark=getAccountRemoteKeyFromEntity(tableName,c.getInt(c.getColumnIndex("_id")));
				if (ark!=null) {
					nameValuePairs.add(new BasicNameValuePair("account",ark));
				}
			}
			nameValuePairs.add(new BasicNameValuePair("lastSyncLocalTimestamp",String.valueOf(options.lastSyncLocalTimestamp)));
			for (NameValuePair p : nameValuePairs) {
				Log.i(TAG,p.toString());
			}
			String strResponse=httpPush(nameValuePairs);							
			
			if (strResponse.equals(FLOWZR_MSG_NET_ERROR) || strResponse.substring(0, 3).equals("500")) {
				return new Exception(strResponse);
			} else if (!strResponse.equals(FLOWZR_MSG_DELETED)) {				
				if (!tableName.equals("currency_exchange_rate")) {
					String sql="update " + tableName + " set remote_key='" + strResponse + "' where  _id=" + c.getInt(c.getColumnIndex("_id"));
					db.beginTransaction();
					db.execSQL(sql);
					db.setTransactionSuccessful();
					db.endTransaction();					
				}
			} 			
			return null;		    	    	
    }

    private String httpPush (ArrayList<NameValuePair> nameValuePairs) {
	        HttpPost httppost = new HttpPost(FLOWZR_API_URL + options.useCredential);
	        try {
				httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs,HTTP.UTF_8));
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
				return FLOWZR_MSG_NET_ERROR;				
			}
	        HttpResponse response;
	        String strResponse;
			try {
				response = http_client.execute(httppost);
		        HttpEntity entity = response.getEntity();
	            int code = response.getStatusLine().getStatusCode();
		        BufferedReader reader = new BufferedReader(new InputStreamReader(entity.getContent()));
				strResponse = reader.readLine(); 				 			
		        entity.consumeContent();			
		        if (code!=200) {
		        	return "500 " + Html.fromHtml(strResponse).toString();
		        }

			} catch (ClientProtocolException e) {
				e.printStackTrace();				
				return FLOWZR_MSG_NET_ERROR;				
			} catch (IOException e) {				
				e.printStackTrace();
				return FLOWZR_MSG_NET_ERROR;							
			}
			return strResponse;    	    	
    }
    
    private Object pushDelete() {
		String sql="select count(*) from " + DatabaseHelper.DELETE_LOG_TABLE ;		
		Cursor cursorCursor=db.rawQuery(sql, null);
		cursorCursor.moveToFirst();
		long total=cursorCursor.getLong(0);
    	
    	Cursor cursor=db.rawQuery("select table_name,remote_key from delete_log",null);
    	int i=0;
    	String del_list="";
    	if (cursor.moveToFirst()) {
    		do {
				if (progressListener != null) {	            
	                progressListener.onProgress((int)(Math.round(i*100/total)));	                
	            }  
				del_list+=cursor.getString(1) + ";";
    			i++;
    		} while (cursor.moveToNext());
			ArrayList<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
			nameValuePairs.add(new BasicNameValuePair("action","pushDelete"));
			nameValuePairs.add(new BasicNameValuePair("remoteKey",del_list));
    		String strResponse=httpPush(nameValuePairs);    		
    		try {
	    		if (strResponse.substring(0, 3).equals("500")) {
					return new Exception(strResponse);
	    		}
    		} catch (Exception e) { }
    	}    	
    	return null;
    }
    
    public Object finishDelete() {
    	db.execSQL("delete from " + DatabaseHelper.DELETE_LOG_TABLE);
    	return null;
    }
    
    /**
     * Pull Job
     */    
    private Object pullUpdate() {    	
    	Object o=null;
    	int i=0;
    	for (String tableName : tableNames) {      	   		
        	if (tableName=="transactions") {
        		//pull all remote accounts, accounts by accounts
        		String sql="select remote_key from account";		
        		Cursor c=db.rawQuery(sql, null);        		
        		if (c.moveToFirst()) {
	        		do {
        				if (((FlowzrSyncActivity)context).isCanceled)
        				{
        					return new Exception("operation canceled");
        				}
	        			o=pullUpdate(tableName,clazzArray[i],options.lastSyncLocalTimestamp,c.getString(c.getColumnIndex("remote_key")),null);          				        				
	        		} while (c.moveToNext());
        		c.close();
        		}
        	} else {
        		//otherwhise pull the table
				if (((FlowzrSyncActivity)context).isCanceled)
				{
					return new Exception("operation canceled");
				}
        		o=pullUpdate(tableName,clazzArray[i],options.lastSyncLocalTimestamp,null,null);           
        	}
        	progressListener.onProgress(Math.round(i*tableNames.length/100));        	
        	if (o instanceof Exception) {
        		return o;
        	}
        	i++;
        }
    	return o;
    }    
        
	private <T> Object pullUpdate(String tableName,Class<T> clazz,long  lastSyncLocalTimestamp,String account,String gotoDate)  {	
		String url=FLOWZR_API_URL + options.useCredential + "/?action=pull" + tableName + "&account=" + account + "&lastSyncLocalTimestamp=" + lastSyncLocalTimestamp  + "&gotoDate="+ gotoDate + "&localTimestamp=" + System.currentTimeMillis();
    	JSONObject jsonObjectResponse=readFlowzrJSON(url);  
    	try {
    		JSONArray entitiesAsJSON=jsonObjectResponse.getJSONArray(tableName);
    		for(int i = 0; i < entitiesAsJSON.length(); i++) {
    			JSONObject jsonObjectEntity = entitiesAsJSON.getJSONObject(i);

    			String remoteKey ="";
				//sometime server can set a transaction with no key (ex initial amount ...)
    			if (jsonObjectEntity.has("key")) {
    					remoteKey=jsonObjectEntity.getString("key");    			
    			}
   			
    			Object o=null;
				if (clazz==Account.class) {    			
					o=saveOrUpdateAccountFromJSON(getLocalKey(tableName,remoteKey),jsonObjectEntity);					
				} else if (clazz==Transaction.class) {
					o=saveOrUpdateTransactionFromJSON(getLocalKey(tableName,remoteKey),jsonObjectEntity);					
				} else if (clazz==Currency.class) {
					o=saveOrUpdateCurrencyFromJSON(getLocalKey(tableName,remoteKey),jsonObjectEntity);					
				} else if (clazz==Budget.class) {
					o=saveOrUpdateBudgetFromJSON(getLocalKey(tableName,remoteKey),jsonObjectEntity);					
				} else if (clazz==MyLocation.class) {
					o=saveOrUpdateLocationFromJSON(getLocalKey(tableName,remoteKey),jsonObjectEntity);					
				} else if (tableName.equals("currency_exchange_rate"))  {
					o=saveOrUpdateCurrencyRateFromJSON(jsonObjectEntity);
				} else if (clazz==Category.class)  {
						o=saveOrUpdateCategoryFromJSON(getLocalKey(tableName,remoteKey),jsonObjectEntity);						
				}else if (clazz==Attribute.class)  {
					o=saveOrUpdateAttributeFromJSON(getLocalKey(tableName,remoteKey),jsonObjectEntity);						
				}  else  {
					o=saveOrUpdateEntityFromJSON(clazz,getLocalKey(tableName,remoteKey),jsonObjectEntity);										
				} 
				if (o instanceof Exception ) {
					return o;
				}
				if (progressListener != null) {	            					
	                progressListener.onProgress((int)(Math.round(i*100/entitiesAsJSON.length())));
	            } 
    		}
    		JSONArray jsonParams=jsonObjectResponse.getJSONArray("params");
			JSONObject jsonParam = jsonParams.getJSONObject(0);
    		if (jsonParam.has("gotoDate")) {
    			pullUpdate(tableName,Transaction.class,0,account,jsonParam.getString("gotoDate"));    			    			
    		}
        	return null;    		
    	} catch (Exception e) {
    		e.printStackTrace();
    		return e;
    	}     	
    }

	private Object saveOrUpdateAttributeFromJSON(long localKey,
			JSONObject jsonObjectEntity) {
		if (!jsonObjectEntity.has("name")) {
			return null;
		}
		
		Attribute tEntity=dba.getAttribute(localKey);
		
		try {			
			tEntity.remoteKey=jsonObjectEntity.getString("key");
			tEntity.name=jsonObjectEntity.getString("name");
			if (jsonObjectEntity.has("type")) {
				tEntity.type=jsonObjectEntity.getInt("type");
			}
			if (jsonObjectEntity.has("default_value")) {
				tEntity.defaultValue=jsonObjectEntity.getString("default_value");
			}
			if (jsonObjectEntity.has("list_values")) {			
				tEntity.listValues=jsonObjectEntity.getString("list_values");
			}
			dba.insertOrUpdate(tEntity);
			return tEntity;
		} catch (Exception e) {
			e.printStackTrace();
			return e;
		}	
	}

	public <T> Object saveOrUpdateEntityFromJSON(Class<T> clazz,long id,JSONObject jsonObjectEntity) {					
		if (!jsonObjectEntity.has("name")) {
			return null;
		}
		MyEntity tEntity=(MyEntity) em.get(clazz, id);
		if (tEntity==null) {
			if (clazz==Project.class) {
				tEntity= new Project();
			} else if (clazz==Payee.class) {
				tEntity=new Payee();
			} 
			tEntity.id=KEY_CREATE; 			
		}
		//---
		try {			
			tEntity.remoteKey=jsonObjectEntity.getString("key");
			((MyEntity)tEntity).title=jsonObjectEntity.getString("name");
			if ((clazz)==Project.class) {
				if (jsonObjectEntity.has("is_active")) {
					if (jsonObjectEntity.getBoolean("is_active")) {
						((Project)tEntity).isActive=true;
					} else {
						((Project)tEntity).isActive=false;						
					}
				}
			}
			em.saveOrUpdate((MyEntity)tEntity);
			return tEntity;
		} catch (Exception e) {
			e.printStackTrace();
			return e;
		}		 		
	}

	public <T> Object saveOrUpdateCategoryFromJSON(long id,JSONObject jsonObjectEntity) {					
		if (!jsonObjectEntity.has("name")) {
			return null;
		}
		Category tEntity=new Category(KEY_CREATE);
		if (id != KEY_CREATE) {
			tEntity = dba.getCategory(id);				
		}
		//---
		try {			
			tEntity.remoteKey=jsonObjectEntity.getString("key");
			tEntity.title=jsonObjectEntity.getString("name");
			if (jsonObjectEntity.has("parentCategory") ) {
				try {			
					int l=(int) getLocalKey(DatabaseHelper.CATEGORY_TABLE, jsonObjectEntity.getString("parentCategory"));
					Category cParent=dba.getCategory(l);
					if (l!=KEY_CREATE) {
						tEntity.parent=cParent;
					}
				} catch (Exception e) {			
					Log.e(TAG,"Error setting parent to :" + jsonObjectEntity.getString("parentCategory"));					
					e.printStackTrace();
				}
			}
			ArrayList<Attribute> attributes = new ArrayList<Attribute>();
			if (jsonObjectEntity.has("attributes") ) {				
				for (String attr_key: jsonObjectEntity.getString("attributes").split(";")) {
						int l=(int) getLocalKey(DatabaseHelper.ATTRIBUTES_TABLE, attr_key);
						if (l>0) {						
							Attribute attr=dba.getAttribute(l);
							attributes.add(attr);
						}
				}				
			}
			//updated on + remote key
			em.saveOrUpdate(tEntity);
			//left, right
			dba.insertOrUpdate(tEntity, attributes);

			
			return tEntity;
		} catch (Exception e) {
			e.printStackTrace();
			return e;
		}		 		
	}
	
	public Object saveOrUpdateCurrencyRateFromJSON(JSONObject jsonObjectEntity) {		
		if (!jsonObjectEntity.has("effective_date")) {
			return null;
		}
		try {
			Currency toCurrency=em.load(Currency.class, getLocalKey(DatabaseHelper.CURRENCY_TABLE, jsonObjectEntity.getString("to_currency")));
			Currency fromCurrency=em.load(Currency.class, getLocalKey(DatabaseHelper.CURRENCY_TABLE, jsonObjectEntity.getString("from_currency")));
			long effective_date=jsonObjectEntity.getLong("effective_date")*1000;
			double rate=jsonObjectEntity.getDouble("rate");
			ExchangeRate exRate= new ExchangeRate();				
			exRate.toCurrencyId=toCurrency.id;
			exRate.fromCurrencyId=fromCurrency.id;
			exRate.rate=rate;
			exRate.date=effective_date;
			dba.saveRate(exRate);
		} catch (Exception e) {
			Log.e(TAG,"unable to load a currency rate from server...");
			e.printStackTrace();
			
		}	
		return null;
	}
	
	public Object saveOrUpdateBudgetFromJSON(long id,JSONObject jsonObjectEntity) {
		Budget tEntity=em.get(Budget.class, id);
		Log.e(TAG,"loading budget" + String.valueOf(id));
		if (tEntity==null) {
			tEntity = new Budget();
			tEntity.id=KEY_CREATE; 									
		}			
//		try {
			try {
				tEntity.remoteKey=jsonObjectEntity.getString("key");
			} catch (JSONException e2) {
				// TODO Auto-generated catch block
				e2.printStackTrace();
			} 
			if (jsonObjectEntity.has("title")) {	
				try {
					tEntity.title=jsonObjectEntity.getString("title");
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (jsonObjectEntity.has("categories")) {	
				try {
					String[] strArrCategories=jsonObjectEntity.getString("categories").split(",");
					tEntity.categories="";
					for (String key: strArrCategories) {
						tEntity.categories+=getLocalKey(DatabaseHelper.CATEGORY_TABLE, key)+",";
					}	
					if (tEntity.categories.endsWith(",")) {
						tEntity.categories=tEntity.categories.substring(0, tEntity.categories.length()-1);
					}

				} catch (Exception e) {
					Log.e(TAG,"Error parsing Budget categories");
					e.printStackTrace();
				}
			}
			if (jsonObjectEntity.has("projects")) {	
				try {
					String[] strArrProjects=jsonObjectEntity.getString("projects").split(",");
					tEntity.projects="";
					for (String key: strArrProjects) {
						tEntity.projects+=getLocalKey(DatabaseHelper.PROJECT_TABLE, key)+",";
					}				
					if (tEntity.projects.endsWith(",")) {
						tEntity.projects=tEntity.projects.substring(0, tEntity.projects.length()-1);
					}									
				} catch (Exception e) {
					Log.e(TAG,"Error parsing Budget.project_id ");				
					e.printStackTrace();
				}
			}
			if (jsonObjectEntity.has("currency")) {				
				try {
					tEntity.currencyId=getLocalKey(DatabaseHelper.CURRENCY_TABLE, jsonObjectEntity.getString("currency"));
				} catch (Exception e) {
					Log.e(TAG,"Error parsing Budget.currency ");				
					e.printStackTrace();
				}
			}
			if (jsonObjectEntity.has("amount")) {			
				try {
					tEntity.amount=(long)jsonObjectEntity.getDouble("amount")*100;
				} catch (Exception e) {
					Log.e(TAG,"Error parsing Budget.amount");								
				}
			}
			
			if (jsonObjectEntity.has("includeSubcategories")) {
				try {
					tEntity.includeSubcategories=jsonObjectEntity.getBoolean("includeSubcategories");
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} 		
			if (jsonObjectEntity.has("expanded")) {
				try {
					tEntity.expanded=jsonObjectEntity.getBoolean("expanded");
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} 
			if (jsonObjectEntity.has("include_credit")) {
				try {
					tEntity.includeSubcategories=jsonObjectEntity.getBoolean("include_credit");
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} 		
			if (jsonObjectEntity.has("startDate")) {
				try {
					tEntity.startDate = jsonObjectEntity.getLong("startDate")*1000;
				} catch (Exception e1) {					
					Log.e(TAG,"Error parsing Budget.startDate");
					e1.printStackTrace();
				}
			}
			if (jsonObjectEntity.has("endDate")) {			
				try {
					tEntity.endDate = jsonObjectEntity.getLong("endDate")*1000;
				} catch (Exception e1) {					
					Log.e(TAG,"Error parsing Budget.endDate");
					e1.printStackTrace();					
				}
			}
			if (jsonObjectEntity.has("recur")) {
				try {
					tEntity.recur=jsonObjectEntity.getString("recur");
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (jsonObjectEntity.has("recurNum")) {
				try {
					tEntity.recurNum=jsonObjectEntity.getInt("recurNum");
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (jsonObjectEntity.has("isCurrent")) {
				try {
					tEntity.isCurrent=jsonObjectEntity.getBoolean("isCurrent");
				} catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (jsonObjectEntity.has("parent_budget_id")) {
				try {
					tEntity.parentBudgetId=getLocalKey(DatabaseHelper.BUDGET_TABLE, jsonObjectEntity.getString("parent_budget_id"));
				} catch (Exception e) {
					
					Log.e(TAG,"Error parsing Budget.parentBudgetId ");				
					e.printStackTrace();
				}
			}
			em.insertBudget(tEntity);
			//em.saveOrUpdate(tEntity);
			
			return tEntity;
//		} catch (Exception e) {			
//			e.printStackTrace();
//			return e;
//		}		 						
	}	
	
	public Object saveOrUpdateLocationFromJSON(long id,JSONObject jsonObjectEntity) {				
		MyLocation tEntity=em.get(MyLocation.class, id); 
		if (tEntity==null) {
			tEntity=new MyLocation();			
			tEntity.id=KEY_CREATE; 			
		}		
		try {
			tEntity.remoteKey=jsonObjectEntity.getString("key"); 			
			if (jsonObjectEntity.has("name")) {
				tEntity.name=jsonObjectEntity.getString("name"); 			
			} else {
				tEntity.name="---";
			}
			if (jsonObjectEntity.has("provider")) {
				tEntity.provider=jsonObjectEntity.getString("provider"); 
			}
			if (jsonObjectEntity.has("accuracy")) {
				try {
					tEntity.accuracy=Float.valueOf(jsonObjectEntity.getString("accuracy")); 	   
				} catch (Exception e) {
					Log.e(TAG,"Error parsing MyLocation.accuracy with : " + jsonObjectEntity.getString("accuracy"));				
				}
			}
			if (jsonObjectEntity.has("geo_point")) {
				JSONObject pt=jsonObjectEntity.getJSONObject("geo_point");
				tEntity.latitude=pt.getDouble("lat");
				tEntity.longitude=pt.getDouble("lon");		
			}
			if (jsonObjectEntity.has("is_payee")) {
				if (jsonObjectEntity.getBoolean("is_payee")) {
					tEntity.isPayee=true;
				} else {
					tEntity.isPayee=false;				
				}
			}
			if (jsonObjectEntity.has("resolved_adress")) {
				tEntity.resolvedAddress=jsonObjectEntity.getString("resolved_adress");
			}
			if (jsonObjectEntity.has("dateOfEmission")) {
				try {
					tEntity.dateTime = jsonObjectEntity.getLong("dateOfEmission");
		 		} catch (Exception e1) {					
					Log.e(TAG,"Error parsing MyLocation.dateTime with : " + jsonObjectEntity.getString("dateOfEmission"));
				}
			}
			if (jsonObjectEntity.has("count")) {
				tEntity.count=jsonObjectEntity.getInt("count");						
			}
			if (jsonObjectEntity.has("dateTime")) {
				tEntity.dateTime=jsonObjectEntity.getLong("dateTime");						
			}

			em.saveOrUpdate(tEntity);					

			return tEntity;
		} catch (Exception e) {
			e.printStackTrace();
			return e;
		}		 		
	}
	
	
	public Object saveOrUpdateCurrencyFromJSON(long id,JSONObject jsonObjectEntity) {
		Currency tEntity=em.get(Currency.class, id);
		if (tEntity==null) {
			tEntity = Currency.EMPTY;
			tEntity.id=KEY_CREATE; 									
		}					
		try {	
			tEntity.remoteKey=jsonObjectEntity.getString("key"); 
			if (jsonObjectEntity.has("title")) {
				tEntity.title=jsonObjectEntity.getString("title");	
			}
			if (jsonObjectEntity.has("name")) {
				tEntity.name=jsonObjectEntity.getString("name");	
			}
			try {
				tEntity.symbol=jsonObjectEntity.getString("symbol");						
			} catch (Exception e) {
				Log.e(TAG,"Error pulling Currency.symbol");					
				e.printStackTrace();
			}
			tEntity.isDefault=false;		
			if (jsonObjectEntity.has("isDefault")) {
				if (jsonObjectEntity.getBoolean("isDefault")) {
					tEntity.isDefault=jsonObjectEntity.getBoolean("isDefault");
				} 
			}
			if (jsonObjectEntity.has("decimals")) {
				try {
					tEntity.decimals=jsonObjectEntity.getInt("decimals");			
				}  catch (Exception e) {
					Log.e(TAG,"Error pulling Currency.decimals");					
					e.printStackTrace();
				}
			}
			try {
				tEntity.decimalSeparator=jsonObjectEntity.getString("decimalSeparator");
			} catch (Exception e) {
				Log.e(TAG,"Error pulling Currency.symbol");					
			}
			try {
				tEntity.groupSeparator=jsonObjectEntity.getString("groupSeparator");
			} catch (Exception e) {
				Log.e(TAG,"Error pulling Currency.symbol");					
			}
			em.saveOrUpdate(tEntity); 				
			return tEntity;
		} catch (Exception e) {			
			e.printStackTrace();
			return e;
		}		 						
	}
	
	public Object saveOrUpdateAccountFromJSON(long id,JSONObject jsonObjectAccount) {

		Account tEntity=em.get(Account.class, id);

		if (tEntity==null) {
			tEntity = new Account();
			tEntity.id=KEY_CREATE; 									
		}		
  						
		try {			
			//title
			try {
			tEntity.title=jsonObjectAccount.getString("name");
			} catch (Exception e) {
				tEntity.title="---";
				Log.e(TAG,"Error parsing Account.name with");
			} 
			tEntity.remoteKey=jsonObjectAccount.getString("key");	
			//creation_date
			try {
				tEntity.creationDate =jsonObjectAccount.getLong("created_on");
			} catch (Exception e) {
				Log.e(TAG,"Error parsing Account.creationDate");
			}
			//last_transaction_date
			try {
				tEntity.lastTransactionDate = jsonObjectAccount.getLong("dateOfLastTransaction");
			} catch (Exception e) {
				Log.e(TAG,"Error parsing Account.dateOfLastTransaction with : " + jsonObjectAccount.getString("dateOfLastTransaction"));
			}			
			//currency, currency_name, currency_symbol
			Currency c=null;			
			Collection<Currency> currencies=CurrencyCache.getAllCurrencies();			
			if (jsonObjectAccount.has("currency_id")) {			
				try {
					c=em.load(Currency.class,getLocalKey(DatabaseHelper.CURRENCY_TABLE, jsonObjectAccount.getString("currency_id")));				
					tEntity.currency=c;
				} catch (Exception e) {
					Log.e(TAG,"unable to load currency for account "  + tEntity.title + " with " +  jsonObjectAccount.getString("currency_id"));
				}
			//server created account don't have a currency_id but all the properties to build one.
			} 
			if (tEntity.currency==null && jsonObjectAccount.has("currency")) {
				//try if provided currency is in user's currency
				for (Currency currency: currencies) {
					if (currency.name.equals(jsonObjectAccount.getString("currency"))) {
						tEntity.currency=currency;						
					}
				}
				//load from data server if any
				if (tEntity.currency==null) {
					c=Currency.EMPTY;	
					c.name=jsonObjectAccount.getString("currency");
					if (jsonObjectAccount.has("currency_name")) {					
						c.title=jsonObjectAccount.getString("currency_name");
					}
					if (jsonObjectAccount.has("currency_symbol")) {							
						c.symbol=jsonObjectAccount.getString("currency_symbol");
					}	
					tEntity.currency=c;
					c.id=-1; //db put!
					em.saveOrUpdate(c);							
				}
			} else if  (tEntity.currency==null) {
				//no currency provided use default
				for (Currency currency: currencies) {
					if (currency.isDefault) {
						tEntity.currency=currency;						
					}
				}				
				//still nothing : default set to empty
				if (tEntity.currency==null) {
					c=Currency.EMPTY;	
					c.isDefault=true;
					tEntity.currency=c;	
					c.id=-1; //db put!
					em.saveOrUpdate(c);							
				}
			}
			CurrencyCache.initialize(em);			
			//card_issuer
		 	if (jsonObjectAccount.has("card_issuer")) {
		 		tEntity.cardIssuer=jsonObjectAccount.getString("card_issuer");
		 	} 
		 	//issuer
		 	if (jsonObjectAccount.has(DatabaseHelper.AccountColumns.ISSUER)) {			 	
		 		tEntity.issuer=jsonObjectAccount.getString(DatabaseHelper.AccountColumns.ISSUER);				
		 	}
		 	//number
		 	if (jsonObjectAccount.has("iban")) {				 	
		 		tEntity.number=jsonObjectAccount.getString("iban");
		 	}
		 	//is_active
		 	if (jsonObjectAccount.has("closed")) {			 	
		 		if (jsonObjectAccount.getBoolean("closed")) {
		 			tEntity.isActive=false;
		 		} else {
		 			tEntity.isActive=true;
		 		}
		 	}
			//is_include_into_totals
		 	if (jsonObjectAccount.has(DatabaseHelper.AccountColumns.IS_INCLUDE_INTO_TOTALS)) {			 	
				if (jsonObjectAccount.getBoolean(DatabaseHelper.AccountColumns.IS_INCLUDE_INTO_TOTALS)) {
					tEntity.isIncludeIntoTotals=true;
				} else  {
					tEntity.isIncludeIntoTotals=false;
				}			
		 	}
			//closing_day
			try {
				tEntity.closingDay=jsonObjectAccount.getInt(DatabaseHelper.AccountColumns.CLOSING_DAY);
			}	catch (Exception e) {}
			//payment_day
			try {
				tEntity.paymentDay=jsonObjectAccount.getInt(DatabaseHelper.AccountColumns.PAYMENT_DAY);    
			}	catch (Exception e) {}
			//note
		 	if (jsonObjectAccount.has("description")) {	
		 		tEntity.note=jsonObjectAccount.getString("description");
		 	}
		 	//sort_order
		 	if (jsonObjectAccount.has(DatabaseHelper.AccountColumns.SORT_ORDER)) {
		 		tEntity.sortOrder=jsonObjectAccount.getInt(DatabaseHelper.AccountColumns.SORT_ORDER);
		 	}
		 	if (jsonObjectAccount.has(DatabaseHelper.AccountColumns.TYPE)) {
		 		tEntity.type=jsonObjectAccount.getString(DatabaseHelper.AccountColumns.TYPE);
		 	}
	 	
			em.saveOrUpdate(tEntity);						
			return tEntity;			
		} catch (Exception e1) {					
			e1.printStackTrace();
			return e1;				
		} 	
	}

	public Object saveOrUpdateTransactionFromJSON(long id,JSONObject jsonObjectResponse) {

		Transaction tEntity=em.get(Transaction.class, id);
		
		if (tEntity==null) {
			tEntity= new Transaction();
			tEntity.id=KEY_CREATE; 			
		}	
		
			try {				
				//from_account_id,       			
				try {
					tEntity.fromAccountId=getLocalKey(DatabaseHelper.ACCOUNT_TABLE, jsonObjectResponse.getString("account"));
				} catch (Exception e1) {					
					Log.e("financisto","Error parsing Transaction.fromAccount with : " + jsonObjectResponse.getString("account"));
					return null;				
				} 
				
				//to_account_id,
				if (jsonObjectResponse.has("to_account")) {       			
					try {
						tEntity.toAccountId=getLocalKey(DatabaseHelper.ACCOUNT_TABLE, jsonObjectResponse.getString("to_account")); 						
					} catch (Exception e1) {											 						
						//					
					} 					
				} else {
					tEntity.toAccountId=0;
				}
				try {
					tEntity.dateTime = jsonObjectResponse.getLong("dateOfEmission")*1000;								
					if (jsonObjectResponse.has("dateTime")) {					
						tEntity.dateTime=jsonObjectResponse.getLong("dateTime")*1000;
					}
				} catch (Exception e1) {
					tEntity.dateTime=System.currentTimeMillis();			
					e1.printStackTrace();
				} 

				if (jsonObjectResponse.has("key")) {
					tEntity.remoteKey=jsonObjectResponse.getString("key");					
				} 
				//from_amount,       			
				double debit=0.0;
				double credit=0.0;       	
				if (jsonObjectResponse.has("debit")) {
					try {
						debit=jsonObjectResponse.getDouble("debit")*100;
					} catch (Exception e) { 
						e.printStackTrace();
					}
				}
				if (jsonObjectResponse.has("credit")) {				
					try {
						credit=jsonObjectResponse.getDouble("credit")*100;
					} catch (Exception e) {
						e.printStackTrace();
					}				
				}
				tEntity.fromAmount=(long)(credit-debit);

			       									
				if (jsonObjectResponse.has("to_amount")) {
					if ((long)jsonObjectResponse.getDouble("to_amount")*100 != 0.0) {
						((Transaction)tEntity).toAmount=(long)jsonObjectResponse.getDouble("to_amount")*100;       				
					}     				
				}

				/**
			    @Column(name = "original_currency_id")
			    public long originalCurrencyId;
				**/
				if (jsonObjectResponse.has("original_currency_id")) {
					try {					
					((Transaction)tEntity).originalCurrencyId=getLocalKey(DatabaseHelper.CURRENCY_TABLE, jsonObjectResponse.getString("original_currency_id"));
					} catch (Exception e) {
						//Log.e("financisto","Error parsing Transaction.original_currency_id with : " + jsonObjectResponse.getString("original_currency_id"));						
					}					
				}
				if (jsonObjectResponse.has("original_from_amount")) {
					((Transaction)tEntity).originalFromAmount=(long)jsonObjectResponse.getDouble("original_from_amount")*100;       				
				}								
				
				if (jsonObjectResponse.has("description")) {
					tEntity.note=jsonObjectResponse.getString("description");				
				}
				//parent_tr
				if (jsonObjectResponse.has("parent_tr")) {
					try {					
					tEntity.parentId=getLocalKey(DatabaseHelper.TRANSACTION_TABLE, jsonObjectResponse.getString("parent_tr"));
					Transaction parent_tr=em.load(Transaction.class, tEntity.parentId);
					parent_tr.categoryId=Category.SPLIT_CATEGORY_ID;
					em.saveOrUpdate(parent_tr);					
					} catch (Exception e) {
						//Log.e("financisto","Error parsing/saving Transaction.parent_tr with : " + jsonObjectResponse.getString("parent_tr"));						
					}
				}								
				//category_id,       			
				if (jsonObjectResponse.has("cat")) {       		
					try {
						//transaction could have been writed with no category from one of it's split
						//or from transaction activity in that case pull will not change.
						if (((Transaction)tEntity).categoryId!=Category.SPLIT_CATEGORY_ID) {
							// in the other case:
							// SPLIT_CATEGORY and NO_CATEGORY are never pulled so they nether get
							// a local key, so get local key return -1 
							long l = getLocalKey(DatabaseHelper.CATEGORY_TABLE, jsonObjectResponse.getString("cat"));
							if (l<=Category.NO_CATEGORY_ID) {
								((Transaction)tEntity).categoryId=Category.NO_CATEGORY_ID;
							} else {
								//set the category
								((Transaction)tEntity).categoryId=l;								
							}
						}
						
					} catch (Exception e1) {					
						tEntity.categoryId=Category.NO_CATEGORY_ID;
						//Log.e("financisto","Error parsing Transaction.categoryId with : " + jsonObjectResponse.getString("cat"));			
					} 						
				} else {
					tEntity.categoryId=Category.NO_CATEGORY_ID;					
				}
				//project_id,
				if (jsonObjectResponse.has("project")) {
					try {
						((Transaction)tEntity).projectId=getLocalKey(DatabaseHelper.PROJECT_TABLE, jsonObjectResponse.getString("project"));
					} catch (Exception e1) {					
						Log.e("financisto","Error parsing Transaction.ProjectId with : " + jsonObjectResponse.getString("project"));		
					} 					
 
				}
				//payee_id,
				if (jsonObjectResponse.has("payee_id")) {
					try {
						((Transaction)tEntity).payeeId=getLocalKey(DatabaseHelper.PAYEE_TABLE, jsonObjectResponse.getString("payee_id"));  
					} catch (Exception e1) {					
						//Log.e("financisto","Error parsing Transaction.ProjectId with : " + jsonObjectResponse.getString("payee_id"));
						//e1.printStackTrace();
						//return e1;					
					} 						     				
				}       			
 
				//location_id
				if (jsonObjectResponse.has("location_id")) {
					try {
						long lid=getLocalKey(DatabaseHelper.LOCATIONS_TABLE, jsonObjectResponse.getString("location_id"));
						if (lid>0) {
							((Transaction)tEntity).locationId=lid;
						}
					} catch (Exception e1) {					
						//Log.e("financisto","Error parsing Transaction.location_id with : " + jsonObjectResponse.getString("location_id"));					
					} 						
				}
				//accuracy,provider,latitude,longitude
				if (jsonObjectResponse.has("geo_point")) {
					if (jsonObjectResponse.has("provider")) {				
						tEntity.provider=jsonObjectResponse.getString("provider");
					}
					if (jsonObjectResponse.has("accuracy")) {	
						try {
							tEntity.accuracy=jsonObjectResponse.getLong("accuracy");
						} catch (Exception e) {
							//Log.e("financisto","Error getting accuracy value for transaction with:" + jsonObjectResponse.getString("accuracy"));
						}
					}
					try {
						JSONObject pt=jsonObjectResponse.getJSONObject("geo_point");
						tEntity.latitude=pt.getDouble("lat");
						tEntity.longitude=pt.getDouble("lon");
					}	catch (Exception e) {
						//Log.e("financisto","Error getting geo_point value for transaction with:" + jsonObjectResponse.getString("geo_point"));
					}
				}

				tEntity.status=TransactionStatus.UR;
				if (jsonObjectResponse.has("status")) {
					//server doesn't have excactly the same data model status is overrided					
					if (jsonObjectResponse.getString("status").equals("RS")) {
						tEntity.status=TransactionStatus.RS;
					}
					if (jsonObjectResponse.getString("status").equals("PN")) {
						tEntity.status=TransactionStatus.PN;
					}
					if (jsonObjectResponse.getString("status").equals("UR")) {
						tEntity.status=TransactionStatus.UR;
					}
					if (jsonObjectResponse.getString("status").equals("CL")) {
						tEntity.status=TransactionStatus.CL;
					}						
					if (jsonObjectResponse.getString("status").equals("RC")) {
						tEntity.status=TransactionStatus.RC;
					}			
				}
				//is_ccard_payment,
				if (jsonObjectResponse.has("is_ccard_payment")) {				
						((Transaction)tEntity).isCCardPayment=jsonObjectResponse.getInt("is_ccard_payment");
				}
				List<TransactionAttribute> attributes = new LinkedList<TransactionAttribute>();
				if (jsonObjectResponse.has("transaction_attribute")) {
					
			    	for (String pair : jsonObjectResponse.getString("transaction_attribute").split(";")) {
			    		String [] tmp=pair.split("=");
			    		if (tmp.length==2) {
			    			String key=tmp[0];
			    			TransactionAttribute a = new TransactionAttribute();
			    			a.value=tmp[1];
			    			a.attributeId=getLocalKey(DatabaseHelper.ATTRIBUTES_TABLE, tmp[0]);
			    			a.transactionId=tEntity.id;
			    			attributes.add(a);
			    		} else {
			    			Log.e(TAG,"transaction attribute: invalid array size");
			    		}
			    		
			    	}
				}
				// end main transaction data				
				if (!jsonObjectResponse.has("from_crebit")) {
					//long id = db.insertOrUpdate(transaction, getAttributes());
				
					id=em.saveOrUpdate((Transaction)tEntity);        		
					if (attributes!=null) {
			            dba.db().delete(DatabaseHelper.TRANSACTION_ATTRIBUTE_TABLE, DatabaseHelper.TransactionAttributeColumns.TRANSACTION_ID+"=?",
			                    new String[]{String.valueOf(id)});
			    		for (TransactionAttribute a : attributes) {
			    			a.transactionId=id;
			    			ContentValues values = a.toValues();
							dba.db().insert(TRANSACTION_ATTRIBUTE_TABLE, null, values);
						}
					}
				}
			} catch (Exception e) {				
				e.printStackTrace();
				return e;
			}		
		return tEntity;
	}
	
	
    public long getLocalKey(String tableName,String remoteKey) {
		String sql="select _id from " + tableName + " where remote_key= '" + remoteKey + "';";
		Cursor c=db.rawQuery(sql, null);

		if (c.moveToFirst()) {
			long l = c.getLong(0);
			c.close();
			return l;
		} else {
			c.close();
    	    return KEY_CREATE;
		}
    }

    public String getRemoteKey(String tableName,String localKey) {
		String sql="select remote_key from " + tableName + " where _id= '" + localKey + "';";
		Cursor c=db.rawQuery(sql, null);

		if (c.moveToFirst()) {
			String l = c.getString(0);
			c.close();
			return l;
		} else {
			c.close();
    	    return null;
		}
    }
    
    
    public JSONObject readFlowzrJSON(String url) {
    	 // Making HTTP request
    	int code=200;
        try {
            // http_client parametred with auth at the activity level (need user response)
            HttpGet httpGet = new HttpGet(url); 
            HttpResponse httpResponse = http_client.execute(httpGet);
            HttpEntity httpEntity = httpResponse.getEntity();
            isHttpcontent = httpEntity.getContent();         
            code = httpResponse.getStatusLine().getStatusCode();

        } catch (Exception e) {
            e.printStackTrace();
        } 
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(isHttpcontent, "UTF-8"), 8);

            String line = null;
            while ((line = reader.readLine()) != null) {
                sb.append(line + "n");
            }
            isHttpcontent.close();
            if (code!=200) {
            	Log.e("financisto", sb.toString());
            	return new JSONObject("{ \"error\": [{\"message\": \"" + Html.fromHtml(sb.toString()).toString() + "\"}]}");
            }            
            json = sb.toString();
            // try parse the string to a JSON object
            try {
                jObj = new JSONObject(json);
            } catch (JSONException e) {
                //Log.e("JSON Parser", "Error parsing data " + e.toString());
                return new JSONObject("{ \"error\": [{\"message\": \"" + Html.fromHtml(sb.toString()).toString().substring(0, 20) + "\"}]}");
            } 
        
        } catch (Exception e) {
            Log.e("financisto", "Error converting result " + e.toString());
        }
 
        // return JSON String
        return jObj; 
      }

    public void setProgressListener(ProgressListener progressListener) {
        this.progressListener = progressListener;
    }


    
	private Object pullDelete(long  lastSyncLocalTimestamp)  {			
		String url=FLOWZR_API_URL + options.useCredential + "?action=pullDelete&lastSyncLocalTimestamp=" + lastSyncLocalTimestamp +  "&localTimestamp=" + System.currentTimeMillis();
    	JSONObject jsonObjectResponse=readFlowzrJSON(url);      	
    	try {
    		JSONArray entitiesAsJSON=new JSONArray();
    		try {
    			entitiesAsJSON=jsonObjectResponse.getJSONArray(DatabaseHelper.DELETE_LOG_TABLE);
    		} catch (Exception e) {
	
				
    			url="https://flowzr-hrd.appspot.com/_ah/logout?continue=https://www.google.com/accounts/Logout%3Fcontinue%3Dhttps://appengine.google.com/_ah/logout%253Fcontinue%253Dhttp://www.flowzr.com/%26service%3Dah";
    		    try {
    		    		http_client.getParams().setBooleanParameter(ClientPNames.HANDLE_REDIRECTS, true);
    		            // http_client parametred with auth at the activity level (need user response)
    		            HttpGet httpGet = new HttpGet(url); 
    		            HttpResponse httpResponse = http_client.execute(httpGet);
    		        } catch (Exception f) {
    		            f.printStackTrace();
    		            return new Exception("Logged out please retry");
    		       } 
    			http_client.getCookieStore().clear();
    			return new Exception(jsonObjectResponse.getJSONArray("error").getJSONObject(0).getString("message"));
    		}
    		for(int i = 0; i < entitiesAsJSON.length(); i++) {
    			JSONObject jsonObjectEntity = entitiesAsJSON.getJSONObject(i);
    			String remoteKey = jsonObjectEntity.getString("key");    			
    			String tableName = jsonObjectEntity.getString("tableName");
    					
    			long id=getLocalKey(tableName,remoteKey);  			
    			Object o=null;
    			if (id>0) {
					if (tableName.equals(DatabaseHelper.ACCOUNT_TABLE)) {    
						dba.deleteAccount(id);				
					} else if (tableName.equals(DatabaseHelper.TRANSACTION_TABLE)) {
						dba.deleteTransaction(id);								
					} else if (tableName.equals(DatabaseHelper.CURRENCY_TABLE)) {
						em.deleteCurrency(id);					
					} else if (tableName.equals(DatabaseHelper.BUDGET_TABLE)) {
						em.deleteBudget(id);
					} else if (tableName.equals(DatabaseHelper.LOCATIONS_TABLE)) {
						em.deleteLocation(id);
					} else if (tableName.equals(DatabaseHelper.PROJECT_TABLE)) {
						em.deleteProject(id);							
					} else if (tableName.equals(DatabaseHelper.PAYEE_TABLE)) {
						em.delete(Payee.class,id);								
					} else  if (tableName.equals(DatabaseHelper.CATEGORY_TABLE)) {
						dba.deleteCategory(id);
					}
    			}
				if (progressListener != null) {	            					
	                progressListener.onProgress((int)(Math.round(i*100/entitiesAsJSON.length())));
	            } 
    		}
        	return null;    		
    	} catch (JSONException e) {    	
    		 return new Exception(e);
    		
    	}    	
    }
}