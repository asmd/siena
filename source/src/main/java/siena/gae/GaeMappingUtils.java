package siena.gae;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import siena.ClassInfo;
import siena.Id;
import siena.Json;
import siena.SienaException;
import siena.SienaRestrictedApiException;
import siena.Util;
import siena.embed.Embedded;
import siena.embed.JsonSerializer;

import com.google.appengine.api.datastore.Blob;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Text;

public class GaeMappingUtils {
	


	
	public static Entity createEntityInstance(Field idField, ClassInfo info, Object obj){
		Entity entity = null;
		Id id = idField.getAnnotation(Id.class);
		if(id != null){
			switch(id.value()) {
			case NONE:
				Object idVal = null;
				try {
					idVal = readField(obj, idField);
				}catch(Exception ex){
					throw new SienaException("Id Field " + idField.getName() + " access error", ex);
				}
				if(idVal == null)
					throw new SienaException("Id Field " + idField.getName() + " value null");
				String keyVal = Util.toString(idField, idVal);				
				entity = new Entity(info.tableName, keyVal);
				break;
			case AUTO_INCREMENT:
				entity = new Entity(info.tableName);
				break;
			case UUID:
				entity = new Entity(info.tableName, UUID.randomUUID().toString());
				break;
			default:
				throw new SienaRestrictedApiException("DB", "createEntityInstance", "Id Generator "+id.value()+ " not supported");
			}
		}
		else throw new SienaException("Field " + idField.getName() + " is not an @Id field");
		
		return entity;
	}
	
	public static void setKey(Field idField, Object obj, Key key) {
		Id id = idField.getAnnotation(Id.class);
		if(id != null){
			switch(id.value()) {
			case NONE:
				idField.setAccessible(true);
				Object val = null;
				if (idField.getType().isAssignableFrom(String.class))
					val = key.getName();
				else if (idField.getType().isAssignableFrom(Long.class))
					val = Long.parseLong((String) key.getName());
				else
					throw new SienaRestrictedApiException("DB", "setKey", "Id Type "+idField.getType()+ " not supported");
					
				try {
					idField.set(obj, val);
				}catch(Exception ex){
					throw new SienaException("Field " + idField.getName() + " access error", ex);
				}
				break;
			case AUTO_INCREMENT:
				// Long value means key.getId()
				try {
					idField.setAccessible(true);
					idField.set(obj, key.getId());
				}catch(Exception ex){
					throw new SienaException("Field " + idField.getName() + " access error", ex);
				}
				break;
			case UUID:
				try {
					idField.setAccessible(true);
					idField.set(obj, key.getName());					
				}catch(Exception ex){
					throw new SienaException("Field " + idField.getName() + " access error", ex);
				}
				break;
			default:
				throw new SienaException("Id Generator "+id.value()+ " not supported");
			}
		}
		else throw new SienaException("Field " + idField.getName() + " is not an @Id field");
	}
	protected static Key getKey(Object obj) {
		Class<?> clazz = obj.getClass();
		ClassInfo info = ClassInfo.getClassInfo(clazz);
		
		try {
			Field idField = info.getIdField();
			Object value = readField(obj, idField);
			
			if(idField.isAnnotationPresent(Id.class)){
				Id id = idField.getAnnotation(Id.class);
				switch(id.value()) {
				case NONE:
					// long or string goes toString
					return KeyFactory.createKey(
							ClassInfo.getClassInfo(clazz).tableName,
							value.toString());
				case AUTO_INCREMENT:
					if (value instanceof String)
						value = Long.parseLong((String) value);
					return KeyFactory.createKey(
							ClassInfo.getClassInfo(clazz).tableName,
							(Long)value);
				case UUID:
					return KeyFactory.createKey(
							ClassInfo.getClassInfo(clazz).tableName,
							value.toString());
				default:
					throw new SienaException("Id Generator "+id.value()+ " not supported");
				}
			}
			else throw new SienaException("Field " + idField.getName() + " is not an @Id field");
		} catch (Exception e) {
			throw new SienaException(e);
		}
	}
	
