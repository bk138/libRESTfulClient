/**
 * A threaded REST client implementation.
 *
 * @author Christian Beier
 *
 * Copyright (C) 2012 CoboltForge
 *
 * This is proprietary software, all rights reserved!
 * You MUST contact hello@coboltforge.com if you want to use this software in your own product!
 *
 */

package com.coboltforge.restful;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.json.JSONObject;

import android.content.Context;
import android.os.Handler;
import android.util.Log;


public class RESTfulClient  {

	private final String TAG="RESTfulClient";
	private DefaultHttpClient httpClient; //only accessed by commThread
	private CommThread commThread;
	public final int SC_OK = 42;
	public final int SC_ERR = 666;
	private int status = SC_OK;

	public RESTfulClient () {
		this(null, 0, null);
	}

	public RESTfulClient (String user, String pass) {
		this(null, 0, null);
		httpClient.getCredentialsProvider().setCredentials(new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
				new UsernamePasswordCredentials(user, pass));
	}

	public RESTfulClient (Context ctx, int bksResource, String pass) {

		if(ctx == null || bksResource == 0 || pass == null) {
			HttpParams httpParams = new BasicHttpParams();
			httpClient = new DefaultHttpClient(httpParams);
		}
		else {
			final SchemeRegistry schemeRegistry = new SchemeRegistry();
			schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
			schemeRegistry.register(new Scheme("https", createAdditionalCertsSSLSocketFactory(ctx, bksResource, pass), 443));

			// create connection manager using scheme, we use ThreadSafeClientConnManager
			final HttpParams params = new BasicHttpParams();
			final ThreadSafeClientConnManager cm = new ThreadSafeClientConnManager(params,schemeRegistry);
			httpClient = new DefaultHttpClient(cm, params);
		}

		commThread = new CommThread();
		commThread.start();
	}





	public synchronized int getStatus()
	{
		return status;
	}


	public synchronized final List<Cookie> getCookies()
	{
		try {
			return httpClient.getCookieStore().getCookies();
		}
		catch(NullPointerException e) {
			return null;
		}
	}



	/**
	 * get unformatted string from url in a thread, callback will be executed on the main thread.
	 * @param h
	 * @param url
	 * @param callback Callback to invoke on completion. May be null.
	 */
	public synchronized void getString(Handler h, String url, RESTfulInterface.OnGetStringCompleteListener callback) {

		url = sanitizeUrl(url);

		Log.d(TAG, "queueing GETSTRING " + url);

		CommThread.Task gs = commThread.new Task(CommThread.Task.MODE_GETSTRING);
		gs.in_url= url;
		gs.callbackHandler = h;
		gs.getStringCallback = callback;
		commThread.addTask(gs);
	}


	/**
	 * get raw binary data from url in a thread, callback will be executed on the main thread.
	 * @param h
	 * @param url
	 * @param callback Callback to invoke on completion. May be null.
	 */
	public synchronized void getRawData(Handler h, String url, RESTfulInterface.OnGetRawDataCompleteListener callback) {

		url = sanitizeUrl(url);

		Log.d(TAG, "queueing GETRAWDATA " + url);

		CommThread.Task grd = commThread.new Task(CommThread.Task.MODE_GETRAWDATA);
		grd.in_url= url;
		grd.callbackHandler = h;
		grd.getRawDataCallback = callback;
		commThread.addTask(grd);
	}



	/**
	 * get JSON from url in a thread, callback will be executed on the main thread.
	 * @param h
	 * @param url
	 * @param callback Callback to invoke on completion. May be null.
	 */
	public synchronized void getJSON(Handler h, String url, RESTfulInterface.OnGetJSONCompleteListener callback) {

		url = sanitizeUrl(url);

		Log.d(TAG, "queueing GETJSON " + url);

		CommThread.Task gj = commThread.new Task(CommThread.Task.MODE_GETJSON);
		gj.in_url= url;
		gj.callbackHandler = h;
		gj.getJSONCallback = callback;
		commThread.addTask(gj);
	}


	/**
	 * post JSON to url in a thread, callback will be executed on the main thread.
	 * @param h
	 * @param url
	 * @param data
	 * @param callback Callback to invoke on completion. May be null.
	 */
	public synchronized void postJSON(Handler h, String url, JSONObject data, RESTfulInterface.OnPostJSONCompleteListener callback) {

		url = sanitizeUrl(url);

		Log.d(TAG, "queueing POSTJSON " + url + " " + data.toString());

		CommThread.Task pj = commThread.new Task(CommThread.Task.MODE_POSTJSON);
		pj.in_url= url;
		pj.in_json = data;
		pj.callbackHandler = h;
		pj.postJSONCallback = callback;
		commThread.addTask(pj);
	}

