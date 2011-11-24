package org.atmosphere.jetty.util.ajax;

import java.io.Externalizable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.atmosphere.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.TypeUtil;

/**
 * JSON Parser and Generator.
 *
 * <p>
 * This class provides some static methods to convert POJOs to and from JSON
 * notation. The mapping from JSON to java is:
 *
 * <pre>
 *   object ==> Map
 *   array  ==> Object[]
 *   number ==> Double or Long
 *   string ==> String
 *   null   ==> null
 *   bool   ==> Boolean
 * </pre>
 *
 * </p>
 * <p>
 * The java to JSON mapping is:
 *
 * <pre>
 *   String --> string
 *   Number --> number
 *   Map    --> object
 *   List   --> array
 *   Array  --> array
 *   null   --> null
 *   Boolean--> boolean
 *   Object --> string (dubious!)
 * </pre>
 *
 * </p>
 * <p>
 * The interface {@link JSON.Convertible} may be implemented by classes that
 * wish to externalize and initialize specific fields to and from JSON objects.
 * Only directed acyclic graphs of objects are supported.
 * </p>
 * <p>
 * The interface {@link JSON.Generator} may be implemented by classes that know
 * how to render themselves as JSON and the {@link #toString(Object)} method
 * will use {@link JSON.Generator#addJSON(Appendable)} to generate the JSON.
 * The class {@link JSON.Literal} may be used to hold pre-generated JSON object.
 * <p>
 * The interface {@link JSON.Convertor} may be implemented to provide static
 * convertors for objects that may be registered with
 * {@link #registerConvertor(Class, org.eclipse.jetty.util.ajax.JSON.Convertor)}
 * . These convertors are looked up by class, interface and super class by
 * {@link #getConvertor(Class)}.
 * </p>
 *
 *
 */
public class JSON
{
    public final static JSON DEFAULT = new JSON();

    private Map<String, Convertor> _convertors = new ConcurrentHashMap<String, Convertor>();
    private int _stringBufferSize = 1024;

