package org.qrone.jsondatastore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.channels.Channels;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.qrone.util.Hex;
import org.qrone.util.Stream;
import org.qrone.util.Token;

import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.QueryResultIterator;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.QueryResultIterable;
import com.google.appengine.api.files.AppEngineFile;
import com.google.appengine.api.files.FileReadChannel;
import com.google.appengine.api.files.FileService;
import com.google.appengine.api.files.FileServiceFactory;
import com.google.appengine.api.files.FileWriteChannel;

@SuppressWarnings("serial")
public class JSONDatastoreServlet extends HttpServlet {
	public static DatastoreService store
		= DatastoreServiceFactory.getDatastoreService();
	public static FileService fileService = FileServiceFactory.getFileService();
	private static BlobstoreService blobService = BlobstoreServiceFactory.getBlobstoreService();
	
	public static Token token;
	
	public Token getSecret(){
		Key key = KeyFactory.createKey("qrone.setting", "secret");
		Token t;
		try {
			Entity e = store.get(key);
			String k = (String)e.getProperty("key");
			t = Token.parse(k);
		} catch (EntityNotFoundException e) {
			t = Token.generate("MASTER", null);
			Entity e2 = new Entity(key);
			e2.setProperty("key", t.toString());
			store.put(e2);
		}
		return t;
	}

	private Object parse(String str){
		if(str.startsWith("json:")){
			try {
				return new JSONObject(str.substring("json:".length()));
			} catch (JSONException e) {
				return null;
			}
		}else if(str.equals("true")){
			return Boolean.TRUE;
		}else if(str.equals("false")){
			return Boolean.FALSE;
		}
		
		try{
			return Integer.parseInt(str);
		}catch(NumberFormatException e){
			try{
				return Double.parseDouble(str);
			}catch(NumberFormatException e2){
				
			}
		}
		
		return str;
	}
	
	
	private Object decode(Object data){
		if(data instanceof String){
			String str = (String)data;
			if(str.startsWith("json:")){
				try {
					return new JSONObject(str.substring("json:".length()));
				} catch (JSONException e) {
					return null;
				}
			}
			return str;
		}
		return data;
	}
	
	private Object encode(Object data){
		if(data instanceof JSONObject){
			return "json:" + ((JSONObject)data).toString();
		}else if(data instanceof JSONArray){
			return "json:" + ((JSONArray)data).toString();
		}
		return data;
	}

	private JSONObject fromEntityToJSON(Entity e){
		if(e == null) return null;
		try {
			JSONObject json = new JSONObject();
			Map<String, Object> map = e.getProperties();
			
			json.put("id", KeyFactory.keyToString(e.getKey()));
			
			for (Iterator i = map.entrySet().iterator(); i.hasNext();) {
				Map.Entry entry = (Map.Entry) i.next();
				String key = (String)entry.getKey();
				if(!key.equals("id")){
					Object o = decode(entry.getValue());
					if(o != null)
						json.put((String)entry.getKey(), o);
				}
			}
			return json;
		} catch (JSONException ex) {
			return null;
		}
	}
	
	private Entity fromJSONtoEntity(String kind, String name, JSONObject json){
		if(json == null) return null;
		try {
			Entity e;
			if(json.has("id")){
				e = new Entity(kind, json.getString("id"));
			}else{
				e = new Entity(kind);
			}

			e.setProperty(".user", name);
			e.setProperty(".timestamp", System.currentTimeMillis() / 1000);
			
			String[] keys = JSONObject.getNames(json);
			for (int i = 0; i < keys.length; i++) {
				String key = keys[i];
				if(key.indexOf('.') < 0 && !key.equals("id")){
					Object o = encode(json.get(key));
					if(o != null)
						e.setProperty(keys[i], o);
				}
			}
			
			return e;
		} catch (JSONException e) {
			return null;
		}
	}
	