	/**
	 * Post the given input streams as multipart form data to the given url.
	 * @param h
	 * @param url
	 * @param is
	 * @param mimeType MIME type of the given data.
	 * @param filename
	 * @param progressCallback Callback to invoke on progress. May be null.
	 * @param completeCallback Callback to invoke on completion. May be null.
	 */
	public synchronized void postMultipart(
			Handler h,
			String url,
			InputStream[] inStreams,
			String[] mimeTypes,
			String[] fileNames,
			RESTfulInterface.OnPostMultipartProgressListener progressCallback,
			RESTfulInterface.OnPostMultipartCompleteListener completeCallback) {

		url = sanitizeUrl(url);

		Log.d(TAG, "queueing POSTMULTIPART " + url + " " + inStreams.toString());

		CommThread.Task pm = commThread.new Task(CommThread.Task.MODE_POSTMULTIPART);
		pm.in_url= url;
		pm.in_arr_is = inStreams;
		pm.in_arr_mimetypes = mimeTypes;
		pm.in_arr_filenames = fileNames;
		pm.callbackHandler = h;
		pm.postMultipartProgressCallback = progressCallback;
		pm.postMultipartCompleteCallback = completeCallback;
		commThread.addTask(pm);
	}


	public synchronized void cancelAll() {

		Log.d(TAG, "Cancelling all operations");

		// empty the task queue
		commThread.mTaskQueue.clear();
		// disconnect callbacks of current task
		try {
			commThread.currentTask.postJSONCallback = null;
			commThread.currentTask.getJSONCallback = null;
			commThread.currentTask.getStringCallback = null;
		}
		catch(NullPointerException e) {
		}
		// and interrupt currently running op
		commThread.interrupt();

	}


	public static String sanitizeUrl(String url) {
		// eat up senseless blanks, would cause httpClient to hickup
		url = url.replaceAll(" ", "");
		// also, remove double shlashes except first pair
		return url.replaceAll("(?<!:)//", "/");
	}


	private class CommThread extends Thread {

		private static final String TAG = "RESTfulCommThread";
		private Task currentTask;

		public class Task {
			// constants
			public final static int MODE_GETSTRING = 0;
			public final static int MODE_GETJSON = 1;
			public final static int MODE_POSTJSON = 2;
			public final static int MODE_GETRAWDATA = 3;
			public final static int MODE_POSTMULTIPART = 4;
			public final static int QUIT = 666;


			// data, acted upon according to mode
			private final int mode;
			private String in_url;
			private String out_string;
			private byte[] out_ba;
			private JSONObject out_json;
			private JSONObject in_json; // for POST JSON
			private InputStream[] in_arr_is; // for POSTMULTIPART
			private String[] in_arr_filenames; // for POSTMULTIPART
			private String[] in_arr_mimetypes; // for POSTMULTIPART
			private Handler callbackHandler; // handler to post callbacks to
			private RESTfulInterface.OnGetStringCompleteListener getStringCallback;
			private RESTfulInterface.OnGetRawDataCompleteListener getRawDataCallback;
			private RESTfulInterface.OnGetJSONCompleteListener getJSONCallback;
			private RESTfulInterface.OnPostJSONCompleteListener postJSONCallback;
			private RESTfulInterface.OnPostMultipartProgressListener postMultipartProgressCallback;
			private RESTfulInterface.OnPostMultipartCompleteListener postMultipartCompleteCallback;



			public Task(int mode) {
				this.mode = mode;
			}
		}

		private ConcurrentLinkedQueue<Task> mTaskQueue = new ConcurrentLinkedQueue<Task>(); //BlockingQueue instead?



		public CommThread () {
		}


