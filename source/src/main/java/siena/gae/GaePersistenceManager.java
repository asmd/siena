/*
 * Copyright 2009 Alberto Gimeno <gimenete at gmail.com>
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package siena.gae;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import siena.AbstractPersistenceManager;
import siena.ClassInfo;
import siena.Json;
import siena.Query;
import siena.QueryFilter;
import siena.QueryJoin;
import siena.QueryOrder;
import siena.SienaException;
import siena.SienaRestrictedApiException;
import siena.Util;
import siena.embed.Embedded;
import siena.embed.JsonSerializer;

import com.google.appengine.api.datastore.Blob;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.SortDirection;
import com.google.appengine.api.datastore.Text;

public class GaePersistenceManager extends AbstractPersistenceManager {

	private DatastoreService ds;

	public void beginTransaction(int isolationLevel) {
	}

	public void closeConnection() {
	}

	public void commitTransaction() {
	}

	public void delete(Object obj) {
		ds.delete(getKey(obj));
	}

	public void get(Object obj) {
		Key key = getKey(obj);
		try {
			Entity entity = ds.get(key);
			fillModel(obj, entity);
		} catch (Exception e) {
			throw new SienaException(e);
		}
	}

	public void init(Properties p) {
		ds = DatastoreServiceFactory.getDatastoreService();
	}

	public void insert(Object obj) {
		Class<?> clazz = obj.getClass();
		Entity entity = new Entity(ClassInfo.getClassInfo(clazz).tableName);
		fillEntity(obj, entity);
		ds.put(entity);
		setKey(ClassInfo.getIdField(clazz), obj, entity.getKey());
	}

	protected static void setKey(Field f, Object obj, Key key) {
		try {
			Object value = key.getId();
			if (f.getType() == String.class)
				value = value.toString();
			f.setAccessible(true);
			f.set(obj, value);
		} catch (Exception e) {
			throw new SienaException(e);
		}
	}

	public void rollbackTransaction() {
	}

	public void update(Object obj) {
		try {
			Entity entity = new Entity(getKey(obj));
			fillEntity(obj, entity);
			ds.put(entity);
		} catch (Exception e) {
			throw new SienaException(e);
		}
	}

	protected static Key getKey(Object obj) {
		try {
			Field f = ClassInfo.getIdField(obj.getClass());
			Object value = f.get(obj);
			if (value instanceof String)
				value = Long.parseLong((String) value);
			return KeyFactory.createKey(
					ClassInfo.getClassInfo(obj.getClass()).tableName,
					(Long) value);
		} catch (Exception e) {
			throw new SienaException(e);
		}
	}

	private static Object readField(Object object, Field field) {
		field.setAccessible(true);
		try {
			return field.get(object);
		} catch (Exception e) {
			throw new SienaException(e);
		}
	}

	protected static void fillEntity(Object obj, Entity entity) {
		Class<?> clazz = obj.getClass();

		for (Field field : ClassInfo.getClassInfo(clazz).updateFields) {
			String property = ClassInfo.getColumnNames(field)[0];
			Object value = readField(obj, field);
			Class<?> fieldClass = field.getType();
			if (ClassInfo.isModel(fieldClass)) {
				if (value == null) {
					entity.setProperty(property, null);
				} else {
					Key key = getKey(value);
					entity.setProperty(property, key);
				}
			} else {
				if (value != null) {
					if (field.getType() == Json.class) {
						value = value.toString();
					} else if (value instanceof String) {
						String s = (String) value;
						if (s.length() > 500)
							value = new Text(s);
					} else if (value instanceof byte[]) {
						byte[] arr = (byte[]) value;
						// GAE Blob doesn't accept more than 1MB
						if (arr.length < 1000000)
							value = new Blob(arr);
						else
							value = new Blob(Arrays.copyOf(arr, 1000000));
					}
					else if (field.getAnnotation(Embedded.class) != null) {
						value = JsonSerializer.serialize(value).toString();
						String s = (String) value;
						if (s.length() > 500)
							value = new Text(s);
					}
					// enum is after embedded because an enum can be embedded
					// don't know if anyone will use it but it will work :)
					else if (Enum.class.isAssignableFrom(field.getType())) {
						value = value.toString();
					} 
				}
				Unindexed ui = field.getAnnotation(Unindexed.class);
				if (ui == null) {
					entity.setProperty(property, value);
				} else {
					entity.setUnindexedProperty(property, value);
				}
			}
		}
	}

	protected static void fillModel(Object obj, Entity entity) {
		Class<?> clazz = obj.getClass();

		for (Field field : ClassInfo.getClassInfo(clazz).updateFields) {
			field.setAccessible(true);
			String property = ClassInfo.getColumnNames(field)[0];
			try {
				Class<?> fieldClass = field.getType();
				if (ClassInfo.isModel(fieldClass)) {
					Key key = (Key) entity.getProperty(property);
					if (key != null) {
						Object value = fieldClass.newInstance();
						Field id = ClassInfo.getIdField(fieldClass);
						setKey(id, value, key);
						field.set(obj, value);
					}
				} else {
					setFromObject(obj, field, entity.getProperty(property));
				}
			} catch (Exception e) {
				throw new SienaException(e);
			}
		}
	}

	protected static void setFromObject(Object object, Field f, Object value)
			throws IllegalArgumentException, IllegalAccessException {
		// if(value instanceof Text)
		// value = ((Text) value).getValue();
		// else if(value instanceof Blob) {
		// value = ((Blob) value).getBytes();
		// }
		Util.setFromObject(object, f, value);
	}

	protected DatastoreService getDatastoreService() {
		return ds;
	}

	protected static <T> List<T> mapEntities(List<Entity> entities,
			Class<T> clazz) {
		Field id = ClassInfo.getIdField(clazz);
		List<T> list = new ArrayList<T>(entities.size());
		for (Entity entity : entities) {
			T obj;
			try {
				obj = clazz.newInstance();
				fillModel(obj, entity);
				list.add(obj);
				setKey(id, obj, entity.getKey());
			} catch (SienaException e) {
				throw e;
			} catch (Exception e) {
				throw new SienaException(e);
			}
		}
		return list;
	}

	protected static <T> List<T> mapEntitiesKeysOnly(List<Entity> entities,
			Class<T> clazz) {
		Field id = ClassInfo.getIdField(clazz);
		List<T> list = new ArrayList<T>(entities.size());
		for (Entity entity : entities) {
			T obj;
			try {
				obj = clazz.newInstance();
				list.add(obj);
				setKey(id, obj, entity.getKey());
			} catch (SienaException e) {
				throw e;
			} catch (Exception e) {
				throw new SienaException(e);
			}
		}
		return list;
	}
	
	protected static <T> com.google.appengine.api.datastore.Query 
			addFiltersOrders(
					Query<T> query, 
					com.google.appengine.api.datastore.Query q) 
	{
		List<QueryFilter> filters = query.getFilters();
		for (QueryFilter filter : filters) {
			Field f = filter.field;
			String propertyName = ClassInfo.getColumnNames(f)[0];
			Object value = filter.value;
			FilterOperator op = operators.get(filter.operator);

			if (value != null && ClassInfo.isModel(value.getClass())) {
				Key key = getKey(value);
				q.addFilter(propertyName, op, key);
			} else {
				if (ClassInfo.isId(f)) {
					if (value instanceof String) {
						value = Long.parseLong(value.toString());
					}
					Key key = KeyFactory.createKey(q.getKind(), (Long) value);
					q.addFilter(Entity.KEY_RESERVED_PROPERTY, op, key);
				} else {
					q.addFilter(propertyName, op, value);
				}
			}
		}

		List<QueryOrder> orders = query.getOrders();
		for (QueryOrder order : orders) {
			Field f = order.field;
			if (ClassInfo.isId(f)) {
				q.addSort(Entity.KEY_RESERVED_PROPERTY,
						order.ascending ? SortDirection.ASCENDING
								: SortDirection.DESCENDING);
			} else {
				q.addSort(ClassInfo.getColumnNames(f)[0],
						order.ascending ? SortDirection.ASCENDING
								: SortDirection.DESCENDING);
			}
		}

		return q;
	}

	private <T> PreparedQuery prepare(Query<T> query) {
		Class<?> clazz = query.getQueriedClass();
		com.google.appengine.api.datastore.Query q = new com.google.appengine.api.datastore.Query(
				ClassInfo.getClassInfo(clazz).tableName);

		return ds.prepare(addFiltersOrders(query, q));
	}

	private <T> PreparedQuery prepareKeysOnly(Query<T> query) {
		Class<?> clazz = query.getQueriedClass();
		com.google.appengine.api.datastore.Query q = new com.google.appengine.api.datastore.Query(
				ClassInfo.getClassInfo(clazz).tableName);

		return ds.prepare(addFiltersOrders(query, q).setKeysOnly());
	}

	
	protected <T> List<T> mapJoins(Query<T> query, List<T> models) {
		try {
			List<QueryJoin> joins = query.getJoins();
			
			// join queries
			Map<Field, ArrayList<Key>> fieldMap = new HashMap<Field, ArrayList<Key>>();
			for (QueryJoin join : joins) {
				Field field = join.field;
				if (!ClassInfo.isModel(field.getType())){
					throw new SienaException("Join not possible: Field "+field.getName()+" is not a relation field");
				}
				fieldMap.put(field, new ArrayList<Key>());
			}
			
			// join annotations
			for(Field field: 
				ClassInfo.getClassInfo(query.getQueriedClass()).joinFields)
			{
				fieldMap.put(field, new ArrayList<Key>());
			}
			
			// creates the list of joined entity keys to extract 
			for (final T model : models) {
				for(Field field: fieldMap.keySet()){
					Key key = getKey(field.get(model));
					List<Key> keys = fieldMap.get(field);
					if(!keys.contains(key))
						keys.add(key);
				}
			}
			
			Map<Field, Map<Key, Entity>> entityMap = 
				new HashMap<Field, Map<Key, Entity>>();

			// retrieves all joined entities per field
			for(Field field: fieldMap.keySet()){
				Map<Key, Entity> entities = ds.get(fieldMap.get(field));
				entityMap.put(field, entities);
			}
			
			// associates linked models to their models
			// linkedModels is just a map to contain entities already mapped
			Map<Key, Object> linkedModels = new HashMap<Key, Object>();
			Object linkedObj;
			Entity entity; 
			
			for (final T model : models) {
				for(Field field: fieldMap.keySet()){
					Object objVal = field.get(model);
					Key key = getKey(objVal);
					linkedObj = linkedModels.get(key);
					if(linkedObj==null){
						entity = entityMap.get(field).get(key);
						linkedObj = objVal;
						fillModel(linkedObj, entity);
						linkedModels.put(key, linkedObj);
					}
				
					field.set(model, linkedObj);				
				}
			}
			return models;
		} catch(Exception ex){
			throw new SienaException(ex);
		}		
	}
	
	@SuppressWarnings("unchecked")
	protected <T> List<T> map(Query<T> query, int offset,
			List<Entity> entities) {
		Class<?> clazz = query.getQueriedClass();
		List<T> result = (List<T>) mapEntities(entities, clazz);
		query.setNextOffset(offset + result.size());
		
		// join management
		if(!query.getJoins().isEmpty() || ClassInfo.getClassInfo(clazz).joinFields.size() != 0)
			return mapJoins(query, result);
		
		return result;
	}

	@SuppressWarnings("unchecked")
	protected <T> List<T> mapKeysOnly(Query<T> query, int offset,
			List<Entity> entities) {
		Class<?> clazz = query.getQueriedClass();
		List<T> result = (List<T>) mapEntitiesKeysOnly(entities, clazz);
		query.setNextOffset(offset + result.size());
		return result;
	}

	@Override
	public <T> List<T> fetch(Query<T> query) {
		return map(query, 0,
				prepare(query).asList(FetchOptions.Builder.withDefaults()));
	}

	@Override
	public <T> List<T> fetch(Query<T> query, int limit) {
		return map(query, 0,
				prepare(query).asList(FetchOptions.Builder.withLimit(limit)));
	}

	@Override
	public <T> List<T> fetch(Query<T> query, int limit, Object offset) {
		return map(
				query,
				(Integer) offset,
				prepare(query).asList(
						FetchOptions.Builder.withLimit(limit).offset(
								(Integer) offset)));
	}

	@Override
	public <T> int count(Query<T> query) {
		return prepare(query)
				.countEntities(FetchOptions.Builder.withDefaults());
	}

	@Override
	public <T> int delete(Query<T> query) {
		final ArrayList<Key> keys = new ArrayList<Key>();

		for (final Entity entity : prepareKeysOnly(query).asIterable(
				FetchOptions.Builder.withDefaults())) {
			keys.add(entity.getKey());
		}

		ds.delete(keys);

		return keys.size();
	}

	@Override
	public <T> List<T> fetchKeys(Query<T> query) {
		return mapKeysOnly(
				query,
				0,
				prepareKeysOnly(query).asList(
						FetchOptions.Builder.withDefaults()));
	}

	@Override
	public <T> List<T> fetchKeys(Query<T> query, int limit) {
		return mapKeysOnly(
				query,
				0,
				prepareKeysOnly(query).asList(
						FetchOptions.Builder.withLimit(limit)));
	}

	@Override
	public <T> List<T> fetchKeys(Query<T> query, int limit, Object offset) {
		return mapKeysOnly(
				query,
				0,
				prepareKeysOnly(query).asList(
						FetchOptions.Builder.withLimit(limit).offset(
								(Integer) offset)));
	}

	@Override
	public <T> Iterable<T> iter(Query<T> query) {
		return new SienaGaeIterable<T>(prepare(query).asIterable(
				FetchOptions.Builder.withDefaults()), query.getQueriedClass());
	}

	@Override
	public <T> Iterable<T> iter(Query<T> query, int limit) {
		return new SienaGaeIterable<T>(prepare(query).asIterable(
				FetchOptions.Builder.withLimit(limit)), query.getQueriedClass());
	}

	@Override
	public <T> Iterable<T> iter(Query<T> query, int limit, Object offset) {
		return new SienaGaeIterable<T>(
				prepare(query).asIterable(
						FetchOptions.Builder.withLimit(limit).offset(
								(Integer) offset)), query.getQueriedClass());
	}

	/*
	@Override
	public <T> List<T> join(Query<T> query, String fieldName, String... sortFields) {
		if (sortFields.length > 0) {
			throw new SienaRestrictedApiException("GAE", "join", "sortFields are not authorized in GAE join");
		}

		// retrieves models and joined models with keys only 
		List<T> models = (List<T>)query.fetch();
		try {
			// the class of the model
			Class<T> clazz = query.getQueriedClass();
			// the joined field
			Field field = clazz.getField(fieldName);
			Class<?> fieldClass = field.getType();
			Field fieldId = ClassInfo.getIdField(fieldClass);			
			if (!ClassInfo.isModel(fieldClass)){
				throw new SienaException("Join not possible: Field "+fieldName+" is not a relation field");
			}
			
			// creates the list of joined entity keys to extract 
			final ArrayList<Key> keys = new ArrayList<Key>();
			
			for (final T model : models) {
				Object fieldValue = field.get(model);
				Key key = getKey(fieldValue);
				if(!keys.contains(key))
					keys.add(key);
			}
			
			// retrieves all joined entities
			Map<Key, Entity> entities = ds.get(keys);
			
			// builds a map containing all models built from entities
			Map<Object, Object> linkedModels = new HashMap<Object, Object>();
			Object obj;
			Entity entity; 
			for(Key key : entities.keySet()){
				if(!linkedModels.containsKey(key)){
					obj = fieldClass.newInstance();
					entity = entities.get(key);
					fillModel(obj, entity);
					setKey(fieldId, obj, key);
					linkedModels.put(fieldId.get(obj), obj);
				}
			}
			
			// associates linked models to their models
			for (final T model : models) {
				Object objVal = field.get(model);
				Object objId = fieldId.get(objVal);
				obj = linkedModels.get(objId);
				
				field.set(model, obj);				
			}
			
			return models;
		} catch(NoSuchFieldException ex){
			throw new SienaException(ex);
		} catch(IllegalAccessException ex){
			throw new SienaException(ex);
		} catch(InstantiationException ex){
			throw new SienaException(ex);
		}
	}*/
	
	private static final Map<String, FilterOperator> operators = new HashMap<String, FilterOperator>() {
		private static final long serialVersionUID = 1L;
		{
			put("=", FilterOperator.EQUAL);
			put("!=", FilterOperator.NOT_EQUAL);
			put("<", FilterOperator.LESS_THAN);
			put(">", FilterOperator.GREATER_THAN);
			put("<=", FilterOperator.LESS_THAN_OR_EQUAL);
			put(">=", FilterOperator.GREATER_THAN_OR_EQUAL);
			put(" IN", FilterOperator.IN);
		}
	};

	private static String[] supportedOperators;

	static {
		supportedOperators = operators.keySet().toArray(new String[0]);
	}

	@Override
	public String[] supportedOperators() {
		return supportedOperators;
	}

	/**
	 * @author mandubian
	 * 
	 *         A Siena Iterable<Model> encapsulating a GAE Iterable<Entity> with
	 *         its Iterator<Model>...
	 */
	public static class SienaGaeIterable<Model> implements Iterable<Model> {
		Iterable<Entity> gaeIterable;
		Class<Model> clazz;

		SienaGaeIterable(Iterable<Entity> gaeIterable, Class<Model> clazz) {
			this.gaeIterable = gaeIterable;
			this.clazz = clazz;
		}

		@Override
		public Iterator<Model> iterator() {
			return new SienaGaeIterator<Model>(gaeIterable.iterator(), clazz);
		}

		public class SienaGaeIterator<T> implements Iterator<T> {
			Iterator<Entity> gaeIterator;
			Class<T> clazz;
			Field id;

			SienaGaeIterator(Iterator<Entity> gaeIterator, Class<T> clazz) {
				this.gaeIterator = gaeIterator;
				this.clazz = clazz;
				this.id = ClassInfo.getIdField(clazz);
			}

			@Override
			public boolean hasNext() {
				return gaeIterator.hasNext();
			}

			@Override
			public T next() {
				T obj;

				try {
					obj = clazz.newInstance();
					Entity entity = gaeIterator.next();
					fillModel(obj, entity);
					setKey(id, obj, entity.getKey());

					return obj;
				} catch (SienaException e) {
					throw e;
				} catch (Exception e) {
					throw new SienaException(e);
				}
			}

			@Override
			public void remove() {
				gaeIterator.remove();
			}

		}

	}

}