	private Entity fromParamsToEntity(String kind, String name, Map<String, String> params){
		if(params == null) return null;
		
		Entity e;
		if(params.containsKey("id")){
			e = new Entity(kind, params.get("id"));
		}else{
			e = new Entity(kind);
		}
		
		e.setProperty(".user", name);
		e.setProperty(".timestamp", System.currentTimeMillis() / 1000);
		
		for (Iterator iter = params.entrySet().iterator(); iter.hasNext();) {
			Map.Entry entry = (Map.Entry) iter.next();
			String key = (String)entry.getKey();
			if(key.indexOf('.') < 0 && !key.equals("id")){
				Object o = parse(((String[])entry.getValue())[0]);
				if(o != null)
					e.setProperty(key, o);
			}
		}
		
		return e;
	}
	
	private PreparedQuery getQueryFromParams(String kind, Map params){
		Query q = new Query(kind);
		for (Iterator iter = params.entrySet().iterator(); iter.hasNext();) {
			Map.Entry e = (Map.Entry)iter.next();
			String key = (String)e.getKey();
			if(key.indexOf('.') < 0){
				q.addFilter(key, FilterOperator.EQUAL, (String)e.getValue());
			}
		}
		String order = params.get(".order").toString();
		if(order != null){
			if(order.startsWith("-")){
				q.addSort(order.substring(1), Query.SortDirection.DESCENDING);
			}else{
				q.addSort(order, Query.SortDirection.ASCENDING);
			}
		}
		return store.prepare(q);
	}
	
	private void result(HttpServletResponse resp, Object result, int length) throws IOException{
		try {
			JSONObject successSet = new JSONObject();
			successSet.put("status", "success");
			if(result == null){
				successSet.put("status", "error");
				successSet.put("message", "Item not found.");	
			}else if(result instanceof Entity){
				Object o = fromEntityToJSON((Entity)result);
				if(o != null)
					successSet.put("item", o);
				else{
					successSet.put("status", "error");
					successSet.put("message", "Entity error.");					
				}
			}else if(result instanceof QueryResultIterator){
				JSONArray json = new JSONArray();
				QueryResultIterator iter = (QueryResultIterator)result;
				int c = 0;
				while(iter.hasNext() && ( length < 0 || c < length) ){
					c++;
					JSONObject j = fromEntityToJSON((Entity)iter.next());
					if(j != null)
						json.put(j);
				}
				successSet.put("list", json);
			}
			
			Writer w = resp.getWriter();
			w.append(successSet.toString());
			w.flush();
			w.close();
		} catch (JSONException e) {
			resp.setStatus(500);
		}
	}
	