		public void run() {

			Log.d(TAG, "Saying Hellooo!");

			boolean quit = false;
			while(!quit) {
				currentTask = mTaskQueue.peek();

				// if queue empty, wait and re-run loop
				if(currentTask==null) {
					synchronized (mTaskQueue) {
						try {
							Log.d(TAG, "nothing to do, waiting...");
							mTaskQueue.wait();
						} catch (InterruptedException e) {
							Log.d(TAG, "woke up!!");
						}
					}
					// get queue head
					continue;
				}

				// there is something
				try {
					switch (currentTask.mode) {

					case Task.QUIT:
						Log.d(TAG, "got QUIT");
						quit = true;
						break;

					case Task.MODE_GETJSON:
						Log.d(TAG, "got GETJSON " + currentTask.in_url);
						printCookies();
						currentTask.out_json = getJSON(currentTask.in_url);
						// currentTask could be something other at time of runnable execution
						final RESTfulInterface.OnGetJSONCompleteListener gjc = currentTask.getJSONCallback;
						final JSONObject gjjo = currentTask.out_json;
						synchronized (RESTfulClient.this) { // do not interfere with cancelAll()
							currentTask.callbackHandler.post(new Runnable() {
								@Override
								public void run() {
									try{
										gjc.onComplete(gjjo);
									}
									catch(NullPointerException e) {
									}
								}
							});
						}
						break;

					case Task.MODE_GETSTRING:
						Log.d(TAG, "got GETSTRING " + currentTask.in_url);
						printCookies();
						currentTask.out_string = getString(currentTask.in_url);
						// currentTask could be something other at time of runnable execution
						final RESTfulInterface.OnGetStringCompleteListener gsc = currentTask.getStringCallback;
						final String gss = currentTask.out_string;
						synchronized (RESTfulClient.this) { // do not interfere with cancelAll()
							currentTask.callbackHandler.post(new Runnable() {
								@Override
								public void run() {
									try {
										gsc.onComplete(gss);
									}
									catch(NullPointerException e) {
									}
								}
							});
						}
						break;

					case Task.MODE_GETRAWDATA:
						Log.d(TAG, "got GETRAWDATA " + currentTask.in_url);
						printCookies();
						currentTask.out_ba = getRawData(currentTask.in_url);
						// currentTask could be something other at time of runnable execution
						final RESTfulInterface.OnGetRawDataCompleteListener grdc = currentTask.getRawDataCallback;
						final byte[] grdba = currentTask.out_ba;
						synchronized (RESTfulClient.this) { // do not interfere with cancelAll()
							currentTask.callbackHandler.post(new Runnable() {
								@Override
								public void run() {
									try{
										grdc.onComplete(grdba);
									}
									catch(NullPointerException e) {
									}
								}
							});
						}
						break;

					case Task.MODE_POSTJSON:
						Log.d(TAG, "got POSTJSON " + currentTask.in_url + " " + currentTask.in_json.toString());
						printCookies();
						currentTask.out_string = postJSON(currentTask.in_url, currentTask.in_json);
						synchronized (RESTfulClient.this) { // do not interfere with cancelAll()
							// currentTask could be something other at time of runnable execution
							final RESTfulInterface.OnPostJSONCompleteListener pjc = currentTask.postJSONCallback;
							final String pjs = currentTask.out_string;
							currentTask.callbackHandler.post(new Runnable() {
								@Override
								public void run() {
									try {
										pjc.onComplete(pjs);
									}
									catch(NullPointerException e) {
									}
								}
							});
						}
						break;

					case Task.MODE_POSTMULTIPART:
						Log.d(TAG, "got POSTMULTIPART " + currentTask.in_url + " count " + currentTask.in_arr_is.length);
						printCookies();
						// here the callback is called from within the worker method
						currentTask.out_string = postMultipart(
								currentTask.in_url,
								currentTask.in_arr_is,
								currentTask.in_arr_mimetypes,
								currentTask.in_arr_filenames,
								currentTask.postMultipartProgressCallback);
						synchronized (RESTfulClient.this) { // do not interfere with cancelAll()
							// currentTask could be something other at time of runnable execution
							final RESTfulInterface.OnPostMultipartCompleteListener pmc = currentTask.postMultipartCompleteCallback;
							if(pmc != null) // check for null
								currentTask.callbackHandler.post(new Runnable() {
									@Override
									public void run() {
										try {
											pmc.onComplete();
										}
										catch(NullPointerException e) {
										}
									}
								});
						}
						break;

					}
				} catch (Exception e) {
					//TODO tell caller
				}

				// done with this Task, remove from queue
				mTaskQueue.poll();

			}


			Log.d(TAG, "Saying Goodbye");
		}