    public JSON()
    {
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the initial stringBuffer size to use when creating JSON strings
     *         (default 1024)
     */
    public int getStringBufferSize()
    {
        return _stringBufferSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param stringBufferSize
     *            the initial stringBuffer size to use when creating JSON
     *            strings (default 1024)
     */
    public void setStringBufferSize(int stringBufferSize)
    {
        _stringBufferSize = stringBufferSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Register a {@link Convertor} for a class or interface.
     *
     * @param forClass
     *            The class or interface that the convertor applies to
     * @param convertor
     *            the convertor
     */
    public static void registerConvertor(Class forClass, Convertor convertor)
    {
        DEFAULT.addConvertor(forClass,convertor);
    }

    /* ------------------------------------------------------------ */
    public static JSON getDefault()
    {
        return DEFAULT;
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public static void setDefault(JSON json)
    {
    }

    /* ------------------------------------------------------------ */
    public static String toString(Object object)
    {
        StringBuilder buffer = new StringBuilder(DEFAULT.getStringBufferSize());
        DEFAULT.append(buffer,object);
        return buffer.toString();
    }

    /* ------------------------------------------------------------ */
    public static String toString(Map object)
    {
        StringBuilder buffer = new StringBuilder(DEFAULT.getStringBufferSize());
        DEFAULT.appendMap(buffer,object);
        return buffer.toString();
    }

    /* ------------------------------------------------------------ */
    public static String toString(Object[] array)
    {
        StringBuilder buffer = new StringBuilder(DEFAULT.getStringBufferSize());
        DEFAULT.appendArray(buffer,array);
        return buffer.toString();
    }

    /* ------------------------------------------------------------ */
    /**
     * Convert Object to JSON
     *
     * @param object
     *            The object to convert
     * @return The JSON String
     */
    public String toJSON(Object object)
    {
        StringBuilder buffer = new StringBuilder(getStringBufferSize());
        append(buffer,object);
        return buffer.toString();
    }

    /* ------------------------------------------------------------ */
    /**
     * Append object as JSON to string buffer.
     *
     * @param buffer
     *            the buffer to append to
     * @param object
     *            the object to append
     */
    public void append(Appendable buffer, Object object)
    {
        try
        {
            if (object == null)
                buffer.append("null");
            else if (object instanceof Convertible)
                appendJSON(buffer,(Convertible)object);
            else if (object instanceof Generator)
                appendJSON(buffer,(Generator)object);
            else if (object instanceof Map)
                appendMap(buffer,(Map)object);
            else if (object instanceof Collection)
                appendArray(buffer,(Collection)object);
            else if (object.getClass().isArray())
                appendArray(buffer,object);
            else if (object instanceof Number)
                appendNumber(buffer,(Number)object);
            else if (object instanceof Boolean)
                appendBoolean(buffer,(Boolean)object);
            else if (object instanceof Character)
                appendString(buffer,object.toString());
            else if (object instanceof String)
                appendString(buffer,(String)object);
            else
            {
                Convertor convertor = getConvertor(object.getClass());
                if (convertor != null)
                    appendJSON(buffer,convertor,object);
                else
                    appendString(buffer,object.toString());
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void appendNull(StringBuffer buffer)
    {
        appendNull((Appendable)buffer);
    }

    /* ------------------------------------------------------------ */
    public void appendNull(Appendable buffer)
    {
        try
        {
            buffer.append("null");
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void appendJSON(final StringBuffer buffer, final Convertor convertor, final Object object)
    {
        appendJSON((Appendable)buffer,convertor,object);
    }

    /* ------------------------------------------------------------ */
    public void appendJSON(final Appendable buffer, final Convertor convertor, final Object object)
    {
        appendJSON(buffer,new Convertible()
        {
            public void fromJSON(Map object)
            {
            }

            public void toJSON(Output out)
            {
                convertor.toJSON(object,out);
            }
        });
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void appendJSON(final StringBuffer buffer, Convertible converter)
    {
        appendJSON((StringBuffer)buffer,converter);
    }

    /* ------------------------------------------------------------ */
    public void appendJSON(final Appendable buffer, Convertible converter)
    {
        ConvertableOutput out=new ConvertableOutput(buffer);
        converter.toJSON(out);
        out.complete();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void appendJSON(StringBuffer buffer, Generator generator)
    {
        generator.addJSON(buffer);
    }

    /* ------------------------------------------------------------ */
    public void appendJSON(Appendable buffer, Generator generator)
    {
        generator.addJSON(buffer);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void appendMap(StringBuffer buffer, Map<?,?> map)
    {
        appendMap((Appendable)buffer,map);
    }

    /* ------------------------------------------------------------ */
    public void appendMap(Appendable buffer, Map<?,?> map)
    {
        try
        {
            if (map == null)
            {
                appendNull(buffer);
                return;
            }

            buffer.append('{');
            Iterator<?> iter = map.entrySet().iterator();
            while (iter.hasNext())
            {
                Map.Entry<?,?> entry = (Map.Entry<?,?>)iter.next();
                QuotedStringTokenizer.quote(buffer,entry.getKey().toString());
                buffer.append(':');
                append(buffer,entry.getValue());
                if (iter.hasNext())
                    buffer.append(',');
            }

            buffer.append('}');
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void appendArray(StringBuffer buffer, Collection collection)
    {
    	appendArray((Appendable)buffer,collection);
    }

    /* ------------------------------------------------------------ */
    public void appendArray(Appendable buffer, Collection collection)
    {
        try
        {
            if (collection == null)
            {
                appendNull(buffer);
                return;
            }

            buffer.append('[');
            Iterator iter = collection.iterator();
            boolean first = true;
            while (iter.hasNext())
            {
                if (!first)
                    buffer.append(',');

                first = false;
                append(buffer,iter.next());
            }

            buffer.append(']');
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void appendArray(StringBuffer buffer, Object array)
    {
	appendArray((Appendable)buffer,array);
    }

    /* ------------------------------------------------------------ */
    public void appendArray(Appendable buffer, Object array)
    {
        try
        {
            if (array == null)
            {
                appendNull(buffer);
                return;
            }

            buffer.append('[');
            int length = Array.getLength(array);

            for (int i = 0; i < length; i++)
            {
                if (i != 0)
                    buffer.append(',');
                append(buffer,Array.get(array,i));
            }

            buffer.append(']');
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void appendBoolean(StringBuffer buffer, Boolean b)
    {
        appendBoolean((Appendable)buffer,b);
    }

    /* ------------------------------------------------------------ */
    public void appendBoolean(Appendable buffer, Boolean b)
    {
        try
        {
            if (b == null)
            {
                appendNull(buffer);
                return;
            }
            buffer.append(b.booleanValue()?"true":"false");
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void appendNumber(StringBuffer buffer, Number number)
    {
	appendNumber((Appendable)buffer,number);
    }

    /* ------------------------------------------------------------ */
    public void appendNumber(Appendable buffer, Number number)
    {
        try
        {
            if (number == null)
            {
                appendNull(buffer);
                return;
            }
            buffer.append(String.valueOf(number));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void appendString(StringBuffer buffer, String string)
    {
    	appendString((Appendable)buffer,string);
    }

    /* ------------------------------------------------------------ */
    public void appendString(Appendable buffer, String string)
    {
        if (string == null)
        {
            appendNull(buffer);
            return;
        }

        QuotedStringTokenizer.quote(buffer,string);
    }

    // Parsing utilities

    /* ------------------------------------------------------------ */
    protected String toString(char[] buffer, int offset, int length)
    {
        return new String(buffer,offset,length);
    }

    /* ------------------------------------------------------------ */
    protected Map<String, Object> newMap()
    {
        return new HashMap<String, Object>();
    }

    /* ------------------------------------------------------------ */
    protected Object[] newArray(int size)
    {
        return new Object[size];
    }

    /* ------------------------------------------------------------ */
    protected JSON contextForArray()
    {
        return this;
    }

    /* ------------------------------------------------------------ */
    protected JSON contextFor(String field)
    {
        return this;
    }

    /* ------------------------------------------------------------ */
    protected Object convertTo(Class type, Map map)
    {
        if (type != null && Convertible.class.isAssignableFrom(type))
        {
            try
            {
                Convertible conv = (Convertible)type.newInstance();
                conv.fromJSON(map);
                return conv;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        Convertor convertor = getConvertor(type);
        if (convertor != null)
        {
            return convertor.fromJSON(map);
        }
        return map;
    }

    /* ------------------------------------------------------------ */
    /**
     * Register a {@link Convertor} for a class or interface.
     *
     * @param forClass
     *            The class or interface that the convertor applies to
     * @param convertor
     *            the convertor
     */
    public void addConvertor(Class forClass, Convertor convertor)
    {
        _convertors.put(forClass.getName(),convertor);
    }

    /* ------------------------------------------------------------ */
    /**
     * Lookup a convertor for a class.
     * <p>
     * If no match is found for the class, then the interfaces for the class are
     * tried. If still no match is found, then the super class and it's
     * interfaces are tried recursively.
     *
     * @param forClass
     *            The class
     * @return a {@link JSON.Convertor} or null if none were found.
     */
    protected Convertor getConvertor(Class forClass)
    {
        Class cls = forClass;
        Convertor convertor = _convertors.get(cls.getName());
        if (convertor == null && this != DEFAULT)
            convertor = DEFAULT.getConvertor(cls);

        while (convertor == null && cls != null && cls != Object.class)
        {
            Class[] ifs = cls.getInterfaces();
            int i = 0;
            while (convertor == null && ifs != null && i < ifs.length)
                convertor = _convertors.get(ifs[i++].getName());
            if (convertor == null)
            {
                cls = cls.getSuperclass();
                convertor = _convertors.get(cls.getName());
            }
        }
        return convertor;
    }

    /* ------------------------------------------------------------ */
    /**
     * Register a {@link JSON.Convertor} for a named class or interface.
     *
     * @param name
     *            name of a class or an interface that the convertor applies to
     * @param convertor
     *            the convertor
     */
    public void addConvertorFor(String name, Convertor convertor)
    {
        _convertors.put(name,convertor);
    }

    /* ------------------------------------------------------------ */
    /**
     * Lookup a convertor for a named class.
     *
     * @param name
     *            name of the class
     * @return a {@link JSON.Convertor} or null if none were found.
     */
    public Convertor getConvertorFor(String name)
    {
        String clsName = name;
        Convertor convertor = _convertors.get(clsName);
        if (convertor == null && this != DEFAULT)
            convertor = DEFAULT.getConvertorFor(clsName);
        return convertor;
    }

    /* ------------------------------------------------------------ */
    protected Object handleUnknown(Source source, char c)
    {
        throw new IllegalStateException("unknown char '" + c + "'(" + (int)c + ") in " + source);
    }


    /* ------------------------------------------------------------ */
    protected String parseString(Source source)
    {
        if (source.next() != '"')
            throw new IllegalStateException();

        boolean escape = false;

        StringBuilder b = null;
        final char[] scratch = source.scratchBuffer();

        if (scratch != null)
        {
            int i = 0;
            while (source.hasNext())
            {
                if (i >= scratch.length)
                {
                    // we have filled the scratch buffer, so we must
                    // use the StringBuffer for a large string
                    b = new StringBuilder(scratch.length * 2);
                    b.append(scratch,0,i);
                    break;
                }

                char c = source.next();

                if (escape)
                {
                    escape = false;
                    switch (c)
                    {
                        case '"':
                            scratch[i++] = '"';
                            break;
                        case '\\':
                            scratch[i++] = '\\';
                            break;
                        case '/':
                            scratch[i++] = '/';
                            break;
                        case 'b':
                            scratch[i++] = '\b';
                            break;
                        case 'f':
                            scratch[i++] = '\f';
                            break;
                        case 'n':
                            scratch[i++] = '\n';
                            break;
                        case 'r':
                            scratch[i++] = '\r';
                            break;
                        case 't':
                            scratch[i++] = '\t';
                            break;
                        case 'u':
                            char uc = (char)((TypeUtil.convertHexDigit((byte)source.next()) << 12) + (TypeUtil.convertHexDigit((byte)source.next()) << 8)
                                    + (TypeUtil.convertHexDigit((byte)source.next()) << 4) + (TypeUtil.convertHexDigit((byte)source.next())));
                            scratch[i++] = uc;
                            break;
                        default:
                            scratch[i++] = c;
                    }
                }
                else if (c == '\\')
                {
                    escape = true;
                    continue;
                }
                else if (c == '\"')
                {
                    // Return string that fits within scratch buffer
                    return toString(scratch,0,i);
                }
                else
                    scratch[i++] = c;
            }

            // Missing end quote, but return string anyway ?
            if (b == null)
                return toString(scratch,0,i);
        }
        else
            b = new StringBuilder(getStringBufferSize());

        // parse large string into string buffer
        final StringBuilder builder=b;
        while (source.hasNext())
        {
            char c = source.next();

            if (escape)
            {
                escape = false;
                switch (c)
                {
                    case '"':
                        builder.append('"');
                        break;
                    case '\\':
                        builder.append('\\');
                        break;
                    case '/':
                        builder.append('/');
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    case 'u':
                        char uc = (char)((TypeUtil.convertHexDigit((byte)source.next()) << 12) + (TypeUtil.convertHexDigit((byte)source.next()) << 8)
                                + (TypeUtil.convertHexDigit((byte)source.next()) << 4) + (TypeUtil.convertHexDigit((byte)source.next())));
                        builder.append(uc);
                        break;
                    default:
                        builder.append(c);
                }
            }
            else if (c == '\\')
            {
                escape = true;
                continue;
            }
            else if (c == '\"')
                break;
            else
                builder.append(c);
        }
        return builder.toString();
    }

    /* ------------------------------------------------------------ */
    protected void seekTo(char seek, Source source)
    {
        while (source.hasNext())
        {
            char c = source.peek();
            if (c == seek)
                return;

            if (!Character.isWhitespace(c))
                throw new IllegalStateException("Unexpected '" + c + " while seeking '" + seek + "'");
            source.next();
        }

        throw new IllegalStateException("Expected '" + seek + "'");
    }

    /* ------------------------------------------------------------ */
    protected char seekTo(String seek, Source source)
    {
        while (source.hasNext())
        {
            char c = source.peek();
            if (seek.indexOf(c) >= 0)
            {
                return c;
            }

            if (!Character.isWhitespace(c))
                throw new IllegalStateException("Unexpected '" + c + "' while seeking one of '" + seek + "'");
            source.next();
        }

        throw new IllegalStateException("Expected one of '" + seek + "'");
    }

    /* ------------------------------------------------------------ */
    protected static void complete(String seek, Source source)
    {
        int i = 0;
        while (source.hasNext() && i < seek.length())
        {
            char c = source.next();
            if (c != seek.charAt(i++))
                throw new IllegalStateException("Unexpected '" + c + " while seeking  \"" + seek + "\"");
        }

        if (i < seek.length())
            throw new IllegalStateException("Expected \"" + seek + "\"");
    }

    private final class ConvertableOutput implements Output
    {
        private final Appendable _buffer;
        char c = '{';

        private ConvertableOutput(Appendable buffer)
        {
            _buffer = buffer;
        }

        public void complete()
        {
            try
            {
                if (c == '{')
                    _buffer.append("{}");
                else if (c != 0)
                    _buffer.append("}");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void add(Object obj)
        {
            if (c == 0)
                throw new IllegalStateException();
            append(_buffer,obj);
            c = 0;
        }

        public void addClass(Class type)
        {
            try
            {
                if (c == 0)
                    throw new IllegalStateException();
                _buffer.append(c);
                _buffer.append("\"class\":");
                append(_buffer,type.getName());
                c = ',';
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void add(String name, Object value)
        {
            try
            {
                if (c == 0)
                    throw new IllegalStateException();
                _buffer.append(c);
                QuotedStringTokenizer.quote(_buffer,name);
                _buffer.append(':');
                append(_buffer,value);
                c = ',';
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void add(String name, double value)
        {
            try
            {
                if (c == 0)
                    throw new IllegalStateException();
                _buffer.append(c);
                QuotedStringTokenizer.quote(_buffer,name);
                _buffer.append(':');
                appendNumber(_buffer,new Double(value));
                c = ',';
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void add(String name, long value)
        {
            try
            {
                if (c == 0)
                    throw new IllegalStateException();
                _buffer.append(c);
                QuotedStringTokenizer.quote(_buffer,name);
                _buffer.append(':');
                appendNumber(_buffer, value);
                c = ',';
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void add(String name, boolean value)
        {
            try
            {
                if (c == 0)
                    throw new IllegalStateException();
                _buffer.append(c);
                QuotedStringTokenizer.quote(_buffer,name);
                _buffer.append(':');
                appendBoolean(_buffer,value?Boolean.TRUE:Boolean.FALSE);
                c = ',';
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public interface Source
    {
        boolean hasNext();

        char next();

        char peek();

        char[] scratchBuffer();
    }

    /* ------------------------------------------------------------ */
    public static class StringSource implements Source
    {
        private final String string;
        private int index;
        private char[] scratch;

        public StringSource(String s)
        {
            string = s;
        }

        public boolean hasNext()
        {
            if (index < string.length())
                return true;
            scratch = null;
            return false;
        }

        public char next()
        {
            return string.charAt(index++);
        }

        public char peek()
        {
            return string.charAt(index);
        }

        @Override
        public String toString()
        {
            return string.substring(0,index) + "|||" + string.substring(index);
        }

        public char[] scratchBuffer()
        {
            if (scratch == null)
                scratch = new char[string.length()];
            return scratch;
        }
    }

    /* ------------------------------------------------------------ */
    public static class ReaderSource implements Source
    {
        private Reader _reader;
        private int _next = -1;
        private char[] scratch;

        public ReaderSource(Reader r)
        {
            _reader = r;
        }

        public void setReader(Reader reader)
        {
            _reader = reader;
            _next = -1;
        }

        public boolean hasNext()
        {
            getNext();
            if (_next < 0)
            {
                scratch = null;
                return false;
            }
            return true;
        }

        public char next()
        {
            getNext();
            char c = (char)_next;
            _next = -1;
            return c;
        }

        public char peek()
        {
            getNext();
            return (char)_next;
        }

        private void getNext()
        {
            if (_next < 0)
            {
                try
                {
                    _next = _reader.read();
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }
        }

        public char[] scratchBuffer()
        {
            if (scratch == null)
                scratch = new char[1024];
            return scratch;
        }

    }

    /* ------------------------------------------------------------ */
    /**
     * JSON Output class for use by {@link Convertible}.
     */
    public interface Output
    {
        public void addClass(Class c);

        public void add(Object obj);

        public void add(String name, Object value);

        public void add(String name, double value);

        public void add(String name, long value);

        public void add(String name, boolean value);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * JSON Convertible object. Object can implement this interface in a similar
     * way to the {@link Externalizable} interface is used to allow classes to
     * provide their own serialization mechanism.
     * <p>
     * A JSON.Convertible object may be written to a JSONObject or initialized
     * from a Map of field names to values.
     * <p>
     * If the JSON is to be convertible back to an Object, then the method
     * {@link Output#addClass(Class)} must be called from within toJSON()
     *
     */
    public interface Convertible
    {
        public void toJSON(Output out);

        public void fromJSON(Map object);
    }

    /* ------------------------------------------------------------ */
    /**
     * Static JSON Convertor.
     * <p>
     * may be implemented to provide static convertors for objects that may be
     * registered with
     * {@link JSON#registerConvertor(Class, org.eclipse.jetty.util.ajax.JSON.Convertor)}
     * . These convertors are looked up by class, interface and super class by
     * {@link JSON#getConvertor(Class)}. Convertors should be used when the
     * classes to be converted cannot implement {@link Convertible} or
     * {@link Generator}.
     */
    public interface Convertor
    {
        public void toJSON(Object obj, Output out);

        public Object fromJSON(Map object);
    }

    /* ------------------------------------------------------------ */
    /**
     * JSON Generator. A class that can add it's JSON representation directly to
     * a StringBuffer. This is useful for object instances that are frequently
     * converted and wish to avoid multiple Conversions
     */
    public interface Generator
    {
        public void addJSON(Appendable buffer);
    }

}

