/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson;

import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is the main class for using Gson. Gson is typically used by first constructing a
 * Gson instance and then invoking {@link #toJson(Object)} or {@link #fromJson(String, Class)}
 * methods on it.
 *
 * <p>You can create a Gson instance by invoking {@code new Gson()} if the default configuration
 * is all you need. You can also use {@link GsonBuilder} to build a Gson instance with various
 * configuration options such as versioning support, pretty printing, custom
 * {@link JsonSerializer}s, {@link JsonDeserializer}s, and {@link InstanceCreator}.</p>
 *
 * <p>Here is an example of how Gson is used:
 *
 * <pre>
 * Gson gson = new Gson(); // Or use new GsonBuilder().create();
 * MyType target = new MyType();
 * String json = gson.toJson(target); // serializes target to Json
 * MyType target2 = gson.fromJson(MyType.class, json); // deserializes json into target2
 * </pre></p>
 *
 * @author Inderjeet Singh
 * @author Joel Leitch
 */
public final class Gson {

  //TODO(inder): get rid of all the registerXXX methods and take all such parameters in the
  // constructor instead. At the minimum, mark those methods private.

  // Default instances of plug-ins
  static final TypeAdapter DEFAULT_TYPE_ADAPTER =
      new TypeAdapterNotRequired(new PrimitiveTypeAdapter());
  static final ModifierBasedExclusionStrategy DEFAULT_MODIFIER_BASED_EXCLUSION_STRATEGY =
      new ModifierBasedExclusionStrategy(true, new int[] { Modifier.TRANSIENT, Modifier.STATIC });
  static final JsonFormatter DEFAULT_JSON_FORMATTER = new JsonCompactFormatter();
  static final FieldNamingStrategy DEFAULT_NAMING_POLICY =
      new SerializedNameAnnotationInterceptingNamingPolicy(new JavaFieldNamingPolicy());

  private static Logger logger = Logger.getLogger(Gson.class.getName());

  private final ObjectNavigatorFactory navigatorFactory;
  private final MappedObjectConstructor objectConstructor;
  private final TypeAdapter typeAdapter;

  /** Map containing Type or Class objects as keys */
  private final ParameterizedTypeHandlerMap<JsonSerializer<?>> serializers =
      new ParameterizedTypeHandlerMap<JsonSerializer<?>>();

  /** Map containing Type or Class objects as keys */
  private final ParameterizedTypeHandlerMap<JsonDeserializer<?>> deserializers =
      new ParameterizedTypeHandlerMap<JsonDeserializer<?>>();

  private final JsonFormatter formatter;
  private final boolean serializeNulls;

  /**
   * Constructs a Gson object with default configuration.
   */
  public Gson() {
    this(createDefaultObjectNavigatorFactory());
  }

  /**
   * Constructs a Gson object with the specified version and the mode of operation while
   * encountering inner class references.
   *
   * @param factory the object navigator factory to use when creating a new {@link ObjectNavigator}
   * instance.
   */
  Gson(ObjectNavigatorFactory factory) {
    this(factory, new MappedObjectConstructor(), DEFAULT_TYPE_ADAPTER, DEFAULT_JSON_FORMATTER,
        false,
        DefaultJsonSerializers.getDefaultSerializers(),
        DefaultJsonDeserializers.getDefaultDeserializers(),
        DefaultInstanceCreators.getDefaultInstanceCreators());
  }

  Gson(ObjectNavigatorFactory factory, MappedObjectConstructor objectConstructor,
      TypeAdapter typeAdapter, JsonFormatter formatter, boolean serializeNulls,
      Map<Type, JsonSerializer<?>> serializerMap, Map<Type, JsonDeserializer<?>> deserializerMap,
      Map<Type, InstanceCreator<?>> instanceCreatorMap) {
    this.navigatorFactory = factory;
    this.objectConstructor = objectConstructor;
    this.typeAdapter = typeAdapter;
    this.formatter = formatter;
    this.serializeNulls = serializeNulls;

    for (Map.Entry<Type, JsonSerializer<?>> entry : serializerMap.entrySet()) {
      Type typeOfT = entry.getKey();
      if (serializers.hasSpecificHandlerFor(typeOfT)) {
        logger.log(Level.WARNING, "Overriding the existing Serializer for " + typeOfT);
      }
      serializers.register(typeOfT, entry.getValue());
    }

    for (Map.Entry<Type, JsonDeserializer<?>> entry : deserializerMap.entrySet()) {
      Type typeOfT = entry.getKey();
      if (deserializers.hasSpecificHandlerFor(typeOfT)) {
        logger.log(Level.WARNING, "Overriding the existing Deserializer for " + typeOfT);
      }
      deserializers.register(typeOfT, entry.getValue());
    }

    for (Map.Entry<Type, InstanceCreator<?>> entry : instanceCreatorMap.entrySet()) {
      objectConstructor.register(entry.getKey(), entry.getValue());
    }
  }

  private static ObjectNavigatorFactory createDefaultObjectNavigatorFactory() {
    return new ObjectNavigatorFactory(
        createExclusionStrategy(VersionConstants.IGNORE_VERSIONS), DEFAULT_NAMING_POLICY);
  }

  private static ExclusionStrategy createExclusionStrategy(double version) {
    List<ExclusionStrategy> strategies = new LinkedList<ExclusionStrategy>();
    strategies.add(new InnerClassExclusionStrategy());
    strategies.add(DEFAULT_MODIFIER_BASED_EXCLUSION_STRATEGY);
    if (version != VersionConstants.IGNORE_VERSIONS) {
      strategies.add(new VersionExclusionStrategy(version));
    }
    return new DisjunctionExclusionStrategy(strategies);
  }

  /**
   * This method serializes the specified object into its equivalent Json representation.
   * This method should be used when the specified object is not a generic type. This method uses
   * {@link Class#getClass()} to get the type for the specified object, but the
   * {@code getClass()} loses the generic type information because of the Type Erasure feature
   * of Java. Note that this method works fine if the any of the object fields are of generic type,
   * just the object itself should not be of a generic type. If the object is of generic type, use
   * {@link #toJson(Object, Type)} instead. If you want to write out the object to a
   * {@link Writer}, use {@link #toJson(Object, Writer)} instead.
   *
   * @param src the object for which Json representation is to be created setting for Gson.
   * @return Json representation of src.
   */
  public String toJson(Object src) {
    if (src == null) {
      return "";
    }
    return toJson(src, src.getClass());
  }

  /**
   * This method serializes the specified object, including those of generic types, into its
   * equivalent Json representation. This method must be used if the specified object is a generic
   * type. For non-generic objects, use {@link #toJson(Object)} instead. If you want to write out
   * the object to a {@link Writer}, use {@link #toJson(Object, Type, Writer)} instead.
   *
   * @param src the object for which JSON representation is to be created.
   * @param typeOfSrc The specific genericized type of src. You can obtain
   * this type by using the {@link com.google.gson.reflect.TypeToken} class. For example,
   * to get the type for {@code Collection<Foo>}, you should use:
   * <pre>
   * Type typeOfSrc = new TypeToken&lt;Collection&lt;Foo&gt;&gt;(){}.getType();
   * </pre>
   * @return Json representation of src.
   */
  public String toJson(Object src, Type typeOfSrc) {
    StringWriter writer = new StringWriter();
    toJson(src, typeOfSrc, writer);
    return writer.toString();
  }

  /**
   * This method serializes the specified object into its equivalent Json representation.
   * This method should be used when the specified object is not a generic type. This method uses
   * {@link Class#getClass()} to get the type for the specified object, but the
   * {@code getClass()} loses the generic type information because of the Type Erasure feature
   * of Java. Note that this method works fine if the any of the object fields are of generic type,
   * just the object itself should not be of a generic type. If the object is of generic type, use
   * {@link #toJson(Object, Type, Writer)} instead.
   *
   * @param src the object for which Json representation is to be created setting for Gson.
   * @param writer Writer to which the Json representation needs to be written.
   */
  public void toJson(Object src, Writer writer) {
    if (src != null) {
      toJson(src, src.getClass(), writer);
    }
  }

  /**
   * This method serializes the specified object, including those of generic types, into its
   * equivalent Json representation. This method must be used if the specified object is a generic
   * type. For non-generic objects, use {@link #toJson(Object, Writer)} instead.
   *
   * @param src the object for which JSON representation is to be created.
   * @param typeOfSrc The specific genericized type of src. You can obtain
   * this type by using the {@link com.google.gson.reflect.TypeToken} class. For example,
   * to get the type for {@code Collection<Foo>}, you should use:
   * <pre>
   * Type typeOfSrc = new TypeToken&lt;Collection&lt;Foo&gt;&gt;(){}.getType();
   * </pre>
   * @param writer Writer to which the Json representation of src needs to be written.
   */
  public void toJson(Object src, Type typeOfSrc, Writer writer) {
    if (src == null) {
      return;
    }
    JsonSerializationContext context =
        new JsonSerializationContextDefault(navigatorFactory, serializeNulls, serializers);
    JsonElement jsonElement = context.serialize(src, typeOfSrc);

    //TODO(Joel): instead of navigating the "JsonElement" inside the formatter, do it here.
    formatter.format(jsonElement, new PrintWriter(writer), serializeNulls);
  }

  /**
   * This method deserializes the specified Json into an object of the specified class. It is not
   * suitable to use if the specified class is a generic type since it will not have the generic
   * type information because of the Type Erasure feature of Java. Therefore, this method should not
   * be used if the desired type is a generic type. Note that this method works fine if the any of
   * the fields of the specified object are generics, just the object itself should not be a
   * generic type. For the cases when the object is of generic type, invoke
   * {@link #fromJson(String, Type)}. If you have the Json in a {@link Reader} instead of
   * a String, use {@link #fromJson(Reader, Class)} instead.
   *
   * @param json the string from which the object is to be deserialized.
   * @param classOfT the class of T.
   * @param <T> the type of the desired object.
   * @return an object of type T from the string.
   * @throws JsonParseException if json is not a valid representation for an object of type
   * classOfT.
   */
  public <T> T fromJson(String json, Class<T> classOfT) throws JsonParseException {
    T target = fromJson(json, (Type) classOfT);
    return target;
  }

  /**
   * This method deserializes the specified Json into an object of the specified type. This method
   * is useful if the specified object is a generic type. For non-generic objects, use
   * {@link #fromJson(String, Class)} instead. If you have the Json in a {@link Reader} instead of
   * a String, use {@link #fromJson(Reader, Type)} instead.
   *
   * @param json the string from which the object is to be deserialized.
   * @param typeOfT The specific genericized type of src. You can obtain this type by using the
   * {@link com.google.gson.reflect.TypeToken} class. For example, to get the type for
   * {@code Collection<Foo>}, you should use:
   * <pre>
   * Type typeOfT = new TypeToken&lt;Collection&lt;Foo&gt;&gt;(){}.getType();
   * </pre>
   * @param <T> the type of the desired object.
   * @return an object of type T from the string.
   * @throws JsonParseException if json is not a valid representation for an object of type typeOfT.
   */
  public <T> T fromJson(String json, Type typeOfT) throws JsonParseException {
    StringReader reader = new StringReader(json);
    T target = fromJson(reader, typeOfT);
    return target;
  }

  /**
   * This method deserializes the Json read from the specified reader into an object of the
   * specified class. It is not suitable to use if the specified class is a generic type since it
   * will not have the generic type information because of the Type Erasure feature of Java.
   * Therefore, this method should not be used if the desired type is a generic type. Note that
   * this method works fine if the any of the fields of the specified object are generics, just the
   * object itself should not be a generic type. For the cases when the object is of generic type,
   * invoke {@link #fromJson(Reader, Type)}. If you have the Json in a String form instead of a
   * {@link Reader}, use {@link #fromJson(String, Class)} instead.
   *
   * @param json the reader producing the Json from which the object is to be deserialized.
   * @param classOfT the class of T.
   * @param <T> the type of the desired object.
   * @return an object of type T from the string.
   * @throws JsonParseException if json is not a valid representation for an object of type
   * classOfT.
   */
  public <T> T fromJson(Reader json, Class<T> classOfT) throws JsonParseException {
    T target = fromJson(json, (Type) classOfT);
    return target;
  }

  /**
   * This method deserializes the Json read from the specified reader into an object of the
   * specified type. This method is useful if the specified object is a generic type. For
   * non-generic objects, use {@link #fromJson(Reader, Class)} instead. If you have the Json in a
   * String form instead of a {@link Reader}, use {@link #fromJson(String, Type)} instead.
   *
   * @param json the reader producing Json from which the object is to be deserialized.
   * @param typeOfT The specific genericized type of src. You can obtain this type by using the
   * {@link com.google.gson.reflect.TypeToken} class. For example, to get the type for
   * {@code Collection<Foo>}, you should use:
   * <pre>
   * Type typeOfT = new TypeToken&lt;Collection&lt;Foo&gt;&gt;(){}.getType();
   * </pre>
   * @param <T> the type of the desired object.
   * @return an object of type T from the json.
   * @throws JsonParseException if json is not a valid representation for an object of type typeOfT.
   */
  public <T> T fromJson(Reader json, Type typeOfT) throws JsonParseException {
    try {
      JsonParser parser = new JsonParser(json);
      JsonElement root = parser.parse();
      JsonDeserializationContext context = new JsonDeserializationContextDefault(navigatorFactory,
          deserializers, objectConstructor, typeAdapter);
      T target = context.deserialize(root, typeOfT);
      return target;
    } catch (TokenMgrError e) {
      throw new JsonParseException("Failed parsing JSON source: " + json + " to Json", e);
    } catch (ParseException e) {
      throw new JsonParseException("Failed parsing JSON source: " + json + " to Json", e);
    } catch (StackOverflowError e) {
      throw new JsonParseException("Failed parsing JSON source: " + json + " to Json", e);
    } catch (OutOfMemoryError e) {
      throw new JsonParseException("Failed parsing JSON source: " + json + " to Json", e);
    }
  }
}