		private void printCookies() {
			List<Cookie> cookies = getCookies();
			if (cookies.isEmpty())
				Log.d(TAG, "No Cookies");
			else
				for (Cookie c : cookies)
					Log.d(TAG, "Cookie: " + c.toString());
		}

		public final ConcurrentLinkedQueue<Task> getQueue() {
			return mTaskQueue;
		}

		public void addTask(Task t) {
			mTaskQueue.add(t);
			synchronized (mTaskQueue) {
				mTaskQueue.notify();
			}
		}




		private String getString(String url)
		{
			status = SC_OK;

			Log.i(TAG, "getString on " +url);

			HttpGet httpGet = new HttpGet(url);

			try {
				HttpResponse response = httpClient.execute(httpGet);

				if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
					// we assume that the response body contains the error message
					HttpEntity entity = response.getEntity();
					if(entity != null) {
						ByteArrayOutputStream ostream = new ByteArrayOutputStream();
						response.getEntity().writeTo(ostream);
						Log.e(TAG, "getString Error: " + ostream.toString());
					}
					else
						Log.e(TAG, "getString Error: Server did not give reason");

					return null;
				}

				Log.i(TAG, "getString Success for query " + url);

				HttpEntity entity = response.getEntity();
				if (entity != null) {

					InputStream instream = entity.getContent();
					BufferedReader reader = new BufferedReader(new InputStreamReader(instream));
					StringBuilder sb = new StringBuilder();

					String line = null;

					while ((line = reader.readLine()) != null)
						sb.append(line);

					String result=sb.toString();

					Log.i(TAG,result);

					instream.close();

					return result;
				}
			}
			catch (Exception e){
				Log.e(TAG, "getString error for query " + url, e);
				status = SC_ERR;
			}finally{
				httpGet.abort();
			}

			return null;
		}


		private byte[] getRawData(String url) {

			status = SC_OK;

			Log.i(TAG, "getRawData on " +url);


			HttpGet httpGet = new HttpGet(url);

			try {
				HttpResponse response = httpClient.execute(httpGet);

				if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
					// we assume that the response body contains the error message
					HttpEntity entity = response.getEntity();
					if(entity != null) {
						ByteArrayOutputStream ostream = new ByteArrayOutputStream();
						response.getEntity().writeTo(ostream);
						Log.e(TAG, "getRawData Error: " + ostream.toString());
					}
					else
						Log.e(TAG, "getRawData Error: Server did not give reason");

					return null;
				}

				HttpEntity entity = response.getEntity();
				if (entity != null) {

					InputStream in = entity.getContent();

					// Now that the InputStream is open, get the content length
					long contentLength = entity.getContentLength();

					long bytesRead = 0;

					// To avoid having to resize the array over and over and over as
					// bytes are written to the array, provide an accurate estimate of
					// the ultimate size of the byte array
					ByteArrayOutputStream out;
					if (contentLength != -1) {
						out = new ByteArrayOutputStream((int)contentLength);
					} else {
						out = new ByteArrayOutputStream(16384); // Pick some appropriate size
					}

					byte[] buf = new byte[512];
					while (true) {
						int len = in.read(buf);
						if (len == -1) {
							break;
						}
						out.write(buf, 0, len);
						bytesRead += len;
						if(isInterrupted()) // stop reading if thread got a pending interrupt
							break;
					}
					in.close();
					out.close();

					Log.i(TAG, "getRawData Success for query '" +url + "' read " + bytesRead + " of " + contentLength);

					return out.toByteArray();
				}
			}
			catch (Exception e){
				Log.e(TAG, "getRawData error for query " + url, e);
				status = SC_ERR;
			}finally{
				httpGet.abort();
			}

