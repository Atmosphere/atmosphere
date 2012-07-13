package org.atmosphere.jackson;

import org.atmosphere.gwt.server.JSONDeserializer;
import org.atmosphere.gwt.server.JSONSerializer;
import org.atmosphere.gwt.server.spi.JSONSerializerProvider;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize.Inclusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author p.havelaar
 */
public class JacksonSerializerProvider implements JSONSerializerProvider {
    
    static final Logger logger = LoggerFactory.getLogger(JacksonSerializerProvider.class);
    
    ObjectMapper mapper;
    
    public JacksonSerializerProvider() {
        mapper = new ObjectMapper();
        mapper.getSerializationConfig().withSerializationInclusion(Inclusion.NON_NULL);
//        mapper.getSerializationConfig().withClassIntrospector(new BeanIntroSpector());
        
        logger.info("Loaded Jackson JSONSerializerProvider");
    }

//    static class BeanIntroSpector extends BasicClassIntrospector {
//        @Override
//        protected MethodFilter getSerializationMethodFilter(SerializationConfig cfg) {
//            return BeanSerializationMethodFilter.instance;
//        }
//    }
//
//    static class BeanSerializationMethodFilter implements MethodFilter {
//        public final static BeanSerializationMethodFilter instance = new BeanSerializationMethodFilter();
//
//        private MethodFilter getterFilter = BasicClassIntrospector.GetterMethodFilter.instance;
//
//        @Override
//        public boolean includeMethod(Method m) {
//            if (m.getName().startsWith("get") == false && m.getName().startsWith("is") == false) {
//                return false;
//            }
//            return getterFilter.includeMethod(m) && hasMatchingSetter(m);
//        }
//        public static boolean hasMatchingSetter(Method m) {
//            try {
//                int prefix = m.getName().startsWith("is") ? 2 : 3;
//                String settername = "set" + m.getName().substring(prefix);
//                Method setter = m.getDeclaringClass().getMethod(settername, m.getReturnType());
//                return !Modifier.isStatic(setter.getModifiers());
//            } catch (NoSuchMethodException ex) {
//                return false;
//            }
//        }
//    }

    public JSONSerializer getSerializer() {
        return new JacksonSerializer(this);
    }

    public JSONDeserializer getDeserializer() {
        return new JacksonDeserializer(this);
    }
    
}