	public void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {

		String path = req.getRequestURI();
		if(path.equals("/admin/register")){

			try {
				JSONObject successSet = new JSONObject();
				successSet.put("status", "success");
				successSet.put("mastertoken", new Token(getSecret(),"MASTER",null).toString());

				Writer w = resp.getWriter();
				w.append(successSet.toString());
				w.flush();
				w.close();
			} catch (JSONException e) {
				resp.setStatus(500);
			}
			return;
		}else if(path.equals("/register")){
			Token auth = Token.parse(req.getParameter(".sign"));
			if(auth == null || !auth.validate("MASTER", getSecret())){
				resp.setStatus(403);
				return;
			}
			
			String name = req.getParameter("name");
			
			try {
				JSONObject successSet = new JSONObject();
				successSet.put("status", "success");
				successSet.put("accesstoken", new Token(getSecret(),"WSSID",name).toString());
				
				Writer w = resp.getWriter();
				w.append(successSet.toString());
				w.flush();
				w.close();
			} catch (JSONException e) {
				resp.setStatus(500);
			}
			return;
		}else if(path.startsWith("/down/")){
			String key = path.substring("/down/".length());
			blobService.serve(new BlobKey(key), resp);
			return;
		}else if(path.startsWith("/delete/")){
			String key = path.substring("/delete/".length());
			blobService.delete(new BlobKey(key));

			try {
				JSONObject successSet = new JSONObject();
				successSet.put("status", "success");
				successSet.put("key", key);
				
				Writer w = resp.getWriter();
				w.append(successSet.toString());
				w.flush();
				w.close();
			} catch (JSONException e) {
				resp.setStatus(500);
			}
			
			return;
		}else if(path.equals("/status")){
			try {
				JSONObject successSet = new JSONObject();
				successSet.put("status", "OK");
				
				Writer w = resp.getWriter();
				w.append(successSet.toString());
				w.flush();
				w.close();
			} catch (JSONException e) {
				resp.setStatus(500);
			}
			return;
		}
		
		
		Token auth = Token.parse(req.getParameter(".sign"));
		if(auth == null || !auth.validate("WSSID", getSecret())){
			resp.setStatus(403);
			return;
		}
		
		int idx = path.indexOf('/', 1);
		String kind = path.substring(0, idx);
		path = path.substring(idx);
		
		if(path.equals("/get")){
			Key key = KeyFactory.stringToKey(req.getParameter("id"));
			try {
				result(resp, store.get(key), 0);
			} catch (EntityNotFoundException e) {
				result(resp, null, 0);
			}
			return;
			
		}else if(path.equals("/list")){
			PreparedQuery pq = getQueryFromParams(kind, req.getParameterMap());
			QueryResultIterator<Entity> iter = pq.asQueryResultIterator();
			String l = req.getParameter(".length");
			int c = -1;
			if(l != null){
				c = Integer.parseInt(l);
			}
			result(resp, iter, c);
			return;
		}else if(path.equals("/add")){
			Entity e = fromParamsToEntity(kind, auth.getId(), req.getParameterMap());
			if(e != null){
				store.put(e);
			}
			result(resp, e, 0);
			return;
		}else if(path.equals("/remove")){
			Key key = KeyFactory.stringToKey(req.getParameter("id"));
			try {
				Entity e = store.get(key);
				store.delete(key);
				result(resp, e, 0);
			} catch (EntityNotFoundException e) {
				result(resp, null, 0);
			}
			return;
		}

		resp.setStatus(404);
		
	}

	public void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws IOException {

		Token auth = Token.parse(req.getParameter(".sign"));
		if(auth == null || !auth.validate("WSSID", getSecret())){
			resp.setStatus(403);
			return;
		}
		
		String path = req.getRequestURI();
		Map params = req.getParameterMap();

		int idx = path.indexOf('/', 1);
		String kind = path.substring(0, idx);
		path = path.substring(idx);
		
		if(path.equals("/add")){
			try {
				String content = new String(Stream.read(req.getInputStream()));
				JSONArray array = new JSONArray(content);
				for (int i = 0; i < array.length(); i++) {
					Entity e = fromJSONtoEntity(kind, auth.getId(), array.getJSONObject(i));
					if(e != null){
						store.put(e);
					}
				}
				result(resp, array, 0);
				return;
			} catch (JSONException e) {
				
			}
		}else if(path.equals("/up")){
			String contentType = req.getParameter("contenttype");
			String filename = req.getParameter("filename");

			if(contentType == null){
				contentType = "text/plain";
			}
			
			if(filename == null){
				filename = "untitled";
			}
			
			AppEngineFile file = fileService.createNewBlobFile(contentType,filename);
			boolean lock = false;
			FileWriteChannel writeChannel = fileService.openWriteChannel(file, lock);
			
			OutputStream out = Channels.newOutputStream(writeChannel);
			Stream.copy(req.getInputStream(), out);
			writeChannel.closeFinally();
			
			BlobKey key = fileService.getBlobKey(file);
			
			try {
				JSONObject successSet = new JSONObject();
				successSet.put("status", "success");
				successSet.put("contentType", contentType);
				successSet.put("filename", filename);
				successSet.put("key", key.getKeyString());

				Writer w = resp.getWriter();
				w.append(successSet.toString());
				w.flush();
				w.close();
				return;
			} catch (JSONException e) {
				
			}
		}
		resp.setStatus(404);
	}
}