	protected static Key makeKey(Class<?> clazz, Object value) {
		ClassInfo info = ClassInfo.getClassInfo(clazz);
		
		try {
			Field idField = info.getIdField();
			
			if(idField.isAnnotationPresent(Id.class)){
				Id id = idField.getAnnotation(Id.class);
				switch(id.value()) {
				case NONE:
					// long or string goes toString
					return KeyFactory.createKey(
							ClassInfo.getClassInfo(clazz).tableName,
							value.toString());
				case AUTO_INCREMENT:
					if (value instanceof String)
						value = Long.parseLong((String) value);
					return KeyFactory.createKey(
							ClassInfo.getClassInfo(clazz).tableName,
							(Long)value);
				case UUID:
					return KeyFactory.createKey(
							ClassInfo.getClassInfo(clazz).tableName,
							value.toString());
				default:
					throw new SienaException("Id Generator "+id.value()+ " not supported");
				}
			}
			else throw new SienaException("Field " + idField.getName() + " is not an @Id field");
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
		} finally {
			field.setAccessible(false);
		}
	}

	public static void fillEntity(Object obj, Entity entity) {
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

	public static void fillModel(Object obj, Entity entity) {
		Class<?> clazz = obj.getClass();

		for (Field field : ClassInfo.getClassInfo(clazz).updateFields) {
			field.setAccessible(true);
			String property = ClassInfo.getColumnNames(field)[0];
			try {
				Class<?> fieldClass = field.getType();
				if (ClassInfo.isModel(fieldClass)) {
					Key key = (Key) entity.getProperty(property);
					if (key != null) {
						Object value = Util.createModelInstance(fieldClass);
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

	public static void setFromObject(Object object, Field f, Object value)
			throws IllegalArgumentException, IllegalAccessException {
		if(value instanceof Text)
			value = ((Text) value).getValue();
		else if(value instanceof Blob && f.getType() == byte[].class) {
			value = ((Blob) value).getBytes();
		}
		Util.setFromObject(object, f, value);
	}
	
	public static <T> T mapEntityKeysOnly(Entity entity, Class<T> clazz) {
		Field id = ClassInfo.getIdField(clazz);
		T obj;
		try {
			obj = Util.createModelInstance(clazz);
			setKey(id, obj, entity.getKey());
		} catch (SienaException e) {
			throw e;
		} catch (Exception e) {
			throw new SienaException(e);
		}
	
		return obj;
	}
	
	public static <T> List<T> mapEntitiesKeysOnly(List<Entity> entities,
			Class<T> clazz) {
		Field id = ClassInfo.getIdField(clazz);
		List<T> list = new ArrayList<T>(entities.size());
		for (Entity entity : entities) {
			T obj;
			try {
				obj = Util.createModelInstance(clazz);
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

	
	public static <T> List<T> mapEntitiesKeysOnly(Iterable<Entity> entities,
			Class<T> clazz) {
		Field id = ClassInfo.getIdField(clazz);
		List<T> list = new ArrayList<T>();
		for (Entity entity : entities) {
			T obj;
			try {
				obj = Util.createModelInstance(clazz);
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
	
	public static <T> T mapEntity(Entity entity, Class<T> clazz) {
		Field id = ClassInfo.getIdField(clazz);
		T obj;
		// try to find a constructor
		try {	
			obj = Util.createModelInstance(clazz);
			fillModel(obj, entity);
			setKey(id, obj, entity.getKey());
		} catch (SienaException e) {
			throw e;
		} catch (Exception e) {
			throw new SienaException(e);
		}
	
		return obj;
	}
	
	public static <T> List<T> mapEntities(List<Entity> entities,
			Class<T> clazz) {
		Field id = ClassInfo.getIdField(clazz);
		List<T> list = new ArrayList<T>(entities.size());
		for (Entity entity : entities) {
			T obj;
			try {
				obj = Util.createModelInstance(clazz);
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

	
	public static <T> List<T> mapEntities(Iterable<Entity> entities,
			Class<T> clazz) {
		Field id = ClassInfo.getIdField(clazz);
		List<T> list = new ArrayList<T>();
		for (Entity entity : entities) {
			T obj;
			try {
				obj = Util.createModelInstance(clazz);
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
}