			return null;

		}


		private JSONObject getJSON(String url)
		{
			status = SC_OK;

			HttpGet httpGet = new HttpGet(url);

			try {
				HttpResponse response = httpClient.execute(httpGet);

				if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
					// we assume that the response body contains the error message
					HttpEntity entity = response.getEntity();
					if(entity != null) {
						ByteArrayOutputStream ostream = new ByteArrayOutputStream();
						response.getEntity().writeTo(ostream);
						Log.e(TAG, "getJSON Error: " + ostream.toString());
					}
					else
						Log.e(TAG, "getJSON Error: Server did not give reason");

					return null;
				}

				Log.i(TAG, "getJSON Success for query " + url);

				HttpEntity entity = response.getEntity();
				if (entity != null) {

					InputStream instream = entity.getContent();
					BufferedReader reader = new BufferedReader(new InputStreamReader(instream));
					StringBuilder sb = new StringBuilder();

					String line = null;

					while ((line = reader.readLine()) != null)
						sb.append(line + "\n");

					String result=sb.toString();

					Log.i(TAG,result);

					instream.close();

					return new JSONObject(result);
				}
			}
			catch (Exception e){
				Log.e(TAG, "getJSON error for query " + url, e);
				status = SC_ERR;
			}finally{
				httpGet.abort();
			}

			return null;
		}


		private String postJSON(String url, JSONObject data)
		{
			status = SC_OK;
			HttpPost httpPost = new HttpPost(url);

			StringEntity se = null;
			try {
				se = new StringEntity(data.toString());
			} catch (UnsupportedEncodingException e1) {
				Log.e(TAG, "postJSON error to " + url, e1);
				status = SC_ERR;
			}
			httpPost.setEntity(se);
			httpPost.setHeader("Accept", "application/json");
			httpPost.setHeader("Content-type", "application/json");

			try {
				HttpResponse response= httpClient.execute(httpPost);

				Log.i(TAG, "postJSON to " + url + " , code: " + response.getStatusLine().getStatusCode());

				// print response body in any case
				ByteArrayOutputStream ostream = new ByteArrayOutputStream();
				response.getEntity().writeTo(ostream);
				Log.i(TAG, "postJSON to:" + url + " , response: " + ostream.toString());

				if(response.getStatusLine().getStatusCode() == HttpStatus.SC_OK)
					return ostream.toString();
			}
			catch (Exception e) {
				status = SC_ERR;
				Log.e(TAG, "postJSON error to " + url, e);
			}
			finally {
				httpPost.abort();
			}

			return null;
		}


		private String postMultipart(String url, InputStream[] inStreams, String[] mimeTypes, String[] filenames, final RESTfulInterface.OnPostMultipartProgressListener progressCallback) {
			status = SC_OK;
			HttpPost httpPost = new HttpPost(url);


			CountingMultipartEntity multipartEntity = new CountingMultipartEntity(
					HttpMultipartMode.BROWSER_COMPATIBLE,
					new CountingMultipartEntity.ProgressListener() {
						@Override
						public void transferred(final long num) {

							synchronized (RESTfulClient.this) { // do not interfere with cancelAll()
								if(progressCallback != null) // check for null
									currentTask.callbackHandler.post(new Runnable() {
										@Override
										public void run() {
											progressCallback.onProgress(num);
										}
									});
							}
						}
					});


			for(int i=0; i<inStreams.length; ++i) {
				InputStream is = inStreams[i];
				// this assumes that all the arrays are of the same size, otherwise exception but WTF
				String mimeType = mimeTypes[i];
				String filename = filenames[i];
				multipartEntity.addPart("RESTfulClientData" + i, new InputStreamBody(is, mimeType, filename));
			}


			httpPost.setEntity(multipartEntity);


			try {
				HttpResponse response= httpClient.execute(httpPost);

				Log.i(TAG, "postMultipart to " + url + " , code: " + response.getStatusLine().getStatusCode());

				// print response body in any case
				ByteArrayOutputStream ostream = new ByteArrayOutputStream();
				response.getEntity().writeTo(ostream);
				Log.i(TAG, "postMultipart to:" + url + " , response: " + ostream.toString());

				if(response.getStatusLine().getStatusCode() == HttpStatus.SC_OK)
					return ostream.toString();
			}
			catch (Exception e) {
				status = SC_ERR;
				Log.e(TAG, "postMultipart error to " + url, e);
			}
			finally {
				httpPost.abort();
			}

			return null;
		}


	} // end workerthread

	private org.apache.http.conn.ssl.SSLSocketFactory createAdditionalCertsSSLSocketFactory(Context ctx, int res, String pass) {
	    try {
	        final KeyStore ks = KeyStore.getInstance("BKS");

	        // a bks file in res/raw
	        final InputStream in = ctx.getResources().openRawResource(res);
	        try {
	            // don't forget to put the password used above in strings.xml/mystore_password
	            ks.load(in, pass.toCharArray());
	        } finally {
	            in.close();
	        }

	        return new AdditionalKeyStoresSSLSocketFactory(ks);

	    } catch( Exception e ) {
	        throw new RuntimeException(e);
	    }
	}

}
